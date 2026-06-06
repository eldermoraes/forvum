# Tier-E — Channels Implementation Plan (M15 · M16 · M17)

> **For agentic workers: REQUIRED SUB-SKILLS.** Run `quarkus/skills` + (for non-Quarkus libs) `context7`
> BEFORE writing any code — TamboUI / JLine 3 via `context7` (not a Quarkus extension);
> `quarkus-websockets-next` (M16) and `quarkus-rest-client` (M17) via `quarkus/skills`. Run tests via the
> Dev MCP for the HTTP-bearing web module and via Surefire for the headless TUI/Telegram libraries
> (CLAUDE.md §4 exception). Produced by a multi-agent ground-truth sweep of `origin/main` HEAD `15648cd`
> (post-Tier-D-merge, PR #101), then hand-verified file:line. (Note: the adversarial-refute phase was
> degraded — 6/8 refuters returned no structured output — so every load-bearing claim below was
> re-verified directly; the four §2 decisions were ratified by the maintainer 2026-06-05.)

**Goal.** Land the first three production turn-driving channels: **M15** TUI (TamboUI on JLine 3),
**M16** Web (WebSockets Next), **M17** Telegram (long-poll). These are the first surfaces that actually
drive a turn; before this window the only turn callers are tests and the 5 e2e provider scripts.

**Architecture.** All three are Layer-3 plugins depending ONLY on `forvum-sdk` + `forvum-core`
(`ChannelProvider` SPI in sdk; `ChannelMessage` inbound + the sealed `ai.forvum.core.event.AgentEvent`
hierarchy outbound in core). They serve the **Channel I/O** bounded context (ULTRAPLAN §2.7 / §3.5) — the
transport surface, not a pillar of their own (CLAUDE.md §6).

**Tech stack.** Java 25 · Quarkus 3.33.1 · TamboUI 0.3.0 (via `tamboui-bom`, already in `forvum-bom`) +
`tamboui-jline3-backend` (M15) · `quarkus-websockets-next` (M16) · `quarkus-rest-client` + `-jackson`
(M17, BLOCKING, `@RunOnVirtualThread`). **Zero `forvum-bom` dependency-VERSION edits** — `tamboui-bom` is
already imported (M1) and the two Quarkus extensions are platform-BOM-managed (versionless); the only
`forvum-bom` edits are the three `ai.forvum:forvum-channel-*` `dependencyManagement` rows so `forvum-app`
can depend on them versionless.

**Authoritative baseline.** `origin/main` HEAD `15648cd`. Reactor = 10 modules (`pom.xml:25-34`); NO
`forvum-channel-*` yet. `security/` dir EXISTS (Tier-D); `e2e/` has 5 of 10 scripts.

**Issue map.** M15→#20, M16→#21, M17→#22 (Mn→#(n+5); all OPEN). PRs target `main`, each `Closes #(n+5)`.

---

## ⚠ AUTHORITATIVE CORRECTIONS (read first — these override the plan body and the prior docs)

Supersede stale lines in `docs/ULTRAPLAN.md` §7.1 and `docs/ISSUES.md`; incorporate the maintainer
sign-off (2026-06-05). Where this banner conflicts with the body or the source docs, the banner wins.

- **AC-E1 — `ChannelProvider` is still the M3 stub; the transport contract is genuinely un-settled.**
  `forvum-sdk/.../ChannelProvider.java` is a sealed interface with ONLY `String extensionId();`; its
  Javadoc defers the inbound `ChannelMessage` + outbound `AgentEvent`-stream methods to M15-M17.
  `AbstractChannelProvider` is an empty non-sealed base. The **M16 anchor** adds the transport method(s)
  in one dedicated SDK commit, mirroring `ModelProvider.resolve` (M7 prelude) and `ToolProvider.tools()`
  (M13 prelude). **(hand-verified.)**

- **AC-E2 — DECIDED (2026-06-05): the anchor is M16 (Web), not the roadmap-first M15.** `ISSUES.md:61`
  is load-bearing: `M7 + M8 → M15 ; M7 → M16 ; M7 + M8 → M17` — **M16 is the only channel not depending
  on M8 (fallback)**, the lightest dep, and WebSockets Next is the natural place to prove the outbound
  `AgentEvent` push. M16 settles the `ChannelProvider` SPI + the engine seam; M15/M17 implement it.
  Roadmap *numbering* stays M15<M16<M17; *merge/anchor order* is **M16 first** — state it explicitly, it
  inverts the number-order intuition. **(hand-verified `ISSUES.md:61`.)**

