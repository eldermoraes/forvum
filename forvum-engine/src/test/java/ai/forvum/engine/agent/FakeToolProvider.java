package ai.forvum.engine.agent;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * A deterministic in-process {@link ai.forvum.sdk.ToolProvider} (extension id {@code faketools})
 * contributing four synthetic tools, so the {@code AgentToolBelt} glob-filtering path is exercisable
 * without a real tool module — the tool-side analogue of {@link FakeModelProvider}. Discovered by the
 * engine's {@code ToolRegistry} at startup like any plugin.
 */
@ApplicationScoped
public class FakeToolProvider extends AbstractToolProvider {

    @Override
    public String extensionId() {
        return "faketools";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(
                new ToolSpec("fs.read", "read a file", PermissionScope.FS_READ, "{}"),
                new ToolSpec("fs.write", "write a file", PermissionScope.FS_WRITE, "{}"),
                new ToolSpec("web.search", "search the web", PermissionScope.FS_READ, "{}"),
                new ToolSpec("web.get", "fetch a url", PermissionScope.FS_READ, "{}"));
    }
}
