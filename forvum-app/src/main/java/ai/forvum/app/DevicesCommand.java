package ai.forvum.app;

import ai.forvum.core.PermissionScope;
import ai.forvum.engine.pairing.Device;
import ai.forvum.engine.pairing.DeviceSpecReader;

import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Inject;

import picocli.CommandLine;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * {@code forvum devices} (P2-PAIR-SCOPE #44): list every paired device under {@code ~/.forvum/devices/}
 * with its reused identity, status (paired/revoked), and the requested-vs-approved capability scopes —
 * flagging a pending scope upgrade (requested but not yet approved) as {@code DRIFT}. A read-only
 * one-shot, parsing through the same {@link DeviceSpecReader} the engine uses so the view matches
 * what a turn would see.
 */
@CommandLine.Command(
        name = "devices",
        mixinStandardHelpOptions = true,
        description = "List paired devices and their requested-vs-approved scopes.")
public class DevicesCommand implements Callable<Integer> {

    @Inject
    DeviceConfigStore store;

    @Override
    public Integer call() {
        List<String> ids = store.ids();
        if (ids.isEmpty()) {
            System.out.println("No devices are paired. Pair one by adding ~/.forvum/devices/<id>.json.");
            return 0;
        }
        DeviceSpecReader specReader = new DeviceSpecReader();
        for (String id : ids) {
            Device device;
            try {
                Optional<ObjectNode> node = store.read(id);
                if (node.isEmpty()) {
                    continue; // listed but vanished between list and read
                }
                device = specReader.parse(id, node.get());
            } catch (RuntimeException e) {
                // a non-object/malformed device file or a bad scope name → one clean line, never a crash
                System.out.printf("%-16s  (invalid: %s)%n", id, e.getMessage());
                continue;
            }
            String status = device.revoked() ? "revoked" : "paired";
            StringBuilder line = new StringBuilder();
            line.append(String.format("%-16s  identity=%-12s  %-7s", id, device.identityId(), status));
            line.append("  requested=").append(new TreeSet<>(device.requestedScopes()));
            line.append("  approved=").append(new TreeSet<>(device.approvedScopes()));
            if (device.hasScopeDrift()) {
                TreeSet<PermissionScope> pending = new TreeSet<>(device.requestedScopes());
                pending.removeAll(device.approvedScopes());
                line.append("  DRIFT(pending=").append(pending).append(")");
            }
            if (device.decisionReason() != null && !device.decisionReason().isBlank()) {
                line.append("  reason=\"").append(device.decisionReason()).append('"');
            }
            System.out.println(line);
        }
        return 0;
    }
}
