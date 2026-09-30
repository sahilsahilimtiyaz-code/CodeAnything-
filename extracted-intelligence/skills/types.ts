export type RiskLevel = "low" | "medium" | "high";
export type ApprovalPolicy = "ALWAYS_ASK" | "ASK_DANGEROUS" | "FULL_ACCESS";

export interface ToolContext {
  workspaceId?: string;
  allowedDirectories: string[];
  sessionId: string;
  signal?: AbortSignal;
  logger?: { debug(...a: unknown[]): void; info(...a: unknown[]): void; warn(...a: unknown[]): void; error(...a: unknown[]): void };
}

export interface ApprovalRequest {
  id: string;
  toolName: string;
  args: Record<string, unknown>;
  description: string;
  diff?: string;
  riskLevel: RiskLevel;
}

export interface SkillTool {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
  riskLevel: RiskLevel;
  execute(args: Record<string, unknown>, ctx: ToolContext): Promise<string>;
}

export interface Skill {
  name: string;
  description: string;
  tools: SkillTool[];
}

/** Decide whether a tool needs user approval under the active policy. */
export function needsApproval(risk: RiskLevel, policy: ApprovalPolicy): boolean {
  if (policy === "FULL_ACCESS") return false;
  if (policy === "ALWAYS_ASK") return risk !== "low";
  // ASK_DANGEROUS
  return risk === "high";
}
