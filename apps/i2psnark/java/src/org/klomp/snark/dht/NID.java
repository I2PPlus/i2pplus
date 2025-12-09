package org.klomp.snark.dht;

/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.crypto.SHA1Hash;
import net.i2p.util.Clock;

/**
 * Node Identifier (NID) for Kademlia DHT operations.
 *
 * <p>A NID is a 20-byte SHA1 hash that uniquely identifies a node in the DHT keyspace. It serves as
 * the primary key for routing table entries and is used extensively throughout the DHT system for
 * node identification, distance calculations, and bucket placement.
 *
 * <p>Beyond the basic SHA1 hash functionality, this class tracks node health metrics including last
 * seen time and failure count. This information is crucial for maintaining a healthy routing table
 * by identifying and removing unreliable or unreachable nodes.
 *
 * <p>Key features:<br>
 * - Extends SHA1Hash for efficient storage and comparison<br>
 * - Tracks last seen time for node freshness assessment<br>
 * - Maintains failure count to detect unreachable nodes<br>
 * - Provides timeout detection after multiple consecutive failures<br>
 * - Used extensively as map keys throughout the DHT system
 *
 * <p>Failure handling allows up to 5 consecutive timeouts before marking a node as problematic,
 * providing resilience against temporary network issues while eventually removing persistently
 * unreachable nodes.
 *
 * @since 0.9.2
 * @author zzz
 */
public class NID extends SHA1Hash {

    /** Timestamp when this node was last successfully contacted */
    private long lastSeen;

    /** Number of consecutive timeouts or failures */
    private int fails;

    /** Maximum allowed consecutive failures before considering node problematic */
    private static final int MAX_FAILS = 5;

    /**
     * Creates a new empty NID.
     *
     * <p>Creates an NID with null data, typically used as a placeholder or for initialization
     * purposes where the actual NID value will be set later.
     */
    public NID() {
        super(null);
    }

    /**
     * Creates a new NID with the specified data.
     *
     * @param data the 20-byte SHA1 hash data for this node identifier
     */
    public NID(byte[] data) {
        super(data);
    }

    /**
     * Returns the timestamp when this node was last successfully seen.
     *
     * @return the timestamp in milliseconds since epoch, or 0 if never seen
     */
    public long lastSeen() {
        return lastSeen;
    }

    /**
     * Updates the last seen timestamp and resets failure count.
     *
     * <p>This method should be called whenever successful communication with the node occurs. It
     * updates the freshness metric and resets the failure counter, indicating the node is currently
     * reachable.
     */
    public void setLastSeen() {
        lastSeen = Clock.getInstance().now();
        fails = 0;
    }

    /**
     * Records a timeout and checks if the node should be considered problematic.
     *
     * <p>Increments the failure counter and returns whether the node has exceeded the maximum
     * allowed consecutive failures. This helps identify nodes that are consistently unreachable and
     * should be removed from the routing table.
     *
     * @return true if the node has exceeded the maximum allowed failures, false otherwise
     */
    public boolean timeout() {
        return ++fails > MAX_FAILS;
    }
}
