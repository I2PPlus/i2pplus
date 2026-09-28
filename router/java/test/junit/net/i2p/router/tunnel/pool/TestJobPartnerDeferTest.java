package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;

import org.junit.Test;

/**
 * Contract tests for {@link TestJob#shouldDeferForPartner(TunnelInfo, boolean)}.
 *
 * A tunnel test is a round trip routed through a partner tunnel: a test of an
 * inbound tunnel goes out over an outbound partner and is answered back down
 * the tunnel under test, and vice versa.  The round therefore fails for two
 * independent reasons, and the decision tested here decides whether the
 * failure is charged to the tunnel under test or deferred as evidence about
 * the partner pool instead.
 *
 * Deferring is not immunity: the caller routes the defer through
 * TestJob.deferForMissingPartner(), which kicks the paired pool to rebuild
 * and eventually fails the tunnel once its bounded budget is spent.  What
 * matters here is attribution — a degraded paired pool must not cascade its
 * failures into this one.
 *
 * @since 0.9.71+
 */
public class TestJobPartnerDeferTest {

    /** A partner stub of the given length in the given test state. */
    private static TunnelInfo partner(int length, boolean hardFailed,
                                      TunnelTestStatus status) {
        TunnelInfo t = mock(TunnelInfo.class);
        when(t.getLength()).thenReturn(length);
        when(t.getTunnelFailed()).thenReturn(hardFailed);
        when(t.getTestStatus()).thenReturn(status);
        return t;
    }

    /** A usable multi-hop partner in GOOD standing: the normal case, where the
     *  failure is evidence about the tunnel under test and must be counted. */
    @Test
    public void usablePartnerCountsTheFailure() {
        TunnelInfo p = partner(3, false, TunnelTestStatus.GOOD);
        assertFalse(TestJob.shouldDeferForPartner(p, false));
    }

    /** A partner still mid-test is fine: TESTING is not a failure state. */
    @Test
    public void testingPartnerCountsTheFailure() {
        TunnelInfo p = partner(3, false, TunnelTestStatus.TESTING);
        assertFalse(TestJob.shouldDeferForPartner(p, false));
    }

    /** With no partner at all the reply leg never existed, so the round is no
     *  evidence about the tunnel under test and must defer.  This used to
     *  charge the tunnel, which condemned healthy tunnels whenever the paired
     *  pool was momentarily empty.  The defer is still bounded by
     *  MAX_PARTNER_DEFERRALS in testFailed(), so a partner that never returns
     *  cannot shield a genuinely dead tunnel. */
    @Test
    public void missingPartnerDefers() {
        assertTrue(TestJob.shouldDeferForPartner(null, false));
        assertTrue(TestJob.shouldDeferForPartner(null, true));
    }

    /** A 0-hop partner cannot carry the reply: the paired pool is degraded and
     *  the round says nothing about this tunnel. */
    @Test
    public void zeroHopPartnerDefers() {
        assertTrue(TestJob.shouldDeferForPartner(partner(0, false, TunnelTestStatus.GOOD), false));
    }

    /** A 1-hop partner has no entry through which the reply can return, so it
     *  is the same artifact as a 0-hop one. */
    @Test
    public void oneHopPartnerDefers() {
        assertTrue(TestJob.shouldDeferForPartner(partner(1, false, TunnelTestStatus.GOOD), false));
    }

    /** A partner already condemned by the hard-failure counter makes every
     *  round routed through it uninformative. */
    @Test
    public void hardFailedPartnerDefers() {
        assertTrue(TestJob.shouldDeferForPartner(partner(3, true, TunnelTestStatus.GOOD), false));
    }

    /** A partner marked FAILING is retained but out of service; a round
     *  through it is still evidence about the partner pool. */
    @Test
    public void failingPartnerDefers() {
        assertTrue(TestJob.shouldDeferForPartner(partner(3, false, TunnelTestStatus.FAILING), false));
    }

    /** Same for FAILED: the mark is what excludes the partner from selection,
     *  so a round through it cannot clear this tunnel either. */
    @Test
    public void failedPartnerDefers() {
        assertTrue(TestJob.shouldDeferForPartner(partner(3, false, TunnelTestStatus.FAILED), false));
    }

    /** A partner borrowed from the exploratory pool because the paired pool
     *  had nothing to offer is degraded by construction, even when the
     *  borrowed tunnel itself is healthy. */
    @Test
    public void exploratoryFallbackDefers() {
        TunnelInfo p = partner(3, false, TunnelTestStatus.GOOD);
        assertTrue(TestJob.shouldDeferForPartner(p, true));
    }

    /** A stub partner short-circuits regardless of its reported status: the
     *  length alone makes the round uninformative. */
    @Test
    public void stubLengthWinsOverHealthyStatus() {
        assertTrue(TestJob.shouldDeferForPartner(partner(1, false, TunnelTestStatus.GOOD), true));
    }
}
