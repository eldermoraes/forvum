package ai.forvum.tools.multimodal;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.MediaAnalysis;
import ai.forvum.sdk.MediaPayload;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code pdf.analyze} tool spec (#185): read a workspace PDF and analyze it. Dual path (issue-mandated):
 * where the resolved provider maps native PDF content (anthropic/google/openai — {@link
 * MediaAnalysis#acceptsMedia}) the raw PDF is sent as native content; otherwise the PDF's TEXT LAYER is
 * extracted locally ({@link PdfTextExtractor}) and injected into the prompt inside a delimited, closing-tag-
 * neutralized data block (the extracted text is UNTRUSTED — DR-6a framing). No OCR, no rendering.
 *
 * <p>Read-only over the workspace; its only external effect is a model spend, so
 * {@link ToolSpec#userConfirmRequired()} is {@code false} (the {@code web.fetch} posture) — belt membership
 * + {@link PermissionScope#MEDIA_ANALYZE} are the gates.
 */
public final class PdfAnalyzeTool {

    static final String DEFAULT_PROMPT = "Analyze this document.";
    private static final String OPEN = "<pdf-document source=\"%s\">";
    private static final String CLOSE = "</pdf-document>";

    /** Matches a closing tag in ANY whitespace/case form (incl. {@code < /pdf-document >}), so untrusted
     * content cannot end the data block early — the RetrievedMemory.CLOSE_TAG precedent (#176). */
    private static final Pattern CLOSE_TAG =
            Pattern.compile("<\\s*/\\s*pdf-document\\s*>", Pattern.CASE_INSENSITIVE);

    /** The tool this module contributes; executed by {@code MultimodalToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "pdf.analyze",
            "Analyze a workspace PDF file with a capable model and return a text analysis. Sends the PDF "
          + "natively where the model supports it, otherwise extracts the PDF's text layer (no OCR). "
          + "Provide a workspace-relative path.",
            PermissionScope.MEDIA_ANALYZE,
            "{\"type\":\"object\",\"properties\":{"
          + "\"path\":{\"type\":\"string\","
          + "\"description\":\"workspace-relative path of the PDF file to analyze\"},"
          + "\"prompt\":{\"type\":\"string\","
          + "\"description\":\"what to analyze about the document; a general analysis is used when omitted\"}},"
          + "\"required\":[\"path\"]}");

    private PdfAnalyzeTool() {
    }

    /**
     * Execute {@code pdf.analyze}: confine + size-check + read the PDF, then either send it as native PDF
     * content or fall back to text extraction, and run one sub-generation.
     *
     * @return the model's text analysis
     */
    public static String analyze(MediaAnalysis mediaAnalysis, WorkspaceRoot workspaceRoot,
                                 MultimodalToolConfig.Spec config, String path, String prompt) {
        MediaLoader.LoadedMedia loaded = MediaLoader.load(workspaceRoot, config.maxFileBytes(), path);
        if (!"application/pdf".equals(loaded.mimeType())) {
            throw new MultimodalException("File '" + path + "' is not a PDF (detected " + loaded.mimeType()
                    + "). Use image.analyze for images.");
        }
        String effectivePrompt = prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt.strip();
        String override = config.modelOrNull();

        if (mediaAnalysis.acceptsMedia("application/pdf", override)) {
            MediaPayload payload = new MediaPayload("application/pdf", loaded.data(), loaded.sourceName());
            return mediaAnalysis.analyze(effectivePrompt, List.of(payload), override);
        }
        // Fallback: extract the text layer locally and frame it as an untrusted data block. BOTH the
        // extracted body AND the model-supplied source name are untrusted and neutralized before framing.
        String extracted = PdfTextExtractor.extract(loaded.data(), loaded.sourceName(), config.maxPdfTextChars());
        String framed = effectivePrompt + "\n\n" + String.format(OPEN, neutralizeSource(loaded.sourceName()))
                + "\n" + neutralize(extracted) + "\n" + CLOSE;
        return mediaAnalysis.analyze(framed, List.of(), override);
    }

    /**
     * Neutralize any embedded closing delimiter in untrusted extracted text so the injected data block
     * cannot be broken out of. Whitespace/case-tolerant (the RetrievedMemory precedent), so {@code
     * </pdf-document>}, {@code < / PDF-Document >}, etc. are all defused. The single real {@link #CLOSE}
     * delimiter is the one this tool appends after the neutralized body.
     */
    static String neutralize(String extracted) {
        if (extracted == null) {
            return "";
        }
        return CLOSE_TAG.matcher(extracted).replaceAll("[pdf-document]");
    }

    /**
     * Neutralize the model-supplied source name before it enters the {@code source="..."} attribute of the
     * open tag: strip the characters that could break out of the attribute or form a tag ({@code "}, {@code
     * <}, {@code >}), and defuse any whole closing delimiter. So a crafted path like
     * {@code x"></pdf-document>inject} cannot escape the framing.
     */
    static String neutralizeSource(String sourceName) {
        if (sourceName == null) {
            return "";
        }
        return neutralize(sourceName).replaceAll("[\"<>]", "_");
    }
}
