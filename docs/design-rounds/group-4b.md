# Group 4b — `CostBudget` design round

**Status:** open (inventory populated; no shape proposed yet).
**Depends on:** Group 4a (`ModelRef`) — applied 2026-04-20.
**Blocks:** Group 4c (`FallbackChain`) — cannot begin until 4b closes.
**Target section:** `docs/ULTRAPLAN.md` §4.3.5.2 (currently `*TBD (Group 4b).*`).
**Mirror file:** `/Users/eldermoraes/.claude/plans/forvum-ultraplan.md` (kept byte-identical via paired Edits).

---

## Inventory

All line numbers refer to `docs/ULTRAPLAN.md` at baseline of 2026-04-20 (post-Group-4a).

### Direct `CostBudget` mentions

| Line | Section | Context |
|---|---|---|
| 39 | §1.2 (improvements over OpenClaw) | Introduces `FallbackChain(primary, fallbacks, budget)` as a "core type" — first signature appearance |
| 65 | §2.1 (`forvum-core` layer) | Listed among core "records and sealed interfaces" alongside `ModelRef`, `FallbackChain`, `MemoryPolicy` |
| 620 | §4.3.5 header | `ModelRef, FallbackChain, CostBudget` bundle section |
| 700 | §4.3.5.2 header | This round's target — currently `*TBD (Group 4b).*` |
| 746 | §5.2 (`AgentRegistry`) | "The child inherits its parent's `CostBudget`, `MemoryPolicy`, and `Identity` unless the spawn request overrides them" |
| 758 | §5.4 (`FallbackChatModel`) | Wraps `FallbackChain(primary, List<fallback>, CostBudget)` — 3-arg signature locked |
| 771 | §5.5 (LangGraph4j `spawn_worker`) | "a narrowed tool belt and a child `CostBudget`" |
| 854 | M2 milestone | `CostBudget.java` listed among `forvum-core` files created in M2 |

### Related signals

| Line | Section | Fact | Relevance |
|---|---|---|---|
| 146 | §3.6 (observability) | OTel span `forvum.llm.call` attrs: `tokens_in`, `tokens_out`, `cost_usd`, `latency_ms`, `fallback` | Per-call cost telemetry already specified |
| 198 | §4.1 (on-disk layout) | `agents/<agentId>.json` has `costBudget` and `toolBudget` caps | JSON key is lowercase `costBudget`; `toolBudget` is a distinct, coexisting concept |
| 280–293 | §4.2 V1 schema | `provider_calls`: `tokens_in INTEGER NOT NULL`, `tokens_out INTEGER NOT NULL`, `cost_usd REAL` **nullable** | Persisted USD cost is nullable — local providers may omit it |
| 451–454 | §4.3.2 `FallbackReasons` | Constants include `COST_BUDGET = "cost_budget"` alongside `RATE_LIMIT`, `TIMEOUT`, `SERVER_ERROR` | Budget exhaustion is first-class fallback trigger |
| 760 | §5.4 | "a nightly summarization cron might want to pin a cheap local Ollama model for cost" | Cost is cited as motivation for per-cron chain selection |
| 770 | §5.5 `tool_loop` | "iterates tool calls until ... the tool budget is exhausted" | `toolBudget` limits tool-call count, a distinct axis from USD/token cost |

### Pre-committed constraints (binding for the design)

Already locked elsewhere in the plan. Any proposed shape in 4b MUST honor these:

1. **Lives in `forvum-core` module.** Line 65 lists it under "records and sealed interfaces" of that module. Package presumed `ai.forvum.core` by analogy with `ModelRef`.
2. **Is the 3rd positional arg of `FallbackChain(primary, fallbacks, budget)`.** Lines 39, 758. Constructor param name `budget` (lowercase) on the `FallbackChain` side.
3. **Spawn inheritance default: child inherits parent's `CostBudget` unless the spawn request overrides.** Line 746. The override *mechanism* is in scope for 4b; the default is not.
4. **Budget exhaustion is the registered `FallbackReasons.COST_BUDGET` value.** Line 454. Whichever component checks the budget must raise a fault mapped to that reason.
5. **JSON deserialization key is `costBudget` (lowercase camelCase).** Line 198. PascalCase type, camelCase JSON — standard convention.
6. **Scopes are per-agent, per-cron, per-spawn — all three coexist.** Line 198 (agents), lines 758 + 760 (crons have their own chain), line 746 (spawn inheritance).
7. **Telemetry `cost_usd` is nullable `REAL`.** Schema line 288. Providers without a USD reporting contract (Ollama local) persist NULL.
8. **Distinct from `toolBudget`.** Line 198 lists both as separate caps; §5.5 line 770 references "tool budget" as tool-call-count limit. `toolBudget` is **explicitly out of scope for 4b** unless we decide it shares the type shape.

---

## Open design points

Seven points defined pre-inventory; three more revealed by the inventory findings.

### Defined in the pre-planning turn

