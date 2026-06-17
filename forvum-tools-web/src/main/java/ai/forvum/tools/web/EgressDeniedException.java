package ai.forvum.tools.web;

/**
 * Thrown by {@link EgressGuard} when a {@code web.fetch} target is refused by the SSRF egress policy (a
 * non-http(s) scheme, a hostless URL, or — with {@code allowPrivateNetwork} off — an internal/private/
 * link-local/loopback address). Analogous to the filesystem module's {@code WorkspaceEscapeException}:
 * it is the module's self-contained confinement boundary for an attacker-influenced target. Unchecked,
 * so it propagates out of {@code WebToolProvider.invoke} to the engine's {@code ToolExecutor}, which
 * audits the call {@code error}.
 */
public class EgressDeniedException extends RuntimeException {

    public EgressDeniedException(String message) {
        super(message);
    }
}
