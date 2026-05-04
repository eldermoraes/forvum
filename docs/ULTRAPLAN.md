# Forvum — AI Personal Assistant Ultraplan

> **For agentic workers:** REQUIRED SUB-SKILL — when executing this plan, use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans`. Phase 1 milestones use checkbox (`- [ ]`) syntax. Implementation language is English throughout (code identifiers, comments, commit messages, docs, config keys). User-facing strings that are part of the app's localization surface may be localized, but source strings default to English.

**Goal:** Ship Forvum — a local-first, open-source personal AI assistant in Java 25 / Quarkus 3.31 / Langchain4j 1.12 / LangGraph4j — that first reaches, then surpasses, OpenClaw on features and engineering quality.

**Architecture:** Maven multi-module, CDI-first with a custom `@AgentScoped` context for in-process sub-agent isolation. The core stays extension-agnostic; every channel, provider, and tool is a module that implements a sealed-interface SDK contract. Hybrid persistence — human-editable markdown and JSON files under `~/.forvum/` for intent, embedded SQLite for operational state, memory, and metrics. Dual build targets from day one: a JVM fast-jar and a GraalVM native single-binary, both gated in CI.

**Tech Stack:** Java 25 · Maven · Quarkus 3.31.x · Quarkiverse `quarkus-langchain4j-*` · Langchain4j 1.12 · LangGraph4j · Xerial SQLite JDBC · Hibernate ORM + Panache + Flyway · JLine 3 · Quarkus WebSockets Next · Quarkus Scheduler · OpenTelemetry · GraalVM CE 24+ · JaCoCo · GitHub Actions.

**Implementation-language policy:** Forvum is open-source, so every artifact inside the repository (code, identifiers, JavaDoc, comments, commit messages, PR descriptions, documentation, config file keys, log messages, error strings, file and directory names) is in English. That is a non-negotiable collaboration requirement, not a stylistic preference.

---

## 1. Overview

Forvum is a local-first personal AI assistant built from the ground up in Java 25 on top of Quarkus, Langchain4j, and LangGraph4j. It inherits the Context Engineering paradigm from the workdir's `CONTEXT-ENGINEERING.md`: context is the differentiator, the context window is a scarce RAM-like resource, and any multi-LLM architecture must treat Write, Select, Compress, and Isolate as non-negotiable pillars.

The central architectural principle is **fixed code, configurable behavior**. New agents, sub-agents, skills, identities, cron jobs, MCP servers, and channel/provider enablement require only editing files under `~/.forvum/` — no recompilation, no restart in dev mode, and a WatchService-driven hot reload in production. Adding a brand-new Java plugin (a new channel, provider, or native tool implementation in Java) does require repackaging `forvum-app`, which is the intended trade-off for a GraalVM native binary with zero runtime reflection outside framework-managed proxies.

### 1.1 What we replicate from OpenClaw

OpenClaw (at `../openclaw/`) is the most capable personal AI assistant in the current ecosystem and our functional benchmark. From it we replicate:

- **Manifest-first plugin discovery.** Plugins declare their identity, capabilities, and activation conditions in a static manifest (`openclaw.plugin.json` in their world; `META-INF/forvum/plugin.json` in ours). Discovery, validation, and setup happen from metadata before any plugin runtime executes.
- **A strict public SDK boundary.** Extensions cross into core only through a versioned SDK. Core never hardcodes extension IDs. Plugins never import core internals.
- **Request-scoped identity and context.** The triple (agent, user, channel-account) is resolved per inbound request, not cached globally. This keeps multi-channel, multi-agent behavior clean.
- **Deterministic prompt assembly.** Every `Set` and `Map` that feeds prompt composition is sorted by a stable key at the assembly boundary, so prompt-cache prefixes stay byte-identical turn over turn — a correctness requirement, not an optimization.
- **Lightweight adapter surfaces.** Channels and providers expose small capability-typed interfaces rather than fat base classes. OpenClaw achieves this with TypeScript discriminated unions; we achieve it with sealed Java interfaces and capability records.

### 1.2 What we improve

OpenClaw's design has friction points rooted in its TypeScript/Node origins. A Java 25 / Quarkus foundation lets us address them directly.

- **Queryable operational state.** OpenClaw stores sessions as raw JSONL. We use SQLite, so `SELECT * FROM semantic_memory WHERE agent_id = ?` is a first-class debug tool and CAPR / token / latency metrics are straightforward aggregate queries.
- **Consolidated SDK surface.** OpenClaw exports 100+ subpaths from its plugin SDK — discoverable only through docs. Our sealed interfaces are fully enumerable by an IDE: `permits ChannelProvider, ModelProvider, ToolProvider, MemoryProvider` tells a new contributor everything they can implement.
- **Build-time DI instead of hand-rolled lazy loading.** OpenClaw's `*.runtime.ts` split exists to keep cold paths light. Quarkus' build-time CDI gives this for free — each channel, provider, and tool is a build-step-discovered bean with framework-emitted reflection hints, fully native-compatible.
- **Immutable request-scoped handles.** OpenClaw's plugin registry is global-mutable and documented as a "transitional compatibility surface." Forvum uses immutable handles derived per request from the `AgentRegistry`, eliminating an entire class of cross-request pollution bugs.
- **First-class per-agent and per-cron LLM selection with fallback.** OpenClaw can swap providers but fallback chains are ad hoc. Forvum makes `FallbackChain(primary, fallbacks, budget)` a core type and `FallbackChatModel` a library-wide decorator; every agent and every cron job carries its own chain, and fallback events land in `provider_calls` with `is_fallback = 1` for later analysis.

### 1.3 Phased objectives

- **MVP (v0.1).** Three channels (TUI, Web UI, Telegram). A main agent plus spawnable sub-agents and independent agents. Per-agent and per-cron LLM selection with fallback. Runtime-configurable via `.md` / `.json` without recompiling. User recognition across channels. Agent identity and persona definition. Native image produced in CI from day one.
- **v0.5 (parity).** Feature parity with OpenClaw: browser tool, code-execution sandbox, voice channel, device pairing, memory-host SDK, Maven-coordinate plugin marketplace, skill marketplace, session replay, config doctor, provider onboarding wizard, RBAC on tools.
- **v1.0+ (leap).** Differentiators unlocked by the clean Java foundation: single-binary native install, queryable semantic memory, LangGraph4j cyclic agents as a first-class primitive, CAPR-driven adaptive model routing, multi-user by toggle, Dev UI live-edit of configs, Kubernetes-native team-assistant mode, proxy-model compression middleware.

### 1.4 Guiding principles (from `CONTEXT-ENGINEERING.md`)

- **Orchestrator-Workers hub-and-spoke** is the default multi-agent topology.
- **Small-and-fast models** (for example a local Ollama `qwen3:1.7b`) handle routing, intent classification, and metadata-extraction sub-steps to minimize end-to-end latency.
- **Strict per-agent state isolation** prevents context clash — every agent runs inside its own `@AgentScoped` context with its own memory, tool subset, and system prompt.
- **Observability with CAPR** (Cost-Aware Pass Rate) sits alongside token counts and latency as a first-class metric from the MVP onward.
- **Governance from day one.** Every tool carries a `PermissionScope`, user-approval hooks gate destructive actions, and outbound outputs can be filtered for sensitive data.

---

## 2. Modules & Bounded Contexts

The project is a Maven multi-module reactor under `groupId = ai.forvum`, organized in four layers that also map to bounded contexts. The layering enforces the "core stays extension-agnostic" rule at the build level: `forvum-engine` has zero compile dependencies on any concrete channel or provider module, so accidentally hardcoding a bundled extension ID is not possible.

### 2.1 Layer 0 — Foundation (no Quarkus)

- **`forvum-parent`** — the root reactor `pom`. Declares `<packaging>pom</packaging>`, the Java 25 compiler arguments, the binding of `quarkus-maven-plugin` and `jacoco-maven-plugin`, and imports `forvum-bom`.
- **`forvum-bom`** — a `<dependencyManagement>`-only module that locks the versions of Quarkus, Quarkiverse `langchain4j-*` extensions, Langchain4j core, LangGraph4j, Xerial SQLite JDBC, JLine, Flyway, OpenTelemetry, and test libraries. Every downstream module imports this BOM, so there is exactly one place to bump a version.
- **`forvum-core`** — pure Java domain. Records and sealed interfaces for `AgentId`, `Identity`, `Persona`, `ChannelMessage`, `ToolSpec`, `ModelRef`, `AgentEvent`, `FallbackChain`, `CostBudget`, and `MemoryPolicy`. No Quarkus dependency at all, so tests and prototypes outside the container can depend on these types directly.

### 2.2 Layer 1 — Public SDK (the only extension contract)

- **`forvum-sdk`** — sealed interfaces that plugins implement: `ChannelProvider`, `ModelProvider`, `ToolProvider`, `MemoryProvider`. Each one permits a `non-sealed abstract` base (`AbstractChannelProvider`, and so on) that third parties extend, which is how we reconcile sealed hierarchies with open extension. Also houses the `@ForvumExtension` plugin marker annotation and a re-export of `@RegisterForReflection` so plugin authors do not need to pull in `quarkus-core` directly. This module is the one and only artifact a third-party plugin compiles against.

### 2.3 Layer 2 — Engine (Quarkus application code, extension-agnostic)

- **`forvum-engine`** — the heart of the application. Contains the `AgentRegistry`, the custom `@AgentScoped` CDI context (implemented via Quarkus ArC's `InjectableContext` SPI so it works in native mode), the LangGraph4j orchestrator, the `ConfigLoader` with `WatchService`-backed hot reload, the SQLite persistence layer (Hibernate ORM + Panache + Flyway), the `LlmSelector` and `FallbackChatModel` decorator, the MCP-client bridge, OpenTelemetry wiring, and the Dev UI cards. Its compile dependencies are limited to `forvum-core` and `forvum-sdk`; never any concrete channel, provider, or tool.

### 2.4 Layer 3 — First-party extensions

All first-party extensions depend only on `forvum-sdk`. They are separate Maven modules so an end-user assembling a slimmer build can drop any of them by editing `forvum-app/pom.xml`.

**Channels**

- **`forvum-channel-tui`** — JLine 3 interactive REPL; streams tokens to stdout; fallback `--no-ansi` plain-stdin mode; GraalVM reflection hints bundled.
- **`forvum-channel-web`** — Quarkus WebSockets Next server; a minimal static HTML/JS bundle served from classpath resources; streaming via server-pushed WS frames.
- **`forvum-channel-telegram`** — long-poll bot (webhook available as an opt-in alternative); uses `quarkus-rest-client-reactive`.

**Providers**

- **`forvum-provider-anthropic`** — wraps `quarkus-langchain4j-anthropic` and exposes a `ModelProvider` SPI bean.
- **`forvum-provider-openai`** — wraps `quarkus-langchain4j-openai`.
- **`forvum-provider-ollama`** — wraps `quarkus-langchain4j-ollama`.
- **`forvum-provider-google`** — wraps `quarkus-langchain4j-vertex-ai-gemini`.

**Tools**

- **`forvum-tools-filesystem`** — `fs.read`, `fs.write`, `fs.list`, guarded by `PermissionScope.FS_READ` / `FS_WRITE`.
- **`forvum-tools-web`** — `web.fetch`, `web.search`, with a pluggable search backend.
- **`forvum-tools-shell`** — `shell.exec` behind an allow-list plus a `USER_CONFIRM_REQUIRED` approval hook.
- **`forvum-tools-mcp-bridge`** — dynamic MCP client; reads `~/.forvum/mcp-servers/*.json` and surfaces remote MCP tools as native `ToolSpec` instances that any agent's `allowedTools` list can reference.

### 2.5 Layer 4 — Assembly

- **`forvum-app`** — the only module that produces runnable artifacts. Depends on `forvum-engine` plus every first-party channel, provider, and tool module. The `quarkus-maven-plugin` binding here produces both the JVM fast-jar (`mvn package`) and, under the `-Pnative` profile, the GraalVM native binary. A single `main()`.

### 2.6 Bounded contexts

The Maven split also delimits bounded contexts for contribution purposes: **Config Management**, **Identity & Persona**, **Agent Runtime**, **Conversation & Memory**, **Tool Execution**, **Model Routing**, **Channel I/O**, and **Observability**. Each maps to either a module or a cohesive sub-package within `forvum-engine` (for example `forvum-engine/src/main/java/ai/forvum/engine/routing/` for Model Routing), so an external contributor can focus on one bounded context without touching anything else.

---

## 3. Stack by Layer

### 3.1 Language runtime

Java 25 LTS. We use its modern surface deliberately:

- **Scoped Values** carry the current `AgentId` across virtual-thread continuations without the leakage issues of `ThreadLocal`. The custom `@AgentScoped` CDI context is backed by a `ScopedValue<AgentId>` set at request entry through `ScopedValue.callWhere(CURRENT_AGENT, id, body)`.
- **Sealed interfaces** on every extension point (`ChannelProvider`, `ModelProvider`, `ToolProvider`, `MemoryProvider`) and on the event hierarchy (`AgentEvent permits TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent`). This gives exhaustive pattern matching in channels and compiler-enforced completeness in event handlers.
- **Records** for every DTO and value object. Records also minimize the GraalVM reflection surface, since each record has a documented canonical constructor.
- **Pattern matching for switch** over sealed types in channel, router, and event-handling code paths, replacing visitor boilerplate.
- **Virtual threads** as the default carrier for per-request work, so fanning out to sub-agents and tool calls is cheap and debuggable.

### 3.2 Application framework

Quarkus 3.31.x. Provides build-time CDI (Arc), native OpenTelemetry integration, WebSockets Next, `quarkus-scheduler` for cron-expression jobs, `quarkus-rest-client-reactive` for Telegram, and the Dev UI as an admin and debugging surface during development. We deliberately avoid Mutiny / reactive streams in engine-level code — virtual threads plus `Flow.Publisher` cover our concurrency needs with simpler stack traces and easier debugging.

### 3.3 AI layer

- **Quarkiverse `quarkus-langchain4j-*` extensions**, one per provider (`anthropic`, `openai`, `ollama`, `vertex-ai-gemini`). They expose `ChatModel` and `StreamingChatModel` as CDI beans, provide declarative `@RegisterAiService` tool-calling when we want it, and ship an MCP client that is already build-time wired and native-compatible.
- **Langchain4j 1.12 core.** Supplies the `ChatMemory` contract (we implement a SQLite-backed variant over the `messages` table), the `ToolSpecification` / `ToolExecutor` surface, structured-output decoding via `@Description` and `@StructuredPrompt`, and the generic `ChatModel` / `StreamingChatModel` interfaces that our `FallbackChatModel` decorates.
- **LangGraph4j.** Supplies the `StateGraph` abstraction — cyclic, state-oriented graphs of `BiFunction<State, RunContext, NodeResult>` nodes with conditional edges. It integrates with Langchain4j via `LC4jToolService` and `MessagesState`. This is the primitive that materializes the hub-and-spoke Orchestrator-Workers pattern from `CONTEXT-ENGINEERING.md`.

### 3.4 Persistence

- **Xerial SQLite JDBC driver + Hibernate ORM + Panache + Flyway.** WAL mode is enabled explicitly in the JDBC URL for acceptable single-writer concurrency in personal-use scenarios. The schema is managed through forward-only Flyway migrations in `forvum-engine/src/main/resources/db/migration/`. Panache keeps the repository code concise without sacrificing type safety.
- **`java.nio.file.WatchService`** inside a `ConfigWatcher` bean fires a CDI `ConfigurationChangedEvent` whenever files under `~/.forvum/` change, enabling hot reload of agent specs, personas, skills, crons, and MCP-server definitions in both dev mode and production fast-jar.

### 3.5 Channels

- **TUI.** JLine 3 with its bundled GraalVM reflection hints. A `--no-ansi` degraded mode ships from the MVP to cover environments where terminal-capability detection fails on first boot (a known JLine quirk).
- **Web.** Quarkus WebSockets Next for bidirectional streaming. The initial UI is a minimal hand-written HTML/JS page shipped as classpath static resources. A natural evolution path is Qute templates plus HTMX fragments for richer server-driven UI without adopting a full SPA toolchain.
- **Telegram.** Long-poll mode via `quarkus-rest-client-reactive` calling the Telegram Bot API. Webhook mode is an opt-in variant for deployments behind a public URL.

### 3.6 Observability

- **OpenTelemetry** is on by default via `quarkus-opentelemetry`. The engine defines four span kinds:
  - `forvum.agent.turn` — one per inbound user message; attributes include `agent.id`, `identity.id`, `channel.id`, `session.id`.
  - `forvum.llm.call` — one per row written to `provider_calls`; attributes include `model`, `tokens_in`, `tokens_out`, `cost_usd`, `latency_ms`, `fallback`.
  - `forvum.tool.call` — one per `tool_invocations` row.
  - `forvum.graph.node` — one per LangGraph4j node execution.
- **CAPR** (Cost-Aware Pass Rate) is computed from a Panache aggregate over `provider_calls` joined with `capr_events`. It is exposed as a JSON endpoint at `/q/dashboard/capr` and rendered as a Dev UI card in development mode. The per-turn pass/fail verdict is produced by a cheap "judge" model (by default a local Ollama `qwen3:1.7b`), off by default in production and enabled selectively for evaluation runs.

### 3.7 Build and distribution

- **Maven 3.9+** as the build tool; the `quarkus-maven-plugin` produces the `quarkus-app/` fast-jar and, under `-Pnative`, a single native executable.
- **GraalVM CE 24+** is required for the native profile. Quarkus' `container-build=true` lets CI cross-compile native images via a builder container when local GraalVM is unavailable.
- **JaCoCo** enforces an 80% line-coverage threshold on `mvn verify` at the parent level.
- **GitHub Actions CI** runs a matrix of `linux-amd64` and `macos-arm64`, building both JVM and native targets on every pull request. A `@QuarkusIntegrationTest` smoke-runs the native binary and fails the build if cold-start exceeds 200 ms.
- **Release channels.** The JVM jar and an OCI container image ship to GitHub Releases and Docker Hub. Platform-specific native binaries ship to GitHub Releases. A Homebrew tap and a Scoop bucket follow from v0.5 onward.

### 3.8 Concurrency Discipline

Forvum runs every per-request workflow on virtual threads. The choice is not cosmetic — `@AgentScoped` (§5.1), `ScopedValue<AgentId>` propagation, and the orchestrator-workers spawn pattern (§5.5) all assume virtual-thread semantics. The rules below codify the implementation discipline so the assumption holds in practice.

- **Virtual-thread placement by layer.** Channel inbound REST and WebSocket handlers (`forvum-channel-*`), the engine turn orchestrator (`forvum-engine`), and `quarkus-scheduler`-fired cron entries (M19) all run on virtual threads. Inbound REST/WebSocket handlers and `@Scheduled` methods (the M19 cron entries) carry `@RunOnVirtualThread`; engine-internal fan-out injects or constructs `Executors.newVirtualThreadPerTaskExecutor()` explicitly. The default Quarkus worker pool is the *exception*; any use is commented at the call site with a rationale.
- **Spawn fan-out (StructuredTaskScope, preview caveat).** §5.5 `spawn_worker → worker_run` is the natural fit for Java 25's `StructuredTaskScope.ShutdownOnFailure` / `ShutdownOnSuccess`, which gives structured cancellation and exception propagation across sub-agent boundaries. `StructuredTaskScope` is JEP 505 — *fifth preview* in Java 25 LTS — so it requires `--enable-preview` at compile and run time, with attendant restrictions on GraalVM native-image. The binding commitment is gated on a spike during M18 — where the supervisor graph's `spawn_worker → worker_run` actually exercises fan-out — that confirms StructuredTaskScope produces a working native image. On spike failure, Forvum ships v0.1 with a manual `Thread.ofVirtual().start(...)` fan-out plus explicit `CompletionStage` join, and revisits when the JEP exits preview.
- **Pinning detection.** Dev and test profiles enable `-Djdk.tracePinnedThreads=full`. A CI step greps test output for `Thread pinned` and fails the build on any new occurrence. `forvum-engine/src/test/resources/pinning-allowlist.txt` enumerates documented carve-outs (each entry cites the upstream issue or PR); the CI step suppresses only matches whose stack-trace fingerprint is on that allowlist.
- **`synchronized` forbidden in hot paths.** A CI grep over `forvum-engine/src/main/java` and (when they exist) `forvum-channel-*/src/main/java` fails the build on any `synchronized` keyword. Use `java.util.concurrent.locks.ReentrantLock`, `java.util.concurrent` collections, or `java.util.concurrent.atomic` primitives. Modules not yet created are exempt until they appear; the rule applies forward, not retroactively.
- **JDBC and virtual threads — posture finalized at M5.** Xerial SQLite JDBC uses `synchronized` JNI native methods and will pin virtual threads. Rather than pre-commit a mitigation against an unfinalized connection-pool choice, this section records the *symptom* and defers the resolution to M5 (see Risk #11). M5 picks among (a) a managed platform-thread executor for transactions, (b) explicit `@Blocking` on Hibernate-bound code paths, or (c) a loom-friendly driver if one becomes available; the chosen pattern is back-filled here.
- **Observability marker.** Every `forvum.*` OTel span carries a `thread.is_virtual` boolean attribute. Dev UI exposes a Concurrency card showing VT-vs-PT carrier counts and pin-detection events; it lands as part of the `forvum-engine` Dev UI surface (§3.2) without a dedicated milestone. The same data exports via OTLP in Phase 2 (§7.2 item 15).

---

## 4. Storage (Files + SQLite Schema)

Forvum uses a hybrid persistence model: human-editable configuration and intent live as Markdown and JSON files under `~/.forvum/`, while operational and append-only state (sessions, messages, memory, tool invocations, provider calls, CAPR events) lives in a single embedded SQLite database. This split keeps user-facing artifacts diffable and friendly to version control, while giving the engine a queryable, transactional store for runtime data. It also maps cleanly onto the Context Engineering Write/Select/Compress/Isolate pillars: files carry the Write surface users edit directly; SQLite carries the Select surface the engine scans, filters, and aggregates.

### 4.1 On-disk layout under `~/.forvum/`

The entire user-editable surface sits under `$FORVUM_HOME`, which defaults to `~/.forvum/` and can be overridden for multi-user or ephemeral deployments via the `FORVUM_HOME` environment variable. Every file below is scanned at startup and watched via `java.nio.file.WatchService` for hot reload; changes fire a CDI `ConfigurationChangedEvent` that the affected subsystem handles without a restart.

```
~/.forvum/
├── config.json                          # Global runtime config — channels on, default LLM chain, log level
├── identities/
│   └── default.json                     # Identity records — linked channel accounts, display name
├── agents/
│   ├── main.md                          # Main agent persona (free-form markdown, used as system prompt)
│   ├── main.json                        # Main agent spec — allowedTools, LLM chain, memory policy
│   ├── researcher.md                    # Sub-agent persona
│   └── researcher.json                  # Sub-agent spec
├── skills/
│   ├── summarize.md                     # Skill definition — a named, reusable prompt template
│   └── translate.md
├── crons/
│   └── daily-brief.json                 # Cron job — schedule, agent id, LLM chain, input template
├── channels/
│   ├── tui.json                         # TUI channel config — prompt style, no-ansi fallback
│   ├── web.json                         # Web channel — port, bind address, auth
│   └── telegram.json                    # Telegram channel — bot token, allowed user ids
├── mcp-servers/
│   └── github.json                      # MCP server definition — transport, command or URL, env
├── plugins/
│   └── README.md                        # Drop-in jars — fast-jar mode only, see §6.3
└── state/
    └── forvum.sqlite                    # SQLite database — operational state, see §4.2
