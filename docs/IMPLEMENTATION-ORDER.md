# Forvum — Implementation Order (open backlog)

**Generated:** 2026-06-27 · **Scope:** all open issues on `eldermoraes/forvum` (39 work items: 37 actionable + 2 tracking epics).

This is a **sequencing view of the live issue tracker** — the order in which the remaining work should be
implemented. It is *not* an architectural document: `docs/ULTRAPLAN.md` remains the architectural source of
truth, and `docs/ISSUES.md` the per-step master index. The documented roadmap (Phases 1–3 / M1–M20 / v0.5 /
v1.0+) is **fully consumed and closed**; everything below is **post-roadmap** work that lives only in the
GitHub tracker plus the `codex-review.md` hardening audit.

**North star:** a Java implementation of OpenClaw with *state-of-the-art context engineering*. The ordering
serves that goal while respecting two hard realities: (1) the Hardening wave fixes **exploitable bugs in
shipped v0.5 code** (the Critical #165 — unauthenticated approval/CAPR dashboards on `0.0.0.0` that can
approve and dispatch tool calls), and (2) several north-star features have **hard dependency edges into the
identity/authz spine** (#175→#168, #189→#167/#168, #188↔#170). You cannot build parity on a cracked
foundation — so the authz spine comes first *on the merits*, not out of caution.

## Legend

- `[CRITICAL]` / `[HIGH]` — severity from the audit, where it drives ordering.
- `[blocked]` — has a hard prerequisite (named inline).
- `[new]` — issue filed 2026-06-27 as part of this planning pass.
- `▸` — independent of the authz spine; **can run in parallel** as a second stream (see *Parallel north-star fast-track*).

## TL;DR — critical path

`#160 / #179` → **`#165 → #168 → #167 → #166 → #170`** → `#169 / #172 → #173 / #176 / #177 / #178 / #171`
→ **`#175`** → `#183` (test capstone) — with the parallel fast-track **`#184 + #192 → #188 → #185 → #190 / #191 / #195 / #196 / #197`**.

---

## Picking the next issue

To answer "which issue should we do now": walk the waves top-to-bottom and pick the **first open issue
whose dependencies are satisfied**.

1. Check status: `gh issue list --repo eldermoraes/forvum --state open` (and `--state closed` to see what
   is already done).
2. Suggest the first **open** issue in wave order, skipping any `[blocked]` item until its named
   prerequisite has closed.
3. The `▸`-marked items have no dependency on the authz spine — they may be pulled forward as a parallel
   second stream if you want north-star progress while the spine lands.
4. Starting an issue = start from its acceptance/Verify test (TDD-as-process), then implement to green.

As of 2026-06-27 (nothing started), the recommended first pick is **Wave 0: #160** (unblocks the real
v0.5.0 release) and **#179** (docs reconciliation); the first critical-path item is **#165**.

---

## Wave 0 — Release truth & docs *(cheap, parallel, start now)*

1. **#160** ▸ `ci(release)`: align release pipeline with ratified #49 (tag-derived version, 4 platforms, GHCR image) — the published binary mislabels itself `0.1.0-SNAPSHOT`; **gates the first real v0.5.0 release** and the build-side of #174.
2. **#179** ▸ `docs(architecture)`: reconcile roadmap + Context-Engineering claims with runtime — the source-of-truth docs currently diverge from the code (the CE mapping still says "only M1 delivered"). Do early so planning doesn't trust stale docs. Off the critical path.

## Wave 1 — Authorization spine (Onda-1) *— critical path, strict order*

3. **#165** `[CRITICAL]` `security(app)`: authenticate & authorize the approval + CAPR dashboards and ChatSocket — establishes the **web authenticated-principal seam** every later item reuses. May start alongside #168 (distinct seams).
4. **#168** *(root)* `security(identity)`: honor AgentSpec identity fallback, **fail closed** for unresolved users — today an unresolved user becomes permissive `anonymous`. The scope-identity root #167/#166 build on.
5. **#167** `security(rbac)`: enforce AgentSpec role caps across interactive + cron turns — defines the *documented order* in which device/identity/role restrictions are intersected once.
6. **#166** `security(pairing)`: authenticate device tokens & intersect `approvedScopes` — reuses #165's principal seam; composes the intersection with #167/#168.
7. **#170** `security(channels)`: replace fail-open authz defaults with explicit opt-in — the **integration layer** that composes #166 + #167 + #168; **must come after them**.

