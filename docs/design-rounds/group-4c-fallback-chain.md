# DR-4c — Group 4c: `FallbackChain` contract

**Status:** SETTLED — wave-ratified decisions applied (wave directive 2026-06-09); newly settled points
flagged for maintainer review.
**Issue:** #62 · **Labels:** `core`, `design-round` · **Milestone:** Design Rounds.
**Scope of authority:** the `forvum-core` `FallbackChain` record shape, the `FailureClass` taxonomy
disposition (incl. the DR-6a `Filtered` handover, §9.2.2 [DP-6]), the chain-traversal semantics the M8
decorators honor, the per-link `costDims` / `LineageWindow` doors reserved by Group 4b, and the
adaptive-routing (P3-4 #52) interaction contract.
**Materializes:** `docs/ULTRAPLAN.md` §4.3.5.3 (replaces the `*TBD (Group 4c)*` marker).
**Unblocks:** DR-8 (the spec-side fallback declaration — resolved there as an optional `fallbackModels`
list, the chain composed engine-side; no key migration), the CostBudget
enforcement e2e (seam named, PR-9 of this superwave), P3-4 #52 (the authority-set contract the router
reorders).

> This round CONFIRMS what M8 built and names what stays deferred (the DR-6a lesson). Boundaries it does
> **not** own are referenced, not redefined: §4.3.5.2 owns `CostBudget`/`BudgetMeter`/the Decision-8/9
> enforcement; DR-6a §9 owns the `OutputFilter` contract and the `FILTERED` token's egress path; DR-8
> owns the spec composition (resolved there as an optional `fallbackModels` list; the chain is composed
> engine-side — no `fallbackChain` JSON field). Decisions ratified by the maintainer's
> standing wave authorization are marked **Ratified (wave directive 2026-06-09)**; points settled fresh
> here are marked **Settled — flagged for maintainer review**.

---

## 1. Context

`FallbackChain` is a committed name with live obligations but no shape — §4.3.5.3 is literally
`*TBD (Group 4c)*`:

