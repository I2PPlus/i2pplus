/* PeerCoordinator - Coordinates which peers do what (up and downloading).
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.ThreadNames;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;
import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;
import org.klomp.snark.dht.DHT;

/**
 * Coordinates peer activities for a single torrent including upload/download management,
 * peer selection, piece distribution, and bandwidth allocation.
 *
 * <p>This class manages the core BitTorrent coordination logic:
 *
 * <ul>
 * <li>Managing connected peers and their states</li>
 * <li>Choking/unchoking logic for upload slots</li>
 * <li>Piece selection and rarest-first distribution</li>
 * <li>Request queue management and pipeline control</li>
 * <li>Bandwidth distribution among peers</li>
 * <li>Peer Exchange (PEX) for peer discovery</li>
 * <li>DHT integration for trackerless operation</li>
 * <li>Tracker announcements and response processing</li>
 * </ul>
 *
 * <p>Thread safety: This class uses extensive synchronization for thread-safe peer management.
 * External access to some fields is documented where permitted.
 *
 * @see Peer
 * @see PeerState
 * @see Storage
 * @since 0.1.0
 */
class PeerCoordinator implements PeerListener, BandwidthListener {
    /** Logger for the peer coordinator. */
    private final Log _log;

    /**
     * Will be null when in magnet mode. Volatile: set in the constructor,
     * read by the peer and web threads that the coordinator starts later.
     */
    volatile MetaInfo metainfo;

    /** Will be null when in magnet mode. Volatile, see {@link #metainfo}. */
    volatile Storage storage;

    /** The download we coordinate peers for. */
    private final Snark snark;

    /** How often to recompute upload slots and UI download speeds. */
    static final long CHECK_PERIOD =
            (long) 5 * 1000; // update download speed in UI and choke/unchoke tests
    /** Maximum peers we upload to at once. */
    static final int MAX_UPLOADERS = 16;
    /**
     * Inactivity timeout before disconnecting idle peers.
     */
    public static final long MAX_INACTIVE =
            5 * (long) 60 * 1000; // how long before we disconnect from an inactive peer
    /**
     * Inactivity timeout before disconnecting idle seeds.
     */
    public static final long MAX_SEED_INACTIVE = 3 * (long) 60 * 1000;

    /**
     * Inactivity timeout before re-queuing a requested piece that is receiving no data.
     *
     * @since 0.9.71+
     */
    static final long PIECE_STALL_TIMEOUT = 5 * (long) 60 * 1000;

    /**
     * Approximation of the number of current uploaders (unchoked peers), whether interested or not.
     * Resynced by PeerChecker once in a while.
     */
    private final AtomicInteger uploaders = new AtomicInteger();

    /**
     * Approximation of the number of current uploaders (unchoked peers), that are interested.
     * Resynced by PeerChecker once in a while.
     */
    private final AtomicInteger interestedUploaders = new AtomicInteger();

    /** External use by {@link PeerCheckerTask} only. */
    private final AtomicInteger interestedAndChoking = new AtomicInteger();

    /** Bytes uploaded to peers. */
    private final AtomicLong uploaded = new AtomicLong();
    /** Bytes downloaded from peers. */
    private final AtomicLong downloaded = new AtomicLong();
    /** Number of samples in the upload/download rate windows. */
    static final int RATE_DEPTH = 3; // make following arrays RATE_DEPTH long
    /** Upload totals at the rate stat boundaries. */
    private final long[] uploaded_old = {-1, -1, -1};
    /** Download totals at the rate stat boundaries. */
    private final long[] downloaded_old = {-1, -1, -1};

    /**
     * Synchronize on this when changing peers or downloaders. This is a Queue, not a Set, because
     * PeerCheckerTask keeps things in order for choking/unchoking.
     */
    final Deque<Peer> peers;

    /** Peers we heard about via PEX. */
    private final Set<PeerID> pexPeers;

    /** Max distinct swarm peers remembered per torrent. */
    private static final int MAX_SWARM_PEERS = 512;

    /** How long a remembered swarm peer stays valid without resurfacing. */
    private static final long SWARM_PEER_TTL = 2 * 60 * 60 * 1000L;

    /** Distinct swarm peers, keyed by destination hash, value is the last-seen wall time. */
    private final Map<Hash, Long> swarmSeen = new HashMap<>(64);

    /** Estimate of the peers, without requiring any synchronization. */
    private volatile int peerCount;

    /** Timer to handle all periodical tasks. */
    private final CheckEvent timer;

    /** Timer re-requesting peers from the tracker. */
    private final SimpleTimer2.TimedEvent rerequestTimer;
    /** Lock for the re-request state. */
    private final Object rerequestLock = new Object();
    /** Whether the last tracker request was allowed. */
    private boolean wasRequestAllowed;
    /** True while a re-request is scheduled. */
    private boolean isRerequestScheduled;

    /** Our 20-byte peer id. */
    private final byte[] id;
    /** The torrent's info hash. */
    private final byte[] infohash;

    /**
     * The wanted pieces, in the order they are requested. A LinkedHashSet, not
     * an ArrayList: a piece is removed from the middle once per completed
     * piece, and a List remove() is an O(n) scan and shift under the monitor
     * the request path iterates - quadratic on a torrent with many pieces. The
     * set removes by hash in O(1) and leaves the remaining order alone, so the
     * rarest-first order survives a removal; it is rebuilt by
     * {@link #resortWanted} when {@link #wantedDirty} is set, and by
     * {@link #reshuffleWanted} to break id order. We could use a TreeSet, but
     * we would have to clear and re-add everything when priorities change.
     */
    private final Set<Piece> wantedPieces;

    /**
     * The total number of bytes in wantedPieces, or -1 if not yet known.
     * Bytes left to download for this torrent. Volatile: a long, written by
     * the checker and piece-completion paths and read by the web thread, so
     * without it a 32-bit JVM could show a torn value and no happens-before
     * exists for the other reads.
     *
     * @since 0.9.1
     */
    private volatile long wantedBytes;


    /** Pieces received but not yet complete. */
    private final List<PartialPiece> partialPieces;

    /**
     * Piece ids currently in partialPieces, for O(1) membership checks. Sync on wantedPieces.
     *
     * @since 0.9.68+
     */
    private final Set<Integer> partialIds = new HashSet<>(8);

    /**
     * Pieces being downloaded right now, by piece id. Unlike partialPieces, which holds
     * the orphans no peer is pulling any more, an entry here is live: it is registered
     * with every peer that is fetching a block of it, and it is the coordinator, not the
     * peers, that owns the data. That is what lets two peers fill complementary blocks of
     * one piece instead of each pulling a duplicate copy of the whole thing.
     *
     * <p>Sync on wantedPieces.
     *
     * @since 0.9.72+
     */
    private final Map<Integer, PartialPiece> _livePartials = new HashMap<>(8);

    /**
     * Index of wantedPieces by piece id, for O(1) lookups. Sync on wantedPieces.
     *
     * @since 0.9.68+
     */
    private final Map<Integer, Piece> wantedMap = new HashMap<>(64);

    /**
     * Whether wantedPieces needs resorting (rarest-first). Sync on wantedPieces.
     *
     * @since 0.9.68+
     */
    private boolean wantedDirty = true;

    /** True once the coordinator has halted. */
    private volatile boolean halted;

    /** Magnet fetch state, null for a normal torrent. */
    private final MagnetState magnetState;
    /** Listener for progress events. */
    private final CoordinatorListener listener;
    /** Listener for bandwidth events, may be null. */
    private final BandwidthListener bwListener;
    /** The plugin's shared utilities. */
    private final I2PSnarkUtil _util;
    /** Random source for peer and piece selection. */
    private final RandomSource _random;

    /** When torrent comments were last requested. */
    private final AtomicLong _commentsLastRequested = new AtomicLong();
    /** Peers that did not ask for comments. */
    private final AtomicInteger _commentsNotRequested = new AtomicInteger();
    /** Interval between comment requests. */
    private static final long COMMENT_REQ_INTERVAL = 12 * 60 * 60 * 1000L;
    /** Delay before the first comment request. */
    private static final long COMMENT_REQ_DELAY = 60 * 60 * 1000L;
    /** Non-requesting peers that trigger a request. */
    private static final int MAX_COMMENT_NOT_REQ = 10;

    /** Hostname to expire time. Sync on this. */
    private Map<String, Long> _webPeerBans;

    /** How long a bad web peer is banned. */
    private static final long WEBPEER_BAN_TIME = 30 * 60 * 1000L;

    /**
     * Create a peer coordinator for the torrent.
     *
     * @param util the context wrapper supplying the I2P app context, random source and clocks
     * @param id the 20 byte torrent hash the peers are matched against
     * @param infohash the torrent hash used for magnet and peer exchange lookups
     * @param metainfo null if in magnet mode
     * @param storage null if in magnet mode
     * @param listener notified as peers are added, dropped and completed
     * @param torrent the torrent this coordinator drives
     * @param bwl receives the byte totals reported by every peer connection
     */
    public PeerCoordinator(
            I2PSnarkUtil util,
            byte[] id,
            byte[] infohash,
            MetaInfo metainfo,
            Storage storage,
            CoordinatorListener listener,
            Snark torrent,
            BandwidthListener bwl) {
        _util = util;
        _random = util.getContext().random();
        _log = util.getContext().logManager().getLog(PeerCoordinator.class);
        this.id = id;
        this.infohash = infohash;
        this.metainfo = metainfo;
        this.storage = storage;
        this.listener = listener;
        this.snark = torrent;
        bwListener = bwl;

        wantedPieces = new LinkedHashSet<>();
        setWantedPieces();
        partialPieces = new ArrayList<>(getMaxConnections() + 1);
        peers = new LinkedBlockingDeque<>();
        magnetState = new MagnetState(infohash, metainfo);
        pexPeers = new ConcurrentHashSet<>();

        // Install a timer to check the uploaders.
        // Randomize the first start time so multiple tasks are spread out,
        // this will help the behavior with global limits
        timer = new CheckEvent(_util.getContext(), new PeerCheckerTask(_util, this));
        timer.schedule((CHECK_PERIOD * 8 / 2) + _random.nextInt((int) CHECK_PERIOD * 8));

        // NOT scheduled until needed
        rerequestTimer = new RerequestEvent();

        // we don't store the last-requested time, so just delay a random amount
        _commentsLastRequested.set(
                util.getContext().clock().now()
                        - (COMMENT_REQ_INTERVAL - _random.nextLong(COMMENT_REQ_DELAY)));
    }

    /**
     * Run the PeerCheckerTask via the SimpleTimer2 executors.
     *
     * @since 0.8.2
     */
    private static class CheckEvent extends SimpleTimer2.TimedEvent {
        /** The checker task being scheduled. */
        private final PeerCheckerTask _task;
        /** Where a pass that threw is reported, on the coordinator's logger. */
        private final Log _log;

        /**
         * Wrapper to reschedule the checker task on each fire.
         *
         * @param ctx the app context, for the timer
         * @param task the checker task to reschedule
         */
        public CheckEvent(I2PAppContext ctx, PeerCheckerTask task) {
            super(ctx.simpleTimer2());
            _task = task;
            _log = ctx.logManager().getLog(PeerCoordinator.class);
        }

