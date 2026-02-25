package org.klomp.snark;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.text.Collator;
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
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
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
import net.i2p.util.SimpleTimer;
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

    /** used to prevent DirMonitor from deleting torrents that don't have a torrent file yet */
    private final Set<String> _magnets;

    private final Object _addSnarkLock;
    private File _configFile;
    private File _configDir;

    /** one lock for all config, files for simplicity */
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

    public static final String PROP_I2CP_HOST = "i2psnark.i2cpHost";
    public static final String PROP_I2CP_PORT = "i2psnark.i2cpPort";
    public static final String PROP_I2CP_OPTS = "i2psnark.i2cpOptions";
    // public static final String PROP_EEP_HOST = "i2psnark.eepHost";
    // public static final String PROP_EEP_PORT = "i2psnark.eepPort";
    public static final String PROP_UPLOADERS_TOTAL = "i2psnark.uploaders.total";
    public static final String PROP_UPBW_MAX = "i2psnark.upbw.max";

    /**
     * @since 0.9.62
     */
    public static final String PROP_DOWNBW_MAX = "i2psnark.downbw.max";

    public static final String PROP_DIR = "i2psnark.dir";
    private static final String PROP_META_PREFIX = "i2psnark.zmeta.";
    private static final String PROP_META_RUNNING = "running";
    private static final String PROP_META_STAMP = "stamp";
    private static final String PROP_META_BASE = "base";
    private static final String PROP_META_BITFIELD = "bitfield";
    private static final String PROP_META_PRIORITY = "priority";
    private static final String PROP_META_PRESERVE_NAMES = "preserveFileNames";
    private static final String PROP_META_UPLOADED = "uploaded";
    private static final String PROP_META_ADDED = "added";
    private static final String PROP_META_COMPLETED = "completed";
    private static final String PROP_META_INORDER = "inOrder";
    private static final String PROP_META_MAGNET = "magnet";
    private static final String PROP_META_MAGNET_DN = "magnet_dn";
    private static final String PROP_META_MAGNET_TR = "magnet_tr";
    private static final String PROP_META_MAGNET_DIR = "magnet_dir";
    // private static final String PROP_META_BITFIELD_SUFFIX = ".bitfield";
    // private static final String PROP_META_PRIORITY_SUFFIX = ".priority";
    private static final String PROP_META_MAGNET_PREFIX = "i2psnark.magnet.";

    /**
     * @since 0.9.31
     */
    private static final String PROP_META_COMMENTS = "comments";

    /**
     * @since 0.9.42
     */
    private static final String PROP_META_ACTIVITY = "activity";

    private static final String CONFIG_FILE_SUFFIX = ".config";
    public static final String CONFIG_FILE = "i2psnark" + CONFIG_FILE_SUFFIX;
    private static final String COMMENT_FILE_SUFFIX = ".comments.txt.gz";
    public static final String PROP_FILES_PUBLIC = "i2psnark.filesPublic";

    /**
     * @since 0.9.66+
     */
    public static final String PROP_PREALLOCATE_FILES = "i2psnark.preallocateFiles";

    public static final String DEFAULT_PREALLOCATE_FILES = "true";
    public static final String PROP_OLD_AUTO_START = "i2snark.autoStart"; // oops
    public static final String PROP_AUTO_START =
            "i2psnark.autoStart"; // convert in migration to new config file
    private final boolean DEFAULT_AUTO_START;
    // public static final String PROP_LINK_PREFIX = "i2psnark.linkPrefix";
    // public static final String DEFAULT_LINK_PREFIX = "file:///";
    public static final String PROP_STARTUP_DELAY = "i2psnark.startupDelay";
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
     * @since 0.9.61+
     */
    public static final String PROP_MAX_MESSAGES = "i2psnark.maxLogMessages";

    /**
     * @since 0.9.64+
     */
    public static final String PROP_VARY_INBOUND_HOPS = "i2psnark.varyInboundHops";

    public static final String PROP_VARY_OUTBOUND_HOPS = "i2psnark.varyOutboundHops";

    public static final int MIN_UP_BW = 5;
    public static final int MIN_DOWN_BW = 2 * MIN_UP_BW;
    public static final int DEFAULT_MAX_UP_BW = 1024;
    private static final int DEFAULT_MAX_DOWN_BW = 1024;
    public static final int DEFAULT_STARTUP_DELAY = 2;
    public static final int DEFAULT_REFRESH_DELAY_SECS = 5;
    private static final int DEFAULT_PAGE_SIZE = 50;
    public static final int DEFAULT_TUNNEL_QUANTITY = 16;
    public static final int DEFAULT_MAX_FILES_PER_TORRENT = 2000;
    public static final String CONFIG_DIR_SUFFIX = ".d";
    private static final String SUBDIR_PREFIX = "s";
    private static final String B64 = Base64.ALPHABET_I2P;
    private static final int DEFAULT_MAX_MESSAGES = 50;

    /**
     * @since 0.9.67+
     */
    private long lastUpBwChange = 0;

    private long lastDownBwChange = 0;

    /**
     * "name", "announceURL=websiteURL" pairs '=' in announceURL must be escaped as &#44;
     *
     * <p>Please use host name, not b32 or full dest, in announce URL. Ensure in default hosts.txt.
     * Please use host name, not b32 or full dest, in website URL. Ensure in default hosts.txt.
     */
    private static final String DEFAULT_TRACKERS[] = {
        "Postman", "http://tracker2.postman.i2p/announce.php=http://tracker2.postman.i2p/",
        "BT", "http://opentracker.bt.i2p/a=http://opentracker.bt.i2p/stats",
        "DgTrack", "http://opentracker.dg2.i2p/a=http://opentracker.dg2.i2p/",
        "R4SAS", "http://opentracker.r4sas.i2p/a=http://opentracker.r4sas.i2p/stats",
        "Sigma", "http://sigmatracker.i2p/a=http://sigmatracker.i2p/",
        "Simp", "http://opentracker.simp.i2p/a=http://opentracker.simp.i2p/tracker",
        "Simp [UDP]", "udp://opentracker.simp.i2p:6969/=http://opentracker.simp.i2p/tracker/",
        "Skank", "http://opentracker.skank.i2p/a=http://opentracker.skank.i2p/tracker",
        "Skank [UDP]", "udp://opentracker.skank.i2p:6969/=http://opentracker.skank.i2p/tracker/"
    };

    /** URL. This is our equivalent to router.utorrent.com for bootstrap */
    public static final String DEFAULT_BACKUP_TRACKER = "http://opentracker.dg2.i2p/a";

    /** URLs, comma-separated. Used for "announce to open trackers also" */
    private static final String DEFAULT_OPENTRACKERS =
        "http://opentracker.bt.i2p/a," +
        "http://opentracker.dg2.i2p/a," +
        "http://opentracker.r4sas.i2p/a," +
        "http://opentracker.simp.i2p/a," +
        "http://opentracker.skank.i2p/a," +
        "http://sigmatracker.i2p/a";

    public static final Set<String> DEFAULT_TRACKER_ANNOUNCES;

    /** host names for config form */
    static final Set<String> KNOWN_OPENTRACKERS = new HashSet<String>(Arrays.asList(new String[] {
        "opentracker.bt.i2p", "ev5dpxvcmshi6mil7gaon3b2wbplwylzraxs4wtz7dd5lzdsc2dq.b32.i2p",
        "opentracker.dg2.i2p", "w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p",
        "opentracker.r4sas.i2p", "punzipidirfqspstvzpj6gb4tkuykqp6quurj6e23bgxcxhdoe7q.b32.i2p",
        "opentracker.simp.i2p", "wc4sciqgkceddn6twerzkfod6p2npm733p7z3zwsjfzhc4yulita.b32.i2p",
        "opentracker.skank.i2p", "by7luzwhx733fhc5ug2o75dcaunblq2ztlshzd7qvptaoa73nqua.b32.i2p",
        "sigmatracker.i2p", "qimlze77z7w32lx2ntnwkuqslrzlsqy7774v3urueuarafyqik5a.b32.i2p",
    }));

    private static final String DEFAULT_TORRENT_CREATE_FILTERS[] = {
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
        Set<String> ann = new HashSet<String>(8);
        for (int i = 1; i < DEFAULT_TRACKERS.length; i += 2) {
            if (DEFAULT_TRACKERS[i - 1].equals("TheBland")
                    && !SigType.ECDSA_SHA256_P256.isAvailable()) {
                continue;
            }
            String urls[] = DataHelper.split(DEFAULT_TRACKERS[i], "=", 2);
            ann.add(urls[0]);
        }
        DEFAULT_TRACKER_ANNOUNCES = Collections.unmodifiableSet(ann);
    }

    /** comma delimited list of name=announceURL=baseURL for the trackers to be displayed */
    public static final String PROP_TRACKERS = "i2psnark.trackers";

    /**
     * comma delimited list of name=filterPattern for torrent create filters. Deprecated. If
     * detected, filters will be converted to new storage and then this config will be removed.
     */
    public static final String PROP_TORRENT_CREATE_FILTERS = "i2psnark.torrent_create_filters";

    /** filename for serialized torrent filters config */
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
        _snarks = new ConcurrentHashMap<String, Snark>();
        _infoHashToSnark = new HashMap<SHA1Hash, Snark>();
        _filteredBaseNameToSnark = new HashMap<String, Snark>();
        _magnets = new ConcurrentHashSet<String>();
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
        _trackerMap = new ConcurrentHashMap<String, Tracker>(4);
        _torrentCreateFilterMap = new ConcurrentHashMap<String, TorrentCreateFilter>(3);
        loadConfig(null);
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
        _monitor = new I2PAppThread(new DirMonitor(), "SnarkDirMonitor", true);
        _monitor.start();
        if (_context.isRouterContext()
                && "i2psnark".equals(_contextName)) { // only if default instance
            _context.simpleTimer2()
                    .addEvent(new Register(), 4 * 60 * 1000); // delay until UpdateManager is there
        }
        _idleChecker = new IdleChecker(this, _peerCoordinatorSet);
        _idleChecker.schedule(3 * 60 * 1000);
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
    private class Register implements SimpleTimer.TimedEvent {
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
    public void sessionDisconnected() {
        if (!_context.isRouterContext()) {
            // addMessage(_t("Unable to connect to I2P"));
            // stopAllTorrents(true);
            _stopping = true;
        }
    }

    /*
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
    public void startup() {}

    /**
     * ClientApp method. Does nothing. Doesn't matter, we are only registering.
     *
     * @since 0.9.30
     */
    public void shutdown(String[] args) {}

    /**
     * ClientApp method. Doesn't matter, we are only registering.
     *
     * @return INITIALIZED always.
     * @since 0.9.30
     */
    public ClientAppState getState() {
        return ClientAppState.INITIALIZED;
    }

    /**
     * ClientApp method.
     *
     * @since 0.9.30
     */
    public String getName() {
        return "i2psnark";
    }

    /**
     * ClientApp method.
     *
     * @since 0.9.30
     */
    public String getDisplayName() {
        return "i2psnark: " + _contextPath;
    }

    /** hook to I2PSnarkUtil for the servlet */
    public I2PSnarkUtil util() {
        return _util;
    }

    /**
     * The BandwidthManager.
     *
     * @since 0.9.62
     */
    public BandwidthListener getBandwidthListener() {
        return _bwManager;
    }

    /* @since 0.9.64+ */
    private long lastAddedMessageTimestamp;
    private String lastAddedMessage;

    /** Use if it does not include a link. Escapes '&lt;' and '&gt;' before queueing */
    public void addMessage(String message) {
        long currentTime = System.currentTimeMillis() / 1000;
        if (lastAddedMessageTimestamp != currentTime || !lastAddedMessage.equals(message)) {
            addMessageNoEscape(
                    message.replace("&", "&amp;")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;nbsp", "&nbsp;"));
        } else if (lastAddedMessage.startsWith(_t("Download already running: "))
                && lastAddedMessage.contains(_t("Downloading"))) {
            lastAddedMessage = lastAddedMessage.replace(_t("Download already running: "), "");
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

    public String getTime() {
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US);
        Long now = System.currentTimeMillis();
        fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
        String date = fmt.format(new Date(now));
        return "<b class=date>" + date + "</b>";
    }

    /** newest last */
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
     * @return default false
     * @since 0.8.9
     */
    public boolean areFilesPublic() {
        return Boolean.parseBoolean(_config.getProperty(PROP_FILES_PUBLIC));
    }

    /**
     * @return default true
     * @since 0.9.66+
     */
    public boolean shouldPreallocateFiles() {
        return Boolean.parseBoolean(
                _config.getProperty(PROP_PREALLOCATE_FILES, DEFAULT_PREALLOCATE_FILES));
    }

    public boolean shouldAutoStart() {
        return Boolean.parseBoolean(
                _config.getProperty(PROP_AUTO_START, Boolean.toString(DEFAULT_AUTO_START)));
    }

    /**
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
     * @return -1 for never
     * @since 0.8.9
     */
    public int getRefreshDelaySeconds() {
        try {
            return Integer.parseInt(_config.getProperty(PROP_REFRESH_DELAY));
        } catch (NumberFormatException nfe) {
            return DEFAULT_REFRESH_DELAY_SECS;
        }
    }

    /**
     * @since 0.9.46 (I2P+)
     */
    public int getMaxFilesPerTorrent() {
        try {
            return Integer.parseInt(_config.getProperty(PROP_MAX_FILES_PER_TORRENT));
        } catch (NumberFormatException nfe) {
            return DEFAULT_MAX_FILES_PER_TORRENT;
        }
    }

    /**
     * @since 0.9.61+ (I2P+)
     */
    public int getMaxLogMessages() {
        try {
            return Integer.parseInt(_config.getProperty(PROP_MAX_MESSAGES));
        } catch (NumberFormatException nfe) {
            return DEFAULT_MAX_MESSAGES;
        }
    }

    /**
     * For GUI
     *
     * @since 0.9.6
     */
    public int getPageSize() {
        try {
            return Integer.parseInt(_config.getProperty(PROP_PAGE_SIZE));
        } catch (NumberFormatException nfe) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    private int getStartupDelayMinutes() {
        if (!_context.isRouterContext()) {
            return 0;
        }
        try {
            return Integer.parseInt(_config.getProperty(PROP_STARTUP_DELAY));
        } catch (NumberFormatException nfe) {
            return DEFAULT_STARTUP_DELAY;
        }
    }

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
     * For RPC
     *
     * @since 0.9.30
     */
    public File getConfigDir() {
        return _configDir;
    }

    /**
     * Migrate the old flat config file to the new config dir containing the config file minus the
     * per-torrent entries, the dht file, and 64 subdirs for per-torrent config files Caller must
     * synch.
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
        Map<String, Properties> configs = new HashMap<String, Properties>(16);
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
        // Now make a config file for each torrent
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
            File cfg = configFile(dir, ih);
            if (!cfg.exists()) {
                File subdir = cfg.getParentFile();
                if (!subdir.exists()) {
                    subdir.mkdirs();
                }
                try {
                    DataHelper.storeProps(props, cfg);
                } catch (IOException ioe) {
                    _log.error("Error storing I2PSnark config " + cfg, ioe);
                }
            }
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
        Properties rv = new OrderedProperties();
        File conf = configFile(_configDir, ih);
        synchronized (_configLock) { // one lock for all
            try {
                I2PSnarkUtil.loadProps(rv, conf);
            } catch (IOException ioe) {
            }
        }
        return rv;
    }

    /**
     * The config file for a torrent
     *
     * @param confDir the config directory
     * @param ih 20-byte infohash
     * @since 0.9.15
     */
    private static File configFile(File confDir, byte[] ih) {
        String hex = I2PSnarkUtil.toHex(ih);
        File subdir = new SecureDirectory(confDir, SUBDIR_PREFIX + B64.charAt((ih[0] >> 2) & 0x3f));
        return new File(subdir, hex + CONFIG_FILE_SUFFIX);
    }

    /**
     * The comment file for a torrent
     *
     * @param confDir the config directory
     * @param ih 20-byte infohash
     * @since 0.9.31
     */
    private static File commentFile(File confDir, byte[] ih) {
        String hex = I2PSnarkUtil.toHex(ih);
        File subdir = new SecureDirectory(confDir, SUBDIR_PREFIX + B64.charAt((ih[0] >> 2) & 0x3f));
        return new File(subdir, hex + COMMENT_FILE_SUFFIX);
    }

    /**
     * The comments for a torrent
     *
     * @return null if none
     * @since 0.9.31
     */
    public CommentSet getSavedComments(Snark snark) {
        File com = commentFile(_configDir, snark.getInfoHash());
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
     * Save the comments for a torrent Caller must synchronize on comments.
     *
     * @param comments non-null
     * @since 0.9.31
     */
    public void locked_saveComments(Snark snark, CommentSet comments) {
        File com = commentFile(_configDir, snark.getInfoHash());
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
     * @param filename null to set initial defaults
     */
    public void loadConfig(String filename) {
        synchronized (_configLock) {
            locked_loadConfig(filename);
        }
    }

    /** null to set initial defaults */
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
        if (!_config.containsKey(PROP_STARTUP_DELAY)) {
            _config.setProperty(PROP_STARTUP_DELAY, Integer.toString(DEFAULT_STARTUP_DELAY));
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
        updateConfig();
        // Initialize bandwidth from config (not from I2CP detection)
        int maxdown = getInt(PROP_DOWNBW_MAX, DEFAULT_MAX_DOWN_BW);
        _bwManager.setDownBWLimit(maxdown * 1024L);
        int maxup = getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW);
        _bwManager.setUpBWLimit(maxup * 1024L);
    }

    /**
     * @since 0.9.31
     */
    public boolean getUniversalTheming() {
        return _context.getBooleanProperty(RC_PROP_UNIVERSAL_THEMING);
    }

    /**
     * Get current theme.
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
     * Get all themes
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
                List<String> th = new ArrayList<String>(dirnames.length);
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
        Map<String, String> i2cpOpts = new HashMap<String, String>();
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
        _util.setStartupDelay(getInt(PROP_STARTUP_DELAY, DEFAULT_STARTUP_DELAY));
        _util.setFilesPublic(areFilesPublic());
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

        for (String c : _config.stringPropertyNames()) {
            if (c.startsWith(PROP_API_PREFIX)) {
                String tgt = c.substring(PROP_API_PREFIX.length());
                String key = _config.getProperty(c);
                // we only support one for now
                _util.setAPI(tgt, key);
                break;
            }
        }

        File dd = getDataDir();

        if (dd.isDirectory()) {
            if (!dd.canWrite()) {
                msg = _t("No write permissions for data directory") + ": " + dd;
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            }
        } else {
            if (!dd.mkdirs()) {
                msg = _t("Data directory cannot be created") + ": " + dd;
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            }
        }
        initTrackerMap();
        initTorrentCreateFilterMap();
    }

    private int getInt(String prop, int defaultVal) {
        String p = _config.getProperty(prop);
        try {
            if ((p != null) && (p.trim().length() > 0)) {
                return Integer.parseInt(p.trim());
            }
        } catch (NumberFormatException nfe) {
        } // ignore
        return defaultVal;
    }

    /** all params may be null or need trimming */
    public void updateConfig(
            String dataDir,
            boolean filesPublic,
            boolean autoStart,
            String refreshDelay,
            String startDelay,
            String pageSize,
            String seedPct,
            String eepHost,
            String eepPort,
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
            String apiTarget,
            String apiKey) {
        synchronized (_configLock) {
            locked_updateConfig(
                    dataDir,
                    filesPublic,
                    autoStart,
                    refreshDelay,
                    startDelay,
                    pageSize,
                    seedPct,
                    eepHost,
                    eepPort,
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
                    apiTarget,
                    apiKey);
        }
    }

    private void locked_updateConfig(
            String dataDir,
            boolean filesPublic,
            boolean autoStart,
            String refreshDelay,
            String startDelay,
            String pageSize,
            String seedPct,
            String eepHost,
            String eepPort,
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
            String apiTarget,
            String apiKey) {
        boolean changed = false;
        boolean interruptMonitor = false;

        if (upLimit != null) {
            int limit = _util.getMaxUploaders();
            try {
                limit = Integer.parseInt(upLimit.trim());
            } catch (NumberFormatException nfe) {
            }
            if (limit != _util.getMaxUploaders()) {
                if (limit >= Snark.MIN_TOTAL_UPLOADERS) {
                    _util.setMaxUploaders(limit);
                    changed = true;
                    _config.setProperty(PROP_UPLOADERS_TOTAL, Integer.toString(limit));
                    String msg = _t("Total uploaders limit changed to {0}", limit);
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                } else {
                    String msg = _t("Minimum uploaders limit is {0}", Snark.MIN_TOTAL_UPLOADERS);
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                }
            }
        }
        if (upBW != null) {
            int limit = _util.getMaxUpBW();
            try {
                limit = Integer.parseInt(upBW.trim());
            } catch (NumberFormatException nfe) {
            }
            if (limit != _util.getMaxUpBW()) {
                if (limit >= MIN_UP_BW) {
                    _bwManager.setUpBWLimit(limit * 1024L);
                    changed = true;
                    _config.setProperty(PROP_UPBW_MAX, Integer.toString(limit));
                    String msg = _t("Up BW limit changed to {0}KBps", limit);
                    // addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                } else {
                    String msg = _t("Minimum up bandwidth limit is {0}KBps", MIN_UP_BW);
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                }
            }
        }
        if (upBW != null) {
            int limit = _util.getMaxUpBW();
            try {
                limit = Integer.parseInt(upBW.trim());
            } catch (NumberFormatException nfe) {
            }
            if (limit != _util.getMaxUpBW()) {
                if (limit >= MIN_UP_BW) {
                    _util.setMaxUpBW(limit);
                    _bwManager.setUpBWLimit(limit * 1024L);
                    changed = true;
                    _config.setProperty(PROP_UPBW_MAX, Integer.toString(limit));
                    String msg = _t("Up BW limit changed to {0}KBps", limit);
                    // addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                } else {
                    String msg = _t("Minimum up bandwidth limit is {0}KBps", MIN_UP_BW);
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                }
            }
        }
        if (downBW != null) {
            int limit = (int) (_bwManager.getDownBWLimit() / 1024);
            try {
                limit = Integer.parseInt(downBW.trim());
            } catch (NumberFormatException nfe) {
            }
            if (limit != _bwManager.getDownBWLimit() / 1024) {
                if (limit >= MIN_DOWN_BW) {
                    _bwManager.setDownBWLimit(limit * 1024L);
                    _config.setProperty(PROP_DOWNBW_MAX, Integer.toString(limit));
                    changed = true;
                    String msg = _t("Maximum download speed changed to {0}KB/s", limit);
                    // addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                } else {
                    String msg = _t("Download speed limit is {0}KB/s", MIN_DOWN_BW);
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
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

        if (startDelay != null && _context.isRouterContext()) {
            int minutes = _util.getStartupDelay();
            try {
                minutes = Integer.parseInt(startDelay.trim());
            } catch (NumberFormatException nfe) {
            }
            if (minutes != _util.getStartupDelay()) {
                _util.setStartupDelay(minutes);
                changed = true;
                _config.setProperty(PROP_STARTUP_DELAY, Integer.toString(minutes));
                String msg =
                        _t(
                                "Startup delay changed to {0}",
                                DataHelper.formatDuration2(minutes * (60L * 1000)));
                addMessageNoEscape(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            }
        }

        if (refreshDelay != null) {
            try {
                int secs = Integer.parseInt(refreshDelay.trim());
                if (secs != getRefreshDelaySeconds()) {
                    changed = true;
                    _config.setProperty(PROP_REFRESH_DELAY, Integer.toString(secs));
                    if (secs >= 0) {
                        String msg =
                                _t(
                                        "Refresh time changed to {0}",
                                        DataHelper.formatDuration2(secs * 1000));
                        addMessageNoEscape(msg);
                        if (!_context.isRouterContext()) {
                            System.out.println(" • " + msg.replace("&nbsp;", " "));
                        }
                    } else {
                        String msg = _t("Refresh disabled");
                        addMessage(msg);
                        if (!_context.isRouterContext()) {
                            System.out.println(" • " + msg);
                        }
                    }
                }
            } catch (NumberFormatException nfe) {
            }
        }

        if (pageSize != null) {
            try {
                int size = Integer.parseInt(pageSize.trim());
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
            } catch (NumberFormatException nfe) {
            }
        }

        // set this before we check the data dir
        if (areFilesPublic() != filesPublic) {
            _config.setProperty(PROP_FILES_PUBLIC, Boolean.toString(filesPublic));
            _util.setFilesPublic(filesPublic);
            if (filesPublic) {
                String msg = _t("New files will be publicly readable");
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            } else {
                String msg = _t("New files will not be publicly readable");
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
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
                // save this tag for now, may need it again
                if (false) {
                    String msg = _t("Data directory does not exist") + ": " + dataDir;
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                }
                String msg = _t("Data directory cannot be created") + ": " + dataDir;
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            } else if (!dd.isDirectory()) {
                String msg = _t("Not a directory") + ": " + dataDir;
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            } else if (!dd.canRead()) {
                String msg = _t("Unreadable") + ": " + dataDir;
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            } else {
                if (!dd.canWrite()) {
                    String msg = _t("No write permissions for data directory") + ": " + dataDir;
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
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
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            }
        }

        // Standalone (app context) language.
        // lang will generally be null since it is hidden from the form if in router context.
        if (lang != null
                && !_context.isRouterContext()
                && lang.length() >= 2
                && lang.length() <= 6) {
            int under = lang.indexOf('_');
            String nlang, ncountry;
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
            try {
                port = Integer.parseInt(i2cpPort);
            } catch (NumberFormatException nfe) {
            }
        }

        Map<String, String> opts = new HashMap<String, String>();
        i2cpOpts = DataHelper.stripHTML(i2cpOpts);
        StringTokenizer tok = new StringTokenizer(i2cpOpts, " \t\n");
        while (tok.hasMoreTokens()) {
            String pair = tok.nextToken();
            int split = pair.indexOf('=');
            if (split > 0) {
                opts.put(pair.substring(0, split), pair.substring(split + 1));
            }
        }
        Map<String, String> oldOpts = new HashMap<String, String>();
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
                        && i2cpHost.trim().length() > 0
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
                _bwManager.setUpBWLimit(max * 1024);
                String msg =
                        _t("I2CP and tunnel changes will take effect after stopping all torrents");
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            } else if (!reconnect) {
                // The usual case, the other two are if not in router context
                _config.setProperty(PROP_I2CP_OPTS, i2cpOpts.trim());
                String msg = _t("I2CP options changed to: {0}", i2cpOpts);
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                _util.setI2CPConfig(oldI2CPHost, oldI2CPPort, opts);
            } else {
                // Won't happen, I2CP host/port, are hidden in the GUI if in router context
                if (_util.connected()) {
                    _util.disconnect();
                    String msg = _t("Disconnecting old I2CP destination");
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                }
                String msg =
                        _t("I2CP options changed to: {0}", i2cpHost + ':' + port + ' ' + i2cpOpts);
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                _util.setI2CPConfig(i2cpHost, port, opts);
                int max = getInt(PROP_UPBW_MAX, DEFAULT_MAX_UP_BW);
                _util.setMaxUpBW(max);
                _bwManager.setUpBWLimit(max * 1024);
                boolean ok = _util.connect();
                if (!ok) {
                    msg =
                            _t(
                                    "Unable to connect with the new settings, reverting to the old"
                                        + " I2CP settings");
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                    _util.setI2CPConfig(oldI2CPHost, oldI2CPPort, oldOpts);
                    ok = _util.connect();
                    if (!ok) {
                        msg = _t("Unable to reconnect with the old settings!");
                        addMessage(msg);
                        if (!_context.isRouterContext()) {
                            System.out.println(" • " + msg);
                        }
                    }
                } else {
                    msg = _t("Reconnected on the new I2CP destination");
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                    _config.setProperty(PROP_I2CP_HOST, i2cpHost.trim());
                    _config.setProperty(PROP_I2CP_PORT, "" + port);
                    _config.setProperty(PROP_I2CP_OPTS, i2cpOpts.trim());
                    // no PeerAcceptors/I2PServerSockets to deal with, since all snarks are inactive
                    for (Snark snark : _snarks.values()) {
                        if (snark.restartAcceptor()) {
                            msg = _t("I2CP listener restarted for \"{0}\"", snark.getBaseName());
                            addMessage(msg);
                            if (!_context.isRouterContext()) {
                                System.out.println(" • " + msg);
                            }
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
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            } else {
                msg = _t("Disabled open trackers - torrent restart required to take effect.");
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
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
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
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
            commentName = commentName.trim().replaceAll("[\n\r<>#;]", "");
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

        if (theme != null) {
            if (!theme.equals(_config.getProperty(PROP_THEME))) {
                _config.setProperty(PROP_THEME, theme);
                changed = true;
            }
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

        if (apiKey != null && apiKey.length() > 0 && apiTarget != null && apiTarget.length() > 0) {
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

        if (changed) {
            saveConfig();
            // Data dir changed. This will stop and remove all old torrents, and add the new ones
            if (interruptMonitor) {
                _monitor.interrupt();
            }
        }
    }

    /**
     * Others should use the version in I2PSnarkUtil
     *
     * @return non-null, empty if disabled
     * @since 0.9.1
     */
    private List<String> getOpenTrackers() {
        if (!_util.shouldUseOpenTrackers()) return Collections.emptyList();
        return getListConfig(PROP_OPENTRACKERS, DEFAULT_OPENTRACKERS);
    }

    /**
     * @return non-null, fixed size, may be empty or unmodifiable
     * @since 0.9.1
     */
    public List<String> getPrivateTrackers() {
        return getListConfig(PROP_PRIVATETRACKERS, null);
    }

    /**
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
        addMessage(msg);
        if (!_context.isRouterContext()) {
            System.out.println(" • " + msg);
        }
        saveConfig();
    }

    /**
     * @param pt null ok, default is none
     * @since 0.9.1
     */
    public void savePrivateTrackers(List<String> pt) {
        setListConfig(PROP_PRIVATETRACKERS, pt);
        String msg = _t("Private tracker list changed - affects newly created torrents only.");
        addMessage(msg);
        if (!_context.isRouterContext()) {
            System.out.println(" • " + msg);
        }
        saveConfig();
    }

    /**
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
     * Sets the config, does NOT save it
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
            addMessage(msg);
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg);
            }
        }
    }

    /** Set of canonical .torrent filenames that we are dealing with. An unsynchronized copy. */
    public Set<String> listTorrentFiles() {
        return new HashSet<String>(_snarks.keySet());
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
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            } else {
                if (FileUtil.rename(sfile, rename)) {
                    msg =
                            _t(
                                    "Torrent file moved from {0} to {1}",
                                    sfile.toString(), rename.toString());
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                }
            }
        }
    }

    /**
     * Caller must verify this torrent is not already added.
     *
     * @param filename the absolute path to save the metainfo to, generally ending in ".torrent"
     * @param baseFile may be null, if so look in dataDir
     * @param dontAutoStart must be false, AND running=true or null in torrent config file, to start
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
     * @param dontAutoStart must be false, AND running=true or null in torrent config file, to start
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
            addMessage(msg);
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg);
            }
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
                        addMessage(msg);
                        if (!_context.isRouterContext()) {
                            System.out.println(" • " + msg);
                        }
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
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
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
                    } catch (IOException e) {
                    }

                    // These tests may be duplicates, but not if we were called
                    // from the DirMonitor, which only checks for dup torrent file names.
                    Snark snark = getTorrentByInfoHash(info.getInfoHash());
                    if (snark != null) {
                        // TODO - if the existing one is a magnet, delete it and add the metainfo
                        // instead?
                        msg =
                                _t(
                                        "Torrent with this info hash is already running: {0}",
                                        snark.getBaseName());
                        addMessage(msg);
                        if (!_context.isRouterContext()) {
                            System.out.println(" • " + msg);
                        }
                        return false;
                    }
                    String filtered = Storage.filterName(info.getName());
                    snark = getTorrentByBaseName(filtered);
                    if (snark != null) {
                        msg =
                                _t(
                                        "Torrent with the same data location is already running:"
                                            + " {0}",
                                        snark.getBaseName());
                        addMessage(msg);
                        if (!_context.isRouterContext()) {
                            System.out.println(" • " + msg);
                        }
                        return false;
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
                        } catch (IOException ioe2) {
                        }
                    String err =
                            _t("Torrent in \"{0}\" is invalid", sfile.toString())
                                    + ": "
                                    + ioe.getLocalizedMessage();
                    addMessage(err);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + err);
                    }
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
                        } catch (IOException ioe) {
                        }
                    }
                }
            }
        } else {
            msg = _t("Torrent already running: {0}", filename);
            addMessage(msg);
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg);
            }
            return false;
        }
        // ok, snark created, now let's start it up or configure it further
        Properties config = getConfig(torrent);
        String prop = config.getProperty(PROP_META_RUNNING);
        boolean running = prop == null || Boolean.parseBoolean(prop);
        prop = config.getProperty(PROP_META_ACTIVITY);
        if (prop != null && torrent.getStorage() != null) {
            try {
                long activity = Long.parseLong(prop);
                torrent.getStorage().setActivity(activity);
            } catch (NumberFormatException nfe) {
            }
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
                        addMessage(msg);
                        System.out.println(" • " + msg);
                    }
                    // this would rename the torrent to .BAD
                    // return false;
                }
            }
            torrent.startTorrent();
            addMessageNoEscape(_t("Torrent added and started: {0}", torrentLink));
            if (!_context.isRouterContext()) {
                System.out.println(
                        " • " + _t("Torrent added and started: {0}", torrent.getBaseName()));
            }
        } else {
            addMessageNoEscape(_t("Torrent added: {0}", torrentLink));
            if (!_context.isRouterContext()) {
                System.out.println(" • " + _t("Torrent added: {0}", torrent.getBaseName()));
            }
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
            addMessage(warnMsg);
            if (!_context.isRouterContext()) {
                System.out.println(" • " + warnMsg);
            }
        }

        return true;
    }

    /**
     * Add a torrent with the info hash alone (magnet / maggot)
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we add this magnet to the config file, to save it across restarts,
     *     in case we don't get the metadata before shutdown?
     * @throws RuntimeException via Snark.fatal()
     * @since 0.8.4
     */
    public void addMagnet(String name, byte[] ih, String trackerURL, boolean updateStatus) {
        // updateStatus is true from UI, false from config file bulk add
        addMagnet(name, ih, trackerURL, updateStatus, updateStatus, null, this);
    }

    /**
     * Add a torrent with the info hash alone (magnet / maggot)
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we add this magnet to the config file, to save it across restarts,
     *     in case we don't get the metadata before shutdown?
     * @param dataDir must exist, or null to default to snark data directory
     * @throws RuntimeException via Snark.fatal()
     * @since 0.9.17
     */
    public void addMagnet(
            String name, byte[] ih, String trackerURL, boolean updateStatus, File dataDir) {
        // updateStatus is true from UI, false from config file bulk add
        addMagnet(name, ih, trackerURL, updateStatus, updateStatus, dataDir, this);
    }

    /**
     * Add a torrent with the info hash alone (magnet / maggot) External use is for UpdateRunner.
     *
     * @param name hex or b32 name from the magnet link
     * @param ih 20 byte info hash
     * @param trackerURL may be null
     * @param updateStatus should we add this magnet to the config file, to save it across restarts,
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
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
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
            if (false) {
                addMessage(_t("Fetching {0}", name));
            }
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
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            }
        } else {
            msg = _t("Adding {0}", name);
            addMessage(msg);
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg);
            }
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
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                return;
            }
            String name = torrent.getName();
            _magnets.add(name); // Tell the dir monitor not to delete us
            putSnark(name, torrent);
        }
        torrent.startTorrent();
    }

    /**
     * Add a torrent from a MetaInfo. Save the MetaInfo data to filename. Holds the snarks lock to
     * prevent interference from the DirMonitor. This verifies that a torrent with this infohash is
     * not already added. This may take a LONG time to create or check the storage.
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
        // prevent interference by DirMonitor
        synchronized (_snarks) {
            Snark snark = getTorrentByInfoHash(metainfo.getInfoHash());
            String msg;
            if (snark != null) {
                msg =
                        _t(
                                "Torrent with this info hash is already running: {0}",
                                snark.getBaseName());
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                return false;
            }
            String filtered = Storage.filterName(metainfo.getName());
            snark = getTorrentByBaseName(filtered);
            if (snark != null) {
                msg =
                        _t(
                                "Torrent with the same data location is already running: {0}",
                                snark.getBaseName());
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                return false;
            }
            if (bitfield != null) {
                saveTorrentStatus(
                        metainfo, bitfield, null, false, baseFile, true, 0, 0,
                        true); // no file priorities
            }
            // Prevent addTorrent from rechecking
            if (filename == null) {
                File f = new File(getDataDir(), filtered + ".torrent");
                if (f.exists()) {
                    msg =
                            _t("Failed to copy torrent file to {0}", f.getAbsolutePath())
                                    + _t(" - torrent file already exists");
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                    _log.error("[I2PSnark] Torrent file already exists: " + f);
                }
                filename = f.getAbsolutePath();
            }
            try {
                locked_writeMetaInfo(
                        metainfo, filename, areFilesPublic()); // hold the lock for a long time
                return addTorrent(filename, baseFile, dontAutoStart);
            } catch (IOException ioe) {
                msg = _t("Failed to copy torrent file to {0}", filename);
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
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
        OutputStream out = null;
        try {
            if (areFilesPublic) {
                out = new FileOutputStream(filename);
            } else {
                out = new SecureFileOutputStream(filename);
            }
            out.write(metainfo.getTorrentData());
        } catch (IOException ioe) {
            file.delete(); // remove any partial
            throw ioe;
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ioe) {
            }
        }
    }

    /** Get the timestamp for a torrent from the config file. A Snark.CompleteListener method. */
    public long getSavedTorrentTime(Snark snark) {
        Properties config = getConfig(snark);
        String time = config.getProperty(PROP_META_STAMP);
        if (time == null) {
            return 0;
        }
        try {
            return Long.parseLong(time);
        } catch (NumberFormatException nfe) {
        }
        return 0;
    }

    /**
     * Get the saved bitfield for a torrent from the config file. Convert "." to a full bitfield. A
     * Snark.CompleteListener method.
     */
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
     * Get the saved priorities for a torrent from the config file.
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
                if (arr[i].length() > 0) {
                    try {
                        rv[i] = Integer.parseInt(arr[i]);
                    } catch (Throwable t) {
                    }
                }
            }
            storage.setFilePriorities(rv);
        }
        boolean inOrder = Boolean.parseBoolean(config.getProperty(PROP_META_INORDER));
        storage.setInOrder(inOrder);
    }

    /**
     * Get the base location for a torrent from the config file.
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
     * Get setting for a torrent from the config file.
     *
     * @return setting, false if not found
     * @since 0.9.15
     */
    public boolean getSavedPreserveNamesSetting(Snark snark) {
        Properties config = getConfig(snark);
        return Boolean.parseBoolean(config.getProperty(PROP_META_PRESERVE_NAMES));
    }

    /**
     * Get setting for a torrent from the config file.
     *
     * @return setting, 0 if not found
     * @since 0.9.15
     */
    public long getSavedUploaded(Snark snark) {
        Properties config = getConfig(snark);
        if (config != null) {
            try {
                return Long.parseLong(config.getProperty(PROP_META_UPLOADED));
            } catch (NumberFormatException nfe) {
            }
        }
        return 0;
    }

    /**
     * Get setting for a torrent from the config file.
     *
     * @return non-null, rv[0] is added time or 0; rv[1] is completed time or 0
     * @since 0.9.23
     */
    public long[] getSavedAddedAndCompleted(Snark snark) {
        long[] rv = new long[2];
        Properties config = getConfig(snark);
        if (config != null) {
            try {
                rv[0] = Long.parseLong(config.getProperty(PROP_META_ADDED));
            } catch (NumberFormatException nfe) {
            }
            try {
                rv[1] = Long.parseLong(config.getProperty(PROP_META_COMPLETED));
            } catch (NumberFormatException nfe) {
            }
        }
        return rv;
    }

    /**
     * Get setting for comments enabled from the config file. Caller must first check global
     * I2PSnarkUtil.commentsEnabled() Default true.
     *
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
     * Set setting for comments enabled in the config file.
     *
     * @since 0.9.31
     */
    public void setSavedCommentsEnabled(Snark snark, boolean yes) {
        saveTorrentStatus(snark, Boolean.valueOf(yes));
    }

    /**
     * Save the completion status of a torrent and other data in the config file for that torrent.
     * Does nothing for magnets.
     *
     * @since 0.9.15
     */
    public void saveTorrentStatus(Snark snark) {
        saveTorrentStatus(snark, null);
    }

    /**
     * Save the completion status of a torrent and other data in the config file for that torrent.
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
                storage.getInOrder(),
                storage.getBase(),
                storage.getPreserveFileNames(),
                snark.getUploaded(),
                storage.getActivity(),
                snark.isStopped(),
                comments);
    }

    /**
     * Save the completion status of a torrent and the current time in the config file for that
     * torrent. The time is a standard long converted to string. The status is either a bitfield
     * converted to Base64 or "." for a completed torrent to save space in the config file and in
     * memory.
     *
     * @param metainfo non-null
     * @param bitfield non-null
     * @param priorities may be null
     * @param base may be null
     */
    private void saveTorrentStatus(
            MetaInfo metainfo,
            BitField bitfield,
            int[] priorities,
            boolean inOrder,
            File base,
            boolean preserveNames,
            long uploaded,
            long activity,
            boolean stopped) {
        saveTorrentStatus(
                metainfo,
                bitfield,
                priorities,
                inOrder,
                base,
                preserveNames,
                uploaded,
                activity,
                stopped,
                null);
    }

    /*
     * @param comments null for no change
     * @since 0.9.31
     */
    private void saveTorrentStatus(
            MetaInfo metainfo,
            BitField bitfield,
            int[] priorities,
            boolean inOrder,
            File base,
            boolean preserveNames,
            long uploaded,
            long activity,
            boolean stopped,
            Boolean comments) {
        synchronized (_configLock) {
            locked_saveTorrentStatus(
                    metainfo,
                    bitfield,
                    priorities,
                    inOrder,
                    base,
                    preserveNames,
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
            boolean inOrder,
            File base,
            boolean preserveNames,
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
        config.setProperty(PROP_META_PRESERVE_NAMES, Boolean.toString(preserveNames));
        config.setProperty(PROP_META_UPLOADED, Long.toString(uploaded));
        boolean running = !stopped;
        config.setProperty(PROP_META_RUNNING, Boolean.toString(running));
        config.setProperty(PROP_META_INORDER, Boolean.toString(inOrder));
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
                    // only output if !inOrder || !skipped so the string isn't too long
                    if (priorities[i] != 0 && (!inOrder || priorities[i] < 0)) {
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
     * @since 0.9.23
     */
    private void locked_saveTorrentStatus(byte[] ih, Properties config) {
        File conf = configFile(_configDir, ih);
        // force autostart for new torrents
        if (shouldAutoStart() && !conf.exists()) {
            config.setProperty(PROP_META_RUNNING, "true");
        }
        File subdir = conf.getParentFile();
        if (!subdir.exists()) {
            subdir.mkdirs();
        }
        try {
            I2PSnarkUtil.storeProps(config, conf);
            if (_log.shouldInfo()) {
                _log.info("Saved config to " + conf);
            }
        } catch (IOException ioe) {
            _log.error("Unable to save the config to " + conf);
        }
    }

    /** Remove the status of a torrent by removing the config file. */
    private void removeTorrentStatus(Snark snark) {
        byte[] ih = snark.getInfoHash();
        File conf = configFile(_configDir, ih);
        File comm = commentFile(_configDir, ih);
        synchronized (_configLock) {
            comm.delete();
            boolean ok = conf.delete();
            if (ok) {
                if (_log.shouldInfo()) {
                    _log.info("Deleted " + conf + " for " + snark.getName());
                }
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Failed to delete " + conf + " for " + snark.getName());
                }
            }
            File subdir = conf.getParentFile();
            String[] files = subdir.list();
            if (files != null && files.length == 0) {
                subdir.delete();
            }
        }
    }

    /**
     * Remove all orphaned torrent status files, which weren't removed before 0.9.20, and could be
     * left around after a manual delete also. Run this once at startup.
     *
     * @since 0.9.20
     */
    private void cleanupTorrentStatus() {
        Set<SHA1Hash> torrents = new HashSet<SHA1Hash>(32);
        int found = 0;
        int totalDeleted = 0;
        synchronized (_snarks) {
            for (Snark snark : _snarks.values()) {
                torrents.add(new SHA1Hash(snark.getInfoHash()));
            }
            synchronized (_configLock) {
                for (int i = 0; i < B64.length(); i++) {
                    File subdir = new File(_configDir, SUBDIR_PREFIX + B64.charAt(i));
                    File[] configs = subdir.listFiles();
                    if (configs == null) {
                        continue;
                    }
                    int deleted = 0;
                    for (int j = 0; j < configs.length; j++) {
                        File config = configs[j];
                        SHA1Hash ih = configFileToInfoHash(config);
                        if (ih == null) {
                            continue;
                        }
                        found++;
                        if (torrents.contains(ih)) {
                            if (_log.shouldInfo()) {
                                _log.info("Torrent for " + config + " exists");
                            }
                        } else {
                            boolean ok = config.delete();
                            if (ok) {
                                if (_log.shouldInfo()) {
                                    _log.info("Deleted " + config + " for " + ih);
                                }
                                deleted++;
                            } else {
                                if (_log.shouldWarn()) {
                                    _log.warn("Failed to delete " + config + " for " + ih);
                                }
                            }
                        }
                    }
                    if (deleted == configs.length) {
                        if (_log.shouldInfo()) {
                            _log.info("Deleting " + subdir);
                        }
                        subdir.delete();
                    }
                    totalDeleted += deleted;
                }
            }
        }
        if (totalDeleted > 0) {
            String msg =
                    "Metadata cleaner removed "
                            + totalDeleted
                            + " orphaned torrent config "
                            + (totalDeleted > 1 ? "folders" : "folder");
            if (_log.shouldInfo()) {
                _log.info(msg);
            }
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg);
            }
        }
    }

    /**
     * Just remember we have it. This used to simply store a line in the config file, but now we
     * also save it in its own config file, just like other torrents, so we can remember the
     * directory, tracker, etc.
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
        // its own config file
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
     * Remove the magnet marker from the config file.
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
     * Does not really delete on failure, that's the caller's responsibility. Warning - does not
     * validate announce URL - use TrackerClient.isValidAnnounce()
     *
     * @return failure message or null on success
     */
    private String validateTorrent(MetaInfo info) {
        List<List<String>> files = info.getFiles();
        if (files != null && files.size() > _util.getMaxFilesPerTorrent()) {
            return _t("Too many files in \"{0}\" ({1})!", info.getName(), files.size())
                    + " - limit is "
                    + _util.getMaxFilesPerTorrent()
                    + ", zip them or set "
                    + PROP_MAX_FILES_PER_TORRENT
                    + '='
                    + files.size()
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
     * shouldRemove is true, removes the config file also.
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
        int remaining = 0;
        Snark torrent = null;
        synchronized (_snarks) {
            if (shouldRemove) {
                torrent = removeSnark(filename);
            } else {
                torrent = _snarks.get(filename);
            }
            remaining = _snarks.size();
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
     * shouldRemove is true, removes the config file also.
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
                addMessageNoEscape(
                        _t("Torrent stopped: {0}", linkify(torrent).replace("Magnet ", "")));
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + _t("Torrent stopped: {0}", getSnarkName(torrent)));
                }
                stopTorrent(torrent, false);
            }
        }
    }

    /**
     * Stop the torrent and delete the torrent file itself, but leaving the data behind. Removes
     * saved config file also. Holds the snarks lock to prevent interference from the DirMonitor.
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
        addMessage(_t("Torrent removed: {0}", torrent.getBaseName()));
        if (!_context.isRouterContext()) {
            System.out.println(" • " + _t("Torrent removed: {0}", getSnarkName(torrent)));
        }
    }

    /**
     * This calls monitorTorrents() once a minute. It also gets the bandwidth limits and loads
     * magnets on first run. For standalone, it also handles checking that the external router is
     * there, and restarting torrents once the router appears.
     */
    private class DirMonitor implements Runnable {
        public void run() {
            File dir = getDataDir();
            getStorageSpace(dir);
            long delay =
                    (60L * 1000)
                            * getStartupDelayMinutes(); // Don't bother delaying if auto start is
                                                        // false
            boolean autostart = shouldAutoStart();
            if (delay == 0) {
                delay = 30000;
            }
            if (delay > 30000 && autostart) {
                int id =
                        _messages.addMessageNoEscape(
                                getTime()
                                        + "&nbsp; "
                                        + _t(
                                                "Adding torrents in {0}" + "&hellip;",
                                                DataHelper.formatDuration2(delay)));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                }
                _messages.clearThrough(id); // Remove that first message
            } else if (_context.isRouterContext()) {
                try {
                    Thread.sleep(3000);
                } // Wait for client manager to be up so we can get bandwidth limits
                catch (InterruptedException ie) {
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
            addMessage(bwMsg);
            if (!_context.isRouterContext()) {
                System.out.println(" • " + bwMsg);
            }

            while (_running) {
                String i2cpConnectMsg =
                        " • "
                                + _t(
                                        "Connecting to I2CP port on I2P instance at {0}",
                                        _util.getI2CPHost() + ':' + _util.getI2CPPort() + "...");
                if (_log.shouldDebug()) {
                    _log.debug(
                            "DirectoryMonitor scanning I2PSnark data dir: "
                                    + dir.getAbsolutePath());
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
                            // Start previously added torrents
                            for (Snark snark : _snarks.values()) {
                                Properties config = getConfig(snark);
                                String prop = config.getProperty(PROP_META_RUNNING);
                                if (prop == null || Boolean.parseBoolean(prop)) {
                                    if (!_util.connected()) {
                                        String msg = _t("Connecting to I2P") + "...";
                                        addMessage(msg);
                                        if (!_context.isRouterContext()) {
                                            System.out.println(i2cpConnectMsg);
                                        }
                                        // getBWLimit() was successful so this should work
                                        boolean ok = _util.connect();
                                        if (!ok) {
                                            if (_context.isRouterContext()) {
                                                addMessage(_t("Unable to connect to I2P"));
                                            } else {
                                                msg =
                                                        _t(
                                                                        "Error connecting to I2P -"
                                                                            + " check your I2CP"
                                                                            + " settings!")
                                                                + ' '
                                                                + _util.getI2CPHost()
                                                                + ':'
                                                                + _util.getI2CPPort();
                                                addMessage(msg);
                                                System.out.println(" • " + msg);
                                            }
                                            routerOK = false;
                                            autostart = false;
                                            break;
                                        } else {
                                            if (!_context.isRouterContext()) {
                                                msg =
                                                        _t("Connected to I2P at")
                                                                + ' '
                                                                + _util.getI2CPHost()
                                                                + ':'
                                                                + _util.getI2CPPort();
                                                System.out.println(" • " + msg);
                                            }
                                        }
                                    }
                                    addMessageNoEscape(
                                            _t("Starting up torrent {0}", linkify(snark)));
                                    try {
                                        snark.startTorrent();
                                    } catch (Snark.RouterException re) {
                                        break;
                                    } // Snark.fatal() will log and call fatal() here for user
                                      // message before throwing
                                    catch (RuntimeException re) {
                                    } // Snark.fatal() will log and call fatal() here for user
                                      // message before throwing
                                }
                            }
                        }
                    } else {
                        autostart = false;
                    }
                }
                boolean ok;
                try {
                    // Don't let this interfere with .torrent files being added or deleted
                    synchronized (_snarks) {
                        ok = monitorTorrents(dir, autostart);
                    }
                } catch (RuntimeException e) {
                    _log.error("Error in the DirectoryMonitor", e);
                    ok = false;
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
                     * Don't run if there was an error, as we would delete the torrent config file(s) and we don't want to do that.
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
                            addMessage(msg);
                            System.out.println(" • " + msg);
                        }
                    }
                }
                try {
                    Thread.sleep(30 * 1000);
                } // Polling period for scanning data dir for new content
                catch (InterruptedException ie) {
                }
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

        addMessage(msg);
        if (!_context.isRouterContext()) {
            System.out.println(" • " + msg);
        }
    }

    // Begin Snark.CompleteListeners

    /** A Snark.CompleteListener method. */
    public void torrentComplete(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        int pieces = snark.getPieces();
        boolean isComplete = pieces >= snark.getNeeded() && snark.getRemainingLength() == 0;
        if (meta == null || storage == null || snark == null || !isComplete) {
            return;
        }

        if (snark.isStorageCompleted() && isComplete && !snark.isNotificationSent()) {
            addMessageNoEscape(_t("Download finished: {0}", linkify(snark)));
            if (!_context.isRouterContext()) {
                String msg = _t("Download finished: {0}", getSnarkName(snark));
                System.out.println(" • " + msg);
            }
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
        }
        updateStatus(snark);
    }

    /** A Snark.CompleteListener method. */
    public void updateStatus(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta != null && storage != null)
            saveTorrentStatus(
                    meta,
                    storage.getBitField(),
                    storage.getFilePriorities(),
                    storage.getInOrder(),
                    storage.getBase(),
                    storage.getPreserveFileNames(),
                    snark.getUploaded(),
                    storage.getActivity(),
                    snark.isStopped());
    }

    /**
     * We transitioned from magnet mode, we have now initialized our metainfo and storage. The
     * listener should now call getMetaInfo() and save the data to disk. A Snark.CompleteListener
     * method.
     *
     * @return the new name for the torrent or null on error
     * @since 0.8.4
     */
    public String gotMetaInfo(Snark snark) {
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();
        if (meta != null && storage != null) {
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
                    false,
                    storage.getBase(),
                    storage.getPreserveFileNames(),
                    0,
                    0,
                    snark.isStopped());
            // temp for addMessage() in case canonical throws
            String name = storage.getBaseName();
            try {
                // _snarks must use canonical
                name =
                        (new File(getDataDir(), storage.getBaseName() + ".torrent"))
                                .getCanonicalPath();
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
                _log.error("Failed to write torrent file -> " + ioe.getMessage());
            }
        }
        return null;
    }

    /**
     * A Snark.CompleteListener method.
     *
     * @since 0.9
     */
    public void fatal(Snark snark, String error) {
        addMessage(error);
    }

    /**
     * A Snark.CompleteListener method.
     *
     * @since 0.9.2
     */
    public void addMessage(Snark snark, String message) {
        addMessage(message);
    }

    /**
     * A Snark.CompleteListener method.
     *
     * @since 0.9.4
     */
    public void gotPiece(Snark snark) {}

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
        for (Iterator<?> iter = _config.keySet().iterator(); iter.hasNext(); ) {
            String k = (String) iter.next();
            if (k.startsWith(PROP_META_MAGNET_PREFIX)) {
                String b64 = k.substring(PROP_META_MAGNET_PREFIX.length());
                b64 = b64.replace('$', '=');
                byte[] ih = Base64.decode(b64);
                // ignore value - TODO put tracker URL in value
                if (ih != null && ih.length == 20) {
                    Properties config = getConfig(ih);
                    String name = config.getProperty(PROP_META_MAGNET_DN);
                    if (name == null) {
                        name = _t("Magnet") + ' ' + I2PSnarkUtil.toHex(ih);
                    }
                    String tracker = config.getProperty(PROP_META_MAGNET_TR);
                    String dir = config.getProperty(PROP_META_MAGNET_DIR);
                    File dirf = (dir != null) ? (new File(dir)) : null;
                    addMagnet(name, ih, tracker, false, autostart, dirf, this);
                } else {
                    iter.remove();
                    changed = true;
                }
            }
        }
        if (changed) {
            saveConfig();
        }
    }

    /**
     * caller must synchronize on _snarks
     *
     * @param shouldStart should we autostart the torrents
     * @return success, false if an error adding any torrent.
     */
    private boolean monitorTorrents(File dir, boolean shouldStart) {
        boolean rv = true;
        File files[] = dir.listFiles(new FileSuffixFilter(".torrent"));
        List<String> foundNames = new ArrayList<String>(0);
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
        int count = 0;
        for (String name : foundNames) {
            if (existingNames.contains(name)) {
            } // already known. noop
            else {
                // Will call connect() in addTorrent() if enabled
                // if (shouldStart && !_util.connect())
                //    addMessage(_t("Unable to connect to I2P!"));
                try {
                    // Snark.fatal() throws a RuntimeException
                    // don't let one bad torrent kill the whole loop
                    boolean ok = addTorrent(name, null, !shouldStart);
                    if (!ok) {
                        addMessage(_t("Error: Could not add torrent: {0}", name));
                        _log.error("Unable to add torrent: " + name);
                        disableTorrentFile(name);
                        rv = false;
                    }
                } catch (Snark.RouterException e) {
                    addMessage(
                            _t("Error: Could not add torrent: {0}", name) + ": " + e.getMessage());
                    _log.error("Unable to add torrent: " + name + "\n* Reason: " + e.getMessage());
                    return false;
                } catch (RuntimeException e) {
                    addMessage(
                            _t("Error: Could not add torrent: {0}", name) + ": " + e.getMessage());
                    _log.error("Unable to add torrent: " + name + "\n* Reason: " + e.getMessage());
                    disableTorrentFile(name);
                    rv = false;
                }
                if (shouldStart && (count++ & 0x0f) == 15) {
                    // try to prevent OOMs at startup
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                    }
                }
            }
        }
        // Don't remove magnet torrents that don't have a torrent file yet
        existingNames.removeAll(_magnets);
        // now let's see which ones have been removed...
        for (String name : existingNames) {
            if (foundNames.contains(name)) {
            } // known and still there.  noop
            else { // known, but removed.  drop it
                try {
                    // Snark.fatal() throws a RuntimeException
                    // don't let one bad torrent kill the whole loop
                    stopTorrent(name, true);
                } catch (RuntimeException e) {
                } // don't bother with message
            }
        }
        return rv;
    }

    /** translate */
    private String _t(String s) {
        return _util.getString(s);
    }

    /** translate */
    private String _t(String s, Object o) {
        return _util.getString(s, o);
    }

    /** translate */
    private String _t(String s, Object o, Object o2) {
        return _util.getString(s, o, o2);
    }

    /**
     * mark for translation, does not translate
     *
     * @since 0.9.53
     */
    private static String _x(String s) {
        return s;
    }

    /**
     * Unsorted map of name to Tracker object Modifiable, not a copy
     *
     * @since 0.9.1
     */
    public Map<String, Tracker> getTrackerMap() {
        return _trackerMap;
    }

    /**
     * Unsorted map of name to TorrentCreateFilter object Modifiable, not a copy
     *
     * @since 0.9.62+
     */
    public Map<String, TorrentCreateFilter> getTorrentCreateFilterMap() {
        return _torrentCreateFilterMap;
    }

    /**
     * Returns the current number of configured file filters
     *
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
     * @since 0.9.62+
     */
    public Collection<TorrentCreateFilter> getTorrentCreateFilterStrings() {
        return _torrentCreateFilterMap.values();
    }

    /**
     * Sorted copy
     *
     * @since 0.9.1
     */
    public List<Tracker> getSortedTrackers() {
        List<Tracker> rv = new ArrayList<Tracker>(_trackerMap.values());
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
     * @since 0.9.62+
     */
    public List<TorrentCreateFilter> getSortedTorrentCreateFilterStrings() {
        List<TorrentCreateFilter> fv =
                new ArrayList<TorrentCreateFilter>(_torrentCreateFilterMap.values());
        Collections.sort(fv, new IgnoreCaseComparatorF());
        return fv;
    }

    /**
     * Has the default tracker list been modified?
     *
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
                if ((name.length() > 0) && (url.length() > 0)) {
                    String urls[] = DataHelper.split(url, "=", 2);
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
            if ((name.length() > 0) && (filterPattern.length() > 0)) {
                String data[] = DataHelper.split(filterPattern, "=", 2);
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

        try {
            FileInputStream file =
                    new FileInputStream(_configDir + "/" + PROP_TORRENT_FILTERS_CONFIG);
            ObjectInputStream in = new ObjectInputStream(file);
            Map<String, TorrentCreateFilter> filterMap = (Map) in.readObject();
            for (Map.Entry<String, TorrentCreateFilter> entry : filterMap.entrySet()) {
                _torrentCreateFilterMap.put(entry.getKey(), entry.getValue());
            }
            in.close();
            file.close();
        } catch (IOException ex) {
            String msg = _t("Unable to load torrent create file filter config: ");
            _log.error(msg + ex.getMessage());
            addMessage(msg + ex.getMessage());
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg + ex.getMessage());
            }
        } catch (ClassNotFoundException ex) {
            String msg = _t("Unable to load torrent create file filter config: ");
            _log.error(msg + ex.getMessage());
            addMessage(msg + ex.getMessage());
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg + ex.getMessage());
            }
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
        for (int i = 0; i < DEFAULT_TRACKERS.length; i += 2) {
            String name = DEFAULT_TRACKERS[i];
            if (name.equals("TheBland") && !SigType.ECDSA_SHA256_P256.isAvailable()) {
                continue;
            }
            String urls[] = DataHelper.split(DEFAULT_TRACKERS[i + 1], "=", 2);
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
        for (int i = 0; i < DEFAULT_TORRENT_CREATE_FILTERS.length; i += 3) {
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
        try {
            FileOutputStream file =
                    new FileOutputStream(_configDir + "/" + PROP_TORRENT_FILTERS_CONFIG);
            ObjectOutputStream out = new ObjectOutputStream(file);
            out.writeObject(_torrentCreateFilterMap);
            out.close();
            file.close();
        } catch (IOException ex) {
            String msg = _t("Unable to save torrent create file filter config: ");
            _log.error("[I2PSnark] " + msg + ex);
            addMessage(msg + ex.getMessage());
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg + ex.getMessage());
            }
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
            addMessageNoEscape(_t("Torrent already running: {0}", linkify(snark)));
            if (!_context.isRouterContext()) {
                System.out.println(" • " + _t("Torrent already running: {0}", getSnarkName(snark)));
            }
            return;
        }
        boolean connected = _util.connected();
        if ((!connected) && !_util.isConnecting()) {
            addMessage(_t("Opening the I2P tunnel") + "...");
        }
        addMessageNoEscape(_t("Starting torrent: {0}", linkify(snark)).replace("Magnet ", ""));
        if (!_context.isRouterContext()) {
            System.out.println(
                    " • "
                            + _t("Starting torrent: {0}", getSnarkName(snark))
                                    .replace("Magnet ", ""));
        }
        if (connected) {
            snark.startTorrent();
        } else {
            snark.setStarting(); // mark it for the UI
            (new I2PAppThread(new ThreadedStarter(snark), "TorrentStarter", true)).start();
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
            }
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
            addMessage(msg);
            if (!_context.isRouterContext()) {
                System.out.println(" • " + msg);
            }
            for (Snark snark : _snarks.values()) {
                snark.setStarting();
            } // mark it for the UI
            _stopping = false;
        }
        (new I2PAppThread(new ThreadedStarter(null), "TorrentStarterAll", true)).start();
        try {
            Thread.sleep(200);
        } catch (InterruptedException ie) {
        }
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
                    } catch (RuntimeException re) {
                    } // Snark.fatal() will log and call fatal() here for user message before
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
        int count = 0;
        for (Snark snark : _snarks.values()) {
            if (snark.isStopped()) {
                try {
                    snark.startTorrent();
                } catch (RuntimeException re) {
                } // Snark.fatal() will log and call fatal() here for user message before throwing
                if ((count++ & 0x0f) == 15) {
                    try {
                        Thread.sleep(250);
                    } // try to prevent OOMs
                    catch (InterruptedException ie) {
                    }
                }
            }
        }
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
                    if (count == 0) {
                        String msg = _t("Stopping all torrents and closing the I2P tunnel.") + "..";
                        addMessage(msg);
                        if (!_context.isRouterContext()) {
                            System.out.println(" • " + msg);
                        }
                    }
                    count++;
                    if (finalShutdown) {
                        snark.stopTorrent(true);
                    } else {
                        snark.stopTorrent(false);
                    }
                    if (count % 8 == 0) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ie) {
                        }
                    }
                }
            }
        }
        // Pass 2: All the rest of the torrents
        for (Snark snark : snarks) {
            if (!snark.isStopped()) {
                if (count == 0) {
                    String msg = _t("Stopping all torrents and closing the I2P tunnel.") + "..";
                    addMessage(msg);
                    if (!_context.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                }
                count++;
                if (finalShutdown) {
                    snark.stopTorrent(true);
                } else {
                    snark.stopTorrent(false);
                }
                // Throttle since every unannounce is now threaded.
                // How to do this without creating a ton of threads?
                if (count % 8 == 0) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ie) {
                    }
                }
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
        if (_util.connected()) {
            if (count > 0) {
                DHT dht = _util.getDHT();
                if (dht != null) {
                    dht.stop();
                }
                String msg = _t("Closing I2P tunnel after notifying trackers.") + "..";
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                if (finalShutdown) {
                    long toWait = 5 * 1000;
                    if (SystemVersion.isARM()) {
                        toWait *= 2;
                    }
                    try {
                        Thread.sleep(toWait);
                    } catch (InterruptedException ie) {
                    }
                    _util.disconnect();
                    _stopping = false;
                } else {
                    // Only schedule this if not a final shutdown
                    new Disconnector(60 * 1000);
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
        public Disconnector(long timeoutMs) {
            super(_context.simpleTimer2(), timeoutMs);
        }
        @Override
        public void timeReached() {
            if (_util.connected()) {
                _util.disconnect();
                _stopping = false;
                addMessage(_t("I2P tunnel closed."));
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
        (new I2PAppThread(new ThreadedRechecker(snark), "TorrentRechecker", true)).start();
        try {
            Thread.sleep(200);
        } catch (InterruptedException ie) {
        }
    }

    /**
     * @since 0.9.23
     */
    private class ThreadedRechecker implements Runnable {
        private final Snark snark;

        /** must have non-null storage */
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
                    addMessageNoEscape(
                            _t(
                                    "Finished recheck of torrent {0}, now {1} complete",
                                    link, complete));
                    if (!_context.isRouterContext()) {
                        System.out.println(
                                " • "
                                        + _t(
                                                "Finished recheck of torrent {0}, now {1} complete",
                                                getSnarkName(snark), complete));
                    }
                } else {
                    String msg = _t("Finished recheck of torrent {0}, unchanged", link);
                    addMessageNoEscape(_t("Finished recheck of torrent {0}, unchanged", link));
                    if (!_context.isRouterContext()) {
                        System.out.println(
                                " • "
                                        + _t(
                                                "Finished recheck of torrent {0}, unchanged",
                                                getSnarkName(snark)));
                    }
                }
            } catch (IOException e) {
                _log.error("Error rechecking " + snark.getBaseName(), e);
                String msg =
                        _t("Error checking torrent {0}", snark.getBaseName())
                                + " -> "
                                + e.getMessage();
                addMessage(msg);
                if (!_context.isRouterContext()) {
                    System.out.println(
                            " • "
                                    + _t("Error checking torrent {0}", getSnarkName(snark))
                                    + " -> "
                                    + e.getMessage());
                }
            }
        }
    }

    /**
     * ignore case, current locale
     *
     * @since 0.9
     */
    private static class IgnoreCaseComparator implements Comparator<Tracker>, Serializable {
        private final Collator coll = Collator.getInstance();

        public int compare(Tracker l, Tracker r) {
            return coll.compare(l.name, r.name);
        }
    }

    /**
     * ignore case, current locale
     *
     * @since 0.9.62+
     */
    private static class IgnoreCaseComparatorF
            implements Comparator<TorrentCreateFilter>, Serializable {
        private final Collator coll = Collator.getInstance();

        public int compare(TorrentCreateFilter l, TorrentCreateFilter r) {
            return coll.compare(l.name, r.name);
        }
    }

    /* @since 0.9.64+ */
    public String getDiskUsage() {
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
            String usageAsPercentage = String.format("%d%%", (int) usagePercent);

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

            int gCount = 0, mCount = 0;
            int gIndex = -1, mIndex = -1;

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
            return String.format(bar, (int) usagePercent);
        } catch (Exception e) {
            if (_log.shouldError()) {
                _log.error("[I2PSnark] Error retrieving disk usage: " + e.getMessage());
            }
            return "";
        }
    }
}
