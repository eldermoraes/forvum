package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The inverse identity index (ULTRAPLAN section 5.3): a channel's inbound handler resolves a
 * {@code (channelId, nativeUserId)} pair into the configured {@code Identity} id. {@code Identity}
 * itself only maps {@code channelId -> nativeUserId} (forward); {@link IdentityResolver} builds the
 * inverse from {@code identities/<id>.json}. An unconfigured native user is unresolved (anonymous).
 */
@QuarkusTest
@TestProfile(IdentityResolverTest.IdentityHomeProfile.class)
class IdentityResolverTest {

    @Inject
    IdentityResolver resolver;

    @Test
    void resolvesAConfiguredChannelAccountToItsIdentityId() {
        // alice is seeded with channelAccounts { telegram: "111", web: "sess-a" }.
        assertEquals(Optional.of("alice"), resolver.resolveIdentityId("telegram", "111"));
        assertEquals(Optional.of("alice"), resolver.resolveIdentityId("web", "sess-a"));
    }

    @Test
    void anUnconfiguredNativeUserIsUnresolved() {
        assertTrue(resolver.resolveIdentityId("telegram", "999").isEmpty());
        assertTrue(resolver.resolveIdentityId("nosuchchannel", "111").isEmpty());
    }

    @Test
    void rolesForReturnsTheDeclaredRoles() {
        // alice declares roles: ["reader"].
        assertEquals(List.of("reader"), resolver.rolesFor("alice"));
    }

    @Test
    void rolesForIsEmptyWhenAnIdentityDeclaresNone() {
        // carol is seeded with no 'roles' key — backward compatible (the registry applies the default).
        assertEquals(List.of(), resolver.rolesFor("carol"));
    }

    @Test
    void rolesForIsEmptyForAnUnknownIdentity() {
        assertEquals(List.of(), resolver.rolesFor("nobody"));
    }

    @Test
    void resolveEffectivePrefersTheResolvedChannelIdentityOverTheAgentFallback() {
        // alice maps (telegram, 111); the resolved identity wins even when an agent fallback is supplied.
        EffectiveIdentity effective = resolver.resolveEffective("telegram", "111", "carol");
        assertEquals("alice", effective.identityId());
        assertEquals(List.of("reader"), effective.roleNames());
    }

    @Test
    void resolveEffectiveUsesTheAgentFallbackIdentityWhenUnresolved() {
        // 999 is unmapped -> the agent fallback "carol" (a configured identity with no roles) is used.
        EffectiveIdentity effective = resolver.resolveEffective("telegram", "999", "carol");
        assertEquals("carol", effective.identityId());
        assertEquals(List.of(), effective.roleNames());
    }

    @Test
    void resolveEffectiveFallsToTheRestrictedAnonymousRoleWithoutAFallback() {
        EffectiveIdentity effective = resolver.resolveEffective("telegram", "999", null);
        assertEquals("anonymous", effective.identityId());
        // The restricted anonymous ROLE — NOT an empty list, which RoleRegistry maps to the permissive
        // default. The no-escalation invariant: the unresolved tail is the most restricted branch.
        assertEquals(List.of("anonymous"), effective.roleNames());
    }

    @Test
    void resolveEffectiveFailsClosedForAnUnknownAgentFallback() {
        // The agent names a fallback identity that has no identities/<id>.json -> fail closed, never
        // silently degrade to the permissive anonymous default.
        assertThrows(IdentityResolutionException.class,
                () -> resolver.resolveEffective("telegram", "999", "ghost"));
    }

    /** Seeds {@code identities/alice.json} (with roles) + {@code identities/carol.json} (no roles). */
    public static class IdentityHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-identity-home");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("alice.json"),
                        "{ \"displayName\": \"Alice\", "
                      + "\"channelAccounts\": { \"telegram\": \"111\", \"web\": \"sess-a\" }, "
                      + "\"roles\": [\"reader\"] }");
                Files.writeString(identities.resolve("carol.json"),
                        "{ \"displayName\": \"Carol\", \"channelAccounts\": { \"telegram\": \"222\" } }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
