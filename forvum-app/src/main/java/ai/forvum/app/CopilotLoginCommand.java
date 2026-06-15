package ai.forvum.app;

import ai.forvum.provider.copilot.CopilotAuth;
import ai.forvum.provider.copilot.CopilotAuth.DeviceCode;
import ai.forvum.provider.copilot.CopilotAuthException;
import ai.forvum.provider.copilot.CopilotCredentials;
import ai.forvum.provider.copilot.JdkCopilotHttp;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * {@code forvum copilot login} (#42): the GitHub device-code OAuth flow. Prints the {@code user_code} +
 * verification URL, polls GitHub until the user authorizes, and stores the resulting long-lived GitHub token
 * owner-only via {@link CopilotCredentials} (the short-lived Copilot token is exchanged from it per turn by
 * the provider). It only resolves + writes a credential file, so (like {@code plugin}/{@code skill}/
 * {@code mcp}) it is a {@code CommandMode} one-shot needing neither the DB nor the watcher. A failure
 * (cancelled, expired, network) prints an operator message — never a token — and exits 1.
 */
@CommandLine.Command(
        name = "login",
        description = "Authenticate to GitHub Copilot via the device-code flow.")
public class CopilotLoginCommand implements Callable<Integer> {

    @Inject
    CopilotCredentials credentials;

    @Override
    public Integer call() {
        return run(new CopilotAuth(new JdkCopilotHttp()), System.out, System.err);
    }

    /** The flow, with an injectable {@link CopilotAuth} + streams so a test drives it offline. */
    int run(CopilotAuth auth, PrintStream out, PrintStream err) {
        try {
            DeviceCode device = auth.requestDeviceCode();
            out.println("To authorize Forvum's GitHub Copilot access:");
            out.println("  1. Open " + device.verificationUri() + " in your browser");
            out.println("  2. Enter the code: " + device.userCode());
            out.println("Waiting for authorization (this window stays open)...");

            long expiresAt = System.currentTimeMillis() + device.expiresInSeconds() * 1000L;
            String githubToken = auth.pollForAccessToken(
                    device.deviceCode(), device.intervalSeconds() * 1000L, expiresAt);

            credentials.storeGitHubToken(githubToken);
            out.println("GitHub Copilot login successful. Credentials stored under ~/.forvum/state/credentials/.");
            out.println("Configure an agent with a copilot:<model> ModelRef (e.g. copilot:gpt-4o) to use it.");
            return 0;
        } catch (CopilotAuthException e) {
            err.println("Copilot login failed: " + e.getMessage());
            return 1;
        }
    }
}
