package org.klomp.snark.dht;

/*
 *  From zzzot, relicensed to GPLv2
 */

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe collection of all torrents tracked by the DHT.
 *
 * <p>This class extends ConcurrentHashMap to provide a thread-safe mapping from torrent info hash
 * to the collection of peers participating in each torrent. It represents the complete set of
 * torrents that the DHT tracker is currently tracking, along with all known peers for each torrent.
 *
 * <p>This is the top-level data structure for the DHT tracker functionality. When peers announce
 * themselves for torrents, they are added to the appropriate Peers collection within this
 * structure. When get_peer requests are received, this structure is queried to find the relevant
 * torrent and return its peers.
 *
 * <p>The concurrent implementation ensures thread safety when multiple DHT operations are accessing
 * or modifying torrent and peer data simultaneously, which is essential for a high-performance DHT
 * tracker.
 *
 * @since 0.9.2
 * @author zzz
 */
class Torrents extends ConcurrentHashMap<InfoHash, Peers> {

    /**
     * Creates a new torrent collection with default initial capacity.
     *
     * <p>Uses the default ConcurrentHashMap constructor which provides good performance
     * characteristics for most DHT tracking scenarios. The map will automatically resize as needed
     * to accommodate the number of tracked torrents.
     */
    public Torrents() {
        super();
    }
}
