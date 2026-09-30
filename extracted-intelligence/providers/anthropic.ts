import type { ChatMessage, ChatOptions, LLMProvider, ProviderConfig } from "./types.js";

/** Minimal Anthropic messages API provider with tool-calling support. */
export class AnthropicProvider implements LLMProvider {
  name = "anthropic";
  supportsToolCalling = true;
  private apiKey: string;
  private baseUrl: string;
  private defaultModel: string;

  constructor(config: ProviderConfig) {
    if (!config.apiKey) throw new Error("Anthropic provider requires apiKey");
    this.apiKey = config.apiKey;
    this.baseUrl = (config.baseUrl ?? "https://api.anthropic.com").replace(/\/+$/, "");
    this.defaultModel = config.defaultModel ?? "claude-sonnet-4-5";
  }

  async chat(messages: ChatMessage[], options: ChatOptions = {}): Promise<ChatMessage> {
    const system = messages.filter((m) => m.role === "system").map((m) => m.content).join("\n\n");
    const converted: any[] = [];
    for (const m of messages) {
      if (m.role === "system") continue;
      if (m.role === "tool") {
        converted.push({
          role: "user",
          content: [{ type: "tool_result", tool_use_id: m.tool_call_id, content: m.content }],
        });
        continue;
      }
      if (m.tool_calls?.length) {
        converted.push({
          role: "assistant",
          content: [
            ...(m.content ? [{ type: "text", text: m.content }] : []),
            ...m.tool_calls.map((tc) => {
              let input: unknown = {};
              try { input = JSON.parse(tc.function.arguments || "{}"); } catch { /* keep {} */ }
              return { type: "tool_use", id: tc.id, name: tc.function.name, input };
            }),
          ],
        });
        continue;
      }
      converted.push({ role: m.role === "assistant" ? "assistant" : "user", content: m.content });
    }
    const res = await fetch(`${this.baseUrl}/v1/messages`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": this.apiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: options.model ?? this.defaultModel,
        max_tokens: options.maxTokens ?? 2048,
        system: system || undefined,
        tools: (options.tools ?? []).map((t) => ({
          name: t.name,
          description: t.description,
          input_schema: t.parameters,
        })),
        messages: converted,
      }),
      signal: options.signal,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`Anthropic HTTP ${res.status}: ${text.slice(0, 500)}`);
    }
    const data = (await res.json()) as any;
    const blocks: any[] = data?.content ?? [];
    const text = blocks.filter((b) => b.type === "text").map((b) => b.text).join("\n");
    const toolCalls = blocks
      .filter((b) => b.type === "tool_use")
      .map((b: any) => ({
        id: b.id,
        type: "function" as const,
        function: { name: b.name, arguments: JSON.stringify(b.input ?? {}) },
      }));
    return { role: "assistant", content: text, tool_calls: toolCalls.length ? toolCalls : undefined };
  }
}
