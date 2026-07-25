# Implementation lessons — Testing & CI

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **Make test fixtures exercise the absent / created-later state, not just the happy pre-populated one.**
  M4's `/code-review` caught a real gap (subfolders created after boot were never watched) that the
  tests masked because the fixture pre-created every directory. Run `/code-review` (high or `ultra`)
  before a milestone merge, and keep Javadoc/claims aligned with actual behavior. [M4]

- **Harness note:** Maven/Quarkus console output carries ANSI/control chars that can break the agent
  display — run Quarkus-bearing builds/tests with `-B -Dstyle.color=never` (and/or in the background),
  then read the clean Surefire `*.txt` reports rather than the raw Maven log. [M4]

- **A shared static `@TestProfile` HOME shares the SQLite DB AND `@ApplicationScoped` state across
  same-profile `@QuarkusTest` classes** (one app instance), and `@Transactional` test methods commit.
  Scope persistence assertions by the keys/sessions the test wrote, and clean up files a test writes
  into the shared home — a `spawn` registry-corruption bug surfaced live as a sibling test seeing
  `main`'s tool belt clobbered. [M7]

- **Run a deep, adversarially-verified review before a milestone merge** (dimensions → find →
  refute-by-default verify). On M7 it flipped two test findings where the test was actually the stronger
  version, and caught a real `spawn` corruption + a non-atomic turn before they shipped. [M7]

- **`-Djdk.tracePinnedThreads` was REMOVED in JDK 24+ — a stderr `Thread pinned` CI grep is a vacuous
  always-pass gate on JDK 25.** The flag is silently inert; runtime pinning detection moved to the JFR
  `jdk.VirtualThreadPinned` event (`quarkus-junit-virtual-threads` `@ShouldNotPin`), and JEP 491 (JDK 24)
  also stopped `synchronized` from pinning (leaving only native-code, e.g. SQLite JNI, pins). The enforced
  concurrency gate is now the static `synchronized`/Mutiny grep (`.github/concurrency-guardrails.sh` +
  repo-root `pinning-allowlist.txt`/`vt-allowlist.txt`); the JFR runtime gate is a tracked follow-up. [M16]

- **MEASURE the per-module JaCoCo baseline BEFORE setting the gate, then exclude only structurally-uncoverable
  code — never lower the global threshold.** Wire `jacoco-maven-plugin` (0.8.15 is the first line reading Java
  25 / class-file 69 bytecode) ONCE in the parent `<build><plugins>` — `prepare-agent` (Surefire only; its
  `argLine` is picked up automatically, do NOT also count the native-profile Failsafe `*IT`), `report`,
  `check` (BUNDLE rule 80% LINE / 75% BRANCH via `${jacoco.line.minimum}`/`${jacoco.branch.minimum}` props) —
  inherited per module, gating each module's own coverage (stronger than a reactor aggregate a weak module
  hides inside). Measure first: `verify -Djacoco.line.minimum=0.00 -Djacoco.branch.minimum=0.00`, then read
  per-module `target/site/jacoco/jacoco.csv` (cols: BRANCH_MISSED=$6 BRANCH_COVERED=$7 LINE_MISSED=$8
  LINE_COVERED=$9 — NOT 4/5/6/7). A child re-declares the `jacoco-check` execution by the SAME id to add
  `<excludes>` (JaCoCo class-exclude form `ai/forvum/pkg/Foo*.class`, `/`-separated, `.class` suffix) or a
  relaxed `<minimum>`; the execution `<configuration>` REPLACES the parent's rules (no deep-merge), so copy the
  whole `<rules>` block. Justified excludes only: `forvum-sdk` logic-free `Abstract*Provider` sealed-set bridges
  (→ 0 lines, passes vacuously), `forvum-engine` native-metadata holders + pure Panache `*Entity` classes (→
  80.31/76.68, clears global). Where there is NO structural class to exclude (a real gap covered only by the
  excluded Failsafe ITs or the booted app), set a JUSTIFIED per-module override and record the gap in a pom
  comment, never weaken the global gate: `forvum-channel-telegram` LINE→0.72 (IT-only CDI-lifecycle/`@RestClient`
  boot lines), `forvum-app` BRANCH→0.70 (picocli command error branches the native ITs cover). `pom`-packaged
  modules (parent, `forvum-bom`) have no exec file → `check` skips gracefully, no carve-out. The four
  §10-mandated property tests already existed — confirm before writing. Pitest stays signal-only (documented,
  not a failing gate). [X3]

- **Author span-less e2e scenarios against existing machinery + observable DB side-effects.** OTel spans do not
  exist in v0.1, so an e2e asserts the ledger rows the turn wrote, not a span. The five X6 scenarios reuse the
  in-process `FakeModelProvider` (no live inference, per the perf-gate convention) and the production seams:
  spawn → `AgentRegistry.spawn` + a per-child `capr_events` row; cron → seed a `0/1 * * * * ?` `tick.json` and
  poll for the `cron:<id>` ledger rows (the real `Scheduler` fires in a `@QuarkusTest` because `CommandMode`
  sees no one-shot arg); hot-reload → fire the same `ConfigurationChangedEvent` the `WatchService` would (the
  macOS poll latency makes a real watcher non-deterministic) and assert the next turn re-reads the edited spec;
  Telegram allow/deny → drive the real `UpdateProcessor` over an in-test recording `TelegramBotApi` impl. A
  package-private production constant (`UpdateProcessor.REFUSAL_MESSAGE`) is asserted by its observable content,
  not widened to `public` for a test. [X6]

