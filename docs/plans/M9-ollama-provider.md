# M9 — Ollama provider (first Layer-3 plugin) + build-time plugin discovery

Status: **planning artifact, not code.** Part of the **Tier-B concurrent window (M7 · M9)** — see
`README.md` in this folder. Architectural decisions below need maintainer sign-off (CLAUDE.md §8).
Source of truth: `docs/ULTRAPLAN.md`; issue map: `docs/ISSUES.md` (**M9 → #14**).

M9 is the **first Layer-3 module** and therefore establishes the canonical provider-plugin recipe that
M10–M12 replicate. The maintainer chose (2026-06-04) to also **implement the build-time plugin-discovery
`BuildStep` now**, rather than deferring it — see AC-2.

---

## AUTHORITATIVE CORRECTIONS (override the body where they conflict)

Verified against `main` (post-Tier-A).

- **AC-1 — the SDK `ModelProvider` SPI is EXTENDED, and that extension is the Tier-B *prelude* that
  lands in the M7 PR, NOT in M9.** Today `forvum-sdk.ModelProvider` has only `extensionId()`; its javadoc
  says `resolve(ModelRef)→ChatModel` "is added by the provider milestones (M9–M12), which bring the
  LangChain4j types." Adding `dev.langchain4j.model.chat.ChatModel resolve(ai.forvum.core.ModelRef ref)`
  to the sealed interface must happen **before/with M7**, because M7's `LlmSelector` + `FakeModelProvider`
  consume the method and M7 merges first — putting it in M9 would leave M7 uncompilable. So the SPI
  extension (method + the `dev.langchain4j:langchain4j-core` compile dep on `forvum-sdk`) is the **shared
  prelude, landed as the first commit of the M7 PR**; M9's PR then *implements* `resolve()` for Ollama.
  Verified facts: `forvum-sdk` imports `forvum-bom`, so `langchain4j-core` is declared **versionless**
  (managed via `quarkus-langchain4j-bom`); the SDK enforcer governs only the `ai.forvum:*` namespace, so
  it needs **no change** (`dev.langchain4j:*` is not banned). `forvum-sdk` stays Quarkus-free (LangChain4j
  is not Quarkus). **Still an SPI change → Decision D-1 (sign-off).**
- **AC-2 — the plugin-discovery `BuildStep` CANNOT live in `forvum-engine`.** A `@BuildStep` only runs in
  a Quarkus *deployment* module (confirmed: Quarkus "building-my-first-extension" — runtime + `-deployment`
  pair, deployment depends on runtime + `quarkus-arc-deployment` + `quarkus-extension-processor`). The M6
  lesson (CLAUDE.md §14) deliberately kept `forvum-engine` a plain library (BCE, not a deployment
  module) to avoid a deployment↔runtime reactor cycle that would evict its `@QuarkusTest`s. Therefore the
  discovery `BuildStep` lives in a **dedicated extension `forvum-plugin-discovery` (runtime) +
  `forvum-plugin-discovery-deployment`**, applied by `forvum-app`. The `ForvumExtension` javadoc
  ("a BuildStep in forvum-engine") and ULTRAPLAN §6.3 get a one-line amendment. **Decision D-2 (sign-off).**
- **AC-3 — the provider implementation is independent of M7; only the *e2e verify* needs M7.** M9's
  `OllamaModelProvider` + manifest + contract tests stand alone (test `resolve()` by building a
  `ChatModel` directly). The ULTRAPLAN §7.1 verify ("a scripted turn *through `AgentRegistry`*") is a
  **gated** integration test — class-presence / `@EnabledIf`-gated until M7 lands, then un-gated. This is
  the same shape as Tier-A's `M8.ProviderCallPersistenceIT → M5`.
- **AC-4 — native-compile is gated on `forvum-app` wiring.** The native image is built only in
  `forvum-app`; the provider native-compiles only once `forvum-app` depends on it (CLAUDE.md §14). Wire
  it in the same milestone. The Risk #5 per-provider canned-turn native smoke (mandatory M9–M12) is
  CI-runnable for Ollama (local, no API key, `qwen3:1.7b`).
- **AC-5 — CDI alone would suffice for M9's discovery; the `BuildStep` is an early investment.** Because
  `OllamaModelProvider` is a CDI bean (`@ForvumExtension` + bean-defining), `forvum-app` discovers it via
  ArC at build time with native-safe reflection already handled. The `BuildStep` adds the *declarative*
  `plugin.json` discovery + reflection-hint emission that the broader plugin ecosystem (tools/channels,
  drop-in) will rely on. If after the D-2 spike its cost outweighs the early-mechanism value, the
  off-ramp is: ship M9 with CDI discovery + `plugin.json` as forward metadata, and land
  `forvum-plugin-discovery` as its own micro-milestone. **Stated so the choice is reversible.**

---

## 1. Scope

**Delivers:**
1. `forvum-provider-ollama` — the first Layer-3 plugin module (depends only on `forvum-sdk` +
   `quarkus-langchain4j-ollama`), with `OllamaModelProvider` (`@ForvumExtension`) implementing the
   extended `ModelProvider` SPI, plus `META-INF/forvum/plugin.json` and `beans.xml`.
2. The SDK SPI extension: `ModelProvider.resolve(ModelRef) → ChatModel` (AC-1).
3. `forvum-plugin-discovery` (+ `-deployment`) — a minimal Quarkus extension whose `BuildStep` scans
   `META-INF/forvum/plugin.json` on the build classpath, records the contributed providers, and emits
   native-image reflection hints (AC-2).
4. `forvum-app` wiring: depend on `forvum-provider-ollama` + apply `forvum-plugin-discovery` (AC-4).
5. The gated `*IT` e2e (AC-3) + the Risk #5 native smoke.

**Out of scope:** other providers (M10–M12), tool plugins, the runtime drop-in `~/.forvum/plugins/` path
(fast-jar-only, documented separately), multi-provider fallback (M10).

---

## 2. Ground-truth this builds on (verified)

| Need | Existing artifact |
|---|---|
| Provider SPI to implement | `forvum-sdk` `ModelProvider` (sealed) / `AbstractModelProvider` (non-sealed) / `ForvumExtension` (M3) — **extended by AC-1** |
| Ledger write seam | `engine/model/ProviderCallRecorder.java` + `PanacheProviderCallRecorder.java` (M8) — invoked via M7's `LlmSelector`, not by the plugin |
| Reactor / app | root `pom.xml` (5 modules); `forvum-app/pom.xml` depends on `forvum-engine` + tamboui only (no Layer-3 module yet) |
| Layer-3 pom + enforcer template | `docs/CODE-REVIEW.md` §5.1 |
| Native lesson | "native-compiles only once `forvum-app` depends on it" (CLAUDE.md §14) |

No `forvum-provider-*` module and no `OllamaModelProvider` exist yet — green field.

---

## 3. Design

### 3.1 `forvum-provider-ollama` (Layer-3 module recipe — the canonical template)
Harvest the current platform coordinates via `quarkus/create` (throwaway app) per §7; transplant into
the module pom (versions via BOMs, never pinned). The module pom:
- `<packaging>jar</packaging>`; `quarkus-maven-plugin` with `generate-code` + `generate-code-tests`
  **only** (no `build` goal — a plugin is not a runnable app);
- `maven-enforcer-plugin` `bannedDependencies` (CODE-REVIEW.md §5.1): allowlist `ai.forvum:forvum-sdk`,
  ban all other `ai.forvum:*` (proves the plugin is extension-agnostic — `./mvnw -DskipTests validate`
  is the gate);
- deps: `forvum-sdk`, `io.quarkiverse.langchain4j:quarkus-langchain4j-ollama` (brings the Ollama
  LangChain4j model + native hints; version via `forvum-bom`'s `quarkus-langchain4j-bom`);
- `src/main/resources/META-INF/beans.xml` (`bean-discovery-mode="annotated"`) so ArC discovers the bean.

`OllamaModelProvider extends AbstractModelProvider` + `@ForvumExtension`, `@ApplicationScoped`:
- `extensionId()` → `"ollama"`;
- `resolve(ModelRef ref)` → an Ollama `ChatModel`. **Recommended:** build programmatically per the
  requested model id — `OllamaChatModel.builder().baseUrl(<from config>).modelName(ref.model())...build()`
  — so any `ollama:<model>` `ModelRef` resolves, not just one statically-configured bean. Base URL +
  defaults from config (`application.properties` / `~/.forvum/`), default `http://localhost:11434`.
  Confirm the exact builder API via `quarkus/skills`(ollama) + `context7` in Step-0.

`META-INF/forvum/plugin.json`:
```json
{ "extension_id": "ollama", "module_name": "forvum-provider-ollama",
  "providers": [{ "type": "model", "class": "ai.forvum.provider.ollama.OllamaModelProvider" }] }
```

### 3.2 SDK SPI extension (AC-1)
Add to `ModelProvider`:
```java
dev.langchain4j.model.chat.ChatModel resolve(ai.forvum.core.ModelRef ref);
```
`AbstractModelProvider` stays abstract (does not implement it); `OllamaModelProvider` implements it.
Update `forvum-sdk/pom.xml` deps + the SDK enforcer allowlist to include `dev.langchain4j:langchain4j-core`.
Update the `ModelProvider` / `ForvumExtension` javadoc accordingly. This is the one change M9 makes
outside its own module + the app.

### 3.3 `forvum-plugin-discovery` extension (AC-2)
Two-module pair:
- **runtime** (`forvum-plugin-discovery`) — holds a recorded `PluginRegistry` (the parsed provider list),
  available as a CDI bean at runtime; depends on `forvum-sdk` (for the manifest types).
- **deployment** (`forvum-plugin-discovery-deployment`) — depends on the runtime module +
  `quarkus-arc-deployment`; a `@BuildStep` reads every `META-INF/forvum/plugin.json` on the build
  classpath, produces a `ReflectiveClassBuildItem` for each declared provider class, and records the
  registry (`@Record(STATIC_INIT)`).

`forvum-app` adds the runtime artifact as a dependency (applying the extension). Confirm the exact
build-item API (`ReflectiveClassBuildItem`, `AdditionalBeanBuildItem`, recorder) via `quarkus/searchDocs`
+ `quarkus/skills` in the D-2 spike before writing — §7 mandate; do not guess deployment APIs.

### 3.4 `forvum-app` wiring (AC-4)
```xml
<dependency><groupId>ai.forvum</groupId><artifactId>forvum-provider-ollama</artifactId></dependency>
<dependency><groupId>ai.forvum</groupId><artifactId>forvum-plugin-discovery</artifactId></dependency>
```
Add both new modules to the root `pom.xml` `<modules>` (Layer-3 / extension section, before
`forvum-app`).

---

## 4. Decisions needing maintainer sign-off (§8)

- **D-1 — extend the `ModelProvider` SPI with `resolve(ModelRef)→ChatModel`; add `langchain4j-core` to
  `forvum-sdk`.** AC-1. Doc-faithful (ULTRAPLAN §4.3.5.1). *(Recommended: accept — the provider
  milestones were always going to force this; M9 is the right place.)*
- **D-2 — implement the discovery `BuildStep` now, in a dedicated `forvum-plugin-discovery` extension
  (not `forvum-engine`).** AC-2 + AC-5. *(Recommended: accept the dedicated-extension placement; the
  off-ramp in AC-5 stays open if the spike shows poor cost/value.)*
- **D-3 — `OllamaModelProvider.resolve` builds models programmatically per `ModelRef`** (vs a single
  statically-configured Quarkiverse bean). *(Recommended: programmatic — honors the `ModelRef` contract
  "resolve any model id".)*
- **D-4 — amend the `ForvumExtension` javadoc + ULTRAPLAN §6.3** to say the discovery BuildStep lives in
  `forvum-plugin-discovery`, not `forvum-engine`. *(Recommended: accept — keeps docs in sync, §10.)*

---

## 5. Files (new unless noted)

```
forvum-provider-ollama/pom.xml                                              # Layer-3 recipe + enforcer
forvum-provider-ollama/src/main/java/ai/forvum/provider/ollama/OllamaModelProvider.java
forvum-provider-ollama/src/main/resources/META-INF/forvum/plugin.json
forvum-provider-ollama/src/main/resources/META-INF/beans.xml
forvum-provider-ollama/src/test/java/ai/forvum/provider/ollama/OllamaModelProviderTest.java      # contract, M7-independent
forvum-provider-ollama/src/test/java/ai/forvum/provider/ollama/OllamaTurnIT.java                 # GATED on M7 (AgentRegistry e2e)

forvum-plugin-discovery/pom.xml                                             # runtime module
forvum-plugin-discovery/src/main/java/ai/forvum/plugin/discovery/PluginRegistry.java
forvum-plugin-discovery-deployment/pom.xml
forvum-plugin-discovery-deployment/src/main/java/ai/forvum/plugin/discovery/deployment/PluginDiscoveryProcessor.java

forvum-sdk/src/main/java/ai/forvum/sdk/ModelProvider.java                   # MODIFIED: + resolve(ModelRef)
forvum-sdk/pom.xml                                                          # MODIFIED: + langchain4j-core + enforcer allowlist
pom.xml                                                                     # MODIFIED: + 3 modules
forvum-app/pom.xml                                                          # MODIFIED: + 2 deps
forvum-app/src/test/java/ai/forvum/e2e/OllamaScriptedTurnE2E.java           # native smoke (Risk #5), gated on M7
```

---

## 6. Verify / tests

- **Contract (M7-independent, CI-green always):** `OllamaModelProviderTest` — `extensionId()=="ollama"`;
  `resolve(ModelRef.parse("ollama:qwen3:1.7b"))` returns a usable `ChatModel` (against a stubbed/local
  endpoint). Enforcer: `./mvnw -DskipTests validate` passes (plugin depends only on `forvum-sdk`).
- **Discovery `BuildStep`:** an `@QuarkusTest` in `forvum-app` (or the deployment module's internal test
  harness) asserts the `PluginRegistry` lists the `ollama` provider and that its class is registered for
  reflection.
- **E2E (ULTRAPLAN §7.1, GATED on M7):** with `ollama serve` running `qwen3:1.7b`, a scripted turn
  through `AgentRegistry` yields a non-empty assistant message and ≥1 `provider_calls` row with
  `provider='ollama'`. `@EnabledIf`/class-presence-gated until M7 merges; un-gated after.
- **Native (Risk #5, mandatory):** the `forvum-app` `-Pnative` build runs `OllamaScriptedTurnE2E`
  against the native binary (no `~/.forvum/` for the boot smoke; a seeded `~/.forvum/` for the scripted
  turn), asserting a non-empty reply + the `provider_calls` row, within the §6.2 cold-start budget. Runs
  on `linux-amd64` + `macos-arm64`, both builders.
- Engine/library tests via Surefire; the Dev-MCP test runner applies only to future HTTP-bearing modules.

---

## 7. Step-0 spikes (before bulk code)
1. `quarkus/create` throwaway → harvest `quarkus-langchain4j-ollama` coordinates + the current platform
   version; transplant into `forvum-bom`/module pom.
2. `quarkus/skills`(langchain4j-ollama) + `context7`(LangChain4j Ollama) → confirm the `OllamaChatModel`
   builder API used in `resolve` (D-3).
3. **D-2 deployment spike:** `quarkus/searchDocs` "writing extension build step / ReflectiveClassBuildItem
   / scan resources at build time" → validate the `forvum-plugin-discovery` pair native-compiles and does
   **not** reintroduce a reactor cycle; confirm `forvum-engine` stays a plain library. If red, take the
   AC-5 off-ramp.

---

## 8. Risks
- **R-1 — SDK dep creep.** Adding `langchain4j-core` to `forvum-sdk` widens the plugin compile surface.
  Mitigation: it is the doc-sanctioned SPI return type; keep the SDK otherwise leaf-thin (no Quarkus).
- **R-2 — deployment-extension complexity (the heaviest part).** Mitigation: dedicated module pair
  (not engine), D-2 spike first, AC-5 off-ramp documented.
- **R-3 — native per-provider smoke flakiness** (needs a live local Ollama). Mitigation: pin
  `qwen3:1.7b`; run as a CI service; keep the contract test (no Ollama) as the always-green gate.
- **R-4 — M7 soft edge.** The e2e needs M7's `AgentRegistry` + the `LlmSelector.resolve` signature.
  Mitigation: agree the SPI signature (AC-1) up front; gate the e2e; M7's `FakeModelProvider` implements
  the same SPI so both sides compile independently.
