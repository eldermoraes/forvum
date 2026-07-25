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
  Ollama via CLI) lives on `demo/conference-mvp`. **Phase-2 (v0.5, OpenClaw parity) has since SHIPPED and
  is released as `v0.5.0`** (`docs/ULTRAPLAN.md` §7.2): the Discord/Slack/Matrix/Signal/WhatsApp/Voice
  channels; the GitHub Copilot provider + Qdrant semantic memory; the web/shell/browser/sandbox/MCP-bridge
  tools; role-based scopes + device pairing + the approval gate + the output guard; and the OTel/CAPR
  observability baseline + live config editor. v0.5 is feature-complete but **not yet hardened for
  production**: the post-v0.5 hardening + remaining-parity + v1.0+ backlog is sequenced in
  `docs/IMPLEMENTATION-ORDER.md`, and Phase-3 (v1.0+) is the next roadmap arc (§7.3).

---

## 2. Tech stack (versions governed by `forvum-bom`)

Java 25 (LTS) · Maven `./mvnw` (3.9+) · Quarkus **3.33.x LTS** (3.33.2) · Quarkiverse
`quarkus-langchain4j-*` **1.11.0** (GA; targets Quarkus 3.33.2) · LangChain4j core
**1.16.2** (transitive via the Quarkiverse extension — do NOT pin independently) · LangGraph4j
**1.8.17** · Xerial SQLite JDBC (≥ 3.40.1.0, use latest
~3.53.x) · Hibernate ORM + Panache + Flyway · TamboUI 0.3.0 (Toolkit + JLine 3 backend) · WebSockets
Next · Quarkus Scheduler · OpenTelemetry · **GraalVM CE 25 / Mandrel 25.0.x-Final** (native builder;
pin the exact patch in CI) · JaCoCo · GitHub Actions.

`forvum-bom` is the single bump point: `quarkus-langchain4j-bom:1.11.0`, `langgraph4j-core:1.8.17`,
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

