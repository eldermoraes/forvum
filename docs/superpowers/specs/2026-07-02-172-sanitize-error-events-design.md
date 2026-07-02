# #172 — Sanitize error events before channel delivery

**Issue:** [#172](https://github.com/eldermoraes/forvum/issues/172) · `security(output-guard)` · Severity Medium ·
milestone *Hardening / Production Readiness* · Wave 2.
**Branch:** `security/172-sanitize-error-events`.
**Date:** 2026-07-02.

## 1. Problem

`OutputGuard` is the outbound sensitive-data boundary, but it is applied only to the **success** reply
(`TurnService.dispatchAuthenticated:228` runs `outputGuards.enforce` over `reply`). The **error** path
bypasses it: the five `ErrorEvent.from(...)` construction sites build the event from a raw exception
message, and every channel renders `ErrorEvent.message()` verbatim — so a provider/tool/network exception
can leak API keys, bearer tokens, endpoint query strings, response bodies, local paths, PII, prompt
fragments, or tool arguments to remote users (and into channel logs / support transcripts). This is the
`codex-review.md` **[MEDIUM][Security]** "error responses bypass the OutputGuard" finding.

## 2. Verified current state (explorer + reads)

- **All five `ErrorEvent` construction sites live in one method** — `TurnService.dispatchAuthenticated`
  (lines 195, 238, 245, 251, 258). No other construction site exists in main code (cron writes
  `e.getMessage()` to the tasks-ledger `error` column — an internal store, not an `ErrorEvent`; approval
  re-dispatch logs only the event class name). So there is a single sanitize-once seam.
- **No shared renderer.** Ten surfaces each read `error.message()` verbatim (byte-identical copies;
  module isolation forbids a shared type): Web `ChatSocket:135`, TUI `TuiChannel:197` (adds `[error] `),
  Telegram `UpdateProcessor:115`, Discord `MessageProcessor:122`, Slack `SlackMessageProcessor:132`,
  Matrix `SyncProcessor:143`, Signal `EnvelopeProcessor:167`, WhatsApp `MessageProcessor:135`, Voice
  `VoicePipeline:271` (synthesized to **speech**), CLI `AskCommand:71-73` (stderr, `code + message`).
  **None render `exceptionClass`/`stackTraceText`** — but `ErrorEvent.from` populates them on every site
  (a latent serialization leak).
- `TurnService` **already injects `OutputGuardChain outputGuards`** and uses it for the success egress.
- The pure secret redactor is `ai.forvum.engine.security.SecretRedactor.redact(String) → Result(content,
  redactions)` — masks secret-*shaped* tokens (`sk-`/`xox[baprs]-`/`gh[posru]_`/`github_pat_`/`AIza`/
  `AKIA`/PEM/`Bearer <opaque>`/`\d{6,}:…`). It does **not** mask arbitrary paths/args/prompt fragments.

## 3. Decisions (ratified)

- **A1 — the untrusted `turn_failed` message is genericized.** Since `SecretRedactor` cannot guarantee
  "no prompt fragment / tool argument / private path" (those are not secret-shaped), the acceptance
  criteria are met only by **not** sending the raw exception text. The user receives a stable category,
  the root-cause **simple class name** (a code identifier — safe), the curated connection hint, and a
  **correlation id** (`turnId`). The full raw detail is logged internally (redacted). Chosen over
  redact-only (A2), which keeps detail but leaks non-secret sensitive content.
- Sanitize **once at construction** in `TurnService` (a private `emitError` helper), not per-channel — one
  place vs ten copy-pasted renderers.
- Reuse the pure `SecretRedactor` (not the full `OutputGuardChain`, whose `Blocked` disposition would
  *throw* on the error path — an error must always surface as a category, never be blocked).
- Cron's success-egress bypass of `outputGuards` is a **separate** (success, not error) gap — out of #172
  scope, noted as a follow-up.

## 4. Design

### 4.1 `TurnService.emitError` (new private instance helper)

Replaces the five raw `sink.accept(ErrorEvent.from(...))` calls with
`emitError(sink, turnId, code, userMessage, cause)`:

1. **User message** = `SecretRedactor.redact(userMessage).content()` + `" (ref: " + turnId + ")"`.
   For the curated categories `userMessage` is the author-controlled config-error message (redaction is
   belt-and-suspenders); for `turn_failed` it is the **safe** `describeFailure` output (§4.2), which never
   contained raw exception text. A `RuntimeException` from redaction (defensive; it is pure) falls back to
   a stable generic message.
2. **Internal diagnostic log** — `LOG.warnf("Turn %s failed [%s]: %s", turnId, code, detail)` where
   `detail = SecretRedactor.redact(fullDetail(cause)).content()` and `fullDetail` renders the cause's
   class + message + stack trace. Redacted even here (a log is a protected-but-shippable sink — the
   persistence acceptance criterion). This is the operator's path from the user's `ref` to the exception.
3. **Emit** `new ErrorEvent(now, turnId, code, safeMessage, cause.getClass().getName(), null)` — keep the
   exception's `exceptionClass` (a class name — a code identifier, never user data — for telemetry and the
   #166 pairing ITs that assert the exception TYPE; channels never render it) but **null `stackTraceText`**
   (the full stack carries exception messages — the latent serialization leak — which live only in the
   redacted internal log).

### 4.2 `describeFailure` → safe variant

`describeFailure(agentId, e)` currently appends `e.getMessage()` and the deepest `root.getMessage()`.
Rewrite it (rename to `safeFailureMessage`, keep it package-private + static-friendly for the unit test)
to build only from safe components:

- a stable phrase (`"The turn could not be completed"`),
- `" (cause: " + rootCause.getClass().getSimpleName() + ")"` — the simple class name only, **no message**,
- the existing connection hint when `isConnectionFailure(root)`: `". Is the model provider running?
  (model: " + primaryModelOrUnknown(agentId) + ")"` (unchanged — built from the safe model ref).

The deepest-cause walk (hop-capped) is retained to find the root class + detect a connection failure.

### 4.3 Channels

**Unchanged.** Every channel reads `.message()`, now pre-sanitized. The single construction-site fix
covers all eight channels + CLI + Voice (speech). `exceptionClass`/`stackTraceText` being null is safe for
every renderer (none read them).

## 5. Testing (covers the issue's acceptance)

| Test | Asserts |
|---|---|
| `SafeErrorMessageTest` (engine unit) | `safeFailureMessage` for a connection cause → contains the class name + the model hint, and **no** `getMessage()` text; for a non-connection cause → class name only, no message; the deepest-cause walk finds the root class |
| `TurnServiceErrorIT` (extend) | drive a turn whose model throws an exception seeding a **secret** (`sk-...`), a **private path** (`/home/x/.ssh/id_rsa`), a **tool argument**, and a **prompt fragment** in its message+cause → the emitted `ErrorEvent.message()` contains **none** of them; contains the `turn_failed` category + the `ref: <turnId>`; `exceptionClass` and `stackTraceText` are **null** |
| `TurnServiceErrorIT` (curated arm) | an unresolved-role / device-unpaired turn → the (safe) curated message survives + gains the `ref`; still no secret if one were injected |
| fallback arm | a **pathological** cause whose `getMessage()`/`getClass()` throws → `emitError`'s `try/catch(RuntimeException)` around the message build falls back to a stable generic message, never the raw text (satisfies "sanitizer failure produces a generic safe error"; `SecretRedactor` itself is pure + null-safe, so the guard is defensive) |

The [P2-OUTPUTGUARD] `verify`-runs-jacoco discipline applies: run `./mvnw verify` (not just `test`) +
`bash .github/concurrency-guardrails.sh` before pushing. Engine tests run via Surefire (§4 exception).

## 6. Out of scope

- Cron **success**-reply egress not passing through `outputGuards` (a distinct success-path gap).
- A shared `ChannelEventRenderer` SDK seam (the ten renderers stay duplicated by module-isolation design;
  #172 needs no consumer change).
- Persisting error diagnostics to a new CAPR/telemetry table (the WARN log is the sufficient protected
  sink; no new table — YAGNI).
