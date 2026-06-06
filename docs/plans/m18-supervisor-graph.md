# M18 — LangGraph4j Supervisor Graph Implementation Plan (#23)

> For agentic workers: REQUIRED SUB-SKILLS — run `context7` (LangGraph4j `StateGraph`/`AgentState`/
> `Channels`, and LangChain4j `ToolSpecifications`/`ToolExecutor`/`ChatRequest` tool-calling surface)
> BEFORE writing any code or test; LangGraph4j is **NOT a Quarkus extension** so do NOT use
> `quarkus/skills` for it (CLAUDE.md §7). Run engine tests via Surefire (`./mvnw -pl forvum-engine test`),
> NOT the Dev MCP — `forvum-engine` is a headless library (CLAUDE.md §4 exception). This plan was
> produced after an 8-agent adversarially-verified recon of the live `origin/main` tree (turn pipeline,
> FallbackChain status, tool API, ULTRAPLAN §7.1, LangGraph4j 1.8.x docs) and the maintainer's four
> finale decisions (2026-06-06).

**Goal.** Materialize the Orchestrator-Workers hub-and-spoke topology (§5.5) as a LangGraph4j
`StateGraph` in `forvum-engine`, and **wire tool execution and sub-agent fan-out into the turn for the
first time** — M13/M14 built `ToolRegistry`/`ToolExecutor`/the filesystem tools but they are NOT called
from `Agent.respond()` yet. Acceptance: a multi-tool scenario ("fetch X then summarize") routes
`tool_loop`→`generate`, produces the expected final message, and writes a `capr_events` row for the turn.

**Authoritative baseline.** Branched off **`origin/main` (`6495c6e`, M106 / Tier-E lessons merge)** —
verified HEAD. Worktree: `.claude/worktrees/feat-m18-supervisor-graph`, branch `feat/m18-supervisor-graph`.

**Issue map.** M18 → `#23` (`docs/ISSUES.md` §issue-map; `Mn` closes `#(n+5)`). No ad-hoc issues.
Re-confirm live at PR time (`gh issue list --state all`).

---

## Maintainer decisions (2026-06-06) — these govern this plan

1. **Branch flow: one-at-a-time off `main`.** M18 implemented → 6-dim review → maintainer authorizes
   merge → then M19 branches off updated `main` → then M20. Linear history, 3 discrete merge checkpoints.
2. **FallbackChain (DR-4c): engine-local `FallbackLink` carrier.** Do NOT ratify a core sealed
   `FallbackChain` in M18/M19. The core type stays TBD; M19 will build a per-cron `List<FallbackLink>`.
   M18 reuses the existing `LlmSelector.select(...)` path (single-link today) unchanged.
3. **Tool dispatch: `String invoke(String,Map)` on the `ToolProvider` SPI (Option A, 2026-06-06 v2).**
   Initially chosen as langchain4j `@Tool`, then switched to A after the native cost of `@Tool` surfaced
   (non-framework-managed `Method.invoke` reflection vs CLAUDE.md §5/§12 + ArC client-proxy unwrap +
   hand-authored native hints). A is zero-reflection. See R2 below — it revises tier-d AC-D2 by design.
4. **M20 scope: pragmatic capstone** (recorded here for sequencing; M20 owns it).

---

## ⚠ Authoritative reconciliations (read first — these override stale doc wording)

- **R1 — `GraphState` is a CLASS, not a record.** ULTRAPLAN §6.3 / the native mandate say "graph-state
  types are records carrying `@RegisterForReflection`." LangGraph4j 1.8.x **requires** the state container
  to extend `org.bsc.langgraph4j.state.AgentState` (an abstract, map-backed class with a
  `Map<String,Object>` constructor + a `SCHEMA` of `Channel<?>`). It **cannot** be a record. The records
  carrying `@RegisterForReflection` are the **values stored inside the channels** (route decision, worker
  digest, tool-call summaries), NOT the state container. The container needs only its `Map`-ctor
  reachable; LangGraph4j reads/writes the state via the String-keyed `Channel` SCHEMA, not field
  reflection — favorable for native. **Doc-sync at M18.5 corrects the ULTRAPLAN wording.**

