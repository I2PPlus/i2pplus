package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests {@link ExpireRoutersJob#dropProbability}: under netdb size pressure,
 * never-attested peers go first, recently-attested peers are protected, and
 * long-attested-but-silent peers go harder than the flat probability.
 *
 * @since 0.9.71+
 */
public class ExpireRoutersJobTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 60 * 60 * 1000L;

    @Test
    public void neverAttestedDroppedHarderCappedAtCertain() {
        assertEquals(80, ExpireRoutersJob.dropProbability(40, 0, NOW));
        assertEquals(128, ExpireRoutersJob.dropProbability(80, 0, NOW));
    }

    @Test
    public void recentlyAttestedProtected() {
        assertEquals(0, ExpireRoutersJob.dropProbability(80, NOW - HOUR, NOW));
        assertEquals(0, ExpireRoutersJob.dropProbability(80, NOW - ExpireRoutersJob.PROTECTED_ATTESTED + 1, NOW));
    }

    @Test
    public void middlingAttestedDroppedHalf() {
        long last = NOW - 24 * HOUR; // between PROTECTED_ATTESTED and STALE_ATTESTED
        assertEquals(20, ExpireRoutersJob.dropProbability(40, last, NOW));
    }

    @Test
    public void longSilentDroppedHarder() {
        long last = NOW - 5 * 24 * HOUR; // past STALE_ATTESTED
        assertEquals(60, ExpireRoutersJob.dropProbability(40, last, NOW));
        assertEquals(120, ExpireRoutersJob.dropProbability(80, last, NOW));
    }

    @Test
    public void boundaries() {
        // exactly at the protection edge -> no longer protected, half rate
        assertEquals(20, ExpireRoutersJob.dropProbability(40, NOW - ExpireRoutersJob.PROTECTED_ATTESTED, NOW));
        // exactly at the stale edge -> no longer half rate, harder drop
        assertEquals(60, ExpireRoutersJob.dropProbability(40, NOW - ExpireRoutersJob.STALE_ATTESTED, NOW));
    }
}
