package ai.forvum.channel.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * Renders a reply fragment for the TUI's stdout (ULTRAPLAN section 5.3, M15). Two modes:
 *
 * <ul>
 *   <li>{@link #plain()} — the pipeable no-ANSI path: returns the text unchanged (no TamboUI), so
 *       {@code forvum-app -Dforvum.no-ansi < input.txt} writes raw stdout to a non-TTY/redirected
 *       stream (the reason {@code --no-ansi} is first-class).</li>
 *   <li>{@link #ansi()} — the interactive ANSI path: styles the text through TamboUI's headless
 *       {@link Buffer}/{@link Paragraph} — the same render path the app banner uses, which is pure Java
 *       (no terminal backend, no native syscall) and already native-proven. The fragment is rendered to a
 *       buffer sized to its own content; the terminal handles its own line wrapping.</li>
 * </ul>
 *
 * <p><strong>No terminal backend in v0.1 (native-first).</strong> TamboUI 0.3.0's terminal backends both
 * fail the GraalVM 25 native build — {@code tamboui-jline3-backend}'s {@code org.jline:jline} uber jar
 * bundles a JNA provider that breaks {@code link-at-build-time}, and {@code tamboui-panama-backend}'s FFM
 * downcall ({@code LibC.tcgetattr}) is rejected by native-image ("unexpected input ... linkToNative").
 * Both are only needed for terminal-size auto-detection, so v0.1 omits the backend entirely: ANSI output
 * is rendered at content width via the pure headless {@link Buffer} (no native terminal call), keeping the
 * binary fully native. Terminal-width auto-detection is deferred to a TamboUI version that native-builds a
 * backend on GraalVM 25 (revisit at the TamboUI bump / M20). Both modes preserve the visible reply
 * characters; ANSI mode normalizes line separators (to the terminal's {@code \r\n}) and trims trailing
 * whitespace per row.
 */
public final class TuiView {

    private final boolean ansi;

    private TuiView(boolean ansi) {
        this.ansi = ansi;
    }

    /** The no-ANSI path: render text unchanged (pipeable, no TamboUI). */
    static TuiView plain() {
        return new TuiView(false);
    }

    /** The ANSI path: render text through TamboUI's headless {@link Buffer} (pure Java, native-safe). */
    static TuiView ansi() {
        return new TuiView(true);
    }

    /**
     * Render a reply fragment. Plain mode returns it unchanged; ANSI mode renders it through a TamboUI
     * {@link Paragraph}/{@link Buffer} sized to the fragment's own content (the terminal wraps long lines
     * itself). An empty fragment renders to the empty string (printed as nothing).
     */
    String render(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!ansi) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        int width = 1;
        for (String line : lines) {
            width = Math.max(width, displayWidth(line));
        }
        Rect area = new Rect(0, 0, width, lines.length);
        Buffer buffer = Buffer.empty(area);
        Paragraph paragraph = Paragraph.builder().text(Text.from(text)).build();
        paragraph.render(area, buffer);
        return buffer.toAnsiStringTrimmed();
    }

    /**
     * Terminal display width of {@code line} in cells: double-width (CJK/Hangul/Kana/fullwidth) code
     * points occupy two cells, every other code point one. {@code String.length()} counts UTF-16 code
     * units, which under-sizes the {@link Buffer} for wide glyphs and truncates the render (e.g. "你好"
     * → "你"); sizing by cell width keeps every glyph visible.
     */
    private static int displayWidth(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            width += isWide(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    /** Whether {@code cp} renders as a double-width (two-cell) glyph in a terminal. */
    private static boolean isWide(int cp) {
        if (Character.isIdeographic(cp)) {
            return true;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }
}
