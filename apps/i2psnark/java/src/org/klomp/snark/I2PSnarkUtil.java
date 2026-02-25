package org.klomp.snark;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketEepGet;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManager.DisconnectListener;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Base32;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import org.klomp.snark.dht.DHT;
import org.klomp.snark.dht.KRPC;

/**
 * I2P-specific utilities and context management for I2PSnark.
 *
 * <p>This class provides I2P network integration and serves as a context object for I2PSnark
 * operations. It handles:
 *
 * <ul>
 *   <li>I2P socket management and connection configuration
 *   <li>Proxy settings for external tracker communication
 *   <li>Peer banlist management
 *   <li>Bandwidth and connection limits
 *   <li>DHT integration and UDP tracker support
 *   <li>File and directory management
 *   <li>UI preferences and display options
 *   <li>Comments and ratings system configuration
 * </ul>
 *
 * <p>This class acts as a singleton-like context that allows multiple individual Snark instances to
 * run while sharing common I2P configuration and resources. Note that while multiple Snarks can
 * share one I2PSnarkUtil instance, multiple SnarkManagers are not supported due to static
 * resources.
 *
 * @since 0.1.0
 */
public class I2PSnarkUtil implements DisconnectListener {
    private final I2PAppContext _context;
    private final Log _log;
    private final String _baseName;
    private boolean _shouldProxy;
    private String _proxyHost;
    private int _proxyPort;
    private String _i2cpHost;
    private int _i2cpPort;
    private final Map<String, String> _opts;
    private volatile I2PSocketManager _manager;
    private volatile boolean _connecting;
    private final Set<Hash> _banlist;
    private int _maxUploaders;
    private int _maxUpBW;
    private int _maxConnections;
    private final File _tmpDir;
    private int _startupDelay;
    private boolean _collapsePanels;
    private boolean _showStatusFilter;
    private boolean _enableLightbox;
    private boolean _enableAddCreate;
    private boolean _shouldUseOT;
    private boolean _shouldUseDHT;
    private boolean _enableRatings, _enableComments;
    private String _commentsName;
    private boolean _areFilesPublic;
    private boolean _shouldPreallocateFiles;
    private boolean _varyInboundHops;
    private boolean _varyOutboundHops;
    private List<String> _openTrackers;
    private DHT _dht;
    private boolean _enableUDP = ENABLE_UDP_TRACKER;
    private UDPTrackerClient _udpTracker;
    private long _startedTime;
    private final DisconnectListener _discon;
    private int _maxFilesPerTorrent = SnarkManager.DEFAULT_MAX_FILES_PER_TORRENT;
    private String _apiTarget, _apiKey;
    private static final int EEPGET_CONNECT_TIMEOUT = 60 * 1000;
    private static final int EEPGET_CONNECT_TIMEOUT_SHORT = 15 * 1000;
    public static final int DEFAULT_STARTUP_DELAY = 3;
    public static final boolean DEFAULT_COLLAPSE_PANELS = true;
    public static final boolean DEFAULT_SHOW_STATUSFILTER = false;
    public static final boolean DEFAULT_ENABLE_LIGHTBOX = true;
    public static final boolean DEFAULT_ENABLE_ADDCREATE = false;
    public static final boolean DEFAULT_USE_OPENTRACKERS = true;
    public static final boolean DEFAULT_VARY_INBOUND_HOPS = false;
    public static final boolean DEFAULT_VARY_OUTBOUND_HOPS = false;
    public static final int MAX_CONNECTIONS = 300; // per torrent
    public static final String PROP_MAX_BW = "i2cp.outboundBytesPerSecond";
    public static final boolean DEFAULT_USE_DHT = true;
    public static final String EEPGET_USER_AGENT = "I2PSnark";
    private static final boolean ENABLE_UDP_TRACKER = true;
    private static final List<String> HIDDEN_I2CP_OPTS =
            Arrays.asList(
                    new String[] {
                        PROP_MAX_BW,
                        "inbound.length",
                        "outbound.length",
                        "inbound.quantity",
                        "outbound.quantity"
                    });

    public I2PSnarkUtil(I2PAppContext ctx) {
        this(ctx, "i2psnark", null);
    }

