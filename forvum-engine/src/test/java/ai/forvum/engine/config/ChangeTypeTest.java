package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.StandardWatchEventKinds;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ChangeType#from(java.nio.file.WatchEvent.Kind)} — the single place JDK
 * {@code WatchEvent} kinds are translated. Pure {@code *Test}, no Quarkus boot.
 */
class ChangeTypeTest {

    @Test
    void mapsEntryCreateToCreated() {
        assertEquals(Optional.of(ChangeType.CREATED),
                ChangeType.from(StandardWatchEventKinds.ENTRY_CREATE));
    }

    @Test
    void mapsEntryModifyToModified() {
        assertEquals(Optional.of(ChangeType.MODIFIED),
                ChangeType.from(StandardWatchEventKinds.ENTRY_MODIFY));
    }

    @Test
    void mapsEntryDeleteToDeleted() {
        assertEquals(Optional.of(ChangeType.DELETED),
                ChangeType.from(StandardWatchEventKinds.ENTRY_DELETE));
    }

    @Test
    void overflowIsIgnored() {
        assertTrue(ChangeType.from(StandardWatchEventKinds.OVERFLOW).isEmpty(),
                "OVERFLOW must not map to a ChangeType");
    }
}
