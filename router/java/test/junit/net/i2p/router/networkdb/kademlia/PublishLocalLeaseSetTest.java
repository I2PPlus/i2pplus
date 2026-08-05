package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.TunnelId;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Job;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.LogManager;
import net.i2p.util.SimpleTimer2;

/**
 * End-to-end verification of publish() for a local client whose LeaseSet is
 * not published to the network (i2cp.dontPublishLeaseSet, e.g. the HTTP
 * proxy).  The local copy must be stored and a republish job scheduled so it
 * is re-minted before its ~6-minute lease expires — previously publish()
 * returned before scheduling anything and the LeaseSet sat expired in the
 * local netdb for minutes.
 */
public class PublishLocalLeaseSetTest {
    private static final long NOW = 1_000_000_000L;

    private RouterContext _ctx;
    private ClientManagerFacade _cm;
    private JobQueue _jobQueue;
    private KademliaNetworkDatabaseFacade _facade;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-lestest-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        Router router = mock(Router.class);
        when(router.getNetworkID()).thenReturn(1);
        when(router.getUptime()).thenReturn(60_000L);
        when(router.getRouterHash()).thenReturn(new Hash(new byte[Hash.HASH_LENGTH]));
        when(router.getSharePercentage()).thenReturn(0.5d);
        when(router.gracefulShutdownInProgress()).thenReturn(false);
        when(_ctx.router()).thenReturn(router);

        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);

        when(_ctx.statManager()).thenReturn(mock(StatManager.class));

        _jobQueue = mock(JobQueue.class);
        when(_ctx.jobQueue()).thenReturn(_jobQueue);

        FloodfillNetworkDatabaseFacade mainDb = mock(FloodfillNetworkDatabaseFacade.class);
        when(mainDb.getPeerSelector()).thenReturn(null);
        when(mainDb.getKBuckets()).thenReturn(null);
        when(mainDb.blindCache()).thenReturn(mock(BlindCache.class));
        when(_ctx.netDb()).thenReturn(mainDb);

        when(_ctx.getRouterDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyInt())).thenReturn(0);
        when(_ctx.getProperty(anyString(), anyLong())).thenReturn(0L);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(null);
        SimpleTimer2 timer = new SimpleTimer2(_ctx, "test");
        when(_ctx.simpleTimer2()).thenReturn(timer);

        CommSystemFacade commSystem = mock(CommSystemFacade.class);
        when(commSystem.isDummy()).thenReturn(true);
        when(_ctx.commSystem()).thenReturn(commSystem);

        _cm = mock(ClientManagerFacade.class);
        when(_ctx.clientManager()).thenReturn(_cm);

        Hash dbid = new Hash(new byte[Hash.HASH_LENGTH]);
        dbid.getData()[0] = 1;
        _facade = new FloodfillNetworkDatabaseFacade(_ctx, dbid);
        _facade.startup();
    }

    @After
    public void tearDown() {
        deleteRecursively(_tmpDir);
    }

    /**
     * An active local client that does not publish must be stored locally AND
     * get a republish job so the copy is re-minted before lease expiry.
     *
     * @throws Exception on crypto setup failure
     */
    @Test
    public void testPublishNonPublishingLocalClientStoresAndSchedulesRepublish() throws Exception {
        LeaseSet ls = createSignedLeaseSet(NOW);
        Hash hash = ls.getHash();
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(false);
        when(_cm.isLocal(hash)).thenReturn(true);

        _facade.publish(ls);

        assertTrue("local copy must be stored", _facade.getDataStore().get(hash) == ls);
        assertTrue("local non-publishing client must have an active republish job",
                   _facade.hasActiveRepublishJob(hash));
        verify(_jobQueue).addJobToTop(any(Job.class));
    }

    /**
     * A client that is no longer local must still be stored locally but must
     * NOT get a republish job — the republish job itself cleans it up once it
     * runs.
     *
     * @throws Exception on crypto setup failure
     */
    @Test
    public void testPublishNonPublishingNonLocalClientStoresWithoutRepublish() throws Exception {
        LeaseSet ls = createSignedLeaseSet(NOW);
        Hash hash = ls.getHash();
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(false);
        when(_cm.isLocal(hash)).thenReturn(false);

        _facade.publish(ls);

        assertTrue("local copy must still be stored", _facade.getDataStore().get(hash) == ls);
        assertFalse("stopped client must not have a republish job",
                    _facade.hasActiveRepublishJob(hash));
        verify(_jobQueue, never()).addJobToTop(any(Job.class));
    }

    /**
     * A publishing local client must still schedule a republish — the new
     * non-publishing bypass must not disturb the normal path.
     *
     * @throws Exception on crypto setup failure
     */
    @Test
    public void testPublishPublishingLocalClientStillSchedules() throws Exception {
        LeaseSet ls = createSignedLeaseSet(NOW);
        Hash hash = ls.getHash();
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(true);
        when(_cm.isLocal(hash)).thenReturn(true);

        _facade.publish(ls);

        assertTrue("publishing local client must have an active republish job",
                   _facade.hasActiveRepublishJob(hash));
    }

    /**
     * Builds a valid, self-signed legacy LeaseSet with a single lease that
     * expires two minutes after {@code now}, matching what the router's own
     * clients publish.
     *
     * @param now the current time in ms
     * @return the signed LeaseSet
     * @throws Exception on key generation or signing failure
     */
    private LeaseSet createSignedLeaseSet(long now) throws Exception {
        Object[] keypair = KeyGenerator.getInstance().generatePKIKeypair();
        Object[] signingKeypair = KeyGenerator.getInstance().generateSigningKeypair();
        PublicKey pub = (PublicKey) keypair[0];
        SigningPublicKey spk = (SigningPublicKey) signingKeypair[0];
        SigningPrivateKey spkPriv = (SigningPrivateKey) signingKeypair[1];

        Destination dest = new Destination();
        dest.setPublicKey(pub);
        dest.setSigningPublicKey(spk);
        dest.setCertificate(new Certificate());

        LeaseSet ls = new LeaseSet();
        ls.setDestination(dest);
        ls.setEncryptionKey(pub);
        ls.setSigningKey(spk);

        Lease lease = new Lease();
        lease.setGateway(new Hash(new byte[Hash.HASH_LENGTH]));
        lease.setTunnelId(new TunnelId(5));
        lease.setEndDate(now + 2L * 60 * 1000);
        ls.addLease(lease);

        ls.sign(spkPriv);
        return ls;
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