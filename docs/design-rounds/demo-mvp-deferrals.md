# Demo MVP — Design deferrals

**Status:** active during demo/conference-mvp development.
**Purpose:** record every contract shortcut taken for the MVP 
demo so none is lost when returning to full Tier 1 implementation.

## Context

demo/conference-mvp is a vertical slice demonstrating Forvum 
running a single agent via CLI against Ollama. It deliberately 
cuts scope from the Tier 1 design captured in ULTRAPLAN.md §4.3 
and in docs/design-rounds/group-4b.md. This file lists every 
such cut so the code debt is visible and the return path is 
explicit.

## Deferrals

### D1 — ModelProvider SPI collapsed to ChatModelFactory switch
- **ULTRAPLAN reference:** §2.2 (sealed ModelProvider interface 
  in forvum-sdk), §4.3.5.1 (ModelRef resolution via 
  ModelProvider.resolve(ModelRef) → ChatModel).
- **MVP shape:** forvum-app/ChatModelFactory with a switch 
  on ref.provider() returning the concrete LangChain4j ChatModel. 
  Only "ollama" handled in MVP; unknown providers throw 
  IllegalStateException.
- **Debt incurred:** no SPI surface; second provider requires 
  refactor (extract sealed interface into forvum-sdk, split 
  ChatModelFactory into per-provider adapters, wire via CDI 
  @All Instance<ModelProvider>).
- **Return trigger:** adding any provider beyond Ollama, OR 
  M9-M12 being opened (whichever comes first).
- **Scope estimate:** ~2-3h refactor; mechanical, non-breaking 
  at the call site if ChatModelFactory preserves the same 
  method signature.

### D2 — AgentSpec defined ad-hoc without full §4.3 specification
- **ULTRAPLAN reference:** §2.1 lists `AgentSpec` conceptually 
  but §4.3 has no subsection defining its record shape, fields, 
  or validation invariants.
- **MVP shape:** minimal record in ai.forvum.core with fields 
  (String id, String systemPrompt, ModelRef primaryModel). 
  Canonical constructor validates non-null/non-blank following 
  the Groups 2/3/4a/4b convention (IllegalStateException with 
  triage-oriented messages).
- **Debt incurred:** when AgentSpec gets a full design round 
  (likely Group 5 or later, when MemoryPolicy + PermissionScope 
  references land), the MVP shape may need extension: 
  allowedTools (List<PermissionScope>), memoryPolicy (MemoryPolicy), 
  fallbackChain (FallbackChain instead of single primaryModel), 
  costBudget (CostBudget), parent agent pointer, persona, 
  identity wiring.
