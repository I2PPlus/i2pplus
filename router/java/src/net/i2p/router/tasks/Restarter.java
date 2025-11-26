package net.i2p.router.tasks;

import net.i2p.router.Router;
import net.i2p.router.RouterClock;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.util.Log;

/**
 * Router restarter implementation.
 *  @since 0.8.8, moved from Router in 0.8.12
 */
public class Restarter implements Runnable {
    private final RouterContext _context;

    public Restarter(RouterContext ctx) {_context = ctx;}

    public void run() {
        Long start = System.currentTimeMillis();
        _context.router().eventLog().addEvent(EventLog.SOFT_RESTART);
        Log log = _context.logManager().getLog(Router.class);
        log.warn("Performing a soft restart...");
        log.logAlways(Log.WARN, "Stopping the Client Manager...");
        // NOTE: DisconnectMessageHandler keys off "restart"
        try {_context.clientManager().shutdown("Router restart");}
        catch (Throwable t) {log.log(Log.CRIT, "Error stopping the Client Manager -> " + t.getMessage());}
        log.logAlways(Log.WARN, "Stopping the Comm system...");
        _context.bandwidthLimiter().reinitialize();
        try {_context.messageRegistry().restart();}
        catch (Throwable t) {log.log(Log.CRIT, "Error restarting the Message Registry -> " + t.getMessage());}
        try {_context.commSystem().restart();}
        catch (Throwable t) {log.log(Log.CRIT, "Error restarting the Comm System -> " + t.getMessage());}
        log.logAlways(Log.WARN, "Stopping the Tunnel Manager...");
        try {_context.tunnelManager().restart();}
        catch (Throwable t) {log.log(Log.CRIT, "Error restarting the Tunnel Manager -> " + t.getMessage());}
        log.logAlways(Log.WARN, "Restarted the tunnel manager");

        try {Thread.sleep(10*1000);} catch (InterruptedException ie) {}
        _context.router().setEstimatedDowntime(System.currentTimeMillis() - start);

        log.logAlways(Log.WARN, "Restarting the Client Manager...");
        try {_context.clientMessagePool().restart();}
        catch (Throwable t) {log.log(Log.CRIT, "Error restarting the ClientMessagePool -> " + t.getMessage());}
        try {_context.clientManager().startup();}
        catch (Throwable t) {log.log(Log.CRIT, "Error starting the Client Manager -> " + t.getMessage());}

        _context.router().setIsAlive();
        _context.router().rebuildRouterInfo();

        log.logAlways(Log.WARN, "Restart complete");
        ((RouterClock) _context.clock()).addShiftListener(_context.router());
    }

}