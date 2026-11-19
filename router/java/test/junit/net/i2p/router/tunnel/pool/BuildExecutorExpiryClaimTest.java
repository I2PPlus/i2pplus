package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

/**
 * An expiring build and its arriving reply must not both win.
 *
 * <p>The expiry sweep moves a build from the building map to the recently-building
 * map and the reply thread removes it from the other direction, and the sweep
 * reaches a config through a weakly consistent iterator. Neither step re-checked
 * that the entry was still where the sweep thought it was, so a reply landing in
 * that window was processed as a success and as a timeout: the contacted hop was
 * cooled down and penalized for a tunnel that actually joined, the pool counted a
 * consecutive failure it did not have, and the sweep's put resurrected in the
 * recently-building map an entry the reply thread had just cleared out of both.
 *
 * @since 0.9.72+
 */
public class BuildExecutorExpiryClaimTest {

    /** Context for the executor's clock, stats and logging. */
    private static RouterContext _ctx;

    /** Rounds of the claim-versus-reply race. */
    private static final int RACE_ROUNDS = 200;

    /**
     * A fixture group for the building-map claim; it holds no state.
     *
     * @since 0.9.72+
     */
    public BuildExecutorExpiryClaimTest() {}

    /**
     * A context to build executors and configs over.
     */
    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    /**
     * The defect: the reply thread had already taken the build out of the building
     * map, and the sweep claimed it anyway, charging a timeout for a build that
     * joined and putting it back where the reply thread had just removed it.
     *
     * @throws Exception if a private building map cannot be reached
     */
    @Test
    public void testAReplyThatArrivedFirstIsNotClaimed() throws Exception {
        BuildExecutor exec = executor();
        PooledTunnelCreatorConfig cfg = config();
        Long key = Long.valueOf(cfg.getReplyMessageId());
        currently(exec).put(key, cfg);

        assertNotNull(exec.removeFromBuilding(cfg.getReplyMessageId()));
        assertFalse("the expiry sweep claimed a build whose reply had already arrived",
                    exec.claimExpiredBuild(key, cfg));
        assertTrue("the sweep left the build in the recently-building map",
                   recently(exec).isEmpty());
    }

    /**
     * The ordinary case still works: an expired build is claimed once, out of the
     * building map and into the recently-building map, and a second claim of the
     * same entry finds nothing.
     *
     * @throws Exception if a private building map cannot be reached
     */
    @Test
    public void testAnExpiredBuildIsClaimedOnce() throws Exception {
        BuildExecutor exec = executor();
        PooledTunnelCreatorConfig cfg = config();
        Long key = Long.valueOf(cfg.getReplyMessageId());
        currently(exec).put(key, cfg);

        assertTrue(exec.claimExpiredBuild(key, cfg));
        assertTrue(currently(exec).isEmpty());
        assertSame(cfg, recently(exec).get(key));
        assertFalse("the same entry was claimed twice", exec.claimExpiredBuild(key, cfg));
    }

    /**
     * A reply id already in the recently-building map is counted, not a veto: the
     * eviction happens, and the entry already there - the one a late reply may
     * still be matched against - is not replaced.
     *
     * @throws Exception if a private building map cannot be reached
     */
    @Test
    public void testTheClaimKeepsAnEntryAlreadyInRecently() throws Exception {
        BuildExecutor exec = executor();
        PooledTunnelCreatorConfig first = config();
        PooledTunnelCreatorConfig expired = config();
        Long key = Long.valueOf(first.getReplyMessageId());
        currently(exec).put(key, expired);
        recently(exec).put(key, first);

        assertTrue(exec.claimExpiredBuild(key, expired));
        assertSame("the claim replaced the entry a late reply could still match",
                   first, recently(exec).get(key));
    }

