> **AUTHORITATIVE CORRECTIONS — apply these over the body below.**
> Folded in from the integration + adversarial review (see `README.md`).
>
> 1. **Do NOT author a second `CoreReflectionRegistration`.** M5 creates the single engine holder;
>    M8 **appends** its Layer-0 targets (`ModelRef`, `FallbackTriggered`, `FallbackReasons`) to it.
>    ULTRAPLAN §6.3 mandates a single holder. (review GAP 2 / integration decision a)
> 2. **Annotate `ProviderCall` with `io.quarkus.runtime.annotations.RegisterForReflection`**, NOT the
>    SDK re-export — the SDK annotation is inert (no BuildStep yet) and would register nothing.
>    (review GAP 1)
> 3. **`forvum-engine/pom.xml` needs `forvum-core` + `forvum-sdk`**, and the `langchain4j-core`
>    availability via `quarkus-langchain4j-bom` is an assumption — keep the §10 harvest as a HARD gate
>    before relying on it; if unmanaged, surface it in `forvum-bom` (never pin in the module pom).
>    (review GAP 3)
> 4. **Confirmed sound:** the engine-local `FallbackLink` carrier (core `FallbackChain` is TBD/Group-4c,
>    verified absent) and **declining** the `reason→FailureClass` migration (it would lose telemetry
>    granularity). Both need a one-line ULTRAPLAN doc amendment + maintainer sign-off. (review confirmation)

# M8 — `FallbackChatModel` + `FailureClassifier` — Implementation Plan

## 1. Objective & Definition of Done

Build, in `forvum-engine`, a LangChain4j `ChatModel`/`StreamingChatModel` decorator that iterates a fallback chain of model bindings, classifies provider exceptions through a typed `FailureClassifier`, continues on retryable faults, stops on non-retryable/unknown faults, and writes one `provider_calls` ledger row per attempted call (with `is_fallback = 1` on calls past the first). The classifier maps the `dev.langchain4j.exception` hierarchy to a sealed `FailureClass permits Retryable, NonRetryable, Unknown` (§5.4).

**Literal success criterion (ULTRAPLAN §7.1 M8 Verify), reproduced verbatim:**

> unit test with a mock `ChatModel` that throws `RateLimitException` on the first call and returns on the second; assert `provider_calls` gets two rows and the second has `is_fallback = 1`.

DoD: the Verify test is green; both decorators + sealed `FailureClass` + `FailureClassifier` exist with the §5.4 mapping; the decorator runs on a virtual thread with no `synchronized`; all new Layer-2 serialized DTOs carry `@RegisterForReflection`; the module native-compiles via `forvum-app` and the binary boots with no `~/.forvum/`. Per the persistence split (see §3 Decision F), the row-count + `is_fallback` DB assertion ships as a *gated* `*IT` against M5's `provider_calls` table; the M5-independent core (decorator iteration + classifier mapping against a mock `ChatModel` and an in-memory recorder) is the Red→Green deliverable that is unconditionally green for this milestone.

---

## 2. Current relevant state

**Exists and is built upon:**
- `forvum-core/src/main/java/ai/forvum/core/ModelRef.java` — `record ModelRef(String provider, String model)` with `parse`, case-folding, first-colon split. This is the chain-link identity and the `provider_calls.(provider, model)` source.
- `forvum-core/src/main/java/ai/forvum/core/event/FallbackTriggered.java` — `record FallbackTriggered(Instant timestamp, ModelRef failed, ModelRef next, String reason)`; Javadoc already states the M8 migration to a `FailureClass` enum is scheduled. **`reason` is currently a `String`.**
- `forvum-core/src/main/java/ai/forvum/core/event/FallbackReasons.java` — constants `RATE_LIMIT`, `TIMEOUT`, `SERVER_ERROR`, `COST_BUDGET`.
- `forvum-core/src/main/java/ai/forvum/core/event/AgentEvent.java` — sealed, 6 permits; `FallbackTriggered` is one.
- `forvum-core/src/main/java/ai/forvum/core/budget/*` — `CostBudget`, `Window`/`DayWindow`/`SessionWindow`, `BudgetMeter`, `Usage`, `Spend`, `ExhaustionCause`, `BudgetExhaustedException`. All present (M2 complete). `BudgetMeter` is an interface; its impl lands in M5.
- `forvum-sdk/src/main/java/ai/forvum/sdk/ModelProvider.java` — sealed SPI; `resolve(ModelRef) → ChatModel` is explicitly deferred to provider milestones (M9–M12). **M8 must not depend on a resolved provider**; it decorates a `ChatModel` handed to it.
- `forvum-engine/pom.xml` — Layer-2 library: `quarkus-maven-plugin` with `generate-code` + `generate-code-tests` (no `build` goal), Surefire with `org.jboss.logmanager.LogManager`, enforcer `enforce-engine-extension-agnostic`. **Currently declares only `quarkus-arc`, `quarkus-jackson`, `quarkus-junit` (test). It does NOT declare `forvum-core`, `forvum-sdk`, or any langchain4j artifact** — M8 must add these.
- `forvum-engine/src/main/resources/META-INF/beans.xml` — present, `bean-discovery-mode="annotated"`.
- `forvum-engine` test convention (M4): `@QuarkusTest` boots in-JVM via Surefire; `TestHomeProfile` redirects config home to a temp dir.
- `forvum-app/pom.xml` — only runnable artifact; depends on `forvum-engine`; has the `native` profile + Failsafe IT. This is where M8 native-compiles.

