package ai.forvum.engine.replay;

import ai.forvum.core.ModelRef;

/**
 * The outcome of a {@code forvum replay --model} substitution rerun (#57): whether the original session
 * existed, and — when it did — the NEW session the rerun wrote (a comparable, independently replayable
 * trace), how many user turns completed under the substituted model, and a {@code failureMessage} naming
 * the turn that failed (e.g. an unconfigured substitute provider) when the rerun did not finish. A
 * non-null {@code failureMessage} means the rerun is PARTIAL — turns up to the failure are persisted in
 * {@code newSessionId}. In-process only (built and printed by the command), so no
 * {@code @RegisterForReflection}.
 */
public record SubstitutionResult(boolean found, String originalSessionId, String newSessionId,
        int turnCount, ModelRef substituteModel, String failureMessage) {

    /** True when the original session existed but the rerun did not complete every turn. */
    public boolean failed() {
        return found && failureMessage != null;
    }

    public static SubstitutionResult notFound(String originalSessionId) {
        return new SubstitutionResult(false, originalSessionId, null, 0, null, null);
    }

    public static SubstitutionResult replayed(String originalSessionId, String newSessionId,
            int turnCount, ModelRef substituteModel) {
        return new SubstitutionResult(true, originalSessionId, newSessionId, turnCount, substituteModel, null);
    }

    /** A rerun that failed mid-way: {@code turnCount} turns completed, {@code failureMessage} explains the stop. */
    public static SubstitutionResult partial(String originalSessionId, String newSessionId,
            int turnCount, ModelRef substituteModel, String failureMessage) {
        return new SubstitutionResult(true, originalSessionId, newSessionId, turnCount, substituteModel,
                failureMessage);
    }
}
