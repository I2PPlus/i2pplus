package org.klomp.snark;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Tests for <code>PeerCoordinator.resortWanted</code> and
 * <code>PeerCoordinator.reshuffleWanted</code>, the two orderings of the
 * wanted-piece set. The set is a LinkedHashSet rather than an ArrayList so that
 * completing a piece removes it by hash in O(1) instead of an O(n) scan and
 * shift under the monitor the request path iterates, so these two rebuilds are
 * what establish the order the request path walks.
 *
 * @since 0.9.72+
 */
public class WantedPiecesOrderTest {

    /**
     * A fixture group for the wanted-piece orderings; it holds no state.
     */
    public WantedPiecesOrderTest() {}

    /**
     * Credit a piece with n peers, for the rarity term of <code>Piece.compareTo</code>.
     * The peer set is private and only reachable through <code>addPeer(Peer)</code>,
     * which needs a live peer, so the set is filled directly.
     *
     * @param piece the piece to make rarer
     * @param n how many peers to record as having it
     * @throws Exception if the private peer set is not readable, so the fixture
     *                   cannot be built and the rarity ordering is untestable
     */
    private static void withPeers(Piece piece, int n) throws Exception {
        Field f = Piece.class.getDeclaredField("peers");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<PeerID> peers = (Set<PeerID>) f.get(piece);
        for (int i = 0; i < n; i++) {
            byte[] hash = new byte[32];
            hash[0] = (byte) (n * 31 + i);
            hash[1] = (byte) i;
            peers.add(new PeerID(hash, (I2PSnarkUtil) null));
        }
    }

    /**
     * Build a wanted set from piece ids, in the order given.
     *
     * @param ids the piece ids to add, in insertion order
     * @return a set holding one piece per id, in the order given
     */
    private static Set<Piece> wanted(int... ids) {
        Set<Piece> set = new LinkedHashSet<>();
        for (int id : ids) {
            set.add(new Piece(id));
        }
        return set;
    }

    /**
     * The piece ids of a set, in iteration order.
     *
     * @param set the set to read out
     * @return the ids, in the order the set iterates
     */
    private static List<Integer> ids(Set<Piece> set) {
        List<Integer> rv = new ArrayList<>(set.size());
        for (Piece p : set) {
            rv.add(Integer.valueOf(p.getId()));
        }
        return rv;
    }

    /**
     * Sorting puts the higher priority first, as the request path relies on: it
     * stops at the first disabled piece because everything after it is lower.
     */
    @Test
    public void testResortOrdersByPriorityDescending() {
        Set<Piece> set = wanted(0, 1, 2, 3);
        for (Piece p : set) {
            p.setPriority(p.getId());
        }
        PeerCoordinator.resortWanted(set);
        assertEquals(Arrays.asList(3, 2, 1, 0), ids(set));
    }

    /**
     * Within one priority, the rarer piece comes first: fewer peers having it is
     * the whole point of rarest-first.
     *
     * @throws Exception if the private peer set is not readable, so the fixture
     *                   cannot be built and the rarity ordering is untestable
     */
    @Test
    public void testResortOrdersByRarityAscending() throws Exception {
        Piece common = new Piece(7);
        Piece rare = new Piece(3);
        Piece middle = new Piece(5);
        withPeers(common, 9);
        withPeers(rare, 1);
        withPeers(middle, 4);
        Set<Piece> set = new LinkedHashSet<>();
        set.add(common);
        set.add(rare);
        set.add(middle);
        PeerCoordinator.resortWanted(set);
        assertEquals(Arrays.asList(3, 5, 7), ids(set));
    }

    /**
     * Priority outranks rarity: a common high-priority piece still precedes a
     * rare low-priority one.
     *
     * @throws Exception if the private peer set is not readable, so the fixture
     *                   cannot be built and the rarity ordering is untestable
     */
    @Test
    public void testResortPriorityOutranksRarity() throws Exception {
        Piece highCommon = new Piece(1);
        Piece lowRare = new Piece(2);
        withPeers(highCommon, 20);
        withPeers(lowRare, 0);
        highCommon.setPriority(5);
        lowRare.setPriority(1);
        Set<Piece> set = new LinkedHashSet<>();
        set.add(lowRare);
        set.add(highCommon);
        PeerCoordinator.resortWanted(set);
        assertEquals(Arrays.asList(1, 2), ids(set));
    }

    /**
     * A rebuild never loses or invents a piece - it only reorders.
     */
    @Test
    public void testResortPreservesContents() {
        Set<Piece> set = wanted(4, 8, 15, 16, 23, 42);
        for (Piece p : set) {
            p.setPriority(p.getId() % 3);
        }
        PeerCoordinator.resortWanted(set);
        assertEquals(new HashSet<>(Arrays.asList(4, 8, 15, 16, 23, 42)),
                     new HashSet<>(ids(set)));
    }

