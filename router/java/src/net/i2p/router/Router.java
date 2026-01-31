package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import freenet.support.CPUInformation.CPUID;
import freenet.support.CPUInformation.UnknownCPUException;
import gnu.getopt.Getopt;
import java.io.File;
import java.io.IOException;
import net.i2p.stat.RateConstants;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.i2p.client.impl.I2PSessionImpl;
import net.i2p.crypto.SigUtil;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.message.GarlicMessageHandler;
import net.i2p.router.networkdb.PublishLocalRouterInfoJob;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.startup.CreateRouterInfoJob;
import net.i2p.router.startup.PortableWorkingDir;
import net.i2p.router.startup.StartupJob;
import net.i2p.router.startup.WorkingDir;
import net.i2p.router.sybil.Analysis;
import net.i2p.router.tasks.*;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.router.transport.UPnPScannerCallback;
import net.i2p.router.transport.ntcp.NTCPTransport;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.util.EventLog;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.ByteCache;
import net.i2p.util.FileUtil;
import net.i2p.util.FortunaRandomSource;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.OrderedProperties;
import net.i2p.util.ReusableGZIPInputStream;
import net.i2p.util.ReusableGZIPOutputStream;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 * Core router implementation that manages I2P node lifecycle, configuration, and subsystem coordination.
 * Handles startup/shutdown, router info management, bandwidth capabilities, and provides the main entry point for both embedded and standalone router operation.
 *
 * For embedded use, instantiate, call setKillVMOnEnd(false), and then call runRouter().
 *
 */
public class Router implements RouterClock.ClockShiftListener {
    private Log _log;
    private final RouterContext _context;
    private final Map<String, String> _config;
    /** full path */
    private String _configFilename;
    private RouterInfo _routerInfo;
    private final ReentrantReadWriteLock _routerInfoLock = new ReentrantReadWriteLock(false);
    private RouterIdentity _routerIdent;
    private Hash _routerHash;
    /** not for external use */
    public final Object routerInfoFileLock = new Object();
    private final Object _configFileLock = new Object();
    private long _started;
    private volatile boolean _killVMOnEnd;
    private int _gracefulExitCode;
    private I2PThread.OOMEventListener _oomListener;
    private ShutdownHook _shutdownHook;
    private I2PThread _gracefulShutdownDetector;
    private RouterWatchdog _watchdog;
    private Thread _watchdogThread;
    private final EventLog _eventLog;
    private final Object _stateLock = new Object();
    private State _state = State.UNINITIALIZED;
    private FamilyKeyCrypto _familyKeyCrypto;
    private boolean _familyKeyCryptoFail;
    public final Object _familyKeyLock = new Object();
    private UPnPScannerCallback _upnpScannerCallback;
    private long _downtime = -1;
    private char _lastCongestionCap = 0;

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";
    public final static String PROP_CONFIG_FILE = "router.configLocation";

    /** let clocks be off by 1 minute */
    public final static long CLOCK_FUDGE_FACTOR = 60*1000;

    /** used to differentiate routerInfo files on different networks */
    private static final int DEFAULT_NETWORK_ID = 2;
    private static final String PROP_NETWORK_ID = "router.networkID";
    private final int _networkID;

    /** coalesce stats this often - should be a little less than one minute, so the graphs get updated */
    public static final int COALESCE_TIME = 50*1000;

    /** this puts an 'H' in your routerInfo **/
    public final static String PROP_HIDDEN = "router.hiddenMode";
    /** this does not put an 'H' in your routerInfo **/
    public final static String PROP_HIDDEN_HIDDEN = "router.isHidden";
    /** New router keys at every restart. Disabled. */
    public final static String PROP_DYNAMIC_KEYS = "router.dynamicKeys";
    /**
     *  New router keys once only.
     *  @since 0.9.34
     */
    public final static String PROP_REBUILD_KEYS = "router.rebuildKeys";
    /** deprecated, use gracefulShutdownInProgress() */
    private final static String PROP_SHUTDOWN_IN_PROGRESS = "__shutdownInProgress";
    public static final String PROP_IB_RANDOM_KEY = TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + TunnelPoolSettings.PROP_RANDOM_KEY;
    public static final String PROP_OB_RANDOM_KEY = TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + TunnelPoolSettings.PROP_RANDOM_KEY;
    private static final String EVENTLOG = "eventlog.txt";
    private static final String PROP_JBIGI = "jbigi.loadedResource";
    private static final String PROP_JBIGI_PROCESSOR = "jbigi.lastProcessor";
    public static final String UPDATE_FILE = "i2pupdate.zip";
    private static final boolean CONGESTION_CAPS = true;
    private static final int SHUTDOWN_WAIT_SECS = 60;
    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final String PROP_RELAX_CONGESTION_CAP = "router.relaxCongestionCap";
    public boolean isAdvanced() {return getContext().getBooleanProperty(PROP_ADVANCED);}
    private static final String originalTimeZoneID;
    static {
        //
        // If embedding I2P you may wish to disable one or more of the following
        // via the associated System property. Since 0.9.19.
        //
        if (System.getProperty("I2P_DISABLE_DNS_CACHE_OVERRIDE") == null) {
            // grumble about sun's java caching DNS entries *forever* by default
            // so let's just keep 'em for a short time
            String DNS_CACHE_TIME = Integer.toString(15*60*1000);
            String DNS_NEG_CACHE_TIME = Integer.toString(5*60*1000);
            System.setProperty("sun.net.inetaddr.ttl", DNS_CACHE_TIME);
            System.setProperty("sun.net.inetaddr.negative.ttl", DNS_NEG_CACHE_TIME);
            System.setProperty("networkaddress.cache.ttl", DNS_CACHE_TIME);
            System.setProperty("networkaddress.cache.negative.ttl", DNS_NEG_CACHE_TIME);
        }

        if (System.getProperty("I2P_DISABLE_HTTP_KEEPALIVE_OVERRIDE") == null) {
            // (no need for keepalive)
            // Note that doc link above is wrong, it IS capital A Alive
            System.setProperty("http.keepAlive", "false");
        }
        // Save it for LogManager
        originalTimeZoneID = TimeZone.getDefault().getID();
        if (System.getProperty("I2P_DISABLE_TIMEZONE_OVERRIDE") == null) {
            System.setProperty("user.timezone", "GMT");
            // just in case, let's make it explicit...
            TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
        }
        // Note: I2P_DISABLE_OUTPUT_OVERRIDE implemented in startup/WorkingDir.java
    }

    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     *
     *  Config file name is "router.config" unless router.configLocation set in system properties.
     *
     *  See two-arg constructor for more information.
     *
     *  @throws IllegalStateException since 0.9.19 if another router with this config is running
     */
    public Router() {this(null, null);}

    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     *
     *  Config file name is "router.config" unless router.configLocation set in envProps or system properties.
     *
     *  See two-arg constructor for more information.
     *
     *  @param envProps may be null
     *  @throws IllegalStateException since 0.9.19 if another router with this config is running
     */
    public Router(Properties envProps) {this(null, envProps);}

    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     *
     *  See two-arg constructor for more information.
     *
     *  @param configFilename may be null
     *  @throws IllegalStateException since 0.9.19 if another router with this config is running
     */
    public Router(String configFilename) {this(configFilename, null);}

