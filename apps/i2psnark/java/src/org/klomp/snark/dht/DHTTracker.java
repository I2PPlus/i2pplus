package org.klomp.snark.dht;

/*
 *  From zzzot, relicensed to GPLv2
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * The tracker stores peers, i.e. Dest hashes (not nodes).
 *
 * @since 0.9.2
 */
class DHTTracker {

    private final I2PAppContext _context;
    private final Torrents _torrents;

    /** How long built bloom filters are reused before being rebuilt. */
    private static final long FILTER_CACHE_TIME = 60 * 1000;

    /** Cached BEP 33 bloom filters per torrent, keyed by info hash. */
    private final Map<InfoHash, FilterCache> _filterCache = new HashMap<>(16);

    /** How long built bloom filters are reused before being rebuilt. */
    private final long _filterCacheTime;
    private long _expireTime;
    private final Log _log;
    private volatile boolean _isRunning;

    /** Not current; updated by the cleaner. */
    private int _peerCount;

    /** Not current; updated by the cleaner. */
    private int _torrentCount;

    /** Stagger with other cleaners. */
    private static final long CLEAN_TIME = (long) 199 * 1000;

    /** No guidance in BEP 5; Vuze is 8h. */
    private static final long MIN_EXPIRE_TIME = 5 * (long) 60 * 1000;
    private static final long DELTA_EXPIRE_TIME = 3 * (long) 60 * 1000;
    private static final int MAX_PEERS = 400;
    private static final int MAX_PEERS_PER_TORRENT = 60;
    private static final int ABSOLUTE_MAX_PER_TORRENT = MAX_PEERS_PER_TORRENT * 2;
    private static final int MAX_TORRENTS = 2000;

    /** Upper bound on cached filter entries; the swarm map holds up to {@link #MAX_TORRENTS}. */
    private static final int MAX_FILTER_CACHE = MAX_TORRENTS / 4;

    /**
     * The maximum expiration period, scaled by how many peers are known:
     * the fewer peers we know, the longer we keep them around.
     *
     * @param peerCount the number of peers currently known
     * @return the maximum expiration period in milliseconds
     */
    private static long getMaxExpireTime(int peerCount) {
        if (peerCount < 100) {
            return 4 * 60 * 60 * 1000L;         // 4 hours
        } else if (peerCount < 200) {
            return 2 * 60 * 60 * 1000L;         // 2 hours
        } else if (peerCount < 300) {
            return 60 * 60 * 1000L;             // 1 hour
        } else {
            return 30 * 60 * 1000L;             // 30 minutes
        }
    }

    /**
     * Create a tracker for the given context.
     *
     * @param ctx the app context
     */
    DHTTracker(I2PAppContext ctx) {
        this(ctx, FILTER_CACHE_TIME);
    }

    /**
     * @param ctx the app context
     * @param filterCacheTime how long built bloom filters are reused, milliseconds; for tests
     */
    DHTTracker(I2PAppContext ctx, long filterCacheTime) {
        _context = ctx;
        _torrents = new Torrents();
        _filterCacheTime = filterCacheTime;
        _expireTime = getMaxExpireTime(0);
        _log = _context.logManager().getLog(DHTTracker.class);
    }

    public void start() {
        _isRunning = true;
        new Cleaner();
    }

    /** Stop the tracker */
    void stop() {
        _torrents.clear();
        _isRunning = false;
    }

    /**
     * Announce a peer for a torrent.
     *
     * @param ih the info hash of the torrent
     * @param hash the peer's destination hash
     * @param isSeed true if the peer is a seed
     */
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
            // We don't update setLastSeen here so that the peer
            // will expire, allowing new peers to come in.
        }
    }

    /**
     * Remove a peer from a torrent's peer list.
     *
     * @param ih the info hash of the torrent
     * @param hash the peer's destination hash
     */
    void unannounce(InfoHash ih, Hash hash) {
        Peers peers = _torrents.get(ih);
        if (peers == null) return;
        peers.remove(hash);
    }

    /**
     * Fetch peers for a torrent.
     * Caller's responsibility to remove himself from the list.
     *
     * @param ih the info hash of the torrent
     * @param max maximum number of peers to return
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
        List<Hash> result = new ArrayList<>(Math.min(max, totalPeers));

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

    /**
     * Build BEP 33 bloom filters for a torrent, one for the stored seeds
     * and one for the stored peers, by inserting the 32-byte destination
     * hash of each peer into the appropriate filter.
     *
     * @param ih the info hash of the torrent
     * @return two-element array with the seeds filter first, or null if
     *         there are no entries for the torrent
     * @since 0.9.71+
     */
    BloomFilter[] getBloomFilters(InfoHash ih) {
        synchronized (_filterCache) {
            // Swarm lookup first so an emptied torrent stops serving stale filters immediately
            Peers peers = _torrents.get(ih);
            if (peers == null || peers.isEmpty()) {
                _filterCache.remove(ih);
                return null;
            }
            long now = System.currentTimeMillis();
            FilterCache cached = _filterCache.get(ih);
            if (cached != null && now - cached.created < _filterCacheTime) {
                return cached.filters;
            }
            BloomFilter seeds = new BloomFilter();
            BloomFilter nons = new BloomFilter();
            for (Peer peer : peers.values()) {
                byte[] data = peer.getData();
                if (peer.isSeed()) {
                    seeds.insert(data);
                } else {
                    nons.insert(data);
                }
            }
            // Bound the cache: drop everything stale, then everything if still over
            if (_filterCache.size() >= MAX_FILTER_CACHE) {
                Iterator<Map.Entry<InfoHash, FilterCache>> it = _filterCache.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<InfoHash, FilterCache> e = it.next();
                    if (now - e.getValue().created >= _filterCacheTime) {
                        it.remove();
                    }
                }
                if (_filterCache.size() >= MAX_FILTER_CACHE) {
                    _filterCache.clear();
                }
            }
            BloomFilter[] rv = new BloomFilter[] {seeds, nons};
            _filterCache.put(ih, new FilterCache(rv, now));
            return rv;
        }
    }

    /**
     * Bloom filters for a torrent, with the time they were built, so the per-query rebuild cost
     * is amortized and the filters are rebuilt once they age out.
     */
    private static class FilterCache {
        final BloomFilter[] filters;
        final long created;

        FilterCache(BloomFilter[] filters, long created) {
            this.filters = filters;
            this.created = created;
        }
    }

    /**
     * Debug info, HTML formatted.
     *
     * @param buf the buffer to append HTML to
     */
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
                    List<Peer> sortedPeers = new ArrayList<>(p.values());
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
            else _expireTime = Math.min(_expireTime + DELTA_EXPIRE_TIME, getMaxExpireTime(peerCount));

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
