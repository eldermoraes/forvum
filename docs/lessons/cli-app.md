# Implementation lessons — CLI app & commands

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **The cold-start lever is a per-invocation skip in EVERY DB/IO `@Observes StartupEvent` observer, not a
  global flag — and it must be tested in BOTH directions.** A one-shot CLI command (`--help`/`--version`/
  `init`, detected by `CommandMode` reading `@CommandLineArguments`, which Quarkus populates before any
  observer) skips Flyway (`PersistenceBootstrap`), the `WatchService` (`ConfigWatcher`), AND cron
  scheduling (`CronScheduler`). The first cut gated only the first two; the 6-dim review caught the
  unguarded `CronScheduler` — combined with `scheduler.start-mode=forced`, a one-shot `init`/`--help` could
  fire a cron turn against the (deliberately) un-migrated DB. When you add a startup observer that touches
  the DB/IO, gate it on `commandMode.isOneShot()` too (`HttpClientFactorySelector`, set-only, stays
  ungated). **`ToolRegistry.onStart` WAS left ungated as "cheap side-effect-free" — P2-13 invalidated that:
  the MCP bridge's `tools()` does a blocking network connect, so `onStart` is now gated on
  `isOneShot()` and `mcp list` re-materializes on demand.** Test the lever with a recording collaborator (a
  `Flyway` subclass whose `migrate()` records; `ConfigWatcher.isWatching()`) injected with a one-shot vs a
  normal `CommandMode` and assert BOTH branches — a single-direction test stays green when the guard is
  deleted (verified by mutating `isOneShot()`→`false` and watching the one-shot assertions go red). [M20]

- **To leave HTTP unbound for a one-shot command, set `quarkus.http.host-enabled=false` as a system
  property in a custom `@QuarkusMain` `main()` BEFORE `Quarkus.run` — not from a `StartupEvent` bean.** The
  bundled Web channel puts `vertx-http` on the only runnable artifact; Quarkus binds the listener at
  RUNTIME_INIT, BEFORE `QuarkusApplication.run()` (where picocli parses args), so a `StartupEvent` bean like
  `CommandMode` is too late and `ProcessHandle...arguments()` is unreliable on macOS. The reliable lever:
  give the `@QuarkusMain` class a `public static void main(String[] args)` (the real native entry point —
  it runs before any Quarkus bootstrap), call `CommandMode.isOneShotCommand(args)` there, and on a one-shot
  `System.setProperty("quarkus.http.host-enabled", "false")` then `Quarkus.run(App.class, args)`.
  `host-enabled` is runtime config (system-property ordinal 400 > application.properties); verified on the
  native binary: with the flag, startup logs `Listening on:` empty and drops ~0.285 s → ~0.040 s. This makes
  a one-shot need no free port (the M20 fix for the review's HTTP-bind findings). (@QuarkusMainTest drives
  `QuarkusApplication.run()`, not the static `main`, so the lever is validated by the native cold-start
  gate, not a JVM test.) [M20]

- **Source `--version` from the build, never a literal.** A hardcoded picocli `version = "..."` drifts from
  the POM on the next bump and a same-literal test pins constant-vs-test, not constant-vs-actual-version. Use
  a CDI `IVersionProvider` reading `quarkus.application.version` (Quarkus sets it from the Maven version and
  bakes it into the native image — reflection-free via ArC, no manifest/resource-filter native hint needed).
  `init` (the app-owned one-shot subcommand) also scaffolds `~/.forvum`, which later holds channel
  credentials, so create it owner-only (0700 dirs / 0600 files via `PosixFilePermissions`, guarded by the
  `posix` view) instead of the world-readable umask default; route the shipped binary's logs to stderr
  (`%prod.quarkus.log.console.stderr=true`) so a one-shot's stdout is just the picocli usage. [M20]