    /**
     * @param baseName generally "i2psnark"
     * @since Jetty 7
     */
    public I2PSnarkUtil(I2PAppContext ctx, String baseName, DisconnectListener discon) {
        _context = ctx;
        _log = _context.logManager().getLog(I2PSnarkUtil.class);
        _baseName = baseName;
        _discon = discon;
        _opts = new HashMap<String, String>();
        // setProxy("127.0.0.1", 4444);
        setI2CPConfig("127.0.0.1", I2PClient.DEFAULT_LISTEN_PORT, null);
        _banlist = new ConcurrentHashSet<Hash>();
        _maxUploaders = Snark.MAX_TOTAL_UPLOADERS;
        _maxUpBW = SnarkManager.DEFAULT_MAX_UP_BW;
        _maxConnections = MAX_CONNECTIONS;
        _startupDelay = DEFAULT_STARTUP_DELAY;
        _shouldUseOT = DEFAULT_USE_OPENTRACKERS;
        _openTrackers = Collections.emptyList();
        _shouldUseDHT = DEFAULT_USE_DHT;
        _collapsePanels = DEFAULT_COLLAPSE_PANELS;
        _showStatusFilter = DEFAULT_SHOW_STATUSFILTER;
        _enableLightbox = DEFAULT_ENABLE_LIGHTBOX;
        _enableAddCreate = DEFAULT_ENABLE_ADDCREATE;
        _enableRatings = _enableComments = true;
        _varyInboundHops = DEFAULT_VARY_INBOUND_HOPS;
        _varyOutboundHops = DEFAULT_VARY_OUTBOUND_HOPS;
        _commentsName = "";
        // This is used for both announce replies and .torrent file downloads, so it must be
        // available
        // even if not connected to I2CP.
        // So much for multiple instances...
        _tmpDir = new SecureDirectory(ctx.getTempDir(), baseName + '-' + ctx.random().nextInt());
        _tmpDir.mkdirs();
    }

    /**
     * @since 0.9.1
     */
    public I2PAppContext getContext() {
        return _context;
    }

    /**
     * @param i2cpHost may be null for no change
     * @param i2cpPort may be 0 for no change
     * @param opts may be null for no change
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setI2CPConfig(String i2cpHost, int i2cpPort, Map opts) {
        if (i2cpHost != null) {
            _i2cpHost = i2cpHost;
        }
        if (i2cpPort > 0) {
            _i2cpPort = i2cpPort;
        }
        if (getVaryInboundHops()) {
            _opts.put("inbound.tunnelVariance", "1");
        } else {
            _opts.put("inbound.tunnelVariance", "0");
        }
        if (getVaryOutboundHops()) {
            _opts.put("outbound.tunnelVariance", "1");
        } else {
            _opts.put("outbound.tunnelVariance", "0");
        }
        if (opts != null) {
            synchronized (_opts) {
                // removed options...
                for (Iterator<String> iter = _opts.keySet().iterator(); iter.hasNext(); ) {
                    String k = iter.next();
                    if (!HIDDEN_I2CP_OPTS.contains(k) && !opts.containsKey(k)) {
                        iter.remove();
                    }
                }
                _opts.putAll(opts);
            }
        }
        setMaxUpBW(_maxUpBW); // This updates the session options and tells the router
    }

    public void setMaxUploaders(int limit) {
        _maxUploaders = limit;
    }

    /**
     * This updates ALL the session options (not just the bw) and tells the router
     *
     * @param limit KBps
     */
    public void setMaxUpBW(int limit) {
        _maxUpBW = limit;
        synchronized (_opts) {
            _opts.put(PROP_MAX_BW, Integer.toString(limit * (1024 * 6 / 5)));
        } // add a little for overhead
        if (_manager != null) {
            I2PSession sess = _manager.getSession();
            if (sess != null) {
                Properties newProps = new Properties();
                synchronized (_opts) {
                    newProps.putAll(_opts);
                }
                sess.updateOptions(newProps);
            }
        }
    }

    public void setMaxConnections(int limit) {
        _maxConnections = limit;
    }

    public void setStartupDelay(int minutes) {
        _startupDelay = minutes;
    }

    public String getI2CPHost() {
        return _i2cpHost;
    }

    public int getI2CPPort() {
        return _i2cpPort;
    }

    /**
     * @return a copy
     */
    public Map<String, String> getI2CPOptions() {
        synchronized (_opts) {
            return new HashMap<String, String>(_opts);
        }
    }

    public String getEepProxyHost() {
        return _proxyHost;
    }

    public int getEepProxyPort() {
        return _proxyPort;
    }

    public boolean getEepProxySet() {
        return _shouldProxy;
    }

    public int getMaxUploaders() {
        return _maxUploaders;
    }

    /**
     * @return KBps
     */
    public int getMaxUpBW() {
        return _maxUpBW;
    }

    public int getMaxConnections() {
        return _maxConnections;
    }

    public int getStartupDelay() {
        return _startupDelay;
    }

    /**
     * @since 0.8.9
     */
    public boolean getFilesPublic() {
        return _areFilesPublic;
    }

    /**
     * @since 0.8.9
     */
    public void setFilesPublic(boolean yes) {
        _areFilesPublic = yes;
    }

    /**
     * @since 0.9.66+
     */
    public boolean getPreallocateFiles() {
        return _shouldPreallocateFiles;
    }

    /**
     * @since 0.9.66+
     */
    public void setPreallocateFiles(boolean yes) {
        _shouldPreallocateFiles = yes;
    }

    /**
     * @since 0.9.1
     */
    public File getTempDir() {
        return _tmpDir;
    }

    /**
     * @since 0.9.58
     */
    public int getMaxFilesPerTorrent() {
        return _maxFilesPerTorrent;
    }

