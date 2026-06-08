package ai.forvum.app;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum plugin} (P2-6, ULTRAPLAN section 7.2 item 6): the parent of the plugin-marketplace
 * subcommands. Today it groups a single child, {@link PluginInstallCommand} ({@code plugin install
 * <coords>}). Invoked bare it prints its usage and exits 0 — picocli only routes to a leaf {@code call()}.
 *
 * <p>The plugin drop-in directory ({@code ~/.forvum/plugins/}) is JVM-fast-jar-only by design (§6.2/§6.3),
 * not a native carve-out: the native binary fixes its plugin set at build time, so {@code install} on a
 * native binary resolves+writes the JAR but warns that it takes effect only after a rebuild.
 */
@CommandLine.Command(
        name = "plugin",
        mixinStandardHelpOptions = true,
        description = "Manage Forvum plugins (fast-jar drop-in; native users rebuild).",
        subcommands = { PluginInstallCommand.class })
public class PluginCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
