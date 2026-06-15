package ai.forvum.app;

import ai.forvum.engine.config.ForvumHome;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code forvum mcp add <url> [--id <id>] [--header "Name: value"]...} (P2-13): register a remote MCP
 * server (HTTP/SSE) by writing {@code ~/.forvum/mcp-servers/<id>.json} owner-only ({@code 0600}, the
 * {@code InitCommand} recipe). The directory is hot-loaded ({@code ConfigWatcher}), so the engine
 * ToolRegistry resyncs and the server's tools surface as {@code mcp.<id>.<tool>} (carrying
 * {@code PermissionScope.MCP_REMOTE}) without a restart. {@code add} is a trust grant for LISTING the
 * server's tools (DR-6b §9.3) — only resolves+writes a file, so (like {@code plugin}/{@code skill}) it is
 * a {@code CommandMode} one-shot needing neither the DB nor the watcher. v0.5 writes the {@code http}
 * transport; stdio is a documented follow-up.
 */
@CommandLine.Command(
        name = "add",
        description = "Register a remote MCP server (HTTP/SSE) into ~/.forvum/mcp-servers/.")
public class McpAddCommand implements Callable<Integer> {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ForvumHome home;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<url>",
            description = "The MCP server's SSE endpoint URL (http or https).")
    String url;

    @CommandLine.Option(
            names = "--id",
            description = "Server id — the mcp-servers/<id>.json stem and the mcp.<id>. tool prefix. "
                    + "Defaults to the URL host.")
    String id;

    @CommandLine.Option(
            names = {"--header", "-H"},
            description = "A custom request header for the server, formatted \"Name: value\" (repeatable).")
    List<String> headers;

    @Override
    public Integer call() {
        String serverId = deriveId(id != null ? id : hostOf(url));
        if (serverId == null) {
            System.err.println("MCP add failed: could not derive a server id from '" + url
                    + "'; pass --id <name>.");
            return 1;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("transport", "http");
        root.put("url", url);
        root.put("enabled", true);
        if (headers != null && !headers.isEmpty()) {
            ObjectNode headerNode = root.putObject("headers");
            for (String header : headers) {
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    System.err.println("MCP add failed: header '" + header
                            + "' must be formatted \"Name: value\".");
                    return 1;
                }
                headerNode.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
            }
        }
        Path target = home.mcpServers().resolve(serverId + ".json");
        try {
            createDir(home.mcpServers());
            Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            if (POSIX) {
                Files.setPosixFilePermissions(target, FILE_PERMS);
            }
        } catch (IOException e) {
            System.err.println("MCP add failed: could not write " + target + ": " + e.getMessage());
            return 1;
        }
        System.out.println("Registered MCP server '" + serverId + "' -> " + target);
        System.out.println("Its tools surface as mcp." + serverId + ".* (PermissionScope MCP_REMOTE) on "
                + "the next ToolRegistry resync / run.");
        return 0;
    }

    /** The host of {@code url}, or {@code null} if it has none (used as the default server id). */
    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A safe server id from {@code raw}: keep only {@code [A-Za-z0-9._-]}; {@code null} if it empties out. */
    private static String deriveId(String raw) {
        if (raw == null) {
            return null;
        }
        String derived = raw.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-").replaceAll("-{2,}", "-").replaceAll("^[-.]+|[-.]+$", "");
        return derived.isEmpty() ? null : derived;
    }

    private static void createDir(Path dir) throws IOException {
        if (POSIX) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
        } else {
            Files.createDirectories(dir);
        }
    }
}
