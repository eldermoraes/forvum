package ai.forvum.app.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.core.ModelRef;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import org.junit.jupiter.api.Test;

/** Unit tests for the deterministic, network-free {@code echo} provider that backs the offline QA suite. */
class EchoModelProviderTest {

    private final ChatModel model = new EchoModelProvider().resolve(ModelRef.parse("echo:test-model"));

    @Test
    void extensionIdIsEcho() {
        assertEquals("echo", new EchoModelProvider().extensionId());
    }

    @Test
    void echoesTheLastUserMessagePrefixed() {
        String reply = model.chat(ChatRequest.builder()
                .messages(UserMessage.from("hello world"))
                .build()).aiMessage().text();
        assertEquals("echo: hello world", reply);
    }

    @Test
    void echoesTheMostRecentUserMessageIgnoringSystemAndEarlierTurns() {
        String reply = model.chat(ChatRequest.builder()
                .messages(SystemMessage.from("you are a bot"),
                        UserMessage.from("first"),
                        UserMessage.from("latest"))
                .build()).aiMessage().text();
        assertEquals("echo: latest", reply);
    }

    @Test
    void noUserMessageEchoesTheBarePrefix() {
        String reply = model.chat(ChatRequest.builder()
                .messages(SystemMessage.from("only system"))
                .build()).aiMessage().text();
        assertEquals("echo: ", reply);
    }
}
