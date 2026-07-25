package ai.forvum.engine.skills;

import ai.forvum.engine.config.ConfigLoader;
import ai.forvum.engine.config.ForvumHome;
import ai.forvum.engine.config.SkillReader;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Test-only factory (in the {@code skills} package, so it can wire the provider's package-private
 * {@code skills}/{@code mapper} fields) that assembles a fully-wired {@link SkillToolProvider} rooted at a
 * {@code @TempDir} home for tests in other packages — e.g. the {@code SupervisorGraph} skill-invoke
 * integration test — without exposing those fields on the production class.
 */
public final class SkillToolFixtures {

    private SkillToolFixtures() {
    }

    /** A {@link SkillToolProvider} reading skills from {@code home/skills/} on demand. */
    public static SkillToolProvider provider(Path home) {
        SkillToolProvider provider = new SkillToolProvider();
        ObjectMapper mapper = new ObjectMapper();
        provider.mapper = mapper;
        provider.skills = new SkillReader(new ConfigLoader(mapper), new ForvumHome(Optional.of(home.toString())));
        return provider;
    }
}
