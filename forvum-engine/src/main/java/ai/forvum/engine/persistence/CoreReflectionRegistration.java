package ai.forvum.engine.persistence;

import io.quarkus.runtime.annotations.RegisterForReflection;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.core.RoleSpec;
import ai.forvum.core.TaskRecord;
import ai.forvum.core.TaskStatus;
import ai.forvum.core.TaskType;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.event.FallbackTriggered;
import ai.forvum.core.budget.CostBudget;
import ai.forvum.core.budget.DayWindow;
import ai.forvum.core.budget.ExhaustionCause;
import ai.forvum.core.budget.SessionWindow;
import ai.forvum.core.budget.Spend;
import ai.forvum.core.budget.Usage;
import ai.forvum.core.id.AgentId;
import ai.forvum.core.id.Identity;

/**
 * The single GraalVM native reflection holder for Layer-0 ({@code forvum-core}) records.
 *
 * <p>Core records cannot carry {@code @RegisterForReflection} themselves — {@code forvum-core} bans
 * {@code io.quarkus*} (CLAUDE.md section 5) — so the engine registers them here. This uses the real
 * {@code io.quarkus.runtime.annotations.RegisterForReflection}, NOT the {@code forvum-sdk} re-export,
 * which is an inert placeholder until its translating build step ships. Compile-time {@code .class}
 * references break the build if any target is renamed or removed.
 *
 * <p>This is the one holder per ULTRAPLAN section 6.3 — later milestones (e.g. M8) append their
 * Layer-0 targets here rather than creating a second holder.
 */
@RegisterForReflection(targets = {
        AgentId.class,
        Identity.class,
        ChannelMessage.class,
        Persona.class,
        ToolSpec.class,
        RoleSpec.class,
        TaskRecord.class,
        TaskType.class,
        TaskStatus.class,
        ModelRef.class,
        CostBudget.class,
        Spend.class,
        Usage.class,
        DayWindow.class,
        SessionWindow.class,
        ExhaustionCause.class,
        FallbackTriggered.class
})
public final class CoreReflectionRegistration {
    private CoreReflectionRegistration() {
    }
}
