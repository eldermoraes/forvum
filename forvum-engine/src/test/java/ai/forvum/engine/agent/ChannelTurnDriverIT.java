package ai.forvum.engine.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.forvum.core.ChannelMessage;
import ai.forvum.core.event.AgentEvent;
import ai.forvum.core.event.Done;
import ai.forvum.core.event.TokenDelta;
import ai.forvum.sdk.ChannelTurnDriver;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine exposes its {@link TurnService} facade as the SDK {@link ChannelTurnDriver} contract, so a
 * Layer-3 channel can drive turns depending only on {@code forvum-sdk} (Resolution B; CLAUDE.md
 * section 3 / 12). Injecting the interface must resolve to the engine facade and drive a real
 * (fake-backed) turn — one {@link TokenDelta} then a terminal {@link Done} (streaming Option B).
 */
@QuarkusTest
@TestProfile(TurnServiceIT.ChannelHomeProfile.class)
class ChannelTurnDriverIT {

    @Inject
    ChannelTurnDriver driver;

    @Test
    void engineTurnServiceIsInjectableAsTheSdkChannelTurnDriver() {
        List<AgentEvent> events = new ArrayList<>();

        driver.dispatch(new ChannelMessage("web", "sess-a", "hello", Instant.now()), events::add);

        assertEquals(2, events.size(), "Option B emits exactly TokenDelta then Done");
        assertEquals("pong", assertInstanceOf(TokenDelta.class, events.get(0)).text(),
                "the fake provider replies 'pong' through the SDK contract");
        assertInstanceOf(Done.class, events.get(1));
    }
}
