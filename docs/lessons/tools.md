# Implementation lessons — Tools & plugins

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **A milestone's roadmap "Files" list can be stale in BOTH directions — verify on disk before scaffolding.**
  M13's ULTRAPLAN §7.1 / ISSUES.md "Files" listed `PermissionScope.java (enum)` under `engine/tools/`, but
  the enum already lived in `forvum-core` (M2) and the §4.3.4 prose said so — scaffolding it in the engine
  would have duplicated a Layer-0 type. Likewise `tool_invocations` was already a V1/M5 table (+ entity), so
  M13 added ZERO migrations — only the write seam (`ToolInvocation` DTO + `ToolInvocationRecorder` +
  `PanacheToolInvocationRecorder`, a verbatim mirror of the `ProviderCall` triad). Grep the codebase for
  every type a milestone's Files list names before creating it; the contract often already exists. [M13]

- **The tool SPI follows the M7 prelude-in-consumer-PR pattern, contribution-only.** `ToolProvider` was an
  `extensionId()`-only stub; M13's engine `ToolRegistry` consumes it, so the prelude method
  `List<ToolSpec> tools()` lands in `forvum-sdk` as M13's first commit (M14 implements it) — exactly as M7
  added `ModelProvider.resolve()` ahead of M9. It carries only `forvum-core` types (no langchain4j
  `ToolSpecification`, no execute method — dispatch is the engine's `ToolExecutor` / M18's `tool_loop`), so
  `forvum-sdk` needs NO new dependency and stays Quarkus-free. The permission model is belt-membership: a
  persona's `allowedTools` globs select the belt (`ToolFilter`), and a tool outside the belt is refused by
  `ToolExecutor` with `PermissionDeniedException` + an audited `denied` row — there is no ad-hoc elevation
  path. `ToolExecutor`/`AgentToolBelt.tools()` have no production caller in M13 (not wired into
  `Agent.respond()`); the model-request wiring is M18. [M13]

- **A tool module is the provider Layer-3 recipe minus the langchain4j extension.** `forvum-tools-filesystem`
  (the first tool module) copies `forvum-provider-ollama`'s pom verbatim and drops the
  `quarkus-langchain4j-*` dependency — a filesystem tool is `java.nio` + `quarkus-arc` only; the enforcer
  allowlist (`forvum-sdk` + `forvum-core`) is unchanged because `ToolSpec`/`PermissionScope` are Layer-0.
  The provider (`@ForvumExtension @ApplicationScoped extends AbstractToolProvider`) implements the M13
  `tools()` SPI — contribution-only, so it declares `ToolSpec`s but does NOT run the tools. The tool
  classes (`Fs{Read,Write,List}Tool`) carry the `java.nio` logic and are tested directly (`@TempDir`
  round-trip); their engine-wired execution is M18. Path confinement is a self-contained `WorkspaceRoot`
  (`normalize` + element-wise `startsWith`, so a sibling `<root>-evil` is rejected) throwing
  `WorkspaceEscapeException` — distinct from M13's capability-scope `PermissionDeniedException` (a tool
  plugin can't depend on the engine), and the full DR-6a threat-model contract is deferred. Wire the
  module into the three append-only poms (root `<modules>`, `forvum-bom`, `forvum-app`) in the same
  milestone so it native-compiles + registers at app startup. [M14]

