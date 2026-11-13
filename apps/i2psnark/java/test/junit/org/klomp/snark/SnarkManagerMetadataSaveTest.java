package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the coalescing decision {@code SnarkManager.saveMetadata()} applies to a
 * metadata mutation. Every metadata mutation used to serialize the whole metadata
 * file, which holds the config of every torrent, and a lookup torrent mutates it
 * twice, once when it starts and once when it is purged. Over the measured window
 * that was one full rewrite every two seconds. Now the first mutation of a burst
 * writes at once and the rest are covered by a single trailing write.
 *
 * @since 0.9.72+
 */
public class SnarkManagerMetadataSaveTest {

    /** A wall-clock base, so the arithmetic is the arithmetic of a running router. */
    private static final long BASE = 1_700_000_000_000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one;
     * the decision under test is static and carries no state.
     *
     * @since 0.9.72+
     */
    public SnarkManagerMetadataSaveTest() {}

    /**
     * The first mutation of all writes at once, so a lone config change is durable
     * immediately rather than waiting for a timer that may never come.
     */
    @Test
    public void testFirstMutationWritesAtOnce() {
        assertTrue(SnarkManager.writeMetadataNow(BASE, 0, false,
                                                 SnarkManager.METADATA_SAVE_MIN_INTERVAL_MS));
    }

    /**
     * A mutation shortly after a write does not rewrite the file again, which is the
     * whole point of the interval.
     */
    @Test
    public void testMutationInsideTheIntervalIsDeferred() {
        long interval = SnarkManager.METADATA_SAVE_MIN_INTERVAL_MS;
        assertFalse(SnarkManager.writeMetadataNow(BASE + 2000, BASE, false, interval));
        assertFalse(SnarkManager.writeMetadataNow(BASE + interval - 1, BASE, false, interval));
    }

    /**
     * Once the interval has passed, the next mutation writes, so a steady stream of
     * changes still reaches the file at a bounded rate rather than never.
     */
    @Test
    public void testMutationAfterTheIntervalWrites() {
        long interval = SnarkManager.METADATA_SAVE_MIN_INTERVAL_MS;
        assertTrue(SnarkManager.writeMetadataNow(BASE + interval, BASE, false, interval));
    }

    /**
     * While a trailing write is already scheduled, no further write is started: one
     * queued write covers the whole burst, and two timers racing to rewrite the same
     * file is what the queue exists to avoid.
     */
    @Test
    public void testQueuedWriteCoversLaterMutations() {
        long interval = SnarkManager.METADATA_SAVE_MIN_INTERVAL_MS;
        assertFalse(SnarkManager.writeMetadataNow(BASE + interval * 4, BASE, true, interval));
    }

    /**
     * A storm of mutations two seconds apart, the measured lookup cadence, costs one
     * rewrite per interval rather than one per mutation.
     */
    @Test
    public void testBurstCostsOneRewritePerInterval() {
        long interval = SnarkManager.METADATA_SAVE_MIN_INTERVAL_MS;
        long lastWrite = 0;
        long nextTrailingWrite = -1;
        int rewrites = 0;
        for (int i = 0; i < 1000; i++) {
            long now = BASE + i * 2000L;
            if (nextTrailingWrite >= 0 && now >= nextTrailingWrite) {
                // the trailing write ran, so the queue is empty again
                nextTrailingWrite = -1;
                lastWrite = now;
            }
            if (SnarkManager.writeMetadataNow(now, lastWrite, nextTrailingWrite >= 0, interval)) {
                rewrites++;
                lastWrite = now;
            } else if (nextTrailingWrite < 0) {
                nextTrailingWrite = now + interval;
            }
        }
        long seconds = 999 * 2;
        int bound = (int) (seconds / (interval / 1000)) + 2;
        assertTrue("expected at most " + bound + " rewrites, got " + rewrites, rewrites <= bound);
        assertTrue("a storm of changes still has to reach the file", rewrites > 0);
    }
}
