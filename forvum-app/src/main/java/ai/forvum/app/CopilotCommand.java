package ai.forvum.app;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum copilot} (#42): parent of the GitHub Copilot subcommands — {@link CopilotLoginCommand}
 * ({@code copilot login}). Invoked bare it prints its usage and exits 0. Copilot is an OpenAI-compatible
 * model provider authenticated by a device-code OAuth login; once logged in, {@code copilot:<model>} agents
 * resolve through the {@code forvum-provider-copilot} extension.
 */
@CommandLine.Command(
        name = "copilot",
        mixinStandardHelpOptions = true,
        description = "Manage GitHub Copilot authentication (device-code login).",
        subcommands = { CopilotLoginCommand.class })
public class CopilotCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
