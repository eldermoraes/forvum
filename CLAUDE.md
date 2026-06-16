# CLAUDE.md — Forvum

Guidance for Claude Code (and any coding agent) in this repository. It defines what Forvum is, how to
build/run/test it, the architecture, the native-first mandate, and the conventions you must follow.
The architectural source of truth is `docs/ULTRAPLAN.md`; when this file conflicts with it, that file
wins. Answer with high-confidence statements only — verify in code or `docs/ULTRAPLAN.md` before
asserting; do not guess.

---

## 1. What Forvum is

Forvum is a **local-first, open-source personal AI assistant on the JVM** (Java 25 + Quarkus +
LangChain4j + LangGraph4j), the spiritual successor to OpenClaw (a TypeScript assistant) rebuilt in
Java for a single-binary native install. The name fuses **Forum** (deliberation) and **Quorum**
(minimum voices for a decision): coordination, evidence, and control are first-class, and every turn,
tool call, fallback, and judgment is observable in the ledger.

Central principle — **fixed code, configurable behavior**: new agents, sub-agents, skills,
identities, cron jobs, MCP servers, and channel/provider enablement need only file edits under
`~/.forvum/` (no recompile; dev-mode hot reload; production `WatchService` hot reload). A brand-new
*Java* plugin (channel/provider/native tool) does require repackaging `forvum-app` — the deliberate
trade-off for a reflection-free native binary.

- Repo: `https://github.com/eldermoraes/forvum` · License **Apache 2.0** · `groupId = ai.forvum` ·
  `version = 0.1.0-SNAPSHOT`.
- Docs: `docs/ULTRAPLAN.md` (source of truth, M1–M20 roadmap) · founding paradigm
  `docs/CONTEXT-ENGINEERING.md` (PT source) → `docs/CONTEXT-ENGINEERING-MAPPING.md` (EN mapping) ·
  `docs/ISSUES.md` (per-step issue master index) · `CONTRIBUTING.md` (full contributor guide).
- Status: **Phase-1 MVP complete — M1–M20 landed (EPIC-1 #1 / v0.1).** The full reactor + Tier-1 domain
  contracts + Layer-1 plugin SDK + M4 config loader (`WatchService` hot reload), SQLite/Flyway (M5),
  `@AgentScoped` CDI context (M6), `AgentRegistry` (M7), `FallbackChatModel` (M8), the provider fleet
  (Ollama/Anthropic/OpenAI/Google, M9–M12), tools (`ToolRegistry`/`PermissionScope`/filesystem, M13–M14),
  channels (TUI/Web/Telegram, M15–M17), the LangGraph4j supervisor graph wiring tool execution into the
  turn (M18), file-driven crons (M19), and the GraalVM native single-binary + CI matrix with the picocli
  command-mode/lazy-DB &lt;200 ms cold-start gate (M20). A working vertical slice (one agent vs local
  Ollama via CLI) lives on `demo/conference-mvp`. v0.1 is feature-complete; not yet hardened for
  production. Phase-2 (v0.5 parity) is the next roadmap arc (`docs/ULTRAPLAN.md` §7.2).

---

## 2. Tech stack (versions governed by `forvum-bom`)

Java 25 (LTS) · Maven `./mvnw` (3.9+) · Quarkus **3.33.x LTS** (3.33.2) · Quarkiverse
`quarkus-langchain4j-*` **1.11.0.CR2** (PRE-RELEASE; stable fallback **1.10.0**) · LangChain4j core
**1.16.1** (transitive via the Quarkiverse extension — do NOT pin independently; **1.14.1** on the
stable-1.10.0 fallback) · LangGraph4j **1.8.17** · Xerial SQLite JDBC (≥ 3.40.1.0, use latest
~3.53.x) · Hibernate ORM + Panache + Flyway · TamboUI 0.3.0 (Toolkit + JLine 3 backend) · WebSockets
Next · Quarkus Scheduler · OpenTelemetry · **GraalVM CE 25 / Mandrel 25.0.x-Final** (native builder;
pin the exact patch in CI) · JaCoCo · GitHub Actions.

`forvum-bom` is the single bump point: `quarkus-langchain4j-bom:1.11.0.CR2`, `langgraph4j-core:1.8.17`,
`tamboui-bom:0.3.0`, `sqlite-jdbc` (latest), test libs (JLine 3 comes transitively via `tamboui-bom`).
Quarkus-managed deps (Flyway, OpenTelemetry) inherit the platform BOM version — never pin them
independently.

---

## 3. Module architecture (4 layers, one bounded context per module/sub-package)

Maven multi-module reactor under `ai.forvum`. The layering enforces **core stays extension-agnostic**
at the build level: `forvum-engine` has zero compile dependencies on any concrete
channel/provider/tool module.

This is enforced (since M1) by `maven-enforcer-plugin` `bannedDependencies` in each module's pom,
allowlist form: `forvum-core` bans Quarkus/Quarkiverse; `forvum-sdk` may depend only on `forvum-core`;
`forvum-engine` only on `forvum-core` + `forvum-sdk`. **Every new module carries its own enforcer
execution** — a Layer-3 plugin compiles against `forvum-sdk` **plus the Layer-0 contracts the SPI
re-exposes** (`forvum-core`, e.g. `ModelRef` in `ModelProvider.resolve(ModelRef)`), never the engine,
another extension, or the app (copy the template in `docs/CODE-REVIEW.md` §5.1). The rule runs at
`validate`, so `./mvnw -DskipTests validate` is the fast local check.

```
forvum-parent (pom)
├── Layer 0  Foundation (no Quarkus)
│   ├── forvum-bom        dependencyManagement only — single version bump point
│   └── forvum-core       pure-Java domain: records + sealed interfaces
├── Layer 1  forvum-sdk   the ONLY extension contract (sealed provider interfaces)
├── Layer 2  forvum-engine Quarkus app code, extension-agnostic (deps: core + sdk only)
├── Layer 3  first-party extensions (depend ONLY on forvum-sdk)
│   ├── channels: forvum-channel-tui | -web | -telegram | -discord | -slack | -matrix
│   ├── providers: forvum-provider-anthropic | -openai | -ollama | -google
│   └── tools:    forvum-tools-filesystem | -web | -shell | -mcp-bridge
└── Layer 4  forvum-app   the only runnable artifact (deps: engine + every first-party extension)
```

Root `pom.xml` on `main` declares `forvum-bom, forvum-core, forvum-sdk, forvum-engine, forvum-app`;
Layer-3 extension modules land milestone by milestone.

| Type / construct | Location | Role |
|---|---|---|
| `AgentId, Identity, Persona, ChannelMessage, ToolSpec, ModelRef, ModelRef.parse` | `forvum-core` | value contracts (records, canonical-constructor validation) |
| `AgentEvent permits TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent` | `forvum-core` | sealed event hierarchy |
| `FallbackChain, CostBudget, MemoryPolicy, PermissionScope` | `forvum-core` | sealed policy/budget/scope contracts |
| `ChannelProvider, ModelProvider, ToolProvider, MemoryProvider` (+ `non-sealed AbstractXProvider`) | `forvum-sdk` | the contracts plugins implement |
| `@ForvumExtension`, re-exported `@RegisterForReflection` | `forvum-sdk` | plugin marker + native hint |
| `AgentRegistry`, `@AgentScoped` context (ArC `InjectableContext`), `SupervisorGraph` (LangGraph4j), `ConfigLoader` (WatchService), `LlmSelector` + `FallbackChatModel`, MCP bridge | `forvum-engine` | the runtime heart |

- **`@AgentScoped`** isolates per-agent state across virtual threads via `ScopedValue` (final in JDK
  25 — see §5) backed by a custom Quarkus ArC `InjectableContext` so it works in native.
- **Bounded contexts** (§2.6/§2.7): Config Management, Identity & Persona, Agent Runtime,
  Conversation & Memory, Tool Execution, Model Routing, Channel I/O, Observability — each maps to a
  module or a cohesive `forvum-engine` sub-package (e.g. `.../engine/routing/`).
- **Storage** (§4): hybrid — human-editable `.md`/`.json` under `~/.forvum/` for intent; embedded
  SQLite (`$FORVUM_HOME/state/forvum.sqlite`, WAL, Flyway-migrated) for operational state, memory,
  and metrics.

---

## 4. Build, run & test

Always invoke the committed Maven Wrapper `./mvnw` (committed so contributors and CI share an identical
Maven). Prereqs: Java 25, Maven 3.9+ (or `./mvnw`), GraalVM CE 25 / Mandrel 25.0.x-Final for native.
**Native is the primary build target** (§5) — the default acceptance path; fast-jar is for the inner
dev loop and the JVM drop-in-plugin path only.

```bash
# Native single-binary — PRIMARY target. `-pl forvum-app -am` builds the reactor modules it depends
# on, so it resolves from a fresh clone (a bare `-f forvum-app` needs them already in ~/.m2).
./mvnw -Pnative -pl forvum-app -am package   # → forvum-app/target/forvum-app-<version>-runner
                                             #   startup <200 ms, RSS <50 MB, no end-user JVM
# CI / no local GraalVM: container build
./mvnw -Pnative -pl forvum-app -am package -Dquarkus.native.container-build=true

# JVM fast-jar — development + JVM drop-in plugins
./mvnw -pl forvum-app -am package            # → forvum-app/target/quarkus-app/quarkus-run.jar
java -jar forvum-app/target/quarkus-app/quarkus-run.jar

# Dev mode (Dev UI + live reload) — developing Forvum itself
./mvnw -f forvum-app quarkus:dev             # Dev UI at /q/dev/ (live agent reload, CAPR dashboard,
                                             # provider-call inspector, Concurrency card)

# Reactor verify — full test suite (JaCoCo coverage gates 80% line / 75% branch are PLANNED, not yet wired — see §11, #69)
./mvnw verify
```

**Run tests via the Quarkus Agent Dev MCP, never raw `mvn test`** (§7). From a subagent: `quarkus/callTool`
`devui-testing_runTests` (all) or `devui-testing_runTest` with `{"className":"ai.forvum.…"}` (one). Each
milestone's `Verify` script is the contract the run must satisfy. Native integration tests (`-Pnative`,
`@QuarkusIntegrationTest`, Failsafe) remain a Maven step inside the native profile and are the M20 gate.
**Exception — modules the Dev MCP runner cannot start:** (a) Quarkus-free modules (`forvum-core`,
`forvum-sdk`) boot no Quarkus; (b) headless Quarkus *library* modules (`forvum-engine`) carry no
`build` goal nor HTTP, so `quarkus:dev` is skipped ("assumed to be a support library") and the Dev-UI
test runner cannot attach. Both run their tests directly via Maven Surefire (e.g.
`./mvnw -pl forvum-engine test`) — a `@QuarkusTest` there still boots Quarkus in-JVM via
`QuarkusTestExtension`. The "never raw `mvn test`" rule applies to Dev-MCP-startable Quarkus modules
(e.g. the future HTTP-bearing web channel).

Test layout: unit `*Test` (Surefire, no Quarkus boot/IO) → integration `*IT` (`@QuarkusTest`, real
SQLite via `@TempDir`) → E2E under `forvum-app/src/test/java/ai/forvum/e2e/` (ten scripts, landing
milestone by milestone). Live-provider tests are `*-LiveTest` `@Tag("live")`, default-off in CI,
nightly only — except the Risk #5 native real-provider turn (`OllamaNativeTurnIT`, a Failsafe `*IT`
also `@Tag("live")`), the one live test the per-PR linux-only `native-turn` job gates on (retry budget 1).

---

## 5. Native-first mandate (HARD requirements)

GraalVM native is the **primary, mandatory** build target — not co-equal with fast-jar. Write every
contribution as if native is the only target; CI enforces it.

- **No `--enable-preview` on the native path** — preview features are PROHIBITED there.
- **`ScopedValue` (JEP 506) is FINAL in JDK 25** — the sanctioned `@AgentScoped` context-propagation
  mechanism, no flag needed. Use the final builder form `ScopedValue.where(KEY, v).call(body)`
  (`.run(...)` for void). The only native risk is ArC `InjectableContext` build-time registration
  (addressed at M6).
- **`StructuredTaskScope` (JEP 505) stays preview in JDK 25 → NOT used in v0.1.** Structured fan-out
  is `Executors.newVirtualThreadPerTaskExecutor()` (try-with-resources) + `CompletionStage` join, or
  LangGraph4j orchestration — the committed design, not a fallback. Re-evaluate only after the JEP
  finalizes (post-JDK 26).
- **No runtime reflection** outside framework-managed paths: every JSON-serialized type is a record
  (reflection-free canonical constructor); every DTO in a Quarkus-bearing module (Layer 2+) carries
  `@RegisterForReflection` (a Maven enforcer, planned from M3+ once the SDK re-exports the annotation,
  fails the build if one is missing). **`forvum-core` (Layer 0) is exempt** — it bans `io.quarkus*` and
  cannot depend upward on `forvum-sdk`, so its records cannot carry the annotation; Layer-0 types are
  registered for native from `forvum-engine` via a `@RegisterForReflection(targets = { … })` holder
  (§6.3 of `docs/ULTRAPLAN.md`). Tool-spec lookup goes through a build-time registry, not classpath
  scanning.
- **Build-time plugin discovery:** `@ForvumExtension` + `META-INF/forvum/plugin.json` scanned by a
  Quarkus `BuildStep` that records providers and emits reflection hints. `ServiceLoader` is a
  fast-jar-only fallback, not exercised in native. The `~/.forvum/plugins/` drop-in path is
  JVM-fast-jar-only **by design** (native users rebuild) — a documented property, not a carve-out.
- **Vetoed dependencies:** `sun.misc.Unsafe`, runtime bytecode generation (CGLib, runtime Javassist),
  and un-hinted reflection are excluded via `forvum-bom` and banned by a CI import grep.
- **LangGraph4j native:** graph-state types are records carrying `@RegisterForReflection` with
  hand-authored reachability metadata under `forvum-engine/src/main/resources/META-INF/native-image/`.
