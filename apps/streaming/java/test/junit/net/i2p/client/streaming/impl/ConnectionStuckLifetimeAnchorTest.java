package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for what the stall backstop measures its give-up budget from, and for the
 * one hand-over that moves it.
 *
 * <p>The backstop is a wall-clock deadline, deliberately anchored so that
 * retransmit activity cannot extend it. It was anchored at the head-of-line
 * packet's creation, which is a fixed point only while the path stays the same —
 * and a stalled connection does not stay: the router is told to prefer another
 * outbound tunnel, and the same packet is retransmitted out of that one. The
 * budget the new tunnel was given was therefore whatever the old one had left
 * when it was abandoned. Read live on 2026-11-25, a stream that stalled 30s into
 * the 90s window had 60s left for a path that had not yet carried a byte, and
 * one that stalled at 80s had 10s — less than a round trip plus a retransmit, so
 * the recovery was over before it could be measured, and the transfer came back
 * truncated rather than slow.
 *
 * <p>Re-anchoring at the hand-over gives each path the budget it is actually being
 * tried for. It is allowed once, because a stream that stalls on every path in
 * turn would otherwise move its own deadline forward forever and never be closed.
 *
 * @since 0.9.72+
 */
public class ConnectionStuckLifetimeAnchorTest {

    /** Arbitrary non-zero origin for the synthetic clock used throughout. */
    private static final long BASE = 1_000_000L;

    /** The default {@code i2p.streaming.stallGiveupMs}. */
    private static final long BUDGET = 90_000L;

    /**
     * The decisions under test are static and take their state as arguments, so
     * the instance holds nothing between cases.
     */
    public ConnectionStuckLifetimeAnchorTest() {}

    /**
     * A connection that never changed tunnels keeps the deadline it has always
     * had, so this changes nothing about the common case.
     */
    @Test
    public void noHandOverKeepsTheCreationAnchor() {
        assertEquals(BASE, Connection.stuckLifetimeAnchor(BASE, 0));
    }

    /**
     * A hand-over later than the packet's creation is the point from which the
     * new path is being tried, so it is the deadline's origin.
     */
    @Test
    public void aLaterHandOverBecomesTheAnchor() {
        assertEquals(BASE + 30_000L, Connection.stuckLifetimeAnchor(BASE, BASE + 30_000L));
    }

    /**
     * A hand-over recorded before the packet was created — a clock adjustment, or
     * a packet enqueued after the stall was detected — cannot move the deadline
     * backwards and kill a live stream early.
     */
    @Test
    public void aHandOverBeforeCreationIsIgnored() {
        assertEquals(BASE, Connection.stuckLifetimeAnchor(BASE, BASE - 5_000L));
    }

    /**
     * The case the change exists for: a stream stalls 30s in, is handed a fresh
     * tunnel, and 70s later on that new tunnel is not yet out of budget — where
     * the creation anchor would have closed it 10s after the new path started.
     */
    @Test
    public void theNewPathGetsTheFullBudget() {
        long handOver = BASE + 30_000L;
        long now = BASE + 100_000L;
        // What the old anchor did: 100s since the packet was created, so the
        // stream closed 10s after the new tunnel started carrying it.
        assertTrue(Connection.stuckLifetimeExceeded(BUDGET, now, BASE));
        // What the hand-over anchor does: 70s on the path being tried.
        assertFalse(Connection.stuckLifetimeExceeded(BUDGET, now,
                                                    Connection.stuckLifetimeAnchor(BASE, handOver)));
    }

    /**
     * Re-anchoring postpones the backstop, it does not remove it: a full budget
     * on the new path still closes the stream, and the exact boundary stays alive.
     */
    @Test
    public void theBackstopStillFiresOnTheNewPath() {
        long anchor = BASE + 30_000L;
        assertFalse(Connection.stuckLifetimeExceeded(BUDGET, anchor + BUDGET, anchor));
        assertTrue(Connection.stuckLifetimeExceeded(BUDGET, anchor + BUDGET + 1, anchor));
    }

    /**
     * A budget of zero disables the backstop whatever it is anchored at, so a
     * hand-over cannot resurrect a check the configuration turned off.
     */
    @Test
    public void aDisabledBudgetStaysDisabled() {
        assertFalse(Connection.stuckLifetimeExceeded(0, BASE + 10 * BUDGET, BASE + 30_000L));
    }

    /**
     * The first stall records the hand-over.
     */
    @Test
    public void firstStallTakesTheAnchor() {
        assertEquals(BASE + 30_000L, Connection.stuckLifetimeReanchor(BASE + 30_000L, 0));
    }

    /**
     * And only the first. A stream that stalls again on the replacement path must
     * not buy itself a second full budget, or the backstop it exists to bound
     * never arrives.
     */
    @Test
    public void aSecondStallDoesNotMoveTheAnchor() {
        long first = BASE + 30_000L;
        assertEquals(first, Connection.stuckLifetimeReanchor(BASE + 70_000L, first));
    }
}
