package ai.forvum.core;

import jakarta.enterprise.context.NormalScope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom CDI normal scope isolating per-agent bean state. Bean instances annotated {@code @AgentScoped}
 * resolve to the instance keyed by the {@code ScopedValue<AgentId>} bound for the current turn (see
 * ULTRAPLAN section 5.1), so sibling agents fanned out across virtual threads never share state.
 *
 * <p>Declared in {@code forvum-core} (Layer 0) per the architecture; its backing
 * {@code InjectableContext} and the registration live in {@code forvum-engine}. Carries no
 * {@code @RegisterForReflection} (Layer-0 exemption).
 */
@NormalScope
@Inherited
@Documented
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentScoped {
}
