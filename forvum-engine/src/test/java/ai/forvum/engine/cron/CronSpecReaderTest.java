package ai.forvum.engine.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ModelRef;
import ai.forvum.core.id.AgentId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

/**
 * Parses a raw {@code crons/<id>.json} (delivered by the M4 {@code CronReader}) into a typed
 * {@link CronSpec} (M19), including the P2-CRON-DELIVERY {@code delivery} block. The JSON carries the
 * cron expression, target agent, the cron's own model ({@code primary}, distinct from the agent's persona
 * — maintainer decision 2026-06-06), the prompt, and an optional {@code delivery} directive validated at
 * parse against the known channel set so an invalid/ambiguous spec is rejected here.
 */
class CronSpecReaderTest {

    private final CronSpecReader reader = new CronSpecReader();
    private static final Set<String> CHANNELS = Set.of("telegram", "web");

    private static JsonNode json(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesAFullCronSpec() {
        CronSpec spec = reader.parse("daily-brief", json(
                "{\"cron\":\"0 * * * * ?\",\"agentId\":\"main\","
              + "\"primary\":\"ollama:llama3.2:1b\",\"prompt\":\"Summarize my day\"}"), CHANNELS);

        assertEquals("daily-brief", spec.id());
        assertEquals("0 * * * * ?", spec.cron());
        assertEquals(new AgentId("main"), spec.agentId());
        assertEquals(ModelRef.parse("ollama:llama3.2:1b"), spec.primaryModel());
        assertEquals("Summarize my day", spec.prompt());
        assertEquals(Delivery.NONE, spec.delivery(),
                "an absent delivery block defaults to NONE (M19 behavior preserved)");
    }

    @Test
    void rejectsAMissingRequiredField() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reader.parse("bad", json(
                "{\"agentId\":\"main\",\"primary\":\"ollama:m\",\"prompt\":\"p\"}"), CHANNELS));
        assertTrue(ex.getMessage().contains("cron"), "the message names the missing field, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("bad"), "and the cron id");
    }

    @Test
    void rejectsABlankPrompt() {
        assertThrows(IllegalStateException.class, () -> reader.parse("x", json(
                "{\"cron\":\"0 * * * * ?\",\"agentId\":\"main\",\"primary\":\"ollama:m\",\"prompt\":\"  \"}"), CHANNELS));
    }

    @Test
    void rejectsAnUnparseableModelRef() {
        assertThrows(IllegalStateException.class, () -> reader.parse("x", json(
                "{\"cron\":\"0 * * * * ?\",\"agentId\":\"main\",\"primary\":\"no-colon\",\"prompt\":\"p\"}"), CHANNELS));
    }

    // ---- P2-CRON-DELIVERY: delivery-block parsing + validation -------------------------------------

    private JsonNode withDelivery(String deliveryJson) {
        return json("{\"cron\":\"0 * * * * ?\",\"agentId\":\"main\",\"primary\":\"ollama:m\","
                + "\"prompt\":\"p\",\"delivery\":" + deliveryJson + "}");
    }

    /** Curated edge cases over every mode token (the parameterized property over the three modes). */
    @ParameterizedTest
    @CsvSource({
            "none,        NONE",
            "last,        LAST",
            "explicit-to, EXPLICIT_TO"
    })
    void parsesEachDeliveryMode(String wire, DeliveryMode expected) {
        String target = expected == DeliveryMode.EXPLICIT_TO ? ",\"target\":\"telegram\"" : "";
        CronSpec spec = reader.parse("c", withDelivery("{\"mode\":\"" + wire + "\"" + target + "}"), CHANNELS);
        assertEquals(expected, spec.delivery().mode());
        if (expected == DeliveryMode.EXPLICIT_TO) {
            assertEquals("telegram", spec.delivery().target());
        }
    }

    @Test
    void deliveryModeIsCaseInsensitiveOnTheKebabToken() {
        CronSpec spec = reader.parse("c", withDelivery("{\"mode\":\"Explicit-To\",\"target\":\"web\"}"), CHANNELS);
        assertEquals(DeliveryMode.EXPLICIT_TO, spec.delivery().mode());
        assertEquals("web", spec.delivery().target());
    }

    @Test
    void rejectsAnUnknownDeliveryMode() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reader.parse("c", withDelivery("{\"mode\":\"broadcast\"}"), CHANNELS));
        assertTrue(ex.getMessage().contains("broadcast"), "names the bad mode: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("c"), "and the cron id");
    }

    @Test
    void rejectsAMissingDeliveryMode() {
        assertThrows(IllegalStateException.class,
                () -> reader.parse("c", withDelivery("{\"target\":\"telegram\"}"), CHANNELS));
    }

    @Test
    void rejectsExplicitToWithoutATarget() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reader.parse("c", withDelivery("{\"mode\":\"explicit-to\"}"), CHANNELS));
        assertTrue(ex.getMessage().contains("target"), "names the missing target: " + ex.getMessage());
    }

    @Test
    void rejectsExplicitToWithABlankTarget() {
        assertThrows(IllegalStateException.class,
                () -> reader.parse("c", withDelivery("{\"mode\":\"explicit-to\",\"target\":\"  \"}"), CHANNELS));
    }

    @Test
    void rejectsExplicitToWithAnUnknownChannelTarget() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reader.parse("c", withDelivery("{\"mode\":\"explicit-to\",\"target\":\"slack\"}"), CHANNELS));
        assertTrue(ex.getMessage().contains("slack"), "names the unknown target: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("known channel"), "explains it is not a known channel");
    }

    @Test
    void rejectsAnAmbiguousSpecTargetWithModeLast() {
        // A target with a non-explicit mode is ambiguous — rejected by the Delivery canonical constructor.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> reader.parse("c", withDelivery("{\"mode\":\"last\",\"target\":\"telegram\"}"), CHANNELS));
        assertTrue(ex.getMessage().contains("target"), "explains the spurious target: " + ex.getMessage());
    }

    @Test
    void rejectsAnAmbiguousSpecTargetWithModeNone() {
        assertThrows(IllegalStateException.class,
                () -> reader.parse("c", withDelivery("{\"mode\":\"none\",\"target\":\"telegram\"}"), CHANNELS));
    }

    @Test
    void rejectsADeliveryThatIsNotAnObject() {
        assertThrows(IllegalStateException.class,
                () -> reader.parse("c", withDelivery("\"last\""), CHANNELS));
    }

    @Test
    void anExplicitNullDeliveryDefaultsToNone() {
        CronSpec spec = reader.parse("c", withDelivery("null"), CHANNELS);
        assertEquals(Delivery.NONE, spec.delivery());
    }
}
