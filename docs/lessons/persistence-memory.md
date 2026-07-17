# Implementation lessons — Persistence & memory

Extracted verbatim from CLAUDE.md §14. Append-only; when adding a lesson here, also add its index line in CLAUDE.md §14.

- **"Other database" (SQLite) needs Hibernate metadata access off + version-check off for a clean
  boot.** With `db-kind=other` + an explicit dialect, set
  `quarkus.hibernate-orm.unsupported-properties."hibernate.boot.allow_jdbc_metadata_access"=false` and
  `quarkus.hibernate-orm.database.version-check.enabled=false`, else Hibernate opens an eager JDBC
  connection on a startup thread (logging a spurious error, and failing the >=2.0.0 version check on a
  not-yet-created DB). Trigger Flyway manually from a `StartupEvent` observer after ensuring the DB
  directory exists — `quarkus.flyway.migrate-at-start` runs during RUNTIME_INIT, before any observer,
  so it would open the file before the dir exists (SQLITE_CANTOPEN). [M5]

- **A turn is made atomic by persist-after-success, not `@Transactional` over the whole turn.** A
  blanket `@Transactional respond()` would roll back the `provider_calls` audit row on a model failure.
  Build the model request with the user message in-memory, call the model, then persist
  user+assistant+observation in one transaction (`AgentMemory.recordTurn`) only on success — the failed
  attempt's ledger row survives in the decorator's own transaction. [M7]

- **Replaying a session is a `messages`+`tool_invocations` interleave whose merge key is turn-logical, not raw
  `created_at`.** A turn's user+assistant pair is committed atomically at turn-END (`AgentMemory.recordTurn`, M7
  persist-after-success) while each `tool_invocations` row is ledgered MID-turn — so a tool's `created_at`
  precedes its own turn's messages. A naive `ORDER BY created_at` merge would print the user message *after* the
  tools it triggered. `SessionReplayer.interleave()` instead walks messages in `id` order and flushes the
  not-yet-emitted tools whose `created_at <=` each *assistant* message (user → tools → assistant), draining any
  trailing tools (a turn that failed before persisting its reply) at the end. The view records
  (`ReplaySession`/`ReplaySegment`) carry NO `@RegisterForReflection` — like the `doctor` report records they are
  built from JDBC rows and printed, never serialized (the native IT proves it). The adversarial review reaffirmed
  the M4 lesson: a single-tool happy-path test left the multi-tool inner loop and the trailing-drain branch
  unexercised — seed the multi-tool and incomplete-turn fixtures, not just the one-tool case. [P2-8]

- **A DB-reading CLI (`forvum replay`) is NOT a `CommandMode` one-shot, and its deterministic native IT seeds the
  DB without a live LLM via a two-launch dance.** Unlike `doctor` (file-only), `replay` reads the SQLite store, so
  it must boot the full Flyway/Panache path — keep it OUT of `CommandMode.isOneShotCommand` (which would skip
  migration). Its native IT therefore can't reuse doctor's file-only fixture: launch the binary once
  (`replay <missing-session>` → it migrates the schema on boot, exits 1 not-found), INSERT a session + message rows
  via plain JDBC into that now-migrated SQLite, then launch `replay <session>` again to read them back — all three
  sharing one `forvum.home`. Test-side Flyway is NOT an option (`flyway-core` 12 ships no SQLite support module on
  the app classpath); letting the binary own schema creation sidesteps it. These `@QuarkusMainIntegrationTest` ITs
  run native-only (`skipITs=true` in JVM); the `forvum.home` propagation they rely on is the proven native-leg
  mechanism (`DoctorNativeIT`/`OllamaNativeTurnIT`), so the same test errors under a JVM `-DskipITs=false` run. [P2-8]

