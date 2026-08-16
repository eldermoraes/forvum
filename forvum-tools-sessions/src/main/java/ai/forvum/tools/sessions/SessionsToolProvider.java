package ai.forvum.tools.sessions;

import ai.forvum.core.ToolSpec;
import ai.forvum.sdk.AbstractToolProvider;
import ai.forvum.sdk.ForvumExtension;
import ai.forvum.sdk.SessionAccess;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * The #189 sessions tool provider: contributes model-callable {@code agents.list} / {@code sessions.list} /
 * {@code sessions.history} ({@code SESSION_READ}) and {@code sessions.send} ({@code SESSION_WRITE}) to the
 * engine's global ToolRegistry (which discovers this {@code @ApplicationScoped} bean via CDI) and
 * self-dispatches each by name (M18 Option A, no reflection) to the injected {@link SessionAccess} seam,
 * whose engine implementation does the identity-scoped, fail-closed read/dispatch over the
 * {@code sessions}/{@code messages} ledger and the turn driver. The engine's ToolExecutor gates permission
 * and audits every call; this provider only dispatches an already-permitted call.
 */
@ForvumExtension
@ApplicationScoped
public class SessionsToolProvider extends AbstractToolProvider {

    @Inject
    SessionAccess sessions;

    @Override
    public String extensionId() {
        return "sessions";
    }

    @Override
    public List<ToolSpec> tools() {
        return List.of(AgentsListTool.SPEC, SessionsListTool.SPEC, SessionsHistoryTool.SPEC,
                SessionsSendTool.SPEC);
    }

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "agents.list" -> AgentsListTool.list(sessions);
            case "sessions.list" -> SessionsListTool.list(sessions);
            case "sessions.history" -> SessionsHistoryTool.history(
                    sessions, stringArg(arguments, "sessionId"),
                    intArg(arguments, "limit", SessionsHistoryTool.DEFAULT_LIMIT));
            case "sessions.send" -> SessionsSendTool.send(
                    sessions, stringArg(arguments, "sessionId"), stringArg(arguments, "message"));
            default -> throw new IllegalArgumentException(
                    "SessionsToolProvider does not contribute a tool named '" + toolName
                  + "'. It provides agents.list, sessions.list, sessions.history, sessions.send.");
        };
    }

    /** The required {@code String} argument {@code key}; the model is contractually obliged to supply it. */
    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required argument '" + key + "' for a sessions tool call.");
        }
        return value.toString();
    }

    /** An optional integer argument (the model may pass a JSON number or a numeric string). */
    private static int intArg(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Argument '" + key + "' must be an integer for a sessions tool call. Got: '"
                  + value + "'.");
        }
    }
}