    /**
     * @since 0.9.58
     */
    public void setMaxFilesPerTorrent(int max) {
        _maxFilesPerTorrent = Math.max(max, 1);
    }

    /**
     * @since 0.9.67
     */
    public String getAPITarget() {
        return _apiTarget;
    }
    ;

    /**
     * @since 0.9.67
     */
    public String getAPIKey() {
        return _apiKey;
    }
    ;

    /**
     * @since 0.9.67
     */
    public void setAPI(String target, String key) {
        _apiTarget = target;
        _apiKey = key;
    }

    /**
     * @since 0.9.67
     */
    public boolean hasAPIKey() {
        return _apiTarget != null
                && _apiTarget.length() > 0
                && _apiKey != null
                && _apiKey.length() > 0;
    }

    /**
     * @since 0.9.64+
     */
    public boolean getVaryInboundHops() {
        return _varyInboundHops;
    }

    /**
     * @since 0.9.64+
     */
    public boolean getVaryOutboundHops() {
        return _varyOutboundHops;
    }

    /**
     * @since 0.9.64+
     */
    public void setVaryInboundHops(boolean yes) {
        _varyInboundHops = yes;
    }

    /**
     * @since 0.9.64+
     */
    public void setVaryOutboundHops(boolean yes) {
        _varyOutboundHops = yes;
    }