- **CI parity is MANDATORY:** every PR builds JVM + native on `linux-amd64` and `macos-arm64`; every
  milestone M1–M20 native-COMPILES and runs its native smoke path; the smoke fails the PR if cold-start
  > 200 ms. The only sanctioned carve-out is a *behavioral* native assertion skip (never the native
  compile) when the milestone's risk is provably JVM-host-only — today the sole case is **M4
  `WatchService`** OS-polling semantics, with a written justification in its Verify block. The
  real-provider native turn (Risk #5) is **no longer deferred**: a linux-only `native-turn` CI job
  builds the binary and drives a real Ollama turn through `forvum ask`, catching native-only provider
  JSON/HTTP/reflection gaps the boot-only smoke missed. For a provider whose native build genuinely
  fails, the remedy is native-first (e.g. Vertex/Gemini's REST `quarkus-langchain4j-ai-gemini`
  extension), not a JVM-only carve-out.

---

## 6. Context Engineering (conceptual foundation)

Forvum is built around `docs/CONTEXT-ENGINEERING.md` and its EN mapping
`docs/CONTEXT-ENGINEERING-MAPPING.md`. Treat the four pillars and the topology as **structural
properties of the architecture**, not aspirational notes:

- **Write** — three-tier memory scratchpad surface (in `~/.forvum/` + the SQLite ledger).
- **Select** — `MemoryProvider` retrieval (vector/graph/metadata/hybrid), tool filtering, model routing.
- **Compress** — write-time summarization of oversized tool results / retrieved memory through a
  small-and-fast proxy model before re-entering the context window; session compaction.
- **Isolate** — `@AgentScoped` per-agent state; only a compressed digest crosses the
  Orchestrator→worker boundary, never a raw worker window.
- **Topology** — Orchestrator-Workers hub-and-spoke; parallel specialist workers (on virtual threads)
  replace a serial cascade. CAPR spans are the operational-traceability foundation.

§2.7 of `docs/ULTRAPLAN.md` owns the pillar → module mapping. When you add or change a module, state
which pillar it serves.

---

## 7. Mandatory Quarkus tooling (`quarkus-agentic@eldermoraes`)

The plugin is the **canonical tool** for all Quarkus work. Its own `CLAUDE.md` is the authoritative
source of stack conventions (Java 25, virtual threads, ScopedValue over ThreadLocal,
records/sealed/pattern-matching, platform BOMs with no pinned extension versions, CDI-first, WebSockets
Next streaming, dual JVM/native build, declarative `@RegisterAiService` + Agentic annotations) —
reference it, do not restate it.

- **Quarkus Agent Dev MCP** (`quarkus/*`: `create`, `update`, `start`, `skills`, `searchDocs`,
  `searchTools`, `callTool`) — MANDATORY for project/module creation, extension selection,
  configuration, version checks, API usage, troubleshooting, and running tests. Never create a Quarkus
  project or add an extension by hand; never answer a Quarkus question from model memory first. New
  module: `quarkus/create` → `quarkus/skills` (BEFORE writing any code/tests) → `quarkus/searchDocs` →
  `quarkus/searchTools` → `quarkus/callTool`. If a required tool is unavailable, **stop and report** —
  do not fall back to model memory or web search.
- **Shape-mismatch reconciliation (BINDING):** the skill's templates are a per-module starting point,
  NOT the reactor skeleton. The reactor topology (parent + `forvum-bom` + the four layers) is
  hand-authored and owned by M1. For each new Quarkus-bearing module, run `quarkus/create` (throwaway
  app) to harvest the current platform version + extension wiring, transplant coordinates into
  `forvum-bom`/the module pom (versions managed by BOMs, never pinned), and adopt the matching template
  class. Quarkus-free modules (`forvum-core`, `forvum-sdk`) do not use the skill.
- **`quarkus-langchain4j-scaffolding` skill** — procedural scaffolding for AI services, agents, RAG
  pipelines, embedding stores.
- **`context7` MCP** for non-Quarkus library docs (LangChain4j, LangGraph4j) before model memory or web
  search. **M18 nuance:** LangGraph4j is not a Quarkus extension → use `context7`, not `quarkus/skills`;
  orchestrate with the LangGraph4j `StateGraph`, NOT the declarative `@SequenceAgent`/`@SupervisorAgent`
  annotations.

---

## 8. Contributing

`docs/ULTRAPLAN.md` is the architectural source of truth; `CONTRIBUTING.md` is the full contributor
guide. Architectural changes — a contract, an SPI, a build tier, or anything in `docs/ULTRAPLAN.md` —
start with a GitHub issue or discussion for design sign-off **before** the PR. Purely additive leaf
changes (a new test, a typo, a small bug fix in merged code) go straight to a PR. `docs/ISSUES.md` is
the per-step issue master index. Issues and PRs are never auto-created or pushed (§10). Code review is
AI-assisted (`/code-review`, or `/code-review ultra` for milestone PRs) plus maintainer approval; the
procedure and rubric live in `docs/CODE-REVIEW.md`, and the merge gate is CI green + rubric walked +
approval.

---

## 9. Branch model

| Branch | Purpose |
|---|---|
| `main` | **default**; ships the multi-module reactor + architectural design docs. PRs target `main`. |
| `gh-pages` | published site (`forvum.ai` / GitHub Pages; brand assets under `docs/brand/`). |

Historical branches are preserved as tags, not branches: the conference-demo vertical slice lives at
tag `archive/demo-conference-mvp` (its D1–D8 deferrals are all absorbed — see the BR-CLEANUP #66
disposition note in `docs/ISSUES.md`) and the pre-decision Tier-1 round draft at
`archive/design-round-tier1` (BR-CLEANUP, #66).

The default branch is `main` (not `master`); use `main` in commit/PR guidance.

---

## 10. Conventions

- **English-only artifacts — non-negotiable.** Every repo artifact is in English: code, identifiers,
  JavaDoc, comments, commit messages, PR descriptions, docs, config keys, log messages, error strings,
  file/directory names. User-facing localization strings may be localized; source strings default to
  English. (Conversational PT with the maintainer is fine.) Use American spelling (`color`, `behavior`,
  `analyze`).
- **Commit convention: Conventional Commits, imperative mood.** Examples: `chore: bootstrap
  multi-module reactor`, `feat(core): add domain records and sealed event hierarchy`, `feat(engine):
  add @AgentScoped CDI context backed by ScopedValue`. A `Co-Authored-By` trailer for AI-assisted
  commits is welcome.
- **No commit/push/issue without explicit authorization.**
- **Surgical edits.** Touch only what the task demands; do not "improve" untouched prose or code; match
  the existing terse, declarative register; remove only orphans your own change created.
- **Keep project docs in sync.** On any commit or PR merge that changes behavior, build, architecture,
  status, conventions, or roadmap, update the affected project-facing docs in the same change —
  `README.md`, `CONTRIBUTING.md`, `CLAUDE.md`, `docs/CONTEXT-ENGINEERING.md`,
  `docs/CONTEXT-ENGINEERING-MAPPING.md`. `docs/ULTRAPLAN.md` remains the architectural source of truth.

---

## 11. Testing discipline (§10 of `docs/ULTRAPLAN.md`)

- **TDD as process commitment** — each milestone's `Verify` script is the test that lands *before*
  implementation passes (Red → Green → Refactor, enforced in PR review).
- **Test pyramid:** unit `*Test` → integration `*IT` (`@QuarkusTest`, real SQLite via `@TempDir`) → E2E
  under `forvum-app/.../e2e/` (ten scripts).
- **Coverage gates (target, NOT yet enforced):** the goal is JaCoCo 80% line (parent) + 75% branch, but
  JaCoCo is **not yet wired** into the build, so coverage is not gated today — wiring it (and the Pitest
  mutation ramp in `forvum-core`, 50% killed greenfield → 70% Phase 2) is tracked in #69 / X3. Once wired,
  coverage is a hard gate while mutation thresholds stay signals until a baseline exists.
- **Property-style tests (JUnit 5) MANDATORY for parsers/records:** `ModelRef.parse` roundtrip,
  `AgentEvent` Jackson roundtrip, `CostBudget` invariants, `PermissionScope.fromName` failure modes.
  Expressed with `@ParameterizedTest` + `@EnumSource`/`@MethodSource` over curated edge cases plus
  seeded-random inputs (a fixed `Random` seed keeps failures reproducible) — **no third-party
  property library**. Quarkus-free modules (`forvum-core`, `forvum-sdk`) use the JUnit line from
  `quarkus-bom`; no `junit-bom` override is needed.
- **Native-mode parity — MANDATORY** (§5). Parser/record (M2), provider HTTP (M9–M12), TUI (M15), web
  (M16), Telegram (M17), and the M20 cold-start gate run native.
- **Per-turn performance gates** (excluding inference, via `FakeProvider`): TUI ≤200 ms, Web ≤300 ms,
  Telegram ≤500 ms — baselined at M5/M6.
- **Flaky-test quarantine:** `*-LiveTest` `@Tag("live")`, default-off, nightly with retry budget 1 —
  except `OllamaNativeTurnIT` (the Risk #5 native turn), which the per-PR `native-turn` job gates on, also
  retry budget 1.
- **Security-test layer** under `forvum-app/.../security/`: prompt-injection → no tool escalation; path
  traversal → denied; spawn-boundary identity override → rejected; `PermissionScope` mismatch → denied
  + audited.
- **Concurrency discipline (§3.8):** **virtual threads first** — blocking, imperative code on virtual
  threads is the default model, not reactive programming; reactive types (Mutiny/Reactor) are allowed
  only at a framework-mandated boundary bridged to a VT, with a justification, and reactive code where
  a VT would have worked is a PR rejection reason. Virtual threads per request; `synchronized` forbidden
  in `forvum-engine` / `forvum-channel-*` hot paths (CI static grep, `.github/concurrency-guardrails.sh`)
  — use `ReentrantLock` / `java.util.concurrent` / atomics. **Pinning detection:**
  `-Djdk.tracePinnedThreads` was REMOVED in JDK 24+ (Forvum runs JDK 25 — the flag is silently inert, so
  a stderr `Thread pinned` grep is a vacuous always-pass gate and is NOT used). JEP 491 (JDK 24) also
  stopped `synchronized` from pinning, leaving only native-code pins (e.g. SQLite JNI); runtime detection
  is via the JFR `jdk.VirtualThreadPinned` event — the `quarkus-junit-virtual-threads` extension's
  `@ShouldNotPin` — and wiring that gate is a tracked follow-up. The enforced concurrency checks today are
  the static `synchronized`/Mutiny greps (`pinning-allowlist.txt` / `vt-allowlist.txt`).

---

## 12. What NOT to do

- Do **not** commit, push, or create live GitHub issues/PRs without explicit authorization.
- Do **not** write any repository artifact in a language other than English.
- Do **not** make `forvum-engine` compile-depend on a concrete channel/provider/tool module, or
  hardcode an extension ID in core — core stays extension-agnostic.
- Do **not** make a plugin depend on `forvum-engine`, another extension, or the app — a plugin compiles
  against `forvum-sdk` **plus the Layer-0 contracts the SPI re-exposes** (`forvum-core`, e.g. `ModelRef` in
  `ModelProvider.resolve(ModelRef)`). `forvum-core` is the pure-contract layer (records/sealed types), not
  internals, so a plugin legitimately uses it; the Layer-3 enforcer allowlists `forvum-sdk` + `forvum-core`.
- Do **not** introduce runtime reflection, dynamic class loading (outside the JVM-only drop-in path),
  `sun.misc.Unsafe`, CGLib, or runtime Javassist — they break the native binary and are CI-banned.
- Do **not** ship a DTO record in a Quarkus-bearing module (Layer 2+) without `@RegisterForReflection`
  (the enforcer, from M3+, fails the build). Conversely, do **not** add the annotation to a `forvum-core`
  (Layer 0) record — core bans `io.quarkus*`; its native reflection is registered from `forvum-engine`
  (§6.3 of `docs/ULTRAPLAN.md`).
- Do **not** use `--enable-preview` on the native path or adopt `StructuredTaskScope` in v0.1.
- Do **not** create/run a Quarkus project or add an extension by hand, or answer a Quarkus question from
  model memory — go through the Quarkus Agent Dev MCP (and `context7` for library docs).
- Do **not** run raw `mvn test` — run tests through the Dev MCP (§4/§7).
- Do **not** use `synchronized` in engine/channel hot paths, or introduce thread-pinning without an
  allowlist entry citing the upstream issue.
- Do **not** introduce reactive code (Mutiny `Uni`/`Multi`, Reactor, a reactive client pipeline) where
  virtual threads + blocking would work — virtual threads are the default model; reactive is allowed
  only at a framework-mandated boundary, bridged to a VT, with a written justification, and
  reactive-where-VT-suffices is a PR rejection reason.
- Do **not** "improve" untouched prose/code — surgical edits only.
- Do **not** treat native as optional or secondary — it is the primary, mandatory target.
- Multi-agent git safety: do not `git stash`, switch branches, or touch `git worktree` checkouts unless
  explicitly asked; scope commits to your own changes.

---

## 13. Behavioral guidelines

- **Think before coding** — state assumptions; surface tradeoffs; if a simpler approach exists, say so;
  if something is unclear, stop and ask.
- **Simplicity first** — minimum code that solves the problem; nothing speculative.
- **Surgical changes** — touch only what the task requires; match existing style; clean up only your own
  orphans.
- **Goal-driven execution** — turn the task into a verifiable goal (write/identify the failing test,
  then make it pass) and loop until it's green.

For anything not covered here, defer to the workspace-level `CLAUDE.md` and to `docs/ULTRAPLAN.md`.

---

## 14. Implementation lessons (accumulated)

Generalizable lessons from completed milestones; append here as milestones land.

- **A module's code only native-COMPILES once `forvum-app` depends on it.** The native image is built
  solely in `forvum-app`; a Layer-2/3 module not wired into the app never enters any native image, so
  "native-compiles" is vacuous. Wire each new module into `forvum-app` in the same milestone, and make
  every `@Startup` bean boot gracefully when its inputs are absent — the CI native smoke runs the binary
  with **no `~/.forvum/`**, so a watcher/loader must warn + no-op (never crash, never block command-mode
  exit) or it fails the smoke. [M4]
- **New Quarkus-bearing *library* module recipe** (harvested via `quarkus/create`, §7): the test artifact
  is `io.quarkus:quarkus-junit` (NOT `quarkus-junit5`); apply `quarkus-maven-plugin` with `generate-code`
  + `generate-code-tests` only (NO `build` goal — a library is not a runnable app); add an empty
  `META-INF/beans.xml` so the app's ArC discovers its `@Singleton` beans; add no native profile (native
  builds only in `forvum-app`). Such a headless library cannot be `quarkus:dev`-ed, so its tests run via
  Surefire — see the §4 exception. Resolve config home with `@ConfigProperty` (MP Config) so a
  `QuarkusTestProfile` can redirect it to a `@TempDir`. [M4]
- **`WatchService` file-watching discipline** (reused by M19 cron): register subfolders created *after*
  boot (on a directory `ENTRY_CREATE`) and scan their already-present files; drop invalid keys on
  `WatchKey.reset() == false`; recover from `OVERFLOW` by rescanning; isolate each synchronous CDI
  `Event.fire()` in try/catch so one throwing observer cannot kill the watch loop; debounce + coalesce
  per path. macOS uses ~2–10 s polling (Risk #7), so behavioral file-watch tests need a generous timeout
  — keep the deterministic assertions in plain unit tests (debounce/coalesce, kind-mapping). [M4]
- **Make test fixtures exercise the absent / created-later state, not just the happy pre-populated one.**
  M4's `/code-review` caught a real gap (subfolders created after boot were never watched) that the
  tests masked because the fixture pre-created every directory. Run `/code-review` (high or `ultra`)
  before a milestone merge, and keep Javadoc/claims aligned with actual behavior. [M4]
- **Harness note:** Maven/Quarkus console output carries ANSI/control chars that can break the agent
  display — run Quarkus-bearing builds/tests with `-B -Dstyle.color=never` (and/or in the background),
  then read the clean Surefire `*.txt` reports rather than the raw Maven log. [M4]
- **Register a custom CDI context from a plain library via a CDI Lite `BuildCompatibleExtension`, not a
  deployment `@BuildStep`.** ArC's documented custom-context path (`ContextRegistrationPhaseBuildItem`
  → `ContextConfiguratorBuildItem` + `CustomScopeBuildItem`) needs a `@BuildStep`, which only runs in a
  deployment module — turning `forvum-engine` into a runtime+deployment extension and forcing its own
  `@QuarkusTest`s out (deployment↔runtime reactor cycle), breaking the M4 headless-library setup. A
  `BuildCompatibleExtension` whose `@Discovery` method calls `MetaAnnotations.addContext(scope, true,
  CtxClass)` (declared in `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`)
  registers the scope from the plain library, makes the annotation bean-defining, and bakes into the
  native image (BCEs run at augmentation). The backing `InjectableContext` lives in the library; the
  scope annotation lives in `forvum-core` with a `provided` `jakarta.enterprise.cdi-api` dep (the
  core enforcer bans only `io.quarkus*`/`io.quarkiverse*`, not `jakarta.*`). [M6]
- **"Other database" (SQLite) needs Hibernate metadata access off + version-check off for a clean
  boot.** With `db-kind=other` + an explicit dialect, set
  `quarkus.hibernate-orm.unsupported-properties."hibernate.boot.allow_jdbc_metadata_access"=false` and
  `quarkus.hibernate-orm.database.version-check.enabled=false`, else Hibernate opens an eager JDBC
  connection on a startup thread (logging a spurious error, and failing the >=2.0.0 version check on a
  not-yet-created DB). Trigger Flyway manually from a `StartupEvent` observer after ensuring the DB
  directory exists — `quarkus.flyway.migrate-at-start` runs during RUNTIME_INIT, before any observer,
  so it would open the file before the dir exists (SQLITE_CANTOPEN). [M5]
- **The SPI method a plugin implements lands in its first CONSUMER's milestone, not the plugin's.**
  `ModelProvider.resolve(ModelRef)→ChatModel` is consumed by M7's `LlmSelector` and implemented by M9's
  Ollama provider; since M7 merges first, the method + a versionless `langchain4j-core` dep land on
  `forvum-sdk` in the M7 PR (else M7 cannot compile). The SDK enforcer governs only the `ai.forvum:*`
  namespace, so adding a non-Forvum dep needs no enforcer change, and `forvum-sdk` stays Quarkus-free
  (LangChain4j is not Quarkus). [M7]
- **`@AgentScoped` bean recipe:** use field injection (package-private) for ArC proxyability — no
  artificial no-arg constructor — and read `CurrentAgent.CURRENT_AGENT` at method-call time. Test
  per-agent isolation/caching via an injected bean's `System.identityHashCode(this)` inside
  `ScopedValue.where(CURRENT_AGENT, id).call(...)` (mirror `ScopeProbe`). The generic isolation lives in
  `AgentContext`, so don't re-assert it per bean. [M7]
- **A turn is made atomic by persist-after-success, not `@Transactional` over the whole turn.** A
  blanket `@Transactional respond()` would roll back the `provider_calls` audit row on a model failure.
  Build the model request with the user message in-memory, call the model, then persist
  user+assistant+observation in one transaction (`AgentMemory.recordTurn`) only on success — the failed
  attempt's ledger row survives in the decorator's own transaction. [M7]
- **A shared static `@TestProfile` HOME shares the SQLite DB AND `@ApplicationScoped` state across
  same-profile `@QuarkusTest` classes** (one app instance), and `@Transactional` test methods commit.
  Scope persistence assertions by the keys/sessions the test wrote, and clean up files a test writes
  into the shared home — a `spawn` registry-corruption bug surfaced live as a sibling test seeing
  `main`'s tool belt clobbered. [M7]
- **Guard public registry mutations + keep IO off lock paths.** `spawn` must reject `childId ==
  parentId` and collisions (`putIfAbsent`-and-throw) or it silently overwrites a file-declared agent. Do
  not run blocking file IO inside `ConcurrentHashMap.computeIfAbsent` (it holds the bin monitor →
  carrier-thread pinning, §3.8) — load outside, then `putIfAbsent`. JPQL `key` is reserved (the `KEY()`
  function), so filter `semantic_memory` by key in-memory, not in a Panache where-clause. [M7]
- **Run a deep, adversarially-verified review before a milestone merge** (dimensions → find →
  refute-by-default verify). On M7 it flipped two test findings where the test was actually the stronger
  version, and caught a real `spawn` corruption + a non-atomic turn before they shipped. [M7]
- **Select the LangChain4j HTTP client factory once, app-wide — the multi-factory conflict is silent until
  the full app classpath and hits EVERY programmatically-built model, not just one.** `forvum-app` carries
  TWO `dev.langchain4j.http.client.HttpClientBuilderFactory` services at once (`JaxRsHttpClientBuilderFactory`
  via ollama/gemini + `JdkHttpClientBuilderFactory`, a transitive of several langchain4j model libs e.g.
  anthropic). A model whose builder is NOT swapped by a Quarkiverse builder-factory — **both Gemini AND
  Ollama** (unlike OpenAI/Anthropic, whose `builder()` IS swapped to the Quarkus REST client) — falls through
  to `HttpClientBuilderLoader.loadHttpClientBuilder()`, which `ServiceLoader`s the classpath and throws
  `IllegalStateException("Conflict: multiple HTTP clients ...")` at `build()` time unless the
  `langchain4j.http.clientBuilderFactory` system property names a factory. (Latent on `main` since M10 added
  the JDK factory: every `ollama:<model>` turn on the assembled binary would have thrown.) Fix at the
  assembly layer: a `@Observes StartupEvent` bean in `forvum-app` (`HttpClientFactorySelector`) sets that
  system property to `JaxRsHttpClientBuilderFactory` once — Quarkus REST client, the same stack the swapped
  siblings use. (Trade-off: per-provider `.httpClientBuilder(...)` pins are self-contained but distributed —
  each new un-swapped provider must remember one, and the first attempt pinned only Gemini and missed Ollama;
  the app-wide selector is central but makes the contract cross-layer, so document the dependency on each
  provider.) The loader reads `System.getProperty` (not MP Config) lazily at first `build()` (= first turn,
  after boot), so a startup observer is early enough; set-only-if-absent leaves an operator `-D` override.
  Native build + no-config boot are verified; the `resolve()` path (System.getProperty + ServiceLoader) is
  identical to the JVM (a live native turn is nightly/M20). The trap: a provider-module contract test passes
  (single-factory classpath) and the only app-classpath exerciser is a `@Tag("live")` e2e (default-off), so it
  ships green — guard with a NON-live `@QuarkusTest` in `forvum-app` that `resolve()`s EVERY provider
  (`build()` alone throws; no key/network), which also catches a future un-swapped provider or a missing
  factory; name the factory via `.class` so a rename is a compile error. [M12]
- **The `quarkus-langchain4j-ai-gemini` extension fails the no-config native boot eagerly** — its
  deployment recorder (`AiGeminiRecorder#throwIfApiKeysNotConfigured`) throws a `ConfigValidationException`
  while constructing the auto-registered default ChatModel synthetic bean at startup when no api-key is
  set (the api-key mapping is itself `Optional<String>`; the eagerness is the recorder's). OpenAI/Anthropic
  are lazy by contrast. Remedy: a placeholder `quarkus.langchain4j.ai.gemini.api-key=unset` default across
  all profiles (the CI native smoke runs the prod profile with no `~/.forvum/` and no key). Forvum never
  uses the extension's own bean (it builds the model programmatically), so the placeholder only defers a
  real-key failure to call time. [M12]
- **A milestone's roadmap "Files" list can be stale in BOTH directions — verify on disk before scaffolding.**
  M13's ULTRAPLAN §7.1 / ISSUES.md "Files" listed `PermissionScope.java (enum)` under `engine/tools/`, but
  the enum already lived in `forvum-core` (M2) and the §4.3.4 prose said so — scaffolding it in the engine
  would have duplicated a Layer-0 type. Likewise `tool_invocations` was already a V1/M5 table (+ entity), so
  M13 added ZERO migrations — only the write seam (`ToolInvocation` DTO + `ToolInvocationRecorder` +
  `PanacheToolInvocationRecorder`, a verbatim mirror of the `ProviderCall` triad). Grep the codebase for
  every type a milestone's Files list names before creating it; the contract often already exists. [M13]
- **The tool SPI follows the M7 prelude-in-consumer-PR pattern, contribution-only.** `ToolProvider` was an
  `extensionId()`-only stub; M13's engine `ToolRegistry` consumes it, so the prelude method
  `List<ToolSpec> tools()` lands in `forvum-sdk` as M13's first commit (M14 implements it) — exactly as M7
  added `ModelProvider.resolve()` ahead of M9. It carries only `forvum-core` types (no langchain4j
  `ToolSpecification`, no execute method — dispatch is the engine's `ToolExecutor` / M18's `tool_loop`), so
  `forvum-sdk` needs NO new dependency and stays Quarkus-free. The permission model is belt-membership: a
  persona's `allowedTools` globs select the belt (`ToolFilter`), and a tool outside the belt is refused by
  `ToolExecutor` with `PermissionDeniedException` + an audited `denied` row — there is no ad-hoc elevation
  path. `ToolExecutor`/`AgentToolBelt.tools()` have no production caller in M13 (not wired into
  `Agent.respond()`); the model-request wiring is M18. [M13]
- **A tool module is the provider Layer-3 recipe minus the langchain4j extension.** `forvum-tools-filesystem`
  (the first tool module) copies `forvum-provider-ollama`'s pom verbatim and drops the
  `quarkus-langchain4j-*` dependency — a filesystem tool is `java.nio` + `quarkus-arc` only; the enforcer
  allowlist (`forvum-sdk` + `forvum-core`) is unchanged because `ToolSpec`/`PermissionScope` are Layer-0.
  The provider (`@ForvumExtension @ApplicationScoped extends AbstractToolProvider`) implements the M13
  `tools()` SPI — contribution-only, so it declares `ToolSpec`s but does NOT run the tools. The tool
  classes (`Fs{Read,Write,List}Tool`) carry the `java.nio` logic and are tested directly (`@TempDir`
  round-trip); their engine-wired execution is M18. Path confinement is a self-contained `WorkspaceRoot`
  (`normalize` + element-wise `startsWith`, so a sibling `<root>-evil` is rejected) throwing
  `WorkspaceEscapeException` — distinct from M13's capability-scope `PermissionDeniedException` (a tool
  plugin can't depend on the engine), and the full DR-6a threat-model contract is deferred. Wire the
  module into the three append-only poms (root `<modules>`, `forvum-bom`, `forvum-app`) in the same
  milestone so it native-compiles + registers at app startup. [M14]
- **A channel (Layer 3) drives turns but must not depend on `forvum-engine` — promote the turn-driver
  contract to `forvum-sdk` (Resolution B).** A channel's direction is inverted (plugin→engine), unlike a
  provider/tool (engine→plugin), yet the Layer-3 enforcer still bans `forvum-engine`. Resolve it with a
  plain (non-sealed) `ChannelTurnDriver` interface in `forvum-sdk` carrying only `forvum-core` + JDK types
  (`dispatch(ChannelMessage, Consumer<AgentEvent>)`, Quarkus-free); the engine's `TurnService` implements
  it; the channel `@Inject`s the SDK interface and ArC resolves it to `TurnService` app-wide.
  `ChannelProvider` stays a pure discovery marker (no transport method — supersedes the planned SI-E1), and
  the channel enforcer stays `{forvum-sdk, forvum-core}`. [M16]
- **A channel-driven turn must self-activate the CDI request context AND catch its own failures — the
  engine `@QuarkusTest` masks both.** `TurnService.dispatch` runs on the channel's own thread (a WebSocket
  virtual thread, a stdin loop) with no ambient request context, but the turn reads via the request-scoped
  `EntityManager` → `ContextNotActiveException`; annotate `dispatch` `@ActivateRequestContext` (the
  `@Transactional` writes still open their own tx). And it must `try/catch` the turn and emit a terminal
  `ErrorEvent` to the sink — an uncaught exception escapes the channel callback and websockets-next's
  default strategy CLOSES the socket with nothing shown, leaving the `ErrorEvent` render arm dead code.
  Engine `@QuarkusTest`s pass regardless (test methods carry a request context and drive only the happy
  path); only an app-level WS e2e (real channel thread) + an always-throwing-provider test catch these —
  both surfaced by the pre-merge adversarial review, not the green build. [M16]
- **A Layer-3 web library is NOT `quarkus:dev`-startable just because it bundles `vertx-http`.** The
  `quarkus:dev` "support library" skip keys off the absent `build` goal, not HTTP presence, so
  `forvum-channel-web` runs its `@QuarkusTest` `*IT` under Surefire (like the headless engine), NOT the Dev
  MCP — even though each `@QuarkusTest` boots a real in-JVM HTTP/WS server. (Corrects the plan's
  "web is the only Dev-MCP-startable module" premise.) [M16]
- **websockets-next test: use `BasicWebSocketConnector` with the full `@TestHTTPResource` URI.** The typed
  `WebSocketConnector<Client>.baseUri(uri)` CONCATENATES the `@WebSocketClient` path, so passing the full
  endpoint URI doubles it → handshake 404. `BasicWebSocketConnector.create().baseUri(fullUri).onTextMessage(
  (c,m)->…).connectAndAwait()` sidesteps it. The per-tab session id is `WebSocketConnection.id()` (from
  `Connection`); `sendTextAndAwait` is blocking → call it from a `@RunOnVirtualThread @OnTextMessage`
  (`io.smallrye.common.annotation.RunOnVirtualThread`), never a Mutiny `Multi` return. [M16]
- **`-Djdk.tracePinnedThreads` was REMOVED in JDK 24+ — a stderr `Thread pinned` CI grep is a vacuous
  always-pass gate on JDK 25.** The flag is silently inert; runtime pinning detection moved to the JFR
  `jdk.VirtualThreadPinned` event (`quarkus-junit-virtual-threads` `@ShouldNotPin`), and JEP 491 (JDK 24)
  also stopped `synchronized` from pinning (leaving only native-code, e.g. SQLite JNI, pins). The enforced
  concurrency gate is now the static `synchronized`/Mutiny grep (`.github/concurrency-guardrails.sh` +
  repo-root `pinning-allowlist.txt`/`vt-allowlist.txt`); the JFR runtime gate is a tracked follow-up. [M16]
- **TamboUI 0.3.0's BOTH terminal backends fail the GraalVM 25 native build — ship NO terminal backend in
  v0.1.** `tamboui-jline3-backend` pulls the `org.jline:jline` uber jar whose bundled JNA provider
  (`JnaNativePty` → absent `com.sun.jna.Platform`) breaks `--link-at-build-time`; `tamboui-panama-backend`'s
  FFM downcall (`LibC.tcgetattr`) is rejected by native-image (`should not reach here: linkToNative`). A
  backend is only needed for terminal-size auto-detection, so `forvum-channel-tui` carries just
  `tamboui-toolkit`+`tamboui-widgets` and renders ANSI through the pure-Java headless `Buffer` (the same
  path the app banner already native-compiles), sized to the fragment's CONTENT width — display-width aware
  so CJK/wide glyphs aren't truncated (`String.length()` is UTF-16 code-units, not terminal cells); the
  terminal wraps long lines. v0.1 is a line-based, pipeable stdin REPL (NOT a full-screen Toolkit app), with
  `--no-ansi` (`forvum.no-ansi`) bypassing TamboUI entirely; terminal-width auto-detection + the full-screen
  Toolkit/`tui.tcss` are deferred to a native-buildable TamboUI backend (TamboUI bump / M20). The TUI is a
  foreground (not server) channel: `ChannelLauncher.FOREGROUND_CHANNELS` + `ForvumApplication.run()` runs the
  REPL in the foreground (returns at stdin EOF) instead of `Quarkus.waitForExit()`. [M15]
- **A Layer-3 library module's config DEFAULTS go in `META-INF/microprofile-config.properties`, not
  `application.properties`; and never log a secret-bearing URL.** Quarkus loads `application.properties` only
  from the application artifact (`forvum-app`); a dependency JAR's `application.properties` works in the
  module's OWN `@QuarkusTest` (there the module IS the app) but is NOT a config source in the assembled
  binary, so it silently falls back to defaults. M17's Telegram rest-client `read-timeout` (which MUST exceed
  the 50s long-poll, else `getUpdates` is cut at the 30s default) belongs in
  `META-INF/microprofile-config.properties` (ordinal 100, loaded from every JAR; `forvum-app`/env override at
  a higher ordinal). Security: the bot token is embedded in the request URL PATH (`/bot<TOKEN>`), so a
  REST-client exception must NOT be logged raw — redact the `/bot<TOKEN>` segment and log a message only (no
  throwable/stack). The long-poll worker is a self-started loop on
  `Executors.newVirtualThreadPerTaskExecutor()` (blocking, no Mutiny — `@RunOnVirtualThread` is only for
  externally-invoked inbound handlers). An enabled-but-token-less `telegram.json` must NOT count as a live
  server channel (`ChannelLauncher.shouldRunAsServer` is token-aware) or the binary hangs in
  `Quarkus.waitForExit()` serving nothing. [M17]
- **LangGraph4j serializes the graph state via `ObjectOutputStream` on EVERY step, even with no
  checkpointer — so keep non-`Serializable` types OUT of the state.** langchain4j messages
  (`UserMessage`/`AiMessage`/…) are not `Serializable`; putting them in a channel throws
  `NotSerializableException` at `invoke()` (reproduced in a spike). Resolution (R6): `GraphState` holds
  ONLY `String`/`List<String>` control signals (`route`/`next`/`final`/`workerDigests`); the `ChatMessage`
  conversation lives in a per-turn mutable holder captured by the node lambdas (compile the graph per
  turn). This also keeps the native image free of any `ObjectStream`/langchain4j serialization surface —
  the M18 native build is green with ZERO hand-authored `META-INF/native-image/` metadata. Corollary:
  `GraphState` MUST be a *class* extending `AgentState` (map-backed contract), NOT a record — the
  ULTRAPLAN's "graph-state types are records" was wrong (records are for the values *in* the channels, of
  which there are now none); both ULTRAPLAN spots were corrected. [M18]
