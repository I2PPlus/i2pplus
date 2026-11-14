package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import org.junit.Test;

/**
 * Pins when a peer we were handed is left alone rather than dialed. The tracker, PEX and the
 * DHT re-advertise the same peers every announce round, and a compact answer is often only a
 * destination hash that may never resolve, so each round used to start a connection thread
 * that died on the cached "nothing". The destination lookup's own backoff now paces those
 * re-adds - but only the dials. An inbound connection arrives with its destination in hand and
 * is never deferred, however that same hash was treated on the way out.
 *
 * @since 0.9.72+
 */
public class UnresolvedPeerDecisionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one; the
     * decision under test is static and carries no state.
     */
    public UnresolvedPeerDecisionTest() {}

    /**
     * A generated destination, for the id that arrives with one already attached.
     *
     * @return a destination with a fresh key pair
     * @throws Exception if the keys cannot be generated
     */
    private static Destination destination() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PClientFactory.createClient().createDestination(out);
        return Destination.create(new ByteArrayInputStream(out.toByteArray()));
    }

    /**
     * A dial at a hash-only peer id is deferred while the lookup holds its hash as
     * unresolvable.
     */
    @Test
    public void testAHashOnlyDialIsDeferredWhileTheWaitRunsOut() {
        assertTrue(PeerCoordinator.defersUnresolvedPeer(false, false, 30_000L));
    }

    /**
     * Nothing is deferred once the wait has run out - that is the next attempt, not a skip.
     */
    @Test
    public void testNothingIsDeferredOnceTheWaitHasRunOut() {
        assertFalse(PeerCoordinator.defersUnresolvedPeer(false, false, 0L));
    }

    /**
     * A peer whose id already carries a full destination is dialed whatever the cache says
     * about some earlier attempt at its hash.
     */
    @Test
    public void testAPeerWithADestinationIsNeverDeferred() {
        assertFalse(PeerCoordinator.defersUnresolvedPeer(false, true, 30_000L));
    }

    /**
     * A peer that has finished its handshake is not a dial, so it is not deferred either.
     */
    @Test
    public void testAConnectedPeerIsNotDeferred() {
        assertFalse(PeerCoordinator.defersUnresolvedPeer(true, false, 30_000L));
    }

    /**
     * The hash-only id a compact tracker answer, a PEX reply or the DHT hands us reports no
     * destination and would have to ask the router; an id built from a destination answers
     * without asking, and that is the difference the decision turns on.
     *
     * @throws Exception if the destination's keys cannot be generated
     */
    @Test
    public void testOnlyAHashOnlyIdHasToAskTheRouter() throws Exception {
        PeerID hashOnly = new PeerID(new byte[32], (I2PSnarkUtil) null);
        assertFalse(hashOnly.hasDestination());
        assertNull(hashOnly.getAddress());

        PeerID full = new PeerID(new byte[20], destination());
        assertTrue(full.hasDestination());
    }
}
