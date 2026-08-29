package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 *  Pins the re-mint decision in {@link RepublishLeaseSetJob#shouldRemint}:
 *  the emergency backstop (any viable lease once the stored copy is inside the
 *  emergency window) versus the normal viability gate (extends expiry and
 *  carries the required count).  These are the boundaries that keep a
 *  deficit-ridden pool from letting a public LeaseSet lapse while preventing
 *  re-mint treadmills on near-dead leases.
 */
public class RemintDecisionTest {
    private static final long WINDOW = 2L * 60 * 1000;

    /**
     *  Inside the emergency window a single viable lease against a higher
     *  required count re-mints immediately — thin beats lapsed.
     */
    @Test
    public void testEmergencyRemintsWithSingleViableLease() {
        assertTrue(RepublishLeaseSetJob.shouldRemint(1, 2, true, 60_000L, WINDOW));
    }

    /**
     *  Exactly at the emergency boundary still re-mints.
     */
    @Test
    public void testExactEmergencyBoundaryRemints() {
        assertTrue(RepublishLeaseSetJob.shouldRemint(2, 3, true, 120_000L, WINDOW));
    }

    /**
     *  A millisecond past the boundary is outside the emergency window, so
     *  the normal required-count gate applies and a deficit defers.
     */
    @Test
    public void testJustOutsideEmergencyBoundaryNeedsNormalGate() {
        assertFalse(RepublishLeaseSetJob.shouldRemint(1, 2, true, 121_000L, WINDOW));
    }

    /**
     *  A healthy pool extending the stored copy re-mints at any remaining
     *  time.
     */
    @Test
    public void testHealthyPoolRemints() {
        assertTrue(RepublishLeaseSetJob.shouldRemint(3, 2, true, 5L * 60 * 1000, WINDOW));
    }

    /**
     *  The emergency backstop does not require the pool copy to extend the
     *  stored copy — the 10-minute lease cap guarantees every re-mint differs,
     *  so a rescue copy is accepted and re-flooded regardless.
     */
    @Test
    public void testEmergencyBypassesExtensionCheck() {
        assertTrue(RepublishLeaseSetJob.shouldRemint(1, 2, false, 60_000L, WINDOW));
    }

    /**
     *  A pool copy with no viable leases never re-mints, even deep inside the
     *  emergency window — re-signing near-dead leases pads nothing.
     */
    @Test
    public void testNoViableLeasesNeverRemints() {
        assertFalse(RepublishLeaseSetJob.shouldRemint(0, 2, true, 10_000L, WINDOW));
    }

    /**
     *  Outside the emergency window, a pool copy that neither extends the
     *  stored copy nor meets the required count defers.
     */
    @Test
    public void testBelowRequiredWithoutExtensionOutsideWindowDeferred() {
        assertFalse(RepublishLeaseSetJob.shouldRemint(1, 2, false, 5L * 60 * 1000, WINDOW));
    }

    /**
     *  Outside the emergency window, meeting the required count without
     *  extending the stored copy still defers — it would re-sign the same
     *  near-expired leases.
     */
    @Test
    public void testRequirementMetWithoutExtensionOutsideWindowDeferred() {
        assertFalse(RepublishLeaseSetJob.shouldRemint(3, 2, false, 5L * 60 * 1000, WINDOW));
    }
}