package ai.forvum.channel.matrix;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates the unique-per-send transaction ids the Matrix {@code PUT .../send/m.room.message/{txnId}}
 * endpoint dedupes on: a per-boot random nonce plus a monotonic counter ({@code <nonce>-<n>}). The nonce
 * makes ids unique ACROSS restarts (a bare counter would restart at 1 and collide with the previous
 * boot's sends, silently dropping replies); the {@link AtomicLong} makes them unique across concurrent
 * virtual threads within a boot — no {@code synchronized} (CLAUDE.md §3.8). Pure and clock-free beyond
 * the boot nonce, so uniqueness/monotonicity are unit-testable directly.
 */
final class TransactionIds {

    private final String nonce;
    private final AtomicLong counter = new AtomicLong();

    TransactionIds() {
        this(UUID.randomUUID().toString());
    }

    /** Package-private nonce override — for tests pinning the rendered shape. */
    TransactionIds(String nonce) {
        this.nonce = nonce;
    }

    /** The next transaction id: {@code <nonce>-<n>} with a strictly increasing {@code n}. */
    String next() {
        return nonce + "-" + counter.incrementAndGet();
    }
}
