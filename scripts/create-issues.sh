#!/usr/bin/env bash
#
# create-issues.sh — create the Forvum roadmap issues on GitHub via `gh`.
#
# ┌──────────────────────────────────────────────────────────────────────────┐
# │ MANUAL STEP. This script is NOT run automatically. Review docs/ISSUES.md  │
# │ first, then run it yourself from the Forvum repository root:              │
# │                                                                          │
# │     gh auth status            # confirm you are authenticated            │
# │     cd /path/to/forvum         # the repo root (has pom.xml + docs/)      │
# │     bash scripts/create-issues.sh                                        │
# │                                                                          │
# │ Requirements:                                                            │
# │   - GitHub CLI `gh` installed and authenticated (`gh auth login`).       │
# │   - Run from the forvum repo root so `gh` infers the repo, OR set        │
# │     FORVUM_REPO below / export GH_REPO=eldermoraes/forvum.               │
# │                                                                          │
# │ Idempotency:                                                             │
# │   - Labels and milestones are created with `|| true` so re-runs are safe.│
# │   - Issues are NOT deduplicated: re-running creates duplicate issues.    │
# │     Run the issue-creation section exactly once.                         │
# └──────────────────────────────────────────────────────────────────────────┘
#
# Commit convention reminder (for the work these issues track):
#   single-author (Elder Moraes), NO Co-Authored-By trailer, Conventional Commits.

set -euo pipefail

# Repository: leave empty to let `gh` infer from the current git remote, or set
# explicitly. You may also `export GH_REPO=eldermoraes/forvum` before running.
FORVUM_REPO="${FORVUM_REPO:-eldermoraes/forvum}"
REPO_FLAG=(--repo "$FORVUM_REPO")

command -v gh >/dev/null 2>&1 || { echo "error: gh CLI not found on PATH" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "error: gh is not authenticated — run 'gh auth login'" >&2; exit 1; }

echo ">> Target repository: $FORVUM_REPO"
echo ">> Creating labels (idempotent) ..."

# ── Labels ──────────────────────────────────────────────────────────────────
# gh label create is idempotent here via `|| true`.
mklabel() { gh label create "$1" "${REPO_FLAG[@]}" --color "$2" --description "$3" 2>/dev/null || true; }

mklabel "epic"                "5319e7" "Tracking epic"
mklabel "phase-1"             "0e8a16" "Phase 1 MVP (v0.1)"
mklabel "phase-2"             "1d76db" "Phase 2 v0.5 parity"
mklabel "phase-3"             "0052cc" "Phase 3 v1.0+ differentiators"
mklabel "design-round"        "fbca04" "Design-round deliverable"
mklabel "ci-infra"            "c5def5" "Cross-cutting CI / test infrastructure"
mklabel "native"             "b60205" "GraalVM native-mandatory work"
mklabel "plugin-tooling"      "d4c5f9" "Uses the quarkus-agentic plugin / Quarkus Agent MCP"
mklabel "provider"            "bfdadc" "Model provider module"
mklabel "channel"             "c2e0c6" "Channel module"
mklabel "tool"                "fef2c0" "Tool module"
mklabel "engine"              "006b75" "forvum-engine work"
mklabel "core"                "1d76db" "forvum-core work"
mklabel "sdk"                 "0e8a16" "forvum-sdk work"
mklabel "persistence"         "5319e7" "SQLite / Flyway / persistence"
mklabel "security"            "d93f0b" "Security / threat model / filters"
mklabel "observability"       "0052cc" "OTel / CAPR / telemetry"
mklabel "context-engineering" "fbca04" "Write/Select/Compress/Isolate pillar work"
mklabel "branch-hygiene"      "ededed" "Repository / branch hygiene"
mklabel "blocked"             "e11d21" "Has a hard prerequisite issue"

echo ">> Creating milestones (idempotent) ..."

# ── Milestones ────────────────────────────────────────────────────────────────
# The REST API rejects a duplicate title with 422; `|| true` keeps re-runs safe.
mkmilestone() {
  gh api "repos/$FORVUM_REPO/milestones" -X POST -f title="$1" -f description="$2" >/dev/null 2>&1 \
    || echo "   milestone '$1' already exists (skipped)"
}

mkmilestone "v0.1 MVP"        "Phase 1 — minimum viable Forvum (M1-M20)."
mkmilestone "v0.5 Parity"     "Phase 2 — feature parity with OpenClaw v2026.4.19-beta.2."
mkmilestone "v1.0+"           "Phase 3 — v1.0+ differentiators."
mkmilestone "Design Rounds"   "Open Tier-1+ design rounds (Groups 4c/5/6a/6b/6c/8) + security-test layer."
mkmilestone "CI/Test Infra"   "Cross-cutting CI, test, and native-discipline infrastructure."

echo ">> Creating issues ..."

# ── Issue helper ──────────────────────────────────────────────────────────────
# create_issue <title> <comma-separated-labels> <milestone> <<'BODY' ... BODY
create_issue() {
  local title="$1" labels="$2" milestone="$3"
  local body; body="$(cat)"
  echo "   - $title"
  gh issue create "${REPO_FLAG[@]}" \
    --title "$title" \
    --label "$labels" \
    --milestone "$milestone" \
    --body "$body"
}

# ============================================================================
# EPIC PARENTS
# ============================================================================

create_issue "[epic] Phase 1 MVP (v0.1)" "epic,phase-1" "v0.1 MVP" <<'BODY'
**Context.** Phase 1 (docs/ULTRAPLAN.md §7.1) delivers the minimum viable Forvum: a native-buildable single binary that runs a real agent turn across TUI/Web/Telegram channels with Ollama/Anthropic/OpenAI/Google providers, SQLite persistence, @AgentScoped isolation, a LangGraph4j supervisor graph, and crons.

**Scope.** Tracking epic for milestones M1-M20. Closes when the M20 capstone is green (JVM + native on both CI platforms, 200 ms cold-start gate passing).

**Acceptance Criteria.** All child issues M1-M20 closed; the E2E suite (X6) passes on fast-jar AND native.

**Dependencies.** None (root epic).
BODY

create_issue "[epic] Phase 2 v0.5 parity with OpenClaw" "epic,phase-2" "v0.5 Parity" <<'BODY'
**Context.** Phase 2 (docs/ULTRAPLAN.md §7.2) reaches feature parity with OpenClaw, reconciled against OpenClaw v2026.4.19-beta.2. §7.2 is the authoritative parity list (items 1-23).

**Scope.** Tracking epic for P2-1..P2-15 plus the eight parity additions (§7.2 items 16-23).

**Acceptance Criteria.** All child issues closed; each verified "against OpenClaw v2026.4.19-beta.2".

**Dependencies.** EPIC-1 (a stable MVP).
BODY

create_issue "[epic] Phase 3 v1.0+ differentiators" "epic,phase-3" "v1.0+" <<'BODY'
**Context.** Phase 3 (docs/ULTRAPLAN.md §7.3) ships the bets the Java/Quarkus/native foundation enables or cheapens versus OpenClaw's TS/Node stack — headlined by the single-binary install (P3-1), the product expression of the native mandate.

**Scope.** Tracking epic for P3-1..P3-10.

**Acceptance Criteria.** All child issues closed.

**Dependencies.** EPIC-2 (v0.5).
BODY

create_issue "[epic] Close remaining design rounds" "epic,design-round" "Design Rounds" <<'BODY'
**Context.** Tier-1 design rounds (Groups 1-4b, §3.8, §10) are CLOSED. The open surface is: Group 6a (threat model + tool filters; opened on main, decisions log empty), Group 6b/6c (planned, no file yet), Group 4c (FallbackChain, §4.3.5.3 *TBD*), Group 5 (MemoryPolicy, §4.3.6 *TBD*), Group 8 (Persona/AgentSpec).

**Scope.** Parent tracking the dependency chain DR-6a -> {DR-4c, DR-5, DR-6b, DR-6c, TEST-SEC} -> DR-8, plus BR-CLEANUP.

**Acceptance Criteria.** No *TBD* markers remain in docs/ULTRAPLAN.md; §9 Security exists; the §10 "see §9 once it lands" forward-reference resolves.

**Dependencies.** None (root epic).
BODY

create_issue "[epic] Cross-cutting CI / test / native-discipline infrastructure" "epic,ci-infra" "CI/Test Infra" <<'BODY'
**Context.** §6.3/§3.8/§10/E2E/Critical-Files imply enforcement and verification layers that are not a single milestone (X1-X8). These are the mandate's enforcement backbone.

**Scope.** Parent tracking X1-X8.

**Acceptance Criteria.** All gates active in CI; the §10 native-parity amendment ("selective" -> "mandatory") operationalized at M20.

**Dependencies.** None (root epic); individual gates fold into M1/M3/M20.
BODY

# ============================================================================
# EPIC-1 — PHASE 1 MILESTONES (M1-M20)
# ============================================================================

create_issue "M1: bootstrap the Maven multi-module reactor" "phase-1,engine,native,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** Forvum is a Maven multi-module reactor (§2): parent + forvum-bom + the four layers (forvum-core, forvum-sdk, forvum-engine, forvum-app). The reactor topology is hand-authored and owned by this milestone — the scaffolding skill produces per-module starting points, not the reactor skeleton (§3.9).

**Scope / Deliverables.** Create parent + bom + core + sdk + engine + app POMs; Maven Wrapper; compiler config `maven.compiler.release=25`; `.gitignore`. Lock Java 25 and the Quarkus 3.33.x LTS platform BOM (3.33.1). forvum-bom imports `quarkus-langchain4j-bom:1.11.0.CR1` (PRE-RELEASE; stable fallback 1.10.0) and pins `langgraph4j-core:1.8.17` and `org.xerial:sqlite-jdbc` (>= 3.40.1.0) as the single bump point (§2.1).

