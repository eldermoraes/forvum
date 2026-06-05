# Concurrent implementation plans (Tier-A · Tier-B · Tier-C · Tier-D · Tier-E)

A **tier** is a group of milestones implementable in one concurrent window — independent tracks joined
by at most one soft (test-level) gating edge. Source of truth remains `docs/ULTRAPLAN.md`; issue map is
`docs/ISSUES.md`. Each plan opens with an **AUTHORITATIVE CORRECTIONS** banner; where the banner
conflicts with the plan body, the banner wins, and this README's cross-cutting decisions win over both.

| Tier | Plans | Issues | Status |
|---|---|---|---|
| **A** | [M5](M5-sqlite-flyway.md) · [M6](M6-agentscoped-context.md) · [M8](M8-fallback-chatmodel.md) | #10 · #11 · #13 | **merged** (§0–§6 below, retained for reference) |
| **B** | [M7](M7-agent-registry.md) · [M9](M9-ollama-provider.md) | #12 · #14 | **merged** (§B below; M7 PR #92, M9 PR #95) |
| **C** | Provider Fleet: M9 · M10 · M11 · M12 (plan on `docs/tier-c-provider-fleet-plan`) | #14–#17 | **merged** (PRs #95–#98) |
| **D** | [Tools: M13 · M14](tier-d-tools.md) | #18 · #19 | **merged** (§D below; PRs #99 · #100) |
| **E** | [Channels: M15 · M16 · M17](tier-e-channels.md) | #20 · #21 · #22 | **planning** (§E below) |

---

## B. Tier-B — M7 · M9 (next concurrent window)

Status: **planning artifacts, not code.** The next window after Tier-A merged: the only two milestones
startable immediately (M1–M6 + M8 done), joined by one soft edge. Architectural decisions flagged in
each plan + below need maintainer sign-off before the PRs land (CLAUDE.md §8).

| Plan | Milestone | Issue | Module surface |
|---|---|---|---|
| [M7-agent-registry.md](M7-agent-registry.md) | `AgentRegistry` + minimal turn (`LlmSelector`, `Agent.respond`) | #12 | new `forvum-engine/agent` + `forvum-engine/routing` |
| [M9-ollama-provider.md](M9-ollama-provider.md) | Ollama provider (first Layer-3) + plugin-discovery `BuildStep` | #14 | new `forvum-provider-ollama` + `forvum-plugin-discovery(-deployment)` + SDK SPI + app wiring |

### B.0 Ground-truth (verified against `main`, post-Tier-A)

- **GT-B1 — no prelude needed.** `forvum-engine/pom.xml` already declares `forvum-core` (Tier-A prelude
  landed, commit `1d85c83`). `forvum-app` depends on `forvum-engine` + tamboui only — no Layer-3 module
  yet, so M9 is the first to wire one in.
- **GT-B2 — the SDK `ModelProvider` has only `extensionId()`.** `resolve(ModelRef)→ChatModel` does not
  exist yet; M9 adds it, and `forvum-sdk` gains a `langchain4j-core` dep (M9 plan AC-1). It stays
  Quarkus-free.
- **GT-B3 — the `AgentContext.get()` race is fixed.** `computeIfAbsent` (commit `1e6f58a`) + a
  `CyclicBarrier` test. M7's concurrent same-agent resolution has no pending blocker.
- **GT-B4 — `Persona` carries only `primaryModel`** (no `FallbackChain`, no `MemoryPolicy` type). M7's
  `LlmSelector` wraps a one-link `FallbackChatModel`; real multi-provider fallback is M10.
- **GT-B5 — zero file overlap.** M7 touches new `forvum-engine/agent` + `routing` packages; M9 creates
  new modules + the SDK file + app/root poms. The only shared files are `pom.xml` (root) and
  `forvum-app/pom.xml`, both edited only by M9 — orthogonal to M7's engine-internal work.

### B.1 The single gating edge

