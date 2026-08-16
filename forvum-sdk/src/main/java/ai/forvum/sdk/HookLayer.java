package ai.forvum.sdk;

/**
 * The egress seam at which an {@link OutputGuard} is invoked (ULTRAPLAN section 9.2.3, DR-6a). v0.1
 * wired only {@link #PRE_CHANNEL_EMIT}; #188 additionally wired {@link #PRE_TOOL_CALL} for the
 * {@code message.send} egress (text leaving the engine through an outbound tool) and for the cron
 * channel-delivery sink; {@link #PRE_MEMORY_WRITE} stays reserved so a guard can declare intent without
 * a contract change when it is wired in a later milestone (DR-6a DP-4).
 */
public enum HookLayer {

    /** The single {@code AgentEvent}/{@code TokenDelta} → channel-render boundary (wired in v0.1). */
    PRE_CHANNEL_EMIT,

    /** Reserved: before a turn's reply is written to memory. */
    PRE_MEMORY_WRITE,

    /** Before outbound text leaves the engine through a tool ({@code message.send}; wired by #188). */
    PRE_TOOL_CALL
}
