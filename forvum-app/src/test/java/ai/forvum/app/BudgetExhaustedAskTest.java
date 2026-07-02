package ai.forvum.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The #169 cost-budget hard stop at the outermost surface: {@code forvum ask} against a {@code main}
 * agent whose {@code costBudget} declares {@code maxTokens: 0} (the always-exhausted boundary) must exit
 * 1 with a {@code budget_exhausted} diagnostic — WITHOUT ever contacting a provider. The agent is pinned
 * to the real {@code ollama:} provider (present in the app classpath AND the native image), but the
 * Decision-8 pre-call gate fires before any HTTP call, so the test is deterministic and offline — which
 * is exactly what lets {@link BudgetExhaustedAskNativeIT} run it against the native binary in the
 * DEFAULT native leg (no {@code @Tag("live")}, the [Risk#5] "an in-test fake provider is not in the
 * image" trap avoided by never needing the provider to answer).
 */
@QuarkusMainTest
@TestProfile(BudgetExhaustedAskTest.ZeroBudgetHomeProfile.class)
class BudgetExhaustedAskTest {

    @Test
    @Launch(value = {"ask", "hello"}, exitCode = 1)
    void askAgainstAnExhaustedBudgetFailsBeforeAnyProviderCall(LaunchResult result) {
        Assertions.assertTrue(result.getErrorOutput().contains("budget_exhausted"),
                () -> "the terminal error must carry code=budget_exhausted; stderr: "
                    + result.getErrorOutput());
        Assertions.assertTrue(result.getErrorOutput().contains("TOKEN_CAP_HIT"),
                () -> "the safe message names the exhausted cap; stderr: " + result.getErrorOutput());
    }

    /** Seeds {@code main} on the real ollama provider with an always-exhausted zero-token day budget. */
    public static class ZeroBudgetHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-zero-budget-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"ollama:qwen3:1.7b\", "
                      + "\"costBudget\": { \"maxTokens\": 0 } }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
