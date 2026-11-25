package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

/**
 * Pins the executor's decision when a build's first hop is gated: keep the
 * build and ask the pool for another hop, or give up on it. The guard only
 * says one thing about one peer - it already carries a build, or it has been
 * failing as a first hop - and says nothing about the demand behind the build,
 * so discarding the build throws away a refill the pool still needs. Measured
 * live on 2026-11-25 the guard turned away 86 builds a minute while 61 were
 * attempted: more than half of the router's build demand was being discarded
 * for want of a free first hop.
 *
 * @since 0.9.72+
 */
public class BuildExecutorGateActionTest {

    /** The re-select ceiling the executor runs with. */
    private static final int LIMIT = BuildExecutor.FIRST_HOP_RESELECT_LIMIT;

    /**
     * Holds no state between cases: each one feeds the decision its own gate
     * reason, pool, attempt count and ceiling.
     */
    public BuildExecutorGateActionTest() {
    }

    /**
     * A clear hop means dispatch - the guard must not disturb a build that
     * nothing is wrong with, whatever pool or attempt count it carries.
     */
    @Test
    public void testClearHopDispatches() {
        TunnelPool pool = mock(TunnelPool.class);
        for (int attempt = 0; attempt <= LIMIT + 1; attempt++) {
            assertEquals(BuildExecutor.GateAction.DISPATCH,
                         BuildExecutor.gateAction(BuildExecutor.FirstHopGate.NONE, pool, attempt, LIMIT));
        }
    }

    /**
     * Both gate reasons re-select. A busy hop and a hop that has been failing
     * are equally uninformative about the rest of the peer set, so neither
     * ends the build while another hop can still be asked for.
     */
    @Test
    public void testGatedHopReselects() {
        TunnelPool pool = mock(TunnelPool.class);
        for (int attempt = 0; attempt < LIMIT; attempt++) {
            assertEquals(BuildExecutor.FirstHopGate.IN_FLIGHT + " * attempt " + attempt, BuildExecutor.GateAction.RESELECT,
                         BuildExecutor.gateAction(BuildExecutor.FirstHopGate.IN_FLIGHT, pool, attempt, LIMIT));
            assertEquals(BuildExecutor.FirstHopGate.RECENT_FAILURE + " * attempt " + attempt, BuildExecutor.GateAction.RESELECT,
                         BuildExecutor.gateAction(BuildExecutor.FirstHopGate.RECENT_FAILURE, pool, attempt, LIMIT));
        }
    }

    /**
     * The walk is bounded: once the re-selects are spent the build is dropped,
     * so a pool whose whole candidate set is busy cannot loop here.
     */
    @Test
    public void testReselectIsBounded() {
        TunnelPool pool = mock(TunnelPool.class);
        assertEquals(BuildExecutor.GateAction.RESELECT, BuildExecutor.gateAction(BuildExecutor.FirstHopGate.IN_FLIGHT, pool,
                                                                   LIMIT - 1, LIMIT));
        assertEquals(BuildExecutor.GateAction.DROP, BuildExecutor.gateAction(BuildExecutor.FirstHopGate.IN_FLIGHT, pool,
                                                               LIMIT, LIMIT));
        assertEquals(BuildExecutor.GateAction.DROP, BuildExecutor.gateAction(BuildExecutor.FirstHopGate.RECENT_FAILURE, pool,
                                                               LIMIT + 5, LIMIT));
    }

    /**
     * Without a pool there is nobody to ask for another hop, so even the first
     * pass drops - the build must not be dispatched onto the gated hop just
     * because the re-select path is unavailable.
     */
    @Test
    public void testNoPoolDrops() {
        assertEquals(BuildExecutor.GateAction.DROP, BuildExecutor.gateAction(BuildExecutor.FirstHopGate.IN_FLIGHT, null, 0, LIMIT));
        assertEquals(BuildExecutor.GateAction.DISPATCH, BuildExecutor.gateAction(BuildExecutor.FirstHopGate.NONE, null, 0, LIMIT));
    }

    /**
     * A ceiling of zero means no re-selection at all: the first gated pass
     * drops, which is the pre-2026-11 behaviour, kept reachable so the walk is
     * testable at both settings.
     */
    @Test
    public void testZeroLimitDropsAtOnce() {
        TunnelPool pool = mock(TunnelPool.class);
        assertEquals(BuildExecutor.GateAction.DROP, BuildExecutor.gateAction(BuildExecutor.FirstHopGate.IN_FLIGHT, pool, 0, 0));
    }

    /**
     * The ceiling the router runs with has to leave room to try a second and
     * third peer - one retry would only be worth having if it were measured to
     * be enough, and it is not.
     */
    @Test
    public void testRouterLimitAllowsTwoRetries() {
        assertEquals(2, LIMIT);
    }
}
