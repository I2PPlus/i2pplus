package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;

/**
 * Unit tests for the peer-selection helpers shared between
 * ClientPeerSelector and ExploratoryPeerSelector via TunnelPeerSelector.
 *
 * @since 0.9.71+
 */
public class TunnelPeerSelectorSharedTest {

    private static TunnelPoolSettings settings(int length, int variance, int override) {
        TunnelPoolSettings s = mock(TunnelPoolSettings.class);
        when(s.getLength()).thenReturn(length);
        when(s.getLengthVariance()).thenReturn(variance);
        when(s.getLengthOverride()).thenReturn(override);
        return s;
    }

    private static TunnelPool pool(TunnelInfo... tunnels) {
        TunnelPool p = mock(TunnelPool.class);
        List<TunnelInfo> list = new ArrayList<>();
        for (TunnelInfo ti : tunnels) {list.add(ti);}
        when(p.listTunnels()).thenReturn(list);
        return p;
    }

    private static TunnelInfo tunnel(int length) {
        TunnelInfo ti = mock(TunnelInfo.class);
        when(ti.getLength()).thenReturn(length);
        return ti;
    }

    // ---------- isZeroHopSettings ----------

    @Test
    public void testIsZeroHopSettings_LengthZero() {
        assertTrue(TunnelPeerSelector.isZeroHopSettings(settings(0, 2, 1)));
    }

    @Test
    public void testIsZeroHopSettings_OverrideZero() {
        assertTrue(TunnelPeerSelector.isZeroHopSettings(settings(3, 1, 0)));
    }

    @Test
    public void testIsZeroHopSettings_LengthPlusVarianceNonPositive() {
        assertTrue(TunnelPeerSelector.isZeroHopSettings(settings(3, -3, 1)));
        assertTrue(TunnelPeerSelector.isZeroHopSettings(settings(2, -2, 1)));
    }

    @Test
    public void testIsZeroHopSettings_Normal() {
        assertFalse(TunnelPeerSelector.isZeroHopSettings(settings(3, 1, 1)));
        assertFalse(TunnelPeerSelector.isZeroHopSettings(settings(2, -1, 1)));
    }

    // ---------- hasTunnelLongerThanOne ----------

    @Test
    public void testHasTunnelLongerThanOne_EmptyPool() {
        assertFalse(TunnelPeerSelector.hasTunnelLongerThanOne(pool()));
    }

    @Test
    public void testHasTunnelLongerThanOne_AllSingleHop() {
        assertFalse(TunnelPeerSelector.hasTunnelLongerThanOne(pool(tunnel(1), tunnel(1))));
    }

    @Test
    public void testHasTunnelLongerThanOne_MultiHopPresent() {
        assertTrue(TunnelPeerSelector.hasTunnelLongerThanOne(pool(tunnel(1), tunnel(2), tunnel(3))));
    }

    // ---------- addFreshCooldownExclusions ----------

    @Test
    public void testAddFreshCooldownExclusions_AddsOnlyFresh() {
        long cutoff = 1000;
        Map<Hash, Long> cooldowns = new HashMap<>();
        Hash fresh = hash(1);
        Hash expired = hash(2);
        cooldowns.put(fresh, 2000L);
        cooldowns.put(expired, 500L);
        Set<Hash> exclude = new HashSet<>();
        int count = TunnelPeerSelector.addFreshCooldownExclusions(cooldowns, cutoff, exclude);
        assertEquals(1, count);
        assertTrue(exclude.contains(fresh));
        assertFalse(exclude.contains(expired));
        // Map must not be mutated
        assertEquals(2, cooldowns.size());
    }

    @Test
    public void testAddFreshCooldownExclusions_BoundaryAndEmpty() {
        long cutoff = 1000;
        Map<Hash, Long> cooldowns = new HashMap<>();
        cooldowns.put(hash(1), 1000L); // == cutoff, not fresh
        Set<Hash> exclude = new HashSet<>();
        assertEquals(0, TunnelPeerSelector.addFreshCooldownExclusions(cooldowns, cutoff, exclude));
        assertEquals(0, TunnelPeerSelector.addFreshCooldownExclusions(new HashMap<>(), cutoff, new HashSet<>()));
    }

    // ---------- pickFurthest composition (as used by both selectors) ----------

    @Test
    public void testPickFurthestComposition_ZeroHopSettingsAlwaysTrue() {
        // Even a pool with a 2-hop tunnel must not force a furthest hop when
        // the pair is zero-hop (EPS:233-249 / CPS:382-423 semantics)
        TunnelPool multi = pool(tunnel(2));
        assertTrue(TunnelPeerSelector.isZeroHopSettings(settings(0, 2, 1)) ||
                   !TunnelPeerSelector.hasTunnelLongerThanOne(multi));
    }

    @Test
    public void testPickFurthestComposition_MultiHopPool() {
        TunnelPool multi = pool(tunnel(2));
        assertFalse(TunnelPeerSelector.isZeroHopSettings(settings(3, 1, 1)) &&
                    TunnelPeerSelector.hasTunnelLongerThanOne(multi));
        // pickFurthest = zeroHop || !hasLonger -> false here
        assertFalse(TunnelPeerSelector.isZeroHopSettings(settings(3, 1, 1)) ||
                    !TunnelPeerSelector.hasTunnelLongerThanOne(multi));
    }

    @Test
    public void testPickFurthestComposition_EmptyOrSingleHopPool() {
        TunnelPool empty = pool();
        TunnelPool single = pool(tunnel(1));
        assertTrue(TunnelPeerSelector.isZeroHopSettings(settings(3, 1, 1)) ||
                   !TunnelPeerSelector.hasTunnelLongerThanOne(empty));
        assertTrue(TunnelPeerSelector.isZeroHopSettings(settings(3, 1, 1)) ||
                   !TunnelPeerSelector.hasTunnelLongerThanOne(single));
    }

    private static Hash hash(int seed) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) seed;
        return new Hash(data);
    }
}