# Implementation lessons — Security & authorization

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **A second authorization gate belongs at the same `ScopedValue` seam as `CURRENT_AGENT`, enforced "only
  when bound" — NOT fail-closed-when-unbound.** P2-11 RBAC gates a tool by the caller's effective scopes in
  addition to belt membership. `ToolExecutor` is `@ApplicationScoped` (can't `@Inject` per-turn state), so it
  reads a new `CurrentIdentity.CURRENT_EFFECTIVE_SCOPES` `ScopedValue` bound at the turn entry — mirroring how
  `Agent` already reads `CURRENT_AGENT`, proven to survive the whole `respond → SupervisorGraph → ToolCallBridge
  → ToolExecutor` chain on one virtual thread. ALL production turn entries that reach a tool call are exactly
  two — `TurnService.dispatch` (channels + `forvum ask`) and `CronScheduler.fire` — and both bind it; sub-agent
  workers (`DefaultWorkerRunner`) do a single direct generation with NO tool loop (M18), so they never reach the
  executor. So "enforce iff bound, else belt-only" keeps the gate always-active in production while leaving the
  many lower-level belt-focused unit tests (`ToolExecutorTest`, `ToolCallBridgeTest`, `SupervisorGraphTest`)
  untouched. Fail-closed-when-unbound was the initial plan but is strictly worse: it forces RBAC bindings into
  those unrelated tests for zero production benefit (it can't make production more secure — production always
  binds). Enumerate every `agent.respond(` / turn entry before choosing the unbound semantics. [P2-11]

- **"Extend `PermissionScope` to role-based sets" means cable role-sets ABOVE the enum, not add enum
  constants.** ULTRAPLAN §4.3.4 mandates the role → scope-set mapping live above `PermissionScope`, which stays a
  flat capability list. So P2-11 added ZERO `PermissionScope` constants: a Layer-0 `RoleSpec(name,
  Set<PermissionScope>)` record + an additive `Identity.roles` (4-arg canonical ctor + a 3-arg delegating ctor
  so existing `new Identity(a,b,c)` callers/tests compile and a `roles`-less JSON defaults to empty = backward
  compatible, no migration), an engine `RoleRegistry` with code-level built-ins overridable by
  `roles/<name>.json` (mirror `AgentRegistry`: `ConcurrentMap` + `putIfAbsent`, IO off the lock, `@Observes
  ConfigurationChangedEvent` evict; built-in `default-user` = `EnumSet.allOf` so it grows with the enum, `cron`
  = read-only). The new config subfolder is wired by adding `ForvumHome.roles()` + `"roles"` to
  `ConfigWatcher.WATCHED_SUBFOLDERS` + a `RoleReader extends JsonDirectoryReader`; the new core record is
  reflection-registered in the engine `CoreReflectionRegistration` holder (§6.3), NOT `@RegisterForReflection`
  in core. Parity with a simpler upstream (OpenClaw is binary owner/non-owner + tool-name lists, no abstract
  scopes) is semantic — reproduce its behavior (permissive default, restricted cron) in the local vocabulary,
  don't copy its types. [P2-11]

- **A new `$FORVUM_HOME/<dir>/` config-file registry is a fixed five-edit recipe — copy `roles/`, don't
  invent.** P2-4 device pairing added `devices/<id>.json` with ZERO schema change by mirroring P2-11/M7
  exactly: (1) `ForvumHome.devices()`; (2) add `"devices"` to `ConfigWatcher.WATCHED_SUBFOLDERS` (this one line
  is what makes hot-reload fire — the watcher already registers any listed subfolder created after boot); (3) a
  raw `config/DeviceReader extends JsonDirectoryReader` (the base is PACKAGE-PRIVATE, so the raw reader MUST
  live in `ai.forvum.engine.config` — only the typed `DeviceSpecReader`/registry live in the feature package);
  (4) a Layer-2 `Device` record with its OWN `@RegisterForReflection` (a Layer-2 record, unlike a core record,
  carries the annotation directly — NOT in `CoreReflectionRegistration`); (5) an `@ApplicationScoped`
  `DeviceRegistry` = `ConcurrentMap` + `putIfAbsent` with IO off the lock + `@Observes ConfigurationChangedEvent`
  evict (filter `path.getName(0)=="devices"`). Enforce opt-in like RBAC: an empty/absent dir disables the guard
  (cache a `volatile Boolean enabled`, null it on config change) so an existing install needs no migration; a
  distinguished built-in id (`cron`/`server`) short-circuits exempt. Enforcement keys off `ChannelMessage`'s
  existing fields (`channelId` = the device endpoint) — do NOT add a `deviceId` to the core record this package
  (the turn entry `TurnService.dispatch` already wraps a thrown guard as the terminal `ErrorEvent`). [P2-4]

- **The prompt-injection security test must drive the BELT gate end-to-end, not the executor directly.** The
  existing `PermissionScopeMismatchTest` already denies an out-of-belt tool by calling `ToolExecutor.execute`
  with a hard-coded name; the mandated prompt-injection category (CLAUDE.md §11) is the same belt-miss denial
  but realized through the real channel turn entry — a scripted tool-calling fake model (id `scripted-injection`,
  app-test scope, mirrors the engine's `ScriptedToolCallModelProvider`) emits an `fs.write` `ToolExecutionRequest`
  the way an injected instruction would coerce a real model, the agent's `allowedTools` is `[]`, and
  `TurnService.dispatch → SupervisorGraph.toolLoop → ToolCallBridge → ToolExecutor` denies + audits it
  (`status='denied'`) while the turn still completes (terminal `Done`, no `ErrorEvent`). Assert BOTH the denied
  row AND `ok=0` for the same `(session,tool)` — the no-escalation half — scoped to the session this method
  writes (shared `@TestProfile` DB, §14). Make it gating by red-checking: put the tool back in the belt and the
  `denied=1` assertion must flip to `0`. The engine `ScriptedToolCallModelProvider`/`FakeToolProvider` live in
  `forvum-engine/src/test` and are NOT on the app classpath — add an app-test fake; route by `extensionId()`
  (`LlmSelector` matches the `ModelRef` provider half), and a new `ModelProvider` bean does not perturb the
  provider-resolve guards (they inject by concrete type). [TEST-SEC]

- **A security design round is "confirm what's built + name what's deferred", not "invent new gates".** DR-6a
  authored §9 (threat model STRIDE-by-surface + the `OutputFilter` contract) by *confirming* the already-merged
  controls in the threat context (the two `ToolExecutor` gates — belt + the P2-11 RBAC `CURRENT_EFFECTIVE_SCOPES`
  second gate; `@AgentScoped` memory isolation; spawn-boundary identity inheritance) rather than proposing new
  runtime machinery. Prompt-injection is **containment-by-structure** (the gates + the `reduce` Isolate boundary +
  data/instruction framing), explicitly NOT a runtime injection-detector — and tool-execution filters are *output*
  filters (catch egress leaks), never injection preventers; a user-defined-tool surface would breach the
  author-authored tool-spec assumption and needs its own future contract. The `OutputFilter` disposition is a
  3-subtype sealed `FilteringOutcome` (`Allowed`/`Redacted`/`Blocked`) in `forvum-core`; the brief's "FILTERED"
  label is the `FallbackReasons.FILTERED` *reason token* on the `Blocked` path (mirrors `COST_BUDGET`), not a
  fourth subtype — and the engine-only `OutputFilteredException` mirrors `BudgetExhaustedException` (unchecked,
  engine-caught terminal short-circuit) so the SDK/core stay exception-free. Coordinate the `Filtered` spelling
  with DR-4c's `FailureClass` (filtered = non-retryable). Flag each settled point inline as `[DP-n]` so a
  maintainer can ratify/amend a draft surgically. [DR-6a]

- **The DR-6a §9.2 `OutputFilter` is a sealed-disposition value in core + a sealed SPI in the SDK + an
  engine-only enforcement surface — the egress is filtered, the memory transcript is NOT.** P2-OUTPUTGUARD
  (#48): `FilteringOutcome` (sealed `Allowed`/`Redacted`/`Blocked`) lives in `forvum-core` (registered for
  native from `CoreReflectionRegistration` §6.3, NOT `@RegisterForReflection` — core bans `io.quarkus*`);
  `OutputGuard` (sealed, `permits AbstractOutputGuard`) + `OutputContext`/`HookLayer` live in `forvum-sdk`
  ROOT package (mirroring `ModelProvider`, Quarkus-free); `OutputFilteredException` is engine-local,
  mirroring `BudgetExhaustedException`'s behavioral pattern (unchecked, engine-caught terminal
  short-circuit) but NOT a Layer-0 value. The engine `OutputGuardChain` folds guards **fail-closed +
  most-restrictive-wins** (any `Blocked` dominates `Redacted` dominates `Allowed`; redactions chain
  forward + union; a guard that throws or returns null folds to `Blocked`). The default `SecretRedactionGuard`
  is **on by default** (opt-out `forvum.output-guard.secret-redaction.enabled=false`) and only ever
  Redacts (full Block is reserved for policy guards v0.1 does not ship). Hook is `TurnService.dispatch`
  AFTER `agent.respond` returns and BEFORE emitting `TokenDelta`/`Done` (the pre-channel-emit seam,
  `HookLayer.PRE_CHANNEL_EMIT` — the only one wired; `PRE_MEMORY_WRITE` is reserved, so the model
  transcript already persisted by `agent.respond` keeps the raw secret — only the channel egress is
  masked). A `Blocked` throws `OutputFilteredException`, caught by a NEW catch arm BEFORE the generic
  `RuntimeException` arm → `ErrorEvent(code="output_filtered")` on the `FallbackReasons.FILTERED` path.
  **TRAP (a green module build hid it):** a test-only blocking guard declared `@Alternative @Priority(N)`
  is enabled APP-WIDE (CDI: an alternative WITH a priority is global), so it blocked EVERY `@QuarkusTest`'s
  egress → 6 unrelated turn ITs flipped to a 1-event ErrorEvent. Isolate a test alternative with
  `@Alternative` and NO `@Priority`, enabled per-test via `QuarkusTestProfile.getEnabledAlternatives()`.
  `SecretRedactor` is pure (unit-tested without CDI): conservative regexes keyed on scheme prefixes that do
  not occur in prose (`sk-`/`xox[baprs]-`/`gh[posru]_`/`AIza`/`AKIA`/PEM blocks/`Bearer <opaque>`), each
  match → prefix + `***`, exact count, null/empty safe. **JaCoCo trap (CI-only):** adding the `OutputContext`
  record (a canonical-ctor null-check = executable lines + a branch) to the previously logic-free
  `forvum-sdk` broke its vacuously-passing coverage gate — and `verify` runs the JaCoCo `check` while
  `test` does NOT, so a green local `test` still fails CI. RUN `./mvnw verify` (not just `test`) before
  pushing. Fix honestly: a test for the new executable type (`OutputContextTest` covers the validation +
  the `HookLayer` enum) and extend the bridge exclude to `Abstract*.class` (`AbstractOutputGuard` is a
  logic-free `non-sealed` bridge like the `Abstract*Provider`s). New CLI command branches (`pair`/`devices`
  error/`--reason` paths) also pushed `forvum-app` branch coverage under its 0.70 override → cover the
  cheap JVM-reachable ones (approve `--reason`, reject-without-reason, invalid id) rather than relax. [P2-OUTPUTGUARD]

- **"Scope-upgrade approval" is CLI governance + visibility ONLY this PR — the turn-path enforcement of
  `approvedScopes` is deferred to #39 (ratified), so do not wire a third `ToolExecutor` gate here.**
  P2-PAIR-SCOPE (#44): `Device` grows `requestedScopes`/`approvedScopes` (`Set<PermissionScope>`) +
  `decisionReason`; a 4-arg ctor delegates to the 7-arg (backward compatible, a scope-less device file
  still parses, no migration). `forvum pair approve|reject` + `forvum devices` are `CommandMode` one-shots
  (file-only, no DB/watcher — keep them in sync with `RootCommand`). `DeviceConfigStore` is the
  `McpAddCommand` 0600-file recipe + a `safeId` anti-traversal guard, editing the parsed `ObjectNode` so
  unknown JSON fields survive, and parsing through the SAME `DeviceSpecReader` the engine/doctor use (no
  parallel schema). `ConfigDoctor.checkDevices` reuses `DeviceReader`+`DeviceSpecReader` as the oracle and
  warns on drift. **TRAPS the 6-dim review caught (all green-for-wrong-reason / unguarded edges):** (1)
  `decisionReason` must reflect the LAST decision — approve/reject with no `--reason` must CLEAR a stale
  prior reason (else an approve shows an old rejection's reason); (2) `store.read()` casting the top-level
  JSON to `ObjectNode` crashes with a raw stack trace on a non-object device file — validate `isObject()`
  and surface a contextual error the commands turn into a clean exit 1 / `(invalid: …)` line; (3) doctor
  must NOT report a REVOKED device as "a pending upgrade awaiting approval" (it was decided/rejected) —
  gate the drift warning on `!revoked`. Pin each with a regression test (stale-reason device, non-object
  file, revoked-with-drift device). [P2-PAIR-SCOPE]

- **A `USER_CONFIRM_REQUIRED` approval gate is the THIRD tool gate (belt → RBAC → approval), blocks the
  turn's own virtual thread, and audits TWO surfaces — the queue owns the lifecycle, `tool_invocations`
  stays append-only.** P2-14 #39: `ToolSpec` grows an additive 5th `boolean userConfirmRequired` (4-arg
  ctor delegates `false`, so every pre-#39 call site + JSON spec is unchanged — no migration on
  `tool_invocations`). `ToolExecutor` consults a narrow engine `ApprovalGate` (sole impl `ApprovalService`)
  ONLY when `tool.userConfirmRequired()` AND after the two existing gates pass (no point parking a call the
  identity may not make). On reject/timeout it audits a `denied` `tool_invocations` row + throws
  `ApprovalDeniedException` (a `PermissionDeniedException` subtype → `SupervisorGraph.runTool` catches it
  FIRST, before the generic arm, for a clear model-facing "you declined it" result; the turn COMPLETES,
  same non-abort behavior as a belt miss [TEST-SEC]). **Audit decision (maintainer-ratified over the
  ULTRAPLAN DP-9 literal):** the new `tool_approvals` queue (`V4__approvals.sql`, the `tasks`-style recipe:
  TEXT-UUID PK, `status` pending|approved|rejected|timed_out, 2 indexes) carries the parked lifecycle;
  `tool_invocations` records exactly ONE terminal row — NOT a parked `confirm_required` row + a resolve
  (that would double rows and break append-only). The §14 [M5] "head bump → bump `SchemaSmokeIT`" rule
  applies: V3→V4, + the table/indexes in EXPECTED_TABLES/INDEXES.

- **The resolution-mode seam lives in `forvum-sdk` (interfaces, jacoco-clean), so a Layer-3 channel binds
  it without depending on the engine; the engine reads it on the turn's one VT (like `CURRENT_AGENT`).**
  `ApprovalContext` (an INTERFACE with two `ScopedValue` constants — no ctor → no per-module jacoco line,
  unlike a holder class) + the `ApprovalPrompter` functional interface. Per-turn binding selects the mode:
  `PROMPTER` bound → synchronous TTY prompt (the interactive TUI binds a console y/N prompter reading the
  same stdin `BufferedReader`); `NON_INTERACTIVE` true → immediate deny (one-shot `forvum ask`, cron, piped
  TUI — no surface); neither → async, the engine parks + blocks on an in-memory `CompletableFuture` the web
  `/q/dashboard/approvals` `POST .../{id}/approve|reject` completes, timing out (`forvum.approval.timeout-seconds`,
  default 300) to deny. `TurnService.dispatch` additionally binds `CurrentAgent.CURRENT_USER_MESSAGE`
  (engine ScopedValue) for R1 capture. **Two ScopedValue traps:** (1) `where` is STATIC —
  `ScopedValue.where(KEY, v).call(...)`, never `KEY.where(v)`; (2) `ScopedValue.orElse(null)` throws
  (`requireNonNull`) — for a nullable read use `isBound() ? get() : null`, reserve `orElse(x)` for non-null x.

- **Persist the pending row BEFORE blocking, in a separate `@Transactional` + `@ActivateRequestContext`
  bean — never hold a connection across the wait** ([M7]/[M16]). `ApprovalStore` (not inlined into
  `ApprovalService`, so the calls cross the CDI proxy and the interceptors fire) commits the `pending` row,
  then `ApprovalService` blocks the VT connection-free, then a short `resolve` tx commits the outcome.
  `@ActivateRequestContext` on the store methods makes them work on ANY thread (the turn's, a one-shot/cron
  thread, the dashboard's blocked-turn thread) for the request-scoped `EntityManager`; each op is
  self-contained so a nested context is harmless. `ConcurrentHashMap` + `CompletableFuture` carry the
  cross-thread hand-off — no `synchronized` (§3.8); a blocking `CompletableFuture` on a VT parks, no pin.
  **Use `future.completeOnTimeout(SENTINEL, t, SECONDS)` + `future.get()`, NOT `get(timeout)`:** the timeout
  becomes an ATOMIC completion, so a late dashboard `decide()` (which returns `future.complete(...)`'s result)
  sees the future already done and reports not-handled — instead of `complete()` succeeding and the dashboard
  claiming success AFTER the waiter already saw `TimeoutException` and resolved `timed_out` (the
  timeout-vs-decide lie the 6-dim review flagged).

- **R1 restart-recovery: a pending row SURVIVES a restart (it is NOT auto-timed-out on boot); approving the
  orphan re-dispatches the turn — best-effort, not exact resume.** When `decide(id)` finds no live future in
  THIS process (the row outlived its turn thread), it resolves the queue row and, on approve, re-dispatches
  the turn from the stored `user_message` via the SDK `ChannelTurnDriver` on a fresh VT, binding two things
  FOR THAT REPLAY TURN ONLY: `NON_INTERACTIVE` (so any OTHER confirm in the replay denies) and a turn-scoped
  `ScopedValue` grant of the exact `(session,tool,args)` key; `requireApproval` auto-approves a call whose key
  is in the bound grant (no row), so the re-run's identical call passes without re-prompting. The reply is
  logged — the original connection is gone. **6-dim review caught a real MAJOR here:** an earlier cut kept the
  grant in a PROCESS-GLOBAL set with no TTL, so a divergent replay (the model not re-emitting the exact call)
  left it dangling and a LATER unrelated same-session identical call would silently auto-approve — a
  confirm-gate BYPASS. The fix is the turn-scoped `ScopedValue` (bound only on the replay VT), which cannot
  reach any other turn (an unrelated turn has no binding); pin it with a test asserting the grant is visible
  DURING the replay and absent outside it (a process-global set would NOT have that property). Exact
  checkpoint/resume is R2, deferred — it conflicts with the M18 R6 "no checkpointer / in-memory langchain4j
  conversation" stance, tracked as #138. Inject `ChannelTurnDriver` (not the
  concrete `TurnService`) into `ApprovalService` — the @ApplicationScoped proxy cycle
  (ApprovalService→TurnService→…→ToolExecutor→ApprovalGate→ApprovalService) is broken by CDI proxies, but
  the SDK interface keeps the coupling clean. A same-thread `Executor` field makes re-dispatch
  deterministically observable in a plain unit test (a `@Vetoed` `ApprovalStore` stub + a recording
  `ChannelTurnDriver`), so the DB-backed resolution modes (interactive/non-interactive/async/timeout) are
  the only part needing a `@QuarkusTest` IT.

- **The web approval surface mirrors `CaprDashboardRoute` exactly (X6): a `quarkus-reactive-routes`
  `@Route` over the already-present `vertx-http`, server-path only, ZERO cold-start impact.** GET returns a
  value (auto-JSON) of `@RegisterForReflection` Layer-4 view records; POST uses `@Param("id")` for the path
  var (confirmed via the Dev MCP, NOT guessed — §7) and returns `{handled, id}` with HTTP 200 even for an
  unknown id (the client reads the flag; a 404 would need `RoutingContext`). `type = BLOCKING` for the
  Panache work; no `@Startup`/observer so command-mode boot is untouched. The e2e seeds orphaned rows via
  the engine `ApprovalStore` and drives real GET/POST — no live model. **CI runs `verify`, not `test`:** the
  new `forvum-sdk` `ApprovalContext` (an interface whose `<clinit>` runs `ScopedValue.newInstance()`) needed
  a 3-test `ApprovalContextTest` to keep the module's jacoco gate green — `./mvnw verify` locally before
  pushing, never just `test` ([P2-OUTPUTGUARD]). **And the CI concurrency-guardrails step is NOT part of
  `verify`** — run `bash .github/concurrency-guardrails.sh` locally too. It cost a red CI here: a separator
  char literal in `preApprovalKey` had been written as a RAW NUL byte (`'<NUL>'`) instead of the escape
  `'\0'` — javac + every local build/test/native accepted it (char 0), but the raw NUL made `grep` treat the
  whole file as BINARY, which DEFEATED the guardrail's comment-exclusion and falsely flagged the Javadoc's
  `{@code synchronized}` mention as a hot-path violation. Two lessons: editor/tool round-trips can silently
  turn an intended space into a control byte in a char literal (use the explicit escape `'\0'`, and a quick
  `python3 -c "...count(b'\x00')"` over changed files catches it); and the guardrail grep's
  legitimate-Javadoc-mention exclusion only works on a TEXT file.

- **An agent-level role CAP is `caller ∩ agent-roles`, applied at the SAME two `CURRENT_EFFECTIVE_SCOPES`
  bind sites as the caller scopes — and "empty agent roles = no cap" is the security-load-bearing OPPOSITE
  of `effectiveScopes`'s "empty = permissive default".** #167 enforced the DR-8 DP-8 ceiling the v0.5 code
  parsed (`Persona.roles`) but never applied — `TurnService.dispatch`/`CronScheduler.fire` bound only the
  CALLER's roles, so an operator-restricted persona still ran with the caller's full scope set. The
  primitive is a NEW `RoleRegistry.capScopes(callerScopes, agentRoleNames)`: DO NOT reuse `effectiveScopes`,
  whose empty branch returns the permissive `default-user` and would WIDEN an uncapped agent back to every
  scope; `capScopes` returns `callerScopes` unchanged on an empty/absent list (no cap, never a grant — it
  can only RESTRICT) and throws on an unknown role (fail-closed, via `scopesFor`). Bind the CAPPED set once
  at the turn entry; a named-but-undefined role (identity OR agent) is caught right at that computation and
  surfaced as a terminal `role_unresolved` ErrorEvent (dispatch) / disabled cron (the existing fire catch),
  carrying the RoleRegistry diagnostic but NOT the caller's broader scopes (acceptance: audit without
  leaking). **Approval-resume needs NO new code** — it re-dispatches through `TurnService.dispatch`, so it
  inherits the cap (covered compositionally: `ApprovalServiceReDispatchTest` proves decide→dispatch +
  `TurnServiceAgentRoleCapIT` proves dispatch-applies-cap; a heavy combined IT would re-test the same path).
  **Spawned workers need NO runtime change** — a worker is one direct generation with no tool loop (M18,
  `DefaultWorkerRunner`), so it never reaches the `ToolExecutor` and has no surface to exceed the cap (pinned
  by a containment IT — even when the worker's model emits a tool call, no `tool_invocations` row is written
  — NOT by enforcement code; role INHERITANCE onto the child is already proven by `AgentRegistryTest`).
  **Defense-in-depth per the ratified acceptance #5:** `SupervisorGraph.scopeVisibleBelt` scope-filters the
  model-facing belt so the model is never even OFFERED an out-of-cap tool, while the FULL belt still reaches
  the executor so a coerced/injected out-of-cap call is still denied + audited with the scope-specific
  message (the `spawn_worker` built-in is added AFTER the filtered belt, so it is unaffected; cycles are
  generation-only, no discovery to filter). `ConfigDoctor` validates each agent role resolves
  (reader-as-oracle: a new public `RoleRegistry.BUILT_IN_ROLE_NAMES` ∪ the `roles/` listing — no drifting
  second list of built-ins), the proactive twin of the runtime fail-closed. **TRAP:** adding
  `registry.persona(id)` to `CronScheduler.fire` broke `CronSchedulerTest` (a `@Vetoed StubRegistry` that
  overrode only `getOrCreate` — `persona()` then threw, the fire caught it, and delivery silently dropped to
  0): when a production method grows a collaborator call, every hand-built stub of that collaborator must
  model the new call. [#167]

- **A PRIOR seam often already names its successor — read the related javadoc before inventing an approach.**
  #166 authenticated device tokens (P2-4 parsed `token`/`approvedScopes` but never compared/intersected
  them — a configured device was authorized by mere existence). The #165 `OperatorAuthMechanism` javadoc
  EXPLICITLY said "a device token resolves to a `SecurityIdentity` through this same mechanism," and the
  issue's "coordinate with #165, not a second credential mechanism" confirmed it — so the web path EXTENDS
  that mechanism (a non-operator token → `device`-role identity, `/ws/chat` policy admits operator OR
  device, dashboards stay operator-only) rather than building a parallel one. The credential is a
  transport-neutral `DeviceCredential(deviceId, token)` `forvum-core` record with a **redacted `toString()`**
  (secret never enters a log/error/ledger), carried on a NEW `ChannelTurnDriver.dispatch` overload added as
  a **default delegating to the 2-arg method** — a new *abstract* method would have broken all 10+ channel-test
  fakes; only `TurnService` + the web fake override it. Engine auth = `DeviceRegistry.authenticate` (constant-time
  `MessageDigest.isEqual` + `deviceId==channelId` bind + revocation, throwing a `DeviceNotPairedException`
  subtype with no token in the message), BEFORE the responder. **The `approvedScopes` intersection is
  PRESENT-only:** apply it iff the turn authenticated AS a device (a credential was presented), so the host
  operator / local surface (the ABSENT path, kept for P2-4 backward-compat) is NEVER device-capped — `approvedScopes`
  govern a paired device, not the host; a pending upgrade never appears because only `approvedScopes` (not
  `requestedScopes`) are intersected. TRAPS: (1) `@Inject SecurityIdentity` in a `@WebSocket` endpoint needs
  `quarkus-security` AS A DIRECT dep on the Layer-3 web channel — the transitive api alone gives no producer,
  so the module's own `@QuarkusTest` fails boot with `UnsatisfiedResolutionException` (masked in the assembled
  app, which has the mechanism); (2) the mechanism's device lookup does blocking file IO, so run it OFF the
  Vert.x event loop (`Uni...runSubscriptionOn(Infrastructure.getDefaultWorkerPool())`) — the `Uni` boundary is
  already framework-mandated; (3) a `SecurityIdentity` test fake needs `getPermissions(): Set<Permission>`
  (not just `checkPermission`) — `javap` it via the real test classpath (`dependency:build-classpath`), don't
  guess the method set; (4) red-check the scope intersection by mutating it OUT and watching the tool-denial
  IT flip to allowed (green-for-right-reason). #166 = HOW to authenticate a configured device (opt-in kept);
  WHEN device auth becomes mandatory for remote channels (fail-closed defaults) is #170. [#166]

- **Sanitize a channel-visible failure at its ONE construction seam, not at the N renderers — and for an
  UNTRUSTED message "genericize" beats "redact" when the redactor only knows secret SHAPES.** #172 closed
  the `codex-review` "error responses bypass the OutputGuard" gap: the success reply ran through
  `OutputGuardChain` but the five `ErrorEvent.from(...)` sites (ALL in `TurnService.dispatchAuthenticated`)
  did not, and ten surfaces (8 channels + CLI + Voice-TTS) each render `ErrorEvent.message()` verbatim
  (byte-identical copies; module isolation forbids a shared renderer). So a provider/tool/network exception
  leaked secrets/paths/tool-args/prompt-fragments. The fix is ONE `emitError(sink, turnId, code, userMessage,
  cause)` helper at the single construction seam — sanitize once, zero channel changes (they already read
  `.message()`). **Genericize vs redact (the load-bearing call):** `SecretRedactor` masks only secret-SHAPED
  tokens (`sk-`/`xox`/`gh_`/`AIza`/`AKIA`/`Bearer <opaque>`/…), NOT arbitrary private paths, tool arguments,
  or prompt fragments — so "no channel-visible path/arg/prompt" is satisfiable ONLY by dropping the raw
  exception text, not by redacting it. The untrusted `turn_failed` message becomes a stable category + the
  root-cause simple CLASS name (a code identifier, safe) + the curated connection hint (safe — the model
  ref) + a `turnId` correlation `ref`; the curated config-error categories (author-controlled, no untrusted
  content) pass through the `SecretRedactor` and stay actionable. Use the pure `SecretRedactor`, NOT the full
  `OutputGuardChain`, on the error path — a `Blocked` disposition THROWS, and an error must always surface as
  a category, never be blocked/re-thrown into the dispatch catch arm (make `safeFailureMessage`/`emitError`
  defensive: a pathological cause chain falls back to a stable generic phrase). Null the event's
  `stackTraceText` (the full stack carries exception MESSAGES — a LATENT serialization leak — no renderer
  reads it today, but a future JSON path would) but KEEP `exceptionClass` (a class name — a code identifier,
  never user data — so telemetry and the [#166] `TurnServicePairingIT` that asserts `error.exceptionClass()`
  still work; nulling it too broke 5 pairing ITs — a class name is safe, only the stack is the leak); log
  the FULL detail (redacted) at WARN keyed by `turnId` — the protected operator diagnostic that walks the
  user's `ref` back to the exception. **Red-check trap:** at the `catch (RuntimeException
  e)`, `e` is the WRAPPER (e.g. the supervisor-graph exception, generic message) — the leak is the DEEPEST
  cause's message. So the leak fixture must seed the secret/path in a NESTED cause, and the meaningful
  red-check injects `root.getMessage()` into `safeFailureMessage` (passing `e.getMessage()` raw does NOT leak
  and gives a false-green red-check). Verified: injecting the root message flips the e2e to fail on the
  nested-cause path assertion. [#172]

- **CI security gates (#174) — placement, canaries, and four pin-the-pin traps.** Placement: per-PR gates are
  parallel-ubuntu seconds-to-minutes jobs (`dependency-review`/`gitleaks`/`actionlint`+`shellcheck`/CodeQL
  `java-kotlin build-mode: none` + `actions`), push-`main` submits the Maven dependency graph (feeds
  Dependabot alerts), weekly runs the network-sensitive Trivy deep scan, release owns SBOM + provenance + the
  blocking pre-push image scan — nothing joins the ~37 min critical path, macOS gains zero jobs. **Prove each
  gate fires with canary PRs, both directions**: a checksum-INVALID well-formed token (random `ghp_`+36) is
  the perfect secret canary — GitHub push protection validates checksums so the push goes through, while
  gitleaks' regex fires (and the same token in `src/test` proves the allowlist); `log4j-core:2.14.1` in a leaf
  pom proves dependency-review names the GHSA. The canaries also caught a REAL false positive — which is the
  point. Traps, all of the shape "the pin you add is itself an unpinned input": (1) **actionlint pin-currency**
  — an actionlint older than the newest GitHub runner labels false-positives on them (1.7.7 did not know
  `macos-15-intel`); pin the CURRENT release AND the download script by commit SHA, and bump both together.
  (2) **gitleaks-action downloads the LATEST engine at run time** — pin `GITLEAKS_VERSION` to the version the
  `.gitleaks.toml` was validated against (`[[allowlists]]` is newer-engine syntax; an older parser ignores it
  silently = the allowlist is inert). Validate the config locally with the SAME engine version in BOTH
  directions before trusting a green scan. (3) **`fail_on_unmatched_files` is per-PATTERN** — a single
  `release/*` glob is vacuously satisfied by any one file; ENUMERATE the asset classes (each binary, each
  checksum, each SBOM, the installer), and name sibling globs mutually exclusive (`-maven-sbom` vs
  `-image-sbom` — a bare `*-sbom.cdx.json` matches either). (4) **concurrency groups need
  `github.event_name`** or a Monday push cancels the in-flight weekly scheduled scan sharing the ref group.
  Also: `makeAggregateBom` is an AGGREGATOR — run it at the reactor root (never `-pl`), after a `package`;
  `docker inspect RepoDigests` populates only AFTER push (attest the image digest post-push);
  `dependency-review-action@v5` is a major BRANCH upstream (no v5 tag) — describe GitHub-authored refs
  accurately. GitHub-side settings (secret scanning, push protection, Dependabot alerts/updates) are `gh api`
  PATCHes — half the acceptance criteria live in repo settings, not YAML. [#174]

