package ai.forvum.core;

/**
 * Capability scopes that tools declare and the engine's ToolExecutor enforces
 * before invoking a tool. A tool's required scope must be reachable from the
 * agent's allowed-tools set (indirectly, via the tool's registration) or the
 * call is refused with PermissionDeniedException and logged to
 * {@code tool_invocations} with {@code status = 'denied'}.
 *
 * <p>Not persisted as a typed SQL column in V1 — denial outcome is captured
 * only by {@code tool_invocations.status}. Serialization to JSON/YAML config
 * uses {@link #name()} directly.
 *
 * <p>This enum is closed at compile time and grows at milestone boundaries.
 * See project docs (docs/ULTRAPLAN.md §6). Plugins compiled against a given
 * core version may only reference scopes present in that version.
 */
public enum PermissionScope {
    FS_READ,
    FS_WRITE,
    /**
     * Authority to invoke a tool surfaced from a REMOTE MCP server (P2-13, DR-6b §9.3). Remote MCP
     * tool-specs are UNTRUSTED (they breach the author-authored assumption), so the MCP bridge stamps
     * this scope on every {@code mcp.<server>.<tool>} spec; it is the RBAC second gate (beyond belt
     * membership) that the P2-11 effective-scopes check enforces. The permissive {@code default-user}
     * role ({@code EnumSet.allOf}) includes it, so an operator who registers a server and puts its tool
     * in an agent's belt gets it working; a restricted role can withhold it.
     */
    MCP_REMOTE,
    /**
     * Authority to execute a process via {@code shell.exec} (and its sandboxed sibling) — the most
     * dangerous capability in the system (PR-6, #27; ULTRAPLAN §9.2.5). Beyond this RBAC scope and belt
     * membership, every such call is also bounded by the {@code tools/shell.json} allowlist and parked
     * through the P2-14 #39 {@code USER_CONFIRM_REQUIRED} approval gate. Declared here in the PR-6 preamble
     * so {@code forvum-tools-shell}/{@code -sandbox} reference it from their first commit.
     */
    SHELL_EXEC,
    /**
     * Authority to drive a browser via the operator-attached Chrome/Chromium over CDP (PR-6, #26). The
     * tool attaches to an operator-launched browser ({@code --remote-debugging-port}); it never downloads
     * one. Declared in the PR-6 preamble for {@code forvum-tools-browser}.
     */
    WEB_BROWSE,
    /**
     * Authority to fetch a URL's content via {@code web.fetch} (PR-6, {@code forvum-tools-web}; resolves
     * the ULTRAPLAN §epic-4 web-tool surface). Read-only outbound HTTP.
     */
    WEB_FETCH,
    /**
     * Authority to run a web search via {@code web.search} (PR-6, {@code forvum-tools-web}, Brave Search
     * API). Distinct from {@link #WEB_FETCH} so a role can grant search without arbitrary URL fetch.
     */
    WEB_SEARCH;

    /**
     * Parses a string into a {@code PermissionScope}, throwing a contextual
     * {@link IllegalStateException} on unknown input.
     *
     * <p>Preferred over {@link Enum#valueOf(Class, String)} because the
     * built-in throws a generic {@link IllegalArgumentException} whose
     * message (e.g., {@code "No enum constant ai.forvum.core.PermissionScope.FOO"})
     * does not identify the likely cause (config drift, hand-edited manifest)
     * or point an operator at where to look. This factory's
     * {@code IllegalStateException} message names the suspect sources
     * explicitly so a production log line carries actionable triage info.
     *
     * @param value the raw string from a config file or manifest
     * @return the matching {@code PermissionScope}
     * @throws IllegalStateException if {@code value} is {@code null} or does
     *         not match any declared scope
     */
    public static PermissionScope fromName(String value) {
        for (PermissionScope s : values()) {
            if (s.name().equals(value)) {
                return s;
            }
        }
        throw new IllegalStateException(
            "Unknown PermissionScope value: '" + value + "'. Indicates config drift "
          + "or an invalid identity/tool manifest. Check files under $FORVUM_HOME.");
    }
}
