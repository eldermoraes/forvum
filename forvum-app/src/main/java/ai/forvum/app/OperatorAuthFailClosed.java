package ai.forvum.app;

import ai.forvum.engine.runtime.CommandMode;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Fail-closed startup guard for the operator endpoints (#165). When a server channel is enabled the
 * binary stays alive and the {@code quarkus.http.auth.permission} policy exposes the approval/CAPR
 * dashboards and the chat socket over HTTP; if no operator credential is configured, every request to
 * them is rejected with {@code 401} (the policy requires the {@code operator} role and
 * {@link OperatorAuthMechanism} can never mint one without a token). This observer turns that silent
 * "everything 401s" state into a loud, blocking startup error so an operator does not deploy a server
 * believing the dashboards work — the explicit fail-closed the audit requires.
 *
 * <p><strong>Command-mode safe ([M20]):</strong> a one-shot command ({@code --help}/{@code init}/
 * {@code ask}/{@code doctor}/…) leaves the HTTP listener unbound ({@code quarkus.http.host-enabled=false},
 * set in {@code ForvumApplication.main}), so it serves no dashboards and is skipped — never blocking a CLI
 * invocation. The interactive TUI (a foreground channel, not a server) only gets a warning: its HTTP
 * listener may still bind, but the policy already protects the routes, so a missing credential there is a
 * usability note, not a deployment vulnerability. Reading {@link OperatorCredentialStore#isConfigured()}
 * here also warms the token cache on the startup thread, off the Vert.x event loop.
 */
@ApplicationScoped
public class OperatorAuthFailClosed {

    private static final Logger LOG = Logger.getLogger(OperatorAuthFailClosed.class);

    @Inject
    CommandMode commandMode;

    @Inject
    ChannelLauncher launcher;

    @Inject
    OperatorCredentialStore credentials;

    void onStart(@Observes StartupEvent event) {
        if (commandMode.isOneShot()) {
            return;
        }
        // Reading isConfigured() also warms the token cache on the startup thread, off the event loop.
        switch (posture(launcher.shouldRunAsServer(), credentials.isConfigured())) {
            case FAIL_CLOSED -> throw new IllegalStateException(
                    "FAIL CLOSED (#165): a server channel is enabled but no operator credential is configured, "
                    + "so the approval/CAPR dashboards and chat socket would reject every request. Set "
                    + "forvum.operator.token (env FORVUM_OPERATOR_TOKEN / a Kubernetes Secret) or write "
                    + "$FORVUM_HOME/state/credentials/operator (0600), then restart. See docs/DEPLOY.md.");
            case WARN -> LOG.warn(
                    "No operator credential configured: the approval/CAPR dashboards and chat socket reject every "
                    + "request with 401. Set forvum.operator.token or $FORVUM_HOME/state/credentials/operator to use "
                    + "them. See docs/DEPLOY.md.");
            case OK -> {
                // a credential is configured — the endpoints are authenticated.
            }
        }
    }

    /** The startup posture once command mode is ruled out, factored out for a pure unit test. */
    enum Posture { FAIL_CLOSED, WARN, OK }

    /**
     * A configured credential is always {@link Posture#OK}. Without one, an exposed server channel is
     * {@link Posture#FAIL_CLOSED} (a deployed control plane with no auth) while a non-server run (the
     * interactive TUI, whose routes the policy still protects with 401) only {@link Posture#WARN}s.
     */
    static Posture posture(boolean serverChannelUp, boolean credentialConfigured) {
        if (credentialConfigured) {
            return Posture.OK;
        }
        return serverChannelUp ? Posture.FAIL_CLOSED : Posture.WARN;
    }
}
