# Forvum — AI Personal Assistant Ultraplan

> **For agentic workers:** REQUIRED SUB-SKILL — when executing this plan, use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans`. Phase 1 milestones use checkbox (`- [ ]`) syntax. Implementation language is English throughout (code identifiers, comments, commit messages, docs, config keys). User-facing strings that are part of the app's localization surface may be localized, but source strings default to English.

**Goal:** Ship Forvum — a local-first, open-source personal AI assistant in Java 25 / Quarkus 3.33.x LTS / LangChain4j 1.16.1 (via quarkus-langchain4j 1.11.0.CR2) / LangGraph4j 1.8.17 — that first reaches, then surpasses, OpenClaw on features and engineering quality.

**Architecture:** Maven multi-module, CDI-first with a custom `@AgentScoped` context for in-process sub-agent isolation. The core stays extension-agnostic; every channel, provider, and tool is a module that implements a sealed-interface SDK contract. Hybrid persistence — human-editable markdown and JSON files under `~/.forvum/` for intent, embedded SQLite for operational state, memory, and metrics. GraalVM native is the primary, mandatory build target — every milestone is native-buildable and native parity is enforced in CI on every PR; the JVM fast-jar is the development target and the only path that loads runtime drop-in plugins.

**Tech Stack:** Java 25 · Maven · Quarkus 3.33.x LTS · Quarkiverse `quarkus-langchain4j-*` 1.11.0.CR2 · LangChain4j 1.16.1 (transitive via quarkus-langchain4j 1.11.0.CR2) · LangGraph4j 1.8.17 · Xerial SQLite JDBC · Hibernate ORM + Panache + Flyway · TamboUI 0.3.0 (Toolkit on the JLine 3 backend) · Quarkus WebSockets Next · Quarkus Scheduler · OpenTelemetry · GraalVM for JDK 25 (Community Edition 25+); native builds use Mandrel 25.0.x-Final as the Quarkus-preferred `native-image` distribution · JaCoCo · GitHub Actions.

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
- **A shared SSRF egress policy on every outbound HTTP surface.** Like OpenClaw, every tool or provider that makes outbound HTTP requests passes through one egress policy that blocks private and loopback ranges by default, with `strict` and `allowPrivateNetwork` modes; in Forvum this is a `forvum-engine` HTTP-client decorator plus a `PermissionScope`-aware policy bean, so no extension reaches the network outside it.
- **A broad bundled channel/provider catalog as an architectural commitment, not a frozen list.** OpenClaw ships a wide channel/provider surface; we replicate the extensible architecture and ship a curated first-party set (§2.4), with the long tail delivered as community plugins through the Maven-coordinate marketplace (§7.2).

### 1.2 What we improve

OpenClaw's design has friction points rooted in its TypeScript/Node origins. A Java 25 / Quarkus foundation lets us address them directly.

- **Queryable operational state.** OpenClaw stores sessions as raw JSONL. We use SQLite, so `SELECT * FROM semantic_memory WHERE agent_id = ?` is a first-class debug tool and CAPR / token / latency metrics are straightforward aggregate queries.
- **Consolidated SDK surface.** OpenClaw exports 100+ subpaths from its plugin SDK — discoverable only through docs. Our sealed interfaces are fully enumerable by an IDE: `permits ChannelProvider, ModelProvider, ToolProvider, MemoryProvider` tells a new contributor everything they can implement.
- **Build-time DI instead of hand-rolled lazy loading.** OpenClaw's `*.runtime.ts` split exists to keep cold paths light. Quarkus' build-time CDI gives this for free — each channel, provider, and tool is a build-step-discovered bean with framework-emitted reflection hints, fully native-compatible.
- **Immutable request-scoped handles.** OpenClaw's plugin registry is global-mutable and documented as a "transitional compatibility surface." Forvum uses immutable handles derived per request from the `AgentRegistry`, eliminating an entire class of cross-request pollution bugs.
- **First-class per-agent and per-cron LLM selection with fallback.** OpenClaw can swap providers but fallback chains are ad hoc. Forvum makes `FallbackChain(primary, fallbacks)` (§4.3.5.3) a core type and `FallbackChatModel` a library-wide decorator; every agent and every cron job carries its own chain, and fallback events land in `provider_calls` with `is_fallback = 1` for later analysis.
- **A queryable task ledger as a day-one primitive.** OpenClaw only recently unified its background bookkeeping over a JSONL-era surface. Forvum makes a SQLite `tasks` ledger and a `TaskExecutor` SPI first-class from the MVP, so cron, sub-agent, and background runs are recorded in one queryable place rather than reconstructed from logs.

### 1.3 Phased objectives

- **MVP (v0.1).** Three channels (TUI, Web UI, Telegram). A main agent plus spawnable sub-agents and independent agents. Per-agent and per-cron LLM selection with fallback. Runtime-configurable via `.md` / `.json` without recompiling. User recognition across channels. Agent identity and persona definition. The GraalVM native binary is the primary CI artifact — every milestone is native-buildable and native parity is enforced on every PR (§6).
- **v0.5 (parity).** Feature parity with OpenClaw v2026.4.19-beta.2 — the authoritative parity set is enumerated in §7.2 (a single source of truth, so this objective never drifts from the roadmap).
- **v1.0+ (leap).** Differentiators unlocked by the clean Java foundation: single-binary native install, queryable semantic memory, LangGraph4j cyclic agents as a first-class primitive, CAPR-driven adaptive model routing, multi-user by toggle, Dev UI live-edit of configs, Kubernetes-native team-assistant mode, proxy-model compression middleware.

### 1.4 Guiding principles (from `CONTEXT-ENGINEERING.md`)

- **Context Engineering is a structural property, not a slogan.** The four pillars from `CONTEXT-ENGINEERING.md` — **Write** (governing reasoning state in scratchpad and memory tiers), **Select** (high-precision filtering of what enters the window), **Compress** (summarizing oversized content at write-back), and **Isolate** (hermetic per-agent state separation) — each have a named owning module; the pillar-to-module map is §2.7 and this section is no longer their sole home.
- **Orchestrator-Workers hub-and-spoke** is the default multi-agent topology; spawned workers run in parallel so the topology is also the latency mechanism (§5.5).
- **Small-and-fast models** (for example a local Ollama `qwen3:1.7b`) handle routing, intent classification, and metadata-extraction sub-steps to minimize end-to-end latency.
- **Strict per-agent state isolation** prevents context clash — every agent runs inside its own `@AgentScoped` context with its own memory, tool subset, and system prompt.
- **Observability with CAPR** (Cost-Aware Pass Rate) sits alongside token counts and latency as a first-class metric from the MVP onward.
- **Governance from day one.** Every tool carries a `PermissionScope`, user-approval hooks gate destructive actions, and outbound outputs are filtered for sensitive data — this principle is upgraded from promise to contract in §9: the threat model (§9.1, STRIDE by surface) and the `OutputFilter` contract (§9.2, realized as the `OutputGuard` SPI in §7.2 item 23) give it a named home, a sealed `FilteringOutcome` disposition, and a pre-channel-emit enforcement point rather than living only here.

---

## 2. Modules & Bounded Contexts

The project is a Maven multi-module reactor under `groupId = ai.forvum`, organized in four layers that also map to bounded contexts. The layering enforces the "core stays extension-agnostic" rule at the build level: `forvum-engine` has zero compile dependencies on any concrete channel or provider module, so accidentally hardcoding a bundled extension ID is not possible.

### 2.1 Layer 0 — Foundation (no Quarkus)

- **`forvum-parent`** — the root reactor `pom`. Declares `<packaging>pom</packaging>`, the Java 25 compiler arguments, the binding of `quarkus-maven-plugin` and `jacoco-maven-plugin`, and imports `forvum-bom`.
- **`forvum-bom`** — a `<dependencyManagement>`-only module that is the single version bump point. It imports `quarkus-bom` (Quarkus 3.33.x LTS) and `io.quarkiverse.langchain4j:quarkus-langchain4j-bom:1.11.0.CR2` (a pre-release / Candidate Release, which transitively governs LangChain4j core 1.16.1; stable fallback `:1.10.0` governs 1.14.1), and pins `org.bsc.langgraph4j:langgraph4j-core:1.8.17` and `org.xerial:sqlite-jdbc` (≥ 3.40.1.0, the first native-image-capable release). Quarkus-managed dependencies (Flyway via `quarkus-flyway`, OpenTelemetry via `quarkus-opentelemetry`) are governed by the Quarkus BOM and are not pinned independently; the TUI stack is governed by `dev.tamboui:tamboui-bom:0.3.0` (which manages `tamboui-toolkit` and the `tamboui-jline3-backend`, and transitively the JLine 3 version), and the test libraries are pinned here. LangChain4j core is never pinned independently of the `quarkus-langchain4j-bom`. Every downstream module imports this BOM, so there is exactly one place to bump a version. See the §3.9 version table.
- **`forvum-core`** — pure Java domain. Records and sealed interfaces for `AgentId`, `Identity`, `Persona`, `ChannelMessage`, `ToolSpec`, `ModelRef`, `AgentEvent`, `FallbackChain`, `CostBudget`, and `MemoryPolicy`. No Quarkus dependency at all, so tests and prototypes outside the container can depend on these types directly.

### 2.2 Layer 1 — Public SDK (the only extension contract)

- **`forvum-sdk`** — sealed interfaces that plugins implement: `ChannelProvider`, `ModelProvider`, `ToolProvider`, `MemoryProvider`. Each one permits a `non-sealed abstract` base (`AbstractChannelProvider`, and so on) that third parties extend, which is how we reconcile sealed hierarchies with open extension. `MemoryProvider` is the Select-pillar SPI: implementations choose vector, graph, metadata, or hybrid retrieval so that only ultra-relevant memory reaches the prompt window, without coupling the agent to a retrieval strategy. Also houses the `@ForvumExtension` plugin marker annotation and a re-export of `@RegisterForReflection` so plugin authors do not need to pull in `quarkus-core` directly. This module is the one and only artifact a third-party plugin compiles against.

### 2.3 Layer 2 — Engine (Quarkus application code, extension-agnostic)

- **`forvum-engine`** — the heart of the application. Contains the `AgentRegistry`, the custom `@AgentScoped` CDI context (implemented via Quarkus ArC's `InjectableContext` SPI so it works in native mode), the LangGraph4j orchestrator, the `ConfigLoader` with `WatchService`-backed hot reload, the SQLite persistence layer (Hibernate ORM + Panache + Flyway), the `LlmSelector` and `FallbackChatModel` decorator, the MCP-client bridge, OpenTelemetry wiring, and the Dev UI cards. Its compile dependencies are limited to `forvum-core` and `forvum-sdk`; never any concrete channel, provider, or tool.

### 2.4 Layer 3 — First-party extensions

All first-party extensions depend only on `forvum-sdk`. They are separate Maven modules so an end-user assembling a slimmer build can drop any of them by editing `forvum-app/pom.xml`.

**Channels**

- **`forvum-channel-tui`** — interactive REPL built with the **TamboUI Toolkit** (declarative widgets + TCSS styling) on the `tamboui-jline3-backend`; streams tokens into a TamboUI view; fallback `--no-ansi` plain-stdin mode; GraalVM reflection/reachability hints (TamboUI + the JLine backend) bundled. The dependency-light `tamboui-panama-backend` (Java FFM, no external library) is the native-first alternative evaluated at M15. *(As built, M15: v0.1 ships a line-based stdin REPL rendering through `tamboui-widgets`' headless `Buffer` with NO terminal backend; the jline3/panama backends + full-screen Toolkit/TCSS are deferred pending a GraalVM-25-native-buildable backend — see the M15 Decision block.)*
- **`forvum-channel-web`** — Quarkus WebSockets Next server; a minimal static HTML/JS bundle served from classpath resources; streaming via server-pushed WS frames.
- **`forvum-channel-telegram`** — long-poll bot (webhook available as an opt-in alternative); uses a blocking REST client called from a self-started long-poll loop on an explicit virtual-thread executor (§3.8; `@RunOnVirtualThread` is for externally-invoked inbound handlers) per the virtual-threads-first principle (§3.8).

**Providers**

- **`forvum-provider-anthropic`** — wraps `quarkus-langchain4j-anthropic` and exposes a `ModelProvider` SPI bean.
- **`forvum-provider-openai`** — wraps `quarkus-langchain4j-openai`.
- **`forvum-provider-ollama`** — wraps `quarkus-langchain4j-ollama`.
- **`forvum-provider-google`** — wraps `quarkus-langchain4j-vertex-ai-gemini`. If the Vertex gRPC/protobuf and Google-auth transitive stack blocks the native build, this module switches to the REST `quarkus-langchain4j-ai-gemini` (Google GenAI) extension, which avoids that stack and is the native-first alternative; switching extensions is preferred over a JVM-only carve-out (see §8 Risk #5).

**Tools**

- **`forvum-tools-filesystem`** — `fs.read`, `fs.write`, `fs.list`, guarded by `PermissionScope.FS_READ` / `FS_WRITE`.
- **`forvum-tools-web`** — `web.fetch`, `web.search`, with a pluggable search backend.
- **`forvum-tools-shell`** — `shell.exec` behind an allow-list plus a `USER_CONFIRM_REQUIRED` approval hook. Owned by M13 acceptance (X7).
- **`forvum-tools-mcp-bridge`** — dynamic MCP client; reads `~/.forvum/mcp-servers/*.json` and surfaces remote MCP tools as native `ToolSpec` instances that any agent's `allowedTools` list can reference. Shipped flagged-OFF in v0.1 (Risk #9); baseline owned by M13 acceptance (X7).

### 2.5 Layer 4 — Assembly

- **`forvum-app`** — the only module that produces runnable artifacts. Depends on `forvum-engine` plus every first-party channel, provider, and tool module. The `quarkus-maven-plugin` binding here produces the GraalVM native binary under the `-Pnative` profile (the primary, mandatory release target) and the JVM fast-jar (`mvn package`) as the development target and the only artifact that loads runtime drop-in plugins from `~/.forvum/plugins/`. A single `main()`.

### 2.6 Bounded contexts

The Maven split also delimits bounded contexts for contribution purposes: **Config Management**, **Identity & Persona**, **Agent Runtime**, **Conversation & Memory**, **Tool Execution**, **Model Routing**, **Channel I/O**, and **Observability**. Each maps to either a module or a cohesive sub-package within `forvum-engine` (for example `forvum-engine/src/main/java/ai/forvum/engine/routing/` for Model Routing), so an external contributor can focus on one bounded context without touching anything else.

### 2.7 Context Engineering pillar ownership

The module split is also the Context Engineering split. Each pillar from `CONTEXT-ENGINEERING.md` has a structural owner, so "where does Compress live?" has a one-module answer:

| Pillar / principle | Owning module(s) / package | Realized by |
|---|---|---|
| **Write** | `forvum-engine/persistence` + `~/.forvum/` files | SQLite `messages`/`episodic_memory`/`semantic_memory` tiers (§4.2); files as the user-editable Write surface (§4.1) |
| **Select** | `forvum-sdk` (`MemoryProvider`) + `forvum-engine/tools` (`AgentToolBelt`) + `forvum-engine/routing` | retrieval-strategy SPI (§2.2); tool filtering (§5.3); `route` node (§5.5) |
| **Compress** | `forvum-engine/graph` (`reduce`) + `forvum-core.budget` + `forvum-core` enums | `reduce` summarization (§5.5); `Usage` one-trip snapshot (§4.3.5.2); SQL-mirror enums (§4.3.3); proxy middleware v1.0+ (§7.3-8) |
| **Isolate** | `forvum-engine/context` (`@AgentScoped`) | custom `InjectableContext` + `ScopedValue` (§5.1) |
| **Orchestrator-Workers** | `forvum-engine/graph` (`SupervisorGraph`) | LangGraph4j `StateGraph` spawn/worker fan-out (§5.5) |
| **Small-and-fast routing** | `forvum-engine/routing` + `forvum-engine/model` | `route` node + `FallbackChain` escalation ladder (§5.4, §5.5) |
| **Governance day one** | `forvum-core` (`PermissionScope`) + `forvum-engine/tools` (`ToolExecutor`) | scope enforcement, approval hooks, output guard (§4.3.4, §5.3, §7.2) |
| **Observability / CAPR** | `forvum-engine/observability` + `capr_events` schema | OTel spans + CAPR (§3.6, §4.2) |

The §2.6 bounded contexts map 1:1 onto these pillars — **Conversation & Memory** = Write+Select, **Tool Execution** = Select+Governance, **Model Routing** = Select+small-and-fast, **Agent Runtime** = Isolate+Orchestrator-Workers, **Observability** = CAPR — so a contributor who owns a bounded context owns a Context Engineering pillar.

---

## 3. Stack by Layer

### 3.1 Language runtime

Java 25 LTS. We use its modern surface deliberately:

- **Scoped Values** (JEP 506, final in Java 25 — not a preview feature) carry the current `AgentId` across virtual-thread continuations without the leakage issues of `ThreadLocal`. The custom `@AgentScoped` CDI context is backed by a `ScopedValue<AgentId>` set at request entry through `ScopedValue.where(CURRENT_AGENT, id).call(body)` (and `.run(body)` for void work); no `--enable-preview` flag is required.
- **Sealed interfaces** on every extension point (`ChannelProvider`, `ModelProvider`, `ToolProvider`, `MemoryProvider`) and on the event hierarchy (`AgentEvent permits TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent`). This gives exhaustive pattern matching in channels and compiler-enforced completeness in event handlers.
- **Records** for every DTO and value object. Records also minimize the GraalVM reflection surface, since each record has a documented canonical constructor.
- **Pattern matching for switch** over sealed types in channel, router, and event-handling code paths, replacing visitor boilerplate.
- **Virtual threads** as the default carrier for per-request work, so fanning out to sub-agents and tool calls is cheap and debuggable.

### 3.2 Application framework

Quarkus 3.33.x LTS. Provides build-time CDI (Arc), native OpenTelemetry integration, WebSockets Next, `quarkus-scheduler` for cron-expression jobs, `quarkus-rest-client` (blocking, on virtual threads) for Telegram, and the Dev UI as an admin and debugging surface during development. We deliberately avoid Mutiny / reactive streams as a programming model (virtual threads first, §3.8) — virtual threads plus `Flow.Publisher` cover our concurrency needs with simpler stack traces and easier debugging; any reactive use is confined to a justified framework boundary and bridged to a virtual thread.

### 3.3 AI layer

- **Quarkiverse `quarkus-langchain4j-*` extensions** (1.11.0.CR2, aligned to Quarkus 3.33.2), one per provider (`anthropic`, `openai`, `ollama`, `vertex-ai-gemini`). They expose `ChatModel` and `StreamingChatModel` as CDI beans, provide declarative `@RegisterAiService` tool-calling when we want it, and supply the MCP client through the Quarkiverse `quarkus-langchain4j-mcp` extension — build-time wired and native-ready (this is the extension we use, not the standalone `dev.langchain4j:langchain4j-mcp` artifact, which is still published `-beta`). For Google, the REST `quarkus-langchain4j-ai-gemini` (Google GenAI) extension is the native-first alternative to `vertex-ai-gemini` and is adopted if the Vertex gRPC stack blocks native (see §2.4, §8 Risk #5).
- **LangChain4j 1.16.x core** (governed transitively by `quarkus-langchain4j-bom:1.11.0.CR2`, not pinned independently). Supplies the `ChatMemory` contract (we implement a SQLite-backed variant over the `messages` table), the `ToolSpecification` / `ToolExecutor` surface (including the `ToolArgumentsErrorHandler` / `ToolExecutionErrorHandler` hooks used by the tool loop in §5.5), structured-output decoding via `@Description` and `@StructuredPrompt`, the centralized `dev.langchain4j.exception` typed hierarchy the `FailureClassifier` maps (§5.4), and the generic `ChatModel` / `StreamingChatModel` interfaces that our `FallbackChatModel` decorates.
- **LangGraph4j 1.8.17** (stable 1.8.x series; Java 17+). Supplies the `StateGraph` abstraction — cyclic, state-oriented graphs of `BiFunction<State, RunContext, NodeResult>` nodes with conditional edges. It integrates with LangChain4j via `LC4jToolService` and `MessagesState`. This is the primitive that materializes the hub-and-spoke Orchestrator-Workers pattern from `CONTEXT-ENGINEERING.md`.
- **Write-time compression (Compress pillar).** Tool results and retrieved memory whose serialized size exceeds a configured threshold are summarized through the small-and-fast model (the local Ollama `qwen3:1.7b` named in §1.4) before they re-enter the context window. Compress therefore applies at every write-back from the MVP onward, not only as the Phase-3 proxy-model middleware (§7.3); only the compressed digest is persisted to the window, never the raw payload.

### 3.4 Persistence

- **Xerial SQLite JDBC driver + Hibernate ORM + Panache + Flyway.** WAL mode is enabled explicitly in the JDBC URL for acceptable single-writer concurrency in personal-use scenarios. The driver (≥ 3.40.1.0) ships native-image support and its own JNI configuration; the native profile sets `org.sqlite.lib.exportPath` so the bundled native library is extracted at run time (see §6.3). The schema is managed through forward-only Flyway migrations in `forvum-engine/src/main/resources/db/migration/`, registered as native resources. Panache keeps the repository code concise without sacrificing type safety. The virtual-thread pinning posture of the JDBC layer is a runtime concern finalized at M5 (§3.8, Risk #11), independent of native compilation.
- **`java.nio.file.WatchService`** inside a `ConfigWatcher` bean fires a CDI `ConfigurationChangedEvent` whenever files under `~/.forvum/` change, enabling hot reload of agent specs, personas, skills, crons, and MCP-server definitions in both dev mode and production fast-jar.

### 3.5 Channels

- **TUI.** The **TamboUI Toolkit** (declarative terminal UI + TCSS styling, in the spirit of Rust's `ratatui` / Python's `textual`) on the `tamboui-jline3-backend`, with its bundled GraalVM reachability hints; the M15 native smoke path is mandatory (§10). TamboUI is GraalVM-native-first (sub-100 ms startup), comfortably inside the §6.2 cold-start budget. A `--no-ansi` degraded mode ships from the MVP to cover environments where terminal-capability detection fails on first boot (a known JLine quirk). The `tamboui-panama-backend` (Java FFM — a final API on Java 25, so the native build stays `--enable-preview`-free — with no external dependency and the best startup) is the alternative backend evaluated at M15. *(As built, M15: v0.1 ships a line-based stdin REPL via `tamboui-widgets`' headless `Buffer` with NO terminal backend (and so no `--no-ansi` JLine-quirk dependency); both the jline3 and panama backends fail the GraalVM 25 native build, so the full-screen Toolkit/TCSS path is deferred pending a native-buildable backend — see the M15 Decision block.)*
- **Web.** Quarkus WebSockets Next for bidirectional streaming. The initial UI is a minimal hand-written HTML/JS page shipped as classpath static resources. A natural evolution path is Qute templates plus HTMX fragments for richer server-driven UI without adopting a full SPA toolchain.
- **Telegram.** Long-poll mode via a blocking `quarkus-rest-client` called from a self-started long-poll loop on an explicit virtual-thread executor (§3.8; `@RunOnVirtualThread` is for externally-invoked inbound handlers) calling the Telegram Bot API. Webhook mode is an opt-in variant for deployments behind a public URL.

### 3.6 Observability

- **OpenTelemetry** is on by default via `quarkus-opentelemetry`. This §3.6 OTel baseline (the four spans below) is owned by M18 acceptance (X7); the OTLP *export* path is the Phase-2 P2-15 follow-on. The engine defines four span kinds:
  - `forvum.agent.turn` — one per inbound user message; attributes include `agent.id`, `identity.id`, `channel.id`, `session.id`.
  - `forvum.llm.call` — one per row written to `provider_calls`; attributes include `model`, `tokens_in`, `tokens_out`, `cost_usd`, `latency_ms`, `fallback`.
  - `forvum.tool.call` — one per `tool_invocations` row.
  - `forvum.graph.node` — one per LangGraph4j node execution.
- **CAPR** (Cost-Aware Pass Rate) is computed from a Panache aggregate over `provider_calls` joined with `capr_events`. It is exposed as a JSON endpoint at `/q/dashboard/capr` and rendered as a Dev UI card in development mode. The per-turn pass/fail verdict is produced by a cheap "judge" model (by default a local Ollama `qwen3:1.7b`), off by default in production and enabled selectively for evaluation runs. **As-built (M18, X6 scenario 10):** the `/q/dashboard/capr` endpoint ships as a minimal `GET` route (`forvum-app` `CaprDashboardRoute`) that returns the recorded `capr_events` rows as a JSON array (the cost-aware aggregate over `provider_calls` is a later refinement; v0.1 ships judge mode off, so every completed turn is a `passed=1`/`judgeModel="none"` row written by `CaprRecorder` from `Agent.respond`). It uses a `quarkus-reactive-routes` `@Route` (`type = BLOCKING` for the Panache read) over the Web channel's already-present `vertx-http` server — preferred over `quarkus-rest` so it does not perturb `HttpClientFactorySelector` or the REST-client stack — and is **server-path-only**: the route serves only when a server channel is up (`vertx-http` is left unbound in one-shot/command mode), carries no `@Startup`/`StartupEvent` work, and so adds no command-mode cold-start cost (the < 200 ms gate is unaffected).
- **CAPR** (Cost-Aware Pass Rate) is computed from a Panache aggregate over `provider_calls` joined with `capr_events`. It is exposed as a JSON endpoint at `/q/dashboard/capr` (owned by M18 acceptance — X7) and rendered as a Dev UI card in development mode. The per-turn pass/fail verdict is produced by a cheap "judge" model (by default a local Ollama `qwen3:1.7b`), off by default in production and enabled selectively for evaluation runs.
- The span set and CAPR aggregate are the operational-traceability foundation of the Context Engineering discipline (§1.4, §2.7): the four `forvum.*` spans make the Write/Select/Compress/Isolate boundaries observable per turn (which window was written, which tools/memory were selected, where the digest replaced raw context, where a worker boundary was crossed), so CE is a measured runtime property rather than a design intention.

### 3.7 Build and distribution

- **Maven 3.9+** as the build tool; the `quarkus-maven-plugin` produces the `quarkus-app/` fast-jar and, under `-Pnative`, a single native executable. The native executable is the shipped product and the default acceptance gate; the fast-jar is the development build and the JVM drop-in-plugin runtime.
- **GraalVM for JDK 25 (Community Edition 25+)** is required for the native profile; native builds use **Mandrel 25.0.x-Final** (Temurin-25-based) as the Quarkus-preferred `native-image` distribution, with the exact patch pinned in CI. The native build is `--enable-preview`-free by construction (§3.8). Quarkus' `container-build=true` lets CI cross-compile native images via a builder container when local GraalVM is unavailable.
- **JaCoCo** enforces an 80% line + 75% branch coverage threshold on `mvn verify` — the `check` rule is declared in the parent and inherited per module (it gates each module, not a reactor aggregate), measured over the Surefire unit run; see §10 for the per-module exclusions/overrides.
- **GitHub Actions CI** runs a matrix of `linux-amd64` and `macos-arm64`, building both JVM and native targets on every pull request. Native parity is mandatory: every milestone native-compiles and runs its native smoke path (§6.4, §10). A `@QuarkusIntegrationTest` smoke-runs the native binary and fails the build if cold-start exceeds 200 ms.
- **Release channels.** The JVM jar and an OCI container image ship to GitHub Releases and Docker Hub. Platform-specific native binaries ship to GitHub Releases. A Homebrew tap and a Scoop bucket follow from v0.5 onward.

### 3.8 Concurrency Discipline

**Virtual threads first** is a foundational project principle: blocking, imperative code carried on virtual threads is Forvum's default concurrency model across every layer — reactive programming (Mutiny `Uni`/`Multi`, Project Reactor) is not. Forvum runs every per-request workflow on virtual threads. The choice is not cosmetic — `@AgentScoped` (§5.1), `ScopedValue<AgentId>` propagation, and the orchestrator-workers spawn pattern (§5.5) all assume virtual-thread semantics. Reactive types are permitted only at a framework-mandated boundary where no virtual-thread-friendly API exists, bridged to a virtual thread at that boundary (e.g. `await()` into a VT scope) with a one-line justification at the call site; JDK streaming primitives (`java.util.concurrent.Flow.Publisher`) used to bridge token streams are not "reactive programming" in this sense. **Reactive code anywhere a virtual thread would have worked is grounds to reject the PR** (see `docs/CODE-REVIEW.md` §3.5). The rules below codify the implementation discipline so the assumption holds in practice.

- **Virtual-thread placement by layer.** Channel inbound REST and WebSocket handlers (`forvum-channel-*`), the engine turn orchestrator (`forvum-engine`), and `quarkus-scheduler`-fired cron entries (M19) all run on virtual threads. Inbound REST/WebSocket handlers and `@Scheduled` methods (the M19 cron entries) carry `@RunOnVirtualThread`; engine-internal fan-out injects or constructs `Executors.newVirtualThreadPerTaskExecutor()` explicitly. The default Quarkus worker pool is the *exception*; any use is commented at the call site with a rationale.
- **Spawn fan-out — virtual-thread executors are the chosen design.** §5.5 `spawn_worker → worker_run` fans out over `Executors.newVirtualThreadPerTaskExecutor()` in a try-with-resources block, joined via `CompletionStage`. This is the committed v0.1 design, not a fallback: it keeps the native build `--enable-preview`-free by construction, which the native-mandatory target (§6) requires. `StructuredTaskScope` (JEP 505) is **not** adopted — it is the *fifth preview* in Java 25 (JEP 525 targets a sixth preview at JDK 26), so it would force `--enable-preview` across javac, the native-image builder, and the runtime JDK in lockstep, tainting the flag-free native build. Structured cancellation and exception propagation across sub-agent boundaries are handled by the executor's shutdown semantics and explicit `CompletionStage` composition. Re-evaluating `StructuredTaskScope` is a forward-looking roadmap note for after the JEP finalizes (post-JDK 26), not a v0.1 spike.
- **Pinning detection.** `-Djdk.tracePinnedThreads` was REMOVED in JDK 24+ (Forvum targets JDK 25), so the legacy stderr `Thread pinned` grep is inert and is not used — a flag-based gate would always vacuously pass. JEP 491 (JDK 24) also stopped `synchronized` from pinning, leaving only native-code pins (e.g. SQLite JNI). Today the enforced concurrency gate is a static CI scan (`.github/concurrency-guardrails.sh`) banning `synchronized`/Mutiny imports in `forvum-engine` / `forvum-channel-*` `src/main`, with fixed-string allowlists at repo root (`pinning-allowlist.txt` / `vt-allowlist.txt`). Runtime pinning detection migrates to the JFR `jdk.VirtualThreadPinned` event (the `quarkus-junit-virtual-threads` extension's `@ShouldNotPin`), which works on all JDK versions; wiring that gate — and allowlisting the documented SQLite JNI frame by its stack-trace fingerprint — is a tracked follow-up.
- **`synchronized` forbidden in hot paths.** A CI grep over `forvum-engine/src/main/java` and (when they exist) `forvum-channel-*/src/main/java` fails the build on any `synchronized` keyword. Use `java.util.concurrent.locks.ReentrantLock`, `java.util.concurrent` collections, or `java.util.concurrent.atomic` primitives. Modules not yet created are exempt until they appear; the rule applies forward, not retroactively.
- **JDBC and virtual threads — posture finalized at M5.** Xerial SQLite JDBC uses `synchronized` JNI native methods and will pin virtual threads. Rather than pre-commit a mitigation against an unfinalized connection-pool choice, this section records the *symptom* and defers the resolution to M5 (see Risk #11). M5 picks among (a) a managed platform-thread executor for transactions, (b) explicit `@Blocking` on Hibernate-bound code paths, or (c) a loom-friendly driver if one becomes available; the chosen pattern is back-filled here.
- **Observability marker.** Every `forvum.*` OTel span carries a `thread.is_virtual` boolean attribute. Dev UI exposes a Concurrency card showing VT-vs-PT carrier counts and pin-detection events; it lands as part of the `forvum-engine` Dev UI surface (§3.2) without a dedicated milestone. The same data exports via OTLP in Phase 2 (§7.2 item 15).

### 3.9 Development Workflow (`quarkus-agentic` plugin)

Forvum is built with the `quarkus-agentic@eldermoraes` plugin as the canonical tooling across every milestone. Two pieces apply throughout: the **`quarkus-langchain4j-scaffolding` skill** (templates and layout for new Quarkus/LangChain4j modules) and the **Quarkus Agent Dev MCP** (`quarkus/*` tools: `create`, `update`, `start`, `skills`, `searchDocs`, `searchTools`, `callTool`). The plugin's drop-in `CLAUDE.md` conventions are the authoritative coding-style source and are not restated here.

- **Mandatory tooling.** Every Quarkus task — module creation, extension selection, configuration, version checks, API usage, troubleshooting — goes through the Quarkus Agent Dev MCP. Never create a module or add an extension by hand. LangChain4j, LangGraph4j, JLine, and other non-Quarkus library APIs are looked up with `context7`; Quarkus APIs with `quarkus/searchDocs`. If a required tool is unavailable, stop and report rather than fall back to model memory.
- **Per-module scaffolding (reactor is hand-authored).** The skill's templates are a per-module starting point, **not** the reactor skeleton. The reactor topology (parent pom, `forvum-bom`, the four layers of §2) is authoritative, owned by M1, and hand-authored. For each new Quarkus-bearing module (`forvum-engine`, every `forvum-provider-*`, `forvum-channel-web`, `forvum-tools-*`), run `quarkus/create` to harvest the *current* Quarkus platform version and correct extension wiring into a throwaway single-module app, then **transplant**: lift the dependency coordinates into `forvum-bom`/the module pom (versions managed by the imported BOMs, never pinned), adopt the matching template class as the module's starter, and discard the generated parent pom, wrapper, and app packaging. The `native` profile, `-parameters` flag, surefire/failsafe wiring, and Dev-Services-off property are lifted into the parent pom once, not per module. Quarkus-free modules (`forvum-core`, `forvum-sdk`) do not use the skill.
- **Extension patterns.** Before writing code against any Quarkus extension, call `quarkus/skills` for that extension — `quarkus-langchain4j-ollama` / `-anthropic` / `-openai` / `-vertex-ai-gemini` (and `-ai-gemini` / `-mcp`), `quarkus-hibernate-orm-panache`, `quarkus-flyway`, `quarkus-websockets-next`, `quarkus-rest-client`, `quarkus-scheduler`, and ArC (`InjectableContext` / `BuildStep`). LangGraph4j is **not** a Quarkus extension, so its API is learned through `context7`, not `quarkus/skills`; at M18 the supervisor graph reuses the scaffolding template's sub-agent and streaming-bridge shapes but orchestrates with LangGraph4j `StateGraph`, **not** the declarative `@SequenceAgent` / `@SupervisorAgent` annotations (§3.3, §5.5).
- **Testing.** JVM-mode tests run through the Dev MCP via a subagent: `quarkus/callTool` with `devui-testing_runTests` (all) or `devui-testing_runTest` (one class). The main session never invokes `mvn test` / `./mvnw test` for JVM-mode tests. Each milestone's `Verify` command in §7.1 remains the contract the run must satisfy; the Dev MCP is the execution mechanism. Native integration tests (`-Pnative`, `@QuarkusIntegrationTest`) remain a Maven/failsafe step — the mandatory native parity of §6 and §10 and the M20 gate.
- **Error triage.** On any compile, deploy, or runtime failure, call `quarkus/callTool` `devui-exceptions_getLastException` for structured detail, fix, then `devui-exceptions_clearLastException`. (If an app fails on its very first deploy, before the Dev MCP handler registers, read the dev-mode console instead.)

**`forvum-bom` pin table** (the single bump point of §2.1; versions observed 2026-06-02, governed by the imported BOMs except where pinned directly):

| Dependency | Pin | Source of truth |
|---|---|---|
| Quarkus platform | 3.33.x LTS — 3.33.2 (import `quarkus-bom`) | imported BOM |
| quarkus-langchain4j (Quarkiverse) | 1.11.0.CR2 (PRE-RELEASE; import `quarkus-langchain4j-bom:1.11.0.CR2`) — stable fallback 1.10.0 | imported BOM — single AI-classpath governor |
| LangChain4j core | 1.16.1 (transitive via qlc4j 1.11.0.CR2; 1.14.1 on the stable-1.10.0 fallback) | not pinned independently |
| LangGraph4j | `org.bsc.langgraph4j:langgraph4j-core:1.8.17` | pinned directly |
| GraalVM / Mandrel | GraalVM CE 25+ / Mandrel 25.0.x-Final | CI toolchain (exact patch pinned in CI) |
| JDK | Java 25 (LTS) | toolchain |
| Xerial SQLite JDBC | `org.xerial:sqlite-jdbc` ≥ 3.40.1.0 | pinned directly |
| TamboUI (TUI) | `tamboui-bom:0.3.0` → `tamboui-toolkit` + `tamboui-jline3-backend` (as built, M15: just `tamboui-widgets`' headless `Buffer`, **NO** terminal backend — jline3/panama both fail the GraalVM 25 native build; see the M15 Decision block) | imported BOM (transitively manages JLine 3) |
| Flyway | via `quarkus-flyway` | Quarkus BOM — not pinned independently |
| OpenTelemetry | via `quarkus-opentelemetry` | Quarkus BOM — not pinned independently |
| Maven | 3.9+ (committed wrapper) | build wrapper |

The baseline adopts the PRE-RELEASE `quarkus-langchain4j` 1.11.0.CR2 (brings LangChain4j core 1.16.1, targets Quarkus 3.33.2) to meet the LangChain4j ≥ 1.15.1 floor; the stable-only fallback is `quarkus-langchain4j` 1.10.0 (LangChain4j core 1.14.1). When `quarkus-langchain4j` 1.11.0 ships FINAL (GA), bump the pin from 1.11.0.CR2 to 1.11.0 and drop the pre-release caveat; the `maxSequentialToolsInvocations` → `maxToolCallingRounds` rename already applies on the 1.15.x+ surface (§5.5).

---

## 4. Storage (Files + SQLite Schema)

Forvum uses a hybrid persistence model: human-editable configuration and intent live as Markdown and JSON files under `~/.forvum/`, while operational and append-only state (sessions, messages, memory, tool invocations, provider calls, CAPR events) lives in a single embedded SQLite database. This split keeps user-facing artifacts diffable and friendly to version control, while giving the engine a queryable, transactional store for runtime data. It also maps cleanly onto the Context Engineering Write/Select/Compress/Isolate pillars: files carry the Write surface users edit directly; SQLite carries the Select surface the engine scans, filters, and aggregates; the typed SQL-mirror enums and one-trip `Usage` snapshot (§4.3) are the Compress surface that keeps stored state classification-dense rather than narrative-heavy; and the `agent_id` column on every operational row is the Isolate surface that keeps one agent's state queryable in isolation from its siblings.

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
- **`skills/<skill>.md`** is a named prompt template with front-matter declaring its input schema. Agents invoke skills by name through the `SkillInvokerTool` (skills ARE tools; the surface is owned by M13 acceptance — X7). Skills are globally visible to any agent allowed to call the skill tool — they are not per-agent.
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

The three memory tiers — `messages` (short-term conversational), `episodic_memory` (procedural: observation/decision/reflection), and `semantic_memory` (long-term facts) — are the Write pillar's tiered scratchpad surface: reasoning state never depends solely on the prompt window, and each tier is read back on the next turn with its own retrieval scope governed by the agent's `MemoryPolicy` (§4.3.6).

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
- `FallbackTriggered.reason` is a `String` populated from `FallbackReasons.*` constants only. (The once-proposed migration to a `FailureClass` enum was **declined at M8**: the stabilized `FailureClass` is the engine-local 3-way retry axis `Retryable`/`NonRetryable`/`Unknown`, orthogonal to and coarser than the user-facing `reason` token — collapsing them would lose telemetry granularity. `reason` stays a `FallbackReasons` String.)

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
- **RBAC layer is orthogonal (delivered in P2-11).** The role → scope-set mapping sits *above* this enum, not inside it — the enum stays a flat list of capabilities. P2-11 adds the Layer-0 `RoleSpec(name, Set<PermissionScope>)` record + an additive `Identity.roles` list, an engine `RoleRegistry` (built-in permissive `default-user` = every registered scope, and restricted `cron` = read-only, each overridable by `$FORVUM_HOME/roles/<name>.json` with M4 hot-reload), and a second `ToolExecutor` gate: a tool in the belt is additionally denied + audited `denied` when its required scope is outside the caller's effective scopes (the union of its roles' sets), bound at turn entry on `CurrentIdentity.CURRENT_EFFECTIVE_SCOPES`. No new enum constants and no schema change were needed (§7.2 item 11).

**Reserved future values:**

| Scope (reserved name)  | Introduced in | Note |
|------------------------|---------------|------|
| WEB_BROWSE             | Phase 2 (§6)  | browser tool |
| WEB_FETCH / WEB_SEARCH | bundled-web   | naming TBD in owning PR |
| SHELL_EXEC             | Phase 2 (§6)  | sandboxed shell |
| MCP_REMOTE             | Phase 2 (§9.3) | remote MCP-server tools (#38) |

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
- **Reserved future extension paths.** *New `Window` permits* — `LineageWindow(rootAgentId)` for shared-budget hierarchies (deferred by DR-4c, §4.3.5.3 — still reserved), rolling-window / monthly / unbounded-accumulation permits as additional `permits` of the sealed interface (non-breaking). *Per-link cost awareness in `FallbackChain`* — declined for v0.5 by DR-4c (§4.3.5.3): links stay bare `ModelRef`s and Decision 9's short-circuit stays non-overridable; the door stays open — a future round enriching chain links with `costDims` declarations would make the short-circuit overridable by chain policy, enabling free-tier fallback substitution. *Race-window mitigations* — pre-allocation soft reservations or pessimistic locking if production telemetry shows overshoot exceeding the documented bounds of Decision 8. *Exhaustion escalation* — escalate-to-human via channel notification if demand surfaces post-MVP.

##### 4.3.5.3 `FallbackChain`

A `FallbackChain` declares one agent's (or one cron's) **ordered model preference**: the `primary` model the turn fronts, plus the `fallbacks` tried in declaration order when a link fails at the provider level. It is a pure config-time value record composed at materialization from the `primaryModel` + `fallbackModels` keys of `agents/<id>.json` / `crons/<id>.json` (§4.3.8, DR-8; never deserialized as a JSON field) — the **runtime** form stays the M8 engine-local `FallbackLink` list: at materialization the engine resolves each `ModelRef` against `ModelProvider` beans and hands the resolved links to the decorators (`FallbackChatModel` / `FallbackStreamingChatModel`, §5.4). The full deliberation and decision points are in `docs/design-rounds/group-4c-fallback-chain.md` (DR-4c).

```java
// Module: forvum-core · Package: ai.forvum.core (co-located with ModelRef, §4.3.5.1)
public record FallbackChain(
    ModelRef primary,           // the model the turn fronts — operator preference, order tiebreak
    List<ModelRef> fallbacks    // tried in declaration order on provider-level failure; empty = none
) {
    /** primary + fallbacks in traversal order — the adaptive router's authority set (P3-4 #52). */
    public List<ModelRef> links() { ... }

    /** The no-fallback chain (the M8/LlmSelector single-link case; DR-8's backward-compatible bridge). */
    public static FallbackChain single(ModelRef primary) { ... }
}
```

**Conventions and rationale:**

- **Validation via `IllegalStateException` with origin-naming messages (the §4.3.5.x convention).** `primary` non-null; `fallbacks` non-null (empty = "no fallback" — the single-link chain), defensively copied immutable, no null elements; duplicates rejected — the primary repeated in `fallbacks`, or a ref repeated within `fallbacks`, is a config mistake (an immediate same-request re-attempt of an identical link cannot succeed where the attempt just failed; deliberate same-link retry/backoff is a deferred knob, not a chain property). (DR-4c DP-1/DP-3)
- **No `CostBudget` field — the earlier `(primary, fallbacks, budget)` sketch is amended.** `Persona` (§4.3.7) and the cron spec already carry `costBudget`; a second budget on the chain would create two config sites with ambiguous precedence. The Decision-8 pre-call check (§4.3.5.2) receives the budget *alongside* the chain at decorator construction, and `BudgetExhaustedException` short-circuits before any link is attempted (Decision 9); the enforcement wiring + e2e is the CostBudget-enforcement package, not DR-4c's. (DR-4c DP-2/DP-11)
- **Traversal semantics are the M8 as-built (§5.4).** Per link: attempt → one `provider_calls` row per attempt (`is_fallback = 1` past the first) → on failure `FailureClassifier.shouldFallback` decides the advance: **provider-level** faults (rate limit, timeout, 5xx, auth, model-not-found, connection, unknown) fall through, emitting one `FallbackTriggered(failed, next, reason)` (§4.3.2) per hop (`reason` nullable when no `FallbackReasons` token fits); **request-level** faults (`InvalidRequestException`, request-level 4xx) rethrow immediately — a malformed request fails on every provider alike. The advance axis is `shouldFallback`, not `FailureClass.isRetryable()` — auth failures advance because the next provider has different credentials. The streaming twin commits to a stream once partial tokens have reached the user (no mid-stream fallback). (DR-4c DP-6/DP-7)
- **`FailureClass` stays the engine-local sealed 3-way retry axis (`Retryable`/`NonRetryable`/`Unknown`) — no `Filtered` permit.** The DR-6a handover (§9.2.2) resolves as a fold into `NonRetryable`: a filter trip is non-retryable by definition; the egress `OutputFilteredException` fires pre-channel-emit *after* the chain returned and never transits `FailureClassifier`; provider-side `ContentFilteredException` already lands `NonRetryable` via its `InvalidRequestException` parent (and does not fall through — re-sending filtered content to another provider is filter evasion). Telemetry keeps the distinction via `FallbackReasons.FILTERED` (§9.2.2; the token lands with P2-OUTPUTGUARD #48 and is never a chain-hop reason). A permit can be added later — breaking exhaustive switches at consumer sites is the intended extensibility surface (the `Window` precedent, Decision 5). The §4.3.2 `String reason → FailureClass` migration stays declined at M8. (DR-4c DP-4/DP-5)
- **The chain is the authority set for adaptive routing (P3-4 #52).** The future CAPR-driven router is a deterministic blended-score function that may **reorder** or **drop** persona-declared links only — it never invents a provider/model outside the declared chain and must keep at least one link; declared order is the tiebreak, and `primary` keeps its operator-preference meaning. `links()` is the entire contract surface the router needs; the record stays routing-agnostic. (DR-4c DP-8)
- **Per-link `costDims` and `LineageWindow`: declined for v0.5; doors stay open.** Links stay bare `ModelRef`s and the Decision-9 `COST_BUDGET` short-circuit stays non-overridable; a future round may widen links into per-link records carrying `costDims` (the §4.3.5.2 reserved path). `LineageWindow` remains a reserved `Window` permit with no surface on the chain — the budget never rides the chain, so there is no interplay to define here. (DR-4c DP-9/DP-10)
- **Native registration + landing.** The record is composed engine-side and never JSON-serialized (§4.3.8, DR-8 DP-12 — the `GraphTurnRequest`/`CronSpec` precedent), so it needs no reflection-holder entry; should a future surface ever serialize it, it joins the engine `CoreReflectionRegistration` holder (§6.3) — never `@RegisterForReflection` in core. It lands with its first consumer (the DR-8 spec composition + the multi-link `LlmSelector` mapping `links()` → resolved `List<FallbackLink>`), the M7 type-lands-in-consumer-PR pattern; property-style canonical-constructor tests (§10) land with it. (DR-4c DP-12)
- **Cross-references.** Decorators + classifier as-built: §5.4, §7.1 M8. Budget pre-call + `BudgetExhaustedException`: §4.3.5.2 Decisions 8/9. `FallbackTriggered` + `FallbackReasons`: §4.3.2. `FILTERED` coordination: §9.2.2. Spec composition (`fallbackModels` on the spec; the chain composed engine-side, no key migration — §4.3.8): DR-8. Adaptive routing: §7.3 item 4 / P3-4 #52.

#### 4.3.6 `MemoryPolicy`

A `MemoryPolicy` declares one agent's **Select-pillar retrieval scope** and **Compress-pillar compression threshold** over the three memory tiers — `messages` (short-term), `episodic_memory` (procedural), `semantic_memory` (long-term facts), all in the M5 SQLite schema (§4.2). It is a pure data record — strategy, tiers, caps, floor, and threshold are static per-agent configuration — that *drives* the `MemoryProvider` retrieval SPI (§2.2, `forvum-sdk`) without coupling the agent to any one retrieval algorithm. Nothing is persisted on `MemoryPolicy` itself; the SQLite tiers are authoritative, exactly as `provider_calls` is authoritative for `CostBudget` (§4.3.5.2). The full deliberation and decision points are in `docs/design-rounds/group-5-memory-policy.md` (DR-5).

All five types below live directly in the `ai.forvum.core` package of the `forvum-core` module (co-located with `ModelRef`, §4.3.5.1) — unlike the budget surface they need no service interface inside core, so they do not earn their own sub-package. Two records (`MemoryQuery`, `MemoryHit`) and the new SPI method also cross the Layer-1 boundary: `MemoryProvider.retrieve(MemoryQuery, MemoryPolicy) → List<MemoryHit>` (§2.2) is the method P2-5's reference memory-host implements. The SPI stays Quarkus-free and reactive-free — retrieval blocks on a virtual thread (§3.8).

```java
// Module: forvum-core
// Package: ai.forvum.core
// Each top-level type below lives in its own .java file.

