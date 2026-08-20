package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Unit tests for the reliability gate in ClientPeerSelector
 * (isReliable / filterByReliability). Each signal is consulted
 * exactly once; the filter preserves input order and never ranks.
 */
public class ClientPeerSelectorReliabilityTest {

    private static final long NOW = 1_000_000_000L;
    private static final long TEN_MIN = 10 * 60 * 1000L;
    private static final long THIRTY_MIN = 30 * 60 * 1000L;

    private RouterContext _ctx;
    private ProfileOrganizer _organizer;
    private CommSystemFacade _commSystem;
    private ClientPeerSelector _selector;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-cpsrel-test-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        _organizer = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(_organizer);
        _commSystem = mock(CommSystemFacade.class);
        when(_ctx.commSystem()).thenReturn(_commSystem);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);
        _selector = new ClientPeerSelector(_ctx);
    }

    @After
    public void tearDown() {
        File[] children = _tmpDir.listFiles();
        if (children != null) {
            for (File c : children) {c.delete();}
        }
        _tmpDir.delete();
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    private PeerProfile profile(double acceptanceRatio, long lastTested, long lastHeardFrom,
                                long lastSendSuccessful, boolean established) {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getTunnelAcceptanceRatio()).thenReturn(acceptanceRatio);
        when(p.getLastTestedSuccessfully()).thenReturn(lastTested);
        when(p.getLastHeardFrom()).thenReturn(lastHeardFrom);
        when(p.getLastSendSuccessful()).thenReturn(lastSendSuccessful);
        when(p.getPeer()).thenReturn(hash(0));
        when(_commSystem.isEstablished(hash(0))).thenReturn(established);
        return p;
    }

    // ---------- isReliable ----------

    @Test
    public void testNullProfileNotReliable() {
        assertFalse(_selector.isReliable(null, NOW, TEN_MIN, THIRTY_MIN));
    }

    @Test
    public void testLowAcceptanceRatioNotReliable() {
        PeerProfile p = profile(0.2, NOW - 1000, NOW - 1000, NOW - 1000, true);
        assertFalse(_selector.isReliable(p, NOW, TEN_MIN, THIRTY_MIN));
    }

    @Test
    public void testRecentTunnelTestReliable() {
        PeerProfile p = profile(0.4, NOW - 1000, 0, 0, false);
        assertTrue(_selector.isReliable(p, NOW, TEN_MIN, THIRTY_MIN));
    }

    @Test
    public void testRecentHeardFromReliable() {
        PeerProfile p = profile(0.4, 0, NOW - 1000, 0, false);
        assertTrue(_selector.isReliable(p, NOW, TEN_MIN, THIRTY_MIN));
    }

    @Test
    public void testRecentSendSuccessfulReliable() {
        PeerProfile p = profile(0.4, 0, 0, NOW - 1000, false);
        assertTrue(_selector.isReliable(p, NOW, TEN_MIN, THIRTY_MIN));
    }

    @Test
    public void testEstablishedReliable() {
        PeerProfile p = profile(0.4, 0, 0, 0, true);
        assertTrue(_selector.isReliable(p, NOW, TEN_MIN, THIRTY_MIN));
    }

    @Test
    public void testNoRecentSignalsNotReliable() {
        PeerProfile p = profile(0.4, 0, 0, 0, false);
        assertFalse(_selector.isReliable(p, NOW, TEN_MIN, THIRTY_MIN));
    }

    @Test
    public void testStaleSignalsNotReliable() {
        PeerProfile p = profile(0.4, NOW - TEN_MIN, NOW - THIRTY_MIN, NOW - THIRTY_MIN, false);
        assertFalse(_selector.isReliable(p, NOW, TEN_MIN, THIRTY_MIN));
    }

    @Test
    public void testThresholdBoundary() {
        assertFalse(_selector.isReliable(profile(0.3 - 0.0001, NOW - 1000, 0, 0, false), NOW, TEN_MIN, THIRTY_MIN));
        assertTrue(_selector.isReliable(profile(0.3, NOW - 1000, 0, 0, false), NOW, TEN_MIN, THIRTY_MIN));
    }

    // ---------- filterByReliability ----------

    @Test
    public void testFilterPreservesInputOrder() {
        Hash h0 = hash(0), h1 = hash(1), h2 = hash(2);
        PeerProfile p0 = profile(0.4, NOW - 1000, 0, 0, false);
        PeerProfile p1 = profile(0.1, NOW - 1000, 0, 0, true);
        PeerProfile p2 = profile(0.4, 0, 0, 0, true);
        when(_organizer.getProfile(h0)).thenReturn(p0);
        when(_organizer.getProfile(h1)).thenReturn(p1);
        when(_organizer.getProfile(h2)).thenReturn(p2);
        Set<Hash> candidates = new LinkedHashSet<>(Arrays.asList(h0, h1, h2));
        List<Hash> rv = _selector.filterByReliability(candidates, null);
        assertNotNull(rv);
        assertEquals(2, rv.size());
        assertEquals(Arrays.asList(h0, h2), new ArrayList<>(rv));
    }

    @Test
    public void testFilterHonorsExclude() {
        Hash h0 = hash(0), h1 = hash(1);
        PeerProfile p0 = profile(0.4, NOW - 1000, 0, 0, false);
        PeerProfile p1 = profile(0.4, NOW - 1000, 0, 0, false);
        when(_organizer.getProfile(h0)).thenReturn(p0);
        when(_organizer.getProfile(h1)).thenReturn(p1);
        Set<Hash> candidates = new HashSet<>(Arrays.asList(h0, h1));
        Set<Hash> exclude = new HashSet<>(Arrays.asList(h0));
        List<Hash> rv = _selector.filterByReliability(candidates, exclude);
        assertEquals(Arrays.asList(h1), new ArrayList<>(rv));
    }

    @Test
    public void testFilterNullAndEmptyCandidates() {
        assertTrue(_selector.filterByReliability(null, null).isEmpty());
        assertTrue(_selector.filterByReliability(new HashSet<>(), null).isEmpty());
    }

    @Test
    public void testFilterAllFailingReturnsEmpty() {
        Hash h0 = hash(0);
        PeerProfile p0 = profile(0.1, NOW - 1000, 0, 0, true);
        when(_organizer.getProfile(h0)).thenReturn(p0);
        assertTrue(_selector.filterByReliability(new HashSet<>(Arrays.asList(h0)), null).isEmpty());
    }
}