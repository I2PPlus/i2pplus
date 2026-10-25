package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for PeerState.shouldBanForDiscardRatio, the decision that bans a peer
 * cancelling most of what it is served. Both sides must be windowed volumes:
 * the pre-fix code compared lifetime cancelled bytes against Peer's rolling
 * 5-second uploaded counter, so any long-lived healthy peer eventually tripped
 * the ban.
 *
 * @since 0.9.72+
 */
public class PeerStateDiscardDecisionTest {

    private static final long MIN = 5L * 1024 * 1024;

    @Test
    public void testBelowVolumeFloorNeverBans() {
        // all cancelled but under the volume floor - congestion, not malice
        assertFalse(PeerState.shouldBanForDiscardRatio(MIN - 1, 0));
        assertFalse(PeerState.shouldBanForDiscardRatio(0, 0));
    }

    @Test
    public void testAtFloorWithAllCancelledBans() {
        assertTrue(PeerState.shouldBanForDiscardRatio(MIN, 0));
    }

    @Test
    public void testNinetyPercentBoundary() {
        // exactly 90% cancelled bans (>= DISCARD_BAN_PERCENT)
        long total = MIN;
        assertTrue(PeerState.shouldBanForDiscardRatio(total * 90 / 100, total / 10));
        // just under 90% does not
        assertFalse(PeerState.shouldBanForDiscardRatio(total * 90 / 100 - 1, total - total * 90 / 100 + 1));
    }

    @Test
    public void testHealthyRatioNeverBans() {
        // heavy cancelling but a third of volume served - normal churn
        assertFalse(PeerState.shouldBanForDiscardRatio(MIN * 2, MIN));
        // tiny cancel count against real uploads
        assertFalse(PeerState.shouldBanForDiscardRatio(16 * 1024, MIN));
    }
}