```

- **`config.json`** holds cross-cutting settings: the default fallback chain for agents that do not declare their own, the log level, the enabled-channel set, and the embedding model used for semantic memory. It never holds secrets — API keys live in platform keychains (macOS Keychain, Secret Service on Linux, Windows Credential Manager) referenced by key id.
- **`identities/<id>.json`** links external accounts (Telegram user id, email, OS username) to a single Forvum identity. The engine resolves the identity at channel-message entry and carries it through the request via a `ScopedValue<Identity>` bound alongside `CURRENT_AGENT`.
- **`agents/<agentId>.md`** is the agent's persona and system prompt, written in free-form Markdown. **`agents/<agentId>.json`** is the structural spec: allowed tools (by glob), LLM fallback chain, memory policy, optional parent pointer for sub-agents, and optional `costBudget` and `toolBudget` caps. This split — prose as `.md`, structure as `.json` — mirrors OpenClaw's convention so migration from an OpenClaw-style setup is largely a `cp`.
- **`skills/<skill>.md`** is a named prompt template with front-matter declaring its input schema. Agents invoke skills by name through the `SkillInvokerTool`. Skills are globally visible to any agent allowed to call the skill tool — they are not per-agent.
- **`crons/<cronId>.json`** declares a scheduled job: cron expression, target agent id, LLM fallback chain distinct from the agent's default, and an input template that renders to the initial user-message content at fire time. The scheduler (`quarkus-scheduler`) picks them up at startup and on hot reload; concurrent overlaps of the same cron id are suppressed.
- **`channels/<channelId>.json`** is per-channel configuration. Channels that need secrets (Telegram bot token) reference a key id resolved through the platform keychain at channel start.
- **`mcp-servers/<name>.json`** declares an MCP server — transport, command or URL, environment variables. The `McpBridge` starts each configured server lazily on first tool call and keeps it alive until process shutdown.
- **`plugins/`** is the runtime drop-in directory for third-party jars. It only works in the JVM fast-jar mode; the native binary cannot load new jars at runtime and instead requires a rebuild that depends on the plugin. This trade-off is documented in §6.3 and made explicit in user documentation.
- **`state/forvum.sqlite`** is the single SQLite file. The engine opens it in WAL mode (`PRAGMA journal_mode=WAL`) for acceptable single-writer concurrency and enables `PRAGMA foreign_keys=ON` explicitly on every connection.

### 4.2 SQLite schema (Flyway V1 + V2)

The operational schema is managed as forward-only Flyway migrations under `forvum-engine/src/main/resources/db/migration/`. The V1 baseline defines seven tables that capture every piece of runtime state the agent needs to reason across turns and that operators need to audit or query for CAPR. Every table uses a generated primary key where appropriate and stores timestamps as milliseconds since epoch for simpler aggregation.

```sql
-- V1__baseline.sql

-- Sessions: one per channel conversation (tui per-invocation, web per-socket, telegram per-chat)
CREATE TABLE sessions (
  id            TEXT PRIMARY KEY,
  identity_id   TEXT NOT NULL,
  channel_id    TEXT NOT NULL,
  agent_id      TEXT NOT NULL,
  started_at    INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  metadata_json TEXT
);
CREATE INDEX idx_sessions_identity ON sessions(identity_id);
CREATE INDEX idx_sessions_lastseen ON sessions(last_seen_at);

