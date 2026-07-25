package ai.forvum.tools.multimodal;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;
import ai.forvum.sdk.MediaAnalysis;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The multimodal tool extension (#185): contributes {@code image.analyze} / {@code pdf.analyze} to the
 * engine's global ToolRegistry (which discovers this {@code @ApplicationScoped} bean via CDI) and
 * self-dispatches each by name (M18 Option A, no reflection) to the tool classes, which confine + read the
 * workspace media and drive the injected engine-backed {@link MediaAnalysis} seam. The engine's
 * {@code ToolExecutor} is the single belt + RBAC ({@link ai.forvum.core.PermissionScope#MEDIA_ANALYZE}) gate
 * and audits every call; this provider only dispatches an already-permitted call.
 *
 * <p>{@link #tools()} returns a CONSTANT SPEC unconditionally (zero boot IO — the P2-13 {@code
 * ToolRegistry.onStart} lesson). The tools are functional with no config (the vision model defaults to the
 * agent's primary — a runtime property, not a config gap), so there is NO {@code configGaps} override.
 */
@ForvumExtension
@ApplicationScoped
public class MultimodalToolProvider extends AbstractToolProvider {

    @Inject
    MediaAnalysis mediaAnalysis;

    @Inject
    WorkspaceRoot workspaceRoot;

    @Inject
    MultimodalToolConfig config;

    @Override
    public String extensionId() {
        return "multimodal";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(ImageAnalyzeTool.SPEC, PdfAnalyzeTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        MultimodalToolConfig.Spec spec = config.read();
        return switch (toolName) {
            case "image.analyze" -> ImageAnalyzeTool.analyze(
                    mediaAnalysis, workspaceRoot, spec, pathsArg(arguments), stringOrNull(arguments.get("prompt")));
            case "pdf.analyze" -> PdfAnalyzeTool.analyze(
                    mediaAnalysis, workspaceRoot, spec, stringArg(arguments, "path"),
                    stringOrNull(arguments.get("prompt")));
            default -> throw new IllegalArgumentException(
                    "MultimodalToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides image.analyze, pdf.analyze.");
        };
    }

    /** Extract the required {@code paths} array argument as a {@code List<String>}. */
    private static List<String> pathsArg(Map<String, Object> arguments) {
        Object value = arguments.get("paths");
        if (value == null) {
            throw new IllegalArgumentException("image.analyze requires a 'paths' array argument.");
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "image.analyze 'paths' must be a JSON array of strings, got: "
                  + value.getClass().getSimpleName());
        }
        List<String> paths = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element == null) {
                throw new IllegalArgumentException("image.analyze 'paths' must not contain null elements.");
            }
            paths.add(element.toString());
        }
        return paths;
    }

    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument '" + key + "' for pdf.analyze.");
        }
        return value.toString();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
