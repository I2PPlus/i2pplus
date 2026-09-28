package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import net.i2p.router.TunnelTestStatus;

import org.junit.Test;

/**
 * Contract tests for {@link TunnelPool#alreadyCondemned(boolean, TunnelTestStatus)}.
 *
 * <p>A condemned tunnel is retained until it expires and replaced immediately,
 * so it keeps being tested and can keep reporting failures.  Re-processing
 * those reports costs a duplicate log line, asks the pool for a replacement it
 * already has, and — because the tunnel is retained — lets a dead tunnel's
 * failure count climb for its whole remaining lifetime.  The predicate decides
 * which reports carry no new information.
 *
 * <p>The case that must <em>not</em> be swallowed is the soft-to-hard
 * escalation: a tunnel whose hard counter is tripped but whose status is still
 * FAILING has not yet been excluded from selection, so its report is the one
 * that actually condemns it.
 *
 * @since 0.9.71+
 */
public class TunnelPoolCondemnDecisionTest {

    // ---- swallowed: dead and already FAILED, so nothing is left to do ----

    @Test
    public void deadAndFailedIsARepetition() {
        assertTrue(TunnelPool.alreadyCondemned(true, TunnelTestStatus.FAILED));
    }

    // ---- must proceed: the escalation that actually condemns the tunnel ----

    @Test
    public void deadButStillFailingMustProceed() {
        assertFalse("a dead FAILING tunnel is not yet excluded, so this report condemns it",
                    TunnelPool.alreadyCondemned(true, TunnelTestStatus.FAILING));
    }

    @Test
    public void liveButFailedMustProceed() {
        assertFalse("a FAILED status is only set with the hard counter, but do not rely on it",
                    TunnelPool.alreadyCondemned(false, TunnelTestStatus.FAILED));
    }

    // ---- must proceed: the first report on a healthy tunnel ----

    @Test
    public void firstReportOnALiveTunnelProceeds() {
        for (TunnelTestStatus st : TunnelTestStatus.values()) {
            assertFalse("status " + st + " on a live tunnel must proceed",
                        TunnelPool.alreadyCondemned(false, st));
        }
    }

    // ---- defensive: absent status is not a condemnation ----

    @Test
    public void nullStatusIsNotACondemnation() {
        assertFalse(TunnelPool.alreadyCondemned(true, null));
        assertFalse(TunnelPool.alreadyCondemned(false, null));
    }
}
