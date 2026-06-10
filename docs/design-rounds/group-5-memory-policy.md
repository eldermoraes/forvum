# DR-5 — Group 5: `MemoryPolicy` contract

**Status:** DELIBERATED — pending maintainer sign-off.
**Issue:** #63 · **Labels:** `design`, `core`, `context-engineering` · **Milestone:** Design & Contracts.
**Scope of authority:** the `forvum-core` `MemoryPolicy` record shape, the `MemoryProvider` retrieval
method it drives, `<retrieved_memory>` data-block framing (DR-6a §9 point 5, referenced), the
pre-memory-write `OutputFilter` boundary (DR-6a §9 point 2c, referenced), spawn inheritance alongside
`CostBudget`/`Identity`.
**Materializes:** `docs/ULTRAPLAN.md` §4.3.6 (replaces the `*TBD (Group 5)*` marker).
**Unblocks:** P2-5 #30 (memory-host SDK reference impl), DR-8 (the `memoryPolicy` field of `AgentSpec`).

> This is a DRAFT round write-up for sign-off. Open decisions are flagged `[DP-n]` for the maintainer to
> ratify or amend. Boundaries this round does **not** own are referenced, not redefined: DR-6a owns the
> §9 threat model + `OutputFilter`/`ToolExecutor` enforcement seam; DR-4c owns `FallbackChain`; DR-8 owns
> the `AgentSpec` composition that holds a `MemoryPolicy` field.

---

## 1. Context

`MemoryPolicy` is already a committed name with three live obligations in the spec — but no shape:

1. It is listed in the `forvum-core` type roster (§2.1 line 69, §4.3.6 file list line 1262) as a `*TBD*`
   placeholder, deliberately left un-invented until this round (per the BR note at ISSUES.md "Predating
   `*TBD*` markers").
2. It is **inherited at spawn** alongside `CostBudget` and `Identity` (§5.5 `AgentRegistry.spawn`,
   §5.1 line 1149).
3. It is the **Compress governance knob** the `reduce` node reads: "When the combined size exceeds the
   agent's `MemoryPolicy` compression threshold, it routes the merge through the small-and-fast model"
   (§5.5 `reduce`, line 1176; CONTEXT-ENGINEERING-MAPPING "Compress — Write-time summarization").
4. It is the **Select-pillar retrieval scope** governing read-back from the three M5 memory tiers:
   "each tier is read back on the next turn with its own retrieval scope governed by the agent's
   `MemoryPolicy` (§4.3.6)" (§4.2 line 413). The `MemoryProvider` SPI (§2.2) "lets implementations
   choose vector, graph, metadata, or hybrid retrieval without coupling the agent to a strategy."

Today the SPI side is a pure discovery marker: `forvum-sdk`'s `sealed interface MemoryProvider permits
AbstractMemoryProvider` exposes only `extensionId()`. The retrieval method was explicitly deferred to
this round ("The retrieval method ... is settled by DR-5", `MemoryProvider.java` Javadoc). DR-5 settles
both halves: the **policy record** (Layer 0) and the **retrieval method** (Layer 1) it drives.

The three tiers already exist in the M5 SQLite schema (§4.2): `messages` (short-term conversational),
`episodic_memory` (procedural: observation/decision/reflection), `semantic_memory` (long-term embedded
facts).

---

## 2. The settled contract (summary)

`MemoryPolicy` is a flat, pure-data Layer-0 record in `ai.forvum.core` (co-located with `ModelRef`,
mirroring the `CostBudget` precedent of "pure data record; the read-side lives on a service"). It carries
the **retrieval scope** (Select) and the **compression threshold** (Compress) for one agent. It governs
read-back but persists nothing; the SQLite tiers are authoritative, exactly as `provider_calls` is
authoritative for `CostBudget`.

```java
// Module: forvum-core · Package: ai.forvum.core
public record MemoryPolicy(
    RetrievalStrategy strategy,    // vector | graph | metadata | hybrid | none
    Set<MemoryTier> tiers,         // which of {MESSAGES, EPISODIC, SEMANTIC} to read back
    int topK,                      // max hits returned across the selected tiers
    double minScore,               // similarity floor in [0.0, 1.0]; 0.0 = no floor
    int compressThresholdChars     // Compress knob: serialized size above which reduce summarizes
) { /* canonical-constructor validation, §4.3.6 */ }

public enum RetrievalStrategy { VECTOR, GRAPH, METADATA, HYBRID, NONE }
public enum MemoryTier        { MESSAGES, EPISODIC, SEMANTIC }
```

The SPI method `MemoryProvider` gains (Layer 1, `forvum-sdk`, Quarkus-free, no reactive — blocking on a
virtual thread):

```java
// Module: forvum-sdk
List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy);
```

driven by two further Layer-0 records:

```java
// Module: forvum-core · Package: ai.forvum.core
public record MemoryQuery(
    String agentId,            // tenant key — never crosses agents (Isolate)
    String sessionId,          // nullable: session-scoped tiers narrow to it; null = cross-session
    String text                // the retrieval cue (current user turn / sub-question)
) { /* validation */ }

