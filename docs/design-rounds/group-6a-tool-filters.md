# Group 6a — Threat model + tool-execution filters design round

**Status:** open (inventory populated; no shape proposed yet).
**Depends on:** Phase A (§3.8 Concurrency Discipline) — applied `8fbf714` (2026-05-04). Phase B (§10 Testing Discipline) — applied `d9b03f0` (2026-05-04). Format precedent: `docs/design-rounds/group-4b.md`.
**Blocks:** Group 4c (`FailureClass.Filtered` permit), Group 5 (memory-write boundary), Group 8 (Persona/AgentSpec composition).
**Target sections:** `docs/ULTRAPLAN.md` §9.1 (Threat Model) + §9.2 (Tool-Execution Filters) — both new; insertion point between §8 Risks and §10 Testing Discipline.

---

## Inventory

All line numbers refer to `docs/ULTRAPLAN.md` at baseline of 2026-05-04 (HEAD `d9b03f0`, 1,435 lines). Section-level cross-references where the line number is unstable to small future edits.

### Direct `OutputFilter` mentions

| Line | Section | Context |
|---|---|---|
| 53 | §1.4 Guiding principles | "outbound outputs can be filtered for sensitive data" — only direct mention; no contract, no package, no milestone |
| 1382 | §10 Testing Discipline | Security-test layer cites `output.filtered` indirectly via "see §9 once it lands"; test-side surface but no underlying contract |

### Direct `WorkspaceRoot` / path-traversal signals

| Line | Section | Context |
|---|---|---|
| 94 | §2.4 Layer 3 — extensions | "`forvum-tools-filesystem` — `fs.read`, `fs.write`, `fs.list`, guarded by `PermissionScope.FS_READ` / `FS_WRITE`" — module + scopes locked, no path-validation contract |
| 1229 | M14 Verify | "integration test against a `@TempDir`; read/write/list round-trip asserted; a write outside the configured workspace root is denied" — informal denial assertion, no contract for "configured workspace root" |
| 1382 | §10 Testing Discipline | "path traversal in fs tool args → denied" — security-test surface, no underlying contract |

### Direct `ShellAllowlist` signals

| Line | Section | Context |
|---|---|---|
| 96 | §2.4 Layer 3 — extensions | "`forvum-tools-shell` — `shell.exec` behind an allow-list plus a `USER_CONFIRM_REQUIRED` approval hook" — only direct mention; no contract for what an "allow-list" carries |
| 627 | §4.3.4 PermissionScope (forward-reserved table) | "`SHELL_EXEC` \| Phase 2 (§6) \| sandboxed shell" — capability scope is forward-reserved, sandbox contract missing |

### Prompt-injection structural signals

| Line | Section | Context |
|---|---|---|
| 28 | §1.1 What we replicate from OpenClaw | "Deterministic prompt assembly. Every `Set` and `Map` that feeds prompt composition is sorted by a stable key at the assembly boundary, so prompt-cache prefixes stay byte-identical turn over turn — a correctness requirement, not an optimization." Partial hint at assembly determinism only. |
| — | `docs/CONTEXT-ENGINEERING.md` | Source doc lists "mitigação de ameaças de Prompt Injection cruzada a múltiplos agentes" as one of three Guardrail foundations. ULTRAPLAN.md does not currently realize this principle. |

### Security-UX signals (`USER_CONFIRM_REQUIRED` + denial UX)

| Line | Section | Context |
|---|---|---|
| 53 | §1.4 | "user-approval hooks gate destructive actions" — principle only |
| 96 | §2.4 | "`USER_CONFIRM_REQUIRED` approval hook" — names the engine primitive |
| 1071 | §5.5 LangGraph4j supervisor | "Each call goes through `ToolExecutor`, which enforces `PermissionScope` and `USER_CONFIRM_REQUIRED` hooks" — enforcement point identified, UX surface absent |
| 1285 | §7.2 item 14 | "User-approval queue UI. Dev UI and web-channel cards that show pending `USER_CONFIRM_REQUIRED` tool calls" — Phase 2 dashboard; per-channel per-turn UX still absent |
| 1382 | §10 | `USER_CONFIRM_REQUIRED` referenced in security-test layer scope |

### Threat model signals

| Line | Section | Context |
|---|---|---|
| 51 | §1.4 | "Strict per-agent state isolation prevents context clash" — implicit threat (context clash) called out |
| 53 | §1.4 | Three governance principles in one bullet: `PermissionScope`, user-approval, output filter — implicit threat surfaces (privilege misuse, destructive ops, data leakage) |
| 1017 | §5 chapter intro | "running many agents in the same process without their contexts poisoning each other... `CONTEXT-ENGINEERING.md` names this explicitly as a reason projects fail in production" — `@AgentScoped` is the architectural answer |
| — | `docs/CONTEXT-ENGINEERING.md` | Three foundation pillars: Isolation, Guardrails (RBAC + audit + prompt-injection mitigation + output filtering), Observability. Forvum's Isolation is well-realized; Guardrails are partially realized (`PermissionScope` + audit only); no consolidated threat model in spec. |