- **AC-E3 — DECIDED (2026-06-05): `Agent.respond` is SINGLE-SHOT `String`; there is NO turn-level
  `AgentEvent` producer today, and the window ships single-shot adaptation (streaming deferred to M18).**
  `Agent.java:68-84` calls non-streaming `model.chat(ChatRequest)` and returns a `String`;
  `FallbackStreamingChatModel` exists but is constructed ONLY in its own test (zero production sites);
  `LlmSelector.java:44` hardcodes `new FallbackLink(ref, resolved, null)` (streaming slot null) and `:46`
  passes `onEvent = null`. No production code constructs `TokenDelta`/`Done`. The §4 decision: the anchor
  TYPES the outbound SPI for streaming but emits a single-shot adaptation (`TokenDelta(reply)` + `Done`);
  true per-token streaming arrives with M18's `SupervisorGraph` (the doc-sanctioned `AgentEvent`-per-node
  producer, §5.5). The §7.1 "streamed tokens" verify lines self-refresh to "see the assistant reply".
  **(hand-verified.)**

- **AC-E4 — M15 carries the HIGH native risk; the JLine-3 carve-out is pre-documented in
  `forvum-app/pom.xml:86-98`.** `jline-native`'s `org.jline.nativ.Kernel32` fails build-time class
  initialization under native-image; M15 hand-authors the repo's FIRST `reachability-metadata.json` AND
  adds `initialize-at-run-time=org.jline.nativ.Kernel32`. ULTRAPLAN §5.4 claims TamboUI ships "bundled
  GraalVM reachability hints" — reported FALSE by the sweep for `tamboui-toolkit-0.3.0` /
  `tamboui-core-0.3.0` (no `META-INF/native-image`), so the hand-authored metadata is load-bearing.
  (Confirm at M15 impl; CLAUDE.md §5 sanctions only *behavioral* native skips, so a native-COMPILE
  failure here is a genuine risk needing an upstream issue + JVM-only note.) **(carve-out comment
  hand-verified.)**

- **AC-E5 — DECIDED: the outbound SPI type is a JDK type (`Consumer<AgentEvent>` / `Flow.Publisher`),
  never Mutiny `Multi`.** `forvum-sdk` is Quarkus/Mutiny-free; §3.8 classifies `Flow.Publisher` as NOT
  reactive programming. The WebSockets-Next outbound path needs NO Mutiny: a `@RunOnVirtualThread
  @OnTextMessage` handler pushes each token via `connection.sendTextAndAwait(...)` (blocking, VT-safe).
  A `Multi<String>`-returning endpoint is reactive-where-VT-suffices → PR rejection (CODE-REVIEW §3.5).

- **AC-E6 — `quarkus-rest-client-reactive` is FORBIDDEN in M17.** ULTRAPLAN Risk #12 + §3.8 mandate the
  BLOCKING `quarkus-rest-client` + `-jackson` on a virtual thread (`@RunOnVirtualThread`). Reject any
  Mutiny/reactive REST client — it is the obvious-but-wrong Quarkus default.

- **AC-E7 — DECIDED (2026-06-05): the CI concurrency greps do NOT exist yet — BUILD them in the M16
  anchor.** `.github/workflows/ci.yml` has no `synchronized`-ban, no `-Djdk.tracePinnedThreads=full` /
  `Thread pinned` scan, no Mutiny-import grep; no `pinning-allowlist.txt` / `vt-allowlist.txt`. They were
  scheduled M5/M6 and slipped (CODE-REVIEW.md "planned: M5/M6"); enforcement is review-only today. The
  channels are the first VT-heavy inbound surface and the first place a Mutiny leak / pin could hide, so
  the anchor ADDS the three greps + the two allowlist files (§9).

- **AC-E8 — STALE-DOC notes (the milestone PRs self-refresh their §7.1 / ISSUES blocks, CLAUDE.md §10).**
  (1) ULTRAPLAN §3.5 "Channels" covers all three channels (§3.6 is "Observability") — the "3.5=TUI,
  3.6=Web/Telegram" premise is wrong. (2) `ISSUES.md` M17 Files OMITS `UpdateProcessor.java` that
  ULTRAPLAN §7.1 includes. (3) `ISSUES.md` M16 Context "the security-test directory lands alongside the
  web channel" is stale — `security/` already exists (Tier-D). (4) §7.1 M12 still says
  `vertex-ai-gemini` though merged M12 uses `-ai-gemini` — §7.1 lags post-merge.

---

## 0. Ground-truth (verified on `origin/main` HEAD `15648cd`, post-Tier-D-merge PR #101)

Bullets marked **(hand-verified)** were re-confirmed by direct read during planning.

- **GT-E1 — reactor is 10 modules; NO `forvum-channel-*`.** `pom.xml:25-34`. Each channel module is
  net-new (root `<module>` + `forvum-bom` `dependencyManagement` row + `forvum-app` wiring). **(hand-verified.)**
- **GT-E2 — `ChannelProvider` / `AbstractChannelProvider` are stubs** (`extensionId()` only; empty base).
  **(hand-verified.)**
