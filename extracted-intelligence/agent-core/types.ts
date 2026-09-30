import type { ChatMessage } from "../providers/types.js";
import type { ApprovalPolicy, ApprovalRequest, SkillTool, ToolContext } from "../skills/types.js";

export interface AgentTurnRequest {
  sessionId: string;
  userMessage: string;
  history?: ChatMessage[];
  allowedDirectories: string[];
  model?: string;
  approvalPolicy?: ApprovalPolicy;
  signal?: AbortSignal;
  /** Pre-approved tool-call ids (from Android ApprovalBanner "Allow"). */
  approvedToolCalls?: string[];
}

export interface AgentTurnResult {
  finalResponse: string;
  messages: ChatMessage[];
  requiresApproval: ApprovalRequest[];
  executedTools: { name: string; resultPreview: string }[];
}

export interface AgentHandlerDeps {
  providerChat: (messages: ChatMessage[], opts: { model?: string; tools: unknown; signal?: AbortSignal }) => Promise<ChatMessage>;
  tools: SkillTool[];
  makeCtx: (allowed: string[], signal?: AbortSignal) => ToolContext;
}