**Files.** `pom.xml` (parent), `forvum-bom/pom.xml`, `forvum-core/pom.xml`, `forvum-sdk/pom.xml`, `forvum-engine/pom.xml`, `forvum-app/pom.xml`, `.gitignore`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`.

**Acceptance Criteria.**
- `./mvnw -N verify` green per module; `./mvnw -pl forvum-app -am package` produces `forvum-app/target/quarkus-app/quarkus-run.jar`.
- [NATIVE] the bootstrapped `-Pnative` profile native-compiles a trivial forvum-app to a runner binary in CI from day one (native gated from M1, §6 / §3.7); Mandrel 25.0.x-Final used as the native-image distribution.
- forvum-bom is the single version source: `quarkus-langchain4j-bom:1.11.0.CR1`, `langgraph4j-core:1.8.17`, `sqlite-jdbc` >= 3.40.1.0 present; no version pinned outside the BOM.
- [PLUGIN] platform version + extension wiring harvested via `quarkus/create` (throwaway app), coordinates transplanted into the hand-authored reactor.

**Dependencies.** None. Unblocks: every other milestone.

**Suggested commit.** `chore: bootstrap multi-module reactor`
BODY

create_issue "M2: add core domain records and sealed event hierarchy" "phase-1,core,native" "v0.1 MVP" <<'BODY'
**Context.** forvum-core holds pure-Java domain types with zero Quarkus dependency (§2.1, §4.3).

**Scope / Deliverables.** Domain records + sealed AgentEvent hierarchy and SQL-mirror enums.

**Files.** `forvum-core/.../id/AgentId.java`, `Identity.java`, `ChannelMessage.java`, `ToolSpec.java`, `ModelRef.java`, `FallbackChain.java`, `CostBudget.java`, `MemoryPolicy.java`, the sealed `AgentEvent permits TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent` (§4.3.2), SQL-mirror enums (§4.3.3), `PermissionScope` (§4.3.4). FallbackChain/MemoryPolicy carry the predating *TBD* markers until DR-4c/DR-5 land — do not invent their shape here.

**Acceptance Criteria.**
- Unit tests round-trip each record through Jackson; pattern-match over AgentEvent compiles with no `default` branch.
- `mvn dependency:analyze` confirms zero Quarkus dependency in forvum-core.
- [NATIVE] §10 marks M2 a must-run-native milestone; native parity is non-negotiable. The 6 AgentEvent permits must match the §4.3.2 list verbatim.
- jqwik property tests for `ModelRef.parse`, AgentEvent Jackson roundtrip, CostBudget invariants, `PermissionScope.fromName` (X3).
- [PLUGIN] context7 for Jackson/record-serialization API; no Quarkus extension here (quarkus/skills N/A).

**Dependencies.** M1. Unblocks: M3.

**Suggested commit.** `feat(core): add domain records and sealed event hierarchy`
BODY

create_issue "M3: define sealed provider interfaces and plugin marker" "phase-1,sdk,native" "v0.1 MVP" <<'BODY'
**Context.** forvum-sdk defines the sealed provider SPI that channels/providers/tools/memory hosts implement (§2.1, §5). Tied to Risk #3 (sealed interfaces + ArC discovery).

**Scope / Deliverables.** Sealed ChannelProvider/ModelProvider/ToolProvider/MemoryProvider, each permitting a `non-sealed abstract AbstractXProvider`; @ForvumExtension marker; the `META-INF/forvum/plugin.json` schema docs; re-export of @RegisterForReflection.

**Files.** `forvum-sdk/.../ChannelProvider.java` (+ ModelProvider/ToolProvider/MemoryProvider), paired `AbstractXProvider`, `@ForvumExtension`, plugin.json schema docs.

**Acceptance Criteria.**
- `SdkSurfaceTest` asserts (via reflection) that only `Abstract*` are direct permits and external classes cannot implement the sealed interface directly.
- forvum-sdk compile scope contains no quarkus-core.
- [NATIVE] M3 passes on native; if ArC warns on sealed interfaces, investigate before M7 (Risk #3).
- [PLUGIN] quarkus/skills for the @RegisterForReflection patterns; context7 for any reflection-config API.

**Dependencies.** M2. Unblocks: M7, M9-M17.

**Suggested commit.** `feat(sdk): define sealed provider interfaces and plugin marker`
BODY

create_issue "M4: add file-based config loader with WatchService hot reload" "phase-1,engine,native" "v0.1 MVP" <<'BODY'
**Context.** `~/.forvum/` is the configuration surface; "fixed code, configurable behavior" (§1.4) requires hot reload (§7.1 M4). Tied to Risk #7 (WatchService platform variance).

**Scope / Deliverables.** WatchService-backed config loader firing a CDI ConfigurationChangedEvent; ForvumHome resolution; per-subfolder readers; 250 ms debounce + coalesce.

**Files.** `forvum-engine/.../config/ConfigLoader.java`, `ConfigWatcher.java`, `ConfigurationChangedEvent.java`, `ForvumHome.java`, per-subfolder readers.

**Acceptance Criteria.**
- Integration test with @TempDir writes a synthetic `~/.forvum/`, fires modifications, asserts observers receive correct path/type; platform-matrix test (macOS polling vs inotify vs ReadDirectoryChangesW).
- [NATIVE] SANCTIONED CARVE-OUT. M4 must still native-COMPILE; it MAY skip the behavioral native assertion because WatchService OS-polling semantics are JVM-host behavior (Risk #7). This is the ONLY sanctioned behavioral carve-out in Phase 1 and must be justified in writing in the milestone Verify block.
- [PLUGIN] quarkus/skills for quarkus-jackson + CDI events before code; tests via Dev MCP.

**Dependencies.** M1, M2. Unblocks: M7, M19.

**Suggested commit.** `feat(engine): add file-based config loader with WatchService`
BODY

create_issue "M5: add SQLite persistence with Flyway V1 baseline" "phase-1,engine,persistence,native" "v0.1 MVP" <<'BODY'
**Context.** SQLite is the single-file local store (§4.2): WAL, foreign_keys=ON, 7-table V1 baseline, via Hibernate ORM + Panache + Flyway. Tied to Risk #11 (JDBC/virtual-thread pinning).

**Scope / Deliverables.** Flyway V1 baseline (7 tables); Panache entities; application.properties JDBC URL at `$FORVUM_HOME/state/forvum.sqlite`. Finalize the JDBC/virtual-thread pinning posture (Risk #11) and back-fill the chosen mitigation into §3.8. Baseline the per-turn performance gates (§10/X4) at M5. Decide the V2 add_turn_id migration boundary (recommend it lands at the M5/M6 boundary; §4.3.1 ties V2 to M6 consumption).

**Files.** `forvum-engine/.../db/migration/V1__baseline.sql`, `application.properties`, `forvum-engine/.../persistence/` Panache entities per table.

**Acceptance Criteria.**
- `SchemaSmokeIT` migrates a fresh file, inserts one row per table, dumps `.schema` against a golden file. A 100-turn synthetic pin-event run picks the no-unbounded-pins option (Risk #11 trigger); the chosen mitigation is back-filled into §3.8.
- [NATIVE] SQLite JDBC native loading + WAL work in native; `org.sqlite.lib.exportPath` set in the native profile; Flyway migrations registered as native resources (SQLite-only SQL); forward-only Flyway CI check applies. The pinning mitigation holds in native.
- [PLUGIN] quarkus/skills for hibernate-orm-panache + flyway before code; quarkus/searchDocs for SQLite dialect config; tests via Dev MCP. NOTE: adopting qlc4j 1.11.0.CR1 on Quarkus 3.33.1 (a matched pair) resolves the demo-branch Quarkiverse-vs-Quarkus build-step incompatibility recorded in deferral D8 (qlc4j 0.26.1 vs Quarkus 3.31.4).

**Dependencies.** M1. Unblocks: M8, M13, M18, M19.

**Suggested commit.** `feat(engine): add SQLite persistence with Flyway V1 baseline`
BODY

create_issue "M6: add @AgentScoped CDI context backed by ScopedValue (CRITICAL native)" "phase-1,engine,native,blocked" "v0.1 MVP" <<'BODY'
**Context.** @AgentScoped isolation (§5.1) is the core multi-agent safety property, backed by ScopedValue (JEP 506) and an ArC InjectableContext. THIS milestone resolves the headline native risk (Risk #1). RESOLVED FACTS: ScopedValue is FINAL in JDK 25 (not preview) — no `--enable-preview`, no preview-gated native flag. The only native risk is ArC InjectableContext build-time registration. The API form is the final builder `ScopedValue.where(KEY, v).call(body)` (`.run(...)` for void); the callWhere/runWhere static helpers were removed before finalization.

**Scope / Deliverables.** Custom @AgentScoped CDI scope via the ArC InjectableContext SPI, backed by `ScopedValue<AgentId>` (plus `ScopedValue<UUID> CURRENT_TURN`, `ScopedValue<Identity>` per §4.3.1/§4.1), with a BuildStep registration. Consumes the §4.3.1 turn-id contract (V2 columns).

**Files.** `forvum-core/.../AgentScoped.java`, `forvum-engine/.../context/AgentContext.java` (InjectableContext impl), `AgentContextBuildItem.java`, `AgentContextProcessor.java` (BuildStep), `CurrentAgent.java`.

**Acceptance Criteria.**
- A dual-thread integration test binds two AgentIds on two virtual threads, resolves the same @AgentScoped bean class on each, and asserts distinct `System.identityHashCode`.
- [NATIVE] CRITICAL. Risk #1 acceptance = CI green on BOTH JVM and native; two-thread isolation test passes native; the native build is `--enable-preview`-free (ScopedValue is final). The spike is scoped to the ArC InjectableContext build-step on native ONLY; there is no ThreadLocal/preview-flag fallback.
- Performance gates (§10/X4) baselined here.
- [PLUGIN] quarkus/skills for ArC/CDI-context patterns before code; quarkus/searchDocs for the InjectableContext SPI; tests via Dev MCP on native.

**Dependencies.** M1, M2, M5 (turn_id columns, §4.3.1). Unblocks: M7.

**Suggested commit.** `feat(engine): add @AgentScoped CDI context backed by ScopedValue`
BODY

create_issue "M7: add AgentRegistry with file-driven agent creation" "phase-1,engine,native" "v0.1 MVP" <<'BODY'
**Context.** File-driven agent creation + sub-agent spawn (§5.2): the registry materializes agents from `agents/<id>.md` + `<id>.json`, isolated per @AgentScoped.

**Scope / Deliverables.** AgentRegistry (getOrCreate + spawn); Agent @AgentScoped facade; AgentMemory; AgentToolBelt; AgentSpecReader (parses .md + .json).

**Files.** `forvum-engine/.../agent/AgentRegistry.java`, `AgentSpec.java`, `Agent.java`, `AgentMemory.java`, `AgentToolBelt.java`, `AgentSpecReader.java`.

**Acceptance Criteria.**
- Seed `agents/main.md` + `main.json`; `getOrCreate("main")` twice -> same instance; `spawn("main", childSpec)` -> distinct child AgentId with a narrower tool belt.
- [NATIVE] spawn isolation holds in native (depends on the M6 native verdict); PermissionScope/sealed discovery (Risk #3) clean before M7.
- [PLUGIN] quarkus/skills for CDI bean lifecycle; context7 for any LangChain4j memory API.

**Dependencies.** M4, M6, M3. Unblocks: M13, M15, M16, M17, M18.

**Suggested commit.** `feat(engine): add AgentRegistry with file-driven agent creation`
BODY

create_issue "M8: add FallbackChatModel decorator with failure classification" "phase-1,engine,provider,native" "v0.1 MVP" <<'BODY'
**Context.** Resilient model invocation (§5.4): a decorator chain over LangChain4j models that classifies failures and falls through to the next provider. Migrates FallbackTriggered.reason String -> FailureClass enum (§4.3.2 schedules this for M8).

**Scope / Deliverables.** FallbackChatModel/FallbackStreamingChatModel decorators over LangChain4j ChatModel/StreamingChatModel; sealed FailureClass + FailureClassifier. The classifier maps the `dev.langchain4j.exception` typed hierarchy via the core ExceptionMapper: 429->RateLimitException, 408->TimeoutException, 5xx->InternalServerException (all Retryable); 401/403->AuthenticationException, 404->ModelNotFoundException, other 4xx->InvalidRequestException (all NonRetryable); LangChain4jException root -> Unknown -> operator alert. Classify against the typed hierarchy, NOT string-matched HTTP codes; record the failing exception FQCN in the EXISTING nullable provider_calls.error column (no new column, no migration).

**Files.** `forvum-engine/.../model/FallbackChatModel.java`, `FallbackStreamingChatModel.java`, `FailureClass.java` (sealed), `FailureClassifier.java`.

**Acceptance Criteria.**
- Unit test: mock ChatModel throws RateLimitException then returns; assert provider_calls gets two rows, second is_fallback = 1.
- [NATIVE] decorator + langchain4j-core native-clean.
- [PLUGIN] context7 for the LangChain4j ChatModel/StreamingChatModel/dev.langchain4j.exception API (core 1.15.1 via qlc4j 1.11.0.CR1); quarkus/searchDocs for quarkus-langchain4j wiring.

**Dependencies.** M5 (writes provider_calls). Unblocks: M9-M12, M15, M17, M18.

**Suggested commit.** `feat(engine): add FallbackChatModel decorator with failure classification`
BODY

create_issue "M9: add local Ollama provider" "phase-1,provider,native,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** First ModelProvider (§7.1 M9), local and key-free; establishes the canonical provider-module creation flow via the Quarkus Agent MCP.

**Scope / Deliverables.** OllamaModelProvider wrapping quarkus-langchain4j-ollama; manifest.

**Files.** `forvum-provider-ollama/.../OllamaModelProvider.java`, `META-INF/forvum/plugin.json`.

**Acceptance Criteria.**
- With local `ollama serve` running `qwen3:1.7b`, a scripted turn produces a non-empty assistant message and >= 1 provider_calls row with provider='ollama'. Live turn tagged @Tag("live").
- [NATIVE] §10 lists M9-M12 (provider HTTP stack) as must-run-native; the per-provider native smoke is MANDATORY (Risk #5; Ollama well-exercised).
- [PLUGIN] add the quarkus-langchain4j-ollama extension via quarkus/searchTools + quarkus/callTool; quarkus/skills for the ollama extension before code; this is the canonical module-creation flow inherited by M10-M12.

**Dependencies.** M3, M8, M5. Unblocks: M10, M15.

**Suggested commit.** `feat(provider-ollama): add local Ollama provider`
BODY

create_issue "M10: add Anthropic provider" "phase-1,provider,native,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** Second provider (§7.1 M10), the first remote/keyed one, used as a fallback target from Ollama.

**Scope / Deliverables.** AnthropicModelProvider wrapping quarkus-langchain4j-anthropic; manifest.

**Files.** `forvum-provider-anthropic/` module, `AnthropicModelProvider.java`, manifest.

**Acceptance Criteria.**
- With ANTHROPIC_API_KEY, a scripted live turn produces a reply; a second turn with an invalid key falls through FallbackChatModel to Ollama. Live test @Tag("live").
- The example model id in the Verify text must be updated to a current model id at implementation time. The §7.1 baseline reads `claude-opus-4-7` (stale); OpenClaw's current default is `claude-opus-4.6` — use that or a current Anthropic id.
- [NATIVE] must-run-native (Risk #5).
- [PLUGIN] add the extension via quarkus/callTool; quarkus/skills for the anthropic extension; context7 for the langchain4j-anthropic API.

**Dependencies.** M3, M8, M9.

**Suggested commit.** `feat(provider-anthropic): add Anthropic provider`
BODY

create_issue "M11: add OpenAI provider" "phase-1,provider,native,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** Third provider (§7.1 M11), OpenAI-compatible.

**Scope / Deliverables.** OpenAiModelProvider wrapping quarkus-langchain4j-openai; manifest.

**Files.** `forvum-provider-openai/` module, `OpenAiModelProvider.java`, manifest.

**Acceptance Criteria.**
- With OPENAI_API_KEY, a scripted live turn produces a reply. Live test @Tag("live").
- The example model id (`gpt-4.1-mini` in the §7.1 baseline) must be updated to a current OpenAI id at implementation time.
- [NATIVE] must-run-native (Risk #5; OpenAI well-exercised).
- [PLUGIN] add the extension via the MCP; quarkus/skills for the openai extension; context7 for the API.

**Dependencies.** M3, M8.

**Suggested commit.** `feat(provider-openai): add OpenAI provider`
BODY

create_issue "M12: add Vertex AI Gemini provider" "phase-1,provider,native,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** Fourth provider (§7.1 M12). Highest native risk among providers (Risk #5: Vertex AI Gemini less exercised in native).

**Scope / Deliverables.** GoogleModelProvider wrapping quarkus-langchain4j-vertex-ai-gemini; manifest. If the Vertex gRPC stack blocks native, switch to the REST quarkus-langchain4j-ai-gemini (Google GenAI) extension — the native-first alternative — BEFORE any JVM-only carve-out.

**Files.** `forvum-provider-google/` module, `GoogleModelProvider.java`, manifest.

**Acceptance Criteria.**
- With Vertex credentials, a scripted live turn produces a reply. Live test @Tag("live").
- The example model id (`gemini-1.5-flash` in the §7.1 baseline) must be updated to a current Gemini id at implementation time.
- [NATIVE] must-run-native; per-provider native smoke is MANDATORY. If native is red, the REMEDY is switching to the REST quarkus-langchain4j-ai-gemini extension. A JVM-only carve-out is allowed ONLY after the REST remedy is exhausted, with an upstream issue filed and a release-note mark.
- [PLUGIN] add the extension via the MCP; quarkus/skills for the vertex-ai-gemini extension; context7 for the API.

**Dependencies.** M3, M8.

**Suggested commit.** `feat(provider-google): add Vertex AI Gemini provider`
BODY

create_issue "M13: add ToolRegistry with glob filtering and permission scopes" "phase-1,engine,tool,security,native" "v0.1 MVP" <<'BODY'
**Context.** Tool capability gating (§5.3, §4.3.4): the Select pillar applied to capability. Tied to the security-test layer (§10) and to the X7 gap (shell tool, SkillInvokerTool skills surface, MCP bridge baseline have no dedicated milestone — fold them here or into M18, or split micro-issues).

**Scope / Deliverables.** ToolRegistry; ToolExecutor (enforces PermissionScope); PermissionScope enum (§4.3.4); ToolFilter (glob matching). Decide X7 placement: shell tool + skills surface + mcp-bridge (flagged off, Risk #9) + OTel baseline as M13/M18 acceptance vs micro-milestones.

**Files.** `forvum-engine/.../tools/ToolRegistry.java`, `ToolExecutor.java`, `PermissionScope.java`, `ToolFilter.java`.

**Acceptance Criteria.**
- Register `a.read`/`a.write`; seed an agent with `allowedTools:["a.read"]`; assert a call to `a.write` is refused with PermissionDeniedException and logged tool_invocations.status='denied'.
- [NATIVE] glob/permission enforcement native-clean.
- Security negative test (X5/TEST-SEC): PermissionScope mismatch -> denied + audited.
- [PLUGIN] quarkus/skills for CDI; context7 for the langchain4j ToolSpecification/ToolExecutor surface.

**Dependencies.** M3, M7, M5. Unblocks: M14, M18.

**Suggested commit.** `feat(engine): add ToolRegistry with glob-based filtering and permission scopes`
BODY

create_issue "M14: add filesystem read/write/list tools with FS permission scope" "phase-1,tool,security,native,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** First first-party tool module (§7.1 M14): read/write/list within a configured workspace root.

**Scope / Deliverables.** forvum-tools-filesystem: FsReadTool (FS_READ), FsWriteTool (FS_WRITE), FsListTool; FilesystemToolProvider; manifest.

**Files.** `forvum-tools-filesystem/` module, `FilesystemToolProvider.java`, `FsReadTool.java`, `FsWriteTool.java`, `FsListTool.java`, manifest.

**Acceptance Criteria.**
- Integration test against @TempDir: read/write/list round-trip; a write outside the configured workspace root is denied.
- [NATIVE] native file I/O native-clean; native parity applies.
- Security negative test (X5/TEST-SEC): path traversal in fs-tool args -> denied (the WorkspaceRoot contract is defined by DR-6a).
- [PLUGIN] quarkus/skills for the SDK/tool patterns; module scaffolded via the MCP.

**Dependencies.** M3, M13.

**Suggested commit.** `feat(tools-fs): add filesystem read/write/list tools with FS permission scope`
BODY

create_issue "M15: add TamboUI-based TUI channel with streaming rendering" "phase-1,channel,native,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** The terminal REPL (§3.5, §7.1 M15): the channel where native cold-start matters most. Built with the TamboUI Toolkit (declarative widgets + TCSS) on the tamboui-jline3-backend. Tied to Risk #6 (TamboUI/JLine on Windows under GraalVM) and Risk #14 (TamboUI pre-1.0 maturity).

**Scope / Deliverables.** forvum-channel-tui: a TamboUI Toolkit view with streaming token rendering, a TCSS theme, `--no-ansi` fallback (first-class from M15), and the TamboUI + JLine-backend GraalVM reachability metadata; manifest. Evaluate the tamboui-panama-backend (Java FFM, no external dep, best startup) as the native-first alternative.

**Files.** `forvum-channel-tui/.../TuiChannel.java`, `TuiView.java` (TamboUI component tree), `src/main/resources/tui.tcss`, the `--no-ansi` path, `META-INF/native-image/.../reachability-metadata.json`, manifest.

**Acceptance Criteria.**
- Integration test pipes scripted stdin through the binary and asserts the rendered TamboUI output contains the assistant reply; `-Dforvum.no-ansi=true < input.txt` is identical.
- [NATIVE] §10 lists M15 must-run-native; native cold-start is the headline metric (TamboUI is GraalVM-native-first, sub-100 ms). Windows CI runs the TUI smoke in ANSI and no-ANSI; no-ANSI default on Windows if red (Risk #6).
- [PLUGIN] TamboUI is NOT a Quarkus extension -> use context7 for the TamboUI Toolkit / TCSS / backend API and for JLine native-image hints; quarkus/skills for any Quarkus extension used.

**Dependencies.** M3, M7, M8.

**Suggested commit.** `feat(channel-tui): add TamboUI-based TUI channel with streaming rendering`
BODY

create_issue "M16: add WebSockets Next chat channel with minimal UI" "phase-1,channel,native,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** Browser chat channel (§7.1 M16) over Quarkus WebSockets Next with per-socket sessions. The security-test directory lands alongside the web channel (§10).

**Scope / Deliverables.** forvum-channel-web: WebSockets Next server + minimal static HTML/JS chat UI; per-socket sessions.

**Files.** `forvum-channel-web/.../WebChannel.java`, `ChatSocket.java`, `src/main/resources/META-INF/resources/index.html`, `chat.js`, manifest.

**Acceptance Criteria.**
- In dev mode, opening `http://localhost:8080/` and exchanging a message shows streamed tokens; a second tab gets a separate session id.
- [NATIVE] §10 lists M16 must-run-native.
- [PLUGIN] quarkus/skills for websockets-next before code; quarkus/searchDocs; tests via Dev MCP.

