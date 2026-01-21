package org.klomp.snark;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents a torrent piece and tracks which peers have it and are requesting it.
 *
 * <p>This class is used internally by PeerCoordinator to manage piece selection and distribution
 * strategies. It maintains:
 *
 * <ul>
 *   <li>The piece identifier within the torrent
 *   <li>Set of peers that have this piece available
 *   <li>Set of peers currently requesting this piece
 *   <li>Priority level for piece selection
 * </ul>
 *
 * <p>Pieces are sorted by priority (highest first) and then by rarity (fewest peers having it
 * first) to optimize download performance.
 *
 * <p><strong>Thread Safety:</strong> This class is not thread-safe. Callers must synchronize on
 * access to most methods.
 *
 * @since 0.1.0
 */
class Piece implements Comparable<Piece> {

    private final int id;
    private final Set<PeerID> peers;

    /**
     * @since 0.8.3
     */
    private volatile Set<PeerID> requests;

    /**
     * @since 0.8.1
     */
    private int priority;

    public Piece(int id) {
        this.id = id;
        this.peers = new HashSet<PeerID>(I2PSnarkUtil.MAX_CONNECTIONS / 2);
        // defer creating requests to save memory
    }

    /** Highest priority first, then rarest first */
    public int compareTo(Piece op) {
        int pdiff = op.priority - this.priority; // reverse
        if (pdiff != 0) return pdiff;
        return this.peers.size() - op.peers.size();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o instanceof Piece) {
            return this.id == ((Piece) o).id;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 31 * hash + this.id;
        return hash;
    }

    public int getId() {
        return this.id;
    }

    /** caller must synchronize */
    public boolean addPeer(Peer peer) {
        return this.peers.add(peer.getPeerID());
    }

    /**
     * Caller must synchronize.
     *
     * @return true if removed
     */
    public boolean removePeer(Peer peer) {
        return this.peers.remove(peer.getPeerID());
    }

    /**
     * How many peers have this piece? Caller must synchronize
     *
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
     * Marks a peer as requesting or no longer requesting this piece.
     * Used to avoid deadlocks when querying each peer.
     * Caller must synchronize.
     *
     * @param peer the peer requesting
     * @param requested true to mark as requested, false to unmark
     * @since 0.8.3
     */
    public void setRequested(Peer peer, boolean requested) {
        if (requested) {
            if (this.requests == null) this.requests = new HashSet<PeerID>(2);
            this.requests.add(peer.getPeerID());
        } else {
            if (this.requests != null) this.requests.remove(peer.getPeerID());
        }
    }

    /**
     * Checks if the given peer is currently requesting this piece.
     * Caller must synchronize.
     *
     * @param peer the peer to check
     * @return true if peer is requesting, false otherwise
     * @since 0.8.3
     */
    public boolean isRequestedBy(Peer peer) {
        return this.requests != null && this.requests.contains(peer.getPeerID());
    }

    /**
     * How many peers are requesting this piece? Caller must synchronize
     *
     * @since 0.8.3
     */
    public int getRequestCount() {
        return this.requests == null ? 0 : this.requests.size();
    }

    /**
     * Clear all knowledge of peers Caller must synchronize
     *
     * @since 0.9.3
     */
    public void clear() {
        peers.clear();
        if (requests != null) requests.clear();
    }

    /**
     * Gets the current priority level for this piece.
     *
     * @return priority value (negative values indicate disabled/skipped pieces)
     * @since 0.8.1
     */
    public int getPriority() {
        return this.priority;
    }

    /**
     * Sets the priority level for this piece.
     *
     * @param p priority value (negative to disable, 0 or positive for normal priority)
     * @since 0.8.1
     */
    public void setPriority(int p) {
        this.priority = p;
    }

    /**
     * Checks if this piece is disabled (should not be downloaded).
     *
     * @return true if disabled (priority < 0), false otherwise
     * @since 0.8.1
     */
    public boolean isDisabled() {
        return this.priority < 0;
    }

    /**
     * Disables this piece so it will not be downloaded.
     * Sets priority to -1.
     *
     * @since 0.8.1
     */
    public void setDisabled() {
        this.priority = -1;
    }

    @Override
    public String toString() {
        return String.valueOf(id);
    }
}