- **`node_async(NodeAction)` builds a `LambdaMetafactory` lambda, not a JDK dynamic `Proxy` — the
  `Proxy.newProxyInstance` branch fires only for `InterruptableAction`** (verified by disassembling
  `langgraph4j-core` 1.8.17). Forvum's plain `state -> node(state)` lambdas are build-time-reachable, so
  no proxy/reflect native config is needed; an adversarial-review "missing native metadata" finding was
  refuted by the bytecode + the green native build. [M18]
- **LangGraph4j's default `recursionLimit` is 25 and counts EVERY node execution, not turns/rounds.** A
  spawn round is 4 nodes (generate→spawn_worker→worker_run→reduce), so an in-graph round cap of 8 is
  unreachable — the framework throws "Maximum number of iterations" around round 6 and fails the turn
  instead of degrading. Fix: `compile(CompileConfig.builder().recursionLimit(MAX_ROUNDS*4 + margin).build())`
  so the in-graph cap binds and returns a best-effort answer. [M18]
- **Tool execution enters the turn at M18 via the graph, and the `@Tool`-vs-self-dispatch choice is a
  native-mandate call.** Option A — `ToolProvider.invoke(String,Map)` self-dispatch (a name→logic switch,
  ZERO reflection) — was chosen over langchain4j `@Tool`/`DefaultToolExecutor`, whose `Method.invoke`
  reflection is NOT framework-managed when models are built programmatically (no `@RegisterAiService`),
  clashing with §5/§12. The SPI execution method lands in its consumer milestone (M18), mirroring M7's
  `resolve()`; the SDK stays Quarkus-free + langchain4j-free (only `java.util.Map`). Model-facing
  `ToolSpecification`s are built FROM `ToolSpec` (no reflection either). Every model-emitted tool call
  still runs inside `ToolExecutor` (belt + audit) — incl. belt tools emitted in the SAME reply as a
  built-in `spawn_worker` call, which the 6-dim review caught being silently dropped (every
  `ToolExecutionRequest` MUST get a result message or the next provider call rejects the conversation).
  [M18]
