package ai.forvum.engine.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Test-only observer that captures {@link ConfigurationChangedEvent}s into a thread-safe queue, so a
 * test can assert what the watcher fired. Events are fired on the watcher's virtual thread, hence the
 * cross-thread {@link BlockingQueue} handoff.
 */
@ApplicationScoped
public class ConfigChangeRecorder {

    private final BlockingQueue<ConfigurationChangedEvent> events = new LinkedBlockingQueue<>();

    void on(@Observes ConfigurationChangedEvent event) {
        events.add(event);
    }

    void clear() {
        events.clear();
    }

    /** Waits up to {@code timeout} for the next event; fails the test if none arrives. */
    ConfigurationChangedEvent awaitNext(Duration timeout) throws InterruptedException {
        ConfigurationChangedEvent event = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (event == null) {
            throw new AssertionError("No ConfigurationChangedEvent within " + timeout);
        }
        return event;
    }
}
