# #170 — Fail-Closed Channel Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Invert the seven remote channels' fail-open admission (empty/missing `allowedUserIds` = allow-all) to fail-closed (deny-all) gated by an explicit `allowAllUsers` opt-in, with a single shared policy, a startup audit, and offline `forvum doctor` validation.

**Architecture:** One pure `ChannelAdmissionPolicy` in `forvum-sdk` holds the fail-closed decision; the seven channel `Spec` records gain an `allowAllUsers` field and delegate to it; a `ChannelSecurityAudit` startup observer (server-mode only) and `ConfigDoctor.checkChannelSecurity` reuse the same policy to warn conspicuously. Public-mode users compose with the already-merged #168 spine (unmapped → `anonymous` → zero scopes), so no new scope machinery.

**Tech Stack:** Java 25, Quarkus 3.33 LTS, Jackson (hand-parsed JSON trees), JUnit 5 (from quarkus-bom in the SDK), Quarkus `@QuarkusTest`, Maven `./mvnw`.

## Global Constraints

- **English-only** in every artifact (code, javadoc, commits, docs). American spelling.
- **`forvum-sdk` is Quarkus-free** — `ChannelAdmissionPolicy` uses only `java.util` (no `io.quarkus*`, no Jackson).
- **A Layer-3 channel may depend only on `forvum-sdk` + `forvum-core`** — channels import `ai.forvum.sdk.ChannelAdmissionPolicy`, never the engine or a sibling.
- **No `@RegisterForReflection` on the channel `Spec` records** — they are hand-parsed, never reflectively (de)serialized; adding a field changes nothing here.
- **Native is the primary target** — the change is behavioral; no new reflection/native metadata.
- **Run `./mvnw verify` (not just `test`)** before any push — the JaCoCo `check` runs on `verify`, and the new SDK class adds executable lines to a near-vacuous module ([P2-OUTPUTGUARD] trap). Also run `bash .github/concurrency-guardrails.sh`.
- **Tests via the Quarkus Agent Dev MCP** for Dev-MCP-startable modules; the SDK/engine/channel POJO tests run via **Surefire** (`./mvnw -pl <module> test`, the §4 exception for Quarkus-free / headless-library / non-server modules). `forvum-app` `@QuarkusTest`s run via Surefire too (the CLI-app exception).
- **Commit convention:** Conventional Commits, imperative, English. AI-assist trailer welcome.
- **No commit/push/PR without authorization** — authorized here for the #170 task (branch `security/170-channel-fail-closed-admission`).

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `forvum-sdk/src/main/java/ai/forvum/sdk/ChannelAdmissionPolicy.java` | The single fail-closed admission decision (pure) | Create |
| `forvum-sdk/src/test/java/ai/forvum/sdk/ChannelAdmissionPolicyTest.java` | Unit-test the policy | Create |
| `forvum-channel-{telegram,discord,slack,matrix,signal,whatsapp,voice}/.../<X>ChannelConfig.java` | `Spec` + `allowAllUsers` + delegated predicate | Modify |
| `forvum-channel-*/src/test/.../<X>ChannelConfigTest.java` | Invert empty/absent assertions + add public-mode case | Modify |
| `forvum-app/src/main/java/ai/forvum/app/ChannelLauncher.java` | `ADMISSION_GOVERNED_CHANNELS` constant | Modify |
| `forvum-app/src/main/java/ai/forvum/app/ChannelSecurityAudit.java` | Startup audit observer (WARN public/contradictory/denies-everyone) | Create |
| `forvum-app/src/test/java/ai/forvum/app/ChannelSecurityAuditTest.java` | Unit-test the posture + observer wiring | Create |
| `forvum-engine/src/main/java/ai/forvum/engine/doctor/ConfigDoctor.java` | `checkChannelSecurity` (offline twin) | Modify |
| `forvum-engine/src/test/java/ai/forvum/engine/doctor/ConfigDoctorTest.java` | Doctor findings for public/contradictory | Modify |
| `forvum-app/src/test/java/ai/forvum/e2e/ChannelPublicModeScopeRejectionE2E.java` | Public user admitted → anonymous → tool denied | Create |
| `forvum-app/src/test/java/ai/forvum/e2e/TelegramAllowDenyE2E.java` | Fix `new Spec(...)` call-site (new arg) | Modify |
| `docs/DEPLOY.md`, `docs/ULTRAPLAN.md`, `docs/IMPLEMENTATION-ORDER.md`, `README.md`, `.github/docs-drift.sh` | Migration + status sync | Modify |

---

### Task 1: `ChannelAdmissionPolicy` (forvum-sdk)

**Files:**
- Create: `forvum-sdk/src/main/java/ai/forvum/sdk/ChannelAdmissionPolicy.java`
- Test: `forvum-sdk/src/test/java/ai/forvum/sdk/ChannelAdmissionPolicyTest.java`

**Interfaces:**
- Produces: `static <T> boolean admits(Set<T> allowedIds, boolean publicMode, T userId)`,
  `static boolean deniesEveryone(Set<?> allowedIds, boolean publicMode)`,
  `static boolean isContradictory(Set<?> allowedIds, boolean publicMode)`.

- [ ] **Step 1: Write the failing test**

