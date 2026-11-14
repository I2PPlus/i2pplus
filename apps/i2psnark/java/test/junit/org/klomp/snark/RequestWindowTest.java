package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins how many blocks one peer may have in flight. The window used to be a deadband on the
 * aggregate download rate against the aggregate bandwidth limit, so it only ever tracked how
 * busy the router was: in the middle of the band a peer was capped at two blocks however well
 * it was delivering, and a peer that had stalled kept the same window as a healthy one. It is
 * now a time target taken from that peer's own measured rate.
 *
 * @since 0.9.72+
 */
public class RequestWindowTest {

    /** The floor used by the fixtures, the shipped default. */
    private static final int MIN = 5;

    /** The ceiling used by the fixtures, the shipped default. */
    private static final int MAX = 32;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decisions under test are static and carry no state.
     *
     * @since 0.9.72+
     */
    public RequestWindowTest() {}

    /**
     * A peer delivering faster gets a larger window, which is what the old rule could not
     * do: at the same aggregate rate, a 100 KiB/s peer and an 8 KiB/s peer got the same
     * window.
     */
    @Test
    public void testFasterPeerGetsMoreWindow() {
        int slow = PeerState.requestWindow(8 * 1024, PeerState.REQUEST_RTT_SECONDS, MIN, MAX);
        int mid = PeerState.requestWindow(100 * 1024, PeerState.REQUEST_RTT_SECONDS, MIN, MAX);
        int fast = PeerState.requestWindow(1024 * 1024, PeerState.REQUEST_RTT_SECONDS, MIN, MAX);
        assertTrue(slow + " vs " + mid, slow <= mid);
        assertTrue(mid + " vs " + fast, mid < fast);
    }

    /**
     * The window is the blocks the peer can pull in the round-trip budget, so a 100 KiB/s
     * peer with a three second budget holds a little under three seconds of 16 KiB blocks.
     */
    @Test
    public void testWindowIsTheRateTimesTheRoundTrip() {
        // 32 KiB/s, 64 KiB/s and 100 KiB/s over a three second budget, in 16 KiB blocks
        assertEquals(6, PeerState.requestWindow(32 * 1024, PeerState.REQUEST_RTT_SECONDS,
                                                MIN, MAX));
        assertEquals(12, PeerState.requestWindow(64 * 1024, PeerState.REQUEST_RTT_SECONDS,
                                                 MIN, MAX));
        assertEquals(18, PeerState.requestWindow(100 * 1024, PeerState.REQUEST_RTT_SECONDS,
                                                 MIN, MAX));
    }

    /**
     * A peer well past the ceiling is held at it, so a fast peer cannot queue an unbounded
     * amount of data against a slow disk.
     */
    @Test
    public void testWindowIsCapped() {
        assertEquals(MAX, PeerState.requestWindow(1024 * 1024, PeerState.REQUEST_RTT_SECONDS,
                                                  MIN, MAX));
        assertEquals(MAX, PeerState.requestWindow(Long.MAX_VALUE / 64,
                                                  PeerState.REQUEST_RTT_SECONDS, MIN, MAX));
    }

    /**
     * A peer slower than the floor still gets the floor, so a trickle is still worth asking
     * of, and a peer with no sample yet - before the first checker pass has measured it -
     * starts at the floor and ramps from there.
     */
    @Test
    public void testSlowAndUnmeasuredPeersGetTheFloor() {
        assertEquals(MIN, PeerState.requestWindow(1, PeerState.REQUEST_RTT_SECONDS, MIN, MAX));
        assertEquals(MIN, PeerState.requestWindow(0, PeerState.REQUEST_RTT_SECONDS, MIN, MAX));
        assertEquals(MIN, PeerState.requestWindow(-1, PeerState.REQUEST_RTT_SECONDS, MIN, MAX));
    }

    /**
     * Across the whole range of plausible rates the window stays inside its bounds and never
     * falls as the rate rises.
     */
    @Test
    public void testBoundsAndMonotonicity() {
        int prev = -1;
        for (long rate = 0; rate <= 4L * 1024 * 1024; rate += 1024) {
            int win = PeerState.requestWindow(rate, PeerState.REQUEST_RTT_SECONDS, MIN, MAX);
            assertTrue(rate + " -> " + win, win >= MIN);
            assertTrue(rate + " -> " + win, win <= MAX);
            assertTrue(Long.toString(rate), win >= prev);
            prev = win;
        }
    }

    /**
     * The window is sized from the peer, not from the router's spare bandwidth, so a peer
     * that is delivering well is not held to the one or two blocks the old deadband gave it
     * in the middle of the band.
     */
    @Test
    public void testHealthyPeerIsNotHeldToTheOldDeadbandCap() {
        int win = PeerState.requestWindow(100 * 1024, PeerState.REQUEST_RTT_SECONDS, MIN, MAX);
        assertTrue(Integer.toString(win), win > 2);
    }

    /**
     * The round-trip budget is the same three seconds the request loop already assumes when
     * it budgets bytes, so the two bounds cannot disagree about how much is in flight.
     */
    @Test
    public void testRoundTripMatchesTheByteBudget() {
        assertEquals(3, PeerState.REQUEST_RTT_SECONDS);
    }

    /**
     * The window climbs one block at a time towards its target rather than jumping to it, so
     * a peer finds its own pipe instead of being handed a full window at once.
     */
    @Test
    public void testSlowStartRampsOneBlockAtATime() {
        int cur = 0;
        int target = 12;
        for (int i = 0; i < target; i++) {
            cur = PeerState.nextWindow(cur, target);
            assertEquals(i + 1, cur);
        }
        assertEquals(target, PeerState.nextWindow(cur, target));
        assertEquals(target + 1, PeerState.nextWindow(target, target + 1));
    }

    /**
     * A window that has to shrink does so at once: a peer that has slowed must stop being
     * fed immediately, not over the next several requests.
     */
    @Test
    public void testShrinkIsImmediate() {
        assertEquals(1, PeerState.nextWindow(30, 1));
        assertEquals(4, PeerState.nextWindow(30, 4));
        assertEquals(30, PeerState.nextWindow(30, 30));
    }

    /**
     * A snubbed or stalled peer, whose measured rate has fallen to nothing, ends up back at
     * the floor after enough passes to walk the window down.
     */
    @Test
    public void testStalledPeerWalksBackToTheFloor() {
        int cur = MAX;
        int target = PeerState.requestWindow(0, PeerState.REQUEST_RTT_SECONDS, MIN, MAX);
        for (int i = 0; i < MAX; i++) {
            cur = PeerState.nextWindow(cur, target);
        }
        assertEquals(MIN, cur);
    }
}
