package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Coverage of {@link WorkspaceRootProducer} — the CDI producer of the shell module's {@link WorkspaceRoot}.
 * Exercised directly (no CDI container needed) by setting the package-private {@code configuredRoot} field
 * and calling the producer method: a configured {@code forvum.workspace.root} is used; an absent one falls
 * back to {@code <user.home>/.forvum/workspace}.
 */
class WorkspaceRootProducerTest {

    @Test
    void usesAConfiguredWorkspaceRoot(@TempDir Path dir) {
        WorkspaceRootProducer producer = new WorkspaceRootProducer();
        producer.configuredRoot = Optional.of(dir.toString());

        WorkspaceRoot workspace = producer.shellWorkspaceRoot();

        assertEquals(dir.toAbsolutePath().normalize(), workspace.root(),
                "a configured forvum.workspace.root is used (absolute, normalized by WorkspaceRoot)");
    }

    @Test
    void fallsBackToTheDefaultWorkspaceWhenUnconfigured() {
        WorkspaceRootProducer producer = new WorkspaceRootProducer();
        producer.configuredRoot = Optional.empty();

        WorkspaceRoot workspace = producer.shellWorkspaceRoot();

        Path expected = Path.of(System.getProperty("user.home"), ".forvum", "workspace")
                .toAbsolutePath().normalize();
        assertEquals(expected, workspace.root(),
                "an absent forvum.workspace.root falls back to <user.home>/.forvum/workspace");
    }
}
