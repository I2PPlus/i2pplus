package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;

/**
 * Interface for handling NTCP connection establishment state machine.
 * Manages the handshake process for both NTCP 1 and NTCP 2 protocols,
 * including parsing handshake data, preparing outbound connections,
 * and tracking establishment completion status.
 *
 * @since 0.9.16
 */
interface EstablishState {

    /**
     * Parse the contents of the buffer as part of the handshake.
     *
     * All data must be copied out of the buffer as Reader.processRead()
     * will return it to the pool.
     *
     * If there are additional data in the buffer after the handshake is complete,
     * the EstablishState is responsible for passing it to NTCPConnection.
     *
     * @param src the buffer to parse
     * @throws IllegalStateException on invalid state
     */
    public void receive(ByteBuffer src);

    /**
     * Does nothing. Outbound (Alice) must override.
     * We are establishing an outbound connection, so prepare ourselves by
     * queueing up the write of the first part of the handshake
     *
     * @throws IllegalStateException on invalid state
     */
    public void prepareOutbound();

    /**
     *  Whether the handshake failed.
     *
     *  @return whether the handshake failed
     */
    public boolean isCorrupt();

    /**
     *  Failure reason, or null if not failed.
     *
     *  @return the failure reason, or null if not failed
     */
    public String getFailReason();

    /**
     *  If synchronized on this, fails with
     *  deadlocks from all over via CSFI.isEstablished().
     *  Also CSFI.getFramedAveragePeerClockSkew().
     *
     *  @return is the handshake complete and valid?
     */
    public boolean isComplete();

    /**
     *  NTCP version.
     *  @return 1, 2, or 0 if unknown
     *  @since 0.9.35
     */
    public int getVersion();

    /**
     *  Release resources on timeout.
     *  @param reason the reason
     *  @param e may be null
     *  @since 0.9.16
     */
    public void close(String reason, Exception e);

}
