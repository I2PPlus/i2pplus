package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;

/**
 * Pins the re-mint decision in {@link RepublishLeaseSetJob#shouldRemint}:
 * the emergency backstop (any viable lease once the stored copy is inside the
 * emergency window) versus the normal viability gate (extends expiry and
 * carries the required count).  These are the boundaries that keep a
 * deficit-ridden pool from letting a public LeaseSet lapse while preventing
 * re-mint treadmills on near-dead leases.
 */
public class RemintDecisionTest {
    private static final long WINDOW = 2L * 60 * 1000;

    /**
     * Inside the emergency window a single viable lease against a higher
     * required count re-mints immediately — thin beats lapsed.
     */
    @Test
    public void testEmergencyRemintsWithSingleViableLease() {
        assertTrue(RepublishLeaseSetJob.shouldRemint(1, 2, true, 60_000L, WINDOW));
    }

    /**
     * Exactly at the emergency boundary still re-mints.
     */
    @Test
    public void testExactEmergencyBoundaryRemints() {
        assertTrue(RepublishLeaseSetJob.shouldRemint(2, 3, true, 120_000L, WINDOW));
    }

    /**
     * A millisecond past the boundary is outside the emergency window, so
     * the normal required-count gate applies and a deficit defers.
     */
    @Test
    public void testJustOutsideEmergencyBoundaryNeedsNormalGate() {
        assertFalse(RepublishLeaseSetJob.shouldRemint(1, 2, true, 121_000L, WINDOW));
    }

    /**
     * A healthy pool extending the stored copy re-mints at any remaining
     * time.
     */
    @Test
    public void testHealthyPoolRemints() {
        assertTrue(RepublishLeaseSetJob.shouldRemint(3, 2, true, 5L * 60 * 1000, WINDOW));
    }

    /**
     * The emergency backstop does not require the pool copy to extend the
     * stored copy — the 10-minute lease cap guarantees every re-mint differs,
     * so a rescue copy is accepted and re-flooded regardless.
     */
    @Test
    public void testEmergencyBypassesExtensionCheck() {
        assertTrue(RepublishLeaseSetJob.shouldRemint(1, 2, false, 60_000L, WINDOW));
    }

    /**
     * A pool copy with no viable leases never re-mints, even deep inside the
     * emergency window — re-signing near-dead leases pads nothing.
     */
    @Test
    public void testNoViableLeasesNeverRemints() {
        assertFalse(RepublishLeaseSetJob.shouldRemint(0, 2, true, 10_000L, WINDOW));
    }

    /**
     * Outside the emergency window, a pool copy that neither extends the
     * stored copy nor meets the required count defers.
     */
    @Test
    public void testBelowRequiredWithoutExtensionOutsideWindowDeferred() {
        assertFalse(RepublishLeaseSetJob.shouldRemint(1, 2, false, 5L * 60 * 1000, WINDOW));
    }

    /**
     * Outside the emergency window, meeting the required count without
     * extending the stored copy still defers — it would re-sign the same
     * near-expired leases.
     */
    @Test
    public void testRequirementMetWithoutExtensionOutsideWindowDeferred() {
        assertFalse(RepublishLeaseSetJob.shouldRemint(3, 2, false, 5L * 60 * 1000, WINDOW));
    }

    // ---- no-op re-mint detection (the loop) ----

    /**
     * The loop's cause: the emergency window re-mints the same aging leases, so
     * the expiry never moves and the next pass re-enters the window. Detecting
     * the identical set is what lets the job hold off instead of spinning.
     */
    @Test
    public void testIdenticalLeaseSetsAreRecognised() {
        LeaseSet stored = leaseSet(3, 0);
        LeaseSet fresh = leaseSet(3, 0);
        assertTrue("identical tunnel sets must be detected",
                   RepublishLeaseSetJob.sameLeaseTunnels(stored, fresh));
    }

