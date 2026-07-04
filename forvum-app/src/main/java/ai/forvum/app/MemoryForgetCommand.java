package ai.forvum.app;

import ai.forvum.engine.memoryquery.SemanticMemoryStore;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum memory forget <key>} / {@code forvum memory forget --all} (#175): the supported surface for
 * deleting stored long-term facts, completing the inspect ({@code memory query}) + delete pair the issue
 * requires. Every delete is scoped to {@code --identity}/{@code --agent} (default {@code default}/{@code
 * main}), so it can never remove another identity's or agent's memory. Exit 0 on success; 1 on a usage
 * error. Like the other {@code memory} subcommands it reads/writes the SQLite store, so it is NOT a
 * {@code CommandMode} one-shot (it boots the full Flyway/Panache path).
 */
@CommandLine.Command(
        name = "forget",
        description = "Delete a stored long-term fact (by key), or all facts (--all), for an identity/agent.")
public class MemoryForgetCommand implements Callable<Integer> {

    @Inject
    SemanticMemoryStore store;

    @CommandLine.Parameters(
            arity = "0..1",
            paramLabel = "<key>",
            description = "The fact key to forget. Omit together with --all to clear every fact.")
    String key;

    @CommandLine.Option(
            names = "--all",
            description = "Delete every long-term fact for the identity/agent.")
    boolean all;

    @CommandLine.Option(
            names = "--identity",
            paramLabel = "<id>",
            description = "Owning identity to forget within (default ${DEFAULT-VALUE}).")
    String identityId = "default";

    @CommandLine.Option(
            names = "--agent",
            paramLabel = "<id>",
            description = "Owning agent to forget within (default ${DEFAULT-VALUE}).")
    String agentId = "main";

    @Override
    public Integer call() {
        if (all) {
            if (key != null) {
                System.err.println("Provide either a <key> or --all, not both.");
                return 1;
            }
            long removed = store.deleteAllFacts(identityId, agentId);
            System.out.println("Forgot " + removed + " fact(s) for identity '" + identityId + "', agent '"
                    + agentId + "'.");
            return 0;
        }
        if (key == null || key.isBlank()) {
            System.err.println("Specify a fact <key> to forget, or --all to clear every fact.");
            return 1;
        }
        int removed = store.deleteFact(identityId, agentId, key);
        if (removed == 0) {
            System.out.println("No fact '" + key + "' found for identity '" + identityId + "', agent '"
                    + agentId + "'.");
        } else {
            System.out.println("Forgot fact '" + key + "' for identity '" + identityId + "', agent '"
                    + agentId + "'.");
        }
        return 0;
    }
}
