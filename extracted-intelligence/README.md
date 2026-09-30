# extracted-intelligence

Slimmed, mobile-ready extraction of the most valuable parts of AnythingLLM:

- Agent core (AgentHandler + AIbitat loop)
- MCP compatibility layer
- Filesystem skill (security-hardened)
- Skill loader (plugin.json + handler.js style)
- Qwen-first LLM providers
- Clean interfaces for Android / on-device Alpine use

## Design Goals

1. Run inside Alpine/PRoot on Android (or as a local Node service)
2. Zero dependency on AnythingLLM Express, Prisma, React, or full vector DB
3. Strict path sandboxing and tool-approval support
4. First-class Qwen support (local Ollama + OpenRouter + generic)
5. All tool results are strings (AnythingLLM contract)

## Folder Overview

- `agent-core/`     – Core agent loop and handler
- `mcp/`            – MCP server management
- `skills/`         – Built-in skills (filesystem is critical)
- `skill-loader/`   – Load custom skills from disk
- `providers/`      – LLM providers (Qwen-first)
- `bridge/`         – Communication with Kotlin/Android side (`server.ts` HTTP/JSON-RPC, `serve.ts` entry)
- `utils/`          – Shared helpers (path security, diff, logging)
- `examples/`       – smoke-test, bridge-test, e2e-approval-test, allowlist-test

## Verify

```bash
npm install --no-bin-links --no-audit --no-fund  # --no-bin-links only on no-symlink filesystems
npm run build     # tsc → dist/ (+ dist/package.json ESM marker via postbuild)
npm test          # 4 suites, ~30 checks, all offline (mock provider)
PORT=18789 node dist/bridge/serve.js
```

## Bridge protocol (Android IntelligenceBridge ↔ server)

`POST /rpc {method, id, params}` · `GET /health`

| method | params | result |
|---|---|---|
| chat | message, sessionId, allowedDirectories[], history[], model?, approvalPolicy?, approvedToolCalls? | finalResponse, requiresApproval[], executedTools[], providerError? |
| listTools | — | tools[], skills[] |
| approveTool | sessionId, toolCallId, approve | approved, result |
| getStatus | — | ok, provider, model, skills, toolCount, pendingApprovals |
| abort | sessionId | aborted |
| loadSkills | dir | loaded[] |

`chat` re-scopes the sandbox per turn: `allowedDirectories` (guest
absolute paths) plus the server workspace, enforced by
`FilesystemManager`; `approveTool` reuses the same session dirs.
High-risk tools never execute without an approval round-trip.

## Security Rules (non-negotiable)

- Every file path must pass through `IFilesystemManager.isPathAllowed()`
- Medium/high risk tools must produce an `ApprovalRequest` when policy requires it
- No unrestricted host filesystem access
- All long-running operations accept `AbortSignal`

## Status (implemented — see examples/ for proofs)

- Agent loop (`AgentHandler` + AIbitat), sandboxed filesystem (9 tools),
  Qwen-first providers, skill registry + loader, MCP seam
- Bridge server matching the Android `IntelligenceBridge` protocol
- Zero runtime dependencies (`diff`/`zod` removed; minimal inline unify-diff)
