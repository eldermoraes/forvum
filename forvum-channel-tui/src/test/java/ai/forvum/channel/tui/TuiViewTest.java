package ai.forvum.channel.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code TuiView} renders a reply fragment for stdout. {@code plain()} is the pipeable no-ANSI path
 * (text unchanged); {@code ansi()} styles through TamboUI's headless {@code Buffer} (the same render path
 * as the app banner), rendering at the fragment's content width (the terminal wraps long lines itself).
 * Both preserve the visible reply characters — the M15 Verify requires the no-ANSI and ANSI modes to
 * "work identically" content-wise.
 */
class TuiViewTest {

    @Test
    void plainRenderReturnsTextUnchanged() {
        assertEquals("echo:hi", TuiView.plain().render("echo:hi"));
    }

    @Test
    void ansiRenderContainsTheText() {
        String out = TuiView.ansi().render("echo:hi");
        assertTrue(out.contains("echo:hi"), out);
    }

    @Test
    void ansiRenderIsStyledAndDiffersFromPlain() {
        String a = TuiView.ansi().render("echo:hi");
        assertNotEquals("echo:hi", a);
        assertTrue(a.indexOf(0x1b) >= 0, a); // 0x1b = ESC: genuine ANSI styling, not an identity no-op
    }

    @Test
    void ansiRenderDoesNotTruncateWideCharacters() {
        String out = TuiView.ansi().render("你好");
        assertTrue(out.contains("你"), out);
        assertTrue(out.contains("好"), out);
    }

    @Test
    void renderOfEmptyIsEmpty() {
        assertEquals("", TuiView.ansi().render(""));
        assertEquals("", TuiView.plain().render(""));
    }

    @Test
    void ansiRenderContainsEachLineOfMultilineText() {
        String out = TuiView.ansi().render("alpha\nbeta");
        assertTrue(out.contains("alpha"), out);
        assertTrue(out.contains("beta"), out);
    }
}
