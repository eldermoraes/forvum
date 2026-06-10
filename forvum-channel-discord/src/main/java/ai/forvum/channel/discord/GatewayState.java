package ai.forvum.channel.discord;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Discord gateway client's shared mutable state, kept in lock-free atomics so the heartbeat virtual
 * thread and the inbound-frame virtual threads can read/update it concurrently without a
 * {@code synchronized} block (CLAUDE.md §3.8 bans {@code synchronized} in the channel hot path).
 *
 * <ul>
 *   <li>{@code lastSequence} — the most recent {@code s} from a DISPATCH frame, echoed in every
 *       heartbeat; {@code null} (here {@link Long#MIN_VALUE} sentinel) until the first DISPATCH, so a
 *       heartbeat before any dispatch sends a JSON {@code null} {@code d} (the protocol's required
 *       behavior).</li>
 *   <li>{@code sessionId} / {@code resumeGatewayUrl} — captured from the READY dispatch, needed to RESUME
 *       a dropped session.</li>
 * </ul>
 *
 * An {@code @ApplicationScoped} CDI bean (one gateway connection per process, so one shared state) that
 * opcode-handling tests can also construct directly via {@code new} (its methods are plain).
 */
@ApplicationScoped
public class GatewayState {

    /** Sentinel meaning "no sequence seen yet" — a heartbeat then carries JSON {@code null}. */
    private static final long NO_SEQUENCE = Long.MIN_VALUE;

    private final AtomicLong lastSequence = new AtomicLong(NO_SEQUENCE);
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final AtomicReference<String> resumeGatewayUrl = new AtomicReference<>();

    /** Record the latest DISPATCH sequence number (the {@code s} field). */
    public void setLastSequence(long seq) {
        lastSequence.set(seq);
    }

    /**
     * The last sequence number to echo in a heartbeat, or empty when no DISPATCH has been seen yet
     * (the heartbeat then sends {@code "d": null}).
     */
    public Optional<Long> lastSequence() {
        long value = lastSequence.get();
        return value == NO_SEQUENCE ? Optional.empty() : Optional.of(value);
    }

    /** Capture the session id + resume URL from the READY dispatch. */
    public void onReady(String sessionId, String resumeGatewayUrl) {
        this.sessionId.set(sessionId);
        this.resumeGatewayUrl.set(resumeGatewayUrl);
    }

    public Optional<String> sessionId() {
        return Optional.ofNullable(sessionId.get());
    }

    public Optional<String> resumeGatewayUrl() {
        return Optional.ofNullable(resumeGatewayUrl.get());
    }

    /**
     * Whether a dropped session can be RESUMED (op 6): READY captured a session id + resume URL AND at
     * least one DISPATCH sequence has been seen ({@code seq} is mandatory in the RESUME payload). False
     * after {@link #reset()} (a non-resumable INVALID_SESSION), so the next connect re-IDENTIFYs on the
     * base gateway URL.
     */
    public boolean canResume() {
        return sessionId.get() != null && resumeGatewayUrl.get() != null
                && lastSequence.get() != NO_SEQUENCE;
    }

    /** Clear the session (INVALID_SESSION non-resumable): drop the seq + session so the next connect re-IDENTIFYs. */
    public void reset() {
        lastSequence.set(NO_SEQUENCE);
        sessionId.set(null);
        resumeGatewayUrl.set(null);
    }
}
