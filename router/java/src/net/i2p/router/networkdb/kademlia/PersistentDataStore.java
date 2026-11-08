package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.BanLogger;
import net.i2p.router.Banlist;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.stat.RateConstants;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PThread;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;
import net.i2p.util.SimpleTimer2;

/**
 * Persistent network database storage with disk I/O and memory management.
 * <p>
 * Writes RouterInfo entries to disk as received and periodically scans for unknown
 * entries to load into memory. Newly discovered routers are automatically added
 * to the routing table. Implements both flat and hierarchical directory structures
 * for efficient file organization and access.
 * <p>
 * Public only for access to static methods by startup classes.
 */
public class PersistentDataStore extends TransientDataStore {
    /** Database directory. */
    private final File _dbDir;
    /** Reference to the Kademlia facade for netdb operations. */
    private final KademliaNetworkDatabaseFacade _facade;
    /** Background thread for persisting RouterInfo files to disk. */
    private final Writer _writer;
    /** Read job that loads the database from disk. */
    private final ReadJob _readJob;
    /** Ban logger. */
    private final BanLogger _banLogger;
    /** Initialized flag. */
    private volatile boolean _initialized;
    /** Flat mode flag. */
    private final boolean _flat;
    /** Network ID. */
    private final int _networkID;

    /** Initial delay before first netdb scan. */
    private static final int READ_DELAY = 3*1000;
    /** Interval between periodic netdb rescans. */
    private static final int READ_INTERVAL = 5*60*1000;
    /** Config property to enable flat directory layout. */
    private static final String PROP_FLAT = "router.networkDatabase.flat";
    /** Directory prefix for netDb files. */
    static final String DIR_PREFIX = "r";
    /** Base64 alphabet used for directory naming. */
    private static final String B64 = Base64.ALPHABET_I2P;
    /** Max router files to load during startup initialization. */
    private static final int MAX_ROUTERS_INIT = 6000;
    /** Minimum router version to persist to disk. */
    private static final String MIN_VERSION = "0.9.64";

    /** Stat rate periods for NetDb write metrics. */
    private static final long[] RATES = RateConstants.BASIC_RATES;

    /**
     * Creates a new persistent data store.
     *
     * @param ctx the router context
     * @param dbDir directory name resolved under the router directory
     * @param facade the network database facade
     * @throws IOException if the database directory cannot be created or accessed
     */
    public PersistentDataStore(RouterContext ctx, String dbDir, KademliaNetworkDatabaseFacade facade) throws IOException {
        super(ctx);
        _networkID = ctx.router().getNetworkID();
        _flat = ctx.getBooleanProperty(PROP_FLAT);
        _dbDir = getDbDir(dbDir);
        _facade = facade;
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
        _readJob = new ReadJob();
        _context.jobQueue().addJob(_readJob);
        ctx.statManager().createRateStat("netDb.writeClobber", "How often we clobber a pending NetDb write", "NetworkDatabase", RATES);
        ctx.statManager().createRateStat("netDb.writeOut", "Total number of NetDb writes", "NetworkDatabase", RATES);
        ctx.statManager().createRateStat("netDb.writePending", "Number of pending NetDb writes", "NetworkDatabase", RATES);
        ctx.statManager().createRateStat("netDb.writeTime", "Total time used for NetDb writes ", "NetworkDatabase", RATES);
        _writer = new Writer();
        I2PThread writer = new I2PThread(_writer, "DBWriter");
        writer.start();
    }

    /**
     * Whether the data store has completed initial loading, either because the
     * read job finished or because enough RouterInfos have been loaded for the
     * netdb to be usable.
     *
     * @return true if the data store has completed initial loading
     */
    @Override
    public boolean isInitialized() {return _initialized || _readJob.isNetDbReady();}

    /**
     * Stop the writer thread and clear the in-memory store.
     * <p>
     * The read job is not stopped: it is a recurring SimpleTimer2 event with no
     * cancellation flag, so it keeps rescanning until the router's graceful
     * shutdown begins and runJob() returns early. A rescan after this point
     * re-reads an empty store and is harmless, but it is an open question
     * whether {@link #stop()} should cancel the reader outright.
     */
    @Override
    public void stop() {
        super.stop();
        _writer.flush();
    }

    /**
     * Trigger an immediate rescan if the store is initialized.
     */
    @Override
    public void rescan() {
        if (_initialized) {_readJob.wakeup();}
    }

    /**
     * The entry stored for the key, or null if not found.
     *
     * @param key the key to look up
     * @return the entry or null if not found
     */
    @Override
    public DatabaseEntry get(Hash key) {return get(key, true);}

    /**
     * Prepare for having only a partial set in memory and the rest on disk.
     * @param persist if false, call super only, don't access disk
     */
    @Override
    public DatabaseEntry get(Hash key, boolean persist) {
        return super.get(key);
    }

    /**
     * Remove from memory and queue the file for disk deletion.
     *
     * @param key the key to remove
     * @return the removed entry, or null if none was stored
     */
    @Override
    public DatabaseEntry remove(Hash key) {return remove(key, true);}

    /**
     * Remove from memory and optionally queue disk deletion.
     *
     * @param key the key to remove
     * @param persist if false, call super only, don't access disk
     * @return the removed entry, or null if none was stored
     */
    @Override
    public DatabaseEntry remove(Hash key, boolean persist) {
        if (persist) {_writer.remove(key);}
        return super.remove(key);
    }

    /**
     * Store the entry and persist it to disk. Only RouterInfos are written;
     * LeaseSets stay in memory.
     *
     * @param key the key to store under
     * @param data the entry to store
     * @return true if the entry was stored
     */
    @Override
    public boolean put(Hash key, DatabaseEntry data) {return put(key, data, true);}

