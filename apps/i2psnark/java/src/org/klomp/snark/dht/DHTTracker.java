package org.klomp.snark.dht;

/*
 *  From zzzot, relicensed to GPLv2
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * The tracker stores peers, i.e. Dest hashes (not nodes).
 *
 * @since 0.9.2
 * @author zzz
 */
class DHTTracker {

    private final I2PAppContext _context;
    private final Torrents _torrents;
    private long _expireTime;
    private final Log _log;
    private volatile boolean _isRunning;

    /** not current, updated by cleaner */
    private int _peerCount;

    /** not current, updated by cleaner */
    private int _torrentCount;

    /** stagger with other cleaners */
    private static final long CLEAN_TIME = 199 * 1000;

    /** no guidance in BEP 5; Vuze is 8h */
    private static final long MAX_EXPIRE_TIME = 10*60*1000;
    private static final long MIN_EXPIRE_TIME = 5*60*1000;
    private static final long DELTA_EXPIRE_TIME = 3 * 60 * 1000;
    private static final int MAX_PEERS = 400;
    private static final int MAX_PEERS_PER_TORRENT = 60;
    private static final int ABSOLUTE_MAX_PER_TORRENT = MAX_PEERS_PER_TORRENT * 2;
    private static final int MAX_TORRENTS = 2000;

    DHTTracker(I2PAppContext ctx) {
        _context = ctx;
        _torrents = new Torrents();
        _expireTime = MAX_EXPIRE_TIME;
        _log = _context.logManager().getLog(DHTTracker.class);
    }

    public void start() {
        _isRunning = true;
        new Cleaner();
    }

    void stop() {
        _torrents.clear();
        _isRunning = false;
    }

    void announce(InfoHash ih, Hash hash, boolean isSeed) {
        if (_log.shouldDebug()) _log.debug("Announce " + hash + " for " + ih);
        Peers peers = _torrents.get(ih);
        if (peers == null) {
            if (_torrents.size() >= MAX_TORRENTS) return;
            peers = new Peers();
            Peers peers2 = _torrents.putIfAbsent(ih, peers);
            if (peers2 != null) peers = peers2;
        }

        if (peers.size() < ABSOLUTE_MAX_PER_TORRENT) {
            Peer peer = new Peer(hash.getData());
            Peer peer2 = peers.putIfAbsent(peer, peer);
            if (peer2 != null) peer = peer2;
            peer.setLastSeen(_context.clock().now());
            // don't let false trump true, as not all sources know the seed status
            if (isSeed) peer.setSeed(true);
        } else {
            // We could update setLastSeen if he is already
            // in there, but that would tend to keep
            // the same set of peers.
            // So let it expire so new ones can come in.
            // Peer peer = peers.get(hash);
            // if (peer != null)
            //    peer.setLastSeen(_context.clock().now());
        }
    }

    void unannounce(InfoHash ih, Hash hash) {
        Peers peers = _torrents.get(ih);
        if (peers == null) return;
        peers.remove(hash);
    }

    /**
     * Caller's responsibility to remove himself from the list
     *
     * @param noSeeds true if we do not want seeds in the result
     * @return list or empty list (never null)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Hash> getPeers(InfoHash ih, int max, boolean noSeeds) {
        Peers peers = _torrents.get(ih);
        if (peers == null || max <= 0) return Collections.emptyList();

        int totalPeers = peers.size();
        if (totalPeers == 0) return Collections.emptyList();

        // Pre-size result list to avoid resizing
        List<Hash> result = new ArrayList<Hash>(Math.min(max, totalPeers));

        if (noSeeds) {
            // Filter out seeds while collecting up to max peers
            int collected = 0;
            for (Peer peer : peers.values()) {
                if (!peer.isSeed()) {
                    result.add(peer);
                    if (++collected >= max) break;
                }
            }
        } else {
            // Collect up to max peers, then shuffle if needed
            int collected = 0;
            for (Peer peer : peers.values()) {
                result.add(peer);
                if (++collected >= max) break;
            }

            // Shuffle only if we have more peers than requested and collected all available
            if (totalPeers > max && collected == max) {
                Collections.shuffle(result, _context.random());
            }
        }

        return result;
    }

    /** Debug info, HTML formatted */
    public void renderStatusHTML(StringBuilder buf) {
        String separator = " <span class=bullet>&nbsp;&bullet;&nbsp;</span> ";
        buf.append("<div class=debugStats>")
                .append("<span class=stat><b>DHT Torrents:</b> <span class=dbug>")
                .append(_torrentCount)
                .append("</span></span>")
                .append(separator)
                .append("<span class=stat><b>DHT Tracker Peers:</b> <span class=dbug>")
                .append(_peerCount)
                .append("</span></span>")
                .append(separator)
                .append("<span class=stat><b>Peer Expiration:</b> <span class=dbug>")
                .append(DataHelper.formatDuration(_expireTime))
                .append("</span></span>")
                .append(separator); // append blacklisted peers info here
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {

        public Cleaner() {
            super(SimpleTimer2.getInstance(), 2 * CLEAN_TIME);
        }

        public void timeReached() {
            if (!_isRunning) return;
            long now = _context.clock().now();
            int torrentCount = 0;
            int peerCount = 0;
            boolean tooMany = false;
            for (Iterator<Peers> iter = _torrents.values().iterator(); iter.hasNext(); ) {
                Peers p = iter.next();
                int recent = 0;
                for (Iterator<Peer> iterp = p.values().iterator(); iterp.hasNext(); ) {
                    Peer peer = iterp.next();
                    if (peer.lastSeen() < now - _expireTime) iterp.remove();
                    else {
                        recent++;
                        peerCount++;
                    }
                }
                if (recent > MAX_PEERS_PER_TORRENT) {
                    // Too many, remove oldest peers (LRU eviction)
                    // TODO per-torrent adjustable expiration?
                    List<Peer> sortedPeers = new ArrayList<Peer>(p.values());
                    Collections.sort(sortedPeers, new Comparator<Peer>() {
                        public int compare(Peer p1, Peer p2) {
                            return Long.compare(p1.lastSeen(), p2.lastSeen());
                        }
                    });

                    for (int i = 0; i < sortedPeers.size() && p.size() > MAX_PEERS_PER_TORRENT; i++) {
                        Peer oldest = sortedPeers.get(i);
                        p.remove(oldest);
                        peerCount--;
                    }
                    torrentCount++;
                    tooMany = true;
                } else if (recent <= 0) {
                    iter.remove();
                } else {
                    torrentCount++;
                }
            }

            if (peerCount > MAX_PEERS) tooMany = true;
            if (tooMany) _expireTime = Math.max(_expireTime - DELTA_EXPIRE_TIME, MIN_EXPIRE_TIME);
            else _expireTime = Math.min(_expireTime + DELTA_EXPIRE_TIME, MAX_EXPIRE_TIME);

            if (_log.shouldDebug())
                _log.debug(
                        "DHT tracker cleaner done, now with "
                                + torrentCount
                                + " torrents, "
                                + peerCount
                                + " peers, "
                                + DataHelper.formatDuration(_expireTime)
                                + " expiration");
            _peerCount = peerCount;
            _torrentCount = torrentCount;
            schedule(tooMany ? CLEAN_TIME / 3 : CLEAN_TIME);
        }
    }
}
