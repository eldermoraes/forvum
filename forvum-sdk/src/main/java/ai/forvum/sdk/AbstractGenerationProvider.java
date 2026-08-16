package ai.forvum.sdk;

/**
 * Non-sealed base a media-generation plugin extends to implement {@link GenerationProvider}. It exists
 * solely so the sealed interface stays a closed set while remaining open to third-party extension
 * through this one permitted type (ULTRAPLAN section 2.2). Concrete plugins carry
 * {@link ForvumExtension}.
 */
public non-sealed abstract class AbstractGenerationProvider implements GenerationProvider {
}