public record MemoryHit(
    MemoryTier tier,           // which tier this hit came from
    String content,            // the retrieved text — DATA, framed as <retrieved_memory> (§7)
    double score,              // relevance in [0.0, 1.0]; 1.0 for non-scored (metadata) hits
    String source              // free-form provenance (semantic_memory.source, or tier+id)
) { /* validation */ }
```

A default `MemoryProvider` (linear-scan over the M5 tiers, the `sqlite-vec`-free MVP path of §4.2 line
415) lands in **P2-5 #30** — DR-5 makes the contract concrete and implementable; it does not ship the
implementation.

---

## 3. `MemoryPolicy` — field rationale

| Field | Type | Pillar | Role |
|---|---|---|---|
| `strategy` | `RetrievalStrategy` | Select | which retrieval algorithm the provider applies; the agent does not couple to one (§2.2) |
| `tiers` | `Set<MemoryTier>` | Select | which of the three M5 tiers (§4.2) are in scope for read-back |
| `topK` | `int` | Select | hard cap on hits returned across selected tiers — keeps the prompt block small |
| `minScore` | `double` | Select | similarity floor; hits below it are dropped (irrelevant memory is noise) |
| `compressThresholdChars` | `int` | Compress | serialized-size threshold above which `reduce` (§5.5) routes through the small-and-fast model |

- **Flat record, not a sealed hierarchy.** The four retrieval strategies differ only in the algorithm
  the *provider* runs, not in the *policy's* schema — every strategy needs the same `tiers`/`topK`/
  `minScore`/`threshold` knobs. A sealed `VectorPolicy`/`GraphPolicy`/… hierarchy would import form
  without the justifying condition, exactly the reasoning that kept `CostBudget` flat (§4.3.5.2
  Decision 1/4). `strategy` is an enum field, not a subtype. `[DP-1]`
- **`minScore` is a `double` in `[0.0, 1.0]`, normalized.** Providers normalize their native distance
  metric (cosine, L2, BM25, graph-walk depth) into a `[0,1]` relevance so the policy's floor is
  provider-independent. `minScore = 0.0` means "no floor". `[DP-2]`
- **`compressThresholdChars` is a character count, not tokens.** Character length is computable without a
  tokenizer (native-clean, no model round-trip to *decide whether* to compress) and the `reduce` node
  already speaks in serialized size (§5.5 "combined size exceeds the ... threshold"). A token-based knob
  would couple the policy to the embedding/tokenizer model. `[DP-3]`
- **`strategy = NONE` is a first-class "memory off" value.** An agent that should *write* but never
  *read back* (or a deterministic test agent) sets `NONE`; the engine then skips the retrieve call
  entirely. This avoids a nullable `MemoryPolicy` on the agent spec (DR-8) — every agent has a policy;
  "no retrieval" is a value, not absence. `[DP-4]`

### 3.1 Validation invariants (canonical constructor, `IllegalStateException`)

Following the `ModelRef`/`CostBudget` convention — `IllegalStateException` with triage-oriented,
origin-naming messages (config file `agents/<id>.json`, vs. a programmatic construction bug):

- `strategy != null`.
- `tiers != null`; defensively copied to an unmodifiable `EnumSet`; **may be empty only when
  `strategy == NONE`** (a non-`NONE` strategy with no tiers retrieves nothing — a config mistake worth
  surfacing). `[DP-5]`
- `topK >= 0` (0 is legal and means "retrieve nothing this turn", paired typically with `NONE`).
- `0.0 <= minScore <= 1.0`.
- `compressThresholdChars >= 0` (0 = "always compress"; a deliberate, if aggressive, choice).

A package-level default factory (`MemoryPolicy.defaults()`) supplies the config-absent value so the M5
config loader and DR-8 `AgentSpec` parse have a single source of truth — see §5. `[DP-6]`

### 3.2 Why `forvum-core` / `ai.forvum.core` (not a sub-package)

`MemoryPolicy`, `MemoryTier`, `RetrievalStrategy`, `MemoryQuery`, and `MemoryHit` are small and cohesive;
unlike the budget surface (which earned its own `ai.forvum.core.budget` package for the `Window`/`Usage`/
`Spend`/`BudgetMeter` cluster of seven types), the memory surface is five plain records/enums with no
service interface inside core. They sit directly in `ai.forvum.core` alongside `ModelRef`. `[DP-7]`

### 3.3 Native registration (mandatory)

All five new core types are JSON-serialized (they ride in `agents/<id>.json` and cross the SPI as
return values) and **must** be registered for native reflection. Per §6.3 / CLAUDE.md §5, Layer-0 records
do **not** carry `@RegisterForReflection` (core bans `io.quarkus*`); they are appended to the single
engine holder `forvum-engine/.../persistence/CoreReflectionRegistration.java` — adding
`MemoryPolicy.class, RetrievalStrategy.class, MemoryTier.class, MemoryQuery.class, MemoryHit.class` to its
`targets`. This is an implementation obligation for the milestone that lands the records (P2-5 #30 or its
prelude), called out here so it is not forgotten.

The `MemoryProvider` retrieval method, `MemoryQuery`, and `MemoryHit` are also a fresh non-Forvum SPI
surface; `forvum-sdk` already depends on `forvum-core`, so the SPI compiles against the new core records
with no new dependency and no SDK enforcer change (the SDK enforcer governs only the `ai.forvum:*`
namespace).

---

## 4. The `MemoryProvider` retrieval method

```java
// forvum-sdk/src/main/java/ai/forvum/sdk/MemoryProvider.java
public sealed interface MemoryProvider permits AbstractMemoryProvider {