    /**
     *  Instantiation only. Starts no threads. Does not install updates.
     *  RouterContext is created but not initialized.
     *  You must call runRouter() after any constructor to start things up.
     *
     *  If configFilename is non-null, configuration is read in from there.
     *  Else if envProps is non-null, configuration is read in from the
     *  location given in the router.configLocation property.
     *  Else it's read in from the System property router.configLocation.
     *  Else from the file "router.config".
     *
     *  The most important properties are i2p.dir.base (the install directory, may be read-only)
     *  and i2p.dir.config (the user's configuration/data directory).
     *
     *  i2p.dir.base defaults to user.dir (CWD) but should almost always be set.
     *
     *  i2p.dir.config default depends on OS, user name (to detect if running as a service or not),
     *  and auto-detection of whether there appears to be previous data files in the base dir.
     *  See WorkingDir for details.
     *  If the config dir does not exist, it will be created, and files migrated from the base dir,
     *  in this constructor.
     *  If files in an existing config dir indicate that another router is already running
     *  with this directory, the constructor will delay for several seconds to be sure,
     *  and then throw an IllegalStateException.
     *
     *  @param configFilename may be null
     *  @param envProps may be null
     *  @throws IllegalStateException since 0.9.19 if another router with this config is running
     */
    public Router(String configFilename, Properties envProps) {
        _killVMOnEnd = true;
        _gracefulExitCode = -1;
        _config = new ConcurrentHashMap<String, String>();

        if (configFilename == null) {
            if (envProps != null) {_configFilename = envProps.getProperty(PROP_CONFIG_FILE);}
            if (_configFilename == null) {_configFilename = System.getProperty(PROP_CONFIG_FILE, "router.config");}
        } else {_configFilename = configFilename;}

        /**
         * We need the user directory figured out by now, so figure it out here rather than
         * in the RouterContext() constructor.
         *
         * We have not read the config file yet. Therefore the base and config locations
         * are determined solely by properties (first envProps then System), for the purposes
         * of initializing the user's config directory if it did not exist.
         *
         * If the base dir and/or config dir are set in the config file, they wil be used after
         * the initialization of the (possibly different) dirs determined by WorkingDir.
         * So for now, it doesn't make much sense to set the base or config dirs in the config file -
         * use properties instead. If for some reason, distros need this, we can revisit it.
         *
         * Then add it to envProps (but not _config, we don't want it in the router.config file)
         * where it will then be available to all via _context.dir()
         *
         * This call also migrates all files to the new working directory, including router.config
         */

        // Do we copy all the data files to the new directory? default false
        String migrate = System.getProperty("i2p.dir.migrate");
        boolean migrateFiles = Boolean.parseBoolean(migrate);
        String isPortableStr = System.getProperty("i2p.dir.portableMode");
        boolean isPortable = Boolean.parseBoolean(isPortableStr);
        String userDir = (!isPortable) ? WorkingDir.getWorkingDir(envProps, migrateFiles) : PortableWorkingDir.getWorkingDir(envProps);
        /**
         * Use the router.config file specified in the router.configLocation property (default "router.config")
         * if it is an abolute path, otherwise look in the userDir returned by getWorkingDir replace relative
         * path with absolute
         */
        File cf = new File(_configFilename);
        if (!cf.isAbsolute()) {
            cf = new File(userDir, _configFilename);
            _configFilename = cf.getAbsolutePath();
        }

        readConfig();

        if (envProps == null) {envProps = new Properties();}
        envProps.putAll(_config);

        if (envProps.getProperty("i2p.dir.config") == null) {envProps.setProperty("i2p.dir.config", userDir);}
        // Save this in the context for the logger and apps that need it
        envProps.setProperty("i2p.systemTimeZone", originalTimeZoneID);

        // Make darn sure we don't have a leftover I2PAppContext in the same JVM
        // e.g. on Android - see finalShutdown() also
        List<RouterContext> contexts = RouterContext.getContexts();
        if (contexts.isEmpty()) {RouterContext.killGlobalContext();}
        else if (SystemVersion.isAndroid()) {
            System.err.println("Warning: Killing " + contexts.size() + " other routers in this JVM");
            contexts.clear();
            RouterContext.killGlobalContext();
        } else {System.err.println("Warning: " + contexts.size() + " other routers in this JVM");}

        // The important thing that happens here is the directory paths are set and created
        // i2p.dir.router defaults to i2p.dir.config
        // i2p.dir.app defaults to i2p.dir.router
        // i2p.dir.log defaults to i2p.dir.router
        // i2p.dir.pid defaults to i2p.dir.router
        // i2p.dir.base defaults to user.dir == $CWD
        _context = new RouterContext(this, envProps, false);
        RouterContext.setGlobalContext(_context);
        _eventLog = new EventLog(_context, new File(_context.getRouterDir(), EVENTLOG));

        // This is here so that we can get the directory location from the context for the ping file
        // Check for other router but do not start a thread yet so the update doesn't cause a NCDFE
        if (!SystemVersion.isAndroid()) {
            for (int i = 0; i < 14; i++) {
                // Wrapper can start us up too quickly after a crash, the ping file
                // may still be less than LIVELINESS_DELAY (60s) old.
                // So wait at least 60s to be sure.
                if (isOnlyRouterRunning()) {
                    if (i > 0) {System.err.println("INFO: No other router running, proceeding with startup...");}
                    break;
                }
                if (i < 13) {
                    if (i == 0) {System.err.println("WARN: There may be another router already running... waiting a while to be sure...");}
                    // yes this is ugly to sleep in the constructor.
                    try {Thread.sleep(5000);}
                    catch (InterruptedException ie) {}
                } else {
                    _eventLog.addEvent(EventLog.ABORTED, "Another router running");
                    System.err.println("ERROR: There appears to be another router already running!");
                    System.err.println("       Make sure old instances are shut down before starting up a new one.");
                    System.err.println("       If no other instance is running, delete: " + getPingFile().getAbsolutePath());
                    //System.exit(-1);
                    // throw exception instead, for embedded
                    throw new IllegalStateException(
                                       "ERROR: There appears to be another router already running!" +
                                       " Make sure old instances are shut down before starting up a new one." +
                                       " If no other instance is running, delete: " + getPingFile().getAbsolutePath());
                }
            }
        }

        if (_config.get("router.firstVersion") == null) {
            // These may be useful someday. First added in 0.8.2
            _config.put("router.firstVersion", RouterVersion.VERSION);
            String now = Long.toString(System.currentTimeMillis());
            _config.put("router.firstInstalled", now);
            _config.put("router.updateLastInstalled", now);
            // First added in 0.8.13
            _config.put("router.previousVersion", RouterVersion.VERSION);
            saveConfig();
        }
        int id = DEFAULT_NETWORK_ID;
        String sid = _config.get(PROP_NETWORK_ID);
        if (sid != null) {
            try {
                id = Integer.parseInt(sid);
                if (id < 2 || id > 254) {throw new IllegalArgumentException("Invalid " + PROP_NETWORK_ID);}
            } catch (NumberFormatException nfe) {throw new IllegalArgumentException("Invalid " + PROP_NETWORK_ID);}
        }
        _networkID = id;
        // for testing
        setUPnPScannerCallback(new LoggerCallback());
        changeState(State.INITIALIZED);
        // *********  Start no threads before here ********* //
    }

    /**
     *  Initializes the RouterContext.
     *  Starts some threads. Does not install updates.
     *  All this was in the constructor.
     *
     *  Could block for 10 seconds or forever if waiting for entropy
     *
     *  @since 0.8.12
     */
    private void startupStuff() {
        // *********  Start no threads before here ********* //
        _log = _context.logManager().getLog(Router.class);

        //
        // NOW we can start the ping file thread.
        if (!SystemVersion.isAndroid()) {beginMarkingLiveliness();}

        // Apps may use this as an easy way to determine if they are in the router JVM
        // But context.isRouterContext() is even easier...
        // Both of these as of 0.7.9
        // As of 0.9.34, this is FULL_VERSION, not VERSION, which was the same as CoreVersion.VERSION
        // and thus not particularly useful.
        System.setProperty("router.version", RouterVersion.FULL_VERSION);

        // crypto init may block for 10 seconds waiting for entropy
        // we want to do this before context.initAll()
        // which will fire up several things that could block on the PRNG init
        warmupCrypto();

        // NOW we start all the activity
        _context.initAll();

        // Set wrapper.log permissions.
        // Just hope this is the right location, we don't know for sure,
        // but this is the same method used in LogsHelper and we have no complaints.
        // (we could look for the wrapper.config file and parse it I guess...)
        // If we don't have a wrapper, RouterLaunch does this for us.
        if (_context.hasWrapper()) {
            String tmpDir = System.getProperty("java.io.tmpdir");
            File f = new File(tmpDir, "wrapper.log");
            if (!f.exists()) {f = new File(_context.getBaseDir(), "wrapper.log");}
            SecureFileOutputStream.setPerms(f);
        }

        CryptoChecker.warnUnavailableCrypto(_context);

        _routerInfo = null;
        if (_log.shouldInfo()) {_log.info("New router created with config file " + _configFilename);}
        _oomListener = new OOMListener(_context);

        _shutdownHook = new ShutdownHook(_context);
        _gracefulShutdownDetector = new I2PAppThread(new GracefulShutdown(_context), "Graceful ShutdownHook", true);
        _gracefulShutdownDetector.setPriority(I2PThread.NORM_PRIORITY + 1);
        _gracefulShutdownDetector.start();

        _watchdog = new RouterWatchdog(_context);
        _watchdogThread = new I2PAppThread(_watchdog, "RouterWatchdog", true);
        _watchdogThread.setPriority(I2PThread.NORM_PRIORITY + 1);
        _watchdogThread.start();

        if (SystemVersion.isWindows()) {BasePerms.fix(_context);}
    }

    /**
     *  Not for external use.
     *  @since 0.8.8
     */
    public static final void clearCaches() {
        ByteCache.clearAll();
        SimpleByteCache.clearAll();
        Destination.clearCache();
        Translate.clearCache();
        Hash.clearCache();
        PublicKey.clearCache();
        SigningPublicKey.clearCache();
        SigUtil.clearCaches();
        I2PSessionImpl.clearCache();
        ReusableGZIPInputStream.clearCache();
        ReusableGZIPOutputStream.clearCache();
        // Reduce UDP packet cache under memory pressure (0.9.68+)
        UDPTransport.reduceCacheSize();
    }

    /**
     * Configure the router to kill the JVM when the router shuts down, as well
     * as whether to explicitly halt the JVM during the hard fail process.
     *
     * Defaults to true. Set to false for embedded before calling runRouter()
     */
    /**
     * Configure the router to kill the JVM when the router shuts down, as well
     * as whether to explicitly halt the JVM during the hard fail process.
     *
     * Defaults to true. Set to false for embedded before calling runRouter()
     *
     * @param shouldDie true to kill VM on shutdown, false for embedded use
     */
    public void setKillVMOnEnd(boolean shouldDie) {_killVMOnEnd = shouldDie;}

    /**
     * Check whether the router is configured to kill the JVM on shutdown.
     *
     * @return true if VM will be killed on router shutdown
     */
    public boolean getKillVMOnEnd() {return _killVMOnEnd;}

    /** @return absolute path */
    /**
     * Get the absolute path to the router configuration file.
     *
     * @return absolute path to the configuration file
     */
    public String getConfigFilename() {return _configFilename;}

    /**
     * Retrieve a configuration setting by name.
     *
     * @param name the configuration property name
     * @return the configuration value, or null if not set
     */
    public String getConfigSetting(String name) {return _config.get(name);}

    /**
     *  Warning, race between here and saveConfig(),
     *  saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove) is recommended.
     *
     *  @since 0.8.13
     *  @deprecated use saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove)
     */
    @Deprecated
    public void setConfigSetting(String name, String value) {_config.put(name, value);}

    /**
     *  Warning, race between here and saveConfig(),
     *  saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove) is recommended.
     *
     *  @since 0.8.13
     *  @deprecated use saveConfig(String name, String value) or saveConfig(Map toAdd, Set toRemove)
     */
    @Deprecated
    public void removeConfigSetting(String name) {
        _config.remove(name);
        _context.removeProperty(name); // remove the backing default also
    }

    /**
     *  @return unmodifiable Set, unsorted
     */
    /**
     * Get an unmodifiable set of all configuration property names.
     *
     * @return unmodifiable Set of configuration property names (unsorted)
     */
    public Set<String> getConfigSettings() {return Collections.unmodifiableSet(_config.keySet());}

    /**
     *  @return unmodifiable Map, unsorted
     */
    /**
     * Get an unmodifiable map of all configuration settings.
     *
     * @return unmodifiable Map of configuration property names to values (unsorted)
     */
    public Map<String, String> getConfigMap() {return Collections.unmodifiableMap(_config);}

    /**
     *  Our current router info.
     *  Warning, may be null if called very early.
     *
     *  Warning - risk of deadlock - do not call while holding locks
     *
     *  Note: Due to lock contention, especially during a
     *  rebuild of the router info, this may take a long time.
     *  For determining the current status of the router, use
     *  RouterContext.commSystem().getStatus().
     */
    public RouterInfo getRouterInfo() {
        _routerInfoLock.readLock().lock();
        try {return _routerInfo;}
        finally {_routerInfoLock.readLock().unlock();}
    }

    /**
     *  Our current router identity.
     *  Warning, may be null if called very early.
     *  Lockless.
     *  @since 0.9.67
     */
    public RouterIdentity getRouterIdentity() {return _routerIdent;}

    /**
     *  Our current router hash.
     *  Warning, may be null if called very early.
     *  Lockless.
     *  @since 0.9.67
     */
    public Hash getRouterHash() {return _routerHash;}

