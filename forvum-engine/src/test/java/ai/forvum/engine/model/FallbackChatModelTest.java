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
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.FallbackReasons;
import ai.forvum.core.event.FallbackTriggered;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
