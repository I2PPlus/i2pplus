package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.data.Base64;
import org.junit.Test;

/**
 * Tests how an SSU2 handshake version is negotiated from the options a remote
 * address advertises.
 *
 * <p>Two ways to get that wrong are invisible until the handshake fails. A
 * version option matched as a substring reads a peer advertising pq=14 as
 * offering hybrid 4, and v=12 as offering version 2, so we attempt a handshake
 * the peer never advertised. And the family that selects the MTU floor cannot
 * come from the host string, because an address that reaches us through
 * introducers has no host option at all - every IPv6 introducer would then be
 * measured against the IPv4 floor, twenty bytes lower.
 *
 * <p>No socket, no transport, no mocks: the negotiation is a decision over
 * option strings.
 *
 * @since 0.9.72+
 */
public class UDPTransportNegotiationTest {

    /** A base64 key of the length the i option must decode to. */
    private static final String INTRO_KEY = Base64.encode(new byte[SSU2Util.INTRO_KEY_LEN]);

    /** A base64 key of the length the s option must decode to. */
    private static final String STATIC_KEY = Base64.encode(new byte[SSU2Util.KEY_LEN]);

    /** An MTU large enough for either post-quantum hybrid, either family. */
    private static final int BIG_MTU = PeerState2.MIN_MLKEM768_IPV6_MTU;

    /**
     * A fixture group for the negotiation; it holds no state.
     *
     * @since 0.9.72+
     */
    public UDPTransportNegotiationTest() {}

    /**
     * A version option matches a whole comma-delimited entry, not a substring of
     * one, and a trailing comma still matches.
     */
    @Test
    public void testVersionOptionsMatchAsWholeEntries() {
        assertTrue(UDPTransport.hasVersion("2", "2"));
        assertTrue(UDPTransport.hasVersion("2,", "2"));
        assertTrue(UDPTransport.hasVersion("2,4", "2"));
        assertTrue(UDPTransport.hasVersion("4,2", "2"));
        assertFalse(UDPTransport.hasVersion("12", "2"));
        assertFalse(UDPTransport.hasVersion("20", "2"));
        assertFalse(UDPTransport.hasVersion("3,12", "2"));
        assertFalse(UDPTransport.hasVersion(null, "2"));
    }

    /**
     * A two-digit hybrid number is not two one-digit ones: pq=14, pq=34 and
     * pq=43 advertise no hybrid we know, and must not talk us into a handshake
     * the peer never offered.
     */
    @Test
    public void testLookalikeHybridsAreNotHybrids() {
        assertEquals(2, negotiate("2", "14"));
        assertEquals(2, negotiate("2", "34"));
        assertEquals(2, negotiate("2", "43"));
        assertEquals(2, negotiate("2", "13"));
        assertEquals(2, negotiate("2", "14,13"));
    }

    /**
     * A real hybrid list is read entry by entry, in either order, and the
     * larger hybrid wins when both are offered.
     */
    @Test
    public void testHybridListsAreReadEntryByEntry() {
        assertEquals(4, negotiate("2", "13,4"));
        assertEquals(4, negotiate("2", "4,3"));
        assertEquals(4, negotiate("2", "4"));
        assertEquals(3, negotiate("2", "3"));
        assertEquals(3, negotiate("2", "14,3"));
        assertEquals(2, negotiate("2", null));
    }

    /**
     * The family decides which MTU floor the MLKEM768 hybrid has, so the same
     * address negotiates 4 over IPv4 and falls back to 3 over IPv6 at an MTU
     * between the two floors.
     */
    @Test
    public void testTheFamilyDecidesTheHybridFloor() {
        int between = PeerState2.MIN_MLKEM768_IPV4_MTU + 2;
        assertEquals(4, negotiate("2", "4,3", between, false));
        assertEquals(3, negotiate("2", "4,3", between, true));
        assertEquals(4, negotiate("2", "4,3", BIG_MTU, true));
    }