    /**
     *  Caller must ensure info is valid - no validation done here.
     *  Not for external use.
     *
     *  Warning - risk of deadlock - do not call while holding locks
     *
     */
    public void setRouterInfo(RouterInfo info) {
        Log log;
        _routerInfoLock.writeLock().lock();
        try {
            if (!info.getIdentity().equals(_routerIdent)) {
                log = _log;
                if (_routerIdent != null) {log.log(Log.CRIT, "Changing router ident while running");} // shouldn't happen
                _routerIdent = info.getIdentity();
                _routerHash = _routerIdent.calculateHash();
            }
            _routerInfo = info;
        } finally {_routerInfoLock.writeLock().unlock();}
        log = _log;
        if (log != null && log.shouldInfo()) {log.info("setRouterInfo() : " + info);}
        if (info != null) {_context.jobQueue().addJob(new PersistRouterInfoJob(_context));}
    }

    /**
     *  Used only by routerconsole.. to be deprecated?
     *  @return System time, NOT context time
     */
    public long getWhenStarted() {return _started;}

    /**
     * Wall clock uptime.
     * This uses System time, NOT context time, so context clock shifts will
     * not affect it. This is important if NTP fails and the
     * clock then shifts from a SSU peer source just after startup.
     */
    public long getUptime() {
        if (_started <= 0) {return 1000;} // racing on startup
        return Math.max(1000, System.currentTimeMillis() - _started);
    }

    /**
     *  The network ID. Default 2.
     *  May be changed with the config property router.networkID (restart required).
     *  Change only if running a test network to prevent cross-network contamination.
     *
     *  @return 2 - 254
     *  @since 0.9.25
     */
    public int getNetworkID() {return _networkID;}

    /**
     *  Non-null, but take care when accessing context items before runRouter() is called
     *  as the context will not be initialized.
     *
     *  @return non-null
     */
    public RouterContext getContext() {return _context;}

    private class LoggerCallback implements UPnPScannerCallback {
        public void beforeScan() {_log.info("SSDP beforeScan()");}
        public void afterScan() {_log.info("SSDP afterScan()");}
    }

    /**
     *  For Android only.
     *  MUST be set before runRouter() is called.
     *
     *  @param callback the callback or null to clear it
     *  @since 0.9.41
     */
    public synchronized void setUPnPScannerCallback(UPnPScannerCallback callback) {
        _upnpScannerCallback = callback;
    }

    /**
     *  For Android only.
     *
     *  @return the callback or null if none
     *  @since 0.9.41
     */
    public synchronized UPnPScannerCallback getUPnPScannerCallback() {
        return _upnpScannerCallback;
    }

    /**
     *  This must be called after instantiation.
     *  Starts the threads. Does not install updates.
     *  This is for embedded use.
     *  Standard standalone installation uses main() instead, which
     *  checks for updates and then calls this.
     *
     *  This may take quite a while, especially if NTP fails
     *  or the system lacks entropy
     *
     *  @since public as of 0.9 for Android and other embedded uses
     *  @throws IllegalStateException if called more than once
     */
    public synchronized void runRouter() {
        synchronized(_stateLock) {
            if (_state != State.INITIALIZED) {throw new IllegalStateException();}
            changeState(State.STARTING_1);
        }
        String last = _config.get("router.previousFullVersion");
        if (last != null) {
            _eventLog.addEvent(EventLog.UPDATED, last + " ➜ " + RouterVersion.FULL_VERSION);
            saveConfig("router.previousFullVersion", null);
        }
        _eventLog.addEvent(EventLog.STARTED, RouterVersion.FULL_VERSION);
        startupStuff();
        changeState(State.STARTING_2);
        _started = System.currentTimeMillis();
        try {Runtime.getRuntime().addShutdownHook(_shutdownHook);}
        catch (IllegalStateException ise) {}
        if (!SystemVersion.isAndroid()) {I2PThread.addOOMEventListener(_oomListener);}

        // message handlers
        _context.inNetMessagePool().registerHandlerJobBuilder(GarlicMessage.MESSAGE_TYPE, new GarlicMessageHandler(_context));

        if (_context.getBooleanProperty(PROP_REBUILD_KEYS)) {killKeys();}

        _context.messageValidator().startup();
        _context.tunnelDispatcher().startup();
        _context.inNetMessagePool().startup();
        _context.jobQueue().runQueue(1);
        //_context.jobQueue().addJob(new CoalesceStatsJob(_context));
        _context.simpleTimer2().addPeriodicEvent(new CoalesceStatsEvent(_context), COALESCE_TIME);
        _context.jobQueue().addJob(new UpdateRoutingKeyModifierJob(_context));
        //_context.adminManager().startup();
        _context.blocklist().startup();

        synchronized(_configFileLock) {
            // persistent key for peer ordering since 0.9.17
            // These will be replaced in CreateRouterInfoJob if we rekey
            if (!_config.containsKey(PROP_IB_RANDOM_KEY) ||
                getEstimatedDowntime() > 12*60*60*1000L) {
                byte rk[] = new byte[32];
                _context.random().nextBytes(rk);
                _config.put(PROP_IB_RANDOM_KEY, Base64.encode(rk));
                _context.random().nextBytes(rk);
                _config.put(PROP_OB_RANDOM_KEY, Base64.encode(rk));
                saveConfig();
            }
        }

        // let the timestamper get us sync'ed
        // this will block for quite a while on a disconnected machine
        long before = System.currentTimeMillis();
        _context.clock().getTimestamper().waitForInitialization();
        long waited = System.currentTimeMillis() - before;
        if (_log.shouldInfo()) {_log.info("Waited " + waited + "ms to initialize");}
        changeState(State.STARTING_3);
        _context.jobQueue().addJob(new StartupJob(_context));
    }

    /**
     * This updates the config with all settings found in the file.
     * It does not clear the config first, so settings not found in
     * the file will remain in the config.
     *
     * This is synchronized with saveConfig().
     * Not for external use.
     */
    @SuppressWarnings({"unchecked", "rawtypes" })
    public void readConfig() {
        synchronized(_configFileLock) {
            String f = getConfigFilename();
            Properties config = getConfig(_context, f);
            Map foo = _config; // to avoid compiler errror
            foo.putAll(config);
        }
    }

    /**
     *  this does not use ctx.getConfigDir(), must provide a full path in filename
     *  Caller must synchronize
     *
     *  @param ctx will be null at startup when called from constructor
     */
    private static Properties getConfig(RouterContext ctx, String filename) {
        Log log = null;
        if (ctx != null) {
            log = ctx.logManager().getLog(Router.class);
            if (log.shouldDebug()) {log.debug("Config file: " + filename, new Exception("location"));}
        }
        Properties props = new Properties();
        try {
            File f = new File(filename);
            if (f.canRead()) {
                DataHelper.loadProps(props, f);
                props.remove(PROP_SHUTDOWN_IN_PROGRESS);
            } else {
                // normal not to exist at first install
                if (log != null) {log.warn("Configuration file " + filename + " does not exist");}
                else {System.err.println("Configuration file " + filename + " does not exist");}
            }
        } catch (IOException ioe) {
            if (log != null) {log.error("Error loading the router configuration from " + filename, ioe);}
            else {System.err.println("Error loading the router configuration from " + filename + ": " + ioe);}
        }
        return props;
    }

    ////////// begin state management

    /**
     *  Startup / shutdown states
     *
     *  @since 0.9.18
     */
    private enum State {
        UNINITIALIZED,
        /** constructor complete */
        INITIALIZED,
        /** runRouter() called */
        STARTING_1,
        /** startupStuff() complete, most of the time here is NTP */
        STARTING_2,
        /** NTP done, Job queue started, StartupJob queued, runRouter() returned */
        STARTING_3,
        /** RIs loaded. From STARTING_3 */
        NETDB_READY,
        /** Non-zero-hop expl. tunnels built. From STARTING_3 */
        EXPL_TUNNELS_READY,
        /** from NETDB_READY or EXPL_TUNNELS_READY */
        RUNNING,
        /**
         *  A "soft" restart, primarily of the comm system, after
         *  a port change or large step-change in system time.
         *  Does not stop the whole JVM, so it is safe even in the absence
         *  of the wrapper.
         *  This is not a graceful restart - all peer connections are dropped immediately.
         */
        RESTARTING,
        /** cancellable shutdown has begun */
        GRACEFUL_SHUTDOWN,
        /** In shutdown(). Non-cancellable shutdown has begun */
        FINAL_SHUTDOWN_1,
        /** In shutdown2(). Killing everything */
        FINAL_SHUTDOWN_2,
        /** In finalShutdown(). Final cleanup */
        FINAL_SHUTDOWN_3,
        /** all done */
        STOPPED
    }

    /**
     *  For efficiency. EnumSets are bitmasks.
     *  @since 0.9.34
     */
    private static final Set<State> STATES_ALIVE =
        EnumSet.of(State.RUNNING, State.GRACEFUL_SHUTDOWN, State.STARTING_1, State.STARTING_2,
                   State.STARTING_3, State.NETDB_READY, State.EXPL_TUNNELS_READY);

    private static final Set<State> STATES_GRACEFUL =
        EnumSet.of(State.GRACEFUL_SHUTDOWN, State.FINAL_SHUTDOWN_1, State.FINAL_SHUTDOWN_2,
                   State.FINAL_SHUTDOWN_3, State.STOPPED);

    private static final Set<State> STATES_FINAL =
        EnumSet.of(State.FINAL_SHUTDOWN_1, State.FINAL_SHUTDOWN_2, State.FINAL_SHUTDOWN_3, State.STOPPED);

    /**
     *  @since 0.9.18
     */
    private void changeState(State state) {
        State oldState;
        Log log;
        synchronized(_stateLock) {
            oldState = _state;
            _state = state;
            log = _log;
        }
        if (log != null && oldState != state && state != State.STOPPED && log.shouldWarn()) {
            log.warn("Router state change from " + oldState + " to " + state /* , new Exception() */ );
            //for debugging
            _context.logManager().flush();
        }
    }

    /**
     * Check if the router is alive (in startup or running state).
     * True during initial startup, false during soft restart.
     *
     * @return true if router is in an alive state
     */
    public boolean isAlive() {
        synchronized(_stateLock) {return STATES_ALIVE.contains(_state);}
    }

    /**
     * @return true if router is RUNNING, i.e NetDB and Expl. tunnels are ready.
     * @since 0.9.39
     */
    public boolean isRunning() {
        synchronized(_stateLock) {return _state == State.RUNNING;}
    }

