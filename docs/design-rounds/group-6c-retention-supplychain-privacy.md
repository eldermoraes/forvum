# DR-6c — Group 6c: audit retention, supply chain, privacy

**Status:** DELIBERATED — pending maintainer sign-off.
**Issue:** #61 · **Labels:** `design-round`, `security` · **Milestone:** Design Rounds.
**Scope of authority:** the per-ledger retention policy for the SQLite operational store (§4.2), the
privacy inventory of every surface where data leaves the machine, the supply-chain posture for native
build inputs and release artifacts, and the secrets-at-rest posture under `$FORVUM_HOME`.
**Materializes:** `docs/ULTRAPLAN.md` §9.4 (the subsection §9.1 already carves out to "DR-6c (#61,
§9.4)"; ordered after DR-6b's §9.3).
**Confirms posture for:** P2-OUTPUTGUARD #48 (default-on secrets redaction at egress), P3-1 #49
(SHA256SUMS on release artifacts), P2-15 #40 (env-var-gated OTLP, default-off), P2-10 #35 / P2-COPILOT
#42 (the credentials JSON at-rest surface), P3-5 #53 (identity-scoped purge), P3-2 / P2-5 #50/#30
(memory/embedding privacy class), X1 #67-family (banned-import greps).

> This round follows the DR-6a lesson: it **confirms what is built and names what is deferred** — it
> invents no new runtime machinery. Decisions that follow directly from the wave directive are marked
> **Ratified (wave directive 2026-06-09)**; points this round settles fresh are marked **Settled —
> flagged for maintainer review** so they can be amended surgically. Boundaries owned elsewhere are
> referenced, not redefined: DR-6b (§9.3) owns plugin/MCP trust; DR-6a (§9.1/§9.2) owns the threat
> model and the `OutputFilter` contract; P2-COMPACT owns the compaction mechanics.

---

## 1. Context

### 1.1 The ledger census (from the Flyway migrations, the only schema authority)

`forvum-engine/src/main/resources/db/migration/` holds three forward-only migrations. Eight tables
exist on `main`:

| Table | Born | Role | Mutation paths today |
|---|---|---|---|
| `sessions` | V1 | one row per channel conversation; carries `identity_id` | insert/update (`last_seen_at`, V3 `cached_prefix_end_index`); **no production delete caller** |
| `messages` | V1 | append-only chat history; V3 adds `block_type` | insert; **deleted only by compaction** (`SessionCompactor.deleteByIds`); `ON DELETE CASCADE` from `sessions` |
| `episodic_memory` | V1 | per-agent observation/decision/reflection log | insert only |
| `semantic_memory` | V1 | long-term facts + `embedding` BLOB; `UNIQUE(agent_id, key)` | upsert by `(agent_id, key)` |
| `tool_invocations` | V1 | every tool call incl. `denied` audit rows | insert only |
| `provider_calls` | V1 | every LLM call (tokens, cost, fallback, error) | insert only |
| `capr_events` | V1 | per-turn judge verdicts; V3 adds `is_archived` | insert + archive flag; **never deleted** (`SessionCompactor` Javadoc: "CAPR is archived, never deleted") |
| `tasks` | V2 | unified background-task ledger (cron / sub_agent / background) | insert + status updates |

P2-COMPACT (§7.2 item 20) already established the two retention precedents this round generalizes:
**messages are reduced only by compaction** (summary reclaims the oldest dropped id; prefix-preserving)
and **CAPR is archive-only**. V2's own header sets the operator-surface precedent: *"Operators query it
via direct SQL (no query DSL in v0.5)"*.

### 1.2 Wave interactions

- **#53 (multi-user, this wave)** adds per-identity rows; `sessions.identity_id` (V1) is already the
  tenant key. Retention decisions must not assume a single owner.
- **#50 / #30 (vector memory)**: `semantic_memory.embedding` and the P2-5 Qdrant reference backend put
  content-derived vectors in scope for the privacy inventory.
- **#48 (OutputGuard, this wave)** ships the §9.2 pre-channel-emit filter with default-on secrets
  redaction — the egress gate the privacy inventory leans on.
- **#40 (telemetry export, this wave)** is the only telemetry egress, env-var-gated, default-off.
- **#49 (release pipeline, this wave)** publishes SHA256SUMS alongside release artifacts.
- **X1 (this wave)** lands the §6.3 banned-import CI greps (verified absent from
  `.github/workflows/ci.yml` today; the enforcer `bannedDependencies` half has been live since M1 in
  `forvum-core`/`forvum-sdk`/`forvum-engine` poms).

