# Tier-D — Tools Implementation Plan (M13 · M14)

> For agentic workers: REQUIRED SUB-SKILLS — run `quarkus/skills` + `context7` (LangChain4j
> `ToolSpecification` / `ToolExecutor`, for the engine-side bridge) BEFORE writing any code or test;
> never answer a Quarkus question from model memory; run tests via the Dev MCP / Surefire per
> CLAUDE.md §4/§7. This plan was produced by a 15-agent adversarially-verified grouping analysis
> (7 ground-truth readers + 7 refuters + 1 synthesizer) and hand-verified against the repo.

**Goal.** Land the tool-execution substrate. **M13** builds the engine-internal `ToolRegistry` /
`ToolFilter` / `ToolExecutor` + the `ToolProvider` SDK SPI prelude + permission enforcement and audit;
**M14** ships `forvum-tools-filesystem`, the first first-party tool module, implementing that SPI.

**Architecture.** M13 = `forvum-engine` + `forvum-sdk` only (NO new Maven module). M14 = the first
Layer-3 *tool* module (mirrors the M9 provider template, **minus** langchain4j). The window is a
**sequential chain**, not parallel siblings: M14's `FilesystemToolProvider` cannot *compile* until
M13's SPI prelude merges.

**Tech stack.** Java 25 · Quarkus 3.33.x · `forvum-bom`-governed · `java.nio` for filesystem ·
existing forvum-core types (`ToolSpec`, `PermissionScope`, `InvocationStatus`) · existing
`tool_invocations` V1 table.

**Authoritative baseline.** Branch the M13/M14 work off **`origin/main` (`e18dc3b`, M12 merge #98)** —
verified HEAD. (When this plan was written, the *local* `main` ref lagged at M11 and would have
silently omitted the M12 Google provider + `HttpClientFactorySelector`; always confirm against
`origin/main`.)

**Issue map.** M13 → `#18`, M14 → `#19` (`docs/ISSUES.md` §issue-map; `Mn` closes `#(n+5)`). No ad-hoc
issues. Re-confirm the numbers are still live at PR time (`gh issue list --state all`) — the issue
script renumbers on re-run.

---

## ⚠ AUTHORITATIVE CORRECTIONS (read first — these override the plan body and the prior docs)

These supersede `docs/ISSUES.md` M13/M14 and `docs/ULTRAPLAN.md` §7.1 M13/M14 where they conflict, and
they incorporate the adversarial verdicts and the maintainer sign-off (2026-06-05). Where this banner
conflicts with the plan body or the source docs, **the banner wins**.

- **AC-D1 — `PermissionScope` is ALREADY DONE in `forvum-core` (M2). M13 creates ZERO `PermissionScope`
  files; M14 adds ZERO enum constants.** The enum is at
  `forvum-core/src/main/java/ai/forvum/core/PermissionScope.java` with exactly `FS_READ, FS_WRITE` +
  `fromName(...)` (hand-verified). `docs/ISSUES.md` (M13 Files) and `docs/ULTRAPLAN.md:1330` stale-list
  `PermissionScope.java (enum)` under `forvum-engine/.../tools/`; this is contradicted within the same
  doc by the authoritative §4.3.4 ("a closed Java enum in `forvum-core`") and the pillar map
  (`ULTRAPLAN:123`). M13 **consumes** the core enum; do NOT scaffold `engine/tools/PermissionScope.java`
  (it would duplicate the Layer-0 enum). `FsListTool` uses `FS_READ` — there is no `FS_LIST` scope.

- **AC-D2 — The `ToolProvider` SDK SPI is a structural stub (`extensionId()` only); M13 MUST land the
  contribution method as its prelude — the M7 pattern.** `forvum-sdk/.../ToolProvider.java` declares only
  `String extensionId();`, and its own Javadoc pre-announces "the contribution/execution methods
  (carrying `ai.forvum.core.ToolSpec`) are added by the tool milestones (M13-M14)";
  `AbstractToolProvider` is the empty non-sealed base (both hand-verified). Mirror
  `ModelProvider.resolve(ModelRef)` (the M7 prelude, `bbc1df2`): M13's PR adds the method and lands a
  `ToolProviderContractTest` analogous to `ModelProviderResolveTest`; M14's `FilesystemToolProvider` is
  the first IMPLEMENTOR. **DECIDED (2026-06-05): the prelude method is `List<ToolSpec> tools()` —
  contribution-only, forvum-core types ONLY (no langchain4j `ToolSpecification`).** This keeps
  `forvum-sdk` Quarkus-free AND needs NO change to `forvum-sdk/pom.xml` (unlike M7, which had to add
  `langchain4j-core` for `ChatModel`). Execution/dispatch lives in the engine `ToolExecutor` (and the
  M18 `tool_loop`), NOT on the SPI.
  - **⤷ REVISED at M18 (Option A, maintainer-approved 2026-06-06):** execution DOES land on the SPI after
    all — `ToolProvider` gained `String invoke(String toolName, Map<String,Object> arguments)` so the
    provider self-dispatches by name (zero reflection; native-clean), still gated + audited by the engine
    `ToolExecutor`/`ToolCallBridge`. The SDK stays Quarkus-free and langchain4j-free (only `java.util.Map`
    added). This supersedes the "NOT on the SPI" clause above; the alternative (langchain4j `@Tool`
    reflection) was rejected for its non-framework-managed `Method.invoke` native cost. See
    `docs/plans/m18-supervisor-graph.md` §R2.

- **AC-D3 — `tool_invocations` is ALREADY a Flyway-migrated table (M5). M13 adds ZERO migrations.**
  `forvum-engine/src/main/resources/db/migration/V1__baseline.sql` creates `tool_invocations`
  (`status TEXT NOT NULL  -- ok | error | denied`) + `idx_tool_session`/`idx_tool_agent`; it is the
  ONLY migration on disk (hand-verified). `ToolInvocationEntity.java` already exists. The next free slot
  **V2 is pre-reserved** for the `turn_id` work (`ULTRAPLAN:375`) — do NOT create a V2 here. M13's
  persistence work is the **write seam only**: a `ToolInvocation` DTO + `ToolInvocationRecorder`
  interface + `PanacheToolInvocationRecorder`, mirroring the `ProviderCall` triad verbatim.

- **AC-D4 — `PermissionDeniedException` does NOT exist yet — M13 authors it.** Grep finds it only in
  prose (Javadoc + `ULTRAPLAN` M13 Verify). **DECIDED (2026-06-05): it lives in
  `forvum-engine/.../tools/`** (the executor enforces in-engine; no Layer-3 plugin needs to throw it, so
  it stays out of `forvum-core`).

- **AC-D5 — The window is SEQUENTIAL (a HARD source-compile edge), NOT "exactly like the provider
  fleet."** M9–M12 had ZERO hard source edges (each compiled against `forvum-sdk` alone because
  `resolve()` pre-existed from M7); their only inter-milestone edge was a SOFT live-fallback test edge
  (M10→M9, `@EnabledIf`-gated). M13→M14 is the opposite: M14's `FilesystemToolProvider` `@Override`s the
  SPI method M13 adds and **cannot compile** until M13 merges. `@EnabledIf`/class-presence gating only
  defers test EXECUTION — it CANNOT bridge a compile dependency. Decouple by agreeing the SPI signature
  up front (done — AC-D2), landing it as M13's FIRST commit, and merging M13 before M14. `ISSUES.md`
  (M14 deps `M3, M13`) is authoritative; `ULTRAPLAN:1337` "Deps: none beyond forvum-sdk" is inaccurate.

