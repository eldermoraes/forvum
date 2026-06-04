# Tier-A concurrent implementation plans — M5 · M6 · M8

Status: **planning artifacts, not code.** Produced for the three milestones that can be implemented
concurrently with M5 (per the parallelization analysis). These are uncommitted working-tree docs;
the architectural decisions flagged below need maintainer sign-off before the PRs land (CLAUDE.md §8).

Source of truth remains `docs/ULTRAPLAN.md`; issue map is `docs/ISSUES.md`.

| Plan | Milestone | Issue | Module surface |
|---|---|---|---|
| [M5-sqlite-flyway.md](M5-sqlite-flyway.md) | SQLite + Flyway V1 | #10 | `forvum-engine/persistence` + `db/migration` + engine `application.properties` |
| [M6-agentscoped-context.md](M6-agentscoped-context.md) | `@AgentScoped` CDI context | #11 | `forvum-core` (`AgentScoped`) + `forvum-engine/context` (+ maybe `forvum-engine-deployment`) |
| [M8-fallback-chatmodel.md](M8-fallback-chatmodel.md) | `FallbackChatModel` + `FailureClassifier` | #13 | `forvum-engine/model` |

Each plan opens with an **AUTHORITATIVE CORRECTIONS** banner; where the banner conflicts with the
plan body, the banner wins. This README's cross-cutting decisions win over both.

---

## 0. Ground-truth corrections that reshape all three plans

Verified against the repo at `main@04d94d2`. Settle these first or the tracks collide.

- **GT-1 — `forvum-engine/pom.xml` declares NEITHER `forvum-core` NOR `forvum-sdk` today.** Its
  `<dependencies>` are only `quarkus-arc`, `quarkus-jackson`, `quarkus-junit` (test); the enforcer
  *allowlists* core/sdk but no `<dependency>` declares them. M5, M6, and M8 all need `forvum-core` on
  the engine compile path. **Add it once, in a prelude commit (§2).**
- **GT-2 — the SDK `@RegisterForReflection` is a non-functional placeholder.**
  `forvum-sdk/.../RegisterForReflection.java` is a Quarkus-free re-declaration whose translating
  `BuildStep` is "a later milestone" and does not exist yet. Any native reflection holder must use
  **`io.quarkus.runtime.annotations.RegisterForReflection`** directly (the engine is Layer-2 and
  already pulls Quarkus). The SDK marker registers nothing today.
- **GT-3 — M6 ↔ M5 `turn_id` is a forward (soft) edge, not a concurrent-window blocker.** M6 only
  *defines* `ScopedValue<UUID> CURRENT_TURN`; the `turn_id` *column* is Flyway **V2**, out of M5's
  V1-only scope. The `ISSUES.md` "M6 needs M5 turn_id" annotation binds a *later* milestone (V2 /
  supervisor), not this window. M6 can land before, after, or alongside M5.

---

## 1. The one single-holder + one engine-core-dep contracts

- **One reflection holder:** `forvum-engine/src/main/java/ai/forvum/engine/persistence/CoreReflectionRegistration.java`,
  owned by **M5**, using the Quarkus annotation (GT-2). **M8 appends** its Layer-0 targets; it must
  **not** create a second `CoreReflectionRegistration`. M6 adds nothing to it.
- **One `forvum-core` engine dependency:** added once (prelude); the others must not re-add it.

---

## 2. Decisions to settle ONCE, up front

