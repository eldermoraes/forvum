package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Surface contract for the plugin SPI (ULTRAPLAN section 7.1, the M3 Verify). Asserts, via
 * reflection, that each provider interface is sealed and permits ONLY its {@code Abstract*} base —
 * so external code cannot implement the interface directly, it must extend the non-sealed base —
 * and that the base is genuinely extendable (public, non-private no-arg constructor). The four
 * pairs run as a parameterized test; the annotation markers are checked directly.
 */
class SdkSurfaceTest {

    /** Each sealed provider SPI mapped to the single non-sealed abstract base it must permit. */
    private static final Map<Class<?>, Class<?>> SPI_TO_BASE = Map.of(
        ChannelProvider.class, AbstractChannelProvider.class,
        ModelProvider.class, AbstractModelProvider.class,
        ToolProvider.class, AbstractToolProvider.class,
        MemoryProvider.class, AbstractMemoryProvider.class);

    static Stream<Class<?>> providerSpis() {
        return SPI_TO_BASE.keySet().stream();
    }

    @ParameterizedTest
    @MethodSource("providerSpis")
    void interfaceIsSealedAndPermitsOnlyItsAbstractBase(Class<?> spi) {
        assertTrue(spi.isInterface(), () -> spi.getName() + " must be an interface");
        assertTrue(spi.isSealed(), () -> spi.getName() + " must be sealed");
        Class<?>[] permitted = spi.getPermittedSubclasses();
        assertEquals(1, permitted.length,
            () -> spi.getName() + " must permit exactly one subclass, got " + Arrays.toString(permitted));
        assertEquals(SPI_TO_BASE.get(spi), permitted[0],
            () -> spi.getName() + " must permit only its Abstract base");
    }

    @ParameterizedTest
    @MethodSource("providerSpis")
    void abstractBaseIsNonSealedAbstractAndImplementsTheSpi(Class<?> spi) throws NoSuchMethodException {
        Class<?> base = SPI_TO_BASE.get(spi);
        assertTrue(Modifier.isAbstract(base.getModifiers()),
            () -> base.getName() + " must be abstract");
        assertNull(base.getPermittedSubclasses(),
            () -> base.getName() + " must be non-sealed (open for third-party extension)");
        assertTrue(spi.isAssignableFrom(base),
            () -> base.getName() + " must implement " + spi.getName());
        assertTrue(Modifier.isPublic(base.getModifiers()),
            () -> base.getName() + " must be public so external plugins can extend it");
        assertFalse(Modifier.isPrivate(base.getDeclaredConstructor().getModifiers()),
            () -> base.getName() + " must expose a non-private no-arg constructor");
    }

    @Test
    void forvumExtensionIsRuntimeRetainedTypeMarker() {
        assertEquals(RetentionPolicy.RUNTIME,
            ForvumExtension.class.getAnnotation(Retention.class).value());
        assertArrayEquals(new ElementType[] {ElementType.TYPE},
            ForvumExtension.class.getAnnotation(Target.class).value());
    }

    @Test
    void registerForReflectionIsRuntimeTypeMarkerWithEmptyTargetsDefault() throws Exception {
        assertEquals(RetentionPolicy.RUNTIME,
            RegisterForReflection.class.getAnnotation(Retention.class).value());
        assertArrayEquals(new ElementType[] {ElementType.TYPE},
            RegisterForReflection.class.getAnnotation(Target.class).value());
        assertArrayEquals(new Class<?>[] {},
            (Class<?>[]) RegisterForReflection.class.getDeclaredMethod("targets").getDefaultValue());
    }
}
