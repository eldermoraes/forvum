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
    WEB_SEARCH,
    /**
     * Authority to retrieve durable facts via {@code memory.recall} (#193, {@code forvum-tools-memory}) —
     * the read half of the explicit memory surface layered on #175's local {@code MemoryProvider}. Every
     * recall is confined to the caller's identity/agent by the provider, so this scope gates the tool, not
     * cross-tenant access. Distinct from {@link #MEMORY_WRITE} so a role can grant recall without letting
     * the agent deliberately persist new facts.
     */
    MEMORY_READ,
    /**
     * Authority to deliberately persist a durable fact via {@code memory.save} (#193,
     * {@code forvum-tools-memory}) — the write half of the explicit memory surface. Every save is routed
     * through the DR-6a pre-memory-write filter and scoped to the caller's identity/agent. The permissive
     * {@code default-user} role ({@code EnumSet.allOf}) includes it; a restricted role can grant recall
     * ({@link #MEMORY_READ}) while withholding write.
     */
    MEMORY_WRITE,
    /**
     * Authority to synthesize speech via {@code tts.speak} (#186, {@code forvum-tools-tts}) — driving the
     * operator-installed piper binary to write a synthesized WAV under the workspace. Distinct from
     * {@link #FS_WRITE} so a role can grant filesystem write without granting audio synthesis / a
     * subprocess launch. The permissive {@code default-user} role ({@code EnumSet.allOf}) includes it; a
     * restricted role can withhold it. The read-side sibling {@code MEDIA_ANALYZE} (#185) is not yet
     * declared.
     */
    MEDIA_SYNTHESIZE;

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
