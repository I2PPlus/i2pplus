package net.i2p.router.crypto.pqc;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyPair;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;

public class MLKEMTest {

    private static final EncType[] INTERNAL_TYPES = {EncType.MLKEM512_X25519_INT, EncType.MLKEM768_X25519_INT, EncType.MLKEM1024_X25519_INT};

    @Before
    public void setUp() {
        I2PAppContext.getGlobalContext();
    }

    @Test
    public void testGetKeysMLKEM512() throws GeneralSecurityException {
        testGetKeys(EncType.MLKEM512_X25519_INT);
    }

    @Test
    public void testGetKeysMLKEM768() throws GeneralSecurityException {
        testGetKeys(EncType.MLKEM768_X25519_INT);
    }

    @Test
    public void testGetKeysMLKEM1024() throws GeneralSecurityException {
        testGetKeys(EncType.MLKEM1024_X25519_INT);
    }

    private void testGetKeys(EncType type) throws GeneralSecurityException {
        KeyPair kp = MLKEM.getKeys(type);
        assertNotNull(kp);
        PublicKey pub = kp.getPublic();
        PrivateKey priv = kp.getPrivate();
        assertNotNull(pub);
        assertNotNull(priv);
        assertEquals(type, pub.getType());
        assertEquals(type, priv.getType());
        assertNotNull(pub.getData());
        assertNotNull(priv.getData());
        assertTrue(pub.getData().length > 0);
        assertTrue(priv.getData().length > 0);
    }

    @Test
    public void testGenerateKeysMLKEM512() throws GeneralSecurityException {
        testGenerateKeys(EncType.MLKEM512_X25519_INT);
    }

    @Test
    public void testGenerateKeysMLKEM768() throws GeneralSecurityException {
        testGenerateKeys(EncType.MLKEM768_X25519_INT);
    }

    @Test
    public void testGenerateKeysMLKEM1024() throws GeneralSecurityException {
        testGenerateKeys(EncType.MLKEM1024_X25519_INT);
    }

    private void testGenerateKeys(EncType type) throws GeneralSecurityException {
        byte[][] keys = MLKEM.generateKeys(type);
        assertNotNull(keys);
        assertEquals(2, keys.length);
        assertNotNull(keys[0]);
        assertNotNull(keys[1]);
        assertTrue(keys[0].length > 0);
        assertTrue(keys[1].length > 0);
    }

    @Test
    public void testEncapDecapMLKEM512() throws GeneralSecurityException {
        testRoundTrip(EncType.MLKEM512_X25519_INT);
    }

    @Test
    public void testEncapDecapMLKEM768() throws GeneralSecurityException {
        testRoundTrip(EncType.MLKEM768_X25519_INT);
    }

    @Test
    public void testEncapDecapMLKEM1024() throws GeneralSecurityException {
        testRoundTrip(EncType.MLKEM1024_X25519_INT);
    }

    private void testRoundTrip(EncType type) throws GeneralSecurityException {
        byte[][] keys = MLKEM.generateKeys(type);
        byte[] pubKey = keys[0];
        byte[] privKey = keys[1];

        byte[][] bob = MLKEM.encaps(type, pubKey);
        assertNotNull(bob);
        assertEquals(2, bob.length);
        byte[] ciphertext = bob[0];
        byte[] sharedBob = bob[1];
        assertNotNull(ciphertext);
        assertNotNull(sharedBob);
        assertEquals(32, sharedBob.length);

        byte[] sharedAlice = MLKEM.decaps(type, ciphertext, privKey);
        assertNotNull(sharedAlice);
        assertEquals(32, sharedAlice.length);
        assertArrayEquals(sharedBob, sharedAlice);
    }

    @Test
    public void testWrongEncTypeThrows() {
        try {
            MLKEM.getKeys(EncType.ECIES_X25519);
            fail("Expected GeneralSecurityException for wrong EncType");
        } catch (GeneralSecurityException e) {
            // expected
        }
    }

    @Test
    public void testEncapWrongTypeThrows() throws GeneralSecurityException {
        byte[][] keys = MLKEM.generateKeys(EncType.MLKEM512_X25519_INT);
        try {
            MLKEM.encaps(EncType.ECIES_X25519, keys[0]);
            fail("Expected GeneralSecurityException for wrong EncType");
        } catch (GeneralSecurityException e) {
            // expected
        }
    }

    @Test
    public void testDecapWrongTypeThrows() throws GeneralSecurityException {
        byte[][] keys = MLKEM.generateKeys(EncType.MLKEM512_X25519_INT);
        byte[][] bob = MLKEM.encaps(EncType.MLKEM512_X25519_INT, keys[0]);
        try {
            MLKEM.decaps(EncType.ECIES_X25519, bob[0], keys[1]);
            fail("Expected GeneralSecurityException for wrong EncType");
        } catch (GeneralSecurityException e) {
            // expected
        }
    }

