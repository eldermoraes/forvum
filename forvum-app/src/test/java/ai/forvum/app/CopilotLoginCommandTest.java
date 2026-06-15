package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.provider.copilot.CopilotAuth;
import ai.forvum.provider.copilot.CopilotCredentials;
import ai.forvum.provider.copilot.CopilotHttp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

/**
 * {@code forvum copilot login} orchestration ({@link CopilotLoginCommand#run}), driven offline: a scripted
 * {@link CopilotHttp} fake supplies the device code then the access token, a recording credentials stub
 * captures the stored token (the file-write contract itself is covered by {@code CopilotCredentialsTest}).
 * Asserts the success path (codes printed, token stored, exit 0) and the cancelled path (exit 1, message,
 * no token stored). Plain unit test — no Quarkus boot, no network.
 */
class CopilotLoginCommandTest {

    /** A {@link CopilotHttp} returning queued POST responses (device code, then the token poll). */
    private static final class FakeHttp implements CopilotHttp {
        final Deque<Resp> posts = new ArrayDeque<>();

        @Override
        public Resp postForm(String url, Map<String, String> form, Map<String, String> headers) {
            return posts.poll();
        }

        @Override
        public Resp get(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("login does not exchange the Copilot token");
        }
    }

    /** A credentials stub that records the stored GitHub token instead of writing a file. */
    private static final class RecordingCreds extends CopilotCredentials {
        String stored;

        RecordingCreds() {
            super(Optional.of(System.getProperty("java.io.tmpdir") + "/forvum-copilot-login-test"));
        }

        @Override
        public void storeGitHubToken(String githubToken) {
            this.stored = githubToken;
        }
    }

    private static String deviceCodeJson() {
        return "{\"device_code\":\"DC\",\"user_code\":\"WXYZ-1234\",\"verification_uri\":"
             + "\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}";
    }

    @Test
    void successPathPrintsTheCodeStoresTheTokenAndExitsZero() {
        FakeHttp http = new FakeHttp();
        http.posts.add(new CopilotHttp.Resp(200, deviceCodeJson()));
        http.posts.add(new CopilotHttp.Resp(200, "{\"access_token\":\"gho_TOKEN\",\"token_type\":\"bearer\"}"));
        CopilotLoginCommand cmd = new CopilotLoginCommand();
        RecordingCreds creds = new RecordingCreds();
        cmd.credentials = creds;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = cmd.run(new CopilotAuth(http), ps(out), ps(new ByteArrayOutputStream()));

        assertEquals(0, code);
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("WXYZ-1234"), "prints the user code; got: " + printed);
        assertTrue(printed.contains("https://github.com/login/device"), "prints the verification URI");
        assertTrue(printed.contains("successful"), "prints a success line");
        assertEquals("gho_TOKEN", creds.stored, "the long-lived GitHub token is stored");
    }

    @Test
    void cancelledLoginExitsOneWithAMessageAndStoresNothing() {
        FakeHttp http = new FakeHttp();
        http.posts.add(new CopilotHttp.Resp(200, deviceCodeJson()));
        http.posts.add(new CopilotHttp.Resp(200, "{\"error\":\"access_denied\"}"));
        CopilotLoginCommand cmd = new CopilotLoginCommand();
        RecordingCreds creds = new RecordingCreds();
        cmd.credentials = creds;
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = cmd.run(new CopilotAuth(http), ps(new ByteArrayOutputStream()), ps(err));

        assertEquals(1, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).toLowerCase().contains("cancelled"),
                "prints a failure message; got: " + err);
        assertEquals(null, creds.stored, "no token is stored on a cancelled login");
    }

    private static PrintStream ps(ByteArrayOutputStream b) {
        return new PrintStream(b, true, StandardCharsets.UTF_8);
    }
}
