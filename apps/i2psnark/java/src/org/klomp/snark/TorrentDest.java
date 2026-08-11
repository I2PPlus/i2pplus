package org.klomp.snark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Destination;

import org.klomp.snark.dht.InfoHash;
import org.klomp.snark.dht.KRPC;
import org.klomp.snark.dht.TorrentKRPC;

/**
 * Bundles the transient I2P destination, session, server socket, DHT instance, and UDP
 * tracker client for one torrent or for a pool of torrents, so that all of a torrent's
 * network activity goes out through its own identity. Created lazily when a torrent starts,
 * destroyed when its last torrent stops, and recreated by the pooling logic when a pool
 * index is reused. Not persisted; a new destination is generated on each start.
 *
 * @since 0.9.71+
 */
public class TorrentDest {

    private final I2PAppContext _context;
    /** Base64-encoded info hash of the first torrent assigned, for thread names and logs */
    private final String _key;
    /** -1 when dedicated to a single torrent, else the shared pool index */
    private final int _poolIndex;
    /** Sequential pool number for the "I2PSnark - Pool N" nickname, -1 when dedicated */
    private final int _poolNum;
    private final I2PSocketManager _mgr;
    private final I2PServerSocket _serverSocket;
    /** Session property carrying the pool's torrent names, for the /tunnels pool tooltip */
    public static final String PROP_POOL_MEMBERS = "i2psnark.poolMembers";

    /** Assigned torrents by Base64-encoded info hash key, guarded by synchronized methods. */
    private final Map<String, InfoHash> _torrents = new HashMap<>(1);
    /** Assigned torrent names by Base64-encoded info hash key, for the pool tooltip. */
    private final Map<String, String> _names = new HashMap<>(1);
    private TorrentKRPC _dht;
    private UDPTrackerClient _udpTracker;

    /**
     * Assign a torrent to this destination.
     *
     * @param key Base64 encoding of the first torrent's info hash
     * @param poolIndex the shared pool index, or -1 for a dedicated destination
     * @param poolNum the sequential pool number for the nickname, -1 when dedicated
     * @param mgr the socket manager of the new transient destination; must be connected
     * @param serverSocket the destination's server socket, or null
     */
    public TorrentDest(I2PAppContext ctx, String key, int poolIndex, int poolNum, I2PSocketManager mgr, I2PServerSocket serverSocket) {
        _context = ctx;
        _key = key;
        _poolIndex = poolIndex;
        _poolNum = poolNum;
        _mgr = mgr;
        _serverSocket = serverSocket;
    }

    /**
     * The Base64-encoded info hash of the first torrent assigned.
     *
     * @return the Base64-encoded info hash of the first torrent assigned
     */
    public String getKey() {
        return _key;
    }

    /**
     * The shared pool index, or -1 when dedicated to a single torrent.
     *
     * @return the shared pool index, or -1 when dedicated to a single torrent
     */
    public int getPoolIndex() {
        return _poolIndex;
    }

    /**
     * The sequential pool number shown in the tunnel nickname "I2PSnark - Pool
     * &lt;n&gt;", or -1 when dedicated to a single torrent.
     *
     * @return the pool number, or -1 when dedicated
     */
    public int getPoolNum() {
        return _poolNum;
    }

    public I2PSocketManager getSocketManager() {
        return _mgr;
    }

    public I2PServerSocket getServerSocket() {
        return _serverSocket;
    }

    /**
     * The destination, or null.
     *
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
     * Assign a torrent to this destination, serving its infohash on the DHT instance when
     * one already runs, and updating the pool tooltip and tunnel nickname. The nickname
     * changes only when the pool crosses the one-to-two torrent boundary; further members
     * keep the pool name until the pool reverts to a single torrent.
     *
     * @param key the Base64-encoded info hash key
     * @param ih the torrent's info hash
     * @param name the torrent name, shown in the pool tooltip
     */
    public synchronized void assign(String key, InfoHash ih, String name) {
        _torrents.put(key, ih);
        if (name != null) {
            _names.put(key, name);
        }
        if (_dht != null) {
            _dht.addServedTorrent(ih);
        }
        updatePoolProps();
    }

    /**
     * Remove a torrent from this destination, stopping its DHT serving when an instance
     * runs and updating the pool tooltip and tunnel nickname. The nickname reverts to the
     * remaining torrent's name when the pool drops back to a single torrent.
     *
     * @param key the Base64-encoded info hash key
     * @return true when no torrents remain assigned
     */
    public synchronized boolean unassign(String key) {
        InfoHash ih = _torrents.remove(key);
        _names.remove(key);
        if (ih != null && _dht != null) {
            _dht.removeServedTorrent(ih);
        }
        updatePoolProps();
        return _torrents.isEmpty();
    }

    /**
     * Push the pool's torrent names to the session's i2psnark.poolMembers property, which
     * the console reads to render the pool tooltip on the tunnels page. The property must
     * carry the inbound./outbound. prefix, because the router only stores prefixed session
     * options in the pool settings. Also sets the tunnel nickname: the single torrent's
     * name while one torrent is assigned, the "I2PSnark - Pool N" name once a second joins
     * and until the pool reverts to one. Dedicated destinations have no tooltip and skip
     * the update.
     */
    private void updatePoolProps() {
        if (_poolIndex < 0) {
            return;
        }
        I2PSession sess = _mgr.getSession();
        if (sess == null) {
            return;
        }
        StringBuilder buf = new StringBuilder(_names.size() * 16);
        for (String name : _names.values()) {
            if (buf.length() > 0) {
                buf.append('|');
            }
            buf.append(name.replace("|", " "));
        }
        Properties props = new Properties();
        props.setProperty("inbound." + PROP_POOL_MEMBERS, buf.toString());
        props.setProperty("outbound." + PROP_POOL_MEMBERS, buf.toString());
        String nick;
        if (_torrents.size() == 1 && !_names.isEmpty()) {
            nick = I2PSnarkUtil.getNickname(_names.values().iterator().next());
        } else if (_torrents.size() > 1) {
            nick = I2PSnarkUtil.getPoolNickname(_poolNum);
        } else {
            nick = null;
        }
        if (nick != null) {
            props.setProperty("inbound.nickname", nick);
            props.setProperty("outbound.nickname", nick);
        }
        sess.updateOptions(props);
    }

    /**
     * Lazily create the DHT instance on this destination's session, sharing the main
     * instance's routing table, tracker, and blacklist, serving tracker queries only for
     * the torrents assigned here.
     *
     * @param shared the main DHT instance, or null if the DHT is disabled
     * @return the DHT instance, or null if disabled
     */
    public synchronized TorrentKRPC getDHT(KRPC shared) {
        if (_dht == null && shared != null) {
            // Snapshot so a concurrent assign/unassign cannot disturb the registration loop
            _dht = new TorrentKRPC(_context, shared, _mgr.getSession(),
                                   new ArrayList<>(_torrents.values()));
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
     * Stop the DHT instance and UDP tracker client and destroy the destination. Call only
     * when no torrents remain assigned.
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