- **Return trigger:** Group 5 opens (AgentSpec spec'd formally), 
  OR any of the missing fields becomes needed for a feature beyond 
  the demo (whichever comes first).
- **Scope estimate:** additive expansion; existing fields 
  (id, systemPrompt, primaryModel) should remain stable. Field 
  rename from `primaryModel` to `fallbackChain` is the main 
  breaking change to anticipate.

### D3 — Agent runtime without @AgentScoped CDI context
- **ULTRAPLAN reference:** §5.1 (@AgentScoped custom CDI context 
  with ScopedValue<AgentId> CURRENT_AGENT + ScopedValue<UUID> 
  CURRENT_TURN).
- **MVP shape:** SimpleAgent as @ApplicationScoped bean receiving 
  AgentSpec and ChatModel via constructor injection. History kept 
  in memory via langchain4j MessageWindowChatMemory 
  (List<ChatMessage>). No per-agent scope isolation — single-agent 
  demo doesn't exercise the isolation requirement.
- **Debt incurred:** when sub-agent spawn (§5.2, §5.5) or multi-
  agent configuration enters scope, the @ApplicationScoped single 
  bean must become @AgentScoped with InjectableContext backing + 
  AgentContextProcessor BuildStep + ScopedValue propagation.
- **Return trigger:** M6 (engine-core with @AgentScoped) or any 
  requirement for concurrent multi-agent execution.
- **Scope estimate:** ~1 full milestone (M6 as specified in the 
  plan); not a simple refactor — it's the custom CDI scope 
  implementation Forvum's architecture depends on.

### D4 — No persistence, no Flyway, no provider_calls ledger
- **ULTRAPLAN reference:** §4.2 (V1 schema + V2 turn_id), §4.3.5.2 
  Decision 2 (provider_calls as canonical ledger).
- **MVP shape:** zero persistence. Chat history in memory, lost 
  on process exit. No SQLite, no Flyway migrations, no 
  provider_calls inserts.
- **Debt incurred:** all observability, budget enforcement, CAPR, 
  memory, and multi-session features depend on this ledger. 
  Nothing built on top of persistence can exist until it lands.
- **Return trigger:** M5 (persistence layer).
- **Scope estimate:** M5 as specified; Flyway V1 + V2 migrations 
  + JDBC wiring + ProviderCallWriter + session_id generation 
  conventions.

### D5 — No AgentEvent hierarchy, no structured event stream
- **ULTRAPLAN reference:** §4.3.2 (sealed AgentEvent with 6 
  permits: TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, 
  Done, ErrorEvent + FallbackReasons constants).
- **MVP shape:** SimpleAgent.chat(String) returns String directly. 
  No streaming, no event emission, no observability hooks. CLI 
  prints the full response after the call completes.
- **Debt incurred:** all channels beyond a simple sync CLI 
  (Web SSE, Telegram updates, DevUI streaming) depend on the 
  event stream. CAPR, logging, and OTel spans consuming event 
  payloads are also blocked.
- **Return trigger:** M2 (core contracts materialization) or 
  any streaming channel milestone.
- **Scope estimate:** M2 as specified for the event types 
  themselves; each channel that consumes them has its own 
  milestone.

### D6 — No FallbackChain, no CostBudget, no BudgetMeter enforcement
- **ULTRAPLAN reference:** §4.3.5.2 Groups 4a-4b complete 
  (ModelRef, CostBudget, Window, BudgetMeter, Usage, Spend, 
  ExhaustionCause, BudgetExhaustedException, 
  SpawnConfigurationException). Group 4c (FallbackChain) not 
  yet opened.
- **MVP shape:** ChatModelFactory resolves the single ModelRef 
  from AgentSpec.primaryModel directly. No fallback on failure 
  (exception propagates to CLI and exits). No budget tracking 
  at all — any cost, any token count is allowed.
- **Debt incurred:** production-readiness gate. Running an agent 
  in a chain with budget control is core to Forvum's value 
  proposition; the MVP demo bypasses it entirely.
- **Return trigger:** M8 (fallback + budget enforcement) per the 
  plan, or Group 4c completion (FallbackChain spec'd) — whichever 
  triggers implementation first.
- **Scope estimate:** full Group 4c design round + M8 milestone.

### D7 — No CAPR judging, no OpenTelemetry, no DevUI
- **ULTRAPLAN reference:** §3.4 (OTel), §5.x (CAPR judge agent), 
  §6.x (DevUI extension).
- **MVP shape:** plain Quarkus app. No OTel exporter, no judge 
  pass, no DevUI tab. Logs go to stdout via quarkus default.
- **Debt incurred:** observability is a first-class concern of 
  the plan; MVP has zero. None of these block each other — 
  each is an independent milestone.
- **Return trigger:** respective milestones (OTel, CAPR, DevUI 
  each have their own).
- **Scope estimate:** per milestone; independent and 
  non-blocking.

## Return path

When the conference demo is over, the recommended sequence is:

1. Decide the fate of demo/conference-mvp — discard (start M2 
   clean from main) or merge selectively (rare; MVP code 
   unlikely to match Tier 1 contracts exactly).
2. Open M2 per ULTRAPLAN, materializing every §4.3 contract 
   without shortcuts. This dissolves D1, D5, and partially 
   D2 (AgentSpec gets its formal record definition).
3. Open Group 4c + Group 5 design rounds to fix the remaining 
   TBD subsections in §4.3.5.3 and §4.3.6.
4. Proceed through M3-M20 in order; each milestone dissolves 
   one or more deferrals listed above.

Each deferral above must be explicitly closed (file amendment 
marking it resolved) when its return trigger fires. Losing 
track of any deferral here means the MVP shortcut silently 
became tech debt in the permanent codebase.

---

*This file lives on demo/conference-mvp and tracks cuts specific 
to that branch. It is NOT meant to merge into main as-is — when 
main absorbs demo-driven learnings (if any), deferrals should 
either be resolved (code matches plan) or migrated to a proper 
design-rounds entry.*
