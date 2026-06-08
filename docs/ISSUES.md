# Forvum — Issue Master Index

This file organizes the Forvum roadmap (`docs/ULTRAPLAN.md`) into discrete, trackable issues — one
per plan step — grouped by epic/milestone. Each entry below has a full issue body (Context, Scope,
Files, Acceptance Criteria, Dependencies, Labels, Milestone). The companion script
`scripts/create-issues.sh` creates these issues on GitHub via `gh` (run manually, after review).

**Repository:** `https://github.com/eldermoraes/forvum`
**Source of truth:** `docs/ULTRAPLAN.md` (§§ referenced throughout).
**Commit convention (every issue):** Conventional Commits, imperative mood. A `Co-Authored-By`
trailer for AI-assisted commits is welcome (convention updated 2026-06-04; supersedes the prior
single-author / no-trailer rule).

## Two acceptance rules that thread into EVERY code milestone

- **[NATIVE]** GraalVM native is the **primary, mandatory** build target — not co-equal with fast-jar.
  Every milestone native-COMPILES and runs its native smoke path in CI on `linux-amd64` and
  `macos-arm64`. The native build is `--enable-preview`-free by construction. The only sanctioned
  carve-out is a *behavioral* native-assertion skip (never the native compile) with a written
  justification in the issue/Verify block; today the sole defensible case is **M4** `WatchService`
  OS-polling semantics. A per-provider native failure (Risk #5) is marked JVM-only in release notes
  ONLY with an upstream issue filed; for Vertex/Gemini the preferred remedy is switching to the REST
  `quarkus-langchain4j-ai-gemini` extension, not a JVM-only carve-out.
- **[PLUGIN]** Scaffold/extend/test through the `quarkus-agentic@eldermoraes` plugin (Quarkus Agent
  Dev MCP + scaffolding skill) per `docs/ULTRAPLAN.md` §3.9: `quarkus/create` to harvest the current
  platform version + extension wiring (then transplant coordinates into `forvum-bom`/the module pom —
  versions managed by BOMs, never pinned); `quarkus/skills` before writing code for each extension;
  `quarkus/searchDocs` preferred over generic doc tools; tests via the Dev MCP (`devui-testing_runTests`/
  `runTest`) with the §7.1 Verify command remaining the contract; `context7` for non-Quarkus libraries
  (LangChain4j, LangGraph4j, JLine, Playwright). The plugin is used per-module/per-extension; the
  reactor topology is hand-authored (owned by M1), not generated.

## Version baseline (from `forvum-bom`, §2.1)

Quarkus 3.33.x LTS (3.33.1) · `quarkus-langchain4j-bom` 1.11.0.CR1 (PRE-RELEASE; brings LangChain4j core 1.15.1; stable fallback 1.10.0 → 1.14.1) ·
`langgraph4j-core` 1.8.17 · `tamboui-bom` 0.3.0 (TUI) · Java 25 · GraalVM CE 25 / Mandrel 25.0.x-Final · Xerial SQLite JDBC
≥ 3.40.1.0 (use ~3.53.x). All version-bearing issues pin through `forvum-bom`, the single bump point.

---

## Dependency-ordered execution sequence

Issues should be opened/worked in this topological order. `→` = "unblocks"; items on one line are
independent and may proceed in parallel once their prerequisites are met.

```
Phase 1 (MVP v0.1) — the critical path
  X1  (native-first discipline gates)  ─┐  open alongside M1
  X3  (test pyramid + native parity)   ─┤
  X2  (concurrency discipline gates)   ─┘
  M1  reactor bootstrap
   ├─→ M2  core domain types
   │     └─→ M3  SDK contract ──────────────┐
   ├─→ M4  config loader (hot reload)       │
   ├─→ M5  SQLite + Flyway V1               │
   │     └─→ M8  FallbackChatModel ─────────┤
   └─(M2)─→ M6  @AgentScoped CDI context (CRITICAL native; needs M5 turn_id)
            └─→ M7  AgentRegistry (needs M4, M6, M3)
   M3 ─→ { M9 Ollama → M10 Anthropic, M11 OpenAI, M12 Google }   (need M8, M5)
   M7 + M3 ─→ M13 ToolRegistry/PermissionScope ─→ M14 filesystem tools
   M7 + M8 ─→ M15 TUI ;  M7 ─→ M16 Web ;  M7 + M8 ─→ M17 Telegram
   M5 + M7 + M8 + M13 ─→ M18 SupervisorGraph (CRITICAL native; VT fan-out)
   M4 + M5 + M7 + M8 + M18 ─→ M19 cron scheduler
   ALL of M1–M19 ─→ M20 native image + CI matrix (CAPSTONE; X3 parity edit lands here)
   X7  (shell/skills/OTel/mcp-bridge gap)  resolved as M13/M18 acceptance or micro-milestones
   X4 X5 X6 X8  land milestone-by-milestone, gated by M20

Phase 2 (v0.5 parity) — gated on a stable MVP (M20)
  P2-1 … P2-15  (R8 items) + P2-CH, P2-COPILOT, P2-QA, P2-PAIR-SCOPE, P2-COMPACT,
                 P2-TASKLEDGER, P2-CRON-DELIVERY, P2-OUTPUTGUARD  (§7.2 items 16–23)

Phase 3 (v1.0+) — gated on v0.5
  P3-1 … P3-10  (P3-1 single-binary install is the native-mandate product expression)

Design & contracts (parallel track; must land before the contracts they define are coded)
  DR-6a ─→ { DR-4c, DR-5, DR-6b, DR-6c, TEST-SEC } ─→ DR-8
  BR-CLEANUP (independent)
```

---

## Epics, milestones & labels

| Group | Issues | Count |
|---|---|---|
| EPIC-1 — Phase 1 MVP (v0.1) | M1–M20 | 20 |
| EPIC-2 — Phase 2 v0.5 parity | P2-1 … P2-15 + 8 parity additions | 23 |
| EPIC-3 — Phase 3 v1.0+ | P3-1 … P3-10 | 10 |
| EPIC-DR — Design & contracts | DR-6a, DR-6b, DR-6c, DR-4c, DR-5, DR-8, TEST-SEC, BR-CLEANUP | 8 |
| EPIC-X — Cross-cutting CI/test infra | X1 … X8 | 8 |
| Epic parents | EPIC-1, EPIC-2, EPIC-3, EPIC-DR, EPIC-X | 5 |
| **Total** | | **74** |

Labels used: `epic`, `phase-1`, `phase-2`, `phase-3`, `design`, `ci-infra`, `native`,
`plugin-tooling`, `provider`, `channel`, `tool`, `engine`, `core`, `sdk`, `persistence`, `security`,
`observability`, `context-engineering`, `branch-hygiene`, `blocked`.

Milestones used: `v0.1 MVP`, `v0.5 Parity`, `v1.0+`, `Design & Contracts`, `CI/Test Infra`.

---

## GitHub issue-number map (one issue per step — never open ad-hoc)

Every roadmap step already exists as a GitHub issue (created by `scripts/create-issues.sh`). At each
step, **close its issue — do not open a new one.** Phase-1 rule: `Mn → #(n+5)`; the milestone PR
carries `Closes #(n+5)`. (Numbers reflect the current tracker; re-running the script renumbers, so
re-sync via `gh issue list --state all`. `✓` = closed.)

| Milestone | Issue | Milestone | Issue | Milestone | Issue | Milestone | Issue |
|---|---|---|---|---|---|---|---|
| M1 | #6 ✓ | M6 | #11 | M11 | #16 | M16 | #21 |
| M2 | #7 ✓ | M7 | #12 | M12 | #17 | M17 | #22 |
| M3 | #8 | M8 | #13 | M13 | #18 | M18 | #23 |
| M4 | #9 | M9 | #14 | M14 | #19 | M19 | #24 |
| M5 | #10 | M10 | #15 | M15 | #20 | M20 | #25 |

**Non-milestone items** (closed on their own track, **not** by an `Mn`):

- **Epic parents:** EPIC-1 #1 · EPIC-2 #2 · EPIC-3 #3 · EPIC-DR #4 · EPIC-X #5.
- **Cross-cutting (EPIC-X):** X1 #67 · X2 #68 · X3 #69 · X4 #70 · X5 #71 · X6 #72 · X7 #73 · X8 #74.
- **Design & contracts (EPIC-DR):** DR-6a #59 · DR-6b #60 · DR-6c #61 · DR-4c #62 · DR-5 #63 ·
  DR-8 #64 · TEST-SEC #65 · BR-CLEANUP #66.
- **Phase 2 (EPIC-2):** P2-1…P2-15 #26–#40 · P2-CH #41 · P2-COPILOT #42 · P2-QA #43 ·
  P2-PAIR-SCOPE #44 · P2-COMPACT #45 · P2-TASKLEDGER #46 · P2-CRON-DELIVERY #47 · P2-OUTPUTGUARD #48.
- **Phase 3 (EPIC-3):** P3-1…P3-10 #49–#58.
- **Loose:** quarkus-langchain4j GA bump #75 · TamboUI backend spike #76.

---

# EPIC PARENTS

## EPIC-1 — Phase 1 MVP (v0.1)
**Labels:** `epic`, `phase-1` · **Milestone:** `v0.1 MVP`

**Context.** Phase 1 (`docs/ULTRAPLAN.md` §7.1) delivers the minimum viable Forvum: a native-buildable
single binary that runs a real agent turn across TUI/Web/Telegram channels with Ollama/Anthropic/
OpenAI/Google providers, SQLite persistence, `@AgentScoped` isolation, a LangGraph4j supervisor graph,
and crons.

**Scope.** Tracking epic for milestones M1–M20. Closes when the M20 capstone is green (JVM + native on
both CI platforms, 200 ms cold-start gate passing).

**Acceptance.** All child issues M1–M20 closed; the E2E suite (X6) passes on fast-jar AND native.

**Dependencies.** None (root epic). Children carry per-milestone deps.

---

## EPIC-2 — Phase 2 v0.5 parity with OpenClaw
**Labels:** `epic`, `phase-2` · **Milestone:** `v0.5 Parity`

**Context.** Phase 2 (`docs/ULTRAPLAN.md` §7.2) reaches feature parity with OpenClaw, reconciled
against OpenClaw **v2026.4.19-beta.2** (REQ #5). §7.2 is the authoritative parity list (items 1–23).

**Scope.** Tracking epic for P2-1…P2-15 plus the eight parity additions (§7.2 items 16–23).

**Acceptance.** All child issues closed; each verified "against OpenClaw v2026.4.19-beta.2".

**Dependencies.** EPIC-1 (a stable MVP).

---

## EPIC-3 — Phase 3 v1.0+ differentiators
**Labels:** `epic`, `phase-3` · **Milestone:** `v1.0+`

**Context.** Phase 3 (`docs/ULTRAPLAN.md` §7.3) ships the bets the Java/Quarkus/native foundation
enables or cheapens versus OpenClaw's TS/Node stack — headlined by the single-binary install (P3-1),
the product expression of the native mandate.

**Scope.** Tracking epic for P3-1…P3-10.

**Acceptance.** All child issues closed.

**Dependencies.** EPIC-2 (v0.5).

---

## EPIC-DR — Settle remaining design & contracts
**Labels:** `epic`, `design` · **Milestone:** `Design & Contracts`

**Context.** Tier-1 contracts (Groups 1–4b, §3.8, §10) are SETTLED. The open surface (R9) is:
Group 6a (threat model + tool filters; design sign-off pending), Group 6b/6c (planned),
Group 4c (`FallbackChain`, §4.3.5.3 `*TBD*`), Group 5 (`MemoryPolicy`, §4.3.6 `*TBD*`),
Group 8 (Persona/AgentSpec). Settling them resolves the two predating `*TBD*` markers and creates §9.

**Scope.** Parent tracking the dependency chain DR-6a → {DR-4c, DR-5, DR-6b, DR-6c, TEST-SEC} → DR-8,
plus BR-CLEANUP.

**Acceptance.** No `*TBD*` markers remain in `docs/ULTRAPLAN.md`; §9 Security exists; the §10
"see §9 once it lands" forward-reference resolves.

**Dependencies.** None (root epic).

---

## EPIC-X — Cross-cutting CI / test / native-discipline infrastructure
**Labels:** `epic`, `ci-infra` · **Milestone:** `CI/Test Infra`

**Context.** §6.3/§3.8/§10/E2E/Critical-Files imply enforcement and verification layers that are not a
single milestone (R8 X1–X8). These are the mandate's enforcement backbone.

**Scope.** Parent tracking X1–X8.

**Acceptance.** All gates active in CI; the §10 native-parity amendment ("selective" → "mandatory")
operationalized at M20.

**Dependencies.** None (root epic); individual gates fold into M1/M3/M20.

---

# EPIC-1 — PHASE 1 MILESTONES (M1–M20)

## M1 — Bootstrap the Maven multi-module reactor
**Labels:** `phase-1`, `engine`, `native`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** Forvum is a Maven multi-module reactor (`docs/ULTRAPLAN.md` §2): parent + `forvum-bom` +
the four layers (`forvum-core`, `forvum-sdk`, `forvum-engine`, `forvum-app`). The reactor topology is
hand-authored and owned by this milestone — the scaffolding skill produces per-module starting points,
not the reactor skeleton (§3.9).

**Scope / Deliverables.** Create parent + bom + core + sdk + engine + app POMs; Maven Wrapper; compiler
config `maven.compiler.release=25`; `.gitignore`. Lock Java 25 and the Quarkus 3.33.x LTS platform BOM.
`forvum-bom` imports `quarkus-langchain4j-bom:1.11.0.CR1` (PRE-RELEASE; stable fallback 1.10.0) and pins `langgraph4j-core:1.8.17` and
`org.xerial:sqlite-jdbc` (≥ 3.40.1.0) as the single bump point (§2.1).

**Files.** `pom.xml` (parent), `forvum-bom/pom.xml`, `forvum-core/pom.xml`, `forvum-sdk/pom.xml`,
`forvum-engine/pom.xml`, `forvum-app/pom.xml`, `.gitignore`, `mvnw`, `mvnw.cmd`,
`.mvn/wrapper/maven-wrapper.properties`.

**Acceptance Criteria.**
- `./mvnw -N verify` green per module; `./mvnw -pl forvum-app -am package` produces
  `forvum-app/target/quarkus-app/quarkus-run.jar`.
- **[NATIVE]** the bootstrapped `-Pnative` profile native-compiles a trivial `forvum-app` to a runner
  binary in CI from day one (native gated from M1, §6 / §3.7); Mandrel 25.0.x-Final used as the
  `native-image` distribution.
- `forvum-bom` is the single version source: `quarkus-langchain4j-bom:1.11.0.CR1`, `langgraph4j-core:1.8.17`,
  `sqlite-jdbc` ≥ 3.40.1.0 present; no version pinned outside the BOM.
- **[PLUGIN]** platform version + extension wiring harvested via `quarkus/create` (throwaway app),
  coordinates transplanted into the hand-authored reactor; the reactor wiring the plugin cannot
  generate is hand-authored.

**Dependencies.** None. Unblocks: every other milestone.

**Suggested commit.** `chore: bootstrap multi-module reactor`

---

## M2 — Core domain types & sealed event hierarchy
**Labels:** `phase-1`, `core`, `native` · **Milestone:** `v0.1 MVP`

**Context.** `forvum-core` holds pure-Java domain types with zero Quarkus dependency (§2.1, §4.3).

**Scope / Deliverables.** Domain records + sealed `AgentEvent` hierarchy and SQL-mirror enums.

**Files.** `forvum-core/.../id/AgentId.java`, `Identity.java`, `ChannelMessage.java`, `ToolSpec.java`,
`ModelRef.java`, `FallbackChain.java`, `CostBudget.java`, `MemoryPolicy.java`, the sealed
`AgentEvent permits TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent` (§4.3.2),
SQL-mirror enums (§4.3.3), `PermissionScope` (§4.3.4). `FallbackChain`/`MemoryPolicy` are placeholders
until DR-4c/DR-5 land (they carry the predating `*TBD*` markers — do not invent their shape here).

**Acceptance Criteria.**
- Unit tests round-trip each record through Jackson; pattern-match over `AgentEvent` compiles with no
  `default` branch.
- `mvn dependency:analyze` confirms zero Quarkus dependency in `forvum-core`.
- **[NATIVE]** §10 marks M2 (parser/record milestone) a must-run-native milestone; native parity is
  non-negotiable. The 6 `AgentEvent` permits must match the §4.3.2 list verbatim. Note: `forvum-core`
  produces no runnable artifact and hosts no `@QuarkusTest`/`@QuarkusIntegrationTest`, so there is no
  per-module native image at M2. Its types are records/sealed/enum (reflection-free) — native-compatible
  by construction — and their native execution is exercised by the M20 app native smoke (and by M5/M6 once
  a Quarkus module first serializes them). M2's gate is JVM unit + JUnit 5 property-style tests.
- JUnit 5 property-style tests for `ModelRef.parse`, `AgentEvent` Jackson roundtrip, `CostBudget` invariants,
  `PermissionScope.fromName` (X3).
- **[PLUGIN]** `context7` for Jackson/record-serialization API; no Quarkus extension here (JVM-domain
  module; `quarkus/skills` N/A).

**Dependencies.** M1. Unblocks: M3.

**Suggested commit.** `feat(core): add domain records and sealed event hierarchy`

---

## M3 — SDK provider contract & plugin marker
**Labels:** `phase-1`, `sdk`, `native` · **Milestone:** `v0.1 MVP`

**Context.** `forvum-sdk` defines the sealed provider SPI that channels/providers/tools/memory hosts
implement (§2.1, §5). Tied to Risk #3 (sealed interfaces + ArC discovery).

**Scope / Deliverables.** Sealed `ChannelProvider`/`ModelProvider`/`ToolProvider`/`MemoryProvider`,
each permitting a `non-sealed abstract AbstractXProvider`; `@ForvumExtension` marker; the
`META-INF/forvum/plugin.json` schema docs; re-export of `@RegisterForReflection`.

**Files.** `forvum-sdk/.../ChannelProvider.java` (+ `ModelProvider`/`ToolProvider`/`MemoryProvider`),
paired `AbstractXProvider`, `@ForvumExtension`, plugin.json schema docs.

**Acceptance Criteria.**
- `SdkSurfaceTest` asserts (via reflection) that only `Abstract*` are direct permits and external
  classes cannot implement the sealed interface directly.
- `forvum-sdk` compile scope contains no `quarkus-core`.
- **[NATIVE]** M3 passes on native; if ArC warns on sealed interfaces, investigate before M7. Native
  pass is acceptance (Risk #3).
- **[PLUGIN]** `quarkus/skills` for the `@RegisterForReflection` patterns; `context7` for any
  reflection-config API.

**Dependencies.** M2. Unblocks: M7, M9–M17 (all plugins compile against the SDK).

**Suggested commit.** `feat(sdk): define sealed provider interfaces and plugin marker`

---

## M4 — File-based config loader with hot reload
**Labels:** `phase-1`, `engine`, `native` · **Milestone:** `v0.1 MVP`

**Context.** `~/.forvum/` is the configuration surface; "fixed code, configurable behavior" (§1.4)
requires hot reload (§7.1 M4). Tied to Risk #7 (`WatchService` platform variance).

**Scope / Deliverables.** `WatchService`-backed config loader firing a CDI `ConfigurationChangedEvent`;
`ForvumHome` resolution; per-subfolder readers; 250 ms debounce + coalesce.

**Files.** `forvum-engine/.../config/ConfigLoader.java`, `ConfigWatcher.java`,
`ConfigurationChangedEvent.java`, `ForvumHome.java`, per-subfolder readers.

**Acceptance Criteria.**
- Integration test with `@TempDir` writes a synthetic `~/.forvum/`, fires modifications, asserts
  observers receive correct `path`/`type`; platform-matrix test (macOS polling vs inotify vs
  ReadDirectoryChangesW).
- **[NATIVE] SANCTIONED CARVE-OUT.** M4 must still native-COMPILE; it MAY skip the *behavioral* native
  assertion because `WatchService` OS-polling semantics are JVM-host behavior (Risk #7). This is the
  ONLY sanctioned behavioral carve-out in Phase 1 and must be justified in writing in the milestone
  Verify block.
- **[PLUGIN]** `quarkus/skills` for `quarkus-jackson` + CDI events before code; tests via Dev MCP.

**Dependencies.** M1, M2. Unblocks: M7, M19.

**Suggested commit.** `feat(engine): add file-based config loader with WatchService`

---

## M5 — SQLite persistence with Flyway V1 baseline
**Labels:** `phase-1`, `engine`, `persistence`, `native` · **Milestone:** `v0.1 MVP`

**Context.** SQLite is the single-file local store (§4.2): WAL, `foreign_keys=ON`, 7-table V1 baseline,
via Hibernate ORM + Panache + Flyway. Tied to Risk #11 (JDBC/virtual-thread pinning).

**Scope / Deliverables.** Flyway V1 baseline (7 tables); Panache entities; `application.properties`
JDBC URL at `$FORVUM_HOME/state/forvum.sqlite`. Finalize the JDBC/virtual-thread pinning posture
(Risk #11) and back-fill the chosen mitigation into §3.8. Baseline the per-turn performance gates
(§10/X4) at M5. Decide the V2 `add_turn_id` migration boundary (recommend it lands at the M5/M6
boundary; §4.3.1 ties V2 to M6 consumption).

**Files.** `forvum-engine/.../db/migration/V1__baseline.sql`, `application.properties`,
`forvum-engine/.../persistence/` Panache entities per table.

**Acceptance Criteria.**
- `SchemaSmokeIT` migrates a fresh file, inserts one row per table, dumps `.schema` against a golden
  file. A 100-turn synthetic pin-event run picks the no-unbounded-pins option (Risk #11 decision
  trigger), and the chosen mitigation is back-filled into §3.8.
- **[NATIVE]** SQLite JDBC native loading + WAL work in native; `org.sqlite.lib.exportPath` set in the
  native profile (sqlite-jdbc ≥ 3.40.1.0 ships its own JNI config); Flyway migrations registered as
  native resources (SQLite-only SQL); forward-only Flyway CI check applies. The pinning mitigation
  holds in native.
- **[PLUGIN]** `quarkus/skills` for `hibernate-orm-panache` + `flyway` before code; `quarkus/searchDocs`
  for SQLite dialect config; tests via Dev MCP. NOTE: adopting qlc4j 1.11.0.CR1 on Quarkus 3.33.1 (a matched
  pair) resolves the demo-branch Quarkiverse-vs-Quarkus build-step incompatibility recorded in
  deferral D8 (qlc4j 0.26.1 vs Quarkus 3.31.4).

**Dependencies.** M1. Unblocks: M8, M13, M18, M19.

**Suggested commit.** `feat(engine): add SQLite persistence with Flyway V1 baseline`

---

## M6 — @AgentScoped custom CDI context (CRITICAL native)
**Labels:** `phase-1`, `engine`, `native`, `blocked` · **Milestone:** `v0.1 MVP`

**Context.** `@AgentScoped` isolation (§5.1) is the core multi-agent safety property. It is backed by
`ScopedValue` (JEP 506) and an ArC `InjectableContext`. **This milestone resolves the headline native
risk (Risk #1).** RESOLVED FACTS: `ScopedValue` is **FINAL in JDK 25** (not preview) — no
`--enable-preview`, no preview-gated native flag. The only native risk is ArC `InjectableContext`
build-time registration. The API form is the final builder `ScopedValue.where(KEY, v).call(body)`
(`.run(...)` for void); the `callWhere`/`runWhere` static helpers were removed before finalization.

**Scope / Deliverables.** Custom `@AgentScoped` CDI scope via the ArC `InjectableContext` SPI, backed
by `ScopedValue<AgentId>` (plus `ScopedValue<UUID> CURRENT_TURN`, `ScopedValue<Identity>` per
§4.3.1/§4.1), with a `BuildStep` registration. Consumes the §4.3.1 turn-id contract (V2 columns).

**Files.** `forvum-core/.../AgentScoped.java`, `forvum-engine/.../context/AgentContext.java`
(`InjectableContext` impl), `AgentContextBuildItem.java`, `AgentContextProcessor.java` (BuildStep),
`CurrentAgent.java`.

**Acceptance Criteria.**
- A dual-thread integration test binds two `AgentId`s on two virtual threads, resolves the same
  `@AgentScoped` bean class on each, and asserts distinct `System.identityHashCode`.
- **[NATIVE] CRITICAL.** Risk #1 acceptance = CI green on BOTH JVM and native; two-thread isolation
  test passes native; the native build is `--enable-preview`-free (ScopedValue is final). The spike is
  scoped to the ArC `InjectableContext` build-step on native ONLY; there is no ThreadLocal/preview-flag
  fallback.
- Performance gates (§10/X4) baselined here.
- **[PLUGIN]** `quarkus/skills` for ArC/CDI-context patterns before code; `quarkus/searchDocs` for the
  `InjectableContext` SPI; tests via Dev MCP on native.

**Dependencies.** M1, M2 (ScopedValue carries `AgentId`), M5 (turn_id columns, §4.3.1). Unblocks: M7.

**Suggested commit.** `feat(engine): add @AgentScoped CDI context backed by ScopedValue`

---

## M7 — AgentRegistry with getOrCreate & spawn
**Labels:** `phase-1`, `engine`, `native` · **Milestone:** `v0.1 MVP`

**Context.** File-driven agent creation + sub-agent spawn (§5.2): the registry materializes agents from
`agents/<id>.md` + `<id>.json`, isolated per `@AgentScoped`.

**Scope / Deliverables.** `AgentRegistry` (`getOrCreate` + `spawn`); `Agent` `@AgentScoped` facade;
`AgentMemory`; `AgentToolBelt`; `AgentSpecReader` (parses `.md` + `.json`).

**Files.** `forvum-engine/.../agent/AgentRegistry.java`, `AgentSpec.java`, `Agent.java`,
`AgentMemory.java`, `AgentToolBelt.java`, `AgentSpecReader.java`.

**Acceptance Criteria.**
- Seed `agents/main.md` + `main.json`; `getOrCreate("main")` twice → same instance;
  `spawn("main", childSpec)` → distinct child `AgentId` with a narrower tool belt.
- **[NATIVE]** spawn isolation holds in native (depends on the M6 native verdict); PermissionScope/sealed
  discovery (Risk #3) clean before M7.
- **[PLUGIN]** `quarkus/skills` for CDI bean lifecycle; `context7` for any LangChain4j memory API.

**Dependencies.** M4, M6, M3 (tool-belt typing). Unblocks: M13, M15, M16, M17, M18.

**Suggested commit.** `feat(engine): add AgentRegistry with file-driven agent creation`

---

## M8 — FallbackChatModel & FailureClassifier
**Labels:** `phase-1`, `engine`, `provider`, `native` · **Milestone:** `v0.1 MVP`

**Context.** Resilient model invocation (§5.4): a decorator chain over LangChain4j models that
classifies failures and falls through to the next provider. Migrates `FallbackTriggered.reason`
String → `FailureClass` enum (§4.3.2 schedules this for M8).

**Scope / Deliverables.** `FallbackChatModel`/`FallbackStreamingChatModel` decorators over LangChain4j
`ChatModel`/`StreamingChatModel`; sealed `FailureClass` + `FailureClassifier`. The classifier maps the
`dev.langchain4j.exception` typed hierarchy via the core `ExceptionMapper`:
`429→RateLimitException`, `408→TimeoutException`, `5xx→InternalServerException` (all Retryable);
`401/403→AuthenticationException`, `404→ModelNotFoundException`, other `4xx→InvalidRequestException`
(all NonRetryable); `LangChain4jException` root → Unknown → operator alert. Classify against the typed
hierarchy, NOT string-matched HTTP codes; record the failing exception's FQCN in the EXISTING nullable
`provider_calls.error` column (§4.2) — no new column, no migration.

**Files.** `forvum-engine/.../model/FallbackChatModel.java`, `FallbackStreamingChatModel.java`,
`FailureClass.java` (sealed), `FailureClassifier.java`.

**Acceptance Criteria.**
- Unit test: mock `ChatModel` throws `RateLimitException` then returns; assert `provider_calls` gets
  two rows, second `is_fallback = 1`.
- **[NATIVE]** decorator + langchain4j-core native-clean.
- **[PLUGIN]** `context7` for the LangChain4j `ChatModel`/`StreamingChatModel`/`dev.langchain4j.exception`
  API (core 1.15.1 via qlc4j 1.11.0.CR1); `quarkus/searchDocs` for `quarkus-langchain4j` wiring.

**Dependencies.** M5 (writes `provider_calls`). Unblocks: M9–M12, M15, M17, M18.

**Suggested commit.** `feat(engine): add FallbackChatModel decorator with failure classification`

---

## M9 — Ollama provider (first provider, local, no API key)
**Labels:** `phase-1`, `provider`, `native`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** First `ModelProvider` (§7.1 M9), local and key-free; establishes the canonical
provider-module creation flow via the Quarkus Agent MCP.

**Scope / Deliverables.** `OllamaModelProvider` wrapping `quarkus-langchain4j-ollama`; manifest.

**Files.** `forvum-provider-ollama/.../OllamaModelProvider.java`, `META-INF/forvum/plugin.json`.

**Acceptance Criteria.**
- With local `ollama serve` running `qwen3:1.7b`, a scripted turn produces a non-empty assistant
  message and ≥ 1 `provider_calls` row with `provider='ollama'`. Live turn tagged `@Tag("live")`.
- **[NATIVE]** §10 lists M9–M12 (provider HTTP stack) as must-run-native; the per-provider native smoke
  is MANDATORY (Risk #5; Ollama well-exercised).
- **[PLUGIN]** add the `quarkus-langchain4j-ollama` extension via `quarkus/searchTools` + `quarkus/callTool`;
  `quarkus/skills` for the ollama extension before code; this is the canonical module-creation flow
  inherited by M10–M12.

**Dependencies.** M3, M8, M5. Unblocks: M10 (fallback target), M15.

**Suggested commit.** `feat(provider-ollama): add local Ollama provider`

---

## M10 — Anthropic provider
**Labels:** `phase-1`, `provider`, `native`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** Second provider (§7.1 M10), the first remote/keyed one, used as a fallback target from
Ollama.

**Scope / Deliverables.** `AnthropicModelProvider` wrapping `quarkus-langchain4j-anthropic`; manifest.

**Files.** `forvum-provider-anthropic/` module, `AnthropicModelProvider.java`, manifest.

**Acceptance Criteria.**
- With `ANTHROPIC_API_KEY`, a scripted live turn produces a reply; a second turn with an invalid key
  falls through `FallbackChatModel` to Ollama. Live test `@Tag("live")`.
- The example model id in the Verify text must be updated to a **current** model id at implementation
  time. The §7.1 baseline reads `claude-opus-4-7` (stale); OpenClaw's current default is
  `claude-opus-4.6` — use that or a current Anthropic id.
- **[NATIVE]** must-run-native (Risk #5).
- **[PLUGIN]** add the extension via `quarkus/callTool`; `quarkus/skills` for the anthropic extension;
  `context7` for the langchain4j-anthropic API.

**Dependencies.** M3, M8, M9 (fallback target). Unblocks: nothing downstream blocks on it.

**Suggested commit.** `feat(provider-anthropic): add Anthropic provider`

---

## M11 — OpenAI provider
**Labels:** `phase-1`, `provider`, `native`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** Third provider (§7.1 M11), OpenAI-compatible.

**Scope / Deliverables.** `OpenAiModelProvider` wrapping `quarkus-langchain4j-openai`; manifest.

**Files.** `forvum-provider-openai/` module, `OpenAiModelProvider.java`, manifest.

**Acceptance Criteria.**
- With `OPENAI_API_KEY`, a scripted live turn produces a reply. Live test `@Tag("live")`.
- The example model id (`gpt-4.1-mini` in the §7.1 baseline) must be updated to a current OpenAI id at
  implementation time.
- **[NATIVE]** must-run-native (Risk #5; OpenAI well-exercised).
- **[PLUGIN]** add the extension via the MCP; `quarkus/skills` for the openai extension; `context7` for
  the API.

**Dependencies.** M3, M8.

**Suggested commit.** `feat(provider-openai): add OpenAI provider`

---

## M12 — Google (Gemini) provider
**Labels:** `phase-1`, `provider`, `native`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** Fourth provider (§7.1 M12). Highest native risk among providers (Risk #5: Vertex AI Gemini
less exercised in native).

**Scope / Deliverables.** `GoogleModelProvider` wrapping `quarkus-langchain4j-vertex-ai-gemini`;
manifest. If the Vertex gRPC stack blocks native, switch to the REST `quarkus-langchain4j-ai-gemini`
(Google GenAI) extension — the native-first alternative — BEFORE any JVM-only carve-out.

**Files.** `forvum-provider-google/` module, `GoogleModelProvider.java`, manifest.

**Acceptance Criteria.**
- With Vertex credentials, a scripted live turn produces a reply. Live test `@Tag("live")`.
- The example model id (`gemini-1.5-flash` in the §7.1 baseline) must be updated to a current Gemini id
  at implementation time.
- **[NATIVE]** must-run-native; per-provider native smoke is MANDATORY. If native is red, the REMEDY is
  switching to the REST `quarkus-langchain4j-ai-gemini` extension. A JVM-only carve-out is allowed ONLY
  after the REST remedy is exhausted, with an upstream issue filed and a release-note mark.
- **[PLUGIN]** add the extension via the MCP; `quarkus/skills` for the vertex-ai-gemini extension;
  `context7` for the API.

**Dependencies.** M3, M8.

**Suggested commit.** `feat(provider-google): add Vertex AI Gemini provider`

---

## M13 — ToolRegistry, filtering & PermissionScope
**Labels:** `phase-1`, `engine`, `tool`, `security`, `native` · **Milestone:** `v0.1 MVP`

**Context.** Tool capability gating (§5.3, §4.3.4): the Select pillar applied to capability. Tied to the
security-test layer (§10). The X7 gap (the shell tool, the `SkillInvokerTool` skills surface, the MCP
bridge baseline, the OTel baseline) is **out of M13 scope** — decided 2026-06-05, deferred to its own
issue (#73), so M13 stays minimal.

**Scope / Deliverables.** `ToolRegistry`; `ToolExecutor` (enforces capability via the agent's filtered
belt); `PermissionDeniedException`; `ToolFilter` (glob matching); the `forvum-sdk` `ToolProvider.tools()`
SPI prelude; the `ToolInvocation` recorder triad (write seam over the existing V1 `tool_invocations`).
`PermissionScope` is **consumed** from `forvum-core` (already exists, M2) — not recreated; M13 adds no
migration. Tools are not wired into `Agent.respond()` here (that is M18).

**Files.** `forvum-engine/.../tools/{ToolRegistry,ToolExecutor,ToolFilter,PermissionDeniedException}.java`,
`.../model/{ToolInvocation,ToolInvocationRecorder}.java`, `.../persistence/PanacheToolInvocationRecorder.java`,
`.../agent/AgentToolBelt.java` (filtered `tools()`); `forvum-sdk/.../ToolProvider.java` (the `tools()` prelude).

**Acceptance Criteria.**
- Register `a.read`/`a.write`; seed an agent with `allowedTools:["a.read"]`; assert a call to `a.write`
  is refused with `PermissionDeniedException` and logged `tool_invocations.status='denied'`.
- **[NATIVE]** glob/permission enforcement native-clean.
- Security negative test (X5/TEST-SEC): `PermissionScope` mismatch → denied + audited.
- **[PLUGIN]** `quarkus/skills` for CDI; `context7` for the langchain4j `ToolSpecification`/`ToolExecutor`
  surface.

**Dependencies.** M3, M7, M5 (`tool_invocations`). Unblocks: M14, M18.

**Suggested commit.** `feat(engine): add ToolRegistry with glob-based filtering and permission scopes`

---

## M14 — Filesystem tools
**Labels:** `phase-1`, `tool`, `security`, `native`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** First first-party tool module (§7.1 M14): read/write/list within a configured workspace
root.

**Scope / Deliverables.** `forvum-tools-filesystem`: `FsReadTool` (`FS_READ`), `FsWriteTool`
(`FS_WRITE`), `FsListTool`; `FilesystemToolProvider`; manifest.

**Files.** `forvum-tools-filesystem/` module, `FilesystemToolProvider.java`, `FsReadTool.java`,
`FsWriteTool.java`, `FsListTool.java`, `WorkspaceRoot.java`, `WorkspaceEscapeException.java`, manifest;
the three append-only pom wirings (root `<modules>`, `forvum-bom`, `forvum-app`).

**Acceptance Criteria.**
- Integration test against `@TempDir`: read/write/list round-trip; a write outside the configured
  workspace root is denied.
- **[NATIVE]** native file I/O native-clean; native parity applies.
- Security negative test (X5/TEST-SEC): path traversal in fs-tool args → denied. M14 ships a minimal
  self-contained `WorkspaceRoot` (`normalize` + element-wise `startsWith`, so a `<root>-evil` sibling is
  rejected). The check is LEXICAL only — symlink-resolving confinement (`toRealPath` / `O_NOFOLLOW`) and
  TOCTOU hardening are part of the deferred DR-6a output-filter / threat-model contract (decided
  2026-06-05; out of scope under the single-user, local-first trust boundary — the M9–M12 precedent).
- **[PLUGIN]** `quarkus/skills` for the SDK/tool patterns; module scaffolded via the MCP.

**Dependencies.** M3, M13.

**Suggested commit.** `feat(tools-fs): add filesystem read/write/list tools with FS permission scope`

---

## M15 — TUI channel (TamboUI on the JLine 3 backend)
**Labels:** `phase-1`, `channel`, `native`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** The terminal REPL (§3.5, §7.1 M15): the channel where native cold-start matters most. Built with the **TamboUI Toolkit** (declarative widgets + TCSS) on the `tamboui-jline3-backend`. Tied to Risk #6 (TamboUI/JLine on Windows under GraalVM) and Risk #14 (TamboUI pre-1.0 maturity).

**Scope / Deliverables.** `forvum-channel-tui`: a TamboUI Toolkit view with streaming token rendering, a TCSS theme, `--no-ansi` fallback (first-class from M15), and the TamboUI + JLine-backend GraalVM reachability metadata; manifest. Evaluate the `tamboui-panama-backend` (Java FFM, no external dep, best startup) as the native-first alternative.

**Files.** `forvum-channel-tui/.../TuiChannel.java`, `TuiView.java` (TamboUI component tree), `src/main/resources/tui.tcss`, the `--no-ansi` path, `META-INF/native-image/.../reachability-metadata.json`, manifest.

**Acceptance Criteria.**
- Integration test pipes scripted stdin through the binary and asserts the rendered TamboUI output contains the assistant reply; `-Dforvum.no-ansi=true < input.txt` is identical.
- **[NATIVE]** §10 lists M15 must-run-native; native cold-start is the headline metric (TamboUI is GraalVM-native-first, sub-100 ms). Windows CI runs the TUI smoke in ANSI and no-ANSI; no-ANSI default on Windows if red (Risk #6).
- **[PLUGIN]** TamboUI is NOT a Quarkus extension → use `context7` for the TamboUI Toolkit / TCSS / backend API and for JLine native-image hints; `quarkus/skills` for any Quarkus extension used.

**Dependencies.** M3, M7, M8. Unblocks: nothing downstream blocks on it.

**Suggested commit.** `feat(channel-tui): add TamboUI-based TUI channel with streaming rendering`

---

## M16 — Web channel (WebSockets Next)
**Labels:** `phase-1`, `channel`, `native`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** Browser chat channel (§7.1 M16) over Quarkus WebSockets Next with per-socket sessions. The
security-test directory lands alongside the web channel (§10).

**Scope / Deliverables.** `forvum-channel-web`: WebSockets Next server + minimal static HTML/JS chat UI;
per-socket sessions.

**Files.** `forvum-channel-web/.../WebChannel.java`, `ChatSocket.java`,
`src/main/resources/META-INF/resources/index.html`, `chat.js`, manifest.

**Acceptance Criteria.**
- In dev mode, opening `http://localhost:8080/` and exchanging a message shows streamed tokens; a
  second tab gets a separate session id.
- **[NATIVE]** §10 lists M16 must-run-native.
- **[PLUGIN]** `quarkus/skills` for `websockets-next` before code; `quarkus/searchDocs`; tests via Dev
  MCP.

**Dependencies.** M3, M7. Unblocks: P2-14 (approval-queue UI).

**Suggested commit.** `feat(channel-web): add WebSockets Next chat channel with minimal UI`

---

## M17 — Telegram channel (long-poll)
**Labels:** `phase-1`, `channel`, `native`, `security`, `plugin-tooling` · **Milestone:** `v0.1 MVP`

**Context.** First messaging channel (§7.1 M17). Tied to Risk #8 (long-poll vs webhook) and Risk #12
(reactive client → resolved by a blocking client on a virtual thread, §3.8).

**Scope / Deliverables.** `forvum-channel-telegram`: long-poll bot via a blocking `quarkus-rest-client`
on a virtual thread (`@RunOnVirtualThread`); webhook opt-in; `allowedUserIds` gate.

**Files.** `forvum-channel-telegram/.../TelegramChannel.java`, `TelegramBotApi.java` (blocking REST
client), manifest.

**Acceptance Criteria.**
- With a bot token in the keychain, a live DM produces a reply within the turn-latency budget;
  `allowedUserIds` refuses other users with a friendly message. No Mutiny in channel/engine source —
  the blocking client keeps the path virtual-thread-native (Risk #12); the M5/M6 import-grep guards
  against reactive creep.
- **[NATIVE]** §10 lists M17 must-run-native (REST-client stack must compile native).
- Security negative test (X5/TEST-SEC): a spawn-boundary identity-override attempt is rejected.
- **[PLUGIN]** `quarkus/skills` for `rest-client`; `context7` for Telegram Bot API shapes;
  add the extension via the MCP.

**Dependencies.** M3, M7, M8. Unblocks: nothing downstream blocks on it.

**Suggested commit.** `feat(channel-telegram): add long-poll Telegram channel`

---

## M18 — LangGraph4j supervisor graph (CRITICAL native)
**Labels:** `phase-1`, `engine`, `native`, `context-engineering`, `blocked` · **Milestone:** `v0.1 MVP`

**Context.** The Orchestrator-Workers hub-and-spoke topology (§5.5) materialized as a LangGraph4j
`StateGraph`. **REWRITTEN per the native mandate (REQ #1):** there is **NO StructuredTaskScope spike**.
`StructuredTaskScope` (JEP 505) is preview in JDK 25 and is NOT adopted in v0.1. Structured fan-out is
the committed design using `Executors.newVirtualThreadPerTaskExecutor()` (try-with-resources) +
`CompletionStage` join (or LangGraph4j orchestration). The native build is `--enable-preview`-free by
construction. Structured concurrency is re-evaluated only after the JEP finalizes (post-JDK 26) — a
roadmap note, not a v0.1 spike. Tied to Risk #4 (corrected: LangGraph4j is **stable 1.8.x**, pinned at
`1.8.17`, not pre-1.0) and the LangGraph4j native-metadata risk.

**Scope / Deliverables.** `SupervisorGraph` (`StateGraph` compiler) with nodes `route`, `generate`,
`tool_loop`, `spawn_worker`, `worker_run`, `reduce`; `GraphState`. The `reduce` node Compresses each
worker's window through the small-and-fast model (`qwen3:1.7b`) so only the digest crosses the
worker→supervisor boundary (Isolate defense + cross-agent-injection guardrail; CE REQ #2). Workers run
in parallel on virtual threads (§3.8), replacing a serial cascade.

**Files.** `forvum-engine/.../graph/SupervisorGraph.java`, node impls
(`route`/`generate`/`tool_loop`/`spawn_worker`/`worker_run`/`reduce`), `GraphState.java`.

**Acceptance Criteria.**
- A multi-tool scenario ("fetch X then summarize") routes `tool_loop`→`generate`, produces the expected
  final message, and writes a CAPR event for the turn.
- **[NATIVE] CRITICAL.** Acceptance = VT fan-out works in native + the native graph smoke is green on
  both CI platforms (LangGraph4j hand-authored reachability metadata in place under
  `forvum-engine/src/main/resources/META-INF/native-image/`; graph-state types are records carrying
  `@RegisterForReflection`). NO `--enable-preview`. NO STS decision sub-issue.
- **[PLUGIN]** LangGraph4j is NOT a Quarkus extension → use `context7` (NOT `quarkus/skills`) for the
  `StateGraph`/`MessagesState`/`LC4jToolService` API. Reuse the template's sub-agent + streaming-bridge
  + `@WebSocket` shapes, but orchestrate with the LangGraph4j `StateGraph`, NOT the declarative
  `@SequenceAgent`/`@SupervisorAgent` annotations.

**Dependencies.** M5, M7, M8, M13 (`tool_loop`). Unblocks: M19, P2-12, P3-3, P3-4, P3-8, P3-10.

**Suggested commit.** `feat(engine): add LangGraph4j supervisor-workers orchestration`

---

## M19 — Quarkus Scheduler & file-driven crons
**Labels:** `phase-1`, `engine`, `native` · **Milestone:** `v0.1 MVP`

**Context.** Background runs from `~/.forvum/crons/*.json` (§7.1 M19), each with its own LLM chain.

**Scope / Deliverables.** `CronScheduler` registers `@Scheduled` programmatically from `crons/*.json`;
per-cron `FallbackChain` distinct from the agent default; overlap suppression; hot reload on new cron
file. `@Scheduled` methods carry `@RunOnVirtualThread` (§3.8).

**Files.** `forvum-engine/.../cron/CronScheduler.java`, `CronSpec.java`, `CronTrigger.java`.

**Acceptance Criteria.**
- A cron firing every minute pinned to Ollama triggers a turn and writes `messages`/`provider_calls`/
  `capr_events`; adding a cron file reloads without restart.
- **[NATIVE]** scheduler fires in native; native parity applies.
- **[PLUGIN]** `quarkus/skills` for `quarkus-scheduler` before code; tests via Dev MCP.

**Dependencies.** M4 (hot reload), M5, M7, M8, M18.

**Suggested commit.** `feat(engine): add file-driven cron scheduler with per-cron LLM chain`

---

## M20 — GraalVM native image & CI matrix (CAPSTONE)
**Labels:** `phase-1`, `native`, `ci-infra`, `plugin-tooling`, `blocked` · **Milestone:** `v0.1 MVP`

**Context.** The capstone (§6, §7.1 M20). Operationalizes the native mandate: the §10 native-parity
amendment ("selective" → "mandatory") lands here.

**Scope / Deliverables.** Native `application.properties` flags; `.github/workflows/ci.yml` matrix
(`linux-amd64`, `macos-arm64`; JVM + native builds; native smoke with the 200 ms cold-start gate);
`Dockerfile.jvm`, `Dockerfile.native`. **REWRITE the CI matrix so native is MANDATORY on every cell
(not selective)**; the 200 ms cold-start gate AND the native-build gate fail the PR. GraalVM CE 25 /
Mandrel 25.0.x-Final on runners (pin the exact Mandrel patch in CI).

**Files.** `forvum-app/src/main/resources/application.properties`, `.github/workflows/ci.yml`,
`Dockerfile.jvm`, `Dockerfile.native`.

**Acceptance Criteria.**
- `./mvnw -f forvum-app -Pnative package -Dquarkus.native.container-build=true` succeeds on a clean
  runner; `./forvum-app-<version>-runner --help` prints in < 200 ms.
- **[NATIVE] CAPSTONE.** Native is mandatory on every CI cell; the only sanctioned skip is the M4
  behavioral assertion (which must still native-compile). No `--enable-preview` anywhere (M6/M18
  resolved preview-free).
- **[PLUGIN]** `quarkus/searchDocs` for native + container-build config; Dev MCP for build verification.

**Dependencies.** ALL of M1–M19 (capstone); hard prereq on the M6 (ScopedValue native) and M18 (VT
native graph) verdicts. Unblocks: P3-1, X6 native gating.

**Suggested commit.** `feat(app): add GraalVM native image profile and CI matrix`

---

# EPIC-2 — PHASE 2 v0.5 PARITY (P2-1 … P2-15 + 8 additions)

Every Phase-2 issue adds the acceptance line: **"parity verified against OpenClaw v2026.4.19-beta.2."**
The [NATIVE]/[PLUGIN] rules apply to all. Common dependency: a stable MVP (M20).

## P2-1 — Browser tool
**Labels:** `phase-2`, `tool`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Headless-browser capability for parity. **Scope.** `web.browse` tool (Playwright Java) with
`PermissionScope.WEB_BROWSE`. **Files.** `forvum-tools-browser`. **Acceptance.** A `web.browse` call
against a local fixture page returns extracted content; scope enforced; parity verified against OpenClaw
v2026.4.19-beta.2. **[NATIVE]** Playwright native-image is high-risk → likely JVM-only carve-out, must
be justified with an upstream issue. **[PLUGIN]** scaffold module via the MCP; `context7` for
Playwright-Java + the langchain4j tool API. **Dependencies.** M13, M14, MVP stable.
**Commit.** `feat(tools-browser): add headless browser web.browse tool`

## P2-2 — Code-execution sandbox
**Labels:** `phase-2`, `tool`, `security`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Safe `shell.exec` replacement. **Scope.** Run code in a container or Firecracker microVM.
**Files.** `forvum-tools-sandbox`. **Acceptance.** Sandboxed run returns output; escape attempt
contained; `USER_CONFIRM_REQUIRED` honored; parity verified. **[NATIVE]** native binary launches the
sandbox runtime; verify. **[PLUGIN]** scaffold via the MCP. **Dependencies.** M13, the
`forvum-tools-shell` pattern (X7 — shell tool has no Phase-1 milestone; resolve via M13).
**Commit.** `feat(tools-sandbox): add containerized code-execution sandbox`

## P2-3 — Voice channel
**Labels:** `phase-2`, `channel`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Local TTS/STT parity. **Scope.** Whisper + Piper streaming channel. **Files.**
`forvum-channel-voice`. **Acceptance.** Spoken input transcribed → turn → spoken reply streamed; parity
verified. **[NATIVE]** native Whisper/Piper bindings — verify or JVM-only carve-out. **[PLUGIN]**
scaffold channel module via the MCP. **Dependencies.** M3, M7, MVP stable.
**Commit.** `feat(channel-voice): add local Whisper/Piper voice channel`

## P2-4 — Device pairing
**Labels:** `phase-2`, `engine`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Pair a second device reusing identity + memory. **Scope.** `forvum-engine/pairing`.
**Acceptance.** Paired device shares identity + memory namespace; unpaired device rejected; parity
verified. **[NATIVE]** engine submodule, native parity. **[PLUGIN]** `quarkus/skills` for any extension.
**Dependencies.** M6, M7, MVP stable. **Extended by** P2-PAIR-SCOPE.
**Commit.** `feat(engine): add device pairing reusing identity and memory`

## P2-5 — Memory-host SDK
**Labels:** `phase-2`, `sdk`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Public SPI for third-party `MemoryProvider` impls. **Scope.** Document the SPI
(declared at M3) + reference impl (Redis/Qdrant/Chroma) + docs. **Files.** `forvum-sdk` + a reference
impl module. **Acceptance.** A reference external `MemoryProvider` loads and serves semantic memory via
the SPI; parity verified. **[NATIVE]** reference impl native-clean. **[PLUGIN]** scaffold the reference
module via the MCP. **Dependencies.** M3, semantic_memory (M5). DR-5 (`MemoryPolicy`) informs the SPI.
**Commit.** `feat(sdk): document MemoryProvider host SPI with reference implementation`

## P2-6 — Maven plugin marketplace
**Labels:** `phase-2`, `engine`, `native` · **Milestone:** `v0.5 Parity`
**Context.** `forvum plugin install <coords>`. **Scope.** Resolve a Maven coordinate, write to
`~/.forvum/plugins/`, trigger fast-jar restart; native users told to rebuild. **Files.**
`forvum-app/.../cli/PluginInstallCommand.java`, `forvum-engine/.../plugin/MavenPluginResolver.java`.
**Acceptance.** Installing a coordinate makes a drop-in plugin available in fast-jar after
restart; parity verified. **[NATIVE]** drop-in is JVM-fast-jar-ONLY BY DESIGN (§6.2/§6.3) — a documented
architectural property, NOT a carve-out from the mandate; native path = rebuild. **[PLUGIN]** scaffold
via the MCP. **Dependencies.** §6.3 build-time discovery, ServiceLoader fast-jar fallback.
**Commit.** `feat(app): add Maven-coordinate plugin install command`

## P2-7 — Skill marketplace
**Labels:** `phase-2`, `engine`, `native` · **Milestone:** `v0.5 Parity`
**Context.** `forvum skill install <url>`. **Scope.** Add a `skills/<skill>.md` from a git repo or gist.
**Files.** `forvum-app/.../cli/SkillInstallCommand.java`. **Acceptance.** Installing a URL adds a skill `.md` invocable by the
skill tool; parity verified. **[NATIVE]** native parity (pure file write). **[PLUGIN]** scaffold via the
MCP. **Dependencies.** the skills surface (`SkillInvokerTool`; X7 — no Phase-1 milestone; resolve via
M13/M18). **Commit.** `feat(app): add skill install command from URL`

## P2-8 — Session replay
**Labels:** `phase-2`, `engine`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Replay a session for debugging/regression. **Scope.** CLI (`forvum replay <sessionId>`)
replays a session from `messages` with original tool outputs — it reproduces the *recorded* transcript
(messages interleaved with their `tool_invocations`, oldest first) and does not re-invoke the model
(re-execution with substitution is P3-9). **Files.** `forvum-app/.../app/SessionReplayCommand.java`,
`forvum-engine/.../replay/SessionReplayer.java`. **Acceptance.** Replaying a stored session
reproduces the message sequence and tool outputs; parity verified. **[NATIVE]** native parity.
**[PLUGIN]** scaffold via the MCP. **Dependencies.** M5. **Extended by** P3-9.
**Commit.** `feat(app): add session replay command`

## P2-9 — Config doctor
**Labels:** `phase-2`, `engine`, `native` · **Milestone:** `v0.5 Parity`
**Context.** `forvum doctor`. **Scope.** Validate the whole `~/.forvum/` layout and surface problems with
actionable hints, exiting non-zero on any error. v0.5 validates by reusing the M4 readers
(`ConfigLoader`/`AgentReader`/…) and the engine's typed binders (`AgentSpecReader`/`CronSpecReader`) as the
validation oracles — plus cross-reference checks (a model ref must resolve to an installed provider; a
cron's `agentId` must name a known agent) — NOT a standalone JSON-Schema library, so doctor never drifts
from how the engine actually parses config (maintainer-signed-off; formal JSON Schemas remain a deferred
fast-follow). **Files.** `forvum-app/.../app/DoctorCommand.java`,
`forvum-engine/.../doctor/ConfigDoctor.java` (+ `Finding`/`DoctorReport`/`Severity`), `CommandMode`
(`doctor` one-shot). **Acceptance.** A malformed config produces a specific actionable error; valid config
passes; parity verified. **[NATIVE]** native parity (offline + deterministic — `DoctorNativeIT` runs
untagged in the default native leg). **[PLUGIN]** scaffold via the MCP. **Dependencies.** M4. **Used by**
P2-19 drift surfacing (P2-PAIR-SCOPE), P3-6. **Commit.** `feat(app): add config doctor validating ~/.forvum
layout`

## P2-10 — Provider onboarding wizard
**Labels:** `phase-2`, `engine`, `native` · **Milestone:** `v0.5 Parity`
**Context.** `forvum provider add <name>`. **Scope.** Walk a keychain entry, default fallback-chain
update, smoke-test turn. **Files.** `forvum-app/.../cli/ProviderAddCommand.java`,
`forvum-engine/.../provider/OnboardingWizard.java`. **Acceptance.** Running the wizard stores a key
in the keychain, updates the `config.json` chain, and runs a smoke turn; parity verified. **[NATIVE]**
native keychain access (macOS Keychain / Secret Service / Win Credential Manager) must work native.
**[PLUGIN]** scaffold via the MCP. **Dependencies.** M9–M12, M4. **Extended by** P2-COPILOT (Copilot
OAuth/device-code). **Commit.** `feat(app): add interactive provider onboarding wizard`

## P2-11 — RBAC on tools
**Labels:** `phase-2`, `core`, `security`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Role-based permission sets. **Scope.** A role → `Set<PermissionScope>` mapping *above* the
enum (NOT new `PermissionScope` constants); `identities/<id>.json` declares `roles`; cron jobs get a
distinguished `cron` role; a second `ToolExecutor` gate denies an in-belt tool whose required scope is
outside the caller's effective scopes. **Files.** `forvum-core` (`RoleSpec` record + `Identity.roles`,
§4.3.4), engine `RoleRegistry`/`RoleReader`/`RoleSpecReader` + `CurrentIdentity` ScopedValue + `ToolExecutor`
gate + binds in `TurnService`/`CronScheduler`, `forvum-app` security tests. **Acceptance.** A role-restricted
identity is denied an out-of-role tool; the cron role is enforced; parity verified. **[NATIVE]** native
parity; tied to the security-test layer. **[PLUGIN]** `quarkus/skills`. **Dependencies.** M13. **Absorbs**
P2-CRON-DELIVERY's cron-role hook. **Commit.** `feat(core): add role-based access control on tool
permission scopes`

**Status (as-built, P2-11).** DONE. **Diverged from the literal "extend `PermissionScope`":** the enum is
unchanged (FS_READ/FS_WRITE) — roles *cable* scope-sets above it (ULTRAPLAN §4.3.4 mandates the mapping
live above the enum, not inside it). Design (maintainer-signed-off): config-driven `roles/<name>.json` +
hot-reload `RoleRegistry` with code-level built-ins (`default-user` = every scope, permissive default for
an identity that declares none → backward compatible, no migration; `cron` = read-only, the distinguished
restricted role), enforcement via a `CurrentIdentity.CURRENT_EFFECTIVE_SCOPES` ScopedValue bound at the
two turn entries (`TurnService.dispatch` / `CronScheduler.fire`) and read in `ToolExecutor` (belt AND
scope; unbound ⇒ belt-only, but every production turn entry binds it). No Flyway migration (denial reuses
`tool_invocations.status='denied'`). OpenClaw v2026.4.19-beta.2 parity = its owner/non-owner + restricted-cron
model, expressed in Forvum's capability-scope vocabulary. The role-restricted-deny + cron-role-enforced
security tests are `@QuarkusTest` (in-process, deterministic); native parity = native compile (RoleSpec
reflection-registered) + boot smoke with no `~/.forvum/` (built-in `cron` enforceable). §9 stays DR-6a's
(#59) — P2-11 only adds the two acceptance tests, no broader threat-model contract.

## P2-12 — Per-agent structured output schemas
**Labels:** `phase-2`, `engine`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Agents declare an output JSON Schema. **Scope.** `AgentGraph` decodes the final message
against the schema via LangChain4j structured output. **Files.** engine agent/graph. **Acceptance.** An
agent with a declared schema returns a decoded, schema-valid object; invalid output surfaces an error;
parity verified. **[NATIVE]** native parity. **[PLUGIN]** `context7` for langchain4j structured output
(`@Description`/`@StructuredPrompt`, §3.3). **Dependencies.** M7, M18.
**Commit.** `feat(engine): add per-agent structured output schema decoding`

## P2-13 — MCP server registry enrichments
**Labels:** `phase-2`, `tool`, `native` · **Milestone:** `v0.5 Parity`
**Context.** `forvum mcp add <url>` / `forvum mcp list`. **Scope.** Remote MCP tools appear in
`ToolRegistry` within seconds. **Files.** `forvum-tools-mcp-bridge` (declared §2.4, off-by-default in
v0.1 per Risk #9) + CLI. **Acceptance.** Adding an MCP server URL surfaces its tools; `mcp list` shows
them; parity verified. **[NATIVE]** Risk #9: stdio MCP servers spawn subprocesses; flip default-on only
after the native smoke passes on all platforms. The MCP client is the Quarkiverse
`quarkus-langchain4j-mcp` extension (native-ready), NOT the standalone `langchain4j-mcp` beta.
**[PLUGIN]** `quarkus/skills` for the MCP-client extension. **Dependencies.** M13, the
`forvum-tools-mcp-bridge` baseline (X7 — no Phase-1 milestone; resolve via M13).
**Commit.** `feat(tools-mcp): add MCP server registry add/list commands`

## P2-14 — User-approval queue UI
**Labels:** `phase-2`, `engine`, `channel`, `security`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Surface pending `USER_CONFIRM_REQUIRED` tool calls. **Scope.** Dev UI + web-channel cards
with approve/reject. **Files.** Dev UI card + web channel. **Acceptance.** A pending confirm-required
call appears; approve runs it, reject denies + audits; parity verified. **[NATIVE]** Dev UI is dev-mode
(fast-jar); the web-channel card must work native. **[PLUGIN]** `quarkus/skills` for Dev UI card
patterns. **Dependencies.** M13 (the `USER_CONFIRM_REQUIRED` hook), M16. The per-channel security UX is
specified by DR-6a. **Commit.** `feat(engine): add user-approval queue UI for confirm-required tools`

## P2-15 — Telemetry export
**Labels:** `phase-2`, `engine`, `observability`, `native` · **Milestone:** `v0.5 Parity`
**Context.** OTLP export. **Scope.** OTLP exporter on when `OTEL_EXPORTER_OTLP_ENDPOINT` is set (default
off); zero-config for Honeycomb/Grafana Tempo/Datadog; also exports the §3.8 Concurrency-card VT/PT +
pin data. **Files.** engine observability config. **Acceptance.** With the env var set, spans/metrics
export to a local OTLP collector; default-off when unset; parity verified. **[NATIVE]**
`quarkus-opentelemetry` OTLP native parity (let the Quarkus BOM govern the version). **[PLUGIN]**
`quarkus/searchDocs` for OTLP config. **Dependencies.** the §3.6 OTel baseline (X7 — wired across
M-milestones, no single milestone; resolve via M13/M18). **Commit.** `feat(engine): add OTLP telemetry
export gated by endpoint env var`

### Parity additions (§7.2 items 16–23, reconciled to OpenClaw v2026.4.19-beta.2)

## P2-CH — Additional first-party channels (§7.2 item 16)
**Labels:** `phase-2`, `channel`, `native` · **Milestone:** `v0.5 Parity`
**Context.** OpenClaw ships a broad channel catalog; Forvum replicates the architecture and ships a
curated set. **Scope.** `forvum-channel-discord`, `-slack`, `-whatsapp`, `-matrix`, `-signal`. The long
tail (iMessage/BlueBubbles, Teams, Google Chat, Mattermost, Feishu, LINE, QQ, Zalo/ZaloUser, IRC, Nostr,
Tlon, Twitch, Synology Chat, Nextcloud Talk, telephony voice-call) is explicitly OUT of v0.5 scope =
community-plugin territory (via the §7.2 item 6 marketplace). **Acceptance.** Each shipped channel
exchanges a message round-trip; the out-of-scope long tail is documented as community-plugin territory;
parity verified against OpenClaw v2026.4.19-beta.2. **[NATIVE]** every shipped channel native-buildable +
native smoke. **[PLUGIN]** scaffold each channel module via the MCP; `quarkus/skills` for the HTTP/WS
extension per channel. **Dependencies.** M3, M7, M17 (channel pattern), MVP stable.
**Commit.** `feat(channel): add Discord/Slack/WhatsApp/Matrix/Signal first-party channels`

## P2-COPILOT — GitHub Copilot model provider (§7.2 item 17)
**Labels:** `phase-2`, `provider`, `native`, `plugin-tooling` · **Milestone:** `v0.5 Parity`
**Context.** OpenClaw supports Copilot as a provider. **Scope.** `forvum-provider-copilot`
(OpenAI-compatible endpoint; Copilot OAuth/device-code via the onboarding wizard P2-10).
**Files.** `forvum-provider-copilot/` module + onboarding wizard hook. **Acceptance.** Device-code OAuth
completes, a key is stored, a turn against the Copilot endpoint returns a reply; parity verified.
**[NATIVE]** must-run-native (OpenAI-compatible HTTP). **[PLUGIN]** add the openai-compatible extension
via the MCP; `context7` for the API. **Dependencies.** M11 (OpenAI-compatible pattern), P2-10 (wizard).
**Commit.** `feat(provider-copilot): add GitHub Copilot model provider`

## P2-QA — QA scenario suite (§7.2 item 18)
**Labels:** `phase-2`, `ci-infra`, `native` · **Milestone:** `v0.5 Parity`
**Context.** OpenClaw ships a QA scenario harness. **Scope.** `forvum qa suite` / `forvum qa <channel>`,
fails-by-default; a scenario pack ships in the release; CI gate. **Files.** QA harness module + scenario
pack + CI step. **Acceptance.** `forvum qa suite` runs the packaged scenarios and fails by default on a
missing/failed scenario; the CI gate enforces it; parity verified. **[NATIVE]** the QA runner runs
against the native binary. **[PLUGIN]** tests via Dev MCP; scaffold via the MCP. **Dependencies.** M15,
M16, M17 (channels under test), X6 (E2E). **Commit.** `feat(app): add QA scenario suite with CI gate`

## P2-PAIR-SCOPE — Device pairing with scope-upgrade approval (§7.2 item 19)
**Labels:** `phase-2`, `engine`, `security`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Extends P2-4 with scope governance. **Scope.** Scope-upgrade approval with reason codes;
requested-vs-approved shown in the Dev UI + `forvum devices`; `forvum doctor` surfaces drift.
**Files.** `forvum-engine/pairing` + `forvum devices` CLI + Dev UI + doctor hook. **Acceptance.** A
scope upgrade requires approval, records reason codes, and drift is surfaced by `forvum doctor`; parity
verified. **[NATIVE]** native parity. **[PLUGIN]** `quarkus/skills` for Dev UI cards.
**Dependencies.** P2-4, P2-9 (doctor), P2-11 (RBAC).
**Commit.** `feat(engine): add device pairing scope-upgrade approval with drift detection`

## P2-COMPACT — Session compaction (§7.2 item 20; CE Compress)
**Labels:** `phase-2`, `engine`, `context-engineering`, `native` · **Milestone:** `v0.5 Parity`
**Context.** OpenClaw compacts sessions to fit the context window; this is a **CE Compress** realization
(cross-links REQ #2). **Scope.** Reserve-token floor capped to the context window; mutate the oldest
turns first to preserve the cached prefix; strip orphaned reasoning/tool blocks. **Files.** engine
session/compaction submodule. **Acceptance.** A session exceeding the reserve floor is compacted oldest-
first, the cached prefix is preserved, orphaned blocks are stripped, and CAPR is not regressed; parity
verified. **[NATIVE]** native parity. **[PLUGIN]** `context7` for langchain4j memory/summarization.
**Dependencies.** M5, M18 (the `reduce` Compress path). **Commit.** `feat(engine): add prefix-preserving
session compaction`

## P2-TASKLEDGER — Detached task runtime registration (§7.2 item 21)
**Labels:** `phase-2`, `engine`, `sdk`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Forvum improves on OpenClaw with a queryable task ledger as a day-one primitive (§1.2).
**Scope.** `TaskExecutor` SPI in `forvum-sdk`; a SQLite `tasks` ledger unifying cron/sub-agent/
background runs. **Files.** `forvum-sdk/.../TaskExecutor.java` + engine task runtime + Flyway migration
for `tasks`. **Acceptance.** Cron/sub-agent/background runs all register in the `tasks` ledger and are
queryable; parity verified. **[NATIVE]** native parity. **[PLUGIN]** `quarkus/skills` for the persistence
extension; tests via Dev MCP. **Dependencies.** M5, M7, M18, M19.
**Commit.** `feat(sdk): add TaskExecutor SPI with unified SQLite task ledger`

## P2-CRON-DELIVERY — Cron isolated-agent delivery modes (§7.2 item 22)
**Labels:** `phase-2`, `engine`, `native` · **Milestone:** `v0.5 Parity`
**Context.** Control where a cron's isolated-agent output is delivered. **Scope.** `delivery.mode:
none|last|explicit-to`; per-execution dedupe; ambiguity rejected at add/update; folds into the P2-11
`cron` RBAC role. **Files.** `forvum-engine/.../cron/` + CronSpec growth. **Acceptance.** Each delivery
mode routes output correctly; per-execution dedupe holds; an ambiguous spec is rejected at add/update;
parity verified. **[NATIVE]** native parity. **[PLUGIN]** `quarkus/skills`. **Dependencies.** M19, P2-11.
**Commit.** `feat(engine): add cron isolated-agent delivery modes`

## P2-OUTPUTGUARD — OutputGuard SPI (§7.2 item 23; CE REQ #2)
**Labels:** `phase-2`, `sdk`, `security`, `context-engineering`, `native`, `blocked` · **Milestone:** `v0.5 Parity`
**Context.** The v0.5 realization of the §1.4 outbound-filter promise: an outbound secret/PII filter.
The full contract is defined by DR-6a (§9.2 `OutputFilter`). **Scope.** `OutputGuard`
SPI in `forvum-sdk`; outbound sensitive-data filter at the pre-channel-emit hook. **Files.**
`forvum-sdk/.../OutputGuard.java` + engine enforcement. **Acceptance.** A configured `OutputGuard`
blocks/redacts outbound secrets/PII at the channel boundary per the §9.2 contract; parity verified.
**[NATIVE]** native parity. **[PLUGIN]** `quarkus/skills` for the SPI/CDI pattern. **Dependencies.**
M13, M16/M17 (channel emit), **DR-6a** (defines the `OutputFilter` contract — blocks this).
**Commit.** `feat(sdk): add OutputGuard outbound sensitive-data filter SPI`

---

# EPIC-3 — PHASE 3 v1.0+ (P3-1 … P3-10)

## P3-1 — Single-binary install as headline UX
**Labels:** `phase-3`, `native`, `ci-infra` · **Milestone:** `v1.0+`
**Context.** The product expression of the native mandate (REQ #1): `curl | sh` drops a ~40 MB native
binary; no runtime/Docker/Node. **Scope.** Install script + release pipeline (extends §6.4).
**Acceptance.** `curl | sh` installs a runnable native binary on linux-x64 and macos-arm64; size budget
~40 MB. **[NATIVE]** the core of the whole mandate. **[PLUGIN]** N/A (uses §6.4 distribution).
**Dependencies.** M20, §6.4 distribution. **Commit.** `feat(release): add curl-based single-binary
native installer`

## P3-2 — Queryable semantic memory
**Labels:** `phase-3`, `engine`, `persistence`, `native` · **Milestone:** `v1.0+`
**Context.** `forvum memory query 'SELECT ...'` over SQLite + `sqlite-vec`. Highest native-risk Phase-3
item (Risk #2). **Scope.** engine CLI + Flyway V3 (`sqlite-vec` vec0 virtual table). **Acceptance.** A
SQL query over `semantic_memory` returns rows; vector search returns nearest neighbors. **[NATIVE]**
Risk #2: `sqlite-vec` is a C extension; native static-linking varies by platform — benchmark linear scan
at 10k/100k/1M; defer vec0 if linear is acceptable at 100k. **[PLUGIN]** `quarkus/searchDocs` for SQLite
extension loading. **Dependencies.** M5, P2-5, the Risk #2 decision.
**Commit.** `feat(app): add queryable semantic memory CLI over sqlite-vec`

## P3-3 — LangGraph4j cyclic agents as a first-class primitive
**Labels:** `phase-3`, `engine`, `native` · **Milestone:** `v1.0+`
**Context.** Declarative cycles (e.g. `reflect→critique→revise`). **Scope.** Engine compiles a declared
cycle into a `StateGraph` with no custom code. **Acceptance.** An agent with a declared cycle runs the
loop to a termination condition and produces a refined result. **[NATIVE]** native parity (depends on
the M18 VT-fan-out verdict). **[PLUGIN]** `context7` for the LangGraph4j cyclic-graph API.
**Dependencies.** M18. **Commit.** `feat(engine): add declarative cyclic-agent compilation to StateGraph`

## P3-4 — CAPR-driven adaptive model routing
**Labels:** `phase-3`, `engine`, `observability`, `native` · **Milestone:** `v1.0+`
**Context.** `LlmSelector` consults rolling CAPR per model over the last N turns and down-ranks sagging
models; the router is itself a small local model seeing the CAPR snapshot (Select pillar). **Scope.**
`forvum-engine/.../routing/`. **Acceptance.** With seeded CAPR data, a low-pass-rate model is down-ranked
in routing decisions. **[NATIVE]** native parity. **[PLUGIN]** `quarkus/skills`; `context7` for
langchain4j. **Dependencies.** M8, M18 (CAPR events), P3-10.
**Commit.** `feat(engine): add CAPR-driven adaptive model routing`

## P3-5 — Multi-user toggle
**Labels:** `phase-3`, `engine`, `persistence`, `native` · **Milestone:** `v1.0+`
**Context.** `multiUser:true` enables per-user isolation; same binary both modes. **Scope.** Per-user
`$FORVUM_HOME` isolation, identity-scoped SQLite schemas, shared-memory namespace for team skills.
**Acceptance.** With multiUser on, two users get isolated state; the team-skill namespace is shared.
**[NATIVE]** native parity (same binary). **[PLUGIN]** `quarkus/skills`. **Dependencies.** M5, M6, M7.
**Unblocks** P3-7. **Commit.** `feat(engine): add multi-user toggle with per-user isolation`

## P3-6 — Dev UI live-edit of configs
**Labels:** `phase-3`, `engine`, `native` · **Milestone:** `v1.0+`
**Context.** Edit `~/.forvum/` files from the Dev UI with schema validation + hot-reload preview.
**Scope.** Dev UI cards (extends the §3.2/§6.1 Dev UI surface). **Acceptance.** Editing a config in the
Dev UI validates against the schema and hot-reloads without restart. **[NATIVE]** Dev UI is dev-mode
(fast-jar) ONLY — explicit, documented native carve-out (Dev UI is not in the native binary).
**[PLUGIN]** `quarkus/skills` for Dev UI card patterns. **Dependencies.** M4, P2-9 (JSON Schemas).
**Commit.** `feat(engine): add Dev UI live config editor`

## P3-7 — Kubernetes-native team-assistant mode
**Labels:** `phase-3`, `engine`, `native` · **Milestone:** `v1.0+`
**Context.** Deploy Forvum as a team assistant in k8s. **Scope.** Helm chart + a Quarkus
Kubernetes-client operator with per-namespace memory isolation. **Acceptance.** A Helm deploy stands up
Forvum in k8s; per-namespace memory is isolated. **[NATIVE]** native container-image deployment.
**[PLUGIN]** `quarkus/skills` for `quarkus-kubernetes`/`-client`. **Dependencies.** P3-5, §6.4 OCI images.
**Commit.** `feat(k8s): add Helm chart and operator for team-assistant mode`

## P3-8 — Proxy-model compression middleware
**Labels:** `phase-3`, `engine`, `context-engineering`, `native` · **Milestone:** `v1.0+`
**Context.** Materializes the CONTEXT-ENGINEERING "proxy model" Compress pattern (REQ #2). **Scope.** A
Sentinel-style compression layer between retrievers and the generator using a tiny local model
(Ollama `qwen3:1.7b`) to score/prune chunks. **Acceptance.** With compression on, retrieved context is
pruned below a token budget while preserving answer quality (CAPR not regressed). **[NATIVE]** native
parity. **[PLUGIN]** `context7` for langchain4j retrieval/RAG; `quarkus/skills`. **Dependencies.** M9
(Ollama), M18, semantic memory (P3-2). **Commit.** `feat(engine): add proxy-model compression middleware`

## P3-9 — Queryable session replay via SQL
**Labels:** `phase-3`, `engine`, `native` · **Milestone:** `v1.0+`
**Context.** Replay any session with any substitution because the schema captures everything (extends
P2-8). **Scope.** engine replay + CLI. **Acceptance.** A session replays with a substituted
model/tool-output/memory-policy and produces a comparable trace; the substitution is recorded.
**[NATIVE]** native parity. **[PLUGIN]** scaffold via the MCP. **Dependencies.** P2-8, M5.
**Commit.** `feat(app): add SQL-driven session replay with substitution`

## P3-10 — First-party evaluation harness with CAPR gating
**Labels:** `phase-3`, `engine`, `observability`, `ci-infra`, `native` · **Milestone:** `v1.0+`
**Context.** `forvum eval <suite>` enforces a CAPR floor and fails the release on regression — a CI
quality gate on par with coverage. **Scope.** eval harness module + CI integration. **Acceptance.**
Running a suite computes CAPR; a regression below the floor fails the eval (and the CI release gate).
**[NATIVE]** native parity; the judge-model cost/latency caveat (Risk #10: judge off by default in prod,
cheap local Ollama judge; measure judge-vs-human agreement, replace if < 0.7). **[PLUGIN]** `context7`
for langchain4j eval primitives. **Dependencies.** M18 (CAPR events), P3-4.
**Commit.** `feat(eval): add CAPR-gated evaluation harness`

---

# EPIC-DR — DESIGN & CONTRACTS

These contracts are settled on `main` (the live design surface). An architectural change starts with a
GitHub issue/discussion for design sign-off, then a PR. No `*TBD*` marker should remain in
`docs/ULTRAPLAN.md` once these land.

## DR-6a — Settle Group 6a (threat model + tool-execution filters)
**Labels:** `design`, `security`, `context-engineering` · **Milestone:** `Design & Contracts`
**Context.** Group 6a (threat model + tool filters) has an inventory + 8 pre-committed constraints + 6
open design points but no signed-off decisions yet. It is the highest-leverage item — it unblocks five
downstream items and creates §9.
**Scope / Deliverables.** Deliberate the 6 open points; record the decisions; author **§9.1 Threat
Model** (STRIDE by surface, everything touching `ToolExecutor`) + **§9.2 Tool-Execution Filters**
(`OutputFilter` contract: hook layers pre-tool-call / pre-channel-emit / pre-memory-write, policy shape,
trip outcome block-vs-redact-vs-`FallbackReasons.FILTERED`, `PermissionScope` composition;
`WorkspaceRoot` contract for fs tools; `ShellAllowlist` contract; prompt-injection structural defense;
per-channel security UX), inserted between §8 and §10. Honor the 8 pre-committed constraints.
**Files.** `docs/ULTRAPLAN.md` (new §9.1/§9.2; resolve §10's "see §9 once it lands" forward-reference;
upgrade the §1.4 governance bullet from principle to contract).
**Acceptance Criteria.** Decisions recorded; §9.1 + §9.2 inserted; new exception types decided; the
prompt-injection-mitigation CE Guardrails pillar becomes structural; §10 forward-reference resolved.
**Dependencies.** §3.8 (done), §10 (done). **Blocks:** DR-4c, DR-5, DR-6b, DR-8, TEST-SEC, P2-OUTPUTGUARD.
**Commit.** `docs(design): settle Group 6a — threat model and tool-execution filters`

## DR-6b — Settle Group 6b (plugin trust + MCP server trust)
**Labels:** `design`, `security` · **Milestone:** `Design & Contracts`
**Context.** Carved out of 6a; not yet specified. **Scope.** Define the
trust boundary for `plugins/` (JVM fast-jar SPI) and configured MCP servers — capability declaration vs
enforcement, sandboxing posture, what a plugin can do to prompt assembly / `PermissionScope`.
**Files.** `docs/ULTRAPLAN.md` §9.3.
**Acceptance.** §9.3 (or a §9.1 STRIDE extension) covers plugin/MCP threat surfaces + the enforcement
contract; decisions recorded. **Dependencies.** DR-6a (reuses the `OutputFilter`/`ToolExecutor`
enforcement seam). **Commit.** `docs(design): settle Group 6b — plugin and MCP server trust`

## DR-6c — Settle Group 6c (audit retention + supply chain + privacy)
**Labels:** `design`, `security` · **Milestone:** `Design & Contracts`
**Context.** Carved out of 6a; not yet specified; largely parallel to 6b. **Scope.** Define the
retention policy for `tool_invocations`/`provider_calls`/`capr_events`, supply-chain posture for
the native build inputs, privacy of persisted conversation + memory. **Files.**
`docs/ULTRAPLAN.md` §9.4 + any §4.2
retention notes. **Acceptance.** §9.4 (or a dedicated subsection) authored; ties to native-first build
inputs; decisions recorded. **Dependencies.** DR-6a (after). **Commit.** `docs(design): settle Group
6c — audit retention, supply chain, privacy`

## DR-4c — Settle Group 4c (FallbackChain)
**Labels:** `design`, `core` · **Milestone:** `Design & Contracts`
**Context.** §4.3.5.3 is literally `*TBD (Group 4c)*`. Group 4b is settled (the explicit blocker); 4c
benefits from DR-6a deciding whether a `Filtered` reason joins `FallbackReasons`. **Scope.** Define
§4.3.5.3 — the `FallbackChain(primary, List<fallback>, CostBudget)` shape, the `FailureClass` enum
permits (incl. the `Filtered` permit handed over by 6a constraint 7), per-link `costDims` (the Group-4b
Decision-9 short-circuit override door), and the `LineageWindow` interplay reserved by Group 4b.
**Files.** `docs/ULTRAPLAN.md` §4.3.5.3.
**Acceptance.** §4.3.5.3 materialized (no longer `*TBD*`); `FailureClass` enum spec'd; the §4.3.2
line-477 migration path (`String reason` → `FailureClass`) pinned to M8. **Dependencies.** Group 4b
(done); benefits from DR-6a. **Commit.** `docs(design): settle Group 4c — FallbackChain contract`

## DR-5 — Settle Group 5 (MemoryPolicy)
**Labels:** `design`, `core`, `context-engineering` · **Milestone:** `Design & Contracts`
**Context.** §4.3.6 is `*TBD (Group 5)*`; `MemoryPolicy` is already listed in `forvum-core` types and
inherited at spawn. **Scope.** Define §4.3.6 — the `MemoryPolicy` record/shape, the Write/Compress
governance role, retrieval framing as `<retrieved_memory>` data blocks (6a point 5), the pre-memory-write
`OutputFilter` boundary (6a point 2c), spawn inheritance alongside `CostBudget`/`Identity`.
**Files.** `docs/ULTRAPLAN.md` §4.3.6.
**Acceptance.** §4.3.6 materialized; `MemoryPolicy` confirmed in the `forvum-core` type list; dissolves
demo deferral D2's memoryPolicy gap. **Dependencies.** DR-6a (memory-write boundary + retrieval
framing); touches M5 episodic+semantic memory. **Commit.** `docs(design): settle Group 5 —
MemoryPolicy contract`

## DR-8 — Settle Group 8 (Persona / AgentSpec composition)
**Labels:** `design`, `core` · **Milestone:** `Design & Contracts`
**Context.** Named as a downstream consumer in 6a; matches demo deferral D2. Last in the chain.
**Scope.** Formalize the `AgentSpec` record composing `Identity`, `Persona`, `FallbackChain`,
`CostBudget`, `MemoryPolicy`, the allowed `PermissionScope` set, and the parent pointer — replacing the
demo's ad-hoc shape; define the on-disk `agents/<id>.json` schema authoritatively. **Files.**
`docs/ULTRAPLAN.md` new §4.3.x `AgentSpec` subsection.
**Acceptance.** The `AgentSpec` subsection authored; demo D2 resolved permanently; the `agents/<id>.json`
schema defined. **Dependencies.** DR-4c, DR-5, DR-6a (needs all composed types to exist first).
**Commit.** `docs(design): settle Group 8 — Persona and AgentSpec composition`

## TEST-SEC — Security-test layer (the "Group 7 Testing" gap)
**Labels:** `design`, `security`, `ci-infra` · **Milestone:** `Design & Contracts`
**Context.** "Group 7 Testing" is §10 (settled discipline) + the §9-gated
security-test layer + per-milestone test debt. **Scope.** Stand up
`forvum-app/src/test/java/ai/forvum/security/` negative integration tests (prompt injection → no tool
escalation; path traversal → denied (M14); spawn-boundary identity override → rejected (M7/M17);
`PermissionScope` mismatch → denied + audited (M13)), landing per-milestone alongside M3/M13/M14/M16/M17.
**Files.** `forvum-app/src/test/java/ai/forvum/security/`. **Acceptance.** Each negative test fails the
build on a regression; implements §10's already-written security-test bullet once §9 exists.
**[NATIVE]** the security tests run against the native binary where the underlying milestone is native.
**Dependencies.** DR-6a (the contracts under test). **Commit.** `test: add security negative-test layer`

## BR-CLEANUP — Branch hygiene
**Labels:** `design`, `branch-hygiene` · **Milestone:** `Design & Contracts`
**Context.** `design-round-tier1` is fully superseded by `main` (its group-4b is pre-decision; group-6a
is absent) and would only cause confusion if someone branched from it. `demo/conference-mvp` is a
throwaway vertical slice carrying deferrals D1–D8. **Scope.** Delete the stale `design-round-tier1`
branch; decide the demo branch's fate (discard per its own "Return path", or cherry-pick learnings —
unlikely to match the Tier-1 contracts). **Files.** none (repo hygiene); referenced in
`forvum/CLAUDE.md`'s branch-model section. **Acceptance.** `design-round-tier1` deleted; the demo branch
fate decided and recorded; the deferrals D1–D8 migrate into the relevant M-issues / contracts when
settled (D8 → M5; D1 → M9–M12; D2 → DR-5/DR-8; D3 → M6; D4 → M5; D5 → M2; D6 → M8/DR-4c; D7 → respective
milestones). **Dependencies.** none. **Commit.** `chore: delete stale design-round-tier1 branch`

---

# EPIC-X — CROSS-CUTTING CI / TEST / NATIVE-DISCIPLINE INFRA

## X1 — Native-first engineering discipline gates (§6.3)
**Labels:** `ci-infra`, `native` · **Milestone:** `CI/Test Infra`
**Context.** The native mandate's enforcement layer (§6.3). **Scope.** No-runtime-reflection rule;
build-time plugin discovery (`@ForvumExtension` + `META-INF/forvum/plugin.json` BuildStep); no dynamic
class loading outside the JVM-only drop-in path; a vetoed-dependency CI grep (`sun.misc.Unsafe`,
`net.sf.cglib`, runtime `javassist.util.proxy`); a custom Maven enforcer rule requiring
`@RegisterForReflection` on all DTOs. **Acceptance.** Each gate fails the PR on violation; folds into
M1/M3/M20 CI. **[NATIVE]** this IS the enforcement layer of REQ #1. **Dependencies.** M1.
**Commit.** `ci: enforce native-first engineering discipline gates`

## X2 — Concurrency-discipline gates (§3.8)
**Labels:** `ci-infra`, `native` · **Milestone:** `CI/Test Infra`
**Context.** §3.8 Concurrency Discipline. **Scope.** `@RunOnVirtualThread` placement rules; pinning
detection (`-Djdk.tracePinnedThreads=full` + CI grep + `pinning-allowlist.txt`); `synchronized`
forbidden in hot paths (CI grep over engine/channel main); a `thread.is_virtual` OTel attribute + a Dev
UI Concurrency card. **Acceptance.** Pinning/`synchronized` violations fail the PR; the Concurrency card
renders VT/PT + pin data. **Dependencies.** M5, M6 (the pinning posture finalized at M5).
**Commit.** `ci: enforce concurrency discipline (pinning, synchronized ban, VT placement)`

## X3 — Test pyramid, coverage/mutation/property gates (§10)
**Labels:** `ci-infra`, `native` · **Milestone:** `CI/Test Infra`
**Context.** §10 Testing Discipline. **Scope.** TDD process; test pyramid (`*Test` Surefire / `*IT`
Failsafe / e2e); JaCoCo 80% line + 75% branch gate; Pitest mutation ramp (core first, 50%→70%); JUnit 5 property-style tests
for `ModelRef.parse`, `AgentEvent` Jackson roundtrip, `CostBudget` invariants,
`PermissionScope.fromName`; flaky `@Tag("live")` quarantine + nightly; `FakeProvider` + `*Fixtures`.
**Acceptance.** **The single most important §10 edit: amend "Native-mode parity — selective" to
"MANDATORY"** (done in the spec by the §10 author; operationalized at M20). Coverage/mutation gates fail
the PR below threshold. **[NATIVE]** native parity becomes mandatory per REQ #1. **Dependencies.** M2,
M20. **Commit.** `test: establish test pyramid, coverage gates, and property tests`

## X4 — Per-channel first-token latency gates (§10)
**Labels:** `ci-infra` · **Milestone:** `CI/Test Infra`
**Context.** §10 performance gates. **Scope.** p95 first-token latency: TUI ≤ 200 ms, Web ≤ 300 ms,
Telegram ≤ 500 ms; baselined at M5/M6 with `FakeProvider`. **Acceptance.** A channel exceeding its p95
budget fails the gate (or the section is amended with evidence it is infeasible). **Dependencies.** M5,
M6, M15, M16, M17. **Commit.** `test: add per-channel first-token latency gates`

## X5 — Security-test layer + the missing §9 Security section (§10 + §9)
**Labels:** `ci-infra`, `security` · **Milestone:** `CI/Test Infra`
**Context.** §10 references "§9 once it lands" but **§9 does not yet exist** — it is created by DR-6a.
**Scope.** Track the §9-creation dependency (DR-6a) and the security negative-test suite (delegated to
TEST-SEC for the actual tests). This issue is the umbrella linking the §9 gap to the test layer.
**Acceptance.** §9 exists (via DR-6a); the security negative tests (via TEST-SEC) gate the build.
**Dependencies.** DR-6a, TEST-SEC. **Commit.** `test: add security negative-test layer`

## X6 — End-to-end verification suite (10 scenarios, dual-target)
**Labels:** `ci-infra`, `native` · **Milestone:** `CI/Test Infra`
**Context.** The 10 E2E scripts under `forvum-app/src/test/java/ai/forvum/e2e/`, landing milestone by
milestone, gating CI, run on **fast-jar AND native** on `linux-amd64` AND `macos-arm64`. **Scope.**
Scenario→milestone mapping: (1) cold install < 200 ms → M20; (2) first-run init (`forvum init`
scaffolds `~/.forvum/`) → M4 + an `init` command (no explicit milestone — fold into M1/M4 acceptance);
(3) TUI golden path → M15 (+ M5); (4) per-agent LLM selection + fallback → M8 + M9 + M10; (5) sub-agent
spawn → M7 + M18; (6) web channel → M16; (7) Telegram allowed/denied user → M17; (8) cron run → M19;
(9) hot reload without restart → M4 + M7; (10) CAPR dashboard (`/q/dashboard/capr`, ≥ 5 capr_events) →
M18 + the §3.6 CAPR endpoint (no explicit milestone — fold into M18 acceptance). **Acceptance.** All 10
scenarios green on fast-jar AND native on both platforms; the suite gates CI. **[NATIVE]** explicitly
dual-target (aligned with REQ #1). **Dependencies.** M20 (native gating), the per-scenario milestones.
**Commit.** `test: add end-to-end verification suite (10 scenarios)`

## X7 — Phase-1 milestone gap: shell tool / skills surface / mcp-bridge / OTel baseline / init / CAPR endpoint
**Labels:** `ci-infra`, `engine` · **Milestone:** `CI/Test Infra`
**Context.** §2.4 declares `forvum-tools-shell`, `forvum-tools-mcp-bridge`, the `SkillInvokerTool`
skills surface, and §3.6 OTel wiring, and the E2E mapping references `forvum init` and the
`/q/dashboard/capr` endpoint — **none has a dedicated M1–M20 milestone.** This is a real roadmap gap.
**Scope.** DECISION issue: fold the shell tool + skills surface + OTel baseline + mcp-bridge (flagged
off, Risk #9) + `forvum init` + the CAPR endpoint into M13/M18/M4 acceptance, OR add micro-milestones.
Recommended: shell tool + skills + OTel baseline + mcp-bridge → M13/M18 acceptance; `forvum init` → M1/M4
acceptance; the CAPR endpoint → M18 acceptance. **Acceptance.** Each gap item has a documented owning
milestone (or a micro-milestone issue) and is not lost. **Dependencies.** M13, M18, M4 (placement
targets). **Commit.** `chore: resolve Phase-1 milestone gaps (shell/skills/mcp-bridge/OTel/init/CAPR)`

## X8 — Critical-Files → milestone cross-link guarantee
**Labels:** `ci-infra` · **Milestone:** `CI/Test Infra`
**Context.** The 10 Critical Files must compile and their milestones pass; an orientation guarantee, not
a behavior change. **Scope.** Cross-link each Critical File to its owning milestone: `AgentId`→M2,
`ChannelProvider`→M3, `AgentContext`→M6, `AgentRegistry`→M7, `FallbackChatModel`→M8, `SupervisorGraph`→
M18, `ConfigLoader`→M4, `V1__baseline.sql`→M5, `application.properties`→M5/M20, `ci.yml`→M20.
**Acceptance.** Every Critical File is mapped to a milestone in the issue tracker; each compiles when its
milestone closes. **Dependencies.** the listed milestones. **Commit.** `docs: cross-link Critical Files
to owning milestones`

---

## Notes for the maintainer
- **Stale example model ids** in §7.1 Verify blocks (`claude-opus-4-7` at M10, `gpt-4.1-mini` at M11,
  `gemini-1.5-flash` at M12) are example `@Tag("live")` model ids; each milestone issue flags
  "update to a current model id at implementation time" (OpenClaw's current Anthropic default is
  `claude-opus-4.6`).
- **Predating `*TBD*` markers** (§4.3.5.3 FallbackChain, §4.3.6 MemoryPolicy) are intentionally left in
  the spec until DR-4c / DR-5 close; they are NOT new placeholders.
- **Native carve-outs** are exhaustively: M4 behavioral assertion (must still compile); per-provider
  native failure (Vertex remedy first, then JVM-only + upstream issue); the `~/.forvum/plugins/` drop-in
  path (JVM-fast-jar-only BY DESIGN, not a carve-out); the Dev UI (dev-mode only, P3-6). Everything else
  is native-mandatory.
</content>
</invoke>
