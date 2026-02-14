package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;

/**
 * Defines the information associated with a tunnel
 */
public interface TunnelInfo {
    /** how many peers are there in the tunnel (including the creator)? */
    public int getLength();

    /**
     * retrieve the tunnelId that the given hop receives messages on.
     * the gateway is hop 0.
     *
     */
    public TunnelId getReceiveTunnelId(int hop);
    /**
     * retrieve the tunnelId that the given hop sends messages on.
     * the gateway is hop 0.
     *
     */
    public TunnelId getSendTunnelId(int hop);

    /** retrieve the peer at the given hop.  the gateway is hop 0 */
    public Hash getPeer(int hop);

    /**
     *  For convenience
     *  @return getPeer(0)
     *  @since 0.8.9
     */
    public Hash getGateway();

    /**
     *  For convenience
     *  @return getPeer(getLength() - 1)
     *  @since 0.8.9
     */
    public Hash getEndpoint();

    /**
     *  For convenience
     *  @return isInbound() ? getGateway() : getEndpoint()
     *  @since 0.8.9
     */
    public Hash getFarEnd();

    /** is this an inbound tunnel? */
    public boolean isInbound();

    /** if this is a client tunnel, what destination is it for? */
    public Hash getDestination();

    /**
     * Get the tunnel's expiration time.
     *
     * @return expiration time in milliseconds since epoch
     */
    public long getExpiration();

    /**
     * Record that the tunnel successfully processed a test with the given response time.
     *
     * @param responseTime the response time in milliseconds
     */
    public void testSuccessful(int responseTime);

    /**
     * Get the count of messages that have been processed through this tunnel.
     *
     * @return the number of processed messages
     */
    public long getProcessedMessagesCount();

    /** we know for sure that this many bytes travelled through the tunnel in its lifetime */
    public long getVerifiedBytesTransferred();

    /**
     * Increment the verified byte count for data successfully sent through the tunnel.
     *
     * @param numBytes the number of bytes to add to the verified total
     */
    public void incrementVerifiedBytesTransferred(int numBytes);

    /**
     *  Did we reuse this tunnel?
     *  @since 0.8.11
     */
    public boolean wasReused();

    /**
     *  Note that we reused this tunnel
     *  @since 0.8.11
     */
    public void setReused();

    /**
     * Has the tunnel failed completely?
     *
     * @since 0.9.53 copied from TunnelCreatorConfig
     */
    public boolean getTunnelFailed();

    /**
     * Get the current test status of this tunnel for UI display.
     *
     * @return the current test status (UNTESTED, TESTING, GOOD, FAILING, or FAILED)
     * @since 0.9.68+
     */
    public TunnelTestStatus getTestStatus();

    /**
     * Set the test status when a test is started.
     * Called by TestJob when beginning a tunnel test.
     *
     * @since 0.9.68+
     */
    public void setTestStarted();

    /**
     * Set the test status when a test fails.
     * Called by TestJob when a tunnel test fails.
     *
     * @since 0.9.68+
     */
    public void setTestFailed();

    /**
     * Get the number of consecutive test failures.
     *
     * @return the count of consecutive failures
     * @since 0.9.68+
     */
    public int getConsecutiveFailures();

    /**
     * Get the last recorded round-trip latency for this tunnel from the most recent test.
     *
     * @return latency in milliseconds, or -1 if not available
     * @since 0.9.70+
     */
    public int getLastLatency();
}