---

## Pre-committed constraints (binding for the design)

Already locked elsewhere in the plan. Any proposed shape in 6a MUST honor these:

1. **`PermissionScope` is a closed enum in `forvum-core`** (§4.3.4). Any new "policy" surface (`OutputFilter`, `WorkspaceRoot`, `ShellAllowlist`) composes with `PermissionScope`, never duplicates or replaces it. New scopes (e.g., a dedicated `FS_WRITE_WORKSPACE`) require an enum entry per §4.3.4's evolution rule.
2. **`@AgentScoped` is the isolation primitive** (§5.1). Filters and workspace roots that hold per-agent state are CDI-scoped to `@AgentScoped`; no global mutable maps.
3. **`ToolExecutor` is the enforcement point for tool-side policy** (§5.5). New filter contracts hook into `ToolExecutor` rather than appearing at every tool's call site.
4. **`tool_invocations.status='denied'`** (§4.2) is the audit row for refusals; no parallel audit table.
5. **`AgentEvent` is the channel-out side** (§4.3.2). A filtered-output event is one of: (a) a pre-existing `ToolResult` whose body has been redacted, (b) a new event permit added via §4.3.2 amendment, or (c) an `ErrorEvent` with a code. The choice is one of the open points; whichever, it must respect the sealed hierarchy.
6. **`PermissionDeniedException`** (§4.3.4) is the canonical refusal exception for capability mismatch. New refusal classes (output-filter trip, workspace traversal) need their own exception types or a discriminated reason field on a shared base — to be decided here.
7. **`FallbackReasons` constants** (§4.3.2) form the current taxonomy for fallback-triggering events. Whether a `Filtered` reason joins them (or whether `OutputFilter` short-circuits the chain entirely) is one of the open points; resolved here, consumed by Group 4c (Phase D).
8. **Identity propagation across spawn is a security property** (§5.3). Any per-agent filter policy must be inheritance-explicit at spawn time — default inherit, override allowed; never silently inherit when the parent's policy depends on parent-scoped state.

---

## Open design points

Six points scoped per the meta-plan's Phase C.6a target (≤ 6).

### 1. Threat model summary

A STRIDE-by-surface table for §9.1, not a full threat-modeling workshop. Surfaces to enumerate:

- **User input** (channel inbound: TUI, Web, Telegram) — malicious prompts, oversize payloads, encoded content
- **LLM output** — adversarial responses (prompt injection from earlier turns, exfiltration, jailbreak)
- **Tool input** (LLM-emitted tool args) — path traversal, command injection, schema mismatch
- **Tool output** (tool result back to LLM) — sensitive data leakage, untrusted-data feed-forward
- **Configuration files** (`~/.forvum/`) — local-trust assumption; tampering is a separate threat (filesystem-level, deferred)
- **LLM provider HTTP** — credential leakage, response tampering (partly covered by §3.6 OTel + §4.2 ledger)

**Not in 6a's scope:** plugin trust + MCP server trust → 6b. Audit retention + supply chain + privacy → 6c.

**Open:** which threats land in §9.1's STRIDE table vs. deferred to 6b/6c? Recommendation: 6a covers everything that touches `ToolExecutor` (user input → LLM output → tool dispatch → tool result → channel out); leave plugin/MCP to 6b and audit/supply-chain/privacy/observability to 6c.

### 2. `OutputFilter` contract

The central new contract. Decisions to lock:

- **Layer(s).** Three candidate hook points:
  - (a) **pre-tool-call** (LLM emits tool args; filter inspects before dispatch — catches command-injection-class issues)
  - (b) **pre-channel-emit** (final assistant text before TUI/Web/Telegram render — catches PII)
  - (c) **pre-memory-write** (text bound for `messages` / `episodic_memory` / `semantic_memory` — prevents poisoning persisted state)
  - All three needed in MVP? Three independent hook points or one filter chain at three positions? Per-agent or global?
- **Policy shape.** Regex sets, PII classes (`PII.EMAIL`, `PII.PHONE`, `SECRET.AWS_KEY`), structured matchers, LLM-as-judge filter? Default-on policies vs. opt-in?
- **Trip outcome.** (a) block & terminate turn (`OutputFilterException` → terminal `ErrorEvent`); (b) redact-and-continue (replace match with `[REDACTED:CLASS]`); (c) trigger fallback (new `FallbackReasons.FILTERED` or `FailureClass.Filtered` permit, surfaced to Group 4c). Which outcome per layer?
- **Configurability.** New `~/.forvum/filters/<id>.json` directory? Inline in `agents/<id>.json`? In `config.json` as global default + per-agent override?
- **Composition with `PermissionScope`.** Does an `OutputFilter` carry its own scope (e.g., `FILTER_REDACT`)? Or are filters always invoked, regardless of agent scope?

### 3. `WorkspaceRoot` contract

For fs tools (M14). Decisions:

