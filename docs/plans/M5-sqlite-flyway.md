> **AUTHORITATIVE CORRECTIONS — apply these over the body below.**
> Folded in from the integration + adversarial review (see `README.md`).
>
> 1. **Risk #11 pinning spike is a BLOCKING, MEASURED step**, not optional. Do **not** pre-pick
>    posture (b) and demote the spike to `@Tag("spike")`. Run the 100-turn synthetic measurement
>    under `-Djdk.tracePinnedThreads=full` *first*; the measured result selects the posture and is
>    back-filled into ULTRAPLAN §3.8 + creates `pinning-allowlist.txt`. (review GAP 1)
> 2. **The Layer-0 reflection holder uses `io.quarkus.runtime.annotations.RegisterForReflection`
>    directly** — the SDK re-export (`ai.forvum.sdk.RegisterForReflection`) is **inert** (its
>    translating `BuildStep` does not exist yet), so it would silently register nothing. M5 owns the
>    SINGLE holder at `…/engine/persistence/CoreReflectionRegistration.java`; M8 APPENDS to it.
>    (review GAP 2 / integration decision a)
> 3. **`forvum-engine/pom.xml` does NOT yet declare `forvum-core`.** Add it (ideally in the prelude
>    commit on `main`, see `README.md`). The entities, `PanacheBudgetMeter`, and the holder all need it.
>    (review GAP 3 / GT-1)
> 4. **Verify the no-`~/.forvum/` native smoke is not broken by `migrate-at-start`** forcing a boot
>    connection — confirm via `quarkus_searchDocs` and gate if needed. (review GAP 4)

# M5 — SQLite + Flyway V1 (persistence in `forvum-engine`): Implementation Plan

## 1. Objective & Definition of Done

Add the operational persistence layer to `forvum-engine`: a Flyway-managed SQLite schema (V1 baseline reproducing ULTRAPLAN §4.2), Panache entities per table, an Agroal JDBC datasource at `$FORVUM_HOME/state/forvum.sqlite` with WAL, Hibernate ORM via the SQLite community dialect, the default `BudgetMeter` bean owning the budget SQL over `provider_calls`, the `@RegisterForReflection(targets={...})` holder enumerating the Layer-0 core records, and graceful boot when `$FORVUM_HOME/state/` is absent. `forvum-engine` is wired into `forvum-app` (already true) so it native-compiles.

**Literal success criterion (§7.1 M5 Verify):**
```
mvn -pl forvum-engine test -Dtest=SchemaSmokeIT
```
This boots Quarkus in-JVM, migrates a fresh SQLite file, inserts one row per table, and dumps the schema against a golden file. Because `forvum-engine` is a headless Quarkus *library* (no `build` goal, no HTTP), `quarkus:dev` cannot attach — so per CLAUDE.md §4 this `@QuarkusTest`/`*IT` runs via **plain Surefire**, NOT the Dev MCP. The exact command run with the harness ANSI guard is `./mvnw -pl forvum-engine test -Dtest=SchemaSmokeIT -B -Dstyle.color=never`.

DoD: `SchemaSmokeIT` green via Surefire; all existing engine config tests still green; engine still native-compiles inside `forvum-app`; `forvum-app` JVM package + native smoke boot with no `~/.forvum/` present.

## 2. Current relevant state

Inspected, real, present:
- `forvum-engine/pom.xml` — Layer-2 library: `quarkus-arc` + `quarkus-jackson`, `quarkus-junit` (test), `quarkus-maven-plugin` with `generate-code`/`generate-code-tests` only (NO `build` goal), enforcer `enforce-engine-extension-agnostic` allowlisting only `forvum-core`+`forvum-sdk`. No `application.properties` yet.
- `forvum-engine/src/main/resources/META-INF/beans.xml` — present, `bean-discovery-mode="annotated"`.
- `forvum-engine/src/main/java/ai/forvum/engine/config/ForvumHome.java` — `@Singleton`, resolves `$FORVUM_HOME` via `@ConfigProperty("forvum.home")`; has `root()`, `configFile()`, `agents()`, etc. **No `state()` accessor yet** — net-new method needed (`root.resolve("state")`).
- `forvum-engine/src/main/java/ai/forvum/engine/config/ConfigWatcher.java` — the canonical graceful-boot pattern (`@Observes StartupEvent`, warn+no-op when root absent). `state/` is explicitly excluded from `WATCHED_SUBFOLDERS`.
- `forvum-engine/src/test/java/ai/forvum/engine/config/TestHomeProfile.java` — `QuarkusTestProfile` redirecting `forvum.home` to a temp dir via `getConfigOverrides()`. Reuse pattern for persistence IT.
- `forvum-core/src/main/java/ai/forvum/core/...` — Layer-0 records already exist: `ai.forvum.core.id.AgentId`, `ai.forvum.core.id.Identity`, `ai.forvum.core.ChannelMessage`, `ai.forvum.core.Persona`, `ai.forvum.core.ToolSpec`, `ai.forvum.core.ModelRef`; budget records in `ai.forvum.core.budget` (`CostBudget`, `Spend`, `Usage`, `DayWindow`, `SessionWindow`, `Window`, `ExhaustionCause`, exceptions); `BudgetMeter` interface is at `ai.forvum.core.budget.BudgetMeter` (single method `Usage usage(CostBudget budget)`). None carry `@RegisterForReflection` (correct — Layer-0 ban).
- `forvum-bom/pom.xml` — **already manages `org.xerial:sqlite-jdbc` at `3.53.1.0`** under `<dependencyManagement>` (version property `sqlite-jdbc.version`). Quarkus platform BOM + langchain4j BOM imported. `hibernate-community-dialects` and `quarkus-flyway`/`quarkus-hibernate-orm-panache`/`quarkus-agroal` are NOT yet managed/declared.
- `forvum-app/pom.xml` — already depends on `forvum-engine`; has the `build` goal and the `native` profile + Failsafe `*IT` smoke. This is where M5 native-compiles.
- Quarkus platform version in scope: **3.33.x** (confirmed via Dev MCP doc results).