1. It is in the `forvum-core` type roster (§2.1 line 69, M2 file manifest §7.1) as a predating
   placeholder; `Persona` deliberately omits it ("the chain field arrives with `FallbackChain`
   (§4.3.5.3, DR-4c)", §4.3.7; `Persona.java` Javadoc: "pending `FallbackChain`, DR-4c").
2. The runtime side **already shipped at M8** and has been live through M9–M12 and v0.1:
   - `FallbackChatModel` (`forvum-engine/.../model/FallbackChatModel.java`) walks a
     `List<FallbackLink>`: per-link try → one `provider_calls` row per attempt (`is_fallback = 1` past
     the first) → on failure `FailureClassifier.shouldFallback` decides advance-or-rethrow, emitting one
     `FallbackTriggered` per hop.
   - `FallbackStreamingChatModel` is the streaming twin: it advances only if no partial tokens reached
     the user handler (a started stream is committed).
   - `FailureClass` (`forvum-engine/.../model/FailureClass.java`) is a **sealed interface** (not an
     enum) `permits Retryable, NonRetryable, Unknown`, with constants
     `RETRYABLE`/`NON_RETRYABLE`/`UNKNOWN`; `Unknown.isRetryable() == false` (never silently retried).
   - `FailureClassifier` keys on LangChain4j's typed exceptions: `classify()` on
     `RetriableException`/`NonRetriableException` base types; `shouldFallback()` rethrows only
     request-level failures (`InvalidRequestException`, or a raw `HttpException` with a request-level
     4xx); `reason()` maps to `FallbackReasons.RATE_LIMIT`/`TIMEOUT`/`SERVER_ERROR`, else `null`.
   - `FallbackLink` (`(ModelRef ref, ChatModel chat, StreamingChatModel streaming)`) is the
     engine-local resolved-runtime link, explicitly awaiting this round ("the public
     `forvum-core.FallbackChain` is still TBD", its Javadoc).
   - `LlmSelector` (`forvum-engine/.../routing/LlmSelector.java`) builds **single-link** chains only
     ("This cycle the chain has a single link", its Javadoc) — `Persona` has no fallbacks field yet.
3. `FallbackTriggered(Instant, ModelRef failed, ModelRef next, String reason)` exists in
   `forvum-core/.../event/FallbackTriggered.java` (§4.3.2); `reason` is a `FallbackReasons` String —
   the once-proposed migration to `FailureClass` was **declined at M8** (§4.3.2, §7.1 M8 as-built
   note 2). `FallbackReasons` on `main` carries `RATE_LIMIT`/`TIMEOUT`/`SERVER_ERROR`/`COST_BUDGET`;
   `FILTERED` is specified by §9.2.2 (DR-6a) and lands with P2-OUTPUTGUARD #48.
4. DR-6a §9.2.2 [DP-6] hands this round one question: a distinct `FailureClass.Filtered` permit, or a
   fold into `NonRetryable` (the non-retryable classification itself is already fixed).
5. Group 4b (§4.3.5.2) reserved two doors for this round: per-link `costDims` (the Decision-9
   short-circuit override) and the `LineageWindow` interplay.

DR-4c settles the **config-time core record**, the **`FailureClass` disposition**, and the doors —
confirming the M8 runtime as the traversal contract rather than inventing a second one.

---

## 2. The settled contract (summary)

`FallbackChain` is a flat, pure-data Layer-0 record in `ai.forvum.core` (co-located with `ModelRef`,
§4.3.5.1). It declares one agent's (or one cron's) ordered model preference at **config time**; the
**runtime** form stays the M8 engine-local `FallbackLink` list — at materialization the engine resolves
each `ModelRef` against `ModelProvider` beans and hands the decorators the resolved links.

```java
// Module: forvum-core · Package: ai.forvum.core
public record FallbackChain(
    ModelRef primary,           // the model the turn fronts — operator preference, order tiebreak
    List<ModelRef> fallbacks    // tried in declaration order on provider-level failure; empty = none
) {
    /** primary + fallbacks in traversal order — the #52 router's authority set. */
    public List<ModelRef> links() { ... }

    /** The no-fallback chain (the M8/LlmSelector single-link case). */
    public static FallbackChain single(ModelRef primary) { ... }
}
```

No new SPI surface: `ModelProvider.resolve(ModelRef)` already covers per-link resolution. No schema
change: `provider_calls.is_fallback` (V1, M5) already ledgers hops. The record lands with its first
consumer (the DR-8 composition + the multi-link `LlmSelector` mapping), per the M7
type-lands-in-consumer-PR pattern.

---

## 3. Record shape and validation

- **`[DP-1]` Shape: `FallbackChain(ModelRef primary, List<ModelRef> fallbacks)`.**
  *Options:* (a) two-field record; (b) the older `(primary, List<fallback>, CostBudget)` sketch
  (§5.4 / ISSUES.md); (c) a single `List<ModelRef>` with the head as primary.
  *Decision:* (a). `primary` is semantically distinct (the operator's preference, the router tiebreak,
  the `Persona.primaryModel` successor); a bare list erases that. The budget is excluded per `[DP-2]`.
  **Ratified (wave directive 2026-06-09).**
- **`[DP-2]` No `CostBudget` field — the `(primary, fallbacks, budget)` sketch is amended.**
  *Decision:* the chain carries models only. `Persona` (§4.3.7, merged at M2) already carries
  `costBudget`, and the cron spec carries its own; a second budget on the chain would create two config
  sites with ambiguous precedence for the same agent. The Decision-8 pre-call check (§4.3.5.2) receives
  the budget *alongside* the chain at decorator construction — never inside it. Follows directly from
  the ratified shape. **Ratified (wave directive 2026-06-09).**
- **`[DP-3]` Validation invariants (canonical constructor, `IllegalStateException`, origin-naming —
  the §4.3.5.x convention).**
  - `primary != null`.
  - `fallbacks != null` (empty list = "no fallback" — the single-link chain; use `single(...)`),
    defensively copied immutable, no null elements.
  - **Duplicates rejected**: the primary repeated in `fallbacks`, or a ref repeated within `fallbacks`
    (exact `ModelRef` equality), is a config mistake in `agents/<id>.json`/`crons/<id>.json` — an
    immediate same-request re-attempt of an identical link cannot succeed where the attempt just
    failed; deliberate same-link retry/backoff is a different, deferred knob.
  - Messages name the likely origin (config file vs. programmatic bug), the `ModelRef`/`CostBudget`
    idiom.
  **Settled — flagged for maintainer review.**

Composition into the agent/cron spec is **DR-8's** — coordinated here, not duplicated: DR-8 settles an
optional `fallbackModels` list composed engine-side with `primaryModel` (no `fallbackChain` JSON field,
no key migration — additive and backward compatible); the `single(primaryModel)` factory remains the
no-fallback bridge for every existing single-model spec.

---

## 4. `FailureClass` taxonomy and the `Filtered` handover

- **`[DP-4]` No `Filtered` permit — the DR-6a handover folds into `NonRetryable`.**
  *Options (per §9.2.2 [DP-6]):* (a) add a fourth sealed permit `Filtered`; (b) fold into
  `NonRetryable`, keep telemetry distinction on the reason token.
  *Decision:* (b), and the as-built sealed set (`Retryable`/`NonRetryable`/`Unknown`, engine-local in
  `ai.forvum.engine.model`, a sealed **interface** with record permits — not an enum) is confirmed
  unchanged. Rationale:
  1. **A permit would have zero call sites.** The DR-6a trip fires at the pre-channel-emit egress seam
     (`OutputFilteredException`, §9.2.4) *after* the chain returned — it never transits
     `FailureClassifier.classify`. As-built, `classify()` has no production caller at all (tests only);
     the live advance axis is `shouldFallback` (`[DP-6]`).
  2. **Provider-side filtering is already covered**: LangChain4j's `ContentFilteredException` extends
     `InvalidRequestException` → classifies `NON_RETRYABLE` *and* does not fall through — the
     security-correct behavior (re-sending filtered content to another provider is filter evasion, the
     DR-6a containment-by-structure stance).
  3. **Nothing is lost**: the `FallbackReasons.FILTERED` token keeps the telemetry distinction — the
     §4.3.2 two-axis separation (retry axis vs. reason token) reaffirmed at M8 does the work.
  4. **The door stays open**: adding a permit later breaks exhaustive switches at consumer sites, which
     is the intended extensibility surface (the `Window` precedent, §4.3.5.2 Decision 5).
  The non-retryable *classification* of a filter trip is pre-fixed by DR-6a + the wave directive; the
  fold-vs-permit choice is this round's. **Settled — flagged for maintainer review.**
- **`[DP-5]` `FILTERED` spelling and reach.** `FallbackReasons.FILTERED = "filtered"` (§9.2.2; verified
  absent from `FallbackReasons.java` on `main` — it is additive and lands with P2-OUTPUTGUARD #48; the
  spelling is `Filtered`/`FILTERED`, never `Filter`/`Censored`/`Masked`). It is emitted only on the
  DR-6a egress short-circuit (`FallbackTriggered(reason = FILTERED)` from the engine's turn boundary,
  §9.2.4) — **never as a chain-hop reason**: `FailureClassifier.reason()` does not and will not return
  it, because a filter trip never advances the chain. **Ratified (DR-6a §9.2.2 coordination + wave
  directive 2026-06-09).**
- The §4.3.2 "line-477" migration path (`String reason → FailureClass`) stays **declined at M8** —
  confirmed, not reopened (§4.3.2; §7.1 M8 as-built note 2). The two axes remain separate: `FailureClass`
  = engine-local retry axis; `reason` = user-facing `FallbackReasons` telemetry token.

---

## 5. Chain traversal semantics (M8 as-built, confirmed)

- **`[DP-6]` The advance axis is `shouldFallback` (request-level vs. provider-level), not
  `isRetryable()`.** Per link: attempt → ledger one `provider_calls` row (`is_fallback = 1` past the
  first) → on `RuntimeException`, `FailureClassifier.shouldFallback(e)` decides:
  - **Provider-level** faults fall through to the next link: rate limit, timeout, 5xx, **auth**,
    model-not-found, connection, **unknown** — the next provider may succeed where this one cannot
    (different credentials, different infrastructure).
  - **Request-level** faults rethrow immediately: `InvalidRequestException` (incl.
    `ContentFilteredException`), or a raw `HttpException` with a request-level 4xx (every 4xx except
    401/403/404/408/429, mirroring LangChain4j's `ExceptionMapper`) — a malformed request fails on
    every provider alike, so trying another wastes a call.
  This **supersedes the §5.4 prose sketch** ("stop on non-retryable (bad credentials …)") — the as-built
  is deliberately stronger: auth failures advance. `FailureClass.isRetryable()` stays the
  retry/telemetry signal, unused for the advance decision (its Javadoc says exactly this).
  **Settled — flagged for maintainer review** (as-built confirmation; §5.4 prose amended to match).
- **`[DP-7]` Ledger + event semantics per hop.** One `provider_calls` row per *attempt* (success or
  failure, with latency and the failing exception recorded); one `FallbackTriggered(timestamp, failed,
  next, reason)` per *hop* (emitted via the decorator's `Consumer<AgentEvent>`), where `reason` is the
  `FallbackReasons` token from `classifier.reason(e)` and is **nullable** when no token fits (auth,
  connection, unknown today). Enriching the token set (e.g. `AUTH`, `CONNECTION`) is additive and
  deferred — not invented here. The exhausted-chain case rethrows the last failure; the decorator never
  swallows a terminal error. Streaming: a link is committed once partial tokens reached the user
  handler — fallback happens only on pre-first-token failures. **Settled (as-built confirmation) —
  flagged for maintainer review.**

---

## 6. Adaptive-routing interaction (P3-4 #52)

- **`[DP-8]` The declared chain is the router's authority set.** The future CAPR-driven router
  (§7.3 item 4, P3-4 #52) is a **deterministic blended-score** function that may **reorder** and
  **drop** persona-declared links only:
  - input = `FallbackChain.links()` (ordered, primary-first) + the rolling CAPR snapshot;
  - output = a derived link order over a non-empty subset of `links()` — it must keep at least one
    link and must **never invent** a provider/model outside the declared chain;
  - declared order is the deterministic tiebreak; `primary` keeps its "operator preference" meaning.
  The record itself stays routing-agnostic — `links()` is the entire contract surface #52 needs; the
  router lives in `forvum-engine/.../routing/` and produces the `List<FallbackLink>` handed to the
  decorators. **Ratified (wave directive 2026-06-09).**

---

## 7. The Group-4b doors: per-link `costDims` and `LineageWindow`

- **`[DP-9]` Per-link `costDims`: declined for v0.5.** Links stay bare `ModelRef`s; the Decision-9
  `COST_BUDGET` short-circuit stays **non-overridable** (`BudgetExhaustedException` ends the chain —
  no free-tier substitution). The door stays open exactly as §4.3.5.2 reserved it: a future round may
  widen links into per-link records carrying `costDims`, making the short-circuit overridable by chain
  policy — a mechanical, additive-at-the-config-level enrichment. Declining now follows the
  simplicity-first rule: no consumer of `costDims` exists, and #52's router gets cost-awareness from
  CAPR data, not static per-link declarations. **Settled — flagged for maintainer review.**
- **`[DP-10]` `LineageWindow` interplay: none to define — stays reserved.** Because the chain carries
  no budget (`[DP-2]`), there is **no surface on `FallbackChain`** for a `LineageWindow(rootAgentId)`
  to interact with; it remains a reserved future `Window` permit of `CostBudget` (§4.3.5.2), touching
  the chain only through the same Decision-8 pre-call seam every window does. **Settled — flagged for
  maintainer review.**

---

## 8. `BudgetExhaustedException` / `COST_BUDGET` seam (named, not built)

- **`[DP-11]` The budget pre-call seam.** Per §4.3.5.2 Decisions 8/9: `FallbackChatModel` invokes
  `BudgetMeter.usage(budget)` **before the link loop**; exhaustion emits
  `FallbackTriggered(reason = FallbackReasons.COST_BUDGET)` and throws `BudgetExhaustedException` —
  no link is attempted; the engine catches it and emits the terminal `ErrorEvent`
  (`code = "budget_exhausted"`). The budget reaches the decorator **alongside** the chain (a
  constructor collaborator, like `FailureClassifier`/`ProviderCallRecorder`), never inside the record.
  **As-built today the decorator performs no budget check** (verified: no `BudgetMeter` reference in
  `FallbackChatModel`/`LlmSelector`; `BudgetMeter`/`PanacheBudgetMeter`/`BudgetExhaustedException`
  exist unconsumed by the chain) — the wiring + e2e is the CostBudget enforcement package (PR-9 of
  this superwave), not DR-4c's. DR-4c only fixes that the seam is pre-loop and chain-external.
  **Settled (names the seam only) — flagged for maintainer review.**

---

## 9. Location, native registration, landing

- **`[DP-12]` `ai.forvum.core`, engine-registered reflection, lands with its first consumer.**
  - Package: `ai.forvum.core`, co-located with `ModelRef` — one small record with no service interface
    in core, so no sub-package (the DR-5 DP-7 reasoning); it does **not** join `ai.forvum.core.budget`
    (it carries no budget).
  - Native: the record rides `agents/<id>.json`/`crons/<id>.json`, so it is appended to the engine
    `CoreReflectionRegistration` holder (§6.3) in the landing PR — never `@RegisterForReflection` in
    core (core bans `io.quarkus*`).
  - Landing: with the DR-8 composition + the multi-link `LlmSelector` change (mapping
    `FallbackChain.links()` → resolved `List<FallbackLink>` via `ModelProvider.resolve` per link) —
    the M7 type-lands-in-consumer-PR pattern. No decorator constructor change is needed: `FallbackLink`
    remains the runtime form (amending M8's "one constructor adapts" forecast — the adapter is a
    mapping, not a constructor).
  - Tests at landing: property-style canonical-constructor tests (`@ParameterizedTest` + seeded random,
    the §10 parser/record mandate) — null/empty/duplicate matrices, `links()` order, `single()`
    equivalence.
  **Settled — flagged for maintainer review.**

---

## 10. M8 as-built declines (recorded, not reopened)

What the shipped fallback machinery deliberately does **not** do today:

1. **Multi-link chains** — `LlmSelector` builds `List.of(link)`; `Persona` has no fallbacks field.
   Every production chain is single-link until DR-8's composition lands. (`LlmSelector.resolve`)
2. **Budget pre-call** — no `BudgetMeter` check in the decorator (seam named in `[DP-11]`; wiring is
   PR-9).
3. **`FallbackTriggered` listener** — `LlmSelector` passes `onEvent = null` (no-op consumer); harmless
   today (a single-link chain cannot hop). The turn's event sink is threaded in with multi-link
   selection.
4. **Streaming in production** — `FallbackStreamingChatModel` has no production caller;
   `FallbackLink.streaming` is `null` on the `LlmSelector` path. The contract is specified and tested;
   wiring is a channel-streaming follow-up.
5. **Same-link retry/backoff** — the chain advances, never re-attempts a link; in-link retry policy is
   a deferred knob (`Retryable` is a telemetry signal, not a retry loop).
6. **The `chat(ChatRequest, ChatRequestOptions)` overload** — undecorated (LangChain4j 1.13+ path);
   documented override point in both decorators' Javadoc.
7. **`reason` tokens for auth/connection/unknown** — `classifier.reason()` returns `null`; token
   enrichment is additive and deferred (`[DP-7]`).
8. **`FallbackTriggered.reason → FailureClass` migration** — declined at M8, confirmed declined here
   (§4.3.2).

---

## 11. Decision points

| # | Decision | Position | Ratification |
|---|---|---|---|
| `[DP-1]` | Record shape | `FallbackChain(ModelRef primary, List<ModelRef> fallbacks)` | Ratified (wave directive 2026-06-09) |
| `[DP-2]` | `CostBudget` field | **Excluded** — budget rides persona/cron config, supplied alongside the chain | Ratified (wave directive 2026-06-09) |
| `[DP-3]` | Validation + helpers | Non-null/immutable/no-duplicates; `links()`; `single()` | Settled — flagged for maintainer review |
| `[DP-4]` | `Filtered` permit (DR-6a §9.2.2 [DP-6]) | **Fold into `NonRetryable`** — no fourth permit; sealed set unchanged | Settled — flagged for maintainer review (non-retryable classification itself: ratified) |
| `[DP-5]` | `FILTERED` spelling/reach | `FallbackReasons.FILTERED = "filtered"` (lands with #48); egress-only, never a chain-hop reason | Ratified (DR-6a coordination + wave directive) |
| `[DP-6]` | Advance axis | `shouldFallback` (request-level rethrows; provider-level incl. auth advances) — as-built confirmed, §5.4 prose amended | Settled — flagged for maintainer review |
| `[DP-7]` | Ledger/event per hop | `provider_calls` row per attempt; `FallbackTriggered` per hop; `reason` nullable; streaming commits a started stream | Settled — flagged for maintainer review |
| `[DP-8]` | #52 router contract | `links()` = authority set; reorder/drop only; ≥1 link; never invent; declared order = tiebreak | Ratified (wave directive 2026-06-09) |
| `[DP-9]` | Per-link `costDims` | Declined v0.5; Decision-9 short-circuit stays non-overridable; door open | Settled — flagged for maintainer review |
| `[DP-10]` | `LineageWindow` interplay | None — stays a reserved `Window` permit; no chain surface | Settled — flagged for maintainer review |
| `[DP-11]` | Budget seam | Pre-link-loop `BudgetMeter.usage` per Decisions 8/9; chain-external; wiring + e2e = PR-9 | Settled (seam named only) — flagged for maintainer review |
| `[DP-12]` | Location/native/landing | `ai.forvum.core`; `CoreReflectionRegistration`; lands with DR-8 composition + multi-link `LlmSelector` | Settled — flagged for maintainer review |

---

## 12. ULTRAPLAN sync

Applied with this round (anchors verified verbatim on the worktree `docs/ULTRAPLAN.md`):

- **§4.3.5.3** — `*TBD (Group 4c).*` replaced with the settled contract (shape, validation, `Filtered`
  fold, traversal as-built, #52 authority set, declined doors, native/landing notes).
- **§1 (differentiators)** — `FallbackChain(primary, fallbacks, budget)` → `(primary, fallbacks)`
  (the budget field is amended out, `[DP-2]`).
- **§4.3.5.2 reserved paths** — `LineageWindow` "(Group 4c or later)" → deferred by DR-4c, still
  reserved; per-link `costDims` "if Group 4c enriches…" → declined for v0.5, door open.
- **§4.3.7** — `(§4.3.5.3, DR-4c)` → `(§4.3.5.3, settled by DR-4c)` on the `Persona` chain-field note.
- **§5.4** — the `(primary, List<fallback>, CostBudget)` sketch and the "stop on non-retryable (bad
  credentials…)" sentence amended to the as-built contract (`[DP-2]`, `[DP-6]`).
- **§7.1 M8 as-built note (3)** — "still TBD … one constructor adapts" → settled; the adapter is the
  `links()` → `FallbackLink` mapping in `LlmSelector`, landing with DR-8.
- **§9.2.2** — "DR-4c will settle / owns whether" → resolved: folded into `NonRetryable`, no fourth
  permit; DR-6a's [DP-6] marker updated to record the resolution.

## 13. Open issues / dependencies

- **DR-8** consumes the settled type: an optional `fallbackModels` spec list composed engine-side with
  `primaryModel` (no `fallbackChain` JSON field, no key migration — additive and backward compatible;
  `FallbackChain.single` remains the no-fallback bridge), and the `agents/<id>.json` schema. The multi-link `LlmSelector` wiring (and threading a real
  `onEvent` sink) lands there or in its implementation package.
- **PR-9 (CostBudget e2e)** wires the `[DP-11]` pre-call seam (`BudgetMeter.usage` →
  `BudgetExhaustedException` → terminal `ErrorEvent`) and proves it end-to-end.
- **P2-OUTPUTGUARD #48** adds `FallbackReasons.FILTERED` and the egress enforcement (DR-6a §9.2);
  nothing in this round blocks it.
- **P3-4 #52** implements the `[DP-8]` router against `links()` (v1.0+; depends on CAPR data, P3-10).
- **Deferred, not invented:** same-link retry/backoff policy; `reason` token enrichment (auth,
  connection); per-link `costDims`; `LineageWindow`; streaming-path production wiring.
