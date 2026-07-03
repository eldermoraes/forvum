package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deterministic in-process {@link ai.forvum.sdk.ModelProvider} (extension id {@code tokencounting})
 * whose model answers {@code "pong"} reporting a fixed {@code TokenUsage(5, 5)} — so the ledgered turn
 * costs exactly 10 tokens — and counts every invocation in {@link #CALLS}. The #169 budget ITs read the
 * counter to prove the hard stop fires BEFORE the next provider call (a blocked turn must not increment
 * it). Reset the counter per test; the provider itself is stateless across turns.
 */
@ApplicationScoped
public class TokenCountingModelProvider extends AbstractModelProvider {

    /** Total chat invocations across every resolved model instance — the "no extra call" observation. */
    public static final AtomicInteger CALLS = new AtomicInteger();

    @Override
    public String extensionId() {
        return "tokencounting";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                CALLS.incrementAndGet();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("pong"))
                        .tokenUsage(new TokenUsage(5, 5))
                        .build();
            }
        };
    }
}
