package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The pacing of the next-session-key collision warning and the shape of its
 * key comparison. A peer retransmits an unadopted key on every message, so an
 * unthrottled warning fires at message rate, and the stored key and the
 * stored key with data are usually the same key, so printing both twice reads
 * as two facts when there is one.
 *
 * @since 0.9.72+
 */
public class RatchetKeyMismatchTest {

    /** Deterministic key bytes, so the prefixes are knowable. */
    private static final byte[] DATA_A = keyData((byte) 0);

    /** A second deterministic key. */
    private static final byte[] DATA_B = keyData((byte) 1);

    /**
     * A fresh test instance per method, as JUnit builds one per test; the
     * class carries only the fixed key bytes.
     *
     * @since 0.9.72+
     */
    public RatchetKeyMismatchTest() {}

    /**
     * Deterministic key bytes.
     *
     * @param offset first byte value
     * @return 32 bytes
     */
    private static byte[] keyData(byte offset) {
        byte[] data = new byte[32];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i + offset);
        }
        return data;
    }

    /**
     * The first mismatch warns.
     */
    @Test
    public void testFirstMismatchWarns() {
        assertTrue(RatchetSKM.shouldWarnKeyMismatch(1000L, 0L));
    }

    /**
     * A retransmit inside the silence window does not warn again.
     */
    @Test
    public void testRepeatInsideTheWindowStaysQuiet() {
        long now = 2_000_000L;
        assertFalse(RatchetSKM.shouldWarnKeyMismatch(now, now - 60_000L));
        assertFalse(RatchetSKM.shouldWarnKeyMismatch(now, now - RatchetSKM.KEY_MISMATCH_WARN_MS));
    }

    /**
     * Past the window the warning fires again, so a persistent peer is not
     * silenced forever.
     */
    @Test
    public void testWarnsAgainAfterTheWindow() {
        long now = 2_000_000L;
        assertTrue(RatchetSKM.shouldWarnKeyMismatch(now, now - RatchetSKM.KEY_MISMATCH_WARN_MS - 1));
    }

    /**
     * When the stored key and the stored key with data are the same key, the
     * comparison says it once.
     */
    @Test
    public void testComparisonDedupesTheStoredKey() {
        NextSessionKey received = new NextSessionKey(DATA_A, 0, false, false);
        NextSessionKey stored = new NextSessionKey(DATA_B, 0, false, false);
        String s = NextSessionKey.keyComparison(received, stored, stored);
        assertTrue(s, s.contains(" * Stored: "));
        assertFalse("stored key printed twice: " + s, s.contains("Stored with data"));
    }

    /**
     * When they differ, both segments appear.
     */
    @Test
    public void testComparisonShowsDistinctStoredKeys() {
        NextSessionKey received = new NextSessionKey(DATA_A, 0, false, false);
        NextSessionKey stored = new NextSessionKey(DATA_A, 0, false, false);
        NextSessionKey storedWithData = new NextSessionKey(DATA_B, 0, false, false);
        String s = NextSessionKey.keyComparison(received, stored, storedWithData);
        assertTrue(s, s.contains(" * Stored: "));
        assertTrue(s, s.contains(" * Stored with data: "));
    }
}
