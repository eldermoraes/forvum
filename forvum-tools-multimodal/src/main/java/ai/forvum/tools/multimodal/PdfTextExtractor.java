package ai.forvum.tools.multimodal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * Extracts the TEXT LAYER of a PDF via Apache PDFBox's {@link PDFTextStripper} (#185, the {@code pdf.analyze}
 * fallback for providers that lack a native PDF content mapper). Text only — no OCR, no page rendering, so
 * the ImageIO/rendering native surface is never reached. The extracted text is capped at a caller-supplied
 * character limit and, when truncated, a FIXED ASCII marker is appended so the result stays bounded (the
 * [#176] contract); a PDF with no text layer (an image-only scan) yields a fixed sentinel rather than an
 * empty string; an encrypted PDF (PDFBox pulls no BouncyCastle — {@code bcprov}/{@code bcpkix} are optional
 * and excluded) is rejected with an actionable message.
 */
public final class PdfTextExtractor {

    /** Fixed ASCII truncation marker (never raw content); appended so the total length stays ≤ the cap. */
    static final String TRUNCATION_MARKER = "\n[pdf-text truncated at cap]";
    /** Fixed sentinel for a PDF with no extractable text layer (e.g. an image-only scan). */
    static final String NO_TEXT = "(no extractable text layer in this PDF)";

    private PdfTextExtractor() {
    }

    /**
     * Extract and bound the text layer of {@code pdfBytes}.
     *
     * @param pdfBytes the raw PDF file bytes
     * @param sourceName the workspace-relative name, for diagnostics only (never its content)
     * @param maxChars the maximum number of characters to return (including the truncation marker)
     * @return the extracted text (≤ {@code maxChars}), or {@link #NO_TEXT} when there is no text layer
     * @throws MultimodalException if the PDF is encrypted or cannot be parsed
     */
    public static String extract(byte[] pdfBytes, String sourceName, int maxChars) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                return NO_TEXT;
            }
            return cap(text.strip(), maxChars);
        } catch (InvalidPasswordException e) {
            throw new MultimodalException("The PDF '" + sourceName + "' is encrypted/password-protected; "
                    + "pdf.analyze cannot read it (encrypted PDFs are not supported).", e);
        } catch (IOException e) {
            throw new MultimodalException("The PDF '" + sourceName + "' could not be parsed for text "
                    + "extraction; it may be corrupt or malformed.", e);
        }
    }

    /** Bound {@code text} to {@code maxChars}, reserving room for {@link #TRUNCATION_MARKER} when it overflows. */
    static String cap(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int keep = Math.max(0, maxChars - TRUNCATION_MARKER.length());
        return text.substring(0, keep) + TRUNCATION_MARKER;
    }
}
