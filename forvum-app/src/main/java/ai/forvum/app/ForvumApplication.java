package ai.forvum.app;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Minimal command-mode entry point for the Forvum native binary (milestone M1).
 *
 * <p>Renders a one-line banner with TamboUI into an in-memory {@link Buffer} and prints it,
 * then exits with code {@code 0}. Rendering to a buffer is headless and needs no TTY, so the
 * same path runs unchanged in CI on both the JVM and the native binary and exits cleanly.
 *
 * <p>The interactive TUI channel (live JLine 3 terminal, event loop, streaming rendering) lands
 * at M15; the picocli CLI, {@code --help}, and the 200 ms cold-start gate land at M20.
 */
@QuarkusMain
public class ForvumApplication implements QuarkusApplication {

    static final String BANNER = "Forvum - local-first AI on the JVM";

    @Override
    public int run(String... args) {
        Rect area = new Rect(0, 0, BANNER.length(), 1);
        Buffer buffer = Buffer.empty(area);
        Paragraph banner = Paragraph.builder()
                .text(Text.from(BANNER))
                .build();
        banner.render(area, buffer);
        System.out.println(buffer.toAnsiStringTrimmed());
        return 0;
    }
}