**Dependencies.** M3, M7. Unblocks: P2-14.

**Suggested commit.** `feat(channel-web): add WebSockets Next chat channel with minimal UI`
BODY

create_issue "M17: add long-poll Telegram channel" "phase-1,channel,native,security,plugin-tooling" "v0.1 MVP" <<'BODY'
**Context.** First messaging channel (§7.1 M17). Tied to Risk #8 (long-poll vs webhook) and Risk #12 (Mutiny <-> virtual-thread boundary).

**Scope / Deliverables.** forvum-channel-telegram: long-poll bot via quarkus-rest-client-reactive; webhook opt-in; allowedUserIds gate; UpdateProcessor as the Mutiny<->virtual-thread seam.

**Files.** `forvum-channel-telegram/.../TelegramChannel.java`, `TelegramBotApi.java` (REST client), `UpdateProcessor.java`, manifest.

**Acceptance Criteria.**
- With a bot token in the keychain, a live DM produces a reply within the turn-latency budget; allowedUserIds refuses other users with a friendly message. The bannedDependencies enforcer step fails if any engine code introduces `io.smallrye.mutiny:*` (Risk #12).
- [NATIVE] §10 lists M17 must-run-native (REST-client reactive stack must compile native).
- Security negative test (X5/TEST-SEC): a spawn-boundary identity-override attempt is rejected.
- [PLUGIN] quarkus/skills for rest-client-reactive; context7 for Telegram Bot API shapes; add the extension via the MCP.

**Dependencies.** M3, M7, M8.

**Suggested commit.** `feat(channel-telegram): add long-poll Telegram channel`
BODY

create_issue "M18: add LangGraph4j supervisor-workers orchestration (CRITICAL native)" "phase-1,engine,native,context-engineering,blocked" "v0.1 MVP" <<'BODY'
**Context.** The Orchestrator-Workers hub-and-spoke topology (§5.5) materialized as a LangGraph4j StateGraph. REWRITTEN per the native mandate (REQ #1): there is NO StructuredTaskScope spike. StructuredTaskScope (JEP 505) is preview in JDK 25 and is NOT adopted in v0.1. Structured fan-out is the committed design using `Executors.newVirtualThreadPerTaskExecutor()` (try-with-resources) + CompletionStage join (or LangGraph4j orchestration). The native build is `--enable-preview`-free by construction. Structured concurrency is re-evaluated only after the JEP finalizes (post-JDK 26) — a roadmap note, not a v0.1 spike. Tied to Risk #4 (corrected: LangGraph4j is stable 1.8.x, pinned at 1.8.17, not pre-1.0) and the LangGraph4j native-metadata risk.

**Scope / Deliverables.** SupervisorGraph (StateGraph compiler) with nodes route, generate, tool_loop, spawn_worker, worker_run, reduce; GraphState. The reduce node Compresses each worker's window through the small-and-fast model (qwen3:1.7b) so only the digest crosses the worker->supervisor boundary (Isolate defense + cross-agent-injection guardrail; CE REQ #2). Workers run in parallel on virtual threads (§3.8), replacing a serial cascade.

**Files.** `forvum-engine/.../graph/SupervisorGraph.java`, node impls (route/generate/tool_loop/spawn_worker/worker_run/reduce), `GraphState.java`.

**Acceptance Criteria.**
- A multi-tool scenario ("fetch X then summarize") routes tool_loop->generate, produces the expected final message, and writes a CAPR event for the turn.
- [NATIVE] CRITICAL. Acceptance = VT fan-out works in native + the native graph smoke is green on both CI platforms (LangGraph4j hand-authored reachability metadata in place under `forvum-engine/src/main/resources/META-INF/native-image/`; graph-state types are records carrying @RegisterForReflection). NO `--enable-preview`. NO STS decision sub-issue.
- [PLUGIN] LangGraph4j is NOT a Quarkus extension -> use context7 (NOT quarkus/skills) for the StateGraph/MessagesState/LC4jToolService API. Reuse the template's sub-agent + streaming-bridge + @WebSocket shapes, but orchestrate with the LangGraph4j StateGraph, NOT the declarative @SequenceAgent/@SupervisorAgent annotations.

**Dependencies.** M5, M7, M8, M13. Unblocks: M19, P2-12, P3-3, P3-4, P3-8, P3-10.

**Suggested commit.** `feat(engine): add LangGraph4j supervisor-workers orchestration`
BODY

create_issue "M19: add file-driven cron scheduler with per-cron LLM chain" "phase-1,engine,native" "v0.1 MVP" <<'BODY'
**Context.** Background runs from `~/.forvum/crons/*.json` (§7.1 M19), each with its own LLM chain.

**Scope / Deliverables.** CronScheduler registers @Scheduled programmatically from `crons/*.json`; per-cron FallbackChain distinct from the agent default; overlap suppression; hot reload on new cron file. @Scheduled methods carry @RunOnVirtualThread (§3.8).

**Files.** `forvum-engine/.../cron/CronScheduler.java`, `CronSpec.java`, `CronTrigger.java`.

**Acceptance Criteria.**
- A cron firing every minute pinned to Ollama triggers a turn and writes messages/provider_calls/capr_events; adding a cron file reloads without restart.
- [NATIVE] scheduler fires in native; native parity applies.
- [PLUGIN] quarkus/skills for quarkus-scheduler before code; tests via Dev MCP.

**Dependencies.** M4, M5, M7, M8, M18.

**Suggested commit.** `feat(engine): add file-driven cron scheduler with per-cron LLM chain`
BODY

create_issue "M20: add GraalVM native image profile and CI matrix (CAPSTONE)" "phase-1,native,ci-infra,plugin-tooling,blocked" "v0.1 MVP" <<'BODY'
**Context.** The capstone (§6, §7.1 M20). Operationalizes the native mandate: the §10 native-parity amendment ("selective" -> "mandatory") lands here.

**Scope / Deliverables.** Native application.properties flags; .github/workflows/ci.yml matrix (linux-amd64, macos-arm64; JVM + native builds; native smoke with the 200 ms cold-start gate); Dockerfile.jvm, Dockerfile.native. REWRITE the CI matrix so native is MANDATORY on every cell (not selective); the 200 ms cold-start gate AND the native-build gate fail the PR. GraalVM CE 25 / Mandrel 25.0.x-Final on runners (pin the exact Mandrel patch in CI).

**Files.** `forvum-app/src/main/resources/application.properties`, `.github/workflows/ci.yml`, `Dockerfile.jvm`, `Dockerfile.native`.

**Acceptance Criteria.**
- `./mvnw -f forvum-app -Pnative package -Dquarkus.native.container-build=true` succeeds on a clean runner; `./forvum-app-<version>-runner --help` prints in < 200 ms.
- [NATIVE] CAPSTONE. Native is mandatory on every CI cell; the only sanctioned skip is the M4 behavioral assertion (which must still native-compile). No `--enable-preview` anywhere (M6/M18 resolved preview-free).
- [PLUGIN] quarkus/searchDocs for native + container-build config; Dev MCP for build verification.

**Dependencies.** ALL of M1-M19 (capstone); hard prereq on the M6 and M18 native verdicts. Unblocks: P3-1, X6 native gating.

**Suggested commit.** `feat(app): add GraalVM native image profile and CI matrix`
BODY

# ============================================================================
# EPIC-2 — PHASE 2 v0.5 PARITY
# ============================================================================

create_issue "P2-1: add headless browser web.browse tool" "phase-2,tool,native" "v0.5 Parity" <<'BODY'
**Context.** Headless-browser capability for parity.

**Scope / Deliverables.** web.browse tool (Playwright Java) with PermissionScope.WEB_BROWSE. Files: forvum-tools-browser.

**Acceptance Criteria.**
- A web.browse call against a local fixture page returns extracted content; scope enforced.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] Playwright native-image is high-risk -> likely JVM-only carve-out, must be justified with an upstream issue.
- [PLUGIN] scaffold module via the MCP; context7 for Playwright-Java + the langchain4j tool API.

**Dependencies.** M13, M14, MVP stable.

**Suggested commit.** `feat(tools-browser): add headless browser web.browse tool`
BODY

create_issue "P2-2: add containerized code-execution sandbox" "phase-2,tool,security,native" "v0.5 Parity" <<'BODY'
**Context.** Safe shell.exec replacement.

**Scope / Deliverables.** Run code in a container or Firecracker microVM. Files: forvum-tools-sandbox.

**Acceptance Criteria.**
- Sandboxed run returns output; escape attempt contained; USER_CONFIRM_REQUIRED honored.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native binary launches the sandbox runtime; verify.
- [PLUGIN] scaffold via the MCP.

**Dependencies.** M13, the forvum-tools-shell pattern (X7 — shell tool has no Phase-1 milestone; resolve via M13).

**Suggested commit.** `feat(tools-sandbox): add containerized code-execution sandbox`
BODY

create_issue "P2-3: add local Whisper/Piper voice channel" "phase-2,channel,native" "v0.5 Parity" <<'BODY'
**Context.** Local TTS/STT parity.

**Scope / Deliverables.** Whisper + Piper streaming channel. Files: forvum-channel-voice.

**Acceptance Criteria.**
- Spoken input transcribed -> turn -> spoken reply streamed.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native Whisper/Piper bindings — verify or JVM-only carve-out.
- [PLUGIN] scaffold channel module via the MCP.

**Dependencies.** M3, M7, MVP stable.

**Suggested commit.** `feat(channel-voice): add local Whisper/Piper voice channel`
BODY

create_issue "P2-4: add device pairing reusing identity and memory" "phase-2,engine,native" "v0.5 Parity" <<'BODY'
**Context.** Pair a second device reusing identity + memory.

**Scope / Deliverables.** forvum-engine/pairing.

**Acceptance Criteria.**
- Paired device shares identity + memory namespace; unpaired device rejected.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] engine submodule, native parity.
- [PLUGIN] quarkus/skills for any extension.

