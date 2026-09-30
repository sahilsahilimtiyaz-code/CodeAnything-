import type { SkillTool, ToolContext } from "../skills/types.js";

export interface MCPServerConfig {
  name: string;
  command: string;
  args?: string[];
  env?: Record<string, string>;
}

export interface MCPTool extends SkillTool {
  serverName: string;
}

/**
 * MCP stub: registry-compatible placeholder.
 * Full stdio-MCP is out of scope for MVP; this keeps the seam so Android
 * can list MCP tools later without changing the bridge protocol.
 */
export class MCPManager {
  private tools = new Map<string, MCPTool>();

  registerTool(tool: MCPTool): void {
    this.tools.set(tool.name, tool);
  }

  allTools(): MCPTool[] {
    return [...this.tools.values()];
  }

  getTool(name: string): MCPTool | undefined {
    return this.tools.get(name);
  }

  serverNames(): string[] {
    return [...new Set([...this.tools.values()].map((t) => t.serverName))];
  }

  async callTool(name: string, args: Record<string, unknown>, ctx: ToolContext): Promise<string> {
    const t = this.tools.get(name);
    if (!t) throw new Error(`Unknown MCP tool ${name}`);
    return t.execute(args, ctx);
  }
}