- **R2 — Option A adds `invoke(String,Map)` to the `ToolProvider` SPI; it revises tier-d AC-D2.** AC-D2
  (signed off 2026-06-05) decided the SPI is contribution-only (`List<ToolSpec> tools()`) with execution
  off the SPI. Option A revises that (maintainer-approved 2026-06-06): `ToolProvider` gains
  `String invoke(String toolName, Map<String,Object> arguments)` — Java `lang`/`util` only, so
  `forvum-sdk` stays Quarkus-free AND langchain4j-free (no pom change). `FilesystemToolProvider`
  self-dispatches by name (switch) to the existing `Fs*Tool` logic. The engine builds the model-facing
  `ToolSpecification` FROM the `ToolSpec` (name/description/parametersJsonSchema) — **no reflection
  anywhere** (the whole point of A vs `@Tool`). Mirrors the M7 prelude pattern (`ModelProvider.resolve`
  landed in its consumer milestone). `tools()` (→ `ToolSpec`) stays the registry/belt/`PermissionScope`/
  audit source of truth; `ToolRegistry` gains `providerFor(name)` for name→provider routing.

- **R3 — Permission + audit are NON-NEGOTIABLE.** Every model-emitted tool call runs **inside
  `ToolExecutor.execute(sessionId, agentId, belt, name, argsJson, action)`**, where `action` is
  `() -> providerFor(name).invoke(name, argsMap)`. Belt membership check (deny → `tool_invocations` row
  `status=DENIED` + `PermissionDeniedException`), OK/ERROR audit row, latency — all preserved. The audit
  row stores the raw `argsJson`; the engine parses it to a `Map` (Jackson, already in the engine) for
  `invoke`. A dispatch path that bypasses `ToolExecutor` is a review-blocking regression.

- **R4 — Stateless graph (no checkpointer).** Compile/run the `StateGraph` WITHOUT a `MemorySaver`/
  checkpointer. Cross-turn memory is already the Forvum SQLite ledger (`AgentMemory`); conversation
  history is injected into the turn, exactly as `Agent.respond()` builds it today.

- **R6 — State holds ONLY serialization-safe data; messages live in a turn-scoped holder (verified).**
  The initial assumption that a checkpointer-free graph avoids serialization was WRONG: LangGraph4j 1.8.17
  serializes the state via `ObjectOutputStream` on every step even with no checkpointer, and langchain4j
  message types (`UserMessage`, `AiMessage`, …) are NOT `Serializable` →
  `NotSerializableException` (reproduced in a spike). So `GraphState` holds only `String`/`List<String>`
  control signals (`route`, `next`, `final`, `workerDigests`); the actual `ChatMessage` conversation lives
  in a mutable turn-scoped holder captured by the per-turn-compiled node lambdas, never in the state.
  LangGraph4j is the control-flow skeleton (nodes + conditional edges + the generate⇄tool_loop loop); data
  flows through the holder. This also keeps the native image free of any `ObjectStream`/langchain4j-message
  serialization surface (aligns with §5). Compile the graph PER TURN so node lambdas capture the holder +
  the resolved `ChatModel`/belt (cheap vs an LLM call).

- **R5 — No `StructuredTaskScope` (JDK 25 preview, §5).** Worker fan-out in `spawn_worker`/`worker_run`
  uses `Executors.newVirtualThreadPerTaskExecutor()` (try-with-resources) + `CompletionStage`/`Future`
  join, OR `RunnableConfig.addParallelNodeExecutor(node, vtExecutor)`. Never preview APIs on the native
  path.

---

## Architecture

`forvum-engine` only — NO new Maven module (the `engine/graph/` package is added to the existing headless
library, like M13). Deps: `langgraph4j-core` (already pinned `1.8.17` in `forvum-bom`; add it to
`forvum-engine/pom.xml`) + the already-present `langchain4j-core`. `langgraph4j-langchain4j` (integration:
`LC4jToolService`, serializers, `StreamingChatGenerator`) is added to the BOM + engine pom **only if**
implementation needs `LC4jToolService`; with langchain4j-core's own `ToolSpecifications`/`ToolExecutor`
and a stateless graph it is likely unnecessary — decide at M18.3 (keep surgical).

### The turn seam

