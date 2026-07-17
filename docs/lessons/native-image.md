# Implementation lessons — GraalVM native image

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **A module's code only native-COMPILES once `forvum-app` depends on it.** The native image is built
  solely in `forvum-app`; a Layer-2/3 module not wired into the app never enters any native image, so
  "native-compiles" is vacuous. Wire each new module into `forvum-app` in the same milestone, and make
  every `@Startup` bean boot gracefully when its inputs are absent — the CI native smoke runs the binary
  with **no `~/.forvum/`**, so a watcher/loader must warn + no-op (never crash, never block command-mode
  exit) or it fails the smoke. [M4]

- **TamboUI 0.3.0's BOTH terminal backends fail the GraalVM 25 native build — ship NO terminal backend in
  v0.1.** `tamboui-jline3-backend` pulls the `org.jline:jline` uber jar whose bundled JNA provider
  (`JnaNativePty` → absent `com.sun.jna.Platform`) breaks `--link-at-build-time`; `tamboui-panama-backend`'s
  FFM downcall (`LibC.tcgetattr`) is rejected by native-image (`should not reach here: linkToNative`). A
  backend is only needed for terminal-size auto-detection, so `forvum-channel-tui` carries just
  `tamboui-toolkit`+`tamboui-widgets` and renders ANSI through the pure-Java headless `Buffer` (the same
  path the app banner already native-compiles), sized to the fragment's CONTENT width — display-width aware
  so CJK/wide glyphs aren't truncated (`String.length()` is UTF-16 code-units, not terminal cells); the
  terminal wraps long lines. v0.1 is a line-based, pipeable stdin REPL (NOT a full-screen Toolkit app), with
  `--no-ansi` (`forvum.no-ansi`) bypassing TamboUI entirely; terminal-width auto-detection + the full-screen
  Toolkit/`tui.tcss` are deferred to a native-buildable TamboUI backend (TamboUI bump / M20). The TUI is a
  foreground (not server) channel: `ChannelLauncher.FOREGROUND_CHANNELS` + `ForvumApplication.run()` runs the
  REPL in the foreground (returns at stdin EOF) instead of `Quarkus.waitForExit()`. [M15]

- **A fixed ~5 s startup stall on the macOS CI cell is `InetAddress.getLocalHost()`, not the HTTP bind —
  fix the runner's hostname resolution, don't chase the listener.** The native cold-start gate measured
  **~5093 ms** on `macos-14` while Linux CI and a dev Mac stayed ~45 ms. First fix attempt — unbinding HTTP
  for one-shot — did NOT move it (still 5088 ms), which PROVED the stall is independent of the listener: it
  is `getLocalHost()` called at startup by OpenTelemetry's host-resource detector + the Vert.x address
  resolver, and the GitHub macOS runner has no resolvable hostname (a known runner issue), so the reverse
  lookup times out ~5 s. Fix in the workflow: `echo "127.0.0.1 $(hostname)" | sudo tee -a /etc/hosts`
  (+`::1`) on the macOS cell, before the build. Lessons: (1) the cold-start gate on BOTH cells is what
  caught it — a "document the limitation" stance passed 3/4 and would have shipped a broken macOS binary;
  (2) when a fix doesn't move the metric, that's the diagnostic — re-run and read it before assuming the
  cause; (3) a consistent N-second (not jittery) delay is almost always a fixed timeout (DNS/getLocalHost),
  not load. [M20]