package ai.forvum.core;

import java.util.EnumSet;
import java.util.Set;

// -- Shape: the per-agent retrieval + compression policy --

public record MemoryPolicy(
        RetrievalStrategy strategy,     // vector | graph | metadata | hybrid | none
        Set<MemoryTier> tiers,          // which tiers to read back from
        int topK,                       // max hits returned across selected tiers
        double minScore,                // similarity floor in [0.0, 1.0]; 0.0 = no floor
        int compressThresholdChars) {   // serialized size above which reduce summarizes (§5.5)

    public MemoryPolicy {
        if (strategy == null) {
            throw new IllegalStateException(
                "MemoryPolicy strategy must be non-null. Use "
              + "RetrievalStrategy.NONE to disable retrieval. Check the "
              + "\"memoryPolicy\" block in agents/<id>.json.");
        }
        if (tiers == null) {
            throw new IllegalStateException(
                "MemoryPolicy tiers must be non-null (empty is allowed only "
              + "when strategy == NONE). Check agents/<id>.json.");
        }
        tiers = (tiers.isEmpty())
                ? EnumSet.noneOf(MemoryTier.class)
                : EnumSet.copyOf(tiers);   // defensive, unmodifiable copy
        if (strategy != RetrievalStrategy.NONE && tiers.isEmpty()) {
            throw new IllegalStateException(
                "MemoryPolicy with strategy=" + strategy + " selects no "
              + "tiers, so it would retrieve nothing. Either select at least "
              + "one MemoryTier or set strategy=NONE. Check agents/<id>.json.");
        }
        if (topK < 0) {
            throw new IllegalStateException(
                "MemoryPolicy topK must be non-negative. Got: " + topK
              + ". 0 means retrieve nothing this turn. Check agents/<id>.json.");
        }
        if (minScore < 0.0 || minScore > 1.0) {
            throw new IllegalStateException(
                "MemoryPolicy minScore must be in [0.0, 1.0]. Got: " + minScore
              + ". Providers normalize their native distance metric into this "
              + "range. Check agents/<id>.json.");
        }
        if (compressThresholdChars < 0) {
            throw new IllegalStateException(
                "MemoryPolicy compressThresholdChars must be non-negative. "
              + "Got: " + compressThresholdChars + ". 0 means always compress. "
              + "Check agents/<id>.json.");
        }
    }

    /**
     * The config-absent default, the single source consumed by the M5 config
     * loader and the DR-8 {@code AgentSpec} parse: hybrid retrieval across all
     * three tiers, topK=8, no similarity floor, an 8000-char compression
     * threshold. Numeric values are starting points to be baselined against
     * the §10 per-turn performance gates, not load-bearing constants.
     */
    public static MemoryPolicy defaults() {
        return new MemoryPolicy(
                RetrievalStrategy.HYBRID,
                EnumSet.allOf(MemoryTier.class),
                8, 0.0, 8000);
    }
}

