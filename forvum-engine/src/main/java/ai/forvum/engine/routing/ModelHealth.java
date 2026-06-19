package ai.forvum.engine.routing;

import ai.forvum.core.ModelRef;

/**
 * A rolling-window health snapshot for one model: the number of recent provider calls observed
 * ({@code attempts}), how many of them failed ({@code failures}), and the resulting pass rate. Derived
 * by {@link ModelHealthReader} from the {@code provider_calls} ledger — the genuinely per-model signal
 * ({@code error} is null on success) — and consumed by {@link CaprRouter} to down-rank a sagging model.
 *
 * <p>Engine-local, never JSON-serialized, so it carries no {@code @RegisterForReflection}.
 *
 * @param ref      the model this snapshot describes
 * @param attempts recent provider calls in the window for {@code ref} ({@code >= 0})
 * @param failures of those, how many recorded an error ({@code 0 <= failures <= attempts})
 */
public record ModelHealth(ModelRef ref, int attempts, int failures) {

    public ModelHealth {
        if (ref == null) {
            throw new IllegalStateException("ModelHealth ref must be non-null.");
        }
        if (attempts < 0 || failures < 0 || failures > attempts) {
            throw new IllegalStateException(
                "ModelHealth requires 0 <= failures <= attempts. Got attempts=" + attempts
              + ", failures=" + failures + " for " + ref + ".");
        }
    }

    /**
     * Recent pass rate in {@code [0, 1]} — {@code (attempts - failures) / attempts}. With no observed
     * attempts the rate is {@code 1.0} (a neutral prior: a model with no history is treated as healthy so
     * it is never starved of its declared position).
     */
    public double passRate() {
        return attempts == 0 ? 1.0 : (double) (attempts - failures) / attempts;
    }
}
