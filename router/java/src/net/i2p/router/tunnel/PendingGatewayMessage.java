package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.util.CDQEntry;

/**
 *  Stores all the state for an unsent or partially-sent message
 *
 *  @since 0.9.3 refactored from TunnelGateway.Pending
 */
class PendingGatewayMessage implements CDQEntry {
    /** the destination router */
    protected final Hash _toRouter;
    /** the destination tunnel */
    protected final TunnelId _toTunnel;
    /** the message ID */
    protected final long _messageId;
    /** the message expiration */
    protected final long _expiration;
    /** raw unfragmented message data */
    protected final byte[] _remaining;
    /** index into the data to be sent */
    protected volatile int _offset;
    /** which fragment are we working on */
    protected int _fragmentNumber;
    /** when this message was created */
    protected final long _created;
    /** IDs of TunnelDataMessages this message was fragmented into */
    private List<Long> _messageIds;
    /** time enqueued in CDQ */
    private long _enqueueTime;

    /**
     * PendingGatewayMessage.
     */
    public PendingGatewayMessage(I2NPMessage message, Hash toRouter, TunnelId toTunnel) {
        _toRouter = toRouter;
        _toTunnel = toTunnel;
        _messageId = message.getUniqueId();
        _expiration = message.getMessageExpiration();
        _remaining = message.toByteArray();
        _created = System.currentTimeMillis();
    }

    /**
     *  The destination router.
     *  @return may be null
     */
    public Hash getToRouter() { return _toRouter; }

    /**
     *  The destination tunnel.
     *  @return may be null
     */
    public TunnelId getToTunnel() { return _toTunnel; }

    /**
     *  Message ID.
     *
     *  @return the message ID
     */
    public long getMessageId() { return _messageId; }

    /**
     *  Message expiration time.
     *
     *  @return the message expiration
     */
    public long getExpiration() { return _expiration; }

    /**
     *  The raw unfragmented message to send.
     *  @return the message data
     */
    public byte[] getData() { return _remaining; }

    /**
     *  The index into the data to be sent.
     *  @return the offset
     */
    public int getOffset() { return _offset; }

    /**
     *  Move the offset.
     *  @param offset the new offset value
     */
    public void setOffset(int offset) { _offset = offset; }

    /**
     *  Lifetime in milliseconds.
     *
     *  @return the lifetime in milliseconds
     */
    public long getLifetime() { return System.currentTimeMillis()-_created; }

    /**
     *  Which fragment are we working on (0 for the first fragment).
     *  @return the fragment number
     */
    public int getFragmentNumber() { return _fragmentNumber; }

    /** ok, fragment sent, increment what the next will be */
    public void incrementFragmentNumber() { _fragmentNumber++; }

    /**
     *  Add an ID to the list of the TunnelDataMssages this message was fragmented into.
     *  Unused except in notePreprocessing() calls for debugging
     *  @param id the message ID to add
     */
    public void addMessageId(long id) {
        synchronized (this) {
            if (_messageIds == null)
                _messageIds = new ArrayList<>();
            _messageIds.add(Long.valueOf(id));
        }
    }

    /**
     *  The IDs of the TunnelDataMssages this message was fragmented into.
     *  Unused except in notePreprocessing() calls for debugging
     *  @return non-null list of message IDs
     */
    public List<Long> getMessageIds() {
        synchronized (this) {
            if (_messageIds != null)
                return new ArrayList<>(_messageIds);
            else
                return new ArrayList<>();
        }
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void setEnqueueTime(long now) {
        _enqueueTime = now;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     * @return the enqueue time
     */
    public long getEnqueueTime() {
        return _enqueueTime;
    }

    /**
     *  For CDQ
     *  @since 0.9.3
     */
    public void drop() {
        // No-op - intentionally empty
    }

    /**
     * toString.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[MsgID ").append(_messageId).append("]");
        //buf.append(TunnelGateway.this.toString());
        if (_toRouter != null) {
            buf.append(" targeting [");
            buf.append(_toRouter.toBase64().substring(0,6) + "] ");
            if (_toTunnel != null)
                buf.append("\n* [TunnelID ").append(_toTunnel.getTunnelId() + "]: ");
        }
        if (_toTunnel == null)
            buf.append("\n* ");
        buf.append("Actual lifetime: ");
        buf.append(getLifetime()).append("ms");
        buf.append("; Potential lifetime: ");
        buf.append(_expiration - _created).append("ms");
        buf.append("; Size: ").append(_remaining.length);
        buf.append("; Offset: ").append(_offset);
        buf.append("; Frag: ").append(_fragmentNumber);
        return buf.toString();
    }
}
