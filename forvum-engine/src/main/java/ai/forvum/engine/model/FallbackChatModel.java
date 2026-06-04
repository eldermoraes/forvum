package ai.forvum.engine.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.FallbackTriggered;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * A LangChain4j {@link ChatModel} decorator that walks a fallback chain: it tries each {@link
 * FallbackLink} in turn, records a {@code provider_calls} row per attempt, and advances only on a
 * {@link FailureClass#isRetryable() retryable} failure — surfacing non-retryable/unknown failures
 * immediately (ULTRAPLAN section 5.4). Stateless per call, no {@code synchronized}; runs on the
 * caller's (virtual) thread.
 *
 * <p>Only the main entry point {@code chat(ChatRequest)} is decorated — the path every AI-service and
 * convenience caller routes through. The 1.13+ {@code chat(ChatRequest, ChatRequestOptions)} overload
 * bypasses it (it calls {@code doChat} directly); override {@code doChat} here if that overload is ever
 * adopted.
 */
public final class FallbackChatModel implements ChatModel {

    private final List<FallbackLink> links;
    private final String sessionId;
    private final String agentId;
    private final FailureClassifier classifier;
    private final ProviderCallRecorder recorder;
    private final Consumer<AgentEvent> onEvent;

    public FallbackChatModel(List<FallbackLink> links, String sessionId, String agentId,
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
    public ChatResponse chat(ChatRequest request) {
        for (int i = 0; i < links.size(); i++) {
            FallbackLink link = links.get(i);
            boolean fallback = i > 0;
            long start = System.nanoTime();
            try {
                ChatResponse response = link.chat().chat(request);
                recorder.record(ProviderCalls.success(sessionId, agentId, link, fallback, response,
                        millisSince(start)));
                return response;
            } catch (RuntimeException e) {
                recorder.record(ProviderCalls.failure(sessionId, agentId, link, fallback, e,
                        millisSince(start)));
                boolean hasNext = i < links.size() - 1;
                if (classifier.classify(e).isRetryable() && hasNext) {
                    onEvent.accept(new FallbackTriggered(Instant.now(), link.ref(),
                            links.get(i + 1).ref(), classifier.reason(e)));
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("unreachable: a non-empty fallback chain returns or throws");
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
