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
import net.i2p.stat.StatManager;
import net.i2p.util.LogManager;

/**
 * Tests for GhostPeerManager:
 * - peers are excluded only after the timeout threshold
 * - exclusions expire after one flat cooldown, in every build-success state
 * - repeat timeouts never lengthen that cooldown
 * - the cooldown is snapshotted at mark time (state changes don't extend it)
 * - success and clearGhost release the peer
 * - the router itself is never tracked.
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
        // Defaults mirror the real ones: timeout threshold 1, cooldown 90s.
        // There is no attack-mode cooldown and no escalation, so one property
        // covers the exclusion length.
        // Note: the cooldown literal in GhostPeerManager is an int, so the
        // (String, int) overload is the one that matters here.
        when(_ctx.getProperty(anyString(), anyInt())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if ("i2p.tunnel.ghostPeer.cooldownMs".equals(key)) {return 90_000;}
            return 1; // i2p.tunnel.ghostPeer.timeoutThreshold
        });
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);
        // GhostPeerManager counts marks added and cleared. The real StatManager is
        // never null in production, so this is a gap in the mock rather than
        // something the manager should guard against.
        when(_ctx.statManager()).thenReturn(mock(StatManager.class));

        _clock = mock(Clock.class);
        when(_clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(_clock);

        _organizer = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(_organizer);
        // Above the attack threshold; build success no longer changes the
        // cooldown, it only annotates the log line.
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

    /**
     * Distinct across the full int range (hash(int) wraps every 256).
     *
     * @param i the value to derive a hash from; its low four bytes are copied in
     *          little-endian order, so no two values collide
     * @return a hash whose first four bytes encode i, distinct for every int
     */
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

        when(_clock.now()).thenReturn(NOW + 60_000L); // cooldown is 90s
        assertTrue("still excluded mid-cooldown", _mgr.isGhost(hash(1)));

        when(_clock.now()).thenReturn(NOW + 91_000L);
        assertFalse("released after cooldown", _mgr.isGhost(hash(1)));
        assertEquals(0, _mgr.getGhostCount());
    }

    @Test
    public void testCooldownIsFlatUnderStress() {
        // Build success no longer changes the exclusion length. It used to drop
        // it to 120s under attack and escalate to 240s on a repeat; now every
        // state gets the same window.
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.2); // below attack threshold
        _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));

        when(_clock.now()).thenReturn(NOW + 60_000L);
        assertTrue("still excluded before 90s", _mgr.isGhost(hash(1)));

        when(_clock.now()).thenReturn(NOW + 91_000L);
        assertFalse("released after the same 90s stress cooldown", _mgr.isGhost(hash(1)));
    }

    @Test
    public void testCooldownSnapshottedAtMarkTime() {
        // Marked while build success is healthy...
        _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));
        // ...and the grant must not be extended by a later state change.
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.2);
        when(_clock.now()).thenReturn(NOW + 91_000L);
        assertFalse("released per the cooldown at mark time", _mgr.isGhost(hash(1)));
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

    // --- flat cooldown: no escalation for repeat timeouts ---

    @Test
    public void testRepeatTimeoutDoesNotExtendCooldown() {
        // The regression this pins: a repeat timeout used to double the cooldown
        // (2x base, capped). The mark is now a flat rebuff, and every one of
        // those windows sat inside the 5-minute first-hop cooldown that
        // passesBasicGates tests anyway, so the doubling bought no extra
        // protection — it only outlived the build burst it was written to cover.
        _mgr.recordTimeout(hash(1));
        when(_clock.now()).thenReturn(NOW + 91_000L);
        assertFalse("first cooldown expired", _mgr.isGhost(hash(1)));

        _mgr.recordTimeout(hash(1)); // second offense, well inside the decay window
        when(_clock.now()).thenReturn(NOW + 91_000L + 60_000L);
        assertTrue("re-marked", _mgr.isGhost(hash(1)));
        when(_clock.now()).thenReturn(NOW + 91_000L + 91_000L);
        assertFalse("repeat mark released after the same flat cooldown",
                    _mgr.isGhost(hash(1)));
    }

    @Test
    public void testManyRepeatTimeoutsNeverGrowTheCooldown() {
        // Six repeats inside the decay window must all grant the same window;
        // the old cap was 2x base, and nothing here may exceed 1x.
        long at = NOW;
        for (int i = 0; i < 6; i++) {
            at += 91_000L;
            when(_clock.now()).thenReturn(at);
            _mgr.recordTimeout(hash(1));
        }
        when(_clock.now()).thenReturn(at + 89_000L);
        assertTrue("still excluded just before the flat cooldown ends",
                   _mgr.isGhost(hash(1)));
        when(_clock.now()).thenReturn(at + 91_000L);
        assertFalse("released after exactly one cooldown, not six",
                    _mgr.isGhost(hash(1)));
    }

    @Test
    public void testOffenseDecaysAfterWindow() {
        _mgr.recordTimeout(hash(1));
        // repeat much later than OFFENSE_DECAY_MS: history decayed, strike 0 again
        when(_clock.now()).thenReturn(NOW + GhostPeerManager.OFFENSE_DECAY_MS + 1_000L);
        assertFalse("original mark expired", _mgr.isGhost(hash(1)));
        _mgr.recordTimeout(hash(1));
        when(_clock.now()).thenReturn(NOW + GhostPeerManager.OFFENSE_DECAY_MS + 91_000L);
        assertFalse("decayed repeat released after the flat cooldown",
                    _mgr.isGhost(hash(1)));
    }

    @Test
    public void testSuccessClearsOffenseHistory() {
        _mgr.recordTimeout(hash(1));
        _mgr.recordSuccess(hash(1));
        when(_clock.now()).thenReturn(NOW + 1_000L);
        _mgr.recordTimeout(hash(1)); // proven live since: a first strike again
        when(_clock.now()).thenReturn(NOW + 1_000L + 91_000L);
        assertFalse("success reset the strike count", _mgr.isGhost(hash(1)));
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
     * The default is two consecutive timeouts, not one. A build timeout is the
     * commonest transient outcome of asking a peer for a tunnel on a congested
     * path — a router measured at 52% build success times out on roughly half of
     * its requests — so one of them says more about the path than about the peer,
     * and the mark it produced blocked tier promotion for 90 seconds.
     *
     * @see #stubThresholdAndCooldown(int, int)
     */
    @Test
    public void testDefaultThresholdIsTwoTimeouts() {
        // Every property unset: getProperty returns the default it was given,
        // which is what a router with no i2p.tunnel.* overrides does.
        when(_ctx.getProperty(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        GhostPeerManager mgr = new GhostPeerManager(_ctx);
        assertEquals("one timeout must not exclude a peer", 2, mgr.getThreshold());

        Hash peer = hash(1);
        mgr.recordTimeout(peer);
        assertFalse("a single build timeout is not evidence about the peer",
                    mgr.isGhost(peer));
        mgr.recordTimeout(peer);
        assertTrue("two inside the strike window is", mgr.isGhost(peer));
    }

    /**
     * The property stays tunable, so an operator who wants the old one-strike
     * behaviour can have it.
     */
    @Test
    public void testThresholdOneIsStillHonoured() {
        stubThresholdAndCooldown(1, 90_000);
        assertEquals(1, _mgr.getThreshold());
        _mgr.recordTimeout(hash(1));
        assertTrue("one strike excludes at threshold 1", _mgr.isGhost(hash(1)));
    }

    /**
     * Rebuild the manager with a custom timeout threshold and cooldown,
     * keeping every other property at its real default.
     *
     * @param threshold the timeouts a peer must accumulate before it is marked a
     *                  ghost; above 1 the first strike is only tracked
     * @param cooldownMs how long a marked peer stays excluded, in milliseconds
     */
    private void stubThresholdAndCooldown(final int threshold, final int cooldownMs) {
        when(_ctx.getProperty(anyString(), anyInt())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if ("i2p.tunnel.ghostPeer.cooldownMs".equals(key)) {return cooldownMs;}
            if ("i2p.tunnel.ghostPeer.timeoutThreshold".equals(key)) {return threshold;}
            return 1;
        });
        _mgr = new GhostPeerManager(_ctx);
    }

    @Test
    public void testThresholdTwoTracksButDoesNotExcludeFirstStrike() {
        stubThresholdAndCooldown(2, 90_000);
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
        _mgr.recordTimeout(hash(1));                       // expires NOW + 90s
        when(_clock.now()).thenReturn(NOW + 30_000L);
        _mgr.recordTimeout(hash(2));                       // expires NOW + 120s
        assertEquals(2, _mgr.getGhostCount());

        // the cached due-time is the earliest expiry: the first lapse forces
        // a rescan and leaves only the second mark active
        when(_clock.now()).thenReturn(NOW + 91_000L);
        assertEquals(1, _mgr.getGhostCount());

        when(_clock.now()).thenReturn(NOW + 121_000L);
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

        // Re-marking the dropped peer must still see its original strike: this is
        // its second strike inside the decay window. A cap that dropped the
        // history instead would treat this as a first strike.
        long at = NOW + GhostPeerManager.MAX_ACTIVE_GHOSTS;
        when(_clock.now()).thenReturn(at);
        _mgr.recordTimeout(distinctHash(0));
        assertTrue("re-marked", _mgr.isGhost(distinctHash(0)));
        when(_clock.now()).thenReturn(at + 89_000L);
        assertTrue("still excluded just before the flat cooldown ends",
                   _mgr.isGhost(distinctHash(0)));
        when(_clock.now()).thenReturn(at + 91_000L);
        assertFalse("released after the same flat cooldown", _mgr.isGhost(distinctHash(0)));
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

        // A later re-mark pushes only that peer's expiry forward by one flat
        // cooldown; the other 19 lapse on schedule. (Re-marking at the same
        // instant would leave every mark expiring together, which is correct but
        // would not distinguish a moved expiry from an unchanged one.)
        when(_clock.now()).thenReturn(NOW + 30_000L);
        _mgr.recordTimeout(distinctHash(40));
        assertEquals(20, _mgr.getGhostCount());

        when(_clock.now()).thenReturn(NOW + 91_000L);
        assertEquals("only the re-marked peer survives its fresh window", 1,
                     _mgr.getGhostCount());
        when(_clock.now()).thenReturn(NOW + 121_000L);
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
