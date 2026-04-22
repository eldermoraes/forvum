// Module: forvum-core
// Package: ai.forvum.core

package ai.forvum.core;

public record ModelRef(String provider, String model) {

    public ModelRef {
        if (provider == null || provider.isBlank()
            || !provider.strip().equals(provider)) {
            throw new IllegalStateException(
                "ModelRef provider must be a non-blank token without "
              + "leading/trailing whitespace. Got: '" + provider + "'. "
              + "Check config file formatting.");
        }
        if (model == null || model.isBlank()
            || !model.strip().equals(model)) {
            throw new IllegalStateException(
                "ModelRef model must be a non-blank token without "
              + "leading/trailing whitespace. Got: '" + model + "'. "
              + "Check config file formatting.");
        }
        provider = provider.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Parse a {@code provider:model} spec string.
     *
     * <p>Splits on the FIRST colon only, so provider-specific model strings
     * that themselves contain colons (e.g., Ollama tags like {@code qwen3:1.7b})
     * survive the round-trip. Both sides must be non-blank and free of
     * leading/trailing whitespace; provider is case-folded to lowercase.
     *
     * <p>Provider-only shorthand (e.g., {@code "ollama"} without a model) is
     * NOT supported — see the convention note below.
     *
     * @throws IllegalStateException if {@code spec} is null, blank, has no
     *         colon, or either side fails the canonical constructor checks.
     */
    public static ModelRef parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalStateException(
                "ModelRef spec must be non-blank. Check config file formatting.");
        }
        int colon = spec.indexOf(':');
        if (colon < 0) {
            throw new IllegalStateException(
                "ModelRef spec must use 'provider:model' form; got: '" + spec
              + "'. Provider-only shorthand is not supported — configuration "
              + "files must specify both halves explicitly.");
        }
        String p = spec.substring(0, colon);
        String m = spec.substring(colon + 1);
        return new ModelRef(p, m);
    }

    @Override
    public String toString() {
        return provider + ":" + model;
    }
}
