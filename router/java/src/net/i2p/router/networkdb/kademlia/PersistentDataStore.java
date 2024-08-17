package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Base64;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PThread;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;


/**
 * Write out keys to disk when we get them and periodically read ones we don't know
 * about into memory, with newly read routers are also added to the routing table.
 *
 * Public only for access to static methods by startup classes
 *
 */
public class PersistentDataStore extends TransientDataStore {
    private final File _dbDir;
    private final KademliaNetworkDatabaseFacade _facade;
    private final Writer _writer;
    private final ReadJob _readJob;
    private volatile boolean _initialized;
    private final boolean _flat;
    private final int _networkID;

    private final static int READ_DELAY = 2*60*1000;
    private static final String PROP_FLAT = "router.networkDatabase.flat";
    static final String DIR_PREFIX = "r";
    private static final String B64 = Base64.ALPHABET_I2P;
    private static final int MAX_ROUTERS_INIT = SystemVersion.isSlow() ? 2000 : 5000;

    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {
        return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);
    }
    private final static boolean DEFAULT_SHOULD_DISCONNECT = false;
    private final static String PROP_SHOULD_DISCONNECT = "router.enableImmediateDisconnect";

    /**
     *  @param dbDir relative path
     */
    public PersistentDataStore(RouterContext ctx, String dbDir, KademliaNetworkDatabaseFacade facade) throws IOException {
        super(ctx);
        _networkID = ctx.router().getNetworkID();
        _flat = ctx.getBooleanProperty(PROP_FLAT);
//        _flat = ctx.getBooleanPropertyDefaultTrue(PROP_FLAT);
        _dbDir = getDbDir(dbDir);
        _facade = facade;
        _readJob = new ReadJob();
        _context.jobQueue().addJob(_readJob);
        ctx.statManager().createRateStat("netDb.writeClobber", "How often we clobber a pending NetDb write", "NetworkDatabase", new long[] { 20*60*1000 });
        ctx.statManager().createRateStat("netDb.writePending", "Number of pending NetDb writes", "NetworkDatabase", new long[] { 60*1000 });
        ctx.statManager().createRateStat("netDb.writeOut", "Total number of NetDb writes", "NetworkDatabase", new long[] { 20*60*1000 });
        ctx.statManager().createRateStat("netDb.writeTime", "Total time used for NetDb writes ", "NetworkDatabase", new long[] { 20*60*1000 });
        //ctx.statManager().createRateStat("netDb.readTime", "How long one took", "NetworkDatabase", new long[] { 20*60*1000 });
        _writer = new Writer();
        I2PThread writer = new I2PThread(_writer, "DBWriter");
        // stop() must be called to flush data to disk
        //writer.setDaemon(true);
        writer.start();
    }

    @Override
    public boolean isInitialized() { return _initialized; }

    // this doesn't stop the read job or the writer, maybe it should?
    @Override
    public void stop() {
        super.stop();
        _writer.flush();
    }

    @Override
    public void rescan() {
        if (_initialized)
            _readJob.wakeup();
    }

    @Override
    public DatabaseEntry get(Hash key) {
        return get(key, true);
    }

    /**
     *  Prepare for having only a partial set in memory and the rest on disk
     *  @param persist if false, call super only, don't access disk
     */
    @Override
    public DatabaseEntry get(Hash key, boolean persist) {
        DatabaseEntry rv =  super.get(key);
/*****
        if (rv != null || !persist)
            return rv;
        rv = _writer.get(key);
        if (rv != null)
            return rv;
        Job rrj = new ReadRouterJob(getRouterInfoName(key), key));
         run in same thread
        rrj.runJob();
*******/
        return rv;
    }

    @Override
    public DatabaseEntry remove(Hash key) {
        return remove(key, true);
    }

    /*
     *  @param persist if false, call super only, don't access disk
     */
    @Override
    public DatabaseEntry remove(Hash key, boolean persist) {
        if (persist) {
            _writer.remove(key);
        }
        return super.remove(key);
    }

    @Override
    public boolean put(Hash key, DatabaseEntry data) {
        return put(key, data, true);
    }

    /*
     *  @param persist if false, call super only, don't access disk
     *  @return success
     */
    @Override
    public boolean put(Hash key, DatabaseEntry data, boolean persist) {
        if ( (data == null) || (key == null) ) return false;
        boolean rv = super.put(key, data);
        // Don't bother writing LeaseSets to disk
        if (rv && persist && data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
            _writer.queue(key, data);
        return rv;
    }

    /*
     *  Unconditionally store, bypass all newer/older checks.
     *  Persists for RI only.
     *
     *  @param persist if false, call super only, don't access disk
     *  @return success
     *  @param key non-null
     *  @param data non-null
     *  @since 0.9.64
     */
    @Override
    public boolean forcePut(Hash key, DatabaseEntry data) {
        boolean rv = super.forcePut(key, data);
        if (rv && data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
            _writer.queue(key, data);
        return rv;
    }

    /** How many files to write every 5 minutes. Doesn't make sense to limit it,
     *  they just back up in the queue hogging memory.
     */
    private static final int WRITE_LIMIT = 6000;
    private static final long WRITE_DELAY = 5*60*1000;

    /*
     * Queue up writes, write unlimited files every 10 minutes.
     * Since we write all we have, don't save the write order.
     * We store a reference to the data here too,
     * rather than simply pull it from super.get(), because
     * we will soon have to implement a scheme for keeping only
     * a subset of all DatabaseEntrys in memory and keeping the rest on disk.
     */
    private class Writer implements Runnable, Flushable {
        private final Map<Hash, DatabaseEntry>_keys;
        private final Set<Hash> _keysToRemove;
        private final Object _waitLock;
        private volatile boolean _quit;

        public Writer() {
            _keys = new ConcurrentHashMap<Hash, DatabaseEntry>(64);
            _keysToRemove = new ConcurrentHashSet<Hash>();
            _waitLock = new Object();
        }

        public void queue(Hash key, DatabaseEntry data) {
            int pending = _keys.size();
            _keysToRemove.remove(key);
            boolean exists = (null != _keys.put(key, data));
            if (exists)
                _context.statManager().addRateData("netDb.writeClobber", pending);
            _context.statManager().addRateData("netDb.writePending", pending);
        }

        public void remove(Hash key) {
            _keys.remove(key);
            _keysToRemove.add(key);
        }

        /*
         *  @since 0.9.50 was in separate RemoveJob
         */
        private void removeQueued() {
            if (_keysToRemove.isEmpty())
                return;
            for (Iterator<Hash> iter = _keysToRemove.iterator(); iter.hasNext(); ) {
                Hash key = iter.next();
                iter.remove();
                try {
                    removeFile(key, _dbDir);
                } catch (IOException ioe) {
                    if (_log.shouldWarn())
                        _log.warn("Error removing key " + key, ioe);
                }
            }
        }

        public void run() {
            _quit = false;
            Hash key = null;
            DatabaseEntry data = null;
            int count = 0;
            int lastCount = 0;
            long startTime = 0;
            while (true) {
                // get a new iterator every time to get a random entry without
                // having concurrency issues or copying to a List or Array
                Iterator<Map.Entry<Hash, DatabaseEntry>> iter = _keys.entrySet().iterator();
                try {
                    Map.Entry<Hash, DatabaseEntry> entry = iter.next();
                    key = entry.getKey();
                    data = entry.getValue();
                    iter.remove();
                    count++;
                } catch (NoSuchElementException nsee) {
                    lastCount = count;
                    count = 0;
                } catch (IllegalStateException ise) {
                    lastCount = count;
                    count = 0;
                }

                if (key != null) {
                    if (data != null) {
                        // synch with the reader job
                        synchronized (_dbDir) {
                            write(key, data);
                        }
                        data = null;
                    }
                    key = null;
                }
                if (count >= WRITE_LIMIT)
                    count = 0;
                if (count == 0) {
                    removeQueued();
                    if (lastCount > 0) {
                        long time = _context.clock().now() - startTime;
                        if (_log.shouldDebug())
                            _log.debug(lastCount + " RouterInfo files saved to disk in " + time + "ms");
                         _context.statManager().addRateData("netDb.writeOut", lastCount);
                         _context.statManager().addRateData("netDb.writeTime", time);
                    }
                    if (_quit)
                        break;
                    synchronized (_waitLock) {
                        try {
                            _waitLock.wait(WRITE_DELAY);
                        } catch (InterruptedException ie) {}
                    }
                    startTime = _context.clock().now();
                }
            }
        }

        public void flush() {
            synchronized(_waitLock) {
                _quit = true;
                _waitLock.notifyAll();
            }
        }
    }

    private void write(Hash key, DatabaseEntry data) {
        RouterInfo ri = (RouterInfo) data;
        boolean isHidden = _context.router().isHidden();
        boolean isUs = key.equals(_context.routerHash());
        OutputStream fos = null;
        File dbFile = null;
        String filename = null;
        String MIN_VERSION = "0.9.61";
        String CURRENT_VERSION = "0.9.62";
        String v = MIN_VERSION;
        String bw = "K";
        String ip = null;
        String ourIP = null;
        ourIP = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(_context.router().getRouterInfo()));
        long uptime = _context.router().getUptime();
        boolean unreachable = false;
        boolean reachable = false;
        boolean isFF = false;
        boolean hasIP = false;
        boolean noSSU = true;
        boolean isBadFF = isFF && noSSU;
        boolean isOld = VersionComparator.comp(v, MIN_VERSION) < 0;
        boolean isOlderThanCurrent = VersionComparator.comp(v, CURRENT_VERSION) < 0;
        boolean shouldDisconnect = _context.getProperty(PROP_SHOULD_DISCONNECT, DEFAULT_SHOULD_DISCONNECT);
        v = ri.getVersion();
        String caps = ri.getCapabilities();
        unreachable = caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
        reachable = caps.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
        String country = "unknown";
        boolean noCountry = true;
        if (caps.contains("f")) {
            isFF = true;
        }
        if (ip != null) {
            hasIP = true;
        }
        bw = ri.getBandwidthTier();
        for (RouterAddress ra : ri.getAddresses()) {
            if (ra.getTransportStyle().contains("SSU")) {
                    noSSU = false;
                    break;
            }
        }
        country = _context.commSystem().getCountry(key);
        if (country != null && country != "unknown") {
            noCountry = false;
        }

        boolean isSlow = ri != null && (caps != null && caps != "unknown") && bw.equals("K") || bw.equals("L") ||
                         bw.equals("M") || bw.equals("N");
        boolean isLTier = bw.equals("L");
        boolean isBanned = ri != null && (_context.banlist().isBanlistedForever(key) ||
                           _context.banlist().isBanlisted(key) ||
                           _context.banlist().isBanlistedHostile(key));

        String version = ri != null ? ri.getVersion() : "0.8.0";
        boolean isInvalidVersion = VersionComparator.comp(version, "2.5.0") >= 0;

        try {
            if (data.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                filename = getRouterInfoName(key);
            } else {
                throw new IOException("We don't know how to write objects of type " + data.getClass().getName());
            }
            dbFile = new File(_dbDir, filename);
            long dataPublishDate = getPublishDate(data);
            if (enableReverseLookups() && uptime > 30*1000 && hasIP) {
                ip = (ri != null) ? net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(ri)) : null;
                String rl = ip != null ? _context.commSystem().getCanonicalHostName(ip) : null;
            }
//            if ((dbFile.lastModified() < dataPublishDate && ri != null && !unreachable && (!isOld || isFF) &&
//                !isBadFF && !noSSU && !isSlow && !isBanned) || isUs) {
            if (dbFile.lastModified() < dataPublishDate) {
                // our filesystem is out of date, let's replace it
                fos = new SecureFileOutputStream(dbFile);
                fos = new BufferedOutputStream(fos);
                try {
                    data.writeBytes(fos);
                    fos.close();
                    dbFile.setLastModified(dataPublishDate);
                    if (_log.shouldDebug())
                        _log.debug("Writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk");
                } catch (DataFormatException dfe) {
                    _log.error("Error writing out malformed object as [" + key.toBase64().substring(0,6) + "]: " + data, dfe);
                    dbFile.delete();
                }
            } else {
                if (ri != null && !isUs && !isBanned) {
                    if (!isUs && hasIP && ip.equals(ourIP)) {
                        if (_log.shouldDebug())
                            _log.debug("Not writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk -> Router is spoofing our IP address");
                        if (_log.shouldWarn())
                            _log.warn("Banning and disconnecting from [" + key.toBase64().substring(0,6) + "] for 72h -> Router is spoofing our IP address!");
                        _context.banlist().banlistRouter(key, " <b>➜</b> Spoofed IP address (ours)", null, null, _context.clock().now() + 72*60*60*1000);
                        _context.simpleTimer2().addEvent(new Disconnector(key), 3*1000);
                        dbFile.delete();
                    } else if (isInvalidVersion) {
                        if (_log.shouldWarn() && !isBanned)
                            _log.warn("Banning for 24h and disconnecting from Router [" + key.toBase64().substring(0,6) + "]" +
                                      " -> Invalid version " + version + " / " + bw + (unreachable ? "U" : ""));
                        _context.banlist().banlistRouter(key, " <b>➜</b> Invalid Router version (" + version + " / " + bw +
                                                         (unreachable ? "U" : reachable ? "R" : "") + ")", null,
                                                          null, _context.clock().now() + 24*60*60*1000);
                        _context.simpleTimer2().addEvent(new Disconnector(key), 3*1000);
                        dbFile.delete();
                    } else if (isLTier && unreachable && isOlderThanCurrent) {
                        if (_log.shouldDebug())
                            _log.debug("Not writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk -> LU and older than " + CURRENT_VERSION);
                        if (_log.shouldWarn())
                            _log.warn("Banning and disconnecting from [" + key.toBase64().substring(0,6) + "] for 8h -> LU and older than current version");
                        _context.banlist().banlistRouter(key, " <b>➜</b> LU and older than 0.9.59", null, null, _context.clock().now() + 8*60*60*1000);
                        _context.simpleTimer2().addEvent(new Disconnector(key), 3*1000);
                        dbFile.delete();
                    } else if (unreachable) {
                        if (_log.shouldDebug())
                            _log.debug("Not writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk -> Unreachable");
                        dbFile.delete();
/**
                    } else if (isBadFF) {
                        if (_log.shouldDebug())
                            _log.debug("Not writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk -> Floodfill with SSU disabled");
                        dbFile.delete();
**/
                    } else if (isSlow) {
                        if (_log.shouldDebug())
                            _log.debug("Not writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk -> K, L, M or N tier");
                        dbFile.delete();
                    } else if (isOld) {
                        if (_log.shouldDebug())
                            _log.debug("Not writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk -> Older than " + MIN_VERSION);
                        dbFile.delete();
/**
                    } else if (noCountry && uptime > 10*60*1000) {
                        if (_log.shouldDebug())
                            _log.debug("Not writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk -> IP address does not resolve via GeoIP");
                        if (_log.shouldWarn())
                            _log.warn("Banning " + (isFF ? "Floodfill" : "Router") + " [" + key.toBase64().substring(0,6) + "] for 15m -> Address not resolvable via GeoIP");
                        if (isFF) {
                            _context.banlist().banlistRouter(key, " <b>➜</b> Floodfill without GeoIP resolvable address", null, null, _context.clock().now() + 15*60*1000);
                        } else {
                            _context.banlist().banlistRouter(key, " <b>➜</b> No GeoIP resolvable address", null, null, _context.clock().now() + 15*60*1000);
                        }
                        if (shouldDisconnect) {
                            _context.simpleTimer2().addEvent(new Disconnector(key), 3*1000);
                        }
                        dbFile.delete();
**/
                    } else {
                        // we've already written the file, no need to waste our time
                        if (_log.shouldDebug())
                            _log.debug("Not writing RouterInfo [" + key.toBase64().substring(0,6) + "] to disk -> Local copy is newer");
                    }
                }
            }
        } catch (IOException ioe) {
            if (_log.shouldDebug()) {
                _log.error("Error writing to disk", ioe);
            } else {
            _log.error("Error writing to disk (" + ioe.getMessage() + ")");
            }
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }

    private static long getPublishDate(DatabaseEntry data) {
        return data.getDate();
    }

    /**
     *  This was mostly for manual reseeding, i.e. the user manually
     *  copies RI files to the directory. Nobody does this,
     *  so this is run way too often.
     *
     *  But it's also for migrating and reading the files after a reseed.
     *  Reseed task calls wakeup() on completion.
     *  As of 0.9.4, also initiates an automatic reseed if necessary.
     */
    private class ReadJob extends JobImpl {
        private volatile long _lastModified;
        private volatile long _lastReseed;
        private volatile boolean _setNetDbReady;
        private static final int MIN_ROUTERS = KademliaNetworkDatabaseFacade.MIN_RESEED;
        private static final long MIN_RESEED_INTERVAL = 90*60*1000;

        public ReadJob() {
            super(PersistentDataStore.this._context);
        }

        public String getName() { return "Read NetDb"; }

        public void runJob() {
            if (getContext().router().gracefulShutdownInProgress()) {
                // don't cause more disk I/O while saving,
                // or start a reseed
                requeue(READ_DELAY);
                return;
            }
            long now = System.currentTimeMillis();
            // check directory mod time to save a lot of object churn in scanning all the file names
            long lastMod = _dbDir.lastModified();
            // if size() (= RI + LS) is too low, call anyway to check for reseed
            boolean shouldScan = lastMod > _lastModified || size() < MIN_ROUTERS + 10;
            if (!shouldScan && !_flat) {
                for (int j = 0; j < B64.length(); j++) {
                    File subdir = new File(_dbDir, DIR_PREFIX + B64.charAt(j));
                    if (subdir.lastModified() > _lastModified) {
                        shouldScan = true;
                        break;
                    }
                }
            }
            if (shouldScan) {
                if (_log.shouldDebug())
                    _log.debug("Scanning for new RouterInfo files in: " + _dbDir);
                // synch with the writer job
                synchronized (_dbDir) {
                    // _lastModified must be 0 for the first run
                    readFiles();
                }
                _lastModified = now;
            }
            requeue(READ_DELAY);
        }

        public void wakeup() {
            requeue(0);
        }

        private void readFiles() {
            int routerCount = 0;

            File routerInfoFiles[] = _dbDir.listFiles(RI_FILTER);
            if (_flat) {
                if (routerInfoFiles != null) {
                    routerCount = routerInfoFiles.length;
                    for (int i = 0; i < routerInfoFiles.length; i++) {
                        // drop out if the router gets killed right after startup
                        if (!_context.router().isAlive())
                            break;
                        Hash key = getRouterInfoHash(routerInfoFiles[i].getName());
                        if (key != null) {
                            // Run it inline so we don't clog up the job queue, esp. at startup
                            // Also this allows us to wait until it is really done to call checkReseed() and set _initialized
                            //PersistentDataStore.this._context.jobQueue().addJob(new ReadRouterJob(routerInfoFiles[i], key));
                            //long start = System.currentTimeMillis();
                            (new ReadRouterJob(routerInfoFiles[i], key)).runJob();
                            //_context.statManager().addRateData("netDb.readTime", System.currentTimeMillis() - start);
                        }
                    }
                }
            } else {
                // move all new RIs to subdirs, then scan those
                if (routerInfoFiles != null)
                    migrate(_dbDir, routerInfoFiles);
                // Loading the files in-order causes clumping in the kbuckets,
                // and bias on early peer selection, so first collect all the files,
                // then shuffle and load.
                List<File> toRead = new ArrayList<File>(2048);
                for (int j = 0; j < B64.length(); j++) {
                    File subdir = new File(_dbDir, DIR_PREFIX + B64.charAt(j));
                    File[] files = subdir.listFiles(RI_FILTER);
                    if (files == null)
                        continue;
                    long lastMod = subdir.lastModified();
                    if (routerCount >= MIN_ROUTERS && lastMod <= _lastModified)
                        continue;
                    routerCount += files.length;
                    if (lastMod <= _lastModified)
                        continue;
                    for (int i = 0; i < files.length; i++) {
                        toRead.add(files[i]);
                    }
                }
                Collections.shuffle(toRead, _context.random());
                int i = 0;
                for (File file : toRead) {
                    // Take the first 4000 good ones, delete the rest
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
                            // Can take 2 minutes to load them all on Android,
                            // after we have already built expl. tunnels.
                            // This is enough to let i2ptunnel get started.
                            // Do not set _initialized yet so we don't start rescanning.
                            _setNetDbReady = true;
                            _context.router().setNetDbReady();
                        } else if (i == 500 && !_setNetDbReady) {
                            // do this for faster systems also at 500
                            _setNetDbReady = true;
                            _context.router().setNetDbReady();
                        }
                    }
                }
            }

            if (!_initialized) {
                _initialized = true;
                if (_facade.reseedChecker().checkReseed(routerCount)) {
                    _lastReseed = _context.clock().now();
                    // checkReseed will call wakeup() when done and we will run again
                } else {
                    _setNetDbReady = true;
                    _context.router().setNetDbReady();
                }
            } else if (_lastReseed < _context.clock().now() - MIN_RESEED_INTERVAL) {
                int count = Math.min(routerCount, size());
                if (count < MIN_ROUTERS) {
                    if (_facade.reseedChecker().checkReseed(count))
                        _lastReseed = _context.clock().now();
                        // checkReseed will call wakeup() when done and we will run again
                } else {
                    if (!_setNetDbReady) {
                        _setNetDbReady = true;
                        _context.router().setNetDbReady();
                    }
                }
            } else {
                // second time through, reseed called wakeup()
                if (!_setNetDbReady) {
                    int count = Math.min(routerCount, size());
                    if (count >= MIN_ROUTERS) {
                        _setNetDbReady = true;
                        _context.router().setNetDbReady();
                    }
                }
            }
        }
    }

    private class ReadRouterJob extends JobImpl {
        private final File _routerFile;
        private final Hash _key;
        private long _knownDate;

        /**
         *  @param key must match the RI hash in the file
         */
        public ReadRouterJob(File routerFile, Hash key) {
            super(PersistentDataStore.this._context);
            _routerFile = routerFile;
            _key = key;
        }

        public String getName() { return "Read RouterInfo"; }

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
                _log.error("Prevented LeaseSet overwrite by RouterInfo [" + _key.toBase64().substring(0,6) + "] from " + _routerFile);
                return false;
            }
        }

        public void runJob() {
            read();
        }

        /**
         *  @return success
         *  @since 0.9.58
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

                InputStream fis = null;
                boolean corrupt = false;
                try {
                    fis = new FileInputStream(_routerFile);
                    fis = new BufferedInputStream(fis);
                    RouterInfo ri = new RouterInfo();
                    ri.readBytes(fis, true);  // true = verify sig on read
                    Hash h = ri.getIdentity().calculateHash();
                    String v = ri.getVersion();
                    String MIN_VERSION = "0.9.61";
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
                    boolean noSSU = true;
                    String caps = "unknown";
                    if (ri != null) {
                        truncHash = h.toBase64().substring(0,6);
                        caps = ri.getCapabilities();
                        ip = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(ri));
                        if (caps.contains("f")) {
                            isFF = true;
                        }
                        if (ip != null) {
                            hasIP = true;
                        }
                        for (RouterAddress ra : ri.getAddresses()) {
                            if (ra.getTransportStyle().contains("SSU")) {
                                noSSU = false;
                                break;
                            }
                        }
                    }
                    boolean isBadFF = isFF && noSSU;
                    long now = _context.clock().now();

                    if (ri.getNetworkId() != _networkID) {
                        corrupt = true;
                        if (_log.shouldError())
                            _log.error("Router [" + truncHash + "] is from a different network");
                        _routerFile.delete();
/*
                    } else if (!hasIP) {
                        corrupt = true;
                        if (_log.shouldInfo())
                            _log.info("Not writing RouterInfo [" + truncHash + "] to disk -> No IP address");
                        if (_log.shouldWarn())
                            _log.warn("Banning: [" + truncHash + "] for 4h -> No IP address");
                            _context.banlist().banlistRouter(_key, " <b>➜</b> No IP address", null, null, now + 4*60*60*1000);
                    } else if (isBadFF) {
                        corrupt = true;
                        if (_log.shouldInfo())
                            _log.info("Not writing RouterInfo [" + truncHash + "] to disk -> Floodfill with SSU disabled");
                        if (_log.shouldWarn())
                            _log.warn("Banning: [" + truncHash + "] for 8h -> Floodfill with SSU disabled");
                            _context.banlist().banlistRouter(_key, " <b>➜</b> Floodfill with SSU disabled", null, null, now + 8*60*60*1000);
*/
                    } else if (!h.equals(_key)) {
                        // prevent injection from reseeding
                        // this is checked in KNDF.validate() but catch it sooner and log as error.
                        corrupt = true;
                        if (_log.shouldWarn())
                            _log.warn("RouterInfo [" + truncHash + "] does not match [" + _key.toBase64().substring(0,6) + "] from " + _routerFile);
                            _log.warn("Banning: [" + truncHash + "] for 1h -> Corrupt RouterInfo");
                            _context.banlist().banlistRouter(_key, " <b>➜</b> Corrupt RouterInfo", null, null, now + 60*60*1000);
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
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
                if (corrupt) _routerFile.delete();
                return !corrupt;
        }
    }

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
        if (_flat) {
            unmigrate(f);
        } else {
            for (int j = 0; j < B64.length(); j++) {
                File subdir = new SecureDirectory(f, DIR_PREFIX + B64.charAt(j));
                if (!subdir.exists())
                    subdir.mkdir();
            }
            File routerInfoFiles[] = f.listFiles(RI_FILTER);
            if (routerInfoFiles != null)
                migrate(f, routerInfoFiles);
        }
        return f;
    }

    /**
     *  Migrate from two-level to one-level directory structure
     *  @since 0.9.5
     */
    private static void unmigrate(File dbdir) {
        for (int j = 0; j < B64.length(); j++) {
            File subdir = new File(dbdir, DIR_PREFIX + B64.charAt(j));
            File[] files = subdir.listFiles(RI_FILTER);
            if (files == null)
                continue;
            for (int i = 0; i < files.length; i++) {
                File from = files[i];
                File to = new File(dbdir, from.getName());
                FileUtil.rename(from, to);
            }
        }
    }

    /**
     *  Migrate from one-level to two-level directory structure
     *  @since 0.9.5
     */
    private static void migrate(File dbdir, File[] files) {
        for (int i = 0; i < files.length; i++) {
            File from = files[i];
            if (!from.isFile())
                continue;
            File dir = new File(dbdir, DIR_PREFIX + from.getName().charAt(ROUTERINFO_PREFIX.length()));
            File to = new File(dir, from.getName());
            FileUtil.rename(from, to);
        }
    }

    private final static String ROUTERINFO_PREFIX = "routerInfo-";
    private final static String ROUTERINFO_SUFFIX = ".dat";

    /** @since 0.9.34 */
    public static final FileFilter RI_FILTER = new FileSuffixFilter(ROUTERINFO_PREFIX, ROUTERINFO_SUFFIX);

    private String getRouterInfoName(Hash hash) {
        String b64 = hash.toBase64();
        if (_flat)
            return ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX;
        return DIR_PREFIX + b64.charAt(0) + File.separatorChar + ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX;
    }

    /**
     *  The persistent RI file for a hash.
     *  This is available before the netdb subsystem is running, so we can delete our old RI.
     *
     *  @return non-null, should be absolute, does not necessarily exist
     *  @since 0.9.23
     */
    public static File getRouterInfoFile(RouterContext ctx, Hash hash) {
        String b64 = hash.toBase64();
        File dir = new File(ctx.getRouterDir(), ctx.getProperty(KademliaNetworkDatabaseFacade.PROP_DB_DIR, KademliaNetworkDatabaseFacade.DEFAULT_DB_DIR));
//        if (ctx.getBooleanPropertyDefaultTrue(PROP_FLAT))
        if (ctx.getBooleanProperty(PROP_FLAT))
            return new File(dir, ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX);
        return new File(dir, DIR_PREFIX + b64.charAt(0) + File.separatorChar + ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX);
    }

    /**
     *  Package private for installer BundleRouterInfos
     */
    static Hash getRouterInfoHash(String filename) {
        return getHash(filename, ROUTERINFO_PREFIX, ROUTERINFO_SUFFIX);
    }

    private static Hash getHash(String filename, String prefix, String suffix) {
        try {
            String key = filename.substring(prefix.length());
            key = key.substring(0, key.length() - suffix.length());
            //Hash h = new Hash();
            //h.fromBase64(key);
            byte[] b = Base64.decode(key);
            if (b == null)
                return null;
            Hash h = Hash.create(b);
            return h;
        } catch (RuntimeException e) {
            // static
            //_log.warn("Unable to fetch the key from [" + filename + "]", e);
            return null;
        }
    }

    private void removeFile(Hash key, File dir) throws IOException {
        String riName = getRouterInfoName(key);
        File f = new File(dir, riName);
        if (f.exists()) {
            boolean removed = f.delete();
            if (!removed && f.exists()) {
                // change from warn to debug as we're likely to see only when failing to delete a previously deleted RI
                if (_log.shouldDebug())
                    _log.debug("Unable to delete " + f.getAbsolutePath());
            } else if (_log.shouldDebug()) {
                _log.debug("Deleted " + f.getAbsolutePath());
            }
            return;
        }
    }

    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) { this.h = h; }
        public void timeReached() {
            _context.commSystem().forceDisconnect(h);
        }
    }
}
