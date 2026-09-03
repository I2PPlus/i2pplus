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
 * Tests that a tunnel provably carrying real traffic is not marked failed:
 * the pool clears the FAILING flag on inbound tunnels with fresh real-traffic
 * markers (arrival is end-to-end proof), while outbound tunnels — where
 * dispatch is use, not proof — and FAILED tunnels stay removal-bound.
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

    /** An inbound FAILED tunnel is never cleared — it stays removal-bound. */
    @Test
    public void testFailedNeverCleared() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        PooledTunnelCreatorConfig cfg = config(3, true, true, pool);
        assertEquals(TunnelTestStatus.FAILED, cfg.getTestStatus());
        injectTunnel(pool, cfg);

        pool.clearFailingOnTraffic();

        assertEquals(TunnelTestStatus.FAILED, cfg.getTestStatus());
        assertEquals(3, cfg.getConsecutiveFailures());
    }

    /** Outbound tunnels are never cleared on dispatch traffic — it is use, not proof. */
    @Test
    public void testOutboundNeverClearedOnTraffic() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(false);
        PooledTunnelCreatorConfig cfg = config(1, false, true, pool);
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
     *  promoted to GOOD so LeaseSet building can publish it without waiting on a
     *  TestJob that may never be scheduled. */
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
     *  promoted — without proof of arrival it must wait for a real test. */
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
