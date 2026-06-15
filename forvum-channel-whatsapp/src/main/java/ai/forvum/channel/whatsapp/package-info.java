/**
 * The WhatsApp channel (P2-CH): Forvum's bridge to WhatsApp through the
 * <strong>Meta WhatsApp Business Cloud API</strong>.
 *
 * <p><strong>Webhook inbound.</strong> Meta delivers inbound messages to a webhook the operator
 * registers in the Meta App dashboard. {@link ai.forvum.channel.whatsapp.WhatsAppWebhook} serves it as
 * two {@code @Route} handlers over the bundled {@code vertx-http} server: a {@code GET} verification
 * handshake (echoes {@code hub.challenge} when {@code hub.verify_token} matches the configured
 * {@code verifyToken}) and a signed {@code POST} whose {@code X-Hub-Signature-256} HMAC is validated
 * against the {@code appSecret} over the raw body BEFORE any processing. A valid POST is acked with
 * {@code 200} immediately and its text messages are driven on virtual-thread workers (Meta retries an
 * un-acked delivery, which would duplicate turns).
 *
 * <p><strong>Public ingress is the operator's concern.</strong> Meta's servers must be able to reach the
 * webhook, so the {@code vertx-http} port has to be publicly reachable (a reverse proxy / tunnel). Forvum
 * does not provision ingress — a documented limitation.
 *
 * <p><strong>Configuration</strong> ({@code $FORVUM_HOME/channels/whatsapp.json}):
 *
 * <pre>{@code
 * {
 *   "verifyToken": "<operator-chosen token, also set in the Meta dashboard>",
 *   "appSecret":   "<Meta app secret — validates inbound signatures>",
 *   "accessToken": "<Graph API bearer token — sends replies>",
 *   "phoneNumberId": "<WhatsApp Business phone-number id>",
 *   "allowedUserIds": ["15551234567"]
 * }
 * }</pre>
 *
 * <p><strong>Outbound.</strong> Replies are Graph API {@code POST {phone-number-id}/messages} calls
 * ({@link ai.forvum.channel.whatsapp.GraphApi}) over a blocking REST client; the access token rides the
 * {@code Authorization: Bearer} header, never the URL.
 *
 * <p><strong>Scope (v0.5).</strong> Direct text messages only: delivery/read {@code statuses}, non-text
 * messages (image/audio/location/…), and reactions are ignored. {@code allowedUserIds} (WhatsApp
 * {@code wa_id} phone numbers) restricts who may drive a turn; an empty list allows any sender
 * (single-user convenience), and a refusal is audited WITHOUT logging the sender id or the allow-list
 * members (they are operator PII). The webhook delivers only inbound user messages, so there is no
 * self-echo loop (unlike Signal).
 *
 * <p><strong>At-least-once delivery (documented limitation).</strong> The ack-before-process ordering
 * defends against Meta re-sending an <em>un-acked</em> notification (the duplicate-turn trap), but the
 * Cloud API is at-least-once: it may redeliver the SAME message (same {@code wamid}) even after a
 * {@code 200}. v0.5 has no dedup store, so a genuine redelivery can re-drive a turn; idempotency keyed by
 * {@code wamid} (already parsed onto {@link ai.forvum.channel.whatsapp.WhatsAppEvents.InboundMessage}) is
 * a follow-up.
 */
package ai.forvum.channel.whatsapp;
