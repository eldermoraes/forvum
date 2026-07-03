package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.memoryquery.SemanticMemoryStore;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;

/**
 * {@code forvum memory forget} routing (#175): by-key vs {@code --all}, identity/agent scoping, and the
 * usage-error guards — driven directly against a recording {@link SemanticMemoryStore} stub (no DB, no CDI
 * boot). The stub is {@code @Vetoed} because a CDI scope is {@code @Inherited}: an un-vetoed subclass of the
 * {@code @ApplicationScoped} store would become a second ambiguous bean in the app's {@code @QuarkusTest}s.
 */
class MemoryForgetCommandTest {

    @Vetoed
    static class RecordingStore extends SemanticMemoryStore {
        String deletedKey;
        String deletedIdentity;
        String deletedAgent;
        boolean deletedAll;
        int factResult = 1;
        long allResult = 3;

        @Override
        public int deleteFact(String identityId, String agentId, String key) {
            this.deletedIdentity = identityId;
            this.deletedAgent = agentId;
            this.deletedKey = key;
            return factResult;
        }

        @Override
        public long deleteAllFacts(String identityId, String agentId) {
            this.deletedIdentity = identityId;
            this.deletedAgent = agentId;
            this.deletedAll = true;
            return allResult;
        }
    }

    private static MemoryForgetCommand command(RecordingStore store) {
        MemoryForgetCommand cmd = new MemoryForgetCommand();
        cmd.store = store;
        cmd.identityId = "u1";
        cmd.agentId = "main";
        return cmd;
    }

    @Test
    void forgetByKeyDeletesThatKeyScopedToIdentityAndAgent() {
        RecordingStore store = new RecordingStore();
        MemoryForgetCommand cmd = command(store);
        cmd.key = "user.city";

        assertEquals(0, cmd.call());
        assertEquals("user.city", store.deletedKey);
        assertEquals("u1", store.deletedIdentity);
        assertEquals("main", store.deletedAgent);
        assertFalse(store.deletedAll, "the by-key path must not clear everything");
    }

    @Test
    void forgetAMissingKeyStillExitsZero() {
        RecordingStore store = new RecordingStore();
        store.factResult = 0;
        MemoryForgetCommand cmd = command(store);
        cmd.key = "nope";

        assertEquals(0, cmd.call(), "a missing key is not an error; it reports nothing removed");
    }

    @Test
    void forgetAllDeletesEveryFactForTheIdentityAndAgent() {
        RecordingStore store = new RecordingStore();
        MemoryForgetCommand cmd = command(store);
        cmd.all = true;

        assertEquals(0, cmd.call());
        assertTrue(store.deletedAll);
        assertEquals("u1", store.deletedIdentity);
    }

    @Test
    void keyAndAllTogetherIsAUsageError() {
        MemoryForgetCommand cmd = command(new RecordingStore());
        cmd.all = true;
        cmd.key = "x";

        assertEquals(1, cmd.call(), "a key together with --all is ambiguous → exit 1");
    }

    @Test
    void neitherKeyNorAllIsAUsageError() {
        assertEquals(1, command(new RecordingStore()).call(), "no key and no --all → exit 1");
    }
}
