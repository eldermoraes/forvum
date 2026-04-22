# Forvum × Context Engineering — Mapping

This document maps the principles in [`CONTEXT-ENGINEERING.md`](CONTEXT-ENGINEERING.md) onto Forvum's architecture and roadmap, showing where each principle is already realized in the design, where it's reflected in the in-progress implementation, and how the platform is intentionally structured to honor them as the milestones land.

The mapping is organized around the five sections of the source document.

---

## Premises → Forvum's foundational stance

| Premise | How Forvum embodies it |
|---|---|
| **Context is the differentiator** | The Tier 1 design round (`docs/design-rounds/group-4b.md`) spent more effort specifying *contracts that move information* — `AgentEvent` permits, `Window` scopes, `BudgetMeter` snapshots — than choosing models. Models are pluggable behind `ModelRef`; context flow is not. |
| **Context-as-a-Compiler** | `AgentSpec` (system prompt + model + future memory policy + future tool list) is the "compilation unit" for an agent. The runtime assembles the compilation context per turn — system prompt, history, retrieved memory, tool specs — and the LLM acts as the compiler. The `ai.forvum.core.budget` package treats this compilation as a measured, governed step. |
| **Context window as RAM** | The `Window` sealed interface (§4.3.5.2) makes the temporal boundary of context an explicit, type-safe concept. `DayWindow` and `SessionWindow` are not configuration knobs — they are domain types that the type system enforces, preventing nonsensical scope combinations. |
| **Optimized context is probabilistic and deterministic** | Forvum's `provider_calls` ledger (§4.2) records every interaction deterministically, while the agent runtime treats LLM outputs as probabilistic and routes them through `FallbackChain` policies. Determinism lives at the persistence layer; probability lives at the inference layer; the boundary is explicit. |

---

## Challenges → How Forvum is designed to absorb them

| Challenge | Forvum's design response |
|---|---|
| **Computational cost vs. latency** | The `CostBudget` contract (§4.3.5.2) treats cost and tokens as first-class architectural concerns, not afterthoughts. Per-agent and per-cron budgets, opt-in per dimension (USD-only, tokens-only, or both), with hard-stop enforcement via `BudgetExhaustedException`. Cost-driven exhaustion short-circuits the fallback chain instead of cascading through expensive retries. |
| **"Context Rot" and position bias** | `MemoryPolicy` (Group 5, in design) and the agent-scoped memory architecture are explicitly scoped to keep the working context small and relevant. The plan deliberately separates short-term conversational memory from episodic and semantic memory layers, each with its own retrieval policy — preventing the "lost in the middle" problem by keeping each layer small and focused. |
| **Context failures and collapse** | The `@AgentScoped` CDI context (§5.1) provides hermetic isolation per agent: each agent has its own beans, memory, tools, and `ScopedValue<UUID> CURRENT_TURN` propagation. Sub-agents spawned via `AgentRegistry.spawn()` get independent contexts by default — no shared mutable state, no context cross-contamination. |

---

## What must be done → Forvum's four pillars

The source document calls out **Write, Select, Compress, Isolate** as the methodological pillars. Forvum's architecture is organized around them.

### Write — governing state with scratchpads and memory

Forvum's persistence layer (§4.2) is purpose-built as the agent's external memory:

- `messages` — short-term conversational history, queryable per session.
- `episodic_memory` — observation/decision/reflection events, captured asynchronously.
- `semantic_memory` — vector-embedded knowledge with the `sqlite-vec` extension (planned for V3 migration).
- `tool_invocations` — auditable record of every tool call with status and latency.
- `provider_calls` — denormalized cost ledger per LLM call.
- `capr_events` — judging outcomes correlated by `turn_id`.

Reasoning state never depends solely on the prompt window. The agent runtime writes to the appropriate memory layer between turns and reads from it on the next turn.

### Select — high-precision filtering

- `MemoryProvider` SPI (planned, §2.2) lets implementations choose between vector retrieval, graph retrieval, metadata filtering, or hybrid strategies — without coupling the agent to a specific approach.
- `Window` permits scope memory queries by agent, session, or day, ensuring the SUM aggregations and lookups stay bounded.
- The `forvum-core` package keeps domain types pure and DB-agnostic, so retrieval logic can evolve in `forvum-engine` without contaminating the contract surface.

### Compress — contextual compression at write time

- `Status`, `EventType`, `Role` — SQL-mirror enums that compress textual classifications into typed values, persisted as `TEXT` for portability but consumed as enums in Java.
- `AgentEvent` permits (`TokenDelta`, `ToolInvoked`, `ToolResult`, `FallbackTriggered`, `Done`, `ErrorEvent`) — structured event records that capture exactly what observability needs, with no narrative bloat.
- `Usage` snapshot from `BudgetMeter` (§4.3.5.2) — a single SQL aggregation produces all four fields (`spent`, `remaining`, `exhausted`, `cause`); consumers never recompute.

### Isolate — hermetic context separation

This is where Forvum's design is most assertive:

- **`@AgentScoped` custom CDI context** (§5.1) — every per-agent bean (memory, tool belt, chat model, graph) resolves to instances keyed by the active `AgentId`. Two concurrent agents see two independent universes of beans.
- **`ScopedValue<AgentId> CURRENT_AGENT` and `ScopedValue<UUID> CURRENT_TURN`** — virtual-thread-safe identity propagation that survives continuations without `InheritableThreadLocal`'s pitfalls.
- **Spawn isolation with `SpawnConfigurationException`** — the design explicitly prohibits silent inheritance of `SessionWindow`-scoped budgets across spawn boundaries (Decision 10 in `group-4b.md`), surfacing misconfigurations at spawn time rather than letting them produce silently unbounded sub-agent spend.

