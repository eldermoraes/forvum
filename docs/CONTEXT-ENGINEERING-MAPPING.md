# Context Engineering for Low Latency Agents

This document maps the principles in [`CONTEXT-ENGINEERING.md`](CONTEXT-ENGINEERING.md) onto Forvum's architecture, citing the section of [`docs/ULTRAPLAN.md`](ULTRAPLAN.md) that owns each design point.

**Status & source of truth.** The whole M1–M20 roadmap and Phase-2 (v0.5, OpenClaw parity) have **shipped** (released as `v0.5.0`); Phase-3 (v1.0+) is next. [`docs/ULTRAPLAN.md`](ULTRAPLAN.md) is the normative design source of truth (its preamble carries the full precedence model); this file maps that design onto the four pillars. Because the design out-aims the as-built runtime in a few places, claims below are qualified with an inline marker:

- **`[shipped]`** — wired into normal turns and enforced in `v0.5.0`.
- **`[partial]`** — present in code but not the full design intent.
- **`[planned]`** — designed, scheduled for v1.0+ (§7.3).
- **`[gap → #NNN]`** — the running code diverges from the stated design; the owning issue is linked.

Anything stated as delivered or enforced without a marker matches `v0.5.0` behavior. The mapping is organized around the sections of the source document.

---

## Premises → Forvum's foundational stance

| Premise | How Forvum embodies it |
|---|---|
| **Context is the differentiator** | The Tier-1 contract specifications (`ULTRAPLAN.md §4.3`) spend more effort specifying *contracts that move information* — `AgentEvent` permits (§4.3.2), the `Window` scopes (§4.3.5.2), the `Usage` snapshot (§4.3.5.2) — than choosing models. Models are pluggable behind `ModelRef` (§4.3.5.1); context flow is not. |
| **Context-as-a-Compiler** | An agent's `AgentSpec` (system prompt + LLM chain + tool list + memory policy; §5.2) is the "compilation unit". The runtime assembles the compilation context per turn — system prompt, history, retrieved memory, tool specs — and the LLM acts as the compiler. The `ai.forvum.core.budget` package (§4.3.5.2) treats this compilation as a measured, governed step. |
| **Context window as RAM** | The `Window` sealed interface (§4.3.5.2) makes the temporal boundary of context an explicit, type-safe concept. `DayWindow` and `SessionWindow` are domain types the type system enforces, not configuration knobs, preventing nonsensical scope combinations. |
| **Optimized context is probabilistic and deterministic** | The `provider_calls` ledger (§4.2) records every interaction deterministically, while the agent runtime treats LLM outputs as probabilistic and routes them through `FallbackChain` policies (§5.4). Determinism lives at the persistence layer; probability lives at the inference layer; the boundary is explicit. |

---

## Challenges → How Forvum is designed to absorb them

| Challenge | Forvum's design response |
|---|---|
| **Computational cost vs. latency** | The `CostBudget` contract (§4.3.5.2) treats cost and tokens as first-class architectural concerns. Per-agent and per-cron budgets opt in per dimension (USD-only, tokens-only, or both), with hard-stop enforcement via `BudgetExhaustedException` (**`[gap → #169]`** the budgets are parsed and carried, but the hard-stop is not yet enforced at runtime). Cost-driven exhaustion short-circuits the fallback chain (§5.4) instead of cascading through expensive retries. |
| **"Context Rot" and position bias** | `MemoryPolicy` (§4.3.6) and the three memory tiers (§4.2) keep the working context small and relevant (**`[gap → #175]`** the tiers exist in the schema, but the per-turn write/read-back is not wired into normal turns by default). The design separates short-term conversational memory (`messages`) from episodic (`episodic_memory`) and semantic (`semantic_memory`) layers, each read back on the next turn under its own retrieval scope — keeping each layer small to avoid the "lost in the middle" problem. |
| **Context failures and collapse** | The `@AgentScoped` CDI context (§5.1) provides hermetic isolation per agent: each agent has its own beans, memory, tools, and `ScopedValue<UUID> CURRENT_TURN` propagation. Sub-agents spawned via `registry.spawn(parentId, childSpec)` (§5.2) get independent contexts — no shared mutable state, no context cross-contamination. |

---

## What must be done → Forvum's four pillars

The source document calls out **Write, Select, Compress, Isolate** as the methodological pillars. Forvum's architecture is organized around them; §2.7 owns the pillar → module mapping.

### Write — governing state with scratchpads and memory

Forvum's persistence layer (§4.2) is the agent's external memory:

