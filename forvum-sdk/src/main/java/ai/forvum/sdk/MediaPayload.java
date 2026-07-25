package ai.forvum.sdk;

/**
 * One piece of media (an image or a PDF) the {@code forvum-tools-multimodal} tools (#185) hand to the
 * {@link MediaAnalysis} seam for a vision sub-generation. A pure value: the raw bytes ({@code data}), their
 * {@code mimeType} (e.g. {@code image/png}, {@code application/pdf}), and a {@code sourceName} (the
 * workspace-relative file name, for diagnostics and prompt framing only). JDK types only — the engine
 * base64-encodes the bytes and wraps them in the LangChain4j content type on its side, so the tool module
 * carries no AI-library dependency and this record stays reflection-free and native-safe.
 *
 * @param mimeType   the media type of {@code data} (e.g. {@code image/png}, {@code application/pdf})
 * @param data       the raw file bytes (never {@code null} or empty)
 * @param sourceName the workspace-relative source file name, for diagnostics/framing (never blank)
 */
public record MediaPayload(String mimeType, byte[] data, String sourceName) {

    public MediaPayload {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException(
                    "MediaPayload mimeType must be non-null and non-blank (e.g. 'image/png').");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException(
                    "MediaPayload data must be non-null and non-empty — an empty media byte array cannot "
                  + "be analyzed.");
        }
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException(
                    "MediaPayload sourceName must be non-null and non-blank — it names the source file for "
                  + "diagnostics and prompt framing.");
        }
    }
}
