package ai.forvum.app;

import ai.forvum.core.Persona;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentSpecReader;
import ai.forvum.engine.config.AgentReader;
import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.tools.ToolFilter;
import ai.forvum.sdk.ToolProvider;
import ai.forvum.tools.mcp.McpServerConfig;
import ai.forvum.tools.mcp.McpServerSpec;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code forvum tools} (#184): list every tool compiled into this binary — its name, required
 * {@code PermissionScope}, whether it needs user confirmation, the owning extension, whether it is in the
 * {@code main} agent's belt, and whether it is configured/ready — so a fresh install can SEE its
 * capabilities instead of perceiving "no tools". Config-read only, never a network call.
 *
 * <p>It is a {@code CommandMode} one-shot (skips Flyway/watcher/cron/registry materialization). The registry
 * is deliberately empty for a one-shot, so tools are gathered directly from {@code Instance<ToolProvider>}
 * via {@link ToolInventoryCollector} (which SKIPS the MCP bridge — its {@code tools()} is a blocking
 * connect, P2-13). Configured MCP servers are listed from the {@code mcp-servers/} files (no connect),
 * pointing the operator at {@code forvum mcp list} to materialize them. The engine built-in
 * {@code spawn_worker} is added by the {@code SupervisorGraph} after the belt, not registry-owned, so it is
 * not listed here. Belt membership is reader-as-oracle: it reuses {@code AgentSpecReader} + {@link ToolFilter},
 * so it agrees with how the engine actually filters the belt. Exit 0 always (informational). Secrets are
 * never printed — only config-gap hints, and MCP URLs are redacted.
 */
@CommandLine.Command(
        name = "tools",
        description = "List the built-in tools, their required scope, belt membership, and readiness.")
public class ToolsCommand implements Callable<Integer> {

    @Inject
    Instance<ToolProvider> providers;

    @Inject
    ForvumHome home;

    @Inject
    ConfigLoader loader;

    @Inject
    McpServerConfig mcpServers;

    @Override
    public Integer call() {
        ToolInventoryCollector.Catalog catalog = ToolInventoryCollector.collect(providers);
        Optional<List<String>> belt = mainBelt();
        Set<String> beltNames = belt
                .map(globs -> ToolFilter.filter(globs, catalog.specs()).stream()
                        .map(ToolSpec::name).collect(Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);

        System.out.println("Forvum tools (belt = agents/main.json allowedTools):");
        if (belt.isEmpty()) {
            System.out.println("  (no readable agents/main.json — run 'forvum init', then 'forvum doctor')");
        }

        List<ToolSpec> sorted = catalog.specs().stream()
                .sorted(Comparator.comparing(ToolSpec::name))
                .toList();
        for (ToolSpec spec : sorted) {
            String owner = catalog.owners().getOrDefault(spec.name(), "?");
            String confirm = spec.userConfirmRequired() ? " confirm" : "";
            String beltCol = belt.isEmpty() ? "-" : (beltNames.contains(spec.name()) ? "yes" : "no");
            String gap = catalog.configGaps().get(spec.name());
            String readiness = gap == null ? "ready" : "needs-config: " + gap;
            System.out.printf("  %-18s %-18s%-9s [%s]  belt:%s  %s%n",
                    spec.name(), spec.requiredScope().name(), confirm, owner, beltCol, readiness);
        }

        listMcpServers();
        return 0;
    }

    /** Print the configured MCP servers from their files (no connect); {@code forvum mcp list} materializes. */
    private void listMcpServers() {
        List<McpServerSpec> servers = mcpServers.readAll();
        if (servers.isEmpty()) {
            return;
        }
        System.out.println("MCP servers (materialize with 'forvum mcp list'):");
        for (McpServerSpec server : servers) {
            String url = server.url() == null ? "(no url)" : McpListCommand.redactUrl(server.url());
            System.out.printf("  mcp.%s.*  %s  MCP_REMOTE%n", server.id(), url);
        }
    }

    /**
     * The {@code main} agent's {@code allowedTools} globs, read through the engine's own binders
     * (reader-as-oracle). Empty when {@code agents/main.json} is absent or unparseable — the belt column
     * then shows {@code -} and a hint, and the command still exits 0.
     */
    private Optional<List<String>> mainBelt() {
        AgentReader reader = new AgentReader(loader, home);
        JsonNode spec;
        try {
            spec = reader.spec("main").orElse(null);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (spec == null) {
            return Optional.empty();
        }
        String persona = reader.persona("main").orElse("");
        try {
            Persona p = new AgentSpecReader().parseSpec(new AgentId("main"), persona, spec).persona();
            return Optional.of(p.allowedTools());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
