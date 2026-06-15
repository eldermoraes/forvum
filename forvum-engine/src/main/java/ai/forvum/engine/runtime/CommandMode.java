package ai.forvum.engine.runtime;

import io.quarkus.runtime.annotations.CommandLineArguments;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Detects a <em>one-shot CLI command</em> ({@code --help}/{@code --version}/{@code init}/{@code doctor}/
 * {@code plugin}/{@code skill}/{@code mcp}) from the process arguments (M20). Such an invocation prints +
 * exits without running an agent turn, so the heavy {@code @Observes StartupEvent} work — Flyway
 * migration, the config {@code WatchService}, cron scheduling, AND the {@code ToolRegistry} tool
 * materialization (whose MCP bridge connect is a blocking network round-trip, P2-13) — is skipped for it,
 * keeping the {@code --help} cold-start path off the DB/IO/network (the lever for the &lt;200 ms gate).
 * {@code doctor} (P2-9) only reads the config files, {@code plugin install} (P2-6) only resolves a Maven
 * coordinate and writes a JAR into {@code ~/.forvum/plugins/}, {@code skill install} (P2-7) downloads a
 * {@code .md} into {@code ~/.forvum/skills/}, and {@code mcp add}/{@code mcp list} (P2-13) read/write
 * {@code ~/.forvum/mcp-servers/} — none needs the DB or the watcher. Interactive/server invocations
 * ({@code oneShot == false}) boot normally. Reads {@code @CommandLineArguments}, which Quarkus populates
 * before any startup observer runs.
 *
 * <p>{@code isOneShotCommand} is also called by {@code ForvumApplication.main} (before Quarkus boots) to set
 * {@code quarkus.http.host-enabled=false}, leaving the bundled Web channel's {@code vertx-http} listener
 * unbound for a one-shot — that listener binds at RUNTIME_INIT, before any observer, so it cannot be gated
 * here. Net: a one-shot pays neither the HTTP bind nor the DB/watcher/cron work, and needs no free port.
 *
 * <p>The recognized set is the app's own CLI surface: the picocli-universal {@code --help}/{@code -h}/
 * {@code --version}/{@code -V} plus the app-defined {@code init}, {@code doctor}, {@code plugin},
 * {@code skill}, and {@code mcp} subcommands. It must stay in sync with {@code RootCommand} (which owns
 * them); only canonical single-token
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
                // + the 'init'/'doctor'/'plugin'/'skill'/'mcp'/'copilot' subcommands). Bare 'help'/'version'
                // are NOT registered subcommands. 'plugin' (P2-6) resolves+writes a JAR, 'skill' (P2-7)
                // downloads+writes a .md, 'mcp' (P2-13) reads/writes mcp-servers/, and 'copilot' (#42)
                // device-code logs in + writes a credential file — none needs the DB/watcher.
                case "--help", "-h", "--version", "-V", "init", "doctor", "plugin", "skill", "mcp",
                        "copilot" -> {
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
