package ai.forvum.engine.doctor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ForvumHome;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test for the {@code forvum doctor} validator over a synthetic {@code $FORVUM_HOME}
 * ({@code @TempDir}). No Quarkus boot — {@link ConfigDoctor} is constructed directly with a plain
 * {@link ConfigLoader} (mirrors {@code SubfolderReadersTest}) and an explicit known-provider set (the
 * assembled app supplies the real set from {@code Instance<ModelProvider>}). It reuses the M4 readers and
 * the {@code AgentSpecReader}/{@code CronSpecReader} typed binders as validation oracles — so doctor stays
 * in lockstep with how the engine actually parses config (the maintainer-signed-off "reuse readers, not a
 * JSON-Schema library" strategy for v0.5).
 */
class ConfigDoctorTest {

    @TempDir
    Path home;

    /** Known providers in the synthetic deployment under test (the app supplies the real set at runtime). */
    private static final Set<String> KNOWN = Set.of("ollama");

    private ConfigDoctor doctor() {
        ForvumHome forvumHome = new ForvumHome(Optional.of(home.toString()));
        ConfigLoader loader = new ConfigLoader(new ObjectMapper());
        return new ConfigDoctor(forvumHome, loader, KNOWN);
    }

    private void write(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** A minimal, fully valid agent: a non-blank persona + a spec pinned to a known provider. */
    private void writeValidMainAgent() throws IOException {
        write("agents/main.md", "You are the main agent.");
        write("agents/main.json", "{\"primaryModel\":\"ollama:qwen3:1.7b\",\"allowedTools\":[]}");
    }

    private static boolean hasError(DoctorReport report, String locationSubstring) {
        return report.findings().stream()
                .anyMatch(f -> f.severity() == Severity.ERROR && f.location().contains(locationSubstring));
    }

    private static boolean hasWarning(DoctorReport report, String locationSubstring) {
        return report.findings().stream()
                .anyMatch(f -> f.severity() == Severity.WARNING && f.location().contains(locationSubstring));
    }

    @Test
    void aValidLayoutIsHealthyWithNoFindings() throws IOException {
        writeValidMainAgent();
        write("identities/default.json", "{\"channelAccounts\":{}}");

        DoctorReport report = doctor().check();

        assertTrue(report.healthy(), () -> "valid layout must be healthy; findings: " + report.findings());
        assertTrue(report.findings().isEmpty(), () -> "valid layout must have no findings: " + report.findings());
    }

    @Test
    void anAbsentHomeIsAnError() {
        // home @TempDir exists but is empty; point at a non-existent child to simulate an uninitialised home.
        ForvumHome missing = new ForvumHome(Optional.of(home.resolve("does-not-exist").toString()));
        ConfigDoctor d = new ConfigDoctor(missing, new ConfigLoader(new ObjectMapper()), KNOWN);

        DoctorReport report = d.check();

        assertFalse(report.healthy(), "an absent home must not be healthy");
        assertTrue(hasError(report, "does-not-exist"),
                () -> "absent home must be reported with its path; findings: " + report.findings());
    }

    @Test
    void aMalformedAgentSpecIsAnError() throws IOException {
        write("agents/broken.md", "persona");
        write("agents/broken.json", "{ this is not json ");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "agents/broken.json"),
                () -> "malformed agent JSON must be an error naming the file; findings: " + report.findings());
    }

    @Test
    void anAgentMissingPrimaryModelIsAnError() throws IOException {
        write("agents/nomodel.md", "persona");
        write("agents/nomodel.json", "{\"allowedTools\":[]}");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "agents/nomodel"),
                () -> "an agent without primaryModel must be an error; findings: " + report.findings());
    }

    @Test
    void anAgentWithAnUnknownProviderIsAnError() throws IOException {
        write("agents/cloud.md", "persona");
        write("agents/cloud.json", "{\"primaryModel\":\"mystery:gpt-9\",\"allowedTools\":[]}");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                        f.severity() == Severity.ERROR
                        && f.location().contains("agents/cloud.json")
                        && f.problem().contains("mystery")),
                () -> "an unknown provider must be an error naming the provider; findings: " + report.findings());
    }

    @Test
    void anAgentMissingItsPersonaMarkdownIsAnError() throws IOException {
        // No agents/lonely.md → AgentSpecReader/Persona reject a blank system prompt (the Persona invariant).
        write("agents/lonely.json", "{\"primaryModel\":\"ollama:qwen3:1.7b\",\"allowedTools\":[]}");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "agents/lonely"),
                () -> "an agent spec with no persona .md must be an error; findings: " + report.findings());
    }

    @Test
    void aMalformedCronIsAnError() throws IOException {
        writeValidMainAgent();
        write("crons/bad.json", "{ not json ");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "crons/bad.json"),
                () -> "malformed cron JSON must be an error naming the file; findings: " + report.findings());
    }

    @Test
    void aCronReferencingAnUnknownAgentIsAnError() throws IOException {
        writeValidMainAgent();
        write("crons/daily.json",
                "{\"cron\":\"0 0 * * * ?\",\"agentId\":\"ghost\",\"primary\":\"ollama:qwen3:1.7b\",\"prompt\":\"hi\"}");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                        f.severity() == Severity.ERROR
                        && f.location().contains("crons/daily.json")
                        && f.problem().contains("ghost")),
                () -> "a cron pointing at an unknown agent must be an error; findings: " + report.findings());
    }

    @Test
    void aCronWithAnUnknownProviderIsAnError() throws IOException {
        writeValidMainAgent();
        write("crons/daily.json",
                "{\"cron\":\"0 0 * * * ?\",\"agentId\":\"main\",\"primary\":\"mystery:m\",\"prompt\":\"hi\"}");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "crons/daily.json"),
                () -> "a cron with an unknown provider must be an error; findings: " + report.findings());
    }

    @Test
    void aCronDeliveringToAnUnknownChannelIsAnError() throws IOException {
        // No channels/slack.json exists, so 'explicit-to slack' names a channel doctor does not know.
        writeValidMainAgent();
        write("crons/daily.json",
                "{\"cron\":\"0 0 * * * ?\",\"agentId\":\"main\",\"primary\":\"ollama:qwen3:1.7b\",\"prompt\":\"hi\","
              + "\"delivery\":{\"mode\":\"explicit-to\",\"target\":\"slack\"}}");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(report.findings().stream().anyMatch(f ->
                        f.severity() == Severity.ERROR
                        && f.location().contains("crons/daily.json")
                        && f.problem().contains("slack")),
                () -> "a cron delivering to an unknown channel must be an error naming the channel; findings: "
                        + report.findings());
    }

    @Test
    void aCronWithAnAmbiguousDeliveryTargetIsAnError() throws IOException {
        // mode 'last' WITH a target is ambiguous — the Delivery canonical constructor rejects it, and
        // doctor surfaces the parse failure as an error on the cron file.
        writeValidMainAgent();
        write("crons/daily.json",
                "{\"cron\":\"0 0 * * * ?\",\"agentId\":\"main\",\"primary\":\"ollama:qwen3:1.7b\",\"prompt\":\"hi\","
              + "\"delivery\":{\"mode\":\"last\",\"target\":\"web\"}}");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "crons/daily.json"),
                () -> "an ambiguous delivery (mode last with a target) must be an error; findings: "
                        + report.findings());
    }

    @Test
    void aMalformedIdentityIsAnError() throws IOException {
        writeValidMainAgent();
        write("identities/default.json", "{ broken ");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "identities/default.json"),
                () -> "malformed identity JSON must be an error; findings: " + report.findings());
    }

    @Test
    void aMalformedChannelIsAnError() throws IOException {
        writeValidMainAgent();
        write("channels/telegram.json", "{ broken ");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "channels/telegram.json"),
                () -> "malformed channel JSON must be an error; findings: " + report.findings());
    }

    @Test
    void aMalformedMcpServerIsAnError() throws IOException {
        writeValidMainAgent();
        write("mcp-servers/local.json", "{ broken ");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "mcp-servers/local.json"),
                () -> "malformed mcp-server JSON must be an error; findings: " + report.findings());
    }

    @Test
    void aDeviceWithAPendingScopeUpgradeIsAWarning() throws IOException {
        writeValidMainAgent();
        write("devices/phone.json",
                "{\"identityId\":\"alice\",\"requestedScopes\":[\"FS_READ\",\"FS_WRITE\"],"
              + "\"approvedScopes\":[\"FS_READ\"]}");

        DoctorReport report = doctor().check();

        assertTrue(report.healthy(), () -> "pending scope upgrade is advisory, not fatal; findings: "
                + report.findings());
        assertTrue(hasWarning(report, "devices/phone.json"),
                () -> "a device with requested-but-unapproved scopes must warn; findings: " + report.findings());
    }

    @Test
    void aFullyApprovedDeviceProducesNoFinding() throws IOException {
        writeValidMainAgent();
        write("devices/phone.json",
                "{\"identityId\":\"alice\",\"requestedScopes\":[\"FS_READ\"],\"approvedScopes\":[\"FS_READ\"]}");

        DoctorReport report = doctor().check();

        assertTrue(report.healthy());
        assertFalse(hasWarning(report, "devices/phone.json"),
                () -> "a settled device must produce no drift warning; findings: " + report.findings());
    }

    @Test
    void aMalformedDeviceScopeIsAnError() throws IOException {
        writeValidMainAgent();
        write("devices/phone.json",
                "{\"identityId\":\"alice\",\"requestedScopes\":[\"FS_TELEPORT\"]}");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "devices/phone.json"),
                () -> "an unknown device scope must be an error; findings: " + report.findings());
    }

    @Test
    void aMalformedRootConfigIsAnError() throws IOException {
        writeValidMainAgent();
        write("config.json", "{ broken ");

        DoctorReport report = doctor().check();

        assertFalse(report.healthy());
        assertTrue(hasError(report, "config.json"),
                () -> "malformed config.json must be an error; findings: " + report.findings());
    }

    @Test
    void anEmptyHomeWithNoAgentsIsAWarningNotAnError() throws IOException {
        // The home directory exists (so it's initialised) but declares no agents.
        Files.createDirectories(home.resolve("identities"));

        DoctorReport report = doctor().check();

        assertTrue(report.healthy(), () -> "no agents is advisory, not fatal; findings: " + report.findings());
        assertTrue(hasWarning(report, "agents"),
                () -> "a home with no agents must warn; findings: " + report.findings());
    }

    @Test
    void anOrphanPersonaMarkdownIsAWarning() throws IOException {
        writeValidMainAgent();
        write("agents/notes.md", "loose persona with no spec");

        DoctorReport report = doctor().check();

        assertTrue(report.healthy(), () -> "an orphan .md is advisory, not fatal; findings: " + report.findings());
        assertTrue(hasWarning(report, "agents/notes.md"),
                () -> "a persona .md with no matching .json must warn; findings: " + report.findings());
    }

    @Test
    void warningsAloneDoNotMakeTheReportUnhealthy() throws IOException {
        // Only an advisory condition (no agents) — exit must stay clean.
        Files.createDirectories(home.resolve("skills"));

        DoctorReport report = doctor().check();

        assertTrue(report.healthy(), () -> "a report with only warnings must be healthy; findings: " + report.findings());
        assertTrue(report.errors().isEmpty(), () -> "no errors expected; findings: " + report.findings());
        assertFalse(report.warnings().isEmpty(), "the no-agents warning is expected");
    }

    @Test
    void everyFindingNamesADistinctActionableLocation() throws IOException {
        write("agents/broken.json", "{ not json ");

        List<Finding> findings = doctor().check().findings();

        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().allMatch(f -> !f.location().isBlank() && !f.problem().isBlank()),
                () -> "every finding must carry a non-blank location + problem: " + findings);
    }
}