// -- Select: the retrieval algorithm the provider applies --

public enum RetrievalStrategy { VECTOR, GRAPH, METADATA, HYBRID, NONE }

// -- The three M5 memory tiers (§4.2) --

public enum MemoryTier { MESSAGES, EPISODIC, SEMANTIC }

// -- The per-turn retrieval request --

public record MemoryQuery(
        String agentId,      // tenant key — retrieval never crosses agents (Isolate)
        String sessionId,    // nullable: session-scoped tiers narrow to it; null = cross-session
        String text) {       // the retrieval cue (current user turn / sub-question)

    public MemoryQuery {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException(
                "MemoryQuery agentId must be non-null and non-blank — it is "
              + "the tenant key that confines retrieval to one agent (Isolate). "
              + "A null here indicates the @AgentScoped context was not "
              + "propagated at construction time.");
        }
        if (text == null) {
            throw new IllegalStateException(
                "MemoryQuery text must be non-null (may be blank for a "
              + "tier-scan with no cue). A null indicates a wiring bug.");
        }
    }
}

// -- A single retrieved memory, framed downstream as <retrieved_memory> DATA (§9, DR-6a) --

public record MemoryHit(
        MemoryTier tier,     // which tier this hit came from
        String content,      // the retrieved text — DATA, not instructions
        double score,        // relevance in [0.0, 1.0]; 1.0 for non-scored (metadata) hits
        String source) {     // free-form provenance (semantic_memory.source, or tier+id)

    public MemoryHit {
        if (tier == null) {
            throw new IllegalStateException(
                "MemoryHit tier must be non-null. A null indicates a "
              + "MemoryProvider construction bug.");
        }
        if (content == null) {
            throw new IllegalStateException(
                "MemoryHit content must be non-null. A null indicates a "
              + "MemoryProvider construction bug.");
        }
        if (score < 0.0 || score > 1.0) {
            throw new IllegalStateException(
                "MemoryHit score must be in [0.0, 1.0]. Got: " + score
              + ". Providers normalize their native metric into this range; "
              + "use 1.0 for non-scored (metadata) hits.");
        }
    }
}
```

**Type conventions and cross-references:**

- **Flat record with an enum strategy field, not a sealed per-strategy hierarchy.** The four retrieval strategies (`VECTOR`/`GRAPH`/`METADATA`/`HYBRID`) differ only in the algorithm the *provider* runs, not in the policy's schema — every strategy needs the same `tiers`/`topK`/`minScore`/`threshold` knobs. A `VectorPolicy`/`GraphPolicy` hierarchy would import form without the justifying condition, the same reasoning that kept `CostBudget` flat (§4.3.5.2 Decision 1/4). (DR-5 DP-1)
- **`RetrievalStrategy.NONE` is a first-class "memory off" value.** An agent that writes but never reads back (or a deterministic test agent) sets `NONE`; the engine short-circuits and never calls `retrieve(...)`. This keeps `MemoryPolicy` on the agent spec non-nullable — every agent has a policy; "no retrieval" is a value, not absence. (DR-5 DP-4)
- **Validation via `IllegalStateException` with origin-naming messages.** The canonical constructors throw `IllegalStateException` (not `IllegalArgumentException`) with triage-oriented text naming the likely origin — config file `agents/<id>.json`, or a programmatic / wiring bug — matching `ModelRef` (§4.3.5.1) and `CostBudget` (§4.3.5.2). Empty `tiers` is legal only with `strategy == NONE`; a non-`NONE` strategy selecting no tiers is a config mistake and throws. (DR-5 DP-5)
- **`minScore` is a normalized `double` in `[0.0, 1.0]`.** Providers normalize their native distance metric (cosine, L2, BM25, graph-walk depth) into a provider-independent relevance so the policy's floor means the same thing everywhere; `minScore = 0.0` is "no floor". `MemoryHit.score` rides the same scale, with `1.0` for non-scored metadata hits. (DR-5 DP-2)
- **`compressThresholdChars` is a character count, not tokens.** Character length is computable without a tokenizer (native-clean, no model round-trip merely to decide *whether* to compress) and `reduce` (§5.5) already speaks in serialized size. It is the single Compress knob: it governs both the `reduce` worker-output merge and write-time summarization of oversized retrieved memory. (DR-5 DP-3, DP-12)
- **`MemoryPolicy.defaults()` is the single config-absent source.** The M5 config loader and the DR-8 `AgentSpec` parse both read it, so the absent-config policy has one definition. Its numeric values are baseline starting points to be tuned against the §10 per-turn performance gates, not load-bearing constants. (DR-5 DP-6)
- **The SPI method `MemoryProvider.retrieve(MemoryQuery, MemoryPolicy) → List<MemoryHit>` (§2.2).** Query carries *what to retrieve about* (cue text + the agent/session tenant keys); policy carries *how/where/how-much* (strategy, tiers, caps, floor). The split keeps the policy reusable across turns (agent config) while the query changes every turn. It returns at most `topK` hits, each at or above `minScore`, drawn only from `tiers`. Blocking, run on a virtual thread (§3.8 — no reactive types); `forvum-sdk` stays Quarkus-free. Implementations confine retrieval to `query.agentId()` (Isolate). The default linear-scan provider (the `sqlite-vec`-free MVP path of §4.2) lands in **P2-5**. (DR-5 DP-8, DP-9)
- **Retrieval output is framed as `<retrieved_memory>` DATA blocks.** Every `MemoryHit.content` is wrapped in a `<retrieved_memory>…</retrieved_memory>` block (with provenance from `MemoryHit.source`) before it enters the prompt window, never spliced into the system/instruction region. Retrieved `semantic_memory`/`episodic_memory` rows can contain model-authored text from a prior, possibly-poisoned turn, so retrieved memory is an untrusted-content surface. The framing mechanism and threat model are owned by the §9 Security section (DR-6a point 5); §4.3.6 only declares that retrieval output flows through it. (DR-5 DP-13)
- **The pre-memory-write `OutputFilter` boundary.** The write path that persists into `episodic_memory`/`semantic_memory` passes candidate content through the pre-memory-write `OutputFilter` hook (the §9 Security section, DR-6a point 2c) *before* the row is inserted — so a secret/PII value cannot be durably stored and later re-retrieved into a prompt. `MemoryPolicy` does **not** configure the filter; it governs *read-back*, the filter governs *write*. The interaction is: filter on write (§9) → store → retrieve under policy (§4.3.6) → frame as `<retrieved_memory>` (§9). (DR-5 DP-14, DP-15)
- **Spawn inheritance mirrors `CostBudget`/`Identity`.** A spawned sub-agent inherits its parent's `MemoryPolicy` verbatim unless the spawn request overrides it (§5.5); the override is all-or-nothing (absent ⇒ inherit, present ⇒ replace, no partial merge — §4.3.5.2 Decision 10). Unlike `CostBudget`'s `SessionWindow`, `MemoryPolicy` carries **no parent-bound scope** — the tenant key (`agentId`) is supplied per-call via `MemoryQuery` from the child's `@AgentScoped` context — so a verbatim-inherited policy reads the *child's* own memory automatically, and **no `SpawnConfigurationException` analogue is needed**. (DR-5 DP-10, DP-11)
- **Native registration.** All five types are JSON-serialized (they ride in `agents/<id>.json` and cross the SPI). Per §6.3, Layer-0 records do **not** carry `@RegisterForReflection` (core bans `io.quarkus*`); `MemoryPolicy`, `RetrievalStrategy`, `MemoryTier`, `MemoryQuery`, and `MemoryHit` are appended to the single engine holder `forvum-engine/.../persistence/CoreReflectionRegistration.java` by the milestone that lands them (P2-5). (DR-5 §3.3)
- **Cross-references.** Memory tiers + schema: §4.2 (V1 schema; the `MemoryPolicy` forward-reference at §4.2 now resolves). `reduce` compression node consuming `compressThresholdChars`: §5.5. Spawn mechanism and override parameter: §5.5 (`spawn_worker`), §5.1. `MemoryProvider` SPI and the Select pillar: §2.2, §2.7. `<retrieved_memory>` framing + pre-memory-write `OutputFilter`: §9 (DR-6a). `ModelRef` (co-located in the same `ai.forvum.core` package): §4.3.5.1. Demo deferral D2's `memoryPolicy` sub-gap is dissolved here; the residual `AgentSpec` composition is settled by DR-8 (§4.3.8).

#### 4.3.7 Identity, message, persona, and tool-spec records

`AgentId`, `Identity`, `ChannelMessage`, `Persona`, and `ToolSpec` are listed in the Layer-0 inventory
(§2.1) and the M2 file manifest (§7.1) but were not shape-frozen above. Their shapes are specified here
as the M2 amendment (this section is the "amend §4.3 first" step required before M2 writes the records).
They follow the same conventions as the frozen Tier-1 types: pure-Java records with canonical-constructor
validation throwing `IllegalStateException` with triage-oriented, origin-naming messages (the `ModelRef` /
`CostBudget` idiom). **Nullable fields are used in preference to `Optional`** — consistent with the
`CostBudget`/`Spend` nullable convention, and simpler for Jackson round-trip and native image.

```java
// ai.forvum.core.id.AgentId  — ScopedValue<AgentId> key; SQL agent_id column.
public record AgentId(String value) {}                      // non-blank, no edge whitespace; toString() == value

// ai.forvum.core.id.Identity  — materialized from identities/<id>.json (§4.1, §5.3).
public record Identity(String id, String displayName,
                       Map<String, String> channelAccounts) {}  // channelId -> native user id; copied to an immutable map

// ai.forvum.core.ChannelMessage  — inbound only; outbound flows as AgentEvent.TokenDelta (§5.3).
public record ChannelMessage(String channelId, String nativeUserId,
                             String content, Instant timestamp) {}  // content may be empty; the rest are required

// ai.forvum.core.Persona  — spec.md (systemPrompt) + spec.json structural config (§5.2).
public record Persona(AgentId id, String systemPrompt, List<String> allowedTools,
                      ModelRef primaryModel, AgentId parent,
                      CostBudget costBudget, Long toolBudget) {}  // allowedTools copied immutable; last three nullable

// ai.forvum.core.ToolSpec  — registry entry filtered by allowedTools globs (§5.3).
public record ToolSpec(String name, String description,
                       PermissionScope requiredScope, String parametersJsonSchema) {}  // raw JSON Schema; "{}" for none
```

**Conventions and rationale:**

- **`AgentId` / `Identity` live in `ai.forvum.core.id`; `ChannelMessage` / `Persona` / `ToolSpec` in
  `ai.forvum.core`.** `Identity.channelAccounts` and `Persona.allowedTools` are defensively copied to
  immutable collections in the canonical constructor; a null collection is rejected (use an empty one).
- **`Persona` omits the LLM fallback chain and the memory policy at M2 by design.** It carries only
  `primaryModel` today; the chain is composed engine-side from `primaryModel` + the optional `fallbackModels`
  spec list (settled by DR-4c + DR-8 §4.3.8); `Persona` carries no chain field, and the retrieval
  field arrives with `MemoryPolicy` (§4.3.6, settled by DR-5). `Persona` must not reference either deferred type at M2; the grown as-built record (12 components) and the authoritative `agents/<id>.json` schema are settled in §4.3.8 (DR-8). `parent`
  null means a top-level agent; null `costBudget`/`toolBudget` mean uncapped; a non-null `toolBudget` must
  be non-negative.
- **`ToolSpec` is a Forvum-native record, not a wrapper over LangChain4j's `ToolSpecification`.** The
  parameter schema is carried as a raw JSON Schema string so Layer 0 stays free of any AI-library
  dependency; adaptation to `ToolSpecification` happens in the SDK/engine. `requiredScope` is the
  capability the `ToolExecutor` enforces (§4.3.4).
- **Native reflection for these records (and every other Layer-0 type) is registered from the engine, not
  here.** See §6.3: Layer 0 cannot carry `@RegisterForReflection`.

#### 4.3.8 `AgentSpec` composition and the `agents/<id>.json` schema

The agent composition — `Identity`, `Persona`, the fallback chain (§4.3.5.3), `CostBudget`,
`MemoryPolicy`, the allowed-scope cap, and the parent pointer — is settled by DR-8
(`docs/design-rounds/group-8-agentspec-composition.md`, issue #64), replacing the demo's ad-hoc shape
and resolving demo deferral D2 permanently. This section is the authoritative `agents/<id>.json`
schema and supersedes the M2 `Persona` snippet in §4.3.7 as the as-built record shape. **Every key
except `primaryModel` is optional with a backward-compatible default** — every pre-DR-8 spec file
parses unchanged. (DR-8 DP-1)

```jsonc
// ~/.forvum/agents/<id>.json — paired with the required agents/<id>.md system prompt (§4.1, §5.2)
{
  "primaryModel":  "ollama:qwen3:1.7b",          // REQUIRED — ModelRef.parse format (§4.3.5.1)
  "fallbackModels": ["openai:gpt-4.1-mini"],     // default [] — ordered fallback refs after primary (§4.3.5.3, §5.4)
  "allowedTools":  ["fs.read", "web.*"],         // default [] — tool-belt globs (§5.3)
  "parent":        "main",                        // default null — top-level agent
  "toolBudget":    20,                            // default null — uncapped tool loop
  "outputSchema":  { "type": "object" },          // default null — free-text reply (P2-12, §7.2 item 12)
  "roles":         ["research-readonly"],         // default [] — no agent-level scope cap (§4.3.4)
  "identityId":    "default",                     // default null — unresolved sessions stay anonymous (§5.3)
  "memoryPolicy":  { "strategy": "HYBRID", "topK": 8 },   // default absent — MemoryPolicy.defaults() (§4.3.6)
  "costBudget":    { "maxUsd": 2.50, "window": "day" },   // default absent — uncapped (§4.3.5.2)
  "cycle":         { "steps": ["reflect", "critique", "revise"],
                     "maxRounds": 3, "stopSentinel": "DONE" }  // default absent — standard §5.5 graph
}
```

The composition splits across two records:

```java
// Layer 0 — forvum-core/.../Persona.java, grown additively (the Identity.roles precedent: the
// canonical constructor widens; the existing 8-arg constructor remains as a delegating overload;
// null fallbackModels/roles normalize to List.of(), null memoryPolicy to MemoryPolicy.defaults()).
public record Persona(
    AgentId id, String systemPrompt, List<String> allowedTools, ModelRef primaryModel,
    AgentId parent, CostBudget costBudget, Long toolBudget, String outputSchema,
    List<ModelRef> fallbackModels,   // NEW — [] = primary-only chain
    MemoryPolicy memoryPolicy,       // NEW — non-nullable; absent config = defaults()
    List<String> roles,              // NEW — [] = no agent-level scope cap
    String identityId                // NEW — null = anonymous fallback unchanged
) {}

