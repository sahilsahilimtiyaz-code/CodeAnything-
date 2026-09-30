import type {
  ChatMessage,
  ChatOptions,
  LLMProvider,
  ProviderConfig,
} from "./types.js";

/**
 * Single OpenAI-compatible chat-completions provider.
 * Covers: generic-openai, ollama, openrouter, deepseek, qwen, lmstudio, litellm…
 * Only the default base URL / headers differ per preset.
 */
export class OpenAICompatibleProvider implements LLMProvider {
  name: string;
  supportsToolCalling = true;
  private baseUrl: string;
  private apiKey?: string;
  private defaultModel: string;
  private extraHeaders: Record<string, string>;

  constructor(name: string, config: ProviderConfig) {
    this.name = name;
    this.baseUrl = (config.baseUrl ?? "http://127.0.0.1:11434/v1").replace(/\/+$/, "");
    this.apiKey = config.apiKey;
    this.defaultModel = config.defaultModel ?? "qwen2.5-coder:7b";
    this.extraHeaders = config.extraHeaders ?? {};
  }

  async chat(messages: ChatMessage[], options: ChatOptions = {}): Promise<ChatMessage> {
    const url = `${this.baseUrl}/chat/completions`;
    const model = options.model ?? this.defaultModel;
    const tools = (options.tools ?? []).map((t) => ({
      type: "function",
      function: {
        name: t.name,
        description: t.description,
        parameters: t.parameters,
      },
    }));
    const body: Record<string, unknown> = {
      model,
      messages: messages.map((m) => {
        if (m.role === "tool") {
          return { role: "tool", tool_call_id: m.tool_call_id, content: m.content };
        }
        if (m.tool_calls?.length) {
          return { role: "assistant", content: m.content ?? null, tool_calls: m.tool_calls };
        }
        return { role: m.role, content: m.content };
      }),
      temperature: options.temperature ?? 0.2,
      tool_choice: tools.length ? "auto" : undefined,
    };
    if (tools.length) body.tools = tools;
    if (options.maxTokens) body.max_tokens = options.maxTokens;

    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      ...this.extraHeaders,
    };
    if (this.apiKey) headers.Authorization = `Bearer ${this.apiKey}`;

    const res = await fetch(url, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      signal: options.signal,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`Provider ${this.name} HTTP ${res.status}: ${text.slice(0, 500)}`);
    }
    const data = (await res.json()) as any;
    const msg = data?.choices?.[0]?.message ?? data?.message ?? {};
    return {
      role: "assistant",
      content: typeof msg.content === "string" ? msg.content : (msg.content ?? ""),
      tool_calls: msg.tool_calls,
    };
  }
}

export function openRouterProvider(config: ProviderConfig): LLMProvider {
  return new OpenAICompatibleProvider("openrouter", {
    type: "openrouter",
    baseUrl: config.baseUrl ?? "https://openrouter.ai/api/v1",
    apiKey: config.apiKey,
    defaultModel: config.defaultModel ?? "qwen/qwen-2.5-coder-32b-instruct",
    extraHeaders: {
      "HTTP-Referer": "https://coding-agent-mobile.local",
      "X-Title": "CodingAgentMobile",
      ...(config.extraHeaders ?? {}),
    },
  });
}

export function qwenProvider(config: ProviderConfig): LLMProvider {
  // Alibaba DashScope OpenAI-compatible endpoint
  return new OpenAICompatibleProvider("qwen", {
    type: "qwen",
    baseUrl: config.baseUrl ?? "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    apiKey: config.apiKey,
    defaultModel: config.defaultModel ?? "qwen-coder-plus",
    extraHeaders: config.extraHeaders,
  });
}

export function deepSeekProvider(config: ProviderConfig): LLMProvider {
  return new OpenAICompatibleProvider("deepseek", {
    type: "deepseek",
    baseUrl: config.baseUrl ?? "https://api.deepseek.com/v1",
    apiKey: config.apiKey,
    defaultModel: config.defaultModel ?? "deepseek-chat",
    extraHeaders: config.extraHeaders,
  });
}

export function ollamaProvider(config: ProviderConfig): LLMProvider {
  return new OpenAICompatibleProvider("ollama", {
    type: "ollama",
    baseUrl: config.baseUrl ?? "http://127.0.0.1:11434/v1",
    defaultModel: config.defaultModel ?? "qwen2.5-coder:7b",
    extraHeaders: config.extraHeaders,
  });
}
