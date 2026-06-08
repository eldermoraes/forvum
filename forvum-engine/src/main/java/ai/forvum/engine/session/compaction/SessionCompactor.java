package ai.forvum.engine.session.compaction;

import ai.forvum.core.BlockType;
import ai.forvum.core.Role;
import ai.forvum.engine.persistence.CaprEventEntity;
import ai.forvum.engine.persistence.MessageEntity;
import ai.forvum.engine.persistence.SessionEntity;

import io.quarkus.narayana.jta.QuarkusTransaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Prefix-preserving session compaction (P2-COMPACT, ULTRAPLAN section 7.2 item 20; the CE Compress
 * pillar realized on the live session window). When a session's non-prefix token estimate exceeds the
 * {@link CompactionPolicy} reserve floor, this folds the OLDEST turns into one summary message
 * (produced by the injectable {@link Summarizer}) so the cached prompt prefix stays byte-stable.
 *
 * <p><strong>Invariants (pinned by {@link SessionCompactorIT}):</strong>
 * <ul>
 *   <li><strong>Prefix preservation.</strong> A message with {@code id <= cachedPrefixEndIndex} is never
 *       read for compaction, never deleted, never re-summarized. The summary message reclaims the
 *       OLDEST dropped id (a native insert), so it lands numerically + chronologically right after the
 *       old prefix and before every retained message; {@code cachedPrefixEndIndex} advances to it, so
 *       the existing {@code order by id} replay path needs no change and the frozen prefix grows
 *       monotonically.</li>
 *   <li><strong>Oldest-first.</strong> The most-recent turns whose token estimate fits {@code retainTokens}
 *       are retained; everything older (after the prefix) is dropped — never the reverse.</li>
 *   <li><strong>Orphan stripping.</strong> {@code TURN_REASONING}/{@code TURN_ARTIFACT} rows older than
 *       the oldest retained USER message are deleted (their turn has left the window). A
 *       {@code TOOL_EXECUTION} row is conservatively RETAINED when it still belongs to a retained turn
 *       (created at/after that boundary), and dropped only once it is older than the boundary.</li>
 *   <li><strong>CAPR is archived, never deleted.</strong> A {@code capr_events} row whose {@code turnId}
 *       references a dropped assistant message is marked {@code is_archived = 1}; CAPR history stays
 *       append-only so aggregation can exclude compacted turns without regressing.</li>
 * </ul>
 *
 * <p>Concurrency: this runs synchronously on the turn's (virtual) thread; no {@code synchronized}, no
 * shared mutable state (CLAUDE.md section 11). The blocking summarizer LLM round-trip is deliberately
 * run OUTSIDE any DB transaction — a short read transaction plans the pass, the model is called with no
 * Agroal/SQLite connection or open JTA transaction held, then a short write transaction applies the
 * mutations — so the connection is never pinned across the network call (CLAUDE.md sections 11 / 14).
 */
@ApplicationScoped
public class SessionCompactor {

    private static final Logger LOG = Logger.getLogger(SessionCompactor.class);

    /** Rough chars-per-token estimate when a row carries no measured {@code tokens} count. */
    private static final int CHARS_PER_TOKEN = 4;

    @Inject
    Summarizer summarizer;

    @Inject
    EntityManager em;

    /**
     * Compact {@code sessionId}/{@code agentId} if it is over {@code policy.reserveFloorTokens}, returning
     * a {@link CompactionResult}. A session at or below the floor is left untouched (a no-op result). Idempotent
     * in the sense that re-running on an already-compacted-and-small session does nothing.
     */
    public CompactionResult compact(String sessionId, String agentId, CompactionPolicy policy) {
        // 1) Plan the pass in a short READ transaction — load the region, partition it, and capture the
        //    primitives the write phase needs. No connection is held past this point.
        CompactionPlan plan = QuarkusTransaction.requiringNew().call(() -> plan(sessionId, agentId, policy));
        if (!plan.compacted()) {
            return CompactionResult.notCompacted(plan.prefixEnd());
        }

        // 2) Summarize the dropped run with NO transaction/connection held: the blocking LLM HTTP
        //    round-trip must never pin the Agroal/SQLite connection (CLAUDE.md sections 11 / 14).
        String summary = summarizer.summarize(plan.droppedContents());

        // 3) Apply the mutations in a short WRITE transaction (archive CAPR, delete dropped/orphans,
        //    native-insert the summary at the reclaimed id, advance the frozen prefix).
        return QuarkusTransaction.requiringNew().call(() -> applyPlan(sessionId, agentId, plan, summary));
    }

