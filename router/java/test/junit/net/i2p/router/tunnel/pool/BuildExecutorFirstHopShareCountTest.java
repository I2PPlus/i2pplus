package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

/**
 * Contract tests for {@link BuildExecutor#countBuildsToFirstHop(Iterable, Hash, PooledTunnelCreatorConfig)},
 * the number the pacing gate reports when it holds a build back.
 *
 * <p>The pacing guard and the repeated-first-hop-failure guard share one stat
 * counter and used to share one log line, and the failure guard logged a message
 * that contradicted the one the caller printed. So the single number that would
 * say whether the pacing guard is too strict or the pool simply has too few
 * usable first hops - 118 of 152 inbound exploratory builds gated, 4 of them
 * succeeding - could not be attributed to either cause. Counting the builds that
 * already ride the hop is what makes the two readable apart: one hop carrying
 * many builds is a narrow candidate set, many hops carrying one each is the guard
 * doing its job on a healthy set.
 *
 * @since 0.9.72+
 */
public class BuildExecutorFirstHopShareCountTest {

    /** Shared router context for the configs this test builds. */
    private static RouterContext _ctx;

    /**
     * Builds the router context every test in this class reads.
     */
    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    /**
     * JUnit creates one instance per test method; the counter under test is
     * static, so the instance carries no state.
     */
    public BuildExecutorFirstHopShareCountTest() {
    }

    /**
     * A client pool the configs can belong to.
     *
     * @param inbound true for an inbound (server) pool, whose build request goes
     *                to peer 0, false for outbound, which goes to peer 1
     * @return a pool over the shared context with mocked manager and selector
     */
    private static TunnelPool pool(boolean inbound) {
        assumeTrue("No RouterContext available", _ctx != null);
        return new TunnelPool(_ctx, mock(TunnelPoolManager.class),
                              new TunnelPoolSettings(inbound), mock(TunnelPeerSelector.class));
    }

