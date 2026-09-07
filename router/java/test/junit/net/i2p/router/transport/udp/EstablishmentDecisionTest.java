package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_CONFIRMED_PARTIALLY;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_INTRODUCED;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_NEEDS_TOKEN;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_REQUEST_SENT;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_UNKNOWN;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_VALIDATION_FAILED;

import java.net.InetAddress;
import java.security.GeneralSecurityException;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.Banlist;
import net.i2p.router.Blocklist;
import net.i2p.router.transport.udp.OutboundEstablishState2.IntroState;
import net.i2p.util.OrderedProperties;
import org.junit.Test;

/**
 * Pins the pure decision helpers extracted from EstablishmentManager:
 * banlist/blocklist admission, exception message quality, relay-response
 * signer eligibility, and reason-code parse. No router context is required;
 * Banlist/Blocklist are mocked.
 *
 * @since 0.9.71+
 */
public class EstablishmentDecisionTest {

    @Test
    public void testIsBanlistedNullHash() {
        assertFalse(EstablishmentManager.isBanlisted(mock(Banlist.class), null));
    }

    @Test
    public void testIsBanlistedAllMethodsFalse() {
        Banlist banlist = mock(Banlist.class);
        Hash h = Hash.create(new byte[Hash.HASH_LENGTH]);
        assertFalse(EstablishmentManager.isBanlisted(banlist, h));
    }

    @Test
    public void testIsBanlistedAnyActiveBan() {
        Hash h = Hash.create(new byte[Hash.HASH_LENGTH]);
        Banlist banlist = mock(Banlist.class);
        // no active ban: not banned
        assertFalse(EstablishmentManager.isBanlisted(banlist, h));
        // any active ban fires, regardless of tier
        when(banlist.isBanlisted(h)).thenReturn(Boolean.TRUE);
        assertTrue(EstablishmentManager.isBanlisted(banlist, h));
    }

    @Test
    public void testIsBlocklistedNullIP() {
        assertFalse(EstablishmentManager.isBlocklisted(mock(Blocklist.class), null));
    }

    @Test
    public void testIsBlocklistedFlagged() {
        byte[] ip = new byte[] {10, 0, 0, 1};
        Blocklist notBlocked = mock(Blocklist.class);
        assertFalse(EstablishmentManager.isBlocklisted(notBlocked, ip));

        Blocklist blocked = mock(Blocklist.class);
        when(blocked.isBlocklisted(ip)).thenReturn(Boolean.TRUE);
        assertTrue(EstablishmentManager.isBlocklisted(blocked, ip));
    }

    @Test
    public void testHasUsefulMessage() throws Exception {
        assertFalse(EstablishmentManager.hasUsefulMessage(null));
        assertFalse(EstablishmentManager.hasUsefulMessage(new GeneralSecurityException()));
        assertFalse(EstablishmentManager.hasUsefulMessage(new GeneralSecurityException("null")));
        assertTrue(EstablishmentManager.hasUsefulMessage(new GeneralSecurityException("Bad keys")));
        assertTrue(EstablishmentManager.hasUsefulMessage(new GeneralSecurityException("")));
    }