    /**
     * @return true if router is RESTARTING (soft restart)
     * @since 0.9.40
     */
    public boolean isRestarting() {
        synchronized(_stateLock) {return _state == State.RESTARTING;}
    }

    /**
     *  Only for Restarter, after soft restart is complete.
     *  Not for external use.
     *  @since 0.8.12
     */
    public void setIsAlive() {changeState(State.RUNNING);}

    /**
     *  Only for NetDB, after RIs are loaded.
     *  Not for external use.
     *  @since 0.9.18
     */
    public void setNetDbReady() {
        boolean changed = false;
        synchronized(_stateLock) {
            if (_state == State.STARTING_3) {
                changeState(State.NETDB_READY);
                changed = true;
            } else if (_state == State.EXPL_TUNNELS_READY) {
                changeState(State.RUNNING);
                changed = true;
            }
        }
        if (changed && _context.netDb().isInitialized()) {
            if (_log.shouldWarn()) {_log.warn("NetDb ready -> Publishing our RouterInfo...");}

            // Any previous calls to netdb().publish() did not actually publish, because netdb init was not complete
            Republish r = new Republish(_context);

            // This is called from PersistentDataStore.ReadJob, so we probably don't need to throw it to the timer queue,
            // but just to be safe
            _context.simpleTimer2().addEvent(r, 0);

            // periodically update our RI and republish it to the flooodfills
            PublishLocalRouterInfoJob plrij = new PublishLocalRouterInfoJob(_context);
            plrij.getTiming().setStartAfter(_context.clock().now() + plrij.getDelay());
            _context.jobQueue().addJob(plrij);
        }
        if (changed) {
            _context.commSystem().initGeoIP();
            if (!SystemVersion.isSlow() && !_context.getBooleanProperty("i2np.allowLocal") &&
                _context.getProperty(Analysis.PROP_FREQUENCY, Analysis.DEFAULT_FREQUENCY) > 0) {
                // Registers and starts itself
                Analysis.getInstance(_context);
            }
        }
    }

    /**
     *  Only for Tunnel Building, after we have non-zero-hop expl. tunnels.
     *  Not for external use.
     *  @since 0.9.18
     */
    public void setExplTunnelsReady() {
        synchronized(_stateLock) {
            if (_state == State.STARTING_3) {changeState(State.EXPL_TUNNELS_READY);}
            else if (_state == State.NETDB_READY) {changeState(State.RUNNING);}
            else {_log.warn("Invalid state " + _state + " for setExplTunnelsReady()");}
        }
    }

    /**
     * Is a graceful shutdown in progress? This may be cancelled.
     * Note that this also returns true if an uncancellable final shutdown is in progress.
     */
    public boolean gracefulShutdownInProgress() {
        synchronized(_stateLock) {return STATES_GRACEFUL.contains(_state);}
    }

    /**
     * Is a final shutdown in progress? This may not be cancelled.
     * @since 0.8.12
     */
    public boolean isFinalShutdownInProgress() {
        synchronized(_stateLock) {return STATES_FINAL.contains(_state);}
    }

    ////////// end state management

    /**
     * Rebuild and republish our routerInfo since something significant
     * has changed.
     * This is a non-blocking rebuild.
     *
     * Not for external use.
     *
     * Warning - risk of deadlock - do not call while holding locks
     *
     */
    public void rebuildRouterInfo() {rebuildRouterInfo(false);}

    /**
     * Rebuild and republish our routerInfo since something significant
     * has changed.
     * Not for external use.
     *
     *  Warning - risk of deadlock - do not call while holding locks
     *
     * @param blockingRebuild ignored, always nonblocking
     */
    public void rebuildRouterInfo(boolean blockingRebuild) {
        if (_log.shouldInfo()) {_log.info("Building us a new RouterInfo, publish inline? " + blockingRebuild);}
        // deadlock thru createAddresses() thru SSU REA... moved outside lock
        List<RouterAddress> addresses = _context.commSystem().createAddresses();
        _routerInfoLock.writeLock().lock();
        try {locked_rebuildRouterInfo(addresses);}
        finally {_routerInfoLock.writeLock().unlock();}
    }

    /**
     * Rebuild and republish our routerInfo since something significant
     * has changed.
     *
     */
    private void locked_rebuildRouterInfo(List<RouterAddress> addresses) {
        RouterInfo ri;
        if (_routerInfo != null) {ri = new RouterInfo(_routerInfo);}
        else {ri = new RouterInfo();}

        try {
            ri.setPublished(_context.clock().now());
            Properties stats = _context.statPublisher().publishStatistics();
            ri.setOptions(stats);
            ri.setAddresses(addresses);
            SigningPrivateKey key = _context.keyManager().getSigningPrivateKey();

            if (key == null) {
                _log.log(Log.CRIT, "Internal Error -> Private signing key not known!");
                return;
            }
            ri.sign(key);
            setRouterInfo(ri);
            if (!ri.isValid()) {throw new DataFormatException("Our RouterInfo has a BAD signature");}
            Republish r = new Republish(_context);
            _context.simpleTimer2().addEvent(r, 0);
        } catch (DataFormatException dfe) {
            _log.log(Log.CRIT, "Internal Error -> Unable to sign our own address?!", dfe);
        }
    }

    /**
     *  Family Key Crypto Signer / Verifier.
     *  Not for external use.
     *  If family key is set, first call Will take a while to generate keys.
     *  Warning - risk of deadlock - do not call while holding locks
     *  (other than routerInfoLock)
     *
     *  @return null on initialization failure
     *  @since 0.9.24
     */
    public FamilyKeyCrypto getFamilyKeyCrypto() {
        synchronized (_familyKeyLock) {
            if (_familyKeyCrypto == null) {
                if (!_familyKeyCryptoFail) {
                    try {_familyKeyCrypto = new FamilyKeyCrypto(_context);}
                    catch (Exception e) {
                        // Could be IllegalArgumentException from key problems
                        _log.error("Failed to initialize family key crypto", e);
                        _familyKeyCryptoFail = true;
                    }
                }
            }
        }
        return _familyKeyCrypto;
    }

    // publicize our ballpark capacity
    public static final char CAPABILITY_BW12 = 'K';
    public static final char CAPABILITY_BW32 = 'L';
    public static final char CAPABILITY_BW64 = 'M';
    public static final char CAPABILITY_BW128 = 'N';
    public static final char CAPABILITY_BW256 = 'O';
    /** @since 0.9.18 */
    public static final char CAPABILITY_BW512 = 'P';
    /** @since 0.9.18 */
    public static final char CAPABILITY_BW_UNLIMITED = 'X';
    /** for testing */
    public static final String PROP_FORCE_BWCLASS = "router.forceBandwidthClass";

    public static final char CAPABILITY_REACHABLE = 'R';
    public static final char CAPABILITY_UNREACHABLE = 'U';

    /** @since 0.9.58, proposal 162 */
    public static final char CAPABILITY_CONGESTION_MODERATE = 'D';
    /** @since 0.9.58, proposal 162 */
    public static final char CAPABILITY_CONGESTION_SEVERE = 'E';
    /** @since 0.9.58, proposal 162 */
    public static final char CAPABILITY_NO_TUNNELS = 'G';

    /** for testing */
    public static final String PROP_FORCE_UNREACHABLE = "router.forceUnreachable";

    /** @deprecated unused */
    @Deprecated
    public static final char CAPABILITY_NEW_TUNNEL = 'T';

    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_K = 0;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_L = 12;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_M = 48;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_N = 64;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_O = 128;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_P = 256;
    /** In binary (1024) Kbytes
     *  @since 0.9.33 */
    public static final int MIN_BW_X = 2000;

    /**
     *  The current bandwidth class.
     *  For building our RI. Not for external use.
     *
     *  @return a character to be added to the RI, one of "KLMNOPX"
     *  @since 0.9.31
     */
    public char getBandwidthClass() {
        int bwLim = Math.min(_context.bandwidthLimiter().getInboundKBytesPerSecond(),
                             _context.bandwidthLimiter().getOutboundKBytesPerSecond());
        bwLim = (int)(bwLim * getSharePercentage());
        String force = _context.getProperty(PROP_FORCE_BWCLASS);

        if (force != null && force.length() > 0) {return force.charAt(0);}
        else if (bwLim < MIN_BW_L) {return CAPABILITY_BW12;}
        else if (bwLim <= MIN_BW_M) {return CAPABILITY_BW32;}
        else if (bwLim <= MIN_BW_N) {return CAPABILITY_BW64;}
        else if (bwLim <= MIN_BW_O) {return CAPABILITY_BW128;}
        else if (bwLim <= MIN_BW_P) {return CAPABILITY_BW256;}
        else if (bwLim <= MIN_BW_X) {return CAPABILITY_BW512;} // 512 supported as of 0.9.18; TODO adjust threshold
        else {return CAPABILITY_BW_UNLIMITED;} // Unlimited supported as of 0.9.18;
    }

