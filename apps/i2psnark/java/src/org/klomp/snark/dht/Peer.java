package org.klomp.snark.dht;

/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.data.Hash;

/**
 * Represents a single peer participating in a specific torrent within the DHT tracker.
 *
 * <p>This class extends Hash to store the peer's identification (typically their destination hash)
 * along with metadata about their participation in the torrent. The DHT tracker maintains
 * collections of these peers to respond to peer discovery requests from other clients.
 *
 * <p>Each peer tracks when it was last seen and whether it's a seed (has the complete torrent).
 * This information is used to prioritize peers in responses and to maintain an accurate view of the
 * torrent's swarm health.
 *
 * @since 0.9.2
 * @author zzz
 */
class Peer extends Hash {

    /** Timestamp when this peer was last active in the torrent */
    private volatile long lastSeen;

    // todo we could pack this into the upper bit of lastSeen
    /** Flag indicating whether this peer has the complete torrent */
    private volatile boolean isSeed;

    /**
     * Creates a new peer with the specified identification data.
     *
     * @param data the peer's identification data (typically destination hash)
     */
    public Peer(byte[] data) {
        super(data);
    }

    /**
     * Returns the timestamp when this peer was last seen active in the torrent.
     *
     * @return the timestamp in milliseconds since epoch, or 0 if never seen
     */
    public long lastSeen() {
        return lastSeen;
    }

    /**
     * Updates the timestamp when this peer was last seen.
     *
     * @param now the current timestamp in milliseconds since epoch
     */
    public void setLastSeen(long now) {
        lastSeen = now;
    }

    /**
     * Returns whether this peer is a seed (has the complete torrent).
     *
     * <p>Seeds are prioritized differently in peer responses as they can provide the complete
     * torrent to leechers. This information is also used to calculate swarm health statistics.
     *
     * @return true if this peer has the complete torrent, false otherwise
     * @since 0.9.14
     */
    public boolean isSeed() {
        return isSeed;
    }

    /**
     * Sets whether this peer is a seed.
     *
     * @param isSeed true if this peer has the complete torrent, false otherwise
     * @since 0.9.14
     */
    public void setSeed(boolean isSeed) {
        this.isSeed = isSeed;
    }
}
