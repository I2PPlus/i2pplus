package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 * Tests the pool maintenance floor ({@link TunnelPool#getEffectiveTarget()})
 * and the LeaseSet quality comparator ({@link TunnelPool#QUALITY_COMPARATOR})
 * that back the build → publish → prune tunnel maintenance loop.
 *
 * @since 0.9.70+
 */
public class TunnelPoolTargetTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /** Real pool over a real context; manager and peer selector are mocks. */
    private static TunnelPool createPool(TunnelPoolSettings settings) {
        TunnelPoolManager mgr = mock(TunnelPoolManager.class);
        TunnelPeerSelector sel = mock(TunnelPeerSelector.class);
        return new TunnelPool(_ctx, mgr, settings, sel);
    }

    /** A quantity-1 pool must still maintain 2 tunnels per direction. */
    @Test
    public void testFloorAppliesToSmallQuantity() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setQuantity(1);
        assertEquals(2, createPool(settings).getEffectiveTarget());
    }

    /** Configured quantities at or above the floor are kept as-is. */
    @Test
    public void testConfiguredQuantityAboveFloorUnchanged() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setQuantity(3);
        assertEquals(3, createPool(settings).getEffectiveTarget());
    }

    /** Expressly zero-hop pools keep their configured quantity. */
    @Test
    public void testZeroHopPoolKeepsQuantity() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setQuantity(1);
        settings.setLength(0);
        assertTrue(settings.isZeroHop());
        assertEquals(1, createPool(settings).getEffectiveTarget());
    }

    /** lengthOverride 0 is an explicit zero-hop configuration. */
    @Test
    public void testLengthOverrideZeroIsZeroHop() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setQuantity(1);
        settings.setLength(2);
        settings.setLengthOverride(0);
        assertTrue(settings.isZeroHop());
        assertEquals(1, createPool(settings).getEffectiveTarget());
    }

    /** A normal multi-hop pool is not zero-hop. */
    @Test
    public void testMultiHopNotZeroHop() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setLength(2);
        settings.setLengthVariance(1);
        assertFalse(settings.isZeroHop());
    }

    /** Ping pools keep their configured quantity — they're short-lived test tunnels. */
    @Test
    public void testPingPoolKeepsQuantity() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPoolSettings settings = new TunnelPoolSettings(false);
        settings.setQuantity(1);
        settings.setDestinationNickname("Ping [abcdef]");
        assertEquals(1, createPool(settings).getEffectiveTarget());
    }

    /**
     * Build a real tunnel config with the given age, latency and failure
     * count for comparator ordering tests.
     */
    private static TunnelCreatorConfig config(long expiration, int latency, int failures) throws Exception {
        TunnelPool pool = createPool(new TunnelPoolSettings(false));
        TunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
        cfg.setExpiration(expiration);
        if (latency >= 0) {
            cfg.addLatencySample(latency);
        }
        if (failures > 0) {
            Field f = TunnelCreatorConfig.class.getDeclaredField("_failures");
            f.setAccessible(true);
            ((AtomicInteger) f.get(cfg)).set(failures);
        }
        return cfg;
    }

    /** Fresher tunnels sort first — the LeaseSet should hold the longest-lived leases. */
    @Test
    public void testComparatorFreshestFirst() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        long now = System.currentTimeMillis();
        TunnelCreatorConfig fresh = config(now + 9L * 60 * 1000, 500, 0);
        TunnelCreatorConfig old = config(now + 5L * 60 * 1000, 200, 0);
        List<TunnelCreatorConfig> list = new ArrayList<>();
        list.add(old);
        list.add(fresh);
        list.sort(TunnelPool.QUALITY_COMPARATOR);
        assertSame(fresh, list.get(0));
    }

    /** Among same-age tunnels, lower latency wins. */
    @Test
    public void testComparatorLatencyTiebreak() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        long now = System.currentTimeMillis();
        TunnelCreatorConfig fast = config(now + 6L * 60 * 1000, 400, 0);
        TunnelCreatorConfig slow = config(now + 6L * 60 * 1000, 1200, 0);
        List<TunnelCreatorConfig> list = new ArrayList<>();
        list.add(slow);
        list.add(fast);
        list.sort(TunnelPool.QUALITY_COMPARATOR);
        assertSame(fast, list.get(0));
    }

    /** Tunnels without latency measurements sort after measured ones. */
    @Test
    public void testComparatorUnknownLatencyLast() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        long now = System.currentTimeMillis();
        TunnelCreatorConfig measured = config(now + 6L * 60 * 1000, 800, 0);
        TunnelCreatorConfig unmeasured = config(now + 6L * 60 * 1000, -1, 0);
        List<TunnelCreatorConfig> list = new ArrayList<>();
        list.add(unmeasured);
        list.add(measured);
        list.sort(TunnelPool.QUALITY_COMPARATOR);
        assertSame(measured, list.get(0));
    }

    /** Among tunnels with identical age and latency, fewer failures wins. */
    @Test
    public void testComparatorFailureTiebreak() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        long now = System.currentTimeMillis();
        TunnelCreatorConfig healthy = config(now + 6L * 60 * 1000, 600, 0);
        TunnelCreatorConfig failing = config(now + 6L * 60 * 1000, 600, 3);
        List<TunnelCreatorConfig> list = new ArrayList<>();
        list.add(failing);
        list.add(healthy);
        list.sort(TunnelPool.QUALITY_COMPARATOR);
        assertSame(healthy, list.get(0));
    }
}
