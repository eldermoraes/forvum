package ai.forvum.engine.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.sdk.MediaAnalysis;
import ai.forvum.sdk.MediaPayload;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

/**
 * Exercises {@link EngineMediaAnalysis} through the real {@link ai.forvum.engine.routing.LlmSelector}
 * budget-gated/ledgered path (a {@link CapturingVisionModelProvider} records the resolved ref + the
 * {@link ChatRequest}). Proves: the image base64/prompt reach the model as {@link ImageContent} content; a
 * PDF becomes {@link PdfFileContent}; an override ref (distinct from the persona primary) is resolved; the
 * default path reads the leased persona primary; the {@code acceptsMedia} matrix; and that a
 * {@code maxTokens:0} agent stops the sub-generation pre-call (the budget-gated path is used).
 */
@QuarkusTest
@TestProfile(MediaAnalysisTestHomeProfile.class)
class EngineMediaAnalysisTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final byte[] PDF_BYTES = {'%', 'P', 'D', 'F', '-', '1', '.', '4'};

    @Inject
    MediaAnalysis mediaAnalysis;

    @Inject
    AgentRegistry registry;

    @BeforeEach
    void reset() {
        CapturingVisionModelProvider.reset();
    }

    private <T> T asSeer(ScopedValue.CallableOp<T, Exception> body) throws Exception {
        AgentId seer = new AgentId("seer");
        registry.getOrCreate(seer);
        return ScopedValue.where(CurrentAgent.CURRENT_AGENT, seer)
                .where(AgentRegistry.CURRENT_AGENT_SPEC, registry.lease(seer))
                .call(body);
    }

    @Test
    void imageContentAndPromptReachTheModelWithExactBase64() throws Exception {
        String out = asSeer(() -> mediaAnalysis.analyze(
                "Describe this.", List.of(new MediaPayload("image/png", PNG_BYTES, "a.png")), null));
        assertEquals("analysis-ok", out);

        UserMessage msg = firstUserMessage();
        assertEquals("Describe this.", text(msg));
        ImageContent image = (ImageContent) mediaOf(msg);
        assertEquals(Base64.getEncoder().encodeToString(PNG_BYTES), image.image().base64Data(),
                "the exact file bytes must reach the model as base64 (not a green-for-wrong-reason stub)");
        assertEquals("image/png", image.image().mimeType());
    }

    @Test
    void multipleImagesAllReachTheModel() throws Exception {
        asSeer(() -> mediaAnalysis.analyze("two", List.of(
                new MediaPayload("image/png", PNG_BYTES, "a.png"),
                new MediaPayload("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1}, "b.jpg")), null));

        long images = firstUserMessage().contents().stream().filter(ImageContent.class::isInstance).count();
        assertEquals(2, images, "both images are sent in one multimodal message");
    }

    @Test
    void pdfBecomesPdfFileContent() throws Exception {
        asSeer(() -> mediaAnalysis.analyze("Read it.",
                List.of(new MediaPayload("application/pdf", PDF_BYTES, "d.pdf")), null));

        PdfFileContent pdf = (PdfFileContent) mediaOf(firstUserMessage());
        assertEquals(Base64.getEncoder().encodeToString(PDF_BYTES), pdf.pdfFile().base64Data());
    }

    @Test
    void overrideRefIsResolvedDistinctFromThePersonaPrimary() throws Exception {
        asSeer(() -> mediaAnalysis.analyze("x",
                List.of(new MediaPayload("image/png", PNG_BYTES, "a.png")), "capture:vision-override"));

        assertEquals("vision-override", CapturingVisionModelProvider.LAST_REF.get().model(),
                "an explicit tools/multimodal.json model override resolves, not the persona primary");
    }

    @Test
    void defaultPathResolvesTheLeasedPersonaPrimary() throws Exception {
        asSeer(() -> mediaAnalysis.analyze("x",
                List.of(new MediaPayload("image/png", PNG_BYTES, "a.png")), null));

        assertEquals("vision-primary", CapturingVisionModelProvider.LAST_REF.get().model(),
                "with no override the agent's primary model is used");
    }

    @Test
    void acceptsMediaMatrix() throws Exception {
        asSeer(() -> {
            assertTrue(mediaAnalysis.acceptsMedia("image/png", null), "images are accepted by every provider");
            assertTrue(mediaAnalysis.acceptsMedia("application/pdf", "anthropic:claude-opus-5"),
                    "anthropic maps native PDF content");
            assertTrue(mediaAnalysis.acceptsMedia("application/pdf", "openai:gpt-4.1"), "openai maps PDF");
            assertTrue(mediaAnalysis.acceptsMedia("application/pdf", "google:gemini-2.5-flash"), "google maps PDF");
            assertFalse(mediaAnalysis.acceptsMedia("application/pdf", "ollama:llava"),
                    "ollama has no PDF content mapper — the tool falls back to text extraction");
            assertFalse(mediaAnalysis.acceptsMedia("application/pdf", "copilot:gpt-4o"),
                    "copilot is conservatively excluded pending endpoint verification");
            return null;
        });
    }

    @Test
    void exhaustedBudgetStopsTheSubGenerationPreCall() {
        AgentId pauper = new AgentId("pauper");
        registry.getOrCreate(pauper);
        assertThrows(RuntimeException.class, () -> ScopedValue.where(CurrentAgent.CURRENT_AGENT, pauper)
                .where(AgentRegistry.CURRENT_AGENT_SPEC, registry.lease(pauper))
                .call(() -> mediaAnalysis.analyze("x",
                        List.of(new MediaPayload("image/png", PNG_BYTES, "a.png")), null)),
                "a maxTokens:0 budget stops the sub-generation — proving the budget-gated resolve path");
        assertNull(CapturingVisionModelProvider.LAST_REQUEST.get(),
                "the model is never called when the budget gate fires pre-call");
    }

    private UserMessage firstUserMessage() {
        ChatRequest request = CapturingVisionModelProvider.LAST_REQUEST.get();
        assertFalse(request.messages().isEmpty(), "the sub-generation issued a request");
        return (UserMessage) request.messages().get(0);
    }

    private static String text(UserMessage msg) {
        return msg.contents().stream().filter(TextContent.class::isInstance)
                .map(c -> ((TextContent) c).text()).findFirst().orElseThrow();
    }

    private static Content mediaOf(UserMessage msg) {
        return msg.contents().stream().filter(c -> !(c instanceof TextContent)).findFirst().orElseThrow();
    }
}