**Dependencies.** M6, M7, MVP stable. Extended by P2-PAIR-SCOPE.

**Suggested commit.** `feat(engine): add device pairing reusing identity and memory`
BODY

create_issue "P2-5: document MemoryProvider host SPI with reference implementation" "phase-2,sdk,native" "v0.5 Parity" <<'BODY'
**Context.** Public SPI for third-party MemoryProvider impls.

**Scope / Deliverables.** Document the SPI (declared at M3) + reference impl (Redis/Qdrant/Chroma) + docs. Files: forvum-sdk + a reference impl module.

**Acceptance Criteria.**
- A reference external MemoryProvider loads and serves semantic memory via the SPI.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] reference impl native-clean.
- [PLUGIN] scaffold the reference module via the MCP.

**Dependencies.** M3, semantic_memory (M5). DR-5 (MemoryPolicy) informs the SPI.

**Suggested commit.** `feat(sdk): document MemoryProvider host SPI with reference implementation`
BODY

create_issue "P2-6: add Maven-coordinate plugin install command" "phase-2,engine,native" "v0.5 Parity" <<'BODY'
**Context.** `forvum plugin install <coords>`.

**Scope / Deliverables.** Resolve a Maven coordinate, write to `~/.forvum/plugins/`, trigger fast-jar restart; native users told to rebuild. Files: engine CLI command.

