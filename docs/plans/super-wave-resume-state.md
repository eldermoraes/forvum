# Super-wave checkpoint — resume state

**Authoritative resume point: issue #130** (full done/remaining inventory). This file is the
repo-visible pointer committed at checkpoint time (2026-06-10, main `162e834`).

## Snapshot

- **Merged this wave:** #123 (deps CR2/3.33.2/LangChain4j 1.16.1, closed #102), #127 (design rounds
  DR-4c/6b/6c/8 + ShellAllowlist §9.2.5 + BR-CLEANUP, closed #60 #61 #62 #64 #66), #126
  (ChannelLauncher required-keys seam).
- **Open, CI 5/5 green, adversarially reviewed + fixed, ready to merge:** PR #128 (Slack Socket
  Mode + Discord op-6 RESUME), PR #129 (Matrix /sync). Merge #128 first, rebase #129.
- **Built, pushed, awaiting review + PR:** branch `feat/channel-signal` (73/73 tests, local native
  build green).
- **Not started:** WhatsApp channel (closes #41), then the remaining clusters per issue #130
  (MCP/Copilot/skill → turn-path → approval queue → native tools → wizard → graph cluster →
  data/routing → ops → gates/harnesses → release v0.5.0 + epic closures).

Process and ratified decisions are recorded in the wave plan (issue #130 §Resume); per-package
discipline is unchanged: TDD, Quarkus Dev MCP for new modules, local native build per cluster,
6-dim adversarial review pre-merge, doc-sync, stacked-safe merges.
