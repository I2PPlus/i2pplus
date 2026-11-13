package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.Random;

import org.junit.Test;

/**
 * Pins the pacing of a batch of outbound dials in
 * {@code TrackerClient.tryAddPeers()}. The spread used to be a sleep between each add,
 * on a worker of the shared announce pool, so one torrent's connect pacing held up
 * every other torrent's announce; it is now a delay each peer's own connection thread
 * waits. These tests pin the shape of that delay sequence and how many dials a batch
 * is allowed to start.
 *
 * @since 0.9.72+
 */
public class PeerDialPacingTest {

    /** The spacing the production code uses as its floor, in ms. */
    private static final int MIN = 2000;

    /** How much longer than the floor a spacing may run, in ms. */
    private static final int SPREAD = 6 * 1000;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one;
     * the pacing helpers are static and carry no state.
     *
     * @since 0.9.72+
     */
    public PeerDialPacingTest() {}

    /**
     * The first dial of a batch waits nothing, so a torrent with free slots starts
     * connecting immediately, and every later dial waits at least the floor.
     */
    @Test
    public void testFirstDialIsImmediate() {
        long[] delays = TrackerClient.pacingDelays(new Random(1), 4, MIN, SPREAD);
        assertEquals(4, delays.length);
        assertEquals(0, delays[0]);
        for (int i = 1; i < delays.length; i++) {
            assertTrue("spacing " + i + " is " + (delays[i] - delays[i - 1]),
                       delays[i] - delays[i - 1] >= MIN);
            assertTrue("spacing " + i + " is " + (delays[i] - delays[i - 1]),
                       delays[i] - delays[i - 1] < MIN + SPREAD);
        }
    }

    /**
     * An empty batch produces no delays, and a single peer produces one immediate
     * dial rather than a sequence.
     */
    @Test
    public void testShortBatches() {
        assertEquals(0, TrackerClient.pacingDelays(new Random(1), 0, MIN, SPREAD).length);
        assertEquals(0, TrackerClient.pacingDelays(new Random(1), -3, MIN, SPREAD).length);
        assertEquals(1, TrackerClient.pacingDelays(new Random(1), 1, MIN, SPREAD).length);
    }

    /**
     * With no jitter the spread is exactly the floor each time, which is what a
     * caller that wants a fixed cadence gets.
     */
    @Test
    public void testNoJitterIsExactSpacing() {
        long[] delays = TrackerClient.pacingDelays(new Random(1), 4, MIN, 0);
        for (int i = 0; i < delays.length; i++) {
            assertEquals((long) i * MIN, delays[i]);
        }
    }

    /**
     * The whole batch stays bounded: a full set of outbound slots must not run the
     * last dial minutes after the first, however the jitter falls.
     */
    @Test
    public void testBatchStaysBounded() {
        for (int seed = 0; seed < 64; seed++) {
            long[] delays = TrackerClient.pacingDelays(new Random(seed), 16, MIN, SPREAD);
            assertEquals(16, delays.length);
            long total = delays[delays.length - 1];
            assertTrue("seed " + seed + " spread " + total,
                       total <= 15L * (MIN + SPREAD));
            for (int i = 1; i < delays.length; i++) {
                assertTrue("seed " + seed + " out of order at " + i, delays[i] >= delays[i - 1]);
            }
        }
    }

    /**
     * A batch is sized by the free outbound slots, not by how many peers the tracker
     * named, so a reply with hundreds of addresses does not start hundreds of dials.
     */
    @Test
    public void testBatchIsSizedByFreeSlots() {
        assertEquals(6, TrackerClient.pacedDialCount(250, 6));
        assertEquals(3, TrackerClient.pacedDialCount(3, 6));
        assertEquals(0, TrackerClient.pacedDialCount(0, 6));
        assertEquals(0, TrackerClient.pacedDialCount(250, 0));
        assertEquals(0, TrackerClient.pacedDialCount(250, -2));
    }
}
