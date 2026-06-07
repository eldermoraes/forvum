package ai.forvum.engine.doctor;

import java.util.List;

/**
 * The result of a {@code forvum doctor} run: the {@link Finding}s in the order they were discovered. The
 * report is {@link #healthy()} when it carries no {@link Severity#ERROR} — warnings are advisory and do
 * not fail the exit. The findings list is defensively copied and immutable.
 */
public record DoctorReport(List<Finding> findings) {

    public DoctorReport {
        findings = List.copyOf(findings);
    }

    /** True when there is no {@link Severity#ERROR} finding (warnings are allowed). */
    public boolean healthy() {
        return findings.stream().noneMatch(f -> f.severity() == Severity.ERROR);
    }

    /** The {@link Severity#ERROR} findings, in discovery order. */
    public List<Finding> errors() {
        return findings.stream().filter(f -> f.severity() == Severity.ERROR).toList();
    }

    /** The {@link Severity#WARNING} findings, in discovery order. */
    public List<Finding> warnings() {
        return findings.stream().filter(f -> f.severity() == Severity.WARNING).toList();
    }
}
