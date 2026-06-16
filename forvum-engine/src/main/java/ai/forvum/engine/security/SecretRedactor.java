package ai.forvum.engine.security;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, IO-free, secrets-only redaction over a candidate egress (P2-OUTPUTGUARD, DR-6a §9.2). It masks
 * well-known credential shapes whose prefixes do NOT occur in ordinary prose — provider API keys, OAuth
 * bearer tokens, bot tokens, and PEM private-key blocks — so the default-on guard stays conservative (no
 * PII heuristics, no false positives on words like "API"/"token"/"sky"). Each match is masked to its
 * scheme prefix plus {@code ***}, preserving enough to read the message while removing the secret.
 *
 * <p>Stateless and reflection-free (a value {@link Result} record built/returned, never serialized): the
 * {@link SecretRedactionGuard} CDI bean delegates here, and the rules are unit-tested directly.
 */
public final class SecretRedactor {

    /** The redacted text and the number of secret spans masked. */
    public record Result(String content, int redactions) {}

    private record Rule(Pattern pattern, Function<Matcher, String> mask) {}

    /**
     * Conservative rule set. Each pattern requires a long opaque body after a distinctive prefix so a
     * short prose word never trips it. Ordered most-specific-first; rules are applied in sequence.
     */
    private static final List<Rule> RULES = List.of(
        // PEM private-key block (whole block → placeholder).
        new Rule(Pattern.compile(
                "-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z0-9 ]+ )?PRIVATE KEY-----"),
            m -> "[redacted-private-key]"),
        // OpenAI / Anthropic style: sk-... and sk-ant-... (also covers sk-proj-...).
        new Rule(Pattern.compile("\\b(sk-(?:ant-)?)[A-Za-z0-9_-]{12,}"),
            m -> m.group(1) + "***"),
        // Slack: xoxb-/xoxp-/xoxa-/xoxr-/xoxs-...
        new Rule(Pattern.compile("\\b(xox[baprs])-[A-Za-z0-9-]{8,}"),
            m -> m.group(1) + "-***"),
        // GitHub fine-grained PAT: github_pat_...
        new Rule(Pattern.compile("\\b(github_pat_)[A-Za-z0-9_]{20,}"),
            m -> m.group(1) + "***"),
        // GitHub classic tokens: ghp_/gho_/ghs_/ghr_/ghu_...
        new Rule(Pattern.compile("\\b(gh[posru]_)[A-Za-z0-9]{16,}"),
            m -> m.group(1) + "***"),
        // Google API key: AIza...
        new Rule(Pattern.compile("\\b(AIza)[A-Za-z0-9_-]{20,}"),
            m -> m.group(1) + "***"),
        // AWS access key id: AKIA + 16 upper/digits.
        new Rule(Pattern.compile("\\b(AKIA)[0-9A-Z]{16}\\b"),
            m -> m.group(1) + "***"),
        // Telegram bot token: <digits>:<35+ opaque>.
        new Rule(Pattern.compile("\\b(\\d{6,}:)[A-Za-z0-9_-]{30,}\\b"),
            m -> m.group(1) + "***"),
        // OAuth bearer token (an opaque value, not the word that follows "bearer" in prose).
        new Rule(Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{16,}"),
            m -> m.group(1) + " ***")
    );

    private SecretRedactor() {}

    /** Mask every known secret shape in {@code content}; a {@code null}/empty input is returned unchanged. */
    public static Result redact(String content) {
        if (content == null || content.isEmpty()) {
            return new Result(content, 0);
        }
        String text = content;
        int total = 0;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            StringBuilder sb = null;
            int count = 0;
            while (m.find()) {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                count++;
                m.appendReplacement(sb, Matcher.quoteReplacement(rule.mask().apply(m)));
            }
            if (sb != null) {
                m.appendTail(sb);
                text = sb.toString();
                total += count;
            }
        }
        return new Result(text, total);
    }
}