## Wave 2 — Remaining hardening *(after the spine; several parallel)*

8. **#169** `[HIGH]` `feat(budget)`: enforce cost & tool budgets at runtime — parsed but never enforced; **unblocks #177**. (Finish the deferred `costBudget` parsing.)
9. **#172** `security(output-guard)`: sanitize `ErrorEvent` before channel delivery — **prerequisite for #173**; keep the error contract compatible with #165.
10. **#173** `security(persistence)`: owner-only (0700/0600) permissions for runtime state — consumes #172's sanitized error contract.
11. **#176** `fix(context)`: bound compression-failure fallbacks (don't reinsert raw text) — fixes the **Compress** pillar's fail-open path; **#175 consumes this contract**.
12. **#177** `fix(spawn)`: retire ephemeral workers & release `@AgentScoped` state — fixes an **Isolate** leak; depends on #169, shares a primitive with #178.
13. **#178** `fix(config)`: make agent hot-reload atomic & capability-safe — relates to #167/#168 (reliable role/tool revocation).
14. **#171** `security(plugins)`: strict artifact checksums + owner-only plugin install — build-side later covered by #174.

## Wave 3 — Memory loop *(foundational CE; unblocks parity)*

15. **#175** `[HIGH]` `feat(memory)`: integrate local semantic + episodic memory into normal turns — today `recordFact` has no production caller and `MemorySelector` only wires Qdrant, so a default install **never** writes or retrieves long-term memory. Realizes the three-tier Write/Select map. Depends on #168, consumes #176; **hard-unblocks #193 and #196, underpins #189**.

## Wave 4 — Security/CI test capstone *(once the controls exist)*

16. **#174** `ci(security)`: supply-chain / secret / SAST / SBOM / provenance gates — follows #160; covers the build-side of #171.
17. **#180** `test(coverage)`: remove substantive exclusions, restore declared gates — test memory/compression against #175/#176 contracts.
18. **#181** `test(live)`: schedule provider / browser / sandbox live tests in CI.
19. **#182** `test(mutation)`: bounded mutation-testing signal + ratchet — depends on #180.
20. **#183** `[HIGH]` `test(security)`: behavioral regression coverage for the declared controls — **the capstone**: closes only when #165–#173, #175, #178 all land. Write incrementally as each control ships; closes last.

## Wave 5 — OpenClaw parity foundations *(surface built capability + SPI seams; ▸ parallelizable with Waves 1–2)*

21. **#184** ▸ `[HIGH]` `feat(tools)`: usable default tool belt + `forvum tools` discoverability — **cheapest, highest impact**: shell/web/browser/mcp tools already exist but the default belt is `fs.*`-only, so they silently no-op. #192 and #194 compose on it.
22. **#192** ▸ `feat(tools-web)`: pluggable web-search backend SPI + keyless default (DuckDuckGo/SearXNG) — makes the `web.search` that #184 belts work out of the box; removes the Brave-key wall.
23. **#188** `[HIGH]` `feat(tools)`: outbound message-send tool + `ChannelSender` SPI — **SPI foundation**; re-points `CronDeliverySink` to a live channel; closes the "no outbound channel-send API" deferral. **After #170** (don't bake in insecure channel assumptions).
24. **#185** ▸ `[HIGH]` `feat(tools-multimodal)`: image + PDF analysis tools — a **real capability gap** (the input path is text-only today); new `MEDIA_ANALYZE` scope.

## Wave 6 — Context-engineering differentiator arc *(the north star's SOTA edge)*

