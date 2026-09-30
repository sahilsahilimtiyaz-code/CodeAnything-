import type { LLMProvider, ChatMessage } from "../providers/types.js";
import type { SkillRegistry } from "../skills/registry.js";
import type { ToolContext } from "../skills/types.js";
import { runAgentLoop } from "./AIbitat.js";
import type { AgentTurnRequest, AgentTurnResult } from "./types.js";

/**
 * AgentHandler: owns provider + registry + per-session history.
 * Thin orchestration over runAgentLoop so the bridge server stays small.
 */
export class AgentHandler {
  private sessions = new Map<string, ChatMessage[]>();

  constructor(
    private provider: LLMProvider,
    private registry: SkillRegistry,
    private defaultModel?: string
  ) {}

  setProvider(provider: LLMProvider, defaultModel?: string): void {
    this.provider = provider;
    if (defaultModel) this.defaultModel = defaultModel;
  }

  clearSession(sessionId: string): void {
    this.sessions.delete(sessionId);
  }

  async handleTurn(req: AgentTurnRequest): Promise<AgentTurnResult> {
    const history = req.history ?? this.sessions.get(req.sessionId) ?? [];
    const seed: ChatMessage[] = [
      { role: "system", content: "You are a careful on-device coding agent. Use tools when needed. All file access is sandboxed to the workspace. Keep answers short." },
      ...history.filter((m) => m.role !== "system"),
      { role: "user", content: req.userMessage },
    ];
    const tools = this.registry.allTools();
    const makeCtx = (_allowed: string[], _signal?: AbortSignal): ToolContext => ({
      allowedDirectories: req.allowedDirectories,
      sessionId: req.sessionId,
      signal: req.signal,
      workspaceId: req.sessionId,
    });

    // Patch: AIbitat's makeCtx signature ignores per-call allowed dirs;
    // tools themselves resolve via FilesystemManager sandbox, so ctx is advisory.
    const { messages, requiresApproval, executed } = await runAgentLoop(seed, {
      tools,
      makeCtx,
      providerChat: (msgs, o) =>
        this.provider.chat(msgs, { model: req.model ?? this.defaultModel, tools: o.tools, signal: req.signal }),
      approvalPolicy: req.approvalPolicy ?? "ASK_DANGEROUS",
      approvedToolCalls: new Set(req.approvedToolCalls ?? []),
    });

    // Persist history (bounded)
    const toStore = messages.filter((m) => m.role !== "system").slice(-40);
    this.sessions.set(req.sessionId, toStore);

    const lastAssistant = [...messages].reverse().find((m) => m.role === "assistant");
    return {
      finalResponse: lastAssistant?.content ?? "(no response)",
      messages,
      requiresApproval,
      executedTools: executed,
    };
  }
}
