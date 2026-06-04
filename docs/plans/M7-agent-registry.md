# M7 — `AgentRegistry` + minimal single-agent turn

Status: **planning artifact, not code.** Part of the **Tier-B concurrent window (M7 · M9)** — see
`README.md` in this folder. The architectural decisions flagged below need maintainer sign-off
(CLAUDE.md §8) before the PR lands. Source of truth: `docs/ULTRAPLAN.md`; issue map: `docs/ISSUES.md`
(**M7 → #12**).

---

## AUTHORITATIVE CORRECTIONS (override the body where they conflict)

Verified against `main` (post-Tier-A). Settle these before coding.

- **AC-1 — M7 is unblocked NOW; no prelude needed.** Deps M3/M4/M6 are done and their artifacts exist
  in code (`config/ConfigLoader.java` + `config/AgentReader.java`; `context/AgentContext.java` +
  `AgentScopeExtension.java` + `CurrentAgent.java`; `forvum-sdk` provider SPIs). `forvum-engine/pom.xml`
  already declares `forvum-core` (Tier-A prelude landed). No new engine dependency is required for M7.
- **AC-2 — the cycle ambition is a *real* end-to-end turn (maintainer decision, 2026-06-04).** M7 ships
  not just the registry but the **model-resolution seam (`LlmSelector`)** and a **minimal
  `Agent.respond()` single-turn path** (memory → model → ledger → memory), so that once M9 plugs in the
  Ollama provider, an actual agent↔Ollama turn closes. This expands M7 beyond the six files named in
  ULTRAPLAN §7.1.
- **AC-3 — `LlmSelector` MUST live in the engine, not in M9.** M9 is a Layer-3 plugin that may depend
  only on `forvum-sdk` and therefore *cannot* add engine code. The `ModelRef → ChatModel` resolution
  glue is engine-side and extension-agnostic (it injects the `ModelProvider` SPI, never Ollama). M7 is
  the engine milestone in this cycle, so the seam is M7's. **The SPI method
  `ModelProvider.resolve(ModelRef)→ChatModel` does not exist yet** (the SDK declares only
  `extensionId()`); since `LlmSelector` + `FakeModelProvider` consume it and M7 merges first, the **SPI
  extension (the method + a versionless `langchain4j-core` dep on `forvum-sdk`) lands as the first commit
  of the M7 PR** — the shared Tier-B prelude (M9 plan AC-1). No SDK enforcer change is needed (that rule
  governs only `ai.forvum:*`). The `ChatModel` FQN is `dev.langchain4j.model.chat.ChatModel`; the M8
  `FallbackChatModel(List<FallbackLink>, sessionId, agentId, FailureClassifier, ProviderCallRecorder,
  Consumer<AgentEvent>)` constructor is what `LlmSelector` feeds the one-link chain.
- **AC-4 — single `primaryModel`, single fallback link this cycle.** Core `Persona` today carries only
  `primaryModel` (one `ModelRef`) — there is **no** `FallbackChain` (TBD / DR-4c) and **no**
  `MemoryPolicy` type in `forvum-core` yet. So `LlmSelector` wraps a **one-link** `FallbackChatModel`
  (the M8 decorator still records `provider_calls` and classifies failures with a single link).
  Multi-provider fallback becomes real only when M10 adds a second provider.
- **AC-5 — reuse core `Persona`; do not duplicate its fields in `AgentSpec`.** `ai.forvum.core.Persona`
  already models `(id, systemPrompt, allowedTools, primaryModel, parent, costBudget, toolBudget)`.
  `AgentSpecReader` parses `<id>.md` + `<id>.json` **into a `Persona`**; the engine `AgentSpec` is a
  thin record bundling that `Persona` + provenance (paths, last-modified) for hot reload.
- **AC-6 — M7's turn path is tested with a `FakeChatModel`, decoupled from M9.** The deterministic
  memory→model→ledger→memory loop is proven in-engine against a stub `ModelProvider`/`ChatModel`; the
  real-Ollama turn is M9's gated e2e. **M7 has zero hard dependency on M9.**
- **AC-7 — three memory tiers are *written* this cycle; semantic *embeddings* default to deferred.**
  Per the maintainer's "three tiers now" decision, `AgentMemory` writes `messages`, `episodic_memory`,
  and `semantic_memory`. But computing the `semantic_memory.embedding` vector needs an embedding model
  + an embedding SPI that this cycle does not otherwise require. **Recommended default:** write semantic
  rows with `embedding = NULL` (schema allows it), `source`/`key`/`value` populated; defer the embedding
  vector + similarity search to the milestone that first *retrieves* semantic memory. **Decided
  2026-06-04: defer (NULL).** The full path (embedding SPI + Ollama `EmbeddingModel` now) was the
  alternative under D-3 and was declined for this cycle.

---

## 1. Scope

M7 turns file-declared agents into live, isolated, conversational runtime objects.

**Delivers:**
1. `AgentRegistry` — `@ApplicationScoped`, watches `~/.forvum/agents/`, maps `AgentId → AgentSpec`,
   `getOrCreate(id)` and `spawn(parentId, childSpec)`.
2. `AgentSpecReader` — parses `agents/<id>.md` + `<id>.json` into a core `Persona` (via M4 `AgentReader`
   + `ConfigLoader`), refusing to activate an agent missing either half (a `ConfigDoctor`-style reason).
3. `Agent` — the `@AgentScoped` facade aggregating persona, `AgentMemory`, `AgentToolBelt`, and the
   resolved LLM, exposing a minimal `respond(userMessage)` turn.
4. `AgentMemory` — a LangChain4j `ChatMemoryStore` over the `messages` table (keyed by `agentId`),
   wrapped in `MessageWindowChatMemory`; plus the episodic/semantic write hooks (AC-7).
5. `AgentToolBelt` — `@AgentScoped`, holds the persona's `allowedTools` glob list (the narrowing
   target). **No filtering this cycle** — the intersection against the global `ToolRegistry` is M13.
