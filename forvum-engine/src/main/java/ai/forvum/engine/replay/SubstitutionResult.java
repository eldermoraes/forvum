package ai.forvum.engine.replay;

import ai.forvum.core.ModelRef;

/**
 * The outcome of a {@code forvum replay --model} substitution rerun (#57): whether the original session
 * existed, and — when it did — the NEW session the rerun wrote (a comparable, independently replayable
 * trace) plus how many user turns were re-run under the substituted model. In-process only (built and
 * printed by the command), so no {@code @RegisterForReflection}.
 */
public record SubstitutionResult(boolean found, String originalSessionId, String newSessionId,
        int turnCount, ModelRef substituteModel) {

    public static SubstitutionResult notFound(String originalSessionId) {
        return new SubstitutionResult(false, originalSessionId, null, 0, null);
    }

    public static SubstitutionResult replayed(String originalSessionId, String newSessionId,
            int turnCount, ModelRef substituteModel) {
        return new SubstitutionResult(true, originalSessionId, newSessionId, turnCount, substituteModel);
    }
}
