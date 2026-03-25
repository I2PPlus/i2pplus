package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import org.junit.Test;

public class SSU2UtilTest {

    @Test
    public void testTerminationCodeToStringKnown() {
        assertEquals("Unspecified reason", SSU2Util.terminationCodeToString(0));
        assertEquals("Termination requested", SSU2Util.terminationCodeToString(1));
        assertEquals("Timeout occurred", SSU2Util.terminationCodeToString(2));
        assertEquals("Shutdown in progress", SSU2Util.terminationCodeToString(3));
        assertEquals("AEAD verification failure", SSU2Util.terminationCodeToString(4));
        assertEquals("Options mismatch", SSU2Util.terminationCodeToString(5));
    }

    @Test
    public void testTerminationCodeToStringExtended() {
        assertEquals("Signature type error", SSU2Util.terminationCodeToString(6));
        assertEquals("Clock skew too large", SSU2Util.terminationCodeToString(7));
        assertEquals("Padding error", SSU2Util.terminationCodeToString(8));
        assertEquals("Framing error", SSU2Util.terminationCodeToString(9));
        assertEquals("Payload error", SSU2Util.terminationCodeToString(10));
    }

    @Test
    public void testTerminationCodeToStringMore() {
        assertEquals("Message 1 error", SSU2Util.terminationCodeToString(11));
        assertEquals("Message 2 error", SSU2Util.terminationCodeToString(12));
        assertEquals("Message 3 error", SSU2Util.terminationCodeToString(13));
        assertEquals("Frame timeout", SSU2Util.terminationCodeToString(14));
        assertEquals("Signature verification failed", SSU2Util.terminationCodeToString(15));
        assertEquals("Session mismatch", SSU2Util.terminationCodeToString(16));
        assertEquals("Banned", SSU2Util.terminationCodeToString(17));
        assertEquals("Token error", SSU2Util.terminationCodeToString(18));
        assertEquals("Resource limits exceeded", SSU2Util.terminationCodeToString(19));
        assertEquals("Protocol version mismatch", SSU2Util.terminationCodeToString(20));
        assertEquals("Network ID mismatch", SSU2Util.terminationCodeToString(21));
        assertEquals("Session replaced", SSU2Util.terminationCodeToString(22));
    }

    @Test
    public void testTerminationCodeUnknown() {
        String result = SSU2Util.terminationCodeToString(99);
        assertNotNull(result);
        assertTrue(result.contains("Unknown"));
    }

    @Test
    public void testTerminationCodeNegative() {
        String result = SSU2Util.terminationCodeToString(-1);
        assertNotNull(result);
        assertTrue(result.contains("Unknown"));
    }

    @Test
    public void testConstants() {
        assertEquals(2, SSU2Util.PROTOCOL_VERSION);
        assertEquals(16, SSU2Util.SHORT_HEADER_SIZE);
        assertEquals(32, SSU2Util.LONG_HEADER_SIZE);
        assertEquals(32, SSU2Util.PADDING_MAX);
    }
}
