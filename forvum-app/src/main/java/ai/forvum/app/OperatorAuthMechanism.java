package ai.forvum.app;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.Set;

/**
 * The custom {@link HttpAuthenticationMechanism} that authenticates an operator against the single shared
 * secret in {@link OperatorCredentialStore} (#165). A request presents the token as
 * {@code Authorization: Bearer <token>} (HTTP) or {@code ?access_token=<token>} (the WebSocket handshake,
 * where a browser cannot set a header); a match yields a {@link SecurityIdentity} carrying the
 * {@code operator} role, which the {@code quarkus.http.auth.permission} policy (in
 * {@code application.properties}) requires for {@code /q/dashboard/*} and {@code /ws/chat}.
 *
 * <p>This is the "trivial use case" the Quarkus security guide sanctions — the mechanism authenticates
 * the request itself rather than delegating to an {@code IdentityProvider}, so {@link #getCredentialTypes()}
 * is empty. The reactive {@code Uni} return types are the framework-mandated SPI shape (CLAUDE.md section
 * 3.8 allows reactive only at such a boundary); the only work done here is an in-memory constant-time
 * string compare (the token is resolved + cached off the event loop, see {@link OperatorCredentialStore}).
 *
 * <p><strong>No information leak:</strong> a missing token yields anonymous (the policy then challenges
 * with {@code 401}); a wrong token fails with {@link AuthenticationFailedException} ({@code 401}). The
 * challenge is a constant {@code Bearer} header — it never reveals whether a given approval/CAPR id
 * exists. An authenticated principal lacking the {@code operator} role is rejected with {@code 403} by the
 * policy (the standard {@code @TestSecurity}-covered path; this mechanism only ever mints operators).
 *
 * <p>This is the web authenticated-principal seam #166 (device-token pairing) and #170 (fail-open default
 * removal) reuse — a later device token resolves to a {@code SecurityIdentity} with the device's
 * {@code approvedScopes} through this same mechanism.
 */
@ApplicationScoped
public class OperatorAuthMechanism implements HttpAuthenticationMechanism {

    /** The role the dashboard/socket HTTP security policy requires. */
    static final String OPERATOR_ROLE = "operator";

    /** The custom authentication scheme name (selected per path via {@code auth-mechanism=operator}). */
    static final String SCHEME = "operator";

    private static final String BEARER_PREFIX = "Bearer ";

    @Inject
    OperatorCredentialStore credentials;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        String presented = extractToken(context);
        if (presented == null) {
            // No credential: stay anonymous so the policy can issue a 401 challenge.
            return Uni.createFrom().nullItem();
        }
        if (!credentials.matches(presented)) {
            return Uni.createFrom().failure(new AuthenticationFailedException());
        }
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(OPERATOR_ROLE))
                .addRole(OPERATOR_ROLE)
                .build();
        return Uni.createFrom().item(identity);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(
                new ChallengeData(401, "WWW-Authenticate", "Bearer realm=\"forvum-operator\""));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        // Authenticated inline (no IdentityProvider), so no credential type is registered.
        return Collections.emptySet();
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(
                new HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, SCHEME));
    }

    /** The presented token from the {@code Authorization: Bearer} header, then the WS {@code access_token} query. */
    private static String extractToken(RoutingContext context) {
        String authorization = context.request().getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = authorization.substring(BEARER_PREFIX.length()).strip();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String fromQuery = context.request().getParam("access_token");
        return (fromQuery != null && !fromQuery.isBlank()) ? fromQuery.strip() : null;
    }
}