**Acceptance Criteria.**
- Installing a coordinate makes a drop-in plugin available in fast-jar after restart.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] drop-in is JVM-fast-jar-ONLY BY DESIGN (§6.2/§6.3) — a documented architectural property, NOT a carve-out from the mandate; native path = rebuild.
- [PLUGIN] scaffold via the MCP.

**Dependencies.** §6.3 build-time discovery, ServiceLoader fast-jar fallback.

**Suggested commit.** `feat(app): add Maven-coordinate plugin install command`
BODY

create_issue "P2-7: add skill install command from URL" "phase-2,engine,native" "v0.5 Parity" <<'BODY'
**Context.** `forvum skill install <url>`.

**Scope / Deliverables.** Add a `skills/<skill>.md` from a git repo or gist. Files: engine CLI command.

**Acceptance Criteria.**
- Installing a URL adds a skill .md invocable by the skill tool.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity (pure file write).
- [PLUGIN] scaffold via the MCP.

**Dependencies.** the skills surface (SkillInvokerTool; X7 — no Phase-1 milestone; resolve via M13/M18).

**Suggested commit.** `feat(app): add skill install command from URL`
BODY

create_issue "P2-8: add session replay command" "phase-2,engine,native" "v0.5 Parity" <<'BODY'
**Context.** Replay a session for debugging/regression.

**Scope / Deliverables.** CLI replays a session from messages with original tool outputs. Files: engine CLI command.

**Acceptance Criteria.**
- Replaying a stored session reproduces the message sequence and tool outputs.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity.
- [PLUGIN] scaffold via the MCP.

**Dependencies.** M5. Extended by P3-9.

**Suggested commit.** `feat(app): add session replay command`
BODY

create_issue "P2-9: add config doctor validating ~/.forvum layout" "phase-2,engine,native" "v0.5 Parity" <<'BODY'
**Context.** `forvum doctor`.

**Scope / Deliverables.** Validate the whole `~/.forvum/` layout against JSON Schemas with actionable hints. Files: engine CLI command + JSON Schemas.

**Acceptance Criteria.**
- A malformed config produces a specific actionable error; valid config passes.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity.
- [PLUGIN] scaffold via the MCP.

**Dependencies.** M4. Used by P2-PAIR-SCOPE, P3-6.

**Suggested commit.** `feat(app): add config doctor validating ~/.forvum layout`
BODY

create_issue "P2-10: add interactive provider onboarding wizard" "phase-2,engine,native" "v0.5 Parity" <<'BODY'
**Context.** `forvum provider add <name>`.

**Scope / Deliverables.** Walk a keychain entry, default fallback-chain update, smoke-test turn. Files: engine CLI command.

**Acceptance Criteria.**
- Running the wizard stores a key in the keychain, updates the config.json chain, and runs a smoke turn.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native keychain access (macOS Keychain / Secret Service / Win Credential Manager) must work native.
- [PLUGIN] scaffold via the MCP.

**Dependencies.** M9-M12, M4. Extended by P2-COPILOT.

**Suggested commit.** `feat(app): add interactive provider onboarding wizard`
BODY

create_issue "P2-11: add role-based access control on tool permission scopes" "phase-2,core,security,native" "v0.5 Parity" <<'BODY'
**Context.** Role-based permission sets.

**Scope / Deliverables.** Extend PermissionScope to role-based sets; `identities/<id>.json` declares roles; cron jobs get a distinguished `cron` role. Files: forvum-core (PermissionScope growth, §4.3.4), engine ToolExecutor.

**Acceptance Criteria.**
- A role-restricted identity is denied an out-of-role tool; the cron role is enforced.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity; tied to the security-test layer.
- [PLUGIN] quarkus/skills.

**Dependencies.** M13. Absorbs P2-CRON-DELIVERY's cron-role hook.

**Suggested commit.** `feat(core): add role-based access control on tool permission scopes`
BODY

create_issue "P2-12: add per-agent structured output schema decoding" "phase-2,engine,native" "v0.5 Parity" <<'BODY'
**Context.** Agents declare an output JSON Schema.

**Scope / Deliverables.** AgentGraph decodes the final message against the schema via LangChain4j structured output. Files: engine agent/graph.

**Acceptance Criteria.**
- An agent with a declared schema returns a decoded, schema-valid object; invalid output surfaces an error.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity.
- [PLUGIN] context7 for langchain4j structured output (@Description/@StructuredPrompt, §3.3).

**Dependencies.** M7, M18.

**Suggested commit.** `feat(engine): add per-agent structured output schema decoding`
BODY

create_issue "P2-13: add MCP server registry add/list commands" "phase-2,tool,native" "v0.5 Parity" <<'BODY'
**Context.** `forvum mcp add <url>` / `forvum mcp list`.

**Scope / Deliverables.** Remote MCP tools appear in ToolRegistry within seconds. Files: forvum-tools-mcp-bridge (declared §2.4, off-by-default in v0.1 per Risk #9) + CLI.

**Acceptance Criteria.**
- Adding an MCP server URL surfaces its tools; `mcp list` shows them.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] Risk #9: stdio MCP servers spawn subprocesses; flip default-on only after the native smoke passes on all platforms. The MCP client is the Quarkiverse quarkus-langchain4j-mcp extension (native-ready), NOT the standalone langchain4j-mcp beta.
- [PLUGIN] quarkus/skills for the MCP-client extension.

**Dependencies.** M13, the forvum-tools-mcp-bridge baseline (X7 — no Phase-1 milestone; resolve via M13).

**Suggested commit.** `feat(tools-mcp): add MCP server registry add/list commands`
BODY

create_issue "P2-14: add user-approval queue UI for confirm-required tools" "phase-2,engine,channel,security,native" "v0.5 Parity" <<'BODY'
**Context.** Surface pending USER_CONFIRM_REQUIRED tool calls.

**Scope / Deliverables.** Dev UI + web-channel cards with approve/reject. Files: Dev UI card + web channel.

**Acceptance Criteria.**
- A pending confirm-required call appears; approve runs it, reject denies + audits.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] Dev UI is dev-mode (fast-jar); the web-channel card must work native.
- [PLUGIN] quarkus/skills for Dev UI card patterns.

**Dependencies.** M13 (the USER_CONFIRM_REQUIRED hook), M16. The per-channel security UX is specified by DR-6a.

**Suggested commit.** `feat(engine): add user-approval queue UI for confirm-required tools`
BODY

create_issue "P2-15: add OTLP telemetry export gated by endpoint env var" "phase-2,engine,observability,native" "v0.5 Parity" <<'BODY'
**Context.** OTLP export.

**Scope / Deliverables.** OTLP exporter on when OTEL_EXPORTER_OTLP_ENDPOINT is set (default off); zero-config for Honeycomb/Grafana Tempo/Datadog; also exports the §3.8 Concurrency-card VT/PT + pin data. Files: engine observability config.

**Acceptance Criteria.**
- With the env var set, spans/metrics export to a local OTLP collector; default-off when unset.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] quarkus-opentelemetry OTLP native parity (let the Quarkus BOM govern the version).
- [PLUGIN] quarkus/searchDocs for OTLP config.

**Dependencies.** the §3.6 OTel baseline (X7 — wired across M-milestones, no single milestone; resolve via M13/M18).

**Suggested commit.** `feat(engine): add OTLP telemetry export gated by endpoint env var`
BODY

create_issue "P2-CH: add Discord/Slack/WhatsApp/Matrix/Signal first-party channels" "phase-2,channel,native" "v0.5 Parity" <<'BODY'
**Context.** OpenClaw ships a broad channel catalog; Forvum replicates the architecture and ships a curated set (§7.2 item 16).

**Scope / Deliverables.** forvum-channel-discord, -slack, -whatsapp, -matrix, -signal. The long tail (iMessage/BlueBubbles, Teams, Google Chat, Mattermost, Feishu, LINE, QQ, Zalo/ZaloUser, IRC, Nostr, Tlon, Twitch, Synology Chat, Nextcloud Talk, telephony voice-call) is explicitly OUT of v0.5 scope = community-plugin territory (via the §7.2 item 6 marketplace).

