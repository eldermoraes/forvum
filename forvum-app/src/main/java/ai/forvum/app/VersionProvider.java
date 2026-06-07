package ai.forvum.app;

import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.ConfigProvider;

import picocli.CommandLine;

/**
 * Sources the {@code --version} string from the build — {@code quarkus.application.version}, which Quarkus
 * sets to the Maven project version and bakes into the native image at build time — so it cannot drift
 * from the POM (M20). A CDI bean: quarkus-picocli's {@code IFactory} instantiates it via ArC, so no
 * runtime reflection is needed for the native image.
 */
@Singleton
public class VersionProvider implements CommandLine.IVersionProvider {

    @Override
    public String[] getVersion() {
        String version = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.application.version", String.class)
                .orElse("unknown");
        return new String[] {"Forvum " + version};
    }
}
