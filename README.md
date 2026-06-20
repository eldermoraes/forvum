<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/forvum-mark.svg">
    <img src="docs/brand/forvum-mark-light.svg" alt="Forvum" width="140" />
  </picture>
</p>

# Forvum

<p align="center">
  <a href="https://github.com/eldermoraes/forvum/actions/workflows/ci.yml?query=branch%3Amain"><img src="https://img.shields.io/github/actions/workflow/status/eldermoraes/forvum/ci.yml?branch=main&style=for-the-badge" alt="CI status"></a>
  <a href="https://github.com/eldermoraes/forvum/releases"><img src="https://img.shields.io/github/v/release/eldermoraes/forvum?style=for-the-badge" alt="GitHub release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=for-the-badge" alt="Apache 2.0 License"></a>
  <img src="https://img.shields.io/badge/Java-25-orange?style=for-the-badge" alt="Java 25">
  <img src="https://img.shields.io/badge/GraalVM-native-1abc9c?style=for-the-badge" alt="GraalVM native">
</p>

**Forvum** is a _local-first personal AI assistant on the JVM_ — one GraalVM native binary you run on
your own machine. No JVM to install, no Docker, no Node; it boots in under 200 ms and answers you on
the channels you already use.

Its guiding principle is **fixed code, configurable behavior**: new agents, sub-agents, skills,
identities, cron jobs, and MCP servers are just files under `~/.forvum/` — no recompile, hot-reloaded
while it runs. And every turn, tool call, fallback, and judgment is recorded in a local ledger you can
inspect; nothing dissolves into a black box.

**Supported channels:** TUI · Web · Telegram · Discord · Slack · Matrix · Signal · WhatsApp · Voice.

