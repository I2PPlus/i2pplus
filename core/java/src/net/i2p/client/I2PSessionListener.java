package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Define a means for the router to asynchronously notify the client that a
 * new message is available or the router is under attack.
 *
 * A client must implement and register this via addSessionListener()
 * to receive messages.
 *
 * If you wish to get notification of the protocol, from port, and to port,
 * or wish to get notification of certain protocols and ports only,
 * you must use I2PSessionMuxedListener and addMuxedSessionListener() instead.
 *
 * @author jrandom
 */
public interface I2PSessionListener {
    /**
     * Instruct the client that the given session has received a message with
     * size # of bytes.
     *
     * After this is called, the client should call receiveMessage(msgId).
     * A message can be dropped without being read by calling
     * discardMessage(msgId), which avoids the decompression cost.
     * If the client does not claim the message, the session drops it on a
     * later pass of its unclaimed-message sweep (every 60 seconds) and logs
     * a warning naming the message ID.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message payload in bytes. The I2CP payload size
     *             is an int internally and is widened to long here, so the
     *             value is never negative
     */
    void messageAvailable(I2PSession session, int msgId, long size);

    /** Instruct the client that the session specified seems to be under attack
     * and that the client may wish to move its destination to another router.
     *
     * Unused. Not fully implemented.
     *
     * @param session session to report abuse to
     * @param severity how bad the abuse is
     */
    void reportAbuse(I2PSession session, int severity);

    /**
     * Notify the client that the session has been terminated
     *
     * @param session the session
     */
    void disconnected(I2PSession session);

    /**
     * Notify the client that some error occurred
     *
     * @param session the session
     * @param message a human-readable description of the error
     * @param error the cause, non-null
     */
    void errorOccurred(I2PSession session, String message, Throwable error);
}