// Layer 2 — forvum-engine: the §5.2 registry value, materialized at last (AgentRegistry holds
// ConcurrentMap<AgentId, AgentSpec>; AgentRegistry.persona(id) keeps returning Persona).
public record AgentSpec(Persona persona, CycleSpec cycle) {}   // cycle nullable = no declared cycle
public record CycleSpec(List<String> steps, int maxRounds, String stopSentinel) {}
```

**Conventions and rationale:**

- **Contract data grows on `Persona` (Layer 0); the graph directive `cycle` stays engine-side.** `fallbackModels`, `memoryPolicy`, `roles`, and `identityId` are pure contract data; `cycle` is a directive to the LangGraph4j compiler — an engine concern, following the `CronSpec` precedent for file-driven engine specs. The §5.2 registry value becomes `AgentSpec(Persona, CycleSpec)`. (DR-8 DP-2, DP-3)
- **`fallbackModels` is a plain ordered `List<ModelRef>`; the §4.3.5.3 chain is composed engine-side.** The engine builds the `FallbackChain` from `primaryModel + fallbackModels` at materialization (the `LlmSelector`/`FallbackChatModel` seam, where the engine-local `FallbackLink` already adapts). There is **no `primaryModel → fallbackChain` key migration**: `primaryModel` stays the required key; an absent `fallbackModels` is today's single-link chain. The §4.1 `config.json` *global* default chain remains a named deferral. (DR-8 DP-4)
- **`costBudget` parsing is un-deferred; file windows are `"day"`-only.** Syntax `{ maxUsd?, maxTokens?, window?: "day", timezone? }` (at least one cap, per the record's own invariant; absent `timezone` resolves to the system zone at parse). A file-declared `"session"` window is rejected at parse — a config file has no session id (§4.3.5.2 Decision 5). Un-deferral activates the dormant Decision-10 spawn guard: `spawn` gains the optional `CostBudget` override and throws `SpawnConfigurationException` on inheriting a `SessionWindow` parent budget without an override. (DR-8 DP-6)
- **`memoryPolicy`: absent block → `MemoryPolicy.defaults()`; a present block defaults each omitted field** from `defaults()` (tuning one knob never forces restating four). Enum values parse case-insensitively; unknown values throw naming the field and file. All invariants stay in the record's canonical constructor (§4.3.6). (DR-8 DP-5)
- **`roles` is an agent-level scope CAP (intersection), never a grant.** Effective scopes = caller scopes ∩ the union of the agent roles' scope sets (via `RoleRegistry`), computed at the two existing `CURRENT_EFFECTIVE_SCOPES` bind sites (`TurnService.dispatch`, `CronScheduler.fire`); absent/empty = no cap. The spec names roles, not raw scopes — role-sets live above the `PermissionScope` enum (§4.3.4); a cap-only field can never escalate, mirroring the `allowedTools` belt philosophy. (DR-8 DP-8)
- **`identityId` is the fallback identity for unresolved sessions, by reference.** It applies only when channel resolution yields no identity (the anonymous path); a channel-resolved identity always wins — the §5.3 no-override property holds across the spec as well as the spawn boundary. (DR-8 DP-9)
- **`cycle { steps[], maxRounds = 3, stopSentinel? }` pulls §7.3 item 3 forward.** One *round* is one in-order traversal of `steps` (each entry the instruction for one generation pass); the sentinel exits early and is stripped from the final answer; the loop returns best-effort at `maxRounds` (degrade, don't fail). The LangGraph4j compile budget scales as `recursionLimit >= maxRounds × steps.size() + margin`. `outputSchema` validates the cycle's *final* answer only (unchanged P2-12 placement). (DR-8 DP-7)
- **Spawn inheritance, per field.** `systemPrompt`/`primaryModel`/`toolBudget`/`fallbackModels`/`roles`/`identityId` inherit verbatim; `allowedTools` must narrow (subset-enforced); `costBudget` and `memoryPolicy` per their owning authorities (§4.3.5.2 Decision 10; §4.3.6); `outputSchema` and `cycle` are **NOT inherited** — a worker digest is a tool result, never the validated top-level answer, and a worker is a single direct generation that never runs a graph (§5.5). (DR-8 DP-10)
- **Validation split.** Record canonical-constructor invariants (`IllegalStateException`, origin-naming) → `AgentSpecReader` file-shape errors naming `agents/<id>.json` (and, because `forvum doctor` validates through the same reader, every rule is a doctor finding for free — §7.2 item 9) → materialization/`ConfigDoctor` cross-reference checks: each role resolves via `RoleRegistry`, `identityId` names an existing identity, `primaryModel` **and every `fallbackModels` entry** resolve to an installed provider, `parent` names a known agent. (DR-8 DP-11)
- **Native registration: zero new core-holder entries.** Every composed Layer-0 type is already in the engine `CoreReflectionRegistration` holder (§6.3) and registration is per-class, so the grown `Persona` needs no holder change; `AgentSpec`/`CycleSpec` are engine records built field-by-field from `JsonNode`, never JSON-serialized, and carry no `@RegisterForReflection`. (DR-8 DP-12)
- **Cross-references.** `Persona` M2 base shape: §4.3.7. Chain: §4.3.5.3 (DR-4c). Budget: §4.3.5.2. Memory policy: §4.3.6 (DR-5). Roles/scopes: §4.3.4. Registry + spawn: §5.2, §5.5. Cycles: §7.3 item 3. Consumers: PR-8 (memory-retrieval turn wiring; #51 cycles), PR-9 (`CostBudget` e2e; fallback links on the spec).

---

## 5. Sub-agents and CDI Isolation

The single hardest architectural problem Forvum solves is running many agents in the same process without their contexts poisoning each other. The failure mode we are preventing is **context clash**: a sub-agent inheriting the wrong tools, the wrong memory, or the wrong system prompt from a sibling because the container short-circuited scoping. `CONTEXT-ENGINEERING.md` names this explicitly as a reason projects fail in production. Forvum's answer is a Quarkus-native custom CDI scope combined with Java 25 `ScopedValue`, implemented at build time so it survives native compilation.

### 5.1 The `@AgentScoped` custom CDI context

`@AgentScoped` is a `@NormalScope` annotation declared in `forvum-core`. Its backing context is an implementation of Quarkus ArC's `InjectableContext` SPI, registered at build time via a CDI Lite `BuildCompatibleExtension` (`MetaAnnotations.addContext`) declared in `forvum-engine` (`META-INF/services`), so ArC discovers it during augmentation and bakes it into the native image. (The original design called for a deployment-module `@BuildStep`; M6 switched to the BCE because a `@BuildStep` would require turning `forvum-engine` into a runtime+deployment extension, which breaks its M4 headless-library Surefire test setup via a reactor cycle. The BCE keeps `forvum-engine` a plain library and was validated in JVM and native — see §7.1 M6.) `ScopedValue` is a final API in Java 25 (JEP 506), so no preview flag is involved and the native build path stays `--enable-preview`-free; the only native-specific work is registering the custom `InjectableContext` at build time. A `ScopedValue<AgentId> CURRENT_AGENT` is the context's identity key, bound at request entry through `ScopedValue.where(CURRENT_AGENT, id).call(body)` (or `.run(...)` for void) — every CDI bean annotated `@AgentScoped` resolves to the bean instance keyed by the current scoped value at injection time.

`ScopedValue` over `ThreadLocal` is not a cosmetic choice. Virtual threads fanned out from an orchestrator carry the bound value through continuations without the inheritance semantics of `InheritableThreadLocal`, which the JDK now explicitly discourages for virtual threads. Binding and unbinding is stack-scoped: `ScopedValue.where(CURRENT_AGENT, agentId).call(() -> agent.run(turn))` guarantees the binding is torn down when the lambda returns, even on exceptions, so no agent ever observes a stale identity. The `InjectableContext` implementation stores per-agent bean instances in a `ConcurrentHashMap<AgentId, ContextInstances>` and evicts entries when the registry removes an agent.

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

The global `ToolRegistry` knows every `ToolSpec` contributed by every `ToolProvider` plugin. When an agent is materialized, the registry intersects those specs against the agent's `allowedTools` glob list. The result is an immutable `List<ToolSpec>` cached on the `@AgentScoped AgentToolBelt`. The LLM only ever sees this filtered list; there is no code path that bypasses the filter to grant "just this one call" access — that kind of ad-hoc elevation is what leads to tool-misuse incidents and is explicitly forbidden by the design. Tool filtering is the Select pillar applied to capability: each agent's window is offered only the ultra-relevant subset of the global `ToolRegistry`, keeping the tool-spec block in the prompt small and on-topic.

Identity resolution happens at channel-message entry. Each channel's inbound handler translates its native user id (Telegram user id, web session cookie, OS username) into a Forvum `Identity` by consulting `identities/<id>.json`. The resolved `Identity` is bound to a `ScopedValue<Identity>` for the duration of the turn, parallel to `CURRENT_AGENT`. Sub-agents inherit their parent's identity — a sub-agent does not "become" a different user, and there is no API to override identity across the spawn boundary. This is a security property, not just a convenience: tools that use `Identity` for authorization cannot be tricked by a spawned sub-agent.

### 5.4 `FallbackChatModel`: per-agent, per-cron LLM selection

`FallbackChatModel` is a `ChatModel` decorator in `forvum-engine` that wraps a `FallbackChain(primary, fallbacks)` (§4.3.5.3) — the `CostBudget` rides the persona/cron config and reaches the decorator alongside the chain, never inside it (DR-4c DP-2). Its `chat(request)` iterates the chain: attempt primary, classify failures, fall through on provider-level faults (rate limit, timeout, 5xx, auth, model-not-found, connection, unknown — the next provider may succeed where this one cannot), stop on request-level faults (`InvalidRequestException`, request-level 4xx — a malformed request fails on every provider alike; DR-4c DP-6), and write one `provider_calls` row per attempted call with `is_fallback = 1` for calls past the first. The same decorator exists for `StreamingChatModel`, emitting tokens as soon as the first successful stream starts and retaining the fallback markers in telemetry.

Per-agent selection: the agent's `.json` names a primary model and a fallback list, resolved against `ModelProvider` beans at materialization time. Per-cron selection: each `crons/<cronId>.json` declares its own chain, independent of the invoked agent's default. This matters because a nightly summarization cron might want to pin a cheap local Ollama model for cost, while the same agent during interactive use fronts a premium hosted model for quality.

Classification of "retryable" is codified as a sealed `FailureClass permits Retryable, NonRetryable, Unknown` pattern — we never let "unknown" silently retry, because that has historically caused runaway spend. `Unknown` surfaces to an operator alert and is treated as non-retryable until a human classifies it. The central `FailureClassifier` classifies against Langchain4j's typed `dev.langchain4j.exception` hierarchy rather than string-matched HTTP codes, mirroring the core `ExceptionMapper` mapping: `RateLimitException`, `TimeoutException`, and `InternalServerException` (the 429 / 408 / 5xx surface) are `Retryable`; `AuthenticationException`, `ModelNotFoundException`, and `InvalidRequestException` (the 401/403 / 404 / other-4xx surface) are `NonRetryable`; the `LangChain4jException` root falls through to `Unknown` and the operator alert. The failing exception's FQCN is recorded in the existing nullable `provider_calls.error` column (§4.2) for later triage — no schema change is needed. Adding a new provider requires updating that classifier as part of the PR (enforced by a compile-time check on the sealed hierarchy).

### 5.5 LangGraph4j supervisor-workers graph

The Orchestrator-Workers topology from `CONTEXT-ENGINEERING.md` materializes as a `StateGraph` in `forvum-engine`. The graph is compiled once per main-agent turn and has the following nodes:

- **`route`** — a cheap local model (default `qwen3:1.7b` via Ollama) classifies the inbound message into: direct-answer, tool-loop, spawn-worker, or delegate-to-named-agent. This is the "small-and-fast" principle from `CONTEXT-ENGINEERING.md` applied to orchestration, not to final generation; routing is also the Select pillar at the orchestration layer, deciding which path and downstream context the turn needs before any expensive generation runs.
- **`generate`** — the direct-answer node; calls `AgentChatModel` and returns a final message.
- **`tool_loop`** — iterates tool calls until the LLM emits no more calls or the tool budget is exhausted. Each call goes through `ToolExecutor`, which enforces `PermissionScope` and `USER_CONFIRM_REQUIRED` hooks.
- **`spawn_worker`** — materializes a sub-agent via `AgentRegistry.spawn(parentId, spec)` with a narrowed tool belt and a child `CostBudget`. The sub-agent runs its own mini-graph inside its own `@AgentScoped` context.
- **`worker_run`** — drives the child agent's turn and returns the final message upstream.
- **`reduce`** — merges worker outputs into the main agent's context. When the combined size exceeds the agent's `MemoryPolicy` compression threshold, it routes the merge through the small-and-fast model (default `qwen3:1.7b`) for a structured summarization pass — the proxy-model Compress pattern from `CONTEXT-ENGINEERING.md`, landing at MVP (M18) in its summarization form and generalized to the standalone middleware in v1.0+ (§7.3 item 8). `reduce` is also the single sanctioned point where isolated worker contexts merge back into the parent, so the summarization pass doubles as the Isolate-defense boundary and the cross-agent-injection guardrail: only the compressed digest crosses, never a worker's raw window, which prevents sibling context clash and stops a poisoned worker output from injecting into the parent.

Workers spawned by `spawn_worker` execute in parallel on virtual threads (fan-out per §3.8); this is the latency rationale for the Orchestrator-Workers topology in `CONTEXT-ENGINEERING.md` — parallel specialist workers replace a single agent's serial cascade, and each worker's isolated window lets the engine use a smaller model at the edge. The `tool_loop` cap and per-agent `toolBudget` are enforced through Langchain4j's idiomatic `ToolArgumentsErrorHandler` / `ToolExecutionErrorHandler` hooks rather than ad-hoc try/catch around each call; the round cap binds to `maxToolCallingRounds` on the Langchain4j 1.16.1 core pinned via quarkus-langchain4j 1.11.0.CR2 (the property renamed from `maxSequentialToolsInvocations`); the legacy name applies only on the stable-1.10.0 / 1.14.1 fallback.

Conditional edges route between these nodes based on the sealed `AgentEvent` type emitted at each step. A turn ends when `generate` emits a `Done` event. The graph is strictly acyclic at the top level — we do not rely on LangGraph4j's cycle support there, but we do use cycles inside `tool_loop`.

### 5.6 Explicit contrast with JavaClaw

JavaClaw's `base/src/main/java/ai/javaclaw/channels/ChannelRegistry.java` maintains a global-mutable `lastChannel` field to thread channel identity through a turn. That works for single-user, single-agent deployments and is the exact anti-pattern Forvum refuses: global mutable state is incompatible with concurrent multi-agent turns. JavaClaw's `DefaultAgent` is 23 lines of single-agent single-LLM code; we supersede it with a registry-backed multi-agent `Agent` facade under `@AgentScoped`. JavaClaw has no scratchpad, no episodic memory, no per-cron LLM, no sub-agent spawn, no identity resolution across channels. Moving from JavaClaw to Forvum is a rewrite, not a port — and this is stated here so no contributor wastes time attempting a class-by-class migration.

---

## 6. Build Targets (Native-First; Fast-Jar for Development)

The GraalVM native binary is the **primary, mandatory** build target — the shipped product and the default acceptance gate. The JVM fast-jar is the development convenience and the only runtime that loads drop-in jar plugins; it is not a co-equal shipping target. Every milestone M1–M20 produces a native-buildable artifact and runs the native smoke path in CI on every pull request (§6.4, §10); the sole sanctioned carve-out is a *behavioral* native assertion skip, never a native-compile skip, justified in writing in the milestone's Verify block (the one defensible case today is M4 `WatchService` OS-polling semantics). The native binary is what the end-user installs, where cold-start latency is felt directly on every invocation, especially on the TUI channel.

### 6.1 JVM fast-jar

`mvn -f forvum-app package` produces `forvum-app/target/quarkus-app/` — the Quarkus fast-jar layout. Startup target is around one second on a warm laptop, resident memory around 200 MB. This target:

- Accepts runtime drop-in plugins under `~/.forvum/plugins/` discovered via `ServiceLoader`.
- Runs Dev UI at `/q/dev/` in dev mode (`mvn -f forvum-app quarkus:dev`), including Forvum-specific cards for live agent reload, a CAPR dashboard, and a provider-call inspector backed by `provider_calls`.
- Supports live-reload of Java sources during dev, which is critical when building new plugins or iterating on engine code.

This is the development target a contributor runs locally while building Forvum itself, and the JVM runtime a power user runs when they want to drop in a compiled third-party plugin without rebuilding the native binary end-to-end. It is a convenience layer over the shipped native product, not an alternative shipping target.

### 6.2 GraalVM native binary

`mvn -f forvum-app -Pnative package` produces a single executable at `forvum-app/target/forvum-app-<version>-runner`, built with **Mandrel 25.0.x-Final** (GraalVM CE 25, JDK 25-based) and **no `--enable-preview`** (§3.8). Startup target is under 200 ms, resident memory under 50 MB, single binary with zero JVM dependency at the end-user machine. This is the primary, mandatory target. It:

- Is what we ship and what end users install as a personal tool.
- Loads all plugins that were on the compile classpath at build time — not from `~/.forvum/plugins/`. The runtime drop-in path is a documented JVM-fast-jar-only architectural property, not a carve-out from the native mandate.
- Cross-compiles via Quarkus `container-build=true` on CI runners that lack a local GraalVM.
- Is smoke-tested on every pull request via `@QuarkusIntegrationTest`; the CI step fails if cold-start exceeds 200 ms.

The fast-jar and native targets share the same source tree and the same configuration surface. The only behavioral difference an end-user sees is the plugin-loading model described in §6.3. A user who wants a curated plugin set builds their own native binary from a fork; Forvum documents this flow explicitly so it is the obvious path rather than a surprise.

### 6.3 Native-first engineering discipline

Native compatibility is not retrofitted late. Every contribution is written as if native is the only target, and CI enforces it. Concretely:

- **No runtime reflection** outside framework-managed paths. Every DTO carries `@RegisterForReflection`, every JSON-serialized type is a record (canonical constructors are reflection-free), and every tool-specification lookup goes through a build-time-generated registry rather than class-path scanning.
- **Build-time plugin discovery.** `@ForvumExtension` plus `META-INF/forvum/plugin.json` manifests are scanned by a Quarkus `BuildStep` that records the set of contributed `ChannelProvider`, `ModelProvider`, `ToolProvider`, and `MemoryProvider` beans and emits the necessary reflection hints. `ServiceLoader` is a fallback for the fast-jar only and is not exercised in the native image.
- **No dynamic class loading** outside the drop-in plugin path, which is explicitly JVM-only.
- **Vetoed dependencies.** Any library that relies on `sun.misc.Unsafe`, runtime bytecode generation (CGLib, Javassist at runtime), or un-hinted reflection is excluded from the compile classpath via `<exclusions>` in `forvum-bom`. A CI check greps for banned imports (`sun.misc.Unsafe`, `net.sf.cglib`, `javassist.util.proxy`) and fails the build if any appear.
- **`@RegisterForReflection` audit.** A custom Maven enforcer rule walks the DTO packages and fails the build if a record is missing the annotation. Records do not technically need reflection for their canonical constructor, but Jackson and Langchain4j both use it for field access, so the annotation is mandatory and enforced. **This audit applies to DTO records in Quarkus-bearing modules (Layer 2+); `forvum-core` (Layer 0) is exempt.** Core bans `io.quarkus*` and cannot depend upward on `forvum-sdk`, so its records cannot carry the annotation — it lives in `io.quarkus.runtime.annotations`, and the SDK re-export (§2.2) sits *above* Layer 0, not below it. Layer-0 types that the engine serializes in native are instead registered from `forvum-engine` via a single `@RegisterForReflection(targets = { … })` holder enumerating the core records (compile-time-checked `.class` references, so a rename or removal breaks the build). That holder lands in the milestone that first serializes those types in native (M5/M6); M2 ships pure records with no annotation.
- **SQLite JDBC native loading.** Xerial `sqlite-jdbc` (≥ 3.40.1.0; pin the latest, currently ~3.53.x) ships its own native-image JNI configuration. The native profile sets `-Dorg.sqlite.lib.exportPath=${project.build.directory}` so the JNI library is exported at build time rather than embedded-then-extracted, with `org.sqlite.lib.path` pinned at runtime. This is a native-config requirement, not a blocker.
- **Flyway native resources.** `quarkus-flyway` migrations under `db/migration/` are registered as native resources; the native build includes only the SQLite migration SQL, so the image carries no unreachable dialect resources.
- **LangGraph4j reachability metadata.** LangGraph4j is a plain library, not a Quarkus extension, so it ships no build-time native hints. **As built (M18) no hand-authored metadata was needed:** `GraphState` is a *class* extending `org.bsc.langgraph4j.state.AgentState` (LangGraph4j's map-backed state contract forbids a record container) holding only serialization-safe `String`/`List<String>` control signals — the `ChatMessage` conversation lives in a per-turn holder, never in the graph state, because LangGraph4j serializes state via `ObjectOutputStream` on every step even with no checkpointer and langchain4j messages are not `Serializable`. The node/edge actions are `LambdaMetafactory` lambdas (`NodeAction`/`AsyncEdgeAction`; the JDK-dynamic-proxy branch in `node_async` fires only for `InterruptableAction`, which Forvum does not use), all build-time-reachable. The M18 local + CI native build is green with NO `META-INF/native-image/` metadata; the directory is added only if a future graph change reintroduces a reflection/serialization surface. The native graph smoke (the supervisor turn) must pass on both CI platforms (Risk #13).

### 6.4 CI matrix and distribution

GitHub Actions runs a matrix of `linux-amd64` and `macos-arm64` on every pull request. Native parity is mandatory, not selective: every matrix cell builds both JVM and native, and every milestone M1–M20 native-compiles and runs its native smoke path. A milestone may skip only a *behavioral* native assertion (never the native compile), with a written justification in its Verify block; the sole defensible case today is M4 `WatchService` OS-polling semantics. The real-provider native turn (Risk #5) is covered by a dedicated linux-only `native-turn` job that drives a real Ollama turn through `forvum ask` against the built binary, so a native-only provider regression cannot ship silently; for a provider whose native build genuinely fails, the native-first remedy (e.g. the REST `quarkus-langchain4j-ai-gemini` extension) is preferred over a JVM-only carve-out. Each matrix cell runs unit tests and integration tests, and executes a native smoke test that:

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
  - **Deps:** locks Java 25 (`maven.compiler.release=25`), Quarkus 3.33.x LTS platform BOM, Maven 3.9+.
  - **Verify:** `cd forvum && ./mvnw -N verify` succeeds on every module; `./mvnw -pl forvum-app -am package` produces `forvum-app/target/quarkus-app/quarkus-run.jar`.
  - **Owns (X7):** the multi-module reactor + pom + wrapper bootstrap only. The `forvum init` first-run command surface is **not** M1's — it ships with M20's picocli command-mode (`forvum init`; see M20 As-built deviation (a)); M4 (below) owns the on-disk `~/.forvum/` layout the scaffold writes (§6.2 scenario 2).
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

- [x] **M4 — Config loader with hot reload.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/config/ConfigLoader.java`, `ConfigWatcher.java`, `ConfigurationChangedEvent.java`, `ForvumHome.java`, plus Panache-less repository-style readers for each `~/.forvum/` subfolder.
  - **Deps:** `quarkus-core`, `quarkus-jackson`.
  - **Verify:** integration test uses `@TempDir`, writes a synthetic `~/.forvum/` layout, fires modifications, asserts `ConfigurationChangedEvent` observers receive the correct `path` and `type`.
  - **Owns (X7):** the `~/.forvum/` on-disk layout the `forvum init` scaffold writes (§4.1, §6.2 scenario 2) — the scaffold target; the `init` command surface itself ships with M20 picocli command-mode.
  - **Commit:** `feat(engine): add file-based config loader with WatchService`.

