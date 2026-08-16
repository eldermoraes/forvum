package ai.forvum.engine.plan;

import ai.forvum.core.BlockType;
import ai.forvum.core.Role;
import ai.forvum.engine.persistence.MessageEntity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.transaction.Transactional;

import java.util.Optional;

/**
 * SQLite-backed {@link PlanStore} over {@code messages} (#190). Each save is one append-only row —
 * {@code role = tool} (excluded from the {@code AgentMemory} history rebuild, so the framed injection is
 * the plan's ONLY window surface) with {@code block_type = plan} (the compaction discriminator: newest
 * retained, superseded stripped). The write commits mid-turn in its own short transaction through the
 * CDI proxy (the {@code ApprovalStore} recipe) and survives a later turn failure — deliberate
 * scratchpad semantics, the {@code tool_invocations} mid-turn precedent. {@code @ActivateRequestContext}
 * makes each method safe on any thread (turn VT, cron, one-shot).
 */
@ApplicationScoped
public class PanachePlanStore implements PlanStore {

    @Override
    @Transactional
    @ActivateRequestContext
    public void save(String sessionId, String agentId, String renderedPlan) {
        MessageEntity row = new MessageEntity();
        row.sessionId = sessionId;
        row.agentId = agentId;
        row.role = Role.TOOL.dbValue();
        row.content = renderedPlan;
        row.tokens = null;
        row.blockType = BlockType.PLAN.dbValue();
        row.createdAt = System.currentTimeMillis();
        row.persist();
    }

    @Override
    @Transactional
    @ActivateRequestContext
    public Optional<String> latest(String sessionId, String agentId) {
        MessageEntity row = MessageEntity.<MessageEntity>find(
                "sessionId = ?1 and agentId = ?2 and blockType = ?3 order by id desc",
                sessionId, agentId, BlockType.PLAN.dbValue()).firstResult();
        return Optional.ofNullable(row).map(r -> r.content);
    }
}