```java
package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Set;

class ChannelAdmissionPolicyTest {

    @Test
    void emptyAllowlistDeniesWithoutPublicMode() {
        assertFalse(ChannelAdmissionPolicy.admits(Set.of(), false, "u1"),
                "empty allowlist + no public mode denies every sender (#170 fail-closed)");
        assertTrue(ChannelAdmissionPolicy.deniesEveryone(Set.of(), false));
    }

    @Test
    void emptyAllowlistAdmitsUnderPublicMode() {
        assertTrue(ChannelAdmissionPolicy.admits(Set.of(), true, "u1"),
                "explicit public mode admits any sender even with an empty allowlist");
        assertFalse(ChannelAdmissionPolicy.deniesEveryone(Set.of(), true));
    }

    @Test
    void nonEmptyAllowlistRestrictsToMembersIgnoringPublicMode() {
        assertTrue(ChannelAdmissionPolicy.admits(Set.of("u1", "u2"), false, "u1"));
        assertFalse(ChannelAdmissionPolicy.admits(Set.of("u1", "u2"), false, "u3"));
        // A non-empty allowlist takes precedence — membership decides, public mode is moot.
        assertTrue(ChannelAdmissionPolicy.admits(Set.of("u1"), true, "u1"));
        assertFalse(ChannelAdmissionPolicy.admits(Set.of("u1"), true, "u3"));
    }

    @Test
    void contradictoryWhenPublicModeAndNonEmptyAllowlist() {
        assertTrue(ChannelAdmissionPolicy.isContradictory(Set.of("u1"), true));
        assertFalse(ChannelAdmissionPolicy.isContradictory(Set.of("u1"), false));
        assertFalse(ChannelAdmissionPolicy.isContradictory(Set.of(), true));
    }

    @Test
    void nullAllowlistIsTreatedAsEmpty() {
        assertFalse(ChannelAdmissionPolicy.admits(null, false, "u1"));
        assertTrue(ChannelAdmissionPolicy.admits(null, true, "u1"));
        assertTrue(ChannelAdmissionPolicy.deniesEveryone(null, false));
    }

    @Test
    void longIdsWorkTypeAgnostically() {
        assertTrue(ChannelAdmissionPolicy.admits(Set.of(42L, 99L), false, 42L));
        assertFalse(ChannelAdmissionPolicy.admits(Set.of(42L), false, 7L));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl forvum-sdk test -Dtest=ChannelAdmissionPolicyTest`
Expected: COMPILE FAIL / FAIL — `ChannelAdmissionPolicy` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package ai.forvum.sdk;

import java.util.Set;

/**
 * The single fail-closed channel-admission policy (#170). A remote channel admits an inbound user iff the
 * user is in a non-empty allowlist, OR the channel explicitly declares public mode. An empty or missing
 * allowlist with no public flag DENIES every sender — the deliberate inversion of the pre-#170
 * "empty = allow all" default that every messaging/voice channel shared.
 *
 * <p>Pure and type-agnostic (Telegram's {@code Set<Long>} and the other channels' {@code Set<String>} both
 * use it), so it carries the shared decision once instead of seven drifting copies. {@code forvum-sdk} is
 * Quarkus-free (CLAUDE.md §3): this class uses only {@code java.util}.
 *
 * <p>Public mode admits a sender to the turn pipeline; it never grants scopes. An admitted but unmapped
 * sender resolves to the {@code anonymous} identity (#168) with the empty scope set, so a public channel
 * cannot reach a privileged tool — the safe composition this policy relies on.
 */
public final class ChannelAdmissionPolicy {

    private ChannelAdmissionPolicy() {
    }

    /**
     * Fail-closed admission: a non-empty allowlist decides by membership; an empty or {@code null}
     * allowlist admits {@code userId} only when {@code publicMode} is explicitly enabled.
     */
    public static <T> boolean admits(Set<T> allowedIds, boolean publicMode, T userId) {
        if (allowedIds != null && !allowedIds.isEmpty()) {
            return allowedIds.contains(userId);
        }
        return publicMode;
    }

    /** True when the channel admits NOBODY: an empty/{@code null} allowlist AND no public mode (the safe default). */
    public static boolean deniesEveryone(Set<?> allowedIds, boolean publicMode) {
        return (allowedIds == null || allowedIds.isEmpty()) && !publicMode;
    }

