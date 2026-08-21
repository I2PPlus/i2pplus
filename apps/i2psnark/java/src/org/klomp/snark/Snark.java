/* Snark - Main snark program startup class.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import net.i2p.util.SimpleTimer2;
import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;

/**
 * Main Snark program startup class.
 */
public class Snark implements StorageListener, CoordinatorListener, ShutdownListener {


    /** Maximum connections. */
    public static final String PROP_MAX_CONNECTIONS = "i2psnark.maxConnections";

    /** Most of these used to be public; use accessors below instead. */
    private String torrent;

    private MetaInfo meta;
    private Storage storage;
    private PeerCoordinator coordinator;
    private ConnectionAcceptor acceptor;
    private TrackerClient trackerclient;
    private final File rootDataDir;
    private final CompleteListener completeListener;
    private volatile boolean stopped;
    private volatile boolean starting;
    private final byte[] id;
    private final byte[] infoHash;
    private String additionalTrackerURL;
    /** The transient destination, when multi-dest is enabled; null otherwise or on stop */
    private volatile TorrentDest _dest;
    /** The util */
    protected final I2PSnarkUtil _util;
    private final Log _log;
    private final PeerCoordinatorSet _peerCoordinatorSet;
    private volatile String trackerProblems;
    private volatile long lastTrackerResponse;
    private volatile int trackerSeenPeers;
    private volatile int _scrapeSeeders;
    private volatile int _scrapeLeechers;
    private volatile int _scrapePartials;
    private volatile boolean _autoStoppable;
    private volatile String activity = "Not started";
    private long savedUploaded;
    private long _startedTime;
    private CommentSet _comments;
    private final Object _commentLock = new Object();
    private static final AtomicInteger __RPCID = new AtomicInteger();
    private final int _rpcID = __RPCID.incrementAndGet();

    /**
     * Create the torrent in multitorrent mode.
     *
     * <p>Will not start itself. Caller must call startTorrent() if desired.
     *
     * @param util the I2PSnarkUtil
     * @param torrent the torrent
     * @param ip the IP address
     * @param user_port the user port
     * @param slistener the storage listener
     * @param clistener the coordinator listener
     * @param complistener the complete listener
     * @param peerCoordinatorSet the peer coordinator set
     * @param connectionAcceptor the connection acceptor
     * @param rootDir the root data directory
     * @throws RuntimeException via fatal()
     * @throws RouterException via fatalRouter()
     */
    public Snark(
            I2PSnarkUtil util,
            String torrent,
            String ip,
            int user_port,
            StorageListener slistener,
            CoordinatorListener clistener,
            CompleteListener complistener,
            PeerCoordinatorSet peerCoordinatorSet,
            ConnectionAcceptor connectionAcceptor,
            String rootDir) {
        this(
                util,
                torrent,
                ip,
                user_port,
                slistener,
                clistener,
                complistener,
                peerCoordinatorSet,
                connectionAcceptor,
                rootDir,
                null);
    }

    /**
     * Create the torrent in multitorrent mode.
     *
     * <p>Will not start itself. Caller must call startTorrent() if desired.
     *
     * @param util the I2PSnarkUtil
     * @param torrent the torrent
     * @param ip the IP address
     * @param user_port the user port
     * @param slistener the storage listener
     * @param clistener the coordinator listener
     * @param complistener the complete listener
     * @param peerCoordinatorSet the peer coordinator set
     * @param connectionAcceptor the connection acceptor
     * @param rootDir the root data directory
     * @param baseFile if null, use rootDir/torrentName; if non-null, use it instead
     * @throws RuntimeException via fatal()
     * @throws RouterException via fatalRouter()
     * @since 0.9.11
     */
    public Snark(
            I2PSnarkUtil util,
            String torrent,
            String ip,
            int user_port,
            StorageListener slistener,
            CoordinatorListener clistener,
            CompleteListener complistener,
            PeerCoordinatorSet peerCoordinatorSet,
            ConnectionAcceptor connectionAcceptor,
            String rootDir,
            File baseFile) {
        if (slistener == null) {
            slistener = this;
        }
        completeListener = complistener;
        _util = util;
        _log = util.getContext().logManager().getLog(Snark.class);
        _peerCoordinatorSet = peerCoordinatorSet;
        acceptor = connectionAcceptor;
        this.torrent = torrent;
        this.rootDataDir = new File(rootDir);
        stopped = true;
        activity = "Network setup";
        // Figure out what the torrent argument represents.
        File f = null;
        byte[] x_infoHash = null;
        try {
            f = new File(torrent);
            if (f.exists()) {
                try (InputStream in = new FileInputStream(f)) {
                    meta = new MetaInfo(in);
                    x_infoHash = meta.getInfoHash();
                }
            } else {
                throw new IOException("not found");
            }
        } catch (IOException ioe) {
            // OK, so it wasn't a torrent metainfo file.
            if (f != null && f.exists()) {
                if (ip == null) {
                    fatal(
                            "'"
                                    + torrent
                                    + "' exists, but is not a valid torrent metainfo file."
                                    + System.getProperty("line.separator"),
                            ioe);
                } else {
                    fatal(
                            "I2PSnark does not support creating and tracking a torrent at the"
                                + " moment");
                }
            } else {
                fatal("Cannot open '" + torrent + "'", ioe);
            }
        } catch (OutOfMemoryError oom) {
            fatalRouter(
                    "ERROR - Out of memory, cannot create torrent "
                            + torrent
                            + ": "
                            + oom.getMessage(),
                    oom);
        }

        infoHash = x_infoHash; // final
        id = generateID();
        if (_log.shouldInfo()) _log.info(meta.toString());

        // When the metainfo torrent was created from an existing file/dir
        // it already exists.
        if (storage == null) {
            try {
                activity = "Checking storage";
                boolean shouldPreserve =
                        completeListener != null
                                && completeListener.getSavedPreserveNamesSetting(this);
                if (baseFile == null) {
                    String base = meta.getName();
                    if (!shouldPreserve) {
                        base = Storage.filterName(base);
                    }
                    if (_util.getFilesPublic()) {
                        baseFile = new File(rootDataDir, base);
                    } else {
                        baseFile = new SecureFile(rootDataDir, base);
                    }
                }
                storage = new Storage(_util, baseFile, meta, slistener, shouldPreserve);
                if (completeListener != null) {
                storage.check(
                        completeListener.getSavedTorrentTime(this),
                        completeListener.getSavedTorrentBitField(this));
                } else {
                    storage.check();
                }
            } catch (IOException ioe) {
                try {
                    storage.close();
                    } catch (IOException ioee) {
                    _log.log(Log.WARN, "Error closing storage after failure", ioee);
                }
                fatal("Could not check or create files for " + getBaseInfo(), ioe);
            }
        }

        savedUploaded = (completeListener != null) ? completeListener.getSavedUploaded(this) : 0;
        if (completeListener != null) {
            _comments = completeListener.getSavedComments(this);
        }
    }

