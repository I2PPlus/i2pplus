package net.i2p.client.streaming;

/**
 * Define the configuration for streaming and verifying data on the socket.
 * Use I2PSocketManager.buildOptions() to get one of these.
 */
public interface I2PSocketOptions {
    /** How much data will we accept that hasn't been written out yet. */
    public static final String PROP_BUFFER_SIZE = "i2p.streaming.bufferSize";
    /** How long wait for the ACK from a SYN, in milliseconds. */
    public static final String PROP_CONNECT_TIMEOUT = "i2p.streaming.connectTimeout";
    /** How long to block on read. */
    public static final String PROP_READ_TIMEOUT = "i2p.streaming.readTimeout";
    /** How long to block on write/flush */
    public static final String PROP_WRITE_TIMEOUT = "i2p.streaming.writeTimeout";

    /**
     * How long we will wait for the ACK from a SYN, in milliseconds.
     *
     * Default 60 seconds. Max of 2 minutes enforced in Connection.java,
     * and it also interprets &lt;= 0 as default.
     *
     * @return milliseconds to wait, or -1 if we will wait indefinitely
     */
    public long getConnectTimeout();

    /**
     * Define how long we will wait for the ACK from a SYN, in milliseconds.
     *
     * Default 60 seconds. Max of 2 minutes enforced in Connection.java,
     * and it also interprets &lt;= 0 as default.
     *
     * @param ms timeout in ms
     */
    public void setConnectTimeout(long ms);

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
    public long getReadTimeout();

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
    public void setReadTimeout(long ms);

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
    public int getMaxBufferSize();

    /**
     * How much data will we accept that hasn't been written out yet.  After
     * this amount has been exceeded, subsequent .write calls will block until
     * either some data is removed or the connection is closed.  If this is
     * less than or equal to zero, there is no limit (warning: can eat ram)
     *
     * Default 64 KB
     *
     * @param numBytes How much data will we accept that hasn't been written out yet.
     */
    public void setMaxBufferSize(int numBytes);

    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws
     * InterruptedIOException.  If this is less than or equal to zero, there
     * is no timeout.
     *
     * Default -1 (unlimited)
     *
     * @return wait time to block on the output stream while waiting for the data to flush.
     */
    public long getWriteTimeout();

    /**
     * What is the longest we'll block on the output stream while waiting
     * for the data to flush.  If this value is exceeded, the write() throws
     * InterruptedIOException.  If this is less than or equal to zero, there
     * is no timeout.
     *
     * Default -1 (unlimited)
     *
     * @param ms wait time to block on the output stream while waiting for the data to flush.
     */
    public void setWriteTimeout(long ms);

    /**
     *  The remote port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getPort();

    /**
     *  The remote port.
     *  @param port 0 - 65535
     *  @since 0.8.9
     */
    public void setPort(int port);

    /**
     *  The local port.
     *  @return Default I2PSession.PORT_UNSPECIFIED (0) or PORT_ANY (0)
     *  @since 0.8.9
     */
    public int getLocalPort();

    /**
     *  The local port.
     *  Zero (default) means you will receive traffic on all ports.
     *  Nonzero means you will get traffic ONLY for that port, use with care,
     *  as most applications do not specify a remote port.
     *  @param port 0 - 65535
     *  @since 0.8.9
     */
    public void setLocalPort(int port);
}
