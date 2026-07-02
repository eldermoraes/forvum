package ai.forvum.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import ai.forvum.core.ModelRef;
import ai.forvum.core.budget.BudgetExhaustedException;
import ai.forvum.core.budget.BudgetMeter;
import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.ExhaustionCause;
import ai.forvum.core.budget.SessionWindow;
import ai.forvum.core.budget.Spend;
import ai.forvum.core.budget.Usage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.FallbackReasons;
import ai.forvum.core.event.FallbackTriggered;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Unit tests for {@link FallbackChatModel} against mock {@link ChatModel}s — no database. */
class FallbackChatModelTest {

    private final FailureClassifier classifier = new FailureClassifier();

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hi")).build();
    }

    private static ChatModel throwing(RuntimeException e) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest r) {
                throw e;
            }
        };
    }

    private static ChatModel returning(ChatResponse response) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest r) {
                return response;
            }
        };
    }

    private static ChatResponse response(int in, int out) {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(new TokenUsage(in, out)).build();
    }

    @Test
    void retriesToNextLinkRecordsTwoRowsAndEmitsFallbackTriggered() {
        var recorder = new InMemoryProviderCallRecorder();
        var events = new ArrayList<AgentEvent>();
        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), throwing(new RateLimitException("limit")), null);
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), returning(response(10, 20)), null);
        var model = new FallbackChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, events::add);

        ChatResponse out = model.chat(request());

        assertEquals(20, out.tokenUsage().outputTokenCount());
        assertEquals(2, recorder.calls.size());
        assertFalse(recorder.calls.get(0).fallback());
        assertEquals(RateLimitException.class.getName(), recorder.calls.get(0).error());
        assertTrue(recorder.calls.get(1).fallback());
        assertNull(recorder.calls.get(1).error());
        assertEquals(20L, recorder.calls.get(1).tokensOut());

        assertEquals(1, events.size());
        FallbackTriggered triggered = assertInstanceOf(FallbackTriggered.class, events.get(0));
        assertEquals(FallbackReasons.RATE_LIMIT, triggered.reason());
        assertEquals("ollama", triggered.next().provider());
    }

    @Test
    void authFailureAdvancesToNextProvider() {
        // AuthenticationException is provider-level (bad key on this provider, not a bad request):
        // the chain must fall through to the next link, return its response, and record 2 rows.
        var recorder = new InMemoryProviderCallRecorder();
        var events = new ArrayList<AgentEvent>();
        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), throwing(new AuthenticationException("bad key")), null);
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), returning(response(1, 2)), null);
        var model = new FallbackChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, events::add);

        ChatResponse out = model.chat(request());

        assertEquals(2, out.tokenUsage().outputTokenCount());
        assertEquals(2, recorder.calls.size(), "one anthropic failure row + one ollama success row");
        assertFalse(recorder.calls.get(0).fallback());
        assertEquals(AuthenticationException.class.getName(), recorder.calls.get(0).error());
        assertTrue(recorder.calls.get(1).fallback());
        assertNull(recorder.calls.get(1).error());
        assertEquals(1, events.size(), "exactly one FallbackTriggered");
        FallbackTriggered triggered = assertInstanceOf(FallbackTriggered.class, events.get(0));
        assertEquals("ollama", triggered.next().provider());
    }

    @Test
    void unknownFailureAdvancesToNextProvider() {
        // Unknown RuntimeExceptions are provider-level (connection error, unexpected failure):
        // the chain must fall through to the next link.
        var recorder = new InMemoryProviderCallRecorder();
        var events = new ArrayList<AgentEvent>();
        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), throwing(new RuntimeException("weird")), null);
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), returning(response(1, 1)), null);
        var model = new FallbackChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, events::add);

        ChatResponse out = model.chat(request());

        assertEquals(1, out.tokenUsage().outputTokenCount());
        assertEquals(2, recorder.calls.size(), "one primary failure row + one secondary success row");
        assertNull(recorder.calls.get(1).error());
        assertEquals(1, events.size(), "exactly one FallbackTriggered");
    }

    @Test
    void invalidRequestStopsImmediatelyWithoutFallback() {
        // InvalidRequestException is request-level: a malformed request fails on every provider,
        // so the chain must re-throw without advancing and must not emit FallbackTriggered.
        var recorder = new InMemoryProviderCallRecorder();
        var events = new ArrayList<AgentEvent>();
        var primary = new FallbackLink(new ModelRef("anthropic", "claude"),
                throwing(new InvalidRequestException("bad request")), null);
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), returning(response(1, 1)), null);
        var model = new FallbackChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, events::add);

        assertThrows(InvalidRequestException.class, () -> model.chat(request()));
        assertEquals(1, recorder.calls.size(), "only the primary failure row — no secondary attempt");
        assertEquals(InvalidRequestException.class.getName(), recorder.calls.get(0).error());
        assertTrue(events.isEmpty(), "FallbackTriggered must NOT be emitted for a request-level failure");
    }

    @Test
    void firstLinkSuccessRecordsOneNonFallbackRow() {
        var recorder = new InMemoryProviderCallRecorder();
        var primary = new FallbackLink(new ModelRef("ollama", "qwen"), returning(response(5, 7)), null);
        var model = new FallbackChatModel(List.of(primary), "s", "a", classifier, recorder, null);

        model.chat(request());

        assertEquals(1, recorder.calls.size());
        assertFalse(recorder.calls.get(0).fallback());
        assertNull(recorder.calls.get(0).error());
        assertEquals(5L, recorder.calls.get(0).tokensIn());
    }

    @Test
    void wholeChainFailingRetryablyRethrowsTheLastErrorAndRecordsEveryAttempt() {
        var recorder = new InMemoryProviderCallRecorder();
        var events = new ArrayList<AgentEvent>();
        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), throwing(new RateLimitException("first")), null);
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), throwing(new RateLimitException("second")), null);
        var model = new FallbackChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, events::add);

        RateLimitException thrown = assertThrows(RateLimitException.class, () -> model.chat(request()));
        assertEquals("second", thrown.getMessage(), "the last link's failure is the one surfaced");
        assertEquals(2, recorder.calls.size());
        assertTrue(recorder.calls.get(1).fallback());
        assertEquals(1, events.size(), "exactly one FallbackTriggered (only the first->second advance)");
    }

    // ---- #169 Decision-8 pre-call budget gate ----------------------------------------------------

    private static Usage exhausted(ExhaustionCause cause) {
        return new Usage(new Spend(null, 10L), new Spend(null, 0L), true, cause);
    }

    private static Usage headroom() {
        return new Usage(new Spend(null, 0L), new Spend(null, 10L), false, null);
    }

    private static CostBudget anyBudget() {
        return new CostBudget(null, 10L, new SessionWindow("s", "a"));
    }

    /** Serves scripted {@link Usage} snapshots in order (the last one repeats), capturing agent ids. */
    private static final class ScriptedMeter implements BudgetMeter {
        final Deque<Usage> snapshots;
        final List<String> agentIds = new ArrayList<>();

        ScriptedMeter(Usage... usages) {
            this.snapshots = new ArrayDeque<>(List.of(usages));
        }

        @Override
        public Usage usage(CostBudget budget) {
            return usage(budget, null);
        }

        @Override
        public Usage usage(CostBudget budget, String agentId) {
            agentIds.add(agentId);
            return snapshots.size() > 1 ? snapshots.poll() : snapshots.peek();
        }
    }

    /** A link model that counts its invocations — the "no extra provider call" observation point. */
    private static final class CountingModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(ChatRequest r) {
            calls.incrementAndGet();
            return response(1, 1);
        }
    }

    @Test
    void exhaustedBudgetHardStopsBeforeTheFirstProviderCall() {
        var recorder = new InMemoryProviderCallRecorder();
        var events = new ArrayList<AgentEvent>();
        var meter = new ScriptedMeter(exhausted(ExhaustionCause.TOKEN_CAP_HIT));
        var counting = new CountingModel();
        var primary = new FallbackLink(new ModelRef("ollama", "qwen"), counting, null);
        UUID turnId = UUID.randomUUID();
        var model = new FallbackChatModel(List.of(primary), "s", "a", classifier, recorder,
                events::add, null, new BudgetGate(anyBudget(), meter, turnId));

        BudgetExhaustedException thrown =
                assertThrows(BudgetExhaustedException.class, () -> model.chat(request()));

        assertEquals(ExhaustionCause.TOKEN_CAP_HIT, thrown.cause());
        assertEquals(turnId, thrown.turnId());
        assertEquals(0, counting.calls.get(), "the hard stop must fire BEFORE any provider call");
        assertTrue(recorder.calls.isEmpty(), "no attempt happened, so no provider_calls row");
        assertEquals(List.of("a"), meter.agentIds,
                "the aggregation must be scoped to the decorator's own agent id (Decision 10)");
        FallbackTriggered triggered = assertInstanceOf(FallbackTriggered.class, events.get(0));
        assertEquals(FallbackReasons.COST_BUDGET, triggered.reason());
        assertNull(triggered.next(), "cost exhaustion short-circuits — there is no next link");
    }

    @Test
    void budgetWithHeadroomLetsTheCallThroughUnchanged() {
        var recorder = new InMemoryProviderCallRecorder();
        var events = new ArrayList<AgentEvent>();
        var counting = new CountingModel();
        var primary = new FallbackLink(new ModelRef("ollama", "qwen"), counting, null);
        var model = new FallbackChatModel(List.of(primary), "s", "a", classifier, recorder,
                events::add, null, new BudgetGate(anyBudget(), new ScriptedMeter(headroom()), null));

        model.chat(request());

        assertEquals(1, counting.calls.get());
        assertEquals(1, recorder.calls.size(), "a permitted attempt is still ledgered normally");
        assertTrue(events.isEmpty());
    }

    @Test
    void exhaustionBetweenAttemptsShortCircuitsTheFallbackChain() {
        // The check is PER ATTEMPT ("including failed calls and fallback attempts"): the primary's
        // failed call is ledgered, then the pre-fallback check sees exhaustion and no further link runs.
        var recorder = new InMemoryProviderCallRecorder();
        var events = new ArrayList<AgentEvent>();
        var meter = new ScriptedMeter(headroom(), exhausted(ExhaustionCause.USD_CAP_HIT));
        var secondary = new CountingModel();
        var links = List.of(
                new FallbackLink(new ModelRef("anthropic", "claude"), throwing(new RateLimitException("limit")), null),
                new FallbackLink(new ModelRef("ollama", "qwen"), secondary, null));
        var model = new FallbackChatModel(links, "s", "a", classifier, recorder, events::add, null,
                new BudgetGate(anyBudget(), meter, null));

        BudgetExhaustedException thrown =
                assertThrows(BudgetExhaustedException.class, () -> model.chat(request()));

        assertEquals(ExhaustionCause.USD_CAP_HIT, thrown.cause());
        assertEquals(0, secondary.calls.get(), "the fallback link must never be attempted");
        assertEquals(1, recorder.calls.size(), "only the primary's failed attempt is ledgered");
        assertEquals(RateLimitException.class.getName(), recorder.calls.get(0).error());
        assertEquals(2, events.size(), "the rate-limit advance, then the cost short-circuit");
        assertEquals(FallbackReasons.RATE_LIMIT, ((FallbackTriggered) events.get(0)).reason());
        assertEquals(FallbackReasons.COST_BUDGET, ((FallbackTriggered) events.get(1)).reason());
    }

    @Test
    void budgetGateRequiresBothBudgetAndMeter() {
        assertThrows(IllegalArgumentException.class, () -> new BudgetGate(null, new ScriptedMeter(headroom()), null));
        assertThrows(IllegalArgumentException.class, () -> new BudgetGate(anyBudget(), null, null));
    }
}
