package ai.forvum.tools.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link PdfTextExtractor}: single + multi-page extraction; image-only → sentinel; encrypted → reject;
 *  cap → truncated with a fixed marker and length ≤ cap. */
class PdfTextExtractorTest {

    @Test
    void extractsSinglePageText() {
        byte[] pdf = PdfFixtures.textPdf("Hello Forvum multimodal.");
        String text = PdfTextExtractor.extract(pdf, "one.pdf", 50_000);
        assertTrue(text.contains("Hello Forvum multimodal."), "the text layer is extracted; got: " + text);
    }

    @Test
    void extractsAllPagesOfAMultiPagePdf() {
        byte[] pdf = PdfFixtures.textPdf("PageOneAlpha", "PageTwoBeta", "PageThreeGamma");
        String text = PdfTextExtractor.extract(pdf, "three.pdf", 50_000);
        assertTrue(text.contains("PageOneAlpha"), "page 1 extracted");
        assertTrue(text.contains("PageTwoBeta"), "page 2 extracted");
        assertTrue(text.contains("PageThreeGamma"), "page 3 extracted (multi-page acceptance)");
    }

    @Test
    void imageOnlyPageYieldsTheNoTextSentinel() {
        byte[] pdf = PdfFixtures.imageOnlyPdf();
        assertEquals(PdfTextExtractor.NO_TEXT, PdfTextExtractor.extract(pdf, "scan.pdf", 50_000));
    }

    @Test
    void encryptedPdfIsRejectedActionably() {
        byte[] pdf = PdfFixtures.encryptedPdf("secret contents", "s3cr3t");
        MultimodalException e = assertThrows(MultimodalException.class,
                () -> PdfTextExtractor.extract(pdf, "locked.pdf", 50_000));
        assertTrue(e.getMessage().contains("locked.pdf"), "the error names the file");
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("encrypt"),
                "the error identifies encryption; got: " + e.getMessage());
    }

    @Test
    void extractionIsCappedWithAFixedMarkerAndLengthWithinTheCap() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            big.append("word").append(i).append(' ');
        }
        byte[] pdf = PdfFixtures.textPdf(big.toString());
        int cap = 100;
        String text = PdfTextExtractor.extract(pdf, "big.pdf", cap);
        assertTrue(text.length() <= cap, "the extracted text is bounded to the cap, got " + text.length());
        assertTrue(text.endsWith(PdfTextExtractor.TRUNCATION_MARKER), "a fixed truncation marker is appended");
    }

    @Test
    void capHelperReservesRoomForTheMarker() {
        String capped = PdfTextExtractor.cap("x".repeat(500), 60);
        assertTrue(capped.length() <= 60);
        assertTrue(capped.endsWith(PdfTextExtractor.TRUNCATION_MARKER));
    }
}
