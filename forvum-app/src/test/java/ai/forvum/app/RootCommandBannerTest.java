package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The boot banner adapts to the terminal: an interactive (TTY) launch gets the block-letter FORVUM
 * banner (demo parity) followed by the tagline; a piped/redirected launch keeps the single-line
 * tagline so piped stdout stays clean and the command-mode smoke output is unchanged.
 */
class RootCommandBannerTest {

    @Test
    void interactiveBannerIsTheBlockLetterDemoBannerPlusTagline() {
        String banner = RootCommand.banner(true);
        assertTrue(banner.contains("█"), banner);
        assertTrue(banner.contains(RootCommand.BANNER), banner);
    }

    @Test
    void pipedBannerIsTheSingleLineTaglineWithoutTheBlockArt() {
        String banner = RootCommand.banner(false);
        assertTrue(banner.contains(RootCommand.BANNER), banner);
        assertFalse(banner.contains("█"), banner);
    }
}
