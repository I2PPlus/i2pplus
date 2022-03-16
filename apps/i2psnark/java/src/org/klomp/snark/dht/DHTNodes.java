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
 *  All the nodes we know about, stored as a mapping from
 *  node ID to a Destination and Port.
 *
 *  And a real Kademlia routing table, which stores node IDs only.
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
    private static final long CLEAN_TIME = 187*1000;
    /** how long since last heard from do we delete  - BEP 5 says 15 minutes */
    private static final long MAX_EXPIRE_TIME = 30*60*1000;
    private static final long MIN_EXPIRE_TIME = 10*60*1000;
    private static final long DELTA_EXPIRE_TIME = 3*60*1000;
//    private static final int MAX_PEERS = 799;
    private static final int MAX_PEERS = 2000;
    /** Buckets older than this are refreshed - BEP 5 says 15 minutes */
    private static final long MAX_BUCKET_AGE = 15*60*1000;
    private static final int KAD_K = 8;
    private static final int KAD_B = 1;

    public DHTNodes(I2PAppContext ctx, NID me) {
        _context = ctx;
        _expireTime = MAX_EXPIRE_TIME;
        _log = _context.logManager().getLog(DHTNodes.class);
        _nodeMap = new ConcurrentHashMap<NID, NodeInfo>();
        _kad = new KBucketSet<NID>(ctx, me, KAD_K, KAD_B, new KBTrimmer(ctx, KAD_K));
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
     *  @return known nodes, not total net size
     */
    public int size() {
        return _nodeMap.size();
    }

    public void clear() {
        _kad.clear();
        _nodeMap.clear();
    }

    public NodeInfo get(NID nid) {
        return _nodeMap.get(nid);
    }

    /**
     *  @return the old value if present, else null
     */
    public NodeInfo putIfAbsent(NodeInfo nInfo) {
        NodeInfo rv = _nodeMap.putIfAbsent(nInfo.getNID(), nInfo);
        // ensure same object in both places
        if (rv != null)
            _kad.add(rv.getNID());
        else
            _kad.add(nInfo.getNID());
        return rv;
    }

    public NodeInfo remove(NID nid) {
        _kad.remove(nid);
        return _nodeMap.remove(nid);
    }

    public Collection<NodeInfo> values() {
        return _nodeMap.values();
    }

    // end ConcurrentHashMap methods

    /**
     *  DHT
     *  @param h either a InfoHash or a NID
     */
    public List<NodeInfo> findClosest(SHA1Hash h, int numWant) {
        NID key;
        if (h instanceof NID)
            key = (NID) h;
        else
            key = new NID(h.getData());
        List<NID> keys = _kad.getClosest(key, numWant);
        List<NodeInfo> rv = new ArrayList<NodeInfo>(keys.size());
        for (NID nid : keys) {
            NodeInfo ninfo = _nodeMap.get(nid);
            if (ninfo != null)
                rv.add(ninfo);
        }
        return rv;
    }

    /**
     *  DHT - get random keys to explore
     */
    public List<NID> getExploreKeys() {
        return _kad.getExploreKeys(MAX_BUCKET_AGE);
    }

    /**
     * Debug info, HTML formatted
     * @since 0.9.4
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
            if (!_isRunning)
                return;
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
            else
                _expireTime = Math.min(_expireTime + DELTA_EXPIRE_TIME, MAX_EXPIRE_TIME);

            if (_log.shouldDebug())
                _log.debug("DHT storage cleaner done - now with " +
                         peerCount + " / " + MAX_PEERS + " peers, " +
                         DataHelper.formatDuration(_expireTime) + " expiration");

            schedule(CLEAN_TIME);
        }
    }
}
