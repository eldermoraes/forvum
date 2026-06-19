<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/forvum-mark.svg">
    <img src="docs/brand/forvum-mark-light.svg" alt="Forvum" width="120" />
  </picture>
</p>

# Forvum

*A JVM platform for personal AI agents, designed in the open. The plan is written; the code follows, milestone by milestone.*

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

> **Forvum is being designed and built around the principles documented in [Context Engineering for Multi-LLM Low-Latency Agents](docs/CONTEXT-ENGINEERING.md). See [how Forvum maps to those principles](docs/CONTEXT-ENGINEERING-MAPPING.md).**

Forvum is a JVM platform being built to let anyone run personal AI agents on their own machine — with the same discipline enterprise Java brings to any other production system. The full architectural vision lives in [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md), covering the core contracts for agents, events, budgets, and scope isolation. The `main` branch ships the complete v0.1 feature set (milestones M1–M20) and the design documentation; a working vertical slice — a single agent against a local Ollama model via an interactive CLI — lives on the `demo/conference-mvp` branch. Implementation proceeds milestone by milestone. If you're a Java developer who wants an AI layer on your own terms, contributions to design or code are welcome.

## The name

*Forvum* is a fusion of two Latin words.

**Forum** — the public space where Roman citizens gathered to deliberate, debate, and decide.

**Quorum** — the minimum number of voices required for a collective decision to stand.

The platform inherits both. Forvum is where agents convene, deliberate, and act — and where every decision is shaped by structure rather than by an opaque single step. Each turn, each tool call, each fallback, each judgment is observable in the ledger; nothing dissolves into a black box.

The result is not an orchestrator that commands silently from the center. It is an architecture where coordination, evidence, and control are all first-class — and where the work of an agent system can finally be reasoned about.

## Status

Phase-1 MVP feature-complete. `main` ships milestones **M1–M20 (EPIC-1 / v0.1)** — the multi-module reactor, core domain contracts, plugin SDK, and config loader (M1–M4); SQLite persistence, `@AgentScoped` isolation, `AgentRegistry`, and fallback chains (M5–M8); the provider fleet — Ollama, Anthropic, OpenAI, Google (M9–M12); the tool registry, permission scopes, and filesystem tools (M13–M14); the TUI, Web, and Telegram channels (M15–M17); the LangGraph4j supervisor graph that wires tool execution into the turn (M18); file-driven crons (M19); and the GraalVM native single-binary with a picocli command-mode/lazy-DB **&lt;200 ms cold-start gate** (M20) — plus the architectural design docs. A conference-demo MVP — a single agent against a local Ollama model via an interactive CLI — lives on the `demo/conference-mvp` branch. v0.1 is feature-complete; not yet hardened for production.

## Install

