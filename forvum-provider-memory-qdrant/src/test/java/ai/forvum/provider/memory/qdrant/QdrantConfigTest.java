package ai.forvum.provider.memory.qdrant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.provider.memory.qdrant.QdrantConfig.Spec;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code QdrantConfig} reads {@code memory/qdrant.json}: url/apiKey/collection parsing, the enabled
 * default, the {@code isActive} gate, and the absent-file → empty/disabled spec (so the provider is inert
 * with no {@code ~/.forvum/}). Plain POJO tests — no Quarkus boot needed.
 */
class QdrantConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesUrlApiKeyAndCollection() throws Exception {
        Spec spec = QdrantConfig.parse(MAPPER.readTree(
                "{ \"enabled\": true, \"url\": \"http://localhost:6333\", \"apiKey\": \"k\", "
              + "\"collection\": \"mem\" }"));

        assertTrue(spec.enabled());
        assertEquals(Optional.of("http://localhost:6333"), spec.url());
        assertEquals("k", spec.apiKey());
        assertEquals("mem", spec.collection());
        assertTrue(spec.isActive(), "enabled with a url is active");
    }

    @Test
    void collectionDefaultsWhenAbsent() throws Exception {
        Spec spec = QdrantConfig.parse(MAPPER.readTree("{ \"url\": \"http://q:6333\" }"));
        assertEquals(QdrantConfig.DEFAULT_COLLECTION, spec.collection());
    }

    @Test
    void apiKeyDefaultsToEmptyWhenAbsent() throws Exception {
        Spec spec = QdrantConfig.parse(MAPPER.readTree("{ \"url\": \"http://q:6333\" }"));
        assertEquals("", spec.apiKey());
    }

    @Test
    void enabledDefaultsTrueWhenAbsentButFalseWhenSet() throws Exception {
        assertTrue(QdrantConfig.parse(MAPPER.readTree("{ \"url\": \"http://q:6333\" }")).enabled());
        assertFalse(QdrantConfig.parse(MAPPER.readTree(
                "{ \"enabled\": false, \"url\": \"http://q:6333\" }")).enabled());
    }

    @Test
    void disabledOrUrllessSpecIsNotActive() throws Exception {
        assertFalse(QdrantConfig.parse(MAPPER.readTree(
                "{ \"enabled\": false, \"url\": \"http://q:6333\" }")).isActive(),
                "disabled is not active");
        assertFalse(QdrantConfig.parse(MAPPER.readTree("{ \"enabled\": true }")).isActive(),
                "no url is not active");
    }

    @Test
    void blankUrlIsTreatedAsAbsent() throws Exception {
        Spec spec = QdrantConfig.parse(MAPPER.readTree("{ \"url\": \"  \" }"));
        assertTrue(spec.url().isEmpty());
        assertFalse(spec.isActive());
    }

    @Test
    void absentFileReadsAsEmptyInactiveSpec(@TempDir Path dir) {
        Spec spec = new QdrantConfig(dir.resolve("qdrant.json")).read();

        assertFalse(spec.enabled(), "an absent config file disables the provider");
        assertTrue(spec.url().isEmpty());
        assertFalse(spec.isActive(), "an unconfigured provider is inert");
    }

    @Test
    void readsAnExistingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("qdrant.json");
        Files.writeString(file, "{ \"url\": \"http://qd:6333\", \"collection\": \"c\" }");

        Spec spec = new QdrantConfig(file).read();

        assertEquals(Optional.of("http://qd:6333"), spec.url());
        assertEquals("c", spec.collection());
        assertTrue(spec.isActive());
    }
}