- `messages` — short-term conversational history, queryable per session.
- `episodic_memory` — observation/decision/reflection events.
- `semantic_memory` — embedded long-term facts, retrieved by a pure-Java linear cosine scan (the `sqlite-vec` `vec0` virtual table was evaluated and **declined** — no Maven artifact + a second native C library would breach the native mandate; #50 / Risk #2, §4.2).
- `tool_invocations` — auditable record of every tool call with status and latency.
- `provider_calls` — denormalized cost ledger per LLM call.
- `capr_events` — judge verdicts correlated by `turn_id`.

The three memory tiers are the Write pillar's tiered scratchpad surface (§4.2): reasoning state never depends solely on the prompt window. The design writes to the appropriate tier between turns and reads from it on the next turn, governed by the agent's `MemoryPolicy` (§4.3.6). **`[gap → #175]`** as-built, a default install exercises only the short-term `messages` tier per turn — `recordFact` has no production caller and `MemorySelector` wires only the Qdrant backend, so episodic/semantic write-back + read are not yet on the normal turn path. User-editable files under `~/.forvum/` carry the complementary Write surface users edit directly (§4.1, §2.7).

### Select — high-precision filtering

- `MemoryProvider` SPI (`forvum-sdk`, §2.2) lets implementations choose vector, graph, metadata, or hybrid retrieval without coupling the agent to a strategy. **`[partial → #175]`** the SPI ships and a Qdrant backend implements it, but retrieval is not wired into normal turns by default; iterative/agentic RAG is **`[planned → #196]`** (v1.0+).
- `Window` permits scope budget aggregation by session or day, keeping `SUM` aggregations bounded (§4.3.5.2); `agent_id` on every operational row keeps per-agent queries bounded (§4.2).
- Tool filtering (§5.3): the agent's `AgentToolBelt` intersects the global `ToolRegistry` against the agent's `allowedTools` globs, so the LLM sees only the relevant subset — Select applied to capability.
- The `forvum-core` module keeps domain types pure and DB-agnostic, so retrieval logic can evolve in `forvum-engine` without contaminating the contract surface.

### Compress — contextual compression at write time

- **Write-time summarization (§3.3, §5.5).** Tool results and retrieved memory whose serialized size exceeds the agent's `MemoryPolicy` threshold are summarized through the small-and-fast model (default Ollama `qwen3:1.7b`) before re-entering the context window; only the compressed digest is persisted. This applies at every write-back from the MVP (the `reduce` node, §5.5), not only as the Phase-3 proxy-model middleware (§7.3 item 8). **`[gap → #176]`** the summarization path ships, but a compression *failure* currently falls back to reinserting the raw text (fail-open) instead of bounding it; within-turn pruning of oversized tool results/images is **`[planned → #197]`** (v1.0+).
- **SQL-mirror enums** — `Role`, `EventType`, `InvocationStatus` (§4.3.3) compress textual classifications into typed values, persisted as `TEXT` for portability but consumed as enums in Java.
- **`AgentEvent` permits** (`TokenDelta`, `ToolInvoked`, `ToolResult`, `FallbackTriggered`, `Done`, `ErrorEvent`; §4.3.2) — structured event records that capture exactly what observability needs, with no narrative bloat.
- **`Usage` snapshot** (§4.3.5.2) — a single SQL aggregation, read through the `BudgetMeter` service, populates all four fields (`spent`, `remaining`, `exhausted`, `cause`) in one trip; consumers never recompute.

### Isolate — hermetic context separation

This is Forvum's most assertive design area:

- **`@AgentScoped` custom CDI context** (§5.1) — every per-agent bean (`AgentMemory`, `AgentToolBelt`, `AgentChatModel`, `AgentGraph`) resolves to instances keyed by the active `AgentId`. Two concurrent agents see two independent universes of beans. The context is an ArC `InjectableContext` registered at build time so it survives native compilation. **`[gap → #177]`** as-built, ephemeral spawned workers currently leak this `@AgentScoped` state instead of releasing it at turn end.
- **`ScopedValue<AgentId> CURRENT_AGENT` and `ScopedValue<UUID> CURRENT_TURN`** (§5.1) — virtual-thread-safe identity propagation that survives continuations without `InheritableThreadLocal`'s pitfalls.
- **Spawn isolation with `SpawnConfigurationException`** (§4.3.5.2, §5.5) — the design prohibits silent inheritance of a `SessionWindow`-scoped parent budget into a child without a child-specific override, surfacing the misconfiguration at spawn time rather than letting it produce silently unbounded sub-agent spend.
- **`reduce` as the sole merge boundary** (§5.5) — only a compressed digest crosses the orchestrator→worker boundary, never a worker's raw window, which prevents sibling context clash and stops a poisoned worker output from injecting into the parent.

---

## How it should be done → Forvum's architectural patterns

| Recommended pattern | Forvum's realization |
|---|---|
| **Orchestrator-Workers (Hub-and-Spoke)** | The main agent plus spawned sub-agents (§5.2, §5.5) is exactly this. `registry.spawn(parentId, childSpec)` creates an isolated worker with its own `@AgentScoped` context, memory, and tool belt. The supervisor `StateGraph` coordinates; workers run in parallel on virtual threads (§3.8). |
| **Pipeline of compression with proxy models** | `FallbackChain` (§4.3.5.3; consumed by `FallbackChatModel`, shipped at M8, §5.4) enables chains where cheaper models handle initial passes and escalate only when needed. Because `ModelRef` (§4.3.5.1) is config-driven, a chain like `[ollama:qwen3:1.7b → anthropic:claude-haiku → anthropic:claude-opus]` is a config-time decision, not a code change. |
| **Use of "small and fast" models for sub-steps** | `forvum-app` is the only assembly that knows concrete providers; `forvum-engine` operates on `ChatModel` abstractions (§2.3). The `route` and `reduce` nodes (§5.5) default to a local Ollama model for classification and summarization while a larger model handles user-facing generation, with no engine-layer change. |
| **Cyclic frameworks (LangGraph)** | The supervisor-workers topology is a LangGraph4j `StateGraph` in `forvum-engine` (§3.3, §5.5). Nodes (`route`, `generate`, `tool_loop`, `spawn_worker`, `worker_run`, `reduce`) and conditional edges keyed on the `AgentEvent` type provide the unified state-management skeleton the source recommends. |

---

## What can't be missing → Forvum's three foundations

### Strict state isolation (sandbox)

Covered above under **Isolate**. The `@AgentScoped` context + `ScopedValue` propagation + spawn-time validation (§5.1, §4.3.5.2, §5.5) is the most architecturally invested area of the contract work. It shaped the choice of Java 25 (for the final `ScopedValue` API, JEP 506), of CDI custom contexts over alternative DI strategies, and the `ai.forvum.core.budget` package organization that co-locates budget enforcement with the isolation guarantees.

### Governance, permissions, and security (Guardrails)

- **`PermissionScope` enum** (§4.3.4) — every tool declares the capability scopes it requires; the engine's `ToolExecutor` enforces them before invocation. Denied calls are logged to `tool_invocations.status = 'denied'` for audit (§4.2, §4.3.4).
- **Identity files** (`identities/<id>.json`, §4.1, §5.3) — separate user/role identities from agent specs, resolved at channel-message entry and carried on a `ScopedValue<Identity>`. Sub-agents inherit identity and cannot override it across the spawn boundary (§5.3).
- **Per-agent allowed-tools sets** (§5.3) — agents only see tools their config permits, preventing cross-agent capability leakage.
- **Network isolation by default** — the local-first design keeps the user's data on their machine unless they explicitly configure cloud providers.

### Operational traceability and observability

- **`AgentEvent` stream** (§4.3.2) — every agent action emits a typed event with an `Instant timestamp()`. Channels, observability, and CAPR judging consume the same stream.
- **`provider_calls` ledger** (§4.2) — every LLM call records provider, model, tokens in/out, cost, latency, and an `is_fallback` flag. A single `GROUP BY` query surfaces agents whose primary model is unreliable.
- **`turn_id` propagation** (§4.3.1, §4.2 V2) — one UUID generated client-side at turn start, propagated via `ScopedValue<UUID> CURRENT_TURN`, ties together every `messages`, `tool_invocations`, `provider_calls`, and `capr_events` row for that turn. One query reconstructs the full reasoning path.
- **OpenTelemetry integration** (§3.6) — four span kinds (`forvum.agent.turn`, `forvum.llm.call`, `forvum.tool.call`, `forvum.graph.run`) make the Write/Select/Compress/Isolate boundaries observable per turn, with `Usage` snapshots feeding span attributes. **`[shipped]`** (P2-15) — **off by default**: the SDK activates only when `OTEL_EXPORTER_OTLP_ENDPOINT` is set, so a default run pays zero telemetry overhead.

---

## Success metrics → Forvum's instrumentation strategy

| Metric domain | Forvum's instrumentation |
|---|---|
| **Process-based metrics** | The `AgentEvent` stream (§4.3.2) plus `capr_events` (§4.2) capture the reasoning trajectory. CAPR (Cost-Aware Pass Rate) is a first-class table (§4.2) and a computed aggregate (§3.6), not a metric bolted on afterward. Tool-call pertinence is queryable via the `tool_invocations.status` distribution per agent. |
| **Operational metrics** | `provider_calls` records latency per call, and the `Usage` snapshot (§4.3.5.2) exposes spent/remaining at any point. OpenTelemetry spans (§3.6) add throughput and end-to-end latency. The `BudgetExhaustedException` flow (§4.3.5.2, §5.4) is the design for cost-aware degradation (**`[gap → #169]`** the hard-stop is parsed but not yet enforced at runtime). |
| **Outcome-based metrics** | `capr_events` records judge verdicts (`passed`, `judge_model`, `rationale`) per `turn_id` (§4.2) — the LLM-as-a-Judge pattern materialized as schema. Faithfulness and answer relevance can be computed by post-hoc queries over `messages` joined with `capr_events` on `turn_id`. |

---

## Closing note

The design described here is documented in `docs/ULTRAPLAN.md`; this file maps it onto the four Context Engineering pillars. The full M1–M20 roadmap and Phase-2 (v0.5, OpenClaw parity) have shipped (released as `v0.5.0`); the principles in `CONTEXT-ENGINEERING.md` shaped the architecture from the first commit. Where the as-built runtime still diverges from a pillar's design intent, the `[gap → #NNN]` markers above link the open issue closing that gap, and the v1.0+ pillar-sharpening arc is `docs/ULTRAPLAN.md` §7.3.
