package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.id.AgentId;

import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property-style invariants for {@link TaskRecord} and its enums (mandatory per ULTRAPLAN section 10):
 * {@code dbValue}/{@code fromDbValue} roundtrips for every constant, {@code fromDbValue} failure modes,
 * and canonical-constructor validation of the required fields.
 */
class TaskRecordPropertyTest {

    @ParameterizedTest
    @EnumSource(TaskType.class)
    void taskTypeDbValueRoundTripsForEveryConstant(TaskType type) {
        assertEquals(type, TaskType.fromDbValue(type.dbValue()));
    }

    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    void taskStatusDbValueRoundTripsForEveryConstant(TaskStatus status) {
        assertEquals(status, TaskStatus.fromDbValue(status.dbValue()));
    }

    @ParameterizedTest
    @MethodSource("notTaskTypeDbValues")
    void taskTypeFromDbValueRejectsAnythingThatIsNotAConstantDbValue(String value) {
        assertThrows(IllegalStateException.class, () -> TaskType.fromDbValue(value));
    }

    @ParameterizedTest
    @MethodSource("notTaskStatusDbValues")
    void taskStatusFromDbValueRejectsAnythingThatIsNotAConstantDbValue(String value) {
        assertThrows(IllegalStateException.class, () -> TaskStatus.fromDbValue(value));
    }

    @Test
    void acceptsAFullyPopulatedValidRecord() {
        TaskRecord r = new TaskRecord("id-1", new AgentId("main"), TaskType.CRON, "brief", null,
                "cron:brief", 1L, 2L, 3L, TaskStatus.COMPLETED, "{}", null, 1L, 4L);
        assertEquals("id-1", r.id());
        assertEquals(TaskType.CRON, r.taskType());
        assertEquals(TaskStatus.COMPLETED, r.status());
    }

    @Test
    void acceptsNullableLifecycleAndProvenanceFields() {
        // A sub-agent task with no cron id, no schedule, no result/error/duration — the nullable columns.
        TaskRecord r = new TaskRecord("id-2", new AgentId("main"), TaskType.SUB_AGENT, null, "child",
                "spawn:child", null, null, null, TaskStatus.PENDING, null, null, null, 9L);
        assertEquals(TaskType.SUB_AGENT, r.taskType());
        assertEquals("child", r.subAgentId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsABlankId(String blank) {
        assertThrows(IllegalStateException.class, () -> new TaskRecord(blank, new AgentId("main"),
                TaskType.BACKGROUND, null, null, "n", null, null, null, TaskStatus.PENDING, null, null,
                null, 1L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsABlankName(String blank) {
        assertThrows(IllegalStateException.class, () -> new TaskRecord("id", new AgentId("main"),
                TaskType.BACKGROUND, null, null, blank, null, null, null, TaskStatus.PENDING, null, null,
                null, 1L));
    }

    @Test
    void rejectsNullAgentId() {
        assertThrows(IllegalStateException.class, () -> new TaskRecord("id", null, TaskType.BACKGROUND,
                null, null, "n", null, null, null, TaskStatus.PENDING, null, null, null, 1L));
    }

    @Test
    void rejectsNullTaskType() {
        assertThrows(IllegalStateException.class, () -> new TaskRecord("id", new AgentId("main"), null,
                null, null, "n", null, null, null, TaskStatus.PENDING, null, null, null, 1L));
    }

    @Test
    void rejectsNullStatus() {
        assertThrows(IllegalStateException.class, () -> new TaskRecord("id", new AgentId("main"),
                TaskType.BACKGROUND, null, null, "n", null, null, null, null, null, null, null, 1L));
    }

    /** Curated near-misses plus seeded-random strings, all filtered to non-dbValue tokens. */
    static Stream<String> notTaskTypeDbValues() {
        Set<String> valid = Arrays.stream(TaskType.values()).map(TaskType::dbValue).collect(Collectors.toSet());
        Stream<String> edges = Stream.of("", " ", "CRON", "Cron", "sub-agent", "subagent", "BACKGROUND",
                "cron ", "unknown");
        return Stream.concat(edges, seededRandoms(20260608L)).filter(s -> !valid.contains(s));
    }

    static Stream<String> notTaskStatusDbValues() {
        Set<String> valid = Arrays.stream(TaskStatus.values()).map(TaskStatus::dbValue)
                .collect(Collectors.toSet());
        Stream<String> edges = Stream.of("", " ", "PENDING", "Running", "done", "complete", "ok",
                "completed ", "failed");
        return Stream.concat(edges, seededRandoms(20260609L)).filter(s -> !valid.contains(s));
    }

    private static Stream<String> seededRandoms(long seed) {
        Random r = new Random(seed);
        return Stream.generate(() -> randomAscii(r, 12)).limit(100);
    }

    private static String randomAscii(Random r, int maxLen) {
        int len = r.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + r.nextInt(26)));
        }
        return sb.toString();
    }
}
