package ai.forvum.engine.routing;

import ai.forvum.core.ModelRef;
import ai.forvum.core.Persona;
import ai.forvum.engine.model.FailureClassifier;
import ai.forvum.engine.model.FallbackChatModel;
import ai.forvum.engine.model.FallbackLink;
import ai.forvum.engine.model.ProviderCallRecorder;
import ai.forvum.sdk.ModelProvider;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Resolves an agent's {@link Persona#primaryModel()} to a callable {@link ChatModel} (ULTRAPLAN
 * section 4.3.5.1). Extension-agnostic: it discovers {@link ModelProvider} plugins via CDI and picks
 * the one whose {@code extensionId()} matches the ref's provider half, never naming a concrete
 * provider. The resolved model is wrapped in the M8 {@link FallbackChatModel} so the call is failure-
 * classified and ledgered into {@code provider_calls}. This cycle the chain has a single link — real
 * multi-provider fallback arrives once M10 contributes a second provider.
 */
@ApplicationScoped
public class LlmSelector {

    @Inject
    Instance<ModelProvider> providers;

    @Inject
    FailureClassifier classifier;

    @Inject
    ProviderCallRecorder recorder;

    /** Resolve {@code persona}'s primary model to a fallback-wrapped {@link ChatModel} for the turn. */
    public ChatModel select(Persona persona, String sessionId) {
        return resolve(persona.primaryModel(), persona.id().value(), sessionId);
    }

    /**
     * Resolve an explicit {@link ModelRef} to a fallback-wrapped {@link ChatModel}, attributing the
     * ledger to {@code agentId}/{@code sessionId}. Used by the M19 cron path to run a turn with the
     * cron's own model rather than the agent's persona model.
     */
    public ChatModel resolve(ModelRef ref, String agentId, String sessionId) {
        ModelProvider provider = providerFor(ref);
        ChatModel resolved = provider.resolve(ref);
        FallbackLink link = new FallbackLink(ref, resolved, null);
        return new FallbackChatModel(List.of(link), sessionId, agentId, classifier, recorder, null);
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
