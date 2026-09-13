package net.i2p.router.message;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the cooldown fast-fail vs transient-gap probe decision in
 * {@link OutboundCache#shouldSkipLeaseSetSend}: a destination whose LeaseSet
 * was valid recently must keep being re-probed (one real lookup per probe
 * interval), while a confirmed-dead destination keeps the original full
 * cooldown fast-fail so lookup floods stay suppressed.
 */
public class LeaseSetSendSkipDecisionTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long GRACE = 10 * 60 * 1000L;
    private static final long PROBE = 30 * 1000L;
    private static final long COOLDOWN = 30 * 1000L;

    @Test
    public void testNoCooldownProceeds() {
        assertFalse(OutboundCache.shouldSkipLeaseSetSend(null, NOW, null, null, GRACE, PROBE));
    }

    @Test
    public void testExpiredCooldownProceeds() {
        assertFalse(OutboundCache.shouldSkipLeaseSetSend(NOW - 1, NOW, null, null, GRACE, PROBE));
    }

    @Test
    public void testNeverValidFastFails() {
        assertTrue(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, null, null, GRACE, PROBE));
    }

    @Test
    public void testStaleValidityFastFails() {
        assertTrue(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, NOW - GRACE - 1, null, GRACE, PROBE));
    }

    @Test
    public void testRecentlyValidProbes() {
        assertFalse(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, NOW - 60_000, null, GRACE, PROBE));
    }

    @Test
    public void testRecentlyValidDeduplicatedIfProbeRecent() {
        assertTrue(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, NOW - 60_000, NOW - 5_000, GRACE, PROBE));
    }

    @Test
    public void testRecentlyValidProbesPastProbeInterval() {
        assertFalse(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, NOW - 60_000, NOW - PROBE - 1, GRACE, PROBE));
    }

    @Test
    public void testBoundaryGraceExactlyTransient() {
        assertFalse(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, NOW - GRACE, null, GRACE, PROBE));
    }

    @Test
    public void testBoundaryOneMsPastGraceFastFails() {
        assertTrue(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, NOW - GRACE - 1, null, GRACE, PROBE));
    }

    @Test
    public void testBoundaryProbeIntervalExactlyAllows() {
        assertFalse(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, NOW - 60_000, NOW - PROBE, GRACE, PROBE));
    }

    @Test
    public void testBoundaryOneMsBeforeProbeIntervalDedups() {
        assertTrue(OutboundCache.shouldSkipLeaseSetSend(NOW + COOLDOWN, NOW, NOW - 60_000, NOW - PROBE + 1, GRACE, PROBE));
    }
}