package ai.forvum.app.qa;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * A deterministic, network-free {@link ai.forvum.sdk.ModelProvider} (extension id {@code echo}) bundled
 * into the production binary so the QA scenario suite ({@code forvum qa}) can drive real turns through the
 * full turn path — graph, ledger, channel — with NO live inference, on the JVM jar AND the native binary.
 * The forvum-app test {@code FakeModelProvider} is test-scope only (absent from the native image), so the
 * offline native QA gate (P2-QA {@code [NATIVE]}) needs a provider that ships in {@code src/main}; this is
 * the minimal one.
 *
 * <p>It is INERT: like the bundled-but-inert Qdrant memory provider, it is never the active model just by
 * being on the classpath — it only resolves when an agent pins {@code echo:<model>}. Production agents pin a
 * real provider ({@code ollama:}/{@code openai:}/…), so {@code echo} is exercised only by a QA pack that
 * seeds {@code primaryModel: "echo:<model>"}.
 *
 * <p>The reply is the last user message verbatim, prefixed with {@code "echo: "} (a stable transform the QA
 * expectations assert against: {@code exact} → {@code "echo: <input>"}, {@code contains} → the input,
 * {@code regex} → a pattern over the prefixed text). Deterministic and stateless across turns.
 */
@ApplicationScoped
public class EchoModelProvider extends AbstractModelProvider {

    /** Stable prefix on every echoed reply, so a QA {@code exact} expectation is predictable. */
    static final String PREFIX = "echo: ";

    @Override
    public String extensionId() {
        return "echo";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(PREFIX + lastUserText(request.messages())))
                        .build();
            }
        };
    }

    /** The most recent {@link UserMessage}'s text, or empty if the request carries none. */
    private static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user && user.hasSingleText()) {
                return user.singleText();
            }
        }
        return "";
    }
}