-- Messages: append-only chat history, one row per turn-level message
CREATE TABLE messages (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  agent_id    TEXT NOT NULL,
  role        TEXT NOT NULL,     -- user | assistant | system | tool
  content     TEXT NOT NULL,
  tokens      INTEGER,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_messages_session ON messages(session_id, created_at);
CREATE INDEX idx_messages_agent   ON messages(agent_id, created_at);

-- Episodic memory: per-agent, per-session event log for the agent's own recall
CREATE TABLE episodic_memory (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id    TEXT NOT NULL,
  session_id  TEXT NOT NULL,
  event_type  TEXT NOT NULL,     -- observation | decision | reflection
  content     TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_episodic_agent_session ON episodic_memory(agent_id, session_id, created_at);

-- Semantic memory: embedded long-term facts the agent has chosen to keep
CREATE TABLE semantic_memory (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id    TEXT NOT NULL,
  key         TEXT NOT NULL,
  value       TEXT NOT NULL,
  embedding   BLOB,              -- float32 vector, length defined by embedding model
  source      TEXT,              -- free-form provenance
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  UNIQUE(agent_id, key)
);
CREATE INDEX idx_semantic_agent ON semantic_memory(agent_id, updated_at);

-- Tool invocations: every tool call, inputs, outputs, outcome
CREATE TABLE tool_invocations (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL,
  agent_id    TEXT NOT NULL,
  tool_name   TEXT NOT NULL,
  arguments   TEXT NOT NULL,     -- JSON
  result      TEXT,              -- JSON or truncated text
  status      TEXT NOT NULL,     -- ok | error | denied
  latency_ms  INTEGER,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_tool_session ON tool_invocations(session_id, created_at);
CREATE INDEX idx_tool_agent   ON tool_invocations(agent_id, created_at);

-- Provider calls: every LLM call, model, tokens, cost, fallback flag
CREATE TABLE provider_calls (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id   TEXT NOT NULL,
  agent_id     TEXT NOT NULL,
  provider     TEXT NOT NULL,
  model        TEXT NOT NULL,
  tokens_in    INTEGER NOT NULL,
  tokens_out   INTEGER NOT NULL,
  cost_usd     REAL,
  latency_ms   INTEGER NOT NULL,
  is_fallback  INTEGER NOT NULL DEFAULT 0,  -- 1 if this call only happened because an earlier chain entry failed
  error        TEXT,
  created_at   INTEGER NOT NULL
);
CREATE INDEX idx_provider_session  ON provider_calls(session_id, created_at);
CREATE INDEX idx_provider_agent    ON provider_calls(agent_id, created_at);
CREATE INDEX idx_provider_fallback ON provider_calls(is_fallback, created_at);

-- CAPR events: per-turn pass/fail verdict from the judge model
CREATE TABLE capr_events (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id   TEXT NOT NULL,
  agent_id     TEXT NOT NULL,
  turn_id      INTEGER NOT NULL,   -- references messages.id of the assistant reply
  passed       INTEGER NOT NULL,   -- 0 or 1
  judge_model  TEXT NOT NULL,
  rationale    TEXT,
  created_at   INTEGER NOT NULL
);
CREATE INDEX idx_capr_agent ON capr_events(agent_id, created_at);
```

```sql
-- V2__add_turn_id.sql

-- Shared turn identifier: UUID generated client-side at turn start, propagated
-- via ScopedValue<UUID> inside @AgentScoped (see §4.3.1). Pre-MVP baseline:
-- tables are empty at migration time. DEFAULT '' is required by SQLite because
-- ALTER TABLE ADD COLUMN NOT NULL without DEFAULT fails even on empty tables.
-- CHECK (turn_id != '') enforces the invariant at INSERT: a missing explicit
-- turn_id triggers DEFAULT '' and then fails the CHECK.

ALTER TABLE messages         ADD COLUMN turn_id TEXT NOT NULL DEFAULT '' CHECK (turn_id != '');
ALTER TABLE tool_invocations ADD COLUMN turn_id TEXT NOT NULL DEFAULT '' CHECK (turn_id != '');
ALTER TABLE provider_calls   ADD COLUMN turn_id TEXT NOT NULL DEFAULT '' CHECK (turn_id != '');

CREATE INDEX idx_messages_turn   ON messages(turn_id);
CREATE INDEX idx_tool_inv_turn   ON tool_invocations(turn_id);
CREATE INDEX idx_prov_calls_turn ON provider_calls(turn_id);

-- capr_events.turn_id semantics change from "= messages.id (INTEGER)" in V1 to
-- "shared UUID (TEXT)" in V2. SQLite cannot ALTER COLUMN TYPE, so the table is
-- recreated. Pre-MVP tables are empty, so no data migration is required.
CREATE TABLE capr_events_new (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id   TEXT NOT NULL,
  agent_id     TEXT NOT NULL,
  turn_id      TEXT NOT NULL CHECK (turn_id != ''),   -- shared UUID; mirrors messages.turn_id / tool_invocations.turn_id / provider_calls.turn_id
  passed       INTEGER NOT NULL,                       -- 0 or 1
  judge_model  TEXT NOT NULL,
  rationale    TEXT,
  created_at   INTEGER NOT NULL
);
DROP TABLE capr_events;
ALTER TABLE capr_events_new RENAME TO capr_events;
CREATE INDEX idx_capr_agent ON capr_events(agent_id, created_at);
CREATE INDEX idx_capr_turn  ON capr_events(turn_id);
```

The `provider_calls` table is deliberately denormalized — it stores the model name and provider as plain strings rather than a foreign key into a providers table — because CAPR queries aggregate over time windows and a string scan is cheaper than a join on a small dimension table. The `is_fallback = 1` flag on a call indicates that this call only happened because an earlier call in the fallback chain failed; querying `SELECT agent_id, COUNT(*) FROM provider_calls WHERE is_fallback = 1 GROUP BY agent_id` immediately surfaces agents whose primary model is unreliable.

The `semantic_memory.embedding` column stores a raw `float32` vector as a BLOB. For the MVP we scan linearly (row counts in a single-user deployment are small); when the row count justifies it, we add a `vec0` virtual table via the `sqlite-vec` extension in a later Flyway migration (V3 or later) without changing the surrounding schema. The engine never rewrites history — Flyway is locked forward-only and a CI check fails the build if any existing migration file is modified.

### 4.3 Contract Specifications

Core domain contracts that cross module boundaries are specified here to lock their shape before M2 writes the records. Each entry is treated as a frozen contract: deviations during implementation require amending this section first.

#### 4.3.1 Turn identifier

`turnId: java.util.UUID`. Every turn has a single UUID generated client-side at the *start* of the turn, before the user message is inserted into `messages`. It is propagated through the turn's execution via `ScopedValue<UUID> CURRENT_TURN` bound inside the `@AgentScoped` context (see §5.1). Every `AgentEvent` record carrying a `turnId` field (`Done`, `ErrorEvent`) uses this same UUID, and the same value is written to `messages.turn_id`, `tool_invocations.turn_id`, `provider_calls.turn_id`, and `capr_events.turn_id` (columns added in Flyway V2, see §4.2). This contract is consumed by M6 (`@AgentScoped` scope implementation); any deviation in M6 breaks structural correlation and replay.

#### 4.3.2 AgentEvent hierarchy

The `AgentEvent` hierarchy is a sealed interface with six permits, designed for exhaustive pattern-matching in channel, router, and persistence code paths (a `switch` over `AgentEvent` compiles without a `default` branch). Every event carries an `Instant timestamp()` recording the moment of creation (not emission). Channels subscribe to the event stream for rendering; persistence writers correlate events to `messages`, `tool_invocations`, `provider_calls`, and `capr_events` rows via the ids carried on the events themselves.

```java
// Module: forvum-core
// Package: ai.forvum.core.event
// Each top-level type below lives in its own .java file.
// Forward reference to ModelRef resolves in §4.3.5.1 (Group 4a).

package ai.forvum.core.event;

import ai.forvum.core.InvocationStatus;  // §4.3.3 (Group 2)
import ai.forvum.core.ModelRef;  // §4.3.5.1 (Group 4a)

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;

/**
 * Events emitted by the agent runtime during a turn's execution.
 *
 * <p>{@link #timestamp()} is the time of event creation, not of emission
 * or delivery to subscribers. Consumers monitoring end-to-end latency must
 * use their own receipt timestamp.
 */
public sealed interface AgentEvent
    permits TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent {

    Instant timestamp();
}

public record TokenDelta(Instant timestamp, String text, ModelRef model)
    implements AgentEvent {}

public record ToolInvoked(
    Instant timestamp,
    long invocationId,
    String toolName,
    String arguments
) implements AgentEvent {}

public record ToolResult(
    Instant timestamp,
    long invocationId,
    String result,
    InvocationStatus status,
    long latencyMs
) implements AgentEvent {}

public record FallbackTriggered(
    Instant timestamp,
    ModelRef failed,
    ModelRef next,
    String reason
) implements AgentEvent {}

public record Done(
    Instant timestamp,
    UUID turnId,
    String finalMessage
) implements AgentEvent {}

public record ErrorEvent(
    Instant timestamp,
    UUID turnId,
    String code,
    String message,
    String exceptionClass,
    String stackTraceText
) implements AgentEvent {

    public static ErrorEvent from(Instant timestamp, UUID turnId,
                                  String code, String message, Throwable cause) {
        return new ErrorEvent(
            timestamp, turnId, code, message,
            cause == null ? null : cause.getClass().getName(),
            cause == null ? null : stackTraceToString(cause)
        );
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}

public final class FallbackReasons {
    public static final String RATE_LIMIT   = "rate_limit";
    public static final String TIMEOUT      = "timeout";
    public static final String SERVER_ERROR = "server_error";
    public static final String COST_BUDGET  = "cost_budget";

    private FallbackReasons() {}
}
```

**Type conventions and forward references:**

- `turnId: java.util.UUID` — see §4.3.1.
- `invocationId: long` — mirrors `tool_invocations.id` autoincrement; assigned by the engine at row INSERT before emitting `ToolInvoked`.
- `InvocationStatus` (on `ToolResult`) — resolved in §4.3.3 (Group 2).
- `ModelRef` (on `TokenDelta`, `FallbackTriggered`) — resolved in §4.3.5.1 (Group 4a).
- `FallbackTriggered.reason` is a `String` populated from `FallbackReasons.*` constants only. Migration to a `FailureClass` enum is scheduled for M8 once the taxonomy stabilizes.

#### 4.3.3 SQL-mirror enums (`role`, `event_type`, `status`)

Three enums in `forvum-core` mirror the V1 CHECK constraints on `messages.role`, `episodic_memory.event_type`, and `tool_invocations.status`. Each enum encodes the DB-literal string explicitly via a `dbValue()` accessor and parses incoming JDBC values via `fromDbValue(String)`. Changing a CHECK constraint requires a coordinated update here *plus* a forward-only Flyway migration.

```java
// Module: forvum-core
// Package: ai.forvum.core
// Each enum below lives in its own .java file (Role.java, EventType.java,
// InvocationStatus.java). Values and their DB-literal strings mirror the V1
// CHECK constraints in §4.2 verbatim.

package ai.forvum.core;

public enum Role {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");

    private final String dbValue;
    Role(String dbValue) { this.dbValue = dbValue; }
    public String dbValue() { return dbValue; }

    public static Role fromDbValue(String value) {
        for (Role r : values()) if (r.dbValue.equals(value)) return r;
        throw new IllegalStateException(
            "Unknown role value from DB: '" + value + "'. Indicates schema drift "
          + "or data corruption. Check Flyway migrations and DB integrity.");
    }
}

public enum EventType {
    OBSERVATION("observation"),
    DECISION("decision"),
    REFLECTION("reflection");

    private final String dbValue;
    EventType(String dbValue) { this.dbValue = dbValue; }
    public String dbValue() { return dbValue; }

    public static EventType fromDbValue(String value) {
        for (EventType e : values()) if (e.dbValue.equals(value)) return e;
        throw new IllegalStateException(
            "Unknown event_type value from DB: '" + value + "'. Indicates schema drift "
          + "or data corruption. Check Flyway migrations and DB integrity.");
    }
}

public enum InvocationStatus {
    OK("ok"),
    ERROR("error"),
    DENIED("denied");

    private final String dbValue;
    InvocationStatus(String dbValue) { this.dbValue = dbValue; }
    public String dbValue() { return dbValue; }

    public static InvocationStatus fromDbValue(String value) {
        for (InvocationStatus s : values()) if (s.dbValue.equals(value)) return s;
        throw new IllegalStateException(
            "Unknown invocation status value from DB: '" + value + "'. Indicates schema drift "
          + "or data corruption. Check Flyway migrations and DB integrity.");
    }
}
```

**Conventions and rationale:**

- **Explicit `dbValue` pattern.** Each constant carries its DB-literal string in a private field; `dbValue()` exposes it to persistence adapters; `fromDbValue(String)` parses back from JDBC result sets. This decouples enum renames from the DB schema — Java constants can be refactored without breaking persisted rows.
- **Unknown values throw `IllegalStateException`.** The expected source is the DB itself, whose V1 CHECK constraints prevent unknown values at INSERT. Encountering an unknown value at read time indicates schema drift or data corruption, not a caller mistake — `IllegalStateException` communicates that precisely and makes production-log triage unambiguous.
- **Exception messages name the DB kind explicitly** (`role`, `event_type`, `invocation status`) and point operators at the two likely causes (Flyway migration drift, direct DB mutation) so the first log line of a symptom carries actionable triage information.
- **Value order mirrors the V1 CHECK lists** for readability during review. Ordering is not load-bearing at runtime.
- **One file per enum** in `ai.forvum.core` (`Role.java`, `EventType.java`, `InvocationStatus.java`). Each has an independent lifecycle: a future CHECK-constraint change touches only one file.
- **`InvocationStatus` resolves the forward reference** declared by `ToolResult.status` in §4.3.2.
- **Naming.** `InvocationStatus` maps 1:1 to `tool_invocations.status`. A less specific name was rejected because it would invite unrelated reuse across the domain as a general-purpose flag.

#### 4.3.4 PermissionScope

`PermissionScope` is a closed Java enum in `forvum-core` naming the capability classes that tools declare and the engine's `ToolExecutor` enforces before invoking a tool. Unlike the Group 2 SQL-mirror enums, it is not persisted as a typed column in V1 — a denied call surfaces only through `tool_invocations.status = 'denied'`. Serialization to JSON/YAML config (identity files, tool manifests) uses `name()` directly; parsing back into Java uses `fromName(String)`.

```java
// Module: forvum-core
// Package: ai.forvum.core
// Single .java file: PermissionScope.java

package ai.forvum.core;

/**
 * Capability scopes that tools declare and the engine's ToolExecutor enforces
 * before invoking a tool. A tool's required scope must be reachable from the
 * agent's allowed-tools set (indirectly, via the tool's registration) or the
 * call is refused with PermissionDeniedException and logged to
 * {@code tool_invocations} with {@code status = 'denied'}.
 *
 * <p>Not persisted as a typed SQL column in V1 — denial outcome is captured
 * only by {@code tool_invocations.status}. Serialization to JSON/YAML config
 * uses {@link #name()} directly.
 *
 * <p>This enum is closed at compile time and grows at milestone boundaries.
 * See project docs (docs/ULTRAPLAN.md §6). Plugins compiled against a given
 * core version may only reference scopes present in that version.
 */
public enum PermissionScope {
    FS_READ,
    FS_WRITE;

    /**
     * Parses a string into a {@code PermissionScope}, throwing a contextual
     * {@link IllegalStateException} on unknown input.
     *
     * <p>Preferred over {@link Enum#valueOf(Class, String)} because the
     * built-in throws a generic {@link IllegalArgumentException} whose
     * message (e.g., {@code "No enum constant ai.forvum.core.PermissionScope.FOO"})
     * does not identify the likely cause (config drift, hand-edited manifest)
     * or point an operator at where to look. This factory's
     * {@code IllegalStateException} message names the suspect sources
     * explicitly so a production log line carries actionable triage info.
     *
     * @param value the raw string from a config file or manifest
     * @return the matching {@code PermissionScope}
     * @throws IllegalStateException if {@code value} is {@code null} or does
     *         not match any declared scope
     */
    public static PermissionScope fromName(String value) {
        for (PermissionScope s : values()) if (s.name().equals(value)) return s;
        throw new IllegalStateException(
            "Unknown PermissionScope value: '" + value + "'. Indicates config drift "
          + "or an invalid identity/tool manifest. Check files under $FORVUM_HOME.");
    }
}
```

**Conventions and rationale:**

- **Closed at compile time, grows at milestone boundaries.** Java enums cannot be extended at runtime; plugins loaded from `plugins/` (JVM fast-jar mode) reference scopes by identifier, which forces a core-version bump whenever a plugin needs a new scope. This trade-off is deliberate — it keeps the authorization surface auditable in one file.
- **Not persisted in SQL.** V1 has no `tool_invocations.permission_scope` column; denial records the outcome via `status = 'denied'` only. Any future requirement to query denials *by scope* should add the column via a forward-only Flyway migration rather than backfill via string matching on `result`.
- **`fromName(String)` over `Enum.valueOf`.** The custom factory throws `IllegalStateException` with a contextual message ("config drift or invalid manifest, check $FORVUM_HOME"), which makes triage of a bad identity file immediate. `Enum.valueOf` throws `IllegalArgumentException` with a generic "No enum constant …" message and does not differentiate "caller bug" from "config-file corruption". See the JavaDoc on `fromName` for the full rationale.
- **Serialization via `name()`.** No `dbValue` field and no `fromDbValue` — those belong to SQL-mirror enums (Group 2). Identity and tool-manifest parsers use `PermissionScope.fromName(...)`; emitters write `scope.name()`.
- **Single file, `ai.forvum.core` package** (same package as the Group 2 enums). A permissions sub-package is deferred until a second type joins.
- **`FS_READ` covers directory listing (`FsListTool`).** Granularity between content-read and metadata-list is not separated in MVP; if needed, `FS_LIST` would be added as a specialized scope in a future milestone.
- **RBAC layer is orthogonal.** §6.3 line 825 ("`PermissionScope` extended to role-based sets; `identities/<id>.json` declares roles") adds a Phase 2 role → scope-set mapping above this enum, not inside it. The enum itself remains a flat list of capabilities.

**Reserved future values:**

| Scope (reserved name)  | Introduced in | Note |
|------------------------|---------------|------|
| WEB_BROWSE             | Phase 2 (§6)  | browser tool |
| WEB_FETCH / WEB_SEARCH | bundled-web   | naming TBD in owning PR |
| SHELL_EXEC             | Phase 2 (§6)  | sandboxed shell |

These names are forward-reserved to avoid bikeshedding in the owning PR. Each will land in `PermissionScope.java` in the milestone that introduces its consuming tool module.

#### 4.3.5 ModelRef, FallbackChain, CostBudget

##### 4.3.5.1 `ModelRef`

A `ModelRef` identifies a concrete LLM to invoke: a provider (which Quarkiverse extension handles the call) and a model (the specific string that provider understands). It is a value object, intentionally minimal, and is the glue between user-facing configuration (`agents/*.json`, `crons/*.json`), the provider resolution SPI (`ModelProvider.resolve(ModelRef) → ChatModel`), and the telemetry row (`provider_calls.provider` / `provider_calls.model`).

```java
// Module: forvum-core
// Package: ai.forvum.core

package ai.forvum.core;

public record ModelRef(String provider, String model) {

    public ModelRef {
        if (provider == null || provider.isBlank()
            || !provider.strip().equals(provider)) {
            throw new IllegalStateException(
                "ModelRef provider must be a non-blank token without "
              + "leading/trailing whitespace. Got: '" + provider + "'. "
              + "Check config file formatting.");
        }
        if (model == null || model.isBlank()
            || !model.strip().equals(model)) {
            throw new IllegalStateException(
                "ModelRef model must be a non-blank token without "
              + "leading/trailing whitespace. Got: '" + model + "'. "
              + "Check config file formatting.");
        }
        provider = provider.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Parse a {@code provider:model} spec string.
     *
     * <p>Splits on the FIRST colon only, so provider-specific model strings
     * that themselves contain colons (e.g., Ollama tags like {@code qwen3:1.7b})
     * survive the round-trip. Both sides must be non-blank and free of
     * leading/trailing whitespace; provider is case-folded to lowercase.
     *
     * <p>Provider-only shorthand (e.g., {@code "ollama"} without a model) is
     * NOT supported — see the convention note below.
     *
     * @throws IllegalStateException if {@code spec} is null, blank, has no
     *         colon, or either side fails the canonical constructor checks.
     */
    public static ModelRef parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalStateException(
                "ModelRef spec must be non-blank. Check config file formatting.");
        }
        int colon = spec.indexOf(':');
        if (colon < 0) {
            throw new IllegalStateException(
                "ModelRef spec must use 'provider:model' form; got: '" + spec
              + "'. Provider-only shorthand is not supported — configuration "
              + "files must specify both halves explicitly.");
        }
        String p = spec.substring(0, colon);
        String m = spec.substring(colon + 1);
        return new ModelRef(p, m);
    }

    @Override
    public String toString() {
        return provider + ":" + model;
    }
}
```

**Conventions:**

- **SQL persistence decomposes to `(provider, model)` — two columns, not one combined string.** `provider_calls.provider` (TEXT NOT NULL) and `provider_calls.model` (TEXT NOT NULL) map 1:1 to the two record components. The denormalization exists specifically to make `provider_calls` queryable without parsing — "show me all failures of `anthropic`" stays a single indexable predicate. This is the fundamental reason `ModelRef` has two fields instead of one string.
- **Format is `provider:model`, both halves required.** `ModelRef` does not support provider-only shorthand; configuration files (`agents/*.json`, `crons/*.json`) MUST specify the full `provider:model` form. A value like `"ollama"` alone fails at parse time — it does not silently pick a default model from the provider registry. If config-layer ergonomics ever requires shorthand, it would be added as syntactic sugar in the config parser (which owns its own defaults policy), not in the type — `ModelRef` stays honest about what it identifies.
- **First-colon split is intentional.** Ollama tags (`qwen3:1.7b`, `llama3.2:1b`) and some vendor version strings use colons internally. The parser treats only the first colon as the delimiter and passes the rest through untouched.
- **Equality invariants worth pinning in M2 tests:** `ModelRef.parse("ANTHROPIC:foo")` equals `ModelRef.parse("anthropic:foo")` (provider case-folds to lowercase in the canonical constructor); `ModelRef.parse("anthropic:Foo")` does NOT equal `ModelRef.parse("anthropic:foo")` (model is case-preserved because provider model identifiers are case-sensitive at the API level).
- **Serialization roundtrip invariant:** for any valid `ModelRef` `ref`, `ModelRef.parse(ref.toString()).equals(ref)` holds. Config writers should use `toString()` when emitting to JSON/YAML; no separate serialization method is needed. (Together with the case-folding invariant above: input `"Ollama:foo"` parses, normalizes to `"ollama:foo"`, and roundtrips as `"ollama:foo"`.)
- **Deferred to M7 (observability):** whether the OpenTelemetry span `forvum.llm.call` emits the model as a single `<provider>:<model>` string attribute (matching `ModelRef.toString()`) or as two separate attributes (`llm.provider` + `llm.model`). The type contract supports both — M7 picks based on OTel semantic-conventions prevailing at implementation time.
- **Forward reference resolution.** The `ModelRef` forward reference declared in §4.3.2 (`TokenDelta.modelRef`, `FallbackTriggered.primary`, `FallbackTriggered.attempted`) resolves to this record.

##### 4.3.5.2 `CostBudget` and related types

A `CostBudget` declares a monetary (USD) and/or token cap bounding an agent's or cron's LLM spend over a scoped time window. It is a pure data record — caps and window are static configuration — paired with a read-side service, `BudgetMeter`, that reports current usage by aggregating over the `provider_calls` ledger (§4.2) on demand. Nothing is persisted on `CostBudget` itself; the ledger is authoritative. Per-dimension opt-in is expressed via nullability: `maxUsd = null` means "no USD cap", `maxTokens = null` means "no token cap", and both being null is rejected at construction time.

All types in this subsection live in the `ai.forvum.core.budget` package of the `forvum-core` module, co-located with `ModelRef` (§4.3.5.1) at the module level but grouped into their own package to signal the budget surface as a discoverable unit. Two unchecked exceptions are declared alongside the records — `BudgetExhaustedException` (thrown by `FallbackChatModel` on a pre-call check, §5.4) and `SpawnConfigurationException` (thrown at spawn time when a `SessionWindow`-scoped parent budget would be silently inherited without a child-specific override, §5.5) — both caught by the engine and surfaced as terminal `Error` `AgentEvent`s (§4.3.2). `CostBudget` is distinct from `toolBudget` (§5.5 tool-loop count cap); the two caps coexist on each config independently.

```java
// Module: forvum-core
// Package: ai.forvum.core.budget
// Each top-level type below lives in its own .java file.

package ai.forvum.core.budget;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.UUID;

// -- Shape: the cap carrier --

public record CostBudget(BigDecimal maxUsd, Long maxTokens, Window window) {
    public CostBudget {
        if (maxUsd == null && maxTokens == null) {
            throw new IllegalStateException(
                "CostBudget must declare at least one cap (maxUsd, "
              + "maxTokens, or both). Both nulls indicates either a "
              + "config-file error or a programmatic construction bug. "
              + "Check agents/<id>.json or crons/<id>.json.");
        }
        if (maxUsd != null && maxUsd.signum() < 0) {
            throw new IllegalStateException(
                "CostBudget maxUsd must be non-negative. Got: " + maxUsd
              + ". Negative caps are nonsensical — check config "
              + "file formatting.");
        }
        if (maxTokens != null && maxTokens < 0) {
            throw new IllegalStateException(
                "CostBudget maxTokens must be non-negative. Got: " + maxTokens
              + ". Negative caps are nonsensical — check config "
              + "file formatting.");
        }
        if (window == null) {
            throw new IllegalStateException(
                "CostBudget window must be non-null. Every budget "
              + "requires an explicit time window (DayWindow or "
              + "SessionWindow). Check agents/<id>.json or "
              + "crons/<id>.json — if the timezone field was the "
              + "only window-related config, the config parser "
              + "must still construct a DayWindow with resolved "
              + "ZoneId before building CostBudget.");
        }
    }
}

// -- Scope and timing --

/**
 * Scope + time window over which a {@link CostBudget} is aggregated.
 *
 * <p><b>Marker interface with scope data carried by permits.</b>
 * This interface declares no methods. Each permit carries the scope
 * data relevant to its granularity ({@link ZoneId} for
 * {@link DayWindow}; {@code sessionId + agentId} for
 * {@link SessionWindow}). Consumers that must derive a persistence
 * query from a {@code Window} pattern-match on the permit type via
 * exhaustive {@code switch} — see {@link BudgetMeter} implementations
 * in the M5 persistence layer (§5.x) for the canonical pattern.
 *
 * <p><b>Extensibility contract.</b> Adding a new permit to this
 * sealed interface is a source-compatible change for code that only
 * constructs or passes {@code Window} values. Consumers that
 * pattern-match on permits via exhaustive {@code switch} will fail
 * compilation until the new permit is handled — this is the intended
 * surfacing mechanism for new cost-window policies.
 */
public sealed interface Window permits DayWindow, SessionWindow {
}

public record DayWindow(ZoneId tz) implements Window {
    public DayWindow {
        if (tz == null) {
            throw new IllegalStateException(
                "DayWindow tz must be non-null. Config-parse boundary "
              + "substitutes ZoneId.systemDefault() when the \"timezone\" "
              + "field is absent from agents/<id>.json or crons/<id>.json, "
              + "so a null reaching this constructor indicates a wiring bug.");
        }
    }
}

public record SessionWindow(String sessionId, String agentId) implements Window {
    public SessionWindow {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException(
                "SessionWindow sessionId must be non-null and non-blank. "
              + "Got: '" + sessionId + "'. This indicates the session "
              + "context was not propagated at construction time.");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException(
                "SessionWindow agentId must be non-null and non-blank. "
              + "Got: '" + agentId + "'. Check agent resolution before "
              + "constructing.");
        }
    }
}

