package ai.forvum.engine.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.FallbackTriggered;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * A LangChain4j {@link ChatModel} decorator that walks a fallback chain: it tries each {@link
 * FallbackLink} in turn, records a {@code provider_calls} row per attempt, and advances on any
 * provider-level failure — re-throwing only request-level failures ({@link
 * dev.langchain4j.exception.InvalidRequestException}) which fail on every provider (ULTRAPLAN
 * section 5.4). Stateless per call, no {@code synchronized}; runs on the caller's (virtual) thread.
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
    private final Tracer tracer;

    /** No-tracing form (the historical 6-arg shape) — used by unit tests; delegates with a null tracer. */
    public FallbackChatModel(List<FallbackLink> links, String sessionId, String agentId,
            FailureClassifier classifier, ProviderCallRecorder recorder, Consumer<AgentEvent> onEvent) {
        this(links, sessionId, agentId, classifier, recorder, onEvent, null);
    }

    /**
     * @param tracer the OpenTelemetry tracer for the {@code forvum.llm.call} span (P2-15 #40); {@code null}
     *               skips span creation (no telemetry — the unit-test path). The {@code LlmSelector} passes
     *               the CDI-injected tracer.
     */
    public FallbackChatModel(List<FallbackLink> links, String sessionId, String agentId,
            FailureClassifier classifier, ProviderCallRecorder recorder, Consumer<AgentEvent> onEvent,
            Tracer tracer) {
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
        this.tracer = tracer;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (tracer == null) {
            return doChat(request, null);
        }
        // §3.6 baseline: one forvum.llm.call span per model invocation (covering any fallback hops). A no-op
        // span when the SDK is disabled (the default), so this adds ~nothing on the zero-config path.
        Span span = tracer.spanBuilder("forvum.llm.call").startSpan();
        span.setAttribute("forvum.model", links.get(0).ref().toString());
        span.setAttribute("thread.is_virtual", Thread.currentThread().isVirtual());
        try (Scope scope = span.makeCurrent()) {
            return doChat(request, span);
        } catch (RuntimeException e) {
            // The chain was exhausted (or hit a request-level failure) — mark the span so a trace shows
            // the failed model call, not a silently-OK span.
            span.setStatus(StatusCode.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** The fallback chain walk; records a {@code provider_calls} row per attempt. {@code span} may be null. */
    private ChatResponse doChat(ChatRequest request, Span span) {
        for (int i = 0; i < links.size(); i++) {
            FallbackLink link = links.get(i);
            boolean fallback = i > 0;
            long start = System.nanoTime();
            try {
                ChatResponse response = link.chat().chat(request);
                recorder.record(ProviderCalls.success(sessionId, agentId, link, fallback, response,
                        millisSince(start)));
                if (span != null && fallback) {
                    span.setAttribute("forvum.fallback", true);
                    span.setAttribute("forvum.model.used", link.ref().toString());
                }
                return response;
            } catch (RuntimeException e) {
                recorder.record(ProviderCalls.failure(sessionId, agentId, link, fallback, e,
                        millisSince(start)));
                boolean hasNext = i < links.size() - 1;
                if (classifier.shouldFallback(e) && hasNext) {
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
