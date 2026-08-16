package ai.forvum.tools.sessions;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.SessionAccess;

import java.util.List;

/**
 * The {@code agents.list} tool (#189): list the configured agent ids. Holds the ToolSpec
 * ({@link PermissionScope#SESSION_READ}) and renders the seam's ids as a plain text list for the model;
 * the identity-gated read (an unresolved caller sees nothing) is the engine's {@link SessionAccess}
 * implementation.
 */
public final class AgentsListTool {

    public static final ToolSpec SPEC = new ToolSpec(
            "agents.list",
            "List the ids of the configured agents on this Forvum install. Use to discover which agents "
          + "exist before inspecting or messaging their sessions.",
            PermissionScope.SESSION_READ,
            "{}");

    private AgentsListTool() {
    }

    static String list(SessionAccess sessions) {
        List<String> ids = sessions.agentIds();
        if (ids.isEmpty()) {
            return "No agents are visible to you.";
        }
        StringBuilder out = new StringBuilder("Configured agents:");
        for (String id : ids) {
            out.append("\n- ").append(id);
        }
        return out.toString();
    }
}
