package ai.forvum.engine.media;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.routing.LlmSelector;
import ai.forvum.sdk.MediaAnalysis;
import ai.forvum.sdk.MediaPayload;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The engine implementation of the #185 {@link MediaAnalysis} seam — the backend for the model-callable
 * {@code image.analyze} / {@code pdf.analyze} tool ({@code forvum-tools-multimodal}). It resolves the
 * vision model (an explicit {@code tools/multimodal.json} {@code model} override, else the current agent's
 * primary model — read lease-aware via the #178 {@code CURRENT_AGENT_SPEC} generation, so a mid-turn reload
 * cannot mix generations) and drives it through the budget-gated (#169), {@code provider_calls}-ledgered,
 * OTel-spanned {@link LlmSelector#resolve(ModelRef, String, String, ai.forvum.core.budget.CostBudget)} path
 * — never a raw {@code ModelProvider}, which would bypass the budget gate, the ledger, and the
 * primary-model default.
 *
 * <p>The LangChain4j media content types stay entirely here: the tool module hands over raw bytes in a
 * {@link MediaPayload}, and this seam base64-encodes them and wraps them in {@link ImageContent} /
 * {@link PdfFileContent}, so the tool module carries zero AI-library dependency.
 *
 * <p>Runs inside a turn, on the turn's virtual thread, so {@code CURRENT_AGENT} is bound; the resolved
 * budget is the agent's, so a sub-generation consumes the cost budget exactly like any other model call.
 */
@ApplicationScoped
public class EngineMediaAnalysis implements MediaAnalysis {

    /**
     * Providers whose LangChain4j content mapper serializes native PDF content ({@code PdfFileContent}),
     * re-byte-scanned on the 1.16.2 jars: anthropic ({@code AnthropicMapper}), google-ai-gemini
     * ({@code PartsAndContentsMapper}), open-ai ({@code OpenAiUtils}). Ollama maps {@code ImageContent}
     * only (no PDF); copilot is conservatively excluded pending endpoint verification. A PDF for a
     * non-native provider falls back to local text extraction in the tool.
     */
    static final Set<String> PDF_NATIVE_PROVIDERS = Set.of("anthropic", "google", "openai");

    /** Synthetic session id for the sub-generation (no session ScopedValue on the tool path), mirroring
     *  {@code EngineMemoryAccess}. */
    private static final String MEDIA_SESSION = "tool:media.analyze";

    @Inject
    LlmSelector llmSelector;

    @Inject
    AgentRegistry registry;

    @Override
    public boolean acceptsMedia(String mimeType, String modelRefOverride) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String mime = mimeType.toLowerCase(Locale.ROOT).strip();
        if (mime.startsWith("image/")) {
            return true; // every installed chat provider's content mapper handles ImageContent (1.16.2).
        }
        if (mime.equals("application/pdf")) {
            return PDF_NATIVE_PROVIDERS.contains(resolveRef(modelRefOverride).provider());
        }
        return false;
    }

    @Override
    public String analyze(String prompt, List<MediaPayload> media, String modelRefOverride) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("media.analyze requires a non-blank prompt.");
        }
        AgentId agentId = CurrentAgent.CURRENT_AGENT.get();
        Persona persona = registry.persona(agentId);
        ModelRef ref = modelRefOverride != null && !modelRefOverride.isBlank()
                ? ModelRef.parse(modelRefOverride)
                : persona.primaryModel();

        // An empty media list is the PDF text-extraction fallback: the extracted text rides in the prompt,
        // so the sub-generation is a plain text call (no ImageContent/PdfFileContent).
        List<MediaPayload> payloads = media == null ? List.of() : media;
        List<Content> contents = new ArrayList<>(payloads.size() + 1);
        contents.add(TextContent.from(prompt));
        for (MediaPayload payload : payloads) {
            String base64 = Base64.getEncoder().encodeToString(payload.data());
            if (payload.mimeType().toLowerCase(Locale.ROOT).startsWith("image/")) {
                contents.add(ImageContent.from(base64, payload.mimeType()));
            } else {
                contents.add(PdfFileContent.from(base64, payload.mimeType()));
            }
        }

        ChatModel model = llmSelector.resolve(ref, agentId.value(), MEDIA_SESSION, persona.costBudget());
        try {
            String reply = model.chat(ChatRequest.builder().messages(UserMessage.from(contents)).build())
                    .aiMessage().text();
            return reply == null ? "" : reply;
        } catch (UnsupportedFeatureException | InvalidRequestException e) {
            // The resolved model cannot process the media (a non-vision model / a provider 4xx rejecting the
            // content) — surface an actionable error naming the ref + the config knob. Budget exhaustion
            // (#169) and other failures are NOT caught here, so they propagate unchanged.
            throw new IllegalStateException(
                    "The model '" + ref + "' cannot analyze the supplied media — it is not vision-capable. "
                  + "Point image.analyze/pdf.analyze at a vision model via the \"model\" field in "
                  + "~/.forvum/tools/multimodal.json (e.g. \"ollama:llava\"), or set the agent's primary "
                  + "model to one. Cause: " + e.getMessage(), e);
        }
    }

    /** Resolve the ModelRef this turn would use — the override, else the leased persona's primary model. */
    private ModelRef resolveRef(String modelRefOverride) {
        if (modelRefOverride != null && !modelRefOverride.isBlank()) {
            return ModelRef.parse(modelRefOverride);
        }
        return registry.persona(CurrentAgent.CURRENT_AGENT.get()).primaryModel();
    }
}
