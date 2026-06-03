# CLAUDE.md — Forvum

Guidance for Claude Code (and any coding agent) working in this repository. This file is
self-sufficient: it defines what Forvum is, how to build/run/test it, the architecture, the
native-first mandate, and the conventions you must follow. When it conflicts with anything, the
architectural source of truth is `docs/ULTRAPLAN.md`.

Answer with high-confidence statements only: verify in code or in `docs/ULTRAPLAN.md` before
asserting a fact; do not guess.

---

## 1. What Forvum is

Forvum is a **local-first, open-source personal AI assistant on the JVM** (Java 25 + Quarkus +
LangChain4j + LangGraph4j), the spiritual successor to OpenClaw (a TypeScript assistant) rebuilt in
Java for a single-binary native install. The name fuses **Forum** (a public space for deliberation)
and **Quorum** (the minimum voices for a decision to stand): coordination, evidence, and control are
first-class, and every turn, tool call, fallback, and judgment is observable in the ledger.

Central principle — **fixed code, configurable behavior**: new agents, sub-agents, skills,
identities, cron jobs, MCP servers, and channel/provider enablement require only editing files under
`~/.forvum/` (no recompile; dev-mode hot reload; production `WatchService` hot reload). Adding a
brand-new *Java* plugin (a new channel/provider/native tool) does require repackaging `forvum-app` —
the deliberate trade-off for a reflection-free native binary.

- Repo: `https://github.com/eldermoraes/forvum` · License: **Apache 2.0** · `groupId = ai.forvum`,
  `version = 0.1.0-SNAPSHOT`.
- Source of truth: `docs/ULTRAPLAN.md` (full architectural vision + M1–M20 roadmap; when in doubt,
  that file wins). Founding paradigm: `docs/CONTEXT-ENGINEERING.md` (PT source) →
  `docs/CONTEXT-ENGINEERING-MAPPING.md` (EN mapping onto Forvum).
- Status: active design + early implementation. M1 complete (multi-module reactor + Tier-1 contract
  design rounds); M2–M20 planned. A working vertical slice (one agent vs local Ollama via CLI) lives
  on `demo/conference-mvp`. Not production-ready.

---

## 2. Tech stack (versions governed by `forvum-bom`)

Java 25 (LTS) · Maven `./mvnw` (3.9+) · Quarkus **3.33.x LTS** (3.33.1) · Quarkiverse
`quarkus-langchain4j-*` **1.11.0.CR1** (PRE-RELEASE; stable fallback **1.10.0**) · LangChain4j core **1.15.1** (transitive via the Quarkiverse
extension — do NOT pin independently; **1.14.1** on the stable-1.10.0 fallback) · LangGraph4j **1.8.17** · Xerial SQLite JDBC (≥ 3.40.1.0, use
latest ~3.53.x) · Hibernate ORM + Panache + Flyway · TamboUI 0.3.0 (Toolkit + JLine 3 backend) · WebSockets Next · Quarkus Scheduler ·
OpenTelemetry · **GraalVM CE 25 / Mandrel 25.0.x-Final** (native builder; pin the exact patch in CI)
· JaCoCo · GitHub Actions.

`forvum-bom` is the single bump point: `quarkus-langchain4j-bom:1.11.0.CR1`, `langgraph4j-core:1.8.17`, `tamboui-bom:0.3.0`,
`sqlite-jdbc` (latest), test libs (JLine 3 comes transitively via `tamboui-bom`). Quarkus-managed deps
(Flyway, OpenTelemetry) inherit the platform BOM version — never pin them independently.

---

## 3. Module architecture (4 layers, one bounded context per module/sub-package)

Maven multi-module reactor under `ai.forvum`. The layering enforces **core stays
extension-agnostic** at the build level: `forvum-engine` has zero compile dependencies on any
concrete channel/provider/tool module.

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

