package net.i2p.router.tunnel;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;

/**
 * Defines the general configuration for a hop in a tunnel.
 *
 * This is used for both participating tunnels and tunnels we create.
 * Data only stored for tunnels we create should be in
 * TunnelCreatorConfig to save space.
 */
public class HopConfig {
    private TunnelId _receiveTunnel;
    private Hash _receiveFrom;
    private TunnelId _sendTunnel;
    private Hash _sendTo;
    private SessionKey _layerKey;
    private SessionKey _ivKey;
    private long _creation;
    private long _expiration;

    /*
     * These 4 were longs, let's save some space
     * 2 billion * 1KB / 10 minutes = 3 GBps in a single tunnel
     * we use synchronization instead of an AtomicInteger here to save space
     */
    private int _messagesProcessed;
    private int _oldMessagesProcessed;
    private volatile int _allocatedBW;

    /** Creates a new HopConfig with default values */
    public HopConfig() {
        _creation = -1;
        _expiration = -1;
    }

    /**
     * What tunnel ID are we receiving on? (0 if uninitialized)
     *
     * @return the receive tunnel ID or 0
     */
    public long getReceiveTunnelId() { return (_receiveTunnel != null) ? _receiveTunnel.getTunnelId() : 0; }

    /**
     * What tunnel ID are we receiving on? (null if uninitialized)
     *
     * @return the receive tunnel or null
     */
    public TunnelId getReceiveTunnel() {
        return _receiveTunnel;
    }

    /**
     * Set the receive tunnel ID
     *
     * @param id the tunnel ID
     */
    public void setReceiveTunnelId(TunnelId id) { _receiveTunnel = id; }

    /**
     * Set the receive tunnel ID.
     *
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     *  @since 0.9.48
     */
    public void setReceiveTunnelId(long id) { _receiveTunnel = new TunnelId(id); }

    /** what is the previous peer in the tunnel (null if gateway) */
    public Hash getReceiveFrom() { return _receiveFrom; }

    /**
     *  Do not set for gateway
     *
     *  @param from the previous peer hash
     */
    public void setReceiveFrom(Hash from) { _receiveFrom = from; }

    /**
     * What is the next tunnel ID we are sending to? (0 if endpoint)
     *
     * @return the send tunnel ID or 0
     */
    public long getSendTunnelId() { return (_sendTunnel != null) ? _sendTunnel.getTunnelId() : 0; }

    /**
     * What is the next tunnel ID we are sending to? (null if endpoint)
     *
     * @return the send tunnel or null
     */
    public TunnelId getSendTunnel() {
        return _sendTunnel;
    }

    /**
     * Set the send tunnel ID.
     *  Do not set for endpoint
     *
     *  @param id the tunnel ID
     *  @since 0.9.48
     */
    public void setSendTunnelId(TunnelId id) { _sendTunnel = id; }

    /**
     * Set the send tunnel ID.
     *  Do not set for endpoint
     *
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     *  @since 0.9.48
     */
    public void setSendTunnelId(long id) { _sendTunnel = new TunnelId(id); }

    /** what is the next peer in the tunnel (null if endpoint) */
    public Hash getSendTo() { return _sendTo; }

    /**
     *  Do not set for endpoint
     *
     *  @param to the next peer hash
     */
    public void setSendTo(Hash to) { _sendTo = to; }

    /** what key should we use to encrypt the layer before passing it on? */
    public SessionKey getLayerKey() { return _layerKey; }
    /** Set the layer encryption key */
    public void setLayerKey(SessionKey key) { _layerKey = key; }

    /** what key should we use to encrypt the preIV before passing it on? */
    public SessionKey getIVKey() { return _ivKey; }
    /** Set the IV encryption key */
    public void setIVKey(SessionKey key) { _ivKey = key; }

    /** when does this tunnel expire (in ms since the epoch)? */
    public long getExpiration() { return _expiration; }
    /** Set the tunnel expiration time */
    public void setExpiration(long when) { _expiration = when; }

    /** when was this tunnel created (in ms since the epoch)? */
    public long getCreation() { return _creation; }
    /** Set the tunnel creation time */
    public void setCreation(long when) { _creation = when; }

    /**
     * Get the allocated bandwidth for this hop.
     *
     *  @return Bps
     *  @since 0.9.66
     */
    public int getAllocatedBW() {
        return _allocatedBW;
    }
    /**
     * Set the allocated bandwidth for this hop.
     *
     *  @param bw Bps
     *  @since 0.9.66
     */
    public void setAllocatedBW(int bw) {
        _allocatedBW = bw;
    }

    /**
     *  Take note of a message being pumped through this tunnel.
     *  "processed" is for incoming and "sent" is for outgoing (could be dropped in between)
     *  We use synchronization instead of an AtomicInteger here to save space.
     */
    public synchronized void incrementProcessedMessages() { _messagesProcessed++; }

    /**
     *  Processed messages count.
     *
     *  @return the processed messages count
     */
    public synchronized int getProcessedMessagesCount() { return _messagesProcessed; }

    /**
     *  This returns the number of processed messages since
     *  the last time getAndResetRecentMessagesCount() was called.
     *  As of 0.9.23, does NOT reset the count, see getAndResetRecentMessagesCount().
     * @return the recent messages count
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

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append(_sendTo != null ? " to: [" + _sendTo.toBase64().substring(0,6) + "]" : "");
        int messagesProcessed = getProcessedMessagesCount();
        if (messagesProcessed > 0) {
            if (messagesProcessed > 1) {buf.append(" (").append(messagesProcessed).append(" messages processed)");}
            else {buf.append(" (").append(messagesProcessed).append(" message processed)");}
        }
        buf.append("\n* Expires: ").append(DataHelper.formatTime(_expiration));
        return buf.toString();
    }
}
