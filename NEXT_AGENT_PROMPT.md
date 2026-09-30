# HANDOFF PROMPT — Mobile AI Coding Agent (AndCode + AnyClaw style)

Copy everything below the line and give it to your next AI coding agent (Claude Code, Cursor, OpenCode, Codex, etc.).

---

## Mission

Continue building a **native Android AI coding agent app** in the style of **AndCode + AnyClaw 5-in-1**, powered by intelligence extracted from **AnythingLLM** (especially Qwen-first providers, MCP, skills, filesystem tools).

You are **not** starting from zero. Substantial work is already done in this zip.

## What’s in the zip

```
coding-agent-mobile-handoff.zip
├── extracted-intelligence/     # Node/TS agent brain
│   ├── agent-core/             # AgentHandler + AIbitat loop (working)
│   ├── providers/              # Ollama, OpenRouter, Generic OpenAI, Anthropic, DeepSeek, Qwen
│   ├── skills/                 # filesystem (9 tools), memory, web-browsing, summarize + registry
│   ├── skill-loader/           # Import plugin.json + handler.js skills
│   ├── bridge/                 # Types for Android ↔ Node RPC
│   ├── mcp/                    # MCP types (stubs)
│   └── examples/smoke-test.mjs # PASSED smoke test (mock + real endpoint support)
│
└── android-coding-agent/       # Kotlin + Compose Android skeleton
    ├── runtime/                # RuntimeManager + AgentRuntimeService (foreground)
    ├── bridge/                 # IntelligenceBridge (HTTP JSON-RPC client)
    ├── agents/                 # AgentRepository
    ├── ui/                     # Chat, Workspace, Settings, Approvals (Material 3)
    ├── domain/                 # Shared models
    └── di/                     # Hilt
```

**Smoke test already passed** for the Node side (list_directory → memory_store → final reply).

## Product goal (do not change)

- Native Android app (not a WebView wrapper as the main UX)
- On-device Linux runtime (PRoot + Alpine), no root required
- Real coding agents (OpenCode, Qwen Code, Claude Code, Codex…) + intelligence layer
- Touch UI: chat, file tree, diffs, Git, **tool approvals**
- Strong **Qwen** support (local Ollama + OpenRouter + custom endpoints)
- Optional remote AnythingLLM later — not required for MVP

## Architecture (keep this)

```
Compose UI → AgentRepository → IntelligenceBridge → Node extracted-intelligence (inside Alpine)
                                      ↓
                            RuntimeManager / PRoot + Alpine
```

## What is DONE (do not redo)

1. **extracted-intelligence**
   - Full agent loop (`AIbitat` + `AgentHandler`)
   - FilesystemManager with path sandboxing + atomic writes + unified diffs
   - 9 filesystem tools with risk levels
   - Providers with **custom endpoint** support
   - Skill registry + memory / web / summarize skills
   - Imported skill loader (plugin.json style)
   - Working `examples/smoke-test.mjs`

2. **android-coding-agent**
   - Full Gradle/Compose/Hilt project structure
   - Chat UI, Workspace browser, Settings, Approval banner
   - RuntimeManager state machine (install/start/stop) — **download/PRoot still TODO**
   - Foreground service
   - IntelligenceBridge client
   - AgentRepository orchestration

## What YOU should do next (priority order)

### Priority 1 — Make the Android app buildable
1. Open `android-coding-agent` in Android Studio / ensure Gradle wrapper exists (`gradle wrapper` if missing).
2. Fix any missing icons/resources so `:app:assembleDebug` succeeds.
3. Confirm Hilt/Compose compile on minSdk 26 / target 35 / arm64-v8a.

### Priority 2 — Real on-device runtime (AndCode pattern)
1. Implement Alpine minirootfs download + SHA-256 verification in `RuntimeManager`.
2. Bundle or download PRoot arm64-v8a native libs.
3. Extract to app-private storage; install git, bash, curl, node, ca-certificates inside Alpine.
4. Start the **extracted-intelligence** Node service inside the rootfs on `127.0.0.1:18789`.
5. Keep tool approvals and path sandboxing — never give unrestricted FS access.

### Priority 3 — Wire Node ↔ Android end-to-end
1. Implement a small HTTP/JSON-RPC server in `extracted-intelligence/bridge` matching `IntelligenceBridge` methods: `chat`, `listTools`, `approveTool`, `getStatus`, `abort`, `loadSkills`.
2. On first successful chat from the Android UI, confirm tools run against `/workspace` only.
3. Surface high-risk tool approvals in the existing `ApprovalBanner`.

### Priority 4 — Coding-agent CLIs (multi-agent)
1. Install OpenCode (musl) and/or Qwen Code inside Alpine.
2. Add agent selector in Settings / Chat top bar.
3. Prefer intelligence layer for skills/MCP; use CLIs for pure coding loops when selected.

### Priority 5 — Polish
- SAF folder picker for external projects
- Diff viewer for `edit_file` approvals
- Session persistence
- Provider presets (Ollama local, OpenRouter, custom)

## Hard rules (anti-bug)

- **Do not** try to run full AnythingLLM Express + React + vector DB on the phone.
- **Do not** grant agents the entire Android filesystem.
- Every write/shell action must support approval (`ALWAYS_ASK` / `ASK_DANGEROUS` / `FULL_ACCESS`).
- All tool results remain **strings**.
- Prefer arm64-v8a only for MVP.
- Keep `extracted-intelligence` dependency surface small (diff, zod only if needed).

## How to verify

```bash
# Node side
cd extracted-intelligence
node examples/smoke-test.mjs
# Optional real model:
# OPENAI_BASE_URL=http://127.0.0.1:11434/v1 OPENAI_MODEL=qwen2.5-coder:7b node examples/smoke-test.mjs

# Android side
cd android-coding-agent
./gradlew :app:assembleDebug
```

## Success criteria for your next milestone

1. `assembleDebug` succeeds.
2. App installs; Settings can “Install runtime” without crash (even if still skeleton).
3. When runtime + Node service are up, Chat can complete one tool-using turn against workspace files.
4. High-risk tools show in ApprovalBanner before applying.

## Reference products

- AndCode: https://github.com/yuga-hashimoto/and-code (PRoot + Alpine + native GUI)
- AnyClaw / openclaw-android-assistant (multi-agent packaging, foreground service)
- AnythingLLM was only a source of **intelligence modules** — already extracted

## Start here

1. Unzip the handoff archive.
2. Read `extracted-intelligence/README.md` and `android-coding-agent/README.md`.
3. Run the smoke test.
4. Make Android build.
5. Implement Priority 2 (real runtime) then Priority 3 (end-to-end chat).

Work carefully, keep commits small, and do not expand scope beyond the priority list until the MVP path above works.