- [ ] **M5 — SQLite + Flyway V1.**
  - **Files:** `forvum-engine/src/main/resources/db/migration/V1__baseline.sql` (the DDL from §4.2), `forvum-engine/src/main/resources/application.properties` (JDBC URL pointing at `$FORVUM_HOME/state/forvum.sqlite`, WAL pragma, `quarkus.flyway.migrate-at-start=true`), `forvum-engine/src/main/java/ai/forvum/engine/persistence/` Panache entities for each table.
  - **Deps:** `quarkus-hibernate-orm-panache`, `quarkus-flyway`, `org.xerial:sqlite-jdbc` (added to `forvum-bom`), `org.hibernate.orm:hibernate-community-dialects` for the SQLite dialect.
  - **Verify:** `mvn -pl forvum-engine test -Dtest=SchemaSmokeIT` migrates a fresh file, inserts one row per table, and dumps `sqlite3 forvum.sqlite '.schema'` against a golden file.
  - **Commit:** `feat(engine): add SQLite persistence with Flyway V1 baseline`.

- [x] **M6 — `@AgentScoped` custom CDI context.**
  - **Files:** `forvum-core/src/main/java/ai/forvum/core/AgentScoped.java`, `forvum-engine/src/main/java/ai/forvum/engine/context/AgentContext.java` (`InjectableContext` impl), `AgentScopeExtension.java` (the CDI Lite `BuildCompatibleExtension` registering the context — see note), `CurrentAgent.java` (the `ScopedValue<AgentId>` + `ScopedValue<UUID> CURRENT_TURN`), plus `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`.
  - **Deps:** `io.quarkus.arc:arc` (already transitive); `jakarta.enterprise:jakarta.enterprise.cdi-api` (`provided`) added to `forvum-core` for the `@NormalScope` meta-annotation.
  - **As-built note:** the context is registered via a CDI Lite `BuildCompatibleExtension` (`MetaAnnotations.addContext`), **not** a deployment-module `@BuildStep` (no `AgentContextBuildItem`/`AgentContextProcessor`). A `@BuildStep` requires `forvum-engine` to become a runtime+deployment Quarkus extension, which would force its own `@QuarkusTest`s into a deployment module (reactor cycle) and break the M4 headless-library Surefire setup. The BCE path was validated to register the scope and survive native compilation in JVM and native, keeping `forvum-engine` a plain library (§5.1).
  - **Verify:** a dual-thread integration test binds two different `AgentId`s on two virtual threads concurrently, resolves the same `@AgentScoped` bean class on each, and asserts the two instances are distinct `System.identityHashCode`.
  - **Commit:** `feat(engine): add @AgentScoped CDI context backed by ScopedValue`.

- [ ] **M7 — `AgentRegistry` with `getOrCreate` and `spawn`.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/agent/AgentRegistry.java`, `AgentSpec.java`, `Agent.java` (the `@AgentScoped` facade), `AgentMemory.java`, `AgentToolBelt.java`, `AgentSpecReader.java` (parses `.md` + `.json`).
  - **Deps:** builds on M4 and M6.
  - **Verify:** seed `~/.forvum/agents/main.md` + `main.json`, call `registry.getOrCreate("main")` twice and assert the same `Agent` instance; call `registry.spawn("main", childSpec)` and assert a distinct child `AgentId` with a narrower tool belt.
  - **Commit:** `feat(engine): add AgentRegistry with file-driven agent creation`.

- [x] **M8 — `FallbackChatModel` + `FailureClassifier`.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/model/FallbackChatModel.java`, `FallbackStreamingChatModel.java`, `FailureClass.java` (sealed: `Retryable`/`NonRetryable`/`Unknown`), `FailureClassifier.java`, `FallbackLink.java` (engine-local chain link), `ProviderCall.java` + `ProviderCallRecorder.java` (write seam) + `PanacheProviderCallRecorder.java` (persistence impl).
  - **Deps:** `dev.langchain4j:langchain4j-core` (transitive via `quarkus-langchain4j-bom`).
  - **As-built notes:** (1) `FailureClassifier` keys on LangChain4j's `RetriableException`/`NonRetriableException` base types. (2) The `FallbackTriggered.reason → FailureClass` migration the original plan scheduled here is **declined** — `FailureClass` is the engine-local 3-way *retry* axis, `reason` stays the finer `FallbackReasons` String *telemetry* token (collapsing would lose `rate_limit`/`timeout`/`server_error` granularity); zero `forvum-core` change (see §4.3.2). (3) `forvum-core.FallbackChain` was TBD at M8 and is now settled (§4.3.5.3 / DR-4c): the engine-local `FallbackLink` list stays the resolved-runtime form; the core record is the config-time declaration, and the adapter is a mapping (`FallbackChain.links()` → resolved `List<FallbackLink>`) in `LlmSelector`, landing with the DR-8 spec composition — no decorator constructor change (amending the original "one constructor adapts" forecast).
  - **Verify:** unit test with a mock `ChatModel` that throws `RateLimitException` on the first call and returns on the second; assert `provider_calls` gets two rows and the second has `is_fallback = 1` (`ProviderCallPersistenceIT`).
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
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/tools/ToolRegistry.java`, `ToolExecutor.java`, `PermissionDeniedException.java`, `ToolFilter.java` (glob matching); the `forvum-sdk` `ToolProvider.tools()` SPI prelude (contribution-only, forvum-core types); `forvum-engine/.../model/ToolInvocation.java` + `ToolInvocationRecorder.java` + `.../persistence/PanacheToolInvocationRecorder.java` (write seam over the existing V1 `tool_invocations`); `AgentToolBelt` filtered `tools()`. `PermissionScope` is **consumed** from `forvum-core` (already exists, M2) — M13 does NOT create it, and adds NO migration (the `tool_invocations` table is V1/M5).
  - **Deps:** builds on M3, M7, and M5 (the existing `tool_invocations` table). Tools are not wired into `Agent.respond()` here — that is M18.
  - **Owns (X7):** the `forvum-tools-shell` tool (`shell.exec` + allow-list + `USER_CONFIRM_REQUIRED`, §2.4), the `SkillInvokerTool` skills surface (skills ARE tools, §4.1), and the `forvum-tools-mcp-bridge` baseline (flagged OFF in v0.1 per Risk #9, §2.4) — all ride the `ToolProvider.tools()` SPI this milestone establishes, so they are owned here, not unscheduled. The §3.6 OTel baseline + the `/q/dashboard/capr` endpoint fold into M18 instead.
  - **Verify:** register two synthetic tools (`a.read`, `a.write`), seed an agent with `allowedTools: ["a.read"]`, assert a call to `a.write` from that agent is refused with a `PermissionDeniedException` and logged in `tool_invocations` with `status = 'denied'`.
  - **Commit:** `feat(engine): add ToolRegistry with glob-based filtering and permission scopes`.

- [ ] **M14 — Filesystem tools.**
  - **Files:** `forvum-tools-filesystem/` module; `FilesystemToolProvider.java` (implements the M13 `ToolProvider.tools()` SPI); `FsReadTool.java` (`PermissionScope.FS_READ`), `FsWriteTool.java` (`FS_WRITE`), `FsListTool.java` (`FS_READ`); `WorkspaceRoot.java` + `WorkspaceEscapeException.java` (self-contained path confinement — the full DR-6a contract is deferred); manifest; the three append-only pom wirings (root `<modules>`, `forvum-bom`, `forvum-app`).
  - **Deps:** builds on M3 and M13 (the `ToolProvider.tools()` SPI). Among non-`java.nio` deps the module needs only `forvum-sdk` + `quarkus-arc` — no langchain4j (it copies the provider recipe minus the AI extension).
  - **Verify:** integration test against a `@TempDir`; read/write/list round-trip asserted; a write outside the configured workspace root is denied.
  - **Commit:** `feat(tools-fs): add filesystem read/write/list tools with FS permission scope`.

- [ ] **M15 — TUI channel (TamboUI headless render, no terminal backend in v0.1).**
  - **Files:** `forvum-channel-tui/src/main/java/ai/forvum/channel/tui/TuiChannel.java` (line-based stdin REPL + exhaustive `AgentEvent` render + foreground `run()`), `TuiChannelProvider.java` (discovery marker), `TuiView.java` (styled renderer via TamboUI's headless `Buffer`/`Paragraph`; `plain()` no-ANSI passthrough / `ansi()` content-width styled render), a `--no-ansi` (`forvum.no-ansi`) plain-stdout path; plus `forvum-app` wiring — `ForvumApplication` foreground dispatch + `ChannelLauncher.FOREGROUND_CHANNELS`. No custom native config and no terminal backend (see Decision); the pure-Java headless `Buffer` render is already native-proven (the app banner). (`tui.tcss`, the full-screen Toolkit TCSS theme, is **deferred** — see Decision.)
  - **Deps:** `dev.tamboui:tamboui-toolkit` + `tamboui-widgets` (managed by `tamboui-bom:0.3.0` in `forvum-bom`). **No terminal backend ships in v0.1:** both TamboUI 0.3.0 backends fail the GraalVM 25 native build — `tamboui-jline3-backend`'s `org.jline:jline` uber jar bundles a JNA provider (`JnaNativePty` → `com.sun.jna.Platform`, absent) that fails `--link-at-build-time`, and `tamboui-panama-backend`'s FFM downcall (`LibC.tcgetattr`) is rejected by native-image ("unexpected input … linkToNative"). A backend is only needed for terminal-size auto-detection, which v0.1 omits.
  - **Decision (M15, line-REPL, no terminal backend):** v0.1 ships a **line-based streaming REPL** (one non-blank stdin line = one turn, pipeable — matching the Verify), NOT a full-screen TamboUI Toolkit app. `TuiView` renders styled output through TamboUI's verified 0.3.0 headless `Buffer` API (the same pure-Java path as the app banner — no terminal backend, no native syscall), sized to the fragment's content (the terminal wraps long lines itself); the no-ANSI path bypasses TamboUI entirely to raw stdout. **Native-first forced the backend choice:** terminal-size auto-detection needs a native terminal syscall, and *both* TamboUI 0.3.0 backends fail the GraalVM 25 native build (jline3 = JNA link failure; panama = FFM `linkToNative` rejection). Since native is mandatory (§5), v0.1 omits the terminal backend entirely (ANSI at content width), keeping the binary fully native; terminal-width auto-detection and the full-screen Toolkit component tree + `tui.tcss` are deferred to a TamboUI version whose backend native-builds on GraalVM 25 (revisit at the TamboUI bump / M20). (Decision history: jline3 + Kernel32 carve-out → Panama FFM → no backend, each ruled out by a distinct native-image failure.)
  - **Verify:** an integration test pipes scripted stdin and asserts the rendered output contains the assistant's reply (`TuiScriptedTurnE2E`, in-process via redirected `System.in`/`System.out` through the real engine + in-process fake model); `forvum-app -Dforvum.no-ansi=true < input.txt` works identically (plain path); the native smoke renders a TamboUI frame (the app banner) within the §6.2 cold-start budget.
  - **Commit:** `feat(channel-tui): add TamboUI-based TUI channel with streaming rendering`.

- [ ] **M16 — Web channel (WebSockets Next).**
  - **Files:** `forvum-channel-web/src/main/java/ai/forvum/channel/web/WebChannel.java`, `ChatSocket.java` (WebSocket endpoint), `src/main/resources/META-INF/resources/index.html` (minimal chat UI), `chat.js`; manifest.
  - **Deps:** `quarkus-websockets-next`.
  - **Verify:** start dev mode, open `http://localhost:8080/`, exchange a message, see streamed tokens; a second browser tab gets a separate session id.
  - **Commit:** `feat(channel-web): add WebSockets Next chat channel with minimal UI`.

- [ ] **M17 — Telegram channel (long-poll).**
  - **Files:** `forvum-channel-telegram/src/main/java/ai/forvum/channel/telegram/TelegramChannel.java`, `TelegramBotApi.java` (REST client), `UpdateProcessor.java`; manifest.
  - **Deps:** `quarkus-rest-client`, `quarkus-rest-client-jackson` (blocking client; invoked on a virtual thread per §3.8).
  - **Verify:** with a Telegram bot token in the keychain, a live DM produces an assistant reply within the turn latency budget; `allowedUserIds` in `channels/telegram.json` refuses other users with a friendly message.
  - **Commit:** `feat(channel-telegram): add long-poll Telegram channel`.

