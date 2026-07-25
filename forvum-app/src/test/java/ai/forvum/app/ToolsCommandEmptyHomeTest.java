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
 * {@code forvum tools} over an empty (un-{@code init}ed) home (#184): the tools are still listed (they are
 * compiled into the binary), but with no readable {@code agents/main.json} the belt column is {@code -} and
 * the command prints a {@code forvum init} hint and exits 0 — informational, never a failure.
 */
@QuarkusMainTest
@TestProfile(ToolsCommandEmptyHomeTest.HomeProfile.class)
class ToolsCommandEmptyHomeTest {

    @Test
    @Launch("tools")
    void listsToolsWithAnUnknownBeltAndInitHint(LaunchResult result) {
        Assertions.assertEquals(0, result.exitCode(),
                () -> "tools must exit 0 on an empty home; stderr: " + result.getErrorOutput());
        String out = result.getOutput();
        Assertions.assertTrue(out.contains("fs.read"),
                () -> "tools are compiled into the binary, listed even with no config; got:\n" + out);
        Assertions.assertTrue(out.contains("belt:-"),
                () -> "with no readable main.json the belt column is '-'; got:\n" + out);
        Assertions.assertTrue(out.contains("forvum init"),
                () -> "the empty-home hint points at 'forvum init'; got:\n" + out);
    }

    public static class HomeProfile implements QuarkusTestProfile {

        static final Path HOME = create();

        private static Path create() {
            try {
                return Files.createTempDirectory("forvum-tools-empty-home");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString(), "quarkus.http.host-enabled", "false");
        }
    }
}
