package ai.forvum.engine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Random;

/**
 * Unit tests for {@link ToolFilter} glob matching — the Select pillar applied to capability (ULTRAPLAN
 * section 5.3). A persona's {@code allowedTools} globs intersect the global tool set into the agent's
 * belt. Glob semantics: {@code *} matches any run of characters (including dots), {@code ?} matches
 * exactly one, every other character is literal (so {@code .} is a literal dot, not a wildcard).
 */
class ToolFilterTest {

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, "desc of " + name, PermissionScope.FS_READ, "{}");
    }

    @ParameterizedTest
    @CsvSource({
        "fs.read,   fs.read,  true",   // exact literal match
        "fs.read,   fs.write, false",  // literal mismatch
        "fs.*,      fs.read,  true",   // star matches the suffix
        "fs.*,      fs.write, true",
        "fs.*,      web.get,  false",  // star is anchored to the literal prefix
        "*,         anything, true",   // bare star matches everything
        "fs.read,   fsxread,  false",  // the dot is literal, not a wildcard
        "fs.?ead,   fs.read,  true",   // ? matches exactly one char
        "fs.?ead,   fs.rread, false",  // ? is exactly one, not many
        "*.read,    fs.read,  true"    // leading star
    })
    void matchesGlobSemantics(String glob, String name, boolean expected) {
        assertEquals(expected, ToolFilter.matches(glob, name),
                "glob '" + glob + "' vs name '" + name + "'");
    }

    @Test
    void filterReturnsTheUnionOfMatchingTools() {
        List<ToolSpec> all = List.of(tool("fs.read"), tool("fs.write"), tool("web.get"));

        List<ToolSpec> belt = ToolFilter.filter(List.of("fs.*"), all);

        assertEquals(List.of("fs.read", "fs.write"), belt.stream().map(ToolSpec::name).toList());
    }

    @Test
    void multipleGlobsUnionTheirMatches() {
        List<ToolSpec> all = List.of(tool("fs.read"), tool("fs.write"), tool("web.get"));

        List<ToolSpec> belt = ToolFilter.filter(List.of("fs.read", "web.*"), all);

        assertEquals(List.of("fs.read", "web.get"), belt.stream().map(ToolSpec::name).toList());
    }

    @Test
    void emptyGlobListGrantsNothing() {
        List<ToolSpec> all = List.of(tool("fs.read"), tool("fs.write"));

        assertTrue(ToolFilter.filter(List.of(), all).isEmpty());
    }

    @Test
    void starMatchesEveryToolAndEmptyGlobsMatchNone_property() {
        // Seeded random keeps a failure reproducible (project testing discipline — no third-party lib).
        Random rng = new Random(1234567L);
        for (int iter = 0; iter < 200; iter++) {
            int n = rng.nextInt(6);
            List<ToolSpec> all = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                all.add(tool("ns" + rng.nextInt(4) + ".op" + rng.nextInt(9)));
            }
            assertEquals(all.size(), ToolFilter.filter(List.of("*"), all).size(),
                    "'*' must select every tool");
            assertTrue(ToolFilter.filter(List.of(), all).isEmpty(),
                    "no globs must select no tool");
        }
    }

    @Test
    void aNonMatchingGlobExcludesTheTool() {
        assertFalse(ToolFilter.matches("fs.*", "web.get"));
    }
}
