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

    // ---- swallowed: already FAILED, so nothing is left to do ----

    @Test
    public void deadAndFailedIsARepetition() {
        assertTrue(TunnelPool.alreadyCondemned(true, TunnelTestStatus.FAILED));
    }

    @Test
    public void softOnlyFailedIsARepetition() {
        // FAILED is set with the hard counter, but do not rely on the two
        // always arriving together.
        assertTrue("a FAILED tunnel is excluded whatever the hard counter says",
                   TunnelPool.alreadyCondemned(false, TunnelTestStatus.FAILED));
    }

    // ---- swallowed: soft-only condemnation, which re-marking cannot change ----

    @Test
    public void softOnlyFailingIsARepetition() {
        // The regression: a retained soft-failing tunnel was re-condemned on
        // every subsequent status-3 timeout, logging one WARN each time and
        // letting the streak run past a thousand on a single tunnel.
        assertTrue("re-marking a live FAILING tunnel FAILING is a no-op",
                   TunnelPool.alreadyCondemned(false, TunnelTestStatus.FAILING));
    }

    // ---- must proceed: the escalation that actually condemns the tunnel ----

    @Test
    public void deadButStillFailingMustProceed() {
        assertFalse("a dead FAILING tunnel is not yet excluded, so this report condemns it",
                    TunnelPool.alreadyCondemned(true, TunnelTestStatus.FAILING));
    }

    // ---- must proceed: the first report on a healthy tunnel ----

    @Test
    public void firstReportOnALiveTunnelProceeds() {
        for (TunnelTestStatus st : TunnelTestStatus.values()) {
            if (st == TunnelTestStatus.FAILED || st == TunnelTestStatus.FAILING)
                continue;   // already condemned; covered above
            assertFalse("status " + st + " on a live tunnel must proceed",
                        TunnelPool.alreadyCondemned(false, st));
        }
    }

    /**
     * The cascade that produced the observed flood: a live tunnel crosses the
     * soft removal bar and is marked FAILING. Every later timeout on that same
     * retained tunnel must be swallowed, or each one re-logs and re-requests a
     * replacement the pool already has.
     */
    @Test
    public void softCascadeIsCondemnedExactlyOnce() {
        TunnelTestStatus status = TunnelTestStatus.GOOD;
        int condemnations = 0;
        for (int report = 0; report < 5000; report++) {
            if (TunnelPool.alreadyCondemned(false, status))
                continue;
            condemnations++;
            status = TunnelTestStatus.FAILING;
        }
        assertEquals("only the crossing report may condemn", 1, condemnations);
    }

    // ---- defensive: absent status is not a condemnation ----

    @Test
    public void nullStatusIsNotACondemnation() {
        assertFalse(TunnelPool.alreadyCondemned(true, null));
        assertFalse(TunnelPool.alreadyCondemned(false, null));
    }
}
