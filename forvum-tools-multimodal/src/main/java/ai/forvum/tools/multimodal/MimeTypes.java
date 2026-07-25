package ai.forvum.tools.multimodal;

import java.util.Locale;

/**
 * Media-type detection for the multimodal tools: magic-byte sniffing with a filename-extension fallback,
 * so a mislabeled or extensionless workspace file is still classified from its bytes (and a mismatched /
 * unknown type is rejected with an actionable message rather than sent to the model blind). Supports the
 * image formats the installed vision providers accept (PNG, JPEG, GIF, WebP) and PDF ({@code %PDF-}).
 */
public final class MimeTypes {

    private MimeTypes() {
    }

    /**
     * Detect the media type of {@code data} (a file named {@code sourceName}), preferring the magic bytes
     * and falling back to the extension only when the bytes are inconclusive.
     *
     * @throws MultimodalException when neither the bytes nor the extension identify a supported media type
     */
    public static String detect(byte[] data, String sourceName) {
        String byMagic = sniff(data);
        if (byMagic != null) {
            return byMagic;
        }
        String byExt = fromExtension(sourceName);
        if (byExt != null) {
            return byExt;
        }
        throw new MultimodalException("Unsupported or unrecognized media type for '" + sourceName
                + "'. image.analyze accepts PNG/JPEG/GIF/WebP images; pdf.analyze accepts PDF files.");
    }

    /** Magic-byte type, or {@code null} when the bytes match no supported signature. */
    static String sniff(byte[] d) {
        if (d == null || d.length < 4) {
            return null;
        }
        // PNG: 89 50 4E 47
        if (u(d[0]) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if (u(d[0]) == 0xFF && u(d[1]) == 0xD8 && u(d[2]) == 0xFF) {
            return "image/jpeg";
        }
        // GIF: "GIF8"
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "image/gif";
        }
        // PDF: "%PDF-"
        if (d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') {
            return "application/pdf";
        }
        // WebP: "RIFF"????"WEBP"
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** Extension fallback, or {@code null} when the name carries no supported extension. */
    static String fromExtension(String sourceName) {
        if (sourceName == null) {
            return null;
        }
        String lower = sourceName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return null;
    }

    static boolean isImage(String mimeType) {
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