1. **Unit of measure.** USD cap? token cap? both? If both: paired (spend counts against each) or independent (whichever hits first triggers exhaustion)?
2. **Granularity.** per-turn, per-session, per-day, per-month, or hierarchical (multiple scopes stacked)?
3. **Reset policy.** rolling window (last N hours), calendar-based (midnight local, month-start), manual-only, or unbounded-accumulation?
4. **Exhaustion behavior.** hard stop (throw `FallbackReasons.COST_BUDGET`), warn + continue, escalate to human, or configurable per-agent?
5. **Tracking primary.** expose `spent` field, `remaining` field, or both? If both, is one derived from the other?
6. **Spawn inheritance beyond default.** Line 746 says "inherits unless overridden" — open: does the child get an **independent copy** of the cap (spend separately from parent), a **shared reference** (child spend eats into parent's pool), or a **proportional carve-out**?
7. **SQL representation.** single column in `provider_calls` (aggregate), multiple columns, separate events/ledger table, or purely derived at query time?

### Revealed by inventory

8. **Null-cost handling.** How does a USD-denominated budget treat rows with `cost_usd = NULL` (Ollama local)?
   - (a) treat as zero — local inference is free;
   - (b) skip the row from USD accounting but still count tokens;
   - (c) estimate via `tokens_in × unit_cost_in + tokens_out × unit_cost_out` with per-provider tables;
   - (d) reject the chain if budget is USD-only and any local call is configured.
9. **Enforcement boundary (who checks the budget).**
   - (a) `FallbackChatModel` decorator pre-call, consulting a `CostLedger` bean;
   - (b) engine layer before entering the turn;
   - (c) per-`ModelProvider` adapter after API returns (so the just-completed call's cost is accounted before the next check).

   Decides where `FallbackReasons.COST_BUDGET` is raised and how atomic the check is across concurrent turns.
10. **Type shape.** Record with primitive/optional fields? Sealed hierarchy like `CostBudget permits UsdBudget, TokenBudget, CompositeBudget`? Nested records per dimension? Decided jointly with point 1 (units) but tracked separately because it determines JSON serialization structure.

---

## Decisions log

### Decision 1 — Unit of measure
**Approved:** 2026-04-22 (session-local)
**Decision:** Both paired — a single `CostBudget` tracks both USD and tokens against the same scope/reset; exhaustion fires when either dimension's cap is reached.
**Rationale:** Options A (USD only) and B (tokens only) each fail to cover the three user profiles Forvum must support: cloud-only (needs USD cap), local-only (USD cap is theater under Ollama's null-cost reporting), and hybrid (wants both). B additionally conflates financial control ("don't spend more than $X") with runaway-call control ("don't loop into thousands of calls") — distinct concerns that a single cap cannot disambiguate. D (independent budgets) covers all three profiles but explodes combinatorially in type shape (#10), spawn inheritance (#6), and test surface; excessive for Tier 1. C keeps one object with a single scope/reset while tracking both dimensions, and allows opt-out per dimension via nullability (pure-local user leaves `maxUsd = null`; cloud-only user leaves `maxTokens = null`).
**Resolves open point(s):** #1
**Implication for §4.3.5.2 spec:** `CostBudget` exposes a USD cap and a token cap sharing one scope/reset window. Exhaustion = any non-null cap reached. Per-field nullability for opt-out is proposed; formal confirmation of shape (flat record vs. sealed hierarchy) deferred to #10.
**Implication for other sections:** §5.4 `FallbackChatModel` budget consultation reads both dimensions. §4.2 `provider_calls` already records both dimensions per row — #7 confirms that the row-level ledger is the aggregation source (no new column needed). #8 (null-cost handling) escalates to resolution position 3 (right after #7), since USD is now in scope and the schema's `cost_usd REAL` nullability demands an accounting policy.

### Decision 2 — SQL representation
**Approved:** 2026-04-22 (session-local)
**Decision:** `provider_calls` (§4.2) is the canonical cost ledger. `CostBudget` computes spend on-the-fly via `SUM()` queries scoped by agent/cron/session and a time window; no aggregate columns are added to the schema, and `CostBudget` itself holds no persisted spend state.
**Rationale:** The schema already persists `tokens_in`, `tokens_out`, and `cost_usd` per row in `provider_calls`, providing both dimensions needed after Decision 1. A denormalized aggregate column would be a derived cache with dessync risk; keeping the ledger authoritative avoids that entire class of bugs. On-the-fly `SUM()` assumes each budget's time window is bounded (per-day, per-month, per-session — to be resolved in #2 granularity); for short windows in single-user MVP use, reads are fast with an index on `(agent_id, created_at)`. If Forvum eventually runs at higher volume (e.g., 90k+ rows after 6 months of intensive use) or if #2 introduces an "all-time" scope, materialized views or periodic aggregation can be added in a later milestone. Not a concern in Tier 1; flagged for production review if a bottleneck surfaces.
**Resolves open point(s):** #7
**Implication for §4.3.5.2 spec:** `CostBudget` is a cap-carrier only — it exposes a read-side method (shape TBD, likely `currentSpend(Window window)` or equivalent) that delegates to the `provider_calls` ledger; no `spent` or `remaining` fields persist on `CostBudget` itself.
**Implication for other sections:** M5 persistence confirms `provider_calls` as the canonical table for cost data (no additional table or column introduced by `CostBudget`). #5 (tracking primary) simplifies — the "persist `spent` vs. `remaining`" question dissolves since nothing is persisted on the budget object itself; #5 now reduces to the API-shape question of what the read-side method returns.

### Decision 3 — Null-cost handling
**Approved:** 2026-04-22 (session-local)
**Decision:** Null-cost rows are treated as zero in the USD aggregation of `CostBudget`. At the persistence layer, `provider_calls.cost_usd` is written as `NULL` when the provider did not report a cost, and as the reported numeric value when it did; `SUM(cost_usd)` (which ignores NULL by SQL semantics) then yields the zero-contribution behavior with no special-case logic. Token dimension is unaffected — `tokens_in`/`tokens_out` are NOT NULL and always contribute to `maxTokens`.
**Rationale:** Options (a) "treat as zero" and (b) "skip row from USD accounting" converge behaviorally because SQL `SUM` ignores NULL; the real choice is framing and what gets persisted. Persisting NULL preserves an analytical signal — a later query can distinguish a local-provider row from a cloud row that happened to cost $0 — while interpreting NULL as zero in the aggregation delivers the intuitive "local contributions are free in USD" contract. Option (c) would require an externally maintained per-provider pricing table (infinite maintenance; arbitrary unit costs for truly local providers) to solve a problem Decision 1 already dissolved via per-dimension opt-in. Option (d) would reject configurations (USD-only cap + local provider in chain) that Decision 1 explicitly permits. "Treat as zero" is preferred as the framing name because "skip" might imply the full row is ignored, whereas token counts from the same row still contribute to `maxTokens`.
**Resolves open point(s):** #8
**Implication for §4.3.5.2 spec:** USD aggregation is a plain `SUM(cost_usd) WHERE ...scope+window...` over `provider_calls`; no `COALESCE` required. Exhaustion in the USD dimension: `sum ≥ maxUsd` when `maxUsd != null`. Token dimension unchanged; aggregates over NOT NULL columns. `CostBudget` itself requires no null-cost-aware logic.
**Implication for other sections:**
- **M9-M12 (provider adapters):** each provider's `ChatResponse` carries usage metadata with an **optional** cost field — the exact shape (`Optional<BigDecimal>`, `double + boolean reported`, or another form) is deferred to the provider-specific milestones. A provider populates the field when the remote API exposes cost; otherwise the field is absent or flagged not-reported.
- **M5 (persistence layer):** writes `cost_usd = NULL` when the provider's usage lacks a cost value; writes the reported numeric otherwise. **No provider-name-based special casing in the persistence adapter** — a provider that was previously "local" (e.g., a TGI deployment with compute-based pricing, or an Ollama plugin that exposes cost) starts populating `cost_usd` transparently as soon as its `ChatResponse` carries the field, with zero persistence-layer changes.
- **#9 (enforcement boundary):** whichever layer checks the budget invokes the same `SUM` query; null-cost rows require no special handling at enforcement time.

### Decision 4 — Type shape
**Approved:** 2026-04-22 (session-local)
**Decision:** Option 1 — flat Java record `CostBudget(BigDecimal maxUsd, Long maxTokens, Window window)` with nullable primitive fields, validated in the canonical constructor via `IllegalStateException` with informative, triage-oriented messages.
**Rationale:** Simplest shape that encodes the two-dimensional, opt-in-per-dimension contract from Decision 1, with a direct parallel to Group 4a's `ModelRef` (flat record, primitives, no wrapper types). Option 2 (sealed hierarchy `permits UsdBudget, TokenBudget, CompositeBudget`) was rejected because it imports the *form* of Group 1's `AgentEvent` without the justifying condition: `AgentEvent` permits diverge in payload per event type, whereas USD vs. token caps differ only in which of two fields is populated, not in schema shape. Option 3 (`Optional<UsdCap>` + `Optional<TokenCap>`) was rejected for concrete technical reasons, not just style: `Optional` in record fields breaks default Jackson serialization, conflicts with Brian Goetz's official Java guidance on `Optional` usage, and introduces a pattern no other Tier-1 Group uses. Option 4 (flat record with nullable `UsdCap`/`TokenCap` value objects) was the attractive middle ground but was rejected under CLAUDE.md's "no abstractions for single-use code" and "no speculative flexibility" — the two `Cap` types would be used solely inside `CostBudget`, and the validation they'd encapsulate (`signum() < 0`, `< 0`) is too trivial to justify dedicated types.
Two adjustments from the base proposal align `CostBudget` with the convention set by Groups 2, 3, 4a: (i) validation throws `IllegalStateException` rather than `IllegalArgumentException`, matching the established convention that invalid values arising from config parsing or DB reads indicate invalid *system state*, not caller argument misuse; (ii) error messages name the likely origin of the invalid value (config file, programmatic construction) and the on-disk path an operator should inspect (`agents/<id>.json`, `crons/<id>.json`), mirroring the informative-message pattern of Groups 2 and 4a.
**Resolves open point(s):** #10
**Implication for §4.3.5.2 spec:** Final record form for §4.3.5.2:

```java
public record CostBudget(BigDecimal maxUsd, Long maxTokens, Window window) {
    public CostBudget {
        if (maxUsd == null && maxTokens == null) {
            throw new IllegalStateException(
                "CostBudget must declare at least one cap (maxUsd, "
              + "maxTokens, or both). Both nulls indicates either a "
              + "config-file error or a programmatic construction bug. "
              + "Check agents/<id>.json or crons/<id>.json.");
        }
        if (maxUsd != null && maxUsd.signum() < 0) {
            throw new IllegalStateException(
                "CostBudget maxUsd must be non-negative. Got: " + maxUsd
              + ". Negative caps are nonsensical — check config "
              + "file formatting.");
        }
        if (maxTokens != null && maxTokens < 0) {
            throw new IllegalStateException(
                "CostBudget maxTokens must be non-negative. Got: " + maxTokens
              + ". Negative caps are nonsensical — check config "
              + "file formatting.");
        }
    }
}
```

The `Window` field's concrete type is deferred to #2 (granularity) + #3 (reset policy), which together define what `Window` needs to express; the record declaration above will be updated once `Window` is fixed.
**Implication for other sections:** None new. Reinforces the `IllegalStateException` + informative-message convention established by Groups 2, 3, 4a — Group 4c (`FallbackChain`) and Group 5 (`MemoryPolicy`) should continue the same pattern.
**Fallback note:** Option 4 (`UsdCap`/`TokenCap` record value objects with nullable fields on `CostBudget`) is documented here as a fallback shape. If subsequent decisions reveal that these wrappers would be reused outside `CostBudget` — e.g., `FallbackChain` exposing per-dimension cost information, `MemoryPolicy` introducing its own `Cap` type, or `#6` spawn inheritance carving out per-dimension allowances for children — revisit this decision. Refactoring from Option 1 to Option 4 is localized (introduce two record types, wrap the two fields, update Jackson mapping) and carries no schema implications.

### Decision 5 — Granularity
**Approved:** 2026-04-22 (session-local)
**Decision:** `Window` is a Java sealed interface with two permits for MVP: `DayWindow(ZoneId tz)` and `SessionWindow(String sessionId, String agentId)`. `ZoneId` resolution reads an optional `timezone` field from `agents/<id>.json` (and `crons/<id>.json` when applicable) and falls back to `ZoneId.systemDefault()` when the field is absent.
**Rationale:** A sealed interface over granularity permits covers both mental models Forvum must support — daily rolling caps ("don't spend more than $X per day") and per-conversation caps ("cap this chat session at N tokens") — without introducing an `enum` + parameter-bundle shape that would couple unrelated fields into a single record. A pre-decision grep check against `docs/ULTRAPLAN.md` §4.2 confirmed `provider_calls.session_id TEXT NOT NULL` plus the composite index `idx_provider_session (session_id, created_at)` already exist in the V1 schema, so `SessionWindow` requires zero schema change and reuses the same `SUM()` infrastructure as `DayWindow`. The `sealed permits` form also leaves the door open for post-MVP additions (rolling windows, monthly caps, unbounded accumulation, hierarchical stacks — see "Combos not included" below) as new permits without breaking existing callers: a `switch` over a sealed type forces exhaustive handling at every call site the moment a permit is added, surfacing work cleanly rather than silently.
**Resolves open point(s):** #2
**Implication for §4.3.5.2 spec:** Ships the sealed `Window` interface plus its two permits, and a contract method that scopes a `SUM()` aggregation over `provider_calls` to the window — conceptually each permit yields a SQL filter (WHERE-clause fragment + parameter bindings). The concrete signature of that contract method is fixed in §4.3.5.2 alongside the final `CostBudget` record. `CostBudget`'s `Window window` field from Decision 4 now resolves to this sealed type. Shape sketch for §4.3.5.2:

```java
public sealed interface Window permits DayWindow, SessionWindow {
    // Each permit provides the WHERE-clause fragment and parameter
    // bindings that scope a SUM() over provider_calls to its window.
}

public record DayWindow(ZoneId tz) implements Window {
    // Validate: tz non-null (config-parse boundary substitutes
    // ZoneId.systemDefault() when the "timezone" field is absent,
    // so a null reaching this constructor indicates a wiring bug).
}

public record SessionWindow(String sessionId, String agentId) implements Window {
    // Validate: both fields non-null and non-blank.
}
```

Full constructor validation with informative `IllegalStateException` messages (per the Groups 2/3/4a and Decision-4 convention) is materialized at spec-assembly time in §4.3.5.2.
**Implication for other sections:**
- **§4.1 config schema (on-disk layout):** `agents/<id>.json` (and `crons/<id>.json` when applicable) gains an **optional** `timezone` field, used only when that config's `costBudget` carries a `DayWindow`. Absent field ⇒ `ZoneId.systemDefault()` applied at parse time.
- **§4.2 persistence (M5):** No schema change. `DayWindow` aggregation reads via `idx_provider_agent` (`agent_id, created_at`); `SessionWindow` reads via `idx_provider_session` (`session_id, created_at`). Both indexes already exist in the V1 schema.
- **M9–M12 providers + cron implementation (implementation note, not a Group-4b contract change):** For `SessionWindow` to behave as a per-invocation cap in crons and autonomous agents, **each cron firing must generate its own fresh `session_id`** (one-session-per-invocation). `provider_calls.session_id` is `NOT NULL`, but §4.2 does not yet pin how crons populate it; this log entry pins the choice to avoid silent divergence. A naive M2+ implementation that reuses a fixed `session_id` per cron definition would make `SessionWindow` accumulate across invocations and behave like an unbounded window — the exact failure mode this decision is intended to preempt. M2+ milestone reviewers should honor this note when wiring cron execution.
**Combos not included:** rolling windows (last N hours), monthly/calendar-aligned-to-month, unbounded accumulation, and hierarchical (agent ∈ cron ∈ global) stacking were considered and deliberately deferred. Each can be added post-MVP as an additional permit of `Window` without breaking changes to `CostBudget`, to existing config files, or to downstream callers — an absent permit is never visible to code that doesn't handle it.

### Decision 6 — Reset policy
**Approved:** 2026-04-22 (session-local)
**Decision:** Reset policy is not a free configuration knob — it is implicit in each `Window` permit. `DayWindow(ZoneId tz)` resets at calendar-aligned midnight in its resolved `ZoneId`. `SessionWindow(sessionId, agentId)` has no internal reset; its accounting spans the lifetime of the identified session.
**Rationale:** Reset semantics are entailed by granularity, not orthogonal to it — exposing a separate `reset` field would admit nonsensical combinations (e.g., a `DayWindow` with a "session-lifetime" reset) that the type system should rule out. Making reset implicit in the permit also keeps `CostBudget` immutable and stateless, consistent with Decision 2 (no persisted spend state): the SQL filter produced by each `Window` permit expresses both the scope and the cutoff of that window in one step — for `DayWindow`, `WHERE agent_id = ? AND created_at >= midnight(today, tz)`; for `SessionWindow`, `WHERE session_id = ? AND agent_id = ?`.
**Resolves open point(s):** #3
**Implication for §4.3.5.2 spec:** The SQL-filter contract method on each `Window` permit encodes its own reset semantics; no separate reset field, `ResetPolicy` type, or reset configuration knob is introduced on `CostBudget` or on `Window`.
**Implication for other sections:** None. Reset requires no state persistence — the `provider_calls.created_at` timestamps plus each permit's SQL filter are sufficient to compute the current window on demand at each `SUM()` call.

### Decision 7 — Read-side API (tracking primary)
**Approved:** 2026-04-22 (session-local)
**Decision:** The read-side of the budget is a service method — `CostBudget` stays a pure data record, consistent with the Groups 2/3/4a pattern — returning an atomic usage snapshot from a single SQL trip. Service name: **`BudgetMeter`** (interface in `forvum-core`; default implementation lives in the M5 persistence layer, with the ledger / `DataSource` supplied via CDI injection). Method signature:

```java
public interface BudgetMeter {
    Usage usage(CostBudget budget);
}
```

Shape of the returned types (all in `forvum-core`):

```java
public record Usage(
    Spend spent,              // already consumed (usd + tokens)
    Spend remaining,          // headroom; individual dimensions may be
                              // null when the matching cap on CostBudget
                              // is null (opt-out per Decision 1)
    boolean exhausted,        // any non-null cap reached
    ExhaustionCause cause     // null iff exhausted == false
) {
    public Usage {
        if (spent == null) {
            throw new IllegalStateException(
                "Usage spent must be non-null. Construct via BudgetMeter "
              + "or test fixtures — a null here indicates a programmatic "
              + "construction bug.");
        }
        if (remaining == null) {
            throw new IllegalStateException(
                "Usage remaining must be non-null. Individual dimensions "
              + "inside the Spend may be null (opt-out), but the Spend "
              + "record itself must be present.");
        }
        if (exhausted && cause == null) {
            throw new IllegalStateException(
                "Usage cause must accompany exhausted=true. Either the "
              + "ExhaustionCause was lost between BudgetMeter query and "
              + "Usage construction, or the exhausted flag was set "
              + "without computing cause. Check BudgetMeter output.");
        }
        if (!exhausted && cause != null) {
            throw new IllegalStateException(
                "Usage cause must be null when exhausted=false. A non-null "
              + "cause paired with exhausted=false indicates a caller "
              + "populated cause without flipping the exhausted flag.");
        }
    }
}

public record Spend(BigDecimal usd, Long tokens) {
    public Spend {
        if (usd != null && usd.signum() < 0) {
            throw new IllegalStateException(
                "Spend usd must be null or non-negative. Got: " + usd
              + ". Negative values indicate either a ledger accounting "
              + "bug or an arithmetic underflow in BudgetMeter.");
        }
        if (tokens != null && tokens < 0) {
            throw new IllegalStateException(
                "Spend tokens must be null or non-negative. Got: " + tokens
              + ". Negative values indicate either a ledger accounting "
              + "bug or an arithmetic underflow in BudgetMeter.");
        }
    }
}

public enum ExhaustionCause {
    USD_CAP_HIT,
    TOKEN_CAP_HIT,
    BOTH_CAPS_HIT
}
```

**Rationale:**
- **(ii) service over (i) instance method on `CostBudget`** — `CostBudget` knows its caps and window (static config), but knowing how to query the ledger (runtime dependency) belongs in a service bean, not in a data record. This preserves the Tier-1 pattern of pure-data records (`ModelRef`, `CostBudget`) + behavior services.
- **(D) atomic snapshot over (A)/(B)/(C) single-field returns** — one `SUM(cost_usd), SUM(tokens_in + tokens_out)` SQL trip populates every consumer's need. Enforcement path reads `usage.exhausted()`; operator `/status` reads `usage.spent()`/`usage.remaining()`; `FallbackTriggered.reason` mapping reads `usage.cause()` — all from the same snapshot, with no re-query race between "check exhausted" and "observe spent/remaining" that could accumulate between two separate trips.
- **`Spend` reused between `spent` and `remaining`** — DRY and symmetric; the same two-dimension shape (usd + tokens) describes both what has been consumed and what still fits. Per-dimension nullability on `remaining` encodes "no cap on this dimension" (opt-out per Decision 1) without introducing a separate `Headroom` type.
- **`BOTH_CAPS_HIT` as a first-class enum constant** — the rare-but-real case where both caps are reached in the same SQL trip is visible downstream (fallback reason, operator log) without ad-hoc "bitmask" patterns.
- **Nullable `cause` paired with boolean `exhausted` over `Optional<ExhaustionCause>`** — same argument as Decision 4: `Optional` in record fields breaks default Jackson serialization and conflicts with Brian Goetz's official guidance on `Optional`. The canonical-constructor biconditional invariant (`cause != null ⇔ exhausted == true`) encodes the relationship safely without `Optional`.
- **Boxed `Long tokens` on `Spend` adopted over primitive `long tokens`** — a minor adjustment from the approved decision text. The decision permits `remaining.tokens` to be null when `maxTokens == null` on the budget (opt-out of the tokens dimension), which requires a reference type. Boxing both fields keeps the `Spend` type symmetric and reusable; `spent`'s non-nullness is guaranteed by `BudgetMeter`'s contract (spent is always concrete), while `remaining` opts per-dimension into null. Sentinel alternatives (`Long.MAX_VALUE` for "no cap") were rejected as obfuscating — boxing cost is negligible at Tier-1 call rates.
- **Validation via `IllegalStateException` with informative messages** — continues the Groups 2/3/4a/4b-earlier-decisions convention. Messages name the likely origin of the invalid value (ledger accounting bug, arithmetic underflow, caller error) to speed triage.

**Resolves open point(s):** #5

**Implication for §4.3.5.2 spec:** §4.3.5.2 now lands four records, one sealed interface with two permits, one service interface, and one enum — all in the `forvum-core` module:
- `CostBudget` record (final form from Decision 4, with its `Window window` field now bound to the sealed type from Decision 5).
- `Window` sealed interface + `DayWindow`, `SessionWindow` permits (from Decisions 5 & 6).
- `BudgetMeter` interface declaring `Usage usage(CostBudget budget)` — the read-side contract. No implementation ships in `forvum-core`; the default M5 persistence implementation issues the `SUM()` query and assembles `Usage`.
- `Usage` record with its invariant-checking canonical constructor.
- `Spend` record with signum / non-negative validation.
- `ExhaustionCause` enum with three constants.

The `BudgetMeter` **implementation** (the actual SUM query, CDI wiring against the `provider_calls` table) is explicitly **not** part of §4.3.5.2 — it lands in §5.x / M5 persistence milestone. §4.3.5.2 owns only the contract types.

**Implication for other sections:**
- **§5.4 `FallbackChatModel`:** pre-call consultation of `BudgetMeter.usage(budget)`. If `Usage.exhausted() == true`, emits `FallbackTriggered` with `reason = FallbackReasons.COST_BUDGET`; the `ExhaustionCause` from `Usage.cause()` maps onto the event's causal payload (e.g. attribute keys like `cost_budget.cause = "usd_cap_hit"` / `"token_cap_hit"` / `"both_caps_hit"`) so downstream consumers — logs, operator `/status`, dashboards — see which dimension triggered the fallback without re-querying the ledger.
- **§3.4 OpenTelemetry observability:** span attributes for `forvum.llm.call` can expose `usage.spent().usd()` and `usage.spent().tokens()` directly from an already-computed snapshot when the caller already paid for a `BudgetMeter.usage()` trip for the enforcement check — avoiding redundant SUM queries per span. No new contract surface; just a hook into the existing one.
- **#9 enforcement boundary (unblocked by this decision):** the shape of `BudgetMeter.usage()` is independent of *who* invokes it — `FallbackChatModel` decorator, engine layer, or per-provider adapter all call the same method. #9 decides only the call site, not the API. Atomic-snapshot semantics also mean whichever boundary is chosen sees a consistent read regardless of concurrency.

### Decision 8 — Enforcement boundary
**Approved:** 2026-04-22 (session-local)
**Decision:** `FallbackChatModel.chat(...)` is the enforcement point. Before dispatching to the selected `ModelProvider`, the decorator invokes `BudgetMeter.usage(budget)` on the current `CostBudget`. If `Usage.exhausted == true`, the decorator emits a `FallbackTriggered` event carrying `reason = FallbackReasons.COST_BUDGET` and the `ExhaustionCause` from the snapshot, then yields control back to the fallback-chain loop — effectively asking the chain to try the next link instead of the currently-selected provider. Behavior of the chain loop *when* the fallback fires (hard stop, free-tier substitution, escalate-to-human, etc.) is explicitly scoped to Decision 9 / open point #4 (exhaustion behavior), not to this decision.
**Rationale:**
- **Fit with `FallbackReasons.COST_BUDGET` as a first-class fallback trigger.** §4.3.2 (preserved by Decision 3) already encodes budget exhaustion as a fallback reason. A pre-call check inside `FallbackChatModel` routes exhaustion through the same "try next link" machinery as rate-limit / timeout / server-error — no novel code path for cost-driven routing.
- **Per-call granularity matches the ledger.** `provider_calls` records one row per LLM call; enforcing once per call keeps check and record on the same axis. Tool loops (§5.5) making N LLM calls in a single turn get N independent checks.
- **Minimum provider surface.** One check site covers every provider; `ModelProvider` implementations (M9–M12) remain thin SDK wrappers with no cost awareness of their own.
- **Race window, made explicit (not glossed).** Pre-call enforcement is best-effort — between the check passing and the spend being persisted, a concurrent turn can commit its own spend and push the shared ledger over the cap. The race window scales as `concurrent_calls × per_call_cost`:
  - *Typical interactive use* (1–2 parallel turns on small/medium models): overshoot bounded at ~$0.02–$0.10.
  - *Fanout scenario* (sub-agent spawn with parallel execution + heavy models like Opus, up to 10 concurrent calls at ~$0.50/call): overshoot up to ~$5.
  
  This is accepted for MVP as **best-effort enforcement**: `maxUsd` is the *intent*, not a transactional gate. Users with strict financial caps should configure a **conservative buffer** — setting `maxUsd` 5–10% below their hard limit absorbs typical fanout overshoot without meaningfully under-utilizing the budget.
- **Future mitigations documented, not promised.** Three paths exist for tightening the race window if production metrics later show the bound is exceeded in real use; none is implemented in MVP:
  - *Pre-allocation soft reservations.* Insert a "reserved" row in the ledger before dispatch, reconcile on completion or timeout. Requires extra persistence state and rollback logic — premature for Tier 1.
  - *Pessimistic SQL lock* (`SELECT … FOR UPDATE`). SQLite's lock granularity is per-database (not per-row) and serializing budget reads would bottleneck throughput; this also breaks the stateless-budget property established by Decision 2.
  - *Accept bounded overshoot and document the trade-off* — this decision's choice for MVP.
  
  Any of these can be revisited in a later milestone if telemetry shows overshoot exceeding the documented bound.

**Resolves open point(s):** #9

**Implication for §4.3.5.2 spec:** None. The contract types locked by Decisions 4, 5, 6, 7 are sufficient; §4.3.5.2 owns contracts, not enforcement flow.

**Implication for §5.4 `FallbackChatModel` spec:** §5.4 gains a paragraph describing the dispatch order inside `chat(...)`, plus a declared dependency on `BudgetMeter` (CDI-wired alongside the chain-config fields). Reference pseudocode for the order:

```
for each link in chain:
    usage = budgetMeter.usage(budget)
    if usage.exhausted:
        emit FallbackTriggered(reason = COST_BUDGET, cause = usage.cause)
        continue  // yield to chain loop; next-link behavior per Decision 9 / #4
    try:
        return link.provider.chat(...)
    catch (RetryableException e):
        emit FallbackTriggered(reason = mapToFallbackReason(e))
        continue
// chain exhausted: propagate terminal failure
```

Whether `continue` after a COST_BUDGET emission actually proceeds to the next link, short-circuits the whole chain, or triggers a different response (escalate, warn-and-continue, etc.) is the scope of Decision 9 / open point #4. This decision locks only the **trigger mechanism** (where and when the check fires, what event it emits), not the **response**.

**Implication for other sections:**
- **§3.4 OpenTelemetry observability:** the budget-check span attribute for `forvum.llm.call` can expose `Usage.spent.usd`, `Usage.spent.tokens`, and (when `exhausted`) `Usage.cause` directly from the snapshot already computed for enforcement — no extra SUM query per span. Attribute keys consistent with Decision 7's suggested mapping (`cost_budget.cause = "usd_cap_hit"`, etc.).
- **Configuration documentation (§4.1 + operator docs):** `agents/<id>.json` and `crons/<id>.json` schema notes must explicitly mention the **best-effort enforcement** trade-off — `maxUsd` is the intent, not a transactional guarantee — and include the **conservative-buffer recommendation** (5–10% below the operator's hard limit) for users with strict caps.

**Punts (deferred to Decision 9 / open point #4 — exhaustion behavior):**
- **Free-tier fallback substitution.** If a chain is configured as `[anthropic, ollama]` and the USD cap is exhausted, should the `continue` branch inside `FallbackChatModel.chat(...)` try `ollama` (which reports `NULL` cost → Decision 3 treats as zero → would not grow the exhausted USD dimension), or should exhaustion short-circuit the entire chain regardless of which providers remain? This is an **exhaustion-behavior** question (what happens *in response to* `exhausted == true`), not an **enforcement-location** question (where the check sits). Punted to Decision 9 / open point #4.
- **Primary-response semantics beyond "emit + continue".** Whether `COST_BUDGET` triggers hard stop, warn-and-continue, escalate-to-human, or per-agent-configurable behavior is also #4 territory.

### Decision 9 — Exhaustion behavior
**Approved:** 2026-04-22 (session-local)
**Decision:**
- **Primary response (Q1):** hard stop on exhaustion. Of the four inventory options for exhaustion behavior, only (α) hard stop is adopted; (β) warn+continue, (γ) escalate-to-human, and (δ) configurable-per-agent are explicitly rejected (see Rationale below).
- **Chain behavior (Q2):** short-circuit the chain on the first `COST_BUDGET` emission — `FallbackChatModel` does **not** attempt subsequent links for cost-driven exhaustion. This contrasts with retry-class `FallbackReasons` (`RATE_LIMIT`, `TIMEOUT`, `SERVER_ERROR`, etc.) where `continue` to the next link remains correct.
- **Signalling mechanism:** short-circuit is signalled by throwing a new unchecked exception, `BudgetExhaustedException`, from inside `FallbackChatModel.chat(...)`. The exception carries the `ExhaustionCause` from the `Usage` snapshot and the current `turnId`. The engine layer wraps turn execution, catches `BudgetExhaustedException`, and emits a terminal `Error` `AgentEvent` whose payload carries `code = "budget_exhausted"`, the exception's `cause`, and its `turnId`.
- **Extension door (Q2-c):** per-link cost awareness is deferred to **Group 4c** (`FallbackChain`). If Group 4c introduces a `costDims` declaration on the chain link shape, Decision 9's short-circuit default becomes overridable by chain policy — the chain would then decide whether to skip only links consuming the exhausted dimension instead of terminating entirely. This preserves compatibility: Group 4b locks the default; Group 4c can introduce the override without revisiting this decision.

Exception shape (added to the `ai.forvum.core.budget` package alongside the other budget-related types):

```java
package ai.forvum.core.budget;

public final class BudgetExhaustedException extends RuntimeException {
    private final ExhaustionCause cause;
    private final UUID turnId;

    public BudgetExhaustedException(ExhaustionCause cause, UUID turnId) {
        super("Budget exhausted: " + cause + " in turn " + turnId);
        this.cause = cause;
        this.turnId = turnId;
    }

    public ExhaustionCause cause() { return cause; }
    public UUID turnId() { return turnId; }
}
```

**Rationale:**
- **(α) hard stop over (β)/(γ)/(δ).**
  - (β) *warn+continue* defeats the purpose of a cap — a cap that logs but doesn't stop cannot enforce financial or runaway control. Rejected.
  - (γ) *escalate-to-human* requires notification-channel integration (channel plugin, operator-acknowledgment state machine) that isn't present in Tier 1. Deferred to a future milestone; not adopted as a default.
  - (δ) *configurable-per-agent* (`onCostExhaustion: "hardStop" | "warn" | "escalate"`) is speculative flexibility without evidence of demand. Per CLAUDE.md "no speculative flexibility", rejected until a real use case surfaces.
- **(Q2-b) short-circuit over (Q2-a) budget-blind retry and (Q2-c) per-link cost awareness.**
  - (Q2-a) is equivalent to (Q2-b) in *outcome* (the ledger state doesn't change between link attempts, so subsequent links fail the same `BudgetMeter.usage()` check) but noisier in the event stream. Rejected as noise with no semantic gain.
  - (Q2-c) requires enriching the `FallbackChain` link shape with per-link `costDims` — a contract change that belongs in Group 4c, not here. Scope-blocked for Group 4b; explicitly left as an extension contract for Group 4c.
- **`BudgetExhaustedException` as the signalling mechanism.**
  - *Exception matches a genuinely exceptional condition.* Budget exhaustion is a terminal failure of the turn, not a normal outcome.
  - *Magic-return alternative* (`Optional<ChatResponse>` empty, or a dedicated sentinel result) would force every caller in the path to check — the exception is unmistakable and flows to the single "right" catcher (the engine layer).
  - *Last-event-as-`FallbackTriggered`* alternative leaves the turn in an ambiguous state — no `Done`, no `Error`, just a trailing `FallbackTriggered` — which confuses renderers, CAPR processors, and log consumers. Group 1 already established `Error` `AgentEvent` as the canonical channel for hard failures; reusing it via exception catch at the engine layer preserves that contract.
- **`RuntimeException` (unchecked) over checked exception.** The exception crosses multiple layers (`FallbackChatModel` → engine → graph node). Forcing declared `throws` at each level would pollute signatures between the throw site and the single legitimate catcher (the engine). Callers other than the engine don't need to know this exception exists.
- **Package `ai.forvum.core.budget`.** Groups the cost-related contract types (`CostBudget`, `Window` + permits, `BudgetMeter`, `Usage`, `Spend`, `ExhaustionCause`, `BudgetExhaustedException`) as a discoverable unit. A refinement over the inventory's presumed top-level `ai.forvum.core` — keeps the top-level namespace reserved for truly cross-cutting records (`ModelRef`, `AgentEvent`, etc.).

**Resolves open point(s):** #4

**Implication for §4.3.5.2 spec:** `BudgetExhaustedException` is added to the package contracts, written verbatim. §4.3.5.2 now enumerates (in `ai.forvum.core.budget`):
- `CostBudget` (record)
- `Window` sealed interface + `DayWindow`, `SessionWindow` permits
- `BudgetMeter` interface
- `Usage`, `Spend` records
- `ExhaustionCause` enum
- `BudgetExhaustedException` (final `RuntimeException`)

**Implication for §5.4 `FallbackChatModel` spec:** the dispatch-order pseudocode now differentiates `COST_BUDGET` from retry-class reasons. Revised reference pseudocode:

```
for each link in chain:
    usage = budgetMeter.usage(budget)
    if usage.exhausted:
        emit FallbackTriggered(reason = COST_BUDGET, cause = usage.cause)
        throw new BudgetExhaustedException(usage.cause, currentTurnId)
        // short-circuit: NO further links attempted for cost-driven exhaustion
    try:
        return link.provider.chat(...)
    catch (RetryableException e):
        emit FallbackTriggered(reason = mapToFallbackReason(e))
        continue  // retry-class reasons (RATE_LIMIT, TIMEOUT, ...) remain "continue"
// chain exhausted on retry-class reasons only: propagate terminal failure
```

**Implication for engine layer (M6+):** the graph node wrapping turn execution catches `BudgetExhaustedException` and emits a terminal `Error` `AgentEvent` whose payload carries `code = "budget_exhausted"`, the exception's `cause` (an `ExhaustionCause`), and its `turnId`. This mapping is engine-layer contract, not part of §4.3.5.2. Note: Group 1's `Error` event shape is the target; if it does not already carry a free-form `cause` attribute, a minor Group 1 touch-up (attribute map or equivalent) will be needed — flagged here for M6+ review.

**Cross-reference to Decision 8:** the `continue` branch for `reason == COST_BUDGET` in Decision 8's reference pseudocode is reference-only and is now superseded by the throw semantics above. Decision 8 is **not** amended in the committed text (option (ii) from the alternatives presented with this decision) — readers should interpret Decision 8's pseudocode through the lens of Decision 9, which establishes the mechanical ground truth. The cost of rewriting Decision 8 for textual consistency is a diff + git-history entry for zero semantic change; the cost of this cross-reference is a single paragraph, preferred.

**Implication for other sections:**
- **Group 4c (`FallbackChain`):** if Group 4c introduces per-link cost awareness (a `costDims` declaration on the chain link shape), Decision 9's short-circuit default becomes overridable by chain policy — the chain can decide to skip only those remaining links that consume the exhausted dimension instead of terminating the chain entirely. This is an extension contract, not a retroactive change: Group 4b locks "hard stop unless chain policy overrides"; Group 4c defines the override mechanism if it opts to.
- **§3.4 OpenTelemetry observability:** the terminal `Error` event emitted by the engine should propagate `cause` as a span attribute (e.g., `budget.cause = "usd_cap_hit"`) for alerting and dashboard consumers. No new contract surface; uses the attribute mapping from Decision 7 / Decision 8.

**Rejections (on-record for future re-litigation if circumstances change):**
- (β) warn+continue — defeats the purpose of the cap.
- (γ) escalate-to-human — requires notification infrastructure absent in Tier 1.
- (δ) configurable-per-agent — speculative flexibility without evidence of demand.
- (Q2-a) try L+1 unaware — equivalent to (Q2-b) in outcome, only noisier in the event stream.

**Punts (deferred to later design rounds):**
- (Q2-c) per-link cost awareness → Group 4c. The short-circuit default of Decision 9 is compatible with a future override mechanism introduced by Group 4c's chain link shape.
- Escalate-to-human via channel notification → post-MVP milestone if demand surfaces.

### Decision 10 — Spawn inheritance
**Approved:** 2026-04-22 (session-local)
**Decision:**
- **Inheritance semantics (a):** **independent copy** — (X) from the inventory. Child's `CostBudget` equals the parent's (immutable record; passed by reference is semantically equivalent to value copy); SUM scope resolves per the child's `agent_id` via CDI context at `BudgetMeter.usage(budget)` call time. Spend is tracked independently per agent.
- **Override mechanism (b):** spawn API accepts an optional `CostBudget` parameter. Omitted / `null` → child inherits parent's `CostBudget` verbatim. Non-null → child uses the passed `CostBudget`; no partial merge, no blending with parent's values.
- **SessionWindow inheritance (c):** **prohibited** at spawn time. If the parent's `CostBudget.window` is a `SessionWindow` **and** the spawn request omits an override, the spawn path throws a new unchecked exception, `SpawnConfigurationException`, carrying parent/child agent IDs and an educational reason string.

Exception shape (added to `ai.forvum.core.budget` alongside the other budget types):

```java
package ai.forvum.core.budget;

public final class SpawnConfigurationException extends RuntimeException {
    private final String parentAgentId;
    private final String childAgentId;

    public SpawnConfigurationException(
            String parentAgentId, String childAgentId, String reason) {
        super(reason);
        this.parentAgentId = parentAgentId;
        this.childAgentId = childAgentId;
    }

    public String parentAgentId() { return parentAgentId; }
    public String childAgentId() { return childAgentId; }
}
```

Expected educational message (constructed by the spawn path when throwing, with `<childId>` and `<parentId>` substituted):

> "Cannot inherit SessionWindow-scoped budget into spawn — child agent '<childId>' has different identity than parent '<parentId>'. SessionWindow filters by (sessionId, agentId), so inheriting verbatim would cause child's spend to be invisible to its budget enforcement. Provide an explicit CostBudget override in the spawn request, scoped to the child's identity (e.g., DayWindow for general caps, or SessionWindow with child's own session)."

**Rationale:**
- **(X) independent copy over (Y) shared reference and (Z) proportional carve-out.**
  - *(Y) shared reference* requires either a new `Window` permit expressing lineage scope (`LineageWindow(rootAgentId)` or equivalent) OR a schema change adding `budget_id` / `parent_agent_id` to `provider_calls`. Both are out of Group 4b's scope — they belong to Group 4c or a later schema-expansion milestone. Rejected here; documented as a compatible future extension path.
  - *(Z) proportional carve-out* introduces a "ratio" knob absent from the inventory, requires spawn-time snapshot arithmetic, and has no articulated demand. Complexity without demand. Rejected.
  - *(X)* mutates no locked decision, changes no schema, and yields child-scoped tracking naturally because the `Window` permits' SQL filters already scope by `agent_id` — either via CDI context (`DayWindow`) or via the explicit `agentId` field (`SessionWindow`, handled separately by sub-decision (c)).
- **All-or-nothing override over partial merge.**
  - *Partial merge* has ambiguous null semantics: an override with `maxUsd = null` — is that "opt out of USD for the child" or "inherit parent's `maxUsd`"? Two interpretations cannot coexist in one API. Rejected.
  - *All-or-nothing* keeps override semantics obvious: present → use it, absent → inherit. Users wanting partial adjustments construct the full `CostBudget` explicitly, referencing parent's immutable record. CLAUDE.md "no abstractions for single-use code" rules out a builder / `withUsd(...)` surface as speculative flexibility.
- **Prohibit-SessionWindow-inheritance over document-as-degenerate and smart-inheritance.**
  - *Document-as-degenerate* creates a silent bug: the inherited `SessionWindow` points at parent's `(sessionId, agentId)`, the child's calls carry a different `agent_id`, the SUM never sees them, the child has effectively infinite budget — with no warning at spawn time. For an OSS project, a pitfall of "you configured a cap and it silently doesn't apply" is exactly what design must prevent, not defer to documentation. Rejected.
  - *Smart inheritance* (auto-reconstruct `SessionWindow` with child identity at inheritance time) was rejected for two reasons: (i) it introduces magic behavior — sets a precedent that "inherit" can silently mutate fields, a surprising transformation for immutable records; (ii) the caller who chose `SessionWindow` almost certainly wants a *specific* `sessionId` for the child (a fresh one? the turn's session? the parent's session again for chained context?), which the spawn mechanism cannot infer — so auto-reconstruction picks an arbitrary `sessionId` that may or may not match intent.
  - *Prohibit* surfaces the configuration error at spawn time (development time — first test run) via an educational exception, not weeks later when a cloud-provider bill arrives. Cost: ~5–10 lines in the spawn path. Benefit: bug is impossible rather than documented. Net positive by a wide margin for an OSS project targeting safe-by-default.
- **Exception placement in `ai.forvum.core.budget`.** The exception arises from a *budget configuration* invariant (`SessionWindow`'s `(sessionId, agentId)` fields are identity-pinned; inheritance can't reconcile with a child of different identity). A contributor debugging "a budget-related problem" finds all budget exceptions co-located with the budget types. The spawn path is the thrower, but the invariant is budget-owned — so the type lives with the invariant, not with the thrower.
- **`RuntimeException` (unchecked).** Consistent with `BudgetExhaustedException` from Decision 9 — crosses multiple layers (spawn invoker → engine → graph node), the only legitimate catcher is the engine, and unchecked avoids `throws` declaration pollution through the intermediate layers.

**Resolves open point(s):** #6

**Implication for §4.3.5.2 spec:** `SpawnConfigurationException` is added to the `ai.forvum.core.budget` package contracts, written verbatim. The complete §4.3.5.2 package surface (cumulative across Decisions 4, 5, 6, 7, 9, 10):
- `CostBudget` (record)
- `Window` sealed interface + `DayWindow`, `SessionWindow` permits
- `BudgetMeter` interface
- `Usage`, `Spend` records
- `ExhaustionCause` enum
- `BudgetExhaustedException`, `SpawnConfigurationException` (both final `RuntimeException`)

**Implication for §5.5 `spawn_worker` spec:** §5.5 gains (i) an explicit `CostBudget` override parameter on the spawn API signature and (ii) validation logic in the spawn pseudocode. Reference pseudocode for the validation:

```
function spawn(spec, budgetOverride):
    effectiveBudget = (budgetOverride != null) ? budgetOverride : spec.parent.costBudget
    if effectiveBudget.window() instanceof SessionWindow && budgetOverride == null:
        throw new SpawnConfigurationException(
            spec.parent.agentId,
            spec.childAgentId,
            <educational message per Decision 10>)
    // ... proceed with spawn construction using effectiveBudget
```

Behavioral nuance: the `SessionWindow` prohibition fires only when the parent has a `SessionWindow`-based budget **and** the caller omitted the override. A caller that explicitly passes a `SessionWindow`-based override (with the child's own `(sessionId, agentId)`) is allowed — explicit intent is respected.

**Implication for engine layer (M6+):** the graph node wrapping spawn execution catches `SpawnConfigurationException` and emits a terminal `Error` `AgentEvent` whose payload carries `code = "spawn_invalid_config"` plus the exception's message (`getMessage()`), `parentAgentId`, and `childAgentId`. This surfaces the configuration error through the same `Error` channel as `BudgetExhaustedException`'s `code = "budget_exhausted"` (Decision 9), maintaining a uniform failure-reporting contract across budget-driven failures.

**Implication for other sections:**
- **§4.1 config schema / operator documentation:** `agents/<id>.json` and `crons/<id>.json` schema notes should mention that using `SessionWindow` on a parent agent requires each spawn to provide an explicit `CostBudget` override — the spawn will fail at the spawn boundary (not silently) if this is forgotten. The exception message is designed to be self-directing; the docs reinforce.
- **Group 4c (`FallbackChain`):** unaffected by this decision. #6 is budget-scoped; chain topology is orthogonal.

**Rejections (on-record):**
- (Y) shared reference — requires new `Window` permit OR schema change, out of Group 4b scope; documented as future extension via a `LineageWindow`-style permit in Group 4c or later.
- (Z) proportional carve-out — complexity without articulated demand.
- Partial merge override — ambiguous null semantics; speculative flexibility.
- Document-as-degenerate `SessionWindow` inheritance — creates silent bug that violates the `CostBudget` "configured cap enforces calls" invariant; OSS projects must prevent such pitfalls in design, not in documentation.
- Smart inheritance (auto-reconstruct `SessionWindow` with child identity) — magic behavior, sets precedent for transformations on inherit, and doesn't address that the caller likely wants a specific `sessionId` that the spawn mechanism cannot infer.

**Punts (deferred to later design rounds):**
- (Y) shared-reference inheritance → Group 4c or later, via a new `Window` permit (e.g., `LineageWindow(rootAgentId)`) that scopes SUMs across agent lineages. Compatible with Decision 10's default of (X): inheritance still copies the `CostBudget`, but if the copied `Window` is a `LineageWindow`, child's SUM reuses the same root-agent scope as parent's, yielding shared-pool semantics without special casing the inheritance step.

*Template for future entries:*

```
### Decision N — <topic>
**Approved:** YYYY-MM-DD (session-local)
**Decision:** <one-sentence summary>
**Rationale:** <why this choice over alternatives>
**Resolves open point(s):** #N [, #M ...]
**Implication for §4.3.5.2 spec:** <what lands in the plan text / code>
**Implication for other sections:** <cross-refs that need updates>
```
