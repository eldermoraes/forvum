package ai.forvum.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.persistence.PanacheProviderCallRecorder;
import ai.forvum.engine.persistence.PersistenceTestHomeProfile;
import ai.forvum.engine.persistence.ProviderCallEntity;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The M8 Verify (ULTRAPLAN section 7.1): a fallback chain whose first link throws RateLimit and second
 * returns must write two {@code provider_calls} rows, the second with {@code is_fallback = 1}. Uses the
 * real Panache recorder; Surefire-run (headless library).
 */
@QuarkusTest
@TestProfile(PersistenceTestHomeProfile.class)
class ProviderCallPersistenceIT {

    @Inject
    PanacheProviderCallRecorder recorder;

    @Inject
    FailureClassifier classifier;

    @Inject
    EntityManager em;

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

    @Test
    @Transactional
    void fallbackChainWritesTwoRowsSecondFlaggedFallback() {
        ProviderCallEntity.deleteAll();
        em.flush();

        var primary = new FallbackLink(new ModelRef("anthropic", "claude"),
                throwing(new RateLimitException("limit")), null);
        var secondary = new FallbackLink(new ModelRef("ollama", "qwen"),
                returning(ChatResponse.builder().aiMessage(AiMessage.from("ok"))
                        .tokenUsage(new TokenUsage(10, 20)).build()), null);
        var model = new FallbackChatModel(List.of(primary, secondary), "sess-1", "main",
                classifier, recorder, null);

        model.chat(request());
        em.flush();

        Number count = (Number) em.createNativeQuery("select count(*) from provider_calls").getSingleResult();
        assertEquals(2L, count.longValue());

        @SuppressWarnings("unchecked")
        List<Number> flags = em.createNativeQuery(
                "select is_fallback from provider_calls order by id").getResultList();
        assertEquals(0, flags.get(0).intValue(), "first call is not a fallback");
        assertEquals(1, flags.get(1).intValue(), "second call is the fallback");
    }
}
