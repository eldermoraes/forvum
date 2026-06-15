package ai.forvum.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The one-shot-command detection that lets startup observers skip DB/watcher work for {@code --help}/
 * {@code --version}/{@code init} (M20 cold-start lever). Pure logic over the raw argument array, plus the
 * injected-bean wiring that the startup observers actually read.
 */
class CommandModeTest {

    @Test
    void recognisesOneShotCommands() {
        assertTrue(CommandMode.isOneShotCommand(new String[] {"--help"}));
        assertTrue(CommandMode.isOneShotCommand(new String[] {"-h"}));
        assertTrue(CommandMode.isOneShotCommand(new String[] {"--version"}));
        assertTrue(CommandMode.isOneShotCommand(new String[] {"-V"}));
        assertTrue(CommandMode.isOneShotCommand(new String[] {"init"}));
        assertTrue(CommandMode.isOneShotCommand(new String[] {"doctor"}),
                "'doctor' validates files and exits without a turn — it must skip the DB/watcher/cron boot");
        assertTrue(CommandMode.isOneShotCommand(new String[] {"plugin", "install", "g:a:1.0.0"}),
                "'plugin' only resolves+writes a JAR — it must skip the DB/watcher/cron boot (P2-6)");
        assertTrue(CommandMode.isOneShotCommand(new String[] {"skill", "install", "https://x/skill.md"}),
                "'skill' only downloads+writes a .md — it must skip the DB/watcher/cron boot (P2-7)");
    }

    @Test
    void treatsServerAndInteractiveInvocationsAsNotOneShot() {
        assertFalse(CommandMode.isOneShotCommand(new String[] {}), "no args = default run (channels)");
        assertFalse(CommandMode.isOneShotCommand(null), "null args = default run");
        assertFalse(CommandMode.isOneShotCommand(new String[] {"tui"}),
                "an unrecognised arg is not a one-shot command (falls through to the default run)");
    }

    @Test
    void doesNotMatchBareHelpOrVersionWhichPicocliDoesNotDispatch() {
        // RootCommand registers only mixinStandardHelpOptions (--help/-h/--version/-V) + the 'init'
        // subcommand. Bare 'help'/'version' are unmatched positionals (picocli errors), so they must NOT
        // be treated as clean one-shots that skip startup work. Keep this set in sync with RootCommand.
        assertFalse(CommandMode.isOneShotCommand(new String[] {"help"}),
                "'help' is not a registered subcommand — not a one-shot");
        assertFalse(CommandMode.isOneShotCommand(new String[] {"version"}),
                "'version' is not a registered subcommand — not a one-shot");
    }

    @Test
    void injectedBeanReadsTheArgsItWasConstructedWith() {
        assertTrue(new CommandMode(new String[] {"--help"}).isOneShot(),
                "a one-shot invocation must report isOneShot()");
        assertFalse(new CommandMode(new String[] {}).isOneShot(),
                "a default run must report not-one-shot");
    }
}
