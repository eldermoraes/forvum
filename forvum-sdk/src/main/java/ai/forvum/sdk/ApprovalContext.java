package ai.forvum.sdk;

/**
 * Per-turn approval-resolution bindings a channel/command sets so the engine knows HOW to resolve a
 * {@code USER_CONFIRM_REQUIRED} tool call (P2-14 #39). The engine reads these {@link ScopedValue}s inside
 * the turn (they propagate across the {@code respond → SupervisorGraph → ToolExecutor} chain on one
 * virtual thread, like {@code CURRENT_AGENT}):
 *
 * <ul>
 *   <li>{@link #PROMPTER} bound → <strong>interactive</strong>: the engine prompts the owner synchronously
 *       on the turn thread (the TUI binds it). The TTY fallback.</li>
 *   <li>neither bound → <strong>async</strong>: the engine parks the call in the SQLite queue and blocks
 *       until the web dashboard approves/rejects it, or it times out to deny. The default for a server
 *       channel turn (web, telegram, …) where the dashboard can resolve it.</li>
 *   <li>{@link #NON_INTERACTIVE} bound {@code true} → <strong>immediate deny</strong>: a one-shot/cron
 *       entry ({@code forvum ask}, {@code CronScheduler}) with no approval surface denies at once rather
 *       than blocking for a dashboard that will never answer.</li>
 * </ul>
 *
 * <p>An interface (not a class) so it carries no constructor — the {@code ScopedValue} constants are
 * implicitly {@code public static final} — keeping {@code forvum-sdk} Quarkus-free and logic-free.
 */
public interface ApprovalContext {

    /** Bound by an interactive channel/command for the turn; its presence selects synchronous prompting. */
    ScopedValue<ApprovalPrompter> PROMPTER = ScopedValue.newInstance();

    /** Bound {@code true} by a non-interactive one-shot/cron entry with no approval surface → deny at once. */
    ScopedValue<Boolean> NON_INTERACTIVE = ScopedValue.newInstance();
}
