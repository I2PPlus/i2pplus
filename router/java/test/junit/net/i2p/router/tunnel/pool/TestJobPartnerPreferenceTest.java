package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.data.Hash;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;

import org.junit.Test;

/**
 * Contract tests for {@link TunnelPool#partnerPreferenceTier(TunnelInfo)}.
 *
 * <p>A test round is a composite of the tunnel under test and the partner its
 * reply arrives through, and one pass/fail bit cannot separate them.  Choosing
 * partners uniformly at random therefore injects noise that looks exactly like
 * a failing tunnel: a partner whose gateway has gone quiet since the tunnel was
 * built still passes every status gate, because those gates inspect the tunnel
 * and never its peers.
 *
 * <p>These pin the ranking only.  The full selection needs a live pool, and
 * the behavioural question — whether preferring proven partners improves the
 * round trip — is answered on a running router, not here.
 *
 * @since 0.9.71+
 */
public class TestJobPartnerPreferenceTest {

    private static TunnelInfo tunnel(TunnelTestStatus status, long lastRealTraffic, long verified) {
        TunnelInfo t = mock(TunnelInfo.class);
        when(t.getTestStatus()).thenReturn(status);
        when(t.getLastRealTraffic()).thenReturn(lastRealTraffic);
        when(t.getVerifiedBytesTransferred()).thenReturn(verified);
        return t;
    }

    /** GOOD plus real traffic is the only tier that proves the tunnel works. */
    @Test
    public void goodAndLiveIsTheBestTier() {
        assertEquals(0, TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.GOOD, 1_000L, 4096L)));
    }

    @Test
    public void goodWithoutTrafficIsNext() {
        assertEquals(1, TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.GOOD, 0L, 0L)));
    }

    @Test
    public void liveButUntestedIsNextAgain() {
        assertEquals(2, TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.UNTESTED, 1_000L, 0L)));
    }

    /**
     * The floor must be the previous behaviour: anything passing the scan
     * gates is still selectable.  If a candidate can score no worse than the
     * old uniform-random choice, this preference cannot reject a working
     * partner and therefore cannot make the round trip worse.
     */
    @Test
    public void anythingElseIsTheFloorNotDisqualified() {
        assertEquals(3, TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.UNTESTED, 0L, 0L)));
        assertEquals(3, TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.TESTING, 0L, 0L)));
    }

    /**
     * Receiving traffic is the strongest available proof, so a tunnel that has
     * received some outranks one that has only sent.  Both count as live: for
     * an inbound partner, receiving data means its gateway is delivering, which
     * is precisely the property the status gates cannot see.
     */
    @Test
    public void eitherDirectionOfTrafficCountsAsLive() {
        assertEquals("receiving only", 2, TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.UNTESTED, 5_000L, 0L)));
        assertEquals("sending only", 2, TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.UNTESTED, 0L, 5_000L)));
    }

    /** A FAILING tunnel should not outrank an untested one purely on traffic. */
    @Test
    public void failingIsNotPromotedToTheTopTier() {
        assertTrue(TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.FAILING, 5_000L, 5_000L)) > 0);
        assertTrue(TunnelPool.partnerPreferenceTier(
                tunnel(TunnelTestStatus.FAILED, 5_000L, 5_000L)) > 0);
    }

    @Test
    public void tiersAreStrictlyOrdered() {
        int best = TunnelPool.partnerPreferenceTier(tunnel(TunnelTestStatus.GOOD, 1L, 1L));
        int worst = TunnelPool.partnerPreferenceTier(tunnel(TunnelTestStatus.TESTING, 0L, 0L));
        assertTrue("the best candidate must score strictly better than the floor",
                   best < worst);
    }
}
