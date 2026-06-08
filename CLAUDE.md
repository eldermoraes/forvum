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
  the DB/IO, gate it on `commandMode.isOneShot()` too (cheap side-effect-free ones — `ToolRegistry`,
  `HttpClientFactorySelector` — are fine left ungated). Test the lever with a recording collaborator (a
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