- **A "milestone gap" can be a docs-ownership gap, not a missing milestone — fold, don't multiply
  milestones.** X7's six items (shell tool, `SkillInvokerTool` skills surface, `forvum-tools-mcp-bridge`
  baseline, §3.6 OTel baseline, `forvum init`, the `/q/dashboard/capr` endpoint) each rode an existing
  milestone's SPI/surface (skills + shell + mcp-bridge on M13's `ToolProvider.tools()`; OTel + CAPR on
  M18's turn/graph spans; the `init` command on M20's picocli command-mode + the M4 `~/.forvum/` layout
  it scaffolds — NOT M1, which is reactor/pom/wrapper bootstrap only), so the fix was to fold them into
  M4/M13/M18/M20 *acceptance* and delete the "real roadmap gap" framing — no micro-milestones, no code.
  When a docs item reads "no Phase-1 milestone", check whether the surface already exists before scheduling new work.
  Unblocks downstream parity issues that depend on the owned baseline (P2-7/#32, P2-13/#38, P2-15/#40). [X7]

- **A wall-clock assertion or a fixed poll window in a `@QuarkusTest` IT is fragile on a cold/loaded CI
  runner — assert SEMANTICS, and warm the persistence in `@BeforeEach`.** The P2-14 `ApprovalServiceIT`
  passed locally + on ubuntu CI but failed BOTH macos-14 cells (JVM + native): the FIRST DB op pays
  Quarkus's lazy datasource/Hibernate/Agroal init, observed at ~5 s (JVM cell) and ~13 s (native cell, the
  box is loaded right after the native-image build) — so a `< 1000 ms` "denies immediately" assertion and a
  2 s `awaitPendingId` poll both blew their budget on the cold first op. Fixes: (1) assert the OUTCOME that
  distinguishes the paths (`status == "rejected"` for the immediate-deny path vs the timeout path's
  `"timed_out"`) instead of timing; (2) a `@BeforeEach` warm-up (`service.listPending()`) pays the
  one-time cold-start OUTSIDE every timed region, so the async approve/timeout arms run warm regardless of
  JUnit method order; (3) keep the configured timeout + poll window generous and the poll window under the
  timeout. macos-14 is the slow cell that catches this — ubuntu green is not enough confidence. [P2-14]

- **Scheduling the live layer is a selection-contract sweep + a skip-guard, not just a new cron workflow.**
  #181 added `.github/workflows/nightly-live.yml` (cron 03:00 UTC + `workflow_dispatch`, one job per
  integration). The load-bearing details: (1) the `live` exclusion MUST be the Surefire
  `<properties><excludedGroups>live</excludedGroups></properties>` USER property in every owning module — a
  plugin-XML `<configuration>` value is CLI-un-overridable (Maven ignores `-D` for an XML-set parameter), so
  `forvum-tools-sandbox` (no exclusion → an accidental per-PR busybox run) and `forvum-provider-memory-qdrant`
  (plugin-XML form) both needed the fix; telegram was already clean. (2) Every invocation carries
  `-DexcludedGroups=none` (NOT `""` — a blank Failsafe `<excludedGroups>` discovers ZERO tests) AND
  `-DfailIfNoTests=true` so selection drift goes red. (3) A cloud-provider job gates on
  `if: needs.preflight.outputs.<p> == 'true'` — a job-level `if:` CANNOT read `secrets`, so a preflight job
  probes presence (never the value) into outputs; an absent secret renders the job `skipped` (green-by-skip,
  the mandated merge state with zero secrets). (4) A skip-guard step parses `TEST-*.xml` and FAILS the job if
  `skipped != 0` — on the guaranteed-env nightly runner a self-skip means breakage, not "no runtime"; prove
  it red-checks both directions. (5) An ownership gate (`.github/live-ownership.sh`) greps `@Tag("live")`
  ANNOTATION lines (`^[[:space:]]*@Tag`, never a javadoc `* {@code @Tag(...)}` mention) and asserts each
  class is named in an owning workflow. (6) Workflow `run:` bash is shellchecked by actionlint under
  `-eo pipefail`: avoid `grep | head` (SIGPIPE → non-zero) — use `grep -om1`; and an empty bash array under
  `set -u` (`"${arr[@]}"`) errors on macOS bash 3.2 — guard with `[ "${#arr[@]}" -gt 0 ]`. [#181]

- **A parallel build-agent Workflow MUST NOT pass `-Djacoco.skip` — the integrator pays the coverage gate.**
  PR-6's Wave-2 build fanned out one worktree-isolated agent per module; the shell stream's `-am` build was
  told to skip jacoco for speed, so shell + the co-built filesystem shipped BELOW the branch gate (0.738)
  and the integrator's full `verify` failed (the [P2-OUTPUTGUARD] trap, now at fan-out scale). The other
  agents ran `verify` and passed. LESSON: build agents run `verify` (jacoco on); the orchestrator
  pre-checks per-module coverage from each worktree's `target/site/jacoco/jacoco.csv` BUT must compute the
  GATED ratio (the per-module `<excludes>` for live-transport adapters), not the raw class total — a raw
  sum over the excluded `@RestClient`/`@WebSocketClient`/`Jdk*Http` classes false-alarms a FAIL on a module
  whose gated check passes (web/browser). [P2-2/#27]