- **GT-E3 — `ChannelMessage` (inbound) exists in `forvum-core`** — `record(channelId, nativeUserId,
  content, timestamp)` with validating canonical constructor; Javadoc: "Outbound tokens flow back as
  `TokenDelta` events, not as `ChannelMessage`." **(hand-verified.)**
- **GT-E4 — sealed `AgentEvent` hierarchy (outbound) exists in `forvum-core/.../event/`** (sub-package,
  not `core/` directly): permits `TokenDelta, ToolInvoked, ToolResult, FallbackTriggered, Done, ErrorEvent`.
  **(hand-verified.)**
- **GT-E5 — `Agent.respond(sessionId, userText)` is single-shot `String`** (non-streaming `model.chat`,
  `return reply`). Javadoc: routing/tool-loop/fan-out "arrive with the LangGraph4j `SupervisorGraph`
  (M18)." **(hand-verified.)**
- **GT-E6 — `FallbackStreamingChatModel` exists but is DEAD in production** — referenced in main only by
  `FallbackLink` (a record field) + its own file; constructed ONLY in `FallbackStreamingChatModelTest`.
  `LlmSelector` has one method `select(...)→ChatModel`, no `selectStreaming`. **(hand-verified.)**
- **GT-E7 — NO `TurnService`/`ChannelRegistry`/`IdentityResolver` in source** (grep matches only in
  `docs/`). The turn-driving "dance" (`registry.getOrCreate(id)` + `ScopedValue.where(CURRENT_AGENT,
  id).call(() -> agent.respond(...))`) lives ONLY in `AgentTurnTest` + the 5 e2e scripts.
  `CurrentAgent.CURRENT_TURN` is defined but bound by no caller. **(hand-verified absent.)**
- **GT-E8 — `SessionManager.ensureSession` writes PLACEHOLDER identity/channel** (`DEFAULT_IDENTITY =
  "default"`, `DEFAULT_CHANNEL = "internal"`); Javadoc defers real per-conversation identity/channel to
  "the channels (M15-M17)". **(hand-verified.)**
- **GT-E9 — `Identity` has only a FORWARD map; no resolver.** `forvum-core/.../id/Identity.java`
  `record(id, displayName, Map<String,String> channelAccounts)` maps `channelId → nativeUserId`. Channel
  inbound needs the INVERSE `(channelId, nativeUserId) → Identity` — no index, no `IdentityResolver`.
  `IdentityReader` reads `identities/<id>.json` but has no resolution logic.
- **GT-E10 — TamboUI toolkit+widgets already in `forvum-app`** (versionless via `tamboui-bom`), used for
  the M1 headless banner; the JLine-3 backend is deferred to M15 with the Kernel32 native blocker
  pre-documented (`forvum-app/pom.xml:86-98`). **(hand-verified.)**
- **GT-E11 — `forvum-app` has ZERO HTTP/web extensions today**; pure command-mode TamboUI-banner binary.
  M16 is the FIRST time the assembled app gets an HTTP server.
- **GT-E12 — `ForvumApplication.run()` unconditionally `return 0` after a banner** (`@QuarkusMain`); NO
  command-mode-vs-server-mode dispatch. `ChannelReader` (engine config) EXISTS and reads
  `$FORVUM_HOME/channels/<id>.json` (M4) — the config-enablement seam is pre-built. **(hand-verified.)**
- **GT-E13 — `security/` exists** (Tier-D); the X5/TEST-SEC spawn-boundary identity-override test does
  NOT. `e2e/` has 5 provider scripts; channel scenarios #3/#6/#7 do not exist.
- **GT-E14 — `forvum-app` is the sole native-image site**; a channel native-COMPILES only once
  `forvum-app` depends on it (CLAUDE.md §14).
- **GT-E15 — no source-side `META-INF/native-image` / `reachability-metadata.json` exists yet**; M15's is
  the repo's first.

---

## 1. Pre-branch blockers (resolve BEFORE branching the window)

