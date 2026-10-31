package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * An OutputStream implementation that buffers data and forwards it
 * to a {@link DataReceiver} in chunks upon buffer full or flush.
 * Provides throttling and blocking behavior according to the data receiver's needs.
 * <p>
 * This class maintains an internal buffer with configurable size limits,
 * supports resizing the buffer size during operation (only effective when buffer is empty),
 * and allows passive flushes after inactivity delays to avoid excessive buffering.
 * <p>
 * MessageOutputStream -&gt; ConnectionDataReceiver -&gt; Connection -&gt;
 * PacketQueue -&gt; I2PSession
 * <p>
 * Thread safety:
 * - Write operations synchronize on an internal data lock when manipulating the buffer.
 * - Waiting for buffer acceptance and completion is done outside synchronization to avoid blocking other threads.
 * - The send itself is made under the data lock, and it parks while the session's
 *   write queue is full, so a flush can hold the lock for the whole of that park and
 *   every writer on the stream is blocked behind it. {@code stream.con.sendUnderLock}
 *   measures the hold, {@code stream.con.sendMessageTime} the park.
 * <p>
 * Performance:
 * - Uses a shared ByteCache for buffer byte arrays to reduce allocations.
 * - Supports a passive flush mechanism to flush buffered data after inactivity.
 * <p>
 * Note:
 * - Buffer resizing only affects the max size used to determine flush boundaries,
 *   buffer reallocation does not occur dynamically.
 * - Stream errors are captured and thrown upon subsequent I/O calls.
 * - Interruptions during wait throw InterruptedIOException accordingly.
 * - Close() flushes any remaining data and releases internal buffers.
 */
class MessageOutputStream extends OutputStream {
    /** The app context. */
    private final I2PAppContext _context;
    /** Logger for the output stream. */
    private final Log _log;

    /** The buffer accumulating the written data. */
    private byte[] _buf;
    /** Valid bytes held in the buffer. */
    private int _valid;  // Number of valid bytes currently in buffer

    /** Lock for the write buffer. */
    private final Object _dataLock = new Object();

    /**
     * Reusable buffer for single byte writes, guarded by _oneByteLock,
     * not _dataLock.
     */
    private final byte[] _oneByte = new byte[1];
    /** Lock for the single byte writes. */
    private final Object _oneByteLock = new Object();

    /** The connection receiving the data. */
    private final DataReceiver _dataReceiver;

    /** The stream error, kept to rethrow on the next call. */
    private final AtomicReference<IOException> _streamError = new AtomicReference<>();

    /** True once the stream is closed. */
    private final AtomicBoolean _closed = new AtomicBoolean(false);

    /** Total bytes written to the connection since creation. */
    private long _written;

    /** Blocking write timeout, in ms, -1 for no timeout. */
    private volatile int _writeTimeout = -1;

    /** Cache supplying the write buffer. */
    private final ByteCache _dataCache;

    /**
     * Buffer size requested at construction, the two sizes control
     * when the buffer is flushed.
     */
    private final int _originalBufferSize;
    /** Buffer size in use now. */
    private int _currentBufferSize;

    /** Buffer size requested by the next flush, updated when buffer drains. */
    private volatile int _nextBufferSize = 0;
    /** Flusher that sends buffered data after a period of inactivity. */
    private final Flusher _flusher;
    /** Timestamp of the most recently buffered data, in context clock ms. */
    private volatile long _lastBuffered;
    /** Delay in milliseconds before a passive flush is triggered. */
    private volatile int _passiveFlushDelay;

    /**
     * Default passive flush delay optimized for lower latency while maintaining
     * stability: 200 ms on a slow system, 100 ms otherwise.
     *
     * @since 0.9.70+ mutable for adaptive tuning
     */
    private static volatile int defaultPassiveFlushDelay = SystemVersion.isSlow() ? 200 : 100;

    /**
     * Read the passive flush delay applied when a caller does not name one.
     *
     * @return the flush delay in milliseconds used when none is specified
     * @since 0.9.70+
     */
    public static int getDefaultPassiveFlushDelay() { return defaultPassiveFlushDelay; }

    /**
     * Set the passive flush delay used when a caller does not name one.
     *
     * @param val requested flush delay in milliseconds, clamped to the range 10 - 500
     * @since 0.9.70+
     */
    public static void setDefaultPassiveFlushDelay(int val) { defaultPassiveFlushDelay = Math.max(10, Math.min(500, val)); }

    /**
     * Stream with default passive flush delay.
     *
     * @param ctx          Application context for logging and utilities
     * @param timer        Timer used for scheduling passive flush events
     * @param receiver     DataReceiver that handles actual data dispatch
     * @param bufSize      Maximum buffer size allowed (will be clamped to preset max values)
     * @param initBufSize  Initial buffer size to use for buffering (&lt;= bufSize)
     */
    public MessageOutputStream(I2PAppContext ctx, SimpleTimer2 timer,
                               DataReceiver receiver, int bufSize, int initBufSize) {
        this(ctx, timer, receiver, bufSize, initBufSize, defaultPassiveFlushDelay);
    }

