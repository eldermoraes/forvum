package ai.forvum.app;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum skill} (P2-7, ULTRAPLAN section 7.2 item 7): the parent of the skill subcommands. Today it
 * groups a single child, {@link SkillInstallCommand} ({@code skill install <url>}). Invoked bare it prints
 * its usage and exits 0 — picocli only routes to a leaf {@code call()}. Skills are operator-trusted prompt
 * templates under {@code ~/.forvum/skills/} (never code), hot-loaded and invocable by the skill tool.
 */
@CommandLine.Command(
        name = "skill",
        mixinStandardHelpOptions = true,
        description = "Install and manage skills (named prompt templates under ~/.forvum/skills/).",
        subcommands = { SkillInstallCommand.class })
public class SkillCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
