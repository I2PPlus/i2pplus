package net.i2p.router.transport.udp;

import java.util.Arrays;

import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.Router;
import net.i2p.router.transport.TransportImpl;
import net.i2p.util.OrderedProperties;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for the pure, package-visible decision helpers extracted from
 * {@link InboundEstablishState2#gotRI}:
 * {@link InboundEstablishState2#selectSessionAddress}, {@link InboundEstablishState2#parseMTU}
 * and {@link InboundEstablishState2#storeFailureReason}. They are exercised without
 * any router context, mirroring the audit pattern used for the SSU2Payload helpers.
 */
public class GotRIDecisionTest {

    private static RouterAddress addr(String style, String host, String... kv) {
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty(RouterAddress.PROP_HOST, host);
        opts.setProperty(RouterAddress.PROP_PORT, "12345");
        for (int i = 0; i < kv.length; i += 2) {
            opts.setProperty(kv[i], kv[i + 1]);
        }
        return new RouterAddress(style, opts, 5);
    }

    // ----- selectSessionAddress -----

    @Test
    public void selectsSingleSsu2Address() {
        RouterAddress a = addr(UDPTransport.STYLE2, "example.com");
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(a), false, ip4("9.9.9.9"));
        assertSame(a, sel.ra);
        assertNull(sel.mismatchMessage);
    }

    @Test
    public void toleratesSingleSsu1Address() {
        RouterAddress a = addr("SSU", "5.6.7.8");
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(a), false, ip4("9.9.9.9"));
        assertSame(a, sel.ra);
    }

    @Test
    public void skipsSsu1WithoutOptionWhenMultiple() {
        RouterAddress ssu1 = addr("SSU", "5.6.7.8");
        RouterAddress ssu2 = addr(UDPTransport.STYLE2, "5.6.7.9");
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(ssu1, ssu2), false, ip4("9.9.9.9"));
        assertSame(ssu2, sel.ra);
    }

    @Test
    public void skipsWrongFamilyForIpv4() {
        RouterAddress ipv6only = addr(UDPTransport.STYLE2, "2001:db8:0:0:0:0:0:1");
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(ipv6only), false, ip4("9.9.9.9"));
        assertNull(sel.ra);
    }

    @Test
    public void skipsWrongFamilyForIpv6() {
        RouterAddress ipv4only = addr(UDPTransport.STYLE2, "1.2.3.4");
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(ipv4only), true, ip6("2001:db8:0:0:0:0:0:1"));
        assertNull(sel.ra);
    }

    @Test
    public void capacityOptionOverridesFamilyCheck() {
        RouterAddress a = addr(UDPTransport.STYLE2, "1.2.3.4", UDPAddress.PROP_CAPACITY, TransportImpl.CAP_IPV6);
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(a), true, ip6("2001:db8:0:0:0:0:0:1"));
        assertSame(a, sel.ra);
    }

    @Test
    public void skipsSelfIpv4() {
        RouterAddress self = addr(UDPTransport.STYLE2, "1.2.3.4");
        RouterAddress other = addr(UDPTransport.STYLE2, "5.6.7.8");
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(self, other), false, ip4("1.2.3.4"));
        assertSame(other, sel.ra);
        assertNotNull(sel.mismatchMessage);
    }

    @Test
    public void flagsIpv4Mismatch() {
        RouterAddress a = addr(UDPTransport.STYLE2, "5.6.7.8");
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(a), false, ip4("1.2.3.4"));
        assertSame(a, sel.ra);
        assertNotNull(sel.mismatchMessage);
        assertTrue(sel.mismatchMessage.contains("IP mismatch actual IP 1.2.3.4"));
    }

    @Test
    public void skipsYggdrasilAddress() {
        RouterAddress ygg = addr(UDPTransport.STYLE2, "0200:0:0:0:0:0:0:1", UDPAddress.PROP_CAPACITY, bothCaps());
        RouterAddress other = addr(UDPTransport.STYLE2, "2001:db8:0:1:0:0:0:9", UDPAddress.PROP_CAPACITY, bothCaps());
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(ygg, other), true, ip6("2001:db8:0:0:0:0:0:9"));
        assertSame(other, sel.ra);
    }

    @Test
    public void skipsIpv6SelfPrefix() {
        RouterAddress self = addr(UDPTransport.STYLE2, "2001:db8:0:0:0:0:0:1", UDPAddress.PROP_CAPACITY, bothCaps());
        RouterAddress other = addr(UDPTransport.STYLE2, "2001:db8:0:1:0:0:0:2", UDPAddress.PROP_CAPACITY, bothCaps());
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(self, other), true, ip6("2001:db8:0:0:0:0:0:1"));
        assertSame(other, sel.ra);
    }

    @Test
    public void flagsIpv6Mismatch() {
        RouterAddress a = addr(UDPTransport.STYLE2, "2001:db8:0:1:0:0:0:9", UDPAddress.PROP_CAPACITY, bothCaps());
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(a), true, ip6("2001:db8:0:0:0:0:0:5"));
        assertSame(a, sel.ra);
        assertNotNull(sel.mismatchMessage);
    }

    @Test
    public void noAddressQualifies() {
        RouterAddress a = addr(UDPTransport.STYLE2, "hostname");
        InboundEstablishState2.AddressSelection sel =
            InboundEstablishState2.selectSessionAddress(Arrays.asList(a), true, ip6("2001:db8:0:0:0:0:0:1"));
        assertNull(sel.ra);
        assertNull(sel.mismatchMessage);
    }

    // ----- parseMTU -----

    @Test
    public void mtuDefaultsSsu2() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4");
        assertEquals(PeerState2.DEFAULT_MTU, InboundEstablishState2.parseMTU(ra, false));
    }

    @Test
    public void mtuDefaultsSsu1Ipv6() throws Exception {
        RouterAddress ra = addr("SSU", "2001:db8:0:0:0:0:0:1");
        assertEquals(PeerState2.DEFAULT_SSU_IPV6_MTU, InboundEstablishState2.parseMTU(ra, true));
    }

    @Test
    public void mtuDefaultsSsu1Ipv4() throws Exception {
        RouterAddress ra = addr("SSU", "1.2.3.4");
        assertEquals(PeerState2.DEFAULT_SSU_IPV4_MTU, InboundEstablishState2.parseMTU(ra, false));
    }

    @Test
    public void mtuUnparsableFallsBackToDefault() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", UDPAddress.PROP_MTU, "xyz");
        assertEquals(PeerState2.DEFAULT_MTU, InboundEstablishState2.parseMTU(ra, false));
    }

    @Test
    public void mtuWorkaround1890Ssu1() throws Exception {
        RouterAddress ra = addr("SSU", "1.2.3.4", UDPAddress.PROP_MTU, "1276");
        assertEquals(PeerState2.MIN_MTU, InboundEstablishState2.parseMTU(ra, false));
    }

    @Test
    public void mtuWorkaroundNotAppliedToSsu2() {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", UDPAddress.PROP_MTU, "1276");
        assertDataFormatException("MTU too small", ra);
    }

    @Test
    public void mtuValueClampsAtMaxSsu2() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", UDPAddress.PROP_MTU, "2000");
        assertEquals(PeerState2.MAX_MTU, InboundEstablishState2.parseMTU(ra, false));
    }

    @Test
    public void mtuValueKeptSsu2() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", UDPAddress.PROP_MTU, "1400");
        assertEquals(1400, InboundEstablishState2.parseMTU(ra, false));
    }

    @Test
    public void mtuValueRejectedBelowMin() {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", UDPAddress.PROP_MTU, "1000");
        assertRIException(SSU2Util.REASON_OPTIONS, "MTU too small", ra);
    }

    @Test
    public void mtuValueClampsAtMinSsu1Ipv4() throws Exception {
        RouterAddress ra = addr("SSU", "1.2.3.4", UDPAddress.PROP_MTU, "1290");
        assertEquals(PeerState2.MIN_SSU_IPV4_MTU, InboundEstablishState2.parseMTU(ra, false));
    }

    @Test
    public void mtuValueClampsAtMaxSsu1Ipv4() throws Exception {
        RouterAddress ra = addr("SSU", "1.2.3.4", UDPAddress.PROP_MTU, "3000");
        assertEquals(PeerState2.MAX_SSU_IPV4_MTU, InboundEstablishState2.parseMTU(ra, false));
    }

    @Test
    public void mtuValueBoundsSsu1Ipv6() throws Exception {
        RouterAddress a = addr("SSU", "2001:db8:0:0:0:0:0:1", UDPAddress.PROP_MTU, "1500");
        assertEquals(PeerState2.MAX_SSU_IPV6_MTU, InboundEstablishState2.parseMTU(a, true));
        RouterAddress b = addr("SSU", "2001:db8:0:0:0:0:0:1", UDPAddress.PROP_MTU, "1280");
        assertEquals(PeerState2.MIN_SSU_IPV6_MTU, InboundEstablishState2.parseMTU(b, true));
        RouterAddress c = addr("SSU", "2001:db8:0:0:0:0:0:1", UDPAddress.PROP_MTU, "1380");
        assertEquals(1380, InboundEstablishState2.parseMTU(c, true));
    }

    @Test
    public void mtuValueBoundsSsu1Ipv4() throws Exception {
        RouterAddress a = addr("SSU", "1.2.3.4", UDPAddress.PROP_MTU, "1292");
        assertEquals(PeerState2.MIN_SSU_IPV4_MTU, InboundEstablishState2.parseMTU(a, false));
        RouterAddress b = addr("SSU", "1.2.3.4", UDPAddress.PROP_MTU, "1484");
        assertEquals(PeerState2.MAX_SSU_IPV4_MTU, InboundEstablishState2.parseMTU(b, false));
    }

    // ----- storeFailureReason -----

    @Test
    public void skewForFutureDatedRi() {
        long now = 1_000_000_000_000L;
        assertEquals(SSU2Util.REASON_SKEW, InboundEstablishState2.storeFailureReason(now, now + 2*60*1000L + 1L));
    }

    @Test
    public void skewAtFutureWindowEdgeIsNotSkew() {
        long now = 1_000_000_000_000L;
        assertEquals(SSU2Util.REASON_MSG3, InboundEstablishState2.storeFailureReason(now, now + 2*60*1000L));
    }

    @Test
    public void skewForExpiredRi() {
        long now = 1_000_000_000_000L;
        assertEquals(SSU2Util.REASON_SKEW, InboundEstablishState2.storeFailureReason(now, now - 60*60*1000L - 1L));
    }

    @Test
    public void skewAtPastWindowEdgeIsNotSkew() {
        long now = 1_000_000_000_000L;
        assertEquals(SSU2Util.REASON_MSG3, InboundEstablishState2.storeFailureReason(now, now - 60*60*1000L));
    }

    @Test
    public void msg3ForRecentRi() {
        long now = 1_000_000_000_000L;
        assertEquals(SSU2Util.REASON_MSG3, InboundEstablishState2.storeFailureReason(now, now));
        assertEquals(SSU2Util.REASON_MSG3, InboundEstablishState2.storeFailureReason(now, now - 30*60*1000L));
    }

    // ----- parseSessionKeys -----

    @Test
    public void parsesMatchingSessionKeys() throws Exception {
        byte[] pub = new byte[SSU2Util.KEY_LEN];
        byte[] ik = new byte[SSU2Util.KEY_LEN];
        Arrays.fill(pub, (byte) 0x5a);
        Arrays.fill(ik, (byte) 0x3c);
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", "i", Base64.encode(ik), "s", Base64.encode(pub));
        assertArrayEquals(ik, InboundEstablishState2.parseSessionKeys(ra, pub));
    }

    @Test
    public void sessionKeysMissingIKey() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", "s", Base64.encode(new byte[SSU2Util.KEY_LEN]));
        assertDataFormatMessage("No SSU2 IKey", ra, null);
    }

    @Test
    public void sessionKeysBadIKey() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", "i", "%%%", "s", Base64.encode(new byte[SSU2Util.KEY_LEN]));
        assertDataFormatMessage("BAD SSU2 IKey", ra, null);
    }

    @Test
    public void sessionKeysWrongIKeyLength() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", "i", Base64.encode(new byte[16]), "s", Base64.encode(new byte[SSU2Util.KEY_LEN]));
        assertDataFormatMessage("BAD SSU2 IKey length", ra, null);
    }

    @Test
    public void sessionKeysMissingS() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", "i", Base64.encode(new byte[SSU2Util.KEY_LEN]));
        assertDataFormatMessage("No SSU2 S", ra, null);
    }

    @Test
    public void sessionKeysBadS() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", "i", Base64.encode(new byte[SSU2Util.KEY_LEN]), "s", "%%%");
        assertDataFormatMessage("BAD SSU2 S", ra, null);
    }

    @Test
    public void sessionKeysWrongSLength() throws Exception {
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", "i", Base64.encode(new byte[SSU2Util.KEY_LEN]), "s", Base64.encode(new byte[16]));
        assertDataFormatMessage("BAD SSU2 S length", ra, null);
    }

    @Test
    public void sessionKeysMismatchSRejected() throws Exception {
        byte[] pub = new byte[SSU2Util.KEY_LEN];
        byte[] wrong = new byte[SSU2Util.KEY_LEN];
        Arrays.fill(pub, (byte) 0x5a);
        Arrays.fill(wrong, (byte) 0x5b);
        RouterAddress ra = addr(UDPTransport.STYLE2, "1.2.3.4", "i", Base64.encode(new byte[SSU2Util.KEY_LEN]), "s", Base64.encode(wrong));
        assertDataFormatMessage("S mismatch in RouterInfo", ra, pub);
    }

    // ----- decideIntroduction -----

    @Test
    public void introBlockedOnTemporarySentPort() {
        assertEquals(InboundEstablishState2.INTRO_BLOCKED,
                     InboundEstablishState2.decideIntroduction("0.9.71", null, false, true, false));
    }

    @Test
    public void introBlockedWhenCannotIntroduce() {
        assertEquals(InboundEstablishState2.INTRO_BLOCKED,
                     InboundEstablishState2.decideIntroduction("0.9.71", null, true, false, false));
    }

    @Test
    public void introBlockedOnOldPeerVersion() {
        assertEquals(InboundEstablishState2.INTRO_VERSION,
                     InboundEstablishState2.decideIntroduction("0.9.56", null, true, true, false));
    }

    @Test
    public void introKeptAtVersionBoundary() {
        assertEquals(InboundEstablishState2.INTRO_KEEP,
                     InboundEstablishState2.decideIntroduction("0.9.57", null, true, true, false));
    }

    @Test
    public void introOpportunisticWhenReachableAndNotRandomlyKept() {
        assertEquals(InboundEstablishState2.INTRO_OPPORTUNISTIC,
                     InboundEstablishState2.decideIntroduction("0.9.71", String.valueOf(Router.CAPABILITY_REACHABLE), true, true, false));
    }

    @Test
    public void introKeptWhenReachableAndRandomlyKept() {
        assertEquals(InboundEstablishState2.INTRO_KEEP,
                     InboundEstablishState2.decideIntroduction("0.9.71", String.valueOf(Router.CAPABILITY_REACHABLE), true, true, true));
    }

    @Test
    public void introKeptWhenCapsUnreachable() {
        assertEquals(InboundEstablishState2.INTRO_KEEP,
                     InboundEstablishState2.decideIntroduction("0.9.71", "", true, true, false));
    }

    @Test
    public void introKeptWhenCapsNull() {
        assertEquals(InboundEstablishState2.INTRO_KEEP,
                     InboundEstablishState2.decideIntroduction("0.9.71", null, true, true, false));
    }

    // ----- helpers -----

    private static String bothCaps() {
        return TransportImpl.CAP_IPV4 + "," + TransportImpl.CAP_IPV6;
    }

    private static byte[] ip4(String host) {
        return parse(host);
    }

    private static byte[] ip6(String host) {
        return parse(host);
    }

    private static byte[] parse(String host) {
        byte[] rv = net.i2p.util.Addresses.getIPOnly(host);
        assertNotNull("unparseable test IP " + host, rv);
        return rv;
    }

    private static void assertRIException(int reason, String msgPart, RouterAddress ra) {
        try {
            InboundEstablishState2.parseMTU(ra, true);
            fail("expected RIException, got none");
        } catch (InboundEstablishState2.RIException e) {
            assertEquals(reason, e.getReason());
            assertTrue(e.getMessage().contains(msgPart));
        } catch (DataFormatException e) {
            fail("expected RIException but got " + e);
        }
    }

    private static void assertDataFormatException(String msgPart, RouterAddress ra) {
        try {
            InboundEstablishState2.parseMTU(ra, true);
            fail("expected DataFormatException, got none");
        } catch (DataFormatException e) {
            assertTrue(e.getMessage().contains(msgPart));
        }
    }

    private static void assertDataFormatMessage(String msgPart, RouterAddress ra, byte[] publicKey) throws Exception {
        try {
            InboundEstablishState2.parseSessionKeys(ra, publicKey);
            fail("expected DataFormatException, got none");
        } catch (DataFormatException e) {
            assertTrue(e.getMessage().contains(msgPart));
        }
    }
}
