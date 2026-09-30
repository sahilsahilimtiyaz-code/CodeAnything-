package com.codingagent.mobile.bridge

import com.codingagent.mobile.domain.ApprovalRequest
import com.codingagent.mobile.domain.ChatMessage
import com.codingagent.mobile.domain.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge to the Node extracted-intelligence service running inside Alpine.
 *
 * Protocol: simple JSON-RPC style over HTTP on localhost.
 * Methods: chat | listTools | approveTool | getStatus | abort | loadSkills
 */
@Singleton
class IntelligenceBridge @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var baseUrl: String = "http://127.0.0.1:18789"

    fun configure(host: String = "127.0.0.1", port: Int = 18789) {
        baseUrl = "http://$host:$port"
    }

    suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.d("Intelligence service not reachable: ${e.message}")
            false
        }
    }

    suspend fun chat(
        message: String,
        history: List<ChatMessage>,
        sessionId: String,
        allowedDirectories: List<String>
    ): ChatTurnResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("method", "chat")
            put("id", sessionId)
            put("params", JSONObject().apply {
                put("message", message)
                put("sessionId", sessionId)
                put("allowedDirectories", JSONArray(allowedDirectories))
                put("history", JSONArray().apply {
                    history.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", msg.role.name.lowercase())
                            put("content", msg.content)
                        })
                    }
                })
            })
        }

        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url("$baseUrl/rpc")
            .post(body)
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw BridgeException("HTTP ${response.code}: ${response.message}")
            }
            val text = response.body?.string() ?: throw BridgeException("Empty body")
            parseChatResult(text)
        }
    }

    suspend fun abort(sessionId: String) = withContext(Dispatchers.IO) {
        rpc("abort", sessionId, JSONObject())
    }

    suspend fun listTools(): ToolsResult = withContext(Dispatchers.IO) {
        val res = rpc("listTools", "tools", JSONObject())
        val result = res.optJSONObject("result") ?: JSONObject()
        val tools = mutableListOf<ToolInfo>()
        val arr = result.optJSONArray("tools")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                tools += ToolInfo(
                    name = t.optString("name"),
                    description = t.optString("description")
                )
            }
        }
        val skills = mutableListOf<String>()
        val sarr = result.optJSONArray("skills")
        if (sarr != null) {
            for (i in 0 until sarr.length()) skills += sarr.optString(i)
        }
        ToolsResult(tools = tools, skills = skills)
    }

    suspend fun approveTool(sessionId: String, toolCallId: String, approve: Boolean): String =
        withContext(Dispatchers.IO) {
            val params = JSONObject().apply {
                put("sessionId", sessionId)
                put("toolCallId", toolCallId)
                put("approve", approve)
            }
            val res = rpc("approveTool", sessionId, params)
            if (res.has("error")) {
                throw BridgeException(res.getJSONObject("error").optString("message", "approve failed"))
            }
            val result = res.optJSONObject("result") ?: JSONObject()
            result.optString("result", "")
        }

    suspend fun getStatus(): JSONObject = withContext(Dispatchers.IO) {
        val res = rpc("getStatus", "status", JSONObject())
        res.optJSONObject("result") ?: JSONObject()
    }

    suspend fun loadSkills(dir: String): List<String> = withContext(Dispatchers.IO) {
        val params = JSONObject().apply { put("dir", dir) }
        val res = rpc("loadSkills", "skills", params)
        val result = res.optJSONObject("result") ?: JSONObject()
        val loaded = mutableListOf<String>()
        val arr = result.optJSONArray("loaded")
        if (arr != null) {
            for (i in 0 until arr.length()) loaded += arr.optString(i)
        }
        loaded
    }

    private fun rpc(method: String, id: String, params: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("method", method)
            put("id", id)
            put("params", params)
        }
        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url("$baseUrl/rpc").post(body).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw BridgeException("HTTP ${response.code}: ${response.message}")
            }
            val text = response.body?.string() ?: throw BridgeException("Empty body")
            return JSONObject(text)
        }
    }

    private fun parseChatResult(json: String): ChatTurnResult {
        val root = JSONObject(json)
        if (root.has("error")) {
            val err = root.getJSONObject("error")
            throw BridgeException(err.optString("message", "Unknown error"))
        }
        val result = root.optJSONObject("result") ?: JSONObject()
        val finalResponse = result.optString("finalResponse", "")
        val approvals = mutableListOf<ApprovalRequest>()
        val arr = result.optJSONArray("requiresApproval")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                approvals += ApprovalRequest(
                    id = a.optString("id"),
                    toolName = a.optString("toolName"),
                    argsJson = a.optJSONObject("args")?.toString() ?: "{}",
                    description = a.optString("description"),
                    diff = a.optString("diff").takeIf { it.isNotBlank() },
                    riskLevel = when (a.optString("riskLevel")) {
                        "high" -> RiskLevel.HIGH
                        "medium" -> RiskLevel.MEDIUM
                        else -> RiskLevel.LOW
                    }
                )
            }
        }
        return ChatTurnResult(
            finalResponse = finalResponse,
            approvals = approvals,
            raw = result
        )
    }

    data class ChatTurnResult(
        val finalResponse: String,
        val approvals: List<ApprovalRequest> = emptyList(),
        val raw: JSONObject = JSONObject()
    )

    data class ToolInfo(val name: String, val description: String)

    data class ToolsResult(val tools: List<ToolInfo>, val skills: List<String>)

    class BridgeException(message: String) : Exception(message)
}
