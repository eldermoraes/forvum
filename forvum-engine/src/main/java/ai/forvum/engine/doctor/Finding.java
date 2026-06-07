package ai.forvum.engine.doctor;

/**
 * One problem {@code forvum doctor} found in the {@code ~/.forvum/} layout. {@code location} is the
 * config path relative to {@code $FORVUM_HOME} (e.g. {@code agents/main.json}) so the operator knows
 * exactly which file to open; {@code problem} states what is wrong; {@code hint} is the actionable next
 * step. {@code hint} may be empty when {@code problem} is already self-explanatory.
 */
public record Finding(Severity severity, String location, String problem, String hint) {

    public Finding {
        if (severity == null) {
            throw new IllegalStateException("Finding severity must be non-null.");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Finding location must be non-blank.");
        }
        if (problem == null || problem.isBlank()) {
            throw new IllegalStateException("Finding problem must be non-blank.");
        }
        hint = hint == null ? "" : hint;
    }
}
