package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.router.tunnel.TunnelDispatcher;
import net.i2p.util.RandomSource;

/**
 * Bandwidth accounting for an idle-tunnel drop. TunnelDispatcher.remove() is the
 * single owner of the _allocatedBW decrement; IdleTunnelMonitor.dropTunnel() used
 * to call freeBandwidth() again on top of it, so every idle drop pushed the
 * transit total negative by one tunnel's allocation and it never came back.
 * <p>
 * The per-hop allocation is recomputed by TunnelParticipant on join, so these
 * tests read the value back rather than assuming the one they asked for.
 *
 * @since 0.9.72+
 */
public class IdleTunnelMonitorBandwidthTest {

    private static RouterContext ctx;

    @BeforeClass
    public static void setUp() {
        ctx = RouterTestHelper.newContext();
    }

    /** A participant hop that joinParticipant() will accept. */
    private static HopConfig hop(long recvId, int requestedBW) {
        byte[] h = new byte[Hash.HASH_LENGTH];
        RandomSource.getInstance().nextBytes(h, 0, h.length);
        Hash peer = new Hash(h);
        HopConfig cfg = new HopConfig();
        cfg.setCreation(ctx.clock().now());
        cfg.setExpiration(ctx.clock().now() + 10 * 60 * 1000L);
        cfg.setReceiveTunnelId(recvId);
        cfg.setReceiveFrom(peer);
        cfg.setSendTo(peer);
        cfg.setSendTunnelId(recvId + 1);
        cfg.setAllocatedBW(requestedBW);
        return cfg;
    }

    /**
     * The monitor reads its dispatcher from the context, so point it at the one
     * this test controls.
     */
    private static IdleTunnelMonitor monitorFor(TunnelDispatcher disp) throws Exception {
        IdleTunnelMonitor mon = new IdleTunnelMonitor(ctx);
        Field f = IdleTunnelMonitor.class.getDeclaredField("_dispatcher");
        f.setAccessible(true);
        f.set(mon, disp);
        return mon;
    }

    /** One drop must free exactly one tunnel's allocation - no more. */
    @Test
    public void testDropFreesBandwidthExactlyOnce() throws Exception {
        TunnelDispatcher disp = new TunnelDispatcher(ctx);
        HopConfig cfg = hop(1234L, 5000);
        assertTrue("join must be accepted", disp.joinParticipant(cfg));
        int allocated = cfg.getAllocatedBW();
        assertTrue("the hop must carry a non-zero allocation for this test to mean anything",
                   allocated > 0);
        assertEquals(allocated, disp.getAllocatedBW());

        monitorFor(disp).dropTunnel(cfg);

        assertEquals("an idle drop must not double-decrement the transit total",
                     0, disp.getAllocatedBW());
    }

    /** Other tunnels' allocations must survive an unrelated drop. */
    @Test
    public void testDropLeavesOtherTunnelsAllocated() throws Exception {
        TunnelDispatcher disp = new TunnelDispatcher(ctx);
        HopConfig a = hop(2001L, 3000);
        HopConfig b = hop(2002L, 4000);
        assertTrue(disp.joinParticipant(a));
        assertTrue(disp.joinParticipant(b));
        assertEquals(a.getAllocatedBW() + b.getAllocatedBW(), disp.getAllocatedBW());

        monitorFor(disp).dropTunnel(a);

        assertEquals(b.getAllocatedBW(), disp.getAllocatedBW());
    }

    /** Repeated drops of distinct tunnels must land exactly on zero, never below. */
    @Test
    public void testRepeatedDropsLandOnZero() throws Exception {
        TunnelDispatcher disp = new TunnelDispatcher(ctx);
        IdleTunnelMonitor mon = monitorFor(disp);
        List<HopConfig> hops = new ArrayList<>();
        int total = 0;
        for (long id = 3000L; id < 3020L; id++) {
            HopConfig cfg = hop(id, 100);
            assertTrue(disp.joinParticipant(cfg));
            hops.add(cfg);
            total += cfg.getAllocatedBW();
        }
        assertEquals(total, disp.getAllocatedBW());
        assertTrue(total > 0);

        for (HopConfig cfg : hops)
            mon.dropTunnel(cfg);

        assertEquals("20 drops must not drift the total", 0, disp.getAllocatedBW());
    }

    /** Dropping the same tunnel twice must not decrement twice. */
    @Test
    public void testDoubleDropDoesNotDecrementTwice() throws Exception {
        TunnelDispatcher disp = new TunnelDispatcher(ctx);
        HopConfig cfg = hop(4004L, 2500);
        assertTrue(disp.joinParticipant(cfg));
        int allocated = cfg.getAllocatedBW();
        assertTrue(allocated > 0);

        IdleTunnelMonitor mon = monitorFor(disp);
        mon.dropTunnel(cfg);
        mon.dropTunnel(cfg);

        assertEquals("a repeat drop must be a no-op", 0, disp.getAllocatedBW());
    }

    /**
     * Removing a config that was never joined must not touch the total. TunnelPool
     * removes zero-hop bootstrap tunnels that never went through a join, so an
     * unconditional decrement there would drift the transit total negative.
     */
    @Test
    public void testRemoveOfNeverJoinedConfigIsNoOp() {
        TunnelDispatcher disp = new TunnelDispatcher(ctx);
        HopConfig joined = hop(5005L, 3000);
        assertTrue(disp.joinParticipant(joined));
        int allocated = disp.getAllocatedBW();
        assertTrue(allocated > 0);

        disp.remove(hop(9999L, 4000));

        assertEquals("a never-joined config must not change the total",
                     allocated, disp.getAllocatedBW());
    }
}
