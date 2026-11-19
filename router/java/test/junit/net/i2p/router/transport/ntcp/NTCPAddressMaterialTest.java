package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import java.util.Arrays;

import net.i2p.data.Base64;
import net.i2p.data.router.RouterAddress;
import net.i2p.util.OrderedProperties;

import org.junit.Test;

/**
 * Pins what an NTCP address has to carry before it is chosen.
 *
 * <p>Address selection accepted any NTCP2 address that merely had an s and an i
 * option, and the handshake material was not decoded until the connection object was
 * built, where it failed. Selection returns the first address that passes, so a peer
 * whose first NTCP2 address carried a corrupt static key or IV was the only address we
 * ever tried: the connection came back null, the send failed, and the peer was marked
 * unreachable - the same signal the tunnel hop picker consults - even though its
 * second address was perfectly good.
 *
 * @since 0.9.72+
 */
public class NTCPAddressMaterialTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public NTCPAddressMaterialTest() {}

    /**
     * A static key of the right length, with no bit set that the handshake reserves.
     */
    private static final byte[] GOOD_KEY = key((byte) 0x11, false);

    /**
     * A static IV of the right length and not zero.
     */
    private static final byte[] GOOD_IV = iv((byte) 0x22);

    /**
     * A key of the right length.
     *
     * @param fill the byte to fill it with
     * @param pqMarker whether to set the high bit of the last byte, which is the PQ
     *                 handshake marker rather than part of a published key
     * @return the key
     */
    private static byte[] key(byte fill, boolean pqMarker) {
        byte[] rv = new byte[32];
        Arrays.fill(rv, fill);
        if (pqMarker) {
            rv[31] = (byte) (rv[31] | 0x80);
        }
        return rv;
    }

    /**
     * An IV of the right length.
     *
     * @param fill the byte to fill it with
     * @return a 16-byte IV that is not all zeros
     */
    private static byte[] iv(byte fill) {
        byte[] rv = new byte[16];
        Arrays.fill(rv, fill);
        return rv;
    }

    /**
     * An NTCP2 address with the given handshake options.
     *
     * @param v the version option, null to leave it out
     * @param i the static IV option, null to leave it out
     * @param s the static key option, null to leave it out
     * @return an NTCP2 address on 192.0.2.1:8071 carrying those options
     */
    private static RouterAddress address(String v, String i, String s) {
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty("host", "192.0.2.1");
        opts.setProperty("port", "8071");
        if (v != null) {opts.setProperty("v", v);}
        if (i != null) {opts.setProperty("i", i);}
        if (s != null) {opts.setProperty("s", s);}
        return new RouterAddress(NTCPTransport.STYLE2, opts, 65);
    }

    /**
     * The version selection would choose for one address.
     *
     * @param v the version option
     * @param i the static IV option
     * @param s the static key option
     * @return the version chosen, or 0 if the address is unusable
     */
    private static int version(String v, String i, String s) {
        return NTCPTransport.getNTCPVersion(address(v, i, s));
    }

    /**
     * An address with usable handshake material is NTCP2.
     */
    @Test
    public void testAValidAddressIsAccepted() {
        assertEquals(2, version("2", Base64.encode(GOOD_IV), Base64.encode(GOOD_KEY)));
    }

    /**
     * The defect: an IV that cannot be used was still an address, and the connection
     * built from it failed.
     */
    @Test
    public void testAnUnusableIVIsRejected() {
        String s = Base64.encode(GOOD_KEY);
        assertEquals(0, version("2", "invalid!", s));
        assertEquals(0, version("2", Base64.encode(new byte[15]), s));
        assertEquals(0, version("2", Base64.encode(new byte[16]), s));
        assertEquals(0, version("2", Base64.encode(GOOD_IV), null));
    }

    /**
     * The same for the static key: wrong length, all zeros, or the PQ marker bit set
     * in a published key.
     */
    @Test
    public void testAnUnusableStaticKeyIsRejected() {
        String i = Base64.encode(GOOD_IV);
        assertEquals(0, version("2", i, "invalid!"));
        assertEquals(0, version("2", i, Base64.encode(new byte[31])));
        assertEquals(0, version("2", i, Base64.encode(new byte[33])));
        assertEquals(0, version("2", i, Base64.encode(new byte[32])));
        assertEquals(0, version("2", i, Base64.encode(key((byte) 0x11, true))));
        assertEquals(0, version("2", i, ""));
    }

    /**
     * The point of checking during selection: the peer's second address is tried
     * instead of the corrupt first one. This is the loop getTargetAddress() runs -
     * the first address with a usable version is the one it returns.
     */
    @Test
    public void testAGoodAddressIsStillFoundAfterABadOne() {
        RouterAddress bad = address("2", "invalid!", Base64.encode(GOOD_KEY));
        RouterAddress good = address("2", Base64.encode(GOOD_IV), Base64.encode(GOOD_KEY));
        RouterAddress[] addrs = {bad, good};
        RouterAddress chosen = null;
        for (RouterAddress addr : addrs) {
            if (NTCPTransport.getNTCPVersion(addr) > 0) {
                chosen = addr;
                break;
            }
        }
        assertSame("the corrupt address was still the one chosen", good, chosen);
    }

    /**
     * A post-quantum version needs usable material as well - the version option is
     * not a promise that the key is there.
     */
    @Test
    public void testAPostQuantumVersionNeedsMaterialToo() {
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty("host", "192.0.2.1");
        opts.setProperty("port", "8071");
        opts.setProperty("v", "2");
        opts.setProperty("i", Base64.encode(GOOD_IV));
        opts.setProperty("s", Base64.encode(GOOD_KEY));
        opts.setProperty("pq", "4");
        RouterAddress addr = new RouterAddress(NTCPTransport.STYLE2, opts, 65);
        assertEquals(4, NTCPTransport.getNTCPVersion(addr));

        OrderedProperties broken = new OrderedProperties();
        broken.setProperty("host", "192.0.2.1");
        broken.setProperty("port", "8071");
        broken.setProperty("v", "2");
        broken.setProperty("i", Base64.encode(GOOD_IV));
        broken.setProperty("s", Base64.encode(new byte[32]));
        broken.setProperty("pq", "4");
        RouterAddress brokenAddr = new RouterAddress(NTCPTransport.STYLE2, broken, 65);
        assertEquals(0, NTCPTransport.getNTCPVersion(brokenAddr));
    }

    /**
     * The two predicates, checked directly: they are the same tests the handshake
     * applies when it builds the connection.
     */
    @Test
    public void testTheMaterialPredicates() {
        assertTrue(OutboundNTCP2State.isValidIV(GOOD_IV));
        assertFalse(OutboundNTCP2State.isValidIV(null));
        assertFalse(OutboundNTCP2State.isValidIV(new byte[16]));
        assertFalse(OutboundNTCP2State.isValidIV(new byte[17]));
        assertTrue(OutboundNTCP2State.isValidStaticKey(GOOD_KEY));
        assertFalse(OutboundNTCP2State.isValidStaticKey(null));
        assertFalse(OutboundNTCP2State.isValidStaticKey(new byte[32]));
        assertFalse(OutboundNTCP2State.isValidStaticKey(key((byte) 0x11, true)));
    }
}
