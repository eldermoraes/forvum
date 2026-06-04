package ai.forvum.engine.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * CDI event fired by {@link ConfigWatcher} when a file under {@code $FORVUM_HOME} is created,
 * modified, or deleted. {@code path} is relative to {@code $FORVUM_HOME} (e.g. {@code agents/main.json}).
 *
 * <p>This is an in-VM CDI event: it is never serialized to JSON nor persisted, so it deliberately
 * carries no {@code @RegisterForReflection} — M4 ships no reflectively-constructed JSON DTO. Observers
 * react via {@code @Observes ConfigurationChangedEvent}; each subsystem (the future {@code AgentRegistry},
 * the cron scheduler, …) decides which {@link ChangeType}s and paths it cares about.
 */
public record ConfigurationChangedEvent(Path path, ChangeType type) {

    public ConfigurationChangedEvent {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
    }
}
