> **AUTHORITATIVE CORRECTIONS — apply these over the body below.**
> Folded in from the integration + adversarial review (see `README.md`).
>
> 1. **Registration mechanism (Decision A):** run the Step-0 spike, but **PREFER Option A2**
>    (runtime CDI portable extension) for this concurrent window. A1 (splitting `forvum-engine` into a
>    runtime+deployment extension pair) forces a structural `<build>` rewrite of `forvum-engine/pom.xml`
>    + a new root `<module>` + a BOM GAV that collide with M5/M8's appends. Choose A1 **only** if the
>    spike proves A2 red under native at 3.33.1; if A1 is forced, its pom restructure lands **LAST**
>    and ULTRAPLAN §5.1/§7.1 M6 must be amended in the same PR. (integration decision d)
> 2. **Retain a COMMITTED native assertion** that actually resolves an `@AgentScoped` bean on the
>    `forvum-app -Pnative` Failsafe path — Risk #1's decision-trigger demands native-green. Do not rely
>    solely on a throwaway spike + the existing home-less boot smoke (which never resolves the scope).
>    (review GAP 2)
> 3. **`forvum-engine/pom.xml` lacks a `forvum-core` dependency today** — add it (prelude commit).
>    (GT-1)
> 4. **Verified safe:** `@AgentScoped` in `forvum-core` + `jakarta.enterprise.cdi-api:provided` does
>    NOT trip the core enforcer (it bans only `io.quarkus*`/`io.quarkiverse*`). (review confirmation)

# M6 — `@AgentScoped` custom CDI context — Implementation Plan

## 1. Objective & Definition of Done

Define a Forvum-owned custom CDI scope, `@AgentScoped`, whose backing context isolates per-agent bean instances keyed by a `ScopedValue<AgentId>` (and a sibling `ScopedValue<UUID> CURRENT_TURN` binding), so that virtual threads fanned out from an orchestrator never observe another agent's bean state. The scope is registered into ArC so it survives GraalVM native compilation with no runtime reflection and no `--enable-preview`.

**Literal success criterion (ULTRAPLAN §7.1 M6 Verify, line 1288):**

> A dual-thread integration test binds two different `AgentId`s on two virtual threads concurrently, resolves the same `@AgentScoped` bean class on each, and asserts the two instances are distinct `System.identityHashCode`.

