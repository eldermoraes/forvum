package ai.forvum.app.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Unit tests for loading the packaged pack and an override file, and the fails-by-default error paths. */
class QaScenarioLoaderTest {

    private final QaScenarioLoader loader = new QaScenarioLoader();

    @Test
    void loadsTheBundledPackFromTheClasspath() {
        List<QaScenario> scenarios = loader.loadPackaged();
        assertFalse(scenarios.isEmpty(), "the bundled qa/scenarios.json must be on the classpath");
        assertTrue(scenarios.stream().allMatch(s -> "cli".equals(s.channel())),
                "v0.1 ships only cli scenarios");
        assertTrue(scenarios.stream().anyMatch(s -> "cli-exact-greeting".equals(s.id())));
    }

    @Test
    void loadsAnOverridePackFromAFile(@TempDir Path dir) throws IOException {
        Path pack = dir.resolve("pack.json");
        Files.writeString(pack, "{ \"scenarios\": [ { \"id\": \"s1\", \"channel\": \"cli\", "
                + "\"prompt\": \"hi\", \"expect\": \"echo: hi\", \"match\": \"exact\" } ] }");
        List<QaScenario> scenarios = loader.loadFrom(pack);
        assertEquals(1, scenarios.size());
        assertEquals("s1", scenarios.get(0).id());
        assertEquals("hi", scenarios.get(0).prompt());
        assertEquals("echo: hi", scenarios.get(0).expect());
        assertEquals("exact", scenarios.get(0).match());
    }

    @Test
    void unknownFieldsAreToleratedForwardCompatWithEvalFormat(@TempDir Path dir) throws IOException {
        Path pack = dir.resolve("pack.json");
        Files.writeString(pack, "{ \"version\": 2, \"scenarios\": [ { \"id\": \"s1\", \"channel\": \"cli\", "
                + "\"prompt\": \"hi\", \"setup\": { \"agentModel\": \"echo:x\" }, "
                + "\"expect\": \"hi\", \"match\": \"contains\" } ] }");
        List<QaScenario> scenarios = loader.loadFrom(pack);
        assertEquals(1, scenarios.size());
        assertEquals("s1", scenarios.get(0).id());
    }

    @Test
    void aMissingOverrideFileThrows(@TempDir Path dir) {
        assertThrows(IllegalStateException.class, () -> loader.loadFrom(dir.resolve("absent.json")));
    }

    @Test
    void aMalformedPackThrows(@TempDir Path dir) throws IOException {
        Path pack = dir.resolve("pack.json");
        Files.writeString(pack, "{ not json");
        assertThrows(UncheckedIOException.class, () -> loader.loadFrom(pack));
    }
}
