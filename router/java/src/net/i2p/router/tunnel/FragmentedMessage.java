package net.i2p.router.tunnel;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Gather fragments of I2NPMessages at a tunnel endpoint, making them available
 * for reading when complete.
 * <p>
 * Warning - this is all unsynchronized here - receivers must implement synchronization.
 *
 */
class FragmentedMessage {
    /** The app context. */
    private final I2PAppContext _context;
    /** Logger for the fragmented message. */
    private final Log _log;
    /** Id of the message being reassembled. */
    private final long _messageId;
    /** The router the message goes to. */
    private Hash _toRouter;
    /** The tunnel the message goes through. */
    private TunnelId _toTunnel;
    /** The fragments received so far. */
    private final ByteArray[] _fragments;
    /** True once the final fragment arrived. */
    private volatile boolean _lastReceived;
    /** Highest fragment number received. */
    private volatile int _highFragmentNum;
    /** When the reassembly started. */
    private final long _createdOn;
    /** True once all fragments are in. */
    private volatile boolean _completed;
    /** When the reassembly is dropped if incomplete. */
    private volatile long _releasedAfter;
    /** Timer dropping the incomplete reassembly. */
    private SimpleTimer2.TimedEvent _expireEvent;

    /** Cache supplying the fragment buffers. */
    private static final ByteCache _cache = ByteCache.getInstance(512, TrivialPreprocessor.PREPROCESSED_SIZE);
    /** Most fragments in one message, even 32 is unlikely. */
    private static final int MAX_FRAGMENTS = 64;
    /** Largest fragment, in bytes. */
    private static final int MAX_FRAGMENT_SIZE = 996;

    /**
     * Starts an empty reassembly buffer for one message id.
     *
     * @param ctx the context supplying the clock and log used by this message
     * @param messageId the id that ties the fragments of one message together
     */
    public FragmentedMessage(I2PAppContext ctx, long messageId) {
        _context = ctx;
        _log = ctx.logManager().getLog(FragmentedMessage.class);
        _messageId = messageId;
        _fragments = new ByteArray[MAX_FRAGMENTS];
        _highFragmentNum = -1;
        _releasedAfter = -1;
        _createdOn = ctx.clock().now();
    }

    /**
     * Receive a followup fragment, though one of these may arrive at the endpoint
     * prior to the fragment # 0.
     *
     * @param fragmentNum sequence number within the message (1 - 63)
     * @param payload data for the fragment non-null
     * @param offset index into the payload where the fragment data starts (past headers/etc)
     * @param length how much past the offset should we snag?
     * @param isLast is this the last fragment in the message?
     * @return true if the fragment was stored, false if the number, the length or
     *         the offset range was out of bounds
     */
    public boolean receive(int fragmentNum, byte[] payload, int offset, int length, boolean isLast) {
        if (fragmentNum <= 0 || fragmentNum >= MAX_FRAGMENTS) {
            if (_log.shouldWarn())
                _log.warn("Bad followon fragment # == " + fragmentNum + " for messageId " + _messageId);
            return false;
        }
        if (length <= 0 || length > MAX_FRAGMENT_SIZE) {
            if (_log.shouldWarn())
                _log.warn("Length is impossible (" + length + ") for messageId " + _messageId);
            return false;
        }
        if (offset + length > payload.length) {
            if (_log.shouldWarn())
                _log.warn("Length is impossible (" + length + "/" + offset + " out of " + payload.length + ") for [MsgID " + _messageId + "]");
            return false;
        }
        if (_log.shouldDebug())
            _log.debug("Received [MsgID " + _messageId + "] fragment " + fragmentNum +
                       " with " + length + " bytes" + (isLast ? " (last)" : "") +
                       " * Offset: " + offset);
        // we should just use payload[] and use an offset/length on it
        ByteArray ba = _cache.acquire(); //new ByteArray(payload, offset, length); //new byte[length]);
        System.arraycopy(payload, offset, ba.getData(), 0, length);
        ba.setValid(length);
        ba.setOffset(0);
        _fragments[fragmentNum] = ba;
        _lastReceived = _lastReceived || isLast;
        if (fragmentNum > _highFragmentNum)
            _highFragmentNum = fragmentNum;
        return true;
    }

