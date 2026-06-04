package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link DebounceBuffer} coalescing — deterministic, no timing. Pure {@code *Test}.
 */
class DebounceBufferTest {

    private final DebounceBuffer buffer = new DebounceBuffer();

    @Test
    void coalescesToLastChangeTypePerPath() {
        Path path = Path.of("agents/main.json");
        buffer.record(path, ChangeType.CREATED);
        buffer.record(path, ChangeType.MODIFIED);
        buffer.record(path, ChangeType.DELETED);

        List<ConfigurationChangedEvent> drained = buffer.drain();

        assertEquals(List.of(new ConfigurationChangedEvent(path, ChangeType.DELETED)), drained);
    }

    @Test
    void keepsDistinctPathsSeparateOrderedByPath() {
        buffer.record(Path.of("crons/b.json"), ChangeType.MODIFIED);
        buffer.record(Path.of("agents/a.json"), ChangeType.CREATED);

        List<ConfigurationChangedEvent> drained = buffer.drain();

        assertEquals(List.of(
                new ConfigurationChangedEvent(Path.of("agents/a.json"), ChangeType.CREATED),
                new ConfigurationChangedEvent(Path.of("crons/b.json"), ChangeType.MODIFIED)),
                drained);
    }

    @Test
    void drainEmptiesTheBuffer() {
        buffer.record(Path.of("config.json"), ChangeType.MODIFIED);
        assertFalse(buffer.isEmpty());

        buffer.drain();

        assertTrue(buffer.isEmpty());
        assertEquals(List.of(), buffer.drain());
    }

    @Test
    void drainOnEmptyReturnsEmpty() {
        assertEquals(List.of(), buffer.drain());
    }
}
