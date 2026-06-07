package ai.forvum.engine.runtime;

import io.quarkus.runtime.annotations.CommandLineArguments;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Detects a <em>one-shot CLI command</em> ({@code --help}/{@code --version}/{@code init}) from the
 * process arguments (M20). Such an invocation prints + exits without running an agent turn, so the heavy
 * {@code @Observes StartupEvent} work — Flyway migration, the config {@code WatchService}, and cron
 * scheduling — is skipped for it, keeping the {@code --help} cold-start path off the DB/IO (the lever for
 * the &lt;200 ms gate). Interactive/server invocations ({@code oneShot == false}) boot normally. Reads
 * {@code @CommandLineArguments}, which Quarkus populates before any startup observer runs.
 *
 * <p>{@code isOneShotCommand} is also called by {@code ForvumApplication.main} (before Quarkus boots) to set
 * {@code quarkus.http.host-enabled=false}, leaving the bundled Web channel's {@code vertx-http} listener
 * unbound for a one-shot — that listener binds at RUNTIME_INIT, before any observer, so it cannot be gated
 * here. Net: a one-shot pays neither the HTTP bind nor the DB/watcher/cron work, and needs no free port.
 *
 * <p>The recognized set is the app's own CLI surface: the picocli-universal {@code --help}/{@code -h}/
 * {@code --version}/{@code -V} plus the app-defined {@code init} subcommand. It must stay in sync with
 * {@code RootCommand} (which owns {@code init}); only canonical single-token forms are matched — a
 * non-canonical input (e.g. clustered {@code -hV}) merely pays the full boot once, never misbehaves.
 */
@ApplicationScoped
public class CommandMode {

    private final boolean oneShot;

    @Inject
    public CommandMode(@CommandLineArguments String[] args) {
        this.oneShot = isOneShotCommand(args);
    }

    /** Whether {@code args} name a one-shot command that exits without serving/running a turn. */
    public static boolean isOneShotCommand(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            switch (arg) {
                // Canonical one-shot forms only — must match RootCommand's surface (mixinStandardHelpOptions
                // + the 'init' subcommand). Bare 'help'/'version' are NOT registered subcommands.
                case "--help", "-h", "--version", "-V", "init" -> {
                    return true;
                }
                default -> {
                    // keep scanning
                }
            }
        }
        return false;
    }

    /** True when the process was launched as a one-shot command (skip DB/watcher startup work). */
    public boolean isOneShot() {
        return oneShot;
    }
}
