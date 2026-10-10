/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.socks;

/**
 * Constants for SOCKS5 protocol.
 *
 * @since 0.9.33 Moved out of net.i2p.i2ptunnel.socks.SOCKS5Server
 */
public class SOCKS5Constants {

    private SOCKS5Constants() {}

    /**
     * The version number this implementation puts at the head of a SOCKS5
     * greeting.
     */
    public static final int SOCKS_VERSION_5 = 0x05;

    /*
     * Some namespaces to enclose SOCKS protocol codes
     */
    /**
     * SOCKS5 authentication methods.
     *
     * @since 0.9.33
     */
    public static class Method {
        /**
         * Names the constant list itself; it holds no per-instance state.
         */
        public Method() {}
        /**
         * The client offers no authentication.
         */
        public static final int NO_AUTH_REQUIRED = 0x00;
        /**
         * The client offers RFC 1929 username and password authentication.
         */
        public static final int USERNAME_PASSWORD = 0x02;
        /**
         * None of the methods the client listed is acceptable to the server.
         */
        public static final int NO_ACCEPTABLE_METHODS = 0xff;
    }

    /**
     * SOCKS5 address types.
     *
     * @since 0.9.33
     */
    public static class AddressType {
        /**
         * Names the constant list itself; it holds no per-instance state.
         */
        public AddressType() {}
        /**
         * A four-byte IPv4 address follows.
         */
        public static final int IPV4 = 0x01;
        /**
         * A one-byte length and that many bytes of domain name follow.
         */
        public static final int DOMAINNAME = 0x03;
        /**
         * A sixteen-byte IPv6 address follows.
         */
        public static final int IPV6 = 0x04;
    }

    /**
     * SOCKS5 command codes.
     *
     * @since 0.9.33
     */
    public static class Command {
        /**
         * Names the constant list itself; it holds no per-instance state.
         */
        public Command() {}
        /**
         * Open a relayed TCP connection to the requested destination.
         */
        public static final int CONNECT = 0x01;
        /**
         * Listen on a TCP port and wait for one inbound connection.
         */
        public static final int BIND = 0x02;
        /**
         * Associate a UDP port to relay datagrams.
         */
        public static final int UDP_ASSOCIATE = 0x03;

        /**
         * Tor extension: resolve the destination through the SOCKS proxy
         * instead of connecting to it.
         * @see <a href="https://github.com/torproject/torspec/blob/main/socks-extensions.txt">Tor SOCKS extensions</a>
         * @since 0.9.57
         */
        public static final int TOR_RESOLVE = 0xf0;

        /**
         * Tor extension: reverse-resolve an address to a hostname. This
         * server rejects the command.
         *
         * @since 0.9.57
         */
        public static final int TOR_RESOLVE_PTR = 0xf1;

        /**
         * Tor extension: open a connection to a directory authority. This
         * server rejects the command.
         *
         * @since 0.9.57
         */
        public static final int TOR_CONNECT_DIR = 0xf2;
    }

    /**
     * SOCKS5 reply codes.
     *
     * @since 0.9.33
     */
    public static class Reply {
        /**
         * Names the constant list itself; it holds no per-instance state.
         */
        public Reply() {}
        /**
         * The request succeeded.
         */
        public static final int SUCCEEDED = 0x00;
        /**
         * A failure not covered by one of the more specific codes.
         */
        public static final int GENERAL_SOCKS_SERVER_FAILURE = 0x01;
        /**
         * A policy rejected the connection.
         */
        public static final int CONNECTION_NOT_ALLOWED_BY_RULESET = 0x02;
        /**
         * The network could not be reached.
         */
        public static final int NETWORK_UNREACHABLE = 0x03;
        /**
         * The host could not be reached.
         */
        public static final int HOST_UNREACHABLE = 0x04;
        /**
         * The host refused the connection.
         */
        public static final int CONNECTION_REFUSED = 0x05;
        /**
         * The relayed connection expired before it could be established.
         */
        public static final int TTL_EXPIRED = 0x06;
        /**
         * The requested command is not supported by this server.
         */
        public static final int COMMAND_NOT_SUPPORTED = 0x07;
        /**
         * The requested address type is not supported by this server.
         */
        public static final int ADDRESS_TYPE_NOT_SUPPORTED = 0x08;
    }

    /**
     * Version byte of the username/password subnegotiation, as defined in RFC 1929.
     */
    public static final int AUTH_VERSION = 1;
    /**
     * The username and password were accepted.
     */
    public static final int AUTH_SUCCESS = 0;
    /**
     * The username or password was rejected.
     */
    public static final int AUTH_FAILURE = 1;
}
