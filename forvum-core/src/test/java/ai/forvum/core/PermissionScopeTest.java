package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link PermissionScope} active constants and {@code fromName} failure modes (ULTRAPLAN section 4.3.4). */
class PermissionScopeTest {

    @Test
    void onlyTheActiveScopesAreDeclared() {
        // FS_READ, FS_WRITE (M2) + MCP_REMOTE (P2-13) + SHELL_EXEC, WEB_BROWSE, WEB_FETCH, WEB_SEARCH
        // (PR-6 preamble for #27/#26/forvum-tools-web) + MEMORY_READ, MEMORY_WRITE (#193 memory tool)
        // + MEDIA_SYNTHESIZE (#186 tts.speak tool) + MEDIA_ANALYZE (#185 image.analyze/pdf.analyze tools)
        // + SKILL_INVOKE (#191 skill.invoke/skill.list tools).
        assertEquals(12, PermissionScope.values().length);
        assertEquals(PermissionScope.FS_READ, PermissionScope.valueOf("FS_READ"));
        assertEquals(PermissionScope.FS_WRITE, PermissionScope.valueOf("FS_WRITE"));
        assertEquals(PermissionScope.MCP_REMOTE, PermissionScope.valueOf("MCP_REMOTE"));
        assertEquals(PermissionScope.SHELL_EXEC, PermissionScope.valueOf("SHELL_EXEC"));
        assertEquals(PermissionScope.WEB_BROWSE, PermissionScope.valueOf("WEB_BROWSE"));
        assertEquals(PermissionScope.WEB_FETCH, PermissionScope.valueOf("WEB_FETCH"));
        assertEquals(PermissionScope.WEB_SEARCH, PermissionScope.valueOf("WEB_SEARCH"));
        assertEquals(PermissionScope.MEMORY_READ, PermissionScope.valueOf("MEMORY_READ"));
        assertEquals(PermissionScope.MEMORY_WRITE, PermissionScope.valueOf("MEMORY_WRITE"));
        assertEquals(PermissionScope.MEDIA_SYNTHESIZE, PermissionScope.valueOf("MEDIA_SYNTHESIZE"));
        assertEquals(PermissionScope.MEDIA_ANALYZE, PermissionScope.valueOf("MEDIA_ANALYZE"));
        assertEquals(PermissionScope.SKILL_INVOKE, PermissionScope.valueOf("SKILL_INVOKE"));
    }

    @Test
    void fromNameRoundTripsActiveScopes() {
        assertEquals(PermissionScope.FS_READ, PermissionScope.fromName("FS_READ"));
        assertEquals(PermissionScope.FS_WRITE, PermissionScope.fromName("FS_WRITE"));
        assertEquals(PermissionScope.MCP_REMOTE, PermissionScope.fromName("MCP_REMOTE"),
                "MCP_REMOTE (P2-13) round-trips — remote MCP tool specs carry it");
        assertEquals(PermissionScope.SHELL_EXEC, PermissionScope.fromName("SHELL_EXEC"));
        assertEquals(PermissionScope.WEB_BROWSE, PermissionScope.fromName("WEB_BROWSE"));
        assertEquals(PermissionScope.WEB_FETCH, PermissionScope.fromName("WEB_FETCH"));
        assertEquals(PermissionScope.WEB_SEARCH, PermissionScope.fromName("WEB_SEARCH"));
        assertEquals(PermissionScope.MEMORY_READ, PermissionScope.fromName("MEMORY_READ"),
                "MEMORY_READ (#193) round-trips — memory.recall carries it");
        assertEquals(PermissionScope.MEMORY_WRITE, PermissionScope.fromName("MEMORY_WRITE"),
                "MEMORY_WRITE (#193) round-trips — memory.save carries it");
        assertEquals(PermissionScope.MEDIA_SYNTHESIZE, PermissionScope.fromName("MEDIA_SYNTHESIZE"),
                "MEDIA_SYNTHESIZE (#186) round-trips — tts.speak carries it");
        assertEquals(PermissionScope.MEDIA_ANALYZE, PermissionScope.fromName("MEDIA_ANALYZE"),
                "MEDIA_ANALYZE (#185) round-trips — image.analyze/pdf.analyze carry it");
        assertEquals(PermissionScope.SKILL_INVOKE, PermissionScope.fromName("SKILL_INVOKE"),
                "SKILL_INVOKE (#191) round-trips — skill.invoke/skill.list carry it");
    }

    @Test
    void fromNameRejectsNullLowercaseAndUnknown() {
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName(null));
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName("fs_read"));
        // A still-unreserved name (the old WEB_BROWSE/SHELL_EXEC fixtures are now real constants, PR-6).
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName("NOT_A_REAL_SCOPE"));
    }
}