- **Carrier shape.** `record WorkspaceRoot(Path absoluteRoot, boolean allowSymlinkEscape, CaseSensitivity caseSensitivity)` in `forvum-core`? Per-agent or per-tool-invocation?
- **Validation algorithm.** Canonical: `Path.normalize().toRealPath()` then `startsWith(root)` after symlink resolution. Or normalize-only (faster, but admits symlink escape)? Symlink resolution opt-in per-root?
- **Case sensitivity policy.** APFS (macOS) and NTFS (Windows) are case-insensitive by default; ext4 (Linux) is case-sensitive. `WorkspaceRoot` normalizes case at validation time, or trusts the OS?
- **Configurability.** Where does each agent's workspace root come from? `agents/<id>.json` field? `FORVUM_HOME` derivation? CWD at launch?
- **Trip outcome.** `PermissionDeniedException` (compose with §4.3.4)? Or new `WorkspaceTraversalException`? Audit row in `tool_invocations.status='denied'` either way.

### 4. `ShellAllowlist` contract

For `forvum-tools-shell` (Phase 2 module per §2.4 line 96, but contract is Phase 1 in 6a). Decisions:

- **Argument model.** Each shell call is a `(executable, args[])` tuple — never a freeform `String` that the agent constructs and the engine `bash -c`'s. Parser rejects shell-metacharacters (`;`, `&&`, `||`, `$(…)`, backticks, `>`, `<`, `|`) at the args-array boundary, before invocation.
- **Allowlist shape.** `~/.forvum/shell-allowlist.json` listing permitted executables (`["git", "cat", "ls", "grep"]`) with optional per-executable arg-pattern allowlists? Per-agent in `agents/<id>.json`? Global default + per-agent override?
- **Environment scrubbing.** Subprocess gets a clean env — only `PATH` (curated subset) and explicit allowlist of vars (`LANG`, `LC_ALL`, `HOME`). Forbids `LD_PRELOAD`, `DYLD_INSERT_LIBRARIES`, etc.
- **Working-dir pinning.** `shell.exec` always pins CWD to the agent's `WorkspaceRoot` (point 3); cannot escape via `cd` or absolute paths.
- **Output capture limits.** Max stdout/stderr bytes (default 1 MB?), wall-clock timeout (default 30 s?), exit-code propagation.
- **`USER_CONFIRM_REQUIRED` escalation.** Every shell call requires user confirmation in MVP; the allowlist relaxes confirmation in v0.5+ for "low-risk" categories. Where is the categorization recorded?

### 5. Prompt-injection structural defense

Detection lives in `OutputFilter` (point 2); this point covers prompt-construction discipline:

- **Delimiter discipline.** System-prompt / user-message / tool-output frames use distinct, non-overlapping delimiters (XML-style tags or distinctive markers). LLM is instructed in the system prompt that text inside `<tool_output>…</tool_output>` is *data*, not *instructions*.
- **Deterministic ordering.** Already partially locked (§1.1 line 28). Extend to: order is (1) system prompt, (2) ordered conversation history, (3) current user message, (4) tool outputs from current turn. Never mixed, never re-ordered by retrieval rank.
- **No concatenation of LLM-emitted text into authoritative instructions.** A tool result the LLM later cites stays inside its `<tool_output>` frame; the engine never lifts tool-result text into the system-prompt prefix or user-message body.
- **Memory retrieval framing.** When `MemoryPolicy` (Group 5, future) returns retrieved snippets, they are framed as `<retrieved_memory>` blocks the system prompt classifies as data. (Hands a constraint forward to Group 5.)
- **What this is *not*.** Not a content-policy or jailbreak-detection mechanism — those live in `OutputFilter`. This point is the *plumbing* that makes detection possible.

### 6. Security UX (per channel)

`USER_CONFIRM_REQUIRED` is the engine primitive (§5.5); the UX is per-channel. Decisions:

- **TUI (M15).** When a `USER_CONFIRM_REQUIRED` tool call arrives, the TUI pauses streaming, prints the proposed call (`tool name + args`), prompts `[y/N]`. Timeout? `--no-ansi` fallback path? "Always allow this combo" learning mode (deferred to Phase 2)?
- **Web (M16).** Modal or inline prompt in the chat UI; same data shown. WebSocket round-trip carries the user's decision. Multi-arg rendering?
- **Telegram (M17).** Bot sends an inline-keyboard message ("Approve / Reject"); user taps; engine receives the callback. Timeout for user response?
- **Denial visibility.** When a tool call is denied (PermissionScope mismatch, output-filter trip pre-call), does the agent (a) retry without that tool, (b) surface the denial as part of the assistant message, (c) both? Silent denials look like failure modes; visible denials risk leaking the security policy to the user.
- **What is *not* in scope.** Phase 2 user-approval queue UI (§7.2 item 14) — only the per-turn UX in MVP.

---

## Decisions log

*(Empty at round opening. Each closed open-point becomes a numbered Decision with: Approved date; Decision; Rationale; Resolves open point(s); Implication for §9.1/§9.2 spec; Implication for other sections.)*
