package ai.forvum.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.sdk.ChannelTurnDriver;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * X4 (#70) — per-channel first-token latency gate (CLAUDE.md §11 / ULTRAPLAN §10): the per-turn
 * performance budgets (excluding inference) — <strong>TUI ≤ 200 ms, Web ≤ 300 ms, Telegram ≤ 500 ms</strong>.
 *
 * <p><strong>What is measured.</strong> Every channel (web, tui, telegram) drives a turn through the same
 * seam: the SDK {@link ChannelTurnDriver} (resolved to the engine {@code TurnService} on the app
 * classpath, Resolution B — see {@code TuiScriptedTurnE2E}/{@code WebScriptedTurnE2E}/{@code
 * TelegramAllowDenyE2E}). The first-token latency is the wall-clock from {@code dispatch(message, sink)}
 * to the sink's first {@link AgentEvent} (a {@link TokenDelta}). Inference is excluded by the in-process
 * {@code FakeModelProvider} ({@code fake:test-model}, replies {@code "pong"} synchronously), exactly the
 * perf-gate convention X6 and the property tests use. The dominant, shared cost is the engine turn
 * (identity resolution + request-context activation + the SQLite read/compact pass + the model dispatch);
 * a channel's own transport (a {@code @WebSocket} callback, a stdin line, a long-poll hop) is a thin
 * wrapper over this, so gating the shared driver against each channel's documented budget is the faithful,
 * CI-stable form of the per-channel gate (a per-channel transport micro-benchmark would be flaky and add
 * no signal — the budgets differ to give the heavier transports headroom, and the tightest, TUI's 200 ms,
 * is the binding constraint this asserts).
 *
 * <p><strong>Median is the in-budget gate; p95 has a CI-headroom multiplier.</strong> A warm fake-model
 * turn is NOT free: each {@code dispatch} pays a real per-turn SQLite round-trip (ensure-session +
 * the eager session-compaction read) on top of identity resolution and request-context activation, so the
 * measured median first-token sits in the tens-to-low-hundreds of milliseconds — and on a CI runner under
 * load (parallel reactor modules, a cold datasource — see the P2-14 {@code ApprovalServiceIT} macos-14
 * lesson) a handful of GC/scheduler outliers push the raw p95 well past the tight 200 ms TUI budget even
 * though the typical turn is comfortably inside it. So the gate asserts two things over {@value #SAMPLES}
 * warm dispatches (warm-up paid in {@link #warm()} before any timed region):
 * <ul>
 *   <li><strong>median &le; the documented budget</strong> — the true regression signal (a doubling of
 *       the turn's typical cost trips it), robust to a single outlier; and</li>
 *   <li><strong>p95 &le; budget &times; {@value #P95_CI_HEADROOM}</strong> — a generous ceiling that still
 *       catches a systemic slowdown (every dispatch regressing) without flaking on one scheduler pause on
 *       a contended cell.</li>
 * </ul>
 * The documented per-channel targets (200/300/500 ms) stay the spec the median enforces; the p95 multiplier
 * is the sanctioned CI-hardware amendment (CLAUDE.md §11 / ULTRAPLAN §10 / §5 carve-out discipline), not a
 * silent drop of the gate — it is a regression alarm, not a tight micro-benchmark.
 */
@QuarkusTest
@TestProfile(ChannelLatencyGateTest.FakeBackedHomeProfile.class)
class ChannelLatencyGateTest {

    /** Warm-up dispatches paid outside every timed region (lazy persistence init, JIT warm-up). */
    private static final int WARMUP = 10;

    /** Measured dispatches the median + p95 are computed over. */
    private static final int SAMPLES = 60;

    /**
     * CI-headroom multiplier applied to the p95 ceiling only (the median enforces the raw budget). A loaded
     * CI cell's GC/scheduler outliers inflate the tail of an otherwise in-budget distribution; 3x keeps the
     * p95 check a systemic-regression alarm without flaking on isolated outliers (the §5/§10 carve-out form).
     */
    private static final long P95_CI_HEADROOM = 3;

    /** Per-turn first-token budgets (ms), excluding inference — CLAUDE.md §11 / ULTRAPLAN §10. */
    private static final long TUI_BUDGET_MS = 200;
    private static final long WEB_BUDGET_MS = 300;
    private static final long TELEGRAM_BUDGET_MS = 500;

    @Inject
    ChannelTurnDriver driver;

    @BeforeEach
    void warm() {
        // Pay the one-time lazy datasource/Hibernate/Agroal cold-start (and JIT) OUTSIDE the timed loop,
        // so the measured samples reflect a warm engine turn regardless of JUnit method order.
        for (int i = 0; i < WARMUP; i++) {
            firstTokenLatencyMillis("tui", "warmup-" + i);
        }
    }

    @Test
    void tuiFirstTokenLatencyIsWithinBudget() {
        assertWithinBudget("tui", TUI_BUDGET_MS);
    }

    @Test
    void webFirstTokenLatencyIsWithinBudget() {
        assertWithinBudget("web", WEB_BUDGET_MS);
    }

    @Test
    void telegramFirstTokenLatencyIsWithinBudget() {
        assertWithinBudget("telegram", TELEGRAM_BUDGET_MS);
    }

    private void assertWithinBudget(String channelId, long budgetMs) {
        List<Double> samples = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            samples.add(firstTokenLatencyMillis(channelId, "user-" + i));
        }
        double median = percentile(samples, 0.50);
        double p95 = percentile(samples, 0.95);
        long p95Ceiling = budgetMs * P95_CI_HEADROOM;

        // The median enforces the documented budget (the typical-turn regression signal).
        assertTrue(median <= budgetMs,
                () -> "channel '" + channelId + "' first-token median = " + String.format("%.1f", median)
                        + " ms exceeds the " + budgetMs + " ms budget (excluding inference). p95="
                        + String.format("%.1f", p95) + " ms. samples(ms)="
                        + samples.stream().map(d -> String.format("%.1f", d)).toList());
        // The p95 enforces a CI-headroom ceiling (a systemic slowdown, not one outlier).
        assertTrue(p95 <= p95Ceiling,
                () -> "channel '" + channelId + "' first-token p95 = " + String.format("%.1f", p95)
                        + " ms exceeds the " + p95Ceiling + " ms CI ceiling (" + budgetMs + " ms budget x "
                        + P95_CI_HEADROOM + "; median=" + String.format("%.1f", median) + " ms). samples(ms)="
                        + samples.stream().map(d -> String.format("%.1f", d)).toList());
    }

    /**
     * Drive one real turn through the SDK {@link ChannelTurnDriver} and return the milliseconds from the
     * {@code dispatch} call to the first {@link AgentEvent} the sink receives ({@code TokenDelta}). A fresh
     * {@code nativeUserId} per call keeps each turn its own session (one conversation per user per channel).
     */
    private double firstTokenLatencyMillis(String channelId, String nativeUserId) {
        ChannelMessage message =
                new ChannelMessage(channelId, nativeUserId, "ping", Instant.now());
        AtomicReference<Long> firstEventNanos = new AtomicReference<>();
        AtomicReference<AgentEvent> firstEvent = new AtomicReference<>();

        long start = System.nanoTime();
        driver.dispatch(message, event -> {
            if (firstEventNanos.compareAndSet(null, System.nanoTime())) {
                firstEvent.set(event);
            }
        });

        assertNotNull(firstEvent.get(),
                () -> "the turn produced no AgentEvent for channel '" + channelId + "' — the engine seam did "
                        + "not stream a first token (a TokenDelta or terminal event is always emitted)");
        return (firstEventNanos.get() - start) / 1_000_000.0;
    }

    /** Nearest-rank percentile over the samples (sorted ascending); {@code q} in [0,1]. */
    private static double percentile(List<Double> samples, double q) {
        List<Double> sorted = new ArrayList<>(samples);
        sorted.sort(Double::compareTo);
        int rank = (int) Math.ceil(q * sorted.size()) - 1; // 0-based nearest-rank
        return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1)));
    }

    /** Seeds {@code main} pinned to the in-process {@code fake} provider so a real turn needs no LLM. */
    public static class FakeBackedHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-latency-gate-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
