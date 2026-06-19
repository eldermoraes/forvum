package ai.forvum.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import org.junit.jupiter.api.Test;

/** {@link LlmJudge} verdict parsing against a scripted model (no live LLM, P3-10 #58 / Risk #10). */
class LlmJudgeTest {

    private static ChatModel scripted(String answer) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
            }
        };
    }

    private static final EvalScenario SCENARIO =
            new EvalScenario("s", "prompt", "expectation", MatchMode.CONTAINS);

    @Test
    void labelEchoesTheJudgeRef() {
        assertEquals("llm:ollama:x", new LlmJudge("llm:ollama:x", scripted("PASS")).label());
    }

    @Test
    void passWhenTheModelSaysPass() {
        assertTrue(new LlmJudge("llm", scripted("PASS")).judge(SCENARIO, "reply").passed());
        assertTrue(new LlmJudge("llm", scripted("  pass, it is fine")).judge(SCENARIO, "reply").passed());
    }

    @Test
    void failWhenTheModelSaysFail() {
        assertFalse(new LlmJudge("llm", scripted("FAIL")).judge(SCENARIO, "reply").passed());
    }

    @Test
    void firstTokenWinsWhenBothAppear() {
        assertTrue(new LlmJudge("llm", scripted("PASS not FAIL")).judge(SCENARIO, "reply").passed());
        assertFalse(new LlmJudge("llm", scripted("FAIL not PASS")).judge(SCENARIO, "reply").passed());
    }

    @Test
    void anAmbiguousAnswerFailsRatherThanSilentlyPasses() {
        EvalJudge.Verdict verdict = new LlmJudge("llm", scripted("maybe?")).judge(SCENARIO, "reply");
        assertFalse(verdict.passed());
        assertTrue(verdict.reason().contains("no PASS/FAIL"));
    }

    @Test
    void aBlankAnswerFails() {
        assertFalse(new LlmJudge("llm", scripted("")).judge(SCENARIO, "reply").passed());
    }
}