// -- Read-side --

/**
 * Read-side service for querying a {@link CostBudget}'s
 * current usage.
 *
 * <p>Implementations aggregate over {@code provider_calls}
 * (§4.2) scoped by the budget's {@link Window} to produce
 * an atomic {@link Usage} snapshot in a single SQL trip.
 * The default implementation lives in the M5 persistence
 * layer (§5.x); this interface ships only the contract.
 *
 * <p>Safe to inject as a singleton CDI bean; no per-call
 * state.
 */
public interface BudgetMeter {
    Usage usage(CostBudget budget);
}

/**
 * Atomic snapshot of a {@link CostBudget}'s current usage, produced
 * by a single {@code SUM()} trip over {@code provider_calls} and
 * consumed jointly by the enforcement path and observability layers.
 *
 * <p><b>Atomicity.</b> All four fields derive from the same SQL
 * aggregation; no caller should recompute any field from the others.
 * The snapshot is a point-in-time read — subsequent calls can return
 * different values as concurrent turns advance the ledger.
 *
 * <p><b>Biconditional invariant.</b> The canonical constructor
 * enforces {@code cause != null} if and only if
 * {@code exhausted == true}. A violation throws
 * {@link IllegalStateException} and names which side of the
 * biconditional was broken (see constructor source).
 */
public record Usage(
    Spend spent,              // already consumed (usd + tokens)
    Spend remaining,          // headroom; individual dimensions may be
                              // null when the matching cap on CostBudget
                              // is null (opt-out per Decision 1)
    boolean exhausted,        // any non-null cap reached
    ExhaustionCause cause     // null iff exhausted == false
) {
    public Usage {
        if (spent == null) {
            throw new IllegalStateException(
                "Usage spent must be non-null. Construct via BudgetMeter "
              + "or test fixtures — a null here indicates a programmatic "
              + "construction bug.");
        }
        if (remaining == null) {
            throw new IllegalStateException(
                "Usage remaining must be non-null. Individual dimensions "
              + "inside the Spend may be null (opt-out), but the Spend "
              + "record itself must be present.");
        }
        if (exhausted && cause == null) {
            throw new IllegalStateException(
                "Usage cause must accompany exhausted=true. Either the "
              + "ExhaustionCause was lost between BudgetMeter query and "
              + "Usage construction, or the exhausted flag was set "
              + "without computing cause. Check BudgetMeter output.");
        }
        if (!exhausted && cause != null) {
            throw new IllegalStateException(
                "Usage cause must be null when exhausted=false. A non-null "
              + "cause paired with exhausted=false indicates a caller "
              + "populated cause without flipping the exhausted flag.");
        }
    }
}

public record Spend(BigDecimal usd, Long tokens) {
    public Spend {
        if (usd != null && usd.signum() < 0) {
            throw new IllegalStateException(
                "Spend usd must be null or non-negative. Got: " + usd
              + ". Negative values indicate either a ledger accounting "
              + "bug or an arithmetic underflow in BudgetMeter.");
        }
        if (tokens != null && tokens < 0) {
            throw new IllegalStateException(
                "Spend tokens must be null or non-negative. Got: " + tokens
              + ". Negative values indicate either a ledger accounting "
              + "bug or an arithmetic underflow in BudgetMeter.");
        }
    }
}

