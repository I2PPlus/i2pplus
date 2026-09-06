package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import net.i2p.data.DataHelper;
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

    @Test
    public void testCorrelationConstants() {
        assertArrayEquals(DataHelper.getASCII("RelayAgreementOK"), SSU2Util.RELAY_RESPONSE_PROLOGUE);
        assertEquals(6, SSU2Util.RELAY_DATA_ADDR_LEN_IPV4);
        assertEquals(18, SSU2Util.RELAY_DATA_ADDR_LEN_IPV6);
        assertEquals(8, SSU2Util.RELAY_DATA_VERSION_OFFSET);
        assertEquals(9, SSU2Util.RELAY_DATA_ADDR_LEN_OFFSET);
        assertEquals(10, SSU2Util.RELAY_DATA_PORT_OFFSET);
        assertEquals(12, SSU2Util.RELAY_DATA_IP_OFFSET);
        assertEquals(8, SSU2Util.RELAY_RESPONSE_TOKEN_LEN);
    }

    @Test
    public void testReasonConstants() {
        assertEquals(0, SSU2Util.REASON_UNSPEC);
        assertEquals(1, SSU2Util.REASON_TERMINATION);
        assertEquals(2, SSU2Util.REASON_TIMEOUT);
        assertEquals(3, SSU2Util.REASON_SHUTDOWN);
        assertEquals(4, SSU2Util.REASON_AEAD);
        assertEquals(5, SSU2Util.REASON_OPTIONS);
        assertEquals(6, SSU2Util.REASON_SIGTYPE);
        assertEquals(7, SSU2Util.REASON_SKEW);
        assertEquals(8, SSU2Util.REASON_PADDING);
        assertEquals(9, SSU2Util.REASON_FRAMING);
        assertEquals(10, SSU2Util.REASON_PAYLOAD);
        assertEquals(11, SSU2Util.REASON_MSG1);
        assertEquals(12, SSU2Util.REASON_MSG2);
        assertEquals(13, SSU2Util.REASON_MSG3);
        assertEquals(14, SSU2Util.REASON_FRAME_TIMEOUT);
        assertEquals(15, SSU2Util.REASON_SIGFAIL);
        assertEquals(16, SSU2Util.REASON_S_MISMATCH);
        assertEquals(17, SSU2Util.REASON_BANNED);
        assertEquals(18, SSU2Util.REASON_TOKEN);
        assertEquals(19, SSU2Util.REASON_LIMITS);
        assertEquals(20, SSU2Util.REASON_VERSION);
        assertEquals(21, SSU2Util.REASON_NETID);
        assertEquals(22, SSU2Util.REASON_REPLACED);
    }

    @Test
    public void testVersionConstants() {
        assertEquals(2, SSU2Util.MIN_SUPPORTED_VERSION);
        assertEquals(4, SSU2Util.MAX_SUPPORTED_VERSION);
    }

    @Test
    public void testIsSupportedVersion() {
        assertFalse(SSU2Util.isSupportedVersion(1));
        assertTrue(SSU2Util.isSupportedVersion(2));
        assertTrue(SSU2Util.isSupportedVersion(3));
        assertTrue(SSU2Util.isSupportedVersion(4));
        assertFalse(SSU2Util.isSupportedVersion(5));
        assertFalse(SSU2Util.isSupportedVersion(0));
        assertFalse(SSU2Util.isSupportedVersion(99));
    }

    @Test
    public void testRelayDataParseIPv4() {
        // addrLen byte = ip len (4) + port len (2) = 6, port at 10-11, ip at 12
        byte[] data = new byte[18];
        data[9] = (byte) 6;
        data[10] = (byte) 0x12;
        data[11] = (byte) 0x34;
        data[12] = 1;
        data[13] = 2;
        data[14] = 3;
        data[15] = 4;
        assertEquals(6, SSU2Util.getRelayDataAddrLen(data));
        assertTrue(SSU2Util.isValidRelayDataAddrLen(6));
        assertEquals(0x1234, SSU2Util.getRelayDataPort(data));
        byte[] ip = SSU2Util.getRelayDataIP(data, 6);
        assertEquals(4, ip.length);
        assertEquals(1, ip[0]);
        assertEquals(2, ip[1]);
        assertEquals(3, ip[2]);
        assertEquals(4, ip[3]);
    }

    @Test
    public void testRelayDataParseIPv6() {
        // addrLen byte = ip len (16) + port len (2) = 18
        byte[] data = new byte[30];
        data[9] = (byte) 18;
        data[10] = (byte) 0x01;
        data[11] = (byte) 0x02;
        data[12] = (byte) 0xfd;
        data[27] = (byte) 0xee;
        assertEquals(18, SSU2Util.getRelayDataAddrLen(data));
        assertTrue(SSU2Util.isValidRelayDataAddrLen(18));
        assertFalse(SSU2Util.isValidRelayDataAddrLen(7));
        assertFalse(SSU2Util.isValidRelayDataAddrLen(0));
        assertEquals(0x0102, SSU2Util.getRelayDataPort(data));
        byte[] ip = SSU2Util.getRelayDataIP(data, 18);
        assertEquals(16, ip.length);
        assertEquals((byte) 0xfd, ip[0]);
        assertEquals((byte) 0xee, ip[15]);
    }

    @Test
    public void testInvalidRelayDataAddrLen() {
        assertFalse(SSU2Util.isValidRelayDataAddrLen(5));
        assertFalse(SSU2Util.isValidRelayDataAddrLen(19));
        assertFalse(SSU2Util.isValidRelayDataAddrLen(-1));
    }

    @Test
    public void testRelayResponseToken() {
        // signed data = 12 bytes + 8 byte token
        byte[] data = new byte[20];
        data[18] = 0x34;
        data[19] = (byte) 0x12;
        long token = SSU2Util.getRelayResponseToken(data);
        assertEquals(0x3412L, token);
        byte[] trimmed = SSU2Util.trimRelayResponseToken(data);
        assertEquals(12, trimmed.length);
        assertEquals(0, trimmed[0]);
    }
}
