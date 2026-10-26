package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * A subsystem's claim on job runners, and the floor built from those claims.
 *
 * <p>A claim used to be an unbounded counter: {@code requestRunners} added to
 * it and scheduled a fresh timer event per call, so the caller had to release
 * exactly what it had requested, every pass, and the only thing enforcing that
 * was call-site discipline. The Tuner's release-then-request pair let the
 * scaler's check land between the two calls and see a floor 2 lower, scale
 * down, then scale the runners back in — threads made and destroyed for
 * nothing. A claim is now a lease with an expiry, and setting it replaces it.
 *
 * @since 0.9.72+
 */
public class JobQueueLeaseTest {

    private RouterContext _ctx;
    private JobQueueScaler _scaler;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        // a private scaler, so the context's own running scaler is not involved
        _scaler = new JobQueueScaler(_ctx, _ctx.jobQueue());
    }

    @After
    public void tearDown() {
        if (_ctx != null) {
            _ctx.jobQueue().shutdown();
        }
    }

    /** Setting a claim puts it on the floor. */
    @Test
    public void setPlacesTheFloor() {
        _scaler.setRunners("ls-lookup", 3, 60_000L);
        assertEquals(3, _scaler.getExternalFloor());
    }

    /**
     * Setting a claim replaces it. The old request path added to it, so a
     * caller that asked on every pass without releasing pushed the floor up
     * without limit.
     */
    @Test
    public void setReplacesRatherThanAccumulates() {
        _scaler.setRunners("ls-lookup", 2, 60_000L);
        for (int i = 0; i < 20; i++) {
            _scaler.setRunners("ls-lookup", 2, 60_000L);
        }
        assertEquals(2, _scaler.getExternalFloor());
    }

    /** Setting a claim to zero withdraws it. */
    @Test
    public void setZeroWithdraws() {
        _scaler.setRunners("ls-lookup", 2, 60_000L);
        _scaler.setRunners("ls-lookup", 0, 60_000L);
        assertEquals(0, _scaler.getExternalFloor());
    }

    /** Distinct reasons add up. */
    @Test
    public void reasonsSum() {
        _scaler.setRunners("ls-lookup", 2, 60_000L);
        _scaler.setRunners("ri-lookup", 3, 60_000L);
        assertEquals(5, _scaler.getExternalFloor());
    }

    /**
     * A claim from a caller that stopped calling decays on its own. Nothing
     * releases it, and no timer event is involved: the floor is the sum of the
     * live leases.
     */
    @Test
    public void expiredLeaseLeavesTheFloor() throws Exception {
        _scaler.setRunners("ls-lookup", 4, 30L);
        assertEquals(4, _scaler.getExternalFloor());
        Thread.sleep(120L);
        assertEquals(0, _scaler.getExternalFloor());
    }

    /** A lease refreshed before it expires stays. */
    @Test
    public void refreshedLeaseStays() throws Exception {
        _scaler.setRunners("ls-lookup", 4, 120L);
        for (int i = 0; i < 6; i++) {
            Thread.sleep(25L);
            _scaler.setRunners("ls-lookup", 4, 120L);
            assertEquals("lease dropped while being refreshed at pass " + i,
                         4, _scaler.getExternalFloor());
        }
    }

    /** The documented request-accumulates path, kept for existing callers. */
    @Test
    public void requestAccumulates() {
        _scaler.requestRunners(2, "burst", 60_000L);
        _scaler.requestRunners(2, "burst", 60_000L);
        assertEquals(4, _scaler.getExternalFloor());
    }

    /** Releasing more than was requested floors at zero, never negative. */
    @Test
    public void releaseClampsAtZero() {
        _scaler.requestRunners(2, "burst", 60_000L);
        _scaler.releaseRunners(5, "burst");
        assertEquals(0, _scaler.getExternalFloor());
    }

    /** Releasing one reason leaves the others standing. */
    @Test
    public void releaseIsPerReason() {
        _scaler.setRunners("ls-lookup", 2, 60_000L);
        _scaler.setRunners("ri-lookup", 3, 60_000L);
        _scaler.releaseRunners(2, "ls-lookup");
        assertEquals(3, _scaler.getExternalFloor());
    }

    /** A lease with no expiry never decays. */
    @Test
    public void noExpiryMeansNoDecay() {
        _scaler.setRunners("standing", 2, 0L);
        assertEquals(2, _scaler.getExternalFloor());
    }

    /** The expiry rule itself, at its boundary. */
    @Test
    public void leaseExpiryBoundary() {
        long now = 100_000L;
        assertTrue(new JobQueueScaler.Lease(2, 0L).isLive(now));
        assertTrue(new JobQueueScaler.Lease(2, now + 1).isLive(now));
        assertFalse(new JobQueueScaler.Lease(2, now).isLive(now));
        assertFalse(new JobQueueScaler.Lease(2, now - 1).isLive(now));
        assertFalse(new JobQueueScaler.Lease(0, 0L).isLive(now));
    }
}
