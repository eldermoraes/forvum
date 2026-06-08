package ai.forvum.engine.plugin;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.file.Path;

/**
 * The outcome of a {@code forvum plugin install <coords>} (P2-6): the resolved Maven {@code coordinates}
 * (groupId:artifactId:version, the canonical form the resolver echoed back), the {@code resolvedJar} the
 * resolver fetched into the local Maven cache, and the {@code installedJar} streamed into
 * {@code ~/.forvum/plugins/}. The drop-in directory is fast-jar-only by design (§6.2/§6.3) — the native
 * binary cannot load a JAR added after build, so the command tells native users to rebuild.
 *
 * <p>A plain view record built from a successful resolution and printed by the CLI; it is never
 * JSON-serialized over a channel. It carries {@link RegisterForReflection} only to keep the Layer-2 DTO
 * enforcer satisfied (every {@code forvum-engine} record does), not because any framework reflects on it.
 */
@RegisterForReflection
public record PluginInstallResult(String coordinates, Path resolvedJar, Path installedJar) {

    public PluginInstallResult {
        if (coordinates == null || coordinates.isBlank()) {
            throw new IllegalStateException("PluginInstallResult coordinates must be non-blank.");
        }
        if (resolvedJar == null) {
            throw new IllegalStateException("PluginInstallResult resolvedJar must be non-null.");
        }
        if (installedJar == null) {
            throw new IllegalStateException("PluginInstallResult installedJar must be non-null.");
        }
    }
}
