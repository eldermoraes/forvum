package ai.forvum.engine.cron;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Binds a raw {@code crons/<id>.json} (delivered by the M4 {@code CronReader}) to a typed
 * {@link CronSpec} (M19, mirrors {@code AgentSpecReader}). All four fields are required; a missing or
 * blank value, or an unparseable {@code primary} {@link ModelRef}, fails with text naming
 * {@code crons/<id>.json} so the operator can fix the file.
 */
public final class CronSpecReader {

    public CronSpec parse(String id, JsonNode spec) {
        String cron = required(spec, "cron", id);
        AgentId agentId = new AgentId(required(spec, "agentId", id));
        ModelRef primaryModel = ModelRef.parse(required(spec, "primary", id));
        String prompt = required(spec, "prompt", id);
        return new CronSpec(id, cron, agentId, primaryModel, prompt);
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
