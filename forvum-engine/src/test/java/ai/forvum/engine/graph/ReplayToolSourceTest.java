package ai.forvum.engine.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ai.forvum.engine.graph.ReplayToolSource.RecordedTool;

/**
 * {@link ReplayToolSource} FIFO-per-tool matching (#57): the Nth call to a tool consumes the Nth recorded
 * result for that name; a miss (unseen tool or drained queue) yields the synthetic marker. Plain Surefire.
 */
class ReplayToolSourceTest {

    @Test
    void servesRecordedResultsFifoPerToolName() {
        ReplayToolSource source = new ReplayToolSource(List.of(
                new RecordedTool("fs.read", "first"),
                new RecordedTool("web.fetch", "<html/>"),
                new RecordedTool("fs.read", "second")));

        assertEquals("first", source.next("fs.read"));
        assertEquals("<html/>", source.next("web.fetch"));
        assertEquals("second", source.next("fs.read"), "the second fs.read call gets the second recorded result");
    }

    @Test
    void synthesizesUnavailableForAToolTheRecordingNeverCalled() {
        ReplayToolSource source = new ReplayToolSource(List.of(new RecordedTool("fs.read", "x")));
        assertEquals(ReplayToolSource.UNAVAILABLE, source.next("shell.exec"));
    }

    @Test
    void synthesizesUnavailableWhenAToolsQueueIsDrained() {
        ReplayToolSource source = new ReplayToolSource(List.of(new RecordedTool("fs.read", "only")));
        assertEquals("only", source.next("fs.read"));
        assertEquals(ReplayToolSource.UNAVAILABLE, source.next("fs.read"),
                "a tool called more often in the rerun than the recording yields the synthetic marker");
    }

    @Test
    void normalizesANullRecordedResultToEmpty() {
        ReplayToolSource source = new ReplayToolSource(List.of(new RecordedTool("fs.read", null)));
        assertEquals("", source.next("fs.read"));
    }
}
