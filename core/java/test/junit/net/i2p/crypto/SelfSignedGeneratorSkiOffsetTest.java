package net.i2p.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;

import org.junit.Test;

/**
 * The DER header skip that decides which bytes the SKI/AKI is hashed over.
 * A long-form DER length octet's low 7 bits count the <em>following</em> length
 * octets, but the code added the 0x80 flag bit itself - 128 - so every DSA and
 * RSA certificate hashed a window starting 127 bytes into the key encoding.
 *
 * @since 0.9.72+
 */
public class SelfSignedGeneratorSkiOffsetTest {

    /** Short-form length: nothing to skip beyond the one header octet. */
    @Test
    public void testShortFormLength() {
        // 0x30 SEQUENCE, 0x2A = 42 bytes, short form
        assertEquals(3, SelfSignedGenerator.skiOffset(new byte[] {0x30, 0x2A, 0x30, 0x05, 0x06}));
        // boundary: 127 is still short form
        assertEquals(3, SelfSignedGenerator.skiOffset(new byte[] {0x30, (byte) 0x7F, 0x30, 0x05, 0x06}));
    }

    /** Long form with one following length octet. */
    @Test
    public void testLongFormOneLengthOctet() {
        // 0x81 = one following octet (0xAF = 175)
        byte[] der = {0x30, (byte) 0x81, (byte) 0xAF, 0x30, 0x05, 0x06};
        assertEquals(4, SelfSignedGenerator.skiOffset(der));
    }

    /** Long form with two following length octets. */
    @Test
    public void testLongFormTwoLengthOctets() {
        // 0x82 = two following octets (0x0126 = 294)
        byte[] der = {0x30, (byte) 0x82, 0x01, 0x26, 0x30, 0x05, 0x06};
        assertEquals(5, SelfSignedGenerator.skiOffset(der));
    }

    /**
     * A real RSA SPKI is long-form with two length octets. Before the fix the
     * offset was 131, which for a 294-byte encoding hashed the wrong 163 bytes.
     *
     * @throws Exception on RSA key pair generation failure
     */
    @Test
    public void testRealRsaKeyIsLongFormAndStaysInTheHeader() throws Exception {
        byte[] der = encoded("RSA", 2048);
        assertEquals("an RSA 2048 SPKI must be long form for this test to mean anything",
                     (byte) 0x82, der[1]);
        int off = SelfSignedGenerator.skiOffset(der);
        assertEquals("2 tag/first-length + 2 length octets + 1 further header octet", 5, off);
        assertTrue("offset " + off + " must stay inside the encoding", off < der.length);
    }

    /**
     * A real DSA SPKI is long form, so it went through the broken branch.
     *
     * @throws Exception on DSA key pair generation failure
     */
    @Test
    public void testRealDsaKeyIsLongFormAndStaysInTheHeader() throws Exception {
        byte[] der = encoded("DSA", 1024);
        assertTrue("a DSA SPKI must be long form for this test to mean anything",
                   (der[1] & 0x80) != 0);
        int off = SelfSignedGenerator.skiOffset(der);
        assertEquals("one past the tag, the length octets, and one further header octet",
                     2 + (der[1] & 0x7f) + 1, off);
        assertTrue("offset " + off + " must stay inside the encoding", off < der.length);
    }

    /**
     * Ed25519's 44-byte SPKI is short form, which is why it never showed the bug.
     *
     * @throws Exception on key pair generation failure other than a missing
     *                   Ed25519 algorithm, which is caught and skipped
     */
    @Test
    public void testEd25519IsShortForm() throws Exception {
        byte[] der;
        try {
            der = encoded("Ed25519", 0);
        } catch (NoSuchAlgorithmException e) {
            return;   // JDK without Ed25519 - nothing to check
        }
        assertEquals("Ed25519 SPKI must be short form", 0, der[1] & 0x80);
        assertEquals(3, SelfSignedGenerator.skiOffset(der));
    }

    /**
     * The general invariant over several real key types: the offset is always
     * just past the header, never a fixed 128-byte jump.
     *
     * @throws Exception on key pair generation failure other than a missing
     *                   algorithm, which is caught and skipped
     */
    @Test
    public void testOffsetIsAlwaysJustPastTheHeader() throws Exception {
        for (String[] spec : new String[][] {{"RSA", "2048"}, {"DSA", "1024"}, {"EC", "256"}}) {
            byte[] der;
            try {
                der = encoded(spec[0], Integer.parseInt(spec[1]));
            } catch (NoSuchAlgorithmException e) {
                continue;
            }
            int octets = (der[1] & 0x80) != 0 ? (der[1] & 0x7f) : 0;
            int expected = 2 + octets + 1;
            int off = SelfSignedGenerator.skiOffset(der);
            assertEquals(spec[0] + " offset", expected, off);
            assertTrue(spec[0] + " offset " + off + " of " + der.length + " bytes",
                       off <= 8 && off < der.length);
        }
    }

    private static byte[] encoded(String algorithm, int keysize) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance(algorithm);
        if (keysize > 0)
            gen.initialize(keysize);
        PublicKey pub = gen.generateKeyPair().getPublic();
        return pub.getEncoded();
    }
}
