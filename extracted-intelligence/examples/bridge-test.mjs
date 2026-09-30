#!/usr/bin/env node
/** End-to-end test of the compiled bridge server: health, listTools, chat (mock), approve flow. */
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const PORT = 18989;
const workspace = await fs.mkdtemp(path.join(os.tmpdir(), "intel-ws-"));
await fs.writeFile(path.join(workspace, "hello.txt"), "hello world\n");

const { startBridge } = await import("../dist/bridge/server.js");
const { server } = await startBridge({
  port: PORT,
  workspaceDir: workspace,
  provider: { type: "mock", defaultModel: "mock" },
});

const rpc = async (method, params = {}) => {
  const res = await fetch(`http://127.0.0.1:${PORT}/rpc`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ method, id: 1, params }),
  });
  return res.json();
};

let failed = 0;
const check = (name, cond, extra = "") => {
  console.log(`${cond ? "✓" : "✗"} ${name} ${extra}`);
  if (!cond) failed += 1;
};

const health = await (await fetch(`http://127.0.0.1:${PORT}/health`)).json();
check("health ok", health.ok === true, JSON.stringify(health));

const tools = await rpc("listTools");
check("listTools has 9 fs tools + memory/web/summarize", (tools.result?.tools?.length ?? 0) >= 14, `count=${tools.result?.tools?.length}`);
check("skills registered", (tools.result?.skills ?? []).includes("filesystem"), (tools.result?.skills ?? []).join(","));

const chat = await rpc("chat", { message: "list files", sessionId: "t1", allowedDirectories: [workspace] });
check("chat returns finalResponse", typeof chat.result?.finalResponse === "string" && chat.result.finalResponse.length > 0, (chat.result?.finalResponse ?? "").slice(0, 80));

// High-risk write should request approval, not execute
const { AgentHandler } = await import("../dist/agent-core/AgentHandler.js");
const { SkillRegistry } = await import("../dist/skills/registry.js");
const { filesystemSkill, FilesystemManager } = await import("../dist/skills/filesystem/index.js");
const { MockProvider } = await import("../dist/providers/index.js");
class WriteOnlyProvider extends MockProvider {
  async chat() {
    return { role: "assistant", content: "writing", tool_calls: [{ id: "w1", type: "function", function: { name: "write_file", arguments: JSON.stringify({ path: "evil.txt", content: "x" }) } }] };
  }
}
const fsm = new FilesystemManager();
await fsm.initialize([workspace]);
const reg = new SkillRegistry();
reg.register(filesystemSkill(fsm));
const h = new AgentHandler(new WriteOnlyProvider(), reg, "mock");
const turn = await h.handleTurn({ sessionId: "s-approval", userMessage: "write", allowedDirectories: [workspace] });
check("high-risk write gated by approval", turn.requiresApproval.length === 1 && turn.requiresApproval[0].toolName === "write_file", JSON.stringify(turn.requiresApproval.map((a) => a.toolName)));
try {
  await fs.stat(path.join(workspace, "evil.txt"));
  check("gated write did NOT touch disk", false);
} catch {
  check("gated write did NOT touch disk", true);
}

// Path sandboxing: read outside workspace must fail
const evil = await reg.getTool("read_file").execute({ path: "/etc/passwd" }, { allowedDirectories: [workspace], sessionId: "x" }).then(() => "NO-BLOCK", (e) => `BLOCKED:${e.message.slice(0, 30)}`);
check("path sandbox blocks /etc/passwd", String(evil).startsWith("BLOCKED"), String(evil));

server.close();
await fs.rm(workspace, { recursive: true, force: true });
console.log(failed === 0 ? "\n✓ bridge e2e passed" : `\n✗ bridge e2e FAILED (${failed})`);
process.exit(failed === 0 ? 0 : 1);
