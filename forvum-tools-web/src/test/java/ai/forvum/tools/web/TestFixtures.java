package ai.forvum.tools.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads a saved HTML fixture from {@code src/test/resources/fixtures/} for the hermetic parser/backend tests. */
final class TestFixtures {

    private TestFixtures() {
    }

    static String load(String name) {
        try (InputStream in = TestFixtures.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture fixtures/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
