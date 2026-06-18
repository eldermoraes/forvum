package ai.forvum.engine.graph;

import java.util.List;

/**
 * A declared agent reflection cycle parsed from the optional {@code "cycle"} block of
 * {@code agents/<id>.json} (DR-8 DP-7, ULTRAPLAN §4.3.8 / §7.3 item 3; the #51 consumer). When present,
 * the engine compiles the cycle into a LangGraph4j {@code StateGraph} with no custom per-agent code:
 * each {@link #steps} string is the free-text instruction for one generation pass, one round is one
 * in-order traversal of the steps, and the loop terminates when a pass's reply contains
 * {@link #stopSentinel} (stripped from the final answer) or after {@link #maxRounds} rounds (best-effort
 * degrade, the M18 recursion-limit lesson).
 *
 * <p>Engine-local (the {@code CronSpec} precedent, DR-8 DP-2): {@code cycle} is a directive to the graph
 * compiler, not Layer-0 contract data, so it lives here rather than in {@code forvum-core}. Like
 * {@code CronSpec} it is parsed field-by-field from a {@code JsonNode} and used in memory, never
 * JSON-serialized, so it carries no {@code @RegisterForReflection}.
 *
 * @param steps        the ordered, non-empty list of per-pass instructions (each non-blank)
 * @param maxRounds    the round cap ({@code >= 1}); the loop returns best-effort after it
 * @param stopSentinel the early-exit marker; {@code null} (or blank, normalized to {@code null}) means
 *                     rounds-only termination
 */
public record CycleSpec(List<String> steps, int maxRounds, String stopSentinel) {

    public CycleSpec {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalStateException(
                "CycleSpec steps must be a non-empty array of instruction strings. Check the 'cycle.steps' "
              + "field in agents/<id>.json.");
        }
        for (String step : steps) {
            if (step == null || step.isBlank()) {
                throw new IllegalStateException(
                    "CycleSpec steps must not contain null or blank entries. Check the 'cycle.steps' "
                  + "array in agents/<id>.json.");
            }
        }
        steps = List.copyOf(steps);
        if (maxRounds < 1) {
            throw new IllegalStateException(
                "CycleSpec maxRounds must be >= 1. Got: " + maxRounds
              + ". Check the 'cycle.maxRounds' field in agents/<id>.json.");
        }
        if (stopSentinel != null && stopSentinel.isBlank()) {
            stopSentinel = null; // a blank sentinel is treated as absent (rounds-only termination)
        }
    }
}
