package ai.forvum.engine.doctor;

import ai.forvum.core.Persona;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.agent.AgentSpecReader;
import ai.forvum.engine.config.AgentReader;
import ai.forvum.engine.config.ConfigFileReader;
import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.CronReader;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.cron.CronSpec;
import ai.forvum.engine.cron.CronSpecReader;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code forvum doctor} validator (Phase 2, ULTRAPLAN section 7.2 item 9). Walks the user-editable
 * {@code ~/.forvum/} surface and reports problems with actionable hints, returning a {@link DoctorReport}.
 *
 * <p>It does not re-implement parsing: it reuses the M4 readers ({@link AgentReader}/{@link CronReader}/
 * etc., whose {@link ConfigLoader} reports a malformed file as an {@link UncheckedIOException}) and the
 * engine's own typed binders ({@link AgentSpecReader}/{@link CronSpecReader}, which throw
 * {@link IllegalStateException} with a file-naming message on a structural error) as validation oracles.
 * So a config that doctor passes is exactly a config the engine can load — there is no second, drifting
 * schema definition (the maintainer-signed-off "reuse readers, not a JSON-Schema library" strategy for
 * v0.5; formal JSON Schemas are a documented fast-follow).
 *
 * <p>Cross-references are checked beyond single-file parsing: a model ref ({@code primaryModel} on an
 * agent, {@code primary} on a cron) must name a provider that is actually on the classpath, and a cron's
 * {@code agentId} must name an agent that exists. {@code knownProviders} is the set of provider extension
 * ids the assembled app contributes (gathered from {@code Instance<ModelProvider>} by {@code DoctorCommand});
 * a unit test passes an explicit set.
 *
 * <p>Severity: a problem that would break a turn, cron, or load is an {@link Severity#ERROR} (the report is
 * unhealthy → non-zero exit); an advisory smell (no agents yet, an orphan persona file) is a
 * {@link Severity#WARNING} that is surfaced but does not fail the exit. Pure: it only reads files, so it is
 * native-clean and safe to run as a {@code CommandMode} one-shot (no DB/watcher boot).
 */
public final class ConfigDoctor {

    private final ForvumHome home;
    private final ConfigLoader loader;
    private final Set<String> knownProviders;

    public ConfigDoctor(ForvumHome home, ConfigLoader loader, Set<String> knownProviders) {
        this.home = home;
        this.loader = loader;
        this.knownProviders = Set.copyOf(knownProviders);
    }

    /** Validate the whole {@code $FORVUM_HOME} layout and return the findings in discovery order. */
    public DoctorReport check() {
        List<Finding> findings = new ArrayList<>();

        if (!Files.isDirectory(home.root())) {
            findings.add(new Finding(Severity.ERROR, home.root().toString(),
                    "Forvum home directory does not exist",
                    "Run 'forvum init' to scaffold ~/.forvum, or set FORVUM_HOME to the right path."));
            return new DoctorReport(findings);
        }

        List<String> agentIds = checkAgents(findings);
        checkCrons(findings, agentIds);
        checkRawJsonDirectory(findings, home.identities(), "identities");
        checkRawJsonDirectory(findings, home.channels(), "channels");
        checkRawJsonDirectory(findings, home.mcpServers(), "mcp-servers");
        checkRootConfig(findings);

        return new DoctorReport(findings);
    }

    /** Validate {@code agents/}; returns the agent ids that exist (the {@code .json} stems) for cron cross-ref. */
    private List<String> checkAgents(List<Finding> findings) {
        AgentReader reader = new AgentReader(loader, home);
        AgentSpecReader specReader = new AgentSpecReader();
        List<String> ids = reader.ids();

        if (ids.isEmpty()) {
            findings.add(new Finding(Severity.WARNING, "agents",
                    "No agents are defined",
                    "Add agents/<id>.json (and a matching <id>.md persona), or run 'forvum init'."));
        }

        for (String id : ids) {
            String location = "agents/" + id + ".json";
            JsonNode spec;
            try {
                spec = reader.spec(id).orElse(null);
            } catch (UncheckedIOException e) {
                findings.add(malformed(location, e));
                continue;
            }
            if (spec == null) {
                continue; // listed by .json but unreadable as a regular file — treat as absent
            }
            String persona = reader.persona(id).orElse("");
            try {
                Persona p = specReader.parse(new AgentId(id), persona, spec);
                checkProvider(findings, location, p.primaryModel().provider(),
                        "primaryModel '" + p.primaryModel() + "'");
            } catch (IllegalStateException e) {
                findings.add(new Finding(Severity.ERROR, location, e.getMessage(),
                        "Fix " + location + " (or the agents/" + id + ".md persona it names)."));
            }
        }

        // An orphan persona — a <id>.md with no <id>.json spec — never becomes an agent (the .json is the
        // source of truth), so flag it so the operator does not think it is wired up.
        for (String mdStem : loader.listIds(home.agents(), ".md")) {
            if (!ids.contains(mdStem)) {
                findings.add(new Finding(Severity.WARNING, "agents/" + mdStem + ".md",
                        "Persona file has no matching agents/" + mdStem + ".json spec",
                        "Add agents/" + mdStem + ".json to define the agent, or remove the orphan persona file."));
            }
        }

        return ids;
    }

    private void checkCrons(List<Finding> findings, List<String> agentIds) {
        CronReader reader = new CronReader(loader, home);
        CronSpecReader specReader = new CronSpecReader();

        for (String id : reader.ids()) {
            String location = "crons/" + id + ".json";
            JsonNode node;
            try {
                node = reader.read(id).orElse(null);
            } catch (UncheckedIOException e) {
                findings.add(malformed(location, e));
                continue;
            }
            if (node == null) {
                continue;
            }
            try {
                CronSpec cron = specReader.parse(id, node);
                checkProvider(findings, location, cron.primaryModel().provider(),
                        "primary '" + cron.primaryModel() + "'");
                if (!agentIds.contains(cron.agentId().value())) {
                    findings.add(new Finding(Severity.ERROR, location,
                            "Cron references unknown agent '" + cron.agentId().value() + "'",
                            "Define agents/" + cron.agentId().value() + ".json, or fix the agentId in " + location + "."));
                }
            } catch (IllegalStateException e) {
                findings.add(new Finding(Severity.ERROR, location, e.getMessage(),
                        "Fix " + location + "."));
            }
        }
    }

    /**
     * Parse-check every {@code <id>.json} in a raw JSON subfolder (identities/channels/mcp-servers). The
     * SAME {@code dir} (a {@link ForvumHome} path) is the single source for both listing and parsing, so a
     * subfolder name can never be listed from one place and parsed from another; {@code subfolder} is only
     * the display label on a finding.
     */
    private void checkRawJsonDirectory(List<Finding> findings, Path dir, String subfolder) {
        for (String id : loader.listIds(dir, ".json")) {
            String location = subfolder + "/" + id + ".json";
            try {
                loader.readJson(dir.resolve(id + ".json"));
            } catch (UncheckedIOException e) {
                findings.add(malformed(location, e));
            }
        }
    }

    private void checkRootConfig(List<Finding> findings) {
        try {
            new ConfigFileReader(loader, home).read();
        } catch (UncheckedIOException e) {
            findings.add(malformed("config.json", e));
        }
    }

    /** Add an ERROR when {@code provider} (the provider half of a model ref) is not on the classpath. */
    private void checkProvider(List<Finding> findings, String location, String provider, String refLabel) {
        if (!knownProviders.contains(provider)) {
            findings.add(new Finding(Severity.ERROR, location,
                    "Unknown model provider '" + provider + "' in " + refLabel,
                    "Known providers: " + new TreeSet<>(knownProviders)
                  + ". Install the matching provider, or fix the model ref in " + location + "."));
        }
    }

    private static Finding malformed(String location, UncheckedIOException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String detail = cause.getMessage() == null ? "" : ": " + firstLine(cause.getMessage());
        return new Finding(Severity.ERROR, location, "File is not valid JSON" + detail,
                "Fix the JSON syntax in " + location + ".");
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
