package com.codingagent.mobile.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Client for `opencode serve` running inside Alpine (default 127.0.0.1:4097).
 *
 * Endpoints used (verified against the OpenAPI spec + SDK types):
 * - GET  /global/health, GET /global/event (SSE), GET /provider, PATCH /config
 * - POST /session, POST /session/:id/prompt_async, POST /session/:id/abort
 * - POST /session/:id/permissions/:permissionID
 */
@Singleton
class OpencodeBridge @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    var baseUrl: String = "http://127.0.0.1:4097"

    fun configure(host: String = "127.0.0.1", port: Int = 4097) {
        baseUrl = "http://$host:$port"
    }

    // ---- one-shot REST ----

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            get("/global/health").optBoolean("healthy", false)
        } catch (e: Exception) {
            Timber.d("opencode not reachable: ${e.message}")
            false
        }
    }

    suspend fun providers(): List<OpencodeProvider> = withContext(Dispatchers.IO) {
        val root = get("/provider")
        val connected = mutableSetOf<String>()
        root.optJSONArray("connected")?.let { arr ->
            for (i in 0 until arr.length()) connected += arr.optString(i)
        }
        val out = mutableListOf<OpencodeProvider>()
        root.optJSONArray("all")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pid = p.optString("id")
                val models = mutableListOf<OpencodeModel>()
                val mmap = p.optJSONObject("models")
                if (mmap != null) {
                    val keys = mmap.keys()
                    while (keys.hasNext()) {
                        val mid = keys.next()
                        val m = mmap.optJSONObject(mid)
                        models += OpencodeModel(
                            id = "$pid/$mid",
                            providerID = pid,
                            modelID = mid,
                            name = m?.optString("name")?.ifBlank { mid } ?: mid,
                            reasoning = m?.optJSONObject("capabilities")?.optBoolean("reasoning") == true,
                            status = m?.optString("status") ?: "active"
                        )
                    }
                }
                out += OpencodeProvider(
                    id = pid,
                    name = p.optString("name").ifBlank { pid },
                    connected = connected.contains(pid),
                    models = models.sortedBy { it.name.lowercase() }
                )
            }
        }
        out.sortedBy { it.name.lowercase() }
    }

    suspend fun setDefaultModel(providerID: String, modelID: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                patch("/config", JSONObject().apply { put("model", "$providerID/$modelID") })
                true
            } catch (e: Exception) {
                Timber.w(e, "setDefaultModel failed")
                false
            }
        }

    suspend fun createSession(title: String): String = withContext(Dispatchers.IO) {
        val res = post("/session", JSONObject().apply { put("title", title.take(60)) })
        res.optString("id").ifBlank { throw BridgeException("No session id returned") }
    }

    suspend fun promptAsync(
        sessionId: String,
        text: String,
        providerID: String? = null,
        modelID: String? = null,
        agent: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            if (providerID != null && modelID != null) {
                put("model", JSONObject().apply {
                    put("providerID", providerID)
                    put("modelID", modelID)
                })
            }
            if (agent != null) put("agent", agent)
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                })
            })
        }
        post("/session/$sessionId/prompt_async", body)
        Unit
    }

    suspend fun abortSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { post("/session/$sessionId/abort", JSONObject()) }.isSuccess
    }

    /**
     * Reply to a permission request. The server takes `accept` / `reject`.
     */
    suspend fun replyPermission(sessionId: String, permissionId: String, accept: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                post(
                    "/session/$sessionId/permissions/$permissionId",
                    JSONObject().apply { put("response", if (accept) "accept" else "reject") }
                )
                true
            } catch (e: Exception) {
                Timber.w(e, "replyPermission failed")
                false
            }
        }

    // ---- SSE event stream ----

    fun openEvents(onEvent: (OpEvent) -> Unit): Closeable {
        val closed = AtomicBoolean(false)
        val req = Request.Builder()
            .url("$baseUrl/global/event")
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call = sseClient.newCall(req)
        val t = thread(name = "opencode-sse", isDaemon = true) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onEvent(OpEvent.StreamError("HTTP ${resp.code}"))
                        return
                    }
                    val source = resp.body?.source() ?: return
                    val dataLines = mutableListOf<String>()
                    while (!closed.get()) {
                        val line = try {
                            source.readUtf8Line()
                        } catch (_: Exception) {
                            break
                        } ?: break
                        when {
                            line.isBlank() -> {
                                dispatchEvent(dataLines.joinToString("\n"), onEvent)
                                dataLines.clear()
                            }
                            line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
                            line.startsWith(":") -> Unit // heartbeat/comment
                            // `event:` frames carry no payload type; the type lives in data.
                        }
                    }
                }
            } catch (e: Exception) {
                if (!closed.get()) onEvent(OpEvent.StreamError(e.message ?: "stream failed"))
            }
        }
        return Closeable {
            closed.set(true)
            call.cancel()
            t.join(2000)
        }
    }

    private fun dispatchEvent(data: String, onEvent: (OpEvent) -> Unit) {
        if (data.isBlank()) return
        try {
            val root = JSONObject(data)
            val payload = root.optJSONObject("payload") ?: return
            when (payload.optString("type")) {
                "server.connected" -> Unit
                "message.part.updated" -> {
                    val props = payload.optJSONObject("properties") ?: return
                    val part = props.optJSONObject("part") ?: return
                    val sessionId = part.optString("sessionID")
                    val messageId = part.optString("messageID")
                    val partId = part.optString("id")
                    when (part.optString("type")) {
                        "text" -> {
                            val text = props.optString("delta")
                                .ifBlank { part.optString("text") }
                            if (text.isNotBlank()) {
                                onEvent(OpEvent.Text(sessionId, messageId, partId, text))
                            }
                        }
                        "reasoning" -> {
                            val text = props.optString("delta")
                                .ifBlank { part.optString("text") }
                            if (text.isNotBlank()) {
                                onEvent(OpEvent.Reasoning(sessionId, messageId, partId, text))
                            }
                        }
                        "tool" -> {
                            val state = part.optJSONObject("state")
                            onEvent(
                                OpEvent.Tool(
                                    sessionId = sessionId,
                                    messageId = messageId,
                                    partId = partId,
                                    toolName = part.optString("tool"),
                                    title = state?.optString("title")
                                        ?: part.optString("tool"),
                                    status = state?.optString("status") ?: "running",
                                    output = state?.optString("output")
                                        ?: state?.optString("error")
                                )
                            )
                        }
                        else -> Unit
                    }
                }
                "permission.updated" -> {
                    val props = payload.optJSONObject("properties") ?: return
                    onEvent(
                        OpEvent.Permission(
                            sessionId = props.optString("sessionID"),
                            permissionId = props.optString("id"),
                            toolName = props.optString("type"),
                            title = props.optString("title"),
                            metadata = props.optJSONObject("metadata")?.toString() ?: "{}"
                        )
                    )
                }
                "session.idle" -> {
                    val sid = payload.optJSONObject("properties")?.optString("sessionID") ?: return
                    onEvent(OpEvent.Idle(sid))
                }
                "session.status" -> {
                    val props = payload.optJSONObject("properties") ?: return
                    val sid = props.optString("sessionID")
                    val status = props.optJSONObject("status")?.optString("type") ?: return
                    onEvent(OpEvent.Status(sid, status))
                }
                "session.error" -> {
                    val props = payload.optJSONObject("properties")
                    val err = props?.optJSONObject("error")?.optJSONObject("data")
                        ?.optString("message") ?: "session error"
                    onEvent(OpEvent.RunError(props?.optString("sessionID") ?: "", err))
                }
                else -> Unit
            }
        } catch (e: Exception) {
            Timber.d("skip malformed event: ${e.message}")
        }
    }

    // ---- HTTP helpers ----

    private fun get(path: String): JSONObject {
        val req = Request.Builder().url("$baseUrl$path").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw BridgeException("HTTP ${resp.code} $path")
            return JSONObject(resp.body?.string() ?: throw BridgeException("Empty body"))
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 204) return JSONObject()
            if (!resp.isSuccessful) {
                throw BridgeException("HTTP ${resp.code} $path: ${resp.body?.string()?.take(300)}")
            }
            val text = resp.body?.string()?.trim().orEmpty()
            return if (text.isEmpty()) JSONObject() else JSONObject(text)
        }
    }

    private fun patch(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .patch(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw BridgeException("HTTP ${resp.code} $path")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    data class OpencodeProvider(
        val id: String,
        val name: String,
        val connected: Boolean,
        val models: List<OpencodeModel>
    )

    data class OpencodeModel(
        val id: String,
        val providerID: String,
        val modelID: String,
        val name: String,
        val reasoning: Boolean,
        val status: String
    )

    sealed interface OpEvent {
        data class Text(val sessionId: String, val messageId: String, val partId: String, val delta: String) : OpEvent
        data class Reasoning(val sessionId: String, val messageId: String, val partId: String, val delta: String) : OpEvent
        data class Tool(
            val sessionId: String,
            val messageId: String,
            val partId: String,
            val toolName: String,
            val title: String,
            val status: String,
            val output: String?
        ) : OpEvent
        data class Permission(
            val sessionId: String,
            val permissionId: String,
            val toolName: String,
            val title: String,
            val metadata: String
        ) : OpEvent
        data class Idle(val sessionId: String) : OpEvent
        data class Status(val sessionId: String, val status: String) : OpEvent
        data class RunError(val sessionId: String, val message: String) : OpEvent
        data class StreamError(val message: String) : OpEvent
    }

    class BridgeException(message: String) : Exception(message)
}
