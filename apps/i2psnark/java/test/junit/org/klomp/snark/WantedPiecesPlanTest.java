package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/**
 * Tests for <code>PeerCoordinator.planWantedPieces</code> and
 * <code>PeerCoordinator.applyWantedPlan</code>, the two halves of a wanted-piece
 * rebuild. The scan over every piece of the torrent and the shuffle run outside
 * the monitor the per-peer request path takes, and only the swap into the live
 * set and id index happens under it, so what must not change is what a rebuild
 * chooses and what it leaves behind.
 *
 * <p>These are the choices a rebuild makes, pinned here: a piece we already have
 * or a piece with a negative priority is not wanted; a BEP 47 padding-only piece
 * is never wanted; the byte total is the sum of the wanted piece lengths, last
 * short piece included; and the swap clears what the new plan does not contain,
 * so a piece that stopped being wanted cannot linger in the set or the index.
 *
 * @since 0.9.72+
 */
public class WantedPiecesPlanTest {

    /** Piece length of the fixture torrents, in bytes. */
    private static final int PIECE_LENGTH = 1024;

    /**
     * A fixture group for the wanted-piece rebuild; it holds no state.
     */
    public WantedPiecesPlanTest() {}

    /**
     * Build a single-file torrent of the given size.
     *
     * @param pieces how many pieces the torrent has
     * @param length total torrent size in bytes, so the last piece comes out
     *               shortened, as it does in a real torrent
     * @return the metadata for a torrent with that geometry
     */
    private static MetaInfo torrent(int pieces, long length) {
        byte[] hashes = new byte[pieces * 20];
        return new MetaInfo("http://tracker.test/announce", "test.file", null,
                            null, null, PIECE_LENGTH, hashes, length, 0,
                            null, null, null, null);
    }

    /**
     * Build a two-file torrent whose second file is BEP 47 padding.
     *
     * @param realLength bytes of the real file, which must be a whole number of
     *                   pieces for the padding piece to be padding-only
     * @param paddingLength bytes of the padding file
     * @return the metadata for that torrent, with the padding attribute set on
     *         the second file only
     */
    private static MetaInfo torrentWithPadding(long realLength, long paddingLength) {
        int pieces = (int) ((realLength + paddingLength) / PIECE_LENGTH);
        byte[] hashes = new byte[pieces * 20];
        List<List<String>> files = new ArrayList<>(2);
        files.add(Arrays.asList("real"));
        files.add(Arrays.asList("pad"));
        List<Long> lengths = Arrays.asList(Long.valueOf(realLength), Long.valueOf(paddingLength));
        List<String> attributes = Arrays.asList("", "p");
        return new MetaInfo("http://tracker.test/announce", "dir", null, files, lengths,
                            attributes, PIECE_LENGTH, hashes, realLength + paddingLength,
                            false, null, null, null, null);
    }

    /**
     * The piece ids of a plan, as a set, so the assertions are about membership
     * and not about the shuffle.
     *
     * @param plan the plan to read out
     * @return the ids of the pieces it wants to request
     */
    private static Set<Integer> idsOf(PeerCoordinator.WantedPiecesPlan plan) {
        Set<Integer> rv = new HashSet<>(plan.pieces.size());
        for (Piece p : plan.pieces) {
            rv.add(Integer.valueOf(p.getId()));
        }
        return rv;
    }

    /**
     * The piece ids of a live wanted set, in iteration order.
     *
     * @param wanted the set to read out
     * @return the ids, in the order the set iterates
     */
    private static List<Integer> idsOf(Set<Piece> wanted) {
        List<Integer> rv = new ArrayList<>(wanted.size());
        for (Piece p : wanted) {
            rv.add(Integer.valueOf(p.getId()));
        }
        return rv;
    }

