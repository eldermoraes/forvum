package ai.forvum.engine.model;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.FallbackTriggered;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streaming counterpart of {@link FallbackChatModel}. On a provider-level {@code onError} it advances
 * to the next link without surfacing the error — but only if no partial tokens have already reached the
 * user handler (once a stream starts emitting, the attempt is committed). Request-level failures
 * ({@link dev.langchain4j.exception.InvalidRequestException}) are surfaced immediately. Records a
 * {@code provider_calls} row per attempt. No {@code synchronized}.
 *
 * <p>Only {@code chat(ChatRequest, handler)} is decorated; the 1.13+ {@code ChatRequestOptions} overload
 * bypasses the fallback (override {@code doChat} if it is ever adopted).
 */
public final class FallbackStreamingChatModel implements StreamingChatModel {

    private final List<FallbackLink> links;
    private final String sessionId;
    private final String agentId;
    private final FailureClassifier classifier;
    private final ProviderCallRecorder recorder;
    private final Consumer<AgentEvent> onEvent;

    public FallbackStreamingChatModel(List<FallbackLink> links, String sessionId, String agentId,
            FailureClassifier classifier, ProviderCallRecorder recorder, Consumer<AgentEvent> onEvent) {
        if (links == null || links.isEmpty()) {
            throw new IllegalArgumentException("A fallback chain must have at least one link");
        }
        this.links = List.copyOf(links);
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.classifier = classifier;
        this.recorder = recorder;
        this.onEvent = onEvent != null ? onEvent : event -> {
        };
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        attempt(0, request, handler);
    }

    private void attempt(int index, ChatRequest request, StreamingChatResponseHandler user) {
        FallbackLink link = links.get(index);
        boolean fallback = index > 0;
        long start = System.nanoTime();
        AtomicBoolean partialsEmitted = new AtomicBoolean(false);

        link.streaming().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                partialsEmitted.set(true);
                user.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                recorder.record(ProviderCalls.success(sessionId, agentId, link, fallback, response,
                        millisSince(start)));
                user.onCompleteResponse(response);
            }

            @Override
            public void onError(Throwable error) {
                recorder.record(ProviderCalls.failure(sessionId, agentId, link, fallback, error,
                        millisSince(start)));
                boolean hasNext = index < links.size() - 1;
                if (classifier.shouldFallback(error) && hasNext && !partialsEmitted.get()) {
                    onEvent.accept(new FallbackTriggered(Instant.now(), link.ref(),
                            links.get(index + 1).ref(), classifier.reason(error)));
                    attempt(index + 1, request, user);
                } else {
                    user.onError(error);
                }
            }
        });
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
