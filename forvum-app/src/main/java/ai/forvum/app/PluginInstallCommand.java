package ai.forvum.app;

import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.plugin.MavenPluginResolver;
import ai.forvum.engine.plugin.PluginInstallResult;
import ai.forvum.engine.plugin.PluginResolutionException;

import io.quarkus.runtime.ImageMode;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum plugin install <coords>} (P2-6, ULTRAPLAN section 7.2 item 6): resolve a Maven coordinate
 * ({@code groupId:artifactId:version}) against the user's {@code ~/.m2} cache + Maven Central via Apache
 * Maven Resolver (the engine {@link MavenPluginResolver}) and stream the resolved JAR into
 * {@code ~/.forvum/plugins/}. The fast-jar then discovers the new provider via {@code ServiceLoader} on its
 * next restart — the command prints the restart instruction rather than restarting the JVM itself.
 *
 * <p>The drop-in path is JVM-fast-jar-ONLY BY DESIGN (§6.2/§6.3), not a native carve-out: the native binary
 * fixes its plugin set at build time and cannot load a JAR added afterwards. When run from a native binary
 * the command still resolves and writes the JAR (so the file is staged) but warns that it takes effect only
 * after rebuilding a native binary that depends on the plugin coordinate.
 *
 * <p>Routed by picocli to this leaf {@code call()} (not {@link RootCommand#call()}), so no channel/server
 * dispatch runs. It only resolves + writes files, so — like {@code init}/{@code doctor} — it needs neither
 * the DB nor the watcher; it is recognized as a {@code CommandMode} one-shot via its {@code plugin} token.
 */
@CommandLine.Command(
        name = "install",
        description = "Resolve a Maven coordinate (groupId:artifactId:version) into ~/.forvum/plugins/.")
public class PluginInstallCommand implements Callable<Integer> {

    @Inject
    ForvumHome home;

    @Inject
    MavenPluginResolver resolver;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<coords>",
            description = "Maven coordinate of the plugin, formatted groupId:artifactId:version "
                    + "(e.g. com.example:forvum-channel-slack:1.0.0).")
    String coordinates;

    @Override
    public Integer call() {
        PluginInstallResult result;
        try {
            result = resolver.install(coordinates, home.plugins());
        } catch (PluginResolutionException e) {
            System.err.println("Plugin install failed: " + e.getMessage());
            return 1;
        }

        System.out.println("Installed " + result.coordinates() + " -> " + result.installedJar());
        if (ImageMode.current() == ImageMode.NATIVE_RUN) {
            System.out.println("Note: this is a native binary. Drop-in plugins are loaded only by the JVM "
                    + "fast-jar. The JAR is staged in ~/.forvum/plugins/, but to use it natively rebuild a "
                    + "native binary that depends on " + result.coordinates() + " (forvum is fast-jar/native "
                    + "by design - see docs section 6.2/6.3).");
        } else {
            System.out.println("Restart Forvum (the fast-jar) to load the new plugin.");
        }
        return 0;
    }
}
