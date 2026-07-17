# Implementation lessons — Engine, graph & scheduling

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **Guard public registry mutations + keep IO off lock paths.** `spawn` must reject `childId ==
  parentId` and collisions (`putIfAbsent`-and-throw) or it silently overwrites a file-declared agent. Do
  not run blocking file IO inside `ConcurrentHashMap.computeIfAbsent` (it holds the bin monitor →
  carrier-thread pinning, §3.8) — load outside, then `putIfAbsent`. JPQL `key` is reserved (the `KEY()`
  function), so filter `semantic_memory` by key in-memory, not in a Panache where-clause. [M7]

- **LangGraph4j serializes the graph state via `ObjectOutputStream` on EVERY step, even with no
  checkpointer — so keep non-`Serializable` types OUT of the state.** langchain4j messages
  (`UserMessage`/`AiMessage`/…) are not `Serializable`; putting them in a channel throws
  `NotSerializableException` at `invoke()` (reproduced in a spike). Resolution (R6): `GraphState` holds
  ONLY `String`/`List<String>` control signals (`route`/`next`/`final`/`workerDigests`); the `ChatMessage`
  conversation lives in a per-turn mutable holder captured by the node lambdas (compile the graph per
  turn). This also keeps the native image free of any `ObjectStream`/langchain4j serialization surface —
  the M18 native build is green with ZERO hand-authored `META-INF/native-image/` metadata. Corollary:
  `GraphState` MUST be a *class* extending `AgentState` (map-backed contract), NOT a record — the
  ULTRAPLAN's "graph-state types are records" was wrong (records are for the values *in* the channels, of
  which there are now none); both ULTRAPLAN spots were corrected. [M18]

- **`node_async(NodeAction)` builds a `LambdaMetafactory` lambda, not a JDK dynamic `Proxy` — the
  `Proxy.newProxyInstance` branch fires only for `InterruptableAction`** (verified by disassembling
  `langgraph4j-core` 1.8.17). Forvum's plain `state -> node(state)` lambdas are build-time-reachable, so
  no proxy/reflect native config is needed; an adversarial-review "missing native metadata" finding was
  refuted by the bytecode + the green native build. [M18]

- **LangGraph4j's default `recursionLimit` is 25 and counts EVERY node execution, not turns/rounds.** A
  spawn round is 4 nodes (generate→spawn_worker→worker_run→reduce), so an in-graph round cap of 8 is
  unreachable — the framework throws "Maximum number of iterations" around round 6 and fails the turn
  instead of degrading. Fix: `compile(CompileConfig.builder().recursionLimit(MAX_ROUNDS*4 + margin).build())`
  so the in-graph cap binds and returns a best-effort answer. [M18]

- **Tool execution enters the turn at M18 via the graph, and the `@Tool`-vs-self-dispatch choice is a
  native-mandate call.** Option A — `ToolProvider.invoke(String,Map)` self-dispatch (a name→logic switch,
  ZERO reflection) — was chosen over langchain4j `@Tool`/`DefaultToolExecutor`, whose `Method.invoke`
  reflection is NOT framework-managed when models are built programmatically (no `@RegisterAiService`),
  clashing with §5/§12. The SPI execution method lands in its consumer milestone (M18), mirroring M7's
  `resolve()`; the SDK stays Quarkus-free + langchain4j-free (only `java.util.Map`). Model-facing
  `ToolSpecification`s are built FROM `ToolSpec` (no reflection either). Every model-emitted tool call
  still runs inside `ToolExecutor` (belt + audit) — incl. belt tools emitted in the SAME reply as a
  built-in `spawn_worker` call, which the 6-dim review caught being silently dropped (every
  `ToolExecutionRequest` MUST get a result message or the next provider call rejects the conversation).
  [M18]

- **Decouple the supervisor graph from `LlmSelector`/`AgentRegistry` via a `WorkerRunner` seam.** Pass the
  resolved `ChatModel` + belt + messages INTO `SupervisorGraph.run(GraphTurnRequest)` (so it is unit-testable
  with a scripted model), and put sub-agent spawn/drive behind a `WorkerRunner` interface
  (`DefaultWorkerRunner` does `AgentRegistry.spawn` + a child generation, re-binding `CURRENT_AGENT` INSIDE
  the worker virtual thread since `ScopedValue` does not inherit across threads). Workers fan out on
  `Executors.newVirtualThreadPerTaskExecutor()` (no `StructuredTaskScope`). A scripted model that ignores
  its input makes "tool result fed back" / "worker digest merged back" tests pass for the wrong reason —
  capture the per-call `ChatRequest.messages()` and assert the result/digest actually reaches the model
  (the 6-dim review caught both as green-for-wrong-reason). [M18]

