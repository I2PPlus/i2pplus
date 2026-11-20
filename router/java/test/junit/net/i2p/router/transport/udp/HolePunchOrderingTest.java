package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_CONFIRMED_COMPLETELY;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_CONFIRMED_PARTIALLY;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_CREATED_RECEIVED;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_INTRODUCED;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_NEEDS_TOKEN;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_PENDING_INTRO;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_REQUEST_SENT;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_TOKEN_REQUEST_SENT;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_REQUEST_SENT_NEW_TOKEN;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_RETRY_RECEIVED;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_UNKNOWN;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_VALIDATION_FAILED;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Tests how an introduced handshake is matched to the HolePunch that proves its
 * endpoint, and how far that endpoint may still be corrected.
 *
 * <p>The attempt is keyed twice: by the address the introducer relayed on our
 * behalf, and by the nonce of the introduction. A NAT that maps one source port
 * per destination puts Charlie's punch on a port the relay response never named,
 * so the endpoint key misses and only the nonce can find the attempt - which
 * means the accepted response must not spend the nonce, and the lookup must not
 * spend it either, because the HolePunch's encryption key is published and a
 * spoofed punch that guessed the nonce would otherwise delete the introduction
 * the real one still needs.
 *
 * <p>No router context is required; the maps are plain ones and the states are
 * mocked.
 *
 * @since 0.9.72+
 */
public class HolePunchOrderingTest {

    /** The peer's address, the same for the named and the mapped endpoint. */
    private static final byte[] IP = new byte[] {(byte) 192, 0, 2, 1};

    /** The introduction nonce the punch echoes back. */
    private static final long NONCE = 0x1234L;

    /**
     * A fixture group for the hole-punch ordering; it holds no state.
     *
     * @since 0.9.72+
     */
    public HolePunchOrderingTest() {}

    /**
     * Only Charlie's rejection spends the live introduction. An accepted
     * response leaves it for the HolePunch, and the introducer's own rejection
     * leaves it so another introducer can still be asked.
     */
    @Test
    public void testOnlyCharliesRejectionSpendsTheIntroduction() {
        assertFalse("an accepted response spent the introduction",
                    EstablishmentManager.endsIntroduction(0));
        assertFalse("an introducer rejection spent the introduction",
                    EstablishmentManager.endsIntroduction(1));
        assertFalse("the last introducer rejection spent the introduction",
                    EstablishmentManager.endsIntroduction(63));
        assertTrue("Charlie's rejection did not spend the introduction",
                   EstablishmentManager.endsIntroduction(64));
        assertTrue("a Charlie rejection above the first code did not spend it",
                   EstablishmentManager.endsIntroduction(127));
    }

    /**
     * A punch that arrives after the accepted relay response, from the port the
     * peer's NAT mapped for us instead of the one the response named, is still
     * matched to the attempt - by nonce, since the endpoint key holds the named
     * address.
     */
    @Test
    public void testLatePunchIsMatchedByNonce() {
        OutboundEstablishState state = mock(OutboundEstablishState.class);
        Map<RemoteHostId, OutboundEstablishState> byEndpoint = new HashMap<>();
        byEndpoint.put(new RemoteHostId(IP, 1234), state);
        Map<Long, OutboundEstablishState> byNonce = new HashMap<>();
        byNonce.put(Long.valueOf(NONCE), state);
        RemoteHostId mapped = new RemoteHostId(IP, 5678);
        assertEquals(state, EstablishmentManager.findIntroduction(byEndpoint, byNonce, mapped, NONCE));
    }