# Reactor verify — full test suite (JaCoCo coverage gates 80% line / 75% branch are wired + ENFORCED — see §11, #69)
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
  and un-hinted reflection are excluded via `forvum-bom` and banned by a CI import grep
  (`.github/native-discipline.sh`, X1) that also bans dynamic class loading outside the JVM-only
  `~/.forvum/plugins/` drop-in; a companion grep (`.github/reflection-registration.sh`) enforces
  `@RegisterForReflection` on every `.dto.`-package record in a Quarkus-bearing module.
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
- **Keep project docs in sync (source-of-truth precedence).** `docs/ULTRAPLAN.md` is the **normative**
  architectural source of truth; its preamble states the full precedence (running code / the `v0.5.0`
  tag → ULTRAPLAN → `docs/IMPLEMENTATION-ORDER.md` → `docs/ISSUES.md`). On any commit or PR that changes
  behavior, build, architecture, status, conventions, or roadmap, update the affected docs **in the same
  change** (`README.md`, `CONTRIBUTING.md`, `CLAUDE.md`, `docs/CONTEXT-ENGINEERING-MAPPING.md`, ULTRAPLAN)
  and run `bash .github/docs-drift.sh` (also a CI gate). **Milestone/release-closure checklist:**
  1. Flip the phase/milestone status in `README.md` §Roadmap, `CLAUDE.md` §1, and ULTRAPLAN §7.2/§7.3.
  2. Reclassify each touched Context-Engineering capability in `docs/CONTEXT-ENGINEERING-MAPPING.md`
     (`[shipped]` / `[partial]` / `[planned]` / `[gap → #NNN]`) and link the owning issue for any gap.
  3. State a real runtime gap as `as-built … → #NNN`, **never** as a delivered/enforced boundary (#179 rule).
  4. Re-sequence remaining work in `docs/IMPLEMENTATION-ORDER.md`; mark a superseded `docs/ISSUES.md`
     proposal with an as-built marker (see that file's legend), never by deleting the original proposal.
  5. Add any new canonical status fact to `.github/docs-drift.sh` so the gate guards it.

---

## 11. Testing discipline (§10 of `docs/ULTRAPLAN.md`)

- **TDD as process commitment** — each milestone's `Verify` script is the test that lands *before*
  implementation passes (Red → Green → Refactor, enforced in PR review).
- **Test pyramid:** unit `*Test` → integration `*IT` (`@QuarkusTest`, real SQLite via `@TempDir`) → E2E
  under `forvum-app/.../e2e/` (ten scripts).
- **Coverage gates (ENFORCED):** JaCoCo 80% line (parent) + 75% branch are wired into the build and gate
  `./mvnw verify` per module (X3 / #69 — see the [X3] lesson below and `pom.xml`). The Pitest mutation
  ramp in `forvum-core` (50% killed greenfield → 70% Phase 2) stays a signal, not a gate, until a baseline
  exists. So: coverage is a hard gate; mutation thresholds remain signals.
- **Property-style tests (JUnit 5) MANDATORY for parsers/records:** `ModelRef.parse` roundtrip,
  `AgentEvent` Jackson roundtrip, `CostBudget` invariants, `PermissionScope.fromName` failure modes.
  Expressed with `@ParameterizedTest` + `@EnumSource`/`@MethodSource` over curated edge cases plus
  seeded-random inputs (a fixed `Random` seed keeps failures reproducible) — **no third-party
  property library**. Quarkus-free modules (`forvum-core`, `forvum-sdk`) use the JUnit line from
  `quarkus-bom`; no `junit-bom` override is needed.
- **Native-mode parity — MANDATORY** (§5). Parser/record (M2), provider HTTP (M9–M12), TUI (M15), web
  (M16), Telegram (M17), and the M20 cold-start gate run native.
- **Per-turn performance gates** (excluding inference, via `FakeProvider`): TUI ≤200 ms, Web ≤300 ms,
  Telegram ≤500 ms — ENFORCED (X4/#70) by `ChannelLatencyGateTest` (a `forvum-app` `@QuarkusTest` in
  `./mvnw verify`): it drives the real turn through the shared SDK `ChannelTurnDriver` with the in-process
  `FakeModelProvider`, warms persistence in `@BeforeEach`, and over 60 dispatches/channel asserts the
  **median ≤ the budget** (the typical-turn regression signal) plus a **p95 ≤ budget × 3 CI-headroom
  ceiling**. The amendment is intentional: a warm fake-model turn pays a real per-turn SQLite round-trip
  (ensure-session + the eager compaction read), so the measured median is ~80–110 ms and a loaded runner's
  GC outliers blow the raw 200 ms p95 (e.g. `median=88 ms, p95=317 ms`); the median enforces the documented
  budget and the ×3 p95 ceiling is the sanctioned CI-hardware multiplier (§5/§10 carve-out), not a silent
  drop. A regression alarm on the shared engine turn, not a per-channel transport micro-benchmark.
- **Flaky-test quarantine:** `*-LiveTest` `@Tag("live")`, default-off, nightly with retry budget 1 —
  except `OllamaNativeTurnIT` (the Risk #5 native turn), which the per-PR `native-turn` job gates on, also
  retry budget 1.
- **Security-test layer** under `forvum-app/.../security/`: prompt-injection → no tool escalation; path
  traversal → denied; spawn-boundary identity override → rejected; `PermissionScope` mismatch → denied
  + audited.
- **CI security gates (#174):** per-PR = blocking `dependency-review` + `gitleaks` (with the
  `**/src/test/**` fake-fixture allowlist) + `CodeQL` (Java + Actions) + `actionlint`/`shellcheck`
  (`security.yml`/`codeql.yml`); weekly Trivy deep scan of the shipped image; release = CycloneDX SBOMs
  (Maven closure + image) + OIDC build-provenance attestations (4 binaries + GHCR image) + a blocking
  pre-push image scan (`release.yml`). Third-party Actions are SHA-pinned (Dependabot updates them).
  Committed thresholds / SLA / suppression policy: `docs/SECURITY-GATES.md`.
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
  `@VirtualThreadUnit` + `@ShouldNotPin` — and wiring that gate is a tracked follow-up (X2/#68), deferred
  deliberately: any real engine-turn test boots through SQLite, whose JNI pins the carrier (the one
  documented non-first-party pin), so a bare `@ShouldNotPin` on a turn fails by design — the gate first
  needs the `@ShouldPin`/allowlist machinery + the `org.sqlite` stack fingerprint (`pinning-allowlist.txt`
  already names it). The enforced concurrency checks today are the static `synchronized`/Mutiny greps
  (`.github/concurrency-guardrails.sh`; allowlists `pinning-allowlist.txt` / `vt-allowlist.txt`).

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

Generalizable lessons from completed milestones. **The full lesson texts live under `docs/lessons/`**,
one topic file per area — the index below is only a one-line hook per lesson. **Before implementing in
an area, read that area's topic file.** When you add a lesson: append its verbatim text to the relevant
`docs/lessons/*.md` file **and** add its index line here (both, in the same change). Line form:
`- [tag] hook → docs/lessons/<file>.md`.

### GraalVM native image — `docs/lessons/native-image.md`
- [M4] Module native-compiles only once forvum-app depends on it; wire in + boot gracefully → docs/lessons/native-image.md
- [M15] TamboUI's two terminal backends both fail GraalVM 25 native; ship none → docs/lessons/native-image.md
- [M20] 5 s macOS cold-start stall is getLocalHost(), not the HTTP bind → docs/lessons/native-image.md
- [M20/Risk#5] Native binary couldn't run a turn; only a live native turn catches it → docs/lessons/native-image.md
- [Risk#5] Native real-provider CI turn needs a `forvum ask` command (ITs have no stdin) → docs/lessons/native-image.md

### Quarkus config, CDI & module recipes — `docs/lessons/quarkus-config-cdi.md`
- [M4] New Quarkus library module recipe (quarkus-junit, no build goal, beans.xml) → docs/lessons/quarkus-config-cdi.md
- [M4] WatchService discipline: watch late-created subfolders, isolate observers, debounce → docs/lessons/quarkus-config-cdi.md
- [M6] Register a custom CDI context via a BuildCompatibleExtension, not a @BuildStep → docs/lessons/quarkus-config-cdi.md
- [M7] @AgentScoped bean recipe: field injection, read CURRENT_AGENT at call time → docs/lessons/quarkus-config-cdi.md

### Persistence & memory — `docs/lessons/persistence-memory.md`
- [M5] SQLite needs Hibernate metadata-access + version-check off; trigger Flyway from StartupEvent → docs/lessons/persistence-memory.md
- [M7] Turn atomicity is persist-after-success, not @Transactional over the whole turn → docs/lessons/persistence-memory.md
- [P2-8] Session replay interleave merges by turn-logical id order, not created_at → docs/lessons/persistence-memory.md
- [P2-8] `forvum replay` isn't a one-shot; native IT seeds via a two-launch dance → docs/lessons/persistence-memory.md
- [P2-COMPACT] Prefix-preserving compaction reclaims the oldest dropped id for its summary → docs/lessons/persistence-memory.md
- [P2-5] A reference Layer-3 memory plugin reuses the Telegram blocking-REST recipe → docs/lessons/persistence-memory.md
- [P2-5] Normalized [0,1] score contracts must reject NaN explicitly → docs/lessons/persistence-memory.md
- [P2-5] MemoryProvider.retrieve SPI plus its first implementor ship in the same PR → docs/lessons/persistence-memory.md
- [P3-2/Risk#5] sqlite-vec resolved = pure-Java linear scan; embedding DTOs need native reflection → docs/lessons/persistence-memory.md
- [#173] Owner-only state perms: the 0700 directory is the durable guarantee, repair-and-warn → docs/lessons/persistence-memory.md

### Model providers — `docs/lessons/providers.md`
- [M7] An SPI method lands in its first consumer's milestone, not the plugin's → docs/lessons/providers.md
- [M12] Select the LangChain4j HTTP client factory once, app-wide, via a startup observer → docs/lessons/providers.md
- [M12] ai-gemini fails no-config native boot; add an `unset` api-key placeholder → docs/lessons/providers.md
- [P2-COPILOT] Copilot = OpenAI recipe + device-code OAuth; swapped builder needs no JDK-client pin → docs/lessons/providers.md
- [P2-10] The provider-onboarding wizard's real work is the 0600-file credential bridge → docs/lessons/providers.md

### Channels — `docs/lessons/channels.md`
- [M16] A channel drives turns via the SDK ChannelTurnDriver, never depending on the engine → docs/lessons/channels.md
- [M16] A channel turn must self-activate the request context and catch its own failures → docs/lessons/channels.md
- [M16] A Layer-3 web library isn't quarkus:dev-startable despite bundling vertx-http → docs/lessons/channels.md
- [M16] websockets-next test: use BasicWebSocketConnector with the full URI → docs/lessons/channels.md
- [M17] Layer-3 config defaults go in microprofile-config.properties; never log a secret URL → docs/lessons/channels.md
- [P2-CH/discord] Discord gateway rides websockets-next CLIENT mode; socket-free protocol, reconnect/RESUME → docs/lessons/channels.md
- [P2-3/#28] Voice serve-gate must mirror onStart isReady() exactly; bounded subprocess drain → docs/lessons/channels.md
- [#170] Fail-closed channel admission is a shared forvum-sdk policy, not seven predicates → docs/lessons/channels.md

### Tools & plugins — `docs/lessons/tools.md`
- [M13] A milestone's "Files" list can be stale; grep on disk before scaffolding → docs/lessons/tools.md
- [M13] The tool SPI follows the prelude-in-consumer pattern, contribution-only → docs/lessons/tools.md
- [M14] A tool module is the provider Layer-3 recipe minus the langchain4j extension → docs/lessons/tools.md
- [P2-6] Bundle Maven Resolver via maven-resolver-supplier (no-DI), not the Guice/Sisu path → docs/lessons/tools.md
- [P2-6] maven-resolver-supplier pulls a split-version graph; pin the whole set in the BOM → docs/lessons/tools.md
- [P2-6] Resolve-from-fast-jar throws a LinkageError until resolver artifacts are parent-first → docs/lessons/tools.md
- [P2-13] MCP bridge must use Quarkiverse QuarkusHttpMcpTransport, not the OkHttp-SSE langchain4j one → docs/lessons/tools.md
- [P2-13] Materializing MCP tools is a network call; gate onStart on one-shot, atomic swap → docs/lessons/tools.md
- [P2-2/#27] shell.exec = filesystem recipe + LIST-form ProcessBuilder; first userConfirmRequired tool → docs/lessons/tools.md
- [P2-2/#27] ShellExecutor: close child stdin immediately, bound the post-settle drain join → docs/lessons/tools.md
- [TOOLS-WEB] web.fetch SSRF defense is layered; set the restricted-header latch at main() time → docs/lessons/tools.md
- [#26] A CDP path="/" endpoint collides on the app classpath; use BasicWebSocketConnector → docs/lessons/tools.md
- [#192] Pluggable web-search backend is module-internal; keyless DDG default flips search to network-on-invoke → docs/lessons/tools.md
- [#171] Strict plugin checksums fail on missing/mismatch; fixtures encoded the vulnerability → docs/lessons/tools.md
- [#186] Model-callable TTS reuses the voice subprocess pair (stdin-fed); static SecureRandom breaks native → docs/lessons/tools.md
- [#184] A usable default belt is TWO fixes: widen the scaffold belt AND wire identityId, else anonymous filters it all → docs/lessons/tools.md
- [#184] `forvum tools`/doctor gather from Instance<ToolProvider> (registry empty one-shot); skip the MCP bridge; configGaps owns the hint → docs/lessons/tools.md

### Engine, graph & scheduling — `docs/lessons/engine-graph.md`
- [M7] Guard registry mutations; keep blocking IO off computeIfAbsent lock paths → docs/lessons/engine-graph.md
- [M18] LangGraph4j serializes graph state every step; keep non-Serializable types out → docs/lessons/engine-graph.md
- [M18] node_async builds a LambdaMetafactory lambda, not a Proxy; no reflection config needed → docs/lessons/engine-graph.md
- [M18] LangGraph4j recursionLimit counts every node execution, not rounds; size it accordingly → docs/lessons/engine-graph.md
- [M18] Tool execution enters the turn via the graph with self-dispatch, not @Tool reflection → docs/lessons/engine-graph.md
- [M18] Decouple the supervisor graph from LlmSelector/AgentRegistry via a WorkerRunner seam → docs/lessons/engine-graph.md
- [M19] Pure programmatic Quarkus scheduling needs scheduler.start-mode=forced → docs/lessons/engine-graph.md
- [M19] A cron that turns invalid on edit must be unscheduled, not left firing stale → docs/lessons/engine-graph.md
- [M19] A per-cron model override is only proven if it differs from the default → docs/lessons/engine-graph.md
- [P2-CRON-DELIVERY] No outbound channel-send API; route cron output to an isolated result sink → docs/lessons/engine-graph.md
- [P2-TASKLEDGER] A sink SPI is a plain forvum-sdk interface with the engine as sole implementor → docs/lessons/engine-graph.md
- [P2-12] Decode the final message against a JSON schema as string→JsonNode, not a POJO → docs/lessons/engine-graph.md
- [DR-5] MemoryPolicy is a flat Layer-0 record driving the Layer-1 retrieve SPI → docs/lessons/engine-graph.md
- [P2-15] Four-span OTel baseline via @WithSpan, off-by-default; propagate Context to worker VTs → docs/lessons/engine-graph.md
- [DR-8/PR-8] A round merged into docs isn't merged into code; verify types on disk → docs/lessons/engine-graph.md
- [PR-8] Retrieval, compression, replay, cycles are all SupervisorGraph seams via GraphTurnRequest ctors → docs/lessons/engine-graph.md
- [#124] A native-clean JSON-Schema lib is proven only by a real native validate; adopt narrowly → docs/lessons/engine-graph.md
- [#169] Budget enforcement: cost by decorator, tool count by ScopedValue; unwrap ExecutionException → docs/lessons/engine-graph.md
- [#169] Activating a dormant exception: reconcile the doc to as-built, don't add dead code → docs/lessons/engine-graph.md
- [#169] Telemetry to a null sink isn't a regression; don't add speculative observability → docs/lessons/engine-graph.md
- [#176] A compression-failure fallback must be bounded, reusing the existing threshold → docs/lessons/engine-graph.md
- [#177] Retire ephemeral workers in run()'s finally; destroy the @AgentScoped context directly → docs/lessons/engine-graph.md
- [#178] Atomic capability-safe hot-reload via an immutable per-turn lease, no refcount/drain → docs/lessons/engine-graph.md

### Security & authorization — `docs/lessons/security.md`
- [P2-11] A second authz gate sits at the CURRENT_AGENT ScopedValue seam, enforce-iff-bound → docs/lessons/security.md
- [P2-11] Role-based scopes cable role-sets above PermissionScope, not new enum constants → docs/lessons/security.md
- [P2-4] A new config-file registry (devices/) is a fixed five-edit recipe; enforce opt-in → docs/lessons/security.md
- [TEST-SEC] The prompt-injection test drives the belt gate end-to-end via a scripted model → docs/lessons/security.md
- [DR-6a] A security design round confirms what's built + names deferrals, not new gates → docs/lessons/security.md
- [P2-OUTPUTGUARD] OutputFilter is a sealed core value + SDK SPI; egress filtered, transcript not → docs/lessons/security.md
- [P2-PAIR-SCOPE] Scope-upgrade approval is CLI governance only; no third ToolExecutor gate here → docs/lessons/security.md
- [P2-14] USER_CONFIRM_REQUIRED is the third tool gate; the queue owns the lifecycle, append-only → docs/lessons/security.md
- [P2-14] The approval resolution-mode seam lives in forvum-sdk; engine reads it on the turn's VT → docs/lessons/security.md
- [P2-14] Persist the pending approval row before blocking; never hold a connection across the wait → docs/lessons/security.md
- [P2-14] Restart-recovery: a pending row survives; approving re-dispatches with a turn-scoped grant → docs/lessons/security.md
- [P2-14] Web approval surface mirrors CaprDashboardRoute; server-path @Route, zero cold-start → docs/lessons/security.md
- [#167] Agent role cap = caller ∩ agent-roles; empty roles means no cap (fail-open opposite) → docs/lessons/security.md
- [#166] A prior seam often names its successor; read the javadoc before inventing → docs/lessons/security.md
- [#172] Sanitize a channel-visible failure at its one construction seam; genericize untrusted text → docs/lessons/security.md
- [#174] CI security gates: placement, canary PRs both directions, four pin-the-pin traps → docs/lessons/security.md

### Testing & CI — `docs/lessons/testing-ci.md`
- [M4] Make fixtures exercise the absent/created-later state, not the happy pre-populated one → docs/lessons/testing-ci.md
- [M4] Harness: run Quarkus builds with -B -Dstyle.color=never; read the Surefire txt reports → docs/lessons/testing-ci.md
- [M7] A shared @TestProfile HOME shares the SQLite DB across same-profile tests; scope assertions → docs/lessons/testing-ci.md
- [M7] Run a deep, adversarially-verified review before a milestone merge → docs/lessons/testing-ci.md
- [M16] -Djdk.tracePinnedThreads was removed in JDK 24+; the stderr grep is vacuous → docs/lessons/testing-ci.md
- [X3] Measure the JaCoCo baseline before setting the gate; exclude only uncoverable code → docs/lessons/testing-ci.md
- [X6] Author span-less e2e scenarios against existing machinery + observable DB side-effects → docs/lessons/testing-ci.md
- [X7] A "milestone gap" can be a docs-ownership gap; fold, don't multiply milestones → docs/lessons/testing-ci.md
- [P2-14] Wall-clock/poll-window assertions are flaky on loaded CI; assert semantics, warm persistence → docs/lessons/testing-ci.md
- [P2-2/#27] A parallel build-agent workflow must not pass -Djacoco.skip; the integrator pays coverage → docs/lessons/testing-ci.md

### CLI app & commands — `docs/lessons/cli-app.md`
- [M20] The cold-start lever skips DB/IO in every startup observer; test both directions → docs/lessons/cli-app.md
- [M20] Unbind HTTP for a one-shot by setting host-enabled=false in @QuarkusMain main() → docs/lessons/cli-app.md
- [M20] Source --version from the build; init scaffolds ~/.forvum owner-only → docs/lessons/cli-app.md
- [P2-9] `forvum doctor` must drive the real loaders, never a second, drifting schema → docs/lessons/cli-app.md
- [X6] A server-only dashboard @Route must not touch the command-mode cold-start path → docs/lessons/cli-app.md
- [UX-INSTALL] A feature-complete binary failed the "stranger installs it" test; never exit silently → docs/lessons/cli-app.md
- [P3-6] The Dev UI config editor is a dev-build-gated @Route, not a Dev UI card → docs/lessons/cli-app.md
