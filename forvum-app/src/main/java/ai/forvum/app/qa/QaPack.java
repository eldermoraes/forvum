package ai.forvum.app.qa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The root of a QA scenario pack: a list of {@link QaScenario}. Deserialized from the packaged
 * {@code qa/scenarios.json} classpath resource (or an operator-supplied override file).
 *
 * <p>{@code @RegisterForReflection}: Jackson reads it from the JSON pack in the native image.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record QaPack(List<QaScenario> scenarios) {
}