    /**
     * Store the entry and optionally persist it to disk. Only RouterInfos are
     * written; LeaseSets stay in memory.
     *
     * @param key the key to store under
     * @param data the entry to store, null to fail
     * @param persist if false, call super only, don't access disk
     * @return true if the entry was stored
     */
    @Override
    public boolean put(Hash key, DatabaseEntry data, boolean persist) {
        if ( (data == null) || (key == null) ) {return false;}
        boolean rv = super.put(key, data);
        // Don't bother writing LeaseSets to disk
        if (rv && persist && data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {_writer.queue(key, data);}
        return rv;
    }

    /**
     * Unconditionally store, bypassing all newer/older checks.
     * Persists for RouterInfos only.
     *
     * @param key non-null
     * @param data non-null
     * @return true if the entry was stored in the Kademlia structure
     * @since 0.9.64
     */
    @Override
    public boolean forcePut(Hash key, DatabaseEntry data) {
        boolean rv = super.forcePut(key, data);
        if (rv && data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {_writer.queue(key, data);}
        return rv;
    }

    /**
     * How many files to write every 10 minutes. Doesn't make sense to limit it,
     * they just back up in the queue hogging memory.
     */
    private static final int WRITE_LIMIT = 10000;
    /** Interval between batch disk write cycles. */
    private static final long WRITE_DELAY = 10*60*1000L;

    /** Thread writing the store changes to disk. */
    private class Writer implements Runnable, Flushable {
        /** Queue of entries pending disk write. */
        private final Map<Hash, DatabaseEntry>_keys;
        /** Keys to remove from disk on next write cycle. */
        private final Set<Hash> _keysToRemove;
        /** Lock object for coordinating write cycle timing. */
        private final Object _waitLock;
        /** Flag to signal the write loop to exit. */
        private volatile boolean _quit;

        /** Builds the disk writer thread. */
        public Writer() {
            _keys = new ConcurrentHashMap<>(64);
            _keysToRemove = new ConcurrentHashSet<>();
            _waitLock = new Object();
        }

        /**
         * Add entry to the disk write queue.
         *
         * @param key the hash of the router whose entry is queued
         * @param data the entry to write on the next cycle
         */
        public void queue(Hash key, DatabaseEntry data) {
            int pending = _keys.size();
            _keysToRemove.remove(key);
            boolean exists = (null != _keys.put(key, data));
            if (exists) {_context.statManager().addRateData("netDb.writeClobber", pending);}
            _context.statManager().addRateData("netDb.writePending", pending);
        }

        /**
         * Mark a key for removal from disk on next cycle.
         *
         * @param key the hash of the router whose on-disk file should go
         */
        public void remove(Hash key) {
            _keys.remove(key);
            _keysToRemove.add(key);
        }

        /** Drops queued entries that were replaced. */
        private void removeQueued() {

            if (_keysToRemove.isEmpty()) {return;}
            int toRemove = 0;
            StringBuilder sb = new StringBuilder();
            for (Iterator<Hash> iter = _keysToRemove.iterator(); iter.hasNext(); ) {
                Hash key = iter.next();
                iter.remove();
                toRemove++;
                if (toRemove < 50) {
                    removeFile(key, _dbDir);
                }
            }
        }

        /**
         * Drain up to WRITE_LIMIT pending entries from the write queue.
         * Returns the drained entries; caller must write and {@link #removeQueued()} them.
         *
         * @return the drained entries, at most WRITE_LIMIT of them; empty when
         *         nothing is pending
         */
        private List<Map.Entry<Hash, DatabaseEntry>> drainPending() {
            List<Map.Entry<Hash, DatabaseEntry>> toWrite = new ArrayList<>();
            Iterator<Map.Entry<Hash, DatabaseEntry>> iter = _keys.entrySet().iterator();
            int written = 0;
            while (iter.hasNext() && written < WRITE_LIMIT) {
                try {
                    Map.Entry<Hash, DatabaseEntry> entry = iter.next();
                    toWrite.add(entry);
                    iter.remove();
                    written++;
                } catch (NoSuchElementException | IllegalStateException e) {
                    break;
                }
            }
            return toWrite;
        }

        /**
         * Write a batch of RouterInfo entries to disk under the _dbDir lock.
         * Each entry is individually locked to avoid holding the lock across the entire batch.
         *
         * @param entries the entries drained from the queue; null data values are skipped
         */
        private void writeBatch(List<Map.Entry<Hash, DatabaseEntry>> entries) {
            for (Map.Entry<Hash, DatabaseEntry> entry : entries) {
                Hash key = entry.getKey();
                DatabaseEntry data = entry.getValue();
                if (data != null) {
                    synchronized (_dbDir) { write(key, data); }
                }
            }
            removeQueued();
        }

        /**
         * Log write statistics and reset the timer.
         *
         * @param written the number of RouterInfo files written this cycle;
         *                nothing is logged for 0
         * @param startTime the wall-clock time in ms when the write cycle began
         */
        private void logWriteStats(int written, long startTime) {
            if (written > 0) {
                long time = _context.clock().now() - startTime;
                if (_log.shouldInfo()) {
                    _log.info(written + " RouterInfo files saved to disk in " + time + "ms");
                }
                _context.statManager().addRateData("netDb.writeOut", written);
                _context.statManager().addRateData("netDb.writeTime", time);
            }
        }

        /**
         * Sleep until the next write cycle or until flushed.
         */
        private void waitForNextCycle() {
            synchronized (_waitLock) {
                try { _waitLock.wait(WRITE_DELAY); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void run() {
            if (isShuttingDown(_context)) return;
            _quit = false;
            long startTime = _context.clock().now();
            while (!_quit) {
                List<Map.Entry<Hash, DatabaseEntry>> toWrite = drainPending();
                writeBatch(toWrite);
                logWriteStats(toWrite.size(), startTime);
                if (!toWrite.isEmpty()) {
                    startTime = _context.clock().now();
                }
                waitForNextCycle();
            }
        }

        /**
         * Signal the write loop to exit.
         * <p>
         * Sets the quit flag and wakes the writer so it leaves
         * {@code waitForNextCycle()}. The loop writes the batch it already
         * drained before it re-checks the flag, so entries queued before the
         * call are written; anything queued concurrently with or after it is
         * not.
         */
        public void flush() {
            synchronized(_waitLock) {
                _quit = true;
                _waitLock.notifyAll();
            }
        }
    }

    /** Pre-computed classification flags for a RouterInfo being written. */
    private static class RiFlags {
        /** Version string of the router info. */
        final String version;
        /** Capabilities string of the router info. */
        final String caps;
        /** Bandwidth class of the router info. */
        final String bw;
        /** IP address text of the router info. */
        final String ip;
        /** Our IP as the peer sees it. */
        final String ourIP;
        /** True if the router info has an IP. */
        final boolean hasIP;
        /** True if the router was marked unreachable. */
        final boolean unreachable;
        /** True if the router was marked reachable. */
        final boolean reachable;
        /** True if the router info is old. */
        final boolean isOld;
        /** True if the version string is invalid. */
        final boolean isInvalidVersion;
        /** True if the router is in the L tier. */
        final boolean isLTier;
        /** True if the router is in the S tier. */
        final boolean isSlow;
        /** True if the router is degraded. */
        final boolean isDegraded;
        /** True if the router is banned. */
        final boolean isBanned;

        /**
         * Records the searchable fields of a router info.
         *
         * @param version the version string
         * @param caps the capabilities string
         * @param bw the bandwidth class
         * @param ip the IP address text
         * @param ourIP our IP as the peer sees it
         * @param hasIP true if the router info has an IP
         * @param unreachable true if the router was marked unreachable
         * @param reachable true if the router was marked reachable
         * @param isOld true if the router info is old
         * @param isInvalidVersion true if the version is invalid
         * @param isLTier true if the router is in the L tier
         * @param isSlow true if the router is in the S tier
         * @param isDegraded true if the router is degraded
         * @param isBanned true if the router is banned
         */
        RiFlags(String version, String caps, String bw, String ip, String ourIP,
                boolean hasIP, boolean unreachable, boolean reachable,
                boolean isOld, boolean isInvalidVersion, boolean isLTier, boolean isSlow,
                boolean isDegraded, boolean isBanned) {
            this.version = version; this.caps = caps; this.bw = bw; this.ip = ip; this.ourIP = ourIP;
            this.hasIP = hasIP; this.unreachable = unreachable; this.reachable = reachable;
            this.isOld = isOld; this.isInvalidVersion = isInvalidVersion; this.isLTier = isLTier;
            this.isSlow = isSlow; this.isDegraded = isDegraded; this.isBanned = isBanned;
        }
    }

    /**
     * Extract classification flags from raw RouterInfo data.
     *
     * @param key the hash of the router, used for the banlist checks
     * @param ri the router info to classify
     * @return a RiFlags snapshot of the version, capability, bandwidth, IP and
     *         banlist state used by the store decision
     */
    private RiFlags extractRiFlags(Hash key, RouterInfo ri) {
        String version = ri.getVersion();
        String caps = ri.getCapabilities();
        String bw = ri.getBandwidthTier();
        String ip = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(ri));
        boolean hasIP = (ip != null);
        boolean unreachable = caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 || caps.indexOf(Router.CAPABILITY_REACHABLE) < 0;
        boolean reachable = caps.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
        int stored = countStoredRIs();

        boolean isOld = VersionComparator.comp(version, MIN_VERSION) < 0;
        boolean isInvalidVersion = Banlist.isInvalidVersion(version);
        boolean isLTier = "L".equals(bw);
        boolean isSlow;
        if (stored < 500) {
            isSlow = "K".equals(bw) || isLTier;
        } else if (stored < 1000) {
            isSlow = "K".equals(bw) || isLTier || "M".equals(bw);
        } else if (stored < 2000) {
            isSlow = "K".equals(bw) || isLTier || "M".equals(bw) || "N".equals(bw);
        } else {
            isSlow = "K".equals(bw) || isLTier || "M".equals(bw) || "N".equals(bw) || "O".equals(bw);
        }
        boolean isDegraded = caps.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) >= 0 ||
                             caps.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) >= 0 ||
                             caps.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0;
        boolean isBanned = _context.banlist().isBanlistedForever(key) ||
                           _context.banlist().isBanlisted(key) ||
                           _context.banlist().isBanlistedHostile(key);
        String ourIP = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(_context.router().getRouterInfo()));

        return new RiFlags(version, caps, bw, ip, ourIP, hasIP, unreachable, reachable,
                           isOld, isInvalidVersion, isLTier, isSlow, isDegraded, isBanned);
    }

    /**
     * Should this RouterInfo be persisted to disk?
     * Returns true only for reachable, non-degraded peers with sufficient bandwidth.
     *
     * @param f the pre-computed classification flags for the peer
     * @return false if the RI should be skipped or deleted
     */
    private static boolean shouldStoreRi(RiFlags f) {
        return !f.isBanned && !f.isSlow && !f.isInvalidVersion && !f.isOld
            && f.hasIP && !f.unreachable && !f.isDegraded;
    }

    /**
     * Detect IP spoofing and apply ban decisions for non-storeable peers.
     * Called only for peers that failed {@link #shouldStoreRi(RiFlags)} and are not banned.
     *
     * @param key the hash of the peer being checked
     * @param ri the peer's router info, used for the spoof check and ban logging
     * @param f the pre-computed classification flags for the peer
     * @return true if the on-disk file should be deleted
     */
    private boolean checkSpoofAndBan(Hash key, RouterInfo ri, RiFlags f) {
        boolean shouldDelete = false;

        // IP spoof detection — skip if firewalled (introducer IP in our RI)
        if (f.hasIP && !_context.commSystem().getStatus().toString().contains("Firewalled")) {
            boolean peerIsFirewalled = ri.isHidden() || f.unreachable;
            if (f.ip != null && f.ip.equals(f.ourIP) && !peerIsFirewalled) {
                if (_log.shouldWarn()) {
                    _log.warn("Banning and disconnecting from [" + key.toShortBase64() + "] for 72h -> Router is spoofing our IP address!");
                }
                _banLogger.logBan(key, f.ip, "Spoofed IP address (ours)", 72L*60*60*1000, ri);
                _context.banlist().banlistRouter(key, "Spoofed IP address (ours)", null, null, _context.clock().now() + 72L*60*60*1000);
                _context.simpleTimer2().addEvent(new Disconnector(key, "Spoofed IP address"), 3L*1000);
                shouldDelete = true;
            }
        }

        if (f.isInvalidVersion) {
            if (_log.shouldDebug()) {
                _log.debug("Not writing RouterInfo [" + key.toShortBase64() + "] to disk -> Invalid version in RouterInfo (" + f.version + ")");
            }
            if (_log.shouldWarn()) {
                _log.warn("Banning for 24h and disconnecting from Router [" + key.toShortBase64() + "]" +
                          " -> Invalid version " + f.version + " / " + f.bw + (f.unreachable ? "U" : ""));
                _banLogger.logBan(key, f.ip != null ? f.ip : "UNKNOWN", "Invalid Router version (" + f.version + " / " + f.bw + ")", 24L*60*60*1000, ri);
                String flag = f.unreachable ? "U" : f.reachable ? "R" : "";
                _context.banlist().banlistRouter(key, "Invalid Router version (" + f.version + " / " + f.bw +
                                                 flag + ")", null, null, _context.clock().now() + 24L*60*60*1000);
            }
            _context.simpleTimer2().addEvent(new Disconnector(key, "Invalid version"), 11L*60*1000);
            shouldDelete = true;
        } else if (f.isLTier && f.unreachable) {
            if (_log.shouldDebug()) {
                _log.debug("Not writing RouterInfo [" + key.toShortBase64() + "] to disk -> LU Router");
            }
            if (_context.banlist().isLuBanEnabled()) {
                if (_log.shouldInfo()) {
                    _log.info("Banning [" + key.toShortBase64() + "] for 1h -> LU Router");
                    _banLogger.logBan(key, f.ip != null ? f.ip : "UNKNOWN", "LU Router", 60L*60*1000, ri);
                    _context.banlist().banlistRouter(key, "LU Router", null, null, _context.clock().now() + 60L*60*1000);
                }
            }
            _context.simpleTimer2().addEvent(new Disconnector(key, "Invalid version"), 11L*60*1000);
            shouldDelete = true;
        } else if (f.unreachable) {
            if (_log.shouldDebug()) {
                _log.debug("Not writing RouterInfo [" + key.toShortBase64() + "] to disk -> Unreachable");
            }
            shouldDelete = true;
        } else if (f.isSlow) {
            if (_log.shouldDebug()) {
                _log.debug("Not writing RouterInfo [" + key.toShortBase64() + "] to disk -> K, L, M or N tier");
            }
            shouldDelete = true;
        } else if (f.isOld) {
            if (_log.shouldDebug()) {
                _log.debug("Not writing RouterInfo [" + key.toShortBase64() + "] to disk -> Older than " + MIN_VERSION);
            }
            shouldDelete = true;
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Not writing RouterInfo [" + key.toShortBase64() + "] to disk -> Local copy is newer");
            }
        }
        return shouldDelete;
    }

