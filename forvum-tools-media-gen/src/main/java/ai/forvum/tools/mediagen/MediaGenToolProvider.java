package ai.forvum.tools.mediagen;

import ai.forvum.core.ToolSpec;

import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;
import ai.forvum.sdk.GeneratedMedia;
import ai.forvum.sdk.GenerationProvider;
import ai.forvum.sdk.GenerationRequest;
import ai.forvum.sdk.MediaKind;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The media-generation tool extension (#187). Contributes {@code image.generate} / {@code video.generate}
 * / {@code music.generate} to the engine's global ToolRegistry (which discovers this
 * {@code @ApplicationScoped} bean via CDI) and (M18 Option A) executes each through
 * {@link #invoke(String, Map)}: route the requested {@link MediaKind} to an installed
 * {@link GenerationProvider} (discovered via CDI {@link Instance} — any Layer-3 backend on the classpath,
 * not just the bundled one), generate, and persist the asset under the workspace {@code media/} outbox.
 * The engine's {@code ToolExecutor} is the single belt + RBAC
 * ({@link ai.forvum.core.PermissionScope#MEDIA_GENERATE}) gate and audits every call; this provider only
 * dispatches an already-permitted call (no reflection, no AI library).
 *
 * <p>{@link #tools()} returns CONSTANT SPECS unconditionally (zero boot IO — the P2-13
 * {@code ToolRegistry.onStart} lesson): the tools are always visible, and an absent
 * {@code tools/media-gen.json} yields an actionable "not configured" error at invoke time, never a
 * boot-time probe or crash.
 *
 * <p><strong>Backend selection.</strong> Candidates are the installed providers whose
 * {@link GenerationProvider#supportedKinds()} contain the requested kind; an
 * {@link GenerationProvider#isActive() active} candidate is preferred, else the first candidate is still
 * invoked so ITS actionable "not configured" error (naming the exact file to create) reaches the model.
 * No candidate at all yields a capability-gap error (a backend for that kind is a Java plugin — the
 * documented native trade-off, ULTRAPLAN §6.2/§6.3).
 */
@ForvumExtension
@ApplicationScoped
public class MediaGenToolProvider extends AbstractToolProvider {

    @Inject
    Instance<GenerationProvider> generationProviders;

    @Inject
    MediaGenConfig config;

    /**
     * The shared workspace root ({@code forvum.workspace.root}, else {@code $HOME/.forvum/workspace}) — the
     * same root the filesystem/shell/tts tools use, so the {@code media/} outbox lands under one workspace.
     * Resolved lazily (never required to exist at boot), so the native no-{@code ~/.forvum} smoke is clean.
     */
    @ConfigProperty(name = "forvum.workspace.root")
    Optional<String> configuredWorkspaceRoot;

    @Override
    public String extensionId() {
        return "media-gen";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(MediaGenerateTools.IMAGE_SPEC, MediaGenerateTools.VIDEO_SPEC,
                MediaGenerateTools.MUSIC_SPEC);
    }

    /**
     * The generation tools' config gap (#184): the bundled backend is inert until the operator points it
     * at an endpoint, so the discovery surfaces ({@code forvum tools}/{@code doctor}) name the exact file
     * + field. Only {@code image.generate} is flagged — the bundled backend serves images; video/music
     * need an installed backend (a capability gap the invoke-time error explains, not a config gap).
     */
    @Override
    public Map<String, String> configGaps() {
        boolean ready;
        try {
            ready = config.read().isReady();
        } catch (RuntimeException e) {
            ready = false;
        }
        if (!ready) {
            return Map.of("image.generate", "set \"baseUrl\" in ~/.forvum/tools/media-gen.json");
        }
        return Map.of();
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        MediaKind kind = switch (toolName) {
            case "image.generate" -> MediaKind.IMAGE;
            case "video.generate" -> MediaKind.VIDEO;
            case "music.generate" -> MediaKind.MUSIC;
            default -> throw new IllegalArgumentException(
                    "MediaGenToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides image.generate, video.generate, music.generate.");
        };
        String prompt = stringOrNull(arguments.get("prompt"));
        if (prompt == null || prompt.isBlank()) {
            throw new MediaGenException(toolName + " requires a non-blank 'prompt' argument.");
        }
        GenerationProvider provider = selectProvider(toolName, kind, generationProviders);
        GeneratedMedia media = provider.generate(new GenerationRequest(kind, prompt));
        return MediaOutbox.write(workspaceRoot(), media);
    }

    /**
     * Select the backend for {@code kind}: prefer an active candidate; fall back to the first inactive
     * one so its own actionable "not configured" error reaches the model; no candidate is a capability gap.
     * Pure (static, {@link Iterable} in) for direct unit testing.
     */
    static GenerationProvider selectProvider(String toolName, MediaKind kind,
                                             Iterable<GenerationProvider> providers) {
        GenerationProvider firstCandidate = null;
        for (GenerationProvider candidate : providers) {
            if (!candidate.supportedKinds().contains(kind)) {
                continue;
            }
            if (candidate.isActive()) {
                return candidate;
            }
            if (firstCandidate == null) {
                firstCandidate = candidate;
            }
        }
        if (firstCandidate == null) {
            throw new MediaGenException(toolName + ": no installed generation backend supports "
                    + kind.name().toLowerCase(Locale.ROOT) + " generation. Install a GenerationProvider "
                    + "plugin that supports it (a Java plugin — rebuild forvum-app to bundle it).");
        }
        return firstCandidate;
    }

    /** The workspace root, mirroring the filesystem/shell/tts {@code WorkspaceRootProducer} resolution. */
    Path workspaceRoot() {
        return configuredWorkspaceRoot
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".forvum", "workspace"));
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