Today `Agent.respond(sessionId, userText)` (`engine/agent/Agent.java:68-84`) is single-shot: build
`List<ChatMessage>` (system prompt + `memory.messages(sessionId)` + user message) →
`llmSelector.select(persona, sessionId)` → `model.chat(ChatRequest)` → `recordTurn(...)`. M18 replaces
the body with: build the same initial messages → seed graph input → `compiledGraph.invoke(input)` →
extract the final assistant message → `memory.recordTurn(...)` on success → write a `capr_events` row.
`llmSelector.select(...)`, `FallbackChatModel`, `AgentMemory.recordTurn`, `ProviderCallRecorder` are
UNCHANGED (the `generate`/`tool_loop` nodes call `model.chat(...)` through the same selector).

### Nodes (each a `NodeAction<GraphState>` wrapped with `node_async`)

| Node | Responsibility |
|---|---|
| `route` | cheap local model (`qwen3:1.7b`) classifies: direct-answer / tool-loop / spawn-worker / delegate. Writes the decision (a `@RegisterForReflection` record) into state. For v0.1 MVP the classifier may be a minimal heuristic + model call; keep it small. |
| `generate` | direct-answer: `model.chat(request)` via `llmSelector.select(...)`, returns the final `AiMessage`; emits `Done`. |
| `tool_loop` | build `ToolSpecification`s FROM the agent's belt `ToolSpec`s (no reflection); `model.chat(request + toolSpecifications)`; for each returned `ToolExecutionRequest`, run it **through `ToolExecutor.execute(...)`** (action = `providerFor(name).invoke(name, argsMap)`); append `ToolExecutionResultMessage`; loop until no tool calls or the round cap (`maxToolCallingRounds`); then fall to `generate`. |
| `spawn_worker` | `AgentRegistry.spawn(parentId, spec)` → narrowed belt + child budget; validate `SessionWindow` non-inheritance (`SpawnConfigurationException`). |
| `worker_run` | drive the child agent's mini-turn in its own `@AgentScoped` context; workers run in parallel on a VT executor (R5). |
| `reduce` | merge worker outputs; when combined size exceeds the `MemoryPolicy` threshold, summarize through `qwen3:1.7b` — the **single sanctioned worker→parent merge point** (Isolate defense: only the compressed digest crosses). |

Conditional edges route on the route decision / `AgentEvent` type; the turn ends when `generate` emits
`Done`. For the M18 **MVP acceptance** the load-bearing path is `route → tool_loop → generate` (+ a
`capr_events` write); `spawn_worker`/`worker_run`/`reduce` are built and unit-tested but the multi-worker
fan-out is exercised minimally (the full X5 spawn e2e is M20-gated, live).

### GraphState (R1 + R6)

```java
public final class GraphState extends AgentState {
    public static final String ROUTE          = "route";         // String — route decision name
    public static final String NEXT           = "next";          // String — conditional-edge signal
    public static final String FINAL          = "final";         // String — final assistant text
    public static final String WORKER_DIGESTS = "workerDigests"; // List<String> — Channels.appender
    public static final Map<String,Channel<?>> SCHEMA = Map.of(WORKER_DIGESTS, Channels.appender(...));
    public GraphState(Map<String,Object> data) { super(data); }  // ROUTE/NEXT/FINAL overwrite (default)
}
```
**Only `String`/`List<String>` in state** (R6 — serialization-safe). The `ChatMessage` conversation lives
in a turn-scoped mutable holder captured by the per-turn node lambdas, never in a channel — so no
langchain4j-message serialization (native-clean, no `@RegisterForReflection` records needed in channels).

### Native (R1/R4)

`forvum-engine/src/main/resources/META-INF/native-image/` hand-authored reachability metadata for the
LangGraph4j node lambdas/`BiFunction` metafactory + `@RegisterForReflection` on every channel value
record. No serializer registration (stateless graph, R4). The binary native-COMPILES only via
`forvum-app` (already depends on `forvum-engine`); local GraalVM 25 build at M18.5.

### CAPR (acceptance)

