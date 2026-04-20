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

*Empty until decisions are approved. Format to follow when entries land:*

```
### Decision N — <topic>
**Approved:** YYYY-MM-DD (session-local)
**Decision:** <one-sentence summary>
**Rationale:** <why this choice over alternatives>
**Resolves open point(s):** #N [, #M ...]
**Implication for §4.3.5.2 spec:** <what lands in the plan text / code>
**Implication for other sections:** <cross-refs that need updates>
```