- **(a) Reflection holder:** M5 owns the single holder, Quarkus annotation; M8 appends. *(settled above)*
- **(b) `FallbackChain` shape:** do **not** pin a `forvum-core.FallbackChain` in this window (it is
  TBD / Group-4c #62, verified absent). M8 uses an engine-local `record FallbackLink(...)`; when
  Group-4c ratifies the core type, one constructor change in `FallbackChatModel` adapts it.
- **(c) `FailureClass` ↔ `FallbackTriggered.reason` migration:** **decline** it. `FailureClass` is the
  3-way retry axis (engine-local, sealed); `reason` stays the finer `FallbackReasons` String token
  (telemetry needs the granularity). Only artifact is a one-line ULTRAPLAN §4.3.2 doc amendment.
  **Zero `forvum-core` source change from M8.**
- **(d) `@AgentScoped` placement + registration:** annotation in `forvum-core` with
  `jakarta.enterprise.cdi-api:provided` (verified safe vs. the core enforcer). For the registration
  mechanism, **prefer A2 (portable extension)** to avoid the engine-pom `<build>` restructure during
  the concurrent window; choose A1 (runtime+deployment extension split) only if the M6 Step-0 spike
  proves A2 red under native — and then land A1's pom restructure last + amend ULTRAPLAN §5.1/§7.1.
- **(e) `CURRENT_TURN` vs `turn_id`:** no overlap in v0.1. Canonical binding location is
  `ai.forvum.engine.context.CurrentAgent.CURRENT_TURN`; M5 ships V1 only (no `turn_id` column);
  M8's `ProviderCall` carries a nullable `turnId` for forward-compat, populated `null` in v0.1.

---

## 3. Prelude commit (before branching the three)

Land one tiny prep commit on `main` to remove the GT-1 collision:

- Add `<dependency>ai.forvum:forvum-core</dependency>` (versionless) to `forvum-engine/pom.xml`.

This lets all three branch from a base where `forvum-core` is declared. (`forvum-sdk` is deferred to
the M8 PR — only M8 needs it, and only for the holder pattern, which per GT-2 uses the Quarkus
annotation anyway, so M8 needs `forvum-sdk` only if a type uses the SDK marker.)

---

## 4. Branch / PR & merge strategy

Per ULTRAPLAN §9 + `docs/ISSUES.md`: PRs target `main`; each PR carries `Closes #(n+5)`; milestone
commits carry a `Co-Authored-By` trailer for AI-assisted work (convention updated 2026-06-04 — see
`ISSUES.md`); English-only; Conventional Commits.

Branches (use `git worktree` for isolation):
- `feat/m5-sqlite-flyway` → `Closes #10`
- `feat/m6-agentscoped-context` → `Closes #11`
- `feat/m8-fallback-chatmodel` → `Closes #13`

Recommended merge order (one gating edge: `M8 ProviderCallPersistenceIT → M5`):

1. **M5 (#10) first** — creates the single holder, `application.properties`, the `provider_calls`
   entity + Panache `ProviderCallRecorder` impl that M8's gated IT needs.
2. **M6 (#11)** — independent of M5; if A2, its only engine-pom edit is the prelude `forvum-core` add.
   Merge before/after M5 freely. If A1 is forced, merge last.
3. **M8 (#13) last** — its M5-independent core (decorator + classifier vs. mocks + in-memory recorder)
   is built in parallel from day one; only the `ProviderCallPersistenceIT` un-gates after M5 lands.

Per-PR gates (all three): native compile via `forvum-app` (mandatory); native smoke boots the binary
with **no `~/.forvum/`**; engine `@QuarkusTest`/`*IT` run via **Surefire** (headless library — NOT the
Dev MCP) with `-B -Dstyle.color=never`, reading `target/surefire-reports/*.txt`; `/code-review` (high)
before merge; CI `Thread pinned` grep clean except M5's allowlisted SQLite JNI pins.

---

## 5. Dependency / timeline

```
PRELUDE (on main): add forvum-core dep to forvum-engine/pom.xml   [removes GT-1 collision]
        ├──────────────┬──────────────────────────────┐
        ▼              ▼                                ▼
   M5 (#10)        M6 (#11)                         M8 (#13)
   SQLite+Flyway   @AgentScoped/ScopedValue         Fallback decorator + classifier
   +BudgetMeter    (spike → prefer A2)              (M5-independent core fully parallel)
   +HOLDER
        │  GATING EDGE (the only one):
        └── M8.ProviderCallPersistenceIT needs M5's provider_calls table +
            Panache ProviderCallRecorder impl + M5's CoreReflectionRegistration holder
```

Fully parallel once the prelude lands: M5↔M6, M5↔M8 (core logic), M6↔M8. The sole hard edge is
`M8.ProviderCallPersistenceIT → M5`, handled by shipping that IT class-presence-gated until M5 merges.

---

## 6. Decisions needing maintainer sign-off (architectural — CLAUDE.md §8)

- **M6 Decision A:** `forvum-engine` becoming a Quarkus extension (A1) vs. a portable extension (A2).
  A1 changes the module's nature and would amend ULTRAPLAN §5.1/§7.1 (the doc names
  `AgentContextBuildItem`/`AgentContextProcessor`, which only A1 produces).
- **M8 Decision C:** declining the `FallbackTriggered.reason → FailureClass` migration the doc
  schedules "for M8" (proposes a §4.3.2 amendment instead).
- **M8 Decision A / `FallbackChain` (Group-4c #62):** engine-local link list until the core shape is
  ratified.