DoD is met when:
- That test (`AgentContextIsolationIT`, `@QuarkusTest`) is green via Surefire (`./mvnw -pl forvum-engine test`), per the §4 headless-library exception.
- The `@AgentScoped` context binds → injects → unbinds correctly in **both JVM and native** (Risk #1 decision trigger, line 1427): the SPIKE proves the full path before the milestone ships.
- `forvum-app` native-compiles with the new scope wired in (CLAUDE.md §14 lesson — a module only native-compiles once `forvum-app` depends on it; it already does).
- The native smoke boots the binary with **no `~/.forvum/`** present and the context infrastructure does not crash (CLAUDE.md §14 / §5; M6 introduces no `@Startup` bean, so this is satisfied by construction — call it out explicitly).
- M6 only **defines** the `CURRENT_TURN` binding mechanism; persistence of `turn_id` columns is M5/Flyway V2, out of scope here (ULTRAPLAN §4.3.1 line 423).

## 2. Current relevant state

Inspected, real files this milestone builds on:

- `forvum-core/src/main/java/ai/forvum/core/id/AgentId.java` — `record AgentId(String value)` with canonical-constructor validation; its Javadoc already names `ScopedValue<AgentId>` and `@AgentScoped` (lines 6, 8). This is the context identity type. **No `@RegisterForReflection` (Layer 0, correct).**
- `forvum-core/pom.xml` — enforcer `enforce-core-purity` bans **only** `io.quarkus*:*` and `io.quarkiverse*:*` (lines 71-72), `searchTransitive=true`. It does **not** ban `jakarta.*`. This is the decisive fact for the `@NormalScope` placement decision (§3 below).
- `forvum-engine/pom.xml` — Layer-2 library: `quarkus-arc` + `quarkus-jackson` runtime deps; `quarkus-maven-plugin` with only `generate-code` + `generate-code-tests` (no `build` goal, lines 51-66); `quarkus-junit` test dep; enforcer `enforce-engine-extension-agnostic` allowlists only `forvum-core` + `forvum-sdk`. **This is a runtime-only library, NOT a Quarkus extension** — it has no deployment module and cannot host `@BuildStep`/`BuildItem` classes today. This is the central gap M6 must close (§3, Decision A).
- `forvum-engine/src/main/resources/META-INF/beans.xml` — present, `bean-discovery-mode="annotated"`; makes engine beans discoverable by `forvum-app`'s ArC.
- `forvum-engine/.../config/ConfigWatcher.java` — the canonical graceful-boot reference: `@Singleton`, `onStart(@Observes StartupEvent)`, warns + no-ops when `$FORVUM_HOME` absent (lines 77-83). M6 must NOT regress this pattern; M6 itself adds no `@Startup` bean.
- `forvum-app/pom.xml` — the only runnable artifact; depends on `forvum-engine` (lines 38-41) + `quarkus-arc`; has the `native` profile + Failsafe `*IT` smoke. This is where M6 native-compiles.
- `forvum-bom/pom.xml` — single version bump point; imports `quarkus-bom:3.33.1`. `quarkus-arc` and (if needed) `quarkus-arc-deployment` are platform-managed; **never pin versions in module poms.**
- ULTRAPLAN §5.1 (1121-1136), §4.3.1 (421-424), §8 Risk #1 (1424-1427), Risk #2/#3 sealed-interface note (1434-1437).

**Net-new directories/packages:**
- `forvum-core/src/main/java/ai/forvum/core/AgentScoped.java` (per doc, Layer 0).
- `forvum-engine/src/main/java/ai/forvum/engine/context/` — `AgentContext.java`, `CurrentAgent.java`.
- Depending on Decision A: either a new `forvum-engine-deployment` Maven module (with `AgentContextBuildItem.java`, `AgentContextProcessor.java`) **or** a runtime `jakarta.enterprise.inject.spi.Extension` (`AgentContextRegistrar.java`) + `META-INF/services`. The doc literally lists `AgentContextBuildItem.java` + `AgentContextProcessor.java` (line 1286), which forces the extension-split question.

## 3. Design decisions to lock

### Decision A (THE milestone-defining decision): How is the custom `InjectableContext` registered, given `forvum-engine` is a runtime library, not an extension?

The doc (§5.1 line 1123, §7.1 line 1286) says "registered via a `BuildStep` in `forvum-engine`" and names `AgentContextBuildItem.java` + `AgentContextProcessor.java`. Quarkus docs (confirmed via `quarkus_searchDocs`, `cdi-integration` guide §10) show the only build-time path is:

```java
@BuildStep
ContextConfiguratorBuildItem registerContext(ContextRegistrationPhaseBuildItem phase) {
    return new ContextConfiguratorBuildItem(
        phase.getContext().configure(AgentScoped.class).normal().contextClass(AgentContext.class));
}
@BuildStep
CustomScopeBuildItem customScope() {
    return new CustomScopeBuildItem(DotName.createSimple(AgentScoped.class.getName()));
}
```

These build items (`io.quarkus.arc.deployment.*`) and `@io.quarkus.deployment.annotations.BuildStep` live in `quarkus-arc-deployment` and "are normally placed on plain classes within an extension's **deployment** module" (writing-extensions guide). **A plain runtime jar cannot host them** — there is no augmentation phase that scans a non-extension runtime jar for `@BuildStep`.

Two real options:

- **Option A1 (recommended): Split `forvum-engine` into a Quarkus extension pair — `forvum-engine` (runtime) + new `forvum-engine-deployment` (deployment).** The runtime module carries `AgentScoped` consumers, `AgentContext`, `CurrentAgent`; the deployment module carries `AgentContextProcessor` (the `@BuildStep`s above) + `AgentContextBuildItem` (a `SimpleBuildItem` marker the processor produces, matching the doc's named file). The runtime jar declares the deployment GAV via `META-INF/quarkus-extension.properties` (`deployment=ai.forvum:forvum-engine-deployment`). This is the **only** path that matches the doc's named files verbatim and is the canonical, native-correct ArC mechanism. Rationale: ULTRAPLAN §5.1 explicitly chose the build-time `InjectableContext` registration precisely so native gets correct hints; Risk #1 frames it as "ArC `InjectableContext` build-time registration" — that is build-step territory.

- **Option A2 (fallback only): Runtime CDI portable extension.** Implement `jakarta.enterprise.inject.spi.Extension` with `void addContext(@Observes AfterBeanDiscovery abd) { abd.addContext(new AgentContext()); }`, registered via `META-INF/services/jakarta.enterprise.inject.spi.Extension`. This stays inside the existing runtime-only `forvum-engine`. ArC supports build-compatible portable extensions, but `addContext` on the *runtime* `AfterBeanDiscovery` is historically less exercised under native than the build-step path and would NOT produce the doc's `AgentContextProcessor.java`/`AgentContextBuildItem.java`. **Use only if the spike (Step 0) proves A1 is blocked under native at 3.33.1.**

**Recommendation: A1, gated by the Step-0 spike.** If the spike shows A1 green in JVM+native, implement A1 and the doc's file list is honored exactly. The deployment module is **internal** to the reactor (it is a build-time-only artifact consumed transitively by `forvum-app` through `forvum-engine`'s extension descriptor) — it does **not** violate the engine's "extension-agnostic" enforcer rule, which is about not depending on concrete *Forvum channel/provider/tool* modules, not about the Quarkus extension SPI. **Flag for the doc:** if the spike forces A2, ULTRAPLAN §5.1/§7.1 should be amended (per §4.3's "amend the section first" rule) to drop the `*BuildItem`/`*Processor` filenames and describe the portable-extension registration. Surface this to the integrator.

