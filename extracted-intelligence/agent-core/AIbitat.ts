import { randomUUID } from "node:crypto";
import type { ChatMessage } from "../providers/types.js";
import { needsApproval, type ApprovalPolicy, type ApprovalRequest, type SkillTool, type ToolContext } from "../skills/types.js";

export interface LoopOpts {
  tools: SkillTool[];
  makeCtx: (allowed: string[], signal?: AbortSignal) => ToolContext;
  providerChat: (
    messages: ChatMessage[],
    opts: { model?: string; tools: { name: string; description: string; parameters: Record<string, unknown> }[]; signal?: AbortSignal }
  ) => Promise<ChatMessage>;
  maxRounds?: number;
  approvalPolicy?: ApprovalPolicy;
  approvedToolCalls?: Set<string>;
}

/**
 * AIbitat-style tool loop: provider → tool_calls → string results → provider…
 * High-risk tools without pre-approval are NOT executed; they are returned
 * as ApprovalRequests so Android can show the ApprovalBanner.
 */
export async function runAgentLoop(
  seed: ChatMessage[],
  opts: LoopOpts
): Promise<{ messages: ChatMessage[]; requiresApproval: ApprovalRequest[]; executed: { name: string; resultPreview: string }[] }> {
  const messages: ChatMessage[] = [...seed];
  const requiresApproval: ApprovalRequest[] = [];
  const executed: { name: string; resultPreview: string }[] = [];
  const maxRounds = opts.maxRounds ?? 6;
  const policy: ApprovalPolicy = opts.approvalPolicy ?? "ASK_DANGEROUS";
  const approved = opts.approvedToolCalls ?? new Set<string>();
  const defs = opts.tools.map((t) => ({ name: t.name, description: t.description, parameters: t.parameters }));

  for (let round = 0; round < maxRounds; round++) {
    const assistant = await opts.providerChat(messages, { tools: defs });
    messages.push(assistant);
    if (!assistant.tool_calls?.length) break;

    for (const tc of assistant.tool_calls) {
      const tool = opts.tools.find((t) => t.name === tc.function.name);
      if (!tool) {
        messages.push({ role: "tool", tool_call_id: tc.id, name: tc.function.name, content: `Error: unknown tool ${tc.function.name}` });
        continue;
      }
      let args: Record<string, unknown> = {};
      try { args = JSON.parse(tc.function.arguments || "{}"); } catch { /* keep {} */ }

      if (needsApproval(tool.riskLevel, policy) && !approved.has(tc.id)) {
        requiresApproval.push({
          id: tc.id,
          toolName: tool.name,
          args,
          description: `${tool.name} ${tool.riskLevel} – approval required`,
          riskLevel: tool.riskLevel,
        });
        messages.push({ role: "tool", tool_call_id: tc.id, name: tool.name, content: "Tool pending approval – awaiting user decision." });
        continue;
      }
      try {
        const ctx = opts.makeCtx([], undefined);
        const result = await tool.execute(args, ctx);
        const text = String(result);
        executed.push({ name: tool.name, resultPreview: text.slice(0, 300) });
        messages.push({ role: "tool", tool_call_id: tc.id, name: tool.name, content: text });
      } catch (e: any) {
        messages.push({ role: "tool", tool_call_id: tc.id, name: tool.name, content: `Error: ${e?.message ?? String(e)}` });
      }
    }
    if (requiresApproval.length) break;
  }
  return { messages, requiresApproval, executed };
}

export function approvalId(): string {
  return randomUUID();
}
