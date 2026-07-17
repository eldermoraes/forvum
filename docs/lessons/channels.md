# Implementation lessons — Channels

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **A channel (Layer 3) drives turns but must not depend on `forvum-engine` — promote the turn-driver
  contract to `forvum-sdk` (Resolution B).** A channel's direction is inverted (plugin→engine), unlike a
  provider/tool (engine→plugin), yet the Layer-3 enforcer still bans `forvum-engine`. Resolve it with a
  plain (non-sealed) `ChannelTurnDriver` interface in `forvum-sdk` carrying only `forvum-core` + JDK types
  (`dispatch(ChannelMessage, Consumer<AgentEvent>)`, Quarkus-free); the engine's `TurnService` implements
  it; the channel `@Inject`s the SDK interface and ArC resolves it to `TurnService` app-wide.
  `ChannelProvider` stays a pure discovery marker (no transport method — supersedes the planned SI-E1), and
  the channel enforcer stays `{forvum-sdk, forvum-core}`. [M16]

- **A channel-driven turn must self-activate the CDI request context AND catch its own failures — the
  engine `@QuarkusTest` masks both.** `TurnService.dispatch` runs on the channel's own thread (a WebSocket
  virtual thread, a stdin loop) with no ambient request context, but the turn reads via the request-scoped
  `EntityManager` → `ContextNotActiveException`; annotate `dispatch` `@ActivateRequestContext` (the
  `@Transactional` writes still open their own tx). And it must `try/catch` the turn and emit a terminal
  `ErrorEvent` to the sink — an uncaught exception escapes the channel callback and websockets-next's
  default strategy CLOSES the socket with nothing shown, leaving the `ErrorEvent` render arm dead code.
  Engine `@QuarkusTest`s pass regardless (test methods carry a request context and drive only the happy
  path); only an app-level WS e2e (real channel thread) + an always-throwing-provider test catch these —
  both surfaced by the pre-merge adversarial review, not the green build. [M16]

- **A Layer-3 web library is NOT `quarkus:dev`-startable just because it bundles `vertx-http`.** The
  `quarkus:dev` "support library" skip keys off the absent `build` goal, not HTTP presence, so
  `forvum-channel-web` runs its `@QuarkusTest` `*IT` under Surefire (like the headless engine), NOT the Dev
  MCP — even though each `@QuarkusTest` boots a real in-JVM HTTP/WS server. (Corrects the plan's
  "web is the only Dev-MCP-startable module" premise.) [M16]

- **websockets-next test: use `BasicWebSocketConnector` with the full `@TestHTTPResource` URI.** The typed
  `WebSocketConnector<Client>.baseUri(uri)` CONCATENATES the `@WebSocketClient` path, so passing the full
  endpoint URI doubles it → handshake 404. `BasicWebSocketConnector.create().baseUri(fullUri).onTextMessage(
  (c,m)->…).connectAndAwait()` sidesteps it. The per-tab session id is `WebSocketConnection.id()` (from
  `Connection`); `sendTextAndAwait` is blocking → call it from a `@RunOnVirtualThread @OnTextMessage`
  (`io.smallrye.common.annotation.RunOnVirtualThread`), never a Mutiny `Multi` return. [M16]

- **A Layer-3 library module's config DEFAULTS go in `META-INF/microprofile-config.properties`, not
  `application.properties`; and never log a secret-bearing URL.** Quarkus loads `application.properties` only
  from the application artifact (`forvum-app`); a dependency JAR's `application.properties` works in the
  module's OWN `@QuarkusTest` (there the module IS the app) but is NOT a config source in the assembled
  binary, so it silently falls back to defaults. M17's Telegram rest-client `read-timeout` (which MUST exceed
  the 50s long-poll, else `getUpdates` is cut at the 30s default) belongs in
  `META-INF/microprofile-config.properties` (ordinal 100, loaded from every JAR; `forvum-app`/env override at
  a higher ordinal). Security: the bot token is embedded in the request URL PATH (`/bot<TOKEN>`), so a
  REST-client exception must NOT be logged raw — redact the `/bot<TOKEN>` segment and log a message only (no
  throwable/stack). The long-poll worker is a self-started loop on
  `Executors.newVirtualThreadPerTaskExecutor()` (blocking, no Mutiny — `@RunOnVirtualThread` is only for
  externally-invoked inbound handlers). An enabled-but-token-less `telegram.json` must NOT count as a live
  server channel (`ChannelLauncher.shouldRunAsServer` is token-aware) or the binary hangs in
  `Quarkus.waitForExit()` serving nothing. [M17]

