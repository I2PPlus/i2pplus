package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests the symmetric hop quality pre-filter: proven responders replace
 * unproven hops in place (protected slots untouched, no duplicates), and
 * unreliable hops are dropped only while surplus hops remain.
 *
 * @since 0.9.71+
 */
public class PeerQualityFilterTest {

    private static final long NOW = 50_000_000L;
    private static final long WINDOW = TunnelPeerSelector.PROVEN_RESPONDER_WINDOW_MS;

    private static Hash h(String s) {
        return Hash.create(s.getBytes());
    }

    private static Map<Hash, Long> proof(Hash h, long t) {
        Map<Hash, Long> m = new HashMap<>();
        m.put(h, t);
        return m;
    }

    @Test
    public void testProvenHopsKeptUnprovenSwapped() {
        Hash unproven = h("aaaaaa");
        Hash fresh = h("cccccc");
        List<Hash> hops = new ArrayList<>(Arrays.asList(unproven));
        Iterator<Hash> cands = Arrays.asList(fresh).iterator();
        assertEquals(1, TunnelPeerSelector.preferProven(
                hops, 0, 1, proof(h("bbbbbb"), NOW - 10), NOW, WINDOW, cands));
        assertEquals(fresh, hops.get(0));
    }

    @Test
    public void testProtectedSlotsNeverReplaced() {
        Hash unprovenFirst = h("aaaaaa");
        Hash candidate = h("cccccc");
        List<Hash> hops = new ArrayList<>(Arrays.asList(unprovenFirst));
        Iterator<Hash> cands = Arrays.asList(candidate).iterator();
        // from=1: slot 0 protected even though unproven
        assertEquals(0, TunnelPeerSelector.preferProven(
                hops, 1, 1, new HashMap<>(), NOW, WINDOW, cands));
        assertEquals(unprovenFirst, hops.get(0));
    }

    @Test
    public void testAlreadyUsedPeersSkipped() {
        Hash usedProven = h("aaaaaa");
        Hash extra = h("cccccc");
        // slot 0 holds a fresh-proven peer; the candidate list repeats it
        // before the usable extra — the repeat must be skipped
        List<Hash> hops = new ArrayList<>(Arrays.asList(
                usedProven, h("dddddd")));
        Map<Hash, Long> proof = proof(usedProven, NOW - 5);
        Iterator<Hash> cands = Arrays.asList(usedProven, extra).iterator();
        assertEquals(1, TunnelPeerSelector.preferProven(hops, 0, hops.size(), proof, NOW, WINDOW, cands));
        assertEquals(extra, hops.get(1));
        assertEquals(usedProven, hops.get(0));
    }

    @Test
    public void testNoCandidatesLeavesHops() {
        Hash a = h("aaaaaa");
        List<Hash> hops = new ArrayList<>(Arrays.asList(a));
        assertEquals(0, TunnelPeerSelector.preferProven(
                hops, 0, 1, new HashMap<>(), NOW, WINDOW,
                new ArrayList<Hash>().iterator()));
        assertEquals(a, hops.get(0));
    }

    @Test
    public void testStaleProofDoesNotProtect() {
        Hash stale = h("aaaaaa");
        Hash fresh = h("cccccc");
        List<Hash> hops = new ArrayList<>(Arrays.asList(stale));
        Map<Hash, Long> proof = proof(stale, NOW - WINDOW - 1);
        Iterator<Hash> cands = Arrays.asList(fresh).iterator();
        assertEquals(1, TunnelPeerSelector.preferProven(hops, 0, hops.size(), proof, NOW, WINDOW, cands));
        assertEquals(fresh, hops.get(0));
    }

    @Test
    public void testDropUnreliableRespectsMinKeep() {
        Hash bad1 = h("aaaaaa");
        Hash bad2 = h("bbbbbb");
        Hash good = h("cccccc");
        List<Hash> hops = new ArrayList<>(Arrays.asList(bad1, good, bad2));
        Set<Hash> unreliable = new HashSet<>(Arrays.asList(bad1, bad2));
        // three hops, minKeep 2: only one of the two unreliable may go
        assertTrue(TunnelPeerSelector.dropUnreliable(hops, unreliable, 2));
        assertEquals(2, hops.size());
        assertTrue(hops.contains(good));
        // minKeep reached: nothing further drops
        assertFalse(TunnelPeerSelector.dropUnreliable(hops, unreliable, 2));
    }

    @Test
    public void testDropUnreliableNoopCases() {
        Hash good = h("aaaaaa");
        List<Hash> hops = new ArrayList<>(Arrays.asList(good));
        assertFalse(TunnelPeerSelector.dropUnreliable(
                hops, new HashSet<>(Arrays.asList(good)), 2));
        // empty unreliable set is a noop regardless of size headroom
        List<Hash> two = new ArrayList<>(Arrays.asList(good, h("bbbbbb")));
        assertFalse(TunnelPeerSelector.dropUnreliable(two, new HashSet<>(), 1));
        assertEquals(2, two.size());
    }

    @Test
    public void testReplacementNeverDuplicatesLaterSlot() {
        Hash later = h("bbbbbb");
        Hash unproven = h("aaaaaa");
        Hash fresh = h("cccccc");
        // slot 1 is unproven; slot 2 holds a proven peer; the candidate
        // list contains slot 2's peer before the usable extra — the swap
        // must skip it rather than duplicate a peer already in the tunnel
        List<Hash> hops = new ArrayList<>(Arrays.asList(unproven, later));
        Map<Hash, Long> proof = new HashMap<>();
        proof.put(later, NOW - 10);
        proof.put(fresh, NOW - 5);
        Iterator<Hash> cands = Arrays.asList(later, fresh).iterator();
        assertEquals(1, TunnelPeerSelector.preferProven(hops, 0, hops.size(), proof, NOW, WINDOW, cands));
        assertEquals(fresh, hops.get(0));
        assertEquals(later, hops.get(1));
    }
}
