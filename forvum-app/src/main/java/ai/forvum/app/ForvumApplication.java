package ai.forvum.app;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

import jakarta.inject.Inject;

/**
 * Entry point for the Forvum native binary. Renders a one-line TamboUI banner into an in-memory
 * {@link Buffer} (headless — needs no TTY, so the same path runs unchanged on JVM and native and is the
 * CI smoke target), then dispatches on the configured channels (milestone M16, interim).
 *
 * <p><strong>Launch dispatch (M16):</strong> if a <em>server</em> channel is enabled — v0.1's only one
 * is the Web channel, whose vertx-http/WebSocket server is already running on background threads — the
 * binary stays alive ({@link Quarkus#waitForExit()}) to serve it; otherwise it exits {@code 0} in
 * command mode. With no {@code ~/.forvum/} (the CI native smoke) no channel is enabled, so it exits
 * cleanly. The picocli CLI, {@code --help}, proper run-modes, and the 200 ms cold-start gate land at
 * M20; the interactive TUI channel lands at M15.
 *
 * <p><strong>Interim limitation:</strong> bundling the Web channel puts vertx-http on the only runnable
 * artifact, and Quarkus binds the HTTP port at boot (RUNTIME_INIT) before {@code run()} chooses a mode —
 * so even a command-mode invocation requires a free {@code quarkus.http.port} (default 8080). A
 * command-vs-server split that leaves HTTP unbound for one-shot commands is part of the M20 run-mode rework.
 */
@QuarkusMain
public class ForvumApplication implements QuarkusApplication {

    static final String BANNER = "Forvum - local-first AI on the JVM";

    @Inject
    ChannelLauncher channels;

    @Override
    public int run(String... args) {
        printBanner();
        if (channels.shouldRunAsServer()) {
            System.out.println("Web channel ready at /ws/chat - press Ctrl+C to stop.");
            Quarkus.waitForExit();
        }
        return 0;
    }

    private static void printBanner() {
        Rect area = new Rect(0, 0, BANNER.length(), 1);
        Buffer buffer = Buffer.empty(area);
        Paragraph banner = Paragraph.builder()
                .text(Text.from(BANNER))
                .build();
        banner.render(area, buffer);
        System.out.println(buffer.toAnsiStringTrimmed());
    }
}
