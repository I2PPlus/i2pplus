package org.klomp.snark;

/**
 * Bandwidth and bandwidth limits
 *
 * <p>Maintain three bandwidth estimators: Sent, received, and requested.
 *
 * @since 0.9.62
 */
public interface BandwidthListener {

    /**
     * Returns the average upload rate in bytes per second.
     *
     * @return the average upload rate in Bps
     */
    public long getUploadRate();

    /**
     * Returns the average download rate in bytes per second.
     *
     * @return the average download rate in Bps
     */
    public long getDownloadRate();

    /**
     * Called when bytes are unconditionally sent.
     *
     * @param size the number of bytes sent
     */
    public void uploaded(int size);

    /**
     * Called when bytes are unconditionally received.
     *
     * @param size the number of bytes received
     */
    public void downloaded(int size);

    /**
     * Checks if we should send the given number of bytes.
     * Do NOT call uploaded() if this returns true.
     *
     * @param size the number of bytes to send
     * @return true if the bytes should be sent, false otherwise
     */
    public boolean shouldSend(int size);

    /**
     * Checks if we should request the given number of bytes from a peer.
     *
     * @param peer the peer to request from
     * @param size the number of bytes to request
     * @return true if the request should be made, false otherwise
     */
    public boolean shouldRequest(Peer peer, int size);

    /**
     * Returns the current upload bandwidth limit in bytes per second.
     *
     * @return the upload limit in Bps
     */
    public long getUpBWLimit();

    /**
     * Returns the current download bandwidth limit in bytes per second.
     *
     * @return the download limit in Bps
     */
    public long getDownBWLimit();

    /**
     * Checks if the current upload bandwidth is over the limit.
     *
     * @return true if over the upload limit, false otherwise
     */
    public boolean overUpBWLimit();

    /**
     * Checks if the current download bandwidth is over the limit.
     *
     * @return true if over the download limit, false otherwise
     */
    public boolean overDownBWLimit();
}
