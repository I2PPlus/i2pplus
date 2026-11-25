package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests for {@link SoftFailureAttribution}, the tally that decides whether a
 * soft (status-3) send failure counts against the tunnel that carried it. A
 * destination may charge at most
 * {@link SoftFailureAttribution#DEST_CONTRIBUTION_CAP} tunnels' worth of credit
 * per window, so one unreachable peer cannot retire every egress tunnel that
 * tried it, while a tunnel that fails against many destinations is still
 * retired.
 *
 * <p>The second dimension is the hop. Once a destination is excused its further
 * failures cost the pool nothing, and a tally with only that rule can excuse
 * every failure in sight without ever asking whether they all rode the same
 * peer — measured live, one destination had absorbed 1,040 soft failures while
 * 68 failures were spread over ten different hops, and no tunnel was charged for
 * any of them. So a hop that carries
 * {@link SoftFailureAttribution#HOP_EXEMPT_CAP} excused failures for
 * {@link SoftFailureAttribution#HOP_DISTINCT_DESTS} destinations is charged
 * back to the tunnel.
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
     * A tunnel hop distinct from every destination and from every other index —
     * the two tallies are keyed by Hash, and a hop that collided with a
     * destination would let one test's destinations feed the other's hops.
     *
     * @param i index whose third and fourth bytes become the hash prefix
     * @return a hop hash no destination or other index produces
     */
    private static Hash hop(int i) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[2] = (byte) i;
        data[3] = (byte) (i >>> 8);
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

    // ---------- the hop dimension ----------

    /**
     * A destination past its cap is excused for the rest of the window, and if
     * every excused failure rode the same hop, that hop is the common factor,
     * not the destinations. Two destinations is enough to say so: one is what the
     * destination cap already excuses.
     */
    @Test
    public void oneHopCarryingTwoDestinationsIsCharged() {
        Hash a = dest(1);
        Hash b = dest(2);
        Hash h = hop(7);
        for (int i = 0; i < 2 * SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
            assertTrue("the first failures still charge the tunnel",
                       _attr.countsAgainstTunnel(i % 2 == 0 ? a : b, h, BASE));
        }
        for (int i = 0; i < SoftFailureAttribution.HOP_EXEMPT_CAP - 1; i++) {
            assertFalse("a pile-up below the hop cap stays excused",
                        _attr.countsAgainstTunnel(i % 2 == 0 ? a : b, h, BASE + i));
        }
        assertTrue("the hop has carried the cap across two destinations",
                   _attr.countsAgainstTunnel(a, h, BASE + 100));
        assertTrue("and it stays charged until the tally decays",
                   _attr.countsAgainstTunnel(b, h, BASE + 101));
    }

    /**
     * The pile-up has to span more than one destination. One destination timing
     * out through one hop is exactly the case the destination cap was written to
     * excuse, and the hop rule must not undo it.
     */
    @Test
    public void oneDestinationThroughOneHopStaysExempt() {
        Hash a = dest(1);
        Hash h = hop(7);
        for (int i = 0; i < SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
            _attr.countsAgainstTunnel(a, h, BASE);
        }
        for (int i = 0; i < SoftFailureAttribution.HOP_EXEMPT_CAP * 4; i++) {
            assertFalse(_attr.countsAgainstTunnel(a, h, BASE + i));
        }
    }

    /**
     * Two destinations, but not enough excused failures through their shared hop
     * yet — the boundary is the hop cap, not the destination count.
     */
    @Test
    public void aPileUpBelowTheHopCapStaysExempt() {
        Hash a = dest(1);
        Hash b = dest(2);
        Hash h = hop(7);
        for (int i = 0; i < 2 * SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
            _attr.countsAgainstTunnel(i % 2 == 0 ? a : b, h, BASE);
        }
        for (int i = 0; i < SoftFailureAttribution.HOP_EXEMPT_CAP - 1; i++) {
            assertFalse(_attr.countsAgainstTunnel(i % 2 == 0 ? a : b, h, BASE + i));
        }
        assertEquals(SoftFailureAttribution.HOP_EXEMPT_CAP - 1,
                     _attr.exemptFailuresThrough(h, BASE));
    }

    /**
     * A caller that cannot name the hop leaves the destination rule in charge —
     * the hop dimension measures, it does not guess.
     */
    @Test
    public void unnamedHopKeepsTheDestinationExempt() {
        Hash a = dest(1);
        for (int i = 0; i < SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
            _attr.countsAgainstTunnel(a, null, BASE);
        }
        for (int i = 0; i < SoftFailureAttribution.HOP_EXEMPT_CAP * 4; i++) {
            assertFalse(_attr.countsAgainstTunnel(a, null, BASE + i));
        }
        assertEquals(0, _attr.trackedHops());
        assertEquals(0, _attr.exemptFailuresThrough(null, BASE));
    }

    /**
     * The excused count is what the hop rule decides on, and it is not the
     * destination's count: the destination tally reports 1,040 while the hop
     * tally reports what this hop carried.
     */
    @Test
    public void excusedFailuresAreCountedPerHop() {
        Hash a = dest(1);
        Hash h = hop(7);
        for (int i = 0; i < SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
            _attr.countsAgainstTunnel(a, h, BASE);
        }
        assertEquals("charged failures are not excused ones", 0,
                     _attr.exemptFailuresThrough(h, BASE));
        _attr.countsAgainstTunnel(a, h, BASE + 1);
        assertEquals(1, _attr.exemptFailuresThrough(h, BASE + 1));
    }

    /**
     * A hop that has been quiet for a window starts clean, so a hop condemned
     * once is not condemned in advance for the next tunnel that uses it.
     */
    @Test
    public void quietHopTallyDecays() {
        Hash a = dest(1);
        Hash b = dest(2);
        Hash h = hop(7);
        for (int i = 0; i < SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
            _attr.countsAgainstTunnel(a, h, BASE);
            _attr.countsAgainstTunnel(b, h, BASE);
        }
        for (int i = 0; i < SoftFailureAttribution.HOP_EXEMPT_CAP; i++) {
            _attr.countsAgainstTunnel(i % 2 == 0 ? a : b, h, BASE);
        }
        long later = BASE + SoftFailureAttribution.WINDOW_MS + 1;
        assertEquals(0, _attr.exemptFailuresThrough(h, later));
    }

    /**
     * Every tunnel in every pool contributes a hop, so the hop tally needs a
     * ceiling of its own, pruned the same way the destination tally is.
     */
    @Test
    public void hopTallyIsBounded() {
        for (int h = 0; h < SoftFailureAttribution.MAX_TRACKED_HOPS + 8; h++) {
            Hash a = dest(h * 2 + 1);
            Hash b = dest(h * 2 + 2);
            for (int i = 0; i < 2 * SoftFailureAttribution.DEST_CONTRIBUTION_CAP; i++) {
                _attr.countsAgainstTunnel(i % 2 == 0 ? a : b, hop(h), BASE);
            }
            for (int i = 0; i < SoftFailureAttribution.HOP_EXEMPT_CAP; i++) {
                _attr.countsAgainstTunnel(i % 2 == 0 ? a : b, hop(h), BASE);
            }
        }
        assertTrue(_attr.trackedHops() <= SoftFailureAttribution.MAX_TRACKED_HOPS);
    }
}
