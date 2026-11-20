package net.i2p.router.transport.udp;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static net.i2p.router.transport.udp.OutboundEstablishState.OutboundState.OB_STATE_INTRODUCED;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A hole punch moves an attempt onto the endpoint it really came from, but only
 * onto an endpoint nobody else already holds.
 *
 * <p>The defect: the re-point removed the attempt from the map under its old key
 * and then wrote the new key with a plain put. While we are also establishing to
 * the mapped address directly, another live attempt is already keyed there, and
 * the put replaced it - silently. The displaced attempt stayed in the byHash and
 * byClaimedAddress maps, so lookups still found it and then missed the
 * handshake, and it was reaped only by the failsafe sweep, twice the maximum
 * establishment time after it stopped being reachable.
 *
 * <p>No router context beyond the test helper's is needed; the maps are reached
 * reflectively and the states are mocked.
 *
 * @since 0.9.72+
 */
public class HolePunchEndpointCollisionTest {

    /** The peer's address, the same for the named and the mapped endpoint. */
    private static final byte[] IP = new byte[] {(byte) 192, 0, 2, 1};

    /** The port the relay response named, which our attempt is keyed to. */
    private static final int NAMED_PORT = 1234;

    /** The port the peer's NAT really mapped, which the punch came from. */
    private static final int MAPPED_PORT = 5678;

    /** Receipt time for the punch. */
    private static final long NOW = 1_700_000_000_000L;

    /** The token from the end of the relay response. */
    private static final long TOKEN = 0x2345L;

    /** Context for the manager's clock and stats. */
    private static RouterContext _ctx;

    /** The transport the manager reports failures to. */
    private UDPTransport _transport;

    /** The manager under test. */
    private EstablishmentManager _manager;

    /**
     * A fixture group for the re-point's endpoint collision; it holds no state.
     *
     * @since 0.9.72+
     */
    public HolePunchEndpointCollisionTest() {}

    /**
     * A plain context, enough for the manager's clock and rate data.
     */
    @BeforeClass
    public static void initContext() {
        _ctx = RouterTestHelper.newContext();
        assertNotNull(_ctx);
    }

    /**
     * A manager over a mocked transport.
     */
    @Before
    public void setUp() {
        _transport = mock(UDPTransport.class);
        _manager = new EstablishmentManager(_ctx, _transport);
    }

    /**
     * Drops the manager and its maps between tests.
     */
    @After
    public void tearDown() {
        _manager = null;
        _transport = null;
    }

    /**
     * An attempt keyed to the port the relay response named, far enough along to
     * be re-pointable and no further. The SSU2 half is the same object, as it is
     * in the transport, and moving the endpoint moves the id the attempt is
     * keyed by - so the maps and the attempt agree about where it lives, before
     * and after.
     *
     * @return the attempt a punch is being applied to
     */
    private OutboundEstablishState2 attempt() {
        OutboundEstablishState2 state = mock(OutboundEstablishState2.class);
        final RemoteHostId[] id = {new RemoteHostId(IP, NAMED_PORT)};
        when(state.getRemoteHostId()).thenAnswer(inv -> id[0]);
        doAnswer(inv -> {
            id[0] = new RemoteHostId(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(state).holePunchAddress(any(), anyInt());
        when(state.getState()).thenReturn(OB_STATE_INTRODUCED);
        return state;
    }

    /**
     * The punch, applied.
     *
     * @param state the attempt being re-pointed, also its SSU2 half
     * @throws Exception if the private re-point cannot be reached
     */
    private void punch(OutboundEstablishState2 state) throws Exception {
        Method m = EstablishmentManager.class.getDeclaredMethod("repointState",
                       OutboundEstablishState.class, OutboundEstablishState2.class,
                       byte[].class, int.class, long.class, long.class);
        m.setAccessible(true);
        m.invoke(_manager, state, state, IP, MAPPED_PORT, NOW, TOKEN);
    }

    /**
     * With the endpoint free, the attempt leaves the named port and is keyed to
     * the mapped one the punch came from - the whole point of the re-point.
     *
     * @throws Exception if the private re-point or the maps cannot be reached
     */
    @Test
    public void aFreeEndpointTakesTheAttempt() throws Exception {
        OutboundEstablishState2 state = attempt();
        Map<RemoteHostId, OutboundEstablishState> byEndpoint = outboundStates();
        byEndpoint.put(new RemoteHostId(IP, NAMED_PORT), state);

        punch(state);

        verify(state).holePunchAddress(IP, MAPPED_PORT);
        assertSame("the attempt was not keyed to the punched endpoint",
                   state, byEndpoint.get(new RemoteHostId(IP, MAPPED_PORT)));
        assertNull("the attempt stayed keyed to the named endpoint",
                   byEndpoint.get(new RemoteHostId(IP, NAMED_PORT)));
    }

    /**
     * The defect: another live attempt already keyed at the mapped endpoint kept
     * it, and ours stayed where it was. Pre-fix the put replaced the other
     * attempt, so it was gone from this map while still sitting in the byHash and
     * byClaimedAddress maps, found by lookups that then missed its handshake.
     *
     * @throws Exception if the private re-point or the maps cannot be reached
     */
    @Test
    public void aHeldEndpointIsNotTaken() throws Exception {
        OutboundEstablishState2 state = attempt();
        OutboundEstablishState incumbent = mock(OutboundEstablishState.class);
        Map<RemoteHostId, OutboundEstablishState> byEndpoint = outboundStates();
        byEndpoint.put(new RemoteHostId(IP, NAMED_PORT), state);
        byEndpoint.put(new RemoteHostId(IP, MAPPED_PORT), incumbent);

        punch(state);

        verify(state, never()).holePunchAddress(any(), anyInt());
        assertSame("the attempt already at the punched endpoint was displaced",
                   incumbent, byEndpoint.get(new RemoteHostId(IP, MAPPED_PORT)));
        assertSame("the attempt was moved off the named endpoint anyway",
                   state, byEndpoint.get(new RemoteHostId(IP, NAMED_PORT)));
    }

    /**
     * A punch that repeats for our own attempt is not a collision: the attempt
     * already keyed at the mapped endpoint is the one being moved, and it stays
     * keyed there.
     *
     * @throws Exception if the private re-point or the maps cannot be reached
     */
    @Test
    public void ourOwnAttemptIsNotACollision() throws Exception {
        OutboundEstablishState2 state = attempt();
        Map<RemoteHostId, OutboundEstablishState> byEndpoint = outboundStates();
        byEndpoint.put(new RemoteHostId(IP, NAMED_PORT), state);
        byEndpoint.put(new RemoteHostId(IP, MAPPED_PORT), state);

        punch(state);

        verify(state).holePunchAddress(IP, MAPPED_PORT);
        assertSame(state, byEndpoint.get(new RemoteHostId(IP, MAPPED_PORT)));
    }

    /**
     * The manager's live map of attempts by endpoint.
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
