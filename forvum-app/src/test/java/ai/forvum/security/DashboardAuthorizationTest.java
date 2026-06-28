package ai.forvum.security;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Authorization of the operator endpoints (#165). The {@code quarkus.http.auth.permission} policy requires
 * the {@code operator} role on {@code /q/dashboard/*}. Using {@code @TestSecurity} to inject an
 * authenticated principal, this pins the three-way contract without minting a real token:
 *
 * <ul>
 *   <li>no principal (anonymous) → {@code 401} (the {@link ai.forvum.app.OperatorAuthMechanism} challenge);
 *   <li>an authenticated NON-operator → {@code 403} (and the constant challenge/refusal cannot reveal
 *       whether a given approval/CAPR id exists);
 *   <li>an authenticated operator → {@code 200}.
 * </ul>
 *
 * <p>The {@code 401} and {@code 200} paths are also covered end-to-end with a real {@code Authorization:
 * Bearer} token by {@code ApprovalDashboardE2E} / {@code CaprDashboardE2E}. This test pins the {@code 403}
 * path the token mechanism cannot reach on its own (it only ever mints operators) — the authorization seam
 * #166's device tokens (with their own {@code approvedScopes}) reuse.
 */
@QuarkusTest
@TestProfile(DashboardAuthorizationTest.HomeProfile.class)
class DashboardAuthorizationTest {

    @Test
    void anonymousIsUnauthorized() {
        given().when().get("/q/dashboard/capr").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "intruder", roles = {})
    void authenticatedNonOperatorIsForbidden() {
        given().when().get("/q/dashboard/capr").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "op", roles = {"operator"})
    void operatorIsAuthorized() {
        given().when().get("/q/dashboard/capr").then().statusCode(200);
    }

    /** A throwaway home so the app boots (Flyway migrates there); no operator token needed — @TestSecurity injects the principal. */
    public static class HomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-dashauthz-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"fake:test-model\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
