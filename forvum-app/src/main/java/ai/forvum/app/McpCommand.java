package ai.forvum.app;

import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code forvum mcp} (P2-13): the parent of the MCP server-registry subcommands — {@link McpAddCommand}
 * ({@code mcp add <url>}) and {@link McpListCommand} ({@code mcp list}). Invoked bare it prints its usage
 * and exits 0 (picocli routes only to a leaf {@code call()}). MCP servers are operator-registered remote
 * tool sources under {@code ~/.forvum/mcp-servers/}; their tools surface into the agent runtime as
 * {@code mcp.<server>.<tool>} (PermissionScope.MCP_REMOTE).
 */
@CommandLine.Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        description = "Manage remote MCP servers (HTTP/SSE) whose tools surface as mcp.<server>.<tool>.",
        subcommands = { McpAddCommand.class, McpListCommand.class })
public class McpCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