### 1.3 OpenClaw parity (history retention/purge)

OpenClaw (`docs/concepts/session.md`) stores sessions as JSONL transcripts and ships **session
maintenance defaulting to `warn` mode** — it *reports* what would be cleaned and deletes nothing unless
the operator opts into `mode: "enforce"` (`pruneAfter: "30d"`, `maxEntries: 500`), with an operator CLI
(`openclaw sessions cleanup --dry-run`). Parity reading: **the upstream default is also
no-silent-deletion**; an opt-in operator cleanup surface is the parity feature, and it is named here as
a deferred follow-up, not shipped in v0.5.

---

## 2. Retention policy per ledger

The policy is a three-class taxonomy over the §1.1 census. The unifying rule: **the engine never
deletes operational data on its own initiative; the only in-engine mutation of history is compaction,
which is a documented, prefix-preserving transform — not a purge.**

| Class | Tables | Policy |
|---|---|---|
| A — conversational window | `messages` (+ `sessions` registry rows) | compaction owns reduction; never silent deletion |
| B — audit/operational ledgers | `provider_calls`, `tool_invocations`, `tasks` | append-only, unbounded by default; operator purge surface deferred |
| C — judgment ledger | `capr_events` | archive-only (`is_archived`); never deleted |
| D — memory tiers | `episodic_memory`, `semantic_memory` | owner-curated, unbounded; purge rides the same deferred surface |

- **[DP-1] Operational ledgers (`provider_calls`, `tool_invocations`, `tasks`) are unbounded by
  default.** No TTL, no row cap, no background reaper. Rationale: these are the audit/repudiation
  surface §9.1.b leans on (`status='denied'` rows, the `turn_id`-correlated ledger) and the CAPR/cost
  evidence base (§3.6, §4.3.5.2); an automatic reaper would silently destroy the non-repudiation
  property a local-first audit ledger exists for, and SQLite row volume at personal-assistant scale is
  not a forcing function. Matches the upstream default (§1.3: OpenClaw `warn` deletes nothing).
  **Ratified (wave directive 2026-06-09).**
