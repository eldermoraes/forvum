package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Property-style {@code fromName} invariants for {@link PermissionScope} (mandatory per ULTRAPLAN section 10). */
class PermissionScopePropertyTest {

    @ParameterizedTest
    @EnumSource(PermissionScope.class)
    void fromNameRoundTripsForEveryConstant(PermissionScope scope) {
        assertEquals(scope, PermissionScope.fromName(scope.name()));
    }

    @ParameterizedTest
    @MethodSource("notScopeNames")
    void fromNameRejectsAnythingThatIsNotAConstantName(String value) {
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName(value));
    }

    /** Curated near-misses plus seeded-random strings, all filtered to non-constant names. */
    static Stream<String> notScopeNames() {
        Set<String> valid = Arrays.stream(PermissionScope.values()).map(Enum::name).collect(Collectors.toSet());
        Stream<String> edges = Stream.of("", " ", "fs_read", "FS_", "UNKNOWN", "FS READ", "FS_READ ");
        Random r = new Random(20260603L);
        Stream<String> randoms = Stream.generate(() -> randomAscii(r, 16)).limit(100);
        return Stream.concat(edges, randoms).filter(s -> !valid.contains(s));
    }

    private static String randomAscii(Random r, int maxLen) {
        int len = r.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (' ' + r.nextInt('~' - ' ' + 1)));
        }
        return sb.toString();
    }
}
