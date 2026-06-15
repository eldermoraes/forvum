package ai.forvum.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads the operator's MCP server registry from {@code $FORVUM_HOME/mcp-servers/<id>.json} ("fixed code,
 * configurable behavior"; the {@code mcp add} command writes these files). Mirrors the channel configs'
 * approach: a Layer-3 module must not depend on {@code forvum-engine}, so this resolves the home the same
 * way {@code ForvumHome} does — the {@code forvum.home} MP Config property (from {@code FORVUM_HOME}),
 * falling back to {@code <user.home>/.forvum} — and reads the JSON directly with Jackson. Read on demand,
 * so an edit takes effect on the next ToolRegistry resync without a restart. With no {@code ~/.forvum/}
 * the directory is absent and {@link #readAll()} returns an empty list (the channel/no-config-smoke
 * contract — the bridge surfaces no tools and the binary boots inert).
 */
@ApplicationScoped
public class McpServerConfig {

    static final String DIR = "mcp-servers";
    static final String DEFAULT_HOME_DIR = ".forvum";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dir;

    @Inject
    public McpServerConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        this.dir = resolveHome(configuredHome, System.getProperty("user.home")).resolve(DIR);
    }

    /** Package-private constructor binding an explicit {@code mcp-servers/} dir — for tests. */
    McpServerConfig(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    static Path resolveHome(Optional<String> configuredHome, String userHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(userHome).resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
    }

    /**
     * Every well-formed server spec under {@code mcp-servers/} (sorted by id). A malformed/unreadable file
     * is SKIPPED (it cannot be surfaced anyway) rather than failing the whole registry — the bad file is
     * the caller's to log. Returns empty if the directory is absent.
     */
    public List<McpServerSpec> readAll() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<McpServerSpec> specs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> parseFile(p).ifPresent(specs::add));
        } catch (IOException e) {
            return List.copyOf(specs);
        }
        return specs;
    }

    private Optional<McpServerSpec> parseFile(Path file) {
        String name = file.getFileName().toString();
        String id = name.substring(0, name.length() - ".json".length());
        try {
            return Optional.of(parse(id, mapper.readTree(Files.readString(file))));
        } catch (Exception e) {
            return Optional.empty(); // a malformed mcp-servers/<id>.json is skipped, not fatal
        }
    }

    /** Parse one server spec. Package-private for tests. */
    static McpServerSpec parse(String id, JsonNode root) {
        JsonNode enabledNode = root.get("enabled");
        boolean enabled = enabledNode == null || enabledNode.asBoolean(true);
        String transport = text(root, "transport", "http");
        String url = text(root, "url", null);
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headersNode = root.get("headers");
        if (headersNode != null && headersNode.isObject()) {
            headersNode.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        }
        return new McpServerSpec(id, enabled, transport, url, Map.copyOf(headers));
    }

    private static String text(JsonNode root, String field, String fallback) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? fallback : node.asText();
    }
}
