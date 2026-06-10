# DR-8 — Group 8: `AgentSpec` composition (`Persona` + the `agents/<id>.json` schema)

**Status:** DELIBERATED — pending maintainer sign-off.
**Issue:** #64 · **Labels:** `core`, `design-round` · **Milestone:** Design Rounds.
**Scope of authority:** the canonical `agents/<id>.json` schema; the Layer-0 `Persona` growth and the
engine-side `AgentSpec` wrapper; spawn-inheritance semantics per composed field; the validation split
(canonical-constructor vs. `AgentSpecReader` vs. `ConfigDoctor`); native-registration obligations.
**Materializes:** `docs/ULTRAPLAN.md` new §4.3.8 (and updates the §4.3.7 "omits by design" note).
**Unblocks:** PR-8 (memory-retrieval turn wiring — `persona.memoryPolicy` reachable at the `generate`
node; #51 declarative cycles — the `cycle` block), PR-9 (`CostBudget` e2e — budget parsing un-deferred;
fallback-chain links on the spec). Resolves demo deferral **D2** permanently.

> Ratification model: this round runs under the wave's standing authorization (2026-06-09). Decision
> points that follow directly from a pre-ratified wave directive are marked **Ratified (wave directive
> 2026-06-09)**; points this round settles anew are marked **Settled — flagged for maintainer review**
> so they can be amended surgically. Boundaries this round does **not** own are referenced, not
> redefined: DR-4c (running in parallel) owns the `FallbackChain` record shape — referenced here only
> abstractly as *the Group-4c record*; DR-5 owns `MemoryPolicy` (§4.3.6, settled); §4.3.5.2 owns
> `CostBudget`; DR-6a owns §9 enforcement seams.

---

## 1. Context

The `AgentSpec` composition is the last open Group: every composed type now exists or has a settled
shape, but the composition itself is still the ad-hoc as-built state. Verified on `main` (2bacc5c):

1. **`Persona` (Layer 0) carries 8 components today** — `id, systemPrompt, allowedTools, primaryModel,
   parent, costBudget, toolBudget, outputSchema`
   (`forvum-core/src/main/java/ai/forvum/core/Persona.java`). Its Javadoc says the fallback chain
   (DR-4c) and retrieval policy (DR-5) are "intentionally omitted until those contracts land" — DR-5
   has landed (§4.3.6) and DR-4c is closing in this same wave, so the omission clause expires now.
   `outputSchema` landed in P2-12 (§7.2 item 12); the §4.3.7 snippet predates it (stale in that one
   respect).
2. **`AgentSpecReader` parses five keys** — `primaryModel` (required), `allowedTools`, `parent`,
   `toolBudget`, `outputSchema` — and hard-defers budget parsing:
   `// costBudget parsing (nested CostBudget) is deferred to a later M7 increment; absent -> null.`
   (`forvum-engine/.../agent/AgentSpecReader.java:67`). PR-9's `CostBudget` e2e needs that deferral
   retired.
3. **There is no `AgentSpec` record on disk.** `AgentRegistry` holds
   `ConcurrentMap<AgentId, Persona>` (`AgentRegistry.java:48`), while §5.2 prose says
   `ConcurrentMap<AgentId, AgentSpec>` and the §7.1 M7 Files list names `AgentSpec.java` — a
   both-directions-stale Files list, exactly the M13 lesson (CLAUDE.md §14): the name is committed,
   the record never materialized. DR-8 gives it a real shape.
4. **`MemoryPolicy` is settled with ZERO engine consumers.** The record + `defaults()` exist in core
   (`MemoryPolicy.java`); the only engine reference is the §6.3 reflection holder. §4.3.6 already
   commits "the M5 config loader and the DR-8 `AgentSpec` parse both read `defaults()`" — this round
   is that DR-8 parse.
