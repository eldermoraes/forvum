package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property-based invariants for {@link ModelRef#parse} (mandatory per ULTRAPLAN section 10). */
class ModelRefPropertyTest {

    @Provide
    Arbitrary<String> providerToken() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }

    @Provide
    Arbitrary<String> modelToken() {
        // Non-blank, no edge whitespace; may carry internal colons (e.g. Ollama tag "qwen3:1.7b").
        Arbitrary<String> segment = Arbitraries.strings()
            .withCharRange('a', 'z').withCharRange('A', 'Z').withCharRange('0', '9')
            .ofMinLength(1).ofMaxLength(8);
        return segment.list().ofMinSize(1).ofMaxSize(3).map(parts -> String.join(":", parts));
    }

    @Property
    void parseRoundTripsThroughToString(@ForAll("providerToken") String provider,
                                        @ForAll("modelToken") String model) {
        ModelRef ref = new ModelRef(provider, model);
        assertEquals(ref, ModelRef.parse(ref.toString()));
    }

    @Property
    void parseSplitsOnFirstColonOnly(@ForAll("providerToken") String provider,
                                     @ForAll("modelToken") String model) {
        ModelRef ref = ModelRef.parse(provider + ":" + model);
        assertEquals(provider, ref.provider());
        assertEquals(model, ref.model());
    }

    @Property
    void providerIsCaseFolded(@ForAll("providerToken") String provider,
                              @ForAll("modelToken") String model) {
        ModelRef folded = ModelRef.parse(provider.toUpperCase(Locale.ROOT) + ":" + model);
        assertEquals(provider, folded.provider());
        assertEquals(ModelRef.parse(provider + ":" + model), folded);
    }
}
