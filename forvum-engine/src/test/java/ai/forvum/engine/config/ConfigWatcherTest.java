package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioral integration test ({@link QuarkusTest}, JVM) for {@link ConfigWatcher}: a real change under
 * a synthetic {@code $FORVUM_HOME} fires a CDI {@link ConfigurationChangedEvent} that observers receive
 * with the correct {@code $FORVUM_HOME}-relative path and {@link ChangeType}.
 *
 * <p>Named {@code *Test} (Surefire / JVM {@code verify} leg), not {@code *IT} — {@code *IT} is the
 * native-only Failsafe smoke (forvum-app). The {@code awaitNext} timeout is generous (15 s) to absorb
 * macOS {@code WatchService} polling latency (Risk #7; observed ~2 s); Linux inotify is near-instant.
 *
 * <p><b>Native carve-out:</b> this behavioral assertion is JVM-only — {@code @QuarkusTest} never runs in
 * a native image (only {@code @QuarkusIntegrationTest} does, and M4 adds none for the watcher). The
 * watcher still native-COMPILES and boots in the native smoke.
 */
@QuarkusTest
@TestProfile(TestHomeProfile.class)
class ConfigWatcherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Inject
    ConfigChangeRecorder recorder;

    @BeforeEach
    void clearRecorder() {
        recorder.clear();
    }

    @Test
    void fileModificationFiresModifiedEventWithRelativePath() throws IOException, InterruptedException {
        Files.writeString(TestHomeProfile.HOME.resolve("crons/seed.json"),
                "{\"schedule\":\"*/5 * * * *\"}");

        ConfigurationChangedEvent event = recorder.awaitNext(TIMEOUT);

        assertEquals(Path.of("crons/seed.json"), event.path());
        assertEquals(ChangeType.MODIFIED, event.type());
    }

    @Test
    void fileDeletionFiresDeletedEventWithRelativePath() throws IOException, InterruptedException {
        Files.delete(TestHomeProfile.HOME.resolve("identities/seed.json"));

        ConfigurationChangedEvent event = recorder.awaitNext(TIMEOUT);

        assertEquals(Path.of("identities/seed.json"), event.path());
        assertEquals(ChangeType.DELETED, event.type());
    }

    @Test
    void subfolderCreatedAfterBootIsWatchedAndItsFilesFireEvents() throws IOException, InterruptedException {
        Path subfolder = TestHomeProfile.HOME.resolve("mcp-servers"); // absent at boot (see TestHomeProfile)
        Files.createDirectory(subfolder);
        Files.writeString(subfolder.resolve("github.json"), "{\"transport\":\"stdio\"}");

        ConfigurationChangedEvent event = recorder.awaitNext(TIMEOUT);

        assertEquals(Path.of("mcp-servers/github.json"), event.path());
        assertTrue(event.type() == ChangeType.CREATED || event.type() == ChangeType.MODIFIED,
                "expected CREATED or MODIFIED, got " + event.type());
    }
}
