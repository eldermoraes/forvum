package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.app.RootCommand.LaunchMode;
import ai.forvum.channel.tui.TuiChannel;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;

/**
 * The default-run dispatch: an enabled interactive (TUI) channel hands the foreground to the REPL and
 * the REPL's exit code is the process exit code. A plain unit test with stubbed collaborators — the
 * stubs are {@code @Vetoed} because a CDI scope is {@code @Inherited} and an un-vetoed subclass would
 * become a second ambiguous bean in this module's {@code @QuarkusMainTest} boots. The blocking SERVER
 * branch ({@code Quarkus.waitForExit()}) is not exercised through {@code call()}; the dispatch decision
 * itself is verified via the pure {@link RootCommand#decide} matrix below.
 */
class RootCommandDispatchTest {

    @Vetoed
    private static final class InteractiveLauncher extends ChannelLauncher {
        @Override
        public boolean shouldRunInteractive() {
            return true;
        }

        @Override
        public boolean shouldRunAsServer() {
            return false; // TUI-only home (no server channel) — the piped/interactive REPL case.
        }
    }

    @Vetoed
    private static final class ExitCodeTui extends TuiChannel {
        @Override
        public int run() {
            return 7;
        }
    }

    @Test
    void anEnabledTuiChannelRunsTheReplAndReturnsItsExitCode() {
        RootCommand command = new RootCommand();
        command.channels = new InteractiveLauncher();
        command.tui = new ExitCodeTui();

        assertEquals(7, command.call());
    }

    // --- decide() matrix: the launch-mode decision for a no-subcommand run -------------------------

    @Test
    void anInteractiveTerminalWithAnEnabledTuiRunsTheRepl() {
        assertEquals(LaunchMode.INTERACTIVE, RootCommand.decide(true, true, false));
        assertEquals(LaunchMode.INTERACTIVE, RootCommand.decide(true, true, true));
    }

    @Test
    void aPipedRunWithOnlyTheTuiStillRunsTheRepl() {
        // echo ... | forvum : no TTY, TUI enabled, no server channel → the piped REPL (M15 contract).
        assertEquals(LaunchMode.INTERACTIVE, RootCommand.decide(false, true, false));
    }

    @Test
    void aServerChannelWinsOverALeftoverTuiWhenNotOnATerminal() {
        // The regression: forvum init leaves channels/tui.json enabled. Under systemd (no TTY) it must
        // NOT shadow a configured server channel, or the binary exits at stdin EOF and restart-loops.
        assertEquals(LaunchMode.SERVER, RootCommand.decide(false, true, true));
    }

    @Test
    void aServerChannelRunsWhenNoTuiIsEnabled() {
        assertEquals(LaunchMode.SERVER, RootCommand.decide(false, false, true));
        assertEquals(LaunchMode.SERVER, RootCommand.decide(true, false, true));
    }

    @Test
    void neitherChannelEnabledFallsThroughToTheInitHint() {
        assertEquals(LaunchMode.NONE, RootCommand.decide(false, false, false));
        assertEquals(LaunchMode.NONE, RootCommand.decide(true, false, false));
    }
}
