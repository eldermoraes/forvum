package ai.forvum.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as a Forvum plugin entry point (ULTRAPLAN section 2.2, section 6.3). A build-time
 * {@code BuildStep} in forvum-engine (a later milestone) scans for this marker together with the
 * {@code META-INF/forvum/plugin.json} manifest to record the contributed providers and emit the
 * native-image reflection hints; {@code ServiceLoader} is a fast-jar-only fallback. Runtime-retained
 * so the fast-jar path can discover it too.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ForvumExtension {
}
