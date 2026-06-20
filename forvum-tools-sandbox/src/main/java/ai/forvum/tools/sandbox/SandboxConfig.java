package ai.forvum.tools.sandbox;

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
import java.util.List;
import java.util.Optional;

/**
 * Reads the sandbox tool's configuration from {@code $FORVUM_HOME/tools/sandbox.json} on demand per
 * invocation ("fixed code, configurable behavior", CLAUDE.md §1; ULTRAPLAN §9.2.5). The operator declares
 * the container image, optional runtime override, network mode, resource limits, the run-as user, the
 * interpreter argv, and a timeout — by editing one file, no recompile.
 *
 * <p>A Layer-3 module must not depend on the engine's config readers, so this resolves the home the same
 * way {@code ForvumHome} does — the {@code forvum.home} MP Config property (mapped from {@code
 * FORVUM_HOME}), falling back to {@code <user.home>/.forvum} — and reads the JSON directly with Jackson as
 * a {@code JsonNode} tree-walk into a plain {@link Spec} record (no reflective POJO binding → native-clean,
 * the QdrantConfig/ShellAllowlist pattern).
 *
 * <p><strong>Fail-closed.</strong> With no {@code ~/.forvum/} (the CI native no-config smoke) the file is
 * absent and {@link #read()} returns {@link Spec#failClosed()}, whose blank {@code image} makes
 * {@link Spec#requireRunnable()} refuse EVERY invocation — so the sandbox tool never launches a container
 * unless the operator has explicitly configured an image.
 */
@ApplicationScoped
public class SandboxConfig {

    static final String DEFAULT_HOME_DIR = ".forvum";
    static final int DEFAULT_TIMEOUT_SECONDS = 60;
    static final String DEFAULT_NETWORK = "none";
    static final String DEFAULT_USER = "1000:1000";
    static final String DEFAULT_CPUS = "1";
    static final String DEFAULT_MEMORY = "256m";
    static final String DEFAULT_WORKDIR = "/workspace";
    static final List<String> DEFAULT_INTERPRETER = List.of("/bin/sh", "-c");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path configFile;

    @Inject
    public SandboxConfig(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = resolveHome(configuredHome, System.getProperty("user.home"));
        this.configFile = home.resolve("tools").resolve("sandbox.json");
    }

    /** Package-private constructor binding an explicit {@code tools/sandbox.json} path — for tests. */
    SandboxConfig(Path configFile) {
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
     * The current sandbox config read from {@code tools/sandbox.json}. Returns {@link Spec#failClosed()}
     * when the file is absent (the sandbox tool then refuses every call); throws {@link UncheckedIOException}
     * on a malformed/unreadable file (a real misconfiguration the operator must see).
     */
    public Spec read() {
        if (!Files.isRegularFile(configFile)) {
            return Spec.failClosed();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sandbox config " + configFile + ".", e);
        }
        return parse(root);
    }

    /** Parse a {@code tools/sandbox.json} JSON tree into a {@link Spec}. Package-private for tests. */
    static Spec parse(JsonNode root) {
        if (root == null || root.isNull() || !root.isObject()) {
            return Spec.failClosed();
        }

        String image = text(root, "image").orElse("");
        Optional<String> runtime = text(root, "runtime");
        String network = text(root, "network").filter(v -> !v.isBlank()).orElse(DEFAULT_NETWORK);
        String user = text(root, "user").filter(v -> !v.isBlank()).orElse(DEFAULT_USER);
        String cpus = text(root, "cpus").filter(v -> !v.isBlank()).orElse(DEFAULT_CPUS);
        String memory = text(root, "memory").filter(v -> !v.isBlank()).orElse(DEFAULT_MEMORY);
        String containerWorkdir =
                text(root, "containerWorkdir").filter(v -> !v.isBlank()).orElse(DEFAULT_WORKDIR);

        List<String> interpreter = stringArray(root, "interpreter");
        if (interpreter.isEmpty()) {
            interpreter = DEFAULT_INTERPRETER;
        }

        JsonNode timeoutNode = root.get("timeoutSeconds");
        int timeoutSeconds = timeoutNode == null || !timeoutNode.isNumber() || timeoutNode.asInt() <= 0
                ? DEFAULT_TIMEOUT_SECONDS
                : timeoutNode.asInt();

        JsonNode networkAllowedNode = root.get("allowNetwork");
        boolean allowNetwork = networkAllowedNode != null && networkAllowedNode.asBoolean(false);

        return new Spec(image.strip(), runtime, network, allowNetwork, user, cpus, memory,
                containerWorkdir, List.copyOf(interpreter), timeoutSeconds);
    }

    private static Optional<String> text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            return Optional.empty();
        }
        return Optional.of(node.asText());
    }

    private static List<String> stringArray(JsonNode root, String field) {
        JsonNode node = root.get(field);
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> {
                if (element.isTextual()) {
                    out.add(element.asText());
                }
            });
        }
        return out;
    }

    /**
     * The sandbox configuration.
     *
     * @param image            the container image to run (e.g. {@code python:3.12-slim}); blank = fail-closed.
     * @param runtime          an optional explicit runtime binary ({@code podman}/{@code docker}/abs path);
     *                         empty = auto-detect (podman then docker).
     * @param network          the {@code --network} mode used when network is NOT allowed (default
     *                         {@code none} — no egress).
     * @param allowNetwork     whether the container may reach the network; default {@code false}. When
     *                         {@code false}, {@code --network=<network>} (default {@code none}) is forced.
     * @param user             the in-container {@code --user} (default {@code 1000:1000}, a non-root uid:gid).
     * @param cpus             the {@code --cpus} limit (default {@code 1}).
     * @param memory           the {@code --memory} limit (default {@code 256m}).
     * @param containerWorkdir the in-container working directory the workspace is mounted at (default
     *                         {@code /workspace}).
     * @param interpreter      the in-container argv the code snippet is appended to (default
     *                         {@code [/bin/sh, -c]}); for argv mode it is ignored.
     * @param timeoutSeconds   the wall-clock timeout in seconds (default 60).
     */
    public record Spec(String image, Optional<String> runtime, String network, boolean allowNetwork,
                       String user, String cpus, String memory, String containerWorkdir,
                       List<String> interpreter, int timeoutSeconds) {

        /** The fail-closed spec: a blank image, so every invocation is refused. */
        static Spec failClosed() {
            return new Spec("", Optional.empty(), DEFAULT_NETWORK, false, DEFAULT_USER, DEFAULT_CPUS,
                    DEFAULT_MEMORY, DEFAULT_WORKDIR, DEFAULT_INTERPRETER, DEFAULT_TIMEOUT_SECONDS);
        }

        /**
         * Assert this config can launch a container — an image must be configured. Empty = the operator
         * has not enabled the sandbox, so every call is refused (fail-closed, the ULTRAPLAN §9.2.5
         * default-deny posture).
         *
         * @throws SandboxExecException if no image is configured.
         */
        public void requireRunnable() {
            if (image == null || image.isBlank()) {
                throw new SandboxExecException(
                        "sandbox.run is fail-closed: no image is configured in tools/sandbox.json. "
                      + "Set an \"image\" (e.g. \"python:3.12-slim\") to enable the sandbox.");
            }
        }
    }
}
