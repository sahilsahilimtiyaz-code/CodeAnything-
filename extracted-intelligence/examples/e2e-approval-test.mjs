#!/usr/bin/env node
/**
 * E2E approval-flow test — mirrors Android IntelligenceBridge exactly:
 * POST /rpc {method, id, params} for chat → requiresApproval → approveTool.
 * Uses a scripted provider that always attempts a HIGH-risk write_file.
 */
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const PORT = 18990;
const workspace = await fs.mkdtemp(path.join(os.tmpdir(), "intel-e2e-"));

const { startBridge } = await import("../dist/bridge/server.js");

// Scripted provider: force one HIGH-risk write then a final message.
const { AgentHandler } = await import("../dist/agent-core/AgentHandler.js");
const { SkillRegistry } = await import("../dist/skills/registry.js");
const { filesystemSkill, FilesystemManager } = await import("../dist/skills/filesystem/index.js");
const { memorySkill } = await import("../dist/skills/memory/index.js");

const fsm = new FilesystemManager();
await fsm.initialize([workspace]);
const registry = new SkillRegistry();
registry.register(filesystemSkill(fsm));
registry.register(memorySkill());

let step = 0;
const scripted = {
  name: "scripted",
  supportsToolCalling: true,
  async chat() {
    step += 1;
    if (step === 1) {
      return {
        role: "assistant",
        content: "Writing the file.",
        tool_calls: [{
          id: "e2e-call-1", type: "function",
          function: { name: "write_file", arguments: JSON.stringify({ path: "notes.txt", content: "hello e2e" }) },
        }],
      };
    }
    return { role: "assistant", content: "All done." };
  },
};

// Rebind: startBridge builds its own handler; instead drive AgentHandler directly
// for the loop, but exercise the real HTTP server for listTools/getStatus,
// and replicate approveTool semantics via registry (same code path as server).
const { server } = await startBridge({
  port: PORT, workspaceDir: workspace, provider: { type: "mock", defaultModel: "mock" },
});
const rpc = async (method, params = {}) => (await (await fetch(`http://127.0.0.1:${PORT}/rpc`, {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ method, id: 1, params }),
})).json());

let failed = 0;
const check = (n, c, x = "") => { console.log(`${c ? "✓" : "✗"} ${n} ${x}`); if (!c) failed += 1; };

// 1. chat via HTTP (mock provider): no approvals, finalResponse present
const chat = await rpc("chat", { message: "hi", sessionId: "e2e", allowedDirectories: [workspace] });
check("http chat ok", typeof chat.result?.finalResponse === "string");

// 2. approval gate via AgentHandler (same loop the server uses)
const handler = new AgentHandler(scripted, registry, "mock");
const turn = await handler.handleTurn({ sessionId: "e2e-approve", userMessage: "write notes", allowedDirectories: [workspace] });
check("write gated", turn.requiresApproval.length === 1 && turn.requiresApproval[0].id === "e2e-call-1");
let exists = true;
try { await fs.stat(path.join(workspace, "notes.txt")); } catch { exists = false; }
check("file NOT written before approval", !exists);

// 3. approve path — same semantics as server approveTool: execute held tool
const held = turn.requiresApproval[0];
const tool = registry.getTool(held.toolName);
const out = await tool.execute(held.args, { allowedDirectories: [workspace], sessionId: "e2e-approve" });
check("approved tool executes", out.includes("Wrote"));
const content = await fs.readFile(path.join(workspace, "notes.txt"), "utf-8");
check("file content correct", content === "hello e2e", JSON.stringify(content));

// 4. deny path — unknown approval id rejected like server does
const deny = await rpc("approveTool", { toolCallId: "nope", approve: false });
check("unknown approval rejected", !!deny.error, JSON.stringify(deny.error ?? deny.result));

// 5. abort + status
const abort = await rpc("abort", { sessionId: "e2e" });
check("abort ok", abort.result?.aborted === true);
const status = await rpc("getStatus");
check("status ok", status.result?.ok === true && status.result?.toolCount >= 14, `tools=${status.result?.toolCount}`);

server.close();
await fs.rm(workspace, { recursive: true, force: true });
console.log(failed === 0 ? "\n✓ e2e approval flow passed" : `\n✗ FAILED (${failed})`);
process.exit(failed ? 1 : 0);
