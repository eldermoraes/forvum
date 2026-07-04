package ai.forvum.engine.agent;

import ai.forvum.core.id.AgentId;

import java.util.UUID;

/**
 * Allocates a unique runtime {@link AgentId} for an ephemeral worker sub-agent (#177). The supervisor lets
 * the model <em>suggest</em> a worker label; this turns that hint into a distinct
 * {@code <sanitized-label>~<uuid8>} id so a spawn can never collide with a persistent, file-declared agent
 * (whose id is a plain {@code agents/<id>.json} stem) nor with another concurrent worker — collision-safety
 * and race-safety by construction, rather than relying on a {@code putIfAbsent} rejection the model would
 * see as an error. The {@code ~} marker keeps ephemeral ids visually distinct from persistent ones without
 * any code needing to parse it: the retirement path retires exactly the ids it allocated.
 */
public final class EphemeralAgentId {

    /** Fallback base when the model's label sanitizes to nothing (blank, or all-disallowed characters). */
    private static final String DEFAULT_BASE = "worker";

    /** Bound on the readable label prefix so an adversarial model cannot inflate the id length. */
    private static final int MAX_BASE_LENGTH = 24;

    private EphemeralAgentId() {
    }

    /** A fresh, unique ephemeral id carrying {@code labelHint} (sanitized) as a readable prefix. */
    public static AgentId forLabel(String labelHint) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new AgentId(sanitize(labelHint) + "~" + suffix);
    }

    private static String sanitize(String hint) {
        if (hint == null) {
            return DEFAULT_BASE;
        }
        String cleaned = hint.strip().replaceAll("[^A-Za-z0-9_-]", "");
        if (cleaned.length() > MAX_BASE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_BASE_LENGTH);
        }
        return cleaned.isEmpty() ? DEFAULT_BASE : cleaned;
    }
}
