package ai.forvum.app;

import ai.forvum.engine.runtime.CommandMode;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

import jakarta.inject.Inject;

import picocli.CommandLine;

/**
 * Entry point for the Forvum native binary (M20). Parses the process arguments with picocli and runs the
 * matched command: {@code --help}/{@code --version} print and exit; {@code init} scaffolds {@code ~/.forvum};
 * with no subcommand the {@link RootCommand} default runs the M15/M16 channel dispatch (banner, then an
 * interactive TUI or a long-lived server channel, else a clean command-mode exit). The {@code IFactory}
 * is the CDI-aware picocli factory provided by quarkus-picocli, so the command beans get their injections.
 *
 * <p>Cold-start (the &lt;200 ms gate): {@link #main} detects a one-shot command
 * ({@code --help}/{@code --version}/{@code init}) from the raw args BEFORE Quarkus boots and (a) leaves the
 * bundled Web channel's {@code vertx-http} listener unbound ({@code quarkus.http.host-enabled=false}), and
 * (b) lets the startup observers skip Flyway migration, the config {@code WatchService}, and cron scheduling
 * ({@code CommandMode}). So a one-shot pays neither the HTTP bind nor the DB/IO — native {@code forvum --help}
 * measures ~45 ms and needs no free port.
 */
@QuarkusMain
public class ForvumApplication implements QuarkusApplication {

    /**
     * Leaves HTTP unbound for a one-shot command so it needs no free port and skips the bundled Web
     * channel's {@code vertx-http} bind. {@code quarkus.http.host-enabled} is read at RUNTIME_INIT (when the
     * listener would bind), which precedes {@code QuarkusApplication.run()} — so the decision must be a
     * system property set here, before {@link Quarkus#run}, not a {@code StartupEvent} observer. (This does
     * not address the separate macOS {@code getLocalHost()} startup stall, which the CI workflow fixes by
     * making the runner's hostname resolvable.)
     */
    public static void main(String[] args) {
        if (CommandMode.isOneShotCommand(args)) {
            System.setProperty("quarkus.http.host-enabled", "false");
        }
        // P2-15 (#40): OTLP export is opt-in via OTEL_EXPORTER_OTLP_ENDPOINT. With no endpoint configured,
        // disable the OTel SDK at runtime so a default run pays zero telemetry overhead and skips the OTel
        // SDK's resource detection (one getLocalHost() caller; the separate Vert.x address-resolver
        // getLocalHost on hostname-less CI hosts is handled in the workflow, the M20 lesson). Same M20
        // lever as host-enabled: a system property set
        // before Quarkus boots (sdk.disabled is read at RUNTIME_INIT). Set-only-if-absent leaves an
        // operator -Dquarkus.otel.sdk.disabled override (and a configured endpoint turns the SDK on).
        String otlpEndpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if ((otlpEndpoint == null || otlpEndpoint.isBlank())
                && System.getProperty("quarkus.otel.sdk.disabled") == null) {
            System.setProperty("quarkus.otel.sdk.disabled", "true");
        }
        // forvum-tools-web (#PR-6): the web.fetch HTTP IP-pin (B2) sets a "Host" header on the JDK
        // HttpClient request, which is a RESTRICTED header unless jdk.httpclient.allowRestrictedHeaders
        // names it. That property is read ONCE, when java.net.http's internal Utils class first
        // initializes — which happens on the first JDK-HttpClient build (an Ollama/Gemini/Copilot LLM
        // turn uses java.net.http), typically BEFORE any web.fetch. So it must be set here, before
        // Quarkus boots and before any client is built — not in JdkHttpFetcher's (lazy) static block,
        // which loses that race on the assembled app. Set-only-if-absent leaves an operator override.
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        }
        Quarkus.run(ForvumApplication.class, args);
    }

    @Inject
    CommandLine.IFactory factory;

    @Inject
    RootCommand root;

    @Override
    public int run(String... args) {
        return new CommandLine(root, factory).execute(args);
    }
}
