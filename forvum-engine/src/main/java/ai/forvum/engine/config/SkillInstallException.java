package ai.forvum.engine.config;

/**
 * Thrown when {@link SkillInstaller#install} cannot fetch or write a skill: an invalid/unsupported URL, a
 * download failure (non-200, IO), an undeterminable skill id, or a write failure. A malformed skill
 * BODY is a {@link SkillSpecException} instead; the install command surfaces both as a non-zero exit.
 */
public class SkillInstallException extends RuntimeException {

    public SkillInstallException(String message) {
        super(message);
    }

    public SkillInstallException(String message, Throwable cause) {
        super(message, cause);
    }
}