**Net-new in this milestone:**
- New package `forvum-engine/src/main/java/ai/forvum/engine/model/` (sibling to existing `config/`).
- `FallbackChatModel.java`, `FallbackStreamingChatModel.java`, `FailureClass.java` (sealed), `FailureClassifier.java`.
- A `@RegisterForReflection(targets = {…})` Layer-0 holder in `forvum-engine` (does not yet exist — see Native checklist).
- The chain shape type (see Decision A).

**Confirmed absent / TBD (blocking — see §3):**
- `forvum-core/.../FallbackChain.java` does **not** exist. §4.3.5.3 reads literally `*TBD (Group 4c).*`. The M2 milestone block lists `FallbackChain.java` in its Files, but it was not delivered (M2 commit `e6dab06` shipped events/records without it). **This blocks M8 unless resolved.**

---

## 3. Design decisions to lock

### Decision A — `FallbackChain` concrete shape (BLOCKING)
**Finding:** `FallbackChain` is not in `forvum-core`; §4.3.5.3 is `TBD (Group 4c)`. §5.4 references `FallbackChain(primary, List<fallback>, CostBudget)` prose-only; §4.3.5.2 Decision 9 + the "Reserved future extension paths" note explicitly contemplate Group 4c *enriching* chain links later (per-link `costDims`). So the shape is genuinely unstable.

**Recommendation:** **Do not pin the public `forvum-core` `FallbackChain` type in M8.** Instead, M8 defines a minimal **engine-local** carrier and a per-link binding so the decorator can be built and verified without coupling to an unratified core type:

- `record FallbackLink(ModelRef ref, dev.langchain4j.model.chat.ChatModel model)` (engine-local, `ai.forvum.engine.model`) — the decorator iterates `List<FallbackLink>`. This keeps M8 independent of how providers later resolve `ModelRef → ChatModel` (M9+), and independent of the still-TBD core `FallbackChain`.
- The `CostBudget` (already in core) is accepted as a separate constructor parameter on `FallbackChatModel`, nullable for M8 (budget enforcement via `BudgetMeter` is wired but the meter impl is M5; pass `null` budget + a no-op/absent meter in M8 unit tests).

**Rationale:** §4.3.5.2 already lays out the budget enforcement contract but the `BudgetMeter` impl is M5; §4.3.5.3 chain shape is Group 4c. Pinning a core `FallbackChain` now would (a) require a cross-cutting M2/core change outside M8's scope and (b) risk churn when Group 4c lands. An engine-local link list satisfies the §5.4 behavior and the §7.1 Verify with zero core API commitment. **Dependency flagged:** when Group 4c ratifies `forvum-core.FallbackChain`, a follow-up adapts `FallbackChatModel`'s constructor to accept it and resolve links via `ModelProvider.resolve`. State this explicitly in the PR description and in §13. (Cites §4.3.5.3 TBD; §4.3.5.2 "Reserved future extension paths".)

### Decision B — `FailureClass` shape and location
**Recommendation:** sealed interface in **`forvum-engine`** package `ai.forvum.engine.model`: `public sealed interface FailureClass permits Retryable, NonRetryable, Unknown` with three record/singleton permits. §5.4 says "sealed `FailureClass permits Retryable, NonRetryable, Unknown`". Keep it in the engine, **not** core, for M8.

**Rationale:** §5.4 places `FailureClass` in the classification logic that lives in `forvum-engine`. The doc's only core touchpoint is the *future* `FallbackTriggered.reason` migration (Decision C). Keeping `FailureClass` engine-local in M8 avoids a premature core API and the Layer-0 reflection-holder churn. (Cites §5.4.)

### Decision C — `FallbackTriggered.reason` migration to `FailureClass` (cross-module)
**Finding:** §4.3.2 note (line 530) and the `FallbackTriggered` Javadoc say migration to a `FailureClass` enum is "scheduled for M8 once the taxonomy stabilizes." But the doc's own §5.4 describes `FailureClass` as a *sealed interface*, not an enum, and the taxonomy (`Retryable`/`NonRetryable`/`Unknown`) is the *retry* axis, **not** the user-facing *reason* axis (`rate_limit`/`timeout`/`server_error`/`cost_budget`).

**Recommendation:** **Do NOT migrate `FallbackTriggered.reason` to `FailureClass` in M8.** Keep `reason: String` populated from `FallbackReasons.*`. M8 produces the `FailureClass` purely as the *internal* retry decision; the `FallbackTriggered` event continues to carry the granular `FallbackReasons` token (which is finer-grained than the 3-way `FailureClass` and is what the ledger/observability consumers expect). The classifier maps exception → `FailureClass` (retry decision) **and** the decorator separately maps exception → `FallbackReasons` token (event reason). Flag in the PR that the §4.3.2/Javadoc "migration scheduled for M8" note is *intentionally deferred/declined* because the stabilized taxonomy (sealed interface, retry-axis) is orthogonal to the reason token, and recommend the doc note be amended to "reason stays a `FallbackReasons` String; `FailureClass` is the internal retry axis."

**Rationale:** migrating `reason` to the 3-value `FailureClass` would *lose* information (`rate_limit` vs `timeout` vs `server_error` all collapse to `Retryable`) that §4.2 and §3.4 telemetry rely on. This is the "taxonomy stabilizes" caveat resolving against migration. This touches the `forvum-core` `FallbackTriggered`/`FallbackReasons` types — **cross-module coordination point, see §13.** No code change to `forvum-core` is required by this recommendation (the decision is to *not* change it); only a one-line doc amendment to §4.3.2 line 530 + the `FallbackTriggered` Javadoc, which is a separate surgical doc edit the integrator sequences.

