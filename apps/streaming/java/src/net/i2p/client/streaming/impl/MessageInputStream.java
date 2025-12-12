/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * InputStream implementation that accepts messages arriving out of order
 * and presents the data in proper sequence for reading.
 *
 * <p>This class buffers incoming data blocks and reorders them transparently,
 * ensuring a continuous byte stream to the caller. It supports:
 * <ul>
 *   <li>Out-of-order message handling</li>
 *   <li>Flow control via blocking reads</li>
 *   <li>Optional read timeouts</li>
 *   <li>EOF signaling via closeReceived()</li>
 *   <li>Error propagation via streamErrorOccurred()</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is synchronized internally and safe for use by one reader thread
 * and potentially multiple message-receiving threads.
 *
 * <p><b>Performance:</b> Designed for high-throughput scenarios such as large file downloads.
 * Avoids unnecessary copying by using ByteArray references.
 *
 * <p><b>Lifecycle:</b> The stream can be closed locally or receive an EOF signal remotely.
 * Once closed, no further reads or message receptions are allowed.
 *
 * <p><b>Usage:</b>
 * <pre>
 * I2PSession -> MessageHandler -> PacketHandler -> ConnectionPacketHandler -> MessageInputStream
 * </pre>
 */
class MessageInputStream extends InputStream {

    /**
     * Initial capacity for internal lists and maps.
     * Helps avoid frequent reallocations for common use cases.
     */
    private static final int INITIAL_CAPACITY = 4;

    /**
     * Minimum number of ready buffers before limiting applies.
     * Helps prevent unnecessary buffering limits on slow systems.
     */
    private static final int MIN_READY_BUFFERS = SystemVersion.isSlow() ? 128 : 1024;
    
    /**
     * Maximum number of packets to buffer regardless of byte size.
     * Prevents excessive memory usage with small packets while allowing
     * higher packet counts for better throughput.
     */
    private static final int MAX_PACKET_COUNT = 1024;

    private final Log _log;
    private final List<ByteArray> _readyDataBlocks; // Ordered list of ready data blocks
    private int _readyDataBlockIndex;               // Index in the current ready block
    private long _highestReadyBlockId;              // Highest fully received block ID
    private long _highestBlockId;                   // Highest received block ID (includes out-of-order)
    private final Map<Long, ByteArray> _notYetReadyBlocks; // Out-of-order blocks

    private boolean _closeReceived; // EOF signal received
    private final AtomicBoolean _locallyClosed = new AtomicBoolean(false); // Stream closed for reading
    private volatile int _readTimeout = I2PSocketOptionsImpl.DEFAULT_READ_TIMEOUT; // Read timeout in milliseconds
    private IOException _streamError;
    private long _readTotal; // Total bytes read so far

    private final int _maxMessageSize; // Max size per message block
    private final int _maxWindowSize;  // Max number of messages in window
    private final int _maxBufferSize;  // Max total buffer size in bytes
    private final int _maxPacketCount; // Max number of packets regardless of size

    private final byte[] _oneByte = new byte[1]; // For single-byte reads
    private final Object _dataLock = new Object(); // Lock for synchronized access

    // Sentinel used for out-of-order duplicates, avoids unnecessary allocations
    private static final ByteArray DUMMY_BA = new ByteArray(null);

    // Total size of all ready data blocks (for fast available() and canAccept())
    private int _readyDataSize = 0;

    /**
     * Constructs a new MessageInputStream with the specified buffer limits.
     *
     * @param ctx Application context for logging and utilities
     * @param maxMessageSize Max size of a single message block
     * @param maxWindowSize Max number of messages in the window
     * @param maxBufferSize Max total size of all buffered data
     */
    public MessageInputStream(I2PAppContext ctx, int maxMessageSize, int maxWindowSize, int maxBufferSize) {
        this(ctx, maxMessageSize, maxWindowSize, maxBufferSize, MAX_PACKET_COUNT);
    }
    
    /**
     * Constructs a new MessageInputStream with the specified buffer limits.
     *
     * @param ctx Application context for logging and utilities
     * @param maxMessageSize Max size of a single message block
     * @param maxWindowSize Max number of messages in the window
     * @param maxBufferSize Max total size of all buffered data
     * @param maxPacketCount Max number of packets regardless of byte size
     */
    public MessageInputStream(I2PAppContext ctx, int maxMessageSize, int maxWindowSize, int maxBufferSize, int maxPacketCount) {
        _log = ctx.logManager().getLog(MessageInputStream.class);
        _readyDataBlocks = new ArrayList<>(INITIAL_CAPACITY);
        _notYetReadyBlocks = new HashMap<>(INITIAL_CAPACITY);
        _highestReadyBlockId = -1;
        _highestBlockId = -1;
        _maxMessageSize = maxMessageSize;
        _maxWindowSize = maxWindowSize;
        _maxBufferSize = maxBufferSize;
        _maxPacketCount = maxPacketCount;
    }