- **Decouple the supervisor graph from `LlmSelector`/`AgentRegistry` via a `WorkerRunner` seam.** Pass the
  resolved `ChatModel` + belt + messages INTO `SupervisorGraph.run(GraphTurnRequest)` (so it is unit-testable
  with a scripted model), and put sub-agent spawn/drive behind a `WorkerRunner` interface
  (`DefaultWorkerRunner` does `AgentRegistry.spawn` + a child generation, re-binding `CURRENT_AGENT` INSIDE
  the worker virtual thread since `ScopedValue` does not inherit across threads). Workers fan out on
  `Executors.newVirtualThreadPerTaskExecutor()` (no `StructuredTaskScope`). A scripted model that ignores
  its input makes "tool result fed back" / "worker digest merged back" tests pass for the wrong reason —
  capture the per-call `ChatRequest.messages()` and assert the result/digest actually reaches the model
  (the 6-dim review caught both as green-for-wrong-reason). [M18]
- **Pure programmatic Quarkus scheduling needs `quarkus.scheduler.start-mode=forced`** — without a
  `@Scheduled` business method the scheduler does not start, so `Scheduler.newJob(id)...schedule()` never
  fires. The flag goes in `META-INF/microprofile-config.properties` (app-wide, [M17]), not
  `application.properties`. Programmatic API: `scheduler.newJob(id).setCron(expr)
  .setConcurrentExecution(SKIP).setTask(task, true).schedule()` / `unscheduleJob(id)` /
  `getScheduledJob(id)` (the latter two confirmed in `quarkus-scheduler` 3.33.1 via `javap`). **The 2nd arg
  of `setTask(Consumer, boolean)` IS the run-on-virtual-thread flag** (a `runOnVirtualThread` field on
  `AbstractJobDefinition`) — framework-managed VT, no manual offload needed. [M19]
- **Hot-reload: a config-driven job that becomes INVALID on edit must be UNSCHEDULED, not left firing the
  stale spec.** The natural `read().map(parse).ifPresent(schedule)` swallows a parse failure and leaves the
  prior job running the old definition (and old model → burns budget). The MODIFIED-into-invalid (and
  empty/mid-write read) path must `unscheduleJob(id)`, mirroring DELETED. Test the reload deterministically
  by firing `ConfigurationChangedEvent` (or calling the `@Observes` method) + asserting
  `scheduler.getScheduledJob(id)` — NOT via WatchService timing; the boot/`onStart` fixture does not cover
  the reload entry point ([M4] lesson). [M19]
- **A per-cron (or per-X) model override is only proven if the override differs from the default in the
  test.** The cron carries its own `ModelRef` (resolved via `LlmSelector.resolve(ref, agentId, sessionId)`
  + an `Agent.respond(..., ChatModel override)` overload). With the cron model == the agent persona model
  in the fixture (and `FakeModelProvider` ignoring the ref), a regression that dropped the override and
  used the persona model passes green. Give the cron a DISTINCT model id and assert `provider_calls.model`
  reflects the CRON's model (the 6-dim review caught this as green-for-wrong-reason). [M19]
- **The cold-start lever is a per-invocation skip in EVERY DB/IO `@Observes StartupEvent` observer, not a
  global flag — and it must be tested in BOTH directions.** A one-shot CLI command (`--help`/`--version`/
  `init`, detected by `CommandMode` reading `@CommandLineArguments`, which Quarkus populates before any
  observer) skips Flyway (`PersistenceBootstrap`), the `WatchService` (`ConfigWatcher`), AND cron
  scheduling (`CronScheduler`). The first cut gated only the first two; the 6-dim review caught the
  unguarded `CronScheduler` — combined with `scheduler.start-mode=forced`, a one-shot `init`/`--help` could
  fire a cron turn against the (deliberately) un-migrated DB. When you add a startup observer that touches
  the DB/IO, gate it on `commandMode.isOneShot()` too (`HttpClientFactorySelector`, set-only, stays
  ungated). **`ToolRegistry.onStart` WAS left ungated as "cheap side-effect-free" — P2-13 invalidated that:
  the MCP bridge's `tools()` does a blocking network connect, so `onStart` is now gated on
  `isOneShot()` and `mcp list` re-materializes on demand.** Test the lever with a recording collaborator (a
  `Flyway` subclass whose `migrate()` records; `ConfigWatcher.isWatching()`) injected with a one-shot vs a
  normal `CommandMode` and assert BOTH branches — a single-direction test stays green when the guard is
  deleted (verified by mutating `isOneShot()`→`false` and watching the one-shot assertions go red). [M20]
- **To leave HTTP unbound for a one-shot command, set `quarkus.http.host-enabled=false` as a system
  property in a custom `@QuarkusMain` `main()` BEFORE `Quarkus.run` — not from a `StartupEvent` bean.** The
  bundled Web channel puts `vertx-http` on the only runnable artifact; Quarkus binds the listener at
  RUNTIME_INIT, BEFORE `QuarkusApplication.run()` (where picocli parses args), so a `StartupEvent` bean like
  `CommandMode` is too late and `ProcessHandle...arguments()` is unreliable on macOS. The reliable lever:
  give the `@QuarkusMain` class a `public static void main(String[] args)` (the real native entry point —
  it runs before any Quarkus bootstrap), call `CommandMode.isOneShotCommand(args)` there, and on a one-shot
  `System.setProperty("quarkus.http.host-enabled", "false")` then `Quarkus.run(App.class, args)`.
  `host-enabled` is runtime config (system-property ordinal 400 > application.properties); verified on the
  native binary: with the flag, startup logs `Listening on:` empty and drops ~0.285 s → ~0.040 s. This makes
  a one-shot need no free port (the M20 fix for the review's HTTP-bind findings). (@QuarkusMainTest drives
  `QuarkusApplication.run()`, not the static `main`, so the lever is validated by the native cold-start
  gate, not a JVM test.) [M20]
- **A fixed ~5 s startup stall on the macOS CI cell is `InetAddress.getLocalHost()`, not the HTTP bind —
  fix the runner's hostname resolution, don't chase the listener.** The native cold-start gate measured
  **~5093 ms** on `macos-14` while Linux CI and a dev Mac stayed ~45 ms. First fix attempt — unbinding HTTP
  for one-shot — did NOT move it (still 5088 ms), which PROVED the stall is independent of the listener: it
  is `getLocalHost()` called at startup by OpenTelemetry's host-resource detector + the Vert.x address
  resolver, and the GitHub macOS runner has no resolvable hostname (a known runner issue), so the reverse
  lookup times out ~5 s. Fix in the workflow: `echo "127.0.0.1 $(hostname)" | sudo tee -a /etc/hosts`
  (+`::1`) on the macOS cell, before the build. Lessons: (1) the cold-start gate on BOTH cells is what
  caught it — a "document the limitation" stance passed 3/4 and would have shipped a broken macOS binary;
  (2) when a fix doesn't move the metric, that's the diagnostic — re-run and read it before assuming the
  cause; (3) a consistent N-second (not jittery) delay is almost always a fixed timeout (DNS/getLocalHost),
  not load. [M20]
- **Source `--version` from the build, never a literal.** A hardcoded picocli `version = "..."` drifts from
  the POM on the next bump and a same-literal test pins constant-vs-test, not constant-vs-actual-version. Use
  a CDI `IVersionProvider` reading `quarkus.application.version` (Quarkus sets it from the Maven version and
  bakes it into the native image — reflection-free via ArC, no manifest/resource-filter native hint needed).
  `init` (the app-owned one-shot subcommand) also scaffolds `~/.forvum`, which later holds channel
  credentials, so create it owner-only (0700 dirs / 0600 files via `PosixFilePermissions`, guarded by the
  `posix` view) instead of the world-readable umask default; route the shipped binary's logs to stderr
  (`%prod.quarkus.log.console.stderr=true`) so a one-shot's stdout is just the picocli usage. [M20]
