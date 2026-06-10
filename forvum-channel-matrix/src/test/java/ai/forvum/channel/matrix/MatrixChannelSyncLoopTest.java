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

    @Test
    void discardsTheInitialSnapshotThenDrivesATurnAndAdvancesTheCursor() {
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, Path.of("/nonexistent/matrix.json"));
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
    void aTransientSyncFailureBacksOffAndTheLoopSurvives() {
        FakeTurnDriver driver = new FakeTurnDriver();
        MatrixChannel channel = wiredChannel(driver, Path.of("/nonexistent/matrix.json"));
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
