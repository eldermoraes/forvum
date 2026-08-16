package ai.forvum.tools.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The configured-channel oracle for {@code message.send} (#188): the set of channel ids with a
 * {@code $FORVUM_HOME/channels/<id>.json} file — the same id set the engine's {@code ChannelReader.ids()}
 * gives {@code forvum doctor}. A channel id outside this set is unknown and the tool rejects it.
 *
 * <p>The engine's {@code ChannelReader} lives in {@code forvum-engine}, which a Layer-3 tool must not
 * depend on (the module enforcer), so this reader resolves the home the same way {@code ForvumHome}
 * does — the {@code forvum.home} MP Config property (mapped from {@code FORVUM_HOME}), falling back to
 * {@code <user.home>/.forvum} — and lists the {@code channels/} directory directly (the
 * {@code ShellAllowlist} precedent). Read on demand per invocation, so an operator's channel add/remove
 * takes effect on the next call without a restart.
 */
@ApplicationScoped
public class ConfiguredChannels {

    static final String DEFAULT_HOME_DIR = ".forvum";
    private static final String JSON = ".json";

    private final Path channelsDir;

    @Inject
    public ConfiguredChannels(@ConfigProperty(name = "forvum.home") Optional<String> configuredHome) {
        Path home = configuredHome
                .filter(value -> !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .orElseGet(() -> Path.of(System.getProperty("user.home"))
                        .resolve(DEFAULT_HOME_DIR).toAbsolutePath().normalize());
        this.channelsDir = home.resolve("channels");
    }

    /** Package-private constructor binding an explicit {@code channels/} directory — for tests. */
    ConfiguredChannels(Path channelsDir) {
        this.channelsDir = channelsDir.toAbsolutePath().normalize();
    }

    /**
     * The ids (file-name stems) of the {@code channels/*.json} files, sorted; empty when the directory
     * is absent (a no-config home never throws — the M4 graceful-boot contract).
     */
    public Set<String> ids() {
        if (!Files.isDirectory(channelsDir)) {
            return Set.of();
        }
        Set<String> ids = new TreeSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(channelsDir, "*" + JSON)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                ids.add(name.substring(0, name.length() - JSON.length()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list channel configs under " + channelsDir + ".", e);
        }
        return Set.copyOf(ids);
    }
}