        /**
         * Fire the checker task and reschedule for the next period. The reschedule is in a
         * finally because one pass throwing must not end every later one: the stall
         * re-queue, the choke cascade and the partial-piece parking all run on this job, so
         * a torrent whose pass died would stop re-queueing stalled pieces for good.
         */
        public void timeReached() {
            try {
                _task.run();
            } catch (RuntimeException re) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Checker pass failed -> Rescheduling anyway", re);
            } finally {
                schedule(checkPeriod(RandomSource.getInstance()));
            }
        }
    }

    /**
     * The next checker delay: CHECK_PERIOD spread by up to a second either way, so two
     * torrents started together do not keep running their checks, file cleanups and
     * local DHT announces in lockstep. The average stays at CHECK_PERIOD.
     *
     * @param r the random to spread with
     * @return the delay in ms
     * @since 0.9.72+
     */
    static long checkPeriod(RandomSource r) {
        return CHECK_PERIOD - 1000 + r.nextLong(2000);
    }

    /**
     * Rerequest after unthrottled.
     *
     * @since 0.9.62
     */
    private class RerequestEvent extends SimpleTimer2.TimedEvent {
        /**
         * Create rerequest event; caller must schedule.
         */
        public RerequestEvent() {
            super(_util.getContext().simpleTimer2());
        }

        /**
         * Check if unthrottled, poke peers if so, or reschedule.
         */
        public void timeReached() {
            // a halted coordinator must not keep itself alive by rescheduling
            if (halted) {
                return;
            }
            if (bwListener.shouldRequest(null, 0)) {
                if (_log.shouldInfo())
                    _log.info("Now unthrottled -> Rescheduling the rerequest timer"
                              + " and poking all peers");
                // so shouldRequest() won't fire us up again
                synchronized (rerequestLock) {
                    wasRequestAllowed = true;
                }
                for (Peer p : peers) {
                    if (p.isInteresting() && !p.isChoked()) {
                        p.request();
                    }
                }
                synchronized (rerequestLock) {
                    isRerequestScheduled = false;
                }
            } else {
                if (_log.shouldInfo())
                    _log.info("Still throttled -> Rescheduling the rerequest timer");
                synchronized (rerequestLock) {
                    wasRequestAllowed = false;
                }
                schedule((long) 2 * 1000);
            }
        }
    }

    /**
     * Rebuild the wanted pieces list from storage. Sets wantedBytes too.
     * Only called externally from Storage after the double-check fails.
     *
     * <p>The scan across every piece of the torrent, the piece allocations and
     * the shuffle are done outside the {@link #wantedPieces} monitor, which the
     * request path of every peer of this torrent takes, see
     * {@link #planWantedPieces}. A rebuild is the rare path and a piece request
     * is the hot one, so the scan is kept off that monitor and only the swap of
     * the rebuilt set and index is done under it.
     */
    public void setWantedPieces() {
        // read once, so a setStorage() part-way through cannot mix two
        // torrents into one scan
        MetaInfo mi = metainfo;
        Storage stg = storage;
        if (mi == null || stg == null) {
            wantedBytes = -1;
            return;
        }

        // Make a list of pieces, off the monitor, then swap it in
        WantedPiecesPlan plan = planWantedPieces(stg.getBitField(),
                                                 stg.getPiecePriorities(), mi, _random);
        synchronized (wantedPieces) {
            applyWantedPlan(wantedPieces, wantedMap, plan);
            wantedBytes = plan.bytes;
            wantedDirty = true;
        }
    }

    /**
     * The rebuilt wanted set and the bytes it occupies, as chosen by
     * {@link #planWantedPieces} and before {@link #applyWantedPlan} swaps it
     * into the live structures.
     *
     * @since 0.9.72+
     */
    static final class WantedPiecesPlan {
        /** The pieces to request from peers, in the order the request path should walk them. */
        final List<Piece> pieces;
        /** The total bytes of {@link #pieces}, so wantedBytes can be set in one write. */
        final long bytes;

        /**
         * Hold a rebuilt wanted set for the swap into the live structures.
         *
         * @param pieces the pieces to request, already shuffled
         * @param bytes the total bytes of those pieces
         */
        WantedPiecesPlan(List<Piece> pieces, long bytes) {
            this.pieces = pieces;
            this.bytes = bytes;
        }
    }

    /**
     * Choose which pieces to request from peers, and total their bytes: the
     * missing pieces at a non-negative priority, minus the BEP 47 padding-only
     * pieces, shuffled so peers do not all start from the same piece.
     *
     * <p>Pure - it reads its arguments and allocates, and takes no lock and
     * changes no coordinator state. That is what lets the scan run outside the
     * {@link #wantedPieces} monitor: a 4 GB torrent at 256 KB pieces is 16k
     * pieces, and every peer of the torrent waits on that monitor to pick its
     * next request, so scanning it there stalls the whole swarm's requests for
     * the length of a rebuild.
     *
     * @param have which pieces we already have, non-null
     * @param pri per-piece priorities as returned by
     *            {@link Storage#getPiecePriorities()}, or null to want every
     *            missing piece at the default priority
     * @param metainfo the torrent to read the piece geometry from, non-null
     * @param random the random to shuffle the result with, or null to leave it
     *               in id order
     * @return the pieces to want and their total bytes, never null
     * @since 0.9.72+
     */
    static WantedPiecesPlan planWantedPieces(BitField have, int[] pri, MetaInfo metainfo, Random random) {
        int pieces = metainfo.getPieces();
        // getPieceLength(0) throws on a torrent with no pieces, which the loop
        // below never visits, so keep returning an empty plan in that case
        int firstLength = pieces > 0 ? metainfo.getPieceLength(0) : 0;
        List<Piece> wanted = new ArrayList<>();
        long count = 0;
        for (int i = 0; i < pieces; i++) {
            // only add if we don't have and the priority is >= 0
            if ((!have.get(i)) && (pri == null || pri[i] >= 0)) {
                int length = metainfo.getPieceLength(i);
                // BEP 47: padding-only pieces hash as zeros and are always trusted,
                // so never request them
                if (metainfo.isRangePadding((long) i * firstLength, length)) {
                    continue;
                }
                Piece p = new Piece(i);
                if (pri != null) p.setPriority(pri[i]);
                wanted.add(p);
                count += length;
            }
        }
        if (random != null) {
            Collections.shuffle(wanted, random);
        }
        return new WantedPiecesPlan(wanted, count);
    }

    /**
     * Replace the contents of the live wanted set and its id index with a
     * rebuilt plan, so a piece that stopped being wanted - completed, or given a
     * negative priority - is gone from both, not only from one of them. The
     * caller holds the {@link #wantedPieces} monitor; the plan itself was built
     * without it.
     *
     * @param wanted the live wanted-piece set to replace the contents of
     * @param byId the live piece-id index to replace the contents of
     * @param plan the pieces to install, as built by {@link #planWantedPieces}
     * @since 0.9.72+
     */
    static void applyWantedPlan(Set<Piece> wanted, Map<Integer, Piece> byId, WantedPiecesPlan plan) {
        wanted.clear();
        wanted.addAll(plan.pieces);
        byId.clear();
        for (Piece p : plan.pieces) {
            byId.put(Integer.valueOf(p.getId()), p);
        }
    }

    /**
     * Returns the storage for this torrent.
     *
     * @return the storage, or null if in magnet mode
     */
    public Storage getStorage() {
        return storage;
    }

    /**
     * Returns a snapshot of the connected peers list for web page detailed stats.
     *
     * @return list of connected peers
     */
    public List<Peer> peerList() {
        return new ArrayList<>(peers);
    }

    /**
     * Record a peer surfaced by any source (announce, PEX, DHT, inbound) as a
     * distinct swarm member, keyed by destination hash. Web seeds are not
     * swarm members. Bounded; refreshes the last-seen time.
     *
     * @param peer the peer, non-null
     */
    void recordSwarmPeer(Peer peer) {
        if (peer instanceof WebPeer) {
            return;
        }
        byte[] destHash = peer.getPeerID().getDestHash();
        if (destHash == null) {
            return;
        }
        Hash key = new Hash(destHash);
        long now = System.currentTimeMillis();
        synchronized (swarmSeen) {
            if (!swarmSeen.containsKey(key) && swarmSeen.size() >= MAX_SWARM_PEERS) {
                // stay bounded, drop the oldest entry
                long oldest = Long.MAX_VALUE;
                Hash oldestKey = null;
                for (Map.Entry<Hash, Long> e : swarmSeen.entrySet()) {
                    if (e.getValue().longValue() < oldest) {
                        oldest = e.getValue().longValue();
                        oldestKey = e.getKey();
                    }
                }
                if (oldestKey != null) {
                    swarmSeen.remove(oldestKey);
                }
            }
            swarmSeen.put(key, Long.valueOf(now));
        }
    }

    /**
     * Distinct swarm peers seen recently (within {@link #SWARM_PEER_TTL}) or
     * currently connected, for the torrent view peer count. Prunes stale
     * entries lazily.
     *
     * @return the count, 0 if none
     */
    public int getSwarmPeerCount() {
        long cutoff = System.currentTimeMillis() - SWARM_PEER_TTL;
        Set<Hash> connected = new HashSet<>(peers.size());
        for (Peer p : peers) {
            byte[] destHash = p.getPeerID().getDestHash();
            if (destHash != null) {
                connected.add(new Hash(destHash));
            }
        }
        synchronized (swarmSeen) {
            Iterator<Map.Entry<Hash, Long>> it = swarmSeen.entrySet().iterator();
            int rv = 0;
            while (it.hasNext()) {
                Map.Entry<Hash, Long> e = it.next();
                long lastSeen = e.getValue().longValue();
                if (lastSeen < cutoff && !connected.contains(e.getKey())) {
                    it.remove();
                } else {
                    rv++;
                }
            }
            return rv;
        }
    }

    /**
     * Returns our peer ID.
     *
     * @return the peer ID bytes
     */
    public byte[] getID() {
        return id;
    }

    /**
     * Returns the torrent name.
     *
     * @return the torrent's display name, taken from the torrent file unless the
     *         completion listener renamed it
     */
    public String getName() {
        return snark.getName();
    }

    /**
     * Returns whether the torrent is complete.
     *
     * @return true if all pieces are downloaded
     */
    public boolean completed() {
        if (storage == null) {
            return false;
        }
        return storage.complete();
    }

    /**
     * Returns the estimated peer count (may be stale).
     *
     * @return estimated peer count
     */
    public int getPeerCount() {
        return peerCount;
    }

    /**
     * Returns the current number of connected peers.
     *
     * @return the number of currently connected peers
     */
    public int getPeers() {
        int rv = peers.size();
        peerCount = rv;
        return rv;
    }

    /**
     * Bytes not yet in storage. Does NOT account for skipped files. Returns how many bytes are
     * still needed to get the complete torrent.
     *
     * @return -1 if in magnet mode
     */
    public long getLeft() {
        if (metainfo == null | storage == null) {
            return -1;
        }
        int psz = metainfo.getPieceLength(0);
        long rv = ((long) storage.needed()) * psz;
        int last = metainfo.getPieces() - 1;
        BitField bf = storage.getBitField();
        if (bf != null && !bf.get(last)) {
            rv -= psz - metainfo.getPieceLength(last);
        }
        return rv;
    }

    /**
     * Bytes still wanted. DOES account for skipped files.
     *
     * @return exact value. or -1 if no storage yet.
     * @since 0.9.1
     */
    public long getNeededLength() {
        return wantedBytes;
    }

    /**
     * Returns the total number of uploaded bytes of all peers.
     *
     * @return total uploaded bytes
     */
    public long getUploaded() {
        return uploaded.get();
    }

    /**
     * The initial total of uploaded bytes of all peers, from a saved status.
     *
     * @param up the byte count to seed the uploaded total with
     * @since 0.9.15
     */
    public void setUploaded(long up) {
        uploaded.set(up);
    }

    /**
     * Returns the total number of downloaded bytes of all peers.
     *
     * @return total downloaded bytes
     */
    public long getDownloaded() {
        return downloaded.get();
    }

    /**
     * BandwidthListener interface method, called when a peer has uploaded
     * some bytes of a piece.
     *
     * @since 0.9.62 params changed
     */
    @Override
    public void uploaded(int size) {
        uploaded.addAndGet(size);
        bwListener.uploaded(size);
    }

    /**
     * Called when a peer has downloaded some bytes of a piece.
     *
     * @since 0.9.62 params changed
     */
    @Override
    public void downloaded(int size) {
        downloaded.addAndGet(size);
        bwListener.downloaded(size);
    }

    /**
     * Should we send this many bytes? Do NOT call {@link #uploaded} if this returns true.
     *
     * @return whether the bandwidth allowance permits sending that many bytes
     * @since 0.9.62
     */
    @Override
    public boolean shouldSend(int size) {
        boolean rv = bwListener.shouldSend(size);
        if (rv) {
            uploaded.addAndGet(size);
        }
        return rv;
    }

    /**
     * Should we request this many bytes?
     *
     * @return whether the bandwidth allowance permits requesting that many bytes
     * @since 0.9.62
     */
    @Override
    public boolean shouldRequest(Peer peer, int size) {
        boolean rv;
        synchronized (rerequestLock) {
            rv = bwListener.shouldRequest(peer, size);
            if (!wasRequestAllowed && rv) {
                // We weren't allowed and now we are
                if (isRerequestScheduled) {
                    // Just let the timer run when scheduled, do not pull it in
                    // to prevent thrashing
                    if (_log.shouldInfo()) {
                        _log.info("Now unthrottled, but not rescheduling rerequest timer to prevent"
                                  + " thrashing");
                    }
                } else {
                    // Schedule the timer
                    // we still have to throw it to the timer so we don't loop
                    if (_log.shouldInfo())
                        _log.info("Now unthrottled -> Scheduling the rerequest timer");
                    isRerequestScheduled = true;
                    // no rush, wait at little while
                    rerequestTimer.reschedule(1000);
                }
                wasRequestAllowed = true;
            } else if (wasRequestAllowed && !rv) {
                // we were allowed and now we aren't
                if (!isRerequestScheduled) {
                    // schedule the timer
                    if (_log.shouldInfo())
                        _log.info("Now throttled -> Scheduling the rerequest timer");
                    isRerequestScheduled = true;
                    rerequestTimer.schedule((long) 3 * 1000);
                }
                wasRequestAllowed = false;
            }
        }
        return rv;
    }

    /**
     * Push the total uploaded/downloaded onto a RATE_DEPTH deep stack.
     *
     * @param up uploaded bytes this period
     * @param down downloaded bytes this period
     */
    public void setRateHistory(long up, long down) {
        setRate(up, uploaded_old);
        setRate(down, downloaded_old);
    }

    /**
     * Push a value onto a RATE_DEPTH deep stack.
     *
     * <p>The shift and the insert are separate on purpose. Inserting inside the
     * shift loop, as this method used to, overwrote slot 0 on every iteration and
     * then shifted that same value down, so the ring held {newest, newest,
     * previous} instead of {newest, previous, previous-before-that}: the weighted
     * average in {@link #getRate(long[])} collapsed onto the newest sample and the
     * oldest slot was never filled.
     *
     * @param val the value to push
     * @param array the rate array to update
     */
    static void setRate(long val, long[] array) {
        synchronized (array) {
            for (int i = RATE_DEPTH - 1; i > 0; i--) {
                array[i] = array[i - 1];
            }
            array[0] = val;
        }
    }

    /** Returns the average rate in Bps over last RATE_DEPTH * CHECK_PERIOD seconds. */
    @Override
    public long getDownloadRate() {
        if (halted) {
            return 0;
        }
        return getRate(downloaded_old);
    }

    /** Returns the average rate in Bps over last RATE_DEPTH * CHECK_PERIOD seconds. */
    @Override
    public long getUploadRate() {
        if (halted) {
            return 0;
        }
        return getRate(uploaded_old);
    }

    /**
     * Returns the rate in Bps over last complete CHECK_PERIOD seconds.
     *
     * @return the upload rate in bytes per second over the most recent complete
     *         CHECK_PERIOD, or 0 when halted or before the first sample is recorded
     */
    public long getCurrentUploadRate() {
        if (halted) {
            return 0;
        }
        // no need to synchronize, only one value
        long r = uploaded_old[0];
        if (r <= 0) {
            return 0;
        }
        return (r * 1000) / CHECK_PERIOD;
    }

    /**
     * The download or upload rate, per the param.
     *
     * @param array rolling byte-count samples, most recent entry first, ending at a negative value
     * @return the weighted average rate in bytes per second, weighting each
     *         sample more heavily the more recent it is, or 0 when the ring holds
     *         no complete sample
     */
    static long getRate(long[] array) {
        long rate = 0;
        int i = 0;
        int factor = 0;
        synchronized (array) {
            for (; i < RATE_DEPTH; i++) {
                if (array[i] < 0) break;
                int f = RATE_DEPTH - i;
                rate += array[i] * f;
                factor += f;
            }
        }
        if (i == 0) {
            return 0;
        }
        return rate / (factor * CHECK_PERIOD / 1000);
    }

    /**
     * Current limit in Bps.
     *
     * @return the up b w limit
     * @since 0.9.62
     */
    @Override
    public long getUpBWLimit() {
        return bwListener.getUpBWLimit();
    }

    /**
     * Is snark as a whole over its upload limit?
     *
     * @return true if over limit
     */
    @Override
    public boolean overUpBWLimit() {
        return bwListener.overUpBWLimit();
    }

    /**
     * Is a particular peer who has downloaded this many bytes from us in the last CHECK_PERIOD over
     * its limit?
     *
     * @param total bytes uploaded to the peer in the last CHECK_PERIOD
     * @return true if over limit
     */
    public boolean overUpBWLimit(long total) {
        return total * 1000 / CHECK_PERIOD > getUpBWLimit();
    }

    /**
     * Current limit in Bps.
     *
     * @return the down b w limit
     * @since 0.9.62
     */
    @Override
    public long getDownBWLimit() {
        return bwListener.getDownBWLimit();
    }

    /**
     * Are we currently over the limit?
     *
     * @since 0.9.62
     */
    @Override
    public boolean overDownBWLimit() {
        return bwListener.overDownBWLimit();
    }

    /**
     * Returns the metainfo for this torrent.
     *
     * @return the metainfo, or null if in magnet mode
     */
    public MetaInfo getMetaInfo() {
        return metainfo;
    }

    /**
     * The info hash for this torrent.
     *
     * @return the torrent hash used for magnet and peer exchange lookups
     */
    public byte[] getInfoHash() {
        return infohash;
    }

    /**
     * Inbound. Not halted, peers &lt; max.
     *
     * @return true while the coordinator is running and holds fewer than the
     *         configured maximum peer connections, so another inbound may be accepted
     * @since 0.9.1
     */
    public boolean needPeers() {
        return !halted && peers.size() < getMaxConnections();
    }

    /**
     * Outbound. Not halted, peers &lt; max, and need pieces.
     *
     * @return true while the coordinator is running, holds at least two
     *         connections below the maximum so new peers can displace stale ones,
     *         and either pieces are still wanted or a comment request is due
     * @since 0.9.1
     */
    public boolean needOutboundPeers() {
        // minus two to make it a little easier for new peers to get in on large swarms
        return (wantedBytes != 0
                        || (_util.utCommentsEnabled()
                                &&
                                // we should also check SnarkManager.getSavedCommentsEnabled() for
                                // this torrent,
                                // but that reads from metadata, there's no caching.
                                // TODO
                                _commentsLastRequested.get()
                                        < _util.getContext().clock().now() - COMMENT_REQ_INTERVAL))
                && !halted
                && peers.size() < getMaxConnections() - 2
                && (storage == null || !storage.isChecking());
    }

    /**
     * Formerly used to reduce max if huge pieces to keep from ooming when leeching but now we don't.
     *
     * @return usually I2PSnarkUtil.MAX_CONNECTIONS
     */
    private int getMaxConnections() {
        if (metainfo == null) {
            return 6;
        }

        int pieces = metainfo.getPieces();
        int max = _util.getMaxConnections();
        if (pieces <= 10 && max > 4) max = 4;
        else if (pieces <= 25 && max > 10) max = 10;
        else if (pieces <= 80 && max > 16) max = 16;

        long bwl = getDownBWLimit();
        if (bwl < 32 * 1024) {
            max =
                    Math.min(
                            max,
                            Math.max(6, (int) (I2PSnarkUtil.MAX_CONNECTIONS * bwl / (32 * 1024))));
        }
        return max;
    }

    /**
     * Returns whether this coordinator has been halted.
     *
     * @return true if halted
     */
    public boolean halted() {
        return halted;
    }

    /**
     * Halt this coordinator, disconnecting all peers.
     */
    public void halt() {
        halted = true;
        List<Peer> removed = new ArrayList<>();
        synchronized (peers) {
            // Stop peer checker task.
            timer.cancel();
            // Stop the rerequest chain too - while throttled it reschedules
            // itself every 2s and would keep this coordinator alive forever
            rerequestTimer.cancel();
            // Stop peers.
            removed.addAll(peers);
            peers.clear();
            peerCount = 0;
        }

        while (!removed.isEmpty()) {
            Peer peer = removed.remove(0);
            // disconnect() calls disconnected() calls removePeerFromPieces()
            peer.disconnect();
        }
        // delete any saved orphan partial piece
        synchronized (wantedPieces) {
            for (PartialPiece pp : partialPieces) {
                pp.release();
            }
            partialPieces.clear();
            partialIds.clear();
            clearLivePartials();
        }
        // Release any piece still waiting for the writer thread. A writer task that
        // is mid-write finishes that piece and then drops the rest: drainWriteQueue()
        // re-checks halted on every pass.
        synchronized (_writeQueue) {
            for (PendingWrite pw : _writeQueue) {
                pw._pp.release();
            }
            _writeQueue.clear();
        }
    }

    /** Restart after halt. */
    public void restart() {
        halted = false;
        synchronized (uploaded_old) {
            Arrays.fill(uploaded_old, 0);
        }
        synchronized (downloaded_old) {
            Arrays.fill(downloaded_old, 0);
        }
        // failsafe
        synchronized (wantedPieces) {
            for (Piece pc : wantedPieces) {
                pc.clear();
            }
            wantedDirty = true;
        }
        timer.schedule((CHECK_PERIOD / 2) + _random.nextInt((int) CHECK_PERIOD));
    }

    /**
     * Called when a peer connection is established.
     *
     * @param peer the newly connected peer
     */
    @Override
    public void connected(Peer peer) {
        if (halted) {
            peer.disconnect(false);
            return;
        }

        Peer toDisconnect = null;
        synchronized (peers) {
            Peer old = peerIDInList(peer.getPeerID(), peers);
            if (old != null && old.getInactiveTime() > old.getMaxInactiveTime()) {
                // idle for 8 minutes, kill the old con (32KB/8min = 68B/sec minimum for one block)
                if (_log.shouldInfo())
                    _log.info("Removing an idle peer connection -> Idle too long"
                              + "\n* Dropping: " + old + " * Keeping: "
                                + SnarkLogFormatter.peer(peer) + " * Idle: "
                              + DataHelper.formatDuration(old.getInactiveTime()));
                peers.remove(old);
                toDisconnect = old;
                old = null;
            }
            if (old != null) {
                // BEP 40 canonical peer priority for I2P: when both we and the
                // peer connected to each other, the side with the smaller
                // destination hash keeps its outgoing leg as the canonical
                // initiator, so the larger side abandons its outgoing leg
                boolean dropNew = old.isIncoming() == peer.isIncoming();
                if (!dropNew) {
                    Destination myDest = _util.getMyDestination(getInfoHash());
                    byte[] ourHash = myDest == null ? null : myDest.calculateHash().getData();
                    byte[] peerHash = peer.getPeerID().getDestHash();
                    if (ourHash == null || peerHash == null) {
                        dropNew = true;
                    } else {
                        dropNew = (DataHelper.compareTo(ourHash, peerHash) > 0) == !peer.isIncoming();
                    }
                }
                if (dropNew) {
                    if (_log.shouldInfo())
                        _log.info("Duplicate peer connection -> Keeping the existing one"
                                  + "\n* Dropping: " + SnarkLogFormatter.peer(peer) + " * Kept: "
                                    + old + " * Idle: "
                                  + DataHelper.formatDuration(old.getInactiveTime()));
                    // toDisconnect = peer to get out of synchronized(peers)
                    peer.disconnect(false); // Don't deregister this connection/peer.
                    // Already checked in addPeer() but we could have gone over the limit since then
                } else {
                    if (_log.shouldInfo())
                        _log.info("Canonical peer priority -> Replacing the duplicate connection"
                                  + " * Keeping: " + SnarkLogFormatter.peer(peer) + " * Dropping: "
                                    + old);
                    peers.remove(old);
                    toDisconnect = old;
                    old = null;
                }
            }
            if (old == null && peers.size() >= getMaxConnections()) {
                if (_log.shouldWarn()) {
                    _log.warn("Already at MAX_CONNECTIONS in connected() with peer "
                              + SnarkLogFormatter.peer(peer));
                }
                // toDisconnect = peer to get out of synchronized(peers)
                peer.disconnect(false);
            } else if (old == null) {
                if (_log.shouldInfo()) {
                    // just for logging
                    String name;
                    if (metainfo == null) {
                        name = "Magnet";
                    } else {
                        name = metainfo.getName();
                    }
                    _log.info("New connection to " + SnarkLogFormatter.peer(peer) + " for " + name);
                }

                // We may have gotten the metainfo after the peer was created.
                if (metainfo != null) {
                    peer.setMetaInfo(metainfo);
                }

                // Add it to the beginning of the list.
                // And try to optimistically make it a uploader.
                if (_util.getContext().random().nextInt(4) == 0) {
                    peers.push(peer);
                } else {
                    peers.add(peer);
                }
                peerCount = peers.size();
                unchokePeer();
            }
        }
        if (toDisconnect != null) {
            toDisconnect.disconnect(true); // ensure partial pieces are returned
            // disconnect() calls disconnected() but will not call removePeerFromPieces()
            // because it was removed from peers above
            removePeerFromPieces(toDisconnect);
        }
    }

    /**
     * The peer with the given ID, or null.
     *
     * @param pid the peer id to look for
     * @param peers the collection of peers to scan
     * @return peer if peer id is in the collection, else null
     */
    private static Peer peerIDInList(PeerID pid, Collection<Peer> peers) {
        Iterator<Peer> it = peers.iterator();
        while (it.hasNext()) {
            Peer cur = it.next();
            if (pid.sameID(cur.getPeerID())) return cur;
        }
        return null;
    }

    /**
     * Is the peer the same destination we are using for this torrent? The
     * tracker, PEX and the DHT all report us back to ourselves while we are in
     * the swarm, so the check is made before the dial rather than in the socket
     * layer, after a thread, a destination lookup and a refused connect are spent.
     *
     * @param ourHash the hash of our own destination for the torrent, null when
     *                the session is not up yet
     * @param peerHash the hash of the peer's destination, null when the peer has
     *                 no address yet
     * @return true only when both hashes are known and identical
     * @since 0.9.72+
     */
    static boolean isSelf(byte[] ourHash, byte[] peerHash) {
        return ourHash != null && peerHash != null && DataHelper.eq(ourHash, peerHash);
    }

    /**
     * Add peer (inbound or outbound).
     *
     * @param peer the connection to take over; disconnected rather than tracked if
     *             halted, full, or our own destination
     * @return true if actual attempt to add peer occurs
     */
    public boolean addPeer(final Peer peer) {
        if (halted) {
            peer.disconnect(false);
            return false;
        }

        Destination myDest = _util.getMyDestination(getInfoHash());
        Hash ourHash = myDest != null ? myDest.getHash() : null;
        if (isSelf(ourHash != null ? ourHash.getData() : null, peer.getPeerID().getDestHash())) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping our own destination -> Peer not added"
                           + " * Peer: " + SnarkLogFormatter.peer(peer)
                           + " * Torrent: " + SnarkLogFormatter.torrent(snark));
            }
            peer.disconnect(false);
            return false;
        }

        boolean needMore;
        int peersize = 0;
        synchronized (peers) {
            peersize = peers.size();
            needMore = (!peer.isConnected()) && peersize < getMaxConnections();
            if (needMore) {
                Peer old = peerIDInList(peer.getPeerID(), peers);
                needMore = old == null || old.getInactiveTime() > old.getMaxInactiveTime();
            }
        }

        if (needMore) {
            if (_log.shouldDebug()) {
                // just for logging
                String name;
                if (metainfo == null) {
                    name = "Magnet";
                } else {
                    name = metainfo.getName();
                }
                _log.debug("Adding a peer for " + name + " -> Peer: " + peer.getPeerID());
            }

            // Run the peer with us as listener and the current bitfield.
            final PeerListener listener = this;
            final BitField bitfield;
            if (storage != null) {
                bitfield = storage.getBitField();
            } else {
                bitfield = null;
            }
            if (!peer.isIncoming() && wantedBytes == 0 && _log.shouldInfo()) {
                _log.info("Checking for new comments -> Torrent: "
                          + SnarkLogFormatter.torrent(snark) + " * Peer: "
                          + SnarkLogFormatter.peer(peer));
            }

            // if we aren't a seed but we don't want any more
            final boolean partialComplete =
                    wantedBytes == 0 && bitfield != null && !bitfield.complete();
            Runnable r =
                    new Runnable() {
                        public void run() {
                            peer.runConnection(
                                    _util,
                                    listener,
                                    PeerCoordinator.this,
                                    bitfield,
                                    magnetState,
                                    partialComplete);
                        }
                    };

            String threadName =
                    // 4 characters of the destination hash, so the name stays in the log column
                    "SnarkPr" + Base32.encode(peer.getPeerID().getDestHash()).substring(0, 4);
            new I2PAppThread(r, threadName, true).start();
            return true;
        }

        if (_log.shouldDebug()) {
            if (peer.isConnected()) {
                _log.debug("Add peer already connected " + SnarkLogFormatter.peer(peer));
            } else {
                _log.debug("Not accepting extra peer " + SnarkLogFormatter.peer(peer) + " ("
                           + "Connections: "
                           + peersize + "/" + getMaxConnections() + ")");
            }
        }
        return false;
    }

    /** (Optimistically) unchoke. Must be called with peers synchronized. */
    void unchokePeer() {
        if (storage == null || storage.getBitField().size() == 0 || overUpBWLimit()) {
            return;
        }

        // linked list will contain all interested peers that we choke.
        // At the start are the peers that have us unchoked at the end the
        // other peer that are interested, but are choking us.
        List<Peer> interested = new LinkedList<>();
        int count = 0;
        int unchokedCount = 0;
        int maxUploaders = allowedUploaders();
        Iterator<Peer> it = peers.iterator();
        while (it.hasNext()) {
            Peer peer = it.next();
            if (peer.isChoking() && peer.isInterested()) {
                count++;
                if (uploaders.get() < maxUploaders) {
                    if (peer.isInteresting() && !peer.isChoked()) {
                        interested.add(unchokedCount++, peer);
                    } else {
                        interested.add(peer);
                    }
                }
            }
        }

        int up = uploaders.get();
        while (up < maxUploaders && !interested.isEmpty()) {
            Peer peer = interested.remove(0);
            if (_log.shouldDebug()) {
                _log.debug("Unchoking " + SnarkLogFormatter.peer(peer));
            }
            peer.setChoking(false);
            up = uploaders.incrementAndGet();
            interestedUploaders.incrementAndGet();
            count--;
            // Put peer back at the end of the list.
            peers.remove(peer);
            peers.add(peer);
            peerCount = peers.size();
        }
        interestedAndChoking.set(count);
    }

    /**
     * Whether we still want the given piece.
     *
     * @return true if we still want the given piece
     */
    @Override
    public boolean gotHave(Peer peer, int piece) {
        synchronized (wantedPieces) {
            Piece pc = wantedMap.get(Integer.valueOf(piece));
            if (pc != null) {
                pc.addPeer(peer);
                wantedDirty = true;
                return true;
            }
            return false;
        }
    }

    /** Returns true if the given bitfield contains at least one piece we are interested in. */
    @Override
    public boolean gotBitField(Peer peer, BitField bitfield) {
        boolean rv = false;
        synchronized (wantedPieces) {
            for (Piece p : wantedPieces) {
                int i = p.getId();
                if (bitfield.get(i)) {
                    p.addPeer(peer);
                    rv = true;
                }
            }
            if (rv) {
                wantedDirty = true;
            }
        }
        return rv;
    }

    /**
     * This should be somewhat less than the max conns per torrent, but not too much less, so a
     * torrent doesn't get stuck near the end.
     * https://en.wikipedia.org/wiki/Glossary_of_BitTorrent_terms#Endgame_/_Endgame_mode
     * Package-visible so the end-game boundary can be pinned by the tests.
     *
     * @since 0.7.14
     */
    static final int END_GAME_THRESHOLD = 32;

    /**
     * Minimum number of peers to get a piece from when in end game - the floor of the
     * per-piece cap from {@link #parallelRequestCap(int, int)}. Package-visible so the
     * tests can name the floor rather than repeat it.
     *
     * @since 0.8.1
     */
    static final int MAX_PARALLEL_REQUESTS = 8;

    /**
     * Ceiling on the per-piece parallel request cap, so a torrent with huge pieces cannot
     * end up with a hundred peers each claiming a couple of blocks of one piece.
     *
     * @since 0.9.72+
     */
    static final int MAX_PARALLEL_REQUESTS_CAP = 16;

    /**
     * How many peers may pull blocks of one piece at the same time.
     *
     * <p>The cap used to be the fixed {@link #MAX_PARALLEL_REQUESTS}. That only binds when
     * the piece has more blocks than it, so a torrent with 16 KiB pieces - one block each -
     * was already pulling a piece from more peers than the number allowed, while a torrent
     * with 512 KiB pieces (32 blocks at the default part size) stopped at 8 peers and left
     * 24 blocks' worth of willing peers unused. Scaling the cap to the piece's block count
     * uses that demand where it exists and keeps the bookkeeping bounded where it does not.
     *
     * @param pieceLength the piece's length in bytes, from
     *                    {@link MetaInfo#getPieceLength(int)}; anything that is not
     *                    positive falls back to the floor
     * @param partSize the block size we request, {@link PeerState#PARTSIZE}
     * @return the cap, from {@value #MAX_PARALLEL_REQUESTS} to
     *         {@value #MAX_PARALLEL_REQUESTS_CAP} peers
     * @since 0.9.72+
     */
    static int parallelRequestCap(int pieceLength, int partSize) {
        if (pieceLength <= 0 || partSize <= 0) {
            return MAX_PARALLEL_REQUESTS;
        }
        int blocks = 1 + (pieceLength - 1) / partSize;
        return Math.max(MAX_PARALLEL_REQUESTS, Math.min(MAX_PARALLEL_REQUESTS_CAP, blocks));
    }

    /**
     * Returns one of pieces in the given BitField that is still wanted or null if none of the given
     * pieces are wanted.
     *
     * @param peer the peer to request the piece from
     * @param havePieces the pieces the peer claims to have
     * @param record if true, actually record in our data structures that we gave the request to
     *               this peer. If false, do not update the data structures.
     * @return the piece to request, or null if none of the peer's pieces are
     *         wanted
     * @since 0.8.2
     */
    private Piece wantPiece(Peer peer, BitField havePieces, boolean record) {
        return wantPiece(peer, havePieces, record, null);
    }

    /**
     * Reorder the wanted set into the request order - priority first, then
     * rarest first - by rebuilding it from a sorted list. A Set cannot be
     * sorted in place; a LinkedHashSet iterates in the order it was last
     * rebuilt, so this is what establishes the rarest-first order the request
     * path walks.
     * <p>
     * Called on the request path when the set is dirty, where the ArrayList
     * this replaced already paid the O(n log n) sort.
     *
     * @param wanted the set to reorder; the caller holds its monitor
     * @since 0.9.72+
     */
    static void resortWanted(Set<Piece> wanted) {
        List<Piece> sorted = new ArrayList<>(wanted);
        Collections.sort(sorted);
        wanted.clear();
        wanted.addAll(sorted);
    }

    /**
     * Shuffle the wanted set so a batch of pieces added in id order is not
     * requested in id order, which would have every peer in the swarm start
     * from the same piece.
     *
     * @param wanted the set to reorder; the caller holds its monitor
     * @param random the random to shuffle with
     * @since 0.9.72+
     */
    static void reshuffleWanted(Set<Piece> wanted, Random random) {
        List<Piece> shuffled = new ArrayList<>(wanted);
        Collections.shuffle(shuffled, random);
        wanted.clear();
        wanted.addAll(shuffled);
    }

    /**
     * Returns one of the claimed pieces in the given BitField that is still wanted or null if none
     * of the given pieces are wanted.
     *
     * <p>When the allowed set is not null, only pieces in it are eligible, for requesting BEP 6
     * allowed fast pieces from a peer that chokes us.
     *
     * @param peer the peer to request the piece from
     * @param havePieces the pieces the peer claims to have
     * @param record if true, actually record in our data structures that we gave the request to
     *               this peer. If false, do not update the data structures.
     * @param allowed the pieces servable while choked, or null for no restriction
     * @return the piece to request, or null if none of the peer's allowed
     *         pieces are wanted
     * @since 0.9.71+
     */
    private Piece wantPiece(
            Peer peer, BitField havePieces, boolean record, Set<Integer> allowed) {
        if (halted) {
            if (_log.shouldInfo()) {
                _log.info("We don't want anything from " + SnarkLogFormatter.peer(peer)
                          + " as we are halted!");
            }
            return null;
        }
        Piece piece = null;
        List<Piece> requested = new ArrayList<>();
        int wantedSize = END_GAME_THRESHOLD + 1;
        synchronized (wantedPieces) {
            if (record && wantedDirty) {
                resortWanted(wantedPieces);
                wantedDirty = false;
            } // Sort in order of rarest first.
            Iterator<Piece> it = wantedPieces.iterator();
            while (piece == null && it.hasNext()) {
                Piece p = it.next();
                // sorted by priority, so when we hit a disabled piece we are done
                if (p.isDisabled()) {
                    break;
                }
                if (allowed != null && !allowed.contains(Integer.valueOf(p.getId()))) {
                    continue; // BEP 6: only allowed fast pieces while choked
                }
                if (peer.dontHave(p.getId())) {
                    continue; // BEP 54: peer said it no longer has this piece
                }
                if (havePieces.get(p.getId()) && !p.isRequested()) {
                    // never ever choose one that's in partialPieces, or we
                    // will create a second one and leak
                    Integer key = Integer.valueOf(p.getId());
                    boolean hasPartial = partialIds.contains(key) || _livePartials.containsKey(key);
                    if (hasPartial && _log.shouldDebug()) {
                        _log.debug("Skipping a piece with an existing partial request"
                                   + " -> Peer: " + SnarkLogFormatter.peer(peer) + " * Piece: "
                                     + p.getId());
                    }
                    if (!hasPartial) {
                        piece = p;
                    }
                } else if (p.isRequested()) {
                    requested.add(p);
                }
            }

            if (piece == null) {
                wantedSize = wantedPieces.size();
            }

            // Only request a piece we've requested before if there's no other choice.
            if (piece == null) {
                // AND if there are almost no wanted pieces left (real end game).
                // If we do end game all the time, we generate lots of extra traffic
                // when the seeder is super-slow and all the peers are "caught up"
                if (wantedSize > END_GAME_THRESHOLD) {
                    if (_log.shouldInfo()) {
                        // the peer and the torrent - the counts alone named neither
                        // the peer being turned away nor the torrent that had
                        // nothing left for it. What is in flight is already in the
                        // per-peer request tally lines
                        _log.info("Nothing to request from " + SnarkLogFormatter.peer(peer)
                                  + " -> Torrent: " + SnarkLogFormatter.torrent(metainfo)
                                  + " * Wanted: " + wantedSize + " pieces");
                    }
                    return null; // nothing to request and not in end game
                }

                // Let's not all get on the same piece
                // Even better would be to sort by number of requests
                if (record) {
                    Collections.shuffle(requested, _random);
                }
                Iterator<Piece> it2 = requested.iterator();
                while (piece == null && it2.hasNext()) {
                    Piece p = it2.next();
                    if (allowed != null && !allowed.contains(Integer.valueOf(p.getId()))) {
                        continue; // BEP 6: only allowed fast pieces while choked
                    }
                    if (peer.dontHave(p.getId())) {
                        continue; // BEP 54: peer said it no longer has this piece
                    }
                    if (havePieces.get(p.getId())) {
                        // limit number of parallel requests, scaled to how many blocks
                        // this piece is actually made of
                        int requestedCount = p.getRequestCount();
                        int cap = parallelRequestCap(
                                metainfo != null ? metainfo.getPieceLength(p.getId()) : 0,
                                PeerState.PARTSIZE);
                        if (requestedCount < cap && !p.isRequestedBy(peer)) {
                            piece = p;
                            break;
                        }
                    }
                }

                if (piece == null) {
                    if (_log.shouldInfo()) {
                        // every piece this peer has is already at the parallel
                        // request cap or asked of it before. The requested list is
                        // a List<Piece> printed as object identities nobody can
                        // read; the count is the finding and the pieces themselves
                        // are named by the request lines that put them in flight
                        _log.info("Every piece it has is already being pulled"
                                  + " -> Peer: " + SnarkLogFormatter.peer(peer)
                                  + " * Torrent: " + SnarkLogFormatter.torrent(metainfo));
                    }
                    return null; // If we still can't find a piece we want, so be it.
                } else {
                    // Should be a lot smarter here -
                    // share blocks rather than starting from 0 with each peer.
                    // This is where the flaws of the snark data model are really exposed.
                    // Could also randomize within the duplicate set rather than strict rarest-first
                    if (_log.shouldInfo()) {
                        _log.info("Requesting a piece already in flight"
                                  + " -> Peer: " + SnarkLogFormatter.peer(peer)
                                  + " * Piece: " + piece.getId()
                                  + " * Torrent: " + SnarkLogFormatter.torrent(metainfo));
                    }
                }
            }

            if (record) {
                if (_log.shouldInfo()) {
                    if (piece.getPriority() > 0) {
                        _log.info("Requesting piece " + piece.getId()
                                  + " -> Peer: " + SnarkLogFormatter.peer(peer)
                                  + " * Torrent: " + SnarkLogFormatter.torrent(metainfo)
                                  + " * Priority: " + piece.getPriority());
                    } else {
                        _log.info("Requesting piece " + piece.getId()
                                  + " -> Peer: " + SnarkLogFormatter.peer(peer)
                                  + " * Torrent: " + SnarkLogFormatter.torrent(metainfo));
                    }
                }
                piece.setRequested(peer, true);
            }
            return piece;
        } // sync
    }

    /**
     * Maps file priorities to piece priorities. Call after updating file priorities
     * {@link org.klomp.snark.Storage#setPriority}.
     *
     * @since 0.8.1
     */
    public void updatePiecePriorities() {
        if (storage == null) {
            return;
        }
        int[] pri = storage.getPiecePriorities();
        if (pri == null) {
            _log.debug("Updated piece priorities called but no priorities to set?");
            return;
        }
        List<Piece> toCancel = new ArrayList<>();
        synchronized (wantedPieces) {
            // Add incomplete and previously unwanted pieces to the list
            // Temp to avoid O(n**2)
            BitField want = new BitField(pri.length);
            for (Piece p : wantedPieces) {
                want.set(p.getId());
            }
            BitField bitfield = storage.getBitField();
            for (int i = 0; i < pri.length; i++) {
                if (pri[i] >= 0 && !bitfield.get(i) && !want.get(i)) {
                    Piece piece = new Piece(i);
                    wantedPieces.add(piece);
                    wantedMap.put(Integer.valueOf(i), piece);
                    wantedBytes += metainfo.getPieceLength(i);
                    // As connections are already up, new Pieces will
                    // not have their PeerID list populated, so do that.
                    for (Peer p : peers) {
                        // TODO don't access state directly
                        PeerState s = p.state;
                        if (s != null) {
                            BitField bf = s.bitfield;
                            if (bf != null && bf.get(i)) {
                                piece.addPeer(p);
                            }
                        }
                    }
                }
            }

            // Now set the new priorities and remove newly unwanted pieces
            for (Iterator<Piece> iter = wantedPieces.iterator(); iter.hasNext(); ) {
                Piece p = iter.next();
                int priority = pri[p.getId()];
                if (priority >= 0) {
                    p.setPriority(priority);
                } else {
                    iter.remove();
                    wantedMap.remove(Integer.valueOf(p.getId()));
                    toCancel.add(p);
                    wantedBytes -= metainfo.getPieceLength(p.getId());
                }
            }
            wantedDirty = true;
            if (_log.shouldDebug()) {
                _log.debug("Updated piece priorities, now wanted: " + wantedPieces);
            }
            // if we added pieces, they will be in-order unless we shuffle
            reshuffleWanted(wantedPieces, _random);
        }

        // cancel outside of wantedPieces lock to avoid deadlocks
        if (!toCancel.isEmpty()) {
            // cancel all peers
            for (Peer peer : peers) {
                for (Piece p : toCancel) {
                    peer.cancel(p.getId());
                }
            }
            // and let go of the partial data of pieces we no longer want. A shared
            // piece has no single peer left to free it once its requests are cancelled,
            // so it would otherwise stay registered until the torrent stopped.
            for (Piece p : toCancel) {
                removePartialPiece(p.getId());
            }
        }

        // ditto, avoid deadlocks
        // update request queues, in case we added wanted pieces
        // and we were previously uninterested
        for (Peer peer : peers) {
            peer.request();
        }
    }

    /**
     * Returns a byte array containing the requested piece or null of the piece is unknown.
     *
     * @return bytes or null for errors such as not having the piece yet
     * @throws RuntimeException on IOE getting the data
     */
    @Override
    public ByteArray gotRequest(Peer peer, int piece, int off, int len) {
        if (halted) {
            return null;
        }
        if (metainfo == null || storage == null) {
            return null;
        }

        try {
            return storage.getPiece(piece, off, len);
        } catch (IOException ioe) {
            // a failed read drops just this request; the peer will re-request.
            // not a reason to stop the torrent, but say why in the console
            String msg =
                    "Storage error: "
                            + StorageError.describe(ioe)
                            + " reading [piece "
                            + piece
                            + "] for "
                            + metainfo.getName();
            _log.error(msg, ioe);
            if (listener != null) {
                listener.addMessage(msg);
            }
            if (!I2PAppContext.getGlobalContext().isRouterContext()) {
                System.out.println(" • " + msg);
            }
            return null;
        }
    }

    /**
     * Whether the storage error is a genuine filesystem problem that should
     * stop the torrent with an explicit reason. Genuine means no space, bad
     * permissions, read-only filesystem, or hardware I/O error; transient or
     * stale-handle conditions (EBADF, fd exhaustion, vanished file) can be
     * retried.
     *
     * @param ioe the storage error
     * @return true if the torrent should stop
     * @since 0.9.71+
     */
    private static boolean isFatalStorageError(IOException ioe) {
        if (ioe.getMessage() == null) {
            // unclassifiable; stop rather than retry forever
            return true;
        }
        return StorageError.classify(ioe).isFatal();
    }

    /**
     * Max completed pieces one torrent may have waiting for the writer thread.
     * Past this the peer connection thread verifies and writes the piece inline,
     * exactly as it always did, so a burst of completions applies backpressure
     * instead of pinning an unbounded amount of piece data in memory.
     * Package-private for the tests.
     */
    static final int MAX_QUEUED_WRITES = 4;

    /**
     * Writer threads available to all torrents. More than one lets different
     * torrents overlap their disk I/O; a single torrent is still drained by one
     * task at a time so its file writes stay serialized. Package-private for the tests.
     */
    static final int MAX_WRITER_THREADS = 2;

    /** Idle writer threads exit after this long, so an idle router pays nothing. */
    private static final long WRITER_THREAD_IDLE_MS = 60 * 1000;

    /** Sequence number of the piece-writer threads. */
    private static final AtomicInteger _writerThreadId = new AtomicInteger();

    /**
     * Pool running piece verify and write, shared by every torrent. Piece verify is a
     * full SHA-1 over the piece, plus a second pass over its temp file for a piece too
     * big to keep in memory, and the write is a third pass plus the torrent file
     * write; all of that used to run on the peer's connection thread, where it closed
     * that peer's receive window and throttled its download for the duration.
     */
    private static final ThreadPoolExecutor _writerExec =
            new ThreadPoolExecutor(
                    0,
                    MAX_WRITER_THREADS,
                    WRITER_THREAD_IDLE_MS,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t =
                                new I2PAppThread(
                                        r, "SnarkWr" + ThreadNames.seq(_writerThreadId.incrementAndGet()), true);
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    static {
        _writerExec.allowCoreThreadTimeOut(true);
    }

    /**
     * A completed piece plus the peer that finished it, waiting for the writer thread.
     */
    private static final class PendingWrite {
        /** The peer that delivered the last block of the piece. */
        final Peer _peer;
        /** The completed piece data, owned by the queue until it is written. */
        final PartialPiece _pp;
        /** The wanted-piece record claimed for this write, or null if unclaimed. */
        final Piece _wanted;

        /**
         * Holds one queued write.
         *
         * @param peer the peer that delivered the last block
         * @param pp the completed piece data
         * @param wanted the claimed wanted-piece record, or null
         */
        PendingWrite(Peer peer, PartialPiece pp, Piece wanted) {
            _peer = peer;
            _pp = pp;
            _wanted = wanted;
        }
    }

    /**
     * Completed pieces waiting to be verified and written, FIFO. Sync on this.
     */
    private final Deque<PendingWrite> _writeQueue = new ArrayDeque<>(MAX_QUEUED_WRITES);

    /**
     * True while a pool task is draining this torrent's write queue. One task per
     * torrent at a time keeps that torrent's file writes in order and off each other.
     * Sync on _writeQueue.
     */
    private boolean _draining;

    /**
     * Pieces waiting for the writer thread. Package-private for the tests.
     *
     * @return the queue depth, 0 if nothing is pending
     */
    int queuedWrites() {
        synchronized (_writeQueue) {
            return _writeQueue.size();
        }
    }

    /**
     * Queue a completed piece for the writer thread. Package-private so the tests can
     * drive the queue without a Storage.
     *
     * @param peer the peer that delivered the last block of the piece
     * @param pp the completed piece data, taken over by the queue
     * @param wanted the claimed wanted-piece record, or null if the piece was unclaimed
     * @return false if the queue was full or the pool saturated, in which case nothing
     *         was queued and the caller must write the piece itself
     */
    boolean queuePieceWrite(Peer peer, PartialPiece pp, Piece wanted) {
        boolean start;
        synchronized (_writeQueue) {
            if (_writeQueue.size() >= MAX_QUEUED_WRITES) return false;
            _writeQueue.add(new PendingWrite(peer, pp, wanted));
            start = !_draining;
            if (start) _draining = true;
        }
        if (start) {
            try {
                _writerExec.execute(this::drainWriteQueue);
            } catch (RejectedExecutionException ree) {
                // Pool saturated and the queue is not empty; drain it on this thread
                // rather than losing the queued pieces.
                drainWriteQueue();
            }
        }
        return true;
    }

    /**
     * Drain this torrent's write queue, verifying and writing each piece in turn.
     * Runs on a pool thread, or on the submitting thread when the pool is saturated.
     * Stops early once the torrent is halted, releasing whatever is still queued.
     */
    private void drainWriteQueue() {
        try {
            for (;;) {
                PendingWrite pw;
                synchronized (_writeQueue) {
                    pw = _writeQueue.poll();
                    if (pw == null) {
                        _draining = false;
                        return;
                    }
                }
                if (halted) {
                    pw._pp.release();
                    continue;
                }
                writePieceNow(pw._peer, pw._pp, pw._wanted);
            }
        } catch (Throwable t) {
            // A fatal storage error stops the torrent and rethrows from writePieceNow;
            // on a pool thread there is nobody to propagate it to, so drop the queue
            // and log rather than killing the writer for every other torrent.
            _log.error("Piece writer stopped for " + metainfo.getName(), t);
            synchronized (_writeQueue) {
                _draining = false;
                for (PendingWrite pw : _writeQueue) {
                    pw._pp.release();
                }
                _writeQueue.clear();
            }
        }
    }

    /**
     * A peer has finished sending us every block of a piece. Claim the piece so no
     * other peer writes the same one, then hand the verify and the write to the
     * writer thread; the SHA-1 and the disk I/O must not run on this peer's
     * connection thread, where they would stall its inbound stream.
     *
     * <p>Returns true unless the piece is dropped outright. A hash failure is not
     * known yet when this returns; the writer thread tells the peer's state about it
     * through {@link PeerState#clearHave(int)}.
     *
     * @param peer the peer that delivered the last block of the piece
     * @param pp the completed piece data
     * @return true if the piece was taken, false if it was dropped outright
     */
    @Override
    public boolean gotPiece(Peer peer, PartialPiece pp) {
        if (metainfo == null || storage == null || storage.isChecking() || halted) {
            pp.release();
            return true;
        }
        int piece = pp.getPiece();
        Piece wantedPiece;

        synchronized (wantedPieces) {
            wantedPiece = wantedMap.get(Integer.valueOf(piece));
            if (wantedPiece == null) {
                if (_log.shouldDebug()) {
                    _log.debug("Received unwanted piece [" + piece + "/" + metainfo.getPieces()
                               + "] from " + SnarkLogFormatter.peer(peer) + " for "
                                 + metainfo.getName());
                }

                // No need to announce have piece to peers.
                // Assume we got a good piece, we don't really care anymore.
                // Well, this could be caused by a change in priorities, so
                // only drop it here if we already have it, otherwise keep it.
                if (storage.getBitField().get(piece)) {
                    pp.release();
                    return true;
                }
            } else {
                // claim the piece so no other peer writes it while we do the disk I/O
                wantedMap.remove(Integer.valueOf(piece));
                wantedPieces.remove(wantedPiece);
            }
        }

        if (halted) {
            pp.release();
            return true;
        }

        // no log line here - writePieceNow() logs the piece once it knows whether
        // the hash check passed, and a line for the queueing alone reported no outcome
        if (!queuePieceWrite(peer, pp, wantedPiece)) {
            // Writer backed up for this torrent: apply backpressure by doing the
            // work on this thread rather than queueing more piece data.
            writePieceNow(peer, pp, wantedPiece);
        }
        return true;
    }

    /**
     * Verify and write one completed piece, then update the wanted set and announce
     * the have. Runs on the writer thread, or inline on a peer thread when the queue
     * is full. Never throws to a peer thread: a fatal storage error stops the torrent
     * and a transient one re-queues the piece.
     *
     * @param peer the peer that delivered the last block of the piece
     * @param pp the completed piece data, released here
     * @param wantedPiece the claimed wanted-piece record, or null if unclaimed
     */
    void writePieceNow(Peer peer, PartialPiece pp, Piece wantedPiece) {
        int piece = pp.getPiece();
        // try/catch outside the sync to avoid deadlock in the catch
        try {
            // try/catch moved outside of sync
            // this takes forever if complete, as it rechecks
            if (storage.putPiece(pp)) {
                if (_log.shouldDebug()) {
                    _log.debug("Piece " + piece + '/' + metainfo.getPieces()
                               + " verified -> Written * Peer: "
                               + SnarkLogFormatter.peer(peer)
                               + " * Torrent: " + SnarkLogFormatter.torrent(metainfo));
                }
            } else {
                // so we will try again
                removePartialPiece(piece); // just in case
                markUnrequested(peer, piece);
                // Mark this peer as not having the piece. PeerState will update its bitfield.
                synchronized (wantedPieces) {
                    if (wantedPiece != null) {
                        Piece dup = wantedMap.remove(Integer.valueOf(piece));
                        if (dup != null) {
                            wantedPieces.remove(dup);
                        } else {
                            wantedBytes += metainfo.getPieceLength(piece);
                        }
                        wantedPiece.removePeer(peer);
                        wantedPiece.setRequested(peer, false);
                        wantedPieces.add(wantedPiece);
                        wantedMap.put(Integer.valueOf(piece), wantedPiece);
                        wantedDirty = true;
                    }
                }
                if (_log.shouldDebug()) {
                    _log.debug("Piece " + piece + '/' + metainfo.getPieces()
                               + " corrupt -> Re-requested it * Peer: "
                               + SnarkLogFormatter.peer(peer)
                               + " * Torrent: " + SnarkLogFormatter.torrent(metainfo));
                }
                // The copy this peer sent us is corrupt; stop asking it for this
                // piece. Done here rather than in PeerState.pieceMessage because the
                // hash check no longer runs on the peer's own thread.
                PeerState st = peer.state;
                if (st != null) st.clearHave(piece);
                return; // No need to announce BAD piece to peers.
            }
            if (wantedPiece != null) {
                synchronized (wantedPieces) {
                    // discard a stale re-add from updatePiecePriorities during the write
                    Piece dup = wantedMap.remove(Integer.valueOf(piece));
                    if (dup != null) {
                        wantedPieces.remove(dup);
                        wantedBytes -= metainfo.getPieceLength(piece);
                    }
                    wantedBytes -= metainfo.getPieceLength(piece);
                    wantedDirty = true;
                }
            }
        } catch (IOException ioe) {
            String reason = StorageError.describe(ioe);
            if (isFatalStorageError(ioe)) {
                String msg =
                        "Storage error: "
                                + reason
                                + " writing [piece "
                                + piece
                                + "] for "
                                + metainfo.getName()
                                + "\n* Torrent stopped - resolve the storage problem and restart";
                _log.error(msg, ioe);
                if (listener != null) {
                    listener.addMessage(msg);
                    listener.addMessage("Fatal storage error: Stopping torrent " + metainfo.getName());
                }
                if (!I2PAppContext.getGlobalContext().isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                // deadlock was here
                snark.stopTorrent();
                throw new RuntimeException(msg, ioe);
            }
            // transient or stale-handle failure (e.g. EBADF from a read-only
            // handle, fd exhaustion, file vanished): keep the torrent alive,
            // release the piece so it is re-downloaded and the write retried
            String msg =
                    "Storage error: "
                            + reason
                            + " writing [piece "
                            + piece
                            + "] for "
                            + metainfo.getName()
                            + " - re-queuing piece, will retry";
            _log.error(msg, ioe);
            if (listener != null) {
                listener.addMessage(msg);
            }
            if (!I2PAppContext.getGlobalContext().isRouterContext()) {
                System.out.println(" • " + msg);
            }
            removePartialPiece(piece); // just in case
            markUnrequested(peer, piece);
            synchronized (wantedPieces) {
                if (wantedPiece != null) {
                    Piece dup = wantedMap.remove(Integer.valueOf(piece));
                    if (dup != null) {
                        wantedPieces.remove(dup);
                    } else {
                        wantedBytes += metainfo.getPieceLength(piece);
                    }
                    wantedPiece.removePeer(peer);
                    wantedPiece.setRequested(peer, false);
                    wantedPieces.add(wantedPiece);
                    wantedMap.put(Integer.valueOf(piece), wantedPiece);
                    wantedDirty = true;
                }
            }
            // Same reaction as the bad-hash path above: the piece is back in the
            // wanted set, so stop assuming this peer has it until it proves again.
            PeerState st = peer.state;
            if (st != null) st.clearHave(piece);
            return; // No need to announce BAD piece to peers.
        }
        // just in case
        removePartialPiece(piece);
        boolean done = wantedBytes <= 0;

        // Announce to the world we have it!
        // Disconnect from other seeders when we get the last piece
        List<Peer> toDisconnect = done ? new ArrayList<>() : null;
        for (Peer p : peers) {
            if (p.isConnected()) {
                if (done && p.isCompleted()) {
                    toDisconnect.add(p);
                } else {
                    p.have(piece);
                }
            }
        }
        if (done) {
            for (Peer p : toDisconnect) {
                p.disconnect(true);
            }

            // put msg on the console if partial, since Storage won't do it
            if (!completed()) {
                snark.storageCompleted(storage);
            }

            synchronized (wantedPieces) {
                for (PartialPiece ppp : partialPieces) {
                    ppp.release();
                }
                partialPieces.clear();
                partialIds.clear();
                clearLivePartials();
            }
        }
    }

    /**
     * Called when a choke message is received. This does nothing but logging.
     *
     * @param peer the peer that sent the choke or unchoke
     * @param choke true for choke, false for unchoke
     */
    @Override
    public void gotChoke(Peer peer, boolean choke) {
        if (_log.shouldInfo()) {
            _log.info("Received choke(" + choke + ") from " + SnarkLogFormatter.peer(peer));
        }
    }

    /**
     * Called when an interested message is received.
     *
     * @param peer the peer that sent the interested or uninterested message
     * @param interest true for interested, false for uninterested
     */
    @Override
    public void gotInterest(Peer peer, boolean interest) {
        if (interest) {
            if (storage == null || storage.getBitField().size() == 0) {
                return;
            } // XD bug #80
            if (uploaders.get() < allowedUploaders() && peer.isChoking() && !overUpBWLimit()) {
                uploaders.incrementAndGet();
                interestedUploaders.incrementAndGet();
                peer.setChoking(false);
                if (_log.shouldInfo()) {
                    _log.info("Unchoking " + SnarkLogFormatter.peer(peer));
                }
            }
        }
    }

    /**
     * Called when a peer disconnects.
     *
     * @param peer the disconnected peer
     */
    @Override
    public void disconnected(Peer peer) {
        if (_log.shouldInfo()) {
            _log.info("Disconnected peer " + SnarkLogFormatter.peer(peer));
        }

        synchronized (peers) {
            // Make sure it is no longer in our lists
            if (peers.remove(peer)) {
                // Unchoke some random other peer
                unchokePeer();
                removePeerFromPieces(peer);
            }
            peerCount = peers.size();
        }
    }

    /**
     * Called when a peer is removed, to prevent it from being used in rarest-first calculations.
     *
     * @param peer the peer being removed from all piece records
     */
    /**
     * Drops the peer from every wanted piece's availability and requester sets. A live
     * partial left with no requester by that unmarking is re-queued here, so a peer that
     * goes away without handing its blocks back does not strand the piece until the
     * checker's stall backstop runs. Package-visible for the tests.
     *
     * @param peer the peer that went away
     */
    void removePeerFromPieces(Peer peer) {
        synchronized (wantedPieces) {
            long now = System.currentTimeMillis();
            for (Piece piece : wantedPieces) {
                piece.removePeer(peer);
                piece.setRequested(peer, false);
                // A live partial whose last requester just went away is re-queued on the
                // spot instead of waiting for the checker's five-minute backstop. The map
                // lookup inside keeps this off every piece but the few in flight.
                parkIfOrphaned(piece.getId(), peer, now);
            }
            wantedDirty = true;
        }
    }

    /**
     * Save partial pieces on peer disconnection and hopefully restart it later. Replace a partial
     * piece in the List if the new one is bigger. Storage method is private so we can expand to
     * save multiple partials if we wish.
     *
     * <p>Also mark the piece unrequested if this peer was the only one.
     *
     * @param peer partials, must include the zero-offset (empty) ones too. No dup pieces. len field
     *             in Requests is ignored.
     * @since 0.8.2
     */
    @Override
    public void savePartialPieces(Peer peer, List<Request> partials) {
        if (_log.shouldInfo()) {
            _log.info("Partials received from " + SnarkLogFormatter.peer(peer) + " - " + partials);
        }
        if (halted || completed()) {
            for (Request req : partials) {
                PartialPiece pp = req.getPartialPiece();
                pp.release();
            }
            return;
        }

        synchronized (wantedPieces) {
            // One piece can be represented by several requests, one per block of it in
            // flight. Settle each piece once, so that a second request for it cannot
            // release the copy that was just kept.
            Map<Integer, PartialPiece> settled = null;
            for (Request req : partials) {
                PartialPiece pp = req.getPartialPiece();
                Integer key = Integer.valueOf(pp.getPiece());
                if (settled == null) settled = new HashMap<>(4);
                PartialPiece prev = settled.get(key);
                if (prev != null && prev != pp) {
                    // a second, different partial for a piece already dealt with
                    pp.release();
                    if (_log.shouldInfo()) {
                        _log.info("Discarding second partial piece for the same piece " + pp);
                    }
                    continue;
                }
                if (_livePartials.get(key) == pp) {
                    // Shared: several peers are pulling blocks of this piece, so this one
                    // going away only frees the blocks it was claiming. Park the piece as
                    // an orphan once nobody is fetching it, so the bytes already
                    // downloaded are not lost, and hand sole ownership to the list.
                    pp.clearRequested(req.off, req.len);
                    if (prev != null) {
                        continue;
                    }
                    settled.put(key, pp);
                    // syncs on wantedPieces, and parks the live copy as an orphan if this
                    // was the piece's last registered requester, so the bytes already
                    // downloaded are not lost and sole ownership goes to the list
                    markUnrequested(peer, pp.getPiece());
                    continue;
                }
                if (prev != null) {
                    continue;
                }
                settled.put(key, pp);
                if (pp.hasData()) {
                    // a partial coming to rest in the orphan list is scanned from the
                    // beginning by whoever picks it up, so nothing may stay claimed
                    pp.clearAllRequested();
                    parkOrphanLocked(pp);
                } else {
                    pp.release();
                } // drop the empty partial piece
                // syncs on wantedPieces...
                markUnrequested(peer, pp.getPiece());
            }
            if (_log.shouldInfo()) {
                _log.info("Partial list size now: " + partialPieces.size());
            }
        }
    }

    /**
     * Return partial piece to the PeerState if it's still wanted and peer has it.
     *
     * @param havePieces pieces the peer has, the rv will be one of these
     * @return PartialPiece or null
     * @since 0.8.2
     */
    @Override
    public PartialPiece getPartialPiece(Peer peer, BitField havePieces) {
        return getPartialPiece(peer, havePieces, null);
    }

    /**
     * Return partial piece to the PeerState if it's still wanted and peer has it.
     *
     * <p>When the allowed set is not null, only pieces in it are eligible, for requesting BEP 6
     * allowed fast pieces from a peer that chokes us.
     *
     * @param havePieces pieces the peer has, the rv will be one of these
     * @param allowed the pieces servable while choked, or null for no restriction
     * @return PartialPiece or null
     * @since 0.9.71+
     */
    @Override
    public PartialPiece getPartialPiece(
            Peer peer, BitField havePieces, Set<Integer> allowed) {
        if (metainfo == null || (storage != null && storage.isChecking())) {
            return null;
        }
        synchronized (wantedPieces) {
            // sorts by preference, highest first
            Collections.sort(partialPieces);
            for (Iterator<PartialPiece> iter = partialPieces.iterator(); iter.hasNext(); ) {
                PartialPiece pp = iter.next();
                int savedPiece = pp.getPiece();
                if (allowed != null && !allowed.contains(Integer.valueOf(savedPiece))) {
                    continue; // BEP 6: only allowed fast pieces while choked
                }
                if (peer.dontHave(savedPiece)) {
                    continue; // BEP 54: peer said it no longer has this piece
                }
                if (havePieces.get(savedPiece)) {
                    // this is just a double-check, it should be in there
                    boolean skipped = false;
                    outer:
                    for (Piece piece : wantedPieces) {
                        if (piece.getId() == savedPiece) {
                            if (peer.isCompleted()
                                    && piece.getPeerCount() > 1
                                    && wantedPieces.size() > 2 * END_GAME_THRESHOLD
                                    && partialPieces.size() < 4
                                    && _random.nextInt(4) != 0) {

                                // Try to preserve rarest-first
                                // by not requesting a partial piece that at least two non-seeders
                                // also have
                                // from a seeder
                                int nonSeeds = 0;
                                int seeds = 0;
                                for (Peer pr : peers) {
                                    if (pr.isCompleted()) {
                                        if (++seeds >= 4) {
                                            break;
                                        }
                                    } else {
                                        // TODO don't access state directly
                                        PeerState state = pr.state;
                                        if (state == null) {
                                            continue;
                                        }
                                        BitField bf = state.bitfield;
                                        if (bf == null) {
                                            continue;
                                        }
                                        if (bf.get(savedPiece) && ++nonSeeds > 1) {
                                            skipped = true;
                                            break outer;
                                        }
                                    }
                                }
                            }
                            iter.remove();
                            registerLive(pp);
                            piece.setRequested(peer, true);
                            if (_log.shouldInfo()) {
                                // the piece and the peer it goes back to - the
                                // tuple the object printed named none of its four
                                // numbers, and the raw Peer toString is a base64
                                // tag the log viewer cannot link
                                _log.info("Restoring an orphaned partial piece -> "
                                          + SnarkLogFormatter.partial(pp)
                                          + " * Peer: " + SnarkLogFormatter.peer(peer)
                                          + "\n* Torrent: "
                                          + SnarkLogFormatter.torrent(metainfo));
                            }
                            return pp;
                        }
                    }
                    if (_log.shouldDebug()) {
                        if (skipped) {
                            _log.debug("Skipping a partial piece offered by a seeder -> "
                                       + SnarkLogFormatter.partial(pp)
                                       + " * Peer: " + SnarkLogFormatter.peer(peer)
                                       + "\n* Two non-seeders already hold it");
                        } else {
                            _log.debug("Partial piece is not in the wanted list -> "
                                       + SnarkLogFormatter.partial(pp)
                                       + " * Torrent: "
                                       + SnarkLogFormatter.torrent(metainfo));
                        }
                    }
                }
            }
            if (_log.shouldDebug() && !partialPieces.isEmpty())
                _log.debug("Peer " + SnarkLogFormatter.peer(peer)
                           + " has none of our partial pieces -> Torrent: "
                           + SnarkLogFormatter.torrent(metainfo)
                           + SnarkLogFormatter.partialList(partialPieces, "Ours"));
        }
        // ...and this section turns this into the general move-requests-around code!
        // Temporary? So PeerState never calls wantPiece() directly for now...
        Piece piece = wantPiece(peer, havePieces, true, allowed);
        if (piece != null) {
            int pieceNum = piece.getId();
            synchronized (wantedPieces) {
                // If the piece is already being pulled, hand out the same one so this
                // peer fills the blocks the others have not claimed yet, rather than a
                // duplicate copy of the whole piece.
                PartialPiece live = _livePartials.get(Integer.valueOf(pieceNum));
                if (live != null) {
                    if (live.isFullyClaimed()) {
                        // Every block is already here or in flight with another peer, so
                        // there is nothing for this one to fetch. Undo the bookkeeping
                        // wantPiece() did so the piece is not counted against this peer.
                        piece.setRequested(peer, false);
                        if (_log.shouldDebug()) {
                            _log.debug("Nothing left to share of a partial piece -> "
                                       + SnarkLogFormatter.partial(live)
                                       + " * Peer: " + SnarkLogFormatter.peer(peer));
                        }
                        return null;
                    }
                    if (_log.shouldInfo()) {
                        _log.info("Sharing a partial piece another peer is pulling -> "
                                  + SnarkLogFormatter.partial(live)
                                  + " * Peer: " + SnarkLogFormatter.peer(peer)
                                  + "\n* Torrent: " + SnarkLogFormatter.torrent(metainfo));
                    }
                    return live;
                }
                return registerLive(
                        new PartialPiece(
                                piece,
                                metainfo.getPieceLength(pieceNum),
                                _util.getTempDir(),
                                metainfo));
            }
        }
        // no line here - wantPiece() returns null only after logging why, and
        // the piece-already-claimed path above logs its own, so this fired as a
        // second, context-free copy of a finding already on the line before it
        return null;
    }

    /**
     * Allocates a PartialPiece for a specific suggested piece, or returns null if the piece is not
     * wanted, is already being fetched, or the peer is already fetching it. Used for BEP 6 suggest
     * handling.
     *
     * @param peer the peer that suggested the piece
     * @param piece the suggested piece number
     * @return a new PartialPiece to request, or null
     * @since 0.9.71+
     */
    @Override
    public PartialPiece getPartialPiece(Peer peer, int piece) {
        if (metainfo == null || (storage != null && storage.isChecking())) {
            return null;
        }
        synchronized (wantedPieces) {
            Integer key = Integer.valueOf(piece);
            // never create a second partial for a piece already being downloaded
            PartialPiece live = _livePartials.get(key);
            if (live == null && partialIds.contains(key)) {
                return null;
            }
            Piece p = wantedMap.get(key);
            if (p == null || p.isDisabled() || p.isRequestedBy(peer)) {
                return null;
            }
            if (live != null && live.isFullyClaimed()) {
                return null; // every block is here or in flight with someone else
            }
            if (p.isRequested() && live == null && wantedPieces.size() > END_GAME_THRESHOLD) {
                return null; // not in end game, already requested from another peer
            }
            if (p.getRequestCount()
                    >= parallelRequestCap(metainfo.getPieceLength(piece), PeerState.PARTSIZE)) {
                return null;
            }
            p.setRequested(peer, true);
            if (_log.shouldInfo()) {
                _log.info("Suggest: requesting piece [" + p + "] from "
                          + SnarkLogFormatter.peer(peer));
            }
            return live != null
                    ? live
                    : registerLive(
                            new PartialPiece(
                                    p, metainfo.getPieceLength(piece), _util.getTempDir(), metainfo));
        }
    }

    /**
     * Called when we are downloading from the peer and may need to ask for a new piece. Returns
     * true if {@link #wantPiece} or getPartialPiece() would return a piece.
     *
     * @param peer the Peer that will be asked to provide the piece.
     * @param havePieces a BitField containing the pieces that the other side has.
     * @return if we want any of what the peer has
     * @since 0.8.2
     */
    @Override
    public boolean needPiece(Peer peer, BitField havePieces) {
        synchronized (wantedPieces) {
            for (PartialPiece pp : partialPieces) {
                int savedPiece = pp.getPiece();
                if (havePieces.get(savedPiece)) {
                    // this is just a double-check, it should be in there
                    for (Piece piece : wantedPieces) {
                        if (piece.getId() == savedPiece) {
                            if (_log.shouldInfo()) {
                                _log.info("We could restore orphaned partial piece " + pp);
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return wantPiece(peer, havePieces, false) != null;
    }

    /**
     * Remove saved state for this piece - the orphan copy, the live copy, and the requests
     * the peers still hold for it. Unless we are in the end game there shouldn't be anything
     * in there. Do not call with wantedPieces lock held (deadlock). Package-visible so the
     * tests can pin that the cancel runs while the live copy is still registered.
     *
     * @param piece the piece number whose saved partial data is dropped
     */
    void removePartialPiece(int piece) {
        PartialPiece live;
        synchronized (wantedPieces) {
            for (Iterator<PartialPiece> iter = partialPieces.iterator(); iter.hasNext(); ) {
                PartialPiece pp = iter.next();
                if (pp.getPiece() == piece) {
                    iter.remove();
                    pp.release();
                    // there should be only one but keep going to be sure
                }
            }
            partialIds.remove(Integer.valueOf(piece));
            live = _livePartials.remove(Integer.valueOf(piece));
        }
        if (live != null) {
            // Peers are still pulling blocks of this piece. Tell them to stop before the
            // data goes away: cancelPiece() drops their requests and their claims.
            // Not under the wantedPieces monitor - PeerState takes it the other way round.
            live.clearAllRequested();
            cancelPieceRequests(piece);
            live.setShared(false);
            live.release();
        }
    }

    /**
     * Tells every peer with an outstanding request for this piece that it no longer has to
     * send it. Used when the piece is finished or thrown away while other peers were still
     * pulling blocks of it; without this those requests sit until the peer is judged
     * stalled. Sends a cancel to each, which is what every other client does here.
     *
     * @param piece the piece no longer being fetched
     */
    private void cancelPieceRequests(int piece) {
        for (Peer p : peers) {
            PeerState ps = p.state;
            if (ps != null) {
                ps.cancelPiece(piece);
            }
        }
        // cancelPiece() drops the requests; this drops the bookkeeping that says the
        // peer still has blocks of the piece in flight, so the piece is not counted as
        // being fetched by anyone
        synchronized (wantedPieces) {
            Piece pc = wantedMap.get(Integer.valueOf(piece));
            if (pc != null) {
                for (Peer p : peers) {
                    pc.setRequested(p, false);
                }
            }
        }
    }

    /**
     * Registers a partial piece as the one being downloaded for its piece id and makes the
     * coordinator the owner of its data. The caller holds the wantedPieces monitor.
     *
     * @param pp the piece to register
     * @return the same piece, for use in a return statement
     */
    private PartialPiece registerLive(PartialPiece pp) {
        // an orphan being put back to work is scanned from the beginning again, and a
        // fresh piece has nothing claimed yet
        pp.clearAllRequested();
        pp.setShared(true);
        _livePartials.put(Integer.valueOf(pp.getPiece()), pp);
        return pp;
    }

    /**
     * Frees every registered live partial piece, for when the torrent is done or stopped.
     * The caller holds the wantedPieces monitor.
     */
    private void clearLivePartials() {
        for (PartialPiece pp : _livePartials.values()) {
            pp.setShared(false);
            pp.release();
        }
        _livePartials.clear();
    }

    /**
     * True if some peer other than nobody is still fetching blocks of this piece.
     * The caller holds the wantedPieces monitor.
     *
     * @param piece the piece to check
     * @return true if at least one peer has requested it
     */
    private boolean someoneFetching(int piece) {
        Piece p = wantedMap.get(Integer.valueOf(piece));
        return p != null && p.getRequestCount() > 0;
    }

    /**
     * Puts a partial piece that has come to rest into the orphan list: added if the list
     * holds no copy of the piece, replacing the copy there if this one holds more data,
     * discarded if it holds no more. The list is then trimmed to the torrent's connection
     * count, dropping the least preferred copy. The caller holds the wantedPieces monitor
     * and has already cleared the piece's claims and un-shared it.
     *
     * @param pp the partial piece to park, not null
     * @since 0.9.72+
     */
    private void parkOrphanLocked(PartialPiece pp) {
        // PartialPiece.equals() only compares piece number, which is what we want
        int idx = partialPieces.indexOf(pp);
        boolean kept;
        if (idx < 0) {
            partialPieces.add(pp);
            partialIds.add(Integer.valueOf(pp.getPiece()));
            kept = true;
        } else if (pp.getDownloaded() > partialPieces.get(idx).getDownloaded()) {
            // replace what's there now
            partialPieces.get(idx).release();
            partialPieces.set(idx, pp);
            kept = true;
        } else {
            pp.release();
            kept = false;
        }
        if (_log.shouldInfo()) {
            if (kept) {
                _log.info("Parked an orphaned partial piece -> Piece: " + pp.getPiece()
                          + " * Downloaded: " + pp.getDownloaded() + " bytes * Orphans: "
                          + partialPieces.size());
            } else {
                _log.info("Discarded an orphaned partial piece -> A parked copy holds more"
                          + "\n* Piece: " + pp.getPiece() + " * Downloaded: "
                          + pp.getDownloaded() + " bytes");
            }
        }
        int max = getMaxConnections();
        if (partialPieces.size() > max) {
            // sorts by preference, highest first
            Collections.sort(partialPieces);
            PartialPiece gone = partialPieces.remove(partialPieces.size() - 1);
            partialIds.remove(Integer.valueOf(gone.getPiece()));
            gone.release();
            if (_log.shouldInfo()) {
                _log.info("Discarded an orphaned partial piece -> Orphan list full"
                          + " * Piece: " + gone.getPiece() + " * Orphans: "
                          + partialPieces.size());
            }
        }
    }

    /**
     * Re-queues the live partial of a piece that no registered requester is fetching, so
     * its blocks go back in the pool and the bytes already downloaded stay where the
     * orphan-adoption path can hand them out again. Does nothing while some peer is still
     * registered for the piece, or when the piece has no live partial.
     *
     * <p>No cancel is sent to the peers that were pulling blocks of it. Every path that
     * clears a piece's requester set - markUnrequested(), removePeerFromPieces() and
     * getPartialPiece() turning a peer away - hands back or drops that peer's requests on
     * the way, so no peer is left holding a reference to the parked copy. Issuing a cancel
     * here is not an option either: PeerState takes the wantedPieces monitor the other way
     * round, and the callers of removePartialPiece() step outside the monitor to cancel.
     *
     * @param piece the piece to re-queue
     * @param peer the peer whose unmarking left the piece alone, null when the checker
     *             sweep found it idle instead
     * @param now the current time in milliseconds, for the log's idle time
     * @return true if a live partial was re-queued
     * @since 0.9.72+
     */
    private boolean parkIfOrphaned(int piece, Peer peer, long now) {
        PartialPiece pp = _livePartials.get(Integer.valueOf(piece));
        if (pp == null || someoneFetching(piece)) {
            return false;
        }
        _livePartials.remove(Integer.valueOf(piece));
        pp.clearAllRequested();
        pp.setShared(false);
        parkOrphanLocked(pp);
        if (_log.shouldWarn()) {
            Piece p = wantedMap.get(Integer.valueOf(piece));
            String reason;
            if (peer != null) {
                reason = "Last peer left * Peer: " + SnarkLogFormatter.peer(peer);
            } else {
                long idle = p != null ? now - p.getLastActive() : 0;
                reason = "No requester for " + DataHelper.formatDuration(idle);
            }
            _log.warn("Re-queued a partial piece -> " + reason + " * Piece: " + piece
                      + " * Downloaded: " + pp.getDownloaded() + " bytes");
        }
        return true;
    }

    /**
     * How many bytes the parked copy of a piece holds, or -1 if the piece is not parked.
     * For the tests.
     *
     * @param piece the piece to look for in the orphan list
     * @return the bytes downloaded in the parked copy, -1 if there is none
     * @since 0.9.72+
     */
    int orphanBytes(int piece) {
        synchronized (wantedPieces) {
            for (PartialPiece pp : partialPieces) {
                if (pp.getPiece() == piece) {
                    return pp.getDownloaded();
                }
            }
            return -1;
        }
    }

    /**
     * Whether the last few pieces are in flight - the point at which the peers that hold
     * them are the only sources left, and choking one of them costs more than the upload
     * slot it frees. See {@link #END_GAME_THRESHOLD}.
     *
     * <p>The count is of the pieces still on the wanted list, so a piece the user disabled
     * counts too: a torrent with 33 wanted pieces of which 32 are disabled is not in the
     * end game by this test, even though only one piece is really left to fetch.
     *
     * @return true while pieces are still wanted and no more than the end-game threshold
     *         of them is left
     * @since 0.9.72+
     */
    boolean inEndGame() {
        synchronized (wantedPieces) {
            int left = wantedPieces.size();
            return left > 0 && left <= END_GAME_THRESHOLD;
        }
    }

    /**
     * Whether this peer holds a piece we still want, with no side effects and no logging,
     * so the choke policy can ask it about every peer on every pass. The scan stops at the
     * first disabled piece, since wantedPieces is sorted by priority, but it is still a
     * scan of every wanted piece - ask only when {@link #inEndGame()} says the last few
     * pieces are in flight.
     *
     * @param peer the peer to ask about, not null
     * @return true if the peer's bitfield covers a wanted piece, false if we have no
     *         bitfield for it or it holds nothing we want
     * @since 0.9.72+
     */
    boolean hasSomethingWeNeed(Peer peer) {
        PeerState st = peer.state;
        if (st == null) {
            return false;
        }
        BitField bf = st.bitfield;
        if (bf == null) {
            return false;
        }
        synchronized (wantedPieces) {
            for (Piece p : wantedPieces) {
                // sorted by priority, so when we hit a disabled piece we are done
                if (p.isDisabled()) {
                    break;
                }
                if (bf.get(p.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How many pieces are registered as being downloaded right now, and how many are
     * parked as orphans. For the tests.
     *
     * @return the count of live partial pieces
     * @since 0.9.72+
     */
    int livePartialCount() {
        synchronized (wantedPieces) {
            return _livePartials.size();
        }
    }

    /**
     * How many partial pieces are parked with no peer fetching them. For the tests.
     *
     * @return the count of orphaned partial pieces
     * @since 0.9.72+
     */
    int orphanPartialCount() {
        synchronized (wantedPieces) {
            return partialPieces.size();
        }
    }

    /**
     * The wanted piece record for a piece number, or null if we are not missing it. For
     * the tests.
     *
     * @param piece the piece number to look up
     * @return the piece record, null if the piece is not wanted
     * @since 0.9.72+
     */
    Piece wantedPiece(int piece) {
        synchronized (wantedPieces) {
            return wantedMap.get(Integer.valueOf(piece));
        }
    }

    /**
     * Clear the requested flag for a piece.
     *
     * @param peer the peer the piece was requested from
     * @param piece the piece number to clear the flag for
     */
    private void markUnrequested(Peer peer, int piece) {
        synchronized (wantedPieces) {
            Piece pc = wantedMap.get(Integer.valueOf(piece));
            if (pc != null) {
                pc.setRequested(peer, false);
                // The piece is nobody's business again as soon as its last requester is
                // gone, so re-queue its live copy here rather than waiting out the
                // checker's five-minute stall backstop.
                parkIfOrphaned(piece, peer, System.currentTimeMillis());
            }
        }
    }

    /**
     * Checks if the piece has been requested but has received no data for longer than the timeout.
     *
     * @param p the piece to check
     * @param now the current time in milliseconds
     * @param timeout the stall timeout in milliseconds
     * @return true if the piece is requested and stale
     * @since 0.9.71+
     */
    static boolean isStalled(Piece p, long now, long timeout) {
        return p.isRequested() && now - p.getLastActive() > timeout;
    }

    /**
     * Re-queues any requested pieces that have received no data for PIECE_STALL_TIMEOUT, so they
     * can be requested from other peers. Called periodically by PeerCheckerTask.
     *
     * @param now the current time in milliseconds
     * @since 0.9.71+
     */
    void checkStalledPieces(long now) {
        if (halted) {
            return;
        }
        List<StalledPiece> stalled = null;
        synchronized (wantedPieces) {
            for (Piece p : wantedPieces) {
                if (isStalled(p, now, PIECE_STALL_TIMEOUT)) {
                    if (stalled == null) {
                        stalled = new ArrayList<>(2);
                    }
                    stalled.add(new StalledPiece(p, p.getRequesters()));
                }
            }
            // A live partial that no registered requester is fetching is invisible to
            // every other path: wantPiece() skips a piece that already has a partial of
            // its own, and getPartialPiece() refuses to share one whose blocks are all
            // claimed. Nothing else reaps it, so it is parked here. The idle pieces are
            // collected first - parking removes from the map being walked.
            List<Integer> idle = null;
            for (Map.Entry<Integer, PartialPiece> entry : _livePartials.entrySet()) {
                Piece p = wantedMap.get(entry.getKey());
                // a piece we no longer want is dealt with when it is finished
                if (p == null || now - p.getLastActive() <= PIECE_STALL_TIMEOUT) {
                    continue;
                }
                if (idle == null) {
                    idle = new ArrayList<>(2);
                }
                idle.add(entry.getKey());
            }
            if (idle != null) {
                for (Integer key : idle) {
                    parkIfOrphaned(key.intValue(), null, now);
                }
            }
        }
        if (stalled != null) {
            for (StalledPiece sp : stalled) {
                expireStalledPiece(sp.piece, sp.requesters, now);
            }
        }
    }

    /**
     * Cancels the outstanding requests for a stalled piece and returns its partial data to the
     * partial piece list, so the piece can be re-requested from other peers.
     *
     * @param p the stalled piece
     * @param requesters snapshot of the peers requesting it, taken under the wantedPieces lock
     * @param now the current time in milliseconds, for the log's idle time
     * @since 0.9.71+
     */
    private void expireStalledPiece(Piece p, Set<PeerID> requesters, long now) {
        for (Peer peer : peerList()) {
            if (!requesters.contains(peer.getPeerID())) {
                continue;
            }
            PeerState st = peer.state;
            if (st == null) {
                continue;
            }
            st.out.cancelRequestMessages(p.getId());
            List<Request> pcs = st.returnPartialPieces(p.getId());
            if (pcs.isEmpty()) {
                markUnrequested(peer, p.getId());
                continue;
            }
            savePartialPieces(peer, pcs);
            if (_log.shouldWarn()) {
                _log.warn("Re-queued a stalled piece -> No data received * Piece: " + p.getId()
                          + " * Peer: " + SnarkLogFormatter.peer(peer)
                          + " * Idle: " + DataHelper.formatDuration(now - p.getLastActive()));
            }
        }
    }

    /** A stalled piece and the snapshot of its requesters, taken under the wantedPieces lock. */
    private static class StalledPiece {
        /** The piece that stalled. */
        final Piece piece;
        /** Peers that had the piece requested. */
        final Set<PeerID> requesters;

        /**
         * Records one stalled piece.
         *
         * @param piece the piece that stalled
         * @param requesters the peers it was requested from
         */
        StalledPiece(Piece piece, Set<PeerID> requesters) {
            this.piece = piece;
            this.requesters = requesters;
        }
    }

    /**
     * PeerListener callback.
     *
     * @since 0.8.4
     */
    @Override
    public void gotExtension(Peer peer, int id, byte[] bs) {
        if (_log.shouldDebug()) {
            _log.debug("Received extension message " + id + " from " + SnarkLogFormatter.peer(peer));
        }

        // Basic handling done in PeerState... here we just check if we are done
        if (metainfo == null && id == ExtensionHandler.ID_METADATA) {
            synchronized (magnetState) {
                if (magnetState.isComplete()) {
                    if (_log.shouldInfo()) {
                        _log.info("Received completed metainfo via extension");
                    }
                    metainfo = magnetState.getMetaInfo();
                    listener.gotMetaInfo(this, metainfo);
                }
            }
        } else if (id == ExtensionHandler.ID_HANDSHAKE) {
            // We may not have the bitfield yet, but if we do, don't send PEX to seeds
            if (!peer.isCompleted()) {
                sendPeers(peer);
            }
            sendDHT(peer);
            if (_util.utCommentsEnabled()) {
                sendCommentReq(peer);
            }
        }
    }

    /**
     * Send a PEX message to the peer, if he supports PEX. This sends everybody we have connected to
     * since the last time we sent PEX to him.
     *
     * @param peer the connection to advertise peers to; needs the PEX extension, non-private torrent
     * @since 0.8.4
     */
    void sendPeers(Peer peer) {
        if (metainfo != null && metainfo.isPrivate()) {
            return;
        }
        Map<String, BEValue> handshake = peer.getHandshakeMap();
        if (handshake == null) {
            return;
        }
        BEValue bev = handshake.get("m");
        if (bev == null) {
            return;
        }
        try {
            if (bev.getMap().get(ExtensionHandler.TYPE_PEX) != null) {
                List<Peer> pList = new ArrayList<>();
                long t = peer.getPexLastSent();
                for (Peer p : peers) {
                    if (p.equals(peer) || p.isWebPeer()) {
                        continue;
                    }
                    if (p.getWhenConnected() > t) {
                        pList.add(p);
                    }
                }
                if (!pList.isEmpty()) {
                    ExtensionHandler.sendPEX(peer, pList);
                    peer.setPexLastSent(_util.getContext().clock().now());
                }
            }
        } catch (InvalidBEncodingException ibee) { /* ignored */ }
    }

    /**
     * Send a DHT message to the peer, if we both support DHT.
     *
     * @param peer the peer to send to
     */
    void sendDHT(Peer peer) {
        DHT dht = _util.getDHTForTorrent(getInfoHash());
        if (dht == null) {
            return;
        }
        Map<String, BEValue> handshake = peer.getHandshakeMap();
        if (handshake == null) {
            return;
        }
        BEValue bev = handshake.get("m");
        if (bev == null) {
            return;
        }
        try {
            if (bev.getMap().get(ExtensionHandler.TYPE_DHT) != null) {
                ExtensionHandler.sendDHT(peer, dht.getPort(), dht.getRPort());
            }
        } catch (InvalidBEncodingException ibee) { /* ignored */ }
    }

    /**
     * Send a comment request message to the peer, if he supports it.
     *
     * @param peer the connection to ask for comments; needs the comment extension advertised
     * @since 0.9.31
     */
    void sendCommentReq(Peer peer) {
        Map<String, BEValue> handshake = peer.getHandshakeMap();
        if (handshake == null) {
            if (wantedBytes == 0
                    && _commentsNotRequested.incrementAndGet() >= MAX_COMMENT_NOT_REQ) {
                _commentsLastRequested.set(_util.getContext().clock().now());
            }
            return;
        }
        BEValue bev = handshake.get("m");
        if (bev == null) {
            if (wantedBytes == 0
                    && _commentsNotRequested.incrementAndGet() >= MAX_COMMENT_NOT_REQ) {
                _commentsLastRequested.set(_util.getContext().clock().now());
            }
            return;
        }

        // TODO if peer hasn't been connected very long, don't bother
        // unless forced at handshake time (see above)
        try {
            if (bev.getMap().get(ExtensionHandler.TYPE_COMMENT) != null) {
                int sz = 0;
                CommentSet comments = snark.getComments();
                if (comments != null) {
                    synchronized (comments) {
                        sz = comments.size();
                    }
                }
                _commentsNotRequested.set(0);
                _commentsLastRequested.set(_util.getContext().clock().now());
                if (sz >= CommentSet.MAX_SIZE) {
                    return;
                }
                ExtensionHandler.sendCommentReq(peer, CommentSet.MAX_SIZE - sz);
            } else {
                // failsafe to prevent seed excessively connecting out to a swarm for comments
                // when nobody in the swarm supports comments
                if (wantedBytes == 0
                        && _commentsNotRequested.incrementAndGet() >= MAX_COMMENT_NOT_REQ) {
                    _commentsLastRequested.set(_util.getContext().clock().now());
                }
            }
        } catch (InvalidBEncodingException ibee) { /* ignored */ }
    }

    /**
     * Store the storage after transition out of magnet mode. Snark calls this after we call
     * gotMetaInfo().
     *
     * @param stg storage built from the metainfo that just arrived; wanted pieces recomputed
     * @since 0.8.4
     */
    public void setStorage(Storage stg) {
        storage = stg;
        setWantedPieces();
        // ok we should be in business
        for (Peer p : peers) {
            p.setMetaInfo(metainfo);
        }
    }

    /**
     * PeerListener callback Tell the DHT to ping it, this will get back the node info.
     *
     * @param rport must be port + 1
     * @since 0.8.4
     */
    @Override
    public void gotPort(Peer peer, int port, int rport) {
        DHT dht = _util.getDHTForTorrent(getInfoHash());
        if (dht != null && port > 0 && port < 65535 && rport == port + 1) {
            dht.ping(peer.getDestination(), port);
        }
    }

    /**
     * Handle peers from PEX; a PeerListener callback.
     *
     * @since 0.8.4
     */
    @Override
    public void gotPeers(Peer peer, List<PeerID> peers) {
        if (!needOutboundPeers()) {
            return;
        }
        Destination myDest = _util.getMyDestination(getInfoHash());
        if (myDest == null) {
            return;
        }
        byte[] myHash = myDest.calculateHash().getData();
        List<Peer> pList = peerList();
        for (PeerID id : peers) {
            if (peerIDInList(id, pList) != null) {
                continue;
            }
            if (DataHelper.eq(myHash, id.getDestHash())) {
                continue;
            }
            pexPeers.add(id);
        }
        // TrackerClient will poll for pexPeers and do the add in its thread,
        // rather than running another thread here.
    }

    /**
     * Called when comments are requested via ut_comment.
     *
     * @since 0.9.31
     */
    @Override
    public void gotCommentReq(Peer peer, int num) {
        /* TODO cache per-torrent setting, use it instead */
        if (!_util.utCommentsEnabled()) {
            return;
        }
        CommentSet comments = snark.getComments();
        if (comments != null) {
            int lastSent = peer.getTotalCommentsSent();
            int sz;
            synchronized (comments) {
                sz = comments.size();
                // only send if we have more than last time
                if (sz <= lastSent) {
                    return;
                }
                ExtensionHandler.locked_sendComments(peer, num, comments);
            }
            peer.setTotalCommentsSent(sz);
        }
    }

    /**
     * Called when comments are received via ut_comment.
     *
     * @param comments non-null
     * @since 0.9.31
     */
    @Override
    public void gotComments(Peer peer, List<Comment> comments) {
        /* TODO cache per-torrent setting, use it instead */
        if (!_util.utCommentsEnabled()) {
            return;
        }
        if (!comments.isEmpty()) {
            snark.addComments(comments);
        }
    }

    /**
     * Called by {@link TrackerClient}.
     *
     * @return the Set itself, modifiable, not a copy, caller should clear()
     * @since 0.8.4
     */
    Set<PeerID> getPEXPeers() {
        return pexPeers;
    }

    /**
     * Return number of allowed uploaders for this torrent.
     * Check with Snark to see if we are over
     * the total upload limit.
     *
     * @return the current uploader count plus one, capped at MAX_UPLOADERS, or one
     * less than the current count while Snark reports the total upload limit exceeded
     */
    public int allowedUploaders() {
        int up = uploaders.get();
        if (listener != null && listener.overUploadLimit(interestedUploaders.get())) {
            if (_log.shouldDebug()) {
                _log.debug("Over the global upload limit -> Allowing one fewer uploader"
                           + " * Was: " + up + " peers");
            }
            return up - 1;
        } else if (up < MAX_UPLOADERS) {
            return up + 1;
        } else {
            return MAX_UPLOADERS;
        }
    }

    /**
     * Uploaders whether interested or not Use this for per-torrent limits.
     *
     * @return the uploader count, capped at the connected peer count
     * @since 0.8.4
     */
    public int getUploaders() {
        int rv = uploaders.get();
        if (rv > 0) {
            int max = getPeers();
            if (rv > max) {
                rv = max;
            }
        }
        return rv;
    }

    /**
     * Uploaders, interested only. Use this to calculate the global total, so that unchoked but
     * uninterested peers don't count against the global limit.
     *
     * @return the interested-uploader count, capped at the connected peer count
     * @since 0.9.28
     */
    public int getInterestedUploaders() {
        int rv = interestedUploaders.get();
        if (rv > 0) {
            int max = getPeers();
            if (rv > max) {
                rv = max;
            }
        }
        return rv;
    }

    /**
     * Record the uploader and interested-uploader counts.
     *
     * @param upl whether interested or not
     * @param inter the interested-only subset of that count, also capped at the maximum
     * @since 0.9.28
     */
    public void setUploaders(int upl, int inter) {
        if (upl < 0) {
            upl = 0;
        } else if (upl > MAX_UPLOADERS) {
            upl = MAX_UPLOADERS;
        }
        uploaders.set(upl);
        if (inter < 0) {
            inter = 0;
        } else if (inter > MAX_UPLOADERS) {
            inter = MAX_UPLOADERS;
        }
        interestedUploaders.set(inter);
    }

    /**
     * Decrement the uploaders and (if set) the interestedUploaders counts.
     *
     * @param isInterested true to also decrement the interested uploader count
     * @since 0.9.28
     */
    public void decrementUploaders(boolean isInterested) {
        int up = uploaders.decrementAndGet();
        if (up < 0) {
            uploaders.set(0);
        }
        if (isInterested) {
            up = interestedUploaders.decrementAndGet();
            if (up < 0) {
                interestedUploaders.set(0);
            }
        }
    }

    /**
     * The current value.
     *
     * @return the number of peers that are interested but choked
     * @since 0.9.28
     */
    public int getInterestedAndChoking() {
        return interestedAndChoking.get();
    }

    /**
     * Shift the interested-and-choking count by a delta, as the checker drops peers
     * without a full recount.
     *
     * @param toAdd the number of peers just removed from the interested set
     * @since 0.9.28
     */
    public void addInterestedAndChoking(int toAdd) {
        interestedAndChoking.addAndGet(toAdd);
    }

    /**
     * Returns the shared torrent utility this coordinator was built with.
     *
     * @return the shared I2PSnarkUtil, common to every torrent in this router
     * @since 0.9.2
     */
    @Override
    public I2PSnarkUtil getUtil() {
        return _util;
    }

    /**
     * Ban a web peer for this torrent, for while or permanently.
     *
     * @param host the hostname to ban
     * @param isPermanent true to ban until restart, false to ban for {@link #WEBPEER_BAN_TIME}
     * @since 0.9.49
     */
    public synchronized void banWebPeer(String host, boolean isPermanent) {
        if (_webPeerBans == null) {
            _webPeerBans = new HashMap<>(4);
        }
        Long time;
        if (isPermanent) {
            time = Long.valueOf(Long.MAX_VALUE);
        } else {
            long now = _util.getContext().clock().now();
            time = Long.valueOf(now + WEBPEER_BAN_TIME);
        }
        Long old = _webPeerBans.put(host, time);
        if (old != null && old.longValue() > time) {
            _webPeerBans.put(host, old);
        }
    }

    /**
     * Is a web peer banned?
     *
     * @param host the host name to look up in the web peer ban map
     * @return whether web peer banned
     * @since 0.9.49
     */
    public synchronized boolean isWebPeerBanned(String host) {
        if (_webPeerBans == null) {
            return false;
        }
        Long time = _webPeerBans.get(host);
        if (time == null) {
            return false;
        }
        long now = _util.getContext().clock().now();
        boolean rv = time.longValue() > now;
        if (!rv) {
            _webPeerBans.remove(host);
        }
        return rv;
    }

    /**
     * Is the given hash banned?
     *
     * @param h the torrent's info hash, checked against the ban list and bad-tracker count
     * @return true if banned
     * @since 0.9.71
     */
    public boolean isBanned(Hash h) {
        return listener.isBanned(h);
    }

    /**
     * Ban the given hash.
     *
     * @param h the torrent's info hash to add to the ban list
     * @since 0.9.71
     */
    public void ban(Hash h) {
        listener.ban(h);
    }
}
