package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.crypto.HKDF;
import net.i2p.data.SessionKey;

import org.junit.Test;

/**
 * The debug toString()s of the ratchet key holders report the key's size,
 * never its bytes. {@link RatchetTagSet#toString()} is appended to an INFO
 * line when a tag misses its set - a path any peer can trigger - and it used
 * to print the ratchet root key and every consumed session key in full
 * base64. {@link SessionKeyAndNonce}, {@link SingleTagSet} and
 * {@link RatchetEntry} sat on the same path. The tags, the public key and
 * the counters stay: they are what correlates a session.
 *
 * @since 0.9.73+
 */
public class RatchetKeySizeToStringTest {

    /** Deterministic root key bytes, so the leaked string is knowable. */
    private static final byte[] ROOT = keyData((byte) 0);

    /** Deterministic session key bytes. */
    private static final byte[] DATA = keyData((byte) 1);

    /**
     * A fresh test instance per method, as JUnit builds one per test; the
     * class carries only the fixed key bytes.
     *
     * @since 0.9.73+
     */
    public RatchetKeySizeToStringTest() {}

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
     * A session key and nonce built for an outbound Existing Session.
     *
     * @return the key
     */
    private static SessionKeyAndNonce keyAndNonce() {
        return new SessionKeyAndNonce(DATA, 7);
    }

    /**
     * The session key and nonce reports its size and nonce, not the key.
     */
    @Test
    public void testSessionKeyAndNonceReportsSize() {
        SessionKeyAndNonce key = keyAndNonce();
        String s = key.toString();
        assertTrue(s, s.contains("Size: 32 bytes"));
        assertTrue(s, s.contains("nonce: 7"));
        assertFalse("key bytes leaked: " + s, s.contains(key.toBase64()));
    }

    /**
     * The tag set dump - logged at INFO on a peer-triggered tag miss - carries
     * no root key and no session key bytes.
     */
    @Test
    public void testTagSetDumpCarriesNoKeyBytes() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        HKDF hkdf = new HKDF(ctx);
        RatchetTagSet ts = new RatchetTagSet(hkdf, null, null, new SessionKey(ROOT),
                                             new SessionKey(DATA), ctx.clock().now(),
                                             0, 0, 8, 16);
        String s = ts.toString();
        SessionKey root = new SessionKey(ROOT);
        assertFalse("root key leaked: " + s, s.contains(root.toBase64()));
        assertTrue(s, s.contains("Root Key: 32 bytes"));
    }

    /**
     * The single-use tag set reports the key size beside its tag.
     */
    @Test
    public void testSingleTagSetReportsSize() {
        SessionTagListener lsnr = new SessionTagListener() {
            @Override
            public boolean addTag(RatchetSessionTag tag, RatchetTagSet ts) {
                return true;
            }

            @Override
            public void expireTag(RatchetSessionTag tag, RatchetTagSet ts) {}
        };
        RatchetSessionTag tag = new RatchetSessionTag(42L);
        SingleTagSet sts = new SingleTagSet(lsnr, new SessionKey(DATA), tag,
                                            System.currentTimeMillis(), 60000L);
        String s = sts.toString();
        SessionKey key = new SessionKey(DATA);
        assertTrue(s, s.contains("Size: 32 bytes"));
        assertFalse("key bytes leaked: " + s, s.contains(key.toBase64()));
    }

    /**
     * The entry dump rides on the session key and nonce's own size form.
     */
    @Test
    public void testRatchetEntryCarriesNoKeyBytes() {
        RatchetEntry entry = new RatchetEntry(new RatchetSessionTag(7L), keyAndNonce(), 3, 1);
        String s = entry.toString();
        assertFalse("key bytes leaked: " + s, s.contains(keyAndNonce().toBase64()));
        assertTrue(s, s.contains("Size: 32 bytes"));
    }

    /**
     * The labelled form puts the caller's label on the key's own line, and
     * the default form keeps the class name. The key is a prefix, so a
     * comparison of three keys costs three short lines, not three lines of
     * 44-character base64.
     */
    @Test
    public void testNextSessionKeyLabel() {
        NextSessionKey key = new NextSessionKey(DATA, 3, false, false);
        String labelled = key.toString("Stored");
        assertTrue(labelled, labelled.startsWith("\n* Stored: "));
        String plain = key.toString();
        assertTrue(plain, plain.startsWith("\n* NextSessionKey: "));
        assertTrue(labelled, labelled.contains("ECIES_X25519"));
        assertTrue(labelled, labelled.contains(key.toBase64().substring(0, 8)));
        assertFalse("whole key printed: " + labelled, labelled.contains(key.toBase64()));
        assertEquals("one continuation line", 1, labelled.chars().filter(c -> c == '\n').count());
        assertTrue("line is " + labelled.length() + " chars", labelled.length() <= 100);
    }

    /**
     * A request or ack carries no key data, and its line says so rather than
     * printing an empty field.
     */
    @Test
    public void testNextSessionKeyWithoutData() {
        NextSessionKey key = new NextSessionKey(3, true, false);
        String labelled = key.toString("Received");
        assertTrue(labelled, labelled.contains("no key data"));
        assertTrue(labelled, labelled.contains("Reverse? true"));
    }

    /**
     * The three-key comparison is one continuation line of prefixes, so the
     * warning that needs it costs two emitted lines, not four, and it names the
     * ID once.
     */
    @Test
    public void testKeyComparisonIsOneLine() {
        NextSessionKey received = new NextSessionKey(DATA, 3, true, false);
        NextSessionKey stored = new NextSessionKey(ROOT, 3, true, false);
        NextSessionKey storedWithData = new NextSessionKey(keyData((byte) 2), 3, true, false);
        String s = NextSessionKey.keyComparison(received, stored, storedWithData);
        assertEquals("one continuation line", 1, s.chars().filter(c -> c == '\n').count());
        assertTrue(s, s.startsWith("\n* Received: ECIES_X25519 "));
        assertEquals("the ID is named once", 1, s.split("ID: ").length - 1);
        assertTrue(s, s.contains("Stored: " + stored.toBase64().substring(0, 8)));
        assertTrue(s, s.contains("Stored with data: " + storedWithData.toBase64().substring(0, 8)));
        assertFalse("whole key printed: " + s, s.contains(received.toBase64()));
        assertTrue("line is " + s.length() + " chars", s.length() <= 100);
    }
}
