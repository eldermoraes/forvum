package ai.forvum.engine.eval;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.Locale;

/**
 * The opt-in LLM judge (P3-10 #58, Risk #10): scores a reply with a cheap local model (e.g. a small
 * Ollama model) asked a yes/no question about whether the reply satisfies the scenario's expectation. It
 * is selected ONLY when a suite declares an explicit {@code judge} ref — it is never the default and is
 * never wired into a normal turn, so the harness stays offline in CI by default.
 *
 * <p>The model's verdict is parsed leniently: the first {@code PASS}/{@code FAIL} token in the reply
 * decides; an unparseable answer is treated as a FAIL with the raw text as the reason, so a confused
 * judge degrades to "not proven to pass" rather than silently passing. Judge-vs-human agreement should
 * be measured and the judge replaced if it falls below 0.7 (Risk #10).
 */
public final class LlmJudge implements EvalJudge {

    private static final String SYSTEM = """
            You are a strict evaluation judge. You are given an EXPECTATION and a REPLY.
            Answer with a single word: PASS if the reply satisfies the expectation, otherwise FAIL.
            Do not explain.""";

    private final String label;
    private final ChatModel model;

    public LlmJudge(String label, ChatModel model) {
        this.label = label;
        this.model = model;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Verdict judge(EvalScenario scenario, String reply) {
        String safeReply = reply == null ? "" : reply;
        String prompt = SYSTEM
                + "\n\nEXPECTATION:\n" + scenario.expect()
                + "\n\nREPLY:\n" + safeReply
                + "\n\nVerdict (PASS or FAIL):";
        String answer = model.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build()).aiMessage().text();
        return parse(answer);
    }

    /** Lenient parse: first PASS/FAIL token wins; an ambiguous answer fails (never silently passes). */
    private static Verdict parse(String answer) {
        String upper = (answer == null ? "" : answer).toUpperCase(Locale.ROOT);
        int pass = upper.indexOf("PASS");
        int fail = upper.indexOf("FAIL");
        if (pass >= 0 && (fail < 0 || pass < fail)) {
            return new Verdict(true, "judge: PASS");
        }
        if (fail >= 0) {
            return new Verdict(false, "judge: FAIL");
        }
        String trimmed = answer == null || answer.isBlank() ? "(blank)" : answer.strip();
        return new Verdict(false, "judge gave no PASS/FAIL: " + trimmed);
    }
}
