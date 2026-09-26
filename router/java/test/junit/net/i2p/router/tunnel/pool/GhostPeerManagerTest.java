package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Tests for GhostPeerManager:
 * - peers are excluded only after the timeout threshold
 * - exclusions expire after the cooldown, shorter under stress
 * - the cooldown is snapshotted at mark time (state changes don't extend it)
 * - success and clearGhost release the peer
 * - the router itself is never tracked
 */
public class GhostPeerManagerTest {

    private static final long NOW = 1_000_000_000L;

    private RouterContext _ctx;
    private Clock _clock;
    private ProfileOrganizer _organizer;
    private GhostPeerManager _mgr;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-ghost-test-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        // Defaults mirror the real ones: timeout threshold 1, cooldown 300s
        // (120s under stress, which getTunnelBuildSuccess() toggles).
        // Note: the cooldown literals in GhostPeerManager are ints, so the
        // (String, int) overload is the one that matters here.
        when(_ctx.getProperty(anyString(), anyInt())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if ("i2p.tunnel.ghostPeer.attackCooldownMs".equals(key)) {return 120_000;}
            if ("i2p.tunnel.ghostPeer.cooldownMs".equals(key)) {return 300_000;}
            return 1; // i2p.tunnel.ghostPeer.timeoutThreshold
        });
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        _clock = mock(Clock.class);
        when(_clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(_clock);

        _organizer = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(_organizer);
        // Normal mode by default (build success above the attack threshold)
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.9);

        _mgr = new GhostPeerManager(_ctx);
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

    /** Distinct across the full int range (hash(int) wraps every 256). */
    private static Hash distinctHash(int i) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) i;
        data[1] = (byte) (i >>> 8);
        data[2] = (byte) (i >>> 16);
        data[3] = (byte) (i >>> 24);
        return Hash.create(data);
    }

    @Test
    public void testMarkedAfterThresholdTimeouts() {
        assertFalse(_mgr.isGhost(hash(1)));
        _mgr.recordTimeout(hash(1));
        assertTrue("at threshold (1 of 1)", _mgr.isGhost(hash(1)));
        assertEquals(1, _mgr.getGhostCount());
    }

    @Test
    public void testGhostExpiresAfterCooldown() {
        _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));

        when(_clock.now()).thenReturn(NOW + 200_000L); // normal cooldown is 300s
        assertTrue("still excluded mid-cooldown", _mgr.isGhost(hash(1)));

        when(_clock.now()).thenReturn(NOW + 301_000L);
        assertFalse("released after cooldown", _mgr.isGhost(hash(1)));
        assertEquals(0, _mgr.getGhostCount());
    }

    @Test
    public void testStressCooldownIsShorter() {
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.2); // below attack threshold
        _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));

        when(_clock.now()).thenReturn(NOW + 60_000L);
        assertTrue("still excluded before 120s", _mgr.isGhost(hash(1)));

        when(_clock.now()).thenReturn(NOW + 121_000L);
        assertFalse("released after 120s stress cooldown", _mgr.isGhost(hash(1)));
    }

    @Test
    public void testCooldownSnapshottedAtMarkTime() {
        // marked under stress (120s)...
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.2);
        _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));
        // ...network recovers mid-cooldown: the 120s grant must not be extended to 300s
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.9);
        when(_clock.now()).thenReturn(NOW + 121_000L);
        assertFalse("released per the cooldown at mark time", _mgr.isGhost(hash(1)));

        // and the reverse: a normal (300s) mark must not be shortened by stress
        when(_clock.now()).thenReturn(NOW);
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.9);
        _mgr.recordTimeout(hash(2));
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.2);
        when(_clock.now()).thenReturn(NOW + 200_000L);
        assertTrue("300s grant respected", _mgr.isGhost(hash(2)));
    }

    @Test
    public void testSuccessClearsGhost() {
        _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));
        _mgr.recordSuccess(hash(1));
        assertFalse("success clears the mark", _mgr.isGhost(hash(1)));
        assertEquals(0, _mgr.getGhostCount());
    }

    @Test
    public void testClearGhost() {
        _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));
        _mgr.clearGhost(hash(1));
        assertFalse(_mgr.isGhost(hash(1)));
        assertEquals(0, _mgr.getGhostCount());
    }

    @Test
    public void testSelfAndNullNeverTracked() {
        Hash self = hash(7);
        when(_ctx.routerHash()).thenReturn(self);
        _mgr.recordTimeout(self);
        _mgr.recordTimeout(null);
        assertFalse(_mgr.isGhost(self));
        assertFalse(_mgr.isGhost(null));
        assertEquals(0, _mgr.getGhostCount());
    }

    // --- repeat-offense escalation (0.9.71+) ---

    @Test
    public void testRepeatTimeoutEscalatesCooldown() {
        _mgr.recordTimeout(hash(1)); // first offense: base 300s
        when(_clock.now()).thenReturn(NOW + 301_000L);
        assertFalse("base cooldown expired", _mgr.isGhost(hash(1)));

        _mgr.recordTimeout(hash(1)); // second offense within the decay window
        // escalated to 2x base (600s) from the repeat mark time
        when(_clock.now()).thenReturn(NOW + 301_000L + 599_000L);
        assertTrue("2x cooldown respected", _mgr.isGhost(hash(1)));
        when(_clock.now()).thenReturn(NOW + 301_000L + 601_000L);
        assertFalse("2x cooldown released", _mgr.isGhost(hash(1)));
    }

    @Test
    public void testEscalationCappedAtFourX() {
        _mgr.recordTimeout(hash(1)); // offense 0: 300s
        when(_clock.now()).thenReturn(NOW + 301_000L);
        _mgr.recordTimeout(hash(1)); // offense 1: 600s
        when(_clock.now()).thenReturn(NOW + 301_000L + 601_000L);
        _mgr.recordTimeout(hash(1)); // offense 2: 1200s (cap)
        long markAt = NOW + 301_000L + 601_000L;
        when(_clock.now()).thenReturn(markAt + 1_199_000L);
        assertTrue("4x cooldown respected", _mgr.isGhost(hash(1)));
        when(_clock.now()).thenReturn(markAt + 1_201_000L);
        assertFalse("4x cooldown released", _mgr.isGhost(hash(1)));

        // further offenses stay at the cap, never grow unbounded
        when(_clock.now()).thenReturn(markAt + 1_201_000L);
        _mgr.recordTimeout(hash(1));
        long cappedAt = markAt + 1_201_000L;
        when(_clock.now()).thenReturn(cappedAt + 1_199_000L);
        assertTrue("still capped at 4x", _mgr.isGhost(hash(1)));
        when(_clock.now()).thenReturn(cappedAt + 1_201_000L);
        assertFalse("capped cooldown released", _mgr.isGhost(hash(1)));
    }

    @Test
    public void testOffenseDecaysAfterWindow() {
        _mgr.recordTimeout(hash(1)); // first offense: 300s
        // repeat much later than OFFENSE_DECAY_MS: history decayed, base again
        when(_clock.now()).thenReturn(NOW + GhostPeerManager.OFFENSE_DECAY_MS + 1_000L);
        assertFalse("original mark expired", _mgr.isGhost(hash(1)));
        _mgr.recordTimeout(hash(1));
        when(_clock.now()).thenReturn(NOW + GhostPeerManager.OFFENSE_DECAY_MS + 301_000L);
        assertFalse("decayed repeat gets base 300s again", _mgr.isGhost(hash(1)));
    }

    @Test
    public void testSuccessClearsOffenseHistory() {
        _mgr.recordTimeout(hash(1));
        _mgr.recordSuccess(hash(1));
        when(_clock.now()).thenReturn(NOW + 1_000L);
        _mgr.recordTimeout(hash(1)); // proven live since: back to base 300s
        when(_clock.now()).thenReturn(NOW + 301_000L);
        assertFalse("success reset escalation", _mgr.isGhost(hash(1)));
    }

    @Test
    public void testExpiredMarkKeepsWorkingAsExclusionBoundary() {
        // expired but unpruned entry must not poison the next mark
        // (regression: putIfAbsent used to silently no-op on stale entries)
        _mgr.recordTimeout(hash(1));
        when(_clock.now()).thenReturn(NOW + 400_000L);
        assertFalse(_mgr.isGhost(hash(1)));
        _mgr.recordTimeout(hash(1));
        assertTrue("re-marked after stale expiry", _mgr.isGhost(hash(1)));
        assertEquals(1, _mgr.getGhostCount());
    }

    // --- threshold gating (i2p.tunnel.ghostPeer.timeoutThreshold > 1) ---

    /**
     * Rebuild the manager with a custom timeout threshold and cooldown,
     * keeping every other property at its real default.
     */
    private void stubThresholdAndCooldown(final int threshold, final int cooldownMs) {
        when(_ctx.getProperty(anyString(), anyInt())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if ("i2p.tunnel.ghostPeer.attackCooldownMs".equals(key)) {return 120_000;}
            if ("i2p.tunnel.ghostPeer.cooldownMs".equals(key)) {return cooldownMs;}
            if ("i2p.tunnel.ghostPeer.timeoutThreshold".equals(key)) {return threshold;}
            return 1;
        });
        _mgr = new GhostPeerManager(_ctx);
    }

    @Test
    public void testThresholdTwoTracksButDoesNotExcludeFirstStrike() {
        stubThresholdAndCooldown(2, 300_000);
        assertEquals(2, _mgr.getThreshold());

        _mgr.recordTimeout(hash(1));
        assertFalse("first strike tracked only", _mgr.isGhost(hash(1)));
        assertEquals(1, _mgr.getTrackedCount());
        assertEquals(0, _mgr.getGhostCount());

        _mgr.recordTimeout(hash(1)); // second strike within the decay window
        assertTrue("second strike excludes", _mgr.isGhost(hash(1)));
        assertEquals(1, _mgr.getGhostCount());
    }

    @Test
    public void testSubThresholdStrikeAfterDecayDoesNotRevokeActiveExclusion() {
        stubThresholdAndCooldown(2, 3_600_000); // 1h cooldown

        _mgr.recordTimeout(hash(1)); // strike 1: tracked only
        assertFalse(_mgr.isGhost(hash(1)));

        long secondAt = NOW + 600_000L;
        when(_clock.now()).thenReturn(secondAt);
        _mgr.recordTimeout(hash(1)); // strike 2 within the window: active
        assertTrue(_mgr.isGhost(hash(1)));

        // History has decayed, so this lands as a fresh sub-threshold strike;
        // the active exclusion was granted earlier and must survive it.
        long thirdAt = secondAt + GhostPeerManager.OFFENSE_DECAY_MS + 1_000L;
        when(_clock.now()).thenReturn(thirdAt);
        _mgr.recordTimeout(hash(1));
        assertTrue("active exclusion survives a decayed strike", _mgr.isGhost(hash(1)));
        assertEquals(1, _mgr.getGhostCount());

        when(_clock.now()).thenReturn(secondAt + 3_600_000L);
        assertFalse("original cooldown still honoured", _mgr.isGhost(hash(1)));
    }

    @Test
    public void testTrackedMarkCountHardBounded() {
        for (int i = 0; i < GhostPeerManager.MAX_TRACKED_PEERS + 64; i++) {
            _mgr.recordTimeout(distinctHash(i));
        }
        assertTrue("bound enforced: " + _mgr.getTrackedCount(),
                   _mgr.getTrackedCount() <= GhostPeerManager.MAX_TRACKED_PEERS);
        assertTrue(_mgr.getGhostCount() <= _mgr.getTrackedCount());
    }

    @Test
    public void testGhostCountCacheFollowsStaggeredExpiry() {
        _mgr.recordTimeout(hash(1));                       // expires NOW + 300s
        when(_clock.now()).thenReturn(NOW + 100_000L);
        _mgr.recordTimeout(hash(2));                       // expires NOW + 400s
        assertEquals(2, _mgr.getGhostCount());

        // the cached due-time is the earliest expiry: the first lapse forces
        // a rescan and leaves only the second mark active
        when(_clock.now()).thenReturn(NOW + 301_000L);
        assertEquals(1, _mgr.getGhostCount());

        when(_clock.now()).thenReturn(NOW + 401_000L);
        assertEquals(0, _mgr.getGhostCount());
    }

    // --- active-set cap (0.9.71+) ---

    @Test
    public void testActiveGhostCountIsCapped() {
        // A timeout burst must not be allowed to exclude the whole candidate
        // pool: past the cap the exclusions nearest to lapsing are dropped, so
        // tier selection always still has peers to pick from.
        for (int i = 0; i < GhostPeerManager.MAX_ACTIVE_GHOSTS + 64; i++) {
            when(_clock.now()).thenReturn(NOW + i);
            _mgr.recordTimeout(distinctHash(i));
        }
        int active = _mgr.getGhostCount();
        assertTrue("active capped at " + GhostPeerManager.MAX_ACTIVE_GHOSTS + ", was " + active,
                   active > 0 && active <= GhostPeerManager.MAX_ACTIVE_GHOSTS);
        // dropping an exclusion never drops its offense record
        assertEquals(GhostPeerManager.MAX_ACTIVE_GHOSTS + 64, _mgr.getTrackedCount());
    }

    @Test
    public void testActiveCapKeepsOffenseHistory() {
        // One millisecond apart, so "nearest to lapsing" is deterministic: the
        // first ones marked are the ones the cap drops.
        for (int i = 0; i < GhostPeerManager.MAX_ACTIVE_GHOSTS; i++) {
            when(_clock.now()).thenReturn(NOW + i);
            _mgr.recordTimeout(distinctHash(i));
        }
        assertFalse("dropped by the active cap", _mgr.isGhost(distinctHash(0)));
        assertTrue("protected (furthest from lapsing)",
                   _mgr.isGhost(distinctHash(GhostPeerManager.MAX_ACTIVE_GHOSTS - 1)));

        // Re-marking the dropped peer must still see its original offense: this
        // is its second strike inside the decay window, so the cooldown doubles.
        // A cap that dropped the history instead would grant the base 300s here.
        long at = NOW + GhostPeerManager.MAX_ACTIVE_GHOSTS;
        when(_clock.now()).thenReturn(at);
        _mgr.recordTimeout(distinctHash(0));
        assertTrue("re-marked", _mgr.isGhost(distinctHash(0)));
        when(_clock.now()).thenReturn(at + 599_000L);
        assertTrue("2x cooldown respected", _mgr.isGhost(distinctHash(0)));
        when(_clock.now()).thenReturn(at + 601_000L);
        assertFalse("2x cooldown released", _mgr.isGhost(distinctHash(0)));
    }

    @Test
    public void testActiveCountStaysConsistentUnderMixedOps() {
        for (int i = 0; i < 50; i++) {
            _mgr.recordTimeout(distinctHash(i));
        }
        assertEquals(50, _mgr.getGhostCount());

        for (int i = 0; i < 20; i++) {
            _mgr.recordSuccess(distinctHash(i));
        }
        assertEquals(30, _mgr.getGhostCount());

        for (int i = 0; i < 10; i++) {
            _mgr.clearGhost(distinctHash(20 + i));
        }
        assertEquals(20, _mgr.getGhostCount());

        // re-marking a peer that is already excluded must not double count it
        _mgr.recordTimeout(distinctHash(40));
        assertEquals(20, _mgr.getGhostCount());

        // ...but that re-mark is a second timeout, so only its own cooldown
        // escalates past the base while every other mark has lapsed
        when(_clock.now()).thenReturn(NOW + 301_000L);
        assertEquals(1, _mgr.getGhostCount());
        when(_clock.now()).thenReturn(NOW + 601_000L);
        assertEquals(0, _mgr.getGhostCount());
    }

    // --- pure helpers ---

    @Test
    public void testBucketDueRoundsUpToWholeSeconds() {
        assertEquals(Long.MAX_VALUE, GhostPeerManager.bucketDue(Long.MAX_VALUE));
        assertEquals(0, GhostPeerManager.bucketDue(0));
        assertEquals(1_000L, GhostPeerManager.bucketDue(1_000L));
        assertEquals(2_000L, GhostPeerManager.bucketDue(1_001L));
        assertEquals(2_000L, GhostPeerManager.bucketDue(1_999L));
        assertEquals(1_234_000L, GhostPeerManager.bucketDue(1_234_000L));
        assertEquals(1_235_000L, GhostPeerManager.bucketDue(1_234_001L));
        // idempotent: re-bucketing an already bucketed deadline never moves it
        assertEquals(GhostPeerManager.bucketDue(1_001L),
                     GhostPeerManager.bucketDue(GhostPeerManager.bucketDue(1_001L)));
    }

    @Test
    public void testEscalationCooldownHelper() {
        assertEquals(300_000L, GhostPeerManager.escalationCooldownMs(300_000L, 0));
        assertEquals(300_000L, GhostPeerManager.escalationCooldownMs(300_000L, -1));
        assertEquals(600_000L, GhostPeerManager.escalationCooldownMs(300_000L, 1));
        assertEquals(1_200_000L, GhostPeerManager.escalationCooldownMs(300_000L, 2));
        assertEquals(1_200_000L, GhostPeerManager.escalationCooldownMs(300_000L, 99));
        assertEquals(0L, GhostPeerManager.escalationCooldownMs(0L, 5));
    }

    @Test
    public void testNextOffensesHelper() {
        assertEquals(0, GhostPeerManager.nextOffenses(0, 0, 1_000L, 60_000L));
        assertEquals(1, GhostPeerManager.nextOffenses(0, 500L, 1_000L, 60_000L));
        assertEquals(3, GhostPeerManager.nextOffenses(2, 500L, 1_000L, 60_000L));
        // decay: previous mark outside the window resets
        assertEquals(0, GhostPeerManager.nextOffenses(2, 500L, 61_000L, 60_000L));
        // clock skew / unset mark: no escalation
        assertEquals(0, GhostPeerManager.nextOffenses(2, 0, 1_000L, 60_000L));
        assertEquals(0, GhostPeerManager.nextOffenses(2, 1_000L, 500L, 60_000L));
        // negative input treated as zero
        assertEquals(0, GhostPeerManager.nextOffenses(-3, 500L, 61_000L, 60_000L));
    }
}
