package ai.forvum.tools.multimodal;

import ai.forvum.sdk.MediaAnalysis;
import ai.forvum.sdk.MediaPayload;

import java.util.List;

/**
 * A hermetic {@link MediaAnalysis} test stub that RECORDS the last {@code (prompt, media, modelRefOverride)}
 * it was called with and returns a canned reply — so the tool tests can assert exactly what the tool handed
 * the engine seam (payload bytes, the framed extracted-text prompt, the PDF native-vs-extraction decision)
 * without any engine/model. {@code acceptsMedia} is scriptable via {@link #pdfNative}.
 */
final class FakeMediaAnalysis implements MediaAnalysis {

    boolean pdfNative = true;
    boolean throwOnAnalyze = false;

    String lastPrompt;
    List<MediaPayload> lastMedia;
    String lastOverride;
    int analyzeCalls;

    @Override
    public boolean acceptsMedia(String mimeType, String modelRefOverride) {
        if (mimeType != null && mimeType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            return true;
        }
        return "application/pdf".equals(mimeType) && pdfNative;
    }

    @Override
    public String analyze(String prompt, List<MediaPayload> media, String modelRefOverride) {
        analyzeCalls++;
        this.lastPrompt = prompt;
        this.lastMedia = media;
        this.lastOverride = modelRefOverride;
        if (throwOnAnalyze) {
            throw new IllegalStateException("The model 'ollama:qwen' cannot analyze the supplied media.");
        }
        return "analysis-result";
    }
}