The fastest path is the one-command installer. It downloads the single-binary GraalVM native build
(~40 MB — one executable, no JVM, no Docker, no Node) for your platform from the latest
[GitHub Release](https://github.com/eldermoraes/forvum/releases), verifies its `sha256`, and installs
it to `$HOME/.local/bin`:

```bash
curl -fsSL https://raw.githubusercontent.com/eldermoraes/forvum/main/install.sh | sh
```

Supported platforms: **linux-x64** and **macos-arm64**. Override the target directory or pin a
version with `FORVUM_INSTALL_DIR` / `FORVUM_VERSION`:

```bash
curl -fsSL https://raw.githubusercontent.com/eldermoraes/forvum/main/install.sh \
  | FORVUM_INSTALL_DIR=/usr/local/bin FORVUM_VERSION=v0.1.0 sh
```

**Manual download.** Grab the native binary for your platform from the
[latest release](https://github.com/eldermoraes/forvum/releases/latest), verify it against the
`.sha256` shipped alongside, then make it executable:

```bash
# linux-x64 (use forvum-macos-arm64 on Apple silicon)
curl -fsSLO https://github.com/eldermoraes/forvum/releases/latest/download/forvum-linux-x64
curl -fsSLO https://github.com/eldermoraes/forvum/releases/latest/download/forvum-linux-x64.sha256
shasum -a 256 -c forvum-linux-x64.sha256       # or: sha256sum -c
chmod +x forvum-linux-x64 && sudo mv forvum-linux-x64 /usr/local/bin/forvum
```

Then scaffold your home and start a session:

```bash
forvum init                     # scaffold ~/.forvum (owner-only: 0700 dirs / 0600 files)
forvum doctor                   # validate ~/.forvum config (exits non-zero on problems)
forvum                          # interactive session: banner + `forvum> ` prompt; /exit or Ctrl+D quits
forvum ask "who are you?"       # one non-interactive turn (stdout is just the reply)
forvum --help                   # also --version
```

For a real conversation you need a model provider — the zero-config default is a local
[Ollama](https://ollama.com):

```bash
ollama serve &            # local model server, no API key
ollama pull qwen3:1.7b    # the model the default agent is pinned to
```

### Build from source

> v0.1 is feature-complete but not yet hardened for production. Building from source is the path for
> platforms without a published binary, or for development.

**Prerequisites:** Java 25 (the bundled `./mvnw` provides Maven); for the native binary, GraalVM CE 25 /
Mandrel 25.0.x-Final.

**Native single binary** (the primary target — one executable, no JVM, &lt;200 ms cold start):

```bash
# -am pulls in the reactor deps — builds from a fresh clone; -DskipTests skips the
# development test suite (installing doesn't need it — contributors run ./mvnw verify)
./mvnw -Pnative -pl forvum-app -am package -DskipTests
BIN=$(ls forvum-app/target/forvum-app-*-runner)
"$BIN" init                     # scaffold ~/.forvum (owner-only: 0700 dirs / 0600 files)
"$BIN" doctor                   # validate ~/.forvum config (exits non-zero on problems)
"$BIN"                          # interactive session: banner + `forvum> ` prompt; /exit or Ctrl+D quits
"$BIN" ask "who are you?"       # one non-interactive turn (stdout is just the reply)
echo "who are you?" | "$BIN"    # the piped REPL also works (banner line + reply)
"$BIN" --help                   # also --version
```

**JVM fast-jar** (no GraalVM needed — development / drop-in plugins). Same steps, same `init`:

```bash
./mvnw package -pl forvum-app -am -DskipTests
JAR=forvum-app/target/quarkus-app/quarkus-run.jar
java -jar "$JAR" init           # first run only — without it there are no agents/channels yet
java -jar "$JAR"                # interactive session (the same `forvum> ` REPL as the native binary)
```

Configuration lives in `~/.forvum` (override with the `FORVUM_HOME` env var). Cloud providers
(Anthropic, OpenAI, Google) need an API key. The quickest path is `forvum provider add <provider>`
(e.g. `forvum provider add anthropic`): it prompts for the key (no echo), stores it owner-only (`0600`)
under `~/.forvum/state/credentials/`, runs a smoke test, and offers to make `<provider>:<model>` agent
`main`'s default. You can still export the key as an env var
(`QUARKUS_LANGCHAIN4J_ANTHROPIC_API_KEY`, which takes precedence) and point an agent at a model by
editing `~/.forvum/agents/main.json` (e.g. `anthropic:claude-sonnet-4-...`). GitHub Copilot uses a
device-code login instead of an API key: run `forvum copilot login`, authorize in the browser, then point
an agent at a `copilot:<model>` ModelRef (e.g. `copilot:gpt-4o`). If a turn fails with
`Is the model provider running?`, start Ollama (`ollama serve`) and pull the model named in the error.
The first turn can take several seconds while Ollama loads the model; the reply prints when complete
(v0.1 has no token streaming or spinner yet).

Chat channels are enabled the same way, one JSON file each under `~/.forvum/channels/` — e.g.
`matrix.json` (`homeserver`, `accessToken`, and `userId` — all three required — plus an optional
`allowedUserIds`) connects the assistant to a Matrix homeserver. `userId` is the bot's own Matrix id
(e.g. `@bot:example.org`): Matrix `/sync` echoes the bot's own messages, so without it the bot cannot
filter itself and the channel refuses to start. **The Matrix channel supports unencrypted rooms only**:
end-to-end encryption (E2EE) is not yet supported
([#125](https://github.com/eldermoraes/forvum/issues/125)) — the bot stays silent in encrypted rooms.

### Optional: the Signal channel (connect-only)

The Signal channel connects to an **operator-run [signal-cli](https://github.com/AsamK/signal-cli)
HTTP daemon** — Forvum does not spawn, install, or manage signal-cli (daemon spawn/install is a
documented follow-up). With your Signal account already registered (or linked) in signal-cli, start
the daemon yourself:

```bash
signal-cli -a +15550001111 daemon --http localhost:8080
```

then enable the channel in `~/.forvum/channels/signal.json`:

```json
{ "baseUrl": "http://localhost:8080", "account": "+15550001111", "allowedUserIds": ["+15557772222"] }
```

Forvum receives messages over the daemon's SSE event stream (`GET /api/v1/events`) and replies via
its JSON-RPC endpoint (`POST /api/v1/rpc`). Direct text messages only in this release — receipts,
typing notifications, sync messages, edited messages, and group messages are ignored (group and edit
support are documented limitations), and the bot never replies to its own account (self-echo). An
empty `allowedUserIds` allows any sender; a non-empty list restricts to those phone numbers/UUIDs.

### Kubernetes (team-assistant mode)

A Helm chart under [`deploy/helm/forvum`](deploy/helm/forvum) deploys Forvum as a **per-namespace team
assistant**: each Helm release is one isolated instance with its own persistent SQLite state, so a
namespace gets a private assistant whose memory no other namespace can read (the isolation is structural
— Kubernetes namespacing plus a per-release `PersistentVolumeClaim`). The chart runs the native
single-binary container image (published to GHCR by the release pipeline) as a long-lived Web-channel
HTTP server.

```bash
# Give each team its own isolated assistant:
helm install forvum deploy/helm/forvum --namespace team-a --create-namespace
helm install forvum deploy/helm/forvum --namespace team-b --create-namespace
kubectl -n team-a port-forward svc/forvum 8080:8080
```

Configuration (`agents/`, `channels/`, …) comes from values and is projected into `$FORVUM_HOME` from a
ConfigMap; provider API keys are wired from Kubernetes Secrets. There is no Kubernetes operator (out of
scope for v0.1). See [`deploy/helm/README.md`](deploy/helm/README.md) for the full isolation model and
configuration reference.

## Quick demo

The demo lives on the `demo/conference-mvp` branch and runs a single agent against an Ollama model via an interactive CLI. It predates v0.1 — for everyday use, the install path above on `main` now provides the same interactive experience (banner, `forvum>` prompt, `/exit`) with the full v0.1 feature set; this branch remains as the frozen conference snapshot.

![FORVUM CLI session — boot banner, agent ready prompt, and a two-turn interaction](docs/images/cli-demo.png)

**Prerequisites:**

- Java 25
- Maven 3.9+ (or use the bundled `./mvnw`)
- [Ollama](https://ollama.com/) installed and running (`ollama serve`)
- A model reference configured in `agents/demo.json`. The default is `ollama:gemma4:31b-cloud`, which requires Ollama cloud access — run `ollama pull gemma4:31b-cloud` with your Ollama account signed in; see [ollama.com](https://ollama.com/) for account setup. For a fully local alternative, edit `agents/demo.json` to use a model you have pulled locally — models with at least 3B parameters tend to follow system prompts reliably.

**Optional (local models only):** export `OLLAMA_KEEP_ALIVE=30m` before running to prevent Ollama from unloading the model during idle periods. The default keep-alive is 5 minutes, which can trigger reload latency between turns during longer sessions.

**Run:**

```bash
git clone https://github.com/eldermoraes/forvum.git
cd forvum
git checkout demo/conference-mvp

# Optional — local models only
export OLLAMA_KEEP_ALIVE=30m

./mvnw package -pl forvum-app -am -DskipTests
java -jar forvum-app/target/quarkus-app/quarkus-run.jar
```

**Example interaction:**

```
Forvum agent 'demo' ready (model: ollama:gemma4:31b-cloud)
Type your message and press Enter. Use /exit or Ctrl+D to quit.

forvum> who are you?
I am Forvum, your local-first personal AI assistant. I'm here to help you manage your projects, ideas, and tasks.

forvum> who made you?
I am Forvum, a personal AI assistant running on the JVM via Quarkus and LangChain4j.

forvum> /exit
```

## Architecture

Forvum is organized as a four-layer Maven reactor (foundation → SDK → engine → first-party channel/provider/tool extensions, assembled by `forvum-app`), structured to match the architectural vision. The `main` branch now implements the full v0.1 feature set; the five core modules below are the architectural backbone, with Layer-3 channel/provider/tool extensions added milestone by milestone.

- **`forvum-core`** — pure Java value contracts with no framework dependencies (agent IDs, model references, event types). Specified in [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §4.3.
- **`forvum-sdk`** — public SPI for extension points (model providers, channels, tools). Specified in [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §2.2.
- **`forvum-engine`** — Quarkus engine, extension-agnostic. Orchestrates agent lifecycle, resolves specs, emits events. Specified in [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §2.3.
- **`forvum-bom`** — centralized dependency version management.
- **`forvum-app`** — the runnable assembly. Wires providers, hosts the TamboUI TUI, boots Quarkus.

For the full architecture — decisions, tradeoffs, and deferred design — read [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md); see [docs/ISSUES.md](docs/ISSUES.md) for the per-step roadmap.

## Roadmap

Phase 1 (the v0.1 MVP) — milestones M1 through M20 — is complete. Phase 2 (v0.5, parity with OpenClaw) is the next arc; see [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §7.2.

- **M1–M20 (complete)** — the multi-module reactor + core contracts + plugin SDK + config loader (M1–M4); SQLite/Flyway, `@AgentScoped`, `AgentRegistry`, and fallback chains (M5–M8); the provider fleet — Ollama, Anthropic, OpenAI, Google (M9–M12); the tool registry, permission scopes, and filesystem tools (M13–M14); the TUI, Web, and Telegram channels (M15–M17); the LangGraph4j supervisor graph (M18); file-driven crons (M19); and the GraalVM native single-binary + CI matrix with the &lt;200 ms cold-start gate (M20).
- **Phase 2 (planned)** — browser tool, MCP bridge, sub-agent spawning, judging, observability dashboards, and production hardening.

Detailed milestone scope is in [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §7.

## Contributing

Contributions to design and code are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to contribute — open an issue or discussion for architectural changes before a PR. [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) is the architectural source of truth.

## License

Apache 2.0 — see [LICENSE](LICENSE).
