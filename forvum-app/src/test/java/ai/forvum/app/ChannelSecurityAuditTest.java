package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.app.ChannelSecurityAudit.Posture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The pure #170 admission audit behind {@link ChannelSecurityAudit}: the {@link Posture} classification,
 * the per-channel {@link ChannelSecurityAudit#audit} warning pass (every posture + the serve gate), and
 * the raw-spec field extractors. Plain unit test — no Quarkus boot, so it runs under Surefire.
 */
class ChannelSecurityAuditTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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
        assertFalse(ChannelLauncher.ADMISSION_GOVERNED_CHANNELS.contains("tui"), "TUI is a local exemption");
        assertFalse(ChannelLauncher.ADMISSION_GOVERNED_CHANNELS.contains("web"),
                "Web is token/role gated (#165/#166)");
        assertTrue(ChannelLauncher.ADMISSION_GOVERNED_CHANNELS.contains("voice"),
                "Voice is fail-closed like the bots (#170 C1)");
    }

    @Test
    void auditWarnsOnlyServingChannelsAcrossEveryPosture() {
        Map<String, JsonNode> specs = Map.of(
                // serves (botToken) + PUBLIC
                "telegram", json("{\"enabled\":true,\"botToken\":\"x\",\"allowAllUsers\":true}"),
                // serves (botToken) + DENIES_EVERYONE (no allowlist, no public)
                "discord", json("{\"enabled\":true,\"botToken\":\"x\"}"),
                // serves (botToken+appToken) + CONTRADICTORY (public AND a non-empty allowlist)
                "slack", json("{\"enabled\":true,\"botToken\":\"x\",\"appToken\":\"y\","
                        + "\"allowAllUsers\":true,\"allowedUserIds\":[\"u\"]}"),
                // serves (baseUrl+account) + RESTRICTED (a non-empty allowlist, no public) -> no warning
                "signal", json("{\"enabled\":true,\"baseUrl\":\"x\",\"account\":\"y\","
                        + "\"allowedUserIds\":[\"u\"]}"),
                // enabled but missing credentials -> does NOT serve -> skipped (the serve-gate)
                "matrix", json("{\"enabled\":true,\"allowAllUsers\":true}"));
        Function<String, JsonNode> specOf = specs::get;

        List<String> warnings = ChannelSecurityAudit.audit(
                Set.of("telegram", "discord", "slack", "signal", "matrix"), specOf);

        assertEquals(3, warnings.size(), () -> "exactly the 3 serving non-RESTRICTED channels warn: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'telegram'") && w.contains("PUBLIC")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'discord'") && w.contains("admits NO users")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'slack'") && w.contains("ignored")));
        assertTrue(warnings.stream().noneMatch(w -> w.contains("'signal'")),
                "a RESTRICTED (configured allow-list) channel is healthy — no warning");
        assertTrue(warnings.stream().noneMatch(w -> w.contains("'matrix'")),
                "a non-serving channel (missing credentials) is skipped");
    }

    @Test
    void allowedUserIdsExtractsTrimmedNonBlankIdsAndIsNullSafe() {
        assertEquals(Set.of("u1", "u2"),
                ChannelSecurityAudit.allowedUserIds(json("{\"allowedUserIds\":[\"u1\",\" u2 \",\"\"]}")));
        assertTrue(ChannelSecurityAudit.allowedUserIds(json("{}")).isEmpty());
        assertTrue(ChannelSecurityAudit.allowedUserIds(null).isEmpty());
    }

    @Test
    void allowAllUsersReadsTheFlagAndIsNullSafe() {
        assertTrue(ChannelSecurityAudit.allowAllUsers(json("{\"allowAllUsers\":true}")));
        assertFalse(ChannelSecurityAudit.allowAllUsers(json("{\"allowAllUsers\":false}")));
        assertFalse(ChannelSecurityAudit.allowAllUsers(json("{}")));
        assertFalse(ChannelSecurityAudit.allowAllUsers(null));
    }
}
