package ai.forvum.tools.tts;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The text-to-speech tool extension (#186). Contributes the {@code tts.speak} tool to the engine's global
 * ToolRegistry, which discovers this {@code @ApplicationScoped} bean via CDI and (M18 Option A) executes
 * it through {@link #invoke(String, Map)}. The engine's {@code ToolExecutor} is the single belt + RBAC
 * ({@link ai.forvum.core.PermissionScope#MEDIA_SYNTHESIZE}) gate and audits every call; this provider
 * only, on a permitted call, reads the on-demand {@code tools/tts.json} config, validates the arguments,
 * and drives piper — it never gates or audits (no reflection, no AI library).
 *
 * <p>{@link #tools()} returns a CONSTANT SPEC unconditionally (zero boot IO — the P2-13 {@code
 * ToolRegistry.onStart} lesson): the tool is always visible, and an absent/unconfigured
 * {@code tools/tts.json} yields an actionable "not configured" error at invoke time, never a boot-time
 * probe or crash.
 */
@ForvumExtension
@ApplicationScoped
public class TtsToolProvider extends AbstractToolProvider {

    @Inject
    TtsConfig config;

    @Inject
    SubprocessRunner runner;

    /**
     * The shared workspace root ({@code forvum.workspace.root}, else {@code $HOME/.forvum/workspace}) — the
     * same root the filesystem/shell tools use, so a {@code tts/} subdir is written under one workspace.
     * Resolved lazily (never required to exist at boot), so the native no-{@code ~/.forvum} smoke is clean.
     */
    @ConfigProperty(name = "forvum.workspace.root")
    Optional<String> configuredWorkspaceRoot;

    @Override
    public String extensionId() {
        return "tts";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(TtsSpeakTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        if (!"tts.speak".equals(toolName)) {
            throw new IllegalArgumentException(
                    "TtsToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides tts.speak.");
        }
        String text = stringOrNull(arguments.get("text"));
        String voice = stringOrNull(arguments.get("voice"));

        TtsConfig.Spec spec = config.read();
        TtsSynthesizer synthesizer = new TtsSynthesizer(runner, workspaceRoot());
        return synthesizer.synthesize(text, voice, spec);
    }

    /** The workspace root, mirroring the filesystem/shell {@code WorkspaceRootProducer} resolution. */
    Path workspaceRoot() {
        return configuredWorkspaceRoot
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".forvum", "workspace"));
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
