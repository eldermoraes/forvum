package ai.forvum.engine.config;

import java.nio.file.Path;

/** The outcome of a successful {@link SkillInstaller#install}: the derived skill {@code id} and the file. */
public record SkillInstallResult(String id, Path path) {
}
