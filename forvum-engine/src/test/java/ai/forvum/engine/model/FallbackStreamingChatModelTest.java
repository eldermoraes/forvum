package ai.forvum.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import ai.forvum.core.ModelRef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link FallbackStreamingChatModel} against mock {@link StreamingChatModel}s. */
class FallbackStreamingChatModelTest {

    private final FailureClassifier classifier = new FailureClassifier();

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hi")).build();
    }

    private static StreamingChatModel erroring(RuntimeException e) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest r, StreamingChatResponseHandler h) {
                h.onError(e);
            }
        };
    }

    private static StreamingChatModel streamingOnce(String token, ChatResponse complete) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest r, StreamingChatResponseHandler h) {
                h.onPartialResponse(token);
                h.onCompleteResponse(complete);
            }
        };
    }

    private static StreamingChatModel partialThenError(String token, RuntimeException e) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest r, StreamingChatResponseHandler h) {
                h.onPartialResponse(token);
                h.onError(e);
            }
        };
    }

    @Test
    void retriesOnRetryableErrorWithoutSurfacingItToTheUser() {
        var recorder = new InMemoryProviderCallRecorder();
        var partials = new ArrayList<String>();
        var errors = new ArrayList<Throwable>();
        var completed = new ArrayList<ChatResponse>();

        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), null, erroring(new RateLimitException("limit")));
        var done = ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), null, streamingOnce("tok", done));
        var model = new FallbackStreamingChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, null);

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                partials.add(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completed.add(response);
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        });

        assertEquals(List.of("tok"), partials);
        assertEquals(1, completed.size());
        assertTrue(errors.isEmpty(), "a retryable error must not reach the user handler");
        assertEquals(2, recorder.calls.size());
        assertTrue(recorder.calls.get(1).fallback());
    }

    @Test
    void authFailureAdvancesToNextProvider() {
        // AuthenticationException is provider-level: the chain must fall through to the next link.
        var recorder = new InMemoryProviderCallRecorder();
        var partials = new ArrayList<String>();
        var completed = new ArrayList<ChatResponse>();
        var errors = new ArrayList<Throwable>();

        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), null, erroring(new AuthenticationException("bad")));
        var fallbackResp = ChatResponse.builder().aiMessage(AiMessage.from("y")).build();
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), null, streamingOnce("tok", fallbackResp));
        var model = new FallbackStreamingChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, null);

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                partials.add(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completed.add(response);
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        });

        assertTrue(errors.isEmpty(), "auth failure must not reach the user handler — chain advances");
        assertEquals(List.of("tok"), partials);
        assertEquals(1, completed.size());
        assertEquals(2, recorder.calls.size(), "one anthropic failure row + one ollama success row");
        assertTrue(recorder.calls.get(1).fallback());
    }

    @Test
    void invalidRequestErrorReachesTheUser() {
        // InvalidRequestException is request-level: re-throw without advancing; must reach the user.
        var recorder = new InMemoryProviderCallRecorder();
        var errors = new ArrayList<Throwable>();

        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), null,
                erroring(new InvalidRequestException("bad request")));
        var fallbackResp = ChatResponse.builder().aiMessage(AiMessage.from("y")).build();
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), null, streamingOnce("x", fallbackResp));
        var model = new FallbackStreamingChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, null);

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse response) {
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        });

        assertEquals(1, errors.size(), "request-level failure must surface to the user");
        assertInstanceOf(InvalidRequestException.class, errors.get(0));
        assertEquals(1, recorder.calls.size(), "only the primary failure row — no secondary attempt");
    }

    @Test
    void retryableErrorAfterAPartialTokenCommitsTheAttemptAndSurfacesTheError() {
        var recorder = new InMemoryProviderCallRecorder();
        var partials = new ArrayList<String>();
        var errors = new ArrayList<Throwable>();
        var secondaryInvoked = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Primary emits a token, THEN errors retryably: the attempt is already committed, so the error
        // must surface to the user and the chain must NOT advance (advancing would re-emit tokens).
        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), null,
                partialThenError("partial", new RateLimitException("limit")));
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), null, new StreamingChatModel() {
            @Override
            public void chat(ChatRequest r, StreamingChatResponseHandler h) {
                secondaryInvoked.set(true);
                h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("should-not-run")).build());
            }
        });
        var model = new FallbackStreamingChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, null);

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                partials.add(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        });

        assertEquals(List.of("partial"), partials);
        assertEquals(1, errors.size(), "a retryable error after a partial token must surface, not retry");
        assertInstanceOf(RateLimitException.class, errors.get(0));
        assertFalse(secondaryInvoked.get(), "the chain must not advance once a token was emitted");
        assertEquals(1, recorder.calls.size());
    }

    @Test
    void allLinksFailingRetryablySurfacesTheLastError() {
        var recorder = new InMemoryProviderCallRecorder();
        var errors = new ArrayList<Throwable>();

        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), null, erroring(new RateLimitException("first")));
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"), null, erroring(new RateLimitException("second")));
        var model = new FallbackStreamingChatModel(List.of(primary, secondary), "s", "a", classifier, recorder, null);

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse response) {
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        });

        assertEquals(1, errors.size(), "the last link's error surfaces when the whole chain fails");
        assertInstanceOf(RateLimitException.class, errors.get(0));
        assertEquals(2, recorder.calls.size());
        assertTrue(recorder.calls.get(1).fallback());
    }
}
