package ai.forvum.engine.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Reads raw per-channel configuration from {@code $FORVUM_HOME/channels/<id>.json}. */
@Singleton
public class ChannelReader extends JsonDirectoryReader {

    @Inject
    public ChannelReader(ConfigLoader loader, ForvumHome home) {
        super(loader, home.channels());
    }
}
