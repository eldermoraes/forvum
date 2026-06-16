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
 * {@code doctor} validates the {@code ~/.forvum} configuration and exits non-zero on problems;
 * {@code replay} reproduces a stored session's recorded message and tool sequence; {@code plugin install}
 * resolves a Maven coordinate into {@code ~/.forvum/plugins/} (fast-jar drop-in; native users rebuild);
 * {@code skill install} downloads a skill {@code .md} into {@code ~/.forvum/skills/}; {@code mcp add}/
 * {@code mcp list} manage the remote MCP-server registry under {@code ~/.forvum/mcp-servers/};
 * {@code pair approve}/{@code pair reject} govern a paired device's requested scopes and {@code devices}
 * lists them.
 */
@CommandLine.Command(
        name = "forvum",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Forvum - local-first, open-source personal AI agents on the JVM.",
        subcommands = { InitCommand.class, AskCommand.class, DoctorCommand.class, SessionReplayCommand.class,
                PluginCommand.class, SkillCommand.class, McpCommand.class, CopilotCommand.class,
                PairCommand.class, DevicesCommand.class })
public class RootCommand implements Callable<Integer> {

    static final String BANNER = "Forvum - local-first AI on the JVM";

    /** Block-letter boot banner shown on an interactive terminal (parity with the conference demo). */
    private static final String BIG_BANNER = """
            ███████╗ ██████╗ ██████╗ ██╗   ██╗██╗   ██╗███╗   ███╗
            ██╔════╝██╔═══██╗██╔══██╗██║   ██║██║   ██║████╗ ████║
            █████╗  ██║   ██║██████╔╝██║   ██║██║   ██║██╔████╔██║
            ██╔══╝  ██║   ██║██╔══██╗╚██╗ ██╔╝██║   ██║██║╚██╔╝██║
            ██║     ╚██████╔╝██║  ██║ ╚████╔╝ ╚██████╔╝██║ ╚═╝ ██║
            ╚═╝      ╚═════╝ ╚═╝  ╚═╝  ╚═══╝   ╚═════╝ ╚═╝     ╚═╝
            """;

    /** Printed when no channel is configured, so a fresh install never exits silently. */
    static final String NO_CHANNEL_HINT =
            "No channels are configured. Run `forvum init` to scaffold ~/.forvum with a starter"
                    + " agent and TUI channel, then run `forvum` again.";

    @Inject
    ChannelLauncher channels;

    @Inject
    TuiChannel tui;

    @Override
    public Integer call() {
        System.out.println(banner(interactiveTerminal()));
        if (channels.shouldRunInteractive()) {
            return tui.run();
        }
        if (channels.shouldRunAsServer()) {
            System.out.println("Server channel(s) ready - press Ctrl+C to stop.");
            Quarkus.waitForExit();
            return 0;
        }
        System.out.println(NO_CHANNEL_HINT);
        return 0;
    }

    /**
     * The boot banner: the block-letter FORVUM banner plus tagline on a real terminal; piped or
     * redirected runs keep the single-line tagline (TamboUI-rendered) so piped stdout stays clean.
     */
    static String banner(boolean interactive) {
        if (interactive) {
            return BIG_BANNER + BANNER;
        }
        Rect area = new Rect(0, 0, BANNER.length(), 1);
        Buffer buffer = Buffer.empty(area);
        Paragraph banner = Paragraph.builder()
                .text(Text.from(BANNER))
                .build();
        banner.render(area, buffer);
        return buffer.toAnsiStringTrimmed();
    }

    /** True when the process is attached to an interactive terminal (mirrors the TUI channel's check). */
    private static boolean interactiveTerminal() {
        java.io.Console console = System.console();
        return console != null && console.isTerminal();
    }
}
