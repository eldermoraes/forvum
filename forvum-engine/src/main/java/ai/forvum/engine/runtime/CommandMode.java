package ai.forvum.engine.runtime;

import io.quarkus.runtime.annotations.CommandLineArguments;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Detects a <em>one-shot CLI command</em> ({@code --help}/{@code --version}/{@code init}/{@code doctor}/
 * {@code plugin}) from the process arguments (M20). Such an invocation prints + exits without running an
 * agent turn, so the heavy {@code @Observes StartupEvent} work — Flyway migration, the config
 * {@code WatchService}, and cron scheduling — is skipped for it, keeping the {@code --help} cold-start path
 * off the DB/IO (the lever for the &lt;200 ms gate). {@code doctor} (P2-9) only reads the config files, and
 * {@code plugin install} (P2-6) only resolves a Maven coordinate and writes a JAR into
 * {@code ~/.forvum/plugins/}, so both need neither the DB nor the watcher. Interactive/server invocations
 * ({@code oneShot == false}) boot normally. Reads {@code @CommandLineArguments}, which Quarkus populates
 * before any startup observer runs.
 *
 * <p>{@code isOneShotCommand} is also called by {@code ForvumApplication.main} (before Quarkus boots) to set
 * {@code quarkus.http.host-enabled=false}, leaving the bundled Web channel's {@code vertx-http} listener
 * unbound for a one-shot — that listener binds at RUNTIME_INIT, before any observer, so it cannot be gated
 * here. Net: a one-shot pays neither the HTTP bind nor the DB/watcher/cron work, and needs no free port.
 *
 * <p>The recognized set is the app's own CLI surface: the picocli-universal {@code --help}/{@code -h}/
 * {@code --version}/{@code -V} plus the app-defined {@code init}, {@code doctor}, and {@code plugin}
 * subcommands. It must stay in sync with {@code RootCommand} (which owns them); only canonical single-token
 * forms are matched — a non-canonical input (e.g. clustered {@code -hV}) merely pays the full boot once,
 * never misbehaves.
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
                // + the 'init'/'doctor'/'plugin'/'skill' subcommands). Bare 'help'/'version' are NOT
                // registered subcommands. 'plugin' (P2-6) only resolves+writes a JAR and 'skill' (P2-7) only
                // downloads+writes a .md, so both skip the DB/watcher too.
                case "--help", "-h", "--version", "-V", "init", "doctor", "plugin", "skill" -> {
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
