package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Tests for the pure decision helpers extracted from
 * {@link ConnectionManager#receiveConnection(net.i2p.client.streaming.Packet)}:
 * the reject-response payload selector ({@link ConnectionManager#limitResponse})
 * and the NACK-vs-local-hash check ({@link ConnectionManager#synNacksMatch}).
 *
 * <p>Both gates sit on the inbound flood/refusal path, where a wrong decision
 * means either an unbounded resource spend answering rejected peers or a reset
 * sent to a peer that merely guessed a stale hash. Pinning them here keeps the
 * word-split comparison, the 443-is-always-RESET rule, and the Retry-After
 * substitution from regressing during future refactors.
 *
 * @since 0.9.71
 */
public class ConnectionManagerRejectResponseTest {

    @Test
    public void testNacksNullIsFine() {
        assertTrue(ConnectionManager.synNacksMatch(null, hash()));
    }

    @Test
    public void testNacksWrongLengthIsFine() {
        assertTrue(ConnectionManager.synNacksMatch(new long[] {1, 2, 3}, hash()));
    }

    @Test
    public void testAllEightWordsMatch() {
        Hash h = hash();
        byte[] data = h.getData();
        long[] nacks = new long[8];
        for (int i = 0; i < 8; i++) {nacks[i] = DataHelper.fromLong(data, i << 2, 4);}
        assertTrue(ConnectionManager.synNacksMatch(nacks, h));
    }

    @Test
    public void testWordMismatchDetected() {
        Hash h = hash();
        byte[] data = h.getData();
        long[] nacks = new long[8];
        for (int i = 0; i < 8; i++) {nacks[i] = DataHelper.fromLong(data, i << 2, 4);}
        nacks[3] ^= 0xFFL;
        assertFalse(ConnectionManager.synNacksMatch(nacks, h));
    }

    @Test
    public void testResetActionsProduceNoPayload() {
        assertNull(ConnectionManager.limitResponse("reset", 120, false));
        assertNull(ConnectionManager.limitResponse("", 120, false));
        assertNull(ConnectionManager.limitResponse(null, 120, false));
    }

    @Test
    public void testPort443AlwaysReset() {
        assertNull("443 must never get a text payload",
                   ConnectionManager.limitResponse("http", 120, true));
        assertNull(ConnectionManager.limitResponse("SOME TEXT", 120, true));
    }

    @Test
    public void testHttpCarriesRetryAfterSeconds() {
        String r = ConnectionManager.limitResponse("http", 120, false);
        assertNotNull(r);
        assertTrue("should carry the caller's seconds: " + r, r.contains("Retry-After: 120"));
    }

    @Test
    public void testHttpOmitsRetryAfterAtZero() {
        String r = ConnectionManager.limitResponse("http", 0, false);
        assertNotNull(r);
        assertFalse("header should be dropped when 0: " + r, r.contains("Retry-After:"));
    }

    @Test
    public void testCustomActionEscapeSequencesNormalized() {
        assertEquals("a\rb\nc", ConnectionManager.limitResponse("a\\rb\\nc", 120, false));
    }

    private static Hash hash() {
        byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) {b[i] = (byte) (i * 7 + 1);}
        return new Hash(b);
    }
}