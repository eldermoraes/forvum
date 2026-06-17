package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Branch-level coverage of {@link ShellAllowlist.Spec#validate(List)} — the security-critical argv
 * validator. The existing {@link ShellAllowlistTest} covers the happy paths and the common rejections;
 * this fills the under-exercised rejection/acceptance edges (null/blank argv[0], the backslash-separator
 * forms, the prefix-vector length guard) so each is asserted as a REAL behavior, not vacuously.
 */
class ShellAllowlistSpecValidateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ShellAllowlist.Spec parse(String json) {
        try {
            return ShellAllowlist.parse(MAPPER.readTree(json));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void validateRejectsNullArgv() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"echo\"]}");

        ShellExecException e = assertThrows(ShellExecException.class, () -> spec.validate(null));
        assertTrue(e.getMessage().contains("non-empty argv"),
                "a null argv is rejected (the same arm as empty argv)");
    }

    @Test
    void validateRejectsABlankCommand() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"echo\"]}");

        ShellExecException e = assertThrows(ShellExecException.class,
                () -> spec.validate(List.of("   ", "x")));
        assertTrue(e.getMessage().contains("must be non-blank"),
                "a blank argv[0] is rejected before the allowlist check");
    }

    @Test
    void validateRejectsANullCommand() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"echo\"]}");
        // A null argv[0] hits the same non-blank guard; List.of forbids null, so use Arrays.asList.
        List<String> argv = Arrays.asList(null, "x");

        ShellExecException e = assertThrows(ShellExecException.class, () -> spec.validate(argv));
        assertTrue(e.getMessage().contains("must be non-blank"),
                "a null argv[0] is rejected by the non-blank guard");
    }

    @Test
    void validateRejectsABackslashRelativePath() {
        // A Windows-style relative path with a backslash separator is also rejected (neither bare nor
        // absolute) — the isRejectedRelativePath backslash branch.
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"sub\\\\evil\",\"echo\"]}");

        ShellExecException e = assertThrows(ShellExecException.class,
                () -> spec.validate(List.of("sub\\evil")));
        assertTrue(e.getMessage().contains("relative path with a separator"),
                "a relative path with a backslash separator is rejected");
    }

    @Test
    void validateAcceptsABackslashAbsolutePathThatIsAllowlisted() {
        // A leading-backslash command is treated as absolute (the `absolute` backslash branch), so it is
        // NOT a rejected relative path; it then passes the allowlist exact-match check.
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"\\\\server\\\\tool\"]}");

        assertDoesNotThrow(() -> spec.validate(List.of("\\server\\tool", "arg")),
                "a leading-backslash (absolute-style) command is not a rejected relative path");
    }

    @Test
    void validateRejectsAVectorLongerThanTheArgumentTail() {
        // The isPrefix vector.size() > tail.size() guard: a 2-element vector cannot prefix a 1-element tail.
        ShellAllowlist.Spec spec = parse(
                "{\"allowedCommands\":[\"git\"],\"allowedArgs\":{\"git\":[[\"log\",\"--oneline\"]]}}");

        assertThrows(ShellExecException.class, () -> spec.validate(List.of("git", "log")),
                "a call whose tail is SHORTER than the only vector matches no vector and is refused");
    }

    @Test
    void validateRejectsWhenTheArgumentTailIsEmptyButAVectorIsRequired() {
        // allowedArgs present + a non-empty vector: a no-argument call has an empty tail and matches no
        // vector, so it is refused (the empty-tail vs non-empty-vector edge).
        ShellAllowlist.Spec spec = parse(
                "{\"allowedCommands\":[\"git\"],\"allowedArgs\":{\"git\":[[\"status\"]]}}");

        assertThrows(ShellExecException.class, () -> spec.validate(List.of("git")),
                "an argument-less call is refused when every vector requires an argument");
    }
}
