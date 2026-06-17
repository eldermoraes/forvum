package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ToolSpec}: name non-blank/no edge whitespace, requiredScope non-null, schema non-null (section 4.3 backfill). */
class ToolSpecTest {

    @Test
    void acceptsValid() {
        ToolSpec t = new ToolSpec("fs.read", "Read a file", PermissionScope.FS_READ, "{}");
        assertEquals("fs.read", t.name());
        assertEquals("Read a file", t.description());
        assertEquals(PermissionScope.FS_READ, t.requiredScope());
        assertEquals("{}", t.parametersJsonSchema());
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalStateException.class, () -> new ToolSpec(null, "d", PermissionScope.FS_READ, "{}"));
        assertThrows(IllegalStateException.class, () -> new ToolSpec(" ", "d", PermissionScope.FS_READ, "{}"));
        assertThrows(IllegalStateException.class, () -> new ToolSpec("fs.read ", "d", PermissionScope.FS_READ, "{}"));
        assertThrows(IllegalStateException.class, () -> new ToolSpec("fs.read", null, PermissionScope.FS_READ, "{}"));
        assertThrows(IllegalStateException.class, () -> new ToolSpec("fs.read", " ", PermissionScope.FS_READ, "{}"));
        assertThrows(IllegalStateException.class, () -> new ToolSpec("fs.read", "d", null, "{}"));
        assertThrows(IllegalStateException.class, () -> new ToolSpec("fs.read", "d", PermissionScope.FS_READ, null));
    }

    @Test
    void fourArgCtorDefaultsConfirmFalse() {
        // P2-14 #39: the 4-arg ctor is the backward-compatible form every existing call site uses; it
        // must default userConfirmRequired to false so an unchanged tool spec never silently parks a turn.
        ToolSpec t = new ToolSpec("fs.read", "Read a file", PermissionScope.FS_READ, "{}");
        assertFalse(t.userConfirmRequired());
    }

    @Test
    void fiveArgCtorCarriesConfirmFlag() {
        // The full canonical ctor lets a destructive tool (e.g. shell.exec, #27) opt in to the approval gate.
        ToolSpec confirm = new ToolSpec("shell.exec", "Run a shell command", PermissionScope.FS_WRITE, "{}", true);
        assertTrue(confirm.userConfirmRequired());
        ToolSpec plain = new ToolSpec("fs.read", "Read a file", PermissionScope.FS_READ, "{}", false);
        assertFalse(plain.userConfirmRequired());
    }
}