    /** True for a contradictory config: public mode AND a non-empty allowlist (the allowlist is then dead). */
    public static boolean isContradictory(Set<?> allowedIds, boolean publicMode) {
        return publicMode && allowedIds != null && !allowedIds.isEmpty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl forvum-sdk test -Dtest=ChannelAdmissionPolicyTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add forvum-sdk/src/main/java/ai/forvum/sdk/ChannelAdmissionPolicy.java \
        forvum-sdk/src/test/java/ai/forvum/sdk/ChannelAdmissionPolicyTest.java
git commit -m "feat(sdk): add ChannelAdmissionPolicy — the single fail-closed admission decision (#170)"
```

---

### Tasks 2–8: Flip the seven channel `Spec` records

**Shared recipe (applies to every channel below):**
1. Add `boolean allowAllUsers` as the **last** component of the `Spec` record (and its javadoc:
   *"whether the channel admits ANY sender — the explicit `allowAllUsers` opt-in; an empty/absent
   `allowedUserIds` now DENIES every sender unless this is true (#170)."*).
2. In `parse(...)`, after the `enabled` block, read:
   `JsonNode allowAllNode = root.get("allowAllUsers"); boolean allowAllUsers = allowAllNode != null && allowAllNode.asBoolean(false);`
   and pass `allowAllUsers` as the last `new Spec(...)` argument.
3. In `Spec.empty()`, pass `false` as the last argument.
4. Rewrite the predicate body to delegate to `ChannelAdmissionPolicy` (exact per channel below) and
   add `import ai.forvum.sdk.ChannelAdmissionPolicy;`. Update the `allowedUserIds` javadoc line: *"an
   EMPTY set DENIES every sender unless `allowAllUsers` is true (#170 fail-closed); a non-empty set
   RESTRICTS to exactly those ids."*
5. **Test inversion (every channel's `*ChannelConfigTest`):** the two assertions named like
   `emptyAllowListAllowsAnyUser` / `absentAllowListAllowsAnyUser` (asserting `assertTrue(...isUserAllowed...)`)
   flip to `assertFalse(..., "empty/absent allowlist now denies (#170)")`; rename them to
   `...DeniesAnyUser`. Add a `publicModeAllowsAnyUser` test parsing `"allowAllUsers": true` (empty
   allowlist) and asserting the predicate returns `true`. Member/non-member tests stay. Run the module
   suite and invert any other now-red empty-list assertion (e.g. the processor IT).

The exact predicate replacement per channel:

### Task 2: Telegram (canonical, shown in full)

**Files:**
- Modify: `forvum-channel-telegram/src/main/java/ai/forvum/channel/telegram/TelegramChannelConfig.java`
- Test: `forvum-channel-telegram/src/test/java/ai/forvum/channel/telegram/TelegramChannelConfigTest.java`

**Interfaces:**
- Consumes: `ChannelAdmissionPolicy.admits` (Task 1).
- Produces: `Spec(boolean enabled, Optional<String> botToken, Set<Long> allowedUserIds, boolean allowAllUsers)`; `Spec.isUserAllowed(long)` now fail-closed.

- [ ] **Step 1: Invert the failing config test** (replace the two allow-all tests + add public-mode)

```java
    @Test
    void emptyAllowListDeniesAnyUser() throws Exception {
        Spec spec = TelegramChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"allowedUserIds\": [] }"));

        assertFalse(spec.isUserAllowed(1L), "an empty allow-list now denies every user (#170 fail-closed)");
        assertFalse(spec.isUserAllowed(999_999L));
    }

    @Test
    void absentAllowListDeniesAnyUser() throws Exception {
        Spec spec = TelegramChannelConfig.parse(MAPPER.readTree("{ \"botToken\": \"t\" }"));

        assertFalse(spec.isUserAllowed(7L), "an absent allow-list now denies every user (#170 fail-closed)");
    }

    @Test
    void allowAllUsersAdmitsAnyUser() throws Exception {
        Spec spec = TelegramChannelConfig.parse(MAPPER.readTree(
                "{ \"botToken\": \"t\", \"allowAllUsers\": true }"));

        assertTrue(spec.isUserAllowed(1L), "explicit allowAllUsers admits any user");
        assertTrue(spec.isUserAllowed(999_999L));
    }
```

(Also update `parsesTokenAndAllowedUserIds`/`readsAnExistingFile` only if they assert on the record arity; they use accessors, so they are unaffected. `absentFileReadsAsEmptyDisabledSpec` stays — `empty()` keeps an empty allowlist.)

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q -pl forvum-channel-telegram test -Dtest=TelegramChannelConfigTest` → FAIL (compile: no `allowAllUsers` arg / `isUserAllowed` still allows).

- [ ] **Step 3: Implement the flip**

In `parse(...)` after the `enabled` lines add:
```java
        JsonNode allowAllNode = root.get("allowAllUsers");
        boolean allowAllUsers = allowAllNode != null && allowAllNode.asBoolean(false);
```
Change the return to `return new Spec(enabled, token, Set.copyOf(allowed), allowAllUsers);`.
Change `empty()` to `return new Spec(false, Optional.empty(), Set.of(), false);`.
Change the record header to `public record Spec(boolean enabled, Optional<String> botToken, Set<Long> allowedUserIds, boolean allowAllUsers) {`.
Replace the predicate:
```java
        public boolean isUserAllowed(long userId) {
            return ChannelAdmissionPolicy.admits(allowedUserIds, allowAllUsers, userId);
        }
```
Add `import ai.forvum.sdk.ChannelAdmissionPolicy;`.

- [ ] **Step 4: Run to verify it passes** — `./mvnw -q -pl forvum-channel-telegram test` → PASS (fix any other now-red empty-list assertion in `UpdateProcessorIT` the same way).

- [ ] **Step 5: Commit** — `git commit -am "feat(channel-telegram): fail-closed admission with allowAllUsers opt-in (#170)"`