    /**
     * Returns the highest consecutive block ID that has been fully received and marked ready.
     *
     * @return highest ready block ID, or -1 if none
     */
    public long getHighestReadyBlockId() {
        synchronized (_dataLock) {
            return _highestReadyBlockId;
        }
    }

    /**
     * Returns the highest block ID received so far, regardless of order.
     *
     * @return highest block ID received, or -1 if none
     */
    public long getHighestBlockId() {
        synchronized (_dataLock) {
            return _highestBlockId;
        }
    }

    /**
     * Gets the total number of bytes queued up in ready buffers.
     * Does not throw IOException on closed stream.
     *
     * @return number of bytes waiting to be read
     */
    public int getTotalReadySize() {
        synchronized (_dataLock) {
            if (_locallyClosed.get()) {
                return 0;
            }
            return _readyDataSize - _readyDataBlockIndex;
        }
    }

    /**
     * Checks if this stream has been closed locally.
     *
     * @return true if closed, false otherwise
     */
    public boolean isLocallyClosed() {
        return _locallyClosed.get();
    }

    /**
     * Determines whether the stream can accept a new message.
     *
     * Accepts zero-length or duplicate packets always.
     *
     * @param messageId The sequence ID of the message
     * @param payloadSize Size of the message payload
     * @return true if the message can be accepted, false otherwise
     */
    public boolean canAccept(long messageId, int payloadSize) {
        if (payloadSize <= 0) {
            return true;
        }

        synchronized (_dataLock) {
            if (messageId <= _highestReadyBlockId) {
                return true;
            }

            if (_locallyClosed.get()) {
                return _notYetReadyBlocks.containsKey(messageId);
            }

            if (messageId < MIN_READY_BUFFERS) {
                return true;
            }

            // Check both byte-based and packet-based limits
            int totalPackets = _readyDataBlocks.size() + _notYetReadyBlocks.size();
            if (totalPackets * _maxMessageSize < _maxBufferSize && totalPackets < _maxPacketCount) {
                return true;
            }

            if (_notYetReadyBlocks.containsKey(messageId)) {
                return true;
            }

            int available = _maxBufferSize - _readyDataSize;
            if (available <= 0) {
                logBufferFull(messageId, available);
                return false;
            }

            int allowedBlocks = available / _maxMessageSize;
            if (messageId > _highestReadyBlockId + allowedBlocks) {
                logBufferBlockWindowExceeded(messageId, allowedBlocks);
                return false;
            }

            if (_readyDataBlocks.size() >= 4 * _maxWindowSize) {
                logTooManyReadyBlocks(messageId);
                return false;
            }

            return true;
        }
    }

    private void logBufferFull(long messageId, int available) {
        if (_log.shouldWarn()) {
            _log.warn("Dropping message " + messageId + ", buffer size exceeded: available=" + available);
        }
        synchronized (_dataLock) {
            _dataLock.notifyAll();
        }
    }

    private void logBufferBlockWindowExceeded(long messageId, int allowedBlocks) {
        if (_log.shouldWarn()) {
            _log.warn("Dropping message " + messageId + ", exceeds allowed buffer blocks window");
        }
        synchronized (_dataLock) {
            _dataLock.notifyAll();
        }
    }

    private void logTooManyReadyBlocks(long messageId) {
        if (_log.shouldWarn()) {
            _log.warn("Dropping message " + messageId + ", too many ready blocks");
        }
        synchronized (_dataLock) {
            _dataLock.notifyAll();
        }
    }

    /**
     * Gets an array of missing block IDs between the highest ready block and highest received block.
     *
     * @return array of missing block IDs, or null if none
     */
    public long[] getNacks() {
        synchronized (_dataLock) {
            List<Long> missingIds = null;
            for (long i = _highestReadyBlockId + 1; i < _highestBlockId; i++) {
                if (!_notYetReadyBlocks.containsKey(i)) {
                    if (missingIds == null) {
                        missingIds = new ArrayList<>(4);
                    }
                    missingIds.add(i);
                }
            }
            if (missingIds != null) {
                long[] array = new long[missingIds.size()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = missingIds.get(i);
                }
                return array;
            }
            return null;
        }
    }

    /**
     * Updates the ACK and NACK fields of a packet based on current stream state.
     *
     * @param packet Packet to update
     */
    public void updateAcks(PacketLocal packet) {
        if (packet.getSendStreamId() > 0 || !packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
            synchronized (_dataLock) {
                packet.setAckThrough(_highestBlockId);
                packet.setNacks(getNacks());
            }
        } else {
            packet.setAckThrough(-1); // Don't send ACK 0 for retransmitted SYN packets
        }
    }

