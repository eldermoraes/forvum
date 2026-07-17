# Implementation lessons — Quarkus config, CDI & module recipes

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **New Quarkus-bearing *library* module recipe** (harvested via `quarkus/create`, §7): the test artifact
  is `io.quarkus:quarkus-junit` (NOT `quarkus-junit5`); apply `quarkus-maven-plugin` with `generate-code`
  + `generate-code-tests` only (NO `build` goal — a library is not a runnable app); add an empty
  `META-INF/beans.xml` so the app's ArC discovers its `@Singleton` beans; add no native profile (native
  builds only in `forvum-app`). Such a headless library cannot be `quarkus:dev`-ed, so its tests run via
  Surefire — see the §4 exception. Resolve config home with `@ConfigProperty` (MP Config) so a
  `QuarkusTestProfile` can redirect it to a `@TempDir`. [M4]

- **`WatchService` file-watching discipline** (reused by M19 cron): register subfolders created *after*
  boot (on a directory `ENTRY_CREATE`) and scan their already-present files; drop invalid keys on
  `WatchKey.reset() == false`; recover from `OVERFLOW` by rescanning; isolate each synchronous CDI
  `Event.fire()` in try/catch so one throwing observer cannot kill the watch loop; debounce + coalesce
  per path. macOS uses ~2–10 s polling (Risk #7), so behavioral file-watch tests need a generous timeout
  — keep the deterministic assertions in plain unit tests (debounce/coalesce, kind-mapping). [M4]

- **Register a custom CDI context from a plain library via a CDI Lite `BuildCompatibleExtension`, not a
  deployment `@BuildStep`.** ArC's documented custom-context path (`ContextRegistrationPhaseBuildItem`
  → `ContextConfiguratorBuildItem` + `CustomScopeBuildItem`) needs a `@BuildStep`, which only runs in a
  deployment module — turning `forvum-engine` into a runtime+deployment extension and forcing its own
  `@QuarkusTest`s out (deployment↔runtime reactor cycle), breaking the M4 headless-library setup. A
  `BuildCompatibleExtension` whose `@Discovery` method calls `MetaAnnotations.addContext(scope, true,
  CtxClass)` (declared in `META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`)
  registers the scope from the plain library, makes the annotation bean-defining, and bakes into the
  native image (BCEs run at augmentation). The backing `InjectableContext` lives in the library; the
  scope annotation lives in `forvum-core` with a `provided` `jakarta.enterprise.cdi-api` dep (the
  core enforcer bans only `io.quarkus*`/`io.quarkiverse*`, not `jakarta.*`). [M6]

- **`@AgentScoped` bean recipe:** use field injection (package-private) for ArC proxyability — no
  artificial no-arg constructor — and read `CurrentAgent.CURRENT_AGENT` at method-call time. Test
  per-agent isolation/caching via an injected bean's `System.identityHashCode(this)` inside
  `ScopedValue.where(CURRENT_AGENT, id).call(...)` (mirror `ScopeProbe`). The generic isolation lives in
  `AgentContext`, so don't re-assert it per bean. [M7]

