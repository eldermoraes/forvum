package ai.forvum.engine.cron;

/**
 * One delivered cron output (P2-CRON-DELIVERY): the reply produced by a single cron fire, plus the
 * resolved {@link Delivery} directive that routed it. It is an internal in-process payload (never
 * JSON-serialized over the wire), so it carries no {@code @RegisterForReflection} — mirroring
 * {@code GraphTurnRequest}.
 *
 * @param cronId   the cron id that produced this output
 * @param agentId  the agent the cron turn ran as
 * @param reply    the agent's final reply for this fire
 * @param delivery the directive that selected this sink ({@code last} or {@code explicit-to})
 */
public record CronDelivery(String cronId, String agentId, String reply, Delivery delivery) {
}
