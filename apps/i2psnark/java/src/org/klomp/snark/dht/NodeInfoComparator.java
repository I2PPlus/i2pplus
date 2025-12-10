package org.klomp.snark.dht;

/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import java.io.Serializable;
import java.util.Comparator;
import net.i2p.crypto.SHA1Hash;
import net.i2p.data.DataHelper;

/**
 * Comparator for finding nodes closest to a target key in the DHT keyspace.
 *
 * <p>This comparator implements the Kademlia distance metric to determine which of two nodes is
 * closer to a target key (either an info hash or node ID). The distance is calculated using XOR
 * distance, which is the standard metric used in Kademlia-based DHTs.
 *
 * <p>The XOR distance metric has the important property that it forms a metric space, meaning it
 * satisfies the triangle inequality and other mathematical properties that make it suitable for
 * routing in distributed hash tables.
 *
 * <p>This comparator is primarily used for:<br>
 * - Finding the k closest nodes to a target info hash<br>
 * - Maintaining sorted lists of nodes by distance<br>
 * - Selecting the best nodes for DHT queries and responses
 *
 * @since 0.9.2
 * @author zzz
 */
class NodeInfoComparator implements Comparator<NodeInfo>, Serializable {
    /** The target key (info hash or node ID) to measure distances against */
    private final byte[] _base;

    /**
     * Creates a new comparator for measuring distance to the specified target key.
     *
     * @param h the target key (info hash or node ID) to compare distances against
     */
    public NodeInfoComparator(SHA1Hash h) {
        _base = h.getData();
    }

    /**
     * Compares two nodes based on their XOR distance to the target key.
     *
     * <p>The comparison calculates the XOR distance between each node's ID and the target key, then
     * compares these distances lexicographically. The node with the smaller XOR distance is
     * considered "closer" to the target.
     *
     * <p>This method implements the standard Kademlia distance metric used throughout the DHT for
     * routing and node selection.
     *
     * @param lhs the first node to compare
     * @param rhs the second node to compare
     * @return negative value if lhs is closer to target, positive if rhs is closer, 0 if equal
     *     distance
     */
    public int compare(NodeInfo lhs, NodeInfo rhs) {
        byte lhsDelta[] = DataHelper.xor(lhs.getNID().getData(), _base);
        byte rhsDelta[] = DataHelper.xor(rhs.getNID().getData(), _base);
        return DataHelper.compareTo(lhsDelta, rhsDelta);
    }
}