    String extensionId();

    /**
     * Retrieve the memory relevant to {@code query}, scoped and ranked per {@code policy}.
     * Returns at most {@code policy.topK()} hits, each at or above {@code policy.minScore()},
     * drawn only from {@code policy.tiers()}. The engine frames every returned hit as a
     * {@code <retrieved_memory>} DATA block (DR-6a §9) before it enters the prompt window.
     *
     * <p>Blocking, run on a virtual thread (no reactive types — §3.8). Implementations must
     * confine retrieval to {@code query.agentId()} (Isolate). A {@code policy.strategy() == NONE}
     * is short-circuited by the engine and never reaches this method.
     */
    List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy);
}
```

- **Signature: `(MemoryQuery, MemoryPolicy) -> List<MemoryHit>`.** Query carries *what to retrieve about*
  (the cue text + the agent/session tenant keys); policy carries *how/where/how-much* (strategy, tiers,
  caps, floor). Splitting them keeps the policy reusable across turns (it is agent config) while the query
  changes every turn. `[DP-8]`
- **Blocking on a virtual thread, no reactive.** Consistent with §3.8 (VT-first; reactive only at a
  framework-mandated boundary). The SDK stays Quarkus-free and reactive-free; a provider doing network IO
  (Qdrant, Redis) blocks on its VT. `[DP-9]`
- **The engine short-circuits `NONE`.** When `strategy == NONE` (or `topK == 0`) the engine skips the
  call; the provider never sees a "retrieve nothing" request. Keeps every provider implementation simple.
- **No streaming.** Retrieval is a bounded `topK` set, materialized as a `List`; streaming a fixed small
  result set buys nothing and complicates the SPI.
- **Caching / proxyability.** The provider bean follows the existing `@AgentScoped` recipe (CLAUDE.md
  §14 [M7]) when its impl is per-agent; `MemoryPolicy` is read at call time, not injected — the engine
  passes the active agent's policy. P2-5 #30 owns the bean wiring; DR-5 fixes only the method shape.

---

## 5. Spawn inheritance (mirror `CostBudget`/`Identity`)

`MemoryPolicy` is inherited at spawn exactly like `CostBudget` and `Identity` (§5.5, §5.1 line 1149: "The
child inherits its parent's `CostBudget`, `MemoryPolicy`, and `Identity` unless the spawn request
overrides them"):

- **Inherited copies are immutable records; reference-passing is value-equivalent** (the `CostBudget`
  Decision 10 reasoning — an immutable record reference is a value copy).
- **Override is all-or-nothing.** The spawn API accepts an optional `MemoryPolicy`: absent ⇒ inherit
  verbatim; present ⇒ replace entirely. No partial merge — same readability argument as `CostBudget`
  (§4.3.5.2 Decision 10). `[DP-10]`
- **No `SessionWindow`-style spawn hazard.** `CostBudget` needs `SpawnConfigurationException` because a
  `SessionWindow` filters by the *parent's* `(sessionId, agentId)` and would silently unbound a child.
  `MemoryPolicy` carries **no parent-bound scope** — the tenant key (`agentId`) is supplied per-call via
  `MemoryQuery`, resolved from the child's `@AgentScoped` context, so a verbatim-inherited policy reads
  the *child's* memory automatically. **No new exception type is needed for memory inheritance.** A
  narrowing override is a deliberate operator choice (e.g. a worker that should read only `SEMANTIC`),
  never a silent footgun. `[DP-11]`
- **Isolate boundary unchanged.** A spawned worker retrieving against its own `agentId` cannot read the
  parent's `messages`/`episodic_memory`; the `reduce` node (§5.5) remains the *only* place a parent
  ingests worker output, and only as a compressed digest.

---

## 6. Compress governance role (the `reduce` knob)

`compressThresholdChars` is the field §5.5's `reduce` node consumes: when merged worker output (or an
oversized retrieved-memory block, or a large tool result per CONTEXT-ENGINEERING-MAPPING "Compress") would
exceed the threshold, `reduce` routes it through the small-and-fast proxy model (default `qwen3:1.7b`) for
a structured summarization pass before it re-enters the context window. DR-5 does **not** redesign
`reduce` (§5.5 / M18 own it); it only fixes the *name and type* of the knob `reduce` already references,
resolving the previously-dangling "`MemoryPolicy` compression threshold" forward-reference.

The same threshold governs write-time summarization of oversized **retrieved memory** before it is
persisted back (the Compress pillar's "only the compressed digest is persisted"). `[DP-12]`

---

## 7. Retrieval framing as `<retrieved_memory>` DATA blocks (DR-6a §9 point 5 — referenced)

DR-6a's §9 Security section establishes the **data/instruction framing boundary**: model-external content
that re-enters the prompt must be framed as DATA, not instructions, to contain prompt injection. DR-6a
§9 point 5 names **retrieved memory** specifically. DR-5 binds its retrieval output to that boundary:

- Every `MemoryHit.content` the engine receives from `MemoryProvider.retrieve(...)` is wrapped in a
  `<retrieved_memory>...</retrieved_memory>` block (with provenance from `MemoryHit.source`) before it is
  assembled into the prompt window. It is **never** spliced into the system/instruction region.
- This matters because `semantic_memory` and `episodic_memory` rows can contain text the model itself
  authored on a prior, possibly-poisoned turn — so retrieved memory is an untrusted-content surface, not
  a trusted instruction surface.
- DR-5 does **not** define the §9 framing mechanism (block syntax, escaping, the threat model) — that is
  DR-6a's contract. DR-5 only **declares that retrieval output flows through it.** `[DP-13]`

---

## 8. Pre-memory-write `OutputFilter` boundary (DR-6a §9 point 2c — referenced)

DR-6a §9 point 2c reserves **pre-memory-write** as one of the three `OutputFilter` hook layers
(pre-tool-call / pre-channel-emit / pre-memory-write). DR-5 owns *what MemoryPolicy means at that
boundary*, not the filter mechanism:

- The write path that persists into `episodic_memory`/`semantic_memory` passes the candidate content
  through the **pre-memory-write `OutputFilter`** (DR-6a §9.2) *before* the row is inserted. A filter trip
  (secret/PII redaction, or block) prevents a sensitive value from being durably stored and later
  re-retrieved into a prompt — closing the loop with §7's untrusted-retrieval surface.
- **`MemoryPolicy` does not configure the filter.** The `OutputFilter` policy is DR-6a's surface;
  `MemoryPolicy` governs *retrieval* (read-back), the filter governs *write*. DR-5's only assertion is
  that the **write boundary exists and is the pre-memory-write hook**, so that what `MemoryProvider`
  later retrieves was already filtered on the way in. The interaction is: *filter on write (DR-6a) →
  store → retrieve under policy (DR-5) → frame as `<retrieved_memory>` (DR-6a §9 point 5).* `[DP-14]`
- If DR-6a's trip outcome ultimately includes a `FallbackReasons.FILTERED` token, a *blocked* memory
  write is a no-op persist (the turn proceeds; the memory simply is not stored), distinct from a blocked
  channel emit. DR-5 flags this seam for DR-6a to confirm; it does not pre-decide it. `[DP-15]`

---

## 9. Demo deferral D2 — resolution

`demo/conference-mvp`'s `docs/design-rounds/demo-mvp-deferrals.md` **D2** records that the MVP `AgentSpec`
is an ad-hoc record `(String id, String systemPrompt, ModelRef primaryModel)`, and lists the missing
fields it will need "when AgentSpec gets a full design round (likely Group 5 or later, when MemoryPolicy
+ PermissionScope references land)", including `memoryPolicy (MemoryPolicy)`.

DR-5 **dissolves the `MemoryPolicy`-type half of D2's gap**: the type now has a settled shape, so the
`memoryPolicy` field of a future `AgentSpec` has something concrete to hold. The *composition* of
`AgentSpec` itself (adding the field, the `agents/<id>.json` schema, the `primaryModel → fallbackChain`
rename) remains **DR-8's** job (ISSUES.md D2 → DR-5/DR-8). *[Resolved by DR-8, 2026-06-09: composition settled as an additive optional `fallbackModels` list composed engine-side — NO `fallbackChain` field and NO `primaryModel` rename/migration; see `group-8-agentspec-composition.md` + ULTRAPLAN §4.3.8.]* DR-5 therefore marks D2's memoryPolicy
sub-gap **resolved** and hands the residual AgentSpec assembly to DR-8 with a concrete type in hand.

---

## 10. Decision points for sign-off

| # | Decision | Draft position |
|---|---|---|
| `[DP-1]` | Flat record vs. sealed per-strategy hierarchy | **Flat**; `strategy` is an enum field (mirrors `CostBudget` Decision 1/4) |
| `[DP-2]` | `minScore` as normalized `double` in `[0,1]` | **Yes**; providers normalize native metric → `[0,1]` |
| `[DP-3]` | Compress threshold in chars vs. tokens | **Chars** (native-clean, tokenizer-free, matches §5.5) |
| `[DP-4]` | `NONE` strategy as first-class "memory off" | **Yes**; avoids a nullable policy on the spec (DR-8) |
| `[DP-5]` | Empty `tiers` legal only when `strategy == NONE` | **Yes** (non-`NONE` + empty = config mistake → throw) |
| `[DP-6]` | `MemoryPolicy.defaults()` factory as single config-absent source | **Yes** (one source for M5 loader + DR-8) — **confirm default values** |
| `[DP-7]` | Package `ai.forvum.core` (not a `…memory` sub-package) | **Yes** (5 small types, no service iface — unlike budget) |
| `[DP-8]` | Method shape `(MemoryQuery, MemoryPolicy) → List<MemoryHit>` | **Yes**; query = per-turn, policy = agent config |
| `[DP-9]` | Blocking on VT, no reactive, SDK stays Quarkus-free | **Yes** (§3.8) |
| `[DP-10]` | Spawn override all-or-nothing (no partial merge) | **Yes** (mirrors `CostBudget` Decision 10) |
| `[DP-11]` | **No** new spawn exception for `MemoryPolicy` inheritance | **None needed** (no parent-bound scope; tenant key is per-call) |
| `[DP-12]` | One `compressThresholdChars` for both `reduce` merge and retrieved-memory write-back | **Shared** (single Compress knob) |
| `[DP-13]` | Retrieval output framed as `<retrieved_memory>` (DR-6a §9 pt 5) | **Bound to DR-6a's boundary** (DR-5 declares flow, not mechanism) |
| `[DP-14]` | Pre-memory-write filter is DR-6a's surface; `MemoryPolicy` governs read only | **Yes** (clean read/write split) |
| `[DP-15]` | A *blocked* memory write is a silent no-op persist (turn proceeds) | **Proposed**; defer final word to DR-6a |

---

## 11. Open issues / dependencies

- **Default field values for `MemoryPolicy.defaults()` (`[DP-6]`)** need a maintainer call. Draft
  proposal: `HYBRID`, `tiers = {MESSAGES, EPISODIC, SEMANTIC}`, `topK = 8`, `minScore = 0.0`,
  `compressThresholdChars = 8000`. These are starting numbers, not load-bearing — baseline against the
  per-turn performance gates (§11) once P2-5 #30 has a real provider.
- **DR-6a must land first** for §7/§8 to bind to a real §9 (ISSUES.md dependency
  `DR-6a → {DR-4c, DR-5, …}`). DR-5 references §9 by forward-reference exactly as the spec already does
  ("see §9 once it lands"); the §7/§8 framing/boundary statements hold regardless of DR-6a's internal
  shape.
- **`MemoryProvider.retrieve(...)` is implemented by P2-5 #30**, not by this round. P2-5 must also append
  the five core types to `CoreReflectionRegistration` (§3.3) and run a native parity test on a retrieval
  round-trip.
- **`reduce` (§5.5 / M18)** consumes `compressThresholdChars`; no §5.5 rewrite here, only the knob's
  name/type is now concrete.
- **DR-8** consumes the settled `MemoryPolicy` to add the `memoryPolicy` field to `AgentSpec` and define
  the `agents/<id>.json` schema (resolving the rest of demo D2).