- **Prefix-preserving compaction needs an id-stable summary, so the summary RECLAIMS the oldest dropped id.**
  The cached prefix is defined id-based (`id <= cached_prefix_end_index`, never mutated), and replay reads
  `order by id` — so a summary inserted with a fresh IDENTITY id (always the highest) would sort LAST, not
  at the prefix tail, and using that high id as the new prefix boundary would freeze EVERYTHING below it.
  Fix: delete the dropped run, then native-INSERT the summary at the oldest dropped message's id (IDENTITY
  forbids a manual id on `persist()`, so a controlled `em.createNativeQuery("INSERT ... (id, ...)")` is the
  seam), and advance `cached_prefix_end_index` to it. The summary then sits numerically+chronologically
  right after the old prefix and before every retained message, the existing `order by id` path is
  untouched, and the prefix grows monotonically. The summarizer is an injectable `Summarizer` SPI
  (default reuses the §1.4 small-and-fast model via `LlmSelector.resolve`, NOT a bespoke endpoint); tests
  bind a deterministic `@Alternative @Priority(1)` stub so no live model is hit. Orphan stripping keys off
  a new `messages.block_type` core enum (`BlockType`, registered in `CoreReflectionRegistration`): strip
  `turn_reasoning`/`turn_artifact` + stale `tool_execution` older than the oldest retained user message,
  retain connected `tool_execution`. CAPR is archived (`capr_events.is_archived`), never deleted. **Adding
  a NOT-NULL column to an existing entity breaks every hand-built fixture** — `SchemaSmokeIT` (2 sites) +
  `SessionReplayerTest` (1 site) set `MessageEntity` fields directly and needed `blockType`; the V2 `DEFAULT
  'turn_message'` only covers raw SQL inserts that omit the column (the app native replay IT). Migration
  is **V2** (the brief said V3, but only V1 existed — keep the chain contiguous). Seed-then-compact-then-read
  ITs use `QuarkusTransaction.requiringNew()` (intra-class `this.seed()` bypasses `@Transactional`
  interception). **The blocking summarizer LLM call must run OUTSIDE any DB transaction** (CLAUDE §14
  [M7]): `compact()` is NOT `@Transactional` — a short read tx plans the pass (partition the region,
  capture id/content primitives so the detached entities are never reused), the model is called with no
  Agroal/SQLite connection held, then a short write tx applies the mutations (bulk delete-by-id +
  native-insert + advance prefix). **Track the retain boundary on EVERY retained `TURN_MESSAGE`
  regardless of role, not just USER rows** — else the newest turn, typically the assistant reply (since
  compaction runs before the next user message is persisted), is left at `Long.MAX_VALUE` and summarized
  away when it alone exceeds `retainTokens`; the user-then-assistant persist order keeps the common-case
  boundary on the USER row unchanged. [P2-COMPACT]