### Decision D — exception → `FailureClass` mapping (the classifier contract)
Per §5.4, mapping against `dev.langchain4j.exception` (confirmed via context7, all extend `LangChain4jException`; `HttpException extends LangChain4jException`):
- `RateLimitException`, `TimeoutException`, `InternalServerException` → `Retryable` (429 / 408 / 5xx).
- `AuthenticationException`, `ModelNotFoundException`, `InvalidRequestException` → `NonRetryable` (401/403 / 404 / other-4xx).
- `LangChain4jException` (root) and anything else → `Unknown` (operator alert; treated as non-retryable; never silently retried).

**Implementation:** `switch` on exception type using Java 25 pattern matching with sealed-coverage discipline. Since `dev.langchain4j.exception` is not a sealed hierarchy, the `switch` has a `default → Unknown` arm; document that adding a provider requires reviewing this classifier (the §5.4 "enforced by a compile-time check on the sealed hierarchy" applies to *our* `FailureClass` sealed type — a `switch` over `FailureClass` elsewhere fails to compile if a permit is added). Map `reason` tokens in parallel: `RateLimitException→RATE_LIMIT`, `TimeoutException→TIMEOUT`, `InternalServerException→SERVER_ERROR`. (Cites §5.4.)

### Decision E — `provider_calls` row write + `is_fallback` semantics
Per §5.4 + §4.2: write one row per *attempted* call (success or failure). `is_fallback = 0` for the first link, `1` for every subsequent link. On a failed attempt, record the failing exception's **FQCN in `provider_calls.error`** (nullable; no schema change — §5.4). On success, `error = NULL`, `tokens_in/out` from `ChatResponse.metadata().tokenUsage()`, `cost_usd = NULL` for cost-less providers, `latency_ms` measured per attempt. (Cites §4.2 DDL lines 342–355, §5.4.)

### Decision F — persistence split (M5 gating; M5 runs in parallel)
The DB write is the *only* part touching M5's `provider_calls` table/Panache entity, which does not exist yet (M5 in parallel). **Split:**
- **(a) M5-independent core (the Red→Green deliverable, unconditionally green):** decorator iterates links, classifier classifies, the row-write is funneled through a narrow `ProviderCallRecorder` SPI (engine-local interface, one method `record(ProviderCall row)`). Unit tests inject an in-memory `ProviderCallRecorder` (a `List`-backed recorder) and assert: two records captured, second `isFallback() == true`, first `error == RateLimitException FQCN`, second `error == null`. This satisfies the *behavioral* contract of the Verify without a database.
- **(b) M5-gated `*IT`:** a `@QuarkusTest` with `@TempDir` SQLite that wires the *real* Panache-backed `ProviderCallRecorder` impl (delivered by M5) and asserts the literal `SELECT COUNT(*)` = 2 and `is_fallback` of the second row = 1. **Gate it** with `@DisabledIfSystemProperty`/`@EnabledIf` keyed on M5 presence (e.g. presence of the `ProviderCallEntity` class on the classpath) so it is *skipped*, never *failing*, until M5 merges. Make the gating explicit in the test Javadoc and §13.

**Rationale:** §7.1 Verify literally asserts on `provider_calls` rows, which requires M5. The `ProviderCallRecorder` seam is the clean decoupling: M8 owns the interface + the in-memory test double; M5 owns the Panache impl. This also matches Decision F-style "BudgetMeter is a service, default impl lives in M5" from §4.3.5.2. (Cites §7.1 M8 Verify; §4.3.5.2 Decision 7; §4.2.)