- **AC-D6 — `AgentToolBelt` is an EXISTING engine stub to UPGRADE, not a new file (the M13 Files lists
  omit it).** `forvum-engine/.../agent/AgentToolBelt.java` is `@AgentScoped`, injects `AgentRegistry`,
  exposes only `globs()`, and its Javadoc says "M13's `ToolRegistry` intersects them against the global
  tool set to produce the filtered `List<ToolSpec>`" (hand-verified). M13 injects the new `ToolRegistry`
  and adds a cached filtered `List<ToolSpec> tools()` beside `globs()`. `AgentRegistryTest` already locks
  the glob-subset-narrowing contract — those assertions must keep passing. Leave `AgentToolBelt` in
  `engine/agent/` (minimal change).

- **AC-D7 — Do NOT wire tools into `Agent.respond()` in M13 — that is M18 scope.** `Agent.respond()` is a
  deliberate single-shot turn with NO `.toolSpecifications(...)` and NO tool loop; its Javadoc says
  "routing, the tool loop, and sub-agent fan-out arrive with the LangGraph4j `SupervisorGraph` (M18)"
  (hand-verified). M13's `ToolExecutor` is unit/integration-testable standalone (seed a synthetic denied
  call → `PermissionDeniedException` + `tool_invocations.status='denied'`); the model-request wiring is
  M18's `tool_loop` node.

