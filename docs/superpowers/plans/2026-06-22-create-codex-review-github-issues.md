# Codex Review GitHub Issues Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create and verify 19 actionable GitHub issues, one for each finding in `codex-review.md`.

**Architecture:** Treat `codex-review.md` as the finding inventory and the approved design as the tracker contract. Prepare each issue independently, preflight its title against the tracker, create it sequentially with `gh`, capture the assigned number/URL, and read it back before continuing.

**Tech Stack:** GitHub Issues, GitHub CLI (`gh`), Markdown.

## Global Constraints

- Repository: `eldermoraes/forvum`.
- Titles and bodies are English and follow the approved Conventional Commits-style naming.
- Use only existing repository labels.
- Leave milestone, assignee, project, and due date unset.
- Do not edit `docs/ISSUES.md` or application/test code.
- Preserve the exact one-finding-to-one-issue mapping; create exactly 19 issues.
- Stop at the first creation or verification failure.

---

### Task 1: Preflight tracker state

**Files:**
- Read: `codex-review.md`
- Read: `docs/superpowers/specs/2026-06-22-codex-review-github-issues-design.md`

**Interfaces:**
- Consumes: 19 reviewed findings and the approved tracker contract.
- Produces: verified label names and a duplicate-free title set.

- [ ] **Step 1: Fetch all repository labels**

Run: `gh label list --repo eldermoraes/forvum --limit 100`

Expected: every label named in the approved design exists.

- [ ] **Step 2: Fetch current open issue titles**

Run: `gh issue list --repo eldermoraes/forvum --state open --limit 200 --json number,title,url`

Expected: none of the 19 proposed titles already exists or describes the same unresolved scope.

- [ ] **Step 3: Confirm local scope**

Run: `git status --short`

Expected: no application/test source is modified; `codex-review.md` may remain untracked.

### Task 2: Prepare the 19 issue payloads

**Files:**
- Read: `codex-review.md`
- Create: temporary Markdown payloads under `/tmp/forvum-codex-review-issues/`

**Interfaces:**
- Consumes: one finding per payload.
- Produces: 19 complete bodies with Context, Current Behavior, Impact, Required Scope, Acceptance Criteria, Verification, and References/Dependencies.

- [ ] **Step 1: Prepare titles and labels**

Use the exact 19 mappings from the approved design. Do not add a custom `AUDIT-*` prefix.

- [ ] **Step 2: Write complete issue bodies**

Every body must cite relevant repository paths, state observable current behavior, define a bounded remediation scope, include objective acceptance criteria, and name the required unit/integration/E2E/CI/native verification.

- [ ] **Step 3: Validate payload completeness**

Run a local check that every payload contains all seven required headings and is non-empty.

Expected: 19 valid payloads and no placeholders such as `TBD`, `TODO`, or `FIXME`.

### Task 3: Create issues sequentially

**Files:**
- Read: temporary payloads under `/tmp/forvum-codex-review-issues/`

**Interfaces:**
- Consumes: validated title, label set, and Markdown body for each finding.
- Produces: one open GitHub issue URL and number per finding.

- [ ] **Step 1: Recheck each title immediately before creation**

Run: `gh issue list --repo eldermoraes/forvum --state open --search '<exact title> in:title' --json number,title,url`

Expected: no matching issue.

- [ ] **Step 2: Create one issue**

Run: `gh issue create --repo eldermoraes/forvum --title '<exact title>' --body-file '<payload>' --label '<label-1>,<label-2>'`

Expected: one `https://github.com/eldermoraes/forvum/issues/<number>` URL.

- [ ] **Step 3: Repeat Steps 1–2 for all 19 payloads**

Expected: 19 distinct URLs. Stop immediately if any command fails.

### Task 4: Verify the created tracker state

**Files:**
- Read: GitHub issue metadata and bodies.

**Interfaces:**
- Consumes: the 19 captured issue numbers.
- Produces: verified issue list for user handoff.

- [ ] **Step 1: Read back all created issues**

Run `gh issue view` for every captured number with `--json number,title,body,state,labels,milestone,url`.

Expected: all issues are `OPEN`, have the intended title and labels, have `milestone: null`, and contain all seven required sections.

- [ ] **Step 2: Confirm count and uniqueness**

Expected: exactly 19 distinct issue numbers and URLs, with no duplicate title.

- [ ] **Step 3: Confirm repository files were not changed by issue creation**

Run: `git status --short`

Expected: no changes to `docs/ISSUES.md` or application/test code.

- [ ] **Step 4: Report the created issues**

Return a numbered list containing each GitHub issue number, title, and link.
