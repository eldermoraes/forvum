package ai.forvum.engine.messaging;

import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ForvumHome;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The #188 fail-closed destination allowlist for {@code message.send} (D4), read on demand from
 * {@code $FORVUM_HOME/tools/message-send.json}. The file is a top-level JSON object keyed by channel id,
 * each value an array of recipient strings:
 *
 * <pre>{@code
 * { "telegram": ["123456789", "987654321"] }
 * }</pre>
 *
 * <p><b>Fail-closed semantics:</b> an ABSENT file, an empty object, a channel with no key, or a channel
 * with an empty recipient array all mean "message.send is not permitted there" — the model-callable
 * outbound surface is opt-in per channel AND per recipient, never default-open. A malformed file throws
 * (the tool call errors rather than guessing); {@code forvum doctor} diagnoses the same file via
 * {@link #parse}. A BLANK target resolves to the sole allowlisted recipient when exactly ONE is
 * configured, and is refused otherwise — it never falls through to a channel-side arbitrary default
 * (the Telegram sender's own blank-target fallback stays reserved for the operator-configured cron
 * path, not the model-callable one).
 *
 * <p>Read-at-invoke (the #191 skill-surface pattern): no cache, so an operator edit is live on the next
 * call with no reload machinery. Pure blocking IO on the calling virtual thread; no reflection.
 */
@ApplicationScoped
public class MessageSendPolicy {

    /** The allowlist file, relative to {@code $FORVUM_HOME/tools/}. */
    public static final String FILE_NAME = "message-send.json";

    private final ConfigLoader loader;
    private final Path file;

    @Inject
    public MessageSendPolicy(ConfigLoader loader, ForvumHome home) {
        this(loader, home.tools().resolve(FILE_NAME));
    }

    /** Package-private constructor wiring an explicit file — for tests. */
    MessageSendPolicy(ConfigLoader loader, Path file) {
        this.loader = loader;
        this.file = file;
    }

    /**
     * The allowlisted recipients for {@code channelId} — empty means "refuse every send on this channel"
     * (absent file, absent key, or empty array alike: fail-closed).
     *
     * @throws IllegalStateException when the file exists but is structurally invalid
     */
    public Set<String> allowedRecipients(String channelId) {
        return byChannel().getOrDefault(channelId, Set.of());
    }

    /**
     * Resolve the model-supplied {@code target} against the channel's allowlist: an explicit target is
     * returned iff it is a member; a blank target resolves to the SOLE allowlisted recipient when
     * exactly one is configured. Empty means "refused".
     */
    public Optional<String> resolveTarget(String channelId, String target) {
        Set<String> allowed = allowedRecipients(channelId);
        if (target == null || target.isBlank()) {
            return allowed.size() == 1 ? Optional.of(allowed.iterator().next()) : Optional.empty();
        }
        return allowed.contains(target) ? Optional.of(target) : Optional.empty();
    }

    /** The parsed allowlist, or an empty map when the file is absent (fail-closed refuse-all). */
    private Map<String, Set<String>> byChannel() {
        Optional<JsonNode> root = loader.readJson(file);
        return root.isEmpty() ? Map.of() : parse(root.get());
    }

    /**
     * Parse (and structurally validate) an allowlist document — the shared oracle {@code forvum doctor}
     * reuses so the doctor and the runtime can never drift.
     *
     * @throws IllegalStateException on a structural violation (non-object root, non-array channel value,
     *                               non-string recipient entry)
     */
    public static Map<String, Set<String>> parse(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalStateException(
                    FILE_NAME + " must be a JSON object keyed by channel id, e.g. "
                  + "{ \"telegram\": [\"123456789\"] }.");
        }
        Map<String, Set<String>> byChannel = new LinkedHashMap<>();
        for (var field : root.properties()) {
            JsonNode value = field.getValue();
            if (!value.isArray()) {
                throw new IllegalStateException(
                        FILE_NAME + ": channel '" + field.getKey()
                      + "' must map to an ARRAY of recipient strings.");
            }
            Set<String> recipients = new LinkedHashSet<>();
            for (JsonNode entry : value) {
                if (!entry.isTextual() || entry.asText().isBlank()) {
                    throw new IllegalStateException(
                            FILE_NAME + ": channel '" + field.getKey()
                          + "' carries a non-string or blank recipient entry.");
                }
                recipients.add(entry.asText());
            }
            byChannel.put(field.getKey(), Set.copyOf(recipients));
        }
        return Map.copyOf(byChannel);
    }
}
