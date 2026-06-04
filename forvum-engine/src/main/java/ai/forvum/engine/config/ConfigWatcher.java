package ai.forvum.engine.config;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Watches the {@code $FORVUM_HOME} config surface with a single {@link WatchService} and fires a CDI
 * {@link ConfigurationChangedEvent} (per-path coalesced over a 250 ms debounce window) so downstream
 * subsystems hot-reload without a restart.
 *
 * <p>Threading is virtual-threads-first (CLAUDE.md §11): the blocking {@link WatchService#take()} loop
 * runs on a single virtual thread; coalescing uses a {@link DebounceBuffer} (concurrent, no
 * {@code synchronized}). When {@code $FORVUM_HOME} is absent the watcher logs a warning and no-ops —
 * it never creates directories nor crashes, so a fresh install or the CI native smoke still boots.
 *
 * <p><b>Native (Risk #7):</b> {@code WatchService} semantics are host-specific (macOS polling, Linux
 * inotify, Windows ReadDirectoryChangesW), so M4's behavioral assertion is the sanctioned Phase-1
 * native carve-out. The watcher still native-COMPILES and boots in the native smoke.
 */
@Singleton
public class ConfigWatcher {

    private static final Logger LOG = Logger.getLogger(ConfigWatcher.class);

    private static final Duration DEBOUNCE = Duration.ofMillis(250);
    private static final String CONFIG_FILE = "config.json";

    /** Subfolders under $FORVUM_HOME that carry hot-reloadable config; {@code state/} and {@code plugins/} are excluded. */
    private static final Set<String> WATCHED_SUBFOLDERS =
            Set.of("identities", "agents", "skills", "crons", "channels", "mcp-servers");

    private final ForvumHome home;
    private final Event<ConfigurationChangedEvent> emitter;
    private final DebounceBuffer buffer = new DebounceBuffer();
    private final Map<WatchKey, Path> keyToDir = new ConcurrentHashMap<>();

    private volatile boolean running;
    private WatchService watchService;
    private Thread watcherThread;

    @Inject
    public ConfigWatcher(ForvumHome home, Event<ConfigurationChangedEvent> emitter) {
        this.home = home;
        this.emitter = emitter;
    }

    void onStart(@Observes StartupEvent ev) {
        start();
    }

    /** Registers the watch surface and launches the watch loop. Package-private for tests. */
    void start() {
        Path root = home.root();
        if (!Files.isDirectory(root)) {
            LOG.warnf("$FORVUM_HOME not found at %s; configuration hot reload is disabled. "
                    + "Run 'forvum init' to create it.", root);
            return;
        }
        try {
            watchService = root.getFileSystem().newWatchService();
            register(root);
            for (String subfolder : WATCHED_SUBFOLDERS) {
                Path dir = root.resolve(subfolder);
                if (Files.isDirectory(dir)) {
                    register(dir);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start config watch on " + root, e);
        }
        running = true;
        watcherThread = Thread.ofVirtual().name("forvum-config-watcher").start(this::watchLoop);
        LOG.infof("Watching %s for configuration changes (debounce %d ms).", root, DEBOUNCE.toMillis());
    }

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keyToDir.put(key, dir);
    }

    private void watchLoop() {
        try {
            while (running) {
                WatchKey key = watchService.take();                 // block until the first event
                do {
                    drainKey(key);
                    if (!key.reset()) {
                        keyToDir.remove(key); // directory gone/invalid — drop the now-dead key
                    }
                    key = watchService.poll(DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS); // coalesce the burst
                } while (key != null && running);
                fireAll(buffer.drain());                            // 250 ms quiet → emit coalesced events
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // normal shutdown triggered by stop()
        }
    }

    private void drainKey(WatchKey key) {
        Path dir = keyToDir.get(key);
        if (dir == null) {
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            Optional<ChangeType> type = ChangeType.from(event.kind());
            if (type.isEmpty()) {
                onOverflow(dir); // OVERFLOW: the OS dropped events — rescan to recover them
                continue;
            }
            Path full = dir.resolve((Path) event.context());
            Path relative = home.root().relativize(full);
            if (type.get() == ChangeType.CREATED && isWatchedSubfolder(relative) && Files.isDirectory(full)) {
                registerAndScan(full); // a watched subfolder appeared after boot — start watching it
            } else if (isWatchedConfig(relative)) {
                buffer.record(relative, type.get());
            }
        }
    }

    /** True for {@code config.json} at the root, or any file inside a watched subfolder. */
    private static boolean isWatchedConfig(Path relative) {
        if (relative.getNameCount() == 1) {
            return relative.toString().equals(CONFIG_FILE);
        }
        return WATCHED_SUBFOLDERS.contains(relative.getName(0).toString());
    }

    /** True for a direct child of {@code $FORVUM_HOME} that is one of the watched subfolders. */
    private static boolean isWatchedSubfolder(Path relative) {
        return relative.getNameCount() == 1 && WATCHED_SUBFOLDERS.contains(relative.toString());
    }

    /** Watch a watched subfolder that appeared after boot, then announce the files already inside it. */
    private void registerAndScan(Path dir) {
        try {
            register(dir);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to watch newly created config directory %s", dir);
            return;
        }
        recordExistingFiles(dir, ChangeType.CREATED);
    }

    /** OVERFLOW recovery: the OS lost events, so rescan {@code dir} and re-announce its files. */
    private void onOverflow(Path dir) {
        LOG.warnf("WatchService OVERFLOW on %s; rescanning to recover dropped events.", dir);
        recordExistingFiles(dir, ChangeType.MODIFIED);
    }

    private void recordExistingFiles(Path dir, ChangeType type) {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile).forEach(file -> {
                Path relative = home.root().relativize(file);
                if (isWatchedConfig(relative)) {
                    buffer.record(relative, type);
                }
            });
        } catch (IOException e) {
            LOG.warnf(e, "Failed to scan config directory %s", dir);
        }
    }

    private void fireAll(List<ConfigurationChangedEvent> events) {
        for (ConfigurationChangedEvent event : events) {
            try {
                emitter.fire(event); // synchronous CDI event on this watch thread
            } catch (RuntimeException e) {
                LOG.errorf(e, "A ConfigurationChangedEvent observer failed for %s; watch continues.", event);
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        WatchService ws = watchService;
        if (ws != null) {
            try {
                ws.close(); // unblocks take()/poll() with ClosedWatchServiceException
            } catch (IOException e) {
                LOG.debugf(e, "Error closing config WatchService");
            }
        }
        Thread thread = watcherThread;
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
