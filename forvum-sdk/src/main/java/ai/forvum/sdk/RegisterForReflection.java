package ai.forvum.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Forvum-owned reflection-registration marker — what ULTRAPLAN calls the SDK's "re-export" of
 * {@code @RegisterForReflection}, realized here as a Quarkus-free re-declaration (it is NOT the
 * Quarkus annotation) so plugins can request native reflection on their DTO records without depending
 * on {@code quarkus-core} (ULTRAPLAN section 2.2, section 7.1 M3). Its shape models the subset of
 * Quarkus' {@code io.quarkus.runtime.annotations.RegisterForReflection} that Forvum's build-time
 * mapping needs (the targets-holder form); a build-time {@code BuildStep} in forvum-engine (a later
 * milestone) translates each occurrence into the equivalent native-image reflection hint.
 *
 * <p>Apply to a type to register that type, or set {@link #targets()} to register other types from a
 * single holder (the form used to register Layer-0 records, which cannot carry the annotation).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterForReflection {

    /** Types to register for reflection; empty (the default) registers the annotated type itself. */
    Class<?>[] targets() default {};
}