    /** Read-only planning pass: partition the compactable region and capture the write phase's inputs. */
    private CompactionPlan plan(String sessionId, String agentId, CompactionPolicy policy) {
        SessionEntity session = SessionEntity.findById(sessionId);
        Integer prefixEnd = session == null ? null : session.cachedPrefixEndIndex;
        long prefixFloor = prefixEnd == null ? Long.MIN_VALUE : prefixEnd;

        // Compactable region: everything AFTER the frozen prefix, oldest first. The prefix (id <= prefixEnd)
        // is never even loaded here, so it can never be mutated.
        List<MessageEntity> region = MessageEntity.list(
                "sessionId = ?1 and agentId = ?2 and id > ?3 order by id",
                sessionId, agentId, prefixFloor);

        int regionTokens = region.stream().mapToInt(SessionCompactor::estimateTokens).sum();
        if (regionTokens <= policy.reserveFloorTokens()) {
            return CompactionPlan.noop(prefixEnd);
        }

        // Walk newest -> oldest over TURN_MESSAGE rows, retaining until the budget is hit; the oldest
        // retained message (any role) is the orphan-stripping boundary, so the newest turn is ALWAYS
        // kept verbatim even when its single TURN_MESSAGE (typically the newest assistant reply, since
        // compaction runs before the new user message is persisted) alone exceeds retainTokens.
        long oldestRetainedMsgCreatedAt = retainBoundary(region, policy.retainTokens());

        // Partition the region into dropped (to summarize/strip) vs retained, by the boundary.
        List<MessageEntity> droppedTurnMessages = new ArrayList<>();
        List<MessageEntity> droppedOrphans = new ArrayList<>();
        for (MessageEntity m : region) {
            if (m.createdAt >= oldestRetainedMsgCreatedAt) {
                continue; // retained turn — keep as-is (incl. its connected TOOL_EXECUTION blocks)
            }
            switch (BlockType.fromDbValue(m.blockType)) {
                case TURN_MESSAGE -> droppedTurnMessages.add(m);
                case TURN_REASONING, TURN_ARTIFACT, TOOL_EXECUTION -> droppedOrphans.add(m);
            }
        }

        if (droppedTurnMessages.isEmpty() && droppedOrphans.isEmpty()) {
            // The whole region is one un-splittable retained turn (or all newer than the boundary): the
            // floor is exceeded by content we cannot drop without losing the live turn. Nothing to do.
            return CompactionPlan.noop(prefixEnd);
        }

        // Capture primitives (the entities detach when this read transaction closes); the summary
        // reclaims the OLDEST dropped id so it joins the frozen prefix in id-order.
        List<String> droppedContents = droppedTurnMessages.stream().map(m -> m.content).toList();
        List<Long> droppedTurnIds = droppedTurnMessages.stream().map(m -> m.id).toList();
        List<Long> droppedOrphanIds = droppedOrphans.stream().map(m -> m.id).toList();
        List<Long> droppedAssistantIds = droppedTurnMessages.stream()
                .filter(m -> Role.fromDbValue(m.role) == Role.ASSISTANT)
                .map(m -> m.id)
                .toList();
        long summaryId = droppedTurnMessages.isEmpty()
                ? droppedOrphans.get(0).id
                : droppedTurnMessages.get(0).id;
        long summaryCreatedAt = droppedTurnMessages.isEmpty()
                ? droppedOrphans.get(0).createdAt
                : droppedTurnMessages.get(0).createdAt;

        return new CompactionPlan(true, prefixEnd, droppedContents, droppedTurnIds, droppedOrphanIds,
                droppedAssistantIds, summaryId, summaryCreatedAt);
    }

    /** Write pass: archive CAPR, delete the dropped run + orphans, native-insert the summary, advance prefix. */
    private CompactionResult applyPlan(String sessionId, String agentId, CompactionPlan plan, String summary) {
        // 1) Archive (never delete) CAPR verdicts whose turn references a dropped assistant message.
        int caprArchived = archiveCapr(sessionId, agentId, plan.droppedAssistantIds());

        // 2) Delete the dropped run + orphans (the summary will reuse plan.summaryId()).
        int summarizedTurns = plan.droppedTurnIds().size();
        int orphansStripped = plan.droppedOrphanIds().size();
        deleteByIds(plan.droppedTurnIds());
        deleteByIds(plan.droppedOrphanIds());
        em.flush();

        // 3) Native insert the summary at the reclaimed id (IDENTITY strategy forbids a manual id on
        // persist; a controlled native insert keeps the id-based prefix rule literally true).
        em.createNativeQuery(
                "INSERT INTO messages (id, session_id, agent_id, role, content, tokens, block_type, created_at) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .setParameter(1, plan.summaryId())
                .setParameter(2, sessionId)
                .setParameter(3, agentId)
                .setParameter(4, Role.SYSTEM.dbValue())
                .setParameter(5, "[summary of " + summarizedTurns + " earlier turns] " + summary)
                .setParameter(6, null)
                .setParameter(7, BlockType.TURN_MESSAGE.dbValue())
                .setParameter(8, plan.summaryCreatedAt())
                .executeUpdate();

        // 4) Advance the frozen prefix to the summary; never touched again.
        SessionEntity session = SessionEntity.findById(sessionId);
        if (session != null) {
            session.cachedPrefixEndIndex = (int) plan.summaryId();
        }

        LOG.debugf("Compacted session %s/%s: %d turns summarized, %d orphans stripped, %d capr archived; "
                        + "cached prefix now ends at message id %d",
                sessionId, agentId, summarizedTurns, orphansStripped, caprArchived, plan.summaryId());

        return new CompactionResult(true, summarizedTurns, orphansStripped, caprArchived,
                (int) plan.summaryId());
    }

    /** Delete every {@code messages} row in {@code ids} (a no-op for an empty list). */
    private void deleteByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        em.createQuery("delete from MessageEntity m where m.id in :ids")
                .setParameter("ids", ids)
                .executeUpdate();
    }