    /**
     * Create the torrent in multitorrent or magnet mode, for the snark-rpc plugin.
     *
     * <p>Will not start itself. Caller must call startTorrent() if desired.
     *
     * @param util the I2PSnarkUtil
     * @param torrent the torrent
     * @param ih the info hash
     * @param trackerURL the tracker URL
     * @param complistener the complete listener
     * @param peerCoordinatorSet the peer coordinator set
     * @param connectionAcceptor the connection acceptor
     * @param ignored used to be autostart
     * @param rootDir the root data directory
     * @throws RuntimeException via fatal()
     * @throws RouterException via fatalRouter()
     * @since 0.8.4, removed in 0.9.36, restored in 0.9.45 with boolean param now ignored
     */
    protected Snark(
            I2PSnarkUtil util,
            String torrent,
            byte[] ih,
            String trackerURL,
            CompleteListener complistener,
            PeerCoordinatorSet peerCoordinatorSet,
            ConnectionAcceptor connectionAcceptor,
            boolean ignored,
            String rootDir) {
        this(
                util,
                torrent,
                ih,
                trackerURL,
                complistener,
                peerCoordinatorSet,
                connectionAcceptor,
                rootDir);
    }

    /**
     * Create the torrent in multitorrent or magnet mode.
     *
     * <p>Will not start itself. Caller must call startTorrent() if desired.
     *
     * @param util the I2PSnarkUtil
     * @param torrent a fake name for now (not a file name)
     * @param ih 20-byte info hash
     * @param trackerURL may be null
     * @param complistener the complete listener
     * @param peerCoordinatorSet the peer coordinator set
     * @param connectionAcceptor the connection acceptor
     * @param rootDir the root data directory
     * @throws RuntimeException via fatal()
     * @throws RouterException via fatalRouter()
     * @since 0.8.4
     */
    public Snark(
            I2PSnarkUtil util,
            String torrent,
            byte[] ih,
            String trackerURL,
            CompleteListener complistener,
            PeerCoordinatorSet peerCoordinatorSet,
            ConnectionAcceptor connectionAcceptor,
            String rootDir) {
        completeListener = complistener;
        _util = util;
        _log = util.getContext().logManager().getLog(Snark.class);
        _peerCoordinatorSet = peerCoordinatorSet;
        acceptor = connectionAcceptor;
        this.torrent = torrent;
        this.infoHash = ih;
        this.additionalTrackerURL = trackerURL;
        this.rootDataDir =
                rootDir != null ? new File(rootDir) : null; // null only for FetchAndAdd extension
        savedUploaded = 0;
        stopped = true;
        id = generateID();

        // All we have is an infoHash
        // meta remains null
        // storage remains null
    }

    /**
     * Our peer ID for trackers and peers: the spoofed client identity selected
     * for this torrent's destination when i2psnark.clientId is configured, else
     * the legacy anonymous ID of nine zero bytes, three 0x03 bytes, then eight
     * random bytes.
     *
     * @return 20-byte peer ID
     * @throws RouterException if the router is shutting down
     */
    private byte[] generateID() {
        ClientID.Profile profile = _util.getClientID(infoHash);
        if (profile != null) {
            try {
                byte[] rv = profile.buildPeerId(_util.getContext().random());
                if (_log.shouldInfo()) {
                    _log.info(
                            "Our PeerID for this session is: "
                                    + PeerID.idencode(rv)
                                    + " ["
                                    + profile.getName()
                                    + ']');
                }
                return rv;
            } catch (IllegalStateException ise) {
                throw new RouterException("Router shutdown", ise);
            }
        }
        // Create a new ID and fill it with something random. First nine
        // zeros bytes, then three bytes filled with snark and then
        // eight random bytes.
        byte snark = (((3 + 7 + 10) * (1000 - 8)) / 992) - 17;
        byte[] rv = new byte[20];
        rv[9] = snark;
        rv[10] = snark;
        rv[11] = snark;
        try {
            I2PAppContext.getGlobalContext().random().nextBytes(rv, 12, 8);
        } catch (IllegalStateException ise) {
            throw new RouterException("Router shutdown", ise);
        } // random is shut down
        if (_log.shouldInfo()) {
            _log.info("Our PeerID for this session is: " + PeerID.idencode(rv));
        }
        return rv;
    }

