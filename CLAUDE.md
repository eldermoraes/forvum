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
- Status: active design + early implementation. M1–M4 complete (multi-module reactor + Tier-1 domain
  contracts: records, sealed `AgentEvent`, enums, `PermissionScope`, budget types; plus the Layer-1
  plugin SDK: sealed provider interfaces, `@ForvumExtension`, re-exported `@RegisterForReflection`;
  plus the M4 file-based config loader with `WatchService` hot reload — `ForvumHome` + `ConfigWatcher`
  firing a CDI `ConfigurationChangedEvent`, per-subfolder readers); M5–M20 planned. A working vertical
  slice (one agent vs local Ollama via CLI) lives on
  `demo/conference-mvp`. Not production-ready.

---

## 2. Tech stack (versions governed by `forvum-bom`)

Java 25 (LTS) · Maven `./mvnw` (3.9+) · Quarkus **3.33.x LTS** (3.33.1) · Quarkiverse
`quarkus-langchain4j-*` **1.11.0.CR1** (PRE-RELEASE; stable fallback **1.10.0**) · LangChain4j core
**1.15.1** (transitive via the Quarkiverse extension — do NOT pin independently; **1.14.1** on the
stable-1.10.0 fallback) · LangGraph4j **1.8.17** · Xerial SQLite JDBC (≥ 3.40.1.0, use latest
~3.53.x) · Hibernate ORM + Panache + Flyway · TamboUI 0.3.0 (Toolkit + JLine 3 backend) · WebSockets
Next · Quarkus Scheduler · OpenTelemetry · **GraalVM CE 25 / Mandrel 25.0.x-Final** (native builder;
pin the exact patch in CI) · JaCoCo · GitHub Actions.

`forvum-bom` is the single bump point: `quarkus-langchain4j-bom:1.11.0.CR1`, `langgraph4j-core:1.8.17`,
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
│   ├── channels: forvum-channel-tui | -web | -telegram
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
# Native single-binary — PRIMARY target
./mvnw -f forvum-app -Pnative package        # → forvum-app/target/forvum-app-<version>-runner
                                             #   startup <200 ms, RSS <50 MB, no end-user JVM
# CI / no local GraalVM: container build
./mvnw -f forvum-app -Pnative package -Dquarkus.native.container-build=true

# JVM fast-jar — development + JVM drop-in plugins
./mvnw -pl forvum-app -am package            # → forvum-app/target/quarkus-app/quarkus-run.jar
java -jar forvum-app/target/quarkus-app/quarkus-run.jar

# Dev mode (Dev UI + live reload) — developing Forvum itself
./mvnw -f forvum-app quarkus:dev             # Dev UI at /q/dev/ (live agent reload, CAPR dashboard,
                                             # provider-call inspector, Concurrency card)

# Reactor verify (JaCoCo gates: 80% line at parent, 75% branch — §11)
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
nightly only.

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
  `WatchService`** OS-polling semantics, with a written justification in its Verify block. A
  per-provider native failure is marked JVM-only in release notes ONLY with an upstream issue filed;
  for Vertex/Gemini the preferred remedy is switching to the REST `quarkus-langchain4j-ai-gemini`
  extension, not a JVM-only carve-out.

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
| `main` | **default**; ships the multi-module reactor + architectural design docs. PRs target `main` unless demo-specific. |
| `demo/conference-mvp` | conference-demo vertical slice (one agent vs local Ollama via CLI). Demo-specific PRs target this branch. |
| `gh-pages` | published site (`forvum.ai` / GitHub Pages; brand assets under `docs/brand/`). |

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
- **Coverage gates:** JaCoCo 80% line (parent) + 75% branch. Pitest mutation testing starts in
  `forvum-core` (50% killed greenfield → 70% Phase 2); mutation thresholds are signals until a baseline
  exists, coverage gates are gates.
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
- **Flaky-test quarantine:** `*-LiveTest` `@Tag("live")`, default-off, nightly with retry budget 1.
- **Security-test layer** under `forvum-app/.../security/`: prompt-injection → no tool escalation; path
  traversal → denied; spawn-boundary identity override → rejected; `PermissionScope` mismatch → denied
  + audited.
- **Concurrency discipline (§3.8):** **virtual threads first** — blocking, imperative code on virtual
  threads is the default model, not reactive programming; reactive types (Mutiny/Reactor) are allowed
  only at a framework-mandated boundary bridged to a VT, with a justification, and reactive code where
  a VT would have worked is a PR rejection reason. Virtual threads per request;
  `-Djdk.tracePinnedThreads=full` in dev/test + CI grep for `Thread pinned`; `synchronized` forbidden
  in `forvum-engine` / `forvum-channel-*` hot paths (CI grep) — use `ReentrantLock` /
  `java.util.concurrent` / atomics.

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
