package net.i2p.router;

/**
 * Controls router load by throttling message processing and tunnel participation.
 * Monitors system performance metrics to decide when to reject or limit activities based on current load and bandwidth constraints.
 *
 */
public interface RouterThrottle {
    /**
     * Should we accept any more data from the network for any sort of message,
     * taking into account our current load, or should we simply slow down?
     *
     */
    public boolean acceptNetworkMessage();
    /**
     * Should we accept the request to participate in the given tunnel,
     * taking into account our current load and bandwidth usage commitments?
     *
     * @return 0 if it should be accepted, higher values for more severe rejection
     */
    public int acceptTunnelRequest();
    /** How backed up we are at the moment processing messages (in milliseconds) */
    public long getMessageDelay();
    /** How backed up our tunnels are at the moment (in milliseconds) */
    public long getTunnelLag();

    /**
     * Message on the state of participating tunnel acceptance
     * @return the tunnel status
     */
    public String getTunnelStatus();
    /**
     * Update the tunnel acceptance status message.
     */
    public void setTunnelStatus(String msg);

    /**
     * The tunnel status message, translated via the router resource bundle.
     *
     * @return the tunnel status, translated via the router resource bundle
     * @since 0.9.45
     */
    public String getLocalizedTunnelStatus();

    /** @since 0.8.12 */
    public void setShutdownStatus();

    /** @since 0.8.12 */
    public void cancelShutdownStatus();
}
