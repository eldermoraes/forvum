package ai.forvum.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.forvum.channel.matrix.MatrixChannelConfig.Spec;
import ai.forvum.channel.matrix.MatrixSyncProtocol.InboundMessage;
import ai.forvum.channel.matrix.MatrixSyncProtocol.Invite;
import ai.forvum.channel.matrix.dto.JoinRequest;
import ai.forvum.channel.matrix.dto.SendMessageRequest;
import ai.forvum.channel.matrix.dto.SyncInvitedRoom;
import ai.forvum.channel.matrix.dto.SyncResponse;
import ai.forvum.channel.matrix.dto.SyncRooms;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Pins {@code redact()} at every WIRED log site — not just in isolation ({@code MatrixRedactTest}):
 * replacing {@code redact(e.getMessage())} with {@code e.getMessage()} at any site below turns its
 * test red. The Matrix access token rides the {@code Authorization: Bearer <token>} header, so a
 * REST-client exception message can echo it; each catch block must log the REDACTED message only.
 * The assertions capture the actually-emitted log records through a handler attached to the backing
 * JBoss LogManager logger (Surefire pins {@code java.util.logging.manager} to it for this module, so
 * the {@code org.jboss.logging} loggers delegate there even without a Quarkus boot).
 */
class MatrixLogRedactionWiringTest {

    private static final String SECRET = "syt_secret_token";
    private static final String LEAKY_MESSAGE =
            "HTTP 401 M_UNKNOWN_TOKEN for Authorization: Bearer " + SECRET;
    private static final Spec SPEC = new Spec(true, Optional.of("https://m.example.org"),
            Optional.of(SECRET), Optional.of("@bot:example.org"), Set.of(), true);

    /** Run {@code action} while capturing the formatted records of {@code loggerClass}'s logger. */
    private static List<String> capturedLogs(Class<?> loggerClass, Runnable action) {
        List<String> messages = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                messages.add(logRecord instanceof ExtLogRecord ext
                        ? ext.getFormattedMessage()
                        : logRecord.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(loggerClass.getName());
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    private static void assertRedacted(List<String> logs) {
        assertTrue(logs.stream().anyMatch(m -> m.contains("Bearer <redacted>")),
                "the failure must be logged with the token redacted; got: " + logs);
        assertFalse(logs.stream().anyMatch(m -> m.contains(SECRET)),
                "the access token must never reach the logs; got: " + logs);
    }

    @Test
    void aSyncFailureLogsTheRedactedMessageOnly() {
        MatrixChannel channel = new MatrixChannel();
        channel.config = new MatrixChannelConfig(Path.of("/nonexistent/matrix.json"));
        channel.processor = new SyncProcessor();
        channel.backoff = new Backoff(1, 1);
        RecordingMatrixClientApi api = new RecordingMatrixClientApi() {
            @Override
            public SyncResponse sync(String baseUrl, String authorization, String since, int timeout) {
                boolean first = syncCalls.isEmpty();
                super.sync(baseUrl, authorization, since, timeout);
                if (!first) {
                    channel.running = false;
                }
                throw new RuntimeException(LEAKY_MESSAGE);
            }
        };

        channel.running = true;
        List<String> logs = capturedLogs(MatrixChannel.class,
                () -> channel.syncLoop(api, "https://m.example.org", "Bearer " + SECRET));

        assertRedacted(logs);
    }

    @Test
    void aFailedSendLogsTheRedactedMessageOnly() {
        SyncProcessor processor = new SyncProcessor();
        processor.turns = new FakeTurnDriver();
        RecordingMatrixClientApi api = new RecordingMatrixClientApi() {
            @Override
            public void sendMessage(String baseUrl, String authorization, String roomId, String txnId,
                                    SendMessageRequest body) {
                throw new RuntimeException(LEAKY_MESSAGE);
            }
        };

        List<String> logs = capturedLogs(SyncProcessor.class,
                () -> processor.process(new InboundMessage("!r:x", "@alice:example.org", "hi"),
                        SPEC, api, "https://m.example.org", "Bearer " + SECRET));

        assertRedacted(logs);
    }

    @Test
    void aFailedJoinLogsTheRedactedMessageOnly() {
        SyncProcessor processor = new SyncProcessor();
        processor.turns = new FakeTurnDriver();
        RecordingMatrixClientApi api = new RecordingMatrixClientApi() {
            @Override
            public void join(String baseUrl, String authorization, String roomId, JoinRequest body) {
                throw new RuntimeException(LEAKY_MESSAGE);
            }
        };

        List<String> logs = capturedLogs(SyncProcessor.class,
                () -> processor.processInvite(new Invite("!r:x", "@alice:example.org"),
                        SPEC, api, "https://m.example.org", "Bearer " + SECRET));

        assertRedacted(logs);
    }

    @Test
    void aPoisonEventFailureInsideTheBatchLoopLogsTheRedactedMessageOnly() {
        // The per-message isolation catch in syncLoop is a log site of its own — pin it too.
        MatrixChannel channel = new MatrixChannel();
        channel.config = new MatrixChannelConfig(Path.of("/nonexistent/matrix.json"));
        channel.backoff = new Backoff(1, 1);
        channel.processor = new SyncProcessor() {
            @Override
            public void processInvite(Invite invite, Spec spec, MatrixClientApi api, String baseUrl,
                                      String authorization) {
                throw new IllegalStateException(LEAKY_MESSAGE);
            }
        };
        RecordingMatrixClientApi api = new RecordingMatrixClientApi() {
            @Override
            public SyncResponse sync(String baseUrl, String authorization, String since, int timeout) {
                if (scriptedResponses.isEmpty()) {
                    channel.running = false;
                }
                return super.sync(baseUrl, authorization, since, timeout);
            }
        };
        api.scriptedResponses.add(new SyncResponse("s1", new SyncRooms(
                null, Map.of("!lure:example.org", new SyncInvitedRoom(null)))));

        channel.running = true;
        List<String> logs = capturedLogs(MatrixChannel.class,
                () -> channel.syncLoop(api, "https://m.example.org", "Bearer " + SECRET));

        assertRedacted(logs);
    }
}
