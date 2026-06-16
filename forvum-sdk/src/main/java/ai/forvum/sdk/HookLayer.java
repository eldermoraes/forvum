package ai.forvum.sdk;

/**
 * The egress seam at which an {@link OutputGuard} is invoked (ULTRAPLAN section 9.2.3, DR-6a). v0.1 wires
 * only {@link #PRE_CHANNEL_EMIT}; the other two are reserved so a guard can declare intent without a
 * contract change when they are wired in a later milestone (DR-6a DP-4).
 */
public enum HookLayer {

    /** The single {@code AgentEvent}/{@code TokenDelta} → channel-render boundary (wired in v0.1). */
    PRE_CHANNEL_EMIT,

    /** Reserved: before a turn's reply is written to memory. */
    PRE_MEMORY_WRITE,

    /** Reserved: before a tool-call argument leaves the engine. */
    PRE_TOOL_CALL
}
