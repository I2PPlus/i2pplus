package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.Job;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.LogManager;

/**
 * Verifies the republish dispatch for local LeaseSets that are not published
 * to the network (i2cp.dontPublishLeaseSet, e.g. the HTTP proxy).
 * Such clients must have their local copy re-minted on the lease timescale,
 * never failed as if they had stopped.
 */
public class RepublishLeaseSetJobTest {
    private static final long NOW = 1_000_000_000L;

    private RouterContext _ctx;
    private ClientManagerFacade _cm;
    private KademliaNetworkDatabaseFacade _facade;
    private JobQueue _jobQueue;

    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(new File("/tmp"));
        when(_ctx.getProperty(anyString(), anyString())).thenReturn("/tmp/i2p-junit-logger.config");
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);

        Router router = mock(Router.class);
        // past the startup-grace window so runJob() reaches the dispatch
        when(router.getUptime()).thenReturn(60_000L);
        when(_ctx.router()).thenReturn(router);

        when(_ctx.clock()).thenReturn(mock(Clock.class));
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(_ctx.clock()).thenReturn(clock);

        _cm = mock(ClientManagerFacade.class);
        when(_ctx.clientManager()).thenReturn(_cm);

        TunnelPoolSettings settings = mock(TunnelPoolSettings.class);
        when(settings.getQuantity()).thenReturn(2);
        when(settings.getDestinationNickname()).thenReturn("tunnel");
        TunnelManagerFacade tm = mock(TunnelManagerFacade.class);
        when(tm.getInboundSettings(any(Hash.class))).thenReturn(settings);
        when(tm.getOutboundSettings(any(Hash.class))).thenReturn(settings);
        when(_ctx.tunnelManager()).thenReturn(tm);

        when(_ctx.statManager()).thenReturn(mock(StatManager.class));

        _jobQueue = mock(JobQueue.class);
        when(_ctx.jobQueue()).thenReturn(_jobQueue);

        when(_ctx.getProperty(anyString(), anyLong())).thenReturn(180_000L);
        when(_ctx.getProperty(anyString(), anyInt())).thenReturn(10_000);

        _facade = mock(KademliaNetworkDatabaseFacade.class);
        when(_facade.hasActiveRepublishJob(any())).thenReturn(false);
        when(_facade.registerPublishingJob(any(RepublishLeaseSetJob.class))).thenReturn(true);
    }

    /**
     * An active local client whose LeaseSet is not published to the network
     * must keep the republish cycle alive so the local copy is re-minted
     * before its ~6-minute lease expires.  It must never floodfill, never be
     * failed, and never stop publishing.
     */
    @Test
    public void testActiveLocalNonPublishingClientKeepsLocalCopyFresh() {
        Hash hash = newHash(1);
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(false);
        when(_cm.isLocal(hash)).thenReturn(true);
        LeaseSet ls = localLeaseSet(hash);
        when(_facade.lookupLeaseSetLocally(hash)).thenReturn(ls);

        RepublishLeaseSetJob job = new RepublishLeaseSetJob(_ctx, _facade, hash);
        assertTrue(job.registerSelf());
        job.runJob();

        // no floodfill, no stop, no fail, no rebuild request (not expiring yet)
        verify(_facade, never()).sendStore(any(Hash.class), any(LeaseSet.class), any(Job.class), any(Job.class), anyLong(), any());
        verify(_facade, never()).stopPublishing(hash);
        verify(_facade, never()).fail(hash);
        verify(_cm, never()).requestLeaseSet(eq(hash), any());
        verify(_ctx.statManager(), never()).addRateData(anyString(), anyLong());
        // test job plus its successor registered; successor queued to re-mint
        verify(_facade, times(2)).registerPublishingJob(any(RepublishLeaseSetJob.class));
        verify(_jobQueue).addJob(any(Job.class));
    }

    /**
     * A client whose I2CP session is closed by close-on-idle but whose tunnel
     * pools still exist must keep the local re-mint cycle alive — the
     * isTrackedLocal() gate prevents treating a temporarily-closed session as
     * a stop, so the local copy is not failed and publishing is not stopped.
     */
    @Test
    public void testClosedSessionWithPoolKeepsLocalCopyFresh() {
        Hash hash = newHash(8);
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(false);
        when(_cm.isLocal(hash)).thenReturn(false);
        LeaseSet ls = localLeaseSet(hash);
        when(_facade.lookupLeaseSetLocally(hash)).thenReturn(ls);

        RepublishLeaseSetJob job = new RepublishLeaseSetJob(_ctx, _facade, hash);
        assertTrue(job.registerSelf());
        job.runJob();

        verify(_facade, never()).stopPublishing(hash);
        verify(_facade, never()).fail(hash);
        verify(_jobQueue).addJob(any(Job.class));
    }

    /**
     * An active local publishing client must still floodfill — the local-only
     * fast path must not swallow the normal publish path.
     */
    @Test
    public void testActiveLocalPublishingClientStillFloodfills() {
        Hash hash = newHash(2);
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(true);
        when(_cm.isLocal(hash)).thenReturn(true);
        // 7-minute runway keeps this clear of the 5-minute re-mint window
        LeaseSet ls = localLeaseSet(hash, NOW + 7L * 60 * 1000);
        when(_facade.lookupLeaseSetLocally(hash)).thenReturn(ls);

        RepublishLeaseSetJob job = new RepublishLeaseSetJob(_ctx, _facade, hash);
        assertTrue(job.registerSelf());
        job.runJob();

        verify(_facade).sendStore(eq(hash), eq(ls), any(Job.class), any(Job.class), anyLong(), any());
        verify(_ctx.statManager()).addRateData(eq("netDb.republishLeaseSetCount"), anyLong());
        verify(_jobQueue).addJob(any(Job.class));
    }