- **A reference Layer-3 plugin against a third-party backend is just the Telegram blocking-REST recipe
  reused.** P2-5's `forvum-provider-memory-qdrant` copied `forvum-channel-telegram` wholesale: same pom
  (`forvum-sdk` + `quarkus-rest-client-jackson` + `quarkus-arc` + `quarkus-junit`, no `build` goal,
  copied enforcer allowlist), same `@RegisterRestClient(configKey=...)` + per-invocation `@Url` (the
  backend URL is operator config, not a compile constant), same `META-INF/{beans.xml, forvum/plugin.json,
  microprofile-config.properties}` (rest-client defaults MUST ship in microprofile-config, ordinal 100,
  not application.properties — a dependency's application.properties is inert in the assembled binary),
  same on-demand file config reader mirroring `ForvumHome.resolve` (so it stays engine-independent and is
  INERT/no-op with no `~/.forvum/`). The provider type in `plugin.json` is `"memory"`. Keep retrieval
  logic PURE and Quarkus-free (`QdrantRetrieval` = request-build + response-map static fns) so most tests
  are plain unit tests with a hand-written `FakeQdrantApi` double; one `@QuarkusTest *IT` proves CDI +
  `@RestClient` wiring (pin `forvum.home` in test `application.properties` so the inert-no-config assertion
  is hermetic regardless of the dev's real `~/.forvum/`). Adding a second rest-client to `forvum-app` does
  NOT reintroduce the Gemini/Ollama multi-factory conflict (that was the langchain4j HTTP client, fixed by
  `HttpClientFactorySelector`; plain JAX-RS rest-clients coexist). [P2-5]

- **A normalized-score `[0,1]` contract must reject NaN explicitly.** `MemoryPolicy.minScore`/`MemoryHit.score`
  validate `in [0,1]`, but `Double.isNaN(x)` makes both `x<0` and `x>1` false, so NaN slips a naive
  range check — the property test caught it. Guard with `Double.isNaN(x) || x<0 || x>1`. When mapping an
  external score (Qdrant cosine ∈ [-1,1]) into the contract, clamp (and NaN→0), don't assume the backend
  pre-normalizes. [P2-5]

- **The SPI method a plugin implements lands in the consumer's PR, but a reference plugin IS its own
  consumer.** Unlike M7's `resolve` (added on the SDK in M7 because `LlmSelector` consumed it there),
  P2-5 added `MemoryProvider.retrieve` AND its first implementor in the same PR — the engine does not yet
  call it (host wiring of retrieval into the turn is a later item), so the method + the reference impl
  ship together and the SDK enricher JavaDoc documents the host contract the engine will honor. [P2-5]

- **Risk #2 (`sqlite-vec`) RESOLVED = LINEAR scan, NOT `vec0` — the C extension has no Maven artifact AND
  would breach the native mandate.** P3-2 (#50) `forvum memory query/search/reindex`: `sqlite-vec` (the
  `vec0` virtual table) has NO published Maven coordinate (its Java distribution is a loadable
  `.dylib`/`.so` from GitHub/npm, so it can't be managed in `forvum-bom`), and using it would `load_extension`
  a SECOND runtime native C library into SQLite — a new native surface §5 forbids (SQLite JNI is the only
  allowed pin). Ship the pure-Java linear cosine scan over the stored `float32` BLOBs: zero native surface,
  benchmarked ~9 ms/query @10k and ~93 ms/query @100k (768-dim, top-K 10), and the issue sanctions it
  ("defer vec0 if linear is acceptable at 100k"). NO Flyway migration — `semantic_memory.embedding` BLOB
  already exists (V1/V5), self-describing (dim = `bytes.length/4` little-endian float32), so SchemaSmokeIT
  is untouched. The embedding SPI is the M7/M13 prelude-in-consumer pattern: `ModelProvider.resolveEmbedding(
  ModelRef)→EmbeddingModel` lands on `forvum-sdk` defaulted-to-throw (SDK already deps langchain4j-core, no
  new dep), implemented for Ollama (`OllamaEmbeddingModel` + the same `JdkHttpClientBuilder` empty-native-
  ServiceLoader pin as the chat model). Write-time embedding is deliberately NOT automatic (it would put a
  blocking model call on the turn's critical path) — an explicit `forvum memory reindex` pass populates the
  BLOBs. `memory` is NOT a `CommandMode` one-shot (it reads/writes the DB → full Flyway/Panache boot, like
  `ask`/`replay`). TRAPS the IT caught: (1) **xerial SQLite rejects `Connection.setReadOnly` on a pooled
  Agroal connection** ("Cannot change read-only flag after establishing a connection") — the `SqlGuard`
  (single-SELECT-only) is the authoritative read-only gate; `executeQuery` on a guarded SELECT cannot
  mutate. (2) **A reindex's `requiringNew()` write commits in its own EM, but a subsequent Hibernate/Panache
  read on the ambient request EM returns the rows STALE (`embedding == null`) from the L1 cache** — observed
  live as search finding zero embedded rows right after a successful reindex (raw JDBC saw the BLOBs). Read
  the search/plan rows via raw JDBC (the same `AgroalDataSource` path `query()` uses), L1-cache-immune; the
  write stays Panache. (3) **The store ops must live in a SEPARATE `@ApplicationScoped` bean** so the
  reindex write crosses the CDI proxy and `@Transactional`/`@ActivateRequestContext` fire — a self-
  invocation bypasses them (the [P2-15] trap), silently dropping the write. The deterministic native IT is
  the `replay` two-launch JDBC-seed dance (untagged, default native leg, proves the SQLite/BLOB stack);
  the live reindex+search against a real Ollama embedding model (`all-minilm`) is a `@Tag("live")` native
  IT the `native-turn` CI job runs (pull the embedding model alongside the chat model). **NATIVE-ONLY TRAP
  that ONLY the live native IT caught (Risk #5, mirroring [M20]):** the native binary's `forvum memory
  reindex` died with `Reindex failed: {"error":"model '' not found"}` — Ollama got `{"model":""}`. The
  `quarkus-langchain4j-ollama` extension's `nativeSupport` build step registers reflection for ONLY the CHAT
  DTOs (`OllamaChatRequest`/`OllamaChatResponse`) — because Forvum builds the embedding model
  PROGRAMMATICALLY (`OllamaEmbeddingModel.builder()`), the langchain4j Ollama `EmbeddingRequest`/`EmbeddingResponse`
  (package-private, `@JsonInclude`/`@JsonNaming` Jackson DTOs) are NEVER registered, so in native Jackson
  serializes an empty request (the `model` field is dropped) → `model ''`. The chat path works ONLY because
  the extension registered ITS request DTOs. Fix = a `forvum-provider-ollama` reflection holder
  (`OllamaEmbeddingReflectionConfig`, the `GraphNativeSerializationConfig` precedent) with the REAL Quarkus
  `@RegisterForReflection(classNames={…EmbeddingRequest, …EmbeddingResponse})` — `classNames` not `targets`
  (the DTOs are package-private, unreferenceable via `.class`), `serialization=false` (JSON via Jackson, not
  `ObjectOutputStream`), `methods`/`fields` default `true` (Jackson needs both halves). The provider module
  already deps `quarkus-arc` and its enforcer bans only `ai.forvum:*`, so the real annotation is allowed; the
  JVM tests can't catch this (no native reflection) — the live native IT is the sole gate. [P3-2/Risk#5]

- **Owner-only runtime-state permissions are the `InitCommand` recipe DUPLICATED into the engine (module
  boundary), enforced repair-and-warn on every boot — and the durable guarantee is the `0700` DIRECTORY,
  not the per-file `0600`.** #173 closed DR-6c [6c-DP-13]: without it a no-`init` first boot left the SQLite
  store (WAL/SHM + ledger/approval/CAPR/memory rows) at the process umask (typically group/world-readable).
  `forvum-engine`'s `StateDirInitializer` cannot depend on `forvum-app`'s `InitCommand` (Layer-2 ⊄ Layer-4),
  so it carries its OWN copy of the `POSIX`/`DIR_PERMS(0700)`/`FILE_PERMS(0600)` recipe — the
  copy-the-recipe convention already set by `InitCommand`↔`SkillInstaller` (which each duplicate this exact
  recipe rather than share a helper; the `WorkspaceRoot`-per-module rule is the cross-module version), NOT a
  new shared helper (a 2-use engine helper is a judgment call left to a future DRY pass, not this PR — §3
  surgical, don't refactor the unrelated `SkillInstaller`). Two seams: `ensureStateDir` makes `state/`
  `0700` BEFORE Flyway (owner-only perms carry no group/other bits, so the mode is umask-independent BY
  CONSTRUCTION — no umask syscall, which Java can't portably do anyway), and `PersistenceBootstrap.onStart`
  calls `hardenStateFiles` AFTER `flyway.migrate()` to tighten the just-created DB + sidecars to `0600`. The
  `0700` DIRECTORY is load-bearing: traversal permission gates every file inside — including the WAL/SHM
  SQLite RE-creates after the one-time boot pass — so a per-file `0600` sweep cannot durably cover files
  born later and must not be sold as if it does (the IT asserts `dir==0700` + `db==0600`; the sidecars are
  directory-protected, their individual mode unit-tested via named `-wal`/`-shm` fixtures). Fail policy is
  **repair-and-warn** (maintainer-ratified option A): tighten a pre-existing loose dir/file best-effort, and
  on a chmod failure (not owner / read-only FS / K8s fsGroup volume) WARN and continue — a hard-block would
  violate the M4 graceful-boot contract, break the CI native smoke (runs with no `~/.forvum/`), and break an
  intentionally group-shared PVC (the acceptance's "preserve K8s PV behavior"). The one hard-reject is a
  **symlinked `state/`** (`Files.isSymbolicLink` → return false = persistence unavailable): following it is a
  path-substitution write-redirect; the file-walk (`Files.walkFileTree`, no `FOLLOW_LINKS`) likewise skips
  symlink entries so it never chmods THROUGH a link to a target outside `state/`. Permission-failure warnings
  omit the absolute state path ([6c-DP-14]); non-POSIX (Windows) creates the dir + warns once that owner-only
  cannot be enforced. TDD traps worth generalizing: (1) a POSIX-conditional NO-OP branch (non-POSIX) cannot
  be RED-driven on a POSIX host — its correct behavior (set no perms) is indistinguishable from unimplemented
  — so it is a coverage guard, not a driver; the real drivers are the exact-mode assertions. (2) the
  `onStart` WIRING (the `hardenStateFiles` call, which is what makes the DB `0600`) is observable only with a
  REAL Flyway boot — prove it by commenting the call out and watching `StatePermissionsIT` flip the db
  assertion from `0600` to the umask `0644`, then restore (the pure `RecordingFlyway` bootstrap test has no
  db file to harden, so it cannot gate the wiring). (3) inject a `boolean posix` overload so both branches
  are deterministically reachable in CI. The native proof reuses the `SessionReplayNativeIT`
  `replay <missing-session>` boot-migrates dance (`replay` is not a `CommandMode` one-shot → it boots
  persistence) to stat the binary-created `state/`+`forvum.sqlite` in the default native leg. [#173]

