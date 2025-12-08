package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.SimpleTimer;
import net.i2p.util.Log;

/**
 * Router information publisher for network database synchronization.
 *
 * <p>This class periodically publishes the router's RouterInfo to the
 * distributed network database (netdb) to ensure the router remains
 * discoverable and reachable by other peers. Regular publication
 * is essential for maintaining network connectivity and participation.</p>
 *
 * <p><strong>Publication Process:</strong></p>
 * <ul>
 *   <li>Retrieves current RouterInfo from router</li>
 *   <li>Validates RouterInfo integrity and timestamps</li>
 *   <li>Publishes to floodfill peers in network database</li>
 *   <li>Handles clock skew and timestamp issues</li>
 *   <li>Triggers identity rebuild if needed</li>
 * </ul>
 *
 * <p><strong>Error Handling:</strong></p>
 * <ul>
 *   <li>Detects and handles clock skew issues</li>
 *   <li>Rebuilds router identity on validation failures</li>
 *   <li>Logs detailed error information for debugging</li>
 *   <li>Recovers from temporary network issues</li>
 * </ul>
 *
 * <p><strong>Clock Skew Detection:</strong></p>
 * <ul>
 *   <li>Detects large time discrepancies (>60 seconds)</li>
 *   <li>Rebuilds RouterInfo with current time</li>
 *   <li>Prevents network database rejection</li>
 *   <li>Maintains router identity continuity</li>
 * </ul>
 *
 * <p><strong>Network Integration:</strong></p>
 * <ul>
 *   <li>Ensures router remains discoverable by peers</li>
 *   <li>Maintains up-to-date routing information</li>
 *   <li>Supports network topology and routing</li>
 *   <li>Enables tunnel building and peer connections</li>
 * </ul>
 *
 * <p>Regular publication is critical for router participation in the
 * I2P network. Without periodic republishing, routers would
 * become unreachable and unable to participate in tunnel creation
 * or network routing.</p>
 *
 * @since 0.8.12 moved from Router.java
 */
public class Republish implements SimpleTimer.TimedEvent {
    private final RouterContext _context;

    /**
     * Create a new router info republisher.
     *
     * @param ctx the router context for accessing router services
     * @since 0.8.12 moved from Router.java
     */
    public Republish(RouterContext ctx) {_context = ctx;}

    /**
     * Publish the router's info to the network database.
     *
     * Retrieves the current RouterInfo and publishes it to the netdb.
     * If the RouterInfo is invalid (due to clock skew or other issues),
     * attempts to rebuild the router identity.
     *
     * This method handles clock skew detection and will trigger a router
     * identity rebuild if significant time discrepancies are detected.
     */
    public void timeReached() {
        RouterInfo ri = null;
        try {
            ri = _context.router().getRouterInfo();
            if (ri != null) {_context.netDb().publish(ri);}
        } catch (IllegalArgumentException iae) {
            Log log = _context.logManager().getLog(Router.class);
            // clock skew / shift race?
            if (ri != null) {
                long now = _context.clock().now();
                long published = ri.getDate();
                long diff = Math.abs(now - published);
                if (diff > 60*1000) {
                    log.logAlways(Log.WARN, "Large clock shift detected (" + DataHelper.formatDuration(diff) + "), rebuilding local RouterInfo...");
                    _context.router().rebuildRouterInfo(); // let's just try this again and hope for better results
                    return;
                }
            }
            log.log(Log.CRIT, "Local RouterInfo is invalid - rebuilding a new identity...", iae);
            _context.router().rebuildNewIdentity();
        }
    }

}

