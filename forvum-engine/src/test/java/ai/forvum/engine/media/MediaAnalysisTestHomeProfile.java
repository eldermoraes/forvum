package ai.forvum.engine.media;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Points {@code $FORVUM_HOME} at a throwaway temp directory seeded with agents pinned to the in-process
 * {@link CapturingVisionModelProvider} (extension id {@code capture}): {@code seer} (primary
 * {@code capture:vision-primary}) drives the default-model path, and {@code pauper} carries a
 * {@code maxTokens:0} budget so a sub-generation is stopped pre-call by the #169 budget gate — proving the
 * seam drives the budget-gated {@code LlmSelector.resolve(..., budget)} path.
 */
public class MediaAnalysisTestHomeProfile implements QuarkusTestProfile {

    static final Path HOME = seed();

    private static Path seed() {
        try {
            Path home = Files.createTempDirectory("forvum-media-home");
            Path agents = Files.createDirectories(home.resolve("agents"));
            Files.writeString(agents.resolve("seer.md"), "You are a vision agent.");
            Files.writeString(agents.resolve("seer.json"),
                    "{ \"primaryModel\": \"capture:vision-primary\", \"allowedTools\": [] }");
            Files.writeString(agents.resolve("pauper.md"), "You are a budget-exhausted vision agent.");
            Files.writeString(agents.resolve("pauper.json"),
                    "{ \"primaryModel\": \"capture:vision-primary\", \"allowedTools\": [], "
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