public enum ExhaustionCause {
    USD_CAP_HIT,
    TOKEN_CAP_HIT,
    BOTH_CAPS_HIT
}

// -- Failure signals --

/**
 * Thrown by {@code FallbackChatModel.chat(...)} when a pre-call
 * {@link BudgetMeter#usage(CostBudget)} check returns
 * {@code exhausted == true}. The exception short-circuits the
 * fallback chain for cost-driven exhaustion — no further links are
 * attempted, in contrast with retry-class {@code FallbackReasons}
 * (see §5.4) — and is caught by the engine layer, which surfaces it
 * as a terminal {@code Error} {@link ai.forvum.core.event.AgentEvent}
 * with {@code code = "budget_exhausted"} plus {@code cause} and
 * {@code turnId} attributes.
 *
 * <p>Unchecked because the only legitimate catcher is the engine
 * layer; intermediate layers should not declare {@code throws}.
 */
public final class BudgetExhaustedException extends RuntimeException {
    private final ExhaustionCause cause;
    private final UUID turnId;

    public BudgetExhaustedException(ExhaustionCause cause, UUID turnId) {
        super("Budget exhausted: " + cause + " in turn " + turnId);
        this.cause = cause;
        this.turnId = turnId;
    }

    public ExhaustionCause cause() { return cause; }
    public UUID turnId() { return turnId; }
}

/**
 * Thrown at spawn time when a parent agent's
 * {@link CostBudget} carries a {@link SessionWindow} and
 * the spawn request omits an explicit budget override.
 *
 * <p>When a parent's {@code CostBudget} uses
 * {@code SessionWindow}, the window filters by the
 * parent's {@code (sessionId, agentId)} pair — so
 * inheriting it verbatim into a child would cause every
 * call the child makes (tagged with the child's own
 * {@code (sessionId, agentId)}) to be invisible to the
 * budget's SUM aggregation. The child would appear to
 * have unlimited budget. This exception surfaces the
 * misconfiguration at spawn time rather than silently
 * at runtime. See §5.5 for the validation site and the
 * recommended override shape.
 *
 * <p>Like {@link BudgetExhaustedException}, this is
 * unchecked and is caught by the engine layer, which
 * surfaces it as a terminal {@code Error}
 * {@link ai.forvum.core.event.AgentEvent} with
 * {@code code = "spawn_invalid_config"} plus
 * {@code parentAgentId}, {@code childAgentId}, and the
 * educational {@code getMessage()} text.
 */
public final class SpawnConfigurationException extends RuntimeException {
    private final String parentAgentId;
    private final String childAgentId;

    public SpawnConfigurationException(
            String parentAgentId, String childAgentId, String reason) {
        super(reason);
        this.parentAgentId = parentAgentId;
        this.childAgentId = childAgentId;
    }

    public String parentAgentId() { return parentAgentId; }
    public String childAgentId() { return childAgentId; }
}
```

**Type conventions and cross-references:**

- **Two-dimensional cap with per-dimension opt-in, expressed as a flat record.** `maxUsd` (`BigDecimal`) and `maxTokens` (`Long`) are both nullable; at least one must be non-null (enforced in the canonical constructor). Pure-cloud users configure USD only; pure-local users configure tokens only; hybrid users configure both. Exhaustion fires when *any* non-null cap is reached. A flat record was chosen over a sealed hierarchy of cap types (`UsdBudget`/`TokenBudget`/`CompositeBudget`) because USD and token dimensions differ only in which field is populated — not in schema shape — so the hierarchy would import form without the justifying condition. (Decisions 1, 4)
- **Validation via `IllegalStateException` with origin-naming messages.** All canonical constructors in this subsection throw `IllegalStateException` (not `IllegalArgumentException`) with triage-oriented text naming the likely origin of the invalid value — config file (`agents/<id>.json`, `crons/<id>.json`) or a ledger / arithmetic bug. `CostBudget`'s canonical constructor in particular enforces four invariants: at least one cap is non-null, `maxUsd` (if present) is non-negative, `maxTokens` (if present) is non-negative, and `window` is non-null. Matches the convention set by `ModelRef` (§4.3.5.1) and earlier Tier-1 Groups. (Decision 4)
- **`provider_calls` is the authoritative cost ledger.** Neither `CostBudget` nor `Usage` persists spend state; the read-side aggregates over `provider_calls` on demand via `SUM(cost_usd), SUM(tokens_in + tokens_out)` scoped by the current `Window`. Denormalized aggregate columns were rejected to avoid the derived-cache desync class of bugs. (Decision 2)
- **Null `cost_usd` rows contribute zero to the USD aggregation.** Providers that do not report cost (e.g., local Ollama) write `NULL` to `provider_calls.cost_usd`; `SUM(cost_usd)` ignores NULL by SQL semantics, yielding the intuitive "local calls are free in USD" contract without `COALESCE` or provider-name special-casing. The token dimension is unaffected (`tokens_in` and `tokens_out` are `NOT NULL`). (Decision 3)
- **`Window` is a sealed marker interface; permits carry scope data.** The interface declares no methods. `DayWindow(ZoneId tz)` holds the resolved timezone; `SessionWindow(String sessionId, String agentId)` holds the session identity. `BudgetMeter` implementations pattern-match on the permit type via exhaustive `switch` to build the SQL filter; adding a new permit breaks compilation at consumer sites, which is the intended extensibility surface. (Decision 5)
- **`ZoneId` resolution on `DayWindow` is config-parse work, not runtime.** The optional `timezone` field in `agents/<id>.json` (and `crons/<id>.json`) is parsed at config load; absent → `ZoneId.systemDefault()`. A null `tz` reaching the `DayWindow` constructor indicates a wiring bug, not a missing config. (Decision 5)
- **Reset policy is implicit in each `Window` permit.** `DayWindow` resets at calendar-aligned midnight in its `tz`. `SessionWindow` has no internal reset — it spans the lifetime of the identified session. A free `reset` field was rejected because it would admit nonsensical `(permit, reset)` combinations that the type system should rule out. (Decision 6)
- **`BudgetMeter` is a service; `CostBudget` is pure data.** The read-side of the budget lives on a CDI-wired bean, not on `CostBudget` itself — runtime concerns (ledger queries) stay out of the data record. The default `BudgetMeter` implementation lives in the M5 persistence layer (§5.x) and owns the SQL; §4.3.5.2 ships only the interface. (Decision 7)
- **`Usage` is an atomic snapshot carrying spent, remaining, exhaustion, and cause.** A single `SUM()` trip populates every consumer's need: enforcement reads `usage.exhausted()`; operator commands read `usage.spent()` / `usage.remaining()`; fallback-reason classification reads `usage.cause()`. No re-query race between "check exhausted" and "observe spent" because all four fields come from the same aggregation. (Decision 7)
- **`Spend` has boxed `Long tokens`, not primitive.** Allows `remaining.tokens` to be `null` when the budget's `maxTokens` is `null` (opt-out per Decision 1). Sentinel alternatives (`Long.MAX_VALUE` for "no cap") were rejected as obfuscating. `spent`'s non-null dimensions are a `BudgetMeter` contractual guarantee, not a `Spend`-constructor invariant — the constructor permits null so the type stays reusable for `remaining`. (Decision 7)
- **`ExhaustionCause` includes `BOTH_CAPS_HIT` as a first-class constant.** The rare case where both dimensions reach their caps in the same SQL trip is visible downstream (fallback reason, operator log, OTel attribute) without an ad-hoc bitmask encoding. (Decision 7)
- **`FallbackChatModel` pre-call is the enforcement point.** The decorator invokes `BudgetMeter.usage(budget)` before dispatching to the selected `ModelProvider`; exhaustion emits `FallbackTriggered(reason = FallbackReasons.COST_BUDGET, cause = usage.cause)`. Enforcement is **best-effort**: the check-to-record window creates overshoot bounded by `concurrent_calls × per_call_cost` — typical interactive use ~$0.02–$0.10, heavy-fanout scenarios up to ~$5. Users with strict caps should configure `maxUsd` 5–10% below their hard limit. (Decision 8)
- **Cost-driven exhaustion short-circuits the chain via `BudgetExhaustedException`.** Unlike retry-class `FallbackReasons` (`RATE_LIMIT`, `TIMEOUT`, `SERVER_ERROR`) where the chain loop `continue`s to the next link, `COST_BUDGET` throws `BudgetExhaustedException` from `FallbackChatModel.chat(...)` — no further links are attempted; the engine catches the exception and emits a terminal `Error` `AgentEvent` with `code = "budget_exhausted"`. Unchecked because the only legitimate catcher is the engine layer; intermediate layers avoid `throws` pollution. (Decision 9)
- **Warn+continue, escalate-to-human, and per-agent-configurable exhaustion responses are explicitly rejected for MVP.** Warn defeats the cap's purpose; escalate needs notification infrastructure absent in Tier 1; per-agent configurability is speculative flexibility without articulated demand. (Decision 9)
- **Inherited spawn budgets are independent copies.** A child agent's `CostBudget` equals the parent's (immutable record; reference-passed is semantically equivalent to value-copy), but the SQL scope resolves per the child's `agent_id` via CDI context, so spend is tracked independently per agent. Parent caps do **not** bound total tree spend by default — operators needing total-tree enforcement configure conservative per-agent caps or pass explicit reduced budgets at spawn time. (Decision 10)
- **Override is all-or-nothing.** The spawn API (§5.5) accepts an optional `CostBudget` parameter: absent ⇒ inherit verbatim, present ⇒ replace entirely. No partial merge — ambiguous null semantics (opt-out vs. inherit-this-field) would make the API unreadable. Callers wanting partial adjustments construct the full `CostBudget` explicitly from the parent's immutable record. (Decision 10)
- **`SessionWindow` inheritance without override is prohibited at spawn time.** A parent `CostBudget` with `SessionWindow` points at parent's `(sessionId, agentId)`; silently inheriting it into a child would cause the child's calls to be invisible to its own SUM, yielding an effectively unbounded budget with no warning. The spawn path throws `SpawnConfigurationException` in that case, carrying parent/child agent IDs and an educational message pointing the operator at the override mechanism. Document-as-degenerate and smart-inheritance (auto-reconstruct) were both rejected in favor of spawn-boundary surfacing. (Decision 10)
- **Cross-references.** `provider_calls` schema + indexes: §4.2 (V1 schema). Enforcement call site and dispatch pseudocode: §5.4 (`FallbackChatModel`). Spawn mechanism and override parameter: §5.5 (`spawn_worker`). `Error` `AgentEvent` mapping for thrown exceptions (`budget_exhausted`, `spawn_invalid_config`): §4.3.2. `ModelRef` (co-located in `forvum-core` module, different package `ai.forvum.core`): §4.3.5.1. OpenTelemetry span attributes consuming `Usage.spent` and `Usage.cause`: §3.4.
- **Reserved future extension paths.** *New `Window` permits* — `LineageWindow(rootAgentId)` for shared-budget hierarchies (Group 4c or later), rolling-window / monthly / unbounded-accumulation permits as additional `permits` of the sealed interface (non-breaking). *Per-link cost awareness in `FallbackChain`* — if Group 4c enriches chain links with `costDims` declarations, Decision 9's short-circuit becomes overridable by chain policy, enabling free-tier fallback substitution. *Race-window mitigations* — pre-allocation soft reservations or pessimistic locking if production telemetry shows overshoot exceeding the documented bounds of Decision 8. *Exhaustion escalation* — escalate-to-human via channel notification if demand surfaces post-MVP.

##### 4.3.5.3 `FallbackChain`

*TBD (Group 4c).*

#### 4.3.6 MemoryPolicy

*TBD (Group 5).*

---

## 5. Sub-agents and CDI Isolation

The single hardest architectural problem Forvum solves is running many agents in the same process without their contexts poisoning each other. The failure mode we are preventing is **context clash**: a sub-agent inheriting the wrong tools, the wrong memory, or the wrong system prompt from a sibling because the container short-circuited scoping. `CONTEXT-ENGINEERING.md` names this explicitly as a reason projects fail in production. Forvum's answer is a Quarkus-native custom CDI scope combined with Java 25 `ScopedValue`, implemented at build time so it survives native compilation.

### 5.1 The `@AgentScoped` custom CDI context

`@AgentScoped` is a `@NormalScope` annotation declared in `forvum-core`. Its backing context is an implementation of Quarkus ArC's `InjectableContext` SPI, registered via a `BuildStep` in `forvum-engine` so ArC discovers it at build time and generates the correct native reflection hints. A `ScopedValue<AgentId> CURRENT_AGENT` is the context's identity key — every CDI bean annotated `@AgentScoped` resolves to the bean instance keyed by the current scoped value at injection time.

`ScopedValue` over `ThreadLocal` is not a cosmetic choice. Virtual threads fanned out from an orchestrator carry the bound value through continuations without the inheritance semantics of `InheritableThreadLocal`, which the JDK now explicitly discourages for virtual threads. Binding and unbinding is stack-scoped: `ScopedValue.callWhere(CURRENT_AGENT, agentId, () -> agent.run(turn))` guarantees the binding is torn down when the lambda returns, even on exceptions, so no agent ever observes a stale identity. The `InjectableContext` implementation stores per-agent bean instances in a `ConcurrentHashMap<AgentId, ContextInstances>` and evicts entries when the registry removes an agent.

Alongside `CURRENT_AGENT`, the scope carries a second binding `ScopedValue<UUID> CURRENT_TURN`. It is bound at the start of every turn using nested `ScopedValue.where(...)` builders, *before* the user message is inserted into `messages`. Every write path inside the scope that touches `messages`, `tool_invocations`, `provider_calls`, or `capr_events` reads `CURRENT_TURN.get()` and populates that row's `turn_id` column (see §4.3.1 for the contract and §4.2 V2 for the schema). `CURRENT_TURN.get()` is also the source of the `turnId` field on the `Done` and `ErrorEvent` `AgentEvent` records. Together, `CURRENT_AGENT` and `CURRENT_TURN` give every piece of per-turn state a pair of stable identifiers — agent-scope for isolation, turn-scope for structural correlation.

Inside the scope, every bean that holds per-agent state is `@AgentScoped`:

- **`Agent`** — the facade aggregating persona, tools, memory, and LLM chain for one agent id.
- **`AgentMemory`** — a SQLite-backed `ChatMemory` that writes only to this agent's rows in `messages`, `episodic_memory`, and `semantic_memory`.
- **`AgentToolBelt`** — the filtered `List<ToolSpec>` the agent is allowed to call, derived from its persona's `allowedTools` globs against the global `ToolRegistry`.
- **`AgentChatModel`** — a `FallbackChatModel` instance configured with this agent's `FallbackChain`.
- **`AgentGraph`** — the LangGraph4j `StateGraph` compiled for this agent, with its nodes bound to the scoped beans above.

### 5.2 `AgentRegistry`: runtime creation driven by files

Agents are declared in files under `~/.forvum/agents/`, not in Java. The `AgentRegistry` is an application-scoped bean that watches that directory and maintains a `ConcurrentMap<AgentId, AgentSpec>`. `spec.md` becomes the system prompt; `spec.json` becomes the structural config (allowed tools, LLM chain, memory policy, optional parent for sub-agents). Both files are required — the registry refuses to activate an agent missing either half and surfaces the reason in a `ConfigDoctor` report.

The access pattern is:

```
AgentRegistry.getOrCreate(agentId)
  → if agent exists in map, bind CURRENT_AGENT and return @AgentScoped facade
  → if not, read ~/.forvum/agents/<agentId>.md + .json, validate, register, then bind
