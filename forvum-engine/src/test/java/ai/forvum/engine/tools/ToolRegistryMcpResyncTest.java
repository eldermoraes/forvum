package ai.forvum.engine.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.runtime.CommandMode;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ToolProvider;

import io.quarkus.runtime.StartupEvent;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@link ToolRegistry#onConfigChange} (P2-13): an {@code mcp-servers/*.json} edit rebuilds the registry so a
 * removed MCP server's {@code mcp.<server>.*} tools are WITHDRAWN (and a re-added server's reappear), while a
 * change under any other config subfolder is ignored. Deterministic — drives the {@code @Observes} method
 * directly with a controllable provider set (the M19 reload-test discipline: fire the event, do not race the
 * WatchService). The MCP provider is modeled by a provider whose {@code tools()} re-reads a mutable set, the
 * way the real bridge re-reads {@code mcp-servers/} on each call.
 */
class ToolRegistryMcpResyncTest {

    /** A provider whose tool set can be swapped between calls — models the bridge re-reading its config. */
    private static final class MutableProvider extends AbstractToolProvider {
        private final String extensionId;
        private volatile List<ToolSpec> current;

        MutableProvider(String extensionId, List<ToolSpec> initial) {
            this.extensionId = extensionId;
            this.current = initial;
        }

        @Override
        public String extensionId() {
            return extensionId;
        }

        @Override
        public List<ToolSpec> tools() {
            return current;
        }

        @Override
        public String invoke(String toolName, Map<String, Object> arguments) {
            return extensionId + ":" + toolName;
        }
    }

    private static ToolSpec tool(String name, PermissionScope scope) {
        return new ToolSpec(name, "desc of " + name, scope, "{}");
    }

    private static ConfigurationChangedEvent change(String relativePath) {
        return new ConfigurationChangedEvent(Path.of(relativePath), ChangeType.MODIFIED);
    }

    /** Build a registry (NON-one-shot boot) whose injected provider {@code Instance} is the given set. */
    private static ToolRegistry registryOver(ToolProvider... providers) {
        ToolRegistry registry = new ToolRegistry();
        registry.providers = new ListInstance(List.of(providers));
        registry.commandMode = new CommandMode(new String[] {}); // a normal (server) run, not a one-shot
        registry.onStart(new StartupEvent()); // initial index, as at boot
        return registry;
    }

    @Test
    void oneShotBootSkipsMaterializationSoMcpServersAreNotConnected() {
        // A one-shot command (e.g. `forvum --help`) runs no turn; materializing here would force the MCP
        // bridge's blocking boot-time connect on the cold-start path (P2-13 fix). The gate must skip it.
        ToolProvider fs = fixed("filesystem", tool("fs.read", PermissionScope.FS_READ));
        MutableProvider mcp = new MutableProvider("mcp",
                List.of(tool("mcp.weather.forecast", PermissionScope.MCP_REMOTE)));
        ToolRegistry registry = new ToolRegistry();
        registry.providers = new ListInstance(List.of(fs, mcp));
        registry.commandMode = new CommandMode(new String[] {"--help"}); // one-shot

        registry.onStart(new StartupEvent());

        assertNull(registry.lookup("fs.read"), "a one-shot boot materializes no tools");
        assertNull(registry.lookup("mcp.weather.forecast"), "a one-shot boot connects to no MCP server");
        // Going the other way (a normal run via registryOver) DOES materialize — see the tests above.
    }

    @Test
    void mcpServerRemovalWithdrawsItsToolsOnResync() {
        ToolProvider fs = fixed("filesystem", tool("fs.read", PermissionScope.FS_READ));
        MutableProvider mcp = new MutableProvider("mcp",
                List.of(tool("mcp.weather.forecast", PermissionScope.MCP_REMOTE)));
        ToolRegistry registry = registryOver(fs, mcp);

        assertNotNull(registry.lookup("mcp.weather.forecast"), "the MCP tool is present at boot");

        mcp.current = List.of();                              // operator removes the weather server
        registry.onConfigChange(change("mcp-servers/weather.json"));

        assertNull(registry.lookup("mcp.weather.forecast"), "the removed server's tool is withdrawn");
        assertNotNull(registry.lookup("fs.read"), "an unrelated provider's tool survives the rebuild");
    }

    @Test
    void mcpServerAdditionSurfacesItsToolsOnResync() {
        MutableProvider mcp = new MutableProvider("mcp", List.of());
        ToolRegistry registry = registryOver(mcp);

        assertNull(registry.lookup("mcp.calendar.list"), "no MCP tools before the server is added");

        ToolSpec added = tool("mcp.calendar.list", PermissionScope.MCP_REMOTE);
        mcp.current = List.of(added);                          // operator adds the calendar server
        registry.onConfigChange(change("mcp-servers/calendar.json"));

        assertSame(added, registry.lookup("mcp.calendar.list"), "the added server's tool surfaces");
    }

    @Test
    void aChangeOutsideMcpServersDoesNotRebuild() {
        MutableProvider mcp = new MutableProvider("mcp",
                List.of(tool("mcp.weather.forecast", PermissionScope.MCP_REMOTE)));
        ToolRegistry registry = registryOver(mcp);

        mcp.current = List.of();                               // tools would change IF a rebuild happened
        registry.onConfigChange(change("agents/main.json"));   // unrelated subfolder

        assertNotNull(registry.lookup("mcp.weather.forecast"),
                "an agents/ edit must not re-materialize the tool registry");
    }

    private static ToolProvider fixed(String extensionId, ToolSpec... specs) {
        return new MutableProvider(extensionId, List.of(specs));
    }

    /**
     * Minimal {@link Instance} over a fixed list — {@link ToolRegistry} only iterates it. The selection /
     * lifecycle methods are unused by the registry and throw if exercised.
     */
    private record ListInstance(List<ToolProvider> items) implements Instance<ToolProvider> {

        @Override
        public Iterator<ToolProvider> iterator() {
            return items.iterator();
        }

        @Override
        public ToolProvider get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance<ToolProvider> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends ToolProvider> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends ToolProvider> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return items.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return items.size() > 1;
        }

        @Override
        public void destroy(ToolProvider instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Handle<ToolProvider> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<ToolProvider>> handles() {
            throw new UnsupportedOperationException();
        }
    }
}
