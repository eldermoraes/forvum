# M19 — File-driven Cron Scheduler Implementation Plan (#24)

> Sub-skills used: Quarkus Dev MCP `quarkus_searchDocs` (programmatic Scheduler API — confirmed
> `io.quarkus:quarkus-scheduler` 3.33.1) before coding (CLAUDE.md §7). Engine tests run via Surefire
> (headless library, §4). Branched off `origin/main` (`7e1c755`, M18 merged via PR #107).

**Goal.** Background agent turns from `~/.forvum/crons/*.json` (§7.1 M19). A `CronScheduler` registers
jobs **programmatically** from cron specs, each firing a full M18 turn; new/changed/removed cron files
reload **without restart** (reuse the M4 `WatchService` → `ConfigurationChangedEvent` plumbing).

**Issue map.** M19 → `#24`. Re-confirm live at PR time.

## Maintainer decision (2026-06-06)
- **The cron carries its own model.** `CronSpec` includes `primaryModel` (a `ModelRef`); the cron turn
  uses the cron's model (distinct from the target agent's persona) — aligns with e2e X8's `primary` in
  the cron JSON. Implemented via `LlmSelector.resolve(ModelRef, agentId, sessionId)` + a model-override
  overload on `Agent.respond`. FallbackChain stays engine-local single-link (DR-4c not ratified); per-cron
  multi-link fallback is deferred.

## Key facts (verified on main)
- **`CronReader` already exists** (`engine/config/CronReader extends JsonDirectoryReader`) — reads raw
  JSON per id from `$FORVUM_HOME/crons/`. M19 adds a `CronSpec` parser on top (mirror `AgentSpecReader`).
- **`ConfigWatcher` already watches `crons/`** and fires `ConfigurationChangedEvent(Path relPath, ChangeType)`
  (CREATED/MODIFIED/DELETED, path relative to `$FORVUM_HOME`). `CronScheduler` `@Observes` it and filters
  `crons/` to reschedule/unschedule live.
- **Programmatic scheduling needs `quarkus.scheduler.start-mode=forced`** (no `@Scheduled` methods exist)
  — set in `forvum-engine/.../META-INF/microprofile-config.properties` (app-wide, [M17]).
- Scheduler API: `@Inject io.quarkus.scheduler.Scheduler` → `scheduler.newJob(id).setCron(expr)
  .setConcurrentExecution(SKIP).setTask(ctx -> …).schedule()` / `unscheduleJob(id)`.

## Files
```
forvum-engine/pom.xml                                   (+ quarkus-scheduler)  [done]
forvum-engine/.../META-INF/microprofile-config.properties (start-mode=forced)  [done]
forvum-engine/.../cron/CronSpec.java                    (record {id, cron, agentId, primaryModel, prompt})
forvum-engine/.../cron/CronSpecReader.java              (parse CronReader raw JSON → CronSpec)
forvum-engine/.../cron/CronScheduler.java               (@ApplicationScoped: schedule + hot-reload + fire)
forvum-engine/.../routing/LlmSelector.java              (+ resolve(ModelRef, agentId, sessionId))
forvum-engine/.../agent/Agent.java                      (+ respond(sessionId, userText, ChatModel override))
forvum-engine/src/test/.../cron/                        (TDD)
```

## Design
- **CronScheduler** (`@ApplicationScoped`): `@Observes StartupEvent` → load+schedule all crons;
  `@Observes ConfigurationChangedEvent` → on a `crons/<id>.json` CREATED/MODIFIED, re-read + reschedule
  that id (`unscheduleJob` then `newJob`); on DELETED, `unscheduleJob`. Graceful no-op when `$FORVUM_HOME`
  / `crons/` is absent (native smoke runs with no home).
- **Fire** (`setTask`): run on a **virtual thread** (`@RunOnVirtualThread` semantics — the turn is
  blocking); bind `CurrentAgent.CURRENT_AGENT = spec.agentId`; ensure a stable per-cron session id;
  resolve the cron model `llmSelector.resolve(spec.primaryModel, agentId, sessionId)`; run
  `agent.respond(sessionId, spec.prompt, cronModel)` → writes `messages` + `provider_calls` +
  `capr_events` (the M18 turn). **`ConcurrentExecution.SKIP`** suppresses overlapping runs of the same id.
- **Per-cron model:** `LlmSelector.select(persona, …)` is refactored to delegate to
  `resolve(ModelRef, agentId, sessionId)`; the cron calls `resolve` directly with `spec.primaryModel`.

## TDD / Verify
- `CronSpecReader` parses a crons/*.json → CronSpec (+ validation). [unit]
- `LlmSelector.resolve` + `Agent.respond(…, override)` use the given model (asserted via FakeProvider). [unit/IT]
- `CronScheduler` schedules a job per spec, reschedules on a `crons/` change event, unschedules on delete,
  and a fired cron runs a turn writing the 3 tables (FakeProvider; assert via counts). [IT]
- **Verify (the M19 contract):** a cron firing every minute pinned to Ollama triggers a turn and writes
  `messages`/`provider_calls`/`capr_events`; adding a cron file reloads without restart. Local GraalVM 25
  native build green. 6-dim adversarial review.

## Risks
- **Native + scheduler:** quarkus-scheduler is native-supported; the local native build is the gate.
- **macOS WatchService latency** (Risk #7): behavioral hot-reload tests need generous timeouts; keep the
  deterministic reschedule logic in plain unit tests where possible.
- **Shared-home test pollution** ([M7]): cron jobs register in the app-wide Scheduler; scope assertions by
  the cron ids a test writes + unschedule/clean up.
