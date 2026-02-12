package net.i2p.router.tunnel.pool;

import java.util.Properties;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import net.i2p.util.Log;

/**
 *  Data about a tunnel we created
 */
public class PooledTunnelCreatorConfig extends TunnelCreatorConfig {
    private final TunnelPool _pool;
    private final Log _log;
    // we don't store the config, that leads to OOM
    private TunnelId _pairedGW;
    /** 
     * When true, this tunnel is the last one in the pool and should not be removed
     * until a replacement is built. It remains available but is only selected if
     * no other tunnels are available.
     */
    private boolean _lastResort;

    /**
     *  Creates a new instance of PooledTunnelCreatorConfig
     *
     *  @param destination may be null
     *  @param pool non-null
     */
    public PooledTunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound, Hash destination, TunnelPool pool) {
        super(ctx, length, isInbound, destination);
        _pool = pool;
        _log = ctx.logManager().getLog(PooledTunnelCreatorConfig.class);
    }

    /** called from TestJob */
    public void testJobSuccessful(int ms) {testSuccessful(ms);}

    /**
     * The tunnel failed a test, so (maybe) stop using it
     * If the tunnel is recently active (traffic in last 60s), don't increment failure count -
     * it may recover from transient issues and we don't want to penalize active tunnels.
     */
    @Override
    public boolean tunnelFailed() {
        // Check if tunnel is recently active - if so, don't penalize for transient failures
        if (isRecentlyActive(60 * 1000)) {
            if (_log.shouldInfo()) {
                _log.info("Tunnel test failed but tunnel is recently active - not incrementing failure count, keeping for recovery: " + this);
            }
            return true; // Keep testing - tunnel may recover
        }
        boolean rv = super.tunnelFailed();
        // Under high load or attack, keep testing failing tunnels - they may recover
        // Only actually remove from pool when replacements are available
        return rv;
    }

    /**
     * We failed to contact the first hop for an outbound tunnel,
     * so immediately stop using it.
     * For outbound non-zero-hop tunnels only.
     *
     * @since 0.9.53
     */
    public void tunnelFailedFirstHop() {
        if (isInbound() || getLength() <= 1) {return;}
        tunnelFailedCompletely();
        _pool.tunnelFailed(this, getPeer(1));
    }

    /**
     *  @return non-null
     */
    @Override
    public Properties getOptions() {return _pool.getSettings().getUnknownOptions();}

    /**
     *  @return non-null
     */
    public TunnelPool getTunnelPool() {return _pool;}

    /**
     *  The ID of the gateway of the paired tunnel used to send/receive the build request
     *
     *  @param gw for paired inbound, the GW rcv tunnel ID; for paired outbound, the GW send tunnel ID.
     *  @since 0.9.53
     */
    public void setPairedGW(TunnelId gw) {_pairedGW = gw;}

    /**
     *  The ID of the gateway of the paired tunnel used to send/receive the build request
     *
     *  @return for paired inbound, the GW rcv tunnel ID; for paired outbound, the GW send tunnel ID.
     *          null if not previously set
     *  @since 0.9.53
     */
    public TunnelId getPairedGW() {return _pairedGW;}

    /**
     * Mark this tunnel as the last resort - the only tunnel remaining in the pool.
     * It should not be removed until a replacement is built.
     * @since 0.9.68+
     */
    public void setLastResort() {_lastResort = true;}

    /**
     * Clear the last resort status - tunnel has recovered or been replaced.
     * @since 0.9.68+
     */
    public void clearLastResort() {_lastResort = false;}

    /**
     * Check if this tunnel is the last resort (only tunnel in pool).
     * @return true if this is the last remaining tunnel
     * @since 0.9.68+
     */
    public boolean isLastResort() {return _lastResort;}

    /**
     * Track recent activity to determine if tunnel is actively being used.
     * Used to prevent removing tunnels that have active connections.
     */
    private volatile long _lastActivityTime;
    private volatile int _lastMessageCount;

    /**
     * Record that this tunnel was selected/used.
     * Called by TunnelPool when tunnel is selected.
     * @since 0.9.68+
     */
    public void recordActivity() {
        _lastActivityTime = _context.clock().now();
        _lastMessageCount = getProcessedMessagesCount();
    }

    /**
     * Check if this tunnel has been recently active (used within the last minute
     * or has processed messages since last check).
     * @param maxIdleMs maximum idle time in milliseconds
     * @return true if tunnel is actively being used
     * @since 0.9.68+
     */
    public boolean isRecentlyActive(long maxIdleMs) {
        long now = _context.clock().now();
        int currentMessages = getProcessedMessagesCount();
        // Active if: used recently OR messages increased since last check
        return (now - _lastActivityTime < maxIdleMs) || (currentMessages > _lastMessageCount);
    }

}
