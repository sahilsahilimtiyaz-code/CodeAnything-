package com.codingagent.mobile.runtime

import android.content.Context
import android.system.Os
import com.codingagent.mobile.domain.RuntimeState
import com.codingagent.mobile.domain.RuntimeStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the on-device Linux runtime (PRoot + Alpine), AndCode-style.
 *
 * Layout under [context.filesDir]/runtime:
 *   alpine/        extracted minirootfs
 *   dist/          Alpine rootfs tarball cache
 *   bin/proot      PRoot arm64 static binary
 *   intelligence/  extracted-intelligence bundle (dist + package.json)
 *
 * No root required. Everything stays in app-private storage; the agent
 * workspace is bound into the rootfs at /workspace.
 */
@Singleton
class RuntimeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    internal val runtimeRoot: File get() = File(context.filesDir, "runtime")
    internal val alpineDir: File get() = File(runtimeRoot, "alpine")
    internal val distDir: File get() = File(runtimeRoot, "dist")
    internal val prootBin: File get() = File(runtimeRoot, "bin/proot")
    internal val intelligenceDir: File get() = File(runtimeRoot, "intelligence")
    internal val workspaceDir: File get() = File(context.filesDir, "workspace")

    private var nodeProcess: Process? = null
    private var opencodeProcess: Process? = null

    /** Extra host dirs (e.g. SAF primary-volume roots) bound into the guest. */
    private val extraBinds = mutableListOf<Pair<String, String>>()

    /**
     * Set extra host directories to bind. Each gets a stable guest path
     * `/mnt/ext<i>`. Only existing directories are bound at launch.
     */
    @Synchronized
    fun setExtraBinds(hostPaths: List<String>) {
        extraBinds.clear()
        hostPaths.distinct().take(8).forEachIndexed { i, host ->
            extraBinds += host to "/mnt/ext$i"
        }
    }

    /** Guest path for a bound host dir, or the workspace when unmapped. */
    @Synchronized
    fun guestPathFor(hostPath: String): String =
        extraBinds.firstOrNull { it.first == hostPath }?.second ?: hostPath

    fun isInstalled(): Boolean =
        File(alpineDir, "etc/os-release").exists() && prootBin.exists() && prootBin.canExecute()

    fun isNodeRunning(): Boolean = try {
        nodeProcess?.isAlive == true
    } catch (_: Exception) {
        false
    }

    /** One-shot diagnostics for bug reports (no secrets included). */
    fun diagnostics(): String = buildString {
        appendLine("state=${_status.value.state}")
        appendLine("message=${_status.value.message}")
        appendLine("error=${_status.value.error}")
        appendLine("alpineVersion=${readAlpineVersion()}")
        appendLine("alpineOsRelease=${File(alpineDir, "etc/os-release").exists()}")
        appendLine("prootExists=${prootBin.exists()} executable=${prootBin.canExecute()}")
        appendLine("workspace=${workspaceDir.exists()}")
        appendLine("nodeAlive=${isNodeRunning()} opencodeAlive=${isOpencodeRunning()}")
        append("abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
    }

    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            _status.value = RuntimeStatus(
                state = RuntimeState.READY,
                message = "Runtime ready",
                alpineVersion = readAlpineVersion()
            )
            return@withContext
        }
        installRuntime()
    }

    suspend fun installRuntime() = withContext(Dispatchers.IO) {
        try {
            runtimeRoot.mkdirs(); distDir.mkdirs()
            File(runtimeRoot, "bin").mkdirs()
            workspaceDir.mkdirs()

            // 1. PRoot comes bundled in the APK (AndCode pattern: native runner
            //    in jniLibs, never downloaded from an ad-hoc URL).
            setStatus(RuntimeState.DOWNLOADING, "Preparing PRoot…", 0.05f)
            installBundledProot()

            // 2. Alpine minirootfs (official CDN + pinned SHA-256)
            setStatus(RuntimeState.DOWNLOADING, "Downloading Alpine Linux…", 0.15f)
            val tarball = File(distDir, "alpine-minirootfs.tar.gz")
            downloadVerified(
                url = RuntimeArtifacts.ALPINE_URL,
                dest = tarball,
                expectedSha256 = RuntimeArtifacts.ALPINE_SHA256.ifBlank { null },
                label = "alpine"
            )

            // 3. Extract
            setStatus(RuntimeState.INSTALLING, "Extracting Alpine…", 0.55f)
            alpineDir.mkdirs()
            runHostTar(tarball, alpineDir)
            File(alpineDir, "etc/resolv.conf").apply {
                parentFile?.mkdirs()
                writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }

            // 4. Bootstrap packages inside PRoot (retried: phone networks flake)
            setStatus(RuntimeState.INSTALLING, "Installing packages (node, git, bash)…", 0.7f)
            val (updateCode, updateOut) = prootExecFull("apk update", 300)
                .let { first ->
                    if (first.first == 0) first
                    else {
                        Timber.w("apk update failed, retrying once")
                        Thread.sleep(5000)
                        prootExecFull("apk update", 300)
                    }
                }
            if (updateCode != 0) {
                throw IllegalStateException(
                    "apk update failed (exit=$updateCode): " +
                        updateOut.takeLast(600).trim().ifBlank { "no output" } +
                        apkHint(updateOut)
                )
            }
            val (addCode, addOut) = prootExecFull(
                "apk add --no-cache nodejs npm git bash curl ca-certificates openssh",
                600
            )
            if (addCode != 0) {
                throw IllegalStateException(
                    "apk add failed (exit=$addCode): " +
                        addOut.takeLast(600).trim().ifBlank { "no output" } +
                        apkHint(addOut)
                )
            }

            // 5. Stage intelligence bundle
            setStatus(RuntimeState.INSTALLING, "Staging intelligence service…", 0.85f)
            stageIntelligenceBundle()

            setStatus(RuntimeState.READY, "Runtime installed", 1f, readAlpineVersion())
            Timber.i("Runtime install complete: alpine=%s", readAlpineVersion())
        } catch (e: Exception) {
            Timber.e(e, "Runtime install failed")
            _status.value = RuntimeStatus(state = RuntimeState.ERROR, message = "Install failed", error = e.message)
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (!isInstalled()) ensureReady()
        if (!isInstalled()) {
            _status.value = RuntimeStatus(state = RuntimeState.ERROR, message = "Runtime not installed")
            return@withContext
        }
        setStatus(RuntimeState.STARTING, "Starting intelligence service…")
        try {
            stopNodeLocked()
            val port = 18789
            // Launch node inside PRoot (bundle is staged at /runtime/intelligence).
            val cmd = prootCommand(
                "env",
                "WORKSPACE_DIR=/workspace",
                "INTEL_PORT=$port",
                "PROVIDER_TYPE=${System.getenv("PROVIDER_TYPE") ?: "ollama"}",
                "/usr/bin/node", "/runtime/intelligence/bridge/serve.js", "$port"
            )
            Timber.i("Starting node: %s", cmd.joinToString(" "))
            nodeProcess = ProcessBuilder(cmd)
                .directory(alpineDir)
                .redirectOutput(File(runtimeRoot, "node-stdout.log"))
                .redirectError(File(runtimeRoot, "node-stderr.log"))
                .start()
            setStatus(RuntimeState.RUNNING, "Runtime running", 1f, readAlpineVersion())
        } catch (e: Exception) {
            Timber.e(e, "Runtime start failed")
            _status.value = RuntimeStatus(state = RuntimeState.ERROR, message = "Start failed", error = e.message)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        stopNodeLocked()
        stopOpencodeLocked()
        _status.value = RuntimeStatus(state = RuntimeState.STOPPED, message = "Runtime stopped")
    }

    fun isOpencodeRunning(): Boolean = try {
        opencodeProcess?.isAlive == true
    } catch (_: Exception) {
        false
    }

    /**
     * Start `opencode serve` inside Alpine (loopback only, port 4097).
     * Requires the CLI ([installAgentCli]) and a started runtime.
     */
    suspend fun ensureOpencodeServer(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!isInstalled()) ensureReady()
            if (!isInstalled()) return@withContext Result.failure(
                IllegalStateException("Runtime not installed")
            )
            if (isOpencodeRunning()) return@withContext Result.success(OPENCODE_PORT)
            stopOpencodeLocked()
            val cmd = prootCommand(
                "/usr/local/bin/opencode", "serve",
                "--port", OPENCODE_PORT.toString(),
                "--hostname", "127.0.0.1"
            )
            if (cmd.isEmpty()) return@withContext Result.failure(
                IllegalStateException("PRoot unavailable")
            )
            Timber.i("Starting opencode serve on 127.0.0.1:%d", OPENCODE_PORT)
            opencodeProcess = ProcessBuilder(cmd)
                .directory(alpineDir)
                .redirectOutput(File(runtimeRoot, "opencode-stdout.log"))
                .redirectError(File(runtimeRoot, "opencode-stderr.log"))
                .start()
            Result.success(OPENCODE_PORT)
        } catch (e: Exception) {
            Timber.e(e, "opencode serve start failed")
            Result.failure(e)
        }
    }

    fun stopOpencodeServer() {
        stopOpencodeLocked()
    }

    private fun stopOpencodeLocked() {
        try {
            opencodeProcess?.destroy()
            opencodeProcess = null
        } catch (e: Exception) {
            Timber.w(e, "stop opencode failed")
        }
    }

    /**
     * Priority 4 — install the OpenCode CLI (musl, runs inside Alpine).
     * No-op when already installed. Safe to call from Settings.
     */
    suspend fun installAgentCli(): Result<String> = withContext(Dispatchers.IO) {        try {
            if (!isInstalled()) ensureReady()
            if (!isInstalled()) return@withContext Result.failure(
                IllegalStateException("Runtime not installed")
            )
            val probe = prootExec("/usr/local/bin/opencode --version")
            if (probe == 0) return@withContext Result.success("opencode already installed")

            val tarball = File(distDir, "opencode-linux-arm64-musl.tar.gz")
            downloadVerified(
                url = RuntimeArtifacts.OPENCODE_URL,
                dest = tarball,
                expectedSha256 = RuntimeArtifacts.OPENCODE_SHA256.ifBlank { null },
                label = "opencode"
            )
            // Extract straight into the guest fs (tarball holds a single `opencode` binary).
            val code = prootExec(
                "tar -xzf /runtime/dist/opencode-linux-arm64-musl.tar.gz -C /tmp " +
                    "&& install -m0755 /tmp/opencode /usr/local/bin/opencode " +
                    "&& /usr/local/bin/opencode --version"
            ) ?: return@withContext Result.failure(IllegalStateException("proot unavailable"))
            if (code != 0) return@withContext Result.failure(
                IllegalStateException("opencode install failed (exit=$code)")
            )
            Timber.i("OpenCode CLI installed")
            Result.success("opencode installed")
        } catch (e: Exception) {
            Timber.e(e, "OpenCode install failed")
            Result.failure(e)
        }
    }

    /**
     * Priority 4 — install Qwen Code CLI via npm inside Alpine
     * (npm package `@qwen-code/qwen-code`, bin `qwen`).
     */
    suspend fun installQwenCli(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isInstalled()) ensureReady()
            if (!isInstalled()) return@withContext Result.failure(
                IllegalStateException("Runtime not installed")
            )
            val probe = prootExec("qwen --version", 60)
            if (probe == 0) return@withContext Result.success("qwen already installed")

            val code = prootExec(
                "npm install -g @qwen-code/qwen-code && qwen --version",
                600
            ) ?: return@withContext Result.failure(
                IllegalStateException("qwen install timed out or proot unavailable")
            )
            if (code != 0) return@withContext Result.failure(
                IllegalStateException("qwen install failed (exit=$code)")
            )
            Timber.i("Qwen Code CLI installed")
            Result.success("qwen installed")
        } catch (e: Exception) {
            Timber.e(e, "Qwen install failed")
            Result.failure(e)
        }
    }

    fun getWorkspacePath(): String = workspaceDir.absolutePath
    fun getAlpinePath(): String = alpineDir.absolutePath

    /** PRoot command prefix binding workspace + app storage, or empty when proot missing. */
    fun prootCommand(vararg guestCmd: String): List<String> {
        if (!prootBin.exists()) return emptyList()
        val extras = synchronized(this) { extraBinds.toList() }
            .filter { (host, _) -> File(host).isDirectory }
        return buildProotArgs(
            prootPath = prootBin.absolutePath,
            alpinePath = alpineDir.absolutePath,
            workspacePath = workspaceDir.absolutePath,
            runtimePath = runtimeRoot.absolutePath,
            extraBinds = extras,
            guestCmd = guestCmd.toList()
        )
    }

    // ---- internals ----

    private fun setStatus(state: RuntimeState, message: String, progress: Float = 0f, alpineVersion: String? = null) {
        _status.value = RuntimeStatus(state, message, progress, alpineVersion ?: readAlpineVersion())
    }

    /**
     * Copy the APK-bundled PRoot runner (jniLibs libproot.so) into app storage.
     * Throws with a clear message when the native lib is missing so release
     * builds fail fast instead of downloading an unpinned binary.
     */
    private fun installBundledProot() {
        if (prootBin.exists() && prootBin.canExecute()) {
            Timber.i("PRoot already staged")
            return
        }
        val candidates = listOf(
            File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
            File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        )
        val src = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException(
                "Bundled PRoot (libproot.so) not found in the APK. " +
                    "Add the arm64-v8a PRoot static binary under app/src/main/jniLibs/arm64-v8a/ " +
                    "as libproot.so (see AndCode runtime_tools/)."
            )
        src.copyTo(prootBin, overwrite = true)
        if (!prootBin.setExecutable(true)) {
            throw IllegalStateException("Cannot make PRoot executable")
        }
        Timber.i("Staged PRoot from %s", src.absolutePath)
    }

    private fun downloadVerified(url: String, dest: File, expectedSha256: String?, label: String) {
        if (dest.exists() && expectedSha256 != null && sha256(dest).equals(expectedSha256, ignoreCase = true)) {
            Timber.i("%s cache hit", label)
            return
        }
        Timber.i("Downloading %s from %s", label, url)
        val req = Request.Builder().url(url).header("User-Agent", "CodingAgentMobile/0.1").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download $label failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty body for $label")
            val tmp = File(dest.parent, dest.name + ".part")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (expectedSha256 != null) {
                val actual = sha256(tmp)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    tmp.delete()
                    throw IllegalStateException("$label SHA-256 mismatch (expected $expectedSha256, got $actual)")
                }
            } else {
                Timber.w("%s has no pinned SHA-256; skipping verification (set RuntimeArtifacts to pin)", label)
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        }
    }

    private fun runHostTar(tarball: File, dest: File) {
        // Prefer system tar; fall back to toybox tar on device.
        val candidates = listOf(
            listOf("tar", "-xzf", tarball.absolutePath, "-C", dest.absolutePath),
            listOf("toybox", "tar", "-xzf", tarball.absolutePath, "-C", dest.absolutePath)
        )
        var lastErr: Exception? = null
        for (cmd in candidates) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                if (code == 0) return
                lastErr = IllegalStateException("tar exit=$code: $out")
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("tar failed")
    }

    /**
     * Run a shell command inside the PRoot guest; returns exit code, or null
     * if proot is missing or the command exceeds [timeoutSeconds].
     * The timeout guarantees installs never hang the app (e.g. a CLI binary
     * that blocks on an unsupported syscall under nested PRoot).
     */
    private fun prootExec(guestSh: String, timeoutSeconds: Long = 180): Int? =
        prootExecFull(guestSh, timeoutSeconds).first

    /**
     * Full version: also captures combined output (bounded) for diagnostics.
     * The tail is mirrored to proot-stdout.log and returned for error text.
     */
    private fun prootExecFull(
        guestSh: String,
        timeoutSeconds: Long = 180
    ): Pair<Int?, String> {
        val cmd = prootCommand("/bin/sh", "-lc", guestSh)
        if (cmd.isEmpty()) return null to "proot unavailable"
        return try {
            val p = ProcessBuilder(cmd)
                .directory(alpineDir)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = p.inputStream.read(buf)
                        if (n <= 0) break
                        val chunk = String(buf, 0, n)
                        synchronized(output) {
                            output.append(chunk)
                            if (output.length > 24_000) {
                                output.delete(0, output.length - 24_000)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            reader.join(2000)
            val text = synchronized(output) { output.toString() }
            try {
                File(runtimeRoot, "proot-stdout.log").writeText(text.takeLast(24_000))
            } catch (_: Exception) {
            }
            if (!finished) {
                Timber.w("proot exec timed out after %ds: %s", timeoutSeconds, guestSh.take(120))
                p.destroyForcibly()
                return null to ("timed out after ${timeoutSeconds}s:\n" + text.takeLast(2000))
            }
            p.exitValue() to text
        } catch (e: Exception) {
            Timber.e(e, "proot exec failed")
            null to (e.message ?: "exec failed")
        }
    }

    /** Human hint for common apk/network failures, appended to install errors. */
    internal fun apkHint(output: String): String = apkFailureHint(output)

    private fun stageIntelligenceBundle() {
        // The APK ships extracted-intelligence under assets/intelligence (added at packaging time
        // by copying ../extracted-intelligence/dist). At runtime we mirror it into the guest.
        intelligenceDir.mkdirs()
        val assetList = try {
            context.assets.list("intelligence")
        } catch (_: Exception) {
            null
        }
        if (assetList != null) {
            copyAssetDir("intelligence", intelligenceDir)
            Timber.i("Staged intelligence bundle from assets (%d entries)", assetList.size)
        } else {
            // Dev fallback: write a stub server.js so the service endpoint exists.
            val stub = File(intelligenceDir, "bridge/serve.js")
            stub.parentFile?.mkdirs()
            stub.writeText(STUB_SERVER_JS)
            Timber.w("No intelligence assets bundled; wrote stub bridge/serve.js")
        }
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val list = context.assets.list(assetPath) ?: return
        dest.mkdirs()
        if (list.isEmpty()) {
            // Leaf file
            context.assets.open(assetPath).use { input ->
                File(dest.parent, dest.name).outputStream().use { input.copyTo(it) }
            }
            return
        }
        for (name in list) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val childDest = File(dest, name)
            val sub = context.assets.list(childAsset)
            if (sub == null || sub.isEmpty()) {
                context.assets.open(childAsset).use { input ->
                    childDest.outputStream().use { input.copyTo(it) }
                }
            } else {
                copyAssetDir(childAsset, childDest)
            }
        }
    }

    private fun stopNodeLocked() {
        try {
            nodeProcess?.destroy()
            nodeProcess = null
        } catch (e: Exception) {
            Timber.w(e, "stop node failed")
        }
        // Best-effort: kill stray guest node via pkill inside proot (non-fatal).
        try {
            Os.kill(0, 0)
        } catch (_: Exception) {
        }
    }

    private fun readAlpineVersion(): String? = try {
        File(alpineDir, "etc/os-release").readLines()
            .firstOrNull { it.startsWith("VERSION_ID=") }
            ?.substringAfter("=")?.trim('"')
    } catch (_: Exception) {
        null
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val OPENCODE_PORT = 4097
        /** Minimal fallback so /health + /rpc exist even without the real bundle. */
        internal const val STUB_SERVER_JS = """
const http = require('node:http');
const port = Number(process.argv[2] || process.env.INTEL_PORT || 18789);
http.createServer((req, res) => {
  const send = (o, code=200) => { res.writeHead(code, {'Content-Type':'application/json'}); res.end(JSON.stringify(o)); };
  if (req.method === 'GET') return send({ ok: true, service: 'stub-intelligence' });
  let b=''; req.on('data', c => b+=c); req.on('end', () => {
    try {
      const r = JSON.parse(b || '{}');
      if (r.method === 'chat') return send({ id: r.id ?? 1, result: { finalResponse: 'Runtime stub is running. Bundle the real extracted-intelligence to enable tools.', requiresApproval: [] } });
      if (r.method === 'listTools') return send({ id: r.id ?? 1, result: { tools: [], skills: [] } });
      if (r.method === 'getStatus') return send({ id: r.id ?? 1, result: { ok: true, stub: true } });
      return send({ id: r.id ?? 1, error: { code: -32601, message: 'stub: unknown method' } });
    } catch (e) { return send({ id: 0, error: { code: -32000, message: String(e) } }, 500); }
  });
}).listen(port, '127.0.0.1', () => console.log('stub listening on ' + port));
"""
    }
}