- **The native binary could not run a single turn — and "native-COMPILEs + boots" never proved it could.**
  A live native turn (Ollama, the default agent) surfaced TWO native-only gaps that every prior milestone's
  "native build green" had hidden, because the build + the no-config boot never EXECUTE a turn:
  (1) **HTTP client.** A programmatically-built model whose builder is NOT swapped by a Quarkiverse
  factory (Ollama, Gemini — unlike OpenAI/Anthropic) resolves its client via langchain4j's
  `HttpClientBuilderLoader`, whose `ServiceLoader` is **EMPTY in a native image** → the turn dies with
  `"No HTTP client has been found in the classpath"`. The M12 `HttpClientFactorySelector` system property
  only DISAMBIGUATES a multi-factory JVM classpath; it cannot populate an empty native ServiceLoader (the
  M12 "resolve() path identical to JVM" note was wrong — it had never run a native turn). Fix: pin an
  explicit `.httpClientBuilder(new JdkHttpClientBuilder())` (pure-langchain4j JDK `java.net.http`, directly
  instantiated, native-safe) on the un-swapped providers — never the loader. Note the Gemini builder hides
  `httpClientBuilder` on its base class (`BaseGeminiChatModel...Builder`), inherited, not on the subclass.
  (2) **Graph state serialization.** LangGraph4j clones the `GraphState` data map via `ObjectOutputStream`
  on EVERY node step (R6) → native needs each serialized concrete type registered, so the first step throws
  `UnsupportedFeatureError: SerializationConstructorAccessor not found for java.util.ArrayList`. Fix: a
  `@RegisterForReflection(targets={ArrayList.class}, serialization=true)` holder (GraphState holds only
  String + an `ArrayList`-backed `List<String>`). LESSON: the only thing that catches these is an actual
  native turn against a real provider (Risk #5) — keep deferring it and the "single native binary" ships
  unable to converse. Verified locally: `echo '...' | forvum` on the native binary returns a real Ollama
  answer and writes `messages`/`provider_calls`. (Tool-loop/spawn paths add no new serialized types — the
  SCHEMA's only collection channel is the ArrayList appender — but were not separately live-tested.) [M20/Risk#5]
- **Automating the native real-provider turn in CI needs a new `forvum ask` command — `@QuarkusMainIntegrationTest`/
  `@Launch` have NO stdin, so PR #111's `echo '...' | forvum` (the TUI REPL) is unreachable from an IT.** A
  native turn can only be driven out-of-process by a subcommand. `AskCommand` (`forvum ask "<prompt>"`) runs ONE
  turn via the SDK `ChannelTurnDriver` (the engine's `TurnService` — it already binds CURRENT_AGENT/CURRENT_TURN,
  resolves identity, activates the request context, and ledgers the turn, so DON'T re-hand-roll the
  registry/ScopedValue dance) and prints `Done.finalMessage()` to stdout; an `ErrorEvent` → stderr + exit 1, so
  **exit 0 is the real native-turn gate** (a "No HTTP client"/JSON-reflection regression surfaces as
  ErrorEvent → exit 1). `ask` is deliberately NOT in `CommandMode.isOneShotCommand` (the turn needs Flyway/the DB),
  so it boots the full path. Traps found wiring this: (1) **a `@QuarkusMainIntegrationTest`'s `getOutput()` includes
  all boot logs** → `non-blank` is vacuous; route logs to stderr in the IT's `@TestProfile`
  (`quarkus.log.console.stderr=true`) so stdout is the reply alone. (2) **Failsafe does NOT read the Surefire
  `${excludedGroups}` property** — give it its own `<groups>${itGroups}</groups>` + `<excludedGroups>${itExcludedGroups}</excludedGroups>`
  (default `itExcludedGroups=live`); the live opt-in is `-DitGroups=live -DitExcludedGroups=none`. A blank `<groups>`
  is fine (no include filter) but a **blank `<excludedGroups>` makes JUnit discover ZERO tests** (`excludeTags` rejects
  a blank expression) — clear the exclusion with a non-empty no-op tag (`none`), never an empty string. (3) **the
  `@TestProfile`'s `getConfigOverrides()` DO propagate to the launched native binary** as `-D` system properties (the
  IT-launcher applies them) — confirmed by the launch line `-Dforvum.home=...`; so a profile-seeded temp home works
  out-of-process, no FORVUM_HOME env needed. (4) **forvum-app dev mode can't run its tests via the Quarkus Dev MCP**:
  since M20 it's a `@QuarkusMain` CLI that runs the command and EXITS on boot (no server channel → `RootCommand.call()`
  returns), so `quarkus:dev` shuts down immediately and the Dev-UI test runner gets "No CDI container available". Run
  forvum-app's `@QuarkusMainTest` JVM tests via **Surefire** (`./mvnw -pl forvum-app -Dtest=… test`), same family as the
  native `*IT` Failsafe step — a de-facto §4 exception for the CLI app. CI: a linux-only `native-turn` job
  (`services: ollama/ollama`, pull `qwen2.5:0.5b` via the HTTP `/api/pull` with retry, then
  `./mvnw -B -Pnative verify -DitGroups=live -DitExcludedGroups=none`); the binary reaches the service at the default
  `quarkus.langchain4j.ollama.base-url=http://localhost:11434` (a `-D` on `mvnw` would NOT reach the out-of-process
  binary, so map the service port instead). The two-cell `native` job (boot smoke + 200 ms cold-start) stays mandatory
  and unchanged. [Risk#5]
- **A config validator (`forvum doctor`, P2-9) must drive the REAL loaders, never a second, drifting schema.**
  Validate through the same machinery the engine loads with: the M4 readers (`ConfigLoader.readJson` rethrows
  a malformed file as `UncheckedIOException`) + the typed binders (`AgentSpecReader`/`CronSpecReader` throw
  `IllegalStateException` with a file-naming message), each wrapped in try/catch → a `Finding`. So a config
  `doctor` passes is exactly one the engine can load — no parallel JSON-Schema definition to drift (maintainer
  signed off on diverging from ULTRAPLAN's literal "against JSON Schemas"; §7.2 item 9 + `docs/ISSUES.md`
  reworded, formal schemas deferred). Add only the cross-refs the binders can't see: a model ref → an installed
  provider via `Instance<ModelProvider>.extensionId()` (the `LlmSelector` idiom — gathered in the app
  `DoctorCommand`, the only layer that knows the provider set, and passed into the engine `ConfigDoctor`); a
  cron `agentId` → a known agent. Exit non-zero on ERROR / 0 on WARNING-only so scripts + CI can gate. `doctor`
  joins `CommandMode.isOneShotCommand` (reads files only → skips Flyway/watcher/cron; native cold-start ~36 ms);
  keep that set in sync with `RootCommand.subcommands`. Because it is offline + deterministic, its native IT is
  UNTAGGED (runs in the DEFAULT native leg, unlike the `@Tag("live")` Ollama turn IT) — a free real native
  exercise of config validation + provider discovery. Review catch worth generalizing: when a helper LISTS a
  directory from one source and PARSES it from a separate hardcoded literal, a name drift silently skips
  validation — make listing and parsing share one `dir` (single source). [P2-9]
- **Replaying a session is a `messages`+`tool_invocations` interleave whose merge key is turn-logical, not raw
  `created_at`.** A turn's user+assistant pair is committed atomically at turn-END (`AgentMemory.recordTurn`, M7
  persist-after-success) while each `tool_invocations` row is ledgered MID-turn — so a tool's `created_at`
  precedes its own turn's messages. A naive `ORDER BY created_at` merge would print the user message *after* the
  tools it triggered. `SessionReplayer.interleave()` instead walks messages in `id` order and flushes the
  not-yet-emitted tools whose `created_at <=` each *assistant* message (user → tools → assistant), draining any
  trailing tools (a turn that failed before persisting its reply) at the end. The view records
  (`ReplaySession`/`ReplaySegment`) carry NO `@RegisterForReflection` — like the `doctor` report records they are
  built from JDBC rows and printed, never serialized (the native IT proves it). The adversarial review reaffirmed
  the M4 lesson: a single-tool happy-path test left the multi-tool inner loop and the trailing-drain branch
  unexercised — seed the multi-tool and incomplete-turn fixtures, not just the one-tool case. [P2-8]
- **A DB-reading CLI (`forvum replay`) is NOT a `CommandMode` one-shot, and its deterministic native IT seeds the
  DB without a live LLM via a two-launch dance.** Unlike `doctor` (file-only), `replay` reads the SQLite store, so
  it must boot the full Flyway/Panache path — keep it OUT of `CommandMode.isOneShotCommand` (which would skip
  migration). Its native IT therefore can't reuse doctor's file-only fixture: launch the binary once
  (`replay <missing-session>` → it migrates the schema on boot, exits 1 not-found), INSERT a session + message rows
  via plain JDBC into that now-migrated SQLite, then launch `replay <session>` again to read them back — all three
  sharing one `forvum.home`. Test-side Flyway is NOT an option (`flyway-core` 12 ships no SQLite support module on
  the app classpath); letting the binary own schema creation sidesteps it. These `@QuarkusMainIntegrationTest` ITs
  run native-only (`skipITs=true` in JVM); the `forvum.home` propagation they rely on is the proven native-leg
  mechanism (`DoctorNativeIT`/`OllamaNativeTurnIT`), so the same test errors under a JVM `-DskipITs=false` run. [P2-8]
- **A second authorization gate belongs at the same `ScopedValue` seam as `CURRENT_AGENT`, enforced "only
  when bound" — NOT fail-closed-when-unbound.** P2-11 RBAC gates a tool by the caller's effective scopes in
  addition to belt membership. `ToolExecutor` is `@ApplicationScoped` (can't `@Inject` per-turn state), so it
  reads a new `CurrentIdentity.CURRENT_EFFECTIVE_SCOPES` `ScopedValue` bound at the turn entry — mirroring how
  `Agent` already reads `CURRENT_AGENT`, proven to survive the whole `respond → SupervisorGraph → ToolCallBridge
  → ToolExecutor` chain on one virtual thread. ALL production turn entries that reach a tool call are exactly
  two — `TurnService.dispatch` (channels + `forvum ask`) and `CronScheduler.fire` — and both bind it; sub-agent
  workers (`DefaultWorkerRunner`) do a single direct generation with NO tool loop (M18), so they never reach the
  executor. So "enforce iff bound, else belt-only" keeps the gate always-active in production while leaving the
  many lower-level belt-focused unit tests (`ToolExecutorTest`, `ToolCallBridgeTest`, `SupervisorGraphTest`)
  untouched. Fail-closed-when-unbound was the initial plan but is strictly worse: it forces RBAC bindings into
  those unrelated tests for zero production benefit (it can't make production more secure — production always
  binds). Enumerate every `agent.respond(` / turn entry before choosing the unbound semantics. [P2-11]
- **"Extend `PermissionScope` to role-based sets" means cable role-sets ABOVE the enum, not add enum
  constants.** ULTRAPLAN §4.3.4 mandates the role → scope-set mapping live above `PermissionScope`, which stays a
  flat capability list. So P2-11 added ZERO `PermissionScope` constants: a Layer-0 `RoleSpec(name,
  Set<PermissionScope>)` record + an additive `Identity.roles` (4-arg canonical ctor + a 3-arg delegating ctor
  so existing `new Identity(a,b,c)` callers/tests compile and a `roles`-less JSON defaults to empty = backward
  compatible, no migration), an engine `RoleRegistry` with code-level built-ins overridable by
  `roles/<name>.json` (mirror `AgentRegistry`: `ConcurrentMap` + `putIfAbsent`, IO off the lock, `@Observes
  ConfigurationChangedEvent` evict; built-in `default-user` = `EnumSet.allOf` so it grows with the enum, `cron`
  = read-only). The new config subfolder is wired by adding `ForvumHome.roles()` + `"roles"` to
  `ConfigWatcher.WATCHED_SUBFOLDERS` + a `RoleReader extends JsonDirectoryReader`; the new core record is
  reflection-registered in the engine `CoreReflectionRegistration` holder (§6.3), NOT `@RegisterForReflection`
  in core. Parity with a simpler upstream (OpenClaw is binary owner/non-owner + tool-name lists, no abstract
  scopes) is semantic — reproduce its behavior (permissive default, restricted cron) in the local vocabulary,
  don't copy its types. [P2-11]
- **There is NO outbound channel-send API — channels are self-driving consumers, not sinks.** The channel SPI
  (`ChannelProvider`) is a pure build-time discovery marker (M16 Resolution B); a channel pulls turns via
  `ChannelTurnDriver.dispatch`, the engine never pushes to one. So "deliver a cron's output to a channel"
  cannot target a live session — route it to an isolated-agent result sink (`CronDeliverySink`, default logs)
  keyed by the resolved target, and document the limitation. Validate an `explicit-to` target against the
  CONFIGURED channels (`channels/<id>.json` stems via `ChannelReader.ids()`), not a live registry. Reject the
  whole delivery directive at PARSE (grow the typed record's canonical constructor for the mode↔target
  ambiguity; layer the known-channel cross-check in the reader, which holds the set) so the existing
  `CronScheduler` catch→`unscheduleJob` disables the bad cron AND `ConfigDoctor` (which reuses the same reader
  as its oracle) surfaces it for free — give doctor the same known-channel set. In-execution dedupe = a single
  `deliver()` call site after a successful `fire()`; no table, no migration. The new payload records
  (`Delivery`/`CronDelivery`) are never JSON-serialized, so they carry no `@RegisterForReflection` (mirror
  `GraphTurnRequest`). To drive `fire()` end-to-end in a NON-boot unit test, construct `CronScheduler`
  directly and set its package-private collaborators to stubs — but a stub that `extends` a CDI bean
  (`AgentRegistry`/`LlmSelector`/`RoleRegistry`/`Agent`) must carry `@Vetoed`: a CDI scope is `@Inherited`,
  so an un-vetoed subclass becomes a second ambiguous bean and breaks the module's `@QuarkusTest` boot
  (a sibling `RecordingSink` implementing the plain `CronDeliverySink` interface needs no veto). [P2-CRON-DELIVERY]
- **A "sink SPI" lives in `forvum-sdk` as a PLAIN (non-sealed) interface with the engine as sole implementor —
  not in the sealed channel/model/tool/memory hierarchy.** `TaskExecutor` (P2-TASKLEDGER) mirrors the
  `ChannelTurnDriver` shape: SDK contract, single engine `@ApplicationScoped` impl (`TaskRecorder`), plugins do
  NOT implement it; engine callers `@Inject TaskExecutor`. The Panache recorder pattern is exact
  copy-`PanacheProviderCallRecorder` (`@ApplicationScoped` + `@Transactional record()` mapping a Layer-0 record
  to an entity row). Record the write persist-after-success (never wrap the whole producer in `@Transactional`),
  and isolate the recorder call in try/catch so a ledger failure cannot undo/kill the work that already
  succeeded. Wire spawn-recording at the REAL chokepoint (`AgentRegistry.spawn`, where every spawn —
  including the M18 `DefaultWorkerRunner` — converges), not at a facade (`TurnService` never spawns). A new
  `V2__tasks.sql` bumps the Flyway head, so the M5 `SchemaSmokeIT` version/table/index assertions (it pins
  version "1" + the V1 table & index lists) MUST be updated to the new head in the same change. [P2-TASKLEDGER]
- **Prefix-preserving compaction needs an id-stable summary, so the summary RECLAIMS the oldest dropped id.**
  The cached prefix is defined id-based (`id <= cached_prefix_end_index`, never mutated), and replay reads
  `order by id` — so a summary inserted with a fresh IDENTITY id (always the highest) would sort LAST, not
  at the prefix tail, and using that high id as the new prefix boundary would freeze EVERYTHING below it.
  Fix: delete the dropped run, then native-INSERT the summary at the oldest dropped message's id (IDENTITY
  forbids a manual id on `persist()`, so a controlled `em.createNativeQuery("INSERT ... (id, ...)")` is the
  seam), and advance `cached_prefix_end_index` to it. The summary then sits numerically+chronologically
  right after the old prefix and before every retained message, the existing `order by id` path is
  untouched, and the prefix grows monotonically. The summarizer is an injectable `Summarizer` SPI
  (default reuses the §1.4 small-and-fast model via `LlmSelector.resolve`, NOT a bespoke endpoint); tests
  bind a deterministic `@Alternative @Priority(1)` stub so no live model is hit. Orphan stripping keys off
  a new `messages.block_type` core enum (`BlockType`, registered in `CoreReflectionRegistration`): strip
  `turn_reasoning`/`turn_artifact` + stale `tool_execution` older than the oldest retained user message,
  retain connected `tool_execution`. CAPR is archived (`capr_events.is_archived`), never deleted. **Adding
  a NOT-NULL column to an existing entity breaks every hand-built fixture** — `SchemaSmokeIT` (2 sites) +
  `SessionReplayerTest` (1 site) set `MessageEntity` fields directly and needed `blockType`; the V2 `DEFAULT
  'turn_message'` only covers raw SQL inserts that omit the column (the app native replay IT). Migration
  is **V2** (the brief said V3, but only V1 existed — keep the chain contiguous). Seed-then-compact-then-read
  ITs use `QuarkusTransaction.requiringNew()` (intra-class `this.seed()` bypasses `@Transactional`
  interception). **The blocking summarizer LLM call must run OUTSIDE any DB transaction** (CLAUDE §14
  [M7]): `compact()` is NOT `@Transactional` — a short read tx plans the pass (partition the region,
  capture id/content primitives so the detached entities are never reused), the model is called with no
  Agroal/SQLite connection held, then a short write tx applies the mutations (bulk delete-by-id +
  native-insert + advance prefix). **Track the retain boundary on EVERY retained `TURN_MESSAGE`
  regardless of role, not just USER rows** — else the newest turn, typically the assistant reply (since
  compaction runs before the next user message is persisted), is left at `Long.MAX_VALUE` and summarized
  away when it alone exceeds `retainTokens`; the user-then-assistant persist order keeps the common-case
  boundary on the USER row unchanged. [P2-COMPACT]
