package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * {@code TransactionIds} renders {@code <nonce>-<n>} with a strictly increasing counter and a per-boot
 * random nonce — the uniqueness contract the Matrix send endpoint dedupes on (a reused txnId silently
 * drops the reply). Pure POJO tests.
 */
class TransactionIdsTest {

    @Test
    void rendersTheNonceAndAMonotonicCounter() {
        TransactionIds ids = new TransactionIds("boot");

        assertEquals("boot-1", ids.next());
        assertEquals("boot-2", ids.next());
        assertEquals("boot-3", ids.next());
    }

    @Test
    void idsAreUniqueWithinABoot() {
        TransactionIds ids = new TransactionIds();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String id = ids.next();
            if (!seen.add(id)) {
                throw new AssertionError("duplicate txnId within a boot: " + id);
            }
        }
        assertEquals(1_000, seen.size());
    }

    @Test
    void twoBootsUseDistinctNonces() {
        // The default constructor draws a random per-boot nonce, so the first id of two instances
        // (= two boots) must differ — a bare counter would collide with the previous boot's sends.
        assertNotEquals(new TransactionIds().next(), new TransactionIds().next());
    }
}