    @Test
    public void testMultipleRoundTrips() throws GeneralSecurityException {
        for (EncType type : INTERNAL_TYPES) {
            byte[][] keys = MLKEM.generateKeys(type);
            for (int i = 0; i < 5; i++) {
                byte[][] bob = MLKEM.encaps(type, keys[0]);
                byte[] sharedAlice = MLKEM.decaps(type, bob[0], keys[1]);
                assertTrue("Shared key mismatch on run " + i + " for " + type, DataHelper.eq(bob[1], sharedAlice));
            }
        }
    }

    /**
     * FIPS 203 decapsulation uses implicit rejection, so a tampered ciphertext
     * must not raise: it yields a different 32 byte key instead. Callers that
     * need authentication must compare the key, not catch an exception.
     */
    @Test
    public void testDecapTamperedCiphertextDoesNotThrow() throws GeneralSecurityException {
        byte[][] keys = MLKEM.generateKeys(EncType.MLKEM768_X25519_INT);
        byte[][] bob = MLKEM.encaps(EncType.MLKEM768_X25519_INT, keys[0]);
        byte[] tampered = bob[0].clone();
        tampered[0] ^= 0x01;
        byte[] shared = MLKEM.decaps(EncType.MLKEM768_X25519_INT, tampered, keys[1]);
        assertNotNull(shared);
        assertEquals(32, shared.length);
        assertFalse("implicit rejection must not return the encapsulated secret",
                    DataHelper.eq(bob[1], shared));
    }

    /**
     * Implicit rejection is deterministic: the same rejected ciphertext against
     * the same decapsulation key always yields the same pseudorandom key, so a
     * peer that retries cannot learn anything new from the failure.
     */
    @Test
    public void testImplicitRejectionIsDeterministic() throws GeneralSecurityException {
        byte[][] keys = MLKEM.generateKeys(EncType.MLKEM768_X25519_INT);
        byte[][] bob = MLKEM.encaps(EncType.MLKEM768_X25519_INT, keys[0]);
        byte[] tampered = bob[0].clone();
        tampered[3] ^= 0x40;
        byte[] first = MLKEM.decaps(EncType.MLKEM768_X25519_INT, tampered, keys[1]);
        byte[] second = MLKEM.decaps(EncType.MLKEM768_X25519_INT, tampered, keys[1]);
        assertArrayEquals(first, second);
    }

    /**
     * Every bit of the ciphertext feeds the rejection key, so two different
     * corruptions of the same ciphertext are rejected to different values.
     */
    @Test
    public void testImplicitRejectionDependsOnCiphertext() throws GeneralSecurityException {
        byte[][] keys = MLKEM.generateKeys(EncType.MLKEM1024_X25519_INT);
        byte[][] bob = MLKEM.encaps(EncType.MLKEM1024_X25519_INT, keys[0]);
        byte[] a = bob[0].clone();
        byte[] b = bob[0].clone();
        a[1] ^= 0x02;
        b[1] ^= 0x04;
        byte[] sharedA = MLKEM.decaps(EncType.MLKEM1024_X25519_INT, a, keys[1]);
        byte[] sharedB = MLKEM.decaps(EncType.MLKEM1024_X25519_INT, b, keys[1]);
        assertFalse(DataHelper.eq(sharedA, sharedB));
    }

    /**
     * A ciphertext from a different key pair is also rejected silently rather
     * than raising, so a peer cannot distinguish a wrong key from a bad
     * ciphertext by the exception it gets back.
     */
    @Test
    public void testDecapWrongKeyDoesNotThrow() throws GeneralSecurityException {
        byte[][] bob = MLKEM.generateKeys(EncType.MLKEM512_X25519_INT);
        byte[][] other = MLKEM.generateKeys(EncType.MLKEM512_X25519_INT);
        byte[][] shared = MLKEM.encaps(EncType.MLKEM512_X25519_INT, bob[0]);
        byte[] result = MLKEM.decaps(EncType.MLKEM512_X25519_INT, shared[0], other[1]);
        assertNotNull(result);
        assertEquals(32, result.length);
        assertFalse(DataHelper.eq(shared[1], result));
    }

    @Test
    public void testKeyFactoryMLKEM512() {
        testKeyFactory(MLKEM.MLKEM512KeyFactory, EncType.MLKEM512_X25519_INT);
    }

    @Test
    public void testKeyFactoryMLKEM768() {
        testKeyFactory(MLKEM.MLKEM768KeyFactory, EncType.MLKEM768_X25519_INT);
    }

    @Test
    public void testKeyFactoryMLKEM1024() {
        testKeyFactory(MLKEM.MLKEM1024KeyFactory, EncType.MLKEM1024_X25519_INT);
    }

    private void testKeyFactory(net.i2p.crypto.KeyFactory factory, EncType type) {
        KeyPair kp = factory.getKeys();
        assertNotNull(kp);
        assertEquals(type, kp.getPublic().getType());
        assertEquals(type, kp.getPrivate().getType());
    }
}
