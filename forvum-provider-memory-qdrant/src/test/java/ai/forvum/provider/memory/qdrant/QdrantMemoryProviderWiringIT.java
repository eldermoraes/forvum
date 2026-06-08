package ai.forvum.provider.memory.qdrant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.sdk.MemoryProvider;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Verifies the Qdrant memory provider WIRES under Quarkus: ArC discovers it as a {@link MemoryProvider}
 * bean and the {@code @RestClient QdrantApi} injects (the native-relevant CDI + rest-client path). With no
 * {@code memory/qdrant.json}, {@code retrieve} returns no hits and issues no call (the inert posture the CI
 * native no-config smoke depends on). Boots Quarkus in-JVM; runs under Surefire (headless library, CLAUDE.md
 * §4 exception).
 */
@QuarkusTest
class QdrantMemoryProviderWiringIT {

    @Inject
    MemoryProvider provider;   // resolves to the single QdrantMemoryProvider bean

    @Test
    void beanIsDiscoveredWithTheExpectedExtensionId() {
        assertNotNull(provider);
        assertEquals("memory-qdrant", provider.extensionId());
    }

    @Test
    void retrieveIsInertWithNoConfig() {
        // The test JVM has no ~/.forvum/memory/qdrant.json, so the provider must no-op safely.
        List<MemoryHit> hits = provider.retrieve(
                new MemoryQuery("agent-1", "sess-1", "hello"), MemoryPolicy.defaults());
        assertTrue(hits.isEmpty(), "an unconfigured provider returns no hits");
    }
}
