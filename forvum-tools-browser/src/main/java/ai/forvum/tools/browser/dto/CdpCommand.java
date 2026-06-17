package ai.forvum.tools.browser.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An OUTBOUND Chrome DevTools Protocol (CDP) command frame:
 * {@code { "id": <n>, "method": "Domain.method", "params": {...}, "sessionId": "..." }}.
 *
 * <p>{@code id} is a monotonic per-session integer the client allocates so the matching response
 * ({@code { "id": <n>, "result": {...} }}) can be correlated back to its caller. {@code params} is the
 * command-specific arguments, kept as a raw {@link JsonNode} so the envelope is generic (the per-command
 * builders in {@code CdpProtocol} assemble it). {@code sessionId} routes the command to an attached target
 * when flatten-mode sessions are used; it is omitted from the wire when {@code null} (the v0.1 path attaches
 * directly to a page target, so it is normally absent).
 *
 * <p><strong>Native-reflection trap [P2-CH/discord].</strong> Every command frame is serialized via
 * {@code ObjectMapper.writeValueAsString} in the native image. Without a real
 * {@code io.quarkus.runtime.annotations.RegisterForReflection} (Layer-3 is Quarkus-bearing, NOT the SDK
 * re-export) the native binary cannot reflect this record's accessors and emits a malformed/empty frame —
 * and CDP silently no-ops. The no-config native smoke cannot catch it (no Chrome attached), so the
 * annotation is mandatory and pinned by a non-live encode test asserting the JSON carries
 * {@code id}/{@code method}/{@code params}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CdpCommand(long id, String method, JsonNode params, String sessionId) {

    /** A command with no session routing ({@code sessionId} omitted from the wire). */
    public CdpCommand(long id, String method, JsonNode params) {
        this(id, method, params, null);
    }
}
