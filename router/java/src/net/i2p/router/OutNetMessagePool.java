package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.util.Log;

/**
 * Maintain a pool of OutNetMessages destined for other routers, organized by
 * priority, expiring messages as necessary.  This pool is populated by anything
 * that wants to send a message, and the communication subsystem periodically 
 * retrieves messages for delivery.
 *
 * Actually, this doesn't 'pool' anything, it calls the comm system directly.
 * Nor does it organize by priority. But perhaps it could someday.
 */
public class OutNetMessagePool {
    private final Log _log;
    private final RouterContext _context;
    
    public OutNetMessagePool(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(OutNetMessagePool.class);
    }
    
    /**
     * Add a new message to the pool
     *
     */
    public void add(OutNetMessage msg) {
        if (msg == null) return;
        MessageSelector selector = msg.getReplySelector();
        boolean valid = validate(msg);
        if (!valid) {
            if (selector != null)
                _context.messageRegistry().unregisterPending(msg);
            return;
        }        
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Adding " + msg);
        
        if (selector != null) {
            _context.messageRegistry().registerPending(msg);
        }
        _context.commSystem().processMessage(msg);
        return;
    }
    
    /**
     * @param msg non-null
     */
    private boolean validate(OutNetMessage msg) {
        if (msg.getMessage() == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Null message in the OutNetMessage - expired too soon");
            return false;
        }
        if (msg.getTarget() == null) {
            _log.error("No target in the OutNetMessage: " + msg, new Exception());
            return false;
        }
        if (msg.getPriority() < 0) {
            _log.error("Priority less than 0?  sounds like nonsense to me... " + msg, new Exception());
            return false;
        }
        if (msg.getExpiration() <= _context.clock().now()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping expired outbound msg: " + msg, new Exception());
            return false;
        }
        return true;
    }
}
