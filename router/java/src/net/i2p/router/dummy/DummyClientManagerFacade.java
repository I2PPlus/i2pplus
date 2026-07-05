package net.i2p.router.dummy;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.Set;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;

/**
 * Manage all interactions with clients
 *
 * @author jrandom
 */
public class DummyClientManagerFacade extends ClientManagerFacade {
    private RouterContext _context;
    public DummyClientManagerFacade(RouterContext ctx) {
        _context = ctx;
    }
    @Override
    public boolean isLocal(Hash destHash) { return true; }
    public boolean isLocal(Destination dest) { return true; }
    public void reportAbuse(Destination dest, String reason, int severity) { /* Intentionally empty - dummy implementation */ }
    @Override
    public void messageReceived(ClientMessage msg) { /* Intentionally empty - dummy implementation */ }
    public void requestLeaseSet(Destination dest, LeaseSet set, long timeout,
                                Job onCreateJob, Job onFailedJob) {
        _context.jobQueue().addJob(onFailedJob);
    }
    @Override
    public void startup() { /* Intentionally empty - dummy implementation */ }
    public void stopAcceptingClients() { /* Intentionally empty - dummy implementation */ }
    public void shutdown() { /* Intentionally empty - dummy implementation */ }
    @Override
    public void shutdown(String msg) { /* Intentionally empty - dummy implementation */ }
    public void restart() { /* Intentionally empty - dummy implementation */ }

    public void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, long msgNonce, int status) { /* Intentionally empty - dummy implementation */ }

    public SessionConfig getClientSessionConfig(Destination _dest) { return null; }
    public SessionKeyManager getClientSessionKeyManager(Hash _dest) { return null; }

    public void requestLeaseSet(Hash dest, LeaseSet set) { /* Intentionally empty - dummy implementation */ }

    /**
     *  Return the floodfill network database facade.
     *
     *  @since 0.9.61
     */
    public FloodfillNetworkDatabaseFacade getClientFloodfillNetworkDatabaseFacade(Hash dbid) {
        return null;
    }

    /**
     *  Return the set of primary hashes.
     *
     *  @since 0.9.61
     */
    public Set<Hash> getPrimaryHashes() {
        return Collections.emptySet();
    }
}

