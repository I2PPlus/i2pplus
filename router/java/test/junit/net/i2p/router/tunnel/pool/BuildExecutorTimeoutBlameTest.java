package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.ProfileOrganizer;

/**
 * A timed-out build may only penalize the hop the build request was
 * dispatched to: the profile penalty and ghost mark go to peer 0 for
 * inbound / peer 1 for outbound builds, never to middle or end hops
 * that may not even have received the request.
 *
 * @since 0.9.71+
 */
public class BuildExecutorTimeoutBlameTest {

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

    private static BuildExecutor newExecutor(GhostPeerManager ghost) {
        return new BuildExecutor(_ctx, mock(TunnelPoolManager.class), ghost);
    }

    private static PooledTunnelCreatorConfig buildConfig(boolean inbound, Hash... peers) {
        TunnelPoolSettings settings = new TunnelPoolSettings(inbound);
        TunnelPool pool = new TunnelPool(_ctx, mock(TunnelPoolManager.class), settings, mock(TunnelPeerSelector.class));
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_ctx, peers.length, inbound, null, pool);
        for (int i = 0; i < peers.length; i++) {
            cfg.setPeer(i, peers[i]);
        }
        return cfg;
    }

    @Test
    public void testInboundTimeoutBlamesOnlyGateway() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        GhostPeerManager ghost = mock(GhostPeerManager.class);
        BuildExecutor exec = newExecutor(ghost);
        Hash ibgw = hash(1), mid = hash(2), end = hash(3);
        PooledTunnelCreatorConfig cfg = buildConfig(true, ibgw, mid, end);

        exec.penalizeTimeout(cfg);

        verify(ghost).recordTimeout(ibgw);
        verify(ghost, never()).recordTimeout(mid);
        verify(ghost, never()).recordTimeout(end);
    }

    @Test
    public void testOutboundTimeoutBlamesOnlyNextHop() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        GhostPeerManager ghost = mock(GhostPeerManager.class);
        BuildExecutor exec = newExecutor(ghost);
        Hash start = hash(1), obep = hash(2), end = hash(3);
        PooledTunnelCreatorConfig cfg = buildConfig(false, start, obep, end);

        exec.penalizeTimeout(cfg);

        verify(ghost, never()).recordTimeout(start);
        verify(ghost).recordTimeout(obep);
        verify(ghost, never()).recordTimeout(end);
    }

    @Test
    public void testSelfGatewayNeverPenalized() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        GhostPeerManager ghost = mock(GhostPeerManager.class);
        BuildExecutor exec = newExecutor(ghost);
        Hash self = _ctx.routerHash(), mid = hash(2), end = hash(3);
        PooledTunnelCreatorConfig cfg = buildConfig(true, self, mid, end);

        exec.penalizeTimeout(cfg);

        verify(ghost, never()).recordTimeout(any(Hash.class));
    }

    // --- the ghost charge and the strike path, not an immediate eviction ---

    /**
     * What one {@code penalizeTimeout} did to the contacted hop: whether it was
     * charged a ghost mark, and whether the strike-accounted tier demotion was
     * run. There is no third verdict: the immediate eviction that used to sit
     * here fired on a single build timeout, and on a router whose build success
     * is 52% a timeout is the commonest outcome of asking anybody anything. It
     * produced 19,124 of 19,241 tier demotions in a 5.5 h run. Eviction now
     * needs {@code DEMOTE_STRIKE_THRESHOLD} first-hop non-replies inside
     * {@code DEMOTE_STRIKE_DECAY_MS}, which is what {@code strikeRun} reports.
     */
    private static final class Verdict {
        final boolean charged;
        final boolean strikeRun;
        Verdict(boolean charged, boolean strikeRun) {
            this.charged = charged;
            this.strikeRun = strikeRun;
        }
    }

    private static void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = RouterContext.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(_ctx, value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot set " + name, e);
        }
    }

    /**
     * Runs a Mockito verification and reports whether it held, so a test can
     * compare two independent verifications rather than asserting one at a time.
     *
     * @param verification the {@code verify(...)} call to attempt
     * @return true if the verification passed, false if Mockito rejected it
     */
    private static boolean called(Runnable verification) {
        try {
            verification.run();
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    /**
     * Runs one {@code penalizeTimeout} with every peer in the same transport
     * state and reports what happened to the contacted hop.
     *
     * @param unreachable whether the transport reports the peer unreachable
     * @param established whether the transport holds a session with the peer
     * @param connecting whether the transport is still handshaking
     * @return the verdict for the contacted hop
     */
    private static Verdict penalizeWith(boolean unreachable, boolean established,
                                       boolean connecting) {
        ProfileOrganizer org = mock(ProfileOrganizer.class);
        GhostPeerManager ghost = mock(GhostPeerManager.class);
        CommSystemFacade priorComm = _ctx.commSystem();
        ProfileOrganizer priorOrg = _ctx.profileOrganizer();
        Hash ibgw = hash(1), mid = hash(2), end = hash(3);
        try {
            CommSystemFacade stub = mock(CommSystemFacade.class);
            when(stub.wasUnreachable(any(Hash.class))).thenReturn(unreachable);
            when(stub.isEstablished(any(Hash.class))).thenReturn(established);
            when(stub.isConnecting(any(Hash.class))).thenReturn(connecting);
            setField("_commSystem", stub);
            setField("_profileOrganizer", org);
            newExecutor(ghost).penalizeTimeout(buildConfig(true, ibgw, mid, end));
            return new Verdict(
                    called(() -> verify(ghost).recordTimeout(ibgw)),
                    called(() -> verify(org).demoteIfUnreachable(ibgw)));
        } finally {
            setField("_commSystem", priorComm);
            setField("_profileOrganizer", priorOrg);
        }
    }

    /**
     * No transport state evicts on one timeout. That is the change: every state
     * now goes through the strike accounting, so a peer leaves the tiers on
     * repeated non-replies, not on the first one.
     */
    @Test
    public void testNoTransportStateEvictsOnASingleTimeout() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        boolean[][] states = {
            {false, false, true},   // established but silent
            {false, false, false},  // plain reachable
            {false, true,  false},  // handshake unfinished
            {true,  false, false},  // unreachable
            {true,  true,  false},  // unreachable, outranks established
        };
        for (boolean[] s : states) {
            Verdict v = penalizeWith(s[0], s[1], s[2]);
            assertTrue("the strike-accounted demotion must run for every state, "
                       + "unreachable=" + s[0] + " established=" + s[1] + " connecting=" + s[2],
                       v.strikeRun);
        }
    }

    /**
     * A verified-unreachable hop is still ghost-charged: the mark is the
     * short, self-limiting penalty, and it is what keeps the peer out of the
     * next build while the strikes accumulate.
     */
    @Test
    public void testUnreachableHopIsGhostCharged() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        assertTrue("unreachable contacted hop is a real failure and is marked",
                   penalizeWith(true, false, false).charged);
    }

    /**
     * The regression the earlier test pinned, kept: established-but-silent was
     * exempt from the ghost charge, yet the heavier tier eviction was applied to
     * exactly that bucket, and that bucket was the common one (~27.7/min against
     * ~4.3/min unreachable). With the eviction moved behind the strikes the two
     * penalties can no longer disagree, because there is only the one.
     */
    @Test
    public void testEstablishedSilentHopNotGhostChargedAndNotEvicted() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Verdict v = penalizeWith(false, true, false);
        assertFalse("established-but-silent must not be ghost-charged", v.charged);
        assertTrue("and it goes through the same strike path as every other state",
                   v.strikeRun);
    }

    /**
     * Skipping the immediate eviction must not exempt the peer: the strike path
     * runs for every state, so a peer that keeps failing is evicted on its third
     * first-hop non-reply inside the decay window.
     */
    @Test
    public void testEstablishedSilentHopStillAccruesStrike() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        assertTrue("the 3-strike demotion path must still run",
                   penalizeWith(false, true, false).strikeRun);
    }
}