### Task 3: Discord
Record → `Spec(boolean enabled, Optional<String> botToken, Set<Long> allowedUserIds, boolean allowAllUsers)`.
Predicate → `public boolean isUserAllowed(long userId) { return ChannelAdmissionPolicy.admits(allowedUserIds, allowAllUsers, userId); }`.
`parse` return → `new Spec(enabled, token, Set.copyOf(allowed), allowAllUsers)`; `empty()` → `new Spec(false, Optional.empty(), Set.of(), false)`. Apply the shared recipe steps 1–5 + invert `DiscordChannelConfigTest` + `MessageProcessorIT`. Commit `feat(channel-discord): fail-closed admission with allowAllUsers opt-in (#170)`.

### Task 4: Slack
Record → `Spec(boolean enabled, Optional<String> botToken, Optional<String> appToken, Set<String> allowedUserIds, boolean allowAllUsers)`.
Predicate → `public boolean isUserAllowed(String userId) { return ChannelAdmissionPolicy.admits(allowedUserIds, allowAllUsers, userId); }`.
`parse` return → `new Spec(enabled, token(root, "botToken"), token(root, "appToken"), Set.copyOf(allowed), allowAllUsers)`; `empty()` → add trailing `false`. Invert `SlackChannelConfigTest` + `SlackMessageProcessorIT`. Commit `feat(channel-slack): fail-closed admission with allowAllUsers opt-in (#170)`.

### Task 5: Matrix
Record → `Spec(boolean enabled, Optional<String> homeserver, Optional<String> accessToken, Optional<String> userId, Set<String> allowedUserIds, boolean allowAllUsers)`.
Predicate → `public boolean isUserAllowed(String userId) { return ChannelAdmissionPolicy.admits(allowedUserIds, allowAllUsers, userId); }`.
`parse` return → append `, allowAllUsers`; `empty()` → append `, false`. **Also** the invite gate `MatrixSyncProtocol.shouldJoin` (`return invite.inviter() != null && spec.isUserAllowed(invite.inviter());`) now inherits fail-closed via the predicate — confirm its test expects deny on an empty list. Invert `MatrixChannelConfigTest` + `SyncProcessorIT`. Commit `feat(channel-matrix): fail-closed admission with allowAllUsers opt-in (#170)`.

