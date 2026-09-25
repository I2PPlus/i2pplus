package net.i2p.router.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2cp.CreateLeaseSet2Message;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.KeyManager;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;
import net.i2p.util.RandomSource;
import net.i2p.util.SimpleTimer2;

/**
 * Lease request failure and retry behavior: every early-failure path must
 * deliver onFailed exactly once and clear the session slot, a stale job run
 * must not account for an already-resolved request, and the expired-published
 * rerequest path must re-queue the same state a bounded number of times
 * instead of waiting out the full timeout check.
 */
public class LeaseRequestFailureTest {
    private static final long NOW = 1_000_000_000L;
    private static final long EXPIRATION_MS = 60_000L;

    private RouterContext _ctx;
    private ClientManager _cm;
    private JobQueue _jobQueue;
    private ClientConnectionRunner _runner;
    private File _tmpDir;

    @Before
    public void setUp() {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-leasesetreq-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());

        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString()))
                .thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
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

        when(_ctx.netDb()).thenReturn(mock(FloodfillNetworkDatabaseFacade.class));

        when(_ctx.getRouterDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyInt())).thenReturn(0);
        when(_ctx.getProperty(anyString(), anyLong())).thenReturn(0L);
        when(_ctx.getProperty(anyString(), anyString())).thenReturn(null);
        // Real pool: TimedEvent.schedule() reaches SimpleTimer2's private
        // schedule() through a synthetic accessor, which a mock cannot
        // intercept. Scheduled events are far-future or tied to jobs that
        // never run here, so nothing fires mid-test. Built before the stub
        // because the constructor calls back into the mock.
        SimpleTimer2 timer = new SimpleTimer2(_ctx, "test");
        when(_ctx.simpleTimer2()).thenReturn(timer);

        CommSystemFacade commSystem = mock(CommSystemFacade.class);
        when(commSystem.isDummy()).thenReturn(true);
        when(_ctx.commSystem()).thenReturn(commSystem);

        when(_ctx.clientManager()).thenReturn(mock(ClientManagerFacade.class));

        TunnelManagerFacade tunnelManager = mock(TunnelManagerFacade.class);
        when(tunnelManager.getOutboundClientTunnelCount(any(Hash.class))).thenReturn(1);
        when(_ctx.tunnelManager()).thenReturn(tunnelManager);

        when(_ctx.keyManager()).thenReturn(mock(KeyManager.class));

        RandomSource rnd = mock(RandomSource.class);
        when(rnd.nextInt()).thenReturn(7);
        when(_ctx.random()).thenReturn(rnd);

        _cm = mock(ClientManager.class);
        when(_cm.destinationEstablished(any(ClientConnectionRunner.class), any(Destination.class)))
                .thenReturn(SessionStatusMessage.STATUS_CREATED);
        _runner = new ClientConnectionRunner(_ctx, _cm, null);
    }

    @After
    public void tearDown() {
        deleteRecursively(_tmpDir);
    }

    /**
     * The requested LeaseSet is null: the job fails early, delivers onFailed
     * once, and clears the slot. A second run (a retry re-run) must not fire
     * a second callback.
     */
    @Test
    public void earlyFailureDeliversOnFailedExactlyOnce() throws Exception {
        Destination dest = createDestination();
        assertEquals(SessionStatusMessage.STATUS_CREATED,
                _runner.sessionEstablished(new SessionConfig(dest)));
        Job onGranted = namedJob("onGranted");
        Job onFailed = namedJob("onFailed");
        _runner.requestLeaseSet(dest.calculateHash(), createLeaseSet(dest), EXPIRATION_MS,
                onGranted, onFailed);
        RequestLeaseSetJob job = lastRequestJob();
        job.runJob();
        job.runJob();
        assertEquals(1, countOf(onFailed));
        assertEquals(0, countOf(onGranted));
        assertNull(_runner.getLeaseRequest(dest.calculateHash()));
        assertFalse(_runner.getIsDead());
    }

    /**
     * SessionId exists but there is no output stream: doSend() throws
     * I2CPMessageException and the catch must resolve exactly once.
     */
    @Test
    public void sendFailureDeliversOnFailedExactlyOnce() throws Exception {
        Destination dest = createDestination();
        assertEquals(SessionStatusMessage.STATUS_CREATED,
                _runner.sessionEstablished(new SessionConfig(dest)));
        _runner.setSessionId(dest.calculateHash(), new SessionId(9));
        Job onFailed = namedJob("onFailed");
        _runner.requestLeaseSet(dest.calculateHash(), createLeaseSet(dest), EXPIRATION_MS,
                null, onFailed);
        RequestLeaseSetJob job = lastRequestJob();
        job.runJob();
        job.runJob();
        assertEquals(1, countOf(onFailed));
        assertNull(_runner.getLeaseRequest(dest.calculateHash()));
        assertFalse(_runner.getIsDead());
    }

    /**
     * Once the request has been resolved elsewhere (slot cleared), a delayed
     * job run must bail out without delivering a second resolution.
     */
    @Test
    public void resolvedRequestIsNotAccountedAgain() throws Exception {
        Destination dest = createDestination();
        assertEquals(SessionStatusMessage.STATUS_CREATED,
                _runner.sessionEstablished(new SessionConfig(dest)));
        Job onFailed = namedJob("onFailed");
        _runner.requestLeaseSet(dest.calculateHash(), createLeaseSet(dest), EXPIRATION_MS,
                null, onFailed);
        RequestLeaseSetJob job = lastRequestJob();
        LeaseRequestState state = _runner.getLeaseRequest(dest.calculateHash());
        assertNotNull(state);
        _runner.failLeaseRequest(state);
        job.runJob();
        assertEquals(0, countOf(onFailed));
        assertNull(_runner.getLeaseRequest(dest.calculateHash()));
    }

    /**
     * The rerequest path re-runs the same state so the slot, callbacks, and
     * deadline survive, and stops after the bound.
     */
    @Test
    public void rerequestRequeuesSameStateWithinBound() throws Exception {
        Destination dest = createDestination();
        assertEquals(SessionStatusMessage.STATUS_CREATED,
                _runner.sessionEstablished(new SessionConfig(dest)));
        _runner.requestLeaseSet(dest.calculateHash(), createLeaseSet(dest), EXPIRATION_MS,
                null, null);
        LeaseRequestState state = _runner.getLeaseRequest(dest.calculateHash());
        assertNotNull(state);
        assertTrue(_runner.rerequestAfterTransientPublishFailure(dest));
        assertSame(state, _runner.getLeaseRequest(dest.calculateHash()));
        assertTrue(_runner.rerequestAfterTransientPublishFailure(dest));
        assertTrue(_runner.rerequestAfterTransientPublishFailure(dest));
        assertFalse(_runner.rerequestAfterTransientPublishFailure(dest));
        assertFalse(_runner.rerequestAfterTransientPublishFailure(dest));
        // seed + 3 bounded retries
        assertEquals(4, countRequestJobs());
    }

    /** No pending lease request, no session, or null dest: no requeue. */
    @Test
    public void rerequestRequiresPendingRequest() throws Exception {
        Destination dest = createDestination();
        assertEquals(SessionStatusMessage.STATUS_CREATED,
                _runner.sessionEstablished(new SessionConfig(dest)));
        assertFalse(_runner.rerequestAfterTransientPublishFailure(dest));
        assertFalse(_runner.rerequestAfterTransientPublishFailure(null));
        assertFalse(_runner.rerequestAfterTransientPublishFailure(createDestination()));
    }

    /**
     * netdb publish() rejects the client's LeaseSet as expired: the pending
     * request is promptly re-queued (seed + one retry per call) until the
     * bound, without disconnecting the client or failing the request.
     */
    @Test
    public void expiredLeaseSetRequeuesPendingRequest() throws Exception {
        Destination dest = createDestination();
        assertEquals(SessionStatusMessage.STATUS_CREATED,
                _runner.sessionEstablished(new SessionConfig(dest)));
        SessionId id = new SessionId(3);
        _runner.setSessionId(dest.calculateHash(), id);
        Job onFailed = namedJob("onFailed");
        _runner.requestLeaseSet(dest.calculateHash(), createLeaseSet(dest), EXPIRATION_MS,
                null, onFailed);
        ClientMessageEventListener listener = new ClientMessageEventListener(_ctx, _runner, true);
        CreateLeaseSet2Message msg = expiredLeaseSetMessage(dest, id);

        listener.handleCreateLeaseSet(msg);
        assertEquals(2, countRequestJobs());
        assertNotNull(_runner.getLeaseRequest(dest.calculateHash()));

        // claims 2 and 3, then the bound stops requeueing
        listener.handleCreateLeaseSet(msg);
        listener.handleCreateLeaseSet(msg);
        assertEquals(4, countRequestJobs());
        listener.handleCreateLeaseSet(msg);
        listener.handleCreateLeaseSet(msg);
        assertEquals(4, countRequestJobs());

        assertEquals(0, countOf(onFailed));
        assertFalse(_runner.getIsDead());
    }

    /** Expiry rejection with no pending request must not queue or disconnect. */
    @Test
    public void expiredLeaseSetWithoutPendingRequestDoesNothing() throws Exception {
        Destination dest = createDestination();
        assertEquals(SessionStatusMessage.STATUS_CREATED,
                _runner.sessionEstablished(new SessionConfig(dest)));
        _runner.setSessionId(dest.calculateHash(), new SessionId(4));
        ClientMessageEventListener listener = new ClientMessageEventListener(_ctx, _runner, true);
        int before = addedJobs().size();
        listener.handleCreateLeaseSet(expiredLeaseSetMessage(dest, new SessionId(4)));
        assertEquals(before, addedJobs().size());
        assertFalse(_runner.getIsDead());
    }

    private static Destination createDestination() throws Exception {
        Object[] pub = KeyGenerator.getInstance().generatePKIKeypair();
        Object[] sig = KeyGenerator.getInstance().generateSigningKeypair();
        Destination dest = new Destination();
        dest.setPublicKey((PublicKey) pub[0]);
        dest.setSigningPublicKey((SigningPublicKey) sig[0]);
        dest.setCertificate(new Certificate());
        return dest;
    }

    private static LeaseSet createLeaseSet(Destination dest) {
        LeaseSet set = new LeaseSet();
        set.setDestination(dest);
        Lease lease = new Lease();
        lease.setGateway(new Hash(new byte[Hash.HASH_LENGTH]));
        lease.setTunnelId(new TunnelId(5));
        lease.setEndDate(NOW + 2L * 60 * 1000);
        set.addLease(lease);
        return set;
    }

    /**
     * An LS2 with no leases is "in the past" by definition (earliest is -1),
     * so publish() throws before storing anything.
     */
    private static CreateLeaseSet2Message expiredLeaseSetMessage(Destination dest, SessionId id) {
        LeaseSet2 ls = new LeaseSet2();
        ls.setDestination(dest);
        CreateLeaseSet2Message msg = new CreateLeaseSet2Message();
        msg.setSessionId(id);
        msg.setLeaseSet(ls);
        // required by the non-encrypted type check; publish() rejects first
        msg.setPrivateKey(new PrivateKey(EncType.ECIES_X25519, new byte[32]));
        return msg;
    }

    private Job namedJob(final String name) {
        return new JobImpl(_ctx) {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public void runJob() {
            }
        };
    }

    private List<Job> addedJobs() {
        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(_jobQueue, atLeastOnce()).addJob(captor.capture());
        return captor.getAllValues();
    }

    private RequestLeaseSetJob lastRequestJob() {
        RequestLeaseSetJob found = null;
        for (Job job : addedJobs()) {
            if (job instanceof RequestLeaseSetJob) {
                found = (RequestLeaseSetJob) job;
            }
        }
        assertNotNull("expected a queued RequestLeaseSetJob", found);
        return found;
    }

    private int countRequestJobs() {
        int n = 0;
        for (Job job : addedJobs()) {
            if (job instanceof RequestLeaseSetJob) {
                n++;
            }
        }
        return n;
    }

    private int countOf(Job target) {
        int n = 0;
        for (Job job : addedJobs()) {
            if (job == target) {
                n++;
            }
        }
        return n;
    }

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
