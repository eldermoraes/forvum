package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.runtime.CommandMode;

import io.quarkus.runtime.StartupEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The M20 cold-start lever in {@link ConfigWatcher}: a one-shot command does NOT start the
 * {@code WatchService}, while a normal run does. <strong>Both</strong> directions are asserted so that
 * dropping the {@code commandMode.isOneShot()} guard (review finding #15) fails the build. Pure JVM (no
 * Quarkus boot) — the behavioral file-watch assertions stay in {@link ConfigWatcherTest}.
 */
class ConfigWatcherOneShotTest {

    @Test
    void oneShotCommandDoesNotStartTheWatcher(@TempDir Path tmp) {
        // emitter is null: the one-shot path must return before ever firing an event.
        ConfigWatcher watcher = new ConfigWatcher(new ForvumHome(tmp), null, new CommandMode(new String[] {"init"}));

        watcher.onStart(new StartupEvent());

        assertFalse(watcher.isWatching(), "a one-shot command must not start the config WatchService");
    }

    @Test
    void normalRunStartsTheWatcher(@TempDir Path tmp) {
        ConfigWatcher watcher = new ConfigWatcher(new ForvumHome(tmp), null, new CommandMode(new String[] {}));
        try {
            watcher.onStart(new StartupEvent());

            assertTrue(watcher.isWatching(), "a normal run must start the config WatchService");
        } finally {
            watcher.stop(); // close the WatchService + join the watch thread
        }
    }
}
