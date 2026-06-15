package ai.forvum.engine.config;

import ai.forvum.engine.graph.OutputSchemaException;
import ai.forvum.engine.graph.OutputSchemaValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reads skill prompt templates from {@code $FORVUM_HOME/skills/<id>.md}. A skill is operator-trusted
 * CONTENT (a named prompt template, never code; ULTRAPLAN §4.1 / DR-6b T3): an optional leading
 * {@code ---}-fenced JSON front-matter ({@code name}/{@code description}/{@code inputSchema}) followed by
 * the template. The front-matter's {@code inputSchema} (if present) is validated on read against the
 * v0.5-subset via the SAME {@link OutputSchemaValidator} the future {@code SkillInvokerTool} enforces
 * invocation arguments against — one validator, no parallel schema (the P2-9 doctor lesson). A skill with
 * no front-matter is valid (the whole file is the template). This is the "real reader" the skill-install
 * command validates through (P2-7 #32).
 */
@Singleton
public class SkillReader {

    /** A front-matter fence line — exactly {@code ---} (trailing whitespace tolerated). */
    private static final String FENCE = "---";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OutputSchemaValidator SCHEMA_VALIDATOR = new OutputSchemaValidator(MAPPER);

    private final ConfigLoader loader;
    private final Path dir;

    @Inject
    public SkillReader(ConfigLoader loader, ForvumHome home) {
        this.loader = loader;
        this.dir = home.skills();
    }

    /** The ids (file-name stems) of the {@code .md} skill files, sorted. */
    public List<String> ids() {
        return loader.listIds(dir, ".md");
    }

    /** The raw Markdown for skill {@code id} (front-matter included); empty if absent. */
    public Optional<String> read(String id) {
        return loader.readText(dir.resolve(id + ".md"));
    }

    /**
     * Read and parse skill {@code id} into a {@link SkillSpec}; empty if absent. Throws
     * {@link SkillSpecException} if the file is present but malformed.
     */
    public Optional<SkillSpec> readSpec(String id) {
        return read(id).map(markdown -> parse(id, markdown));
    }

    /**
     * Parse a skill {@code .md} into a {@link SkillSpec}: an optional leading {@code ---}-fenced JSON
     * front-matter ({@code name}/{@code description}/{@code inputSchema}) + the template. Validates the
     * front-matter is a JSON object and {@code inputSchema} (if present) is a well-formed v0.5-subset
     * schema. A file with no leading fence has no front-matter (the whole text is the template).
     * {@code label} names the source (skill id or URL) in error messages. Throws {@link SkillSpecException}
     * on a malformed skill.
     */
    public static SkillSpec parse(String label, String markdown) {
        String body = markdown == null ? "" : markdown;
        if (!body.isEmpty() && body.charAt(0) == 0xFEFF) {
            body = body.substring(1); // strip a leading UTF-8 BOM (Windows editors / git-raw exports)
        }
        String[] lines = body.split("\n", -1);

        int open = 0;
        while (open < lines.length && lines[open].isBlank()) {
            open++;
        }
        if (open >= lines.length || !lines[open].strip().equals(FENCE)) {
            // No front-matter — the whole file is the template.
            return new SkillSpec(Optional.empty(), Optional.empty(), Optional.empty(), body.strip());
        }

        int close = -1;
        for (int i = open + 1; i < lines.length; i++) {
            if (lines[i].strip().equals(FENCE)) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            throw new SkillSpecException(
                "skill '" + label + "': the front-matter opened with '---' but was never closed.");
        }

        String frontMatter = String.join("\n", Arrays.asList(lines).subList(open + 1, close));
        String template = String.join("\n", Arrays.asList(lines).subList(close + 1, lines.length)).strip();

        JsonNode root;
        try {
            root = MAPPER.readTree(frontMatter);
        } catch (Exception e) {
            throw new SkillSpecException(
                "skill '" + label + "': the front-matter is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new SkillSpecException("skill '" + label + "': the front-matter must be a JSON object.");
        }

        Optional<String> inputSchema = Optional.empty();
        JsonNode schemaNode = root.get("inputSchema");
        if (schemaNode != null && !schemaNode.isNull()) {
            String schemaJson;
            try {
                schemaJson = MAPPER.writeValueAsString(schemaNode);
            } catch (Exception e) {
                throw new SkillSpecException(
                    "skill '" + label + "': the inputSchema could not be read: " + e.getMessage(), e);
            }
            try {
                SCHEMA_VALIDATOR.validateSchema(schemaJson);
            } catch (OutputSchemaException e) {
                throw new SkillSpecException("skill '" + label + "': " + e.getMessage(), e);
            }
            inputSchema = Optional.of(schemaJson);
        }

        return new SkillSpec(nonBlank(root, "name"), nonBlank(root, "description"), inputSchema, template);
    }

    private static Optional<String> nonBlank(JsonNode root, String field) {
        // Only a present, TEXTUAL, non-blank value counts: a JSON null (NullNode.asText() == "null"),
        // a number, or a boolean is treated as absent rather than coerced to a literal string.
        JsonNode node = root.get(field);
        return node == null || !node.isTextual() || node.asText().isBlank()
                ? Optional.empty()
                : Optional.of(node.asText());
    }
}