    /**
     * Receive the first fragment (#0) and related metadata.  This may not be the first
     * one to arrive at the endpoint however.
     *
     * @param payload data for the fragment non-null
     * @param offset index into the payload where the fragment data starts (past headers/etc)
     * @param length how much past the offset should we snag?
     * @param isLast is this the last fragment in the message?
     * @param toRouter what router is this destined for (may be null)
     * @param toTunnel what tunnel is this destined for (may be null)
     * @return true if the fragment was stored, false if the length or the offset
     *         range was out of bounds
     */
    public boolean receive(byte[] payload, int offset, int length, boolean isLast, Hash toRouter, TunnelId toTunnel) {
        if (length <= 0 || length > MAX_FRAGMENT_SIZE) {
            if (_log.shouldWarn())
                _log.warn("Invalid length (" + length + ") for [MsgID " + _messageId + "]");
            return false;
        }
        if (offset + length > payload.length) {
            if (_log.shouldWarn())
                _log.warn("Invalid length (" + length + "/" + offset + " out of " + payload.length + ") for [MsgID " + _messageId + "]");
            return false;
        }
        if (_log.shouldDebug())
            _log.debug("Receiving " + (isLast ? "last ": "") + length + " bytes message [MsgID " + _messageId + "]" +
                       (toRouter != null ? "\n* Target Router: [" + toRouter.toBase64().substring(0,6) + "]" : "") +
                       " on tunnel: " + toTunnel + " with " + offset + " bytes offset");
        ByteArray ba = _cache.acquire(); // new ByteArray(payload, offset, length); // new byte[length]);
        System.arraycopy(payload, offset, ba.getData(), 0, length);
        ba.setValid(length);
        ba.setOffset(0);
        _fragments[0] = ba;
        _lastReceived = _lastReceived || isLast;
        _toRouter = toRouter;
        _toTunnel = toTunnel;
        if (_highFragmentNum < 0)
            _highFragmentNum = 0;
        return true;
    }

    /**
     * The unique ID of this fragmented message.
     *
     * @return the message id
     */
    public long getMessageId() { return _messageId; }
    /**
     * The router this message is being sent to.
     *
     * @return the target router
     */
    public Hash getTargetRouter() { return _toRouter; }
    /**
     * The tunnel this message is being sent to.
     *
     * @return the target tunnel
     */
    public TunnelId getTargetTunnel() { return _toTunnel; }

    /**
     * The number of fragments received so far.
     *
     * @return the fragment count
     */
    public int getFragmentCount() {
        return countFragments(_fragments, _highFragmentNum);
    }

    /**
     * Count the non-null fragment slots from 0 through the high-water mark.
     * <p>
     * Slots above {@code highFragmentNum} cannot be non-null: both
     * {@link #receive} overloads raise the mark to at least the index they store
     * into, and the mark is never lowered, so scanning past it cannot add to the
     * count. Bounding the scan therefore costs nothing in accuracy while
     * skipping the unused tail of the array, and it makes this accessor agree
     * with {@link #isComplete}, {@link #getCompleteSize},
     * {@link #writeComplete} and {@link #releaseFragments}, all of which already
     * stop at the mark.
     * <p>
     * Static and driven by its parameters so the scan can be exercised without
     * an I2PAppContext. The high-water mark is passed in rather than read in the
     * loop condition: it is only ever increased, and passing it as an argument
     * reads the volatile field exactly once instead of on every iteration.
     *
     * @param fragments fragment slots, indexed by fragment number; not null
     * @param highFragmentNum highest fragment number received, or -1 if none received
     * @return the number of non-null slots in 0..highFragmentNum, zero if none received
     * @throws NullPointerException if {@code fragments} is null and the range to scan is non-empty
     * @since 0.9.71+
     */
    static int countFragments(ByteArray[] fragments, int highFragmentNum) {
        int found = 0;
        for (int i = 0; i <= highFragmentNum; i++)
            if (fragments[i] != null)
                found++;
        return found;
    }

    /**
     * Used in the fragment handler so we can cancel the expire event on success.
     *
     * @return the scheduled expiry event, or null if none has been set
     */
    public SimpleTimer2.TimedEvent getExpireEvent() { return _expireEvent; }

    /**
     * The expire event to cancel when this message completes.
     *
     * @param evt the timer event to cancel on completion
     */
    public void setExpireEvent(SimpleTimer2.TimedEvent evt) { _expireEvent = evt; }

