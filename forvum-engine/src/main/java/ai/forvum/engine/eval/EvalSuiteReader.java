package ai.forvum.engine.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds a raw {@code eval/<name>.json} suite file to a typed {@link EvalSuite} (P3-10 #58), hand-walking
 * the {@link JsonNode} (no Jackson reflective databind, so the records carry no
 * {@code @RegisterForReflection} — the {@code CronSpecReader} pattern). A missing/blank required field or
 * an invalid value fails with text naming {@code eval/<name>.json} so the operator can fix the file.
 *
 * <p>This is the SHARED scenario format proposed for {@code forvum qa} (#43): one file per suite, a
 * top-level {@code agent}/{@code floor}/optional {@code judge}/{@code match}, and a {@code scenarios}
 * array of {@code id}/{@code prompt}/{@code expect}/optional {@code match}. The {@code match} defaults to
 * {@code contains} (suite-level override, then per-scenario override).
 */
public final class EvalSuiteReader {

    private static final MatchMode DEFAULT_MATCH = MatchMode.CONTAINS;

    /**
     * Parse {@code spec} (the raw JSON of {@code eval/<name>.json}) into a suite named {@code name}.
     *
     * @throws IllegalStateException if the JSON is missing a required field or carries an invalid value
     */
    public EvalSuite parse(String name, JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw new IllegalStateException(
                    "Eval suite '" + name + "' must be a JSON object. Check eval/" + name + ".json.");
        }
        String agentId = required(spec, "agent", name);
        double floor = floor(spec, name);
        String judge = optionalText(spec, "judge");
        MatchMode suiteMatch = matchOrDefault(spec.get("match"), DEFAULT_MATCH, name, "suite");
        List<EvalScenario> scenarios = scenarios(name, spec.get("scenarios"), suiteMatch);
        return wrap(name, () -> new EvalSuite(name, agentId, floor, judge, scenarios));
    }

    private static List<EvalScenario> scenarios(String name, JsonNode node, MatchMode suiteMatch) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalStateException(
                    "Eval suite '" + name + "' must declare a non-empty 'scenarios' array. "
                  + "Check eval/" + name + ".json.");
        }
        List<EvalScenario> scenarios = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw new IllegalStateException(
                        "Eval suite '" + name + "' has a 'scenarios' entry that is not an object. "
                      + "Check eval/" + name + ".json.");
            }
            String id = required(element, "id", name);
            String prompt = required(element, "prompt", name);
            String expect = required(element, "expect", name);
            MatchMode match = matchOrDefault(element.get("match"), suiteMatch, name, "scenario '" + id + "'");
            scenarios.add(wrap(name, () -> new EvalScenario(id, prompt, expect, match)));
        }
        return scenarios;
    }

    private static double floor(JsonNode spec, String name) {
        JsonNode node = spec.get("floor");
        if (node == null || node.isNull() || !node.isNumber()) {
            throw new IllegalStateException(
                    "Eval suite '" + name + "' is missing a numeric 'floor' (the minimum CAPR pass-rate, "
                  + "0.0..1.0). Check eval/" + name + ".json.");
        }
        return node.asDouble();
    }

    private static MatchMode matchOrDefault(JsonNode node, MatchMode fallback, String name, String where) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            return MatchMode.fromWire(node.asText());
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Eval suite '" + name + "' " + where + " has an invalid 'match': " + e.getMessage()
                  + " Check eval/" + name + ".json.");
        }
    }

    private static String optionalText(JsonNode spec, String field) {
        JsonNode node = spec.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private static String required(JsonNode spec, String field, String name) {
        JsonNode node = spec.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(
                    "Eval suite '" + name + "' is missing the required '" + field + "' field. "
                  + "Check eval/" + name + ".json.");
        }
        return node.asText();
    }

    /** Re-throw a record canonical-constructor failure with the suite-file hint. */
    private static <T> T wrap(String name, java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage() + " Check eval/" + name + ".json.");
        }
    }
}