    /**
     * Notifies the stream that no more messages will be received after the current highest.
     * Buffered data remains accessible until read.
     */
    public void closeReceived() {
        synchronized (_dataLock) {
            if (_log.shouldDebug()) {
                _log.debug("Close received: ready blocks=" + _readyDataBlocks.size()
                        + ", not ready blocks=" + _notYetReadyBlocks.size()
                        + ", highest ready block=" + _highestReadyBlockId);
            }
            _closeReceived = true;
            _dataLock.notifyAll();
        }
    }

    /**
     * Wakes up any threads waiting for data.
     */
    public void notifyActivity() {
        synchronized (_dataLock) {
            _dataLock.notifyAll();
        }
    }

    /**
     * Accepts a newly received message block.
     *
     * If the message is next in sequence, it's added to the ready list and contiguous out-of-order
     * messages are promoted.
     *
     * @param messageId The message sequence ID
     * @param payload The message payload
     * @return true if the message was new, false if a duplicate
     */
    public boolean messageReceived(long messageId, ByteArray payload) {
        if (_log.shouldDebug()) {
            _log.debug("Received message ID " + messageId + ", length: " +
                       (payload != null ? payload.getValid() : "no payload"));
        }

        synchronized (_dataLock) {
            if (messageId <= _highestReadyBlockId) {
                if (_log.shouldInfo()) {
                    _log.info("Ignoring duplicate message ID " + messageId);
                }
                return false;
            }

            if (messageId > _highestBlockId) {
                _highestBlockId = messageId;
            }

            if (messageId == _highestReadyBlockId + 1) {
                if (!_locallyClosed.get() && payload != null && payload.getValid() > 0) {
                    _readyDataBlocks.add(payload);
                    _readyDataSize += payload.getValid();
                }
                _highestReadyBlockId = messageId;

                long cur = _highestReadyBlockId + 1;
                ByteArray ba;
                while ((ba = _notYetReadyBlocks.remove(cur)) != null) {
                    if (ba.getData() != null && ba.getValid() > 0) {
                        _readyDataBlocks.add(ba);
                        _readyDataSize += ba.getValid();
                    }
                    if (_log.shouldDebug()) {
                        _log.debug("Promoted out-of-order block " + cur + " to ready");
                    }
                    cur++;
                    _highestReadyBlockId++;
                }
                _dataLock.notifyAll();
            } else {
                if (_locallyClosed.get()) {
                    if (_log.shouldInfo()) {
                        _log.info("Received out-of-order message ID " + messageId + " on closed stream");
                    }
                    _notYetReadyBlocks.put(messageId, DUMMY_BA);
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Received out-of-order message ID " + messageId);
                    }
                    _notYetReadyBlocks.put(messageId, payload);
                }
            }
        }
        return true;
    }

    /**
     * Reads a single byte from the stream.
     *
     * @return the next byte (0-255), or -1 on EOF
     * @throws IOException if stream is closed or read fails
     */
    @Override
    public int read() throws IOException {
        int read = read(_oneByte, 0, 1);
        if (read <= 0) {
            return -1;
        }
        return _oneByte[0] & 0xFF;
    }

    /**
     * Reads bytes into the given array.
     * Throws SocketTimeoutException on timeout.
     *
     * @param target byte array to read into
     * @return number of bytes read, or -1 on EOF
     * @throws IOException if stream is closed or read fails
     */
    @Override
    public int read(byte[] target) throws IOException {
        return read(target, 0, target.length);
    }

    /**
     * Reads up to `length` bytes into the `target` array at `offset`.
     * Blocks until data is available or timeout occurs.
     *
     * @param target byte array to read into
     * @param offset offset in array to start writing
     * @param length number of bytes to read
     * @return number of bytes read, or -1 on EOF
     * @throws IOException if stream is closed or read fails
     */
    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        int readTimeout = _readTimeout;
        long expiration = readTimeout > 0 ? System.currentTimeMillis() + readTimeout : -1;
        boolean shouldDebug = _log.shouldDebug();

        synchronized (_dataLock) {
            if (_locallyClosed.get()) {
                throw new IOException("Input stream closed");
            }
            throwAnyError();

            int totalRead = 0;

            while (totalRead < length) {
                if (_readyDataBlocks.isEmpty() && totalRead == 0) {
                    while (_readyDataBlocks.isEmpty()) {
                        if (_locallyClosed.get()) {
                            throw new IOException("Input stream closed");
                        }
                        if (_notYetReadyBlocks.isEmpty() && _closeReceived) {
                            if (_log.shouldInfo()) {
                                _log.info("EOF reached after reading " + _readTotal + " bytes");
                            }
                            return -1;
                        }
                        try {
                            if (readTimeout < 0) {
                                if (shouldDebug) _log.debug("Waiting indefinitely for data");
                                _dataLock.wait();
                                throwAnyError();
                            } else if (readTimeout > 0) {
                                if (shouldDebug) _log.debug("Waiting up to " + readTimeout + "ms for data");
                                _dataLock.wait(readTimeout);
                                throwAnyError();

                                if (_readyDataBlocks.isEmpty()) {
                                    long remaining = expiration - System.currentTimeMillis();
                                    if (remaining <= 0) {
                                        if (_log.shouldInfo()) {
                                            _log.info("Read timed out after " + readTimeout + "ms");
                                        }
                                        throw new SocketTimeoutException("Read timed out");
                                    }
                                    readTimeout = (int) remaining;
                                }
                            } else {
                                return 0; // non-blocking
                            }
                        } catch (InterruptedException ie) {
                            throw new InterruptedIOException("Interrupted during read");
                        }
                    }
                } else if (_readyDataBlocks.isEmpty()) {
                    return totalRead;
                } else {
                    ByteArray cur = _readyDataBlocks.get(0);
                    int available = cur.getValid() - _readyDataBlockIndex;
                    int toRead = Math.min(available, length - totalRead);

                    System.arraycopy(
                        cur.getData(), cur.getOffset() + _readyDataBlockIndex,
                        target, offset + totalRead,
                        toRead
                    );

                    _readyDataBlockIndex += toRead;
                    totalRead += toRead;
                    _readTotal += toRead;

                    if (_readyDataBlockIndex >= cur.getValid()) {
                        _readyDataSize -= cur.getValid();
                        _readyDataBlockIndex = 0;
                        _readyDataBlocks.remove(0);
                    }

                    if (shouldDebug) {
                        _log.debug("Read " + toRead + " bytes; remaining in block: " + (cur.getValid() - _readyDataBlockIndex));
                    }
                }
            }
            return totalRead;
        }
    }

    /**
     * Returns an estimate of bytes readily available to read without blocking.
     *
     * @return number of bytes available for reading
     * @throws IOException if stream is closed or error occurred
     */
    @Override
    public int available() throws IOException {
        synchronized (_dataLock) {
            if (_locallyClosed.get()) {
                throw new IOException("Input stream closed");
            }
            throwAnyError();
            return Math.max(0, _readyDataSize - _readyDataBlockIndex);
        }
    }

    /**
     * Returns the current read timeout in milliseconds.
     * -1 means block indefinitely, 0 means non-blocking.
     *
     * @return read timeout in ms
     */
    public int getReadTimeout() {
        return _readTimeout;
    }

    /**
     * Sets the read timeout in milliseconds.
     * -1 means block indefinitely, 0 means non-blocking.
     *
     * @param timeout new read timeout
     */
    public void setReadTimeout(int timeout) {
        if (_log.shouldDebug()) {
            _log.debug("Changing read timeout from " + _readTimeout + " to " + timeout);
        }
        _readTimeout = timeout;
    }

    /**
     * Closes the stream, clears all buffered data, and marks the stream as locally closed.
     * Subsequent reads will throw IOException.
     */
    @Override
    public void close() {
        synchronized (_dataLock) {
            if (_log.shouldDebug()) {
                _log.debug("close(): ready blocks=" + _readyDataBlocks.size()
                        + ", not ready blocks=" + _notYetReadyBlocks.size()
                        + ", highest ready block=" + _highestReadyBlockId);
            }

            _readyDataBlocks.clear();
            _readyDataSize = 0;
            _notYetReadyBlocks.clear();
            _locallyClosed.set(true);
            _dataLock.notifyAll();
        }
    }

    /**
     * Records a stream error and marks the stream as locally closed.
     * Wakes up any waiting threads.
     *
     * @param ioe The IOException that occurred
     */
    void streamErrorOccurred(IOException ioe) {
        synchronized (_dataLock) {
            if (_streamError == null) {
                _streamError = ioe;
            }
            _locallyClosed.set(true);
            _dataLock.notifyAll();
        }
    }

    /**
     * Throws any stored stream error and clears it.
     *
     * @throws IOException if a stream error has occurred
     */
    private void throwAnyError() throws IOException {
        IOException error = _streamError;
        if (error != null) {
            _streamError = null;
            IOException wrapped = new IOException("Input stream error");
            wrapped.initCause(error);
            throw wrapped;
        }
    }
}