    /** Connect to the router, if we aren't already */
    public synchronized boolean connect() {
        if (_manager == null) {
            _connecting = true;
            // try to find why reconnecting after stop
            if (_log.shouldDebug()) {
                _log.debug("Connecting to I2P...", new Exception("I did it"));
            }
            Properties opts = _context.getProperties();
            synchronized (_opts) {
                opts.putAll(_opts);
            }
            // override preference and start with two tunnels. IdleChecker will ramp up/down as
            // necessary
            String sin = opts.getProperty("inbound.quantity");
            String sout = opts.getProperty("outbound.quantity");
            if (sin != null) {
                int in;
                try {
                    in = Integer.parseInt(sin);
                } catch (NumberFormatException nfe) {
                    in = 3;
                }
                if (in > 2) {
                    opts.setProperty("inbound.quantity", "2");
                }
            }
            if (sout != null) {
                int out;
                try {
                    out = Integer.parseInt(sout);
                } catch (NumberFormatException nfe) {
                    out = 3;
                }
                if (out > 2) {
                    opts.setProperty("outbound.quantity", "2");
                }
            }
            if (opts.containsKey("inbound.backupQuantity")) {
                opts.setProperty("inbound.backupQuantity", "0");
            }
            if (opts.containsKey("outbound.backupQuantity")) {
                opts.setProperty("outbound.backupQuantity", "0");
            }
            if (opts.getProperty("inbound.nickname") == null) {
                opts.setProperty("inbound.nickname", _baseName.replace("i2psnark", "I2PSnark"));
            }
            if (opts.getProperty("outbound.nickname") == null) {
                opts.setProperty("outbound.nickname", _baseName.replace("i2psnark", "I2PSnark"));
            }
            if (opts.getProperty("inbound.lengthVariance") == null || !getVaryInboundHops()) {
                opts.setProperty("inbound.lengthVariance", "0");
            } else if (getVaryInboundHops()) {
                opts.setProperty("inbound.lengthVariance", "1");
            }
            if (opts.getProperty("outbound.lengthVariance") == null || !getVaryOutboundHops()) {
                opts.setProperty("outbound.lengthVariance", "0");
            } else if (getVaryOutboundHops()) {
                opts.setProperty("outbound.lengthVariance", "1");
            }
            if (opts.getProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT) == null) {
                opts.setProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT, "300000");
            } // 5 minutes
            if (opts.getProperty("i2p.streaming.inactivityTimeout") == null) {
                opts.setProperty("i2p.streaming.inactivityTimeout", "180000");
            } // 3 minute idle time before disconnect
            if (opts.getProperty("i2p.streaming.inactivityAction") == null) {
                opts.setProperty("i2p.streaming.inactivityAction", "1");
            } // 1 == disconnect, 2 == ping
            if (opts.getProperty("i2p.streaming.initialWindowSize") == null) {
                opts.setProperty("i2p.streaming.initialWindowSize", "8");
            }
            if (opts.getProperty("i2p.streaming.slowStartGrowthRateFactor") == null) {
                opts.setProperty("i2p.streaming.slowStartGrowthRateFactor", "2");
            }
            if (opts.getProperty("i2p.streaming.maxConnsPerMinute") == null) {
                opts.setProperty("i2p.streaming.maxConnsPerMinute", "32");
            } // per peer max incoming connections
            if (opts.getProperty("i2p.streaming.maxTotalConnsPerMinute") == null) {
                opts.setProperty("i2p.streaming.maxTotalConnsPerMinute", "1024");
            } // total incoming connections
            if (opts.getProperty("i2p.streaming.maxConnsPerHour") == null) {
                opts.setProperty("i2p.streaming.maxConnsPerHour", "384");
            } // per peer max incoming connections
            if (opts.getProperty("i2p.streaming.enforceProtocol") == null) {
                opts.setProperty("i2p.streaming.enforceProtocol", "true");
            }
            if (opts.getProperty("i2p.streaming.disableRejectLogging") == null) {
                opts.setProperty("i2p.streaming.disableRejectLogging", "false");
            }
            if (opts.getProperty("i2p.streaming.answerPings") == null) {
                opts.setProperty("i2p.streaming.answerPings", "false");
            }
            if (opts.getProperty(I2PSocketOptions.PROP_PROFILE) == null) {
                opts.setProperty(
                        I2PSocketOptions.PROP_PROFILE,
                        Integer.toString(I2PSocketOptions.PROFILE_BULK));
            }
            if (opts.getProperty(I2PClient.PROP_SIGTYPE) == null) {
                opts.setProperty(I2PClient.PROP_SIGTYPE, "EdDSA_SHA512_Ed25519");
            }
            if (opts.getProperty("i2cp.leaseSetEncType") == null) {
                opts.setProperty("i2cp.leaseSetEncType", "6,4");
            }
            if (opts.getProperty(I2PClient.PROP_GZIP) == null) {
                opts.setProperty(I2PClient.PROP_GZIP, "false");
            } // assume compressed content
            _manager = I2PSocketManagerFactory.createManager(_i2cpHost, _i2cpPort, opts);
            if (_manager != null) {
                _startedTime = _context.clock().now();
                if (_discon != null) {
                    _manager.addDisconnectListener(this);
                }
            }
            _connecting = false;
        }
        if (_shouldUseDHT && _manager != null && _dht == null) {
            _dht = new KRPC(_context, _baseName, _manager.getSession());
        }
        if (_enableUDP && _manager != null) {
            if (_udpTracker == null) {
                _udpTracker = new UDPTrackerClient(_context, _manager.getSession(), this);
            }
            _udpTracker.start();
        }
        return (_manager != null);
    }

    /**
     * DisconnectListener interface
     *
     * @since 0.9.53
     */
    public void sessionDisconnected() {
        synchronized (this) {
            _manager = null;
            _connecting = false;
            if (_dht != null) {
                _dht.stop();
                _dht = null;
            }
        }
        if (_discon != null) {
            _discon.sessionDisconnected();
        }
    }

    /**
     * @return null if disabled or not started
     * @since 0.8.4
     */
    public DHT getDHT() {
        return _dht;
    }

    /**
     * @return null if disabled or not started
     * @since 0.9.14
     */
    public UDPTrackerClient getUDPTrackerClient() {
        return _udpTracker;
    }

    public boolean connected() {
        return _manager != null;
    }

    /**
     * @since 0.9.1
     */
    public boolean isConnecting() {
        return _manager == null && _connecting;
    }

    /**
     * For FetchAndAdd
     *
     * @return null if not connected
     * @since 0.9.1
     */
    public I2PSocketManager getSocketManager() {
        return _manager;
    }

    /** Destroy the destination itself */
    public synchronized void disconnect() {
        if (_dht != null) {
            _dht.stop();
            _dht = null;
        }
        if (_udpTracker != null) {
            _udpTracker.stop();
            _udpTracker = null;
        }
        _startedTime = 0;
        I2PSocketManager mgr = _manager;
        // FIXME this can cause race NPEs elsewhere
        _manager = null;
        _banlist.clear();
        if (mgr != null) {
            if (_log.shouldDebug()) {
                _log.debug("Disconnecting from I2P...", new Exception("I did it"));
            }
            mgr.destroySocketManager();
        }
        FileUtil.rmdir(
                _tmpDir,
                false); // this will delete a .torrent file d/l in progress so don't do that...
        _tmpDir.mkdirs(); // in case the user will d/l a .torrent file next...
    }

    /**
     * When did we connect to the network? For RPC
     *
     * @return 0 if not connected
     * @since 0.9.30
     */
    public long getStartedTime() {
        return _startedTime;
    }

    /** Connect to the given destination */
    I2PSocket connect(PeerID peer) throws IOException {
        I2PSocketManager mgr = _manager;
        if (mgr == null) {
            throw new IOException("No socket manager");
        }
        Destination addr = peer.getAddress();
        if (addr == null) {
            throw new IOException("Null address");
        }
        if (addr.equals(getMyDestination())) {
            throw new IOException("Attempt to connect to myself");
        }
        Hash dest = addr.calculateHash();
        if (_banlist.contains(dest)) {
            throw new IOException(
                    "Not trying to contact banlisted peer ["
                            + dest.toBase64().substring(0, 6)
                            + "]");
        }
        try {
            // TODO opts.setPort(xxx); connect(addr, opts)
            // DHT moved above 6881 in 0.9.9
            I2PSocket rv = _manager.connect(addr);
            if (rv != null) {
                _banlist.remove(dest);
            }
            return rv;
        } catch (I2PException ie) {
            _banlist.add(dest);
            new Unbanlist(dest, 15 * 60 * 1000);
            IOException ioe = new IOException("Unable to reach peer [" + peer + "]");
            ioe.initCause(ie);
            throw ioe;
        }
    }

    private class Unbanlist extends SimpleTimer2.TimedEvent {
        private Hash _dest;

        public Unbanlist(Hash dest, long timeoutMs) {
            super(_context.simpleTimer2(), timeoutMs);
            _dest = dest;
        }

        @Override
        public void timeReached() {
            _banlist.remove(_dest);
        }
    }

    /** Fetch the given URL, returning the file it is stored in, or null on error. No retries. */
    public File get(String url) {
        return get(url, true, 0);
    }

    /**
     * @param rewrite if true, convert http://KEY.i2p/foo/announce to http://i2p/KEY/foo/announce
     */
    public File get(String url, boolean rewrite) {
        return get(url, rewrite, 0);
    }

    /**
     * @param retries if &gt; 0, set timeout to a few seconds
     */
    public File get(String url, int retries) {
        return get(url, true, retries);
    }

    /**
     * @param retries if &gt; 0, set timeout to a few seconds
     */
    public File get(String url, boolean rewrite, int retries) {
        if (_log.shouldDebug()) {
            _log.debug(
                    "Fetching ["
                            + url
                            + "] proxy="
                            + _proxyHost
                            + ":"
                            + _proxyPort
                            + ": "
                            + _shouldProxy);
        }
        File out = null;
        try {
            // We could use the system tmp dir but deleteOnExit() doesn't seem to work on all
            // platforms...
            out = SecureFile.createTempFile("i2psnark", null, _tmpDir);
        } catch (IOException ioe) {
            _log.error("Temp file error", ioe);
            if (out != null) {
                out.delete();
            }
            return null;
        }
        out.deleteOnExit();
        String fetchURL = url;
        if (rewrite) {
            fetchURL = rewriteAnnounce(url);
        }
        // Use our tunnel for announces and .torrent fetches too! Make sure we're connected first...
        int timeout;
        if (retries < 0) {
            if (!connected()) {
                return null;
            }
            timeout = EEPGET_CONNECT_TIMEOUT_SHORT;
            retries = 10;
        } else {
            timeout = EEPGET_CONNECT_TIMEOUT;
            if (!connected()) {
                if (!connect()) {
                    return null;
                }
            }
        }
        EepGet get =
                new I2PSocketEepGet(_context, _manager, retries, out.getAbsolutePath(), fetchURL);
        get.addHeader("User-Agent", EEPGET_USER_AGENT);
        int truncate = url.indexOf("&");
        String convertedurl =
                url.replace(
                                "ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p",
                                "tracker2.postman.i2p")
                        .replace(
                                "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICkEbKUqJ9mPYQlTSujhNxiRIW-oLwMtvayCFci99oX8MvazPS7~97x0Gsm-onEK1Td9nBdmq30OqDxpRtXBimbzkLbR1IKObbg9HvrKs3L-kSyGwTUmHG9rSQSoZEvFMA-S0EXO~o4g21q1oikmxPMhkeVwQ22VHB0-LZJfmLr4SAAAA.i2p",
                                "tracker2.postman.i2p")
                        .replace(
                                "w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p",
                                "opentracker.dg2.i2p")
                        .replace(
                                "afuuortfaqejkesne272krqvmafn65mhls6nvcwv3t7l2ic2p4kq.b32.i2p",
                                "lyoko.i2p")
                        .replace(
                                "s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p",
                                "tracker.thebland.i2p")
                        .replace(
                                "nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p",
                                "torrfreedom.i2p")
                        .replace("http://", "");
        if (get.fetch(timeout)) {
            if (_log.shouldDebug())
                _log.debug(
                        "Request successful ["
                                + convertedurl.substring(0, truncate)
                                + "...] (Size: "
                                + out.length()
                                + " bytes)");
            return out;
        } else {
            if (_log.shouldWarn())
                _log.warn(
                        "Timeout ("
                                + timeout / 1000
                                + "s) requesting ["
                                + convertedurl.substring(0, truncate)
                                + "...]");
            out.delete();
            return null;
        }
    }

    /**
     * Fetch to memory
     *
     * @param retries if &lt; 0, set timeout to a few seconds
     * @param initialSize buffer size
     * @param maxSize fails if greater
     * @return null on error
     * @since 0.9.4
     */
    public byte[] get(String url, boolean rewrite, int retries, int initialSize, int maxSize) {
        if (_log.shouldDebug()) {
            _log.debug("Fetching [" + url + "] to memory");
        }
        String fetchURL = url;
        if (rewrite) {
            fetchURL = rewriteAnnounce(url);
        }
        int timeout;
        if (retries < 0) {
            if (!connected()) {
                return null;
            }
            timeout = EEPGET_CONNECT_TIMEOUT_SHORT;
            retries = 0;
        } else {
            timeout = EEPGET_CONNECT_TIMEOUT;
            if (!connected()) {
                if (!connect()) {
                    return null;
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(initialSize);
        EepGet get =
                new I2PSocketEepGet(_context, _manager, retries, -1, maxSize, null, out, fetchURL);
        get.addHeader("User-Agent", EEPGET_USER_AGENT);
        int truncate = url.indexOf("&");
        String convertedurl =
                url.replace(
                                "ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p",
                                "tracker2.postman.i2p")
                        .replace(
                                "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICkEbKUqJ9mPYQlTSujhNxiRIW-oLwMtvayCFci99oX8MvazPS7~97x0Gsm-onEK1Td9nBdmq30OqDxpRtXBimbzkLbR1IKObbg9HvrKs3L-kSyGwTUmHG9rSQSoZEvFMA-S0EXO~o4g21q1oikmxPMhkeVwQ22VHB0-LZJfmLr4SAAAA.i2p",
                                "tracker2.postman.i2p")
                        .replace(
                                "w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p",
                                "opentracker.dg2.i2p")
                        .replace(
                                "afuuortfaqejkesne272krqvmafn65mhls6nvcwv3t7l2ic2p4kq.b32.i2p",
                                "lyoko.i2p")
                        .replace(
                                "s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p",
                                "tracker.thebland.i2p")
                        .replace(
                                "nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p",
                                "torrfreedom.i2p")
                        .replace("http://", "");
        if (get.fetch(timeout)) {
            if (_log.shouldDebug())
                _log.debug(
                        "Request successful ["
                                + convertedurl.substring(0, truncate)
                                + "...] (Size: "
                                + out.size()
                                + " bytes)");
            return out.toByteArray();
        } else {
            if (_log.shouldWarn())
                _log.warn(
                        "Timeout ("
                                + timeout / 1000
                                + "s) requesting ["
                                + convertedurl.substring(0, truncate)
                                + "...]");
            return null;
        }
    }

    public I2PServerSocket getServerSocket() {
        I2PSocketManager mgr = _manager;
        if (mgr != null) {
            return mgr.getServerSocket();
        } else {
            return null;
        }
    }

    /** Full Base64 of Destination */
    public String getOurIPString() {
        Destination dest = getMyDestination();
        if (dest != null) {
            return dest.toBase64();
        }
        return "unknown";
    }

    /**
     * @return dest or null
     * @since 0.8.4
     */
    Destination getMyDestination() {
        if (_manager == null) {
            return null;
        }
        I2PSession sess = _manager.getSession();
        if (sess != null) {
            return sess.getMyDestination();
        }
        return null;
    }

    /** Base64 only - static (no naming service) */
    static Destination getDestinationFromBase64(String ip) {
        if (ip == null) {
            return null;
        }
        if (ip.endsWith(".i2p")) {
            if (ip.length() < 520) {
                return null;
            }
            try {
                return new Destination(ip.substring(0, ip.length() - 4));
            } // sans .i2p
            catch (DataFormatException dfe) {
                return null;
            }
        } else {
            try {
                return new Destination(ip);
            } catch (DataFormatException dfe) {
                return null;
            }
        }
    }

    private static final int BASE32_HASH_LENGTH = 52; // 1 + Hash.HASH_LENGTH * 8 / 5

    /** Base64 Hash or Hash.i2p or name.i2p using naming service */
    Destination getDestination(String ip) {
        if (ip == null) return null;
        if (ip.endsWith(".i2p")) {
            if (ip.length() < 520) { // key + ".i2p"
                if (_manager != null
                        && ip.length() == BASE32_HASH_LENGTH + 8
                        && ip.endsWith(".b32.i2p")) {
                    // Use existing I2PSession for b32 lookups if we have it
                    // This is much more efficient than using the naming service
                    I2PSession sess = _manager.getSession();
                    if (sess != null) {
                        byte[] b = Base32.decode(ip.substring(0, BASE32_HASH_LENGTH));
                        if (b != null) {
                            // Hash h = new Hash(b);
                            Hash h = Hash.create(b);
                            if (_log.shouldDebug())
                                _log.debug("Using existing session for lookup of [" + ip + "]");
                            try {
                                return sess.lookupDest(h, 15 * 1000);
                            } catch (I2PSessionException ise) {
                            }
                        }
                    }
                }
                if (_log.shouldDebug()) {
                    _log.debug("Using naming service for lookup of [" + ip + "]");
                }
                return _context.namingService().lookup(ip);
            }
            if (_log.shouldDebug()) {
                _log.debug("Creating Destination for [" + ip + "]");
            }
            try {
                return new Destination(ip.substring(0, ip.length() - 4));
            } // sans .i2p
            catch (DataFormatException dfe) {
                return null;
            }
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Creating Destination for [" + ip + "]");
            }
            try {
                return new Destination(ip);
            } catch (DataFormatException dfe) {
                return null;
            }
        }
    }

    public String lookup(String name) {
        Destination dest = getDestination(name);
        if (dest == null) {
            return null;
        }
        return dest.toBase64();
    }

    /**
     * Given http://KEY.i2p/foo/announce turn it into http://i2p/KEY/foo/announce Given
     * http://tracker.blah.i2p/foo/announce leave it alone
     */
    String rewriteAnnounce(String origAnnounce) {
        int destStart = "http://".length();
        int destEnd = origAnnounce.indexOf(".i2p");
        if (destEnd < destStart + 516) {
            return origAnnounce;
        }
        int pathStart = origAnnounce.indexOf('/', destEnd);
        String rv =
                "http://i2p/"
                        + origAnnounce.substring(destStart, destEnd)
                        + origAnnounce.substring(pathStart);
        // _log.debug("Rewriting [" + origAnnounce + "] as [" + rv + "]");
        return rv;
    }

    /**
     * @param ot non-null list of announce URLs
     */
    public void setOpenTrackers(List<String> ot) {
        _openTrackers = ot;
    }

    /**
     * List of open tracker announce URLs to use as backups
     *
     * @return non-null, possibly unmodifiable, empty if disabled
     */
    public List<String> getOpenTrackers() {
        if (!shouldUseOpenTrackers()) {
            return Collections.emptyList();
        }
        return _openTrackers;
    }

    /**
     * Is this announce URL probably for an open tracker?
     *
     * @since 0.9.17
     */
    public boolean isKnownOpenTracker(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            return host != null && SnarkManager.KNOWN_OPENTRACKERS.contains(host);
        } catch (URISyntaxException use) {
            return false;
        }
    }

    /**
     * List of open tracker announce URLs to use as backups even if disabled
     *
     * @return non-null
     * @since 0.9.4
     */
    public List<String> getBackupTrackers() {
        return _openTrackers;
    }

    public void setUseOpenTrackers(boolean yes) {
        _shouldUseOT = yes;
    }

    public boolean shouldUseOpenTrackers() {
        return _shouldUseOT;
    }

    /**
     * @since DHT
     */
    public synchronized void setUseDHT(boolean yes) {
        _shouldUseDHT = yes;
        if (yes && _manager != null && _dht == null) {
            _dht = new KRPC(_context, _baseName, _manager.getSession());
        } else if (!yes && _dht != null) {
            _dht.stop();
            _dht = null;
        }
    }

    /**
     * @since DHT
     */
    public boolean shouldUseDHT() {
        return _shouldUseDHT;
    }

    /**
     * @since 0.9.67
     */
    public void setUDPEnabled(boolean yes) {
        _enableUDP = yes;
    }

    /**
     * @since 0.9.67
     */
    public boolean udpEnabled() {
        return _enableUDP;
    }

    /**
     * @since 0.9.31
     */
    public void setRatingsEnabled(boolean yes) {
        _enableRatings = yes;
    }

    /**
     * @since 0.9.31
     */
    public boolean ratingsEnabled() {
        return _enableRatings;
    }

    /**
     * @since 0.9.31
     */
    public void setCommentsEnabled(boolean yes) {
        _enableComments = yes;
    }

    /**
     * @since 0.9.31
     */
    public boolean commentsEnabled() {
        return _enableComments;
    }

    /**
     * @since 0.9.31
     */
    public void setCommentsName(String name) {
        _commentsName = name;
    }

    /**
     * @return non-null, "" if none
     * @since 0.9.31
     */
    public String getCommentsName() {
        return _commentsName == null ? "" : _commentsName;
    }

    /**
     * @since 0.9.31
     */
    public boolean utCommentsEnabled() {
        return _enableRatings || _enableComments;
    }

    /**
     * @since 0.9.32
     */
    public boolean collapsePanels() {
        return _collapsePanels;
    }

    /**
     * @since 0.9.32
     */
    public void setCollapsePanels(boolean yes) {
        _collapsePanels = yes;
    }

    /**
     * @since 0.9.34+
     */
    public boolean showStatusFilter() {
        return _showStatusFilter;
    }

    /**
     * @since 0.9.34+
     */
    public void setShowStatusFilter(boolean yes) {
        _showStatusFilter = yes;
    }

    /**
     * @since 0.9.34+
     */
    public boolean enableLightbox() {
        return _enableLightbox;
    }

    /**
     * @since 0.9.34
     */
    public void setEnableLightbox(boolean yes) {
        _enableLightbox = yes;
    }

    /**
     * @since 0.9.38+
     */
    public boolean enableAddCreate() {
        return _enableAddCreate;
    }

    /**
     * @since 0.9.38+
     */
    public void setEnableAddCreate(boolean yes) {
        _enableAddCreate = yes;
    }

    /**
     * @since 0.9.64+
     */
    public boolean enableVaryInboundHops() {
        return _varyInboundHops;
    }

    /**
     * @since 0.9.64+
     */
    public boolean enableVaryOutboundHops() {
        return _varyOutboundHops;
    }

    /**
     * @since 0.9.64+
     */
    public void setEnableVaryInboundHops(boolean yes) {
        _varyInboundHops = yes;
    }

    /**
     * @since 0.9.64+
     */
    public void setEnableVaryOutboundHops(boolean yes) {
        _varyOutboundHops = yes;
    }

    /**
     * Like DataHelper.toHexString but ensures no loss of leading zero bytes
     *
     * @since 0.8.4
     */
    public static String toHex(byte[] b) {
        StringBuilder buf = new StringBuilder(40);
        for (int i = 0; i < b.length; i++) {
            int bi = b[i] & 0xff;
            if (bi < 16) {
                buf.append('0');
            }
            buf.append(Integer.toHexString(bi));
        }
        return buf.toString();
    }

    private static final String BUNDLE_NAME = "org.klomp.snark.web.messages";

    /** lang in routerconsole.lang property, else current locale */
    public String getString(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /**
     * Translate a string with a parameter This is a lot more expensive than getString(s, ctx), so
     * use sparingly.
     *
     * @param s string to be translated containing {0} The {0} will be replaced by the parameter.
     *     Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     * @param o parameter, not translated. To translate parameter also, use _t("foo {0} bar",
     *     _t("baz")) Do not double the single quotes in the parameter. Use autoboxing to call with
     *     ints, longs, floats, etc.
     */
    public String getString(String s, Object o) {
        return Translate.getString(s, o, _context, BUNDLE_NAME);
    }

    /** {0} and {1} */
    public String getString(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, _context, BUNDLE_NAME);
    }

    /** ngettext @since 0.7.14 */
    public String getString(int n, String s, String p) {
        return Translate.getString(n, s, p, _context, BUNDLE_NAME);
    }

    private static final boolean SHOULD_SYNC =
            !(SystemVersion.isAndroid() || SystemVersion.isARM());
    private static final Pattern ILLEGAL_KEY = Pattern.compile("[#=\\r\\n;]");
    private static final Pattern ILLEGAL_VALUE = Pattern.compile("[\\r\\n]");

    /**
     * Same as DataHelper.loadProps() but allows '#' in values, so we can have filenames with '#' in
     * them in torrent config files. '#' must be in column 1 for a comment.
     *
     * @since 0.9.58
     */
    static void loadProps(Properties props, File f) throws IOException {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1024);
            String line = null;
            while ((line = in.readLine()) != null) {
                if (line.trim().length() <= 0) {
                    continue;
                }
                if (line.charAt(0) == '#') {
                    continue;
                }
                if (line.charAt(0) == ';') {
                    continue;
                }
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = line.substring(0, split);
                String val = line.substring(split + 1).trim();
                props.setProperty(key, val);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ioe) {
                }
            }
        }
    }

    /**
     * Same as DataHelper.loadProps() but allows '#' in values, so we can have filenames with '#' in
     * them in torrent config files. '#' must be in column 1 for a comment.
     *
     * @since 0.9.58
     */
    static void storeProps(Properties props, File file) throws IOException {
        FileOutputStream fos = null;
        PrintWriter out = null;
        IOException ioe = null;
        File tmpFile = new File(file.getPath() + ".tmp");
        try {
            fos = new SecureFileOutputStream(tmpFile);
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(fos, "UTF-8")));
            out.println("# NOTE: This I2P config file must use UTF-8 encoding");
            out.println("# Last saved: " + DataHelper.formatTime(System.currentTimeMillis()));
            for (Map.Entry<Object, Object> entry : props.entrySet()) {
                String name = (String) entry.getKey();
                String val = (String) entry.getValue();
                if (ILLEGAL_KEY.matcher(name).find()) {
                    if (ioe == null) {
                        ioe =
                                new IOException(
                                        "Invalid character (one of \"#;=\\r\\n\") in key: \""
                                                + name
                                                + "\" = \""
                                                + val
                                                + '\"');
                    }
                    continue;
                }
                if (ILLEGAL_VALUE.matcher(val).find()) {
                    if (ioe == null) {
                        ioe =
                                new IOException(
                                        "Invalid character (one of \"\\r\\n\") in value: \""
                                                + name
                                                + "\" = \""
                                                + val
                                                + '\"');
                    }
                    continue;
                }
                out.println(name + "=" + val);
            }
            if (SHOULD_SYNC) {
                out.flush();
                fos.getFD().sync();
            }
            out.close();
            if (out.checkError()) {
                out = null;
                tmpFile.delete();
                throw new IOException("Failed to write properties to " + tmpFile);
            }
            out = null;
            if (!FileUtil.rename(tmpFile, file))
                throw new IOException("Failed rename from " + tmpFile + " to " + file);
        } finally {
            if (out != null) out.close();
            if (fos != null)
                try {
                    fos.close();
                } catch (IOException e) {
                }
        }
        if (ioe != null) {
            throw ioe;
        }
    }
}
