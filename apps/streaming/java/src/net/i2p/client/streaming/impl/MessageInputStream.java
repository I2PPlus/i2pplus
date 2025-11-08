package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * InputStream implementation that accepts messages potentially arriving out of order
 * and presents the data in proper order for reading.
 *
 * The stream buffers incoming data blocks delivered out of order, reorders them
 * as missing sequence numbers arrive, and presents a continuous byte stream.
 * Backpressure and limiting are handled upstream in ConnectionPacketHandler.
 *
 * Data arrives via {@link #messageReceived(long, ByteArray)} with sequence IDs.
 * This stream manages reordering and buffering transparently to callers reading bytes.
 *
 * Thread safety:
 * - Synchronized on a private lock object for state changes and data access.
 * - Read and message receive methods block efficiently waiting for data arrival.
 *
 * Performance:
 * - Uses lists and maps optimized for expected message volumes.
 * - Avoids unnecessary copying by referencing ByteArray slices.
 *
 * Lifecycle:
 * - Closing via {@link #close()} clears buffered data and prevents further reads.
 * - EOF condition signaled by {@link #closeReceived()}
 *
 * Examples of usage:
 * I2PSession -> MessageHandler -> PacketHandler -> ConnectionPacketHandler -> MessageInputStream
 *
 */
class MessageInputStream extends InputStream {
    private static final int INITIAL_CAPACITY = 4;
    private static final int MIN_READY_BUFFERS = SystemVersion.isSlow() ? 128 : 1024;

    private final Log _log;
    private final List<ByteArray> _readyDataBlocks; // Ordered list of received consecutive data blocks ready for reading
    private int _readyDataBlockIndex;               // Index within first block of the next byte to read
    private long _highestReadyBlockId;              // Highest consecutive message ID fully received and ready
    private long _highestBlockId;                   // Highest message ID ever received (including out-of-order)

    // Buffer for out-of-order message blocks indexed by message ID
    private final Map<Long, ByteArray> _notYetReadyBlocks;

    private boolean _closeReceived; // EOF signal received (no more messages after current highest block)
    private boolean _locallyClosed; // Stream locally closed for reading (no more reads allowed)
    private int _readTimeout;       // Read timeout in milliseconds (-1 = block forever, 0 = nonblocking)
    private IOException _streamError;
    private long _readTotal;        // Total bytes read so far (for logging/debug)

    private final int _maxMessageSize; // Maximum allowed message block size
    private final int _maxWindowSize;  // Maximum allowed window size in blocks
    private final int _maxBufferSize;  // Maximum allowed total buffer size in bytes

    private final byte[] _oneByte = new byte[1];
    private final Object _dataLock;

    // Sentinel ByteArray used for out-of-order duplicates, no data.
    private static final ByteArray DUMMY_BA = new ByteArray(null);

    // Running total of available bytes in ready blocks
    private int _readyDataSize = 0;

    /**
     * Constructs the MessageInputStream with configured buffer parameters.
     *
     * @param ctx           Application context for logging
     * @param maxMessageSize Maximum allowed size of an individual message block
     * @param maxWindowSize  Maximum allowed number of messages in window
     * @param maxBufferSize  Maximum size in bytes allowed for buffered data
     */
    public MessageInputStream(I2PAppContext ctx, int maxMessageSize, int maxWindowSize, int maxBufferSize) {
        _log = ctx.logManager().getLog(MessageInputStream.class);
        _readyDataBlocks = new ArrayList<>(INITIAL_CAPACITY);
        _notYetReadyBlocks = new HashMap<>(INITIAL_CAPACITY);
        _highestReadyBlockId = -1;
        _highestBlockId = -1;
        _readTimeout = I2PSocketOptionsImpl.DEFAULT_READ_TIMEOUT;
        _dataLock = new Object();
        _maxMessageSize = maxMessageSize;
        _maxWindowSize = maxWindowSize;
        _maxBufferSize = maxBufferSize;
    }

    /**
     * Gets the highest consecutive block ID that has been fully received and marked ready.
     *
     * @return highest fully received consecutive block ID, or -1 if none
     */
    public long getHighestReadyBlockId() {
        synchronized (_dataLock) {
            return _highestReadyBlockId;
        }
    }

    /**
     * Gets the highest block ID received, regardless of order.
     *
     * @return highest block ID received, or -1 if none
     */
    public long getHighestBlockId() {
        synchronized (_dataLock) {
            return _highestBlockId;
        }
    }

    /**
     * Checks if the stream has been locally closed for reading.
     *
     * @return true if closed locally, false otherwise
     */
    public boolean isLocallyClosed() {
        synchronized (_dataLock) {
            return _locallyClosed;
        }
    }

    /**
     * Determines whether the stream can accept a new message based on buffer limits.
     *
     * Accepts zero-length or duplicate packets always.
     *
     * @param messageId   The message sequence ID
     * @param payloadSize Size of the incoming message payload
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

            if (_locallyClosed) {
                return _notYetReadyBlocks.containsKey(messageId);
            }

            if (messageId < MIN_READY_BUFFERS) {
                return true;
            }

            if ((_readyDataBlocks.size() + _notYetReadyBlocks.size()) * _maxMessageSize < _maxBufferSize) {
                return true;
            }

            if (_notYetReadyBlocks.containsKey(messageId)) {
                return true;
            }

            int available = _maxBufferSize - _readyDataSize;
            if (available <= 0) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping message " + messageId + ", buffer size exceeded: available=" + available);
                }
                _dataLock.notifyAll();
                return false;
            }

            int allowedBlocks = available / _maxMessageSize;
            if (messageId > _highestReadyBlockId + allowedBlocks) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping message " + messageId + ", exceeds allowed buffer blocks window");
                }
                _dataLock.notifyAll();
                return false;
            }

            if (_readyDataBlocks.size() >= 4 * _maxWindowSize) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping message " + messageId + ", too many ready blocks");
                }
                _dataLock.notifyAll();
                return false;
            }
        }
        return true;
    }

    /**
     * Fetches "holes" in the received block sequence as missing block IDs between
     * the highest ready block and highest received block.
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
     * @param packet PacketLocal instance for ACK/NACK update
     */
    public void updateAcks(PacketLocal packet) {
        if (packet.getSendStreamId() > 0 || !packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) {
            synchronized (_dataLock) {
                packet.setAckThrough(_highestBlockId);
                packet.setNacks(getNacks());
            }
        } else {
            // Do not send ACK 0 for retransmitted SYN packets
            packet.setAckThrough(-1);
        }
    }

    /**
     * Notifies the stream that no further messages will be received after current highest.
     * Buffered data remains accessible until read.
     */
    public void closeReceived() {
        synchronized (_dataLock) {
            if (_log.shouldDebug()) {
                StringBuilder sb = new StringBuilder(128);
                sb.append("Close received: ready blocks=").append(_readyDataBlocks.size())
                  .append(", not ready blocks=").append(_notYetReadyBlocks.size())
                  .append(", highest ready block=").append(_highestReadyBlockId);
                _log.debug(sb.toString(), new Exception("InputStream closed"));
            }
            _closeReceived = true;
            _dataLock.notifyAll();
        }
    }

    /**
     * Optional method to notify activity on the stream, wakes up waiting readers.
     */
    public void notifyActivity() {
        synchronized (_dataLock) {
            _dataLock.notifyAll();
        }
    }

    /**
     * Accepts a newly received message block.
     *
     * If the message is next in sequence, adds it to ready blocks,
     * then promotes any contiguous subsequent out-of-order messages.
     *
     * Out-of-order blocks are stored in a separate map for later promotion.
     *
     * @param messageId The sequence ID of the received message block
     * @param payload   The ByteArray payload associated; may be null for dups on closed streams
     * @return true if this is a new message block, false if duplicate
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

            if (_highestReadyBlockId + 1 == messageId) {
                if (!_locallyClosed && payload != null && payload.getValid() > 0) {
                    if (_log.shouldDebug()) {
                        _log.debug("Adding message ID " + messageId + " to ready blocks");
                    }
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
                if (_locallyClosed) {
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
     * Read a single byte from the stream.
     * Throws SocketTimeoutException on timeout.
     *
     * @return the next byte as an int in range [0,255], or -1 if EOF.
     * @throws IOException if stream is closed or other IO error occurs.
     */
    @Override
    public int read() throws IOException {
        int read = read(_oneByte, 0, 1);
        if (read <= 0) {
            return -1;
        }
        return _oneByte[0] & 0xff;
    }

    /**
     * Reads bytes into the given array, blocking up to configured read timeout.
     * Throws SocketTimeoutException on read timeout.
     *
     * @param target Target byte array buffer
     * @return number of bytes read, or -1 on EOF
     * @throws IOException on stream errors or interruptions
     */
    @Override
    public int read(byte[] target) throws IOException {
        return read(target, 0, target.length);
    }

    /**
     * Reads up to length bytes into target buffer at offset,
     * blocking up to read timeout if no data currently available.
     * Throws SocketTimeoutException on timeout.
     *
     * @param target Target byte buffer
     * @param offset Offset into buffer to start storing bytes
     * @param length Maximum number of bytes to read
     * @return number of bytes read, or -1 if EOF
     * @throws IOException on errors or interruptions
     */
    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        int readTimeout = _readTimeout;
        long expiration = (readTimeout > 0) ? System.currentTimeMillis() + readTimeout : -1;
        final boolean shouldDebug = _log.shouldDebug();

        synchronized (_dataLock) {
            if (_locallyClosed) {
                throw new IOException("Input stream closed");
            }
            throwAnyError();

            int totalRead = 0;

            while (totalRead < length) {
                if (_readyDataBlocks.isEmpty() && totalRead == 0) {
                    // Block until data available, timeout, or EOF condition
                    while (_readyDataBlocks.isEmpty()) {
                        if (_locallyClosed) {
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
                                if (shouldDebug) {
                                    _log.debug("Waiting indefinitely for data");
                                }
                                _dataLock.wait();
                                throwAnyError();
                            } else if (readTimeout > 0) {
                                if (shouldDebug) {
                                    _log.debug("Waiting up to " + readTimeout + "ms for data");
                                }
                                _dataLock.wait(readTimeout);
                                throwAnyError();
                            } else {
                                if (shouldDebug) {
                                    _log.debug("Nonblocking read returning zero bytes");
                                }
                                return 0;
                            }
                        } catch (InterruptedException ie) {
                            InterruptedIOException iioe = new InterruptedIOException("Interrupted read");
                            iioe.initCause(ie);
                            throw iioe;
                        }

                        if (_readyDataBlocks.isEmpty() && readTimeout > 0) {
                            long remaining = expiration - System.currentTimeMillis();
                            if (remaining <= 0) {
                                if (_log.shouldInfo()) {
                                    _log.info("Read timed out after " + _readTimeout + "ms");
                                }
                                throw new SocketTimeoutException();
                            }
                            readTimeout = (int) remaining;
                        }
                    }
                } else if (_readyDataBlocks.isEmpty()) {
                    if (shouldDebug) {
                        _log.debug("No ready data blocks available, returning " + totalRead);
                    }
                    return totalRead;
                } else {
                    ByteArray cur = _readyDataBlocks.get(0);
                    int available = cur.getValid() - _readyDataBlockIndex;
                    int toRead = Math.min(available, length - totalRead);

                    System.arraycopy(cur.getData(), cur.getOffset() + _readyDataBlockIndex, target, offset + totalRead, toRead);
                    _readyDataBlockIndex += toRead;
                    totalRead += toRead;
                    _readTotal += toRead;

                    if (_readyDataBlockIndex >= cur.getValid()) {
                        _readyDataBlockIndex = 0;
                        _readyDataSize -= cur.getValid();
                        _readyDataBlocks.remove(0);
                    }

                    if (shouldDebug) {
                        _log.debug("Read " + toRead + " bytes; readyDataBlockIndex=" + _readyDataBlockIndex
                                + ", readyBlocks=" + _readyDataBlocks.size()
                                + ", totalRead=" + _readTotal);
                    }
                }
            }
            return totalRead;
        }
    }

    /**
     * Returns an estimate of bytes readily available to read without blocking.
     *
     * @return number of bytes available in buffers
     * @throws IOException if stream closed or error occurred
     */
    @Override
    public int available() throws IOException {
        synchronized (_dataLock) {
            if (_locallyClosed) {
                throw new IOException("Input stream closed");
            }
            throwAnyError();
            return Math.max(0, _readyDataSize - _readyDataBlockIndex);
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
            if (_locallyClosed) {
                return 0;
            }
            return _readyDataSize - _readyDataBlockIndex;
        }
    }

    /**
     * How long a read() call should block.
     * If less than 0, block indefinitely.
     * If 0, do not block at all (nonblocking).
     *
     * @return read timeout in milliseconds, 0 for nonblocking, negative to block indefinitely
     */
    public int getReadTimeout() {
        return _readTimeout;
    }

    /**
     * Set how long a read() call should block.
     * If less than 0, block indefinitely.
     * If 0, do not block at all (nonblocking).
     *
     * @param timeout read timeout in milliseconds
     */
    public void setReadTimeout(int timeout) {
        if (_log.shouldDebug()) {
            _log.debug("Changing read timeout from " + _readTimeout + " to " + timeout + ": " + hashCode());
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
                StringBuilder sb = new StringBuilder(128);
                sb.append("close(): ready bytes=").append(_readyDataSize - _readyDataBlockIndex)
                  .append(", ready blocks=").append(_readyDataBlocks.size())
                  .append(", not ready blocks=").append(_notYetReadyBlocks.size())
                  .append(", highest ready block=").append(_highestReadyBlockId)
                  .append(", hashCode=").append(hashCode());
                _log.debug(sb.toString());
            }

            _readyDataBlocks.clear();
            _readyDataSize = 0;
            _notYetReadyBlocks.clear();

            _locallyClosed = true;
            _dataLock.notifyAll();
        }
    }

    /**
     * Records a stream error and locally closes the stream.
     * Wakes up any waiting readers.
     *
     * @param ioe The IOException that occurred
     */
    void streamErrorOccurred(IOException ioe) {
        synchronized (_dataLock) {
            if (_streamError == null) {
                _streamError = ioe;
            }
            _locallyClosed = true;
            _dataLock.notifyAll();
        }
    }

    /**
     * Throws any stored stream error and clears the error.
     *
     * @throws IOException if a stream error was recorded
     */
    private void throwAnyError() throws IOException {
        IOException ioe = _streamError;
        if (ioe != null) {
            _streamError = null;
            IOException wrapped = new IOException("Input stream error");
            wrapped.initCause(ioe);
            throw wrapped;
        }
    }
}