- **A persistent-WebSocket channel (Discord gateway) rides `quarkus-websockets-next` CLIENT mode, not a
  reactive SDK.** JDA/Discord4J are native-broken/reactive and pull a transport stack that violates the SDK
  boundary; a hand-rolled minimal Gateway v10 client over `@WebSocketClient` (the CLIENT-mode dual of the web
  channel's `@WebSocket`) native-compiles + boots (the readiness spike proved the websockets-next-client +
  rest-client combo). CLIENT-mode API (from the Dev MCP, NOT memory): `@WebSocketClient(path="/")` endpoint
  with `@OnOpen(WebSocketClientConnection)` / `@OnTextMessage @RunOnVirtualThread void onText(conn, frame)` /
  `@OnClose`; inject `Instance<WebSocketConnector<Endpoint>>` and `connectors.get().baseUri(URI)
  .userData(UserData.TypedKey.forString(k), v).connectAndAwait()` to open (connectors are single-use +
  not-thread-safe → `Instance.get()` per connect); pass per-connection secrets via `userData()` read back in
  the endpoint, never a field. Keep the protocol layer SOCKET-FREE (a pure `GatewayProtocol.decide(payload,
  state)→sealed Reaction` + frame parse/encode + an atomic `GatewayState`) so HELLO→IDENTIFY, the
  heartbeat-carries-last-seq, and MESSAGE_CREATE flows are unit-testable with no live `wss://`. CONCURRENCY:
  the op-1 heartbeat loop is a dedicated `Thread.ofVirtual()` driven by `heartbeat_interval`; shared
  seq/session live in `AtomicLong`/`AtomicReference` (NO `synchronized`, §3.8); a `ReentrantLock` guards ONLY
  the heartbeat-thread reference swap (interrupt the old thread OUTSIDE the lock — no blocking IO under any
  lock → no carrier pinning). Token never logged: it rides the IDENTIFY frame + the REST `Authorization: Bot
  <token>` header (a `@HeaderParam`, not a `@Url` path like Telegram), and `redact()` masks any `Bot <token>`
  echo. Mirror Telegram for the rest (config reader, allowedUserIds, byte-identical `render()`, the
  no-token→warn+no-op boot, the `ChannelLauncher` token-gated `serves()`). Native-gate caveat: heartbeat-loop
  concurrency + reconnect/TLS edges only surface at runtime → keep plain JSON (no zlib-stream), gate any live
  end-to-end behind `*-LiveTest @Tag("live")`. NATIVE-FRAME TRAP: the *outbound* envelope record (`{op,d}`
  wrapping every IDENTIFY/HEARTBEAT via `writeValueAsString`) ALSO needs `@RegisterForReflection` — without it
  the native binary emits an empty/malformed frame and the handshake silently fails, and the no-token native
  smoke can NOT catch it (no token → never serialized). Pin it with a non-live encode test asserting the JSON
  carries `op`/`d` with the right opcodes (2 IDENTIFY, 1 HEARTBEAT). RECONNECT (must, not optional): a gateway
  connection is NOT permanent — Discord routinely sends op-7 RECONNECT, so a connect-once design dies on the
  first routine event. Self-heal from `@OnClose(conn)` (read `conn.closeReason().getCode()`): if still
  `running` (no ShutdownEvent) and the code is not a fatal 4xxx (`{4004,4010..4014}`), re-open on a VT with
  exponential backoff (a pure clock-free `Backoff` atomic: 1s→2s→…cap 60s, `reset()` on READY); a deliberate
  shutdown (`running=false`) never reconnects; a fatal code stops with a WARN (no infinite loop). The initial
  v0.1 policy was fresh IDENTIFY per reconnect; the op-6 RESUME follow-up has since LANDED — on a resumable
  close the reconnect dials `resume_gateway_url` (+ the same `?v=10&encoding=json` params; Discord sends it
  bare) and HELLO yields `SendResume` (`{token, session_id, seq}`, a `@RegisterForReflection` outbound frame +
  encode test) when `GatewayState.canResume()` (session + resume URL + a seen seq), falling back to IDENTIFY
  on the base URL after op-9 `d=false` resets the state; RESUMED resets the backoff like READY. CLOSE-CODE
  TRAP: Discord invalidates the session when the CLIENT closes with 1000/1001, so every close made with
  intent to resume — op-7, resumable op-9, heartbeat failure — must send a non-1000 application code
  (`closeAndAwait(new CloseReason(4000, reason))`) or the RESUME is defeated on its primary trigger (op-7)
  by our own NORMAL close; deliberate shutdown and non-resumable op-9 keep 1000. Pin the close code at the
  ENDPOINT with a fake `WebSocketClientConnection` — decide()-level tests alone pin intent the transport can
  silently defeat. And `state.reset()` on BOTH a failed dial of the resume host (hosts rotate; else every
  backoff retry re-dials the dead host forever) AND close codes 4007/4009 ("start a new session"). A failed
  heartbeat send must CLOSE the connection so the
  same `@OnClose`→reconnect path fires (else the log claim "the gateway will reconnect" lies). The
  endpoint(`@Singleton`)→channel(`@ApplicationScoped`) callback is plain `@Inject`; a test subclass of the
  channel that overrides `connect()` to record must be `@Vetoed` or the module's `@QuarkusTest` sees two
  `DiscordChannel` beans (AmbiguousResolution). Make the policy unit-testable via a `Sleeper` seam +
  same-thread executor: assert growing backoff on transient close, no reconnect on shutdown, stop on fatal,
  backoff reset on READY. [P2-CH/discord]