6. `LlmSelector` — resolves `Persona.primaryModel` to a `ChatModel` via the `ModelProvider` SPI and
   wraps it in a one-link `FallbackChatModel` (AC-3, AC-4).
7. A minimal `SessionManager.ensureSession(agentId)` so the turn has a `messages.session_id` FK target
   (full per-conversation session lifecycle lands with the channels, M15–M17).

**Out of scope (named to prevent scope creep):** tool *filtering* (M13), the LangGraph4j orchestration
graph / route / tool_loop / spawn-worker fan-out (M18), multi-provider fallback (M10), semantic
*retrieval* + embeddings (AC-7 / D-3), real channel sessions (M15–M17).

---

## 2. Ground-truth this builds on (verified in code)

| Need | Existing artifact (module/package) |
|---|---|
| File I/O for `agents/<id>.{md,json}` | `engine/config/AgentReader.java`, `ConfigLoader.java`, `ForvumHome.java` (M4) |
| Hot reload signal | `engine/config/ConfigWatcher.java` → `ConfigurationChangedEvent` (M4) |
| Per-agent isolation | `engine/context/AgentContext.java` (`InjectableContext`), `AgentScopeExtension.java` (BCE), `CurrentAgent.java` (`CURRENT_AGENT`, `CURRENT_TURN`) (M6) |
| Persona value type | `core/Persona.java`, `core/id/AgentId.java`, `core/ModelRef.java`, `core/ToolSpec.java` (M2) |
| Conversation rows | `engine/persistence/MessageEntity.java`, `EpisodicMemoryEntity.java`, `SemanticMemoryEntity.java`, `SessionEntity.java` (M5) |
| Model decorator + ledger | `engine/model/FallbackChatModel.java`, `FallbackLink.java`, `ProviderCallRecorder.java` + `PanacheProviderCallRecorder.java` (M8) |
| Provider SPI | `forvum-sdk` `ModelProvider` / `AbstractModelProvider` (M3) — **note:** `resolve()` is added by M9 (see M9 plan AC-1) |

The `AgentContext.get()` race that was a hard blocker for M7's concurrent same-agent resolution is
**already fixed** on `main` (`computeIfAbsent`, commit `1e6f58a`) with a `CyclicBarrier` test. M7 inherits
a sound concurrency foundation.

---

## 3. Design

### 3.1 `AgentSpecReader` → `Persona`
`agents/<id>.md` → `Persona.systemPrompt` (raw markdown). `agents/<id>.json` → `allowedTools` (globs),
`primaryModel` (`ModelRef.parse`), optional `parent`, `costBudget`, `toolBudget`. Both files required;
missing half → activation refused with a triage message naming the file. Validation reuses the core
record canonical constructors (they throw `IllegalStateException` with origin-naming text).

