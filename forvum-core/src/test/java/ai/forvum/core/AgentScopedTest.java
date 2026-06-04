package ai.forvum.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.context.NormalScope;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

/** Reflective contract checks for the {@code @AgentScoped} meta-annotations. Pure unit, no CDI boot. */
class AgentScopedTest {

    @Test
    void isANormalScope() {
        assertTrue(AgentScoped.class.isAnnotationPresent(NormalScope.class));
    }

    @Test
    void retainedAtRuntime() {
        assertEquals(RetentionPolicy.RUNTIME,
                AgentScoped.class.getAnnotation(Retention.class).value());
    }

    @Test
    void targetsTypeMethodAndField() {
        Set<ElementType> targets = Set.of(AgentScoped.class.getAnnotation(Target.class).value());
        assertTrue(targets.containsAll(Set.of(ElementType.TYPE, ElementType.METHOD, ElementType.FIELD)));
    }

    @Test
    void isInherited() {
        assertTrue(AgentScoped.class.isAnnotationPresent(Inherited.class));
    }
}