- **A `forvum-channel-*` `ChannelLauncher` serve-gate MUST mirror the channel's own `onStart` `isReady()`
  gate EXACTLY, and a `ProcessBuilder` subprocess driver MUST use the `ShellExecutor` bounded-drain
  pattern.** The voice channel (#28) shipped two latent defects the 3-agent review caught: (a) its
  `ChannelLauncher` serve-gate required 2 keys `{whisperBin, piperBin}` while `VoiceChannel.onStart`
  gates on `VoiceSpec.isReady()` = 4 keys — the [M17] serve-gate-vs-isReady mismatch at n=4 (a partially
  configured voice channel would be launched-as-server then no-op). Align the launcher gate to the same
  key set the channel's own `isReady()` checks. (b) `DefaultSubprocessRunner` drained the child's output
  on an `ExecutorService` + unbounded `Future.get()`; its `close()` AWAITS the drain task, so an escaped
  reparented descendant holding the output pipe hangs the worker forever. Use the `ShellExecutor`
  recipe: drain on a `Thread.ofVirtual()`, hand the result back via an `AtomicReference`, and BOUND the
  post-settle `join(graceMillis)`. File-drop voice (transcribe a dropped audio file, synthesize to a
  file) also avoids the live-mic `javax.sound.sampled` native surface, so `[NATIVE]` is resolved with
  ZERO native bindings — subprocess exec only. Reconstruct-on-clean-branch when a stacked branch carries
  more than the one feature: cherry-pick the fix and rebase the single-purpose branch onto main so the
  net diff is feature-only. [P2-3/#28]

- **A cross-channel "fail-closed default" is a SHARED policy in `forvum-sdk` + a per-channel one-line
  delegation, NOT seven hand-rolled predicates — and the breaking-default flip's real cost is the test
  blast radius, not the production edit.** #170 inverted the seven remote channels' fail-OPEN admission
  (`allowedUserIds.isEmpty() || contains(id)` = empty-list-allows-anyone) to fail-CLOSED
  (`ChannelAdmissionPolicy.admits(allowedIds, allowAllUsers, id)`: empty/missing list DENIES unless an
  explicit `allowAllUsers` opt-in). The decision (#170 A1) was admission-flip-ONLY: a remote channel with
  no allowlist and no public flag denies every sender (the "fails closed" branch of the acceptance
  criterion), and device pairing (#166) stays opt-in/orthogonal — NOT made mandatory. Where the policy
  lives is the load-bearing call: a tiny pure `ChannelAdmissionPolicy` in `forvum-sdk` (Quarkus-free,
  type-agnostic so Telegram's `Set<Long>` and the others' `Set<String>` both use it) reused by all seven
  channel `Spec` records AND the engine `ConfigDoctor` AND the app `ChannelSecurityAudit` — one source of
  truth, no seven-way drift, one contract test (the idiomatic Forvum "shared channel contract in the SDK",
  cf. `ChannelTurnDriver`). **The "public mode does not grant privileged scopes" criterion needs ZERO new
  scope machinery — it composes with #168**: a public-mode-admitted sender is unmapped → resolves to the
  `anonymous` identity → empty scope set → `ToolExecutor` denies every scoped tool. Proven by a security
  test driving an IN-belt `fs.write` (belt gate passes) that the SCOPE gate denies for the anonymous
  principal (distinct from `PromptInjectionToolDeniedTest`'s empty-belt belt-miss). **Boot-audit vs doctor
  split:** the app `ChannelSecurityAudit` (a `StartupEvent` observer gated on `CommandMode.isOneShot()` like
  `OperatorAuthFailClosed`, so zero cold-start cost) knows the channel set (`ChannelLauncher.ADMISSION_GOVERNED_CHANNELS`
  = `SERVER_CHANNELS` minus web), so it WARNs the deny-everyone nudge + public + contradictory; the engine
  `ConfigDoctor.checkChannelSecurity` is channel-agnostic, so it keys on `allowAllUsers` being PRESENT
  (only an admission-governed channel sets it) → flags public/contradictory with no false-positive on the
  token-gated web channel. **Test blast radius (the real work):** adding `allowAllUsers` as a trailing
  `Spec` record component breaks EVERY `new Spec(...)` call-site, and each channel had 2–5 coupled tests
  beyond its config test — the processor IT, the poll-loop/sync-loop test, the log-redaction-wiring test —
  that relied on empty-list-allows-anyone to drive an admitted turn; each must opt into public mode
  (`, true` on the ctor, or `"allowAllUsers": true` in a config-FILE-backed fixture), while a populated-list
  membership/deny test just appends `, false`. The `empty-list-allows` config-unit assertions INVERT to
  deny + gain a public-mode case. A parallel one-agent-per-channel sweep (worktree-free, distinct modules,
  no cross-file conflict) is the right tool — but tell each agent to run its FULL module suite and fix
  every now-red test, not just the config test. **NPE trap the sweep surfaced (real bug, fixed centrally):**
  the channels back the allowlist with an immutable `Set.copyOf(...)`, whose `contains(null)` THROWS in JDK
  25 — the old WhatsApp/Voice predicates carried a `senderId != null` guard that naive delegation dropped,
  so `admits` must itself guard the null id (`userId != null && allowedIds.contains(userId)`), centralizing
  the guard for every channel. Two independent agents hit the same NPE and the shared-policy fix resolved
  both. **Verify-under-saturation flake:** launching the full `./mvnw verify` while six build-agents were
  still finishing saturated the box — the forvum-app `@QuarkusTest` boot ballooned to 122 s and the fork
  failed with the app reporting only one test class and NO `<<< FAILURE` marker; re-running
  `-pl forvum-app test` on the now-idle machine is the diagnostic (every other module was SUCCESS), mirroring
  the documented macOS ApprovalServiceIT saturation flake — don't read a fork-killed-under-load app failure
  as a code failure. [#170]