Net-new directories/files (no module is created; everything lands inside the existing `forvum-engine`):
- `forvum-engine/src/main/resources/db/migration/` (Flyway migrations).
- `forvum-engine/src/main/resources/application.properties` (net-new).
- `forvum-engine/src/main/java/ai/forvum/engine/persistence/` (entities, BudgetMeter impl, PersistenceBootstrap, reflection holder).
- `forvum-engine/src/test/resources/` golden schema file + test config.

## 3. Design decisions to lock

**D1 — SQLite is NOT an out-of-box Quarkus JDBC extension; wire it as an "Other database" (CONFIRMED via Dev MCP).** The built-in `db-kind`s are db2/h2/mariadb/mssql/mysql/oracle/postgresql only. SQLite path:
- Deps: `quarkus-hibernate-orm-panache` (pulls `quarkus-hibernate-orm` + `quarkus-agroal`), `quarkus-flyway`, bare `org.xerial:sqlite-jdbc` (driver only, no Quarkus JDBC wrapper exists), `org.hibernate.orm:hibernate-community-dialects`.
- `quarkus.datasource.db-kind=other`, `quarkus.datasource.jdbc.driver=org.sqlite.JDBC`, explicit `quarkus.hibernate-orm.dialect=org.hibernate.community.dialect.SQLiteDialect`.
Rationale: §3.4 mandates Xerial + Hibernate + Panache + Flyway; the doc's "deps" list (§7.1 M5) omits `quarkus-agroal` only because Panache pulls it transitively, and omits `db-kind=other`/driver class because they are config, not deps — flag this as a doc gap to back-fill.

**D2 — JDBC URL is computed at runtime from `ForvumHome`, not a static `application.properties` literal (Risk parity with M4 graceful boot).** A static `jdbc:sqlite:${forvum.home}/state/forvum.sqlite` cannot honor M4's `forvum.home` resolution precedence (system prop → `FORVUM_HOME` env → `<user.home>/.forvum`) for the *directory-creation* and *absent-state* cases. Recommended: a `@Singleton` producer (`DatasourceConfig`/`PersistenceBootstrap`) that (a) on `@Observes StartupEvent`, computes `home.state()`, creates the `state/` dir if the parent `$FORVUM_HOME` already exists, and (b) supplies the JDBC URL. Practical mechanism: set `quarkus.datasource.jdbc.url` via a small `@StaticInitSafe ConfigSource` or — simpler and native-safe — keep `application.properties` with `quarkus.datasource.jdbc.url=jdbc:sqlite:${forvum.home}/state/forvum.sqlite?...` using MP-Config property expansion (Quarkus expands `${forvum.home}`), and add a `state/` dir-ensure step in a `@Startup`/`StartupEvent` observer that runs before first connection use. **Recommended answer:** use property expansion for the URL (`jdbc:sqlite:${forvum.home}/state/forvum.sqlite`) PLUS a `StateDirInitializer` `@Observes StartupEvent` bean that `Files.createDirectories(home.state())` *only when* `$FORVUM_HOME` root resolves to an existing parent or is the default — and that swallows/`warnf`s when creation is impossible so the native smoke (no `~/.forvum/`) still boots. Justification: §3.4 "JDBC URL pointing at `$FORVUM_HOME/state/forvum.sqlite`"; M4 lesson "every `@Startup` bean boot gracefully when its inputs are absent."

**D3 — Graceful-boot for the no-`~/.forvum/` native smoke: gate `migrate-at-start` + connection at runtime, not build time.** Flyway `migrate-at-start=true` runs at first datasource use. The native smoke runs the binary in command-mode with no `~/.forvum/`. Two sub-decisions:
- (a) `quarkus.flyway.migrate-at-start=true` is set in `%prod` and test profiles; the *trigger* of migration is lazy (first connection). If the app exits command-mode without touching the DB, no migration occurs and no crash. Recommended: keep `migrate-at-start=true` but ensure the native smoke path does not force a connection; the `StateDirInitializer` must `createDirectories` defensively. SQLite *creates the file* on first connect, so if a connection IS forced, the parent `state/` dir must exist — hence `StateDirInitializer`.
- (b) If `$FORVUM_HOME` cannot be created (read-only FS / CI sandbox), log a warning and skip dir creation; let any forced connection fail loudly only in a real run, never at boot. Rationale: M4 lesson + §6.4 (the only sanctioned behavioral native carve-out is M4 WatchService; M5 must actually boot).

