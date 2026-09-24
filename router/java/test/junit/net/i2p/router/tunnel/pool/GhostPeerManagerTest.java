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

    // --- pure helpers ---

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
