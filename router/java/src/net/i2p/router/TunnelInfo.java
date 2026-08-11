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
    /** How many peers are there in the tunnel (including the creator)? */
    public int getLength();

    /**
     * The tunnelId that the given hop receives messages on.
     * The gateway is hop 0.
     *
     * @return the receive tunnel id
     */
    public TunnelId getReceiveTunnelId(int hop);
    /**
     * The tunnelId that the given hop sends messages on.
     * The gateway is hop 0.
     *
     * @return the send tunnel id
     */
    public TunnelId getSendTunnelId(int hop);

    /** The peer at the given hop. The gateway is hop 0. */
    public Hash getPeer(int hop);

    /**
     *  For convenience
     *
     *  @return getPeer(0)
     *  @since 0.8.9
     */
    public Hash getGateway();

    /**
     *  For convenience
     *
     *  @return getPeer(getLength() - 1)
     *  @since 0.8.9
     */
    public Hash getEndpoint();

    /**
     *  For convenience
     *
     *  @return isInbound() ? getGateway() : getEndpoint()
     *  @since 0.8.9
     */
    public Hash getFarEnd();

    /** Is this an inbound tunnel? */
    public boolean isInbound();

    /** If this is a client tunnel, what destination is it for? */
    public Hash getDestination();

    /**
     * The tunnel's expiration time.
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
     * The count of messages that have been processed through this tunnel.
     *
     * @return the number of processed messages
     */
    public int getProcessedMessagesCount();

    /** Number of bytes known to have travelled through the tunnel in its lifetime. */
    public long getVerifiedBytesTransferred();

    /**
     * Increment the verified byte count for data successfully sent through the tunnel.
     *
     * @param numBytes the number of bytes to add to the verified total
     */
    public void incrementVerifiedBytesTransferred(int numBytes);

    /**
     *  Did we reuse this tunnel?
     *
     *  @since 0.8.11
     */
    public boolean wasReused();

    /**
     *  Note that we reused this tunnel
     *
     *  @since 0.8.11
     */
    public void setReused();

    /**
     * Has the tunnel failed completely?
     *
     * @since 0.9.53 copied from TunnelCreatorConfig
     * @return the tunnel failed
     */
    public boolean getTunnelFailed();

    /**
     * The current test status of this tunnel for UI display.
     *
     * @return the current test status (UNTESTED, TESTING, GOOD, FAILING, or FAILED)
     * @since 0.9.68+
     */
    public TunnelTestStatus getTestStatus();

    /**
     * The test status when a test is started.
     * Called by TestJob when beginning a tunnel test.
     *
     * @since 0.9.68+
     */
    public void setTestStarted();

    /**
     * The test status when a test fails.
     * Called by TestJob when a tunnel test fails.
     *
     * @since 0.9.68+
     */
    public void setTestFailed();

    /**
     * The number of consecutive test failures.
     *
     * @return the count of consecutive failures
     * @since 0.9.68+
     */
    public int getConsecutiveFailures();

    /**
     * The latency of the last tunnel test.
     *
     * @return latency in milliseconds, or -1 if not available
     * @since 0.9.68+
     */
    public int getLastLatency();

    /**
     * The tunnel's expiration time.
     * Allows proactive tunnel cleanup by marking tunnels for expiry.
     *
     * @param when expiration time in milliseconds since epoch
     * @since 0.9.69+
     */
    public void setExpiration(long when);
}
