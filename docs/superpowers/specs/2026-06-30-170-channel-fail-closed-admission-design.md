# #170 — Replace fail-open channel-authorization defaults with explicit opt-in

**Issue:** [#170](https://github.com/eldermoraes/forvum/issues/170) · `security(channels)` · Severity High ·
milestone *Hardening / Production Readiness*.
**Branch:** `security/170-channel-fail-closed-admission`.
**Date:** 2026-06-30.

## 1. Problem

The `codex-review.md` hardening audit (finding #11) found that the channel, pairing, and RBAC defaults
compose into a **complete permissive path**: a freshly enabled remote channel can accept any platform
user, skip device authentication, and bind a broad tool-scope set. #170 is the **integration layer** that
closes the channel-admission half of that path, composing the three already-merged spine issues (#166
device-token auth, #167 agent role caps, #168 identity fallback / fail-closed).

The concrete defect this spec fixes: **seven** messaging/voice channels treat an empty/missing
`allowedUserIds` as *allow every user*.

| Channel | Predicate (today) | File:line |
|---|---|---|
| Telegram | `allowedUserIds.isEmpty() \|\| contains(id)` | `TelegramChannelConfig.java:129-131` |
| Discord | `allowedUserIds.isEmpty() \|\| contains(id)` | `DiscordChannelConfig.java:131-133` |
| Slack | `allowedUserIds.isEmpty() \|\| contains(id)` | `SlackChannelConfig.java:143-145` |
| Matrix | `allowedUserIds.isEmpty() \|\| contains(id)` | `MatrixChannelConfig.java:148-150` |
| Signal | `if (allowedUserIds.isEmpty()) return true;` | `SignalChannelConfig.java:145-155` |
| WhatsApp | `allowedUserIds.isEmpty() \|\| contains(id)` | `WhatsAppChannelConfig.java:153-155` |
| Voice | `allowedUserIds.isEmpty() \|\| contains(id)` | `VoiceChannelConfig.java:206-208` |

## 2. What is already done by the spine (do NOT duplicate)

- **Unresolved identity → least privilege (#168).** `IdentityResolver.resolveEffective` returns
  `EffectiveIdentity("anonymous", ["anonymous"])` for an unmapped user with no agent fallback;
  `RoleRegistry` resolves `anonymous` to the **empty** scope set. The anonymous tail binds
  `roleNames=["anonymous"]` (never `[]`), so it cannot collapse into the permissive empty-roles
  `default-user` branch. **Acceptance criterion "unknown identities → least-privilege" is therefore already
  satisfied** for the unresolved case; #170 documents + tests it, it does not re-wire it.
- **Web channel is already fail-closed (#165/#166).** `OperatorAuthMechanism` requires an operator or
  device token; `OperatorAuthFailClosed` aborts boot if a server channel is exposed with no operator
  credential. Web has no `allowedUserIds` and needs no admission flip. Its mechanism javadoc already names
  #170 as "reusing the same seam".
- **Device-token auth + `approvedScopes` intersection (#166)** is wired at `TurnService.dispatch`. #170
  keeps device pairing **opt-in / orthogonal** (decision A1 below) — it does not make pairing mandatory.

## 3. Decisions (ratified)

- **A1 — scope.** #170 = **channel-admission flip only**. A remote channel with no allowlist and no public
  flag denies *every* sender (this is the "fails closed" branch of acceptance criterion 5's "fails closed
  OR prevents readiness"). Device pairing (#166) stays opt-in and orthogonal — *not* made mandatory.
- **B1 — architecture.** The admission decision lives once in a new **`forvum-sdk`**
  `ChannelAdmissionPolicy` (pure, Quarkus-free), reused by all seven channels **and** the engine's
  `ConfigDoctor`. Single source of truth, no 7-way drift, one shared contract test — the idiomatic Forvum
  "shared channel contract in the SDK" pattern (cf. `ChannelTurnDriver` / Resolution B).
- **C1 — Voice.** Voice is flipped fail-closed like the six bots (it is a `SERVER_CHANNELS` member with an
  allowlist; its inbox can be fed by a shared/synced folder). **TUI stays the explicit local exemption**
  (stdin REPL, the host's own terminal) — documented and tested separately.
- **Flag spelling:** `allowAllUsers` (boolean, default `false`) in `channels/<id>.json`.
- **Startup warning location:** central, in `ChannelLauncher` (server-mode only → zero cold-start impact),
  naming each channel.

## 4. Design

### 4.1 `forvum-sdk` — `ChannelAdmissionPolicy` (new)

A pure, Quarkus-free final class (the SDK rule). Type-agnostic so Telegram's `Set<Long>` and the others'
`Set<String>` both use it:

```java
package ai.forvum.sdk;

/** The single fail-closed channel-admission policy (#170): a remote channel admits an inbound user iff
 *  the user is in a non-empty allowlist, OR the channel explicitly declares public mode. An empty/missing
 *  allowlist with no public flag DENIES every sender — the deliberate inversion of the pre-#170 default. */
public final class ChannelAdmissionPolicy {
    private ChannelAdmissionPolicy() {}

    /** Fail-closed admission: non-empty allowlist → membership; empty/absent allowlist → only if publicMode. */
    public static <T> boolean admits(Set<T> allowedIds, boolean publicMode, T userId) {
        if (allowedIds != null && !allowedIds.isEmpty()) {
            return allowedIds.contains(userId);
        }
        return publicMode;
    }

    /** True when the channel admits NOBODY: empty/missing allowlist AND no public mode (the safe default). */
    public static boolean deniesEveryone(Set<?> allowedIds, boolean publicMode) {
        return (allowedIds == null || allowedIds.isEmpty()) && !publicMode;
    }

    /** Contradictory: public mode AND a non-empty allowlist — the allowlist is then dead config. */
    public static boolean isContradictory(Set<?> allowedIds, boolean publicMode) {
        return publicMode && allowedIds != null && !allowedIds.isEmpty();
    }
}
```

### 4.2 Seven channel `Spec` records — flip + `allowAllUsers`

For each of Telegram, Discord, Slack, Matrix, Signal, WhatsApp, Voice:

1. Add a `boolean allowAllUsers` component to the `Spec` record (last position).
2. Parse it from the JSON tree: `root.get("allowAllUsers")` → `asBoolean(false)` (absent/garbage → `false`).
3. `Spec.empty()` passes `allowAllUsers=false`.
4. The admission predicate (`isUserAllowed` / `isSenderAllowed`) delegates:
   `return ChannelAdmissionPolicy.admits(allowedUserIds, allowAllUsers, userId);`
   (Signal/WhatsApp/Voice keep their multi-id / null-id wrapper, calling `admits` for the actual decision.)

The on-demand re-read each channel already performs means a hot edit that empties the allowlist now
**denies** on the next cycle — no allow-all window, by construction.

### 4.3 Central startup audit — `ChannelLauncher` (app)

On the server-channel startup path (already gated to server mode, so no one-shot cold-start cost), for each
channel that **will actually serve** (the existing `serves(id, spec)` gate: enabled + required credentials
present) **and is admission-governed** (the seven flipped channels — Web is token-gated and TUI is the
local exemption, both excluded), read its `channels/<id>.json` raw spec, extract `allowedUserIds` (array,
non-empty?) + `allowAllUsers` (bool), and via `ChannelAdmissionPolicy`:

- `isContradictory` → `WARN`: "channel `<id>`: allowAllUsers + allowedUserIds — the allowlist is ignored".
- public mode → `WARN` (conspicuous): "channel `<id>` is PUBLIC — any platform user can send turns; they
  run as the anonymous identity (no tool scopes) unless mapped to an identity".
- `deniesEveryone` → `WARN` (actionable): "channel `<id>` is enabled but admits no users — add
  `allowedUserIds` or set `allowAllUsers: true`".

No secret is ever logged (only the channel id and the boolean posture). Web/TUI are skipped (Web is
token-gated; TUI is the local exemption).

### 4.4 `ConfigDoctor.checkChannelSecurity` — offline twin

A new check reusing the same `ChannelAdmissionPolicy` over each `channels/<id>.json` (reader-as-oracle), so
`forvum doctor` reports the exact `channels/<id>.json` + field for: public mode (WARNING), contradictory
(WARNING), denies-everyone (WARNING). A doctor pass is exactly an admission posture the launcher audits
identically — no second, drifting schema.

### 4.5 "Public mode does not grant privileged scopes"

Satisfied **structurally** by composition with #168: a public-mode-admitted user is unmapped → `anonymous`
→ empty scope set → `ToolExecutor` denies every scoped tool (FS_WRITE, SHELL_EXEC, MCP_REMOTE, WEB_BROWSE,
WEB_FETCH, WEB_SEARCH — and FS_READ). **No new scope-capping machinery** (YAGNI). The "second explicit
acknowledgement" required to give public users any scope is the operator's deliberate act of mapping them
to an `identity`/`role`. Documented caveat: do not point a public channel's agent at a privileged
*fallback identity* (the one residual edge explorer-confirmed in `effectiveScopes([]) → default-user`,
which is out of #170's scope — it is resolved-user opt-in RBAC, not the unresolved path).

## 5. Breaking change, migration, docs

This is an **intentional breaking security change**: an existing deployment with an enabled bot and an
empty/absent `allowedUserIds` will, after upgrade, **admit nobody** until the operator declares intent.

- **Migration:** add `allowedUserIds` (restrict to those users) **or** set `allowAllUsers: true` (restore
  the old wide-open behavior, explicitly and conspicuously).
- **Docs to update in the same PR** (CLAUDE.md §10 source-of-truth sync): `docs/DEPLOY.md` (per-channel
  config + migration), `README.md` if it documents channel config, `docs/ULTRAPLAN.md` §9 threat model
  (the #170 as-built note + remove the "WHEN … is #170" deferral), `docs/IMPLEMENTATION-ORDER.md` (mark
  #170 done), and `.github/docs-drift.sh` if a guarded canonical fact changes. A **release-note warning**
  calling out the breaking default.

## 6. Testing (covers the issue's Verify section)

| Layer | Test | Asserts |
|---|---|---|
| sdk unit | `ChannelAdmissionPolicyTest` | missing/empty → deny; member → allow; non-member → deny; public+empty → allow; contradictory & denies-everyone predicates |
| channel unit (×7) | `*ChannelConfigTest` | **inverted** contract: empty/missing allowlist now **denies**; non-member denies; member allows; `allowAllUsers:true` allows any. (These flip the 7 current "empty = allow all" assertions — the expected TDD red.) |
| channel IT (×N) | existing processor ITs | deny path now fires for an empty allowlist; allow path with a populated allowlist unchanged |
| app | `ChannelLauncher` audit test | public WARNING emitted + named; contradictory WARNING; denies-everyone WARNING |
| engine | `ConfigDoctorTest` | public/contradictory/denies-everyone findings with `channels/<id>.json` + field |
| app E2E | public-mode privileged-scope rejection | a public-mode channel admits an unmapped user → `anonymous` → a scoped tool is **denied** (zero successful tool calls); a rejected sender performs **zero** provider/tool calls |
| native | representative channel ITs | behavioral flip native-compiles unchanged |

**Operational reminders:** the new SDK class adds executable lines to a near-vacuous module — run
`./mvnw verify` (the JaCoCo `check` runs on `verify`, not `test`; the [P2-OUTPUTGUARD] trap) **and**
`bash .github/concurrency-guardrails.sh` before pushing.

## 7. Plan shape

One spec, multi-phase plan (the channel sweep is parallelizable):

1. `ChannelAdmissionPolicy` + `ChannelAdmissionPolicyTest` (SDK) — the shared contract first.
2. Flip the 7 channel `Spec` records + invert their `*ChannelConfigTest`s + processor ITs.
3. Central `ChannelLauncher` audit + test.
4. `ConfigDoctor.checkChannelSecurity` + test.
5. App E2E (public-mode privileged-scope rejection; rejected-sender zero-calls).
6. Docs + migration + release-note + drift gate.

## 8. Out of scope

- Making device pairing (#166) mandatory for remote channels (decision A1).
- Re-wiring `RoleRegistry.effectiveScopes([]) → default-user` for *resolved* identities (that is opt-in
  RBAC by design; only the *unresolved* path is #170's concern, and #168 already closed it).
- The outbound message-send tool / `ChannelSender` SPI (#188, sequenced after #170).
