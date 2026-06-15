/**
 * The Signal channel (P2-CH): Forvum's bridge to the Signal messenger through an
 * <strong>operator-run signal-cli daemon</strong>.
 *
 * <p><strong>Connect-only (maintainer-ratified for v0.5).</strong> Forvum does NOT spawn, install, or
 * manage signal-cli — the operator runs the daemon themselves and points the channel at it; daemon
 * spawn/install is a documented follow-up. With the Signal account already registered (or linked), the
 * operator starts the HTTP daemon, for example:
 *
 * <pre>{@code
 * signal-cli -a +15550001111 daemon --http localhost:8080
 * }</pre>
 *
 * and enables the channel in {@code $FORVUM_HOME/channels/signal.json}:
 *
 * <pre>{@code
 * {
 *   "baseUrl": "http://localhost:8080",
 *   "account": "+15550001111",
 *   "allowedUserIds": ["+15557772222"]
 * }
 * }</pre>
 *
 * <p><strong>Transport.</strong> Receives ride the daemon's Server-Sent Events stream
 * ({@code GET {baseUrl}/api/v1/events}) consumed by a hand-rolled blocking SSE reader on a virtual
 * thread (JDK {@code java.net.http} — no reactive SSE client, CLAUDE.md §3.8; the JDK HttpClient is the
 * proven native-safe path). The stream reconnects on EOF/IOException with exponential backoff (1 s
 * doubling to a 60 s cap, reset on the first healthy event). Replies are JSON-RPC 2.0 {@code send}
 * calls ({@code POST {baseUrl}/api/v1/rpc}) over a blocking REST client.
 *
 * <p><strong>Scope (v0.5).</strong> Direct text messages only: receipts, typing notifications, sync
 * messages, edited messages ({@code envelope.editMessage}), and group messages
 * ({@code dataMessage.groupInfo} present) are ignored — group and edit support are documented
 * limitations. The bot's own sends are dropped (self-echo: by {@code syncMessage}, and by an
 * own-{@code account} sender match, so a plain-{@code dataMessage} echo cannot loop the bot onto
 * itself). {@code allowedUserIds} (phone numbers and/or UUIDs) restricts who may drive a turn; an empty
 * list allows any sender (single-user convenience), and a refusal is audited WITHOUT logging the sender
 * id or the allow-list members (they are operator PII). A daemon-reported stream error
 * ({@code {"exception":{...}}}) is surfaced at WARN, not swallowed.
 */
package ai.forvum.channel.signal;