```
M7 (#12) AgentRegistry + LlmSelector + Agent.respond()      M9 (#14) OllamaModelProvider + plugin.json
   forvum-engine/agent + routing                               + forvum-plugin-discovery(-deployment)
   tested with a FakeModelProvider (no M9)                     + SDK resolve(ModelRef) + app wiring
        │                                                      contract test + native smoke (no M7)
        └──────── GATING EDGE (only one) ───────────────────────────────┘
            M9.OllamaTurnIT / OllamaScriptedTurnE2E ("scripted turn through AgentRegistry") needs M7.
            Decouple exactly like Tier-A's M8.ProviderCallPersistenceIT → M5:
            ship the e2e class-presence/@EnabledIf-gated until M7 lands, then un-gate.
```

Both sides agree the **`ModelProvider.resolve(ModelRef)→ChatModel` signature up front** (M9 plan AC-1):
M7 codes its `LlmSelector` + `FakeModelProvider` against it; M9 implements it for Ollama. Each milestone
is fully testable alone; only the real-Ollama turn needs both.

### B.2 Branch / PR & merge order

PRs target `main`; each carries `Closes #(n+5)`; Conventional Commits + `Co-Authored-By` trailer;
English-only. Use `git worktree` for isolation (do not switch branches in place).

- `feat/m7-agent-registry` → `Closes #12`
- `feat/m9-ollama-provider` → `Closes #14`