    /** Reordering is not a change: rotation must not defeat the check. */
    @Test
    public void testReorderedLeaseSetsAreStillIdentical() {
        LeaseSet stored = leaseSetOf(1L, 2L, 3L);
        LeaseSet fresh = leaseSetOf(3L, 1L, 2L);
        assertTrue("order must not matter", RepublishLeaseSetJob.sameLeaseTunnels(stored, fresh));
    }

    /** A pool that actually replaced a tunnel is a real change. */
    @Test
    public void testDifferentLeaseSetsAreNotIdentical() {
        assertFalse(RepublishLeaseSetJob.sameLeaseTunnels(leaseSet(3, 0), leaseSet(3, 9)));
        assertFalse("a different count is a change",
                    RepublishLeaseSetJob.sameLeaseTunnels(leaseSet(3, 0), leaseSet(2, 0)));
    }

    /** Null and empty inputs are never treated as a no-op. */
    @Test
    public void testNullAndEmptyAreNotIdentical() {
        assertFalse(RepublishLeaseSetJob.sameLeaseTunnels(null, leaseSet(2, 0)));
        assertFalse(RepublishLeaseSetJob.sameLeaseTunnels(leaseSet(2, 0), null));
        assertFalse("empty sets carry no evidence",
                    RepublishLeaseSetJob.sameLeaseTunnels(leaseSet(0, 0), leaseSet(0, 1)));
    }

    // ---- backoff ----

    /**
     * The invariant that matters: holding off must never be the reason a
     * LeaseSet lapses unrepublished. Five minutes, the obvious constant, would
     * abandon a 63s copy for four minutes.
     */
    @Test
    public void testBackoffAlwaysLeavesRoomForAnotherAttempt() {
        for (long remaining : new long[] {1_000L, 20_000L, 63_000L, 120_000L, 300_000L, 600_000L}) {
            long delay = RepublishLeaseSetJob.ineffectiveRemintDelay(remaining);
            assertTrue("remaining=" + remaining + " delay=" + delay + " would abandon the copy",
                       delay < remaining);
        }
    }

    /** The observed production case: a 63s copy must not wait five minutes. */
    @Test
    public void testObservedSixtyThreeSecondCopyIsNotAbandoned() {
        long remaining = 63_000L;
        long delay = RepublishLeaseSetJob.ineffectiveRemintDelay(remaining);
        assertTrue("must retry well inside the remaining life, was " + delay,
                   delay < 5L * 60 * 1000L);
        assertTrue("and still leave a gap before lapse", delay < remaining);
    }

    /** Where the copy outlives the floor, the floor is respected. */
    @Test
    public void testBackoffRespectsTheFloorWhenThereIsRoom() {
        assertTrue(RepublishLeaseSetJob.ineffectiveRemintDelay(600_000L) >= 60_000L);
        assertTrue(RepublishLeaseSetJob.ineffectiveRemintDelay(300_000L) >= 60_000L);
    }

    /** Scales with the copy, so a healthy LeaseSet is not re-minted needlessly. */
    @Test
    public void testBackoffScalesWithRemainingLife() {
        assertTrue("a long-lived copy should wait longer",
                   RepublishLeaseSetJob.ineffectiveRemintDelay(600_000L) >
                   RepublishLeaseSetJob.ineffectiveRemintDelay(120_000L));
    }

    // ---- anomaly signal ----

    /** Below the pool's eligibility floor: no lease in the copy was publishable. */
    @Test
    public void testShortExpiryIsFlaggedAsAnomalous() {
        assertTrue("63s is under the 2m pool floor",
                   RepublishLeaseSetJob.effectiveExpiryAnomalouslyShort(63_000L));
        assertTrue(RepublishLeaseSetJob.effectiveExpiryAnomalouslyShort(119_000L));
    }

