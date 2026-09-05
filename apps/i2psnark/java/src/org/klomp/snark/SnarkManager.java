package org.klomp.snark;

import java.io.File;
import java.io.FileFilter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.Collator;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.app.NavService;
import net.i2p.app.NotificationService;
import net.i2p.client.I2PClient;
import net.i2p.client.streaming.I2PSocketManager.DisconnectListener;
import net.i2p.crypto.SHA1Hash;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.update.*;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.UIMessages;
import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;
import org.klomp.snark.dht.DHT;
import org.klomp.snark.dht.KRPC;

/**
 * Central manager for multiple torrent downloads and the I2PSnark application.
 *
 * <p>This class is the core controller that manages:
 *
 * <ul>
 *   <li>Multiple torrent instances (Snarks) and their lifecycle
 *   <li>Configuration management and persistence
 *   <li>Peer coordination and bandwidth management
 *   <li>Tracker communication and DHT integration
 *   <li>Web interface and user interaction
 *   <li>Torrent file monitoring and auto-loading
 *   <li>Magnet link handling
 *   <li>Comments and metadata management
 * </ul>
 *
 * <p>As a ClientApp, it integrates with the I2P router application framework and can be started,
 * stopped, and monitored through the standard I2P interfaces.
 *
 * @since 0.1.0
 */
public class SnarkManager implements CompleteListener, ClientApp, DisconnectListener {

    /**
     * Map of (canonical) filename of the .torrent file to Snark instance. This is a CHM so
     * listTorrentFiles() need not be synced, but all adds, deletes, and the DirMonitor should sync
     * on it.
     */
    private final Map<String, Snark> _snarks;

    // sync on _snarks
    private final Map<SHA1Hash, Snark> _infoHashToSnark;
    // sync on _snarks
    private final Map<String, Snark> _filteredBaseNameToSnark;

    /** Prevents DirMonitor from deleting torrents that don't have a torrent file yet. */
    private final Set<String> _magnets;

    private final Object _addSnarkLock;
    private File _configFile;
    private File _configDir;
    private File _metadataFile;
    private Properties _metadata;

    /** One lock for all config, files for simplicity. */
    private final Object _configLock = new Object();

    private Properties _config;
    private final I2PAppContext _context;
    private final String _contextPath;
    private final String _contextName;
    private final Log _log;
    private final UIMessages _messages;
    private final I2PSnarkUtil _util;
    private final PeerCoordinatorSet _peerCoordinatorSet;
    private final ConnectionAcceptor _connectionAcceptor;
    private final BandwidthManager _bwManager;
    private Thread _monitor;
    private volatile boolean _running;
    private volatile boolean _stopping;
    private final Map<String, Tracker> _trackerMap;
    private final Map<String, TorrentCreateFilter> _torrentCreateFilterMap;
    private UpdateManager _umgr;
    private UpdateHandler _uhandler;
    private SimpleTimer2.TimedEvent _idleChecker;

    /** Max concurrent DHT lookup threads (zzzot lookups). @since 0.9.71+ */
    private static final int MAX_LOOKUP_CONCURRENCY = 8;
    private static final long LOOKUP_STALE_MS = 10 * 60 * 1000;
    private final Semaphore _lookupSemaphore = new Semaphore(MAX_LOOKUP_CONCURRENCY, true);
    /** infohash → time (ms) when lookup was created, for stale cleanup. */
    private final Map<SHA1Hash, Long> _lookupCreationTimes = new ConcurrentHashMap<>(8);
    private volatile boolean _staleLookupCleanupRunning;

    private volatile boolean _randomizeStartupDelay = true;
    private volatile boolean _browserApiEnabled;
    /** Raw comma-separated config value, for the config form. */
    private volatile String _browserApiHosts = "";
    /** Resolved allowed hosts (excluding loopback, which is always allowed). */
    private volatile Set<InetAddress> _browserApiHostSet = new HashSet<>();

    public static final String PROP_I2CP_HOST = "i2psnark.i2cpHost";
    public static final String PROP_I2CP_PORT = "i2psnark.i2cpPort";
    public static final String PROP_I2CP_OPTS = "i2psnark.i2cpOptions";
    public static final String PROP_UPLOADERS_TOTAL = "i2psnark.uploaders.total";
    public static final String PROP_UPBW_MAX = "i2psnark.upbw.max";

    /**
     * @since 0.9.62
     */
    public static final String PROP_DOWNBW_MAX = "i2psnark.downbw.max";

    /** Minimum request pipeline depth for outbound requests. @since 0.9.71+ */
    public static final String PROP_MIN_PIPELINE = "i2psnark.pipeline.min";
    /** Maximum request pipeline depth for outbound requests. @since 0.9.71+ */
    public static final String PROP_MAX_PIPELINE = "i2psnark.pipeline.max";
    /** Chunk size for outbound piece requests. @since 0.9.71+ */
    public static final String PROP_PARTSIZE = "i2psnark.pipeline.partsize";
    /** Cap on a single piece request from a peer. @since 0.9.71+ */
    public static final String PROP_MAX_PARTSIZE = "i2psnark.pipeline.maxPartsize";

    public static final String PROP_DIR = "i2psnark.dir";

    /**
     * Configuration key for the staging directory where incomplete files are
     * written until a file's pieces are all downloaded, at which point the
     * file is copied into the data directory. Unset or empty disables the
     * feature.
     *
     * @since 0.9.71+
     */
    public static final String PROP_TEMP_DIR = "i2psnark.tempDir";

    /**
     * Configuration key for the directory where .torrent files are stored,
     * separate from the data directory. Unset or empty falls back to the
     * data directory.
     *
     * @since 0.9.71+
     */
    public static final String PROP_TORRENT_DIR = "i2psnark.torrentDir";

    private static final String PROP_META_PREFIX = "i2psnark.zmeta.";
    static final String PROP_META_RUNNING = "running";
    private static final String PROP_META_STAMP = "stamp";
    private static final String PROP_META_BASE = "base";
    private static final String PROP_META_BITFIELD = "bitfield";
    private static final String PROP_META_PRIORITY = "priority";
    private static final String PROP_META_UPLOADED = "uploaded";
    private static final String PROP_META_ADDED = "added";
    private static final String PROP_META_COMPLETED = "completed";
    private static final String PROP_META_MAGNET = "magnet";
    private static final String PROP_META_MAGNET_DN = "magnet_dn";
    private static final String PROP_META_MAGNET_TR = "magnet_tr";
    private static final String PROP_META_MAGNET_DIR = "magnet_dir";
    private static final String PROP_META_MAGNET_PREFIX = "i2psnark.magnet.";

    /**
     * @since 0.9.31
     */
    private static final String PROP_META_COMMENTS = "comments";

    /**
     * @since 0.9.42
     */
    private static final String PROP_META_ACTIVITY = "activity";

    /**
     * Deprecated per-torrent keys removed in 0.9.72.
     * preserveFileNames is now global; inOrder was removed entirely.
     * Stripped on load and migration.
     *
     * @since 0.9.72
     */
    private static final String DEPRECATED_PRESERVE_FILE_NAMES = "preserveFileNames";
    private static final String DEPRECATED_IN_ORDER = "inOrder";

    private static final String CONFIG_FILE_SUFFIX = ".config";
    public static final String CONFIG_FILE = "i2psnark" + CONFIG_FILE_SUFFIX;
    private static final String COMMENT_FILE_SUFFIX = ".comments.txt.gz";
    private static final String METADATA_FILE = "i2psnark.metadata";
    private static final String META_PREFIX = "zmeta.";
    public static final String PROP_FILES_PUBLIC = "i2psnark.filesPublic";

    /**
     * @since 0.9.66+
     */
    public static final String PROP_PREALLOCATE_FILES = "i2psnark.preallocateFiles";

    public static final String DEFAULT_PREALLOCATE_FILES = "true";

    /**
     * Add BEP 47 padding files to new torrents.
     *
     * @since 0.9.71+
     */
    public static final String PROP_SHOULD_PAD_FILES = "i2psnark.shouldPadFiles";

    public static final String DEFAULT_SHOULD_PAD_FILES = "false";

    /**
     * Disconnect peers that cancel most of what they request. Off by default; conservative
     * thresholds and a minimum-volume guard protect legitimate peers under congestion.
     *
     * @since 0.9.71+
     */
    public static final String PROP_BAN_DISCARD_RATIO = "i2psnark.banDiscardRatio";

    public static final String DEFAULT_BAN_DISCARD_RATIO = "true";

    /**
     * Duration of the ban applied to peers with an excessive discard ratio, in minutes.
     *
     * @since 0.9.71+
     */
    public static final String PROP_BAN_DISCARD_PERIOD = "i2psnark.banDiscardPeriod";

    public static final int DEFAULT_BAN_DISCARD_PERIOD = 60;
    public static final String PROP_OLD_AUTO_START = "i2snark.autoStart"; // oops
    public static final String PROP_AUTO_START =
            "i2psnark.autoStart"; // convert in migration to new config file
    private final boolean DEFAULT_AUTO_START;
    /** @deprecated since 2.13.0 replaced by PROP_STARTUP_DELAY_MIN/MAX, kept for migration */
    public static final String PROP_STARTUP_DELAY = "i2psnark.startupDelay";
    public static final String PROP_STARTUP_DELAY_MIN = "i2psnark.startupDelayMin";
    public static final String PROP_STARTUP_DELAY_MAX = "i2psnark.startupDelayMax";
    public static final String PROP_REFRESH_DELAY = "i2psnark.refreshSeconds";
    public static final String PROP_PAGE_SIZE = "i2psnark.pageSize";
    public static final String RC_PROP_THEME = "routerconsole.theme";
    public static final String RC_PROP_UNIVERSAL_THEMING = "routerconsole.universal.theme";
    public static final String PROP_THEME = "i2psnark.theme";
    public static final String DEFAULT_THEME = "ubergine";

    /**
     * @since 0.9.32
     */
    public static final String PROP_COLLAPSE_PANELS = "i2psnark.collapsePanels";

    /**
     * @since 0.9.34
     */
    public static final String PROP_SHOW_STATUSFILTER = "i2psnark.showStatusFilter";

    public static final String DEFAULT_SHOW_STATUSFILTER = "false";

    /**
     * @since 0.9.34
     */
    public static final String PROP_ENABLE_LIGHTBOX = "i2psnark.enableLightbox";

    public static final String DEFAULT_ENABLE_LIGHTBOX = "true";

    /**
     * @since 0.9.38
     */
    public static final String PROP_ENABLE_ADDCREATE = "i2psnark.enableAddCreate";

    public static final String DEFAULT_ENABLE_ADDCREATE = "false";
    private static final String PROP_USE_OPENTRACKERS = "i2psnark.useOpentrackers";
    public static final String PROP_OPENTRACKERS = "i2psnark.opentrackers";
    public static final String PROP_PRIVATETRACKERS = "i2psnark.privatetrackers";
    private static final String PROP_USE_DHT = "i2psnark.enableDHT";
    private static final String PROP_SMART_SORT = "i2psnark.smartSort";
    private static final String PROP_LANG = "i2psnark.lang";
    private static final String PROP_COUNTRY = "i2psnark.country";

    /**
     * @since 0.9.31
     */
    private static final String PROP_RATINGS = "i2psnark.ratings";

    /**
     * @since 0.9.31
     */
    private static final String PROP_COMMENTS = "i2psnark.comments";

    /**
     * @since 0.9.31
     */
    private static final String PROP_COMMENTS_NAME = "i2psnark.commentsName";

    /**
     * @since 0.9.58
     */
    public static final String PROP_MAX_FILES_PER_TORRENT = "i2psnark.maxFilesPerTorrent";

    /**
     * @since 0.9.67
     */
    private static final String PROP_API_PREFIX = "i2psnark.apikey.";

    /**
     * Whether to preserve original file names from the torrent.
     * When false (default), filenames are filtered to remove illegal filesystem characters.
     * When true, original filenames are used but may cause errors on some filesystems;
     * a per-file fallback to safe names occurs with a warning message.
     *
     * @since 0.9.71+
     */
    static final String PROP_PRESERVE_FILE_NAMES = "i2psnark.preserveFileNames";

    /**
     * Whether the nonce-free browser API (magnet/torrent add) is enabled.
     * @since 0.9.71+
     */
    public static final String PROP_BROWSER_API = "i2psnark.browserApi";

    /**
     * Comma-separated hosts (IPs or hostnames) allowed to use the browser API
     * without a nonce, in addition to loopback. Ignored when PROP_BROWSER_API is false.
     * @since 0.9.71+
     */
    public static final String PROP_BROWSER_API_HOSTS = "i2psnark.browserApiHosts";

    /**
     * @since 0.9.61+
     */
    public static final String PROP_MAX_MESSAGES = "i2psnark.maxLogMessages";

    /**
     * @since 0.9.64+
     */
    public static final String PROP_VARY_INBOUND_HOPS = "i2psnark.varyInboundHops";

    public static final String PROP_VARY_OUTBOUND_HOPS = "i2psnark.varyOutboundHops";

    /**
     * Whether each torrent runs on its own destination.
     *
     * @since 0.9.71+
     */
    public static final String PROP_MULTI_DEST = "i2psnark.multiDest";

    /**
     * Maximum number of destinations in multi-dest mode, 0 for one per torrent.
     *
     * @since 0.9.71+
     */
    public static final String PROP_MULTI_DEST_MAX = "i2psnark.multiDestMax";

    /**
     * Client identity spoofing: empty (default) to identify as I2PSnark,
     * "random" to pick a random known client profile per destination per run,
     * or the name of a known client (e.g. "Vuze") to always impersonate it.
     * Applies to the tracker peer ID and User-Agent, the BT handshake peer ID,
     * the BEP 10 extension handshake "v" string, and webseed fetches.
     *
     * @since 0.9.71+
     */
    public static final String PROP_CLIENT_ID = "i2psnark.clientId";

    /**
     * Optional comma-separated subset of client names (see
     * {@link org.klomp.snark.ClientID#profiles()}) to choose from when
     * {@link #PROP_CLIENT_ID} is "random"; unknown names are ignored.
     *
     * @since 0.9.71+
     */
    public static final String PROP_CLIENT_IDS = "i2psnark.clientIds";

    /**
     * Whether running torrents are periodically stopped and restarted so their
     * destinations rotate to fresh identities, breaking long-lived linkage
     * between the router's IP and the torrents' destinations at trackers and in
     * the DHT. Destinations are ephemeral anyway, changing on every router
     * restart; this extends that to within a run. The cycle is skipped while
     * any torrent is actively downloading, and only the torrents running when
     * the cycle fires are restarted.
     *
     * @since 0.9.71+
     */
    public static final String PROP_DEST_CYCLE = "i2psnark.destCycle";

    /** Destinations cycle every this long, plus a random jitter */
    private static final long DEST_CYCLE_INTERVAL = 3 * (long) 60 * 60 * 1000;

    /** Random jitter added to each destination cycle, so routers do not rotate in lockstep */
    private static final int DEST_CYCLE_JITTER = 60 * 60 * 1000;

    /** Delay after the stop-all before restarting, long enough for the Disconnector to close the old session */
    private static final long DEST_CYCLE_RESTART_DELAY = 70 * 1000;

    /**
     * Stagger batched starts with a random delay, so torrents that start together
     * cannot be correlated by trackers or DHT peers, and tunnel builds are spread
     * out. Disable to start all torrents in a batch immediately.
     *
     * @since 0.9.71+
     */
    public static final String PROP_RANDOMIZE_STARTUP = "i2psnark.randomizeStartupDelay";

    /** Default cap on destinations so many torrents share destinations instead of exhausting memory */
    public static final int DEFAULT_MULTI_DEST_MAX = 50;
    /** Absolute maximum for the destination cap */
    public static final int MAX_MULTI_DEST = 1000;

    public static final int MIN_UP_BW = 5;
    public static final int MIN_DOWN_BW = 2 * MIN_UP_BW;
    public static final int DEFAULT_MAX_UP_BW = 1024;
    private static final int DEFAULT_MAX_DOWN_BW = 1024;
    public static final int DEFAULT_STARTUP_DELAY_MIN = 3;
    public static final int DEFAULT_STARTUP_DELAY_MAX = 10;
    public static final int DEFAULT_REFRESH_DELAY_SECS = 5;
    private static final int DEFAULT_PAGE_SIZE = 50;
    public static final int DEFAULT_TUNNEL_QUANTITY = 16;
    /** Delay between per-destination torrent starts, allowing each destination's tunnels to build */
    private static final long MULTI_DEST_STAGGER_MS = 8 * 1000;
    public static final int DEFAULT_MAX_FILES_PER_TORRENT = 2000;

    /**
     * Wait before the next auto-started torrent in a batch when multi-destination mode is
     * on, once per pool, spreading out tunnel builds and decoupling start times from batch
     * order. No wait in single-dest mode, where all torrents share the one session, or
     * when randomize startup delay is disabled.
     *
     * @since 0.9.71+
     */
    private void multiDestStartDelay() {
        if (!_util.getMultiDest() || !_randomizeStartupDelay) {
            return;
        }
        sleep(startDelay(_context.random()));
    }

    /**
     * Random delay between auto-started torrents in multi-dest mode, 30-90 seconds.
     *
     * @param rnd source of randomness
     * @return delay in milliseconds
     * @since 0.9.71+
     */
    static long startDelay(Random rnd) {
        return 30L * 1000 + 1000L * rnd.nextInt(61);
    }