- **Pure programmatic Quarkus scheduling needs `quarkus.scheduler.start-mode=forced`** — without a
  `@Scheduled` business method the scheduler does not start, so `Scheduler.newJob(id)...schedule()` never
  fires. The flag goes in `META-INF/microprofile-config.properties` (app-wide, [M17]), not
  `application.properties`. Programmatic API: `scheduler.newJob(id).setCron(expr)
  .setConcurrentExecution(SKIP).setTask(task, true).schedule()` / `unscheduleJob(id)` /
  `getScheduledJob(id)` (the latter two confirmed in `quarkus-scheduler` 3.33.1 via `javap`). **The 2nd arg
  of `setTask(Consumer, boolean)` IS the run-on-virtual-thread flag** (a `runOnVirtualThread` field on
  `AbstractJobDefinition`) — framework-managed VT, no manual offload needed. [M19]

- **Hot-reload: a config-driven job that becomes INVALID on edit must be UNSCHEDULED, not left firing the
  stale spec.** The natural `read().map(parse).ifPresent(schedule)` swallows a parse failure and leaves the
  prior job running the old definition (and old model → burns budget). The MODIFIED-into-invalid (and
  empty/mid-write read) path must `unscheduleJob(id)`, mirroring DELETED. Test the reload deterministically
  by firing `ConfigurationChangedEvent` (or calling the `@Observes` method) + asserting
  `scheduler.getScheduledJob(id)` — NOT via WatchService timing; the boot/`onStart` fixture does not cover
  the reload entry point ([M4] lesson). [M19]

- **A per-cron (or per-X) model override is only proven if the override differs from the default in the
  test.** The cron carries its own `ModelRef` (resolved via `LlmSelector.resolve(ref, agentId, sessionId)`
  + an `Agent.respond(..., ChatModel override)` overload). With the cron model == the agent persona model
  in the fixture (and `FakeModelProvider` ignoring the ref), a regression that dropped the override and
  used the persona model passes green. Give the cron a DISTINCT model id and assert `provider_calls.model`
  reflects the CRON's model (the 6-dim review caught this as green-for-wrong-reason). [M19]

- **There is NO outbound channel-send API — channels are self-driving consumers, not sinks.** The channel SPI
  (`ChannelProvider`) is a pure build-time discovery marker (M16 Resolution B); a channel pulls turns via
  `ChannelTurnDriver.dispatch`, the engine never pushes to one. So "deliver a cron's output to a channel"
  cannot target a live session — route it to an isolated-agent result sink (`CronDeliverySink`, default logs)
  keyed by the resolved target, and document the limitation. Validate an `explicit-to` target against the
  CONFIGURED channels (`channels/<id>.json` stems via `ChannelReader.ids()`), not a live registry. Reject the
  whole delivery directive at PARSE (grow the typed record's canonical constructor for the mode↔target
  ambiguity; layer the known-channel cross-check in the reader, which holds the set) so the existing
  `CronScheduler` catch→`unscheduleJob` disables the bad cron AND `ConfigDoctor` (which reuses the same reader
  as its oracle) surfaces it for free — give doctor the same known-channel set. In-execution dedupe = a single
  `deliver()` call site after a successful `fire()`; no table, no migration. The new payload records
  (`Delivery`/`CronDelivery`) are never JSON-serialized, so they carry no `@RegisterForReflection` (mirror
  `GraphTurnRequest`). To drive `fire()` end-to-end in a NON-boot unit test, construct `CronScheduler`
  directly and set its package-private collaborators to stubs — but a stub that `extends` a CDI bean
  (`AgentRegistry`/`LlmSelector`/`RoleRegistry`/`Agent`) must carry `@Vetoed`: a CDI scope is `@Inherited`,
  so an un-vetoed subclass becomes a second ambiguous bean and breaks the module's `@QuarkusTest` boot
  (a sibling `RecordingSink` implementing the plain `CronDeliverySink` interface needs no veto). [P2-CRON-DELIVERY]

- **A "sink SPI" lives in `forvum-sdk` as a PLAIN (non-sealed) interface with the engine as sole implementor —
  not in the sealed channel/model/tool/memory hierarchy.** `TaskExecutor` (P2-TASKLEDGER) mirrors the
  `ChannelTurnDriver` shape: SDK contract, single engine `@ApplicationScoped` impl (`TaskRecorder`), plugins do
  NOT implement it; engine callers `@Inject TaskExecutor`. The Panache recorder pattern is exact
  copy-`PanacheProviderCallRecorder` (`@ApplicationScoped` + `@Transactional record()` mapping a Layer-0 record
  to an entity row). Record the write persist-after-success (never wrap the whole producer in `@Transactional`),
  and isolate the recorder call in try/catch so a ledger failure cannot undo/kill the work that already
  succeeded. Wire spawn-recording at the REAL chokepoint (`AgentRegistry.spawn`, where every spawn —
  including the M18 `DefaultWorkerRunner` — converges), not at a facade (`TurnService` never spawns). A new
  `V2__tasks.sql` bumps the Flyway head, so the M5 `SchemaSmokeIT` version/table/index assertions (it pins
  version "1" + the V1 table & index lists) MUST be updated to the new head in the same change. [P2-TASKLEDGER]

