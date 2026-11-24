package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.data.Hash;
import org.junit.Test;

/**
 * The floodfills a search rotated away from stay askable,
 * {@link FloodfillPeerSelector#rotatedFallback(Map, int)}.
 * <p>
 * Floodfill selection skips any peer this router queried in the last 60
 * seconds, so that concurrent searches do not all beat on the same one - a
 * floodfill asked over and over by one router in a short window answers by
 * blocking it. A LeaseSet, though, is held only by the floodfills nearest the
 * destination's own hash, and a live router runs LeaseSet lookups several times
 * a second, so the skip starves lookups. On a live router 5,192 searches in 40
 * minutes found no floodfill at all to ask, and two searches for the same live
 * destination 8 seconds apart queried 24 and 8 peers with nothing in common:
 * the 24-peer search failed, while the 8-peer one - rotated out of everything
 * and fallen back to ordinary routers - found the LeaseSet in 418 ms. Whether
 * the destination resolved was decided by which floodfills the rotation
 * happened to skip.
 * <p>
 * The tier keeps the load-spreading, since a rotated peer is only asked once
 * every fresher tier is spent, and ranks it by how long ago it went quiet, so
 * reopening it does not turn into hammering one floodfill.
 *
 * @since 0.9.72+
 */
public class FloodfillRotationTierTest {

    /**
     * Test case carrying the floodfill rotation-tier cases. Every case hands a
     * map of cooldown deadlines to a static helper, so the instance holds no
     * state and needs no router.
     */
    public FloodfillRotationTierTest() {
    }

    /**
     * A floodfill hash distinguished only by its first byte, so a case can
     * name the peers it ranks and assert on the order they come back in.
     *
     * @param id the first byte of the hash, used to tell the peers apart
     * @return a fresh 32-byte hash, never shared with another caller
     */
    private static Hash peer(char id) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) id;
        data[1] = (byte) 0x5a;
        return Hash.create(data);
    }

    /**
     * The rotated peers a search with slots open would ask.
     *
     * @param ends peer hash to cooldown end time in ms since the epoch
     * @param slots slots still open in the search's selection
     * @return the peers it may re-ask, quietest first
     */
    private static List<Hash> reask(Map<Hash, Long> ends, int slots) {
        return FloodfillPeerSelector.rotatedFallback(ends, slots);
    }

    /**
     * Nothing rotated out means nothing to re-ask, and a search with no slots
     * open asks nobody even when peers were rotated.
     */
    @Test
    public void testNothingToReask() {
        assertTrue(reask(new HashMap<Hash, Long>(), 8).isEmpty());
        Map<Hash, Long> rotated = new HashMap<>(4);
        rotated.put(peer('a'), 1000L);
        assertEquals(0, reask(rotated, 0).size());
        assertEquals(0, reask(rotated, -1).size());
    }

    /**
     * The premise of the fix: peers this router asked a moment ago may be the
     * only ones that can answer, so they have to come back rather than be
     * dropped. Before the tier existed every one of them was discarded and the
     * search asked random non-floodfill routers instead.
     */
    @Test
    public void testRotatedPeersComeBack() {
        long now = System.currentTimeMillis();
        Map<Hash, Long> rotated = new HashMap<>(4);
        Hash a = peer('a');
        Hash b = peer('b');
        Hash c = peer('c');
        rotated.put(a, now + 60 * 1000L);
        rotated.put(b, now + 45 * 1000L);
        rotated.put(c, now + 30 * 1000L);
        List<Hash> rv = reask(rotated, 8);
        assertEquals(3, rv.size());
        Set<Hash> asked = new HashSet<>(rv);
        assertTrue(asked.contains(a));
        assertTrue(asked.contains(b));
        assertTrue(asked.contains(c));
    }

    /**
     * The peer asked longest ago goes first: its cooldown ends soonest, so it
     * is the one this router has gone longest without bothering. Ordering the
     * other way would re-ask the peer queried a second ago before one queried
     * a minute ago, which is the hammering the rotation exists to prevent.
     */
    @Test
    public void testQuietestPeerAskedFirst() {
        long now = System.currentTimeMillis();
        Map<Hash, Long> rotated = new HashMap<>(4);
        Hash justAsked = peer('a');
        Hash quietAwhile = peer('b');
        Hash quietLongest = peer('c');
        rotated.put(justAsked, now + 59 * 1000L);
        rotated.put(quietLongest, now + 3 * 1000L);
        rotated.put(quietAwhile, now + 21 * 1000L);
        List<Hash> rv = reask(rotated, 3);
        assertEquals(quietLongest, rv.get(0));
        assertEquals(quietAwhile, rv.get(1));
        assertEquals(justAsked, rv.get(2));
    }

    /**
     * The cap is the search's open slots, and it is filled from the quietest
     * peers rather than an arbitrary subset - a search with two slots left
     * spends them on the two floodfills it has gone longest without asking.
     */
    @Test
    public void testCapTakesTheQuietestPeers() {
        long now = System.currentTimeMillis();
        Map<Hash, Long> rotated = new HashMap<>(8);
        for (int i = 0; i < 8; i++) {
            rotated.put(peer((char) ('a' + i)), now + (i + 1) * 1000L);
        }
        List<Hash> rv = reask(rotated, 2);
        assertEquals(2, rv.size());
        assertEquals(peer('a'), rv.get(0));
        assertEquals(peer('b'), rv.get(1));
    }
}