### 3.2 `AgentRegistry`
`@ApplicationScoped`. Holds `ConcurrentMap<AgentId, AgentSpec>`. `getOrCreate(id)`: if the spec is known,
bind the `@AgentScoped` `Agent`; else read+validate+register, then bind. Observes
`ConfigurationChangedEvent` for the `agents/` subfolder to refresh specs on hot reload. **Native / no
`~/.forvum/`:** boot to an empty registry with a warning (never crash, never block command-mode exit) —
the M4 lesson; `getOrCreate` on an unknown id returns a clear error, not an NPE.

### 3.3 `Agent` facade + `Agent.respond(userMessage)` (the minimal turn)
`@AgentScoped`. Lazily aggregates `persona`, `AgentMemory`, `AgentToolBelt`, and `llm()` (the
`LlmSelector` result). `respond` runs on a virtual thread and:
1. `SessionManager.ensureSession(agentId)` → bind a session id for the turn;
2. append the user `ChatMessage` to `AgentMemory` (writes a `messages` row);
3. build the request (system = `persona.systemPrompt`, history = windowed memory);
4. call `llm()` (the one-link `FallbackChatModel`, which records a `provider_calls` row);
5. append the assistant `ChatMessage` (writes a `messages` row);
6. write an `episodic_memory` `observation` row for the turn;
7. (AC-7) write any `semantic_memory` rows (`embedding = NULL` by default);
8. return the assistant text.

This is deliberately a straight line — M18 later wraps/replaces it with the `SupervisorGraph`
(route/generate/tool_loop/spawn-worker). No tools, no routing, no spawn-in-turn here.

### 3.4 `AgentMemory` (LangChain4j `ChatMemoryStore`)
Implements `ChatMemoryStore.getMessages/updateMessages/deleteMessages(memoryId)` over `MessageEntity`,
keyed by `agentId` as the memory id, scoped to the current session. Serialize via LangChain4j's
`ChatMessageSerializer`/`ChatMessageDeserializer`. Wrapped in `MessageWindowChatMemory.builder()
.id(agentId).maxMessages(N).chatMemoryStore(this).build()`. Episodic/semantic writes are thin helpers
on `AgentMemory` invoked by the turn path (§3.3 steps 6–7).

### 3.5 `AgentToolBelt` (stub until M13)
`@AgentScoped`. Holds `List<String> allowedTools` (the persona globs). Exposes the (empty for now)
resolved `List<ToolSpec>`. **No `ToolRegistry`, no glob intersection** — that is M13's job; the LLM sees
no tools this cycle. `spawn` narrows the child's glob list (structural narrowing), which is what M7's
verify asserts — *not* a filtered `ToolSpec` set.

