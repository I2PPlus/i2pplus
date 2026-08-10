package org.klomp.snark.dht;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;

/**
 * A DHT instance for a single torrent, running on its own transient I2P session and
 * destination. Shares the main instance's routing table, tracker, and blacklist, but
 * generates its own NID, query ports, and token maps, so that tokens issued for one
 * destination can never authorize an announce from another, which would make remote
 * nodes store the wrong peer hash for a torrent.  Serves tracker queries only for its
 * own torrent's infohash, so probing this destination never reveals the other torrents
 * hosted on the router.
 *
 * @since 0.9.71+
 */
public class TorrentKRPC extends KRPC {

    /**
     * @param ctx application context
     * @param shared the main DHT instance, whose routing table, tracker, and blacklist are
     *            shared; must be started already
     * @param session the transient session of the torrent's destination
     * @param ih the torrent's infohash, the only infohash this instance serves
     */
    public TorrentKRPC(I2PAppContext ctx, KRPC shared, I2PSession session, InfoHash ih) {
        super(ctx, "i2psnark", session, shared);
        serveTorrent(ih);
        start();
    }

    /**
     * Registers the datagram listeners on this instance's own session and ports, without
     * loading the DHT file or starting the cleaner, explorer, tracker, and routing table,
     * which remain the job of the main instance.
     */
    @Override
    public synchronized void start() {
        if (_isRunning) {
            return;
        }
        _session.addMuxedSessionListener(this, I2PSession.PROTO_DATAGRAM_RAW, _rPort);
        _session.addMuxedSessionListener(this, I2PSession.PROTO_DATAGRAM, _qPort);
        _isRunning = true;
    }

    /**
     * Unregisters the datagram listeners, leaving the shared routing table, tracker, and
     * blacklist running for the main instance.
     */
    @Override
    public synchronized void stop() {
        if (!_isRunning) {
            return;
        }
        _isRunning = false;
        _session.removeListener(I2PSession.PROTO_DATAGRAM, _qPort);
        _session.removeListener(I2PSession.PROTO_DATAGRAM_RAW, _rPort);
    }
}
