package ai.forvum.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test for the thin per-subfolder readers over a synthetic {@code $FORVUM_HOME} ({@code @TempDir}).
 * Covers every reader shape: single-file ({@link ConfigFileReader}), JSON-directory
 * ({@link IdentityReader}/{@link CronReader}), Markdown-directory ({@link SkillReader}), and the
 * dual persona+spec {@link AgentReader}. No Quarkus boot — readers are constructed directly.
 */
class SubfolderReadersTest {

    @TempDir
    Path home;

    private final ConfigLoader loader = new ConfigLoader(new ObjectMapper());
    private ForvumHome forvumHome;

    @BeforeEach
    void setup() {
        forvumHome = new ForvumHome(home);
    }

    private void write(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void configFileReaderReadsRoot() throws IOException {
        write("config.json", "{\"logLevel\":\"INFO\"}");

        assertEquals("INFO",
                new ConfigFileReader(loader, forvumHome).read().orElseThrow().get("logLevel").asText());
    }

    @Test
    void identityReaderListsAndReads() throws IOException {
        write("identities/default.json", "{\"displayName\":\"Elder\"}");

        IdentityReader reader = new IdentityReader(loader, forvumHome);
        assertEquals(List.of("default"), reader.ids());
        assertEquals("Elder", reader.read("default").orElseThrow().get("displayName").asText());
    }

    @Test
    void agentReaderReadsPersonaAndSpec() throws IOException {
        write("agents/main.md", "# Main agent");
        write("agents/main.json", "{\"allowedTools\":[\"*\"]}");

        AgentReader reader = new AgentReader(loader, forvumHome);
        assertEquals(List.of("main"), reader.ids());
        assertEquals("# Main agent", reader.persona("main").orElseThrow());
        assertTrue(reader.spec("main").orElseThrow().has("allowedTools"));
    }

    @Test
    void skillReaderReadsMarkdown() throws IOException {
        write("skills/summarize.md", "Summarize: {{input}}");

        SkillReader reader = new SkillReader(loader, forvumHome);
        assertEquals(List.of("summarize"), reader.ids());
        assertEquals("Summarize: {{input}}", reader.read("summarize").orElseThrow());
    }

    @Test
    void roleReaderListsAndReads() throws IOException {
        write("roles/reader.json", "{\"scopes\":[\"FS_READ\"]}");

        RoleReader reader = new RoleReader(loader, forvumHome);
        assertEquals(List.of("reader"), reader.ids());
        assertTrue(reader.read("reader").orElseThrow().get("scopes").isArray());
    }

    @Test
    void missingSubfolderYieldsEmpty() {
        CronReader reader = new CronReader(loader, forvumHome);
        assertEquals(List.of(), reader.ids());
        assertTrue(reader.read("whatever").isEmpty());
    }
}
