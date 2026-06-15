package ai.forvum.channel.slack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.slack.RecordingSlackRestClient.Posted;
import ai.forvum.channel.slack.SlackChannelConfig.Spec;
import ai.forvum.channel.slack.dto.ChatPostMessageResponse;
import ai.forvum.channel.slack.dto.MessageEvent;
import ai.forvum.core.ChannelMessage;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code SlackMessageProcessor} maps a Socket Mode message event to a turn and enforces
 * {@code allowedUserIds} (the P2-CH round-trip + the channel-pattern Verify). Drives the real
 * {@code SlackMessageProcessor} bean against the in-module {@link FakeTurnDriver} (the engine's
 * {@code TurnService} is banned by the Layer-3 enforcer) and a {@link RecordingSlackRestClient}. Boots
 * Quarkus in-JVM — which also proves the module's CDI + {@code @RestClient} wiring AND the inert
 * no-config boot ({@code forvum.home} is pinned to a hermetic, absent path in the test
 * {@code application.properties}, so {@code SlackChannel.onStart} must warn + no-op during this very
 * boot regardless of the developer's real {@code ~/.forvum/}). Runs under Surefire (headless library,
 * CLAUDE.md §4 exception).
 */
@QuarkusTest
class SlackMessageProcessorIT {

    private static final String AUTH = "Bearer xoxb-test-token";

    @Inject
    SlackMessageProcessor processor;

    @Inject
    FakeTurnDriver driver;

    @Inject
    SlackChannel channel;

    private RecordingSlackRestClient rest;

    @BeforeEach
    void resetDriver() {
        driver.reset();
        rest = new RecordingSlackRestClient();
    }

    private static Spec anyUserSpec() {
        return new Spec(true, Optional.of("xoxb-t"), Optional.of("xapp-t"), Set.of());
    }

    private static MessageEvent userMessage(String userId, String channelId, String text) {
        return new MessageEvent("message", null, userId, null, channelId, text, null);
    }

    @Test
    void theChannelBeanStaysInertWithTheHermeticNoConfigHome() {
        // The @QuarkusTest boot already ran SlackChannel.onStart against the pinned absent forvum.home:
        // the inert-no-config contract means it must NOT have started its connect worker.
        assertFalse(channel.started(),
                "with no channels/slack.json the channel must warn + no-op at boot");
    }

    @Test
    void anAllowedUserDrivesATurnAndTheReplyIsPostedBack() {
        processor.process(userMessage("U42", "C777", "hello"), anyUserSpec(), rest, AUTH);

        assertEquals(1, driver.dispatched().size(), "an allowed user must drive exactly one turn");
        ChannelMessage dispatched = driver.dispatched().get(0);
        assertEquals("slack", dispatched.channelId());
        assertEquals("U42", dispatched.nativeUserId(), "native user id is the Slack user id");
        assertEquals("hello", dispatched.content());

        List<Posted> posted = rest.posted;
        assertEquals(1, posted.size(), "the TokenDelta reply is posted; the terminal Done renders to nothing");
        assertEquals("C777", posted.get(0).channel());
        assertEquals("echo:hello", posted.get(0).text());
        assertEquals(AUTH, posted.get(0).authorization(), "the reply carries the Bearer bot authorization");
    }

    @Test
    void aDisallowedUserIsRefusedWithAFriendlyMessageAndNoTurnRuns() {
        Spec spec = new Spec(true, Optional.of("xoxb-t"), Optional.of("xapp-t"), Set.of("U42"));

        processor.process(userMessage("U999", "C888", "let me in"), spec, rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a refused user must NOT drive a turn");
        assertEquals(1, rest.posted.size(), "the refusal must be posted back to the user's conversation");
        assertEquals("C888", rest.posted.get(0).channel());
        assertEquals(SlackMessageProcessor.REFUSAL_MESSAGE, rest.posted.get(0).text());
    }

    @Test
    void anAllowedListedUserDrivesATurn() {
        Spec spec = new Spec(true, Optional.of("xoxb-t"), Optional.of("xapp-t"), Set.of("U42"));

        processor.process(userMessage("U42", "C999", "hi"), spec, rest, AUTH);

        assertEquals(1, driver.dispatched().size());
        assertEquals("echo:hi", rest.posted.get(0).text());
    }

    @Test
    void aBotMessageIsIgnored() {
        // A bot_id marks a bot author — including this bot's own replies, which Slack echoes back as
        // message events; processing them would make the channel converse with itself.
        MessageEvent botEcho = new MessageEvent("message", null, null, "B9", "C777", "echo:hello", null);

        processor.process(botEcho, anyUserSpec(), rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a bot/self message drives no turn");
        assertTrue(rest.posted.isEmpty(), "a bot/self message posts nothing");
    }

    @Test
    void aSubtypedMessageIsIgnored() {
        // message_changed/message_deleted/channel_join/... are not plain user messages.
        MessageEvent edited =
                new MessageEvent("message", "message_changed", "U42", null, "C777", "edited", null);

        processor.process(edited, anyUserSpec(), rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a subtyped message drives no turn");
        assertTrue(rest.posted.isEmpty());
    }

    @Test
    void aMessageWithoutAUserIsIgnored() {
        MessageEvent noUser = new MessageEvent("message", null, null, null, "C777", "hello", null);

        processor.process(noUser, anyUserSpec(), rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a user-less message drives no turn");
        assertTrue(rest.posted.isEmpty());
    }

    @Test
    void anEmptyTextMessageIsIgnored() {
        processor.process(userMessage("U42", "C777", ""), anyUserSpec(), rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "an empty message drives no turn");
        assertTrue(rest.posted.isEmpty());
    }

    @Test
    void aWhitespaceOnlyMessageStillDrivesATurn() {
        // Boundary pin, deliberately stated: the empty-text gate is isEmpty(), NOT isBlank() —
        // byte-identical to the Discord/Telegram processors (the three share the same filtering by
        // design). A whitespace-only message therefore DOES drive a turn today; tightening this to
        // isBlank() is a cross-channel parity decision, not a single-channel drive-by.
        processor.process(userMessage("U42", "C777", "   "), anyUserSpec(), rest, AUTH);

        assertEquals(1, driver.dispatched().size(),
                "whitespace-only text passes the isEmpty gate (parity with Discord/Telegram)");
        assertEquals("   ", driver.dispatched().get(0).content());
    }

    @Test
    void aMessageWithoutAChannelIsIgnored() {
        processor.process(userMessage("U42", null, "hello"), anyUserSpec(), rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a message with no conversation drives no turn");
        assertTrue(rest.posted.isEmpty(), "nothing to reply to — nowhere to post");
    }

    @Test
    void anOkFalsePostMessageReplyIsLoggedNotThrown() {
        rest.postMessageResponse = new ChatPostMessageResponse(false, "channel_not_found");

        assertDoesNotThrow(() -> processor.process(userMessage("U42", "C777", "hello"),
                        anyUserSpec(), rest, AUTH),
                "Slack signals failure as ok=false (HTTP 200); it must never take the socket loop down");
        assertEquals(1, driver.dispatched().size(), "the turn itself still ran");
        assertEquals(1, rest.posted.size(), "the reply was attempted exactly once");
    }
}
