package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.data.Base64;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.util.OrderedProperties;

/**
 * Tests the length checks an out-of-session peer test message has to pass before
 * it is read, and the intro key taken from a remote RouterInfo's address options.
 * Both values come off the wire, so a short one used to throw out of the peer
 * test path instead of being dropped.
 *
 * @since 0.9.72+
 */
public class PeerTestManagerLengthTest {

    @Test
    public void testHeaderLengthCoversEveryFixedRead() {
        assertEquals(10, PeerTestManager.PEERTEST_HEADER_LENGTH);
    }

    /**
     * The declared address length is a claim, so the minimum message length grows
     * with it: 6 for an IPv4 address plus port, 18 for IPv6 plus port.
     */
    @Test
    public void testMinimumLengthFollowsTheDeclaredAddress() {
        assertEquals(10, PeerTestManager.peerTestMinLength(0));
        assertEquals(16, PeerTestManager.peerTestMinLength(6));
        assertEquals(28, PeerTestManager.peerTestMinLength(18));
    }

    /**
     * A message exactly at the minimum is readable, and one byte less is not -
     * the copy of a declared IPv6 address used to run off the end.
     */
    @Test
    public void testIpv6MessageOneByteShortIsRejected() {
        byte[] data = new byte[PeerTestManager.peerTestMinLength(18)];
        data[0] = 2;
        data[9] = 18;
        assertEquals(PeerTestManager.PEERTEST_HEADER_LENGTH + 18, data.length);

        byte[] shortData = new byte[data.length - 1];
        System.arraycopy(data, 0, shortData, 0, shortData.length);
        assertTrue(shortData.length < PeerTestManager.peerTestMinLength(shortData[9] & 0xff));
    }

    @Test
    public void testNoAddressNeedsOnlyTheHeader() {
        byte[] data = new byte[PeerTestManager.peerTestMinLength(0)];
        data[0] = 2;
        data[9] = 0;
        assertFalse(data.length < PeerTestManager.PEERTEST_HEADER_LENGTH);
    }

    @Test
    public void testIntroKeyNullAddress() {
        assertNull(PeerTestManager.getIntroKey(null));
    }

    @Test
    public void testIntroKeyMissingOption() {
        assertNull(PeerTestManager.getIntroKey(addressWithOptions(null)));
    }

    /**
     * The "i" option of a remote SSU2 address is not necessarily a key. SessionKey
     * rejects any other size, so a short or long option used to throw out of the
     * relay and peer-test paths.
     */
    @Test
    public void testIntroKeyWrongSizeRejected() {
        assertNull(PeerTestManager.getIntroKey(addressWithOptions(new byte[0])));
        assertNull(PeerTestManager.getIntroKey(addressWithOptions(new byte[SessionKey.KEYSIZE_BYTES - 1])));
        assertNull(PeerTestManager.getIntroKey(addressWithOptions(new byte[SessionKey.KEYSIZE_BYTES + 1])));
        assertNull(PeerTestManager.getIntroKey(addressWithOptions("not base64 at all ***".getBytes())));
    }

    @Test
    public void testIntroKeyOfTheRightSizeIsKept() {
        byte[] key = new byte[SessionKey.KEYSIZE_BYTES];
        for (int i = 0; i < key.length; i++)
            key[i] = (byte) i;
        SessionKey sk = PeerTestManager.getIntroKey(addressWithOptions(key));
        assertNotNull(sk);
        assertArrayEquals(key, sk.getData());
    }

    /**
     * An SSU2 router address carrying the intro key option.
     *
     * @param key the key to base64 into the "i" option, or null to omit the option
     * @return a UDP router address with the "i" option set as asked
     */
    private static RouterAddress addressWithOptions(byte[] key) {
        OrderedProperties props = new OrderedProperties();
        props.setProperty("host", "127.0.0.1");
        props.setProperty("port", "12345");
        if (key != null)
            props.setProperty("i", Base64.encode(key));
        return new RouterAddress("SSU2", props, 0);
    }
}