    /**
     * Initial startup retry delay after a failed per-torrent session creation, in milliseconds.
     *
     * @since 0.9.71+
     */
    private static final long RETRY_INITIAL_MS = 60 * 1000;

    /**
     * Maximum startup retry delay (backoff cap), in milliseconds.
     *
     * @since 0.9.71+
     */
    private static final long RETRY_MAX_MS = 5 * 60 * 1000;

    /**
     * Give up on a per-torrent session after this long, in milliseconds.
     *
     * @since 0.9.71+
     */
    private static final long RETRY_TOTAL_MS = 60 * 60 * 1000;

    /** Startup retry event, when a per-torrent session creation failed. */
    private volatile SimpleTimer2.TimedEvent _retryEvent;

    /** Current backoff delay for the next session creation retry. */
    private long _retryDelay = RETRY_INITIAL_MS;

    /** Wall-clock time of the first failed session creation, for the give-up window. */
    private long _retryStart;

    /**
     * Stop count, incremented by stopTorrent() so a retry event that already fired cannot
     * reconnect a torrent stopped while its event was pending.
     *
     * @since 0.9.71+
     */
    private long _stopCount;

    /**
     * Start up contacting peers and querying the tracker. Blocks if tunnel is not yet open.
     *
     * @throws RuntimeException via fatal()
     * @throws RouterException via fatalRouter()
     */
    public synchronized void startTorrent() {
        if (!stopped) {
            return;
        }
        cancelRetry();
        starting = true;
        try {
            x_startTorrent();
            if (!stopped) {
                resetRetry();
                _startedTime = _util.getContext().clock().now();
            }
        } finally {
            starting = false;
        }
    }

    private void x_startTorrent() {
        boolean ok = _util.connect();
        if (!ok) {
            if (_util.getContext().isRouterContext())
                fatalRouter(_util.getString("Unable to connect to I2P"), null);
            else
                fatalRouter(
                        _util.getString("Error connecting to I2P - check your I2CP settings!")
                                + ' '
                                + _util.getI2CPHost()
                                + ':'
                                + _util.getI2CPPort(),
                        null);
        }
        String key = null;
        TorrentDest td = null;
        if (_util.getMultiDest()) {
            key = Base64.encode(infoHash);
            td = _util.getOrCreateTorrentDest(key, getBaseName());
            _dest = td;
            if (td == null) {
                // Session creation failed (e.g. tunnels or router unreachable); not fatal
                // for a per-torrent session — retry with backoff, then give up after an
                // hour so the torrent can be restarted later.
                long now = _util.getContext().clock().now();
                if (_retryStart == 0) {
                    _retryStart = now;
                }
                if (now - _retryStart >= RETRY_TOTAL_MS) {
                    _retryStart = 0;
                    fatalRouter("Unable to listen for I2P connections", null);
                }
                long delay = _retryDelay;
                _retryDelay = Math.min(_retryDelay * 2, RETRY_MAX_MS);
                if (_log.shouldWarn()) {
                    _log.warn(
                            "Unable to listen for I2P connections for "
                                    + getBaseName()
                                    + ", retrying in "
                                    + (delay / 60000)
                                    + " minutes");
                }
                scheduleRetry(delay);
                return;
            }
        }
        if (coordinator == null) {
            I2PServerSocket serversocket =
                    td != null ? td.getServerSocket() : _util.getServerSocket();
            if (serversocket == null) fatalRouter("Unable to listen for I2P connections", null);
            else {
                Destination d = serversocket.getManager().getSession().getMyDestination();
                if (_log.shouldInfo())
                    _log.info(
                            "Listening on I2P destination [" + d.toBase64().substring(0, 6) + "]");
            }
            if (_log.shouldInfo())
                _log.info("Starting PeerCoordinator, ConnectionAcceptor, and TrackerClient");
            activity = "Collecting pieces";
            coordinator =
                    new PeerCoordinator(
                            _util,
                            id,
                            infoHash,
                            meta,
                            storage,
                            this,
                            this,
                            completeListener.getBandwidthListener());
            coordinator.setUploaded(savedUploaded);
            if (_peerCoordinatorSet != null) {
                _peerCoordinatorSet.add(coordinator); // multitorrent
            } else {
                if (td != null) {
                    acceptor =
                            new ConnectionAcceptor(
                                    _util, new PeerAcceptor(coordinator), td); // single torrent
                } else {
                    acceptor =
                            new ConnectionAcceptor(
                                    _util, new PeerAcceptor(coordinator)); // single torrent
                }
            }
            // TODO pass saved closest DHT nodes to the tracker? or direct to the coordinator?
            trackerclient = new TrackerClient(_util, meta, additionalTrackerURL, coordinator, this);
        }
        if (td != null && _peerCoordinatorSet != null) {
            // multitorrent: register the accept loop on the torrent's destination,
            // on first start and on restart
            acceptor.addTorrentAcceptor(td, coordinator);
        }
        // ensure acceptor is running when in multitorrent
        if (_peerCoordinatorSet != null && acceptor != null && td == null) {
            acceptor.startAccepting();
        }

        stopped = false;
        if (coordinator.halted()) {
            coordinator.restart();
            if (_peerCoordinatorSet != null) _peerCoordinatorSet.add(coordinator);
        }
        if (!trackerclient.started()) {
            trackerclient.start();
        } else if (trackerclient.halted()) {
            if (storage != null) {
                try {
                    storage.reopen();
                } catch (IOException ioe) {
                    try {
                        storage.close();
                    } catch (IOException ioee) {
                        _log.log(Log.WARN, "Error closing storage after failure", ioee);
                    }
                    fatal("Could not open file for " + getBaseInfo(), ioe);
                }
            }
            trackerclient.start();
        } else if (_log.shouldDebug()) {
            _log.debug("NOT starting TrackerClient???");
        }
    }

