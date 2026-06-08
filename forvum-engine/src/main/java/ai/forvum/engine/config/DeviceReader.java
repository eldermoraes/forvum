package ai.forvum.engine.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Reads raw paired-device declarations from {@code $FORVUM_HOME/devices/<id>.json} (P2-4). */
@Singleton
public class DeviceReader extends JsonDirectoryReader {

    @Inject
    public DeviceReader(ConfigLoader loader, ForvumHome home) {
        super(loader, home.devices());
    }
}
