package ai.forvum.sdk;

/**
 * One generated media asset a {@link GenerationProvider} returns (#187): the raw bytes plus the file
 * extension the caller should persist them under (e.g. {@code png}, {@code mp4}, {@code wav}). A pure
 * value (JDK types only, the {@link MediaPayload} posture) — where the bytes land (the workspace
 * {@code media/} outbox) is the calling tool's concern, never the provider's.
 *
 * @param kind          the kind of media generated (never {@code null})
 * @param data          the raw asset bytes (never {@code null} or empty)
 * @param fileExtension the extension to persist under, without a leading dot (never blank)
 */
public record GeneratedMedia(MediaKind kind, byte[] data, String fileExtension) {

    public GeneratedMedia {
        if (kind == null) {
            throw new IllegalArgumentException(
                    "GeneratedMedia kind must be non-null — one of the MediaKind constants.");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException(
                    "GeneratedMedia data must be non-null and non-empty — an empty asset cannot be "
                  + "persisted.");
        }
        if (fileExtension == null || fileExtension.isBlank() || fileExtension.startsWith(".")) {
            throw new IllegalArgumentException(
                    "GeneratedMedia fileExtension must be non-blank without a leading dot (e.g. 'png'). "
                  + "Got: '" + fileExtension + "'.");
        }
    }
}
