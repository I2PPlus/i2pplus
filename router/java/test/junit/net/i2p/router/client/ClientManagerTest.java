package net.i2p.router.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Collections;
import java.util.Date;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.KeyManager;
import net.i2p.router.RouterContext;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;
import net.i2p.util.LogManager;
import net.i2p.util.RandomSource;
import net.i2p.util.SimpleTimer2;

/**
 * A client that reconnects with a destination that a previous dead connection
 * still owns must replace the stale registration instead of being refused,
 * and the stale connection's teardown must not drop the new registration. A
 * connection that is still live is only replaced by a session the destination
 * authorized later, so a replay of the live session's own CreateSession - the
 * frame's date is inside its signature and nothing else distinguishes one
 * session from the next - cannot take the destination over.
 */
public class ClientManagerTest {

    /** Port the manager under test is built with; it opens no socket. */
    private static final int PORT = 7654;

    /** Mocked context the manager reads its config dir, stats and keys from. */
    private RouterContext _ctx;
    /** The manager under test. */
    private ClientManager _manager;
    /** Scratch config dir, removed afterwards. */
    private File _tmpDir;

    /** Builds a fresh manager for each case; it holds no state of its own. */
    public ClientManagerTest() {}

    /**
     * Builds the manager over a mocked context with room for every session ID
     * the cases allocate.
     *
     * @throws Exception declared for the test harness, never thrown here
     */
    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-clientmgr-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);
        when(_ctx.statManager()).thenReturn(mock(StatManager.class));
        when(_ctx.simpleTimer2()).thenReturn(mock(SimpleTimer2.class));
        RandomSource rnd = mock(RandomSource.class);
        when(rnd.nextInt(anyInt())).thenReturn(500, 501, 502, 503, 504, 505);
        when(_ctx.random()).thenReturn(rnd);
        CommSystemFacade csf = mock(CommSystemFacade.class);
        when(csf.isDummy()).thenReturn(false);
        when(_ctx.commSystem()).thenReturn(csf);
        // a replaced session's lease-set keys are unregistered at the handover
        when(_ctx.keyManager()).thenReturn(mock(KeyManager.class));
        // room for all session IDs allocated in the tests
        when(_ctx.getProperty(anyString(), anyInt())).thenReturn(1536);

        _manager = new ClientManager(_ctx, PORT);
    }

    /** Removes the scratch config dir. */
    @After
    public void tearDown() {
        deleteRecursively(_tmpDir);
    }

    /**
     * A second connection that claims the same destination gets
     * STATUS_CREATED and the stale runner is disconnected, so the client can
     * re-establish immediately after a session loss.
     */
    @Test
    public void testSecondConnectionReplacesStaleOne() {
        Destination dest = createDestination();
        ClientConnectionRunner stale = mock(ClientConnectionRunner.class);
        ClientConnectionRunner fresh = mock(ClientConnectionRunner.class);
        when(stale.getDestinations()).thenReturn(Collections.singletonList(dest));
        when(stale.getSessionIds()).thenReturn(Collections.emptyList());

        assertEquals(SessionStatusMessage.STATUS_CREATED, _manager.destinationEstablished(stale, dest));
        assertEquals("Second registration must not be refused",
                     SessionStatusMessage.STATUS_CREATED, _manager.destinationEstablished(fresh, dest));
        verify(stale).disconnectClient("Replaced by a new connection for the same destination", Log.WARN);
        assertSame(fresh, _manager.getRunner(dest));
    }

    /**
     * The stale runner's teardown (unregisterConnection) must not drop
     * the replacement registration, so the new connection survives even if
     * the old one is cleaned up later.
     */
    @Test
    public void testStaleTeardownDoesNotRemoveReplacement() {
        Destination dest = createDestination();
        ClientConnectionRunner stale = mock(ClientConnectionRunner.class);
        ClientConnectionRunner fresh = mock(ClientConnectionRunner.class);
        when(stale.getDestinations()).thenReturn(Collections.singletonList(dest));
        when(stale.getSessionIds()).thenReturn(Collections.emptyList());
        when(fresh.getDestinations()).thenReturn(Collections.singletonList(dest));
        when(fresh.getSessionIds()).thenReturn(Collections.emptyList());

        _manager.destinationEstablished(stale, dest);
        _manager.destinationEstablished(fresh, dest);
        assertEquals(fresh, _manager.getRunner(dest));

        // The stale runner's full teardown runs after it was disconnected
        _manager.unregisterConnection(stale);
        // The replacement must still be registered
        assertSame(fresh, _manager.getRunner(dest));

        // The fresh runner's own cleanup removes it
        _manager.unregisterConnection(fresh);
        assertNull(_manager.getRunner(dest));
    }

    /**
     * A byte-for-byte replay of the live connection's own CreateSession carries
     * that frame's date, so it is refused and the owner keeps the destination.
     */
    @Test
    public void testReplayedSessionDoesNotReplaceLiveOne() {
        Destination dest = createDestination();
        Hash hash = dest.calculateHash();
        Date authorized = new Date(1_700_000_000_000L);
        ClientConnectionRunner live = mock(ClientConnectionRunner.class);
        ClientConnectionRunner replay = mock(ClientConnectionRunner.class);
        when(live.getDestinations()).thenReturn(Collections.singletonList(dest));
        when(live.getConfig(hash)).thenReturn(sessionConfig(authorized));
        when(replay.getConfig(hash)).thenReturn(sessionConfig(authorized));

        assertEquals(SessionStatusMessage.STATUS_CREATED, _manager.destinationEstablished(live, dest));
        assertEquals("A replayed CreateSession must not take over a live session",
                     SessionStatusMessage.STATUS_DUP_DEST,
                     _manager.destinationEstablished(replay, dest));
        assertSame(live, _manager.getRunner(dest));
        verify(live, never()).disconnectClient(anyString(), anyInt());
    }

    /**
     * A session the destination authorized later does take over a live
     * connection, which is what a reconnect with fresh keys, or a second holder
     * of the keys that waits its turn, looks like.
     */
    @Test
    public void testNewerSessionReplacesLiveOne() {
        Destination dest = createDestination();
        Hash hash = dest.calculateHash();
        Date authorized = new Date(1_700_000_000_000L);
        ClientConnectionRunner live = mock(ClientConnectionRunner.class);
        ClientConnectionRunner fresh = mock(ClientConnectionRunner.class);
        when(live.getDestinations()).thenReturn(Collections.singletonList(dest));
        when(fresh.getDestinations()).thenReturn(Collections.singletonList(dest));
        when(live.getConfig(hash)).thenReturn(sessionConfig(authorized));
        when(fresh.getConfig(hash)).thenReturn(sessionConfig(new Date(authorized.getTime() + 60 * 1000L)));

        assertEquals(SessionStatusMessage.STATUS_CREATED, _manager.destinationEstablished(live, dest));
        assertEquals(SessionStatusMessage.STATUS_CREATED, _manager.destinationEstablished(fresh, dest));
        assertSame(fresh, _manager.getRunner(dest));
        verify(live).disconnectClient("Replaced by a new connection for the same destination", Log.WARN);
    }

    /**
     * Different destinations on different connections coexist
     * normally, no replacement involved.
     */
    @Test
    public void testDistinctDestinationsCoexist() {
        Destination destA = createDestination();
        Destination destB = createDestination();
        ClientConnectionRunner rA = mock(ClientConnectionRunner.class);
        ClientConnectionRunner rB = mock(ClientConnectionRunner.class);
        when(rA.getDestinations()).thenReturn(Collections.singletonList(destA));
        when(rB.getDestinations()).thenReturn(Collections.singletonList(destB));

        assertEquals(SessionStatusMessage.STATUS_CREATED, _manager.destinationEstablished(rA, destA));
        assertEquals(SessionStatusMessage.STATUS_CREATED, _manager.destinationEstablished(rB, destB));
        assertSame(rA, _manager.getRunner(destA));
        assertSame(rB, _manager.getRunner(destB));
    }

    /**
     * Uses KeyGenerator for real keys so calculateHash() works.
     *
     * @return a Destination carrying freshly generated PKI and signing public
     *         keys and an empty certificate
     */
    private Destination createDestination() {
        Object[] keypair = KeyGenerator.getInstance().generatePKIKeypair();
        Object[] signingKeypair = KeyGenerator.getInstance().generateSigningKeypair();
        PublicKey pub = (PublicKey) keypair[0];
        SigningPublicKey spk = (SigningPublicKey) signingKeypair[0];

        Destination dest = new Destination();
        dest.setPublicKey(pub);
        dest.setSigningPublicKey(spk);
        dest.setCertificate(new Certificate());
        return dest;
    }

    /**
     * A session config carrying only the authorization date the takeover
     * decision reads.
     *
     * @param authorized the date the destination authorized the session
     * @return the config, with no destination or options set
     */
    private static SessionConfig sessionConfig(Date authorized) {
        SessionConfig cfg = new SessionConfig();
        cfg.setCreationDate(authorized);
        return cfg;
    }

    /**
     * Recursively delete a temporary directory tree.
     *
     * @param file the directory to delete
     */
    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
