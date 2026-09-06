package net.i2p.router.transport.udp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins connection-migration decision logic extracted from
 * PeerState2.receivePacket(). The decision methods are pure and require no
 * router context, so the full migration-state classifier is exercised here.
 *
 * @since 0.9.71+
 */
public class PeerState2DecisionTest {

    /** mirrored from PeerState2 so tests are independent of field privacy */
    private static final int MAX_PATH_CHALLENGE_SENDS = 4;
    private static final long MAX_PATH_CHALLENGE_TIME = 30*1000L;

    @Test
    public void testInitiateMigrationRequiresAllConditions() {
        assertTrue(PeerState2.shouldInitiateMigration(true, true, true, true));
    }

    @Test
    public void testInitiateMigrationFalseOnAnyMissingCondition() {
        assertFalse(PeerState2.shouldInitiateMigration(false, true, true, true));
        assertFalse(PeerState2.shouldInitiateMigration(true, false, true, true));
        assertFalse(PeerState2.shouldInitiateMigration(true, true, false, true));
        assertFalse(PeerState2.shouldInitiateMigration(true, true, true, false));
        assertFalse(PeerState2.shouldInitiateMigration(false, false, false, false));
    }

    @Test
    public void testPendingFromCurrentRemoteCancels() {
        // from == _remoteHostId, regardless of anything else
        assertSame(PeerState2.MigrationPendingDecision.CANCEL,
                   PeerState2.decidePendingMigration(999999L, true, true, 0L, 0L, 0L));
        assertSame(PeerState2.MigrationPendingDecision.CANCEL,
                   PeerState2.decidePendingMigration(999999L, true, false, 0L, 99L, 0L));
    }

    @Test
    public void testPendingTimeoutBoundaryNotExceededStaysActive() {
        long started = 1000L;
        // at exactly started + MAX_PATH_CHALLENGE_TIME, not expired yet
        PeerState2.MigrationPendingDecision at =
            PeerState2.decidePendingMigration(started + MAX_PATH_CHALLENGE_TIME, false, true, started, 0L, 0L);
        assertNotEquals(PeerState2.MigrationPendingDecision.EXPIRED, at);
    }

    @Test
    public void testPendingTimeoutExpiredJustPastBoundary() {
        long started = 1000L;
        assertSame(PeerState2.MigrationPendingDecision.EXPIRED,
                   PeerState2.decidePendingMigration(started + MAX_PATH_CHALLENGE_TIME + 1, false, true, started, 0L, 0L));
    }

    @Test
    public void testPendingSendCountBoundaryNotExceededStaysActive() {
        assertNotEquals(PeerState2.MigrationPendingDecision.EXPIRED,
                        PeerState2.decidePendingMigration(0L, false, true, 0L, MAX_PATH_CHALLENGE_SENDS, 0L));
    }

    @Test
    public void testPendingSendCountExpiredPastBoundary() {
        assertSame(PeerState2.MigrationPendingDecision.EXPIRED,
                   PeerState2.decidePendingMigration(0L, false, true, 0L, MAX_PATH_CHALLENGE_SENDS + 1L, 0L));
    }

    @Test
    public void testPendingRetransmitsOnlyWhenPastDue() {
        long now = 100000L;
        long started = now - 1000L; // keep within the 30s challenge budget
        // now > nextSendTime -> retransmit
        assertSame(PeerState2.MigrationPendingDecision.RETRANSMIT,
                   PeerState2.decidePendingMigration(now, false, true, started, 0L, now - 1L));
        // now == nextSendTime turns the strict greater-than into wait
        assertSame(PeerState2.MigrationPendingDecision.STILL_WAITING,
                   PeerState2.decidePendingMigration(now, false, true, started, 0L, now));
        assertSame(PeerState2.MigrationPendingDecision.STILL_WAITING,
                   PeerState2.decidePendingMigration(now, false, true, started, 0L, now + 1L));
    }

    @Test
    public void testPendingThirdAddressDoesNotSwitch() {
        // not current remote, not the pending host, within budget
        assertSame(PeerState2.MigrationPendingDecision.THIRD_ADDRESS,
                   PeerState2.decidePendingMigration(0L, false, false, 0L, 0L, 0L));
    }
}
