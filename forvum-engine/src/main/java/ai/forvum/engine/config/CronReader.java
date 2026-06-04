package ai.forvum.engine.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Reads raw cron-job definitions from {@code $FORVUM_HOME/crons/<id>.json}. */
@Singleton
public class CronReader extends JsonDirectoryReader {

    @Inject
    public CronReader(ConfigLoader loader, ForvumHome home) {
        super(loader, home.crons());
    }
}