`capr_events` is currently never written. M18 writes one row per turn (`CaprEventEntity`: sessionId,
agentId, turnId→messages.id, passed, judgeModel, rationale, createdAt). MVP: a minimal verdict
(`passed=1`, judgeModel="none"/rationale="judge-mode-off") so the structural acceptance ("a CAPR event
written for the turn") holds; full judge-mode is deferred (X10 is M20-gated).

---

## Files

```
forvum-bom/pom.xml                                  (+ langgraph4j-langchain4j IF used — conditional)
forvum-engine/pom.xml                               (+ langgraph4j-core dep)
forvum-engine/src/main/java/ai/forvum/engine/graph/
  ├── GraphState.java            (extends AgentState; SCHEMA)
  ├── RouteDecision.java         (record, @RegisterForReflection)  + other channel value records
  ├── SupervisorGraph.java       (compiles the StateGraph; @ApplicationScoped or built per turn)
  ├── RouteNode.java GenerateNode.java ToolLoopNode.java
  ├── SpawnWorkerNode.java WorkerRunNode.java ReduceNode.java
  └── ToolCallBridge.java        (ToolSpec→ToolSpecification; ToolExecutionRequest→ToolExecutor.execute→provider.invoke)
forvum-engine/src/main/resources/META-INF/native-image/   (reachability metadata for graph node lambdas)
forvum-sdk/.../ToolProvider.java                          (+ String invoke(String, Map<String,Object>))
forvum-engine/.../tools/ToolRegistry.java                (+ providerFor(name) routing)
forvum-tools-filesystem/.../FilesystemToolProvider.java   (implements invoke(): switch by name → Fs*Tool)
forvum-engine/src/main/java/ai/forvum/engine/agent/Agent.java  (respond() drives the graph; writes capr_events)
forvum-engine/src/test/java/ai/forvum/engine/graph/   (TDD tests)
```

---

## TDD sequence (red→green per CLAUDE.md §11)

1. **Tool dispatch (Option A)** — failing tests: (a) `ToolProvider.invoke()` on `FilesystemToolProvider`
   reads/writes/lists by name; (b) `ToolCallBridge` routes an `fs.read` call through `ToolExecutor` —
   denies when not in belt (audit `DENIED`) and executes+audits `OK` when in belt; (c) `ToolRegistry.
   providerFor(name)` routes to the owning provider. Green: SPI method + `FilesystemToolProvider.invoke` +
   `ToolRegistry.providerFor` + `ToolCallBridge`.
2. **GraphState + channels** — failing test: appender/base channel semantics + typed accessors. Green.
3. **Each node** — failing unit test per node (route classification, generate returns AiMessage text,
   tool_loop iterates then stops, reduce compresses over threshold). Green with minimal impl + a
   `FakeProvider`/in-memory `ChatModel` (no live network in unit tests).
4. **SupervisorGraph wiring** — failing test: compiled graph runs `route→tool_loop→generate` for a
   scripted multi-tool scenario and yields the expected final message + a `capr_events` row.
5. **Agent.respond() integration** (`*IT`, `@QuarkusTest`, real SQLite `@TempDir`) — the turn drives the
   graph and persists messages/provider_calls/capr_events.

**Verify (the M18 contract):** `./mvnw -pl forvum-engine -am test` green; the "fetch X then summarize"
scenario routes `tool_loop→generate` with the expected message + CAPR row; local GraalVM 25 native build
of `forvum-app` succeeds.

---

## Risks

- **Risk #4 (LangGraph4j API drift in 1.8.x):** keep ALL coupling in `engine/graph/` so a bump/replace is
  module-local. Confirm the exact 1.8.17 `addConditionalEdges`/`node_async`/`compile`/`invoke` signatures
  via `context7` at M18.3.
- **Risk #13 (native reachability):** node `BiFunction` metafactory + `@Tool` reflection are the native
  surfaces; add hints + verify the local native build before claiming green.
- **ToolSpec → ToolSpecification conversion:** the model-facing `ToolSpecification` is built from
  `ToolSpec.parametersJsonSchema` (a raw JSON-schema string). Confirm the langchain4j 1.15.1 path to turn
  a JSON-schema string into a `JsonObjectSchema` via `context7` at M18.3 (parse + build, or a helper).
- **Round cap:** `maxToolCallingRounds` (langchain4j 1.15.1; legacy `maxSequentialToolsInvocations` on the
  1.10.0 fallback) — bind via the request, not ad-hoc try/catch.
