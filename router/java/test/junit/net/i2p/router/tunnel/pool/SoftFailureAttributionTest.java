package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests for {@link SoftFailureAttribution}, the per-destination tally that
 * decides whether a soft (status-3) send failure counts against the tunnel
 * that carried it. A destination may charge at most
 * {@link SoftFailureAttribution#DEST_CONTRIBUTION_CAP} tunnels' worth of
 * credit per window, so one unreachable peer cannot retire every egress
 * tunnel that tried it, while a tunnel that fails against many destinations
 * is still retired.
 *
 * @since 0.9.72+
 */
public class SoftFailureAttributionTest {

    /** Arbitrary non-zero origin for the synthetic clock used throughout. */
    private static final long BASE = 1_000_000L;

    /** The tally under test, rebuilt for every case. */
    private SoftFailureAttribution _attr;

    /**
     * A fresh, empty tally — no destination has spent any credit yet.
     */
    public SoftFailureAttributionTest() {}

    /**
     * Reset the tally so cases stay independent of each other.
     */
    @Before
    public void setUp() {
        _attr = new SoftFailureAttribution();
    }

    /**
     * A destination distinct for each index — the tally is keyed by Hash, so
     * the instances must not be equal.
     *
     * @param i index whose low two bytes become the hash prefix
     * @return a destination hash no other index produces
     */
    private static Hash dest(int i) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) i;
        data[1] = (byte) (i >>> 8);
        return new Hash(data);
    }

    /**
     * With no destination in the report there is nothing else to blame, so the
     * failure is charged to the tunnel exactly as it was before attribution.
     */
    @Test
    public void unknownDestinationIsChargedToTheTunnel() {
        for (int i = 0; i < SoftFailureAttribution.DEST_CONTRIBUTION_CAP + 4; i++) {
            assertTrue("no destination means nothing else to blame",
                       _attr.countsAgainstTunnel(null, BASE));
        }
        assertEquals(0, _attr.trackedDestinations());
    }

    /**
     * The first {@code DEST_CONTRIBUTION_CAP} timeouts from a destination still
     * count against the tunnels that carried them.
     */
    @Test
    public void firstFailuresFromADestinationCount() {
        for (int i = 1; i <= SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
            assertTrue(_attr.countsAgainstTunnel(dest(1), BASE + i));
            assertEquals(i, _attr.failuresFrom(dest(1), BASE + i));
        }
    }

    /**
     * Past the allowance the destination is the common factor, however many
     * thousands of timeouts it goes on producing.
     */
    @Test
    public void pastTheCapTheDestinationIsBlamedNotTheTunnel() {
        Hash d = dest(7);
        for (int i = 0; i < SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
            assertTrue(_attr.countsAgainstTunnel(d, BASE));
        }
        for (int i = 0; i < 500; i++) {
            assertFalse(_attr.countsAgainstTunnel(d, BASE + 10));
        }
    }

    /**
     * A tunnel that fails against many different destinations keeps
     * accumulating — the cap is per destination, not per pool.
     */
    @Test
    public void eachDestinationGetsItsOwnAllowance() {
        for (int d = 0; d < 20; d++) {
            for (int i = 1; i <= SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
                assertTrue(_attr.countsAgainstTunnel(dest(d), BASE));
            }
            assertFalse(_attr.countsAgainstTunnel(dest(d), BASE));
        }
        assertEquals(20, _attr.trackedDestinations());
    }

    /**
     * A destination that has been quiet for a full window can charge tunnels
     * again, so a peer that recovered is not permanently exempt from blame.
     */
    @Test
    public void quietDestinationGetsAFreshAllowance() {
        Hash d = dest(3);
        for (int i = 0; i < SoftFailureAttribution.DEST_CONTRIBUTION_CAP + 2; i++) {
            _attr.countsAgainstTunnel(d, BASE);
        }
        assertFalse(_attr.countsAgainstTunnel(d, BASE));
        long later = BASE + SoftFailureAttribution.WINDOW_MS + 1;
        assertTrue(_attr.countsAgainstTunnel(d, later));
        assertEquals(1, _attr.failuresFrom(d, later));
    }

    /**
     * The window is inclusive: a failure exactly one window after the last is
     * still inside the history that earned the destination its exemption.
     */
    @Test
    public void stillChargedAtExactlyOneWindow() {
        Hash d = dest(4);
        for (int i = 0; i < SoftFailureAttribution.DEST_CONTRIBUTION_CAP + 1; i++) {
            _attr.countsAgainstTunnel(d, BASE);
        }
        assertFalse(_attr.countsAgainstTunnel(d, BASE + SoftFailureAttribution.WINDOW_MS));
    }

    /**
     * A router talks to far more destinations than the tally can hold, so the
     * map must stay bounded instead of growing with the address book.
     */
    @Test
    public void tallyIsBoundedOverManyDestinations() {
        for (int i = 0; i < SoftFailureAttribution.MAX_TRACKED_DESTINATIONS * 3; i++) {
            _attr.countsAgainstTunnel(dest(i), BASE);
        }
        assertTrue(_attr.trackedDestinations() <= SoftFailureAttribution.MAX_TRACKED_DESTINATIONS);
    }

    /**
     * Pruning takes the decayed destinations first, so a destination that is
     * failing right now is never evicted to make room.
     */
    @Test
    public void staleEntriesArePrunedBeforeEvictingRecentOnes() {
        Hash old = dest(1000);
        _attr.countsAgainstTunnel(old, BASE);
        long later = BASE + SoftFailureAttribution.WINDOW_MS + 1;
        for (int i = 0; i < SoftFailureAttribution.MAX_TRACKED_DESTINATIONS + 1; i++) {
            _attr.countsAgainstTunnel(dest(i), later);
        }
        assertEquals(0, _attr.failuresFrom(old, later));
    }

    /**
     * An unknown destination, and a null one, report no failures rather than
     * throwing — the log line asks this about a hash the caller may not have.
     */
    @Test
    public void unknownDestinationReportsNoFailures() {
        assertEquals(0, _attr.failuresFrom(dest(9), BASE));
        assertEquals(0, _attr.failuresFrom(null, BASE));
    }
}