    /**
     * Schedule a retry of startTorrent() after a per-torrent session creation failure.
     * Rescheduled on each subsequent failure; cancelled by stopTorrent() or a manual start.
     *
     * @param delay delay before the retry, in milliseconds
     * @since 0.9.71+
     */
    private void scheduleRetry(long delay) {
        cancelRetry();
        long stopCount = _stopCount;
        _retryEvent =
                new SimpleTimer2.TimedEvent(_util.getContext().simpleTimer2(), delay) {
                    @Override
                    public void timeReached() {
                        cancelRetry();
                        synchronized (Snark.this) {
                            // Re-check under the monitor: a stop that completed while this
                            // event was pending leaves stopped true and bumps the count
                            if (stopped && stopCount == _stopCount) {
                                startTorrent();
                            }
                        }
                    }
                };
    }

    /**
     * Reset the session creation retry backoff state, on success and on stop.
     *
     * @since 0.9.71+
     */
    private void resetRetry() {
        _retryStart = 0;
        _retryDelay = RETRY_INITIAL_MS;
    }

    /**
     * Cancel a pending startup retry, if any.
     *
     * @since 0.9.71+
     */
    private synchronized void cancelRetry() {
        if (_retryEvent != null) {
            _retryEvent.cancel();
            _retryEvent = null;
        }
    }

    /** Stop contacting the tracker and talking with peers */
    public void stopTorrent() {
        stopTorrent(false, false);
    }

    /**
     * Stop contacting the tracker and talking with peers
     *
     * @param fast if true, limit the life of the unannounce threads
     * @since 0.9.1
     */
    public void stopTorrent(boolean fast) {
        stopTorrent(fast, false);
    }

    /**
     * Stop contacting the tracker and talking with peers, optionally leaving the
     * I2P session open so the caller can dispatch unannounces before teardown.
     *
     * @param fast if true, limit the life of the unannounce threads
     * @param deferTeardown if true, keep the session for teardownSession()
     * @since 0.9.71+
     */
    synchronized void stopTorrent(boolean fast, boolean deferTeardown) {
        cancelRetry();
        resetRetry();
        _stopCount++;
        TrackerClient tc = trackerclient;
        if (tc != null) {
            tc.halt(fast);
        }
        PeerCoordinator pc = coordinator;
        if (pc != null) {
            pc.halt();
        }
        Storage st = storage;
        if (!fast) {
            // HACK: Needed a way to distinguish between user-stop and
            // shutdown-stop. stopTorrent(true) is in stopAllTorrents().
            // (#766)
            stopped = true;
        }
        if (st != null) {
            // TODO: Cache the config-in-mem to compare vs config-on-disk
            // (needed for auto-save to not double-save in some cases)
            long nowUploaded = getUploaded();
            // If autoStart is enabled, always save the config, so we know whether to start it up
            // next time
            boolean changed =
                    storage.isChanged()
                            || nowUploaded != savedUploaded
                            || (completeListener != null && completeListener.shouldAutoStart());
            try {
                storage.close();
            } catch (IOException ioe) {
                if (_log.shouldWarn()) {
                    _log.warn("Error closing " + torrent, ioe);
                }
            }
            savedUploaded = nowUploaded;
            // SnarkManager.stopAllTorrents() will save comments at shutdown even if never
            // started...
            if (completeListener != null) {
                if (changed) {
                    completeListener.updateStatus(this);
                }
                synchronized (_commentLock) {
                    if (_comments != null) {
                        synchronized (_comments) {
                            if (_comments.isModified())
                                completeListener.locked_saveComments(this, _comments);
                        }
                    }
                }
            }
        }
        if (fast) {
            stopped = true;
        } // HACK: See above if(!fast)
        if (!deferTeardown) {
            if (tc != null) {
                tc.awaitUnannounces(TrackerClient.unannounceDispatchWait());
            }
            teardownSession();
        }
    }

    /**
     * Destroy the I2P session for this stopped torrent. Called by stopTorrent()
     * or, after the stop's unannounces have had a chance to dispatch, by
     * stopAllTorrents().
     *
     * @since 0.9.71+
     */
    void teardownSession() {
        PeerCoordinator pc = coordinator;
        if (_peerCoordinatorSet != null) {
            if (pc != null) {
                _peerCoordinatorSet.remove(pc);
            }
            if (_util.getMultiDest()) {
                String key = Base64.encode(infoHash);
                acceptor.removeTorrentAcceptor(_dest, pc);
                _util.removeTorrentDest(key);
                _dest = null;
            } else {
                _util.disconnect();
            }
        }
    }

    /**
     * Wait, capped at ms, for this torrent's unannounces to dispatch.
     *
     * @since 0.9.71+
     */
    void awaitUnannounces(long ms) {
        TrackerClient tc = trackerclient;
        if (tc != null) {
            tc.awaitUnannounces(ms);
        }
    }

    // Accessors

    /**
     * The file name of the .torrent file.
     *
     * @return file name of .torrent file (should be full absolute path), or a fake name if in
     *     magnet mode.
     * @since 0.8.4
     */
    public String getName() {
        return torrent;
    }

