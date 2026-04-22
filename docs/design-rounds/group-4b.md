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
