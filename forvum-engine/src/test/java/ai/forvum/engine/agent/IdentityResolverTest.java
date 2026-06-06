package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Seeds {@code identities/alice.json} so the resolver has a forward map to invert. */
    public static class IdentityHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-identity-home");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("alice.json"),
                        "{ \"displayName\": \"Alice\", "
                      + "\"channelAccounts\": { \"telegram\": \"111\", \"web\": \"sess-a\" } }");
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
