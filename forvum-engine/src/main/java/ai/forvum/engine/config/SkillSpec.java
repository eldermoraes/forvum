package ai.forvum.engine.config;

import java.util.Optional;

/**
 * A parsed skill: the optional leading {@code ---}-fenced JSON front-matter
 * ({@code name}/{@code description}/{@code inputSchema}) plus the prompt {@code template}. A skill with
 * no front-matter is valid — the whole file is the template, with no declared name or input schema.
 * {@code inputSchema}, when present, is the compact JSON string of a v0.5-subset schema (the same subset
 * {@code OutputSchemaValidator} enforces) that the future {@code SkillInvokerTool} validates invocation
 * arguments against. Produced by {@link SkillReader#parse}.
 */
public record SkillSpec(Optional<String> name, Optional<String> description, Optional<String> inputSchema,
                        String template) {
}
