package ai.forvum.sdk;

import java.util.List;

/**
 * The engine-backed access seam the session introspection tool (#189, {@code forvum-tools-sessions})
 * drives for the model-callable {@code agents.list} / {@code sessions.list} / {@code sessions.history} /
 * {@code sessions.send} surface — OpenClaw's session/agent introspection rebuilt on the engine's
 * {@code AgentRegistry} config surface, the {@code sessions}/{@code messages} ledger, and the
 * {@link ChannelTurnDriver} turn dispatch.
 *
 * <p>This is a <strong>Resolution-B seam</strong> (the {@link MemoryAccess}/{@link MediaAnalysis}
 * pattern), NOT a sealed provider: the single implementation lives in {@code forvum-engine} — where the
 * agent config reader, the session persistence, and the turn driver already sit — and plugins do NOT
 * implement it. It is promoted to {@code forvum-sdk} only so a Layer-3 tool module (which the enforcer
 * bars from depending on {@code forvum-engine}) can inject the contract and let ArC resolve it to the
 * engine bean. A plain (non-sealed) interface: the engine is the sole implementor, so there is no closed
 * implementor set to seal.
 *
 * <p><strong>Identity scoping is FAIL-CLOSED</strong> (#170/#167 posture): every method reads the
 * caller's identity from the engine's per-turn scope binding. An unbound or unresolved (anonymous)
 * identity sees nothing — the reads return empty and {@code send} is denied. A session owned by another
 * identity is invisible: {@link #history} and {@link #send} refuse it with the SAME diagnostic as a
 * nonexistent session, so the seam is not an existence oracle across tenants. The single-user
 * {@code default} identity (the #53 namespace-collapse tenant every turn binds when multi-user is off)
 * sees every session — one operator, one namespace.
 *
 * <p>{@code forvum-sdk} is Quarkus-free; the contract takes/returns only JDK types + the Layer-1 value
 * records {@link SessionSummary}/{@link SessionMessage}, so it is reflection-free and native-safe.
 */
public interface SessionAccess {

    /**
     * The configured agent ids (the {@code agents/<id>.md} + {@code <id>.json} pairs under
     * {@code $FORVUM_HOME}), sorted. Empty when the caller's identity is unbound or unresolved
     * (fail-closed — an anonymous caller introspects nothing).
     */
    List<String> agentIds();

    /**
     * The sessions visible to the caller's identity, most recently active first. A session owned by
     * another identity is absent; an unbound/unresolved caller gets an empty list (fail-closed).
     */
    List<SessionSummary> sessions();

    /**
     * The most recent {@code limit} transcript messages of a caller-visible session, oldest first.
     *
     * @throws IllegalArgumentException when no session {@code sessionId} is visible to the caller —
     *         deliberately the same failure whether the session does not exist or belongs to another
     *         identity (no cross-tenant existence oracle)
     */
    List<SessionMessage> history(String sessionId, int limit);

    /**
     * Deliver {@code message} into the caller-visible session {@code sessionId} by dispatching a full
     * turn through the engine's turn driver (the target session's agent responds and both the message
     * and its reply are appended to the target transcript). Returns the target agent's reply.
     *
     * @throws IllegalArgumentException when no session {@code sessionId} is visible to the caller
     *         (same fail-closed diagnostic as {@link #history})
     * @throws IllegalStateException when the dispatched turn fails, or when called re-entrantly from a
     *         turn that is itself a {@code sessions.send} delivery (delivery loops are refused)
     */
    String send(String sessionId, String message);
}
