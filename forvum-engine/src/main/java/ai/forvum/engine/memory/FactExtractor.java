package ai.forvum.engine.memory;

import ai.forvum.core.ModelRef;
import ai.forvum.engine.routing.LlmSelector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts durable long-term facts from one completed turn with the small-and-fast model (#175). The
 * model half ({@link #extract}) is deliberately never on the turn's critical path — {@code MemoryWriter}
 * runs it off-turn — and never throws: a model or parse failure degrades to no facts (logged at WARN so an
 * always-failing async path is diagnosable, not silently mistaken for "no durable facts"). The parse half
 * ({@link #parse}) is a tolerant, reflection-free JSON tree-walk (no POJO binding), native-clean and
 * unit-testable without a model.
 */
@ApplicationScoped
public class FactExtractor {

    /** A durable fact the small model proposes to remember, as a stable {@code (key, value)} pair. */
    public record ExtractedFact(String key, String value) {
    }

    private static final Logger LOG = Logger.getLogger(FactExtractor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
            You extract durable, long-term facts worth remembering about the user from one conversation \
            turn. Return ONLY a compact JSON array of {"key","value"} objects: key is a short stable slug \
            (e.g. "user.name", "user.city", "pref.language"), value is the fact. ALWAYS capture an explicit \
            user request to remember something. Do NOT include ephemeral chit-chat, questions, or the \
            assistant's own phrasing. If nothing is durable, return []. Output the JSON array only, no prose.""";

    @Inject
    LlmSelector llmSelector;

    /**
     * Run the extraction model over one completed turn and return the durable facts it proposes. Never
     * throws — a model or parse failure yields an empty list so the write path degrades safely, and logs at
     * WARN so a persistently-failing extraction (bad model, transport, missing context) is diagnosable.
     */
    public List<ExtractedFact> extract(ModelRef modelRef, String agentId, String sessionId, String userText,
            String assistantText) {
        try {
            ChatModel model = llmSelector.resolve(modelRef, agentId, sessionId);
            String turn = "User said:\n" + userText + "\n\nAssistant replied:\n" + assistantText;
            String output = model.chat(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(turn))
                    .aiMessage().text();
            return parse(output);
        } catch (RuntimeException e) {
            LOG.warnf("Fact extraction failed for agent '%s' (model %s): %s — no facts written this turn.",
                    agentId, modelRef, e.getMessage());
            return List.of();
        }
    }

    /**
     * Parse the model's output into facts. Tries the whole de-fenced output first (the model is instructed
     * to return the array only), then falls back to a bracket-balanced object-array slice so surrounding
     * prose containing a stray {@code [} cannot corrupt the extraction and silently drop valid facts.
     * Malformed JSON or no array yields an empty list — never an exception.
     */
    static List<ExtractedFact> parse(String modelOutput) {
        if (modelOutput == null) {
            return List.of();
        }
        List<ExtractedFact> whole = tryParseArray(stripFences(modelOutput.trim()));
        if (whole != null) {
            return whole;
        }
        String sliced = sliceObjectArray(modelOutput);
        List<ExtractedFact> fallback = sliced == null ? null : tryParseArray(sliced);
        return fallback == null ? List.of() : fallback;
    }

    /** Parse {@code json} as an array of {@code {key,value}} objects, or {@code null} if not a well-formed array. */
    private static List<ExtractedFact> tryParseArray(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
        if (root == null || !root.isArray()) {
            return null;
        }
        List<ExtractedFact> facts = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode key = node.get("key");
            JsonNode value = node.get("value");
            if (key != null && value != null && !key.asText().isBlank() && !value.asText().isBlank()) {
                facts.add(new ExtractedFact(key.asText().strip(), value.asText().strip()));
            }
        }
        return facts;
    }

    /** Strip a wrapping markdown code fence ({@code ```json ... ```}) if the whole output is fenced. */
    private static String stripFences(String text) {
        String stripped = text;
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            if (firstNewline >= 0) {
                stripped = stripped.substring(firstNewline + 1);
            }
            int lastFence = stripped.lastIndexOf("```");
            if (lastFence >= 0) {
                stripped = stripped.substring(0, lastFence);
            }
        }
        return stripped.trim();
    }

    /**
     * Locate a bracket-balanced JSON array of objects: the first {@code [} that (after whitespace) opens
     * with {@code {} or {@code ]} (an empty array), balanced with string/escape awareness so brackets inside
     * prose or string values never mislead the match. Returns {@code null} if none is found.
     */
    private static String sliceObjectArray(String text) {
        for (int start = text.indexOf('['); start >= 0; start = text.indexOf('[', start + 1)) {
            int next = start + 1;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            if (next >= text.length()) {
                return null;
            }
            char opener = text.charAt(next);
            if (opener != '{' && opener != ']') {
                continue; // a stray '[' in prose, not an object array — keep looking
            }
            String slice = bracketMatch(text, start);
            if (slice != null) {
                return slice;
            }
        }
        return null;
    }

    /** The substring from the {@code [} at {@code start} to its matching {@code ]}, string/escape aware. */
    private static String bracketMatch(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