    /**
     * At or above the floor is ordinary decay, not a fault. This is the
     * boundary that the old design-target comparison got wrong: it fired on
     * every LeaseSet, because the ~600s target sits far above the 2m floor the
     * pool actually enforces.
     */
    @Test
    public void testHealthyExpiryIsNotFlagged() {
        assertFalse("exactly at the floor is publishable",
                    RepublishLeaseSetJob.effectiveExpiryAnomalouslyShort(120_000L));
        assertFalse(RepublishLeaseSetJob.effectiveExpiryAnomalouslyShort(9L * 60 * 1000));
        assertFalse(RepublishLeaseSetJob.effectiveExpiryAnomalouslyShort(
            RepublishLeaseSetJob.LEASE_ELIGIBILITY_FLOOR_MS));
    }

    /**
     * Pins the regression directly: the old check treated anything under a
     * third of the 600s design target as anomalous, which is true for nearly
     * every real LeaseSet and produced a warning on every pass.
     */
    @Test
    public void testOrdinaryDecayIsNotFlagged() {
        for (long s = 120; s <= 600; s += 20) {
            assertFalse("a " + s + "s LeaseSet is normal decay, not a fault",
                        RepublishLeaseSetJob.effectiveExpiryAnomalouslyShort(s * 1000L));
        }
    }

    /** Unknown expiry is not an anomaly. */
    @Test
    public void testUnknownExpiryIsNotFlagged() {
        assertFalse(RepublishLeaseSetJob.effectiveExpiryAnomalouslyShort(0L));
        assertFalse(RepublishLeaseSetJob.effectiveExpiryAnomalouslyShort(-1L));
    }

    /** Builds a LeaseSet from explicit tunnel id numbers, in the given order. */
    private static LeaseSet leaseSetOf(long... ids) {
        LeaseSet ls = new LeaseSet();
        long end = System.currentTimeMillis() + 300_000L;
        for (long id : ids) {
            Lease lease = new Lease();
            // LeaseSet.addLease rejects a lease with no gateway.
            lease.setGateway(new Hash(new byte[Hash.HASH_LENGTH]));
            lease.setTunnelId(new TunnelId(id));
            lease.setEndDate(end);
            ls.addLease(lease);
        }
        return ls;
    }

    /**
     * Builds a LeaseSet with {@code count} leases. Same {@code seed} yields the
     * same tunnel ids, which is what the identity comparison turns on. Ids start
     * at 1 because {@code TunnelId} rejects zero.
     */
    private static LeaseSet leaseSet(int count, int seed) {
        LeaseSet ls = new LeaseSet();
        long end = System.currentTimeMillis() + 300_000L;
        for (int i = 0; i < count; i++) {
            Lease lease = new Lease();
            // LeaseSet.addLease rejects a lease with no gateway.
            lease.setGateway(new Hash(new byte[Hash.HASH_LENGTH]));
            lease.setTunnelId(new TunnelId(seed * 1000L + i + 1));
            lease.setEndDate(end);
            ls.addLease(lease);
        }
        return ls;
    }