    /**
     * Stream with specified passive flush delay.
     *
     * @param ctx                Application context for logging and utilities
     * @param timer              Timer used for scheduling passive flush events
     * @param receiver           DataReceiver that handles actual data dispatch
     * @param bufSize            Maximum buffer size allowed (will be clamped for safety)
     * @param initBufSize        Initial buffer size to use for buffering (&lt;= bufSize)
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
     * Write timeout in milliseconds for blocking write operations.
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
     * Current write timeout.
     *
     * @return write timeout in milliseconds
     */
    public int getWriteTimeout() {
        return _writeTimeout;
    }

    /**
     * Current passive flush delay.
     * @return current passive flush delay in ms
     */
    public int getPassiveFlushDelay() { return _passiveFlushDelay; }

    /**
     * Dynamically adjust the passive flush delay. Clamped to [10, 500].
     * Used by the interactive profile to reduce flush latency mid-connection.
     *
     * @param delayMs new delay, will be clamped to valid range
     */
    public void setPassiveFlushDelay(int delayMs) {
        if (delayMs < 10) delayMs = 10;
        if (delayMs > 500) delayMs = 500;
        _passiveFlushDelay = delayMs;
    }

    /**
     * Requests resizing the buffer to the specified size.
     * Effective only when the internal buffer is empty, and
     * must be &gt; 0 and &lt;= the original max buffer size.
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
            // -1: no send on this pass. 0 is a real send that returned at once.
            long sendMs = -1;
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
                    long sendStart = _context.clock().now();
                    ws = _dataReceiver.writeData(_buf, 0, _valid);
                    sendMs = _context.clock().now() - sendStart;
                    _written += _valid;
                    _valid = 0;
                    throwAnyError();
                }
            }
            if (sendMs >= 0) {recordSendUnderLock(sendMs);}
            // Wait for acceptance outside lock to avoid blocking other writers
            if (ws != null) {
                if (_log.shouldDebug()) {
                    _log.debug("Waiting up to " + _writeTimeout + "ms for write acceptance");
                }
                try {
                    ws.waitForAccept(_writeTimeout);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
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
                _log.debug("Slow write * Elapsed: " + DataHelper.formatDuration(elapsed));
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
        synchronized (_oneByteLock) {
            _oneByte[0] = (byte) b;
            write(_oneByte, 0, 1);
        }
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
        long sendMs = -1;

        if (_log.shouldDebug() && _valid > 0) {
            _log.debug("flush() called, valid bytes: " + _valid);
        }

        synchronized (_dataLock) {
            if (_buf == null) {
                _dataLock.notifyAll();
                throw new IOException("Output stream closed");
            }
            // Always call writeData even when _valid == 0: the close path
            // must be able to flush a CLOSE packet that was appended to the
            // buffer by the caller (i.e. flush(false) from close()).  This
            // matches mainline where writeData is unconditional ("this could
            // flush a CLOSE packet too").
            if (!waitForAcceptOnly) {
                long sendStart = _context.clock().now();
                ws = _dataReceiver.writeData(_buf, 0, _valid);
                sendMs = _context.clock().now() - sendStart;
                _written += _valid;
                _valid = 0;
                _dataLock.notifyAll();
            }
        }
        if (sendMs >= 0) {recordSendUnderLock(sendMs);}

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
                // Defensive only: DataReceiver.writeData never returns null. A
                // null WriteStatus would mean the data (or CLOSE) was never
                // queued, so there is nothing to wait on — surface it and move
                // on rather than throwing, since writeData's contract provides
                // exactly one status per call.
                if (_log.shouldWarn()) {
                    _log.warn("WriteStatus is null during flush");
                }
                return;
            } else if (_closed.get() && (_writeTimeout > Connection.getDisconnectTimeout() || _writeTimeout <= 0)) {
                ws.waitForCompletion(Connection.getDisconnectTimeout());
            } else if (_writeTimeout <= 0 || _writeTimeout > Connection.getDisconnectTimeout()) {
                ws.waitForCompletion(Connection.getDisconnectTimeout());
            } else {
                ws.waitForCompletion(_writeTimeout);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
                _log.debug("Slow flush * Elapsed: " + DataHelper.formatDuration(elapsed));
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
     * Marks the stream as closed and pushes any buffered data out
     * fire-and-forget, so a connection closed right after a write does not
     * lose the application's last bytes.
     */
    void closeInternal() {
        if (!_closed.compareAndSet(false, true)) {
            return;
        }
        _flusher.cancel();
        _streamError.compareAndSet(null, new IOException("Output stream closed"));
        clearData(true);
    }