- [x] **M18 — LangGraph4j supervisor graph.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/graph/SupervisorGraph.java` (the `StateGraph` compiler), node implementations for `route`, `generate`, `tool_loop`, `spawn_worker`, `worker_run`, `reduce`, `GraphState.java`.
  - **Deps:** `org.bsc.langgraph4j:langgraph4j-core` (and the Langchain4j integration module).
  - **Owns (X7):** the §3.6 OTel baseline (the four `forvum.*` spans emitted per turn/graph node) and the `/q/dashboard/capr` CAPR endpoint — both surface once the supervisor turn produces graph-node + provider-call + CAPR rows, so M18 is their owning milestone (the OTLP *export* is the Phase-2 P2-15 follow-on).
  - **Verify:** a multi-tool scenario ("fetch X then summarize") routes through `tool_loop` -> `generate` and produces the expected final message; CAPR event written for the turn.
  - **Commit:** `feat(engine): add LangGraph4j supervisor-workers orchestration`.
  - **As-built note (CAPR endpoint, X7 → M18; X6 scenario 10).** The §3.6 `/q/dashboard/capr` dashboard endpoint — which X7 placed under M18 acceptance — ships as `forvum-app` `CaprDashboardRoute`: a minimal `quarkus-reactive-routes` `@Route` returning the `capr_events` rows as JSON (see §3.6 as-built). Server-path-only (no command-mode cold-start impact), `@Route` over the existing `vertx-http` (no `quarkus-rest`, so `HttpClientFactorySelector`/the REST-client stack are untouched). Guarded by `CaprDashboardE2E` (five turns → `GET /q/dashboard/capr` returns ≥ 5 rows).

- [x] **M19 — Quarkus-scheduler + crons.**
  - **Files:** `forvum-engine/src/main/java/ai/forvum/engine/cron/CronScheduler.java` (registers `@Scheduled` programmatically from `~/.forvum/crons/*.json`), `CronSpec.java`, `CronTrigger.java`.
  - **Deps:** `quarkus-scheduler`.
  - **Verify:** a cron firing every minute with a `FallbackChain` pinned to Ollama triggers an agent turn and writes to `messages`, `provider_calls`, `capr_events`; adding a new cron file triggers reload without restart.
  - **Commit:** `feat(engine): add file-driven cron scheduler with per-cron LLM chain`.

- [x] **M20 — GraalVM native image + CI matrix.**
  - **Files:** `forvum-app/src/main/resources/application.properties` (native-specific flags), `.github/workflows/ci.yml` (matrix: `linux-amd64`, `macos-arm64`; JVM and native builds; native smoke test with 200 ms cold-start gate), `Dockerfile.jvm`, `Dockerfile.native`.
  - **Deps:** `quarkus-container-image-docker`; GraalVM CE 25 / Mandrel 25.0.x-Final on runners.
  - **Verify:** `mvn -f forvum-app -Pnative package -Dquarkus.native.container-build=true` succeeds on a clean CI runner; `./forvum-app-<version>-runner --help` prints help in < 200 ms measured from process start.
  - **Owns (X7):** the `forvum init` first-run command surface (picocli command-mode `--help`/`--version`/`init`) — see As-built deviation (a); it scaffolds the M4-owned `~/.forvum/` layout (§6.2 scenario 2).
  - **Commit:** `feat(app): add GraalVM native image profile and CI matrix`.
  - **As-built deviations:** (a) the cold-start lever is picocli command-mode (`--help`/`--version`/`init`) + a `CommandMode` one-shot detector that makes the DB/watcher/cron startup observers skip their work — native `forvum --help` measures ~45 ms (gate met). (b) `quarkus-container-image-docker` was deliberately **not** adopted: hand-authored `Dockerfile.jvm`/`Dockerfile.native` are simpler and CI never builds an image (it runs `forvum --help` on the bare runner). (c) one-shot commands leave HTTP **unbound**: `ForvumApplication.main` sets `quarkus.http.host-enabled=false` (read at RUNTIME_INIT, before `run()`) when the args name a one-shot, so they pay neither the bundled Web channel's `vertx-http` bind nor a free-port requirement. (Separately, the macOS CI cell paid a fixed ~5 s `InetAddress.getLocalHost()` stall at startup — OpenTelemetry's host-resource detector + the Vert.x address resolver call it, independent of the listener — so the workflow makes the runner's hostname resolvable; real Macs resolve fine.) (d) **Risk #5** (a real-provider native scripted-turn smoke) is now automated, not deferred: a linux-only `native-turn` CI job builds the binary and drives a real Ollama turn through the new `forvum ask` command (`OllamaNativeTurnIT`, a `@QuarkusMainIntegrationTest @Tag("live")` asserting exit 0 + a non-blank stdout reply) against an `ollama/ollama` service running `qwen2.5:0.5b` — closing the gap PR #111 fell through. The two-cell `native` job (boot smoke + 200 ms cold-start) stays mandatory on both cells; the `native-turn` job is linux-only because the macOS runner cannot host the service container.

### 7.2 Phase 2 — v0.5 (parity with OpenClaw)

Goal: match OpenClaw's feature set so a user currently on OpenClaw can migrate to Forvum without losing capability. Each item ships as its own module or engine submodule and is gated on the MVP being stable.

1. **Browser tool.** Headless-browser `web.browse` tool (Playwright Java) with `PermissionScope.WEB_BROWSE`; delivered as `forvum-tools-browser`.
2. **Code-execution sandbox.** A `shell.exec` replacement that runs code in a container or Firecracker microVM; delivered as `forvum-tools-sandbox`.
3. **Voice channel.** Local TTS/STT (Whisper + Piper) streaming channel; delivered as `forvum-channel-voice`.
4. **Device pairing.** Pair a phone or second device to an existing Forvum instance, reusing identity and memory; delivered as `forvum-engine/pairing`. The engine submodule (P2-4) is "fixed code, configurable behavior": a device is paired by dropping a `$FORVUM_HOME/devices/<id>.json` (`identityId` + an optional `token` shared-secret + an optional `revoked` flag) — NO SQL table and NO Flyway migration (mirrors the `roles/`/`agents/` registries). A Layer-2 `Device` record (`@RegisterForReflection`), a `DeviceSpecReader` typed binder, and a `DeviceRegistry` (`@ApplicationScoped`, `ConcurrentMap` cache with IO off the lock + `@Observes ConfigurationChangedEvent` hot-reload) resolve a device endpoint id to its paired identity. Enforcement is at the turn entry `TurnService.dispatch`, keyed by `channelId` (the device endpoint), BEFORE the responder runs: an unknown or `revoked` device is rejected with `DeviceNotPairedException` (surfaced as the turn's terminal `ErrorEvent`). A paired device's `identityId` is RECORDED in its `devices/<id>.json` (the CLI/management surface in item 19 reads it); namespace sharing itself is delivered by the existing `IdentityResolver` channel-account mapping — `TurnService` resolves the session identity from `IdentityResolver`, so a device reuses an identity's memory namespace by mapping its `(channelId, nativeUserId)` to that identity, not via the `Device` record. Like RBAC, pairing is opt-in (an empty/absent `devices/` disables the guard — backward compatible, no migration), and the distinguished built-in `cron`/`server`/`cli` devices are always paired (exempt, mirroring item 11's `cron` role): the local operator CLI (`forvum ask`, channel `cli`) is the host's inherently-trusted primary surface, so enabling pairing never locks out the operator's own terminal. (`cron` turns never reach `TurnService.dispatch` — `CronScheduler.fire` calls `agent.respond` directly — so `cron` is exempt by construction; the `cron`/`server` `EXEMPT` entries are a defensive belt.) CLI surface (`forvum pair`/`forvum devices`), scope-upgrade approval, and `forvum doctor` drift detection are item 19 (P2-PAIR-SCOPE).
5. **Memory-host SDK.** Public SPI for third-party `MemoryProvider` implementations (Redis, Qdrant, Chroma); documented plus reference implementation. *Landed:* DR-5 settles the Select-pillar contract in `forvum-core` (`MemoryPolicy(strategy, tiers, topK, minScore, compressThresholdChars)` + `defaults()`, `RetrievalStrategy`, `MemoryTier`, `MemoryQuery`, `MemoryHit`), and the sealed `MemoryProvider` gains `List<MemoryHit> retrieve(MemoryQuery, MemoryPolicy)` (blocking on a virtual thread, Quarkus-free, driven by the policy's strategy/tiers/topK/minScore). The reference impl is `forvum-provider-memory-qdrant` — a native-clean Layer-3 module against Qdrant's REST API (`points/search` for vector/hybrid, `points/scroll` for the embedding-free METADATA path) via `quarkus-rest-client-jackson`, using a documented deterministic reference embedding (operators supply a real model). Bundled in `forvum-app` so it native-COMPILES but inert unless an operator enables `memory/qdrant.json`.
6. **Maven plugin marketplace.** A `forvum plugin install <coords>` command that resolves a Maven coordinate, writes it to `~/.forvum/plugins/`, and triggers a fast-jar restart. Native users are told to rebuild. As built: the engine `MavenPluginResolver` resolves `groupId:artifactId:version` via Apache Maven Resolver (`maven-resolver-supplier`, the no-DI bootstrap — no Guice/CGLib on the classpath) against the user's `~/.m2/repository` cache + Maven Central and streams the resolved JAR into `~/.forvum/plugins/` (created if absent; `Files.copy`, no full in-memory buffer). The `forvum-app` `PluginInstallCommand` (`plugin install`, a `CommandMode` one-shot — it only resolves + writes files) prints a restart instruction in the fast-jar; on a native binary (`ImageMode.NATIVE_RUN`) it still stages the JAR but warns it takes effect only after rebuilding a native binary that depends on the coordinate — the drop-in path is JVM-fast-jar-ONLY BY DESIGN (§6.2/§6.3), not a native carve-out. The resolver classes ride the native classpath but never run there: the `@ApplicationScoped` resolver is lazy (never instantiated unless `install()` runs), references the Aether types only inside method bodies, does no `@Startup` work, and registers nothing for reflection.
7. **Skill marketplace.** A `forvum skill install <url>` that adds a `skills/<skill>.md` from a git repo or gist.
8. **Session replay.** A CLI command that replays a session from `messages` with the original tool outputs, used for debugging and regression. v0.5 reproduces the *recorded* transcript — the session's `messages` interleaved with their `tool_invocations`, oldest first — and does not re-invoke the model (re-execution with substitution is item 9 below / P3-9). The merge is turn-logical, not raw `created_at`: a turn's user+assistant pair is committed at turn-end (M7 persist-after-success) while a tool is ledgered mid-turn, so each tool is surfaced between the user message and the assistant reply of its turn. Delivered as `forvum-engine/.../replay/SessionReplayer` (+ `ReplaySession`/`ReplaySegment` view records, no `@RegisterForReflection` — built from JDBC rows and printed, never serialized) and a `forvum-app` `SessionReplayCommand` (`forvum replay <sessionId>`); unlike `doctor` it reads the SQLite DB, so it boots the full Flyway/Panache path rather than the `CommandMode` one-shot path.
9. **Config doctor.** `forvum doctor` validates the entire `~/.forvum/` layout and surfaces problems with actionable hints, exiting non-zero on any error. v0.5 validates by reusing the M4 readers and the engine's own typed binders (`AgentSpecReader`/`CronSpecReader`) as the validation oracles — plus cross-reference checks (a model ref must resolve to an installed provider; a cron's `agentId` must name a known agent) — rather than against standalone JSON Schemas, so doctor can never drift from how the engine actually parses config (maintainer-signed-off; formal JSON Schemas remain a documented fast-follow). Delivered as `forvum-engine/.../doctor/ConfigDoctor` + a `forvum-app` `DoctorCommand` (a `CommandMode` one-shot, so it skips the DB/watcher/cron boot).
10. **Provider onboarding wizard.** `forvum provider add anthropic` walks the user through keychain entry, default fallback-chain update, and a smoke-test turn.
11. **RBAC on tools.** `identities/<id>.json` declares `roles`; each role maps to a `Set<PermissionScope>`; a role-restricted identity is denied a tool whose required scope is outside its roles' union — even when the belt allows it — and the denial is audited (`status='denied'`). Delivered as the Layer-0 `RoleSpec` record + an additive `Identity.roles` list (reflection-registered via the engine holder, §6.3), an engine `RoleRegistry` resolving role → scopes ("fixed code, configurable behavior": built-in permissive `default-user` = every scope, and restricted read-only `cron`, each overridable by `$FORVUM_HOME/roles/<name>.json` with M4 `WatchService` hot-reload), and a second gate in `ToolExecutor` reading effective scopes from a new `CurrentIdentity.CURRENT_EFFECTIVE_SCOPES` `ScopedValue` — bound at every turn entry (`TurnService.dispatch` resolves the identity's roles via `IdentityResolver.rolesFor`; `CronScheduler.fire` binds the distinguished `cron` role). An identity that declares no roles gets the permissive default (RBAC is opt-in restriction; backward compatible, no migration); a caller outside a turn entry leaves the binding unset and is gated by the belt alone (every production turn entry binds it, so the gate is always active in production). No new `PermissionScope` constants and no Flyway migration. Mirrors OpenClaw v2026.4.19-beta.2's owner/non-owner + restricted-cron model, expressed in Forvum's capability-scope vocabulary.
12. **Structured output schemas per agent.** Agents declare an optional `outputSchema` (a JSON Schema) in `agents/<id>.json`; the `SupervisorGraph` parses the final assistant message as JSON and validates it against that schema. Delivered as an optional, backward-compatible `Persona.outputSchema` (a JSON-Schema STRING carried on the Layer-0 record — `null` = current free-text behavior, a present-but-blank value rejected; already in the §6.3 reflection holder), `AgentSpecReader` parsing it (an embedded object is re-serialized; a string taken verbatim), `GraphTurnRequest.outputSchema`, and a pure-Java `forvum-engine/.../graph/OutputSchemaValidator` that decodes the reply and validates it, re-serializing the validated `JsonNode` as canonical JSON on success. On failure (not valid JSON, missing required field, or a property of the wrong primitive type) the graph throws a `SupervisorGraphException` NAMING the schema and the offending field, which `TurnService` surfaces as a terminal `ErrorEvent` — no retry. **Decision (maintainer-locked):** JSON-Schema → `JsonNode`, NOT a typed POJO + LangChain4j `@Description`/`@StructuredPrompt` — a per-agent output class would force runtime reflection / classpath class loading and break the native binary; the string-schema + tree-walk path keeps it native-clean and config-driven (§5). The validator covers the v0.5-parity subset (root `type`, `required`, and each declared property's primitive `type`); full JSON-Schema-draft validation (nested schemas, `enum`, `allOf`/`anyOf`/`oneOf`, `format`/`pattern`) is a documented fast-follow that must first prove a JSON-Schema library native-compiles. A spawned worker child does not inherit the schema (its output is a digest merged as a tool result, never the validated top-level answer).
13. **MCP server registry enrichments.** `forvum mcp add <url>` and `forvum mcp list`; remote MCP tools appear in `ToolRegistry` within seconds.
14. **User-approval queue UI.** Dev UI and web-channel cards that show pending `USER_CONFIRM_REQUIRED` tool calls and let the user approve or reject them. Ratified expanded scope (§9.2.5): #39 (this wave) delivers the engine `USER_CONFIRM_REQUIRED` machinery + the SQLite-persisted blocking approval queue every shell/sandbox invocation parks through (timeout→deny), the web-channel approval card, and the dashboard route.
15. **Telemetry export.** OpenTelemetry OTLP exporter on by default when `OTEL_EXPORTER_OTLP_ENDPOINT` is set; default off; zero-config path for Honeycomb, Grafana Tempo, and Datadog.
16. **Additional first-party channels.** Discord, Slack, WhatsApp, Matrix, and Signal as `forvum-channel-*` modules. `forvum-channel-discord` ships first (a hand-rolled Discord Gateway v10 client over `quarkus-websockets-next` in CLIENT mode + a blocking `quarkus-rest-client-jackson` reply path — no JDA/Discord4J, both native-broken/reactive; the persistent-WebSocket gateway pattern is the template the remaining socket-based channels reuse). The long tail (iMessage/BlueBubbles, Teams, Google Chat, Mattermost, Feishu, LINE, QQ, Zalo/ZaloUser, IRC, Nostr, Tlon, Twitch, Synology Chat, Nextcloud Talk, telephony voice-call) is explicitly out of v0.5 scope and is community-plugin territory delivered through the item 6 marketplace.
17. **GitHub Copilot model provider.** `forvum-provider-copilot` against Copilot's OpenAI-compatible endpoint, with Copilot OAuth/device-code authorization wired into the provider onboarding wizard (item 10).
18. **QA scenario suite.** `forvum qa suite` / `forvum qa <channel>` runs a scripted scenario pack against the running assistant; fails by default on any unverified scenario. The scenario pack ships in the release and the suite is a CI gate.
19. **Device pairing with scope-upgrade approval.** Extends item 4: a paired device requests a `PermissionScope` set, the owner approves or rejects with reason codes, and requested-vs-approved scopes are visible in the Dev UI and `forvum devices`. `forvum doctor` surfaces requested-vs-granted drift.
20. **Session compaction.** When a session approaches the model's context window, compaction caps a reserve-token floor, mutates the oldest turns first to preserve the cached prefix, and strips orphaned reasoning/tool blocks. This is the Context Engineering Compress pillar (§1.4, §2.7) realized on the live session window. *Realized (P2-COMPACT):* `forvum-engine/.../session/compaction/SessionCompactor`, called eagerly from `TurnService.dispatch` (a `CompactionPolicy{reserveFloorTokens, retainTokens}` from `forvum.compaction.*` config) so the agent always reads a pre-compacted window. The cached prefix is the id-ordered head `id <= sessions.cached_prefix_end_index` (nullable; never read or mutated by a pass); a pass retains the most-recent turns within `retainTokens`, folds the older turn-messages into one summary via the injectable `Summarizer` (default reuses the §1.4 small-and-fast `ollama:qwen3:1.7b` through `LlmSelector`, the same model as the M18 `reduce` node), and the summary RECLAIMS the oldest dropped message id so it joins the frozen prefix in id-order and `cached_prefix_end_index` advances monotonically. A `messages.block_type` discriminator (`turn_message` | `turn_reasoning` | `turn_artifact` | `tool_execution`) drives orphan stripping: reasoning/artifact and stale tool-execution blocks older than the oldest retained user message are deleted, connected tool-execution blocks retained. CAPR is not regressed — `capr_events.is_archived` marks verdicts for dropped assistant turns; rows are never deleted.
21. **Detached task runtime registration.** A `TaskExecutor` *sink* SPI in `forvum-sdk` (a plain interface — the engine's `TaskRecorder` bean is the sole implementor; plugins do not implement it, as it is not a sealed provider in the channel/model/tool/memory hierarchy) plus a SQLite `tasks` ledger (Flyway `V2__tasks.sql`) that unifies cron entries, sub-agent runs, and background tasks under one queryable record. Writes are persist-after-success: `CronScheduler.fire` records a `cron` task after a turn (mirroring `provider_calls`), and `AgentRegistry.spawn` — the single chokepoint every sub-agent spawn flows through, including the M18 supervisor `DefaultWorkerRunner` — records a `sub_agent` task after a successful spawn. Operators query the ledger via direct SQL (no query DSL in v0.5).
22. **Cron isolated-agent delivery modes.** `delivery.mode: none | last | explicit-to` on cron entries, with per-execution dedupe and ambiguous delivery rejected at add/update time. Folds into the item 11 RBAC `cron` role. *As-built (P2-CRON-DELIVERY):* the `delivery` block parses to a typed `Delivery` whose canonical constructor rejects the mode↔target ambiguity, and `CronSpecReader` rejects an `explicit-to` target that is not a configured channel (the `channels/<id>.json` set) — both at PARSE, so `CronScheduler` disables the bad cron and `forvum doctor` surfaces it. Routing is inline in `CronScheduler.fire()` after a successful turn, once per fire (in-execution dedupe; no table/migration), through a `CronDeliverySink` seam. Because the channel SPI is a pure build-time discovery marker (M16 Resolution B) with no outbound send API, `last`/`explicit-to` deliver to the isolated-agent result sink (logged) rather than a live channel session; a later outbound channel-send surface backs the sink without changing the cron contract.
23. **`OutputGuard` SPI.** An outbound sensitive-data (secret/PII) filter on every channel egress surface — the v0.5 realization of the §1.4 outbound-filter promise. The contract is authored in §9.2 (the `OutputFilter` SPI shape, the sealed `FilteringOutcome` disposition `Allowed`/`Redacted`/`Blocked`, the `FallbackReasons.FILTERED` token, the engine-local `OutputFilteredException`, and the pre-channel-emit hook layer); this item implements it. Delivered as `forvum-sdk/.../OutputGuard.java` + `AbstractOutputGuard` + engine enforcement (P2-OUTPUTGUARD #48).

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

1. **Quarkus ArC `InjectableContext` build-time registration in native image.**
   - **Context:** The custom `@AgentScoped` context is implemented via the ArC SPI, registered through a build-time `BuildStep`. `ScopedValue` (JEP 506) is **final** in Java 25 — a permanent standard API needing no `--enable-preview` and no preview-gated native flag — so the residual native risk is purely whether the ArC `InjectableContext` and its reflection hints are generated correctly at build time for the native image.
   - **Mitigation:** A spike during M6 validates the full path (bind `ScopedValue.where(KEY, v).call(body)`, inject the `@AgentScoped` bean, unbind) in both JVM and native, exercising the ArC build-step registration. No `ThreadLocal`/preview-flag fallback is contemplated; the build stays `--enable-preview`-free.
   - **Decision trigger:** M6 CI green on both JVM and native; a two-thread test asserts per-agent isolation. If the ArC build-step path is red in native, file a Quarkus issue and resolve before M6 ships (it is a native-mandatory milestone).

2. **`sqlite-vec` native-image compatibility.**
   - **Context:** `sqlite-vec` is a C extension loaded as a shared library. Native-image static linking varies by platform.
   - **Mitigation:** v0.1 uses linear scan; `sqlite-vec` is a v1.0+ dependency only. This keeps the MVP decoupled from the risk.
   - **Decision trigger:** at v1.0+ scoping, benchmark linear scan at realistic row counts (10k, 100k, 1M). If linear is acceptable at 100k, defer indefinitely.

3. **Sealed interfaces and CDI bean discovery.**
   - **Context:** ArC's bean discovery historically matches concrete classes; sealed hierarchies with `non-sealed` leaves are unusual.
   - **Mitigation:** Plugins implement the `non-sealed abstract AbstractXProvider`, which is a concrete class from ArC's perspective. M3 includes a compile-time test that asserts this contract.
   - **Decision trigger:** M3 passes on native; if ArC emits warnings about sealed interfaces we investigate before M7.

4. **LangGraph4j version stability.**
   - **Context:** LangGraph4j is a stable 1.8.x release (pin `langgraph4j-core:1.8.17`); it is no longer pre-1.0, but minor-version API drift within the 1.8.x series is still possible.
   - **Mitigation:** Pin the exact version in `forvum-bom`. Keep the engine's coupling to LangGraph4j concentrated in `forvum-engine/src/main/java/ai/forvum/engine/graph/` so an upgrade or a replacement is a module-local change.
   - **Decision trigger:** if LangGraph4j breaks API twice within a v0.1 → v0.5 cycle, evaluate replacing it with a small in-house `StateGraph` implementation on the same `AgentEvent` sealed type.

5. **Quarkiverse `quarkus-langchain4j-*` native readiness per provider.**
   - **Context:** Extensions vary in their native-image readiness; Ollama, OpenAI, and Anthropic are well-exercised; the Vertex AI Gemini gRPC/Google-auth stack is less so under native.
   - **Mitigation:** A real-provider native scripted-turn smoke is automated in CI: a linux-only `native-turn` job builds the binary and drives a real Ollama turn through the `forvum ask` command against it (`OllamaNativeTurnIT`, `@Tag("live")`, `qwen2.5:0.5b`), gating every PR so a native-only provider regression cannot ship silently — the gap PR #111 fell through. (The originally-planned per-provider M9–M12 native turn was never realized: those `*ScriptedTurnE2E` are JVM `@QuarkusTest`, not native ITs; one linux-only Ollama turn now covers the native turn path, since an Ollama CI service is linux-only.) For a provider whose native BUILD genuinely fails, the preferred remedy is switching to a native-first extension (e.g. Vertex/Gemini → REST `quarkus-langchain4j-ai-gemini`) rather than carving the provider out to JVM-only.
   - **Decision trigger:** a provider's native build (or the `native-turn` smoke) red for two weeks → first attempt the REST-extension remedy where one exists (Vertex → `ai-gemini`); only if no native path exists, file an upstream issue and mark the provider JVM-only in release notes with that issue linked.

6. **TamboUI / JLine 3 on Windows under GraalVM.**
   - **Context:** The TUI runs on the TamboUI `jline3-backend`, and JLine 3 has edge cases on Windows consoles (especially legacy cmd.exe) that differ from macOS/Linux. (The `tamboui-panama-backend` is an alternative if the JLine backend proves problematic on Windows.)
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
    - **Context:** Xerial SQLite JDBC uses `synchronized` JNI native methods, which pin virtual threads. The engine runs on virtual threads end-to-end (§3.8); without mitigation, every Hibernate transaction blocks a carrier thread. This is a runtime virtual-thread-pinning concern only — it is independent of native-image viability, which compiles fine either way (§6.3).
    - **Mitigation:** M5 chooses among (a) a managed platform-thread executor for transactions, (b) explicit `@Blocking` posture on Hibernate-bound code paths, or (c) a loom-friendly JDBC driver if one becomes available. The selection is recorded back into §3.8 once locked.
    - **Decision trigger:** an M5 spike measures pin events under steady-state load (a synthetic 100-turn run hitting `messages` and `provider_calls`); the option that produces zero unbounded pins wins. If all three options have unbounded pin events, ship the least-bad option in v0.1 and file an issue capping the regression.

12. **Reactive client in the Telegram channel — resolved by the virtual-threads-first principle.**
    - **Context:** the obvious Quarkus choice for the Telegram Bot API would be `quarkus-rest-client-reactive` (Mutiny-based), which would pull a reactive programming model into a channel the engine consumes on virtual threads.
    - **Resolution:** per §3.8, the Telegram channel uses a **blocking** REST client on a virtual thread (`quarkus-rest-client` with `@RunOnVirtualThread`, or `java.net.http.HttpClient`) and hands `ChannelMessage` records (only `forvum-core` / `forvum-sdk` types) to the engine — so there is no Mutiny in the Telegram path and no bridging seam to maintain. Should a future source genuinely force a reactive boundary, it is confined and `await()`-bridged to a virtual thread at that boundary, with a one-line justification at the call site.
    - **Guard:** Mutiny is kept out of channel/engine source by the M5/M6 source import-grep (with an allowlist of justified boundaries), scanning for the Java package `io.smallrye.mutiny` — **not** a `bannedDependencies` rule, since Mutiny ships transitively with Quarkus ArC (artifact `io.smallrye.reactive:mutiny`) and a transitive classpath ban would break the build.

13. **LangGraph4j native reachability metadata.**
    - **Context:** LangGraph4j is a plain library, not a Quarkus extension, so it ships no build-time native hints. The `StateGraph`, its node actions, and (if a checkpointer is used) the `ObjectOutputStream`-serialized graph state can use reflection/serialization that native-image cannot infer automatically.
    - **Mitigation (as resolved at M18):** the design sidesteps the reflection/serialization surface rather than papering it with hints. `GraphState` holds only `String`/`List<String>` (a class extending `AgentState`, NOT records — the map-backed contract forbids a record container), so no graph-state type needs `@RegisterForReflection`; the `ChatMessage` conversation is kept in a per-turn holder out of the serialized state; the graph runs without a checkpointer (no `ObjectStream` value surface); and the node actions are `LambdaMetafactory` lambdas (`node_async`'s `Proxy.newProxyInstance` branch fires only for `InterruptableAction`, which Forvum does not implement). The M18 local + CI native build is green with NO hand-authored `META-INF/native-image/` metadata. Coupling stays concentrated in `engine/graph/` (Risk #4).
    - **Decision trigger:** the native graph smoke (the supervisor turn at M18) is green on both `linux-amd64` and `macos-arm64`. If a future change reintroduces a reflection/serialization surface and the smoke goes red, the missing metadata is added before that change is on the default native path; it does not regress the native-mandatory gate.

14. **TamboUI maturity (pre-1.0).**
    - **Context:** TamboUI is a young library (0.3.0, announced 2026-02) on a pre-1.0 line; API drift between 0.x releases is possible, and the TUI is the channel where native cold-start is felt most.
    - **Mitigation:** Pin the exact version via `tamboui-bom` in `forvum-bom` (§2.1) and concentrate all TamboUI coupling in `forvum-channel-tui`. TamboUI runs on the `jline3-backend`, so the escape hatch is a raw JLine 3 REPL in the same module (a module-local change); the `tamboui-panama-backend` is the alternative if the JLine backend is the problem.
    - **Decision trigger:** if TamboUI breaks API twice within a v0.1 → v0.5 cycle, or its native build regresses, fall back to a raw JLine 3 renderer in `forvum-channel-tui` (TamboUI is confined to that module by design).

---

## 9. Security

> **Status: DESIGN-ROUND DRAFT for maintainer sign-off (DR-6a, #59).** This section authors the threat model and the outbound-filter contract that the rest of EPIC-DR and the security-test layer depend on. The six open Group-6a design points are settled below and flagged inline as **[DP-n]**; they are summarized for ratification in the DR-6a issue (#59). Until the maintainer ratifies them, treat the named SPI shapes, exception types, and outcome subtypes as proposed-and-pending, not as locked contracts. The corresponding source artifacts (`OutputFilter`/`OutputGuard` SPI, `FilteringOutcome`, exception types, the `forvum-app/.../security/` negative-test layer) land milestone-by-milestone in P2-OUTPUTGUARD (#48), TEST-SEC (#65), and the per-milestone security amendments — not in this docs-only design round.

Security in Forvum is **structural, not bolted-on**: the same primitives that make the architecture observable and isolated (`PermissionScope`, `@AgentScoped` isolation, the `turn_id`-correlated ledger, the single egress policy) are the security primitives. This section makes the §1.4 governance bullet — *"every tool carries a `PermissionScope`, user-approval hooks gate destructive actions, and outbound outputs can be filtered for sensitive data"* — concrete: it upgrades that principle from a promise to a named threat model (§9.1) and a named contract (§9.2). It is the architectural home the Context-Engineering **Guardrails** pillar (`CONTEXT-ENGINEERING-MAPPING.md` §"Governance, permissions, and security (Guardrails)"; `CONTEXT-ENGINEERING.md` REQ #2) maps onto.

### 9.1 Threat model (STRIDE by surface)

The threat model is organized **by surface**, one threat-set per attackable surface, rather than as a flat STRIDE table — each surface has a distinct trust boundary and a distinct mitigation owner. The five surfaces below are exactly the attack surfaces that touch the agent runtime; surfaces outside the runtime (plugin trust, MCP-server trust, audit retention, supply chain, privacy) are deliberately carved out: plugin, MCP-server, and skill trust are settled in §9.3 (DR-6b, #60); audit retention, the build-input supply chain, and privacy remain with DR-6c (#61, §9.4) — all out of scope here.

For each surface we name the relevant STRIDE categories (**S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of service, **E**levation of privilege), the concrete threat, and the mitigation. A mitigation tagged **[built]** already exists in merged code; **[contract]** is specified in §9.2 and implemented by a downstream issue.

#### 9.1.a Tool-spec design — can a malicious or over-broad tool spec escalate?

- **Trust boundary.** Tool specs are **author-authored**, contributed by first-party `ToolProvider` plugins at build time (§5.3); they are never user-derived, never dynamically assembled, and never synthesized from model output. A `ToolSpec(name, description, requiredScope, parametersJsonSchema)` is a `forvum-core` record (§5.3) frozen on the compile classpath of the native binary.
- **E (Elevation of privilege) — over-broad `requiredScope`.** A tool that declares a *weaker* scope than the capability it actually exercises (e.g. an `fs.write`-capable tool that declares only `FS_READ`) would let an agent whose belt grants `FS_READ` invoke a write. **Mitigation:** scope declaration is a code-review obligation on the owning plugin PR (the scope is a literal in the tool's source, native-frozen), backed by the per-milestone security negative test (§10, TEST-SEC) that a tool's *required* scope is the *strongest* scope it can reach. There is no runtime synthesis path that could widen a spec, so the only injection vector is a reviewer missing an under-declared scope — a code-review control, not a runtime one. **[DP-1: tool specs are a closed, author-authored set; no runtime spec assembly. RATIFY.]**
- **T (Tampering) — config-edited belt.** `allowedTools` globs live in `agents/<id>.json` under `~/.forvum/`; a user with filesystem access can already widen their own agent's belt. This is **in the trusted zone** (local-first, single-owner default): editing one's own `~/.forvum/` is owner intent, not an attack. The RBAC second gate (§9.1.b) is what bounds a *role-restricted* identity below the belt.
- **I/D.** A tool spec carries no secrets and no unbounded work; the `tool_loop` round cap and per-agent `toolBudget` (§5.5) bound denial-of-service via tool-call storms.

#### 9.1.b `ToolExecutor` gate enforcement — the two-gate model (RBAC second gate is built)

- **Trust boundary.** Every tool call flows through the engine's `ToolExecutor` (§4.3.4, §5.5), which is the single chokepoint between the LLM's requested call and the tool's side effect. There is **no code path that bypasses the filter to grant "just this one call"** access (§5.3) — ad-hoc elevation is forbidden by design.
- **E (Elevation of privilege) — belt gate (first gate).** `ToolExecutor` denies any call whose `ToolSpec.requiredScope` is outside the agent's materialized `AgentToolBelt` (the `allowedTools`-filtered subset of the global `ToolRegistry`). A denied call is audited as `tool_invocations.status = 'denied'` (§4.3.4). **[built — M13.]**
- **E — RBAC role gate (second gate), already built (P2-11, #36).** Beyond the belt, `ToolExecutor` reads the caller's *effective scopes* from `CurrentIdentity.CURRENT_EFFECTIVE_SCOPES` (a `ScopedValue<Set<PermissionScope>>`, bound at every turn entry: `TurnService.dispatch` binds the identity's role-union via `IdentityResolver.rolesFor`; `CronScheduler.fire` binds the distinguished read-only `cron` role). A tool *in the belt* is **additionally denied + audited `denied`** when its required scope is outside the caller's effective scopes (§4.3.4 RBAC note, §7.2 item 11). An identity declaring no roles gets the permissive `default-user` (RBAC is opt-in restriction; backward-compatible, no migration). A caller outside a turn entry leaves the binding unset and is gated by the belt alone — and **every production turn entry binds it**, so the second gate is always active in production. **This gate is confirmed as the second tool-execution gate in the threat model; it is already merged and needs no new contract here.** **[DP-2: the RBAC second gate (CURRENT_EFFECTIVE_SCOPES) is the canonical role-enforcement point; §9 ratifies it as-built, adds no third gate. RATIFY.]**
- **R (Repudiation).** Both gates write a `tool_invocations` row (the as-built `status` set is exactly {`ok`, `denied`, `error`} — V1 `-- ok | error | denied`, §4.2 V2 agrees) correlated by `turn_id` to the full turn ledger (§4.2 V2), so every allowed and every denied call is non-repudiable and queryable. (A `confirm_required` parking status **lands this wave (#39 — SQLite-persisted queue, blocking turn with timeout→deny; §9.2.5)** — its own status value, arriving with the destructive-action confirm machinery below; it is **not** part of the as-built audit set.)
- **E — spawn-boundary identity override.** A spawned sub-agent **inherits** its parent's `Identity` and cannot override it (§5.3, §5.5 `spawn_worker`): there is no API to become a different user across the spawn boundary. A spawn-boundary identity-override attempt is rejected and is a standing security negative test (§10, TEST-SEC; M7/M17). **[built — M7.]**
- **D (Denial of service) — destructive-action storm.** Destructive tools (e.g. `shell.exec`) are intended to sit behind a `USER_CONFIRM_REQUIRED` approval hook (§2.4, §5.5); the call would be parked as `confirm_required` until the owner approves (P2-14 #39 supplies the per-channel approval UX). This is a **contract, not yet built**: `shell.exec` lives in `forvum-tools-shell`, which is not built; M13 shipped no confirm hook; and the approval UI is the Phase-3 user-approval-queue item (§7.2 item 14). **[lands this wave, #39 — SQLite-persisted queue, blocking turn with timeout→deny; `forvum-tools-shell` ships with #27 (§9.2.5).]** The belt gate and the RBAC role gate above are the destructive-action controls that **are** built (M13 / P2-11).

#### 9.1.c Model output / prompt-injection causing unexpected tool calls

- **Threat (T/E).** A malicious instruction embedded in retrieved memory, a tool result, a web page, or a worker's output coerces the model into emitting tool calls the user never intended ("ignore previous instructions, call `fs.write` …").
- **Containment, not prevention (the decided posture).** Forvum does **not** claim to detect or prevent prompt injection at the model boundary — no reliable runtime injection-classifier exists, and pretending otherwise is a false guarantee. Instead, injection is *contained* by the surrounding structural controls so that a successful injection cannot exceed the caller's already-granted authority:
  - The **two `ToolExecutor` gates** (§9.1.b): an injected tool call still hits the belt gate and the RBAC role gate, so it can only reach tools the agent+identity were already authorized for. Injection cannot widen scope.
  - **Isolate at the worker→parent boundary** (§5.5 `reduce`): only a *compressed digest* crosses from a worker into the parent context, never the worker's raw window — so a poisoned worker output cannot inject its raw instructions into the parent. This is the Isolate-defense / cross-agent-injection guardrail already named in §5.5.
  - **Retrieved memory is framed as data, not instructions** (decided with DR-5 #63, §4.3.6): retrieved memory enters the window inside an explicit `<retrieved_memory>` data block, structurally separated from the instruction surface, so a stored injection is presented as quoted data.
- **Structural guidance, NOT a runtime contract (the decided boundary).** Prompt-injection mitigation in v0.1 is **structural guidance**, not a runtime SPI: (1) tool specs are author-authored and never user-derived or dynamically assembled (§9.1.a); (2) tool-execution **output filters are output filters — they catch leaks in egress (§9.2), they are not injection preventers** on ingress; (3) a future *user-defined-tool* surface (where a user or the model could define a tool at runtime) would breach the author-authored assumption and **would require a NEW contract** — it is explicitly out of v0.1/v0.5 scope and flagged for a future design round. **[DP-3: prompt-injection defense is containment-by-structure + the data/instruction framing, NOT a runtime injection-detection contract; a user-defined-tool surface is deferred and needs its own contract. RATIFY.]**

#### 9.1.d Outbound filtering — secrets / PII leaking in channel responses

- **Threat (I — Information disclosure).** A model response, a tool result echoed to the user, or a memory recall surfaces a secret (API key, token) or PII into a channel egress surface (TUI render, web frame, Telegram message) where it should not appear.
- **Mitigation [contract].** A single **outbound filter** runs at the **pre-channel-emit** hook layer on every channel egress surface — the `OutputGuard` SPI (§7.2 item 23) implementing the `OutputFilter` contract specified in §9.2. Because every channel emits through the same `AgentEvent` → channel-render seam (§5.3, outbound flows as `AgentEvent.TokenDelta`), one filter placement covers all three v0.1 channels and every future channel. The filter can **block**, **redact**, or **mark-filtered** the egress (§9.2 `FilteringOutcome`). **[contract — §9.2; built by P2-OUTPUTGUARD #48.]**
- **Hook layers (decided).** The `OutputFilter` contract reserves **three** hook layers so the same SPI is reusable beyond channel egress: **pre-channel-emit** (the v0.1 surface, secrets/PII before a user sees them), **pre-memory-write** (so a secret is not *persisted* into `semantic_memory`/`episodic_memory`; the boundary DR-5 #63 §4.3.6 reserves), and **pre-tool-call** (so a secret is not handed *out* to a tool's outbound HTTP, complementing the SSRF egress policy of §1.1/§1.4). v0.1 wires only **pre-channel-emit**; the other two layers are contract-reserved and wired in their owning issues (DR-5 for pre-memory-write). **[DP-4: the OutputFilter contract defines three hook layers (pre-channel-emit, pre-memory-write, pre-tool-call); v0.1 wires only pre-channel-emit. RATIFY.]**

#### 9.1.e Memory isolation — one agent's memory leaking into another

- **Threat (I/E).** Agent A reads or writes agent B's `messages` / `episodic_memory` / `semantic_memory` rows, breaching the per-agent isolation contract.
- **Mitigation [built].** `AgentMemory` is a SQLite-backed `ChatMemory` that **writes only to this agent's rows** (`agent_id`-scoped), materialized inside the `@AgentScoped` context keyed by `CURRENT_AGENT` (§5.1, §5.3, Isolate pillar). Every memory read/write is `agent_id`-filtered; there is no cross-agent query path in the agent runtime (operator SQL in §7.3 item 2 is an out-of-band debug surface, not an in-turn path). Sub-agent isolation is the same mechanism: each spawned worker runs in its own `@AgentScoped` context with its own memory, and only the compressed digest crosses the `reduce` boundary (§5.5). The two-thread per-agent isolation assertion (M6 Risk #1) and the shared-`@TestProfile` pollution lessons (CLAUDE §14 [M7]) are the standing guards. **[built — M5/M6/M7.]**

### 9.2 `OutputFilter` contract

The `OutputFilter` is the named contract behind the §1.4 outbound-filter promise and the §7.2 item 23 `OutputGuard` SPI. It is specified here (DR-6a) and implemented next wave by **P2-OUTPUTGUARD (#48)**; this section is the contract that issue builds to.

#### 9.2.1 The `FilteringOutcome` sealed hierarchy

The result of running a filter over a candidate egress is a **sealed** `FilteringOutcome` in `forvum-core` (a `forvum-core` type because the `OutputFilter` SPI in `forvum-sdk` returns it, and the SDK may depend only on `forvum-core`). It carries exactly three outcome subtypes:

```java
// ai.forvum.core.security.FilteringOutcome  (Layer 0; sealed; reflection-registered
// from the forvum-engine CoreReflectionRegistration holder per §6.3 — NOT @RegisterForReflection here)
public sealed interface FilteringOutcome
        permits FilteringOutcome.Allowed,
                FilteringOutcome.Redacted,
                FilteringOutcome.Blocked {

    /** Egress passes through unchanged — no sensitive data matched. */
    record Allowed(String content) implements FilteringOutcome {}

    /** Egress is emitted with matched spans replaced (e.g. "sk-***"); the user still
     *  gets a response, minus the secret/PII. Carries the redacted text + a redaction count. */
    record Redacted(String content, int redactions) implements FilteringOutcome {}

    /** Egress is suppressed entirely; the turn surfaces a FallbackTriggered/Error path
     *  with reason = FallbackReasons.FILTERED instead of leaking. Carries the trip reason. */
    record Blocked(String reason) implements FilteringOutcome {}
}
```

- **`Allowed`** — the common path: nothing matched; the original content flows to the channel unchanged. (Naming note: the *outcome* subtype for "passed clean" is `Allowed`; the *event-level* signal that a filter *acted* is `FallbackReasons.FILTERED`, used only on the `Blocked` / hard-trip path — see §9.2.2. The task brief's "FILTERED" outcome label is realized as the `FallbackReasons.FILTERED` **reason token**, not as a third `FilteringOutcome` subtype; the three subtypes are the disposition — pass / redact / suppress — which is the orthogonal and more useful axis.)
- **`Redacted`** — the preferred non-fatal trip: the user still gets a useful answer with the secret/PII masked; the redaction count feeds telemetry. Redaction is the default for matched secrets; full block is reserved for policy-configured hard categories.
- **`Blocked`** — the hard trip: the whole egress is suppressed and the turn ends on the `FallbackReasons.FILTERED` path rather than leak. Used when redaction cannot be done safely (e.g. the entire message is the secret) or when policy declares a category block-only.

**[DP-5: the trip disposition is a 3-subtype sealed `FilteringOutcome` (`Allowed` / `Redacted` / `Blocked`); the brief's "FILTERED" label is the `FallbackReasons.FILTERED` reason token on the `Blocked` path, not a fourth subtype. RATIFY or amend the subtype names.]**

#### 9.2.2 Naming coordination with `FallbackReasons.FILTERED` and DR-4c `FailureClass`

A new `FallbackReasons.FILTERED = "filtered"` constant joins the existing `FallbackReasons` set (§4.3.2: `RATE_LIMIT`, `TIMEOUT`, `SERVER_ERROR`, `COST_BUDGET`). It is the user-facing telemetry token written when a `Blocked` outcome ends a turn, mirroring how `COST_BUDGET` short-circuits (§4.3.2 / Decision 9). This is **additive** to `forvum-core` and carries no migration.

**Coordination with DR-4c (#62).** DR-4c settled the `FallbackChain` core type (§4.3.5.3) and resolved the `Filtered` handover: the `FailureClass` permits (`Retryable`/`NonRetryable`/`Unknown`) already shipped at M8 (sealed, engine-local), and the handed-over permit is **folded into `NonRetryable` — no fourth permit** (§4.3.5.3 / DR-4c DP-4); the fold was the question handed over by this design round (ISSUES.md DR-4c scope: *"the `Filtered` permit handed over by 6a constraint 7"*). **The naming must stay consistent across the two axes** (which §4.3.2 already keeps deliberately separate — `FailureClass` is the engine-local 3-way *retry* axis `Retryable`/`NonRetryable`/`Unknown`; `reason` is the finer user-facing *telemetry* token):
- `FallbackReasons.FILTERED` (a `String` reason token, **user-facing telemetry**) — added here.
- The `FailureClass.Filtered` permit (the **engine-local retry axis**) — resolved by DR-4c (§4.3.5.3): **folded into `NonRetryable`**, no fourth permit (a permit would have zero call sites — the egress `OutputFilteredException` fires after the chain returned and never transits `FailureClassifier`). A filtered egress is **`NonRetryable`** in retry terms (retrying produces the same secret), and the fold loses nothing: the `reason` token `FILTERED` still distinguishes it in telemetry. The *spelling* is `Filtered`/`FILTERED`, never `Filter`/`Censored`/`Masked`. **[DP-6: name coordination — `FallbackReasons.FILTERED` (token) here; DR-4c resolved the permit question: **folded into `NonRetryable`** (§4.3.5.3 / DR-4c DP-4). RATIFY the spelling + the non-retryable classification.]**

#### 9.2.3 SPI shape (what P2-OUTPUTGUARD #48 implements)

The contract P2-OUTPUTGUARD implements is a sealed-family SPI in `forvum-sdk` (the only plugin-contract layer, §2.2), consistent with the other provider SPIs:

```java
// ai.forvum.sdk.OutputGuard  (Layer 1; forvum-sdk; depends only on forvum-core)
public sealed interface OutputGuard permits AbstractOutputGuard {

