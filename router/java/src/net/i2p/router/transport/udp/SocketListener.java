package net.i2p.router.transport.udp;

/**
 *  Listener for socket events.
 *  @since 0.9.16
 */
interface SocketListener {
    /**
     * Notification that the socket failed.
     */
    public void fail();
}
