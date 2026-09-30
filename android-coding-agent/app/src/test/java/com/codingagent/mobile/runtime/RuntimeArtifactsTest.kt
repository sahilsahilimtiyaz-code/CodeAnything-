package com.codingagent.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeArtifactsTest {

    @Test
    fun `proot args bind only workspace and runtime, never device root`() {
        val cmd = buildProotArgs(
            prootPath = "/data/data/app/files/runtime/bin/proot",
            alpinePath = "/data/data/app/files/runtime/alpine",
            workspacePath = "/data/data/app/files/workspace",
            runtimePath = "/data/data/app/files/runtime",
            guestCmd = listOf("/usr/bin/node", "/runtime/intelligence/bridge/serve.js", "18789")
        )
        assertEquals("/data/data/app/files/runtime/bin/proot", cmd.first())
        assertTrue(cmd.contains("/data/data/app/files/workspace:/workspace"))
        assertTrue(cmd.contains("/data/data/app/files/runtime:/runtime"))
        assertTrue(cmd.contains("/workspace"))
        // No unrestricted binds
        assertTrue(cmd.none { it == "/:/root" || it == "/sdcard" || it.endsWith(":/sdcard") })
        // Guest command appended last
        assertEquals(
            listOf("/usr/bin/node", "/runtime/intelligence/bridge/serve.js", "18789"),
            cmd.takeLast(3)
        )
    }

    @Test
    fun `alpine artifact is pinned with official url and sha256`() {
        assertTrue(RuntimeArtifacts.ALPINE_URL.startsWith("https://dl-cdn.alpinelinux.org/"))
        assertTrue(RuntimeArtifacts.ALPINE_URL.contains("aarch64"))
        assertEquals(64, RuntimeArtifacts.ALPINE_SHA256.length)
        assertTrue(RuntimeArtifacts.ALPINE_SHA256.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `opencode cli pin is a musl arm64 build for priority 4`() {
        assertTrue(RuntimeArtifacts.OPENCODE_URL.contains("arm64"))
        assertTrue(RuntimeArtifacts.OPENCODE_URL.contains("musl"))
        assertEquals(64, RuntimeArtifacts.OPENCODE_SHA256.length)
    }

    @Test
    fun `extra binds mount at fixed guest paths and reject escapes`() {
        val cmd = buildProotArgs(
            prootPath = "/p",
            alpinePath = "/a",
            workspacePath = "/w",
            runtimePath = "/r",
            extraBinds = listOf(
                "/storage/emulated/0/Download" to "/mnt/ext0",
                "/evil" to "/etc",
                "/evil2" to "/mnt/ext/../x",
                "" to "/mnt/ext1"
            ),
            guestCmd = listOf("/bin/sh")
        )
        assertTrue(cmd.contains("/storage/emulated/0/Download:/mnt/ext0"))
        assertTrue(cmd.none { it == "/evil:/etc" || it == "/evil2:/mnt/ext/../x" })
    }

    @Test
    fun `apk hints point at date-time and network`() {
        assertTrue(apkFailureHint("ERROR: certificate verification failed").contains("date/time"))
        assertTrue(apkFailureHint("Could not resolve dl-cdn.alpinelinux.org").contains("DNS"))
        assertTrue(apkFailureHint("all good").isEmpty())
    }

    @Test
    fun `primary volume tree uri resolves to real path`() {
        assertEquals(
            "/storage/emulated/0/Download/foo",
            resolveExternalRoot("content://com.android.externalstorage.documents/tree/primary%3ADownload%2Ffoo")
        )
        assertEquals(null, resolveExternalRoot("content://com.android.externalstorage.documents/tree/1234-ABCD%3Afoo"))
        assertEquals(null, resolveExternalRoot("content://other/tree/x"))
        assertEquals(null, resolveExternalRoot("content://com.android.externalstorage.documents/tree/primary%3A..%2F..%2Fetc"))
    }
}
