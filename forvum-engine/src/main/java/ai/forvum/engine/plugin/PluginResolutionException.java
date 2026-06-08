package ai.forvum.engine.plugin;

/**
 * A {@code forvum plugin install <coords>} (P2-6) failed to resolve the requested Maven coordinate — the
 * coordinate was malformed or no configured repository (the {@code ~/.m2} cache or Maven Central) held the
 * artifact. The CLI catches this and prints a one-line diagnostic with a non-zero exit; the cause carries
 * the underlying Maven Resolver exception for context.
 */
public class PluginResolutionException extends RuntimeException {

    public PluginResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
