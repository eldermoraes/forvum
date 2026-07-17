# Implementation lessons — Model providers

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **The SPI method a plugin implements lands in its first CONSUMER's milestone, not the plugin's.**
  `ModelProvider.resolve(ModelRef)→ChatModel` is consumed by M7's `LlmSelector` and implemented by M9's
  Ollama provider; since M7 merges first, the method + a versionless `langchain4j-core` dep land on
  `forvum-sdk` in the M7 PR (else M7 cannot compile). The SDK enforcer governs only the `ai.forvum:*`
  namespace, so adding a non-Forvum dep needs no enforcer change, and `forvum-sdk` stays Quarkus-free
  (LangChain4j is not Quarkus). [M7]

- **Select the LangChain4j HTTP client factory once, app-wide — the multi-factory conflict is silent until
  the full app classpath and hits EVERY programmatically-built model, not just one.** `forvum-app` carries
  TWO `dev.langchain4j.http.client.HttpClientBuilderFactory` services at once (`JaxRsHttpClientBuilderFactory`
  via ollama/gemini + `JdkHttpClientBuilderFactory`, a transitive of several langchain4j model libs e.g.
  anthropic). A model whose builder is NOT swapped by a Quarkiverse builder-factory — **both Gemini AND
  Ollama** (unlike OpenAI/Anthropic, whose `builder()` IS swapped to the Quarkus REST client) — falls through
  to `HttpClientBuilderLoader.loadHttpClientBuilder()`, which `ServiceLoader`s the classpath and throws
  `IllegalStateException("Conflict: multiple HTTP clients ...")` at `build()` time unless the
  `langchain4j.http.clientBuilderFactory` system property names a factory. (Latent on `main` since M10 added
  the JDK factory: every `ollama:<model>` turn on the assembled binary would have thrown.) Fix at the
  assembly layer: a `@Observes StartupEvent` bean in `forvum-app` (`HttpClientFactorySelector`) sets that
  system property to `JaxRsHttpClientBuilderFactory` once — Quarkus REST client, the same stack the swapped
  siblings use. (Trade-off: per-provider `.httpClientBuilder(...)` pins are self-contained but distributed —
  each new un-swapped provider must remember one, and the first attempt pinned only Gemini and missed Ollama;
  the app-wide selector is central but makes the contract cross-layer, so document the dependency on each
  provider.) The loader reads `System.getProperty` (not MP Config) lazily at first `build()` (= first turn,
  after boot), so a startup observer is early enough; set-only-if-absent leaves an operator `-D` override.
  Native build + no-config boot are verified; the `resolve()` path (System.getProperty + ServiceLoader) is
  identical to the JVM (a live native turn is nightly/M20). The trap: a provider-module contract test passes
  (single-factory classpath) and the only app-classpath exerciser is a `@Tag("live")` e2e (default-off), so it
  ships green — guard with a NON-live `@QuarkusTest` in `forvum-app` that `resolve()`s EVERY provider
  (`build()` alone throws; no key/network), which also catches a future un-swapped provider or a missing
  factory; name the factory via `.class` so a rename is a compile error. [M12]