**Acceptance Criteria.**
- Each shipped channel exchanges a message round-trip; the out-of-scope long tail is documented as community-plugin territory.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] every shipped channel native-buildable + native smoke.
- [PLUGIN] scaffold each channel module via the MCP; quarkus/skills for the HTTP/WS extension per channel.

**Dependencies.** M3, M7, M17 (channel pattern), MVP stable.

**Suggested commit.** `feat(channel): add Discord/Slack/WhatsApp/Matrix/Signal first-party channels`
BODY

create_issue "P2-COPILOT: add GitHub Copilot model provider" "phase-2,provider,native,plugin-tooling" "v0.5 Parity" <<'BODY'
**Context.** OpenClaw supports Copilot as a provider (§7.2 item 17).

**Scope / Deliverables.** forvum-provider-copilot (OpenAI-compatible endpoint; Copilot OAuth/device-code via the onboarding wizard P2-10). Files: forvum-provider-copilot/ module + onboarding wizard hook.

**Acceptance Criteria.**
- Device-code OAuth completes, a key is stored, a turn against the Copilot endpoint returns a reply.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] must-run-native (OpenAI-compatible HTTP).
- [PLUGIN] add the openai-compatible extension via the MCP; context7 for the API.

**Dependencies.** M11 (OpenAI-compatible pattern), P2-10 (wizard).

**Suggested commit.** `feat(provider-copilot): add GitHub Copilot model provider`
BODY

create_issue "P2-QA: add QA scenario suite with CI gate" "phase-2,ci-infra,native" "v0.5 Parity" <<'BODY'
**Context.** OpenClaw ships a QA scenario harness (§7.2 item 18).

**Scope / Deliverables.** `forvum qa suite` / `forvum qa <channel>`, fails-by-default; a scenario pack ships in the release; CI gate. Files: QA harness module + scenario pack + CI step.

**Acceptance Criteria.**
- `forvum qa suite` runs the packaged scenarios and fails by default on a missing/failed scenario; the CI gate enforces it.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] the QA runner runs against the native binary.
- [PLUGIN] tests via Dev MCP; scaffold via the MCP.

**Dependencies.** M15, M16, M17 (channels under test), X6 (E2E).

**Suggested commit.** `feat(app): add QA scenario suite with CI gate`
BODY

create_issue "P2-PAIR-SCOPE: add device pairing scope-upgrade approval with drift detection" "phase-2,engine,security,native" "v0.5 Parity" <<'BODY'
**Context.** Extends P2-4 with scope governance (§7.2 item 19).

**Scope / Deliverables.** Scope-upgrade approval with reason codes; requested-vs-approved shown in the Dev UI + `forvum devices`; `forvum doctor` surfaces drift. Files: forvum-engine/pairing + forvum devices CLI + Dev UI + doctor hook.

**Acceptance Criteria.**
- A scope upgrade requires approval, records reason codes, and drift is surfaced by `forvum doctor`.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity.
- [PLUGIN] quarkus/skills for Dev UI cards.

**Dependencies.** P2-4, P2-9 (doctor), P2-11 (RBAC).

**Suggested commit.** `feat(engine): add device pairing scope-upgrade approval with drift detection`
BODY

