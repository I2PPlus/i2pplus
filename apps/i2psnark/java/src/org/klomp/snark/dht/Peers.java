package org.klomp.snark.dht;

/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.data.Hash;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe collection of all peers participating in a single torrent.
 *
 * <p>This class extends ConcurrentHashMap to provide a thread-safe mapping from peer identification
 * (Hash) to Peer objects. It represents the complete set of known peers for a specific torrent that
 * the DHT tracker is aware of.
 *
 * <p>The collection is used by the DHT tracker to respond to get_peer requests, providing peers
 * that are actively participating in the torrent. The concurrent implementation ensures thread
 * safety when multiple DHT operations are accessing or modifying the peer list simultaneously.
 *
 * <p>Each peer in the collection includes metadata such as last seen time and seed status, which
 * can be used to prioritize responses and maintain accurate swarm statistics.
 *
 * @since 0.9.2
 * @author zzz
 */
class Peers extends ConcurrentHashMap<Hash, Peer> {

    /**
     * Creates a new peer collection with initial capacity for 8 peers.
     *
     * <p>The initial capacity is set to 8 as a reasonable default for most torrents, balancing
     * memory usage with the need to avoid frequent resizing for small to medium-sized swarms.
     */
    public Peers() {
        super(8);
    }
}