```

Sub-agents are first-class: any agent can call `registry.spawn(parentId, childSpec)` either programmatically (for internally-generated sub-agents) or via a `SpawnAgentTool` exposed to the LLM. The child inherits its parent's `CostBudget`, `MemoryPolicy`, and `Identity` unless the spawn request overrides them. Independent top-level agents are simply agent files with no `parent` field; the registry treats them identically at runtime — the only difference is who can invoke them. Top-level agents are addressable by channel routing; sub-agents are addressable only from their parent.

File-driven creation means the user can create or rename an agent by editing `~/.forvum/agents/`, the `ConfigWatcher` fires a `ConfigurationChangedEvent`, and the registry reloads that agent on the next turn without a restart. Concurrent turns against the old spec finish with the old spec; the new spec takes effect on their successors — there is no mid-turn reconfiguration, which would violate isolation. Renames are safe: the old agent id remains reachable until its active turns drain.

### 5.3 Tool filtering and identity resolution

The global `ToolRegistry` knows every `ToolSpec` contributed by every `ToolProvider` plugin. When an agent is materialized, the registry intersects those specs against the agent's `allowedTools` glob list. The result is an immutable `List<ToolSpec>` cached on the `@AgentScoped AgentToolBelt`. The LLM only ever sees this filtered list; there is no code path that bypasses the filter to grant "just this one call" access — that kind of ad-hoc elevation is what leads to tool-misuse incidents and is explicitly forbidden by the design.

Identity resolution happens at channel-message entry. Each channel's inbound handler translates its native user id (Telegram user id, web session cookie, OS username) into a Forvum `Identity` by consulting `identities/<id>.json`. The resolved `Identity` is bound to a `ScopedValue<Identity>` for the duration of the turn, parallel to `CURRENT_AGENT`. Sub-agents inherit their parent's identity — a sub-agent does not "become" a different user, and there is no API to override identity across the spawn boundary. This is a security property, not just a convenience: tools that use `Identity` for authorization cannot be tricked by a spawned sub-agent.

### 5.4 `FallbackChatModel`: per-agent, per-cron LLM selection

`FallbackChatModel` is a `ChatModel` decorator in `forvum-engine` that wraps a `FallbackChain(primary, List<fallback>, CostBudget)`. Its `chat(request)` iterates the chain: attempt primary, classify failures, fall through on retryable faults (rate limit, timeout, 5xx), stop on non-retryable (bad credentials, policy rejection), and write one `provider_calls` row per attempted call with `is_fallback = 1` for calls past the first. The same decorator exists for `StreamingChatModel`, emitting tokens as soon as the first successful stream starts and retaining the fallback markers in telemetry.

Per-agent selection: the agent's `.json` names a primary model and a fallback list, resolved against `ModelProvider` beans at materialization time. Per-cron selection: each `crons/<cronId>.json` declares its own chain, independent of the invoked agent's default. This matters because a nightly summarization cron might want to pin a cheap local Ollama model for cost, while the same agent during interactive use fronts a premium hosted model for quality.

Classification of "retryable" is codified as a sealed `FailureClass permits Retryable, NonRetryable, Unknown` pattern — we never let "unknown" silently retry, because that has historically caused runaway spend. `Unknown` surfaces to an operator alert and is treated as non-retryable until a human classifies it. Every Langchain4j provider exception type is mapped to a `FailureClass` in a central `FailureClassifier`, and adding a new provider requires updating that classifier as part of the PR (enforced by a compile-time check on the sealed hierarchy).

### 5.5 LangGraph4j supervisor-workers graph

The Orchestrator-Workers topology from `CONTEXT-ENGINEERING.md` materializes as a `StateGraph` in `forvum-engine`. The graph is compiled once per main-agent turn and has the following nodes:

- **`route`** — a cheap local model (default `qwen3:1.7b` via Ollama) classifies the inbound message into: direct-answer, tool-loop, spawn-worker, or delegate-to-named-agent. This is the "small-and-fast" principle from `CONTEXT-ENGINEERING.md` applied to orchestration, not to final generation.
- **`generate`** — the direct-answer node; calls `AgentChatModel` and returns a final message.
- **`tool_loop`** — iterates tool calls until the LLM emits no more calls or the tool budget is exhausted. Each call goes through `ToolExecutor`, which enforces `PermissionScope` and `USER_CONFIRM_REQUIRED` hooks.
- **`spawn_worker`** — materializes a sub-agent via `AgentRegistry.spawn(parentId, spec)` with a narrowed tool belt and a child `CostBudget`. The sub-agent runs its own mini-graph inside its own `@AgentScoped` context.
- **`worker_run`** — drives the child agent's turn and returns the final message upstream.
- **`reduce`** — merges worker outputs into the main agent's context, compressing them through a small summarization pass if the combined size exceeds a threshold (the "proxy model" compression pattern).

Conditional edges route between these nodes based on the sealed `AgentEvent` type emitted at each step. A turn ends when `generate` emits a `Done` event. The graph is strictly acyclic at the top level — we do not rely on LangGraph4j's cycle support there, but we do use cycles inside `tool_loop`.

### 5.6 Explicit contrast with JavaClaw

JavaClaw's `base/src/main/java/ai/javaclaw/channels/ChannelRegistry.java` maintains a global-mutable `lastChannel` field to thread channel identity through a turn. That works for single-user, single-agent deployments and is the exact anti-pattern Forvum refuses: global mutable state is incompatible with concurrent multi-agent turns. JavaClaw's `DefaultAgent` is 23 lines of single-agent single-LLM code; we supersede it with a registry-backed multi-agent `Agent` facade under `@AgentScoped`. JavaClaw has no scratchpad, no episodic memory, no per-cron LLM, no sub-agent spawn, no identity resolution across channels. Moving from JavaClaw to Forvum is a rewrite, not a port — and this is stated here so no contributor wastes time attempting a class-by-class migration.

---

## 6. Build Targets (Fast-Jar + Native)

Forvum ships two build targets from the MVP and keeps both gated in CI. Neither is a fallback for the other: the JVM fast-jar is the canonical target for development and for deployments that need runtime jar drop-in plugins; the GraalVM native binary is the canonical target for end-user install, especially on the TUI channel where cold-start latency is felt directly by the user on every invocation.

### 6.1 JVM fast-jar

`mvn -f forvum-app package` produces `forvum-app/target/quarkus-app/` — the Quarkus fast-jar layout. Startup target is around one second on a warm laptop, resident memory around 200 MB. This target:

- Accepts runtime drop-in plugins under `~/.forvum/plugins/` discovered via `ServiceLoader`.
- Runs Dev UI at `/q/dev/` in dev mode (`mvn -f forvum-app quarkus:dev`), including Forvum-specific cards for live agent reload, a CAPR dashboard, and a provider-call inspector backed by `provider_calls`.
- Supports live-reload of Java sources during dev, which is critical when building new plugins or iterating on engine code.

This is the target a contributor runs locally while developing Forvum itself, and the target a power user runs when they want to drop in a compiled third-party plugin without rebuilding Forvum end-to-end.

### 6.2 GraalVM native binary

`mvn -f forvum-app -Pnative package` produces a single executable at `forvum-app/target/forvum-app-<version>-runner`. Startup target is under 200 ms, resident memory under 50 MB, single binary with zero JVM dependency at the end-user machine. This target:

- Is what we recommend to end users installing Forvum as a personal tool.
- Loads all plugins that were on the compile classpath at build time — not from `~/.forvum/plugins/`.
- Cross-compiles via Quarkus `container-build=true` on CI runners that lack a local GraalVM.
- Is smoke-tested on every pull request via `@QuarkusIntegrationTest`; the CI step fails if cold-start exceeds 200 ms.

The fast-jar and native targets share the same source tree and the same configuration surface. The only behavioral difference an end-user sees is the plugin-loading model described in §6.3. A user who wants a curated plugin set builds their own native binary from a fork; Forvum documents this flow explicitly so it is the obvious path rather than a surprise.

### 6.3 Native-first engineering discipline

Native compatibility is not retrofitted late. Every contribution is written as if native is the only target, and CI enforces it. Concretely:

- **No runtime reflection** outside framework-managed paths. Every DTO carries `@RegisterForReflection`, every JSON-serialized type is a record (canonical constructors are reflection-free), and every tool-specification lookup goes through a build-time-generated registry rather than class-path scanning.
- **Build-time plugin discovery.** `@ForvumExtension` plus `META-INF/forvum/plugin.json` manifests are scanned by a Quarkus `BuildStep` that records the set of contributed `ChannelProvider`, `ModelProvider`, `ToolProvider`, and `MemoryProvider` beans and emits the necessary reflection hints. `ServiceLoader` is a fallback for the fast-jar only and is not exercised in the native image.
- **No dynamic class loading** outside the drop-in plugin path, which is explicitly JVM-only.
- **Vetoed dependencies.** Any library that relies on `sun.misc.Unsafe`, runtime bytecode generation (CGLib, Javassist at runtime), or un-hinted reflection is excluded from the compile classpath via `<exclusions>` in `forvum-bom`. A CI check greps for banned imports (`sun.misc.Unsafe`, `net.sf.cglib`, `javassist.util.proxy`) and fails the build if any appear.
- **`@RegisterForReflection` audit.** A custom Maven enforcer rule walks the DTO packages and fails the build if a record is missing the annotation. Records do not technically need reflection for their canonical constructor, but Jackson and Langchain4j both use it for field access, so the annotation is mandatory and enforced.

### 6.4 CI matrix and distribution

GitHub Actions runs a matrix of `linux-amd64` and `macos-arm64` on every pull request. Each matrix cell builds both JVM and native, runs unit tests and integration tests, and executes a native smoke test that:

1. Starts the native binary with a canned `~/.forvum/` layout.
2. Sends a scripted TUI interaction through stdin.
3. Asserts the binary exits cleanly within a latency budget.
4. Fails the build if cold-start (measured from process start to first prompt rendered) exceeds 200 ms.

Release distribution:

- **GitHub Releases** — JVM jar, OCI container image tags, and platform-specific native binaries (`linux-x64`, `macos-arm64`, `macos-x64`).
- **Docker Hub** — OCI image for the JVM fast-jar (`forvum/forvum:<version>-jvm`) and an equivalent scratch-based image for the native binary (`forvum/forvum:<version>-native`).
- **Homebrew tap** — `brew install eldermoraes/forvum/forvum` from v0.5 onward.
- **Scoop bucket** — `scoop install forvum` from v0.5 onward (Windows).
- **AUR package** — community-maintained from v1.0 onward.

---

## 7. Phased Roadmap

The roadmap is organized into three phases. Phase 1 (MVP, v0.1) is broken into twenty milestones, each scoped to produce a working, testable increment that could in principle be released on its own. Phase 2 (v0.5) reaches feature parity with OpenClaw. Phase 3 (v1.0+) materializes the differentiators that the Java/Quarkus foundation makes possible.

Every Phase 1 milestone includes four subsections: **Files** (what is created or modified), **Deps** (new dependencies this milestone adds to `forvum-bom` or any module POM), **Verify** (the concrete command and expected result an operator uses to confirm the milestone is done), **Commit** (the suggested commit title — imperative mood, Conventional-Commits-friendly). Milestones are numbered `M1`..`M20` and meant to be executed in order; later milestones assume the scaffolding of earlier ones.

### 7.1 Phase 1 — MVP (v0.1)

- [ ] **M1 — Reactor bootstrap.**
  - **Files:** `forvum/pom.xml` (parent), `forvum/forvum-bom/pom.xml`, `forvum/forvum-core/pom.xml`, `forvum/forvum-sdk/pom.xml`, `forvum/forvum-engine/pom.xml`, `forvum/forvum-app/pom.xml`, `.gitignore`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`.
  - **Note:** The Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) is generated via `mvn wrapper:wrapper -Dmaven=3.9.14` and committed so contributors and CI invoke an identical Maven version — OSS convention for JVM projects and eliminates a source of "works on my machine" drift.
  - **Note:** Compiler config (release=25, encoding=UTF-8) consolidated in parent pom.xml — no `.mvn/maven-compiler.config` needed.
  - **Deps:** locks Java 25 (`maven.compiler.release=25`), Quarkus 3.31.x platform BOM, Maven 3.9+.
  - **Verify:** `cd forvum && ./mvnw -N verify` succeeds on every module; `./mvnw -pl forvum-app -am package` produces `forvum-app/target/quarkus-app/quarkus-run.jar`.
  - **Commit:** `chore: bootstrap multi-module reactor`.

- [ ] **M2 — Core domain types.**
  - **Files:** `forvum-core/src/main/java/ai/forvum/core/id/AgentId.java`, `Identity.java`, `ChannelMessage.java`, `ToolSpec.java`, `ModelRef.java`, `FallbackChain.java`, `CostBudget.java`, `MemoryPolicy.java`, plus sealed `AgentEvent permits TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent`.
  - **Deps:** none beyond core Java 25. Zero Quarkus dependency — verified by `mvn dependency:analyze`.
  - **Verify:** unit tests in `forvum-core/src/test/java/ai/forvum/core/.../` round-trip each record through Jackson; pattern-matching over `AgentEvent` compiles without a `default` branch.
  - **Commit:** `feat(core): add domain records and sealed event hierarchy`.

