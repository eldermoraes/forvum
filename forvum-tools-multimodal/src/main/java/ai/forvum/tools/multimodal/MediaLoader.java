package ai.forvum.tools.multimodal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Confines, size-checks, reads, and type-detects a workspace-relative media file for the multimodal tools —
 * the shared read path of {@code image.analyze} and {@code pdf.analyze}. The order is load-bearing:
 * <ol>
 *   <li>confine the model-supplied path to the workspace ({@link WorkspaceRoot#resolveForRead}), rejecting
 *       traversal/absolute/symlink-escape BEFORE any I/O;</li>
 *   <li>{@code stat} the file and reject anything over the size cap BEFORE reading the bytes (base64 inflates
 *       ~4/3, so an oversize file is never loaded into the heap);</li>
 *   <li>read the bytes and detect the media type from the magic bytes (extension fallback).</li>
 * </ol>
 */
final class MediaLoader {

    private MediaLoader() {
    }

    /** A loaded media file: its bytes, detected media type, and workspace-relative source name. */
    record LoadedMedia(byte[] data, String mimeType, String sourceName) {
    }

    static LoadedMedia load(WorkspaceRoot workspaceRoot, long maxFileBytes, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("A media file path is required and must be non-blank.");
        }
        Path file = workspaceRoot.resolveForRead(relativePath); // confinement (throws WorkspaceEscapeException)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new MultimodalException("Media file '" + relativePath + "' does not exist in the workspace "
                    + "(or is not a regular file).");
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stat media file '" + relativePath + "'.", e);
        }
        if (size > maxFileBytes) {
            throw new MultimodalException("Media file '" + relativePath + "' is " + size + " bytes, over the "
                    + maxFileBytes + "-byte cap (set 'maxFileBytes' in ~/.forvum/tools/multimodal.json to "
                    + "raise it).");
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read media file '" + relativePath + "'.", e);
        }
        String mimeType = MimeTypes.detect(data, relativePath);
        return new LoadedMedia(data, mimeType, relativePath);
    }
}
