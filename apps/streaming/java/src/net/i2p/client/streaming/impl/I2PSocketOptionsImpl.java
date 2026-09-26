package net.i2p.client.streaming.impl;

import java.util.Properties;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.util.SystemVersion;

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

    /**
     *  Default max buffer size.  Platform-dependent: a slow system, or one
     *  reporting less than 512 MB of memory, gets 512 KB, everything else 1 MB
     *  (see the sizing below).
     */
    public static final int DEFAULT_BUFFER_SIZE = SystemVersion.isSlow() || SystemVersion.getMaxMemory() < 512*1024*1024 ?
        // Slow systems: 1730 * (1.5*192 + 2) = 500KB, rounded to 512KB for safety margin
        512*1024 :
        // Normal systems: 1730 * (1.5*384 + 2) = 998KB, rounded to 1MB for safety margin
        1024*1024;
    /** Default read timeout in ms; -1 means block forever. */
    public static final int DEFAULT_READ_TIMEOUT = -1;
    /** Default write timeout in ms; -1 means never time out. */
    public static final int DEFAULT_WRITE_TIMEOUT = -1;
    /**
     *  Default base connect timeout in ms (30 seconds).  This is the pre-scaling
     *  window only: the effective window is scaled by the connect timeout
     *  multiplier and capped at the absolute max connect timeout (75 seconds
     *  by default), so a caller setting a longer value still waits no longer
     *  than the cap.
     */
    public static final int DEFAULT_CONNECT_TIMEOUT = 30*1000;

    /**
     *  Max buffer size, connect timeout, read timeout, and write timeout
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
     *  Max buffer size, connect timeout, read timeout, and write timeout
     *  from properties. Does not set local port or remote port.
     *
     *  A property absent from opts, or one that does not parse as an integer,
     *  falls back to the corresponding default.
     *
     *  @param opts may be null
     */
    public I2PSocketOptionsImpl(Properties opts) {
        init(opts);
    }

    /**
     *  Max buffer size, connect timeout, read timeout, and write timeout
     *  from properties. Does not set local port or remote port.
     *
     *  Only the properties present in opts are applied; anything absent from
     *  opts keeps the value this object already has, so a partial opts
     *  overrides only what it names.
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
     *  Max buffer size, connect timeout, read timeout, and write timeout
     *  from properties. Does not set local port or remote port.
     */
    protected void init(Properties opts) {
        _maxBufferSize = getInt(opts, PROP_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        _connectTimeout = getInt(opts, PROP_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        _readTimeout = getInt(opts, PROP_READ_TIMEOUT, -1);
        _writeTimeout = getInt(opts, PROP_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
    }

    /**
     * Parse an integer property value, with a default on failure.
     * @return the int
     */
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
    static double getDouble(Properties opts, String name, double defaultVal) {
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
     * Base time to wait for the ACK from a SYN, in milliseconds.
     *
     * The stored value is un-scaled; the effective connect window adds the
     * connect delay on the delayed-SYN path, is scaled by the connect timeout
     * multiplier, and is capped at the absolute max connect timeout, so a
     * stored value above that cap waits no longer than the cap.
     *
     * Default 30 seconds.
     *
     * @return milliseconds to wait, or a non-positive value for no connect
     *         timeout, i.e. the handshake is not given up on a timer
     */
    @Override
    public long getConnectTimeout() {
        return _connectTimeout;
    }

    /**
     * Define how long we will wait for the ACK from a SYN, in milliseconds.
     *
     * The value is stored un-scaled; see {@link #getConnectTimeout()}.
     *
     * Default 30 seconds.
     *
     * @param ms timeout in ms, &lt;= 0 for no connect timeout
     */
    @Override
    public void setConnectTimeout(long ms) {
        _connectTimeout = ms;
    }

    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws
     * SocketTimeoutException.
     *
     * WARNING: Default -1 (unlimited), which is probably not what you want.
     *
     * @return timeout in ms, 0 for nonblocking, -1 for forever
     */
    @Override
    public long getReadTimeout() {
        return _readTimeout;
    }

    /**
     * What is the longest we'll block on the input stream while waiting
     * for more data.  If this value is exceeded, the read() throws
     * SocketTimeoutException.
     *
     * WARNING: Default -1 (unlimited), which is probably not what you want.
     *
     * @param ms timeout in ms, 0 for nonblocking, -1 for forever
     */
    @Override
    public void setReadTimeout(long ms) {
        _readTimeout = ms;
    }

    /**
     * How much data will we accept that hasn't been written out yet.  After
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     * Default is {@link #DEFAULT_BUFFER_SIZE}, which is platform-dependent.
     *
     * @return buffer size limit, in bytes
     */
    @Override
    public int getMaxBufferSize() {
        return _maxBufferSize;
    }

    /**
     * How much data will we accept that hasn't been written out yet.  After
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     * Default is {@link #DEFAULT_BUFFER_SIZE}, which is platform-dependent.
     *
     * @param numBytes How much data will we accept that hasn't been written out yet.
     */
    @Override
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
     * @return the write timeout
     */
    @Override
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
     * @param ms wait time to block on the output stream while waiting for the data to flush.
     */
    @Override
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
    @Override
    public void setPort(int port) {
        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Bad port");
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
            throw new IllegalArgumentException("Bad port");
        _localPort = port;
    }
}
