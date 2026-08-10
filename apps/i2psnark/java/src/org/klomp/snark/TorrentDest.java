package org.klomp.snark;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base64;
import net.i2p.data.Destination;

import org.klomp.snark.dht.InfoHash;
import org.klomp.snark.dht.KRPC;
import org.klomp.snark.dht.TorrentKRPC;

/**
 * Bundles the transient I2P destination, session, server socket, DHT instance, and UDP
 * tracker client for one torrent, so that all of a torrent's network activity goes out
 * through its own identity. Created lazily when a torrent starts and destroyed when it
 * stops. Not persisted; a new destination is generated on each start.
 *
 * @since 0.9.71+
 */
public class TorrentDest {

    private final I2PAppContext _context;
    private final String _key;
    private final I2PSocketManager _mgr;
    private final I2PServerSocket _serverSocket;
    private TorrentKRPC _dht;
    private UDPTrackerClient _udpTracker;

    /**
     * @param key Base64 encoding of the torrent's info hash, used as the map key
     * @param mgr the socket manager of the new transient destination; must be connected
     * @param serverSocket the destination's server socket, or null
     */
    public TorrentDest(I2PAppContext ctx, String key, I2PSocketManager mgr, I2PServerSocket serverSocket) {
        _context = ctx;
        _key = key;
        _mgr = mgr;
        _serverSocket = serverSocket;
    }

    /**
     * @return the Base64-encoded info hash key
     */
    public String getKey() {
        return _key;
    }

    public I2PSocketManager getSocketManager() {
        return _mgr;
    }

    public I2PServerSocket getServerSocket() {
        return _serverSocket;
    }

    /**
     * @return the destination, or null
     */
    public Destination getMyDestination() {
        I2PSession sess = _mgr.getSession();
        if (sess != null) {
            return sess.getMyDestination();
        }
        return null;
    }

    /**
     * Lazily create the per-torrent DHT instance on this destination's session, sharing
     * the main instance's routing table, tracker, and blacklist, serving tracker queries
     * only for this torrent's infohash.
     *
     * @param shared the main DHT instance, or null if the DHT is disabled
     * @return the DHT instance, or null if disabled
     */
    public synchronized TorrentKRPC getDHT(KRPC shared) {
        if (_dht == null && shared != null) {
            _dht = new TorrentKRPC(_context, shared, _mgr.getSession(),
                                   new InfoHash(Base64.decode(_key)));
        }
        return _dht;
    }

    /**
     * Lazily create the UDP tracker client on this destination's session.
     *
     * @param util the shared util, for destination lookups
     * @return the tracker client, or null if the session is gone
     */
    public synchronized UDPTrackerClient getUDPTracker(I2PSnarkUtil util) {
        if (_udpTracker == null) {
            I2PSession sess = _mgr.getSession();
            if (sess == null) {
                return null;
            }
            _udpTracker = new UDPTrackerClient(_context, sess, util);
            _udpTracker.start();
        }
        return _udpTracker;
    }

    /**
     * Stop the DHT instance and UDP tracker client and destroy the destination.
     */
    public synchronized void destroy() {
        if (_dht != null) {
            _dht.stop();
            _dht = null;
        }
        if (_udpTracker != null) {
            _udpTracker.stop();
            _udpTracker = null;
        }
        _mgr.destroySocketManager();
    }
}