    /**
     * The orphan-stripping boundary: the {@code createdAt} of the oldest retained TURN_MESSAGE,
     * regardless of role. Walks the region newest -> oldest accumulating TURN_MESSAGE token estimates
     * until the next would exceed {@code retainTokens}, keeping at least the single most-recent
     * TURN_MESSAGE verbatim. Tracking the boundary on EVERY retained message (not only a USER row)
     * guarantees the newest turn is never dropped: when the newest message is an ASSISTANT reply that
     * alone exceeds {@code retainTokens} — the typical case, since compaction runs before the next user
     * message is persisted — its {@code createdAt} still becomes the boundary, so it survives verbatim
     * instead of staying at {@code Long.MAX_VALUE} and being classified as dropped. The natural
     * user-then-assistant persist order means a retained pair still resolves the boundary to its USER
     * message, so the orphan-stripping rule is unchanged in the common case. Returns
     * {@code Long.MAX_VALUE} only when no TURN_MESSAGE exists in the region.
     */
    private static long retainBoundary(List<MessageEntity> region, int retainTokens) {
        long boundary = Long.MAX_VALUE;
        int acc = 0;
        for (int i = region.size() - 1; i >= 0; i--) {
            MessageEntity m = region.get(i);
            if (BlockType.fromDbValue(m.blockType) != BlockType.TURN_MESSAGE) {
                continue; // reasoning/artifact/tool rows don't drive the retain budget
            }
            int t = estimateTokens(m);
            if (acc + t > retainTokens && acc > 0) {
                break; // adding this message would blow the retain budget; stop (keep at least one)
            }
            acc += t;
            boundary = m.createdAt; // oldest retained TURN_MESSAGE seen so far (any role)
        }
        return boundary;
    }

    /** Mark {@code is_archived} on every CAPR verdict whose turn references a dropped assistant message. */
    private int archiveCapr(String sessionId, String agentId, List<Long> droppedAssistantIds) {
        if (droppedAssistantIds.isEmpty()) {
            return 0;
        }
        return em.createQuery(
                "update CaprEventEntity c set c.archived = true "
              + "where c.sessionId = :sid and c.agentId = :aid and c.turnId in :ids and c.archived = false")
                .setParameter("sid", sessionId)
                .setParameter("aid", agentId)
                .setParameter("ids", droppedAssistantIds)
                .executeUpdate();
    }

    /** Token estimate: the measured {@code tokens} count, or a chars/4 fallback (>= 1 for any content). */
    static int estimateTokens(MessageEntity m) {
        if (m.tokens != null) {
            return m.tokens;
        }
        int len = m.content == null ? 0 : m.content.length();
        return Math.max(1, len / CHARS_PER_TOKEN);
    }

    /**
     * The detached output of the read-only planning pass, carrying only primitives so the LLM call and
     * the write transaction never touch a managed entity from the read transaction.
     *
     * @param compacted          whether a pass should run (false when the session is under the floor)
     * @param prefixEnd          the session's current cached prefix end (echoed into a no-op result)
     * @param droppedContents    the dropped turn messages' content, oldest first (summarizer input)
     * @param droppedTurnIds     ids of the dropped TURN_MESSAGE rows (deleted in the write pass)
     * @param droppedOrphanIds   ids of the dropped reasoning/artifact/stale-tool rows (deleted)
     * @param droppedAssistantIds ids of the dropped ASSISTANT turn messages (their CAPR verdicts archived)
     * @param summaryId          the OLDEST dropped id the summary reclaims (joins the frozen prefix)
     * @param summaryCreatedAt   the {@code created_at} the summary inherits from the reclaimed row
     */
    private record CompactionPlan(
            boolean compacted,
            Integer prefixEnd,
            List<String> droppedContents,
            List<Long> droppedTurnIds,
            List<Long> droppedOrphanIds,
            List<Long> droppedAssistantIds,
            long summaryId,
            long summaryCreatedAt) {

        static CompactionPlan noop(Integer prefixEnd) {
            return new CompactionPlan(false, prefixEnd, List.of(), List.of(), List.of(), List.of(), 0, 0);
        }
    }
}
