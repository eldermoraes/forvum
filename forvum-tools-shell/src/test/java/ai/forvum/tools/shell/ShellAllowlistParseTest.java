package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Branch-level coverage of {@link ShellAllowlist#parse(com.fasterxml.jackson.databind.JsonNode)} — the
 * on-demand {@code tools/shell.json} tree-walk. Each test feeds a tree exercising a missing / wrong-typed /
 * partial field and asserts the documented default, so the parser's defensive branches (non-object root,
 * absent or wrong-typed {@code allowedCommands}/{@code allowedArgs}, non-textual command entries, malformed
 * vectors) are real behaviors, not vacuous assertions.
 */
class ShellAllowlistParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ShellAllowlist.Spec parse(String json) {
        try {
            return ShellAllowlist.parse(MAPPER.readTree(json));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void nullRootIsFailClosed() {
        ShellAllowlist.Spec spec = ShellAllowlist.parse(null);

        assertTrue(spec.allowedCommands().isEmpty(), "a null root yields the fail-closed spec");
    }

    @Test
    void jsonNullNodeIsFailClosed() {
        ShellAllowlist.Spec spec = ShellAllowlist.parse(NullNode.getInstance());

        assertTrue(spec.allowedCommands().isEmpty(), "a JSON null node yields the fail-closed spec");
    }

    @Test
    void nonObjectRootIsFailClosed() {
        // A top-level array (not an object) is fail-closed via the !isObject() branch.
        ShellAllowlist.Spec spec = parse("[\"echo\"]");

        assertTrue(spec.allowedCommands().isEmpty(), "a non-object root yields the fail-closed spec");
    }

    @Test
    void absentAllowedCommandsYieldsAnEmptyList() {
        ShellAllowlist.Spec spec = parse("{\"timeoutSeconds\":30}");

        assertTrue(spec.allowedCommands().isEmpty(),
                "a missing allowedCommands key yields an empty (fail-closed) command list");
    }

    @Test
    void wrongTypedAllowedCommandsIsIgnored() {
        // allowedCommands present but NOT an array -> the isArray() guard skips it -> empty list.
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":\"echo\"}");

        assertTrue(spec.allowedCommands().isEmpty(),
                "a non-array allowedCommands is ignored, leaving an empty command list");
    }

    @Test
    void nonTextualAndBlankCommandEntriesAreSkipped() {
        // Numbers, nulls, and blank strings in the array are skipped; only the non-blank textual entry
        // survives — and it is stripped.
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"  git  \",123,null,\"   \",\"ls\"]}");

        assertEquals(List.of("git", "ls"), spec.allowedCommands(),
                "only non-blank textual entries are kept (stripped); numbers/nulls/blanks are dropped");
    }

    @Test
    void absentAllowedArgsYieldsAnEmptyMap() {
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"git\"]}");

        assertTrue(spec.allowedArgs().isEmpty(), "a missing allowedArgs key yields an empty map");
    }

    @Test
    void wrongTypedAllowedArgsIsIgnored() {
        // allowedArgs present but NOT an object -> the isObject() guard skips it -> empty map.
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"git\"],\"allowedArgs\":[\"status\"]}");

        assertTrue(spec.allowedArgs().isEmpty(), "a non-object allowedArgs is ignored");
    }

    @Test
    void nonArrayArgsEntryValueProducesNoVectors() {
        // The per-command value is not an array -> the inner isArray() guard leaves an empty vector list.
        ShellAllowlist.Spec spec = parse(
                "{\"allowedCommands\":[\"git\"],\"allowedArgs\":{\"git\":\"status\"}}");

        assertEquals(Map.of("git", List.of()), spec.allowedArgs(),
                "a non-array allowedArgs value yields an entry with no vectors");
    }

    @Test
    void nonArrayVectorElementsAreSkipped() {
        // Within the per-command array, a non-array element (a bare string) is skipped; only the real
        // vector survives.
        ShellAllowlist.Spec spec = parse(
                "{\"allowedCommands\":[\"git\"],\"allowedArgs\":{\"git\":[\"notavector\",[\"status\"]]}}");

        assertEquals(Map.of("git", List.of(List.of("status"))), spec.allowedArgs(),
                "non-array vector elements are skipped; only the array vector is kept");
    }

    @Test
    void blankWorkingDirIsTreatedAsAbsent() {
        // A present-but-blank workingDir collapses to Optional.empty() (the isBlank() branch).
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"x\"],\"workingDir\":\"   \"}");

        assertTrue(spec.workingDir().isEmpty(), "a blank workingDir is treated as absent");
    }

    @Test
    void nonNumericTimeoutFallsBackToTheDefault() {
        // timeoutSeconds present but a string (not a number) -> the !isNumber() branch -> default.
        ShellAllowlist.Spec spec = parse("{\"allowedCommands\":[\"x\"],\"timeoutSeconds\":\"oops\"}");

        assertEquals(ShellAllowlist.DEFAULT_TIMEOUT_SECONDS, spec.timeoutSeconds(),
                "a non-numeric timeoutSeconds falls back to the default");
    }
}
