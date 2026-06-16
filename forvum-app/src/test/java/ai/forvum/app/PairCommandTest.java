package ai.forvum.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code forvum pair approve|reject} and {@code forvum devices} (P2-PAIR-SCOPE #44) end-to-end through the
 * CLI, against an isolated {@code forvum.home} seeded with several devices (distinct ids per scenario so
 * the launches do not interfere). Runs under Surefire (the forvum-app {@code @QuarkusMainTest} family,
 * CLAUDE.md §4). The block + redaction wiring is the engine's; here we exercise the operator commands.
 */
@QuarkusMainTest
@TestProfile(PairCommandTest.HomeProfile.class)
class PairCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void approveWithNoScopesGrantsEveryRequestedScope(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("pair", "approve", "phonefull");

        Assertions.assertEquals(0, result.exitCode(), () -> "stderr: " + result.getErrorOutput());
        List<String> approved = approvedScopes("phonefull");
        Assertions.assertTrue(approved.contains("FS_READ") && approved.contains("FS_WRITE"),
                () -> "every requested scope must be approved; got: " + approved);
        Assertions.assertFalse(revoked("phonefull"), "approving clears revocation");
    }

    @Test
    void approveASubsetLeavesTheRestPending(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("pair", "approve", "phonesubset", "--scopes", "FS_READ");

        Assertions.assertEquals(0, result.exitCode(), () -> "stderr: " + result.getErrorOutput());
        List<String> approved = approvedScopes("phonesubset");
        Assertions.assertEquals(List.of("FS_READ"), approved, "only the named scope is approved");
        Assertions.assertTrue(result.getOutput().contains("pending"),
                () -> "the unapproved requested scope must be reported as pending; got: " + result.getOutput());
    }

    @Test
    void approvingAnUnknownDeviceFails(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("pair", "approve", "ghost");
        Assertions.assertEquals(1, result.exitCode(), "an unpaired device must fail");
    }

    @Test
    void approvingAScopeTheDeviceDidNotRequestFails(QuarkusMainLauncher launcher) {
        // phonenotreq requested only FS_READ; granting FS_WRITE is refused (an operator grants what was asked).
        LaunchResult result = launcher.launch("pair", "approve", "phonenotreq", "--scopes", "FS_WRITE");
        Assertions.assertEquals(1, result.exitCode(), "approving a non-requested scope must fail");
    }

    @Test
    void rejectRevokesTheDeviceAndRecordsTheReason(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("pair", "reject", "phonereject", "--reason", "too broad");

        Assertions.assertEquals(0, result.exitCode(), () -> "stderr: " + result.getErrorOutput());
        Assertions.assertTrue(revoked("phonereject"), "reject must revoke the device");
        Assertions.assertEquals("too broad", decisionReason("phonereject"));
    }

    @Test
    void approveWithoutReasonClearsAStaleDecisionReason(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("pair", "approve", "phonestale");

        Assertions.assertEquals(0, result.exitCode(), () -> "stderr: " + result.getErrorOutput());
        Assertions.assertEquals(null, decisionReason("phonestale"),
                "approve with no --reason must clear the stale prior reason");
    }

    @Test
    void aNonObjectDeviceFileFailsCleanlyNotCrash(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("pair", "approve", "phonebad");

        Assertions.assertEquals(1, result.exitCode(), "a non-object device file must fail cleanly");
        Assertions.assertTrue(result.getErrorOutput().contains("not a JSON object"),
                () -> "must surface a contextual error, not a stack trace; stderr: " + result.getErrorOutput());
    }

    @Test
    void devicesListsRequestedVsApprovedAndFlagsDrift(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("devices");

        Assertions.assertEquals(0, result.exitCode(), () -> "stderr: " + result.getErrorOutput());
        String out = result.getOutput();
        // Bind the DRIFT flag to the device-under-test's own line — a DRIFT elsewhere must not pass this.
        String driftLine = out.lines().filter(l -> l.contains("phonedrift")).findFirst().orElse("");
        Assertions.assertFalse(driftLine.isEmpty(), () -> "must list phonedrift; got: " + out);
        Assertions.assertTrue(driftLine.contains("DRIFT"),
                () -> "the phonedrift line must be flagged DRIFT; got: " + driftLine);
    }

    private static JsonNode device(String id) {
        try {
            return MAPPER.readTree(Files.readString(
                    HomeProfile.FORVUM_HOME.resolve("devices").resolve(id + ".json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> approvedScopes(String id) {
        List<String> scopes = new ArrayList<>();
        device(id).withArray("approvedScopes").forEach(n -> scopes.add(n.asText()));
        return scopes;
    }

    private static boolean revoked(String id) {
        return device(id).path("revoked").asBoolean(false);
    }

    private static String decisionReason(String id) {
        return device(id).path("decisionReason").asText(null);
    }

    public static class HomeProfile implements QuarkusTestProfile {

        static final Path FORVUM_HOME = seed();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", FORVUM_HOME.toString());
        }

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-pair-home");
                Path devices = Files.createDirectories(home.resolve("devices"));
                write(devices, "phonefull", "[\"FS_READ\",\"FS_WRITE\"]", null);
                write(devices, "phonesubset", "[\"FS_READ\",\"FS_WRITE\"]", null);
                write(devices, "phonereject", "[\"FS_READ\"]", null);
                write(devices, "phonenotreq", "[\"FS_READ\"]", null);
                write(devices, "phonedrift", "[\"FS_READ\",\"FS_WRITE\"]", "[\"FS_READ\"]");
                // carries a stale decisionReason: approving without --reason must clear it.
                Files.writeString(devices.resolve("phonestale.json"),
                        "{\"identityId\":\"alice\",\"requestedScopes\":[\"FS_READ\"],"
                      + "\"decisionReason\":\"old reason\"}");
                // a non-object device file (a JSON array): commands must fail cleanly, never crash.
                Files.writeString(devices.resolve("phonebad.json"), "[\"not\",\"an\",\"object\"]");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static void write(Path devices, String id, String requested, String approved)
                throws IOException {
            StringBuilder json = new StringBuilder("{\"identityId\":\"alice\",\"requestedScopes\":")
                    .append(requested);
            if (approved != null) {
                json.append(",\"approvedScopes\":").append(approved);
            }
            json.append("}");
            Files.writeString(devices.resolve(id + ".json"), json.toString());
        }
    }
}
