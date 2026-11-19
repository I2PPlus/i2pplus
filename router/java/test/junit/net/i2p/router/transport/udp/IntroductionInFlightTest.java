package net.i2p.router.transport.udp;

import static net.i2p.router.transport.udp.OutboundEstablishState2.IntroState.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.KeyCertificate;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.transport.udp.OutboundEstablishState2.IntroState;
import net.i2p.util.OrderedProperties;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * An introduction that is still being set up must not be treated as one that
 * failed.
 *
 * <p>The defect: {@code handlePendingIntro()} counted only a relay request as
 * progress. A RouterInfo lookup it had just sent stamped the introduction
 * deadline as if a relay request had gone out, and a connection to an introducer
 * that was still in flight left the per-introducer state at HAS_RI, which the
 * pass then read as a bad RouterInfo and rejected. With nothing left to do the
 * pass expired the whole establishment, failed every queued message and marked
 * the peer unreachable - for work we had started a moment earlier.
 *
 * @since 0.9.72+
 */
public class IntroductionInFlightTest {

    /** Context for the manager's clock, netDb, random and stats. */
    private static RouterContext _ctx;

    /** The transport the manager reports failures to. */
    private UDPTransport _transport;

    /** The manager under test. */
    private EstablishmentManager _manager;

    /** The peer we are trying to introduce to, in every test. */
    private RouterIdentity _charlie;

    /**
     * A fixture group for SSU2 introductions; it holds no state.
     *
     * @since 0.9.72+
     */
    public IntroductionInFlightTest() {}

    /**
     * A context with a dummy netDb, so an introducer RouterInfo can be stored and
     * read back.
     */
    @BeforeClass
    public static void initContext() {
        _ctx = RouterTestHelper.newContext();
        assertNotNull(_ctx);
    }

    /**
     * A manager over a mocked transport, so every failure it reports is visible
     * to the assertions.
     */
    @Before
    public void setUp() {
        _transport = mock(UDPTransport.class);
        _manager = new EstablishmentManager(_ctx, _transport);
        _charlie = identity(1);
    }

    /**
     * Drops the manager and its live introductions between tests.
     */
    @After
    public void tearDown() {
        _manager = null;
        _transport = null;
    }

    /**
     * The set of per-introducer states that mean work is outstanding. Everything
     * else - rejected, failed, expired, disconnected, us, success, invalid - is
     * settled, and only a settled list may expire the attempt.
     */
    @Test
    public void testOnlyUnfinishedStatesCountAsInFlight() {
        IntroState[] inFlight = {INTRO_STATE_LOOKUP_SENT, INTRO_STATE_HAS_RI,
                                 INTRO_STATE_CONNECTING, INTRO_STATE_CONNECTED,
                                 INTRO_STATE_RELAY_REQUEST_SENT,
                                 INTRO_STATE_RELAY_CHARLIE_ACCEPTED};
        for (IntroState state : IntroState.values()) {
            boolean expected = false;
            for (IntroState inflight : inFlight) {
                if (state == inflight) {expected = true;}
            }
            assertEquals("wrong in-flight verdict for " + state, expected,
                         EstablishmentManager.isIntroInFlight(state));
        }
    }

    /**
     * The defect, lookup half: an introducer RouterInfo we do not have means a
     * lookup, not a relay request, so the introduction deadline must not start -
     * the lookup has a 10-second timeout and the deadline is 2.5s.
     */
    @Test
    public void testAPendingLookupDoesNotStartTheDeadline() {
        Hash bob = hash(2);
        OutboundEstablishState2 state = state(charlieAddress(bob), null);
        _manager.handlePendingIntro(state);
        assertEquals("the introducer was not queued for a lookup",
                     INTRO_STATE_LOOKUP_SENT, state.getIntroState(bob));
        assertEquals("a lookup started the introduction deadline", 0, state.getIntroSentTime());
        verify(_transport, never()).markUnreachable(any(Hash.class));
    }

    /**
     * The defect, connection half: an introducer we already hold a RouterInfo
     * for, whose connection is still in flight, was rejected by the same pass,
     * and the establishment expired with it.
     *
     * @throws Exception if the manager's map of attempts in flight cannot be read
     */
    @Test
    public void testAPendingConnectionIsNotRejected() throws Exception {
        Hash bob = hash(3);
        byte[] bobIP = new byte[] {5, 6, 7, 8};
        int bobPort = 21222;
        RouterInfo bobRI = new RouterInfo();
        bobRI.setIdentity(identity(3));
        _ctx.netDb().store(bob, bobRI);
        when(_transport.getTargetAddresses(bobRI)).thenReturn(
            Collections.singletonList(udpAddress(bobIP, bobPort)));
        // The connection attempt already in flight for that endpoint.
        OutboundEstablishState2 attempt = state(charlieAddress(hash(4)), null);
        outboundStates().put(new RemoteHostId(bobIP, bobPort), attempt);

        OutboundEstablishState2 state = state(charlieAddress(bob), null);
        state.setIntroState(bob, INTRO_STATE_HAS_RI);
        _manager.handlePendingIntro(state);

        assertEquals("the introducer's connection was read as a rejection",
                     INTRO_STATE_CONNECTING, state.getIntroState(bob));
        verify(_transport, never()).markUnreachable(any(Hash.class));
    }