    /**
     * Have we received all of the fragments?
     *
     * @return true if the last fragment has been seen and no slot up to the
     *         high-water mark is still empty
     */
    public boolean isComplete() {
        if (!_lastReceived)
            return false;
        for (int i = 0; i <= _highFragmentNum; i++)
            if (_fragments[i] == null)
                return false;
        return true;
    }
    /**
     * The size of the complete message, in bytes.
     *
     * @return the complete size
     */
    public int getCompleteSize() {
        if (!_lastReceived)
            throw new IllegalStateException("don't get the completed size when we're not complete!");
        if (_releasedAfter > 0) {
            RuntimeException e = new RuntimeException("use after free in FragmentedMessage");
            _log.error("FM completeSize()", e);
            throw e;
        }
        int size = 0;
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            // NPE seen here, root cause unknown
            if (ba == null)
                throw new IllegalStateException("don't get the completed size when we're not complete! - null fragment i=" + i + " of " + _highFragmentNum);
            size += ba.getValid();
        }
        return size;
    }

    /**
     * How long has this fragmented message been alive?
     *
     * @return the milliseconds elapsed since construction
     */
    public long getLifetime() { return _context.clock().now() - _createdOn; }
    /**
     * Whether this message has been released after completion.
     *
     * @return true once every fragment has been written out to the caller's buffer, after
     *         which this message must not be read again
     */
    public boolean getReleased() { return _completed; }

    /**
     * Writes the reassembled message to the buffer.
     *
     * @param target the buffer to write into
     * @param offset where to start writing
     */
    private void writeComplete(byte[] target, int offset) {
        if (_releasedAfter > 0) {
            RuntimeException e = new RuntimeException("use after free in FragmentedMessage");
            _log.error("FM writeComplete() 2", e);
            throw e;
        }
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            if (ba == null)
                throw new IllegalStateException("null fragment i=" + i + " of " + _highFragmentNum);
            System.arraycopy(ba.getData(), ba.getOffset(), target, offset, ba.getValid());
            offset += ba.getValid();
        }
        _completed = true;
    }

    /**
     * The complete reassembled message data, freeing the fragments.
     *
     * @return the concatenated fragment data, or null if the fragments have
     *         already been released
     */
    public byte[] toByteArray() {
        synchronized (this) {
            if (_releasedAfter > 0) return null;
            byte[] rv = new byte[getCompleteSize()];
            writeComplete(rv, 0);
            releaseFragments();
            return rv;
        }
    }

    /**
     * The lifetime at which the fragments were released, or 0 if not yet released.
     *
     * @return the released after
     */
    public synchronized long getReleasedAfter() { return _releasedAfter; }
    /**
     * Releases the fragments, marking the message as failed.
     */
    public void failed() {
        synchronized (this) {
            releaseFragments();
        }
    }

    /**
     * Called as one of the endpoints for the tunnel cache pipeline (see TunnelDataMessage).
     *
     */
    private void releaseFragments() {
        if (_releasedAfter > 0) {
            RuntimeException e = new RuntimeException("double free in FragmentedMessage");
            _log.error("FM releaseFragments()", e);
            throw e;
        }
        _releasedAfter = getLifetime();
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            if ( (ba != null) && (ba.getData().length == TrivialPreprocessor.PREPROCESSED_SIZE) ) {
                _cache.release(ba);
                _fragments[i] = null;
            }
        }
    }

    /**
     * The message ID, in the bracketed form the console log links.
     * <p>A caller that has its own sentence to lead with puts this on its first
     * line; the id is the handle every related log line is grepped by, so it
     * belongs beside the event, not on a continuation line of its own.
     *
     * @return the id, as {@code [MsgID 1234]}
     * @since 0.9.72+
     */
    String idString() {
        return "[MsgID " + _messageId + ']';
    }

    /**
     * Who the message was addressed to, for a caller that wants it on its first
     * line: the target router, and the tunnel it arrived on.
     * <p>One bracket pair per value, as the log linkifier requires.
     *
     * @return the target, e.g. {@code [Target: AbC123] [TunnelId 12345]}, or null
     *         if no target was recorded
     * @since 0.9.72+
     */
    String targetString() {
        if (_toRouter == null) {return null;}
        StringBuilder buf = new StringBuilder(40);
        buf.append("[Target: ").append(_toRouter.toBase64().substring(0, 6)).append(']');
        if (_toTunnel != null) {
            buf.append(" [TunnelId ").append(_toTunnel.getTunnelId()).append(']');
        }
        return buf.toString();
    }

    /**
     * The reassembly state: which fragments are held, how the message stands, and
     * how long it has been open.
     * <p>Read the fragment tally as the state at the moment of the call, not the
     * state the message was received in: {@link #toByteArray()} releases the
     * fragments it has assembled, so a caller that logs after consuming the
     * payload sees the slots empty. That is why the sites that drop a completed
     * message log this only at debug, and lead with the reason at warn.
     *
     * @return the state, as one {@code * }-separated line, with the target on a
     *         continuation line
     * @since 0.9.72+
     */
    String detailString() {
        StringBuilder buf = new StringBuilder(128);
        for (int i = 0; i <= _highFragmentNum; i++) {
            ByteArray ba = _fragments[i];
            if (ba != null) {
                buf.append("Fragment [").append(i).append("] ").append(ba.getValid()).append(" bytes * ");
            } else {
                buf.append("Fragment [").append(i).append("] missing * ");
            }
        }
        buf.append("Highest received: ").append(_highFragmentNum);
        buf.append(" * Last received: [").append(_lastReceived).append(']');
        buf.append(" * Lifetime: ").append(DataHelper.formatDuration(_context.clock().now() - _createdOn));
        String target = targetString();
        if (target != null) {
            buf.append("\n* Target: [").append(_toRouter.toBase64().substring(0, 6)).append(']');
            if (_toTunnel != null) {
                buf.append(" * TunnelId: ").append(_toTunnel.getTunnelId());
            }
        }
        if (_completed) {buf.append(" * Completed");}
        if (_releasedAfter > 0) {
            buf.append(" * Released after ").append(DataHelper.formatDuration(_releasedAfter));
        }
        return buf.toString();
    }

    /**
     * Debug description of the message ID and the received fragments, the ID on
     * its own line, for a caller that has no sentence of its own.
     */
    @Override
    public String toString() {
        return idString() + "\n* " + detailString();
    }
}
