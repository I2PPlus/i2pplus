package net.i2p.router.tunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Tests for DummyValidator.
 *  No I2P context needed.
 */
public class DummyValidatorTest {

    @Test
    public void testSingleton() {
        DummyValidator v1 = DummyValidator.getInstance();
        DummyValidator v2 = DummyValidator.getInstance();
        assertSame(v1, v2);
    }

    @Test
    public void testAlwaysAccepts() {
        DummyValidator v = DummyValidator.getInstance();
        byte[] iv = new byte[16];
        byte[] payload = new byte[1024];
        assertTrue(v.receiveIV(iv, 0, payload, 0));
    }

    @Test
    public void testAcceptsZeroIV() {
        DummyValidator v = DummyValidator.getInstance();
        byte[] iv = new byte[16];
        byte[] payload = new byte[512];
        assertTrue(v.receiveIV(iv, 0, payload, 0));
    }

    @Test
    public void testAcceptsRandomIV() {
        DummyValidator v = DummyValidator.getInstance();
        byte[] iv = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] payload = new byte[256];
        assertTrue(v.receiveIV(iv, 0, payload, 0));
    }

    @Test
    public void testAcceptsRepeatedly() {
        DummyValidator v = DummyValidator.getInstance();
        byte[] iv = new byte[16];
        byte[] payload = new byte[512];
        for (int i = 0; i < 100; i++) {
            assertTrue(v.receiveIV(iv, 0, payload, 0));
        }
    }
}
