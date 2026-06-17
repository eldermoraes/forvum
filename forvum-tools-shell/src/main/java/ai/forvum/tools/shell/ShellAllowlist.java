package ai.forvum.tools.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the shell tool's allowlist from {@code $FORVUM_HOME/tools/shell.json} on demand per invocation
 * ("fixed code, configurable behavior", CLAUDE.md §1; ULTRAPLAN §9.2.5). The operator declares exactly
 * which commands may run, optional per-command argv-prefix vectors, a timeout, and an optional working
 * directory — by editing one file, no recompile.
 *
 * <p>A Layer-3 module must not depend on the engine's config readers, so this resolves the home the same
 * way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from {@code
 * FORVUM_HOME}), falling back to {@code <user.home>/.forvum} — and reads the JSON directly with Jackson as
 * a {@code JsonNode} tree-walk into a plain {@link Spec} record (no reflective POJO binding → native-clean,
 * the QdrantConfig pattern).
 *
 * <p><strong>Fail-closed.</strong> With no {@code ~/.forvum/} (the CI native no-config smoke) the file is
 * absent and {@link #read()} returns {@link Spec#failClosed()}, whose empty {@code allowedCommands} makes
 * {@link Spec#validate(List)} refuse EVERY invocation — so the shell tool never launches a process unless
 * the operator has explicitly allowlisted commands.
 */
@ApplicationScoped
public class ShellAllowlist {

    static final String DEFAULT_HOME_DIR = ".forvum";
    static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public ShellAllowlist(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("tools").resolve("shell.json");
    }

    /** Package-private constructor binding an explicit {@code tools/shell.json} path — for tests. */
    ShellAllowlist(Path configFile) {
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    /** Pure home resolution, mirroring {@code ForvumHome.resolve}. Always absolute and normalized. */
    static Path resolveHome(Optional<String> configuredHome, String userHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(userHome).resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
    }

    /**
     * The current allowlist read from {@code tools/shell.json}. Returns {@link Spec#failClosed()} when the
     * file is absent (the shell tool then refuses every call); throws {@link UncheckedIOException} on a
     * malformed/unreadable file (a real misconfiguration the operator must see).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.failClosed();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read shell allowlist " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code tools/shell.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull() || !root.isObject()) {
            return Spec.failClosed();
        }

        List<String> allowedCommands = new ArrayList<>();
        JsonNode commandsNode = root.get("allowedCommands");
        if (commandsNode != null && commandsNode.isArray()) {
            commandsNode.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    allowedCommands.add(node.asText().strip());
                }
            });
        }

        Map<String, List<List<String>>> allowedArgs = new LinkedHashMap<>();
        JsonNode argsNode = root.get("allowedArgs");
        if (argsNode != null && argsNode.isObject()) {
            argsNode.fields().forEachRemaining(entry -> {
                List<List<String>> vectors = new ArrayList<>();
                if (entry.getValue().isArray()) {
                    entry.getValue().forEach(vectorNode -> {
                        if (vectorNode.isArray()) {
                            List<String> vector = new ArrayList<>();
                            vectorNode.forEach(token -> vector.add(token.asText()));
                            vectors.add(List.copyOf(vector));
                        }
                    });
                }
                allowedArgs.put(entry.getKey(), List.copyOf(vectors));
            });
        }

        JsonNode timeoutNode = root.get("timeoutSeconds");
        int timeoutSeconds = timeoutNode == null || !timeoutNode.isNumber() || timeoutNode.asInt() <= 0
                ? DEFAULT_TIMEOUT_SECONDS
                : timeoutNode.asInt();

        JsonNode workingDirNode = root.get("workingDir");
        Optional<String> workingDir = workingDirNode == null || workingDirNode.asText().isBlank()
                ? Optional.empty()
                : Optional.of(workingDirNode.asText().strip());

        return new Spec(List.copyOf(allowedCommands), Map.copyOf(allowedArgs), timeoutSeconds, workingDir);
    }

    /**
     * The shell allowlist.
     *
     * @param allowedCommands exact {@code argv[0]} values that may run (a bare command name resolved
     *                        against the scrubbed PATH, or an absolute path); empty = refuse everything.
     * @param allowedArgs     optional per-command argv-prefix vectors keyed by {@code argv[0]}: when an
     *                        entry is present, the call's argv tail must element-wise prefix-match at least
     *                        one vector; no entry for a command = any arguments are allowed.
     * @param timeoutSeconds  the process timeout in seconds (default 60).
     * @param workingDir      the optional default working directory (workspace-relative); absent = the
     *                        workspace root.
     */
    public record Spec(List<String> allowedCommands, Map<String, List<List<String>>> allowedArgs,
                       int timeoutSeconds, Optional<String> workingDir) {

        /** The fail-closed spec: no command is allowed, so every invocation is refused. */
        static Spec failClosed() {
            return new Spec(List.of(), Map.of(), DEFAULT_TIMEOUT_SECONDS, Optional.empty());
        }

        /**
         * Validate a model-supplied {@code argv} against this allowlist. The rules (ULTRAPLAN §9.2.5,
         * DP-7 exact-match, no glob/regex in v1):
         *
         * <ul>
         *   <li>argv must be non-empty.</li>
         *   <li>{@code argv[0]} must be a bare command name (no path separator — resolved against the
         *       scrubbed PATH by {@code ShellExecutor}) OR an absolute path. A relative path with a
         *       separator ({@code ./x}, {@code sub/x}) is rejected (it is neither a bare name nor
         *       absolute, and would resolve against an attacker-influenced CWD).</li>
         *   <li>{@code argv[0]} must EXACTLY match an entry in {@code allowedCommands}.</li>
         *   <li>If {@code allowedArgs} has an entry for {@code argv[0]}, the call's argument tail
         *       (argv[1..]) must element-wise prefix-match at least one of its vectors; no entry = any
         *       arguments.</li>
         * </ul>
         *
         * @throws ShellExecException with a contextual message when the call is not allowed.
         */
        public void validate(List<String> argv) {
            if (argv == null || argv.isEmpty()) {
                throw new ShellExecException("shell.exec requires a non-empty argv vector.");
            }
            String command = argv.get(0);
            if (command == null || command.isBlank()) {
                throw new ShellExecException("shell.exec argv[0] (the command) must be non-blank.");
            }
            if (isRejectedRelativePath(command)) {
                throw new ShellExecException(
                        "shell.exec command '" + command + "' is a relative path with a separator; use a "
                      + "bare command name (resolved against PATH) or an absolute path.");
            }
            if (!allowedCommands.contains(command)) {
                throw new ShellExecException(
                        "shell.exec command '" + command + "' is not in the tools/shell.json allowlist.");
            }
            List<List<String>> vectors = allowedArgs.get(command);
            if (vectors == null || vectors.isEmpty()) {
                return; // no per-command restriction: any arguments allowed
            }
            List<String> tail = argv.subList(1, argv.size());
            for (List<String> vector : vectors) {
                if (isPrefix(vector, tail)) {
                    return;
                }
            }
            throw new ShellExecException(
                    "shell.exec arguments " + tail + " do not match any allowed argv-prefix vector for '"
                  + command + "' in tools/shell.json.");
        }

        /** A relative path that contains a path separator (neither a bare name nor an absolute path). */
        private static boolean isRejectedRelativePath(String command) {
            boolean hasSeparator = command.indexOf('/') >= 0 || command.indexOf('\\') >= 0;
            boolean absolute = command.startsWith("/") || command.startsWith("\\");
            return hasSeparator && !absolute;
        }

        /** Whether {@code vector} is an element-wise prefix of {@code tail}. */
        private static boolean isPrefix(List<String> vector, List<String> tail) {
            if (vector.size() > tail.size()) {
                return false;
            }
            for (int i = 0; i < vector.size(); i++) {
                if (!vector.get(i).equals(tail.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}
