package ai.forvum.engine.doctor;

/**
 * The weight of a {@link Finding}. An {@link #ERROR} is a configuration problem that will break a turn,
 * cron, or channel and makes the report unhealthy (a non-zero {@code forvum doctor} exit). A
 * {@link #WARNING} is advisory — the install still works — so it is surfaced but does not fail the exit.
 */
public enum Severity {
    ERROR,
    WARNING
}