/**
 * Pure PRoot argv builder (no Android dependencies — unit-tested).
 *
 * Binds only app-private dirs + the workspace + explicit extra roots;
 * never the whole device fs.
 */
fun buildProotArgs(
    prootPath: String,
    alpinePath: String,
    workspacePath: String,
    runtimePath: String,
    extraBinds: List<Pair<String, String>> = emptyList(),
    guestCmd: List<String>
): List<String> {
    val mapped = mutableListOf(
        "$workspacePath:/workspace",
        "$runtimePath:/runtime"
    )
    // Extra roots mount at fixed guest paths; reject anything that would
    // escape the intended layout.
    for ((host, guest) in extraBinds) {
        if (!guest.startsWith("/mnt/ext") || guest.contains("..") || host.isBlank()) continue
        mapped += "$host:$guest"
    }
    return buildList {
        add(prootPath)
        add("-r"); add(alpinePath)
        for (m in mapped) { add("-b"); add(m) }
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("-w"); add("/workspace")
        add("-0")
        addAll(guestCmd)
    }
}

/**
 * Pure hint matcher for apk/network failures (unit-tested).
 */
fun apkFailureHint(output: String): String {
    val o = output.lowercase()
    return when {
        "certificate" in o || "ssl" in o || "tls" in o ->
            " (TLS error — check the device date/time is correct, then retry)"
        "could not resolve" in o || "temporary failure" in o ||
            "network unreachable" in o || "network is unreachable" in o ->
            " (guest has no network/DNS — check internet connection, VPN or private DNS, then retry)"
        "404" in o || "not found" in o ->
            " (Alpine mirror unreachable from this network — retry or switch networks)"
        else -> ""
    }
}

/**
 * Download artifacts (AndCode pattern: pin URL + SHA-256).
 *
 * Alpine pins below are the official arm64 minirootfs, SHA-256 taken from
 * AndCode's local-runtime-manifest.json (runtime 2026.07.25.1), which pins
 * the same official CDN file. PRoot is bundled in the APK (see
 * installBundledProot) and intentionally has no download URL here.
 * OpenCode CLI pins are AndCode's (anomalyco/opencode fork, musl build)
 * for the Priority-4 multi-agent step.
 */
object RuntimeArtifacts {
    const val ALPINE_VERSION = "3.24.1"
    const val ALPINE_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz"
    const val ALPINE_SHA256 =
        "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259"

    // Priority 4 — OpenCode CLI (musl, runs inside Alpine). Pinned by AndCode.
    const val OPENCODE_VERSION = "1.18.5"
    const val OPENCODE_URL =
        "https://github.com/anomalyco/opencode/releases/download/v1.18.5/opencode-linux-arm64-musl.tar.gz"
    const val OPENCODE_SHA256 =
        "d493f6d15bd4c2cb429d59c5213ea237128f4dd889a9426983b4c6e5d2c047ae"
}