    /**
     *  For building our RI. Not for external use.
     *
     *  @return a capabilities string to be added to the RI
     */
    public String getCapabilities() {
        StringBuilder rv = new StringBuilder(4);
        boolean hidden = isHidden();
        char bw = hidden ? CAPABILITY_BW32 : getBandwidthClass();
        rv.append(bw);

        // 512 and unlimited supported as of 0.9.18;
        // if prop set to true, don't tell people we are ff even if we are - which means we likely won't get used at all
        if (_context.netDb().floodfillEnabled() && !_context.getBooleanProperty("router.hideFloodfillParticipant")) {
            rv.append(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL);
        }

        if (_context.getBooleanProperty(PROP_HIDDEN)) {rv.append(RouterInfo.CAPABILITY_HIDDEN);}

        if (hidden || _context.getBooleanProperty(PROP_FORCE_UNREACHABLE)) {
            rv.append(CAPABILITY_UNREACHABLE);
            //if (CONGESTION_CAPS) {rv.append(CAPABILITY_NO_TUNNELS);}
            return rv.toString();
        }
        boolean forceG = false;
        switch (_context.commSystem().getStatus()) {
            case OK:
            case IPV4_OK_IPV6_UNKNOWN:
            case IPV4_OK_IPV6_FIREWALLED:
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_DISABLED_IPV6_OK:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_SNAT_IPV6_OK:
                rv.append(CAPABILITY_REACHABLE);
                break;

            case DIFFERENT:
            case HOSED:
            case IPV4_SNAT_IPV6_UNKNOWN:
                forceG = true;
                // fall through

            case REJECT_UNSOLICITED:
            case IPV4_DISABLED_IPV6_FIREWALLED:
                rv.append(CAPABILITY_UNREACHABLE);
                break;

            case DISCONNECTED:
            case UNKNOWN:
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
            case IPV4_DISABLED_IPV6_UNKNOWN:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
            default:
                // no explicit capability
                break;
        }

        char cong = 0;
        int maxTunnels = _context.getProperty(RouterThrottleImpl.PROP_MAX_TUNNELS, RouterThrottleImpl.DEFAULT_MAX_TUNNELS);
        boolean disableCongestionCaps = isAdvanced() && _context.getBooleanProperty(PROP_RELAX_CONGESTION_CAP);
        boolean capsEnabled = false;
        if (forceG || maxTunnels <= 0) {cong = CAPABILITY_NO_TUNNELS;}
        else if (maxTunnels <= 1000 || SystemVersion.isSlow()) {cong = CAPABILITY_CONGESTION_MODERATE;}
        else {
            int numTunnels = _context.tunnelManager().getParticipatingCount();
            if (numTunnels > 9 * maxTunnels / 10) {cong = CAPABILITY_CONGESTION_SEVERE;}
            else if (numTunnels > 8 * maxTunnels / 10) {cong = CAPABILITY_CONGESTION_MODERATE;}
            else {
                // this is a greatly simplified version of RouterThrottleImpl.acceptTunnelRequest()
                long maxLag = _context.jobQueue().getMaxLag();
                RateStat jobLag = _context.statManager().getRate("jobQueue.jobLag");
                long avgLag = 0;
                if (jobLag != null) {
                    Rate lagRate = jobLag.getRate(RateConstants.ONE_MINUTE);
                    avgLag = (long) lagRate.getAverageValue();
                }
                if (maxLag > 1000 && avgLag > 200 && getUptime() > 10*60*1000) {
                    if (maxLag > 1500 && avgLag > 300) {cong = CAPABILITY_CONGESTION_SEVERE;}
                    else {cong = CAPABILITY_CONGESTION_MODERATE;}
                } else {
                    double bwLim = getSharePercentage() * 1024 *
                                   Math.min(_context.bandwidthLimiter().getInboundKBytesPerSecond(),
                                            _context.bandwidthLimiter().getOutboundKBytesPerSecond());
                    if (bwLim < 4*1024) {cong = CAPABILITY_NO_TUNNELS;}
                    else {
                        RateStat rs = _context.statManager().getRate("tunnel.participatingMessageCountAvgPerTunnel");
                        double messagesPerTunnel = 0;
                        if (rs != null) {
                            Rate r = rs.getRate(20*RateConstants.ONE_MINUTE);
                            if (r != null) {
                                RateAverages ra = RateAverages.getTemp();
                                messagesPerTunnel = r.computeAverages(ra, true).getAverage();
                            }
                        }
                        if (messagesPerTunnel < RouterThrottleImpl.DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE) {
                            messagesPerTunnel = RouterThrottleImpl.DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE;
                        }
                        double bpsAllocated = messagesPerTunnel * numTunnels * 1024 / (10 * 60);
                        if (_log.shouldInfo()) {_log.info("Bytes/s allocated: " + bpsAllocated + " -> Bandwidth limit: " + bwLim);}
                        if (bpsAllocated > 0.9 * bwLim) {
                            cong = CAPABILITY_CONGESTION_SEVERE;
                            capsEnabled =true;
                        }
                        else if (bpsAllocated > 0.8 * bwLim) {
                            cong = CAPABILITY_CONGESTION_MODERATE;
                            capsEnabled =true;
                        }
                        else {cong = 0;}
                        if (capsEnabled && disableCongestionCaps) {
                            cong = 0;
                            if (_log.shouldWarn()) {
                                _log.warn("Not applying congestion caps due to bandwidth -> Disabled by config");
                            }
                        }
                    }
                }
            }
        }
        if (cong != 0 && CONGESTION_CAPS) {
            rv.append(cong);
            if (_log.shouldWarn() && cong != _lastCongestionCap) {_log.warn("Congestion cap \'" + cong + "\' activated");}
            _lastCongestionCap = cong;
        }
        return rv.toString();
    }

    /*
     *  This checks the config only. We don't check the current RI
     *  due to deadlocks.
     *
     */
    public boolean isHidden() {
        if (_context.getBooleanProperty(PROP_HIDDEN)) {return true;}
        String h = _context.getProperty(PROP_HIDDEN_HIDDEN);
        if (h != null) {return Boolean.parseBoolean(h);}
        return _context.commSystem().isInStrictCountry();
    }

    /**
     *  @since 0.9.3
     */
    public EventLog eventLog() {return _eventLog;}

    /**
     * Ugly list of files that we need to kill if we are building a new identity
     *
     */
    private static final String _rebuildFiles[] = new String[] {
        CreateRouterInfoJob.INFO_FILENAME,
        CreateRouterInfoJob.KEYS_FILENAME,
        CreateRouterInfoJob.KEYS2_FILENAME,
        "netDb/my.info",      // no longer used
        "connectionTag.keys", // never used?
        KeyManager.DEFAULT_KEYDIR + '/' + KeyManager.KEYFILE_PRIVATE_ENC,
        KeyManager.DEFAULT_KEYDIR + '/' + KeyManager.KEYFILE_PUBLIC_ENC,
        KeyManager.DEFAULT_KEYDIR + '/' + KeyManager.KEYFILE_PRIVATE_SIGNING,
        KeyManager.DEFAULT_KEYDIR + '/' + KeyManager.KEYFILE_PUBLIC_SIGNING,
        "sessionKeys.dat",     // no longer used
        "ssu2tokens.txt"
    };

    /**
     *  Not for external use.
     */
    public void killKeys() {
        //new Exception("Clearing identity files").printStackTrace();
        for (int i = 0; i < _rebuildFiles.length; i++) {
            File f = new File(_context.getRouterDir(),_rebuildFiles[i]);
            if (f.exists()) {
                boolean removed = f.delete();
                if (removed) {
                    System.out.println("INFO: Removing old identity file: " + _rebuildFiles[i]);
                } else {
                    System.out.println("ERROR: Could not remove old identity file: " + _rebuildFiles[i]);
                }
            }
        }

        // now that we have random ports, keeping the same port would be bad
        synchronized(_configFileLock) {
            removeConfigSetting(UDPTransport.PROP_INTERNAL_PORT);
            removeConfigSetting(UDPTransport.PROP_EXTERNAL_PORT);
            removeConfigSetting(UDPTransport.PROP_INTRO_KEY);
            removeConfigSetting(UDPTransport.PROP_SSU2_SP);
            removeConfigSetting(UDPTransport.PROP_SSU2_IKEY);
            removeConfigSetting(NTCPTransport.PROP_I2NP_NTCP_PORT);
            removeConfigSetting(NTCPTransport.PROP_NTCP2_SP);
            removeConfigSetting(NTCPTransport.PROP_NTCP2_IV);
            removeConfigSetting(PROP_IB_RANDOM_KEY);
            removeConfigSetting(PROP_OB_RANDOM_KEY);
            removeConfigSetting(PROP_REBUILD_KEYS);
            saveConfig();
        }
    }

    /**
     * Rebuild a new identity the hard way - delete all of our old identity
     * files, then reboot the router.
     *
     *  Calls exit(), never returns.
     *
     *  Not for external use.
     */
    public synchronized void rebuildNewIdentity() {
        if (_shutdownHook != null) {
            try {Runtime.getRuntime().removeShutdownHook(_shutdownHook);}
            catch (IllegalStateException ise) {}
        }
        killKeys();
        for (Runnable task : _context.getShutdownTasks()) {
            if (_log.shouldWarn()) {_log.warn("Running shutdown task " + task.getClass());}
            try {task.run();}
            catch (Throwable t) {_log.log(Log.CRIT, "Error running Shutdown Task", t);}
        }
        _context.removeShutdownTasks();
        // hard and ugly
        if (_context.hasWrapper()) {_log.log(Log.CRIT, "Restarting with new Router Identity");}
        else {_log.log(Log.CRIT, "Shutting down because old Router Identity was INVALID -> Restart I2P");}
        finalShutdown(EXIT_HARD_RESTART);
    }

    /**
     *  Could block for 10 seconds or forever
     */
    private void warmupCrypto() {
        String oldLoaded = _context.getProperty(PROP_JBIGI);
        String oldProcessor = _context.getProperty(PROP_JBIGI_PROCESSOR);
        String processor = null;
        if (SystemVersion.isX86()) {
            /**
             * Check to see if processor changed since last time we ran, or if we detected a different
             * processor model due to CPUID code changes, or if we changed 32/64 bit.
             * If so, delete the old jbigi.so file, to protect against a JVM crash.
             *
             * We do this before calling elGamalEngine(), which calls NBI.
             * We take care not to access the NBI class yet.
             */
            try {
                processor = CPUID.getInfo().getCPUModelString();
                if (SystemVersion.is64Bit()) {processor += "/64";}
                if (oldProcessor != null && !oldProcessor.equals(processor)) {
                    // delete old so file
                    boolean isWin = SystemVersion.isWindows();
                    boolean isMac = SystemVersion.isMac();
                    String osName = System.getProperty("os.name").toLowerCase(Locale.US);
                    // only do this on these OSes
                    boolean goodOS = isWin || isMac || osName.contains("linux") || osName.contains("freebsd");
                    File jbigiJar = new File(_context.getLibDir(), "jbigi.jar");
                    if (goodOS && jbigiJar.exists() && _context.getBaseDir().canWrite()) {
                        String libPrefix = isWin ? "" : "lib";
                        String libSuffix = isWin ? ".dll" : isMac ? ".jnilib" : ".so";
                        File jbigiLib = new File(_context.getBaseDir(), libPrefix + "jbigi" + libSuffix);
                        if (jbigiLib.canWrite()) {
                            String path = jbigiLib.getAbsolutePath();
                            boolean success = FileUtil.copy(path, path + ".bak", true, true);
                            if (success) {
                                success = jbigiLib.delete();
                                if (success) {
                                    System.out.println("Processor change detected, moved jbigi library to " +
                                                       path + ".bak");
                                    System.out.println("Check logs for successful installation of new library");
                                }
                            }
                        }
                    }
                }
            } catch (UnknownCPUException e) {}
        }
        _context.random().nextBoolean();
        // Instantiate to fire up the YK refiller thread
        _context.elGamalEngine();
        String loaded = NativeBigInteger.getLoadedResourceName();
        Map<String, String> changes = null;
        if (loaded != null && !loaded.equals(oldLoaded)) {
            changes = new HashMap<String, String>(2);
            changes.put(PROP_JBIGI, loaded);
        }
        if (processor != null && !processor.equals(oldProcessor)) {
            if (changes == null) {changes = new HashMap<String, String>(1);}
            changes.put(PROP_JBIGI_PROCESSOR, processor);
        }
        if (changes != null) {saveConfig(changes, null);}
    }

