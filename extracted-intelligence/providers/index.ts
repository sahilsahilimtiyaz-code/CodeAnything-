import { AnthropicProvider } from "./anthropic.js";
import { MockProvider } from "./mock.js";
import {
  OpenAICompatibleProvider,
  deepSeekProvider,
  ollamaProvider,
  openRouterProvider,
  qwenProvider,
} from "./openaiCompatible.js";
import type { LLMProvider, ProviderConfig } from "./types.js";

export * from "./types.js";
export { AnthropicProvider } from "./anthropic.js";
export { MockProvider } from "./mock.js";
export {
  OpenAICompatibleProvider,
  deepSeekProvider,
  ollamaProvider,
  openRouterProvider,
  qwenProvider,
} from "./openaiCompatible.js";

/** Qwen-first factory used by the bridge server and Android provider settings. */
export function createProvider(config: ProviderConfig): LLMProvider {
  switch (config.type) {
    case "ollama": return ollamaProvider(config);
    case "openrouter": return openRouterProvider(config);
    case "anthropic": return new AnthropicProvider(config);
    case "deepseek": return deepSeekProvider(config);
    case "qwen": return qwenProvider(config);
    case "mock": return new MockProvider();
    case "generic-openai":
    default:
      return new OpenAICompatibleProvider("generic-openai", config);
  }
}