Root `pom.xml` on `main` currently declares `forvum-bom, forvum-core, forvum-sdk, forvum-engine,
forvum-app`; the Layer-3 extension modules are added milestone by milestone.

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

Always invoke the committed Maven Wrapper `./mvnw` (generated with `mvn wrapper:wrapper
-Dmaven=3.9.14`, committed so contributors and CI share an identical Maven). Prereqs: Java 25, Maven
3.9+ (or `./mvnw`), GraalVM CE 25 / Mandrel 25.0.x-Final for the native profile.

**Native is the primary build target** (§5). Use it as the default acceptance path; use fast-jar for
the inner dev loop and for the JVM drop-in-plugin path only.

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

# Reactor verify (JaCoCo gates: 80% line at parent, 75% branch per §10)
./mvnw verify
```

**Run tests via the Quarkus Agent Dev MCP, never raw `mvn test`** (§7). From a subagent:
`quarkus/callTool` `devui-testing_runTests` (all) or `devui-testing_runTest` with
`{"className":"ai.forvum.…"}` (one). The milestone's `Verify` script in §7.1 is the **contract** the
run must satisfy. Native integration tests (`-Pnative`, `@QuarkusIntegrationTest`, Failsafe) remain
a Maven step inside the native profile and are the M20 gate.

Test layout: unit `*Test` (Surefire, no Quarkus boot/IO) → integration `*IT` (`@QuarkusTest`, real
SQLite via `@TempDir`) → E2E under `forvum-app/src/test/java/ai/forvum/e2e/` (ten scripts, landing
milestone by milestone). Live-provider tests are `*-LiveTest` `@Tag("live")`, default-off in CI,
nightly only.

---

## 5. Native-first mandate (HARD requirements — requirement #1)

GraalVM native is the **primary, mandatory** build target — not co-equal with fast-jar. Write every
contribution as if native is the only target; CI enforces it.

- **The native build is `--enable-preview`-free by construction.** Preview features are PROHIBITED on
  the native path.
- **`ScopedValue` (JEP 506) is FINAL in JDK 25** — it is the sanctioned `@AgentScoped`
  context-propagation mechanism and needs no flag. Use the final builder form
  `ScopedValue.where(KEY, v).call(body)` (`.run(...)` for void). The only native risk here is the ArC
  `InjectableContext` build-time registration, addressed at M6.
- **`StructuredTaskScope` (JEP 505) stays preview in JDK 25 → it is NOT used in v0.1.** Structured
  fan-out is `Executors.newVirtualThreadPerTaskExecutor()` (try-with-resources) + `CompletionStage`
  join, or LangGraph4j orchestration — this is the committed design, not a fallback. Re-evaluate
  structured concurrency only after the JEP finalizes (post-JDK 26).
- **No runtime reflection** outside framework-managed paths: every JSON-serialized type is a record
  (reflection-free canonical constructor); every DTO carries `@RegisterForReflection` (a Maven
  enforcer fails the build if one is missing); tool-spec lookup goes through a build-time registry,
  not classpath scanning.
- **Build-time plugin discovery:** `@ForvumExtension` + `META-INF/forvum/plugin.json` scanned by a
  Quarkus `BuildStep` that records contributed providers and emits reflection hints. `ServiceLoader`
  is a fast-jar-only fallback, not exercised in native. The `~/.forvum/plugins/` drop-in path is
  JVM-fast-jar-only **by design** (native users rebuild) — a documented architectural property, not a
  carve-out.
- **Vetoed dependencies:** `sun.misc.Unsafe`, runtime bytecode generation (CGLib, runtime Javassist),
  and un-hinted reflection are excluded via `forvum-bom` and banned by a CI import grep.
- **LangGraph4j native:** graph-state types are records carrying `@RegisterForReflection` with
  hand-authored reachability metadata under `forvum-engine/src/main/resources/META-INF/native-image/`.
- **CI parity is MANDATORY** (was "selective"): every PR builds JVM + native on `linux-amd64` and
  `macos-arm64`; every milestone M1–M20 native-COMPILES and runs its native smoke path; the native
  smoke fails the PR if cold-start > 200 ms. The **only** sanctioned carve-out is a *behavioral*
  native assertion skip — never the native compile — when the milestone's risk is provably
  JVM-host-only; today the sole defensible case is **M4 `WatchService`** OS-polling semantics, with a
  written justification in its Verify block. A per-provider native failure (Risk #5) is marked
  JVM-only in release notes ONLY with an upstream issue filed; for Vertex/Gemini the preferred remedy
  is switching to the REST `quarkus-langchain4j-ai-gemini` extension, not a JVM-only carve-out.

---

## 6. Context Engineering (the conceptual foundation)

Forvum is built around `docs/CONTEXT-ENGINEERING.md` and its EN mapping
`docs/CONTEXT-ENGINEERING-MAPPING.md`. Treat the four pillars and the topology as **structural
properties of the architecture**, not aspirational notes:

- **Write** — three-tier memory scratchpad surface (in `~/.forvum/` + the SQLite ledger).
- **Select** — `MemoryProvider` retrieval (vector/graph/metadata/hybrid), tool filtering, model
  routing.
- **Compress** — write-time summarization of oversized tool results / retrieved memory through a
  small-and-fast proxy model before re-entering the context window; session compaction.
- **Isolate** — `@AgentScoped` per-agent state; only a compressed digest crosses the
  Orchestrator→worker boundary, never a raw worker window.
- **Topology** — Orchestrator-Workers hub-and-spoke; parallel specialist workers (on virtual
  threads, §3.8) replace a serial cascade. CAPR spans are the operational-traceability foundation.

§2.7 of `docs/ULTRAPLAN.md` owns the pillar → module mapping. When you add or change a module, state
which pillar it serves.

---

## 7. Using the `quarkus-agentic@eldermoraes` plugin (mandatory tooling — requirement #4)

The plugin is the **canonical tool** for all Quarkus work in Forvum. It has two composing parts; its
own `CLAUDE.md` is the authoritative source of stack conventions (Java 25, virtual threads,
ScopedValue over ThreadLocal, records/sealed/pattern-matching, platform BOMs with no pinned extension
versions, CDI-first, WebSockets Next streaming, dual JVM/native build, declarative
`@RegisterAiService` + Agentic annotations). Reference it — do not restate it.

- **Quarkus Agent Dev MCP** (`quarkus/*`: `create`, `update`, `start`, `skills`, `searchDocs`,
  `searchTools`, `callTool`) — MANDATORY for project/module creation, extension selection,
  configuration, version checks, API usage, troubleshooting, and running tests. Never create a
  Quarkus project or add an extension by hand; never answer a Quarkus question from model memory
  before consulting it. New module: `quarkus/create` → `quarkus/skills` (call BEFORE writing any
  code/tests) → `quarkus/searchDocs` → `quarkus/searchTools` → `quarkus/callTool`. If a required tool
  is unavailable, **stop and report** — do not fall back to model memory or generic web search.
- **Shape-mismatch reconciliation (BINDING):** the skill's templates are a per-module starting point,
  NOT the reactor skeleton. The reactor topology (parent + `forvum-bom` + the four layers) is
  hand-authored and owned by M1. For each new Quarkus-bearing module, run `quarkus/create` (throwaway
  app) to harvest the current platform version + extension wiring, then transplant coordinates into
  `forvum-bom`/the module pom (versions managed by BOMs, never pinned) and adopt the matching template
  class. Quarkus-free modules (`forvum-core`, `forvum-sdk`) do not use the skill.
- **`quarkus-langchain4j-scaffolding` skill** — procedural scaffolding for AI services, agents, RAG
  pipelines, embedding stores.
- **`context7` MCP** for non-Quarkus library docs (LangChain4j, LangGraph4j) before model memory or
  web search. **M18 nuance:** LangGraph4j is not a Quarkus extension → use `context7`, not
  `quarkus/skills`; orchestrate with the LangGraph4j `StateGraph`, NOT the declarative
  `@SequenceAgent`/`@SupervisorAgent` annotations.

---

## 8. Design-round workflow & where the docs live

**Design contributions come first.** Before any change to a contract, an SPI, a build tier, or
anything in `docs/ULTRAPLAN.md`, open a **design round** under `docs/design-rounds/` first — even a
short one. Purely additive leaf changes (a new test, a typo, a small bug fix in merged code) skip the
round and PR directly.

- A round file opens with `**Status:**` (open/closed), `**Depends on:**` / `**Blocks:**`
  cross-references (commit SHA + date), `**Target sections:**` (which `ULTRAPLAN.md` §§ it amends +
  insertion point), then an `## Inventory` (line-referenced existing signals) before any proposed
  shape. The round amends `ULTRAPLAN.md` in numbered/dated commits. Examples on disk: `group-4b.md`
  (CostBudget, closed), `group-6a-tool-filters.md` (threat model + tool-execution filters, open).
- Docs map: `docs/ULTRAPLAN.md` (source of truth) · `docs/CONTEXT-ENGINEERING.md` +
  `docs/CONTEXT-ENGINEERING-MAPPING.md` · `docs/design-rounds/` · `docs/ISSUES.md` (per-step issue
  master index) · `docs/brand/`, `docs/images/` (logo + screenshots).
- **Working pattern:** propose → write the draft to `/tmp` → `cp` into the repo → commit (manual
  approval) → push (separate authorization). Issues and PRs are never auto-created or pushed.

---

## 9. Branch model

| Branch | Purpose |
|---|---|
| `main` | **default**; ships the multi-module reactor + architectural design docs/rounds. PRs target `main` unless demo-specific. |
| `demo/conference-mvp` | conference-demo vertical slice (one agent vs local Ollama via CLI). Demo-specific PRs target this branch. |
| `gh-pages` | published site (`forvum.ai` / GitHub Pages; brand assets under `docs/brand/`). |
| `design-round-tier1` | stale Tier-1 design-round working branch — flagged for deletion. |

The default branch is `main` (not `master`); use `main` in commit/PR guidance.

---

## 10. Conventions

- **English-only artifacts — non-negotiable.** Every artifact in the repo is in English: code,
  identifiers, JavaDoc, comments, commit messages, PR descriptions, docs, config keys, log messages,
  error strings, file/directory names. User-facing localization strings may be localized; source
  strings default to English. (Conversational PT with the maintainer is fine.) Use American spelling
  (`color`, `behavior`, `analyze`).
- **Commit convention: single-author Elder Moraes, NO `Co-Authored-By` trailer.** This is the inverse
  of the Claude-Code default — do not append a `Co-Authored-By: Claude` line. Conventional Commits,
  imperative mood. Examples: `chore: bootstrap multi-module reactor`,
  `feat(core): add domain records and sealed event hierarchy`,
  `feat(engine): add @AgentScoped CDI context backed by ScopedValue`,
  `docs(design): open Group 6a round`.
- **Surgical edits.** Touch only what the task demands; do not "improve" untouched prose or code;
  match the existing terse, declarative ULTRAPLAN register. Remove only orphans your own change
  created.
- **No commit/push/issue without explicit authorization** (§8 working pattern).

---

## 11. Testing discipline (§10 of `docs/ULTRAPLAN.md`)

- **TDD as process commitment** — each milestone's `Verify` script is the test that lands *before*
  implementation passes (Red → Green → Refactor, enforced in PR review).
- **Test pyramid:** unit `*Test` → integration `*IT` (`@QuarkusTest`, real SQLite via `@TempDir`) →
  E2E under `forvum-app/.../e2e/` (ten scripts).
- **Coverage gates:** JaCoCo 80% line (parent) + 75% branch (§10). Pitest mutation testing starts in
  `forvum-core` (50% killed greenfield → 70% Phase 2); thresholds are signals until a baseline
  exists, coverage gates are gates.
- **Property-based tests (jqwik) MANDATORY for parsers/records:** `ModelRef.parse` roundtrip,
  `AgentEvent` Jackson roundtrip, `CostBudget` invariants, `PermissionScope.fromName` failure modes.
- **Native-mode parity — MANDATORY** (§5). Parser/record (M2), provider HTTP (M9–M12), TUI (M15), web
  (M16), Telegram (M17), and the M20 cold-start gate run native.
- **Per-turn performance gates** (excluding inference, via `FakeProvider`): TUI ≤200 ms, Web ≤300 ms,
  Telegram ≤500 ms — baselined at M5/M6.
- **Flaky-test quarantine:** `*-LiveTest` `@Tag("live")`, default-off, nightly with retry budget 1.
- **Security-test layer** under `forvum-app/.../security/`: prompt-injection → no tool escalation;
  path traversal → denied; spawn-boundary identity override → rejected; `PermissionScope` mismatch →
  denied + audited.
- **Concurrency discipline (§3.8):** virtual threads per request; `-Djdk.tracePinnedThreads=full` in
  dev/test + CI grep for `Thread pinned`; `synchronized` forbidden in `forvum-engine` /
  `forvum-channel-*` hot paths (CI grep) — use `ReentrantLock` / `java.util.concurrent` / atomics.

---

## 12. What NOT to do

- Do **not** add a `Co-Authored-By` trailer to commits (single-author Elder Moraes only).
- Do **not** commit, push, or create live GitHub issues/PRs without explicit authorization.
- Do **not** write any repository artifact in a language other than English.
- Do **not** make `forvum-engine` compile-depend on a concrete channel/provider/tool module, and do
  **not** hardcode an extension ID in core — core stays extension-agnostic.
- Do **not** import core internals from a plugin — plugins compile only against `forvum-sdk`.
- Do **not** introduce runtime reflection, dynamic class loading (outside the JVM-only drop-in path),
  `sun.misc.Unsafe`, CGLib, or runtime Javassist — they break the native binary and are CI-banned.
- Do **not** ship a DTO record without `@RegisterForReflection` (the enforcer fails the build).
- Do **not** use `--enable-preview` on the native path or adopt `StructuredTaskScope` in v0.1.
- Do **not** create/run a Quarkus project or add an extension by hand, or answer a Quarkus question
  from model memory — go through the Quarkus Agent Dev MCP (and `context7` for library docs).
- Do **not** run raw `mvn test` — run tests through the Dev MCP (§4/§7).
- Do **not** use `synchronized` in engine/channel hot paths, or introduce thread-pinning without an
  allowlist entry citing the upstream issue.
- Do **not** change a contract / SPI / build tier / `ULTRAPLAN.md` without first opening a design
  round under `docs/design-rounds/`.
- Do **not** "improve" untouched prose/code — surgical edits only.
- Do **not** treat native as optional or secondary — it is the primary, mandatory target.
- Multi-agent git safety: do not `git stash`, switch branches, or touch `git worktree` checkouts
  unless explicitly asked; scope commits to your own changes.

---

## 13. Behavioral guidelines

- **Think before coding** — state assumptions; surface tradeoffs; if a simpler approach exists, say
  so; if something is unclear, stop and ask.
- **Simplicity first** — minimum code that solves the problem; nothing speculative.
- **Surgical changes** — touch only what the task requires; match existing style; clean up only your
  own orphans.
- **Goal-driven execution** — turn the task into a verifiable goal (write/identify the failing test,
  then make it pass) and loop until it's green.

For anything not covered here, defer to the workspace-level `CLAUDE.md` and to `docs/ULTRAPLAN.md`.