    /** shut down after all tunnels are gone */
    public static final int EXIT_GRACEFUL = 2;
    /** shut down immediately */
    public static final int EXIT_HARD = 3;
    /** shut down immediately */
    public static final int EXIT_OOM = 10;
    /** shut down immediately, and tell the wrapper to restart */
    public static final int EXIT_HARD_RESTART = 4;
    /** shut down after all tunnels are gone, and tell the wrapper to restart */
    public static final int EXIT_GRACEFUL_RESTART = 5;

    /**
     *  Shutdown with no chance of cancellation.
     *  Blocking, will call exit() and not return unless setKillVMOnExit(false) was previously called,
     *  or a final shutdown is already in progress.
     *  May take several seconds as it runs all the shutdown hooks.
     *
     *  @param exitCode one of the EXIT_* values, non-negative
     *  @throws IllegalArgumentException if exitCode negative
     */
    public synchronized void shutdown(int exitCode) {
        if (exitCode < 0) {throw new IllegalArgumentException();}
        synchronized(_stateLock) {
            if (_state == State.FINAL_SHUTDOWN_1 ||
                _state == State.FINAL_SHUTDOWN_2 ||
                _state == State.FINAL_SHUTDOWN_3 ||
                _state == State.STOPPED)
                return;
            changeState(State.FINAL_SHUTDOWN_1);
        }
        _context.throttle().setShutdownStatus();
        if (_shutdownHook != null) {
            try {Runtime.getRuntime().removeShutdownHook(_shutdownHook);}
            catch (IllegalStateException ise) {}
        }
        shutdown2(exitCode);
    }

    /**
     *  Cancel the JVM runtime hook before calling this.
     *  Called by the ShutdownHook.
     *  NOT to be called by others, use shutdown().
     *
     *  @param exitCode one of the EXIT_* values, non-negative
     *  @throws IllegalArgumentException if exitCode negative
     */
    public synchronized void shutdown2(int exitCode) {
        if (exitCode < 0) {throw new IllegalArgumentException();}
        changeState(State.FINAL_SHUTDOWN_2);
        // help us shut down esp. after OOM
        int priority = (exitCode == EXIT_OOM) ? Thread.MAX_PRIORITY - 1 : Thread.NORM_PRIORITY + 2;
        Thread.currentThread().setPriority(priority);
        String exitString = "";
        if (exitCode == 2) {exitString = "graceful shutdown";}
        else if (exitCode == 3) {exitString = "hard shutdown";}
        else if (exitCode == 4) {exitString = "hard restart";}
        else if (exitCode == 5) {exitString = "graceful restart";}
        else if (exitCode == 10) {exitString = "forced restart (Out of Memory error)";}
        if (exitCode >= 2) {_log.log(Log.CRIT, "Initiating " + exitString + "...");}
        // So we can get all the way to the end
        // No, you can't do Thread.currentThread.setDaemon(false)
        if (_killVMOnEnd) {
            try {(new Spinner()).start();}
            catch (Throwable t) {}
        }
        ((RouterClock) _context.clock()).removeShiftListener(this);
        _context.random().saveSeed();
        I2PThread.removeOOMEventListener(_oomListener);
        // Run the shutdown hooks first in case they want to send some goodbye messages
        // Maybe we need a delay after this too?
        LinkedList<Thread> tasks = new LinkedList<Thread>();
        for (Runnable task : _context.getShutdownTasks()) {
            //System.err.println("Running shutdown task " + task.getClass());
            if (_log.shouldWarn()) {_log.warn("Running Shutdown Task " + task.getClass());}
            try {
                //task.run();
                Thread t = new I2PAppThread(task, "ShutdownTask " + task.getClass().getName());
                t.setDaemon(true);
                t.start();
                tasks.add(t);
            } catch (Throwable t) {_log.log(Log.CRIT, "Error running Shutdown Task", t);}
        }
        long waitSecs = SHUTDOWN_WAIT_SECS;
        if (SystemVersion.isSlow()) {waitSecs *= 2;}
        final long maxWait = System.currentTimeMillis() + (waitSecs *1000);
        Thread th;
        while ((th = tasks.poll()) != null) {
            long toWait = maxWait - System.currentTimeMillis();
            if (toWait <= 0) {
                _log.logAlways(Log.WARN, "Shutdown tasks took more than " + waitSecs + "s to run");
                tasks.clear();
                break;
            }
            try {th.join(toWait);}
            catch (InterruptedException ie) {}
            if (th.isAlive()) {
                _log.logAlways(Log.WARN, "Shutdown Task [" + th.getName() + "] took more than " + waitSecs + "s to run");
                tasks.clear();
                break;
            } else if (_log.shouldInfo()) {_log.info("Shutdown Task [ " + th.getName() + "] complete");}
        }

        // Set the last version to the current version, since 0.8.13
        if (!RouterVersion.VERSION.equals(_config.get("router.previousVersion"))) {
            saveConfig("router.previousVersion", RouterVersion.VERSION);
        }

        _context.removeShutdownTasks();
        _context.deleteTempDir();

        // All in-JVM clients should be gone by now,
        // unless they are stuck waiting for tunnels.
        // If we have any of those, or external clients,
        // we will wait below for the I2CP disconnect messages to get to them.
        boolean waitForClients = _killVMOnEnd && !_context.clientManager().listClients().isEmpty();
        int delay = 2000;
        if (_log.shouldWarn()) {_log.warn("Stopping ClientManager...");}
        try {_context.clientManager().shutdown();}
        catch (Throwable t) {_log.error("[ClientManager] " + t.getMessage());}
        if (waitForClients) {
            // Give time for the disconnect messages to get to them
            // so they can shut down correctly before the JVM goes away
            try {Thread.sleep(delay);} catch (InterruptedException ie) {}
            if (_log.shouldWarn()) {
                _log.warn("Done waiting " + delay + "ms for clients to disconnect, terminating subsystems...");
            }
        }

        if (_log.shouldDebug()) {
            try {_context.namingService().shutdown();} catch (Throwable t) {_log.error("[NamingService] ", t);}
            try {_context.jobQueue().shutdown();} catch (Throwable t) {_log.error("[JobQueue] ", t);}
            try {_context.tunnelManager().shutdown();} catch (Throwable t) {_log.error("[TunnelManager] ", t);}
            try {_context.tunnelDispatcher().shutdown();} catch (Throwable t) {_log.error("[TunnelDispatcher] ", t);}
            try {_context.netDbSegmentor().shutdown();} catch (Throwable t) {_log.error("[NetworkDb] ", t);}
            try {_context.commSystem().shutdown();} catch (Throwable t) {_log.error("[CommSystem] ", t);}
            try {_context.bandwidthLimiter().shutdown();} catch (Throwable t) {_log.error("[BandwidthLimiter]", t);}
            try {_context.peerManager().shutdown();} catch (Throwable t) {_log.error("[PeerManager] ",  t);}
            try {_context.messageRegistry().shutdown();} catch (Throwable t) {_log.error("[MessageRegistry] ",  t);}
            try {_context.messageValidator().shutdown();} catch (Throwable t) {_log.error("[MessageValidator] ",  t);}
            try {_context.inNetMessagePool().shutdown();} catch (Throwable t) {_log.error("[InboundNetPool] ",  t);}
            try {_context.clientMessagePool().shutdown();} catch (Throwable t) {_log.error("[ClientMessagePool] ",  t);}
            try {_context.sessionKeyManager().shutdown();} catch (Throwable t) {_log.error("[SessionKeyManager] ",  t);}
            try {_context.eciesEngine().shutdown();} catch (Throwable t) {_log.error("[ECIES engine] ",  t);}
            try {_context.messageHistory().shutdown();} catch (Throwable t) {_log.error("[MessageHistoryLogger] ",  t);}
            // do stat manager last to reduce chance of NPEs in other threads
            try {_context.statManager().shutdown();} catch (Throwable t) {_log.error("[StatsManager] ",  t);}
        } else if (isAdvanced()) {
            try {_context.namingService().shutdown();} catch (Throwable t) {_log.error("[Naming service] " + t.getMessage());}
            try {_context.jobQueue().shutdown();} catch (Throwable t) {_log.error("[JobQueue] " + t.getMessage());}
            try {_context.tunnelManager().shutdown();} catch (Throwable t) {_log.error("[TunnelManager] " + t.getMessage());}
            try {_context.tunnelDispatcher().shutdown();} catch (Throwable t) {_log.error("[TunnelDispatcher] " + t.getMessage()
                 .replace("Cannot invoke \"net.i2p.router.tunnel.TunnelGateway$Receiver.getSendTo()\"", "Cannot send to TunnelGateway"));}
            try {_context.netDbSegmentor().shutdown();} catch (Throwable t) {_log.error("[networkDb] " + t.getMessage());}
            try {_context.commSystem().shutdown();} catch (Throwable t) {_log.error("[CommSystem] " + t.getMessage());}
            try {_context.bandwidthLimiter().shutdown();} catch (Throwable t) {_log.error("[BandwidthLimiter] " + t.getMessage());}
            try {_context.peerManager().shutdown();} catch (Throwable t) {_log.error("[PeerManager] " + t.getMessage());}
            try {_context.messageRegistry().shutdown();} catch (Throwable t) {_log.error("[MessageRegistry] " + t.getMessage());}
            try {_context.messageValidator().shutdown();} catch (Throwable t) {_log.error("[MessageValidator] " + t.getMessage());}
            try {_context.inNetMessagePool().shutdown();} catch (Throwable t) {_log.error("[InboundNetPool] " + t.getMessage());}
            try {_context.clientMessagePool().shutdown();} catch (Throwable t) {_log.error("[ClientMesssagePool] " + t.getMessage());}
            try {_context.sessionKeyManager().shutdown();} catch (Throwable t) {_log.error("[SessionKeyManager] " + t.getMessage());}
            try {_context.eciesEngine().shutdown();} catch (Throwable t) {_log.error("[ECIES engine] " + t.getMessage());}
            try {_context.messageHistory().shutdown();} catch (Throwable t) {_log.error("[MessageHistoryLogger] " + t.getMessage());}
            // do stat manager last to reduce chance of NPEs in other threads
            try {_context.statManager().shutdown();} catch (Throwable t) {_log.error("[StatsManager] " + t.getMessage());}
        } else {
            try {_context.namingService().shutdown();} catch (Throwable t) {}
            try {_context.jobQueue().shutdown();} catch (Throwable t) {}
            try {_context.tunnelManager().shutdown();} catch (Throwable t) {}
            try {_context.tunnelDispatcher().shutdown();} catch (Throwable t) {}
            try {_context.netDbSegmentor().shutdown();} catch (Throwable t) {}
            try {_context.commSystem().shutdown();} catch (Throwable t) {}
            try {_context.bandwidthLimiter().shutdown();} catch (Throwable t) {}
            try {_context.peerManager().shutdown();} catch (Throwable t) {}
            try {_context.messageRegistry().shutdown();} catch (Throwable t) {}
            try {_context.messageValidator().shutdown();} catch (Throwable t) {}
            try {_context.inNetMessagePool().shutdown();} catch (Throwable t) {}
            try {_context.clientMessagePool().shutdown();} catch (Throwable t) {}
            try {_context.sessionKeyManager().shutdown();} catch (Throwable t) {}
            try {_context.eciesEngine().shutdown();} catch (Throwable t) {}
            try {_context.messageHistory().shutdown();} catch (Throwable t) {}
            // do stat manager last to reduce chance of NPEs in other threads
            try {_context.statManager().shutdown();} catch (Throwable t) {}
        }

        //_context.deleteTempDir();
        List<RouterContext> contexts = RouterContext.getContexts();
        contexts.remove(_context);

        // shut down I2PAppContext tasks here

        try {_context.elGamalEngine().shutdown();}
        catch (Throwable t) {_log.log(Log.CRIT, "[ElGamal engine] " + t.getMessage());}

        if (contexts.isEmpty()) {} // any thing else to shut down?
        else {
            _log.logAlways(Log.WARN, "Warning - " + contexts.size() + " routers remaining in this JVM, not releasing all resources");
        }
        try {((FortunaRandomSource)_context.random()).shutdown();}
        catch (Throwable t) {_log.log(Log.CRIT, "[FortunaRandomSource] " + t.getMessage());}

        // logManager shut down in finalShutdown()
        _watchdog.shutdown();
        _watchdogThread.interrupt();
        _eventLog.addEvent(EventLog.STOPPED, exitString);
        finalShutdown(exitCode);
    }