[Website](https://forvum.ai) · [Architecture](docs/ULTRAPLAN.md) · [Context Engineering](docs/CONTEXT-ENGINEERING.md) · [Roadmap](docs/ULTRAPLAN.md#7-phased-roadmap) · [Deploy](docs/DEPLOY.md) · [Contributing](CONTRIBUTING.md) · [Releases](https://github.com/eldermoraes/forvum/releases)

> **Forvum is designed around the principles in [Context Engineering for Multi-LLM Low-Latency Agents](docs/CONTEXT-ENGINEERING.md) — see [how Forvum maps to them](docs/CONTEXT-ENGINEERING-MAPPING.md).** The full architectural vision lives in [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md).

## Highlights

- **Single native binary** — one GraalVM-compiled executable (~130 MB), [&lt;200 ms cold start](docs/ULTRAPLAN.md), no JVM/Docker/Node on the end-user machine.
- **Local-first & private** — runs entirely on your machine; conversations, memory, and metrics live in an embedded SQLite ledger under `~/.forvum/`.
- **Multi-channel** — talk to your assistant from a [terminal REPL](forvum-channel-tui), a [browser](forvum-channel-web), [Telegram](forvum-channel-telegram), [Discord](forvum-channel-discord), [Slack](forvum-channel-slack), [Matrix](forvum-channel-matrix), [Signal](forvum-channel-signal), [WhatsApp](forvum-channel-whatsapp), or [voice](forvum-channel-voice).
- **Provider fleet with fallback** — [Ollama](forvum-provider-ollama) (local, zero-config default), [Anthropic](forvum-provider-anthropic), [OpenAI](forvum-provider-openai), [Google](forvum-provider-google), and [GitHub Copilot](forvum-provider-copilot); per-agent fallback chains keep a turn alive when a provider fails.
- **Fixed code, configurable behavior** — agents, sub-agents, skills, identities, crons, roles, and MCP servers are JSON/Markdown files under `~/.forvum/`, hot-reloaded by a `WatchService` with no restart.
- **Tools, gated** — filesystem, [web fetch](forvum-tools-web), [shell](forvum-tools-shell), a [headless browser](forvum-tools-browser), a [code sandbox](forvum-tools-sandbox), and an [MCP bridge](forvum-tools-mcp-bridge) — each behind permission scopes, role-based access, and an interactive approval gate for sensitive calls.
- **Sub-agent orchestration** — a [LangGraph4j](docs/ULTRAPLAN.md) supervisor graph spawns isolated specialist workers on virtual threads and merges their results.
- **Semantic memory** — pluggable retrieval with a [Qdrant](forvum-provider-memory-qdrant) backend and a built-in local vector search (`forvum memory search`).
- **Observable by design** — every turn, tool call, and fallback is written to the ledger; an optional CAPR dashboard and OpenTelemetry spans are one config flag away.

## Install

The fastest path is the one-command installer. It downloads the single-binary GraalVM native build
(~130 MB — one executable, no JVM, no Docker, no Node) for your platform from the latest
[GitHub Release](https://github.com/eldermoraes/forvum/releases), verifies its `sha256`, and installs
it to `$HOME/.local/bin`:

```bash
curl -fsSL https://raw.githubusercontent.com/eldermoraes/forvum/main/install.sh | sh
```

The installer resolves the release tag from the GitHub API — the latest stable release (currently
[`v0.5.0`](https://github.com/eldermoraes/forvum/releases/tag/v0.5.0)), falling back to the newest
pre-release if no stable exists. Supported platforms: **linux-x64** and **macos-arm64**. Override the
target directory or pin an exact version with `FORVUM_INSTALL_DIR` / `FORVUM_VERSION`:

```bash
curl -fsSL https://raw.githubusercontent.com/eldermoraes/forvum/main/install.sh \
  | FORVUM_INSTALL_DIR=/usr/local/bin FORVUM_VERSION=v0.5.0 sh
```

<details>
<summary><strong>Manual download</strong></summary>

Pick the newest release on the [releases page](https://github.com/eldermoraes/forvum/releases) and
download the `forvum-<platform>` asset plus its `.sha256`, verify it, then make it executable (using a
tag as an example):

```bash
VER=v0.5.0                                     # the release tag to install
# linux-x64 (use forvum-macos-arm64 on Apple silicon)
curl -fsSLO "https://github.com/eldermoraes/forvum/releases/download/$VER/forvum-linux-x64"
curl -fsSLO "https://github.com/eldermoraes/forvum/releases/download/$VER/forvum-linux-x64.sha256"
shasum -a 256 -c forvum-linux-x64.sha256       # or: sha256sum -c
chmod +x forvum-linux-x64 && sudo mv forvum-linux-x64 /usr/local/bin/forvum
```

</details>

For a real conversation you need a model provider — the zero-config default is a local
[Ollama](https://ollama.com). Install it from [ollama.com/download](https://ollama.com/download), then
start it and pull the default model:

```bash
ollama serve &            # local model server, no API key
ollama pull qwen3:1.7b    # the model agent `main` is pinned to by default
```

## Quick start (TL;DR)

```bash
forvum init                     # scaffold ~/.forvum (owner-only: 0700 dirs / 0600 files)
forvum doctor                   # validate ~/.forvum config (exits non-zero on problems)
forvum                          # interactive session: banner + `forvum> ` prompt; /exit or Ctrl+D quits
forvum ask "who are you?"       # one non-interactive turn (stdout is just the reply)
forvum --help                   # also --version
```

The first turn can take several seconds while Ollama loads the model; the reply prints when complete.
If a turn fails with `Is the model provider running?`, start Ollama (`ollama serve`) and pull the model
named in the error.

![FORVUM CLI session — boot banner, agent ready prompt, and a two-turn interaction](docs/images/cli-demo.png)

## Security defaults

Forvum can connect to real messaging surfaces, so treat inbound messages as **untrusted input**. The
defaults are conservative — run `forvum doctor` to validate your config and surface malformed files,
unknown providers, and pending device scope upgrades.

- **Channel allowlists** — every server channel (Telegram, Discord, Signal, …) takes an
  `allowedUserIds`; an empty list on a public bot allows *anyone*, so always set it.
- **Device pairing & role-based scopes** — unknown devices are paired explicitly
  (`forvum pair approve …`); an identity's role maps to a set of permission scopes, and a tool outside
  an agent's belt is refused and audited.
- **Approval gate** — tools marked `userConfirmRequired` (e.g. `shell.exec`) block the turn for an
  interactive yes/no, and are denied by default in non-interactive / cron contexts.
- **Output guard** — a secret-redaction filter masks API keys and tokens on the way out of a turn
  (on by default).
- **The Web channel has no built-in authentication** — bind it to localhost
  (`QUARKUS_HTTP_HOST=127.0.0.1`) and front it with an authenticating reverse proxy (TLS) for public
  access; never expose `0.0.0.0:8080` directly.

## Channels

Each channel is enabled by dropping one JSON file under `~/.forvum/channels/`. Dropping any **server
channel** file flips the binary from one-shot command mode into a long-lived server.

- **TUI** — the built-in interactive terminal REPL; `forvum init` scaffolds an enabled `channels/tui.json`, so `forvum` launches it with no hand-written config.
- **Telegram** — recommended for phone access: outbound long-polling, so **no inbound port, no TLS, no
  reverse proxy**. Create a bot with [@BotFather](https://t.me/BotFather), then write
  `channels/telegram.json`:

  ```json
  { "botToken": "123456:ABC-your-bot-token", "allowedUserIds": [111111111] }
  ```

- **Web** — a browser chat UI over WebSocket. Drop an empty `channels/web.json` (`{}`) and Forvum
  serves HTTP on port 8080. **No built-in auth** — see [Security defaults](#security-defaults).
- **Discord / Slack / WhatsApp** — enable with a `channels/<id>.json` carrying the bot credentials and
  an `allowedUserIds` allowlist.
- **Matrix** — set `homeserver`, `accessToken`, and `userId` (the bot's own id, e.g.
  `@bot:example.org`) in `channels/matrix.json`. **Unencrypted rooms only** — end-to-end encryption is
  not yet supported ([#125](https://github.com/eldermoraes/forvum/issues/125)); the bot stays silent in
  encrypted rooms.
- **Signal** — connects to an operator-run [signal-cli](https://github.com/AsamK/signal-cli) HTTP
  daemon (Forvum does not spawn, install, or manage it). Start the daemon, then point
  `channels/signal.json` at it:

  ```json
  { "baseUrl": "http://localhost:8088", "account": "+15550001111", "allowedUserIds": ["+15557772222"] }
  ```

  (point `baseUrl` at whatever port your signal-cli daemon listens on — not 8080 if the Web channel is
  also enabled on the same host.) Direct text messages only (groups, edits, and receipts are ignored); the bot never replies to its
  own account.
- **Voice** — a file-drop transport: drop an audio clip in the channel's inbox and Forvum transcribes
  it (whisper.cpp), runs the turn, and writes a spoken reply (Piper) to the outbox. Point
  `channels/voice.json` at your operator-installed whisper/piper binaries.

## Configuration

Everything lives under `~/.forvum/` (override with the `FORVUM_HOME` env var). `forvum init` scaffolds
a working `agents/main.json` + system-prompt `agents/main.md`; the only required agent field is the
model:

```json
// ~/.forvum/agents/main.json
{ "primaryModel": "ollama:qwen3:1.7b" }
```

Cloud providers (Anthropic, OpenAI, Google) need an API key. The quickest path is
`forvum provider add <provider>` (e.g. `forvum provider add anthropic`): it prompts for the key (no
echo), stores it owner-only (`0600`) under `~/.forvum/state/credentials/`, runs a smoke test, and
offers to make `<provider>:<model>` agent `main`'s default. You can also export
`QUARKUS_LANGCHAIN4J_<PROVIDER>_API_KEY` (which takes precedence) and point an agent at a model by
editing `agents/main.json`. GitHub Copilot uses a device-code login instead of an API key:
`forvum copilot login`, then point an agent at a `copilot:<model>` ModelRef.

Beyond agents, the same `~/.forvum/` tree holds `channels/`, `crons/`, `skills/`, `roles/`,
`devices/`, and `mcp-servers/` — all hot-reloaded with no restart. See
[docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §4 for the full layout.

## Run on a server (VPS)

To run Forvum 24/7 on a Linux VPS, install the binary (the one-liner above works on `linux-x64`),
enable a **server channel**, and run it under `systemd`. **Telegram** is the easiest for phone access —
it uses outbound long-polling, so there is no inbound port, no TLS, and no reverse proxy to manage. A
ready-to-use unit and a full walkthrough (dedicated user, persistence/backups, security checklist,
Docker Compose, reverse-proxy TLS) live in **[docs/DEPLOY.md](docs/DEPLOY.md)** and
[`deploy/systemd/forvum.service`](deploy/systemd/forvum.service):

```bash
# install system-wide, create a dedicated user + state dir, scaffold, then enable the service:
curl -fsSL https://raw.githubusercontent.com/eldermoraes/forvum/main/install.sh | FORVUM_INSTALL_DIR=/usr/local/bin sh
sudo useradd -r -s /usr/sbin/nologin -d /var/lib/forvum forvum
sudo install -d -o forvum -g forvum /var/lib/forvum
sudo -u forvum env FORVUM_HOME=/var/lib/forvum forvum init
# edit /var/lib/forvum/agents/main.json + add channels/telegram.json (see docs/DEPLOY.md), then:
sudo cp deploy/systemd/forvum.service /etc/systemd/system/
sudo systemctl enable --now forvum
journalctl -u forvum -f
```

A real conversation needs a model provider reachable from the VPS — either a cloud key
(`forvum provider add ...` or a `QUARKUS_LANGCHAIN4J_<PROVIDER>_API_KEY` env var) or an Ollama server
the box can reach. See [docs/DEPLOY.md](docs/DEPLOY.md) for both.

## Kubernetes (team-assistant mode)

A Helm chart under [`deploy/helm/forvum`](deploy/helm/forvum) deploys Forvum as a **per-namespace team
assistant**: each Helm release is one isolated instance with its own persistent SQLite state, so a
namespace gets a private assistant whose memory no other namespace can read (the isolation is
structural — Kubernetes namespacing plus a per-release `PersistentVolumeClaim`). The chart runs the
native single-binary container image (published to GHCR by the release pipeline) as a long-lived
Web-channel HTTP server.

```bash
# Give each team its own isolated assistant:
helm install forvum deploy/helm/forvum --namespace team-a --create-namespace
helm install forvum deploy/helm/forvum --namespace team-b --create-namespace
kubectl -n team-a port-forward svc/forvum 8080:8080
```

Configuration (`agents/`, `channels/`, …) comes from values and is projected into `$FORVUM_HOME` from a
ConfigMap; provider API keys are wired from Kubernetes Secrets. There is no Kubernetes operator (out of
scope for now). See [`deploy/helm/README.md`](deploy/helm/README.md) for the full isolation model and
configuration reference.

## Dev mode: the live config editor

While developing Forvum itself in dev mode (`./mvnw -f forvum-app quarkus:dev`), a browser-based live
editor for the `~/.forvum/` config is available at
[http://localhost:8080/q/dev-ui/config-editor](http://localhost:8080/q/dev-ui/config-editor) (alongside
the Quarkus Dev UI at `/q/dev/`). It lists the editable config files (agents, channels, crons, roles,
devices, MCP servers, skills, tools, and `config.json`), validates an edit through the same
loader / `forvum doctor` machinery the engine loads with — so a saved config is exactly one the engine
can load — and fires the hot-reload event so the running engine re-reads the edited agent/cron
**without a restart**. A malformed edit (or a model ref naming an uninstalled provider) is rejected
with inline findings and the file is left unchanged.

This editor is **dev-mode only** — an explicit, documented native carve-out. The Quarkus Dev UI is a
fast-jar dev feature and is not part of the GraalVM native binary; the editor route is build-time-gated
off in production, so it adds no native surface and no cold-start cost.

## Build from source

> Prerequisites: Java 25 (the bundled `./mvnw` provides Maven); for the native binary, GraalVM CE 25 /
> Mandrel 25.0.x-Final. Building from source is the path for platforms without a published binary, or
> for development.

**Native single binary** (the primary target — one executable, no JVM, &lt;200 ms cold start):

```bash
# -am pulls in the reactor deps — builds from a fresh clone; -DskipTests skips the
# development test suite (installing doesn't need it — contributors run ./mvnw verify)
./mvnw -Pnative -pl forvum-app -am package -DskipTests
BIN=$(ls forvum-app/target/forvum-app-*-runner)
"$BIN" init                     # scaffold ~/.forvum (owner-only: 0700 dirs / 0600 files)
"$BIN"                          # interactive session: banner + `forvum> ` prompt; /exit or Ctrl+D quits
"$BIN" ask "who are you?"       # one non-interactive turn (stdout is just the reply)
```

<details>
<summary><strong>JVM fast-jar</strong> (no GraalVM needed — development / drop-in plugins)</summary>

```bash
./mvnw package -pl forvum-app -am -DskipTests
JAR=forvum-app/target/quarkus-app/quarkus-run.jar
java -jar "$JAR" init           # first run only — without it there are no agents/channels yet
java -jar "$JAR"                # interactive session (the same `forvum> ` REPL as the native binary)
```

</details>

## Architecture

Forvum is organized as a four-layer Maven reactor (foundation → SDK → engine → first-party
channel/provider/tool extensions, assembled by `forvum-app`), structured so the core stays
extension-agnostic at the build level.

- **`forvum-core`** — pure-Java value contracts with no framework dependencies (agent IDs, model
  references, event types). [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §4.3.
- **`forvum-sdk`** — the public SPI for extension points (model providers, channels, tools, memory).
  [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §2.2.
- **`forvum-engine`** — the Quarkus engine, extension-agnostic. Orchestrates agent lifecycle, resolves
  specs, routes models, and emits events. [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §2.3.
- **`forvum-bom`** — centralized dependency version management (the single version bump point).
- **`forvum-app`** — the only runnable artifact. Wires every first-party channel/provider/tool, hosts
  the TUI, and boots Quarkus.

A brand-new *Java* plugin (channel/provider/native tool) requires repackaging `forvum-app` — the
deliberate trade-off for a reflection-free native binary. For the full architecture — decisions,
trade-offs, and deferred design — read [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md); see
[docs/ISSUES.md](docs/ISSUES.md) for the per-step roadmap.

## Roadmap

- **Phase 1 — MVP (v0.1) · complete** — the multi-module reactor, core contracts, plugin SDK, and
  config loader; SQLite/Flyway persistence, `@AgentScoped` isolation, `AgentRegistry`, and fallback
  chains; the provider fleet (Ollama, Anthropic, OpenAI, Google); the tool registry, permission scopes,
  and filesystem tools; the TUI, Web, and Telegram channels; the LangGraph4j supervisor graph;
  file-driven crons; and the GraalVM native single-binary with the &lt;200 ms cold-start gate.
- **Phase 2 — v0.5 (parity with OpenClaw) · shipped** — released as
  [`v0.5.0`](https://github.com/eldermoraes/forvum/releases/tag/v0.5.0): the Discord, Slack, Matrix,
  Signal, WhatsApp, and Voice channels; the GitHub Copilot provider and Qdrant semantic memory; the
  web-fetch, shell, headless-browser, code-sandbox, and MCP-bridge tools; role-based scopes, device
  pairing, an approval gate, and an output guard; and observability + a live config editor.
- **Phase 3 — v1.0+ (next)** — the differentiators on top of parity. Detailed scope lives in
  [docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) §7.3.

## The name

*Forvum* is a fusion of two Latin words.

**Forum** — the public space where Roman citizens gathered to deliberate, debate, and decide.

**Quorum** — the minimum number of voices required for a collective decision to stand.

The platform inherits both. Forvum is where agents convene, deliberate, and act — and where every
decision is shaped by structure rather than by an opaque single step. Each turn, each tool call, each
fallback, each judgment is observable in the ledger; nothing dissolves into a black box.

The result is not an orchestrator that commands silently from the center. It is an architecture where
coordination, evidence, and control are all first-class — and where the work of an agent system can
finally be reasoned about.

## Contributing

Contributions to design and code are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to
contribute — open an issue or discussion for architectural changes before a PR.
[docs/ULTRAPLAN.md](docs/ULTRAPLAN.md) is the architectural source of truth.

## License

Apache 2.0 — see [LICENSE](LICENSE).
