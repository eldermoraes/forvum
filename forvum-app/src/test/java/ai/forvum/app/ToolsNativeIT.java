package ai.forvum.app;

import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;

/**
 * Native parity for {@code forvum tools} (#184): re-runs {@link ToolsCommandTest}'s scaffolded-home
 * assertions against the produced native binary, OUT-OF-PROCESS. The command is offline and deterministic
 * (it reads config files + enumerates the in-image {@code Instance<ToolProvider>}, and lists the MCP server
 * from the FILE without connecting — P2-13), so unlike the live {@code OllamaNativeTurnIT} this carries NO
 * {@code @Tag("live")} and runs in the default native leg: a free, real native exercise of the picocli
 * one-shot dispatch, {@code ToolProvider} discovery, and the on-demand config readers in the image.
 *
 * <p>[Risk#5]: the {@code DefaultBeltTurnTest}/{@code AnonymousBeltInvisibleTest} scripted providers are
 * src/test-only (not in the image), so this native IT extends ONLY the fixture-file-driven
 * {@link ToolsCommandTest}, never those turn tests. The {@code @TestProfile} is re-declared so the native
 * subprocess gets the seeded home without relying on annotation inheritance.
 */
@QuarkusMainIntegrationTest
@TestProfile(ToolsCommandTest.HomeProfile.class)
class ToolsNativeIT extends ToolsCommandTest {
}