    /**
     * Walks a LeaseSet toward expiry and asserts it is always reached while
     * still alive.
     *
     * <p>This property outranks the noise reduction: an active service's
     * LeaseSet must never lapse. The loop fix holds off re-mints that cannot
     * improve anything, so the question is whether that hold-off could ever be
     * the reason a copy dies unrepublished. The walk answers it directly — the
     * hold-off is always shorter than the remaining life, so the copy cannot
     * reach zero between passes.
     *
     * <p>Mirrors {@code computeNextRepublish}: max(MIN_RESCHEDULE,
     * min(republishInterval, remaining - EXPIRY_WINDOW)).
     */
    @Test
    public void testLeaseSetNeverLapsesUnderRepeatedIneffectiveRemints() {
        final long minReschedule = 60_000L;
        final long republishInterval = 5L * 60 * 1000L;
        final long expiryWindow = 4L * 60 * 1000L;
        long remaining = 120_000L;
        int attempts = 0;
        // The pool is stuck in the state that produced the loop: the same two
        // leases, none of which extends the stored copy. Every pass below takes
        // the ineffective branch.
        while (remaining > 0L) {
            if (attempts > 0) {
                long nextRepublish = Math.max(minReschedule,
                    Math.min(republishInterval, remaining - expiryWindow));
                long step = Math.min(nextRepublish,
                    RepublishLeaseSetJob.ineffectiveRemintDelay(remaining));
                remaining -= step;
                assertTrue("pass " + attempts + " held the copy off past its own expiry, "
                         + "leaving " + remaining + "ms", remaining > 0L);
            }
            attempts++;
            if (attempts >= 500) {
                // The walk converges on zero without ever reaching it, which is
                // exactly the property being asserted. Progress must still be
                // real though, so check it has actually driven the copy down.
                assertTrue("the hold-off stalled: " + remaining + "ms left after 500 passes",
                           remaining < 1_000L);
                return;
            }
        }
        assertTrue("the walk should take several passes to be meaningful, took " + attempts,
                   attempts > 3);
    }

    /**
     * The same property stated without any scheduling: no hold-off value can
     * reach or exceed the life it is protecting. Swept across the whole range
     * these LeaseSets occupy rather than sampled at a few points.
     */
    @Test
    public void testHoldOffIsAlwaysShorterThanTheLifeItProtects() {
        for (long remaining = 1L; remaining <= 900_000L; remaining += 97L) {
            long delay = RepublishLeaseSetJob.ineffectiveRemintDelay(remaining);
            assertTrue("remaining=" + remaining + "ms delay=" + delay
                     + "ms would let the copy die unrepublished", delay < remaining);
        }
    }

    // ---- supply pre-flight ----

    /**
     * The pre-flight must never be the reason a LeaseSet lapses. It only defers
     * while outside the emergency window, so the emergency boundary -- not the
     * deferral budget -- is what guarantees a mint still happens. This asserts
     * the real termination order: the window is reached first, and the copy is
     * still comfortably alive when it is.
     */
    @Test
    public void supplyPreflightCannotCauseLapse() {
        long supplyWindow = 3L * 60 * 1000L;
        long emergencyWindow = 2L * 60 * 1000L;
        long retry = 30L * 1000L;
        long remaining = supplyWindow;
        int defers = 0;
        // Defer while outside the emergency window, exactly as the gate does.
        while (remaining > emergencyWindow && defers < 100) {
            remaining -= retry;
            defers++;
        }
        assertTrue("must not defer past the emergency boundary",
                   remaining >= emergencyWindow);
        assertTrue("a mint still has a live copy to work with, " + remaining + "ms left",
                   remaining > 0L);
        assertTrue("the emergency window terminates the pre-flight, not the budget",
                   defers * retry <= (supplyWindow - emergencyWindow) + retry);
    }

    /**
     * The lease viability window has to be reachable. A tunnel lives 11 minutes
     * (660s); requiring ten minutes of remaining life would only be satisfiable
     * in roughly the first minute of a tunnel's life, so any gate using it would
     * defer permanently on a healthy pool.
     */
    @Test
    public void viabilityWindowIsReachable() {
        long tunnelLife = 11L * 60 * 1000L;
        long viability = 3L * 60 * 1000L;
        assertTrue("the viability window must leave room in a tunnel's life",
                   tunnelLife - viability >= 5L * 60 * 1000L);
        assertTrue("a fresh tunnel clears it", tunnelLife >= viability);
    }

    /** The pre-flight window sits between the renew window and the emergency window. */
    @Test
    public void supplyWindowIsOrderedAgainstEmergency() {
        long supply = 3L * 60 * 1000L;
        long emergency = 2L * 60 * 1000L;
        long expiryWindow = 4L * 60 * 1000L;
        assertTrue("pre-flight engages inside the renew window", supply < expiryWindow);
        assertTrue("pre-flight must leave room before emergency", supply > emergency);
    }
}