**D4 — WAL + busy_timeout pragmas via the JDBC URL query string.** Xerial honors `?journal_mode=WAL&busy_timeout=5000&foreign_keys=on` appended to the URL (or via `org.sqlite.SQLiteConfig`). Recommended URL: `jdbc:sqlite:${forvum.home}/state/forvum.sqlite?journal_mode=WAL&busy_timeout=5000&foreign_keys=on`. `foreign_keys=on` is required for the `messages.session_id REFERENCES sessions(id) ON DELETE CASCADE` FK in §4.2 to be enforced (SQLite defaults FKs off). Rationale: §3.4 "WAL mode is enabled explicitly in the JDBC URL"; §4.2 FK semantics. Also set `quarkus.datasource.jdbc.max-size=1` (single writer) — SQLite is single-writer; a pool >1 invites `SQLITE_BUSY`. Flag the `max-size=1` choice as a §3.8/Risk #11-adjacent decision to record.

**D5 — `@RegisterForReflection(targets={...})` holder ownership: place it in M5, in `forvum-engine`.** The doc (§6.3) says "M5/M6" and "lands in the milestone that first serializes those types in native." M5 is the first milestone that persists/serializes Layer-0 records (`AgentId` as `agent_id`, budget records via `BudgetMeter`/`Usage`, and entities mapping core types). **Recommended: M5 owns it.** Create `forvum-engine/src/main/java/ai/forvum/engine/persistence/CoreReflectionRegistration.java`:
```java
@RegisterForReflection(targets = {
    AgentId.class, Identity.class, ChannelMessage.class, Persona.class, ToolSpec.class, ModelRef.class,
    CostBudget.class, Spend.class, Usage.class, DayWindow.class, SessionWindow.class, ExhaustionCause.class
})
public final class CoreReflectionRegistration {}
```
Compile-time `.class` references break the build on a core rename/removal (the doc's stated intent). Justification: §6.3 "Layer-0 types registered from forvum-engine via a single holder"; M6 (`@AgentScoped`) does not serialize core records, so M5 is the natural first-serializer. **Cross-milestone note: tell the M6 integrator the holder already exists — M6 must NOT create a second one; it only adds graph-state types (those land at the LangGraph4j milestone, not M6).** Use the SDK re-exported annotation `ai.forvum.sdk.RegisterForReflection` (per §2.2 re-export) — confirm whether engine already imports `io.quarkus.runtime.annotations.RegisterForReflection` directly; the existing SDK file `forvum-sdk/.../RegisterForReflection.java` suggests the project standard is the SDK re-export. **Recommended: use `io.quarkus.runtime.annotations.RegisterForReflection` directly in the engine** (engine is Layer-2 and already depends on Quarkus; the SDK re-export exists for Layer-3 plugin authors). Flag this for integrator confirmation.

**D6 — Risk #11 pinning SPIKE is a planned, time-boxed measurement step, not a code deliverable that gates `SchemaSmokeIT`.** The SPIKE produces a decision back-filled into §3.8; the recommended *default to implement* (so M5 ships a posture) is **(b) explicit `@Blocking`/virtual-thread-aware transaction boundaries with `max-size=1`** as the v0.1 posture, because (a) a managed platform-thread executor adds a thread hop on every transaction and contradicts "virtual-threads-first" except as a carve-out, and (c) no loom-friendly SQLite JDBC driver exists today (Xerial uses `synchronized` JNI — confirmed by the doc). The SPIKE (synthetic 100-turn run hitting `messages` + `provider_calls` under `-Djdk.tracePinnedThreads=full`) decides whether the pin is *bounded* (acceptable: one carrier pinned briefly per single-writer txn) or *unbounded* (then escalate to option (a) a dedicated platform-thread executor for DB transactions). Recommended decision rule: with `max-size=1` and short transactions, pins are bounded → ship (b); record in §3.8 + add any documented carve-out fingerprint to `forvum-engine/src/test/resources/pinning-allowlist.txt` (§3.8 mentions this file; verify it exists, create if net-new). Flag: the allowlist file is referenced by §3.8 but may not exist yet — net-new.

**D7 — `capr_events.turn_id` is INTEGER in V1 (references `messages.id`), changes to TEXT/UUID in V2.** V1 only. Do NOT pre-apply V2. The Verify golden file is the V1-only schema (one migration). **Recommend shipping ONLY `V1__baseline.sql` in M5**; V2 is a later milestone (the `@AgentScoped`/`ScopedValue<UUID> turnId` work, M6+). The §4.2 V2 block is documented but out of M5 scope — note this explicitly so the golden `.schema` matches V1.

**D8 — Panache entity style: `PanacheEntityBase` with explicit `@Id`, NOT `PanacheEntity`.** Tables mix `INTEGER PRIMARY KEY AUTOINCREMENT` (messages, episodic_memory, semantic_memory, tool_invocations, provider_calls, capr_events) with `TEXT PRIMARY KEY` (sessions). `PanacheEntity` forces a `Long id` named `id` — fine for the autoincrement tables but wrong for `sessions` (TEXT PK). Recommended: use `PanacheEntityBase` + explicit `@Id`/`@GeneratedValue` per entity so types match the DDL exactly (TEXT PK for `SessionEntity`, `Long` IDENTITY for the rest). Hibernate must NOT own DDL — set `quarkus.hibernate-orm.schema-management.strategy=none` (Flyway is the single source of truth; §4.2 "Hibernate never owns the schema, Flyway does, forward-only"). Validate entity↔DDL drift via `validate` is risky on SQLite's loose typing — recommend `strategy=none` and rely on `SchemaSmokeIT` insert-per-table to catch mapping drift.

## 4. Step-by-step TDD plan

Order matters: schema first (the contract), then config wiring, then entities, then BudgetMeter, then reflection holder, then app native wiring.

**Step 0 (tooling, no code):** Run `quarkus_skills` for `hibernate-orm-panache,flyway,agroal` against `forvum-engine`, and `quarkus_searchDocs` for "db-kind other sqlite dialect" + "flyway native resources". (Detailed in §10.)

**Step 1 — Flyway V1 baseline (Red→Green via SchemaSmokeIT later; this step is the schema artifact).**
- Create `forvum-engine/src/main/resources/db/migration/V1__baseline.sql` — verbatim §4.2 V1 (the 7 tables + all indexes): `sessions`, `messages`, `episodic_memory`, `semantic_memory`, `tool_invocations`, `provider_calls`, `capr_events`, with every index listed in §4.2. No V2.
- Golden file: `forvum-engine/src/test/resources/schema/golden-v1.schema` — the canonical `.schema` dump SQLite produces after V1 (includes Flyway's own `flyway_schema_history` table — decide: the golden either filters it out in the test or includes it; recommend the test dumps user tables only via `SELECT sql FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history' ORDER BY name`).

**Step 2 — `application.properties` (engine) — Red: SchemaSmokeIT cannot boot a datasource without it.**
Create `forvum-engine/src/main/resources/application.properties`:
```
quarkus.datasource.db-kind=other
quarkus.datasource.jdbc.driver=org.sqlite.JDBC
quarkus.datasource.jdbc.url=jdbc:sqlite:${forvum.home:${user.home}/.forvum}/state/forvum.sqlite?journal_mode=WAL&busy_timeout=5000&foreign_keys=on
quarkus.datasource.jdbc.max-size=1
quarkus.hibernate-orm.dialect=org.hibernate.community.dialect.SQLiteDialect
quarkus.hibernate-orm.schema-management.strategy=none
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=db/migration
```
Note: confirm the `forvum.home` default-expansion syntax works with MP Config; if not, the URL is set by a programmatic `ConfigSource`/producer (D2). The IT overrides the URL to a `@TempDir` file.

**Step 3 — `ForvumHome.state()` accessor — Red: `ForvumHomeTest` asserting `home.state()` equals `root/state`.**
- Test (add to existing `ForvumHomeTest`): `assertThat(home.state()).isEqualTo(root.resolve("state"))`.
- Green: add `public Path state() { return root.resolve("state"); }` to `ForvumHome`.
- Verify: `./mvnw -pl forvum-engine test -Dtest=ForvumHomeTest -B -Dstyle.color=never`.

**Step 4 — `StateDirInitializer` graceful-boot bean — Red: `StateDirInitializerTest`/IT asserting boot with absent `$FORVUM_HOME` does NOT throw and logs a warning; and that with a present root the `state/` dir is created.**
- Test: unit-style test invoking `ensureStateDir()` with a temp root (dir created) and with a non-existent/unwritable root (no throw). Plus an IT under a `QuarkusTestProfile` pointing `forvum.home` at a temp dir, asserting boot succeeds.
- Green: `forvum-engine/src/main/java/ai/forvum/engine/persistence/StateDirInitializer.java`, `@Singleton`, `void onStart(@Observes StartupEvent ev)` → `try { Files.createDirectories(home.state()); } catch (IOException e) { LOG.warnf(...); }`. Mirror `ConfigWatcher.start()` warn+no-op idiom.
- Verify via Surefire.

**Step 5 — Panache entities — Red: per-entity mapping is exercised by SchemaSmokeIT's insert-one-row-per-table.**
- Create under `forvum-engine/src/main/java/ai/forvum/engine/persistence/`: `SessionEntity` (`@Table(name="sessions")`, `@Id String id`), `MessageEntity`, `EpisodicMemoryEntity`, `SemanticMemoryEntity`, `ToolInvocationEntity`, `ProviderCallEntity`, `CaprEventEntity` — all `extends PanacheEntityBase`, columns mapped 1:1 to §4.2 (millis-since-epoch `long created_at`, `String role/status/event_type`, `byte[] embedding` BLOB, `Double cost_usd` nullable, `int is_fallback`). Use `@GeneratedValue(strategy=IDENTITY)` for the AUTOINCREMENT tables.
- These are persistence entities, not JSON DTOs — they do NOT need `@RegisterForReflection` themselves (Hibernate registers entities for native automatically via the Quarkus Hibernate extension). Confirm in `quarkus_skills`.

**Step 6 — Default `BudgetMeter` bean — Red: `BudgetMeterIT` (`@QuarkusTest`, `@TempDir` SQLite) inserts `provider_calls` rows and asserts `usage(costBudget)` returns correct `spent`/`remaining`/`exhausted`/`cause`.**
- Test cases: (a) USD-only budget, rows with `cost_usd` summed, NULL `cost_usd` rows contribute zero (Decision 3); (b) token-only budget sums `tokens_in+tokens_out`; (c) both caps → `BOTH_CAPS_HIT`; (d) `DayWindow` filters by `created_at >= midnight(tz)`, `SessionWindow` filters by `(session_id, agent_id)`; (e) exhausted/cause biconditional honored by `Usage` constructor.
- Green: `forvum-engine/src/main/java/ai/forvum/engine/persistence/PanacheBudgetMeter.java implements ai.forvum.core.budget.BudgetMeter`, `@Singleton`. Pattern-match on `Window` permit (`switch` over `DayWindow`/`SessionWindow`, exhaustive) to build the SQL filter; single `SUM(cost_usd), SUM(tokens_in+tokens_out)` aggregation over `provider_calls` (Decision 7 atomic snapshot). Use Panache `getEntityManager().createQuery(...)`/`Provider­CallEntity.getEntityManager()` for the aggregate. NO `synchronized` (§3.8). Per §4.3.5.2 it owns the SQL; §4.3.5.2 ships only the interface (already in core).
- Verify via Surefire.

**Step 7 — `CoreReflectionRegistration` holder (Decision 5).**
- Create the holder enumerating Layer-0 records. No dedicated test (compile-time check is the test); it is exercised by the native build in `forvum-app`.

**Step 8 — `SchemaSmokeIT` (the §7.1 Verify, Green is the milestone gate).**
- `forvum-engine/src/test/java/ai/forvum/engine/persistence/SchemaSmokeIT.java`, `@QuarkusTest` + a `QuarkusTestProfile` (clone `TestHomeProfile`) pointing `forvum.home` at a `@TempDir`, so the JDBC URL resolves to `<temp>/state/forvum.sqlite`.
  - Asserts Flyway migrated a fresh file (table `flyway_schema_history` present, version `1` success).
  - Inserts exactly one row per entity (all 7 tables), `@Transactional`.
  - Dumps the schema (`SELECT sql FROM sqlite_master ... ORDER BY name`) and compares to `golden-v1.schema` (normalize whitespace).
- Verify (the literal contract): `./mvnw -pl forvum-engine test -Dtest=SchemaSmokeIT -B -Dstyle.color=never`, then read `forvum-engine/target/surefire-reports/ai.forvum.engine.persistence.SchemaSmokeIT.txt`.

**Step 9 — `forvum-bom` + `forvum-engine/pom.xml` dependency wiring (§6).** (Done alongside Step 2 so the module compiles.)

**Step 10 — `forvum-app` native compile + JVM package smoke.**
- `forvum-engine` is already an `forvum-app` dependency, so M5 native-compiles automatically. Verify the app still packages: `./mvnw -pl forvum-app -am package -B -Dstyle.color=never` (JVM fast-jar) and that the existing `forvum-app` native smoke `*IT` still boots with no `~/.forvum/` (Failsafe under `-Pnative` — CI). Confirm `StateDirInitializer` warn+no-ops in that path.

**Step 11 — Risk #11 pinning SPIKE (measurement, Decision 6).** Add a `@Tag("spike")` (default-off) or a scratch `BudgetMeterPinningSpikeTest` running a synthetic 100-turn loop hitting `messages` + `provider_calls` under `-Djdk.tracePinnedThreads=full`; grep output for `Thread pinned`; record the verdict and back-fill §3.8 + `pinning-allowlist.txt`. Not part of the `SchemaSmokeIT` gate.

## 5. Files — created/modified (mapped to §7.1 M5 Files)

Created:
- `forvum-engine/src/main/resources/db/migration/V1__baseline.sql` — §4.2 V1 DDL (7 tables + indexes). [§7.1 file 1]
- `forvum-engine/src/main/resources/application.properties` — datasource/dialect/flyway config. [§7.1 file 2]
- `forvum-engine/src/main/java/ai/forvum/engine/persistence/SessionEntity.java`, `MessageEntity.java`, `EpisodicMemoryEntity.java`, `SemanticMemoryEntity.java`, `ToolInvocationEntity.java`, `ProviderCallEntity.java`, `CaprEventEntity.java` — Panache entities. [§7.1 file 3 dir]
- `forvum-engine/src/main/java/ai/forvum/engine/persistence/PanacheBudgetMeter.java` — default `BudgetMeter` (§4.3.5.2).
- `forvum-engine/src/main/java/ai/forvum/engine/persistence/StateDirInitializer.java` — graceful `state/` dir-ensure on `StartupEvent`.
- `forvum-engine/src/main/java/ai/forvum/engine/persistence/CoreReflectionRegistration.java` — Layer-0 reflection holder (§6.3).
- `forvum-engine/src/test/java/ai/forvum/engine/persistence/SchemaSmokeIT.java` — the Verify IT.
- `forvum-engine/src/test/java/ai/forvum/engine/persistence/BudgetMeterIT.java` — BudgetMeter IT.
- `forvum-engine/src/test/java/ai/forvum/engine/persistence/PersistenceTestHomeProfile.java` — `QuarkusTestProfile` redirecting `forvum.home` to `@TempDir`.
- `forvum-engine/src/test/resources/schema/golden-v1.schema` — golden `.schema`.
- `forvum-engine/src/test/resources/pinning-allowlist.txt` — if net-new (§3.8); documents bounded SQLite JNI pins.

Modified:
- `forvum-bom/pom.xml` — add `quarkus-hibernate-orm-panache`, `quarkus-flyway` (platform-managed, no version), `org.hibernate.orm:hibernate-community-dialects` (version via platform BOM if managed there; else a property) under `<dependencyManagement>`. `sqlite-jdbc` already present.
- `forvum-engine/pom.xml` — add `<dependency>` entries (no versions) for the above. `quarkus-agroal` comes transitively via Panache (confirm; add explicitly only if needed). Existing `META-INF/beans.xml` already marks the bean archive — no change needed (entities + `@Singleton` beans are discovered).
- `forvum-engine/src/main/java/ai/forvum/engine/config/ForvumHome.java` — add `state()` accessor.
- `forvum-app/pom.xml` — likely NO change (already depends on `forvum-engine`). Native reachability metadata for SQLite is config-driven (§7 below); if a `native-image.properties` is needed it lives in `forvum-engine/src/main/resources/META-INF/native-image/`.

Wiring details:
- Flyway migrations under `db/migration/` are auto-registered as native resources by `quarkus-flyway` (§6.3). No manual resource hint needed for the SQL files.
- `forvum-engine` keeps NO `build` goal (library) — unchanged.

## 6. Dependencies (exact coordinates, BOM-managed, never version-pinned in module poms)

In `forvum-bom/pom.xml` `<dependencyManagement>`:
- `io.quarkus:quarkus-hibernate-orm-panache` — version from the imported Quarkus platform BOM (already imported). Do NOT pin.
- `io.quarkus:quarkus-flyway` — version from Quarkus platform BOM. Do NOT pin.
- `io.quarkus:quarkus-agroal` — transitive via Panache; manage only if you want it explicit. Version from platform BOM.
- `org.hibernate.orm:hibernate-community-dialects` — version aligned to Hibernate ORM shipped by the platform; the platform BOM may already manage `org.hibernate.orm:*` (verify in `quarkus_searchDocs`); if not, add a `hibernate.version`-aligned `<version>` property. **Prefer letting the platform BOM align it** — pin only if unmanaged.
- `org.xerial:sqlite-jdbc` — ALREADY managed at `3.53.1.0` (§4.2 says ≥3.40.1.0). No change.

In `forvum-engine/pom.xml` `<dependencies>` (versionless — managed by `forvum-bom` which is imported there):
- `io.quarkus:quarkus-hibernate-orm-panache`
- `io.quarkus:quarkus-flyway`
- `org.xerial:sqlite-jdbc`
- `org.hibernate.orm:hibernate-community-dialects`

All four carry no `<version>` — versions resolve through the imported `forvum-bom` → platform BOM (CLAUDE.md §2/§7: versions managed by BOMs, never pinned in module poms).

## 7. Native-first checklist

- **`@RegisterForReflection` placements:** entities do NOT need it (Hibernate extension registers them). DTO/JSON records: none net-new in M5 (the budget records live in Layer-0 core). The **Layer-0 holder** `CoreReflectionRegistration` in `forvum-engine` carries `@RegisterForReflection(targets={...})` enumerating `AgentId, Identity, ChannelMessage, Persona, ToolSpec, ModelRef, CostBudget, Spend, Usage, DayWindow, SessionWindow, ExhaustionCause` (Decision 5). `forvum-core` records stay annotation-free (Layer-0 exemption, §6.3).
- **forvum-core exemption honored:** no `io.quarkus*` import added to `forvum-core` in M5.
- **Reachability metadata:** SQLite JDBC ships its own native-image JNI config (§6.3) — no hand-authored hints for the driver. Flyway migrations under `db/migration/` are auto-registered native resources by `quarkus-flyway`. If the native build cannot find `org.hibernate.community.dialect.SQLiteDialect`, add a one-line reflection hint via `quarkus.native.additional-build-args=--initialize-at-run-time=...` or a `reflect-config.json` under `forvum-engine/src/main/resources/META-INF/native-image/ai.forvum/forvum-engine/` — confirm necessity during the app native build, do not pre-add speculatively.
- **SQLite native lib exportPath (§6.3, §3.4):** add to the `native` profile (in `forvum-app/pom.xml`, where native builds) `quarkus.native.additional-build-args` or a system prop `-Dorg.sqlite.lib.exportPath=${project.build.directory}` and runtime `org.sqlite.lib.path`. Since the native profile lives in `forvum-app`, the exportPath wiring belongs there. Note this as the one app-side change M5 may need.
- **No reflection confirmation:** entities use field/property access via Hibernate (framework-managed, allowed); `PanacheBudgetMeter` uses typed JPQL, no classpath scanning; no `synchronized` (§3.8).
- **Wired into `forvum-app`:** already a dependency → native-compiles in M5 (M4 lesson satisfied).
- **Graceful boot without `~/.forvum/`:** `StateDirInitializer` warn+no-ops; `migrate-at-start` triggers lazily on first connection; the native smoke (command-mode, no DB touch, no `~/.forvum/`) boots clean (Decision 3).

## 8. Tests — what runs where

- **Unit (`*Test`, Surefire, no Quarkus boot):** `ForvumHomeTest` (add `state()` assertion), `StateDirInitializerTest` (pure dir-ensure + absent-root no-throw). Run via Surefire.
- **Integration (`*IT`, `@QuarkusTest` + `@TempDir` SQLite via `PersistenceTestHomeProfile`):** `SchemaSmokeIT` (the Verify), `BudgetMeterIT`. Run via **plain Surefire** (`./mvnw -pl forvum-engine test`) — `forvum-engine` is a headless library; Dev MCP runner cannot attach (CLAUDE.md §4 exception). Read `target/surefire-reports/*.txt`.
- **Native parity:** built only in `forvum-app` under `-Pnative` (CI); the existing app `*IT` native smoke must still boot with no `~/.forvum/`. M5 adds no new native behavioral assertion (schema correctness is asserted in JVM `SchemaSmokeIT`); native compile of the persistence layer is the parity gate.
- **Pinning SPIKE (`@Tag("spike")`, default-off):** `BudgetMeterPinningSpikeTest`, run manually with `-Djdk.tracePinnedThreads=full` (Decision 6).
- **Exact Verify command:** `mvn -pl forvum-engine test -Dtest=SchemaSmokeIT` (harness form: `./mvnw -pl forvum-engine test -Dtest=SchemaSmokeIT -B -Dstyle.color=never`).

Note for the implementer: `-Dtest=SchemaSmokeIT` with the `*IT` name runs under Surefire here (not Failsafe) because we invoke `test`, not `verify` — matching the §7.1 command literally. Ensure Surefire is not configured to exclude `*IT` in the engine pom (it currently is not).

## 9. Risks & mitigations

- **Risk #11 (JDBC/SQLite VT pinning):** the M5 deliverable. SPIKE + Decision 6 (ship posture (b) `@Blocking`/short-txn + `max-size=1`; escalate to (a) platform-thread executor if pins are unbounded). Back-fill §3.8 and `pinning-allowlist.txt`. The CI `Thread pinned` grep (§3.8) must allow only documented bounded SQLite JNI pins.
- **Risk #5 (per-provider native failure) — adjacent:** SQLite native lib export/`org.sqlite.lib.exportPath` (§6.3); mitigated by the app-side native-profile arg. If native fails to load the JNI lib, file an upstream issue, never mark JVM-only silently.
- **Risk #13 (library reachability metadata) — pattern parity:** Hibernate community dialect is a plain library class; if native cannot reach `SQLiteDialect`, add the reflect-config hint under `forvum-engine/src/main/resources/META-INF/native-image/`.
- **M4 lesson (graceful boot, §6.4):** `StateDirInitializer` must warn+no-op on absent/unwritable `$FORVUM_HOME`; otherwise the no-`~/.forvum/` native smoke fails.
- **Risk #12 (Mutiny out of engine):** persistence uses blocking Hibernate/Panache on virtual threads — no Mutiny introduced; the M5 source import-grep for `io.smallrye.mutiny` stays clean.

## 10. Quarkus/library tooling steps

Before writing code (mandatory, §7 / §3.9):
- `quarkus_skills` projectDir=`/Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine` query=`hibernate-orm-panache,flyway,agroal` — learn entity patterns (`PanacheEntityBase` vs `PanacheEntity`), `@Transactional` boundaries, native entity registration, Flyway config keys.
- `quarkus_searchDocs` query=`datasource db-kind other custom driver SQLite` (CONFIRMED: SQLite is "Other database", `db-kind=other` + bare driver + explicit dialect).
- `quarkus_searchDocs` query=`hibernate-orm dialect Other databases community dialect` (CONFIRMED: `quarkus.hibernate-orm.dialect=` explicit class).
- `quarkus_searchDocs` query=`flyway migrate-at-start locations native resources` (CONFIRMED: migrations auto-registered native resources).
- `quarkus_searchDocs` query=`hibernate-orm schema-management strategy none` — confirm Flyway-owns-schema config key for the platform version (3.33.x property name may be `quarkus.hibernate-orm.schema-management.strategy` or legacy `quarkus.hibernate-orm.database.generation`; verify and use the correct one).
- context7: not needed for M5 (no LangChain4j/LangGraph4j surface). SQLite/Xerial/Flyway specifics resolved via `quarkus_searchDocs` (Quarkus extensions) — do not use context7 for these.
- **No `quarkus_create` harvest step:** M5 adds NO new module; it extends the existing `forvum-engine` whose Quarkus-library pom recipe was already harvested at M4. Do NOT run `quarkus_create`/`quarkus_start` (mutating). Coordinates for the new extensions are platform-BOM-managed — transplant artifactIds (versionless) into `forvum-bom` + `forvum-engine/pom.xml`.

## 11. Commit(s)

- `feat(engine): add SQLite persistence with Flyway V1 baseline` (the §7.1 M5 commit; includes V1 SQL, `application.properties`, entities, `StateDirInitializer`, `ForvumHome.state()`, BOM/pom deps, `CoreReflectionRegistration`, `SchemaSmokeIT`).
- `feat(engine): add default Panache BudgetMeter over provider_calls` (if split; otherwise folded into the above).
- `docs(ultraplan): record Risk #11 JDBC pinning decision in section 3.8` (back-fill after the SPIKE).
- `chore(engine): document bounded SQLite JNI pins in pinning-allowlist` (if the SPIKE adds an allowlist entry).

## 12. Completion checklist

- [ ] `V1__baseline.sql` reproduces §4.2 V1 exactly: tables `sessions, messages, episodic_memory, semantic_memory, tool_invocations, provider_calls, capr_events` + every index; NO V2.
- [ ] `application.properties`: `db-kind=other`, driver `org.sqlite.JDBC`, URL `jdbc:sqlite:.../state/forvum.sqlite?journal_mode=WAL&busy_timeout=5000&foreign_keys=on`, `max-size=1`, dialect `SQLiteDialect`, schema strategy `none`, `flyway.migrate-at-start=true`.
- [ ] 7 Panache entities map 1:1 to DDL; insert-one-row-per-table passes.
- [ ] `PanacheBudgetMeter` returns correct `Usage` for USD-only / token-only / both / `DayWindow` / `SessionWindow`; NULL `cost_usd`→0; no `synchronized`.
- [ ] `ForvumHome.state()` added + tested.
- [ ] `StateDirInitializer` warn+no-ops on absent/unwritable root; boots clean.
- [ ] `CoreReflectionRegistration` holder enumerates all 12 Layer-0 records via `.class`.
- [ ] `forvum-core` carries no new `io.quarkus*` import.
- [ ] BOM manages the 3 new deps versionless; engine pom references them versionless.
- [ ] `SchemaSmokeIT` green via Surefire: `./mvnw -pl forvum-engine test -Dtest=SchemaSmokeIT -B -Dstyle.color=never`; golden `.schema` matches.
- [ ] Existing engine config tests still green.
- [ ] `forvum-app -am package` (JVM) succeeds; native smoke boots with no `~/.forvum/` (CI).
- [ ] Risk #11 SPIKE run; decision back-filled into §3.8; allowlist updated if needed.
- [ ] `/code-review` (high/ultra) run before merge (M4 lesson).

## 13. Cross-milestone coordination notes (M5 / M6 / M8 integrator)

- **`CoreReflectionRegistration` holder (shared with M6):** M5 creates the single Layer-0 reflection holder at `forvum-engine/src/main/java/ai/forvum/engine/persistence/CoreReflectionRegistration.java`. **M6 must NOT create a second holder.** If M6/M8 introduce engine-serialized core types, they ADD `.class` entries to this one holder. The LangGraph4j graph-state records (§6.3) are a separate later concern, not M6.
- **`ForvumHome` (shared with M6/M8):** M5 adds `state()`. M6 (`@AgentScoped`/`ScopedValue<AgentId>`) does not touch `ForvumHome`. If M8 touches `ForvumHome`, sequence M5 first (additive method).
- **`application.properties` (single shared file):** M5 creates `forvum-engine/src/main/resources/application.properties`. M6 (`@AgentScoped` context) and M8 may need to append keys (e.g. ArC context registration is code, not config; M6 likely none). Whoever lands second must MERGE, not overwrite. Recommend M5 lands first so the file exists.
- **`provider_calls` schema is the budget ledger (§4.3.5.1, shared with the FallbackChatModel milestone):** M5 owns the table and `PanacheBudgetMeter`. The `FallbackChatModel` enforcement caller (later) depends on M5's `BudgetMeter` impl — sequence M5 before any budget-enforcement milestone.
- **`turn_id` / V2 (M6 territory):** V2 (`turn_id` columns, `capr_events` recreate) belongs to the `ScopedValue<UUID> turnId` milestone (M6+), NOT M5. M5 ships V1 only; the integrator must ensure V2 is NOT slipped into M5 (Flyway is forward-only; a premature V2 would break the golden `.schema` and the immutable-migration CI check).
- **§3.8 back-fill (doc, shared surface):** M5's Risk #11 decision edits ULTRAPLAN §3.8 and possibly creates `forvum-engine/src/test/resources/pinning-allowlist.txt` referenced by the §3.8 CI grep — coordinate with any sibling milestone editing §3.8.

### Critical Files for Implementation
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine/src/main/resources/db/migration/V1__baseline.sql
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine/src/main/resources/application.properties
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine/pom.xml
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-bom/pom.xml
- /Users/eldermoraes/git/eldermoraes/workdir/forvum/forvum-engine/src/main/java/ai/forvum/engine/config/ForvumHome.java
