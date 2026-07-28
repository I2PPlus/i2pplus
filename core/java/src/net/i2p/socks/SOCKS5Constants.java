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
     * SOCKS_VERSION_5.
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
         * NO_AUTH_REQUIRED.
         */
        public static final int NO_AUTH_REQUIRED = 0x00;
        /**
         * USERNAME_PASSWORD.
         */
        public static final int USERNAME_PASSWORD = 0x02;
        /**
         * NO_ACCEPTABLE_METHODS.
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
         * IPV4.
         */
        public static final int IPV4 = 0x01;
        /**
         * DOMAINNAME.
         */
        public static final int DOMAINNAME = 0x03;
        /**
         * IPV6.
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
         * CONNECT.
         */
        public static final int CONNECT = 0x01;
        /**
         * BIND.
         */
        public static final int BIND = 0x02;
        /**
         * UDP_ASSOCIATE.
         */
        public static final int UDP_ASSOCIATE = 0x03;

        /**
         * @see <a href="https://github.com/torproject/torspec/blob/main/socks-extensions.txt">Tor SOCKS extensions</a>
         * @since 0.9.57
         */
        public static final int TOR_RESOLVE = 0xf0;

        /** @since 0.9.57 */
        public static final int TOR_RESOLVE_PTR = 0xf1;

        /** @since 0.9.57 */
        public static final int TOR_CONNECT_DIR = 0xf2;
    }

    /**
     * SOCKS5 reply codes.
     *
     * @since 0.9.33
     */
    public static class Reply {
        /**
         * SUCCEEDED.
         */
        public static final int SUCCEEDED = 0x00;
        /**
         * GENERAL_SOCKS_SERVER_FAILURE.
         */
        public static final int GENERAL_SOCKS_SERVER_FAILURE = 0x01;
        /**
         * CONNECTION_NOT_ALLOWED_BY_RULESET.
         */
        public static final int CONNECTION_NOT_ALLOWED_BY_RULESET = 0x02;
        /**
         * NETWORK_UNREACHABLE.
         */
        public static final int NETWORK_UNREACHABLE = 0x03;
        /**
         * HOST_UNREACHABLE.
         */
        public static final int HOST_UNREACHABLE = 0x04;
        /**
         * CONNECTION_REFUSED.
         */
        public static final int CONNECTION_REFUSED = 0x05;
        /**
         * TTL_EXPIRED.
         */
        public static final int TTL_EXPIRED = 0x06;
        /**
         * COMMAND_NOT_SUPPORTED.
         */
        public static final int COMMAND_NOT_SUPPORTED = 0x07;
        /**
         * ADDRESS_TYPE_NOT_SUPPORTED.
         */
        public static final int ADDRESS_TYPE_NOT_SUPPORTED = 0x08;
    }

    /**
     * AUTH_VERSION.
     */
    public static final int AUTH_VERSION = 1;
    /**
     * AUTH_SUCCESS.
     */
    public static final int AUTH_SUCCESS = 0;
    /**
     * AUTH_FAILURE.
     */
    public static final int AUTH_FAILURE = 1;
}
