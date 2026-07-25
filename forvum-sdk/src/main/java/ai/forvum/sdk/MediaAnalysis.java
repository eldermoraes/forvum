package ai.forvum.sdk;

import java.util.List;

/**
 * The engine-backed access seam the multimodal tools (#185, {@code forvum-tools-multimodal}) drive to run a
 * vision-capable sub-generation over a workspace image or PDF — the backend for the model-callable
 * {@code image.analyze} / {@code pdf.analyze} surface.
 *
 * <p>This is a <strong>Resolution-B seam</strong> (the {@code ChannelTurnDriver}/{@link TaskExecutor}/
 * {@link MemoryAccess} pattern), NOT a sealed provider: the single implementation lives in
 * {@code forvum-engine} — where the model machinery already sits ({@code LlmSelector}, the budget gate,
 * the {@code provider_calls} ledger, the {@code AgentRegistry} persona lease) — and plugins do NOT
 * implement it. It is promoted to {@code forvum-sdk} only so a Layer-3 tool module (which the enforcer bars
 * from depending on {@code forvum-engine}) can inject the contract and let ArC resolve it to the engine
 * bean. A plain (non-sealed) interface: the engine is the sole implementor, so there is no closed
 * implementor set to seal.
 *
 * <p>The seam does two things the tool cannot do alone (both live engine-side): it resolves the vision
 * model — an explicit {@code tools/multimodal.json} {@code model} override, else the current agent's
 * primary model (the leased #178 generation) — and it drives that model through the budget-gated (#169),
 * {@code provider_calls}-ledgered, OTel-spanned {@code LlmSelector.resolve(..., costBudget)} path, so a
 * sub-generation consumes the agent's cost budget exactly like any other model call. {@code forvum-sdk} is
 * Quarkus-free and AI-library-free; the contract takes/returns only JDK types + {@link MediaPayload}, so it
 * is reflection-free and native-safe, and the LangChain4j content types stay entirely engine-side.
 */
public interface MediaAnalysis {

    /**
     * Whether the vision model this turn would resolve to (the {@code modelRefOverride}, else the agent's
     * primary) can carry media of {@code mimeType} as native content. Images are accepted by every installed
     * chat provider's content mapper; a PDF is accepted only where the provider maps native PDF content
     * (the multimodal tool falls back to local text extraction when this returns {@code false}).
     *
     * @param mimeType         the media type to check (e.g. {@code image/png}, {@code application/pdf})
     * @param modelRefOverride an explicit {@code provider:model} override, or {@code null} for the agent's
     *                         primary model
     * @return {@code true} when the resolved provider can carry that media type as native content
     */
    boolean acceptsMedia(String mimeType, String modelRefOverride);

    /**
     * Run one vision sub-generation: send {@code prompt} plus {@code media} to the resolved vision model and
     * return its text analysis. Resolves the model as {@link #acceptsMedia} describes and drives it through
     * the budget-gated, ledgered {@code LlmSelector} path. Throws {@link IllegalStateException} naming the
     * model when the resolved model cannot actually process the media (a non-vision model), so the tool can
     * surface an actionable "configure a vision model" error to the turn.
     *
     * @param prompt           the analysis instruction (never {@code null}/blank); for the PDF
     *                         text-extraction fallback it carries the extracted text in a framed data block
     * @param media            the media payloads; may be empty for a text-only sub-generation (the PDF
     *                         text-extraction fallback), else one or more image/PDF payloads
     * @param modelRefOverride an explicit {@code provider:model} override, or {@code null} for the agent's
     *                         primary model
     * @return the model's text analysis
     */
    String analyze(String prompt, List<MediaPayload> media, String modelRefOverride);
}