| # | Blocker | Resolution | Gate |
|---|---|---|---|
| B-E1 | `ChannelProvider` transport-method signatures | **DECIDED §2/§3:** inbound `ChannelMessage` + outbound JDK `Consumer<AgentEvent>` (AC-E5). Added in the M16 SDK prelude commit. | maintainer sign-off ✅ |
| B-E2 | How a channel obtains an `AgentEvent` stream off single-shot `Agent.respond` | **DECIDED §4 (Option B):** the engine adapts single-shot → `TokenDelta(reply)` + `Done`; true streaming is M18. | maintainer sign-off ✅ |
| B-E3 | M15 JLine-3 native class-init + first `reachability-metadata.json` (AC-E4) | `initialize-at-run-time=org.jline.nativ.Kernel32` + hand-authored metadata; `--no-ansi` first-class | native smoke green |
| B-E4 | M16 makes `forvum-app` HTTP-bearing; server-mode-stay-alive vs command-mode-exit (GT-E11/E12) | **DECIDED §2:** interim launch dispatch in `ForvumApplication.run()` keyed by `channels/*.json` enablement (picocli is M20); web/telegram keep the binary alive, TUI is blocking command-mode | native smoke boots with no `~/.forvum/` |
| B-E5 | M17 Telegram token config + no-config-boot graceful no-op | `channels/telegram.json` enablement via existing `ChannelReader`; absent token → warn + no-op (CLAUDE.md §14) | native smoke green |
| B-E6 | Turn-driver facade + inverse identity index (GT-E7/E8/E9) | **DECIDED §2/§8:** the M16 anchor adds a thin engine `TurnService` + `IdentityResolver`; `ensureSession` gains resolved identity/channel | maintainer sign-off ✅ |
| B-E7 | CI concurrency greps absent (AC-E7) | **DECIDED §9:** the anchor builds the `synchronized`/pinned/Mutiny greps + allowlist files | maintainer sign-off ✅ |
| B-E8 | Baseline ref / shared poms | `origin/main` `15648cd`; per channel: root `<module>`, `forvum-bom` `dependencyManagement` row, `forvum-app` `<dependencies>` (no `forvum-bom` version edits) | reactor builds |

All architectural blockers (B-E1, B-E2, B-E4, B-E6, B-E7) are signed off (§2).

---

## 2. Architectural sign-off items (CLAUDE.md §8) — STATUS (all DECIDED 2026-06-05)

- **SI-E1 — `ChannelProvider` transport SPI signatures → DECIDED:** inbound `ChannelMessage` + outbound
  JDK `Consumer<AgentEvent>` (never Mutiny `Multi`; AC-E5). Settled by the M16 anchor; M15/M17 implement.
  **SUPERSEDED at M16 implementation (Resolution B, PR #103):** `ChannelProvider` stays a pure discovery
  marker (`extensionId()` only — NO transport method). The decided contract (inbound `ChannelMessage` +
  outbound `Consumer<AgentEvent>`) lives on a SEPARATE new SDK interface `ChannelTurnDriver` that the
  engine's `TurnService` implements and the channel `@Inject`s — the channel is self-driving (it calls the
  driver; the engine never calls back into `ChannelProvider`). Same signatures + types as decided here;
  only the host type changed (a new SDK interface, not methods on `ChannelProvider`), which keeps
  `forvum-sdk` Quarkus-free and the channel enforcer at `{forvum-sdk, forvum-core}`. (Thus AC-E1's
  "anchor adds transport methods to `ChannelProvider`" and B-E1 read against `ChannelTurnDriver`, not
  `ChannelProvider`.)
- **SI-E2 — `AgentEvent`-stream production off single-shot `respond` → DECIDED Option B (§4):** engine
  emits `TokenDelta(reply)` + `Done`; SPI typed for streaming; true streaming deferred to M18.
- **SI-E3 — turn-driver facade + identity resolution → DECIDED full facade:** the anchor adds a thin
  engine `TurnService` (encapsulates `getOrCreate` + `ScopedValue.where(CURRENT_AGENT, id)` + `respond`;
  binds `CURRENT_TURN`), an `IdentityResolver` building the inverse `(channelId, nativeUserId)→Identity`
  index, and `ensureSession` taking resolved identity/channel (replaces the `default`/`internal`
  placeholders). All three channels call the one facade.
- **SI-E4 — anchor = M16 (Web) → DECIDED (AC-E2):** merge order M16 → {M15, M17}.
- **SI-E5 — CI concurrency enforcement → DECIDED build-in-anchor (AC-E7 / §9).**
- **SI-E6 — impl-time, flagged:** the M15 JLine native carve-out (AC-E4); the reactive-WS-boundary review
  gate (AC-E5); the M17 `allowedUserIds` security amendment (X5/TEST-SEC).

---

## 3. Window shape · merge order · shared files · gating edges

- **Anchor = M16 (Web)** (AC-E2). It settles `ChannelProvider`'s transport methods, the `TurnService`
  facade, the `IdentityResolver`, the §4 single-shot streaming adaptation, the interim launch dispatch,
  and the CI concurrency greps.