- **"Decode the final message against a JSON Schema" stays native-clean as schema-STRING → `JsonNode`, NOT a
  typed POJO.** P2-12's locked decision rejects LangChain4j `@Description`/`@StructuredPrompt` decoding: a
  per-agent output class would force runtime reflection / classpath class loading and break the native binary.
  Instead `Persona` carries an optional `outputSchema` STRING (null = free-text, backward compatible; blank-but-
  present rejected — already in the §6.3 reflection holder), `AgentSpecReader` serializes an embedded object
  spec to a compact string (or takes a string verbatim), and a pure-Java `OutputSchemaValidator` tree-walks the
  decoded reply (`mapper.readTree`) checking the v0.5-parity subset (root `type`, `required`, each property's
  primitive `type`) — no third-party JSON-Schema lib until one is proven to native-compile (documented fast-
  follow). Thread it through `GraphTurnRequest` (add a backward-compatible secondary ctor defaulting it to null
  so existing 5-arg callers/tests compile) and validate in `SupervisorGraph.run` AFTER the graph returns, not in
  a node. A failure throws `SupervisorGraphException` naming the schema + field; `TurnService` already converts
  any turn `RuntimeException` into a terminal `ErrorEvent.from(...)`, so the named message rides into the event —
  no retry, no new event plumbing. A spawned worker child passes `null` (its digest is merged as a tool result,
  never the validated top-level answer). [P2-12]

- **`MemoryPolicy` is a flat Layer-0 record driving a Layer-1 retrieval SPI — settled by DR-5
  (`docs/design-rounds/group-5-memory-policy.md`), §4.3.6.** `MemoryPolicy(RetrievalStrategy strategy,
  Set<MemoryTier> tiers, int topK, double minScore, int compressThresholdChars)` + four siblings
  (`RetrievalStrategy`/`MemoryTier` enums, `MemoryQuery`, `MemoryHit`) all in `ai.forvum.core` (no
  sub-package — unlike budget, no service iface in core). It DRIVES the new SPI method
  `MemoryProvider.retrieve(MemoryQuery, MemoryPolicy) → List<MemoryHit>` (blocking on a VT, NO reactive;
  SDK stays Quarkus-free; SDK already deps core so no new dep/enforcer change), which P2-5 #30 implements.
  `strategy=NONE` keeps the policy non-nullable on the agent spec ("memory off" is a value). One
  `compressThresholdChars` knob serves both the §5.5 `reduce` merge AND retrieved-memory write-back
  (chars not tokens = native-clean). Spawn-inherited like CostBudget/Identity but needs NO
  `SpawnConfigurationException` analogue — unlike a `SessionWindow` budget, the tenant key (`agentId`)
  is per-call via `MemoryQuery` from the child's `@AgentScoped` context, so a verbatim policy reads the
  child's own memory. Retrieval framed as `<retrieved_memory>` DATA + pre-memory-write `OutputFilter` are
  REFERENCED from DR-6a §9, never redefined (read/write split: policy governs read-back, filter governs
  write). The five core records do NOT carry `@RegisterForReflection` (core bans `io.quarkus*`) — P2-5
  appends them to the engine `CoreReflectionRegistration` holder (§6.3). Dissolves demo D2's
  `memoryPolicy` sub-gap; residual `AgentSpec` composition is DR-8's. [DR-5]