### 3.6 `LlmSelector` (the model seam)
`@ApplicationScoped`, extension-agnostic. Injects `Instance<ModelProvider>`; `select(persona)`:
1. parse `persona.primaryModel` → `(provider, model)`;
2. pick the `ModelProvider` whose `extensionId()` equals `provider` (else a clear "no provider for
   `<id>`" error);
3. `provider.resolve(primaryModel)` → a LangChain4j `ChatModel` (the SPI method M9 adds);
4. wrap in a one-link `FallbackChatModel` (M8) bound to `(agentId, sessionId)` for ledger rows.

> **Step-0:** confirm the exact `FallbackChatModel` / `FallbackLink` constructor against
> `engine/model/FallbackChatModel.java` before wiring — do not guess the API.

### 3.7 `spawn(parentId, childSpec)`
Constructs a distinct child `AgentId`, inheriting parent `CostBudget`/`Identity` unless overridden
(reuse the §4.3.6 budget rules already in `core/budget`; a `SessionWindow` budget without override
throws `SpawnConfigurationException` — already in core). The child's `AgentToolBelt` carries a glob list
⊆ the parent's. Memoized in the registry under the new id.

---

## 4. Decisions needing maintainer sign-off (§8)

- **D-1 — `LlmSelector` + `Agent.respond()` belong in M7.** Forced by AC-3 (M9 cannot add engine code).
  Confirms the cycle delivers a real turn. *(Recommended: accept.)*
- **D-2 — `AgentToolBelt` is a stub (glob list only) until M13.** The LLM sees no tools this cycle.
  *(Recommended: accept — `ToolRegistry` is M13.)*
- **D-3 — semantic-tier embeddings: defer (NULL) vs full now. DECIDED 2026-06-04: defer.** AC-7. Write
  semantic rows with `embedding = NULL`, no embedding SPI/model this cycle. ("Full now" — an
  `EmbeddingModel` SPI method + an Ollama embedding model, coupling to M9 — was declined; revisit when
  v0.1 must do semantic *search*.)
- **D-4 — minimal `SessionManager` in M7.** A thin ensure-session so the turn has a `messages.session_id`
  target; real per-conversation sessions land with channels. *(Recommended: accept.)*
- **D-5 — reuse core `Persona` as the spec payload (AC-5).** Avoid a parallel `AgentSpec` field set.
  *(Recommended: accept.)*

---

## 5. Files (new unless noted)

```
forvum-engine/src/main/java/ai/forvum/engine/agent/AgentRegistry.java
forvum-engine/src/main/java/ai/forvum/engine/agent/AgentSpec.java          # wraps core Persona + provenance
forvum-engine/src/main/java/ai/forvum/engine/agent/AgentSpecReader.java
forvum-engine/src/main/java/ai/forvum/engine/agent/Agent.java              # @AgentScoped facade + respond()
forvum-engine/src/main/java/ai/forvum/engine/agent/AgentMemory.java        # ChatMemoryStore over messages (+ episodic/semantic hooks)
forvum-engine/src/main/java/ai/forvum/engine/agent/AgentToolBelt.java      # @AgentScoped glob-list stub
forvum-engine/src/main/java/ai/forvum/engine/routing/LlmSelector.java      # ModelRef -> ChatModel via ModelProvider SPI
forvum-engine/src/main/java/ai/forvum/engine/persistence/SessionManager.java
forvum-engine/src/test/java/ai/forvum/engine/agent/FakeModelProvider.java  # test stub (ModelProvider + deterministic ChatModel)
```
No existing engine package is modified (new `agent/` + `routing/` packages); zero file overlap with M9.

---

## 6. Verify / tests

**Unit / integration (`@QuarkusTest`, real SQLite via `@TempDir`, run via Surefire — headless library,
NOT the Dev MCP; `-B -Dstyle.color=never`, read `target/surefire-reports/*.txt`):**
- **M7 §7.1 verify:** seed `~/.forvum/agents/main.{md,json}`; `getOrCreate("main")` twice ⇒ same
  `@AgentScoped Agent` instance; `spawn("main", child)` ⇒ distinct child `AgentId` + child glob list ⊊
  parent's.
- **Turn loop (FakeModelProvider, AC-6):** `agent.respond("hi")` ⇒ non-empty reply, two `messages` rows
  (user+assistant), one `provider_calls` row, one `episodic_memory` `observation` row.
- **Concurrent same-agent:** N virtual threads behind a `CyclicBarrier` resolving the same agent ⇒ one
  shared `Agent` instance (exercises the M6 `computeIfAbsent` fix under a real M7 caller).
- **Missing-half / malformed spec:** activation refused with a file-naming reason.

**Native (in `forvum-app`):** native-compiles (already wired via the engine dep); the **no-`~/.forvum/`
smoke** boots the binary with the registry empty + a warning and exits command-mode cleanly. No
behavioral native assertion is required for M7 (no host-only risk) — the standard compile + boot smoke.

**Performance gate (FakeProvider, excludes inference):** keep the turn loop within the §6.2 per-turn
budget baseline (TUI ≤200 ms) so M15 inherits headroom.

---

## 7. Step-0 spikes (before the bulk of the code)
1. Confirm the `FallbackChatModel`/`FallbackLink` constructor + `ProviderCallRecorder` wiring against
   the M8 code (so `LlmSelector` composes the one-link chain correctly).
2. Confirm the `ChatMemoryStore` ↔ `MessageEntity` mapping (serialization round-trip; session scoping).
3. `quarkus/skills` (CDI, hibernate-orm-panache) + `context7` (LangChain4j `ChatMemory`/`ChatMemoryStore`)
   before writing code — §7 mandate.

---

## 8. Risks
- **R-1 — scope inflation from the real-turn ambition.** Mitigation: the turn path is a deliberate
  straight line (no tools/graph), fake-provider-tested; M18 owns the real orchestration.
- **R-2 — `LlmSelector` depends on M9's SPI `resolve()` signature.** Soft edge: agree the signature in
  the M9 plan (its AC-1) first; M7 codes against the SPI, `FakeModelProvider` implements it for tests.
- **R-3 — session model is minimal and may be reshaped by channels.** Accepted: `SessionManager` is
  intentionally thin; the schema (`sessions`) is fixed (M5), so channels extend, not migrate.
