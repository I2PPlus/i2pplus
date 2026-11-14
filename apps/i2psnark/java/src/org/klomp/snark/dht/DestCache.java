package org.klomp.snark.dht;

import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 * The destination answers snark has already paid for, shared with the DHT.
 *
 * <p>A {@code .b32.i2p} destination is only a hash, and resolving it is a network-database
 * search that costs up to a minute of whichever thread asked. Snark's dial path already
 * keeps those answers, including the "nothing" answers, on a backoff. The DHT kept its own
 * copy on each {@link NodeInfo} and no negative answer at all, so a node whose destination
 * cannot be resolved was asked about again on every query aimed at it - 3,348 dropped
 * queries in a four hour window, each behind a fresh lookup - and the same destination
 * could be resolved twice over, once for the DHT and once for a connection.
 *
 * <p>Implemented by {@code I2PSnarkUtil}, which owns the cache; the DHT only reads and
 * extends it.
 *
 * @since 0.9.72+
 */
public interface DestCache {

    /**
     * The destination this hash resolved earlier, if that answer is still fresh.
     *
     * @param h the destination hash, null tolerated
     * @return the destination, or null if nothing resolved is cached
     */
    Destination cachedDest(Hash h);

    /**
     * How long this hash is still held as unresolvable. A node the router cannot resolve
     * is normally a node that has gone away, and the swarm keeps naming it for the life of
     * a torrent, so this is what tells the DHT not to spend another search on it.
     *
     * @param h the destination hash, null tolerated
     * @return the millis left before it is worth asking again, 0 to ask now
     */
    long destLookupQuiet(Hash h);

    /**
     * Records what a lookup answered, so the dial path and the DHT share one answer and
     * one backoff for one hash.
     *
     * @param h the hash that was asked about
     * @param dest the destination that was resolved, null if it did not resolve
     */
    void noteDest(Hash h, Destination dest);
}