    /**
     * Matching takes nothing. Until the introducer's signature on the punch has
     * been checked, a spoofed packet carrying a guessed nonce and passing the
     * published introduction key must leave the introduction in place, or
     * Charlie's real punch has nowhere to land.
     */
    @Test
    public void testMatchingLeavesTheIntroductionInPlace() {
        OutboundEstablishState state = mock(OutboundEstablishState.class);
        Map<RemoteHostId, OutboundEstablishState> byEndpoint = new HashMap<>();
        Map<Long, OutboundEstablishState> byNonce = new HashMap<>();
        byNonce.put(Long.valueOf(NONCE), state);
        RemoteHostId from = new RemoteHostId(IP, 5678);
        assertEquals(state, EstablishmentManager.findIntroduction(byEndpoint, byNonce, from, NONCE));
        assertEquals("the nonce lookup consumed the introduction",
                     state, byNonce.get(Long.valueOf(NONCE)));
    }

    /**
     * When the punch does come from the named address, the endpoint key answers
     * first, and a punch that matches neither key matches no attempt at all.
     */
    @Test
    public void testEndpointKeyAnswersFirstAndAnUnknownPunchMatchesNothing() {
        OutboundEstablishState named = mock(OutboundEstablishState.class);
        OutboundEstablishState other = mock(OutboundEstablishState.class);
        RemoteHostId endpoint = new RemoteHostId(IP, 1234);
        Map<RemoteHostId, OutboundEstablishState> byEndpoint = new HashMap<>();
        byEndpoint.put(endpoint, named);
        Map<Long, OutboundEstablishState> byNonce = new HashMap<>();
        byNonce.put(Long.valueOf(NONCE), other);
        assertEquals("the endpoint key did not win",
                     named, EstablishmentManager.findIntroduction(byEndpoint, byNonce, endpoint, NONCE));
        assertNull(EstablishmentManager.findIntroduction(byEndpoint, byNonce,
                                                        new RemoteHostId(IP, 5678), 99L));
        assertNull(EstablishmentManager.findIntroduction(new HashMap<>(), new HashMap<>(), endpoint, NONCE));
    }

    /**
     * The endpoint may be corrected while the handshake is in flight or has not
     * been sent, and not after their SessionCreated has been authenticated: by
     * then the session is keyed by the address, and moving it under the session
     * strands it.
     */
    @Test
    public void testEndpointCorrectionsStopAtAuthentication() {
        assertTrue(EstablishmentManager.canRepointEndpoint(OB_STATE_PENDING_INTRO));
        assertTrue(EstablishmentManager.canRepointEndpoint(OB_STATE_INTRODUCED));
        assertTrue("a session request already on its way could not be re-pointed",
                   EstablishmentManager.canRepointEndpoint(OB_STATE_REQUEST_SENT));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_CREATED_RECEIVED));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_CONFIRMED_PARTIALLY));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_CONFIRMED_COMPLETELY));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_VALIDATION_FAILED));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_UNKNOWN));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_NEEDS_TOKEN));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_TOKEN_REQUEST_SENT));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_RETRY_RECEIVED));
        assertFalse(EstablishmentManager.canRepointEndpoint(OB_STATE_REQUEST_SENT_NEW_TOKEN));
    }

    /**
     * A punch may move an attempt onto an endpoint nothing else holds, and onto
     * the endpoint the attempt already holds - a punch that repeats for our own
     * attempt is not a collision. It may not move it on top of a different
     * attempt: the establishment maps keep one state per endpoint, so the holder
     * would be dropped from the main map while staying in the byHash and
     * byClaimedAddress maps, missing every SessionCreated and Retry aimed at it
     * until the failsafe sweep removes it.
     */
    @Test
    public void testEndpointIsNotTakenByOurOwnAttempt() {
        OutboundEstablishState ours = mock(OutboundEstablishState.class);
        OutboundEstablishState other = mock(OutboundEstablishState.class);
        assertFalse("a free endpoint counted as taken",
                    EstablishmentManager.endpointTaken(null, ours));
        assertFalse("our own attempt counted as someone else's",
                    EstablishmentManager.endpointTaken(ours, ours));
        assertTrue("another attempt did not count as holding the endpoint",
                   EstablishmentManager.endpointTaken(other, ours));
    }
}