- **A new `$FORVUM_HOME/<dir>/` config-file registry is a fixed five-edit recipe — copy `roles/`, don't
  invent.** P2-4 device pairing added `devices/<id>.json` with ZERO schema change by mirroring P2-11/M7
  exactly: (1) `ForvumHome.devices()`; (2) add `"devices"` to `ConfigWatcher.WATCHED_SUBFOLDERS` (this one line
  is what makes hot-reload fire — the watcher already registers any listed subfolder created after boot); (3) a
  raw `config/DeviceReader extends JsonDirectoryReader` (the base is PACKAGE-PRIVATE, so the raw reader MUST
  live in `ai.forvum.engine.config` — only the typed `DeviceSpecReader`/registry live in the feature package);
  (4) a Layer-2 `Device` record with its OWN `@RegisterForReflection` (a Layer-2 record, unlike a core record,
  carries the annotation directly — NOT in `CoreReflectionRegistration`); (5) an `@ApplicationScoped`
  `DeviceRegistry` = `ConcurrentMap` + `putIfAbsent` with IO off the lock + `@Observes ConfigurationChangedEvent`
  evict (filter `path.getName(0)=="devices"`). Enforce opt-in like RBAC: an empty/absent dir disables the guard
  (cache a `volatile Boolean enabled`, null it on config change) so an existing install needs no migration; a
  distinguished built-in id (`cron`/`server`) short-circuits exempt. Enforcement keys off `ChannelMessage`'s
  existing fields (`channelId` = the device endpoint) — do NOT add a `deviceId` to the core record this package
  (the turn entry `TurnService.dispatch` already wraps a thrown guard as the terminal `ErrorEvent`). [P2-4]