### Decision B: Where does `@AgentScoped` live, and how does it get its `@NormalScope` meta-annotation without breaking core purity?

The doc says `@AgentScoped` is declared in `forvum-core` (§5.1 line 1123). A CDI normal-scope annotation must be meta-annotated `@jakarta.enterprise.context.NormalScope` (+ `@Inherited`, `@Documented`, `@Target`, `@Retention(RUNTIME)`).

The `enforce-core-purity` rule bans `io.quarkus*` / `io.quarkiverse*` only (verified, forvum-core/pom.xml lines 71-72). `jakarta.enterprise.context.NormalScope` ships in `jakarta.enterprise:jakarta.enterprise.cdi-api` (group `jakarta.enterprise`), which is **not** on the ban list.

**Recommendation:** Add `jakarta.enterprise:jakarta.enterprise.cdi-api` to `forvum-core` as a **`provided`-scope** (compile-only, non-transitive at runtime) dependency, version managed by the platform BOM via `forvum-bom`, never pinned. Rationale:
- It keeps `@AgentScoped` in `forvum-core` exactly as the doc mandates.
- `provided` scope means core does not drag a CDI runtime onto downstream consumers and stays a "pure Java" Layer-0 artifact at runtime (the annotation is `@Retention(RUNTIME)` but the API jar is supplied by ArC downstream).
- It does NOT trip the enforcer (jakarta is allowed). This is the single most important correctness check: I verified the enforcer config bans only the two `io.quarkus*`/`io.quarkiverse*` group wildcards.
- Update the enforcer's explanatory comment in `forvum-core/pom.xml` (lines 68-75) to state explicitly that `jakarta.enterprise.cdi-api` is an allowed compile-only API, so a future reviewer does not "fix" it.

`@AgentScoped` carries **NO** `@RegisterForReflection` (Layer 0 exemption, CLAUDE.md §5 lines 169-173). If native needs the annotation reachable, it is registered from the engine holder (§7).

### Decision C: `CURRENT_AGENT` / `CURRENT_TURN` ScopedValue placement and final API form.

`CurrentAgent.java` in `forvum-engine` (`ai.forvum.engine.context`) holds:
```java
public final class CurrentAgent {
    public static final ScopedValue<AgentId> CURRENT_AGENT = ScopedValue.newInstance();
    public static final ScopedValue<java.util.UUID> CURRENT_TURN = ScopedValue.newInstance();
    private CurrentAgent() {}
}
```
Binding uses the **final** JDK 25 builder form (CLAUDE.md §5 lines 159-161): `ScopedValue.where(CURRENT_AGENT, id).call(body)` and nested `ScopedValue.where(CURRENT_AGENT, id).where(CURRENT_TURN, turnId).run(body)`. **No** `synchronized` anywhere (CLAUDE.md §11); the context's per-agent store is a `ConcurrentHashMap<AgentId, ContextInstances>` (§5.1 line 1125). Rationale: ScopedValue is final in JDK 25 (JEP 506) → no preview flag, native-safe (Risk #1 line 1425). The `CURRENT_TURN` binding is **defined** here; nothing in M6 reads it for persistence (that is M5 V2 — §4.3.1 line 423).

### Decision D: `AgentContext.contextClass` semantics — what does `AgentContext` resolve against?