- **AC-D8 — X7 (shell tool / skills surface / mcp-bridge flagged-off / OTel baseline) is OUT of M13/M14.**
  **DECIDED (2026-06-05): scope X7 OUT; it is decided in its own issue (#73).** The "fold X7 in here?"
  note in M13's `ISSUES.md` Context is a red herring for the committed deliverable — keep the milestones
  minimal (Simplicity-First).

- **AC-D9 — `§9 Security` / `DR-6a` does NOT exist in `ULTRAPLAN` (headings jump 8→10).** M14's
  path-traversal acceptance leans on "the WorkspaceRoot contract defined by DR-6a" (unwritten issue #59).
  **DECIDED (2026-06-05): M14 ships a self-contained minimal `WorkspaceRoot`** (`Path.normalize()` +
  `startsWith(root)` containment) + a path-traversal test, documenting that the full DR-6a
  OutputFilter/threat-model + TEST-SEC suite is deferred (the M9–M12 precedent of shipping without all
  design-contract issues). No-config default: NO tool surface / warn+no-op, never crash.

- **AC-D10 — `tool_invocations.status` has NO SQL `CHECK` constraint.** `V1__baseline.sql` is a bare
  `TEXT NOT NULL` column with an inline `-- ok | error | denied` comment (hand-verified); the
  `{ok,error,denied}` domain is enforced in Java by `InvocationStatus` (`DENIED.dbValue() == "denied"`).
  (The `InvocationStatus` Javadoc's phrase "V1 CHECK constraint" is itself slightly inaccurate — a
  pre-existing doc nuance, not M13's concern.) M13 maps the denied path via
  `InvocationStatus.DENIED.dbValue()`.

---

## 0. Ground-truth (verified on `origin/main`, post-M12-merge `e18dc3b`)

Bullets marked **(hand-verified)** were re-confirmed by direct file reads during planning; the rest come
from the 7-agent ground-truth sweep with cited file evidence.

- **GT-D1 — The reactor is 9 modules** (`pom.xml`): `forvum-bom, forvum-core, forvum-sdk, forvum-engine,
  forvum-provider-{ollama,anthropic,openai,google}, forvum-app`. No `forvum-tools-*` exists. M14 is
  greenfield; M13 adds no module.
- **GT-D2 — `ToolProvider` SPI is a stub** (`extensionId()` only); `AbstractToolProvider` empty.
  **(hand-verified.)** See AC-D2.
- **GT-D3 — `ToolSpec` exists in forvum-core, langchain4j-free.**
  `record ToolSpec(String name, String description, PermissionScope requiredScope, String
  parametersJsonSchema)` with canonical-constructor validation; each `ToolSpec` **already embeds its
  `requiredScope`** (no separate name→scope table); Javadoc: "adaptation to LangChain4j
  `ToolSpecification` happens in the SDK/engine." **(hand-verified.)**
- **GT-D4 — `PermissionScope` + `FS_READ`/`FS_WRITE` + `fromName` exist in forvum-core (M2).**
  **(hand-verified.)** See AC-D1.
- **GT-D5 — `tool_invocations` table + `ToolInvocationEntity` + `InvocationStatus` all exist.**
  `V1__baseline.sql`; `ToolInvocationEntity.java` (full `@Entity` mapping); `InvocationStatus`
  (`OK/ERROR/DENIED` + `dbValue()`/`fromDbValue()`). `SchemaSmokeIT` already round-trips a
  `tool_invocations` row. **(hand-verified: table, entity, single migration.)** See AC-D3.
- **GT-D6 — The `ProviderCall` recorder triad is the verbatim template.** DTO
  `engine/model/ProviderCall.java` (`@RegisterForReflection public record`); interface
  `engine/model/ProviderCallRecorder.java` (`void record(ProviderCall)`); impl
  `engine/persistence/PanacheProviderCallRecorder.java` (`@Singleton`, `@Override @Transactional`,
  DTO→`ProviderCallEntity.persist()`). The **entity carries NO `@RegisterForReflection`** (Panache
  handles it); only the DTO does. **(hand-verified.)**
- **GT-D7 — `AgentToolBelt` is the integration seam** (`globs()` only; Javadoc points to M13's
  `ToolRegistry` intersection). **(hand-verified.)** See AC-D6.
- **GT-D8 — `Agent.respond()` is single-shot, no tool loop.** **(hand-verified.)** See AC-D7.
- **GT-D9 — The concurrency template is `AgentRegistry`** (`ConcurrentMap` + `putIfAbsent`-and-throw,
  blocking IO loaded OUTSIDE the compute lock; `spawn` rejects self/collision). ZERO `synchronized` in
  `forvum-engine/src/main/java` (only Javadoc asserting its absence). `OllamaModelProvider` is the
  secondary `computeIfAbsent` per-id-cache template. M13's registries copy these; `synchronized` would
  trip the CI grep (CLAUDE.md §11/§12).
- **GT-D10 — `CoreReflectionRegistration` is the single Layer-0 native-reflection holder; `ToolSpec.class`
  is ALREADY registered.** `PermissionScope` is NOT in the list. Javadoc: "later milestones append their
  Layer-0 targets here rather than creating a second holder."
- **GT-D11 — Engine package layout is `agent/ config/ context/ model/ persistence/ routing/`; no `tools/`
  yet.** M13's `engine/tools/` is a new sibling package.
- **GT-D12 — The ratified Layer-3 recipe is `forvum-provider-ollama` (M9).** Enforcer allowlists EXACTLY
  `forvum-sdk` + `forvum-core` (`searchTransitive=true`, excludes `ai.forvum:*`); headless
  quarkus-maven-plugin (`generate-code` + `generate-code-tests`, NO `build` goal); `beans.xml`
  (`annotated`); `plugin.json`; surefire includes `**/*Test.java` + `**/*IT.java`. M14 copies this minus
  the langchain4j extension.
- **GT-D13 — The three append-only shared files are root `pom.xml` `<modules>`, `forvum-bom`
  `dependencyManagement`, `forvum-app` `<dependencies>`** (each M9–M12 provider appended to all three).
- **GT-D14 — `forvum-app` is the sole native-image site + no-config smoke.** Native profile + Failsafe
  `*IT` (`ForvumApplicationIT`); `ForvumApplication` (command-mode banner, exits 0, boots with no
  `~/.forvum/`) + `HttpClientFactorySelector`. A filesystem tool has NO HTTP client, so it never touches
  the multi-factory machinery — but must boot gracefully with no `~/.forvum/` (CLAUDE.md §14 [M4]).
- **GT-D15 — `forvum-app/src/test/java/ai/forvum/security/` does NOT exist yet** (absent on disk and
  `origin/main`); the `e2e/` dir exists with 5 of 10 scripts. M13 creates the `security/` dir with the
  first negative test.

---

## 1. Pre-branch blockers (resolve BEFORE branching the window)

| # | Blocker | Resolution | Gate |
|---|---|---|---|
| **B-1** | `ToolProvider` SPI shape (the prelude method) | **DECIDED:** `List<ToolSpec> tools()`, forvum-core types only (AC-D2). Land it as M13's first commit. | Before any M13/M14 code (M14 `@Override`s it). |
| **B-2** | `PermissionDeniedException` does not exist + its home | **DECIDED:** `forvum-engine/.../tools/` (AC-D4). M13 authors it. | Before writing `ToolExecutor`. |
| **B-3** | `WorkspaceRoot` contract (DR-6a / §9) unwritten | **DECIDED:** ship a self-contained minimal `WorkspaceRoot` in M14 (`normalize`+`startsWith`); document DR-6a deferral (AC-D9). | Before M14's path-traversal test. |
| **B-4** | X7 (shell/skills/mcp-bridge/OTel) folding | **DECIDED:** scope OUT of M13/M14; defer to issue #73 (AC-D8). | Architectural sign-off (done). |
| **B-5** | Baseline ref | Branch off `origin/main` (`e18dc3b`), not a possibly-stale local `main` (AC-D5/baseline). | Before branching. |
| **B-6** | Three append-only shared poms — M14 only | M14 appends `forvum-tools-filesystem` to root `<modules>`, `forvum-bom` `dependencyManagement`, `forvum-app` `<dependencies>` (GT-D13). Rebase onto merged M13 first. | M14 merge gate. |

All architectural blockers (B-1..B-4) were signed off by the maintainer on 2026-06-05 (§2).

---

## 2. Architectural sign-off items (CLAUDE.md §8) — STATUS

Items touching an SPI / a contract need design sign-off before the M13 PR. **All four decided
2026-06-05:**

- **SI-1 — `ToolProvider` SPI prelude signature → DECIDED `List<ToolSpec> tools()`** (contribution-only,
  forvum-core types only; no `execute`-style method on the SPI now — dispatch lives in the engine
  `ToolExecutor` and M18). (AC-D2.)
- **SI-3 — `PermissionDeniedException` location → DECIDED `forvum-engine/.../tools/`.** (AC-D4.)
- **SI-4 — M14 `WorkspaceRoot` → DECIDED self-contained minimal + documented DR-6a deferral.** (AC-D9.)
- **SI-5 — X7 scope → DECIDED OUT (issue #73).** (AC-D8.)

Remaining (impl-time, not blocking sign-off):

- **SI-2 — `ToolExecutor` enforcement + audit contract.** Confirm the executor surface (in
  `forvum-engine`, standalone-testable, NOT wired into `Agent.respond()` — AC-D7) and that denial audits
  via the recorder → `tool_invocations.status = InvocationStatus.DENIED.dbValue()`.
- **SI-6 — Reflection-holder policy.** Append `PermissionScope.class` to `CoreReflectionRegistration`
  ONLY if a native JSON path reflects on the enum directly; otherwise rely on transitive reachability via
  the already-registered `ToolSpec` record (GT-D10). Decide at the M13 native-gate step.

---

## 3. Window shape · merge order · shared files · gating edges

- **Roles.** **M13 is the anchor** (engine `tools/` package + SDK SPI prelude + recorder seam + first
  security test); **M14 is the dependent sibling** (first Layer-3 tool module). Unlike the Provider
  Fleet's four parallel peers, this is a **2-milestone chain**.
- **The single gating edge — HARD source-compile (AC-D5).** M14's `FilesystemToolProvider` `@Override`s
  the `ToolProvider.tools()` method that M13's PR adds; it **cannot compile** until M13 merges.
  `@EnabledIf`/class-presence gating does NOT help — it defers test execution, not compilation.
- **Decoupling lever.** Agree the SPI signature up front (done, SI-1), land it as M13's **first commit**,
  merge M13, then M14's CI goes green. M14 can develop in parallel (module scaffold, `WorkspaceRoot`,
  `Fs{Read,Write,List}` logic, tests) but its provider only compiles post-M13.
- **Merge order: M13 first, M14 second** (forced; `ISSUES.md` M14 deps `M3, M13`).
- **Append-only shared files (M14 ONLY; M13 touches NONE):** root `pom.xml` `<modules>` (add
  `forvum-tools-filesystem` after the providers); `forvum-bom/pom.xml` `dependencyManagement` (add the
  module with `<version>${project.version}</version>`); `forvum-app/pom.xml` `<dependencies>` (add
  `ai.forvum:forvum-tools-filesystem`). **M13's work is entirely inside existing `forvum-engine` +
  `forvum-sdk`** (verdict-confirmed) — no root/app/bom pom edit.
- **Native-compile rule.** M14 enters a native image only once `forvum-app` depends on it (CLAUDE.md §14
  [M4]) — hence the app-wiring is part of M14, in the same milestone.

```
(no prelude module — branch off origin/main e18dc3b)
        │
        ▼
   M13 (#18)  ANCHOR                                  M14 (#19)  DEPENDENT
   forvum-sdk ToolProvider.tools()  ◄───── HARD ─────  forvum-tools-filesystem
   + engine/tools/{Registry,Filter,         compile     FilesystemToolProvider @Override tools()
     Executor,PermissionDeniedException}     edge        + Fs{Read,Write,List}Tool + WorkspaceRoot
   + ToolInvocation recorder triad                       + app/bom/root pom wiring
   + AgentToolBelt upgrade                               + @TempDir IT + PathTraversal security test
   + PermissionScopeMismatch security test               + native smoke
        │  merges FIRST ─────────────────────────────────► M14 compiles + CI green
```

---

## 4. Canonical tool-module recipe (M14) — delta vs the ratified provider recipe

M14 is the FIRST tool module; it ratifies the tool-module recipe (a future second tool module copies
it). It is the `forvum-provider-ollama` Layer-3 skeleton with these substitutions/drops:

- **`pom.xml`** — copy `forvum-provider-ollama/pom.xml`, then:
  - **DROP** the langchain4j extension dep (`quarkus-langchain4j-ollama`) — a filesystem tool uses
    `java.nio` only, no AI library (AC-D2; `ToolProvider` has no langchain4j coupling).
  - **KEEP** the enforcer block UNCHANGED — it already allowlists EXACTLY `forvum-sdk` + `forvum-core`
    and excludes `ai.forvum:*`. `ToolSpec`/`PermissionScope` are forvum-core, so no enforcer edit
    (Option-A allowlist, same as M9–M12).
  - **KEEP** `quarkus-arc` (CDI discovery) + `quarkus-junit` (test scope, platform-managed). The prod
    surface is `{forvum-sdk, quarkus-arc}`; test adds `quarkus-junit`.
  - **KEEP** the headless quarkus-maven-plugin (`generate-code` + `generate-code-tests`, NO `build`
    goal) + surefire `**/*Test.java` + `**/*IT.java` includes + `dependencyManagement` importing
    `forvum-bom`.
- **`beans.xml`** — copy verbatim (`bean-discovery-mode="annotated"`).
- **`plugin.json`** — copy shape; set `extension_id` → `"filesystem"`, `providers[0].type` → `"tool"`,
  `class` → `ai.forvum.tools.filesystem.FilesystemToolProvider`.
- **`FilesystemToolProvider.java`** — implements `ToolProvider` (the M13 SPI); `tools()` returns
  `List<ToolSpec>` for the three tools; NO `resolve`, NO langchain4j.
- **App wiring** — the three append-only shared files (§3 / GT-D13).

**Net delta vs a provider module: drop the langchain4j extension; everything else is identical.**

---

## 5. M13 — `ToolRegistry`, filtering, permission enforcement (ANCHOR) · Closes #18

**Files** (create unless noted):

- `forvum-sdk/.../ToolProvider.java` — **EDIT:** add `List<ToolSpec> tools()` (forvum-core type; NO pom
  change — AC-D2). `AbstractToolProvider` stays empty.
- `forvum-sdk/src/test/.../ToolProviderContractTest.java` — anonymous `AbstractToolProvider` stub test,
  analogous to `ModelProviderResolveTest`.
- `forvum-engine/.../tools/ToolRegistry.java` — `@ApplicationScoped`; `ConcurrentMap<String,ToolSpec>`
  populated at startup from CDI-discovered `ToolProvider`s; `putIfAbsent`-and-throw on duplicate tool
  name (GT-D9). NO `synchronized`; any blocking load OUTSIDE the compute lock.
- `forvum-engine/.../tools/ToolFilter.java` — glob matching: intersect a persona's `allowedTools` globs
  against the global tool set → `List<ToolSpec>`.
- `forvum-engine/.../tools/ToolExecutor.java` — reads `spec.requiredScope()`; throws
  `PermissionDeniedException` on scope mismatch; records the attempt via the recorder seam
  (`denied`/`ok`/`error`). Standalone-testable; does NOT touch `Agent.respond()` (AC-D7).
- `forvum-engine/.../tools/PermissionDeniedException.java` — **NEW** (AC-D4).
- `forvum-engine/.../agent/AgentToolBelt.java` — **EDIT:** inject `ToolRegistry`; add cached
  `List<ToolSpec> tools()` beside `globs()`; keep `AgentRegistryTest` subset assertions green (AC-D6).
- `forvum-engine/.../model/ToolInvocation.java` — **NEW** DTO `record` with its OWN
  `@RegisterForReflection` (Layer-2, copies `ProviderCall`).
- `forvum-engine/.../model/ToolInvocationRecorder.java` — **NEW** interface `void record(ToolInvocation)`.
- `forvum-engine/.../persistence/PanacheToolInvocationRecorder.java` — **NEW** `@Singleton`,
  `@Override @Transactional record(...)` mapping DTO → `ToolInvocationEntity.persist()`; status via
  `InvocationStatus.DENIED.dbValue()` (AC-D3/AC-D10/GT-D6).
- `forvum-app/src/test/java/ai/forvum/security/PermissionScopeMismatchTest.java` — **NEW** (creates the
  `security/` dir, GT-D15): PermissionScope mismatch → denied + audited.
- **NO migration** (AC-D3). **NO `PermissionScope` file** (AC-D1). `CoreReflectionRegistration`: do NOT
  re-append `ToolSpec` (already registered); append `PermissionScope.class` ONLY per SI-6.

### Tasks (Red → Green → Refactor)

- [ ] **Step 0 — Sub-skills.** `quarkus/skills` + `context7` (LangChain4j `ToolSpecification`/
  `ToolExecutor`) for the engine-side bridge knowledge. Confirm the `ToolProvider` SPI signature (SI-1,
  decided).
- [ ] **Step 1 — SPI prelude commit (FIRST).** Add `List<ToolSpec> tools()` to `ToolProvider`; add
  `ToolProviderContractTest`. Commit: `feat(sdk): add ToolProvider.tools() contribution method (M13 prelude)`.
- [ ] **Step 2 — Failing tests.** `ToolRegistryTest` (register `a.read`/`a.write`, duplicate-name
  rejection), `ToolFilterTest` (glob intersection), `ToolExecutorTest` (allowed → ok; `a.write` from
  `allowedTools:[a.read]` → `PermissionDeniedException`), recorder test (denied row). Run → verify FAIL.
- [ ] **Step 3 — Implement** `ToolRegistry`/`ToolFilter`/`ToolExecutor`/`PermissionDeniedException` + the
  recorder triad; upgrade `AgentToolBelt`.
- [ ] **Step 4 — Verify PASS.** `forvum-engine` tests via **Surefire** (headless lib — `-B
  -Dstyle.color=never`, read `target/surefire-reports/*.txt`); the `forvum-app` security test via the
  Dev MCP path. Keep `AgentRegistryTest` green.
- [ ] **Step 5 — Security test** `PermissionScopeMismatchTest` under `forvum-app/.../security/`.
- [ ] **Step 6 — Native gate.** Build `forvum-app -Pnative`; confirm glob/permission paths native-clean +
  no-config boot still exits 0. If a native JSON path reflects on the enum, append `PermissionScope.class`
  to `CoreReflectionRegistration` (SI-6).
- [ ] **Step 7 — `/code-review` (high or `ultra`) → PR.** Commit: `feat(engine): add ToolRegistry with
  glob-based filtering and permission scopes`. **Closes #18.**

**Verify (the contract the M13 PR satisfies):** register two synthetic tools (`a.read`, `a.write`), seed
an agent with `allowedTools: ["a.read"]`; a call to `a.write` is refused with `PermissionDeniedException`
and logged in `tool_invocations` with `status = 'denied'` — against the EXISTING V1 schema (no
migration).

---

## 6. M14 — `forvum-tools-filesystem` (first tool module) · Closes #19

**Delta from §4 recipe:** new module `forvum-tools-filesystem/` (Layer-3, copies the §4 tool-module
recipe: enforcer allowlist `forvum-sdk` + `forvum-core`, headless pom, `beans.xml`, `plugin.json`
`extension_id:"filesystem"`).

**Files:**

- `forvum-tools-filesystem/pom.xml`, `src/main/resources/META-INF/beans.xml`,
  `src/main/resources/META-INF/forvum/plugin.json`.
- `.../FilesystemToolProvider.java` — implements `ToolProvider`; `tools()` returns three `ToolSpec`s:
  `fs.read`/`FS_READ`, `fs.write`/`FS_WRITE`, `fs.list`/`FS_READ` (no `FS_LIST` — AC-D1).
- `.../FsReadTool.java` (`FS_READ`), `.../FsWriteTool.java` (`FS_WRITE`), `.../FsListTool.java`
  (`FS_READ`).
- `.../WorkspaceRoot.java` — **self-contained minimal** confinement: `Path.normalize()` +
  `startsWith(root)` containment; default-safe under no-config boot (warn + no-op, never crash —
  GT-D14). Documents the DR-6a deferral (AC-D9/SI-4).
- Three append-only shared poms (root `<modules>`, `forvum-bom` `dependencyManagement`, `forvum-app`
  `<dependencies>` — GT-D13/B-6).
- `forvum-tools-filesystem/src/test/.../FilesystemToolIT.java` — `@TempDir` read/write/list round-trip;
  write outside `WorkspaceRoot` denied.
- `forvum-app/src/test/java/ai/forvum/security/PathTraversalDeniedTest.java` — the SECOND security test
  (path traversal / workspace-escape write → denied).

### Tasks (Red → Green → Refactor)

- [ ] **Step 0 — Sub-skills + `quarkus/create` harvest.** Confirm the platform/extension wiring; copy the
  §4 recipe. Verify M13's `ToolProvider.tools()` is on `origin/main` (else `FilesystemToolProvider` won't
  compile — AC-D5).
- [ ] **Step 1 — Scaffold module** (pom/beans.xml/plugin.json) — can start in parallel with M13.
- [ ] **Step 2 — Failing IT.** `FilesystemToolIT` (@TempDir round-trip; escape-write denied) +
  `WorkspaceRoot` unit test. Run → FAIL.
- [ ] **Step 3 — Implement** `WorkspaceRoot` + `Fs{Read,Write,List}Tool` + `FilesystemToolProvider`.
- [ ] **Step 4 — Verify PASS** (Surefire, headless tool lib).
- [ ] **Step 5 — Reactor wiring.** Append to the three shared poms. Commit:
  `chore(reactor): wire filesystem tool module into forvum-app`.
- [ ] **Step 6 — Security test + native smoke.** `PathTraversalDeniedTest` under
  `forvum-app/.../security/`; build `forvum-app -Pnative` — confirm native file I/O native-clean +
  no-config boot exits 0 (an unconfigured workspace root warns + no-ops).
- [ ] **Step 7 — `/code-review` → PR.** Commit: `feat(tools-fs): add filesystem read/write/list tools
  with FS permission scope`. **Closes #19.**

**Verify:** integration test against a `@TempDir`; read/write/list round-trip asserted; a write outside
the configured workspace root is denied. (The denied-write test is SELF-CONTAINED in the tool module —
the tool's own `WorkspaceRoot` containment, NOT M13's `ToolExecutor` at runtime — AC-D5.)

---

## 7. Test taxonomy & per-PR gates

| Layer | What | Runner | In per-PR gate? |
|---|---|---|---|
| Unit / contract `*Test` | `ToolProviderContractTest` (SDK); `ToolRegistryTest` / `ToolFilterTest` / `ToolExecutorTest`; recorder test; `WorkspaceRoot` unit | Surefire `-B -Dstyle.color=never` (engine/sdk — headless-lib exception, CLAUDE.md §4); read `*.txt` | ✅ always-green |
| Enforcer | M14 module deps ⊆ `{forvum-sdk, forvum-core}` (no engine, no langchain4j) | `./mvnw -DskipTests validate` (runs at `validate`) | ✅ |
| Integration `*IT` | `FilesystemToolIT` (@TempDir round-trip + escape-write denied) | `@QuarkusTest`/Failsafe; real NIO via `@TempDir` | ✅ |
| Security (negative) | M13 `PermissionScopeMismatchTest` (denied + audited); M14 `PathTraversalDeniedTest` | Surefire in `forvum-app/.../security/` (new dir) | ✅ |
| Native smoke (Risk #5) | M13 glob/permission native-clean; M14 file I/O native-clean; no-config boot exits 0 | `forvum-app -Pnative` Failsafe (`ForvumApplicationIT`) | ✅ MANDATORY |
| Live `*-LiveTest @Tag("live")` | none (no provider/network in tools) | nightly | ❌ n/a |

Concurrency discipline: `Thread pinned` grep clean; virtual-threads-first; ZERO `synchronized` in
engine/tool hot paths (GT-D9; CI grep). Coverage gates: JaCoCo 80% line (parent) / 75% branch.

---

## 8. Open questions / residual risks for the maintainer

The four architectural decisions are signed off (§2). Residual, non-blocking items:

1. **Reflection (SI-6).** Accept transitive `PermissionScope` reachability via the registered `ToolSpec`,
   or eagerly append `PermissionScope.class` to `CoreReflectionRegistration` for safety? (One-line,
   in-pattern; decide at the M13 native-gate step.)
2. **Tool-name convention.** Confirm the dotted names (`fs.read`/`fs.write`/`fs.list`) and that personas
   reference them via `allowedTools` globs (e.g. `fs.*`). `a.read`/`a.write` in the M13 Verify are
   synthetic test tools.
3. **Workspace-root config.** Where is the workspace root configured (a `~/.forvum/` key) and what is the
   no-config default? (Recommendation: no tool surface / warn + no-op, never crash — GT-D14.)
4. **Issue numbers.** Re-confirm M13 → #18 / M14 → #19 are still live at PR time (`gh issue list --state
   all`); do NOT confuse ISSUE numbers (#18/#19) with PR numbers (the fleet's were #95–#98).

---

## 9. Branch / PR & merge summary

PRs target `main`; each carries `Closes #(n+5)`; Conventional Commits + a
`Co-Authored-By: Claude Opus 4.8 (1M context)` trailer; English-only; `git worktree` isolation (do not
switch branches in place). **No commit/push/issue without explicit maintainer authorization.**

| Branch | Milestone | Closes | Order |
|---|---|---|---|
| `feat/m13-tool-registry` | M13 ToolRegistry + filtering + `ToolProvider.tools()` prelude (anchor) | #18 | **merge first** |
| `feat/m14-tools-filesystem` | M14 filesystem tool module (first tool plugin) | #19 | after M13; rebase onto merged M13, then wire the 3 poms |

Per-PR gates (both): native compile via `forvum-app` (mandatory); native smoke boots with **no
`~/.forvum/`** (graceful warn + no-op); engine/sdk/tool-lib tests via **Surefire** (headless libs);
`/code-review` (high or `ultra`) before merge; `Thread pinned` grep clean. Doc-sync (CLAUDE.md §10): the
M13 PR amends `docs/ISSUES.md` M13 + `docs/ULTRAPLAN.md` §7.1 M13 (PermissionScope-in-core, no-migration,
SPI-prelude) and the M14 PR amends the M14 entries (deps `M3, M13`; tool-module recipe), per the banner.

**Plan-doc logistics.** This plan lives at `docs/plans/tier-d-tools.md` on the dedicated
`docs/tier-d-tools-plan` branch (the same unmerged-docs-branch pattern Tier-C used —
`tier-c-provider-fleet.md` lives only on `docs/tier-c-provider-fleet-plan`), with a `Tier-D` row added to
the `docs/plans/README.md` index. Tier-C (Provider Fleet M9–M12) is merged (PRs #95–#98); its plan
remains on its own branch.
