import type { ChatMessage, ChatOptions, LLMProvider } from "./types.js";

/** Deterministic mock for tests and offline smoke runs. */
export class MockProvider implements LLMProvider {
  name = "mock";
  supportsToolCalling = true;
  private calls = 0;

  async chat(messages: ChatMessage[], _options?: ChatOptions): Promise<ChatMessage> {
    this.calls += 1;
    if (this.calls === 1) {
      return {
        role: "assistant",
        content: "I will list the workspace files first.",
        tool_calls: [
          { id: "call_1", type: "function", function: { name: "list_directory", arguments: JSON.stringify({ path: "." }) } },
        ],
      };
    }
    if (this.calls === 2) {
      return {
        role: "assistant",
        content: "I have seen the files. I will remember the workspace style.",
        tool_calls: [
          { id: "call_2", type: "function", function: { name: "memory_store", arguments: JSON.stringify({ key: "workspace_note", value: "Smoke test workspace – keep changes small." }) } },
        ],
      };
    }
    void messages;
    return { role: "assistant", content: "Done. Mock turn completed successfully." };
  }
}