`AgentContext implements io.quarkus.arc.InjectableContext`. `getScope()` → `AgentScoped.class`. On `get(Contextual, CreationalContext)` it reads `CurrentAgent.CURRENT_AGENT.get()` (throws a clear `ContextNotActiveException` if unbound), then looks up/creates the per-agent `ContextInstances` map entry, then the per-contextual instance within it. `isActive()` → `CurrentAgent.CURRENT_AGENT.isBound()`. `destroy()` evicts an agent's entry (used when `AgentRegistry` removes an agent at M7 — §5.1 line 1125). Rationale: this is the standard ArC `InjectableContext` contract; keying on the ScopedValue (not a ThreadLocal) is what makes virtual-thread fan-out correct (§5.1 lines 1123-1125).

### Decision E (sealed/ArC, Risk #2/#3): `@AgentScoped` beans are concrete classes.

Risk #3 (line 1434-1437) is about sealed *provider* hierarchies. The M6 test bean is an ordinary concrete `@AgentScoped` class (`ScopeProbe`), so ArC discovery is unremarkable. No action beyond confirming the test bean is concrete and discovered (`beans.xml` already `annotated` mode; `@AgentScoped` becomes a bean-defining annotation via `CustomScopeBuildItem`). Flag: M7 introduces the first *real* `@AgentScoped` beans — keep them concrete.

## 4. Step-by-step TDD plan

> Test execution rule (CLAUDE.md §4): `forvum-engine` is a headless Quarkus library → all its `@QuarkusTest`s run via **Surefire** (`./mvnw -pl forvum-engine test -B -Dstyle.color=never`), never the Dev MCP runner. `forvum-core` unit tests also run via Surefire. Read clean `target/surefire-reports/*.txt`.

