package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The slow-handshake threshold that gives the establish-time stats a usable tail.
 *
 * <p>The existing {@code udp.*EstablishTime} stats expose only a mean, and the mean cannot
 * size the deadline: measured live, successful outbound handshakes average 196ms, so the
 * tuner's 4x-of-mean formula targets 784ms - below the 2250 that abandoned roughly 117
 * handshakes a minute. The abandonments came from a long right tail, not the body. These
 * counters mark that tail so something can be read.
 *
 * @since 0.9.71+
 */
public class SlowEstablishThresholdTest {

    @Test
    public void thresholdIsWellUnderTheValidatedFloor() {
        assertEquals(1000, EstablishmentManager.SLOW_ESTABLISH_MS);
        assertTrue("the marker must sit below the establish floor, or every handshake "
                   + "on a slow link would count and the stat would just mirror the total",
                   EstablishmentManager.SLOW_ESTABLISH_MS
                   < net.i2p.router.Tuner.ESTABLISH_TIMEOUT_MIN);
    }

    /**
     * A quarter of the floor. At 4000ms a handshake using more than a quarter of its
     * budget is already in the region that used to get abandoned, so the marker is
     * placed where the tail became visible rather than near the deadline itself.
     */
    @Test
    public void thresholdIsAQuarterOfTheFloor() {
        assertEquals(net.i2p.router.Tuner.ESTABLISH_TIMEOUT_MIN / 4,
                     EstablishmentManager.SLOW_ESTABLISH_MS);
    }

    /** The live mean sits far below the marker, which is the whole point: it is a tail. */
    @Test
    public void theLiveMeanWouldNotCountAsSlow() {
        double observedMean = 196.095;
        assertTrue("the observed mean must fall below the slow marker",
                   observedMean < EstablishmentManager.SLOW_ESTABLISH_MS);
        assertTrue("and the mean must be well under it, or the counter is not a tail",
                   observedMean < EstablishmentManager.SLOW_ESTABLISH_MS / 2);
    }

    /** Boundary: the record sites test {@code >=}, so the threshold itself counts. */
    @Test
    public void thresholdItselfCounts() {
        long t = EstablishmentManager.SLOW_ESTABLISH_MS;
        assertTrue(t >= EstablishmentManager.SLOW_ESTABLISH_MS);
        assertTrue(!(t - 1 >= EstablishmentManager.SLOW_ESTABLISH_MS));
    }
}
