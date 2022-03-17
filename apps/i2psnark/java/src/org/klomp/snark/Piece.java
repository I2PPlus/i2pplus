package org.klomp.snark;

import java.util.HashSet;
import java.util.Set;

/**
 * This class is used solely by PeerCoordinator.
 * Caller must synchronize on many of these methods.
 */
class Piece implements Comparable<Piece> {

    private final int id;
    private final Set<PeerID> peers;
    /** @since 0.8.3 */
    private volatile Set<PeerID> requests;
    /** @since 0.8.1 */
    private int priority;

    public Piece(int id) {
        this.id = id;
        this.peers = new HashSet<PeerID>(I2PSnarkUtil.MAX_CONNECTIONS / 2);
        // defer creating requests to save memory
    }

    /**
     *  Highest priority first,
     *  then rarest first
     */
    public int compareTo(Piece op) {
        int pdiff = op.priority - this.priority;   // reverse
        if (pdiff != 0)
            return pdiff;
        return this.peers.size() - op.peers.size();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o instanceof Piece) {
            return this.id == ((Piece)o).id;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 31 * hash + this.id;
        return hash;
    }

    public int getId() { return this.id; }

    /** caller must synchronize */
    public boolean addPeer(Peer peer) { return this.peers.add(peer.getPeerID()); }

    /**
     * Caller must synchronize.
     * @return true if removed
     */
    public boolean removePeer(Peer peer) { return this.peers.remove(peer.getPeerID()); }

    /**
     * How many peers have this piece?
     * Caller must synchronize
     * @since 0.9.1
     */
    public int getPeerCount() {
        return this.peers.size();
    }

    /** caller must synchronize */
    public boolean isRequested() {
        return this.requests != null && !this.requests.isEmpty();
    }

    /**
     * Since 0.8.3, keep track of who is requesting here,
     * to avoid deadlocks from querying each peer.
     * Caller must synchronize
     */
    public void setRequested(Peer peer, boolean requested) {
        if (requested) {
            if (this.requests == null)
                this.requests = new HashSet<PeerID>(2);
            this.requests.add(peer.getPeerID());
        } else {
            if (this.requests != null)
                this.requests.remove(peer.getPeerID());
        }
    }

    /**
     * Is peer requesting this piece?
     * Caller must synchronize
     * @since 0.8.3
     */
    public boolean isRequestedBy(Peer peer) {
        return this.requests != null && this.requests.contains(peer.getPeerID());
    }

    /**
     * How many peers are requesting this piece?
     * Caller must synchronize
     * @since 0.8.3
     */
    public int getRequestCount() {
        return this.requests == null ? 0 : this.requests.size();
    }

    /**
     * Clear all knowledge of peers
     * Caller must synchronize
     * @since 0.9.3
     */
    public void clear() {
        peers.clear();
        if (requests != null)
            requests.clear();
    }

    /** @return default 0 @since 0.8.1 */
    public int getPriority() { return this.priority; }

    /** @since 0.8.1 */
    public void setPriority(int p) { this.priority = p; }

    /** @since 0.8.1 */
    public boolean isDisabled() { return this.priority < 0; }

    /** @since 0.8.1 */
    public void setDisabled() { this.priority = -1; }

    @Override
    public String toString() {
        return String.valueOf(id);
    }
}