    /**
     * An address advertising an MTU below the SSU2 minimum, or one we cannot
     * carry at our own minimum, negotiates no SSU2 version at all - not even the
     * plain one.
     */
    @Test
    public void testAnMtuBelowTheFloorNegotiatesNothing() {
        assertEquals(0, negotiate("2", "4,3", PeerState2.MIN_MTU - 1, true));
        assertEquals(0, negotiate("2", null, PeerState2.MIN_MTU - 1, false));
        assertEquals(0, negotiate("2", null, PeerState2.MIN_MTU, false, PeerState2.MIN_MTU - 1));
        assertEquals(2, negotiate("2", null, PeerState2.MIN_MTU, false));
        assertEquals(2, negotiate("2", null, 0, false));
    }

    /**
     * A malformed key option makes the address unusable now, rather than
     * throwing part-way through the handshake we selected it for.
     */
    @Test
    public void testAMalformedKeyOptionNegotiatesNothing() {
        String shortKey = Base64.encode(new byte[SSU2Util.KEY_LEN - 1]);
        String longKey = Base64.encode(new byte[SSU2Util.KEY_LEN + 1]);
        assertEquals(0, negotiateWithKeys(null, STATIC_KEY));
        assertEquals(0, negotiateWithKeys(shortKey, STATIC_KEY));
        assertEquals(0, negotiateWithKeys(longKey, STATIC_KEY));
        assertEquals(0, negotiateWithKeys(INTRO_KEY, shortKey));
        assertEquals(0, negotiateWithKeys("not base64 !!", STATIC_KEY));
        assertTrue(UDPTransport.isKeyOfLength(STATIC_KEY, SSU2Util.KEY_LEN));
        assertFalse(UDPTransport.isKeyOfLength(shortKey, SSU2Util.KEY_LEN));
        assertFalse(UDPTransport.isKeyOfLength(null, SSU2Util.KEY_LEN));
    }

    /**
     * The version option is read as a list, so an address advertising a list that
     * starts with something else is not SSU2, and one that carries 2 anywhere in
     * the list is.
     */
    @Test
    public void testVersionListsAreMatchedByEntry() {
        assertEquals(0, negotiate("12", null));
        assertEquals(0, negotiate("20", null));
        assertEquals(0, negotiate(null, null));
        assertEquals(2, negotiate("2,4", null));
        assertEquals(2, negotiate("4,2", null));
    }

    /**
     * Runs the negotiation with the given keys, no hybrid advertised, and no
     * MTU advertised by either side.
     *
     * @param introKey the i option, base64
     * @param staticKey the s option, base64
     * @return the negotiated version
     */
    private static int negotiateWithKeys(String introKey, String staticKey) {
        return UDPTransport.negotiateVersion("2", null, introKey, staticKey, 0, 0, false);
    }

    /**
     * Runs the negotiation with valid keys, no MTU advertised by either side, and
     * an IPv4 address.
     *
     * @param v the v option
     * @param pq the pq option
     * @return the negotiated version
     */
    private static int negotiate(String v, String pq) {
        return negotiate(v, pq, 0, false);
    }

    /**
     * Runs the negotiation with valid keys and both MTUs advertised.
     *
     * @param v the v option
     * @param pq the pq option
     * @param mtu the MTU both sides carry, in bytes
     * @param ipv6 the address family
     * @return the negotiated version
     */
    private static int negotiate(String v, String pq, int mtu, boolean ipv6) {
        return negotiate(v, pq, mtu, ipv6, mtu);
    }

    /**
     * Runs the negotiation with valid keys.
     *
     * @param v the v option
     * @param pq the pq option
     * @param theirMtu their advertised MTU, in bytes
     * @param ipv6 the address family
     * @param ourMtu our MTU for that family, in bytes
     * @return the negotiated version
     */
    private static int negotiate(String v, String pq, int theirMtu, boolean ipv6, int ourMtu) {
        return UDPTransport.negotiateVersion(v, pq, INTRO_KEY, STATIC_KEY, theirMtu, ourMtu, ipv6);
    }
}
