package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Property-style invariants for {@link ModelRef#parse} (mandatory per ULTRAPLAN section 10). */
class ModelRefPropertyTest {

    private static final long SEED = 20260603L;
    private static final int CASES = 100;

    /** (provider, model) pairs: curated edge cases + seeded-random tokens (model may carry inner colons). */
    static Stream<Arguments> providerModelPairs() {
        Random r = new Random(SEED);
        Stream<Arguments> edges = Stream.of(
            arguments("a", "x"),
            arguments("ollama", "qwen3:1.7b"),   // inner colon -> split-on-first-colon coverage
            arguments("openai", "gpt-4o"),
            arguments("z", "a:b:c"));
        Stream<Arguments> randoms = Stream.generate(() -> arguments(provider(r), model(r))).limit(CASES);
        return Stream.concat(edges, randoms);
    }

    @ParameterizedTest
    @MethodSource("providerModelPairs")
    void parseRoundTripsThroughToString(String provider, String model) {
        ModelRef ref = new ModelRef(provider, model);
        assertEquals(ref, ModelRef.parse(ref.toString()));
    }

    @ParameterizedTest
    @MethodSource("providerModelPairs")
    void parseSplitsOnFirstColonOnly(String provider, String model) {
        ModelRef ref = ModelRef.parse(provider + ":" + model);
        assertEquals(provider, ref.provider());
        assertEquals(model, ref.model());
    }

    @ParameterizedTest
    @MethodSource("providerModelPairs")
    void providerIsCaseFolded(String provider, String model) {
        ModelRef folded = ModelRef.parse(provider.toUpperCase(Locale.ROOT) + ":" + model);
        assertEquals(provider, folded.provider());
        assertEquals(ModelRef.parse(provider + ":" + model), folded);
    }

    private static String provider(Random r) {           // lowercase a-z, length 1..12
        int len = 1 + r.nextInt(12);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + r.nextInt(26)));
        }
        return sb.toString();
    }

    private static String model(Random r) {              // 1..3 alnum segments joined by ':'
        int segments = 1 + r.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < segments; s++) {
            if (s > 0) {
                sb.append(':');
            }
            int len = 1 + r.nextInt(8);
            for (int i = 0; i < len; i++) {
                sb.append(alnum(r));
            }
        }
        return sb.toString();
    }

    private static char alnum(Random r) {
        int n = r.nextInt(62);
        if (n < 26) return (char) ('a' + n);
        if (n < 52) return (char) ('A' + n - 26);
        return (char) ('0' + n - 52);
    }
}