- **Bundle Maven Resolver via `maven-resolver-supplier` (the no-DI bootstrap), not the Guice/Sisu path.**
  P2-6 (`forvum plugin install <coords>`) resolves a coordinate against `~/.m2` + Central. The 1.9.x
  `org.apache.maven.resolver:maven-resolver-supplier` hand-wires a `RepositorySystem`
  (`RepositorySystemSupplier().get()` + `MavenRepositorySystemUtils.newSession()` from the transitive
  `maven-resolver-provider`; 1.9.x uses `DefaultRepositorySystemSession` + `LocalRepository` +
  `system.newLocalRepositoryManager`, NOT 2.x's `SessionBuilderSupplier`/`getPath()` — `ArtifactResult.
  getArtifact().getFile()` in 1.9.x), so it pulls NO Guice/CGLib (only httpclient + maven-model + plexus-utils)
  — clean for the import-grep ban. NATIVE containment: these classes ride the `forvum-app` native classpath
  but RUN only in the fast-jar drop-in path; keep them inert by (a) `@ApplicationScoped` resolver (lazy — never
  instantiated unless `install()` runs), (b) referencing aether types only inside method bodies, never as a
  field/`@Startup` work, (c) registering NOTHING for reflection (the supplier needs no ServiceLoader/reflection).
  The drop-in dir is JVM-fast-jar-ONLY BY DESIGN (§6.2/§6.3), so the command warns + still stages the JAR when
  `ImageMode.current() == NATIVE_RUN` (the running-native constant; `NATIVE` does not exist on `ImageMode`).
  Test the resolve+stream path HERMETICALLY against a `file://` remote seeded with a tiny jar+pom in a
  `@TempDir` (no network/`~/.m2` flake); make `plugin` a `CommandMode` one-shot (it only resolves+writes). [P2-6]

- **`maven-resolver-supplier` pulls a SPLIT-version transitive graph — pin the whole set in the BOM.**
  Depending only on `maven-resolver-supplier:1.9.27` resolves `named-locks`/`transport-file`/`transport-http`
  at 1.9.27 but `api`/`spi`/`impl`/`util`/`connector-basic` at 1.9.25 (a mismatched packaged app). Add explicit
  `dependencyManagement` for the api/spi/impl/util/connector-basic set at `${maven-resolver.version}` so supplier
  runs against a matching impl; verify the exact patch exists on repo1.maven.org first (solrsearch is stale). [P2-6]

- **Resolve-from-the-fast-jar throws a loader-constraint `LinkageError` until the resolver artifacts are
  parent-first.** Maven Resolver spreads its `org.eclipse.aether.*` packages across several JARs; under the
  Quarkus runtime classloader they split across loaders, so `RepositorySystemSupplier.get()` fails wiring
  `Maven2RepositoryLayoutFactory` against `ChecksumAlgorithmFactorySelector` (different `Class` objects).
  Fix with `quarkus.class-loading.parent-first-artifacts=<the whole resolver set + org.apache.maven:maven-resolver-provider>`
  so one loader owns every aether class — a classloading directive only (no eager init; stays native-inert). The
  plain-JVM engine unit test (single classloader) CANNOT catch this; only an in-JVM `@QuarkusMainTest` success
  path that actually reaches `RepositorySystemSupplier.get()` does — so the resolve+stream CLI MUST be tested
  end-to-end through the Quarkus runtime, not just at the engine unit level. [P2-6]

- **The MCP bridge MUST build its transport from the Quarkiverse `QuarkusHttpMcpTransport`, NOT the
  standalone langchain4j `HttpMcpTransport` — the latter drags in OkHttp's `okhttp-sse`, which is absent
  from the classpath AND not native-friendly.** P2-13's `forvum-tools-mcp-bridge` is the provider Layer-3
  recipe (`forvum-sdk` + `quarkus-arc` + `quarkus-langchain4j-mcp`, no `build` goal, copied enforcer
  allowlist) surfacing `mcp-servers/<id>.json` servers as `mcp.<server>.<tool>` `ToolSpec`s carrying
  `PermissionScope.MCP_REMOTE` (DR-6b §9.3 — remote specs are UNTRUSTED, behind belt + the P2-11 RBAC
  scope gate). The §7 mandate ("the Quarkiverse extension, NOT the beta") is load-bearing, not advisory:
  `new dev.langchain4j.mcp.client.transport.http.HttpMcpTransport.Builder().build()` constructs an OkHttp
  `SseEventListener` → `NoClassDefFoundError: okhttp3/sse/EventSourceListener` at connect time. Use
  `io.quarkiverse.langchain4j.mcp.runtime.http.QuarkusHttpMcpTransport.Builder` (Vert.x, native-ready,
  the extension's own `McpRecorder` builds clients the same way) — its API differs (`headers(Map)` not
  `customHeaders`, plus `mcpClientName`); it `implements McpTransport`, so it still feeds
  `new DefaultMcpClient.Builder().transport(...)`. THE TEST TRAP: that `NoClassDefFoundError` is an
  `Error`, so it escapes the provider's best-effort `catch (RuntimeException)` and CRASHES boot
  (`ToolRegistry.onStart` → `tools()` → connect) — but the no-config wiring IT NEVER connects (empty
  `mcp-servers/`), so it ships green. Only a test whose home HAS a server exercises the real transport
  build; the multi-launch app `McpCommandTest` catches it because its shared `forvum.home` accumulates
  server files, so the 2nd+ launch's boot connects. Keep the SOLE langchain4j-touching class
  (`DefaultMcpClientFactory`) behind a `McpClientFactory` seam so the provider + its units stay
  langchain4j-free (fake factory), and jacoco-exclude that adapter (live-server-only, qdrant/telegram
  policy). [P2-13]

- **Materializing MCP tools is a SYNCHRONOUS network call, so `ToolRegistry.onStart` is NO LONGER the
  "cheap side-effect-free" observer M20 left ungated — gate it on one-shot, and re-materialize `mcp list`
  on demand.** Listing a server's tools is a connect + `listTools` round-trip; if `onStart` materializes
  unconditionally, EVERY one-shot (`--help`/`--version`/`init`/`doctor`/`plugin`/`skill`/`mcp add`) blocks
  at boot up to the connect timeout PER configured server on a machine with `mcp-servers/*.json` — the
  pre-merge review's headline finding, invisible to the CI `<200 ms` gate (it runs with no `~/.forvum/` →
  zero connects). Fix: `ToolRegistry.onStart` now returns early when `commandMode.isOneShot()` (inject the
  engine `CommandMode`); `mcp list` (a one-shot) re-materializes by calling the bridge directly
  (`McpBridgeToolProvider.tools()`), so the connect cost is paid only when the operator asks to list. Test
  BOTH directions (one-shot boot → registry empty; normal boot → materialized) — the [M20] discipline.
  Keep a tunable `forvum.mcp.connect-timeout-seconds` (default 5) so a normal server boot can't hang long
  on a down server. **The resync swap MUST be atomic:** hold `(tools, owners)` in ONE `volatile` `Index`
  record swapped in a single write — a `clear()`+`putAll()` across two `ConcurrentMap`s lets a concurrent
  turn read a half-rebuilt registry and `ToolCallBridge` then throws belt/registry-divergence on a tool
  that exists identically before and after (the review's second major). The engine-side registry wiring is
  the 5-edit recipe BUT `ForvumHome.mcpServers()` + `ConfigWatcher.WATCHED_SUBFOLDERS` were already
  pre-declared; the only new engine code is `ToolRegistry.onConfigChange` (filtered to `mcp-servers/`).
  A multi-launch `@QuarkusMainTest` over the bundled Web channel must set `quarkus.http.host-enabled=false`
  in the test profile (the launcher drives `QuarkusApplication.run()`, not the static `main` that unbinds
  HTTP for a one-shot) so the sequential launches don't contend for the listener. Redact secret material
  (`userinfo`/query) from a server URL before `mcp list` prints it (the Telegram never-log-a-secret-URL
  lesson). [P2-13]

- **`forvum-tools-shell` (`shell.exec`) is the filesystem-tool recipe + a ProcessBuilder LIST-form launcher,
  and it is the FIRST `userConfirmRequired=true` tool — it consumes the merged #39 gate with ZERO new
  approval code (the flag is the whole opt-in).** The allowlist (`ShellAllowlist`) reads `tools/shell.json`
  on demand (the `QdrantConfig` JsonNode tree-walk, no Jackson reflection → no `@RegisterForReflection`),
  default EMPTY = fail-closed; argv is LIST-form (never `sh -c`), env scrubbed to `{PATH,HOME,LANG}`,
  workingDir confined by the in-place-HARDENED `WorkspaceRoot` (lexical + `toRealPath` link-resolving +
  NOFOLLOW write — the §9.2.5/#27 obligation; NOT promoted to a shared layer, so shell + filesystem each
  carry a same-named-but-distinct `WorkspaceRoot`, since a Layer-3 plugin can't depend on a sibling).
  shell.exec needs argv as a JSON-schema `type:array`, so #27 OWNS the one engine seam it adds:
  `ToolCallBridge` `objectSchema/addProperty` gains array-of-scalar-string handling (the only PR-6 tool
  needing it — browser/web args are scalar/object). **A shell-tool-internal refusal (allowlist miss, invalid
  argv, workspace escape) audits `error`, NOT `denied`** — it is a thrown exception the engine's generic
  post-action catch records; `DENIED` is the engine's pre-action belt/RBAC/approval verdict, which a Layer-3
  tool cannot emit (DP-9 reworded; consistent with the WorkspaceRoot-escape decision). **shell.exec cannot
  be exec-tested in a headless native IT:** every non-interactive turn entry binds `NON_INTERACTIVE`, so the
  #39 gate denies a confirm-required tool without exec'ing it — native proof = the binary native-COMPILEs +
  boots with the module + JVM `ShellExecutorTest` launching REAL processes + the GraalVM Process substrate;
  an auto-approving native harness is a documented fast-follow (the 6-dim review REFUTED that the IT is
  required). [P2-2/#27]

- **`ShellExecutor` robustness, caught by the 6-dim review:** close the child's stdin right after
  `start()` (`process.getOutputStream().close()`) or a command that reads stdin with no operand (`cat`,
  `grep PATTERN`) blocks until the timeout; and BOUND the post-settle `drain.join(graceMillis)` — an
  unbounded join hangs the turn forever if a double-forked/reparented descendant escapes `descendants()`
  and keeps the merged-output write end open (`destroyForcibly` is async + the snapshot misses orphans).
  The drain runs on a virtual thread (no `synchronized`, result via `AtomicReference`). [P2-2/#27]

- **`web.fetch` SSRF defense is layered and the restricted-header latch is a `main()`-time set, not a
  lazy static.** `forvum-tools-web` (resolving the epic-#4 L680 TBD) hardens egress per the
  maintainer-chosen E-ii: redirect-`NEVER` + per-hop re-validate (a `Redirect.NORMAL` client silently
  follows a 30x to a private IP); full IPv4+IPv6 private/reserved coverage (`fc00::/7` ULA, the
  `::ffff:0:0/96` IPv4-mapped variant, `0.0.0.0/8`, `240.0.0.0/4`), HTTP IP-pin (connect to the
  validated literal IP with a `Host` header), a port allowlist, and a connect-time re-resolve+re-check
  for the DNS-rebind TOCTOU. The trap: `jdk.httpclient.allowRestrictedHeaders=host` must be set in
  `ForvumApplication.main` (before any HttpClient is built) — a lazy static block in the fetcher LOSES
  the latch race to an LLM turn's JDK `HttpClient`, after which the JVM caches the restricted-header set
  and the `Host`-pin silently no-ops. Pin the `Host`-header behavior with an end-to-end test against a
  loopback server, not just a unit assertion. Residual HTTPS-rebind (the cert validates the rebound
  host) is documented and tracked to a shared engine egress decorator. [TOOLS-WEB]

- **A CDP `@WebSocketClient(path="/")` endpoint COLLIDES with another fixed-path WS client on the
  assembled app classpath — use `BasicWebSocketConnector` with the full URI.** `forvum-tools-browser`'s
  `CdpEndpoint(path="/")` clashed with `DiscordGatewayEndpoint(path="/")` only on the combined
  `forvum-app` classpath (a single-module build never sees it, the [M12] combined-verify lesson), and the
  typed `WebSocketConnector` concatenates its annotated path into the dynamic CDP `baseUri` ([M16]). Dial
  CDP via `BasicWebSocketConnector.create().baseUri(fullWsUrl)...` — no endpoint class, no path, no
  collision. Two review bugs worth generalizing: a `clearLoadEvents()` that was authored but never wired
  let a stale `Page.loadEventFired` satisfy a 2nd `navigate`'s wait instantly (wire the reset INTO
  navigate); and a `CdpSession.send` that caught only checked exceptions leaked the pending-future +
  bypassed graceful relay when Mutiny's `sendTextAndAwait` threw a raw `RuntimeException` (add a
  `RuntimeException` arm). Live-validate against an operator Chrome (`--remote-debugging-port`); keep the
  end-to-end Chrome run `@Tag("live")`. [#26]

- **A pluggable web-search backend is a MODULE-INTERNAL seam (not a public SDK SPI) + an HTML-scrape keyless
  default, and its single most dangerous edge is flipping `web.search` from inert to network-on-invoke.** #192
  added a package-private `WebSearchBackend` interface INSIDE `forvum-tools-web` (the issue BINDS the placement
  — swapping the search provider is an implementation detail, not a new Layer-1 contract), with two impls:
  `DuckDuckGoBackend` (keyless HTML scrape of `html.duckduckgo.com/html`, the DEFAULT so search works with no
  key) and `BraveBackend` (the existing keyed `BraveSearchApi`). Precedence (issue wording): an explicit
  `tools/web.json` `"backend"` wins, else a `braveApiKey` selects `brave`, else `duckduckgo`. Load-bearing
  decisions/traps: (1) **The keyless default is the trap** — with no config `web.search` now dials the internet,
  so the module's own no-config `@QuarkusTest` wiring IT (which INVOKED `web.search` and asserted "not configured")
  would make CI dial DDG. RE-POINT it: seed `tools/web.json = {"backend":"brave"}` (no key) so the invoke resolves
  to the config-shaped message with ZERO network (grep for every `web.search` invoker before merging — engine hits
  are name-only belt-glob fixtures, unaffected). Boot inertness is UNCHANGED (config read on demand, no `@Startup`),
  only default-leg *invocation* changed. (2) **Never a naive `new HttpClient` ([TOOLS-WEB])** — the DDG backend
  reuses the module's `EgressGuard`(strict, `allowPrivateNetwork` NOT honored since the host is fixed public) +
  `HttpFetcher`, composing the URL from the FIXED host + `URLEncoder.encode(query)` (the encode IS the input
  sanitization — the model query can only ride as an encoded param), with a bounded ≤3-redirect loop mirroring
  `WebFetchTool` (per-hop guard re-check + HTTPS→HTTP downgrade refusal). The **downgrade refusal fires BEFORE the
  private-IP check**, so a test meaning to prove the guard denies a loopback redirect must target `https://127.0.0.1`
  (HTTPS) — an `http://` loopback redirect is refused as a downgrade first (a real ordering finding the test caught).
  (3) **Per-request header override** via a new `default FetchResult get(approved, Map headers)` on `HttpFetcher`
  (the [#166] default-method recipe, so the two existing test fakes compile unchanged); `JdkHttpFetcher` overrides it
  applying extras with `setHeader` (REPLACE, not `header`/append) so the DDG browser-UA (OpenClaw parity — lowers the
  challenge rate on `html.duckduckgo.com`) supplants the honest `Forvum/…` UA on THIS request only. (4) **ZERO new
  reflection surface** — DDG is HTML→String, so `SearchResult` is a plain internal record deliberately OUTSIDE the
  `.dto` package with NO `@RegisterForReflection` (the `EgressGuard.Approved` precedent; keeps
  `.github/reflection-registration.sh`, which greps `.dto.` records, clean). The parser is a faithful Java port of
  OpenClaw's `ddg-client.ts` regex contract (`result__a`/`result__snippet`, `uddg` unwrap incl. scheme-relative
  `//`, entity decode, tag strip, `isBotChallenge` = challenge markers only when NO `result__a`). (5) **Degrade
  contract:** config-shaped (Brave-no-key / unknown backend) RETURNS an actionable message + no network; runtime
  (non-200 / bot-challenge / zero-parse-from-nonempty = markup drift / redirect cap) THROWS `WebSearchException`
  (audited `error`, rendered to the model, turn completes); a genuine `no-results` page returns `"no results."`.
  Fixtures cover results/no-results/challenge/DRIFTED markup ([M4] fixture-realism), not just the happy page. (6) The
  trailing `Optional<String> searchBackend` on `Spec` gets a 3-arg delegating ctor so the 8+ existing `new Spec(...)`
  sites compile unchanged ([#170]); `backend` parsing is LENIENT (raw string, resolved only on the search path) so a
  bad value can't break `web.fetch`, which shares `read()`. (7) `javac` REJECTS `catch (NumberFormatException |
  IllegalArgumentException)` — NFE is a subclass of IAE, so a multi-catch relating them fails to compile; catch the
  supertype alone (a "verify the real BUILD SUCCESS/FAILURE, not the pipe exit" catch — a green notification hid a
  compile failure). (8) **Native + live-test placement** ([M20/Risk#5], [OQ2]): native proof is the local `-Pnative`
  build + boot-inert + ONE manual live keyless run against the binary with evidence in the PR — the live keyless
  search is a module `@Tag("live")` test (nightly/manual, scheduled by #181), NOT the per-PR `native-turn` job (a
  third-party endpoint that challenges datacenter IPs would make that gate permanently flaky). **The `live`
  exclusion MUST be a POM `<properties><excludedGroups>live</excludedGroups></properties>` Surefire USER property
  (the forvum-tools-browser precedent), never a literal inside the plugin `<configuration>`** — an explicit plugin-XML
  value is CLI-un-overridable (Maven ignores `-D` for an XML-set parameter), so the documented opt-in
  `-DexcludedGroups= -Dgroups=live` silently runs ZERO tests and the live test is unrunnable as shipped (qdrant
  carried the hardcoded form — swept to the property form by #181; telegram was already clean, no
  `excludedGroups` anywhere). (9) A `sed -i.bak`+`mv`
  red-check restore PRESERVES the old mtime, so Maven's incremental compiler keeps the MUTATED class in
  `target/classes` — `touch` the source (or clean the module) after restoring, or the next build runs the mutant. [#192]

- **Strict plugin checksums are BELT-AND-BRACES (session global + per-remote FAIL), and the FAIL policy
  rejects MISSING checksums too — so the existing hermetic fixtures ENCODED the vulnerability and the
  no-`.sha1` shape becomes the negative test.** #171 hardened `forvum plugin install` (a JVM drop-in runs
  in-process with core-equivalent authority): `MavenPluginResolver` sets `CHECKSUM_POLICY_FAIL` on BOTH
  `session.setChecksumPolicy` (a GLOBAL override that rejects a tampered artifact even through a
  default-`warn` remote — load-bearing for the test-injected `file://` remotes, which carry the default
  policy) AND every remote's release + snapshot `RepositoryPolicy` (explicit in code, acceptance #2). FAIL
  aborts on a MISSING checksum, not just a mismatch (probed), so both hermetic fixtures — which wrote a
  `.jar`+`.pom` but NO `.sha1` and passed only because `warn` tolerates it — flip RED; the fix is
  fixture-side (write a valid `.jar.sha1` via `MessageDigest`/`HexFormat`) and the old no-sha1 shape is
  promoted to the `missingChecksumIsRejected*` negative test (the [#176] rewrite-the-test-that-encoded-the-bug
  arc). NEGATIVE-TEST TRAP: a cached artifact resolves with NO checksum re-verification (the operator's own `~/.m2` disk is
  trusted — the local-cache boundary), so a tampered-remote test reusing a warm cache passes for the wrong
  reason — use a FRESH local-cache `@TempDir` per negative case (the per-test dirs already comply). A
  concrete-version `resolveArtifact` does NOT fetch the `.pom`, so only the `.jar.sha1` governs. RED-CHECK:
  removing the session policy line flips BOTH the tampered AND missing tests to "nothing was thrown" (the
  tampered artifact RESOLVES = the live bug). The scheme allowlist is `{https, file}` (plaintext `http://`
  = MITM downgrade, rejected; `file://` retained for local mirrors + the sanctioned hermetic-test path
  through the PRODUCTION `forvum.plugins.repository-url` — checksum FAIL still applies to it, so banning it
  would force a test-only bypass seam). Diagnostics redact `://user:secret@` via a small `redact()`.
  Owner-only install is a package-private `PluginArtifactInstaller` (the `StateDirInitializer` shape,
  module-own copy of the `POSIX`/`0700`/`0600` recipe + a `boolean posix` overload): stream the bytes into a
  `0600`-at-birth temp file in the SAME dir (`Files.copy(REPLACE_EXISTING)` would RECREATE it with umask
  perms — stream into the temp inode via `newOutputStream(WRITE, TRUNCATE_EXISTING)`) then `ATOMIC_MOVE`
  (never a partial JAR; a failed install never touches the previous valid JAR — only the atomic move does),
  rejecting a symlinked dir/target. FAIL POLICY diverges from #173's boot-time repair-and-warn: an
  interactive one-shot installer staging executable code is NOT the M4 graceful-boot contract, so an
  un-tightenable loose dir / a symlink FAILS the install fail-closed (exit 1 + remediation), mirroring
  `SkillInstaller`. Write failures now throw a new engine `PluginInstallException` (the old `streamInto`
  wrapped them in `UncheckedIOException`, which ESCAPED the CLI's catch and stack-traced) caught alongside
  `PluginResolutionException` in one CLI multi-catch. `plugins/` is deliberately not read at boot, so
  install-time is the enforced gate (no startup observer — §13 simplicity; a future drop-in scanner must
  re-validate). Zero new native surface (pure `java.nio` + method-body-only resolver refs, the P2-6
  inertness invariant preserved), zero new config, zero new deps. [#171]

- **A model-callable TTS tool is the shell/filesystem Layer-3 recipe + the VOICE channel's subprocess pair
  (NOT shell's) — because the two copied recipes differ on the ONE thing that matters: stdin.** #186's
  `forvum-tools-tts` (`tts.speak`) lifts piper out of the voice CHANNEL into an ordinary turn's tool
  surface. The load-bearing copy choice: `ShellExecutor` closes the child's stdin IMMEDIATELY after start
  (it feeds nothing — a `cat`/`grep` with no operand must see EOF at once), whereas piper is stdin-FED (the
  text to synthesize rides on standard input) — so the base is the voice `DefaultSubprocessRunner`'s
  write-the-text-then-close (try-with-resources on `getOutputStream()`, IOException on early child exit
  tolerated), NOT a blind `ShellExecutor` copy (which would synthesize EMPTY audio with exit 0). The #186
  delta on the voice runner is the ShellExecutor env scrub (`environment().clear()` + re-add
  `{PATH,HOME,LANG}`); everything else (concurrent VT drains via `AtomicReference`, kill-tree, the bounded
  post-settle `join(DRAIN_GRACE_MILLIS)` so an escaped/reparented descendant holding the pipe cannot hang
  the turn) is copied verbatim. A Layer-3 plugin cannot depend on a sibling, so the runner pair + the
  `WorkspaceRoot`-style resolution are COPIED per the established per-module convention ([#173]). New
  `PermissionScope.MEDIA_SYNTHESIZE` (append-only enum → `PermissionScopeTest` count 9→10 + a round-trip
  case is the only pin — the [#167] enum-append-ripple; `default-user`'s `EnumSet.allOf` auto-grows).
  `tools()` returns a CONSTANT SPEC (zero boot IO, [P2-13]); config (`tools/tts.json`: `piperBin` +
  `piperVoice` + an optional `voices` name→path map) is read on demand into a hand-parsed record (no
  `@RegisterForReflection` — the voice `Spec` precedent), inert (an actionable "not configured" error) when
  absent. `userConfirmRequired=false` (fs-write-class: operator-fixed program/argv/voice/output, model
  controls only the stdin text + a config-resolved voice NAME — a raw model-supplied `.onnx` path would be
  an untrusted-path-to-subprocess surface, which the name→path map forecloses). Output is a generated
  collision-free name (`speech-<yyyyMMdd-HHmmss>-<8 hex>.wav`) under a FIXED `<workspace>/tts/` subdir, so
  there is no model-supplied path to confine (copying the fs/shell two-stage `WorkspaceRoot` confinement
  would be machinery for an impossible input, §2). The synthesizer is pure (runner + workspace-root
  ctor-injected) so a `FakeRunner` that records argv/stdin/timeout AND writes bytes to the `-f` path drives
  every branch hermetically; the real piper round-trip is a `@Tag("live")` test excluded via the POM
  `<excludedGroups>live</excludedGroups>` USER property (never a plugin-XML literal — CLI-un-overridable,
  [#192]), never gating the native compile (the shell/voice posture). **NATIVE TRAP the local `-Pnative`
  build caught (green JVM `verify` hid it):** a `private static final SecureRandom RANDOM = new
  SecureRandom()` for the unique file-name suffix is initialized at class-init and BAKED INTO the image
  heap — native-image FAILS with `UnsupportedFeatureException: Detected an instance of Random/SplittableRandom
  class in the image heap` (its cached seed would make it non-random at run time). A static
  `Random`/`SecureRandom` field is a native-image no-go; use `UUID.randomUUID()` (its `SecureRandom` is a
  run-time-initialized holder INSIDE `UUID`, not a field on your class) or create the instance at call
  time. The [M14] rule stands — a Layer-3 module only native-COMPILEs once `forvum-app` depends on it, so
  RUN the local `-Pnative` build in the same PR; the JVM suite cannot catch an image-heap constraint. [#186]

- **A "usable default tool belt" is TWO fixes — widen the scaffold belt AND wire an identity — because a
  fresh-init home resolves anonymous and the whole belt is filtered before it ever reaches the model.** #184's
  headline symptom ("no tools") had a non-obvious second cause: `init` scaffolded an fs-only belt, but even
  that was invisible because `main.json` had no `identityId`, so `IdentityResolver` fell back to the
  `ANONYMOUS_IDENTITY` (empty scopes) and `SupervisorGraph.scopeVisibleBelt` filtered EVERY tool out of the
  model-facing set (the `ToolExecutor` denies a coerced call anyway). The scaffold fix is BOTH: a widened
  `DEFAULT_ALLOWED_TOOLS` (only tools that WORK or degrade to user-caused config guidance with zero setup —
  `fs.*`, `web.fetch`/`web.search` keyless via #192, `memory.*` local via #175; NOT the confirm-gated /
  fail-closed / dependency-needing `shell.exec`/`sandbox.run`/`browser.*`/`tts.speak`/`mcp.*`) PLUS
  `"identityId": "default"` pointing at the already-scaffolded `identities/default.json` (no `roles` → the
  permissive `default-user` `EnumSet.allOf`, #168). Both files are written in one `InitCommand.call()`, so the
  fail-closed `IdentityResolutionException` (an `identityId` naming a missing file) can never arise from a
  fresh scaffold — do NOT reorder the writes behind conditionals. Explicit tool IDs, not globs: a future
  binary shipping a tool matching `fs.*`/`web.*` must not silently widen the default belt. The executable
  proof is two turn tests through the real `TurnService → SupervisorGraph`: WITH the identity wiring the first
  `ChatRequest.toolSpecifications()` includes the belt tools; WITHOUT it, only the engine built-in
  `spawn_worker` is offered (`generate()` ALWAYS appends `spawn_worker`, so the anonymous assertion is
  "the belt tools are absent", never "the offered set is empty"). [#184]

- **Discoverability (`forvum tools`) gathers from `Instance<ToolProvider>`, NOT the registry — and MUST skip
  the MCP bridge instance — because a one-shot deliberately leaves the registry unmaterialized.** #184's
  `forvum tools` (and the `forvum doctor` belt-gap check) is a `CommandMode` one-shot, so
  `ToolRegistry.onStart` returns early and the registry is empty; both surfaces discover directly through CDI
  via a shared `ToolInventoryCollector`. It skips the concrete `McpBridgeToolProvider` by `instanceof` (the
  [M12] `.class` discipline, never a string) because `McpBridgeToolProvider.tools()` is a blocking network
  connect per configured server (P2-13) — configured MCP servers are listed from the `mcp-servers/` FILES
  (URL through `McpListCommand.redactUrl`) pointing at `forvum mcp list` to materialize. A present-but-
  unconfigured tool self-describes via a NEW `ToolProvider.configGaps()` SDK default method (empty = all
  ready): the tool-id → config-field mapping lives in the OWNING module (each override reads its own on-demand
  config offline — no network, no reachability probe, never a config VALUE), so `forvum tools`, `doctor`, and
  the tool's own "not configured" runtime message cannot drift. Reuse the existing selection logic rather than
  re-deriving it (web.search's brave-no-key / unknown-backend gap shares `WebSearchTool`'s precedence helper).
  Belt membership is reader-as-oracle (`AgentSpecReader` + `ToolFilter`), so `-`/`yes`/`no` agrees with how the
  engine actually filters. Zero new native reflection surface: printed text only, `configGaps` is an interface
  default, no new DTO/serialization. **CDI TRAP** ([M7] proxy discipline): a test that reads a captured field
  off an `@ApplicationScoped` provider (e.g. a scripted model recording its `ChatRequest`s) sees the client
  proxy's always-empty field — expose the capture through a METHOD so the call is dispatched to the contextual
  instance. [#184]