    /**
     * Write a single RouterInfo to disk. Skips if the file is already current.
     *
     * @param key the hash of the router, used for the log messages
     * @param data the RouterInfo entry to serialize
     * @param dbFile the destination file; skipped when its last-modified time
     *               is already at or after the entry's publish date
     * @return true if the write failed (file should be deleted)
     * @throws IOException if the output stream cannot be opened or the data
     *                     cannot be written
     */
    private boolean writeRiFile(Hash key, DatabaseEntry data, File dbFile) throws IOException {
        long dataPublishDate = getPublishDate(data);
        if (dbFile.lastModified() >= dataPublishDate) {
            return false;
        }
        try (OutputStream fos = new BufferedOutputStream(new SecureFileOutputStream(dbFile))) {
            data.writeBytes(fos);
            dbFile.setLastModified(dataPublishDate);
            if (_log.shouldDebug()) {
                _log.debug("Writing RouterInfo [" + key.toShortBase64() + "] to disk");
            }
            return false;
        } catch (DataFormatException dfe) {
            _log.error("Error writing out malformed object as [" + key.toShortBase64() + "]: " + data, dfe);
            return true;
        }
    }

    /**
     * Persist a single RouterInfo to disk, applying spoof/ban checks.
     *
     * @param key the hash of the router to write
     * @param data the entry to write; a null entry is ignored
     */
    private void write(Hash key, DatabaseEntry data) {
        if (isShuttingDown(_context)) {return;}

        if (data == null) {
            if (_log.shouldWarn()) {_log.warn("Attempted to write NULL data for key: " + key);}
            return;
        }

        RouterInfo ri = (RouterInfo) data;
        boolean isUs = key.equals(_context.routerHash());
        RiFlags f = extractRiFlags(key, ri);

        File dbFile = null;
        String filename;
        boolean shouldDelete = false;

        try {
            if (data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                filename = getRouterInfoName(key);
            } else {
                throw new IOException("We don't know how to write objects of type " + data.getClass().getName());
            }
            dbFile = new File(_dbDir, filename);

            boolean shouldStore = shouldStoreRi(f);
            if (shouldStore || isUs) {
                shouldDelete = writeRiFile(key, data, dbFile);
            } else if (!isUs && !f.isBanned) {
                shouldDelete = checkSpoofAndBan(key, ri, f);
            }
        } catch (IOException ioe) {
            if (_log.shouldDebug()) {
                _log.error("Error writing to disk", ioe);
            } else {
                _log.error("Error writing to disk (" + ioe.getMessage() + ")");
            }
        }
        if (shouldDelete && dbFile != null) {dbFile.delete();}
    }

