package ai.forvum.engine.routing;

import ai.forvum.core.FallbackChain;
import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.engine.model.FailureClassifier;
import ai.forvum.engine.model.FallbackChatModel;
import ai.forvum.engine.model.FallbackLink;
import ai.forvum.engine.model.ProviderCallRecorder;
import ai.forvum.sdk.ModelProvider;

import dev.langchain4j.model.chat.ChatModel;

import io.opentelemetry.api.trace.Tracer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves an agent's declared {@link FallbackChain} (its {@link Persona#primaryModel()} +
 * {@link Persona#fallbackModels()}) to a callable {@link ChatModel} (ULTRAPLAN section 4.3.5.1).
 * Extension-agnostic: it discovers {@link ModelProvider} plugins via CDI and picks the one whose
 * {@code extensionId()} matches each ref's provider half, never naming a concrete provider. The resolved
 * links are wrapped in the M8 {@link FallbackChatModel} so each call is failure-classified and ledgered
 * into {@code provider_calls}.
 *
 * <p><b>CAPR-driven adaptive routing (P3-4 #52, Context-Engineering Select pillar).</b> Before resolving
 * the persona chain, {@link CaprRouter} reorders {@link FallbackChain#links()} by each model's rolling
 * recent pass rate ({@link ModelHealthReader} over {@code provider_calls}), so a model whose recent calls
 * sag sinks below a healthier sibling — neutrally by default (no data / a single link / weight 0 returns
 * the declared order unchanged). The single-ref {@link #resolve(ModelRef, String, String)} path (cron,
 * replay, compaction, worker) is always single-link, so reorder is a no-op there.
 */
@ApplicationScoped
public class LlmSelector {

    @Inject
    Instance<ModelProvider> providers;

    @Inject
    FailureClassifier classifier;

    @Inject
    ProviderCallRecorder recorder;

    @Inject
    Tracer tracer;

    @Inject
    CaprRouter caprRouter;

    @Inject
    ModelHealthReader healthReader;

    /**
     * Resolve {@code persona}'s declared chain (primary + fallbacks) to a fallback-wrapped
     * {@link ChatModel} for the turn, with the chain order adapted by CAPR-driven routing.
     */
    public ChatModel select(Persona persona, String sessionId) {
        FallbackChain chain = new FallbackChain(persona.primaryModel(), persona.fallbackModels());
        List<ModelRef> ordered = route(persona.id().value(), chain.links());
        List<FallbackLink> resolved = new ArrayList<>(ordered.size());
        for (ModelRef ref : ordered) {
            resolved.add(new FallbackLink(ref, providerFor(ref).resolve(ref), null));
        }
        return new FallbackChatModel(resolved, sessionId, persona.id().value(), classifier, recorder,
                null, tracer);
    }

    /**
     * Resolve an explicit single {@link ModelRef} to a fallback-wrapped {@link ChatModel}, attributing
     * the ledger to {@code agentId}/{@code sessionId}. Used by the M19 cron path (and replay/compaction)
     * to run a turn with a specific model rather than the agent's persona chain. Always a single link, so
     * no routing applies.
     */
    public ChatModel resolve(ModelRef ref, String agentId, String sessionId) {
        ModelProvider provider = providerFor(ref);
        ChatModel resolved = provider.resolve(ref);
        FallbackLink link = new FallbackLink(ref, resolved, null);
        return new FallbackChatModel(List.of(link), sessionId, agentId, classifier, recorder, null, tracer);
    }

    /**
     * Adapt the declared link order for {@code agentId} by CAPR-driven routing. Degrades to the declared
     * order on any health-read failure — a routing problem must never fail the turn.
     */
    private List<ModelRef> route(String agentId, List<ModelRef> links) {
        if (links.size() < 2) {
            return links; // single-link chain: nothing to reorder
        }
        try {
            Map<ModelRef, ModelHealth> health = healthReader.health(agentId, links);
            return caprRouter.reorder(links, health);
        } catch (RuntimeException e) {
            return links;
        }
    }

    private ModelProvider providerFor(ModelRef ref) {
        for (ModelProvider provider : providers) {
            if (provider.extensionId().equals(ref.provider())) {
                return provider;
            }
        }
        throw new IllegalStateException(
                "No model provider for '" + ref.provider() + "' (from " + ref
              + "). Is the matching provider plugin on the classpath?");
    }
}
