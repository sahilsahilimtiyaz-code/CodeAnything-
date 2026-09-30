import http from "node:http";
import { AgentHandler } from "../agent-core/AgentHandler.js";
import { createProvider, type ProviderConfig } from "../providers/index.js";
import { SkillRegistry } from "../skills/registry.js";
import { filesystemSkill, FilesystemManager } from "../skills/filesystem/index.js";
import { memorySkill } from "../skills/memory/index.js";
import { summarizeSkill } from "../skills/summarize/index.js";
import { webBrowsingSkill } from "../skills/web-browsing/index.js";
import { loadSkillsFromRoot } from "../skill-loader/loader.js";
import { createLogger } from "../utils/logger.js";
import { BRIDGE_HOST, BRIDGE_PORT, type RpcRequest } from "./types.js";

const log = createLogger("bridge");

export interface BridgeOptions {
  host?: string;
  port?: number;
  workspaceDir?: string;
  provider?: ProviderConfig;
  skillsDir?: string;
}

function sendJson(res: http.ServerResponse, status: number, obj: unknown): void {
  const body = JSON.stringify(obj);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (c) => {
      data += c;
      if (data.length > 4_000_000) reject(new Error("Body too large"));
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

/** Start the intelligence HTTP/JSON-RPC service. Returns { server, handler, registry }. */
export async function startBridge(opts: BridgeOptions = {}) {
  const host = opts.host ?? process.env.INTEL_HOST ?? BRIDGE_HOST;
  const port = opts.port ?? Number(process.env.INTEL_PORT ?? BRIDGE_PORT);
  const workspaceDir = opts.workspaceDir ?? process.env.WORKSPACE_DIR ?? "/workspace";
  const skillsDir = opts.skillsDir ?? process.env.SKILLS_DIR;

  const providerCfg: ProviderConfig = opts.provider ?? {
    type: process.env.PROVIDER_TYPE ?? "ollama",
    baseUrl: process.env.OPENAI_BASE_URL ?? process.env.PROVIDER_BASE_URL ?? "http://127.0.0.1:11434/v1",
    apiKey: process.env.OPENAI_API_KEY ?? process.env.PROVIDER_API_KEY,
    defaultModel: process.env.OPENAI_MODEL ?? process.env.PROVIDER_MODEL ?? "qwen2.5-coder:7b",
  };
  // Convenience: OpenRouter / DashScope presets via env PROVIDER_TYPE
  const provider = createProvider(providerCfg);

  const fsManager = new FilesystemManager();
  await fsManager.initialize([workspaceDir]);

  const registry = new SkillRegistry();
  registry.register(filesystemSkill(fsManager));
  const memory = new Map<string, string>();
  registry.register(memorySkill(memory));
  registry.register(webBrowsingSkill());
  registry.register(summarizeSkill());
  if (skillsDir) {
    for (const s of await loadSkillsFromRoot(skillsDir)) registry.register(s);
  }

  const handler = new AgentHandler(provider, registry, providerCfg.defaultModel);
  const abortControllers = new Map<string, AbortController>();
  const pendingApprovals = new Map<string, { toolName: string; args: Record<string, unknown> }>();
  const sessionDirs = new Map<string, string[]>();

  const server = http.createServer(async (req, res) => {
    try {
      if (req.method === "GET" && (req.url === "/health" || req.url === "/")) {
        return sendJson(res, 200, { ok: true, service: "extracted-intelligence", tools: registry.allTools().length });
      }
      if (req.method !== "POST" || req.url !== "/rpc") {
        return sendJson(res, 404, { error: "Use POST /rpc or GET /health" });
      }
      const raw = await readBody(req);
      const rpc = JSON.parse(raw) as RpcRequest;
      const id = (rpc as any).id ?? 1;
      const params = (rpc as any).params ?? {};

      switch (rpc.method) {
        case "chat": {
          const sessionId = String(params.sessionId ?? "default");
          const ac = new AbortController();
          abortControllers.set(sessionId, ac);
          try {
            // Honor the client's allowlist: workspace always in scope, plus
            // extra guest paths (SAF bind mounts). The FilesystemManager
            // still confines every access to exactly this list.
            const requested = (Array.isArray(params.allowedDirectories)
              ? params.allowedDirectories.map(String)
              : []
            ).filter((p: string) => p.startsWith("/") && p !== workspaceDir);
            await fsManager.initialize([workspaceDir, ...requested]);
            sessionDirs.set(sessionId, [workspaceDir, ...requested]);
            let result;
            try {
              result = await handler.handleTurn({
                sessionId,
                userMessage: String(params.message ?? ""),
                history: Array.isArray(params.history)
                  ? params.history.map((h: any) => ({ role: h.role, content: String(h.content ?? "") }) as any)
                  : undefined,
                allowedDirectories: Array.isArray(params.allowedDirectories) && params.allowedDirectories.length
                  ? params.allowedDirectories.map(String)
                  : [workspaceDir],
                model: params.model ? String(params.model) : undefined,
                approvalPolicy: params.approvalPolicy,
                approvedToolCalls: params.approvedToolCalls,
                signal: ac.signal,
              });
            } catch (e: any) {
              // Provider unreachable / aborted: degrade gracefully so the
              // Android chat shows a readable message instead of HTTP 500.
              const msg = e?.name === "AbortError"
                ? "Cancelled."
                : `Model unavailable (${providerCfg.type} ${providerCfg.defaultModel ?? ""}): ${e?.message ?? e}. ` +
                  `Check Settings → Model provider, or start Ollama / set OPENAI_BASE_URL.`;
              log.warn("chat provider error:", e?.message);
              return sendJson(res, 200, {
                id,
                result: { finalResponse: msg, requiresApproval: [], executedTools: [], providerError: true },
              });
            }
            for (const a of result.requiresApproval) {
              pendingApprovals.set(a.id, { toolName: a.toolName, args: a.args });
            }
            return sendJson(res, 200, {
              id,
              result: {
                finalResponse: result.finalResponse,
                requiresApproval: result.requiresApproval,
                executedTools: result.executedTools,
              },
            });
          } finally {
            abortControllers.delete(sessionId);
          }
        }
        case "listTools":
          return sendJson(res, 200, { id, result: { tools: registry.toolDefinitions(), skills: registry.skillNames() } });
        case "approveTool": {
          const toolCallId = String(params.toolCallId ?? params.id ?? "");
          const approve = params.approve !== false;
          const pending = pendingApprovals.get(toolCallId);
          if (!pending) return sendJson(res, 200, { id, error: { code: -32004, message: "Unknown approval id" } });
          if (!approve) {
            pendingApprovals.delete(toolCallId);
            return sendJson(res, 200, { id, result: { approved: false, result: "Denied by user." } });
          }
          const tool = registry.getTool(pending.toolName);
          if (!tool) return sendJson(res, 200, { id, error: { code: -32005, message: `Unknown tool ${pending.toolName}` } });
          try {
            const out = await tool.execute(pending.args, {
              allowedDirectories: sessionDirs.get(String(params.sessionId ?? "default")) ?? [workspaceDir],
              sessionId: String(params.sessionId ?? "default"),
            });
            pendingApprovals.delete(toolCallId);
            return sendJson(res, 200, { id, result: { approved: true, result: String(out).slice(0, 8000) } });
          } catch (e: any) {
            return sendJson(res, 200, { id, error: { code: -32006, message: e?.message ?? "Tool failed" } });
          }
        }
        case "getStatus":
          return sendJson(res, 200, {
            id,
            result: {
              ok: true,
              provider: providerCfg.type,
              model: providerCfg.defaultModel,
              workspaceDir,
              skills: registry.skillNames(),
              toolCount: registry.allTools().length,
              pendingApprovals: pendingApprovals.size,
            },
          });
        case "abort": {
          const sid = String(params.sessionId ?? "default");
          abortControllers.get(sid)?.abort();
          handler.clearSession(sid);
          return sendJson(res, 200, { id, result: { aborted: true } });
        }
        case "loadSkills": {
          const dir = String(params.dir ?? skillsDir ?? "");
          if (!dir) return sendJson(res, 200, { id, error: { code: -32007, message: "No skills dir given" } });
          const skills = await loadSkillsFromRoot(dir);
          for (const s of skills) registry.register(s);
          return sendJson(res, 200, { id, result: { loaded: skills.map((s) => s.name) } });
        }
        default:
          return sendJson(res, 200, { id, error: { code: -32601, message: `Unknown method ${String((rpc as any).method)}` } });
      }
    } catch (e: any) {
      log.error("rpc error", e?.message);
      return sendJson(res, 500, { id: 0, error: { code: -32000, message: e?.message ?? "Internal error" } });
    }
  });

  await new Promise<void>((resolve) => server.listen(port, host, resolve));
  log.info(`listening on http://${host}:${port} workspace=${workspaceDir} provider=${providerCfg.type}`);
  return { server, handler, registry, fsManager, host, port };
}