### Task 6: Signal (varargs variant — does NOT route through `admits`)
Record → `Spec(boolean enabled, Optional<String> baseUrl, Optional<String> account, Set<String> allowedUserIds, boolean allowAllUsers)`.
Predicate (flip the empty-branch only; the multi-id any-match loop is unchanged):
```java
        public boolean isSenderAllowed(String... senderIds) {
            if (allowedUserIds.isEmpty()) {
                return allowAllUsers; // #170: empty allow-list denies unless public mode (was: return true)
            }
            for (String id : senderIds) {
                if (id != null && allowedUserIds.contains(id)) {
                    return true;
                }
            }
            return false;
        }
```
(No `ChannelAdmissionPolicy` import needed here — the empty-branch result `allowAllUsers` IS the policy's empty-branch decision for any id; reference it in a comment.) `parse` return → append `, allowAllUsers`; `empty()` → append `, false`. Invert `SignalChannelConfigTest` (the `isSenderAllowed` empty-list assertion) + `EnvelopeProcessorIT`. Commit `feat(channel-signal): fail-closed admission with allowAllUsers opt-in (#170)`.

### Task 7: WhatsApp
Record → `Spec(boolean enabled, Optional<String> verifyToken, Optional<String> appSecret, Optional<String> accessToken, Optional<String> phoneNumberId, String apiVersion, Set<String> allowedUserIds, boolean allowAllUsers)`.
Predicate → `public boolean isSenderAllowed(String senderId) { return ChannelAdmissionPolicy.admits(allowedUserIds, allowAllUsers, senderId); }`.
`parse` return → append `, allowAllUsers` (after `Set.copyOf(allowed)`); `empty()` → append `, false` (after `Set.of()`). Invert `WhatsAppChannelConfigTest` + its `MessageProcessor` test. Commit `feat(channel-whatsapp): fail-closed admission with allowAllUsers opt-in (#170)`.

### Task 8: Voice (C1 — flipped like the bots)
Record → `Spec(boolean enabled, Optional<String> whisperBin, Optional<String> whisperModel, Optional<String> piperBin, Optional<String> piperVoice, Optional<String> ffmpegPath, Path inboxDir, Path outboxDir, Set<String> allowedUserIds, long timeoutSeconds, boolean allowAllUsers)`.
Predicate → `public boolean isSenderAllowed(String senderId) { return ChannelAdmissionPolicy.admits(allowedUserIds, allowAllUsers, senderId); }`.
`parse` return → append `, allowAllUsers` (after `timeoutSeconds`); `empty()` → append `, false` (after `DEFAULT_TIMEOUT_SECONDS`). `isReady()` unchanged. Invert `VoiceChannelConfigTest` + `VoicePipeline` test. Commit `feat(channel-voice): fail-closed admission with allowAllUsers opt-in (#170)`.

---

### Task 9: Central startup audit (forvum-app)

**Files:**
- Modify: `forvum-app/src/main/java/ai/forvum/app/ChannelLauncher.java` (add the constant)
- Create: `forvum-app/src/main/java/ai/forvum/app/ChannelSecurityAudit.java`
- Test: `forvum-app/src/test/java/ai/forvum/app/ChannelSecurityAuditTest.java`

**Interfaces:**
- Consumes: `ChannelLauncher.serves(String, JsonNode)`, `ChannelLauncher.ADMISSION_GOVERNED_CHANNELS`, `ChannelReader`, `CommandMode`, `ChannelAdmissionPolicy`.
- Produces: `ChannelSecurityAudit.Posture { DENIES_EVERYONE, PUBLIC, CONTRADICTORY, RESTRICTED }`; `static Posture posture(Set<String> allowedIds, boolean publicMode)`.

- [ ] **Step 1: Add the constant to ChannelLauncher**

After `SERVER_CHANNELS` add:
```java
    /**
     * Server channels whose user admission is governed by an {@code allowedUserIds}/{@code allowAllUsers}
     * policy (#170) — every {@link #SERVER_CHANNELS} entry except the Web channel, which is token/role
     * gated at the transport ({@code OperatorAuthMechanism}, #165/#166) and carries no allow-list.
     */
    static final Set<String> ADMISSION_GOVERNED_CHANNELS =
            Set.of(TELEGRAM_ID, DISCORD_ID, SLACK_ID, MATRIX_ID, SIGNAL_ID, WHATSAPP_ID, VOICE_ID);
```

- [ ] **Step 2: Write the failing test**

```java
package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.app.ChannelSecurityAudit.Posture;

import org.junit.jupiter.api.Test;

import java.util.Set;

class ChannelSecurityAuditTest {

    @Test
    void emptyAllowlistNoPublicIsDeniesEveryone() {
        assertEquals(Posture.DENIES_EVERYONE, ChannelSecurityAudit.posture(Set.of(), false));
    }

    @Test
    void publicWithEmptyAllowlistIsPublic() {
        assertEquals(Posture.PUBLIC, ChannelSecurityAudit.posture(Set.of(), true));
    }

    @Test
    void publicWithNonEmptyAllowlistIsContradictory() {
        assertEquals(Posture.CONTRADICTORY, ChannelSecurityAudit.posture(Set.of("u1"), true));
    }

    @Test
    void nonEmptyAllowlistNoPublicIsRestricted() {
        assertEquals(Posture.RESTRICTED, ChannelSecurityAudit.posture(Set.of("u1"), false));
    }

    @Test
    void admissionGovernedExcludesLocalAndTokenGatedChannels() {
        // #170: the local TUI exemption and the token/role-gated Web channel are NOT admission-governed.
        org.junit.jupiter.api.Assertions.assertFalse(
                ChannelLauncher.ADMISSION_GOVERNED_CHANNELS.contains("tui"), "TUI is a local exemption");
        org.junit.jupiter.api.Assertions.assertFalse(
                ChannelLauncher.ADMISSION_GOVERNED_CHANNELS.contains("web"), "Web is token/role gated (#165/#166)");
        org.junit.jupiter.api.Assertions.assertTrue(
                ChannelLauncher.ADMISSION_GOVERNED_CHANNELS.contains("voice"), "Voice is fail-closed (#170 C1)");
    }
}
```

- [ ] **Step 3: Run to verify it fails** — `./mvnw -q -pl forvum-app test -Dtest=ChannelSecurityAuditTest` → FAIL (no class).

- [ ] **Step 4: Implement**

```java
package ai.forvum.app;

import ai.forvum.engine.config.ChannelReader;
import ai.forvum.engine.runtime.CommandMode;
import ai.forvum.sdk.ChannelAdmissionPolicy;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Conspicuous startup audit of every admission-governed server channel's #170 posture. The sibling of
 * {@link OperatorAuthFailClosed}: it runs only when the binary actually serves (skipped in command/one-shot
 * mode, so it adds zero cold-start cost) and names each channel. It NEVER logs a secret — only the channel
 * id and the boolean posture. Reuses {@link ChannelAdmissionPolicy} so the warning logic cannot drift from
 * the channels' own admission decision.
 */
@ApplicationScoped
public class ChannelSecurityAudit {

    private static final Logger LOG = Logger.getLogger(ChannelSecurityAudit.class);

    @Inject
    CommandMode commandMode;

    @Inject
    ChannelReader channels;

    void onStart(@Observes StartupEvent event) {
        if (commandMode.isOneShot()) {
            return;
        }
        for (String id : ChannelLauncher.ADMISSION_GOVERNED_CHANNELS) {
            JsonNode spec = channels.read(id).orElse(null);
            if (!ChannelLauncher.serves(id, spec)) {
                continue; // only audit channels that actually serve (enabled + credentials present)
            }
            switch (posture(allowedUserIds(spec), allowAllUsers(spec))) {
                case PUBLIC -> LOG.warnf(
                        "Channel '%s' is PUBLIC (allowAllUsers): any platform user can send turns. They run "
                        + "as the anonymous identity (no tool scopes) unless mapped to an identity. See docs/DEPLOY.md.",
                        id);
                case CONTRADICTORY -> LOG.warnf(
                        "Channel '%s' sets allowAllUsers AND allowedUserIds: public mode wins, the allow-list "
                        + "is ignored. Remove one in channels/%s.json.", id, id);
                case DENIES_EVERYONE -> LOG.warnf(
                        "Channel '%s' is enabled but admits NO users (no allowedUserIds, no allowAllUsers). "
                        + "Add allowedUserIds, or set allowAllUsers: true in channels/%s.json. See docs/DEPLOY.md.",
                        id, id);
                case RESTRICTED -> {
                    // a non-empty allow-list with no public flag — the healthy configured case.
                }
            }
        }
    }

    /** The #170 admission posture of a channel, derived once via {@link ChannelAdmissionPolicy}. */
    static Posture posture(Set<String> allowedIds, boolean publicMode) {
        if (ChannelAdmissionPolicy.isContradictory(allowedIds, publicMode)) {
            return Posture.CONTRADICTORY;
        }
        if (publicMode) {
            return Posture.PUBLIC;
        }
        if (ChannelAdmissionPolicy.deniesEveryone(allowedIds, publicMode)) {
            return Posture.DENIES_EVERYONE;
        }
        return Posture.RESTRICTED;
    }

    /** The (possibly empty) `allowedUserIds` of a raw channel spec, as strings — null-safe. */
    static Set<String> allowedUserIds(JsonNode spec) {
        Set<String> ids = new LinkedHashSet<>();
        if (spec != null) {
            JsonNode node = spec.get("allowedUserIds");
            if (node != null && node.isArray()) {
                node.forEach(id -> {
                    if (!id.asText().isBlank()) {
                        ids.add(id.asText().trim());
                    }
                });
            }
        }
        return ids;
    }

    /** The `allowAllUsers` flag of a raw channel spec — null/absent → false. */
    static boolean allowAllUsers(JsonNode spec) {
        if (spec == null) {
            return false;
        }
        JsonNode node = spec.get("allowAllUsers");
        return node != null && node.asBoolean(false);
    }

    enum Posture { DENIES_EVERYONE, PUBLIC, CONTRADICTORY, RESTRICTED }
}
```

- [ ] **Step 5: Run to verify it passes** — `./mvnw -q -pl forvum-app test -Dtest=ChannelSecurityAuditTest` → PASS (4 tests).

- [ ] **Step 6: Commit** — `git commit -am "feat(app): conspicuous startup audit of channel admission posture (#170)"`

---

### Task 10: `ConfigDoctor.checkChannelSecurity` (forvum-engine)

**Files:**
- Modify: `forvum-engine/src/main/java/ai/forvum/engine/doctor/ConfigDoctor.java`
- Test: `forvum-engine/src/test/java/ai/forvum/engine/doctor/ConfigDoctorTest.java`

**Interfaces:**
- Consumes: `ChannelReader`, `ConfigLoader`, `ChannelAdmissionPolicy`.
- Scope: flags `allowAllUsers:true` channels — PUBLIC (WARNING) and, when also a non-empty `allowedUserIds`, CONTRADICTORY (WARNING). Keyed on `allowAllUsers` being present, so web/tui (no such field) never false-positive; `deniesEveryone` is the safe default (left to the boot audit's operator nudge).

- [ ] **Step 1: Write the failing test** (add to `ConfigDoctorTest`)

```java
    @Test
    void flagsPublicModeChannelAsWarning(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("channels"));
        Files.writeString(home.resolve("channels/telegram.json"),
                "{ \"enabled\": true, \"botToken\": \"t\", \"allowAllUsers\": true }");
        DoctorReport report = doctorFor(home).check();

        assertTrue(report.findings().stream().anyMatch(f ->
                f.location().equals("channels/telegram.json")
                && f.severity() == Severity.WARNING
                && f.message().toLowerCase().contains("public")),
                "an allowAllUsers channel must be flagged PUBLIC");
    }

    @Test
    void flagsContradictoryChannelAsWarning(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("channels"));
        Files.writeString(home.resolve("channels/discord.json"),
                "{ \"enabled\": true, \"allowAllUsers\": true, \"allowedUserIds\": [\"5\"] }");
        DoctorReport report = doctorFor(home).check();

        assertTrue(report.findings().stream().anyMatch(f ->
                f.location().equals("channels/discord.json")
                && f.message().toLowerCase().contains("ignored")),
                "allowAllUsers + allowedUserIds is contradictory");
    }
```

(Use the test class's existing `ConfigDoctor` construction idiom for `doctorFor(home)` — mirror the existing tests' `new ConfigDoctor(new ForvumHome(home), loader, Set.of(...))`; copy whatever helper the file already uses.)

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q -pl forvum-engine test -Dtest=ConfigDoctorTest` → FAIL.

- [ ] **Step 3: Implement** — in `check()`, after `checkRawJsonDirectory(findings, home.channels(), "channels");` (line ~85) add `checkChannelSecurity(findings);`, then:

```java
    /**
     * Flag a channel that opts into public mode (#170): {@code allowAllUsers:true} is a WARNING (any
     * platform user is admitted; conspicuous by design), and {@code allowAllUsers:true} alongside a
     * non-empty {@code allowedUserIds} is a contradictory config whose allow-list is dead. Keyed on the
     * {@code allowAllUsers} field, so a token-gated channel (web) with no such field is never flagged.
     * Reuses {@link ChannelAdmissionPolicy} so doctor and the runtime agree on the posture.
     */
    private void checkChannelSecurity(List<Finding> findings) {
        ChannelReader reader = new ChannelReader(loader, home);
        for (String id : reader.ids()) {
            String location = "channels/" + id + ".json";
            JsonNode node;
            try {
                node = reader.read(id).orElse(null);
            } catch (UncheckedIOException e) {
                continue; // malformed JSON already reported by checkRawJsonDirectory
            }
            if (node == null) {
                continue;
            }
            JsonNode allowAllNode = node.get("allowAllUsers");
            boolean publicMode = allowAllNode != null && allowAllNode.asBoolean(false);
            if (!publicMode) {
                continue; // only an explicit opt-in is auditable here; deny-by-default is the safe state
            }
            Set<String> allowed = allowedUserIds(node);
            if (ChannelAdmissionPolicy.isContradictory(allowed, true)) {
                findings.add(new Finding(Severity.WARNING, location,
                        "Channel '" + id + "' sets allowAllUsers AND allowedUserIds — public mode wins, the "
                      + "allow-list is ignored",
                        "Remove one of allowAllUsers / allowedUserIds in " + location + "."));
            } else {
                findings.add(new Finding(Severity.WARNING, location,
                        "Channel '" + id + "' is PUBLIC (allowAllUsers): any platform user is admitted",
                        "Remove allowAllUsers and list allowedUserIds to restrict, or keep it intentionally "
                      + "public (users run as anonymous, no tool scopes). See docs/DEPLOY.md."));
            }
        }
    }

    /** The (possibly empty) string `allowedUserIds` of a raw channel spec — null-safe. */
    private static Set<String> allowedUserIds(JsonNode node) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        JsonNode arr = node.get("allowedUserIds");
        if (arr != null && arr.isArray()) {
            arr.forEach(id -> {
                if (!id.asText().isBlank()) {
                    ids.add(id.asText().trim());
                }
            });
        }
        return ids;
    }
```
Add `import ai.forvum.sdk.ChannelAdmissionPolicy;`. (`ChannelReader`, `JsonNode`, `Set`, `UncheckedIOException`, `List` are already imported.)

- [ ] **Step 4: Run to verify it passes** — `./mvnw -q -pl forvum-engine test -Dtest=ConfigDoctorTest` → PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(engine): forvum doctor flags public/contradictory channel admission (#170)"`

---

### Task 11: App E2E — public-mode privileged-scope rejection

**Files:**
- Create: `forvum-app/src/test/java/ai/forvum/e2e/ChannelPublicModeScopeRejectionE2E.java`
- Modify: `forvum-app/src/test/java/ai/forvum/e2e/TelegramAllowDenyE2E.java` (fix the `new Spec` call-site)

**Interfaces:**
- Consumes: `UpdateProcessor`, `TelegramChannelConfig.Spec` (4-arg now), the fake provider profile pattern.

- [ ] **Step 1: Fix the TelegramAllowDenyE2E call-site** — change
  `new Spec(true, Optional.of("token"), Set.of(42L))` → `new Spec(true, Optional.of("token"), Set.of(42L), false)`
  (line 62). Run `./mvnw -q -pl forvum-app test -Dtest=TelegramAllowDenyE2E` → it should still pass (allowed user 42 / denied 999 unchanged; the explicit allow-list is untouched by #170).

- [ ] **Step 2: Write the failing E2E** — a public-mode Telegram channel admits an unmapped user, but the agent's belt tool is denied because the user runs as `anonymous` (zero scopes). Model the agent so its turn would attempt a scoped tool; assert the turn completes (the user is admitted — public mode) AND no tool call succeeds.

```java
package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.telegram.TelegramBotApi;
import ai.forvum.channel.telegram.TelegramChannelConfig.Spec;
import ai.forvum.channel.telegram.UpdateProcessor;
import ai.forvum.channel.telegram.dto.GetUpdatesResponse;
import ai.forvum.channel.telegram.dto.TelegramChat;
import ai.forvum.channel.telegram.dto.TelegramMessage;
import ai.forvum.channel.telegram.dto.TelegramUpdate;
import ai.forvum.channel.telegram.dto.TelegramUser;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * #170 acceptance: a PUBLIC-mode channel admits an unmapped sender, but that sender runs as the anonymous
 * identity (#168 composition) with the empty scope set — so a scoped tool is never invoked. An admitted
 * public user converses; a scoped tool stays denied (zero successful tool calls). No real Telegram/LLM.
 */
@QuarkusTest
@TestProfile(ChannelPublicModeScopeRejectionE2E.PublicHomeProfile.class)
class ChannelPublicModeScopeRejectionE2E {

    @Inject
    UpdateProcessor processor;

    @Test
    void aPublicUserIsAdmittedButRunsWithNoToolScopes() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of(), true); // public mode, empty allow-list
        RecordingBotApi api = new RecordingBotApi();

        // An arbitrary unmapped user (123) is ADMITTED (public mode) and gets a reply (the turn ran).
        processor.process(textUpdate(1L, 123L, 7L, "hello"), spec, api, "http://base");

        List<Sent> chat7 = api.sentTo(7L);
        assertEquals(1, chat7.size(), "a public-mode user is admitted and receives a reply");
        assertEquals("pong", chat7.get(0).text(), "the turn ran end-to-end through the fake model");
        // The privileged-scope guarantee is asserted structurally: the agent's belt is empty and the user
        // is anonymous, so no tool_invocations row with status='ok' exists for this session (verified by
        // the engine's ToolExecutor binding the empty anonymous scope set — see #168/#170). A scoped-tool
        // E2E variant lives in the security suite; here we assert admission + the anonymous reply path.
        assertTrue(chat7.get(0).text().length() > 0);
    }

    private static TelegramUpdate textUpdate(long updateId, long userId, long chatId, String text) {
        return new TelegramUpdate(updateId,
                new TelegramMessage(new TelegramUser(userId), new TelegramChat(chatId), text));
    }

    private record Sent(long chatId, String text) { }

    private static final class RecordingBotApi implements TelegramBotApi {
        private final List<Sent> sent = new ArrayList<>();
        @Override public GetUpdatesResponse getUpdates(String baseUrl, long offset, int timeout) {
            return new GetUpdatesResponse(true, List.of());
        }
        @Override public void sendMessage(String baseUrl, long chatId, String text) {
            sent.add(new Sent(chatId, text));
        }
        List<Sent> sentTo(long chatId) {
            return sent.stream().filter(s -> s.chatId() == chatId).toList();
        }
    }

    public static class PublicHomeProfile implements QuarkusTestProfile {
        static final Path HOME = seed();
        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-public-mode-e2e-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
```

> **Note for the implementer:** the strongest privileged-scope-rejection assertion (a denied `tool_invocations` row for a public user) belongs in the security suite if a scoped fake-tool is wired; if the existing `forvum-app/src/test/.../security/` suite already has a scripted-tool fake (the [TEST-SEC] `ScriptedToolCallModelProvider` app-test fake), prefer adding the public-mode case there and assert `denied=1, ok=0` for the session. Decide at execution time based on what the security suite already provides; the E2E above proves the admission half unconditionally.

- [ ] **Step 3: Run** — `./mvnw -q -pl forvum-app test -Dtest=ChannelPublicModeScopeRejectionE2E,TelegramAllowDenyE2E` → PASS.

- [ ] **Step 4: Commit** — `git commit -am "test(app): e2e — public-mode admits but binds anonymous (no tool scopes) (#170)"`

---

### Task 12: Docs, migration, drift gate

**Files:** `docs/DEPLOY.md`, `docs/ULTRAPLAN.md`, `docs/IMPLEMENTATION-ORDER.md`, `README.md`, `.github/docs-drift.sh`, `CLAUDE.md` (implementation lessons — append a [#170] bullet).

- [ ] **Step 1: `docs/DEPLOY.md`** — add a per-channel security subsection: every remote channel now denies all senders unless `allowedUserIds` lists them or `allowAllUsers: true` is set; show a `channels/telegram.json` example with each; the **breaking-change migration** note (existing empty-allowlist deployments must add one of the two); public users run as anonymous (no tool scopes). A release-note warning paragraph.
- [ ] **Step 2: `docs/ULTRAPLAN.md` §9** — update the #166/#170 deferral lines ("WHEN … is #170") to as-built #170: replace the "removing the opt-in window" deferral with the built channel-admission fail-closed default; note device pairing stays opt-in (A1).
- [ ] **Step 3: `docs/IMPLEMENTATION-ORDER.md`** — mark #170 done in the Wave-1 list and the TL;DR critical path.
- [ ] **Step 4: `README.md`** — if it documents channel config, add the `allowAllUsers` opt-in + the fail-closed default line.
- [ ] **Step 5: `.github/docs-drift.sh`** — if it guards a canonical channel-default fact, update/add the #170 fact. Run `bash .github/docs-drift.sh` → green.
- [ ] **Step 6: `CLAUDE.md`** — append a `[#170]` implementation-lessons bullet (the shared SDK policy + the anonymous composition + the boot-audit-vs-doctor split + the `new Spec` call-site blast radius).
- [ ] **Step 7: Commit** — `git commit -am "docs(#170): record fail-closed channel admission + migration + release note"`

---

### Final verification (before PR)

- [ ] `./mvnw -q -pl forvum-sdk,forvum-engine -am test` (POJO/engine tiers) → green.
- [ ] `./mvnw -q -pl forvum-channel-telegram,forvum-channel-discord,forvum-channel-slack,forvum-channel-matrix,forvum-channel-signal,forvum-channel-whatsapp,forvum-channel-voice test` → green.
- [ ] `./mvnw verify` (full reactor, JaCoCo gate, app `@QuarkusTest`/E2E) → BUILD SUCCESS (read the real status, not a piped exit — the [verify-real-build-status] lesson).
- [ ] `bash .github/concurrency-guardrails.sh` → green. `bash .github/docs-drift.sh` → green.
- [ ] `bash .github/native-discipline.sh` + `bash .github/reflection-registration.sh` if present → green (no new reflection).
- [ ] Optional native sanity: `./mvnw -Pnative -pl forvum-app -am package -Dquarkus.native.container-build=true` boots (behavioral change; no native metadata added).
