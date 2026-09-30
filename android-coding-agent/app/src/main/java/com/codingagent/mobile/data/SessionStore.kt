package com.codingagent.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.codingagent.mobile.domain.AgentKind
import com.codingagent.mobile.domain.ChatMessage
import com.codingagent.mobile.domain.ProviderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/**
 * Persists provider config, selected agent and recent chat history.
 *
 * NOTE: the provider API key currently lives in plain DataStore prefs.
 * Migrate it to EncryptedSharedPreferences (androidx.security) before any
 * build that handles paidBYOK keys on shared devices.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val PROVIDER_TYPE = stringPreferencesKey("provider_type")
        val PROVIDER_URL = stringPreferencesKey("provider_url")
        val PROVIDER_KEY = stringPreferencesKey("provider_key")
        val PROVIDER_MODEL = stringPreferencesKey("provider_model")
        val SELECTED_AGENT = stringPreferencesKey("selected_agent")
        val CHAT_HISTORY = stringPreferencesKey("chat_history")
        val OPENCODE_MODEL = stringPreferencesKey("opencode_model")
        val OPENCODE_AGENT = stringPreferencesKey("opencode_agent")
    }

    companion object {
        const val MAX_HISTORY = 50
    }

    suspend fun loadProvider(): ProviderConfig? {
        val p = context.sessionDataStore.data.map { prefs ->
            val type = prefs[Keys.PROVIDER_TYPE] ?: return@map null
            ProviderConfig(
                type = type,
                baseUrl = prefs[Keys.PROVIDER_URL],
                apiKey = prefs[Keys.PROVIDER_KEY],
                defaultModel = prefs[Keys.PROVIDER_MODEL]
            )
        }.first()
        return p
    }

    suspend fun saveProvider(config: ProviderConfig) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.PROVIDER_TYPE] = config.type
            prefs.remove(Keys.PROVIDER_URL)
            prefs.remove(Keys.PROVIDER_KEY)
            prefs.remove(Keys.PROVIDER_MODEL)
            config.baseUrl?.let { prefs[Keys.PROVIDER_URL] = it }
            config.apiKey?.let { prefs[Keys.PROVIDER_KEY] = it }
            config.defaultModel?.let { prefs[Keys.PROVIDER_MODEL] = it }
        }
    }

    suspend fun loadAgent(): AgentKind? {
        val name = context.sessionDataStore.data.map { it[Keys.SELECTED_AGENT] }.first()
        return runCatching { if (name != null) AgentKind.valueOf(name) else null }.getOrNull()
    }

    suspend fun saveAgent(kind: AgentKind) {
        context.sessionDataStore.edit { it[Keys.SELECTED_AGENT] = kind.name }
    }

    suspend fun loadHistory(): List<ChatMessage> {
        val raw = context.sessionDataStore.data.map { it[Keys.CHAT_HISTORY] }.first()
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val role = runCatching { ChatMessage.Role.valueOf(o.optString("role")) }
                        .getOrDefault(ChatMessage.Role.USER)
                    add(
                        ChatMessage(
                            id = o.optString("id"),
                            role = role,
                            content = o.optString("content"),
                            timestamp = o.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "history parse failed")
            emptyList()
        }
    }

    suspend fun saveHistory(messages: List<ChatMessage>) {
        val kept = messages.filter { !it.isStreaming }.takeLast(MAX_HISTORY)
        val arr = JSONArray()
        for (m in kept) {
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("role", m.role.name)
                    put("content", m.content)
                    put("timestamp", m.timestamp)
                }
            )
        }
        context.sessionDataStore.edit { it[Keys.CHAT_HISTORY] = arr.toString() }
    }

    suspend fun clearHistory() {
        context.sessionDataStore.edit { it.remove(Keys.CHAT_HISTORY) }
    }

    suspend fun saveOpencodeModel(providerID: String, modelID: String) {
        context.sessionDataStore.edit { it[Keys.OPENCODE_MODEL] = "$providerID/$modelID" }
    }

    suspend fun loadOpencodeModel(): Pair<String, String>? {
        val raw = context.sessionDataStore.data.map { it[Keys.OPENCODE_MODEL] }.first()
            ?: return null
        val slash = raw.indexOf('/')
        if (slash <= 0 || slash == raw.length - 1) return null
        return raw.substring(0, slash) to raw.substring(slash + 1)
    }

    suspend fun saveOpencodeAgent(agent: String) {
        context.sessionDataStore.edit { it[Keys.OPENCODE_AGENT] = agent }
    }

    suspend fun loadOpencodeAgent(): String? {
        val agent = context.sessionDataStore.data.map { it[Keys.OPENCODE_AGENT] }.first()
        return if (agent == "build" || agent == "plan") agent else null
    }
}
