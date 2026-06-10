# DR-6b — Group 6b: plugin trust + MCP server trust

**Status:** DELIBERATED — wave-directive decisions pre-ratified (2026-06-09); newly settled points
flagged for maintainer review.
**Issue:** #60 · **Labels:** `design`, `security` · **Milestone:** Design & Contracts.
**Scope of authority:** the trust boundary for the four extension surfaces — first-party bundled
modules, `~/.forvum/plugins/` drop-in JARs (JVM fast-jar SPI), configured remote MCP servers
(`mcp-servers/*.json` → `forvum-tools-mcp-bridge`), and installed skill files (`skills/*.md`) —
capability declaration vs. enforcement, sandboxing posture, what a plugin can do to prompt assembly /
`PermissionScope`, the plugin-artifact supply chain for `forvum plugin install`, and revocation.
**Materializes:** `docs/ULTRAPLAN.md` §9.3 (the subsection §9.1 already reserves for DR-6b) + a
one-line §9.1 intro touch-up.
**Unblocks:** P2-13 #38 (MCP server registry), P2-7 #32 (skill install from URL), TEST-SEC #65
additions; **confirms as-built:** P2-6 #31 (Maven plugin marketplace, merged).

> This is the round write-up for sign-off. Decisions that follow directly from the maintainer's wave
> directive (2026-06-09, AskUserQuestion) are marked **Ratified (wave directive)**; points this round
> settles fresh are marked **Settled — flagged for maintainer review** so they can be amended
> surgically. Boundaries this round does **not** own are referenced, not redefined: DR-6a owns §9.1
> (threat model) and §9.2 (`OutputFilter`); DR-6c (#61, §9.4) owns audit retention, the *build-input*
> supply chain, and privacy. In ULTRAPLAN §9.3 the decision tags are namespaced `[6b-DP-n]` to avoid
> colliding with DR-6a's `[DP-n]` set inside the same §9.

---

## 1. Context

§9.1 (DR-6a, merged) deliberately carved plugin trust and MCP-server trust out of the runtime threat
model: *"surfaces outside the runtime (plugin trust, MCP-server trust, audit retention, supply chain,
privacy) are deliberately carved out to DR-6b (#60, §9.3) and DR-6c (#61, §9.4)"*. This round closes
the DR-6b half. Per the DR-6a lesson (CLAUDE.md §14), a security round **confirms what is built and
names what is deferred** — it invents no runtime machinery beyond what this wave's consumers (#38,
#32) need.

What exists on `main` (`2bacc5c`), verified in code:

- **The closed scope vocabulary.** `PermissionScope` is a Layer-0 enum, today exactly
  `{FS_READ, FS_WRITE}`, with Javadoc declaring it *"closed at compile time"*, growing only *"at
  milestone boundaries"* (`forvum-core/.../PermissionScope.java`). Every `ToolSpec` must declare a
  non-null `requiredScope` (`forvum-core/.../ToolSpec.java` canonical-constructor validation).
- **The two-gate executor.** `ToolExecutor` enforces belt membership (M13) plus the RBAC second gate
  reading `CurrentIdentity.CURRENT_EFFECTIVE_SCOPES` (P2-11 #36); both denials are audited
  `tool_invocations.status = 'denied'` (§9.1.b — referenced, not redefined here).
- **The registry seam.** `ToolRegistry` indexes every `ToolProvider`'s `tools()` at startup and
  rejects a duplicate tool name with a hard `IllegalStateException` — *"never a silent overwrite"*
  (`forvum-engine/.../tools/ToolRegistry.java`).
- **MCP config surface, parsed but unconsumed.** `ForvumHome.mcpServers()` resolves
  `$FORVUM_HOME/mcp-servers/`, `McpServerReader extends JsonDirectoryReader` reads it, and
  `"mcp-servers"` is in `ConfigWatcher.WATCHED_SUBFOLDERS` (`forvum-engine/.../config/ConfigWatcher.java`),
  so hot-reload events already fire for it. `forvum-tools-mcp-bridge` (§2.4) is **not yet a module**;
  its baseline is owned by M13 acceptance (X7) and built by #38.
- **Skills surface, half-built.** `SkillReader` reads `$FORVUM_HOME/skills/*.md` and `"skills"` is
  watched; the `SkillInvokerTool` (§4.1: *"skills ARE tools"*) is **not yet in code** (X7 → M13/M18
  acceptance; #32 installs the files it will invoke).
- **Plugin marketplace, merged (#31).** `MavenPluginResolver` resolves `groupId:artifactId:version`
  against `~/.m2/repository` + Maven Central over HTTPS
  (`DEFAULT_CENTRAL_URL = "https://repo.maven.apache.org/maven2/"`) and streams the JAR into
  `~/.forvum/plugins/`; `PluginInstallCommand` warns-and-stages under `ImageMode.NATIVE_RUN`. The
  drop-in dir is JVM-fast-jar-only **by design** (§6.2/§6.3) and is deliberately **excluded** from
  `WATCHED_SUBFOLDERS` (the `ConfigWatcher` Javadoc names `state/` and `plugins/` as excluded).
- **Owner-only home.** `forvum init` creates dirs `0700` / files `0600` via `PosixFilePermissions`
  (`forvum-app/.../InitCommand.java`); it scaffolds `agents/`, `identities/`, `channels/` — **not**
  `plugins/` or `mcp-servers/` (those are created on first use).

OpenClaw parity facts (grepped at `openclaw/`):

- Native plugins *"run **in-process** with the Gateway. They are not sandboxed. A loaded native
  plugin has the same process-level trust boundary as core code … a malicious native plugin is
  equivalent to arbitrary code execution"* (`docs/plugins/architecture.md` §"Execution model").
  `plugins.allow` *"trusts plugin ids, not source provenance"*, and a workspace plugin with the same
  id intentionally **shadows** the bundled copy.
- Bundles are *"content packs"*, safer by default — OpenClaw *"does not load arbitrary bundle runtime
  modules in-process"* (`docs/plugins/bundles.md`).
- Skills: *"Treat third-party skills as **untrusted code**. Read them before enabling."*
  (`docs/tools/skills.md`); the ClawHub threat model names malicious skill installation
  (T-PERSIST-001) and skill-update poisoning (T-PERSIST-002), and lists MCP servers as *"external
  tool providers"* (`docs/security/THREAT-MODEL-ATLAS.md`).

Parity is **semantic, not literal** (the P2-11 lesson): Forvum reproduces OpenClaw's honest
"in-process plugin = core trust" posture and its "read third-party content before enabling" stance in
the local vocabulary — with two structural differences that *strengthen* the local position: Forvum
skills are prompt templates, never executable code, and `ToolRegistry` hard-fails on a name collision
instead of allowing id-shadowing.

---

## 2. The settled contract (summary): four trust tiers

| Tier | Surface | Code or content | Trust class | What it may do | Gates |
|---|---|---|---|---|---|
| **T0** | First-party bundled module (compile classpath of `forvum-app`) | Java, in-process | Core-equivalent; reviewed in-repo | Anything its layer allows (enforcer-bounded) | Repo review + `/code-review`, Layer-3 enforcer allowlist, CI import grep, native build |
| **T1** | Operator-installed Maven plugin (`~/.forvum/plugins/` drop-in, JVM fast-jar only) | Java, in-process | Core-equivalent **once loaded** — the operator's `plugin install` act *is* the trust decision | Same as T0 at runtime (unsandboxed); contributes providers/tools via the SDK SPIs | Install act (explicit coordinates), HTTPS+checksum resolution (§6), restart-to-load; its tools still hit belt + RBAC |
| **T2** | Remote MCP server (`mcp-servers/<name>.json`, HTTP/SSE) | Out-of-process service | **Untrusted**: specs untrusted, results untrusted DATA | List tool specs; execute a tool **only** when belt + scope admit it | `mcp add` = listing grant only; belt allowlist; `PermissionScope.MCP_REMOTE` (RBAC); DR-6a data framing on results |
| **T3** | Installed skill file (`skills/<skill>.md`) | Prompt template (content, never code) | Operator-trusted **content**; standing prompt-injection surface | Expand a template into the invoking agent's turn | Front-matter input-schema validation; the invoking agent's existing belt + scopes — a skill grants **zero** additional authority |

Capability **declaration** is plugin-side (`ToolSpec.requiredScope`, `plugin.json` type); capability
**enforcement** is engine-only (`ToolRegistry` uniqueness, `ToolFilter` belt, the two `ToolExecutor`
gates, the §9.2 egress filter). No tier can mint a new `PermissionScope`: the enum is closed at the
binary's compile time, so the capability *vocabulary* itself is fixed — a T1 plugin or T2 server can
only reference scopes that already exist. `[DP-1]`

---

## 3. The mandated ruling — remote MCP tool specs breach §9.1.a

§9.1.a's trust boundary reads: tool specs are *"author-authored, contributed by first-party
`ToolProvider` plugins at build time … never user-derived, never dynamically assembled"*. A remote
MCP server's tool list is the exact negation: specs arrive **at runtime, over the network, authored
by a third party**, and `forvum-tools-mcp-bridge` (§2.4) surfaces them as native `ToolSpec`
instances. This round rules the breach explicitly rather than leaving §9.1.a silently false once #38
lands:

**Ruling.** MCP-surfaced tool specs are **UNTRUSTED specs** — the §9.1.a assumption is scoped to
T0/T1 (compile-classpath) providers only, and a remote spec passes three gates before its tool can
ever execute: `[DP-2]` — **Ratified (wave directive 2026-06-09).**

- **(a) The belt.** An MCP tool appearing in `ToolRegistry` does **not** enter any agent's belt; the
  persona's `allowedTools` must allowlist it explicitly (`ToolFilter` narrows the global registry,
  M13). Registry listing ≠ belt membership.
- **(b) `PermissionScope.MCP_REMOTE` via RBAC.** Every MCP-surfaced `ToolSpec` declares
  `requiredScope = MCP_REMOTE` (a new constant — reserved in ULTRAPLAN §4.3.4; lands with #38), so the
  P2-11 second gate applies: a role-restricted identity without `MCP_REMOTE` is denied an in-belt MCP tool, and
  the denial is audited. By the as-built role mechanics, the permissive built-in `default-user`
  (`EnumSet.allOf`) acquires `MCP_REMOTE` automatically (RBAC stays opt-in restriction), while the
  read-only built-in `cron` role does **not** include it — a cron-fired turn cannot reach a remote
  MCP tool unless the operator widens the `cron` role file deliberately. `[DP-5]`
- **(c) `forvum mcp add` is a trust grant for LISTING only.** The operator's explicit add act
  authorizes Forvum to *connect and enumerate* the server's tools into `ToolRegistry` — nothing
  more. Execution authority is still (a) + (b), per call, audited per call.

**Results are untrusted DATA.** A tool result returned by an MCP server re-enters the window framed
as data, never instructions — exactly the DR-6a §9.1.c containment posture (the same boundary that
frames `<retrieved_memory>`): an injected instruction inside an MCP result is presented as quoted
content, and any tool call it coerces still hits the two gates. No new framing mechanism is defined
here; MCP results are declared to flow through the DR-6a one. `[DP-3]` — **Ratified (wave directive).**

Two supporting decisions this round settles fresh:

- **Namespaced tool names.** The bridge surfaces remote tools as `mcp.<server>.<tool>` (the
  `<server>` segment is the `mcp-servers/<name>.json` stem). Two reasons: `ToolRegistry` requires
  global uniqueness (two servers exposing the same upstream name must not collide), and a
  conventional belt glob such as `fs.*` must never accidentally admit a remote tool. The residual
  hazard is a bare `*` belt, which admits every future tool by construction — documented as an
  operator footgun, not guarded by new machinery. `[DP-4]` — **Settled — flagged for maintainer
  review.**
- **`MCP_REMOTE` is deliberately coarse in v0.5.** One scope covers all configured servers' tools;
  granting it to a role grants the *class* of remote-MCP execution (the belt still narrows per
  agent/per tool). Per-server or per-tool scope granularity is a named deferral, re-opened only if a
  real multi-server deployment demands it. `[DP-5]` — constant **Ratified (wave directive)**;
  coarseness note **Settled — flagged**.
- **Transports: HTTP/SSE only in v0.5; stdio parsed-but-flag-off.** `mcp-servers/<name>.json` may
  declare a stdio transport and the reader parses it, but the bridge refuses to start it while the
  Risk #9 flag is off (subprocess spawning from the native binary is unproven on all three
  platforms; §8 Risk #9's decision trigger stands). A stdio entry is reported by `forvum doctor`
  as configured-but-disabled, not an error. `[DP-6]` — **Ratified (wave directive).** The `mcp add` /
  `mcp list` commands are `CommandMode` one-shots (file write/read only, like `doctor`/`plugin`);
  keep `CommandMode.isOneShotCommand` in sync with `RootCommand.subcommands` (the P2-9 lesson).

---

## 4. Skill-file trust (T3)

A skill is **operator-trusted CONTENT, never code**. `skills/<skill>.md` is a named prompt template
with front-matter declaring its **full input schema** (the #32 pre-ratified shape, matching §4.1);
the `SkillInvokerTool` surface (X7, owned by M13/M18 acceptance) expands it into the invoking
agent's turn. The trust consequences: `[DP-7]` — **Ratified (wave directive)** for the input-schema
shape; trust framing confirmed against §9.1.

- **Validation, not sandboxing.** The front-matter input schema is validated on read (a malformed
  skill is rejected with a file-naming error, the M4 reader convention) and invocation arguments are
  validated against it before expansion. Validation bounds the *interface*, not the template prose.
- **No scope escalation by skill — structural.** The expanded template is content inside the
  invoking agent's turn; any tool call it induces executes under that agent's **existing** belt and
  the caller's effective scopes. A skill carries no scope set, no belt, no identity — there is
  nothing on the skill surface that the two gates read, so escalation-by-skill has no mechanism.
- **A skill is a standing prompt-injection surface.** A malicious installed skill is exactly the
  §9.1.c threat (an instruction embedded in content), contained by the same structure: gates +
  Isolate boundary + data/instruction framing. The OpenClaw warning maps over in weakened form —
  OpenClaw skills can carry executable code ("treat as untrusted code"); Forvum skills cannot, so the
  honest local guidance is *"read a third-party skill before installing it; it becomes part of your
  agent's prompt"*. #32's installer writes the file owner-only (`0600`, the `InitCommand` recipe) and
  does nothing else — installation is file placement, hot-loaded by the watcher (`"skills"` is in
  `WATCHED_SUBFOLDERS`).
- **The skill tool's own scope.** `ToolSpec.requiredScope` is non-null, so the future
  `SkillInvokerTool` must declare one. Recommendation: a dedicated `SKILL_INVOKE` constant
  (read-only class — template expansion has no side effect), added when the tool lands, so a
  role can be denied skills wholesale without touching `FS_*`/`MCP_REMOTE`. Until that issue lands
  nothing depends on the choice. `[DP-8]` — **Settled — flagged for maintainer review.**

---

## 5. In-process plugin trust (T1) and the sandboxing posture

- **No sandbox is claimed, v0.1 through v0.5.** A drop-in JAR loads in-process via `ServiceLoader`
  (fast-jar only) and runs with the same process-level authority as core code — the OpenClaw posture,
  stated with the same honesty: **a malicious drop-in plugin is arbitrary code execution** in the
  Forvum process. The trust decision is the operator's explicit `forvum plugin install <coords>` act
  (provenance-bearing: a Maven coordinate, not an opaque file), made at install time, not enforceable
  at runtime. Forvum's containment for T1 is *structural and build-time*: the SDK seam bounds what a
  well-behaved plugin compiles against (`forvum-sdk` + `forvum-core` only, Layer-3 enforcer), the
  closed `PermissionScope` enum bounds the capability vocabulary, and `ToolRegistry`'s
  duplicate-name hard error prevents a drop-in from silently shadowing a first-party tool (stronger
  than OpenClaw's id-shadowing allowlist model). The OpenClaw guidance carries over: treat drop-in
  plugins as development-time code; the production-grade path for a curated set is rebuilding the
  native binary with the plugin as a compile dependency (§6.2). `[DP-9]` — **Settled — flagged for
  maintainer review.**
- **What a plugin can do to prompt assembly.** There is **no SPI hook into prompt assembly** — the
  window is assembled by the engine (`Agent`/`SupervisorGraph`); the extension contracts are
  `ChannelProvider`/`ModelProvider`/`ToolProvider`/`MemoryProvider` (+ the engine-implemented
  `ChannelTurnDriver` and the future `OutputGuard`). A *well-typed* plugin influences the prompt only
  through the data it legitimately returns (a tool result, retrieved memory hits — both framed as
  data per DR-6a). A `ModelProvider` necessarily *sees* the fully assembled request and *returns* the
  reply — so a malicious T1 model provider can read the whole window (including retrieved memory)
  and forge responses; that is inside T1's arbitrary-code reality, not a new gap, and is why the
  install act is the trust boundary. Out-of-process tiers (T2/T3) have no such sight: an MCP server
  sees only the arguments of the tool call the gates admitted; a skill sees nothing.
- **What a plugin can do to `PermissionScope`.** Declare, never define: it sets `requiredScope` on
  its `ToolSpec`s from the existing closed enum. The §9.1.a under-declaration threat (a tool
  declaring a weaker scope than it exercises) applies to T1 with **no code-review backstop** — the
  repo's review gate does not see third-party source. This is absorbed into T1's stated trust class
  (the operator trusts the artifact, not just its declarations) rather than papered over with a
  runtime verifier that cannot exist for in-process code.

---

## 6. Supply chain for Maven plugins (`forvum plugin install`, #31 as-built)

Scope note: this section covers the **plugin-artifact** supply chain only; the *build-input* supply
chain of the Forvum binary itself (dependency pinning, SBOM, CI provenance) remains **DR-6c** (#61,
§9.4). `[DP-10]` — **Settled — flagged for maintainer review.**

- **Resolution is HTTPS-only by default.** `MavenPluginResolver` resolves against the local
  `~/.m2/repository` cache, falling back to Maven Central at
  `https://repo.maven.apache.org/maven2/` (the `forvum.plugins.repository-url` override exists for
  hermetic `file://` tests; production never sets it). Verified in
  `forvum-engine/.../plugin/MavenPluginResolver.java`.
- **Checksums: verified by Resolver, but warn-only today — harden to fail.** Maven Resolver fetches
  and checks the SHA-1 checksum of every downloaded artifact; the as-built `RemoteRepository` uses
  the default `RepositoryPolicy`, whose checksum policy is **`warn`** (verified by disassembling
  `maven-resolver-api` 1.9.x: the no-arg `RepositoryPolicy()` is `(enabled, "daily", "warn")`). A
  warn-on-mismatch install is a silent integrity bypass on the one path that pulls executable code
  from the network. **Settled:** `MavenPluginResolver.remote()` sets
  `CHECKSUM_POLICY_FAIL` on the remote's release policy — a one-line builder change, follow-up on
  the merged #31 surface.
- **Signature (PGP) verification is a documented deferral.** Central publishes `.asc` signatures,
  but key-trust management (whose key? pinned where?) is a real subsystem; Maven itself does not
  verify signatures by default. v0.5 documents the gap instead of faking the control. Re-opens with
  the DR-6c build-input work if at all.
- **The drop-in directory's permissions.** `forvum init` creates dirs `0700`/files `0600` — but it
  does not scaffold `plugins/`, and `MavenPluginResolver.streamInto` creates it with plain
  `Files.createDirectories` (umask default). When `~/.forvum` was created by `init`, the `0700` root
  denies traversal, containing the gap; a hand-made home has no such guarantee. **Settled:** the
  installer creates `plugins/` (and the installed JAR) owner-only via the `InitCommand`
  recipe — a world-writable plugin dir is a code-injection point. Same follow-up class as the
  checksum line.
- **Native binary: stage-and-warn is correct.** Under `ImageMode.NATIVE_RUN` the command still
  resolves + stages the JAR but warns that only a rebuild loads it (verified in
  `PluginInstallCommand`); the drop-in path stays JVM-fast-jar-only **by design** (§6.2/§6.3) — the
  native mandate is itself a supply-chain control (the binary's code set is fixed at build).

---

## 7. Revocation (all tiers)

Revocation is file removal plus the existing hot-reload machinery — no new revocation subsystem.
`[DP-11]` — resync semantics **Ratified (wave directive)**; the rest is as-built confirmation.

| Tier | Revoke by | Takes effect |
|---|---|---|
| T2 MCP server | `rm mcp-servers/<name>.json` (or edit it invalid) | `ConfigWatcher` fires (`"mcp-servers"` is in `WATCHED_SUBFOLDERS`, verified on `main`); #38's config-driven `ToolRegistry` resync **withdraws** the server's `mcp.<server>.*` specs — DELETED and modified-into-invalid both unregister, mirroring the M19 cron lesson (never leave a stale spec live). In-flight calls complete; the next belt materialization excludes the tools. |
| T3 skill | `rm skills/<skill>.md` | `"skills"` is watched; the invoker no longer resolves the name. |
| T1 drop-in plugin | delete the JAR from `~/.forvum/plugins/` + **restart the fast-jar** | `plugins/` is deliberately **not** watched (excluded from `WATCHED_SUBFOLDERS` alongside `state/`); in-process code cannot be safely unloaded, so restart is the honest unit of revocation. Native: the plugin was never loaded. |
| Any tool, per agent | edit `agents/<id>.json` `allowedTools` / `roles/<name>.json` | watched; next turn's belt/effective-scopes exclude it — the fastest revocation lever and the one that needs no restart for T1 tools too. |

---

## 8. Decision points for sign-off

| # | Decision | Position | Ratification |
|---|---|---|---|
| `[DP-1]` | Four trust tiers T0–T3 with engine-only enforcement; closed scope vocabulary | As §2 table | Settled — flagged for maintainer review |
| `[DP-2]` | Remote MCP tool specs breach §9.1.a → UNTRUSTED specs behind three gates (belt allowlist; `MCP_REMOTE` RBAC; `mcp add` = listing-only grant) | Ruled as §3 | **Ratified (wave directive 2026-06-09)** |
| `[DP-3]` | MCP tool results are untrusted DATA — DR-6a framing, never instructions | Yes | **Ratified (wave directive 2026-06-09)** |
| `[DP-4]` | MCP tool names namespaced `mcp.<server>.<tool>` (registry uniqueness + glob-belt safety; bare `*` belts documented as footgun) | Yes | Settled — flagged for maintainer review |
| `[DP-5]` | A new enum constant `PermissionScope.MCP_REMOTE` (reserved in §4.3.4; lands with #38) on every MCP-surfaced spec; `default-user` acquires it (`EnumSet.allOf`), `cron` does not; coarse (per-class, not per-server) in v0.5 | Yes | Constant **Ratified (wave directive)**; coarseness note Settled — flagged |
| `[DP-6]` | Transports HTTP/SSE only; stdio parsed-but-flag-off (Risk #9 trigger unchanged); `mcp add`/`mcp list` are `CommandMode` one-shots | Yes | **Ratified (wave directive 2026-06-09)** |
| `[DP-7]` | Skill = operator-trusted CONTENT with full front-matter input schema; args validated; template runs under the invoking agent's existing belt — zero scope escalation by skill | Yes | **Ratified (wave directive 2026-06-09)** |
| `[DP-8]` | Future `SkillInvokerTool` declares a dedicated `SKILL_INVOKE` scope (decided at its landing issue; nothing depends on it this wave) | Recommended | Settled — flagged for maintainer review |
| `[DP-9]` | T1 drop-in plugins are in-process, unsandboxed, core-equivalent trust once loaded; install act = the trust decision; no prompt-assembly SPI hook; no runtime verifier pretended | Yes (OpenClaw-parity honesty) | Settled — flagged for maintainer review |
| `[DP-10]` | Plugin supply chain: HTTPS Central + Resolver checksums **hardened to fail** (today `warn` — verified); PGP signatures = documented deferral; `plugins/` + installed JARs created owner-only (today umask — gap named); build-input supply chain stays DR-6c | Yes | Settled — flagged for maintainer review |
| `[DP-11]` | Revocation = file removal + existing hot-reload (`mcp-servers/`/`skills/` watched — verified; #38 resync withdraws specs on DELETED/invalid); T1 = delete JAR + restart (`plugins/` unwatched by design) | Yes | Resync **Ratified (wave directive)**; remainder as-built confirmation |

---

## 9. Open issues / dependencies

- **#38 (P2-13)** implements: the `forvum-tools-mcp-bridge` module (HTTP/SSE via the Quarkiverse
  `quarkus-langchain4j-mcp` extension, §3.3 — never the standalone `-beta` artifact), the
  `mcp.<server>.<tool>` naming, `requiredScope = MCP_REMOTE` on every surfaced spec, the
  `ToolRegistry` config-driven resync on `ConfigurationChangedEvent`, the stdio flag-off parse path,
  and the `mcp add`/`mcp list` one-shots. The `MCP_REMOTE` constant is additive to `forvum-core`
  (`PermissionScope` grows "at milestone boundaries" — its own Javadoc) with the mandatory
  `fromName` property-test extension (§10).
- **#32 (P2-7)** implements: `forvum skill install <url>` writing `skills/<skill>.md` owner-only with
  full front-matter input schema; validation through the real reader (the P2-9 doctor lesson: one
  loader, no parallel schema).
- **#31 (P2-6, merged)** gains two small follow-ups from §6: `CHECKSUM_POLICY_FAIL` on the Central
  remote and owner-only `plugins/` creation. Both are one-liners on the merged surface; tracked with
  this round's sync, implemented in the next touching PR.
- **TEST-SEC (#65)** gains three negative scenarios from this round: an out-of-belt MCP tool call →
  denied + audited; a role without `MCP_REMOTE` invoking an in-belt MCP tool → denied + audited; a
  skill template attempting an out-of-belt tool call → denied (the prompt-injection-via-skill case,
  same shape as the existing scripted-injection test).
- **DR-6c (#61)** retains audit retention, the build-input supply chain, and privacy; §6's boundary
  note hands the PGP/signature question there if it ever re-opens.
- **`SKILL_INVOKE`** (`[DP-8]`) is decided at the `SkillInvokerTool` landing issue; this round only
  records the recommendation.

---

## 10. ULTRAPLAN sync

Applied with this round (anchors verified on the worktree):

1. **§9.3 inserted** after §9.2.5 — "Plugin, MCP-server, and skill trust (DR-6b)": the trust-tier
   table, the §9.1.a breach ruling with the three MCP gates and data-framed results, skill-file
   trust, the Maven-plugin supply-chain posture (+ the two #31 follow-ups), and revocation semantics,
   with decisions tagged `[6b-DP-n]` mapping 1:1 to this file's `[DP-n]`.
2. **§9.1 intro touch-up** — the carve-out sentence now points plugin/MCP/skill trust at the settled
   §9.3 and leaves audit retention, build-input supply chain, and privacy with DR-6c (#61, §9.4).
