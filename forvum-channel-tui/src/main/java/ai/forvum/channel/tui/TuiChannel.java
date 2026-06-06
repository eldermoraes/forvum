package ai.forvum.channel.tui;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.core.event.FallbackTriggered;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.core.event.ToolInvoked;
import ai.forvum.core.event.ToolResult;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The terminal (TUI) channel's inbound surface (ULTRAPLAN section 5.3, M15). A line-based stdin REPL:
 * each non-blank line is one turn, handed to the engine as a {@link ChannelMessage} and driven through
 * the SDK {@link ChannelTurnDriver} (the engine's {@code TurnService} at runtime), streaming each
 * rendered {@link AgentEvent} back to stdout. The line-based loop is pipeable — the contract the M15
 * Verify exercises with {@code forvum-app -Dforvum.no-ansi < input.txt} — and needs no terminal
 * raw-mode, so piped (non-TTY) stdin works unchanged.
 *
 * <p>v0.1 ships both the plain (no-ANSI) streaming path and the styled ANSI path (TamboUI's headless
 * {@code Buffer}); there is no terminal backend and no native carve-out. The {@code forvum.no-ansi} switch
 * and the foreground launch are wired in {@code forvum-app}.
 */
@ApplicationScoped
public class TuiChannel {

    /** Channel id stamped on every inbound {@link ChannelMessage}; matches the plugin extension id. */
    static final String CHANNEL_ID = "tui";

    @Inject
    ChannelTurnDriver turns;

    /**
     * Plain-stdout mode (no ANSI): {@code forvum.no-ansi=true} (system property or {@code FORVUM_NO_ANSI})
     * makes the REPL bypass TamboUI and print raw text — first-class so the channel stays pipeable and
     * writes raw stdout to a non-TTY/redirected stream (the M15 Verify's
     * {@code forvum-app -Dforvum.no-ansi < input.txt}). Defaults to styled (ANSI) for an interactive shell.
     */
    @ConfigProperty(name = "forvum.no-ansi", defaultValue = "false")
    boolean noAnsi;

    /**
     * Foreground entry point invoked by {@code ForvumApplication} when the TUI channel is enabled: run the
     * REPL on the process stdin/stdout, choosing the plain or styled view per {@link #noAnsi}. Blocks until
     * end-of-input, then returns {@code 0}. The TUI is an interactive foreground channel (not a server
     * channel), so the binary runs it in the foreground rather than {@code Quarkus.waitForExit()}.
     */
    public int run() {
        TuiView view = noAnsi ? TuiView.plain() : TuiView.ansi();
        return repl(System.in, System.out, view);
    }

    /**
     * Run the REPL: read one line per turn from {@code in}, drive it through the {@link ChannelTurnDriver},
     * and stream each rendered {@link AgentEvent} to {@code out} via {@code view} ({@link TuiView#plain()}
     * for the pipeable no-ANSI path, {@link TuiView#ansi()} for the styled interactive path). Blank
     * lines are skipped. Returns {@code 0} at end of input. Streams and the view are parameters so the loop
     * is exercised in tests with piped input/output (and by {@code run()} with
     * {@code System.in}/{@code System.out}).
     */
    int repl(InputStream in, PrintStream out, TuiView view) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String user = nativeUserId();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                ChannelMessage message = new ChannelMessage(CHANNEL_ID, user, line, Instant.now());
                turns.dispatch(message, event -> {
                    String rendered = render(event);
                    if (rendered != null && !rendered.isEmpty()) {
                        out.print(view.render(rendered));
                    }
                });
                out.println();
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading TUI stdin.", e);
        }
        return 0;
    }

    /** The native user id stamped on each turn: the OS username, or {@code local} if unavailable. */
    static String nativeUserId() {
        String user = System.getProperty("user.name");
        return (user == null || user.isBlank()) ? "local" : user;
    }

    /**
     * Render an {@link AgentEvent} to the text the terminal user sees, or an empty string to print
     * nothing. Exhaustive over the sealed event type (no {@code default} branch), mirroring the web
     * channel: v0.1 (streaming Option B) emits only {@link TokenDelta} (the reply) then a terminal
     * {@link Done} (whose reply is already carried by the {@code TokenDelta}, so it is skipped); an
     * {@link ErrorEvent} surfaces its message so a failed turn is visible; the tool-lifecycle events are
     * not surfaced yet. Package-private so {@code TuiRenderTest} can cover every arm.
     */
    static String render(AgentEvent event) {
        return switch (event) {
            case TokenDelta delta -> delta.text();
            case ErrorEvent error -> error.message();
            case Done ignored -> "";
            case ToolInvoked ignored -> "";
            case ToolResult ignored -> "";
            case FallbackTriggered ignored -> "";
        };
    }
}
