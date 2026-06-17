package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Additional branch coverage of {@link ShellToolProvider#invoke(String, Map)} beyond
 * {@link ShellToolProviderTest}: the spec-supplied {@code workingDir} fallback (no model-supplied
 * {@code workingDir}) and the argv-element validation (a null element is a programming error).
 */
class ShellToolProviderEdgeTest {

    private static String binaryOrSkip(String... candidates) {
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        assumeTrue(false, "no POSIX binary among " + List.of(candidates));
        return null; // unreachable
    }

    private static ShellToolProvider providerFor(Path home, Path workspace) {
        ShellToolProvider provider = new ShellToolProvider();
        provider.allowlist = new ShellAllowlist(home.resolve("tools").resolve("shell.json"));
        provider.workspace = new WorkspaceRoot(workspace);
        return provider;
    }

    private static void writeAllowlist(Path home, String json) throws IOException {
        Path file = home.resolve("tools").resolve("shell.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    void invokeUsesTheSpecWorkingDirWhenTheCallOmitsIt(@TempDir Path home, @TempDir Path workspace)
            throws IOException {
        // No model-supplied workingDir -> the provider falls back to the spec's workingDir (the
        // requestedWorkingDir == null branch + spec.workingDir().orElse(null)).
        String pwd = binaryOrSkip("/bin/pwd", "/usr/bin/pwd");
        Path sub = Files.createDirectories(workspace.resolve("specdir"));
        writeAllowlist(home, "{\"allowedCommands\":[\"" + pwd + "\"],\"workingDir\":\"specdir\"}");
        ShellToolProvider provider = providerFor(home, workspace);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("argv", List.of(pwd));
        // deliberately NO "workingDir" key in the arguments
        String out = provider.invoke("shell.exec", args);

        assertEquals(sub.toRealPath().toString(), out.strip(),
                "with no model workingDir, the tools/shell.json workingDir is used");
    }

    @Test
    void invokeRejectsANullArgvElement(@TempDir Path home, @TempDir Path workspace) {
        ShellToolProvider provider = providerFor(home, workspace);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("argv", Arrays.asList("echo", null));

        assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("shell.exec", args),
                "a null argv element is a programming error (not a JSON-string vector)");
    }
}
