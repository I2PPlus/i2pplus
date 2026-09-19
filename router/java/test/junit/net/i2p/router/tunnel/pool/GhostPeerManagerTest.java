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
        // Defaults mirror the real ones: timeout threshold 2, cooldown 300s
        // (120s under stress, which getTunnelBuildSuccess() toggles).
        // Note: the cooldown literals in GhostPeerManager are ints, so the
        // (String, int) overload is the one that matters here.
        when(_ctx.getProperty(anyString(), anyInt())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if ("i2p.tunnel.ghostPeer.attackCooldownMs".equals(key)) {return 120_000;}
            if ("i2p.tunnel.ghostPeer.cooldownMs".equals(key)) {return 300_000;}
            return 2; // i2p.tunnel.ghostPeer.timeoutThreshold
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
        assertFalse("below threshold (1 of 3)", _mgr.isGhost(hash(1)));
        assertEquals(0, _mgr.getGhostCount());
        _mgr.recordTimeout(hash(1));
        assertFalse("below threshold (2 of 3)", _mgr.isGhost(hash(1)));
        _mgr.recordTimeout(hash(1));
        assertTrue("at threshold (3 of 3)", _mgr.isGhost(hash(1)));
        assertEquals(1, _mgr.getGhostCount());
    }

    @Test
    public void testGhostExpiresAfterCooldown() {
        _mgr.recordTimeout(hash(1));
        _mgr.recordTimeout(hash(1));
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
        for (int i = 0; i < 5; i++) _mgr.recordTimeout(hash(1));
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
        for (int i = 0; i < 5; i++) _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));
        // ...network recovers mid-cooldown: the 120s grant must not be extended to 300s
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.9);
        when(_clock.now()).thenReturn(NOW + 121_000L);
        assertFalse("released per the cooldown at mark time", _mgr.isGhost(hash(1)));

        // and the reverse: a normal (300s) mark must not be shortened by stress
        when(_clock.now()).thenReturn(NOW);
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.9);
        for (int i = 0; i < 3; i++) _mgr.recordTimeout(hash(2));
        when(_organizer.getTunnelBuildSuccess()).thenReturn(0.2);
        when(_clock.now()).thenReturn(NOW + 200_000L);
        assertTrue("300s grant respected", _mgr.isGhost(hash(2)));
    }

    @Test
    public void testSuccessClearsGhost() {
        _mgr.recordTimeout(hash(1));
        _mgr.recordTimeout(hash(1));
        _mgr.recordTimeout(hash(1));
        assertTrue(_mgr.isGhost(hash(1)));
        _mgr.recordSuccess(hash(1));
        assertFalse("success clears the mark", _mgr.isGhost(hash(1)));
        assertEquals(0, _mgr.getGhostCount());
    }

    @Test
    public void testClearGhost() {
        _mgr.recordTimeout(hash(1));
        _mgr.recordTimeout(hash(1));
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
}