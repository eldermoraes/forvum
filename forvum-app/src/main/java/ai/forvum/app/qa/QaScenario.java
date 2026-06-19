package ai.forvum.app.qa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One QA scenario in the packaged pack (P2-QA, ULTRAPLAN §7.2 item 18): drive {@code input} through the
 * given {@code channel} and assert the assistant reply satisfies {@code expect}.
 *
 * <p><strong>Scenario format (one place, kept minimal for the #58 eval integrator to align — the super-wave
 * mandates ONE shared format).</strong> A pack is a JSON object {@code { "scenarios": [ {scenario}, … ] }};
 * each scenario is:
 * <pre>{@code
 * {
 *   "id":      "<unique id>",            // required, non-blank
 *   "channel": "cli",                    // required; v0.1 runs every scenario through the CLI turn path
 *   "input":   "<the user message>",     // required
 *   "expect":  { "match": "exact|contains|regex", "value": "<expected>" }  // required
 * }
 * }</pre>
 * An optional {@code setup} object (e.g. a per-scenario agent model) is intentionally deferred — the pack
 * runs against a home the runner seeds with the deterministic {@code echo} provider, so v0.1 needs none.
 *
 * <p>{@code @RegisterForReflection}: a Layer-4 record deserialized from a classpath JSON resource by Jackson,
 * so the native image needs its reflection metadata (the resource itself is hinted under
 * {@code META-INF/native-image/}). {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps a future
 * {@code setup}/eval field from breaking the parse.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record QaScenario(String id, String channel, String input, QaExpectation expect) {
}
