package ai.forvum.engine.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ForvumHome;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unit test for the #191 {@link SkillToolProvider}: no Quarkus boot — the provider is constructed directly
 * over a real {@link ai.forvum.engine.config.SkillReader} rooted at a {@code @TempDir} home (the
 * {@code SubfolderReadersTest} idiom), so it exercises the true read → validate-args → expand → size-check
 * path. Covers a happy invoke, a missing skill, arg-schema validation (before expansion), the schema-less
 * pass-through, the size cap, the {@code skill.list} catalog, invoke-time freshness (edit + delete), the
 * no-front-matter skill, argument leniency, and the pure {@code expand} contract.
 */
class SkillToolProviderTest {

    @TempDir
    Path home;

    private SkillToolProvider provider() {
        SkillToolProvider provider = new SkillToolProvider();
        ObjectMapper mapper = new ObjectMapper();
        provider.mapper = mapper;
        provider.skills = new ai.forvum.engine.config.SkillReader(
                new ConfigLoader(mapper), new ForvumHome(Optional.of(home.toString())));
        return provider;
    }

    private void writeSkill(String id, String content) throws IOException {
        Path file = home.resolve("skills").resolve(id + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String invoke(SkillToolProvider provider, String name, Map<String, Object> args) {
        return provider.invoke("skill.invoke", args == null ? Map.of("name", name) : Map.of("name", name, "args", args));
    }

    @Test
    void happyInvokeReturnsTheExpandedTemplateWithPlaceholdersSubstituted() throws IOException {
        writeSkill("greeting", "Hello {{who}}, welcome to Forvum!");
        SkillToolProvider provider = provider();

        String result = invoke(provider, "greeting", Map.of("who", "Ada"));

        assertEquals("Hello Ada, welcome to Forvum!", result);
    }

    @Test
    void aMissingSkillErrorsNamingTheInstalledIds() throws IOException {
        writeSkill("greeting", "Hello {{who}}");
        SkillToolProvider provider = provider();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> invoke(provider, "nope", Map.of()));
        assertTrue(thrown.getMessage().contains("nope"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("greeting"),
                "the error names the installed skills for self-healing: " + thrown.getMessage());
    }

    @Test
    void argsViolatingTheInputSchemaErrorNamingTheFieldAndDoNotExpand() throws IOException {
        // who is declared a string; passing an integer must fail validation BEFORE expansion.
        writeSkill("greet", "---\n{\"inputSchema\":{\"type\":\"object\",\"required\":[\"who\"],"
                + "\"properties\":{\"who\":{\"type\":\"string\"}}}}\n---\nHello {{who}}");
        SkillToolProvider provider = provider();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> invoke(provider, "greet", Map.of("who", 42)));
        assertTrue(thrown.getMessage().contains("who"),
                "the error names the offending field: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("inputSchema"), thrown.getMessage());
    }

    @Test
    void aRequiredArgAbsentErrors() throws IOException {
        writeSkill("greet", "---\n{\"inputSchema\":{\"type\":\"object\",\"required\":[\"who\"],"
                + "\"properties\":{\"who\":{\"type\":\"string\"}}}}\n---\nHello {{who}}");
        SkillToolProvider provider = provider();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> invoke(provider, "greet", Map.of()));
        assertTrue(thrown.getMessage().contains("who"), thrown.getMessage());
    }

    @Test
    void aSchemaLessSkillAcceptsArgsWithoutValidationAndSubstitutes() throws IOException {
        writeSkill("free", "Hi {{who}} from {{place}}");
        SkillToolProvider provider = provider();

        String result = invoke(provider, "free", Map.of("who", "Bob", "place", "Rio"));

        assertEquals("Hi Bob from Rio", result);
    }

    @Test
    void expansionOverTheSizeCapErrorsNamingTheCap() throws IOException {
        writeSkill("big", "BODY:{{body}}");
        SkillToolProvider provider = provider();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> invoke(provider, "big", Map.of("body", "x".repeat(SkillToolProvider.MAX_EXPANDED_CHARS + 10))));
        assertTrue(thrown.getMessage().contains(String.valueOf(SkillToolProvider.MAX_EXPANDED_CHARS)),
                "the error names the cap: " + thrown.getMessage());
    }

