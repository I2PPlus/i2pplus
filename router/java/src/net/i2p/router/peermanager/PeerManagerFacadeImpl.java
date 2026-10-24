package net.i2p.router.peermanager;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Set;
import net.i2p.data.Hash;
import net.i2p.router.PeerManagerFacade;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Base implementation that has simple algorithms and periodically saves state.
 */
public class PeerManagerFacadeImpl implements PeerManagerFacade {
    /** Logger for this class. */
    private final Log _log;
    /** The manager doing the actual work, null until started. */
    private PeerManager _manager;
    /** The router the manager runs on. */
    private final RouterContext _context;
    /** Saves and restores the peer profiles. */
    private final ProfilePersistenceHelper _persistenceHelper;
    /** Job that tests peers for transport support. */
    private final PeerTestJob _testJob;

    /** Switch for the peer testing subsystem. */
    private static final boolean ENABLE_PEER_TEST = true;

    /**
     * Opens this facade's log and builds the profile persistence helper and
     * peer tester.
     * @param ctx the router context whose log manager, profile persistence
     *            store and peer tester this facade will use
     */
    public PeerManagerFacadeImpl(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(PeerManagerFacadeImpl.class);
        _persistenceHelper = new ProfilePersistenceHelper(ctx);
        _testJob = ENABLE_PEER_TEST ? new PeerTestJob(_context) : null;
    }

    /**
     * Start the peer manager and begin testing peers.
     */
    public synchronized void startup() {
        _log.info("Starting Peer Manager...");
        _manager = new PeerManager(_context);
        _persistenceHelper.setUs(_context.routerHash());
        if (_testJob != null) {_testJob.startTesting(_manager);}
    }

    /**
     * Shut down the peer manager, storing and clearing the profiles.
     */
    public synchronized void shutdown() {
        _log.info("Shutting down Peer Manager...");
        if (_testJob != null) {_testJob.stopTesting();}
        if (_manager != null) {
            _manager.storeProfiles();
            _manager.clearProfiles();
        }
    }

    /**
     * Store the profiles and reload them from disk.
     */
    public synchronized void restart() {
        _manager.storeProfiles();
        _persistenceHelper.setUs(_context.routerHash());
        _manager.loadProfiles();
    }

    /**
     * Record a peer's capability flags.
     *
     * @param caps non-null
     */
    public void setCapabilities(Hash peer, String caps) {
        if (_manager == null) return;
        _manager.setCapabilities(peer, caps);
    }

    /**
     * Remove all capabilities for the peer from the index.
     */
    public void removeCapabilities(Hash peer) {
        if (_manager == null) return;
        _manager.removeCapabilities(peer);
    }

    /**
     * The peer test job for scheduling priority peer tests.
     *
     * @return the PeerTestJob instance, or null if not available
     * @since 0.9.69+
     */
    public PeerTestJob getPeerTestJob() {
        return _testJob;
    }

    /**
     * Peers advertising the given capability.
     *
     * @param capability the address capability letter, matched without regard to case
     * @return non-null unmodifiable set
     */
    public Set<Hash> getPeersByCapability(char capability) {
        if (_manager == null) return Collections.emptySet();
        return _manager.getPeersByCapability(capability);
    }

    /**
     * How many peers advertise the given capability.
     *
     * @param capability the address capability letter, matched without regard to case
     * @return the number of profiles advertising that capability
     * @since 0.9.45
     */
    public int countPeersByCapability(char capability) {
        if (_manager == null) return 0;
        return _manager.countPeersByCapability(capability);
    }

    /**
     * No-op, the HTML status page was not worth translating.
     *
     * @deprecated moved to routerconsole
     */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {
        // Deprecated - moved to routerconsole
    }

}
