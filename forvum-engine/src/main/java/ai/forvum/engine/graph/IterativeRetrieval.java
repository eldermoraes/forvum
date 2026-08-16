package ai.forvum.engine.graph;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;
import ai.forvum.core.RetrievalStrategy;
import ai.forvum.core.budget.BudgetExhaustedException;
import ai.forvum.engine.routing.MemorySelector;
import ai.forvum.engine.routing.RetrievedMemory;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The agentic-RAG upgrade of the Select pillar (#196): a BOUNDED retrieve → evaluate-sufficiency →
 * decompose/re-query loop, gated by {@link RetrievalStrategy#ITERATIVE} (OPT-IN — every other strategy
 * keeps the single-shot {@code SupervisorGraph.retrieveAndFrame} path unchanged).
 *
 * <p>Realized as a bounded, blocking memory sub-agent on the turn's virtual thread, mirroring the
 * worker/reduce Isolate topology (§5.5): the evaluator runs in its OWN throwaway conversation — its
 * transcript never enters the supervisor window — and only the accumulated hit set crosses back, which
 * the caller then compresses ({@code compressHits}, #176 {@code BoundedCompressor} under
 * {@code MemoryPolicy.compressThresholdChars}) and frames as {@code <retrieved_memory>} DATA
 * ({@link RetrievedMemory}, DR-6a §9 — never the instruction region). The hits the evaluator itself
 * inspects are framed through the SAME data posture (close-tag-neutralized), so the iterative path never
 * splices untrusted memory into an instruction region either.
 *
 * <p>Everything is bounded: at most {@link #MAX_ITERATIONS} retrieval iterations, at most
 * {@link #MAX_SUBQUERIES_PER_ITERATION} sub-queries per iteration, and at most
 * {@code policy.topK() * MAX_ITERATIONS} accumulated hits. An evaluator failure or an unparseable
 * verdict STOPS the loop with whatever was already retrieved (graceful degradation — retrieval must
 * never fail the turn), except a {@link BudgetExhaustedException} anywhere in the cause chain, which
 * re-throws AS ITSELF to abort the turn (#169 — the evaluator runs on the turn's cost-decorated model).
 *
 * <p>Each underlying provider call runs the {@link RetrievalStrategy#HYBRID} blend (ITERATIVE is an
 * orchestration strategy layered ABOVE the provider SPI, not a new provider mechanism — DR-5 keeps
 * {@code MemoryProvider.retrieve} single-shot). Stateless and reflection-free (no JSON: the verdict is a
 * plain-text line protocol) — native-clean.
 */
final class IterativeRetrieval {

    private static final Logger LOG = Logger.getLogger(IterativeRetrieval.class);

    /** Max retrieval iterations (the initial retrieve plus up to two re-query rounds). */
    static final int MAX_ITERATIONS = 3;

    /** Max refined sub-queries honored per evaluator verdict. */
    static final int MAX_SUBQUERIES_PER_ITERATION = 3;

    /** The verdict token that stops the loop (anything unparseable degrades to this). */
    static final String SUFFICIENT = "SUFFICIENT";

    /** The verdict token that requests another retrieval round with refined sub-queries. */
    static final String INSUFFICIENT = "INSUFFICIENT";

    private static final SystemMessage EVALUATOR_INSTRUCTION = SystemMessage.from(
            "You are a retrieval-sufficiency evaluator. Judge whether the retrieved memory below is "
          + "sufficient context to answer the user's question. Reply with exactly " + SUFFICIENT + " when "
          + "it is, or " + INSUFFICIENT + " followed by up to " + MAX_SUBQUERIES_PER_ITERATION
          + " refined search queries, one per line, when more retrieval would help.");

    private IterativeRetrieval() {
    }

    /** The evaluator's parsed verdict: stop ({@code sufficient}) or re-query with {@code subQueries}. */
    record Verdict(boolean sufficient, List<String> subQueries) {

        static final Verdict STOP = new Verdict(true, List.of());

        Verdict {
            subQueries = List.copyOf(subQueries);
        }
    }

    /**
     * Run the bounded iterative loop and return the accumulated, de-duplicated hits (insertion order —
     * first-retrieved first), never {@code null}. {@code evaluator} is the turn's resolved model (already
     * fallback/cost-wrapped); {@code query} carries the user's question as its text.
     */
    static List<MemoryHit> retrieve(ChatModel evaluator, MemorySelector selector, MemoryQuery query,
            MemoryPolicy policy) {
        // ITERATIVE is orchestration-only; each provider call runs the default HYBRID blend.
        MemoryPolicy providerPolicy = new MemoryPolicy(RetrievalStrategy.HYBRID, policy.tiers(),
                policy.topK(), policy.minScore(), policy.compressThresholdChars());
        int maxHits = policy.topK() * MAX_ITERATIONS;

        Map<String, MemoryHit> accumulated = new LinkedHashMap<>();
        Set<String> issuedQueries = new LinkedHashSet<>();
        List<String> queries = List.of(query.text());

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            for (String text : queries) {
                if (text == null || text.isBlank() || !issuedQueries.add(text.strip())
                        || accumulated.size() >= maxHits) {
                    continue;
                }
                List<MemoryHit> hits = selector.retrieve(
                        new MemoryQuery(query.agentId(), query.sessionId(), text), providerPolicy);
                for (MemoryHit hit : hits) {
                    if (accumulated.size() >= maxHits) {
                        break;
                    }
                    accumulated.putIfAbsent(hit.tier() + "|" + hit.source() + "|" + hit.content(), hit);
                }
            }
            if (iteration == MAX_ITERATIONS - 1) {
                break; // the cap binds: no evaluator call whose verdict could never be honored
            }
            Verdict verdict = evaluate(evaluator, query.text(), List.copyOf(accumulated.values()));
            if (verdict.sufficient() || verdict.subQueries().isEmpty()) {
                break;
            }
            queries = verdict.subQueries();
        }
        return List.copyOf(accumulated.values());
    }

    /**
     * One evaluator pass in an isolated throwaway conversation (the Isolate boundary — this transcript
     * never crosses back). Hits are shown DATA-framed via {@link RetrievedMemory#frame}. Any evaluator
     * failure degrades to {@link Verdict#STOP}, except budget exhaustion, which re-throws (#169).
     */
    private static Verdict evaluate(ChatModel evaluator, String question, List<MemoryHit> hits) {
        String framed = RetrievedMemory.frame(hits);
        String prompt = "Question: " + question + "\n\n"
                + (framed == null ? "No memory has been retrieved yet." : framed);
        String reply;
        try {
            reply = evaluator.chat(ChatRequest.builder()
                    .messages(List.of(EVALUATOR_INSTRUCTION, UserMessage.from(prompt)))
                    .build()).aiMessage().text();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof BudgetExhaustedException budget) {
                    throw budget; // #169: the hard stop aborts the turn AS ITSELF, never degrades
                }
            }
            LOG.warnf(e, "Iterative-retrieval evaluator failed; stopping with %d accumulated hit(s).",
                    hits.size());
            return Verdict.STOP;
        }
        return parseVerdict(reply);
    }

    /**
     * Parse the evaluator's plain-text verdict. {@code INSUFFICIENT} (case-insensitive, first token)
     * yields the refined queries — the remainder of the first line plus each following non-blank line,
     * stripped of leading bullet/number decoration, capped at {@link #MAX_SUBQUERIES_PER_ITERATION}.
     * Anything else — {@code SUFFICIENT}, blank, malformed — stops the loop (graceful degradation).
     */
    static Verdict parseVerdict(String reply) {
        if (reply == null || reply.isBlank()) {
            return Verdict.STOP;
        }
        String[] lines = reply.strip().split("\\R");
        String first = lines[0].strip();
        if (!first.toUpperCase(Locale.ROOT).startsWith(INSUFFICIENT)) {
            return Verdict.STOP;
        }
        List<String> queries = new ArrayList<>();
        String inline = stripDecoration(first.substring(INSUFFICIENT.length()));
        if (!inline.isBlank()) {
            queries.add(inline);
        }
        for (int i = 1; i < lines.length && queries.size() < MAX_SUBQUERIES_PER_ITERATION; i++) {
            String query = stripDecoration(lines[i]);
            if (!query.isBlank()) {
                queries.add(query);
            }
        }
        return queries.isEmpty() ? Verdict.STOP : new Verdict(false, queries);
    }

    /** Strip leading punctuation/bullet/number decoration (":", ",", "-", "*", "1.", "2)") from a query line. */
    private static String stripDecoration(String line) {
        return line.strip().replaceFirst("^(?:[-*:,]|\\d+[.)])\\s*", "").strip();
    }
}
