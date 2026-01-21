package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.router.message.OutboundCache;
import net.i2p.router.message.OutboundClientMessageOneShotJob;
import net.i2p.util.Log;

/**
 * Manage all of the inbound and outbound client messages maintained by the router.
 * The ClientManager subsystem fetches messages from this for locally deliverable
 * messages and adds in remotely deliverable messages.  Remotely deliverable messages
 * are picked up by interested jobs and processed and transformed into an OutNetMessage
 * to be eventually placed in the OutNetMessagePool.
 */
public class ClientMessagePool {
    private final Log _log;
    private final RouterContext _context;
    private final OutboundCache _cache;

    public ClientMessagePool(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(ClientMessagePool.class);
        _cache = new OutboundCache(_context);
        OutboundClientMessageOneShotJob.init(_context);
    }

    /**
     * Get the outbound cache for this message pool.
     *
     * @return the OutboundCache instance
     */
    public OutboundCache getCache() {
        return _cache;
    }

    /**
     * Shutdown the client message pool and clear all caches.
     * @since 0.8.8
     */
    public void shutdown() {
        _cache.clearAllCaches();
    }

    /**
     * Restart the client message pool.
     * @since 0.8.8
     */
    public void restart() {
        shutdown();
    }

    /**
     * Add a new client message to the pool.
     * The message is either locally or remotely destined.
     *
     * @param msg the ClientMessage to add
     */
    public void add(ClientMessage msg) {
        add(msg, false);
    }
    /**
     * Add a new client message with knowledge of whether it's remote.
     *
     * If we're coming from the client subsystem itself, we already know whether
     * the target is definitely remote and as such don't need to recheck
     * ourselves, but if we aren't certain, we want it to check for us.
     *
     * @param msg the ClientMessage to add
     * @param isDefinitelyRemote true if we know for sure that the target is not local
     */
    public void add(ClientMessage msg, boolean isDefinitelyRemote) {
        if (!isDefinitelyRemote &&
            (_context.clientManager().isLocal(msg.getDestination()) ||
             _context.clientManager().isLocal(msg.getDestinationHash()))) {
            if (_log.shouldDebug())
                _log.debug("Adding message for local delivery");
            _context.clientManager().messageReceived(msg);
        } else {
            if (_log.shouldDebug())
                _log.debug("Adding message for remote delivery");
            OutboundClientMessageOneShotJob j = new OutboundClientMessageOneShotJob(_context, _cache, msg);
            if (true) // blocks the I2CP reader for a nontrivial period of time
                j.runJob();
            else
                _context.jobQueue().addJob(j);
        }
    }
}
