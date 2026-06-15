package ai.forvum.provider.copilot;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.forvum.core.ModelRef;
import ai.forvum.provider.copilot.CopilotAuth.CopilotToken;

import dev.langchain4j.model.chat.ChatModel;

import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

/**
 * Proves the Copilot model BUILD path: {@code OpenAiChatModel.builder().baseUrl(...).apiKey(...)
 * .customHeaders(...).build()} constructs a model for the Copilot endpoint with no network. {@code @QuarkusTest}
 * supplies the ArC context the Quarkus-swapped OpenAI builder needs at {@code build()} (a turn always has
 * one). This is Copilot's equivalent of the app-level provider-resolve guard, which can't call
 * {@code resolve()} for Copilot (that needs a live token exchange). Runs under Surefire (headless library).
 */
@QuarkusTest
class CopilotModelBuildTest {

    @Test
    void buildsAnOpenAiCompatibleModelForTheCopilotEndpointWithoutNetwork() {
        CopilotToken token = new CopilotToken("tid=abc;proxy-ep=proxy.x.githubcopilot.com",
                System.currentTimeMillis() + 1_800_000, "https://api.x.githubcopilot.com");

        ChatModel model = CopilotModelProvider.buildModel("gpt-4o", token);

        assertNotNull(model, "the model builds lazily (no connection opened) via the Quarkus REST client");
    }

    @Test
    void resolveExchangesViaCredentialsAndCachesPerModelAndToken(@TempDir Path home) {
        // A provider whose credentials are backed by a fake HTTP exchange (no live GitHub): resolve()
        // gets the cached Copilot token, builds the model, and reuses it while the token is stable.
        long farFuture = System.currentTimeMillis() / 1000 + 3600;
        CopilotCredentials creds = new CopilotCredentials(home, new CopilotAuth(new CopilotHttp() {
            @Override
            public Resp postForm(String url, Map<String, String> form, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Resp get(String url, Map<String, String> headers) {
                return new Resp(200, "{\"token\":\"tid=z;proxy-ep=proxy.z.githubcopilot.com\","
                        + "\"expires_at\":" + farFuture + "}");
            }
        }));
        creds.storeGitHubToken("gho_TOKEN");
        CopilotModelProvider provider = new CopilotModelProvider();
        provider.credentials = creds;

        ChatModel first = provider.resolve(ModelRef.parse("copilot:gpt-4o"));
        ChatModel again = provider.resolve(ModelRef.parse("copilot:gpt-4o"));

        assertNotNull(first);
        assertSame(first, again, "a stable Copilot token reuses the cached model for the same id");
        assertNotNull(provider.resolve(ModelRef.parse("copilot:gpt-4o-mini")), "a different model resolves too");
    }

    @Test
    void resolveRebuildsTheModelWhenTheCopilotTokenRefreshes(@TempDir Path home) {
        long past = System.currentTimeMillis() / 1000 - 10; // already expired → re-exchange every call
        java.util.Deque<String> tokens = new java.util.ArrayDeque<>(java.util.List.of(
                "tid=A;proxy-ep=proxy.x.githubcopilot.com", "tid=B;proxy-ep=proxy.x.githubcopilot.com"));
        CopilotCredentials creds = new CopilotCredentials(home, new CopilotAuth(new CopilotHttp() {
            @Override
            public Resp postForm(String url, Map<String, String> form, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Resp get(String url, Map<String, String> headers) {
                return new Resp(200, "{\"token\":\"" + tokens.poll() + "\",\"expires_at\":" + past + "}");
            }
        }));
        creds.storeGitHubToken("gho_TOKEN");
        CopilotModelProvider provider = new CopilotModelProvider();
        provider.credentials = creds;

        ChatModel withTokenA = provider.resolve(ModelRef.parse("copilot:gpt-4o"));
        ChatModel withTokenB = provider.resolve(ModelRef.parse("copilot:gpt-4o"));

        org.junit.jupiter.api.Assertions.assertNotSame(withTokenA, withTokenB,
                "a refreshed Copilot token rebuilds the model (the token is baked in at build time)");
    }
}