- **[DP-2] The operator purge surface is a named, deferred follow-up; raw SQL is the v0.5 surface.**
  A `forvum sessions purge`-style command (session-granular, dry-run-first, mirroring `openclaw
  sessions cleanup`) is the intended UX, but it is **not scheduled in v0.5**: the V2 precedent
  ("Operators query it via direct SQL — no query DSL in v0.5", §7.2 item 21) makes direct SQL against
  `$FORVUM_HOME/state/forvum.sqlite` the v0.5 operator surface for both inspection and deletion. P3-2
  (#50) is the v1.0+ first-class query surface. **Ratified (wave directive 2026-06-09).**
- **[DP-3] `messages`: compaction owns reduction; deletion is session-granular and operator-initiated
  only.** The only in-engine path that removes a `messages` row is the P2-COMPACT pass
  (`SessionCompactor`: delete the dropped run, native-insert the id-reclaiming summary, advance the
  prefix) — a window transform with the content folded into the summary, not a purge. The schema's
  `ON DELETE CASCADE` (`messages.session_id REFERENCES sessions(id)`) is the **reserved mechanism**
  for the deferred [DP-2] purge surface: deleting a session row removes its transcript atomically.
  No production code deletes a session today (verified — no delete caller on `main`). The cascade
  reservation is this round's framing. **Ratified (wave directive 2026-06-09)** for
  compaction-owns-reduction / never-silent-deletion; the cascade-as-purge-mechanism framing is
  **Settled — flagged for maintainer review.**
- **[DP-4] `capr_events` is archive-only — ratified as built.** V3 added `is_archived`; compaction
  marks verdicts of compacted-out turns and never deletes a row (`SessionCompactor` write pass,
  "Archive (never delete)"). The judgment ledger is the long-horizon evidence base for P3-4
  CAPR-driven routing; archiving preserves it while keeping live queries cheap
  (`idx_capr_archived`). **Ratified (wave directive 2026-06-09).**
- **[DP-5] Memory tiers (`episodic_memory`, `semantic_memory`) follow Class D: owner-curated,
  unbounded, purge rides the [DP-2] surface.** `semantic_memory` self-bounds per key via
  `UNIQUE(agent_id, key)` upserts; `episodic_memory` grows append-only like Class B. No automatic
  forgetting in v0.5 — DR-5's `MemoryPolicy` governs *read-back* (Select), not deletion, and the
  pre-memory-write `OutputFilter` hook (§9.2, [DP-4] there) governs what gets *in*. A future
  forgetting/decay policy would be a new design round, not a retention default.
  **Settled — flagged for maintainer review.**
- **[DP-6] Any purge surface must be identity-scoped once #53 lands.** With multi-user on, ledger rows
  are per-identity tenant data (`sessions.identity_id` is the key; operational tables join through
  `session_id`). An untargeted `DELETE` is a cross-tenant hazard; the deferred [DP-2] command must
  take an identity/session scope, and the v0.5 raw-SQL guidance documents the same join. This is a
  constraint on the *future* surface, not new machinery. **Settled — flagged for maintainer review.**

---

## 3. Privacy — what leaves the machine

Forvum is local-first: the default state surface (SQLite store, config files, embeddings BLOB) never
leaves `$FORVUM_HOME`. The complete egress inventory:

| # | Surface | Carries | Gate | Default |
|---|---|---|---|---|
| 1 | Model provider calls | the prompt window: messages, retrieved memory, tool results | operator configures the provider + key (M9–M12); local Ollama is the scaffolded default (`init` pins `ollama:gemma4:31b-cloud`) | on (the product's purpose) |
| 2 | Channel egress | assistant replies to the channel platform (Telegram Bot API, Discord gateway/REST) | `OutputGuard` pre-channel-emit (§9.2), **default-on secrets redaction** (#48, this wave) | on when a channel is configured |
| 3 | OTLP telemetry | spans/metrics | exports **only when `OTEL_EXPORTER_OTLP_ENDPOINT` is set** (#40, this wave) | **off** (unset env var ⇒ zero telemetry egress; today no spans exist at all — X6) |
| 4 | Operator-enabled extensions | memory content → configured Qdrant endpoint (`memory/qdrant.json`, P2-5 #30, inert without the file); tool args → configured MCP servers (off by default v0.1, Risk #9; trust contract = DR-6b §9.3); Maven coordinates → Maven Central on `forvum plugin install` (P2-6 — coordinates only, no user data) | each requires an explicit operator config/action | off |

- **[DP-7] The sanctioned default egress set is exactly rows 1–3; row 4 is operator-opt-in.** No other
  code path performs outbound IO: there is no update-checker, no crash reporter, no analytics, and the
  CI-built binary phones nothing home. A new egress surface added by any future package must be
  appended to this inventory in the same PR (a §9.4 doc obligation, mirroring the §2.7 pillar-mapping
  rule). Rows 1–3 are **Ratified (wave directive 2026-06-09)**; the row-4 enumeration and the
  keep-the-inventory-current obligation are **Settled — flagged for maintainer review.**
- **[DP-8] No ledger-write redaction hook (a named non-goal).** Ledger columns can carry sensitive
  content (`messages.content`, `tool_invocations.arguments`, `provider_calls.error`); their protection
  boundary is **at rest** (the §5 owner-only home + the fact that the store never leaves the machine)
  plus the egress gates of [DP-7] — not a write-time filter. §9.2's three hook layers
  (pre-channel-emit, pre-memory-write, pre-tool-call) deliberately do not include ledger writes:
  filtering the audit trail would degrade the very evidence §9.1.b's repudiation defense depends on.
  **Settled — flagged for maintainer review.**

---

## 4. Supply chain

- **[DP-9] The native binary is locked at build time — confirmed as built + X1 (this wave).** The
  closed-world posture is already structural: plugins load only from the compile classpath (§6.2; the
  `~/.forvum/plugins/` drop-in is JVM-fast-jar-only by design), per-module `maven-enforcer-plugin`
  `bannedDependencies` allowlists police the layer graph (live since M1 — verified in
  `forvum-core`/`forvum-sdk`/`forvum-engine` poms), `forvum-bom` is the single version bump point
  (§3.7) excluding vetoed dependencies (`sun.misc.Unsafe`, CGLib, runtime Javassist — §6.3), and the
  exact Mandrel patch is pinned in CI. The missing enforcement half — the §6.3 **banned-import CI
  greps** (`sun.misc.Unsafe`, `net.sf.cglib`, `javassist.util.proxy`) — is delivered by X1 in this
  wave (verified not yet in `.github/workflows/ci.yml`; the static concurrency greps,
  `.github/concurrency-guardrails.sh`, already run). **Ratified (wave directive 2026-06-09).**
- **[DP-10] JVM drop-in plugins are an operator-trust boundary — DR-6b's contract, cross-referenced
  not duplicated.** A jar in `~/.forvum/plugins/` (hand-placed or via `forvum plugin install`) runs
  with full process authority in the fast-jar; what a plugin may do to prompt assembly, scopes, and
  tool registration is §9.3 (DR-6b #60). This round adds only the supply-chain corollary: the install
  command resolves against the operator's `~/.m2` + Maven Central over standard Maven Resolver
  transports — choosing the coordinate is the trust decision, exactly as §6.2 documents for the
  rebuild-your-own-native path. **Ratified (wave directive 2026-06-09).**
- **[DP-11] Release artifacts ship with SHA256SUMS (#49, this wave); SBOM/provenance is a named
  deferral.** The §6.4 release set (platform native binaries, JVM jar, OCI images) gains a
  `SHA256SUMS` file published alongside the artifacts on GitHub Releases; the P3-1 `curl | sh`
  installer verifies its download against it before installing. Signed checksums / SLSA-style
  provenance attestation and a published SBOM are **deferred follow-ups** (named here, owned by the
  release-pipeline work, not scheduled in this wave). The SHA256SUMS publication is **Ratified (wave
  directive 2026-06-09)**; installer verification + the SBOM/provenance deferral are **Settled —
  flagged for maintainer review.**

---

## 5. Secrets at rest

### 5.1 Inventory — where secrets live today (verified on `main`)

| Location | Secret | As-built handling |
|---|---|---|
| `channels/telegram.json` | `botToken` | file-borne (`TelegramChannelConfig` reads `botToken`); token rides the request URL path, redacted from logs |
| `channels/discord.json` | `botToken` | file-borne (`DiscordChannelConfig`); token rides the IDENTIFY frame + `Authorization: Bot` header, redacted from logs |
| MP Config (env vars / `-D` / `application.properties`) | provider API keys (`quarkus.langchain4j.<provider>.api-key`) | not stored under `~/.forvum` today (e.g. `AnthropicModelProvider` `@ConfigProperty`; the Gemini `unset` placeholder) |
| `$FORVUM_HOME` (whole tree) | everything above + future credentials | `forvum init` creates it owner-only — `0700` dirs / `0600` files, POSIX-guarded (`InitCommand` `DIR_PERMS`/`FILE_PERMS`) |

- **[DP-12] The at-rest posture is the owner-only `$FORVUM_HOME` tree, and the #35/#42 credentials
  JSON joins it at `0600`.** The wave pre-ratified a **credentials file under `$FORVUM_HOME`**
  (owner-only, created `0600` via the same `InitCommand` permission constants) as the storage surface
  the P2-10 provider-onboarding wizard (#35) and the Copilot OAuth/device-code flow (#42) write —
  provider keys and OAuth tokens move from ad-hoc env vars into one inventoried, permission-controlled
  file. This **amends the §4.1 keychain-only sentence as the v0.5 posture**: platform-keychain storage
  (macOS Keychain / Secret Service / Windows Credential Manager, the §4.1 direction and the P2-10
  `[NATIVE]` note) remains the named hardening follow-up, not the v0.5 gate. Channel `botToken`s
  remain file-borne as built. **Ratified (wave directive 2026-06-09).**
- **[DP-13] Named gap: a no-`init` first boot creates `state/` with umask defaults.** The `0700/0600`
  posture is `init`-owned; `StateDirInitializer` uses plain `Files.createDirectories(stateDir)`, so a
  user who skips `forvum init` gets a SQLite store with the platform umask (typically group/world
  readable). Hardening follow-up (named, not scheduled): align `StateDirInitializer` (and any other
  engine-side directory creation) with the `InitCommand` POSIX permission recipe.
  **Settled — flagged for maintainer review.**
- **[DP-14] No-secret-in-logs is confirmed as built and is a standing review obligation.** The
  enforced rules (CLAUDE.md §14 [M17], [P2-CH/discord]): never log a secret-bearing URL or header raw;
  redact the secret-bearing segment (`TelegramChannel.redact` strips `/bot<TOKEN>`;
  `DiscordChannel.redact` masks `Bot <token>`, applied across the gateway/REST/processor classes) and
  log message-only (no throwable/stack) on secret-bearing exceptions. Every new secret-bearing surface
  (the #35/#42 credentials flows included) must ship its redaction seam + a non-live encode/log test in
  the same PR. **Ratified (wave directive 2026-06-09).**

---

## 6. Decision points for sign-off

| # | Decision | Position | Marking |
|---|---|---|---|
| `[DP-1]` | Operational ledgers (`provider_calls`, `tool_invocations`, `tasks`) retention | unbounded by default; no TTL/reaper | Ratified (wave directive 2026-06-09) |
| `[DP-2]` | Operator purge surface | `forvum sessions purge`-style command = deferred follow-up; raw SQL = v0.5 surface (V2/§7.2-21 precedent) | Ratified (wave directive 2026-06-09) |
| `[DP-3]` | `messages` retention | compaction owns reduction; never silent deletion; session cascade reserved for the purge surface | Ratified; cascade framing flagged |
| `[DP-4]` | `capr_events` | archive-only (`is_archived`), never deleted — as built (P2-COMPACT) | Ratified (wave directive 2026-06-09) |
| `[DP-5]` | Memory tiers retention | owner-curated, unbounded; no automatic forgetting in v0.5 | Settled — flagged for maintainer review |
| `[DP-6]` | Multi-user (#53) interaction | any purge surface must be identity-scoped | Settled — flagged for maintainer review |
| `[DP-7]` | Privacy egress inventory | defaults = provider calls + #48-gated channel egress + env-var-gated OTLP (#40, default-off); extension egress is operator-opt-in | Rows 1–3 ratified; row 4 + currency obligation flagged |
| `[DP-8]` | Ledger-write redaction | non-goal — at-rest + egress gates are the boundary; the audit trail is not filtered | Settled — flagged for maintainer review |
| `[DP-9]` | Native supply chain | locked at build: closed-world classpath + enforcer + BOM vetoes + X1 banned-import greps (this wave) | Ratified (wave directive 2026-06-09) |
| `[DP-10]` | JVM drop-in plugins | operator-trust boundary; contract owned by DR-6b §9.3 (cross-ref only) | Ratified (wave directive 2026-06-09) |
| `[DP-11]` | Release integrity | SHA256SUMS published with release artifacts (#49); SBOM/provenance deferred | SHA256SUMS ratified; verification/SBOM deferral flagged |
| `[DP-12]` | Secrets at rest | owner-only `0700/0600` `$FORVUM_HOME`; #35/#42 credentials JSON at `0600`; keychain = named follow-up | Ratified (wave directive 2026-06-09) |
| `[DP-13]` | `state/` umask gap | no-`init` boot creates `state/` world-readable — named hardening follow-up | Settled — flagged for maintainer review |
| `[DP-14]` | No-secret-in-logs | confirmed as built (Telegram/Discord redaction); standing obligation for every new secret-bearing surface | Ratified (wave directive 2026-06-09) |

---

## 7. Open issues / named deferrals

- **`forvum sessions purge`** (session-granular, dry-run-first, identity-scoped per [DP-6]) — the
  [DP-2] operator surface; deferred, unscheduled; raw SQL is the v0.5 answer.
- **Platform-keychain credential storage** — the §4.1 direction; deferred behind the [DP-12]
  credentials-file posture (#35/#42 land the file; keychain is their named follow-up).
- **`StateDirInitializer` permission alignment** — the [DP-13] umask gap; a small hardening change,
  owned by whichever package next touches the persistence bootstrap.
- **SBOM / signed provenance** — the [DP-11] deferral on the #49 release pipeline.
- **Memory forgetting/decay policy** — explicitly out of scope ([DP-5]); a future design round if
  wanted.
- **DR-6b ordering** — §9.3 (plugin/MCP trust) precedes §9.4 in the document; the two rounds are
  parallel in content and share only the [DP-10] cross-reference.

---

## 8. ULTRAPLAN sync

- **§9.4 (new):** a compact subsection — the retention classes ([DP-1..6]), the egress inventory
  ([DP-7..8]), the supply-chain posture ([DP-9..11]), and the secrets-at-rest posture ([DP-12..14]) —
  inserted at the end of §9, after DR-6b's §9.3, resolving §9.1's "carved out to … DR-6c (#61, §9.4)"
  forward-reference. Rationale stays in this round file; ULTRAPLAN carries the settled posture only.
- No §4.2 schema change: retention is policy over the existing migrations (V1/V2/V3); this round adds
  zero tables, zero columns, zero code.
