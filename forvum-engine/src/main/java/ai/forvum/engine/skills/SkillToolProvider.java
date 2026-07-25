package ai.forvum.engine.skills;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.engine.config.SkillReader;
import ai.forvum.engine.config.SkillSpec;
import ai.forvum.engine.graph.OutputSchemaException;
import ai.forvum.engine.graph.OutputSchemaValidator;
import ai.forvum.sdk.AbstractToolProvider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine-resident skill-invocation surface (#191, ULTRAPLAN §4.1 / §9.3.3): contributes
 * {@code skill.invoke} and {@code skill.list} to the global {@code ToolRegistry} (which discovers this
 * {@code @ApplicationScoped} bean via CDI, the ratified "engine-internal contribution path") and
 * self-dispatches each by name (M18 Option A, no reflection). Unlike a Layer-3 tool plugin this lives in
 * the engine, but it rides the SAME gate chain: the engine's {@code ToolExecutor} enforces belt membership
 * + the {@link PermissionScope#SKILL_INVOKE} RBAC scope and audits every call; this provider only
 * dispatches an already-permitted call.
 *
 * <p>{@code skill.invoke(name, args)} reads {@code skills/<name>.md} at INVOKE time (so a {@code skills/}
 * edit is visible on the next call with no cache/eviction), validates {@code args} against the skill's
 * declared {@code inputSchema} via the SAME {@link OutputSchemaValidator} (#124) BEFORE expansion, then
 * expands the template with a literal single-pass {@code {{key}}} substitution and returns it as the tool
 * result — operator-trusted content that rides a role-framed tool-result message into the invoking agent's
 * turn ([6b-DP-7]). A skill carries no scope/belt/identity, so any tool call it induces still crosses
 * belt → RBAC → approval → budget under the CALLER's identity (§9.1.c containment). {@code skill.list}
 * (no params) returns a bounded catalog so the model can discover what is installed.
 *
 * <p>A missing skill / an arg-validation failure / an over-{@link #MAX_EXPANDED_CHARS} expansion throws a
 * plain {@link IllegalArgumentException} — model-recoverable (the {@code SupervisorGraph} generic arm
 * renders it back as a tool result and the turn completes), NEVER a turn abort. {@link #tools()} returns a
 * CONSTANT spec pair (zero boot IO — the P2-13 {@code ToolRegistry.onStart} lesson), so the registry never
 * needs a skills-driven rebuild (content is read at invoke time).
 */
@ApplicationScoped
public class SkillToolProvider extends AbstractToolProvider {

    /**
     * Hard ceiling on a skill's expanded size handed to the model (4x the 8000-char
     * {@code MemoryPolicy.defaults()} compress threshold — the #176 "too large to hand to the model" band).
     * Checked AFTER expansion, so ballooning {@code args} cannot smuggle an oversized window past it. Never
     * compressed (summarizing procedural INSTRUCTIONS destroys them, unlike data — the #176 fallback does
     * not apply); over-limit is a model-recoverable error.
     */
    static final int MAX_EXPANDED_CHARS = 32_000;

    /**
     * The exact skill-id shape {@code forvum skill install} produces (its {@code deriveId}: only
     * {@code [A-Za-z0-9._-]}, leading {@code .}/{@code -} trimmed, so an installed id never starts with
     * {@code .} or {@code -}). Enforced on the MODEL-supplied {@code name} BEFORE any filesystem touch, so a
     * path-traversal attempt ({@code ../agents/main}, an absolute path, or any name with a separator) is
     * refused before {@code SkillReader.readSpec} ever resolves {@code skills/<name>.md} — a {@code /} or
     * {@code \}, a leading {@code .}, a NUL, or a blank name cannot match. The guard lives at this untrusted
     * seam rather than inside {@code SkillReader} so the reader's other callers ({@code ConfigDoctor},
     * {@code SkillInstaller}) — which pass ids sourced from real directory stems or the installer's own
     * sanitizer — keep their graceful-skip contract for a hand-dropped odd filename.
     */
    private static final Pattern SKILL_ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    private static final ToolSpec SKILL_INVOKE_SPEC = new ToolSpec(
            "skill.invoke",
            "Invoke an installed skill by id: pull the named skills/<id>.md prompt template into this turn, "
          + "substituting {{key}} placeholders from args (validated against the skill's inputSchema). Call "
          + "skill.list first to see installed skills, their descriptions, and required arguments.",
            PermissionScope.SKILL_INVOKE,
            "{\"type\":\"object\",\"properties\":{"
          + "\"name\":{\"type\":\"string\",\"description\":\"the skill id (skills/<id>.md)\"},"
          + "\"args\":{\"type\":\"object\",\"description\":\"arguments matching the skill's declared "
          + "inputSchema; omit when it declares none\"}},\"required\":[\"name\"]}");

    private static final ToolSpec SKILL_LIST_SPEC = new ToolSpec(
            "skill.list",
            "List the installed skills — one line per skill with its id, description, and (when declared) the "
          + "arguments its inputSchema requires. Use before skill.invoke to discover what is available.",
            PermissionScope.SKILL_INVOKE,
            "{}");

    @Inject
    SkillReader skills;

    @Inject
    ObjectMapper mapper;

    @Override
    public String extensionId() {
        return "engine-skills";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(SKILL_INVOKE_SPEC, SKILL_LIST_SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "skill.invoke" -> invokeSkill(arguments);
            case "skill.list" -> listSkills();
            default -> throw new IllegalArgumentException(
                    "SkillToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides skill.invoke, skill.list.");
        };
    }

    /** Read → validate args against inputSchema → expand {@code {{key}}} → size-check, returning the template. */
    private String invokeSkill(Map<String, Object> arguments) {
        String name = requireSkillName(arguments.get("name"));
        Map<String, Object> args = coerceArgs(arguments.get("args"));

        SkillSpec spec = skills.readSpec(name).orElseThrow(() -> new IllegalArgumentException(
                "No skill named '" + name + "' is installed. Installed skills: " + skills.ids()
              + ". Install one with `forvum skill install <url>`, or call skill.list."));

        spec.inputSchema().ifPresent(schema -> validateArgs(name, schema, args));

        String expanded = expand(spec.template(), args);
        if (expanded.length() > MAX_EXPANDED_CHARS) {
            throw new IllegalArgumentException(
                    "Skill '" + name + "' expanded to " + expanded.length() + " characters, over the "
                  + MAX_EXPANDED_CHARS + "-character cap. Shorten the template or the arguments.");
        }
        return expanded;
    }

    /**
     * The {@code name} argument as a validated skill id, else a clear model-recoverable error. Rejects a
     * path-traversal / absolute / separator-bearing name via {@link #SKILL_ID} BEFORE any filesystem access,
     * so a resolved path is never touched and never echoed.
     */
    private static String requireSkillName(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "skill.invoke requires a non-blank string 'name' argument (the skill id).");
        }
        String id = s.strip();
        if (!SKILL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "skill.invoke 'name' must be a skill id, not a path: it may contain only letters, digits, "
                  + "'.', '_' and '-' and cannot start with '.' or '-'. Call skill.list to see installed skills.");
        }
        return id;
    }

    /**
     * The {@code args} argument as a {@code Map}: canonical when the model passes a JSON object, lenient
     * when it stringifies the object, empty when omitted. Anything else is a clear model-recoverable error.
     */
    private Map<String, Object> coerceArgs(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> args = new LinkedHashMap<>(map.size());
            map.forEach((k, v) -> args.put(String.valueOf(k), v));
            return args;
        }
        if (value instanceof String s) {
            if (s.isBlank()) {
                return Map.of();
            }
            try {
                JsonNode node = mapper.readTree(s);
                if (node != null && node.isObject()) {
                    return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<
                            LinkedHashMap<String, Object>>() {});
                }
            } catch (JsonProcessingException ignored) {
                // fall through to the shared error below
            }
        }
        throw new IllegalArgumentException(
                "skill.invoke 'args' must be a JSON object (or an omitted argument), got: "
              + value.getClass().getSimpleName());
    }

    /** Validate {@code args} against the skill's declared {@code inputSchema} BEFORE expansion (#124/#191). */
    private void validateArgs(String name, String schemaJson, Map<String, Object> args) {
        String argsJson;
        try {
            argsJson = mapper.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "skill '" + name + "': its arguments could not be serialized for validation: "
                  + e.getOriginalMessage(), e);
        }
        try {
            new OutputSchemaValidator(mapper).validate(schemaJson, argsJson);
        } catch (OutputSchemaException e) {
            throw new IllegalArgumentException(
                    "skill '" + name + "': the arguments do not match its inputSchema: " + e.getMessage(), e);
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");

    /**
     * Literal single-pass expansion: scan the TEMPLATE once for {@code {{key}}} tokens, replacing each with
     * its arg value (a String as raw text, any other value as compact JSON, a null as the empty string).
     * Because the scan walks the template (not an accumulating buffer) and each replacement is inserted
     * literally, a {@code {{...}}} sequence appearing INSIDE an arg VALUE is never re-substituted — the
     * single-pass contract. An unmatched placeholder (a key not in {@code args}) stays verbatim (the skill
     * enforces presence via {@code required} in its {@code inputSchema}); extra args are unused. No nesting,
     * conditionals, or escape syntax. Pure + static so it is unit-testable in isolation.
     */
    static String expand(String template, Map<String, Object> args) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = args.containsKey(key) ? render(args.get(key)) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return EXPAND_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static final ObjectMapper EXPAND_MAPPER = new ObjectMapper();

    /** A bounded catalog of installed skills — one line each: id, description, and required arguments. */
    private String listSkills() {
        List<String> ids = skills.ids();
        if (ids.isEmpty()) {
            return "No skills are installed. Add skills/<id>.md under $FORVUM_HOME, or run "
                 + "`forvum skill install <url>`.";
        }
        List<String> lines = new ArrayList<>(ids.size());
        for (String id : ids) {
            lines.add(describe(id));
        }
        return String.join("\n", lines);
    }

    private String describe(String id) {
        SkillSpec spec;
        try {
            spec = skills.readSpec(id).orElse(null);
        } catch (RuntimeException e) {
            return id + ": (malformed skill — " + e.getMessage() + ")";
        }
        if (spec == null) {
            return id + ": (no description) — no declared args";
        }
        String description = spec.description().filter(d -> !d.isBlank()).orElse("(no description)");
        return id + ": " + description + " — " + argsNote(spec);
    }

    /** The arguments note for a catalog line: the required keys of the inputSchema, or that it declares none. */
    private String argsNote(SkillSpec spec) {
        if (spec.inputSchema().isEmpty()) {
            return "no declared args";
        }
        List<String> required = new ArrayList<>();
        try {
            JsonNode schema = mapper.readTree(spec.inputSchema().get());
            JsonNode requiredNode = schema.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                requiredNode.forEach(node -> required.add(node.asText()));
            }
        } catch (JsonProcessingException ignored) {
            // the schema was already well-formed at read time; treat an unexpected re-parse issue as none
        }
        return required.isEmpty() ? "args (required: none)" : "args (required: " + String.join(", ", required) + ")";
    }
}
