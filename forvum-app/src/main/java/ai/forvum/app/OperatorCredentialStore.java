package ai.forvum.app;

import ai.forvum.engine.config.ForvumHome;
import ai.forvum.sdk.FileApiKeyStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * The operator credential the Web dashboards and chat socket authenticate against (#165). A single opaque
 * shared secret: present it as an {@code Authorization: Bearer <token>} header (HTTP) or an
 * {@code ?access_token=<token>} query parameter (the WebSocket handshake, where a browser cannot set a
 * header), and {@link OperatorAuthMechanism} grants a {@code SecurityIdentity} with the {@code operator}
 * role for the {@code /q/dashboard/*} + {@code /ws/chat} HTTP security policy.
 *
 * <p><strong>Precedence (mirrors {@link FileApiKeyStore}'s config-over-file rule, [P2-10]):</strong> the
 * MicroProfile config property {@code forvum.operator.token} (env {@code FORVUM_OPERATOR_TOKEN}, a
 * Kubernetes Secret, or {@code -D}) wins; otherwise the owner-only ({@code 0600}) file
 * {@code $FORVUM_HOME/state/credentials/operator}. No token configured ⇒ {@link #isConfigured()} is
 * false, which the fail-closed startup check ({@link OperatorAuthFailClosed}) turns into a blocking error
 * when a server channel is exposed.
 *
 * <p>The effective token is resolved once and cached in memory: {@link OperatorAuthMechanism#authenticate}
 * runs on the Vert.x event loop and must not block on a file read, so the file is read at most once (a
 * benign idempotent race; the fail-closed check warms it on the startup thread before any request). A
 * changed token therefore needs a restart — documented in {@code docs/DEPLOY.md}. Comparison is
 * constant-time ({@link MessageDigest#isEqual}) so a wrong token cannot be timed byte by byte.
 */
@ApplicationScoped
public class OperatorCredentialStore {

    /** The {@code state/credentials/} filename for the operator secret (a non-provider {@link FileApiKeyStore} id). */
    static final String CREDENTIAL_ID = "operator";

    @Inject
    ForvumHome home;

    @ConfigProperty(name = "forvum.operator.token")
    Optional<String> configuredToken;

    private volatile String cachedToken;
    private volatile boolean resolved;

    /** The effective operator token, or {@code null} when none is configured (config override, then file). */
    public String token() {
        if (!resolved) {
            cachedToken = resolve();
            resolved = true;
        }
        return cachedToken;
    }

    private String resolve() {
        if (configuredToken.isPresent() && !configuredToken.get().isBlank()) {
            return configuredToken.get().strip();
        }
        return FileApiKeyStore.read(home.root(), CREDENTIAL_ID).orElse(null);
    }

    /** Whether an operator token is configured at all (the fail-closed gate keys off this). */
    public boolean isConfigured() {
        String t = token();
        return t != null && !t.isBlank();
    }

    /** Constant-time check that {@code presented} equals the configured operator token. */
    public boolean matches(String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        String t = token();
        if (t == null || t.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                t.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
