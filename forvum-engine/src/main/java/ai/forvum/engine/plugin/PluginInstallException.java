package ai.forvum.engine.plugin;

/**
 * A {@code forvum plugin install <coords>} (P2-6) resolved the artifact but failed to install it owner-only
 * into {@code ~/.forvum/plugins/} — a write/stream failure, an unsafe {@code plugins/} directory or target
 * (a symlink, a loose directory that could not be tightened), or an atomic-move failure (#171). The
 * install-side dual of {@link PluginResolutionException}: the CLI catches both and prints a one-line
 * diagnostic with a non-zero exit. The cause carries the underlying {@code java.nio} exception for context.
 */
public class PluginInstallException extends RuntimeException {

    public PluginInstallException(String message, Throwable cause) {
        super(message, cause);
    }
}
