package net.i2p.router.tunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.i2p.router.Router;

import org.junit.Test;

/**
 * Which queued participating tunnels the LeaveTunnel job retires. The queue is
 * filled in join order, but the job used to stop at the first entry that had not
 * expired, on the assumption it was sorted by expiration. It never was, so every
 * expired entry behind a live head waited for that head's own expiration - up to
 * a full tunnel lifetime of holding a tunnel ID and transit bandwidth.
 *
 * @since 0.9.72+
 */
public class TunnelDispatcherExpiredConfigsTest {

    /** The dispatcher's private expiry fudge, rebuilt from the public constant. */
    private static final long FUDGE = (3 * Router.CLOCK_FUDGE_FACTOR / 2) + 1000;
    private static final long NOW = 10_000_000L;
    private static final long MIN = 60 * 1000L;

    /**
     * A config clearly past expiration plus the fudge.
     *
     * @return a HopConfig expiring FUDGE plus one minute before NOW, so the
     *         LeaveTunnel job must retire it
     */
    private static HopConfig dead() {
        return cfg(NOW - FUDGE - MIN);
    }

    /**
     * A config clearly inside the fudge window, so still live.
     *
     * @return a HopConfig expiring one minute after NOW, so the job leaves it
     *         queued
     */
    private static HopConfig live() {
        return cfg(NOW + MIN);
    }

    private static HopConfig cfg(long expiration) {
        HopConfig c = new HopConfig();
        c.setExpiration(expiration);
        return c;
    }

    private static ConcurrentLinkedQueue<HopConfig> queue(HopConfig... cfgs) {
        ConcurrentLinkedQueue<HopConfig> q = new ConcurrentLinkedQueue<>();
        q.addAll(Arrays.asList(cfgs));
        return q;
    }

    /** Nothing queued, nothing to retire. */
    @Test
    public void testEmptyQueue() {
        assertTrue(TunnelDispatcher.expiredConfigs(new ConcurrentLinkedQueue<HopConfig>(), NOW).isEmpty());
    }

    /** A live tunnel is not retired. */
    @Test
    public void testLiveTunnelNotRetired() {
        assertTrue(TunnelDispatcher.expiredConfigs(queue(live()), NOW).isEmpty());
    }

    /** An expired tunnel is retired. */
    @Test
    public void testExpiredTunnelRetired() {
        HopConfig dead = dead();
        List<HopConfig> rv = TunnelDispatcher.expiredConfigs(queue(dead), NOW);
        assertEquals(1, rv.size());
        assertSame(dead, rv.get(0));
    }

    /**
     * The head-of-line bug: a live head used to stop the scan, so the two expired
     * tunnels behind it were never retired until the head itself expired.
     */
    @Test
    public void testExpiredBehindALiveHeadAreRetired() {
        HopConfig head = live();
        HopConfig dead1 = cfg(NOW - FUDGE - 10 * MIN);
        HopConfig dead2 = cfg(NOW - FUDGE - 5 * MIN);
        List<HopConfig> rv = TunnelDispatcher.expiredConfigs(queue(head, dead1, dead2), NOW);
        assertEquals("both expired tunnels must be retired despite the live head",
                     2, rv.size());
        assertSame(dead1, rv.get(0));
        assertSame(dead2, rv.get(1));
    }

    /** Alternating live and expired: every expired one is found. */
    @Test
    public void testAlternatingOrder() {
        HopConfig a = cfg(NOW - FUDGE - 1000L);
        HopConfig b = live();
        HopConfig c = cfg(NOW - FUDGE - 2000L);
        HopConfig d = cfg(NOW + 2 * MIN);
        HopConfig e = cfg(NOW - FUDGE - 3000L);
        List<HopConfig> rv = TunnelDispatcher.expiredConfigs(queue(a, b, c, d, e), NOW);
        assertEquals(3, rv.size());
        assertSame(a, rv.get(0));
        assertSame(c, rv.get(1));
        assertSame(e, rv.get(2));
    }

    /** Queue order is preserved, so the removals stay predictable. */
    @Test
    public void testJoinOrderPreserved() {
        HopConfig first = cfg(NOW - FUDGE - 100 * MIN);
        HopConfig second = cfg(NOW - FUDGE - 10 * MIN);
        HopConfig third = cfg(NOW - FUDGE - 50 * MIN);
        List<HopConfig> rv = TunnelDispatcher.expiredConfigs(queue(first, second, third), NOW);
        assertEquals(3, rv.size());
        assertSame(first, rv.get(0));
        assertSame(second, rv.get(1));
        assertSame(third, rv.get(2));
    }

    /**
     * The fudge boundary: expiring exactly fudge ago is not yet retired, one
     * millisecond later is.
     */
    @Test
    public void testFudgeBoundary() {
        HopConfig atEdge = cfg(NOW - FUDGE);
        assertTrue(TunnelDispatcher.expiredConfigs(queue(atEdge), NOW).isEmpty());
        HopConfig pastEdge = cfg(NOW - FUDGE - 1);
        List<HopConfig> rv = TunnelDispatcher.expiredConfigs(queue(pastEdge), NOW);
        assertEquals(1, rv.size());
        assertSame(pastEdge, rv.get(0));
    }

    /** A large queue is scanned in full, not just its head. */
    @Test
    public void testLargeQueueScannedFully() {
        ConcurrentLinkedQueue<HopConfig> q = new ConcurrentLinkedQueue<>();
        q.add(live());   // live head - the old scan would have stopped here
        for (int i = 0; i < 500; i++)
            q.add(cfg(NOW - FUDGE - MIN - i * 1000L));
        assertEquals(500, TunnelDispatcher.expiredConfigs(q, NOW).size());
    }
}
