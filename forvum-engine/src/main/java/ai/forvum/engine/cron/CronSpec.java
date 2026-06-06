package ai.forvum.engine.cron;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;

/**
 * A typed scheduled-job definition parsed from {@code $FORVUM_HOME/crons/<id>.json} (ULTRAPLAN
 * section 7.1 M19). Each cron fires a full agent turn for {@link #agentId} using its OWN
 * {@link #primaryModel} (distinct from the agent's persona model — maintainer decision 2026-06-06,
 * aligning with the e2e X8 {@code primary}-in-cron format), with {@link #prompt} as the turn's input.
 *
 * @param id           the cron id (the {@code .json} file-name stem)
 * @param cron         the cron expression (Quarkus/Quartz format, e.g. {@code "0 * * * * ?"})
 * @param agentId      the agent the turn runs as
 * @param primaryModel the model the cron turn uses
 * @param prompt       the user-message content the turn starts from
 */
public record CronSpec(String id, String cron, AgentId agentId, ModelRef primaryModel, String prompt) {

    public CronSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("CronSpec id must be non-blank.");
        }
        if (cron == null || cron.isBlank()) {
            throw new IllegalStateException("CronSpec '" + id + "' cron expression must be non-blank.");
        }
        if (agentId == null) {
            throw new IllegalStateException("CronSpec '" + id + "' agentId must be non-null.");
        }
        if (primaryModel == null) {
            throw new IllegalStateException("CronSpec '" + id + "' primaryModel must be non-null.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("CronSpec '" + id + "' prompt must be non-blank.");
        }
    }
}