### Decision G — concurrency
The fallback loop runs on a virtual thread; **no `synchronized`** anywhere in `ai.forvum.engine.model` (CLAUDE.md §11, §3.8 grep). The decorator is stateless per call; the `ProviderCallRecorder` impl owns its own thread-safety (M5's concern). No shared mutable state in the loop → no lock needed in M8. (Cites CLAUDE.md §11; ULTRAPLAN §3.8.)

### Decision H — cost-budget short-circuit (scope boundary)
§4.3.5.2 Decision 9 says cost exhaustion throws `BudgetExhaustedException` and short-circuits (no further links). The `BudgetMeter` impl is M5. **For M8:** wire the pre-call `BudgetMeter.usage(budget)` check *behind a nullable budget* — if `budget == null` or `meter == null`, skip the check (the M8 default). Add one unit test with a stub `BudgetMeter` returning `exhausted=true` asserting `BudgetExhaustedException` is thrown and **no link is attempted**. This keeps M8's contract complete without depending on M5's meter impl. (Cites §4.3.5.2 Decision 9; §5.4.)

---

## 4. Step-by-step TDD plan

All test classes under `forvum-engine/src/test/java/ai/forvum/engine/model/`; all production classes under `forvum-engine/src/main/java/ai/forvum/engine/model/`. Engine tests run via **Surefire** (`./mvnw -pl forvum-engine test -B -Dstyle.color=never`), per CLAUDE.md §4 exception (headless library).

**Step 0 — pom + harvest prerequisites (no test).** Add `forvum-core`, `forvum-sdk`, and `dev.langchain4j:langchain4j-core` to `forvum-engine/pom.xml` (see §5/§6). Run `quarkus_skills` + the harvest step (see §10) *before* writing code. Verify `./mvnw -pl forvum-engine -DskipTests validate` passes the enforcer (langchain4j-core is not an `ai.forvum:*` artifact, so the layering rule is unaffected; it is also not `io.quarkus*`).

**Step 1 — `FailureClassifierTest` (Red) → `FailureClass` + `FailureClassifier` (Green).**
- Test `ai.forvum.engine.model.FailureClassifierTest`: `@ParameterizedTest` `@MethodSource` over `(exceptionInstance, expectedFailureClass, expectedReasonToken)` covering all six typed exceptions + the `LangChain4jException` root + a non-LangChain4j `RuntimeException`. Asserts `classifier.classify(ex)` returns the right `FailureClass` permit (via `instanceof` pattern) and `classifier.reason(ex)` returns the right `FallbackReasons` token (or null/`UNKNOWN`-marker for non-retryable). Also a `switch`-exhaustiveness compile assertion: a small helper `String describe(FailureClass)` switches over the sealed type with no `default` — proves the sealed contract.
- Green: `FailureClass.java` (sealed: `Retryable`, `NonRetryable`, `Unknown` as records or enum-like singletons), `FailureClassifier.java` (`@Singleton` CDI bean; pure `switch` mapping per Decision D).
- Verify: `./mvnw -pl forvum-engine test -Dtest=FailureClassifierTest -B -Dstyle.color=never`; read `target/surefire-reports/*.txt`.

**Step 2 — `FallbackChatModelTest` core (Red) → `FallbackChatModel` (Green).** *This is the M5-independent core of the §7.1 Verify.*
- Test `ai.forvum.engine.model.FallbackChatModelTest`: builds two `FallbackLink`s with mock `ChatModel`s. The first mock's `chat(ChatRequest)` throws `dev.langchain4j.exception.RateLimitException`; the second returns a stub `ChatResponse` (with a `TokenUsage`). Inject an in-memory `ProviderCallRecorder` (test double, `List<ProviderCall>`). Call `fallback.chat(request)`.
  - Assert: returned `ChatResponse` is the second mock's response.
  - Assert: recorder captured exactly **two** `ProviderCall` rows.
  - Assert: row[0] `isFallback()==false`, `error()==RateLimitException.class.getName()`, `model()/provider()` = first link's `ModelRef`.
  - Assert: row[1] `isFallback()==true`, `error()==null`, `tokensIn/out` from the stub usage.
- Additional unit tests in the same class: (i) non-retryable first call (`AuthenticationException`) → no second attempt, one row, exception rethrown; (ii) `Unknown` (root `LangChain4jException`) → treated as non-retryable, one row, rethrown (never silently retried — §5.4); (iii) all links exhausted retryably → last exception rethrown, N rows; (iv) `FallbackTriggered` event emission asserted via a captured `Event<AgentEvent>` (inject a test `Event` double or a CDI observer) carrying `failed`/`next` `ModelRef`s and the `FallbackReasons.RATE_LIMIT` token.
- Green: `FallbackChatModel.java` implementing `dev.langchain4j.model.chat.ChatModel`; constructor `(List<FallbackLink> links, CostBudget budget /*nullable*/, BudgetMeter meter /*nullable*/, ProviderCallRecorder recorder, Event<AgentEvent> events)`. Loop over links (no `synchronized`); per attempt: optional budget pre-check (Decision H), measure latency, call `link.model().chat(request)`, on success record a success row + return; on `LangChain4jException` classify, record a failure row (FQCN in `error`), if `Retryable` and more links remain emit `FallbackTriggered` and continue, else rethrow. Plus `ProviderCallRecorder` (interface) + `ProviderCall` (record carrying the row fields) + `FallbackLink` (record).
- Verify: `./mvnw -pl forvum-engine test -Dtest=FallbackChatModelTest -B -Dstyle.color=never`.

**Step 3 — `FallbackStreamingChatModelTest` (Red) → `FallbackStreamingChatModel` (Green).**
- Test: two `FallbackLink`-streaming mocks. First mock's `chat(request, handler)` invokes `handler.onError(new RateLimitException(...))`; second streams `onPartialResponse` tokens then `onCompleteResponse`. The decorator wraps the user's `StreamingChatResponseHandler`: on first link's `onError` with a `Retryable` class, it advances to the next link **without surfacing the error to the user handler**; on the next link's stream it forwards partials and completion, and emits tokens "as soon as the first successful stream starts" (§5.4). Assert: user handler received the partials + completion, never the `onError`; recorder captured two rows, second `isFallback()==true`. Non-retryable `onError` → forwarded to user handler, one row.
- Green: `FallbackStreamingChatModel.java` implementing `dev.langchain4j.model.chat.StreamingChatModel`. The retry-on-`onError` advance is the streaming subtlety: a per-attempt internal handler that decides retry vs. forward based on `FailureClassifier`. No `synchronized`; if attempt index needs mutation across the async callback use an `AtomicInteger`/`ReentrantLock`-free immutable recursion (recommended: recursive `attempt(index)` invocation from inside `onError`).
- Verify: `./mvnw -pl forvum-engine test -Dtest=FallbackStreamingChatModelTest -B -Dstyle.color=never`.

**Step 4 — budget short-circuit test (Red→Green).** Stub `BudgetMeter` returning `Usage(exhausted=true, cause=USD_CAP_HIT)`; assert `FallbackChatModel.chat` throws `BudgetExhaustedException`, no link `chat` invoked, optionally a `FallbackTriggered(reason=COST_BUDGET)` emitted before throwing (per §4.3.5.2 Decision 9). Green: the pre-check branch from Step 2's constructor.

**Step 5 — M5-gated `ProviderCallPersistenceIT` (Red against M5; SKIPPED until M5).**
- `ai.forvum.engine.model.ProviderCallPersistenceIT` (`@QuarkusTest`, `@TempDir` SQLite via a test profile mirroring `TestHomeProfile`). Wires the real M5 Panache `ProviderCallRecorder` impl. Drives the same two-mock scenario as Step 2, then runs the literal Verify SQL: `SELECT COUNT(*) FROM provider_calls` == 2 and the second row's `is_fallback` == 1. **Gated** with `@EnabledIf`/class-presence guard on M5's entity so it skips cleanly pre-M5. This is the test that satisfies §7.1's literal DB assertion once M5 lands.
- Verify (post-M5): `./mvnw -pl forvum-engine test -Dtest=ProviderCallPersistenceIT -B -Dstyle.color=never`.

**Step 6 — native wiring + Layer-0 reflection holder.** Add the `@RegisterForReflection(targets={…})` holder (see §7), confirm `forvum-app` pulls engine (already does), and rely on the M20/per-PR native compile in `forvum-app`. No new native smoke behavior is required by M8 beyond compile (the behavioral assertion is the JVM Surefire test); note this in the Verify block per CLAUDE.md §5 (native-compile mandatory, behavioral native assertion not required here since there's no OS-host nuance).

**Refactor pass:** extract the shared attempt/record/classify logic referenced by both decorators into a small package-private helper (`FallbackAttempts`) so streaming + non-streaming share the classifier/recorder/event code with no duplication. Run `/code-review` (high) before merge per M4 lesson.

---

## 5. Files

Mapped to §7.1 M8 Files list (`FallbackChatModel.java`, `FallbackStreamingChatModel.java`, `FailureClass.java` (sealed), `FailureClassifier.java`) plus the wiring the milestone requires:

**Created (production):**
- `forvum-engine/src/main/java/ai/forvum/engine/model/FallbackChatModel.java` — `implements dev.langchain4j.model.chat.ChatModel`. *(§7.1)*
- `forvum-engine/src/main/java/ai/forvum/engine/model/FallbackStreamingChatModel.java` — `implements dev.langchain4j.model.chat.StreamingChatModel`. *(§7.1)*
- `forvum-engine/src/main/java/ai/forvum/engine/model/FailureClass.java` — `sealed interface … permits Retryable, NonRetryable, Unknown` (+ the three permits, each in its own `.java` or as nested permitted records; prefer separate files `Retryable.java`, `NonRetryable.java`, `Unknown.java` to match core's one-type-per-file convention). *(§7.1)*
- `forvum-engine/src/main/java/ai/forvum/engine/model/FailureClassifier.java` — `@Singleton` CDI bean; `classify(Throwable)→FailureClass`, `reason(Throwable)→String` (FallbackReasons token). *(§7.1)*
- `forvum-engine/src/main/java/ai/forvum/engine/model/FallbackLink.java` — `record FallbackLink(ModelRef ref, ChatModel model)` (and a streaming counterpart or a shared link carrying both; recommend a single `record FallbackLink(ModelRef ref, ChatModel chat, StreamingChatModel streaming)` with nullable streaming). *(net-new; Decision A)*
- `forvum-engine/src/main/java/ai/forvum/engine/model/ProviderCallRecorder.java` — interface `void record(ProviderCall row)` (the M5 seam). *(net-new; Decision F)*
- `forvum-engine/src/main/java/ai/forvum/engine/model/ProviderCall.java` — `record ProviderCall(...)` mirroring `provider_calls` columns; **carries `@RegisterForReflection`** (Layer-2 DTO). *(net-new; Decision E/F)*
- `forvum-engine/src/main/java/ai/forvum/engine/model/CoreReflectionRegistration.java` — `@RegisterForReflection(targets = { ModelRef.class, FallbackTriggered.class, FallbackReasons.class, CostBudget.class, Usage.class, Spend.class, ExhaustionCause.class })` empty holder for the Layer-0 types this milestone serializes/reflects across the native boundary (CLAUDE.md §5; ULTRAPLAN §6.3). *(net-new)*

**Created (tests, Surefire):**
- `forvum-engine/src/test/java/ai/forvum/engine/model/FailureClassifierTest.java`
- `forvum-engine/src/test/java/ai/forvum/engine/model/FallbackChatModelTest.java`
- `forvum-engine/src/test/java/ai/forvum/engine/model/FallbackStreamingChatModelTest.java`
- `forvum-engine/src/test/java/ai/forvum/engine/model/InMemoryProviderCallRecorder.java` (test double)
- `forvum-engine/src/test/java/ai/forvum/engine/model/ProviderCallPersistenceIT.java` (M5-gated)

**Modified:**
- `forvum-engine/pom.xml` — add `forvum-core` + `forvum-sdk` + `langchain4j-core` dependencies (§6). No plugin-goal change (already `generate-code`/`generate-code-tests`, no `build`). `META-INF/beans.xml` already present (`bean-discovery-mode="annotated"`) — `@Singleton FailureClassifier` is discovered automatically; no edit needed.
- `forvum-bom/pom.xml` — if and only if the harvest (§10) shows `langchain4j-core` is *not* already managed by the imported `quarkus-langchain4j-bom`, surface it (see §6). Expected: **no edit needed** (the Quarkiverse BOM brings langchain4j-core transitively, per its comment at line 38).

**No new module, no new `beans.xml`, no native profile** in engine (native builds only in `forvum-app` — M4 lesson). Reachability metadata: the `@RegisterForReflection` holder is the mechanism; no hand-authored `META-INF/native-image/*.json` is needed for M8 (that is the LangGraph4j concern, M18/Risk #13).

---

## 6. Dependencies

All BOM-managed; **no version pinned in module poms** (CLAUDE.md §2, §7).

Add to **`forvum-engine/pom.xml`** `<dependencies>`:
- `ai.forvum:forvum-core` (version omitted — managed by `forvum-bom`'s `dependencyManagement`).
- `ai.forvum:forvum-sdk` (managed by `forvum-bom`) — needed for the re-exported `@RegisterForReflection` (CLAUDE.md §5: Layer-0 holder lives in engine and uses the SDK-re-exported annotation; M8 also imports `ai.forvum.sdk.RegisterForReflection`).
- `dev.langchain4j:langchain4j-core` (version omitted — managed transitively by the `quarkus-langchain4j-bom` already imported in `forvum-bom`). Provides `dev.langchain4j.model.chat.ChatModel` / `StreamingChatModel`, `dev.langchain4j.model.chat.request.ChatRequest`, `dev.langchain4j.model.chat.response.ChatResponse` / `StreamingChatResponseHandler`, `dev.langchain4j.model.output.TokenUsage`, and the `dev.langchain4j.exception.*` hierarchy. *(§7.1 M8 Deps: "`dev.langchain4j:langchain4j-core` (from `forvum-bom`)".)*

**Verification step (§10 harvest):** confirm `quarkus-langchain4j-bom:1.11.0.CR1` manages `dev.langchain4j:langchain4j-core` (expected: yes, LangChain4j core 1.15.1 per CLAUDE.md §2). If the engine compile cannot see langchain4j-core through the Quarkiverse BOM import, add a `<dependencyManagement>` entry for `dev.langchain4j:langchain4j-core` to **`forvum-bom`** with the version sourced from the harvested throwaway app (still no pin in the module pom). Do **not** pin LangChain4j core independently if the BOM already manages it (CLAUDE.md §2: "do NOT pin independently").

No other new dependencies. `quarkus-arc` (already present) supplies `@Singleton`, `jakarta.enterprise.event.Event`. `quarkus-junit` (test, present) supplies `@QuarkusTest`.

---

## 7. Native-first checklist

- **`@RegisterForReflection` placements (Layer-2 DTOs):** `ProviderCall` (the serialized/reflected row record) carries `@ai.forvum.sdk.RegisterForReflection` directly (it lives in Layer-2 engine). Any other engine record that is JSON-serialized or reflectively constructed gets it too. The decorators/classifier are framework-managed CDI beans, not DTOs — no annotation needed on them.
- **forvum-core exemption + holder:** `ModelRef`, `FallbackTriggered`, `FallbackReasons`, and the budget types (`CostBudget`, `Usage`, `Spend`, `ExhaustionCause`) are Layer-0 and **must not** carry the annotation (core bans `io.quarkus*`; CLAUDE.md §5/§12). They are registered for native from engine via the new `CoreReflectionRegistration.java` `@RegisterForReflection(targets = {…})` holder. (ULTRAPLAN §6.3; CLAUDE.md §5.)
- **No-reflection confirmation:** all serialized types are records with reflection-free canonical constructors; classification is a `switch` (no reflection); the decorator uses direct method calls, no `Class.forName`/proxies. `error` column stores `ex.getClass().getName()` — that is a `String`, not reflective loading.
- **Reachability metadata:** none hand-authored for M8 (no LangGraph4j here). The `@RegisterForReflection` holder is the only native hint.
- **Wired into `forvum-app` so it native-compiles:** `forvum-app/pom.xml` already depends on `forvum-engine`; the new `ai.forvum.engine.model` classes enter the app's build-time augmentation automatically. The per-PR native build in `forvum-app` (CLAUDE.md §5) compiles them. **No new `@Startup` bean is introduced by M8** (the decorators are constructed on demand by the routing layer in later milestones, not at boot), so there is **no graceful-boot-without-`~/.forvum/` concern for M8** — the M4 native-smoke (binary boots with no `~/.forvum/`) is unaffected because M8 adds no boot-time I/O. Explicitly confirm in the PR that no `@Startup`/`StartupEvent` observer is added.
- **Native behavioral assertion:** none required for M8 (CLAUDE.md §5 mandates native-*compile*, not a behavioral native assertion, where there is no OS-host nuance — there is none here; the behavior is pure JVM logic verified by Surefire).

---

## 8. Tests

| Test | Type | Where it runs | Asserts |
|---|---|---|---|
| `FailureClassifierTest` | unit `*Test` | **Surefire** (`./mvnw -pl forvum-engine test`) | exception→`FailureClass` + reason-token mapping (Decision D); sealed-switch exhaustiveness |
| `FallbackChatModelTest` | unit `*Test` | **Surefire** | **the M5-independent core of §7.1**: two-link mock, RateLimit→retry→success, 2 recorder rows, second `isFallback`, FQCN in error[0], `FallbackTriggered` emission, non-retryable/unknown/exhausted paths |
| `FallbackStreamingChatModelTest` | unit `*Test` | **Surefire** | streaming retry-on-`onError`, user handler shielded from retryable error, 2 rows |
| budget short-circuit (in `FallbackChatModelTest`) | unit `*Test` | **Surefire** | `BudgetExhaustedException`, no link attempted (Decision H) |
| `ProviderCallPersistenceIT` | integration `*IT` (`@QuarkusTest` + `@TempDir` SQLite) | **Surefire** (engine is headless library — CLAUDE.md §4 exception), **GATED on M5** | the **literal §7.1 Verify**: `SELECT COUNT(*) FROM provider_calls == 2`, second row `is_fallback == 1` |
| native parity | compile only | `forvum-app` `-Pnative` (CI matrix) | M8 classes native-compile; no behavioral native assertion needed |

**Why Surefire, not Dev MCP:** `forvum-engine` carries no `build` goal / no HTTP, so `quarkus:dev` cannot attach and the Dev-UI test runner cannot run it; its `@QuarkusTest` still boots Quarkus in-JVM via Surefire (CLAUDE.md §4 exception; M4 precedent). Use `-B -Dstyle.color=never` and read `forvum-engine/target/surefire-reports/*.txt` (M4 ANSI lesson).

**Exact Verify command (M5-independent core, always green this milestone):**
```
./mvnw -pl forvum-engine test -Dtest=FallbackChatModelTest,FailureClassifierTest,FallbackStreamingChatModelTest -B -Dstyle.color=never
```
**Exact Verify command for the literal §7.1 DB assertion (post-M5):**
```
./mvnw -pl forvum-engine test -Dtest=ProviderCallPersistenceIT -B -Dstyle.color=never
```

---

## 9. Risks & mitigations

- **§8 Risk #4 (LangGraph4j version stability) / Risk #13 (LangGraph4j native reachability):** *Not directly in M8's path* — M8 uses LangChain4j `ChatModel`, not LangGraph4j. The architectural mitigation that applies analogously: keep all LangChain4j coupling concentrated in `ai.forvum.engine.model/` (mirrors the "concentrate coupling in `engine/graph/`" pattern) so a LangChain4j bump or the `FallbackChain` Group-4c change is module-local. The fallback decorator is the natural choke point per §1.4/§5.4 (one library-wide decorator).
- **§8 Risk #5 (per-provider native readiness):** M8 itself instantiates no provider (it decorates whatever `ChatModel` it's handed), so it adds **zero** provider-native risk; the per-provider native smokes land at M9–M12. M8's design (provider-agnostic decorator) is what *enables* the M10 Verify ("invalid key falls through `FallbackChatModel` to Ollama").
- **§8 Risk #11 (JDBC/SQLite VT pinning):** the `provider_calls` write touches SQLite. M8 isolates this behind the `ProviderCallRecorder` seam so M8's own logic is pin-free; the pinning posture is M5's decision (Risk #11). The M8-gated `*IT` will exercise whatever M5 chooses. The decorator loop itself has no `synchronized` (CLAUDE.md §11).
- **Doc-driven risk — `FallbackChain` TBD (Decision A) / `reason` migration (Decision C):** the two unstable doc points (§4.3.5.3 TBD; §4.3.2 line 530 migration note). Mitigation: M8 uses an engine-local `FallbackLink` list and **declines** the `reason→FailureClass` migration with a documented rationale, so a later Group-4c ratification is an additive, module-local change. Flag both in the PR for maintainer sign-off (CLAUDE.md §8: SPI/contract changes need design sign-off — here we are *avoiding* a core change, which is the safer default).

---

## 10. Quarkus / library tooling steps

Per CLAUDE.md §7 (mandatory; do not answer Quarkus from memory):
1. **`quarkus_skills`** with query like `langchain4j,cdi` — BEFORE writing code — to confirm CDI bean patterns (`@Singleton`, `Event<T>` injection) and the current LangChain4j integration idioms in the pinned platform.
2. **`quarkus_searchDocs`** (pass `projectDir=/Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine`) for: "langchain4j ChatModel CDI", "RegisterForReflection native", "quarkus-langchain4j-bom managed dependencies" — to confirm `langchain4j-core` is BOM-managed and the annotation re-export wiring.
3. **`context7`** (`/langchain4j/langchain4j`, version 1.12.x to approximate the pinned 1.15.1) — already consulted here; confirmed `ChatModel.chat(ChatRequest)→ChatResponse`, `StreamingChatModel.chat(ChatRequest, StreamingChatResponseHandler)`, `ExceptionMapper` (429→RateLimit, 408→Timeout, 5xx→InternalServer, 401/403→Authentication, 404→ModelNotFound, other-4xx→InvalidRequest), all `extends LangChain4jException`; `ChatResponse.aiMessage()` + `metadata().tokenUsage()`→`TokenUsage`. Re-query at implementation time pinned to the actual 1.15.1 if any signature differs.
4. **`quarkus_create` harvest:** M8 adds **no new module** (it extends `forvum-engine`), so the full new-module harvest is **not** required. *Only if* step 2 shows `langchain4j-core` is not transitively managed: run a throwaway `quarkus_create` with a `quarkus-langchain4j-*` extension to read the resolved `dev.langchain4j:langchain4j-core` coordinate/version, then transplant the *version* into `forvum-bom` `dependencyManagement` (never into the module pom). Do not use `quarkus_create`/`quarkus_start` for any mutating purpose otherwise.
5. Run tests via **Surefire** (engine exception, §8 above), not the Dev MCP `devui-testing_runTests`.

---

## 11. Commit(s)

Single Conventional-Commits commit (matches §7.1 M8):
- `feat(engine): add FallbackChatModel decorator with failure classification`

If the maintainer prefers granularity (optional):
- `feat(engine): add FailureClass and FailureClassifier for provider exceptions`
- `feat(engine): add FallbackChatModel and FallbackStreamingChatModel decorators`

If a `forvum-bom` change proves necessary (only if langchain4j-core is unmanaged):
- `chore(bom): surface dev.langchain4j:langchain4j-core for the engine fallback decorator`

(No commit/push without explicit authorization — CLAUDE.md §10/§12.)

---

## 12. Completion checklist

- [ ] `forvum-engine/pom.xml` declares `forvum-core`, `forvum-sdk`, `dev.langchain4j:langchain4j-core` (all version-less / BOM-managed); enforcer `validate` still green.
- [ ] `FailureClass.java` sealed (`Retryable`/`NonRetryable`/`Unknown`); a `switch` over it compiles with no `default`.
- [ ] `FailureClassifier.java` maps the six typed exceptions + root + non-LC4j per §5.4 (Decision D); `Unknown` never silently retried.
- [ ] `FallbackChatModel.java` implements `ChatModel`; iterates `List<FallbackLink>`; records one row per attempt; `is_fallback` 0 then 1; FQCN in `error` on failure; emits `FallbackTriggered`; throws `BudgetExhaustedException` on pre-check exhaustion (nullable budget).
- [ ] `FallbackStreamingChatModel.java` implements `StreamingChatModel`; retries on retryable `onError` without surfacing it; forwards partials/completion from the first successful stream.
- [ ] No `synchronized` in `ai.forvum.engine.model` (CI grep clean); loop runs on a virtual thread.
- [ ] `ProviderCall` carries `@RegisterForReflection`; `CoreReflectionRegistration` holder registers the Layer-0 types; no `@RegisterForReflection` on any `forvum-core` type.
- [ ] `FallbackChatModelTest` + `FailureClassifierTest` + `FallbackStreamingChatModelTest` green via Surefire (`-B -Dstyle.color=never`); reports read from `target/surefire-reports/*.txt`.
- [ ] `ProviderCallPersistenceIT` present and **skipped** pre-M5 (gated), asserting the literal §7.1 SQL once M5 lands.
- [ ] `forvum-app -Pnative` compiles with the new engine classes; no new `@Startup` bean; native-smoke-with-no-`~/.forvum/` unaffected.
- [ ] PR description flags Decision A (engine-local link vs TBD core `FallbackChain`) and Decision C (declining the `reason→FailureClass` migration) for maintainer sign-off; proposes the §4.3.2 line-530 doc amendment.
- [ ] `/code-review` (high) run before merge (M4 lesson).

---

## 13. Cross-milestone coordination notes (for the integrator sequencing M5/M6/M8)

- **M8 ↔ M5 (`provider_calls` table + Panache):** M8 owns the `ProviderCallRecorder` *interface* + `ProviderCall` record + the in-memory test double; **M5 owns the Panache-backed `ProviderCallRecorder` impl + the `provider_calls` entity + `V1__baseline.sql`.** Coordinate the column-to-record mapping: `ProviderCall` must mirror `provider_calls` columns exactly (`session_id, agent_id, provider, model, tokens_in, tokens_out, cost_usd, latency_ms, is_fallback, error, created_at`; `turn_id` arrives in Flyway V2 — M8's `ProviderCall` should carry `turnId` as a nullable/optional field to be forward-compatible). The M8 `*IT` is gated on M5's entity class presence; **M5 should land first or concurrently**, after which un-gate and run the literal Verify. Agree the recorder method signature (`record(ProviderCall)`) before either merges to avoid a re-fit.
- **M8 ↔ M6 (`@AgentScoped` + `CURRENT_TURN`):** `provider_calls.turn_id` (V2) is sourced from `ScopedValue<UUID> CURRENT_TURN` bound by M6. M8's `ProviderCall.turnId` should be populated from `CURRENT_TURN.get()` *at the call site that constructs `FallbackChatModel`* (routing layer, later milestone), not inside the decorator's loop — keep the decorator agnostic of scoping. Note for M6: no `@AgentScoped` bean is introduced by M8, so M8 does not constrain the M6 context impl; but the eventual wiring of the decorator into the per-agent model selection (§5.4 per-agent/per-cron) will read `CURRENT_AGENT`/`CURRENT_TURN` — flag this as the M6→M8 integration seam.
- **`forvum-core` shared types (M2-owned, touched conceptually by M8):** M8 reads `ModelRef`, `FallbackTriggered`, `FallbackReasons`, `CostBudget`/`Usage`/`Spend`/`ExhaustionCause`/`BudgetMeter`/`BudgetExhaustedException` but **modifies none of them**. The only core-adjacent recommendation is a one-line **doc** amendment (§4.3.2 line 530 + `FallbackTriggered` Javadoc) to record that the `reason→FailureClass` migration is intentionally declined (Decision C). If any sibling milestone (or a future Group-4c PR) instead *does* introduce a core `FallbackChain`, M8's engine-local `FallbackLink` is the adaptation point — a single constructor change in `FallbackChatModel`. Sequence: ratify `FallbackChain` shape (Group 4c, separate from M5/M6/M8) before re-pointing the decorator.
- **`@RegisterForReflection` holder ownership:** M8 introduces `CoreReflectionRegistration` in `forvum-engine`. If M5/M6 also need to register Layer-0 types for native, **consolidate into this single holder** (CLAUDE.md §5 / §6.3 prescribe "a single `@RegisterForReflection(targets={…})` holder") rather than each milestone adding its own — coordinate so M5/M6/M8 append targets to one file instead of creating competing holders.

### Critical Files for Implementation
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine/pom.xml
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-core/src/main/java/ai/forvum/core/event/FallbackTriggered.java
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-core/src/main/java/ai/forvum/core/event/FallbackReasons.java
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-core/src/main/java/ai/forvum/core/budget/CostBudget.java
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine/src/test/java/ai/forvum/engine/config/TestHomeProfile.java
