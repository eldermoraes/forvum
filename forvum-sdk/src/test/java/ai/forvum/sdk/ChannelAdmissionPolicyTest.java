package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * {@link ChannelAdmissionPolicy} is the fail-closed admission decision shared by every remote channel
 * (#170): an empty/missing allowlist denies unless an explicit public mode is on; a non-empty allowlist
 * restricts by membership. Plain unit test — {@code forvum-sdk} is Quarkus-free.
 */
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

    @Test
    void nullUserIdIsDeniedAgainstANonEmptyAllowlistWithoutThrowing() {
        // The channels back the allowlist with an immutable Set.copyOf(...), whose contains(null) throws;
        // admits must guard the null id and deny it (the per-channel senderId != null guard, centralized).
        assertFalse(ChannelAdmissionPolicy.admits(Set.of("u1"), false, null),
                "a null user id is never a member of a non-empty allowlist (no NPE)");
        // With an empty allowlist the membership branch is skipped, so public mode still decides.
        assertTrue(ChannelAdmissionPolicy.admits(Set.of(), true, null));
        assertFalse(ChannelAdmissionPolicy.admits(Set.of(), false, null));
    }
}
