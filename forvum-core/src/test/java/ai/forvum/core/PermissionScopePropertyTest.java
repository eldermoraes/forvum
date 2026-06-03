package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property-based {@code fromName} invariants for {@link PermissionScope} (mandatory per ULTRAPLAN section 10). */
class PermissionScopePropertyTest {

    @Property
    void fromNameRoundTripsForEveryConstant(@ForAll PermissionScope scope) {
        assertEquals(scope, PermissionScope.fromName(scope.name()));
    }

    @Property
    void fromNameRejectsAnythingThatIsNotAConstantName(@ForAll("notAScopeName") String value) {
        assertThrows(IllegalStateException.class, () -> PermissionScope.fromName(value));
    }

    @Provide
    Arbitrary<String> notAScopeName() {
        Set<String> valid = Arrays.stream(PermissionScope.values())
            .map(Enum::name)
            .collect(Collectors.toSet());
        return Arbitraries.strings().ofMaxLength(16).filter(s -> !valid.contains(s));
    }
}