create_issue "P2-COMPACT: add prefix-preserving session compaction" "phase-2,engine,context-engineering,native" "v0.5 Parity" <<'BODY'
**Context.** OpenClaw compacts sessions to fit the context window; this is a CE Compress realization (§7.2 item 20; cross-links REQ #2).

**Scope / Deliverables.** Reserve-token floor capped to the context window; mutate the oldest turns first to preserve the cached prefix; strip orphaned reasoning/tool blocks. Files: engine session/compaction submodule.

**Acceptance Criteria.**
- A session exceeding the reserve floor is compacted oldest-first, the cached prefix is preserved, orphaned blocks are stripped, and CAPR is not regressed.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity.
- [PLUGIN] context7 for langchain4j memory/summarization.

**Dependencies.** M5, M18 (the reduce Compress path).

**Suggested commit.** `feat(engine): add prefix-preserving session compaction`
BODY

create_issue "P2-TASKLEDGER: add TaskExecutor SPI with unified SQLite task ledger" "phase-2,engine,sdk,native" "v0.5 Parity" <<'BODY'
**Context.** Forvum improves on OpenClaw with a queryable task ledger as a day-one primitive (§1.2; §7.2 item 21).

**Scope / Deliverables.** TaskExecutor SPI in forvum-sdk; a SQLite `tasks` ledger unifying cron/sub-agent/background runs. Files: forvum-sdk/.../TaskExecutor.java + engine task runtime + Flyway migration for `tasks`.

**Acceptance Criteria.**
- Cron/sub-agent/background runs all register in the tasks ledger and are queryable.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity.
- [PLUGIN] quarkus/skills for the persistence extension; tests via Dev MCP.

**Dependencies.** M5, M7, M18, M19.

**Suggested commit.** `feat(sdk): add TaskExecutor SPI with unified SQLite task ledger`
BODY

create_issue "P2-CRON-DELIVERY: add cron isolated-agent delivery modes" "phase-2,engine,native" "v0.5 Parity" <<'BODY'
**Context.** Control where a cron's isolated-agent output is delivered (§7.2 item 22).

**Scope / Deliverables.** `delivery.mode: none|last|explicit-to`; per-execution dedupe; ambiguity rejected at add/update; folds into the P2-11 cron RBAC role. Files: forvum-engine/.../cron/ + CronSpec growth.

**Acceptance Criteria.**
- Each delivery mode routes output correctly; per-execution dedupe holds; an ambiguous spec is rejected at add/update.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity.
- [PLUGIN] quarkus/skills.

**Dependencies.** M19, P2-11.

**Suggested commit.** `feat(engine): add cron isolated-agent delivery modes`
BODY

create_issue "P2-OUTPUTGUARD: add OutputGuard outbound sensitive-data filter SPI" "phase-2,sdk,security,context-engineering,native,blocked" "v0.5 Parity" <<'BODY'
**Context.** The v0.5 realization of the §1.4 outbound-filter promise: an outbound secret/PII filter (§7.2 item 23; CE REQ #2). The full contract is defined by design-round Group 6a (§9.2 OutputFilter).

**Scope / Deliverables.** OutputGuard SPI in forvum-sdk; outbound sensitive-data filter at the pre-channel-emit hook. Files: forvum-sdk/.../OutputGuard.java + engine enforcement.

**Acceptance Criteria.**
- A configured OutputGuard blocks/redacts outbound secrets/PII at the channel boundary per the §9.2 contract.
- Parity verified against OpenClaw v2026.4.19-beta.2.
- [NATIVE] native parity.
- [PLUGIN] quarkus/skills for the SPI/CDI pattern.

**Dependencies.** M13, M16/M17 (channel emit), DR-6a (defines the OutputFilter contract — blocks this).

**Suggested commit.** `feat(sdk): add OutputGuard outbound sensitive-data filter SPI`
BODY

# ============================================================================
# EPIC-3 — PHASE 3 v1.0+
# ============================================================================

create_issue "P3-1: add curl-based single-binary native installer" "phase-3,native,ci-infra" "v1.0+" <<'BODY'
**Context.** The product expression of the native mandate (REQ #1): `curl | sh` drops a ~40 MB native binary; no runtime/Docker/Node.

**Scope / Deliverables.** Install script + release pipeline (extends §6.4).

**Acceptance Criteria.**
- `curl | sh` installs a runnable native binary on linux-x64 and macos-arm64; size budget ~40 MB.
- [NATIVE] the core of the whole mandate.
- [PLUGIN] N/A (uses §6.4 distribution).

**Dependencies.** M20, §6.4 distribution.

**Suggested commit.** `feat(release): add curl-based single-binary native installer`
BODY

create_issue "P3-2: add queryable semantic memory CLI over sqlite-vec" "phase-3,engine,persistence,native" "v1.0+" <<'BODY'
**Context.** `forvum memory query 'SELECT ...'` over SQLite + sqlite-vec. Highest native-risk Phase-3 item (Risk #2).

**Scope / Deliverables.** engine CLI + Flyway V3 (sqlite-vec vec0 virtual table).

**Acceptance Criteria.**
- A SQL query over semantic_memory returns rows; vector search returns nearest neighbors.
- [NATIVE] Risk #2: sqlite-vec is a C extension; native static-linking varies by platform — benchmark linear scan at 10k/100k/1M; defer vec0 if linear is acceptable at 100k.
- [PLUGIN] quarkus/searchDocs for SQLite extension loading.

**Dependencies.** M5, P2-5, the Risk #2 decision.

**Suggested commit.** `feat(app): add queryable semantic memory CLI over sqlite-vec`
BODY

create_issue "P3-3: add declarative cyclic-agent compilation to StateGraph" "phase-3,engine,native" "v1.0+" <<'BODY'
**Context.** Declarative cycles (e.g. reflect->critique->revise).

**Scope / Deliverables.** Engine compiles a declared cycle into a StateGraph with no custom code.

**Acceptance Criteria.**
- An agent with a declared cycle runs the loop to a termination condition and produces a refined result.
- [NATIVE] native parity (depends on the M18 VT-fan-out verdict).
- [PLUGIN] context7 for the LangGraph4j cyclic-graph API.

**Dependencies.** M18.

**Suggested commit.** `feat(engine): add declarative cyclic-agent compilation to StateGraph`
BODY

create_issue "P3-4: add CAPR-driven adaptive model routing" "phase-3,engine,observability,native" "v1.0+" <<'BODY'
**Context.** LlmSelector consults rolling CAPR per model over the last N turns and down-ranks sagging models; the router is itself a small local model seeing the CAPR snapshot (Select pillar).

**Scope / Deliverables.** forvum-engine/.../routing/.

**Acceptance Criteria.**
- With seeded CAPR data, a low-pass-rate model is down-ranked in routing decisions.
- [NATIVE] native parity.
- [PLUGIN] quarkus/skills; context7 for langchain4j.

**Dependencies.** M8, M18 (CAPR events), P3-10.

**Suggested commit.** `feat(engine): add CAPR-driven adaptive model routing`
BODY

create_issue "P3-5: add multi-user toggle with per-user isolation" "phase-3,engine,persistence,native" "v1.0+" <<'BODY'
**Context.** `multiUser:true` enables per-user isolation; same binary both modes.

**Scope / Deliverables.** Per-user $FORVUM_HOME isolation, identity-scoped SQLite schemas, shared-memory namespace for team skills.

**Acceptance Criteria.**
- With multiUser on, two users get isolated state; the team-skill namespace is shared.
- [NATIVE] native parity (same binary).
- [PLUGIN] quarkus/skills.

**Dependencies.** M5, M6, M7. Unblocks P3-7.

**Suggested commit.** `feat(engine): add multi-user toggle with per-user isolation`
BODY

create_issue "P3-6: add Dev UI live config editor" "phase-3,engine,native" "v1.0+" <<'BODY'
**Context.** Edit `~/.forvum/` files from the Dev UI with schema validation + hot-reload preview.

**Scope / Deliverables.** Dev UI cards (extends the §3.2/§6.1 Dev UI surface).

**Acceptance Criteria.**
- Editing a config in the Dev UI validates against the schema and hot-reloads without restart.
- [NATIVE] Dev UI is dev-mode (fast-jar) ONLY — explicit, documented native carve-out (Dev UI is not in the native binary).
- [PLUGIN] quarkus/skills for Dev UI card patterns.

**Dependencies.** M4, P2-9 (JSON Schemas).

**Suggested commit.** `feat(engine): add Dev UI live config editor`
BODY

create_issue "P3-7: add Helm chart and operator for team-assistant mode" "phase-3,engine,native" "v1.0+" <<'BODY'
**Context.** Deploy Forvum as a team assistant in k8s.

**Scope / Deliverables.** Helm chart + a Quarkus Kubernetes-client operator with per-namespace memory isolation.

**Acceptance Criteria.**
- A Helm deploy stands up Forvum in k8s; per-namespace memory is isolated.
- [NATIVE] native container-image deployment.
- [PLUGIN] quarkus/skills for quarkus-kubernetes/-client.

**Dependencies.** P3-5, §6.4 OCI images.

**Suggested commit.** `feat(k8s): add Helm chart and operator for team-assistant mode`
BODY

create_issue "P3-8: add proxy-model compression middleware" "phase-3,engine,context-engineering,native" "v1.0+" <<'BODY'
**Context.** Materializes the CONTEXT-ENGINEERING "proxy model" Compress pattern (REQ #2).

**Scope / Deliverables.** A Sentinel-style compression layer between retrievers and the generator using a tiny local model (Ollama qwen3:1.7b) to score/prune chunks.

**Acceptance Criteria.**
- With compression on, retrieved context is pruned below a token budget while preserving answer quality (CAPR not regressed).
- [NATIVE] native parity.
- [PLUGIN] context7 for langchain4j retrieval/RAG; quarkus/skills.

**Dependencies.** M9 (Ollama), M18, semantic memory (P3-2).

**Suggested commit.** `feat(engine): add proxy-model compression middleware`
BODY

create_issue "P3-9: add SQL-driven session replay with substitution" "phase-3,engine,native" "v1.0+" <<'BODY'
**Context.** Replay any session with any substitution because the schema captures everything (extends P2-8).

**Scope / Deliverables.** engine replay + CLI.

**Acceptance Criteria.**
- A session replays with a substituted model/tool-output/memory-policy and produces a comparable trace; the substitution is recorded.
- [NATIVE] native parity.
- [PLUGIN] scaffold via the MCP.

**Dependencies.** P2-8, M5.

**Suggested commit.** `feat(app): add SQL-driven session replay with substitution`
BODY

create_issue "P3-10: add CAPR-gated evaluation harness" "phase-3,engine,observability,ci-infra,native" "v1.0+" <<'BODY'
**Context.** `forvum eval <suite>` enforces a CAPR floor and fails the release on regression — a CI quality gate on par with coverage.

**Scope / Deliverables.** eval harness module + CI integration.

**Acceptance Criteria.**
- Running a suite computes CAPR; a regression below the floor fails the eval (and the CI release gate).
- [NATIVE] native parity; the judge-model cost/latency caveat (Risk #10: judge off by default in prod, cheap local Ollama judge; measure judge-vs-human agreement, replace if < 0.7).
- [PLUGIN] context7 for langchain4j eval primitives.

**Dependencies.** M18 (CAPR events), P3-4.

**Suggested commit.** `feat(eval): add CAPR-gated evaluation harness`
BODY

# ============================================================================
# EPIC-DR — OPEN DESIGN ROUNDS
# ============================================================================

create_issue "DR-6a: close Group 6a (threat model + tool-execution filters)" "design-round,security,context-engineering" "Design Rounds" <<'BODY'
**Context.** docs/design-rounds/group-6a-tool-filters.md is opened on main with an inventory + 8 pre-committed constraints + 6 open design points but an empty decisions log. It is the highest-leverage round — it unblocks five downstream items and creates §9.

**Scope / Deliverables.** Deliberate the 6 open points; write the Decisions log; author §9.1 Threat Model (STRIDE by surface, everything touching ToolExecutor) + §9.2 Tool-Execution Filters (OutputFilter contract: hook layers pre-tool-call / pre-channel-emit / pre-memory-write, policy shape, trip outcome block-vs-redact-vs-FallbackReasons.FILTERED, PermissionScope composition; WorkspaceRoot contract for fs tools; ShellAllowlist contract; prompt-injection structural defense; per-channel security UX), inserted between §8 and §10. Honor the 8 pre-committed constraints.

**Files.** docs/design-rounds/group-6a-tool-filters.md (decisions log), docs/ULTRAPLAN.md (new §9.1/§9.2; resolve §10's "see §9 once it lands" forward-reference; upgrade the §1.4 governance bullet from principle to contract).

**Acceptance Criteria.**
- Decisions log complete; §9.1 + §9.2 inserted; new exception types decided; the prompt-injection-mitigation CE Guardrails pillar becomes structural; §10 forward-reference resolved.

**Dependencies.** §3.8 (done), §10 (done). Blocks: DR-4c, DR-5, DR-6b, DR-8, TEST-SEC, P2-OUTPUTGUARD.

**Suggested commit.** `docs(design-round): close Group 6a - threat model and tool-execution filters`
BODY

create_issue "DR-6b: open and close Group 6b (plugin trust + MCP server trust)" "design-round,security" "Design Rounds" <<'BODY'
**Context.** Carved out of 6a; no round file exists yet.

**Scope / Deliverables.** Create the round file; define the trust boundary for plugins/ (JVM fast-jar SPI) and configured MCP servers — capability declaration vs enforcement, sandboxing posture, what a plugin can do to prompt assembly / PermissionScope.

**Files.** docs/design-rounds/group-6b-plugin-mcp-trust.md, docs/ULTRAPLAN.md §9.3.

**Acceptance Criteria.**
- §9.3 (or a §9.1 STRIDE extension) covers plugin/MCP threat surfaces + the enforcement contract; decisions logged.

**Dependencies.** DR-6a (reuses the OutputFilter/ToolExecutor enforcement seam).

**Suggested commit.** `docs(design-round): close Group 6b - plugin and MCP server trust`
BODY

create_issue "DR-6c: open and close Group 6c (audit retention + supply chain + privacy)" "design-round,security" "Design Rounds" <<'BODY'
**Context.** Carved out of 6a; no round file yet; largely parallel to 6b.

**Scope / Deliverables.** Create the round file; retention policy for tool_invocations/provider_calls/capr_events, supply-chain posture for the native build inputs, privacy of persisted conversation + memory.

**Files.** docs/design-rounds/group-6c-audit-supplychain-privacy.md, docs/ULTRAPLAN.md §9.4 + any §4.2 retention notes.

**Acceptance Criteria.**
- §9.4 (or a dedicated subsection) authored; ties to native-first build inputs; decisions logged.

**Dependencies.** DR-6a (after); largely parallel to DR-6b.

**Suggested commit.** `docs(design-round): close Group 6c - audit retention, supply chain, privacy`
BODY

create_issue "DR-4c: close Group 4c (FallbackChain)" "design-round,core" "Design Rounds" <<'BODY'
**Context.** §4.3.5.3 is literally *TBD (Group 4c)*. Group 4b is closed (the explicit blocker); 4c benefits from DR-6a deciding whether a Filtered reason joins FallbackReasons.

**Scope / Deliverables.** Define §4.3.5.3 — the FallbackChain(primary, List<fallback>, CostBudget) shape, the FailureClass enum permits (incl. the Filtered permit handed over by 6a constraint 7), per-link costDims (the Group-4b Decision-9 short-circuit override door), and the LineageWindow interplay reserved by Group 4b.

**Files.** docs/design-rounds/group-4c-fallbackchain.md, docs/ULTRAPLAN.md §4.3.5.3.

**Acceptance Criteria.**
- §4.3.5.3 materialized (no longer *TBD*); FailureClass enum spec'd; the §4.3.2 line-477 migration path (String reason -> FailureClass) pinned to M8.

**Dependencies.** Group 4b (done); benefits from DR-6a.

**Suggested commit.** `docs(design-round): close Group 4c - FallbackChain contract`
BODY

create_issue "DR-5: open and close Group 5 (MemoryPolicy)" "design-round,core,context-engineering" "Design Rounds" <<'BODY'
**Context.** §4.3.6 is *TBD (Group 5)*; MemoryPolicy is already listed in forvum-core types and inherited at spawn.

**Scope / Deliverables.** Define §4.3.6 — the MemoryPolicy record/shape, the Write/Compress governance role, retrieval framing as <retrieved_memory> data blocks (6a point 5), the pre-memory-write OutputFilter boundary (6a point 2c), spawn inheritance alongside CostBudget/Identity.

**Files.** docs/design-rounds/group-5-memory-policy.md, docs/ULTRAPLAN.md §4.3.6.

**Acceptance Criteria.**
- §4.3.6 materialized; MemoryPolicy confirmed in the forvum-core type list; dissolves demo deferral D2's memoryPolicy gap.

**Dependencies.** DR-6a (memory-write boundary + retrieval framing); touches M5 episodic+semantic memory.

**Suggested commit.** `docs(design-round): close Group 5 - MemoryPolicy contract`
BODY

create_issue "DR-8: open and close Group 8 (Persona / AgentSpec composition)" "design-round,core" "Design Rounds" <<'BODY'
**Context.** Named as a downstream consumer in 6a; matches demo deferral D2. Last in the chain.

**Scope / Deliverables.** Formalize the AgentSpec record composing Identity, Persona, FallbackChain, CostBudget, MemoryPolicy, the allowed PermissionScope set, and the parent pointer — replacing the demo's ad-hoc shape; define the on-disk `agents/<id>.json` schema authoritatively.

**Files.** docs/design-rounds/group-8-agentspec.md, docs/ULTRAPLAN.md new §4.3.x AgentSpec subsection.

**Acceptance Criteria.**
- The AgentSpec subsection authored; demo D2 resolved permanently; the agents/<id>.json schema defined.

**Dependencies.** DR-4c, DR-5, DR-6a (needs all composed types to exist first).

**Suggested commit.** `docs(design-round): close Group 8 - Persona and AgentSpec composition`
BODY

create_issue "TEST-SEC: add security negative-test layer (the Group 7 Testing gap)" "design-round,security,ci-infra" "Design Rounds" <<'BODY'
**Context.** There is no Group-7 round file; "Group 7 Testing" is §10 (closed discipline) + the §9-gated security-test layer + per-milestone test debt.

**Scope / Deliverables.** Stand up forvum-app/src/test/java/ai/forvum/security/ negative integration tests (prompt injection -> no tool escalation; path traversal -> denied (M14); spawn-boundary identity override -> rejected (M7/M17); PermissionScope mismatch -> denied + audited (M13)), landing per-milestone alongside M3/M13/M14/M16/M17.

**Files.** forvum-app/src/test/java/ai/forvum/security/.

**Acceptance Criteria.**
- Each negative test fails the build on a regression; implements §10's already-written security-test bullet once §9 exists.
- [NATIVE] the security tests run against the native binary where the underlying milestone is native.

**Dependencies.** DR-6a (the contracts under test).

**Suggested commit.** `test: add security negative-test layer`
BODY

create_issue "BR-CLEANUP: delete stale design-round-tier1 branch and decide demo branch fate" "design-round,branch-hygiene" "Design Rounds" <<'BODY'
**Context.** design-round-tier1 is fully superseded by main (its group-4b is pre-decision; group-6a is absent) and would only cause confusion if someone branched from it. demo/conference-mvp is a throwaway vertical slice carrying deferrals D1-D8.

**Scope / Deliverables.** Delete the stale design-round-tier1 branch; decide the demo branch's fate (discard per its own "Return path", or cherry-pick learnings — unlikely to match the Tier-1 contracts).

**Files.** none (repo hygiene); referenced in forvum/CLAUDE.md's branch-model section.

**Acceptance Criteria.**
- design-round-tier1 deleted; the demo branch fate decided and recorded; the deferrals D1-D8 migrate into the relevant M-issues / design rounds when closed (D8 -> M5; D1 -> M9-M12; D2 -> DR-5/DR-8; D3 -> M6; D4 -> M5; D5 -> M2; D6 -> M8/DR-4c; D7 -> respective milestones).

**Dependencies.** none.

**Suggested commit.** `chore: delete stale design-round-tier1 branch`
BODY

# ============================================================================
# EPIC-X — CROSS-CUTTING CI / TEST / NATIVE-DISCIPLINE INFRA
# ============================================================================

create_issue "X1: enforce native-first engineering discipline gates" "ci-infra,native" "CI/Test Infra" <<'BODY'
**Context.** The native mandate's enforcement layer (§6.3).

**Scope / Deliverables.** No-runtime-reflection rule; build-time plugin discovery (@ForvumExtension + META-INF/forvum/plugin.json BuildStep); no dynamic class loading outside the JVM-only drop-in path; a vetoed-dependency CI grep (sun.misc.Unsafe, net.sf.cglib, runtime javassist.util.proxy); a custom Maven enforcer rule requiring @RegisterForReflection on all DTOs.

**Acceptance Criteria.**
- Each gate fails the PR on violation; folds into M1/M3/M20 CI.
- [NATIVE] this IS the enforcement layer of REQ #1.

**Dependencies.** M1.

**Suggested commit.** `ci: enforce native-first engineering discipline gates`
BODY

create_issue "X2: enforce concurrency discipline (pinning, synchronized ban, VT placement)" "ci-infra,native" "CI/Test Infra" <<'BODY'
**Context.** §3.8 Concurrency Discipline.

**Scope / Deliverables.** @RunOnVirtualThread placement rules; pinning detection (-Djdk.tracePinnedThreads=full + CI grep + pinning-allowlist.txt); synchronized forbidden in hot paths (CI grep over engine/channel main); a thread.is_virtual OTel attribute + a Dev UI Concurrency card.

**Acceptance Criteria.**
- Pinning/synchronized violations fail the PR; the Concurrency card renders VT/PT + pin data.

**Dependencies.** M5, M6 (the pinning posture finalized at M5).

**Suggested commit.** `ci: enforce concurrency discipline (pinning, synchronized ban, VT placement)`
BODY

create_issue "X3: establish test pyramid, coverage gates, and property tests" "ci-infra,native" "CI/Test Infra" <<'BODY'
**Context.** §10 Testing Discipline.

**Scope / Deliverables.** TDD process; test pyramid (*Test Surefire / *IT Failsafe / e2e); JaCoCo 80% line + 75% branch gate; Pitest mutation ramp (core first, 50%->70%); jqwik property tests for ModelRef.parse, AgentEvent Jackson roundtrip, CostBudget invariants, PermissionScope.fromName; flaky @Tag("live") quarantine + nightly; FakeProvider + *Fixtures.

**Acceptance Criteria.**
- The single most important §10 edit: amend "Native-mode parity - selective" to "MANDATORY" (done in the spec by the §10 author; operationalized at M20). Coverage/mutation gates fail the PR below threshold.
- [NATIVE] native parity becomes mandatory per REQ #1.

**Dependencies.** M2, M20.

**Suggested commit.** `test: establish test pyramid, coverage gates, and property tests`
BODY

create_issue "X4: add per-channel first-token latency gates" "ci-infra" "CI/Test Infra" <<'BODY'
**Context.** §10 performance gates.

**Scope / Deliverables.** p95 first-token latency: TUI <= 200 ms, Web <= 300 ms, Telegram <= 500 ms; baselined at M5/M6 with FakeProvider.

**Acceptance Criteria.**
- A channel exceeding its p95 budget fails the gate (or the section is amended with evidence it is infeasible).

**Dependencies.** M5, M6, M15, M16, M17.

**Suggested commit.** `test: add per-channel first-token latency gates`
BODY

create_issue "X5: add security-test layer and the missing §9 Security section" "ci-infra,security" "CI/Test Infra" <<'BODY'
**Context.** §10 references "§9 once it lands" but §9 does not yet exist — it is created by DR-6a.

**Scope / Deliverables.** Track the §9-creation dependency (DR-6a) and the security negative-test suite (delegated to TEST-SEC for the actual tests). This issue is the umbrella linking the §9 gap to the test layer.

**Acceptance Criteria.**
- §9 exists (via DR-6a); the security negative tests (via TEST-SEC) gate the build.

**Dependencies.** DR-6a, TEST-SEC.

**Suggested commit.** `test: add security negative-test layer`
BODY

create_issue "X6: add end-to-end verification suite (10 scenarios)" "ci-infra,native" "CI/Test Infra" <<'BODY'
**Context.** The 10 E2E scripts under forvum-app/src/test/java/ai/forvum/e2e/, landing milestone by milestone, gating CI, run on fast-jar AND native on linux-amd64 AND macos-arm64.

**Scope / Deliverables.** Scenario->milestone mapping: (1) cold install < 200 ms -> M20; (2) first-run init (forvum init scaffolds ~/.forvum/) -> M4 + an init command (no explicit milestone — fold into M1/M4 acceptance); (3) TUI golden path -> M15 (+ M5); (4) per-agent LLM selection + fallback -> M8 + M9 + M10; (5) sub-agent spawn -> M7 + M18; (6) web channel -> M16; (7) Telegram allowed/denied user -> M17; (8) cron run -> M19; (9) hot reload without restart -> M4 + M7; (10) CAPR dashboard (/q/dashboard/capr, >= 5 capr_events) -> M18 + the §3.6 CAPR endpoint (no explicit milestone — fold into M18 acceptance).

**Acceptance Criteria.**
- All 10 scenarios green on fast-jar AND native on both platforms; the suite gates CI.
- [NATIVE] explicitly dual-target (aligned with REQ #1).

**Dependencies.** M20 (native gating), the per-scenario milestones.

**Suggested commit.** `test: add end-to-end verification suite (10 scenarios)`
BODY

create_issue "X7: resolve Phase-1 milestone gaps (shell/skills/mcp-bridge/OTel/init/CAPR)" "ci-infra,engine" "CI/Test Infra" <<'BODY'
**Context.** §2.4 declares forvum-tools-shell, forvum-tools-mcp-bridge, the SkillInvokerTool skills surface, and §3.6 OTel wiring, and the E2E mapping references forvum init and the /q/dashboard/capr endpoint — none has a dedicated M1-M20 milestone. This is a real roadmap gap.

**Scope / Deliverables.** DECISION issue: fold the shell tool + skills surface + OTel baseline + mcp-bridge (flagged off, Risk #9) + forvum init + the CAPR endpoint into M13/M18/M4 acceptance, OR add micro-milestones. Recommended: shell tool + skills + OTel baseline + mcp-bridge -> M13/M18 acceptance; forvum init -> M1/M4 acceptance; the CAPR endpoint -> M18 acceptance.

**Acceptance Criteria.**
- Each gap item has a documented owning milestone (or a micro-milestone issue) and is not lost.

**Dependencies.** M13, M18, M4 (placement targets).

**Suggested commit.** `chore: resolve Phase-1 milestone gaps (shell/skills/mcp-bridge/OTel/init/CAPR)`
BODY

create_issue "X8: cross-link Critical Files to owning milestones" "ci-infra" "CI/Test Infra" <<'BODY'
**Context.** The 10 Critical Files must compile and their milestones pass; an orientation guarantee, not a behavior change.

**Scope / Deliverables.** Cross-link each Critical File to its owning milestone: AgentId->M2, ChannelProvider->M3, AgentContext->M6, AgentRegistry->M7, FallbackChatModel->M8, SupervisorGraph->M18, ConfigLoader->M4, V1__baseline.sql->M5, application.properties->M5/M20, ci.yml->M20.

**Acceptance Criteria.**
- Every Critical File is mapped to a milestone in the issue tracker; each compiles when its milestone closes.

**Dependencies.** the listed milestones.

**Suggested commit.** `docs: cross-link Critical Files to owning milestones`
BODY

echo
echo ">> Done. Created 5 epic parents + 20 Phase-1 + 23 Phase-2 + 10 Phase-3 + 8 design-round + 8 CI-infra = 74 issues."
echo ">> Review them at: https://github.com/$FORVUM_REPO/issues"
