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
     * allowlist admits {@code userId} only when {@code publicMode} is explicitly enabled. A {@code null}
     * {@code userId} is never a member, so it is denied against a non-empty allowlist (the
     * {@code senderId != null} guard the per-channel predicates carried, preserved here — and required
     * because the channels back the allowlist with an immutable {@code Set.copyOf(...)} whose
     * {@code contains(null)} throws).
     */
    public static <T> boolean admits(Set<T> allowedIds, boolean publicMode, T userId) {
        if (allowedIds != null && !allowedIds.isEmpty()) {
            return userId != null && allowedIds.contains(userId);
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
