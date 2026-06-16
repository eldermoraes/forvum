package ai.forvum.sdk;

import ai.forvum.core.security.FilteringOutcome;

/**
 * SPI a plugin implements to inspect a candidate outbound egress and return its disposition (ULTRAPLAN
 * section 9.2.3, DR-6a) — the named contract behind the section 1.4 outbound-filter promise. Sealed: a
 * third party extends {@link AbstractOutputGuard}, consistent with the other provider SPIs (section 2.2).
 *
 * <p>The engine composes every configured guard <strong>fail-closed and most-restrictive-wins</strong>
 * (any {@code Blocked} dominates a {@code Redacted} dominates {@code Allowed}; redactions union); the
 * composition lives in the engine, so a guard stays single-responsibility and need not know about siblings.
 *
 * <p>{@link #filter} runs <strong>blocking on the turn's virtual thread</strong>, pre-channel-emit in
 * v0.1 (section 3.8). It MUST be pure + side-effect-free over the content and MUST NOT block on network
 * or perform IO — a guard that needs a remote classifier is out of v0.1 scope.
 */
public sealed interface OutputGuard permits AbstractOutputGuard {

    /** Inspect {@code candidate} at the {@code ctx} hook layer and return its disposition. */
    FilteringOutcome filter(OutputContext ctx, String candidate);
}
