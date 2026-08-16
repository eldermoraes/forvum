package ai.forvum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;
import ai.forvum.core.id.AgentId;
import ai.forvum.engine.context.CurrentIdentity;
import ai.forvum.engine.persistence.ToolInvocationEntity;
import ai.forvum.engine.tools.PermissionDeniedException;
import ai.forvum.engine.tools.ToolExecutor;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Security-test layer for #188: an identity whose effective scopes do NOT include
 * {@link PermissionScope#CHANNEL_SEND} cannot invoke {@code message.send} — the real engine
 * {@link ToolExecutor} refuses with {@link PermissionDeniedException} and audits a
 * {@code tool_invocations} row with {@code status = 'denied'} (the #166 RBAC gate, enforce-iff-bound).
 * The spec under test carries the same name/scope/confirm shape the messaging plugin contributes
 * (its {@code SPEC} constant is deliberately package-private — Resolution B keeps the envelope
 * engine-side). Mirrors {@link PermissionScopeMismatchTest} and reuses its temp-home profile.
 */
@QuarkusTest
@TestProfile(PermissionScopeMismatchTest.SecurityHomeProfile.class)
class ChannelSendScopeDeniedTest {

    @Inject
    ToolExecutor executor;

    @Test
    void messageSendOutsideTheCallersEffectiveScopesIsDeniedAndAudited() throws Exception {
        ToolSpec messageSend = new ToolSpec("message.send", "send a message on a configured channel",
                PermissionScope.CHANNEL_SEND, "{}", true);
        List<ToolSpec> belt = List.of(messageSend);
        Set<PermissionScope> withoutChannelSend =
                EnumSet.of(PermissionScope.FS_READ, PermissionScope.MEMORY_READ);

        Supplier<String> mustNotRun = () -> {
            throw new AssertionError("a scope-denied message.send must never run");
        };

        ScopedValue.where(CurrentIdentity.CURRENT_EFFECTIVE_SCOPES, withoutChannelSend).call(() ->
                assertThrows(PermissionDeniedException.class, () -> executor.execute(
                        "sess-sec-188", new AgentId("attacker"), belt, "message.send", "{}",
                        mustNotRun)));

        long denied = ToolInvocationEntity.count(
                "status = ?1 and toolName = ?2", "denied", "message.send");
        assertEquals(1L, denied,
                "the CHANNEL_SEND scope denial must be audited to tool_invocations with "
                        + "status='denied'");
    }
}