- **A reference Layer-3 plugin against a third-party backend is just the Telegram blocking-REST recipe
  reused.** P2-5's `forvum-provider-memory-qdrant` copied `forvum-channel-telegram` wholesale: same pom
  (`forvum-sdk` + `quarkus-rest-client-jackson` + `quarkus-arc` + `quarkus-junit`, no `build` goal,
  copied enforcer allowlist), same `@RegisterRestClient(configKey=...)` + per-invocation `@Url` (the
  backend URL is operator config, not a compile constant), same `META-INF/{beans.xml, forvum/plugin.json,
  microprofile-config.properties}` (rest-client defaults MUST ship in microprofile-config, ordinal 100,
  not application.properties — a dependency's application.properties is inert in the assembled binary),
  same on-demand file config reader mirroring `ForvumHome.resolve` (so it stays engine-independent and is
  INERT/no-op with no `~/.forvum/`). The provider type in `plugin.json` is `"memory"`. Keep retrieval
  logic PURE and Quarkus-free (`QdrantRetrieval` = request-build + response-map static fns) so most tests
  are plain unit tests with a hand-written `FakeQdrantApi` double; one `@QuarkusTest *IT` proves CDI +
  `@RestClient` wiring (pin `forvum.home` in test `application.properties` so the inert-no-config assertion
  is hermetic regardless of the dev's real `~/.forvum/`). Adding a second rest-client to `forvum-app` does
  NOT reintroduce the Gemini/Ollama multi-factory conflict (that was the langchain4j HTTP client, fixed by
  `HttpClientFactorySelector`; plain JAX-RS rest-clients coexist). [P2-5]
- **A normalized-score `[0,1]` contract must reject NaN explicitly.** `MemoryPolicy.minScore`/`MemoryHit.score`
  validate `in [0,1]`, but `Double.isNaN(x)` makes both `x<0` and `x>1` false, so NaN slips a naive
  range check — the property test caught it. Guard with `Double.isNaN(x) || x<0 || x>1`. When mapping an
  external score (Qdrant cosine ∈ [-1,1]) into the contract, clamp (and NaN→0), don't assume the backend
  pre-normalizes. [P2-5]
- **The SPI method a plugin implements lands in the consumer's PR, but a reference plugin IS its own
  consumer.** Unlike M7's `resolve` (added on the SDK in M7 because `LlmSelector` consumed it there),
  P2-5 added `MemoryProvider.retrieve` AND its first implementor in the same PR — the engine does not yet
  call it (host wiring of retrieval into the turn is a later item), so the method + the reference impl
  ship together and the SDK enricher JavaDoc documents the host contract the engine will honor. [P2-5]
- **Bundle Maven Resolver via `maven-resolver-supplier` (the no-DI bootstrap), not the Guice/Sisu path.**
  P2-6 (`forvum plugin install <coords>`) resolves a coordinate against `~/.m2` + Central. The 1.9.x
  `org.apache.maven.resolver:maven-resolver-supplier` hand-wires a `RepositorySystem`
  (`RepositorySystemSupplier().get()` + `MavenRepositorySystemUtils.newSession()` from the transitive
  `maven-resolver-provider`; 1.9.x uses `DefaultRepositorySystemSession` + `LocalRepository` +
  `system.newLocalRepositoryManager`, NOT 2.x's `SessionBuilderSupplier`/`getPath()` — `ArtifactResult.
  getArtifact().getFile()` in 1.9.x), so it pulls NO Guice/CGLib (only httpclient + maven-model + plexus-utils)
  — clean for the import-grep ban. NATIVE containment: these classes ride the `forvum-app` native classpath
  but RUN only in the fast-jar drop-in path; keep them inert by (a) `@ApplicationScoped` resolver (lazy — never
  instantiated unless `install()` runs), (b) referencing aether types only inside method bodies, never as a
  field/`@Startup` work, (c) registering NOTHING for reflection (the supplier needs no ServiceLoader/reflection).
  The drop-in dir is JVM-fast-jar-ONLY BY DESIGN (§6.2/§6.3), so the command warns + still stages the JAR when
  `ImageMode.current() == NATIVE_RUN` (the running-native constant; `NATIVE` does not exist on `ImageMode`).
  Test the resolve+stream path HERMETICALLY against a `file://` remote seeded with a tiny jar+pom in a
  `@TempDir` (no network/`~/.m2` flake); make `plugin` a `CommandMode` one-shot (it only resolves+writes). [P2-6]
- **`maven-resolver-supplier` pulls a SPLIT-version transitive graph — pin the whole set in the BOM.**
  Depending only on `maven-resolver-supplier:1.9.27` resolves `named-locks`/`transport-file`/`transport-http`
  at 1.9.27 but `api`/`spi`/`impl`/`util`/`connector-basic` at 1.9.25 (a mismatched packaged app). Add explicit
  `dependencyManagement` for the api/spi/impl/util/connector-basic set at `${maven-resolver.version}` so supplier
  runs against a matching impl; verify the exact patch exists on repo1.maven.org first (solrsearch is stale). [P2-6]
- **Resolve-from-the-fast-jar throws a loader-constraint `LinkageError` until the resolver artifacts are
  parent-first.** Maven Resolver spreads its `org.eclipse.aether.*` packages across several JARs; under the
  Quarkus runtime classloader they split across loaders, so `RepositorySystemSupplier.get()` fails wiring
  `Maven2RepositoryLayoutFactory` against `ChecksumAlgorithmFactorySelector` (different `Class` objects).
  Fix with `quarkus.class-loading.parent-first-artifacts=<the whole resolver set + org.apache.maven:maven-resolver-provider>`
  so one loader owns every aether class — a classloading directive only (no eager init; stays native-inert). The
  plain-JVM engine unit test (single classloader) CANNOT catch this; only an in-JVM `@QuarkusMainTest` success
  path that actually reaches `RepositorySystemSupplier.get()` does — so the resolve+stream CLI MUST be tested
  end-to-end through the Quarkus runtime, not just at the engine unit level. [P2-6]
- **A persistent-WebSocket channel (Discord gateway) rides `quarkus-websockets-next` CLIENT mode, not a
  reactive SDK.** JDA/Discord4J are native-broken/reactive and pull a transport stack that violates the SDK
  boundary; a hand-rolled minimal Gateway v10 client over `@WebSocketClient` (the CLIENT-mode dual of the web
  channel's `@WebSocket`) native-compiles + boots (the readiness spike proved the websockets-next-client +
  rest-client combo). CLIENT-mode API (from the Dev MCP, NOT memory): `@WebSocketClient(path="/")` endpoint
  with `@OnOpen(WebSocketClientConnection)` / `@OnTextMessage @RunOnVirtualThread void onText(conn, frame)` /
  `@OnClose`; inject `Instance<WebSocketConnector<Endpoint>>` and `connectors.get().baseUri(URI)
  .userData(UserData.TypedKey.forString(k), v).connectAndAwait()` to open (connectors are single-use +
  not-thread-safe → `Instance.get()` per connect); pass per-connection secrets via `userData()` read back in
  the endpoint, never a field. Keep the protocol layer SOCKET-FREE (a pure `GatewayProtocol.decide(payload,
  state)→sealed Reaction` + frame parse/encode + an atomic `GatewayState`) so HELLO→IDENTIFY, the
  heartbeat-carries-last-seq, and MESSAGE_CREATE flows are unit-testable with no live `wss://`. CONCURRENCY:
  the op-1 heartbeat loop is a dedicated `Thread.ofVirtual()` driven by `heartbeat_interval`; shared
  seq/session live in `AtomicLong`/`AtomicReference` (NO `synchronized`, §3.8); a `ReentrantLock` guards ONLY
  the heartbeat-thread reference swap (interrupt the old thread OUTSIDE the lock — no blocking IO under any
  lock → no carrier pinning). Token never logged: it rides the IDENTIFY frame + the REST `Authorization: Bot
  <token>` header (a `@HeaderParam`, not a `@Url` path like Telegram), and `redact()` masks any `Bot <token>`
  echo. Mirror Telegram for the rest (config reader, allowedUserIds, byte-identical `render()`, the
  no-token→warn+no-op boot, the `ChannelLauncher` token-gated `serves()`). Native-gate caveat: heartbeat-loop
  concurrency + reconnect/TLS edges only surface at runtime → keep plain JSON (no zlib-stream), gate any live
  end-to-end behind `*-LiveTest @Tag("live")`. NATIVE-FRAME TRAP: the *outbound* envelope record (`{op,d}`
  wrapping every IDENTIFY/HEARTBEAT via `writeValueAsString`) ALSO needs `@RegisterForReflection` — without it
  the native binary emits an empty/malformed frame and the handshake silently fails, and the no-token native
  smoke can NOT catch it (no token → never serialized). Pin it with a non-live encode test asserting the JSON
  carries `op`/`d` with the right opcodes (2 IDENTIFY, 1 HEARTBEAT). RECONNECT (must, not optional): a gateway
  connection is NOT permanent — Discord routinely sends op-7 RECONNECT, so a connect-once design dies on the
  first routine event. Self-heal from `@OnClose(conn)` (read `conn.closeReason().getCode()`): if still
  `running` (no ShutdownEvent) and the code is not a fatal 4xxx (`{4004,4010..4014}`), re-open on a VT with
  exponential backoff (a pure clock-free `Backoff` atomic: 1s→2s→…cap 60s, `reset()` on READY); a deliberate
  shutdown (`running=false`) never reconnects; a fatal code stops with a WARN (no infinite loop). The initial
  v0.1 policy was fresh IDENTIFY per reconnect; the op-6 RESUME follow-up has since LANDED — on a resumable
  close the reconnect dials `resume_gateway_url` (+ the same `?v=10&encoding=json` params; Discord sends it
  bare) and HELLO yields `SendResume` (`{token, session_id, seq}`, a `@RegisterForReflection` outbound frame +
  encode test) when `GatewayState.canResume()` (session + resume URL + a seen seq), falling back to IDENTIFY
  on the base URL after op-9 `d=false` resets the state; RESUMED resets the backoff like READY. CLOSE-CODE
  TRAP: Discord invalidates the session when the CLIENT closes with 1000/1001, so every close made with
  intent to resume — op-7, resumable op-9, heartbeat failure — must send a non-1000 application code
  (`closeAndAwait(new CloseReason(4000, reason))`) or the RESUME is defeated on its primary trigger (op-7)
  by our own NORMAL close; deliberate shutdown and non-resumable op-9 keep 1000. Pin the close code at the
  ENDPOINT with a fake `WebSocketClientConnection` — decide()-level tests alone pin intent the transport can
  silently defeat. And `state.reset()` on BOTH a failed dial of the resume host (hosts rotate; else every
  backoff retry re-dials the dead host forever) AND close codes 4007/4009 ("start a new session"). A failed
  heartbeat send must CLOSE the connection so the
  same `@OnClose`→reconnect path fires (else the log claim "the gateway will reconnect" lies). The
  endpoint(`@Singleton`)→channel(`@ApplicationScoped`) callback is plain `@Inject`; a test subclass of the
  channel that overrides `connect()` to record must be `@Vetoed` or the module's `@QuarkusTest` sees two
  `DiscordChannel` beans (AmbiguousResolution). Make the policy unit-testable via a `Sleeper` seam +
  same-thread executor: assert growing backoff on transient close, no reconnect on shutdown, stop on fatal,
  backoff reset on READY. [P2-CH/discord]
- **"Decode the final message against a JSON Schema" stays native-clean as schema-STRING → `JsonNode`, NOT a
  typed POJO.** P2-12's locked decision rejects LangChain4j `@Description`/`@StructuredPrompt` decoding: a
  per-agent output class would force runtime reflection / classpath class loading and break the native binary.
  Instead `Persona` carries an optional `outputSchema` STRING (null = free-text, backward compatible; blank-but-
  present rejected — already in the §6.3 reflection holder), `AgentSpecReader` serializes an embedded object
  spec to a compact string (or takes a string verbatim), and a pure-Java `OutputSchemaValidator` tree-walks the
  decoded reply (`mapper.readTree`) checking the v0.5-parity subset (root `type`, `required`, each property's
  primitive `type`) — no third-party JSON-Schema lib until one is proven to native-compile (documented fast-
  follow). Thread it through `GraphTurnRequest` (add a backward-compatible secondary ctor defaulting it to null
  so existing 5-arg callers/tests compile) and validate in `SupervisorGraph.run` AFTER the graph returns, not in
  a node. A failure throws `SupervisorGraphException` naming the schema + field; `TurnService` already converts
  any turn `RuntimeException` into a terminal `ErrorEvent.from(...)`, so the named message rides into the event —
  no retry, no new event plumbing. A spawned worker child passes `null` (its digest is merged as a tool result,
  never the validated top-level answer). [P2-12]
- **MEASURE the per-module JaCoCo baseline BEFORE setting the gate, then exclude only structurally-uncoverable
  code — never lower the global threshold.** Wire `jacoco-maven-plugin` (0.8.15 is the first line reading Java
  25 / class-file 69 bytecode) ONCE in the parent `<build><plugins>` — `prepare-agent` (Surefire only; its
  `argLine` is picked up automatically, do NOT also count the native-profile Failsafe `*IT`), `report`,
  `check` (BUNDLE rule 80% LINE / 75% BRANCH via `${jacoco.line.minimum}`/`${jacoco.branch.minimum}` props) —
  inherited per module, gating each module's own coverage (stronger than a reactor aggregate a weak module
  hides inside). Measure first: `verify -Djacoco.line.minimum=0.00 -Djacoco.branch.minimum=0.00`, then read
  per-module `target/site/jacoco/jacoco.csv` (cols: BRANCH_MISSED=$6 BRANCH_COVERED=$7 LINE_MISSED=$8
  LINE_COVERED=$9 — NOT 4/5/6/7). A child re-declares the `jacoco-check` execution by the SAME id to add
  `<excludes>` (JaCoCo class-exclude form `ai/forvum/pkg/Foo*.class`, `/`-separated, `.class` suffix) or a
  relaxed `<minimum>`; the execution `<configuration>` REPLACES the parent's rules (no deep-merge), so copy the
  whole `<rules>` block. Justified excludes only: `forvum-sdk` logic-free `Abstract*Provider` sealed-set bridges
  (→ 0 lines, passes vacuously), `forvum-engine` native-metadata holders + pure Panache `*Entity` classes (→
  80.31/76.68, clears global). Where there is NO structural class to exclude (a real gap covered only by the
  excluded Failsafe ITs or the booted app), set a JUSTIFIED per-module override and record the gap in a pom
  comment, never weaken the global gate: `forvum-channel-telegram` LINE→0.72 (IT-only CDI-lifecycle/`@RestClient`
  boot lines), `forvum-app` BRANCH→0.70 (picocli command error branches the native ITs cover). `pom`-packaged
  modules (parent, `forvum-bom`) have no exec file → `check` skips gracefully, no carve-out. The four
  §10-mandated property tests already existed — confirm before writing. Pitest stays signal-only (documented,
  not a failing gate). [X3]
- **A server-only dashboard endpoint must not touch the command-mode cold-start path.** The `/q/dashboard/capr`
  CAPR endpoint (X6 scenario 10) is a `quarkus-reactive-routes` `@Route` (`CaprDashboardRoute`, `type =
  BLOCKING` for the Panache read) over the Web channel's already-present `vertx-http` — chosen over
  `quarkus-rest` so it does not perturb `HttpClientFactorySelector` (the `langchain4j.http.clientBuilderFactory`
  pin) or the REST-client stack. A `@Route` handler binds only when a server channel is up; one-shot/command
  mode leaves `vertx-http` unbound (`quarkus.http.host-enabled=false` from `ForvumApplication.main`), so the
  route never serves there. The discipline: give the endpoint NO `@Startup`/`StartupEvent` observer and do its
  DB/HTTP work only inside the handler — then it cannot regress the < 200 ms command-mode boot-smoke nor the
  `ask`/`doctor` one-shot path (the gate measures those). Add the extension via the platform BOM, never pinned;
  the DTO it serializes is a record carrying the real Quarkus `@RegisterForReflection` (Layer 4 is
  Quarkus-bearing, so the SDK re-export is unnecessary here). [X6]
- **Author span-less e2e scenarios against existing machinery + observable DB side-effects.** OTel spans do not
  exist in v0.1, so an e2e asserts the ledger rows the turn wrote, not a span. The five X6 scenarios reuse the
  in-process `FakeModelProvider` (no live inference, per the perf-gate convention) and the production seams:
  spawn → `AgentRegistry.spawn` + a per-child `capr_events` row; cron → seed a `0/1 * * * * ?` `tick.json` and
  poll for the `cron:<id>` ledger rows (the real `Scheduler` fires in a `@QuarkusTest` because `CommandMode`
  sees no one-shot arg); hot-reload → fire the same `ConfigurationChangedEvent` the `WatchService` would (the
  macOS poll latency makes a real watcher non-deterministic) and assert the next turn re-reads the edited spec;
  Telegram allow/deny → drive the real `UpdateProcessor` over an in-test recording `TelegramBotApi` impl. A
  package-private production constant (`UpdateProcessor.REFUSAL_MESSAGE`) is asserted by its observable content,
  not widened to `public` for a test. [X6]
- **The prompt-injection security test must drive the BELT gate end-to-end, not the executor directly.** The
  existing `PermissionScopeMismatchTest` already denies an out-of-belt tool by calling `ToolExecutor.execute`
  with a hard-coded name; the mandated prompt-injection category (CLAUDE.md §11) is the same belt-miss denial
  but realized through the real channel turn entry — a scripted tool-calling fake model (id `scripted-injection`,
  app-test scope, mirrors the engine's `ScriptedToolCallModelProvider`) emits an `fs.write` `ToolExecutionRequest`
  the way an injected instruction would coerce a real model, the agent's `allowedTools` is `[]`, and
  `TurnService.dispatch → SupervisorGraph.toolLoop → ToolCallBridge → ToolExecutor` denies + audits it
  (`status='denied'`) while the turn still completes (terminal `Done`, no `ErrorEvent`). Assert BOTH the denied
  row AND `ok=0` for the same `(session,tool)` — the no-escalation half — scoped to the session this method
  writes (shared `@TestProfile` DB, §14). Make it gating by red-checking: put the tool back in the belt and the
  `denied=1` assertion must flip to `0`. The engine `ScriptedToolCallModelProvider`/`FakeToolProvider` live in
  `forvum-engine/src/test` and are NOT on the app classpath — add an app-test fake; route by `extensionId()`
  (`LlmSelector` matches the `ModelRef` provider half), and a new `ModelProvider` bean does not perturb the
  provider-resolve guards (they inject by concrete type). [TEST-SEC]
- **A "milestone gap" can be a docs-ownership gap, not a missing milestone — fold, don't multiply
  milestones.** X7's six items (shell tool, `SkillInvokerTool` skills surface, `forvum-tools-mcp-bridge`
  baseline, §3.6 OTel baseline, `forvum init`, the `/q/dashboard/capr` endpoint) each rode an existing
  milestone's SPI/surface (skills + shell + mcp-bridge on M13's `ToolProvider.tools()`; OTel + CAPR on
  M18's turn/graph spans; the `init` command on M20's picocli command-mode + the M4 `~/.forvum/` layout
  it scaffolds — NOT M1, which is reactor/pom/wrapper bootstrap only), so the fix was to fold them into
  M4/M13/M18/M20 *acceptance* and delete the "real roadmap gap" framing — no micro-milestones, no code.
  When a docs item reads "no Phase-1 milestone", check whether the surface already exists before scheduling new work.
  Unblocks downstream parity issues that depend on the owned baseline (P2-7/#32, P2-13/#38, P2-15/#40). [X7]
- **`MemoryPolicy` is a flat Layer-0 record driving a Layer-1 retrieval SPI — settled by DR-5
  (`docs/design-rounds/group-5-memory-policy.md`), §4.3.6.** `MemoryPolicy(RetrievalStrategy strategy,
  Set<MemoryTier> tiers, int topK, double minScore, int compressThresholdChars)` + four siblings
  (`RetrievalStrategy`/`MemoryTier` enums, `MemoryQuery`, `MemoryHit`) all in `ai.forvum.core` (no
  sub-package — unlike budget, no service iface in core). It DRIVES the new SPI method
  `MemoryProvider.retrieve(MemoryQuery, MemoryPolicy) → List<MemoryHit>` (blocking on a VT, NO reactive;
  SDK stays Quarkus-free; SDK already deps core so no new dep/enforcer change), which P2-5 #30 implements.
  `strategy=NONE` keeps the policy non-nullable on the agent spec ("memory off" is a value). One
  `compressThresholdChars` knob serves both the §5.5 `reduce` merge AND retrieved-memory write-back
  (chars not tokens = native-clean). Spawn-inherited like CostBudget/Identity but needs NO
  `SpawnConfigurationException` analogue — unlike a `SessionWindow` budget, the tenant key (`agentId`)
  is per-call via `MemoryQuery` from the child's `@AgentScoped` context, so a verbatim policy reads the
  child's own memory. Retrieval framed as `<retrieved_memory>` DATA + pre-memory-write `OutputFilter` are
  REFERENCED from DR-6a §9, never redefined (read/write split: policy governs read-back, filter governs
  write). The five core records do NOT carry `@RegisterForReflection` (core bans `io.quarkus*`) — P2-5
  appends them to the engine `CoreReflectionRegistration` holder (§6.3). Dissolves demo D2's
  `memoryPolicy` sub-gap; residual `AgentSpec` composition is DR-8's. [DR-5]
- **A security design round is "confirm what's built + name what's deferred", not "invent new gates".** DR-6a
  authored §9 (threat model STRIDE-by-surface + the `OutputFilter` contract) by *confirming* the already-merged
  controls in the threat context (the two `ToolExecutor` gates — belt + the P2-11 RBAC `CURRENT_EFFECTIVE_SCOPES`
  second gate; `@AgentScoped` memory isolation; spawn-boundary identity inheritance) rather than proposing new
  runtime machinery. Prompt-injection is **containment-by-structure** (the gates + the `reduce` Isolate boundary +
  data/instruction framing), explicitly NOT a runtime injection-detector — and tool-execution filters are *output*
  filters (catch egress leaks), never injection preventers; a user-defined-tool surface would breach the
  author-authored tool-spec assumption and needs its own future contract. The `OutputFilter` disposition is a
  3-subtype sealed `FilteringOutcome` (`Allowed`/`Redacted`/`Blocked`) in `forvum-core`; the brief's "FILTERED"
  label is the `FallbackReasons.FILTERED` *reason token* on the `Blocked` path (mirrors `COST_BUDGET`), not a
  fourth subtype — and the engine-only `OutputFilteredException` mirrors `BudgetExhaustedException` (unchecked,
  engine-caught terminal short-circuit) so the SDK/core stay exception-free. Coordinate the `Filtered` spelling
  with DR-4c's `FailureClass` (filtered = non-retryable). Flag each settled point inline as `[DP-n]` so a
  maintainer can ratify/amend a draft surgically. [DR-6a]
- **A feature-complete binary still failed the "stranger installs it" test — silent exits and bare wrapper
  errors made working machinery look broken.** A fresh-machine install hit three pure-UX walls: (1) the
  default run with no `~/.forvum` printed the banner and exited 0 with no hint (and the README's JVM section
  omitted `init` entirely), reading as "the terminal doesn't work"; (2) the REPL had no prompt/help line, so
  an interactive session looked frozen while the model worked; (3) every turn failure rendered as the
  wrapper's "Supervisor graph failed for session ..." with the actionable root cause (ConnectException →
  Ollama not running) swallowed. Fixes: TTY-gated interactive affordances — `System.console() != null &&
  console.isTerminal()` is the JDK 25 seam (works in the native image), threaded as a
  `repl(..., boolean interactive)` parameter. TTY-only: the `forvum> ` prompt, the ready line, the
  block-letter banner, and `/exit`-`/quit` interception (demo parity; a piped `/exit` line is NEVER
  swallowed — piped framing stays identical to the M15 contract and a piped session ends at EOF).
  Unconditional by design: the no-channel `forvum init` hint (the whole point is "never exit silently",
  including CI/piped runs) and the error rendering. `TurnService.describeFailure` walks to the deepest
  cause (hop-capped — a multi-node cause cycle is constructible and would spin the catch block forever)
  and, on a connection-level root, appends "Is the model provider running? (model: <ref>)" — fetched via
  a never-throwing persona lookup since the lookup itself can be the failure; NOTE the native image
  surfaces connection-refused as `ClosedChannelException` while the JVM throws `ConnectException` (only a
  live native error-path run caught that), so the predicate covers both (+`UnknownHostException`,
  +`HttpConnectTimeoutException`); the TUI renders
  `ErrorEvent` behind an `[error] ` marker. README install builds get `-DskipTests` (installers were running
  the dev suite and reading its noise as a broken build; contributors run `./mvnw verify`). Test the
  install path as a user would — `init`/`doctor`/piped turn/no-config run on a clean `FORVUM_HOME` — not
  just the milestone Verify scripts. [UX-INSTALL]
- **The MCP bridge MUST build its transport from the Quarkiverse `QuarkusHttpMcpTransport`, NOT the
  standalone langchain4j `HttpMcpTransport` — the latter drags in OkHttp's `okhttp-sse`, which is absent
  from the classpath AND not native-friendly.** P2-13's `forvum-tools-mcp-bridge` is the provider Layer-3
  recipe (`forvum-sdk` + `quarkus-arc` + `quarkus-langchain4j-mcp`, no `build` goal, copied enforcer
  allowlist) surfacing `mcp-servers/<id>.json` servers as `mcp.<server>.<tool>` `ToolSpec`s carrying
  `PermissionScope.MCP_REMOTE` (DR-6b §9.3 — remote specs are UNTRUSTED, behind belt + the P2-11 RBAC
  scope gate). The §7 mandate ("the Quarkiverse extension, NOT the beta") is load-bearing, not advisory:
  `new dev.langchain4j.mcp.client.transport.http.HttpMcpTransport.Builder().build()` constructs an OkHttp
  `SseEventListener` → `NoClassDefFoundError: okhttp3/sse/EventSourceListener` at connect time. Use
  `io.quarkiverse.langchain4j.mcp.runtime.http.QuarkusHttpMcpTransport.Builder` (Vert.x, native-ready,
  the extension's own `McpRecorder` builds clients the same way) — its API differs (`headers(Map)` not
  `customHeaders`, plus `mcpClientName`); it `implements McpTransport`, so it still feeds
  `new DefaultMcpClient.Builder().transport(...)`. THE TEST TRAP: that `NoClassDefFoundError` is an
  `Error`, so it escapes the provider's best-effort `catch (RuntimeException)` and CRASHES boot
  (`ToolRegistry.onStart` → `tools()` → connect) — but the no-config wiring IT NEVER connects (empty
  `mcp-servers/`), so it ships green. Only a test whose home HAS a server exercises the real transport
  build; the multi-launch app `McpCommandTest` catches it because its shared `forvum.home` accumulates
  server files, so the 2nd+ launch's boot connects. Keep the SOLE langchain4j-touching class
  (`DefaultMcpClientFactory`) behind a `McpClientFactory` seam so the provider + its units stay
  langchain4j-free (fake factory), and jacoco-exclude that adapter (live-server-only, qdrant/telegram
  policy). [P2-13]
- **Materializing MCP tools is a SYNCHRONOUS network call, so `ToolRegistry.onStart` is NO LONGER the
  "cheap side-effect-free" observer M20 left ungated — gate it on one-shot, and re-materialize `mcp list`
  on demand.** Listing a server's tools is a connect + `listTools` round-trip; if `onStart` materializes
  unconditionally, EVERY one-shot (`--help`/`--version`/`init`/`doctor`/`plugin`/`skill`/`mcp add`) blocks
  at boot up to the connect timeout PER configured server on a machine with `mcp-servers/*.json` — the
  pre-merge review's headline finding, invisible to the CI `<200 ms` gate (it runs with no `~/.forvum/` →
  zero connects). Fix: `ToolRegistry.onStart` now returns early when `commandMode.isOneShot()` (inject the
  engine `CommandMode`); `mcp list` (a one-shot) re-materializes by calling the bridge directly
  (`McpBridgeToolProvider.tools()`), so the connect cost is paid only when the operator asks to list. Test
  BOTH directions (one-shot boot → registry empty; normal boot → materialized) — the [M20] discipline.
  Keep a tunable `forvum.mcp.connect-timeout-seconds` (default 5) so a normal server boot can't hang long
  on a down server. **The resync swap MUST be atomic:** hold `(tools, owners)` in ONE `volatile` `Index`
  record swapped in a single write — a `clear()`+`putAll()` across two `ConcurrentMap`s lets a concurrent
  turn read a half-rebuilt registry and `ToolCallBridge` then throws belt/registry-divergence on a tool
  that exists identically before and after (the review's second major). The engine-side registry wiring is
  the 5-edit recipe BUT `ForvumHome.mcpServers()` + `ConfigWatcher.WATCHED_SUBFOLDERS` were already
  pre-declared; the only new engine code is `ToolRegistry.onConfigChange` (filtered to `mcp-servers/`).
  A multi-launch `@QuarkusMainTest` over the bundled Web channel must set `quarkus.http.host-enabled=false`
  in the test profile (the launcher drives `QuarkusApplication.run()`, not the static `main` that unbinds
  HTTP for a one-shot) so the sequential launches don't contend for the listener. Redact secret material
  (`userinfo`/query) from a server URL before `mcp list` prints it (the Telegram never-log-a-secret-URL
  lesson). [P2-13]
- **A GitHub Copilot provider is the OpenAI recipe + a decoupled device-code OAuth login — and because
  `OpenAiChatModel.builder()` is the Quarkiverse-SWAPPED builder, it needs NO `JdkHttpClientBuilder` pin and
  NO `langchain4j-http-client-jdk` dep (unlike Ollama/Gemini).** `forvum-provider-copilot` (#42): the auth
  flow (confirmed from the OpenClaw source — `CLIENT_ID Iv1.b507a08c87ecfe98`, `device/code` + poll,
  `copilot_internal/v2/token` Bearer exchange, `proxy-ep`→base proxy.*→api.*, IDE headers, `expires_at`
  sec/ms) lives behind a langchain4j-free `CopilotHttp` seam (`JdkCopilotHttp` = pure `java.net.http`) so
  `CopilotAuth` is fully unit-testable with a scripted fake + injected sleeper/clock. `CopilotCredentials`
  stores the long-lived GitHub token `0600` at `state/credentials/github-copilot.json` and caches the
  short-lived Copilot token in memory (5-min margin) — re-exchanged at most once per ~25-min lifetime, never
  per turn. **The build trap:** `OpenAiChatModel.builder()...build()` throws `IllegalState: Unable to locate
  CDIProvider` outside an ArC context (the swapped builder reaches into CDI) AND the swap routes it through
  the Quarkus REST client (native-safe like the OpenAI/Anthropic providers), so DON'T pin
  `JdkHttpClientBuilder` (it neither helps — build still needs CDI — nor is needed — no empty native
  ServiceLoader for a swapped builder); make the build/`resolve` test a `@QuarkusTest`. **The guard trap:**
  Copilot's `resolve()` needs a live token exchange, so it CANNOT go in the offline app-level
  `ProviderResolveInAppClasspathTest` (which builds every other provider with no network) — cover the build
  + cached-`resolve` paths in-module under `@QuarkusTest` (credentials backed by a fake exchange) and
  document the guard exclusion. `copilot` is a `CommandMode` one-shot (login writes only a credential file).
  The login command stays offline-testable via a package-private `run(CopilotAuth, out, err)` + a fake
  `CopilotHttp` + a `RecordingCreds` stub (extends `CopilotCredentials` through its public ctor, overrides
  `storeGitHubToken`); jacoco-exclude only `JdkCopilotHttp` (live transport) and add a few error-branch
  tests (the OAuth flow is dense with defensive null/status branches → branch coverage needs them). [P2-COPILOT]
- **The DR-6a §9.2 `OutputFilter` is a sealed-disposition value in core + a sealed SPI in the SDK + an
  engine-only enforcement surface — the egress is filtered, the memory transcript is NOT.** P2-OUTPUTGUARD
  (#48): `FilteringOutcome` (sealed `Allowed`/`Redacted`/`Blocked`) lives in `forvum-core` (registered for
  native from `CoreReflectionRegistration` §6.3, NOT `@RegisterForReflection` — core bans `io.quarkus*`);
  `OutputGuard` (sealed, `permits AbstractOutputGuard`) + `OutputContext`/`HookLayer` live in `forvum-sdk`
  ROOT package (mirroring `ModelProvider`, Quarkus-free); `OutputFilteredException` is engine-local,
  mirroring `BudgetExhaustedException`'s behavioral pattern (unchecked, engine-caught terminal
  short-circuit) but NOT a Layer-0 value. The engine `OutputGuardChain` folds guards **fail-closed +
  most-restrictive-wins** (any `Blocked` dominates `Redacted` dominates `Allowed`; redactions chain
  forward + union; a guard that throws or returns null folds to `Blocked`). The default `SecretRedactionGuard`
  is **on by default** (opt-out `forvum.output-guard.secret-redaction.enabled=false`) and only ever
  Redacts (full Block is reserved for policy guards v0.1 does not ship). Hook is `TurnService.dispatch`
  AFTER `agent.respond` returns and BEFORE emitting `TokenDelta`/`Done` (the pre-channel-emit seam,
  `HookLayer.PRE_CHANNEL_EMIT` — the only one wired; `PRE_MEMORY_WRITE` is reserved, so the model
  transcript already persisted by `agent.respond` keeps the raw secret — only the channel egress is
  masked). A `Blocked` throws `OutputFilteredException`, caught by a NEW catch arm BEFORE the generic
  `RuntimeException` arm → `ErrorEvent(code="output_filtered")` on the `FallbackReasons.FILTERED` path.
  **TRAP (a green module build hid it):** a test-only blocking guard declared `@Alternative @Priority(N)`
  is enabled APP-WIDE (CDI: an alternative WITH a priority is global), so it blocked EVERY `@QuarkusTest`'s
  egress → 6 unrelated turn ITs flipped to a 1-event ErrorEvent. Isolate a test alternative with
  `@Alternative` and NO `@Priority`, enabled per-test via `QuarkusTestProfile.getEnabledAlternatives()`.
  `SecretRedactor` is pure (unit-tested without CDI): conservative regexes keyed on scheme prefixes that do
  not occur in prose (`sk-`/`xox[baprs]-`/`gh[posru]_`/`AIza`/`AKIA`/PEM blocks/`Bearer <opaque>`), each
  match → prefix + `***`, exact count, null/empty safe. **JaCoCo trap (CI-only):** adding the `OutputContext`
  record (a canonical-ctor null-check = executable lines + a branch) to the previously logic-free
  `forvum-sdk` broke its vacuously-passing coverage gate — and `verify` runs the JaCoCo `check` while
  `test` does NOT, so a green local `test` still fails CI. RUN `./mvnw verify` (not just `test`) before
  pushing. Fix honestly: a test for the new executable type (`OutputContextTest` covers the validation +
  the `HookLayer` enum) and extend the bridge exclude to `Abstract*.class` (`AbstractOutputGuard` is a
  logic-free `non-sealed` bridge like the `Abstract*Provider`s). New CLI command branches (`pair`/`devices`
  error/`--reason` paths) also pushed `forvum-app` branch coverage under its 0.70 override → cover the
  cheap JVM-reachable ones (approve `--reason`, reject-without-reason, invalid id) rather than relax. [P2-OUTPUTGUARD]
- **"Scope-upgrade approval" is CLI governance + visibility ONLY this PR — the turn-path enforcement of
  `approvedScopes` is deferred to #39 (ratified), so do not wire a third `ToolExecutor` gate here.**
  P2-PAIR-SCOPE (#44): `Device` grows `requestedScopes`/`approvedScopes` (`Set<PermissionScope>`) +
  `decisionReason`; a 4-arg ctor delegates to the 7-arg (backward compatible, a scope-less device file
  still parses, no migration). `forvum pair approve|reject` + `forvum devices` are `CommandMode` one-shots
  (file-only, no DB/watcher — keep them in sync with `RootCommand`). `DeviceConfigStore` is the
  `McpAddCommand` 0600-file recipe + a `safeId` anti-traversal guard, editing the parsed `ObjectNode` so
  unknown JSON fields survive, and parsing through the SAME `DeviceSpecReader` the engine/doctor use (no
  parallel schema). `ConfigDoctor.checkDevices` reuses `DeviceReader`+`DeviceSpecReader` as the oracle and
  warns on drift. **TRAPS the 6-dim review caught (all green-for-wrong-reason / unguarded edges):** (1)
  `decisionReason` must reflect the LAST decision — approve/reject with no `--reason` must CLEAR a stale
  prior reason (else an approve shows an old rejection's reason); (2) `store.read()` casting the top-level
  JSON to `ObjectNode` crashes with a raw stack trace on a non-object device file — validate `isObject()`
  and surface a contextual error the commands turn into a clean exit 1 / `(invalid: …)` line; (3) doctor
  must NOT report a REVOKED device as "a pending upgrade awaiting approval" (it was decided/rejected) —
  gate the drift warning on `!revoked`. Pin each with a regression test (stale-reason device, non-object
  file, revoked-with-drift device). [P2-PAIR-SCOPE]
- **The §3.6 four-span OTel baseline is `@WithSpan` on the three CDI beans + a PROGRAMMATIC span on the one
  plain object, and it is OFF BY DEFAULT via the M20 `main()` system-property lever — so it adds zero
  cold-start cost.** P2-15 (#40): `forvum.agent.turn` (`Agent.respond`), `forvum.tool.call`
  (`ToolExecutor.execute`), `forvum.graph.run` (`SupervisorGraph.run`) via `@WithSpan`; `forvum.llm.call`
  via a `Tracer` span in `FallbackChatModel.chat` — a plain object, so the `Tracer` is injected into
  `LlmSelector` and passed through a NEW 7-arg ctor (the 6-arg ctor delegates with a null tracer, leaving
  the 7 unit-test call-sites untouched; a null tracer skips the span). Each span carries
  `thread.is_virtual` (`Thread.currentThread().isVirtual()`). `Agent.respond` annotates BOTH overloads
  (2-arg channel entry, 3-arg cron entry) and sets attributes in the 3-arg via `Span.current()` — a CDI
  self-invocation (`this.respond(...)`) does NOT re-intercept, so the 2-arg's span is the active one and
  there is exactly one turn span on either entry. **Off-by-default:** `ForvumApplication.main` sets
  `quarkus.otel.sdk.disabled=true` (set-only-if-absent) before `Quarkus.run` when
  `OTEL_EXPORTER_OTLP_ENDPOINT` is unset (the host-enabled lever's twin — `sdk.disabled` is RUNTIME_INIT
  config, too early for a `StartupEvent`); the SDK-off path makes every `Span.current()`/`@WithSpan` a
  no-op, skips OTel resource detection, and keeps the native cold-start ~70 ms (gate 200 ms). dev/test set
  `%dev`/`%test.quarkus.otel.sdk.disabled=true`; engine tests default it off via a new
  `forvum-engine/src/test/resources/application.properties`, and `TurnSpanIT` re-enables it
  (`getConfigOverrides`) with a profile-scoped `@Produces InMemorySpanExporter` + a deterministic
  `OpenTelemetrySdk.getSdkTracerProvider().forceFlush().join(...)` (no sleep/poll). **TRAP the review
  caught:** OTel `Context` is thread-local and does NOT cross the worker virtual-thread fan-out (the same
  reason `CURRENT_AGENT` is re-bound in the worker), so a spawned worker's spans orphan — capture
  `Context parent = Context.current()` before the loop and submit `parent.wrap(task)`. `opentelemetry-sdk-testing`
  resolves via the OTel BOM transitive to `quarkus-opentelemetry` (no pin). [P2-15]