- [ ] **M3 — SDK contract.**
  - **Files:** `forvum-sdk/src/main/java/ai/forvum/sdk/ChannelProvider.java` (`sealed ... permits AbstractChannelProvider`), analogous files for `ModelProvider`, `ToolProvider`, `MemoryProvider`; each paired with a `non-sealed abstract AbstractXProvider`. Plus `@ForvumExtension` plugin marker, `META-INF/forvum/plugin.json` schema docs, and a re-export of `@RegisterForReflection`.
  - **Deps:** `forvum-core` only. No `quarkus-core` in compile scope.
  - **Verify:** a `forvum-sdk/src/test/java/.../SdkSurfaceTest.java` asserts via reflection that only the `Abstract*` classes are direct permits and that external classes cannot implement the sealed interface directly.
  - **Commit:** `feat(sdk): define sealed provider interfaces and plugin marker`.

- [ ] **M4 — Config loader with hot reload.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/config/ConfigLoader.java`, `ConfigWatcher.java`, `ConfigurationChangedEvent.java`, `ForvumHome.java`, plus Panache-less repository-style readers for each `~/.forvum/` subfolder.
  - **Deps:** `quarkus-core`, `quarkus-jackson`.
  - **Verify:** integration test uses `@TempDir`, writes a synthetic `~/.forvum/` layout, fires modifications, asserts `ConfigurationChangedEvent` observers receive the correct `path` and `type`.
  - **Commit:** `feat(engine): add file-based config loader with WatchService`.

- [ ] **M5 — SQLite + Flyway V1.**
  - **Files:** `forvum-engine/src/main/resources/db/migration/V1__baseline.sql` (the DDL from §4.2), `forvum-engine/src/main/resources/application.properties` (JDBC URL pointing at `$FORVUM_HOME/state/forvum.sqlite`, WAL pragma, `quarkus.flyway.migrate-at-start=true`), `forvum-engine/src/main/java/ai/forvum/engine/persistence/` Panache entities for each table.
  - **Deps:** `quarkus-hibernate-orm-panache`, `quarkus-flyway`, `org.xerial:sqlite-jdbc` (added to `forvum-bom`), `org.hibernate.orm:hibernate-community-dialects` for the SQLite dialect.
  - **Verify:** `mvn -pl forvum-engine test -Dtest=SchemaSmokeIT` migrates a fresh file, inserts one row per table, and dumps `sqlite3 forvum.sqlite '.schema'` against a golden file.
  - **Commit:** `feat(engine): add SQLite persistence with Flyway V1 baseline`.

- [ ] **M6 — `@AgentScoped` custom CDI context.**
  - **Files:** `forvum-core/src/main/java/ai/forvum/core/AgentScoped.java`, `forvum-engine/src/main/java/ai/forvum/engine/context/AgentContext.java` (`InjectableContext` impl), `AgentContextBuildItem.java`, `AgentContextProcessor.java` (the `BuildStep`), `CurrentAgent.java` (the `ScopedValue<AgentId>`).
  - **Deps:** `io.quarkus.arc:arc` (already transitive).
  - **Verify:** a dual-thread integration test binds two different `AgentId`s on two virtual threads concurrently, resolves the same `@AgentScoped` bean class on each, and asserts the two instances are distinct `System.identityHashCode`.
  - **Commit:** `feat(engine): add @AgentScoped CDI context backed by ScopedValue`.

- [ ] **M7 — `AgentRegistry` with `getOrCreate` and `spawn`.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/agent/AgentRegistry.java`, `AgentSpec.java`, `Agent.java` (the `@AgentScoped` facade), `AgentMemory.java`, `AgentToolBelt.java`, `AgentSpecReader.java` (parses `.md` + `.json`).
  - **Deps:** builds on M4 and M6.
  - **Verify:** seed `~/.forvum/agents/main.md` + `main.json`, call `registry.getOrCreate("main")` twice and assert the same `Agent` instance; call `registry.spawn("main", childSpec)` and assert a distinct child `AgentId` with a narrower tool belt.
  - **Commit:** `feat(engine): add AgentRegistry with file-driven agent creation`.

- [ ] **M8 — `FallbackChatModel` + `FailureClassifier`.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/model/FallbackChatModel.java`, `FallbackStreamingChatModel.java`, `FailureClass.java` (sealed), `FailureClassifier.java`.
  - **Deps:** `dev.langchain4j:langchain4j-core` (from `forvum-bom`).
  - **Verify:** unit test with a mock `ChatModel` that throws `RateLimitException` on the first call and returns on the second; assert `provider_calls` gets two rows and the second has `is_fallback = 1`.
  - **Commit:** `feat(engine): add FallbackChatModel decorator with failure classification`.

- [ ] **M9 — Ollama provider (first provider, local, no API key).**
  - **Files:** `forvum-provider-ollama/src/main/java/ai/forvum/provider/ollama/OllamaModelProvider.java`, `META-INF/forvum/plugin.json`.
  - **Deps:** `io.quarkiverse.langchain4j:quarkus-langchain4j-ollama`.
  - **Verify:** with a local `ollama serve` running `qwen3:1.7b`, a scripted turn through `AgentRegistry` produces a non-empty assistant message and at least one `provider_calls` row with `provider = 'ollama'`.
  - **Commit:** `feat(provider-ollama): add local Ollama provider`.

- [ ] **M10 — Anthropic provider.**
  - **Files:** `forvum-provider-anthropic/` module; `AnthropicModelProvider.java`; manifest.
  - **Deps:** `io.quarkiverse.langchain4j:quarkus-langchain4j-anthropic`.
  - **Verify:** with `ANTHROPIC_API_KEY` set, a scripted turn against `claude-opus-4-7` produces an assistant message; a second turn with a deliberately invalid key falls through `FallbackChatModel` to Ollama.
  - **Commit:** `feat(provider-anthropic): add Anthropic provider`.

- [ ] **M11 — OpenAI provider.**
  - **Files:** `forvum-provider-openai/` module; `OpenAiModelProvider.java`; manifest.
  - **Deps:** `io.quarkiverse.langchain4j:quarkus-langchain4j-openai`.
  - **Verify:** with `OPENAI_API_KEY` set, a scripted turn against `gpt-4.1-mini` produces an assistant message.
  - **Commit:** `feat(provider-openai): add OpenAI provider`.

- [ ] **M12 — Google provider.**
  - **Files:** `forvum-provider-google/` module; `GoogleModelProvider.java` wrapping Vertex AI Gemini; manifest.
  - **Deps:** `io.quarkiverse.langchain4j:quarkus-langchain4j-vertex-ai-gemini`.
  - **Verify:** with Vertex credentials set, a scripted turn against `gemini-1.5-flash` produces an assistant message.
  - **Commit:** `feat(provider-google): add Vertex AI Gemini provider`.

- [ ] **M13 — `ToolRegistry`, filtering, `PermissionScope`.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/tools/ToolRegistry.java`, `ToolExecutor.java`, `PermissionScope.java` (enum), `ToolFilter.java` (glob matching).
  - **Deps:** builds on M3 and M7.
  - **Verify:** register two synthetic tools (`a.read`, `a.write`), seed an agent with `allowedTools: ["a.read"]`, assert a call to `a.write` from that agent is refused with a `PermissionDeniedException` and logged in `tool_invocations` with `status = 'denied'`.
  - **Commit:** `feat(engine): add ToolRegistry with glob-based filtering and permission scopes`.

- [ ] **M14 — Filesystem tools.**
  - **Files:** `forvum-tools-filesystem/` module; `FilesystemToolProvider.java`; `FsReadTool.java` (`PermissionScope.FS_READ`), `FsWriteTool.java` (`FS_WRITE`), `FsListTool.java`; manifest.
  - **Deps:** none beyond `forvum-sdk`.
  - **Verify:** integration test against a `@TempDir`; read/write/list round-trip asserted; a write outside the configured workspace root is denied.
  - **Commit:** `feat(tools-fs): add filesystem read/write/list tools with FS permission scope`.

- [ ] **M15 — TUI channel (JLine 3).**
  - **Files:** `forvum-channel-tui/src/main/java/ai/forvum/channel/tui/TuiChannel.java`, `TuiStreamingRenderer.java`, a `--no-ansi` fallback path, `META-INF/native-image/.../reflect-config.json` from the JLine 3 GraalVM bundle; manifest.
  - **Deps:** `org.jline:jline` 3.x (pinned in `forvum-bom`).
  - **Verify:** an integration test pipes scripted input through the binary's stdin and asserts the rendered output contains the assistant's reply; `forvum-app -Dforvum.no-ansi=true < input.txt` works identically.
  - **Commit:** `feat(channel-tui): add JLine-based TUI channel with streaming rendering`.

- [ ] **M16 — Web channel (WebSockets Next).**
  - **Files:** `forvum-channel-web/src/main/java/ai/forvum/channel/web/WebChannel.java`, `ChatSocket.java` (WebSocket endpoint), `src/main/resources/META-INF/resources/index.html` (minimal chat UI), `chat.js`; manifest.
  - **Deps:** `quarkus-websockets-next`.
  - **Verify:** start dev mode, open `http://localhost:8080/`, exchange a message, see streamed tokens; a second browser tab gets a separate session id.
  - **Commit:** `feat(channel-web): add WebSockets Next chat channel with minimal UI`.

- [ ] **M17 — Telegram channel (long-poll).**
  - **Files:** `forvum-channel-telegram/src/main/java/ai/forvum/channel/telegram/TelegramChannel.java`, `TelegramBotApi.java` (REST client), `UpdateProcessor.java`; manifest.
  - **Deps:** `quarkus-rest-client-reactive`, `quarkus-rest-client-reactive-jackson`.
  - **Verify:** with a Telegram bot token in the keychain, a live DM produces an assistant reply within the turn latency budget; `allowedUserIds` in `channels/telegram.json` refuses other users with a friendly message.
  - **Commit:** `feat(channel-telegram): add long-poll Telegram channel`.

- [ ] **M18 — LangGraph4j supervisor graph.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/graph/SupervisorGraph.java` (the `StateGraph` compiler), node implementations for `route`, `generate`, `tool_loop`, `spawn_worker`, `worker_run`, `reduce`, `GraphState.java`.
  - **Deps:** `org.bsc.langgraph4j:langgraph4j-core` (and the Langchain4j integration module).
  - **Verify:** a multi-tool scenario ("fetch X then summarize") routes through `tool_loop` -> `generate` and produces the expected final message; CAPR event written for the turn.
  - **Commit:** `feat(engine): add LangGraph4j supervisor-workers orchestration`.

- [ ] **M19 — Quarkus-scheduler + crons.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/cron/CronScheduler.java` (registers `@Scheduled` programmatically from `~/.forvum/crons/*.json`), `CronSpec.java`, `CronTrigger.java`.
  - **Deps:** `quarkus-scheduler`.
  - **Verify:** a cron firing every minute with a `FallbackChain` pinned to Ollama triggers an agent turn and writes to `messages`, `provider_calls`, `capr_events`; adding a new cron file triggers reload without restart.
  - **Commit:** `feat(engine): add file-driven cron scheduler with per-cron LLM chain`.

- [ ] **M20 — GraalVM native image + CI matrix.**
  - **Files:** `forvum-app/src/main/resources/application.properties` (native-specific flags), `.github/workflows/ci.yml` (matrix: `linux-amd64`, `macos-arm64`; JVM and native builds; native smoke test with 200 ms cold-start gate), `Dockerfile.jvm`, `Dockerfile.native`.
  - **Deps:** `quarkus-container-image-docker`; GraalVM CE 24+ on runners.
  - **Verify:** `mvn -f forvum-app -Pnative package -Dquarkus.native.container-build=true` succeeds on a clean CI runner; `./forvum-app-<version>-runner --help` prints help in < 200 ms measured from process start.
  - **Commit:** `feat(app): add GraalVM native image profile and CI matrix`.

### 7.2 Phase 2 — v0.5 (parity with OpenClaw)

Goal: match OpenClaw's feature set so a user currently on OpenClaw can migrate to Forvum without losing capability. Each item ships as its own module or engine submodule and is gated on the MVP being stable.

1. **Browser tool.** Headless-browser `web.browse` tool (Playwright Java) with `PermissionScope.WEB_BROWSE`; delivered as `forvum-tools-browser`.
2. **Code-execution sandbox.** A `shell.exec` replacement that runs code in a container or Firecracker microVM; delivered as `forvum-tools-sandbox`.
3. **Voice channel.** Local TTS/STT (Whisper + Piper) streaming channel; delivered as `forvum-channel-voice`.
4. **Device pairing.** Pair a phone or second device to an existing Forvum instance, reusing identity and memory; delivered as `forvum-engine/pairing`.
5. **Memory-host SDK.** Public SPI for third-party `MemoryProvider` implementations (Redis, Qdrant, Chroma); documented plus reference implementation.
6. **Maven plugin marketplace.** A `forvum plugin install <coords>` command that resolves a Maven coordinate, writes it to `~/.forvum/plugins/`, and triggers a fast-jar restart. Native users are told to rebuild.
7. **Skill marketplace.** A `forvum skill install <url>` that adds a `skills/<skill>.md` from a git repo or gist.
8. **Session replay.** A CLI command that replays a session from `messages` with the original tool outputs, used for debugging and regression.
9. **Config doctor.** `forvum doctor` validates the entire `~/.forvum/` layout against JSON Schemas and surfaces problems with actionable hints.
10. **Provider onboarding wizard.** `forvum provider add anthropic` walks the user through keychain entry, default fallback-chain update, and a smoke-test turn.
11. **RBAC on tools.** `PermissionScope` extended to role-based sets; `identities/<id>.json` declares roles; cron jobs get a distinguished `cron` role.
12. **Structured output schemas per agent.** Agents declare an output JSON Schema in `.json`; `AgentGraph` decodes the final message against it via Langchain4j's structured-output support.
13. **MCP server registry enrichments.** `forvum mcp add <url>` and `forvum mcp list`; remote MCP tools appear in `ToolRegistry` within seconds.
14. **User-approval queue UI.** Dev UI and web-channel cards that show pending `USER_CONFIRM_REQUIRED` tool calls and let the user approve or reject them.
15. **Telemetry export.** OpenTelemetry OTLP exporter on by default when `OTEL_EXPORTER_OTLP_ENDPOINT` is set; default off; zero-config path for Honeycomb, Grafana Tempo, and Datadog.

### 7.3 Phase 3 — v1.0+ (differentiators)

Each item is a bet that the Java/Quarkus foundation either strictly enables or makes materially cheaper than in OpenClaw's TypeScript/Node runtime.

