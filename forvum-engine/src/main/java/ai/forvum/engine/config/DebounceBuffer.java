package ai.forvum.engine.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accumulates file-change signals and coalesces them per relative path — the last {@link ChangeType}
 * seen for a path within a debounce window wins (e.g. CREATE then DELETE drains as a single DELETED).
 * {@link ConfigWatcher} owns the 250 ms timing; this is the thread-safe accumulator it drains. No
 * {@code synchronized} — backed by a {@link ConcurrentHashMap} (CLAUDE.md §11).
 */
final class DebounceBuffer {

    private final Map<Path, ChangeType> pending = new ConcurrentHashMap<>();

    /** Records a change, overwriting any earlier pending change for the same path (last wins). */
    void record(Path relativePath, ChangeType type) {
        pending.put(relativePath, type);
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    /** Removes and returns all pending changes as events, ordered by path for deterministic delivery. */
    List<ConfigurationChangedEvent> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<ConfigurationChangedEvent> events = new ArrayList<>(pending.size());
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, ChangeType> entry = it.next();
            events.add(new ConfigurationChangedEvent(entry.getKey(), entry.getValue()));
            it.remove();
        }
        events.sort(Comparator.comparing(event -> event.path().toString()));
        return events;
    }
}