    /**
     * A two-hop client config whose build request will be dispatched to the given peer.
     *
     * @param inbound true for an inbound tunnel, whose dispatch peer is hop 0
     * @param pool the owning pool
     * @param dispatchHop the peer the build request goes to; placed at hop 0 for
     *                    an inbound tunnel and hop 1 for an outbound one
     * @return a two-hop config, never null
     */
    private static PooledTunnelCreatorConfig config(boolean inbound, TunnelPool pool,
                                                    Hash dispatchHop) {
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, 2, inbound, null, pool);
        cfg.setPeer(inbound ? 0 : 1, dispatchHop);
        return cfg;
    }

    /**
     * A peer hash built from an ordinal, so each hop is distinct.
     *
     * @param ordinal written into the hash's first byte
     * @return a hash whose first byte is the ordinal and the rest zero
     */
    private static Hash hop(int ordinal) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) ordinal;
        return Hash.create(data);
    }

    /**
     * One build already riding the hop is what holds a second one back, and the
     * count has to report it.
     */
    @Test
    public void countsOneBuildSharingTheHop() {
        TunnelPool pool = pool(true);
        Hash firstHop = hop(1);
        List<PooledTunnelCreatorConfig> inFlight =
            Arrays.asList(config(true, pool, firstHop));
        assertEquals(1, BuildExecutor.countBuildsToFirstHop(inFlight, firstHop, null));
    }

    /**
     * Several builds on one hop count several times - that is the shape that
     * says the selection keeps offering the same peer.
     */
    @Test
    public void countsEveryBuildSharingTheHop() {
        TunnelPool pool = pool(true);
        Hash firstHop = hop(1);
        List<PooledTunnelCreatorConfig> inFlight = Arrays.asList(config(true, pool, firstHop),
                                                                config(true, pool, firstHop),
                                                                config(true, pool, firstHop));
        assertEquals(3, BuildExecutor.countBuildsToFirstHop(inFlight, firstHop, null));
    }

    /**
     * Builds to other peers are not this hop's traffic, and a hop nobody is
     * building to is free.
     */
    @Test
    public void countsNothingWhenTheHopIsFree() {
        TunnelPool pool = pool(true);
        List<PooledTunnelCreatorConfig> inFlight = Arrays.asList(config(true, pool, hop(2)),
                                                                config(true, pool, hop(3)));
        assertEquals(0, BuildExecutor.countBuildsToFirstHop(inFlight, hop(1), null));
    }

    /**
     * The build being decided on is already in the in-flight list by the time the
     * guard runs, and it must not count itself - a build that only collides with
     * itself would be held back forever.
     */
    @Test
    public void excludesTheBuildBeingDecidedOn() {
        TunnelPool pool = pool(true);
        Hash firstHop = hop(1);
        PooledTunnelCreatorConfig self = config(true, pool, firstHop);
        List<PooledTunnelCreatorConfig> inFlight = new ArrayList<>();
        inFlight.add(self);
        assertEquals(0, BuildExecutor.countBuildsToFirstHop(inFlight, firstHop, self));
        inFlight.add(config(true, pool, firstHop));
        assertEquals(1, BuildExecutor.countBuildsToFirstHop(inFlight, firstHop, self));
    }

    /**
     * For an outbound tunnel the dispatch peer is hop 1, not hop 0, so a config
     * that shares only hop 0 does not collide.  Matching the wrong hop would
     * report a collision on every pair of tunnels that merely start at the same peer.
     */
    @Test
    public void countsTheDispatchPeerNotTheFarHopForOutboundTunnels() {
        TunnelPool pool = pool(false);
        Hash farHop = hop(1);
        Hash dispatchHop = hop(9);
        PooledTunnelCreatorConfig other = new PooledTunnelCreatorConfig(_ctx, 2, false, null, pool);
        other.setPeer(0, farHop);
        other.setPeer(1, dispatchHop);
        List<PooledTunnelCreatorConfig> inFlight = Arrays.asList(other);
        assertEquals(1, BuildExecutor.countBuildsToFirstHop(inFlight, dispatchHop, null));
        assertEquals(0, BuildExecutor.countBuildsToFirstHop(inFlight, farHop, null));
    }

    /**
     * A config with no dispatch peer at all - a zero-hop outbound tunnel - is
     * never counted against a hop, rather than matching a null lookup.
     */
    @Test
    public void aConfigWithNoDispatchPeerIsNotCounted() {
        TunnelPool pool = pool(false);
        PooledTunnelCreatorConfig zeroHop = new PooledTunnelCreatorConfig(_ctx, 1, false, null, pool);
        assertNull(BuildRequestor.getBuildRequestPeer(zeroHop));
        List<PooledTunnelCreatorConfig> inFlight = Arrays.asList(zeroHop);
        assertEquals(0, BuildExecutor.countBuildsToFirstHop(inFlight, hop(1), null));
    }

    /**
     * Nothing in flight, or no hop to match, answers zero rather than throwing -
     * the caller logs the count on a debug path and a throw there would lose the
     * gate event entirely.
     */
    @Test
    public void missingInputsCountZero() {
        assertEquals(0, BuildExecutor.countBuildsToFirstHop(null, hop(1), null));
        TunnelPool pool = pool(true);
        List<PooledTunnelCreatorConfig> inFlight = Arrays.asList(config(true, pool, hop(1)));
        assertEquals(0, BuildExecutor.countBuildsToFirstHop(inFlight, null, null));
    }

    /**
     * The count does not consume or reorder what it is given - the caller hands it
     * the executor's live building-map view and then goes on to use it.
     */
    @Test
    public void countingLeavesTheInFlightListAlone() {
        TunnelPool pool = pool(true);
        PooledTunnelCreatorConfig a = config(true, pool, hop(1));
        PooledTunnelCreatorConfig b = config(true, pool, hop(2));
        List<PooledTunnelCreatorConfig> inFlight = new ArrayList<>(Arrays.asList(a, b));
        BuildExecutor.countBuildsToFirstHop(inFlight, hop(1), null);
        assertEquals(2, inFlight.size());
        assertSame(a, inFlight.get(0));
        assertSame(b, inFlight.get(1));
    }
}
