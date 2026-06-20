package ai.forvum.app.qa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One QA scenario in the packaged pack (P2-QA, ULTRAPLAN §7.2 item 18): drive {@code prompt} through the
 * given {@code channel} and assert the assistant reply satisfies {@code expect} under {@code match}.
 *
 * <p><strong>Shared scenario format (one format, two consumers — {@code docs/SCENARIO-FORMAT.md}).</strong>
 * The super-wave mandates ONE scenario shape for {@code forvum qa} (#43) and {@code forvum eval} (#58). A QA
 * pack is a JSON object {@code { "scenarios": [ {scenario}, … ] }}; each scenario carries the same per-case
 * fields the eval suite uses ({@code id}/{@code prompt}/{@code expect}/{@code match}), plus qa's own
 * suite-level {@code channel} concern:
 * <pre>{@code
 * {
 *   "id":      "<unique id>",                 // required, non-blank
 *   "channel": "cli",                         // required; v0.1 runs every scenario through the CLI turn path
 *   "prompt":  "<the user message>",          // required
 *   "expect":  "<expected reply property>",   // required (a substring, exact string, or regex per match)
 *   "match":   "contains|exact|regex"         // optional; defaults to contains
 * }
 * }</pre>
 * The {@code match} token is matched with the engine's shared {@code MatchMode} (the same
 * {@code contains}/{@code exact}/{@code regex} matcher the eval {@code MatcherJudge} uses), so the two
 * features never fork their match semantics. A blank/absent {@code match} defaults to {@code contains}.
 *
 * <p>{@code @RegisterForReflection}: a Layer-4 record deserialized from a classpath JSON resource by Jackson,
 * so the native image needs its reflection metadata (the resource itself is hinted under
 * {@code quarkus.native.resources.includes}). The record is all-{@code String}, so {@code match} binds
 * verbatim and is converted to a {@code MatchMode} at match time (a bad token fails the scenario, not the
 * parse). {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps a future eval field (or a per-scenario
 * {@code setup}) from breaking the parse.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record QaScenario(String id, String channel, String prompt, String expect, String match) {
}
