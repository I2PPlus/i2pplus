package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * An OutputStream implementation that buffers data and forwards it
 * to a {@link DataReceiver} in chunks upon buffer full or flush.
 * Provides throttling and blocking behavior according to the data receiver's needs.
 *
 * This class maintains an internal buffer with configurable size limits,
 * supports resizing the buffer size during operation (only effective when buffer is empty),
 * and allows passive flushes after inactivity delays to avoid excessive buffering.
 *
 * MessageOutputStream -> ConnectionDataReceiver -> Connection -> PacketQueue -> I2PSession
 *
 * Thread safety:
 * - Write operations synchronize on an internal data lock when manipulating the buffer.
 * - Waiting for buffer acceptance and completion is done outside synchronization to avoid blocking other threads.
 *
 * Performance:
 * - Uses a shared ByteCache for buffer byte arrays to reduce allocations.
 * - Supports a passive flush mechanism to flush buffered data after inactivity.
 * - Minimizes locking time by doing blocking waits outside of synchronized blocks.
 *
 * Note:
 * - Buffer resizing only affects the max size used to determine flush boundaries,
 *   buffer reallocation does not occur dynamically.
 * - Stream errors are captured and thrown upon subsequent I/O calls.
 * - Interruptions during wait throw InterruptedIOException accordingly.
 * - Close() flushes any remaining data and releases internal buffers.
 */
class MessageOutputStream extends OutputStream {
    private final I2PAppContext _context;
    private final Log _log;

    // Buffer and buffer management
    // _buf is fetched from ByteCache, length == _originalBufferSize
    private byte[] _buf;
    private int _valid;  // Number of valid bytes currently in buffer

    private final Object _dataLock = new Object();

    private final DataReceiver _dataReceiver;

    // Track any stream IOException to propagate on next I/O call
    private final AtomicReference<IOException> _streamError = new AtomicReference<>();

    // Whether this stream is closed
    private final AtomicBoolean _closed = new AtomicBoolean(false);

    // Total bytes written since creation
    private long _written;

    // Write timeout in milliseconds for blocking waits, -1 indicates infinite
    private volatile int _writeTimeout = -1;

    // Buffer caching utility
    private final ByteCache _dataCache;

    // Original and current buffer sizes controlling maximum buffer flush size
    private final int _originalBufferSize;
    private int _currentBufferSize;

    // Buffer requested size for next flush (will update only when buffer is empty)
    private volatile int _nextBufferSize = 0;

    // Flusher that passively flushes data after inactivity period
    private final Flusher _flusher;

    // Timestamp of last buffered data for flush delay computation
    private volatile long _lastBuffered;

    // How long to wait before passive flush triggers (milliseconds)
    private final int _passiveFlushDelay;

    /**
     * Default passive flush delay to reduce latency on slow systems.
     */
    private static final int DEFAULT_PASSIVE_FLUSH_DELAY = SystemVersion.isSlow() ? 200 : 100;
    private static final String PROP_PASSIVE_FLUSH_DELAY = "router.passiveFlushDelay";

    /**
     * Constructs the stream with default passive flush delay.
     *
     * @param ctx          Application context for logging and utilities
     * @param timer        Timer used for scheduling passive flush events
     * @param receiver     DataReceiver that handles actual data dispatch
     * @param bufSize      Maximum buffer size allowed (will be clamped to preset max values)
     * @param initBufSize  Initial buffer size to use for buffering (<= bufSize)
     */
    public MessageOutputStream(I2PAppContext ctx, SimpleTimer2 timer,
                               DataReceiver receiver, int bufSize, int initBufSize) {
        this(ctx, timer, receiver, bufSize, initBufSize, DEFAULT_PASSIVE_FLUSH_DELAY);
    }

    /**
     * Constructs the stream with specified passive flush delay.
     *
     * @param ctx                Application context for logging and utilities
     * @param timer              Timer used for scheduling passive flush events
     * @param receiver           DataReceiver that handles actual data dispatch
     * @param bufSize            Maximum buffer size allowed (will be clamped for safety)
     * @param initBufSize        Initial buffer size to use for buffering (<= bufSize)
     * @param passiveFlushDelay  Delay in ms before passive flush if data is buffered but not flushed
     */
    public MessageOutputStream(I2PAppContext ctx, SimpleTimer2 timer,
                               DataReceiver receiver, int bufSize, int initBufSize, int passiveFlushDelay) {
        super();

        // Clamp buffer size to allowed maximums to prevent resource exhaustion attacks
        if (bufSize < ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE) {
            bufSize = ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE;
        } else if (bufSize > ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE &&
                   bufSize < ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE_RATCHET) {
            bufSize = ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE_RATCHET;
        }

        _dataCache = ByteCache.getInstance(128, bufSize);
        _originalBufferSize = bufSize;

        // Ensure initial buffer size doesn't exceed original buffer size
        if (initBufSize <= 0 || initBufSize > _originalBufferSize)
            _currentBufferSize = _originalBufferSize;
        else
            _currentBufferSize = initBufSize;

        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _buf = _dataCache.acquire().getData();
        _dataReceiver = receiver;
        _writeTimeout = -1;
        _passiveFlushDelay = passiveFlushDelay;
        _flusher = new Flusher(timer);
        _lastBuffered = 0L;
    }

