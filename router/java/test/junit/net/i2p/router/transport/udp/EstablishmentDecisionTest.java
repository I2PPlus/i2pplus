package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.security.GeneralSecurityException;

import net.i2p.data.Hash;
import net.i2p.router.Banlist;
import net.i2p.router.Blocklist;
import net.i2p.router.transport.udp.OutboundEstablishState2.IntroState;
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
    public void testIsBanlistedAnyTier() {
        Hash h = Hash.create(new byte[Hash.HASH_LENGTH]);
        Banlist plain = mock(Banlist.class);
        when(plain.isBanlisted(h)).thenReturn(Boolean.TRUE);
        assertTrue(EstablishmentManager.isBanlisted(plain, h));

        Banlist hostile = mock(Banlist.class);
        when(hostile.isBanlistedHostile(h)).thenReturn(Boolean.TRUE);
        assertTrue(EstablishmentManager.isBanlisted(hostile, h));

        Banlist forever = mock(Banlist.class);
        when(forever.isBanlistedForever(h)).thenReturn(Boolean.TRUE);
        assertTrue(EstablishmentManager.isBanlisted(forever, h));
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
        assertFalse(EstablishmentManager.isUsableUDPAddress(null, 80));
        assertFalse(EstablishmentManager.isUsableUDPAddress(addr, 0));
        assertFalse(EstablishmentManager.isUsableUDPAddress(addr, -1));
        assertFalse(EstablishmentManager.isUsableUDPAddress(addr, 65536));
        assertTrue(EstablishmentManager.isUsableUDPAddress(addr, 1));
        assertTrue(EstablishmentManager.isUsableUDPAddress(addr, 65535));
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
}