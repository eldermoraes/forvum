package ai.forvum.core;

import java.util.ArrayList;
import java.util.List;

/**
 * One agent's (or one cron's) ordered model preference, declared at config time (ULTRAPLAN §4.3.5.3,
 * DR-4c). A flat, pure-data Layer-0 record co-located with {@link ModelRef}: {@code primary} is the
 * model the turn fronts (the operator's preference and the router's order tiebreak) and {@code fallbacks}
 * are tried in declaration order on a provider-level failure ({@code []} = no fallback).
 *
 * <p>The <em>runtime</em> form stays the engine-local {@code FallbackLink} list: at materialization the
 * engine resolves each {@link ModelRef} against its {@code ModelProvider} bean and hands the M8
 * decorators the resolved links. This record carries no budget ({@code Persona}/the cron spec already
 * carry their own — DR-4c [DP-2]) and no per-link cost dimensions (DR-4c [DP-9], deferred). It is
 * composed engine-side and never JSON-serialized (§4.3.8, the {@code GraphTurnRequest} precedent), so it
 * needs no engine {@code CoreReflectionRegistration} holder entry.
 *
 * <p><b>Adaptive routing (P3-4 #52).</b> {@link #links()} is the entire contract surface the CAPR-driven
 * router needs: it may <em>reorder</em> or <em>drop</em> declared links but must keep at least one and may
 * never invent a model outside this chain — declared order is the deterministic tiebreak (DR-4c [DP-8]).
 *
 * <p><b>Validation (DR-4c [DP-3]).</b> {@code primary} is non-null; {@code fallbacks} is non-null
 * (defensively copied immutable, no null elements); duplicates are rejected (the primary repeated in
 * {@code fallbacks}, or a ref repeated within {@code fallbacks}, by exact {@link ModelRef} equality) — an
 * immediate same-request re-attempt of an identical link cannot succeed where the attempt just failed, so
 * a repeat is a config mistake in {@code agents/<id>.json}/{@code crons/<id>.json}.
 */
public record FallbackChain(ModelRef primary, List<ModelRef> fallbacks) {

    public FallbackChain {
        if (primary == null) {
            throw new IllegalStateException(
                "FallbackChain primary must be non-null. The 'primaryModel' field in "
              + "agents/<id>.json (or the cron's model) must parse via ModelRef.");
        }
        if (fallbacks == null) {
            throw new IllegalStateException(
                "FallbackChain fallbacks must be non-null (use an empty list, or FallbackChain.single(...), "
              + "for no fallback). Check the 'fallbackModels' array in agents/<id>.json.");
        }
        List<ModelRef> seen = new ArrayList<>(fallbacks.size() + 1);
        seen.add(primary);
        for (ModelRef ref : fallbacks) {
            if (ref == null) {
                throw new IllegalStateException(
                    "FallbackChain fallbacks must not contain null entries. Check the 'fallbackModels' "
                  + "array in agents/<id>.json for a stray null element.");
            }
            if (seen.contains(ref)) {
                throw new IllegalStateException(
                    "FallbackChain has a duplicate model '" + ref + "'. The primary and each fallback "
                  + "must be distinct — a same-request re-attempt of an identical link cannot succeed "
                  + "where it just failed. Check the 'primaryModel'/'fallbackModels' in agents/<id>.json.");
            }
            seen.add(ref);
        }
        fallbacks = List.copyOf(fallbacks);
    }

    /** {@code primary} then {@code fallbacks} in traversal order — the #52 router's authority set. */
    public List<ModelRef> links() {
        List<ModelRef> links = new ArrayList<>(fallbacks.size() + 1);
        links.add(primary);
        links.addAll(fallbacks);
        return List.copyOf(links);
    }

    /** The no-fallback chain (the M8/{@code LlmSelector} single-link case). */
    public static FallbackChain single(ModelRef primary) {
        return new FallbackChain(primary, List.of());
    }
}