1. **Single-binary install as the headline UX.** `curl | sh` drops a ~40 MB native binary; no runtime, no Docker, no Node.
2. **Queryable semantic memory.** `forvum memory query 'SELECT agent_id, key, value FROM semantic_memory WHERE ...'` is a first-class operator surface, backed by SQLite + `sqlite-vec`. No export to an external vector DB required.
3. **LangGraph4j cyclic agents as a first-class primitive.** Agents declare cycles in their `.json` (e.g., `cycle: reflect → critique → revise`) and the engine compiles them into the `StateGraph` without custom code.
4. **CAPR-driven adaptive model routing.** The `LlmSelector` consults rolling CAPR per-model over the last N turns and down-ranks models with sagging pass rates automatically. The router is itself a small local model that sees the CAPR snapshot at every decision.
5. **Multi-user toggle.** Flipping `multiUser: true` in `config.json` turns on per-user `$FORVUM_HOME` isolation, identity-scoped SQLite schemas, and a shared-memory namespace for team skills — the same binary serves both modes.
6. **Dev UI live-edit of configs.** Dev UI cards edit `~/.forvum/` files with JSON Schema validation and hot-reload preview, so a developer never hand-edits JSON for the golden path.
7. **Kubernetes-native team-assistant mode.** A Helm chart and a Quarkus Kubernetes-client operator deploy Forvum as a team assistant with per-namespace memory isolation.
8. **Proxy-model compression middleware.** A Sentinel-style compression layer sits between retrievers and the generator model, using a tiny local model (e.g., Ollama `qwen3:1.7b`) to score and prune chunks. Materializes the "proxy model" pattern from `CONTEXT-ENGINEERING.md` directly.
9. **Queryable session replay via SQL.** Every session is replayable with any substitution (new model, new tool outputs, new memory policy) because the schema captures everything — this is not possible in OpenClaw's JSONL store without custom parsing.
10. **First-party evaluation harness with CAPR gating.** A `forvum eval <suite>` command runs a benchmark suite, enforces a CAPR floor, and fails the release if performance regressed — CI-enforced quality gate on par with code coverage.

---

## 8. Risks and Open Decisions

Each item below is either a technical risk to validate early or a decision deferred pending evidence. Every entry includes **Context** (what the concern is), **Mitigation** (how we reduce blast radius ahead of a decision), and **Decision trigger** (what must be true for us to resolve it one way or the other).

1. **Quarkus ArC `InjectableContext` + Java 25 `ScopedValue` interoperability in native image.**
   - **Context:** The custom `@AgentScoped` context is implemented via ArC SPI; ScopedValue is a Java 25 preview feature that may require specific native-image flags.
   - **Mitigation:** A spike during M6 validates the full path (bind `ScopedValue`, inject `@AgentScoped` bean, unbind) both in JVM and in a native image. If native fails, we fall back to `ThreadLocal` inside a virtual-thread-pinning guard and accept the performance cost.
   - **Decision trigger:** M6 CI green on both JVM and native; a two-thread test asserts isolation. If red, we file a Quarkus issue and ship the `ThreadLocal` variant in v0.1.

2. **`sqlite-vec` native-image compatibility.**
   - **Context:** `sqlite-vec` is a C extension loaded as a shared library. Native-image static linking varies by platform.
   - **Mitigation:** v0.1 uses linear scan; `sqlite-vec` is a v1.0+ dependency only. This keeps the MVP decoupled from the risk.
   - **Decision trigger:** at v1.0+ scoping, benchmark linear scan at realistic row counts (10k, 100k, 1M). If linear is acceptable at 100k, defer indefinitely.

3. **Sealed interfaces and CDI bean discovery.**
   - **Context:** ArC's bean discovery historically matches concrete classes; sealed hierarchies with `non-sealed` leaves are unusual.
   - **Mitigation:** Plugins implement the `non-sealed abstract AbstractXProvider`, which is a concrete class from ArC's perspective. M3 includes a compile-time test that asserts this contract.
   - **Decision trigger:** M3 passes on native; if ArC emits warnings about sealed interfaces we investigate before M7.

4. **LangGraph4j maturity and version stability.**
   - **Context:** LangGraph4j is pre-1.0 at time of writing; API changes between minor versions are possible.
   - **Mitigation:** Pin the exact version in `forvum-bom`. Keep the engine's coupling to LangGraph4j concentrated in `forvum-engine/src/main/java/ai/forvum/engine/graph/` so an upgrade or a replacement is a module-local change.
   - **Decision trigger:** if LangGraph4j breaks API twice within a v0.1 → v0.5 cycle, evaluate replacing it with a small in-house `StateGraph` implementation on the same `AgentEvent` sealed type.

5. **Quarkiverse `quarkus-langchain4j-*` native readiness per provider.**
   - **Context:** Extensions vary in their native-image readiness; Ollama and OpenAI are well-exercised; Vertex AI Gemini less so.
   - **Mitigation:** Native smoke test runs every provider against a canned scripted turn in CI. If a provider fails native, it is still available in the fast-jar build and clearly marked as such in docs.
   - **Decision trigger:** provider smoke test red in native for two weeks → file upstream issue, mark the provider JVM-only in release notes, unblock other work.

6. **JLine 3 on Windows under GraalVM.**
   - **Context:** JLine 3 has edge cases on Windows consoles (especially legacy cmd.exe) that differ from macOS/Linux.
   - **Mitigation:** The `--no-ansi` fallback is a first-class mode from M15, not a retrofit. Windows CI job runs the TUI smoke test in both ANSI and no-ANSI modes.
   - **Decision trigger:** Windows CI red → document no-ANSI as the default on Windows in v0.1, investigate fully in v0.5.

7. **`WatchService` platform variance.**
   - **Context:** macOS uses polling under the hood (`WatchService` is not native there), Linux uses `inotify`, Windows uses `ReadDirectoryChangesW`. Debounce and ordering differ.
   - **Mitigation:** `ConfigWatcher` debounces changes with a 250 ms window and coalesces multiple events on the same file. A platform-matrix test in M4 exercises each OS.
   - **Decision trigger:** user reports of stale reloads → evaluate a library like `jnotify` or an in-process periodic diff against a hash snapshot.

8. **Telegram long-poll reliability vs webhook.**
   - **Context:** Long-poll is simpler to run on a laptop but burns persistent connections; webhook requires a public URL and TLS.
   - **Mitigation:** Ship long-poll as the default in M17; document webhook as an opt-in for users who already have a public endpoint. Both modes share the same `UpdateProcessor`.
   - **Decision trigger:** a user request for webhook-only deployment (e.g., hosting on a serverless platform) promotes webhook to a first-class equal.

9. **MCP client native support under Quarkus.**
   - **Context:** The Quarkiverse MCP client is relatively new; stdio MCP servers spawn subprocesses, which native images handle but with startup nuances.
   - **Mitigation:** Ship `forvum-tools-mcp-bridge` in v0.1 gated behind a feature flag off by default; exercise it against a local `mcp-server-filesystem` before enabling by default in v0.5.
   - **Decision trigger:** stdio MCP smoke test passes on all three platforms in native → flip the flag on by default.

10. **CAPR judge-model cost and latency at scale.**
    - **Context:** Running a judge model on every turn doubles LLM calls and cost.
    - **Mitigation:** Judge is off by default in production; enabled in dev and in scheduled evaluation runs. When enabled, the default judge is a cheap local Ollama model, not a hosted one.
    - **Decision trigger:** the evaluation harness in v1.0+ measures judge-vs-human agreement; if agreement falls below 0.7 on our suite, we either replace the judge model or invest in calibration.

11. **JDBC/SQLite pinning under virtual threads.**
    - **Context:** Xerial SQLite JDBC uses `synchronized` JNI native methods, which pin virtual threads. The engine runs on virtual threads end-to-end (§3.8); without mitigation, every Hibernate transaction blocks a carrier thread.
    - **Mitigation:** M5 chooses among (a) a managed platform-thread executor for transactions, (b) explicit `@Blocking` posture on Hibernate-bound code paths, or (c) a loom-friendly JDBC driver if one becomes available. The selection is recorded back into §3.8 once locked.
    - **Decision trigger:** an M5 spike measures pin events under steady-state load (a synthetic 100-turn run hitting `messages` and `provider_calls`); the option that produces zero unbounded pins wins. If all three options have unbounded pin events, ship the least-bad option in v0.1 and file an issue capping the regression.

12. **Mutiny ↔ virtual-thread boundary in the Telegram channel.**
    - **Context:** `forvum-channel-telegram` (M17) uses `quarkus-rest-client-reactive`, which is Mutiny-based. The rest of the engine — including all `@AgentScoped` work — runs on virtual threads with no Mutiny exposure. Without an explicit seam, Mutiny types could leak into engine code and create dual-paradigm code paths.
    - **Mitigation:** `UpdateProcessor` (M17) is the seam: it consumes the Mutiny `Uni<Update>` stream from the REST client, `await().indefinitely()`s within a virtual-thread scope, and hands `ChannelMessage` records (only `forvum-core` / `forvum-sdk` types) to the engine. The boundary is enforced at build time via the `maven-enforcer-plugin` `bannedDependencies` rule in `forvum-engine/pom.xml`, banning `io.smallrye.mutiny:*` from the compile classpath.
    - **Decision trigger:** the `bannedDependencies` enforcement step in M17's CI build fails if any engine code introduces a Mutiny type. If the seam leaks, redesign `UpdateProcessor` before M17 ships.

---

## End-to-End Verification

The MVP is "done" when all twenty Phase 1 milestones are green and the following end-to-end script passes on both fast-jar and native targets on both `linux-amd64` and `macos-arm64`:

1. **Cold install.** Download the native binary from GitHub Releases, `chmod +x`, run `./forvum --help`. Expected: help text printed in < 200 ms measured from process start.
2. **First-run initialization.** Run `./forvum init` with no `~/.forvum/` present. Expected: directory scaffolded with an example `agents/main.md`, `main.json`, `identities/default.json`, `channels/tui.json`.
3. **TUI golden path.** Run `./forvum tui`; type "hi"; expected: a streamed assistant reply within the turn latency budget; `state/forvum.sqlite` contains one session and at least two messages.
4. **Per-agent LLM selection.** Edit `agents/main.json` to pin `primary = ollama:qwen3:1.7b` and a single fallback `anthropic:claude-haiku-4-5`. Kill the local Ollama process; run `./forvum tui` and ask a question. Expected: the turn completes via the Anthropic fallback; `provider_calls` has two rows, the second with `is_fallback = 1`.
5. **Sub-agent spawn.** Ask the main agent to "use a researcher sub-agent to find X". Expected: a new `researcher` agent is spawned via `AgentRegistry.spawn`, runs its own turn, returns; the trace contains `forvum.graph.node{name=spawn_worker}` → `forvum.graph.node{name=worker_run}` spans.
6. **Web channel parity.** Start the web channel, open `http://localhost:8080/`, send the same message. Expected: the same assistant reply; a new row in `sessions` distinct from the TUI session.
7. **Telegram channel parity.** Configure a bot token, DM the bot from an allowed user id. Expected: reply within the turn latency budget; denied for a disallowed user id.
8. **Cron run.** Add `crons/daily-brief.json` firing every minute with `primary = ollama:llama3.2:1b`. Wait 90 seconds. Expected: `messages` contains an assistant message from the cron; `provider_calls.provider = 'ollama'`.
9. **Hot reload without restart.** Edit `agents/main.md` while the process runs; send a new message. Expected: the new system prompt is in effect; prior turns finished with the old prompt.
10. **CAPR dashboard.** Enable judge mode, run five turns, visit `/q/dashboard/capr`. Expected: the endpoint returns a JSON summary with at least five entries in `capr_events`.

Each of these ten scenarios is also an integration test under `forvum-app/src/test/java/ai/forvum/e2e/` gated in CI, so a regression on any of them fails the build before merge.

---

## Critical Files

The table below lists the files a new contributor should read first to orient themselves, ranked by architectural importance. Reading these ten files end-to-end is sufficient to understand Forvum's shape without exploring the whole tree.

| File | Responsibility |
|------|----------------|
| `forvum-core/src/main/java/ai/forvum/core/id/AgentId.java` (and siblings) | The domain records that flow through every boundary. |
| `forvum-sdk/src/main/java/ai/forvum/sdk/ChannelProvider.java` | The sealed-interface SDK contract that plugins implement. |
| `forvum-engine/src/main/java/ai/forvum/engine/context/AgentContext.java` | The `InjectableContext` implementation backing `@AgentScoped`. |
| `forvum-engine/src/main/java/ai/forvum/engine/agent/AgentRegistry.java` | File-driven agent creation and sub-agent spawn. |
| `forvum-engine/src/main/java/ai/forvum/engine/model/FallbackChatModel.java` | Per-agent and per-cron LLM chain semantics. |
| `forvum-engine/src/main/java/ai/forvum/engine/graph/SupervisorGraph.java` | LangGraph4j orchestration. |
| `forvum-engine/src/main/java/ai/forvum/engine/config/ConfigLoader.java` | Hot-reloadable config surface. |
| `forvum-engine/src/main/resources/db/migration/V1__baseline.sql` | The SQLite schema. |
| `forvum-app/src/main/resources/application.properties` | Runtime configuration, native-image flags. |
| `.github/workflows/ci.yml` | CI matrix, native smoke test, quality gates. |

Once these ten files compile and their associated milestones pass, Forvum has a defensible shape; everything else — new channels, providers, tools, skills — is a pattern repeat against the `forvum-sdk` surface.

---

## Post-approval follow-ups

These three items must happen after `ExitPlanMode` is approved. They are recorded here so the plan is self-contained and recoverable from the repository alone.

1. **Rename the plan file.** `mv ~/.claude/plans/voc-vai-fazer-peppy-peach.md ~/.claude/plans/forvum-ultraplan.md`. The auto-generated slug is unrecoverable; a human-readable filename lets future sessions (and future contributors) find the plan by name.

2. **Copy the plan into the repository.** `cp ~/.claude/plans/forvum-ultraplan.md forvum/docs/ULTRAPLAN.md` (creating `forvum/docs/` if missing). The plan then ships with the source tree, is versioned by git, and anyone who clones the repo reads it at the same revision as the code it describes.

3. **Register the English-only project-artifact preference as persistent memory.** Forvum is open-source and must welcome international collaboration; English is the canonical language for every artifact inside the repository — source code, identifiers, JavaDoc and inline comments, commit messages, PR descriptions, issue text, documentation files, config file keys, log messages, error strings, file and directory names. User-facing localized strings inside the application's own localization surface may be in other languages, but all source strings default to English and collaboration language on GitHub (commits, issues, PRs, reviews) is English. This preference is saved as a `feedback`-type memory so future sessions apply it without being told again.
