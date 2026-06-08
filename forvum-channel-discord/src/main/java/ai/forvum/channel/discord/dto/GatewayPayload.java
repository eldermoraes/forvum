package ai.forvum.channel.discord.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The Discord Gateway v10 frame envelope (every gateway message has this shape):
 * {@code { "op": <opcode>, "d": <event data>, "s": <sequence>, "t": <event name> }}.
 *
 * <ul>
 *   <li>{@code op} — the gateway opcode (10 HELLO, 0 DISPATCH, 1 HEARTBEAT, 11 HEARTBEAT_ACK,
 *       7 RECONNECT, 9 INVALID_SESSION, ...).</li>
 *   <li>{@code d} — the opcode-specific payload, kept as a raw {@link JsonNode} so the envelope is
 *       generic; the per-opcode record (e.g. {@link Hello}, {@link Ready}, {@link MessageCreate}) is
 *       decoded from it only for the opcodes Forvum handles.</li>
 *   <li>{@code s} — the sequence number; present (non-null) only on {@code op 0} (DISPATCH). It is the
 *       value echoed in the next heartbeat. Nullable.</li>
 *   <li>{@code t} — the dispatch event name (e.g. {@code "READY"}, {@code "MESSAGE_CREATE"}); present
 *       only on {@code op 0}. Nullable.</li>
 * </ul>
 *
 * A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayPayload(int op, JsonNode d, Long s, String t) {
}
