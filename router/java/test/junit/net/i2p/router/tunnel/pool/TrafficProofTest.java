package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Tests that a tunnel provably carrying real traffic is not held down by
 * stale test bookkeeping: the pool clears FAILING and FAILED on either
 * direction once the real-traffic marker is fresh — inbound stamps only
 * after verified decrypt, outbound only after the remote reply — and
 * promotes traffic-proven UNTESTED tunnels so LeaseSet building can see
 * them.  A stale marker never revives a marked tunnel: proof has a window.
 *
 * @since 0.9.71+
 */
public class TrafficProofTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /** Real pool over a real context; manager and peer selector are mocks. */
    private static TunnelPool createPool(boolean isInbound) {
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        return new TunnelPool(_ctx, mgr, new TunnelPoolSettings(isInbound), sel);
    }

    /** Inject a tunnel into the pool's private list, bypassing build side effects. */
    private static void injectTunnel(TunnelPool pool, TunnelCreatorConfig cfg) throws Exception {
        Field field = TunnelPool.class.getDeclaredField("_tunnels");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TunnelInfo> tunnels = (List<TunnelInfo>) field.get(pool);
        tunnels.add(cfg);
    }

    /** A pooled config in the given state, with or without a fresh traffic marker. */
    private static PooledTunnelCreatorConfig config(int failures, boolean isInbound, boolean freshTraffic,
                                                    TunnelPool pool) throws Exception {
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, isInbound, null, pool);
        for (int i = 0; i < failures; i++) {
            cfg.incrementTestFailures();
        }
        if (failures > 0) {
            cfg.setTestFailed();
        }
        if (freshTraffic) {
            cfg.recordRealTraffic();
        }
        return cfg;
    }

    /** The marker starts at zero and advances when real traffic is recorded. */
    @Test
    public void testRealTrafficMarker() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = config(0, true, false, createPool(true));
        assertEquals(0, cfg.getLastRealTraffic());
        long before = System.currentTimeMillis();
        cfg.recordRealTraffic();
        assertTrue("Marker should advance", cfg.getLastRealTraffic() >= before);
    }

    /** Real traffic restores the recent-traffic test exemption budget.  It
     * used to be refilled only by a passing test, so a tunnel that carried
     * data but whose reply path could never answer a test got exactly one
     * free pass for its whole lifetime and was then condemned by failures
     * it had no way to clear. */
    @Test
    public void testRealTrafficRefillsExemptionBudget() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = config(0, true, false, createPool(true));
        cfg.incrementRecentTestExemptions();
        cfg.incrementRecentTestExemptions();
        assertEquals(2, cfg.getRecentTestExemptions());

        cfg.recordRealTraffic();

        assertEquals(0, cfg.getRecentTestExemptions());
    }

    /** recordRealTraffic() owns the budget and the marker; promotion back to
     * GOOD stays the sweep's job, so a traffic-carrying tunnel that is still
     * marked keeps its status until clearFailingOnTraffic() runs. */
    @Test
    public void testRecordRealTrafficLeavesStatusAlone() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(1, true, false, pool);
        assertEquals(TunnelTestStatus.FAILING, cfg.getTestStatus());

        cfg.recordRealTraffic();

        assertEquals(TunnelTestStatus.FAILING, cfg.getTestStatus());
        assertEquals(1, cfg.getConsecutiveFailures());
        assertEquals(0, cfg.getRecentTestExemptions());
    }

    /** Promotion back to GOOD also restores the budget: a revived tunnel must
     * not inherit the spend that produced the mark. */
    @Test
    public void testClearTestFailuresRestoresExemptionBudget() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        PooledTunnelCreatorConfig cfg = config(3, true, false, createPool(true));
        assertEquals(TunnelTestStatus.FAILED, cfg.getTestStatus());
        cfg.incrementRecentTestExemptions();
        assertEquals(1, cfg.getRecentTestExemptions());

        cfg.clearTestFailures();

        assertEquals(TunnelTestStatus.GOOD, cfg.getTestStatus());
        assertEquals(0, cfg.getRecentTestExemptions());
    }

    /** An inbound FAILING tunnel with fresh real traffic is cleared to GOOD. */
    @Test
    public void testInboundFailingClearedOnFreshTraffic() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(1, true, true, pool);
        assertEquals(TunnelTestStatus.FAILING, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.GOOD, cfg.getTestStatus());
        assertEquals(0, cfg.getConsecutiveFailures());
    }

    /** An inbound FAILING tunnel without recent traffic stays FAILING. */
    @Test
    public void testInboundFailingNotClearedWithoutTraffic() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(1, true, false, pool);
        assertEquals(TunnelTestStatus.FAILING, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.FAILING, cfg.getTestStatus());
        assertEquals(1, cfg.getConsecutiveFailures());
    }

    /** A FAILED tunnel is NOT cleared by a single traffic proof — FAILED
     * means 3+ consecutive test failures, which is conclusively dead.  A
     * single packet may be a fluke (partial delivery, stale path).  Only
     * FAILING (1-2 failures) clears on one proof. */
    @Test
    public void testFailedNotClearedOnSingleTraffic() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(3, true, true, pool);
        assertEquals(TunnelTestStatus.FAILED, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.FAILED, cfg.getTestStatus());
    }

    /** A FAILED tunnel whose traffic marker has aged out stays FAILED —
     * stale proof must not keep a dead tunnel selectable. */
    @Test
    public void testFailedNotClearedWithoutFreshTraffic() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(3, true, false, pool);
        assertEquals(TunnelTestStatus.FAILED, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.FAILED, cfg.getTestStatus());
        assertEquals(3, cfg.getConsecutiveFailures());
    }

    /** Outbound tunnels are cleared on fresh traffic too: the marker is
     * stamped by SendSuccessJob on the remote reply, not on dispatch. */
    @Test
    public void testOutboundClearedOnFreshTraffic() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(false);
        PooledTunnelCreatorConfig cfg = config(1, false, true, pool);
        assertEquals(TunnelTestStatus.FAILING, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.GOOD, cfg.getTestStatus());
        assertEquals(0, cfg.getConsecutiveFailures());
    }

    /** An outbound FAILING tunnel with no traffic at all stays FAILING. */
    @Test
    public void testOutboundNotClearedWithoutTraffic() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(false);
        PooledTunnelCreatorConfig cfg = config(1, false, false, pool);
        assertEquals(TunnelTestStatus.FAILING, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.FAILING, cfg.getTestStatus());
        assertEquals(1, cfg.getConsecutiveFailures());
    }

    /** GOOD tunnels are untouched by the sweep. */
    @Test
    public void testGoodUnaffected() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(0, true, true, pool);
        cfg.testSuccessful(100);
        assertEquals(TunnelTestStatus.GOOD, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.GOOD, cfg.getTestStatus());
        assertEquals(0, cfg.getConsecutiveFailures());
    }

    /** An inbound UNTESTED tunnel with verified inbound bytes + fresh traffic is
     * promoted to GOOD so LeaseSet building can publish it without waiting on a
     * TestJob that may never be scheduled. */
    @Test
    public void testInboundUntestedPromotedOnVerifiedTraffic() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(0, true, true, pool);
        cfg.incrementVerifiedBytesTransferred(1024);
        assertEquals(TunnelTestStatus.UNTESTED, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.GOOD, cfg.getTestStatus());
    }

    /** An inbound UNTESTED tunnel with fresh traffic but no verified bytes is NOT
     * promoted — without proof of arrival it must wait for a real test. */
    @Test
    public void testInboundUntestedNotPromotedWithoutVerifiedBytes() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(0, true, true, pool);
        assertEquals(0, cfg.getVerifiedBytesTransferred());
        assertEquals(TunnelTestStatus.UNTESTED, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.UNTESTED, cfg.getTestStatus());
    }

    /** A traffic-proven UNTESTED inbound tunnel is a LeaseSet top-up candidate. */
    @Test
    public void testTrafficProvenUntestedIsLeaseCandidate() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(0, true, true, pool);
        cfg.incrementVerifiedBytesTransferred(1024);
        long now = System.currentTimeMillis();
        cfg.setExpiration(now + 600_000L);
        long expireAfter = now - TestJob.TRAFFIC_PROOF_MS - 1; // far future expiry
        assertTrue(TunnelPool.isTrafficProvenUntestedLeaseCandidate(cfg, expireAfter, now));
    }

    /** No verified bytes → not a lease candidate even with recent traffic. */
    @Test
    public void testTrafficProvenUntestedNeedsVerifiedBytes() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(0, true, true, pool);
        long now = System.currentTimeMillis();
        cfg.setExpiration(now + 600_000L);
        long expireAfter = now - TestJob.TRAFFIC_PROOF_MS - 1;
        assertFalse(TunnelPool.isTrafficProvenUntestedLeaseCandidate(cfg, expireAfter, now));
    }

    /** A GOOD tunnel is not an UNTESTED top-up candidate (already counted separately). */
    @Test
    public void testGoodNotUntestedLeaseCandidate() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(0, true, true, pool);
        cfg.incrementVerifiedBytesTransferred(1024);
        cfg.testSuccessful(100);
        long now = System.currentTimeMillis();
        cfg.setExpiration(now + 600_000L);
        long expireAfter = now - TestJob.TRAFFIC_PROOF_MS - 1;
        assertFalse(TunnelPool.isTrafficProvenUntestedLeaseCandidate(cfg, expireAfter, now));
    }

    /** Expiring within the propagation window is not eligible. */
    @Test
    public void testExpiringUntestedNotLeaseCandidate() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(0, true, true, pool);
        cfg.incrementVerifiedBytesTransferred(1024);
        long now = System.currentTimeMillis();
        cfg.setExpiration(now); // tunnel expires now, before the propagation deadline
        long expireAfter = now;
        assertFalse(TunnelPool.isTrafficProvenUntestedLeaseCandidate(cfg, expireAfter, now));
    }
}
