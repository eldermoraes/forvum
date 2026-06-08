package ai.forvum.core;

/**
 * A request for relevant memory, handed to {@code MemoryProvider.retrieve} alongside a
 * {@link MemoryPolicy} (ULTRAPLAN section 4.3.6; the Context-Engineering Select pillar; DR-5). It scopes
 * retrieval to one agent and session and carries the free-text the provider matches against (a query the
 * provider embeds or keyword-filters per the policy's {@link RetrievalStrategy}).
 *
 * @param agentId   the agent whose memory is being queried (non-blank).
 * @param sessionId the session scoping the query (non-blank).
 * @param text      the free-text query (non-blank).
 */
public record MemoryQuery(String agentId, String sessionId, String text) {
    public MemoryQuery {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException(
                "MemoryQuery agentId must be non-blank. Every retrieval is scoped to one agent.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException(
                "MemoryQuery sessionId must be non-blank. Every retrieval is scoped to one session.");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(
                "MemoryQuery text must be non-blank. There is nothing to retrieve against an empty "
              + "query string.");
        }
    }
}