### Step 0 — SPIKE (Risk #1 decision trigger; do FIRST, before committing to A1)
Harvest + prove the registration path in JVM and native.
- **Quarkus tooling:** `quarkus_create` a throwaway extension skeleton (runtime+deployment) to harvest the exact 3.33.1 extension pom wiring (`quarkus-extension-maven-plugin` goal `extension-descriptor`, the `META-INF/quarkus-extension.properties` shape, `quarkus-arc-deployment` coordinate). `quarkus_skills` query `arc` BEFORE writing the processor. Transplant coordinates into `forvum-bom`/the module poms; do not keep the throwaway app.
- **Spike test (throwaway, not committed):** a `@QuarkusTest` that binds one `AgentId`, resolves an `@AgentScoped` bean, asserts non-null, unbinds. Build the spike into `forvum-app -Pnative` and run it as a native `*IT` to prove the ArC build-step path generates correct native hints.
- **Decision gate:** A1 green in JVM+native → proceed A1. A1 red in native → switch to A2 (portable extension), file a Quarkus issue (Risk #1 line 1427), and flag the doc amendment.

### Step 1 — `@AgentScoped` annotation (Red → Green), forvum-core
- **Red:** `forvum-core/src/test/java/ai/forvum/core/AgentScopedTest.java` — asserts via reflection that `AgentScoped` is annotated with `@jakarta.enterprise.context.NormalScope`, `@Retention(RUNTIME)`, `@Target({TYPE, METHOD, FIELD})`, `@Inherited`. (Plain Surefire, Quarkus-free.)
- **Green:** create `forvum-core/src/main/java/ai/forvum/core/AgentScoped.java`; add `jakarta.enterprise.cdi-api` `provided` dep to `forvum-core/pom.xml`; update the enforcer comment.
- **Verify:** `./mvnw -pl forvum-core test -B -Dstyle.color=never` green; `./mvnw -DskipTests validate` (enforcer still passes — confirms Decision B).

### Step 2 — `CurrentAgent` ScopedValues (Red → Green), forvum-engine
- **Red:** `forvum-engine/src/test/java/ai/forvum/engine/context/CurrentAgentTest.java` (plain unit `*Test`, no Quarkus boot) — asserts `CURRENT_AGENT` unbound by default; inside `ScopedValue.where(CURRENT_AGENT, new AgentId("a")).call(...)` it is bound and `.get()` returns the value; nested `.where(CURRENT_TURN, uuid)` binds the turn; both unbind on lambda return.
- **Green:** `forvum-engine/src/main/java/ai/forvum/engine/context/CurrentAgent.java` (Decision C).
- **Verify:** `./mvnw -pl forvum-engine test -Dtest=CurrentAgentTest -B -Dstyle.color=never`.

### Step 3 — `AgentContext` InjectableContext (Red → Green), forvum-engine
- **Red:** `forvum-engine/src/test/java/ai/forvum/engine/context/AgentContextUnitTest.java` (plain `*Test`, instantiate `AgentContext` directly, no CDI) — `getScope()==AgentScoped.class`; `isActive()` false unbound, true bound; `get(contextual, ctx)` with a fake `Contextual` returns the **same** instance for the same `AgentId` and a **distinct** instance under a different `AgentId` binding (direct unit-level proof of isolation before the CDI integration test); `get(contextual,null)` returns null when no creational context; `getState`/`destroy` evicts.
- **Green:** `forvum-engine/src/main/java/ai/forvum/engine/context/AgentContext.java` (Decision D), `ConcurrentHashMap`, no `synchronized`.
- **Verify:** `./mvnw -pl forvum-engine test -Dtest=AgentContextUnitTest -B -Dstyle.color=never`.

### Step 4 — Registration wiring (Green), per Decision A
- **A1:** create `forvum-engine-deployment` module: `AgentContextProcessor.java` (the two `@BuildStep`s — `registerContext` producing `ContextConfiguratorBuildItem`, `customScope` producing `CustomScopeBuildItem`, plus producing the named `AgentContextBuildItem` marker), `AgentContextBuildItem.java` (`extends SimpleBuildItem`); add `META-INF/quarkus-extension.properties` (`deployment=ai.forvum:forvum-engine-deployment`) + `quarkus-extension-maven-plugin` to `forvum-engine`'s pom; add the deployment module to root `pom.xml` `<modules>` and to `forvum-bom`. The runtime `AgentContext` reachability is implicit (it is referenced by the build step). No separate beans.xml change.
- **A2 (only if Step 0 forced it):** `forvum-engine/src/main/java/ai/forvum/engine/context/AgentContextRegistrar.java implements jakarta.enterprise.inject.spi.Extension` + `forvum-engine/src/main/resources/META-INF/services/jakarta.enterprise.inject.spi.Extension`.

### Step 5 — Dual-thread isolation test (THE Verify, Red first) — forvum-engine
- **Red (write before Step 4 is wired):** `forvum-engine/src/test/java/ai/forvum/engine/context/AgentContextIsolationIT.java`, `@QuarkusTest`. A concrete `@AgentScoped` `ScopeProbe` bean (test-scoped, distinct identity per instance). Two virtual threads (`Thread.ofVirtual()...`/`newVirtualThreadPerTaskExecutor`, CLAUDE.md §11), each binds a different `AgentId` via `ScopedValue.where(CURRENT_AGENT, id).call(() -> probe-resolve-and-touch)`, run concurrently behind a `CountDownLatch`/`CyclicBarrier` so both bindings overlap; capture `System.identityHashCode` of the resolved `ScopeProbe` from each thread; assert the two differ; also assert resolving twice under the **same** binding yields the **same** identityHashCode (intra-agent identity). Inject `@Inject ScopeProbe probe` (a client proxy) and force contextual resolution inside each binding.
- **Green:** Step 4 wiring makes the context active and resolvable.
- **Verify (literal §7.1 Verify):** `./mvnw -pl forvum-engine test -Dtest=AgentContextIsolationIT -B -Dstyle.color=never` green; the two identityHashCodes differ.

### Step 6 — App wiring + native parity (Green)
- `forvum-app` already depends on `forvum-engine`; with A1 the deployment module is pulled transitively via the extension descriptor — no new app dep needed. Confirm `./mvnw -pl forvum-app -am package` (JVM) augments without error.
- **Native:** `./mvnw -f forvum-app -Pnative package` (or `-Dquarkus.native.container-build=true`) must compile the binary with the custom context registered, and the existing `*IT` native smoke must boot with **no `~/.forvum/`** present (M6 adds no `@Startup` bean → boots clean). This is the Risk #1 native green gate.

## 5. Files — created / modified

Mapped to §7.1 M6 Files list (line 1286):

**Created:**
- `forvum-core/src/main/java/ai/forvum/core/AgentScoped.java` *(doc-listed)* — `@NormalScope` annotation (Decision B). No `@RegisterForReflection`.
- `forvum-core/src/test/java/ai/forvum/core/AgentScopedTest.java` — Step 1 Red.
- `forvum-engine/src/main/java/ai/forvum/engine/context/CurrentAgent.java` *(doc-listed)* — the `ScopedValue<AgentId> CURRENT_AGENT` + `ScopedValue<UUID> CURRENT_TURN`.
- `forvum-engine/src/main/java/ai/forvum/engine/context/AgentContext.java` *(doc-listed)* — `InjectableContext` impl.
- `forvum-engine/src/test/java/ai/forvum/engine/context/CurrentAgentTest.java` — Step 2.
- `forvum-engine/src/test/java/ai/forvum/engine/context/AgentContextUnitTest.java` — Step 3.
- `forvum-engine/src/test/java/ai/forvum/engine/context/AgentContextIsolationIT.java` — Step 5 (THE Verify).
- **A1:** new module `forvum-engine-deployment/` with `pom.xml`, `src/main/java/ai/forvum/engine/deployment/AgentContextProcessor.java` *(doc-listed)* and `AgentContextBuildItem.java` *(doc-listed)*; `forvum-engine/src/main/resources/META-INF/quarkus-extension.properties` (`deployment=…`).
- **A2 (only if forced):** `forvum-engine/src/main/java/ai/forvum/engine/context/AgentContextRegistrar.java` + `forvum-engine/src/main/resources/META-INF/services/jakarta.enterprise.inject.spi.Extension`.

**Modified:**
- `forvum-core/pom.xml` — add `jakarta.enterprise:jakarta.enterprise.cdi-api` `provided` (BOM-managed, unpinned); update enforcer comment (lines 68-75).
- **A1:** `forvum-engine/pom.xml` — add `quarkus-extension-maven-plugin` (`extension-descriptor` goal) so it becomes a runtime extension; root `pom.xml` `<modules>` (add `forvum-engine-deployment` before `forvum-app`); `forvum-bom/pom.xml` — add `forvum-engine-deployment` GAV entry. New deployment module pom carries its own enforcer execution (CLAUDE.md §10) and depends on `forvum-engine` + `quarkus-arc-deployment`.

**Native reachability metadata:** none hand-authored for M6 (no LangGraph4j record types here). The ScopedValue/InjectableContext path needs no `reflect-config.json` because A1 registers the context at build time (the whole point of Risk #1). If the Layer-0 `AgentScoped`/`AgentId` need explicit reflection reachability, they go through the existing/forthcoming `@RegisterForReflection(targets={...})` holder in `forvum-engine` (§7), not a JSON file.

## 6. Dependencies — exact coordinates

All BOM-managed, **never** version-pinned in module poms (CLAUDE.md §2, §7):

| Coordinate | Goes in | Scope | Notes |
|---|---|---|---|
| `jakarta.enterprise:jakarta.enterprise.cdi-api` | `forvum-core/pom.xml` | `provided` | version from `quarkus-bom` via `forvum-bom`; supplies `@NormalScope`. Not banned by core enforcer. |
| `io.quarkus:quarkus-arc` | `forvum-engine/pom.xml` | compile | **already present** (line 33). Provides `io.quarkus.arc.InjectableContext`. |
| `io.quarkus:quarkus-arc-deployment` | `forvum-engine-deployment/pom.xml` (A1 only) | compile | platform-managed; provides `ContextRegistrationPhaseBuildItem`, `ContextConfiguratorBuildItem`, `CustomScopeBuildItem`, `@BuildStep`. |
| `ai.forvum:forvum-engine-deployment` | `forvum-bom/pom.xml` + root `<modules>` (A1 only) | — | `${project.version}`. |

§7.1 M6 "Deps: `io.quarkus.arc:arc` (already transitive)" (line 1287) is satisfied by the existing `quarkus-arc`; A1 additionally needs the **deployment** artifact, which the doc omits — flag this to the integrator as a documentation gap consistent with the `*BuildItem`/`*Processor` filenames the doc itself lists.

## 7. Native-first checklist

- **`@RegisterForReflection` placements:** `@AgentScoped` (core) and `AgentId` (core) carry **none** — Layer-0 exemption (CLAUDE.md §5 lines 169-173, §12 line 333). If native reachability for these types is required, add them to the existing `forvum-engine` `@RegisterForReflection(targets={...})` holder (ULTRAPLAN §6.3). `AgentContext`, `CurrentAgent` are wired/referenced by ArC at build time → no annotation needed. The test `ScopeProbe` is test-scoped, irrelevant to the production native image.
- **Reachability metadata:** no hand-authored `META-INF/native-image/` entries for M6 (none of its types are JSON DTOs or LangGraph4j state). A1's build-step registration is the native-correct substitute for any reflection config (Risk #1 line 1425).
- **No-reflection confirmation:** registration is build-time (A1) / portable-extension (A2); ScopedValue is a standard final API; `ConcurrentHashMap` keyed lookup — zero runtime reflection, zero `--enable-preview` (CLAUDE.md §5 lines 157-161).
- **Wired into `forvum-app` so it native-compiles:** `forvum-app` already depends on `forvum-engine` (pom lines 38-41); A1's deployment module is pulled transitively via the extension descriptor. `./mvnw -f forvum-app -Pnative package` is the compile gate (CLAUDE.md §14 — a module native-compiles only once the app depends on it).
- **Graceful boot without `~/.forvum/`:** M6 introduces **no** `@Startup`/`@Observes StartupEvent` bean and touches no filesystem — the context is passive infrastructure activated only inside a `ScopedValue.where(...).call(...)`. The native smoke (no `~/.forvum/`) boots unaffected (CLAUDE.md §14 lesson honored by construction; the existing `ConfigWatcher` graceful path is untouched).

## 8. Tests

| Test | Type | Where it runs | Asserts |
|---|---|---|---|
| `AgentScopedTest` | unit `*Test` (Quarkus-free) | Surefire `-pl forvum-core` | `@AgentScoped` meta-annotations (Decision B) |
| `CurrentAgentTest` | unit `*Test` | Surefire `-pl forvum-engine` | bind/unbind of both ScopedValues, final builder form |
| `AgentContextUnitTest` | unit `*Test` | Surefire `-pl forvum-engine` | `InjectableContext` contract + per-`AgentId` instance distinctness at unit level |
| `AgentContextIsolationIT` | integration `@QuarkusTest` | **Surefire** `-pl forvum-engine` (headless library, CLAUDE.md §4 lines 138-143) | **THE §7.1 Verify**: two `AgentId`s on two virtual threads → distinct `System.identityHashCode`; same binding → same instance |
| spike native `*IT` (throwaway) | native parity | `forvum-app -Pnative` Failsafe | Risk #1: bind→inject→unbind green in native |
| existing app native smoke `*IT` | native parity | `forvum-app -Pnative` Failsafe | binary boots with no `~/.forvum/`, context registered, no crash |

There is **no `@TempDir`/SQLite** in M6 (no persistence — that is M5). The `@TempDir SQLite` pattern noted in the prompt belongs to M5; M6's integration test needs only Quarkus + virtual threads.

**Exact Verify command:**
```
./mvnw -pl forvum-engine test -Dtest=AgentContextIsolationIT -B -Dstyle.color=never
```
Full milestone gate (JVM + native):
```
./mvnw -pl forvum-core,forvum-engine test -B -Dstyle.color=never
./mvnw -f forvum-app -Pnative package -Dquarkus.native.container-build=true   # native compile + smoke
```

## 9. Risks & mitigations

- **Risk #1 — ArC `InjectableContext` build-time registration in native (lines 1424-1427).** THE native risk for M6. Mitigation: Step-0 spike validates bind→inject→unbind in JVM and native before implementing; A2 portable-extension fallback exists if A1 is red in native; the build stays `--enable-preview`-free (ScopedValue final). Decision trigger: M6 CI green on both JVM and native + the two-thread isolation test passes; if A1 native is red, file a Quarkus issue and resolve before M6 ships (native-mandatory milestone).
- **Risk #2/#3 — sealed interfaces + ArC bean discovery (lines 1434-1437).** Low exposure here: the M6 test bean is a concrete class; `@AgentScoped` becomes a bean-defining annotation via `CustomScopeBuildItem`. Mitigation: keep all `@AgentScoped` beans concrete (relevant for M7's real beans). If ArC warns on `@AgentScoped` discovery, investigate before M7.
- **Doc/reality gap (new, must surface):** `forvum-engine` is a runtime library; the doc's `AgentContextBuildItem`/`AgentContextProcessor` filenames imply a deployment module. Mitigation: Decision A1 creates `forvum-engine-deployment`; if the spike forces A2, amend ULTRAPLAN §5.1/§7.1 first (§4.3 amendment rule) and the §7.1 Deps line to include `quarkus-arc-deployment`.

## 10. Quarkus / library tooling steps

- `quarkus_skills` (projectDir `forvum-app`), query `arc` — BEFORE writing the processor/context (mandatory, CLAUDE.md §7 line 226).
- `quarkus_searchDocs` — `cdi-integration` guide §10 "Register a Custom CDI Context" (already retrieved: `ContextRegistrationPhaseBuildItem` → `ContextConfiguratorBuildItem` + `CustomScopeBuildItem`); `writing-extensions` / `building-my-first-extension` for the runtime+deployment module split and `quarkus-extension.properties`.
- `quarkus_create` — throwaway extension skeleton (Step 0) to harvest the 3.33.1 `quarkus-extension-maven-plugin` `extension-descriptor` wiring and the deployment module pom shape; transplant coordinates into `forvum-bom`/poms (versions BOM-managed, unpinned); discard the throwaway app. Do NOT use `quarkus_create`/`quarkus_start` against the real reactor; do not use any mutating tool.
- **context7:** not needed for M6 (no LangChain4j/LangGraph4j surface).

## 11. Commit(s)

Primary (matches §7.1 M6, line 1289), imperative Conventional Commits:
- `feat(engine): add @AgentScoped CDI context backed by ScopedValue`

If A1 splits the module, optionally a preceding enabling commit:
- `chore(engine): split forvum-engine into runtime + deployment extension modules`

(Commit only when the user authorizes — CLAUDE.md §10 line 279. Include the `Co-Authored-By` trailer.)

## 12. Completion checklist

- [ ] Step-0 spike green in JVM **and** native (Risk #1 trigger) → A1 confirmed (or A2 chosen + doc-amendment flagged).
- [ ] `AgentScoped.java` in `forvum-core` with `@NormalScope` + retention/target/inherited; **no** `@RegisterForReflection`.
- [ ] `jakarta.enterprise.cdi-api` added `provided` to `forvum-core`; `./mvnw -DskipTests validate` (enforcer) still green.
- [ ] `CurrentAgent` exposes `CURRENT_AGENT` + `CURRENT_TURN` `ScopedValue`s; uses final `where(...).call/run` form; no `synchronized`.
- [ ] `AgentContext implements InjectableContext`; `ConcurrentHashMap` per-agent store; `isActive()` ⇔ `CURRENT_AGENT.isBound()`.
- [ ] Custom context registered (A1 build steps `ContextConfiguratorBuildItem` + `CustomScopeBuildItem`, or A2 portable extension).
- [ ] `AgentContextIsolationIT` (THE Verify) green via Surefire: two virtual threads, two `AgentId`s, distinct `System.identityHashCode`; same binding → same instance.
- [ ] `./mvnw -f forvum-app -Pnative package` compiles the binary with the context registered; native smoke boots with **no `~/.forvum/`**.
- [ ] No new `@Startup` bean; ScopedValue final; no `--enable-preview`; no runtime reflection.
- [ ] `CURRENT_TURN` binding defined only (no `turn_id` persistence — deferred to M5/V2).

## 13. Cross-milestone coordination notes (M5 / M8 siblings)

- **Shared file — `forvum-engine/pom.xml`:** M5 (SQLite/Flyway) adds `quarkus-hibernate-orm-panache`, `quarkus-flyway`, `sqlite-jdbc`, `hibernate-community-dialects` to this pom (§7.1 M5 line 1281); M6 (A1) restructures the same pom into a runtime extension + adds `quarkus-extension-maven-plugin`. **Sequence M6's pom restructure and M5's dependency additions in one integrator pass to avoid a merge collision.** If A1 lands, M5's `quarkus-flyway` (which itself has a deployment module) coexists cleanly, but the integrator must re-run `./mvnw -f forvum-app -Pnative package` after both land.
- **Shared file — root `pom.xml` `<modules>` and `forvum-bom/pom.xml`:** A1 adds `forvum-engine-deployment` to both. M5/M8 do not add modules, so collision is limited to the `<modules>` list and the BOM GAV block — trivial to merge but flag it.
- **`forvum-core` type surface:** M6 adds `AgentScoped.java` and the `jakarta.enterprise.cdi-api` dep to `forvum-core`. If M8 touches `forvum-core` records or the `@RegisterForReflection(targets={...})` holder in `forvum-engine`, coordinate so `AgentScoped`/`AgentId` reflection registration (if added) lands once, not twice.
- **`CURRENT_TURN` ↔ M5 V2 contract (§4.3.1 line 423):** M6 owns the `ScopedValue<UUID> CURRENT_TURN` definition; M5's Flyway V2 owns the `turn_id` columns that read `CURRENT_TURN.get()`. The integrator must ensure M5/V2 references `CurrentAgent.CURRENT_TURN` from `ai.forvum.engine.context` (the package M6 establishes) — fix the import target if M5 was drafted assuming a different location.
- **Decision-A doc amendment:** if the spike forces A2, ULTRAPLAN §5.1/§7.1 M6 must be amended (drop `*BuildItem`/`*Processor`, describe portable-extension); the integrator should land that doc change in the same PR (CLAUDE.md §10 docs-sync).

### Critical Files for Implementation
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-core/pom.xml
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine/pom.xml
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-core/src/main/java/ai/forvum/core/id/AgentId.java
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine/src/main/resources/META-INF/beans.xml
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/docs/ULTRAPLAN.md (§5.1, §4.3.1, §8 Risk #1/#3)
