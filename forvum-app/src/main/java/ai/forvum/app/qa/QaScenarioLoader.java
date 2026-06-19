package ai.forvum.app.qa;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads the QA scenario pack: the packaged {@code qa/scenarios.json} classpath resource by default, or an
 * operator-supplied override file. Reading the resource by name is what makes it a native-image resource —
 * {@code META-INF/native-image/ai.forvum/forvum-app/resource-config.json} includes it so
 * {@link ClassLoader#getResourceAsStream} finds it in the binary.
 *
 * <p>The loader does NOT decide pass/fail; it only materializes the scenarios. An absent/empty/malformed
 * pack throws — the {@link QaRunner} turns "no scenarios" into a failed suite (fails-by-default), so a
 * missing pack can never read as a vacuous pass.
 */
@ApplicationScoped
public class QaScenarioLoader {

    /** The packaged pack's classpath resource path (also native-hinted under {@code META-INF/native-image/}). */
    static final String PACKAGED_RESOURCE = "qa/scenarios.json";

    private final ObjectMapper mapper = new ObjectMapper();

    /** Load the packaged pack from the classpath. Throws if it is absent or cannot be parsed. */
    public List<QaScenario> loadPackaged() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(PACKAGED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("packaged QA pack '" + PACKAGED_RESOURCE
                        + "' not found on the classpath");
            }
            return scenariosOf(mapper.readValue(in, QaPack.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read packaged QA pack '" + PACKAGED_RESOURCE + "'", e);
        }
    }

    /** Load a pack from {@code path} (the {@code --pack} override). Throws if it is absent or unparseable. */
    public List<QaScenario> loadFrom(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("QA pack file not found: " + path);
        }
        try {
            return scenariosOf(mapper.readValue(Files.readAllBytes(path), QaPack.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read QA pack '" + path + "'", e);
        }
    }

    private static List<QaScenario> scenariosOf(QaPack pack) {
        if (pack == null || pack.scenarios() == null) {
            return List.of();
        }
        return pack.scenarios();
    }
}