    /**
     * A different config under the same reply id is not the entry the sweep found:
     * claiming compares the value, not just the key.
     *
     * @throws Exception if a private building map cannot be reached
     */
    @Test
    public void testTheClaimTakesOnlyThatExactEntry() throws Exception {
        BuildExecutor exec = executor();
        PooledTunnelCreatorConfig cfg = config();
        PooledTunnelCreatorConfig other = config();
        Long key = Long.valueOf(cfg.getReplyMessageId());
        currently(exec).put(key, other);

        assertFalse(exec.claimExpiredBuild(key, cfg));
        assertSame(other, currently(exec).get(key));
        assertTrue(recently(exec).isEmpty());
    }

    /**
     * The race itself, run until it is hit: whoever wins, the build must end up out
     * of both maps. A sweep that claims an entry the reply thread already removed
     * puts it back, and that is what this catches.
     *
     * @throws Exception if a racer fails to join or a private map is unreachable
     */
    @Test
    public void testTheRaceNeverLeavesABuildBehind() throws Exception {
        BuildExecutor exec = executor();
        for (int i = 0; i < RACE_ROUNDS; i++) {
            PooledTunnelCreatorConfig cfg = config();
            Long key = Long.valueOf(cfg.getReplyMessageId());
            currently(exec).put(key, cfg);
            CountDownLatch go = new CountDownLatch(1);
            AtomicBoolean claimed = new AtomicBoolean();
            Thread sweep = new Thread(() -> {
                await(go);
                claimed.set(exec.claimExpiredBuild(key, cfg));
            });
            Thread reply = new Thread(() -> {
                await(go);
                exec.removeFromBuilding(cfg.getReplyMessageId());
            });
            sweep.start();
            reply.start();
            go.countDown();
            sweep.join();
            reply.join();
            if (!recently(exec).isEmpty()) {
                fail("round " + i + " left a build in the recently-building map, claimed: " +
                     claimed.get());
            }
            currently(exec).clear();
            recently(exec).clear();
        }
    }

    /**
     * Waits for the start signal, reporting an interrupt as a failure rather than
     * swallowing it.
     *
     * @param go the latch every racer waits on
     */
    private static void await(CountDownLatch go) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return an executor over mocked pool manager and ghost peer manager, never
     *         started
     */
    private static BuildExecutor executor() {
        return new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));
    }

    /**
     * @return a three-hop inbound config with its own reply id
     */
    private static PooledTunnelCreatorConfig config() {
        TunnelPoolSettings settings = new TunnelPoolSettings(true);
        TunnelPool pool = new TunnelPool(_ctx, mock(TunnelPoolManager.class), settings,
                                         mock(TunnelPeerSelector.class));
        return new PooledTunnelCreatorConfig(_ctx, 3, true, null, pool);
    }

    /**
     * Read one of the executor's private building maps.
     *
     * @param name the field name on BuildExecutor
     * @return the live map, mutable
     * @throws Exception if the field is missing or not accessible
     */
    private static ConcurrentHashMap<Long, PooledTunnelCreatorConfig> map(BuildExecutor exec,
                                                                          String name)
            throws Exception {
        Field f = BuildExecutor.class.getDeclaredField(name);
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, PooledTunnelCreatorConfig> map =
            (ConcurrentHashMap<Long, PooledTunnelCreatorConfig>) f.get(exec);
        return map;
    }

    /**
     * @param exec the executor whose map is read
     * @return the executor's building map, mutable
     * @throws Exception if the {@code _currentlyBuildingMap} field is unreachable
     */
    private static ConcurrentHashMap<Long, PooledTunnelCreatorConfig> currently(BuildExecutor exec)
            throws Exception {
        return map(exec, "_currentlyBuildingMap");
    }

    /**
     * @param exec the executor whose map is read
     * @return the executor's recently-building map, mutable
     * @throws Exception if the {@code _recentlyBuildingMap} field is unreachable
     */
    private static ConcurrentHashMap<Long, PooledTunnelCreatorConfig> recently(BuildExecutor exec)
            throws Exception {
        return map(exec, "_recentlyBuildingMap");
    }
}
