package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** {@link BlockType} mirrors the {@code messages.block_type} discriminator (P2-COMPACT, V2 migration). */
class BlockTypeTest {

    @Test
    void dbValuesMatchMigrationTokens() {
        assertEquals("turn_message", BlockType.TURN_MESSAGE.dbValue());
        assertEquals("turn_reasoning", BlockType.TURN_REASONING.dbValue());
        assertEquals("turn_artifact", BlockType.TURN_ARTIFACT.dbValue());
        assertEquals("tool_execution", BlockType.TOOL_EXECUTION.dbValue());
    }

    @ParameterizedTest
    @EnumSource(BlockType.class)
    void fromDbValueRoundTripsEveryConstant(BlockType type) {
        assertEquals(type, BlockType.fromDbValue(type.dbValue()));
    }

    @Test
    void fromDbValueRejectsUnknownAndWrongCase() {
        assertThrows(IllegalStateException.class, () -> BlockType.fromDbValue("reasoning"));
        assertThrows(IllegalStateException.class, () -> BlockType.fromDbValue("TURN_MESSAGE"));
        assertThrows(IllegalStateException.class, () -> BlockType.fromDbValue(null));
    }
}