    /**
     * The base name of the torrent.
     *
     * @return base name of torrent [filtered version of getMetaInfo.getName()], or a fake name if
     *     in magnet mode
     * @since 0.8.4
     */
    public String getBaseName() {
        if (storage != null) {
            return storage.getBaseName();
        }
        return torrent;
    }

    /**
     * The base name with path and error info, for error logging only.
     *
     * @return base name for torrent [filtered version of getMetaInfo.getName()], or a fake name if
     *     in magnet mode, followed by path info and error message, for error logging only
     * @since 0.9.44
     */
    private String getBaseInfo() {
        if (storage != null) {
            return storage.getBaseName()
                    + " at "
                    + storage.getBase()
                    + " - check that device is present and writable";
        }
        return torrent;
    }

    /**
     * The peer ID.
     *
     * @return always will be valid even in magnet mode
     * @since 0.8.4
     */
    public byte[] getID() {
        return id;
    }

    /**
     * The info hash.
     *
     * @return always will be valid even in magnet mode
     * @since 0.8.4
     */
    public byte[] getInfoHash() {
        // should always be the same
        if (meta != null) {
            return meta.getInfoHash();
        }
        return infoHash;
    }

    /**
     * The transient multi-dest destination, or null when multi-dest is
     * disabled or the torrent is stopped.
     *
     * @return the destination, or null
     * @since 0.9.71+
     */
    public TorrentDest getDest() {
        return _dest;
    }

    /**
     * The metainfo.
     *
     * @return may be null if in magnet mode
     * @since 0.8.4
     */
    public MetaInfo getMetaInfo() {
        return meta;
    }

    /**
     * The storage.
     *
     * @return may be null if in magnet mode
     * @since 0.8.4
     */
    public Storage getStorage() {
        return storage;
    }

    /**
     * Check if the torrent is stopped.
     *
     * @return true if stopped
     * @since 0.8.4
     */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Check if startup is in progress.
     *
     * @return true if starting
     * @since 0.9.1
     */
    public boolean isStarting() {
        return starting && stopped;
    }

    /**
     * Mark startup in progress.
     *
     * @since 0.9.1
     */
    public void setStarting() {
        starting = true;
    }

    /**
     * Check if file checking is in progress.
     *
     * @return true if checking is in progress, false otherwise
     * @since 0.9.3
     */
    public boolean isChecking() {
        return storage != null && storage.isChecking();
    }

    /**
     * The progress of file checking as a percentage. If checking is in progress, return
     * completion 0.0 ... 1.0, else return 1.0.
     *
     * @return checking progress as a percentage (0.0 to 1.0)
     * @since 0.9.23
     */
    public double getCheckingProgress() {
        if (storage != null && storage.isChecking()) {
            return storage.getCheckingProgress();
        } else {
            return 1.0d;
        }
    }

    /**
     * Check if disk allocation (ballooning) is in progress.
     *
     * @return true if allocating, false otherwise
     * @since 0.9.3
     */
    public boolean isAllocating() {
        return storage != null && storage.isAllocating();
    }