- **A config validator (`forvum doctor`, P2-9) must drive the REAL loaders, never a second, drifting schema.**
  Validate through the same machinery the engine loads with: the M4 readers (`ConfigLoader.readJson` rethrows
  a malformed file as `UncheckedIOException`) + the typed binders (`AgentSpecReader`/`CronSpecReader` throw
  `IllegalStateException` with a file-naming message), each wrapped in try/catch → a `Finding`. So a config
  `doctor` passes is exactly one the engine can load — no parallel JSON-Schema definition to drift (maintainer
  signed off on diverging from ULTRAPLAN's literal "against JSON Schemas"; §7.2 item 9 + `docs/ISSUES.md`
  reworded, formal schemas deferred). Add only the cross-refs the binders can't see: a model ref → an installed
  provider via `Instance<ModelProvider>.extensionId()` (the `LlmSelector` idiom — gathered in the app
  `DoctorCommand`, the only layer that knows the provider set, and passed into the engine `ConfigDoctor`); a
  cron `agentId` → a known agent. Exit non-zero on ERROR / 0 on WARNING-only so scripts + CI can gate. `doctor`
  joins `CommandMode.isOneShotCommand` (reads files only → skips Flyway/watcher/cron; native cold-start ~36 ms);
  keep that set in sync with `RootCommand.subcommands`. Because it is offline + deterministic, its native IT is
  UNTAGGED (runs in the DEFAULT native leg, unlike the `@Tag("live")` Ollama turn IT) — a free real native
  exercise of config validation + provider discovery. Review catch worth generalizing: when a helper LISTS a
  directory from one source and PARSES it from a separate hardcoded literal, a name drift silently skips
  validation — make listing and parsing share one `dir` (single source). [P2-9]

- **A server-only dashboard endpoint must not touch the command-mode cold-start path.** The `/q/dashboard/capr`
  CAPR endpoint (X6 scenario 10) is a `quarkus-reactive-routes` `@Route` (`CaprDashboardRoute`, `type =
  BLOCKING` for the Panache read) over the Web channel's already-present `vertx-http` — chosen over
  `quarkus-rest` so it does not perturb `HttpClientFactorySelector` (the `langchain4j.http.clientBuilderFactory`
  pin) or the REST-client stack. A `@Route` handler binds only when a server channel is up; one-shot/command
  mode leaves `vertx-http` unbound (`quarkus.http.host-enabled=false` from `ForvumApplication.main`), so the
  route never serves there. The discipline: give the endpoint NO `@Startup`/`StartupEvent` observer and do its
  DB/HTTP work only inside the handler — then it cannot regress the < 200 ms command-mode boot-smoke nor the
  `ask`/`doctor` one-shot path (the gate measures those). Add the extension via the platform BOM, never pinned;
  the DTO it serializes is a record carrying the real Quarkus `@RegisterForReflection` (Layer 4 is
  Quarkus-bearing, so the SDK re-export is unnecessary here). [X6]

- **A feature-complete binary still failed the "stranger installs it" test — silent exits and bare wrapper
  errors made working machinery look broken.** A fresh-machine install hit three pure-UX walls: (1) the
  default run with no `~/.forvum` printed the banner and exited 0 with no hint (and the README's JVM section
  omitted `init` entirely), reading as "the terminal doesn't work"; (2) the REPL had no prompt/help line, so
  an interactive session looked frozen while the model worked; (3) every turn failure rendered as the
  wrapper's "Supervisor graph failed for session ..." with the actionable root cause (ConnectException →
  Ollama not running) swallowed. Fixes: TTY-gated interactive affordances — `System.console() != null &&
  console.isTerminal()` is the JDK 25 seam (works in the native image), threaded as a
  `repl(..., boolean interactive)` parameter. TTY-only: the `forvum> ` prompt, the ready line, the
  block-letter banner, and `/exit`-`/quit` interception (demo parity; a piped `/exit` line is NEVER
  swallowed — piped framing stays identical to the M15 contract and a piped session ends at EOF).
  Unconditional by design: the no-channel `forvum init` hint (the whole point is "never exit silently",
  including CI/piped runs) and the error rendering. `TurnService.describeFailure` walks to the deepest
  cause (hop-capped — a multi-node cause cycle is constructible and would spin the catch block forever)
  and, on a connection-level root, appends "Is the model provider running? (model: <ref>)" — fetched via
  a never-throwing persona lookup since the lookup itself can be the failure; NOTE the native image
  surfaces connection-refused as `ClosedChannelException` while the JVM throws `ConnectException` (only a
  live native error-path run caught that), so the predicate covers both (+`UnknownHostException`,
  +`HttpConnectTimeoutException`); the TUI renders
  `ErrorEvent` behind an `[error] ` marker. README install builds get `-DskipTests` (installers were running
  the dev suite and reading its noise as a broken build; contributors run `./mvnw verify`). Test the
  install path as a user would — `init`/`doctor`/piped turn/no-config run on a clean `FORVUM_HOME` — not
  just the milestone Verify scripts. [UX-INSTALL]

