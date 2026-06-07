package ai.forvum.app;

import ai.forvum.channel.tui.TuiChannel;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;

import io.quarkus.runtime.Quarkus;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * The picocli root command (M20). {@code --help}/{@code --version} are handled by picocli
 * ({@code mixinStandardHelpOptions}) without ever reaching {@link #call()}; the {@code init} subcommand
 * scaffolds {@code ~/.forvum}. With no subcommand, {@link #call()} is the default run — the M15/M16
 * channel dispatch moved here from {@code ForvumApplication}: render the banner, then run an interactive
 * foreground channel (TUI) or stay alive for a server channel (Web/Telegram), else exit {@code 0}. The
 * {@code init} subcommand scaffolds {@code ~/.forvum}; {@code ask} runs one non-interactive turn and exits;
 * {@code doctor} validates the {@code ~/.forvum} configuration and exits non-zero on problems.
 */
@CommandLine.Command(
        name = "forvum",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Forvum - local-first, open-source personal AI agents on the JVM.",
        subcommands = { InitCommand.class, AskCommand.class, DoctorCommand.class })
public class RootCommand implements Callable<Integer> {

    static final String BANNER = "Forvum - local-first AI on the JVM";

    @Inject
    ChannelLauncher channels;

    @Inject
    TuiChannel tui;

    @Override
    public Integer call() {
        printBanner();
        if (channels.shouldRunInteractive()) {
            return tui.run();
        }
        if (channels.shouldRunAsServer()) {
            System.out.println("Server channel(s) ready - press Ctrl+C to stop.");
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