    /**
     * Clears any buffered data, optionally flushing it out first.
     *
     * @param shouldFlush if true, send the buffered data (but do not wait for
     *                    acknowledgment); if false, discard it. The error path
     *                    discards, the close path flushes.
     */
    private void clearData(boolean shouldFlush) {
        ByteArray ba = null;
        long sendMs = -1;
        if (_log.shouldDebug() && _valid > 0) {
            _log.debug("Discarding " + _valid + " unsent bytes from message buffer");
        }
        synchronized (_dataLock) {
            // flush any data, but don't wait for it
            if (_valid > 0 && shouldFlush) {
                long sendStart = _context.clock().now();
                _dataReceiver.writeData(_buf, 0, _valid);
                sendMs = _context.clock().now() - sendStart;
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
        if (sendMs >= 0) {recordSendUnderLock(sendMs);}
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
        long sendMs = -1;

        if (_log.shouldDebug() && _valid > 0) {
            _log.debug("Flushing " + _valid + " message bytes to peer");
        }

        synchronized (_dataLock) {
            long sendStart = _context.clock().now();
            ws = target.writeData(_buf, 0, _valid);
            sendMs = _context.clock().now() - sendStart;
            _written += _valid;
            _valid = 0;
            _dataLock.notifyAll();
        }
        if (sendMs >= 0) {recordSendUnderLock(sendMs);}

        if (blocking && ws != null) {
            try {
                ws.waitForAccept(_writeTimeout);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
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
     * Record how long a send took while the data lock was held. The send parks
     * while the session's write queue is full, and every writer on this stream
     * is blocked behind the lock for the whole of that park, so the hold has to
     * show up in the stats and not only in a warn line.
     * <p>
     * Call this for every send, including one that returned at once: a stat that
     * only sees the slow sends has no denominator, and its average says nothing.
     * Callers therefore initialize the duration to -1 for "no send on this pass",
     * since 0 is a real send that was fast.
     *
     * @param ms the milliseconds the send took under the lock
     * @since 0.9.72+
     */
    private void recordSendUnderLock(long ms) {
        _context.statManager().addRateData("stream.con.sendUnderLock", ms);
    }

    /**
     * Releases resources without flushing.
     */
    void destroy() {
        if (!_closed.compareAndSet(false, true)) {
            return;
        }
        _flusher.cancel();
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

    }

    /**
     * Passive flush timer to flush buffered data after inactivity delay.
     * Ensures data does not stay buffered indefinitely.
     */
    private class Flusher extends SimpleTimer2.TimedEvent {
        /** True while the stream waits in the event queue. */
        private volatile boolean _enqueued = false;

        /**
         * Flusher with the given timer.
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
            if (!_enqueued) {
                forceReschedule(_passiveFlushDelay);
                if (_log.shouldDebug()) {
                    _log.debug("Rescheduled the flusher to run in " + _passiveFlushDelay + "ms");
                }
                _enqueued = true;
            } else if (_log.shouldDebug()) {
                _log.debug("Flusher already enqueued -> Not rescheduling");
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
            // Only log if delayed significantly (> 100ms) to reduce log spam
            if (_log.shouldDebug() && timeLeft <= -100) {
                _log.debug("Flusher delayed " + (-timeLeft) + "ms");
            }

            // Don't reschedule if there's no data to flush
            if (_valid <= 0) {
                return;
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
         * <p>
         * Handles null WriteStatus gracefully to avoid NullPointerException.
         */
        private void doFlush() {
            WriteStatus ws = null;
            boolean sent = false;
            long sendMs = -1;
            synchronized (_dataLock) {
                long flushTimeThreshold = _lastBuffered + _passiveFlushDelay;
                if (_valid > 0 && flushTimeThreshold <= _context.clock().now()) {
                    if (_buf != null) {
                        long sendStart = _context.clock().now();
                        ws = _dataReceiver.writeData(_buf, 0, _valid);
                        sendMs = _context.clock().now() - sendStart;
                        if (!ws.writeFailed()) {
                            _written += _valid;
                            _valid = 0;
                            _dataLock.notifyAll();
                            sent = true;
                        }
                    }
                } else if (_log.shouldInfo() && _valid > 0) {
                    _log.info("Passive flush skipped -> Valid bytes: " + _valid);
                }
            }
            if (sendMs >= 0) {recordSendUnderLock(sendMs);}

            if (sent && _log.shouldDebug()) {
                _log.debug("Passive flush executed: " + ws);
            } else if (ws != null && ws.writeFailed() && _log.shouldWarn()) {
                _log.warn("Passive flush failed, will retry on close: " + ws);
            }
        }
    }

}
