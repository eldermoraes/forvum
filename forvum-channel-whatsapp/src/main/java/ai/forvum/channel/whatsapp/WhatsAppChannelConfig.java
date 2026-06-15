package ai.forvum.channel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the WhatsApp channel's file-based configuration from {@code $FORVUM_HOME/channels/whatsapp.json}
 * ("fixed code, configurable behavior", CLAUDE.md §1): the operator enables WhatsApp, sets the webhook
 * {@code verifyToken} (echoed during Meta's GET verification) and {@code appSecret} (validates inbound
 * {@code X-Hub-Signature-256}), the Graph API {@code accessToken} + {@code phoneNumberId} (the reply
 * path), and restricts {@code allowedUserIds} — all by editing one file, no recompile. Mirrors
 * {@code MatrixChannelConfig}/{@code SignalChannelConfig}.
 *
 * <p>The engine's {@code ChannelReader} (which reads this same tree) lives in {@code forvum-engine},
 * which a Layer-3 channel must not depend on (the module enforcer). So this reader resolves the home the
 * same way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from the
 * {@code FORVUM_HOME} env var), falling back to {@code <user.home>/.forvum} — and reads the JSON directly
 * with Jackson. With no {@code ~/.forvum/} the file is absent and {@link #read()} returns
 * {@link Spec#empty()} (no credentials, empty allow-list), so the channel boots gracefully in the CI
 * native no-config smoke.
 *
 * <p>The config is read on demand (each {@link #read()} re-reads the file) so an operator's
 * {@code allowedUserIds} edit takes effect on the next inbound event without a restart.
 */
@ApplicationScoped
public class WhatsAppChannelConfig {

    static final String CHANNEL_ID = "whatsapp";
    static final String DEFAULT_HOME_DIR = ".forvum";
    /** Default Graph API version when {@code channels/whatsapp.json} does not pin one. */
    static final String DEFAULT_API_VERSION = "v21.0";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public WhatsAppChannelConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("channels").resolve(CHANNEL_ID + ".json");
    }

    /** Package-private constructor binding an explicit {@code channels/whatsapp.json} path — for tests. */
    WhatsAppChannelConfig(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    /**
     * Pure home resolution, mirroring {@code ForvumHome.resolve}: the configured home when present and
     * non-blank, otherwise {@code <userHome>/.forvum}. Always absolute and normalized.
     */
    static Path resolveHome(Optional<String> configuredHome, String userHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(userHome).resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
    }

    /**
     * The current spec read from {@code channels/whatsapp.json}. Returns {@link Spec#empty()} if the file
     * is absent; throws {@link UncheckedIOException} on a malformed/unreadable file (a real
     * misconfiguration the operator must see, not silently swallowed).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read WhatsApp channel config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code channels/whatsapp.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull()) {
            return Spec.empty();
        }
        JsonNode enabledNode = root.get("enabled");
        boolean enabled = enabledNode == null || enabledNode.asBoolean(true);

        Set<String> allowed = new LinkedHashSet<>();
        JsonNode allowedNode = root.get("allowedUserIds");
        if (allowedNode != null && allowedNode.isArray()) {
            for (JsonNode id : allowedNode) {
                String value = id.asText().trim();
                if (!value.isEmpty()) {
                    allowed.add(value);
                }
            }
        }
        String apiVersion = nonBlank(root, "apiVersion").orElse(DEFAULT_API_VERSION);
        return new Spec(enabled, nonBlank(root, "verifyToken"), nonBlank(root, "appSecret"),
                nonBlank(root, "accessToken"), nonBlank(root, "phoneNumberId"), apiVersion,
                Set.copyOf(allowed));
    }

    private static Optional<String> nonBlank(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.asText().isBlank()
                ? Optional.empty()
                : Optional.of(node.asText());
    }

    /**
     * The WhatsApp channel's resolved configuration.
     *
     * @param enabled        whether the channel is enabled (enabled unless {@code "enabled": false}); an
     *                       absent file is treated as disabled by {@link Spec#empty()}.
     * @param verifyToken    the operator-chosen token Meta echoes during the webhook GET verification
     *                       handshake (matched in {@link WhatsAppWebhook}); absent → the channel does not
     *                       serve (warn + no-op).
     * @param appSecret      the Meta app secret that signs inbound POSTs ({@code X-Hub-Signature-256});
     *                       used to validate every event before processing. Absent → does not serve.
     * @param accessToken    the Graph API bearer token for sending replies. Absent → does not serve.
     * @param phoneNumberId  the WhatsApp Business phone-number id the reply send is addressed to (the
     *                       {@code {phone-number-id}/messages} path segment). Absent → does not serve.
     * @param apiVersion     the Graph API version segment (default {@value #DEFAULT_API_VERSION}).
     * @param allowedUserIds the WhatsApp sender ids ({@code wa_id} phone numbers, as Meta delivers them)
     *                       permitted to drive a turn; an EMPTY set means "allow any sender" (single-user
     *                       convenience), a non-empty set RESTRICTS to exactly those ids.
     */
    public record Spec(boolean enabled, Optional<String> verifyToken, Optional<String> appSecret,
                       Optional<String> accessToken, Optional<String> phoneNumberId, String apiVersion,
                       Set<String> allowedUserIds) {

        static Spec empty() {
            return new Spec(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    DEFAULT_API_VERSION, Set.of());
        }

        /**
         * Whether {@code senderId} (a WhatsApp {@code wa_id}) may use the assistant: any sender when
         * {@code allowedUserIds} is empty, otherwise only a sender in the set. Compared exactly as Meta
         * delivers the id. The friendly refusal is the caller's concern.
         */
        public boolean isSenderAllowed(String senderId) {
            return allowedUserIds.isEmpty() || (senderId != null && allowedUserIds.contains(senderId));
        }
    }
}
