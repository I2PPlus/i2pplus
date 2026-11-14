package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.client.streaming.I2PSocketOptions;

/**
 * Pins the connect budget a peer dial is given. Snark asked streaming for a 300 s connect
 * timeout while streaming caps the connect window at {@code i2p.streaming.maxConnectTimeout},
 * whose default is 75 s, and snark never raised that cap - so the 300 s was decorative and an
 * unreachable peer was given 75 s. One of the two numbers had to change; this pins that they
 * now agree.
 *
 * @since 0.9.72+
 */
public class ConnectBudgetTest {

    /** The connect window streaming falls back to when no cap is set, in ms. */
    private static final long STREAMING_DEFAULT_CAP = 75000L;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * values under test are constants and carry no state.
     *
     * @since 0.9.72+
     */
    public ConnectBudgetTest() {}

    /**
     * The cap is at least the timeout asked for. Below it, streaming clamps the window and
     * the requested timeout is never reached.
     */
    @Test
    public void testCapReachesTheRequestedTimeout() {
        assertTrue(I2PSnarkUtil.MAX_CONNECT_TIMEOUT_MS >= I2PSnarkUtil.CONNECT_TIMEOUT_MS);
    }

    /**
     * The cap is not left far above the timeout either, so a dial that never connects stops
     * occupying its thread at a known bound rather than at some larger accidental one.
     */
    @Test
    public void testCapIsNotLoose() {
        assertEquals(I2PSnarkUtil.CONNECT_TIMEOUT_MS, I2PSnarkUtil.MAX_CONNECT_TIMEOUT_MS);
    }

    /**
     * The budget is the five minutes the code intends, and it is several times the
     * streaming default cap that used to silently replace it.
     */
    @Test
    public void testBudgetIsFiveMinutes() {
        assertEquals(300000L, I2PSnarkUtil.CONNECT_TIMEOUT_MS);
        assertTrue(I2PSnarkUtil.CONNECT_TIMEOUT_MS >= 4 * STREAMING_DEFAULT_CAP);
    }

    /**
     * The keys written into the session options are the ones streaming actually reads. A
     * near-miss key would be accepted by I2CP and ignored by streaming, which is how the
     * cap went unset in the first place.
     */
    @Test
    public void testPropertyKeysAreTheStreamingOnes() {
        assertEquals("i2p.streaming.maxConnectTimeout",
                     I2PSocketOptions.PROP_MAX_CONNECT_TIMEOUT);
        assertEquals("i2p.streaming.connectTimeout", I2PSocketOptions.PROP_CONNECT_TIMEOUT);
    }
}
