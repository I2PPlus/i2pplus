package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.Banlist;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.util.LogManager;

/**
 * Contract tests for ClientPeerSelector list-returning helpers.
 * selectPeers() and its helper chain never return null: an empty list
 * is used instead, so callers may rely on a non-null List.
 */
public class ClientPeerSelectorContractTest {

    private RouterContext _ctx;
    private TunnelManagerFacade _tmf;
    private GhostPeerManager _ghostManager;
    private ClientPeerSelector _selector;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-cps-test-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        _tmf = mock(TunnelManagerFacade.class);
        when(_ctx.tunnelManager()).thenReturn(_tmf);
        _ghostManager = mock(GhostPeerManager.class);
        when(_tmf.getGhostPeerManager()).thenReturn(_ghostManager);
        _selector = new ClientPeerSelector(_ctx);
    }

    @After
    public void tearDown() {
        deleteRecursively(_tmpDir);
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {deleteRecursively(c);}
        }
        f.delete();
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (5 - b);
        return Hash.create(data);
    }

    private static List<Hash> threePeers() {
        return new ArrayList<>(Arrays.asList(hash(0), hash(1), hash(2)));
    }

    @Test
    public void testFilterGhostPeersAllGhostsReturnsEmpty() {
        when(_ghostManager.isGhost(any(Hash.class))).thenReturn(true);
        List<Hash> rv = _selector.filterGhostPeers(threePeers());
        assertNotNull(rv);
        assertTrue(rv.isEmpty());
    }

    @Test
    public void testFilterGhostPeersKeepsOnlyNonGhosts() {
        when(_ghostManager.isGhost(hash(1))).thenReturn(true);
        List<Hash> rv = _selector.filterGhostPeers(threePeers());
        assertNotNull(rv);
        assertEquals(2, rv.size());
        assertTrue(rv.contains(hash(0)));
        assertFalse(rv.contains(hash(1)));
        assertTrue(rv.contains(hash(2)));
    }

    @Test
    public void testFilterGhostPeersNoGhostsKeepsAllInOrder() {
        List<Hash> peers = threePeers();
        List<Hash> rv = _selector.filterGhostPeers(peers);
        assertEquals(peers, rv);
    }

    @Test
    public void testFilterGhostPeersNoGhostManagerKeepsAll() {
        when(_tmf.getGhostPeerManager()).thenReturn(null);
        List<Hash> peers = threePeers();
        List<Hash> rv = _selector.filterGhostPeers(peers);
        assertEquals(peers, rv);
    }

    @Test
    public void testFilterGhostPeersNullInputUnchanged() {
        assertNull(_selector.filterGhostPeers(null));
    }

    @Test
    public void testFilterGhostPeersEmptyInputUnchanged() {
        List<Hash> empty = new ArrayList<>();
        assertTrue(_selector.filterGhostPeers(empty).isEmpty());
    }

    // ---------- filterBannedPeers ----------

    @Test
    public void testFilterBannedPeersAllBannedReturnsOriginal() {
        Banlist banlist = mock(Banlist.class);
        when(_ctx.banlist()).thenReturn(banlist);
        when(banlist.isBanlisted(any(Hash.class))).thenReturn(true);
        List<Hash> peers = threePeers();
        List<Hash> rv = _selector.filterBannedPeers(peers);
        assertNotNull(rv);
        assertEquals(peers.size(), rv.size());
        assertTrue(rv.containsAll(peers));
    }

    @Test
    public void testFilterBannedPeersDropsOnlyBanned() {
        Banlist banlist = mock(Banlist.class);
        when(_ctx.banlist()).thenReturn(banlist);
        when(banlist.isBanlisted(hash(1))).thenReturn(true);
        when(banlist.isBanlisted(hash(0))).thenReturn(false);
        when(banlist.isBanlisted(hash(2))).thenReturn(false);
        List<Hash> rv = _selector.filterBannedPeers(threePeers());
        assertNotNull(rv);
        assertEquals(2, rv.size());
        assertTrue(rv.contains(hash(0)));
        assertFalse(rv.contains(hash(1)));
        assertTrue(rv.contains(hash(2)));
    }

    @Test
    public void testFilterBannedPeersNoBannedKeepsAll() {
        Banlist banlist = mock(Banlist.class);
        when(_ctx.banlist()).thenReturn(banlist);
        when(banlist.isBanlisted(any(Hash.class))).thenReturn(false);
        List<Hash> peers = threePeers();
        List<Hash> rv = _selector.filterBannedPeers(peers);
        assertEquals(peers, rv);
    }

    @Test
    public void testFilterBannedPeersNullInputUnchanged() {
        assertNull(_selector.filterBannedPeers(null));
    }

    @Test
    public void testFilterBannedPeersEmptyInputUnchanged() {
        List<Hash> empty = new ArrayList<>();
        assertTrue(_selector.filterBannedPeers(empty).isEmpty());
    }
}