    /**
     * The current download rate in bytes per second.
     *
     * @return download rate in bytes per second, or 0 if not available
     * @since 0.8.4
     */
    public long getDownloadRate() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            return coord.getDownloadRate();
        }
        return 0;
    }

    /**
     * The current upload rate in bytes per second.
     *
     * @return upload rate in bytes per second, or 0 if not available
     * @since 0.8.4
     */
    public long getUploadRate() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            return coord.getUploadRate();
        }
        return 0;
    }

    /**
     * The total number of bytes downloaded.
     *
     * @return total bytes downloaded, or 0 if not available
     * @since 0.8.4
     */
    public long getDownloaded() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            return coord.getDownloaded();
        }
        return 0;
    }

    /**
     * The total number of bytes uploaded.
     *
     * @return total bytes uploaded, including saved uploaded if coordinator is not available
     * @since 0.8.4
     */
    public long getUploaded() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            return coord.getUploaded();
        }
        return savedUploaded;
    }

    /**
     * The peer count.
     *
     * @return peer count
     */
    public int getPeerCount() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            return coord.getPeerCount();
        }
        return 0;
    }

    /**
     * The peer list.
     *
     * @return peer list
     */
    public List<Peer> getPeerList() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            return coord.peerList();
        }
        return Collections.emptyList();
    }

    /**
     * Distinct swarm peers seen recently (via announce, PEX or DHT) or
     * currently connected, for the torrent view peer count.
     *
     * @return the count, 0 if no coordinator
     * @since 0.9.71+
     */
    public int getSwarmPeerCount() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            return coord.getSwarmPeerCount();
        }
        return 0;
    }

    /**
     * Not HTML escaped.
     *
     * @return String returned from tracker, or null if no error
     * @since 0.8.4
     */
    public String getTrackerProblems() {
        return trackerProblems;
    }

    /**
     * Store the tracker problems string.
     *
     * @param p tracker error string or null
     * @since 0.8.4
     */
    public void setTrackerProblems(String p) {
        trackerProblems = p;
    }

    /**
     * The wall-clock time of the last valid tracker announce or scrape
     * response, or 0 if none was ever received.
     *
     * @return milliseconds since epoch
     * @since 0.9.68
     */
    public long getLastTrackerResponse() {
        return lastTrackerResponse;
    }

    /**
     * Record a valid tracker announce or scrape response.
     *
     * @param t milliseconds since epoch
     * @since 0.9.68
     */
    public void setLastTrackerResponse(long t) {
        lastTrackerResponse = t;
    }

    /**
     * The tracker seen peers count.
     *
     * @return count returned from tracker
     * @since 0.8.4
     */
    public int getTrackerSeenPeers() {
        return trackerSeenPeers;
    }

    /**
     * Store the tracker seen peers count.
     *
     * @param p peer count from tracker
     */
    public void setTrackerSeenPeers(int p) {
        trackerSeenPeers = p;
    }

    /**
     * The best-known seed count from tracker scrapes.
     *
     * @return the seed count, 0 if no scrape yet
     * @since 0.9.71+
     */
    public int getScrapeSeeders() {
        return _scrapeSeeders;
    }

    /**
     * The best-known leech count from tracker scrapes.
     *
     * @return the leech count, 0 if no scrape yet
     * @since 0.9.71+
     */
    public int getScrapeLeechers() {
        return _scrapeLeechers;
    }

    /**
     * The best-known partial seed count from tracker scrapes.
     *
     * <p>Partial seeds have some files but download nothing more (BEP 21).
     *
     * @return the partial seed count, 0 if no scrape yet or not supported
     * @since 0.9.71+
     */
    public int getScrapePartialSeeds() {
        return _scrapePartials;
    }

    /**
     * Update the best-known swarm composition from a tracker scrape (BEP 15/48).
     *
     * <p>Keeps the maximum of each count, and raises the aggregate tracker seen peers to the sum
     * when larger.
     *
     * @param seeders the seed count
     * @param leechers the leech count
     * @since 0.9.71+
     */
    public void updateScrape(int seeders, int leechers) {
        updateScrape(seeders, leechers, 0);
    }

    /**
     * Update the best-known swarm composition from a tracker scrape (BEP 15/48/21).
     *
     * <p>Keeps the maximum of each count, and raises the aggregate tracker seen peers to the sum
     * when larger.
     *
     * @param seeders the seed count
     * @param leechers the leech count
     * @param partials the partial seed count, 0 if unknown
     * @since 0.9.71+
     */
    public void updateScrape(int seeders, int leechers, int partials) {
        if (seeders < 0 || leechers < 0 || partials < 0) {
            return;
        }
        if (seeders > _scrapeSeeders) {
            _scrapeSeeders = seeders;
        }
        if (leechers > _scrapeLeechers) {
            _scrapeLeechers = leechers;
        }
        if (partials > _scrapePartials) {
            _scrapePartials = partials;
        }
        int total = _scrapeSeeders + _scrapeLeechers;
        if (total > trackerSeenPeers) {
            setTrackerSeenPeers(total);
        }
    }

    /** Recalculate piece priorities */
    public void updatePiecePriorities() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            coord.updatePiecePriorities();
        }
    }

    /**
     * The total length of all torrent files.
     *
     * @return total of all torrent files, or total of metainfo file if fetching magnet, or -1
     * @since 0.8.4
     */
    public long getTotalLength() {
        if (meta != null) {
            return meta.getTotalLength();
        }
        return -1; // FIXME else return metainfo length if available
    }

    /**
     * The total length of all non-padding torrent files; equals {@link #getTotalLength()} for
     * torrents without BEP 47 padding files. The projected downloaded size.
     *
     * @return total of all non-padding files, or -1 if unavailable
     */
    public long getDataLength() {
        if (meta != null) {
            return meta.getDataLength();
        }
        return -1;
    }

    /**
     * Bytes not yet in storage. Does NOT account for skipped files.
     *
     * @return exact value. or -1 if no storage yet. getNeeded() * pieceLength(0) isn't accurate if
     *     last piece is still needed.
     * @since 0.8.9
     */
    public long getRemainingLength() {
        if (meta != null && storage != null) {
            long needed = storage.needed();
            long length0 = meta.getPieceLength(0);
            long remaining = needed * length0;
            // fixup if last piece is needed
            int last = meta.getPieces() - 1;
            if (last != 0 && !storage.getBitField().get(last)) {
                remaining -= length0 - meta.getPieceLength(last);
            }
            return remaining;
        }
        return -1;
    }

    /**
     * Bytes still wanted. DOES account for (i.e. does not include) skipped files. FIXME -1 when not
     * running.
     *
     * @return exact value. or -1 if no storage yet or when not running.
     * @since 0.9.1
     */
    public long getNeededLength() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            return coord.getNeededLength();
        }
        return -1;
    }

    /**
     * Bytes not received and set to skipped. This is not the same as the total of all skipped
     * files, since pieces may span multiple files.
     *
     * @return exact value. or 0 if no storage yet.
     * @since 0.9.24
     */
    public long getSkippedLength() {
        PeerCoordinator coord = coordinator;
        if (coord != null) {
            // fast way
            long r = getRemainingLength();
            if (r <= 0) {
                return 0;
            }
            long n = coord.getNeededLength();
            return r - n;
        } else if (storage != null) {
            return storage.getSkippedLength();
        } // slow way
        return 0;
    }

    /**
     * Does not account (i.e. includes) for skipped files.
     *
     * @return number of pieces still needed (magnet mode or not), or -1 if unknown
     * @since 0.8.4
     */
    public long getNeeded() {
        if (storage != null) {
            return storage.needed();
        }
        if (meta != null) {
            return meta.getTotalLength();
        } // FIXME subtract chunks we have
        return -1; // FIXME fake
    }

    /**
     * The piece length.
     *
     * @param p the piece number
     * @return metainfo piece length or 16K if fetching magnet
     * @since 0.8.4
     */
    public int getPieceLength(int p) {
        if (meta != null) {
            return meta.getPieceLength(p);
        }
        return 16 * 1024;
    }

    /**
     * The number of pieces.
     *
     * @return number of pieces
     * @since 0.8.4
     */
    public int getPieces() {
        if (meta != null) {
            return meta.getPieces();
        }
        return -1; // FIXME else return metainfo pieces if available
    }

    /**
     * Restart the connection acceptor.
     *
     * @return true if restarted
     * @since 0.8.4
     */
    public boolean restartAcceptor() {
        if (acceptor == null) {
            return false;
        }
        acceptor.restart();
        return true;
    }

    /**
     * The tracker URL.
     *
     * @return trackerURL string from magnet-mode constructor, may be null
     * @since 0.8.4
     */
    public String getTrackerURL() {
        return additionalTrackerURL;
    }

    /**
     * Check if the torrent is auto-stoppable.
     *
     * @return true if auto-stoppable
     * @since 0.9.9
     */
    public boolean isAutoStoppable() {
        return _autoStoppable;
    }

    /**
     * Store whether the torrent is auto-stoppable.
     *
     * @param yes true if auto-stoppable
     * @since 0.9.9
     */
    public void setAutoStoppable(boolean yes) {
        _autoStoppable = yes;
    }

    /**
     * Aborts program abnormally.
     *
     * @throws RuntimeException always
     */
    private void fatal(String s) throws RuntimeException {
        fatal(s, null);
    }

    /**
     * Aborts program abnormally.
     *
     * @throws RuntimeException always
     */
    private void fatal(String s, Throwable t) throws RuntimeException {
        _log.error(s, t);
        stopTorrent();
        if (t != null) {
            s += ": " + t;
        }
        if (completeListener != null) {
            completeListener.fatal(this, s);
        }
        throw new RuntimeException(s, t);
    }

    /**
     * Throws a unique exception class to blame the router that can be caught by SnarkManager
     *
     * @throws RouterException always
     * @since 0.9.46
     */
    private void fatalRouter(String s, Throwable t) throws RouterException {
        _log.error(s, t);
        if (!_util.getContext().isRouterContext()) {
            System.err.println(s);
        }
        stopTorrent(true);
        if (completeListener != null) {
            completeListener.fatal(this, s);
        }
        throw new RouterException(s, t);
    }

    /**
     * A unique exception class to blame the router that can be caught by SnarkManager
     *
     * @since 0.9.46
     */
    static class RouterException extends RuntimeException {
        /**
         * With message only.
         *
         * @param s error message
         */
        public RouterException(String s) {
            super(s);
        }

        /**
         * With message and cause.
         *
         * @param s error message
         * @param t cause
         */
        public RouterException(String s, Throwable t) {
            super(s, t);
        }
    }

    /** CoordinatorListener no-op */
    @Override
    public void peerChange(PeerCoordinator coordinator, Peer peer) { /* no-op */ }

    /**
     * Called when the PeerCoordinator got the MetaInfo via magnet. CoordinatorListener. Create the
     * storage, tell SnarkManager, and give the storage back to the coordinator.
     *
     * @throws RuntimeException via fatal()
     * @since 0.8.4
     */
    @Override
    public void gotMetaInfo(PeerCoordinator coordinator, MetaInfo metainfo) {
        try {
            String base = Storage.filterName(metainfo.getName());
            File baseFile;
            if (_util.getFilesPublic()) {
                baseFile = new File(rootDataDir, base);
            } else {
                baseFile = new SecureFile(rootDataDir, base);
            }
            if (baseFile.exists()) {
                throw new IOException("\n* Data location already exists: " + baseFile);
            }
            // The following two may throw IOE...
            storage = new Storage(_util, baseFile, metainfo, this, false);
            storage.check();
            // ... so don't set meta until here
            meta = metainfo;
            if (completeListener != null) {
                String newName = completeListener.gotMetaInfo(this);
                if (newName != null) {
                    torrent = newName;
                } // else some horrible problem
            }
            coordinator.setStorage(storage);
        } catch (IOException ioe) {
            if (storage != null) {
                try {
                    storage.close();
                } catch (IOException ioee) { /* ignored */ }
                // clear storage, we have a mess if we have non-null storage and null metainfo,
                // as on restart, Storage.reopen() will throw an ioe
                storage = null;
            }
            // TODO we're still in an inconsistent state, won't work if restarted
            // (PeerState "disconnecting seed that connects to seeds"
            fatal(
                    "Could not create file for "
                            + getBaseInfo().replace("Magnet", "info hash:")
                            + ' '
                            + ioe.getMessage());
        }
    }

    /**
     * Call after editing torrent. Caller must ensure infohash, files, etc. did not change.
     *
     * @since 0.9.53
     */
    public void replaceMetaInfo(MetaInfo metainfo) {
        meta = metainfo;
        TrackerClient tc = trackerclient;
        if (tc != null) {
            tc.reinitialize();
        }
    }

    ///////////// Begin StorageListener methods

    /** StorageListener no-op */
    @Override
    public void storageCreateFile(Storage storage, String name, long length) { /* no-op */ }

    // How much storage space has been allocated

    /** No-op; deliberately empty. */
    @Override
    public void storageAllocated(Storage storage, long length) { /* no-op */ }

    private boolean allChecked;
    private boolean checking;

    /**
     * StorageListener callback called when a piece check completes.
     *
     * @param storage the storage
     * @param num the piece number
     * @param checked true if the piece hash was correct
     */
    @Override
    public void storageChecked(Storage storage, int num, boolean checked) {
        if (!allChecked && !checking) {
            checking = true;
        }
        if (!checking && completeListener != null) {
            completeListener.gotPiece(this);
        }
    }

    /**
     * StorageListener callback called when all pieces have been checked.
     *
     * @param storage the storage
     */
    @Override
    public void storageAllChecked(Storage storage) {
        allChecked = true;
        checking = false;
        if (storage.isChanged() && completeListener != null) {
            completeListener.updateStatus(this);
            storage.clearChanged(); // this saved the status, so reset the variables
            savedUploaded = getUploaded();
        }
        // Avoid redundant "gotPiece" call when all pieces are checked
        if (!checking && completeListener != null && !allChecked) {
            completeListener.gotPiece(this);
        }
    }

    /** Whether the torrent storage has reported completion. */
    public boolean storageCompleted;

    /**
     * StorageListener callback called when the torrent has completed.
     *
     * @param storage the storage
     */
    @Override
    public void storageCompleted(Storage storage) {
        if (_log.shouldInfo()) {
            _log.info("Torrent " + torrent + " completed");
        }
        if (completeListener != null) {
            completeListener.torrentComplete(this);
            savedUploaded = getUploaded(); // This saves the status, so reset the variables
            storage.clearChanged();
            storageCompleted = true;
        }
    }

    /**
     * Whether the torrent storage has completed.
     *
     * @return true if the torrent storage has completed
     */
    public boolean isStorageCompleted() {
        return storageCompleted;
    }

    /**
     * StorageListener callback to update the coordinator's wanted piece set.
     *
     * @param storage the storage
     */
    @Override
    public void setWantedPieces(Storage storage) {
        PeerCoordinator localCoordinator = this.coordinator;
        if (localCoordinator != null) {
            synchronized (localCoordinator) {
                localCoordinator.setWantedPieces();
            }
        }
    }

    ///////////// End StorageListener methods

    /** SnarkShutdown callback unused */
    @Override
    public void shutdown() { /* no-op */ }

    /**
     * StorageListener and CoordinatorListener callback
     *
     * @since 0.9.2
     */
    @Override
    public void addMessage(String message) {
        if (completeListener != null) {
            completeListener.addMessage(this, message);
        }
    }

    /** Maintain a configurable total uploader cap CoordinatorListener */
    static final int MIN_TOTAL_UPLOADERS = 10;

    /** Maximum peers we upload to across all torrents. */
    static final int MAX_TOTAL_UPLOADERS = 50;

    /**
     * CoordinatorListener callback to check if the total uploaders across all torrents exceeds the
     * configured limit.
     *
     * @param uploaders the number of interested uploaders on this coordinator
     * @return true if the global upload limit is exceeded
     */
    @Override
    public boolean overUploadLimit(int uploaders) {
        int maxUploaders = _util.getMaxUploaders();
        if (maxUploaders < MIN_TOTAL_UPLOADERS) {
            maxUploaders = MIN_TOTAL_UPLOADERS;
        }
        if (_peerCoordinatorSet == null || uploaders <= 0) {
            return false;
        }
        int totalUploaders = 0;
        for (PeerCoordinator c : _peerCoordinatorSet) {
            if (!c.halted()) {
                totalUploaders += c.getInterestedUploaders();
            }
        }
        int limit = Math.max(MIN_TOTAL_UPLOADERS, maxUploaders);
        if (_log.shouldDebug()) {
            _log.debug(
                    "Currently uploading to: "
                            + totalUploaders
                            + " peer"
                            + (totalUploaders != 1 ? "s" : "")
                            + " (Limit: "
                            + limit
                            + ")");
        }
        return totalUploaders > limit;
    }

    /**
     * A unique ID for this torrent, useful for RPC
     *
     * @return positive value unless you wrap around
     * @since 0.9.30
     */
    public int getRPCID() {
        return _rpcID;
    }

    /**
     * When did we start this torrent For RPC
     *
     * @return 0 if not started before. Not cleared when stopped.
     * @since 0.9.30
     */
    public long getStartedTime() {
        return _startedTime;
    }

    /**
     * The current comment set for this torrent. Not a copy. Caller MUST synch on the returned
     * object for all operations.
     *
     * @return may be null if none
     * @since 0.9.31
     */
    public CommentSet getComments() {
        synchronized (_commentLock) {
            if (_comments != null) {
                return new CommentSet(_comments);
            }
            return _comments;
        }
    }

    /**
     * Add to the current comment set for this torrent, creating it if it didn't previously exist.
     *
     * @return true if the set changed
     * @since 0.9.31
     */
    public boolean addComments(List<Comment> comments) {
        synchronized (_commentLock) {
            if (_comments == null) {
                _comments = new CommentSet(comments);
                return true;
            } else {
                synchronized (_comments) {
                    return _comments.addAll(comments);
                }
            }
        }
    }

    private boolean notificationSent;

    /**
     * Whether a completion notification has already been sent for this torrent.
     *
     * @return true if a completion notification has already been sent for this torrent
     */
    public boolean isNotificationSent() {
        return notificationSent;
    }

    /**
     * Mark that a completion notification has been sent.
     *
     * @param sent true if the notification was sent
     */
    public void setNotificationSent(boolean sent) {
        this.notificationSent = sent;
    }

    /**
     * Is the given hash banned?
     *
     * @param h the hash
     * @return true if banned
     * @since 0.9.71
     */
    public boolean isBanned(Hash h) {
        return acceptor != null && acceptor.isBanned(h);
    }

    /**
     * Ban the given hash.
     *
     * @param h the hash
     * @since 0.9.71
     */
    public void ban(Hash h) {
        if (acceptor != null)
            acceptor.ban(h);
    }
}
