package com.codingagent.mobile.runtime

import java.io.File
import java.net.URLDecoder

/**
 * Resolve a SAF tree URI string to a real filesystem path when possible.
 *
 * Pure JVM (no Android framework) so it is unit-testable.
 *
 * Only the primary shared-storage volume maps deterministically
 * (`primary:rel/path` → `/storage/emulated/0/rel/path`). Other volumes
 * (SD cards, USB, cloud providers) return null and stay browse-only in the
 * Workspace UI — they are never added to the agent sandbox.
 */
fun resolveExternalRoot(treeUri: String): String? {
    val auth = "://com.android.externalstorage.documents/"
    val idx = treeUri.indexOf(auth)
    if (!treeUri.startsWith("content") || idx < 0) return null
    val rest = treeUri.substring(idx + auth.length)
    if (!rest.startsWith("tree/")) return null
    val encoded = rest.removePrefix("tree/").substringBefore("/")
    val docId = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
    val volume = docId.substringBefore(":")
    if (volume != "primary") return null
    val rel = docId.substringAfter(":").trim('/')
    if (rel.contains("..")) return null
    val abs = File(File("/storage/emulated/0"), rel).absolutePath
    if (abs != "/storage/emulated/0" && !abs.startsWith("/storage/emulated/0/")) return null
    return abs
}
