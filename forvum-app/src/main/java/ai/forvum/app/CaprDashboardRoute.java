package ai.forvum.app;

import ai.forvum.engine.persistence.CaprEventEntity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.vertx.web.Route;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * The {@code GET /q/dashboard/capr} CAPR dashboard endpoint (ULTRAPLAN §3.6, owned by M18 per X7; the
 * e2e X6 scenario 10 guard). Returns the recorded {@code capr_events} (the per-turn pass/fail verdicts)
 * as a JSON array — read straight from SQLite via Panache — so a client can confirm that running turns
 * produced verdict rows. v0.1 ships judge mode off (every completed turn is a {@code passed=1},
 * {@code judgeModel="none"} row, written by {@code CaprRecorder} from {@code Agent.respond}); the
 * cost-aware aggregate (CAPR joined over {@code provider_calls}) is a later milestone — this endpoint
 * surfaces the raw verdict rows the e2e scenario asserts (≥ 5 after a few turns).
 *
 * <p><strong>Server-path only — no command-mode cold-start impact.</strong> A {@link Route} handler is
 * registered against the {@code vertx-http} server, which binds only when a server channel (the Web
 * channel) is up; in command mode ({@code --help}/{@code --version}/{@code init}) the listener is left
 * unbound ({@code quarkus.http.host-enabled=false}, set in {@code ForvumApplication.main}) and this route
 * never serves. The bean carries <em>no</em> {@code @Startup}/{@code StartupEvent} observer and does no
 * eager DB or HTTP work — the Panache read runs only inside the handler, on an actual HTTP request — so
 * adding it does not touch the &lt; 200 ms command-mode/boot-smoke path nor the {@code ask}/{@code doctor}
 * one-shot turn path. It also does not touch {@code HttpClientFactorySelector} or the REST-client stack:
 * {@code @Route} comes from {@code quarkus-reactive-routes} over the already-present {@code vertx-http}
 * (brought by the Web channel's WebSockets Next), not from {@code quarkus-rest}.
 *
 * <p>{@code type = BLOCKING}: the handler does a blocking JDBC/Panache read, so it must run off the Vert.x
 * IO thread on a worker thread (CLAUDE.md §11 — blocking work never on the event loop). The method is
 * {@code @Transactional} so the read sees a consistent SQLite snapshot.
 */
@ApplicationScoped
public class CaprDashboardRoute {

    /**
     * Return every {@code capr_events} row (most recent first) as JSON. The {@code @Route} return value
     * is an {@code Object}, which {@code quarkus-reactive-routes} encodes to JSON with
     * {@code application/json} when no content-type is set.
     */
    @Route(path = "/q/dashboard/capr", methods = Route.HttpMethod.GET, type = Route.HandlerType.BLOCKING,
            produces = "application/json")
    @Transactional
    public List<CaprEventView> capr() {
        return CaprEventEntity.<CaprEventEntity>listAll().stream()
                .map(CaprEventView::from)
                .toList();
    }

    /**
     * The JSON view of one {@code capr_events} row. A record (reflection-free canonical constructor) in a
     * Quarkus-bearing module, so it carries {@code @RegisterForReflection} for the native image (CLAUDE.md
     * §5) — the endpoint serializes it.
     */
    @RegisterForReflection
    public record CaprEventView(
            Long id,
            String sessionId,
            String agentId,
            long turnId,
            int passed,
            String judgeModel,
            String rationale,
            long createdAt) {

        static CaprEventView from(CaprEventEntity e) {
            return new CaprEventView(
                    e.id, e.sessionId, e.agentId, e.turnId, e.passed, e.judgeModel, e.rationale, e.createdAt);
        }
    }
}
