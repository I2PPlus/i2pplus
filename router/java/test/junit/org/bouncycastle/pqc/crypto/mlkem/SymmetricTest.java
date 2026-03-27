package org.bouncycastle.pqc.crypto.mlkem;

import static org.junit.Assert.*;

import org.junit.Test;

public class SymmetricTest {

    @Test
    public void testHashHDeterministic() {
        Symmetric sym = new Symmetric.ShakeSymmetric();
        byte[] input = new byte[32];
        for (int i = 0; i < 32; i++) input[i] = (byte) i;
        byte[] out1 = new byte[32];
        byte[] out2 = new byte[32];
        sym.hash_h(out1, input, 0);
        sym.hash_h(out2, input, 0);
        assertArrayEquals(out1, out2);
    }

    @Test
    public void testHashHOutputSize() {
        Symmetric sym = new Symmetric.ShakeSymmetric();
        byte[] input = new byte[64];
        byte[] out = new byte[32];
        sym.hash_h(out, input, 0);
        assertNotNull(out);
        assertEquals(32, out.length);
    }

    @Test
    public void testHashGDeterministic() {
        Symmetric sym = new Symmetric.ShakeSymmetric();
        byte[] input = new byte[64];
        for (int i = 0; i < 64; i++) input[i] = (byte) (i + 10);
        byte[] out1 = new byte[64];
        byte[] out2 = new byte[64];
        sym.hash_g(out1, input);
        sym.hash_g(out2, input);
        assertArrayEquals(out1, out2);
    }

    @Test
    public void testXofAbsorbSqueeze() {
        Symmetric sym = new Symmetric.ShakeSymmetric();
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) i;
        sym.xofAbsorb(seed, (byte) 0, (byte) 1);
        byte[] out = new byte[sym.xofBlockBytes];
        sym.xofSqueezeBlocks(out, 0, out.length);
        assertNotNull(out);
        assertEquals(sym.xofBlockBytes, out.length);
    }

    @Test
    public void testPrfDeterministic() {
        Symmetric sym = new Symmetric.ShakeSymmetric();
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        byte[] out1 = new byte[64];
        byte[] out2 = new byte[64];
        sym.prf(out1, key, (byte) 0);
        sym.prf(out2, key, (byte) 0);
        assertArrayEquals(out1, out2);
    }

    @Test
    public void testPrfDifferentNonceDifferentOutput() {
        Symmetric sym = new Symmetric.ShakeSymmetric();
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        byte[] out1 = new byte[64];
        byte[] out2 = new byte[64];
        sym.prf(out1, key, (byte) 0);
        sym.prf(out2, key, (byte) 1);
        assertFalse("different nonces should produce different output",
                    java.util.Arrays.equals(out1, out2));
    }

    @Test
    public void testKdfDeterministic() {
        Symmetric sym = new Symmetric.ShakeSymmetric();
        byte[] input = new byte[64];
        for (int i = 0; i < 64; i++) input[i] = (byte) (i * 3);
        byte[] out1 = new byte[32];
        byte[] out2 = new byte[32];
        sym.kdf(out1, input);
        sym.kdf(out2, input);
        assertArrayEquals(out1, out2);
    }

    @Test
    public void testShakeSymmetricBlockBytes() {
        Symmetric sym = new Symmetric.ShakeSymmetric();
        assertEquals(168, sym.xofBlockBytes);
    }
}
