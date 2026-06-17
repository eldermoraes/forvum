package ai.forvum.tools.browser.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An INBOUND Chrome DevTools Protocol (CDP) message — either a command RESPONSE
 * ({@code { "id": <n>, "result": {...} }} or {@code { "id": <n>, "error": {...} }}) or an unsolicited
 * EVENT ({@code { "method": "Domain.event", "params": {...} }}). A single generic envelope with raw
 * {@link JsonNode} payloads (mirrors the discord {@code GatewayPayload}) so per-domain result shapes are
 * tree-walked rather than bound to many typed POJOs — this keeps the native reflection surface to the two
 * DTO records.
 *
 * <ul>
 *   <li>{@code id} — present (non-null) on a command response; correlates to the {@code CdpCommand.id}.</li>
 *   <li>{@code result} — the command's successful result payload (a response with no {@code error}).</li>
 *   <li>{@code error} — the command's error payload ({@code { "code": ..., "message": ... }}).</li>
 *   <li>{@code method} — the event name (e.g. {@code "Page.loadEventFired"}); present only on an event.</li>
 *   <li>{@code params} — the event's payload; present only on an event.</li>
 * </ul>
 *
 * A real {@code RegisterForReflection} (Quarkus-bearing Layer-3 module) plus
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} so future protocol fields do not break decoding.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record CdpMessage(Long id, JsonNode result, JsonNode error, String method, JsonNode params) {

    /** Whether this is a command response (carries an {@code id}), versus an unsolicited event. */
    public boolean isResponse() {
        return id != null;
    }

    /** Whether this response carries a CDP error payload. */
    public boolean isError() {
        return error != null && !error.isNull();
    }
}
