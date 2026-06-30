package ai.forvum.app;

import ai.forvum.engine.config.ChannelReader;
import ai.forvum.engine.runtime.CommandMode;
import ai.forvum.sdk.ChannelAdmissionPolicy;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Conspicuous startup audit of every admission-governed server channel's #170 posture. The sibling of
 * {@link OperatorAuthFailClosed}: it runs only when the binary actually serves (skipped in command/one-shot
 * mode, so it adds zero cold-start cost) and names each channel. It NEVER logs a secret — only the channel
 * id and the boolean posture. Reuses {@link ChannelAdmissionPolicy} so the warning logic cannot drift from
 * the channels' own admission decision.
 *
 * <p>It never blocks boot (unlike the {@link OperatorAuthFailClosed} fail-closed guard): the fail-closed
 * default ({@code deniesEveryone}) is the SAFE state, so an enabled-but-admits-nobody channel is a
 * helpfulness WARNING, not an error. Public mode and a contradictory allow-list are also WARNINGs — both
 * are operator-intended states that must simply be loud.
 */
@ApplicationScoped
public class ChannelSecurityAudit {

    private static final Logger LOG = Logger.getLogger(ChannelSecurityAudit.class);

    @Inject
    CommandMode commandMode;

    @Inject
    ChannelReader channels;

    void onStart(@Observes StartupEvent event) {
        if (commandMode.isOneShot()) {
            return; // a one-shot CLI command serves no channel — nothing to audit, no cold-start cost
        }
        audit(ChannelLauncher.ADMISSION_GOVERNED_CHANNELS, id -> channels.read(id).orElse(null))
                .forEach(LOG::warn);
    }

    /**
     * The #170 admission warnings for each governed channel that actually serves — pure (no logging, no
     * CDI) so it is unit-testable across every posture and the serve gate. {@code specOf} resolves a
     * channel id to its raw {@code channels/<id>.json} tree (or {@code null} when absent). A channel that
     * does not serve (disabled or missing credentials) is skipped; the healthy RESTRICTED posture emits
     * nothing. No secret is ever included — only the channel id and the boolean posture.
     */
    static List<String> audit(Set<String> governed, Function<String, JsonNode> specOf) {
        List<String> warnings = new ArrayList<>();
        for (String id : governed) {
            JsonNode spec = specOf.apply(id);
            if (!ChannelLauncher.serves(id, spec)) {
                continue; // only audit channels that actually serve (enabled + credentials present)
            }
            switch (posture(allowedUserIds(spec), allowAllUsers(spec))) {
                case PUBLIC -> warnings.add("Channel '" + id + "' is PUBLIC (allowAllUsers): any platform "
                        + "user can send turns. They run as the anonymous identity (no tool scopes) unless "
                        + "mapped to an identity. See docs/DEPLOY.md.");
                case CONTRADICTORY -> warnings.add("Channel '" + id + "' sets allowAllUsers AND "
                        + "allowedUserIds: public mode wins, the allow-list is ignored. Remove one in "
                        + "channels/" + id + ".json.");
                case DENIES_EVERYONE -> warnings.add("Channel '" + id + "' is enabled but admits NO users "
                        + "(no allowedUserIds, no allowAllUsers). Add allowedUserIds, or set allowAllUsers: "
                        + "true in channels/" + id + ".json. See docs/DEPLOY.md.");
                case RESTRICTED -> {
                    // a non-empty allow-list with no public flag — the healthy configured case, no warning.
                }
            }
        }
        return warnings;
    }

    /** The #170 admission posture of a channel, derived once via {@link ChannelAdmissionPolicy}. */
    static Posture posture(Set<String> allowedIds, boolean publicMode) {
        if (ChannelAdmissionPolicy.isContradictory(allowedIds, publicMode)) {
            return Posture.CONTRADICTORY;
        }
        if (publicMode) {
            return Posture.PUBLIC;
        }
        if (ChannelAdmissionPolicy.deniesEveryone(allowedIds, publicMode)) {
            return Posture.DENIES_EVERYONE;
        }
        return Posture.RESTRICTED;
    }

    /** The (possibly empty) {@code allowedUserIds} of a raw channel spec, as strings — null-safe. */
    static Set<String> allowedUserIds(JsonNode spec) {
        Set<String> ids = new LinkedHashSet<>();
        if (spec != null) {
            JsonNode node = spec.get("allowedUserIds");
            if (node != null && node.isArray()) {
                node.forEach(id -> {
                    if (!id.asText().isBlank()) {
                        ids.add(id.asText().trim());
                    }
                });
            }
        }
        return ids;
    }

    /** The {@code allowAllUsers} flag of a raw channel spec — null/absent → false. */
    static boolean allowAllUsers(JsonNode spec) {
        if (spec == null) {
            return false;
        }
        JsonNode node = spec.get("allowAllUsers");
        return node != null && node.asBoolean(false);
    }

    enum Posture { DENIES_EVERYONE, PUBLIC, CONTRADICTORY, RESTRICTED }
}