    @Test
    void skillListReturnsABoundedCatalogWithDescriptionsAndRequiredKeys() throws IOException {
        writeSkill("greeting", "---\n{\"description\":\"Greet a person\","
                + "\"inputSchema\":{\"type\":\"object\",\"required\":[\"who\"],"
                + "\"properties\":{\"who\":{\"type\":\"string\"}}}}\n---\nHello {{who}}");
        writeSkill("plain", "just a template, no front matter");
        SkillToolProvider provider = provider();

        String catalog = provider.invoke("skill.list", Map.of());

        assertTrue(catalog.contains("greeting"), catalog);
        assertTrue(catalog.contains("Greet a person"), catalog);
        assertTrue(catalog.contains("who"), "the required arg key is listed: " + catalog);
        assertTrue(catalog.contains("plain"), catalog);
        assertTrue(catalog.contains("(no description)"), "the no-description fallback appears: " + catalog);
    }

    @Test
    void skillListOnAnEmptyHomeReportsNothingInstalled() {
        String catalog = provider().invoke("skill.list", Map.of());
        assertTrue(catalog.toLowerCase().contains("no skills"), catalog);
    }

    @Test
    void invokeReadsAtCallTimeSoAnEditIsVisibleImmediatelyAndADeleteRevokes() throws IOException {
        writeSkill("live", "v1 {{who}}");
        SkillToolProvider provider = provider();
        assertEquals("v1 Ada", invoke(provider, "live", Map.of("who", "Ada")));

        writeSkill("live", "v2 {{who}}"); // edit between invokes — no cache to evict
        assertEquals("v2 Ada", invoke(provider, "live", Map.of("who", "Ada")));

        Files.delete(home.resolve("skills").resolve("live.md")); // revocation
        assertThrows(IllegalArgumentException.class, () -> invoke(provider, "live", Map.of("who", "Ada")));
    }

    @Test
    void aNoFrontMatterSkillIsInvocableVerbatim() throws IOException {
        writeSkill("verbatim", "This whole file is the template.");
        SkillToolProvider provider = provider();

        assertEquals("This whole file is the template.", invoke(provider, "verbatim", null));
    }

    @Test
    void argsAsAJsonObjectStringAreAccepted() throws IOException {
        writeSkill("greeting", "Hello {{who}}");
        SkillToolProvider provider = provider();

        String result = provider.invoke("skill.invoke",
                Map.of("name", "greeting", "args", "{\"who\":\"Ada\"}"));

        assertEquals("Hello Ada", result);
    }

    @Test
    void argsAsANonObjectValueErrorClearly() throws IOException {
        writeSkill("greeting", "Hello {{who}}");
        SkillToolProvider provider = provider();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> provider.invoke("skill.invoke", Map.of("name", "greeting", "args", 5)));
        assertTrue(thrown.getMessage().contains("args"), thrown.getMessage());
    }

    @Test
    void unknownToolNameIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> provider().invoke("skill.frobnicate", Map.of()));
    }

    @Test
    void expandRendersStringsRawOtherValuesAsCompactJsonAndLeavesUnmatchedPlaceholders() {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("s", "text");
        args.put("n", 7);
        args.put("list", List.of("a", "b"));
        args.put("nil", null);

        String out = SkillToolProvider.expand("[{{s}}][{{n}}][{{list}}][{{nil}}][{{missing}}]", args);

        assertEquals("[text][7][[\"a\",\"b\"]][][{{missing}}]", out,
                "strings raw, non-strings compact JSON, null empty, unmatched verbatim");
    }

    @Test
    void expandOverAnEmptyArgMapIsIdentity() {
        assertEquals("no {{placeholders}} touched", SkillToolProvider.expand("no {{placeholders}} touched", Map.of()));
    }

    @Test
    void listedToolsAreTheTwoStaticSpecsCarryingSkillInvokeScope() {
        var specs = provider().tools();
        assertEquals(2, specs.size());
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("skill.invoke")));
        assertTrue(specs.stream().anyMatch(s -> s.name().equals("skill.list")));
        assertTrue(specs.stream().allMatch(s -> s.requiredScope() == ai.forvum.core.PermissionScope.SKILL_INVOKE));
        assertFalse(specs.stream().anyMatch(ai.forvum.core.ToolSpec::userConfirmRequired),
                "both skill tools are read-class, no user-confirm gate");
    }
}
