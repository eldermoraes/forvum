# Session handoff — 2026-04-22 afternoon

**Branch:** demo/conference-mvp (backed up as origin/demo/conference-mvp)
**Status:** Blocos 1 + 2 completos e commitados. Usuário vai 
testar interativamente e decidir próximos passos.

## Current state

- main @ HEAD: squash commit of Tier 1 design work (Groups 1, 
  2, 3, 4a, 4b complete; Groups 4c and 5 remain as TBD 
  placeholders in §4.3.5.3 and §4.3.6 of ULTRAPLAN.md).
- demo/conference-mvp @ HEAD: MVP vertical slice implemented 
  across 12 commits. Compiles, smoke tests passed per previous 
  session. Not yet interactively validated by user.

## Known facts from smoke tests (automated only)

- Quarkus boots cleanly (187ms)
- Ollama responds via raw dev.langchain4j:langchain4j-ollama 
  (Quarkiverse extension was incompatible — see D8 in deferrals)
- Multi-turn memory works per smoke test (BANANA recall test)
- EOF exit is clean

## Known deferrals (recorded in docs/design-rounds/demo-mvp-deferrals.md)

D1 — ModelProvider SPI collapsed to ChatModelFactory switch
D2 — AgentSpec defined ad-hoc without full §4.3 specification
D3 — Agent runtime without @AgentScoped CDI context
D4 — No persistence, no Flyway, no provider_calls ledger
D5 — No AgentEvent hierarchy, no structured event stream
D6 — No FallbackChain, no CostBudget, no BudgetMeter enforcement
D7 — No CAPR judging, no OpenTelemetry, no DevUI
D8 — Raw langchain4j instead of Quarkiverse extensions

## What the user will decide next

User is about to test interactively and will report back with 
one of these outcomes:

1. "Everything works" → proceed to Bloco 3 (polish + README)
2. "Found issues" → diagnose + fix before Bloco 3
3. "Something needs to change" → user-driven adjustments

Do NOT assume outcome. Wait for user input.

## Bloco 3 (if approved) overview

- README.md at repo root (tagline, status, quick demo, 
  architecture, roadmap, contributing, license)
- Optional polish: banner ASCII, demo script, CONTRIBUTING.md, 
  screenshot — user decides subset
- Each section/item = separate commit
- No push until user explicitly requests

## Files to reference when resuming

- docs/ULTRAPLAN.md (full architectural spec, §4.3 Tier 1 contracts)
- docs/design-rounds/group-4b.md (CostBudget design round record)
- docs/design-rounds/demo-mvp-deferrals.md (8 deferrals)
- agents/demo.json (single agent config)
- CLAUDE.md (workflow conventions)

## Conventions to carry over

- Manual approve mode on all code operations
- Commit per logical unit (no batching of unrelated changes)
- Report each commit before next component
- No push until user explicitly requests
- English-only for code, docs, commits, issues (per 
  feedback_project_artifacts_language.md memory)