- **The `quarkus-langchain4j-ai-gemini` extension fails the no-config native boot eagerly** — its
  deployment recorder (`AiGeminiRecorder#throwIfApiKeysNotConfigured`) throws a `ConfigValidationException`
  while constructing the auto-registered default ChatModel synthetic bean at startup when no api-key is
  set (the api-key mapping is itself `Optional<String>`; the eagerness is the recorder's). OpenAI/Anthropic
  are lazy by contrast. Remedy: a placeholder `quarkus.langchain4j.ai.gemini.api-key=unset` default across
  all profiles (the CI native smoke runs the prod profile with no `~/.forvum/` and no key). Forvum never
  uses the extension's own bean (it builds the model programmatically), so the placeholder only defers a
  real-key failure to call time. [M12]

- **A GitHub Copilot provider is the OpenAI recipe + a decoupled device-code OAuth login — and because
  `OpenAiChatModel.builder()` is the Quarkiverse-SWAPPED builder, it needs NO `JdkHttpClientBuilder` pin and
  NO `langchain4j-http-client-jdk` dep (unlike Ollama/Gemini).** `forvum-provider-copilot` (#42): the auth
  flow (confirmed from the OpenClaw source — `CLIENT_ID Iv1.b507a08c87ecfe98`, `device/code` + poll,
  `copilot_internal/v2/token` Bearer exchange, `proxy-ep`→base proxy.*→api.*, IDE headers, `expires_at`
  sec/ms) lives behind a langchain4j-free `CopilotHttp` seam (`JdkCopilotHttp` = pure `java.net.http`) so
  `CopilotAuth` is fully unit-testable with a scripted fake + injected sleeper/clock. `CopilotCredentials`
  stores the long-lived GitHub token `0600` at `state/credentials/github-copilot.json` and caches the
  short-lived Copilot token in memory (5-min margin) — re-exchanged at most once per ~25-min lifetime, never
  per turn. **The build trap:** `OpenAiChatModel.builder()...build()` throws `IllegalState: Unable to locate
  CDIProvider` outside an ArC context (the swapped builder reaches into CDI) AND the swap routes it through
  the Quarkus REST client (native-safe like the OpenAI/Anthropic providers), so DON'T pin
  `JdkHttpClientBuilder` (it neither helps — build still needs CDI — nor is needed — no empty native
  ServiceLoader for a swapped builder); make the build/`resolve` test a `@QuarkusTest`. **The guard trap:**
  Copilot's `resolve()` needs a live token exchange, so it CANNOT go in the offline app-level
  `ProviderResolveInAppClasspathTest` (which builds every other provider with no network) — cover the build
  + cached-`resolve` paths in-module under `@QuarkusTest` (credentials backed by a fake exchange) and
  document the guard exclusion. `copilot` is a `CommandMode` one-shot (login writes only a credential file).
  The login command stays offline-testable via a package-private `run(CopilotAuth, out, err)` + a fake
  `CopilotHttp` + a `RecordingCreds` stub (extends `CopilotCredentials` through its public ctor, overrides
  `storeGitHubToken`); jacoco-exclude only `JdkCopilotHttp` (live transport) and add a few error-branch
  tests (the OAuth flow is dense with defensive null/status branches → branch coverage needs them). [P2-COPILOT]

- **The provider-onboarding wizard's real work is the credential BRIDGE, not the CLI command.** No
  "0600 file → provider api-key" bridge existed: `anthropic`/`openai`/`google` read ONLY
  `@ConfigProperty("quarkus.langchain4j.<p>.api-key")`; only Copilot read a file (`CopilotCredentials`).
  The chosen bridge (maintainer-ratified over a central MP-Config `ConfigSource`) is a per-provider
  fallback mirroring #42: a Layer-1 SDK `FileApiKeyStore` (pure-JDK, native-safe, no Jackson — one opaque
  secret per file, plain text, `0600`/dir `0700`, traversal-confined id) that each key-based provider
  reads in `resolve()` WHEN its `@ConfigProperty` key is blank. This is robust for the same-process smoke
  because the file is read at RESOLVE time, not bean-construction time, so a just-written key is seen — a
  `ConfigSource` resolves `@ConfigProperty` once at bean creation and is fragile for the smoke. The file
  read MUST be OUTSIDE `computeIfAbsent` (blocking IO in the CHM callback pins the carrier, [M7]):
  capture `effectiveApiKey()` first, pass the String into the lambda; the config-set short-circuit keeps
  ZERO I/O on the common path. Smoke = chat-direct via the raw `ModelProvider` (not the ledgering
  `LlmSelector`) so no `provider_calls` row + no DB → `provider add` IS a `CommandMode` one-shot like
  `copilot login` (the earlier "NOT one-shot" ratification assumed a turn-full smoke; the chat-direct
  smoke flips it — re-confirm a behavior change against its original premise). The chain update is
  single-link `primaryModel` on `agents/main.json` (`fallbackModels` is UNREAD until P3 #52) edited via
  `ObjectNode` preserving unknown fields. Test the key precedence at the `effectiveApiKey()` seam (plain
  unit, no CDI) and the command flow via `run(Prompt, out, err, Smoker)` with a scripted `Prompt` (a
  single input seam chosen in `call()` — `ConsolePrompt` for a TTY, `ReaderPrompt` for piped, so
  `Console`/`Reader` never mix on one stdin) + the app-test `fake` provider for the real smoke / a
  throwing lambda for the failure branch — a `@QuarkusMainTest` has NO stdin so the interactive wizard
  can't be driven there [Risk#5].
  Gemini's `unset` boot placeholder counts as "no key" so the file fallback applies. [P2-10]

