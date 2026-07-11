package net.i2p.client;

/**
 * Listener for tunnel status changes.
 * Implemented by streaming library to react to tunnel test failures and removals.
 *
 * @since 0.9.69
 */
public interface TunnelStatusListener {

    /**
     * Called when a tunnel has failed completely (max consecutive test failures reached).
     * The listener should trigger connection failover to new tunnels.
     *
     * @param poolName the name of the tunnel pool (e.g., "I2P HTTP Proxy", "I2P IRC Network")
     * @param isInbound true for inbound tunnel, false for outbound
     */
    void tunnelFailed(String poolName, boolean isInbound);

    /**
     * Called when a tunnel is removed from a client tunnel pool.
     * The listener should gracefully handle connection migration to remaining tunnels.
     *
     * @param event the tunnel removal event containing details about the removed tunnel
     * @since 0.9.69
     */
    void tunnelRemoved(TunnelRemovalEvent event);

    /**
     * Called when a tunnel pool is being shut down (all tunnels being removed).
     * The listener should prepare for connection termination or failover.
     *
     * @param poolName the name of the tunnel pool
     * @param isInbound true for inbound tunnel pool, false for outbound
     * @since 0.9.69
     */
    void poolShuttingDown(String poolName, boolean isInbound);
}
