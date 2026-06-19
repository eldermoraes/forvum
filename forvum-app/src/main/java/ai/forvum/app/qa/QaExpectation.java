package ai.forvum.app.qa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.regex.Pattern;

/**
 * A QA scenario's expectation: how to compare the assistant reply ({@code match}) against {@code value}.
 *
 * <ul>
 *   <li>{@code exact} — the reply equals {@code value} verbatim.</li>
 *   <li>{@code contains} — the reply contains {@code value} as a substring.</li>
 *   <li>{@code regex} — {@code value} is a Java regex that {@link Pattern#matcher} finds anywhere in the
 *       reply ({@code find()}, not full-match — anchor with {@code ^…$} for a full match).</li>
 * </ul>
 *
 * A blank/unknown {@code match} is rejected by {@link #satisfiedBy} (it throws, so a malformed pack fails
 * the scenario rather than passing vacuously — fails-by-default).
 *
 * <p>{@code @RegisterForReflection}: Jackson-deserialized from the packaged JSON pack in the native image.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record QaExpectation(String match, String value) {

    /** Whether {@code reply} satisfies this expectation; throws on a null/blank/unknown {@code match}. */
    public boolean satisfiedBy(String reply) {
        String actual = reply == null ? "" : reply;
        String expected = value == null ? "" : value;
        String mode = match == null ? "" : match.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "exact" -> actual.equals(expected);
            case "contains" -> actual.contains(expected);
            case "regex" -> Pattern.compile(expected).matcher(actual).find();
            default -> throw new IllegalArgumentException(
                    "unknown match mode '" + match + "' (expected exact|contains|regex)");
        };
    }
}
