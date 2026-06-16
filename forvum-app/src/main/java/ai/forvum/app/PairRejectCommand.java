package ai.forvum.app;

import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code forvum pair reject <device-id> [--reason "..."]} (P2-PAIR-SCOPE #44): refuse a paired device's
 * requested scopes by revoking it, recording a reason code. Revocation reuses the P2-4 {@code revoked}
 * flag — a revoked device is rejected at the turn entry exactly like an unknown one — so a refused scope
 * upgrade disables the device until it is re-approved. A one-shot: it only reads + rewrites the device file.
 */
@CommandLine.Command(
        name = "reject",
        description = "Reject a paired device's requested scopes (revokes the device).")
public class PairRejectCommand implements Callable<Integer> {

    @Inject
    DeviceConfigStore store;

    @CommandLine.Parameters(arity = "1", paramLabel = "<device-id>",
            description = "The devices/<id>.json stem to reject.")
    String deviceId;

    @CommandLine.Option(names = "--reason",
            description = "A reason code recorded with the rejection (e.g. \"scope too broad\").")
    String reason;

    @Override
    public Integer call() {
        Optional<ObjectNode> existing;
        try {
            existing = store.read(deviceId);
        } catch (RuntimeException e) {
            System.err.println("pair reject failed: " + e.getMessage());
            return 1;
        }
        if (existing.isEmpty()) {
            System.err.println("pair reject failed: device '" + deviceId + "' is not paired (no devices/"
                    + deviceId + ".json).");
            return 1;
        }
        ObjectNode node = existing.get();
        node.put("revoked", true);
        // decisionReason records the LAST decision: set it when given, else clear any stale prior reason.
        if (reason != null && !reason.isBlank()) {
            node.put("decisionReason", reason);
        } else {
            node.remove("decisionReason");
        }
        store.write(deviceId, node);
        System.out.println("Rejected device '" + deviceId + "' (revoked)"
                + (reason != null && !reason.isBlank() ? " (reason: " + reason + ")" : "")
                + ". Re-approve with `forvum pair approve " + deviceId + "` to restore access.");
        return 0;
    }
}