    @Test
    public void testCanBeSignerFinalOrInitialStates() {
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_INIT));
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_EXPIRED));
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_REJECTED));
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_CONNECT_FAILED));
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_BOB_REJECT));
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_CHARLIE_REJECT));
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_FAILED));
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_INVALID));
        assertFalse(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_DISCONNECTED));
    }

    @Test
    public void testCanBeSignerPendingOrAcceptedStates() {
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_LOOKUP_SENT));
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_HAS_RI));
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_CONNECTING));
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_CONNECTED));
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_RELAY_REQUEST_SENT));
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_RELAY_CHARLIE_ACCEPTED));
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_LOOKUP_FAILED));
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_RELAY_RESPONSE_TIMEOUT));
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_SUCCESS));
    }

    @Test
    public void testCanBeSignerUsFallsToDefault() {
        assertTrue(EstablishmentManager.canBeSigner(IntroState.INTRO_STATE_US));
    }

    @Test
    public void testShouldSuppressException() {
        assertTrue(EstablishmentManager.shouldSuppressException(new RuntimeException("Old and slow peer")));
        assertTrue(EstablishmentManager.shouldSuppressException(new RuntimeException("RouterInfo store fail")));
        assertTrue(EstablishmentManager.shouldSuppressException(
            new RuntimeException("outer", new RuntimeException("Old and slow"))));
        assertFalse(EstablishmentManager.shouldSuppressException(new RuntimeException("Something else")));
        assertFalse(EstablishmentManager.shouldSuppressException(null));
        assertFalse(EstablishmentManager.shouldSuppressException(new RuntimeException()));
    }

    @Test
    public void testParseReasonKnownCodes() {
        assertEquals("Unspecified", EstablishmentManager.parseReason(SSU2Util.REASON_UNSPEC));
        assertEquals("Termination", EstablishmentManager.parseReason(SSU2Util.REASON_TERMINATION));
        assertEquals("Timeout", EstablishmentManager.parseReason(SSU2Util.REASON_TIMEOUT));
        assertEquals("Shutdown", EstablishmentManager.parseReason(SSU2Util.REASON_SHUTDOWN));
        assertEquals("AEAD error", EstablishmentManager.parseReason(SSU2Util.REASON_AEAD));
        assertEquals("Options error", EstablishmentManager.parseReason(SSU2Util.REASON_OPTIONS));
        assertEquals("Signature type error", EstablishmentManager.parseReason(SSU2Util.REASON_SIGTYPE));
        assertEquals("Excessive clock skew", EstablishmentManager.parseReason(SSU2Util.REASON_SKEW));
        assertEquals("Padding error", EstablishmentManager.parseReason(SSU2Util.REASON_PADDING));
        assertEquals("Framing error", EstablishmentManager.parseReason(SSU2Util.REASON_FRAMING));
        assertEquals("Payload error", EstablishmentManager.parseReason(SSU2Util.REASON_PAYLOAD));
        assertEquals("Message #1 error", EstablishmentManager.parseReason(SSU2Util.REASON_MSG1));
        assertEquals("Message #2 error", EstablishmentManager.parseReason(SSU2Util.REASON_MSG2));
        assertEquals("Message #3 error", EstablishmentManager.parseReason(SSU2Util.REASON_MSG3));
        assertEquals("Frame Timeout", EstablishmentManager.parseReason(SSU2Util.REASON_FRAME_TIMEOUT));
        assertEquals("Signature error", EstablishmentManager.parseReason(SSU2Util.REASON_SIGFAIL));
        assertEquals("S Mismatch", EstablishmentManager.parseReason(SSU2Util.REASON_S_MISMATCH));
        assertEquals("Router is banned", EstablishmentManager.parseReason(SSU2Util.REASON_BANNED));
        assertEquals("Token error", EstablishmentManager.parseReason(SSU2Util.REASON_TOKEN));
        assertEquals("Limit reached", EstablishmentManager.parseReason(SSU2Util.REASON_LIMITS));
        assertEquals("Incompatible Version", EstablishmentManager.parseReason(SSU2Util.REASON_VERSION));
        assertEquals("BAD NetId", EstablishmentManager.parseReason(SSU2Util.REASON_NETID));
        assertEquals("Replaced connection", EstablishmentManager.parseReason(SSU2Util.REASON_REPLACED));
    }

    @Test
    public void testParseReasonUnknown() {
        assertEquals("Unknown error", EstablishmentManager.parseReason(23));
        assertEquals("Unknown error", EstablishmentManager.parseReason(-1));
        assertEquals("Unknown error", EstablishmentManager.parseReason(100));
    }

    @Test
    public void testIsWrongNetwork() {
        assertFalse(EstablishmentManager.isWrongNetwork(7, 7));
        assertTrue(EstablishmentManager.isWrongNetwork(8, 7));
        assertTrue(EstablishmentManager.isWrongNetwork(-1, 7));
    }

    @Test
    public void testIsUnspecifiedNetwork() {
        assertTrue(EstablishmentManager.isUnspecifiedNetwork(-1));
        assertFalse(EstablishmentManager.isUnspecifiedNetwork(0));
        assertFalse(EstablishmentManager.isUnspecifiedNetwork(7));
        assertFalse(EstablishmentManager.isUnspecifiedNetwork(1));
    }

    @Test
    public void testIsUsableUDPAddress() throws Exception {
        InetAddress addr = InetAddress.getByName("10.0.0.1");
        assertTrue(EstablishmentManager.isUsableUDPAddress(addr, 80));
        assertFalse(EstablishmentManager.isUsableUDPAddress((InetAddress)null, 80));
        assertFalse(EstablishmentManager.isUsableUDPAddress(addr, 0));
        assertFalse(EstablishmentManager.isUsableUDPAddress(addr, -1));
        assertFalse(EstablishmentManager.isUsableUDPAddress(addr, 65536));
        assertTrue(EstablishmentManager.isUsableUDPAddress(addr, 1));
        assertTrue(EstablishmentManager.isUsableUDPAddress(addr, 65535));
    }

    @Test
    public void testIsUsableUDPAddressBytes() {
        byte[] ip = new byte[] {10, 0, 0, 1};
        assertTrue(EstablishmentManager.isUsableUDPAddress(ip, 80));
        assertFalse(EstablishmentManager.isUsableUDPAddress((byte[])null, 80));
        assertFalse(EstablishmentManager.isUsableUDPAddress(ip, 0));
        assertFalse(EstablishmentManager.isUsableUDPAddress(ip, -1));
        assertFalse(EstablishmentManager.isUsableUDPAddress(ip, 65536));
        assertTrue(EstablishmentManager.isUsableUDPAddress(ip, 1));
        assertTrue(EstablishmentManager.isUsableUDPAddress(ip, 65535));
    }

    @Test
    public void testIsInvalidPeerIP() {
        assertTrue(EstablishmentManager.isInvalidPeerIP(false, false, false));
        assertTrue(EstablishmentManager.isInvalidPeerIP(false, true, false));
        assertTrue(EstablishmentManager.isInvalidPeerIP(false, true, true));
        assertFalse(EstablishmentManager.isInvalidPeerIP(true, false, false));
        assertFalse(EstablishmentManager.isInvalidPeerIP(true, false, true));
        // equals our external IP and local not allowed
        assertTrue(EstablishmentManager.isInvalidPeerIP(true, true, false));
        // equals our external IP but local allowed
        assertFalse(EstablishmentManager.isInvalidPeerIP(true, true, true));
    }

    @Test
    public void testNeedsIndirect() {
        assertTrue(EstablishmentManager.needsIndirect(true, false));
        assertTrue(EstablishmentManager.needsIndirect(false, true));
        assertTrue(EstablishmentManager.needsIndirect(true, true));
        assertFalse(EstablishmentManager.needsIndirect(false, false));
    }

    @Test
    public void testShouldQueueOutbound() {
        assertFalse(EstablishmentManager.shouldQueueOutbound(false, 10, 5));
        assertFalse(EstablishmentManager.shouldQueueOutbound(true, 4, 5));
        assertTrue(EstablishmentManager.shouldQueueOutbound(true, 5, 5));
        assertTrue(EstablishmentManager.shouldQueueOutbound(true, 10, 5));
    }

    @Test
    public void testShouldRejectQueue() {
        assertFalse(EstablishmentManager.shouldRejectQueue(true, 100, 128));
        assertTrue(EstablishmentManager.shouldRejectQueue(false, 128, 128));
        assertFalse(EstablishmentManager.shouldRejectQueue(false, 127, 128));
        assertFalse(EstablishmentManager.shouldRejectQueue(true, 200, 128));
    }

    @Test
    public void testQueueAtCapacity() {
        assertFalse(EstablishmentManager.queueAtCapacity(31, 32));
        assertTrue(EstablishmentManager.queueAtCapacity(32, 32));
        assertTrue(EstablishmentManager.queueAtCapacity(33, 32));
        assertFalse(EstablishmentManager.queueAtCapacity(0, 32));
    }

    @Test
    public void testNextIntroStateForPeerCheckConnectedSupported() {
        OutboundEstablishState2.IntroState next =
            EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_INIT, true, true);
        assertEquals(IntroState.INTRO_STATE_CONNECTED, next);

        next = EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_CONNECTING, true, true);
        assertEquals(IntroState.INTRO_STATE_CONNECTED, next);
    }

    @Test
    public void testNextIntroStateForPeerCheckConnectedUnsupportedVersion() {
        OutboundEstablishState2.IntroState next =
            EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_INIT, true, false);
        assertEquals(IntroState.INTRO_STATE_REJECTED, next);

        next = EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_CONNECTING, true, false);
        assertEquals(IntroState.INTRO_STATE_REJECTED, next);
    }

    @Test
    public void testNextIntroStateForPeerCheckNoPeer() {
        assertEquals(IntroState.INTRO_STATE_INIT,
                     EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_INIT, false, false));
        assertEquals(IntroState.INTRO_STATE_CONNECTING,
                     EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_CONNECTING, false, true));
        assertEquals(IntroState.INTRO_STATE_HAS_RI,
                     EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_HAS_RI, false, true));
    }

    @Test
    public void testNextIntroStateForPeerCheckConnectedGone() {
        assertEquals(IntroState.INTRO_STATE_DISCONNECTED,
                     EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_CONNECTED, false, false));
        assertEquals(IntroState.INTRO_STATE_CONNECTED,
                     EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_CONNECTED, true, false));
        assertEquals(IntroState.INTRO_STATE_CONNECTED,
                     EstablishmentManager.nextIntroStateforPeerCheck(IntroState.INTRO_STATE_CONNECTED, true, true));
    }

    @Test
    public void testNextIntroStateForLocalLookup() {
        assertEquals(IntroState.INTRO_STATE_HAS_RI,
                     EstablishmentManager.nextIntroStateforLocalLookup(IntroState.INTRO_STATE_INIT, true));
        assertEquals(IntroState.INTRO_STATE_HAS_RI,
                     EstablishmentManager.nextIntroStateforLocalLookup(IntroState.INTRO_STATE_LOOKUP_SENT, true));
        assertEquals(IntroState.INTRO_STATE_HAS_RI,
                     EstablishmentManager.nextIntroStateforLocalLookup(IntroState.INTRO_STATE_HAS_RI, true));
        assertEquals(IntroState.INTRO_STATE_INIT,
                     EstablishmentManager.nextIntroStateforLocalLookup(IntroState.INTRO_STATE_INIT, false));
        assertEquals(IntroState.INTRO_STATE_LOOKUP_SENT,
                     EstablishmentManager.nextIntroStateforLocalLookup(IntroState.INTRO_STATE_LOOKUP_SENT, false));
        assertEquals(IntroState.INTRO_STATE_HAS_RI,
                     EstablishmentManager.nextIntroStateforLocalLookup(IntroState.INTRO_STATE_HAS_RI, false));
        // states not eligible for local lookup are unchanged regardless
        assertEquals(IntroState.INTRO_STATE_REJECTED,
                     EstablishmentManager.nextIntroStateforLocalLookup(IntroState.INTRO_STATE_REJECTED, true));
        assertEquals(IntroState.INTRO_STATE_CONNECTING,
                     EstablishmentManager.nextIntroStateforLocalLookup(IntroState.INTRO_STATE_CONNECTING, true));
    }

    @Test
    public void testIsTerminalOutboundState() {
        assertTrue(EstablishmentManager.isTerminalOutboundState(OB_STATE_CONFIRMED_COMPLETELY));
        assertTrue(EstablishmentManager.isTerminalOutboundState(OB_STATE_VALIDATION_FAILED));
        // every retryable/sending state is non-terminal
        assertFalse(EstablishmentManager.isTerminalOutboundState(OB_STATE_UNKNOWN));
        assertFalse(EstablishmentManager.isTerminalOutboundState(OB_STATE_INTRODUCED));
        assertFalse(EstablishmentManager.isTerminalOutboundState(OB_STATE_NEEDS_TOKEN));
        assertFalse(EstablishmentManager.isTerminalOutboundState(OB_STATE_REQUEST_SENT));
        assertFalse(EstablishmentManager.isTerminalOutboundState(OB_STATE_CONFIRMED_PARTIALLY));
    }

    @Test
    public void testHasObMessageTimedOut() {
        // never sent: never flagged regardless of age
        assertFalse(EstablishmentManager.hasObMessageTimedOut(0, 30000L, 500000L));
        // before the timeout elapses: not timed out
        assertFalse(EstablishmentManager.hasObMessageTimedOut(1000L, 30000L, 30000L));
        // exactly at sent + timeout: timed out (boundary)
        assertTrue(EstablishmentManager.hasObMessageTimedOut(1000L, 30000L, 31000L));
        // well past the timeout
        assertTrue(EstablishmentManager.hasObMessageTimedOut(1000L, 30000L, 40000L));
    }

    @Test
    public void testShouldFailObState() {
        // hard lifetime expiry wins regardless of send times
        assertTrue(EstablishmentManager.shouldFailObState(true, 1000L, 30000L, 2000L));
        // not expired and not timed out: keep going
        assertFalse(EstablishmentManager.shouldFailObState(false, 1000L, 30000L, 2000L));
        assertFalse(EstablishmentManager.shouldFailObState(false, 0L, 30000L, 500000L));
        // not expired but a message has gone unanswered past the timeout
        assertTrue(EstablishmentManager.shouldFailObState(false, 1000L, 30000L, 31000L));
    }

    @Test
    public void testIsSendDue() {
        assertFalse(EstablishmentManager.isSendDue(1000L, 999L));
        assertTrue(EstablishmentManager.isSendDue(1000L, 1000L));
        assertTrue(EstablishmentManager.isSendDue(1000L, 2000L));
    }

    @Test
    public void testHasInboundEstablishExpired() {
        // before the overall cap: not expired, whatever phase
        assertFalse(EstablishmentManager.hasInboundEstablishExpired(4000L, false, 5000L, 5000L));
        assertFalse(EstablishmentManager.hasInboundEstablishExpired(4000L, true, 5000L, 5000L));
        // past the overall cap: always expired
        assertTrue(EstablishmentManager.hasInboundEstablishExpired(5001L, false, 5000L, 5000L));
        assertTrue(EstablishmentManager.hasInboundEstablishExpired(9000L, true, 5000L, 5000L));
        // retry-sent limit only applies to retry-sent states
        // boundary: exactly at the retry limit expires a retry-sent state
        assertTrue(EstablishmentManager.hasInboundEstablishExpired(5000L, true, 5000L, 5000L));
        assertFalse(EstablishmentManager.hasInboundEstablishExpired(4999L, true, 5000L, 5000L));
        // a non-retry state past the retry-sent cap but under the overall cap survives
        assertFalse(EstablishmentManager.hasInboundEstablishExpired(4999L, false, 5000L, 5000L));
    }

    @Test
    public void testIsBobRelayReject() {
        assertFalse(EstablishmentManager.isBobRelayReject(0));
        assertTrue(EstablishmentManager.isBobRelayReject(1));
        assertTrue(EstablishmentManager.isBobRelayReject(63));
        assertFalse(EstablishmentManager.isBobRelayReject(64));
        assertFalse(EstablishmentManager.isBobRelayReject(127));
    }

    @Test
    public void testRelayResponseIntroState() {
        assertEquals(IntroState.INTRO_STATE_SUCCESS,
                     EstablishmentManager.relayResponseIntroState(0));
        assertEquals(IntroState.INTRO_STATE_BOB_REJECT,
                     EstablishmentManager.relayResponseIntroState(1));
        assertEquals(IntroState.INTRO_STATE_BOB_REJECT,
                     EstablishmentManager.relayResponseIntroState(63));
        assertEquals(IntroState.INTRO_STATE_CHARLIE_REJECT,
                     EstablishmentManager.relayResponseIntroState(64));
        assertEquals(IntroState.INTRO_STATE_CHARLIE_REJECT,
                     EstablishmentManager.relayResponseIntroState(127));
    }

    @Test
    public void testIsBadRelayDataAddress() {
        // all checks pass: address is fine
        assertFalse(EstablishmentManager.isBadRelayDataAddress(true, true, false, false, false));
        // each individual failure rejects the address
        assertTrue(EstablishmentManager.isBadRelayDataAddress(false, true, false, false, false));
        assertTrue(EstablishmentManager.isBadRelayDataAddress(true, false, false, false, false));
        assertTrue(EstablishmentManager.isBadRelayDataAddress(true, true, true, false, false));
        assertTrue(EstablishmentManager.isBadRelayDataAddress(true, true, false, true, false));
        assertTrue(EstablishmentManager.isBadRelayDataAddress(true, true, false, false, true));
    }

    @Test
    public void testHasValidV2Introducer() {
        // no introducers advertised: none valid
        assertFalse(EstablishmentManager.hasValidV2Introducer(ssu2Addr(), 1000L));
        // expiration 0 means never expires
        UDPAddress unexpired = ssu2Addr("1234", 0L);
        assertTrue(EstablishmentManager.hasValidV2Introducer(unexpired, 1000L));
        // future expiration is valid (UDPAddress stores exp as seconds * 1000)
        UDPAddress future = ssu2Addr("1234", 5000L);
        assertTrue(EstablishmentManager.hasValidV2Introducer(future, 1000L));
        // past expiration is not
        UDPAddress expired = ssu2Addr("1234", 1L);
        assertFalse(EstablishmentManager.hasValidV2Introducer(expired, 1001L));
    }

    @Test
    public void testIsMtuTooSmall() {
        // unknown MTUs (0) pass
        assertFalse(EstablishmentManager.isMtuTooSmall(0, 0));
        // peer MTU below minimum fails; at the boundary it passes
        assertTrue(EstablishmentManager.isMtuTooSmall(PeerState2.MIN_MTU - 1, 0));
        assertFalse(EstablishmentManager.isMtuTooSmall(PeerState2.MIN_MTU, 0));
        // our MTU below minimum fails
        assertTrue(EstablishmentManager.isMtuTooSmall(0, PeerState2.MIN_MTU - 1));
        assertFalse(EstablishmentManager.isMtuTooSmall(0, PeerState2.MIN_MTU));
        // either side failing is enough
        assertTrue(EstablishmentManager.isMtuTooSmall(PeerState2.MIN_MTU - 1, PeerState2.MIN_MTU));
        assertTrue(EstablishmentManager.isMtuTooSmall(PeerState2.MIN_MTU, PeerState2.MIN_MTU - 1));
    }

    /**
     *  An SSU2 address with no introducers, or with a single hash introducer
     *  whose tag and (optional) expiration are given.
     */
    private static UDPAddress ssu2Addr(String tag, long exp) {
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty(RouterAddress.PROP_HOST, "9.9.9.9");
        opts.setProperty(RouterAddress.PROP_PORT, "12345");
        if (tag != null) {
            Hash h = Hash.create(new byte[Hash.HASH_LENGTH]);
            opts.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + "0", tag);
            opts.setProperty(UDPAddress.PROP_INTRO_HASH_PREFIX + "0", Base64.encode(h.getData()));
            if (exp > 0)
                opts.setProperty(UDPAddress.PROP_INTRO_EXP_PREFIX + "0", Long.toString(exp));
        }
        return new UDPAddress(new RouterAddress("SSU2", opts, 5));
    }

    private static UDPAddress ssu2Addr() {return ssu2Addr(null, 0L);}
}