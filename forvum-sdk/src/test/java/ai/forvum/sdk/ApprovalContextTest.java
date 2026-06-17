package ai.forvum.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ApprovalContext}: the per-turn approval-resolution bindings are unbound by default (the async
 * dashboard path) and carry an {@link ApprovalPrompter}/flag when a surface binds them.
 */
class ApprovalContextTest {

    @Test
    void bindingsAreUnboundByDefault() {
        assertFalse(ApprovalContext.PROMPTER.isBound(), "no interactive prompter unless a channel binds one");
        assertFalse(ApprovalContext.NON_INTERACTIVE.isBound(), "non-interactive flag is opt-in");
    }

    @Test
    void prompterCarriesTheInteractiveDecision() throws Exception {
        String seen = ScopedValue
                .where(ApprovalContext.PROMPTER,
                        (agentId, tool, args) -> "main".equals(agentId) && "shell.exec".equals(tool))
                .call(() -> {
                    assertTrue(ApprovalContext.PROMPTER.isBound());
                    boolean approved = ApprovalContext.PROMPTER.get().promptApproval("main", "shell.exec", "{}");
                    return approved ? "approved" : "rejected";
                });
        assertEquals("approved", seen);
    }

    @Test
    void nonInteractiveFlagCarriesTrue() throws Exception {
        Boolean value = ScopedValue.where(ApprovalContext.NON_INTERACTIVE, Boolean.TRUE)
                .call(ApprovalContext.NON_INTERACTIVE::get);
        assertTrue(value);
    }
}
