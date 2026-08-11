package net.i2p.client.streaming;

import java.net.SocketAddress;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;

/**
 *  A SocketAddress (Destination + port) so we can have SocketChannels.
 *  Ports are not widely used in I2P, in most cases the port will be zero.
 *  See InetSocketAddress for javadocs.
 *
 *  @since 0.9.1
 */
public class I2PSocketAddress extends SocketAddress {
    /** Serial version unique identifier */
    private static final long serialVersionUID = 1L;
    /** Port number, 0 if not specified. */
    private final int _port;
    /** The I2P destination. */
    private transient Destination _dest;
    /** Hostname from the constructor. */
    private final String _host;

    /**
     *  Convenience constructor that parses host:port.
     *
     *  Does a naming service lookup to resolve the dest.
     *  May take several seconds for b32.
     *  @param host hostname or b64 dest or b32, may have :port appended
     *  @throws IllegalArgumentException for port &lt; 0 or port &gt; 65535 or invalid port
     *  @since 0.9.9
     */
    public I2PSocketAddress(String host) {
        int port = 0;
        int colon = host.indexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
                if (port < 0 || port > 65535)
                    throw new IllegalArgumentException("Bad port " + port);
            } catch (IndexOutOfBoundsException ioobe) {
                throw new IllegalArgumentException("Bad port " + host);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Bad port " + host);
            }
        }
        _port = port;
        _dest = I2PAppContext.getGlobalContext().namingService().lookup(host);
        _host = host;
    }

    /**
     *  Does not do a reverse lookup. Host will be null.
     *  @throws IllegalArgumentException for port &lt; 0 or port &gt; 65535
     */
    public I2PSocketAddress(Destination dest, int port) {
        if (dest == null)
            throw new NullPointerException();
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Bad port " + port);
        _port = port;
        _dest = dest;
        _host = null;
    }

    /**
     *  Does a naming service lookup to resolve the dest.
     *  May take several seconds for b32.
     *  @throws IllegalArgumentException for port &lt; 0 or port &gt; 65535
     */
    public I2PSocketAddress(String host, int port) {
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Bad port " + port);
        _port = port;
        _dest = I2PAppContext.getGlobalContext().namingService().lookup(host);
        _host = host;
    }

    /**
     *  Creates an unresolved address for the given host and port.
     *  @throws IllegalArgumentException for port &lt; 0 or port &gt; 65535
     */
    public static I2PSocketAddress createUnresolved(String host, int port) {
        return new I2PSocketAddress(port, host);
    }

    /** I2P socket address. */
    private I2PSocketAddress(int port, String host) {
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Bad port " + port);
        _port = port;
        _dest = null;
        _host = host;
    }

    /**
     * The port number of this address.
     * @return the port number, 0 if not specified
     */
    public int getPort() {
        return _port;
    }

    /**
     *  Does a naming service lookup to resolve the dest if this was created unresolved
     *  or if the resolution failed in the constructor.
     *  If unresolved, this may take several seconds for b32.
     * @return the address
     */
    public synchronized Destination getAddress() {
        if (_dest == null)
            _dest = I2PAppContext.getGlobalContext().namingService().lookup(_host);
        return _dest;
    }

    /**
     *  Host name of this address, as given in the constructor.
     *  @return the host only if given in the constructor. Does not do a reverse lookup.
     */
    public String getHostName() {
        return _host;
    }

    /**
     * Whether the destination has not been resolved.
     * @return true if this address was created unresolved or resolution failed
     */
    public boolean isUnresolved() {
        return _dest == null;
    }

    /**
     * String representation of this address.
     */
    @Override
    @SuppressWarnings("PMD.AvoidUnnecessaryStringBuilderCreation")
    public String toString() {
        StringBuilder buf = new StringBuilder();
        if (_dest != null)
            buf.append(_dest.calculateHash().toString());
        else
            buf.append(_host);
        buf.append(':');
        buf.append(_port);
        return buf.toString();
    }

    /**
     * Whether this address equals another, comparing port, destination, and host.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof I2PSocketAddress))
            return false;
        I2PSocketAddress o = (I2PSocketAddress) obj;
        if (_port != o._port)
            return false;
        if (_dest != null)
            return _dest.equals(o._dest);
        if (o._dest != null)
            return false;
        if (_host != null)
            return _host.equals(o._host);
        return o._host == null;
    }

    /**
     * Hash code of this address.
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_dest) ^ DataHelper.hashCode(_host) ^ _port;
    }
}
