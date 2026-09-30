export type ChatRole = "system" | "user" | "assistant" | "tool";

export interface ToolCall {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
}

export interface ChatMessage {
  role: ChatRole;
  content: string;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
  name?: string;
}

export interface ToolDefinition {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface ChatOptions {
  model?: string;
  tools?: ToolDefinition[];
  signal?: AbortSignal;
  temperature?: number;
  maxTokens?: number;
}

export interface LLMProvider {
  name: string;
  supportsToolCalling: boolean;
  chat(messages: ChatMessage[], options?: ChatOptions): Promise<ChatMessage>;
}

export interface ProviderConfig {
  type: string; // ollama | openrouter | generic-openai | anthropic | deepseek | qwen | mock
  baseUrl?: string;
  apiKey?: string;
  defaultModel?: string;
  extraHeaders?: Record<string, string>;
}