- **Siblings = M15 (TUI), M17 (Telegram).** Both depend on M8 (fallback turn) and implement the settled
  SPI. M15 carries the heaviest native risk (Risks #6 + #14) + the §6.2 cold-start headline; M17 carries
  the `allowedUserIds` security amendment.
- **Hard edges (compile-blocking):** M16 → M15, M16 → M17. The siblings cannot compile against the
  transport SPI until the anchor adds it — AND the anchor lands engine work (`TurnService`,
  `IdentityResolver`, the streaming adaptation, the launch dispatch), so this window is **MORE sequential
  than Tier-D Tools** (whose anchor was a pure-contribution SPI + recorder seam).
- **Soft edges (test-level):** E2E #3 (TUI golden path), #6 (Web parity), #7 (Telegram allowed/denied)
  land with their milestones.
- **Shared files the M16 anchor touches** (everything below the channel modules):
  - `forvum-sdk/.../ChannelProvider.java` — add the transport method(s) (prelude; `AbstractChannelProvider`
    unchanged).
  - `forvum-engine/` — NEW `TurnService`, NEW `IdentityResolver`; EDIT `SessionManager.ensureSession`
    (resolved identity/channel). (No `LlmSelector`/`Agent` streaming change — Option B uses single-shot.)
  - `forvum-app/ForvumApplication.java` — interim launch dispatch (server-mode stay-alive).
  - `.github/workflows/ci.yml` + `pinning-allowlist.txt` + `vt-allowlist.txt` — the concurrency greps (§9).
  - Per channel: root `pom.xml` `<module>`, `forvum-bom` `dependencyManagement` row, `forvum-app`
    `<dependencies>`.

**`ChannelProvider` SPI prelude (anchor commit):**
```java
// forvum-sdk/.../ChannelProvider.java — added by the M16 anchor (the channel-transport prelude)
public sealed interface ChannelProvider permits AbstractChannelProvider {
    String extensionId();
    // inbound: the channel hands the engine a ChannelMessage (forvum-core type) for a turn.
    // outbound: the engine renders the turn as a stream of AgentEvent (forvum-core) via a JDK
    //           Consumer<AgentEvent> — NEVER Mutiny Multi (AC-E5; forvum-sdk is Quarkus-free).
    //           Transport/rendering EXECUTION lives in the channel module, not on this SPI.
}
```
Carries only `forvum-core` types, so `forvum-sdk` needs no new dependency and stays Quarkus-free
(mirrors the M7 `resolve()` / M13 `tools()` preludes). M15/M17 are the first implementors.

**Merge order:** M16 (#21) first → then M15 (#20) and M17 (#22) in either order (rebase each onto the
merged anchor first, per [[stacked-pr-safe-merge]]).

---

## 4. The streaming-turn adaptation (DECIDED: Option B)

`Agent.respond` returns a `String`; no production code emits a turn-level `AgentEvent` stream (AC-E3 /
GT-E5/E6). **Decision (maintainer, 2026-06-05): Option B — the anchor types the outbound SPI for
streaming but ships a single-shot adaptation.**

- The `ChannelProvider` outbound type is `Consumer<AgentEvent>` (a later non-breaking upgrade to true
  streaming).
- `TurnService` drives the single-shot turn (`respond` → `String reply`) and emits a minimal terminal
  event sequence: one `TokenDelta(reply)` then `Done` (channels render that as the assistant reply).
- True per-token streaming is M18's job: the `SupervisorGraph` already emits an `AgentEvent` per node
  (ULTRAPLAN §5.5), and a `ModelProvider.resolveStreaming` SDK prelude (touching all four merged
  providers) belongs there, NOT in a channel window.

**Why not Option A (real streaming now):** it requires a second SDK prelude (`ModelProvider.resolveStreaming`)
touching M9–M12, a `LlmSelector.selectStreaming`, populating `FallbackLink.streaming`, and a brand-new
`StreamingChatResponseHandler → TokenDelta/Done` mapping layer — a large cross-layer change that enlarges
the anchor and duplicates M18's mandate. Consequence accepted: the ULTRAPLAN §7.1 "streamed tokens" /
"streaming rendering" verify lines self-refresh to "see the assistant reply" (documented degradation per
AC-E8); a second pass (M18) re-wires channels to the real stream with no SPI break.

---

## 5. Canonical channel-module recipe (delta vs the ratified provider/tool recipe)

Clone the ratified Layer-3 skeleton (`forvum-provider-ollama` / `forvum-tools-filesystem` — identical
shape) with these substitutions/drops:

- **`pom.xml`** — copy the headless-library config: `quarkus-maven-plugin` with `generate-code` +
  `generate-code-tests` ONLY (NO `build` goal), Surefire includes `**/*Test.java` + `**/*IT.java`. **KEEP
  the enforcer block UNCHANGED** — the allowlist is already `{forvum-sdk, forvum-core}` exclude
  `ai.forvum:*`, exactly what channels need. **DROP** the langchain4j extension; **substitute** the
  per-channel transport deps.
- **`beans.xml`** — copy verbatim (`bean-discovery-mode="annotated"`).
- **`plugin.json`** (`META-INF/forvum/`) — `type: channel`, `class: <ChannelProvider impl>`, the
  extension id.
- **Per-channel dep delta (all versionless):**
  - **M15 (TUI):** `dev.tamboui:tamboui-toolkit` + `dev.tamboui:tamboui-jline3-backend` (both explicit —
    the toolkit does NOT transitively pull the jline3 backend; managed by `tamboui-bom`). PLUS source-side
    `META-INF/native-image/.../reachability-metadata.json` + the `initialize-at-run-time` native build-arg
    (AC-E4). TamboUI/JLine are non-Quarkus → API via `context7`; the *module wiring* still via
    `quarkus/create`.
  - **M16 (Web):** `quarkus-websockets-next` (platform-BOM-managed) → first HTTP server in the assembled app.
  - **M17 (Telegram):** `quarkus-rest-client` + `quarkus-rest-client-jackson` (BLOCKING; NOT `-reactive`,
    AC-E6).
- **Dev-MCP-startability split (NEW vs Tier-C/D):**
  - **M15 (TUI) + M17 (Telegram) — headless libraries → Surefire** (no HTTP server; stdin REPL / outbound
    long-poll). CLAUDE.md §4 exception.
  - **M16 (Web) — HTTP/WS-bearing → Dev MCP.** Its `*IT` runs via the Dev MCP runner ("never raw
    `mvn test`"); the Dev-UI test runner CAN attach to a WS-bearing module even though, as a library with
    no `build` goal, the HTTP endpoint only goes live once `forvum-app` depends on it.

**Net delta vs a provider module:** drop langchain4j; add per-channel transport deps; M15 adds native
metadata; M16 flips the test runner to Dev MCP. Everything else (enforcer, beans.xml, plugin.json, 3
append-only poms, `forvum-app`-only native profile) is identical.

---

## 6. Milestone task outlines

### §6.1 M16 — `forvum-channel-web` (ANCHOR) · Closes #21

**Files** (create unless noted):
- **EDIT** `forvum-sdk/.../ChannelProvider.java` — add transport method(s) (the prelude).
- **NEW (engine):** `TurnService.java` (facade), `IdentityResolver.java` (inverse index); **EDIT**
  `SessionManager.ensureSession` (resolved identity + channelId).
- **EDIT** `forvum-app/ForvumApplication.java` — interim launch dispatch (server-mode stay-alive).
- **NEW (CI):** the `synchronized`/pinned/Mutiny greps in `.github/workflows/ci.yml` + `pinning-allowlist.txt`
  + `vt-allowlist.txt` (§9).
- `forvum-channel-web/.../WebChannel.java` (`@ApplicationScoped` extends `AbstractChannelProvider`),
  `ChatSocket.java` (`@WebSocket`, `@RunOnVirtualThread @OnTextMessage`, `sendTextAndAwait`),
  `META-INF/resources/index.html`, `chat.js`, `plugin.json`, `beans.xml`, `pom.xml`.
- **NEW (test):** `ChannelProviderContractTest` (analogue of `ToolProviderContractTest`), `ChatSocketIT`
  (`@QuarkusTest`, WebSockets via Dev MCP), `WebScriptedTurnE2E`.

**Tasks (Red → Green → Refactor):**
- [ ] **Step 0 — Sub-skills.** `quarkus/skills quarkus-websockets-next` + `quarkus/searchDocs`; harvest
  module wiring via `quarkus/create`.
- [ ] **Step 1 — SPI prelude (Red).** `ChannelProviderContractTest` against the new transport method(s).
  Commit: `feat(sdk): add ChannelProvider transport methods (M16 anchor prelude)`.
- [ ] **Step 2 — Engine seam.** `TurnService` (single-shot → `TokenDelta`+`Done`, Option B) +
  `IdentityResolver` + `ensureSession` change. Commit: `feat(engine): add TurnService facade and
  inverse-index IdentityResolver`.
- [ ] **Step 3 — Web channel (Green).** `WebChannel` + `ChatSocket` (`@RunOnVirtualThread`,
  `sendTextAndAwait` per `AgentEvent`); a 2nd tab gets a separate session id.
- [ ] **Step 4 — launch dispatch + module wiring.** `ForvumApplication` server-mode; 3 append-only poms.
- [ ] **Step 5 — CI concurrency greps + allowlist files (§9).**
- [ ] **Step 6 — `*IT` via Dev MCP** + `WebScriptedTurnE2E`; native smoke (app boots HTTP server with no
  `~/.forvum/`).
- [ ] **Step 7 — `/code-review` (high/ultra) → PR.** Commit: `feat(channel-web): add WebSockets Next chat
  channel with minimal UI`. **Closes #21.**

**Verify:** start dev mode, open `http://localhost:8080/`, exchange a message, see the assistant reply; a
second tab gets a distinct session id; `ChannelProviderContractTest` green; enforcer clean (module ⊆
{sdk, core}); native smoke boots with no config.

### §6.2 M15 — `forvum-channel-tui` (TamboUI/JLine 3) · Closes #20

**Files:** `TuiChannel.java` (`@ApplicationScoped` extends `AbstractChannelProvider`, implements the
settled SPI), `TuiView.java` (TamboUI Toolkit component tree), `tui.tcss` (TCSS theme), a `--no-ansi`
fallback path, `META-INF/native-image/.../reachability-metadata.json` (first in repo), `plugin.json`,
`beans.xml`, `pom.xml`; **EDIT** `ForvumApplication.run()` (TUI command-mode loop).

**Tasks:** Step 0 — `context7` for TamboUI Toolkit + JLine 3 (NOT a Quarkus extension); module wiring via
`quarkus/create`. Step 1 — implement the SPI; render via TamboUI; each turn on a VT
(`Executors.newVirtualThreadPerTaskExecutor()` — no `@RunOnVirtualThread` seam for a stdin REPL, §9).
Step 2 — `--no-ansi` first-class (Risk #6); confine ALL TamboUI/JLine coupling to this module (Risk #14).
Step 3 — native: `initialize-at-run-time=org.jline.nativ.Kernel32` + `reachability-metadata.json`
(AC-E4); a JLine blocking read on a VT MAY PIN → likely a `pinning-allowlist.txt` entry citing the
upstream issue. Step 4 — `*IT` via Surefire (pipe scripted stdin, assert rendered output contains the
reply). Steps 5-7 — wiring, native smoke, `/code-review` → PR. Commit:
`feat(channel-tui): add TamboUI/JLine TUI channel`. **Closes #20.**

**Verify:** integration test pipes scripted stdin, asserts rendered TamboUI output contains the assistant
reply; `forvum-app -Dforvum.no-ansi=true < input.txt` works identically; native smoke renders a TamboUI
frame within the §6.2 cold-start budget.

### §6.3 M17 — `forvum-channel-telegram` (long-poll) · Closes #22

**Files:** `TelegramChannel.java` (`@ApplicationScoped`/`@Startup` extends `AbstractChannelProvider`),
`TelegramBotApi.java` (BLOCKING `@RegisterRestClient`, invoked under `@RunOnVirtualThread`),
`UpdateProcessor.java` (present per ULTRAPLAN §7.1, OMITTED in stale ISSUES — AC-E8), `plugin.json`,
`beans.xml`, `pom.xml`; **ADD to the existing `security/` dir** the X5/TEST-SEC spawn-boundary test.

**Tasks:** Step 0 — `quarkus/skills quarkus-rest-client`; harvest. Step 1 — long-poll loop on a VT
(`@RunOnVirtualThread`), maps Telegram updates → `ChannelMessage` → `TurnService`. Step 2 —
`allowedUserIds` in `channels/telegram.json` (via `ChannelReader`): refuse other users with a friendly
message; absent token → warn + no-op (no-config boot, CLAUDE.md §14). Step 3 — security: `allowedUserIds`
deny test + X5/TEST-SEC spawn-boundary identity-override rejection (net-new in `security/`). Steps 4-7 —
`*IT` via Surefire (stubbed bot API), wiring, native smoke, `/code-review` → PR. **REJECT any
`quarkus-rest-client-reactive`/Mutiny** (AC-E6). Commit: `feat(channel-telegram): add long-poll Telegram
channel`. **Closes #22.**

**Verify:** live DM (token in keychain) produces a reply within the turn latency budget; `allowedUserIds`
refuses other users with a friendly message (E2E #7); no `Mutiny` import in the module; X5/TEST-SEC
spawn-boundary test green.

---

## 7. Test taxonomy & per-PR gates

| Layer | What | Runner | In per-PR gate? |
|---|---|---|---|
| Unit / contract | `ChannelProviderContractTest`; per-channel render/parse | Surefire | ✅ |
| Enforcer | each channel module deps ⊆ {forvum-sdk, forvum-core} | Maven `validate` | ✅ |
| Integration `*IT` | TUI: TamboUI render (Surefire); **Web: WebSockets `@QuarkusTest` via Dev MCP**; Telegram: long-poll w/ stubbed bot API (Surefire) | split (§5) | ✅ |
| Security (negative) | spawn-boundary identity-override → rejected (X5/TEST-SEC); Telegram `allowedUserIds` → denied + friendly | Surefire (ADD to existing `security/`) | ✅ |
| Native smoke | **M15 TUI/JLine = HIGH-RISK** (reachability metadata + Kernel32 init); M16 boots HTTP w/ no config; cold-start watched (hard gate M20) | `-Pnative`, `forvum-app` | ✅ (native leg) |
| Per-turn perf (FakeProvider) | TUI ≤200 ms · Web ≤300 ms · Telegram ≤500 ms (CLAUDE.md §11) | Surefire | ✅ |
| Live | `*-LiveTest @Tag("live")` (Telegram DM) | nightly, default-off | ❌ |

Concurrency: inbound handlers carry `@RunOnVirtualThread` (Web/Telegram); the TUI stdin loop runs each
turn on a VT explicitly (§6.2). ZERO `synchronized` in channel hot paths; NO Mutiny where a VT works —
**now CI-enforced** by the greps the anchor adds (§9). JaCoCo 80% line / 75% branch.

---

## 8. Architectural sign-off items (consolidated — all DECIDED 2026-06-05)

1. `ChannelProvider` SPI: inbound `ChannelMessage` + outbound JDK `Consumer<AgentEvent>` (never Mutiny;
   AC-E5). Settled by the M16 anchor.
2. Streaming: **Option B** (single-shot `TokenDelta`+`Done`, SPI typed for streaming; true streaming at
   M18) (§4).
3. Turn-driver facade + identity: `TurnService` + `IdentityResolver` (inverse index) + `ensureSession`
   resolved (replaces `default`/`internal` placeholders).
4. Anchor: **M16 (Web)**, merge first.
5. CI concurrency enforcement: **build the greps + allowlist files in the anchor** (§9).
6. Impl-time/flagged: M15 JLine native carve-out (AC-E4); reactive-WS review gate (AC-E5); M17
   `allowedUserIds` security (X5/TEST-SEC).

---

## 9. CI concurrency greps (built by the M16 anchor — AC-E7)

The M5/M6-scheduled enforcement slipped; the anchor adds it because the channels are the first VT-heavy
inbound surface:
- **`synchronized` ban** — grep `forvum-engine` + `forvum-channel-*` `src/main` for `synchronized`; fail
  unless the hit is in an allowlisted file (none expected).
- **Thread-pinning scan** — run the VT test legs with `-Djdk.tracePinnedThreads=full`; fail on `Thread
  pinned` unless the carrier/frame is in **`pinning-allowlist.txt`** (M5's SQLite JNI pins + a likely M15
  JLine-read entry, each citing an upstream issue).
- **Mutiny-import grep** — fail on `io.smallrye.mutiny`/`reactor` imports in `forvum-engine` +
  `forvum-channel-*` unless in **`vt-allowlist.txt`** with a framework-mandated-boundary justification.

These are a small, self-contained CI add; they are NOT the M20 native CI matrix (that stays M20).

---

## 10. Open questions / residual risks for the maintainer

1. **TUI thread model (genuine gap).** §3.8's VT-placement rule is written for Quarkus
   `@RunOnVirtualThread` inbound handlers — the TUI stdin REPL fits none. M15 runs each turn on a VT
   explicitly and documents it; a JLine blocking read on a VT may pin → a `pinning-allowlist.txt` entry
   citing the upstream issue.
2. **Server-mode lifecycle (B-E4).** M16 makes the binary HTTP-bearing — it must stay alive (not `return
   0`), while TUI is blocking command-mode and Telegram is a `@Startup` background poll. The interim
   dispatch keys off `channels/*.json` enablement via `ChannelReader`; picocli/args is M20.
3. **Cold-start budget.** `forvum-app` native boot is already ~217 ms warm (gate 200 ms, not CI-enforced
   until M20 — [[native-coldstart-exceeds-200ms]]). M16 adds an HTTP server thread; M15 adds JLine init.
   Watch the budget; the hard gate is M20.
4. **M15 native-COMPILE risk (AC-E4).** If `initialize-at-run-time` + metadata do not yield a clean
   native build, file an upstream issue + a JVM-only release note (CLAUDE.md §5 sanctions only behavioral
   skips, so a compile failure is a real risk) — the Channels analogue of M12's Gemini native ladder.

---

## 11. Branch / PR & merge summary

| Branch | Milestone | Closes | Order |
|---|---|---|---|
| `feat/m16-channel-web` | M16 Web (ANCHOR) | #21 | **merge first** |
| `feat/m15-channel-tui` | M15 TUI | #20 | after M16 (rebase onto merged main) |
| `feat/m17-channel-telegram` | M17 Telegram | #22 | after M16 (rebase onto merged main) |

PRs target `main`; each carries `Closes #(n+5)`; Conventional Commits (imperative) +
`Co-Authored-By: Claude Opus 4.8 (1M context)`; English-only; `git worktree` isolation. No commit/push/
issue without explicit maintainer authorization. Per-PR gates: enforcer (`validate`) · unit/contract ·
`*IT` (Dev MCP for web, Surefire for tui/telegram) · security negative · native smoke (no `~/.forvum/`) ·
JaCoCo 80%/75% · the new concurrency greps. Stacked merges per [[stacked-pr-safe-merge]]: merge M16
without `--delete-branch`, rebase M15/M17 onto main (`git rebase --onto origin/main feat/m16-channel-web
<sibling>`), retarget, merge, delete base last.

**Plan-doc logistics.** This plan lives at `docs/plans/tier-e-channels.md` on branch
`docs/tier-e-channels-plan` (off `origin/main`), mirroring the unmerged-docs-branch pattern Tier-C/D used;
a Tier-E row + `## E.` section are added to `docs/plans/README.md`. Doc-sync: each milestone PR amends its
ULTRAPLAN §7.1 + ISSUES.md block + CLAUDE.md §14 (AC-E8).
