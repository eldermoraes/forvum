package ai.forvum.engine.agent;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Points {@code $FORVUM_HOME} at a throwaway temp directory seeded with a single {@code main} agent
 * ({@code agents/main.md} + {@code agents/main.json}) so {@link AgentRegistryTest} exercises the real
 * file-driven load path (M4 {@code AgentReader}) rather than a hand-built spec.
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
