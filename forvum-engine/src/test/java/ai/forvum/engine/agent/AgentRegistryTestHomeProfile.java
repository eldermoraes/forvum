package ai.forvum.engine.agent;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Points {@code $FORVUM_HOME} at a throwaway temp directory seeded with two agents — {@code main}
 * (pinned to {@code ollama:qwen3:1.7b}) and {@code faker} (pinned to the in-process
 * {@link FakeModelProvider}) — so the registry/turn tests exercise the real file-driven load path
 * (M4 {@code AgentReader}) rather than a hand-built spec.
 */
public class AgentRegistryTestHomeProfile implements QuarkusTestProfile {

    static final Path HOME = seed();

    private static Path seed() {
        try {
            Path home = Files.createTempDirectory("forvum-agents-home");
            Path agents = Files.createDirectories(home.resolve("agents"));
            Files.writeString(agents.resolve("main.md"), "You are the main agent.");
            Files.writeString(agents.resolve("main.json"),
                    "{ \"primaryModel\": \"ollama:qwen3:1.7b\", "
                  + "\"allowedTools\": [\"fs.read\", \"web.search\"] }");
            // A second agent pinned to the in-process FakeModelProvider so the turn path is exercisable
            // without a real LLM (AgentTurnTest).
            Files.writeString(agents.resolve("faker.md"), "You are a test agent.");
            Files.writeString(agents.resolve("faker.json"),
                    "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
            // A third agent pinned to the always-throwing BoomModelProvider, for the failed-turn path.
            Files.writeString(agents.resolve("boomer.md"), "You are a failing test agent.");
            Files.writeString(agents.resolve("boomer.json"),
                    "{ \"primaryModel\": \"boom:test-model\", \"allowedTools\": [] }");
            // A fourth agent carrying NON-default DR-8 fields (fallback chain, memory policy, role cap,
            // identity pointer) + a declared cycle, so spawn-inheritance and spec(id) are proven against
            // values that differ from the defaults (the [M19] override-only-if-distinct discipline).
            Files.writeString(agents.resolve("policied.md"), "You are a policied agent.");
            Files.writeString(agents.resolve("policied.json"),
                    "{ \"primaryModel\": \"ollama:qwen3:1.7b\", "
                  + "\"allowedTools\": [\"fs.read\", \"web.search\"], "
                  + "\"fallbackModels\": [\"openai:gpt-4.1-mini\"], "
                  + "\"roles\": [\"research-readonly\"], "
                  + "\"identityId\": \"default\", "
                  + "\"memoryPolicy\": { \"strategy\": \"METADATA\", \"tiers\": [\"MESSAGES\"], \"topK\": 4 }, "
                  + "\"cycle\": { \"steps\": [\"reflect\", \"critique\", \"revise\"], "
                  + "\"maxRounds\": 2, \"stopSentinel\": \"DONE\" } }");
            // A fifth agent with a TWO-link fallback chain, both on the in-process FakeModelProvider, so a
            // real turn exercises CAPR-driven routing (P3-4 #52): the primary is down-ranked when it sags.
            Files.writeString(agents.resolve("routed.md"), "You are a routed test agent.");
            Files.writeString(agents.resolve("routed.json"),
                    "{ \"primaryModel\": \"fake:sag-model\", \"allowedTools\": [], "
                  + "\"fallbackModels\": [\"fake:healthy-model\"] }");
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
