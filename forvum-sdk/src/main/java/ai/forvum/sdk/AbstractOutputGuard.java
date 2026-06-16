package ai.forvum.sdk;

/**
 * Non-sealed base a plugin extends to implement {@link OutputGuard}. It exists solely so the sealed
 * interface stays a closed set while remaining open to third-party extension through this one permitted
 * type (ULTRAPLAN section 2.2 / 9.2.3), mirroring {@link AbstractModelProvider}. Concrete plugin guards
 * carry {@link ForvumExtension}.
 */
public non-sealed abstract class AbstractOutputGuard implements OutputGuard {
}
