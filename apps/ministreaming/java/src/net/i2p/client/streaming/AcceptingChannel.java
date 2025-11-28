package net.i2p.client.streaming;

import net.i2p.I2PException;
import java.net.ConnectException;
import java.nio.channels.SelectableChannel;

/**
 *  As this does not (yet) extend ServerSocketChannel it cannot be returned by StandardServerSocket.getChannel(),
 *  until we implement an I2P SocketAddress class.
 *
 *  Warning, this interface and implementation is preliminary and subject to change without notice.
 *
 *  Unimplemented, unlikely to ever be implemented.
 *
 *  @since 0.8.11
 */
public abstract class AcceptingChannel extends SelectableChannel {

    /**
     * Accept an incoming connection.
     * 
     * @return the accepted I2P socket
     * @throws I2PException if an I2P error occurs
     * @throws ConnectException if a connection error occurs
     */
    protected abstract I2PSocket accept() throws I2PException, ConnectException;

    /** Socket manager for this channel */
    protected final I2PSocketManager _socketManager;

    /**
     * Create a new accepting channel.
     * 
     * @param manager the socket manager
     */
    protected AcceptingChannel(I2PSocketManager manager) {
        this._socketManager = manager;
    }
}