- **The §3.6 four-span OTel baseline is `@WithSpan` on the three CDI beans + a PROGRAMMATIC span on the one
  plain object, and it is OFF BY DEFAULT via the M20 `main()` system-property lever — so it adds zero
  cold-start cost.** P2-15 (#40): `forvum.agent.turn` (`Agent.respond`), `forvum.tool.call`
  (`ToolExecutor.execute`), `forvum.graph.run` (`SupervisorGraph.run`) via `@WithSpan`; `forvum.llm.call`
  via a `Tracer` span in `FallbackChatModel.chat` — a plain object, so the `Tracer` is injected into
  `LlmSelector` and passed through a NEW 7-arg ctor (the 6-arg ctor delegates with a null tracer, leaving
  the 7 unit-test call-sites untouched; a null tracer skips the span). Each span carries
  `thread.is_virtual` (`Thread.currentThread().isVirtual()`). `Agent.respond` annotates BOTH overloads
  (2-arg channel entry, 3-arg cron entry) and sets attributes in the 3-arg via `Span.current()` — a CDI
  self-invocation (`this.respond(...)`) does NOT re-intercept, so the 2-arg's span is the active one and
  there is exactly one turn span on either entry. **Off-by-default:** `ForvumApplication.main` sets
  `quarkus.otel.sdk.disabled=true` (set-only-if-absent) before `Quarkus.run` when
  `OTEL_EXPORTER_OTLP_ENDPOINT` is unset (the host-enabled lever's twin — `sdk.disabled` is RUNTIME_INIT
  config, too early for a `StartupEvent`); the SDK-off path makes every `Span.current()`/`@WithSpan` a
  no-op, skips OTel resource detection, and keeps the native cold-start ~70 ms (gate 200 ms). dev/test set
  `%dev`/`%test.quarkus.otel.sdk.disabled=true`; engine tests default it off via a new
  `forvum-engine/src/test/resources/application.properties`, and `TurnSpanIT` re-enables it
  (`getConfigOverrides`) with a profile-scoped `@Produces InMemorySpanExporter` + a deterministic
  `OpenTelemetrySdk.getSdkTracerProvider().forceFlush().join(...)` (no sleep/poll). **TRAP the review
  caught:** OTel `Context` is thread-local and does NOT cross the worker virtual-thread fan-out (the same
  reason `CURRENT_AGENT` is re-bound in the worker), so a spawned worker's spans orphan — capture
  `Context parent = Context.current()` before the loop and submit `parent.wrap(task)`. `opentelemetry-sdk-testing`
  resolves via the OTel BOM transitive to `quarkus-opentelemetry` (no pin). [P2-15]

- **A design round merged "into the docs" is NOT merged "into the code" — verify on disk before assuming
  a contract exists.** PR-8's first commit had to MATERIALIZE DR-8: it landed in PR-1 only as
  `docs/design-rounds/group-8-agentspec-composition.md` + ULTRAPLAN §4.3.8, while the code still carried
  the 8-field `Persona`, no `AgentSpec`/`CycleSpec` records, and an `AgentSpecReader` that hard-passed
  `costBudget=null` — exactly the M13 stale-Files trap in reverse (the design was committed, the record
  never built). Grep the actual types a "merged" round names before consuming them. The composition is the
  `Identity.roles` additive-growth recipe at n=4: `Persona` grew 8→12 (`fallbackModels`/`memoryPolicy`/
  `roles`/`identityId`), the canonical ctor widened, an 8-arg delegating overload supplies the defaults
  (null list→`List.of()`, null `memoryPolicy`→`MemoryPolicy.defaults()` — "memory off" is the value
  `strategy=NONE`, not absence); `AgentSpec(Persona, CycleSpec)` became the §5.2 registry value but
  `AgentRegistry.persona(id)` still returns `Persona` (a new `spec(id)` exposes the cycle); `spawn` inherits
  the 4 new fields verbatim (never the cycle — a worker is one direct generation) and `ConfigDoctor`
  validates the cycle by switching to `parseSpec` (reader-as-oracle). [DR-8/PR-8]

- **Memory retrieval, proxy compression, and replay-substitution are all `SupervisorGraph` seams threaded
  through `GraphTurnRequest` additive ctors (the P2-12 `outputSchema` precedent) — keep the graph the
  single place the turn's read/compress/replay behavior lives.** (1) **Retrieval** (commit 1): `MemorySelector`
  mirrors `LlmSelector`'s `Instance<MemoryProvider>` (resolves the SINGLE installed provider — multi-provider
  deferred — and degrades to empty on no-provider/`NONE`/failure so a retrieval problem NEVER fails the
  turn); `SupervisorGraph` retrieves ONCE at turn entry (last user message = query) and inserts a
  `<retrieved_memory>` DATA `UserMessage` just before the user's question (DR-6a §9 — never the
  system/instruction region; the closing tag is neutralized inside untrusted hit content). (2) **Compression**
  (#56): one `MemoryPolicy.compressThresholdChars` knob governs BOTH retrieved-hit compression and the §5.5
  reduce-node worker-digest compression via the shared P2-COMPACT `Summarizer` seam; `threshold<=0` (no
  policy) disables both. (3) **Replay** (#57): a `ReplayContext.CURRENT_REPLAY` ScopedValue (the `CURRENT_AGENT`
  pattern) puts the graph in replay mode — `runTool` serves recorded outputs FIFO-per-tool from a
  `ReplayToolSource` (miss → synthetic marker) instead of executing/auditing, AND retrieval + compression are
  short-circuited for determinism. The [M18] green-for-wrong-reason guard is load-bearing for all four: a
  scripted model that CAPTURES `ChatRequest.messages()` is the only way to prove the framed block / compressed
  summary / recorded result / per-step instruction actually reached the model (not a coincidence).
  (4) **Cycles** (#51): a declared `CycleSpec` compiles a cyclic generation graph by REUSING the supervisor's
  own `generate↔tool_loop` self-loop pattern (a `cycle` node + a conditional self-edge),
  `recursionLimit = maxRounds×steps+margin` (the M18 counts-every-node lesson); generation-only by DP-7 (no
  tools/retrieval inside a cycle). [PR-8]

- **A "native-clean JSON-Schema library" claim is only true once a real validate call runs in the binary —
  and the library is adopted ONLY where there is no loader to drift from.** #124 evaluated
  `com.networknt:json-schema-validator` for the two ratified pragmatic divergences (P2-9 doctor, P2-12
  `outputSchema`). VERDICT: the library is native-clean — 1.5.8 ships its OWN `META-INF/native-image/`
  metadata that is an EMPTY `reflect-config.json` + a `resource-config.json` for its bundled draft
  meta-schemas (zero runtime reflection, NO `META-INF/services` ServiceLoader), and the optional ECMA-262
  regex engines (`org.graalvm.js:js` ~50MB / `org.jruby.joni`) are `optional=true` upstream so they DON'T
  transitively pull in (the JDK regex path backs `pattern`). Inspect the jar's bundled native metadata FIRST
  (`unzip -l … | grep native-image`, read `reflect-config.json`) — an empty reflect-config is the strongest
  paper signal — but the only PROOF is a deterministic native IT that runs the validator's RESOURCE-load +
  compile path through the binary, the [M20]/[Risk#5] "native-COMPILEs + boots never proved it could run"
  rule (the value-side `validate` shares `JsonSchemaFactory.getInstance(V202012)` + `getSchema(...)` — the
  draft-2020-12 meta-schema RESOURCE load is the real native risk, covered by the lib's `resource-config.json`).
  **TRAP (cost a wasted native build): an `@QuarkusMainIntegrationTest` launches the PRODUCTION binary
  out-of-process, so a `src/test` `@ApplicationScoped` fake `ModelProvider` is NOT in the image** — a first
  cut drove a `forvum ask` turn against an in-test `schemafake` provider and the native turn died with
  "No model provider for 'schemafake'" (exit 1), nothing to do with networknt. A test-scope bean only works
  for the in-JVM `@QuarkusMainTest`, never the native IT. The deterministic, OFFLINE native driver that needs
  NO provider in the image is `OutputSchemaDoctorNativeIT`: `forvum doctor` over a home whose `main` declares
  a valid `outputSchema` — `ConfigDoctor` now compiles that schema through the same `OutputSchemaValidator`,
  exercising factory init + resource load + compile in the native binary (default leg, no `@Tag("live")`, no
  live LLM). SCOPE DECISION (the load-bearing call, NOT "force the lib everywhere"): adopt it ONLY in
  `OutputSchemaValidator` (the schema is user config validated against a model reply — no loader to drift
  from), giving full draft-2020-12 coverage (`enum`, nested objects, arrays, numeric bounds, length,
  `pattern`/`format`, `allOf`/`anyOf`/`oneOf`). The P2-9 config-loader half STAYS loader-as-oracle: a formal
  schema generated-from OR tested-against the loaders is still a parallel definition that drifts (the whole
  P2-9 point), so generating `agents`/`crons`/etc. schemas would REGRESS the no-drift property — "evaluate a
  lib" is not "adopt it for every config surface". (Doctor's new `outputSchema` check is itself reader-as-
  oracle: it reuses the engine's own validator, not a parallel schema.) The networknt API is classic +
  reflection-free (`JsonSchemaFactory.getInstance(VersionFlag.V202012)` → `getSchema(String, InputFormat.JSON)`
  → `schema.validate(JsonNode) → Set<ValidationMessage>`, `ValidationMessage.getMessage()` = `$.field: integer
  found, string expected`); KEEP `validateSchema`'s explicit structural guards (object / `required` array /
  `properties` object) AHEAD of the compile so `SkillReader`'s existing precise config-read messages survive,
  and UPDATE the value-side tests whose assertions pinned the old hand-rolled wording (the bare-value test's
  "(root)"/"declares it as object" → networknt's "object expected") — the validator's message text is an
  implementation detail, only the turn-abort + schema-named-in-`SupervisorGraphException` behavior is the
  contract. networknt LOGS an ERROR via its own `JsonSchemaFactory` logger when handed a malformed schema
  (the `validateSchema`/unusable-schema test paths) — noisy but harmless; in production a malformed schema is
  caught at config-read by `validateSchema`, never reaching `validate`. Manage the version in `forvum-bom`
  (`json-schema-validator.version`), never pinned in a module. [#124]

- **Budget enforcement is TWO different seams — cost by decorator construction, tool count by ScopedValue
  — and a LangGraph4j node's exception emerges from `graph.invoke()` wrapped in `ExecutionException`, so a
  typed catch without an unwrap-walk is dead code.** #169 activated the dormant §4.3.5.2 Decisions 8/9/10:
  the `costBudget` gate rides `FallbackChatModel` BY CONSTRUCTION (`LlmSelector.select` builds a
  `BudgetGate` from `persona.costBudget()`; a new budget-gated `resolve` overload serves the cron path —
  update `CronSchedulerTest`'s `StubLlmSelector` to the new signature, the [#167] stub-models-the-new-call
  trap), checked BEFORE EVERY attempt so a failed link's ledger row is seen by the next check; `toolBudget`
  is a per-turn `AtomicLong` grant counter (`TurnToolBudget`, the `CURRENT_EFFECTIVE_SCOPES` enforce-iff-
  bound pattern) bound by `Agent.respond` and consumed by `ToolExecutor` AFTER belt/RBAC/approval and
  BEFORE the action — the issue's coordination rule "denied actions must not consume tool budget, while
  authorized attempted actions must" falls out of that gate order for free. `BudgetExhaustedException` must
  abort the turn AS ITSELF (TurnService maps it to `code=budget_exhausted`): `runTool` rethrows it ahead of
  the render-back-to-model arms (else the loop burns generate rounds on an exhausted budget), and `run()`'s
  catch walks the cause chain (hop-capped) — the tests' error logs proved invoke() delivers the node's
  exception inside `ExecutionException`, so the pre-existing `catch (SupervisorGraphException) rethrow`
  idiom never fires for node-thrown types. Decision-10 per-agent day scoping = an ADDITIVE default overload
  `BudgetMeter.usage(budget, agentId)` (core interface unchanged for implementors), where the filter value
  IS the decorator's own ledger-attribution `agentId` — the aggregation filters by exactly what the rows
  were written with, no ScopedValue divergence possible. And a zero cap (`maxTokens: 0`) makes the
  enforcement path a DETERMINISTIC OFFLINE native E2E: the pre-call gate fires before any provider HTTP, so
  `BudgetExhaustedAskNativeIT` runs the full parse→meter→gate→error surface in the DEFAULT native leg
  against the real `ollama:`-pinned provider with no live model ([Risk#5]'s fake-not-in-image trap never
  applies because the provider never has to answer). [#169]

- **Activating a dormant exception can expose a stale doc-vs-code contract — reconcile the doc to the
  as-built, don't add dead mapping code.** #169 un-deferred `costBudget`, which activated the M7 dormant
  `SpawnConfigurationException` guard (a `SessionWindow` parent budget inherited without an override). Its
  core javadoc + ULTRAPLAN Decision 10 both promised the engine surfaces it as a terminal
  `spawn_invalid_config` `ErrorEvent` — but the M18 supervisor graph's ONLY production spawn path
  (`spawn_worker` fan-out → `SupervisorGraph.prepareSpawn`) catches every `RuntimeException` (id collision,
  self-id, belt-widening) as a model-visible tool result and CONTINUES the turn, so the promised terminal
  surface has no escape path. Three independent review finders flagged the divergence; the exception is
  ALSO unreachable from file config (`AgentSpecReader` rejects a `"session"` window), reachable only via
  the programmatic 4-arg `spawn` override. Right fix: reconcile the javadoc + the ULTRAPLAN Decision-10
  line to the as-built (a defensive spawn-time safeguard rendered as a tool result, not a terminal event),
  NOT add a `spawn_invalid_config` catch arm in `TurnService` + make `prepareSpawn` rethrow — that is dead
  code for an unreachable path (YAGNI, CLAUDE §13 simplicity). The generalizable rule: when you turn on a
  guard that was inert, grep its own javadoc for the behavioral contract it advertises and make the code
  honor it OR correct the doc — an activated guard with a lying doc is worse than a dormant one. [#169]

- **Telemetry emitted to a null sink is not a #169 regression — the `FallbackTriggered` stream has no
  production consumer.** Two finders flagged that the cost gate's `FallbackTriggered(COST_BUDGET)` is
  dropped because `LlmSelector` builds every production `FallbackChatModel` with `onEvent=null`. This is
  pre-existing and by-design for ALL reasons (`RATE_LIMIT`/`TIMEOUT`/`SERVER_ERROR`/`COST_BUDGET`):
  production fallback telemetry is the `provider_calls` ledger + OTel spans, not the `AgentEvent`
  `FallbackTriggered` stream (which the M8 decorator emits only when a caller passes a sink — the unit
  tests do). The hard stop still reaches the user via `BudgetExhaustedException` → `code=budget_exhausted`.
  Emitting the event keeps the decorator's contract uniform and ULTRAPLAN-Decision-8-mandated (the unit
  test asserts it), and wiring a `FallbackTriggered` consumer is out of #169 scope — don't add speculative
  observability plumbing to satisfy a finder about a dead sink. [#169]

- **A compression-FAILURE fallback must be BOUNDED, not fail-open — and the ceiling reuses the existing
  threshold rather than adding config.** #176 closed the Compress-pillar fail-open path: `SupervisorGraph`
  had TWO call-sites (`compressHits` for retrieved memory, `compress` for the `reduce` worker-digest) that,
  on a summarizer throw / null / blank, reinserted the RAW oversized content (already `> threshold`) with no
  ceiling — overflowing the window / amplifying injection from untrusted retrieved/worker data. The fix is
  ONE shared `ai.forvum.engine.compress.BoundedCompressor` (pure-Java, native-safe, no reflection, no CDI —
  a static `compress(content, CompressionBudget, Summarizer)` taking the summarizer as a PARAM so the graph
  keeps its single injected instance and tests still set `graph.summarizer = lambda`). Budgets derive from
  the single `compressThresholdChars` knob (DR-5, no new config surface — the maintainer's config-minimalism):
  `maxOutput = threshold` (a failed compression must not leave content above the size that triggered it),
  `maxInput = threshold*4` (clamped) above which the model is SKIPPED entirely (bounded memory, no expensive
  call on adversarial multi-MB input — the "very-large-input" acceptance). Fallback = truncate to `maxOutput`
  + a FIXED marker (OUR literal, ASCII, no delimiter, never attacker-derived; the exact omitted count goes to
  the safe content-free WARN log, never the prompt). Traps: (1) the marker is 60 chars, so tests using a tiny
  `threshold` (5/10) hard-clamp it away — the graph tests needed realistic thresholds (100) + content sized
  into the right band (>threshold, <maxInput for the model-called path; >maxInput for the skip path). (2) the
  three existing `*KeepsTheRaw*` tests ENCODED the fail-open bug — rewrite them to the bounded contract (assert
  `!contains(rawOversized)` + `contains(TRUNCATION_MARKER)` + a char-count `<= maxOutput`), the Red→Green.
  (3) an over-limit SUMMARY (model "compressed" to something still `> maxOutput`) is itself bounded → truncate
  the SUMMARY (better signal than raw). (4) `BudgetExhaustedException` is RE-THROWN (hop-capped cause walk,
  mirroring `asGraphFailure`), never swallowed into a fallback — a #169 hard stop still aborts the turn, so
  budget exhaustion can never trigger a compression fallback at all. (5) delimiter safety is preserved by
  ORDER: the memory fallback text still flows through `RetrievedMemory.frame`→`neutralize` AFTER truncation
  (a complete `</retrieved_memory>` in the kept chars is stripped; a partial tag at the cut is inert), and the
  worker digest rides a role-framed `ToolExecutionResultMessage` (no textual delimiter). (6) removing the two
  `LOG.warnf` fail-open lines orphaned the graph's `LOG` field + `Logger` import — remove them (surgical). The
  3rd `Summarizer` call-site (`SessionCompactor`) is fail-CLOSED over TRUSTED own-session content — NOT the
  #176 vector, left out of scope. The failure taxonomy (timeout/exception/blank/invalid/over-limit/input-over-limit)
  is a `CompressionOutcome` enum for diagnostics; classify timeout by a `*TimeoutException` in the cause chain. [#176]

- **Retiring ephemeral spawned workers (#177) is a `finally` in `SupervisorGraph.run` + an `AgentRegistry.retire`
  that both UNREGISTERS and DESTROYS the `@AgentScoped` context — and the ArC-registered custom-context instance is
  reachable DIRECTLY, no wrapper.** The empirical fact that unblocked the design (verified with a throwaway probe):
  `Arc.container().getContexts(AgentScoped.class)` returns the REAL `AgentContext` instance (size 1), so a cast +
  the custom `destroy(AgentId)` works — a re-resolve of an `@AgentScoped` bean then yields a fresh identity, proving
  teardown. The wrapper the `AgentContext` javadoc warns about only shadows the no-arg `destroy()`/`getState()` of the
  container-shutdown lifecycle, NOT the custom method. **Native-safety is by CONSTRUCTION, not by precedent**: the
  context is registered at BUILD TIME (the `BuildCompatibleExtension`) and `getContexts` is a deterministic,
  reflection-free, no-dynamic-proxy runtime map lookup of that registered instance — so it behaves identically on the
  JVM and in native (categorically unlike the Risk#5 ServiceLoader/serialization traps that native genuinely breaks;
  do NOT claim `MemoryWriter` as precedent — it uses `requestContext()`, a built-in scope, not `getContexts(customScope)`).
  Still, `destroyScope` asserts the instance IS an `AgentContext` and WARNS loudly if not (never a silent skip), so a
  future ArC change could not let the leak return unnoticed. Design shape: (1) allocate a UNIQUE runtime id per worker
  (`EphemeralAgentId.forLabel`, `<sanitized-label>~<uuid8>`) — NEVER the model's raw suggested `childId` — so a spawn
  can never collide with a persistent (file-declared) agent or another worker, making collision-safety, race-safety,
  and the persistent-vs-ephemeral distinction trivial; (2) track live ids in a dedicated `ephemeralIds`
  `ConcurrentHashMap.newKeySet()` — its membership IS the "retire never touches a persistent agent" guarantee
  (`retire` no-ops on an id it did not spawn) AND the `activeWorkerCount()` gauge; (3) retire ALL of them in `run()`'s
  `finally`, covering EVERY exit path (success / model-fail / tool-fail / MAX_ROUNDS timeout / budget / interrupt) —
  safe because `worker_run`'s try-with-resources VT executor has already joined+closed the fan-out before `invoke()`
  returns, so no worker async work survives teardown. `destroyScope` REBINDS `CURRENT_AGENT` to the child so any
  `@PreDestroy` a scoped bean fires resolves in the CHILD's context, not the parent turn's (retirement runs on the
  parent thread). TRAPS the 6-dim `/code-review` caught (all applied): (a) `reduce` clears `turn.spawns` PER ROUND, so
  the `finally` must iterate a SEPARATE accumulated `spawnedIds` list, not `turn.spawns` (empty by then). (b) The
  "cannot mask the turn result" guard must catch **`Throwable`**, not `RuntimeException` — a native teardown `Error`
  (LinkageError) thrown from a `finally` would otherwise REPLACE the in-flight exception / discard the answer. (c) Do
  NOT invent a package-private `scopeDestroyer` seam + a `ClientProxy.unwrap` test just to drive a cleanup-failure
  counter — ArC swallows a failing `@PreDestroy`, so that path is un-drivable in production and the seam is test-only
  damage (§2 "no error handling for impossible scenarios"); instead put the `retireFailureCount()` counter on
  `SupervisorGraph.retireWorkers` where the existing fake `throwOnRetire` drives it NATURALLY, and let `retire`
  propagate. (d) A bounded/concurrent stress test asserting only `activeWorkerCount()` (== `ephemeralIds.size()`,
  which `retire` clears FIRST) stays green even if the `specs`-map leak — the whole point of #177 — regressed; also
  assert `persona(worker)` throws (spec evicted), not just the gauge. (e) Switching to generated ids breaks any test
  asserting the exact childId in the worker digest (`FakeWorkerRunner` returns `childId.value() + " result for: " +
  task`) — assert the task SUBSTRING, and for the `assertFalse` compression tests drop the id prefix
  (`"researcher result for"` → `"result for"`) or the assertion passes vacuously. ledger/task rows survive (retire
  touches no DB). Native EXECUTION of the retire path has no dedicated deterministic IT (the bundled `EchoModelProvider`
  never emits `spawn_worker`, and a native IT is out-of-process so it cannot inspect context liveness) — covered by
  JVM `@QuarkusTest` + native-COMPILEs/boots + the by-construction argument + the defensive warning. [#177]

- **Atomic capability-safe hot-reload (#178) is a per-turn immutable LEASE + a snapshot-resident derived belt +
  a validate-then-atomic-swap publish — NOT a refcount/drain.** The pre-#178 gap was that agent config was
  re-read live at 4+ independent sites in one turn (`TurnService`, `Agent.respond`, `AgentToolBelt`, cron/workers),
  the filtered belt was cached on the shared `@AgentScoped` `AgentToolBelt` bean (so a capability-REDUCING reload
  was never revoked for the bean), and `onConfigChange` was a bare evict that a concurrent in-flight `persona`
  read would miss-and-throw. Fix, four moves: (1) version each registry entry as an immutable
  `LiveAgent(long generation, AgentSpec spec)` stamped by a monotonic `AtomicLong` (never reused → delete+recreate
  is ABA-distinguishable); (2) a turn LEASES one generation at entry and binds it into
  `AgentRegistry.CURRENT_AGENT_SPEC` (a `ScopedValue<LiveAgent>`) — `persona(id)`/`spec(id)` return the leased
  snapshot when it is bound for that id (enforce-iff-bound, the P2-11 `CURRENT_EFFECTIVE_SCOPES` pattern), so ALL
  the existing call-sites become coherent with ZERO edits to each; (3) drop `AgentToolBelt`'s `volatile filtered`
  cache — the belt is recomputed from the lease per turn (called once, cheap glob match), so the derived state
  lives in the GC-per-turn snapshot, not on the shared bean; (4) rewrite `onConfigChange` from evict-only to
  atomic publish: rebuild the FULL spec OFF the lock, then `specs.replace` (the `ToolRegistry` volatile-swap
  precedent) — an invalid/half-written file is DROPPED (`reloadFailureCount++`, keep the last-known-good, never
  widen), `DELETED` removes + `destroyScope`s so a new `lease` throws (rejected new-turn) while in-flight turns
  finish on their leased snapshot. Load-bearing decisions: **NO refcount/drain is needed** — because the derived
  belt lives in the lease (not the `@AgentScoped` context), `destroyScope` on reload cannot disrupt an in-flight
  turn (it holds an immutable `LiveAgent`), satisfying "destroy doesn't disrupt leases" without a drain
  mechanism; the `@AgentScoped` context is keyed by `AgentId` (not generation), so "retire the OLD generation's
  context after its leases" is only achievable by making the beans stateless-for-config (this design) rather than
  re-keying the ArC context (invasive) — the issue's "recompute belts per generation" IS the stateless-belt fix.
  The `ScopedValue` lives on `AgentRegistry`, not `CurrentAgent`, because `LiveAgent`/`AgentSpec` are in the
  `agent` package and `agent`→`context` already exists, so a `context`→`agent` field would cycle the packages.
  The two files of an agent (`.md`+`.json`) arrive as two separate `ConfigurationChangedEvent`s — rebuilding the
  FULL spec on each is safe (every published generation is a complete, validated spec, never a cross-generation
  field mix); a transient intermediate generation (new `.json` + old `.md`, if both parse) is valid, not partial.
  A worker VT does not inherit the `ScopedValue`, so it reads its own ephemeral child spec from the map — correct
  (child specs are immutable, never touched by `onConfigChange`, retired at turn end #177). Approval-resume
  re-dispatches through `TurnService.dispatch`, so it leases the CURRENT (post-reload) generation — the safe
  choice (a capability-reducing edit takes effect; the approved tool still passes the new config's gates).
  TESTING split: the freeze MECHANISM is proven at the registry level (a bound lease keeps the old model after a
  concurrent reload); the production BINDING is proven by a `LeaseProbeModelProvider` capturing
  `CURRENT_AGENT_SPEC.isBound()` at chat time (red-check: delete the `.where(...)` and it flips false); an
  in-flight turn completing when its agent is deleted mid-turn is a concurrent latch test. TRAP (the #167
  stub-models-the-new-call discipline, hit again): `CronScheduler.fire` grew a `registry.lease(id)` call, so
  `CronSchedulerTest`'s `@Vetoed StubRegistry` had to override `lease()` (reusing its `persona(id)`) or `fire`
  caught the throw and silently dropped delivery. Native is the sanctioned [M4] `WatchService` OS-polling
  carve-out — the deterministic event-fire is the tested path on both JVM and native; the reload machinery is
  pure map/`ScopedValue` (no reflection), native-identical. [#178]