5. **`Identity.roles` (P2-11) is the additive-growth precedent**: canonical constructor widened, a
   delegating 3-arg overload kept existing callers compiling, null `roles` normalized to `List.of()`
   (`forvum-core/.../id/Identity.java:67`). The two `CURRENT_EFFECTIVE_SCOPES` bind sites are
   `TurnService.dispatch` (`TurnService.java:132–136`) and `CronScheduler.fire`
   (`CronScheduler.java:160–162`); `RoleRegistry` resolves role names → scope sets with built-ins
   `default-user` (permissive) and `cron` (read-only).
6. **`FallbackChain` is engine-shadowed, core-TBD.** The engine carries a deliberately local
   `FallbackLink(ModelRef, ChatModel, StreamingChatModel)` ("the public `forvum-core.FallbackChain` is
   still TBD … when it is ratified, only the decorator constructors adapt", `FallbackLink.java`).
   DR-4c is settling the core record IN PARALLEL — this round must not pin its fields.
7. **Spawn as built** (`AgentRegistry.spawn`, `AgentRegistry.java:88–111`): child inherits
   systemPrompt/model/budgets verbatim, `allowedTools` must narrow (subset-enforced), `outputSchema`
   is NOT inherited (P2-12 comment at line 100–101), and the §4.3.5.2 Decision-10
   `SpawnConfigurationException` guard is an explicit TODO pending budget parsing (line 83).
8. **Existing spec files that must parse unchanged**: the `forvum init` scaffold
   (`forvum-app/.../InitCommand.java:48`, `primaryModel` + `allowedTools`), every engine/app test
   fixture (`AgentSpecReaderTest` uses `primaryModel`/`allowedTools`/`parent`/`toolBudget`/
   `outputSchema`), and any user's `~/.forvum/agents/*.json` written since M7.

Issue #64's mandate: "Formalize the `AgentSpec` record composing `Identity`, `Persona`,
`FallbackChain`, `CostBudget`, `MemoryPolicy`, the allowed `PermissionScope` set, and the parent
pointer — replacing the demo's ad-hoc shape; define the on-disk `agents/<id>.json` schema
authoritatively." DR-5 §9 handed this round the residual D2 assembly with `MemoryPolicy` concrete.

---

## 2. The settled composition (summary)

The authoritative `agents/<id>.json` schema. **Every key except `primaryModel` is optional with a
backward-compatible default** — every pre-DR-8 spec file parses unchanged.

```jsonc
// ~/.forvum/agents/<id>.json — paired with the required agents/<id>.md system prompt (§4.1, §5.2)
{
  "primaryModel":  "ollama:qwen3:1.7b",          // REQUIRED — ModelRef.parse format (§4.3.5.1)
  "fallbackModels": ["openai:gpt-4.1-mini"],     // default [] — ordered fallback refs after primary (§5.4)
  "allowedTools":  ["fs.read", "web.*"],         // default [] — tool-belt globs (§5.3)
  "parent":        "main",                        // default null — top-level agent
  "toolBudget":    20,                            // default null — uncapped tool loop
  "outputSchema":  { "type": "object" },          // default null — free-text reply (P2-12)
  "roles":         ["research-readonly"],         // default absent — no agent-level scope cap (§4.3.4)
  "identityId":    "default",                     // default null — unresolved sessions stay anonymous (§5.3)
  "memoryPolicy": {                               // default absent — MemoryPolicy.defaults() (§4.3.6)
    "strategy": "HYBRID",
    "tiers": ["MESSAGES", "EPISODIC", "SEMANTIC"],
    "topK": 8, "minScore": 0.0, "compressThresholdChars": 8000
  },
  "costBudget": {                                 // default absent — uncapped (null, §4.3.5.2)
    "maxUsd": 2.50, "maxTokens": 200000,
    "window": "day", "timezone": "America/Sao_Paulo"
  },
  "cycle": {                                      // default absent — standard §5.5 supervisor graph
    "steps": ["reflect", "critique", "revise"],
    "maxRounds": 3, "stopSentinel": "DONE"
  }
}
```

The composition splits across two records:

```java
// Layer 0 — forvum-core/.../Persona.java (grown additively, Identity.roles precedent)
public record Persona(
    AgentId id, String systemPrompt, List<String> allowedTools, ModelRef primaryModel,
    AgentId parent, CostBudget costBudget, Long toolBudget, String outputSchema,
    List<ModelRef> fallbackModels,   // NEW — [] = primary-only chain
    MemoryPolicy memoryPolicy,       // NEW — null normalized to MemoryPolicy.defaults()
    List<String> roles,              // NEW — [] = no agent-level scope cap
    String identityId                // NEW — null = anonymous fallback unchanged
) { /* canonical ctor widens; the existing 8-arg overload delegates with the defaults above */ }

// Layer 2 — forvum-engine/.../agent/AgentSpec.java (the §5.2 registry value, materialized at last)
public record AgentSpec(Persona persona, CycleSpec cycle) { }   // cycle nullable = no declared cycle

// Layer 2 — forvum-engine/.../graph/CycleSpec.java (engine-local, the CronSpec precedent)
public record CycleSpec(List<String> steps, int maxRounds, String stopSentinel) { /* validation */ }
```

`Identity` and the Group-4c chain are composed **by reference, not embedding**: `identityId` points at
`identities/<id>.json`; the engine builds the Group-4c chain record from
`primaryModel` + `fallbackModels` at materialization (`LlmSelector`/`FallbackChatModel`; `costBudget`
reaches the decorator alongside the chain, never inside it — DR-4c DP-2),
so `Persona` never references the still-parallel DR-4c type. The "allowed `PermissionScope` set" of
issue #64 is expressed as `roles` — role names resolved through `RoleRegistry`, per the P2-11 rule
that role-sets are cabled ABOVE the `PermissionScope` enum, never as new constants.

---

## 3. Decision points

### `[DP-1]` The canonical key set, each optional-with-default, fully backward compatible

**Decision:** **Ratified (wave directive 2026-06-09).** The `agents/<id>.json` top-level keys are
exactly: `primaryModel` (required), `fallbackModels?`, `allowedTools?`, `parent?`, `toolBudget?`,
`outputSchema?`, `roles?`, `identityId?`, `memoryPolicy?`, `costBudget?`, `cycle?`. Defaults (the
"absent" column): `fallbackModels=[]` (primary-only chain — today's behavior), `allowedTools=[]`,
`parent=null`, `toolBudget=null` (uncapped), `outputSchema=null` (free text), `roles=[]` (no agent
cap), `identityId=null` (anonymous fallback unchanged), `memoryPolicy=MemoryPolicy.defaults()`,
`costBudget=null` (uncapped), `cycle=null` (standard supervisor graph).
**Rationale:** the five pre-existing keys keep their exact M7/P2-12 semantics, so every existing file
— `forvum init`'s scaffold included — parses unchanged; no migration, no doctor warning. Unknown keys
remain ignored (the reader reads by name; it does not reject extras), preserving forward
compatibility, consistent with how `Identity` files predating `roles` stayed valid.

### `[DP-2]` Composition split: grow `Persona` (core) + materialize an engine-side `AgentSpec` wrapper

**Decision:** **Settled — flagged for maintainer review.** Contract-grade pure data grows on `Persona`
(Layer 0): `fallbackModels`, `memoryPolicy`, `roles`, `identityId`. The graph-compilation directive
`cycle` lives engine-side in `CycleSpec`, and the §5.2 registry value becomes
`AgentSpec(Persona persona, CycleSpec cycle)` — `ConcurrentMap<AgentId, AgentSpec>` exactly as §5.2
always said; `AgentRegistry.persona(id)` keeps returning `Persona` so existing callers are untouched.
**Rationale:** a literal reading of issue #64 ("`AgentSpec` composing Identity, Persona,
FallbackChain, CostBudget, MemoryPolicy, scopes, parent") would duplicate `costBudget`/`parent`/
`allowedTools` already carried by `Persona` — the as-built record IS most of the composition. The
only piece that does not belong in core is `cycle`: it is a directive to the LangGraph4j compiler
(an engine concern), and the established precedent for file-driven engine specs is `CronSpec`
(engine package, not core). Putting `CycleSpec` in core would force core to freeze graph semantics
it cannot see. The wrapper record finally aligns code with the §5.2/§7.1 prose (the M13 stale-Files
lesson, closed rather than re-documented).

### `[DP-3]` `Persona` growth is additive — canonical ctor widens, delegating overload, null-normalization

**Decision:** **Ratified (wave directive 2026-06-09)** — the `Identity.roles` (P2-11) precedent. The
canonical constructor takes all 12 components; the existing 8-argument constructor remains as a
delegating overload supplying `(List.of(), MemoryPolicy.defaults(), List.of(), null)`; in the
canonical constructor a null `fallbackModels`/`roles` normalizes to `List.of()` and a null
`memoryPolicy` normalizes to `MemoryPolicy.defaults()` (both then defensively copied/validated).
**Rationale:** every existing caller and test (`AgentRegistry.spawn`, `AgentSpecReader`,
`PersonaTest`, app fixtures) compiles unchanged; a JSON file predating the new keys binds to the same
defaults the overload supplies — one definition of "absent" (`MemoryPolicy.defaults()` as the single
config-absent source, DR-5 DP-6/§4.3.6). Normalizing instead of throwing matches
`Identity.roles == null → List.of()` and keeps `memoryPolicy` non-nullable on the record ("memory off"
is `strategy=NONE`, a value, not absence — DR-5 DP-4).

### `[DP-4]` `fallbackModels` is `List<ModelRef>` on `Persona`; the Group-4c chain is composed engine-side

**Decision:** **Settled — flagged for maintainer review.** The spec key is `fallbackModels` (array of
`ModelRef.parse` strings); `Persona` carries it as a plain ordered `List<ModelRef>`. The engine
composes the Group-4c record from `primaryModel` + `fallbackModels` at materialization
(the `LlmSelector`/`FallbackChatModel` seam, where the engine-local `FallbackLink` already lives
"engine-local on purpose … when [FallbackChain] is ratified, only the decorator constructors adapt");
`costBudget` reaches the decorator alongside the chain, never inside it (DR-4c DP-2).
**Rationale:** DR-4c is running in parallel — pinning its fields here would race the round. A
`List<ModelRef>` is 4c-shape-independent: whatever fields the Group-4c record carries, the engine's
adapter has the inputs it needs (the original §5.4 three-input sketch
`FallbackChain(primary, List<fallback>, CostBudget)` is amended by DR-4c's §5.4 edit in this same wave:
the chain is `(primary, fallbacks)`; the budget arrives alongside, per DR-4c DP-2).
If DR-4c later prefers `Persona` to carry the chain record directly, that is one additive field swap
under the same DP-3 overload discipline. PR-9 gets its fallback links on the spec either way. The
§4.1 `config.json` *global* default chain ("for agents that do not declare their own") is **deferred**
— named, not wired: no wave consumer needs it, and the per-agent key must exist first.

### `[DP-5]` `memoryPolicy` block syntax: absent → `defaults()`; present-block fields individually defaulted

**Decision:** absent block → `MemoryPolicy.defaults()` — **Ratified** (DR-5 DP-6, §4.3.6 commits the
DR-8 parse to it). A *present* block defaults each omitted field from `defaults()` (e.g.
`{"topK": 4}` = defaults with topK 4) — **Settled — flagged for maintainer review.** Enum values
(`strategy`, `tiers`) parse case-insensitively to the §4.3.6 enums; unknown values throw, naming the
field and file.
**Rationale:** per-field defaulting is the operator-friendly reading of "optional-with-default" at
one extra `if` per field in the binder; the all-or-nothing alternative would force anyone tuning one
knob to restate four. All validation invariants stay in `MemoryPolicy`'s canonical constructor
(already merged) — the binder only maps JSON shape to arguments, so `forvum doctor` inherits every
rule through the reader-as-oracle design (P2-9). PR-8 then threads `persona.memoryPolicy()` into the
turn via `GraphTurnRequest` exactly as P2-12 threaded `outputSchema` (additive secondary ctor), making
it reachable at the `generate`/`route` nodes — the wiring itself is PR-8's, not this round's.

### `[DP-6]` `costBudget` parsing un-deferred; file syntax is day-window only

**Decision:** un-deferral — **Ratified (wave directive 2026-06-09;** PR-9's `CostBudget` e2e needs
it**)**. The `AgentSpecReader.java:67` null-deferral is retired: `costBudget` parses to a real
`CostBudget`. File syntax: `{ maxUsd?, maxTokens?, window?: "day", timezone? }` — `window` defaults
to `"day"`; `timezone` resolves at parse (absent → `ZoneId.systemDefault()`, §4.3.5.2 Decision 5); at
least one cap required (the record's own invariant). A file-declared `"window": "session"` is
**rejected at parse** with a message naming the file — **Settled — flagged for maintainer review.**
**Rationale:** a config file has no session id, so the reader cannot construct
`SessionWindow(sessionId, agentId)` (§4.3.5.2 Decision 5); deferring materialization to turn time
would silently re-create the exact unbounded-budget hazard Decision 10 made a spawn-time error.
Session-windowed budgets remain what they are today: programmatic, spawn-time constructs. Un-deferral
also activates the dormant Decision-10 spawn guard — `AgentRegistry.spawn` gains the optional
`CostBudget` override parameter and throws `SpawnConfigurationException` on inheriting a
`SessionWindow` parent budget without an override (the `AgentRegistry.java:83` TODO comes due in
PR-9, same change that makes a non-null parent budget reachable). The same `costBudget` shape applies
to `crons/<id>.json` when cron budgets land (§4.3.5.2 names both files); only the agent file is wired
by this wave.

### `[DP-7]` The `cycle` block: `{ steps[], maxRounds, stopSentinel? }`, compiled by the engine

**Decision:** block shape — **Ratified (wave directive 2026-06-09**, the PR-8 #51 consumer**)**.
Semantics — **Settled — flagged for maintainer review**:
- `steps` — non-empty ordered list of strings; each string is the *instruction* for one generation
  pass (free text; resolution of a step name against `skills/<name>.md` templates is a named
  deferral, not wired now). One *round* is one in-order traversal of `steps`.
- `maxRounds` — optional, default `3`, must be `>= 1`; the loop returns best-effort after the cap
  (degrade, don't fail — the M18 lesson).
- `stopSentinel` — optional, default null (rounds-only termination); when a pass's reply contains the
  sentinel the cycle exits early and the sentinel is stripped from the final answer.
- The LangGraph4j compile budget must scale with the cycle:
  `recursionLimit >= maxRounds × steps.size() + margin` (the M18 `recursionLimit` lesson — the
  default 25 counts every node execution).
- `outputSchema`, when present, validates the cycle's *final* answer — unchanged P2-12 placement
  (after the graph returns, not per pass).
**Rationale:** this is §7.3 item 3 ("agents declare cycles in their `.json`… the engine compiles them
into the `StateGraph` without custom code") pulled forward by the wave with the smallest possible
schema: three fields, all pure data, no new event types, no per-step model overrides (deferred until
demanded). `CycleSpec` is parsed field-by-field from `JsonNode` like `CronSpec` — no Jackson binding.

### `[DP-8]` `roles` on the agent spec is a scope CAP (intersection), never a grant

**Decision:** **Settled — flagged for maintainer review.** When `roles` is present, the turn's
effective scopes are `callerScopes ∩ union(RoleRegistry.scopesFor(each agent role))`, computed at the
two existing `CURRENT_EFFECTIVE_SCOPES` bind sites (`TurnService.dispatch`, `CronScheduler.fire`).
Absent/empty = no agent-level cap (caller scopes pass through unchanged — today's behavior). The
P2-11 "enforce iff bound" executor semantics are untouched; no third gate is added — the second
gate's bound value is narrowed before binding.
**Rationale:** this is how issue #64's "the allowed `PermissionScope` set" composes without violating
two standing rules: role-sets live ABOVE the `PermissionScope` enum (P2-11 — so the spec names roles,
not raw scopes), and an agent-spec field must never *escalate* (the §5.3/§9 security posture: specs
are operator-authored, but a cap-only field is harmless even if tampered — removing it merely restores
the identity's own scopes). Intersection mirrors the `allowedTools` belt philosophy: the agent surface
only ever narrows.

### `[DP-9]` `identityId` is the fallback identity for unresolved sessions, by reference

**Decision:** **Settled — flagged for maintainer review.** `identityId` names an
`identities/<id>.json` identity. It applies only when channel resolution yields no identity
(`IdentityResolver.resolveIdentityId(...) == empty` — today's `ANONYMOUS_IDENTITY` path at
`TurnService.java:119`): the turn then acts as the named identity (its roles drive the RBAC bind). A
channel-resolved identity **always wins**; `identityId` can never reassign a resolved user. Default
null = the anonymous path, byte-for-byte today's behavior.
**Rationale:** this gives issue #64's "composing Identity" a concrete, reference-not-embedding
meaning (the same by-id pattern as `parent`), and it is the only identity composition that does not
breach §5.3's security property ("there is no API to override identity across the spawn boundary" —
and now: nor across the spec). Anonymous TUI/`forvum ask` sessions today bind the permissive
`default-user` role; an operator can now pin them to a restricted identity per agent. An `identityId`
naming a missing identity file is a cross-ref error (DP-11), not a runtime surprise.

### `[DP-10]` Spawn-inheritance matrix, per field

**Decision:** ratified components ratified, new fields settled:

| Field | Spawn semantics | Authority |
|---|---|---|
| `systemPrompt`, `primaryModel`, `toolBudget` | inherited verbatim | as built (`AgentRegistry.spawn`) |
| `allowedTools` | must narrow (subset-enforced, throw otherwise) | as built |
| `parent` | set to the spawning parent's id | as built |
| `costBudget` | inherit verbatim; all-or-nothing override; `SessionWindow`-without-override → `SpawnConfigurationException` | **Ratified** (§4.3.5.2 Decision 10) |
| `memoryPolicy` | inherit verbatim; all-or-nothing override; no spawn exception needed | **Ratified** (DR-5 DP-10/DP-11, §4.3.6) |
| `outputSchema` | **NOT inherited** (child gets null — a worker digest is a tool result, never the validated top-level answer) | as built (P2-12) |
| `cycle` | **NOT inherited** (a worker is a single direct generation, M18 `DefaultWorkerRunner` — it never runs a graph) | **Ratified (wave directive 2026-06-09)** |
| `fallbackModels` | inherited verbatim (the chain follows the model it backs) | **Settled — flagged** |
| `roles` (cap) | inherited verbatim; no spawn-time widening or override (the belt subset rule already narrows the child; a cap override path is speculative) | **Settled — flagged** |
| `identityId` | inherited verbatim — and inert in workers: identity binds once at turn entry; workers never re-resolve (§5.3 no-override property holds) | **Settled — flagged** |

### `[DP-11]` Validation split: canonical constructor → reader → materialization/doctor

**Decision:** **Settled — flagged for maintainer review** (it instantiates the established P2-9
pattern; the placement table is the new content):
- **Canonical-constructor invariants** (`IllegalStateException`, origin-naming — the `ModelRef` idiom):
  all existing `Persona` checks, plus — `fallbackModels` entries non-null (list copied immutable);
  `roles` entries non-null/non-blank (copied immutable, mirroring `Identity.roles`); `identityId`
  non-blank when present; `memoryPolicy`/`CostBudget` invariants already live in their own records.
  `CycleSpec`: `steps` non-null/non-empty with no blank entry, `maxRounds >= 1`, `stopSentinel`
  non-blank when present.
- **`AgentSpecReader` file-shape errors** (each naming `agents/<id>.json`): wrong JSON type for a key
  (non-array `fallbackModels`/`roles`, non-object `memoryPolicy`/`costBudget`/`cycle`, non-integer
  `toolBudget`/`maxRounds`/`topK`), bad `ModelRef` text, unknown `strategy`/`tiers` value, invalid
  `timezone` (`ZoneId`), a file-declared `"session"` window (DP-6), `outputSchema` neither object nor
  string. Because `forvum doctor` validates through this same reader (the P2-9 reader-as-oracle
  decision), every rule above is a doctor finding for free — no parallel schema to drift.
- **Materialization / `ConfigDoctor` cross-reference checks** (need registries the plain reader cannot
  see — `AgentSpecReader` is a `new`-constructed binder with no CDI): each `roles` name resolves via
  `RoleRegistry` (built-in or `roles/<name>.json`); `identityId` names an existing
  `identities/<id>.json`; `primaryModel` **and every `fallbackModels` entry** resolve to an installed
  provider (extending the existing `DoctorCommand` model→provider check to the chain); `parent` names
  a known agent. These follow the P2-9 split: the provider/agent sets are gathered in the app layer
  and passed into the engine doctor.

### `[DP-12]` Native registration: zero new core-holder entries; engine records unannotated

**Decision:** **Settled** (it follows §6.3 and the merged precedents; flagged only pro forma).
`Persona`, `MemoryPolicy` (+ `RetrievalStrategy`/`MemoryTier`), `CostBudget` (+ `DayWindow`/
`SessionWindow`), `Identity`, `RoleSpec`, and `ModelRef` are ALL already registered in
`forvum-engine/.../persistence/CoreReflectionRegistration.java` — registration is per-class, so the
grown `Persona` needs **no holder change**. `AgentSpec` and `CycleSpec` are engine records built
field-by-field from `JsonNode` and never Jackson-bound or serialized — they carry **no
`@RegisterForReflection`**, mirroring `GraphTurnRequest` and `CronSpec` (the P2-CRON-DELIVERY
precedent: payload records that are never JSON-serialized stay unannotated). If DR-4c places its
chain record in core, that record's holder entry is DR-4c's consumer-PR obligation, not this round's.

---

## 4. Demo deferral D2 — permanent resolution

`demo/conference-mvp`'s D2 recorded the MVP's ad-hoc `AgentSpec(String id, String systemPrompt,
ModelRef primaryModel)` and deferred the full composition "when AgentSpec gets a full design round".
DR-5 dissolved the `memoryPolicy` sub-gap; this round dissolves the remainder: the composition is
`Persona` (12 components, DP-2/DP-3) + the engine `AgentSpec(Persona, CycleSpec)` wrapper, with the
on-disk schema of §2 as the authoritative replacement for the demo shape. **D2 is closed**
(ISSUES.md demo-deferral map: D2 → DR-5/DR-8, both now settled).

---

## 5. Decision summary for sign-off

| # | Decision | Status |
|---|---|---|
| `[DP-1]` | Canonical key set; every key optional-with-default except `primaryModel`; existing files parse unchanged | **Ratified** (wave 2026-06-09) |
| `[DP-2]` | `Persona` grows the contract data; engine `AgentSpec(Persona, CycleSpec)` is the §5.2 registry value; `cycle` is engine-side | Settled — flagged |
| `[DP-3]` | Additive growth: canonical ctor widens + delegating 8-arg overload + null-normalization (`Identity.roles` precedent) | **Ratified** (wave 2026-06-09) |
| `[DP-4]` | `fallbackModels` as `List<ModelRef>` on `Persona`; the Group-4c chain composed engine-side at materialization | Settled — flagged |
| `[DP-5]` | `memoryPolicy` absent → `defaults()` (**Ratified**, DR-5 DP-6); present-block per-field defaulting | Settled — flagged (per-field half) |
| `[DP-6]` | `costBudget` parsing un-deferred (**Ratified**, PR-9); file windows are `"day"`-only, `"session"` rejected at parse; Decision-10 spawn guard activated | Settled — flagged (file-syntax half) |
| `[DP-7]` | `cycle { steps[], maxRounds=3, stopSentinel? }` (**Ratified** shape); pass-instruction steps, sentinel-strip exit, `recursionLimit` scaling | Settled — flagged (semantics half) |
| `[DP-8]` | `roles` = agent-level scope CAP via intersection at the two existing bind sites; absent = no cap | Settled — flagged |
| `[DP-9]` | `identityId` = fallback identity for unresolved sessions, by reference; channel-resolved identity always wins | Settled — flagged |
| `[DP-10]` | Spawn matrix: budget/memory/cycle/outputSchema per their owning authorities; `fallbackModels`/`roles`/`identityId` verbatim-inherit | Mixed (see table) |
| `[DP-11]` | Validation split: record ctor → reader (doctor oracle for free) → materialization/doctor cross-refs | Settled — flagged |
| `[DP-12]` | Zero new `CoreReflectionRegistration` entries; `AgentSpec`/`CycleSpec` unannotated | Settled |

---

## 6. ULTRAPLAN sync

Returned with this round (anchors verified verbatim on the worktree `docs/ULTRAPLAN.md`; chosen
disjoint from DR-4c's parallel §4.3.7 edit, which targets the `(§4.3.5.3, DR-4c)` string on a
different line of the same bullet):

- **New §4.3.8** — `AgentSpec` composition + the authoritative `agents/<id>.json` schema, inserted
  after the §4.3.7 closing native-reflection bullet (the §4.3.6 register: schema block, record
  shapes, conventions-and-rationale bullets tagged `(DR-8 DP-n)`).
- **§4.3.7 omission bullet** — "omits … by design" scoped to "at M2 by design", and the
  "must not reference either deferred type at M2" sentence now hands off to §4.3.8 (DR-8) for the
  grown as-built record and the schema.
- **§4.3.6 cross-references** — "the residual `AgentSpec` composition is DR-8" → "settled by DR-8
  (§4.3.8)". The two remaining "DR-8 `AgentSpec` parse" forward-references (§4.3.6 code block +
  `defaults()` bullet) now resolve to §4.3.8 naturally and are left untouched.
- **Not edited:** §5.2's `ConcurrentMap<AgentId, AgentSpec>` prose and the §7.1 M7 Files list
  (`AgentSpec.java`) already describe the settled end-state this round materializes; the §4.3.7 M2
  `Persona` snippet stays as the historical M2 amendment record.

---

## 7. Open issues / dependencies

- **DR-4c (parallel).** The Group-4c record's shape is referenced abstractly throughout (DP-4). When
  it lands in §4.3.5.3, the engine adapter (`FallbackLink` → chain) is PR-9 implementation work; if
  4c wants the chain record carried on `Persona` directly, it is one additive DP-3-style field swap.
  The field-name authority 4c ceded to this round resolves as: **no `primaryModel → fallbackChain`
  key migration** — `primaryModel` stays the required key, `fallbackModels` is additive, and the
  Group-4c record's single-link bridge factory covers every spec with no `fallbackModels`.
- **PR-8 owns the wiring** this composition enables: threading `memoryPolicy` through
  `GraphTurnRequest` to the retrieval call (the P2-12 `outputSchema` threading precedent), and the
  `CycleSpec` → `StateGraph` compiler (#51). DR-8 fixes shapes, not wiring.
- **PR-9 owns** retiring `AgentSpecReader.java:67`, the `spawn` `CostBudget` override parameter +
  `SpawnConfigurationException` (the `AgentRegistry.java:83` TODO), and `fallbackModels` parsing.
- **Named deferrals:** the `config.json` global default chain (§4.1) stays unwired; skill-template
  resolution for cycle steps; per-step model overrides in `cycle`; cron-file `costBudget` wiring;
  formal JSON Schemas for spec files (the P2-9 doctor stance, unchanged).
- **Tests due with the implementing PRs** (§10 discipline): `Persona` 12-arg + overload-delegation
  property tests; reader backward-compat fixtures (a five-key pre-DR-8 file must bind to the DP-1
  defaults); a spawn test per DP-10 row — including the green-for-wrong-reason guard of giving
  overrides values DISTINCT from the parent's (the M19 lesson).