    /** Inspect a candidate egress at a given hook layer; return its disposition.
     *  Pure + side-effect-free over the content; runs on the turn's virtual thread,
     *  pre-channel-emit (v0.1). MUST NOT block on network or perform IO. */
    FilteringOutcome filter(OutputContext ctx, String candidate);
}

// non-sealed extension point, mirroring AbstractXProvider (§2.2)
public abstract non-sealed class AbstractOutputGuard implements OutputGuard {}
```

- **Hook layer (decided).** v0.1 invokes the guard at the **pre-channel-emit** seam — the single `AgentEvent.TokenDelta` → channel-render boundary (§5.3) — so one placement covers TUI/Web/Telegram. The `OutputContext` carries the hook-layer enum (`PRE_CHANNEL_EMIT` | `PRE_MEMORY_WRITE` | `PRE_TOOL_CALL`), the `AgentId`, and the `turn_id` so a trip is auditable; only `PRE_CHANNEL_EMIT` is wired in v0.1 (§9.1.d, [DP-4]).
- **Composition.** Multiple configured `OutputGuard`s compose **fail-closed and most-restrictive-wins**: the engine folds the outcomes so any `Blocked` dominates a `Redacted` dominates `Allowed`; redactions union. The composition lives in the engine (an `OutputGuardChain`-style fold), not in the SPI, so a plugin guard stays single-responsibility.
- **Concurrency.** The guard runs **blocking on the turn's virtual thread** (no reactive types; §3.8) and must be IO-free — a guard that needs a remote classifier is out of v0.1 scope and would force the `PRE_TOOL_CALL`/async question into a later design round.

#### 9.2.4 New exception type(s) (decided)

- **`OutputFilteredException`** (a new `forvum-engine` unchecked exception, **engine-local**, not in `forvum-sdk`/`forvum-core`) — thrown by the engine's egress path when a composed `Blocked` outcome suppresses an egress, carrying the `FallbackReasons.FILTERED` reason and the `turn_id`. Unchecked, because the only legitimate catcher is the engine's turn boundary. It **mirrors the *behavioral* pattern of `BudgetExhaustedException`** (§4.3.5.2 / Decision 9 — an unchecked, engine-caught terminal short-circuit that intermediate layers must not be forced to declare) while **deliberately living in `forvum-engine`, not `forvum-core`** (it is purely the engine's enforcement surface, not a value contract — so it does not add to the Layer-0 native-reflection surface). The engine catches it and emits a terminal `ErrorEvent` (`code = "output_filtered"`) / `FallbackTriggered(reason = FallbackReasons.FILTERED)`. A `Redacted` outcome throws **nothing** — it rewrites the egress in place and continues. **No new exception is added to `forvum-sdk` or `forvum-core`** (the SPI returns `FilteringOutcome`; the exception is purely the engine's enforcement surface), so the plugin contract stays exception-free and the Layer-0/Layer-1 native-reflection surface is unchanged.

#### 9.2.5 Adjacent fs/shell contracts — ShellAllowlist (settled) + WorkspaceRoot hardening (scheduled)

The Group-6a inventory named two adjacent contracts: the `WorkspaceRoot` path-confinement contract (fs tools) and the `ShellAllowlist` contract (shell tool). **This amendment settles the `ShellAllowlist` contract and schedules the full `WorkspaceRoot` contract** (design-rounds close wave, 2026-06-09; supersedes the former blanket deferral). Both are implemented by **P2-2 #27 (PR-6)**, which delivers `forvum-tools-shell` (the M13-acceptance-owned surface, X7 #73, §2.4) together with `forvum-tools-sandbox`.

**`WorkspaceRoot` hardening (scheduled, not re-specified).** M14 shipped the minimal lexical `WorkspaceRoot` + `WorkspaceEscapeException` (`normalize` + element-wise `startsWith`; §7.1 M14). The full contract — symlink-resolving confinement (`toRealPath`) and TOCTOU hardening — lands in #27 alongside the shell tool, the first consumer for which the lexical-only gap is practically exploitable (a model-supplied path or working directory can be a symlink out of the root). It is an implementation obligation of #27, not a new design surface.

**The `ShellAllowlist` contract (settled).** One operator-authored policy file, `$FORVUM_HOME/tools/shell.json`, governs both tools:

```json
{
  "allowedCommands": ["git", "/opt/homebrew/bin/rg"],
  "allowedArgs":     { "git": [["status"], ["log", "--oneline"]] },
  "workingDir":      "~/work/scratch",
  "timeoutSeconds":  60,
  "sandboxImage":    "docker.io/library/alpine:3.21",
  "sandboxRuntime":  "podman"
}
```

- **Exact match, no glob/regex in v1.** `allowedCommands` entries are exact `argv[0]` values — a bare command name (resolved against the scrubbed `PATH`) or an absolute path; a relative path containing a separator is rejected. `allowedArgs` is optional, per command: each entry is an argument-vector prefix matched element-wise against the call's argv tail (no entry for a command ⇒ any arguments — the command itself was the grant). Rationale: **predictability** — an operator reading `shell.json` can enumerate exactly what can run; pattern allowlists are a bypass-construction surface (a careless `r.*` also matches `rm`) and resist review. Belt membership already has globs (`allowedTools`, M13); the command layer stays exact. **[DP-7: exact-match allowlist shape; glob/regex deferred to v2 behind a proven need. RATIFY or amend.]**
- **Default = EMPTY — fail-closed.** A missing or empty `tools/shell.json` makes `shell.exec` (and the sandbox) **refuse every invocation**, audited `denied`. This deliberately **inverts** the `devices/`/`roles/` opt-in convention (absent config ⇒ guard disabled / permissive `default-user`, §7.2 items 4/11): those files *restrict* an already-authorized surface, so absence must stay permissive for backward compatibility; this file *grants* the most dangerous capability in the system (arbitrary process execution), so absence must grant nothing. Opt-in restriction vs. opt-in capability — the asymmetry is the point. **[DP-8: empty-default fail-closed, inverting the opt-in pattern. RATIFY.]**
- **Every invocation is `USER_CONFIRM_REQUIRED` — allowlisted or not.** The allowlist bounds *what can run*; the confirm gate guarantees *the owner sees each run* (defense in depth on top of the §9.1.b belt + RBAC gates, which also still apply). Each call is parked through the **P2-14 #39 approval queue — blocking, SQLite-backed**: the tool call's virtual thread blocks on the queued row until approve/reject; an unresolved confirmation times out to deny (the bound is #39's knob). Audit rides `tool_invocations`: parked as `confirm_required` (the planned parking status §9.1.b anticipates — the `status` column is free TEXT, no migration), resolved to `ok`/`error` on an approved run, `denied` on reject, timeout, or allowlist miss. **[DP-9: confirm-required on every shell/sandbox invocation via the #39 blocking SQLite queue. Ratified (wave directive 2026-06-09).]**
- **argv-vector execution only; no PTY; scrubbed env.** The process launches via the `ProcessBuilder` **list form** from the validated argv vector — never `sh -c`, never a concatenated shell string: no shell-metacharacter surface exists (`;`, `|`, `$()`, quoting are never interpreted). Dispatch is the M18 `ToolProvider.invoke(String, Map)` self-dispatch seam — zero reflection. No PTY in v1 (no interactive programs); stdout/stderr are captured and size-bounded before re-entering the window. The child environment is cleared and rebuilt from a fixed pass-through allowlist of exactly **`{PATH, HOME, LANG}`** — no token-bearing host variable reaches a process whose arguments the model chose (complements §9.1.d). The effective working directory (the file's `workingDir`, optionally narrowed per call) is confined via the hardened `WorkspaceRoot`; an escape throws `WorkspaceEscapeException` → denied + audited. Execution is bounded by `timeoutSeconds` (default 60); on expiry the process tree is destroyed forcibly and the row audited `error`. **[DP-10: argv-only + fixed env pass-through + no PTY. RATIFY or amend the env set.]**
- **`PermissionScope.SHELL_EXEC`** lands in `PermissionScope.java` with the module, per the §4.3.4 reserved-values table. (Implementation note: `PermissionScopeTest` currently uses `"SHELL_EXEC"` as its unknown-name fixture; the owning PR moves that fixture to a still-unknown name.)
- **The sandbox reuses `SHELL_EXEC` and the same file.** `forvum-tools-sandbox` (§7.2 item 2) reads the same `tools/shell.json` — `sandboxImage` + `sandboxRuntime` (default `podman`) — and runs `<runtime> run --rm <image> <argv…>` with the workspace **bind-mounted read-only by default** (a writable mount is per-call opt-in, surfaced in the confirm prompt). No separate scope: sandboxed exec is the *same capability* under stronger containment; a distinct scope would invite granting the sandbox more broadly while still executing model-influenced argv. **[DP-11: sandbox reuses `SHELL_EXEC`. Ratified (wave directive 2026-06-09).]** **[DP-12: sandbox keys in the same file; `--rm` + read-only workspace mount as defaults. RATIFY or amend.]**
- **Config wiring + hot-reload.** `tools/` is a new `$FORVUM_HOME` subfolder following the standing registry recipe (§7.2 item 4): `ForvumHome.tools()` plus `"tools"` in `ConfigWatcher.WATCHED_SUBFOLDERS` — verified absent today (the watcher set lists `identities, agents, skills, crons, channels, mcp-servers, roles, devices`); the one-line addition lands with #27. The Layer-3 tool itself stays engine-independent (a plugin cannot observe the engine's `ConfigurationChangedEvent`): it reads the file on demand per invocation via the P2-5 reader pattern (mirroring `ForvumHome.resolve`, inert with no `~/.forvum/`), so an operator edit is live on the next call; the watcher line makes the subfolder a first-class config surface for `forvum doctor` and the standard change eventing. **[DP-13: per-invocation on-demand read in the tool; engine-side watcher line for doctor/eventing. RATIFY or amend.]**

**Genuinely deferred:** glob/regex command patterns (v2), PTY/interactive sessions, per-command env additions, sandbox network policy — and the **user-defined-tool surface remains deferred per §9.1.c [DP-3]**: `shell.exec` does not breach the author-authored tool-spec boundary (the spec is frozen at build time; only argument *values* are model-supplied, and those pass the allowlist, confirm, belt, and RBAC gates).

### 9.3 Plugin, MCP-server, and skill trust (DR-6b)

> **Status: DESIGN-ROUND output (DR-6b, #60).** Deliberation and verification trail in `docs/design-rounds/group-6b-plugin-mcp-trust.md`; decisions are tagged **[6b-DP-n]** (namespaced so they cannot collide with §9.1/§9.2's DR-6a `[DP-n]` set). Items pre-ratified by the 2026-06-09 wave directive are marked **[ratified]**; the rest are flagged for maintainer review. Consumers: P2-13 #38 (MCP server registry), P2-7 #32 (skill install from URL); P2-6 #31 (Maven plugin marketplace, merged) is confirmed as-built.

#### 9.3.1 Trust tiers

Four extension surfaces, four trust classes. Capability **declaration** is plugin-side (`ToolSpec.requiredScope`, `plugin.json`); capability **enforcement** is engine-only (`ToolRegistry` duplicate-name hard error, the `ToolFilter` belt, the two `ToolExecutor` gates of §9.1.b, the §9.2 egress filter). No tier can mint a `PermissionScope`: the enum is closed at the binary's compile time (its own Javadoc: grows only "at milestone boundaries"), so the capability *vocabulary* is fixed — a plugin or remote server can only reference scopes that already exist. **[6b-DP-1]**

| Tier | Surface | Trust class | Gates |
|---|---|---|---|
| **T0** | First-party bundled module (compile classpath) | Core-equivalent; reviewed in-repo | Repo review, Layer-3 enforcer allowlist, CI import grep, native build |
| **T1** | Operator-installed Maven plugin (`~/.forvum/plugins/` drop-in; JVM fast-jar only, §6.2/§6.3) | Core-equivalent **once loaded** — the operator's explicit `plugin install` act *is* the trust decision | HTTPS+checksum resolution (§9.3.4); restart-to-load; its tools still pass belt + RBAC |
| **T2** | Remote MCP server (`mcp-servers/<name>.json`; HTTP/SSE) | **Untrusted**: specs untrusted, results untrusted DATA | `mcp add` = listing grant only; belt allowlist; `PermissionScope.MCP_REMOTE` (RBAC); §9.1.c data framing on results |
| **T3** | Installed skill file (`skills/<skill>.md`) | Operator-trusted **content**, never code; a standing prompt-injection surface | Front-matter input-schema validation; the invoking agent's existing belt + scopes — zero added authority |

**Sandboxing posture (decided): none is claimed for T1, v0.1 through v0.5.** A drop-in JAR loads in-process via `ServiceLoader` and runs with core's process-level authority — a malicious drop-in plugin is arbitrary code execution in the Forvum process (the OpenClaw posture, `openclaw/docs/plugins/architecture.md` "Execution model", stated with the same honesty). Containment for T1 is structural and build-time: the SDK seam (Layer-3 enforcer), the closed scope enum, and `ToolRegistry`'s duplicate-name rejection (no silent shadowing of a first-party tool — stronger than OpenClaw's id-shadowing model). There is **no SPI hook into prompt assembly** (the window is engine-assembled; the contracts are `ChannelProvider`/`ModelProvider`/`ToolProvider`/`MemoryProvider` + the engine-implemented `ChannelTurnDriver`); a `ModelProvider` necessarily sees the assembled request, which is inside T1's stated trust class, not a new gap. The §9.1.a under-declared-scope threat applies to T1 with no code-review backstop — absorbed into the install-act trust decision, not papered over with a runtime verifier that cannot exist for in-process code. Treat drop-in plugins as development-time code; the production path for a curated set is a native rebuild (§6.2). **[6b-DP-9]**

#### 9.3.2 Remote MCP tools — the §9.1.a breach, ruled

§9.1.a's trust boundary (tool specs are author-authored, build-time, never dynamically assembled) is **breached by design** by `forvum-tools-mcp-bridge` (§2.4): a remote MCP server's tool list arrives at runtime, over the network, authored by a third party. **Ruling [ratified]:** §9.1.a is scoped to T0/T1 providers only; MCP-surfaced tool specs are **UNTRUSTED specs** behind three gates — **(a) the belt:** appearing in `ToolRegistry` does not enter any agent's belt; the persona's `allowedTools` must allowlist the tool explicitly (registry listing ≠ belt membership); **(b) `PermissionScope.MCP_REMOTE` via RBAC:** every MCP-surfaced `ToolSpec` declares `requiredScope = MCP_REMOTE` (a new constant — reserved in the §4.3.4 table; lands with #38), so the §9.1.b second gate applies — by the as-built role mechanics the permissive `default-user` (`EnumSet.allOf`) acquires it automatically while the read-only `cron` role does not; **(c) `forvum mcp add` is a trust grant for LISTING only** — it authorizes connecting and enumerating the server's tools, nothing more; execution authority is (a)+(b), per call, audited per call. **[6b-DP-2]** Tool **results** from an MCP server are untrusted DATA: they re-enter the window framed as data, never instructions — the same §9.1.c boundary that frames `<retrieved_memory>`; an injected instruction inside a result is quoted content, and any tool call it coerces still hits both gates. **[6b-DP-3, ratified]**

Supporting decisions: MCP tools are surfaced under namespaced names **`mcp.<server>.<tool>`** (the `<server>` segment is the `mcp-servers/<name>.json` stem) — required for `ToolRegistry` global uniqueness across servers and so a conventional belt glob (`fs.*`) can never accidentally admit a remote tool; a bare `*` belt admits everything by construction and is documented as an operator footgun, not guarded by new machinery **[6b-DP-4]**. `MCP_REMOTE` is deliberately **coarse** in v0.5 (one scope for the whole remote-MCP class; the belt narrows per agent/per tool); per-server granularity is a named deferral **[6b-DP-5]**. **Transports: HTTP/SSE only in v0.5; stdio is parsed-but-flag-off** (Risk #9's decision trigger unchanged; a stdio entry is reported by `forvum doctor` as configured-but-disabled, not an error). `mcp add`/`mcp list` are `CommandMode` one-shots (file write/read only — keep `CommandMode.isOneShotCommand` in sync with `RootCommand.subcommands`). **[6b-DP-6, ratified]**

#### 9.3.3 Skill-file trust

A skill is **operator-trusted CONTENT, never code** (unlike OpenClaw, whose skills can carry executable code and are therefore "untrusted code" — Forvum's surface is prompt-template-only). `skills/<skill>.md` carries **full front-matter input-schema** declaration ([ratified] for #32); the schema is validated on read (file-naming error on a malformed skill, the M4 reader convention) and invocation arguments are validated against it before expansion. The expanded template is content inside the invoking agent's turn: any tool call it induces executes under that agent's **existing** belt and the caller's effective scopes — a skill carries no scope set, no belt, no identity, so **escalation-by-skill has no mechanism**; a malicious skill is exactly the §9.1.c injection threat, contained by the same structure. #32's installer writes the file owner-only (`0600`, the `InitCommand` recipe); `skills/` is hot-loaded (`ConfigWatcher.WATCHED_SUBFOLDERS`). **[6b-DP-7, ratified]** The future `SkillInvokerTool` (X7/M13 acceptance) must declare a `requiredScope`; the recommendation is a dedicated `SKILL_INVOKE` constant (read-only class), decided at that tool's landing issue. **[6b-DP-8]**

#### 9.3.4 Maven-plugin supply chain (#31 as-built; build-input supply chain stays DR-6c §9.4)

`MavenPluginResolver` resolves `groupId:artifactId:version` against `~/.m2/repository` then Maven Central over **HTTPS** (`https://repo.maven.apache.org/maven2/`; the `forvum.plugins.repository-url` override exists for hermetic `file://` tests only). Maven Resolver fetches and checks SHA-1 checksums for every download, but the as-built default `RepositoryPolicy` is checksum-**`warn`** (verified against `maven-resolver-api` 1.9.x bytecode) — a silent integrity bypass on the one path that pulls executable code from the network. **Decided:** `MavenPluginResolver.remote()` sets `CHECKSUM_POLICY_FAIL` (one-line follow-up on the merged #31 surface). **PGP signature verification is a documented deferral** (key-trust management is a real subsystem; Maven itself does not verify by default) — re-opens with DR-6c's build-input work if at all. `forvum init` does not scaffold `plugins/`, and the installer creates it with umask defaults; **decided:** the installer creates `plugins/` and the installed JAR owner-only via the `InitCommand` `0700`/`0600` recipe (an `init`-created `0700` root contains the gap today; a hand-made home does not). On a native binary the command stages-and-warns (`ImageMode.NATIVE_RUN`) — correct as-built: the drop-in path is JVM-fast-jar-only by design, and the fixed native code set is itself a supply-chain control. **[6b-DP-10]**

