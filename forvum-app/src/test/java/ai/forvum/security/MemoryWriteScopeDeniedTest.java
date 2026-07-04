package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.IdentityResolver;
import ai.forvum.engine.agent.RoleRegistry;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.persistence.ToolInvocationEntity;
import ai.forvum.engine.tools.PermissionDeniedException;
import ai.forvum.engine.tools.ToolExecutor;
import ai.forvum.engine.tools.ToolFilter;
import ai.forvum.tools.memory.MemoryRecallTool;
import ai.forvum.tools.memory.MemorySaveTool;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Security-test layer (ULTRAPLAN section 10): the #193 {@code MEMORY_WRITE} acceptance — an identity granted
 * only {@code MEMORY_READ} is denied {@code memory.save} <em>even though the belt allows it</em>, and the
 * denial is audited; {@code memory.recall} (which needs only {@code MEMORY_READ}) still runs, proving the
 * READ/WRITE split is enforced granularly. Uses the REAL {@link MemorySaveTool#SPEC}/{@link MemoryRecallTool}
 * belt so a scope drift on the shipped tool would fail here. Drives the real engine {@link ToolExecutor} +
 * Panache recorder over real SQLite (temp {@code $FORVUM_HOME}, Flyway-migrated). Non-live; default build.
 *
 * <p>Companion to {@code RoleRestrictedToolDeniedTest} (FS scopes) and {@code PermissionScopeMismatchTest}
 * (belt denial). The cross-identity isolation half of #193 is proven by {@code EngineMemoryAccessIT}.
 */
@QuarkusTest
@TestProfile(MemoryWriteScopeDeniedTest.MemoryRbacHomeProfile.class)
class MemoryWriteScopeDeniedTest {

    // The belt grants BOTH real memory tools (glob match), so a denial can only come from the scope gate.
    private static final List<ToolSpec> BELT = ToolFilter.filter(
            List.of("memory.save", "memory.recall"),
            List.of(MemorySaveTool.SPEC, MemoryRecallTool.SPEC));

    @Inject
    ToolExecutor executor;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    IdentityResolver identities;

    @Test
    void aMemoryReadOnlyIdentityIsDeniedMemorySaveAndItIsAudited() {
        Set<PermissionScope> effective = roleRegistry.effectiveScopes(identities.rolesFor("mem-restricted"));
        assertEquals(Set.of(PermissionScope.MEMORY_READ), effective, "the mem-reader role grants only MEMORY_READ");

        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("memory.save must never run without MEMORY_WRITE");
        };

        assertThrows(PermissionDeniedException.class, () ->
                ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, effective)
                        .call(() -> executor.execute("sess-mem", new AgentId("main"), BELT,
                                "memory.save", "{}", mustNotRun)));

        // Scope the count to this call's session (M7 convention, CLAUDE.md §14): the @TestProfile DB is
        // shared across methods, so assert only on the rows this method wrote.
        long denied = ToolInvocationEntity.count("sessionId = ?1 and status = ?2 and toolName = ?3",
                "sess-mem", "denied", "memory.save");
        assertEquals(1L, denied,
                "the MEMORY_WRITE denial must be audited to tool_invocations with status='denied'");
    }

    @Test
    void theSameReadOnlyIdentityMayStillRecall() {
        // The control: MEMORY_READ IS granted, so memory.recall (same belt, the read scope) runs — proving
        // the denial above is specifically the missing MEMORY_WRITE, i.e. the granular READ/WRITE split.
        Set<PermissionScope> effective = roleRegistry.effectiveScopes(identities.rolesFor("mem-restricted"));

        String result = ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, effective)
                .call(() -> executor.execute("sess-mem-ok", new AgentId("main"), BELT,
                        "memory.recall", "{}", () -> "recalled"));

        assertEquals("recalled", result, "MEMORY_READ permits recall; only the write scope is withheld");
    }

    /** Seeds a {@code mem-reader} role (MEMORY_READ) + a {@code mem-restricted} identity declaring it, into a
     * throwaway temp home (SQLite + Flyway create the schema). */
    public static class MemoryRbacHomeProfile implements QuarkusTestProfile {

        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-mem-rbac-home");
                Path roles = Files.createDirectories(home.resolve("roles"));
                Files.writeString(roles.resolve("mem-reader.json"), "{ \"scopes\": [\"MEMORY_READ\"] }");
                Path identities = Files.createDirectories(home.resolve("identities"));
                Files.writeString(identities.resolve("mem-restricted.json"),
                        "{ \"displayName\": \"Memory Reader\", \"channelAccounts\": { \"web\": \"m1\" }, "
                      + "\"roles\": [\"mem-reader\"] }");
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