/**
     * A publishing client whose stored LeaseSet is expiring must never flood
     * the near-expiry copy to the network — it would die before propagation.
     * Instead the job must request a re-mint from the pool's current tunnels
     * (whose LS expiry extends beyond the stored copy) and reschedule.
     */
    @Test
    public void testExpiringPublishingClientRemintsInsteadOfFlooding() {
        Hash hash = newHash(5);
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(true);
        when(_cm.isLocal(hash)).thenReturn(true);

        // stored LS expiring soon (within the 3-minute EXPIRY_WINDOW)
        LeaseSet ls = localLeaseSet(hash, NOW + 60L * 1000);
        when(_facade.lookupLeaseSetLocally(hash)).thenReturn(ls);

        // the pool's current LeaseSet extends well beyond the stored copy
        // and carries enough fresh leases to satisfy the re-mint gate
        LeaseSet freshPoolLs = freshPoolLeaseSet(NOW + 5L * 60 * 1000, 2);
        TunnelPool pool = mock(TunnelPool.class);
        when(pool.getInboundTunnelsAsLeaseSet()).thenReturn(freshPoolLs);
        TunnelManagerFacade tm = mock(TunnelManagerFacade.class);
        when(tm.getInboundPool(hash)).thenReturn(pool);
        when(_ctx.tunnelManager()).thenReturn(tm);

        RepublishLeaseSetJob job = new RepublishLeaseSetJob(_ctx, _facade, hash);
        assertTrue(job.registerSelf());
        job.runJob();

        // never flood the expiring stored copy
        verify(_facade, never()).sendStore(eq(hash), eq(ls), any(Job.class), any(Job.class), anyLong(), any());
        // re-mint requested with the pool's healthy current LS, not the stored copy
        verify(_cm).requestLeaseSet(eq(hash), eq(freshPoolLs));
        verify(_jobQueue).addJob(any(Job.class));
    }

    /**
     * An expiring LeaseSet whose pool holds too few fresh tunnels must NOT
     * re-mint — the re-mint gate only fires when the pool can actually extend
     * the lease past the fresh window.  Below target minus one, the job
     * defers, requests fresh tunnel builds, and reschedules; it never floods
     * the dying copy and never re-signs the same near-expired leases.
     */
    @Test
    public void testExpiringBelowTargetDefersAndRequestsBuilds() {
        Hash hash = newHash(7);
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(true);
        when(_cm.isLocal(hash)).thenReturn(true);

        // stored LS expiring soon with only 1 lease against a target of 3
        LeaseSet ls = localLeaseSet(hash, NOW + 60L * 1000);
        when(ls.getLeaseCount()).thenReturn(1);
        when(_facade.lookupLeaseSetLocally(hash)).thenReturn(ls);

        // pool's current LeaseSet extends beyond the stored copy but has only
        // 1 fresh lease — below the target of 3, so the gate holds
        LeaseSet freshPoolLs = freshPoolLeaseSet(NOW + 5L * 60 * 1000, 1);
        TunnelPool pool = mock(TunnelPool.class);
        when(pool.getInboundTunnelsAsLeaseSet()).thenReturn(freshPoolLs);
        TunnelManagerFacade tm = mock(TunnelManagerFacade.class);
        when(tm.getInboundPool(hash)).thenReturn(pool);
        TunnelPoolSettings targetSettings = settings(3);
        when(tm.getInboundSettings(any(Hash.class))).thenReturn(targetSettings);
        when(_ctx.tunnelManager()).thenReturn(tm);

        RepublishLeaseSetJob job = new RepublishLeaseSetJob(_ctx, _facade, hash);
        assertTrue(job.registerSelf());
        job.runJob();

        // never flood the expiring stored copy, never re-mint below target
        verify(_facade, never()).sendStore(eq(hash), eq(ls), any(Job.class), any(Job.class), anyLong(), any());
        verify(_cm, never()).requestLeaseSet(eq(hash), any());
        // fresh builds requested so the next cycle can re-mint
        verify(pool).requestFreshTunnelBuild();
        verify(_jobQueue).addJob(any(Job.class));
    }

    /**
     * A publishing client whose stored LeaseSet is expiring but whose pool has
     * no usable LeaseSet right now must NOT flood the dying copy either —
     * it requests the client to build fresh tunnels and reschedules.
     */
    @Test
    public void testExpiringPublishingClientWithoutPoolStillSkipsFlood() {
        Hash hash = newHash(6);
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(true);
        when(_cm.isLocal(hash)).thenReturn(true);

        LeaseSet ls = localLeaseSet(hash, NOW + 60L * 1000);
        when(_facade.lookupLeaseSetLocally(hash)).thenReturn(ls);

        TunnelManagerFacade tm = mock(TunnelManagerFacade.class);
        when(tm.getInboundPool(hash)).thenReturn(null);
        when(_ctx.tunnelManager()).thenReturn(tm);

        RepublishLeaseSetJob job = new RepublishLeaseSetJob(_ctx, _facade, hash);
        assertTrue(job.registerSelf());
        job.runJob();

        verify(_facade, never()).sendStore(eq(hash), eq(ls), any(Job.class), any(Job.class), anyLong(), any());
        verify(_cm, never()).requestLeaseSet(eq(hash), any());
        verify(_jobQueue).addJob(any(Job.class));
    }

    /**
     * A client that is no longer local, should not publish, AND has no tunnel
     * pools left has truly stopped — its LeaseSet is failed and publishing
     * stopped.  The isTrackedLocal() gate keeps the re-mint cycle alive only
     * while a pool still exists (close-on-idle), so a pool-less destination
     * must fall through to the stop path.
     */
    @Test
    public void testStoppedClientStopsPublishing() {
        Hash hash = newHash(3);
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(false);
        when(_cm.isLocal(hash)).thenReturn(false);
        LeaseSet ls = localLeaseSet(hash);
        when(_facade.lookupLeaseSetLocally(hash)).thenReturn(ls);
        // no pools configured for this destination — truly stopped
        TunnelManagerFacade tm = mock(TunnelManagerFacade.class);
        when(_ctx.tunnelManager()).thenReturn(tm);

        RepublishLeaseSetJob job = new RepublishLeaseSetJob(_ctx, _facade, hash);
        assertTrue(job.registerSelf());
        job.runJob();

        verify(_facade).stopPublishing(hash);
        verify(_facade).fail(hash);
        verify(_facade, never()).sendStore(any(Hash.class), any(LeaseSet.class), any(Job.class), any(Job.class), anyLong(), any());
        // only the test job registered — no successor scheduled
        verify(_facade).registerPublishingJob(any(RepublishLeaseSetJob.class));
        verify(_jobQueue, never()).addJob(any(Job.class));
    }

    /**
     * A publishing client that is no longer local gets the normal
     * not-local cleanup: the stored LeaseSet is failed (dropping any stale
     * raw-store copy) and publishing stopped.
     */
    @Test
    public void testNonLocalPublishingClientStopsPublishing() {
        Hash hash = newHash(4);
        when(_cm.shouldPublishLeaseSet(hash)).thenReturn(true);
        when(_cm.isLocal(hash)).thenReturn(false);
        when(_facade.lookupLeaseSetLocally(hash)).thenReturn(null);

        RepublishLeaseSetJob job = new RepublishLeaseSetJob(_ctx, _facade, hash);
        assertTrue(job.registerSelf());
        job.runJob();

        verify(_facade).stopPublishing(hash);
        verify(_facade).fail(hash);
        verify(_facade, never()).sendStore(any(Hash.class), any(LeaseSet.class), any(Job.class), any(Job.class), anyLong(), any());
    }

    /**
     * A unique hash per scenario so the static per-destination job state
     * cannot leak between test methods.
     *
     * @param salt the first byte of the hash
     * @return the hash
     */
    private static Hash newHash(int salt) {
        Hash hash = new Hash(new byte[Hash.HASH_LENGTH]);
        hash.getData()[0] = (byte) salt;
        return hash;
    }

    /**
     * A valid, unexpired local LeaseSet mock for a local client.
     *
     * @param hash the destination hash
     * @return the mocked LeaseSet
     */
    private LeaseSet localLeaseSet(Hash hash) {
        return localLeaseSet(hash, NOW + 5L * 60 * 1000);
    }

    /**
     * A valid local LeaseSet mock with an explicit latest-lease date.
     *
     * @param hash the destination hash
     * @param latestLeaseDate the expiry the mocked LeaseSet reports
     * @return the mocked LeaseSet
     */
    private LeaseSet localLeaseSet(Hash hash, long latestLeaseDate) {
        Destination dest = mock(Destination.class);
        when(dest.calculateHash()).thenReturn(hash);
        LeaseSet ls = mock(LeaseSet.class);
        when(ls.getDestination()).thenReturn(dest);
        when(ls.isCurrent(anyLong())).thenReturn(true);
        when(ls.getLatestLeaseDate()).thenReturn(latestLeaseDate);
        when(ls.getLeaseCount()).thenReturn(2);
        return ls;
    }

    /**
     * A pool LeaseSet mock whose leases all extend to the given date, so the
     * re-mint gate's fresh-lease count matches the given count.
     *
     * @param latestLeaseDate the expiry every lease reports
     * @param leaseCount the number of leases, all fresh
     * @return the mocked LeaseSet
     */
    private static LeaseSet freshPoolLeaseSet(long latestLeaseDate, int leaseCount) {
        LeaseSet ls = mock(LeaseSet.class);
        when(ls.getLatestLeaseDate()).thenReturn(latestLeaseDate);
        when(ls.getLeaseCount()).thenReturn(leaseCount);
        for (int i = 0; i < leaseCount; i++) {
            Lease lease = mock(Lease.class);
            when(lease.getEndTime()).thenReturn(latestLeaseDate);
            when(ls.getLease(i)).thenReturn(lease);
        }
        return ls;
    }

    /**
     * Tunnel pool settings with the given quantity.
     *
     * @param quantity the target lease count
     * @return the mocked settings
     */
    private static TunnelPoolSettings settings(int quantity) {
        TunnelPoolSettings s = mock(TunnelPoolSettings.class);
        when(s.getQuantity()).thenReturn(quantity);
        return s;
    }
}