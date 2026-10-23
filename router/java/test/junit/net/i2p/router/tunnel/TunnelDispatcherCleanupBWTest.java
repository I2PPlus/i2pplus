package net.i2p.router.tunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The periodic sweep that drops expired transit tunnels. It used to delete the
 * dispatcher map entries by hand instead of going through remove(), which is the
 * single owner of the allocated-bandwidth accounting - so every tunnel the sweep
 * caught left its bandwidth counted as allocated for the rest of the run, and
 * left _lastParticipatingExpiration naming a tunnel that no longer existed.
 *
 * @since 0.9.72+
 */
public class TunnelDispatcherCleanupBWTest {

    private static final int BW = 1000;
    private static final long MIN = 60 * 1000L;

    private RouterContext _ctx;
    private TunnelDispatcher _dispatcher;
    private long _now;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _now = _ctx.clock().now();
        _dispatcher = new TunnelDispatcher(_ctx);
    }

    @After
    public void tearDown() {
        if (_dispatcher != null)
            _dispatcher.shutdown();
    }

    /**
     * A participant joined with a bandwidth allocation and an expiry an hour out.
     *
     * @param id the receive tunnel id to join with, unique among the test's participants
     * @return the config that was joined, for the caller to expire or charge later
     */
    private HopConfig join(long id) {
        HopConfig cfg = new HopConfig();
        cfg.setReceiveTunnelId(new TunnelId(id));
        cfg.setAllocatedBW(BW);
        // Joined live, then expired, so the LeaveTunnel job (scheduled at join
        // time) is not what removes it - the sweep has to.
        cfg.setExpiration(_now + 60 * MIN);
        assertTrue("join failed", _dispatcher.joinParticipant(cfg));
        return cfg;
    }

    /**
     * What the dispatcher actually charged for a joined tunnel. TunnelParticipant
     * raises a small allocation to its own per-tunnel estimate during the join,
     * so the requested BW is not what ends up in the total.
     *
     * @param cfg a config that has already been joined
     * @return the allocation now recorded on the config, in bytes per second
     */
    private static int charged(HopConfig cfg) {
        return cfg.getAllocatedBW();
    }

    /**
     * Move a joined tunnel well past expiration + fudge.
     *
     * @param cfg the joined config to push 10 minutes into the past
     */
    private void expire(HopConfig cfg) {
        cfg.setExpiration(_now - 10 * MIN);
    }

    /** The join itself allocates the bandwidth - the baseline the leak is measured against. */
    @Test
    public void testJoinAllocatesBandwidth() {
        HopConfig cfg = join(1);
        assertEquals(charged(cfg), _dispatcher.getAllocatedBW());
        assertEquals(1, _dispatcher.getParticipatingCount());
    }

    /**
     * The leak: the sweep removed the maps entries without giving the bandwidth
     * back, so getAllocatedBW() kept the expired tunnel's allocation forever.
     */
    @Test
    public void testSweepReleasesBandwidth() {
        HopConfig cfg = join(1);
        expire(cfg);
        assertEquals(1, _dispatcher.cleanupExpiredTunnels());
        assertEquals("expired tunnel must not keep its bandwidth allocated",
                     0, _dispatcher.getAllocatedBW());
    }

    /** The sweep also drops the tunnel from the participating count. */
    @Test
    public void testSweepClearsParticipatingCount() {
        HopConfig cfg = join(1);
        expire(cfg);
        _dispatcher.cleanupExpiredTunnels();
        assertEquals(0, _dispatcher.getParticipatingCount());
    }

    /** A tunnel that has not expired keeps its allocation. */
    @Test
    public void testLiveTunnelKeepsItsBandwidth() {
        HopConfig cfg = join(1);
        assertEquals(0, _dispatcher.cleanupExpiredTunnels());
        assertEquals(charged(cfg), _dispatcher.getAllocatedBW());
        assertEquals(1, _dispatcher.getParticipatingCount());
    }

    /**
     * Inside the expiry fudge window the config sweep leaves the tunnel alone, so
     * its bandwidth stays allocated - the sweep must not free early. Note that the
     * separate _participants sweep below it compares expiration to the cutoff with
     * no fudge at all, so it does count this tunnel; that asymmetry is recorded as
     * a finding, and it does not leak bandwidth, because the config and its
     * allocation are still released together once the fudge has passed.
     */
    @Test
    public void testFudgeWindowIsHonoured() {
        HopConfig cfg = join(2);
        // Expired, but only just - the fudge is 3x CLOCK_FUDGE_FACTOR/2 + 1s.
        cfg.setExpiration(_now - 1000L);
        _dispatcher.cleanupExpiredTunnels();
        assertEquals("still inside the fudge window", 1, _dispatcher.getParticipatingCount());
        assertEquals(charged(cfg), _dispatcher.getAllocatedBW());
    }

    /** Running the sweep twice must not free the bandwidth twice. */
    @Test
    public void testSweepIsIdempotent() {
        HopConfig cfg = join(1);
        expire(cfg);
        assertEquals(1, _dispatcher.cleanupExpiredTunnels());
        assertEquals(0, _dispatcher.cleanupExpiredTunnels());
        assertEquals("no double free", 0, _dispatcher.getAllocatedBW());
    }

    /**
     * remove() after the sweep must be a no-op, not a second decrement - the
     * sweep now goes through the same single-owner path.
     */
    @Test
    public void testRemoveAfterSweepDoesNotDoubleFree() {
        HopConfig cfg = join(1);
        expire(cfg);
        _dispatcher.cleanupExpiredTunnels();
        _dispatcher.remove(cfg);
        assertEquals(0, _dispatcher.getAllocatedBW());
    }

    /** Several expired tunnels all release, and the total lands exactly on zero. */
    @Test
    public void testMultipleExpiredTunnelsAllRelease() {
        int total = 0;
        for (long id = 10; id < 15; id++) {
            HopConfig cfg = join(id);
            total += charged(cfg);
            expire(cfg);
        }
        assertEquals(total, _dispatcher.getAllocatedBW());
        assertEquals(5, _dispatcher.cleanupExpiredTunnels());
        assertEquals(0, _dispatcher.getAllocatedBW());
        assertEquals(0, _dispatcher.getParticipatingCount());
    }

    /** A mix: only the expired ones are swept and only their bandwidth is freed. */
    @Test
    public void testMixedLiveAndExpired() {
        HopConfig live = join(1);
        HopConfig dead = join(2);
        expire(dead);
        assertEquals(1, _dispatcher.cleanupExpiredTunnels());
        assertEquals(charged(live), _dispatcher.getAllocatedBW());
        assertEquals(1, _dispatcher.getParticipatingCount());
        // and the survivor is untouched
        assertEquals(_now + 60 * MIN, live.getExpiration());
    }
}
