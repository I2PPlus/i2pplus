package net.i2p.router.tunnel.pool;

import java.util.Properties;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.TunnelCreatorConfig;

/**
 *  Data about a tunnel we created
 */
public class PooledTunnelCreatorConfig extends TunnelCreatorConfig {
    private final TunnelPool _pool;
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
    }

    /** called from TestJob */
    public void testJobSuccessful(int ms) {testSuccessful(ms);}

    /**
     * The tunnel failed a test, so (maybe) stop using it
     */
    @Override
    public boolean tunnelFailed() {
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

}
