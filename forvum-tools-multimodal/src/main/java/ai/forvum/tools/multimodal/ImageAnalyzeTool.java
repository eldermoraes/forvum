package ai.forvum.tools.multimodal;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.MediaAnalysis;
import ai.forvum.sdk.MediaPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code image.analyze} tool spec (#185): read one or more workspace image files and route them, with a
 * prompt, through a vision-capable sub-generation (the engine-backed {@link MediaAnalysis} seam) for a text
 * analysis. Read-only over the workspace; its only external effect is a model spend, so it declares
 * {@link ToolSpec#userConfirmRequired()} {@code = false} (the {@code web.fetch} posture) — belt membership +
 * the {@link PermissionScope#MEDIA_ANALYZE} RBAC scope are the gates.
 *
 * <p>{@code paths} is an ARRAY of strings (multi-image analysis); the engine's {@code ToolCallBridge}
 * already supports array-of-string properties (#27's {@code argv}). {@code prompt} is optional.
 */
public final class ImageAnalyzeTool {

    /** Default prompt when the model omits one. */
    static final String DEFAULT_PROMPT = "Describe the image(s) in detail.";

    /** The tool this module contributes; executed by {@code MultimodalToolProvider}. */
    public static final ToolSpec SPEC = new ToolSpec(
            "image.analyze",
            "Analyze one or more workspace image files (PNG/JPEG/GIF/WebP) with a vision-capable model and "
          + "return a text analysis. Requires a vision-capable model (the agent's primary, or the 'model' "
          + "override in tools/multimodal.json). Provide workspace-relative paths.",
            PermissionScope.MEDIA_ANALYZE,
            "{\"type\":\"object\",\"properties\":{"
          + "\"paths\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
          + "\"description\":\"workspace-relative paths of the image files to analyze (one or more)\"},"
          + "\"prompt\":{\"type\":\"string\","
          + "\"description\":\"what to analyze about the image(s); a general description is used when omitted\"}},"
          + "\"required\":[\"paths\"]}");

    private ImageAnalyzeTool() {
    }

    /**
     * Execute {@code image.analyze}: confine + size-check + read each image, then run one vision
     * sub-generation over all of them.
     *
     * @return the model's text analysis
     */
    public static String analyze(MediaAnalysis mediaAnalysis, WorkspaceRoot workspaceRoot,
                                 MultimodalToolConfig.Spec config, List<String> paths, String prompt) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("image.analyze requires a non-empty 'paths' array.");
        }
        List<MediaPayload> media = new ArrayList<>(paths.size());
        for (String path : paths) {
            MediaLoader.LoadedMedia loaded = MediaLoader.load(workspaceRoot, config.maxFileBytes(), path);
            if (!MimeTypes.isImage(loaded.mimeType())) {
                throw new MultimodalException("File '" + path + "' is not an image (detected "
                        + loaded.mimeType() + "). Use pdf.analyze for PDFs.");
            }
            media.add(new MediaPayload(loaded.mimeType(), loaded.data(), loaded.sourceName()));
        }
        String effectivePrompt = prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt.strip();
        return mediaAnalysis.analyze(effectivePrompt, media, config.modelOrNull());
    }
}
