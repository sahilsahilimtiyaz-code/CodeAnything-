/** JSON-RPC protocol shared with Android IntelligenceBridge. Methods: chat | listTools | approveTool | getStatus | abort | loadSkills */
export interface RpcRequest {
  method: "chat" | "listTools" | "approveTool" | "getStatus" | "abort" | "loadSkills";
  id: string | number;
  params: Record<string, any>;
}

export interface RpcSuccess {
  id: string | number;
  result: Record<string, any>;
}

export interface RpcError {
  id: string | number;
  error: { code: number; message: string };
}

export type RpcResponse = RpcSuccess | RpcError;

export interface ChatParams {
  message: string;
  sessionId: string;
  allowedDirectories: string[];
  history?: { role: string; content: string }[];
  model?: string;
  approvalPolicy?: "ALWAYS_ASK" | "ASK_DANGEROUS" | "FULL_ACCESS";
  approvedToolCalls?: string[];
}

export const BRIDGE_PORT = 18789;
export const BRIDGE_HOST = "127.0.0.1";
