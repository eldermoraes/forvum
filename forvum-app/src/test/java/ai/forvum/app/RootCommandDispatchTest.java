package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.forvum.channel.tui.TuiChannel;

import jakarta.enterprise.inject.Vetoed;

import org.junit.jupiter.api.Test;

/**
 * The default-run dispatch: an enabled interactive (TUI) channel hands the foreground to the REPL and
 * the REPL's exit code is the process exit code. A plain unit test with stubbed collaborators — the
 * stubs are {@code @Vetoed} because a CDI scope is {@code @Inherited} and an un-vetoed subclass would
 * become a second ambiguous bean in this module's {@code @QuarkusMainTest} boots.
 */
class RootCommandDispatchTest {

    @Vetoed
    private static final class InteractiveLauncher extends ChannelLauncher {
        @Override
        public boolean shouldRunInteractive() {
            return true;
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
}