    /**
     * A torrent with no pieces at all, as an empty file's torrent has, plans
     * nothing and does not throw - the scan never reads a piece length there, so
     * the short-circuit on the piece count is what keeps it alive.
     */
    @Test
    public void testPiecelessTorrentPlansNothing() {
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(0), null, torrent(0, 0), new Random(1));
        assertTrue(plan.pieces.isEmpty());
        assertEquals(0, plan.bytes);
    }

    /**
     * Nothing downloaded: every piece is wanted and the byte total is the whole
     * torrent, short last piece included.
     */
    @Test
    public void testMissingPiecesAreWantedAndCounted() {
        // 3 full pieces and a 500 byte last one
        MetaInfo mi = torrent(4, 3L * PIECE_LENGTH + 500);
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(4), null, mi, new Random(1));
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2, 3)), idsOf(plan));
        assertEquals(3L * PIECE_LENGTH + 500, plan.bytes);
    }

    /**
     * A completed torrent wants nothing and reports zero bytes, which is what
     * tells the torrent it is a seed.
     */
    @Test
    public void testCompletedTorrentWantsNothing() {
        MetaInfo mi = torrent(4, 4L * PIECE_LENGTH);
        BitField have = new BitField(4);
        have.setAll();
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(have, null, mi, new Random(1));
        assertTrue(plan.pieces.isEmpty());
        assertEquals(0, plan.bytes);
    }

    /**
     * A resumed download wants only the pieces it is missing, which is the whole
     * point of rebuilding from the storage bitfield.
     */
    @Test
    public void testPartialResumeWantsOnlyMissingPieces() {
        MetaInfo mi = torrent(5, 5L * PIECE_LENGTH);
        BitField have = new BitField(5);
        have.set(0);
        have.set(2);
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(have, null, mi, new Random(1));
        assertEquals(new HashSet<>(Arrays.asList(1, 3, 4)), idsOf(plan));
        assertEquals(3L * PIECE_LENGTH, plan.bytes);
    }

    /**
     * A negative priority means the user do-not-download-ed that file, so its
     * piece is neither wanted nor counted, and the surviving pieces carry the
     * priority the request path sorts by.
     */
    @Test
    public void testNegativePriorityIsSkippedAndPriorityCarried() {
        MetaInfo mi = torrent(4, 4L * PIECE_LENGTH);
        int[] pri = {0, -1, 5, 0};
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(4), pri, mi, new Random(1));
        assertEquals(new HashSet<>(Arrays.asList(0, 2, 3)), idsOf(plan));
        assertEquals(3L * PIECE_LENGTH, plan.bytes);
        for (Piece p : plan.pieces) {
            assertEquals(pri[p.getId()], p.getPriority());
        }
    }

    /**
     * Null priorities means no file is excluded, so every missing piece is
     * wanted at the default priority - the normal case, since storage only
     * builds a priority array once the user touches a file priority.
     */
    @Test
    public void testNullPrioritiesWantEveryMissingPiece() {
        MetaInfo mi = torrent(3, 3L * PIECE_LENGTH);
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(3), null, mi, new Random(1));
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2)), idsOf(plan));
        for (Piece p : plan.pieces) {
            assertEquals(0, p.getPriority());
        }
    }

    /**
     * A piece that is entirely BEP 47 padding is never requested: it hashes as
     * zeros, it is always trusted, and it is never written to disk, so
     * requesting it only wastes peer bandwidth and bytes in the wanted total.
     */
    @Test
    public void testPaddingOnlyPiecesAreNeverWanted() {
        // piece 0 is the real file, piece 1 is entirely the padding file
        MetaInfo mi = torrentWithPadding(PIECE_LENGTH, PIECE_LENGTH);
        assertEquals(2, mi.getPieces());
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(2), null, mi, new Random(1));
        assertEquals(new HashSet<>(Arrays.asList(0)), idsOf(plan));
        assertEquals(PIECE_LENGTH, plan.bytes);
    }

    /**
     * The shuffle reorders the plan, it does not filter it: the same pieces, the
     * same count and the same bytes, whatever the seed. Losing a piece here
     * would silently stall a download, since nothing else rebuilds the set.
     */
    @Test
    public void testShufflePreservesTheWantedSet() {
        int n = 64;
        MetaInfo mi = torrent(n, (long) n * PIECE_LENGTH);
        BitField have = new BitField(n);
        have.set(7);
        PeerCoordinator.WantedPiecesPlan shuffled =
                PeerCoordinator.planWantedPieces(have, null, mi, new Random(20261107L));
        PeerCoordinator.WantedPiecesPlan plain =
                PeerCoordinator.planWantedPieces(have, null, mi, null);
        assertEquals(n - 1, plain.pieces.size());
        assertEquals(idsOf(plain), idsOf(shuffled));
        assertEquals(plain.bytes, shuffled.bytes);
        assertEquals(plain.pieces.size(), shuffled.pieces.size());
    }

    /**
     * Without a random the plan stays in id order, which is what the fixture
     * above compares against and what makes the shuffle assertion above
     * non-vacuous: an unshuffled 63-piece plan is exactly the id sequence.
     */
    @Test
    public void testNoRandomKeepsIdOrder() {
        int n = 16;
        MetaInfo mi = torrent(n, (long) n * PIECE_LENGTH);
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(n), null, mi, null);
        for (int i = 0; i < n; i++) {
            assertEquals(i, plan.pieces.get(i).getId());
        }
    }

    /**
     * The shuffle actually shuffles a batch built in id order, otherwise every
     * peer in the swarm would start from piece 0.
     */
    @Test
    public void testShuffleBreaksIdOrder() {
        int n = 64;
        MetaInfo mi = torrent(n, (long) n * PIECE_LENGTH);
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(n), null, mi, new Random(20261107L));
        List<Integer> got = new ArrayList<>(n);
        for (Piece p : plan.pieces) {
            got.add(Integer.valueOf(p.getId()));
        }
        List<Integer> inOrder = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            inOrder.add(Integer.valueOf(i));
        }
        assertNotEquals(inOrder, got);
    }

    /**
     * The swap must clear what the new plan does not contain. A rebuild that
     * shrinks the wanted set - the last piece finished, or a file switched to
     * do-not-download - leaves a stale piece behind otherwise, and the request
     * path then keeps asking peers for a piece we either have or refuse.
     */
    @Test
    public void testApplyShrinkingPlanClearsStaleEntries() {
        Set<Piece> wanted = new LinkedHashSet<>();
        Map<Integer, Piece> byId = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            Piece stale = new Piece(i);
            wanted.add(stale);
            byId.put(Integer.valueOf(i), stale);
        }
        MetaInfo mi = torrent(6, 6L * PIECE_LENGTH);
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(6), null, mi, null);
        // only two of the six are still wanted
        List<Piece> keep = new ArrayList<>(2);
        keep.add(plan.pieces.get(2));
        keep.add(plan.pieces.get(4));
        PeerCoordinator.applyWantedPlan(wanted, byId,
                                       new PeerCoordinator.WantedPiecesPlan(keep, 2L * PIECE_LENGTH));
        assertEquals(Arrays.asList(2, 4), idsOf(wanted));
        assertEquals(2, byId.size());
        assertTrue(byId.containsKey(Integer.valueOf(2)));
        assertTrue(byId.containsKey(Integer.valueOf(4)));
        assertFalse(byId.containsKey(Integer.valueOf(5)));
    }

    /**
     * An empty plan empties both structures, the completed-torrent case: a
     * leftover piece in the id index is a piece the request path can still hand
     * to a peer after the download finished.
     */
    @Test
    public void testApplyEmptyPlanClearsBoth() {
        Set<Piece> wanted = new LinkedHashSet<>();
        Map<Integer, Piece> byId = new HashMap<>();
        Piece stale = new Piece(3);
        wanted.add(stale);
        byId.put(Integer.valueOf(3), stale);
        PeerCoordinator.applyWantedPlan(wanted, byId,
                                       new PeerCoordinator.WantedPiecesPlan(new ArrayList<Piece>(), 0));
        assertTrue(wanted.isEmpty());
        assertTrue(byId.isEmpty());
    }

    /**
     * The set and the id index must hold the same Piece objects after a swap.
     * The request path picks from the set and the storage callback removes by id,
     * so an entry that is in one and not the same object in the other is a piece
     * that never stops being requested.
     */
    @Test
    public void testApplyPutsTheSameObjectsInBoth() {
        Set<Piece> wanted = new LinkedHashSet<>();
        Map<Integer, Piece> byId = new HashMap<>();
        MetaInfo mi = torrent(5, 5L * PIECE_LENGTH);
        PeerCoordinator.WantedPiecesPlan plan =
                PeerCoordinator.planWantedPieces(new BitField(5), null, mi, new Random(9));
        PeerCoordinator.applyWantedPlan(wanted, byId, plan);
        assertEquals(plan.pieces.size(), wanted.size());
        assertEquals(plan.pieces.size(), byId.size());
        for (Piece p : plan.pieces) {
            assertSame(p, byId.get(Integer.valueOf(p.getId())));
        }
        for (Iterator<Piece> it = wanted.iterator(); it.hasNext(); ) {
            Piece p = it.next();
            assertSame(byId.get(Integer.valueOf(p.getId())), p);
        }
    }
}
