package ai.forvum.channel.matrix.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The (empty) request body for the Matrix {@code POST /_matrix/client/v3/join/{roomId}} endpoint:
 * serializes to {@code {}}. Forvum never sends {@code third_party_signed}, but homeservers expect a
 * JSON body on the POST, so an explicit empty object is sent rather than a zero-length entity.
 *
 * <p><strong>OUTBOUND frame.</strong> Jackson-SERIALIZED by the REST client, so it carries a real
 * {@code @RegisterForReflection} (the Discord native-frame trap); {@code OutboundFrameEncodeTest} pins
 * the {@code {}} shape.
 */
@RegisterForReflection
public record JoinRequest() {
}
