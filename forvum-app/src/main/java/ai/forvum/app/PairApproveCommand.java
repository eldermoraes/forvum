package ai.forvum.app;

import ai.forvum.core.PermissionScope;
import ai.forvum.engine.pairing.Device;
import ai.forvum.engine.pairing.DeviceSpecReader;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * {@code forvum pair approve <device-id> [--scopes FS_READ,FS_WRITE] [--reason "..."]} (P2-PAIR-SCOPE
 * #44): grant a paired device the capability scopes it requested, recording a reason code. With
 * {@code --scopes} omitted, every requested scope is approved; a {@code --scopes} value must be a subset
 * of what the device requested (an operator grants what was asked — to widen the request, edit the device
 * file). Approving also clears {@code revoked}. A one-shot: it only reads + rewrites the device file.
 */
@CommandLine.Command(
        name = "approve",
        description = "Approve a paired device's requested scopes (clears any revocation).")
public class PairApproveCommand implements Callable<Integer> {

    @Inject
    DeviceConfigStore store;

    @CommandLine.Parameters(arity = "1", paramLabel = "<device-id>",
            description = "The devices/<id>.json stem to approve.")
    String deviceId;

    @CommandLine.Option(names = "--scopes", split = ",", paramLabel = "<SCOPE>",
            description = "Scopes to approve (comma-separated). Defaults to every requested scope.")
    Set<String> scopes;

    @CommandLine.Option(names = "--reason",
            description = "A reason code recorded with the decision (e.g. \"trusted device\").")
    String reason;

    @Override
    public Integer call() {
        Optional<ObjectNode> existing = store.read(deviceId);
        if (existing.isEmpty()) {
            System.err.println("pair approve failed: device '" + deviceId + "' is not paired (no devices/"
                    + deviceId + ".json). Pair it first.");
            return 1;
        }
        ObjectNode node = existing.get();
        Device device;
        try {
            device = new DeviceSpecReader().parse(deviceId, node);
        } catch (IllegalStateException e) {
            System.err.println("pair approve failed: " + e.getMessage());
            return 1;
        }

        Set<PermissionScope> approved;
        if (scopes == null || scopes.isEmpty()) {
            approved = new TreeSet<>(device.requestedScopes());
        } else {
            approved = new TreeSet<>();
            for (String raw : scopes) {
                PermissionScope scope;
                try {
                    scope = PermissionScope.fromName(raw.strip());
                } catch (IllegalStateException e) {
                    System.err.println("pair approve failed: unknown scope '" + raw.strip() + "'.");
                    return 1;
                }
                if (!device.requestedScopes().contains(scope)) {
                    System.err.println("pair approve failed: device '" + deviceId + "' did not request "
                            + scope + " (requested: " + new TreeSet<>(device.requestedScopes())
                            + "). Edit the device file to widen the request.");
                    return 1;
                }
                approved.add(scope);
            }
        }

        ArrayNode approvedArray = node.putArray("approvedScopes");
        for (PermissionScope scope : approved) {
            approvedArray.add(scope.name());
        }
        node.put("revoked", false);
        if (reason != null && !reason.isBlank()) {
            node.put("decisionReason", reason);
        }
        store.write(deviceId, node);

        Set<PermissionScope> pending = new LinkedHashSet<>(device.requestedScopes());
        pending.removeAll(approved);
        System.out.println("Approved device '" + deviceId + "' scopes: " + approved
                + (reason != null && !reason.isBlank() ? " (reason: " + reason + ")" : ""));
        if (!pending.isEmpty()) {
            System.out.println("Still pending (requested, not approved): " + new TreeSet<>(pending));
        }
        return 0;
    }
}
