package ai.forvum.engine.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Reads raw authorization-role definitions from {@code $FORVUM_HOME/roles/<name>.json} (P2-11). */
@Singleton
public class RoleReader extends JsonDirectoryReader {

    @Inject
    public RoleReader(ConfigLoader loader, ForvumHome home) {
        super(loader, home.roles());
    }
}
