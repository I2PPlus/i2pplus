package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The inline log segment {@link RatchetSKM#keyFingerprint} builds for a
 * next-session key. The key's own toString() opens with a newline and its own
 * label, so prefixing it on a log line put "Forward key:" on one line with an
 * empty value and "Reverse key:" at the end of the forward key's line, and the
 * 44-character base64 of an X25519 key pushed the whole message past the log
 * width. The segment replaces that with a bracketed eight-character
 * fingerprint, which is what the console linkifier recognises, so the whole
 * "sending keys" message stays on one line.
 */
public class RatchetKeyFingerprintTest {

    /** A 32-byte X25519 key, the only length the type accepts. */
    private static final byte[] KEY_DATA = keyData();

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the class carries no per-test state, only the fixed key bytes
     * the segments are built from.
     *
     * @since 0.9.72+
     */
    public RatchetKeyFingerprintTest() {}

    /**
     * Deterministic key bytes, so the expected fingerprint is fixed.
     *
     * @return 32 bytes, the length EncType.ECIES_X25519 requires
     */
    private static byte[] keyData() {
        byte[] data = new byte[32];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7 + 3);
        }
        return data;
    }

    /**
     * A forward key renders as a bracketed eight-character fingerprint plus its
     * key id, with the label first.
     */
    @Test
    public void testForwardKeySegment() {
        NextSessionKey key = new NextSessionKey(KEY_DATA, 3, false, false);
        String fp = key.toBase64().substring(0, 8);
        String seg = RatchetSKM.keyFingerprint(key, "Forward");
        assertEquals("Forward: [" + fp + "] ID: 3", seg);
    }

    /**
     * The segment carries no newline, which is the whole point: the key's own
     * toString() begins with one, and that is what scattered the labels across
     * lines.
     */
    @Test
    public void testSegmentStaysOnOneLine() {
        NextSessionKey key = new NextSessionKey(KEY_DATA, 1, false, false);
        String seg = RatchetSKM.keyFingerprint(key, "Forward");
        assertFalse("segment wraps: " + seg, seg.contains("\n"));
    }

    /**
     * The full key is not in the line. It goes on the wire in the PNBlock, so
     * the log only needs to identify it, and a 44-character value there is what
     * made the message too wide to keep on one line.
     */
    @Test
    public void testFullKeyIsNotEmitted() {
        NextSessionKey key = new NextSessionKey(KEY_DATA, 1, false, false);
        String seg = RatchetSKM.keyFingerprint(key, "Forward");
        assertFalse("full base64 leaked: " + seg, seg.contains(key.toBase64()));
        assertEquals(8, bracketed(seg).length());
    }

    /**
     * A key built for an ack or a request carries no data at all, so there is
     * nothing to fingerprint; the segment says so instead of throwing.
     */
    @Test
    public void testKeyWithoutDataRendersTbd() {
        NextSessionKey key = new NextSessionKey(7, true, true);
        assertNull("a data-less key should have no base64", key.toBase64());
        assertEquals("Reverse: TBD ID: 7", RatchetSKM.keyFingerprint(key, "Reverse"));
    }

    /**
     * The direction label is whatever the caller names, so the same helper
     * serves the forward and the reverse key, and the key's own id is the
     * selector a reader greps for.
     */
    @Test
    public void testLabelAndIdCarriedThrough() {
        NextSessionKey key = new NextSessionKey(KEY_DATA, 65535, true, false);
        String seg = RatchetSKM.keyFingerprint(key, "Reverse");
        assertTrue(seg, seg.startsWith("Reverse: "));
        assertTrue(seg, seg.endsWith(" ID: 65535"));
    }

    /**
     * The composed message — set label plus both keys — stays within the house
     * log width, which is the reason the fingerprint is eight characters.
     */
    @Test
    public void testComposedMessageFitsTheLogWidth() {
        NextSessionKey fwd = new NextSessionKey(KEY_DATA, 1, false, false);
        NextSessionKey rev = new NextSessionKey(KEY_DATA, 0, true, false);
        String line = "Sending keys for Set NSR/1 * " + RatchetSKM.keyFingerprint(fwd, "Forward") +
                      " * " + RatchetSKM.keyFingerprint(rev, "Reverse");
        assertTrue("line is " + line.length() + " chars: " + line, line.length() <= 100);
        assertFalse(line, line.contains("\n"));
    }

    /**
     * Pull the bracketed fingerprint out of a segment.
     *
     * @param seg the segment built by keyFingerprint
     * @return the text between the brackets
     */
    private static String bracketed(String seg) {
        int start = seg.indexOf('[');
        int end = seg.indexOf(']');
        assertTrue("no brackets in " + seg, start > 0 && end > start);
        return seg.substring(start + 1, end);
    }
}
