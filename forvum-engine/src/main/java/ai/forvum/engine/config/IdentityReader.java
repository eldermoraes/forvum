package ai.forvum.engine.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Reads raw identity records from {@code $FORVUM_HOME/identities/<id>.json}. */
@Singleton
public class IdentityReader extends JsonDirectoryReader {

    @Inject
    public IdentityReader(ConfigLoader loader, ForvumHome home) {
        super(loader, home.identities());
    }
}
