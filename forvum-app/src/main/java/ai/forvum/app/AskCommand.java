package ai.forvum.app;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.engine.pairing.DeviceRegistry;
import ai.forvum.sdk.ApprovalContext;
import ai.forvum.sdk.ChannelTurnDriver;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code forvum ask "<prompt>"}: run ONE non-interactive turn for the {@code main} agent, print the
 * assistant reply to stdout, and exit. It is the out-of-process turn entry the native real-provider smoke
 * needs — {@code @QuarkusMainIntegrationTest}/{@code @Launch} expose no stdin, so the TUI stdin REPL is
 * unreachable from a native integration test; a subcommand is.
 *
 * <p>The turn runs through the SDK {@link ChannelTurnDriver} (the engine's {@code TurnService}) — the same
 * seam every channel uses — which binds {@code CURRENT_AGENT}/{@code CURRENT_TURN}, resolves the identity,
 * activates the request context, drives {@code Agent.respond}, and ledgers the turn. So {@code ask} reuses
 * the production turn path rather than re-implementing the registry/{@code ScopedValue} dance.
 *
 * <p>Unlike {@code --help}/{@code --version}/{@code init}, {@code ask} is deliberately NOT a
 * {@code CommandMode} one-shot: the turn needs Flyway and the DB (it writes {@code messages}/
 * {@code provider_calls}), so it boots the full path and exits after the single turn. picocli routes
 * {@code ask} here (not to {@link RootCommand#call()}), so no channel/server dispatch runs.
 */
@CommandLine.Command(
        name = "ask",
        description = "Run a single non-interactive turn for the main agent and print the reply.")
public class AskCommand implements Callable<Integer> {

    /**
     * The channel id presented to the engine for a CLI-driven turn. It is the engine's distinguished,
     * always-paired CLI device ({@link DeviceRegistry#CLI}) so enabling device pairing never locks out the
     * host operator's own terminal — the host CLI is the inherently-trusted primary surface (P2-4).
     */
    private static final String CLI_CHANNEL = DeviceRegistry.CLI;

    @Inject
    ChannelTurnDriver turns;

    @CommandLine.Parameters(
            arity = "1..*",
            paramLabel = "<prompt>",
            description = "Prompt for the main agent (multiple words are joined with a single space).")
    List<String> prompt;

    @Override
    public Integer call() {
        ChannelMessage message = new ChannelMessage(
                CLI_CHANNEL, nativeUserId(), String.join(" ", prompt), Instant.now());

        // dispatch() runs synchronously on this thread and invokes the sink once per event, in order
        // (v0.1: a TokenDelta carrying the full reply, then a terminal Done). The one-shot ask renders the
        // terminal event only: Done -> the reply to stdout (exit 0); ErrorEvent -> a diagnostic to stderr
        // (exit 1). The failed turn is already ledgered in provider_calls by the model decorator (M7).
        int[] exitCode = {0};
        // P2-14 #39: ask is a one-shot with no human at the keyboard mid-turn and no dashboard requester, so
        // a confirm-required tool must deny immediately rather than block for an approval that never arrives.
        ScopedValue.where(ApprovalContext.NON_INTERACTIVE, Boolean.TRUE).run(() ->
                turns.dispatch(message, event -> {
                    switch (event) {
                        case Done done -> System.out.println(done.finalMessage());
                        case ErrorEvent error -> {
                            System.err.println("ask failed [" + error.code() + "]: " + error.message());
                            exitCode[0] = 1;
                        }
                        default -> {
                            // Intermediate events (TokenDelta, tool events) are not rendered by the one-shot
                            // ask; the full reply arrives on Done. Per-token streaming is a later upgrade.
                        }
                    }
                }));
        return exitCode[0];
    }

    /** The OS user as the native user id, falling back to {@code "local"} so the id is always non-blank. */
    private static String nativeUserId() {
        String user = System.getProperty("user.name");
        return user == null || user.isBlank() ? "local" : user;
    }
}
