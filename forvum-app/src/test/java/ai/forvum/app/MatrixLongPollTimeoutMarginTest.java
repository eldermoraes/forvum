package ai.forvum.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Gates the Matrix long-poll timeout margin in the EFFECTIVE assembled-app config: the REST client's
 * {@code read-timeout} MUST exceed the {@code /sync} long-poll timeout, or every long poll is cut
 * short client-side before the homeserver responds (the M17 trap). The matrix module ships the
 * defaults in its {@code META-INF/microprofile-config.properties} (ordinal 100), but a future
 * {@code forvum-app/application.properties} or profile override lowering {@code read-timeout} would
 * silently truncate every poll — only an app-classpath test sees the final resolved values, so the
 * guard lives here, not in the channel module.
 */
@QuarkusTest
class MatrixLongPollTimeoutMarginTest {

    @ConfigProperty(name = "quarkus.rest-client.\"matrix-client-api\".read-timeout")
    long readTimeoutMillis;

    /** The default mirrors {@code MatrixChannel#syncTimeoutMillis} (the key has no file-level default). */
    @ConfigProperty(name = "forvum.channel.matrix.sync-timeout-millis", defaultValue = "30000")
    int syncTimeoutMillis;

    @Test
    void theRestClientReadTimeoutExceedsTheSyncLongPollTimeout() {
        assertTrue(readTimeoutMillis > syncTimeoutMillis,
                "the matrix-client-api read-timeout (" + readTimeoutMillis + " ms) must exceed the "
                        + "/sync long-poll timeout (" + syncTimeoutMillis + " ms), or the client cuts "
                        + "every long poll before the homeserver returns");
    }
}
