# Codex Review Findings — GitHub Issue Design

## Goal

Create one actionable GitHub issue for each of the 19 findings in `codex-review.md`, without changing
`docs/ISSUES.md`. Every issue must be understandable and implementable without reopening the audit.

## Tracker conventions

- Write titles and bodies in English.
- Use Conventional Commits-style titles with a subsystem scope, following recent post-roadmap issues.
- Let GitHub assign issue numbers; do not introduce an `AUDIT-*` logical numbering scheme.
- Use only labels that already exist in `eldermoraes/forvum`.
- Leave the milestone unset because these are post-roadmap hardening issues, matching current issues
  such as #160.
- Create exactly 19 issues, preserving the one-finding-to-one-issue mapping from `codex-review.md`.

## Issue body contract

Each issue will contain:

1. **Context** — the intended contract and why the finding matters.
2. **Current behavior** — the observed implementation, with repository paths and relevant symbols.
3. **Impact** — security, reliability, cost, or Context Engineering consequences.
4. **Required scope** — the behavior that must change, without prescribing an unnecessarily narrow
   implementation.
5. **Acceptance criteria** — objective, checkable completion conditions.
6. **Verification** — specific unit, integration, E2E, CI, JVM, and native checks where applicable.
7. **References and dependencies** — design documents, related issues, and ordering constraints.

## Issue set

| Finding | Proposed title prefix | Primary labels |
|---|---|---|
| Unauthenticated approval/CAPR dashboards | `security(app)` | `security`, `engine`, `channel` |
| Device token and approved scopes not enforced | `security(pairing)` | `security`, `engine`, `channel` |
| `AgentSpec.roles` cap ignored | `security(rbac)` | `security`, `engine`, `core` |
| `AgentSpec.identityId` fallback ignored | `security(identity)` | `security`, `engine`, `core` |
| Cost and tool budgets not enforced | `feat(budget)` | `engine`, `core`, `context-engineering` |
| Fail-open channel/pairing/RBAC defaults | `security(channels)` | `security`, `engine`, `channel` |
| Plugin checksum and filesystem controls missing | `security(plugins)` | `security`, `engine`, `plugin-tooling` |
| Error responses bypass `OutputGuard` | `security(output-guard)` | `security`, `engine`, `channel` |
| State directory permissions depend on umask | `security(persistence)` | `security`, `engine`, `persistence` |
| Missing CI supply-chain security gates | `ci(security)` | `security`, `ci-infra` |
| Local semantic/episodic memory absent from turns | `feat(memory)` | `context-engineering`, `engine`, `persistence` |
| Compression falls back to unbounded raw context | `fix(context)` | `context-engineering`, `engine` |
| Spawned workers are never retired | `fix(spawn)` | `context-engineering`, `engine` |
| Hot reload can retain stale agent capabilities | `fix(config)` | `security`, `engine`, `context-engineering` |
| Architecture/status documentation drift | `docs(architecture)` | `context-engineering` |
| Coverage gates weakened by exclusions/overrides | `test(coverage)` | `ci-infra`, `engine` |
| Live provider/browser/sandbox tests absent from CI | `test(live)` | `ci-infra`, `provider`, `tool` |
| Mutation testing promised but not configured | `test(mutation)` | `ci-infra` |
| Critical security contracts lack behavioral tests | `test(security)` | `security`, `ci-infra` |

## Creation and verification

- Create issues sequentially so the returned GitHub numbers and URLs can be captured reliably.
- Stop on any creation error; do not silently skip or duplicate an issue.
- After creation, query every returned issue and verify title, labels, open state, empty milestone, and
  non-empty acceptance/verification sections.
- Check the tracker for duplicate titles before creating each issue.
- Return the final numbered list of issue links to the user.

## Non-goals

- Do not edit `docs/ISSUES.md` or its GitHub issue-number map.
- Do not modify application or test code.
- Do not assign owners, projects, due dates, or milestones.
- Do not close, reopen, or alter existing issues.
