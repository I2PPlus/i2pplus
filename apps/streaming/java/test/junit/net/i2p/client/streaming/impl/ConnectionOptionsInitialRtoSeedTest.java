package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.util.Properties;

import org.junit.Test;

/**
 * Tests for the retransmit timeout a connection starts with before it has
 * measured anything, {@link ConnectionOptions#initialRtoMs(int)}. The seed used
 * to be the initial-RTO constant on its own and never went through the bounds
 * {@code computeRTO()} applies to a computed RTO, so a router whose Tuner had
 * pulled its RTO ceiling down to 2,005ms still handed a fresh connection a
 * 9,000ms first timeout.
 */
public class ConnectionOptionsInitialRtoSeedTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The router-wide bounds each case moves are always restored.
     *
     * @since 0.9.72+
     */
    public ConnectionOptionsInitialRtoSeedTest() {}

    /** The shipped initial RTO, the value the seed used to be. */
    private static final int SHIPPED_INITIAL_RTO = 9000;

    /** The shipped resend-delay floor, the pair's low bound. */
    private static final int SHIPPED_MIN_RESEND_DELAY = 100;

    /** The shipped resend-delay ceiling, the pair's high bound. */
    private static final int SHIPPED_MAX_RESEND_DELAY = 20000;

    /**
     * The seed is the same 1.5x derivation {@code computeRTO()} uses for a
     * connection's first sample, applied to the initial RTT estimate - so a
     * connection's first loss costs one and a half round trips of the path it
     * already assumes, not a separate constant.
     */
    @Test
    public void seedTracksTheInitialRttEstimate() {
        pinShippedBounds();
        try {
            int rtt = ConnectionOptions.DEFAULT_INITIAL_RTT;
            assertEquals(rtt + rtt / 2, ConnectionOptions.initialRtoMs(rtt));
            assertTrue("the seed must be below the constant it replaced",
                       ConnectionOptions.initialRtoMs(rtt) < SHIPPED_INITIAL_RTO);
        } finally {
            restoreBounds();
        }
    }

    /**
     * A slower assumed path raises the seed in step, up to the configured initial
     * RTO, which stays the ceiling on it.
     */
    @Test
    public void seedRisesWithTheEstimateUpToTheConfiguredCeiling() {
        pinShippedBounds();
        try {
            assertEquals(3000, ConnectionOptions.initialRtoMs(2000));
            assertEquals(6000, ConnectionOptions.initialRtoMs(4000));
            // an 8s estimate would imply 12s, above the shipped ceiling
            assertEquals(SHIPPED_INITIAL_RTO, ConnectionOptions.initialRtoMs(8000));
        } finally {
            restoreBounds();
        }
    }

    /**
     * The seed goes through the same clamp as a computed RTO. This is the live
     * case: the Tuner had moved Max Resend Delay to 2,005ms and Min Resend Delay
     * to 1,062ms, and the seed still read 9,000ms - the same quantity bounded two
     * ways, with the looser bound winning on exactly the fresh connections that
     * have no measurement to argue with.
     */
    @Test
    public void seedIsClampedByTheResendDelayBounds() {
        pinShippedBounds();
        try {
            ConnectionOptions.setMaxResendDelay(2005);
            ConnectionOptions.setMinResendDelay(1062);
            assertEquals(2005, ConnectionOptions.initialRtoMs(2000));
            // and the floor binds from the other side
            ConnectionOptions.setMinResendDelay(5000);
            assertEquals(5000, ConnectionOptions.initialRtoMs(2000));
        } finally {
            restoreBounds();
        }
    }

    /**
     * The Tuner keeps its knob in both directions: lowering the initial RTO below
     * what the RTT estimate implies lowers the seed, and raising it cannot lift
     * the seed past the estimate.
     */
    @Test
    public void tunerCanMoveTheSeedInBothDirections() {
        pinShippedBounds();
        try {
            ConnectionOptions.setInitialRTO(1500);
            assertEquals(1500, ConnectionOptions.initialRtoMs(2000));
            ConnectionOptions.setInitialRTO(30000);
            assertEquals(3000, ConnectionOptions.initialRtoMs(2000));
        } finally {
            restoreBounds();
        }
    }

    /**
     * A connection's options actually carry the seed, so the change reaches the
     * retransmit timer and not just the helper.
     */
    @Test
    public void freshOptionsCarryTheSeed() {
        pinShippedBounds();
        try {
            ConnectionOptions opts = new ConnectionOptions(new Properties());
            assertEquals(ConnectionOptions.initialRtoMs(ConnectionOptions.DEFAULT_INITIAL_RTT),
                         opts.getRTO());
            assertEquals(3000, opts.getRTO());
        } finally {
            restoreBounds();
        }
    }

    /**
     * An explicit per-socket initial RTO still wins outright - it is what
     * I2PSnark and the IRC client use to ask for a longer first timeout, and the
     * seed must not second-guess a caller that named a value.
     */
    @Test
    public void explicitPropertyStillWins() {
        pinShippedBounds();
        try {
            Properties props = new Properties();
            props.setProperty(ConnectionOptions.PROP_INITIAL_RTO, "20000");
            ConnectionOptions opts = new ConnectionOptions(props);
            assertEquals(20000, opts.getRTO());
        } finally {
            restoreBounds();
        }
    }

    /**
     * The seed survives a copy, and a copy still carries the source's measured
     * RTO rather than re-seeding - tunnel failover hands the next attempt the
     * options of the attempt before it.
     */
    @Test
    public void copyCarriesTheSeedAndAMeasuredRto() {
        pinShippedBounds();
        try {
            ConnectionOptions src = new ConnectionOptions(new Properties());
            int seed = src.getRTO();
            assertEquals(seed, new ConnectionOptions(src).getRTO());
            src.updateRTT(1200);
            ConnectionOptions copy = new ConnectionOptions(src);
            assertEquals("a measured RTO is carried, not re-seeded", src.getRTO(), copy.getRTO());
            assertTrue("the measured RTO is not the seed", src.getRTO() != seed);
        } finally {
            restoreBounds();
        }
    }

    /**
     * The floor in effect before {@link #pinShippedBounds()} moved the pair, for
     * {@link #restoreBounds()} to put back.
     */
    private int _savedMinResendDelay;

    /** The ceiling in effect before {@link #pinShippedBounds()} moved the pair. */
    private int _savedMaxResendDelay;

    /** The initial RTO in effect before {@link #pinShippedBounds()} moved it. */
    private int _savedInitialRTO;

    /**
     * Sets the router-wide resend-delay pair and initial RTO to their shipped
     * values, so every case sees a known clamp whatever an earlier test class
     * left behind, and remembers what to put back.
     */
    private void pinShippedBounds() {
        _savedMinResendDelay = ConnectionOptions.getMinResendDelayStatic();
        _savedMaxResendDelay = ConnectionOptions.getMaxResendDelayStatic();
        _savedInitialRTO = ConnectionOptions.getInitialRTO();
        ConnectionOptions.setMinResendDelay(SHIPPED_MIN_RESEND_DELAY);
        ConnectionOptions.setMaxResendDelay(SHIPPED_MAX_RESEND_DELAY);
        ConnectionOptions.setInitialRTO(SHIPPED_INITIAL_RTO);
    }

    /**
     * Puts the router-wide values back, so a case that moved the clamp cannot leak
     * it into the next test class in the same JVM.
     */
    private void restoreBounds() {
        ConnectionOptions.setMaxResendDelay(_savedMaxResendDelay);
        ConnectionOptions.setMinResendDelay(_savedMinResendDelay);
        ConnectionOptions.setInitialRTO(_savedInitialRTO);
    }
}
