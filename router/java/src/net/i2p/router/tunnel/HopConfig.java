package net.i2p.router.tunnel;

import java.util.Date;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;

/**
 * Defines the general configuration for a hop in a tunnel.
 *
 */
public class HopConfig {
    private byte _receiveTunnelId[];
    private TunnelId _receiveTunnel;
    private Hash _receiveFrom;
    private byte _sendTunnelId[];
    private TunnelId _sendTunnel;
    private Hash _sendTo;
    private SessionKey _layerKey;
    private SessionKey _ivKey;
    private SessionKey _replyKey;
    private byte[] _replyIV;
    private long _creation;
    private long _expiration;
    //private Map _options;

    // these 4 were longs, let's save some space
    // 2 billion * 1KB / 10 minutes = 3 GBps in a single tunnel
    // we use synchronization instead of an AtomicInteger here to save space
    private int _messagesProcessed;
    private int _oldMessagesProcessed;
    //private int _messagesSent;
    //private int _oldMessagesSent;

    /** IV length for {@link #getReplyIV} */
    public static final int REPLY_IV_LENGTH = 16;

    public HopConfig() {
        _creation = -1;
        _expiration = -1;
    }

    /** what tunnel ID are we receiving on? */
    public byte[] getReceiveTunnelId() { return _receiveTunnelId; }
    public TunnelId getReceiveTunnel() {
        if (_receiveTunnel == null)
            _receiveTunnel = getTunnel(_receiveTunnelId);
        return _receiveTunnel;
    }

    public void setReceiveTunnelId(byte id[]) { _receiveTunnelId = id; }
    public void setReceiveTunnelId(TunnelId id) { _receiveTunnelId = DataHelper.toLong(4, id.getTunnelId()); }

    /** what is the previous peer in the tunnel (null if gateway) */
    public Hash getReceiveFrom() { return _receiveFrom; }
    public void setReceiveFrom(Hash from) { _receiveFrom = from; }

    /** what is the next tunnel ID we are sending to? (null if endpoint) */
    public byte[] getSendTunnelId() { return _sendTunnelId; }

    /** what is the next tunnel we are sending to? (null if endpoint) */
    public TunnelId getSendTunnel() {
        if (_sendTunnel == null)
            _sendTunnel = getTunnel(_sendTunnelId);
        return _sendTunnel;
    }
    public void setSendTunnelId(byte id[]) { _sendTunnelId = id; }

    private static TunnelId getTunnel(byte id[]) {
        if (id == null)
            return null;
        else
            return new TunnelId(DataHelper.fromLong(id, 0, id.length));
    }

    /** what is the next peer in the tunnel (null if endpoint) */
    public Hash getSendTo() { return _sendTo; }
    public void setSendTo(Hash to) { _sendTo = to; }

    /** what key should we use to encrypt the layer before passing it on? */
    public SessionKey getLayerKey() { return _layerKey; }
    public void setLayerKey(SessionKey key) { _layerKey = key; }

    /** what key should we use to encrypt the preIV before passing it on? */
    public SessionKey getIVKey() { return _ivKey; }
    public void setIVKey(SessionKey key) { _ivKey = key; }

    /** key to encrypt the reply sent for the new tunnel creation crypto */
    public SessionKey getReplyKey() { return _replyKey; }
    public void setReplyKey(SessionKey key) { _replyKey = key; }

    /**
     *  IV used to encrypt the reply sent for the new tunnel creation crypto
     *
     *  @return 16 bytes
     */
    public byte[] getReplyIV() { return _replyIV; }

    /**
     *  IV used to encrypt the reply sent for the new tunnel creation crypto
     *
     *  @throws IllegalArgumentException if not 16 bytes
     */
    public void setReplyIV(byte[] iv) {
        if (iv.length != REPLY_IV_LENGTH)
            throw new IllegalArgumentException();
        _replyIV = iv;
    }

    /** when does this tunnel expire (in ms since the epoch)? */
    public long getExpiration() { return _expiration; }
    public void setExpiration(long when) { _expiration = when; }

    /** when was this tunnel created (in ms since the epoch)? */
    public long getCreation() { return _creation; }
    public void setCreation(long when) { _creation = when; }

    /**
     * what are the configuration options for this tunnel (if any).  keys to
     * this map should be strings and values should be Objects of an
     * option-specific type (e.g. "maxMessages" would be an Integer, "shouldPad"
     * would be a Boolean, etc).
     *
     */
    //public Map getOptions() { return _options; }
    //public void setOptions(Map options) { _options = options; }

    /**
     *  Take note of a message being pumped through this tunnel.
     *  "processed" is for incoming and "sent" is for outgoing (could be dropped in between)
     *  We use synchronization instead of an AtomicInteger here to save space.
     */
    public synchronized void incrementProcessedMessages() { _messagesProcessed++; }

    public synchronized int getProcessedMessagesCount() { return _messagesProcessed; }

    /**
     *  This returns the number of processed messages since
     *  the last time getAndResetRecentMessagesCount() was called.
     *  As of 0.9.23, does NOT reset the count, see getAndResetRecentMessagesCount().
     */
    public synchronized int getRecentMessagesCount() {
        return _messagesProcessed - _oldMessagesProcessed;
    }

    /**
     *  This returns the number of processed messages since the last time this was called,
     *  and resets the count. It should only be called by code that updates the router stats.
     *  See TunnelDispatcher.updateParticipatingStats().
     *
     *  @since 0.9.23
     */
    synchronized int getAndResetRecentMessagesCount() {
        int rv = _messagesProcessed - _oldMessagesProcessed;
        _oldMessagesProcessed = _messagesProcessed;
        return rv;
    }

    /**
     *  Take note of a message being pumped through this tunnel.
     *  "processed" is for incoming and "sent" is for outgoing (could be dropped in between)
     */
  /****
    public void incrementSentMessages() { _messagesSent++; }

    public int getSentMessagesCount() { return _messagesSent; }

    public int getRecentSentMessagesCount() {
        int rv = _messagesSent - _oldMessagesSent;
        _oldMessagesSent = _messagesSent;
        return rv;
    }
  ****/

    /** */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        if (_receiveTunnelId != null) {
            buf.append("\n* Receive on: [TunnelID ");
            buf.append(DataHelper.fromLong(_receiveTunnelId, 0, 4));
            buf.append("]");
        }

        if (_sendTo != null) {
            buf.append("\n* Send to: [").append(_sendTo.toBase64().substring(0,6)).append("]:");
            if (_sendTunnelId != null)
                buf.append(DataHelper.fromLong(_sendTunnelId, 0, 4));
        }

        buf.append("\n* Expires: ").append(new Date(_expiration));
        int messagesProcessed = getProcessedMessagesCount();
        if (messagesProcessed > 0)
            buf.append(" (used ").append(messagesProcessed).append("KB)");
        return buf.toString();
    }
}
