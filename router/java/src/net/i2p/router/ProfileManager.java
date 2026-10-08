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

/**
 * Manages peer profiles and performance metrics for router peers.
 * Tracks communication statistics, tunnel participation, and database interactions
 * to maintain performance profiles for network peers.
 */
public interface ProfileManager {
    /**
     * Note that it took msToSend to send a message of size bytesSent to the peer over the transport.
     * This should only be called if the transport considered the send successful.
     *
     * @param peer hash of the peer the message was sent to
     * @param transport style of the transport that carried the message
     * @param msToSend milliseconds the send took
     * @param bytesSent size of the message in bytes
     */
    void messageSent(Hash peer, String transport, long msToSend, long bytesSent);

    /**
     * Record the packet-loss (retransmit) ratio measured by the transport for a peer.
     *
     * @param peer hash of the peer the packet loss was measured on
     * @param ratio retransmitted / transmitted packets, 0.0 = healthy
     * @since 0.9.71+
     */
    void peerLossEvent(Hash peer, float ratio);

    /**
     * Note that the router failed to send a message to the peer over the transport specified
     *
     * @param peer hash of the peer the send to failed
     * @param transport style of the transport the send failed on
     */
    void messageFailed(Hash peer, String transport);

    /**
     * Note that the router failed to send a message to the peer over any transport
     *
     * @param peer hash of the peer the send to failed
     */
    void messageFailed(Hash peer);

    /**
     * Note that there was some sort of communication error talking with the peer
     *
     * @param peer hash of the peer the error occurred talking to
     */
    void commErrorOccurred(Hash peer);

    /**
     * Note that the router agreed to participate in a tunnel
     *
     * @param peer hash of the peer that agreed to join
     * @param responseTimeMs milliseconds the peer took to accept the request
     */
    void tunnelJoined(Hash peer, long responseTimeMs);

    /**
     * Note that a router explicitly rejected joining a tunnel
     *
     * @param peer who rejected us
     * @param responseTimeMs how long it took to get the rejection
     * @param severity how much the peer doesnt want to participate in the
     *                 tunnel (large == more severe)
     */
    void tunnelRejected(Hash peer, long responseTimeMs, int severity);

    /**
     * Note that a router timed out joining a tunnel
     *
     * @param peer who rejected us
     */
    void tunnelTimedOut(Hash peer);

    /**
     * Note that a tunnel that the router is participating in
     * was successfully tested with the given round trip latency
     *
     * @param peer hash of the peer on the far end of the tunnel
     * @param responseTimeMs round trip latency of the test in milliseconds
     */
    void tunnelTestSucceeded(Hash peer, long responseTimeMs);

    /**
     * Note that we were able to push some data through a tunnel that the peer
     * is participating in (detected after rtt).
     *
     * @param peer hash of the peer participating in the tunnel
     * @param rtt round trip latency in milliseconds, not used in the rate accounting
     * @param size bytes pushed, accumulated into a long so a period total
     *        exceeding 2 GB is not truncated
     */
    void tunnelDataPushed(Hash peer, long rtt, long size);

    /**
     * Note that the peer is participating in a tunnel that pushed the given amount of data
     * over the last minute.
     *
     * @param peer hash of the peer participating in the tunnel
     * @param size bytes pushed during the period, normalized to a minute
     */
    void tunnelDataPushed1m(Hash peer, long size);

    /**
     * Note that we were able to push the given amount of data through a tunnel
     * that the peer is participating in
     *
     * @param peer hash of the peer participating in the tunnel
     * @param lifetime age of the tunnel in milliseconds
     * @param size bytes pushed over the lifetime of the tunnel
     */
    void tunnelLifetimePushed(Hash peer, long lifetime, long size);

    /**
     * Note that the peer participated in a tunnel that failed.  Its failure may not have
     * been the peer's fault however.
     *
     * @param peer hash of the peer that participated in the tunnel
     * @param pct chance of blaming the peer for the failure, 0-100 percent
     */
    void tunnelFailed(Hash peer, int pct);

    /**
     * Note that the peer was able to return the valid data for a db lookup
     *
     * @param peer hash of the peer that answered
     * @param responseTimeMs milliseconds the peer took to answer
     */
    void dbLookupSuccessful(Hash peer, long responseTimeMs);

    /**
     * Note that the peer was unable to reply to a db lookup - either with data or with
     * a lookupReply redirecting the user elsewhere
     *
     * @param peer hash of the peer that did not answer
     */
    void dbLookupFailed(Hash peer);

    /**
     * Note that the peer replied to a db lookup with a redirect to other routers, where
     * the list of redirected users included newPeers routers that the local router didn't
     * know about, oldPeers routers that the local router already knew about, the given invalid
     * routers that were invalid in some way, and the duplicate number of routers that we explicitly
     * asked them not to send us, but they did anyway
     *
     * @param peer hash of the peer that sent the redirect
     * @param newPeers redirected routers we did not previously know about
     * @param oldPeers redirected routers we already had profiles for
     * @param invalid redirected routers that were unusable
     * @param duplicate redirected routers we had explicitly excluded
     * @param responseTimeMs milliseconds the peer took to answer
     */
    void dbLookupReply(Hash peer, int newPeers, int oldPeers, int invalid, int duplicate, long responseTimeMs);

    /**
     * Note that the local router received a db lookup from the given peer
     *
     * @param peer hash of the peer that sent the lookup to us
     */
    void dbLookupReceived(Hash peer);

    /**
     * Note that the local router received an unprompted db store from the given peer
     *
     * @param peer hash of the peer that sent the store
     * @param wasNewKey true if the stored key was not already in our database
     */
    void dbStoreReceived(Hash peer, boolean wasNewKey);

    /**
     * Note that we've confirmed a successful send of db data to the peer (though we haven't
     * necessarily requested it again from them, so they /might/ be lying)
     *
     * @param peer hash of the peer we sent the data to
     * @param responseTimeMs milliseconds the peer took to acknowledge it
     */
    void dbStoreSent(Hash peer, long responseTimeMs);

    /**
     * Note that we confirmed a successful send of db data to
     * the peer.
     *
     * @param peer hash of the peer that verified the stored data
     */
    void dbStoreSuccessful(Hash peer);

    /**
     * Note that we were unable to confirm a successful send of db data to
     * the peer, at least not within our timeout period
     *
     * @param peer hash of the peer that did not verify the stored data
     */
    void dbStoreFailed(Hash peer);

    /**
     * Note that the local router received a reference to the given peer, either
     * through an explicit dbStore or in a dbLookupReply.
     *
     * @param peer the hash of the peer we learned about
     */
    void heardAbout(Hash peer);

    /**
     * Note that the local router received a reference to the given peer at a specific time.
     *
     * @param peer the hash of the peer we learned about
     * @param when the timestamp when we learned about the peer
     */
    void heardAbout(Hash peer, long when);

    /**
     * Note that the router received a message from the given peer on the specified
     * transport.  Messages received without any "from" information aren't recorded
     * through this metric.  If msToReceive is negative, there was no timing information
     * available
     *
     * @param peer hash of the peer that sent the message
     * @param style transport style the message arrived over
     * @param msToReceive milliseconds the receive took, negative if untimed
     * @param bytesRead size of the message in bytes
     */
    void messageReceived(Hash peer, String style, long msToReceive, int bytesRead);
}
