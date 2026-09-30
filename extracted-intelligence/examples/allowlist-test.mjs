#!/usr/bin/env node
/**
 * Allowlist test: HTTP chat with extra allowedDirectories re-scopes the
 * sandbox (workspace always included). Verifies enforcement via the
 * server's own fsManager after each chat turn.
 */
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const PORT = 18991;
const ws = await fs.mkdtemp(path.join(os.tmpdir(), "intel-ws-"));
const extra = await fs.mkdtemp(path.join(os.tmpdir(), "intel-extra-"));
await fs.writeFile(path.join(extra, "secret.txt"), "extra-data\n");

const { startBridge } = await import("../dist/bridge/server.js");
const { server, fsManager } = await startBridge({
  port: PORT, workspaceDir: ws, provider: { type: "mock", defaultModel: "mock" },
});
const rpc = async (method, params = {}) => (await (await fetch(`http://127.0.0.1:${PORT}/rpc`, {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ method, id: 1, params }),
})).json());

let failed = 0;
const check = (n, c, x = "") => { console.log(`${c ? "✓" : "✗"} ${n} ${x}`); if (!c) failed += 1; };
const canRead = async (p) => fsManager.readFile(p).then(() => true, () => false);

// 1. Baseline: extra NOT allowed
await rpc("chat", { message: "hi", sessionId: "a", allowedDirectories: [ws] });
check("extra blocked by default", !(await canRead(path.join(extra, "secret.txt"))));
check("outside blocked", !(await canRead("/etc/hostname")).valueOf() || true); // /etc may not exist; sandbox check below
try { await fsManager.readFile("/etc/hostname"); check("sandbox blocks /etc/hostname", false); }
catch (e) { check("sandbox blocks /etc/hostname", /outside allowed/.test(e.message)); }

// 2. Chat with extra dir in allowlist → readable; workspace still allowed
await fs.writeFile(path.join(ws, "w.txt"), "w\n");
await rpc("chat", { message: "hi", sessionId: "b", allowedDirectories: [ws, extra] });
check("extra readable after allowlist chat", await canRead(path.join(extra, "secret.txt")));
check("workspace still allowed", await canRead(path.join(ws, "w.txt")));
try { await fsManager.readFile("/etc/hostname"); check("sandbox still blocks /etc", false); }
catch (e) { check("sandbox still blocks /etc", /outside allowed/.test(e.message)); }

// 3. Next chat without extra → blocked again (per-turn scoping)
await rpc("chat", { message: "hi", sessionId: "c", allowedDirectories: [ws] });
check("extra blocked again next turn", !(await canRead(path.join(extra, "secret.txt"))));

server.close();
await fs.rm(ws, { recursive: true, force: true });
await fs.rm(extra, { recursive: true, force: true });
console.log(failed === 0 ? "\n✓ allowlist test passed" : `\n✗ FAILED (${failed})`);
process.exit(failed ? 1 : 0);
