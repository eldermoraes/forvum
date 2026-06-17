package ai.forvum.tools.shell;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Coverage of {@link ShellExecutor}'s size-bounded capture (DP-10): a command whose merged output exceeds
 * {@link ShellExecutor#MAX_OUTPUT_BYTES} is truncated at the cap with a marker, and the rest is drained so
 * the child can still exit (no full-pipe deadlock). The existing {@link ShellExecutorTest} covers the
 * normal-size, non-zero-exit, timeout, env-scrub, and working-dir paths; this exercises the truncation
 * branch + the {@code Capture.render()} truncated arm.
 */
class ShellExecutorTruncationTest {

    private final ShellExecutor executor = new ShellExecutor();

    private static String binaryOrSkip(String... candidates) {
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        assumeTrue(false, "no POSIX binary among " + List.of(candidates));
        return null; // unreachable
    }

    @Test
    void outputLargerThanTheCapIsTruncatedWithAMarker(@TempDir Path dir) throws Exception {
        String cat = binaryOrSkip("/bin/cat", "/usr/bin/cat");
        // A file comfortably larger than the 64 KiB cap; `cat` streams it to stdout.
        Path big = dir.resolve("big.txt");
        byte[] payload = new byte[ShellExecutor.MAX_OUTPUT_BYTES * 2 + 1024];
        java.util.Arrays.fill(payload, (byte) 'A');
        Files.write(big, payload);

        String out = executor.run(List.of(cat, big.toString()), dir, 30);

        assertTrue(out.contains("[output truncated at " + ShellExecutor.MAX_OUTPUT_BYTES + " bytes]"),
                "output beyond the cap is truncated with a marker, got tail: "
                        + out.substring(Math.max(0, out.length() - 80)));
        // The captured prefix is exactly the cap (the marker is appended after it).
        int markerStart = out.indexOf("\n[output truncated");
        assertTrue(markerStart == ShellExecutor.MAX_OUTPUT_BYTES,
                "the captured prefix is exactly MAX_OUTPUT_BYTES before the marker, marker at=" + markerStart);
    }
}
