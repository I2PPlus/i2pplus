package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

/**
 * A timed-out build may only charge the contacted hop a ghost mark when that
 * hop's state actually indicts it.
 *
 * <p>The contacted hop being <em>established but silent</em> is explicitly not
 * such a state: the peer accepted our connection, so the missing reply could
 * equally be the request dropped inside the peer, the reply lost on the return
 * path, or our own path failing. Charging that case excluded peers at scale —
 * on the live router it governed roughly 27.7 ghost marks/min against
 * 4.3/min for genuinely unreachable peers — and the exclusion set grew faster
 * than it was retired (82/min in, 15/min out), shrinking the very pool
 * selection draws from.
 *
 * <p>Every other classification must still charge, so a genuinely dead peer
 * keeps being penalised.
 *
 * @since 0.9.71+
 */
public class BuildExecutorGhostChargeTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    private static BuildExecutor newExecutor() {
        return new BuildExecutor(_ctx, mock(TunnelPoolManager.class), mock(GhostPeerManager.class));
    }

    private static PooledTunnelCreatorConfig buildConfig(boolean inbound, Hash... peers) {
        TunnelPoolSettings settings = new TunnelPoolSettings(inbound);
        TunnelPool pool = new TunnelPool(_ctx, mock(TunnelPoolManager.class), settings,
                                         mock(TunnelPeerSelector.class));
        PooledTunnelCreatorConfig cfg =
                new PooledTunnelCreatorConfig(_ctx, peers.length, inbound, null, pool);
        for (int i = 0; i < peers.length; i++) {
            cfg.setPeer(i, peers[i]);
        }
        return cfg;
    }

    /**
     * Drives the contacted hop into a known classification, then asserts the
     * ghost-charge decision.  The transport facade is stubbed on the shared
     * context, so each test restores it to avoid leaking into the next.
     */
    private static boolean chargeWhen(boolean inbound, boolean unreachable,
                                      boolean established, boolean connecting) {
        CommSystemFacade prior = _ctx.commSystem();
        try {
            CommSystemFacade stub = mock(CommSystemFacade.class);
            Hash gateway = hash(1), next = hash(2), far = hash(3);
            for (Hash h : new Hash[] {gateway, next, far}) {
                when(stub.wasUnreachable(h)).thenReturn(unreachable);
                when(stub.isEstablished(h)).thenReturn(established);
                when(stub.isConnecting(h)).thenReturn(connecting);
            }
            setCommSystem(stub);
            PooledTunnelCreatorConfig cfg = buildConfig(inbound, gateway, next, far);
            Hash contacted = BuildRequestor.getBuildRequestPeer(cfg);
            return newExecutor().shouldChargeGhostMark(cfg, contacted);
        } finally {
            setCommSystem(prior);
        }
    }

    private static void setCommSystem(CommSystemFacade cs) {
        try {
            java.lang.reflect.Field f = RouterContext.class.getDeclaredField("_commSystem");
            f.setAccessible(true);
            f.set(_ctx, cs);
        } catch (Exception e) {
            throw new IllegalStateException("cannot stub comm system", e);
        }
    }

    @Test
    public void testEstablishedSilentIsNotCharged() {
        // The regression: established-but-silent used to charge a ghost mark.
        assertFalse("established-but-silent hop must not be ghost-charged",
                    chargeWhen(true, false, true, false));
    }

    @Test
    public void testUnreachableIsStillCharged() {
        assertTrue("an unreachable contacted hop is a real failure",
                   chargeWhen(true, true, false, false));
    }

    @Test
    public void testHandshakeUnfinishedIsStillCharged() {
        assertTrue("a peer still handshaking had no session to answer over",
                   chargeWhen(true, false, false, true));
    }

    @Test
    public void testPlainReachableIsStillCharged() {
        // Nothing distinguishes this peer, so it remains the best explanation
        // for the expiry and must keep being charged.
        assertTrue("a reachable contacted hop is still the best explanation",
                   chargeWhen(true, false, false, false));
    }

    @Test
    public void testUnreachableOutranksEstablished() {
        // classifyExpiredHop reports unreachable first, so a peer on both counts
        // is charged even though it also holds a session.
        assertTrue("unreachable outranks established and must charge",
                   chargeWhen(true, true, true, false));
    }

    @Test
    public void testOutboundEstablishedSilentIsNotCharged() {
        // outbound blames peer 1 rather than peer 0; the rule must hold there too
        assertFalse("outbound established-but-silent hop must not be ghost-charged",
                    chargeWhen(false, false, true, false));
    }

    @Test
    public void testOutboundUnreachableIsStillCharged() {
        assertTrue("outbound unreachable contacted hop is a real failure",
                   chargeWhen(false, true, false, false));
    }

    @Test
    public void testNullInputsAreNotCharged() {
        BuildExecutor exec = newExecutor();
        assertFalse(exec.shouldChargeGhostMark(null, hash(1)));
        PooledTunnelCreatorConfig cfg = buildConfig(true, hash(1), hash(2));
        assertFalse(exec.shouldChargeGhostMark(cfg, null));
    }

    @Test
    public void testAbsentPeerFallsBackToCharging() {
        // If the contacted hop is not in the config we cannot classify it, and
        // charging preserves the previous behaviour rather than silently
        // dropping the penalty.
        PooledTunnelCreatorConfig cfg = buildConfig(true, hash(1), hash(2));
        assertTrue(newExecutor().shouldChargeGhostMark(cfg, hash(99)));
    }
}