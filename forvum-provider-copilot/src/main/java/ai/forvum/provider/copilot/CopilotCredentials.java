package ai.forvum.provider.copilot;

import ai.forvum.provider.copilot.CopilotAuth.CopilotToken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * Stores the long-lived GitHub token (from {@code forvum copilot login}) and caches the short-lived Copilot
 * API token exchanged from it (#42). Layer-3, so it resolves {@code $FORVUM_HOME} the same way the other
 * file-driven extensions do (the {@code forvum.home} MP Config property, else {@code <user.home>/.forvum})
 * rather than depending on the engine.
 *
 * <p>The GitHub token lives owner-only ({@code 0600}) at {@code state/credentials/github-copilot.json}. The
 * Copilot token is cached in memory with a 5-minute safety margin and re-exchanged on expiry — the exchange
 * is a network call, so it happens at most once per token lifetime (~25 min), never per turn. With no stored
 * credentials the provider fails with a clear "run `forvum copilot login`" message.
 */
@ApplicationScoped
public class CopilotCredentials {

    static final String DEFAULT_HOME_DIR = ".forvum";
    private static final long EXPIRY_MARGIN_MS = 5 * 60 * 1000L;
    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path credentialsFile;
    private final CopilotAuth auth;
    private volatile CopilotToken cachedCopilotToken;

    @Inject
    public CopilotCredentials(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        this(resolveHome(configuredHome, System.getProperty("user.home")), new CopilotAuth(new JdkCopilotHttp()));
    }

    /** Test seam: an explicit home dir + an injectable {@link CopilotAuth} (fake HTTP). */
    CopilotCredentials(Path home, CopilotAuth auth) {
        this.credentialsFile = home.resolve("state").resolve("credentials").resolve("github-copilot.json");
        this.auth = auth;
    }

    static Path resolveHome(Optional<String> configuredHome, String userHome) {
        return configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(userHome).resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
    }

    /** Whether a GitHub token is stored (i.e. {@code forvum copilot login} has run). */
    public boolean isAuthenticated() {
        return readGitHubToken().isPresent();
    }

    /** The stored long-lived GitHub token, or empty if not logged in. */
    public Optional<String> readGitHubToken() {
        if (!Files.isRegularFile(credentialsFile)) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(credentialsFile));
            JsonNode token = root.get("token");
            return token != null && token.isTextual() && !token.asText().isBlank()
                    ? Optional.of(token.asText())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Persist the GitHub token owner-only (the {@code login} command calls this). */
    public void storeGitHubToken(String githubToken) {
        ObjectNode root = mapper.createObjectNode();
        root.put("token", githubToken);
        try {
            Path dir = credentialsFile.getParent();
            if (POSIX) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
                // createDirectories only applies the attribute to dirs it CREATES; re-tighten an
                // already-present credentials dir to owner-only (the token file beneath it holds a secret).
                Files.setPosixFilePermissions(dir, DIR_PERMS);
            } else {
                Files.createDirectories(dir);
            }
            Files.writeString(credentialsFile, mapper.writeValueAsString(root));
            if (POSIX) {
                Files.setPosixFilePermissions(credentialsFile, FILE_PERMS);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write Copilot credentials to " + credentialsFile, e);
        }
        cachedCopilotToken = null; // a fresh GitHub token invalidates any cached Copilot token
    }

    /**
     * The current Copilot API token (+ base URL), exchanging + caching it on first use or after expiry. The
     * cache keeps a 5-minute margin. Throws {@link CopilotAuthException} when not logged in.
     */
    public CopilotToken currentApiToken() {
        CopilotToken cached = cachedCopilotToken;
        if (cached != null && cached.expiresAtMs() - System.currentTimeMillis() > EXPIRY_MARGIN_MS) {
            return cached;
        }
        String githubToken = readGitHubToken().orElseThrow(() -> new CopilotAuthException(
                "No GitHub Copilot credentials. Run `forvum copilot login` to authenticate."));
        CopilotToken fresh = auth.exchangeCopilotToken(githubToken);
        cachedCopilotToken = fresh;
        return fresh;
    }
}