    /** Cached count of the router info entries. */
    private int cachedRiCount = 0;
    /** When the cached count was taken. */
    private long lastRiCountTime = 0;
    /** How long the cached count stays valid. */
    private static final long RI_CACHE_TTL = WRITE_DELAY;

    /**
     * Count the number of RouterInfo files currently stored on disk.
     * This includes files in subdirectories if not using flat mode.
     *
     * @return number of RI files on disk
     * @since 0.9.68+
     */
    public int countStoredRIs() {
        long now = System.currentTimeMillis();
        if (lastRiCountTime + RI_CACHE_TTL > now) {
            return cachedRiCount;
        }

        int count;
        if (!_dbDir.exists() || !_dbDir.isDirectory()) {
            count = 0;
        } else if (_flat) {
            File[] files = _dbDir.listFiles(RI_FILTER);
            count = files != null ? files.length : 0;
        } else {
            int total = 0;
            for (int j = 0; j < B64.length(); j++) {
                File subdir = new File(_dbDir, DIR_PREFIX + B64.charAt(j));
                if (!subdir.exists() || !subdir.isDirectory()) {
                    continue;
                }
                File[] files = subdir.listFiles(RI_FILTER);
                if (files != null) {
                    total += files.length;
                }
            }
            count = total;
        }

        // Update cache
        cachedRiCount = count;
        lastRiCountTime = now;

        return count;
    }

    /**
     * Returns the publish date of an entry.
     *
     * @param data the entry to read
     * @return the publish date
     */
    private static long getPublishDate(DatabaseEntry data) {return data.getDate();}

    /**
     * This was mostly for manual reseeding, i.e. the user manually
     * copies RI files to the directory. Nobody does this,
     * so this is run way too often.
     * <p>
     * But it's also for migrating and reading the files after a reseed.
     * Reseed task calls {@link #wakeup()} on completion.
     * As of 0.9.4, also initiates an automatic reseed if necessary.
     */
    private class ReadJob extends JobImpl {
        /** Last modified time. */
        private volatile long _lastModified;
        /** Last reseed time. */
        private volatile long _lastReseed;
        /** Netdb ready flag. */
        private volatile boolean _setNetDbReady;
        /** Minimum routers before netdb is considered ready. */
        private static final int MIN_ROUTERS = KademliaNetworkDatabaseFacade.MIN_RESEED;
        /** Minimum time between automatic reseed attempts. */
        private static final long MIN_RESEED_INTERVAL = 90*60*1000L;
        /** Whether the initial scan has completed at least once. */
        private volatile boolean hasRun = false;
        /**
         * RouterInfo file counts from the last pass that listed each base-64
         * sub-directory, in {@code B64} order, valid only while the modified time
         * in {@link #_dirCountMods} still matches.
         */
        private final int[] _dirCounts = new int[B64.length()];
        /**
         * The sub-directory modified time each count in {@link #_dirCounts} was
         * taken at, in {@code B64} order; 0 means that directory has never been
         * counted.
         */
        private final long[] _dirCountMods = new long[B64.length()];

        /** Job reading the stored entries at startup. */
        public ReadJob() {super(PersistentDataStore.this._context);}

        @Override
        public String getName() {return "Read NetDb";}

