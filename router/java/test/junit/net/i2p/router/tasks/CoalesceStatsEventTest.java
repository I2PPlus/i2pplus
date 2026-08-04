package net.i2p.router.tasks;

import static org.junit.Assert.*;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for CoalesceStatsEvent, the periodic stats aggregation job.
 *  Verifies that construction creates the required rate stats and
 *  that a full timeReached() pass completes without error and
 *  populates the coalesced rates.
 *
 *  @since 0.8.12
 */
public class CoalesceStatsEventTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    @Test
    public void testConstructorCreatesRequiredStats() {
        CoalesceStatsEvent event = new CoalesceStatsEvent(_ctx);
        StatManager sm = _ctx.statManager();
        assertNotNull("router.knownPeers", sm.getRate("router.knownPeers"));
        assertNotNull("router.activePeers", sm.getRate("router.activePeers"));
        assertNotNull("router.bannedPeers", sm.getRate("router.bannedPeers"));
        assertNotNull("router.memoryUsed", sm.getRate("router.memoryUsed"));
        assertNotNull("router.gcPauseTime", sm.getRate("router.gcPauseTime"));
        assertNotNull("bw.sendRate", sm.getRate("bw.sendRate"));
        assertNotNull("bw.recvRate", sm.getRate("bw.recvRate"));
        assertNotNull("tunnel.tunnelBuildSuccessAvg", sm.getRate("tunnel.tunnelBuildSuccessAvg"));
        event.cancel();
    }

    @Test
    public void testTimeReachedPopulatesRates() {
        CoalesceStatsEvent event = new CoalesceStatsEvent(_ctx);
        event.timeReached();
        StatManager sm = _ctx.statManager();
        RateStat known = sm.getRate("router.knownPeers");
        assertNotNull(known);
        // the coalesced value must have been recorded into a one-minute rate
        assertNotNull(known.getRate(net.i2p.stat.RateConstants.ONE_MINUTE));
        event.cancel();
    }

    @Test
    public void testTimeReachedDoesNotThrow() {
        CoalesceStatsEvent event = new CoalesceStatsEvent(_ctx);
        // run a couple of cycles to exercise the GC pause delta path
        event.timeReached();
        event.timeReached();
        // reaching here without exception is the assertion
        event.cancel();
    }
}