    /**
     * With every introducer settled - here, a RouterInfo with no usable address -
     * the attempt does expire, and the peer is reported unreachable.
     */
    @Test
    public void testNothingInFlightExpiresTheAttempt() {
        Hash bob = hash(5);
        RouterInfo bobRI = new RouterInfo();
        bobRI.setIdentity(identity(5));
        _ctx.netDb().store(bob, bobRI);
        when(_transport.getTargetAddresses(bobRI))
            .thenReturn(Collections.<RouterAddress>emptyList());

        OutboundEstablishState2 state = state(charlieAddress(bob), null);
        state.setIntroState(bob, INTRO_STATE_HAS_RI);
        _manager.handlePendingIntro(state);

        assertEquals(INTRO_STATE_REJECTED, state.getIntroState(bob));
        verify(_transport).markUnreachable(any(Hash.class));
    }

    /**
     * Rescheduling an in-flight introduction must not start the deadline, and once
     * a relay request is out there it must not push the next pass past the
     * deadline that request already has.
     */
    @Test
    public void testReschedulingLeavesTheDeadlineAlone() {
        Hash bob = hash(6);
        OutboundEstablishState2 state = state(charlieAddress(bob), null);
        long now = _ctx.clock().now();
        state.introductionPending();
        assertEquals(0, state.getIntroSentTime());
        assertTrue(state.getNextSendTime() >= now + OutboundEstablishState.RETRANSMIT_DELAY);

        state.introSent(bob);
        long deadline = state.getIntroSentTime();
        assertTrue(deadline > 0);
        state.introductionPending();
        assertEquals("rescheduling moved the introduction deadline",
                     deadline, state.getIntroSentTime());
        assertTrue(state.getNextSendTime() <= deadline + EstablishmentManager.OB_MESSAGE_TIMEOUT);
    }

    /**
     * @param b a byte differing the identities from each other
     * @return a router identity whose hash computes
     */
    private static RouterIdentity identity(int b) {
        RouterIdentity id = new RouterIdentity();
        byte[] pk = new byte[PublicKey.KEYSIZE_BYTES];
        pk[0] = (byte) b;
        id.setPublicKey(new PublicKey(pk));
        byte[] sig = new byte[SigningPublicKey.KEYSIZE_BYTES];
        sig[0] = (byte) (b + 1);
        SigningPublicKey spk = new SigningPublicKey(sig);
        id.setSigningPublicKey(spk);
        id.setPadding(new byte[128 - SigningPublicKey.KEYSIZE_BYTES]);
        id.setCertificate(new KeyCertificate(spk));
        return id;
    }

    /**
     * @param b a byte differing the hashes from each other
     * @return a hash that is neither the zero hash the test context's router uses
     *         nor another test's
     */
    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        return Hash.create(data);
    }

    /**
     * @param intros the introducers to advertise, by hash
     * @return an SSU2 router address for 9.9.9.9:12345 naming them
     */
    private static RouterAddress charlieAddress(Hash... intros) {
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty(RouterAddress.PROP_HOST, "9.9.9.9");
        opts.setProperty(RouterAddress.PROP_PORT, "12345");
        for (int i = 0; i < intros.length; i++) {
            opts.setProperty(UDPAddress.PROP_INTRO_TAG_PREFIX + i, "1234");
            String b64 = Base64.encode(intros[i].getData());
            opts.setProperty(UDPAddress.PROP_INTRO_HASH_PREFIX + i, b64);
        }
        return new RouterAddress("SSU2", opts, 5);
    }

    /**
     * @param ip the endpoint's address
     * @param port the endpoint's port
     * @return an SSU2 router address for that endpoint
     */
    private static RouterAddress udpAddress(byte[] ip, int port) {
        OrderedProperties opts = new OrderedProperties();
        StringBuilder host = new StringBuilder();
        for (int i = 0; i < ip.length; i++) {
            if (i > 0) {host.append('.');}
            host.append(ip[i] & 0xff);
        }
        opts.setProperty(RouterAddress.PROP_HOST, host.toString());
        opts.setProperty(RouterAddress.PROP_PORT, Integer.toString(port));
        return new RouterAddress("SSU2", opts, 7);
    }

    /**
     * @param ra the address of the peer being introduced to, naming its introducers
     * @param claimed our endpoint for this attempt, null when the introducers
     *                stand in for one
     * @return a new outbound establishment over that address
     */
    private OutboundEstablishState2 state(RouterAddress ra, RemoteHostId claimed) {
        UDPAddress addr = new UDPAddress(ra);
        RemoteHostId rhid = new RemoteHostId(ra.getIP(), ra.getPort());
        return new OutboundEstablishState2(_ctx, _transport, claimed, rhid, _charlie, true,
                                           new SessionKey(new byte[32]), ra, addr, 2);
    }

    /**
     * The manager's map of attempts in flight, reached by reflection so a test can
     * put one there without driving the whole handshake.
     *
     * @return the live map, mutable
     * @throws Exception if the field cannot be read
     */
    @SuppressWarnings("unchecked")
    private Map<RemoteHostId, OutboundEstablishState> outboundStates() throws Exception {
        Field f = EstablishmentManager.class.getDeclaredField("_outboundStates");
        f.setAccessible(true);
        return (Map<RemoteHostId, OutboundEstablishState>) f.get(_manager);
    }
}