        /**
         * Check if the netdb directory or any subdirectory has been modified
         * since the last scan, or if we need to check for reseed.
         * <p>
         * Collects the sub-directory modified times on the way, so {@link
         * #readFiles(long[])} does not stat the same 64 directories a second time
         * - they are what tells it which directories are worth listing.
         *
         * @param dirModifieds non-null, set to each sub-directory's modified time,
         *                     in {@code B64} order, 0 if that directory does not
         *                     exist; ignored in flat mode
         * @return true if the netdb directory or a subdirectory has changed, the
         *         stored routers are too few, or a reseed is due
         */
        private boolean shouldScan(long[] dirModifieds) {
            if (!_flat) {
                for (int j = 0; j < B64.length(); j++) {
                    dirModifieds[j] = new File(_dbDir, DIR_PREFIX + B64.charAt(j)).lastModified();
                }
            }
            long lastMod = _dbDir.lastModified();
            if (lastMod > _lastModified || size() < MIN_ROUTERS + 10) {
                return true;
            }
            if (!_flat) {
                for (int j = 0; j < B64.length(); j++) {
                    if (dirModifieds[j] > _lastModified) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Decide whether to reseed based on router count and time since last reseed.
         *
         * @param routerCount the number of RouterInfo files loaded, passed to the reseed checker
         */
        private void checkReseed(int routerCount) {
            if (!_initialized) {
                _initialized = true;
                purgeSlowRouters();
                int storedCount = countStoredRIs();
                if (_log.shouldDebug()) {
                    _log.debug("Initial RouterInfo load complete -> Ram / Disk: " + size() + " / " + storedCount);
                }
                if (_facade.reseedChecker().checkReseed(routerCount)) {
                    _lastReseed = _context.clock().now();
                } else if (!_setNetDbReady) {
                    _setNetDbReady = true;
                    _context.router().setNetDbReady();
                }
            } else if (_lastReseed < _context.clock().now() - MIN_RESEED_INTERVAL) {
                int count = Math.max(routerCount, size());
                if (count < MIN_ROUTERS) {
                    if (_facade.reseedChecker().checkReseed(count)) {
                        _lastReseed = _context.clock().now();
                    }
                } else if (!_setNetDbReady) {
                    _setNetDbReady = true;
                    _context.router().setNetDbReady();
                }
            } else if (!_setNetDbReady) {
                int count = Math.max(routerCount, size());
                if (count >= MIN_ROUTERS) {
                    _setNetDbReady = true;
                    _context.router().setNetDbReady();
                }
            }
        }

        /**
         * Scan for new RouterInfo files, then reschedule. The first run comes
         * soon after startup; later runs use the full read interval.
         */
        @Override
        public void runJob() {
            if (getContext().router().gracefulShutdownInProgress()) {
                return;
            }
            long[] dirModifieds = new long[B64.length()];
            if (shouldScan(dirModifieds)) {
                if (_log.shouldDebug())
                    _log.debug("Scanning for new RouterInfo files in: " + _dbDir);
                synchronized (_dbDir) {readFiles(dirModifieds);}
                _lastModified = System.currentTimeMillis();
            }
            if (!hasRun) {
               requeue(READ_DELAY);
               hasRun = true;
            } else {
               requeue(READ_INTERVAL);
            }
        }

        /** Trigger an immediate rescan. */
        public void wakeup() {requeue(0);}

        /**
         * Whether the netdb has enough routers to be usable.
         *
         * @return true once the netdb has enough routers to be usable
         */
        public boolean isNetDbReady() {return _setNetDbReady;}

        /**
         * Read all RouterInfo files from disk into memory.
         * Handles both flat and hierarchical directory layouts.
         * Must be called under synchronized(_dbDir).
         *
         * @param dirModifieds the sub-directory modified times {@link
         *                     #shouldScan(long[])} collected, in {@code B64} order
         */
        private void readFiles(long[] dirModifieds) {
            int routerCount = 0;
            File[] routerInfoFiles = _dbDir.listFiles(RI_FILTER);

            if (_flat) {
                routerCount = readFlatFiles(routerInfoFiles);
            } else {
                routerCount = readHierarchicalFiles(routerInfoFiles, dirModifieds);
            }

            checkReseed(routerCount);
        }

        /**
         * Read RouterInfo files from the flat (non-subdirectory) layout.
         *
         * @param routerInfoFiles the files listed from the root directory;
         *                        null means there was nothing to read
         * @return total router count
         */
        private int readFlatFiles(File[] routerInfoFiles) {
            int routerCount = 0;
            if (routerInfoFiles == null) {return routerCount;}
            routerCount = routerInfoFiles.length;
            for (int i = 0; i < routerInfoFiles.length; i++) {
                if (!_context.router().isAlive()) {break;}
                Hash key = getRouterInfoHash(routerInfoFiles[i].getName());
                if (key != null) {
                    (new ReadRouterJob(routerInfoFiles[i], key)).runJob();
                }
                routerCount = Math.max(_context.netDb().getKnownRouters() - 1, i);
            }
            return routerCount;
        }

        /**
         * Read RouterInfo files from the hierarchical (subdirectory) layout.
         * Migrates root-level files, reads the most recently modified ones first,
         * and triggers intermediate netdbReady events on slow systems.
         * <p>
         * A sub-directory whose modified time has not advanced since the previous
         * scan holds the same files it held then, so it is neither listed nor
         * read: its file count is taken from the cache keyed on that time. That
         * is what keeps a rescan from walking every RouterInfo file in the
         * database every five minutes.
         *
         * @param routerInfoFiles root-level RouterInfo files to migrate into the
         *                        subdirectories, may be null
         * @param dirModifieds the sub-directory modified times {@link
         *                     #shouldScan(long[])} collected, in {@code B64} order
         * @return total router count
         */
        private int readHierarchicalFiles(File[] routerInfoFiles, long[] dirModifieds) {
            int routerCount = 0;
            if (routerInfoFiles != null) {migrate(_dbDir, routerInfoFiles);}

            List<File> toRead = new ArrayList<>(2048);
            for (int j = 0; j < B64.length(); j++) {
                File subdir = new File(_dbDir, DIR_PREFIX + B64.charAt(j));
                long lastMod = dirModifieds[j];
                if (!shouldListSubDir(lastMod, _lastModified, routerCount, MIN_ROUTERS)) {continue;}
                if (!shouldReadSubDir(lastMod, _lastModified)) {
                    int counted = cachedSubDirCount(lastMod, _dirCountMods[j], _dirCounts[j]);
                    if (counted >= 0) {
                        // Same modified time means the same entries, so report the
                        // count without allocating one File per entry to re-derive it.
                        routerCount += counted;
                        continue;
                    }
                }
                File[] files = subdir.listFiles(RI_FILTER);
                if (files == null) {
                    _dirCountMods[j] = lastMod;
                    _dirCounts[j] = 0;
                    continue;
                }
                _dirCountMods[j] = lastMod;
                _dirCounts[j] = files.length;
                routerCount += files.length;
                if (!shouldReadSubDir(lastMod, _lastModified)) {continue;}
                for (int i = 0; i < files.length; i++) {
                    toRead.add(files[i]);
                    routerCount = Math.max(_context.netDb().getKnownRouters() - 1, i);
                }
            }
            sortForRead(toRead);

            int i = 0;
            for (File file : toRead) {
                if (i >= MAX_ROUTERS_INIT && !_initialized) {
                    file.delete();
                    continue;
                }
                Hash key = getRouterInfoHash(file.getName());
                if (key != null) {
                    ReadRouterJob rrj = new ReadRouterJob(file, key);
                    if (!rrj.read())
                        continue;
                    if (i++ == 150 && SystemVersion.isSlow() && !_initialized) {
                        _setNetDbReady = true;
                        _context.router().setNetDbReady();
                    } else if (i == 1000 && !_setNetDbReady) {
                        _setNetDbReady = true;
                        _context.router().setNetDbReady();
                    }
                }
            }
            return routerCount;
        }
    }

    /**
     * Whether a sub-directory holds RouterInfo files this scan has not read yet.
     *
     * @param dirModified the sub-directory's modified time, 0 if it does not exist
     * @param lastModified when the previous scan finished, 0 before the first one
     * @return true if files were added to or removed from the directory since the previous scan
     * @since 0.9.72+
     */
    static boolean shouldReadSubDir(long dirModified, long lastModified) {
        return dirModified > lastModified;
    }

    /**
     * Whether a sub-directory has to be listed at all during this scan.
     * <p>
     * The listing serves two purposes only: reading the new files, and topping
     * up the RouterInfo count handed to the reseed check until that count reaches
     * {@code minRouters}. Past that point an unchanged directory contributes
     * nothing, so listing it allocates one {@code File} per router for nothing.
     *
     * @param dirModified the sub-directory's modified time, 0 if it does not exist
     * @param lastModified when the previous scan finished, 0 before the first one
     * @param routerCount the RouterInfo files counted so far during this scan
     * @param minRouters the count the reseed check is satisfied with
     * @return true if the directory must be listed this scan
     * @since 0.9.72+
     */
    static boolean shouldListSubDir(long dirModified, long lastModified, int routerCount, int minRouters) {
        return dirModified > lastModified || routerCount < minRouters;
    }

    /**
     * The RouterInfo file count of a sub-directory that has not changed since it
     * was last counted.
     * <p>
     * Creating, renaming or deleting an entry is what changes a directory's
     * modified time, so a matching time proves the entry list, and with it the
     * count, is still what was counted then.
     *
     * @param dirModified the sub-directory's modified time now, 0 if it does not exist
     * @param cachedModified the time the cached count was taken at, 0 if there is none
     * @param cachedCount the count taken at {@code cachedModified}
     * @return the count still valid for {@code dirModified}, or -1 if it has to be re-derived
     * @since 0.9.72+
     */
    static int cachedSubDirCount(long dirModified, long cachedModified, int cachedCount) {
        return dirModified > 0 && cachedModified == dirModified ? cachedCount : -1;
    }

    /**
     * Order the candidate RouterInfo files by modified time, newest first, so the
     * {@code MAX_ROUTERS_INIT} files a capped startup read reaches are the routers
     * most recently seen up and running, and the first tunnels are built out of
     * recently active peers instead of whichever entries happen to sort first.
     * <p>
     * A RouterInfo file is rewritten whenever the router is re-stored, which the
     * router does when a transport is established with a peer, so the modified
     * time tracks recent reachability rather than the date the entry was first
     * published. It is also uncorrelated with the hash prefix the file is filed
     * under, so this removes the load bias the base-64 directory order gave a
     * capped read. Files past the cap, the ones deleted as excess, are now the
     * stalest entries in the database rather than an arbitrary tail.
     * <p>
     * Each file is stat'ed once and the time carried through the sort, so the
     * comparator does not re-read it once per comparison. Ties keep the listing
     * order, as the sort is stable.
     *
     * @param toRead the candidate files, reordered in place, newest first
     * @since 0.9.72+
     */
    static void sortForRead(List<File> toRead) {
        int n = toRead.size();
        if (n < 2) {return;}
        ModifiedFile[] sorted = new ModifiedFile[n];
        int i = 0;
        for (File file : toRead) {
            sorted[i++] = new ModifiedFile(file);
        }
        Arrays.sort(sorted);
        toRead.clear();
        for (ModifiedFile mf : sorted) {
            toRead.add(mf._file);
        }
    }

    /**
     * A candidate RouterInfo file paired with the modified time read for it, so a
     * sort of the candidates stats each file once instead of once per comparison.
     *
     * @since 0.9.72+
     */
    private static class ModifiedFile implements Comparable<ModifiedFile> {
        /** The candidate RouterInfo file. */
        final File _file;

        /** When the file was last written, 0 if it is gone or unreadable. */
        final long _modified;

        /**
         * Stats the file once, at construction.
         *
         * @param file the candidate RouterInfo file
         */
        ModifiedFile(File file) {
            _file = file;
            long modified = file.lastModified();
            // A vanished or unreadable file reports 0; keep it at the end of the
            // list instead of letting a negative time sort it as the newest.
            _modified = modified > 0 ? modified : 0;
        }

        /**
         * Orders newest modified time first.
         *
         * @param other the candidate to compare against
         * @return a negative value if this file was written after the other
         */
        @Override
        public int compareTo(ModifiedFile other) {
            return Long.compare(other._modified, _modified);
        }
    }

    /** Reads a single RouterInfo file from disk and stores it in memory. */
    private class ReadRouterJob extends JobImpl {
        /** Router file to read. */
        private final File _routerFile;
        /** Hash key of the router. */
        private final Hash _key;
        /** Published date of the cached entry. */
        private long _knownDate;

        /**
         * Creates a job to read a RouterInfo from disk.
         *
         * @param routerFile the on-disk RouterInfo file to read
         * @param key must match the RI hash in the file
         */
        public ReadRouterJob(File routerFile, Hash key) {
            super(PersistentDataStore.this._context);
            _routerFile = routerFile;
            _key = key;
        }

        @Override
        public String getName() {return "Read RouterInfo";}

        /**
         * Whether the file is newer than the cached entry.
         *
         * @return true if the file is newer than the cached entry
         */
        private boolean shouldRead() {
            // persist = false to call only super.get()
            DatabaseEntry data = get(_key, false);
            if (data == null) return true;
            if (data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                _knownDate = ((RouterInfo)data).getPublished();
                long fileDate = _routerFile.lastModified();
                // don't overwrite recent netdb RIs with reseed data
                return fileDate > _knownDate + (60*60*1000);
            } else {
                // safety measure - prevent injection from reseeding
                _log.error("Prevented LeaseSet overwrite by RouterInfo [" + _key.toShortBase64() + "] from " + _routerFile);
                return false;
            }
        }

        /**
         * Read a RouterInfo from disk and load it into memory.
         */
        @Override
        public void runJob() {
            read();
        }

        /**
         * Reads the RouterInfo from disk.
         *
         * @return true if the RouterInfo was read and stored successfully
         * @since 0.9.58
         */
        public boolean read() {
            if (_routerFile.length() > RouterInfo.MAX_UNCOMPRESSED_SIZE) {
                if (_log.shouldWarn())
                    _log.warn("RouterInfo file [" + _routerFile + "] exceeds maximum permitted size of 4KB -> " + _routerFile.length() + "bytes");
                _routerFile.delete();
                return false;
            }
            if (!shouldRead()) return false;
            if (_log.shouldDebug())
                _log.debug("Reading " + _routerFile);

                boolean corrupt = false;
                try (InputStream fis = new BufferedInputStream(new FileInputStream(_routerFile))) {
                    RouterInfo ri = new RouterInfo();
                    ri.readBytes(fis, true);  // true = verify sig on read
                    Hash h = ri.getIdentity().calculateHash();
                    String v = ri.getVersion();
                    String MIN_VERSION = "0.9.64";
                    String ip = null;
                    String truncHash = "";
                    Hash us = _context.routerHash();
                    boolean isUs = ri != null && us.equals(ri.getIdentity().getHash());
                    boolean isOldVersion = ri != null && VersionComparator.comp(v, MIN_VERSION) < 0;
                    boolean isSlow = ri != null && (ri.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                                     ri.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                                     ri.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0 ||
                                     ri.getCapabilities().indexOf(Router.CAPABILITY_BW64) >= 0) && !isUs;
                    boolean isFF = false;
                    boolean hasIP = false;
                    String caps = "unknown";
                    if (ri != null) {
                        truncHash = h.toShortBase64();
                        caps = ri.getCapabilities();
                        ip = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(ri));
                        if (caps.contains("f")) {
                            isFF = true;
                        }
                        if (ip != null) {
                            hasIP = true;
                        }
                    }
                    long now = _context.clock().now();
                    if (ri.getNetworkId() != _networkID) {
                        corrupt = true;
                        if (_log.shouldError())
                            _log.error("Router [" + truncHash + "] is from a different network");
                        _routerFile.delete();
                    } else if (!h.equals(_key)) {
                        // prevent injection from reseeding
                        // this is checked in KNDF.validate() but catch it sooner and log as error.
                        corrupt = true;
                        if (_log.shouldWarn())
                            _log.warn("RouterInfo [" + truncHash + "] does not match [" + _key.toShortBase64() + "] from " + _routerFile);
                        _log.warn("Banning: [" + truncHash + "] for 1h -> Corrupt RouterInfo");
                        _banLogger.logBan(_key, _context, "Corrupt RouterInfo", 60L*60*1000);
                        _context.banlist().banlistRouter(_key, "Corrupt RouterInfo", null, null, now + 60L*60*1000);
                        _routerFile.delete();
                    } else if (ri.getPublished() <= _knownDate) {
                        // Don't store but don't delete
                        if (_log.shouldInfo())
                            _log.info("Skipping since NetDb copy is newer than " + _routerFile);
                    } else if (isSlow || isOldVersion) {
                        // don't store unreachable, K,L,M tier or older peers & delete any existing ri files
                        corrupt = true;
                        if (_log.shouldInfo()) {
                            if (ri.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 || ri.getAddresses().isEmpty())
                                _log.info("Not writing RouterInfo [" + truncHash + "] to disk -> Unreachable");
                            else if (isSlow)
                                _log.info("Not writing RouterInfo [" + truncHash + "] to disk -> K, L or M tier");
                            else if (isOldVersion)
                                _log.info("Not writing RouterInfo [" + truncHash + "] to disk -> Older than " + MIN_VERSION);
                        }
                    } else if (getContext().blocklist().isBlocklisted(ri)) {
                        corrupt = true;
                        if (_log.shouldWarn())
                            _log.warn("Not writing RouterInfo [" + truncHash + "] to disk -> Blocklisted");
                    } else {
                        try {
                            // persist = false so we don't write what we just read
                            _facade.store(ri.getIdentity().getHash(), ri, false);
                            // when heardAbout() was removed from TransientDataStore, it broke
                            // profile bootstrapping for new routers,
                            // so add it here.
                            getContext().profileManager().heardAbout(ri.getIdentity().getHash(), ri.getPublished());
                        } catch (IllegalArgumentException iae) {
                            corrupt = true;
                            if (_log.shouldInfo())
                                _log.info("Rejected locally loaded RouterInfo [" + truncHash + "]\n* " + iae.getMessage());
                        }
                    }
                } catch (DataFormatException dfe) {
                    corrupt = true;
                    if (_log.shouldInfo())
                        _log.info("Deleted " + _routerFile.getName() + " -> File is corrupt \n* " + dfe.getMessage());
                } catch (IOException ioe) {
                    corrupt = true;
                    if (_log.shouldInfo())
                        _log.info("Deleted " + _routerFile.getName() + " -> Unable to read Router reference \n* " + ioe.getMessage());
                } catch (RuntimeException e) {
                    // key certificate problems, etc., don't let one bad RI kill the whole thing
                    corrupt = true;
                    if (_log.shouldInfo())
                        _log.info("Deleted " + _routerFile.getName() + " -> Unable to read Router reference \n* " + e.getMessage());
                }
                if (corrupt) _routerFile.delete();
                return !corrupt;
        }
    }

    /**
     * At startup, this method scans existing RouterInfos and removes any
     * categorized as slow, determined by the total number stored on disk.
     * @since 0.9.68+
     */
    private void purgeSlowRouters() {
        int totalStored = countStoredRIs();
        if (totalStored < 500) {
            if (_log.shouldInfo()) {
                _log.info("Not deleting slow RouterInfos: only " + totalStored + " routers on disk (too few).");
            }
            return;
        }

        if (_log.shouldInfo()) {
            _log.info("Scanning for slow RouterInfos to delete (total stored: " + totalStored + ")");
        }

        FileFilter routerInfoFilter = new FileSuffixFilter(ROUTERINFO_PREFIX, ROUTERINFO_SUFFIX);
        int deletedCount = 0;

        File[] files;
        if (_flat) {
            files = _dbDir.listFiles(routerInfoFilter);
        } else {
            List<File> allFiles = new ArrayList<>();
            for (int j = 0; j < B64.length(); j++) {
                File subdir = new File(_dbDir, DIR_PREFIX + B64.charAt(j));
                if (subdir.isDirectory()) {
                    File[] subFiles = subdir.listFiles(routerInfoFilter);
                    if (subFiles != null) {
                        Collections.addAll(allFiles, subFiles);
                    }
                }
            }
            files = allFiles.toArray(new File[0]);
        }

        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            try {
                Hash key = getRouterInfoHash(file.getName());
                if (key == null) {
                    continue;
                }

                // readFiles() parsed and signature-verified these files seconds
                // ago, so the tier and the capabilities of every RouterInfo it
                // kept are already in memory; only a file it did not keep has to
                // be parsed again to be judged.
                RouterInfo ri = resolveRouterInfo(routerInfoInMemory(key), file);

                // Determine if this is a slow router
                String bw = ri.getBandwidthTier();
                String caps = ri.getCapabilities();
                if (!shouldPurgeRouter(bw, caps, totalStored)) {
                    continue;
                }
                if (isSlowTier(bw, totalStored)) {
                    if (_log.shouldInfo()) {
                        _log.info("Deleting slow (" + bw + ") RouterInfo: [" + key.toShortBase64() + "]");
                    }
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Deleting degraded (" + bw + ") RouterInfo: [" + key.toShortBase64() + "]");
                    }
                }
                file.delete();
                deletedCount++;

            } catch (Exception e) {
                if (_log.shouldWarn()) {
                    _log.warn("Error reading RouterInfo from file: " + file.getName(), e);
                }
            }
        }

