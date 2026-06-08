package ai.forvum.engine.cron;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Binds a raw {@code crons/<id>.json} (delivered by the M4 {@code CronReader}) to a typed
 * {@link CronSpec} (M19, mirrors {@code AgentSpecReader}). The four core fields are required; a missing
 * or blank value, or an unparseable {@code primary} {@link ModelRef}, fails with text naming
 * {@code crons/<id>.json} so the operator can fix the file.
 *
 * <p>The optional {@code delivery} block (P2-CRON-DELIVERY, ULTRAPLAN section 7.2 item 22) is validated
 * at parse so an invalid/ambiguous spec is REJECTED here — {@code CronScheduler} then disables the bad
 * cron and {@code forvum doctor} surfaces it. An absent {@code delivery} defaults to {@link Delivery#NONE}
 * (M19 behavior preserved). For {@code mode: explicit-to} the {@code target} must name a channel in
 * {@code knownChannels}; an unknown/missing target is rejected. The {@link Delivery} canonical constructor
 * rejects the mode↔target ambiguity (a target with mode {@code none}/{@code last}).
 */
public final class CronSpecReader {

    public CronSpec parse(String id, JsonNode spec, Set<String> knownChannels) {
        String cron = required(spec, "cron", id);
        AgentId agentId = new AgentId(required(spec, "agentId", id));
        ModelRef primaryModel = ModelRef.parse(required(spec, "primary", id));
        String prompt = required(spec, "prompt", id);
        Delivery delivery = parseDelivery(id, spec.get("delivery"), knownChannels);
        return new CronSpec(id, cron, agentId, primaryModel, prompt, delivery);
    }

    private static Delivery parseDelivery(String id, JsonNode node, Set<String> knownChannels) {
        if (node == null || node.isNull()) {
            return Delivery.NONE; // no delivery block → drop the reply (M19 default)
        }
        if (!node.isObject()) {
            throw new IllegalStateException(
                    "Cron '" + id + "' field 'delivery' must be an object "
                  + "{ \"mode\": ..., \"target\": ... }. Check crons/" + id + ".json.");
        }
        JsonNode modeNode = node.get("mode");
        if (modeNode == null || modeNode.isNull() || modeNode.asText().isBlank()) {
            throw new IllegalStateException(
                    "Cron '" + id + "' delivery is missing the required 'mode' field "
                  + "(none|last|explicit-to). Check crons/" + id + ".json.");
        }
        DeliveryMode mode = wrap(id, () -> DeliveryMode.fromWire(modeNode.asText()));

        JsonNode targetNode = node.get("target");
        String target = targetNode == null || targetNode.isNull() || targetNode.asText().isBlank()
                ? null
                : targetNode.asText();

        // The Delivery canonical constructor enforces the mode↔target coupling (ambiguity check).
        Delivery delivery = wrap(id, () -> new Delivery(mode, target));

        // Cross-check the explicit-to target against the configured channels (the known set is held here,
        // not in the Layer-0-style record). Reject an unknown target so the cron is disabled at parse.
        if (delivery.mode() == DeliveryMode.EXPLICIT_TO && !knownChannels.contains(delivery.target())) {
            throw new IllegalStateException(
                    "Cron '" + id + "' delivery target '" + delivery.target()
                  + "' is not a known channel. Known channels: " + new java.util.TreeSet<>(knownChannels)
                  + ". Add channels/" + delivery.target() + ".json, or fix the target in crons/" + id + ".json.");
        }
        return delivery;
    }

    /** Re-throw a {@link Delivery}/{@link DeliveryMode} validation failure with the cron id + file hint. */
    private static <T> T wrap(String id, java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Cron '" + id + "' has an invalid delivery: " + e.getMessage()
                  + " Check crons/" + id + ".json.");
        }
    }

    private static String required(JsonNode spec, String field, String id) {
        JsonNode node = spec.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(
                    "Cron '" + id + "' is missing the required '" + field + "' field. "
                  + "Check crons/" + id + ".json.");
        }
        return node.asText();
    }
}
