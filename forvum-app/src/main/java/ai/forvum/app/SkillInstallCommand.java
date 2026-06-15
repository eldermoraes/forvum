package ai.forvum.app;

import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.config.SkillInstallException;
import ai.forvum.engine.config.SkillInstallResult;
import ai.forvum.engine.config.SkillInstaller;
import ai.forvum.engine.config.SkillSpecException;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum skill install <url>} (P2-7 #32, ULTRAPLAN section 7.2 item 7): download a skill prompt
 * template ({@code .md}) from a URL (http, https, or file — a git-raw URL or gist), validate it through
 * the engine's real {@link SkillInstaller}/{@code SkillReader} (a malformed front-matter or {@code
 * inputSchema} is rejected, not written), and write it owner-only into {@code ~/.forvum/skills/}. The dir
 * is hot-loaded ({@code ConfigWatcher}), so the skill becomes invocable by the skill tool without a
 * restart.
 *
 * <p>Routed by picocli to this leaf {@code call()} (not {@link RootCommand#call()}), so no channel/server
 * dispatch runs. It only downloads + validates + writes a file, so — like {@code init}/{@code doctor}/
 * {@code plugin} — it needs neither the DB nor the watcher; it is recognized as a {@code CommandMode}
 * one-shot via its {@code skill} token. NATIVE: pure file write + {@code java.net.http} download.
 */
@CommandLine.Command(
        name = "install",
        description = "Install a skill (a named prompt template .md) from a URL into ~/.forvum/skills/.")
public class SkillInstallCommand implements Callable<Integer> {

    @Inject
    ForvumHome home;

    @Inject
    SkillInstaller installer;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<url>",
            description = "URL of the skill .md to install (http, https, or file — e.g. a git-raw URL).")
    String url;

    @Override
    public Integer call() {
        SkillInstallResult result;
        try {
            result = installer.install(url, home.skills());
        } catch (SkillInstallException | SkillSpecException e) {
            System.err.println("Skill install failed: " + e.getMessage());
            return 1;
        }
        System.out.println("Installed skill '" + result.id() + "' -> " + result.path());
        System.out.println("It is hot-loaded into ~/.forvum/skills/ and invocable by the skill tool.");
        return 0;
    }
}
