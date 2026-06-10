package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.matrix.MatrixChannelConfig.Spec;
import ai.forvum.channel.matrix.MatrixSyncProtocol.InboundMessage;
import ai.forvum.channel.matrix.MatrixSyncProtocol.Invite;
import ai.forvum.core.ChannelMessage;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

/**
 * {@code SyncProcessor} maps an inbound Matrix message to a turn and enforces {@code allowedUserIds}
 * (ULTRAPLAN §5.5 / the M17 contract). Drives the real {@code SyncProcessor} bean against the in-module
 * {@link FakeTurnDriver} (the engine's {@code TurnService} is banned by the Layer-3 enforcer) and a
 * {@link RecordingMatrixClientApi}. Boots Quarkus in-JVM, which ALSO proves the CDI + {@code @RestClient}
 * wiring ({@link MatrixChannel} injects the registered REST client at boot) and the inert no-config boot
 * ({@code forvum.home} is pinned to a hermetic, config-less path in the test
 * {@code application.properties}, so {@code MatrixChannel.onStart} must warn + no-op regardless of the
 * developer's real {@code ~/.forvum/}). Runs under Surefire (headless library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class SyncProcessorIT {

    private static final String BASE = "https://m.example.org";
    private static final String AUTH = "Bearer syt_test";

    @Inject
    SyncProcessor processor;

    @Inject
    FakeTurnDriver driver;

    @Inject
    MatrixChannel channel;

    @Inject
    @RestClient
    MatrixClientApi restClient;

    private RecordingMatrixClientApi api;

    @BeforeEach
    void resetDriver() {
        driver.reset();
        api = new RecordingMatrixClientApi();
    }

    private static Spec spec(Set<String> allowedUserIds) {
        return new Spec(true, Optional.of(BASE), Optional.of("syt_test"),
                Optional.of("@bot:example.org"), allowedUserIds);
    }

    @Test
    void theNoConfigBootIsInertAndTheRestClientWiringResolves() {
        // The hermetic forvum.home has no channels/matrix.json => onStart warned + no-oped at boot.
        assertFalse(channel.isRunning(), "with no channels/matrix.json the sync loop must not start");
        // The @RestClient bean registered (configKey matrix-client-api + the placeholder URL from
        // META-INF/microprofile-config.properties) and injected — the CDI/rest-client wiring proof.
        assertNotNull(restClient);
    }

    @Test
    void anAllowedUserDrivesATurnAndTheReplyIsSentBackWithUniqueTxnIds() {
        // empty allow-list => any user allowed
        processor.process(new InboundMessage("!room:example.org", "@alice:example.org", "hello"),
                spec(Set.of()), api, BASE, AUTH);

        assertEquals(1, driver.dispatched().size(), "an allowed user must drive exactly one turn");
        ChannelMessage dispatched = driver.dispatched().get(0);
        assertEquals("matrix", dispatched.channelId());
        assertEquals("@alice:example.org", dispatched.nativeUserId(),
                "native user id is the Matrix user id");
        assertEquals("hello", dispatched.content());

        assertEquals(1, api.sent.size(), "the TokenDelta reply is sent; the terminal Done renders to nothing");
        assertEquals("!room:example.org", api.sent.get(0).roomId());
        assertEquals("echo:hello", api.sent.get(0).body().body());
        assertEquals("m.text", api.sent.get(0).body().msgtype());

        // A second turn's send must carry a DIFFERENT txnId (the homeserver dedupes on it).
        processor.process(new InboundMessage("!room:example.org", "@alice:example.org", "again"),
                spec(Set.of()), api, BASE, AUTH);
        assertEquals(2, api.sent.size());
        assertNotEquals(api.sent.get(0).txnId(), api.sent.get(1).txnId(),
                "every send must use a unique transaction id");
    }

    @Test
    void aDisallowedUserIsRefusedWithAFriendlyMessageAndNoTurnRuns() {
        processor.process(new InboundMessage("!room:example.org", "@mallory:example.org", "let me in"),
                spec(Set.of("@alice:example.org")), api, BASE, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a refused user must NOT drive a turn");
        assertEquals(1, api.sent.size(), "the refusal must be sent back to the room");
        assertEquals("!room:example.org", api.sent.get(0).roomId());
        assertEquals(SyncProcessor.REFUSAL_MESSAGE, api.sent.get(0).body().body());
    }

    @Test
    void anAllowedListedUserDrivesATurn() {
        processor.process(new InboundMessage("!r:x", "@alice:example.org", "hi"),
                spec(Set.of("@alice:example.org")), api, BASE, AUTH);

        assertEquals(1, driver.dispatched().size());
        assertEquals("echo:hi", api.sent.get(0).body().body());
    }

    @Test
    void anInviteFromAnAllowedUserIsJoinedAndOneFromADisallowedUserIsIgnored() {
        Spec spec = spec(Set.of("@alice:example.org"));

        processor.processInvite(new Invite("!good:example.org", "@alice:example.org"),
                spec, api, BASE, AUTH);
        processor.processInvite(new Invite("!lure:example.org", "@mallory:example.org"),
                spec, api, BASE, AUTH);

        assertEquals(1, api.joined.size(), "only the allowed inviter's room is joined");
        assertEquals("!good:example.org", api.joined.get(0).roomId());
        assertTrue(driver.dispatched().isEmpty(), "invite handling never drives a turn");
    }
}
