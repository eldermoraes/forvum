package ai.forvum.engine.config;

import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Optional;

/**
 * The kind of change observed for a file under {@code $FORVUM_HOME}, mapped 1:1 from the JDK
 * {@link WatchEvent.Kind} constants.
 */
public enum ChangeType {

    CREATED,
    MODIFIED,
    DELETED;

    /**
     * Translates a {@link WatchEvent.Kind} into a {@link ChangeType}. Returns {@link Optional#empty()}
     * for {@link StandardWatchEventKinds#OVERFLOW} or any unrecognized kind, so the watcher ignores
     * those rather than emitting a spurious event.
     */
    public static Optional<ChangeType> from(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return Optional.of(CREATED);
        }
        if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            return Optional.of(MODIFIED);
        }
        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            return Optional.of(DELETED);
        }
        return Optional.empty();
    }
}