---

## How it should be done → Forvum's architectural patterns

| Recommended pattern | Forvum's realization |
|---|---|
| **Orchestrator-Workers (Hub-and-Spoke)** | Forvum's main agent + spawned sub-agents pattern (§5.2, §5.5) is exactly this. `AgentRegistry.spawn(parentId, childSpec)` creates an isolated worker with its own `@AgentScoped` context, its own memory, its own tool belt. Parent coordinates; workers execute in parallel. |
| **Pipeline of compression with proxy models** | `FallbackChain` (Group 4c, in design) is structured to enable chains where smaller, cheaper models handle initial passes and escalate only when needed. The `ModelRef` abstraction means a chain like `[ollama:llama3.2:3b → anthropic:claude-haiku → anthropic:claude-opus]` is a config-time decision, not a code change. |
| **Use of "small and fast" models for sub-steps** | `forvum-app` is the only assembly that knows about concrete providers; `forvum-engine` operates on `ChatModel` abstractions. This means a fast local model (Ollama) can handle classification or extraction sub-steps while a larger cloud model handles user-facing responses, without engine-layer changes. |
| **Cyclic frameworks (LangGraph)** | Forvum builds on `LangGraph4j` (§3.3) for stateful agent orchestration. Graph state types and node wrappers are explicit in the design, providing the unified state-management skeleton recommended by the source. |

---

## What can't be missing → Forvum's three foundations

### Strict state isolation (sandbox)

Already covered above under **Isolate**. The `@AgentScoped` context + `ScopedValue` propagation + spawn-time validation is the most architecturally invested area of the entire Tier 1 design round. This was not an afterthought — it shaped the choice of Java 25 (for stable `ScopedValue` semantics), the choice of CDI custom contexts (over alternative DI strategies), and the package organization (`ai.forvum.core.budget` co-locating budget enforcement with isolation guarantees).

### Governance, permissions, and security (Guardrails)

- **`PermissionScope` enum** (§4.3.4) — every tool declares the capability scopes it requires; the engine's `ToolExecutor` enforces them before invocation. Denied calls are logged to `tool_invocations.status = 'denied'` for audit.
- **Identity files** (`identities/<id>.json`, planned) — separate user/role identities from agent specs, supporting future role-based access control.
- **Per-agent allowed-tools sets** (§5.2) — agents only see tools their config permits, preventing cross-agent capability leakage.
- **Network isolation by default** — local-first design means the user's data stays on their machine unless they explicitly configure cloud providers.

### Operational traceability and observability

- **`AgentEvent` stream** (§4.3.2) — every agent action emits a typed event (`TokenDelta`, `ToolInvoked`, `ToolResult`, `FallbackTriggered`, `Done`, `ErrorEvent`) with an `Instant timestamp()`. Channels, observability, and CAPR judging all consume the same stream.
- **`provider_calls` ledger** (§4.2) — every LLM call is recorded with provider, model, tokens in/out, cost, latency, and whether it was a fallback. Enables exactly the kind of debugging the source document calls out as missing in most agent systems.
- **`turn_id` propagation** (§4.3.1) — a single UUID generated client-side at turn start ties together every `messages` row, `tool_invocations` entry, `provider_calls` row, and `capr_events` record for that turn. One query reconstructs the full reasoning path.
- **OpenTelemetry integration** (§3.4) — `forvum.llm.call`, `forvum.graph.node`, and per-tool spans are first-class concerns, with `Usage` snapshots feeding span attributes directly.

---

## Success metrics → Forvum's instrumentation strategy

| Metric domain | Forvum's instrumentation |
|---|---|
| **Process-based metrics** | The `AgentEvent` stream + `capr_events` table together capture the full reasoning trajectory. CAPR (Cost-Aware Pass Rate) is named explicitly in the schema — it's not a metric Forvum adds on top, it's a first-class table. Tool call pertinence is queryable via `tool_invocations.status` distribution per agent. |
| **Operational metrics** | `provider_calls` records latency per call, and the `BudgetMeter` `Usage` snapshot exposes spent/remaining at any point. OpenTelemetry spans add throughput and end-to-end latency observability. The `BudgetExhaustedException` flow makes cost-aware degradation an enforced behavior, not a hopeful one. |
| **Outcome-based metrics** | `capr_events` is designed to record judge verdicts (passed/rationale) per turn — this is the LLM-as-a-Judge pattern materialized as schema, not as ad-hoc tooling. Faithfulness and answer relevance can be computed by post-hoc queries over `messages` joined with `capr_events` on `turn_id`. |

---

## Closing note

Most of what's described here is documented in `docs/ULTRAPLAN.md` and the design rounds in `docs/design-rounds/`. The implementation lands milestone by milestone — `M1` shipped the multi-module bootstrap and the Tier 1 contract design rounds; `M2` onward will materialize the contracts into running code.

The point of this mapping is not to claim Forvum has solved context engineering. The point is that the principles in `CONTEXT-ENGINEERING.md` shaped the design from the first commit, and the architectural choices visible in the repository today reflect that intent — even where the corresponding code is still on the roadmap.
