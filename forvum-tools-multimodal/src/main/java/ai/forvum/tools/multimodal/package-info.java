/**
 * The multimodal tools (#185): Forvum's model-callable {@code image.analyze} / {@code pdf.analyze} actions,
 * reading a workspace image or PDF and routing it through a vision-capable sub-generation for a text
 * analysis.
 *
 * <p>Forvum's input path is text-only; this Layer-3 module adds two belt-gated tools (the new
 * {@link ai.forvum.core.PermissionScope#MEDIA_ANALYZE} scope) that hand raw media bytes to the engine-backed
 * {@link ai.forvum.sdk.MediaAnalysis} seam. The seam (implemented once in {@code forvum-engine}) resolves the
 * vision model — an explicit {@code tools/multimodal.json} {@code model} override, else the agent's primary
 * model — and drives it through the budget-gated, ledgered {@code LlmSelector} path, so a sub-generation
 * consumes the agent's cost budget like any other model call. The LangChain4j content types stay entirely
 * engine-side, so this module carries <strong>no AI-library dependency</strong>.
 *
 * <p>{@code pdf.analyze} is dual-path (issue-mandated): where the resolved provider maps native PDF content
 * (anthropic/google/openai) the raw PDF is sent as native content; otherwise the PDF's TEXT LAYER is
 * extracted locally with Apache PDFBox ({@link org.apache.pdfbox.text.PDFTextStripper} — no OCR, no
 * rendering) and injected into the prompt inside a delimited, closing-tag-neutralized data block (the
 * extracted text is UNTRUSTED — DR-6a framing).
 *
 * <p><strong>Native-clean by construction.</strong> The config is a hand-parsed {@code JsonNode} tree-walk
 * into a plain record (no reflective binding, no {@code @RegisterForReflection}); PDFBox pulls no
 * BouncyCastle ({@code bcprov}/{@code bcpkix} are optional and excluded → encrypted PDFs are rejected) and no
 * ServiceLoader, and its bundled font/glyph resources are embedded via a hand-authored
 * {@code META-INF/native-image/.../resource-config.json} (the [#124] Decision-tree A verdict). No static
 * {@code Random}/{@code SecureRandom} field (an image-heap constraint — the [#186] native trap).
 *
 * <p><strong>Configuration.</strong> The tools are functional with no config; an operator may tune them in
 * {@code $FORVUM_HOME/tools/multimodal.json}:
 *
 * <pre>{@code
 * {
 *   "model": "ollama:llava",   // optional vision-model override; absent => the agent's primary model
 *   "maxFileBytes": 5242880,    // per-file size cap, stat-checked before read (default 5 MiB)
 *   "maxPdfTextChars": 50000    // extracted-PDF-text cap (default 50000; truncation adds a fixed marker)
 * }
 * }</pre>
 */
package ai.forvum.tools.multimodal;
