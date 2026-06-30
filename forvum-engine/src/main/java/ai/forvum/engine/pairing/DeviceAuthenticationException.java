package ai.forvum.engine.pairing;

/**
 * Thrown by {@link DeviceRegistry#authenticate} at the turn entry when a device PRESENTS a credential
 * that fails authentication (#166): a token that does not match the paired device's shared secret, a
 * missing token where the device requires one, or a credential whose claimed device id does not match
 * the channel it arrived on (cross-channel / cross-device). The turn is rejected BEFORE the responder
 * runs, so an unauthenticated device never reaches the model nor a tool.
 *
 * <p>It extends {@link DeviceNotPairedException} so {@code TurnService.dispatch} surfaces every device
 * rejection — unpaired (P2-4), revoked (P2-4), and unauthenticated (#166) — through one terminal
 * {@code ErrorEvent} path, and so the existing P2-4 catch handling keeps working.
 *
 * <p><strong>Secret hygiene (#166 acceptance).</strong> The message NEVER contains the presented or the
 * expected token — only the device id and the failure kind — so a logged or echoed error cannot leak the
 * shared secret.
 */
public class DeviceAuthenticationException extends DeviceNotPairedException {

    public DeviceAuthenticationException(String message) {
        super(message);
    }
}
