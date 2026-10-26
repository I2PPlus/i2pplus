package net.i2p.client.impl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The send-queue timeout parsing in I2PSessionImpl.
 *
 * <p>The timeout bounds how long a send parks when the write queue is full. A
 * caller that holds a lock across the send blocks everyone waiting on that lock
 * for the whole park, so the bound has to be settable, and 0 has to mean "do
 * not wait" rather than falling back to the default.
 *
 * @since 0.9.72+
 */
public class SendQueueTimeoutTest {

    /** The pre-existing fixed wait, kept as the default. */
    private static final long DEFAULT = 10_000L;

    @Test
    public void testDefault() {
        assertEquals(DEFAULT, I2PSessionImpl.getSendQueueTimeout(null, null));
        assertEquals(DEFAULT, I2PSessionImpl.getSendQueueTimeout(null, ""));
    }

    @Test
    public void testSessionValueWins() {
        assertEquals(500L, I2PSessionImpl.getSendQueueTimeout("500", "750"));
        assertEquals(500L, I2PSessionImpl.getSendQueueTimeout(" 500 ", null));
    }

    @Test
    public void testContextFallback() {
        assertEquals(750L, I2PSessionImpl.getSendQueueTimeout(null, "750"));
    }

    /** Zero is a setting, not a missing value: it means fail at once. */
    @Test
    public void testZeroMeansNoWait() {
        assertEquals(0L, I2PSessionImpl.getSendQueueTimeout("0", null));
        assertEquals(0L, I2PSessionImpl.getSendQueueTimeout(null, "0"));
    }

    @Test
    public void testInvalidValuesFallBack() {
        assertEquals(DEFAULT, I2PSessionImpl.getSendQueueTimeout("-1", null));
        assertEquals(DEFAULT, I2PSessionImpl.getSendQueueTimeout("abc", null));
        assertEquals(DEFAULT, I2PSessionImpl.getSendQueueTimeout("", "abc"));
        assertEquals(DEFAULT, I2PSessionImpl.getSendQueueTimeout("1.5", null));
    }

    @Test
    public void testLargeValue() {
        assertEquals(120_000L, I2PSessionImpl.getSendQueueTimeout("120000", null));
    }
}