    /**
     * Sets the write timeout in milliseconds for blocking write operations.
     *
     * @param ms Timeout in milliseconds, -1 for infinite wait.
     */
    public void setWriteTimeout(int ms) {
        if (_log.shouldDebug()) {
            _log.debug("Changing write timeout from " + _writeTimeout + " to " + ms);
        }
        _writeTimeout = ms;
    }

    /**
     * Gets the current write timeout.
     *
     * @return write timeout in milliseconds
     */
    public int getWriteTimeout() {
        return _writeTimeout;
    }

    /**
     * Requests resizing the buffer to the specified size.
     * Effective only when the internal buffer is empty, and
     * must be > 0 and <= the original max buffer size.
     * Does not immediately resize the underlying byte array.
     *
     * @param size New desired buffer size, must be positive and no greater than original buffer size.
     */
    public void setBufferSize(int size) {
        if (size <= 0 || size > _originalBufferSize) return;
        _nextBufferSize = size;
    }

    /**
     * Writes the specified byte array to the stream, may block waiting for acceptance.
     *
     * @param b   byte array to write
     * @throws IOException if the stream is closed or write fails
     */
    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    /**
     * Writes len bytes from the specified byte array starting at offset off.
     * Blocks if buffer becomes full waiting for acceptance.
     *
     * @param b     byte array source
     * @param off   start offset in array
     * @param len   number of bytes to write
     * @throws IOException if the stream is closed or write fails
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (_closed.get()) throw new IOException("Output stream closed");
        if (b == null) throw new NullPointerException("Buffer is null");
        if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();

        int cur = off;
        int remaining = len;

        long traceStart = _log.shouldDebug() ? _context.clock().now() : 0;

        while (remaining > 0) {
            WriteStatus ws = null;
            if (_closed.get()) throw new IOException("Output stream closed");

            synchronized (_dataLock) {
                final int maxBuffer = (_valid == 0) ? locked_updateBufferSize() : _currentBufferSize;
                if (_buf == null) throw new IOException("Output stream closed");

                if (_valid + remaining < maxBuffer) {
                    // Buffer has space for all remaining data; copy and defer flush
                    System.arraycopy(b, cur, _buf, _valid, remaining);
                    _valid += remaining;
                    cur += remaining;
                    _written += remaining;
                    _lastBuffered = _context.clock().now();

                    if (_passiveFlushDelay > 0) {
                        _flusher.enqueue();
                    }
                    remaining = 0;
                } else {
                    // Buffer capacity reached, fill available space and flush
                    int toWrite = maxBuffer - _valid;
                    System.arraycopy(b, cur, _buf, _valid, toWrite);
                    remaining -= toWrite;
                    cur += toWrite;
                    _valid = maxBuffer;
                    if (_log.shouldDebug()) {
                        _log.debug("write() buffer full, flushing " + _valid + " bytes");
                    }
                    ws = _dataReceiver.writeData(_buf, 0, _valid);
                    _written += _valid;
                    _valid = 0;
                    throwAnyError();
                }
            }
            // Wait for acceptance outside lock to avoid blocking other writers
            if (ws != null) {
                if (_log.shouldDebug()) {
                    _log.debug("Waiting up to " + _writeTimeout + "ms for write acceptance");
                }
                try {
                    ws.waitForAccept(_writeTimeout);
                } catch (InterruptedException ie) {
                    InterruptedIOException iioe = new InterruptedIOException("Interrupted write");
                    iioe.initCause(ie);
                    throw iioe;
                }
                if (!ws.writeAccepted()) {
                    String reason = ws.toString().isEmpty() ? "" : ": " + ws;
                    if (_writeTimeout > 0) {
                        throw new InterruptedIOException("Write not accepted within timeout" + reason);
                    } else {
                        throw new IOException("Write not accepted into the queue" + reason);
                    }
                }
                if (_log.shouldInfo()) {
                    _log.info("Write accepted after wait");
                }
            } else {
                if (_log.shouldDebug()) {
                    _log.debug("Buffered " + len + " bytes without flush");
                }
            }
        }

        if (_log.shouldDebug()) {
            long elapsed = _context.clock().now() - traceStart;
            if (elapsed > 10_000) {
                _log.debug("write() took " + elapsed + "ms", new Exception("SlowWrite"));
            }
        }
        throwAnyError();
    }

    /**
     * Writes a single byte to the stream.
     *
     * @param b byte to write (only least significant 8 bits used)
     * @throws IOException if the stream is closed or write fails
     */
    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
        throwAnyError();
    }

    /**
     * Updates the current buffer size if a new size has been requested.
     * This occurs only when the buffer is empty to avoid data loss.
     *
     * @return the effective current buffer size
     */
    private final int locked_updateBufferSize() {
        int size = _nextBufferSize;
        if (size > 0) {
            // Apply the requested buffer size change
            _currentBufferSize = size;
            _nextBufferSize = 0;
            if (_log.shouldDebug()) {
                _log.debug("Updated buffer size to " + size);
            }
        }
        return _currentBufferSize;
    }

    /**
     * Flushes buffered data, blocking until accepted into the send queue.
     * Does not wait for ACK from far end, to avoid performance bottlenecks.
     *
     * @throws IOException if flushing encounters an error
     */
    @Override
    public void flush() throws IOException {
        flush(true);
    }

    /**
     * Flush implementation allowing control over wait behavior.
     *
     * @param waitForAcceptOnly true to wait only for buffer acceptance,
     *                          false to wait for full completion (ACK)
     * @throws IOException if flushing fails or is interrupted
     */
    private void flush(boolean waitForAcceptOnly) throws IOException {
        long traceStart = _log.shouldDebug() ? _context.clock().now() : 0;
        WriteStatus ws = null;

        if (_log.shouldDebug() && _valid > 0) {
            _log.debug("flush() called with valid bytes = " + _valid);
        }

        synchronized (_dataLock) {
            if (_buf == null) {
                _dataLock.notifyAll();
                throw new IOException("Output stream closed");
            }
            // Conditional flush inside lock for writeData
            if (!waitForAcceptOnly && _valid > 0) {
                ws = _dataReceiver.writeData(_buf, 0, _valid);
                _written += _valid;
                _valid = 0;
                _dataLock.notifyAll();
            }
        }

        if (waitForAcceptOnly) {
            flushAvailable(_dataReceiver, true);
            return;
        }

        // Wait for complete ACK or until timeout
        if (_log.shouldDebug()) {
            _log.debug("Waiting up to " + _writeTimeout + "ms for write completion, ws: " + ws);
        }

        try {
            if (ws == null) {
                if (_log.shouldInfo()) {
                    _log.info("WriteStatus is null during flush");
                    throw new IOException("DataReceiver returned null WriteStatus");
                }
            } else if (_closed.get() && (_writeTimeout > Connection.DISCONNECT_TIMEOUT || _writeTimeout <= 0)) {
                ws.waitForCompletion(Connection.DISCONNECT_TIMEOUT);
            } else if (_writeTimeout <= 0 || _writeTimeout > Connection.DISCONNECT_TIMEOUT) {
                ws.waitForCompletion(Connection.DISCONNECT_TIMEOUT);
            } else {
                ws.waitForCompletion(_writeTimeout);
            }
        } catch (InterruptedException ie) {
            InterruptedIOException iioe = new InterruptedIOException("Interrupted flush");
            iioe.initCause(ie);
            throw iioe;
        }

        if (_log.shouldDebug()) {
            _log.debug("Finished waiting for write completion");
        }

        if (ws != null) {
            if (ws.writeFailed() && _writeTimeout > 0) {
                throw new InterruptedIOException("Timed out during write");
            } else if (ws.writeFailed()) {
                throw new IOException("Write failed");
            }
        }

        if (_log.shouldDebug()) {
            long elapsed = _context.clock().now() - traceStart;
            if (elapsed > 10_000) {
                _log.debug("flush() took " + elapsed + "ms", new Exception("SlowFlush"));
            }
        }
        throwAnyError();
    }

    /**
     * Closes the stream, flushing all buffered data and waiting
     * for any CLOSE packet acknowledgments.
     *
     * @throws IOException if closing encounters an error
     */
    @Override
    public void close() throws IOException {
        if (!_closed.compareAndSet(false, true)) {
            // Already closed, notify waiters just in case deadlock exists
            synchronized (_dataLock) {
                _dataLock.notifyAll();
            }
            _log.logCloseLoop("MessageOutputStream close called multiple times");
            return;
        }

        _flusher.cancel();

        // Flush all data including waiting for acknowledgment of CLOSE
        flush(false);

        if (_log.shouldDebug()) {
            _log.debug("Stream closed after writing " + _written + " bytes");
        }

        ByteArray ba = null;
        synchronized (_dataLock) {
            if (_buf != null) {
                ba = new ByteArray(_buf);
                _buf = null;
                _valid = 0;
            }
            _dataLock.notifyAll();
        }
        if (ba != null) {
            _dataCache.release(ba);
        }
    }

    /**
     * Non-blocking internal close used within the package.
     * Does not wait for flush or acknowledgment.
     * Marks stream as closed and clears buffer immediately.
     */
    void closeInternal() {
        if (!_closed.compareAndSet(false, true)) {
            _log.logCloseLoop("closeInternal called multiple times");
            return;
        }
        _flusher.cancel();
        _streamError.compareAndSet(null, new IOException("Output stream closed"));
        clearData(false);
    }

    /**
     * Clears any buffered data and optionally flushes them (non-blocking).
     *
     * @param shouldFlush true to flush buffered data before clearing
     */
    private void clearData(boolean shouldFlush) {
        ByteArray ba = null;
        if (_log.shouldDebug() && _valid > 0) {
            _log.debug("clearData() clearing " + _valid + " bytes");
        }
        synchronized (_dataLock) {
            if (_valid > 0 && shouldFlush) {
                _dataReceiver.writeData(_buf, 0, _valid);
            }
            _written += _valid;
            _valid = 0;

            if (_buf != null) {
                ba = new ByteArray(_buf);
                _buf = null;
                _valid = 0;
            }
            _dataLock.notifyAll();
        }
        if (ba != null) {
            _dataCache.release(ba);
        }
    }

    /**
     * Returns true if the stream is closed.
     *
     * @return true if closed
     */
    public boolean getClosed() {
        return _closed.get();
    }

    /**
     * Throws any stored stream error and clears the error.
     *
     * @throws IOException if a stream error has occurred
     */
    private void throwAnyError() throws IOException {
        IOException ioe = _streamError.getAndSet(null);
        if (ioe != null) {
            IOException wrapped = new IOException("Output stream error");
            wrapped.initCause(ioe);
            throw wrapped;
        }
    }

    /**
     * Records a stream error and clears buffered data.
     *
     * @param ioe The IOException to record
     */
    void streamErrorOccurred(IOException ioe) {
        _streamError.compareAndSet(null, ioe);
        clearData(false);
    }

    /**
     * Attempts to flush buffered data to the target DataReceiver,
     * optionally blocking until accepted.
     *
     * @param target   the destination DataReceiver
     * @param blocking whether the flush should block until accepted
     * @throws IOException if the flush fails or is interrupted
     */
    void flushAvailable(DataReceiver target, boolean blocking) throws IOException {
        WriteStatus ws;

        if (_log.shouldDebug() && _valid > 0) {
            _log.debug("flushAvailable() flushing " + _valid + " bytes");
        }

        synchronized (_dataLock) {
            ws = target.writeData(_buf, 0, _valid);
            _written += _valid;
            _valid = 0;
            _dataLock.notifyAll();
        }

        if (blocking && ws != null) {
            try {
                ws.waitForAccept(_writeTimeout);
            } catch (InterruptedException ie) {
                InterruptedIOException iioe = new InterruptedIOException("Interrupted flush");
                iioe.initCause(ie);
                throw iioe;
            }
            if (ws.writeFailed()) {
                throw new IOException("Flush available failed");
            }
            if (!ws.writeAccepted()) {
                throw new InterruptedIOException("Flush available timed out (" + _writeTimeout + "ms)");
            }
        }
    }

    /**
     * Convenience overload variant with blocking flush.
     *
     * @param target DataReceiver to flush to
     * @throws IOException if flush fails or interrupted
     */
    void flushAvailable(DataReceiver target) throws IOException {
        flushAvailable(target, true);
    }

    /**
     * Releases resources without flushing.
     */
    void destroy() {
        if (!_closed.compareAndSet(false, true)) {
            _log.logCloseLoop("destroy() called multiple times");
            return;
        }
        _flusher.cancel();
        synchronized (_dataLock) {
            _dataLock.notifyAll();
        }
    }

    /**
     * Interface for receiving data flushed from this stream.
     */
    public interface DataReceiver {
        /**
         * Non-blocking write of data.
         *
         * @param buf  buffer containing data
         * @param off  offset in buffer to start writing
         * @param size number of bytes to write
         * @return WriteStatus to monitor acceptance and completion of write
         */
        WriteStatus writeData(byte[] buf, int off, int size);

        /**
         * Indicates whether a write is currently in process.
         *
         * @return true if a write is in progress, false otherwise
         */
        public boolean writeInProcess();
    }

    /**
     * Interface to detect status of an asynchronous write operation.
     */
    public interface WriteStatus {
        /**
         * Wait until data write either succeeds or fails (ACK received).
         *
         * @param maxWaitMs maximum wait time in milliseconds (-1 means wait forever)
         * @throws IOException          if write fails or times out
         * @throws InterruptedException if waiting thread is interrupted
         */
        void waitForCompletion(int maxWaitMs) throws IOException, InterruptedException;

        /**
         * Wait until data write is accepted into outbound queue, indicating space is available.
         *
         * @param maxWaitMs maximum wait time in milliseconds (-1 means wait forever)
         * @throws IOException          if acceptance fails or times out
         * @throws InterruptedException if waiting thread is interrupted
         */
        void waitForAccept(int maxWaitMs) throws IOException, InterruptedException;

        /**
         * Returns true if the write was accepted by the outbound queue.
         *
         * @return true if accepted, false otherwise
         */
        boolean writeAccepted();

        /**
         * Returns true if the write operation failed.
         *
         * @return true if failed, false otherwise
         */
        boolean writeFailed();

        /**
         * Returns true if the write operation succeeded.
         *
         * @return true if successful, false otherwise
         */
        boolean writeSuccessful();
    }

    /**
     * Passive flush timer to flush buffered data after inactivity delay.
     * Ensures data does not stay buffered indefinitely.
     */
    private class Flusher extends SimpleTimer2.TimedEvent {
        private volatile boolean _enqueued = false;

        /**
         * Constructs a Flusher with given timer.
         *
         * @param timer timer to schedule flush events
         */
        public Flusher(SimpleTimer2 timer) {
            super(timer);
        }

        /**
         * Enqueues or reschedules the flush event after the passive flush delay.
         * Avoids scheduling duplicate flush events.
         */
        public void enqueue() {
            int pfd = _context.getProperty(PROP_PASSIVE_FLUSH_DELAY, DEFAULT_PASSIVE_FLUSH_DELAY);
            if (!_enqueued) {
                forceReschedule(pfd);
                if (_log.shouldDebug()) {
                    _log.debug("Rescheduled the flusher to run in " + pfd + "ms");
                }
                _enqueued = true;
            } else if (_log.shouldDebug()) {
                _log.debug("Flusher already enqueued; skipping reschedule");
            }
        }

        /**
         * Called when flush timer expires.
         * Attempts passive flush if no recent writes detected or if no write in progress.
         */
        @Override
        public void timeReached() {
            if (_closed.get()) return;
            _enqueued = false;

            long timeLeft = (_lastBuffered + _passiveFlushDelay - _context.clock().now());
            if (_log.shouldDebug()) {
                _log.debug("Flusher fired, time left until passive flush: " + timeLeft + "ms");
            }
            if (timeLeft > 0) {
                enqueue();
                return;
            }

            if (_dataReceiver.writeInProcess()) {
                enqueue();
                return;
            }

            doFlush();
        }

        /**
         * Performs the actual flush of buffered data, if any.
         * If data is available and the passive flush delay has elapsed,
         * writes data to the data receiver and resets the buffer.
         *
         * Handles null WriteStatus gracefully to avoid NullPointerException.
         */
        private void doFlush() {
            WriteStatus ws = null;
            boolean sent = false;
            synchronized (_dataLock) {
                long flushTimeThreshold = _lastBuffered + _passiveFlushDelay;
                if (_valid > 0 && flushTimeThreshold <= _context.clock().now()) {
                    if (_buf != null) {
                        ws = _dataReceiver.writeData(_buf, 0, _valid);
                        _written += _valid;
                        _valid = 0;
                        _dataLock.notifyAll();
                        sent = true;
                    }
                } else if (_log.shouldInfo() && _valid > 0) {
                    _log.info("Passive flush skipped, valid=" + _valid);
                }
            }

            if (sent) {
                if (_log.shouldDebug()) {
                    _log.debug("Passive flush executed: " + ws);
                }
                if (ws != null) {} //no-op
                else {
                    if (_log.shouldWarn()) {
                        _log.warn("Passive flush executed but WriteStatus was null");
                    }
                }
            }
        }
    }

}