25. **#190** ▸ `feat(tools)`: structured planning `update_plan` tool — **Write** pillar; strongest single driver of multi-step coherence. Plan in the session scratchpad with a compaction `block_type`.
26. **#191** ▸ `[HIGH]` `feat(engine)`: `SkillInvokerTool` skill-invocation surface — **Select** (procedural); unlocks the skills axis. (Phase-1 X7 #73 leftover.)
27. **#195** ▸ `[new]` `feat(capr)`: replace the always-`passed` turn verdict with a real per-turn judge — **closes the CAPR measurement loop** the whole CE paradigm is built around (`Agent.java:117` records a constant verdict today). Off the critical path, off by default.
28. **#196** ▸ `[new]` `[blocked: #175]` `feat(memory)`: upgrade Select from single-shot retrieval to iterative/agentic RAG — the PT doc's headline ask ("Agentic RAG dinâmico") + OpenClaw's `active-memory` pattern; bounded retrieve→evaluate→decompose as an Isolate-respecting sub-agent.
29. **#197** ▸ `[new]` `feat(context)`: mid-turn context pruning for tool results & images — the one **Compress** mechanism OpenClaw has (`context-pruning/pruner.ts`) and Forvum lacks; distinct from #176 and the between-turn compactor.

## Wave 7 — Remaining parity + polish *(breadth / delight / reach)*

30. **#189** `feat(tools)`: session + sub-agent introspection tools (`agents.list` / `sessions.*`) — depends on #167/#168.
31. **#193** `[blocked: #175]` `feat(tools)`: explicit `memory.save` / `memory.recall` tool — hard dep on #175; do not start before its provider contract merges.
32. **#186** ▸ `feat(tools)`: text-to-speech (`tts.speak`) tool — reuses the voice-channel Piper subprocess pattern.
33. **#187** ▸ `feat(tools)`: media-generation tools (image/video/music) via a new `GenerationProvider` SPI.
34. **#194** `[HIGH]` `feat(app)`: interactive first-run onboarding wizard (`forvum onboard`) — composes #184 + #192; **pullable forward** right after Wave 5 if adoption is a priority.

## Wave 8 — Deferred / blocked *(do when unblocked)*

35. **#75** `[blocked: upstream]` `chore(deps)`: bump `quarkus-langchain4j` 1.11.0.CR2 → 1.11.0 GA — when upstream ships.
36. **#138** *(deferred, arch-touching)* Approval R2: exact checkpoint/resume of a parked turn across restart — reverses the M18 R6 no-checkpointer stance; do deliberately later.
37. **#125** `[blocked: native crypto]` `feat(channel-matrix)`: E2EE room support — needs a native-clean JVM crypto path.

## Tracking epics *(not work items)*

- **#2** `[epic]` Phase 2 v0.5 parity with OpenClaw — stays open as a tracker.
- **#3** `[epic]` Phase 3 v1.0+ differentiators — tracker for the differentiator arc (incl. #195/#196/#197) and the deferred #125/#138.

---

## Parallel north-star fast-track

If you want visible progress toward OpenClaw parity + SOTA context engineering **while the security spine
lands**, the `▸`-marked items have no dependency on the authz spine and can run as a second stream from day
one: **#160, #179, #184, #192, #185, #190, #191, #195, #197** (and #186/#187 later). The items that *must*
wait for Wave 1 are **#188** (after #170), **#189** (after #167/#168), and **#175 → #193 / #196**.

## How this list was derived

Synthesized from: a full `gh` census of the 36 open issues (labels, milestones, dependency edges in issue
bodies); the in-repo roadmap docs (`docs/ULTRAPLAN.md` §7, `docs/ISSUES.md`, `docs/design-rounds/`); the
`codex-review.md` hardening audit; and a feature-parity + context-engineering comparison against the
`openclaw/` reference repo and `docs/CONTEXT-ENGINEERING.md` / `-MAPPING.md`. Code claims behind #195/#196/#197
were verified against `forvum-engine` source (`Agent.java:117`, `CaprRecorder`, `ModelHealthReader`,
`SupervisorGraph.retrieveAndFrame`).
