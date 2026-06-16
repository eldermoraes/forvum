package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The §3.6 OTel four-span baseline (P2-15 #40): a turn emits {@code forvum.agent.turn} →
 * {@code forvum.graph.run} → {@code forvum.llm.call}, each carrying {@code thread.is_virtual} (true here
 * because the turn is driven on a virtual thread, the channel/cron carrier). The OTel SDK is re-enabled
 * for this profile (the engine default is disabled) and a CDI {@link InMemorySpanExporter} captures the
 * spans. {@code forvum.tool.call} uses the identical {@code @WithSpan} mechanism (a pong turn calls no
 * tool, so it is not in this assertion; the same interceptor proven on the other three covers it).
 */
@QuarkusTest
@TestProfile(TurnSpanIT.SpanProfile.class)
class TurnSpanIT {

    @Inject
    TurnService turns;

    @Inject
    OpenTelemetry openTelemetry;

    @Test
    void aTurnEmitsTheBaselineSpansOnAVirtualThread() throws Exception {
        SpanProfile.EXPORTER.reset();

        // Drive the turn on a virtual thread (the production carrier) so thread.is_virtual is true.
        Thread vt = Thread.ofVirtual().start(() ->
                turns.dispatch(new ChannelMessage("web", "sess-a", "hello", Instant.now()), e -> { }));
        vt.join();
        flush();

        List<SpanData> spans = SpanProfile.EXPORTER.getFinishedSpanItems();
        List<String> names = spans.stream().map(SpanData::getName).toList();
        assertTrue(names.contains("forvum.agent.turn"), () -> "missing agent span; got: " + names);
        assertTrue(names.contains("forvum.graph.run"), () -> "missing graph span; got: " + names);
        assertTrue(names.contains("forvum.llm.call"), () -> "missing llm span; got: " + names);

        SpanData turnSpan = spans.stream().filter(s -> s.getName().equals("forvum.agent.turn"))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE,
                turnSpan.getAttributes().get(AttributeKey.booleanKey("thread.is_virtual")),
                "the turn ran on a virtual thread");
        assertEquals("main",
                turnSpan.getAttributes().get(AttributeKey.stringKey("forvum.agent.id")));
    }

    private void flush() {
        // Force the batch processor to drain so the assertion is deterministic (no sleep/poll).
        if (openTelemetry instanceof OpenTelemetrySdk sdk) {
            sdk.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS);
        }
    }

    public static class SpanProfile implements QuarkusTestProfile {

        static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();
        static final Path HOME = seed();

        /** A profile-scoped CDI exporter — the {@code cdi} traces exporter wires it as the span sink. */
        @Produces
        @Singleton
        InMemorySpanExporter spanExporter() {
            return EXPORTER;
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            // Re-enable the SDK (engine tests default it off) so the spans are actually exported.
            return Map.of("forvum.home", HOME.toString(),
                    "quarkus.otel.sdk.disabled", "false");
        }

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-span-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("alice.json"),
                        "{ \"displayName\": \"Alice\", \"channelAccounts\": { \"web\": \"sess-a\" } }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
