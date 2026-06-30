package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.matrix.RecordingMatrixClientApi.SyncCall;
import ai.forvum.channel.matrix.dto.EventContent;
import ai.forvum.channel.matrix.dto.RoomEvent;
import ai.forvum.channel.matrix.dto.SyncInviteState;
import ai.forvum.channel.matrix.dto.SyncInvitedRoom;
import ai.forvum.channel.matrix.dto.SyncJoinedRoom;
import ai.forvum.channel.matrix.dto.SyncResponse;
import ai.forvum.channel.matrix.dto.SyncRooms;
import ai.forvum.channel.matrix.dto.SyncTimeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The sync cycle ({@link MatrixChannel#syncLoop}): initial snapshot (messages DISCARDED, cursor taken,
 * invites handled) → incremental batch (turn driven, reply sent, cursor advanced) → transient failure
 * (redacted log + back-off, loop survives) → exit; plus the graceful no-op boot branches
 * ({@link MatrixChannel#onStart}) and the credentialed start path.
 *
 * <p>Plain POJO tests (no Quarkus boot): {@code MatrixChannel}, {@code SyncProcessor}, and
 * {@code FakeTurnDriver} are constructed directly and wired by hand, so the loop's {@code running} flag
 * and collaborators are real instance fields (not CDI client-proxy fields). The
 * {@link RecordingMatrixClientApi} stands in for the REST client, exercising sync → dispatch → send
 * with no live homeserver.
 */
class MatrixChannelSyncLoopTest {

    private static final String BOT = "@bot:example.org";

    private static SyncResponse messageBatch(String nextBatch, String roomId, String sender,
                                             String body) {
        RoomEvent event = new RoomEvent("m.room.message", sender, null,
                new EventContent("m.text", body, null));
        return new SyncResponse(nextBatch, new SyncRooms(
                Map.of(roomId, new SyncJoinedRoom(new SyncTimeline(List.of(event)))), null));
    }

    private static SyncResponse inviteBatch(String nextBatch, String roomId, String inviter) {
        RoomEvent member = new RoomEvent("m.room.member", inviter, BOT,
                new EventContent(null, null, "invite"));
        return new SyncResponse(nextBatch, new SyncRooms(null,
                Map.of(roomId, new SyncInvitedRoom(new SyncInviteState(List.of(member))))));
    }

    /** Wire a {@code MatrixChannel} with a fake driver + a config bound to an explicit file path. */
    private static MatrixChannel wiredChannel(FakeTurnDriver driver, Path configFile) {
        SyncProcessor processor = new SyncProcessor();
        processor.turns = driver;

        MatrixChannel channel = new MatrixChannel();
        channel.processor = processor;
        channel.config = new MatrixChannelConfig(configFile);
        channel.syncTimeoutMillis = 1;
        channel.backoff = new Backoff(1, 1); // millisecond back-off keeps the failure test fast
        return channel;
    }

    /** A stopping API: drains the scripted responses, then stops the loop on the next sync. */
    private static RecordingMatrixClientApi stoppingApi(MatrixChannel channel) {
        return new RecordingMatrixClientApi() {
            @Override
            public SyncResponse sync(String baseUrl, String authorization, String since, int timeout) {
                if (scriptedResponses.isEmpty()) {
                    channel.running = false; // exit after the scripted batches are drained
                }
                return super.sync(baseUrl, authorization, since, timeout);
            }
        };
    }

    /** A {@code channels/matrix.json} carrying the serve-required keys incl. the bot's own user id.
     * Public mode ({@code allowAllUsers}) so an empty allow-list still admits the test users (#170). */
    private static Path credentialedConfig(Path home) throws IOException {
        return configFile(home, "{ \"homeserver\": \"https://m.example.org\", "
                + "\"accessToken\": \"syt_tok\", \"userId\": \"" + BOT + "\", \"allowAllUsers\": true }");
    }

    @Test
    void discardsTheInitialSnapshotThenDrivesATurnAndAdvancesTheCursor(@TempDir Path home)
            throws IOException {
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, credentialedConfig(home));
        RecordingMatrixClientApi api = stoppingApi(channel);
        // The FIRST sync (since == null) is the initial snapshot — its timeline is history and must
        // NOT drive a turn; only its next_batch cursor is taken.
        api.scriptedResponses.add(messageBatch("s1", "!room:example.org", "@alice:example.org",
                "old history — never replayed"));
        api.scriptedResponses.add(messageBatch("s2", "!room:example.org", "@alice:example.org", "ping"));

        channel.running = true;
        channel.syncLoop(api, "https://m.example.org", "Bearer syt_tok");

        assertEquals(1, driver.dispatched().size(),
                "only the incremental batch drives a turn — the initial snapshot is discarded");
        assertEquals("ping", driver.dispatched().get(0).content());
        assertEquals("@alice:example.org", driver.dispatched().get(0).nativeUserId());

        assertEquals(1, api.sent.size(), "the rendered reply was sent back");
        assertEquals("!room:example.org", api.sent.get(0).roomId());
        assertEquals("echo:ping", api.sent.get(0).body().body());
        assertEquals("m.text", api.sent.get(0).body().msgtype());

        List<SyncCall> calls = api.syncCalls;
        assertEquals(3, calls.size());
        assertEquals(new SyncCall("https://m.example.org", "Bearer syt_tok", null), calls.get(0));
        assertEquals("s1", calls.get(1).since(), "the snapshot's next_batch becomes since");
        assertEquals("s2", calls.get(2).since(), "the incremental batch advances the cursor");
    }

    @Test
    void aTransientSyncFailureBacksOffAndTheLoopSurvives(@TempDir Path home) throws IOException {
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, credentialedConfig(home));
        RecordingMatrixClientApi api = new RecordingMatrixClientApi() {
            private boolean failed;

            @Override
            public SyncResponse sync(String baseUrl, String authorization, String since, int timeout) {
                if (!failed) {
                    failed = true;
                    throw new RuntimeException(
                            "HTTP 401 M_UNKNOWN_TOKEN for Authorization: Bearer syt_secret");
                }
                if (scriptedResponses.isEmpty()) {
                    channel.running = false;
                }
                return super.sync(baseUrl, authorization, since, timeout);
            }
        };
        api.scriptedResponses.add(new SyncResponse("s1", null));
        api.scriptedResponses.add(messageBatch("s2", "!r:x", "@alice:example.org", "after recovery"));

        channel.running = true;
        channel.syncLoop(api, "https://m.example.org", "Bearer syt_secret");

        assertEquals(1, driver.dispatched().size(), "the loop survived the failed sync and recovered");
        assertEquals("after recovery", driver.dispatched().get(0).content());
    }

    @Test
    void theOwnEchoNeverDrivesATurn(@TempDir Path home) throws IOException {
        Path configFile = configFile(home, "{ \"homeserver\": \"https://m.org\", "
                + "\"accessToken\": \"t\", \"userId\": \"" + BOT + "\" }");
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, configFile);
        RecordingMatrixClientApi api = stoppingApi(channel);
        api.scriptedResponses.add(new SyncResponse("s1", null));
        api.scriptedResponses.add(messageBatch("s2", "!r:x", BOT, "echo:previous reply"));

        channel.running = true;
        channel.syncLoop(api, "https://m.org", "Bearer t");

        assertTrue(driver.dispatched().isEmpty(), "the bot's own echo must never drive a turn");
        assertTrue(api.sent.isEmpty());
    }

    @Test
    void joinsAnInviteFromAnAllowedUserEvenInTheInitialSnapshot(@TempDir Path home) throws IOException {
        Path configFile = configFile(home, "{ \"homeserver\": \"https://m.org\", "
                + "\"accessToken\": \"t\", \"userId\": \"" + BOT + "\", "
                + "\"allowedUserIds\": [\"@alice:example.org\"] }");
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, configFile);
        RecordingMatrixClientApi api = stoppingApi(channel);
        // A pending invite received while the bot was offline appears ONLY in the initial snapshot, so
        // invite handling is NOT subject to the snapshot discard (a join is idempotent, not a replay).
        api.scriptedResponses.add(inviteBatch("s1", "!invited:example.org", "@alice:example.org"));

        channel.running = true;
        channel.syncLoop(api, "https://m.org", "Bearer t");

        assertEquals(1, api.joined.size(), "an invite from an allowed user is auto-joined");
        assertEquals("!invited:example.org", api.joined.get(0).roomId());
    }

    @Test
    void ignoresAnInviteFromADisallowedUser(@TempDir Path home) throws IOException {
        Path configFile = configFile(home, "{ \"homeserver\": \"https://m.org\", "
                + "\"accessToken\": \"t\", \"userId\": \"" + BOT + "\", "
                + "\"allowedUserIds\": [\"@alice:example.org\"] }");
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, configFile);
        RecordingMatrixClientApi api = stoppingApi(channel);
        api.scriptedResponses.add(inviteBatch("s1", "!lure:example.org", "@mallory:example.org"));

        channel.running = true;
        channel.syncLoop(api, "https://m.org", "Bearer t");

        assertTrue(api.joined.isEmpty(), "an invite from a disallowed user is ignored, never joined");
    }

    @Test
    void disabledChannelLeavesTheLoopUnstartedWithoutThrowing() {
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, Path.of("/nonexistent/matrix.json"));

        // onStart with the absent-file config (Spec.empty() => enabled=false) hits the DISABLED branch:
        // info + no-op, never throw and never start a syncer — the CI native no-config boot contract.
        channel.onStart(null);

        assertFalse(channel.running, "a disabled channel never starts the sync loop");
        assertTrue(driver.dispatched().isEmpty(), "no turn is driven when the channel is disabled");
        // onStop must be safe even though no syncer was started.
        channel.onStop(null);
    }

    @Test
    void enabledButCredentialLessChannelLeavesTheLoopUnstartedWithoutThrowing(@TempDir Path home)
            throws IOException {
        // An ENABLED matrix.json missing accessToken (and one missing homeserver) => the "no
        // credentials" branch: WARN + no-op — both keys are required to serve.
        for (String json : new String[] {
                "{ \"enabled\": true, \"homeserver\": \"https://m.org\" }",
                "{ \"enabled\": true, \"accessToken\": \"syt_tok\" }" }) {
            FakeTurnDriver driver = new FakeTurnDriver();
            MatrixChannel channel = wiredChannel(driver, configFile(home, json));

            channel.onStart(null);

            assertFalse(channel.running,
                    "an enabled but credential-less channel never starts the sync loop: " + json);
            assertTrue(driver.dispatched().isEmpty());
            channel.onStop(null);
        }
    }

    @Test
    void enabledButUserIdLessChannelLeavesTheLoopUnstartedWithoutThrowing(@TempDir Path home)
            throws IOException {
        // Matrix /sync ECHOES the bot's own sends (unlike Telegram getUpdates / Discord's bot-author
        // filter), so without its own user id the bot cannot self-filter: an empty allow-list opens an
        // unbounded self-conversation (one real LLM call per cycle), a non-empty one an unbounded
        // refusal loop. A missing userId must therefore fail safe — WARN + no-op, like a missing
        // credential — never start the loop.
        Path configFile = configFile(home, "{ \"enabled\": true, "
                + "\"homeserver\": \"https://m.org\", \"accessToken\": \"syt_tok\" }");
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, configFile);

        channel.onStart(null);

        assertFalse(channel.running,
                "an enabled channel without the bot's own userId must never start the sync loop");
        assertTrue(driver.dispatched().isEmpty());
        channel.onStop(null);
    }

    @Test
    void aPoisonMessageNeverWedgesTheCursorAndItsSiblingsAreDispatchedExactlyOnce(@TempDir Path home)
            throws IOException {
        // One throwing message must not abort its batch: the siblings before AND after it are
        // dispatched exactly once, the cursor still advances past the batch (no eternal refetch +
        // duplicate turns), and the loop survives — per-message isolation, the M4 observer lesson.
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, credentialedConfig(home));
        channel.processor = new PoisonOnBody(driver);
        RecordingMatrixClientApi api = stoppingApi(channel);
        api.scriptedResponses.add(new SyncResponse("s1", null)); // initial snapshot
        RoomEvent good1 = textEvent("@alice:example.org", "first");
        RoomEvent poison = textEvent("@alice:example.org", "poison");
        RoomEvent good2 = textEvent("@alice:example.org", "second");
        api.scriptedResponses.add(new SyncResponse("s2", new SyncRooms(
                Map.of("!room:example.org",
                        new SyncJoinedRoom(new SyncTimeline(List.of(good1, poison, good2)))),
                null)));

        channel.running = true;
        channel.syncLoop(api, "https://m.example.org", "Bearer syt_tok");

        assertEquals(List.of("first", "second"),
                driver.dispatched().stream().map(m -> m.content()).toList(),
                "the poison message's siblings are dispatched exactly once — no drop, no redispatch");
        assertEquals("s2", api.syncCalls.get(2).since(),
                "the cursor advances past the poisoned batch — it is never refetched");
    }

    /** A {@code SyncProcessor} whose {@code process} throws on body {@code "poison"}. {@code @Vetoed}
     * so this subclass of an {@code @ApplicationScoped} bean never becomes a second ambiguous bean in
     * the module's {@code @QuarkusTest} boot (CDI scopes are {@code @Inherited}). */
    @jakarta.enterprise.inject.Vetoed
    static final class PoisonOnBody extends SyncProcessor {
        PoisonOnBody(ai.forvum.sdk.ChannelTurnDriver driver) {
            this.turns = driver;
        }

        @Override
        public void process(MatrixSyncProtocol.InboundMessage message, MatrixChannelConfig.Spec spec,
                            MatrixClientApi api, String baseUrl, String authorization) {
            if ("poison".equals(message.body())) {
                throw new IllegalStateException("poisoned event for Authorization: Bearer syt_tok");
            }
            super.process(message, spec, api, baseUrl, authorization);
        }
    }

    private static RoomEvent textEvent(String sender, String body) {
        return new RoomEvent("m.room.message", sender, null, new EventContent("m.text", body, null));
    }

    @Test
    void aNullOrBlankNextBatchRetainsThePreviousCursor(@TempDir Path home) throws IOException {
        // The defensive next_batch guard: a malformed response without a cursor must not reset since
        // to null (which would re-trigger the initial-snapshot discard and lose the position).
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, credentialedConfig(home));
        RecordingMatrixClientApi api = stoppingApi(channel);
        api.scriptedResponses.add(new SyncResponse("s1", null)); // initial snapshot: cursor taken
        api.scriptedResponses.add(new SyncResponse(null, null)); // no cursor — retain s1
        api.scriptedResponses.add(new SyncResponse("   ", null)); // blank cursor — retain s1

        channel.running = true;
        channel.syncLoop(api, "https://m.example.org", "Bearer syt_tok");

        List<SyncCall> calls = api.syncCalls;
        assertEquals(4, calls.size());
        assertEquals("s1", calls.get(1).since());
        assertEquals("s1", calls.get(2).since(), "a null next_batch retains the previous cursor");
        assertEquals("s1", calls.get(3).since(), "a blank next_batch retains the previous cursor");
    }

    @Test
    void aFailedSyncConsumesTheBackoffSchedule(@TempDir Path home) throws IOException {
        // Pins the WIRING (BackoffTest covers the class): a failed sync must consume
        // backoff.nextDelayMillis(), advancing the schedule — delete the backOff() call and this fails.
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, credentialedConfig(home));
        channel.backoff = new Backoff(1, 64);
        RecordingMatrixClientApi api = new RecordingMatrixClientApi() {
            @Override
            public SyncResponse sync(String baseUrl, String authorization, String since, int timeout) {
                boolean first = syncCalls.isEmpty();
                super.sync(baseUrl, authorization, since, timeout);
                if (!first) {
                    channel.running = false; // second failure exits the loop BEFORE backing off again
                }
                throw new RuntimeException("transient sync failure");
            }
        };

        channel.running = true;
        channel.syncLoop(api, "https://m.example.org", "Bearer syt_tok");

        assertEquals(2, channel.backoff.nextDelayMillis(),
                "exactly one back-off was consumed for the one retried failure (1 → next is 2)");
    }

    @Test
    void aSuccessfulSyncResetsTheBackoffSchedule(@TempDir Path home) throws IOException {
        // Pins the reset-on-success wiring: after two consumed failures (1, 2 → next would be 4) a
        // successful sync must return the schedule to its initial delay.
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, credentialedConfig(home));
        channel.backoff = new Backoff(1, 64);
        RecordingMatrixClientApi api = new RecordingMatrixClientApi() {
            @Override
            public SyncResponse sync(String baseUrl, String authorization, String since, int timeout) {
                if (syncCalls.size() < 2) {
                    super.sync(baseUrl, authorization, since, timeout);
                    throw new RuntimeException("transient sync failure");
                }
                channel.running = false; // the successful sync is the last cycle
                return super.sync(baseUrl, authorization, since, timeout);
            }
        };
        api.scriptedResponses.add(new SyncResponse("s1", null));

        channel.running = true;
        channel.syncLoop(api, "https://m.example.org", "Bearer syt_tok");

        assertEquals(1, channel.backoff.nextDelayMillis(),
                "the successful sync resets the back-off schedule to its initial delay");
    }

    @Test
    void aCredentialedStartSyncsWithTheBearerHeaderAndTheNormalizedBaseUrl(@TempDir Path home)
            throws Exception {
        // Trailing slash on the homeserver must be stripped; the token must ride the Authorization
        // header as Bearer <token>. The api is swapped for a stopping recorder, so the spawned virtual
        // thread runs exactly one sync and exits.
        Path configFile = configFile(home, "{ \"homeserver\": \"https://m.example.org/\", "
                + "\"accessToken\": \"syt_tok\", \"userId\": \"" + BOT + "\" }");
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, configFile);
        RecordingMatrixClientApi api = stoppingApi(channel);
        channel.api = api;

        channel.onStart(null);
        try {
            assertTrue(channel.running || !api.syncCalls.isEmpty(), "the sync loop was started");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (api.syncCalls.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertFalse(api.syncCalls.isEmpty(), "the spawned worker performed a sync");
            assertEquals("https://m.example.org", api.syncCalls.get(0).baseUrl(),
                    "trailing slashes are stripped from the homeserver base URL");
            assertEquals("Bearer syt_tok", api.syncCalls.get(0).authorization());
        } finally {
            channel.onStop(null);
        }
    }

    @Test
    void stripTrailingSlashesNormalizes() {
        assertEquals("https://m.org", MatrixChannel.stripTrailingSlashes("https://m.org"));
        assertEquals("https://m.org", MatrixChannel.stripTrailingSlashes("https://m.org/"));
        assertEquals("https://m.org", MatrixChannel.stripTrailingSlashes("https://m.org///"));
    }

    private static Path configFile(Path home, String json) throws IOException {
        Path channels = Files.createDirectories(home.resolve("channels"));
        Path file = channels.resolve("matrix.json");
        Files.writeString(file, json);
        return file;
    }
}
