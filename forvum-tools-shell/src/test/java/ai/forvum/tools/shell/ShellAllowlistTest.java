package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Unit tests for {@link ShellAllowlist} — the on-demand {@code tools/shell.json} reader (QdrantConfig
 * tree-walk pattern) and the {@link ShellAllowlist.Spec#validate(List)} allowlist rules (ULTRAPLAN §9.2.5,
 * DP-7 exact-match). The reader is fail-closed: an absent file refuses every command.
 */
class ShellAllowlistTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ShellAllowlist.Spec parse(String json) {
        try {
            return ShellAllowlist.parse(MAPPER.readTree(json));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void absentFileIsFailClosed(@TempDir Path dir) {
        ShellAllowlist allowlist = new ShellAllowlist(dir.resolve("tools").resolve("shell.json"));

        ShellAllowlist.Spec spec = allowlist.read();

        assertTrue(spec.allowedCommands().isEmpty(), "an absent file allows no command");
        assertThrows(ShellExecException.class, () -> spec.validate(List.of("echo", "hi")),
                "fail-closed: every invocation is refused when no allowlist exists");
    }

    @Test
    void readsAnAllowlistFromDisk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("tools").resolve("shell.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"allowedCommands\":[\"git\",\"ls\"],\"timeoutSeconds\":30}");

        ShellAllowlist.Spec spec = new ShellAllowlist(file).read();

        assertEquals(List.of("git", "ls"), spec.allowedCommands());
        assertEquals(30, spec.timeoutSeconds());
    }

    @Test
    void malformedFileThrows(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("tools").resolve("shell.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");

        assertThrows(RuntimeException.class, () -> new ShellAllowlist(file).read());
    }

    @Test
    void defaultTimeoutWhenAbsentOrInvalid() {
        assertEquals(ShellAllowlist.DEFAULT_TIMEOUT_SECONDS,
                parse("{\"allowedCommands\":[\"x\"]}").timeoutSeconds());
        assertEquals(ShellAllowlist.DEFAULT_TIMEOUT_SECONDS,
                parse("{\"allowedCommands\":[\"x\"],\"timeoutSeconds\":0}").timeoutSeconds(),
                "a non-positive timeout falls back to the default");
    }

    @Test
    void workingDirIsOptional() {
        assertEquals(Optional.empty(), parse("{\"allowedCommands\":[\"x\"]}").workingDir());
        assertEquals(Optional.of("sub"),
                parse("{\"allowedCommands\":[\"x\"],\"workingDir\":\"sub\"}").workingDir());
    }

    @Test
    void validateRejectsEmptyArgv() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"echo\"]}");

        assertThrows(ShellExecException.class, () -> spec.validate(List.of()));
    }

    @Test
    void validateAcceptsAnExactlyAllowedBareCommand() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"echo\"]}");

        assertDoesNotThrow(() -> spec.validate(List.of("echo", "hello")));
    }

    @Test
    void validateRejectsACommandNotInTheAllowlist() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"echo\"]}");

        ShellExecException e = assertThrows(ShellExecException.class,
                () -> spec.validate(List.of("rm", "-rf", "/")));
        assertTrue(e.getMessage().contains("not in the tools/shell.json allowlist"));
    }

    @Test
    void validateAcceptsAnAllowedAbsolutePath() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"/bin/echo\"]}");

        assertDoesNotThrow(() -> spec.validate(List.of("/bin/echo", "hi")));
    }

    @Test
    void validateRejectsARelativePathWithASeparator() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"./evil\",\"echo\"]}");

        ShellExecException e = assertThrows(ShellExecException.class,
                () -> spec.validate(List.of("./evil")));
        assertTrue(e.getMessage().contains("relative path with a separator"),
                "a relative path with a separator is rejected before the allowlist check");
    }

    @Test
    void validateEnforcesArgvPrefixVectorsWhenPresent() {
        ShellAllowlist.Spec spec = parse(
                "{\"allowedCommands\":[\"git\"],\"allowedArgs\":{\"git\":[[\"status\"],[\"log\",\"--oneline\"]]}}");

        assertDoesNotThrow(() -> spec.validate(List.of("git", "status")),
                "an exact prefix-vector match is allowed");
        assertDoesNotThrow(() -> spec.validate(List.of("git", "status", "--short")),
                "a longer call whose head matches a vector is allowed (prefix match)");
        assertDoesNotThrow(() -> spec.validate(List.of("git", "log", "--oneline", "-5")),
                "the second vector is also honored");
        assertThrows(ShellExecException.class, () -> spec.validate(List.of("git", "push")),
                "an argument tail matching no vector is refused");
        assertThrows(ShellExecException.class, () -> spec.validate(List.of("git", "lo")),
                "a non-element-wise (substring) match is refused");
    }

    @Test
    void noArgsEntryForACommandAllowsAnyArguments() {
        // git has an allowedArgs entry; ls does NOT — so ls allows any args.
        ShellAllowlist.Spec spec = parse(
                "{\"allowedCommands\":[\"git\",\"ls\"],\"allowedArgs\":{\"git\":[[\"status\"]]}}");

        assertDoesNotThrow(() -> spec.validate(List.of("ls", "-la", "/tmp")),
                "a command with no allowedArgs entry accepts any arguments");
    }
}
