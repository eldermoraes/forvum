package ai.forvum.engine.eval;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How a scenario's {@code expect} string is matched against the agent's reply by the deterministic,
 * offline {@link MatcherJudge} (P3-10 #58). The default offline judge needs NO live model, so a suite
 * runs as a CI quality gate without inference.
 *
 * <ul>
 *   <li>{@link #CONTAINS} — case-insensitive substring (the lenient default; good for "the answer
 *       mentions X").</li>
 *   <li>{@link #EXACT} — case-insensitive whole-string equality after trimming.</li>
 *   <li>{@link #REGEX} — the {@code expect} string is a Java regex searched anywhere in the reply.</li>
 * </ul>
 */
public enum MatchMode {

    CONTAINS {
        @Override
        boolean matches(String expect, String reply) {
            return reply.toLowerCase(Locale.ROOT).contains(expect.toLowerCase(Locale.ROOT));
        }
    },

    EXACT {
        @Override
        boolean matches(String expect, String reply) {
            return reply.strip().equalsIgnoreCase(expect.strip());
        }
    },

    REGEX {
        @Override
        boolean matches(String expect, String reply) {
            try {
                return Pattern.compile(expect).matcher(reply).find();
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException(
                        "Eval expectation '" + expect + "' is not a valid regex: " + e.getMessage(), e);
            }
        }
    };

    /** True when {@code reply} satisfies {@code expect} under this mode. Inputs are never null. */
    abstract boolean matches(String expect, String reply);

    /**
     * Parse a wire token ({@code contains}|{@code exact}|{@code regex}, case-insensitive) to a mode.
     *
     * @throws IllegalStateException if {@code token} names no mode (with the valid set in the message).
     */
    public static MatchMode fromWire(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Eval match mode must be non-blank (contains|exact|regex).");
        }
        return switch (token.strip().toLowerCase(Locale.ROOT)) {
            case "contains" -> CONTAINS;
            case "exact" -> EXACT;
            case "regex" -> REGEX;
            default -> throw new IllegalStateException(
                    "Unknown eval match mode '" + token + "' (expected contains|exact|regex).");
        };
    }
}
