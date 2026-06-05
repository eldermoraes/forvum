package ai.forvum.app;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Selects the LangChain4j HTTP client factory once, at startup, for the whole assembled application.
 *
 * <p>{@code forvum-app} bundles every first-party provider, so its classpath carries more than one
 * {@code dev.langchain4j.http.client.HttpClientBuilderFactory} service at once:
 * {@code JaxRsHttpClientBuilderFactory} (via the ollama/gemini extensions) and
 * {@code JdkHttpClientBuilderFactory} (a transitive of several langchain4j model libs, e.g. anthropic).
 * A model built <em>programmatically</em> whose builder is NOT swapped by a Quarkiverse builder-factory
 * — Gemini and Ollama, whose extensions ship no such swap (OpenAI/Anthropic ARE swapped to the Quarkus
 * REST client and never reach the loader) — routes through {@code HttpClientBuilderLoader}, which throws
 * {@code IllegalStateException("Conflict: multiple HTTP clients ...")} at {@code build()} time unless the
 * {@code langchain4j.http.clientBuilderFactory} system property names the factory to use.
 *
 * <p>Pinning it once here — at the assembly layer that actually owns the conflicting classpath — fixes
 * every current and future programmatically-built model at the root, rather than each provider repeating
 * a per-builder pin (a discipline that already missed Ollama). We select the Quarkus REST client
 * ({@code JaxRsHttpClientBuilderFactory}): native-tested, the same stack the OpenAI/Anthropic builders are
 * swapped to, and what the ai-gemini extension's own recorder uses.
 *
 * <p>The loader reads {@link System#getProperty} (NOT MP Config) lazily, at the first model {@code build()}
 * — which happens on the first turn, well after boot — so a {@link StartupEvent} observer is early enough,
 * and {@code System.setProperty} at runtime works identically in the JVM and the native image. The value is
 * set only when absent, so an operator may override it with {@code -Dlangchain4j.http.clientBuilderFactory=...}.
 * {@code ProviderResolveInAppClasspathTest} resolves every provider in the full classpath and fails fast if
 * this factory name ever drifts from the real type or a new un-swapped provider is added.
 */
@ApplicationScoped
public class HttpClientFactorySelector {

    /** System property read by {@code dev.langchain4j.http.client.HttpClientBuilderLoader}. */
    static final String PROPERTY = "langchain4j.http.clientBuilderFactory";

    /**
     * Fully-qualified name of the Quarkus REST client factory. A string literal (not a class reference)
     * so {@code forvum-app} need not compile against the Quarkiverse impl jar; the loader compares it to
     * {@code factory.getClass().getName()}, and {@code ProviderResolveInAppClasspathTest} catches any drift.
     */
    static final String JAXRS_FACTORY = "io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilderFactory";

    void onStart(@Observes StartupEvent event) {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, JAXRS_FACTORY);
        }
    }
}