    /**
     * Sleep, ignoring interruption.
     *
     * @param ms delay in milliseconds
     * @since 0.9.71+
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) { /* ignored */ }
    }

    public static final String CONFIG_DIR_SUFFIX = ".d";
    private static final String SUBDIR_PREFIX = "s";
    private static final String B64 = Base64.ALPHABET_I2P;
    private static final Pattern COMMENT_CLEANUP = Pattern.compile("[\n\r<>#;]");
    private static final int DEFAULT_MAX_MESSAGES = 50;

    /**
     * "name", "announceURL=websiteURL" pairs '=' in announceURL must be escaped as &#44;
     *
     * <p>Please use host name, not b32 or full dest, in announce URL. Ensure in default hosts.txt.
     * Please use host name, not b32 or full dest, in website URL. Ensure in default hosts.txt.
     */
    private static final String[] DEFAULT_TRACKERS = {
        "Postman", "http://tracker2.postman.i2p/announce.php=http://tracker2.postman.i2p/",
        "BT", "http://opentracker.bt.i2p/a=http://opentracker.bt.i2p/stats",
        "DgTrack", "http://opentracker.dg2.i2p/a=http://opentracker.dg2.i2p/",
        "R4SAS", "http://opentracker.r4sas.i2p/a=http://opentracker.r4sas.i2p/stats",
        "InsulaOcculta", "http://tracker.insulaocculta.i2p/a=http://insulaocculta.i2p/",
        "InsulaOcculta [UDP]", "udp://tracker.insulaocculta.i2p:6969/=http://insulaocculta.i2p/",
        "Sigma", "http://sigmatracker.i2p/a=http://sigmatracker.i2p/",
        //"Simp", "http://opentracker.simp.i2p/a=http://opentracker.simp.i2p/tracker",
        //"Simp [UDP]", "udp://opentracker.simp.i2p:6969/=http://opentracker.simp.i2p/tracker/",
        "Skank", "http://opentracker.skank.i2p/a=http://opentracker.skank.i2p/tracker",
        "Skank [UDP]", "udp://opentracker.skank.i2p:6969/=http://opentracker.skank.i2p/tracker/",
        "Actix", "http://opentracker-actix.i2p/announce=http://opentracker-actix.i2p/",
        "FattyDove", "http://opentracker.fattydove.i2p/a=http://opentracker.fattydove.i2p/",
        "LocalCache", "http://opentracker.localcache.i2p/a=http://opentracker.localcache.i2p/",
        "Observations", "http://opentracker-observations.i2p/a=http://opentracker-observations.i2p/",
        "Public", "http://opentracker-public.i2p/announce=http://opentracker-public.i2p/",
        "YetAnother", "http://yet-another-public-tracker.i2p/announce=http://yet-another-public-tracker.i2p/",
    };

    /** URL. This is our equivalent to router.utorrent.com for bootstrap */
    public static final String DEFAULT_BACKUP_TRACKER = "http://opentracker.dg2.i2p/a";

    /** URLs, comma-separated. Used for "announce to open trackers also" */
    private static final String DEFAULT_OPENTRACKERS =
        "http://opentracker.bt.i2p/a," +
        "http://opentracker.dg2.i2p/a," +
        "http://opentracker.r4sas.i2p/a," +
        //"http://opentracker.simp.i2p/a," +
        "http://opentracker.skank.i2p/a," +
        "http://sigmatracker.i2p/a," +
        "http://tracker.insulaocculta.i2p/a," +
        "http://opentracker-actix.i2p/announce," +
        "http://opentracker.fattydove.i2p/a," +
        "http://opentracker.localcache.i2p/a," +
        "http://opentracker-observations.i2p/a," +
        "http://opentracker-public.i2p/announce," +
        "http://yet-another-public-tracker.i2p/announce";

    /**
     * Default set of tracker announce URLs to use when no torrent trackers are configured.
     */
    public static final Set<String> DEFAULT_TRACKER_ANNOUNCES;

    /** Host names for config form. */
    static final Set<String> KNOWN_OPENTRACKERS = new HashSet<>(Arrays.asList(new String[] {
        "opentracker.bt.i2p", "ev5dpxvcmshi6mil7gaon3b2wbplwylzraxs4wtz7dd5lzdsc2dq.b32.i2p",
        "opentracker.dg2.i2p", "w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p",
        "opentracker.r4sas.i2p", "punzipidirfqspstvzpj6gb4tkuykqp6quurj6e23bgxcxhdoe7q.b32.i2p",
        //"opentracker.simp.i2p", "wc4sciqgkceddn6twerzkfod6p2npm733p7z3zwsjfzhc4yulita.b32.i2p",
        "opentracker.skank.i2p", "by7luzwhx733fhc5ug2o75dcaunblq2ztlshzd7qvptaoa73nqua.b32.i2p",
        "sigmatracker.i2p", "qimlze77z7w32lx2ntnwkuqslrzlsqy7774v3urueuarafyqik5a.b32.i2p",
        "tracker.insulaocculta.i2p", "4cxcka62bhpnl6raidcx7mgwkybaw3svckgbkprktd4zogc7npca.b32.i2p",
        "opentracker-actix.i2p", "cbrjdqygiogbtn4w5ngducnc3l3ipkt7e2muisfnuea4zek4bmhq.b32.i2p",
        "opentracker.fattydove.i2p", "svece3bxv4vqlt2zuut5ww4ztkwunfcnab55pmnjjb6zfei3noha.b32.i2p",
        "opentracker.localcache.i2p", "trackfmu3by6nhkibnw5exyhibjrvxl6k3e4y54wmjy4bvqgs3ha.b32.i2p",
        "opentracker-observations.i2p", "7o3d4x4jpk3pvmmposhfqhc44zsy2dbtayfapuic3nuhh3r4grqq.b32.i2p",
        "opentracker-public.i2p", "vej4thpwphy6vit6v6abirqyvhporm7whrbyk74vvjhppfmd3plq.b32.i2p",
        "yet-another-public-tracker.i2p", "ybi4axxg4bduwsenutmsynz2plfdb4jzr2vhapvprvzcxskx2foa.b32.i2p",
    }));

    private static final String[] DEFAULT_TORRENT_CREATE_FILTERS = {
        ".backup files", ".backup", "ends_with",
        ".bak files", ".bak", "ends_with",
        ".nfo files", ".nfo", "ends_with",
        "DO_NOT_MIRROR.exe", "DO_NOT_MIRROR.exe", "contains",
        "Hidden unix files", ".", "starts_with",
        "macOS folder metadata", "DS_Store", "contains",
        "Synology NAS metadata", "@eaDir", "contains",
        "Temporary backup files", "~", "ends_with"
    };

    static {
        Set<String> ann = new HashSet<>(8);
        for (int i = 1; i < DEFAULT_TRACKERS.length; i += 2) {
            if (DEFAULT_TRACKERS[i - 1].equals("TheBland")
                    && !SigType.ECDSA_SHA256_P256.isAvailable()) {
                continue;
            }
            String[] urls = DataHelper.split(DEFAULT_TRACKERS[i], "=", 2);
            ann.add(urls[0]);
        }
        DEFAULT_TRACKER_ANNOUNCES = Collections.unmodifiableSet(ann);
    }

    /** Comma delimited list of name=announceURL=baseURL for the trackers to be displayed. */
    public static final String PROP_TRACKERS = "i2psnark.trackers";

    /**
     * Comma delimited list of name=filterPattern for torrent create filters. Deprecated. If
     * detected, filters will be converted to new storage and then this config will be removed.
     */
    public static final String PROP_TORRENT_CREATE_FILTERS = "i2psnark.torrent_create_filters";

    /** Filename for serialized torrent filters config. */
    public static final String PROP_TORRENT_FILTERS_CONFIG = "filters.conf";

    /** For embedded. */
    public SnarkManager(I2PAppContext ctx) {
        this(ctx, "/i2psnark", "i2psnark");
    }

    /**
     * For webapp.
     *
     * @param ctxPath generally "/i2psnark"
     * @param ctxName generally "i2psnark"
     * @since 0.9.6
     */
    public SnarkManager(I2PAppContext ctx, String ctxPath, String ctxName) {
        _snarks = new ConcurrentHashMap<>();
        _infoHashToSnark = new HashMap<>();
        _filteredBaseNameToSnark = new HashMap<>();
        _magnets = new ConcurrentHashSet<>();
        _addSnarkLock = new Object();
        _context = ctx;
        _contextPath = ctxPath;
        _contextName = ctxName;
        _log = _context.logManager().getLog(SnarkManager.class);
        _messages = new UIMessages(DEFAULT_MAX_MESSAGES);
        _util = new I2PSnarkUtil(_context, ctxName, this);
        _peerCoordinatorSet = new PeerCoordinatorSet();
        _connectionAcceptor = new ConnectionAcceptor(_util, _peerCoordinatorSet);
        _bwManager =
                new BandwidthManager(ctx, DEFAULT_MAX_UP_BW * 1024, DEFAULT_MAX_DOWN_BW * 1024);
        DEFAULT_AUTO_START = true;
        String cfile = ctxName + CONFIG_FILE_SUFFIX;
        File configFile = new File(cfile);
        if (!configFile.isAbsolute()) {
            configFile = new File(_context.getConfigDir(), cfile);
        }
        _configDir = migrateConfig(configFile);
        _configFile = new File(_configDir, CONFIG_FILE);
        _metadataFile = new File(_configDir, METADATA_FILE);
        _trackerMap = new ConcurrentHashMap<>(4);
        _torrentCreateFilterMap = new ConcurrentHashMap<>(3);
        synchronized (_configLock) {
            locked_loadConfig(null);
            loadMetadata();
            migrateToMetadata();
        }
        if (!ctx.isRouterContext()) {
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(new TempDeleter(_util.getTempDir()), "SnarkDelTemp"));
        }
    }

    /**
     * Caller _must_ call loadConfig(file) before this if setting new values for i2cp host/port or
     * i2psnark.dir
     */
    public void start() {
        _running = true;
        _stopping = false;
        ClientAppManager cmgr = _context.clientAppManager();
        if ("i2psnark"
                .equals(
                        _contextName)) { // Register with the ClientAppManager so rpc plugin can
                                         // find us (only if default instance)
            if (cmgr != null) {
                cmgr.register(this);
            }
        } else { // Register link with NavHelper
            if (cmgr != null) {
                NavService nav = (NavService) cmgr.getRegisteredApp("NavHelper");
                if (nav != null) {
                    String name = DataHelper.stripHTML(_contextPath.substring(1));
                    nav.registerApp(
                            name, name, _contextPath, null, "/themes/console/images/i2psnark.png");
                }
            }
        }
        _monitor = new I2PAppThread(new DirMonitor(), "SnarkDirMon", true);
        _monitor.start();
        if (_context.isRouterContext()
                && "i2psnark".equals(_contextName)) { // only if default instance
            _context.simpleTimer2()
                    .addEvent(new Register(), 4 * (long) 60 * 1000);
        // Register self-schedules via setPool() in addEvent()
        }
        _idleChecker = new IdleChecker(this, _peerCoordinatorSet);
        _idleChecker.schedule(3 * (long) 60 * 1000);
        new DestCycle().schedule(DEST_CYCLE_INTERVAL + _context.random().nextInt(DEST_CYCLE_JITTER));
        if (!_context.isRouterContext()) {
            String lang = _config.getProperty(PROP_LANG);
            if (lang != null) {
                String country = _config.getProperty(PROP_COUNTRY, "");
                Translate.setLanguage(lang, country);
            }
        }
    }

    /**
     * Only used in app context
     *
     * @since 0.9.27
     */
    private static class TempDeleter implements Runnable {
        private final File file;

        public TempDeleter(File f) {
            file = f;
        }

        public void run() {
            FileUtil.rmdir(file, false);
        }
    }

    /**
     * @since 0.9.4
     */
    private class Register extends SimpleTimer2.TimedEvent {
        public void timeReached() {
            if (!_running) {
                return;
            }
            ClientAppManager cmgr = _context.clientAppManager();
            if (cmgr != null) {
                _umgr = (UpdateManager) cmgr.getRegisteredApp(UpdateManager.APP_NAME);
            }
            if (_umgr != null) {
                _uhandler = new UpdateHandler(_context, _umgr, SnarkManager.this);
                _umgr.register(_uhandler, UpdateType.ROUTER_SIGNED, UpdateMethod.TORRENT, 10);
                _umgr.register(_uhandler, UpdateType.ROUTER_SIGNED_SU3, UpdateMethod.TORRENT, 10);
                _log.info("Registered I2PSnark with Update Manager for Router updates");
            } else {
                _log.warn("No Update Manager found: cannot register I2PSnark for Router updates");
            }
        }
    }

    /**
     * DisconnectListener interface
     *
     * @since 0.9.53
     */
    @Override
    public void sessionDisconnected() {
        if (!_context.isRouterContext()) {
            _stopping = true;
        }
    }

    /**
     *  Called by the webapp at Jetty shutdown.
     *  Stops all torrents. Does not close the tunnel, so the announces have a chance.
     *  Fix this so an individual webapp stop will close the tunnel.
     *  Runs inline.
     */
    public void stop() {
        if (_umgr != null && _uhandler != null) {
            _umgr.unregister(_uhandler, UpdateType.ROUTER_SIGNED, UpdateMethod.TORRENT);
            _umgr.unregister(_uhandler, UpdateType.ROUTER_SIGNED_SU3, UpdateMethod.TORRENT);
        }
        _running = false;
        _stopping = true;
        _monitor.interrupt();
        _connectionAcceptor.halt();
        _idleChecker.cancel();
        stopAllTorrents(true);
        ClientAppManager cmgr = _context.clientAppManager();
        if ("i2psnark".equals(_contextName)) { // only if default instance
            if (cmgr != null) {
                cmgr.unregister(this);
            }
        } else {
            if (cmgr != null) {
                NavService nav = (NavService) cmgr.getRegisteredApp("NavHelper");
                if (nav != null) {
                    String name = DataHelper.stripHTML(_contextPath.substring(1));
                    nav.unregisterApp(name); // Unregister link with NavHelper
                }
            }
        }
    }

    /**
     * Whether the manager is in the process of stopping.
     *
     * @return whether stopping
     * @since 0.9.1
     */
    public boolean isStopping() {
        return _stopping;
    }

    /**
     * ClientApp method. Does nothing. Doesn't matter, we are only registering.
     *
     * @since 0.9.30
     */
    @Override
    public void startup() { /* no-op */ }

    /**
     * ClientApp method. Does nothing. Doesn't matter, we are only registering.
     *
     * @since 0.9.30
     */
    @Override
    public void shutdown(String[] args) { /* no-op */ }

    /**
     * ClientApp method. Doesn't matter, we are only registering.
     *
     * @return INITIALIZED always.
     * @since 0.9.30
     */
    @Override
    public ClientAppState getState() {
        return ClientAppState.INITIALIZED;
    }

    /**
     * ClientApp method.
     *
     * @return the name
     * @since 0.9.30
     */
    @Override
    public String getName() {
        return "i2psnark";
    }

    /**
     * ClientApp method.
     *
     * @return the display name
     * @since 0.9.30
     */
    @Override
    public String getDisplayName() {
        return "i2psnark: " + _contextPath;
    }

    /**
     * The I2PSnarkUtil instance for this manager.
     *
     * @return I2PSnarkUtil instance
     */
    public I2PSnarkUtil util() {
        return _util;
    }

    /**
     * Whether batched torrent starts are staggered with a random delay, so torrents
     * starting together cannot be correlated, and tunnel builds are spread out.
     *
     * @return true to stagger batched starts (default)
     * @since 0.9.71+
     */
    public boolean getRandomizeStartupDelay() {
        return _randomizeStartupDelay;
    }

    /**
     * Whether running torrents are periodically restarted with fresh
     * destinations.
     *
     * @return true to cycle destinations (default)
     * @since 0.9.71+
     */
    public boolean shouldDestCycle() {
        return Boolean.parseBoolean(_config.getProperty(PROP_DEST_CYCLE, "true"));
    }

    /**
     * The bandwidth listener for this manager.
     *
     * @return bandwidth listener
     */
    @Override
    public BandwidthListener getBandwidthListener() {
        return _bwManager;
    }

    /* @since 0.9.64+ */
    private long lastAddedMessageTimestamp;
    private String lastAddedMessage;

    /**
     *  Escape a message for HTML display, preserving intentional '&amp;nbsp;' spacers.
     *  Order matters: '&amp;' first so the later entity-escapes of '&lt;' and '&gt;'
     *  are not undone, and restore the literal '&nbsp;' entries afterwards.
     *
     *  @param message the raw message text, not null
     *  @return the message with HTML metacharacters escaped
     */
    public static String escapeMessage(String message) {
        return message.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("&amp;nbsp;", "&nbsp;");
    }

    /** Use if it does not include a link. Escapes '&lt;' and '&gt;' before queueing */
    public void addMessage(String message) {
        long currentTime = System.currentTimeMillis() / 1000;
        if (lastAddedMessageTimestamp != currentTime || !lastAddedMessage.equals(message)) {
            addMessageNoEscape(escapeMessage(message));
        }
        lastAddedMessageTimestamp = currentTime;
        lastAddedMessage = message;
        if (_log.shouldInfo()) {
            _log.info(message);
        }
    }

    /**
     * Use if it includes a link. Does not escape '&lt;' and '&gt;' before queueing
     *
     * @since 0.9.14.1
     */
    public void addMessageNoEscape(String message) {
        long currentTime = System.currentTimeMillis() / 1000;
        if (lastAddedMessageTimestamp != currentTime || !lastAddedMessage.equals(message)) {
            _messages.addMessageNoEscape(getTime() + "&nbsp; " + message);
        }
        lastAddedMessageTimestamp = currentTime;
        lastAddedMessage = message;
        if (_log.shouldInfo()) {
            _log.info(message);
        }
    }

    /**
     * Queue a message and, when running standalone, print it to stdout.
     *
     * @param message the message to queue
     * @since 0.9.71+
     */
    public void addMessageAndPrint(String message) {
        addMessage(message);
        printToConsole(message);
    }

    /**
     * Queue a message and, when running standalone, print possibly different text to stdout.
     *
     * @param message the message to queue
     * @param print the text to print, or null for no output
     * @since 0.9.71+
     */
    public void addMessageAndPrint(String message, String print) {
        addMessage(message);
        printToConsole(print);
    }

    /**
     * Queue a message and, when running standalone, print it to stdout.
     *
     * @param message the message to queue
     * @since 0.9.71+
     */
    public void addMessageNoEscapeAndPrint(String message) {
        addMessageNoEscape(message);
        printToConsole(message);
    }

    /**
     * Queue a message and, when running standalone, print possibly different text to stdout.
     *
     * @param message the message to queue
     * @param print the text to print, or null for no output
     * @since 0.9.71+
     */
    public void addMessageNoEscapeAndPrint(String message, String print) {
        addMessageNoEscape(message);
        printToConsole(print);
    }

    /**
     * Print a message to stdout when running standalone, replacing any HTML
     * nbsp entities so the console output is readable.
     *
     * @param msg the text to print, or null for no output
     * @since 0.9.71+
     */
    private void printToConsole(String msg) {
        if (!_context.isRouterContext()) {
            System.out.println(" • " + msg.replace("&nbsp;", " "));
        }
    }

    /**
     * Date format for recent messages.
     */
    private static final ThreadLocal<DateFormat> DATE_FORMAT = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            return fmt;
        }
    };

    public String getTime() {
        long now = System.currentTimeMillis();
        String date = DATE_FORMAT.get().format(new Date(now));
        return "<b class=date>" + date + "</b>";
    }

    /**
     * The list of recent UI messages for display on the web page.
     *
     * @return list of recent messages
     */
    public List<UIMessages.Message> getMessages() {
        return _messages.getMessages();
    }

    /**
     * @since 0.9
     */
    public void clearMessages() {
        _messages.clear();
    }

    /**
     * Clear through this id
     *
     * @since 0.9.33
     */
    public void clearMessages(int id) {
        _messages.clearThrough(id);
    }

    /**
     * Whether the files in the data directory are world-readable.
     *
     * @return default false
     * @since 0.8.9
     */
    public boolean areFilesPublic() {
        return Boolean.parseBoolean(_config.getProperty(PROP_FILES_PUBLIC));
    }

    /**
     * Whether new torrents preallocate their storage files.
     *
     * @return default true
     * @since 0.9.66+
     */
    public boolean shouldPreallocateFiles() {
        return Boolean.parseBoolean(
                _config.getProperty(PROP_PREALLOCATE_FILES, DEFAULT_PREALLOCATE_FILES));
    }

    /**
     * Whether newly added torrents should be started automatically.
     *
     * @return true if newly added torrents should be started automatically
     */
    @Override
    public boolean shouldAutoStart() {
        return Boolean.parseBoolean(
                _config.getProperty(PROP_AUTO_START, Boolean.toString(DEFAULT_AUTO_START)));
    }

    /**
     * Whether smart sort is enabled in the web interface.
     *
     * @return default false
     * @since 0.9.23
     */
    public boolean isSmartSortEnabled() {
        String val = _config.getProperty(PROP_SMART_SORT);
        if (val == null) {
            return false;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * Whether the web interface collapses panels by default.
     *
     * @return default true
     * @since 0.9.32
     */
    public boolean isCollapsePanelsEnabled() {
        String val = _config.getProperty(PROP_COLLAPSE_PANELS);
        if (val == null) {
            return I2PSnarkUtil.DEFAULT_COLLAPSE_PANELS;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * Whether the status filter bar is shown in the web interface.
     *
     * @return default false
     * @since 0.9.34
     */
    public boolean isShowStatusFilter() {
        String val = _config.getProperty(PROP_SHOW_STATUSFILTER);
        if (val == null) {
            return I2PSnarkUtil.DEFAULT_SHOW_STATUSFILTER;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * Whether the lightbox is used to display large images in the web interface.
     *
     * @return default true
     * @since 0.9.34
     */
    public boolean isEnableLightbox() {
        String val = _config.getProperty(PROP_ENABLE_LIGHTBOX);
        if (val == null) {
            return I2PSnarkUtil.DEFAULT_ENABLE_LIGHTBOX;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * Whether hops are randomly varied for inbound torrent tunnels.
     *
     * @return default false
     * @since 0.9.64+
     */
    public boolean isVaryInboundHops() {
        String val = _config.getProperty(PROP_VARY_INBOUND_HOPS);
        if (val == null) {
            return I2PSnarkUtil.DEFAULT_VARY_INBOUND_HOPS;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * Whether hops are randomly varied for outbound torrent tunnels.
     *
     * @return default false
     * @since 0.9.64+
     */
    public boolean isVaryOutboundHops() {
        String val = _config.getProperty(PROP_VARY_OUTBOUND_HOPS);
        if (val == null) {
            return I2PSnarkUtil.DEFAULT_VARY_OUTBOUND_HOPS;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * How often the web page refreshes itself.
     *
     * @return -1 for never
     * @since 0.8.9
     */
    public int getRefreshDelaySeconds() {
        return I2PSnarkUtil.parseInt(_config.getProperty(PROP_REFRESH_DELAY), DEFAULT_REFRESH_DELAY_SECS);
    }

    /**
     * Maximum number of files a torrent may contain.
     *
     * @return the max files per torrent
     * @since 0.9.46 (I2P+)
     */
    public int getMaxFilesPerTorrent() {
        return I2PSnarkUtil.parseInt(_config.getProperty(PROP_MAX_FILES_PER_TORRENT), DEFAULT_MAX_FILES_PER_TORRENT);
    }

    /**
     * Maximum number of messages kept for display on the web page.
     *
     * @return the max log messages
     * @since 0.9.61+ (I2P+)
     */
    public int getMaxLogMessages() {
        return I2PSnarkUtil.parseInt(_config.getProperty(PROP_MAX_MESSAGES), DEFAULT_MAX_MESSAGES);
    }

    /**
     * For GUI
     *
     * @return the page size
     * @since 0.9.6
     */
    public int getPageSize() {
        return I2PSnarkUtil.parseInt(_config.getProperty(PROP_PAGE_SIZE), DEFAULT_PAGE_SIZE);
    }

    /**
     * A random startup delay in minutes, uniformly between the configured
     * minimum and maximum, to avoid correlating auto-starts across routers.
     *
     * @return delay in minutes, 0 when not running inside the router
     */
    private int getStartupDelayMinutes() {
        if (!_context.isRouterContext()) {
            return 0;
        }
        int min = getInt(PROP_STARTUP_DELAY_MIN, DEFAULT_STARTUP_DELAY_MIN);
        int max = getInt(PROP_STARTUP_DELAY_MAX, DEFAULT_STARTUP_DELAY_MAX);
        if (max < min) {
            int tmp = max;
            max = min;
            min = tmp;
        }
        return min + _context.random().nextInt(max - min + 1);
    }

    /**
     * The configured torrent data directory.
     *
     * @return the configured torrent data directory
     */
    public File getDataDir() {
        String dir = _config.getProperty(PROP_DIR, _contextName);
        File f;
        if (areFilesPublic()) {
            f = new File(dir);
        } else {
            f = new SecureDirectory(dir);
        }
        if (!f.isAbsolute()) {
            if (areFilesPublic()) {
                f = new File(_context.getAppDir(), dir);
            } else {
                f = new SecureDirectory(_context.getAppDir(), dir);
            }
        }
        return f;
    }

    /**
     * Return the configured torrent file directory, or the data directory
     * if not set. Torrent metadata (.torrent) files are stored here when
     * configured separately from downloaded data.
     *
     * @return the directory for .torrent files
     * @since 0.9.71+
     */
    public File getTorrentDir() {
        String dir = _config.getProperty(PROP_TORRENT_DIR);
        if (dir == null || dir.trim().isEmpty()) {
            return getDataDir();
        }
        dir = dir.trim();
        File f = new File(dir);
        if (!f.isAbsolute()) {
            f = new File(_context.getAppDir(), dir);
        }
        return f;
    }

    /**
     * For RPC
     *
     * @return the config dir
     * @since 0.9.30
     */
    public File getConfigDir() {
        return _configDir;
    }

    /**
     * Migrate the old flat config file to the new config dir. Extracts per-torrent
     * zmeta entries into the metadata file and writes the remaining config.
     * Caller must synch.
     *
     * @return the new config directory, non-null
     * @throws RuntimeException on creation fail
     * @since 0.9.15
     */
    private File migrateConfig(File oldFile) {
        File dir = new SecureDirectory(oldFile + CONFIG_DIR_SUFFIX);
        if ((!dir.exists()) && (!dir.mkdirs())) {
            _log.error("Error creating I2PSnark config dir " + dir);
            throw new RuntimeException("Error creating I2PSnark config dir " + dir);
        }
        // move the DHT file as-is
        String oldName = oldFile.toString();
        if (oldName.endsWith(CONFIG_FILE_SUFFIX)) {
            String oldDHT = oldName.replace(CONFIG_FILE_SUFFIX, KRPC.DHT_FILE_SUFFIX);
            File oldDHTFile = new File(oldDHT);
            if (oldDHTFile.exists()) {
                File newDHTFile = new File(dir, "i2psnark" + KRPC.DHT_FILE_SUFFIX);
                FileUtil.rename(oldDHTFile, newDHTFile);
            }
        }
        if (!oldFile.exists()) {
            return dir;
        }
        Properties oldProps = new Properties();
        try {
            DataHelper.loadProps(oldProps, oldFile);
            // a good time to fix this ancient typo
            String auto = (String) oldProps.remove(PROP_OLD_AUTO_START);
            if (auto != null) {
                oldProps.setProperty(PROP_AUTO_START, auto);
            }
        } catch (IOException ioe) {
            _log.error("Error loading I2PSnark config " + oldFile, ioe);
            return dir;
        }
        // Gather the props for each torrent, removing them from config
        // old b64 of hash as key
        Map<String, Properties> configs = new HashMap<>(16);
        for (Iterator<Map.Entry<Object, Object>> iter = oldProps.entrySet().iterator();
                iter.hasNext(); ) {
            Map.Entry<Object, Object> e = iter.next();
            String k = (String) e.getKey();
            if (k.startsWith(PROP_META_PREFIX)) {
                iter.remove();
                String v = (String) e.getValue();
                try {
                    k = k.substring(PROP_META_PREFIX.length());
                    String h = k.substring(0, 28); // length of b64 of 160 bit infohash
                    k = k.substring(29); // skip '.'
                    Properties tprops = configs.get(h);
                    if (tprops == null) {
                        tprops = new OrderedProperties();
                        configs.put(h, tprops);
                    }
                    if (k.equals(PROP_META_BITFIELD)) {
                        // old config was timestamp,bitfield; split them
                        int comma = v.indexOf(',');
                        if (comma > 0 && v.length() > comma + 1) {
                            tprops.put(PROP_META_STAMP, v.substring(0, comma));
                            tprops.put(PROP_META_BITFIELD, v.substring(comma + 1));
                        } else {
                            tprops.put(PROP_META_STAMP, v);
                        } // timestamp only??
                    } else {
                        tprops.put(k, v);
                    }
                } catch (IndexOutOfBoundsException ioobe) {
                    continue;
                }
            }
        }
        // Now write torrent properties directly into metadata
        _metadata = new OrderedProperties();
        for (Map.Entry<String, Properties> e : configs.entrySet()) {
            String b64 = e.getKey();
            Properties props = e.getValue();
            if (props.isEmpty()) {
                continue;
            }
            b64 = b64.replace('$', '=');
            byte[] ih = Base64.decode(b64);
            if (ih == null || ih.length != 20) {
                continue;
            }
            mergeIntoMetadata(ih, props);
        }
        // Save metadata file
        _metadataFile = new File(dir, METADATA_FILE);
        try {
            DataHelper.storeProps(_metadata, _metadataFile);
        } catch (IOException ioe) {
            _log.error("Error storing metadata " + _metadataFile, ioe);
        }
        // now store in new location, minus the zmeta entries
        File newFile = new File(dir, CONFIG_FILE);
        Properties newProps = new OrderedProperties();
        newProps.putAll(oldProps);
        try {
            DataHelper.storeProps(newProps, newFile);
        } catch (IOException ioe) {
            _log.error("Error storing I2PSnark config " + newFile, ioe);
            return dir;
        }
        oldFile.delete();
        if (_log.shouldWarn()) {
            _log.warn("Legacy I2PSnark configuration file migrated from " + oldFile + " to " + dir);
        }
        return dir;
    }

    /** How long to cache a per-torrent config before reloading, covers manual file edits. */
    private static final long CONFIG_CACHE_TTL = 60 * 1000;

    /** Guarded by _configLock. */
    private final Map<SHA1Hash, ConfigCacheEntry> _configCache = new HashMap<>(8);

    /**
     * A cached per-torrent config.
     *
     * @since 0.9.68+
     */
    private static class ConfigCacheEntry {
        private final Properties props;
        private final long loaded;

        public ConfigCacheEntry(Properties props) {
            this.props = props;
            loaded = System.currentTimeMillis();
        }
    }

    /**
     * The config for a torrent
     *
     * @return non-null, possibly empty
     * @since 0.9.15
     */
    private Properties getConfig(Snark snark) {
        return getConfig(snark.getInfoHash());
    }

    /**
     * The config for a torrent
     *
     * @param ih 20-byte infohash
     * @return non-null, possibly empty
     * @since 0.9.15
     */
    private Properties getConfig(byte[] ih) {
        SHA1Hash hash = new SHA1Hash(ih);
        synchronized (_configLock) {
            ConfigCacheEntry ce = _configCache.get(hash);
            if (ce != null && ce.loaded + CONFIG_CACHE_TTL > System.currentTimeMillis()) {
                Properties rv = new OrderedProperties();
                rv.putAll(ce.props);
                return rv;
            }
            Properties rv = new OrderedProperties();
            String prefix = META_PREFIX + I2PSnarkUtil.toHex(ih) + ".";
            for (String key : _metadata.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    rv.setProperty(key.substring(prefix.length()), _metadata.getProperty(key));
                }
            }
            _configCache.put(hash, new ConfigCacheEntry(rv));
            return rv;
        }
    }

    /**
     * Build a metadata key for a torrent property.
     *
     * @since 0.9.71+
     */
    private static String metaKey(byte[] ih, String prop) {
        return META_PREFIX + I2PSnarkUtil.toHex(ih) + "." + prop;
    }

    /**
     * Load the metadata file into _metadata.
     * Called once at startup, under _configLock.
     *
     * @since 0.9.71+
     */
    private void loadMetadata() {
        _metadata = new OrderedProperties();
        if (_metadataFile.exists()) {
            try {
                DataHelper.loadProps(_metadata, _metadataFile);
            } catch (IOException ioe) {
                _log.error("Error loading metadata " + _metadataFile, ioe);
            }
            stripDeprecatedKeys();
        }
    }

    /**
     * Remove deprecated per-torrent keys from metadata.
     * preserveFileNames is now global; inOrder was removed entirely.
     *
     * @since 0.9.72
     */
    private void stripDeprecatedKeys() {
        List<Object> toRemove = null;
        for (Object key : _metadata.keySet()) {
            String s = (String) key;
            if (s.endsWith("." + DEPRECATED_PRESERVE_FILE_NAMES)
                || s.endsWith("." + DEPRECATED_IN_ORDER)) {
                if (toRemove == null) toRemove = new ArrayList<>(4);
                toRemove.add(key);
            }
        }
        if (toRemove != null) {
            for (Object key : toRemove) {
                _metadata.remove(key);
            }
        }
    }

    /**
     * Save the metadata file to disk.
     * Called under _configLock after every mutation.
     *
     * @since 0.9.71+
     */
    private void saveMetadata() {
        try {
            DataHelper.storeProps(_metadata, _metadataFile);
        } catch (IOException ioe) {
            _log.error("Error saving metadata " + _metadataFile, ioe);
        }
    }

    /**
     * Migrate per-torrent config files into the single metadata file.
     * One-time operation at startup: scans sX/ subdirs and flat/grouped
     * files, merges them into _metadata, then deletes the old files.
     *
     * @since 0.9.71+
     */
    private void migrateToMetadata() {
        if (_metadataFile.exists() && _metadata.size() > 0) {
            return;
        }
        int migrated = 0;
        // Scan legacy B64 subdirs
        for (int i = 0; i < B64.length(); i++) {
            File subdir = new File(_configDir, SUBDIR_PREFIX + B64.charAt(i));
            if (!subdir.isDirectory()) {
                continue;
            }
            File[] configs = subdir.listFiles();
            if (configs == null) {
                continue;
            }
            for (File f : configs) {
                if (!f.isFile() || !f.getName().endsWith(CONFIG_FILE_SUFFIX)) {
                    continue;
                }
                SHA1Hash ih = configFileToInfoHash(f);
                if (ih == null) {
                    continue;
                }
                Properties props = new Properties();
                try {
                    I2PSnarkUtil.loadProps(props, f);
                } catch (IOException ioe) {
                    continue;
                }
                mergeIntoMetadata(ih.getData(), props);
                migrated++;
                // Move comment file if present
                moveCommentFile(f, ih.getData());
                f.delete();
            }
            String[] remaining = subdir.list();
            if (remaining != null && remaining.length == 0) {
                subdir.delete();
            }
        }
        // Scan flat/grouped .config files directly in _configDir or subdirs
        scanForConfigFiles(_configDir, migrated);
        if (migrated > 0) {
            synchronized (_configLock) {
                saveMetadata();
            }
            _log.warn("Migrated " + migrated + " torrent config files to metadata");
        }
    }

    /**
     * Recursively scan for .config files and migrate them.
     * Skips the root config dir itself (only scans children).
     *
     * @since 0.9.71+
     */
    private int scanForConfigFiles(File dir, int migrated) {
        File[] children = dir.listFiles();
        if (children == null) {
            return migrated;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                migrated = scanForConfigFiles(f, migrated);
            } else if (f.isFile() && f.getName().endsWith(CONFIG_FILE_SUFFIX)
                       && !f.getName().equals(CONFIG_FILE)) {
                // Try to extract infohash from the infohash property
                Properties props = new Properties();
                try {
                    I2PSnarkUtil.loadProps(props, f);
                } catch (IOException ioe) {
                    continue;
                }
                String hex = props.getProperty("infohash");
                SHA1Hash ih = null;
                if (hex != null && hex.length() == 40) {
                    byte[] ihBytes = new byte[20];
                    try {
                        for (int j = 0; j < 20; j++) {
                            ihBytes[j] = (byte) (Integer.parseInt(hex.substring(j * 2, (j * 2) + 2), 16) & 0xff);
                        }
                        ih = new SHA1Hash(ihBytes);
                    } catch (NumberFormatException nfe) {
                        // fall through
                    }
                }
                if (ih == null) {
                    ih = configFileToInfoHash(f);
                }
                if (ih != null && !_metadata.containsKey(metaKey(ih.getData(), PROP_META_RUNNING))) {
                    mergeIntoMetadata(ih.getData(), props);
                    migrated++;
                    moveCommentFile(f, ih.getData());
                }
                f.delete();
            }
        }
        return migrated;
    }

    /**
     * Merge per-torrent properties into _metadata.
     *
     * @since 0.9.71+
     */
    private void mergeIntoMetadata(byte[] ih, Properties props) {
        String prefix = META_PREFIX + I2PSnarkUtil.toHex(ih) + ".";
        for (String key : props.stringPropertyNames()) {
            // Don't store internal migration keys
            if (key.equals("infohash")) {
                continue;
            }
            // Skip deprecated per-torrent keys (preserveFileNames is global, inOrder removed)
            if (key.equals(DEPRECATED_PRESERVE_FILE_NAMES) || key.equals(DEPRECATED_IN_ORDER)) {
                continue;
            }
            _metadata.setProperty(prefix + key, props.getProperty(key));
        }
    }

    /**
     * Move a comment file from legacy/subdir location to config dir root.
     *
     * @since 0.9.71+
     */
    private void moveCommentFile(File configConf, byte[] ih) {
        String baseName = configConf.getName();
        if (!baseName.endsWith(CONFIG_FILE_SUFFIX)) {
            return;
        }
        String commentName = baseName.substring(0,
                baseName.length() - CONFIG_FILE_SUFFIX.length())
                + COMMENT_FILE_SUFFIX;
        File oldComment = new File(configConf.getParentFile(), commentName);
        if (oldComment.exists()) {
            File newComment = new File(_configDir, commentName);
            FileUtil.rename(oldComment, newComment);
        }
    }

    /**
     * The comment file for a torrent, stored alongside the config.
     *
     * @param ih 20-byte infohash
     * @since 0.9.31
     */
    private File commentFile(byte[] ih) {
        String hex = I2PSnarkUtil.toHex(ih);
        return new File(_configDir, hex + COMMENT_FILE_SUFFIX);
    }

    /**
     * The comments for a torrent
     *
     * @return null if none
     * @since 0.9.31
     */
    @Override
    public CommentSet getSavedComments(Snark snark) {
        File com = commentFile(snark.getInfoHash());
        if (com.exists()) {
            try {
                return new CommentSet(com);
            } catch (IOException ioe) {
                if (_log.shouldWarn()) {
                    _log.warn("Comment load error", ioe);
                }
            }
        }
        return null;
    }

    /**
     * Save the comments for a torrent. Caller must synchronize on comments.
     *
     * @param comments non-null
     * @since 0.9.31
     */
    @Override
    public void locked_saveComments(Snark snark, CommentSet comments) {
        File com = commentFile(snark.getInfoHash());
        try {
            comments.save(com);
        } catch (IOException ioe) {
            if (_log.shouldWarn()) {
                _log.warn("Comment save error -> " + ioe.getMessage());
            }
        }
    }

    /**
     * Extract the info hash from a config file name
     *
     * @return null for invalid name
     * @since 0.9.20
     */
    private static SHA1Hash configFileToInfoHash(File file) {
        String name = file.getName();
        if (name.length() != 40 + CONFIG_FILE_SUFFIX.length()
                || !name.endsWith(CONFIG_FILE_SUFFIX)) {
            return null;
        }
        String hex = name.substring(0, 40);
        byte[] ih = new byte[20];
        try {
            for (int i = 0; i < 20; i++) {
                ih[i] = (byte) (Integer.parseInt(hex.substring(i * 2, (i * 2) + 2), 16) & 0xff);
            }
        } catch (NumberFormatException nfe) {
            return null;
        }
        return new SHA1Hash(ih);
    }

    /**
     * Load the config from the given file.
     *
     * @param filename null to set initial defaults
     */
    public void loadConfig(String filename) {
        synchronized (_configLock) {
            locked_loadConfig(filename);
        }
    }

    /** Null to set initial defaults. */
    private void locked_loadConfig(String filename) {
        if (_config == null) {
            _config = new OrderedProperties();
        }
        if (filename != null) {
            File cfg = new File(filename);
            if (!cfg.isAbsolute()) {
                cfg = new File(_context.getConfigDir(), filename);
            }
            _configDir = migrateConfig(cfg);
            _configFile = new File(_configDir, CONFIG_FILE);
            if (_configFile.exists()) {
                try {
                    DataHelper.loadProps(_config, _configFile);
                } catch (IOException ioe) {
                    _log.error("Error loading I2PSnark config " + _configFile, ioe);
                }
            }
        }
        // now add sane defaults
        if (!_config.containsKey(PROP_I2CP_HOST)) {
            _config.setProperty(PROP_I2CP_HOST, "127.0.0.1");
        }
        if (!_config.containsKey(PROP_I2CP_PORT)) {
            _config.setProperty(PROP_I2CP_PORT, Integer.toString(I2PClient.DEFAULT_LISTEN_PORT));
        }
        if (!_config.containsKey(PROP_I2CP_OPTS)) {
            _config.setProperty(
                    PROP_I2CP_OPTS,
                    "inbound.length=3 outbound.length=3"
                            + " inbound.quantity="
                            + DEFAULT_TUNNEL_QUANTITY
                            + " outbound.quantity="
                            + DEFAULT_TUNNEL_QUANTITY);
        }
        if (!_config.containsKey(PROP_UPLOADERS_TOTAL)) {
            _config.setProperty(PROP_UPLOADERS_TOTAL, "" + Snark.MAX_TOTAL_UPLOADERS);
        }
        if (!_config.containsKey(PROP_DIR)) {
            _config.setProperty(PROP_DIR, _contextName);
        }
        if (!_config.containsKey(PROP_AUTO_START)) {
            _config.setProperty(PROP_AUTO_START, Boolean.toString(DEFAULT_AUTO_START));
        }
        if (!_config.containsKey(PROP_REFRESH_DELAY)) {
            _config.setProperty(PROP_REFRESH_DELAY, Integer.toString(DEFAULT_REFRESH_DELAY_SECS));
        }
        if (!_config.containsKey(PROP_STARTUP_DELAY_MIN)) {
            if (_config.containsKey(PROP_STARTUP_DELAY)) {
                try {
                    // migrate the old single value to a fixed range
                    int old = Integer.parseInt(_config.getProperty(PROP_STARTUP_DELAY));
                    _config.setProperty(PROP_STARTUP_DELAY_MIN, Integer.toString(old));
                    _config.setProperty(PROP_STARTUP_DELAY_MAX, Integer.toString(old));
                    _config.remove(PROP_STARTUP_DELAY);
                } catch (NumberFormatException nfe) { /* fall through to defaults */ }
            }
            if (!_config.containsKey(PROP_STARTUP_DELAY_MIN)) {
                _config.setProperty(PROP_STARTUP_DELAY_MIN, Integer.toString(DEFAULT_STARTUP_DELAY_MIN));
            }
            if (!_config.containsKey(PROP_STARTUP_DELAY_MAX)) {
                _config.setProperty(PROP_STARTUP_DELAY_MAX, Integer.toString(DEFAULT_STARTUP_DELAY_MAX));
            }
        }
        if (!_config.containsKey(PROP_PAGE_SIZE)) {
            _config.setProperty(PROP_PAGE_SIZE, Integer.toString(DEFAULT_PAGE_SIZE));
        }
        if (!_config.containsKey(PROP_THEME)) {
            _config.setProperty(PROP_THEME, DEFAULT_THEME);
        }
        if (!_config.containsKey(PROP_RATINGS)) {
            _config.setProperty(PROP_RATINGS, "true");
        }
        if (!_config.containsKey(PROP_COMMENTS)) {
            _config.setProperty(PROP_COMMENTS, "true");
        }
        if (!_config.containsKey(PROP_COMMENTS_NAME)) {
            _config.setProperty(PROP_COMMENTS_NAME, "");
        }
        if (!_config.containsKey(PROP_COLLAPSE_PANELS)) {
            _config.setProperty(
                    PROP_COLLAPSE_PANELS, Boolean.toString(I2PSnarkUtil.DEFAULT_COLLAPSE_PANELS));
        }
        if (!_config.containsKey(PROP_SHOW_STATUSFILTER)) {
            _config.setProperty(PROP_SHOW_STATUSFILTER, "false");
        }
        if (!_config.containsKey(PROP_ENABLE_LIGHTBOX)) {
            _config.setProperty(PROP_ENABLE_LIGHTBOX, "true");
        }
        if (!_config.containsKey(PROP_UPBW_MAX)) {
            _config.setProperty(PROP_UPBW_MAX, Integer.toString(DEFAULT_MAX_UP_BW));
        }
        if (!_config.containsKey(PROP_DOWNBW_MAX)) {
            _config.setProperty(PROP_DOWNBW_MAX, Integer.toString(DEFAULT_MAX_DOWN_BW));
        }
        if (!_config.containsKey(PROP_BAN_DISCARD_RATIO)) {
            _config.setProperty(PROP_BAN_DISCARD_RATIO, DEFAULT_BAN_DISCARD_RATIO);
        }
        if (!_config.containsKey(PROP_BAN_DISCARD_PERIOD)) {
            // One-time migration: configs saved before the discard-ratio ban upgrade
            // persist the old default (false); re-apply the new default.
            _config.setProperty(PROP_BAN_DISCARD_RATIO, DEFAULT_BAN_DISCARD_RATIO);
            _config.setProperty(
                    PROP_BAN_DISCARD_PERIOD, Integer.toString(DEFAULT_BAN_DISCARD_PERIOD));
        }
        // Pipeline tunables. Apply before any PeerState exists so standalone config is honored.
        int minPipe = getInt(PROP_MIN_PIPELINE, PeerState.MIN_PIPELINE);
        int maxPipe = getInt(PROP_MAX_PIPELINE, PeerState.MAX_PIPELINE);
        int chunkSize = getInt(PROP_PARTSIZE, PeerState.PARTSIZE);
        int maxChunk = getInt(PROP_MAX_PARTSIZE, PeerState.MAX_PARTSIZE);
        PeerState.setPipelineParams(minPipe, maxPipe, chunkSize, maxChunk);
        updateConfig();
        // Initialize bandwidth from config (not from I2CP detection)
        int maxdown = getInt(PROP_DOWNBW_MAX, DEFAULT_MAX_DOWN_BW);
        _bwManager.setDownBWLimit(maxdown * 1024L);
        int maxup = getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW);
        _bwManager.setUpBWLimit(maxup * 1024L);
        _util.setBanDiscardRatio(
                Boolean.parseBoolean(_config.getProperty(PROP_BAN_DISCARD_RATIO)));
        _util.setBanDiscardPeriod(
                getInt(PROP_BAN_DISCARD_PERIOD, DEFAULT_BAN_DISCARD_PERIOD) * (long) 60 * 1000);
    }

    /**
     * Whether themes are applied across all applications.
     *
     * @return the universal theming
     * @since 0.9.31
     */
    public boolean getUniversalTheming() {
        return _context.getBooleanProperty(RC_PROP_UNIVERSAL_THEMING);
    }

    /**
     * The current theme for this web interface.
     *
     * @return String -- the current theme
     */
    public String getTheme() {
        String theme;
        if (getUniversalTheming()) {
            // Fetch console theme option (or use our default if it doesn't exist)
            theme = _context.getProperty(RC_PROP_THEME, DEFAULT_THEME);
            String[] themes = getThemes();
            boolean themeExists = false;
            for (int i = 0; i < themes.length; i++) { // Ensure that theme exists
                if (themes[i].equals(theme)) {
                    themeExists = true;
                    break;
                }
            }
            if (!themeExists) {
                // Since the default is not "light", explicitly check if universal theme is
                // "classic"
                if (theme.equals("classic")) {
                    theme = "light";
                } else {
                    theme = DEFAULT_THEME;
                }
                _config.setProperty(PROP_THEME, DEFAULT_THEME);
            }
        } else {
            theme = _config.getProperty(PROP_THEME, DEFAULT_THEME);
        }
        return theme;
    }

    /**
     * All themes available for this web interface.
     *
     * @return String[] -- Array of all the themes found, non-null, unsorted
     */
    public String[] getThemes() {
        String[] themes;
        if (_context.isRouterContext()) {
            File dir = new File(_context.getBaseDir(), "docs/themes/snark");
            FileFilter fileFilter =
                    new FileFilter() {
                        public boolean accept(File file) {
                            return file.isDirectory();
                        }
                    };
            File[] dirnames = dir.listFiles(fileFilter);
            if (dirnames != null) {
                List<String> th = new ArrayList<>(dirnames.length);
                for (int i = 0; i < dirnames.length; i++) {
                    String name = dirnames[i].getName();
                    if (name.equals("images")) {
                        continue;
                    }
                    th.add(name);
                }
                themes = th.toArray(new String[th.size()]);
            } else {
                themes = new String[0];
            }
        } else {
            themes =
                    new String[] {
                        "classic", "dark", "light", "midnight", "ubergine", "vanilla", "zilvero"
                    };
        }
        return themes;
    }

    /**
     * Call from DirMonitor since loadConfig() is called before router I2CP is up. We also use this
     * as a test that the router is there for standalone.
     *
     * @return true if we got a response from the router
     */
    private boolean getBWLimit() {
        int[] limits = BWLimits.getBWLimits(_util.getI2CPHost(), _util.getI2CPPort());
        if (limits == null) {
            return false;
        }
        // Bandwidth limits are not updated from I2CP detected values
        // Only user-configured values are used
        return true;
    }

    private void updateConfig() {
        String i2cpHost = _config.getProperty(PROP_I2CP_HOST);
        int i2cpPort = getInt(PROP_I2CP_PORT, I2PClient.DEFAULT_LISTEN_PORT);
        String opts = _config.getProperty(PROP_I2CP_OPTS);
        Map<String, String> i2cpOpts = new HashMap<>();
        if (opts != null) {
            StringTokenizer tok = new StringTokenizer(opts, " ");
            while (tok.hasMoreTokens()) {
                String pair = tok.nextToken();
                int split = pair.indexOf('=');
                if (split > 0) {
                    i2cpOpts.put(pair.substring(0, split), pair.substring(split + 1));
                }
            }
        }
        _util.setI2CPConfig(i2cpHost, i2cpPort, i2cpOpts);
        String msg = _t("Configuring I2PSnark with I2CP options") + ": " + i2cpOpts;
        if (_log.shouldInfo()) {
            _log.info(msg);
        }

        _util.setMaxUploaders(getInt(PROP_UPLOADERS_TOTAL, Snark.MAX_TOTAL_UPLOADERS));
        _util.setMaxUpBW(getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW));
        _util.setMaxFilesPerTorrent(
                getInt(PROP_MAX_FILES_PER_TORRENT, DEFAULT_MAX_FILES_PER_TORRENT));
        _util.setShouldPadFiles(parseShouldPadFiles(_config));
        int startDelayMin = getInt(PROP_STARTUP_DELAY_MIN, DEFAULT_STARTUP_DELAY_MIN);
        int startDelayMax = getInt(PROP_STARTUP_DELAY_MAX, DEFAULT_STARTUP_DELAY_MAX);
        if (startDelayMax < startDelayMin) {
            int tmp = startDelayMax;
            startDelayMax = startDelayMin;
            startDelayMin = tmp;
        }
        _util.setStartupDelayMin(startDelayMin);
        _util.setStartupDelayMax(startDelayMax);
        _util.setFilesPublic(areFilesPublic());
        _util.setPreallocateFiles(shouldPreallocateFiles());
        _util.setPreserveFileNames(Boolean.parseBoolean(_config.getProperty(PROP_PRESERVE_FILE_NAMES, "false")));
        _util.setOpenTrackers(getListConfig(PROP_OPENTRACKERS, DEFAULT_OPENTRACKERS));
        String useOT = _config.getProperty(PROP_USE_OPENTRACKERS);
        boolean bOT = useOT == null || Boolean.parseBoolean(useOT);
        _util.setUseOpenTrackers(bOT);
        // careful, so we can switch default to true later
        _util.setUseDHT(
                Boolean.parseBoolean(
                        _config.getProperty(
                                PROP_USE_DHT, Boolean.toString(I2PSnarkUtil.DEFAULT_USE_DHT))));
        _util.setRatingsEnabled(Boolean.parseBoolean(_config.getProperty(PROP_RATINGS, "true")));
        _util.setCommentsEnabled(Boolean.parseBoolean(_config.getProperty(PROP_COMMENTS, "true")));
        _util.setCommentsName(_config.getProperty(PROP_COMMENTS_NAME, ""));
        _util.setCollapsePanels(
                Boolean.parseBoolean(
                        _config.getProperty(
                                PROP_COLLAPSE_PANELS,
                                Boolean.toString(I2PSnarkUtil.DEFAULT_COLLAPSE_PANELS))));
        _util.setShowStatusFilter(
                Boolean.parseBoolean(
                        _config.getProperty(
                                PROP_SHOW_STATUSFILTER,
                                Boolean.toString(I2PSnarkUtil.DEFAULT_SHOW_STATUSFILTER))));
        _util.setEnableLightbox(
                Boolean.parseBoolean(
                        _config.getProperty(
                                PROP_ENABLE_LIGHTBOX,
                                Boolean.toString(I2PSnarkUtil.DEFAULT_ENABLE_LIGHTBOX))));
        _util.setEnableLightbox(
                Boolean.parseBoolean(
                        _config.getProperty(
                                PROP_ENABLE_LIGHTBOX,
                                Boolean.toString(I2PSnarkUtil.DEFAULT_ENABLE_LIGHTBOX))));
        _util.setVaryInboundHops(
                Boolean.parseBoolean(
                        _config.getProperty(
                                PROP_VARY_INBOUND_HOPS,
                                Boolean.toString(I2PSnarkUtil.DEFAULT_VARY_INBOUND_HOPS))));
        _util.setVaryOutboundHops(
                Boolean.parseBoolean(
                        _config.getProperty(
                                PROP_VARY_OUTBOUND_HOPS,
                                Boolean.toString(I2PSnarkUtil.DEFAULT_VARY_OUTBOUND_HOPS))));
        _util.setMultiDest(Boolean.parseBoolean(_config.getProperty(PROP_MULTI_DEST, "false")));
        _randomizeStartupDelay = Boolean.parseBoolean(_config.getProperty(PROP_RANDOMIZE_STARTUP, "true"));
        _util.setMaxDest(parseMaxDest(_config.getProperty(PROP_MULTI_DEST_MAX, Integer.toString(DEFAULT_MULTI_DEST_MAX))));
        _util.setClientId(_config.getProperty(PROP_CLIENT_ID, ""));
        _util.setClientIdCandidates(ClientID.parseCandidateList(_config.getProperty(PROP_CLIENT_IDS, "")));

        for (String c : _config.stringPropertyNames()) {
            if (c.startsWith(PROP_API_PREFIX)) {
                String tgt = c.substring(PROP_API_PREFIX.length());
                String key = _config.getProperty(c);
                // we only support one for now
                _util.setAPI(tgt, key);
                break;
            }
        }

        _browserApiEnabled = Boolean.parseBoolean(_config.getProperty(PROP_BROWSER_API, "false"));
        _browserApiHosts = _config.getProperty(PROP_BROWSER_API_HOSTS, "");
        _browserApiHostSet = resolveBrowserApiHosts(_browserApiHosts);

        File dd = getDataDir();

        if (dd.isDirectory()) {
            if (!dd.canWrite()) {
                msg = _t("No write permissions for data directory") + ": " + dd;
                addMessageAndPrint(msg);
            }
        } else {
            if (!dd.mkdirs()) {
                msg = _t("Data directory cannot be created") + ": " + dd;
                addMessageAndPrint(msg);
            }
        }

        String tempDir = validateTempDir(getTempDirProp());
        // Apply to new storages via the util, like the other config options
        _util.setTempDirProp(tempDir);
        initTrackerMap();
        initTorrentCreateFilterMap();
    }

    /**
     * Re-reads the entire config file from disk and activates any settings
     * that differ from the applied ones, so edits to i2psnark.config take
     * effect without a restart. Called by the directory monitor on its
     * periodic scan. Changed values are re-applied through the same
     * updateConfig() path used at startup; settings removed from the file
     * keep their current value.
     *
     * @since 0.9.71+
     */
    private void rereadConfig() {
        if (_configFile == null || !_configFile.exists()) {
            return;
        }
        Properties props = new OrderedProperties();
        try {
            DataHelper.loadProps(props, _configFile);
        } catch (IOException ioe) {
            _log.error("Error re-reading I2PSnark config " + _configFile, ioe);
            return;
        }
        synchronized (_configLock) {
            boolean differs = false;
            for (String key : props.stringPropertyNames()) {
                if (!props.getProperty(key).equals(_config.getProperty(key))) {
                    differs = true;
                    break;
                }
            }
            if (!differs) {
                return;
            }
            _config.putAll(props);
            updateConfig();
            PeerState.setPipelineParams(
                    getInt(PROP_MIN_PIPELINE, PeerState.MIN_PIPELINE),
                    getInt(PROP_MAX_PIPELINE, PeerState.MAX_PIPELINE),
                    getInt(PROP_PARTSIZE, PeerState.PARTSIZE),
                    getInt(PROP_MAX_PARTSIZE, PeerState.MAX_PARTSIZE));
        }
    }

    /**
     * Parses the {@link #PROP_SHOULD_PAD_FILES} value from the given
     * properties, applying the default when absent or unparsable.
     *
     * @param props the config properties
     * @return whether BEP 47 padding files are added to new torrents
     * @since 0.9.71+
     */
    static boolean parseShouldPadFiles(Properties props) {
        return Boolean.parseBoolean(
                props.getProperty(PROP_SHOULD_PAD_FILES, DEFAULT_SHOULD_PAD_FILES));
    }

    /**
     * Reads the {@link #PROP_TEMP_DIR} property, trimming whitespace and
     * stripping surrounding double quotes (so the value may contain spaces).
     * An unset, blank, or quoted-empty value disables the staging feature.
     *
     * @return the staging directory, or null if disabled
     * @since 0.9.71+
     */
    private String getTempDirProp() {
        return cleanTempDirProp(_config.getProperty(PROP_TEMP_DIR));
    }

    /**
     * Trims whitespace and strips surrounding double quotes (so the value may
     * contain spaces). An unset, blank, or quoted-empty value disables the
     * staging feature.
     *
     * @return the staging directory, or null if disabled
     * @since 0.9.71+
     */
    private static String cleanTempDirProp(String s) {
        if (s != null) {
            s = s.trim();
            while (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
                s = s.substring(1, s.length() - 1).trim();
            }
            if (s.isEmpty()) {
                s = null;
            }
        }
        return s;
    }

    /**
     * Validates the staging directory: creates it if missing, checks write
     * permission, and warns (returning null) when it is unusable.
     *
     * @param tempDir the raw configured value, or null
     * @return the cleaned usable staging directory, or null if disabled or unusable
     * @since 0.9.71+
     */
    private String validateTempDir(String tempDir) {
        String cleaned = cleanTempDirProp(tempDir);
        if (cleaned != null) {
            File td = new File(cleaned);
            if (td.isDirectory()) {
                if (!td.canWrite()) {
                    String msg = _t("No write permissions for temp directory") + ": " + td;
                    addMessageAndPrint(msg);
                    cleaned = null;
                }
            } else if (!td.mkdirs()) {
                String msg = _t("Temp directory cannot be created") + ": " + td;
                addMessageAndPrint(msg);
                cleaned = null;
            }
        }
        return cleaned;
    }

    private String validateTorrentDir(String torrentDir) {
        if (torrentDir == null || torrentDir.trim().isEmpty()) {
            return null;
        }
        String cleaned = torrentDir.trim();
        File td = new File(cleaned);
        if (td.isDirectory()) {
            if (!td.canWrite()) {
                String msg = _t("No write permissions for torrent directory") + ": " + td;
                addMessageAndPrint(msg);
                return null;
            }
        } else if (!td.mkdirs()) {
            String msg = _t("Torrent directory cannot be created") + ": " + td;
            addMessageAndPrint(msg);
            return null;
        }
        return cleaned;
    }

    private int getInt(String prop, int defaultVal) {
        String p = _config.getProperty(prop);
        if (p != null) {
            return I2PSnarkUtil.parseInt(p.trim(), defaultVal);
        }
        return defaultVal;
    }

    /**
     * Parse the destination cap, clamping invalid and out-of-range values to zero, which
     * restores one destination per torrent.
     *
     * @param s the raw property value, or null
     * @return a value in [0, MAX_MULTI_DEST]
     */
    private static int parseMaxDest(String s) {
        int rv = 0;
        if (s != null) {
            rv = I2PSnarkUtil.parseInt(s.trim(), 0);
        }
        if (rv < 0 || rv > MAX_MULTI_DEST) {
            rv = 0;
        }
        return rv;
    }

    /** All params may be null or need trimming. */
    public void updateConfig(
            String dataDir,
            boolean filesPublic,
            boolean autoStart,
            String refreshDelay,
            String startDelayMin,
            String startDelayMax,
            String pageSize,
            String i2cpHost,
            String i2cpPort,
            String i2cpOpts,
            String upLimit,
            String upBW,
            String downBW,
            boolean useOpenTrackers,
            boolean useDHT,
            String theme,
            String lang,
            boolean enableRatings,
            boolean enableComments,
            String commentName,
            boolean collapsePanels,
            boolean showStatusFilter,
            boolean enableLightbox,
            boolean enableAddCreate,
            boolean enableVaryInboundHops,
            boolean enableVaryOutboundHops,
            boolean multiDest,
            String multiDestMax,
            boolean randomizeStartup,
            String apiTarget,
            String apiKey,
            String maxFiles,
            boolean preallocateFiles,
            String tempDir,
            String torrentDir,
            boolean preserveFileNames) {
        synchronized (_configLock) {
            locked_updateConfig(
                    dataDir,
                    filesPublic,
                    autoStart,
                    refreshDelay,
                    startDelayMin,
                    startDelayMax,
                    pageSize,
                    i2cpHost,
                    i2cpPort,
                    i2cpOpts,
                    upLimit,
                    upBW,
                    downBW,
                    useOpenTrackers,
                    useDHT,
                    theme,
                    lang,
                    enableRatings,
                    enableComments,
                    commentName,
                    collapsePanels,
                    showStatusFilter,
                    enableLightbox,
                    enableAddCreate,
                    enableVaryInboundHops,
                    enableVaryOutboundHops,
                    multiDest,
                    multiDestMax,
                    randomizeStartup,
                    apiTarget,
                    apiKey,
                    maxFiles,
                    preallocateFiles,
                    tempDir,
                    torrentDir,
                    preserveFileNames);
        }
    }

    private void locked_updateConfig(
            String dataDir,
            boolean filesPublic,
            boolean autoStart,
            String refreshDelay,
            String startDelayMin,
            String startDelayMax,
            String pageSize,
            String i2cpHost,
            String i2cpPort,
            String i2cpOpts,
            String upLimit,
            String upBW,
            String downBW,
            boolean useOpenTrackers,
            boolean useDHT,
            String theme,
            String lang,
            boolean enableRatings,
            boolean enableComments,
            String commentName,
            boolean collapsePanels,
            boolean showStatusFilter,
            boolean enableLightbox,
            boolean enableAddCreate,
            boolean enableVaryInboundHops,
            boolean enableVaryOutboundHops,
            boolean multiDest,
            String multiDestMax,
            boolean randomizeStartup,
            String apiTarget,
            String apiKey,
            String maxFiles,
            boolean preallocateFiles,
            String tempDir,
            String torrentDir,
            boolean preserveFileNames) {
        boolean changed = false;
        boolean interruptMonitor = false;

        if (upLimit != null) {
            int limit = I2PSnarkUtil.parseInt(upLimit.trim(), _util.getMaxUploaders());
            if (limit != _util.getMaxUploaders()) {
                if (limit >= Snark.MIN_TOTAL_UPLOADERS) {
                    _util.setMaxUploaders(limit);
                    changed = true;
                    _config.setProperty(PROP_UPLOADERS_TOTAL, Integer.toString(limit));
                    String msg = _t("Total uploaders limit changed to {0}", limit);
                    addMessageAndPrint(msg);
                } else {
                    String msg = _t("Minimum uploaders limit is {0}", Snark.MIN_TOTAL_UPLOADERS);
                    addMessageAndPrint(msg);
                }
            }
        }
        if (upBW != null) {
            int limit = I2PSnarkUtil.parseInt(upBW.trim(), _util.getMaxUpBW());
            if (limit != _util.getMaxUpBW()) {
                if (limit >= MIN_UP_BW) {
                    _util.setMaxUpBW(limit);
                    _bwManager.setUpBWLimit(limit * 1024L);
                    changed = true;
                    _config.setProperty(PROP_UPBW_MAX, Integer.toString(limit));
                    String msg = _t("Up BW limit changed to {0}KBps", limit);
                    addMessageAndPrint(msg);
                } else {
                    String msg = _t("Minimum up bandwidth limit is {0}KBps", MIN_UP_BW);
                    addMessageAndPrint(msg);
                }
            }
        }
        if (downBW != null) {
            int limit = I2PSnarkUtil.parseInt(downBW.trim(), (int) (_bwManager.getDownBWLimit() / 1024));
            if (limit != _bwManager.getDownBWLimit() / 1024) {
                if (limit >= MIN_DOWN_BW) {
                    _bwManager.setDownBWLimit(limit * 1024L);
                    _config.setProperty(PROP_DOWNBW_MAX, Integer.toString(limit));
                    changed = true;
                    String msg = _t("Maximum download speed changed to {0}KB/s", limit);
                    addMessageAndPrint(msg);
                } else {
                    String msg = _t("Download speed limit is {0}KB/s", MIN_DOWN_BW);
                    addMessageAndPrint(msg);
                }
            }
        }

        String restart =
                _t(
                        "Note: You may need to stop and restart your torrents or restart I2PSnark"
                            + " in order to effect this change.");

        if (_util.enableVaryInboundHops() != enableVaryInboundHops) {
            _config.setProperty(PROP_VARY_INBOUND_HOPS, Boolean.toString(enableVaryInboundHops));
            if (enableVaryInboundHops) {
                addMessage(_t("Enabled +0/1 tunnel hop randomization on Inbound tunnels"));
            } else {
                addMessage(_t("Disabled tunnel hop randomization on Inbound tunnels"));
            }
            addMessage(restart);
            _util.setEnableVaryInboundHops(enableVaryInboundHops);
            changed = true;
        }

        if (_util.enableVaryOutboundHops() != enableVaryOutboundHops) {
            _config.setProperty(PROP_VARY_OUTBOUND_HOPS, Boolean.toString(enableVaryOutboundHops));
            if (enableVaryOutboundHops) {
                addMessage(_t("Enabled +0/1 tunnel hop randomization on Outbound tunnels"));
            } else {
                addMessage(_t("Disabled tunnel hop randomization on Outbound tunnels"));
            }
            addMessage(restart);
            _util.setEnableVaryOutboundHops(enableVaryOutboundHops);
            changed = true;
        }

        if (_util.getMultiDest() != multiDest) {
            _config.setProperty(PROP_MULTI_DEST, Boolean.toString(multiDest));
            if (multiDest) {
                addMessage(_t("Enabled a separate destination for each torrent"));
            } else {
                addMessage(_t("Disabled a separate destination for each torrent"));
            }
            addMessage(restart);
            _util.setMultiDest(multiDest);
            changed = true;
        }

        if (multiDestMax != null) {
            int maxDest = parseMaxDest(multiDestMax);
            if (maxDest != _util.getMaxDest()) {
                _util.setMaxDest(maxDest);
                changed = true;
                _config.setProperty(PROP_MULTI_DEST_MAX, Integer.toString(maxDest));
                if (maxDest > 0) {
                    addMessage(_t("Maximum destinations changed to {0}", maxDest));
                } else {
                    addMessage(_t("Unlimited destinations restored (one per torrent)"));
                }
                addMessage(restart);
            }
        }

        if (_randomizeStartupDelay != randomizeStartup) {
            _config.setProperty(PROP_RANDOMIZE_STARTUP, Boolean.toString(randomizeStartup));
            if (randomizeStartup) {
                addMessage(_t("Enabled randomizing torrent start times"));
            } else {
                addMessage(_t("Disabled randomizing torrent start times"));
            }
            addMessage(restart);
            _randomizeStartupDelay = randomizeStartup;
            changed = true;
        }

        if (startDelayMin != null && _context.isRouterContext()) {
            int min = I2PSnarkUtil.parseInt(startDelayMin.trim(), _util.getStartupDelayMin());
            int max = _util.getStartupDelayMax();
            if (startDelayMax != null) {
                max = I2PSnarkUtil.parseInt(startDelayMax.trim(), max);
            }
            if (max < min) {
                int tmp = max;
                max = min;
                min = tmp;
            }
            if (min != _util.getStartupDelayMin() || max != _util.getStartupDelayMax()) {
                _util.setStartupDelayMin(min);
                _util.setStartupDelayMax(max);
                changed = true;
                _config.setProperty(PROP_STARTUP_DELAY_MIN, Integer.toString(min));
                _config.setProperty(PROP_STARTUP_DELAY_MAX, Integer.toString(max));
                _config.remove(PROP_STARTUP_DELAY);
                String msg =
                        _t(
                                "Startup delay range changed to {0} - {1}",
                                DataHelper.formatDuration2(min * (60L * 1000)),
                                DataHelper.formatDuration2(max * (60L * 1000)));
                addMessageNoEscape(msg);
            }
        }

        if (refreshDelay != null) {
            int secs = I2PSnarkUtil.parseInt(refreshDelay.trim(), getRefreshDelaySeconds());
            if (secs != getRefreshDelaySeconds()) {
                changed = true;
                _config.setProperty(PROP_REFRESH_DELAY, Integer.toString(secs));
                if (secs >= 0) {
                    String msg =
                            _t(
                                    "Refresh time changed to {0}",
                                    DataHelper.formatDuration2(secs * 1000L));
                    addMessageNoEscapeAndPrint(msg);
                } else {
                    String msg = _t("Refresh disabled");
                    addMessageAndPrint(msg);
                }
            }
        }

        if (pageSize != null) {
            int size = I2PSnarkUtil.parseInt(pageSize.trim(), getPageSize());
            if (size <= 0) {
                size = 999999;
            } else if (size < 5) {
                size = 5;
            }
            if (size != getPageSize()) {
                changed = true;
                pageSize = Integer.toString(size);
                _config.setProperty(PROP_PAGE_SIZE, pageSize);
                addMessage(_t("Page size changed to {0}", pageSize));
            }
        }

        // set this before we check the data dir
        if (areFilesPublic() != filesPublic) {
            _config.setProperty(PROP_FILES_PUBLIC, Boolean.toString(filesPublic));
            _util.setFilesPublic(filesPublic);
            if (filesPublic) {
                String msg = _t("New files will be publicly readable");
                addMessageAndPrint(msg);
            } else {
                String msg = _t("New files will not be publicly readable");
                addMessageAndPrint(msg);
            }
            changed = true;
        }

        if (dataDir != null && !dataDir.equals(getDataDir().getAbsolutePath())) {
            dataDir = DataHelper.stripHTML(dataDir.trim());
            File dd = areFilesPublic() ? new File(dataDir) : new SecureDirectory(dataDir);
            if (_util.connected()) {
                addMessage(_t("Stop all torrents before changing data directory"));
            } else if (!dd.isAbsolute()) {
                addMessage(_t("Data directory must be an absolute path") + ": " + dataDir);
            } else if (!dd.exists() && !dd.mkdirs()) {
                String msg = _t("Data directory cannot be created") + ": " + dataDir;
                addMessageAndPrint(msg);
            } else if (!dd.isDirectory()) {
                String msg = _t("Not a directory") + ": " + dataDir;
                addMessageAndPrint(msg);
            } else if (!dd.canRead()) {
                String msg = _t("Unreadable") + ": " + dataDir;
                addMessageAndPrint(msg);
            } else {
                if (!dd.canWrite()) {
                    String msg = _t("No write permissions for data directory") + ": " + dataDir;
                    addMessageAndPrint(msg);
                }
                changed = true;
                interruptMonitor = true;
                synchronized (_snarks) {
                    for (Snark snark : _snarks.values()) {
                        // leave magnets alone, remove everything else
                        if (snark.getMetaInfo() != null) {
                            stopTorrent(snark, true);
                        }
                    }
                    _config.setProperty(PROP_DIR, dataDir);
                }
                String msg = _t("Data directory changed to {0}", dataDir);
                addMessageAndPrint(msg);
            }
        }

        // Standalone (app context) language.
        // lang will generally be null since it is hidden from the form if in router context.
        if (lang != null
                && !_context.isRouterContext()
                && lang.length() >= 2
                && lang.length() <= 6) {
            int under = lang.indexOf('_');
            String nlang;
            String ncountry;
            if (under > 0 && lang.length() > under + 1) {
                nlang = lang.substring(0, under);
                ncountry = lang.substring(under + 1);
            } else {
                nlang = lang;
                ncountry = "";
            }
            String olang = _config.getProperty(PROP_LANG);
            String ocountry = _config.getProperty(PROP_COUNTRY);
            if (!nlang.equals(olang) || !ncountry.equals(ocountry)) {
                changed = true;
                _config.setProperty(PROP_LANG, nlang);
                _config.setProperty(PROP_COUNTRY, ncountry);
                Translate.setLanguage(nlang, ncountry);
            }
        }

        // Start of I2CP stuff.
        // i2cpHost will generally be null since it is hidden from the form if in router context.
        int oldI2CPPort = _util.getI2CPPort();
        String oldI2CPHost = _util.getI2CPHost();
        int port = oldI2CPPort;
        if (i2cpPort != null) {
            port = I2PSnarkUtil.parseInt(i2cpPort, oldI2CPPort);
        }

        Map<String, String> opts = new HashMap<>();
        i2cpOpts = DataHelper.stripHTML(i2cpOpts);
        StringTokenizer tok = new StringTokenizer(i2cpOpts, " \t\n");
        while (tok.hasMoreTokens()) {
            String pair = tok.nextToken();
            int split = pair.indexOf('=');
            if (split > 0) {
                opts.put(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        Map<String, String> oldOpts = new HashMap<>();
        String oldI2CPOpts = _config.getProperty(PROP_I2CP_OPTS);
        if (oldI2CPOpts == null) oldI2CPOpts = "";
        tok = new StringTokenizer(oldI2CPOpts, " \t\n");
        while (tok.hasMoreTokens()) {
            String pair = tok.nextToken();
            int split = pair.indexOf('=');
            if (split > 0) {
                oldOpts.put(pair.substring(0, split), pair.substring(split + 1));
            }
        }

        boolean reconnect =
                i2cpHost != null
                        && !i2cpHost.trim().isEmpty()
                        && port > 0
                        && (port != _util.getI2CPPort() || !oldI2CPHost.equals(i2cpHost));
        if (reconnect || !oldOpts.equals(opts)) {
            boolean snarksActive = false;
            if (reconnect) {
                for (Snark snark : _snarks.values()) {
                    if (!snark.isStopped()) {
                        snarksActive = true;
                        break;
                    }
                }
            }
            if (_log.shouldDebug()) {
                _log.debug(
                        "i2cp host ["
                                + i2cpHost
                                + "] i2cp port "
                                + port
                                + " opts ["
                                + opts
                                + "] oldOpts ["
                                + oldOpts
                                + "]");
            }
            if (snarksActive) {
                Properties p = new Properties();
                p.putAll(opts);
                _util.setI2CPConfig(i2cpHost, port, p);
                _util.setVaryInboundHops(enableVaryInboundHops);
                _util.setVaryOutboundHops(enableVaryOutboundHops);
                int max = getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW);
                _util.setMaxUpBW(max);
                _bwManager.setUpBWLimit(max * 1024L);
                String msg =
                        _t("I2CP and tunnel changes will take effect after stopping all torrents");
                addMessageAndPrint(msg);
            } else if (!reconnect) {
                // The usual case, the other two are if not in router context
                _config.setProperty(PROP_I2CP_OPTS, i2cpOpts.trim());
                String msg = _t("I2CP options changed to: {0}", i2cpOpts);
                addMessageAndPrint(msg);
                _util.setI2CPConfig(oldI2CPHost, oldI2CPPort, opts);
            } else {
                // Won't happen, I2CP host/port, are hidden in the GUI if in router context
                if (_util.connected()) {
                    _util.disconnect();
                    String msg = _t("Disconnecting old I2CP destination");
                    addMessageAndPrint(msg);
                }
                String msg =
                        _t("I2CP options changed to: {0}", i2cpHost + ':' + port + ' ' + i2cpOpts);
                addMessageAndPrint(msg);
                _util.setI2CPConfig(i2cpHost, port, opts);
                int max = getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW);
                _util.setMaxUpBW(max);
                _bwManager.setUpBWLimit(max * 1024L);
                boolean ok = _util.connect();
                if (!ok) {
                    msg =
                            _t(
                                    "Unable to connect with the new settings, reverting to the old"
                                        + " I2CP settings");
                    addMessageAndPrint(msg);
                    _util.setI2CPConfig(oldI2CPHost, oldI2CPPort, oldOpts);
                    ok = _util.connect();
                    if (!ok) {
                        msg = _t("Unable to reconnect with the old settings!");
                        addMessageAndPrint(msg);
                    }
                } else {
                    msg = _t("Reconnected on the new I2CP destination");
                    addMessageAndPrint(msg);
                    _config.setProperty(PROP_I2CP_HOST, i2cpHost.trim());
                    _config.setProperty(PROP_I2CP_PORT, "" + port);
                    _config.setProperty(PROP_I2CP_OPTS, i2cpOpts.trim());
                    // no PeerAcceptors/I2PServerSockets to deal with, since all snarks are inactive
                    for (Snark snark : _snarks.values()) {
                        if (snark.restartAcceptor()) {
                            msg = _t("I2CP listener restarted for \"{0}\"", snark.getBaseName());
                            addMessageAndPrint(msg);
                            // this is the common ConnectionAcceptor, so we only need to do it once
                            break;
                        }
                    }
                }
            }
            changed = true;
        } // reconnect || changed options

        if (shouldAutoStart() != autoStart) {
            _config.setProperty(PROP_AUTO_START, Boolean.toString(autoStart));
            if (autoStart) {
                addMessage(_t("Enabled autostart"));
            } else {
                addMessage(_t("Disabled autostart"));
            }
            changed = true;
        }

        if (_util.shouldUseOpenTrackers() != useOpenTrackers) {
            _config.setProperty(PROP_USE_OPENTRACKERS, useOpenTrackers + "");
            String msg;
            if (useOpenTrackers) {
                msg = _t("Enabled open trackers - torrent restart required to take effect.");
                addMessageAndPrint(msg);
            } else {
                msg = _t("Disabled open trackers - torrent restart required to take effect.");
                addMessageAndPrint(msg);
            }
            _util.setUseOpenTrackers(useOpenTrackers);
            changed = true;
        }

        if (_util.shouldUseDHT() != useDHT) {
            _config.setProperty(PROP_USE_DHT, Boolean.toString(useDHT));
            if (useDHT) {
                addMessage(_t("Enabled DHT."));
            } else {
                addMessage(_t("Disabled DHT."));
            }
            if (_util.connected()) {
                String msg = _t("DHT change requires tunnel shutdown and reopen") + ".";
                addMessageAndPrint(msg);
            }
            _util.setUseDHT(useDHT);
            changed = true;
        }

        if (_util.ratingsEnabled() != enableRatings) {
            _config.setProperty(PROP_RATINGS, Boolean.toString(enableRatings));
            if (enableRatings) {
                addMessage(_t("Enabled Ratings."));
            } else {
                addMessage(_t("Disabled Ratings."));
            }
            _util.setRatingsEnabled(enableRatings);
            changed = true;
        }

        if (_util.commentsEnabled() != enableComments) {
            _config.setProperty(PROP_COMMENTS, Boolean.toString(enableComments));
            if (enableComments) {
                addMessage(_t("Enabled Comments."));
            } else {
                addMessage(_t("Disabled Comments."));
            }
            _util.setCommentsEnabled(enableComments);
            changed = true;
        }
        if (commentName == null) {
            commentName = "";
        } else {
            commentName = COMMENT_CLEANUP.matcher(commentName.trim()).replaceAll("");
            if (commentName.length() > Comment.MAX_NAME_LEN) {
                commentName = commentName.substring(0, Comment.MAX_NAME_LEN);
            }
        }
        if (!_util.getCommentsName().equals(commentName)) {
            _config.setProperty(PROP_COMMENTS_NAME, commentName);
            addMessage(_t("Comments name set to {0}.", '"' + commentName + '"'));
            _util.setCommentsName(commentName);
            changed = true;
        }

        if (theme != null && !theme.equals(_config.getProperty(PROP_THEME))) {
            _config.setProperty(PROP_THEME, theme);
            changed = true;
        }

        if (_util.collapsePanels() != collapsePanels) {
            _config.setProperty(PROP_COLLAPSE_PANELS, Boolean.toString(collapsePanels));
            if (collapsePanels) {
                addMessage(_t("Collapsible panels enabled."));
            } else {
                addMessage(_t("Collapsible panels disabled."));
            }
            _util.setCollapsePanels(collapsePanels);
            changed = true;
        }

        if (_util.showStatusFilter() != showStatusFilter) {
            _config.setProperty(PROP_SHOW_STATUSFILTER, Boolean.toString(showStatusFilter));
            if (getRefreshDelaySeconds() > 0) {
                if (showStatusFilter) {
                    addMessage(_t("Torrent filter bar enabled."));
                } else {
                    addMessage(_t("Torrent filter bar disabled."));
                }
                _util.setShowStatusFilter(showStatusFilter);
                changed = true;
            }
        }

        if (_util.enableLightbox() != enableLightbox) {
            _config.setProperty(PROP_ENABLE_LIGHTBOX, Boolean.toString(enableLightbox));
            if (enableLightbox) {
                addMessage(_t("Lightbox enabled for image thumbnails."));
            } else {
                addMessage(_t("Lightbox disabled for image thumbnails."));
            }
            _util.setEnableLightbox(enableLightbox);
            changed = true;
        }

        if (_util.enableAddCreate() != enableAddCreate) {
            _config.setProperty(PROP_ENABLE_ADDCREATE, Boolean.toString(enableAddCreate));
            if (enableAddCreate) {
                addMessage(_t("Add and Create sections enabled on all torrent listing pages."));
            } else {
                addMessage(
                        _t(
                                "Add and Create sections to display only on first page of multipage"
                                    + " torrent listing pages."));
            }
            _util.setEnableAddCreate(enableAddCreate);
            changed = true;
        }

        if (apiKey != null && !apiKey.isEmpty() && apiTarget != null && !apiTarget.isEmpty()) {
            apiKey = DataHelper.stripHTML(apiKey.trim());
            apiTarget = DataHelper.stripHTML(apiTarget.trim());
            String oldk = _util.getAPIKey();
            String oldt = _util.getAPITarget();
            if (!apiKey.equals(oldk) || !apiTarget.equals(oldt)) {
                _config.setProperty(PROP_API_PREFIX + apiTarget, apiKey);
                _util.setAPI(apiTarget, apiKey);
                addMessage(_t("API key updated."));
                changed = true;
            }
        }

        if (_util.getPreallocateFiles() != preallocateFiles) {
            _config.setProperty(PROP_PREALLOCATE_FILES, Boolean.toString(preallocateFiles));
            _util.setPreallocateFiles(preallocateFiles);
            if (preallocateFiles) {
                addMessage(_t("Preallocate files for new torrents enabled."));
            } else {
                addMessage(_t("Preallocate files for new torrents disabled."));
            }
            changed = true;
        }

        if (_util.getPreserveFileNames() != preserveFileNames) {
            _config.setProperty(PROP_PRESERVE_FILE_NAMES, Boolean.toString(preserveFileNames));
            _util.setPreserveFileNames(preserveFileNames);
            if (preserveFileNames) {
                addMessage(_t("Preserve original file names enabled."));
            } else {
                addMessage(_t("Preserve original file names disabled."));
            }
            changed = true;
        }

        if (maxFiles != null) {
            int limit = I2PSnarkUtil.parseInt(maxFiles.trim(), _util.getMaxFilesPerTorrent());
            if (limit != _util.getMaxFilesPerTorrent()) {
                if (limit >= 1) {
                    _util.setMaxFilesPerTorrent(limit);
                    changed = true;
                    _config.setProperty(PROP_MAX_FILES_PER_TORRENT, Integer.toString(limit));
                    String msg = _t("Maximum files per torrent changed to {0}", limit);
                    addMessageAndPrint(msg);
                } else {
                    String msg = _t("Invalid maximum files per torrent: {0}", limit);
                    addMessageAndPrint(msg);
                }
            }
        }

        String newTempDir = validateTempDir(tempDir);
        String oldTempDir = _util.getTempDirProp();
        boolean tempDirChanged =
                newTempDir != null ? !newTempDir.equals(oldTempDir) : oldTempDir != null;
        if (tempDirChanged) {
            if (newTempDir != null) {
                _config.setProperty(PROP_TEMP_DIR, newTempDir);
                _util.setTempDirProp(newTempDir);
                addMessage(_t("Temp directory for staging changed to {0}", newTempDir));
            } else {
                _config.remove(PROP_TEMP_DIR);
                _util.setTempDirProp(null);
                addMessage(_t("Temp directory staging disabled."));
            }
            changed = true;
        }

        String newTorrentDir = validateTorrentDir(torrentDir);
        String oldTorrentDir = _config.getProperty(PROP_TORRENT_DIR);
        boolean torrentDirChanged =
                newTorrentDir != null ? !newTorrentDir.equals(oldTorrentDir) : oldTorrentDir != null;
        if (torrentDirChanged) {
            if (newTorrentDir != null) {
                _config.setProperty(PROP_TORRENT_DIR, newTorrentDir);
                addMessage(_t("Torrent directory changed to {0}", newTorrentDir));
            } else {
                _config.remove(PROP_TORRENT_DIR);
                addMessage(_t("Torrent directory reset to data directory."));
            }
            changed = true;
            interruptMonitor = true;
        }

        if (changed) {
            saveConfig();
            // Data dir changed. This will stop and remove all old torrents, and add the new ones
            if (interruptMonitor) {
                _monitor.interrupt();
            }
        }
    }

    /**
     * The configured private tracker list.
     *
     * @return non-null, fixed size, may be empty or unmodifiable
     * @since 0.9.1
     */
    public List<String> getPrivateTrackers() {
        return getListConfig(PROP_PRIVATETRACKERS, null);
    }

    /**
     * Whether the nonce-free browser API for adding torrents is enabled.
     * @since 0.9.71+
     */
    public boolean browserApiEnabled() {
        return _browserApiEnabled;
    }

    /**
     * The raw comma-separated browser API allowed hosts config, for the config form.
     * @return non-null, may be empty
     * @since 0.9.71+
     */
    public String getBrowserApiHosts() {
        return _browserApiHosts;
    }

    /**
     * Set and persist the browser API enable flag and allowed hosts list.
     * The change takes effect immediately; no restart required.
     *
     * @param enable whether the browser API is enabled at all
     * @param hosts comma-separated hostnames or IPs, may be null/empty
     * @since 0.9.71+
     */
    public void setBrowserApi(boolean enable, String hosts) {
        synchronized (_configLock) {
            if (hosts == null) {hosts = "";}
            hosts = hosts.trim();
            if (enable != _browserApiEnabled || !hosts.equals(_browserApiHosts)) {
                _browserApiEnabled = enable;
                _browserApiHosts = hosts;
                _browserApiHostSet = resolveBrowserApiHosts(hosts);
                _config.setProperty(PROP_BROWSER_API, Boolean.toString(enable));
                if (hosts.isEmpty()) {
                    _config.remove(PROP_BROWSER_API_HOSTS);
                } else {
                    _config.setProperty(PROP_BROWSER_API_HOSTS, hosts);
                }
                saveConfig();
            }
        }
    }

    /**
     * Set and persist the API key for remote access, mirroring the apiKey/apiTarget
     * handling in updateConfig. Empty key clears nothing (existing behavior).
     *
     * @param target the API target base path
     * @param key the API key
     * @since 0.9.71+
     */
    public void setAPI(String target, String key) {
        synchronized (_configLock) {
            if (key != null && !key.isEmpty() && target != null && !target.isEmpty()) {
                key = DataHelper.stripHTML(key.trim());
                target = DataHelper.stripHTML(target.trim());
                String oldk = _util.getAPIKey();
                String oldt = _util.getAPITarget();
                if (!key.equals(oldk) || !target.equals(oldt)) {
                    _config.setProperty(PROP_API_PREFIX + target, key);
                    _util.setAPI(target, key);
                    addMessage(_t("API key updated."));
                    saveConfig();
                }
            }
        }
    }

    /**
     * Whether the given remote address may use the browser API without a nonce.
     * Loopback is always allowed; otherwise the host must be listed in the
     * PROP_BROWSER_API_HOSTS config. Does not check the enable flag.
     *
     * @param host remote address string from the request
     * @return whether allowed
     * @since 0.9.71+
     */
    public boolean isBrowserApiHost(String host) {
        if (host == null) {return false;}
        if (isLoopbackHost(host)) {return true;}
        Set<InetAddress> allowed = _browserApiHostSet;
        if (allowed.isEmpty()) {return false;}
        try {
            InetAddress addr = InetAddress.getByName(host);
            return allowed.contains(addr);
        } catch (UnknownHostException uhe) {
            return false;
        }
    }

    /**
     * Parse a comma-separated host list into resolved addresses.
     * Unresolvable or malformed entries are skipped; loopback entries are
     * redundant (always allowed) and skipped.
     *
     * @param hosts comma-separated hostnames or IPs, may be null
     * @return non-null set of resolved addresses, may be empty
     * @since 0.9.71+
     */
    public static Set<InetAddress> resolveBrowserApiHosts(String hosts) {
        Set<InetAddress> rv = new HashSet<>(8);
        if (hosts == null || hosts.isEmpty()) {return rv;}
        StringTokenizer tok = new StringTokenizer(hosts, ",");
        while (tok.hasMoreTokens()) {
            String entry = tok.nextToken().trim();
            if (entry.isEmpty() || isLoopbackHost(entry)) {continue;}
            try {
                InetAddress[] addrs = InetAddress.getAllByName(entry);
                for (InetAddress addr : addrs) {
                    rv.add(addr);
                }
            } catch (UnknownHostException uhe) {
                continue;
            }
        }
        return rv;
    }

    /**
     * IPv4 loopback (127/8), IPv6 loopback, and "localhost".
     * @since 0.9.71+
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null) {return false;}
        if (host.equals("localhost")) {return true;}
        if (host.startsWith("127.")) {return true;}
        if (host.equals("::1")) {return true;}
        if (host.equals("0:0:0:0:0:0:0:1")) {return true;}
        return false;
    }

    /**
     * Save the given open tracker list.
     *
     * @param ot null to restore default
     * @since 0.9.1
     */
    public void saveOpenTrackers(List<String> ot) {
        setListConfig(PROP_OPENTRACKERS, ot);
        if (ot == null) {
            ot = getListConfig(PROP_OPENTRACKERS, DEFAULT_OPENTRACKERS);
        }
        _util.setOpenTrackers(ot);
        String msg = _t("Open Tracker list changed - torrent restart required to take effect.");
        addMessageAndPrint(msg);
        saveConfig();
    }

    /**
     * Save the given private tracker list.
     *
     * @param pt null ok, default is none
     * @since 0.9.1
     */
    public void savePrivateTrackers(List<String> pt) {
        setListConfig(PROP_PRIVATETRACKERS, pt);
        String msg = _t("Private tracker list changed - affects newly created torrents only.");
        addMessageAndPrint(msg);
        saveConfig();
    }

    /**
     * Deserialize the given list property from the config.
     *
     * @param dflt default or null
     * @return non-null, fixed size
     * @since 0.9.1
     */
    private List<String> getListConfig(String prop, String dflt) {
        String val = _config.getProperty(prop);
        if (val == null) {
            val = dflt;
        }
        if (val == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(DataHelper.split(val, ","));
    }

    /**
     * Serialize the given list into the config for the given property, does NOT save it.
     *
     * @param values may be null or empty
     * @return the comma-separated config string, non-null
     * @since 0.9.1
     */
    private String setListConfig(String prop, List<String> values) {
        if (values == null || values.isEmpty()) {
            _config.remove(prop);
            return "";
        }
        StringBuilder buf = new StringBuilder(64);
        for (String s : values) {
            if (buf.length() > 0) {
                buf.append(',');
            }
            buf.append(s);
        }
        String rv = buf.toString();
        _config.setProperty(prop, rv);
        return rv;
    }

    public void saveConfig() {
        try {
            synchronized (_configLock) {
                DataHelper.storeProps(_config, _configFile);
            }
        } catch (IOException ioe) {
            String msg = _t("Unable to save the config to {0}", _configFile.getAbsolutePath());
            addMessageAndPrint(msg);
        }
    }

    /** Canonical .torrent filenames that we are dealing with. An unsynchronized copy. */
    public Set<String> listTorrentFiles() {
        return new HashSet<>(_snarks.keySet());
    }

    /**
     * Grab the torrent given the (canonical) filename of the .torrent file
     *
     * @return Snark or null
     */
    public Snark getTorrent(String filename) {
        synchronized (_snarks) {
            return _snarks.get(filename);
        }
    }

    /**
     * Unmodifiable
     *
     * @return the torrents
     * @since 0.9.4
     */
    public Collection<Snark> getTorrents() {
        return Collections.unmodifiableCollection(_snarks.values());
    }

    /**
     * Grab the torrent given the base name of the storage
     *
     * @param filename must be the filtered name, which may be different than the metainfo's name
     * @return Snark or null
     * @since 0.7.14
     */
    public Snark getTorrentByBaseName(String filename) {
        synchronized (_snarks) {
            return _filteredBaseNameToSnark.get(filename);
        }
    }

    /**
     * Grab the torrent given the info hash
     *
     * @return Snark or null
     * @since 0.8.4
     */
    public Snark getTorrentByInfoHash(byte[] infohash) {
        synchronized (_snarks) {
            return _infoHashToSnark.get(new SHA1Hash(infohash));
        }
    }

    /**
     * Add the snark. Caller must sync on _snarks
     *
     * @since 0.9.42
     */
    private void putSnark(String torrentFile, Snark snark) {
        _snarks.put(torrentFile, snark);
        _infoHashToSnark.put(new SHA1Hash(snark.getInfoHash()), snark);
        Storage storage = snark.getStorage();
        if (storage != null) {
            _filteredBaseNameToSnark.put(storage.getBaseName(), snark);
        }
    }

    /**
     * Remove the snark. Caller must sync on _snarks
     *
     * @since 0.9.42
     */
    private void removeSnark(Snark snark) {
        _snarks.remove(snark.getName());
        _infoHashToSnark.remove(new SHA1Hash(snark.getInfoHash()));
        Storage storage = snark.getStorage();
        if (storage != null) {
            _filteredBaseNameToSnark.remove(storage.getBaseName());
        }
    }

    /**
     * Remove the snark. Caller must sync on _snarks
     *
     * @return the removed Snark or null
     * @since 0.9.42
     */
    private Snark removeSnark(String torrentFile) {
        Snark snark = _snarks.remove(torrentFile);
        if (snark != null) {
            _infoHashToSnark.remove(new SHA1Hash(snark.getInfoHash()));
            Storage storage = snark.getStorage();
            if (storage != null) {
                _filteredBaseNameToSnark.remove(storage.getBaseName());
            }
        }
        return snark;
    }

    /**
     * Rename the torrent file to add a .BAD suffix, log messages
     *
     * @since 0.9.42
     */
    private void disableTorrentFile(String torrentFile) {
        File sfile = new File(torrentFile);
        File rename = new File(torrentFile + ".BAD");
        String msg;
        if (rename.exists()) {
            if (sfile.delete()) {
                msg = _t("Torrent file deleted: {0}", sfile.toString());
                addMessageAndPrint(msg);
            } else {
                if (FileUtil.rename(sfile, rename)) {
                    msg =
                            _t(
                                    "Torrent file moved from {0} to {1}",
                                    sfile.toString(), rename.toString());
                    addMessageAndPrint(msg);
                }
            }
        }
    }

    /**
     * Caller must verify this torrent is not already added.
     *
     * @param filename the absolute path to save the metainfo to, generally ending in ".torrent"
     * @param baseFile may be null, if so look in dataDir
     * @param dontAutoStart must be false, AND running=true or null in metadata, to start
     * @throws RuntimeException via Snark.fatal()
     * @return success
     */
    private boolean addTorrent(String filename, File baseFile, boolean dontAutoStart) {
        return addTorrent(filename, baseFile, dontAutoStart, null);
    }

    /**
     * Caller must verify this torrent is not already added.
     *
     * @param filename the absolute path to save the metainfo to, generally ending in ".torrent"
     * @param baseFile may be null, if so look in dataDir
     * @param dontAutoStart must be false, AND running=true or null in metadata, to start
     * @param dataDir must exist, or null to default to snark data directory
     * @throws RuntimeException via Snark.fatal()
     * @return success
     * @since 0.9.17
     */
    private boolean addTorrent(
            String filename, File baseFile, boolean dontAutoStart, File dataDir) {
        File sfile = new File(filename);
        String msg;
        try {
            filename = sfile.getCanonicalPath();
        } catch (IOException ioe) {
            _log.error("Unable to add torrent: " + filename + " (" + ioe.getMessage() + ")");
            msg = _t("Error: Could not add torrent: {0}", filename) + " (" + ioe.getMessage() + ")";
            addMessageAndPrint(msg);
            return false;
        }
        if (dataDir == null) {
            dataDir = getDataDir();
        }
        Snark torrent;
        synchronized (_snarks) {
            torrent = _snarks.get(filename);
        }
        // Don't hold the _snarks lock while verifying the torrent
        if (torrent == null) {
            synchronized (_addSnarkLock) { // Double-check
                synchronized (_snarks) {
                    if (_snarks.get(filename) != null) {
                        msg = _t("Torrent already running: {0}", filename);
                        addMessageAndPrint(msg);
                        return false;
                    }
                }

                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(sfile);
                } catch (IOException ioe) {
                    // catch this here so we don't try do delete it below
                    msg =
                            _t("Cannot open \"{0}\"", sfile.getName())
                                    + ": "
                                    + ioe.getLocalizedMessage();
                    addMessageAndPrint(msg);
                    return false;
                }

                try {
                    // This is somewhat wasteful as this metainfo is thrown away,
                    // the real one is created in the Snark constructor.
                    // TODO: Make a Snark constructor where we pass the MetaInfo in as a parameter.
                    MetaInfo info = new MetaInfo(fis);
                    try {
                        fis.close();
                        fis = null;
                    } catch (IOException e) { /* ignored */ }

                    // These tests may be duplicates, but not if we were called
                    // from the DirMonitor, which only checks for dup torrent file names.
                    Snark snark = getTorrentByInfoHash(info.getInfoHash());
                    if (snark != null) {
                        // If the existing one is a lookup or magnet, remove it and
                        // replace with the real torrent (the TODO from 0.8.4).
                        // This happens when a DHT lookup resolves: gotMetaInfo writes
                        // the .torrent to the torrent dir, DirMonitor picks it up,
                        // and we get here because the magnet is still in _snarks.
                        String existingName = snark.getName();
                        boolean isLookup = _magnets.contains(existingName)
                                || (existingName != null && existingName.startsWith("lookup-"));
                        if (isLookup) {
                            if (_log.shouldInfo()) {
                                _log.info("Replacing lookup magnet with real torrent: " + existingName);
                            }
                            synchronized (_snarks) {
                                removeSnark(snark);
                            }
                            snark.stopTorrent();
                            _magnets.remove(existingName);
                            removeMagnetStatus(snark.getInfoHash());
                        } else {
                            msg =
                                    _t(
                                            "Torrent with this info hash is already running: {0}",
                                            snark.getBaseName());
                            addMessageAndPrint(msg);
                            return false;
                        }
                    }
                    String name = info.getName();
                    snark = getTorrentByBaseName(name);
                    if (snark != null) {
                        msg =
                                _t(
                                        "Torrent with the same data location is already running:"
                                            + " {0}",
                                        snark.getBaseName());
                        addMessageAndPrint(msg);
                        return false;
                    }
                    String filtered = Storage.filterName(name);
                    if (!filtered.equals(name)) {
                        snark = getTorrentByBaseName(filtered);
                        if (snark != null) {
                            addMessage(_t("Torrent with the same data location is already running: {0}", snark.getBaseName()));
                            return false;
                        }
                    }

                    String rejectMessage = validateTorrent(info);
                    if (rejectMessage != null) {
                        throw new IOException(rejectMessage);
                    }

                    // TODO load saved closest DHT nodes and pass to the Snark ?
                    // This may take a LONG time
                    if (baseFile == null) {
                        baseFile = getSavedBaseFile(info.getInfoHash());
                    }
                    if (_log.shouldInfo()) {
                        _log.info(
                                "New Snark loaded\n* Torrent: "
                                        + filename
                                        + "\n* Base: "
                                        + baseFile);
                    }
                    torrent =
                            new Snark(
                                    _util,
                                    filename,
                                    null,
                                    -1,
                                    null,
                                    null,
                                    this,
                                    _peerCoordinatorSet,
                                    _connectionAcceptor,
                                    dataDir.getPath(),
                                    baseFile);
                    loadSavedFilePriorities(torrent);
                    synchronized (_snarks) {
                        putSnark(filename, torrent);
                    }
                } catch (IOException ioe) {
                    // close before rename/delete for windows
                    if (fis != null)
                        try {
                            fis.close();
                            fis = null;
                        } catch (IOException ioe2) { /* ignored */ }
                    String err =
                            _t("Torrent in \"{0}\" is invalid", sfile.toString())
                                    + ": "
                                    + ioe.getLocalizedMessage();
                    addMessageAndPrint(err);
                    _log.error(err, ioe);
                    disableTorrentFile(filename);
                    return false;
                } catch (OutOfMemoryError oom) {
                    String s =
                            _t(
                                            "ERROR - Out of memory, cannot create torrent from {0}",
                                            sfile.getName())
                                    + ": "
                                    + oom.getLocalizedMessage();
                    addMessage(s);
                    throw new Snark.RouterException(s, oom);
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (IOException ioe) { /* ignored */ }
                    }
                }
            }
        } else {
            msg = _t("Torrent already running: {0}", filename);
            addMessageAndPrint(msg);
            return false;
        }
        // ok, snark created, now let's start it up or configure it further
        Properties config = getConfig(torrent);
        String prop = config.getProperty(PROP_META_RUNNING);
        boolean running = prop == null || Boolean.parseBoolean(prop);
        prop = config.getProperty(PROP_META_ACTIVITY);
        if (prop != null && torrent.getStorage() != null) {
            long activity = I2PSnarkUtil.parseLong(prop, torrent.getStorage().getActivity());
            torrent.getStorage().setActivity(activity);
        }

        // Were we running last time?
        String link = linkify(torrent);
        String torrentLink = link.replace(" ", "%20").replace("a%20href", "a href");
        if (!dontAutoStart && shouldAutoStart() && running) {
            if (!_util.connected()) {
                msg = _t("Initializing I2PSnark and opening tunnels") + "...";
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                boolean ok = _util.connect();
                if (!ok) {
                    if (_context.isRouterContext()) {
                        addMessage(_t("Unable to connect to I2P"));
                    } else {
                        msg =
                                _t("Error connecting to I2P - check your I2CP settings!")
                                        + ' '
                                        + _util.getI2CPHost()
                                        + ':'
                                        + _util.getI2CPPort();
                        addMessageAndPrint(msg);
                    }
                }
            }
            torrent.startTorrent();
            addMessageNoEscapeAndPrint(
                    _t("Torrent added and started: {0}", torrentLink),
                    _t("Torrent added and started: {0}", torrent.getBaseName()));
        } else {
            addMessageNoEscapeAndPrint(
                    _t("Torrent added: {0}", torrentLink),
                    _t("Torrent added: {0}", torrent.getBaseName()));
        }

        MetaInfo info = torrent.getMetaInfo();
        String warnMsg;
        if (!TrackerClient.isValidAnnounce(info.getAnnounce())) {
            if (info.isPrivate()) {
                warnMsg = _t("ERROR - No I2P trackers in private torrent \"{0}\"", info.getName());
            } else if (!_util.getOpenTrackers().isEmpty()) {
                warnMsg =
                        _t(
                                "Warning - No I2P trackers in \"{0}\", will announce to I2P open"
                                    + " trackers and DHT only.",
                                info.getName());
            } else if (_util.shouldUseDHT()) {
                warnMsg =
                        _t(
                                "Warning - No I2P trackers in \"{0}\", and open trackers are"
                                    + " disabled, will announce to DHT only.",
                                info.getName());
            } else {
                warnMsg =
                        _t(
                                "Warning - No I2P trackers in \"{0}\", and DHT and open trackers"
                                    + " are disabled, you should enable open trackers or DHT before"
                                    + " starting the torrent.",
                                info.getName());
                dontAutoStart = true;
            }
            addMessageAndPrint(warnMsg);
        }

        return true;
    }

    /**
     * Add a torrent with the info hash alone (magnet / maggot)
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we save this magnet to metadata, to persist it across restarts,
     *     in case we don't get the metadata before shutdown?
     * @throws RuntimeException via Snark.fatal()
     * @since 0.8.4
     */
    public void addMagnet(String name, byte[] ih, String trackerURL, boolean updateStatus) {
        // updateStatus is true from UI, false from startup bulk add
        addMagnet(name, ih, trackerURL, updateStatus, updateStatus, null, this);
    }

    /**
     * Add a torrent with the info hash alone (magnet / maggot)
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we save this magnet to metadata, to persist it across restarts,
     *     in case we don't get the metadata before shutdown?
     * @param dataDir must exist, or null to default to snark data directory
     * @throws RuntimeException via Snark.fatal()
     * @since 0.9.17
     */
    public void addMagnet(
            String name, byte[] ih, String trackerURL, boolean updateStatus, File dataDir) {
        // updateStatus is true from UI, false from startup bulk add
        addMagnet(name, ih, trackerURL, updateStatus, updateStatus, dataDir, this);
    }

    /**
     * Add a torrent with the info hash alone (magnet / maggot) External use is for UpdateRunner.
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we save this magnet to metadata, to persist it across restarts,
     *     in case we don't get the metadata before shutdown?
     * @param dataDir must exist, or null to default to snark data directory
     * @param listener to intercept callbacks, should pass through to this
     * @return the new Snark or null on failure
     * @throws RuntimeException via Snark.fatal()
     * @since 0.9.4
     */
    public Snark addMagnet(
            String name,
            byte[] ih,
            String trackerURL,
            boolean updateStatus,
            boolean autoStart,
            File dataDir,
            CompleteListener listener) {
        String dirPath = dataDir != null ? dataDir.getAbsolutePath() : getDataDir().getPath();
        String msg;
        Snark torrent =
                new Snark(
                        _util,
                        name,
                        ih,
                        trackerURL,
                        listener,
                        _peerCoordinatorSet,
                        _connectionAcceptor,
                        dirPath);

        synchronized (_snarks) {
            Snark snark = getTorrentByInfoHash(ih);
            if (snark != null) {
                msg =
                        _t(
                                "Torrent with this info hash is already running: {0}",
                                snark.getBaseName());
                addMessageAndPrint(msg);
                return null;
            }
            // Tell the dir monitor not to delete us
            _magnets.add(name);
            if (updateStatus) {
                saveMagnetStatus(ih, dirPath, trackerURL, name);
            }
            putSnark(name, torrent);
        }
        if (autoStart) {
            startTorrent(ih);
            DHT dht = _util.getDHT();
            boolean shouldWarn =
                    _util.connected()
                            && _util.getOpenTrackers().isEmpty()
                            && ((!_util.shouldUseDHT()) || dht == null || dht.size() <= 0);
            if (shouldWarn) {
                msg =
                        _t(
                                "Open trackers are disabled and we have no DHT peers. Fetch of {0}"
                                    + " may not succeed until you start another torrent, enable"
                                    + " open trackers, or enable DHT.",
                                name);
                addMessageAndPrint(msg);
            }
        } else {
            msg = _t("Adding {0}", name);
            addMessageAndPrint(msg);
        }
        return torrent;
    }

    /**
     * Stop and delete a torrent running in magnet mode
     *
     * @param snark a torrent with a fake file name ("Magnet xxxx")
     * @since 0.8.4
     */
    public void deleteMagnet(Snark snark) {
        synchronized (_snarks) {
            removeSnark(snark);
        }
        snark.stopTorrent();
        _magnets.remove(snark.getName());
        removeMagnetStatus(snark.getInfoHash());
        removeTorrentStatus(snark);
    }

    /**
     * Result of a DHT metadata lookup — name plus combined size of data files.
     * Size is {@code MetaInfo.getDataLength()} (excludes BEP47 padding), i.e. the
     * sum of {@code getLengths()} for non-padding files; for single-file torrents
     * equal to {@code getTotalLength()}. Zero if metadata unavailable.
     * @since 0.9.71+
     */
    public static class TorrentInfo {
        /** Torrent display name ({@code MetaInfo.getName()}) */
        public final String name;
        /** Combined size of data files in bytes (excludes padding) */
        public final long size;
        public TorrentInfo(String name, long size) {
            this.name = name;
            this.size = size;
        }
        @Override
        public String toString() { return name + " (" + DataHelper.formatSize2(size) + ")"; }
    }

    /**
     * Self-contained DHT lookup for torrent name without persisting torrent data.
     * Creates a temporary magnet in a temp directory under the I2P temp dir,
     * waits for metadata via DHT, extracts the torrent name, then deletes the
     * magnet and temp directory. Does not store the .torrent file in the
     * snark data directory and does not persist magnet status.
     * If the infohash is already running as a user torrent, its name is returned
     * directly without creating a lookup torrent or deleting the existing one.
     *
     * <p>This is intended for lightweight trackers (e.g. zzzot) that need only the
     * torrent name for display (hashlist) and do not want to store metadata or
     * pre-allocate data files. The lookup torrent is stored in
     * {@code $I2P_TEMP/zzzot-lookup-<random>} and deleted when resolved or on timeout.
     *
     * @param infoHash 20-byte infohash
     * @param timeoutMs max time to wait for metadata (e.g. 60_000 for 1 min, 3_600_000 for 60 min)
     * @return torrent name (MetaInfo.getName()) or null if not found/timeout/interrupted
     * @since 0.9.71+
     */
    public String lookupTorrentName(byte[] infoHash, long timeoutMs) {
        if (infoHash == null || infoHash.length != 20)
            return null;
        // If already running as a user torrent, return its name directly (do not delete it)
        Snark existing = getTorrentByInfoHash(infoHash);
        if (existing != null) {
            String existingName = null;
            try { existingName = existing.getName(); } catch (Exception ignore) {}
            boolean isOurLookup = existingName != null && (existingName.startsWith("lookup-") || existingName.contains("zzzot-lookup"));
            // Also check storage base for temp dir
            try {
                Storage st = existing.getStorage();
                if (st != null) {
                    File base = st.getBase();
                    if (base != null && base.getPath().contains("zzzot-lookup"))
                        isOurLookup = true;
                }
            } catch (Exception ignore) {}
            if (!isOurLookup) {
                try {
                    MetaInfo meta = existing.getMetaInfo();
                    if (meta != null) {
                        String mn = meta.getName();
                        if (mn != null && !mn.isEmpty())
                            return mn;
                    }
                } catch (Exception ignore) {}
                if (existingName != null && !existingName.isEmpty() && !existingName.startsWith("lookup-"))
                    return existingName;
                // Fall through to wait for existing lookup-* to resolve
                if (!isOurLookup)
                    return existingName;
            }
            // isOurLookup == true: reuse existing lookup torrent, wait for its metadata below
        }
        // With zero timeout, don't create a magnet (addMagnet + startTorrent + deleteMagnet is expensive)
        if (timeoutMs <= 0)
            return null;
        File tmpBase = _context.getTempDir();
        File lookupDir = new File(tmpBase, "zzzot-lookup-" + _context.random().nextLong());
        // Ensure directory exists; SecureDirectory handles perms
        if (!lookupDir.mkdirs() && !lookupDir.isDirectory()) {
            _log.warn("lookupTorrentName: could not create tmp dir " + lookupDir);
            return null;
        }
        String hex = I2PSnarkUtil.toHex(infoHash);
        String magnetName = "lookup-" + hex.substring(0, 8);
        Snark snark = null;
        boolean created = false;
        synchronized (_snarks) {
            Snark dup = getTorrentByInfoHash(infoHash);
            if (dup != null) {
                // Another lookup raced us - reuse it, don't create duplicate
                snark = dup;
            } else {
                try {
                    // updateStatus=false (don't persist), autoStart=true (bypass startup delay)
                    snark = addMagnet(magnetName, infoHash, null, false, true, lookupDir, this);
                    created = (snark != null);
                    if (created) {
                        _lookupCreationTimes.put(new SHA1Hash(infoHash), System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    _log.warn("lookupTorrentName addMagnet failed for " + hex, e);
                    FileUtil.rmdir(lookupDir, false);
                    return null;
                }
                if (snark == null) {
                    // addMagnet returned null (duplicate raced), try to get it
                    snark = getTorrentByInfoHash(infoHash);
                    if (snark == null) {
                        FileUtil.rmdir(lookupDir, false);
                        return null;
                    }
                }
            }
        }
        // Wait for metadata — bounded by semaphore to prevent thread explosion
        if (!_lookupSemaphore.tryAcquire()) {
            if (_log.shouldWarn()) {
                _log.warn("lookupTorrentName: " + MAX_LOOKUP_CONCURRENCY
                    + " concurrent lookups in progress, rejecting " + hex);
            }
            // Still clean up the magnet we just created
            Snark toReject = getTorrentByInfoHash(infoHash);
            if (toReject != null && created) {
                try { deleteMagnet(toReject); } catch (Exception ignore) {}
            }
            try { FileUtil.rmdir(lookupDir, false); } catch (Exception ignore) {}
            return null;
        }
        scheduleStaleLookupCleanup();
        long end = System.currentTimeMillis() + timeoutMs;
        String result = null;
        try {
            while (System.currentTimeMillis() < end) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                Snark cur = getTorrentByInfoHash(infoHash);
                if (cur == null)
                    break;
                try {
                    MetaInfo meta = cur.getMetaInfo();
                    if (meta != null) {
                        String n = meta.getName();
                        if (n != null && !n.isEmpty()) {
                            result = n;
                            break;
                        }
                    }
                } catch (Exception ignore) {}
                // Fallback: after promotion, getName() may be the .torrent path
                try {
                    String n = cur.getName();
                    if (n != null && !n.isEmpty() && !n.equals(magnetName) && !n.startsWith("lookup-")) {
                        // n may be /path/to/file.torrent - extract basename without .torrent
                        if (n.endsWith(".torrent")) {
                            try { result = new File(n).getName().replaceFirst("\\.torrent$", ""); }
                            catch (Exception ignore) { result = n; }
                        } else {
                            result = n;
                        }
                        break;
                    }
                } catch (Exception ignore) {}
            }
        } finally {
            _lookupSemaphore.release();
            _lookupCreationTimes.remove(new SHA1Hash(infoHash));
            // Clean up lookup torrent if we created it or it is still a lookup-*
            Snark toDelete = getTorrentByInfoHash(infoHash);
            if (toDelete != null) {
                String toDeleteName = null;
                File storageBase = null;
                try { toDeleteName = toDelete.getName(); } catch (Exception ignore) {}
                try {
                    Storage st = toDelete.getStorage();
                    if (st != null) storageBase = st.getBase();
                } catch (Exception ignore) {}
                boolean isLookup = false;
                if (toDeleteName != null && (toDeleteName.startsWith("lookup-") || toDeleteName.contains("zzzot-lookup")))
                    isLookup = true;
                if (storageBase != null && storageBase.getPath().contains("zzzot-lookup"))
                    isLookup = true;
                // Also check lookupDir path we created
                if (lookupDir.getPath().contains("zzzot-lookup"))
                    isLookup = isLookup || created;
                if (isLookup) {
                    // Only delete lookup torrents, never user torrents
                    try {
                        deleteMagnet(toDelete);
                    } catch (Exception e) {
                        _log.warn("lookupTorrentName deleteMagnet failed for " + hex, e);
                    }
                    // Delete .torrent file if it was written to lookupDir (now possibly in default dir)
                    if (toDeleteName != null && toDeleteName.endsWith(".torrent")) {
                        try {
                            File tf = new File(toDeleteName);
                            if (tf.exists() && tf.getParentFile() != null && tf.getParentFile().getPath().contains("zzzot-lookup")) {
                                tf.delete();
                            }
                        } catch (Exception ignore) {}
                    }
                }
            }
            // Always delete our tmp dir (contains pre-allocated data if any)
            try { FileUtil.rmdir(lookupDir, false); } catch (Exception ignore) {}
        }
        return result;
    }

    /**
     * Hex string overload for convenience.
     *
     * @param hex 40-char hex infohash (case-insensitive)
     * @param timeoutMs max wait
     * @return name or null
     * @since 0.9.71+
     */
    public String lookupTorrentName(String hex, long timeoutMs) {
        if (hex == null || hex.length() != 40)
            return null;
        byte[] ih = new byte[20];
        try {
            for (int i = 0; i < 20; i++) {
                ih[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4) + Character.digit(hex.charAt(i * 2 + 1), 16));
            }
        } catch (Exception e) {
            return null;
        }
        return lookupTorrentName(ih, timeoutMs);
    }

    /**
     * Self-contained DHT lookup for torrent name and size without persisting torrent data.
     * Same semantics as {@link #lookupTorrentName(byte[], long)} but also returns
     * the combined size of data files (excludes BEP47 padding) via
     * {@code MetaInfo.getDataLength()}. This is the value zzzot hashlist should
     * display alongside the name.
     *
     * @param infoHash 20-byte infohash
     * @param timeoutMs max wait as for {@code lookupTorrentName}
     * @return {@code TorrentInfo} with name and size, or null if not found/timeout
     * @since 0.9.71+
     */
    public TorrentInfo lookupTorrentInfo(byte[] infoHash, long timeoutMs) {
        if (infoHash == null || infoHash.length != 20)
            return null;
        Snark existing = getTorrentByInfoHash(infoHash);
        if (existing != null) {
            String existingName = null;
            try { existingName = existing.getName(); } catch (Exception ignore) {}
            boolean isOurLookup = existingName != null && (existingName.startsWith("lookup-") || existingName.contains("zzzot-lookup"));
            try {
                Storage st = existing.getStorage();
                if (st != null) {
                    File base = st.getBase();
                    if (base != null && base.getPath().contains("zzzot-lookup"))
                        isOurLookup = true;
                }
            } catch (Exception ignore) {}
            if (!isOurLookup) {
                try {
                    MetaInfo meta = existing.getMetaInfo();
                    if (meta != null) {
                        String mn = meta.getName();
                        if (mn != null && !mn.isEmpty())
                            return new TorrentInfo(mn, meta.getDataLength());
                    }
                } catch (Exception ignore) {}
                // If no MetaInfo yet (magnet without metadata), don't return placeholder
                // — fall through to wait or return null
                if (existingName != null && !existingName.isEmpty() && !existingName.startsWith("lookup-") && !existingName.startsWith("Magnet")) {
                    // Best-effort fallback when meta is null but we have a real name
                    return new TorrentInfo(existingName, 0);
                }
            }
        }
        // With zero timeout, don't create a magnet (addMagnet + startTorrent + deleteMagnet is expensive)
        if (timeoutMs <= 0)
            return null;
        // Reuse the name-only lookup then enrich with size from MetaInfo if available.
        // This keeps metadata-only semantics (Snark.gotMetaInfo skips Storage) and
        // avoids duplicating the full wait/cleanup logic here.
        File tmpBase = _context.getTempDir();
        File lookupDir = new File(tmpBase, "zzzot-lookup-" + _context.random().nextLong());
        if (!lookupDir.mkdirs() && !lookupDir.isDirectory()) {
            _log.warn("lookupTorrentInfo: could not create tmp dir " + lookupDir);
            return null;
        }
        String hex = I2PSnarkUtil.toHex(infoHash);
        String magnetName = "lookup-" + hex.substring(0, 8);
        boolean created = false;
        synchronized (_snarks) {
            Snark dup = getTorrentByInfoHash(infoHash);
            if (dup == null) {
                try {
                    Snark snark = addMagnet(magnetName, infoHash, null, false, true, lookupDir, this);
                    created = (snark != null);
                } catch (Exception e) {
                    _log.warn("lookupTorrentInfo addMagnet failed for " + hex, e);
                    FileUtil.rmdir(lookupDir, false);
                    return null;
                }
            }
        }
        long end = System.currentTimeMillis() + timeoutMs;
        if (!_lookupSemaphore.tryAcquire()) {
            if (_log.shouldWarn()) {
                _log.warn("lookupTorrentInfo: " + MAX_LOOKUP_CONCURRENCY
                    + " concurrent lookups in progress, rejecting " + hex);
            }
            if (created) {
                Snark toReject = getTorrentByInfoHash(infoHash);
                if (toReject != null) try { deleteMagnet(toReject); } catch (Exception ignore) {}
            }
            try { FileUtil.rmdir(lookupDir, false); } catch (Exception ignore) {}
            return null;
        }
        scheduleStaleLookupCleanup();
        TorrentInfo result = null;
        try {
            while (System.currentTimeMillis() < end) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                Snark cur = getTorrentByInfoHash(infoHash);
                if (cur == null)
                    break;
                try {
                    MetaInfo meta = cur.getMetaInfo();
                    if (meta != null) {
                        String n = meta.getName();
                        if (n != null && !n.isEmpty()) {
                            result = new TorrentInfo(n, meta.getDataLength());
                            break;
                        }
                    }
                } catch (Exception ignore) {}
                try {
                    String n = cur.getName();
                    if (n != null && !n.isEmpty() && !n.equals(magnetName) && !n.startsWith("lookup-")) {
                        if (n.endsWith(".torrent")) {
                            try { n = new File(n).getName().replaceFirst("\\.torrent$", ""); }
                            catch (Exception ignore) {}
                        }
                        result = new TorrentInfo(n, 0);
                        break;
                    }
                } catch (Exception ignore) {}
            }
        } finally {
            _lookupSemaphore.release();
            _lookupCreationTimes.remove(new SHA1Hash(infoHash));
            Snark toDelete = getTorrentByInfoHash(infoHash);
            if (toDelete != null) {
                String toDeleteName = null;
                File storageBase = null;
                try { toDeleteName = toDelete.getName(); } catch (Exception ignore) {}
                try {
                    Storage st = toDelete.getStorage();
                    if (st != null) storageBase = st.getBase();
                } catch (Exception ignore) {}
                boolean isLookup = false;
                if (toDeleteName != null && (toDeleteName.startsWith("lookup-") || toDeleteName.contains("zzzot-lookup")))
                    isLookup = true;
                if (storageBase != null && storageBase.getPath().contains("zzzot-lookup"))
                    isLookup = true;
                if (lookupDir.getPath().contains("zzzot-lookup"))
                    isLookup = isLookup || created;
                if (isLookup) {
                    try { deleteMagnet(toDelete); } catch (Exception e) { _log.warn("lookupTorrentInfo deleteMagnet failed for " + hex, e); }
                    if (toDeleteName != null && toDeleteName.endsWith(".torrent")) {
                        try {
                            File tf = new File(toDeleteName);
                            if (tf.exists() && tf.getParentFile() != null && tf.getParentFile().getPath().contains("zzzot-lookup"))
                                tf.delete();
                        } catch (Exception ignore) {}
                    }
                }
            }
            try { FileUtil.rmdir(lookupDir, false); } catch (Exception ignore) {}
        }
        return result;
    }

    /**
     * Hex overload for {@link #lookupTorrentInfo(byte[], long)}.
     * @since 0.9.71+
     */
    public TorrentInfo lookupTorrentInfo(String hex, long timeoutMs) {
        if (hex == null || hex.length() != 40)
            return null;
        byte[] ih = new byte[20];
        try {
            for (int i = 0; i < 20; i++) {
                ih[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4) + Character.digit(hex.charAt(i * 2 + 1), 16));
            }
        } catch (Exception e) {
            return null;
        }
        return lookupTorrentInfo(ih, timeoutMs);
    }

    /**
     * Schedule a one-shot cleanup of stale lookup torrents (if not already scheduled).
     * Scans _snarks for lookup-* torrents that have been running longer than
     * {@link #LOOKUP_STALE_MS} and removes them. They will be resubmitted by
     * the caller (zzzot) on the next request.
     *
     * @since 0.9.71+
     */
    private void scheduleStaleLookupCleanup() {
        if (_staleLookupCleanupRunning) return;
        _staleLookupCleanupRunning = true;
        new SimpleTimer2.TimedEvent(SimpleTimer2.getInstance(), 60 * 1000) {
            public void timeReached() {
                _staleLookupCleanupRunning = false;
                if (!_running) return;
                cleanupStaleLookupTorrents();
            }
        };
    }

    /**
     * Remove lookup torrents from _snarks that have been running longer than
     * {@link #LOOKUP_STALE_MS}. Called periodically after a lookup is submitted.
     *
     * @since 0.9.71+
     */
    private void cleanupStaleLookupTorrents() {
        long now = System.currentTimeMillis();
        List<Snark> stale = new ArrayList<>(0);
        synchronized (_snarks) {
            for (Snark snark : _snarks.values()) {
                String name = null;
                try { name = snark.getName(); } catch (Exception ignore) {}
                boolean isLookup = (name != null && (name.startsWith("lookup-") || name.contains("zzzot-lookup")));
                if (!isLookup) {
                    try {
                        Storage st = snark.getStorage();
                        if (st != null) {
                            File base = st.getBase();
                            if (base != null && base.getPath().contains("zzzot-lookup"))
                                isLookup = true;
                        }
                    } catch (Exception ignore) {}
                }
                if (isLookup) {
                    Long created = _lookupCreationTimes.get(new SHA1Hash(snark.getInfoHash()));
                    if (created != null && (now - created) > LOOKUP_STALE_MS) {
                        stale.add(snark);
                    }
                }
            }
        }
        if (stale.isEmpty()) return;
        if (_log.shouldInfo()) {
            _log.info("Cleaning up " + stale.size() + " stale lookup torrent(s) (> "
                + (LOOKUP_STALE_MS / 60000) + " min)");
        }
        for (Snark snark : stale) {
            byte[] ih = snark.getInfoHash();
            SHA1Hash ihHash = new SHA1Hash(ih);
            String hex = I2PSnarkUtil.toHex(ih);
            _lookupCreationTimes.remove(ihHash);
            try {
                deleteMagnet(snark);
            } catch (Exception e) {
                _log.warn("Stale lookup cleanup deleteMagnet failed for " + hex, e);
            }
            // Clean up tmp dir
            try {
                Storage st = snark.getStorage();
                if (st != null) {
                    File base = st.getBase();
                    if (base != null && base.getPath().contains("zzzot-lookup"))
                        FileUtil.rmdir(base, false);
                }
            } catch (Exception ignore) {}
        }
    }

    /**
     * Add and start a FetchAndAdd task. Remove it with deleteMagnet().
     *
     * @param torrent must be instanceof FetchAndAdd
     * @throws RuntimeException via Snark.fatal()?
     * @since 0.9.1
     */
    public void addDownloader(Snark torrent) {
        synchronized (_snarks) {
            Snark snark = getTorrentByInfoHash(torrent.getInfoHash());
            if (snark != null) {
                String msg = _t("Download already running: {0}", snark.getBaseName());
                addMessageAndPrint(msg);
                return;
            }
            String name = torrent.getName();
            _magnets.add(name); // Tell the dir monitor not to delete us
            putSnark(name, torrent);
        }
        torrent.startTorrent();
    }

    /**
     * Add a torrent from a MetaInfo. Save the MetaInfo data to filename. Serialized on the add
     * lock to prevent interference from the DirMonitor. This verifies that a torrent with this
     * infohash is not already added. This may take a LONG time to create or check the storage, so
     * the global snarks lock is only held for the duplicate checks.
     *
     * <p>Called from servlet. This is only for the 'create torrent' form.
     *
     * @param metainfo the metainfo for the torrent
     * @param bitfield the current completion status of the torrent, or null
     * @param filename the absolute path to save the metainfo to, generally ending in ".torrent",
     *     which is also the name of the torrent Must be a filesystem-safe name. If null, will
     *     generate a name from the metainfo.
     * @param baseFile may be null, if so look in rootDataDir
     * @throws RuntimeException via Snark.fatal()
     * @return success
     * @since 0.8.4
     */
    public boolean addTorrent(
            MetaInfo metainfo,
            BitField bitfield,
            String filename,
            File baseFile,
            boolean dontAutoStart)
            throws IOException {
        // Serialize with the DirMonitor, which also takes this lock in addTorrent()
        synchronized (_addSnarkLock) { // Double-check
            synchronized (_snarks) {
                Snark snark = getTorrentByInfoHash(metainfo.getInfoHash());
                String msg;
                if (snark != null) {
                    msg =
                            _t(
                                    "Torrent with this info hash is already running: {0}",
                                    snark.getBaseName());
                    addMessageAndPrint(msg);
                    return false;
                }
                String filtered = Storage.filterName(metainfo.getName());
                snark = getTorrentByBaseName(filtered);
                if (snark != null) {
                    msg =
                            _t(
                                    "Torrent with the same data location is already running: {0}",
                                    snark.getBaseName());
                    addMessageAndPrint(msg);
                    return false;
                }
                if (filename == null) {
                    File f = new File(getTorrentDir(), filtered + ".torrent");
                    File parent = f.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    if (f.exists()) {
                        msg =
                                _t("Failed to copy torrent file to {0}", f.getAbsolutePath())
                                        + _t(" - torrent file already exists");
                        addMessageAndPrint(msg);
                        _log.error("[I2PSnark] Torrent file already exists: " + f);
                    }
                    filename = f.getAbsolutePath();
                }
                if (bitfield != null) {
                    saveTorrentStatus(
                            metainfo,
                            bitfield,
                            null,
                            baseFile,
                            0,
                            0,
                            true,
                            (Boolean) null); // no file priorities
                }
            }
            try {
                locked_writeMetaInfo(
                        metainfo, filename, areFilesPublic());
                // Prevent addTorrent from rechecking
                // The long storage check runs without the global snarks lock;
                // addTorrent() re-verifies duplicates under this same add lock
                return addTorrent(filename, baseFile, dontAutoStart);
            } catch (IOException ioe) {
                String msg = _t("Failed to copy torrent file to {0}", filename);
                addMessageAndPrint(msg);
                _log.error("[I2PSnark] Failed to write torrent file", ioe);
                return false;
            }
        }
    }

    /**
     * Add a torrent from a file not in the torrent directory. Copy the file to filename. Holds the
     * snarks lock to prevent interference from the DirMonitor. Caller must verify this torrent is
     * not already added. This may take a LONG time to create or check the storage.
     *
     * @param fromfile where the file is now, presumably in a temp directory somewhere
     * @param filename the absolute path to save the metainfo to, generally ending in ".torrent",
     *     which is also the name of the torrent Must be a filesystem-safe name.
     * @param dataDir must exist, or null to default to snark data directory
     * @throws RuntimeException via Snark.fatal()
     * @return success
     * @since 0.8.4
     */
    public boolean copyAndAddTorrent(File fromfile, String filename, File dataDir)
            throws IOException {
        // prevent interference by DirMonitor
        synchronized (_snarks) {
            boolean success = FileUtil.copy(fromfile.getAbsolutePath(), filename, false);
            if (!success) {
                addMessage(_t("Failed to copy torrent file to {0}", filename));
                _log.error("Failed to write torrent file to " + filename);
                return false;
            }
            if (!areFilesPublic()) {
                SecureFileOutputStream.setPerms(new File(filename));
            }
            return addTorrent(filename, null, false, dataDir); // hold the lock for a long time
        }
    }

    /**
     * Write the metainfo to the file, caller must hold the snarks lock to prevent interference from
     * the DirMonitor.
     *
     * @param metainfo The metainfo for the torrent
     * @param filename The absolute path to save the metainfo to, generally ending in ".torrent".
     *     Must be a filesystem-safe name.
     * @since 0.8.4
     */
    private static void locked_writeMetaInfo(
            MetaInfo metainfo, String filename, boolean areFilesPublic) throws IOException {
        File file = new File(filename);
        if (file.exists()) {
            String msg = "Cannot overwrite an existing .torrent file: " + file.getPath();
            throw new IOException(msg);
        }
        try (OutputStream out = areFilesPublic ? new FileOutputStream(filename) : new SecureFileOutputStream(filename)) {
            out.write(metainfo.getTorrentData());
        } catch (IOException ioe) {
            file.delete(); // remove any partial
            throw ioe;
        }
    }

    /** Timestamp for a torrent from the metadata file. A Snark.CompleteListener method. */
    @Override
    public long getSavedTorrentTime(Snark snark) {
        Properties config = getConfig(snark);
        String time = config.getProperty(PROP_META_STAMP);
        if (time == null) {
            return 0;
        }
        return I2PSnarkUtil.parseLong(time, 0);
    }

    /**
     * Saved bitfield for a torrent from the metadata file. Convert "." to a full bitfield. A
     * Snark.CompleteListener method.
     *
     * @return the saved torrent bit field
     */
    @Override
    public BitField getSavedTorrentBitField(Snark snark) {
        MetaInfo metainfo = snark.getMetaInfo();
        if (metainfo == null) {
            return null;
        }
        Properties config = getConfig(snark);
        String bf = config.getProperty(PROP_META_BITFIELD);
        if (bf == null) {
            return null;
        }
        int len = metainfo.getPieces();
        if (bf.equals(".")) {
            BitField bitfield = new BitField(len);
            for (int i = 0; i < len; i++) {
                bitfield.set(i);
            }
            return bitfield;
        }
        byte[] bitfield = Base64.decode(bf);
        if (bitfield == null) {
            return null;
        }
        if (bitfield.length * 8 < len) {
            return null;
        }
        return new BitField(bitfield, len);
    }

    /**
     * Saved priorities for a torrent from the metadata file.
     *
     * @since 0.8.1
     */
    public void loadSavedFilePriorities(Snark snark) {
        MetaInfo metainfo = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (metainfo == null || storage == null) {
            return;
        }
        if (metainfo.getFiles() == null) {
            return;
        }
        Properties config = getConfig(snark);
        String pri = config.getProperty(PROP_META_PRIORITY);
        if (pri != null) {
            int filecount = metainfo.getFiles().size();
            int[] rv = new int[filecount];
            String[] arr = DataHelper.split(pri, ",");
            for (int i = 0; i < filecount && i < arr.length; i++) {
                if (!arr[i].isEmpty()) {
                    rv[i] = I2PSnarkUtil.parseInt(arr[i], 0);
                }
            }
            storage.setFilePriorities(rv);
        }
    }

    /**
     * Base location for a torrent from the metadata file.
     *
     * @return File or null, doesn't necessarily exist
     * @since 0.9.15
     */
    private File getSavedBaseFile(byte[] ih) {
        Properties config = getConfig(ih);
        String base = config.getProperty(PROP_META_BASE);
        if (base == null) {
            return null;
        }
        return new File(base);
    }

    /**
     * Setting for a torrent from the metadata file.
     *
     * @return setting, 0 if not found
     * @since 0.9.15
     */
    @Override
    public long getSavedUploaded(Snark snark) {
        Properties config = getConfig(snark);
        if (config != null) {
            return I2PSnarkUtil.parseLong(config.getProperty(PROP_META_UPLOADED), 0);
        }
        return 0;
    }

    /**
     * Setting for a torrent from the metadata file.
     *
     * @return non-null, rv[0] is added time or 0; rv[1] is completed time or 0
     * @since 0.9.23
     */
    public long[] getSavedAddedAndCompleted(Snark snark) {
        long[] rv = new long[2];
        Properties config = getConfig(snark);
        if (config != null) {
            rv[0] = I2PSnarkUtil.parseLong(config.getProperty(PROP_META_ADDED), 0);
            rv[1] = I2PSnarkUtil.parseLong(config.getProperty(PROP_META_COMPLETED), 0);
        }
        return rv;
    }

    /**
     * Setting for comments enabled from the metadata file. Caller must first check global
     * I2PSnarkUtil.commentsEnabled() Default true.
     *
     * @return the saved comments enabled
     * @since 0.9.31
     */
    public boolean getSavedCommentsEnabled(Snark snark) {
        boolean rv = true;
        Properties config = getConfig(snark);
        if (config != null) {
            String s = config.getProperty(PROP_META_COMMENTS);
            if (s != null) {
                rv = Boolean.parseBoolean(s);
            }
        }
        return rv;
    }

    /**
     * Setting for comments enabled in the metadata file.
     *
     * @since 0.9.31
     */
    public void setSavedCommentsEnabled(Snark snark, boolean yes) {
        saveTorrentStatus(snark, Boolean.valueOf(yes));
    }

    /**
     * Save the completion status of a torrent and other data in the metadata file.
     * Does nothing for magnets.
     *
     * @since 0.9.15
     */
    public void saveTorrentStatus(Snark snark) {
        saveTorrentStatus(snark, null);
    }

    /**
     * Save the completion status of a torrent and other data in the metadata file.
     * Does nothing for magnets.
     *
     * @param comments null for no change
     * @since 0.9.31
     */
    private void saveTorrentStatus(Snark snark, Boolean comments) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta == null || storage == null) {
            return;
        }
        saveTorrentStatus(
                meta,
                storage.getBitField(),
                storage.getFilePriorities(),
                storage.getBase(),
                snark.getUploaded(),
                storage.getActivity(),
                snark.isStopped(),
                comments);
    }

    /**
     * Save the completion status of a torrent and the current time in the metadata file.
     * The time is a standard long converted to string. The status is either a bitfield
     * converted to Base64 or "." for a completed torrent to save space in the metadata file
     * and in memory.
     *
     * @param metainfo non-null
     * @param bitfield non-null
     * @param priorities may be null
     * @param base may be null
     * @param comments null for no change
     * @since 0.9.31
     */
    private void saveTorrentStatus(
            MetaInfo metainfo,
            BitField bitfield,
            int[] priorities,
            File base,
            long uploaded,
            long activity,
            boolean stopped,
            Boolean comments) {
        synchronized (_configLock) {
            locked_saveTorrentStatus(
                    metainfo,
                    bitfield,
                    priorities,
                    base,
                    uploaded,
                    activity,
                    stopped,
                    comments);
        }
    }

    private void locked_saveTorrentStatus(
            MetaInfo metainfo,
            BitField bitfield,
            int[] priorities,
            File base,
            long uploaded,
            long activity,
            boolean stopped,
            Boolean comments) {
        byte[] ih = metainfo.getInfoHash();
        Properties config = getConfig(ih);
        String now = Long.toString(System.currentTimeMillis());
        config.setProperty(PROP_META_STAMP, now);
        if (config.getProperty(PROP_META_ADDED) == null) {
            config.setProperty(PROP_META_ADDED, now);
        }
        String bfs;
        synchronized (bitfield) {
            if (bitfield.complete()) {
                bfs = ".";
                if (config.getProperty(PROP_META_COMPLETED) == null) {
                    config.setProperty(PROP_META_COMPLETED, now);
                }
            } else {
                byte[] bf = bitfield.getFieldBytes();
                bfs = Base64.encode(bf);
                config.remove(PROP_META_COMPLETED);
            }
        }
        config.setProperty(PROP_META_BITFIELD, bfs);
        config.setProperty(PROP_META_UPLOADED, Long.toString(uploaded));
        boolean running = !stopped;
        config.setProperty(PROP_META_RUNNING, Boolean.toString(running));
        if (base != null) {
            config.setProperty(PROP_META_BASE, base.getAbsolutePath());
        }
        if (comments != null) {
            config.setProperty(PROP_META_COMMENTS, comments.toString());
        }
        if (activity > 0) {
            config.setProperty(PROP_META_ACTIVITY, Long.toString(activity));
        }

        // now the file priorities
        if (priorities != null) {
            boolean nonzero = false;
            for (int i = 0; i < priorities.length; i++) {
                if (priorities[i] != 0) {
                    nonzero = true;
                    break;
                }
            }
            if (nonzero) {
                // generate string like -5,,4,3,,,,,,-2 where no number is zero.
                StringBuilder buf = new StringBuilder(2 * priorities.length);
                for (int i = 0; i < priorities.length; i++) {
                    // only output if non-zero
                    if (priorities[i] != 0) {
                        buf.append(Integer.toString(priorities[i]));
                    }
                    if (i != priorities.length - 1) {
                        buf.append(',');
                    }
                }
                config.setProperty(PROP_META_PRIORITY, buf.toString());
            } else {
                config.remove(PROP_META_PRIORITY);
            }
        } else {
            config.remove(PROP_META_PRIORITY);
        }
        // magnet properties, no longer apply, we have the metainfo
        config.remove(PROP_META_MAGNET);
        config.remove(PROP_META_MAGNET_DIR);
        config.remove(PROP_META_MAGNET_DN);
        config.remove(PROP_META_MAGNET_TR);

        // TODO save closest DHT nodes too
        locked_saveTorrentStatus(ih, config);
    }

    /**
     * Save per-torrent config to the single metadata file.
     *
     * @since 0.9.23
     */
    private void locked_saveTorrentStatus(byte[] ih, Properties config) {
        synchronized (_configLock) {
            String prefix = metaKey(ih, "");
            // Clear old entries for this infohash
            Set<String> toRemove = new HashSet<>(8);
            for (String key : _metadata.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    toRemove.add(key);
                }
            }
            for (String key : toRemove) {
                _metadata.remove(key);
            }
            // Write new entries
            for (String key : config.stringPropertyNames()) {
                _metadata.setProperty(prefix + key, config.getProperty(key));
            }
            saveMetadata();
            _configCache.remove(new SHA1Hash(ih));
            if (_log.shouldDebug()) {
                _log.debug("Saved metadata for " + I2PSnarkUtil.toHex(ih));
            }
        }
    }

    /**
     * Remove the status of a torrent from the metadata file.
     *
     * @since 0.9.20
     */
    private void removeTorrentStatus(Snark snark) {
        byte[] ih = snark.getInfoHash();
        SHA1Hash hash = new SHA1Hash(ih);
        File comm = commentFile(ih);
        synchronized (_configLock) {
            comm.delete();
            // Purge all metadata entries for this infohash
            String prefix = metaKey(ih, "");
            Set<String> toRemove = new HashSet<>(8);
            for (String key : _metadata.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    toRemove.add(key);
                }
            }
            for (String key : toRemove) {
                _metadata.remove(key);
            }
            saveMetadata();
            _configCache.remove(hash);
            if (_log.shouldInfo()) {
                _log.info("Purged metadata for " + snark.getName());
            }
        }
    }

    /**
     * Remove metadata entries for torrents no longer loaded.
     * Run once at startup.
     *
     * @since 0.9.20
     */
    private void cleanupTorrentStatus() {
        Set<SHA1Hash> torrents = new HashSet<>(32);
        synchronized (_snarks) {
            for (Snark snark : _snarks.values()) {
                torrents.add(new SHA1Hash(snark.getInfoHash()));
            }
        }
        int totalDeleted = 0;
        synchronized (_configLock) {
            Set<String> toRemove = new HashSet<>(8);
            for (String key : _metadata.stringPropertyNames()) {
                if (key.startsWith(META_PREFIX)) {
                    // Extract infohash hex: zmeta.<hex>.prop -> <hex>
                    int dot = key.indexOf('.', META_PREFIX.length());
                    if (dot < 0) {
                        continue;
                    }
                    String hex = key.substring(META_PREFIX.length(), dot);
                    if (hex.length() != 40) {
                        continue;
                    }
                    byte[] ih = new byte[20];
                    try {
                        for (int j = 0; j < 20; j++) {
                            ih[j] = (byte) (Integer.parseInt(hex.substring(j * 2, (j * 2) + 2), 16) & 0xff);
                        }
                    } catch (NumberFormatException nfe) {
                        toRemove.add(key);
                        continue;
                    }
                    if (!torrents.contains(new SHA1Hash(ih))) {
                        toRemove.add(key);
                    }
                }
            }
            for (String key : toRemove) {
                _metadata.remove(key);
                totalDeleted++;
            }
            if (totalDeleted > 0) {
                saveMetadata();
            }
        }
        if (totalDeleted > 0) {
            String msg = "Metadata cleaner removed " + totalDeleted
                    + " orphaned torrent " + (totalDeleted > 1 ? "entries" : "entry");
            if (_log.shouldInfo()) {
                _log.info(msg);
            }
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg);
            }
        }
    }

    /**
     * Just remember we have it. Stores the magnet info in the metadata file
     * so we can remember the directory, tracker, etc.
     *
     * @param dir may be null
     * @param trackerURL may be null
     * @param dn may be null
     * @since 0.8.4
     */
    public void saveMagnetStatus(byte[] ih, String dir, String trackerURL, String dn) {
        // i2psnark.config file
        String infohash = Base64.encode(ih);
        infohash = infohash.replace('=', '$');
        _config.setProperty(PROP_META_MAGNET_PREFIX + infohash, ".");
        // metadata file
        Properties config = new OrderedProperties();
        config.setProperty(PROP_META_MAGNET, "true");
        if (dir != null) {
            config.setProperty(PROP_META_MAGNET_DIR, dir);
        }
        if (trackerURL != null) {
            config.setProperty(PROP_META_MAGNET_TR, trackerURL);
        }
        if (dn != null) {
            config.setProperty(PROP_META_MAGNET_DN, dn);
        }
        String now = Long.toString(System.currentTimeMillis());
        config.setProperty(PROP_META_ADDED, now);
        config.setProperty(PROP_META_STAMP, now);
        config.setProperty(PROP_META_RUNNING, "true");
        // save
        synchronized (_configLock) {
            saveConfig();
            locked_saveTorrentStatus(ih, config);
        }
    }

    /**
     * Remove the magnet marker from the config.
     *
     * @since 0.8.4
     */
    public void removeMagnetStatus(byte[] ih) {
        String infohash = Base64.encode(ih);
        infohash = infohash.replace('=', '$');
        if (_config.remove(PROP_META_MAGNET_PREFIX + infohash) != null) {
            saveConfig();
        }
    }

    /**
     * Count the files in a metainfo file list for the max files per torrent
     * check, excluding BEP 47 padding files (.pad) and parse-renamed padding
     * (_pad); a padded torrent must not be rejected because of its synthetic
     * zero files. Padding is only recognized at the top level, matching the
     * BEP 47 layout and Storage's create-side handling.
     *
     * @param files from MetaInfo.getFiles(), may be null
     * @return the number of non-padding files
     */
    static int countRealFiles(List<List<String>> files) {
        if (files == null) {
            return 0;
        }
        int count = files.size();
        for (List<String> f : files) {
            if (Storage.isPadDir(f.get(0))) {
                count--;
            }
        }
        return count;
    }

    /**
     * Does not really delete on failure, that's the caller's responsibility. Warning - does not
     * validate announce URL - use TrackerClient.isValidAnnounce()
     *
     * @return failure message or null on success
     */
    private String validateTorrent(MetaInfo info) {
        List<List<String>> files = info.getFiles();
        int fileCount = files != null ? countRealFiles(files) : 0;
        if (fileCount > _util.getMaxFilesPerTorrent()) {
            return _t("Too many files in \"{0}\" ({1})!", info.getName(), fileCount)
                    + " - limit is "
                    + _util.getMaxFilesPerTorrent()
                    + ", zip them or set "
                    + PROP_MAX_FILES_PER_TORRENT
                    + '='
                    + fileCount
                    + " in "
                    + _configFile.getAbsolutePath()
                    + " and restart";
        } else if ((files == null) && (info.getName().endsWith(".torrent"))) {
            return _t("Torrent file \"{0}\" cannot end in \".torrent\"!", info.getName());
        } else if (info.getPieces() <= 0) {
            return _t("No pieces in \"{0}\"!", info.getName());
        } else if (info.getPieces() > Storage.MAX_PIECES) {
            return _t(
                    "Too many pieces in \"{0}\", limit is {1}!",
                    info.getName(), Storage.MAX_PIECES);
        } else if (info.getPieceLength(0) > Storage.MAX_PIECE_SIZE) {
            return _t(
                            "Pieces are too large in \"{0}\" ({1}B)!",
                            info.getName(), DataHelper.formatSize2(info.getPieceLength(0)))
                    + ' '
                    + _t("Limit is {0}B", DataHelper.formatSize2(Storage.MAX_PIECE_SIZE));
        } else if (info.getTotalLength() <= 0) {
            return _t("Torrent \"{0}\" has no data!", info.getName());
        } else if (info.getTotalLength() > Storage.MAX_TOTAL_SIZE) {
            return _t(
                    "Torrents larger than {0}B are not supported yet \"{1}\"!",
                    Storage.MAX_TOTAL_SIZE, info.getName());
        } else {
            return null;
        } // ok
    }

    /**
     * Stop the torrent, leaving it on the list of torrents unless told to remove it. If
     * shouldRemove is true, removes the torrent's metadata entries also.
     */
    public Snark stopTorrent(String filename, boolean shouldRemove) {
        File sfile = new File(filename);
        try {
            filename = sfile.getCanonicalPath();
        } catch (IOException ioe) {
            _log.error("Unable to remove the torrent " + filename, ioe);
            addMessage(
                    _t("Error: Could not remove the torrent {0}", filename)
                            + ": "
                            + ioe.getLocalizedMessage());
            return null;
        }
        Snark torrent = null;
        synchronized (_snarks) {
            if (shouldRemove) {
                torrent = removeSnark(filename);
            } else {
                torrent = _snarks.get(filename);
            }
        }
        if (torrent != null) {
            boolean wasStopped = torrent.isStopped();
            torrent.stopTorrent();
            if (shouldRemove) {
                removeTorrentStatus(torrent);
            }
            if (!wasStopped) {
                addMessageNoEscape(
                        _t("Torrent stopped: {0}", linkify(torrent).replace("Magnet ", "")));
            }
        }
        return torrent;
    }

    /**
     * Stop the torrent, leaving it on the list of torrents unless told to remove it. If
     * shouldRemove is true, removes the torrent's metadata entries also.
     *
     * @since 0.8.4
     */
    public void stopTorrent(Snark torrent, boolean shouldRemove) {
        if (torrent != null) {
            if (shouldRemove) {
                synchronized (_snarks) {
                    removeSnark(torrent);
                }
            }
            boolean wasStopped = torrent.isStopped();
            if (!wasStopped) {
                torrent.stopTorrent();
            }
            if (shouldRemove) {
                removeTorrentStatus(torrent);
            }
        }
    }

    /**
     * Stop the torrent only, leaving it on the list of torrents.
     *
     * @since 0.9.67+
     */
    public void stopTorrent(Snark torrent) {
        if (torrent != null) {
            boolean wasStopped = torrent.isStopped();
            if (!wasStopped) {
                addMessageNoEscapeAndPrint(
                        _t("Torrent stopped: {0}", linkify(torrent).replace("Magnet ", "")),
                        _t("Torrent stopped: {0}", getSnarkName(torrent)));
                stopTorrent(torrent, false);
            }
        }
    }

    /**
     * Stop the torrent and delete the torrent file itself, but leaving the data behind. Removes
     * saved metadata entries also. Holds the snarks lock to prevent interference from the DirMonitor.
     */
    public void removeTorrent(String filename) {
        Snark torrent;
        // prevent interference by DirMonitor
        synchronized (_snarks) {
            torrent = stopTorrent(filename, true);
            if (torrent == null) {
                return;
            }
            File torrentFile = new File(filename);
            torrentFile.delete();
        }
        addMessageAndPrint(
                _t("Torrent removed: {0}", torrent.getBaseName()),
                _t("Torrent removed: {0}", getSnarkName(torrent)));
    }

    /**
     * This calls monitorTorrents() once a minute. It also gets the bandwidth limits and loads
     * magnets on first run. For standalone, it also handles checking that the external router is
     * there, and restarting torrents once the router appears.
     */
    private class DirMonitor implements Runnable {
        public void run() {
            File dataDir = getDataDir();
            File torrentDir = getTorrentDir();
            getStorageSpace(dataDir);
            long delay =
                    (60L * 1000)
                            * getStartupDelayMinutes(); // Don't bother delaying if auto start is
                                                        // false
            boolean autostart = shouldAutoStart();
            if (delay == 0) {
                delay = 30000;
            }
            if (delay > 30000 && autostart) {
                // Build the shared session's tunnels during the startup delay,
                // so the batch can start as soon as the delay elapses instead
                // of waiting on the lease set afterwards
                (new I2PAppThread(
                                new Runnable() {
                                    public void run() {
                                        _util.connect();
                                    }
                                },
                                "SnarkPreConnect",
                                true))
                        .start();
                // Make torrents visible in the UI immediately; only their
                // auto-start waits out the configured delay (tunnels are
                // pre-built by SnarkPreConnect during this window). The
                // countdown is added after this pass so clearing it below
                // does not wipe the per-torrent "added" notifications.
                List<Snark> earlyAdded;
                try {
                    synchronized (_snarks) {
                        earlyAdded = monitorTorrents(torrentDir);
                    }
                } catch (RuntimeException e) {
                    _log.error("Error in the DirectoryMonitor", e);
                    earlyAdded = Collections.emptyList();
                }
                int id =
                        _messages.addMessageNoEscape(
                                getTime()
                                        + "&nbsp; "
                                        + _t(
                                                "Starting torrents in {0}" + "&hellip;",
                                                DataHelper.formatDuration2(delay)));
                sleep(delay);
                _messages.clearThrough(id); // Remove just the countdown
                if (!earlyAdded.isEmpty()) {
                    startBatch(previouslyRunning(earlyAdded));
                }
            } else if (_context.isRouterContext()) {
                // Wait for client manager to be up so we can get bandwidth limits
                sleep(3000);
            }
            // Immediate first add pass for the non-deferred paths; the loop's
            // first pass below then typically finds nothing new. Deferred mode
            // already added above so torrents are visible during the wait.
            if (!(delay > 30000 && autostart)) {
                List<Snark> earlyAdded;
                try {
                    synchronized (_snarks) {
                        earlyAdded = monitorTorrents(torrentDir);
                    }
                } catch (RuntimeException e) {
                    _log.error("Error in the DirectoryMonitor", e);
                    earlyAdded = Collections.emptyList();
                }
                if (autostart && !earlyAdded.isEmpty()) {
                    startBatch(earlyAdded);
                }
            }
            // Here because we need to delay until I2CP is up although the user will see the default
            // until then
            boolean routerOK = false;
            boolean doMagnets = true;

            String bwMsg =
                    _t("Down bandwidth limit is {0} KB/s", _bwManager.getDownBWLimit() / 1024)
                            + "; "
                            + _t("Up bandwidth limit is {0} KB/s", _util.getMaxUpBW());
            addMessageAndPrint(bwMsg);

            while (_running) {
                torrentDir = getTorrentDir();
                String i2cpConnectMsg =
                        " • "
                                + _t(
                                        "Connecting to I2CP port on I2P instance at {0}",
                                        _util.getI2CPHost() + ':' + _util.getI2CPPort() + "...");
                if (_log.shouldDebug()) {
                    _log.debug(
                            "DirectoryMonitor scanning I2PSnark torrent dir: "
                                    + torrentDir.getAbsolutePath());
                }
                if (routerOK) {
                    if (_context.isRouterContext() || _util.connected() || _util.isConnecting()) {
                        autostart = shouldAutoStart();
                    }
                } else {
                    // Test if the router is there
                    // For standalone, this will probe the router every 60 seconds if not connected
                    boolean oldOK = routerOK;
                    // standalone, first time only
                    if (doMagnets && !_context.isRouterContext()) {
                        System.out.println(i2cpConnectMsg);
                    }
                    routerOK = getBWLimit();
                    if (routerOK) {
                        autostart = shouldAutoStart();
                        if (autostart && !oldOK && !doMagnets && !_snarks.isEmpty()) {
                            // Start previously added torrents; all of them are
                            // known here, so pool-mates can be started together
                            List<Snark> batch = new ArrayList<>(0);
                            for (Snark snark : _snarks.values()) {
                                Properties config = getConfig(snark);
                                String prop = config.getProperty(PROP_META_RUNNING);
                                if (prop == null || Boolean.parseBoolean(prop)) {
                                    batch.add(snark);
                                }
                            }
                            if (!startBatch(batch)) {
                                routerOK = false;
                                autostart = false;
                            }
                        }
                    } else {
                        autostart = false;
                    }
                }
                List<Snark> added;
                boolean ok = true;
                try {
                    // Don't let this interfere with .torrent files being added or deleted
                    synchronized (_snarks) {
                        added = monitorTorrents(torrentDir);
                    }
                } catch (RuntimeException e) {
                    _log.error("Error in the DirectoryMonitor", e);
                    added = Collections.emptyList();
                    ok = false;
                }
                if (autostart && !added.isEmpty()) {
                    // Start OUTSIDE the _snarks lock: startBatch staggers pool
                    // startups with multi-second sleeps that must never block
                    // torrent lookups, or the UI stalls for minutes.
                    // Only torrents that were running when last saved are
                    // restarted; user-stopped ones stay stopped.
                    startBatch(previouslyRunning(added));
                }
                if (doMagnets) {
                    // first run only
                    try {
                        addMagnets(autostart);
                        doMagnets = false;
                    } catch (RuntimeException e) {
                        _log.error("Error in the DirectoryMonitor", e);
                    }

                    if (routerOK && !_snarks.isEmpty()) {
                        addMessage(
                                _t(
                                        "Upload bandwidth limit is {0} KBps to a maximum of {1}"
                                            + " concurrent peers.",
                                        _util.getMaxUpBW(), _util.getMaxUploaders()));
                    }
                    /*
                     * To fix bug where files were left behind, but also good for when user removes snarks when i2p is not running
                     *
                     * Don't run if there was an error, as we would delete the torrent metadata and we don't want to do that.
                     * We'll do the cleanup the next time i2psnark starts. See ticket #1658.
                     */
                    if (ok) {
                        cleanupTorrentStatus();
                    }
                    if (!routerOK) {
                        if (_context.isRouterContext()) {
                            addMessage(_t("Unable to connect to I2P"));
                        } else {
                            String msg =
                                    _t("Error connecting to I2P - check your I2CP settings!")
                                            + ' '
                                            + _util.getI2CPHost()
                                            + ':'
                                            + _util.getI2CPPort();
                            addMessageAndPrint(msg);
                        }
                    }
                }
                // Polling period for scanning data dir for new content
                rereadConfig();
                sleep((long) 30 * 1000);
            }
        }
    }

    private void getStorageSpace(File dir) {
        long freeSpace = dir.getUsableSpace();
        double freeSpaceGB = freeSpace / (1024.0 * 1024 * 1024);
        int freeSpaceMB = (int) (freeSpace / (1024 * 1024));

        DecimalFormat df = new DecimalFormat("#.#");
        String msg;
        if (freeSpaceMB > 1024) {
            msg =
                    _t(
                            "Storage: {0}GB currently available for downloads on configured data"
                                + " partition",
                            df.format(freeSpaceGB));
        } else {
            msg =
                    _t(
                            "Storage: {0}MB currently available for downloads on configured data"
                                + " partition",
                            freeSpaceMB);
        }

        if (freeSpaceMB < 100) {
            msg =
                    _t(
                            "Warning - Only {0}MB available for downloads on configured data"
                                + " partition",
                            freeSpaceMB);
            if (_log.shouldWarn()) {
                _log.warn(
                        "[I2PSnark] Partition containing data directory only has "
                                + freeSpaceMB
                                + "MB free");
            }
        }

        addMessageAndPrint(msg);

        // Also report space on the staging partition when it differs from the data partition
        String tempDir = getTempDirProp();
        if (tempDir != null) {
            checkTempDirSpace(new File(tempDir));
        }
    }

    /**
     * Checks free space on the partition containing the staging (temp)
     * directory and warns when it is low. Incomplete files are written there,
     * so a full temp partition stalls all downloads.
     *
     * @param dir the staging directory
     * @since 0.9.71+
     */
    private void checkTempDirSpace(File dir) {
        long freeSpace = dir.getUsableSpace();
        double freeSpaceGB = freeSpace / (1024.0 * 1024 * 1024);
        int freeSpaceMB = (int) (freeSpace / (1024 * 1024));

        DecimalFormat df = new DecimalFormat("#.#");
        String msg;
        if (freeSpaceMB > 1024) {
            msg =
                    _t(
                            "Temp storage: {0}GB currently available for downloads on configured"
                                + " temp partition",
                            df.format(freeSpaceGB));
        } else {
            msg =
                    _t(
                            "Temp storage: {0}MB currently available for downloads on configured"
                                + " temp partition",
                            freeSpaceMB);
        }

        if (freeSpaceMB < 100) {
            msg =
                    _t(
                            "Warning - Only {0}MB available for downloads on configured temp"
                                + " partition",
                            freeSpaceMB);
            if (_log.shouldWarn()) {
                _log.warn(
                        "[I2PSnark] Partition containing temp directory only has "
                                + freeSpaceMB
                                + "MB free");
            }
        }

        addMessageAndPrint(msg);
    }

    // Begin Snark.CompleteListeners

    /** A Snark.CompleteListener method. */
    @Override
    public void torrentComplete(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        int pieces = snark.getPieces();
        boolean isComplete = pieces >= snark.getNeeded() && snark.getRemainingLength() == 0;
        if (meta == null || storage == null || snark == null || !isComplete) {
            return;
        }

        if (snark.isStorageCompleted() && isComplete && !snark.isNotificationSent()) {
            addMessageNoEscapeAndPrint(
                    _t("Download finished: {0}", linkify(snark)),
                    _t("Download finished: {0}", getSnarkName(snark)));
            snark.setNotificationSent(true);
            ClientAppManager cmgr = _context.clientAppManager();
            if (cmgr != null) {
                NotificationService ns = (NotificationService) cmgr.getRegisteredApp("desktopgui");
                if (ns != null) {
                    ns.notify(
                            "I2PSnark",
                            null,
                            Log.INFO,
                            _t("I2PSnark"),
                            _t("Download finished: {0}", snark.getName()),
                            "/i2psnark/" + linkify(snark));
                }
            }
            updateStatus(snark);
        }
    }

    /** A Snark.CompleteListener method. */
    @Override
    public void updateStatus(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta != null && storage != null) {
            // Skip persistence for lookup torrents (zzzot name resolution)
            String name = snark.getName();
            if (name != null && (name.startsWith("lookup-") || name.contains("zzzot-lookup")))
                return;
            try {
                File base = storage.getBase();
                if (base != null && base.getPath().contains("zzzot-lookup"))
                    return;
            } catch (Exception ignore) {}
            saveTorrentStatus(
                    meta,
                    storage.getBitField(),
                    storage.getFilePriorities(),
                    storage.getBase(),
                    snark.getUploaded(),
                    storage.getActivity(),
                    snark.isStopped(),
                    (Boolean) null);
        }
    }

    /**
     * We transitioned from magnet mode, we have now initialized our metainfo and storage. The
     * listener should now call getMetaInfo() and save the data to disk. A Snark.CompleteListener
     * method.
     *
     * @return the new name for the torrent or null on error
     * @since 0.8.4
     */
    @Override
    public String gotMetaInfo(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta != null && storage != null) {
            // Skip persistence for lookup torrents (zzzot name resolution).
            // Lookup torrents are created with name "lookup-*" in a temp dir "zzzot-lookup-*".
            // We must not write a .torrent file, save config, or re-register under a new name,
            // as the lookup's finally block will delete the snark. Without this check,
            // gotMetaInfo() would promote the snark into the main data dir and the cleanup
            // would fail to recognize it, leaving a persistent downloading torrent.
            // Return null so Snark.gotMetaInfo() keeps the "lookup-*" name intact — if we
            // returned the real name, deleteMagnet() would fail to remove the snark from
            // _snarks (key mismatch) and leave a stale entry.
            String snarkName = snark.getName();
            if (snarkName != null && (snarkName.startsWith("lookup-") || snarkName.contains("zzzot-lookup"))) {
                if (_log.shouldInfo()) {
                    _log.info("gotMetaInfo skipping persistence for lookup torrent: " + snarkName);
                }
                return null;
            }
            try {
                File base = storage.getBase();
                if (base != null && base.getPath().contains("zzzot-lookup")) {
                    if (_log.shouldInfo()) {
                        _log.info("gotMetaInfo skipping persistence for lookup torrent (storage in temp dir): " + snarkName);
                    }
                    return null;
                }
            } catch (Exception ignore) {}
            String rejectMessage = validateTorrent(meta);
            if (rejectMessage != null) {
                addMessage(rejectMessage);
                snark.stopTorrent();
                return null;
            }
            saveTorrentStatus(
                    meta,
                    storage.getBitField(),
                    null,
                    storage.getBase(),
                    0,
                    0,
                    snark.isStopped(),
                    (Boolean) null);
            // temp for addMessage() in case canonical throws
            String name = storage.getBaseName();
            try {
                // _snarks must use canonical
                name =
                        (new File(getTorrentDir(), storage.getBaseName() + ".torrent"))
                                .getCanonicalPath();
                File nameFile = new File(name);
                File parent = nameFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                // put the announce URL in the file
                String announce = snark.getTrackerURL();
                if (announce != null) {
                    meta = meta.reannounce(announce);
                }
                synchronized (_snarks) {
                    locked_writeMetaInfo(meta, name, areFilesPublic());
                    // put it in the list under the new name
                    removeSnark(snark);
                    putSnark(name, snark);
                }
                _magnets.remove(snark.getName());
                removeMagnetStatus(snark.getInfoHash());
                addMessageNoEscape(_t("Starting torrent: {0}", linkify(snark)));
                return name;
            } catch (IOException ioe) {
                addMessage(_t("Failed to copy torrent file to {0}", name));
                _log.error("Failed to write torrent file", ioe);
            }
        }
        return null;
    }

    /**
     * A Snark.CompleteListener method.
     *
     * @since 0.9
     */
    @Override
    public void fatal(Snark snark, String error) {
        addMessage(error);
    }

    /**
     * A Snark.CompleteListener method.
     *
     * @since 0.9.2
     */
    @Override
    public void addMessage(Snark snark, String message) {
        addMessage(message);
    }

    /**
     * A Snark.CompleteListener method.
     *
     * @since 0.9.4
     */
    @Override
    public void gotPiece(Snark snark) { /* no-op */ }

    // End Snark.CompleteListeners

    /**
     * An HTML link to the file if complete and a single file, to the directory if not complete or
     * not a single file, or simply the unlinkified name of the snark if a magnet
     *
     * @since 0.9.23
     */
    private String linkify(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta == null || storage == null) {
            return DataHelper.escapeHTML(snark.getBaseName().replace("%20", " "));
        }
        StringBuilder buf = new StringBuilder(256);
        String base = DataHelper.escapeHTML(storage.getBaseName());
        String enc =
                base.replace("[", "%5B")
                        .replace("]", "%5D")
                        .replace("|", "%7C")
                        .replace(" ", "%20")
                        .replace("è", "&egrave;")
                        .replace("é", "&eacute;")
                        .replace("à", "&agrave;");
        buf.append("<a href=\"").append(_contextPath).append('/').append(enc);
        if (meta.getFiles() != null || !storage.complete()) {
            buf.append('/');
        }
        buf.append("\">")
                .append(
                        base.replace("%20", " ")
                                .replace("&egrave;", "è")
                                .replace("&eacute;", "é")
                                .replace("&agrave;", "à"))
                .append("</a>");
        return buf.toString();
    }

    /**
     * Returns the Snark name with URL-encoded spaces replaced by regular spaces. This method is
     * intended for use when sending the Snark name to a terminal log (standalone)
     *
     * @param snark The Snark object to retrieve the name from.
     * @return The Snark name suitable for display in a terminal log.
     */
    private String getSnarkName(Snark snark) {
        String baseName = snark.getBaseName();
        String snarkName = baseName.replace("%20", " ");
        return snarkName;
    }

    /**
     * Add all magnets from the config file
     *
     * @since 0.8.4
     */
    private void addMagnets(boolean autostart) {
        boolean changed = false;
        List<String> keys = new ArrayList<>(0);
        for (Iterator<?> iter = _config.keySet().iterator(); iter.hasNext(); ) {
            String k = (String) iter.next();
            if (k.startsWith(PROP_META_MAGNET_PREFIX)) {
                String b64 = k.substring(PROP_META_MAGNET_PREFIX.length());
                b64 = b64.replace('$', '=');
                byte[] ih = Base64.decode(b64);
                // ignore value - TODO put tracker URL in value
                if (ih != null && ih.length == 20) {
                    keys.add(k);
                } else {
                    iter.remove();
                    changed = true;
                }
            }
        }
        // Collect all the entries first, so none starts until the whole batch
        // is known; then randomize the pool start order so pool-mates start
        // together. In single-dest mode everything is in one pool, so this
        // randomizes the whole batch's start order
        List<String> ordered = keys;
        if (autostart) {
            Map<Integer, List<String>> byPool = new HashMap<>(keys.size() / 2);
            for (String k : keys) {
                String b64 = k.substring(PROP_META_MAGNET_PREFIX.length());
                b64 = b64.replace('$', '=');
                byte[] ih = Base64.decode(b64);
                int pool = _util.getPoolIndex(ih);
                List<String> members = byPool.get(pool);
                if (members == null) {
                    members = new ArrayList<>(0);
                    byPool.put(pool, members);
                }
                members.add(k);
            }
            List<List<String>> pools = new ArrayList<>(byPool.values());
            Collections.shuffle(pools, _context.random());
            ordered = new ArrayList<>(keys.size());
            for (List<String> members : pools) {
                Collections.shuffle(members, _context.random());
                ordered.addAll(members);
            }
        }
        int started = 0;
        Set<Integer> seenPools = new HashSet<>(0);
        for (String k : ordered) {
            String b64 = k.substring(PROP_META_MAGNET_PREFIX.length());
            b64 = b64.replace('$', '=');
            byte[] ih = Base64.decode(b64);
            Properties config = getConfig(ih);
            String name = config.getProperty(PROP_META_MAGNET_DN);
            if (name == null) {
                name = _t("Magnet") + ' ' + I2PSnarkUtil.toHex(ih);
            }
            String tracker = config.getProperty(PROP_META_MAGNET_TR);
            String dir = config.getProperty(PROP_META_MAGNET_DIR);
            File dirf = (dir != null) ? (new File(dir)) : null;
            if (autostart) {
                int pool = _util.getPoolIndex(ih);
                boolean newPool = (pool < 0) || seenPools.add(pool);
                if (started++ > 0 && newPool) {
                    multiDestStartDelay();
                }
            }
            addMagnet(name, ih, tracker, false, autostart, dirf, this);
        }
        if (changed) {
            saveConfig();
        }
    }

    /**
     * Add torrents found in the given directory. Caller must synchronize on
     * _snarks, and must keep the critical section short - the returned batch
     * is started by the caller AFTER releasing the lock, since starting
     * staggers pools with long sleeps.
     *
     * @return the newly added torrents; empty if none were added, or null
     *         if a fatal error aborted the pass
     */
    private List<Snark> monitorTorrents(File dir) {
        File[] files = dir.listFiles(new FileSuffixFilter(".torrent"));
        List<String> foundNames = new ArrayList<>(0);
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                try {
                    foundNames.add(files[i].getCanonicalPath());
                } catch (IOException ioe) {
                    _log.error("Error resolving '" + files[i] + "' in '" + dir, ioe);
                }
            }
            // sort so the initial startup goes in natural order, more or less
            Collections.sort(foundNames, Collator.getInstance());
        }

        Set<String> existingNames = listTorrentFiles();
        // let's find new ones first...
        // Add every new torrent without starting it: nothing may start until
        // the whole batch is known, so pool-mates can be started together
        List<Snark> added = new ArrayList<>(0);
        for (String name : foundNames) {
            if (existingNames.contains(name)) { /* ignored */ } // already known. noop
            else {
                boolean ok = false;
                try {
                    // don't let one bad torrent kill the whole loop
                    ok = addTorrent(name, null, true);
                    if (!ok) {
                        addMessage(_t("Error: Could not add torrent: {0}", name));
                        _log.error("Unable to add torrent: " + name);
                        disableTorrentFile(name);
                    }
                } catch (Snark.RouterException e) {
                    addMessage(
                            _t("Error: Could not add torrent: {0}", name) + ": " + e.getMessage());
                    _log.error("Unable to add torrent: " + name + "\n* Reason: " + e.getMessage());
                    // fatal: abort the pass; null tells the caller to skip
                    // both cleanup and autostart for this cycle
                    return null;
                } catch (RuntimeException e) {
                    addMessage(
                            _t("Error: Could not add torrent: {0}", name) + ": " + e.getMessage());
                    _log.error("Unable to add torrent: " + name + "\n* Reason: " + e.getMessage());
                    disableTorrentFile(name);
                }
                if (ok) {
                    Snark snark = getTorrent(name);
                    if (snark != null) {
                        added.add(snark);
                    }
                }
            }
        }
        // Don't remove magnet torrents that don't have a torrent file yet
        existingNames.removeAll(_magnets);
        // now let's see which ones have been removed...
        for (String name : existingNames) {
            if (foundNames.contains(name)) { /* ignored */ } // known and still there.  noop
            else { // known, but removed.  drop it
                try {
                    // Snark.fatal() throws a RuntimeException
                    // don't let one bad torrent kill the whole loop
                    stopTorrent(name, true);
                } catch (RuntimeException e) { /* ignored */ } // don't bother with message
            }
        }
        return added;
    }

    /** Translate the given string. */
    private String _t(String s) {
        return _util.getString(s);
    }

    /** Translate the given string with one substitution. */
    private String _t(String s, Object o) {
        return _util.getString(s, o);
    }

    /** Translate the given string with two substitutions. */
    private String _t(String s, Object o, Object o2) {
        return _util.getString(s, o, o2);
    }

    /**
     * Unsorted map of name to Tracker object Modifiable, not a copy
     *
     * @return the tracker map
     * @since 0.9.1
     */
    public Map<String, Tracker> getTrackerMap() {
        return _trackerMap;
    }

    /**
     * Unsorted map of name to TorrentCreateFilter object Modifiable, not a copy
     *
     * @return the torrent create filter map
     * @since 0.9.62+
     */
    public Map<String, TorrentCreateFilter> getTorrentCreateFilterMap() {
        return _torrentCreateFilterMap;
    }

    /**
     * Returns the current number of configured file filters
     *
     * @return the create filter count
     * @since 0.9.62+
     */
    public int getCreateFilterCount() {
        return _torrentCreateFilterMap.size();
    }

    /** Unsorted, do not modify */
    public Collection<Tracker> getTrackers() {
        return _trackerMap.values();
    }

    /**
     * Unsorted, do not modify
     *
     * @return the torrent create filter strings
     * @since 0.9.62+
     */
    public Collection<TorrentCreateFilter> getTorrentCreateFilterStrings() {
        return _torrentCreateFilterMap.values();
    }

    /**
     * Sorted copy
     *
     * @return the sorted trackers
     * @since 0.9.1
     */
    public List<Tracker> getSortedTrackers() {
        List<Tracker> rv = new ArrayList<>(_trackerMap.values());
        if (!_util.udpEnabled()) {
            for (Iterator<Tracker> iter = rv.iterator(); iter.hasNext(); ) {
                Tracker tr = iter.next();
                if (tr.announceURL.startsWith("udp://")) {
                    iter.remove();
                }
            }
        }
        Collections.sort(rv, new IgnoreCaseComparator());
        return rv;
    }

    /**
     * Sorted copy
     *
     * @return the sorted torrent create filter strings
     * @since 0.9.62+
     */
    public List<TorrentCreateFilter> getSortedTorrentCreateFilterStrings() {
        List<TorrentCreateFilter> fv =
                new ArrayList<>(_torrentCreateFilterMap.values());
        Collections.sort(fv, new IgnoreCaseComparatorF());
        return fv;
    }

    /**
     * Has the default tracker list been modified?
     *
     * @return whether modified trackers is present
     * @since 0.9.35
     */
    public boolean hasModifiedTrackers() {
        return _config.containsKey(PROP_TRACKERS);
    }

    /**
     * @since 0.9
     */
    private void initTrackerMap() {
        String trackers = _config.getProperty(PROP_TRACKERS);
        if ((trackers == null) || (trackers.trim().length() <= 0))
            trackers = _context.getProperty(PROP_TRACKERS);
        if ((trackers == null) || (trackers.trim().length() <= 0)) {
            setDefaultTrackerMap(true);
        } else {
            String[] toks = DataHelper.split(trackers, ",");
            for (int i = 0; i < toks.length; i += 2) {
                String name = toks[i].trim().replace("&#44;", ",");
                String url = toks[i + 1].trim().replace("&#44;", ",");
                if ((!name.isEmpty()) && (!url.isEmpty())) {
                    String[] urls = DataHelper.split(url, "=", 2);
                    String url2 = urls.length > 1 ? urls[1] : "";
                    _trackerMap.put(name, new Tracker(name, urls[0], url2));
                }
            }
        }
    }

    /**
     * @since 0.9.62+
     */
    private void convertFiltersToNewConfig() {
        String torrentCreateFilters = _config.getProperty(PROP_TORRENT_CREATE_FILTERS);
        if ((torrentCreateFilters == null) || (torrentCreateFilters.trim().length() <= 1)) {
            return;
        }
        String[] toks = DataHelper.split(torrentCreateFilters, ",");
        for (int i = 0; i < toks.length; i += 2) {
            String name = toks[i].trim().replace("&#44;", ",");
            String filterPattern = toks[i + 1].trim().replace("&#44;", ",");
            if ((!name.isEmpty()) && (!filterPattern.isEmpty())) {
                String[] data = DataHelper.split(filterPattern, "=", 2);
                boolean isDefault = data.length > 1 ? true : false;
                _torrentCreateFilterMap.put(
                        name, new TorrentCreateFilter(name, data[0], "contains", isDefault));
            }
        }
        saveTorrentCreateFilterMap();
    }

    /**
     * @since 0.9.62+
     */
    private void initTorrentCreateFilterMap() {
        String torrentCreateFilters = _config.getProperty(PROP_TORRENT_CREATE_FILTERS);
        if (!((torrentCreateFilters == null) || (torrentCreateFilters.trim().length() <= 0))) {
            convertFiltersToNewConfig();
            _config.remove(PROP_TORRENT_CREATE_FILTERS);
            saveConfig();
            return;
        }

        File f = new File(_configDir + "/" + PROP_TORRENT_FILTERS_CONFIG);
        if (!f.exists()) {
            setDefaultTorrentCreateFilterMap(true);
            return;
        }

        try (FileInputStream file =
                    new FileInputStream(_configDir + "/" + PROP_TORRENT_FILTERS_CONFIG);
             BufferedInputStream buf = new BufferedInputStream(file);
             ObjectInputStream in = new ObjectInputStream(buf)) {
            @SuppressWarnings("unchecked")
            Map<String, TorrentCreateFilter> filterMap =
                    (Map<String, TorrentCreateFilter>) in.readObject();
            for (Map.Entry<String, TorrentCreateFilter> entry : filterMap.entrySet()) {
                _torrentCreateFilterMap.put(entry.getKey(), entry.getValue());
            }
        } catch (IOException ex) {
            String msg = _t("Unable to load torrent create file filter config: ");
            _log.error(msg + ex.getMessage());
            addMessageAndPrint(msg + ex.getMessage());
        } catch (ClassNotFoundException ex) {
            String msg = _t("Unable to load torrent create file filter config: ");
            _log.error(msg + ex.getMessage());
            addMessageAndPrint(msg + ex.getMessage());
        }
    }

    /**
     * @since 0.9
     */
    public void setDefaultTrackerMap() {
        setDefaultTrackerMap(true);
    }

    /**
     * @since 0.9.62+
     */
    public void setDefaultTorrentCreateFilterMap() {
        setDefaultTorrentCreateFilterMap(true);
    }

    /**
     * @since 0.9.1
     */
    private void setDefaultTrackerMap(boolean save) {
        _trackerMap.clear();
        for (int i = 0; i < DEFAULT_TRACKERS.length - 1; i += 2) {
            String name = DEFAULT_TRACKERS[i];
            if (name.equals("TheBland") && !SigType.ECDSA_SHA256_P256.isAvailable()) {
                continue;
            }
            String[] urls = DataHelper.split(DEFAULT_TRACKERS[i + 1], "=", 2);
            String url2 = urls.length > 1 ? urls[1] : null;
            _trackerMap.put(name, new Tracker(name, urls[0], url2));
        }
        if (save && _config.remove(PROP_TRACKERS) != null) {
            saveConfig();
        }
    }

    /**
     * @since 0.9.62+
     */
    private void setDefaultTorrentCreateFilterMap(boolean save) {
        _torrentCreateFilterMap.clear();
        for (int i = 0; i < DEFAULT_TORRENT_CREATE_FILTERS.length - 2; i += 3) {
            String name = DEFAULT_TORRENT_CREATE_FILTERS[i];
            String filterPattern = DEFAULT_TORRENT_CREATE_FILTERS[i + 1];
            String filterType = DEFAULT_TORRENT_CREATE_FILTERS[i + 2];
            _torrentCreateFilterMap.put(
                    name, new TorrentCreateFilter(name, filterPattern, filterType, false));
        }
        if (save) {
            saveTorrentCreateFilterMap();
        }
    }

    /**
     * @since 0.9
     */
    public void saveTrackerMap() {
        StringBuilder buf = new StringBuilder(2048);
        boolean comma = false;
        for (Map.Entry<String, Tracker> e : _trackerMap.entrySet()) {
            if (comma) {
                buf.append(',');
            } else {
                comma = true;
            }
            Tracker t = e.getValue();
            buf.append(e.getKey().replace(",", "&#44;"))
                    .append(',')
                    .append(t.announceURL.replace(",", "&#44;"));
            if (t.baseURL != null) {
                buf.append('=').append(t.baseURL);
            }
        }
        _config.setProperty(PROP_TRACKERS, buf.toString());
        saveConfig();
    }

    /**
     * @since 0.9.62+
     */
    public void saveTorrentCreateFilterMap() {
        try (FileOutputStream file =
                    new FileOutputStream(_configDir + "/" + PROP_TORRENT_FILTERS_CONFIG);
             BufferedOutputStream buf = new BufferedOutputStream(file);
             ObjectOutputStream out = new ObjectOutputStream(buf)) {
            out.writeObject(_torrentCreateFilterMap);
        } catch (IOException ex) {
            String msg = _t("Unable to save torrent create file filter config: ");
            _log.error("[I2PSnark] " + msg + ex);
            addMessageAndPrint(msg + ex.getMessage());
        }
    }

    /**
     * If not connected, thread it, otherwise inline
     *
     * @throws RuntimeException via Snark.fatal()
     * @since 0.9.1
     */
    public void startTorrent(byte[] infoHash) {
        for (Snark snark : _snarks.values()) {
            if (DataHelper.eq(infoHash, snark.getInfoHash())) {
                startTorrent(snark);
                return;
            }
        }
        addMessage(_t("Torrent not found"));
    }

    /**
     * If not connected, thread it, otherwise inline
     *
     * @throws RuntimeException via Snark.fatal()
     * @since 0.9.23
     */
    public void startTorrent(Snark snark) {
        if (snark.isStarting() || !snark.isStopped()) {
            addMessageNoEscapeAndPrint(
                    _t("Torrent already running: {0}", linkify(snark)),
                    _t("Torrent already running: {0}", getSnarkName(snark)));
            return;
        }
        boolean connected = _util.connected();
        if ((!connected) && !_util.isConnecting()) {
            addMessage(_t("Opening the I2P tunnel") + "...");
        }
        boolean isLookup = _magnets.contains(snark.getName())
                || snark.getName().startsWith("lookup-");
        if (isLookup) {
            addMessageNoEscapeAndPrint(
                    _t("Resolving torrent lookup: {0}", linkify(snark)).replace("Magnet ", ""),
                    _t("Resolving torrent lookup: {0}", getSnarkName(snark)).replace("Magnet ", ""));
        } else {
            addMessageNoEscapeAndPrint(
                    _t("Starting torrent: {0}", linkify(snark)).replace("Magnet ", ""),
                    _t("Starting torrent: {0}", getSnarkName(snark)).replace("Magnet ", ""));
        }
        if (connected) {
            try {
                snark.startTorrent();
            } catch (RuntimeException re) { /* ignored */ } // Snark.fatal() will log and call fatal() here for user
                                                              // message before throwing
        } else {
            snark.setStarting(); // mark it for the UI
            (new I2PAppThread(new ThreadedStarter(snark), "TorrStarter", true)).start();
            sleep(200);
        }
    }

    /**
     * Always thread it
     *
     * @since 0.9.1
     */
    public void startAllTorrents() {
        if (!_util.connected()) {
            String msg = _t("Opening the I2P tunnel and starting all torrents.") + "..";
            addMessageAndPrint(msg);
            for (Snark snark : _snarks.values()) {
                snark.setStarting();
            } // mark it for the UI
            _stopping = false;
        }
        (new I2PAppThread(new ThreadedStarter(null), "TorrStAll", true)).start();
        sleep(200);
    }

    /**
     * Start all currently-stopped torrents of the given batch in the
     * background using the same pool-aware staggering and parallel pool
     * startup as startBatch(). Returns immediately so the caller's request
     * thread is never blocked by tunnel builds.
     *
     * Torrents that are not stopped are ignored, so a repeated invocation
     * while a previous batch is still starting is naturally a no-op.
     *
     * @param batch the candidate torrents
     * @since 0.9.71+
     */
    public void startStoppedTorrents(List<Snark> batch) {
        if (batch == null || batch.isEmpty()) {return;}
        List<Snark> stopped = new ArrayList<>(batch.size());
        for (Snark snark : batch) {
            if (snark.isStopped()) {stopped.add(snark);}
        }
        if (stopped.isEmpty()) {return;}
        if ((!_util.connected()) && !_util.isConnecting()) {
            addMessage(_t("Opening the I2P tunnel") + "...");
            // mark it for the UI until the tunnel comes up
            for (Snark snark : stopped) {snark.setStarting();}
        }
        (new I2PAppThread(() -> startBatch(stopped), "TorrStBatch", true)).start();
    }

    /**
     * Use null constructor param for all
     *
     * @since 0.9.1
     */
    private class ThreadedStarter implements Runnable {
        private final Snark snark;

        public ThreadedStarter(Snark s) {
            snark = s;
        }

        public void run() {
            if (snark != null) {
                if (snark.isStopped()) {
                    try {
                        snark.startTorrent();
                    } catch (RuntimeException re) { /* ignored */ } // Snark.fatal() will log and call fatal() here for user message before
                      // throwing
                }
            } else {
                startAll();
            }
        }
    }

    /**
     * Inline
     *
     * @since 0.9.1
     */
    private void startAll() {
        // Collect the stopped torrents first; when they exceed the destination
        // count, group them by pool and randomize the pool order so each pool's
        // tunnel builds are staggered, not the individual torrents'
        List<Snark> stopped = new ArrayList<>(0);
        for (Snark snark : _snarks.values()) {
            if (snark.isStopped()) {
                stopped.add(snark);
            }
        }
        if (_util.getMultiDest() && _randomizeStartupDelay && _util.getMaxDest() > 0 && stopped.size() > _util.getMaxDest()) {
            Map<Integer, List<Snark>> byPool = new HashMap<>(stopped.size() / 2);
            for (Snark snark : stopped) {
                int pool = _util.getPoolIndex(snark.getInfoHash());
                List<Snark> members = byPool.get(pool);
                if (members == null) {
                    members = new ArrayList<>(0);
                    byPool.put(pool, members);
                }
                members.add(snark);
            }
            // Randomize the pool start order
            List<List<Snark>> pools = new ArrayList<>(byPool.values());
            Collections.shuffle(pools, _context.random());
            stopped.clear();
            for (List<Snark> members : pools) {
                stopped.addAll(members);
            }
        }
        int count = 0;
        int started = 0;
        Set<Integer> seenPools = new HashSet<>(0);
        for (Snark snark : stopped) {
            if (_util.getMultiDest() && _randomizeStartupDelay) {
                // Stagger per-pool torrents: only a pool's first torrent builds its
                // tunnels, so wait before the first torrent of each new pool, and
                // start pool-mates together
                int pool = _util.getPoolIndex(snark.getInfoHash());
                if (started++ > 0 && (pool < 0 || seenPools.add(pool))) {
                    sleep(MULTI_DEST_STAGGER_MS);
                }
            }
            try {
                snark.startTorrent();
            } catch (RuntimeException re) { /* ignored */ } // Snark.fatal() will log and call fatal() here for user message before throwing
            if ((count++ & 0x0f) == 15) {
                // try to prevent OOMs
                sleep(250);
            }
        }
    }

    /**
     * Filters a candidate start batch down to the torrents that were running
     * when last saved. A torrent whose per-torrent config has no
     * {@link #PROP_META_RUNNING} entry counts as previously running (matches
     * the auto-start default); one explicitly saved as stopped stays stopped.
     *
     * Used by the startup auto-start paths, where torrents are first loaded
     * without starting them (so pool-mates can batch together) and must then
     * be filtered by their persisted prior status before starting.
     *
     * @param batch candidate torrents, non-null
     * @return the subset that should be auto-started
     * @since 0.9.71+
     */
    private List<Snark> previouslyRunning(List<Snark> batch) {
        List<Snark> rv = new ArrayList<>(batch.size());
        for (Snark snark : batch) {
            Properties config = getConfig(snark);
            if (wasPreviouslyRunning(config)) {
                rv.add(snark);
            }
        }
        return rv;
    }

    /**
     * Returns true if the torrent's persisted config indicates it was running
     * when last saved. A missing entry counts as previously running (matches
     * the auto-start default); an explicitly false entry means it was stopped.
     *
     * @param config the torrent's persisted properties
     * @return true if the torrent should be auto-started
     * @since 0.9.71+
     */
    static boolean wasPreviouslyRunning(Properties config) {
        String prop = config.getProperty(PROP_META_RUNNING);
        return prop == null || Boolean.parseBoolean(prop);
    }

    /**
     * Start a batch of torrents, keeping pool-mates together. All torrents in
     * the batch are already known when this is called, so the destinations can
     * be computed up front: pool-mates are started back-to-back, and only the
     * first torrent of each subsequent pool waits, letting that destination's
     * tunnels build before the next pool's torrents start. The pool start order
     * is randomized so one destination doesn't grab all the early torrents; in
     * single-dest mode the batch order is randomized with no delay between
     * torrents.
     *
     * @param batch the torrents to start
     * @return false if the I2CP connection could not be established, so the
     *     caller knows the batch was left for a later pass
     * @since 0.9.71+
     */
    private boolean startBatch(List<Snark> batch) {
        if (batch.isEmpty()) {
            return true;
        }
        // Mark scheduled torrents up front so the UI shows them as queued
        // for autostart from schedule time, not only once their pool begins
        for (Snark snark : batch) {
            if (snark.isStopped()) {snark.setStarting();}
        }
        // Group by pool; in single-dest mode this is one group of all torrents
        Map<Integer, List<Snark>> byPool = new HashMap<>(batch.size() / 2);
        for (Snark snark : batch) {
            int pool = _util.getPoolIndex(snark.getInfoHash());
            List<Snark> members = byPool.get(pool);
            if (members == null) {
                members = new ArrayList<>(0);
                byPool.put(pool, members);
            }
            members.add(snark);
        }
        // Randomize the pool start order, and the order within each pool
        List<List<Snark>> pools = new ArrayList<>(byPool.values());
        Collections.shuffle(pools, _context.random());
        for (List<Snark> members : pools) {
            Collections.shuffle(members, _context.random());
        }
        if (!_context.isRouterContext() && !_util.connected()) {
            // Standalone: connect to the external router inline so a failure is
            // reported back and the DirMonitor keeps probing; in-router, the
            // shared session was pre-connected during the startup delay
            String msg = _t("Connecting to I2P") + "...";
            addMessageAndPrint(
                    msg,
                    _t(
                            "Connecting to I2CP port on I2P instance at {0}",
                            _util.getI2CPHost() + ':' + _util.getI2CPPort() + "..."));
            // getBWLimit() was successful so this should work
            boolean ok = _util.connect();
            if (!ok) {
                msg =
                        _t("Error connecting to I2P - check your I2CP settings!")
                                + ' '
                                + _util.getI2CPHost()
                                + ':'
                                + _util.getI2CPPort();
                addMessageAndPrint(msg);
                // Leave the rest of the batch for a later pass
                return false;
            }
            msg = _t("Connected to I2P at") + ' ' + _util.getI2CPHost() + ':' + _util.getI2CPPort();
            System.out.println(" • " + msg);
        }
        int started = 0;
        for (List<Snark> members : pools) {
            if (started++ > 0) {
                // No-op in single-dest mode or when randomize startup delay is off
                multiDestStartDelay();
            }
            // Start each pool on its own thread: a pool's session creation
            // blocks until its tunnels build, and one slow pool must not hold
            // up the rest of the batch
            (new I2PAppThread(new PoolStarter(members), "SnarkSPool", true)).start();
        }
        return true;
    }

    /**
     * Start the torrents of one pool on its own thread, so each pool's session
     * and tunnel builds proceed in parallel with the other pools.
     *
     * @since 0.9.71+
     */
    private class PoolStarter implements Runnable {
        private final List<Snark> _members;

        public PoolStarter(List<Snark> members) {
            _members = members;
        }

        public void run() {
            for (Snark snark : _members) {
                if (!_util.connected()) {
                    String msg = _t("Connecting to I2P") + "...";
                    addMessageAndPrint(
                            msg,
                            _t(
                                    "Connecting to I2CP port on I2P instance at {0}",
                                    _util.getI2CPHost()
                                            + ':'
                                            + _util.getI2CPPort()
                                            + "..."));
                    boolean ok = _util.connect();
                    if (!ok) {
                        if (_context.isRouterContext()) {
                            addMessage(_t("Unable to connect to I2P"));
                        } else {
                            msg =
                                    _t("Error connecting to I2P - check your I2CP settings!")
                                            + ' '
                                            + _util.getI2CPHost()
                                            + ':'
                                            + _util.getI2CPPort();
                            addMessageAndPrint(msg);
                        }
                        // Leave the rest of the pool for a later pass
                        return;
                    } else if (!_context.isRouterContext()) {
                        msg =
                                _t("Connected to I2P at")
                                        + ' '
                                        + _util.getI2CPHost()
                                        + ':'
                                        + _util.getI2CPPort();
                        System.out.println(" • " + msg);
                    }
                }
                addMessageNoEscape(_t("Starting up torrent {0}", linkify(snark)));
                try {
                    snark.startTorrent();
                } catch (Snark.RouterException re) {
                    return;
                } // Snark.fatal() will log and call fatal() here for user
                  // message before throwing
                catch (RuntimeException re) { /* ignored */ } // Snark.fatal() will log and call fatal() here for user
                  // message before throwing
            }
        }
    }

    /**
     * Stop one torrent, notifying trackers per finalShutdown, and adding the
     * "stopping all" message on the first stop.
     *
     * @param snark the torrent to stop
     * @param count number of torrents stopped so far
     * @param finalShutdown if true, notify trackers immediately
     * @return the incremented count
     * @since 0.9.71+
     */
    private int stopTorrent(Snark snark, int count, boolean finalShutdown) {
        if (count == 0) {
            String msg = _t("Stopping all torrents and closing the I2P tunnel.") + "..";
            addMessageAndPrint(msg);
        }
        count++;
        snark.stopTorrent(finalShutdown, true);
        return count;
    }

    /**
     * Stop all running torrents, and close the tunnel after a delay to allow for announces. If
     * called at router shutdown via Jetty shutdown hook -&gt; webapp destroy() -&gt; stop(), the
     * tunnel won't actually be closed as the SimpleTimer2 is already shutdown or will be soon, so
     * we delay a few seconds inline.
     *
     * @param finalShutdown if true, sleep at the end if any torrents were running
     * @since 0.9.1
     */
    public void stopAllTorrents(boolean finalShutdown) {
        _stopping = true;
        if (finalShutdown && _log.shouldWarn()) {
            _log.warn("SnarkManager final shutdown");
        }
        int count = 0;
        List<Snark> stopped = new ArrayList<Snark>();
        Collection<Snark> snarks = _snarks.values();
        /*
         * We do two passes so we shutdown the high-priority snarks first.
         * Pass 1: All running, incomplete torrents, to make sure the status
         * gets saved so there will be no recheck on restart.
         */
        for (Snark snark : snarks) {
            if (!snark.isStopped()) {
                Storage storage = snark.getStorage();
                if (storage != null && !storage.complete()) {
                    count = stopTorrent(snark, count, finalShutdown);
                    stopped.add(snark);
                }
            }
        }
        // Pass 2: All the rest of the torrents
        for (Snark snark : snarks) {
            if (!snark.isStopped()) {
                count = stopTorrent(snark, count, finalShutdown);
                stopped.add(snark);
            } else {
                CommentSet cs = snark.getComments();
                if (cs != null) {
                    synchronized (cs) {
                        if (cs.isModified()) {
                            locked_saveComments(snark, cs);
                        }
                    }
                }
            }
        }
        // Give the sessions one bounded window to dispatch their unannounces
        // before any teardown, then tear the stopped destinations down
        long deadline = System.currentTimeMillis() + TrackerClient.unannounceDispatchWait();
        for (Snark snark : stopped) {
            snark.awaitUnannounces(deadline - System.currentTimeMillis());
        }
        for (Snark snark : stopped) {
            snark.teardownSession();
        }
        if (_util.connected()) {
            if (count > 0) {
                DHT dht = _util.getDHT();
                if (dht != null) {
                    dht.stop();
                }
                String msg = _t("Closing I2P tunnel after notifying trackers.") + "..";
                addMessageAndPrint(msg);
                if (finalShutdown) {
                    long toWait = (long) 5 * 1000;
                    if (SystemVersion.isARM()) {
                        toWait *= 2;
                    }
                    sleep(toWait);
                    _util.disconnect();
                    _stopping = false;
                } else {
                    // Only schedule this if not a final shutdown
                    new Disconnector().schedule((long) 60 * 1000);
                }
            } else {
                _util.disconnect();
                _stopping = false;
                addMessage(_t("I2P tunnel closed."));
            }
        }
    }

    /**
     * @since 0.9.1
     */
    private class Disconnector extends SimpleTimer2.TimedEvent {
        public void timeReached() {
            if (_util.connected()) {
                _util.disconnect();
                _stopping = false;
                addMessage(_t("I2P tunnel closed."));
            }
        }
    }

    /**
     * Restart all running torrents on fresh destinations: stop them all (which
     * destroys their sessions, and schedules the Disconnector to close the main
     * session too), then restart only the torrents that were running when the
     * cycle fired, once the old session is gone. Skipped while any torrent is
     * actively downloading, and when the router is already stopping.
     *
     * @since 0.9.71+
     */
    private void cycleDestinations() {
        if (_stopping || !shouldDestCycle()) {
            return;
        }
        List<Snark> running = new ArrayList<>(_snarks.size());
        int downloading = 0;
        for (Snark snark : _snarks.values()) {
            if (!snark.isStopped()) {
                Storage storage = snark.getStorage();
                if (storage != null && !storage.complete()) {
                    downloading++;
                } else {
                    running.add(snark);
                }
            }
        }
        if (downloading > 0) {
            String msg = downloading == 1
                    ? _t("Skipping destination cycle - {0} active download in progress", "1")
                    : _t("Skipping destination cycle - {0} active downloads in progress", String.valueOf(downloading));
            if (_log.shouldInfo()) {
                _log.info(msg);
            }
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg);
            }
            return; // active download; rotating now would disrupt it
        }
        if (running.isEmpty()) {
            if (_log.shouldInfo()) {
                _log.info("Skipping destination cycle - no running torrents");
            }
            return;
        }
        String msg = _t("No actively downloading torrents - cycling destinations") + "..";
        addMessageAndPrint(msg);
        stopAllTorrents(false);
        new DestCycleRestart(running).schedule(DEST_CYCLE_RESTART_DELAY);
    }

    /**
     * Periodically cycle destinations: stop all running torrents and restart
     * them so their sessions - and with them their destinations - are recreated
     * fresh. Self-reschedules with a random jitter.
     *
     * @since 0.9.71+
     */
    private class DestCycle extends SimpleTimer2.TimedEvent {
        public void timeReached() {
            try {
                cycleDestinations();
            } finally {
                schedule(DEST_CYCLE_INTERVAL + _context.random().nextInt(DEST_CYCLE_JITTER));
            }
        }
    }

    /**
     * Restart the torrents that were running when the cycle fired, once the
     * stop-all's Disconnector has closed the old session, so the new session -
     * and with it the new destinations - is created on start. Multi-dest pools
     * restart in random order with the startup stagger.
     *
     * @since 0.9.71+
     */
    private class DestCycleRestart extends SimpleTimer2.TimedEvent {
        private final List<Snark> _running;

        public DestCycleRestart(List<Snark> running) {
            _running = running;
        }

        public void timeReached() {
            _stopping = false;
            Set<Integer> seenPools = new HashSet<>(_running.size() / 2);
            int started = 0;
            for (Snark snark : _running) {
                if (!snark.isStopped()) {
                    continue; // already running, or removed
                }
                if (_util.getMultiDest() && _util.getMaxDest() > 0) {
                    int pool = _util.getPoolIndex(snark.getInfoHash());
                    boolean newPool = (pool < 0) || seenPools.add(pool);
                    if (started++ > 0 && newPool) {
                        multiDestStartDelay();
                    }
                }
                snark.setStarting();
                (new I2PAppThread(new ThreadedStarter(snark), "TorrStarter", true)).start();
            }
        }
    }

    /**
     * Threaded. Torrent must be stopped.
     *
     * @since 0.9.23
     */
    public void recheckTorrent(Snark snark) {
        if (snark.isStarting() || !snark.isStopped()) {
            addMessage(
                    (_t("Cannot check {0}", snark.getBaseName())
                            + " -> "
                            + _t("Torrent already started")));
            return;
        }
        Storage storage = snark.getStorage();
        if (storage == null) {
            addMessage((_t("Cannot check {0}", snark.getBaseName()) + " -> " + _t("No storage")));
            return;
        }
        (new I2PAppThread(new ThreadedRechecker(snark), "TorrRecheck", true)).start();
        sleep(200);
    }

    /**
     * @since 0.9.23
     */
    private class ThreadedRechecker implements Runnable {
        private final Snark snark;

        /** Must have non-null storage. */
        public ThreadedRechecker(Snark s) {
            snark = s;
        }

        public void run() {
            try {
                if (_log.shouldWarn()) {
                    _log.warn("Starting recheck of " + snark.getBaseName() + "...");
                }
                if (!_context.isRouterContext()) {
                    System.out.println(
                            " • " + (_t("Starting recheck of {}", getSnarkName(snark))) + "...");
                }
                boolean changed = snark.getStorage().recheck();
                if (changed) {
                    updateStatus(snark);
                }
                if (_log.shouldWarn()) {
                    _log.warn(
                            "Finished recheck of "
                                    + snark.getBaseName()
                                    + " -> "
                                    + (changed ? "File changes detected" : "Unchanged"));
                }
                String link = linkify(snark);
                if (changed) {
                    int pieces = snark.getPieces();
                    double completion = (pieces - snark.getNeeded()) / (double) pieces;
                    String complete = (new DecimalFormat("0.00%")).format(completion);
                    addMessageNoEscapeAndPrint(
                            _t(
                                    "Finished recheck of torrent {0}, now {1} complete",
                                    link, complete),
                            _t(
                                    "Finished recheck of torrent {0}, now {1} complete",
                                    getSnarkName(snark), complete));
                } else {
                    addMessageNoEscapeAndPrint(
                            _t("Finished recheck of torrent {0}, unchanged", link),
                            _t(
                                    "Finished recheck of torrent {0}, unchanged",
                                    getSnarkName(snark)));
                }
            } catch (IOException e) {
                _log.error("Error rechecking " + snark.getBaseName(), e);
                String msg =
                        _t("Error checking torrent {0}", snark.getBaseName())
                                + " -> "
                                + e.getMessage();
                addMessageAndPrint(
                        msg,
                        _t("Error checking torrent {0}", getSnarkName(snark))
                                + " -> "
                                + e.getMessage());
            }
        }
    }

    /**
     * Compare ignoring case, current locale.
     *
     * @since 0.9
     */
    private static class IgnoreCaseComparator implements Comparator<Tracker>, Serializable {
        private static final long serialVersionUID = 1L;
        private final Collator coll = Collator.getInstance();

        public int compare(Tracker l, Tracker r) {
            return coll.compare(l.name, r.name);
        }
    }

    /**
     * Compare ignoring case, current locale.
     *
     * @since 0.9.62+
     */
    private static class IgnoreCaseComparatorF
            implements Comparator<TorrentCreateFilter>, Serializable {
        private static final long serialVersionUID = 1L;
        private final Collator coll = Collator.getInstance();

        public int compare(TorrentCreateFilter l, TorrentCreateFilter r) {
            return coll.compare(l.name, r.name);
        }
    }

    /** How long to cache the disk usage string, avoids a statfs per render. */
    private static final long DISK_USAGE_TTL = 30 * 1000;
    /** Last time the disk usage string was computed. */
    private long _diskUsageCheckedAt;
    /** Cached disk usage string. */
    private String _diskUsageCached;

    /**
     * The cached disk usage string for display.
     *
     * @return the disk usage
     * @since 0.9.64+
     */
    public String getDiskUsage() {
        long now = System.currentTimeMillis();
        if (_diskUsageCached != null && now - _diskUsageCheckedAt < DISK_USAGE_TTL) {
            return _diskUsageCached;
        }
        try {
            File dir = getDataDir();
            if (dir == null || !dir.exists()) {
                String msg =
                        _t("Data directory does not exist")
                                + " -> "
                                + _t("Cannot create diskspace bar");
                if (_log.shouldError()) {
                    _log.error("[I2PSnark] " + msg);
                }
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                return "";
            }

            long freeSpace = dir.getUsableSpace();
            long totalSpace = dir.getTotalSpace();

            if (totalSpace <= 0) {
                return "";
            }

            double usagePercent = ((totalSpace - freeSpace) / (double) totalSpace) * 100;

            String freeSpaceStr;
            if (freeSpace >= (1024 * 1024 * 1024)) {
                freeSpaceStr = String.format("%.1f G", freeSpace / (double) (1024 * 1024 * 1024));
            } else {
                freeSpaceStr = String.format("%d M", freeSpace / (1024 * 1024));
            }

            String totalSpaceStr =
                    totalSpace >= (1024 * 1024 * 1024)
                            ? String.format("%.1f G", totalSpace / (double) (1024 * 1024 * 1024))
                            : String.format("%d M", totalSpace / (1024 * 1024));

            String title = _t("Data partition") + ": " + freeSpaceStr + " / " + totalSpaceStr;

            String bar =
                    "<span class=volatile id=diskSpace title=\""
                            + title
                            + "\">"
                            + "<span id=diskSpaceInner style='width:%d%%'></span></span>";
            bar = bar.replace(".0", "").replace(" G", "G").replace(" M", "M");

            int gCount = 0;
            int mCount = 0;
            int gIndex = -1;
            int mIndex = -1;

            for (int i = 0; i < bar.length(); i++) {
                char ch = bar.charAt(i);
                if (ch == 'G') {
                    gCount++;
                    if (gIndex == -1) {
                        gIndex = i;
                    }
                } else if (ch == 'M') {
                    mCount++;
                    if (mIndex == -1) {
                        mIndex = i;
                    }
                }
            }

            // remove first 'G' or 'M' if both values are same unit
            if (gCount > 1) {
                bar = bar.substring(0, gIndex) + bar.substring(gIndex + 1);
            } else if (mCount > 1) {
                bar = bar.substring(0, mIndex) + bar.substring(mIndex + 1);
            }
            String rv = String.format(bar, (int) usagePercent);
            _diskUsageCached = rv;
            _diskUsageCheckedAt = now;
            return rv;
        } catch (Exception e) {
            if (_log.shouldError()) {
                _log.error("[I2PSnark] Error retrieving disk usage: " + e.getMessage());
            }
            return "";
        }
    }
}
