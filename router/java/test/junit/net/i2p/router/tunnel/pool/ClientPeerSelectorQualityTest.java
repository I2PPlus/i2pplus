package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.peermanager.PeerProfile;

/**
 * Unit tests for the peer quality comparator cascade in ClientPeerSelector.
 * The stage order is behaviorally significant (each stage short-circuits the
 * ones below it), so the tests pin both the individual stage decisions and
 * the stage ordering.
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorQualityTest {

    private static final long NOW = 1_000_000L;
    private static final long THIRTY_MINUTES = 30 * 60 * 1000L;

    /** Peer A: good acceptance, active, fast latency. */
    private static PeerProfile goodActiveFast() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.8);
        when(p.getLastHeardFrom()).thenReturn(NOW - 1000L);
        when(p.getTunnelTestTimeAverage()).thenReturn(1000f);
        return p;
    }

    /** Peer B: null profile (unknown peer). */
    private static PeerProfile nullProfile() {
        return null;
    }

    /** Peer C: dead acceptance ratio, active, fast. */
    private static PeerProfile deadActiveFast() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.0);
        when(p.getLastHeardFrom()).thenReturn(NOW - 1000L);
        when(p.getTunnelTestTimeAverage()).thenReturn(1000f);
        return p;
    }

    /** Peer D: good acceptance, inactive, slow latency. */
    private static PeerProfile goodInactiveSlow() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getTunnelAcceptanceRatio()).thenReturn(0.8);
        when(p.getLastHeardFrom()).thenReturn(0L);
        when(p.getTunnelTestTimeAverage()).thenReturn(30_000f);
        return p;
    }

    private static Hash hash(int seed) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) seed;
        return new Hash(data);
    }

    /** Compare two peers through the full cascade. */
    private static int cascadeCompare(Set<Hash> exclude, PeerProfile pa, PeerProfile pb) {
        return ClientPeerSelector.compareQuality(hash(1), hash(2), exclude, pa, pb, NOW, THIRTY_MINUTES);
    }

    @Test
    public void testCompareExcludedBoth() {
        Set<Hash> exclude = new HashSet<>();
        Hash a = hash(1);
        Hash b = hash(2);
        exclude.add(a);
        exclude.add(b);
        assertEquals(0, ClientPeerSelector.compareExcluded(a, b, exclude));
    }

    @Test
    public void testCompareExcludedFirstOnly() {
        Set<Hash> exclude = new HashSet<>();
        Hash a = hash(1);
        Hash b = hash(2);
        exclude.add(a);
        assertEquals(1, ClientPeerSelector.compareExcluded(a, b, exclude));
        assertEquals(-1, ClientPeerSelector.compareExcluded(b, a, exclude));
    }

    @Test
    public void testCompareExcludedNone() {
        Hash a = hash(1);
        Hash b = hash(2);
        assertEquals(0, ClientPeerSelector.compareExcluded(a, b, null));
        assertEquals(0, ClientPeerSelector.compareExcluded(a, b, new HashSet<>()));
    }

    @Test
    public void testCompareAcceptanceDeadVsGood() {
        PeerProfile dead = deadActiveFast();
        PeerProfile good = goodActiveFast();
        // Dead (<= 0) ranks BELOW good (> 0.3): good sorts first
        assertEquals(1, ClientPeerSelector.compareAcceptance(dead, good));
        assertEquals(-1, ClientPeerSelector.compareAcceptance(good, dead));
    }

    @Test
    public void testCompareAcceptanceDeadVsLow() {
        PeerProfile dead = deadActiveFast();
        PeerProfile low = mock(PeerProfile.class);
        when(low.getTunnelAcceptanceRatio()).thenReturn(0.2);
        // Dead (<= 0) ranks BELOW low (0 < r < 0.3): low sorts first
        assertEquals(1, ClientPeerSelector.compareAcceptance(dead, low));
        assertEquals(-1, ClientPeerSelector.compareAcceptance(low, dead));
    }

    @Test
    public void testCompareAcceptanceLowVsGood() {
        PeerProfile low = mock(PeerProfile.class);
        when(low.getTunnelAcceptanceRatio()).thenReturn(0.2);
        PeerProfile good = goodActiveFast();
        assertEquals(1, ClientPeerSelector.compareAcceptance(low, good));
        assertEquals(-1, ClientPeerSelector.compareAcceptance(good, low));
    }

    @Test
    public void testCompareAcceptanceNullProfileDefaultsHigh() {
        PeerProfile good = goodActiveFast();
        assertEquals(0, ClientPeerSelector.compareAcceptance(nullProfile(), good));
        assertEquals(0, ClientPeerSelector.compareAcceptance(good, nullProfile()));
    }

    @Test
    public void testCompareSlowLatencyFastVsSlow() {
        assertEquals(1, ClientPeerSelector.compareSlowLatency(30_000f, 1000f));
        assertEquals(-1, ClientPeerSelector.compareSlowLatency(1000f, 30_000f));
    }

    @Test
    public void testCompareSlowLatencyBothSlowPrefersLessSlow() {
        assertEquals(-1, ClientPeerSelector.compareSlowLatency(20_000f, 40_000f));
        assertEquals(1, ClientPeerSelector.compareSlowLatency(40_000f, 20_000f));
    }

    @Test
    public void testCompareSlowLatencyUnknownNotSlow() {
        assertEquals(0, ClientPeerSelector.compareSlowLatency(0f, 0f));
        assertEquals(0, ClientPeerSelector.compareSlowLatency(5000f, 0f));
    }

    @Test
    public void testCompareActivityActiveFirst() {
        PeerProfile active = goodActiveFast();
        PeerProfile inactive = goodInactiveSlow();
        assertEquals(-1, ClientPeerSelector.compareActivity(active, inactive, NOW, THIRTY_MINUTES));
        assertEquals(1, ClientPeerSelector.compareActivity(inactive, active, NOW, THIRTY_MINUTES));
    }

    @Test
    public void testCompareActivityNullProfileInactive() {
        PeerProfile active = goodActiveFast();
        assertEquals(1, ClientPeerSelector.compareActivity(nullProfile(), active, NOW, THIRTY_MINUTES));
        assertEquals(-1, ClientPeerSelector.compareActivity(active, nullProfile(), NOW, THIRTY_MINUTES));
    }

    @Test
    public void testCompareLatencyMeasuredBeatsUnknown() {
        assertEquals(-1, ClientPeerSelector.compareLatency(1000f, 0f));
        assertEquals(1, ClientPeerSelector.compareLatency(0f, 1000f));
        assertEquals(0, ClientPeerSelector.compareLatency(0f, 0f));
    }

    @Test
    public void testCompareLatencyLowerFirst() {
        assertEquals(-1, ClientPeerSelector.compareLatency(1000f, 5000f));
        assertEquals(1, ClientPeerSelector.compareLatency(5000f, 1000f));
        assertEquals(0, ClientPeerSelector.compareLatency(3000f, 3000f));
    }

    @Test
    public void testCascadeExclusionBeatsEverything() {
        Set<Hash> exclude = new HashSet<>();
        exclude.add(hash(1));
        // Excluded peer must lose even against a dead-ratio peer
        assertEquals(1, cascadeCompare(exclude, goodActiveFast(), deadActiveFast()));
    }

    @Test
    public void testCascadeAcceptanceBeatsSlow() {
        // Acceptance ratio decides before slow latency: good-ratio peer wins
        // over the dead-ratio peer even though the good one is slow
        assertEquals(-1, cascadeCompare(null, goodInactiveSlow(), deadActiveFast()));
    }

    @Test
    public void testCascadeSlowBeatsActivity() {
        // Slow latency ranks below active: slow peer loses despite activity equality
        assertEquals(1, cascadeCompare(null, goodInactiveSlow(), goodActiveFast()));
    }

    @Test
    public void testCascadeActivityBeatsLatency() {
        // Neither peer slow; p1 active with higher latency, p2 inactive with
        // lower latency: activity stage precedes the final latency stage
        PeerProfile p1 = mock(PeerProfile.class);
        when(p1.getTunnelAcceptanceRatio()).thenReturn(0.8);
        when(p1.getLastHeardFrom()).thenReturn(NOW - 1000L);
        when(p1.getTunnelTestTimeAverage()).thenReturn(10_000f);
        PeerProfile p2 = mock(PeerProfile.class);
        when(p2.getTunnelAcceptanceRatio()).thenReturn(0.8);
        when(p2.getLastHeardFrom()).thenReturn(0L);
        when(p2.getTunnelTestTimeAverage()).thenReturn(2000f);
        assertEquals(-1, cascadeCompare(null, p1, p2));
        assertEquals(1, cascadeCompare(null, p2, p1));
    }

    @Test
    public void testCascadeEqualPeers() {
        PeerProfile pa = goodActiveFast();
        PeerProfile pb = goodActiveFast();
        assertEquals(0, cascadeCompare(null, pa, pb));
    }
}