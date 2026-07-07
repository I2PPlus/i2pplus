package org.klomp.snark.dht;

/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1Hash;
import net.i2p.data.DataHelper;
import net.i2p.kademlia.KBucketSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * All the nodes we know about, stored as a mapping from node ID to a Destination and Port.
 *
 * <p>And a real Kademlia routing table, which stores node IDs only.
 *
 * @since 0.9.2
 * @author zzz
 */
class DHTNodes {

    private final I2PAppContext _context;
    private long _expireTime;
    private final Log _log;
    private final ConcurrentHashMap<NID, NodeInfo> _nodeMap;
    private final KBucketSet<NID> _kad;
    private volatile boolean _isRunning;

    /** stagger with other cleaners */
    private static final long CLEAN_TIME = (long) 117 * 1000;

    /** how long since last heard from do we delete - BEP 5 says 15 minutes */
    private static final long MAX_EXPIRE_TIME = 10 * (long) 60 * 1000;
    private static final long MIN_EXPIRE_TIME = 8 * (long) 60 * 1000;
    private static final long DELTA_EXPIRE_TIME = 3 * (long) 60 * 1000;
    private static final int MAX_PEERS = 500;

    /** Buckets older than this are refreshed - BEP 5 says 15 minutes */
    private static final long MAX_BUCKET_AGE = 15 * (long) 60 * 1000;
    private static final int KAD_K = 8;
    private static final int KAD_B = 1;

    /**
     * Create a new DHTNodes instance.
     *
     * @param ctx the I2P application context
     * @param me our own node ID
     */
    public DHTNodes(I2PAppContext ctx, NID me) {
        _context = ctx;
        _expireTime = MAX_EXPIRE_TIME;
        _log = _context.logManager().getLog(DHTNodes.class);
        _nodeMap = new ConcurrentHashMap<>();
        _kad = new KBucketSet<>(ctx, me, KAD_K, KAD_B, new KBTrimmer(ctx, KAD_K));
    }

    public void start() {
        _isRunning = true;
        new Cleaner();
    }

    public void stop() {
        clear();
        _isRunning = false;
    }

    // begin ConcurrentHashMap methods

    /**
     * @return known nodes, not total net size
     */
    public int size() {
        return _nodeMap.size();
    }

    /**
     * Clear all known nodes and the routing table.
     */
    public void clear() {
        _kad.clear();
        _nodeMap.clear();
    }

    /**
     * Get a node by its ID.
     *
     * @param nid the node ID to look up
     * @return the node info, or null if not found
     */
    public NodeInfo get(NID nid) {
        return _nodeMap.get(nid);
    }

    /**
     * @return the old value if present, else null
     */
    public NodeInfo putIfAbsent(NodeInfo nInfo) {
        NodeInfo rv = _nodeMap.putIfAbsent(nInfo.getNID(), nInfo);
        // ensure same object in both places
        if (rv != null) _kad.add(rv.getNID());
        else _kad.add(nInfo.getNID());
        return rv;
    }

    /**
     * Remove a node by its ID.
     *
     * @param nid the node ID to remove
     * @return the removed node info, or null if not found
     */
    public NodeInfo remove(NID nid) {
        _kad.remove(nid);
        return _nodeMap.remove(nid);
    }

    /**
     * Get all known nodes.
     *
     * @return collection of all known node infos
     */
    public Collection<NodeInfo> values() {
        return _nodeMap.values();
    }

    // end ConcurrentHashMap methods

    /**
     * Find the closest nodes to a given hash or node ID.
     *
     * @param h either an InfoHash or a NID
     * @param numWant the maximum number of nodes to return
     * @return list of closest node infos
     */
    public List<NodeInfo> findClosest(SHA1Hash h, int numWant) {
        NID key;
        if (h instanceof NID) key = (NID) h;
        else key = new NID(h.getData());
        List<NID> keys = _kad.getClosest(key, numWant);
        List<NodeInfo> rv = new ArrayList<>(keys.size());
        for (NID nid : keys) {
            NodeInfo ninfo = _nodeMap.get(nid);
            if (ninfo != null) rv.add(ninfo);
        }
        return rv;
    }

    /**
     * Get random keys to explore for DHT maintenance.
     *
     * @return list of node IDs representing buckets that need refreshing
     */
    public List<NID> getExploreKeys() {
        return _kad.getExploreKeys(MAX_BUCKET_AGE);
    }

    /**
     * Debug info, HTML formatted.
     *
     * @param buf the buffer to append HTML to
     */
    public void renderStatusHTML(StringBuilder buf) {
        buf.append(_kad.toString().replace("\n", "<br>\n"));
    }

    /** */
    private class Cleaner extends SimpleTimer2.TimedEvent {

        public Cleaner() {
            super(SimpleTimer2.getInstance(), 5 * CLEAN_TIME);
        }

        public void timeReached() {
            if (!_isRunning) return;
            long now = _context.clock().now();
            int peerCount = 0;
            for (Iterator<NodeInfo> iter = DHTNodes.this.values().iterator(); iter.hasNext(); ) {
                NodeInfo peer = iter.next();
                if (peer.lastSeen() < now - _expireTime) {
                    iter.remove();
                    _kad.remove(peer.getNID());
                } else {
                    peerCount++;
                }
            }

            if (peerCount > MAX_PEERS)
                _expireTime = Math.max(_expireTime - DELTA_EXPIRE_TIME, MIN_EXPIRE_TIME);
            else _expireTime = Math.min(_expireTime + DELTA_EXPIRE_TIME, MAX_EXPIRE_TIME);

            if (_log.shouldDebug())
                _log.debug(
                        "DHT storage cleaner done - now with "
                                + peerCount
                                + " / "
                                + MAX_PEERS
                                + " peers, "
                                + DataHelper.formatDuration(_expireTime)
                                + " expiration");

            schedule(CLEAN_TIME);
        }
    }
}
