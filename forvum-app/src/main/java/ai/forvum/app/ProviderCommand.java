package ai.forvum.app;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum provider} (P2-10 #35): the parent of the provider-onboarding subcommands — today just
 * {@link ProviderAddCommand} ({@code provider add <provider>}). Invoked bare it prints its usage and
 * exits 0 (picocli routes only to a leaf {@code call()}). The wizard stores an LLM provider's API key
 * owner-only under {@code ~/.forvum/state/credentials/} so a key-based {@code ModelProvider} reads it at
 * {@code resolve()} time — "fixed code, configurable behavior" with no recompile.
 */
@CommandLine.Command(
        name = "provider",
        mixinStandardHelpOptions = true,
        description = "Onboard an LLM provider: store its API key (0600) and smoke-test it.",
        subcommands = { ProviderAddCommand.class })
public class ProviderCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