#### 9.3.5 Revocation

Revocation is file removal plus the existing hot-reload machinery — no new subsystem. **T2:** `rm mcp-servers/<name>.json` (or edit-to-invalid) → `ConfigWatcher` fires (`"mcp-servers"` is in `WATCHED_SUBFOLDERS`, verified on `main`) and #38's config-driven `ToolRegistry` resync **withdraws** the server's `mcp.<server>.*` specs — DELETED and modified-into-invalid both unregister (the M19 never-leave-a-stale-spec-live lesson); in-flight calls complete, the next belt materialization excludes the tools. **T3:** `rm skills/<skill>.md` (`skills/` is watched). **T1:** delete the JAR + restart the fast-jar — `plugins/` is deliberately **not** watched (excluded alongside `state/`); in-process code cannot be safely unloaded, so restart is the honest unit of revocation (native never loaded it). The fastest lever for any tier remains the belt/role edit (`agents/<id>.json` `allowedTools`, `roles/<name>.json`), hot-reloaded and effective on the next turn. **[6b-DP-11, resync ratified]**

### 9.4 Audit retention, supply chain, and privacy (DR-6c)

Settled by DR-6c (#61; full rationale in `docs/design-rounds/group-6c-retention-supplychain-privacy.md`, whose `[DP-n]` decisions are cited here as `[6c-DP-n]`). This subsection records **posture over what is already built** — it adds zero tables, zero columns, zero code; the V1/V2/V3 Flyway migrations remain the only schema authority.

- **Retention — per-ledger classes over the eight-table census.** The engine never deletes operational data on its own initiative; the only in-engine mutation of history is compaction (§7.2 item 20), a prefix-preserving transform, not a purge. **Class A** `messages` (+ `sessions` registry rows): compaction owns reduction, never silent deletion; the V1 `ON DELETE CASCADE` (`messages.session_id` → `sessions`) is the reserved mechanism for the deferred purge surface `[6c-DP-3]`. **Class B** `provider_calls` / `tool_invocations` / `tasks`: append-only, unbounded by default — no TTL, no row cap, no background reaper; these rows are the §9.1.b repudiation/audit evidence base and the CAPR/cost evidence base `[6c-DP-1]`. **Class C** `capr_events`: archive-only (`is_archived`, V3), never deleted — ratified as built (P2-COMPACT) `[6c-DP-4]`. **Class D** `episodic_memory` / `semantic_memory`: owner-curated, unbounded; no automatic forgetting in v0.5 (DR-5's `MemoryPolicy` governs read-back, not deletion; the pre-memory-write `OutputFilter` hook governs what gets in) `[6c-DP-5]`. The operator purge surface — a `forvum sessions purge`-style command, session-granular, dry-run-first (the OpenClaw `sessions cleanup` parity feature), and identity-scoped once P3-5 #53 lands `[6c-DP-6]` — is a **named deferred follow-up**; direct SQL against `$FORVUM_HOME/state/forvum.sqlite` is the v0.5 operator surface (the §7.2 item 21 precedent) `[6c-DP-2]`.

- **Privacy — the complete egress inventory.** Forvum is local-first: the SQLite store, the `~/.forvum/` config tree, and the `semantic_memory` embeddings never leave the machine by default. Exactly three default egress surfaces exist `[6c-DP-7]`: (1) **model provider calls** — the prompt window (messages, retrieved memory, tool results) flows to the operator-configured providers (M9–M12; local Ollama is the `init`-scaffolded default); (2) **channel egress** — assistant replies to the configured channel platforms, gated by the §9.2 pre-channel-emit `OutputGuard` with default-on secrets redaction (#48, lands this wave); (3) **OTLP telemetry** — exports only when `OTEL_EXPORTER_OTLP_ENDPOINT` is set (#40; default-off, unset ⇒ zero telemetry egress). Everything else is operator-opt-in by explicit config/action: a configured Qdrant memory host (`memory/qdrant.json`, P2-5 #30, inert without the file), configured MCP servers (off by default in v0.1, Risk #9; trust contract §9.3), and `forvum plugin install` coordinate resolution against Maven Central (coordinates only, no user data). There is no update-checker, crash reporter, or analytics; any future egress surface must be appended to this inventory in the same PR. **Ledger writes are deliberately not filtered** `[6c-DP-8]`: at-rest ledger content (`messages.content`, `tool_invocations.arguments`, `provider_calls.error`) is protected by the owner-only home plus the egress gates — filtering the audit trail would degrade the very evidence the §9.1.b repudiation defense depends on.

- **Supply chain.** The **native binary is locked at build time** `[6c-DP-9]`: plugins load only from the compile classpath (the `~/.forvum/plugins/` drop-in is JVM-fast-jar-only by design, §6.2), per-module `maven-enforcer-plugin` `bannedDependencies` allowlists police the layer graph (live since M1), `forvum-bom` is the single version bump point excluding the vetoed dependencies (§6.3), CI pins the exact Mandrel patch, and the §6.3 banned-import CI greps complete the enforcement with X1 (#67). **JVM drop-in plugins are an operator-trust boundary** — the contract is §9.3 (DR-6b), cross-referenced, not duplicated `[6c-DP-10]`. **Release artifacts ship with `SHA256SUMS`** published alongside them (#49; the installer verifies its download against it before installing); SBOM / signed provenance attestation is a named deferral `[6c-DP-11]`.

- **Secrets at rest.** The posture is the **owner-only `$FORVUM_HOME` tree** — `forvum init` creates `0700` dirs / `0600` files (POSIX-guarded) — and the #35/#42 **credentials JSON joins it at `0600`** as the v0.5 storage surface for provider keys and OAuth tokens; the §4.1 platform-keychain direction (macOS Keychain / Secret Service / Windows Credential Manager) remains the named hardening follow-up, not the v0.5 gate `[6c-DP-12]`. Channel `botToken`s stay file-borne as built (`channels/telegram.json`, `channels/discord.json`). Named gap: a no-`init` first boot creates `state/` with platform-umask defaults — a hardening follow-up to align `StateDirInitializer` with the `init` permission recipe `[6c-DP-13]`. **No-secret-in-logs is a standing review obligation** `[6c-DP-14]`: every secret-bearing surface ships its redaction seam (the Telegram `/bot<TOKEN>` and Discord `Bot <token>` precedents) plus a non-live log/encode test in the same PR.

---

## 10. Testing Discipline

Forvum's test surface is part of the spec, not relegated to CONTRIBUTING. Each Phase 1 milestone declares its `Verify` script (§7.1); §3.7 commits the build to JaCoCo + a native CI matrix. This section codifies the *process discipline* that makes those Verify scripts trustworthy as gates rather than after-the-fact checks: how tests are written, what layers they live in, what coverage and performance gates apply, and what the project does about flaky live-provider tests.

- **TDD as process commitment.** Every milestone's Verify script is the test that lands *before* the implementation passes — Red → Green → Refactor, enforced by reviewer during PR. The `Verify` text in §7.1 reads as "the test the implementation must satisfy", not "the manual check after the fact".
- **Test pyramid.** Three layers: unit tests (`*Test`, fast, no Quarkus boot, no I/O — Maven Surefire), integration tests (`*IT`, Quarkus DevServices, real SQLite via `@TempDir`, `@QuarkusTest` — Maven Failsafe), and end-to-end scenarios (the ten scripts under `forvum-app/src/test/java/ai/forvum/e2e/` declared in "End-to-End Verification", landing milestone by milestone). Surefire and Failsafe split the lifecycle so a fast inner loop never waits on integration cost.
- **Coverage policy.** §3.7's JaCoCo 80 % line gate plus a 75 % branch gate. *Operationalized (X3):* the `jacoco-maven-plugin` `check` (a BUNDLE rule, 80 % LINE / 75 % BRANCH) is declared once in the parent and **inherited per module**, so it gates each module's own coverage rather than a reactor-aggregate number a weak module could hide inside; it runs in `verify` over the Surefire unit run only (the native-profile Failsafe `*IT` smoke is excluded from coverage). Baselines were measured before the gate landed; modules whose code is structurally not unit-coverable carry justified per-module exclusions/overrides in their pom (`forvum-sdk` excludes its logic-free sealed-set `Abstract*Provider` bridges; `forvum-engine` excludes native-metadata holders + pure Panache `*Entity` data classes; `forvum-channel-telegram` overrides LINE to 0.72 and `forvum-app` overrides BRANCH to 0.70 for the IT-only boot/error paths the excluded Failsafe ITs cover), each with a pom comment recording why. Mutation testing (Pitest) lands in `forvum-core` first because its Quarkus-free domain types are mutation-friendly. (`forvum-sdk` is also Quarkus-free but is dominated by sealed interfaces and abstract classes — little behavior to mutate; mutation joins it in Phase 2 alongside `forvum-engine`.) Initial target is **50 % mutation-killed** (industry-typical greenfield baseline), raised toward 70 % in Phase 2 once a measured baseline exists — wired as a signal, NOT a failing gate. Coverage gates are gates; mutation thresholds are signals until a baseline is in hand.
- **Property-style tests on JUnit 5.** Mandatory for parsers and records: `ModelRef.parse` roundtrip (§4.3.5.1), `AgentEvent` Jackson roundtrip (§4.3.2), `CostBudget` validation invariants (§4.3.5.2), `PermissionScope.fromName` failure modes (§4.3.4). Expressed with `@ParameterizedTest` + `@EnumSource`/`@MethodSource` over curated edge cases plus seeded-random inputs (a fixed `Random` seed keeps failures reproducible) — no third-party property library. They catch regressions JaCoCo never will because they exercise inputs the author didn't think of.
- **Native-mode parity — MANDATORY.** Native is the primary shipped target, so every milestone M1–M20 native-compiles and runs its native smoke path in CI on every pull request (§6.4). A milestone may skip only a *behavioral* native assertion — never the native compile — with a written justification in its Verify block. The single sanctioned skip today is M4 `WatchService`, whose OS-polling semantics are provably JVM-host behavior; M4 must still native-compile and may only omit the behavioral native assertion. The real-provider native turn (Risk #5) is CI-verified by a linux-only `native-turn` job (a real Ollama turn through `forvum ask` against the built binary), not deferred; for a provider whose native build genuinely fails, the native-first remedy (e.g. Vertex/Gemini's REST `quarkus-langchain4j-ai-gemini` extension) is preferred over a JVM-only carve-out. There is no other default-to-JVM path; the doubled CI cost is the price of a native-mandatory product.
- **Test execution via the Quarkus Agent Dev MCP.** JVM-mode tests are run through the Quarkus Agent Dev MCP (`devui-testing_runTests` / `runTest`) via a subagent (§3.9); the §7.1 Verify command remains the contract the run must satisfy. Native integration tests (`-Pnative`, `@QuarkusIntegrationTest`) remain a Maven/Failsafe step and are the M20 gate.
- **Performance gates per turn — initial targets, baselined at M5/M6.** Suggested p95 first-token latency excluding model inference: TUI ≤ 200 ms, Web ≤ 300 ms, Telegram ≤ 500 ms. These are *initial targets* to be confirmed by M5 (persistence) and M6 (`@AgentScoped` context) baselines; if measurements show them infeasible, this section is amended before they are enforced. Measurement uses a `FakeProvider` returning deterministic tokens so the gate measures Forvum, not the LLM.
- **Flaky-test quarantine.** Live-provider tests live in `*-LiveTest` classes tagged `@Tag("live")`. Default-off in CI; a nightly workflow runs them with retry budget 1 and fails fast on the second failure. Live tests catch real regressions but do not gate every PR — with one deliberate exception: the Risk #5 real-provider native turn (`OllamaNativeTurnIT`, also `@Tag("live")` but a Failsafe `*IT`), which the dedicated linux-only `native-turn` job runs per-PR with the same retry budget 1, so a non-conversing native binary cannot reach `main`.
- **Security-test layer.** Negative integration tests under `forvum-app/src/test/java/ai/forvum/security/` cover: prompt injection in user message → no tool-call escalation; path traversal in fs tool args → denied; spawn-boundary identity override attempt → rejected; `PermissionScope` mismatch (belt) → denied and audited; a role-restricted identity → denied an in-belt tool outside its role scopes and audited (P2-11); the distinguished `cron` role → enforced read-only (P2-11). The directory and tests land milestone by milestone alongside the security amendments to M3 / M13 / M14 / M16 / M17 / P2-11; the contracts under test are the threat model and `OutputFilter` contract authored in §9.
- **Test fixture conventions.** A `*Fixtures` factory class per package (`AgentSpecFixtures`, `ProviderCallFixtures`, etc.) centralizes valid-instance construction so test code stays small and intent stays visible. A single `FakeProvider` in `forvum-engine`'s test fixtures returns canned `ChatResponse`s for performance, security, and behavioral tests.

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
