package net.i2p.app;

import java.io.IOException;
import java.net.Socket;

/**
 * Interface for outproxy configuration.
 *
 *  @since 0.9.11
 */
public interface Outproxy {

    public static final String NAME = "outproxy";

    /**
     * Connect to a host through the outproxy.
     *
     * @param host the hostname to connect to
     * @param port the port to connect to
     * @return a connected socket
     * @throws IOException if the connection fails
     */
    public Socket connect(String host, int port) throws IOException;
}
