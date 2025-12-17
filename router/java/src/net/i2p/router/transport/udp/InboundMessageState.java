package net.i2p.router.transport.udp;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.util.CDQEntry;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Hold the raw data fragments of an inbound message.
 */
class InboundMessageState implements CDQEntry {
    private final RouterContext _context;
    private final Log _log;
    private final long _messageId;
    private final Hash _from;
    private ByteArray _fragments[];
    private int _lastFragment;
    private final long _receiveBegin;
    private long _enqueueTime;
    private int _completeSize;
    private volatile boolean _released;
    private int _receivedCount;
    private final Object lock = new Object();

    private static final long MAX_RECEIVE_TIME = 10*1000;
    public static final int MAX_FRAGMENTS = 32;
    private static final int MAX_FRAGMENT_SIZE = UDPPacket.MAX_PACKET_SIZE;
    private static final ByteCache _fragmentCache = ByteCache.getInstance(8, MAX_FRAGMENT_SIZE);

    /**
     * Constructs a new InboundMessageState with no fragments.
     */
    public InboundMessageState(RouterContext ctx, long messageId, Hash from) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundMessageState.class);
        _messageId = messageId;
        _from = from;
        _fragments = new ByteArray[8]; // Start small, grow as needed
        _lastFragment = -1;
        _completeSize = -1;
        _receivedCount = 0;
        _receiveBegin = ctx.clock().now();
    }

    /**
     * Constructs and initializes by receiving one fragment.
     * @throws DataFormatException if fragment is invalid
     */
    public InboundMessageState(RouterContext ctx, long messageId, Hash from,
                               byte[] data, int off, int len, int fragmentNum, boolean isLast)
                               throws DataFormatException {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundMessageState.class);
        _messageId = messageId;
        _from = from;
        if (isLast) {
            if (fragmentNum > MAX_FRAGMENTS)
                throw new DataFormatException("corrupt - too many fragments: " + fragmentNum);
            int neededSize = fragmentNum + 1;
            _fragments = new ByteArray[Math.min(neededSize, MAX_FRAGMENTS)];
        } else {
            _fragments = new ByteArray[8]; // Start small, grow as needed
        }
        _lastFragment = -1;
        _completeSize = -1;
        _receivedCount = 0;
        _receiveBegin = ctx.clock().now();
        if (!receiveFragment(data, off, len, fragmentNum, isLast))
            throw new DataFormatException("corrupt");
    }

    /**
     * Receives and stores a fragment.
     * @return true if successful, false if corrupt or invalid
     */
    public boolean receiveFragment(byte[] data, int off, int len, int fragmentNum, boolean isLast) throws DataFormatException {
        synchronized(lock) {
            // Grow array if needed, but cap at MAX_FRAGMENTS
            if (fragmentNum >= _fragments.length) {
                if (fragmentNum >= MAX_FRAGMENTS) {
                    if (_log.shouldWarn())
                        _log.warn("Invalid fragment " + fragmentNum + '/' + MAX_FRAGMENTS);
                    return false;
                }
                // Grow the array to accommodate the fragment
                int newSize = Math.min(fragmentNum + 1, MAX_FRAGMENTS);
                _fragments = java.util.Arrays.copyOf(_fragments, newSize);
            }
            if (_fragments[fragmentNum] == null) {
                ByteArray message = _fragmentCache.acquire();
                System.arraycopy(data, off, message.getData(), 0, len);
                message.setValid(len);
                _fragments[fragmentNum] = message;
                _receivedCount++;
                if (isLast) {
                    if (_lastFragment >= 0) {
                        if (_log.shouldWarn())
                            _log.warn("Multiple last fragments for message " + _messageId + " from " + _from);
                        return false;
                    }
                    _lastFragment = fragmentNum;
                } else if (_lastFragment >= 0 && fragmentNum >= _lastFragment) {
                    if (_log.shouldWarn())
                        _log.warn("Non-last fragment " + fragmentNum + " when last is " + _lastFragment + " for message " + _messageId + " from " + _from);
                    return false;
                }
                if (_log.shouldDebug())
                    _log.debug("New fragment " + fragmentNum + " for message " + _messageId
                               + ", size=" + len
                               + ", isLast=" + isLast);
            } else {
                if (_log.shouldDebug())
                    _log.debug("Received fragment " + fragmentNum + " for message " + _messageId
                               + " again, old size=" + _fragments[fragmentNum].getValid()
                               + " and new size=" + len);
            }
            return true;
        }
    }

    /**
     * Returns whether the specified fragment has been received.
     */
    public boolean hasFragment(int fragmentNum) {
        synchronized(lock) {
            if (fragmentNum >= _fragments.length)
                return false;
            return _fragments[fragmentNum] != null;
        }
    }

    /**
     * Returns true if all fragments up to last have been received.
     */
    public boolean isComplete() {
        synchronized(lock) {
            int last = _lastFragment;
            if (last < 0) return false;
            return _receivedCount == (last + 1);
        }
    }

    /**
     * Returns true if message has expired (received more than 10s ago).
     */
    public boolean isExpired() {
        return _context.clock().now() > _receiveBegin + MAX_RECEIVE_TIME;
    }

    /**
     * Returns the message lifetime in milliseconds.
     */
    public long getLifetime() {
        return _context.clock().now() - _receiveBegin;
    }

    /**
     * Sets enqueue time for queueing.
     */
    public void setEnqueueTime(long now) {
        synchronized(lock) {
            _enqueueTime = now;
        }
    }

    /**
     * Gets the enqueue time.
     */
    public long getEnqueueTime() {
        synchronized(lock) {
            return _enqueueTime;
        }
    }

    /**
     * Drops and releases resources.
     */
    public void drop() {
        releaseResources();
    }

    /**
     * Returns the Hash of the sender.
     */
    public Hash getFrom() { return _from; }

    /**
     * Returns the message ID.
     */
    public long getMessageId() { return _messageId; }

    /**
     * Returns the total size of the complete message in bytes.
     * @throws IllegalStateException if message incomplete or released
     */
    public int getCompleteSize() {
        synchronized(lock) {
            if (_completeSize < 0) {
                if (_lastFragment < 0)
                    throw new IllegalStateException("Last fragment not set");
                if (_released)
                    throw new IllegalStateException("SSU IMS 2 Use after free");
                int size = 0;
                for (int i = 0; i <= _lastFragment; i++) {
                    ByteArray frag = _fragments[i];
                    if (frag == null)
                        throw new IllegalStateException("null fragment " + i + '/' + _lastFragment);
                    size += frag.getValid();
                }
                _completeSize = size;
            }
            return _completeSize;
        }
    }

    /**
     * Creates a bitfield representing received fragments.
     */
    public ACKBitfield createACKBitfield() {
        synchronized(lock) {
            int last = _lastFragment;
            int sz = (last >= 0) ? last + 1 : _fragments.length;
            return new PartialBitfield(_messageId, _fragments, sz);
        }
    }

    private static final class PartialBitfield implements ACKBitfield {
        private final long _bitfieldMessageId;
        private final int _fragmentCount;
        private final int _ackCount;
        private final int _highestReceived;
        private final long _fragmentAcks;

        public PartialBitfield(long messageId, Object data[], int size) {
            if (size > MAX_FRAGMENTS)
                throw new IllegalArgumentException();
            _bitfieldMessageId = messageId;
            int ackCount = 0;
            int highestReceived = -1;
            long acks = 0;
            for (int i = 0; i < size; i++) {
                if (data[i] != null) {
                    acks |= mask(i);
                    ackCount++;
                    highestReceived = i;
                }
            }
            _fragmentAcks = acks;
            _fragmentCount = size;
            _ackCount = ackCount;
            _highestReceived = highestReceived;
        }

        private static long mask(int fragment) {
            return 1L << fragment;
        }

        public int fragmentCount() { return _fragmentCount; }
        public int ackCount() { return _ackCount; }
        public int highestReceived() { return _highestReceived; }
        public long getMessageId() { return _bitfieldMessageId; }

        public boolean received(int fragmentNum) {
            if (fragmentNum < 0 || fragmentNum > _highestReceived)
                return false;
            return (_fragmentAcks & mask(fragmentNum)) != 0;
        }

        public boolean receivedComplete() { return _ackCount == _fragmentCount; }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(64);
            buf.append("Outbound Partial ACK of [");
            buf.append(_bitfieldMessageId);
            buf.append("] Highest: ").append(_highestReceived);
            buf.append(" with ").append(_ackCount).append(" ACKs for fragments [");
            for (int i = 0; i <= _highestReceived; i++) {
                if (received(i))
                    buf.append(i).append(' ');
            }
            buf.append(" / ").append(_highestReceived + 1).append("]");
            return buf.toString();
        }
    }

    /**
     * Releases all cached fragments and marks this state as released.
     */
    public void releaseResources() {
        synchronized(lock) {
            _released = true;
            for (int i = 0; i < _fragments.length; i++) {
                if (_fragments[i] != null) {
                    _fragmentCache.release(_fragments[i]);
                    _fragments[i] = null;
                }
            }
        }
    }

    /**
     * Returns the array of fragments, throws if already released.
     */
    public ByteArray[] getFragments() {
        synchronized(lock) {
            if (_released) {
                RuntimeException e = new IllegalStateException("Use after free: " + _messageId);
                _log.error("SSU IMS", e);
                throw e;
            }
            return _fragments;
        }
    }

    /**
     * Returns number of fragments received or expected.
     */
    public int getFragmentCount() {
        synchronized(lock) {
            return _lastFragment + 1;
        }
    }

    /**
     * Returns a string summary of this message state.
     */
    @Override
    public String toString() {
        synchronized(lock) {
            StringBuilder buf = new StringBuilder(256);
            buf.append("\n* Inbound Message: ").append(_messageId);
            buf.append(" from [").append(_from.toString().substring(0,6)).append("]");
            if (isComplete()) {
                buf.append(" completely received with ");
                buf.append(_completeSize).append(" bytes in ");
                buf.append(_lastFragment + 1).append(" fragments");
            } else {
                for (int i = 0; i <= _lastFragment; i++) {
                    buf.append(" fragment ").append(i);
                    ByteArray ba = _fragments[i];
                    if (ba != null)
                        buf.append(": known at size ").append(ba.getValid());
                    else
                        buf.append(": unknown");
                }
            }
            buf.append(" (Lifetime: ").append(getLifetime()).append("ms)");
            return buf.toString();
        }
    }
}