- **The Dev UI live config editor is a dev-build-gated `@Route`, NOT a Dev UI card — a card needs a
  `*-deployment` module that breaks the headless-library setup ([M6]).** P3-6 (#54): a true Dev UI card
  (`CardPageBuildItem` + a Lit web component) requires a `@BuildStep` in a `*-deployment` artifact, which would
  force `forvum-engine` into the runtime+deployment split that ArC's custom-context path already showed breaks
  its no-`build`-goal library structure ([M6]); `forvum-app` (Layer 4, the runnable app) is not a library
  extension either, so neither layer can host one without the restructuring this issue forbids. The sanctioned
  fallback is a `quarkus-reactive-routes` `@Route` surface (`DevConfigEditorRoute` in `forvum-app`) over the Web
  channel's already-present `vertx-http` — the same mechanism as `CaprDashboardRoute`/`ApprovalDashboardRoute`
  (X6) — serving a self-contained editor page + JSON list/read/validate/save APIs at `/q/dev-ui/config-editor`,
  reachable in `quarkus:dev`. **Dev-only carve-out via a BUILD-TIME property, not `@IfBuildProfile`:** gate the
  bean with `@IfBuildProperty(name="forvum.devui.config-editor.enabled", stringValue="true")`, set `true` only in
  `%dev`/`%test` (absent → false in prod). `@IfBuildProperty` is evaluated at AUGMENTATION, so the bean — and its
  `@Route` handlers — are removed entirely from the prod/native image (zero native surface, zero cold-start
  cost), the native carve-out the issue mandates. Choosing the build-property gate over `@IfBuildProfile("dev")`
  is what makes the route TESTABLE: a `@QuarkusTest` runs in the `test` build profile (not `dev`), so
  `@IfBuildProfile("dev")` would remove the route from the test build and leave the HTTP wiring unexercised; the
  `%test`-enabled property keeps it built for the E2E while still off in prod. **Validation reuses the P2-9
  oracle, never a second schema:** the engine `ConfigEditorService` (plain final class constructed per request,
  mirroring `ConfigDoctor`; the app supplies `knownProviders` from `Instance<ModelProvider>` like `DoctorCommand`)
  validates a candidate through `ConfigDoctor` and SAVES validate-then-write-then-rollback — write the candidate,
  run doctor, and if the findings for THAT file carry an ERROR restore the previous content (a new file is
  deleted) and fire no event, so a bad edit can never reach the engine's hot-reload; on success fire the same
  `ConfigurationChangedEvent` the `WatchService` emits so the running engine re-reads the spec without a restart.
  `validate` is the dry run (stage the candidate into a throwaway copy of the home so cross-file refs still
  resolve, run doctor, touch nothing on disk). The editable surface is path-confined (traversal/unknown-folder/
  bad-suffix rejected) since it writes user files. Test the service as a plain JUnit unit (no Quarkus boot, a
  `@TempDir` home + a recording change-notifier, mirroring `ConfigDoctorTest`) and the HTTP wiring as an app E2E
  (`%test`-enabled route, seed a `fake:`-pinned agent so no live model). [P3-6]