- **The native binary could not run a single turn — and "native-COMPILEs + boots" never proved it could.**
  A live native turn (Ollama, the default agent) surfaced TWO native-only gaps that every prior milestone's
  "native build green" had hidden, because the build + the no-config boot never EXECUTE a turn:
  (1) **HTTP client.** A programmatically-built model whose builder is NOT swapped by a Quarkiverse
  factory (Ollama, Gemini — unlike OpenAI/Anthropic) resolves its client via langchain4j's
  `HttpClientBuilderLoader`, whose `ServiceLoader` is **EMPTY in a native image** → the turn dies with
  `"No HTTP client has been found in the classpath"`. The M12 `HttpClientFactorySelector` system property
  only DISAMBIGUATES a multi-factory JVM classpath; it cannot populate an empty native ServiceLoader (the
  M12 "resolve() path identical to JVM" note was wrong — it had never run a native turn). Fix: pin an
  explicit `.httpClientBuilder(new JdkHttpClientBuilder())` (pure-langchain4j JDK `java.net.http`, directly
  instantiated, native-safe) on the un-swapped providers — never the loader. Note the Gemini builder hides
  `httpClientBuilder` on its base class (`BaseGeminiChatModel...Builder`), inherited, not on the subclass.
  (2) **Graph state serialization.** LangGraph4j clones the `GraphState` data map via `ObjectOutputStream`
  on EVERY node step (R6) → native needs each serialized concrete type registered, so the first step throws
  `UnsupportedFeatureError: SerializationConstructorAccessor not found for java.util.ArrayList`. Fix: a
  `@RegisterForReflection(targets={ArrayList.class}, serialization=true)` holder (GraphState holds only
  String + an `ArrayList`-backed `List<String>`). LESSON: the only thing that catches these is an actual
  native turn against a real provider (Risk #5) — keep deferring it and the "single native binary" ships
  unable to converse. Verified locally: `echo '...' | forvum` on the native binary returns a real Ollama
  answer and writes `messages`/`provider_calls`. (Tool-loop/spawn paths add no new serialized types — the
  SCHEMA's only collection channel is the ArrayList appender — but were not separately live-tested.) [M20/Risk#5]

- **Automating the native real-provider turn in CI needs a new `forvum ask` command — `@QuarkusMainIntegrationTest`/
  `@Launch` have NO stdin, so PR #111's `echo '...' | forvum` (the TUI REPL) is unreachable from an IT.** A
  native turn can only be driven out-of-process by a subcommand. `AskCommand` (`forvum ask "<prompt>"`) runs ONE
  turn via the SDK `ChannelTurnDriver` (the engine's `TurnService` — it already binds CURRENT_AGENT/CURRENT_TURN,
  resolves identity, activates the request context, and ledgers the turn, so DON'T re-hand-roll the
  registry/ScopedValue dance) and prints `Done.finalMessage()` to stdout; an `ErrorEvent` → stderr + exit 1, so
  **exit 0 is the real native-turn gate** (a "No HTTP client"/JSON-reflection regression surfaces as
  ErrorEvent → exit 1). `ask` is deliberately NOT in `CommandMode.isOneShotCommand` (the turn needs Flyway/the DB),
  so it boots the full path. Traps found wiring this: (1) **a `@QuarkusMainIntegrationTest`'s `getOutput()` includes
  all boot logs** → `non-blank` is vacuous; route logs to stderr in the IT's `@TestProfile`
  (`quarkus.log.console.stderr=true`) so stdout is the reply alone. (2) **Failsafe does NOT read the Surefire
  `${excludedGroups}` property** — give it its own `<groups>${itGroups}</groups>` + `<excludedGroups>${itExcludedGroups}</excludedGroups>`
  (default `itExcludedGroups=live`); the live opt-in is `-DitGroups=live -DitExcludedGroups=none`. A blank `<groups>`
  is fine (no include filter) but a **blank `<excludedGroups>` makes JUnit discover ZERO tests** (`excludeTags` rejects
  a blank expression) — clear the exclusion with a non-empty no-op tag (`none`), never an empty string. (3) **the
  `@TestProfile`'s `getConfigOverrides()` DO propagate to the launched native binary** as `-D` system properties (the
  IT-launcher applies them) — confirmed by the launch line `-Dforvum.home=...`; so a profile-seeded temp home works
  out-of-process, no FORVUM_HOME env needed. (4) **forvum-app dev mode can't run its tests via the Quarkus Dev MCP**:
  since M20 it's a `@QuarkusMain` CLI that runs the command and EXITS on boot (no server channel → `RootCommand.call()`
  returns), so `quarkus:dev` shuts down immediately and the Dev-UI test runner gets "No CDI container available". Run
  forvum-app's `@QuarkusMainTest` JVM tests via **Surefire** (`./mvnw -pl forvum-app -Dtest=… test`), same family as the
  native `*IT` Failsafe step — a de-facto §4 exception for the CLI app. CI: a linux-only `native-turn` job
  (`services: ollama/ollama`, pull `qwen2.5:0.5b` via the HTTP `/api/pull` with retry, then
  `./mvnw -B -Pnative verify -DitGroups=live -DitExcludedGroups=none`); the binary reaches the service at the default
  `quarkus.langchain4j.ollama.base-url=http://localhost:11434` (a `-D` on `mvnw` would NOT reach the out-of-process
  binary, so map the service port instead). The two-cell `native` job (boot smoke + 200 ms cold-start) stays mandatory
  and unchanged. [Risk#5]