    /**
     * disable dynamic key functionality for the moment, as it may be harmful and doesn't
     * add meaningful anonymity
     */
    private static final boolean ALLOW_DYNAMIC_KEYS = true;

    /**
     *  Cancel the JVM runtime hook before calling this.
     *
     *  @param exitCode one of the EXIT_* values, non-negative
     */
    private synchronized void finalShutdown(int exitCode) {
        changeState(State.FINAL_SHUTDOWN_3);
        clearCaches();
        if (exitCode == 2) {_log.log(Log.CRIT, "Completed graceful shutdown");}
        else if (exitCode == 3) {_log.log(Log.CRIT, "Completed hard shutdown");}
/*
        else if (exitCode == 4)
            _log.log(Log.CRIT, "Completed hard restart, now restarting...");
        else if (exitCode == 5)
            _log.log(Log.CRIT, "Completed graceful restart, now restarting...");
        else if (exitCode == 10)
            _log.log(Log.CRIT, "Completed forced restart, now restarting...");
*/
        try {_context.logManager().shutdown();} catch (Throwable t) {}
        if (ALLOW_DYNAMIC_KEYS) {
            if (_context.getBooleanProperty(PROP_DYNAMIC_KEYS)) {killKeys();}
        }

        if (!SystemVersion.isAndroid()) {
            File f = getPingFile();
            f.delete();
        }

        // Only do this on Android. On desktop, rogue threads
        // may create a new I2PAppContext before the JVM stops
        // if we delete this one.
        //if (RouterContext.getContexts().isEmpty())
        if (SystemVersion.isAndroid()) {RouterContext.killGlobalContext();}

        // Since 0.8.8, for Android and the wrapper
        for (Runnable task : _context.getFinalShutdownTasks()) {
            //System.err.println("Running final shutdown task " + task.getClass());
            try {task.run();}
            catch (Throwable t) {System.err.println("Running final shutdown task " + t);}
        }
        _context.getFinalShutdownTasks().clear();

        if (_killVMOnEnd) {
            try {Thread.sleep(1000);}
            catch (InterruptedException ie) {}
            //Runtime.getRuntime().halt(exitCode);
            // allow the Runtime shutdown hooks to execute
            Runtime.getRuntime().exit(exitCode);
        }
        changeState(State.STOPPED);
    }

    /**
     * Non-blocking shutdown.
     *
     * Call this if we want the router to kill itself as soon as we aren't
     * participating in any more tunnels (etc).  This will not block and doesn't
     * guarantee any particular time frame for shutting down.  To shut the
     * router down immediately, use {@link #shutdown}.  If you want to cancel
     * the graceful shutdown (prior to actual shutdown ;), call
     * {@link #cancelGracefulShutdown}.
     *
     * Exit code will be EXIT_GRACEFUL.
     *
     * Shutdown delay will be from zero to 11 minutes.
     */
    public void shutdownGracefully() {shutdownGracefully(EXIT_GRACEFUL);}

    /**
     * Non-blocking shutdown.
     *
     * Call this with EXIT_HARD or EXIT_HARD_RESTART for a non-blocking,
     * hard, non-graceful shutdown with a brief delay to allow a UI response
     *
     * Returns silently if a final shutdown is already in progress.
     *
     * @param exitCode one of the EXIT_* values, non-negative
     * @throws IllegalArgumentException if exitCode negative
     */
    public void shutdownGracefully(int exitCode) {
        if (exitCode < 0) {throw new IllegalArgumentException();}
        synchronized(_stateLock) {
            if (isFinalShutdownInProgress()) {return;} // too late
            changeState(State.GRACEFUL_SHUTDOWN);
            _gracefulExitCode = exitCode;
        }
        _context.throttle().setShutdownStatus();
        net.i2p.i2ptunnel.TunnelControllerGroup tcg = net.i2p.i2ptunnel.TunnelControllerGroup.getInstance();
        if (tcg != null) {
            tcg.prepareGracefulShutdown();
        }
        synchronized (_gracefulShutdownDetector) {_gracefulShutdownDetector.notifyAll();}
    }

    /**
     * Cancel any prior request to shut the router down gracefully.
     *
     * Returns silently if a final shutdown is already in progress.
     */
    public void cancelGracefulShutdown() {
        synchronized(_stateLock) {
            if (isFinalShutdownInProgress()) {return;} // too late
            changeState(State.RUNNING);
            _gracefulExitCode = -1;
        }
        _context.throttle().cancelShutdownStatus();
        net.i2p.i2ptunnel.TunnelControllerGroup tcg = net.i2p.i2ptunnel.TunnelControllerGroup.getInstance();
        if (tcg != null) {
            tcg.cancelDelayedShutdown();
        }
        synchronized (_gracefulShutdownDetector) {_gracefulShutdownDetector.notifyAll();}
    }

    /**
     * What exit code do we plan on using when we shut down (or -1, if there isn't a graceful shutdown planned)
     *
     * @return one of the EXIT_* values or -1
     */
    public int scheduledGracefulExitCode() {
        synchronized(_stateLock) {return _gracefulExitCode;}
    }

    /**
     *  How long until the graceful shutdown will kill us?
     *  @return -1 if no shutdown in progress.
     */
    public long getShutdownTimeRemaining() {
        synchronized(_stateLock) {
            if (_gracefulExitCode <= 0) return -1; // maybe Long.MAX_VALUE would be better?
            if (_gracefulExitCode == EXIT_HARD || _gracefulExitCode == EXIT_HARD_RESTART) {return 0;}
        }
        long exp = _context.tunnelManager().getLastParticipatingExpiration();
        if (exp < 0) {return 0;}
        else {return Math.max(0, exp + 2*CLOCK_FUDGE_FACTOR - _context.clock().now());}
    }

    /**
     * Save the current config options (returning true if save was
     * successful, false otherwise)
     *
     * Synchronized with file read in getConfig()
     */
    public boolean saveConfig() {
        try {
            Properties ordered = new OrderedProperties();
            synchronized(_configFileLock) {
                ordered.putAll(_config);
                DataHelper.storeProps(ordered, new File(_configFilename));
            }
        } catch (IOException ioe) {
                // warning, _log will be null when called from constructor
                if (_log != null) {_log.error("Error saving the config to " + _configFilename, ioe);}
                else {System.err.println("Error saving the config to " + _configFilename + ": " + ioe);}
                return false;
        }
        return true;
    }

    /**
     * Updates the current config with the given key/value and then saves it.
     * Prevents a race in the interval between setConfigSetting() / removeConfigSetting() and saveConfig(),
     * Synchronized with getConfig() / saveConfig()
     *
     * @param name setting to add/change/remove before saving
     * @param value if non-null, updated value; if null, setting will be removed
     * @return success
     * @since 0.8.13
     */
    public boolean saveConfig(String name, String value) {
        synchronized(_configFileLock) {
            if (value != null) {_config.put(name, value);}
            else {removeConfigSetting(name);}
            return saveConfig();
        }
    }

    /**
     * Updates the current config and then saves it.
     * Prevents a race in the interval between setConfigSetting() / removeConfigSetting() and saveConfig(),
     * Synchronized with getConfig() / saveConfig()
     *
     * @param toAdd settings to add/change before saving, may be null or empty
     * @param toRemove settings to remove before saving, may be null or empty
     * @return success
     * @since 0.8.13
     */
    @SuppressWarnings({"unchecked", "rawtypes" })
    public boolean saveConfig(Map toAdd, Collection<String> toRemove) {
        synchronized(_configFileLock) {
            if (toAdd != null) {_config.putAll(toAdd);}
            if (toRemove != null) {
                for (String s : toRemove) {removeConfigSetting(s);}
            }
            return saveConfig();
        }
    }

