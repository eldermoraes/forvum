package ai.forvum.tools.mediagen;

import ai.forvum.core.PermissionScope;
import ai.forvum.core.ToolSpec;

/**
 * The three media-generation tool specs (#187): {@code image.generate} / {@code video.generate} /
 * {@code music.generate}, all gated by ONE scope ({@link PermissionScope#MEDIA_GENERATE}) because they
 * share the prompt-to-asset shape and their only external effect is a generation-backend spend plus a
 * workspace write. Each declares {@code userConfirmRequired = false} (the 4-argument constructor): the
 * model controls only the DATA (the prompt); the backend, its endpoint, the model, and the output
 * location are all operator/tool-fixed — the same posture as {@code tts.speak} and {@code fs.write}
 * ({@code shell.exec} confirms only because the model chooses the program).
 *
 * <p>The parameters schema is a single required {@code prompt} string per tool, so the engine's
 * {@code ToolCallBridge} needs no new schema shape. Executed by {@link MediaGenToolProvider}, which
 * routes each kind to an installed {@link ai.forvum.sdk.GenerationProvider}.
 */
public final class MediaGenerateTools {

    /** {@code image.generate}: prompt → image file under the workspace {@code media/} outbox. */
    public static final ToolSpec IMAGE_SPEC = new ToolSpec(
            "image.generate",
            "Generate an image from a natural-language prompt using the operator-configured generation "
          + "backend. The image is written under the workspace media/ directory; returns the "
          + "workspace-relative path to the generated file.",
            PermissionScope.MEDIA_GENERATE,
            promptSchema("the natural-language description of the image to generate"));

    /** {@code video.generate}: prompt → video file under the workspace {@code media/} outbox. */
    public static final ToolSpec VIDEO_SPEC = new ToolSpec(
            "video.generate",
            "Generate a video from a natural-language prompt using an installed generation backend that "
          + "supports video. The video is written under the workspace media/ directory; returns the "
          + "workspace-relative path to the generated file.",
            PermissionScope.MEDIA_GENERATE,
            promptSchema("the natural-language description of the video to generate"));

    /** {@code music.generate}: prompt → audio file under the workspace {@code media/} outbox. */
    public static final ToolSpec MUSIC_SPEC = new ToolSpec(
            "music.generate",
            "Generate music from a natural-language prompt using an installed generation backend that "
          + "supports music. The audio is written under the workspace media/ directory; returns the "
          + "workspace-relative path to the generated file.",
            PermissionScope.MEDIA_GENERATE,
            promptSchema("the natural-language description of the music to generate"));

    private static String promptSchema(String description) {
        return "{\"type\":\"object\",\"properties\":{"
             + "\"prompt\":{\"type\":\"string\",\"description\":\"" + description + "\"}},"
             + "\"required\":[\"prompt\"]}";
    }

    private MediaGenerateTools() {
    }
}
