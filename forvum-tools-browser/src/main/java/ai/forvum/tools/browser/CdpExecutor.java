package ai.forvum.tools.browser;

import ai.forvum.tools.browser.dto.CdpCommand;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The minimal CDP execution surface {@link BrowserOperations} needs, so the per-tool orchestration
 * (navigate / snapshot / extract / click / type / wait) is unit-testable against a scripted fake without a
 * live Chrome (the P2-1 testability requirement, mirroring how the discord protocol is socket-free). The
 * production implementation is {@link CdpSession} (lazy dial + bounded-timeout await over websockets-next).
 */
public interface CdpExecutor {

    /** The shared protocol: the monotonic id allocator + command builders. */
    CdpProtocol protocol();

    /** Send a command and await its {@code result} node; throws {@link CdpException} on failure/timeout. */
    JsonNode send(CdpCommand command);

    /** Drain any buffered {@code Page.loadEventFired} events (called before arming a load wait). */
    void clearLoadEvents();

    /** Whether a {@code Page.loadEventFired} has arrived since the last {@link #clearLoadEvents}. */
    boolean loadEventSeen();
}
