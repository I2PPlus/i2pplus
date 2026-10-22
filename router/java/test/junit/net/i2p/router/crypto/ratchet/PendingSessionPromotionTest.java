package net.i2p.router.crypto.ratchet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.southernstorm.noise.protocol.HandshakeState;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Picking which pending session a New Session Reply promotes (C-5). The pick
 * happens under the pending-sessions map monitor, which is one lock for every
 * NSR the router handles, so it has to be a map operation and nothing else -
 * the key derivations and tagset inserts that follow run outside it. What the
 * pick must guarantee is that exactly one reply can promote a given handshake:
 * the winner leaves the queue immediately, and anything left behind is stale.
 */
public class PendingSessionPromotionTest {

    /**
     * The matching session comes out of the queue; the others stay.
     */
    @Test
    public void testTheMatchIsDetached() {
        HandshakeState s1 = mock(HandshakeState.class);
        HandshakeState s2 = mock(HandshakeState.class);
        HandshakeState s3 = mock(HandshakeState.class);
        List<RatchetSKM.OutboundSession> pending =
            new ArrayList<RatchetSKM.OutboundSession>(Arrays.asList(pending(s1), pending(s2), pending(s3)));

        RatchetSKM.OutboundSession got = RatchetSKM.takePendingFor(pending, s2);

        assertNotNull(got);
        assertEquals(2, pending.size());
        assertSame(pending.get(0).getHandshakeState(), s1);
        assertSame(pending.get(1).getHandshakeState(), s3);
    }

    /**
     * A late or duplicate NSR for a handshake that is no longer queued promotes
     * nothing and must not disturb the queue - the sessions in it belong to a
     * different handshake that is still waiting for its own reply.
     */
    @Test
    public void testNoMatchLeavesTheQueueAlone() {
        HandshakeState queued = mock(HandshakeState.class);
        HandshakeState gone = mock(HandshakeState.class);
        List<RatchetSKM.OutboundSession> pending =
            new ArrayList<RatchetSKM.OutboundSession>(Arrays.asList(pending(queued)));

        assertNull(RatchetSKM.takePendingFor(pending, gone));
        assertEquals(1, pending.size());
        assertSame(queued, pending.get(0).getHandshakeState());
    }

    /**
     * Two queue entries for one handshake should not both be promoted: the
     * first wins and the second is left in the queue for the caller to destroy
     * as stale.
     */
    @Test
    public void testASecondEntryForOneHandshakeIsLeftBehind() {
        HandshakeState same = mock(HandshakeState.class);
        RatchetSKM.OutboundSession first = pending(same);
        RatchetSKM.OutboundSession second = pending(same);
        List<RatchetSKM.OutboundSession> pending =
            new ArrayList<RatchetSKM.OutboundSession>(Arrays.asList(first, second));

        RatchetSKM.OutboundSession got = RatchetSKM.takePendingFor(pending, same);

        assertSame(first, got);
        assertEquals(1, pending.size());
        assertSame(second, pending.get(0));
    }

    /**
     * Nothing queued, nothing to promote.
     */
    @Test
    public void testEmptyQueue() {
        assertNull(RatchetSKM.takePendingFor(new ArrayList<RatchetSKM.OutboundSession>(),
                                             mock(HandshakeState.class)));
    }

    /**
     * @param state the handshake state the stub reports
     * @return a pending session carrying that state
     */
    private static RatchetSKM.OutboundSession pending(HandshakeState state) {
        RatchetSKM.OutboundSession sess = mock(RatchetSKM.OutboundSession.class);
        when(sess.getHandshakeState()).thenReturn(state);
        return sess;
    }
}
