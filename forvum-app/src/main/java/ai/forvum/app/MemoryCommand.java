package ai.forvum.app;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum memory} (P3-2, #50): the parent of the queryable-semantic-memory subcommands —
 * {@link MemoryQueryCommand} ({@code memory query '<SQL>'}, a read-only SELECT over the SQLite store),
 * {@link MemorySearchCommand} ({@code memory search '<text>'}, nearest-neighbor over the stored
 * embeddings), and {@link MemoryReindexCommand} ({@code memory reindex}, populate the embedding BLOBs).
 * Invoked bare it prints its usage and exits 0 (picocli routes only to a leaf {@code call()}).
 *
 * <p>Every {@code memory} subcommand reads (and {@code reindex} writes) the operational SQLite database, so
 * — like {@code ask}/{@code replay} and unlike {@code doctor}/{@code init} — {@code memory} is deliberately
 * NOT a {@code CommandMode} one-shot: it boots the full Flyway/Panache path.
 */
@CommandLine.Command(
        name = "memory",
        mixinStandardHelpOptions = true,
        description = "Query and search the semantic memory store (SQL queries + vector nearest-neighbor).",
        subcommands = { MemoryQueryCommand.class, MemorySearchCommand.class, MemoryReindexCommand.class })
public class MemoryCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