    /**
     *  The clock shift listener.
     *  Restart the router if we should.
     *
     *  @since 0.8.8
     */
    public void clockShift(long delta) {
        if (delta > -60*1000 && delta < 60*1000) {return;}
        synchronized(_stateLock) {
            if (gracefulShutdownInProgress() || !isAlive()) {return;}
        }
        _eventLog.addEvent(EventLog.CLOCK_SHIFT, Long.toString(delta) + "ms");
        // update the routing key modifier
        _context.routerKeyGenerator().generateDateBasedModData();
        if (delta > 0) {_log.error("Restarting after large clock shift forward by " + DataHelper.formatDuration(delta) + "ms");}
        else {_log.error("Restarting after large clock shift backward by " + DataHelper.formatDuration(0 - delta) + "ms");}
        restart();
    }

    /**
     *  A "soft" restart, primarily of the comm system, after a port change or large step-change in system time.
     *  Does not stop the whole JVM, so it is safe even in the absence of the wrapper.
     *  This is not a graceful restart - all peer connections are dropped immediately.
     *
     *  As of 0.8.8, this returns immediately and does the actual restart in a separate thread.
     *  Poll isAlive() if you need to know when the restart is complete.
     *
     *  Not recommended for external use.
     */
    public synchronized void restart() {
        synchronized(_stateLock) {
            if (gracefulShutdownInProgress() || !isAlive()) {return;}
            changeState(State.RESTARTING);
        }
        ((RouterClock) _context.clock()).removeShiftListener(this);
        // Stop accepting tunnels, etc.
        // This also prevents netdb from immediately expiring all the RIs
        _started = System.currentTimeMillis();
        synchronized(_configFileLock) {_downtime = 1;}
        Thread t = new I2PThread(new Restarter(_context), "Router Restart");
        t.setPriority(I2PThread.NORM_PRIORITY + 1);
        t.start();
    }

    /**
     *  Usage: Router [rebuild]
     *  No other options allowed, for now
     *  Instantiates Router(), and either installs updates and exits,
     *  or calls runRouter().
     *
     *  Not recommended for embedded use.
     *  Applications bundling I2P should instantiate a Router and call runRouter().
     *
     *  @param args null ok
     *  @throws IllegalArgumentException
     */
    public static void main(String args[]) {
        boolean rebuild = false;
        if (args != null) {
            boolean error = false;
            Getopt g = new Getopt("router", args, "");
            int c;
            while ((c = g.getopt()) != -1) {
                switch (c) {
                    default:
                        error = true;
                }
            }
            int remaining = args.length - g.getOptind();
            if (remaining > 1) {error = true;}
            else if (remaining == 1) {
                rebuild = args[g.getOptind()].equals("rebuild");
                if (!rebuild) {error = true;}
            }
            if (error) {throw new IllegalArgumentException();}
        }

        System.out.println("Starting I2P+ " + RouterVersion.FULL_VERSION + "...");
        //verifyWrapperConfig();
        Router r;
        try {r = new Router();}
        catch (IllegalStateException ise) {System.exit(-1); return;}
        if (rebuild) {r.rebuildNewIdentity();}
        else {
            /**
             * This is here so that we can get the directory location from the context for the
             * zip file and the base location to unzip to. If it does an update, it never returns.
             * I guess it's better to have the other-router check above this, we don't want to
             * overwrite an existing running router's jar files, except for ours.
             */
            InstallUpdate.installUpdates(r);
            // *********  Start no threads before here ********* //
            r.runRouter();
        }
    }

    private File getPingFile() {
        String s = _context.getProperty("router.pingFile", "router.ping");
        File f = new File(s);
        if (!f.isAbsolute()) {f = new File(_context.getPIDDir(), s);}
        return f;
    }

    private static final long LIVELINESS_DELAY = 60*1000;

    /**
     * Check the file "router.ping", but if
     * that file already exists and was recently written to, return false as there is
     * another instance running.
     *
     * @return true if the router is the only one running
     * @since 0.8.2
     */
    private boolean isOnlyRouterRunning() {
        File f = getPingFile();
        if (f.exists()) {
            long lastWritten = f.lastModified();
            long downtime = System.currentTimeMillis() - lastWritten;
            synchronized(_configFileLock) {
                if (downtime > 0 && _downtime < 0) {_downtime = downtime;}
            }
            if (downtime > LIVELINESS_DELAY) {
                System.err.println("WARN: Old router was not shut down gracefully -> Deleting " + f + "...");
                f.delete();
                if (lastWritten > 0) {
                    _eventLog.addEvent(EventLog.CRASHED, Translate.getString("{0} ago",
                                       DataHelper.formatDuration2(downtime), _context, BUNDLE_NAME));
                }
            } else {return false;}
        }
        return true;
    }

    /**
     * Start a thread that will periodically update the file "router.ping".
     * isOnlyRouterRunning() MUST have been called previously.
     */
    private void beginMarkingLiveliness() {
        File f = getPingFile();
        _context.simpleTimer2().addPeriodicEvent(new MarkLiveliness(this, f), 0, LIVELINESS_DELAY - (5*1000));
    }

    /**
     *  How long this router was down before it started, or 0 if unknown.
     *
     *  This may be used for a determination of whether to regenerate keys, for example.
     *  We use the timestamp of the previous ping file left behind on crash,
     *  as set by isOnlyRouterRunning(), if present.
     *  Otherwise, the last STOPPED entry in the event log.
     *
     *  May take a while to run the first time, if it has to go through the event log.
     *  Once called, the result is cached.
     *
     *  @since 0.9.47
     */
    public long getEstimatedDowntime() {
        synchronized(_configFileLock) {
            if (_downtime >= 0) {return _downtime;}
            long begin = System.currentTimeMillis();
            long stopped = _eventLog.getLastEvent(EventLog.STOPPED, _context.clock().now() - 365*24*60*60*1000L);
            long downtime = stopped > 0 ? _started - stopped : 0;
            if (downtime < 0) {downtime = 0;}
            if (_log.shouldWarn()) {
                _log.warn("Router has been down for " + DataHelper.formatDuration(downtime));
            }
            _downtime = downtime;
            return downtime;
        }
    }

    /**
     *  Only for soft restart. Not for external use.
     *
     *  @since 0.9.47
     */
    public void setEstimatedDowntime(long downtime) {
        if (downtime <= 0) {downtime = 1;}
        synchronized(_configFileLock) {_downtime = downtime;}
    }

    public static final String PROP_BANDWIDTH_SHARE_PERCENTAGE = "router.sharePercentage";
    public static final int DEFAULT_SHARE_PERCENTAGE = 90;

    /**
     * What fraction of the bandwidth specified in our bandwidth limits should
     * we allow to be consumed by participating tunnels?
     * @return a number less than one, not a percentage!
     *
     */
    public double getSharePercentage() {
        String pct = _context.getProperty(PROP_BANDWIDTH_SHARE_PERCENTAGE);
        if (pct != null) {
            try {
                double d = Double.parseDouble(pct);
                if (d > 1) {return d/100d;} // *cough* sometimes it's 80 instead of .8 (!stab jrandom)
                else {return d;}
            } catch (NumberFormatException nfe) {
                if (_log.shouldInfo()) {_log.info("Unable to get the share percentage");}
            }
        }
        return DEFAULT_SHARE_PERCENTAGE / 100.0d;
    }

    /**
     *  Max of inbound and outbound rate in bytes per second
     */
    public int get1sRate() {return get1sRate(false);}

    /**
     *  When outboundOnly is false, outbound rate in bytes per second.
     *  When true, max of inbound and outbound rate in bytes per second.
     */
    public int get1sRate(boolean outboundOnly) {
        FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
        int out = (int)bw.getSendBps();
        if (outboundOnly) {return out;}
        return (int)Math.max(out, bw.getReceiveBps());
    }

    /**
     *  Inbound rate in bytes per second
     */
    public int get1sRateIn() {
        FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
        return (int) bw.getReceiveBps();
    }

    /**
     *  Max of inbound and outbound rate in bytes per second
     */
    public int get15sRate() {return get15sRate(false);}

    /**
     *  When outboundOnly is false, outbound rate in bytes per second.
     *  When true, max of inbound and outbound rate in bytes per second.
     */
    public int get15sRate(boolean outboundOnly) {
        FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
        int out = (int)bw.getSendBps15s();
        if (outboundOnly) {return out;}
        return (int)Math.max(out, bw.getReceiveBps15s());
    }

    /**
     *  Inbound rate in bytes per second
     */
    public int get15sRateIn() {
        FIFOBandwidthLimiter bw = _context.bandwidthLimiter();
        return (int) bw.getReceiveBps15s();
    }

    /**
     *  Max of inbound and outbound rate in bytes per second
     */
    public int get1mRate() {return get1mRate(false);}

    /**
     *  When outboundOnly is false, outbound rate in bytes per second.
     *  When true, max of inbound and outbound rate in bytes per second.
     */
    public int get1mRate(boolean outboundOnly) {
        int send = 0;
        StatManager mgr = _context.statManager();
        RateStat rs = mgr.getRate("bw.sendRate");
        if (rs != null) {send = (int)rs.getRate(RateConstants.ONE_MINUTE).getAverageValue();}
        if (outboundOnly) {return send;}
        int recv = 0;
        rs = mgr.getRate("bw.recvRate");
        if (rs != null) {recv = (int)rs.getRate(RateConstants.ONE_MINUTE).getAverageValue();}
        return Math.max(send, recv);
    }

    /**
     *  Inbound rate in bytes per second
     */
    public int get1mRateIn() {
        StatManager mgr = _context.statManager();
        RateStat rs = mgr.getRate("bw.recvRate");
        int recv = 0;
        if (rs != null) {recv = (int)rs.getRate(RateConstants.ONE_MINUTE).getAverageValue();}
        return recv;
    }

    /**
     *  Max of inbound and outbound rate in bytes per second
     */
    public int get5mRate() {return get5mRate(false);}

    /**
     *  When outboundOnly is false, outbound rate in bytes per second.
     *  When true, max of inbound and outbound rate in bytes per second.
     */
    public int get5mRate(boolean outboundOnly) {
        int send = 0;
        RateStat rs = _context.statManager().getRate("bw.sendRate");
        if (rs != null) {send = (int)rs.getRate(RateConstants.FIVE_MINUTES).getAverageValue();}
        if (outboundOnly) {return send;}
        int recv = 0;
        rs = _context.statManager().getRate("bw.recvRate");
        if (rs != null) {recv = (int)rs.getRate(RateConstants.FIVE_MINUTES).getAverageValue();}
        return Math.max(send, recv);
    }

    /**
     *  Translate with console bundle
     *  @since 0.9.53
     */
    private final String _t(String s) {return Translate.getString(s, _context, BUNDLE_NAME);}

}
