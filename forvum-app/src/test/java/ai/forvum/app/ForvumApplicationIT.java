package ai.forvum.app;

import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;

/**
 * Native (and runnable-jar) smoke: runs the produced binary and reuses the
 * {@link ForvumApplicationTest} assertions. Executed by Failsafe only under the {@code native}
 * profile (skipITs=false). No latency assertion here — the 200 ms cold-start gate is M20.
 */
@QuarkusMainIntegrationTest
class ForvumApplicationIT extends ForvumApplicationTest {
}
