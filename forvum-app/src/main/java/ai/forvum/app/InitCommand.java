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
import java.util.Set;
import java.util.concurrent.Callable;

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

    @Inject
    ForvumHome home;

    @Override
    public Integer call() throws IOException {
        Path root = home.root();
        writeIfAbsent(root.resolve("agents").resolve("main.md"),
                "You are Forvum's main assistant. Be concise, accurate, and helpful.\n");
        writeIfAbsent(root.resolve("agents").resolve("main.json"),
                "{\n  \"primaryModel\": \"ollama:gemma4:31b-cloud\",\n"
              + "  \"allowedTools\": [\"fs.read\", \"fs.write\", \"fs.list\"]\n}\n");
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
