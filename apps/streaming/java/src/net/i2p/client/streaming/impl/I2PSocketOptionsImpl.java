package net.i2p.client.streaming.impl;

import java.util.Properties;

import net.i2p.client.streaming.I2PSocketOptions;

/**
 * Define the configuration for streaming and verifying data on the socket.
 * Use I2PSocketManager.buildOptions() to get one of these.
 */
class I2PSocketOptionsImpl implements I2PSocketOptions {
    private long _connectTimeout;
    private long _readTimeout;
    private long _writeTimeout;
    private int _maxBufferSize;
    private int _localPort;
    private int _remotePort;

    public static final int DEFAULT_BUFFER_SIZE = 1024*64;
    public static final int DEFAULT_READ_TIMEOUT = -1;
    public static final int DEFAULT_WRITE_TIMEOUT = -1;
    public static final int DEFAULT_CONNECT_TIMEOUT = 60*1000;

    /**
     *  Sets max buffer size, connect timeout, read timeout, and write timeout
     *  from System properties. Does not set local port or remote port.
     */
    public I2PSocketOptionsImpl() {
        this(System.getProperties());
    }

    /**
     *  Initializes from System properties then copies over all options.
     *  @param opts may be null
     */
    public I2PSocketOptionsImpl(I2PSocketOptions opts) {
        this(System.getProperties());
        if (opts != null) {
            _connectTimeout = opts.getConnectTimeout();
            _readTimeout = opts.getReadTimeout();
            _writeTimeout = opts.getWriteTimeout();
            _maxBufferSize = opts.getMaxBufferSize();
            _localPort = opts.getLocalPort();
            _remotePort = opts.getPort();
        }
    }

    /**
     *  Sets max buffer size, connect timeout, read timeout, and write timeout
     *  from properties. Does not set local port or remote port.
     *
     *  As of 0.9.19, defaults in opts are honored.
     *
     *  @param opts may be null
     */
    public I2PSocketOptionsImpl(Properties opts) {
        init(opts);
    }

    /**
     *  Sets max buffer size, connect timeout, read timeout, and write timeout
     *  from properties. Does not set local port or remote port.
     *
     *  As of 0.9.19, defaults in opts are honored.
     *
     *  @param opts may be null
     */
    public void setProperties(Properties opts) {
        if (opts == null) return;
        if (opts.getProperty(PROP_BUFFER_SIZE) != null)
            _maxBufferSize = getInt(opts, PROP_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        if (opts.getProperty(PROP_CONNECT_TIMEOUT) != null)
            _connectTimeout = getInt(opts, PROP_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        if (opts.getProperty(PROP_READ_TIMEOUT) != null)
            _readTimeout = getInt(opts, PROP_READ_TIMEOUT, DEFAULT_READ_TIMEOUT);
        if (opts.getProperty(PROP_WRITE_TIMEOUT) != null)
            _writeTimeout = getInt(opts, PROP_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
    }

    /**
     *  Sets max buffer size, connect timeout, read timeout, and write timeout
     *  from properties. Does not set local port or remote port.
     */
    protected void init(Properties opts) {
        _maxBufferSize = getInt(opts, PROP_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        _connectTimeout = getInt(opts, PROP_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        _readTimeout = getInt(opts, PROP_READ_TIMEOUT, -1);
        _writeTimeout = getInt(opts, PROP_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
    }

    protected static int getInt(Properties opts, String name, int defaultVal) {
        if (opts == null) return defaultVal;
        String val = opts.getProperty(name);
        if (val == null) {
            return defaultVal;
        } else {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                return defaultVal;
            }
        }
    }

    /**
     *  Not part of the API, not for external use.
     */
    public static double getDouble(Properties opts, String name, double defaultVal) {
        if (opts == null) return defaultVal;
        String val = opts.getProperty(name);
        if (val == null) {
            return defaultVal;
        } else {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException nfe) {
                return defaultVal;
            }
        }
    }

    /**
     * How long we will wait for the ACK from a SYN, in milliseconds.
     *
     * Default 60 seconds. Max of 2 minutes enforced in Connection.java,
     * and it also interprets &lt;= 0 as default.
     *
     * @return milliseconds to wait, or -1 if we will wait indefinitely
     */
    public long getConnectTimeout() {
        return _connectTimeout;
    }

    /**
     * Define how long we will wait for the ACK from a SYN, in milliseconds.
     *
     * Default 60 seconds. Max of 2 minutes enforced in Connection.java,
     * and it also interprets &lt;= 0 as default.
     *
     */
    public void setConnectTimeout(long ms) {
        _connectTimeout = ms;
    }

    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws
     * SocketTimeoutException as of 0.9.36.
     * Prior to that, the read() returned -1 or 0.
     *
     * WARNING: Default -1 (unlimited), which is probably not what you want.
     *
     * @return timeout in ms, 0 for nonblocking, -1 for forever
     */
    public long getReadTimeout() {
        return _readTimeout;
    }

    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws
     * SocketTimeoutException as of 0.9.36.
     * Prior to that, the read() returned -1 or 0.
     *
     * WARNING: Default -1 (unlimited), which is probably not what you want.
     *
     * @param ms timeout in ms, 0 for nonblocking, -1 for forever
     */
    public void setReadTimeout(long ms) {
        _readTimeout = ms;
    }

    /**
     * How much data will we accept that hasn't been written out yet.  After
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     * Default 64 KB
     *
     * @return buffer size limit, in bytes
     */
    public int getMaxBufferSize() {
        return _maxBufferSize;
    }

    /**
     * How much data will we accept that hasn't been written out yet.  After
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     * Default 64 KB
     *
     */
    public void setMaxBufferSize(int numBytes) {
        _maxBufferSize = numBytes;
    }

    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws
     * InterruptedIOException.  If this is less than or equal to zero, there
     * is no timeout.
     *
     * Default -1 (unlimited)
     */
    public long getWriteTimeout() {
        return _writeTimeout;
    }

    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws
     * InterruptedIOException.  If this is less than or equal to zero, there
     * is no timeout.
     *
     * Default -1 (unlimited)
     */
    public void setWriteTimeout(long ms) {
        _writeTimeout = ms;
    }

    /**
     *  The remote port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getPort() {
        return _remotePort;
    }

    /**
     *  The remote port.
     *  @param port 0 - 65535
     *  @throws IllegalArgumentException
     *  @since 0.8.9
     */
    public void setPort(int port) {
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("bad port");
        _remotePort = port;
    }

    /**
     *  The local port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getLocalPort() {
        return _localPort;
    }

    /**
     *  The local port.
     *  Zero (default) means you will receive traffic on all ports.
     *  Nonzero means you will get traffic ONLY for that port, use with care,
     *  as most applications do not specify a remote port.
     *  @param port 0 - 65535
     *  @throws IllegalArgumentException
     *  @since 0.8.9
     */
    public void setLocalPort(int port) {
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("bad port");
        _localPort = port;
    }
}
