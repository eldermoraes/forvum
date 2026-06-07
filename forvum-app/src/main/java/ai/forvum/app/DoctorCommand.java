package ai.forvum.app;

import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.doctor.ConfigDoctor;
import ai.forvum.engine.doctor.DoctorReport;
import ai.forvum.engine.doctor.Finding;
import ai.forvum.sdk.ModelProvider;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code forvum doctor} (Phase 2, ULTRAPLAN section 7.2 item 9): validate the whole {@code ~/.forvum}
 * configuration surface and print problems with actionable hints. Exits 0 when healthy (warnings allowed),
 * 1 when any ERROR is found — so a script or CI step can gate on the configuration being loadable.
 *
 * <p>The validation lives in the engine's {@link ConfigDoctor} (reusing the M4 readers + the engine's own
 * typed binders as oracles). This command supplies the two things only the assembled app knows: the
 * resolved {@link ForvumHome} and the set of model-provider extension ids actually on the classpath
 * (gathered from {@code Instance<ModelProvider>}, the same way the engine's {@code LlmSelector} discovers
 * providers), so doctor can flag a model ref that names a provider no installed plugin handles.
 *
 * <p>Like {@code --help}/{@code --version}/{@code init}, {@code doctor} is a {@code CommandMode} one-shot:
 * it only reads files, so its boot skips Flyway, the config {@code WatchService}, and cron scheduling. Keep
 * the name in sync with {@code CommandMode} (which owns the one-shot set) if this is renamed.
 */
@CommandLine.Command(
        name = "doctor",
        description = "Validate the ~/.forvum configuration and report problems with hints.")
public class DoctorCommand implements Callable<Integer> {

    @Inject
    ForvumHome home;

    @Inject
    ConfigLoader loader;

    @Inject
    Instance<ModelProvider> providers;

    @Override
    public Integer call() {
        Set<String> knownProviders = providers.stream()
                .map(ModelProvider::extensionId)
                .collect(Collectors.toUnmodifiableSet());

        DoctorReport report = new ConfigDoctor(home, loader, knownProviders).check();

        System.out.println("Forvum doctor: checking " + home.root());
        for (Finding finding : report.findings()) {
            System.out.println(finding.severity() + " " + finding.location() + ": " + finding.problem());
            if (!finding.hint().isBlank()) {
                System.out.println("    hint: " + finding.hint());
            }
        }
        if (report.findings().isEmpty()) {
            System.out.println("No problems found.");
        } else {
            System.out.println("Found " + report.findings().size() + " problem(s): "
                    + report.errors().size() + " error(s), " + report.warnings().size() + " warning(s).");
        }

        return report.healthy() ? 0 : 1;
    }
}
