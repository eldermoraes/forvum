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
    FS_WRITE;

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
