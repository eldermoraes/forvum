# #172 — Sanitize Error Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Route every channel-visible turn error through sanitization so no secret/path/prompt-fragment/tool-argument leaks, by fixing the single construction seam in `TurnService`.

**Architecture:** All five `ErrorEvent` construction sites live in `TurnService.dispatchAuthenticated`. Replace them with one `emitError` helper that (a) builds a safe user message — the untrusted `turn_failed` path is genericized to a category + root-cause class name + curated connection hint + correlation id, never raw exception text; curated config errors pass through the `SecretRedactor` — (b) logs the full redacted diagnostic keyed by `turnId`, and (c) emits the event with null diagnostic fields. The ten channel/CLI renderers read `.message()` verbatim, so this single fix covers them all.

**Tech Stack:** Java 25, Quarkus, `ai.forvum.engine.security.SecretRedactor` (pure, null-safe), JBoss Logging, JUnit 5, `@QuarkusTest`.

## Global Constraints

- English-only artifacts. Native-first (no reflection added; all types already native-safe).
- Reuse the **pure `SecretRedactor.redact(String) → Result(content, redactions)`**, NOT `OutputGuardChain` (whose `Blocked` disposition throws — an error must never be blocked).
- Engine tests run via Surefire (`./mvnw -pl forvum-engine test`, §4 exception). Run `./mvnw verify` (jacoco) + `bash .github/concurrency-guardrails.sh` before pushing.
- No commit/push/PR without authorization (authorized for #172; branch `security/172-sanitize-error-events`).

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `forvum-engine/.../agent/TurnService.java` | `emitError` + pure `safeFailureMessage`/`redactedDiagnostic` statics + LOG; replace 5 sites; remove `describeFailure` | Modify |
| `forvum-engine/src/test/.../agent/SafeErrorFormattingTest.java` | unit-test the pure statics | Create |
| `forvum-engine/src/test/.../agent/LeakyModelProvider.java` | a test provider (id `leaky`) throwing a secret/path/prompt-laden exception | Create |
| `forvum-engine/src/test/.../agent/TurnServiceSafeErrorIT.java` | E2E: a failed turn leaks nothing to the channel | Create |
| `forvum-engine/src/test/.../agent/TurnServiceErrorIT.java` | strengthen (ref + null diagnostic fields) | Modify |
| `docs/ULTRAPLAN.md`, `docs/IMPLEMENTATION-ORDER.md`, `CLAUDE.md` | as-built + status + lesson | Modify |

---

### Task 1: Pure sanitization statics + unit tests

**Files:**
- Modify: `forvum-engine/src/main/java/ai/forvum/engine/agent/TurnService.java`
- Test: `forvum-engine/src/test/java/ai/forvum/engine/agent/SafeErrorFormattingTest.java`

**Interfaces:**
- Produces: `static String safeFailureMessage(Throwable e, String modelHint)`, `static String redactedDiagnostic(Throwable cause)` (package-private on `TurnService`). Consumes the existing `static boolean isConnectionFailure(Throwable)`.

- [ ] **Step 1: Write the failing unit test**

Create `SafeErrorFormattingTest.java`:
```java
package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;

/** The pure #172 sanitization helpers: {@code safeFailureMessage} builds only from safe components (a
 *  class name + the curated connection hint), and {@code redactedDiagnostic} masks secrets in the internal
 *  log detail. Plain unit test (package-private statics). */
class SafeErrorFormattingTest {

    @Test
    void connectionFailureNamesClassAndModelHintButNoRawText() {
        RuntimeException e = new RuntimeException("wrapper sk-LEAK1234567890abcd /home/x/.ssh",
                new ConnectException("refused: secret /etc/shadow"));
        String msg = TurnService.safeFailureMessage(e, "ollama:qwen3");
        assertTrue(msg.contains("ConnectException"), "keeps the root-cause class name");
        assertTrue(msg.contains("ollama:qwen3"), "keeps the safe model hint");
        assertFalse(msg.contains("LEAK1234567890abcd"), "no secret");
        assertFalse(msg.contains("/home/x/.ssh"), "no path");
        assertFalse(msg.contains("/etc/shadow"), "no nested-cause path");
        assertFalse(msg.contains("refused"), "no raw cause message");
    }

    @Test
    void nonConnectionFailureNamesOnlyTheClassNoHint() {
        RuntimeException e = new RuntimeException("boom /etc/passwd",
                new IllegalStateException("tool arg leaked"));
        String msg = TurnService.safeFailureMessage(e, "ollama:x");
        assertTrue(msg.contains("IllegalStateException"), "keeps the root-cause class");
        assertFalse(msg.contains("/etc/passwd"), "no path");
        assertFalse(msg.contains("tool arg leaked"), "no raw cause message");
        assertFalse(msg.contains("Is the model provider running"), "no connection hint for a non-connection cause");
    }

    @Test
    void aPathologicalCauseChainFallsBackToTheGenericMessage() {
        Throwable pathological = new RuntimeException("x") {
            @Override public synchronized Throwable getCause() {
                throw new RuntimeException("cause access blows up");
            }
        };
        assertEquals("The turn could not be completed",
                TurnService.safeFailureMessage(pathological, "ollama:x"));
    }

    @Test
    void redactedDiagnosticMasksSecretsButKeepsTheClass() {
        Throwable cause = new IllegalStateException("call failed with sk-DISTINCTIVEBODY1234567");
        String d = TurnService.redactedDiagnostic(cause);
        assertFalse(d.contains("DISTINCTIVEBODY1234567"), "the internal log redacts the secret body");
        assertTrue(d.contains("IllegalStateException"), "but keeps the class for the operator");
    }

    @Test
    void redactedDiagnosticIsNullSafe() {
        assertEquals("(no cause)", TurnService.redactedDiagnostic(null));
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -pl forvum-engine test -Dtest=SafeErrorFormattingTest -Dsurefire.failIfNoSpecifiedTests=false -B -Dstyle.color=never` → FAIL (methods do not exist).

- [ ] **Step 3: Implement the statics** — in `TurnService.java`, add imports `java.io.PrintWriter`, `java.io.StringWriter`, `ai.forvum.engine.security.SecretRedactor`. Add:

```java
    /**
     * The SAFE user-facing message for a failed turn (#172): built ONLY from non-sensitive components — a
     * stable phrase, the root cause's simple CLASS name (a code identifier, never user data), and the
     * curated connection hint. It deliberately excludes {@code e.getMessage()}/cause messages, which can
     * carry provider bodies, tool arguments, paths, prompt fragments, or secrets. Defensive: any failure
     * walking a pathological cause chain falls back to the stable generic phrase (never throws into the
     * dispatch catch arm). Package-private + static for the unit test.
     */
    static String safeFailureMessage(Throwable e, String modelHint) {
        try {
            Throwable root = e;
            for (int hops = 0; hops < 50 && root.getCause() != null && root.getCause() != root; hops++) {
                root = root.getCause();
            }
            String message = "The turn could not be completed (cause: " + root.getClass().getSimpleName() + ")";
            if (isConnectionFailure(root)) {
                message += ". Is the model provider running? (model: " + modelHint + ")";
            }
            return message;
        } catch (RuntimeException defensive) {
            return "The turn could not be completed";
        }
    }

    /**
     * The full internal diagnostic for the PROTECTED operator log (#172): the cause chain's stack trace
     * (class + message + causes), run through {@link SecretRedactor} so even the log follows the redaction
     * boundary. Keyed by {@code turnId} at the call site, it is how an operator walks from the {@code ref}
     * the user received to the real exception. Null/failure safe.
     */
    static String redactedDiagnostic(Throwable cause) {
        if (cause == null) {
            return "(no cause)";
        }
        try {
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            return SecretRedactor.redact(sw.toString()).content();
        } catch (RuntimeException defensive) {
            return "<diagnostic unavailable>";
        }
    }
```

- [ ] **Step 4: Run to verify it passes** — same command → PASS (5 tests).

- [ ] **Step 5: Commit** — `git add ...TurnService.java ...SafeErrorFormattingTest.java && git commit -m "feat(engine): add pure safe-error sanitization statics (#172)"`

---

### Task 2: emitError helper + replace the 5 sites

**Files:**
- Modify: `forvum-engine/src/main/java/ai/forvum/engine/agent/TurnService.java`
- Test: `forvum-engine/src/test/java/ai/forvum/engine/agent/TurnServiceErrorIT.java`

**Interfaces:**
- Consumes: `safeFailureMessage`, `redactedDiagnostic` (Task 1), `SecretRedactor.redact`, the existing `primaryModelOrUnknown(agentId)`.
- Produces: `private void emitError(Consumer<AgentEvent> sink, UUID turnId, String code, String userMessage, Throwable cause)`.

- [ ] **Step 1: Strengthen the failing test** — in `TurnServiceErrorIT.dispatchEmitsAnErrorEventWhenTheTurnFailsInsteadOfThrowing`, after the existing asserts add:
```java
        assertTrue(error.message().contains("ref: " + error.turnId()),
                "#172: the safe message carries the correlation id");
        assertNull(error.exceptionClass(), "#172: no diagnostic class on the channel-bound event");
        assertNull(error.stackTraceText(), "#172: no diagnostic stack on the channel-bound event");
```
Add imports `static org.junit.jupiter.api.Assertions.assertNull` and `assertTrue`.

- [ ] **Step 2: Run to verify it fails** — `./mvnw -pl forvum-engine test -Dtest=TurnServiceErrorIT -Dsurefire.failIfNoSpecifiedTests=false -B -Dstyle.color=never` → FAIL (`exceptionClass`/`stackTraceText` are currently populated; no `ref:`).

- [ ] **Step 3: Implement `emitError` + LOG, replace the 5 sites, remove `describeFailure`.**

Add a logger — imports `org.jboss.logging.Logger`; a field near the top of the class:
```java
    private static final Logger LOG = Logger.getLogger(TurnService.class);
```
Add the helper:
```java
    /**
     * Emit a terminal {@link ErrorEvent} to the channel with a SANITIZED message (#172). The user-facing
     * message is the {@link SecretRedactor}-redacted {@code userMessage} plus the {@code turnId} as a
     * correlation {@code ref} (a null/blank redaction falls back to a stable phrase). The FULL detail is
     * logged (redacted) at WARN keyed by {@code turnId} — the protected operator diagnostic. The event
     * carries NO {@code exceptionClass}/{@code stackTraceText}, removing the latent serialization leak;
     * channels render only {@code message}. Defensive: message building never throws into the caller.
     */
    private void emitError(Consumer<AgentEvent> sink, UUID turnId, String code, String userMessage,
            Throwable cause) {
        String safe;
        try {
            String redacted = SecretRedactor.redact(userMessage).content();
            String base = (redacted == null || redacted.isBlank()) ? "The turn failed" : redacted;
            safe = base + " (ref: " + turnId + ")";
        } catch (RuntimeException guardFailure) {
            safe = "The turn failed (ref: " + turnId + ")";
        }
        LOG.warnf("Turn %s failed [%s]: %s", turnId, code, redactedDiagnostic(cause));
        sink.accept(new ErrorEvent(Instant.now(), turnId, code, safe, null, null));
    }
```
Replace the five `sink.accept(ErrorEvent.from(...))` calls:
- `role_unresolved` (≈195): `emitError(sink, turnId, "role_unresolved", roleError.getMessage(), roleError);`
- `output_filtered` (≈238): `emitError(sink, turnId, "output_filtered", filtered.getMessage(), filtered);`
- `identity_unresolved` (≈245): `emitError(sink, turnId, "identity_unresolved", unresolved.getMessage(), unresolved);`
- `device_unpaired` (≈251): `emitError(sink, turnId, "device_unpaired", deviceRejected.getMessage(), deviceRejected);`
- `turn_failed` (≈258): `emitError(sink, turnId, "turn_failed", safeFailureMessage(e, primaryModelOrUnknown(agentId)), e);`

Delete the old `describeFailure(AgentId, RuntimeException)` method (its safe replacement is `safeFailureMessage`; grep confirms line 258 was its only caller). Keep `isConnectionFailure` and `primaryModelOrUnknown`.

- [ ] **Step 4: Run to verify it passes** — the same `TurnServiceErrorIT` command → PASS. Then run the whole error/formatting set: `./mvnw -pl forvum-engine test -Dtest=SafeErrorFormattingTest,TurnServiceErrorIT -Dsurefire.failIfNoSpecifiedTests=false -B -Dstyle.color=never` → PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(engine): sanitize every ErrorEvent at the TurnService emit seam (#172)"`

---

### Task 3: Leaky-provider E2E proving no leak reaches the channel

**Files:**
- Create: `forvum-engine/src/test/java/ai/forvum/engine/agent/LeakyModelProvider.java`
- Create: `forvum-engine/src/test/java/ai/forvum/engine/agent/TurnServiceSafeErrorIT.java`

**Interfaces:**
- Consumes: `ChannelTurnDriver`, `AbstractModelProvider` (SDK), langchain4j `ChatModel`/`ChatRequest`/`ChatResponse`.

- [ ] **Step 1: Write the leaky provider** (mirror `BoomModelProvider`'s shape — read it for the exact imports):
```java
package ai.forvum.engine.agent;

import ai.forvum.core.ModelRef;
import ai.forvum.sdk.AbstractModelProvider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A model provider (extension id {@code leaky}) whose {@link ChatModel} throws an exception whose message
 * and nested cause carry a secret, private paths, a tool argument, and a prompt fragment — the #172
 * leak fixture. {@code TurnServiceSafeErrorIT} asserts none of them reach the channel-visible error.
 */
@ApplicationScoped
public class LeakyModelProvider extends AbstractModelProvider {

    @Override
    public String extensionId() {
        return "leaky";
    }

    @Override
    public ChatModel resolve(ModelRef ref) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new RuntimeException(
                        "provider POST failed with Authorization key sk-LEAKED1234567890abcdef "
                        + "writing /home/victim/.ssh/id_rsa",
                        new IllegalStateException(
                                "tool arg {\"path\":\"/etc/shadow\"} prompt fragment SECRET-PROMPT-XYZ"));
            }
        };
    }
}
```

- [ ] **Step 2: Write the failing E2E** (`TurnServiceSafeErrorIT.java`, mirror `TurnServiceErrorIT`'s profile shape, pinning `main` to `leaky:test-model`):
```java
package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.ErrorEvent;
import ai.forvum.sdk.ChannelTurnDriver;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * #172 acceptance: a failed turn whose provider exception carries a secret, private paths, a tool
 * argument, and a prompt fragment must surface to the channel with NONE of them — only a stable category,
 * the correlation id, and (for a connection cause) the curated hint. The channel-bound event carries no
 * diagnostic fields. Driven through the real {@link ChannelTurnDriver} with {@code main} pinned to the
 * {@code leaky} provider.
 */
@QuarkusTest
@TestProfile(TurnServiceSafeErrorIT.LeakyHomeProfile.class)
class TurnServiceSafeErrorIT {

    @Inject
    ChannelTurnDriver driver;

    @Test
    void aFailedTurnLeaksNoSecretPathPromptOrToolArgToTheChannel() {
        List<AgentEvent> events = new ArrayList<>();

        driver.dispatch(new ChannelMessage("web", "sess-leak", "hello", Instant.now()), events::add);

        ErrorEvent error = assertInstanceOf(ErrorEvent.class, events.get(0), "a failed turn emits an ErrorEvent");
        String m = error.message();
        assertFalse(m.contains("sk-LEAKED1234567890abcdef"), "no secret in the channel-visible error");
        assertFalse(m.contains("/home/victim/.ssh/id_rsa"), "no private path");
        assertFalse(m.contains("/etc/shadow"), "no private path (nested cause)");
        assertFalse(m.contains("SECRET-PROMPT-XYZ"), "no prompt fragment");
        assertFalse(m.contains("\"path\":"), "no tool argument");
        assertEquals("turn_failed", error.code(), "a stable failure category");
        assertTrue(m.contains("ref: " + error.turnId()), "a correlation id");
        assertNull(error.exceptionClass(), "no diagnostic class on the channel event");
        assertNull(error.stackTraceText(), "no diagnostic stack on the channel event");
    }

    public static class LeakyHomeProfile implements QuarkusTestProfile {
        static final Path HOME = seed();

        private static Path seed() {
            try {
                Path home = Files.createTempDirectory("forvum-leaky-turn-home");
                Path agents = Files.createDirectories(home.resolve("agents"));
                Files.writeString(agents.resolve("main.md"), "You are the main agent.");
                Files.writeString(agents.resolve("main.json"),
                        "{ \"primaryModel\": \"leaky:test-model\", \"allowedTools\": [] }");
                return home;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("forvum.home", HOME.toString());
        }
    }
}
```

- [ ] **Step 3: Run to verify** — `./mvnw -pl forvum-engine test -Dtest=TurnServiceSafeErrorIT -Dsurefire.failIfNoSpecifiedTests=false -B -Dstyle.color=never`. It should PASS with Task 2 implemented (the sanitization is already in place); if Task 2 were reverted it would FAIL (the raw secret/path would appear). Red-check once by temporarily reverting the `turn_failed` site to the raw `ErrorEvent.from(..., describeFailure...)` form and confirming the secret assertion fails, then restore.

- [ ] **Step 4: Commit** — `git add ...LeakyModelProvider.java ...TurnServiceSafeErrorIT.java && git commit -m "test(engine): e2e — a failed turn leaks no secret/path/prompt to the channel (#172)"`

---

### Task 4: Docs

**Files:** `docs/ULTRAPLAN.md` (§9 threat model — the OutputGuard error-egress as-built), `docs/IMPLEMENTATION-ORDER.md` (#172 done), `CLAUDE.md` §14 (`[#172]` lesson), `.github/docs-drift.sh` if it guards a related fact (it does not — verify).

- [ ] **Step 1: ULTRAPLAN §9** — find the OutputGuard/§9.2 threat-model text and add an as-built note: error egress now also passes the redaction boundary (#172) — the `turn_failed` message is genericized to category + root class + hint + correlation id; curated config errors are redacted; the diagnostic is logged (redacted), not sent.
- [ ] **Step 2: `docs/IMPLEMENTATION-ORDER.md`** — mark #172 done in the Wave-2 list (line ~66) with a one-line as-built.
- [ ] **Step 3: `CLAUDE.md` §14** — append a `[#172]` lesson: the single-emit-seam (all 5 ErrorEvent sites in TurnService; 10 renderers read `.message()` verbatim so sanitize-at-construction beats touching consumers); A1 genericize-untrusted vs redact-only; SecretRedactor (not OutputGuardChain — Block throws) for errors; null the diagnostic fields; correlation-id + redacted internal log.
- [ ] **Step 4: Commit** — `git commit -am "docs(#172): record sanitized error-egress as-built + lesson"`

---

### Final verification (before PR)

- [ ] `./mvnw -pl forvum-engine test -B -Dstyle.color=never` → engine suite green.
- [ ] `./mvnw verify -B -Dstyle.color=never` → BUILD SUCCESS (jacoco gates; read the real status, not a pipe exit). If the ~2 new TurnService methods dent the engine line/branch gate, the unit + E2E tests above cover them — confirm from `forvum-engine/target/site/jacoco/jacoco.csv`.
- [ ] `bash .github/concurrency-guardrails.sh` + `bash .github/docs-drift.sh` → green.
- [ ] Native is behavioral-only here (no reflection/metadata added); CI runs the native legs.
