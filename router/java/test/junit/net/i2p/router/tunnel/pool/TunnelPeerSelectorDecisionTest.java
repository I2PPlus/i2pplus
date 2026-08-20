package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import net.i2p.CoreVersion;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.TunnelInfo;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.util.OrderedProperties;

/**
 * Unit tests for the pure decision helpers extracted from TunnelPeerSelector's
 * high-complexity methods (getExclusionReason, shouldExclude, isDuplicateSequence,
 * hasValidTransportAddress).
 *
 * @since 0.9.71+
 */
public class TunnelPeerSelectorDecisionTest {

    private static final long MINUTE = 60 * 1000L;
    /** Arbitrary positive "now" — the helpers use absolute timestamps, 0 means never */
    private static final long NOW = 1_000_000_000L;

    // ---------- hasConnectivitySignal ----------

    private static PeerProfile profile(long lastHeardFrom, long lastSendSuccessful,
                                       long lastTested, double acceptanceRatio) {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getLastHeardFrom()).thenReturn(lastHeardFrom);
        when(p.getLastSendSuccessful()).thenReturn(lastSendSuccessful);
        when(p.getTunnelAcceptanceRatio()).thenReturn(acceptanceRatio);
        TunnelHistory th = mock(TunnelHistory.class);
        when(th.getLastTestedSuccessfully()).thenReturn(lastTested);
        when(p.getTunnelHistory()).thenReturn(th);
        return p;
    }

    @Test
    public void testConnectivitySignal_HeardFromWithinWindow() {
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(profile(NOW - 10 * MINUTE, 0, 0, 0.0), NOW, 4 * 60 * MINUTE));
    }

    @Test
    public void testConnectivitySignal_HeardFromTooOld() {
        // 31 minutes ago is outside the 30-minute heard-from window
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(profile(NOW - 31 * MINUTE, 0, 0, 0.0), NOW, 4 * 60 * MINUTE));
    }

    @Test
    public void testConnectivitySignal_SendSuccessfulWithinWindow() {
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(profile(0, NOW - 5 * MINUTE, 0, 0.0), NOW, 4 * 60 * MINUTE));
    }

    @Test
    public void testConnectivitySignal_SendSuccessfulExactBoundary() {
        // Strictly-less-than: exactly 30 minutes ago is not enough
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(profile(0, NOW - 30 * MINUTE, 0, 0.0), NOW, 4 * 60 * MINUTE));
    }

    @Test
    public void testConnectivitySignal_TestedWithinActivityWindow() {
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(profile(0, 0, NOW - 2 * 60 * MINUTE, 0.0), NOW, 4 * 60 * MINUTE));
    }

    @Test
    public void testConnectivitySignal_TestedOutsideActivityWindow() {
        // 5 hours ago exceeds the 4-hour activity window and no other signal
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(profile(0, 0, NOW - 5 * 60 * MINUTE, 0.0), NOW, 4 * 60 * MINUTE));
    }

    @Test
    public void testConnectivitySignal_GoodAcceptanceWithOldTest() {
        // Proven-capable peer with any test history and >50% acceptance passes
        assertTrue(TunnelPeerSelector.hasConnectivitySignal(profile(0, 0, NOW - 30 * 60 * MINUTE, 0.7), NOW, 4 * 60 * MINUTE));
    }

    @Test
    public void testConnectivitySignal_LowAcceptanceFails() {
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(profile(0, 0, NOW - 30 * 60 * MINUTE, 0.5), NOW, 4 * 60 * MINUTE));
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(profile(0, 0, NOW - 30 * 60 * MINUTE, 0.0), NOW, 4 * 60 * MINUTE));
    }

    @Test
    public void testConnectivitySignal_NoSignalAtAll() {
        assertFalse(TunnelPeerSelector.hasConnectivitySignal(profile(0, 0, 0, 0.0), 0, 4 * 60 * MINUTE));
    }

    // ---------- matchesExistingTunnel ----------

    private static TunnelInfo tunnel(int length, Hash... peers) {
        TunnelInfo ti = mock(TunnelInfo.class);
        when(ti.getLength()).thenReturn(length);
        for (int i = 0; i < peers.length; i++) {when(ti.getPeer(i)).thenReturn(peers[i]);}
        return ti;
    }

    private static Hash hash(int seed) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) seed;
        return new Hash(data);
    }

    private static final Hash A = hash(1);
    private static final Hash B = hash(2);
    private static final Hash C = hash(3);
    private static final Hash SELF = hash(4);

    @Test
    public void testMatchesExistingTunnel_LengthMismatch() {
        // newPeers has 2 peers, existing is 3 hops (self included) — length must be 3
        assertFalse(TunnelPeerSelector.matchesExistingTunnel(tunnel(2, A, B), Arrays.asList(A, B), true));
        assertFalse(TunnelPeerSelector.matchesExistingTunnel(tunnel(4, A, B, C, SELF), Arrays.asList(A, B), true));
    }

    @Test
    public void testMatchesExistingTunnel_InboundOffsetZero() {
        // Inbound: existing starts at gateway (index 0)
        assertTrue(TunnelPeerSelector.matchesExistingTunnel(tunnel(3, A, B, SELF), Arrays.asList(A, B), true));
        assertFalse(TunnelPeerSelector.matchesExistingTunnel(tunnel(3, B, A, SELF), Arrays.asList(A, B), true));
    }

    @Test
    public void testMatchesExistingTunnel_OutboundOffsetOne() {
        // Outbound: existing has self at index 0, peers follow at index 1
        assertTrue(TunnelPeerSelector.matchesExistingTunnel(tunnel(3, SELF, A, B), Arrays.asList(A, B), false));
        assertFalse(TunnelPeerSelector.matchesExistingTunnel(tunnel(3, SELF, B, A), Arrays.asList(A, B), false));
    }

    @Test
    public void testMatchesExistingTunnel_NullPeerInExisting() {
        // Missing peer in the existing tunnel never matches
        assertFalse(TunnelPeerSelector.matchesExistingTunnel(tunnel(3, A, null, SELF), Arrays.asList(A, B), true));
    }

    @Test
    public void testMatchesExistingTunnel_SingleHopTriviallyMatches() {
        // 1-hop tunnel with empty newPeers matches for both orientations
        assertTrue(TunnelPeerSelector.matchesExistingTunnel(tunnel(1), Collections.<Hash>emptyList(), true));
        assertTrue(TunnelPeerSelector.matchesExistingTunnel(tunnel(1), Collections.<Hash>emptyList(), false));
    }

    // ---------- isUsableRouterAddress ----------

    private static RouterAddress address(String style, String host, String port, String itag0, String v) {
        OrderedProperties opts = new OrderedProperties();
        if (host != null) opts.setProperty("host", host);
        if (port != null) opts.setProperty("port", port);
        if (itag0 != null) opts.setProperty("itag0", itag0);
        if (v != null) opts.setProperty("v", v);
        return new RouterAddress(style, opts, 10);
    }

    @Test
    public void testUsableAddress_SsuV2WithIpAndPort() {
        assertTrue(TunnelPeerSelector.isUsableRouterAddress(address("SSU", "1.2.3.4", "4567", null, "2")));
    }

    @Test
    public void testUsableAddress_SsuV1Rejected() {
        assertFalse(TunnelPeerSelector.isUsableRouterAddress(address("SSU", "1.2.3.4", "4567", null, "1")));
        assertFalse(TunnelPeerSelector.isUsableRouterAddress(address("SSU", "1.2.3.4", "4567", null, null)));
    }

    @Test
    public void testUsableAddress_SsuWithIntroduction() {
        assertTrue(TunnelPeerSelector.isUsableRouterAddress(address("SSU", null, null, "itag=123", "2")));
    }

    @Test
    public void testUsableAddress_SsuNoIpNoIntroduction() {
        assertFalse(TunnelPeerSelector.isUsableRouterAddress(address("SSU", null, null, null, "2")));
    }

    @Test
    public void testUsableAddress_Ssu2ValidOrIntroduction() {
        assertTrue(TunnelPeerSelector.isUsableRouterAddress(address("SSU2", "1.2.3.4", "4567", null, null)));
        assertTrue(TunnelPeerSelector.isUsableRouterAddress(address("SSU2", null, null, "itag=123", null)));
        assertFalse(TunnelPeerSelector.isUsableRouterAddress(address("SSU2", null, null, null, null)));
    }

    @Test
    public void testUsableAddress_NtcpValidPort() {
        assertTrue(TunnelPeerSelector.isUsableRouterAddress(address("NTCP", "1.2.3.4", "4567", null, null)));
        assertTrue(TunnelPeerSelector.isUsableRouterAddress(address("NTCP2", "1.2.3.4", "4567", null, null)));
    }

    @Test
    public void testUsableAddress_NtcpInvalidPort() {
        // Port 0 is invalid; no itag0 fallback for NTCP
        assertFalse(TunnelPeerSelector.isUsableRouterAddress(address("NTCP", "1.2.3.4", "0", null, null)));
        assertFalse(TunnelPeerSelector.isUsableRouterAddress(address("NTCP2", "1.2.3.4", "0", null, null)));
    }

    @Test
    public void testUsableAddress_UnknownStyle() {
        assertFalse(TunnelPeerSelector.isUsableRouterAddress(address("Blah", "1.2.3.4", "4567", null, null)));
    }

    // ---------- countKnownCaps ----------

    @Test
    public void testCountKnownCaps() {
        assertEquals(0, TunnelPeerSelector.countKnownCaps(""));
        assertEquals(0, TunnelPeerSelector.countKnownCaps("B"));
        assertEquals(1, TunnelPeerSelector.countKnownCaps("F"));
        assertEquals(1, TunnelPeerSelector.countKnownCaps("R"));
        assertEquals(1, TunnelPeerSelector.countKnownCaps("M"));
        assertEquals(1, TunnelPeerSelector.countKnownCaps("ABCDEF"));
        assertEquals(2, TunnelPeerSelector.countKnownCaps("FR"));
        assertEquals(3, TunnelPeerSelector.countKnownCaps("FRX"));
        assertEquals(3, TunnelPeerSelector.countKnownCaps("FRLMNOPQX"));
    }

    // ---------- isOutdatedVersion ----------

    @Test
    public void testIsOutdatedVersion() {
        assertFalse(TunnelPeerSelector.isOutdatedVersion(CoreVersion.PUBLISHED_VERSION));
        assertFalse(TunnelPeerSelector.isOutdatedVersion("0.9.62")); // MIN_VERSION boundary, not strictly older
        assertFalse(TunnelPeerSelector.isOutdatedVersion("0.9.70"));
        assertFalse(TunnelPeerSelector.isOutdatedVersion("2.1.0"));
        assertTrue(TunnelPeerSelector.isOutdatedVersion("0.9.61"));
        assertTrue(TunnelPeerSelector.isOutdatedVersion("0.9.10"));
    }
}