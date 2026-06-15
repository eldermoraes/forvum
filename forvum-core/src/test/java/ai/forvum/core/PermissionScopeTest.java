package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link PermissionScope} active constants and {@code fromName} failure modes (ULTRAPLAN section 4.3.4). */
class PermissionScopeTest {

    @Test
    void onlyTheActiveScopesAreDeclared() {
        assertEquals(3, PermissionScope.values().length);
        assertEquals(PermissionScope.FS_READ, PermissionScope.valueOf("FS_READ"));
        assertEquals(PermissionScope.FS_WRITE, PermissionScope.valueOf("FS_WRITE"));
        assertEquals(PermissionScope.MCP_REMOTE, PermissionScope.valueOf("MCP_REMOTE"));
    }

    @Test
    void fromNameRoundTripsActiveScopes() {
        assertEquals(PermissionScope.FS_READ, PermissionScope.fromName("FS_READ"));
        assertEquals(PermissionScope.FS_WRITE, PermissionScope.fromName("FS_WRITE"));
        assertEquals(PermissionScope.MCP_REMOTE, PermissionScope.fromName("MCP_REMOTE"),
                "MCP_REMOTE (P2-13) round-trips — remote MCP tool specs carry it");
    }

    @Test
    void fromNameRejectsNullLowercaseAndReserved() {
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName(null));
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName("fs_read"));
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName("WEB_BROWSE"));
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName("SHELL_EXEC"));
    }
}