    /**
     * Sorting an already sorted set is a no-op, so the request path can rebuild
     * every time the set is dirty without churning the order.
     *
     * @throws Exception if the private peer set is not readable, so the fixture
     *                   cannot be built and the rarity ordering is untestable
     */
    @Test
    public void testResortIsIdempotent() throws Exception {
        Set<Piece> set = wanted(9, 1, 7, 3, 12, 15);
        for (Piece p : set) {
            p.setPriority(p.getId() % 3);
            withPeers(p, p.getId() % 4);
        }
        PeerCoordinator.resortWanted(set);
        List<Piece> first = new ArrayList<>(set);
        PeerCoordinator.resortWanted(set);
        assertEquals(first, new ArrayList<>(set));
    }

    /**
     * The empty set and a single piece survive both rebuilds, the cases hit when
     * a torrent is complete or has one piece left.
     */
    @Test
    public void testRebuildsOnTinySets() {
        Set<Piece> empty = new LinkedHashSet<>();
        PeerCoordinator.resortWanted(empty);
        PeerCoordinator.reshuffleWanted(empty, new Random(1));
        assertTrue(empty.isEmpty());

        Set<Piece> one = wanted(11);
        PeerCoordinator.resortWanted(one);
        PeerCoordinator.reshuffleWanted(one, new Random(1));
        assertEquals(Arrays.asList(11), ids(one));
    }

    /**
     * Shuffling keeps every piece - it reorders, it does not filter.
     */
    @Test
    public void testReshufflePreservesElements() {
        Set<Piece> set = wanted(0, 1, 2, 3, 4, 5, 6, 7);
        PeerCoordinator.reshuffleWanted(set, new Random(7));
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7)),
                     new HashSet<>(ids(set)));
    }

    /**
     * Pieces added in id order come out shuffled, which is why the init path and
     * the priority-update path shuffle: unshuffled, every peer in the swarm would
     * start from the same piece.
     */
    @Test
    public void testReshuffleBreaksIdOrder() {
        int n = 64;
        Set<Piece> set = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            set.add(new Piece(i));
        }
        PeerCoordinator.reshuffleWanted(set, new Random(20261028L));
        List<Integer> got = ids(set);
        List<Integer> inOrder = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            inOrder.add(Integer.valueOf(i));
        }
        assertNotEquals(inOrder, got);
    }

    /**
     * The same seed gives the same order, so the shuffle is a plain seeded
     * shuffle of the set's contents and nothing else.
     */
    @Test
    public void testReshuffleIsDeterministicForASeed() {
        Set<Piece> a = wanted(2, 3, 5, 7, 11, 13, 17, 19, 23, 29);
        Set<Piece> b = wanted(2, 3, 5, 7, 11, 13, 17, 19, 23, 29);
        PeerCoordinator.reshuffleWanted(a, new Random(42));
        PeerCoordinator.reshuffleWanted(b, new Random(42));
        assertEquals(ids(a), ids(b));
    }

    /**
     * The property the LinkedHashSet swap depends on: removing a piece from the
     * middle of the set leaves the order of the survivors alone, so a completed
     * piece no longer costs a scan and shift under the request path's monitor.
     */
    @Test
    public void testRemovalPreservesOrderOfSurvivors() {
        Set<Piece> set = wanted(10, 11, 12, 13, 14);
        List<Piece> snapshot = new ArrayList<>(set);
        assertTrue(set.remove(snapshot.get(2)));
        assertEquals(Arrays.asList(10, 11, 13, 14), ids(set));
        // the survivors are the same objects, not copies
        assertSame(snapshot.get(0), set.iterator().next());
    }

    /**
     * Removing from a set whose equals is the piece id means a stale Piece object
     * for the same id removes the live one, and adding an id twice cannot
     * duplicate it - both match the ArrayList behavior the callers assume.
     */
    @Test
    public void testSetMatchesPiecesById() {
        Set<Piece> set = wanted(4, 5, 6);
        assertTrue(set.remove(new Piece(5)));
        assertEquals(Arrays.asList(4, 6), ids(set));
        set.add(new Piece(5));
        set.add(new Piece(5));
        assertEquals(3, set.size());
    }

    /**
     * End to end for the request path: a shuffled set rebuilt by the resort comes
     * out rarest-first, so the lazy sort on the dirty flag restores the order the
     * path walks after any shuffle or removal.
     *
     * @throws Exception if the private peer set is not readable, so the fixture
     *                   cannot be built and the rarity ordering is untestable
     */
    @Test
    public void testResortAfterRemovalAndShuffleRestoresRarestFirst() throws Exception {
        Set<Piece> set = new LinkedHashSet<>();
        for (int id = 0; id < 6; id++) {
            Piece p = new Piece(id);
            withPeers(p, 5 - id);
            set.add(p);
        }
        PeerCoordinator.reshuffleWanted(set, new Random(3));
        set.remove(new Piece(2));
        PeerCoordinator.resortWanted(set);
        assertEquals(Arrays.asList(5, 4, 3, 1, 0), ids(set));
        // a sorted set is a TreeSet read in the same order, the order contract
        assertEquals(new ArrayList<>(new TreeSet<>(set)), new ArrayList<>(set));
    }
}
