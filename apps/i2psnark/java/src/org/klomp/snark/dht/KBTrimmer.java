package org.klomp.snark.dht;

import net.i2p.I2PAppContext;
import net.i2p.kademlia.KBucket;
import net.i2p.kademlia.KBucketTrimmer;

import java.util.Set;

/**
 * Kademlia bucket trimmer for DHT node management.
 *
 * <p>Implements a conservative bucket trimming strategy that removes stale nodes while preserving
 * recently active routing information. This trimmer follows Kademlia best practices by only
 * removing nodes that haven't been seen for an extended period, and only when the bucket has been
 * stable.
 *
 * <p>Trimming strategy:<br>
 * - Only removes nodes older than 15 minutes<br>
 * - Only trims if the bucket hasn't changed in the last 5 minutes<br>
 * - Prioritizes keeping recently active nodes in the routing table<br>
 * - Helps maintain DHT health by preventing premature eviction of good nodes
 *
 * <p>This conservative approach helps maintain DHT stability and prevents thrashing where nodes are
 * frequently added and removed from buckets.
 *
 * @since 0.9.2
 */
class KBTrimmer implements KBucketTrimmer<NID> {
    private final I2PAppContext _ctx;
    private final int _max;

    /** Minimum time (5 minutes) a bucket must be unchanged before trimming is allowed */
    private static final long MIN_BUCKET_AGE = 5 * 60 * 1000;

    /** Maximum age (15 minutes) for nodes before they become candidates for removal */
    private static final long MAX_NODE_AGE = 15 * 60 * 1000;

    /**
     * Creates a new bucket trimmer with the specified maximum bucket size.
     *
     * @param ctx the I2P application context for clock access
     * @param max the maximum number of entries allowed in a bucket
     */
    public KBTrimmer(I2PAppContext ctx, int max) {
        _ctx = ctx;
        _max = max;
    }

    /**
     * Attempts to trim a Kademlia bucket to make room for a new node.
     *
     * <p>This method implements a conservative trimming strategy that only removes stale nodes
     * under specific conditions. The trimming logic is designed to maintain DHT health by
     * preserving recently active nodes while allowing removal of nodes that are likely no longer
     * reachable.
     *
     * <p>Trimming conditions:<br>
     * 1. The bucket must not have changed in the last 5 minutes<br>
     * 2. Only nodes not seen in the last 15 minutes are candidates for removal<br>
     * 3. If no stale nodes are found, trimming only succeeds if bucket is under capacity
     *
     * @param kbucket the Kademlia bucket to trim
     * @param toAdd the node ID that will be added if trimming succeeds
     * @return true if trimming was successful and space is available for the new node, false
     *     otherwise
     */
    public boolean trim(KBucket<NID> kbucket, NID toAdd) {
        long now = _ctx.clock().now();
        if (kbucket.getLastChanged() > now - MIN_BUCKET_AGE) return false;
        Set<NID> entries = kbucket.getEntries();
        for (NID nid : entries) {
            if (nid.lastSeen() < now - MAX_NODE_AGE) {
                if (kbucket.remove(nid)) return true;
            }
        }
        return entries.size() < _max;
    }
}
