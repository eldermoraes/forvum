package ai.forvum.channel.discord.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code d} payload of the {@code op 6} RESUME frame the client sends after reconnecting to the
 * {@code resume_gateway_url} to continue a dropped session instead of opening a fresh one (IDENTIFY):
 *
 * <ul>
 *   <li>{@code token} — the bot token (a per-deployment secret from {@code channels/discord.json}); it
 *       must never reach the logs (the RESUME frame is serialized only to the socket, never logged).</li>
 *   <li>{@code session_id} — the session captured from the READY dispatch.</li>
 *   <li>{@code seq} — the last sequence number received; the gateway replays every missed event after
 *       it, then sends the {@code RESUMED} dispatch.</li>
 * </ul>
 *
 * A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module): like IDENTIFY/HEARTBEAT, this
 * is an OUTBOUND Jackson-serialized record — without the hint the native binary emits an empty/malformed
 * frame and the resume silently fails, and the no-token native smoke cannot catch it (the NATIVE-FRAME
 * lesson, CLAUDE.md §14 [P2-CH/discord]); pinned by an encode-path test.
 */
@RegisterForReflection
public record Resume(String token,
                     @JsonProperty("session_id") String sessionId,
                     long seq) {
}
