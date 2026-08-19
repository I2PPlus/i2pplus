package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.TunnelPoolSettings;

/**
 * Tests the duplicate-sequence regeneration in TunnelPeerSelector:
 * successive attempts must yield different, deterministic, key-derived
 * orderings (the old shuffle-then-sort reproduced the canonical order and
 * the retry loop always broke at attempt 1).
 */
public class TunnelPeerSelectorRegenerateTest {

    private static RouterContext _ctx;
    private static TestSelector _sel;

    private static class TestSelector extends TunnelPeerSelector {
        TestSelector(RouterContext ctx) {
            super(ctx);
        }

        @Override
        public List<Hash> selectPeers(TunnelPoolSettings settings) {
            return null;
        }
    }

    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _sel = new TestSelector(_ctx);
    }

    private static List<Hash> peers(int count) {
        List<Hash> rv = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[Hash.HASH_LENGTH];
            data[0] = (byte) i;
            data[1] = (byte) (count - i);
            rv.add(Hash.create(data));
        }
        return rv;
    }

    private static TunnelPoolSettings settings() {
        TunnelPoolSettings s = new TunnelPoolSettings(false);
        assertNotNull(s.getRandomKey());
        return s;
    }

    @Test
    public void testAttemptOneDiffersFromCanonicalOrder() {
        TunnelPoolSettings s = settings();
        List<Hash> peers = peers(5);
        List<Hash> canonical = new ArrayList<>(peers);
        _sel.orderPeers(canonical, s.getRandomKey());
        List<Hash> regenerated = _sel.regeneratePeers(s, new ArrayList<>(peers), 1);
        assertNotEquals("regeneration must change the sequence", canonical, regenerated);
    }

    @Test
    public void testSuccessiveAttemptsDiffer() {
        TunnelPoolSettings s = settings();
        List<Hash> peers = peers(5);
        List<Hash> a1 = _sel.regeneratePeers(s, new ArrayList<>(peers), 1);
        List<Hash> a2 = _sel.regeneratePeers(s, new ArrayList<>(peers), 2);
        List<Hash> a3 = _sel.regeneratePeers(s, new ArrayList<>(peers), 3);
        assertEquals("attempts must preserve the peer set", peers.size(), a1.size());
        assertNotEquals(a1, a2);
        assertNotEquals(a1, a3);
        assertNotEquals(a2, a3);
    }

    @Test
    public void testRegenerationIsDeterministicPerAttempt() {
        TunnelPoolSettings s = settings();
        List<Hash> peers = peers(5);
        List<Hash> a = _sel.regeneratePeers(s, new ArrayList<>(peers), 2);
        List<Hash> b = _sel.regeneratePeers(s, new ArrayList<>(peers), 2);
        assertEquals("same key + attempt must give the same order", a, b);
    }

    @Test
    public void testRegenerationDoesNotMutateInput() {
        TunnelPoolSettings s = settings();
        List<Hash> peers = peers(5);
        List<Hash> before = new ArrayList<>(peers);
        _sel.regeneratePeers(s, peers, 3);
        assertEquals("input list must not be modified", before, peers);
    }

    @Test
    public void testSinglePeerUnchanged() {
        TunnelPoolSettings s = settings();
        List<Hash> single = peers(1);
        List<Hash> rv = _sel.regeneratePeers(s, new ArrayList<>(single), 1);
        assertEquals(single, rv);
    }
}