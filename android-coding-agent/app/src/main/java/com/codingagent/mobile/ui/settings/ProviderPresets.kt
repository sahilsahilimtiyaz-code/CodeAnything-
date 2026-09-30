package com.codingagent.mobile.ui.settings

/** Provider presets for Settings (match extracted-intelligence provider factory types). */
data class ProviderPreset(
    val label: String,
    val type: String,
    val baseUrl: String,
    val model: String
)

object ProviderPresets {
    val OLLAMA_LOCAL = ProviderPreset(
        label = "Ollama (on-device / LAN)",
        type = "ollama",
        baseUrl = "http://127.0.0.1:11434",
        model = "qwen2.5-coder:7b"
    )
    val OPENROUTER = ProviderPreset(
        label = "OpenRouter",
        type = "openrouter",
        baseUrl = "https://openrouter.ai/api/v1",
        model = "qwen/qwen-2.5-coder-32b-instruct"
    )
    val QWEN = ProviderPreset(
        label = "Qwen (DashScope)",
        type = "qwen",
        baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        model = "qwen-coder-plus"
    )
    val DEEPSEEK = ProviderPreset(
        label = "DeepSeek",
        type = "deepseek",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat"
    )
    val CUSTOM = ProviderPreset(
        label = "Custom OpenAI-compatible",
        type = "generic-openai",
        baseUrl = "",
        model = ""
    )

    val ALL: List<ProviderPreset> = listOf(OLLAMA_LOCAL, OPENROUTER, QWEN, DEEPSEEK, CUSTOM)
}
