package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.LogManager;

/**
 * Tests for the cooldown exclusion reader: it must admit exactly the entries
 * still inside their cooldown window (value > cutoff) and must never mutate
 * the shared cooldown map — expiry is handled lazily at read time and by
 * periodic pruning, not by sweeping the map on every selection.
 */
public class ClientPeerSelectorEvictionTest {

    private static final long CUTOFF = 1_000_000L;

    private RouterContext _ctx;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-cpsev-test-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());
        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);
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
        data[1] = (byte) (9 - b);
        return Hash.create(data);
    }

    @Test
    public void testAdmitsOnlyFreshEntries() {
        Hash fresh = hash(1), expired = hash(2), edge = hash(3);
        Map<Hash, Long> cooldowns = new HashMap<>();
        cooldowns.put(fresh, CUTOFF + 1);
        cooldowns.put(expired, CUTOFF);
        cooldowns.put(edge, CUTOFF - 10);
        Set<Hash> exclude = new HashSet<>();
        int count = ClientPeerSelector.addFreshCooldownExclusions(cooldowns, CUTOFF, exclude);
        assertEquals(1, count);
        assertEquals(new HashSet<Hash>() {{add(fresh);}}, exclude);
    }

    @Test
    public void testDoesNotMutateTheMap() {
        Hash a = hash(1), b = hash(2);
        Map<Hash, Long> cooldowns = new HashMap<>();
        cooldowns.put(a, CUTOFF - 100);
        cooldowns.put(b, CUTOFF + 100);
        Set<Hash> exclude = new HashSet<>();
        ClientPeerSelector.addFreshCooldownExclusions(cooldowns, CUTOFF, exclude);
        assertEquals(2, cooldowns.size());
        assertTrue(cooldowns.containsKey(a));
        assertTrue(cooldowns.containsKey(b));
    }

    @Test
    public void testEmptyMap() {
        Set<Hash> exclude = new HashSet<>();
        assertEquals(0, ClientPeerSelector.addFreshCooldownExclusions(new HashMap<Hash, Long>(), CUTOFF, exclude));
        assertTrue(exclude.isEmpty());
    }

    @Test
    public void testAllExpired() {
        Map<Hash, Long> cooldowns = new HashMap<>();
        cooldowns.put(hash(1), CUTOFF);
        cooldowns.put(hash(2), CUTOFF - 5);
        Set<Hash> exclude = new HashSet<>();
        assertEquals(0, ClientPeerSelector.addFreshCooldownExclusions(cooldowns, CUTOFF, exclude));
        assertTrue(exclude.isEmpty());
    }
}