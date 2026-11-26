package net.i2p.i2ptunnel;

import java.util.Properties;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the IRC latency-tolerance profile applied by
 * I2PTunnelIRCClient.setLatencyTolerantOptions: the option keys it writes,
 * the window it writes, and the operator settings it must not overwrite.
 */
public class I2PTunnelIRCClientLatencyTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the class carries no per-test state, only the option keys the
     * profile is checked against.
     *
     * @since 0.9.72+
     */
    public I2PTunnelIRCClientLatencyTest() {}

    /**
     * The tolerance is the 320s figure chosen to outlast a multi-minute
     * latency spike on a path to an IRC server without hanging on a peer
     * that is really gone.
     */
    @Test
    public void testLatencyTolerance_Is320Seconds() {
        assertEquals(320 * 1000L, I2PTunnelIRCClient.IRC_LATENCY_TOLERANCE_MS);
    }

    /**
     * The tolerance has to outlast both router-wide streaming bounds it
     * replaces, or raising the IRC one would not change the outcome.
     */
    @Test
    public void testLatencyTolerance_OutlastsTheStreamingDefaults() {
        assertTrue(I2PTunnelIRCClient.IRC_LATENCY_TOLERANCE_MS > 300000L);
        assertTrue(I2PTunnelIRCClient.IRC_LATENCY_TOLERANCE_MS > 90000L);
    }

    /**
     * The keys are written out in the tunnel rather than imported from the
     * streaming module, so a rename there has to fail here.
     */
    @Test
    public void testOptionKeys_MatchTheStreamingModule() {
        assertEquals("i2p.streaming.stallGiveupMs",
                     I2PTunnelIRCClient.PROP_STALL_GIVEUP_MS);
        assertEquals("i2p.streaming.inactivityTimeout",
                     I2PTunnelIRCClient.PROP_INACTIVITY_TIMEOUT);
    }

    /**
     * An IRC tunnel with nothing configured gets both bounds raised.
     */
    @Test
    public void testAppliesBothBoundsWhenUnset() {
        Properties opts = new Properties();
        I2PTunnelIRCClient.setLatencyTolerantOptions(opts);
        String giveup = I2PTunnelIRCClient.PROP_STALL_GIVEUP_MS;
        String silence = I2PTunnelIRCClient.PROP_INACTIVITY_TIMEOUT;
        assertEquals("320000", opts.getProperty(giveup));
        assertEquals("320000", opts.getProperty(silence));
    }

    /**
     * A stall give-up the operator set wins outright, and the other bound
     * is still raised.
     */
    @Test
    public void testKeepsConfiguredStallGiveup() {
        Properties opts = new Properties();
        String giveup = I2PTunnelIRCClient.PROP_STALL_GIVEUP_MS;
        String silence = I2PTunnelIRCClient.PROP_INACTIVITY_TIMEOUT;
        opts.setProperty(giveup, "60000");
        I2PTunnelIRCClient.setLatencyTolerantOptions(opts);
        assertEquals("60000", opts.getProperty(giveup));
        assertEquals("320000", opts.getProperty(silence));
    }

    /**
     * An inactivity window the operator set wins outright, and the other
     * bound is still raised.
     */
    @Test
    public void testKeepsConfiguredInactivityTimeout() {
        Properties opts = new Properties();
        String giveup = I2PTunnelIRCClient.PROP_STALL_GIVEUP_MS;
        String silence = I2PTunnelIRCClient.PROP_INACTIVITY_TIMEOUT;
        opts.setProperty(silence, "600000");
        I2PTunnelIRCClient.setLatencyTolerantOptions(opts);
        assertEquals("600000", opts.getProperty(silence));
        assertEquals("320000", opts.getProperty(giveup));
    }

    /**
     * A zero or negative operator value is left alone too: it is a request
     * to disarm the bound, not a missing setting.
     */
    @Test
    public void testKeepsDisablingValues() {
        Properties opts = new Properties();
        String giveup = I2PTunnelIRCClient.PROP_STALL_GIVEUP_MS;
        String silence = I2PTunnelIRCClient.PROP_INACTIVITY_TIMEOUT;
        opts.setProperty(giveup, "0");
        opts.setProperty(silence, "-1");
        I2PTunnelIRCClient.setLatencyTolerantOptions(opts);
        assertEquals("0", opts.getProperty(giveup));
        assertEquals("-1", opts.getProperty(silence));
    }

    /**
     * Only the two bounds are written; the options object is otherwise
     * untouched.
     */
    @Test
    public void testLeavesUnrelatedOptionsAlone() {
        Properties opts = new Properties();
        opts.setProperty(I2PTunnelIRCClient.PROP_DCC, "true");
        I2PTunnelIRCClient.setLatencyTolerantOptions(opts);
        assertEquals("true", opts.getProperty(I2PTunnelIRCClient.PROP_DCC));
        assertEquals(3, opts.size());
    }
}
