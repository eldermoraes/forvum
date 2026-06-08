package ai.forvum.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.discord.DiscordChannelConfig.Spec;
import ai.forvum.channel.discord.RecordingDiscordRestClient.Posted;
import ai.forvum.channel.discord.dto.MessageCreate;
import ai.forvum.channel.discord.dto.MessageCreate.Author;
import ai.forvum.core.ChannelMessage;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code MessageProcessor} maps a Discord MESSAGE_CREATE to a turn and enforces {@code allowedUserIds}
 * (the P2-CH round-trip + the channel-pattern Verify). Drives the real {@code MessageProcessor} bean
 * against the in-module {@link FakeTurnDriver} (the engine's {@code TurnService} is banned by the Layer-3
 * enforcer) and a {@link RecordingDiscordRestClient}. Boots Quarkus in-JVM; runs under Surefire (headless
 * library, CLAUDE.md §4 exception).
 */
@QuarkusTest
class MessageProcessorIT {

    private static final String AUTH = "Bot test-token";

    @Inject
    MessageProcessor processor;

    @Inject
    FakeTurnDriver driver;

    private RecordingDiscordRestClient rest;

    @BeforeEach
    void resetDriver() {
        driver.reset();
        rest = new RecordingDiscordRestClient();
    }

    private static MessageCreate userMessage(String userId, String channelId, String content) {
        return new MessageCreate(new Author(userId, false), channelId, content);
    }

    @Test
    void anAllowedUserDrivesATurnAndTheReplyIsPostedBack() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of()); // empty allow-list => any user allowed

        processor.process(userMessage("42", "777", "hello"), spec, rest, AUTH);

        assertEquals(1, driver.dispatched().size(), "an allowed user must drive exactly one turn");
        ChannelMessage dispatched = driver.dispatched().get(0);
        assertEquals("discord", dispatched.channelId());
        assertEquals("42", dispatched.nativeUserId(), "native user id is the Discord user id as String");
        assertEquals("hello", dispatched.content());

        List<Posted> posted = rest.posted;
        assertEquals(1, posted.size(), "the TokenDelta reply is posted; the terminal Done renders to nothing");
        assertEquals("777", posted.get(0).channelId());
        assertEquals("echo:hello", posted.get(0).content());
        assertEquals(AUTH, posted.get(0).authorization(), "the reply carries the Bot authorization header");
    }

    @Test
    void aDisallowedUserIsRefusedWithAFriendlyMessageAndNoTurnRuns() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of(42L)); // only 42 allowed

        processor.process(userMessage("999", "888", "let me in"), spec, rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a refused user must NOT drive a turn");
        assertEquals(1, rest.posted.size(), "the refusal must be posted back to the user's channel");
        assertEquals("888", rest.posted.get(0).channelId());
        assertEquals(MessageProcessor.REFUSAL_MESSAGE, rest.posted.get(0).content());
    }

    @Test
    void anAllowedListedUserDrivesATurn() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of(42L));

        processor.process(userMessage("42", "999", "hi"), spec, rest, AUTH);

        assertEquals(1, driver.dispatched().size());
        assertEquals("echo:hi", rest.posted.get(0).content());
    }

    @Test
    void aBotMessageIsIgnored() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of());

        processor.process(new MessageCreate(new Author("42", true), "777", "I am a bot"), spec, rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a bot/self message drives no turn");
        assertTrue(rest.posted.isEmpty(), "a bot/self message posts nothing");
    }

    @Test
    void anEmptyContentMessageIsIgnored() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of());

        processor.process(userMessage("42", "777", ""), spec, rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "an empty (no MESSAGE_CONTENT) message drives no turn");
        assertTrue(rest.posted.isEmpty(), "an empty message posts nothing");
    }

    @Test
    void aMessageWithoutAChannelIdIsIgnored() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of());

        // A malformed MESSAGE_CREATE with a null channel_id: there is nowhere to reply, so the guard drops
        // it before any dispatch — no turn, no post (the channelId == null defensive branch).
        processor.process(userMessage("42", null, "hello"), spec, rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a message with no channel_id drives no turn");
        assertTrue(rest.posted.isEmpty(), "a message with no channel_id posts nothing (nowhere to reply)");
    }

    @Test
    void aMessageFromANonNumericAuthorIdIsIgnored() {
        Spec spec = new Spec(true, Optional.of("token"), Set.of());

        // A non-numeric author.id (a malformed/unexpected snowflake) hits the NumberFormatException warn
        // path: the attempt is logged and dropped — no turn, no post.
        processor.process(userMessage("not-a-number", "777", "hello"), spec, rest, AUTH);

        assertTrue(driver.dispatched().isEmpty(), "a non-numeric author id drives no turn");
        assertTrue(rest.posted.isEmpty(), "a non-numeric author id posts nothing");
    }
}
