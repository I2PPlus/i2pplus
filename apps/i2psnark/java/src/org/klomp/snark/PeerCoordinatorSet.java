package org.klomp.snark;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.crypto.SHA1Hash;

/**
 * Thread-safe collection for managing PeerCoordinator instances across multiple torrents.
 *
 * <p>This class provides a centralized registry for PeerCoordinator objects, enabling the
 * multi-torrent functionality in I2PSnark. It is primarily used by:
 *
 * <ul>
 *   <li>PeerAcceptor - to route incoming connections to the correct coordinator
 *   <li>Snark instances - to register and unregister themselves
 *   <li>DHT and tracker systems - to find the appropriate coordinator for a torrent
 * </ul>
 *
 * <p>The set maps torrent info hashes to their corresponding PeerCoordinator instances, allowing
 * efficient lookup by torrent identifier. All operations are thread-safe through ConcurrentHashMap.
 *
 * <p>Each PeerCoordinator is automatically added when a torrent starts and removed when the torrent
 * stops, ensuring the set always reflects currently active torrents.
 *
 * @since 0.9.2
 */
class PeerCoordinatorSet implements Iterable<PeerCoordinator> {
    private final Map<SHA1Hash, PeerCoordinator> _coordinators;

    public PeerCoordinatorSet() {
        _coordinators = new ConcurrentHashMap<SHA1Hash, PeerCoordinator>();
    }

    public Iterator<PeerCoordinator> iterator() {
        return _coordinators.values().iterator();
    }

    public void add(PeerCoordinator coordinator) {
        _coordinators.put(new SHA1Hash(coordinator.getInfoHash()), coordinator);
    }

    public void remove(PeerCoordinator coordinator) {
        _coordinators.remove(new SHA1Hash(coordinator.getInfoHash()));
    }

    /**
     * @since 0.9.2
     */
    public PeerCoordinator get(byte[] infoHash) {
        return _coordinators.get(new SHA1Hash(infoHash));
    }
}
