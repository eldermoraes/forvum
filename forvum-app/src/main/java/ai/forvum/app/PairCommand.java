package ai.forvum.app;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum pair} (P2-PAIR-SCOPE #44): the parent of the device scope-upgrade approval subcommands —
 * {@link PairApproveCommand} ({@code pair approve <id>}) and {@link PairRejectCommand}
 * ({@code pair reject <id>}). Invoked bare it prints its usage and exits 0. A device declares the scopes
 * it wants in {@code ~/.forvum/devices/<id>.json}; the owner grants (or refuses) them here, recording a
 * reason code. {@code forvum devices} shows the requested-vs-approved state and {@code forvum doctor}
 * surfaces a pending upgrade as a warning.
 */
@CommandLine.Command(
        name = "pair",
        mixinStandardHelpOptions = true,
        description = "Approve or reject a paired device's requested capability scopes.",
        subcommands = { PairApproveCommand.class, PairRejectCommand.class })
public class PairCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
