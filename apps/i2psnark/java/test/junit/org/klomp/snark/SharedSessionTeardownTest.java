package org.klomp.snark;

import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the single-destination session teardown guard.
 *
 * <p>The defect: {@link Snark#teardownSession()} called {@link I2PSnarkUtil#disconnect()}
 * whenever any torrent stopped in single-dest mode, because every torrent shares one I2CP
 * session. Trackers such as zzzot submit throwaway lookup magnets and delete them minutes later;
 * each deletion destroyed the shared session, so every running torrent was forced onto a fresh
 * destination with a from-scratch tunnel pool. Observed as the pool building, collapsing and
 * rebuilding on a few-minute cadence.
 *
 * @since 0.9.71+
 */
public class SharedSessionTeardownTest {

    /**
     * A PeerCoordinator is not cheaply constructible (it wants a live I2PSnarkUtil), and
     * {@code hasOtherRunning} reads only {@code halted()}, so a mock is enough and keeps the
     * test free of router context.
     */
    private static int _seq = 0;

    private static PeerCoordinator coordinator(boolean halted) {
        PeerCoordinator pc = Mockito.mock(PeerCoordinator.class);
        Mockito.when(pc.halted()).thenReturn(halted);
        // PeerCoordinatorSet keys by infohash; mocks return null, so stub a distinct one per
        // instance or every coordinator would collide on the same map entry.
        byte[] ih = new byte[20];
        ih[0] = (byte) (_seq++);
        Mockito.when(pc.getInfoHash()).thenReturn(ih);
        return pc;
    }

    // ---- hasOtherRunning ----

    /** The regression: a second live torrent must keep the shared session alive. */
    @Test
    public void anotherRunningTorrentKeepsTheSession() {
        PeerCoordinatorSet pcs = new PeerCoordinatorSet();
        PeerCoordinator mine = coordinator(false);
        pcs.add(mine);
        pcs.add(coordinator(false));
        assertTrue(pcs.hasOtherRunning(mine));
    }

    /**
     * The teardown removes itself from the set before asking, so a lone torrent must be free to
     * close the session - otherwise stopping the last torrent would strand it.
     */
    @Test
    public void lastTorrentMayCloseTheSession() {
        PeerCoordinatorSet pcs = new PeerCoordinatorSet();
        PeerCoordinator mine = coordinator(false);
        pcs.add(mine);
        assertFalse(pcs.hasOtherRunning(mine));
    }

    /** A stopped sibling is not a user, so it must not pin the session open. */
    @Test
    public void haltedSiblingDoesNotKeepTheSession() {
        PeerCoordinatorSet pcs = new PeerCoordinatorSet();
        PeerCoordinator mine = coordinator(false);
        pcs.add(mine);
        pcs.add(coordinator(true));
        assertFalse(pcs.hasOtherRunning(mine));
    }

    /**
     * The zzzot shape: a user torrent plus a transient lookup that is being torn down. The
     * lookup must not be able to drop the session the user torrent depends on.
     */
    @Test
    public void tearingDownALookupDoesNotStrandAUserTorrent() {
        PeerCoordinatorSet pcs = new PeerCoordinatorSet();
        PeerCoordinator lookup = coordinator(false);
        pcs.add(lookup);
        pcs.add(coordinator(false)); // the user's torrent
        assertTrue("lookup teardown must not disconnect the shared session", pcs.hasOtherRunning(lookup));
    }

    /** An empty set has no other user. */
    @Test
    public void emptySetHasNoOtherUser() {
        assertFalse(new PeerCoordinatorSet().hasOtherRunning(null));
    }

    /**
     * Exclusion is by identity, so asking about one coordinator must not hide the other. Both
     * torrents are live, so each sees the other as a reason to keep the session.
     */
    @Test
    public void eachCoordinatorExcludesOnlyItself() {
        PeerCoordinatorSet pcs = new PeerCoordinatorSet();
        PeerCoordinator mine = coordinator(false);
        PeerCoordinator other = coordinator(false);
        pcs.add(mine);
        pcs.add(other);
        assertTrue(pcs.hasOtherRunning(mine));
        assertTrue(pcs.hasOtherRunning(other));
    }

    /** A coordinator absent from the set still gets a truthful answer: nothing else is live. */
    @Test
    public void unknownCoordinatorIsTreatedAsTheOnlyOne() {
        PeerCoordinatorSet pcs = new PeerCoordinatorSet();
        PeerCoordinator present = coordinator(false);
        pcs.add(present);
        assertTrue("the registered torrent is still a user", pcs.hasOtherRunning(coordinator(false)));
    }
}
