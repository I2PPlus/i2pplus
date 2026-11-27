package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

/**
 * The expiry sweep's charge against the first-hop failure history - the record
 * the recently-failed-first-hop gate reads before it lets a build dispatch to a
 * peer.
 * <p>
 * A build that was answered by nobody never reaches
 * {@link BuildExecutor#buildComplete}, which is where that charge lives: the
 * sweep in run2() is the only code that ever sees a TIMEOUT. So the charge was
 * never made for the dominant failure, and the gate could not fire. Measured
 * live on 2026-11-27 over 15 minutes: 1,263 expiries attributed to a peer whose
 * transport was up and that answered nothing, and not one gate logged a failing
 * hop. The gate's own bar - three failures inside two minutes - is what keeps
 * it conservative once it is fed; these tests pin both the feeding and the bar.
 *
 * @since 0.9.72+
 */
public class BuildExecutorFirstHopTimeoutTest {

    /** Shared context for the tests that need a live pool over a real context. */
    private static RouterContext _ctx;

    /**
     * Holds no state of its own; the executor and pool are built inside each
     * case so no failure history carries between them.
     */
    public BuildExecutorFirstHopTimeoutTest() {
    }

    /**
     * Build the shared context once for the class. It is null when the test
     * environment cannot create one, which every case treats as a skip.
     */
    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /**
     * A distinct peer hash, made unique among this class's hops by its first
     * byte.
     *
     * @param b the first byte of the hash
     * @return a 32-byte hash starting with {@code b}
     */
    private static Hash hop(byte b) {
        Hash rv = new Hash(new byte[32]);
        rv.getData()[0] = b;
        return rv;
    }

    /**
     * Real client pool over a real context; manager and peer selector are mocks,
     * never touched by an expiry.
     *
     * @param inbound whether the pool builds inbound tunnels
     * @return a live pool with the given direction and no tunnels
     */
    private static TunnelPool createPool(boolean inbound) {
        TunnelPoolSettings settings = new TunnelPoolSettings(inbound);
        return new TunnelPool(_ctx, mock(TunnelPoolManager.class), settings, mock(TunnelPeerSelector.class));
    }

    /**
     * A three-hop build over {@code pool}, with this router in its own hop slot
     * and distinct peers in the rest.
     *
     * @param pool the pool the build belongs to
     * @param inbound an inbound build is dispatched to hop 0, an outbound one to
     *                hop 1
     * @return the config, not yet added to the pool's in-progress list
     */
    private static PooledTunnelCreatorConfig config(TunnelPool pool, boolean inbound) {
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 3, inbound, null, pool);
        // Distinct hashes in every slot, this router's own slot included: the
        // test context's identity may be unset, and a null hop would be read as
        // a peer by the expiry's blame loop.
        cfg.setPeer(0, inbound ? hop((byte) 31) : hop((byte) 30));
        cfg.setPeer(1, inbound ? hop((byte) 32) : hop((byte) 31));
        cfg.setPeer(2, hop((byte) 33));
        return cfg;
    }

    /**
     * The executor's private in-flight-or-failing gate, over {@code cfg}.
     *
     * @param exec the executor to ask
     * @param cfg the prospective build
     * @return true if the build's first hop holds it back
     * @throws Exception on reflection failure if the gate is gone or unreachable
     */
    private static boolean gated(BuildExecutor exec, PooledTunnelCreatorConfig cfg) throws Exception {
        Method m = BuildExecutor.class.getDeclaredMethod("hasBuildInFlightToFirstHop",
                                                         PooledTunnelCreatorConfig.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(exec, cfg);
    }

    /**
     * Three unanswered builds to the same first hop gate that hop; two do not.
     * The sweep, not the reply path, is what makes the charge, so this is the
     * only path by which the dominant build failure reaches the gate.
     *
     * @throws Exception on reflection failure reading the gate
     */
    @Test
    public void testUnansweredBuildsChargeTheirFirstHop() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(false);
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));
        PooledTunnelCreatorConfig cfg = config(pool, false);
        Hash firstHop = cfg.getPeer(1);

        assertFalse("a hop we have never built through is not held", exec.hasRecentlyFailedAsFirstHop(firstHop));
        assertFalse("and a build to it is not gated", gated(exec, cfg));

        exec.handleExpiredBuild(cfg, null);
        exec.handleExpiredBuild(cfg, null);
        assertFalse("one failure short of the bar, the hop is still offered",
                    exec.hasRecentlyFailedAsFirstHop(firstHop));
        assertFalse(gated(exec, cfg));

        exec.handleExpiredBuild(cfg, null);
        assertTrue("at the bar the hop is recorded as failing", exec.hasRecentlyFailedAsFirstHop(firstHop));
        assertTrue("and the build that would have used it is held", gated(exec, cfg));
    }

    /**
     * The charge goes to the peer the build request was actually dispatched to.
     * For an inbound tunnel that is hop 0, not hop 1: charging the wrong slot
     * would penalise a peer that never received the request and leave the silent
     * one in the candidate set.
     *
     * @throws Exception on reflection failure reading the gate
     */
    @Test
    public void testInboundChargesThePeerTheRequestWentTo() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        TunnelPool pool = createPool(true);
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));
        PooledTunnelCreatorConfig cfg = config(pool, true);
        Hash contacted = cfg.getPeer(0);
        Hash secondHop = cfg.getPeer(1);

        for (int i = 0; i < BuildExecutor.getFirstHopFailureThreshold(); i++) {
            exec.handleExpiredBuild(cfg, null);
        }

        assertTrue("the hop the request went to is charged", exec.hasRecentlyFailedAsFirstHop(contacted));
        assertFalse("the hop behind it is not", exec.hasRecentlyFailedAsFirstHop(secondHop));
        assertTrue(gated(exec, cfg));
    }

    /**
     * The executor's private first-hop failure history.
     *
     * @param exec the executor whose private {@code _firstHopFailureHistory} is
     *             read
     * @return the live map, so a test can seed a peer or read what was recorded
     * @throws Exception on reflection failure if the field is gone or
     *                   unreachable
     */
    @SuppressWarnings("unchecked")
    private static java.util.concurrent.ConcurrentHashMap<Hash, long[]> failureHistory(BuildExecutor exec)
        throws Exception {
        java.lang.reflect.Field f = BuildExecutor.class.getDeclaredField("_firstHopFailureHistory");
        f.setAccessible(true);
        return (java.util.concurrent.ConcurrentHashMap<Hash, long[]>) f.get(exec);
    }

    /**
     * A hop that failed once long enough ago is offered again: the history
     * decays instead of holding a peer out of the candidate set for the
     * router's uptime, which is what keeps a transient no-reply event from
     * permanently shrinking the pool. The record is also dropped, so a peer that
     * leaves the network does not leave an entry behind.
     *
     * @throws Exception on reflection failure seeding the history
     */
    @Test
    public void testOldFailuresDoNotHoldTheHop() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        BuildExecutor exec = new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));
        Hash stale = hop((byte) 34);
        long when = _ctx.clock().now() - BuildExecutor.getFirstHopFailureCooldownMs() - 1000L;
        failureHistory(exec).put(stale, new long[] {BuildExecutor.getFirstHopFailureThreshold(), when});

        assertFalse("a record past the cooldown window is not a finding",
                    exec.hasRecentlyFailedAsFirstHop(stale));
        assertFalse("and it is dropped rather than carried", failureHistory(exec).containsKey(stale));
    }
}