**Merge order: M7 first, then M9** — M7 provides the `AgentRegistry` + `LlmSelector` that M9's e2e
un-gates against (the impl the other's IT needs merges first, exactly as M5 did in Tier-A). Agree the
SPI signature before either branches so both compile in parallel.

Per-PR gates (both): native compile via `forvum-app` (mandatory); native smoke boots with **no
`~/.forvum/`** (registry/discovery warn + no-op); engine/library tests via **Surefire** (headless lib —
not the Dev MCP) with `-B -Dstyle.color=never`; `/code-review` (high) before merge; `Thread pinned` grep
clean. M9 adds the Risk #5 per-provider canned-turn native smoke (CI-runnable, local Ollama).

### B.3 Cross-cutting decisions needing sign-off (CLAUDE.md §8)

1. **SDK SPI extension** — add `resolve(ModelRef)→ChatModel`; `forvum-sdk` gains a versionless
   `langchain4j-core` dep (managed via `forvum-bom`; no enforcer change — it governs only `ai.forvum:*`).
   Doc-faithful (ULTRAPLAN §4.3.5.1). **Lands as the first commit of the M7 PR** (the shared prelude:
   M7's `LlmSelector` consumes it and M7 merges first); M9 implements `resolve()` for Ollama.
2. **Plugin-discovery `BuildStep` now, in a dedicated `forvum-plugin-discovery` extension** — not
   `forvum-engine` (M6 lesson). CDI alone would suffice for M9; the BuildStep is an early investment with
   a documented off-ramp (M9 D-2 / AC-5).
3. **M7 turn ambition** — `LlmSelector` + `Agent.respond()` ship in M7 (M7 D-1); `AgentToolBelt` is a
   stub until M13 (M7 D-2).
4. **Three memory tiers written; semantic embeddings deferred (NULL)** — *decided 2026-06-04* (M7 D-3 /
   AC-7); revisit when v0.1 first needs semantic search.
5. **Doc sync** — amend the `ForvumExtension` javadoc + ULTRAPLAN §6.3 (discovery BuildStep location) and
   §4.3.5.1 (SPI resolve) in the milestone PRs (M9 D-4).

### B.4 Dependency / timeline

```
(no prelude — main is ready)
        ├──────────────────────────────┐
        ▼                               ▼
   M7 (#12)                         M9 (#14)
   AgentRegistry + LlmSelector      OllamaModelProvider + plugin.json + discovery extension
   + Agent.respond() + AgentMemory  + SDK resolve(ModelRef) + app/root pom wiring
   (FakeModelProvider-tested)       (contract test + native smoke; e2e gated on M7)
        │   GATING EDGE (the only one):
        └── M9's "scripted turn through AgentRegistry" e2e needs M7 → ship it gated, un-gate post-M7.
```

Fully parallel once both agree the `resolve(ModelRef)` signature. M7 merges first; M9 un-gates its e2e.

---

## D. Tier-D — M13 · M14 (Tools)

Status: **planning artifacts, not code.** Full plan: [`tier-d-tools.md`](tier-d-tools.md). The next
window after the merged Provider Fleet (Tier-C, M9–M12). Unlike a fleet of parallel siblings, this is a
**sequential chain**: M14's `FilesystemToolProvider` cannot *compile* until M13's `ToolProvider.tools()`
SPI prelude merges (a HARD source edge — `@EnabledIf`/class-presence gating cannot bridge a compile
dependency, unlike Tier-C's soft test edge).

| Plan | Milestone | Issue | Module surface |
|---|---|---|---|
| [tier-d-tools.md](tier-d-tools.md) (§5) | M13 — `ToolRegistry` + `ToolFilter` + `ToolExecutor` + `ToolProvider.tools()` SPI prelude + permission enforcement/audit (anchor) | #18 | `forvum-engine/tools` + `forvum-sdk` (no new module) |
| [tier-d-tools.md](tier-d-tools.md) (§6) | M14 — `forvum-tools-filesystem` (first Layer-3 tool module; Fs read/write/list) | #19 | new `forvum-tools-filesystem` + app/bom/root wiring |

**Window-shape headlines (adversarially verified, 2026-06-05):** M13 adds **no migration** and **no
`PermissionScope`** (both already exist from M5/M2); the `ToolProvider` SPI is a stub that M13 fills with
`List<ToolSpec> tools()` (contribution-only, forvum-core types); tools are **not** wired into
`Agent.respond()` (that is M18); X7 (shell/mcp-bridge/OTel) is **out of scope** (issue #73). Four
architectural sign-offs decided 2026-06-05 (see the plan §2). Merge order **M13 → M14**.

---

## E. Tier-E — M15 · M16 · M17 (Channels)

Status: **planning artifacts, not code.** Full plan: [`tier-e-channels.md`](tier-e-channels.md). The
first three production turn-driving channels — TUI (M15), Web (M16), Telegram (M17). Like Tools, this is a
**sequential chain**, and even MORE so: the anchor settles the un-settled `ChannelProvider` transport SPI
**and** lands engine work (a `TurnService` facade, an `IdentityResolver`, the launch dispatch), so the
siblings depend on both.

| Plan | Milestone | Issue | Module surface |
|---|---|---|---|
| [tier-e-channels.md](tier-e-channels.md) (§6.1) | M16 — Web channel (WebSockets Next) + `ChannelProvider` SPI prelude + `TurnService`/`IdentityResolver` + launch dispatch + CI greps (ANCHOR) | #21 | new `forvum-channel-web` + `forvum-sdk` + `forvum-engine` + `forvum-app` |
| [tier-e-channels.md](tier-e-channels.md) (§6.2) | M15 — TUI channel (TamboUI/JLine 3) | #20 | new `forvum-channel-tui` (high native risk) |
| [tier-e-channels.md](tier-e-channels.md) (§6.3) | M17 — Telegram channel (long-poll) | #22 | new `forvum-channel-telegram` + `security/` |

**Window-shape headlines (ground-truth verified 2026-06-05; four sign-offs decided):** **anchor = M16/Web**
(the only channel not depending on M8); `ChannelProvider` is a stub the anchor fills with an inbound
`ChannelMessage` + outbound JDK `Consumer<AgentEvent>` (never Mutiny — `forvum-sdk` is Quarkus-free);
`Agent.respond` is single-shot with no `AgentEvent` producer → the anchor ships **single-shot adaptation**
(`TokenDelta`+`Done`), true streaming deferred to M18; the anchor also builds the slipped **CI concurrency
greps** (`synchronized`/pinned/Mutiny). M15 carries the high native risk (first `reachability-metadata.json`
+ JLine `Kernel32` `initialize-at-run-time`); M17 carries `allowedUserIds` security. Merge order **M16 →
{M15, M17}**.

---

> The sections below (§0–§6) document the **completed Tier-A window (M5 · M6 · M8)**, retained for
> reference and for the file-watching / reflection-holder conventions they established.

---

## 0. Ground-truth corrections that reshaped the Tier-A plans

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
