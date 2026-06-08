package ai.forvum.sdk;

import java.util.List;

import ai.forvum.core.MemoryHit;
import ai.forvum.core.MemoryPolicy;
import ai.forvum.core.MemoryQuery;

/**
 * The host SPI a memory-host plugin implements so the Context-Engineering <em>Select</em> pillar can
 * retrieve relevant memory (vector, graph, metadata, or hybrid) without coupling the agent to a
 * retrieval strategy (ULTRAPLAN section 2.2, Layer 1; section 4.3.6; CONTEXT-ENGINEERING Select pillar;
 * DR-5). The interface is sealed: a third party extends {@link AbstractMemoryProvider} (the one permitted
 * type), so the closed set stays enumerable while remaining open to external implementations.
 *
 * <h2>Discovery</h2>
 * A concrete implementation is a Layer-3 Forvum extension that depends ONLY on {@code forvum-sdk}. It
 * carries:
 * <ul>
 *   <li>{@link ForvumExtension} on the provider class, so the build-time plugin scanner records it and
 *       emits the native-image reflection hints (ULTRAPLAN section 6.3);</li>
 *   <li>a {@code META-INF/forvum/plugin.json} manifest declaring a provider of
 *       {@code "type": "memory"} pointing at the implementing class;</li>
 *   <li>{@code @ApplicationScoped} (or another bean-defining scope) plus a {@code META-INF/beans.xml}
 *       so ArC discovers the bean.</li>
 * </ul>
 * On the native binary, plugins are loaded from the compile classpath at build time, not from
 * {@code ~/.forvum/plugins/} (the drop-in path is a documented JVM-fast-jar-only property). A bundled
 * provider stays INERT until an operator configures it and a {@code MemoryPolicy} selects its strategy.
 *
 * <h2>The retrieve contract</h2>
 * {@link #retrieve(MemoryQuery, MemoryPolicy)} maps a {@link MemoryQuery} (agent + session + query text)
 * to an ordered {@link List} of {@link MemoryHit}, honoring the {@link MemoryPolicy}:
 * <ul>
 *   <li>{@link MemoryPolicy#strategy()} selects the retrieval mechanism (VECTOR / GRAPH / METADATA /
 *       HYBRID / NONE); a provider that does not support a strategy SHOULD degrade gracefully (e.g. fall
 *       back to its closest supported mode) rather than throw. Strategy {@code NONE} MUST return an
 *       empty list (retrieval disabled).</li>
 *   <li>{@link MemoryPolicy#tiers()} restricts which memory tiers may be drawn from; a hit's
 *       {@link MemoryHit#tier()} MUST be a member of this set (with {@code NONE} the set may be empty).</li>
 *   <li>{@link MemoryPolicy#topK()} caps the number of returned hits; the provider returns AT MOST
 *       {@code topK} hits.</li>
 *   <li>{@link MemoryPolicy#minScore()} is the floor a hit's {@link MemoryHit#score()} (normalized to
 *       {@code [0, 1]}) must clear to be included.</li>
 * </ul>
 * Compression of an oversized hit ({@link MemoryPolicy#compressThresholdChars()}) is the host's concern,
 * not the provider's — the provider returns raw hits.
 *
 * <h2>Concurrency &amp; native expectations</h2>
 * {@code retrieve} is BLOCKING and is invoked on a virtual thread (CLAUDE.md section 3.8): an
 * implementation calls its backend with blocking IO (a blocking REST client, a blocking driver) and
 * returns a value directly — NEVER a reactive return type (Mutiny {@code Uni}/{@code Multi} is a
 * PR-reject). The module must be native-clean: DTOs are records carrying {@code @RegisterForReflection},
 * no runtime reflection, no {@code sun.misc.Unsafe}, no runtime bytecode generation, no heavy
 * embedding-model dependency on the build classpath.
 */
public sealed interface MemoryProvider permits AbstractMemoryProvider {

    /** Stable id of the contributing extension, matching its {@code META-INF/forvum/plugin.json}. */
    String extensionId();

    /**
     * Retrieve the memory most relevant to {@code query}, honoring {@code policy}.
     *
     * <p>Blocking; invoked on a virtual thread. Returns an ordered (most-relevant-first), at-most-{@code
     * policy.topK()} list whose every {@link MemoryHit} scores at least {@code policy.minScore()} and
     * comes from a tier in {@code policy.tiers()}. Returns an empty list (never {@code null}) when there
     * is nothing to return or when {@code policy.strategy()} is {@code NONE}.
     *
     * @param query  the agent/session-scoped retrieval request (non-null)
     * @param policy the retrieval policy driving strategy, tiers, topK, and minScore (non-null)
     * @return the retrieved hits, possibly empty, never {@code null}
     */
    List<MemoryHit> retrieve(MemoryQuery query, MemoryPolicy policy);
}
