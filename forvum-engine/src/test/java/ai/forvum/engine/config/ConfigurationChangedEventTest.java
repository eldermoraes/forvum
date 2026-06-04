package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the {@link ConfigurationChangedEvent} record: accessor round-trip and the
 * canonical-constructor null rejection. Pure {@code *Test}, no Quarkus boot.
 */
class ConfigurationChangedEventTest {

    @Test
    void exposesPathAndType() {
        Path path = Path.of("agents/main.json");
        ConfigurationChangedEvent event = new ConfigurationChangedEvent(path, ChangeType.MODIFIED);

        assertEquals(path, event.path());
        assertEquals(ChangeType.MODIFIED, event.type());
    }

    @Test
    void rejectsNullPath() {
        assertThrows(NullPointerException.class,
                () -> new ConfigurationChangedEvent(null, ChangeType.CREATED));
    }

    @Test
    void rejectsNullType() {
        assertThrows(NullPointerException.class,
                () -> new ConfigurationChangedEvent(Path.of("config.json"), null));
    }
}
