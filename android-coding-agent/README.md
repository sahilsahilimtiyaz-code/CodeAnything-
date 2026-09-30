# Coding Agent Mobile

Premium Android skeleton for an on-device AI coding agent (AndCode + AnyClaw style), designed to host:

- PRoot + Alpine Linux runtime
- Coding agent CLIs (OpenCode, Qwen Code, Claude Code, Codex…)
- Node `extracted-intelligence` service (agent loop, MCP, skills, providers)
- Native Jetpack Compose UI (chat, workspace, tool approvals, settings)

## Architecture

```
app/
├── ui/           Chat · Workspace · Settings · Approvals (Compose)
├── runtime/      RuntimeManager + AgentRuntimeService (foreground)
├── bridge/       IntelligenceBridge → localhost Node RPC
├── agents/       AgentRepository (orchestration)
├── domain/       Shared models
└── di/           Hilt
```

Paired with:

```
../extracted-intelligence/   Node agent core, skills, providers
```

## Stack

- Kotlin 2.0 · Jetpack Compose · Material 3
- Hilt · Coroutines · OkHttp · Timber · DataStore
- minSdk 26 · targetSdk 35 · arm64-v8a

## Status (skeleton)

| Area | Status |
|------|--------|
| Project / Gradle / Manifest | Ready |
| Theme + navigation | Ready |
| Chat UI + ViewModel | Ready |
| Workspace file browser | Ready |
| Settings (runtime + provider) | Ready |
| Tool approval banner | Ready |
| RuntimeManager state machine | Ready (Alpine download + SHA-256, bundled PRoot, apk/node bootstrap, Node launch, OpenCode CLI install, exec timeouts) |
| Foreground service | Ready |
| IntelligenceBridge RPC client | Ready (chat, listTools, approveTool, getStatus, abort, loadSkills) |
| AgentRepository | Ready (approve/deny wired to bridge, agent selector, CLI install status) |
| Agent selector (Chat top bar + Settings) | Ready (intelligence-backed for all kinds; native CLI loops = Phase 4) |
| Diff viewer for approvals | Ready (color-coded, scrollable, 80-line cap) |
| SAF external folder picker | Ready (browse-only; agent sandbox stays on app workspace) |
| Unit tests (`RuntimeArtifactsTest`) | Ready (`:app:testDebugUnitTest`) |

## Next implementation steps

1. ~~Bundle PRoot native libs + Alpine download with SHA-256 (AndCode pattern)~~ Done — `libproot.so` vendored (AndCode v1.2.25, PRoot 5.4.0, see `app/src/main/jniLibs/README.md`); Alpine 3.24.1 SHA verified against official CDN
2. ~~Start Node intelligence service inside Alpine and point the bridge at it~~ Done — validated end-to-end inside PRoot+Alpine on aarch64 (health, chat, tools)
3. Wire real provider config into the Node process (via Settings → saveProvider → bridge env)
4. ~~SAF folder picker for external projects~~ Done (browse-only)
5. ~~Diff viewer for `edit_file` approvals~~ Done
6. ~~Multi-agent switcher (OpenCode / Qwen / Claude)~~ Done in UI (intelligence-backed); OpenCode CLI install implemented (`installAgentCli`, musl URL+SHA pinned) — native CLI chat loops remain Phase 4

## On-device test checklist (needs an arm64 phone)

1. Install `app-debug.apk`; Settings → Install runtime (downloads Alpine, verifies SHA, extracts, `apk add`s node/git)
2. Start runtime → Chat completes a tool turn against workspace files
3. Trigger a `write_file` → Allow/Deny appears in `ApprovalBanner` with color diff
4. Settings → Coding agent → OpenCode → Install CLI; switch agents in Chat top bar
5. Workspace → folder icon → open an external (SAF) folder

## Build

Gradle wrapper is committed (`gradlew`). Requirements: JDK 17+, Android SDK
(platform 35, build-tools 35.0.0). Point the SDK via `local.properties`
(`sdk.dir=...`) or `ANDROID_HOME`.

```bash
# Node brain first (ships into APK assets via syncIntelligenceAssets)
cd ../extracted-intelligence && npm run build && node examples/bridge-test.mjs

# Android side (run with sh if the FS is mounted noexec)
sh gradlew :app:assembleDebug
```

## License

MIT (align with extracted-intelligence and upstream agents).
