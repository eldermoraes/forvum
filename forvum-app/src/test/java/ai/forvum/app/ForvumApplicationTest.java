package ai.forvum.app;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * JVM command-mode smoke for {@link ForvumApplication}: launches the app in-process and asserts a
 * clean exit with the rendered banner. Reused against the built binary by {@code ForvumApplicationIT}.
 */
@QuarkusMainTest
class ForvumApplicationTest {

    @Test
    @Launch({})
    void runsAndPrintsBanner(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode());
        Assertions.assertTrue(result.getOutput().contains("Forvum"),
                () -> "Expected the Forvum banner in output, got: " + result.getOutput());
    }
}
