package net.i2p.router.transport.udp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.transport.udp.InboundEstablishState.InboundState;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * An inbound establish attempt that dies before the handshake was cryptographically
 * verified must not cost the endpoint a ban. There the peer has spent nothing we
 * have to defend - a lost token request, a stale token after its NAT mapping
 * changed, or a lost retry - and the ban keeps it from trying again for the whole
 * inbound ban window.
 *
 * @since 0.9.72+
 */
public class InboundEstablishmentRecoveryTest {

    /** Context for the manager's clock, logging and ban log. */
    private static RouterContext _ctx;

    /** The transport the manager reports failures to. */
    private UDPTransport _transport;

    /** The manager under test. */
    private EstablishmentManager _manager;

    /** The endpoint every state in these tests comes from. */
    private RemoteHostId _peer;

    @BeforeClass
    public static void initContext() {
        _ctx = RouterTestHelper.newContext();
        assertNotNull(_ctx);
    }

    @Before
    public void setUp() {
        _transport = mock(UDPTransport.class);
        _manager = new EstablishmentManager(_ctx, _transport);
        _peer = new RemoteHostId(new byte[] {1, 2, 3, 4}, 12345);
    }

    @After
    public void tearDown() {
        _manager = null;
        _transport = null;
    }

    /**
     * A token request that never got a reply: the endpoint must stay connectable.
     *
     * @throws Exception if the private expiry method cannot be reached
     */
    @Test
    public void testLostTokenRequestDoesNotBanEndpoint() throws Exception {
        expire(InboundState.IB_STATE_TOKEN_REQUEST_RECEIVED);
        assertFalse(bans().containsKey(_peer));
    }

    /**
     * A retry we sent that was never answered, still no ban.
     *
     * @throws Exception if the private expiry method cannot be reached
     */
    @Test
    public void testLostRetryDoesNotBanEndpoint() throws Exception {
        expire(InboundState.IB_STATE_RETRY_SENT);
        assertFalse(bans().containsKey(_peer));
    }

    /**
     * A stale token, the usual result of the peer's NAT mapping changing under it.
     *
     * @throws Exception if the private expiry method cannot be reached
     */
    @Test
    public void testStaleTokenDoesNotBanEndpoint() throws Exception {
        expire(InboundState.IB_STATE_REQUEST_BAD_TOKEN_RECEIVED);
        assertFalse(bans().containsKey(_peer));
    }

    /**
     * Once the session was created the DH work is real, so the ban that keeps a
     * flood off the CPU stays.
     *
     * @throws Exception if the private expiry method cannot be reached
     */
    @Test
    public void testSessionCreatedTimeoutStillBans() throws Exception {
        long before = _ctx.clock().now();
        expire(InboundState.IB_STATE_CREATED_SENT);
        Long exp = bans().get(_peer);
        assertNotNull("a verified attempt must still ban the endpoint", exp);
        assertTrue("ban expiry is not the inbound ban window",
                   exp.longValue() > before + 10 * 60 * 1000L);
    }

    /**
     * Same for a partially confirmed session.
     *
     * @throws Exception if the private expiry method cannot be reached
     */
    @Test
    public void testPartialConfirmationStillBans() throws Exception {
        long before = _ctx.clock().now();
        expire(InboundState.IB_STATE_CONFIRMED_PARTIALLY);
        assertTrue(bans().get(_peer).longValue() > before);
    }

    /**
     * Expiring an old attempt must not evict a newer attempt from the same
     * endpoint that arrived while the old one was timing out.
     *
     * @throws Exception if the private expiry method cannot be reached
     */
    @Test
    public void testNewerStateSurvivesOldExpiry() throws Exception {
        InboundEstablishState2 old = state(InboundState.IB_STATE_RETRY_SENT);
        InboundEstablishState2 replacement = state(InboundState.IB_STATE_RETRY_SENT);
        states().put(_peer, replacement);
        processExpired(old);
        assertSame(replacement, states().get(_peer));
        assertFalse(bans().containsKey(_peer));
    }

    /**
     * Runs the expiry of one attempt in the given state and checks the parts that
     * do not depend on the state: the slot is released and the queued messages
     * are failed.
     *
     * @param phase the state the attempt reached before it timed out
     * @throws Exception if the private method cannot be reached
     */
    private void expire(InboundState phase) throws Exception {
        InboundEstablishState2 state = state(phase);
        OutNetMessage queued = mock(OutNetMessage.class);
        when(state.getNextQueuedMessage()).thenReturn(queued, (OutNetMessage) null);
        states().put(_peer, state);
        processExpired(state);
        assertFalse("an expired attempt must release its establishment slot",
                    states().containsKey(_peer));
        verify(_transport).failed(eq(queued), anyString());
    }

    /**
     * A mocked attempt from {@link #_peer} that reached the given state.
     *
     * @param phase the state to report
     * @return the state object
     */
    private InboundEstablishState2 state(InboundState phase) {
        InboundEstablishState2 state = mock(InboundEstablishState2.class);
        when(state.getState()).thenReturn(phase);
        when(state.getRemoteHostId()).thenReturn(_peer);
        return state;
    }

    /**
     * Calls the private expiry handler.
     *
     * @param state the attempt to expire
     * @throws Exception if the method is missing or the call fails
     */
    private void processExpired(InboundEstablishState state) throws Exception {
        Method m = EstablishmentManager.class.getDeclaredMethod("processExpired",
                                                               InboundEstablishState.class);
        m.setAccessible(true);
        m.invoke(_manager, state);
    }

    /**
     * The manager's live inbound attempts.
     *
     * @return the attempt map, keyed by endpoint
     * @throws NoSuchFieldException if the field was renamed
     * @throws IllegalAccessException if the field is not readable
     */
    @SuppressWarnings("unchecked")
    private Map<RemoteHostId, InboundEstablishState> states() throws NoSuchFieldException,
                                                                    IllegalAccessException {
        Field f = EstablishmentManager.class.getDeclaredField("_inboundStates");
        f.setAccessible(true);
        return (Map<RemoteHostId, InboundEstablishState>) f.get(_manager);
    }

    /**
     * The manager's inbound ban map.
     *
     * @return the ban map, endpoint to expiry time
     * @throws NoSuchFieldException if the field was renamed
     * @throws IllegalAccessException if the field is not readable
     */
    @SuppressWarnings("unchecked")
    private Map<RemoteHostId, Long> bans() throws NoSuchFieldException, IllegalAccessException {
        Field f = EstablishmentManager.class.getDeclaredField("_inboundBans");
        f.setAccessible(true);
        return (Map<RemoteHostId, Long>) f.get(_manager);
    }
}
