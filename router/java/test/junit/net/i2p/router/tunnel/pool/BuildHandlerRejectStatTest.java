package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

/**
 * The rejection-reason counters for build requests we turn down. The name written
 * by handleReq() must be one BuildHandler registers: StatManager.addRateData()
 * drops writes to an unregistered name without a trace, so the earlier
 * "tunnel.reject." + response form counted nothing for its entire life while
 * looking like a working per-reason counter.
 *
 * @since 0.9.72+
 */
public class BuildHandlerRejectStatTest {

    /** Every reject code TunnelHistory defines. */
    private static final int[] ALL_CODES = {
        TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT,
        TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD,
        TunnelHistory.TUNNEL_REJECT_BANDWIDTH,
        TunnelHistory.TUNNEL_REJECT_CRIT,
        TunnelHistory.TUNNEL_REJECT_STREAMING_NACK,
    };

    private static StatManager registeredStats() {
        StatManager mgr = new StatManager(I2PAppContext.getGlobalContext());
        BuildHandler.createRejectStats(mgr, RateConstants.SHORT_TERM_RATES);
        return mgr;
    }

    /** The invariant: any name rejectStatName() returns must be registered. */
    @Test
    public void testEveryMappedNameIsRegistered() {
        StatManager mgr = registeredStats();
        for (int code : ALL_CODES) {
            String name = BuildHandler.rejectStatName(code);
            if (name == null)
                continue;
            assertNotNull(name + " must be registered, or addRateData() drops it silently",
                        mgr.getRate(name));
        }
    }

    /**
     * The three codes handleReq() actually produces are counted. The
     * probabilistic one comes from RouterThrottleImpl.acceptTunnelRequest(), so
     * it is easy to miss when reading handleReq() alone, and leaving it out
     * means the rejections the throttler is busiest making are the ones with no
     * counter.
     */
    @Test
    public void testReachableCodesAreMapped() {
        assertEquals("tunnel.rejectBandwidth",
                     BuildHandler.rejectStatName(TunnelHistory.TUNNEL_REJECT_BANDWIDTH));
        assertEquals("tunnel.rejectTransient",
                     BuildHandler.rejectStatName(TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD));
        assertEquals("tunnel.rejectProbabilistic",
                     BuildHandler.rejectStatName(TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT));
    }

    /**
     * A code with no stat maps to null rather than to a name nobody registered.
     * Returning a plausible-looking unregistered name is the bug this guards.
     */
    @Test
    public void testUnmappedCodesReturnNull() {
        assertNull(BuildHandler.rejectStatName(0));
        assertNull(BuildHandler.rejectStatName(TunnelHistory.TUNNEL_REJECT_CRIT));
        assertNull(BuildHandler.rejectStatName(TunnelHistory.TUNNEL_REJECT_STREAMING_NACK));
        assertNull(BuildHandler.rejectStatName(-1));
        assertNull(BuildHandler.rejectStatName(9999));
    }

    /**
     * The refactor artefact that made the old counters invisible was a numeric
     * suffix on the stat name. Forbid reintroducing it.
     */
    @Test
    public void testNoDottedNumericName() {
        for (int code : ALL_CODES) {
            String name = BuildHandler.rejectStatName(code);
            if (name == null)
                continue;
            assertTrue(name + " must not end in a numeric suffix",
                       !name.matches(".*\\.\\d+$"));
            assertTrue(name + " must live in the tunnel group", name.startsWith("tunnel."));
        }
    }

    /** Three codes must not collapse onto one counter. */
    @Test
    public void testNamesAreDistinct() {
        Set<String> names = new HashSet<>();
        for (int code : ALL_CODES) {
            String name = BuildHandler.rejectStatName(code);
            if (name != null)
                assertTrue("duplicate stat name: " + name, names.add(name));
        }
        assertEquals(3, names.size());
    }

    /** Writing through the mapped name must actually move a counter. */
    @Test
    public void testWriteLandsInRegisteredStat() {
        StatManager mgr = registeredStats();
        String name = BuildHandler.rejectStatName(TunnelHistory.TUNNEL_REJECT_BANDWIDTH);
        mgr.addRateData(name, 1);
        assertEquals(1, mgr.getRate(name).getLifetimeEventCount());
    }

    /** Writing an unmapped name is a silent no-op - the failure mode being fixed. */
    @Test
    public void testUnregisteredNameIsSilentlyDropped() {
        StatManager mgr = registeredStats();
        String dead = "tunnel.reject." + TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        mgr.addRateData(dead, 1);
        assertNull("an unregistered stat name must not exist", mgr.getRate(dead));
    }
}
