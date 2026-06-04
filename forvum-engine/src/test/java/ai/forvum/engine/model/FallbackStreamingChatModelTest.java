package ai.forvum.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
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
    void nonRetryableErrorReachesTheUser() {
        var recorder = new InMemoryProviderCallRecorder();
        var errors = new ArrayList<Throwable>();

        var primary = new FallbackLink(new ModelRef("anthropic", "claude"), null, erroring(new AuthenticationException("bad")));
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

        assertEquals(1, errors.size());
        assertInstanceOf(AuthenticationException.class, errors.get(0));
        assertEquals(1, recorder.calls.size());
    }
}
