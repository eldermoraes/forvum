package ai.forvum.app;

import ai.forvum.engine.config.ForvumHome;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code forvum init} (M20, e2e X2): scaffold {@code ~/.forvum} with a runnable example — a {@code main}
 * agent (pinned to a local Ollama model), a default identity, and an enabled TUI channel — so the next
 * {@code forvum} launch starts an interactive session. Idempotent: an existing file is left untouched so
 * a re-run never clobbers operator edits. On POSIX the tree is owner-only (0700 dirs / 0600 files) — it
 * later holds channel credentials (e.g. {@code channels/telegram.json} botToken), so it must not inherit
 * the world-readable umask default.
 *
 * <p>The {@code init} command name is also recognized as a one-shot by {@code CommandMode} (engine) so its
 * cold-start skips the DB/watcher/cron observers — keep the two in sync if this is renamed.
 */
@CommandLine.Command(
        name = "init",
        description = "Scaffold ~/.forvum with an example agent, identity, and TUI channel.")
public class InitCommand implements Callable<Integer> {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    /**
     * The scaffolded {@code main} agent's default tool belt (#184): the tools that WORK (or degrade to
     * user-caused configuration guidance) with zero setup — the workspace-confined filesystem tools, the
     * read-only web tools ({@code web.search} is keyless out of the box, #192), and the local zero-config
     * memory tools (#175). Explicit ids, NOT globs, so a future binary that ships a tool matching
     * {@code fs.*}/{@code web.*} never silently widens the default belt. EXCLUDED by default: the
     * confirm-gated / fail-closed {@code shell.exec} / {@code sandbox.run}, the operator-Chrome
     * {@code browser.*}, the piper-dependent {@code tts.speak}, and {@code mcp.*} (no servers on a fresh
     * install). An operator adds any of those to the belt by editing one file. Shared with the tests so the
     * scaffold and its assertions read from one source.
     */
    static final List<String> DEFAULT_ALLOWED_TOOLS = List.of(
            "fs.read", "fs.write", "fs.list", "web.fetch", "web.search", "memory.save", "memory.recall");

    /**
     * The scaffolded identity id (#184 D2): the persona's {@code identityId} pointer to
     * {@code identities/default.json}. Without it a fresh home resolves every turn to the anonymous
     * identity (no scopes) and the whole belt is filtered out — so the tools would never be offered. The
     * identity file (already scaffolded below) carries no {@code roles}, so a resolved {@code default}
     * identity gets the permissive {@code default-user} scope set (#168) and the belt is offered.
     */
    static final String DEFAULT_IDENTITY_ID = "default";

    @Inject
    ForvumHome home;

    @Override
    public Integer call() throws IOException {
        Path root = home.root();
        writeIfAbsent(root.resolve("agents").resolve("main.md"),
                "You are Forvum's main assistant. Be concise, accurate, and helpful.\n");
        String belt = DEFAULT_ALLOWED_TOOLS.stream()
                .map(tool -> "\"" + tool + "\"")
                .collect(Collectors.joining(", "));
        writeIfAbsent(root.resolve("agents").resolve("main.json"),
                "{\n  \"primaryModel\": \"ollama:gemma4:31b-cloud\",\n"
              + "  \"identityId\": \"" + DEFAULT_IDENTITY_ID + "\",\n"
              + "  \"allowedTools\": [" + belt + "]\n}\n");
        writeIfAbsent(root.resolve("identities").resolve("default.json"),
                "{\n  \"channelAccounts\": {}\n}\n");
        writeIfAbsent(root.resolve("channels").resolve("tui.json"),
                "{\n  \"enabled\": true\n}\n");
        System.out.println("Initialized Forvum home at " + root);
        return 0;
    }

    private static void writeIfAbsent(Path file, String content) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        createDirectories(file.getParent());
        Files.writeString(file, content);
        if (POSIX) {
            Files.setPosixFilePermissions(file, FILE_PERMS);
        }
    }

    /** Create {@code dir} (and parents) owner-only on POSIX; the platform default elsewhere (e.g. Windows). */
    private static void createDirectories(Path dir) throws IOException {
        if (POSIX) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
        } else {
            Files.createDirectories(dir);
        }
    }
}