        if (_log.shouldInfo()) {
            _log.info("Deleted " + deletedCount + " slow/degraded RouterInfo files");
        }
    }

    /**
     * The RouterInfo this store holds in memory for a key, without touching disk.
     *
     * @param key the router hash to look up
     * @return the RouterInfo held in memory, or null if this store holds no
     *         RouterInfo for that key
     */
    private RouterInfo routerInfoInMemory(Hash key) {
        // super.get(), not get(key, false): the persistent store's lookups never
        // reach disk anyway, and this states the intent without the flag.
        DatabaseEntry data = super.get(key);
        return data instanceof RouterInfo ? (RouterInfo)data : null;
    }

    /**
     * The RouterInfo to judge for a stored file: the one held in memory when
     * there is one, otherwise the file read back from disk.
     * <p>
     * Reading a file costs a stream, a SHA-256 over the payload and a signature
     * verify. {@link ReadJob} has just paid all three for every file it loaded,
     * so re-reading them here to recover two strings doubles the cost of the
     * startup netdb pass for nothing.
     *
     * @param inMemory the RouterInfo held in memory for the file's key, or null if there is none
     * @param file the RouterInfo file on disk, read only if {@code inMemory} is null
     * @return {@code inMemory} if it is non-null, otherwise the RouterInfo decoded from {@code file}
     * @throws DataFormatException if the file had to be read and does not hold a valid RouterInfo
     * @throws IOException if the file had to be read and could not be opened
     * @since 0.9.72+
     */
    static RouterInfo resolveRouterInfo(RouterInfo inMemory, File file) throws IOException, DataFormatException {
        if (inMemory != null) {return inMemory;}
        RouterInfo ri = new RouterInfo();
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            ri.readBytes(fis, true); // verify sig
        }
        return ri;
    }

    /**
     * Whether a bandwidth tier is too slow to keep in the netdb at the current
     * size of the database.
     * <p>
     * The cut-off moves with how many routers are stored: a full netdb can afford
     * to drop the slowest tiers, a nearly empty one has to keep what it can get.
     *
     * @param bwTier the tier as {@link RouterInfo#getBandwidthTier()} returns it, a single
     *               letter or "Unknown", null tolerated
     * @param totalStored the number of RouterInfo files on disk
     * @return true for K, L or M at any size, for N from 1000 stored, and for O from 2000
     *         stored; any other tier, including "Unknown", is kept
     * @since 0.9.72+
     */
    static boolean isSlowTier(String bwTier, int totalStored) {
        if (bwTier == null || bwTier.length() != 1) {return false;}
        char tier = bwTier.charAt(0);
        if (tier == Router.CAPABILITY_BW12 || tier == Router.CAPABILITY_BW32 || tier == Router.CAPABILITY_BW64) {
            return true;
        }
        if (tier == Router.CAPABILITY_BW128) {return totalStored >= 1000;}
        return tier == Router.CAPABILITY_BW256 && totalStored >= 2000;
    }

    /**
     * Whether a router's capabilities say it is too congested to route tunnels
     * for anyone else, whatever its bandwidth tier.
     *
     * @param capabilities the capability string as {@link RouterInfo#getCapabilities()} returns
     *                     it, an empty string if none, null tolerated
     * @return true if it reports moderate or severe congestion, or that it cannot build tunnels
     * @since 0.9.72+
     */
    static boolean isDegradedCapabilities(String capabilities) {
        if (capabilities == null) {return false;}
        return capabilities.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) >= 0 ||
               capabilities.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) >= 0 ||
               capabilities.indexOf(Router.CAPABILITY_NO_TUNNELS) >= 0;
    }

    /**
     * Whether a stored RouterInfo should be deleted from the netdb.
     *
     * @param bwTier the router's bandwidth tier, as {@link #isSlowTier(String, int)} takes it
     * @param capabilities the router's capabilities, as {@link #isDegradedCapabilities(String)} takes them
     * @param totalStored the number of RouterInfo files on disk, which sets the tier cut-off
     * @return true if the router is slow for this netdb size or reports itself degraded
     * @since 0.9.72+
     */
    static boolean shouldPurgeRouter(String bwTier, String capabilities, int totalStored) {
        return isSlowTier(bwTier, totalStored) || isDegradedCapabilities(capabilities);
    }

    /**
     * Resolve and prepare the database directory, creating subdirs if needed.
     *
     * @param dbDir the database directory path, relative to the router directory
     * @return the directory, created and verified readable and writable
     * @throws IOException if the directory cannot be created, is not a
     *                     directory, or is not readable or writable
     */
    private File getDbDir(String dbDir) throws IOException {
        File f = new SecureDirectory(_context.getRouterDir(), dbDir);
        if (!f.exists()) {
            boolean created = f.mkdirs();
            if (!created)
                throw new IOException("Unable to create the NetDb directory [" + f.getAbsolutePath() + "]");
        }
        if (!f.isDirectory())
            throw new IOException("NetDb directory [" + f.getAbsolutePath() + "] is not a directory!");
        if (!f.canRead())
            throw new IOException("NetDb directory [" + f.getAbsolutePath() + "] is not readable!");
        if (!f.canWrite())
            throw new IOException("NetDb directory [" + f.getAbsolutePath() + "] is not writable!");
        if (_flat) {unmigrate(f);}
        else {
            for (int j = 0; j < B64.length(); j++) {
                File subdir = new SecureDirectory(f, DIR_PREFIX + B64.charAt(j));
                if (!subdir.exists()) {subdir.mkdir();}
            }
            File[] routerInfoFiles = f.listFiles(RI_FILTER);
            if (routerInfoFiles != null) {migrate(f, routerInfoFiles);}
        }
        return f;
    }

    /**
     * Migrate from two-level to one-level directory structure.
     * @param dbdir the NetDb root directory whose subdirs are emptied
     * @since 0.9.5
     */
    private static void unmigrate(File dbdir) {
        for (int j = 0; j < B64.length(); j++) {
            File subdir = new File(dbdir, DIR_PREFIX + B64.charAt(j));
            File[] files = subdir.listFiles(RI_FILTER);
            if (files == null) {continue;}
            for (int i = 0; i < files.length; i++) {
                File from = files[i];
                File to = new File(dbdir, from.getName());
                FileUtil.rename(from, to);
            }
        }
    }

    /**
     * Migrate from one-level to two-level directory structure.
     * @param dbdir the NetDb root directory the files currently sit in
     * @param files the RouterInfo files to move into the subdirectories
     * @since 0.9.5
     */
    private static void migrate(File dbdir, File[] files) {
        for (int i = 0; i < files.length; i++) {
            File from = files[i];
            if (!from.isFile()) {continue;}
            File dir = new File(dbdir, DIR_PREFIX + from.getName().charAt(ROUTERINFO_PREFIX.length()));
            File to = new File(dir, from.getName());
            FileUtil.rename(from, to);
        }
    }

    /** Prefix for RouterInfo filenames on disk. */
    private static final String ROUTERINFO_PREFIX = "routerInfo-";
    /** Suffix for RouterInfo filenames on disk. */
    private static final String ROUTERINFO_SUFFIX = ".dat";

    /**
     * File filter for RouterInfo files.
     * @since 0.9.34
     */
    public static final FileFilter RI_FILTER = new FileSuffixFilter(ROUTERINFO_PREFIX, ROUTERINFO_SUFFIX);

    /**
     * Builds the router info file name for a hash.
     *
     * @param hash the hash to name
     * @return the file name
     */
    private String getRouterInfoName(Hash hash) {
        String b64 = hash.toBase64();
        if (_flat)
            return ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX;
        return DIR_PREFIX + b64.charAt(0) + File.separatorChar + ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX;
    }

    /**
     * The persistent RI file for a hash.
     * This is available before the netdb subsystem is running, so we can delete our old RI.
     *
     * @param ctx the router context
     * @param hash the router hash
     * @return non-null, should be absolute, does not necessarily exist
     * @since 0.9.23
     */
    public static File getRouterInfoFile(RouterContext ctx, Hash hash) {
        String b64 = hash.toBase64();
        File dir = new File(ctx.getRouterDir(), ctx.getProperty(KademliaNetworkDatabaseFacade.PROP_DB_DIR, KademliaNetworkDatabaseFacade.DEFAULT_DB_DIR));
        if (ctx.getBooleanProperty(PROP_FLAT))
            return new File(dir, ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX);
        return new File(dir, DIR_PREFIX + b64.charAt(0) + File.separatorChar + ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX);
    }

    /**
     * Package private for installer BundleRouterInfos.
     *
     * @param filename the routerinfo filename, including the prefix and suffix added by {@link #getRouterInfoFile}
     * @return the hash encoded in the name, or null if the name is malformed or the key does not decode
     */
    static Hash getRouterInfoHash(String filename) {
        return getHash(filename, ROUTERINFO_PREFIX, ROUTERINFO_SUFFIX);
    }

    /**
     * Extract hash from a prefixed+suffixed filename.
     *
     * @param filename the file name as stored on disk
     * @param prefix the leading part to strip, e.g. "routerInfo-"
     * @param suffix the trailing part to strip, e.g. ".dat"
     * @return the decoded hash, or null if the name is malformed or the
     *         embedded base64 key does not decode
     */
    private static Hash getHash(String filename, String prefix, String suffix) {
        try {
            String key = filename.substring(prefix.length());
            key = key.substring(0, key.length() - suffix.length());
            byte[] b = Base64.decode(key);
            if (b == null) {return null;}
            return Hash.create(b);
        } catch (RuntimeException e) {return null;}
    }

    /**
     * Delete the RouterInfo file for a hash, if it is present.
     *
     * @param key the hash of the router whose file name is derived
     * @param dir the NetDb root directory the file path is resolved against
     */
    private void removeFile(Hash key, File dir) {
        String riName = getRouterInfoName(key);
        File f = new File(dir, riName);
        if (f.exists()) {
            boolean removed = f.delete();
            if (!removed && f.exists()) {
                if (_log.shouldDebug()) {_log.debug("Unable to delete " + f.getAbsolutePath() + " -> Previously deleted?");}
            } else if (_log.shouldDebug()) {_log.debug("Deleted " + f.getAbsolutePath());}
        }
    }

    /** Schedules a delayed force disconnect for a banned peer. */
    private class Disconnector extends SimpleTimer2.TimedEvent {
        /** Router hash to disconnect. */
        private final Hash h;
        /** Reason for the disconnect. */
        private final String reason;
        /**
         * Job disconnecting a peer after a delay.
         *
         * @param h the peer hash
         * @param reason the reason for the disconnect
         */
        public Disconnector(Hash h, String reason) {super(_context.simpleTimer2()); this.h = h; this.reason = reason;}
        public void timeReached() {_context.commSystem().forceDisconnect(h, reason);}
    }

    /**
     * Check if the router is in a graceful or hard shutdown state.
     *
     * @param ctx the router context
     * @return true if shutdown is in progress
     */
    public static boolean isShuttingDown(RouterContext ctx) {
        int code = ctx.router().scheduledGracefulExitCode();
        return Router.EXIT_GRACEFUL == code || Router.EXIT_HARD == code ||
               Router.EXIT_GRACEFUL_RESTART == code || Router.EXIT_HARD_RESTART == code;
    }

}
