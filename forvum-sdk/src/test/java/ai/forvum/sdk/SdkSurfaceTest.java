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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.junit.jupiter.api.Test;

/**
 * Surface contract for the plugin SPI (ULTRAPLAN section 7.1, the M3 Verify). Asserts, via
 * reflection, that each provider interface is sealed and permits ONLY its {@code Abstract*} base —
 * so external code cannot implement the interface directly, it must extend the non-sealed base,
 * keeping the implementation set closed and build-time-known for native-image discovery. The four
 * pairs are exercised as a jqwik property (this module pins the JUnit 5 platform line in its pom,
 * CLAUDE.md section 11).
 */
class SdkSurfaceTest {

    /** Each sealed provider SPI mapped to the single non-sealed abstract base it must permit. */
    private static final Map<Class<?>, Class<?>> SPI_TO_BASE = Map.of(
        ChannelProvider.class, AbstractChannelProvider.class,
        ModelProvider.class, AbstractModelProvider.class,
        ToolProvider.class, AbstractToolProvider.class,
        MemoryProvider.class, AbstractMemoryProvider.class);

    @Provide
    Arbitrary<Class<?>> providerSpi() {
        return Arbitraries.of(
            ChannelProvider.class, ModelProvider.class, ToolProvider.class, MemoryProvider.class);
    }

    @Property
    void interfaceIsSealedAndPermitsOnlyItsAbstractBase(@ForAll("providerSpi") Class<?> spi) {
        assertTrue(spi.isInterface(), () -> spi.getName() + " must be an interface");
        assertTrue(spi.isSealed(), () -> spi.getName() + " must be sealed");
        Class<?>[] permitted = spi.getPermittedSubclasses();
        assertEquals(1, permitted.length,
            () -> spi.getName() + " must permit exactly one subclass, got " + Arrays.toString(permitted));
        assertEquals(SPI_TO_BASE.get(spi), permitted[0],
            () -> spi.getName() + " must permit only its Abstract base");
    }

    @Property
    void abstractBaseIsNonSealedAbstractAndImplementsTheSpi(@ForAll("providerSpi") Class<?> spi)
            throws NoSuchMethodException {
        Class<?> base = SPI_TO_BASE.get(spi);
        assertTrue(Modifier.isAbstract(base.getModifiers()),
            () -> base.getName() + " must be abstract");
        assertNull(base.getPermittedSubclasses(),
            () -> base.getName() + " must be non-sealed (open for third-party extension)");
        assertTrue(spi.isAssignableFrom(base),
            () -> base.getName() + " must implement " + spi.getName());
        // The base must be genuinely extendable by an external plugin (the documented "only way to
        // implement the SPI"): public, with a reachable (non-private) no-arg constructor. Without
        // these, a base could stay abstract+non-sealed yet silently un-extendable from outside.
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
