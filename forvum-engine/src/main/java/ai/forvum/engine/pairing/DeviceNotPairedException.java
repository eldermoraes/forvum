package ai.forvum.engine.pairing;

/**
 * Thrown by {@link DeviceRegistry#requirePaired} at the turn entry when an inbound turn arrives on a
 * device that is not paired — it is unknown (no {@code devices/<id>.json}), or it is declared but
 * {@code revoked} (P2-4, ULTRAPLAN section 7.2 item 4). The turn is rejected BEFORE the responder runs,
 * so an unpaired device never reaches the model nor the memory namespace.
 *
 * <p>Engine-local and a {@link RuntimeException}: {@code TurnService.dispatch} already converts a thrown
 * turn into a terminal {@code ErrorEvent} on the channel's sink, so a self-driving channel does not crash.
 */
public class DeviceNotPairedException extends RuntimeException {

    public DeviceNotPairedException(String message) {
        super(message);
    }
}
