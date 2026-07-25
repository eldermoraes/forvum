package ai.forvum.engine.config;

/**
 * Thrown when a skill {@code .md} is malformed: its {@code ---}-fenced front-matter is unclosed, is not a
 * JSON object, or declares an {@code inputSchema} that is not a well-formed v0.5-subset schema. The
 * message names the skill (id or source URL) and the failure — the M4 reader convention. The skill
 * install command surfaces it as a non-zero exit; the engine-resident {@code SkillToolProvider}
 * ({@code skill.invoke}, #191) validates on read at invoke time, and {@code forvum doctor} surfaces it.
 */
public class SkillSpecException extends RuntimeException {

    public SkillSpecException(String message) {
        super(message);
    }

    public SkillSpecException(String message, Throwable cause) {
        super(message, cause);
    }
}
