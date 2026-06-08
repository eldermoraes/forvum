package ai.forvum.engine.cron;

import ai.forvum.core.PermissionScope;
import ai.forvum.engine.agent.Agent;
import ai.forvum.engine.agent.AgentRegistry;
import ai.forvum.engine.agent.RoleRegistry;
import ai.forvum.engine.config.ChangeType;
import ai.forvum.engine.config.ConfigurationChangedEvent;
import ai.forvum.engine.config.CronReader;
import ai.forvum.engine.context.CurrentAgent;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.routing.LlmSelector;
import ai.forvum.engine.runtime.CommandMode;

import ai.forvum.core.TaskRecord;
import ai.forvum.core.TaskStatus;
import ai.forvum.core.TaskType;
import ai.forvum.sdk.TaskExecutor;

import dev.langchain4j.model.chat.ChatModel;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Registers background agent turns from {@code $FORVUM_HOME/crons/*.json} programmatically (ULTRAPLAN
 * section 7.1 M19). On startup it schedules every cron via the {@link Scheduler}; it reacts to the M4
 * {@link ConfigurationChangedEvent} so a new/changed/removed cron file reloads <strong>without
 * restart</strong>. Each fire runs a full M18 turn (writing {@code messages}/{@code provider_calls}/
 * {@code capr_events}) for the cron's agent using the cron's OWN model, on a virtual thread, with
 * overlapping runs of the same id suppressed.
 */
@ApplicationScoped
public class CronScheduler {

    private static final Logger LOG = Logger.getLogger(CronScheduler.class);
    private static final String CRONS = "crons";
    private static final String JSON = ".json";

    private final CronSpecReader specReader = new CronSpecReader();

    @Inject
    Scheduler scheduler;

    @Inject
    CronReader cronReader;

    @Inject
    AgentRegistry registry;

    @Inject
    LlmSelector llmSelector;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    CommandMode commandMode;

    @Inject
    TaskExecutor taskExecutor;

    /** Schedule every cron present at startup. A missing {@code crons/} folder simply yields no jobs. */
    void onStart(@Observes StartupEvent event) {
        if (commandMode.isOneShot()) {
            // A one-shot command (--help/--version/init) never runs a turn — don't start the (forced)
            // scheduler for it. Crucially, the cold-start path skips Flyway, so a cron must not fire a turn
            // against an un-migrated DB (M20; mirrors PersistenceBootstrap/ConfigWatcher).
            LOG.debugf("One-shot command — not scheduling crons.");
            return;
        }
        for (String id : cronReader.ids()) {
            scheduleFromFile(id);
        }
    }

    /**
     * Reload on a {@code crons/<id>.json} change (M4 plumbing): (re)schedule on CREATED/MODIFIED,
     * unschedule on DELETED — no restart. Non-cron paths are ignored.
     */
    void onConfigChange(@Observes ConfigurationChangedEvent event) {
        Optional<String> cronId = cronIdFor(event.path());
        if (cronId.isEmpty()) {
            return;
        }
        String id = cronId.get();
        if (event.type() == ChangeType.DELETED) {
            scheduler.unscheduleJob(id);
            LOG.infof("Unscheduled cron '%s' (file removed).", id);
        } else {
            scheduleFromFile(id);
        }
    }

    private void scheduleFromFile(String id) {
        Optional<CronSpec> spec;
        try {
            spec = cronReader.read(id).map(json -> specReader.parse(id, json));
        } catch (RuntimeException e) {
            // An edit that makes a cron invalid must STOP the prior job, not leave it firing the stale
            // spec/model (the DELETED path already unschedules; an invalid MODIFIED must too).
            LOG.warnf("Disabling cron '%s' — invalid definition: %s", id, e.getMessage());
            scheduler.unscheduleJob(id);
            return;
        }
        if (spec.isPresent()) {
            schedule(spec.get());
        } else {
            // The file is absent/empty (e.g. read mid-write) — stop any prior job rather than keep it stale.
            scheduler.unscheduleJob(id);
        }
    }

    private void schedule(CronSpec spec) {
        scheduler.unscheduleJob(spec.id()); // idempotent: replace any existing job for this id
        scheduler.newJob(spec.id())
                .setCron(spec.cron())
                .setConcurrentExecution(Scheduled.ConcurrentExecution.SKIP)
                .setTask(execution -> fire(spec), true) // true => run the turn on a virtual thread (section 3.8)
                .schedule();
        LOG.infof("Scheduled cron '%s' (%s) for agent '%s'.", spec.id(), spec.cron(), spec.agentId().value());
    }

    /**
     * Run one cron turn. Bound to the cron's agent + its own model, and to the distinguished restricted
     * {@code cron} role's effective scopes (P2-11) so a scheduled job is denied a tool outside that role's
     * scope-set. Failures are logged, never fatal.
     */
    void fire(CronSpec spec) {
        String sessionId = "cron:" + spec.id();
        long startedAt = System.currentTimeMillis();
        try {
            Agent agent = registry.getOrCreate(spec.agentId());
            ChatModel model = llmSelector.resolve(spec.primaryModel(), spec.agentId().value(), sessionId);
            Set<PermissionScope> cronScopes = roleRegistry.scopesFor(RoleRegistry.CRON);
            ScopedValue.where(CurrentAgent.CURRENT_AGENT, spec.agentId())
                    .where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, cronScopes)
                    .run(() -> agent.respond(sessionId, spec.prompt(), model));
            // Record the cron task ONLY after the turn succeeds (persist-after-success; the turn's own
            // ledger rows survive in their own transactions, and a failed turn's ERROR task is recorded
            // below — the whole fire is never wrapped in one transaction).
            recordCronTask(spec, startedAt, TaskStatus.COMPLETED, null);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Cron '%s' turn failed for agent '%s'", spec.id(), spec.agentId().value());
            recordCronTask(spec, startedAt, TaskStatus.ERROR, e.getMessage());
        }
    }

    /** Write one {@code tasks} ledger row for this cron fire. A recorder failure must not break the loop. */
    private void recordCronTask(CronSpec spec, long startedAt, TaskStatus status, String error) {
        long completedAt = System.currentTimeMillis();
        try {
            taskExecutor.record(new TaskRecord(
                    UUID.randomUUID().toString(), spec.agentId(), TaskType.CRON, spec.id(), null,
                    "cron:" + spec.id(), startedAt, startedAt, completedAt, status, null, error,
                    completedAt - startedAt, completedAt));
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to record tasks-ledger row for cron '%s'", spec.id());
        }
    }

    /** The cron id for a {@code crons/<id>.json} path (relative to {@code $FORVUM_HOME}), else empty. */
    static Optional<String> cronIdFor(Path relativePath) {
        if (relativePath.getNameCount() < 2 || !relativePath.getName(0).toString().equals(CRONS)) {
            return Optional.empty();
        }
        String file = relativePath.getFileName().toString();
        if (!file.endsWith(JSON)) {
            return Optional.empty();
        }
        return Optional.of(file.substring(0, file.length() - JSON.length()));
    }
}
