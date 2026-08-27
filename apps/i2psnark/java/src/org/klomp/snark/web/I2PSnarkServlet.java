package org.klomp.snark.web;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.*;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.ByteArray;
import net.i2p.data.Hash;
import net.i2p.servlet.RequestWrapper;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.FileUtil;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.UIMessages;
import org.klomp.snark.BandwidthGraph;
import org.klomp.snark.ClientID;
import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MagnetURI;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Peer;
import org.klomp.snark.PeerID;
import org.klomp.snark.Snark;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.Storage;
import org.klomp.snark.TorrentCreateFilter;
import org.klomp.snark.TorrentDest;
import org.klomp.snark.Tracker;
import org.klomp.snark.TrackerClient;
import org.klomp.snark.URIUtil;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;
import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;
import org.klomp.snark.dht.DHT;

/**
 * Web interface servlet for I2PSnark torrent management.
 *
 * <p>This servlet provides the complete web-based user interface for I2PSnark,
 * allowing users to manage their torrents through a browser. It handles:
 * <ul>
 * <li>Torrent listing and status display</li>
 * <li>Adding torrents from files, URLs, or magnet links</li>
 * <li>Torrent creation from local files</li>
 * <li>Peer management and connection monitoring</li>
 * <li>Bandwidth configuration and statistics</li>
 * <li>DHT and tracker status</li>
 * <li>Comments and ratings system</li>
 * <li>File browsing and downloading</li>
 * <li>Configuration management</li>
 * <li>Theme and localization support</li>
 * </ul>
 *
 * <p>The servlet has been refactored to eliminate Jetty-specific dependencies
 * and works with standard servlet containers.</p>
 *
 * <p>Security features include:
 * </p>
 * <ul>
 * <li>CSRF protection via nonces</li>
 * <li>Content Security Policy headers</li>
 * <li>Input validation and sanitization</li>
 * <li>Secure file handling</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class I2PSnarkServlet extends BasicServlet {

    private static final long serialVersionUID = 1L;
    /** Context path, generally "/i2psnark"; set once in init(). */
    private String _contextPath;
    /** Context display name, generally "i2psnark"; set once in init(). */
    private String _contextName;
    private transient SnarkManager _manager;
    /** Rotating CSRF nonce, rotates every 5 minutes */
    private long _currentNonce;
    /** The two nonces before the current one, still accepted for in-flight forms. */
    private final long[] _recentNonces = new long[2];
    /** When the current nonce was minted, ms since epoch; drives rotation. */
    private long _lastRotation;
    private static final long NONCE_ROTATION_MS = 5 * (long) 60 * 1000; // 5 minutes
    /** Version of the bundled I2PSnark Bridge XPI, read lazily from the war; null if unknown. */
    private static volatile String _bridgeVersion;
    /** Set once when an outdated I2PSnark Bridge extension reported itself; one screen-log notice per webapp start. */
    private static volatile boolean _bridgeUpdateLogged;

    private static final Pattern INFOHASH_PAREN = Pattern.compile(" \\(");
    /** Cumulative stats array indices: [downloaded, uploaded, download rate, upload rate, peers, total size] */
    private static final int STAT_DOWNLOADED = 0;
    private static final int STAT_UPLOADED = 1;
    private static final int STAT_DOWNLOAD_RATE = 2;
    private static final int STAT_UPLOAD_RATE = 3;
    private static final int STAT_PEERS = 4;
    private static final int STAT_TOTAL_SIZE = 5;
    private static final Pattern HEX_PATTERN = Pattern.compile("[a-fA-F0-9]+");
    private static final Pattern BASE32_PATTERN = Pattern.compile("[a-zA-Z2-7]+");

    /** Web path of the active theme's resources; recomputed per request. */
    private String _themePath;
    /** Web path of static resources; set once in init(), never mutated per request. */
    private String _resourcePath;
    /** Icon image base path derived from {@link #_themePath}. */
    private String _imgPath;
    /** Announce URL of the torrent being edited; preselects its tracker radio button. */
    private String _lastAnnounceURL;

    private static final String DEFAULT_NAME = "i2psnark";
    /**
     * Config file path property.
     */
    public static final String PROP_CONFIG_FILE = "i2psnark.configFile";
    /**
     * Webapp resource base path.
     */
    public static final String WARBASE = "/.res/";
    /** Ellipsis character used to truncate long names. */
    static final char HELLIP = '\u2026';
    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final String RC_PROP_ENABLE_SORA_FONT = "routerconsole.displayFontSora";
    private static final ThreadLocal<DateFormat> _DATE_FMT1 = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            return new SimpleDateFormat("HH:mm, EEE dd MMM yyyy", Locale.US);
        }
    };
    private static final ThreadLocal<DateFormat> _DATE_FMT2 = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            return new SimpleDateFormat("HH:mm, EEE dd MMMM yyyy", Locale.US);
        }
    };
    private static final ThreadLocal<DateFormat> _DATE_FMT3 = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        }
    };

    /** Search results */
    private int searchResults;
    /** Csp nonce */
    String cspNonce = Integer.toHexString(_context.random().nextInt());
    public I2PSnarkServlet() {super();}

    /**
     * Current CSRF nonce, rotating every 5 minutes.
     *
     * @return the nonce
     * @since 0.9.70+
     */
    private synchronized long getNonce() {
        if (_currentNonce == 0) {
            _currentNonce = _context.random().nextLong();
            _lastRotation = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - _lastRotation > NONCE_ROTATION_MS) {
            // Rotate: shift current to recent[0], recent[0] to recent[1], generate new
            _recentNonces[1] = _recentNonces[0];
            _recentNonces[0] = _currentNonce;
            _currentNonce = _context.random().nextLong();
            _lastRotation = System.currentTimeMillis();
        }
        return _currentNonce;
    }

    /**
     *  Validate nonce against current and recent nonces (backward compatibility).
     *  @param nonce the nonce to validate
     *  @return true if valid
     *  @since 0.9.70+
     */
    private synchronized boolean isValidNonce(String nonce) {
        if (nonce == null) {return false;}
        long current = getNonce();
        if (DataHelper.eqCT(String.valueOf(current), nonce)) {return true;}
        if (_recentNonces[0] != 0 && DataHelper.eqCT(String.valueOf(_recentNonces[0]), nonce)) {return true;}
        if (_recentNonces[1] != 0 && DataHelper.eqCT(String.valueOf(_recentNonces[1]), nonce)) {return true;}
        return false;
    }

    /**
     *  Validate Origin header for POST requests.
     *  Allows requests with matching Origin (same-origin), or no Origin header.
     *  Rejects cross-origin POST requests to prevent CSRF attacks.
     *
     *  @param request the HTTP request
     *  @return true if allowed
     */
    private boolean allowOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String host = request.getHeader("Host");

        if (origin == null || origin.isEmpty()) {
            return true;
        }

        if ("null".equalsIgnoreCase(origin)) {
            return true;
        }

        String originHost;
        int originPort;
        try {
            if (origin.startsWith("http://")) {
                String rest = origin.substring(7);
                int colon = rest.indexOf(':');
                if (colon > 0) {
                    originHost = rest.substring(0, colon);
                    originPort = Integer.parseInt(rest.substring(colon + 1));
                } else {
                    originHost = rest;
                    originPort = 80;
                }
            } else if (origin.startsWith("https://")) {
                String rest = origin.substring(8);
                int colon = rest.indexOf(':');
                if (colon > 0) {
                    originHost = rest.substring(0, colon);
                    originPort = Integer.parseInt(rest.substring(colon + 1));
                } else {
                    originHost = rest;
                    originPort = 443;
                }
            } else {
                return true;
            }
        } catch (Exception e) {
            return true;
        }

        String requestHost;
        int requestPort;
        try {
            if (host == null) {
                return true;
            }
            if (host.startsWith("[")) {
                int brack = host.indexOf(']');
                if (brack > 0) {
                    requestHost = host.substring(1, brack);
                    String rest = host.substring(brack + 1);
                    if (rest.startsWith(":")) {
                        requestPort = Integer.parseInt(rest.substring(1));
                    } else {
                        requestPort = request.isSecure() ? 443 : 80;
                    }
                } else {
                    requestHost = host;
                    requestPort = request.isSecure() ? 443 : 80;
                }
            } else {
                int colon = host.indexOf(':');
                if (colon > 0) {
                    requestHost = host.substring(0, colon);
                    requestPort = Integer.parseInt(host.substring(colon + 1));
                } else {
                    requestHost = host;
                    requestPort = request.isSecure() ? 443 : 80;
                }
            }
        } catch (Exception e) {
            return true;
        }

        return originHost.equals(requestHost) && originPort == requestPort;
    }

    /** Package-visible collaborators for {@link I2PSnarkConfigure}. */
    SnarkManager manager() {return _manager;}
    String contextPath() {return _contextPath;}
    String contextName() {return _contextName;}
    String resourcePath() {return _resourcePath;}
    String warBase() {return WARBASE;}
    I2PAppContext context() {return _context;}

    private final I2PSnarkConfigure configForms = new I2PSnarkConfigure(this);
    I2PSnarkConfigure configForms() {return configForms;}

    private void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {configForms().writeConfigForm(out, req);}

    private void writeTorrentCreateFilterForm(PrintWriter out, HttpServletRequest req) throws IOException {configForms().writeTorrentCreateFilterForm(out, req);}

    private void writeTrackerForm(PrintWriter out, HttpServletRequest req) throws IOException {configForms().writeTrackerForm(out, req);}

    /**
     * Initialize the servlet.
     *
     * @param cfg the servlet config
     */
    @Override
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        String cpath = getServletContext().getContextPath();
        _contextPath = cpath.isEmpty() ? "/" : cpath;
        _contextName = cpath.isEmpty() ? DEFAULT_NAME : cpath.substring(1).replace("/", "_");
        // set once here - render methods previously re-assigned this per
        // request, an unsynchronized shared-field write
        _resourcePath = _contextPath + WARBASE;
        getNonce(); // Initialize the nonce
        // Limited protection against overwriting other config files or directories
        // in case you named your war "router.war"
        // We don't handle bad characters in the context path. Don't do that.
        String configName = _contextName;
        if (!configName.equals(DEFAULT_NAME)) {configName = DEFAULT_NAME + '_' + _contextName;}
        _manager = new SnarkManager(_context, _contextPath, configName);
        String configFile = _context.getProperty(PROP_CONFIG_FILE);
        if ((configFile == null) || (configFile.trim().length() <= 0)) {configFile = configName + ".config";}
        _manager.loadConfig(configFile);
        _manager.start();
        loadMimeMap("org/klomp/snark/web/mime");
        setResourceBase(_manager.getDataDir());
        setWarBase(WARBASE);
        // Started last: the sampler needs a live SnarkManager on its first tick.
        BandwidthGraph.start(_manager);
    }

    /**
     * Destroy the servlet.
     */
    @Override
    public void destroy() {
        // Flush and cancel sampling before torrents tear down.
        BandwidthGraph.stop();
        if (_manager != null) {_manager.stop();}
        super.destroy();
    }

    /**
     *  We override this to set the file relative to the storage directory
     *  for the torrent.
     *
     *  Deliberately unsynchronized: this runs for every snark request, and
     *  the only shared field it reads (_resourceBase) is volatile and swapped
     *  rarely by setResourceBase(). Holding the servlet monitor here
     *  serialized all requests behind slow torrent lookups and stalled the UI.
     *
     *  @param pathInContext should always start with /
     *  @return the resource
     */
    @Override
    public File getResource(String pathInContext) {
        if (pathInContext == null || pathInContext.equals("/") || pathInContext.equals("/index.jsp") ||
            !pathInContext.startsWith("/") || pathInContext.isEmpty() || pathInContext.equals("/index.html") ||
            pathInContext.startsWith(WARBASE) || pathInContext.contains("..")) {
            return super.getResource(pathInContext);
        }

        pathInContext = pathInContext.substring(1); // files in the i2psnark/ directory - get top level
        // The first path segment is the torrent's (filtered) base name;
        // everything after it resolves inside that torrent's storage.
        int slash = pathInContext.indexOf('/');
        String baseName = slash >= 0 ? pathInContext.substring(0, slash) : pathInContext;
        Snark snark = _manager.getTorrentByBaseName(baseName);
        if (snark != null) {
            Storage storage = snark.getStorage();
            if (storage != null) {
                String child = slash >= 0 ? pathInContext.substring(slash) : "";
                return resolveTorrentPath(storage, child);
            }
        }

        return new File(_resourceBase, pathInContext);
    }

    /**
     * The on-disk location of a path within a torrent. While an incomplete
     * download is staged in the temp dir, the data-directory tree may not
     * exist yet; paths are then resolved against the staging tree, which
     * mirrors the data-directory layout.
     *
     * @param storage the torrent's storage
     * @param pathInTorrent the path within the torrent, "/" or empty for the root
     * @return the physical file
     * @since 0.9.71+
     */
    private static File resolveTorrentPath(Storage storage, String pathInTorrent) {
        File sbase = storage.getBase();
        File r = pathInTorrent.equals("/") ? sbase : new File(sbase, pathInTorrent);
        if (!r.exists() && storage.getStagingDir() != null) {
            File staging = storage.getStagingDir();
            r = pathInTorrent.equals("/") ? staging : new File(staging, pathInTorrent);
        }
        return r;
    }

    /**
     *  Handle what we can here, calling super.doGet() for the rest.
     *  @since 0.8.3
     */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGetAndPost(request, response);
    }

    /**
     *  Handle what we can here, calling super.doPost() for the rest.
     *  @since Jetty 7
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGetAndPost(request, response);
    }

    /**
     * Returns whether advanced mode is enabled in the router console.
     *
     * @return true if advanced mode is enabled
     */
    public boolean isAdvanced() {return _context.getBooleanProperty(PROP_ADVANCED);}

    /**
     * Returns whether the Sora display font should be used.
     *
     * @return true if the Sora font is enabled or running in standalone mode
     */
    public boolean useSoraFont() {
        return _context.getBooleanProperty(RC_PROP_ENABLE_SORA_FONT) || isStandalone();
    }

    /**
     * Handle what we can here, calling super.doGet() or super.doPost() for the rest.
     *
     * Some parts modified from Jetty
     *
     * Section map, in order:
     * <ol>
     *   <li>gatekeeping - CSRF origin check, CSP for scripts, static
     *       WARBASE resources</li>
     *   <li>handleAjaxRequest() - XHR fragment endpoints</li>
     *   <li>browser API /_add and bridge magnet page</li>
     *   <li>bridge extension update notice</li>
     *   <li>handleUnmanagedPath() - directory listings, playlists, static
     *       passthrough for everything but the managed pages</li>
     *   <li>handleFormSubmission() - nonce'd POST actions, P-R-G redirect</li>
     *   <li>page assembly - head/navbar/search scaffolding, then either
     *       writeConfigurePanels() or writeTorrentsSection(), then
     *       writePageTail()</li>
     * </ol>
     */
    private void doGetAndPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Get HTTP method and servlet path
        String method = req.getMethod(); // since we are not overriding handle*(), do this here
        String path = req.getServletPath(); // this is the part after /i2psnark
        String lang = req.getParameter("lang");

        req.setCharacterEncoding("UTF-8"); // Set request encoding early

        // CSRF: Validate Origin header for POST requests
        if ("POST".equals(method) && !allowOrigin(req)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid Origin header for POST request");
            return;
        }

        // Set Content Security Policy header for JS requests
        String csp = "default-src 'self'; base-uri 'self'; connect-src 'self'; worker-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; ";
        if (path != null && path.contains(".js")) {resp.setHeader("Content-Security-Policy", csp);}

        // Handle static resource under WARBASE only supporting GET/HEAD
        if (path != null && path.startsWith(WARBASE)) {
            if ("GET".equals(method) || "HEAD".equals(method)) {super.doGet(req, resp);}
            else {resp.sendError(405);} // no POST either
            return;
        }

        String theme = _manager.getTheme();
        if (_context.isRouterContext()) {_themePath = "/themes/snark/" + theme + '/';}
        else {_themePath = _contextPath + WARBASE + "themes/snark/" + theme + '/';}
        _imgPath = _themePath + "images/";
        boolean isConnected = _manager.util().connected();
        String pOverride = isConnected ? null : "";
        String peerString = getQueryString(req, pOverride, null, null, "");
        String jsPfx = _context.isRouterContext() ? "" : ".res";

        PrintWriter out = null;
        boolean isConfigure = path.endsWith("/configure");

        if (isAjaxPath(path)) {
            handleAjaxRequest(path, isConfigure, peerString, req, resp);
            return;
        }

        // Browser API: nonce-free add of magnet links, torrent URLs, or info hashes.
        // POST only; authorized by the enable flag plus loopback / allowed hosts / API key.
        if ("/_add".equals(path)) {
            handleBrowserApiAdd(req, resp);
            return;
        }

        // Magnet handler page for the I2PSnark Bridge extension: the browser opens
        // this page when a magnet: link is handed to the extension; the page's
        // magnetHandler.js script POSTs the magnet to /_add on the same origin and
        // the extension shows a notification. GET only; no server-side side effects.
        if ("/magnet".equals(path)) {
            handleMagnetPage(req, resp);
            return;
        }

        // I2PSnark Bridge self-report: the extension adds its version header to
        // every request to the router. Notify once per webapp start, via the
        // screen log, when an update is available.
        if ("GET".equals(method)) {
            String installed = req.getHeader(BridgeVersion.HEADER);
            String bundled = getBridgeVersion();
            if (BridgeVersion.isUpdateAvailable(installed, bundled) && !_bridgeUpdateLogged) {
                _bridgeUpdateLogged = true;
                _manager.addMessage(_t("I2PSnark Bridge extension v{0} is outdated, update to v{1} on the config page", DataHelper.escapeHTML(installed), bundled));
            }
        }

        boolean isIndex = isIndexPath(path);

        // Handle non index, configure, or known special paths
        if (isUnmanagedPath(path, isIndex, isConfigure)) {
            handleUnmanagedPath(req, resp, method, path);
            return;
        }

        setHTMLHeaders(resp, cspNonce, true);

        // Form submissions arrive with a nonce; anything else renders below
        if (handleFormSubmission(req, resp, method, peerString)) {return;}

        // Cache panel and utility flags
        boolean noCollapse = noCollapsePanels(req);
        boolean collapsePanels = _manager.util().collapsePanels();
        boolean showStatusFilter = _manager.util().showStatusFilter();

        setHTMLHeaders(resp, cspNonce, false);
        StringBuilder buf = new StringBuilder(16 * 1024);
        int delay = _manager.getRefreshDelaySeconds();
        int pageSize = _manager.getPageSize();
        String head = renderHead(req, isConfigure, isIndex, noCollapse, collapsePanels,
                                     showStatusFilter, peerString, lang, delay, pageSize);

        buf.append(head)
           .append("<body style=display:none;pointer-events:none id=snarkxhr class=\"")
           .append(theme).append(" lang_").append(lang).append("\">\n");

        if (isIndex) {
            buf.append("<span id=toast hidden></span>\n").append(IFRAME_FORM);
        }

        // Build navbar, cache trackers and filters once
        List<Tracker> sortedTrackers = null;
        List<TorrentCreateFilter> sortedFilters = null;
        sortedTrackers = _manager.getSortedTrackers();
        sortedFilters = _manager.getSortedTorrentCreateFilterStrings();

        buf.append("<div id=navbar>\n");
        appendNavbar(buf, isConfigure, peerString, sortedTrackers);
        buf.append("</div>\n");

        appendSearchForm(buf, req);

        // Notify user about new torrent URLs in GET requests
        String newURL = req.getParameter("newURL");
        if (newURL != null && !newURL.trim().isEmpty() && "GET".equals(method)) {
            _manager.addMessage(_t("Click \"Add torrent\" button to fetch torrent"));
        }

        buf.append("<div id=page>\n<div id=mainsection class=mainsection>\n");

        // Output header and navigation content
        out = resp.getWriter();
        out.append(buf);
        buf.setLength(0);
        out.flush();

        // Render messages area dynamically
        writeMessages(out, isConfigure, peerString);

        if (isConfigure) {
            writeConfigurePanels(out, req);
        } else {
            writeTorrentsSection(out, req, sortedTrackers, sortedFilters);
        }

        writePageTail(out, isConfigure);
        out.flush();
    }

    /** The two XHR fragment endpoints polled by the console JS. */
    static boolean isAjaxPath(String path) {
        return "/.ajax/xhr1.html".equals(path) || "/.ajax/xhrscreenlog.html".equals(path);
    }

    /**
     * Whether path is the torrent list landing page.
     *
     * @param path servlet path after the context, may be null
     */
    static boolean isIndexPath(String path) {
        return path != null && (path.isEmpty() || "/".equals(path) || "index.jsp".equals(path));
    }

    /**
     * Paths that bypass normal page rendering and are answered by
     * handleUnmanagedPath: everything outside the index page(s), the POST
     * target, and the configuration UI.
     *
     * @param path servlet path after the context, may be null
     */
    static boolean isUnmanagedPath(String path, boolean isIndex, boolean isConfigure) {
        if (isIndex || "/index.html".equals(path) || "/_post".equals(path) || isConfigure) {return false;}
        return true;
    }

    /**
     * Serves the XHR fragment endpoints; unknown paths are ignored so the
     * caller's fall-through routing stays in charge.
     *
     * @return true if the request was handled here
     * @since 0.9.71+
     */
    private boolean handleAjaxRequest(String path, boolean isConfigure, String peerString,
                                      HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isAjaxPath(path)) {return false;}
        setXHRHeaders(resp, cspNonce, false);
        PrintWriter out = resp.getWriter();
        if ("/.ajax/xhr1.html".equals(path)) {
            // volatile read; disk probe stays out of any shared lock
            boolean canWrite = _resourceBase.canWrite();
            out.write("<!DOCTYPE HTML>\n<html>\n<body id=snarkxhr>\n<div id=screenlogStamp data-v=\"");
            out.write(screenLogStamp());
            out.write("\" hidden></div>\n");
            emitGraphData(req, out);
            out.write("<div id=mainsection>\n");
            writeTorrents(out, req, canWrite);
            out.write("\n</div>\n</body>\n</html>\n");
        } else {
            out.write("<!DOCTYPE HTML>\n<html>\n<body id=snarkxhrlogs>\n");
            writeMessages(out, isConfigure, peerString);
            out.write("</body>\n</html>\n");
        }
        out.flush();
        return true;
    }

    /**
     * Answers everything outside the managed pages: torrent directory
     * listings (with optional playlist export) under trailing-slash paths,
     * and container delegation for the rest.
     *
     * @since 0.9.71+
     */
    private void handleUnmanagedPath(HttpServletRequest req, HttpServletResponse resp,
                                     String method, String path) throws ServletException, IOException {
        if (!path.endsWith("/")) {
            if ("GET".equals(method) || "HEAD".equals(method)) {super.doGet(req, resp);}
            else if ("POST".equals(method)) {super.doPost(req, resp);}
            else {resp.sendError(405);}
            return;
        }
        String pathInfo = req.getPathInfo();
        String pathInContext = addPaths(path, pathInfo);
        File resource = getResource(pathInContext);
        if (resource == null) {
            resp.sendError(404);
            return;
        }
        if (req.getParameter("playlist") != null) {
            String base = addPaths(req.getRequestURI(), "/");
            String listing = getPlaylist(req.getRequestURL().toString(), base, req.getParameter("sort"));
            if (listing == null) {
                resp.sendError(404);
                return;
            }
            setHTMLHeaders(resp, cspNonce, false);
            resp.setContentType("audio/mpegurl; charset=UTF-8; name=\"playlist.m3u\"");
            resp.addHeader("Content-Disposition", "attachment; filename=\"playlist.m3u\"");
            resp.getWriter().write(listing);
            return;
        }
        boolean isPost = "POST".equals(method);
        // headers must precede any bytes: large tables stream directly
        if (!isPost) {setHTMLHeaders(resp, cspNonce, true);}
        String base = addPaths(req.getRequestURI(), "/");
        String listing = getListHTML(resource, base, true,
            isPost ? req.getParameterMap() : null,
            req.getParameter("sort"),
            isPost ? null : resp.getWriter());
        if (isPost) {
            sendRedirect(req, resp, ""); // POST-Redirect-GET
        } else if (listing != null && !listing.isEmpty()) {
            resp.getWriter().write(listing); // buffered page, or streamed-mode tail
        } else if (!resp.isCommitted()) {
            resp.sendError(404); // safety net; unreachable on GET today
        }
    }

    /**
     * Dispatches a nonce-bearing form submission to its action and finishes
     * with the P-R-G redirect (or G-R-G to hide params from the address bar).
     *
     * @return true if a form was submitted and the response is complete;
     *         false when there is no nonce and normal rendering should proceed
     * @since 0.9.71+
     */
    private boolean handleFormSubmission(HttpServletRequest req, HttpServletResponse resp,
                                         String method, String peerString) throws IOException {
        String nonce = req.getParameter("nonce");
        if (nonce == null) {return false;}
        if (( "POST".equals(method) || "Clear".equals(req.getParameter("action"))) &&
            isValidNonce(nonce)) {
            if (processRequest(req, resp)) {return true;} // response fully handled (e.g. install redirect)
        } else if (!(method.equals("POST") || "Clear".equals(req.getParameter("action")))) {
            // Lynx bug?
            _manager.addMessage("Bad form method, POST required");
        } else {
            // nonce is constant, shouldn't happen
            _manager.addMessage("Please retry form submission (bad nonce)");
        }
        sendRedirect(req, resp, peerString);
        return true;
    }

    /**
     * Renders the navbar: configure variant links back to the torrent list;
     * list variant adds tracker links from the sorted tracker list.
     *
     * @since 0.9.71+
     */
    private void appendNavbar(StringBuilder buf, boolean isConfigure, String peerString,
                              List<Tracker> sortedTrackers) {
        if (isConfigure) {
            buf.append("<a href=")
               .append(_contextPath)
               .append("/ title=\"")
               .append(_t("Torrents"))
               .append("\" id=nav_main class=\"snarkNav isConfig\">")
               .append(_contextName.equals(DEFAULT_NAME) ? _t("I2PSnark") : _contextName)
               .append("</a>");
            return;
        }
        buf.append("<a href=\"")
           .append(_contextPath).append('/')
           .append(peerString)
           .append("\" title=\"")
           .append(_t("Refresh page"))
           .append("\" id=nav_main class=snarkNav>")
           .append(_contextName.equals(DEFAULT_NAME) ? _t("I2PSnark") : _contextName)
           .append("</a><a href=")
           .append(_contextPath)
           .append("/configure id=nav_config class=snarkNav>")
           .append(_t("Configure"))
           .append("</a><a href=http://discuss.i2p/ id=nav_forum class=snarkNav target=_blank title=\"")
           .append(_t("Torrent &amp; filesharing forum"))
           .append("\">")
           .append(_t("Forum"))
           .append("</a>");

        for (Tracker t : sortedTrackers) {
            if (t.baseURL == null || !t.baseURL.startsWith("http")) continue;
            if (_manager.util().isKnownOpenTracker(t.announceURL)) continue;
            buf.append("<a href=\"")
               .append(t.baseURL)
               .append("\" class=\"snarkNav nav_tracker\" target=_blank>")
               .append(t.name)
               .append("</a>");
        }
    }

    /**
     * Renders the search form when multiple torrents exist; hidden until
     * realtimeSearch.js activates it, functional without JS via GET submit.
     *
     * @since 0.9.71+
     */
    private void appendSearchForm(StringBuilder buf, HttpServletRequest req) {
        if (_manager.getTorrents().size() <= 1) {return;}
        String s = req.getParameter("search");
        boolean searchActive = (s != null && !s.isEmpty());
        buf.append("<form id=snarkSearch action=\"").append(_contextPath).append("\" method=GET hidden>\n")
           .append("<span id=searchwrap><input id=searchInput type=search required name=search size=20 placeholder=\"")
           .append(_t("Search torrents")).append("\"");
        if (searchActive) {
            buf.append(" value=\"").append(DataHelper.escapeHTML(s.trim())).append("\"");
        }
        buf.append("><a href=").append(_contextPath).append(" title=\"").append(_t("Clear search"))
           .append("\" hidden>x</a></span><input type=submit value=\"Search\">\n</form>\n");
    }

    /**
     * Configuration page panels below the messages area.
     *
     * @since 0.9.71+
     */
    private void writeConfigurePanels(PrintWriter out, HttpServletRequest req) throws IOException {
        out.write("<div class=logshim></div>\n</div>\n");
        writeConfigForm(out, req);
        writeTorrentCreateFilterForm(out, req);
        writeTrackerForm(out, req);
    }

    /**
     * Torrent table plus the add/create forms of the lower section.
     *
     * @since 0.9.71+
     */
    private void writeTorrentsSection(PrintWriter out, HttpServletRequest req,
                                      List<Tracker> sortedTrackers,
                                      List<TorrentCreateFilter> sortedFilters) throws IOException {
        boolean canWrite = _resourceBase.canWrite();
        boolean pageOne = writeTorrents(out, req, canWrite);

        out.write("</div>\n"); // close mainsection div

        boolean enableAddCreate = _manager.util().enableAddCreate();

        if ((pageOne || enableAddCreate) && canWrite) {
            out.write("<div id=lowersection>\n");
            writeAddForm(out, req);
            writeSeedForm(out, req, sortedTrackers, sortedFilters);
            out.write("</div>\n");
        }
    }

    /**
     * Page scripts and footer, shared by index and configuration pages.
     *
     * @since 0.9.71+
     */
    private void writePageTail(PrintWriter out, boolean isConfigure) throws IOException {
        String jsPath = "<script src=" + _resourcePath + "js/";

        if (!isConfigure) {
            out.write(jsPath + "toggleLinks.js type=module></script>\n");
            out.write(jsPath + "toggleAddCreate.js type=module></script>\n");
        }
        out.write(jsPath + "setFilterQuery.js type=module></script>\n");
        out.write(jsPath + "realtimeSearch.js type=module></script>\n");

        if (!isStandalone()) {out.write(FOOTER);}
        else {out.write(FOOTER_STANDALONE);}
    }

    /**
     * Renders the full <head> section of the main HTML page.
     * @since 0.9.68+
     */
    private String renderHead(HttpServletRequest req, boolean isConfigure,
                              boolean isIndex, boolean noCollapse,
                              boolean collapsePanels, boolean showStatusFilter,
                              String peerString, String lang, int delay, int pageSize) {
        StringBuilder buf = new StringBuilder(4096);
        String theme = _manager.getTheme();

        // Determine page background color based on theme
        String pageBackground = "#fff";
        if ("dark".equals(theme)) {
            pageBackground = "#000";
        } else if ("midnight".equals(theme)) {
            pageBackground = "#001";
        } else if ("ubergine".equals(theme)) {
            pageBackground = "#101";
        } else if ("vanilla".equals(theme)) {
            pageBackground = "#cab39b";
        }

        String resourcePath = _contextPath + WARBASE;

        buf.append(DOCTYPE)
           .append("<html")
           .append(isStandalone() ? " class=standalone" : "")
           .append(" style=\"background:").append(pageBackground).append("\">\n<head>\n")
           .append("<meta charset=utf-8>\n<meta name=viewport content=\"width=device-width, initial-scale=1\">\n")
           .append("<script nonce=").append(cspNonce).append(">const theme = \"").append(theme).append("\";</script>\n");

        if (!isConfigure && !isStandalone()) {
            buf.append("<link rel=modulepreload href=/js/iframeResizer/updatedEvent.js>\n")
               .append("<link rel=modulepreload href=/js/iframeResizer/iframeResizer.contentWindow.js>\n")
               .append("<link rel=modulepreload href=/js/setupIframe.js>\n");
        }
        if (!isConfigure) {
            buf.append("<link rel=modulepreload href=").append(resourcePath).append("js/refreshTorrents.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/realtimeSearch.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/snarkSort.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/toggleLinks.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/toggleLog.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/toggleAddCreate.js>\n");
            if (showStatusFilter) {
                buf.append("<link rel=modulepreload href=").append(resourcePath).append("js/filterBar.js>\n")
                   .append("<link rel=modulepreload href=").append(resourcePath).append("js/setFilterQuery.js>\n");
            }
            if (isIndex) {
                buf.append("<script src=/i2psnark/.res/js/click.js type=module></script>\n")
                   .append("<script src=/i2psnark/.res/js/snarkAlert.js type=module></script>\n");
            }
        }

        String v = CoreVersion.VERSION;
        String fontPath = isStandalone() ? "/i2psnark/.res/themes/fonts" : "/themes/fonts";
        String displayFont = isStandalone() || useSoraFont() ? "Sora" : "OpenSans";
        buf.append("<link rel=preload href=").append(fontPath).append("/").append(displayFont).append(".css as=style>\n")
           .append("<link rel=preload href=").append(fontPath).append("/").append(displayFont).append("/").append(displayFont)
           .append(".woff2 as=font type=font/woff2 crossorigin>\n")
           .append("<link rel=stylesheet href=").append(fontPath).append("/").append(displayFont).append(".css>\n")
           .append("<link rel=preload href=\"").append(_themePath).append("snark.css?").append(v).append("\" as=style>\n")
           .append("<link rel=preload href=\"").append(_themePath).append("images/images.css?").append(v).append("\" as=style>\n")
           .append("<link rel=\"shortcut icon\" href=\"").append(_contextPath).append(WARBASE).append("icons/favicon.svg\">\n")
           .append("<title>");
        buf.append(_contextName.equals(DEFAULT_NAME) ? _t("I2PSnark") : _contextName)
           .append(" - ")
           .append(isConfigure ? _t("Configuration") : _t("Anonymous BitTorrent Client"))
           .append("</title>\n");

        if (!isConfigure) {
            buf.append("<script nonce=").append(cspNonce).append(">\n")
               .append("  const deleteMsg = \"").append(_t("Are you sure you want to delete {0} and all downloaded data?")).append("\";\n")
               .append("  const postDeleteMsg = \"").append(_t("Deleting <b>{0}</b> and all associated data...")).append("\";\n")
               .append("  const removeMsg = \"").append(_t("Are you sure you want to delete torrent file {0} and associated metadata?")).append("\";\n")
               .append("  const removeMsg2 = \"").append(_t("Note: Downloaded data will not be deleted.")).append("\";\n")
               .append("  const postRemoveMsg = \"").append(_t("Deleting {0} and associated metadata only...")).append("\";\n")
               .append("  const snarkPageSize = ").append(pageSize).append(";\n")
               .append("  const snarkRefreshDelay = ").append(delay).append(";\n")
               .append("  const totalSnarks = ").append(_manager.listTorrentFiles().size()).append(";\n")
               .append("  window.snarkPageSize = snarkPageSize;\n")
               .append("  window.snarkRefreshDelay = snarkRefreshDelay;\n")
                .append("  window.totalSnarks = totalSnarks;\n</script>\n");
             buf.append("<script nonce=").append(cspNonce).append(" type=module>\n")
               .append("  import {initSnarkRefresh} from \"").append(resourcePath).append("js/refreshTorrents.js\";\n")
               .append("  document.addEventListener(\"DOMContentLoaded\", initSnarkRefresh);\n</script>\n");
            if (delay > 0) {
                buf.append("<noscript><meta http-equiv=refresh content=\"").append(delay < 60 ? 60 : delay)
                   .append(";").append(_contextPath).append("/").append(peerString).append("\"></noscript>\n");
            }
        }

        // Append CSS assets and user overrides
        buf.append(cssLink("snark.css", _themePath, "id=snarkTheme")).append("\n");
        buf.append(cssLink("images/images.css", _themePath)).append("\n");

        String slash = String.valueOf(File.separatorChar);
        String themeBase = I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() + slash +
                           "docs" + slash + "themes" + slash + "snark" + slash + theme + slash;
        File override = new File(themeBase + "override.css");
        int rnd = _context.random().nextInt(3);
        if (!isStandalone() && rnd == 0 && "light".equals(theme)) {
            buf.append("<style>#screenlog{background:url(/themes/snark/light/images/k2.webp) no-repeat right bottom,")
               .append("repeating-linear-gradient(180deg,rgba(255,255,255,.5) 2px,rgba(220,220,255,.5) 4px),")
               .append("var(--snarkGraph) no-repeat,var(--th);background-size:72px auto,100%,")
               .append("calc(100% - 80px) calc(100% - 4px),100%;background-position:right bottom,")
               .append("center center,left bottom,center center;background-blend-mode:multiply,overlay,luminosity,normal}</style>\n");
}
        if (!isStandalone() && override.exists()) {
            buf.append(cssLink("override.css", _themePath) + "\n"); // optional override.css for version-persistent user edits
        }

        // Larger fonts for CJK languages
        if ("zh".equals(lang) || "ja".equals(lang) || "ko".equals(lang)) {
            buf.append(cssLink("snark_big.css", _themePath)).append("\n");
        }

        if (noCollapse || !collapsePanels) {
            buf.append(cssLink("nocollapse.css", _themePath)).append("\n");
        }

        buf.append("<style id=cssfilter></style>\n<style id=toggleLogCss></style>\n");

        buf.append("</head>\n");
        return buf.toString();
    }

    /**
     * Handles the standard HTTP headers for all HTML pages.
     *
     * @param resp the HttpServletResponse object to which headers will be added
     * @param cspNonce the nonce value for Content Security Policy (CSP)
     * @param allowMedia whether to allow media sources in CSP
     * @since 0.9.16 moved from doGetAndPost()
     */
    private void setHTMLHeaders(HttpServletResponse resp, String cspNonce, boolean allowMedia) {
        String mimeType = resp.getContentType();
        String nonceString = "nonce-" + cspNonce;
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=utf-8");
        resp.setHeader("Accept-Ranges", "bytes");

        if (mimeType != null && (
                mimeType.equals("image/png") || mimeType.equals("image/jpeg") ||
                mimeType.equals("font/woff2") || mimeType.equals("image/gif") ||
                mimeType.equals("image/webp") || mimeType.equals("image/svg+xml") ||
                mimeType.equals("text/css") || mimeType.contains("javascript"))) {
            resp.setHeader("Cache-Control", "private, max-age=2628000, immutable");
        } else {resp.setHeader("Cache-Control", "private, no-cache, max-age=2628000");}

        if (mimeType == null || mimeType.contains("text") || mimeType.contains("script") || mimeType.contains("application")) {
            String csp = "default-src 'self'; base-uri 'self'; connect-src 'self'; worker-src 'self'; style-src 'self' 'unsafe-inline'; " +
                         "img-src 'self' blob: data:; " +
                         "script-src-elem 'self' '" + nonceString + "'; " +
                         "script-src 'self' '" + nonceString + "'; " +
                         "object-src 'none'; media-src '" + (allowMedia ? "self" : "none") + "'; form-action 'self'";
            resp.setHeader("Content-Security-Policy", csp);
            resp.setHeader("Permissions-Policy", "fullscreen=(self)");
            resp.setHeader("Referrer-Policy", "same-origin");
        }

        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-XSS-Protection", "1; mode=block");
    }

    /**
     * Cross-Origin Resource Sharing (CORS) and Content Security Policy (CSP) headers
     * for XMLHttpRequest (XHR) responses.
     *
     * @param resp the HttpServletResponse object to which headers will be added
     * @param cspNonce the nonce value for the Content Security Policy (CSP)
     * @param allowMedia indicates whether media sources should be allowed in CSP
     */
    private void setXHRHeaders(HttpServletResponse resp, String cspNonce, boolean allowMedia) {
        String refresh = String.valueOf(_manager.getRefreshDelaySeconds());
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=utf-8");
        int maxAge = Math.min(I2PSnarkUtil.parseInt(refresh, 60), 60);
        resp.setHeader("Cache-Control", "private, no-cache, max-age=" + maxAge);
        resp.setHeader("Content-Security-Policy", "default-src 'none'; child-src 'self'");
    }

    /**
     * Cheap change-detection stamp for the screen log, emitted with every xhr1
     * refresh payload so the client can skip the xhrscreenlog.html round trip
     * entirely while the log is unchanged. Combines the last message id (which
     * advances on every add) with the list size (which moves on clears and
     * cap-driven trims), so both growth and shrinkage are detected without
     * rendering anything.
     *
     * @return "lastMessageId:messageCount", or "0:0" when the log is empty
     * @since 0.9.71+
     */
    private String screenLogStamp() {
        List<UIMessages.Message> msgs = _manager.getMessages();
        if (msgs.isEmpty()) {return "0:0";}
        return msgs.get(msgs.size() - 1).id + ":" + msgs.size();
    }

    /**
     * Emits the bandwidth graph element for xhr1 payloads. The client passes its last
     * seen sample version as the "gv" parameter; the full sample CSV travels only when
     * the client is behind (including first-load, where no gv is sent), so
     * steady-state ticks cost a few bytes while new samples and fresh page loads get
     * data immediately.
     *
     * @param req the refresh request, may carry a "gv" parameter
     * @param out the PrintWriter to write the element to
     * @since 0.9.71+
     */
    private void emitGraphData(HttpServletRequest req, PrintWriter out) {
        long version = BandwidthGraph.getVersion();
        long clientVersion = -1;
        String gv = req.getParameter("gv");
        if (gv != null) {
            try {clientVersion = Long.parseLong(gv.trim());} catch (NumberFormatException nfe) {}
        }
        out.write("<div id=snarkGraphData data-v=\"" + version + "\"");
        if (clientVersion < version) {
            out.write(" data-samples=\"");
            out.write(BandwidthGraph.getSamples());
            out.write("\"");
        }
        out.write(" hidden></div>\n");
    }

    /**
     * Writes the logging messages to the HTML screenlog.
     *
     * @param out the PrintWriter to which the HTML output will be written
     * @param isConfigure a boolean indicating whether the current page is the configuration page
     * @param peerString a string containing the peer parameters for the URL
     * @throws IOException if an I/O error occurs while writing to the output stream
     */
    private void writeMessages(PrintWriter out, boolean isConfigure, String peerString) throws IOException {
        List<UIMessages.Message> msgs = _manager.getMessages();
        int entries = msgs.size();
        StringBuilder buf = new StringBuilder(entries*256);
        if (!msgs.isEmpty()) {
            buf.append("<div id=screenlog")
               .append(isConfigure ? " class=configpage" : "")
               .append(" tabindex=0>\n<a id=closelog href=\"")
               .append(_contextPath).append('/');
            if (isConfigure) {buf.append("configure");}
            if (!peerString.isEmpty()) {buf.append(peerString).append("&amp;");}
            else {buf.append("?");}
            int lastID = msgs.get(msgs.size() - 1).id;
            String tx = _t("clear messages");
            String x = _t("Expand");
            String s = _t("Shrink");
            buf.append("action=Clear&amp;id=").append(lastID)
               .append("&amp;nonce=").append(getNonce()).append("\">");
            appendIcon(buf, "delete", tx, tx, true, true);
            buf.append("</a>\n<a class=script id=expand hidden>");
            appendIcon(buf, "expand", x, x, true, true);
            buf.append("</a>\n<a class=script id=shrink hidden>");
            appendIcon(buf, "shrink", s, s, true, true);
            buf.append("</a>\n<ul id=messages class=volatile>\n");
            if (!_manager.util().connected()) {
                buf.append("<noscript>\n<li class=noscriptWarning>")
                   .append(_t("Warning! Javascript is disabled in your browser. " +
                            "If {0} is enabled, you will lose any input in the add/create torrent sections when a refresh occurs.",
                            "<a href=\"configure\">" + _t("page refresh") + "</a>"))
                   .append("</li>\n</noscript>\n");
            }

            for (int i = msgs.size() - 1; i >= 0; i--) {
                String msg = msgs.get(i).message
                                        .replace("Adding Magnet ", "Magnet added: " + "<span class=infohash>")
                                        .replace("Starting torrent: Magnet", "Starting torrent: <span class=infohash>");
                if (msg.contains("class=infohash")) {msg = INFOHASH_PAREN.matcher(msg).replaceFirst("</span> (");}
                if (msg.contains(_t("Warning - No I2P"))) {msg = msg.replace("</span>", "");}
                buf.append("<li class=msg>").append(msg).append("</li>\n");
            }
            buf.append("</ul>");
        } else {buf.append("<div id=screenlog hidden><ul id=messages></ul>");}
        // Seed the bandwidth graph into the page so a fresh load renders it
        // immediately, before the first refresh poll.
        long graphVersion = BandwidthGraph.getVersion();
        buf.append("<div id=snarkGraphData data-v=\"").append(graphVersion).append("\"");
        if (graphVersion >= 0) {
            buf.append(" data-samples=\"").append(BandwidthGraph.getSamples()).append("\"");
        }
        buf.append(" hidden></div>\n");
        buf.append("</div>\n<script src=")
           .append(_resourcePath)
           .append("js/toggleLog.js type=module></script>\n");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    /**
     * Writes the HTML representation of the torrents to the output stream.
     * This method generates the HTML structure for displaying torrents, including
     * headers, sorting options, pagination controls, and the torrent list itself.
     * It also updates the statistics array with aggregated torrent data.
     *
     * Output is gated by row count: pages with fewer than
     * STREAM_MIN_TORRENT_ROWS visible rows stage the whole table into one
     * buffer written in a single call, larger pages stream rows to the writer
     * as today. Markup order is identical in both modes. Note the page scaffold
     * upstream of this method is flushed before the gate is evaluated, so
     * buffered mode does not restore Content-Length here.
     *
     * @param out the PrintWriter to which the HTML output will be written
     * @param req the HttpServletRequest containing the request parameters
     * @param canWrite a boolean indicating whether the data directory is writable
     * @return true if the current page is the first page of the torrent list
     * @throws IOException if an I/O error occurs while writing to the output stream
     */
    private boolean writeTorrents(PrintWriter out, HttpServletRequest req, boolean canWrite) throws IOException {
        final long[] stats = new long[6];
        String filter = req.getParameter("filter") != null ? req.getParameter("filter") : "";
        String peerParam = req.getParameter("p");
        String search = req.getParameter("search");
        String srt = normalizeSortParam(req.getParameter("sort"));
        String stParam = req.getParameter("st");
        boolean filterEnabled = !filter.isEmpty() && !"all".equals(filter);

        List<Snark> snarks = getSortedSnarks(req);
        int total = snarks.size();
        int downloads = 0;
        int uploads = 0;
        long totalETA = 0;
        boolean isConnected = _manager.util().connected();
        boolean noSnarks = snarks.isEmpty();
        boolean isForm = isConnected || !noSnarks;
        boolean showStatusFilter = _manager.util().showStatusFilter();
        boolean searchActive = search != null && !search.isEmpty();
        boolean isUploading = false;
        boolean hasPeers = false;
        DHT dht = _manager.util().getDHT();

        if (searchActive) {
            List<Snark> matches = search(search, snarks);
            if (matches != null) { snarks = matches; searchResults = matches.size(); }
        }

        int start = 0;
        if (stParam != null) {
            start = Math.max(0, Math.min(total - 1, I2PSnarkUtil.parseInt(stParam, 0)));
        }

        int pageSize = filterEnabled ? 9999 : Math.max(_manager.getPageSize(), 10);
        String ps = req.getParameter("ps");
        if ("null".equals(ps)) ps = Integer.toString(pageSize);
        if (ps != null) {
            pageSize = Math.min(Math.max(I2PSnarkUtil.parseInt(ps, pageSize), 10), 9999);
        }

        // rows actually rendered on this page; the gate decision must precede
        // any writes so both modes emit identical markup in identical order
        final int end = Math.min(start + pageSize, snarks.size());
        final boolean streamed = shouldStreamTorrentRows(Math.max(0, end - start));
        StringWriter sink = streamed ? null : new StringWriter();
        PrintWriter target = streamed ? out : new PrintWriter(sink);

        boolean isDegraded = false;
        boolean noThinsp = false;
        String ua = req.getHeader("User-Agent");
        if (ua != null) {
            isDegraded = ServletUtil.isTextBrowser(ua);
            noThinsp = isDegraded || ua.startsWith("Opera");
        }

        if (isForm) {
            if (showStatusFilter) renderFilterBar(target, req);
            else target.write("<form id=torrentlist action=_post method=POST target=processForm>\n");
            writeHiddenInputs(target, req, null);
            target.flush();
        }

        target.write(TABLE_HEADER);
        paginator(target, req, start, pageSize, total, filter, noThinsp, isForm, searchActive, (searchActive ? search.length() : 0));
        target.write(appendSnarkHeader(req, snarks, start, pageSize, filter, peerParam, srt, _contextPath));
        target.flush();

        boolean showDebug = "2".equals(peerParam);
        // Decode a targeted infohash once instead of Base64-encoding every
        // torrent's hash per row just for the comparison.
        final boolean showAllPeers = "1".equals(peerParam);
        byte[] peerHash = null;
        if (!showDebug && !showAllPeers && peerParam != null) {
            byte[] h = Base64.decode(peerParam);
            if (h != null && h.length == 20) {peerHash = h;}
        }
        StringBuilder buf = new StringBuilder(2048);
        Map<ByteArray, BadgeInfo> badgeCache = new HashMap<>();

        // Mint short action tokens for every loaded torrent (not just this page's
        // slice) so POST resolution always finds exactly one match.
        List<String> b64Names = new ArrayList<>(snarks.size());
        for (int i = 0; i < snarks.size(); i++) {
            b64Names.add(Base64.encode(snarks.get(i).getInfoHash()));
        }
        Map<String, String> actionTokens = ActionTokens.mint(b64Names);

        for (int i = start; i < end; i++) {
            Snark snark = snarks.get(i);
            boolean showPeers = showDebug || showAllPeers
                                || (peerHash != null && DataHelper.eq(snark.getInfoHash(), peerHash));
            buf.setLength(0);
            displaySnark(target, new RowContext(snark, i, showPeers, stats, noThinsp, canWrite, filter, srt, badgeCache, actionTokens), buf);

            // additionally accumulate downloads, uploads, ETA, flags
            if (snark.getPeerCount() >= 1) {
                if (snark.getDownloadRate() > 0) downloads++;
                if (snark.getUploadRate() > 0) { uploads++; isUploading = true; }
                hasPeers = true;
                long needed = snark.getNeededLength();
                if (needed > total) needed = total;
                if (stats[STAT_DOWNLOAD_RATE] > 0 && needed > 0) totalETA += needed / stats[STAT_DOWNLOAD_RATE];
            }
        }

        if (total == 0) {
            target.write("<tbody id=noTorrents><tr id=noload class=noneLoaded><td colspan=12><i>");
            {
                File dd = _resourceBase;
                if (!dd.exists() && !dd.mkdirs()) target.write(_t("Data directory cannot be created") + ": " + DataHelper.escapeHTML(dd.toString()));
                else if (!dd.isDirectory()) target.write(_t("Not a directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                else if (!dd.canRead()) target.write(_t("Unreadable") + ": " + DataHelper.escapeHTML(dd.toString()));
                else if (!canWrite) target.write(_t("No write permissions for data directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                else if (searchActive) target.write(_t("No torrents found."));
                else target.write(_t("No torrents loaded."));
            }
            target.write("</i></td></tr></tbody>");
        }

        appendSnarkFooter(target, buf, new FooterContext(stats, totalETA, total, isConnected, noSnarks, hasPeers, isUploading, dht, isStandalone(), peerParam));
        target.write("</table>\n");

        if (isForm) target.write("</form>\n");
        if (total > 0) target.write("<script src=/i2psnark/.res/js/convertTooltips.js type=module></script>");

        // buffered mode: the staged table reaches the client as one write;
        // streamed mode: rows are already on the wire
        if (sink != null) {out.write(sink.toString());}
        else {out.flush();}
        return start == 0;
    }

    /**
     * Builds the HTML table header row for the torrent list display, including sortable column
     * headers, peer toggling links, and activity indicators.
     *
     * <p>Dispatcher only: prepares request-derived inputs into a
     * SortHeaderContext plus an activity scan, then delegates each column to
     * its own builder so per-column markup stays independently reviewable.</p>
     *
     * @param req the HttpServletRequest containing the current request parameters
     * @param snarks the list of torrent objects currently shown or filtered
     * @param start the starting index of the current page slice within the torrent list
     * @param pageSize the maximum number of torrents displayed per page
     * @param filterParam the current filter parameter controlling torrent visibility
     * @param peerParam the current peer parameter controlling peer display toggling
     * @param currentSort the current sorting parameter for the torrent list
     * @param contextPath the context path prefix for constructing URLs
     * @return a String containing the fully constructed HTML for the torrent table header row
     * @since 0.9.68+
     */
    private String appendSnarkHeader(HttpServletRequest req, List<Snark> snarks, int start, int pageSize,
                                     String filterParam, String peerParam, String currentSort, String contextPath) {
        StringBuilder buf = new StringBuilder(2048);
        boolean showSort = snarks.size() > 1;
        boolean isConnected = _manager.util().connected();
        boolean noSnarks = snarks.isEmpty();
        int total = snarks.size();

        // Cache common localized strings
        final String txtStatus = _t("Status");
        final String txtTorrent = _t("Torrent");
        final String txtETA = _t("ETA");
        final String txtRX = _t("RX");
        final String txtRXRate = _t("RX Rate");
        final String txtTX = _t("TX");
        final String txtTXRate = _t("TX Rate");
        final String txtStartAll = _t("Start All");
        final String txtStopAll = _t("Stop All");
        final String txtStopAllTitle = _t("Stop all torrents and the I2P tunnel");
        final String txtStartAllTitle = _t("Start all torrents and the I2P tunnel");
        final String txtStartStoppedTitle = _t("Start all stopped torrents");

        // Construct filterQuery string reliably; pairs inside it always join
        // with '&' - buildLink() owns any leading '?'
        String currentSearch = req.getParameter("search");
        StringBuilder fq = new StringBuilder("filter=");
        fq.append(filterParam == null || filterParam.isEmpty() ? "all" : filterParam);
        if (currentSearch != null && !currentSearch.isEmpty()) {
            fq.append("&search=").append(currentSearch);
        }
        String filterQuery = fq.toString();

        TorrentActivityScan scan = scanTorrentActivity(snarks, start, pageSize, total);
        SortHeaderContext hc = new SortHeaderContext(req, contextPath, currentSort,
                                                     filterParam, filterQuery, showSort);

        // Start building header row
        buf.append("<tr>");
        // Status header sort parameters and active sort detection,
        // cycling status asc/desc and status+pool asc/desc when pooled dests exist
        appendStatusHeader(buf, hc, multiDestActive(), txtStatus);
        // Peer toggle link cell
        appendPeerToggleHeader(buf, hc, peerParam, isConnected, noSnarks, scan);
        // Torrent name/type sorting header (colspan=2 includes hidden checkbox)
        buf.append("<th class=torrentLink colspan=2><input id=linkswitch class=optbox type=checkbox hidden></th>");
        appendNameTypeHeader(buf, hc, txtTorrent);
        buf.append("<th class=tName></th>");
        appendEtaHeader(buf, hc, txtETA, isConnected, noSnarks, scan);
        appendRxHeader(buf, hc, txtRX, noSnarks);
        appendRateDownHeader(buf, hc, txtRXRate, txtRX, peerParam, isConnected, noSnarks, scan);
        appendTxHeader(buf, hc, txtTX);
        appendRateUpHeader(buf, hc, txtTXRate, isConnected, noSnarks, scan);
        // Action buttons header (Start/Stop all)
        appendActionsHeader(buf, snarks, isConnected, noSnarks,
                            txtStartAll, txtStopAll, txtStopAllTitle, txtStartAllTitle, txtStartStoppedTitle);
        buf.append("</tr>\n</thead>\n<tbody id=snarkTbody>");

        return buf.toString();
    }

    /**
     * Request-derived inputs shared by every sortable torrent-list header
     * cell; replaces a thirteen-parameter signature. Package-visible for testing.
     *
     * @since 0.9.71+
     */
    static class SortHeaderContext {
        final HttpServletRequest req;
        /** Link path prefix: contextPath with trailing '/'. */
        final String pathPrefix;
        final String currentSort;
        final String filterParam;
        final String filterQuery;
        final boolean showSort;

        SortHeaderContext(HttpServletRequest req, String contextPath, String currentSort,
                          String filterParam, String filterQuery, boolean showSort) {
            this.req = req;
            this.pathPrefix = contextPath + '/';
            this.currentSort = currentSort;
            this.filterParam = filterParam;
            this.filterQuery = filterQuery;
            this.showSort = showSort;
        }
    }

    /**
     * Peer/rate activity flags scanned across one rendered page slice; drives
     * which optional header cells render. Package-visible for testing.
     *
     * @since 0.9.71+
     */
    static class TorrentActivityScan {
        final boolean hasPeers;
        final boolean isDownloading;
        final boolean isUploading;

        TorrentActivityScan(boolean hasPeers, boolean isDownloading, boolean isUploading) {
            this.hasPeers = hasPeers;
            this.isDownloading = isDownloading;
            this.isUploading = isUploading;
        }
    }

    /**
     * Scans the page slice for peer/rate activity, exiting early once every
     * flag is set. Extracted from appendSnarkHeader; write-only counters that
     * were computed here previously are gone.
     *
     * @param snarks full sorted list
     * @param start first row of the rendered slice
     * @param pageSize rows per page
     * @param total list size bounding the slice
     * @return flags for the slice, never null
     * @since 0.9.71+
     */
    private TorrentActivityScan scanTorrentActivity(List<Snark> snarks, int start, int pageSize, int total) {
        boolean hasPeers = false;
        boolean isDownloading = false;
        boolean isUploading = false;
        int end = Math.min(start + pageSize, total);
        for (int i = start; i < end && !(hasPeers && isDownloading && isUploading); i++) {
            Snark s = snarks.get(i);
            if (s.getPeerCount() > 0) {
                hasPeers = true;
                if (s.getDownloadRate() > 0) {isDownloading = true;}
                if (s.getUploadRate() > 0) {isUploading = true;}
            }
        }
        return new TorrentActivityScan(hasPeers, isDownloading, isUploading);
    }

    /**
     * Next value of the Status column sort cycle: toggles status asc/desc,
     * and in multi-destination mode continues into status+pool asc/desc
     * before returning to status desc. Unknown keys restart at desc.
     *
     * @param currentSort current sort key, may be null
     * @param poolSort true when multi-destination mode is active
     * @return the next sort key, never null
     * @since 0.9.71+
     */
    static String nextStatusSort(String currentSort, boolean poolSort) {
        if ("-2".equals(currentSort)) {return "2";}
        if ("2".equals(currentSort)) {return poolSort ? "13" : "-2";}
        if (poolSort && "13".equals(currentSort)) {return "-13";}
        return "-2";
    }

    /**
     * Name/type ladder for the torrent column. Unlike the directory-page
     * cycle ({@link #nextNameTypeSort(String)}) the fallback after foreign
     * keys restarts at name ascending ("1"), not unsorted - both behaviors
     * are pinned by tests.
     *
     * @param currentSort current sort key, may be null
     * @return the next sort key, never null
     * @since 0.9.71+
     */
    static String nextTorrentNameTypeSort(String currentSort) {
        if (currentSort == null || "0".equals(currentSort) || "1".equals(currentSort)) {return "-1";}
        if ("-1".equals(currentSort)) {return "12";}
        if ("12".equals(currentSort)) {return "-12";}
        return "1";
    }

    /**
     * Next value of the RX column cycle: download total and rate alternate
     * asc/desc across four states; unknown keys restart at "-5".
     *
     * @param currentSort current sort key, may be null
     * @return the next sort key, never null
     * @since 0.9.71+
     */
    static String nextRXSort(String currentSort) {
        if ("-5".equals(currentSort)) {return "5";}
        if ("5".equals(currentSort)) {return "-6";}
        if ("-6".equals(currentSort)) {return "6";}
        if ("6".equals(currentSort)) {return "-5";}
        return "-5";
    }

    /**
     * Next value of the TX column cycle: upload total and share ratio
     * alternate asc/desc across four states; unknown keys restart at "-7".
     *
     * @param currentSort current sort key, may be null
     * @return the next sort key, never null
     * @since 0.9.71+
     */
    static String nextTXSort(String currentSort) {
        if ("-7".equals(currentSort)) {return "7";}
        if ("7".equals(currentSort)) {return "-11";}
        if ("-11".equals(currentSort)) {return "11";}
        if ("11".equals(currentSort)) {return "-7";}
        return "-7";
    }

    /**
     * Status column: sortable always; pool-aware via {@link #nextStatusSort}.
     *
     * @since 0.9.71+
     */
    private void appendStatusHeader(StringBuilder buf, SortHeaderContext hc, boolean poolSort, String txtStatus) {
        String nextSort = nextStatusSort(hc.currentSort, poolSort);
        boolean isStatusSort = "2".equals(hc.currentSort) || "-2".equals(hc.currentSort)
                               || (poolSort && ("13".equals(hc.currentSort) || "-13".equals(hc.currentSort)));
        boolean isStatusDesc = "-2".equals(hc.currentSort) || (poolSort && "-13".equals(hc.currentSort));
        appendSortHeader(buf, hc, nextSort, "status", "status", txtStatus, hc.showSort, isStatusSort, isStatusDesc);
    }

    /**
     * Peer-count column: renders the show/hide peers toggle when connected
     * with peers present; empty cell otherwise.
     *
     * @since 0.9.71+
     */
    private void appendPeerToggleHeader(StringBuilder buf, SortHeaderContext hc, String peerParam,
                                        boolean isConnected, boolean noSnarks, TorrentActivityScan scan) {
        buf.append("<th class=peerCount>");
        if (isConnected && !noSnarks && scan.hasPeers) {
            boolean showPeers = peerParam != null;
            String qs = showPeers ? getQueryString(hc.req, "", null, null, null)
                                  : getQueryString(hc.req, "1", null, null, null);
            String link = buildLink(hc.pathPrefix, qs, hc.filterQuery);
            String tx = showPeers ? _t("Hide Peers") : _t("Show Peers");
            String img = showPeers ? "hidepeers" : "showpeers";
            String filterPrefix = showPeers ? "?filter=" : "&filter=";
            // Adjust filter prefix within link if needed
            if (link.contains("filter=")) {
                int index = link.indexOf("filter=");
                link = link.substring(0, index) + link.substring(index).replaceFirst("filter=", filterPrefix);
            } else {
                link += filterPrefix;
            }
            buf.append("<a class=\"sorter ").append(showPeers ? "hidePeers" : "showPeers")
               .append("\" href=\"").append(link).append("\">");
            appendIcon(buf, img, tx, tx, true, false);
            buf.append("</a>\n");
        }
        buf.append("</th>");
    }

    /**
     * Torrent name/type column: cycles via {@link #nextTorrentNameTypeSort};
     * active-state detection mirrors the five name/type keys.
     *
     * @since 0.9.71+
     */
    private void appendNameTypeHeader(StringBuilder buf, SortHeaderContext hc, String txtTorrent) {
        String nextSort = null;
        if (hc.showSort) {
            nextSort = nextTorrentNameTypeSort(hc.currentSort);
        }
        boolean isTorrentSortActive = "1".equals(hc.currentSort) || "0".equals(hc.currentSort) || "-1".equals(hc.currentSort)
                                      || "12".equals(hc.currentSort) || "-12".equals(hc.currentSort);
        boolean isTorrentSortDesc = hc.currentSort != null && hc.currentSort.startsWith("-");
        appendSortHeader(buf, hc, nextSort, "torrentSort", "torrent", txtTorrent,
                         hc.showSort, isTorrentSortActive, isTorrentSortDesc);
    }

    /**
     * ETA column: sortable only while something on the page is downloading.
     *
     * @since 0.9.71+
     */
    private void appendEtaHeader(StringBuilder buf, SortHeaderContext hc, String txtETA,
                                 boolean isConnected, boolean noSnarks, TorrentActivityScan scan) {
        if (!(isConnected && !noSnarks && scan.isDownloading)) {
            buf.append("<th class=ETA></th>");
            return;
        }
        String nextSort = null;
        if (hc.showSort) {
            if (hc.currentSort == null || "-4".equals(hc.currentSort)) {
                nextSort = "4";
            } else if ("4".equals(hc.currentSort)) {
                nextSort = "-4";
            }
        }
        boolean isETAActive = "4".equals(hc.currentSort) || "-4".equals(hc.currentSort);
        boolean isETADesc = "-4".equals(hc.currentSort);
        appendSortHeader(buf, hc, nextSort, "ETA", "eta", txtETA, hc.showSort, isETAActive, isETADesc);
    }

    /**
     * RX column: four-state cycle via {@link #nextRXSort} whenever the list
     * is sortable; empty cell otherwise.
     *
     * @since 0.9.71+
     */
    private void appendRxHeader(StringBuilder buf, SortHeaderContext hc, String txtRX, boolean noSnarks) {
        if (noSnarks || !hc.showSort) {
            buf.append("<th class=rxd></th>");
            return;
        }
        String nextSort = nextRXSort(hc.currentSort);
        boolean isRXActive = "-5".equals(hc.currentSort) || "5".equals(hc.currentSort) || "-6".equals(hc.currentSort) || "6".equals(hc.currentSort);
        boolean isRXDesc = "-5".equals(hc.currentSort) || "-6".equals(hc.currentSort);
        appendSortHeader(buf, hc, nextSort, "rxd", "head_rx", txtRX, hc.showSort, isRXActive, isRXDesc);
    }

    /**
     * Download-rate column: sortable only while downloading; direction icon
     * reflects the current key.
     *
     * @since 0.9.71+
     */
    private void appendRateDownHeader(StringBuilder buf, SortHeaderContext hc, String txtRXRate, String txtRX,
                                      String peerParam, boolean isConnected, boolean noSnarks,
                                      TorrentActivityScan scan) {
        if (!(isConnected && !noSnarks && scan.isDownloading)) {
            buf.append("<th class=rateDown></th>");
            return;
        }
        String nextSort = "-8".equals(hc.currentSort) ? "8" : "-8";
        boolean desc8 = "8".equals(hc.currentSort);
        // Determine peerFlag for getQueryString
        String peerFlag = (peerParam != null) ? "1" : "0";
        buf.append("<th class=rateDown><span class=sortIcon>");
        if (desc8) {
            buf.append("<span class=descending></span>");
        } else {
            buf.append("<span class=ascending></span>");
        }
        buf.append("<a class=sorter href=\"")
           .append(buildLink(hc.pathPrefix, getQueryString(hc.req, peerFlag, nextSort, hc.filterParam, null), hc.filterQuery))
           .append("\">");
        appendIcon(buf, "head_rx", txtRXRate, hc.showSort ? _t("Sort by {0}", txtRX) : "", true, false);
        buf.append("</a></span></th>");
    }

    /**
     * TX column: four-state cycle via {@link #nextTXSort} whenever the list
     * is sortable; empty cell otherwise.
     *
     * @since 0.9.71+
     */
    private void appendTxHeader(StringBuilder buf, SortHeaderContext hc, String txtTX) {
        if (!hc.showSort) {
            buf.append("<th class=txd></th>");
            return;
        }
        String nextSort = nextTXSort(hc.currentSort);
        boolean isTXActive = "-7".equals(hc.currentSort) || "7".equals(hc.currentSort) || "-11".equals(hc.currentSort) || "11".equals(hc.currentSort);
        boolean isTXDesc = "-7".equals(hc.currentSort) || "-11".equals(hc.currentSort);
        appendSortHeader(buf, hc, nextSort, "txd", "head_tx", txtTX, hc.showSort, isTXActive, isTXDesc);
    }

    /**
     * Upload-rate column: sortable only while uploading; ascending icon when
     * currently on "9" (note the inverted default versus rateDown).
     *
     * @since 0.9.71+
     */
    private void appendRateUpHeader(StringBuilder buf, SortHeaderContext hc, String txtTXRate,
                                    boolean isConnected, boolean noSnarks, TorrentActivityScan scan) {
        if (!(isConnected && !noSnarks && scan.isUploading)) {
            buf.append("<th class=rateUp></th>");
            return;
        }
        String nextSort = "-9".equals(hc.currentSort) ? "9" : "-9";
        boolean ascendingRateUp = "9".equals(hc.currentSort);
        buf.append("<th class=rateUp><span class=sortIcon>");
        if (ascendingRateUp) {
            buf.append("<span class=ascending></span>");
        } else {
            buf.append("<span class=descending></span>");
        }
        buf.append("<a class=sorter href=\"")
           .append(buildLink(hc.pathPrefix, getQueryString(hc.req, null, null, nextSort, null), hc.filterQuery))
           .append("\">");
        appendIcon(buf, "head_txspeed", txtTXRate, hc.showSort ? _t("Sort by {0}", _t("Up Rate")) : "", true, false);
        buf.append("</a></span></th>");
    }

    /**
     * Action column: Stop All always when connected; Start All variants
     * depending on whether any listed torrent is stopped.
     *
     * @since 0.9.71+
     */
    private void appendActionsHeader(StringBuilder buf, List<Snark> snarks, boolean isConnected, boolean noSnarks,
                                     String txtStartAll, String txtStopAll, String txtStopAllTitle,
                                     String txtStartAllTitle, String txtStartStoppedTitle) {
        buf.append("<th class=tAction>");
        if (isConnected && !noSnarks) {
            buf.append("<input type=submit id=doStopAll name=do_StopAll value=\"").append(txtStopAll)
               .append("\" title=\"").append(txtStopAllTitle).append("\">");
            for (Snark s : snarks) {
                if (s.isStopped()) {
                    buf.append("<input type=submit id=doStartAll name=do_StartAll value=\"")
                       .append(txtStartAll).append("\" title=\"").append(txtStartStoppedTitle).append("\">");
                    break;
                }
            }
        } else if (!noSnarks) {
            boolean disableStartAll = _manager.util().isConnecting();
            buf.append("<input type=submit id=doStartAll name=do_StartAll value=\"")
               .append(txtStartAll).append("\" title=\"").append(txtStartAllTitle).append("\"")
               .append(disableStartAll ? " disabled" : "").append(">");
        }
        buf.append("</th>");
    }

    /**
     * Appends a sortable table header cell including sort icons and links.
     * Shows ascending or descending icon only if this header matches current sorting.
     *
     * @param buf the StringBuilder used to append HTML content
     * @param hc shared request-derived inputs (request, path, sort state, query parts)
     * @param newSort the sort parameter value to link to for toggling sorting
     * @param cssClass CSS class to apply to the <th> element
     * @param iconName icon identifier for rendering
     * @param title localized title text for the header cell
     * @param showSort if true, render sorting link and icons; otherwise render plain header
     * @param currentSortMatches true if this header corresponds to the current sort parameter (active sorted column)
     * @param isDescending true if the current sorting direction for this header is descending
     */
    private void appendSortHeader(StringBuilder buf, SortHeaderContext hc,
                                  String newSort, String cssClass, String iconName, String title,
                                  boolean showSort, boolean currentSortMatches, boolean isDescending) {
        if (!showSort) {
            buf.append("<th class=").append(cssClass).append(">");
            appendIcon(buf, iconName, title, "", true, false);
            buf.append("</th>");
            return;
        }

        buf.append("<th class=").append(cssClass).append("><span class=sortIcon>");

        // Render icon only if this header is currently sorted
        if (currentSortMatches) {
            if (isDescending) {
                buf.append("<span class=descending></span>");
            } else {
                buf.append("<span class=ascending></span>");
            }
        }

        buf.append("<a class=sorter href=\"")
           .append(buildLink(hc.pathPrefix, getQueryString(hc.req, null, null, newSort, null), hc.filterQuery))
           .append("\">");

        appendIcon(buf, iconName, title, _t("Sort by {0}", title), true, false);
        buf.append("</a></span></th>");
    }

    /**
     * Whether multi-destination mode is active: enabled in the config with
     * at least one active destination. Gates everything pool-related -
     * the per-row destination badges, status+pool sorting, and the clamp
     * that keeps StatusPoolComparator out of single-dest requests.
     *
     * @return true when multi-dest mode is in use
     */
    boolean multiDestActive() {
        return _manager.util().getMultiDest() && _manager.util().getTorrentDests().size() >= 1;
    }


    /**
     * Aggregated footer state, replacing a twelve-parameter signature.
     * Package-visible for testing.
     * @since 0.9.71+
     */
    static class FooterContext {
        final long[] stats;
        final long totalETA;
        final int total;
        final boolean isConnected;
        final boolean noSnarks;
        final boolean hasPeers;
        final boolean isUploading;
        final DHT dht;
        final boolean standalone;
        final String peerParam;
        FooterContext(long[] stats, long totalETA, int total, boolean isConnected,
                      boolean noSnarks, boolean hasPeers, boolean isUploading,
                      DHT dht, boolean standalone, String peerParam) {
            this.stats=stats;this.totalETA=totalETA;this.total=total;this.isConnected=isConnected;
            this.noSnarks=noSnarks;this.hasPeers=hasPeers;this.isUploading=isUploading;
            this.dht=dht;this.standalone=standalone;this.peerParam=peerParam;
        }
    }

    /**
     * Appends the footer section for the torrent list table, displaying overall statistics,
     * counters, and status indicators with associated icons.
     *
     * If the manager is in a connecting state, outputs a placeholder footer indicating initialization.
     * Otherwise, outputs detailed stats including disk usage, torrent count, file size, peer counts,
     * active downloads/uploads, tunnel counts (if applicable), and connection speed metrics.
     *
     * @param out          the PrintWriter to write HTML output to
     * @param buf          a reusable StringBuilder used for generating icon HTML snippets
     * @param stats        a long array of cumulative stats: [downloaded, uploaded, download rate, upload rate, peers, total size]
     * @param totalETA     total estimated download time for all torrents, seconds
     * @param total        total number of torrents
     * @param isConnected  whether the system is connected
     * @param noSnarks     true if no torrents are loaded (empty list)
     * @param hasPeers     true if there are any connected peers
     * @param isUploading  true if there is active uploading
     * @param dht          the DHT instance providing additional peer info (may be null)
     * @param isStandalone true if running in standalone mode (tunnel info omitted if standalone)
     * @param peerParam    the peer parameter from the request query, affects debug mode toggle links
     * @throws IOException if writing to the output stream fails
     */
    private void appendSnarkFooter(PrintWriter out, StringBuilder buf, FooterContext fc) throws IOException {

        final boolean connecting = _manager.util().isConnecting();

        // Cache constant localized strings that are reused
        final String titleTotalSize = _t("Total size of loaded torrents");
        final String titleConnectedPeers = ngettext("1 connected peer", "{0} peer connections", (int) fc.stats[STAT_PEERS]);
        final String titleActiveDownloads = _t("Active downloads");
        final String titleActiveUploads = _t("Active uploads");
        final String titleInboundTunnels = _t("Active Inbound tunnels");
        final String titleOutboundTunnels = _t("Active Outbound tunnels");
        final String titleEstimatedDownload = _t("Estimated download time for all torrents");
        final String titleDataDownloaded = _t("Data downloaded this session");
        final String titleTotalDownloadSpeed = _t("Total download speed");
        final String titleTotalUploaded = _t("Total data uploaded (for listed torrents)");
        final String titleTotalUploadSpeed = _t("Total upload speed");
        final String toggleDebug = _t("Toggle Debug Panel");
        final String debugModeText = _t("Debug Mode");
        final String normalModeText = _t("Normal Mode");

        out.write("<tfoot id=snarkFoot");
        if (connecting) {out.write(" class=initializing");}
        out.write("><tr class=volatile><th id=torrentTotals class=left colspan=6><span id=totals>");
        out.write(_manager.getDiskUsage());

        // Torrent count span
        buf.setLength(0);
        buf.append("<span id=torrentCount class=counter title=\"");
        buf.append(ngettext("1 torrent", "{0} torrents", fc.total));
        buf.append("\">");
        appendIcon(buf, "torrent", "", "", true, false);
        buf.append("<span class=badge>");
        buf.append(fc.total);
        buf.append("</span></span>");
        out.write(buf.toString());

        // Filesize span
        buf.setLength(0);
        buf.append("<span id=torrentFilesize class=counter title=\"");
        buf.append(titleTotalSize);
        buf.append("\">");
        appendIcon(buf, "size", "", "", true, false);
        buf.append("<span class=badge>");
        buf.append(DataHelper.formatSize2(fc.stats[STAT_TOTAL_SIZE]).replace("i", ""));
        buf.append("</span></span>");
        out.write(buf.toString());

        // Peer count span
        buf.setLength(0);
        buf.append("<span id=peerCount class=counter title=\"");
        buf.append(titleConnectedPeers);
        buf.append("\">");
        appendIcon(buf, "showpeers", "", "", true, false);
        buf.append("<span class=badge>");
        buf.append((int) fc.stats[STAT_PEERS]);
        buf.append("</span></span>");
        out.write(buf.toString());

        ArrayList<Snark> snarks = new ArrayList<>(_manager.getTorrents());

        // actively downloading
        int downloads = 0;
        int start = 0;

        for (int i = start; i < snarks.size(); i++) {
            if ((snarks.get(i).getPeerCount() >= 1) && (snarks.get(i).getDownloadRate() > 0)) {downloads++;}
        }

        // RX count span
        buf.setLength(0);
        buf.append("<span id=rxCount class=counter title=\"");
        buf.append(titleActiveDownloads);
        buf.append("\">");
        appendIcon(buf, "head_rx", "", "", true, false);
        buf.append("<span class=badge>");
        buf.append(downloads);
        buf.append("</span></span>");
        out.write(buf.toString());

        // actively uploading
        int uploads = 0;
        for (int i = start; i < snarks.size(); i++) {
            if ((snarks.get(i).getPeerCount() >= 1) && (snarks.get(i).getUploadRate() > 0)) {uploads++;}
        }

        // TX count span
        buf.setLength(0);
        buf.append("<span id=txCount class=counter title=\"");
        buf.append(titleActiveUploads);
        buf.append("\">");
        appendIcon(buf, "head_tx", "", "", true, false);
        buf.append("<span class=badge>");
        buf.append(uploads);
        buf.append("</span></span>");
        out.write(buf.toString());

        // Tunnel counters, computed from our own sessions so they also work in fc.standalone
        int[] tnl = _manager.util().getTunnelCounts();
        String[][] counters = {
            {"tnlInCount", "inbound", titleInboundTunnels, Integer.toString(tnl[0])},
            {"tnlOutCount", "outbound", titleOutboundTunnels, Integer.toString(tnl[1])}
        };
        for (String[] counter : counters) {
            buf.setLength(0);
            buf.append("<span id=").append(counter[0]).append(" class=counter title=\"");
            buf.append(counter[2]);
            buf.append("\">");
            appendIcon(buf, counter[1], "", "", true, true);
            buf.append("<span class=badge>").append(counter[3]).append("</span></span>");
            out.write(buf.toString());
        }

        out.write("</span></th>");

        if (fc.isConnected && fc.total > 0) {
            out.write("<th class=ETA>");
            if (!fc.noSnarks && fc.hasPeers && fc.totalETA > 0) {
                out.write("<span title=\"");
                out.write(titleEstimatedDownload);
                out.write("\">");
                out.write(DataHelper.formatDuration2(Math.max(fc.totalETA, 10) * 1000));
                out.write("</span>");
            }
            out.write("</th><th class=rxd title=\"");
            out.write(titleDataDownloaded);
            out.write("\">");
            if (fc.stats[STAT_DOWNLOADED] > 0) out.write(formatSize(fc.stats[STAT_DOWNLOADED]).replace("iB", ""));
            out.write("</th><th class=rateDown title=\"");
            out.write(titleTotalDownloadSpeed);
            out.write("\">");
            if (fc.stats[STAT_DOWNLOAD_RATE] > 0) out.write(formatSize(fc.stats[STAT_DOWNLOAD_RATE]).replace("iB", "") + "/s");
            out.write("</th><th class=txd title=\"");
            out.write(titleTotalUploaded);
            out.write("\">");
            if (fc.stats[STAT_UPLOADED] > 0) out.write(formatSize(fc.stats[STAT_UPLOADED]).replace("iB", ""));
            out.write("</th><th class=rateUp title=\"");
            out.write(titleTotalUploadSpeed);
            out.write("\">");
            if (fc.stats[STAT_UPLOAD_RATE] > 0 && fc.isUploading) out.write(formatSize(fc.stats[STAT_UPLOAD_RATE]).replace("iB", "") + "/s");
            out.write("</th><th class=tAction>");

            if (fc.dht != null && !"2".equals(fc.peerParam)) {
                out.write("<a id=debugMode href=\"?p=2\" title=\"" + toggleDebug + "\">" + debugModeText + "</a>");
            } else if (fc.dht != null) {
                out.write("<a id=debugMode href=\"?p\" title=\"" + toggleDebug + "\">" + normalModeText + "</a>");
            }

            out.write("</th>");
        } else {
            out.write("<th colspan=6></th>");
        }
        out.write("</tr>");
        appendDebugRow(out, fc.dht, fc.peerParam);
        out.write("</tfoot>");
    }

    /**
     * Renders the DHT debug row as the second row of the snark footer, hidden unless
     * debug mode (?p=2) is active. Kept inside the single #snarkFoot tfoot so browsers
     * lay it out below the totals row, as multiple tfoot elements render out of order.
     *
     * @param out target for the HTML
     * @param dht the DHT, or null if not running
     * @param peerParam the query parameter
     */
    private void appendDebugRow(PrintWriter out, DHT dht, String peerParam) throws IOException {
        out.write("\n<tr id=dhtDebug");
        if (!"2".equals(peerParam)) {out.write(" hidden");}
        out.write("><th colspan=12><div class=volatile>");
        if (dht != null) {out.write(_manager.getBandwidthListener().toString() + dht.renderStatusHTML());}
        else {out.write("<b id=noDHTpeers>" + _t("No DHT Peers") + "</b>");}
        out.write("</div></th></tr>");
    }

    /**
     * Composes a link URL from a path, a servlet-generated query string, and
     * extra query text, choosing the '?'/'&amp;' separators by inspection
     * rather than by caller-supplied guesses about the raw request.
     *
     * Robust against every historical producer form: qs may begin with '?'
     * (getQueryString contract), be bare ("k=v"), or be empty; extra may be
     * empty or carry multiple "&amp;"-joined pairs without a leading mark.
     * Replaces six hand-rolled joins whose separator logic silently broke
     * sort/filter persistence on parameterless page loads.
     *
     * @param path leading URL path including trailing '/'
     * @param qs query portion from getQueryString(): '', 'k=v' or '?k=v...'
     * @param extra additional pairs joined by '&amp;', no leading mark, may be empty
     * @return composed URL, never null
     * @since 0.9.71+
     */
    static String buildLink(String path, String qs, String extra) {
        StringBuilder buf = new StringBuilder(path.length() + qs.length() + extra.length() + 2);
        buf.append(path);
        if (!qs.isEmpty()) {
            if (qs.charAt(0) != '?') {buf.append('?');}
            buf.append(qs);
            if (!extra.isEmpty()) {buf.append('&');}
        } else {
            buf.append('?');
        }
        buf.append(extra);
        return buf.toString();
    }

    /**
     * Renders the torrent filter bar with links for filtering and searching torrents.
     * Constructs URLs preserving existing query parameters except the current filter,
     * then appends updated filter criteria. Displays badges with counts for some filters.
     *
     * @param out  the PrintWriter to write the generated HTML output
     * @param req  the current HttpServletRequest providing query parameters and URL
     * @throws IOException if an I/O error occurs during writing to the output stream
     */
    private void renderFilterBar(PrintWriter out, HttpServletRequest req) throws IOException {
        String filter = req.getParameter("filter");
        filter = filter != null ? filter : "";
        String peerParam = req.getParameter("p");
        String psize = req.getParameter("ps");
        String search = req.getParameter("search");
        String srt = normalizeSortParam(req.getParameter("sort"));
        String reqURL = req.getRequestURL().toString();

        int pageSizeConf = _manager.getPageSize();
        List<Snark> snarks = getSortedSnarks(req);
        int total = snarks.size();
        int maxPageSize = Math.max(pageSizeConf, 10);
        boolean searchActive = (search != null && !search.isEmpty());

        StringBuilder activeQuery = new StringBuilder("/i2psnark/?");
        if (peerParam != null) activeQuery.append("p=").append(peerParam).append("&");
        if (srt != null) activeQuery.append("sort=").append(srt).append("&");
        if (searchActive) activeQuery.append("search=").append(search).append("&");
        if (psize != null) activeQuery.append("ps=").append(psize).append("&");

        // Remove existing filter parameter from activeQuery if present
        String existingFilter = "filter=" + filter;
        int filterIndex = activeQuery.indexOf(existingFilter);
        if (filterIndex >= 0) {
            activeQuery.delete(filterIndex, filterIndex + existingFilter.length());
            // Remove trailing '&' that may remain after deletion
            if (filterIndex < activeQuery.length() && activeQuery.charAt(filterIndex) == '&') {
                activeQuery.deleteCharAt(filterIndex);
            }
        }

        // Trim trailing '&' if present
        if (activeQuery.length() > 0 && activeQuery.charAt(activeQuery.length() - 1) == '&') {
            activeQuery.setLength(activeQuery.length() - 1);
        }

        String buttonUrl = activeQuery.toString();
        if (buttonUrl.endsWith("?")) {buttonUrl += "filter=";}
        else {buttonUrl += "&filter=";}

        final String badge = "<span class=badge></span>";

        String allBadgeText = null;
        if (!searchActive) {
            allBadgeText = (maxPageSize < total) ? (maxPageSize + " / " + total) : String.valueOf(total);
        }

        StringBuilder buf = new StringBuilder(1280);
        buf.append("<form id=torrentlist action=_post method=POST target=processForm>\n<div id=filterBar>")
           .append(buildFilterLink(buttonUrl, "search", searchActive, _t("Search"),
               searchActive ? (searchResults > maxPageSize ? maxPageSize + " / " + searchResults : String.valueOf(searchResults)) : null))
           .append(buildFilterLink(buttonUrl, "all", !searchActive, _t("Show All"), allBadgeText))
           .append(buildSimpleFilterLink(buttonUrl, "active", _t("Active"), badge))
           .append(buildSimpleFilterLink(buttonUrl, "inactive", _t("Inactive"), badge))
           .append(buildSimpleFilterLink(buttonUrl, "connected", _t("Connected"), badge))
           .append(buildSimpleFilterLink(buttonUrl, "downloading", _t("Downloading"), badge))
           .append(buildSimpleFilterLink(buttonUrl, "seeding", _t("Seeding"), badge))
           .append(buildSimpleFilterLink(buttonUrl, "complete", _t("Complete"), badge))
           .append(buildSimpleFilterLink(buttonUrl, "incomplete", _t("Incomplete"), badge))
           .append(buildSimpleFilterLink(buttonUrl, "stopped", _t("Stopped"), badge))
           .append("</div>\n");
        if (!reqURL.contains("/.ajax")) {
            buf.append("<script src=/i2psnark/.res/js/filterBar.js type=module></script>\n");
        }
        out.append(buf);
        buf.setLength(0);
    }

    /**
     * Builds an HTML filter link anchor element with an optional badge and visibility toggle.
     *
     * @param baseUrl   the base URL to which the filter id is appended as a parameter
     * @param filterId  the id and filter name used in the link
     * @param visible   whether the link should be visible or hidden (via CSS)
     * @param title     the display text for the filter link
     * @param badgeText optional badge content to display inside the link
     * @return          the constructed HTML string of the filter link
     */
    @SuppressWarnings("PMD.AvoidUnnecessaryStringBuilderCreation")
    private String buildFilterLink(String baseUrl, String filterId, boolean visible, String title, String badgeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("<a class=filter id=").append(filterId).append(" href=\"").append(baseUrl).append(filterId).append("\"");
        if (!visible) sb.append(" hidden");
        sb.append("><span>").append(title)
          .append("<span class=badge");
        if (!visible) sb.append(" hidden");
        sb.append(">");
        if (badgeText != null) sb.append(badgeText);
        sb.append("</span></span></a>");
        return sb.toString();
    }

    /**
     * Builds a simple HTML filter link anchor element with a badge but no visibility toggle.
     *
     * @param baseUrl  the base URL to which the filter id is appended as a parameter
     * @param filterId the id and filter name used in the link
     * @param title    the display text for the filter link
     * @param badge    the badge HTML to append inside the link
     * @return         the constructed HTML string of the filter link
     */
    private String buildSimpleFilterLink(String baseUrl, String filterId, String title, String badge) {
        return "<a class=filter id=" + filterId + " href=\"" + baseUrl + filterId + "\"><span>" + title + badge + "</span></a>";
    }

    /**
     *  Search torrents for matching terms
     *
     *  @param search non-null
     *  @param snarks unmodified
     *  @return null if no valid search, or matching torrents in same order, empty if no match
     *  @since 0.9.58
     */
    private static List<Snark> search(String search, Collection<Snark> snarks) {
        List<String> searchList = null;
        String[] terms = DataHelper.split(search, " ");
        for (int i = 0; i < terms.length; i++) {
            String term = terms[i];
            if (!term.isEmpty()) {
                if (searchList == null) {searchList = new ArrayList<>(4);}
                searchList.add(Normalizer.normalize(term.toLowerCase(Locale.US), Normalizer.Form.NFKD));
            }
        }
        if (searchList == null || searchList.isEmpty()) {return new ArrayList<>(0);} // empty list
        List<Snark> matches = new ArrayList<>(32);
        loop:
        for (Snark snark : snarks) {
            String lcname = Normalizer.normalize(snark.getBaseName().toLowerCase(Locale.US), Normalizer.Form.NFKD);
            for (int j = 0; j < searchList.size(); j++) {
                String term = searchList.get(j);
                // search for all terms (AND)
                if (!lcname.contains(term)) {continue loop;}
            }
            matches.add(snark);
        }
        return matches;
    }

    /**
     * Hidden inputs for the nonce and parameters p, st, and sort.
     *
     * @param out writes to it
     * @param action if non-null, add it as the action
     * @since 0.9.16
     */
    void writeHiddenInputs(PrintWriter out, HttpServletRequest req, String action) {
        StringBuilder buf = new StringBuilder(256);
        writeHiddenInputs(buf, req, action);
        out.append(buf);
    }

    /**
     * Hidden inputs for the nonce and parameters p, st, and sort.
     * Emitted in a fixed order; absent request values are omitted.
     *
     * @param buf appends to it
     * @param action if non-null, add it as the action
     * @since 0.9.16
     */
    void writeHiddenInputs(StringBuilder buf, HttpServletRequest req, String action) {
        appendHiddenInput(buf, "nonce", String.valueOf(getNonce()), false);
        String p = req.getParameter("p");
        if (p != null) {appendHiddenInput(buf, "p", DataHelper.stripHTML(p), false);}
        String st = req.getParameter("st");
        if (st != null) {appendHiddenInput(buf, "st", DataHelper.stripHTML(st), false);}
        String sort = normalizeSortParam(req.getParameter("sort"));
        if (sort != null) {appendHiddenInput(buf, "sort", DataHelper.stripHTML(sort), false);}
        if (action != null) {appendHiddenInput(buf, "action", DataHelper.stripHTML(action), false);}
        // for buttons, keep the search term readable rather than stripped
        String search = req.getParameter("search");
        if (search != null) {appendHiddenInput(buf, "search", DataHelper.escapeHTML(search), true);}
        buf.append('\n');
    }

    /**
     * Append a single hidden form input.
     *
     * @param esc true to HTML-escape the value (free-text fields such as
     *            search), false to strip HTML (numeric and enum fields)
     */
    private static void appendHiddenInput(StringBuilder buf, String name, String value, boolean esc) {
        buf.append("<input type=hidden name=").append(name).append(" value=\"")
           .append(esc ? DataHelper.escapeHTML(value) : DataHelper.stripHTML(value))
           .append("\">");
    }

    /**
     *  Normalize a human-readable sort parameter to its legacy numeric form,
     *  e.g. "pool-desc" to "-13", so readable URLs survive header cycling,
     *  hidden inputs and query-string rebuilds. Numeric and unrecognized
     *  values pass through unchanged.
     *
     *  @param sort the raw sort parameter, or null
     *  @return the normalized sort parameter, or null
     *  @since 0.9.71+
     */
    private static String normalizeSortParam(String sort) {
        if (sort == null) {
            return null;
        }
        String name = sort.toLowerCase(Locale.US);
        boolean rev = false;
        if (name.endsWith("-desc")) {
            rev = true;
            name = name.substring(0, name.length() - 5);
        } else if (name.endsWith("-asc")) {
            name = name.substring(0, name.length() - 4);
        }
        int type;
        if (name.equals("name")) {
            type = 1;
        } else if (name.equals("status")) {
            type = 2;
        } else if (name.equals("pool")) {
            // Must stay 13: getSortedSnarks() clamps sort values 13/-13 to
            // the default outside multi-dest mode, and Sorters maps them
            // to StatusPoolComparator. Changing this number breaks both.
            type = 13;
        } else if (name.equals("peers")) {
            type = 3;
        } else if (name.equals("eta")) {
            type = 4;
        } else if (name.equals("size")) {
            type = 5;
        } else if (name.equals("downloaded")) {
            type = 6;
        } else if (name.equals("uploaded")) {
            type = 7;
        } else if (name.equals("downrate") || name.equals("down-rate")) {
            type = 8;
        } else if (name.equals("uprate") || name.equals("up-rate")) {
            type = 9;
        } else if (name.equals("remaining") || name.equals("needed")) {
            type = 10;
        } else if (name.equals("ratio")) {
            type = 11;
        } else if (name.equals("type")) {
            type = 12;
        } else {
            return sort;
        }
        return String.valueOf(rev ? -type : type);
    }

    /**
     * Query string with an optional search override.
     *
     * Parameters are emitted in fixed p, sort, st, search order. Only values
     * that are exactly signed integers are emitted, so a free-text search
     * override never round-trips through these URLs.
     *
     * @param p page override, or null to inherit and sanitize from the request
     * @param st start-index override, or null to inherit and sanitize
     * @param so sort override, or null to inherit and normalize
     * @param search search override, or null to inherit and HTML-escape
     * @return the query string, beginning with '?' or empty
     * @since 0.9.58
     */
    private static String getQueryString(HttpServletRequest req, String p, String st, String so, String search) {
        StringBuilder buf = new StringBuilder(64);
        appendQueryParam(buf, req, "p", p);
        appendQueryParam(buf, req, "sort", so);
        appendQueryParam(buf, req, "st", st);
        appendQueryParam(buf, req, "search", search);
        return buf.toString();
    }

    /**
     * Append one query pair to buf if it resolves to an emittable value.
     *
     * A null override inherits the value from the request, applying the
     * per-name sanitization the legacy map-based implementation used:
     * sort is normalized, p/sort/st are HTML-stripped, search is
     * HTML-escaped. Explicit overrides are trusted as caller-provided
     * constants. Emission requires an exact signed integer, matching the
     * validation applied when such links are later parsed.
     *
     * @param buf destination buffer, prefix '?' or '&' chosen by its state
     * @param req the current request, for inherited values
     * @param name parameter name to emit
     * @param override caller-supplied value, or null to inherit
     * @since 0.9.71+
     */
    private static void appendQueryParam(StringBuilder buf, HttpServletRequest req,
                                         String name, String override) {
        String value = override;
        if (value == null) {
            value = req.getParameter(name);
            if (value != null) {
                if ("sort".equals(name)) {value = normalizeSortParam(value);}
                if ("search".equals(name)) {
                    value = DataHelper.escapeHTML(value);
                } else {
                    value = DataHelper.stripHTML(value);
                }
            }
        }
        if (RedirectQuery.isValidNumeric(value)) {
            buf.append(buf.length() > 0 ? '&' : '?').append(name).append('=').append(value);
        }
    }

    /**
     * Renders the page navigation controls with visibility handling.
     *
     * @param out the PrintWriter to write HTML output to
     * @param req the HttpServletRequest containing request parameters
     * @param start the starting index of the current page view
     * @param pageSize the number of items per page
     * @param total the total number of items available
     * @param filter the current filter parameter string
     * @param noThinsp flag indicating special rendering for certain browsers
     * @param isForm whether the page is rendering as a form (affects visibility logic)
     * @param searchActive whether a search is currently active on the page
     * @param searchLength length of the active search string
     * @throws IOException if an error occurs writing output
     */
    private void paginator(PrintWriter out, HttpServletRequest req, int start, int pageSize, int total,
                         String filter, boolean noThinsp, boolean isForm, boolean searchActive, int searchLength) throws IOException {

        req.setCharacterEncoding("UTF-8");
        boolean showNav = isForm && total > 0 && (start > 0 || total > pageSize);
        boolean navVisible = !searchActive || (searchActive && searchLength > pageSize);

        out.write("<tr id=paginate");
        if (!showNav || !navVisible) {
            out.write(" hidden");
        }
        out.write("><th colspan=12>");

        if (showNav && navVisible) {
            StringBuilder buf = new StringBuilder(1024);

            // First
            buf.append("<a href=\"").append(_contextPath)
               .append(getQueryString(req, null, "", null, null))
               .append("\"").append(start > 0 ? "" : " class=disabled")
               .append("><span id=first>");
            appendIcon(buf, "first", _t("First"), _t("First page"), true, true);
            buf.append("</span></a>");

            // Back
            int prev = Math.max(0, start - pageSize);
            buf.append("<a href=\"").append(_contextPath)
               .append(getQueryString(req, null, String.valueOf(prev), null, null))
               .append("\"").append(prev > 0 ? "" : " class=disabled")
               .append("><span id=previous>");
            appendIcon(buf, "previous", _t("Prev"), _t("Previous page"), true, true);
            buf.append("</span></a>");

            // Page count
            int pages = 1 + ((total - 1) / pageSize);
            if (pages == 1 && start > 0) { pages = 2; }
            if (pages > 1) {
                int page = (start + pageSize >= total) ? pages : (1 + (start / pageSize));
                buf.append("<span id=pagecount>").append(page).append(thinsp(noThinsp))
                   .append(pages).append("</span>");
            }

            // Next
            int next = start + pageSize;
            buf.append("<a href=\"").append(_contextPath)
               .append(getQueryString(req, null, String.valueOf(next), null, null))
               .append("\"").append(next + pageSize < total ? "" : " class=disabled")
               .append("><span id=next>");
            appendIcon(buf, "next", _t("Next"), _t("Next page"), true, true);
            buf.append("</span></a>");

            // Last
            int last = ((total - 1) / pageSize) * pageSize;
            buf.append("<a href=\"").append(_contextPath)
               .append(getQueryString(req, null, String.valueOf(last), null, null))
               .append("\"").append(start + pageSize < total ? "" : " class=disabled")
               .append("><span id=last>");
            appendIcon(buf, "last", _t("Last"), _t("Last page"), true, true);
            buf.append("</span></a>");

            out.append(buf);
            buf.setLength(0);
        }

        out.write("</th></tr>");
    }

    /**
     * Process HTTP request to handle torrent-related actions.
     * Delegates to specific handlers based on the action parameter.
     */
    private boolean processRequest(HttpServletRequest req, HttpServletResponse resp) {
        String action = extractAction(req);
        if (action == null) {return false;} // No action specified

        switch (action) {
            case "Add": handleAdd(req);
                break;
            case "Save": return handleSave(req, resp);
            case "SaveTrackers": handleSaveTrackers(req);
                break;
            case "SaveCreateFilters": handleSaveCreateFilters(req);
                break;
            case "Create": handleCreate(req);
                break;
            case "StopAll": handleStopAll(req);
                break;
            case "StartAll": handleStartAll(req);
                break;
            case "Clear": handleClearMessages(req);
                break;
            default:
                if (action.startsWith("Stop_")) {
                    handleStop(action);
                } else if (action.startsWith("Start_")) {
                    handleStart(action);
                } else if (action.startsWith("Remove_")) {
                    handleRemove(action);
                } else if (action.startsWith("Delete_")) {
                    handleDelete(action);
                } else {
                    _manager.addMessage("Unknown POST action: \"" + action + '\"');
                }
        }
        return false;
    }

    /**
     * Extracts the action parameter from the request.
     * Checks "action" parameter and fallback to keys starting with "do_".
     *
     * @param req the HTTP request
     * @return the extracted action or null if none found
     */
    private String extractAction(HttpServletRequest req) {
        String action = req.getParameter("action");
        if (action == null) {
            @SuppressWarnings("unchecked") // Safe cast since keys are Strings
            Map<String, String[]> params = req.getParameterMap();
            for (Object o : params.keySet()) {
                String key = (String) o;
                if (key.startsWith("do_")) {
                    action = key.substring(3);
                    break;
                }
            }
        }
        return action;
    }

    /**
     * Handles the "Add" action, processes file uploads or URLs to add torrents.
     *
     * @param req the HTTP request
     */
    private void handleAdd(HttpServletRequest req) {
        File dataDir = _manager.getDataDir();
        if (!dataDir.canWrite()) {
            _manager.addMessage(_t("No write permissions for data directory") + ": " + dataDir);
            return;
        }

        RequestWrapper reqw = new RequestWrapper(req);
        String newURL = reqw.getParameter("nofilter_newURL");
        String newFile = reqw.getFilename("newFile");

        if (newFile != null && !newFile.trim().isEmpty()) {
            handleAddFile(newFile.trim(), dataDir, reqw);
        } else if (newURL != null && !newURL.trim().isEmpty()) {
            handleAddURL(newURL.trim(), dataDir, reqw);
        } else {
            _manager.addMessage(_t("Enter URL or select torrent file"));
        }
    }

    /**
     * Nonce-free browser API: adds a magnet link, torrent URL, info hash, or local
     * torrent file path. POST only. Authorized when the browser API is enabled and
     * the client is loopback, in the configured allowed hosts list, or presents a
     * valid API key. Replies with a single text/plain status line.
     *
     * @param req the HTTP request
     * @param resp the HTTP response
     * @throws IOException on write failure
     * @since 0.9.71+
     */
    private void handleBrowserApiAdd(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String msg;
        if (!"POST".equals(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().println("ERR: POST required");
            return;
        }
        if (!browserApiAuthorized(req)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().println("ERR: not authorized (browser API disabled, host not allowed, or bad API key)");
            return;
        }
        // nofilter_ prefix bypasses the XSSFilter parameter whitelist, which
        // strips values containing '&' (present in every real magnet link)
        String url = req.getParameter("nofilter_newURL");
        if (url == null || url.trim().isEmpty()) {
            url = req.getParameter("nofilter_magnet");
        }
        if (url == null || url.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().println("ERR: missing newURL or magnet parameter");
            return;
        }
        url = url.trim();
        String name = browserApiName(url);
        if (name == null) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().println("ERR: unsupported URL or info hash");
            return;
        }
        File dataDir = _manager.getDataDir();
        handleAddURL(url, dataDir, new RequestWrapper(req));
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().println("OK: " + name);
    }

    /**
     * The page the browser opens when a magnet: link is handed to the I2PSnark
     * Bridge extension (registered via protocol_handlers in the extension
     * manifest). The page itself performs no server-side action; the external
     * script magnetHandler.js reads the magnet from the URL, POSTs it to the
     * browser API /_add on the same origin, dispatches a CustomEvent the
     * extension's content script forwards to the background script for a
     * browser notification, and closes the tab immediately. The page body is
     * intentionally blank so the tab the protocol handler opens is never
     * noticed; the result is only shown in the browser notification.
     *
     * <p>The magnet arrives in the URL, so the nofilter_ parameter name is
     * required: the XSS filter strips '&' from filtered parameter values, and
     * every real magnet link contains '&amp;'.
     *
     * <p>GET only; the existing /_add authorization applies to the actual add.
     *
     * @param req the HTTP request
     * @param resp the HTTP response
     * @throws IOException on write failure
     * @since 0.9.71+
     */
    private void handleMagnetPage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod())) {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "DENY");
        resp.getWriter().write(
            "<!DOCTYPE HTML>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n" +
            "<title>I2PSnark</title>\n" +
            buildBridgeMetaTag(browserApiBaseUrl(req)) +
            "</head>\n<body>\n" +
            "<div id=\"status\" style=\"display:none\"></div>\n" +
            "<script src=\"" + _contextPath + WARBASE + "js/magnetHandler.js\"></script>\n" +
            "</body>\n</html>\n");
    }

    /**
     * The meta element advertising the base URL of the I2PSnark webapp that
     * served this page, so the I2PSnark Bridge extension's content script can
     * discover the exact origin + context path (host, port, https) and route
     * subsequent magnet: handoffs to it. This is authoritative even when the
     * context path or listening port differs from the extension's manifest
     * default (e.g. standalone I2PSnark on port 8002, custom console port).
     *
     * @param baseUrl the webapp base URL, e.g. http://127.0.0.1:8002/i2psnark
     * @return a single HTML meta line, or empty string when no URL is available
     * @since 0.9.71+
     */
    public static String buildBridgeMetaTag(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {return "";}
        return "<meta name=\"i2psnark-base-url\" content=\"" + DataHelper.escapeHTML(baseUrl) + "\">\n";
    }

    /**
     * Whether the browser API request is authorized. Requires the API to be
     * enabled and the client to be loopback, on the allowed hosts list, or to
     * present the configured API key.
     */
    private boolean browserApiAuthorized(HttpServletRequest req) {
        if (!_manager.browserApiEnabled()) {return false;}
        boolean hostAllowed = _manager.isBrowserApiHost(req.getRemoteAddr());
        String key = req.getParameter("apiKey");
        String apikey = _manager.util().getAPIKey();
        return browserApiAuthorized(true, hostAllowed, key, apikey);
    }

    /**
     * Pure decision function for browser API authorization (unit-testable):
     * requires the API to be enabled and the client to be an allowed host or
     * to present the configured API key.
     *
     * @param enabled the browser API enable flag
     * @param hostAllowed whether the client address is loopback or on the allowlist
     * @param providedKey the apiKey request parameter, may be null
     * @param configuredKey the configured API key, may be null or empty
     * @return whether the request is authorized
     * @since 0.9.71+
     */
    public static boolean browserApiAuthorized(boolean enabled, boolean hostAllowed, String providedKey, String configuredKey) {
        if (!enabled) {return false;}
        if (hostAllowed) {return true;}
        return providedKey != null && configuredKey != null && !configuredKey.isEmpty()
               && DataHelper.eqCT(providedKey, configuredKey);
    }

    /**
     * Validate the browser API payload and extract a display name for the OK reply.
     * Mirrors the formats accepted by handleAddURL().
     *
     * @param url the URL, magnet, info hash, or file path
     * @return a display name, or null if unsupported
     */
    private String browserApiName(String url) {
        if (url.startsWith(MagnetURI.MAGNET) || url.startsWith(MagnetURI.MAGGOT)) {
            try {
                MagnetURI magnet = new MagnetURI(_manager.util(), url);
                String name = magnet.getName();
                return (name != null && !name.isEmpty()) ? name : url;
            } catch (IllegalArgumentException iae) {
                return null;
            }
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            if (!isI2PTracker(url)) {return null;}
            return url;
        } else if (isValidHexInfoHash(url) || isValidBase32InfoHash(url)) {
            return url.toUpperCase(Locale.US);
        } else if (isValidV2InfoHash(url)) {
            return null;
        } else if (url.endsWith(".torrent")) {
            String path = url.startsWith("file://") ? url.substring(7) : url;
            if (new File(path).isFile()) {return url;}
        }
        return null;
    }

    /**
     * Handles adding a torrent from an uploaded file.
     *
     * @param newFile filename from upload
     * @param dataDir data directory where torrents are stored
     * @param reqw wrapped request for accessing multipart inputs
     */
    private void handleAddFile(String newFile, File dataDir, RequestWrapper reqw) {
        if (!newFile.endsWith(".torrent")) {
            newFile += ".torrent";
        }
        File local = new File(dataDir, newFile);
        String filteredName = Storage.filterName(newFile);
        File localFiltered = (!newFile.equals(filteredName)) ? new File(dataDir, filteredName) : null;

        if (local.exists() || (localFiltered != null && localFiltered.exists())) {
            try {
                String canonical = local.getCanonicalPath();
                String canonicalFiltered = (localFiltered != null) ? localFiltered.getCanonicalPath() : null;
                if (_manager.getTorrent(canonical) != null ||
                    (canonicalFiltered != null && _manager.getTorrent(canonicalFiltered) != null)) {
                    String msg = _t("Torrent already running: {0}", canonical);
                    _manager.addMessageAndPrint(msg);
                } else {
                    String msg = _t("Torrent already in the queue: {0}", canonical);
                    _manager.addMessageAndPrint(msg);
                }
            } catch (IOException ioe) {
                String msg = _t("Error adding the torrent: {0}", DataHelper.escapeHTML(newFile)) +
                             ": " + DataHelper.stripHTML(ioe.getMessage());
                _manager.addMessageNoEscapeAndPrint(msg);
            }
            return;
        }

        File tmp = new File(_manager.util().getTempDir(),
                            "newTorrent-" + _manager.util().getContext().random().nextLong() + ".torrent");

        try (InputStream in = reqw.getInputStream("newFile");
             OutputStream out = new SecureFileOutputStream(tmp)) {
            DataHelper.copy(in, out);
        } catch (IOException ioe) {
            String msg = _t("Error uploading the torrent file: {0}", DataHelper.escapeHTML(newFile)) +
                         ": " + DataHelper.stripHTML(ioe.getMessage());
            _manager.addMessageNoEscapeAndPrint(msg);
            tmp.delete();
            return;
        }

        // Validate torrent and add
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(tmp))) {
            byte[] infoHash = new byte[20];
            String name = MetaInfo.getNameAndInfoHash(in, infoHash);

            Snark snark = _manager.getTorrentByInfoHash(infoHash);
            if (snark != null) {
                String msg = _t("Torrent with this info hash is already running: {0}", snark.getBaseName());
                _manager.addMessageAndPrint(msg);
                tmp.delete();
                return;
            }

            File targetFile = (localFiltered != null) ? localFiltered : local;
            String canonical = targetFile.getCanonicalPath();

            if (!_manager.copyAndAddTorrent(tmp, canonical, dataDir)) {
                throw new IOException("Unknown error - check logs");
            }

            snark = _manager.getTorrentByInfoHash(infoHash);
            if (snark != null) {
                snark.startTorrent();
            } else {
                throw new IOException("Not found after adding: " + canonical);
            }
        } catch (IOException ioe) {
            String msg = _t("Torrent at {0} was not valid", DataHelper.escapeHTML(newFile)) + ": " +
                         DataHelper.stripHTML(ioe.getMessage());
            _manager.addMessageNoEscapeAndPrint(msg);
        } catch (OutOfMemoryError oom) {
            String msg = _t("ERROR - Out of memory, cannot create torrent from {0}", DataHelper.escapeHTML(newFile)) +
                         ": " + DataHelper.stripHTML(oom.getMessage());
            _manager.addMessageNoEscapeAndPrint(msg);
        } finally {
            tmp.delete();
        }
    }

    /**
     * Handles adding a torrent from a URL or magnet link.
     *
     * @param newURL the URL or magnet link string
     * @param dataDir data directory for torrents
     * @param reqw wrapped request
     */
    private void handleAddURL(String newURL, File dataDir, RequestWrapper reqw) {
        String newDir = reqw.getParameter("nofilter_newDir");
        File dir = null;

        if (newDir != null) {
            newDir = newDir.trim();
            if (!newDir.isEmpty()) {
                dir = new SecureFile(newDir);
                if (!dir.isAbsolute()) {
                    String msg = _t("Data directory must be an absolute path") + ": " + dir;
                    _manager.addMessageAndPrint(msg);
                    return;
                }
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    String msg = _t("Data directory cannot be created") + ": " + dir;
                    _manager.addMessageAndPrint(msg);
                    return;
                }
                // Prevent nested torrents
                if (checkNestedTorrent(dir, false)) {
                    return;
                }
            }
        }

        if (newURL.startsWith("http://") || newURL.startsWith("https://")) {
            if (isI2PTracker(newURL)) {
                FetchAndAdd fetch = new FetchAndAdd(_context, _manager, newURL, dir);
                _manager.addDownloader(fetch);
            } else {
                String msg = _t("Download from non-I2P location {0} is not supported", urlify(newURL));
                _manager.addMessageNoEscapeAndPrint(msg);
            }
        } else if (newURL.startsWith(MagnetURI.MAGNET) || newURL.startsWith(MagnetURI.MAGGOT)) {
            addMagnet(newURL, dir);
        } else if (isValidHexInfoHash(newURL)) {
            addMagnet(MagnetURI.MAGNET_FULL + newURL.toUpperCase(Locale.US), dir);
        } else if (isValidBase32InfoHash(newURL)) {
            addMagnet(MagnetURI.MAGNET_FULL + newURL.toUpperCase(Locale.US), dir);
        } else if (isValidV2InfoHash(newURL)) {
            String msg = _t("Error: Version 2 info hashes are not supported");
            _manager.addMessageAndPrint(msg);
        } else {
            handleAddFromFilePath(newURL, dataDir);
        }
    }

    /**
     * Validates if a string is a valid 40-hex character info hash.
     * @return whether valid hex info hash
     * @since 0.9.71+
     */
    public static boolean isValidHexInfoHash(String s) {
        return s != null && s.length() == 40 && HEX_PATTERN.matcher(s).matches();
    }

    /**
     * Validates if a string is a valid 32-base32 character info hash.
     * @return whether valid base32 info hash
     * @since 0.9.71+
     */
    public static boolean isValidBase32InfoHash(String s) {
        return s != null && s.length() == 32 && BASE32_PATTERN.matcher(s).matches();
    }

    /**
     * Validates if string is version 2 hex multihash (68 characters starting with "1220").
     * @return whether valid v2 info hash
     * @since 0.9.71+
     */
    public static boolean isValidV2InfoHash(String s) {
        return s != null && s.length() == 68 && s.startsWith("1220") && HEX_PATTERN.matcher(s).matches();
    }

    /**
     * Handles adding a torrent from a file path.
     *
     * @param newURL file path to .torrent file
     * @param dataDir data directory
     */
    private void handleAddFromFilePath(String newURL, File dataDir) {
        if (newURL.startsWith("file://")) {
            newURL = newURL.substring(7);
        }
        File file = new File(newURL);
        if (!file.isAbsolute() || !file.exists()) {
            String msg = _t("Invalid URL: Must start with \"{0}\" or \"{1}\"", "http://", MagnetURI.MAGNET);
            _manager.addMessageAndPrint(msg);
            return;
        }
        if (!newURL.endsWith(".torrent")) {
            String msg = _t("Torrent at {0} was not valid", DataHelper.escapeHTML(newURL));
            _manager.addMessageNoEscapeAndPrint(msg);
            return;
        }

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] infoHash = new byte[20];
            String name = MetaInfo.getNameAndInfoHash(in, infoHash);

            Snark snark = _manager.getTorrentByInfoHash(infoHash);
            if (snark != null) {
                String msg = _t("Torrent with this info hash is already running: {0}", snark.getBaseName());
                _manager.addMessageAndPrint(msg);
                return;
            }

            String filteredName = Storage.filterName(name);
            File torrentFile = new File(dataDir, filteredName + ".torrent");
            String canonical = torrentFile.getCanonicalPath();

            if (torrentFile.exists()) {
                if (_manager.getTorrent(canonical) != null) {
                    String msg = _t("Torrent already running: {0}", filteredName);
                    _manager.addMessageAndPrint(msg);
                    return;
                } else {
                    String msg = _t("Torrent already in the queue: {0}", filteredName);
                    _manager.addMessageAndPrint(msg);
                    return;
                }
            } else {
                boolean ok = _manager.copyAndAddTorrent(file, canonical, dataDir);
                if (!ok) {
                    throw new IOException("Unknown error - check logs");
                }
                snark = _manager.getTorrentByInfoHash(infoHash);
                if (snark != null) {
                    snark.startTorrent();
                } else {
                    throw new IOException("Unknown error - check logs");
                }
            }
        } catch (IOException ioe) {
            String msg = _t("Torrent at {0} was not valid", DataHelper.escapeHTML(newURL)) +
                         ": " + DataHelper.stripHTML(ioe.getMessage());
            _manager.addMessageNoEscapeAndPrint(msg);
        } catch (OutOfMemoryError oom) {
            String msg = _t("ERROR - Out of memory, cannot create torrent from {0}",
                            DataHelper.escapeHTML(newURL)) + ": " + DataHelper.stripHTML(oom.getMessage());
            _manager.addMessageNoEscapeAndPrint(msg);
        }
    }

    /**
     * Resolves a short action token to its torrent by unique-prefix match over
     * the loaded torrents' base64 info-hash names. Ambiguous or unknown tokens
     * return null, making stale submissions a safe no-op.
     *
     * @param token the token extracted from a submitted control name
     * @return the matching Snark, or null when none or several match
     * @since 0.9.71+
     */
    private Snark resolveTorrentByToken(String token) {
        if (token == null || token.isEmpty()) {return null;}
        List<String> names = new ArrayList<>(_manager.getTorrents().size());
        for (Snark s : _manager.getTorrents()) {names.add(Base64.encode(s.getInfoHash()));}
        String name = ActionTokens.resolveUnique(token, names);
        if (name == null) {return null;}
        byte[] infoHash = Base64.decode(name);
        return infoHash != null && infoHash.length == 20
            ? _manager.getTorrentByInfoHash(infoHash) : null;
    }

    /**
     * Handles the "Stop_" action to stop a torrent by action token.
     *
     * @param action the action string starting with "Stop_"
     */
    private void handleStop(String action) {
        String token = action.substring(5);
        Snark snark = resolveTorrentByToken(token);
        if (snark != null) {_manager.stopTorrent(snark);}
    }

    /**
     * Handles the "Start_" action to start a torrent by action token.
     *
     * @param action the action string starting with "Start_"
     */
    private void handleStart(String action) {
        Snark snark = resolveTorrentByToken(action.substring(6));
        if (snark != null && !snark.isStopped()) {return;}
        if (snark != null) {_manager.startTorrent(snark.getInfoHash());}
    }

    /**
     * Handles the "Remove_" action to remove a torrent by action token.
     *
     * @param action the action string starting with "Remove_"
     */
    private void handleRemove(String action) {
        Snark snark = resolveTorrentByToken(action.substring(7));
        if (snark == null) {return;}
        byte[] infoHash = snark.getInfoHash();

        for (String name : _manager.listTorrentFiles()) {
            Snark snarkByFile = _manager.getTorrent(name);
            if (snarkByFile != null && DataHelper.eq(infoHash, snarkByFile.getInfoHash())) {
                MetaInfo meta = snarkByFile.getMetaInfo();
                if (meta == null) {
                    // magnet - remove and delete are the same thing
                    _manager.deleteMagnet(snarkByFile);
                    _manager.addMessage(_t("Magnet deleted: {0}", name.replace("Magnet ", "")));
                    return;
                }
                File torrentFile = new File(name);
                File dataDir = _manager.getDataDir();
                boolean canDelete = dataDir.canWrite() || !torrentFile.exists();
                _manager.stopTorrent(snarkByFile, canDelete);
                if (torrentFile.delete()) {
                    _manager.addMessage(_t("Torrent file deleted: {0}", torrentFile.getAbsolutePath()));
                } else if (torrentFile.exists()) {
                    if (!canDelete) {
                        _manager.addMessage(_t("No write permissions for data directory") + ": " + dataDir);
                    }
                    _manager.addMessage(_t("Torrent file could not be deleted: {0}", torrentFile.getAbsolutePath()));
                }
                break;
            }
        }
    }

    /**
     * Handles the "Delete_" action to delete a torrent and its data by action token.
     *
     * @param action the action string starting with "Delete_"
     */
    private void handleDelete(String action) {
        Snark snark = resolveTorrentByToken(action.substring(7));
        if (snark == null) {return;}
        byte[] infoHash = snark.getInfoHash();

        for (String name : _manager.listTorrentFiles()) {
            Snark snarkByFile = _manager.getTorrent(name);
            if (snarkByFile != null && DataHelper.eq(infoHash, snarkByFile.getInfoHash())) {
                MetaInfo meta = snarkByFile.getMetaInfo();
                if (meta == null) {
                    _manager.deleteMagnet(snarkByFile);
                    _manager.addMessage(_t("Magnet deleted: {0}", name.replace("Magnet ", "")));
                    return;
                }
                File torrentFile = new File(name);
                File dataDir = _manager.getDataDir();
                boolean canDelete = dataDir.canWrite() || !torrentFile.exists();
                _manager.stopTorrent(snarkByFile, canDelete);

                if (torrentFile.delete()) {
                    _manager.addMessage(_t("Torrent file deleted: {0}", torrentFile.getAbsolutePath()));
                } else if (torrentFile.exists()) {
                    if (!canDelete) {
                        _manager.addMessage(_t("No write permissions for data directory") + ": " + dataDir);
                    }
                    _manager.addMessage(_t("Torrent file could not be deleted: {0}", torrentFile.getAbsolutePath()));
                    return;
                }

                Storage storage = snark.getStorage();
                if (storage == null) break;

                // remove partial downloads from the staging dir, if any
                storage.deleteStagingData();

                List<List<String>> files = meta.getFiles();
                if (files == null) {
                    for (File file : storage.getFiles()) {
                        if (file.delete()) {
                            _manager.addMessage(_t("Data file deleted: {0}", file.getAbsolutePath()));
                        } else if (file.exists()) {
                            _manager.addMessage(_t("Data file could not be deleted: {0}", file.getAbsolutePath()));
                        }
                    }
                    break;
                }

                // Delete files silently, log failure
                for (File file : storage.getFiles()) {
                    if (!file.delete() && file.exists()) {
                        _manager.addMessage(_t("Data file could not be deleted: {0}", file.getAbsolutePath()));
                    }
                }

                // Delete directories bottom-up
                Set<File> dirs = storage.getDirectories();
                if (dirs == null) break;

                boolean allDeleted = true;
                if (_log.shouldInfo()) {
                    _log.info("Dirs to delete: " + DataHelper.toString(dirs));
                }
                for (File dir : dirs) {
                    if (!dir.delete() && dir.exists()) {
                        allDeleted = false;
                        _manager.addMessage(_t("Directory could not be deleted: {0}", dir.getAbsolutePath()));
                        if (_log.shouldWarn()) {
                            _log.warn("[I2PSnark] Could not delete directory: " + dir);
                        }
                    }
                }
                if (allDeleted) {
                    _manager.addMessage(_t("Directory deleted: {0}", storage.getBase()));
                }
                break;
            }
        }
    }

    /**
     * Handles saving configuration updates.
     *
     * @param req the HTTP request with config parameters
     */
    private boolean handleSave(HttpServletRequest req, HttpServletResponse resp) {
        configForms().applySettings(req);
        try {
            setResourceBase(_manager.getDataDir());
        } catch (ServletException ignored) { /* ignored */ }
        String browserApiHosts = req.getParameter("nofilter_browserApiHosts");
        if (browserApiHosts != null) {
            _manager.setBrowserApi(req.getParameter("browserApi") != null, browserApiHosts);
        }
        if (req.getParameter("installBrowserApi") != null) {
            return installBrowserApi(req, resp);
        }
        return false;
    }

    /**
     * Installs the I2PSnark Bridge extension that forwards magnet links to the
     * browser API endpoint. Called from the main config save when the install
     * button was used; enables the API if it is off.
     *
     * <p>The server writes no files: the browser is redirected to the bundled
     * extension XPI, which opens the install prompt in the logged-in user's
     * own browser session.
     *
     * @param req the HTTP request
     * @param resp the HTTP response
     * @return true if the response was fully handled (redirect sent)
     */
    private boolean installBrowserApi(HttpServletRequest req, HttpServletResponse resp) {
        if (!_manager.browserApiEnabled()) {
            _manager.setBrowserApi(true, _manager.getBrowserApiHosts());
            _manager.addMessage(_t("Browser API enabled."));
        }
        String url = browserApiBaseUrl(req) + WARBASE + "browser/i2psnark-bridge.xpi";
        try {
            resp.sendRedirect(url);
            return true;
        } catch (IOException ioe) {
            _manager.addMessageAndPrint(_t("Failed to open the I2PSnark Bridge extension: {0}", ioe.getMessage()));
            return false;
        }
    }

    /**
     * The version of the I2PSnark Bridge extension bundled in this war, read
     * once from the XPI's manifest.json; null if it cannot be determined.
     */
    String getBridgeVersion() {
        String v = _bridgeVersion;
        if (v == null) {
            synchronized (BridgeVersion.class) {
                v = _bridgeVersion;
                if (v == null) {
                    // stream ownership transfers to readXpiVersion(), which closes it
                    InputStream in = getServletContext().getResourceAsStream(WARBASE + "browser/i2psnark-bridge.xpi");
                    v = BridgeVersion.readXpiVersion(in);
                    if (v == null) {v = "";} // negative-cache failures
                    _bridgeVersion = v;
                }
            }
        }
        return v.isEmpty() ? null : v;
    }

    /**
     * The console base URL for this request, e.g. http://127.0.0.1:7657/i2psnark
     * (no trailing slash), used as the default endpoint of the installed handler.
     */
    private String browserApiBaseUrl(HttpServletRequest req) {
        String url = req.getRequestURL().toString();
        String context = _contextPath;
        int idx = url.indexOf(context);
        if (idx > 0) {return url.substring(0, idx + context.length());}
        return url;
    }

    /**
     * Handles saving tracker form updates.
     *
     * @param req the HTTP request
     */
    private void handleSaveTrackers(HttpServletRequest req) {
        String taction = req.getParameter("taction");
        if (taction != null) {
            processTrackerForm(taction, req);
        }
    }

    /**
     * Handles saving torrent creation filter form updates.
     *
     * @param req the HTTP request
     */
    private void handleSaveCreateFilters(HttpServletRequest req) {
        String raction = req.getParameter("raction");
        if (raction != null) {
            processTorrentCreateFilterForm(raction, req);
        }
    }

    /**
     * Result of validating a base file for torrent creation.
     * Package-visible for testing.
     */
    static class BaseFileValidationResult {
        final File baseFile;
        final String errorMessage; // null if valid

        BaseFileValidationResult(File baseFile, String errorMessage) {
            this.baseFile = baseFile;
            this.errorMessage = errorMessage;
        }

        boolean isValid() {return errorMessage == null;}
    }

    /**
     * Validates the base file for torrent creation.
     * Checks: not empty, exists, not .torrent, not duplicate, not in I2P dirs, not nested.
     *
     * @param baseData the raw base file path from request
     * @param manager the SnarkManager for context (data dir, existing torrents)
     * @return validation result with resolved File and error message (null if valid)
     * @since 0.9.71+
     */
    static BaseFileValidationResult validateBaseFile(String baseData, SnarkManager manager) {
        if (baseData == null || baseData.trim().isEmpty()) {
            return new BaseFileValidationResult(null, "Error creating torrent - you must specify a file or directory");
        }
        File baseFile = new File(baseData.trim());
        if (!baseFile.isAbsolute()) {
            baseFile = new File(manager.getDataDir(), baseData.trim());
        }
        if (!baseFile.exists()) {
            return new BaseFileValidationResult(null,
                "Cannot create a torrent for the nonexistent data: " + baseFile.getAbsolutePath());
        }
        String baseName = baseFile.getName();
        if (baseName.toLowerCase(Locale.US).endsWith(".torrent")) {
            return new BaseFileValidationResult(null,
                "Cannot add a torrent ending in \".torrent\": " + baseFile.getAbsolutePath());
        }
        if (manager.getTorrentByBaseName(baseName) != null) {
            return new BaseFileValidationResult(null,
                "Torrent with this name is already running: " + baseName);
        }
        File dataDir = manager.getDataDir();
        if (!dataDir.canWrite()) {
            return new BaseFileValidationResult(null,
                "No write permissions for data directory: " + dataDir);
        }
        // Check I2P directories
        if (isParentOf(baseFile, dataDir) ||
            isParentOf(baseFile, manager.util().getContext().getBaseDir()) ||
            isParentOf(baseFile, manager.util().getContext().getConfigDir())) {
            return new BaseFileValidationResult(null,
                "Cannot add a torrent including an I2P directory: " + baseFile.getAbsolutePath());
        }
        // Check nested torrents (requires instance method, delegate to servlet)
        // We return valid here; nested check is done after with instance method
        return new BaseFileValidationResult(baseFile, null);
    }

    /**
     * Parsed announce parameters from the create torrent form.
     * Package-visible for testing.
     */
    static class AnnounceParams {
        final String primary; // may be null
        final List<String> backupURLs; // never null

        AnnounceParams(String primary, List<String> backupURLs) {
            this.primary = primary;
            this.backupURLs = backupURLs;
        }
    }

    /**
     * Parses announce URL and backup tracker URLs from the request.
     * Recognized: "announceURL", "backup_*" parameters.
     *
     * @param req the HTTP request
     * @return parsed announce parameters, never null
     * @since 0.9.71+
     */
    static AnnounceParams parseAnnounceParams(HttpServletRequest req) {
        String announceURL = req.getParameter("announceURL");
        if ("none".equals(announceURL)) announceURL = null;

        List<String> backupURLs = new ArrayList<>();
        Enumeration<?> paramNames = req.getParameterNames();
        while (paramNames.hasMoreElements()) {
            Object o = paramNames.nextElement();
            if (!(o instanceof String)) continue;
            String k = (String) o;
            if (k.startsWith("backup_")) {
                String url = k.substring(7);
                if (!url.equals(announceURL)) {
                    backupURLs.add(DataHelper.stripHTML(url));
                }
            }
        }
        return new AnnounceParams(announceURL, backupURLs);
    }

    /**
     * Result of building the announce list.
     * Package-visible for testing.
     */
    static class CreateAnnounceListResult {
        final List<List<String>> announceList; // null if no backups
        final boolean isPrivate;
        final String errorMessage; // null if valid

        CreateAnnounceListResult(List<List<String>> announceList, boolean isPrivate, String errorMessage) {
            this.announceList = announceList;
            this.isPrivate = isPrivate;
            this.errorMessage = errorMessage;
        }

        boolean isValid() {return errorMessage == null;}
    }

    /**
     * Builds the tiered announce list from primary and backup URLs.
     * Validates no mixing of private and public trackers.
     *
     * @param params parsed announce parameters
     * @param privateTrackers the configured private tracker URLs
     * @return announce list result with tiered list and private flag, or error
     * @since 0.9.71+
     */
    static CreateAnnounceListResult buildAnnounceList(AnnounceParams params, List<String> privateTrackers) {
        if (params.backupURLs.isEmpty()) {
            boolean isPrivate = params.primary != null && privateTrackers.contains(params.primary);
            return new CreateAnnounceListResult(null, isPrivate, null);
        }
        if (params.primary == null) {
            return new CreateAnnounceListResult(null, false,
                "Error - Cannot include alternate trackers without a primary tracker");
        }
        List<String> allURLs = new ArrayList<>(params.backupURLs.size() + 1);
        allURLs.add(params.primary);
        allURLs.addAll(params.backupURLs);

        boolean hasPrivate = false;
        boolean hasPublic = false;
        for (String url : allURLs) {
            if (privateTrackers.contains(url)) hasPrivate = true;
            else hasPublic = true;
        }
        if (hasPrivate && hasPublic) {
            return new CreateAnnounceListResult(null, false,
                "Error - Cannot mix private and public trackers in a torrent");
        }
        List<List<String>> announceList = new ArrayList<>(allURLs.size());
        for (String url : allURLs) {
            announceList.add(Collections.singletonList(url));
        }
        return new CreateAnnounceListResult(announceList, hasPrivate, null);
    }

    /**
     * Parses the selected torrent creation filters from the request.
     *
     * @param req the HTTP request
     * @param filterMap the available filters from manager
     * @return list of selected filters (never null)
     * @since 0.9.71+
     */
    static List<TorrentCreateFilter> parseCreateFilters(HttpServletRequest req,
                                                         Map<String, TorrentCreateFilter> filterMap) {
        String[] filters = req.getParameterValues("filters");
        if (filters == null) return Collections.emptyList();
        List<TorrentCreateFilter> filterList = new ArrayList<>(filters.length);
        for (String filterName : filters) {
            TorrentCreateFilter filter = filterMap.get(filterName);
            if (filter != null) {
                filterList.add(filter);
            }
        }
        return filterList;
    }

    /**
     * Handles creating a new torrent from provided base file or directory.
     *
     * @param req the HTTP request
     */
    private void handleCreate(HttpServletRequest req) {
        BaseFileValidationResult validation = validateBaseFile(req.getParameter("nofilter_baseFile"), _manager);
        if (!validation.isValid()) {
            _manager.addMessageAndPrint(_t(validation.errorMessage));
            return;
        }
        File baseFile = validation.baseFile;

        // Check nested torrents (instance method)
        if (checkNestedTorrent(baseFile, true)) {
            return;
        }

        AnnounceParams announceParams = parseAnnounceParams(req);
        _lastAnnounceURL = announceParams.primary;

        CreateAnnounceListResult alr = buildAnnounceList(announceParams, _manager.getPrivateTrackers());
        if (!alr.isValid()) {
            _manager.addMessageAndPrint(_t(alr.errorMessage));
            return;
        }

        List<TorrentCreateFilter> filterList = parseCreateFilters(req, _manager.getTorrentCreateFilterMap());

        try {
            Storage storage = new Storage(_manager.util(), baseFile, announceParams.primary,
                                          alr.announceList, null, alr.isPrivate, null, filterList);
            storage.close(); // close files

            MetaInfo info = storage.getMetaInfo();
            File torrentFile = new File(_manager.getDataDir(), storage.getBaseName() + ".torrent");

            boolean ok = _manager.addTorrent(info, storage.getBitField(), torrentFile.getAbsolutePath(), baseFile, true);
            if (!ok) return;

            List<String> filesExcluded = storage.getExcludedFiles(_manager.getDataDir());
            if (_log.shouldInfo() && !filesExcluded.isEmpty()) {
                String msg = filesExcluded.size() + " excluded from \"" + baseFile.getName() + "\" due to filter rules [" + String.join(", ", filesExcluded) + "]";
                _log.info("[I2PSnark] " + msg);
                if (isStandalone()) System.out.println(" • " + msg);
            }
            if (filesExcluded.size() > 5) {
                _manager.addMessage(filesExcluded.size() + _t(" files or folders were excluded from \"{0}\" due to filter rules.", baseFile.getName()));
            } else if (!filesExcluded.isEmpty()) {
                _manager.addMessage(_t("The following files or folders were excluded from \"{0}\" due to filter rules: ", baseFile.getName()) + String.join(", ", filesExcluded));
            }

            _manager.addMessage(_t("Torrent created for \"{0}\"", baseFile.getName()) + " ➜  " + torrentFile.getAbsolutePath());

            if (announceParams.primary != null && !_manager.util().getOpenTrackers().contains(announceParams.primary)) {
                _manager.addMessage(_t("Many I2P trackers require you to register new torrents before seeding - please do so before starting \"{0}\"", baseFile.getName()));
            }
        } catch (IOException ioe) {
            String msg = ioe.getMessage() != null ? ioe.getMessage() : ioe.toString();
            _manager.addMessage(_t("Error creating a torrent for \"{0}\"", baseFile.getAbsolutePath()) + ": " + msg);
            _log.warn("Error creating a torrent: " + msg);
        }
    }

    /**
     * Handles stopping all torrents or search-filtered torrents.
     *
     * @param req the HTTP request with "search" parameter
     */
    private void handleStopAll(HttpServletRequest req) {
        String search = req.getParameter("search");
        if (search != null && !search.isEmpty()) {
            List<Snark> matches = search(search, _manager.getTorrents());
            if (matches != null) {
                for (Snark snark : matches) {
                    _manager.stopTorrent(snark, false);
                }
                return;
            }
        }
        _manager.stopAllTorrents(false);
    }

    /**
     * Handles starting all torrents or search-filtered torrents.
     *
     * @param req the HTTP request with "search" parameter
     */
    private void handleStartAll(HttpServletRequest req) {
        String search = req.getParameter("search");
        if (search != null && !search.isEmpty()) {
            List<Snark> matches = search(search, _manager.getTorrents());
            if (matches != null) {
                // staggered, pool-aware start on a background thread; the
                // request returns immediately so the page never stalls
                _manager.startStoppedTorrents(matches);
                return;
            }
        }
        _manager.startAllTorrents();
    }

    /**
     * Handles clearing messages by id.
     *
     * @param req the HTTP request with "id" parameter
     */
    private void handleClearMessages(HttpServletRequest req) {
        String sid = req.getParameter("id");
        if (sid != null) {
            int id = I2PSnarkUtil.parseInt(sid, -1);
            if (id >= 0) {
                _manager.clearMessages(id);
            }
        }
    }

    /**
     *  Redirect a POST to a GET (P-R-G), preserving the query string.
     *  The query string must only contain the numeric params that
     *  {@link #getQueryString} emits, so the redirect target can never
     *  be an attacker-supplied URL.
     *
     *  @param req the request
     *  @param resp the response
     *  @param p the query string, may be null or empty
     *  @throws IOException on write failure
     *  @since 0.9.5
     */
    private void sendRedirect(HttpServletRequest req, HttpServletResponse resp, String p) throws IOException {
        String url = req.getRequestURL().toString();
        // Trim trailing "_post" if present
        if (url.endsWith("_post")) {url = url.substring(0, url.length() - 5);}

        if (p != null && !p.isEmpty()) {
            // Remove any HTML entities &amp; before validating
            String decodedP = p.replace("&amp;", "&");
            if (!RedirectQuery.isSafeRedirectQuery(decodedP)) {
                // Invalid redirect parameter, reject request
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid redirect parameter");
                return;
            }
            url += decodedP;
        }
        // Perform redirect safely
        resp.sendRedirect(url);
    }

    /**
     * Process tracker form submission.
     *
     * @param action the action
     * @param req the request
     */
    private void processTrackerForm(String action, HttpServletRequest req) {
        if (action.equals(_t("Delete selected")) || action.equals(_t("Save tracker configuration"))) {
            boolean changed = false;
            Map<String, Tracker> trackers = _manager.getTrackerMap();
            List<String> removed = new ArrayList<>();
            List<String> open = new ArrayList<>();
            List<String> priv = new ArrayList<>();
            Enumeration<?> e = req.getParameterNames();
            while (e.hasMoreElements()) {
                 Object o = e.nextElement();
                 if (!(o instanceof String)) {continue;}
                 String k = (String) o;
                 if (k.startsWith("delete_")) {
                     k = k.substring(7);
                     Tracker t;
                     if ((t = trackers.remove(k)) != null) {
                        removed.add(t.announceURL);
                        _manager.addMessage(_t("Removed") + ": " + DataHelper.stripHTML(k));
                        changed = true;
                     }
                } else if (k.startsWith("ttype_")) {
                     String val = req.getParameter(k);
                     k = k.substring(6);
                     if ("1".equals(val)) {open.add(k);}
                     else if ("2".equals(val)) {priv.add(k);}
                }
            }
            if (changed) {_manager.saveTrackerMap();}

            open.removeAll(removed);
            List<String> oldOpen = new ArrayList<>(_manager.util().getOpenTrackers());
            Collections.sort(oldOpen);
            Collections.sort(open);
            if (!open.equals(oldOpen)) {_manager.saveOpenTrackers(open);}

            priv.removeAll(removed);
            // open trumps private
            priv.removeAll(open);
            List<String> oldPriv = new ArrayList<>(_manager.getPrivateTrackers());
            Collections.sort(oldPriv);
            Collections.sort(priv);
            if (!priv.equals(oldPriv)) {_manager.savePrivateTrackers(priv);}

        } else if (action.equals(_t("Add tracker"))) {
            String name = req.getParameter("tname");
            String hurl = req.getParameter("thurl");
            String aurl = req.getParameter("taurl");
            if (name != null && hurl != null && aurl != null) {
                name = DataHelper.stripHTML(name.trim());
                hurl = DataHelper.stripHTML(hurl.trim());
                if (!hurl.startsWith("http://") && !hurl.startsWith("udp://")) {hurl = "http://" + hurl;} // Add http:// if not present
                aurl = DataHelper.stripHTML(aurl.trim()).replace("=", "&#61;");
                if (!aurl.startsWith("http://") && !aurl.startsWith("udp://")) {aurl = "http://" + aurl;}  // Add http:// if not present
                if (!name.isEmpty() && hurl.startsWith("http://") && TrackerClient.isValidAnnounce(aurl)) {
                    Map<String, Tracker> trackers = _manager.getTrackerMap();
                    trackers.put(name, new Tracker(name, aurl, hurl));
                    _manager.saveTrackerMap();
                    String type = req.getParameter("add_tracker_type");
                    if ("1".equals(type)) {
                        List<String> newOpen = new ArrayList<>(_manager.util().getOpenTrackers());
                        newOpen.add(aurl);
                        _manager.saveOpenTrackers(newOpen);
                    } else if ("2".equals(type)) {
                        List<String> newPriv = new ArrayList<>(_manager.getPrivateTrackers());
                        newPriv.add(aurl);
                        _manager.savePrivateTrackers(newPriv);
                    }
                } else {_manager.addMessage(_t("Enter valid tracker name and URLs"));}
            } else {_manager.addMessage(_t("Enter valid tracker name and URLs"));}
        } else if (action.equals(_t("Restore defaults"))) {
            _manager.setDefaultTrackerMap();
            _manager.saveOpenTrackers(null);
            _manager.addMessage(_t("Restored default trackers"));
        } else {_manager.addMessage("Unknown POST action: \"" + action + '\"');}
    }

    /**
     * Process torrent create filter form submission.
     *
     * @param action the action
     * @param req the request
     */
    private void processTorrentCreateFilterForm(String action, HttpServletRequest req) {
        if (action.equals(_t("Delete selected")) || action.equals(_t("Save Filter Configuration"))) {
            boolean changed = false;
            Map<String, TorrentCreateFilter> torrentCreateFilters = _manager.getTorrentCreateFilterMap();
            Enumeration<?> e = req.getParameterNames();
            ArrayList<String> newDefaults = new ArrayList<>();
            ArrayList<TorrentCreateFilter> replaceFilters = new ArrayList<>();
            while (e.hasMoreElements()) {
                Object o = e.nextElement();
                if (!(o instanceof String)) {continue;}
                String k = (String) o;
                if (k.startsWith("delete_")) {
                    k = k.substring(7);
                    if ((torrentCreateFilters.remove(k)) != null) {
                        _manager.addMessage(_t("Removed") + ": " + DataHelper.stripHTML(k));
                    }
                } else if (k.startsWith("defaultEnabled_")) {
                    String filterName = k.replace("defaultEnabled_", "");
                    newDefaults.add(filterName);
                }
            }
            for (Map.Entry<String, TorrentCreateFilter> entry : torrentCreateFilters.entrySet()) {
                String filterName = entry.getKey();
                String filterPattern = entry.getValue().filterPattern;
                String filterType = req.getParameter("filterType_" + filterName.replace(" ", "_"));
                String oldFilterType = entry.getValue().filterType;
                boolean newDefault = newDefaults.contains(filterName);

                if (filterType == null) {filterType = oldFilterType;}

                TorrentCreateFilter oldFilter = torrentCreateFilters.remove(filterName);
                TorrentCreateFilter newFilter = new TorrentCreateFilter(filterName, filterPattern, filterType, newDefault);
                replaceFilters.add(newFilter);
            }
            for (int i = 0; i < replaceFilters.size(); i++) {
                TorrentCreateFilter filter = replaceFilters.get(i);
                torrentCreateFilters.put(filter.name, filter);
            }
            _manager.saveTorrentCreateFilterMap();

        } else if (action.equals(_t("Add File Filter"))) {
            String name = req.getParameter("fname");
            String filterPattern = req.getParameter("filterPattern");
            String filterType = req.getParameter("filterType");
            boolean isDefault = req.getParameter("filterIsDefault") != null;
            if (name != null && !name.trim().isEmpty() && filterPattern != null && !filterPattern.trim().isEmpty()) {
                Map<String, TorrentCreateFilter> torrentCreateFilters = _manager.getTorrentCreateFilterMap();
                torrentCreateFilters.put(name, new TorrentCreateFilter(name, filterPattern, filterType, isDefault));
                _manager.saveTorrentCreateFilterMap();
            } else {_manager.addMessage(_t("Enter valid name and filter pattern"));}
        } else if (action.equals(_t("Restore defaults"))) {
            _manager.setDefaultTorrentCreateFilterMap();
            _manager.addMessage(_t("Restored default torrent create filters"));
        } else {_manager.addMessage("Unknown POST action: \"" + action + '\"');}
    }

    /**
     * Builds the I2CP options string from individual form parameters.
     *
     * @param req the HTTP request containing tunnel configuration parameters
     * @return the combined I2CP options string
     */

    /**
     * Returns the list of torrents sorted according to the current request's sort parameter.
     *
     * @param req the HTTP request containing the "sort" parameter
     * @return a new sorted list of Snark instances
     */
    private List<Snark> getSortedSnarks(HttpServletRequest req) {
        ArrayList<Snark> rv = new ArrayList<>(_manager.getTorrents());
        if (rv.size() > 1) {
            int sort = 0;
            String ssort = normalizeSortParam(req.getParameter("sort"));
            if (ssort != null) {
                sort = I2PSnarkUtil.parseInt(ssort, 0);
            }
            // Pool-aware sorting exists only in multi-dest mode; ignore stale
            // 13/-13 parameters from old URLs instead of applying
            // StatusPoolComparator outside its domain.
            if (!multiDestActive() && (sort == 13 || sort == -13)) {
                sort = 0;
            }
            String lang;
            if (_manager.isSmartSortEnabled()) {
                lang = Translate.getLanguage(_manager.util().getContext());
            } else {lang = null;}
            // Java 7 TimSort - may be unstable
            DataHelper.sort(rv, Sorters.getComparator(sort, lang, this));
        }
        return rv;
    }

    /** Longest file name shown before truncation. */
    static final int MAX_DISPLAYED_FILENAME_LENGTH = 255;

    /**
     * Checks whether a torrent matches the given filter based on its status string.
     *
     * @param s the Snark torrent to check
     * @param filter the filter name (e.g., "active", "downloading", "stopped")
     * @param snarkStatus the status keyword string for the torrent
     * @return true if the torrent matches the filter, or if filter is null/empty
     */
    private boolean snarkMatchesFilter(Snark s, String filter, String snarkStatus) {
        if (s == null || filter == null || filter.isEmpty()) { return true; }
        if (snarkStatus == null) { return false; }

        switch (filter) {
            case "active":
                return snarkStatus.contains("active") && !snarkStatus.contains("inactive");
            case "inactive":
                return snarkStatus.contains("zero") || snarkStatus.contains("inactive");
            case "downloading":
                return snarkStatus.contains("downloading");
            case "connected":
                return snarkStatus.contains("connected");
            case "seeding":
                return snarkStatus.contains("seeding");
            case "complete":
                return snarkStatus.contains("complete") && !snarkStatus.contains("incomplete");
            case "incomplete":
                return snarkStatus.contains("incomplete");
            case "stopped":
                return snarkStatus.contains("stopped");
            case "all":
                return true;
            default:
                return true;
        }
    }

    /**
     * Per-row and per-render inputs for {@link #displaySnark}, replacing an
     * eighteen-parameter signature. Immutable view over render-scope state.
     *
     * @since 0.9.71+
     */
    private static class RowContext {
        final Snark snark;
        /** zero-based index within the current page; drives parity styling */
        final int index;
        final boolean showPeers;
        /** render-scope accumulator; reference is shared, contents mutate */
        final long[] stats;
        final boolean noThinsp;
        final boolean canWrite;
        /** active filter value, "" for none; never null */
        final String filterParam;
        /** normalized sort parameter, or null */
        final String sortParam;
        /**
         * Per-render cache of badge lookups keyed by info hash, shared by
         * every RowContext of the current response; single-threaded.
         */
        final Map<ByteArray, BadgeInfo> badgeCache;
        /**
         * Render-scope map of b64 info-hash name to short action token
         * (see {@link ActionTokens#mint}); shared by every RowContext.
         */
        final Map<String, String> actionTokens;

        RowContext(Snark snark, int index, boolean showPeers, long[] stats,
                   boolean noThinsp, boolean canWrite, String filterParam, String sortParam,
                   Map<ByteArray, BadgeInfo> badgeCache, Map<String, String> actionTokens) {
            this.snark = snark;
            this.index = index;
            this.showPeers = showPeers;
            this.stats = stats;
            this.noThinsp = noThinsp;
            this.canWrite = canWrite;
            this.filterParam = filterParam;
            this.sortParam = sortParam;
            this.badgeCache = badgeCache;
            this.actionTokens = actionTokens;
        }
    }

    /**
     * Cached per-torrent values used by the status-cell destination badge:
     * the four-character base64 destination prefix and the client identity
     * display name, if any.
     *
     * @since 0.9.71+
     */
    private static class BadgeInfo {
        final String destPrefix;
        final String clientName;

        BadgeInfo(String destPrefix, String clientName) {
            this.destPrefix = destPrefix;
            this.clientName = clientName;
        }
    }

    /**
     * Look up (or compute) the badge values for a torrent. The base64
     * destination encoding is by far the most expensive part of building
     * the badge, and pool-sorted rows repeat the same destinations, so
     * results are cached per render in the context's cache.
     *
     * @param rc row context carrying the shared cache
     * @param snark the torrent being rendered
     * @return never-null cached badge info
     * @since 0.9.71+
     */
    private BadgeInfo badgeInfo(RowContext rc, Snark snark) {
        ByteArray key = new ByteArray(snark.getInfoHash());
        BadgeInfo bi = rc.badgeCache.get(key);
        if (bi == null) {
            TorrentDest td = snark.getDest();
            String prefix = "";
            if (td != null && td.getMyDestination() != null) {
                prefix = td.getMyDestination().toBase64().substring(0, 4);
            }
            ClientID.Profile cid = _manager.util().getClientID(snark.getInfoHash());
            bi = new BadgeInfo(prefix, cid != null ? cid.getName() : null);
            rc.badgeCache.put(key, bi);
        }
        return bi;
    }

    /**
     * Displays a single snark (torrent) as an HTML table row, including optional peer rows.
     * Updates {@link RowContext#stats} with cumulative data (uploaded, downloaded, rates,
     * peer counts, and size). Filtering is applied here: rows not matching the active
     * filter contribute to stats but emit no HTML. Appends generated HTML to the provided
     * StringBuilder buffer instead of writing directly to output, allowing batching and
     * improved rendering performance.
     *
     * @param out the PrintWriter the completed row buffer is appended to
     * @param rc per-row and per-render inputs; stats contents are updated in place
     * @param buf scratch buffer reset by the caller between rows
     * @throws IOException if an I/O error occurs during output operations
     */
    private void displaySnark(PrintWriter out, RowContext rc, StringBuilder buf) throws IOException {
        Snark snark = rc.snark;
        int row = rc.index;
        long[] stats = rc.stats;
        boolean noThinsp = rc.noThinsp;
        boolean canWrite = rc.canWrite;
        boolean showPeers = rc.showPeers;
        String filterParam = rc.filterParam;
        String sortParam = rc.sortParam;
        boolean filterEnabled = !filterParam.isEmpty() && !"all".equals(filterParam);
        String b64 = Base64.encode(snark.getInfoHash());
        // Short unique action token minted for this render (falls back to the
        // full b64 name if the token map is unavailable).
        String token = rc.actionTokens != null
            ? rc.actionTokens.getOrDefault(b64, b64) : b64;

        // Update stats first (minimal processing)
        long uploaded = snark.getUploaded();
        stats[STAT_DOWNLOADED] += snark.getDownloaded();
        stats[STAT_UPLOADED] += uploaded;
        long downBps = snark.getDownloadRate();
        long upBps = snark.getUploadRate();
        boolean isRunning = !snark.isStopped();
        if (isRunning) {
            stats[STAT_DOWNLOAD_RATE] += downBps;
            stats[STAT_UPLOAD_RATE] += upBps;
        }
        int curPeers = snark.getPeerCount();
        stats[STAT_PEERS] += curPeers;
        long total = snark.getTotalLength();
        long dataLength = snark.getDataLength();
        if (dataLength > 0) stats[STAT_TOTAL_SIZE] += dataLength;

        // Cache repeated computations
        String basename = snark.getBaseName();
        String fullBasename = basename;
        if (basename.length() > MAX_DISPLAYED_FILENAME_LENGTH) {
            String start = ServletUtil.truncate(basename, MAX_DISPLAYED_FILENAME_LENGTH);
            if (start.indexOf(' ') < 0 && start.indexOf('-') < 0) basename = start + HELLIP;
        }
        long remaining = snark.getRemainingLength();
        if (remaining < 0 || remaining > total) remaining = total;
        long needed = snark.getNeededLength();
        if (needed < 0 || needed > total) needed = total;
        long remainingSeconds = (downBps > 0 && needed > 0) ? needed / downBps : -1;

        MetaInfo meta = snark.getMetaInfo();
        boolean isValid = meta != null;
        boolean isMultiFile = isValid && meta.getFiles() != null;

        int swarmPeers = snark.getSwarmPeerCount();
        int scrapePeers = snark.getScrapeSeeders() + snark.getScrapeLeechers() + snark.getScrapePartialSeeds();
        int knownPeers = Math.max(curPeers, Math.max(swarmPeers, Math.max(scrapePeers, snark.getTrackerSeenPeers())));
        StatusResult statusResult = buildStatusString(snark, curPeers, knownPeers, downBps, upBps, isRunning, remaining, needed, noThinsp);
        String snarkStatusLocal = statusResult.snarkStatus;

        // Filter check early exit
        if (!filterEnabled || snarkMatchesFilter(snark, filterParam, snarkStatusLocal)) {
            String statusString = statusResult.statusHtml;
            String rowClass = (row % 2 == 0 ? "even" : "odd");
            String rowStatus = rowClass + ' ' + snarkStatusLocal;
            // queued for autostart: scheduled by the batch starter, tunnels
            // not built yet
            if (snark.isStarting()) {rowStatus += " autostart";}

            // In multi-dest mode every running torrent carries a pool/destination
            // badge after its status icon when sorted by status+pool (13/-13):
            // the sequential pool number for torrents sharing a pooled
            // destination, or D for one on a dedicated destination.
            if (isRunning && multiDestActive() && sortParam != null && sortParam.contains("13")) {
                statusString = injectPoolBadge(statusString, snark, rc);
            }

            buf.append("<tr class=\"").append(rowStatus).append(" volatile\"><td class=status>")
               .append(statusString).append("</b></td><td class=trackerLink>");

            if (isValid) {
                String announce = meta.getAnnounce();
                if (announce == null) announce = snark.getTrackerURL();
                if (announce != null) {
                    String trackerLink = getTrackerLink(announce, snark.getInfoHash());
                    if (trackerLink != null) buf.append(trackerLink);
                }
            }

            String encodedBaseName = encodePath(fullBasename);
            String hex = I2PSnarkUtil.toHex(snark.getInfoHash());
            String torrentPath = (encodedBaseName != null) ? "/i2psnark/" + encodedBaseName + "/" : "";

            buf.append("</td><td class=magnet>");
            if (isValid && meta != null) {
                String announce = meta.getAnnounce();
                String magnetLink = MagnetURI.MAGNET_FULL + hex;
                buf.append("<a class=mLink href=\"").append(magnetLink);
                if (announce != null) buf.append("&amp;tr=").append(announce);
                if (encodedBaseName != null) buf.append("&amp;dn=").append(encodedBaseName.replace(".torrent", ""));
                buf.append("\">");
                appendIcon(buf, "magnet", "", "", false, true);
                buf.append("<span class=copyMagnet></span></a>");
            }

            buf.append("</td><td class=\"details").append(!isValid && !isMultiFile ? " fetching" : " data").append("\">");

            if (isValid) {
                CommentSet comments = snark.getComments();
                buf.append("<span class=filetype><a href=\"")
                   .append(torrentPath)
                   .append("\" title=\"")
                   .append(_t("Torrent details"))
                   .append("\">");
                if (comments != null && !comments.isEmpty()) {
                    buf.append("<span class=commented title=\"")
                       .append(_t("Torrent has comments")).append("\">");
                    appendIcon(buf, "rateme", "", "", false, true);
                    buf.append("</span>");
                }
            }

            String icon = isMultiFile ? "folder" : (isValid ? toIcon(meta.getName()) : (snark instanceof FetchAndAdd ? "download" : "magnet"));
            if (isValid) {
                appendIcon(buf, icon, "", "", false, true);
                buf.append("</a></span>");
            } else {
                appendIcon(buf, icon, "", "", false, true);
            }

            buf.append("</td><td class=tName>");
            if (remaining == 0 || isMultiFile) {
                buf.append("<a href=\"").append(DataHelper.escapeHTML(torrentPath));
                if (isMultiFile) buf.append('/');
                buf.append("\" title=\"").append(isMultiFile ? _t("View files") : _t("Open file")).append("\">");
            }

            if (basename.contains("Magnet")) {
                buf.append(DataHelper.escapeHTML(basename)
                    .replace("Magnet ", "<span class=infohash>")
                    .replaceFirst("\\(", "</span> <span class=magnetLabel>").replaceAll("\\)$", ""))
                   .append("</span>");
            } else {
                buf.append(DataHelper.escapeHTML(basename));
            }

            if (remaining == 0 || isMultiFile) buf.append("</a>");

            buf.append("</td><td class=ETA>");
            if (isRunning && remainingSeconds > 0 && !snark.isChecking()) {
                buf.append(DataHelper.formatDuration2(Math.max(remainingSeconds, 10) * 1000));
            }
            buf.append("</td><td class=rxd>");
            if (remaining > 0) {
                buf.append(buildProgressBar(total, remaining, true, true, noThinsp, true));
            } else if (remaining == 0) {
                DateFormat fmt = _DATE_FMT1.get();
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                long[] dates = _manager.getSavedAddedAndCompleted(snark);
                String date = fmt.format(new Date(dates[1]));
                buf.append("<div class=barComplete title=\"").append(_t("Completed")).append(": ").append(date).append("\">")
                   .append(formatSize(dataLength).replaceAll("iB", "")).append("</div>");
            }

            buf.append("</td><td class=\"rateDown");
            if (downBps >= 100000) buf.append(" hundred");
            else if (downBps >= 10000) buf.append(" ten");
            buf.append("\">");

            if (isRunning && needed > 0 && downBps > 0 && curPeers > 0) {
                buf.append("<span class=right>").append(formatSizeSpans(formatSize(downBps), false)).append("/s</span>");
            }

            buf.append("</td><td class=txd>");
                if (isValid) {
                    double ratio = dataLength > 0 ? uploaded / (double) dataLength : 0;
                    String txPercent = new DecimalFormat(ratio <= 0.01 && ratio > 0 ? "0.00" : "0").format(ratio * 100);
                    String txPercentBar = ratio > 1 ? "100%" : txPercent + "%";

                    if (uploaded > 0) {
                    buf.append("<span class=tx title=\"").append(_t("Share ratio")).append(": ").append(txPercent).append(" %");
                    DateFormat fmt = _DATE_FMT1.get();
                    Storage storage = snark.getStorage();
                    if (storage != null) {
                        long lastActive = storage.getActivity();
                        String date = fmt.format(new Date(lastActive));
                        buf.append(" &bullet; ").append(_t("Last activity")).append(": ").append(date);
                    }
                    buf.append("\"><span class=txBarText><span class=right>")
                       .append(formatSizeSpans(formatSize(uploaded), true))
                       .append("</span> <span class=txBarInner style=\"width:calc(").append(txPercentBar).append(" - 2px)\"></span></span>");
                }
            }

            buf.append("</td><td class=\"rateUp");
            if (upBps >= 100000) buf.append(" hundred");
            else if (upBps >= 10000) buf.append(" ten");
            buf.append("\">");
            if (isRunning && isValid && upBps > 0 && curPeers > 0) {
                buf.append("<span class=right>").append(formatSizeSpans(formatSize(upBps), false)).append("/s</span>");
            }

            buf.append("</td><td class=tAction>");
            boolean shouldDisable = snark.isChecking();
            if (isRunning) {
                buf.append("<input type=submit class=doStop name=\"do_Stop_").append(token).append("\" value=\"").append(_t("Stop"))
                   .append("\" title=\"").append(_t("Stop torrent")).append("\"").append(shouldDisable ? " disabled" : "").append(">");
            } else if (!snark.isStarting()) {
                buf.append("<input type=submit class=doStart name=\"do_Start_").append(token).append("\" value=\"").append(_t("Start"))
                   .append("\" title=\"").append(_t("Start torrent")).append("\"").append(shouldDisable ? " disabled" : "").append(">");

                if (isValid && canWrite) {
                    appendTorrentActionButton(buf, "Remove", token, snark,
                            _t("Remove and delete torrent, retaining downloaded files"));
                }
                if (!isValid || canWrite) {
                    appendTorrentActionButton(buf, "Delete", token, snark,
                            _t("Delete .torrent file and associated data files"));
                }
            }
            buf.append("</td></tr>\n");

            // Conditionally render peers
            if (showPeers && isRunning && curPeers > 0) {
                List<Peer> peers = snark.getPeerList();
                Collections.sort(peers, new PeerComparator());
                for (Peer peer : peers) {
                    appendPeerRow(buf, peer, snark, meta, noThinsp);
                }
            }
            out.append(buf);
        }
    }

    /**
     * Injects the multi-dest pool badge into the status cell HTML. The badge
     * carries the sequential pool number (or D for dedicated) and the
     * destination prefix as tooltip, inserted before the closing &lt;/td&gt;.
     *
     * @param statusHtml the status cell inner HTML, modified by this call
     * @param snark the torrent whose destination to display
     * @param rc row context carrying the shared badge cache; must not be null
     * @return the status HTML with the pool badge injected
     * @since 0.9.71+
     */
    private String injectPoolBadge(String statusHtml, Snark snark, RowContext rc) {
        TorrentDest td = snark.getDest();
        if (td == null || td.getMyDestination() == null) {return statusHtml;}
        int poolNum = td.getPoolNum();
        BadgeInfo bi = badgeInfo(rc, snark);
        StringBuilder badge = new StringBuilder(64).append("<span class=pool");
        badge.append(" title=\"").append(_t("Destination")).append(": ")
             .append(bi.destPrefix);
        if (bi.clientName != null) {badge.append(" [").append(bi.clientName).append(']');}
        badge.append('"').append('>');
        badge.append(poolNum >= 1 ? String.valueOf(poolNum) : "D");
        badge.append("</span>");
        int tdEnd = statusHtml.indexOf("</td>");
        if (tdEnd < 0) {return statusHtml;}
        return statusHtml.substring(0, tdEnd) + badge + statusHtml.substring(tdEnd);
    }

    /**
     * Append a Remove or Delete submit button for a torrent row. Both carry
     * the JS-escaped torrent names consumed by the row's confirmation script.
     *
     * @param buf destination buffer for the row HTML
     * @param action action suffix appended to the request parameter name
     * @param b64 base64 info hash forming the parameter name with the action
     * @param snark the torrent the button acts on
     * @param title tooltip text describing what will be removed or deleted
     * @since 0.9.71+
     */
    private void appendTorrentActionButton(StringBuilder buf, String action,
                                           String token, Snark snark, String title) {
        buf.append("<input type=submit class=do").append(action)
           .append(" name=\"do_").append(action).append('_').append(token).append("\" value=\"")
           .append(_t(action)).append("\" title=\"").append(title).append("\" client=\"")
           .append(escapeJSString(snark.getName())).append("\" data-name=\"")
           .append(escapeJSString(snark.getBaseName())).append(".torrent\">");
    }

    /**
     * The mutually exclusive states a torrent row can display, in the
     * evaluation order of {@link #classifyStatus}. Package-visible so the
     * classifier can be unit-tested without a servlet.
     */
    enum StatusKind {
        CHECKING, ALLOCATING, TRACKER_ERROR, STARTING,
        SEEDING_ACTIVE, SEEDING_CONNECTED_IDLE, STALLED_CONNECTED_IDLE,
        SEEDING_IDLE, COMPLETE_STOPPED, DOWNLOADING,
        STALLED_INCOMPLETE_CONNECTED, NOPEERS_CONNECTED, NOPEERS_UNKNOWN,
        STOPPED_DEFAULT
    }

    /**
     * Pure decision function mirroring the historical if/else cascade of
     * buildStatusString(), extracted so state classification can be tested
     * without rendering. Branch order here is load-bearing: earlier states
     * win, exactly as in the original cascade.
     *
     * @param allocating torrent is allocating disk space
     * @param checking torrent is being checked or rechecked
     * @param starting start requested, tunnels not built yet
     * @param trackerProblems tracker unreachable for over an hour while idle
     * @param running torrent is neither stopped nor errored
     * @param complete all pieces present (remaining or needed == 0)
     * @param curPeers connected peer count
     * @param knownPeers total peers known from trackers/DHT/PEX
     * @param uploading current upload rate above zero
     * @param downloading current download rate above zero
     * @return the single display state for this torrent
     * @since 0.9.71+
     */
    static StatusKind classifyStatus(boolean allocating, boolean checking, boolean starting,
                                     boolean trackerProblems, boolean running, boolean complete,
                                     int curPeers, int knownPeers,
                                     boolean uploading, boolean downloading) {
        boolean connected = curPeers > 0;
        boolean anyPeers = knownPeers > 0;
        boolean seeding = complete && running;
        boolean activelySeeding = seeding && anyPeers && uploading;
        if (checking) {return StatusKind.CHECKING;}
        if (allocating) {return StatusKind.ALLOCATING;}
        if (trackerProblems) {return StatusKind.TRACKER_ERROR;}
        if (starting) {return StatusKind.STARTING;}
        if (activelySeeding) {return StatusKind.SEEDING_ACTIVE;}
        if (seeding && connected && !uploading) {return StatusKind.SEEDING_CONNECTED_IDLE;}
        if (!complete && connected && !uploading && !downloading) {return StatusKind.STALLED_CONNECTED_IDLE;}
        if (seeding) {return StatusKind.SEEDING_IDLE;}
        if (!running && complete) {return StatusKind.COMPLETE_STOPPED;}
        if (connected && downloading) {return StatusKind.DOWNLOADING;}
        if (!complete && connected) {return StatusKind.STALLED_INCOMPLETE_CONNECTED;}
        if (running && anyPeers && !connected) {return StatusKind.NOPEERS_CONNECTED;}
        if (running && !anyPeers) {return StatusKind.NOPEERS_UNKNOWN;}
        return StatusKind.STOPPED_DEFAULT;
    }

    /**
     * Generates HTML status string and status code for a Snark based on its state and peer info.
     * Classification is delegated to {@link #classifyStatus}; this method only renders.
     * @param snark the Snark instance
     * @param curPeers current connected peers
     * @param knownPeers total known peers
     * @param downBps download speed
     * @param upBps upload speed
     * @param isRunning running state
     * @param remaining data left to download
     * @param needed data needed for completion
     * @param noThinsp spacing control flag
     * @return StatusResult containing the status HTML and status keyword
     * @since 0.9.68+
    */
    private StatusResult buildStatusString(Snark snark, int curPeers, int knownPeers,
                                           long downBps, long upBps, boolean isRunning,
                                           long remaining, long needed, boolean noThinsp) {
        StringBuilder statusBuf = new StringBuilder(256);
        String snarkSt;

        boolean hasTrackerProblems = snark.getTrackerProblems() != null && isRunning && curPeers == 0
            && System.currentTimeMillis() - snark.getLastTrackerResponse() > 60 * 60 * 1000L;
        boolean isComplete = remaining == 0 || needed == 0;
        boolean isSeeding = isComplete && isRunning;
        boolean hasConnectedPeers = curPeers > 0;
        boolean hasPeers = knownPeers > 0;
        boolean isUploading = upBps > 0;
        boolean isDownloading = downBps > 0;
        boolean isActivelySeeding = isSeeding && hasPeers && isUploading;

        // Cache repeated peer count HTML once
        final String peerCountHtml = "</td><td class=peerCount><b><span class=right>" + curPeers + "</span>" + thinsp(noThinsp) + "<span class=left>" + knownPeers + "</span>";

        StatusKind kind = classifyStatus(snark.isAllocating(), snark.isChecking(), snark.isStarting(),
                                         hasTrackerProblems, isRunning, isComplete,
                                         curPeers, knownPeers, isUploading, isDownloading);
        switch (kind) {
            case CHECKING:
                appendIcon(statusBuf, "processing", "", _t("Checking"), false, true);
                statusBuf.append(peerCountHtml);
                snarkSt = "active starting processing";
                break;
            case ALLOCATING:
                appendIcon(statusBuf, "processing", "", _t("Allocating"), false, true);
                statusBuf.append("</td><td class=peerCount><b>");
                snarkSt = "active starting processing";
                break;
            case TRACKER_ERROR:
                String tooltip = _t("Failed to connect to all configured trackers");
                appendIcon(statusBuf, "error", "", tooltip, false, true);
                statusBuf.append(peerCountHtml);
                snarkSt = isComplete ? "inactive complete neterror" : "inactive downloading incomplete neterror";
                break;
            case STARTING:
                appendIcon(statusBuf, "stalled", "", _t("Starting"), false, true);
                statusBuf.append("</td><td class=peerCount><b>");
                snarkSt = "active starting";
                break;
            case SEEDING_ACTIVE:
                String seedTooltip = ngettext("Seeding to {0} peer", "Seeding to {0} peers", curPeers);
                appendIcon(statusBuf, "seeding_active", "", seedTooltip, false, true);
                statusBuf.append(peerCountHtml);
                snarkSt = "active seeding complete connected";
                break;
            case SEEDING_CONNECTED_IDLE:
                String idleTooltip = _t("Seeding") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")";
                statusBuf.append(toSVGWithDataTooltip("seeding", "", idleTooltip))
                    .append(peerCountHtml);
                snarkSt = "inactive seeding complete connected";
                break;
            case STALLED_CONNECTED_IDLE:
                String stalledTooltip = _t("Stalled") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")";
                statusBuf.append(toSVGWithDataTooltip("stalled", "", stalledTooltip))
                    .append(peerCountHtml);
                snarkSt = "inactive incomplete connected";
                break;
            case SEEDING_IDLE:
                String swarmTooltip = ngettext("Seeding to {0} peer in swarm", "Seeding to {0} peers in swarm", curPeers);
                appendIcon(statusBuf, "seeding", "", swarmTooltip, false, true);
                statusBuf.append(peerCountHtml);
                snarkSt = "inactive seeding complete";
                break;
            case COMPLETE_STOPPED:
                snarkSt = "inactive complete stopped";
                statusBuf.append(toSVGWithDataTooltip("complete", "", _t("Complete"))).append("</td><td class=peerCount><b>&mdash;");
                break;
            case DOWNLOADING:
                String downTooltip = _t("OK") + ", " + ngettext("Downloading from {0} peer", "Downloading from {0} peers", curPeers);
                statusBuf.append(toSVGWithDataTooltip("downloading", "", downTooltip))
                    .append(peerCountHtml);
                snarkSt = "active downloading incomplete connected";
                break;
            case STALLED_INCOMPLETE_CONNECTED:
                String stalled2 = _t("Stalled") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")";
                statusBuf.append(toSVGWithDataTooltip("stalled", "", stalled2))
                    .append(peerCountHtml);
                snarkSt = "inactive downloading incomplete connected";
                break;
            case NOPEERS_CONNECTED:
                String nopeersTooltip = _t("No Peers") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")";
                statusBuf.append(toSVGWithDataTooltip("nopeers", "", nopeersTooltip))
                    .append("</td><td class=peerCount><b><span class=right>0</span>")
                    .append(thinsp(noThinsp))
                    .append("<span class=\"left\">").append(knownPeers).append("</span>");
                snarkSt = "inactive downloading incomplete nopeers";
                break;
            case NOPEERS_UNKNOWN:
                statusBuf.append(toSVGWithDataTooltip("nopeers", "", _t("No Peers")))
                    .append(peerCountHtml);
                snarkSt = "inactive downloading incomplete nopeers zero";
                break;
            default:
                statusBuf.append(toSVGWithDataTooltip("stopped", "", _t("Stopped")))
                    .append("</td><td class=peerCount><b>&mdash;");
                snarkSt = "inactive incomplete stopped zero";
                break;
        }

        return new StatusResult(statusBuf.toString(), snarkSt);
    }

    /**
     * Encapsulates result of status building with HTML output and status keyword.
     */
    private static class StatusResult {
        final String statusHtml;
        final String snarkStatus;

        StatusResult(String statusHtml, String snarkStatus) {
            this.statusHtml = statusHtml;
            this.snarkStatus = snarkStatus;
        }
    }

    /**
     * Immutable context for rendering a peer row.
     * Package-visible for testing.
     *
     * @since 0.9.71+
     */
    static class PeerRowContext {
        final Snark snark;
        final MetaInfo meta;
        final boolean noThinsp;

        PeerRowContext(Snark snark, MetaInfo meta, boolean noThinsp) {
            this.snark = snark;
            this.meta = meta;
            this.noThinsp = noThinsp;
        }
    }

    /**
     * Peer status classification result.
     * Package-visible for testing.
     *
     * @since 0.9.71+
     */
    static class PeerStatus {
        final String status; // "active" or "inactive"
        final boolean isTx;
        final boolean isRx;

        PeerStatus(String status, boolean isTx, boolean isRx) {
            this.status = status;
            this.isTx = isTx;
            this.isRx = isRx;
        }
    }

    /**
     * Determines the peer status (active/inactive with TX/RX flags).
     *
     * @param peer the peer to check
     * @return peer status with flags
     * @since 0.9.71+
     */
    static PeerStatus classifyPeerStatus(Peer peer) {
        long t = peer.getInactiveTime();
        if ((peer.getUploadRate() > 0 || peer.getDownloadRate() > 0) && t < 60 * 1000) {
            boolean isTx = peer.getUploadRate() > 0 && !peer.isInteresting() && !peer.isChoking();
            boolean isRx = peer.getDownloadRate() > 0 && !peer.isInterested() && !peer.isChoked();
            return new PeerStatus("active", isTx, isRx);
        }
        return new PeerStatus("inactive", false, false);
    }

    /**
     * Appends HTML for a single peer row to the given StringBuilder.
     *
     * @param buf the StringBuilder to append to
     * @param peer the Peer object to render
     * @param snark the Snark (torrent) the peer belongs to
     * @param meta the MetaInfo of the torrent (may be null)
     * @param noThinsp whether to suppress thin space characters
     */
    private void appendPeerRow(StringBuilder buf, Peer peer, Snark snark, MetaInfo meta, boolean noThinsp) {
        PeerRowContext ctx = new PeerRowContext(snark, meta, noThinsp);
        PeerStatus ps = classifyPeerStatus(peer);

        if (!peer.isConnected()) {return;}

        buf.append("<tr class=\"peerinfo ")
           .append(ps.status)
           .append(" volatile\">\n<td class=status title=\"")
           .append(_t("Peer attached to swarm"))
           .append("\"></td><td class=peerdata colspan=5>");

        renderPeerIdentity(buf, peer, ctx);
        renderPeerInactivity(buf, peer.getInactiveTime());

        buf.append("</td><td class=ETA></td><td class=rxd>");
        float pct = renderPeerProgress(buf, peer, ctx);

        renderDownloadRate(buf, peer, ctx, ctx.meta != null ? ctx.snark.getNeededLength() : -1);

        renderUploadRate(buf, peer, ctx, pct, ctx.meta != null);

        buf.append("</td><td class=tAction></td></tr>\n");
    }

    /**
     * Renders the peer identity/client section.
     *
     * @param buf the StringBuilder to append to
     * @param peer the peer to render
     * @param ctx the rendering context
     * @since 0.9.71+
     */
    private void renderPeerIdentity(StringBuilder buf, Peer peer, PeerRowContext ctx) {
        PeerID pid = peer.getPeerID();
        String ch = pid != null ? pid.toString() : "????";
        if (ch.startsWith("WebSeed@")) {
            buf.append(ch);
        } else {
            String client = getClientName(peer);
            buf.append("<span class=peerclient><code title=\"")
               .append(_t("Destination (identity) of peer"))
               .append("\">")
               .append(peer.toString().substring(5, 9))
               .append("</code>&nbsp;<span class=clientid>")
               .append(client)
               .append("</span></span>");
        }
    }

    /**
     * Renders the peer inactivity bar if applicable.
     *
     * @param buf the StringBuilder to append to
     * @param inactiveTime the peer's inactive time in milliseconds
     * @since 0.9.71+
     */
    private void renderPeerInactivity(StringBuilder buf, long inactiveTime) {
        if (inactiveTime >= 5000) {
            buf.append("<span class=inactivity style=width:").append(inactiveTime / 2000)
               .append("px title=\"").append(_t("Inactive")).append(": ")
               .append(inactiveTime / 1000).append(' ').append(_t("seconds")).append("\"></span>");
        }
    }

    /**
     * Renders the peer progress bar or seed indicator.
     *
     * @param buf the StringBuilder to append to
     * @param peer the peer to render
     * @param ctx the rendering context
     * @return the completion percentage (101.0f if unknown)
     * @since 0.9.71+
     */
    private float renderPeerProgress(StringBuilder buf, Peer peer, PeerRowContext ctx) {
        float pct;
        boolean isValid = ctx.meta != null;
        if (isValid) {
            pct = (float) (100.0 * peer.completed() / ctx.meta.getPieces());
            if (pct >= 100.0) {
                buf.append("<span class=peerSeed title=\"")
                   .append(_t("Seed"))
                   .append("\">");
                appendIcon(buf, "peerseed", _t("Seed"), "", false, true);
                buf.append("</span>");
            } else {
                buf.append(buildProgressBar(100, (int) (100 - pct), true, false, ctx.noThinsp, false));
            }
        } else {
            pct = 101.0f;
        }
        return pct;
    }

    /**
     * Renders the download rate cell.
     *
     * @param buf the StringBuilder to append to
     * @param peer the peer to render
     * @param ctx the rendering context
     * @param needed the needed length
     * @since 0.9.71+
     */
    private void renderDownloadRate(StringBuilder buf, Peer peer, PeerRowContext ctx, long needed) {
        buf.append("</td><td class=\"rateDown");
        if (peer.getDownloadRate() >= 100000) {buf.append(" hundred");}
        else if (peer.getDownloadRate() >= 10000) {buf.append(" ten");}
        buf.append("\">");

        if (needed > 0) {
            if (peer.isInteresting() && !peer.isChoked() && peer.getDownloadRate() > 0) {
                buf.append("<span class=unchoked><span class=right>")
                   .append(formatSizeSpans(formatSize(peer.getDownloadRate()), false))
                   .append("/s</span></span>");
            } else if (peer.isInteresting() && !peer.isChoked()) {
                buf.append("<span class=\"unchoked idle\"></span>");
            } else {
                buf.append("<span class=choked title=\"");
                if (!peer.isInteresting()) {
                    buf.append(_t("Uninteresting (The peer has no pieces we need)"));
                } else {
                    buf.append(_t("Choked (The peer is not allowing us to request pieces)"));
                }
                buf.append("\"><span class=right>")
                   .append(formatSizeSpans(formatSize(peer.getDownloadRate()), false))
                   .append("/s</span></span>");
            }
        } else if (ctx.meta == null) {
            buf.append("<span class=unchoked><span class=right>")
               .append(formatSizeSpans(formatSize(peer.getDownloadRate()), false))
               .append("/s</span></span>");
        }
    }

    /**
     * Renders the upload rate cell.
     *
     * @param buf the StringBuilder to append to
     * @param peer the peer to render
     * @param ctx the rendering context
     * @param pct the completion percentage
     * @param isValid whether meta is valid
     * @since 0.9.71+
     */
    private void renderUploadRate(StringBuilder buf, Peer peer, PeerRowContext ctx, float pct, boolean isValid) {
        buf.append("</td><td class=txd></td><td class=\"rateUp");
        if (peer.getUploadRate() >= 100000) {buf.append(" hundred");}
        else if (peer.getUploadRate() >= 10000) {buf.append(" ten");}
        buf.append("\">");

        if (isValid && pct < 100.0) {
            if (peer.isInterested() && !peer.isChoking() && peer.getUploadRate() > 0) {
                buf.append("<span class=unchoked><span class=right>")
                   .append(formatSizeSpans(formatSize(peer.getUploadRate()), false))
                   .append("/s</span></span>");
            } else if (peer.isInterested() && !peer.isChoking()) {
                buf.append("<span class=\"unchoked idle\" title=\"")
                   .append(_t("Peer is interested but currently idle"))
                   .append("\"></span>");
            } else {
                buf.append("<span class=choked title=\"");
                if (!peer.isInterested()) {
                    buf.append(_t("Uninterested (We have no pieces the peer needs)"));
                } else {
                    buf.append(_t("Choking (We are not allowing the peer to request pieces)"));
                }
                buf.append("\"><span class=unchoked><span class=right>")
                   .append(formatSizeSpans(formatSize(peer.getUploadRate()), false))
                   .append("/s</span></span>");
            }
        }
    }

    /**
     * Returns a human-readable name of the client associated with the given Peer.
     *
     * &lt;p&gt;Most BitTorrent clients identify themselves using a 4-character prefix in the PeerID,
     * often based on the client's identifier in the BitTorrent protocol handshake.
     * These prefixes typically encode client-specific information (e.g., "-LT" for libtorrent,
     * "-qB" for qBittorrent).&lt;/p&gt;
     *
     * &lt;p&gt;This method maps known PeerID prefixes to client names using standardized conventions
     * and attempts to extract client names from the handshake data if no match is found.&lt;/p&gt;
     *
     * &lt;p&gt;Special cases:
     * &lt;ul&gt;
     *   &lt;li&gt;I2PSnark PeerID starts with "AwMD" (Base64 encoding of \3\3\3)&lt;/li&gt;
     *   &lt;li&gt;Handshake "v" field is used as a fallback to identify unknown clients&lt;/li&gt;
     * &lt;/ul&gt;
     *
     * @param peer The Peer object to analyze.
     * @return A string representing the detected client name, or "Unknown" if undetermined.
     */
    private String getClientName(Peer peer) {
        PeerID pid = peer.getPeerID();
        if (pid == null) {return "Unknown";}

        String ch = pid.toString().substring(0, 4); // First 4 chars of PeerID

        String known = ClientID.getClientName(ch);
        if (known != null) {return known;}

        // Try to extract client name from handshake "v" field
        Map<String, BEValue> handshake = peer.getHandshakeMap();
        if (handshake != null) {
            BEValue bev = handshake.get("v");
            if (bev != null) {
                try {
                    String s = bev.getString();
                    if (!s.isEmpty()) {return s.length() > 64 ? s.substring(0, 64) : s;}
                } catch (InvalidBEncodingException ignored) { /* ignored */ }
            }
        }

        // Fallback: return raw PeerID prefix if nothing else matched
        return ch;
    }

    /**
     * Formats a human-readable size string by wrapping unit characters (B, K, M, G, T)
     * in HTML span tags with CSS classes for styling.
     * &lt;p&gt;
     * This method removes any "iB" substring and replaces each unit character with a
     * corresponding closing and opening span tag sequence. The caller controls whether
     * the left span is closed immediately by the {@code closeLeftSpan} flag.
     *
     * @param formattedSize the size string already formatted (e.g. "123.4 MiB")
     * @param closeLeftSpan whether to append a closing </span> tag after each unit
     * @return the formatted string with added HTML span tags for styling unit characters
     *
     * @since 0.9.67+
     */
    private String formatSizeSpans(String formattedSize, boolean closeLeftSpan) {
        String closingTag = closeLeftSpan ? "</span>" : "";
        return formattedSize.replaceAll("iB", "")
                            .replace("B", "</span><span class=left>B" + closingTag)
                            .replace("K", "</span><span class=left>K" + closingTag)
                            .replace("M", "</span><span class=left>M" + closingTag)
                            .replace("G", "</span><span class=left>G" + closingTag)
                            .replace("T", "</span><span class=left>T" + closingTag);
    }

    /**
     *  Escape a string for embedding in an HTML attribute value that page
     *  scripts read back into JavaScript strings, e.g. the data-name and
     *  client attributes on the torrent action buttons. The backslash is
     *  escaped first so the generated escape sequences are not re-escaped,
     *  followed by the quote characters, the HTML-significant angle brackets
     *  and ampersand, and the line-break characters that cannot appear
     *  literally inside a JavaScript string literal. Output is ASCII-only.
     *
     *  Escapes are emitted as JS short escapes for line breaks and two-digit
     *  hex escapes for everything else; they are deliberately spelled without
     *  the four-hex-digit unicode form because java's lexer processes such
     *  sequences before string escapes, even inside this comment.
     *
     *  Not for use in URL or CSS contexts.
     *
     *  @param s non-null string to escape
     *  @return the escaped string, ASCII-only
     *  @since 0.9.15
     */
    static String escapeJSString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("<", "\\x3c")
                .replace(">", "\\x3e")
                .replace("\"", "\\x22")
                .replace("'", "\\x27")
                .replace("&", "\\x26");
    }

    /** @since 0.8.2 */
    private static String thinsp(boolean disable) {
        if (disable) {return " / ";}
        return ("&thinsp;/&thinsp;");
    }

    /**
     *  Sort by completeness (seeds first), then by ID
     *  @since 0.8.1
     */
    private static class PeerComparator implements Comparator<Peer>, Serializable {

        public int compare(Peer l, Peer r) {
            int diff = r.completed() - l.completed(); // reverse
            if (diff != 0) {return diff;}
            return l.toString().substring(5, 9).compareTo(r.toString().substring(5, 9));
        }
    }

    /**
     * Link to the details page if the tracker supports it.
     * Start of anchor only; caller must add anchor text or img and close the anchor.
     *
     * @return the tracker link url
     * @since 0.8.4
     */
    private String getTrackerLinkUrl(String announce, byte[] infohash) {
        // temporarily hardcoded for postman and torrfreedom, requires bytemonsoon patch or flyte for lookup by info_hash
        if (announce != null && (announce.startsWith("http://tracker2.postman.i2p/") || announce.startsWith("http://lnQ6yoBT") ||
              announce.startsWith("http://6a4kxkg5wp33p25qqhgwl6sj4yh4xuf5b3p3qldwgclebchm3eea.b32.i2p/") ||
              announce.startsWith("http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p/") ||
              announce.startsWith("http://torrfreedom.i2p/") || announce.startsWith("http://ZgNqT5tv") ||
              announce.startsWith("http://nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p/"))) {
            for (Tracker t : _manager.getTrackers()) {
                String aURL = t.announceURL;
                if (!(aURL.startsWith(announce) || // vvv hack for non-b64 announce in list vvv
                      (announce.startsWith("http://lnQ6yoBT") && aURL.startsWith("http://tracker2.postman.i2p/")) ||
                      (announce.startsWith("http://6a4kxkg5wp33p25qqhgwl6sj4yh4xuf5b3p3qldwgclebchm3eea.b32.i2p/") && aURL.startsWith("http://tracker2.postman.i2p/")) ||
                      (announce.startsWith("http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p/") && aURL.startsWith("http://tracker2.postman.i2p/")) ||
                      (announce.startsWith("http://ZgNqT5tv") && aURL.startsWith("http://torrfreedom.i2p/")) ||
                      (announce.startsWith("http://nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p/") && aURL.startsWith("http://torrfreedom.i2p/"))))
                    continue;
                String baseURL = urlEncode(t.baseURL);
                String name = DataHelper.escapeHTML(t.name);
                StringBuilder buf = new StringBuilder(128);
                buf.append("<a class=tLink href=\"").append(baseURL).append("details.php?info_hash=")
                   .append(TrackerClient.urlencode(infohash))
                   .append("\" title=\"").append(_t("Details at {0} tracker", name)).append("\" target=_blank>");
                return buf.toString();
            }
        }
        return null;
    }

    /**
     * Full link to the details page with an image.
     *
     * @return the tracker link
     * @since 0.8.4
     */
    private String getTrackerLink(String announce, byte[] infohash) {
        String linkUrl = getTrackerLinkUrl(announce, infohash);
        if (linkUrl != null) {
            StringBuilder buf = new StringBuilder(128);
            buf.append(linkUrl);
            appendIcon(buf, "link", _t("Info"), "", false, true);
            buf.append("</a>");
            return buf.toString();
        }
        return null;
    }

    /**
     * Full anchor to the home page or details page with shortened hostname as anchor text.
     *
     * @return the short tracker link
     * @since 0.9.5
     */
    private String getShortTrackerLink(String announce, byte[] infohash) {
        StringBuilder buf = new StringBuilder(128);
        String trackerLinkUrl = getTrackerLinkUrl(announce, infohash);
        boolean isUDP = false;

        if (announce.startsWith("http://")) {announce = announce.substring(7);}
        else if (announce.startsWith("https://")) {announce = announce.substring(8);}
        else if (announce.startsWith("udp://") && announce.contains(".i2p")) {
            announce = announce.substring(6);
            isUDP = true;
        }
        // strip path
        int slsh = announce.indexOf('/');
        if (slsh > 0) {announce = announce.substring(0, slsh);}
        if (trackerLinkUrl != null) {buf.append(trackerLinkUrl);}
        else if (isUDP) {
            // browsers don't like a full b64 dest, so convert it to b32
            String host = announce;
            if (host.length() >= 516) {
                int colon = announce.indexOf(':');
                String port = "";
                if (colon > 0) {
                    port = host.substring(colon);
                    host = host.substring(0, colon);
                }
                if (host.endsWith(".i2p")) {host = host.substring(0, host.length() - 4);}
                byte[] b = Base64.decode(host);
                if (b != null) {
                    Hash h = _context.sha().calculateHash(b);
                    // should we add the port back or strip it?
                    host = Base32.encode(h.getData()) + ".b32.i2p" + port;
                }
            }
            int space = host.indexOf(" ");
            if (space == -1) {space = host.length();}
            if (!host.endsWith("[ext]") || host.contains(".i2p")) {
                buf.append("<a href=\"http://").append(urlEncode(host)).append("/\" target=_blank>");
            } else {host = host.substring(0, space);}
        }
        // strip port
        int colon = announce.indexOf(':');
        if (colon > 0) {announce = announce.substring(0, colon);}
        if (isUDP) {announce = "UDP " + announce;}
        int end = Math.max(0, announce.length() - 8); // Ensure the end index is not negative
        if (announce.length() > 67) {
            announce = DataHelper.escapeHTML(announce.substring(0, 40)) + "&hellip;" +
                       DataHelper.escapeHTML(announce.substring(end));
        }
        if (announce.endsWith(".i2p") && !announce.endsWith(".b32.i2p")) {
            announce = announce.replace(".i2p", "");
            if (announce.equals("tracker2.postman")) {announce = "postman";}
            if (announce.startsWith("tracker.")) {announce = announce.substring(8, announce.length());}
            if (announce.startsWith("opentracker.")) {announce = announce.substring(12, announce.length());}
        }
        buf.append(announce);
        if (trackerLinkUrl != null) {buf.append("</a>");}
        return buf.toString();
    }

    /**
     * Writes the HTML form for adding new torrents via URL or file upload.
     *
     * @param out the PrintWriter to write the HTML output
     * @param req the HTTP request containing query parameters
     * @throws IOException if an I/O error occurs during writing
     */
    private void writeAddForm(PrintWriter out, HttpServletRequest req) throws IOException {
        // display incoming parameter if a GET so links will work
        StringBuilder buf = new StringBuilder(1024);
        String newURL = req.getParameter("nofilter_newURL");
        if (newURL == null || newURL.trim().length() <= 0 || req.getMethod().equals("POST")) {newURL = "";}
        else {newURL = DataHelper.stripHTML(newURL);} // XSS

        String addTop =
            "<div id=add class=snarkNewTorrent>\n" +
            "<form id=addForm action=_post method=POST enctype=multipart/form-data accept-charset=UTF-8 target=processForm>\n" +
            "<div class=sectionPanel id=addSection>\n";
        buf.append(addTop);
        writeHiddenInputs(buf, req, "Add");
        buf.append("<input hidden class=toggle_input id=toggle_addtorrent type=checkbox");
        if (!newURL.isEmpty()) {buf.append(" checked>");} // force toggle open
        else {buf.append('>');}
        buf.append("<label id=tab_addtorrent class=toggleview for=toggle_addtorrent><span class=tab_label>")
           .append(_t("Add Torrent")).append("</span></label><hr>\n<table border=0><tr><td class=right><span>")
           .append(_t("From URL")).append("</span>:</td><td class=left><input id=addTorrentURL type=text name=nofilter_newURL size=85 value=\"")
           .append(newURL).append("\" spellcheck=false title=\"").append(_t("Enter the torrent file download URL (I2P only), magnet link, or info hash"))
           .append("\" required>\n").append("<input type=submit class=add value=\"").append(_t("Add torrent"))
           .append("\" name=foo></td>\n<tr hidden><td class=right>").append(_t("Torrent file"))
           .append(":</td><td class=left><input type=file name=newFile accept=\".torrent\"></td><tr><td class=right><span>")
           .append(_t("Data dir")).append("</span>:</td><td class=left><input type=text name=nofilter_newDir size=85 value=\"")
           .append(_manager.getDataDir().getAbsolutePath()).append("\" spellcheck=false title=\"")
           .append(_t("Enter the directory to save the data in (default {0})", _manager.getDataDir().getAbsolutePath()))
           .append("\"></td></tr>\n</table>\n<div id=addNotify class=notify hidden><table><tr><td></td></tr></table></div>\n</div>\n</form>\n</div>\n");

        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     * Writes the HTML form for creating new torrents from local files.
     *
     * @param out the PrintWriter to write the HTML output
     * @param req the HTTP request containing query parameters
     * @param sortedTrackers the list of available trackers for selection
     * @param sortedFilters the list of available torrent create filters
     * @throws IOException if an I/O error occurs during writing
     */
    private void writeSeedForm(PrintWriter out, HttpServletRequest req, List<Tracker> sortedTrackers, List<TorrentCreateFilter> sortedFilters) throws IOException {
        StringBuilder buf = new StringBuilder(3*1024);
        buf.append("<div class=sectionPanel id=createSection>\n<div>\n<form id=createForm action=_post method=POST target=processForm>\n");
        writeHiddenInputs(buf, req, "Create");
        buf.append("<input hidden class=toggle_input id=toggle_createtorrent type=checkbox>")
           .append("<label id=tab_newtorrent class=toggleview for=toggle_createtorrent><span class=tab_label>").append(_t("Create Torrent"))
           .append("</span></label><hr>\n<table border=0><tr><td class=right><span>").append(_t("Data to seed"))
           .append("</span>:</td><td class=left><input id=createTorrentFile type=text name=nofilter_baseFile size=85 value=\"\" spellcheck=false title=\"")
           .append(_t("File or directory to seed (full path or within the directory {0} )", _manager.getDataDir().getAbsolutePath() + File.separatorChar))
           .append("\" required> <input type=submit class=create value=\"").append(_t("Create torrent")).append("\" name=foo></td></tr>\n")
           .append("<tr id=createTorrentFilters title=\"").append(_t("Exclude files from the torrent if they reside in the torrent folder")).append("\">")
           .append("<td class=right><span>").append(_t("Content Filters")).append("</span>:</td><td class=left><div id=contentFilter>");

        for (TorrentCreateFilter f : sortedFilters) {
           String name = f.name;
           String nameUnderscore = name.replace(" ", "_");
           String pattern = f.filterPattern;
           String type = f.filterType;
           String filterTypeLabel = type.replace("_", " ");
           boolean isDefault = f.isDefault;
           buf.append("<input type=checkbox id=").append(nameUnderscore).append(" name=filters value=\"").append(name)
              .append("\"").append(isDefault ? " checked" : "").append(" hidden><label for=").append(nameUnderscore)
              .append(" class=\"createFilterToggle ").append(type).append("\" title=\"Filter pattern: (")
              .append(filterTypeLabel).append(") ").append(pattern).append("\">").append(name).append("</label>");
        }

        buf.append("</div></td></tr>\n<tr><td><span>").append(_t("Trackers"))
           .append("</span>:</td><td>\n<table id=trackerselect>\n<tr><td>Name</td><td>")
           .append(_t("Primary")).append("</td><td>").append(_t("Alternates")).append("</td><td>")
           .append(_t("Tracker Type")).append("</td></tr>\n");

        for (Tracker t : sortedTrackers) {
            List<String> openTrackers = _manager.util().getOpenTrackers();
            List<String> privateTrackers = _manager.getPrivateTrackers();
            boolean isPrivate = privateTrackers.contains(t.announceURL);
            boolean isKnownOpen = _manager.util().isKnownOpenTracker(t.announceURL);
            boolean isOpen = isKnownOpen || openTrackers.contains(t.announceURL);
            String name = t.name;
            String announceURL = t.announceURL.replace("&#61;", "=");
            String homeURL = t.baseURL;
            buf.append("<tr><td><span class=trackerName>")
               .append("<a href=\"").append(homeURL).append("\" target=_blank>").append(name).append("</a>")
               .append("</span></td><td><input type=radio class=optbox name=announceURL value=\"").append(announceURL).append("\"");
            if (announceURL.equals(_lastAnnounceURL)) {buf.append(" checked");}
            buf.append("></td><td><input type=checkbox class=\"optbox slider\" name=\"backup_")
               .append(announceURL).append("\" value=\"foo\"></td><td>");

            if (!(isOpen || isPrivate)) {buf.append(_t("Standard"));}
            if (isOpen) {buf.append(_t("Open"));}
            if (isPrivate) {buf.append(_t("Private"));}
            buf.append("</td></tr>\n");
        }
        buf.append("<tr><td><i>").append(_t("none"))
           .append("</i></td><td><input type=radio class=optbox name=announceURL value=\"none\"");
        if (_lastAnnounceURL == null) {buf.append(" checked");}

        String createBottom =
            "></td><td></td><td></td></tr>\n</table>\n</td></tr>\n</table>\n</form>\n</div>\n" +
            "<div id=createNotify class=notify hidden><table><tr><td></td></tr></table></div>\n</div>\n";
        buf.append(createBottom);

        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     * Whether the given User-Agent header indicates a Firefox-family browser
     * (Firefox, LibreWolf, Waterfox, Pale Moon, Tor Browser), which can install
     * the I2PSnark Bridge extension. Unit-testable; other browsers get the
     * installer-script fallback instead.
     *
     * @param ua the User-Agent header, may be null
     * @return true if the UA names Firefox
     * @since 0.9.71+
     */
    public static boolean isFirefoxFamilyUserAgent(String ua) {
        return ua != null && ua.contains("Firefox");
    }

    /**
     * Whether the given User-Agent header indicates Windows, which gets the
     * PowerShell installer script instead of the shell one.
     *
     * @param ua the User-Agent header, may be null
     * @return true if the UA names Windows
     * @since 0.9.71+
     */
    public static boolean isWindowsUserAgent(String ua) {
        return ua != null && ua.contains("Windows");
    }

    /**
     * Add a torrent from a magnet URL.
     *
     * @param url in base32 or hex
     * @param dataDir null to default to snark data directory
     * @since 0.8.4
     */
    private void addMagnet(String url, File dataDir) {
        if (url.startsWith(MagnetURI.MAGNET_FULL_V2)) {
            _manager.addMessage(_t("Cannot add magnet: Version 2 magnet links are not supported"));
            return;
        }
        try {
            MagnetURI magnet = new MagnetURI(_manager.util(), url);
            String name = magnet.getName();
            byte[] ih = magnet.getInfoHash();
            String trackerURL = magnet.getTrackerURL();
            _manager.addMagnet(name, ih, trackerURL, true, dataDir);
        } catch (IllegalArgumentException iae) {
            _manager.addMessage(_t("Invalid magnet URL {0}", url));
        }
    }

    /** Translate a string. */
    String _t(String s) {return _manager.util().getString(s);}

    /** Translate a string with one argument. */
    String _t(String s, Object o) {return _manager.util().getString(s, o);}

    /** Translate a string with two arguments. */
    String _t(String s, Object o, Object o2) {return _manager.util().getString(s, o, o2);}

    /** Translate a pluralized string. @since 0.7.14 */
    String ngettext(String s, String p, int n) {return _manager.util().getString(n, s, p);}

    /** Format the file size. */
    private static String formatSize(long bytes) {return DataHelper.formatSize2(bytes) + 'B';}

    /**
     * This is for a full URL. For a path only, use encodePath().
     * @since 0.7.14
     */
    static String urlify(String s) {return urlify(s, 100);}

    /**
     * This is for a full URL. For a path only, use encodePath().
     * @since 0.9
     */
    static String urlify(String s, int max) {
        // browsers seem to work without doing this but let's be strict
        String link = urlEncode(s);
        String display;
        if (s.length() <= max) {
            if (link.startsWith("https")) {display = DataHelper.escapeHTML(link);}
            else {display = DataHelper.escapeHTML(link.replace("http://", ""));}
        } else {display = DataHelper.escapeHTML(s.substring(0, max)) + "&hellip;";}
        return "<a href=\"" + link + "\" target=_blank>" + display + "</a>";
    }

    /**
     * This is for a full URL. For a path only, use encodePath().
     * @since 0.8.13
     */
    private static String urlEncode(String s) {
        return s.replace(";", "%3B").replace("&", "&amp;").replace(" ", "%20")
                .replace("<", "%3C").replace(">", "%3E")
                .replace("[", "%5B").replace("]", "%5D");
    }

    private static final String DOCTYPE = "<!DOCTYPE HTML>\n";
    private static final String TABLE_HEADER = "<table id=torrents width=100% border=0>\n" + "<thead id=snarkHead>";

    /**
     * Generates a CSS link tag for the given filename.
     * @param filename the CSS filename (e.g., "snark.css")
     * @param themePath the theme path
     * @param attributes optional additional attributes (e.g., "id=snarkTheme")
     * @return complete link tag
     * @since 0.9.71+
     */
    private static String cssLink(String filename, String themePath, String... attributes) {
        StringBuilder buf = new StringBuilder(128);
        buf.append("<link href=\"").append(themePath).append(filename)
           .append("?").append(CoreVersion.VERSION).append("\" rel=stylesheet");
        for (String attr : attributes) {
            buf.append(' ').append(attr);
        }
        buf.append('>');
        return buf.toString();
    }

    private static final String FOOTER = "</div>\n<span id=endOfPage data-iframe-height></span>\n" +
        "<script src=/js/iframeResizer/iframeResizer.contentWindow.js id=iframeResizer type=module></script>\n" +
        "<script src=/js/iframeResizer/updatedEvent.js type=module></script>\n" +
        "<script src=/js/setupIframe.js type=module></script>\n" +
        "<script src=/js/detectPageZoom.js type=module></script>\n" +
        "<script src=/js/autologout.js></script>\n" +
        "<link rel=stylesheet href=/i2psnark/.res/snarkAlert.css>\n" +
        "</body>\n</html>";

    private static final String FOOTER_STANDALONE = "</div>\n" +
        "<script src=/i2psnark/.res/js/detectPageZoom.js type=module></script>\n" +
        "<link rel=stylesheet href=/i2psnark/.res/snarkAlert.css>\n" + "</body>\n</html>";

    private static final String IFRAME_FORM = "<iframe name=processForm id=processForm hidden></iframe>\n";

    /**
     * Minimum visible rows before a torrent-list page renders in streamed
     * mode. Torrent rows are heavy (badges, progress bars, ~1-2 KB each), so
     * this gate sits lower than the file-table gate.
     */
    private static final int STREAM_MIN_TORRENT_ROWS = 32;

    /**
     * Minimum visible rows before a directory file table renders in streamed
     * mode. File rows are lighter than torrent rows, so buffering stays
     * worthwhile up to a higher count; below the gate a single buffered write
     * keeps Content-Length on the directory page.
     */
    private static final int STREAM_MIN_FILE_ROWS = 64;

    /** Rows rendered per chunk in streamed mode; bounds the staging buffer near tens of KB. */
    private static final int STREAM_DRAIN_EVERY = 16;

    /**
     * Whether a torrent list rendering visibleRows entries uses streamed
     * output.
     *
     * @param visibleRows entries on the rendered page after filtering, non-negative
     * @return true once {@link #STREAM_MIN_TORRENT_ROWS} is reached
     */
    static boolean shouldStreamTorrentRows(int visibleRows) {
        return visibleRows >= STREAM_MIN_TORRENT_ROWS;
    }

    /**
     * Whether a directory file table rendering visibleRows entries uses
     * streamed output.
     *
     * @param visibleRows entries actually rendered after filtering, non-negative
     * @return true once {@link #STREAM_MIN_FILE_ROWS} is reached
     */
    static boolean shouldStreamFileRows(int visibleRows) {
        return visibleRows >= STREAM_MIN_FILE_ROWS;
    }

    /**
     * Pushes staged markup to the response writer and empties the buffer,
     * forcing bytes toward the client so large pages render progressively.
     * Called only in streamed mode; buffered mode never drains.
     *
     * @param out non-null response writer
     * @param buf staging buffer, emptied regardless of outcome
     * @return false if the client disconnected (writer error); callers should
     *         skip further drains, rendering continues only to keep counters
     *         and closing markup consistent
     */
    static boolean drainTo(PrintWriter out, StringBuilder buf) {
        out.append(buf);
        out.flush();
        buf.setLength(0);
        return !out.checkError();
    }

    /**
     * Modded heavily from the Jetty version in Resource.java,
     * pass Resource as 1st param
     * All the xxxResource constructors are package local so we can't extend them.
     *
     * Get the resource list as a HTML directory listing.
     *
     * Section map, in output order:
     * <ol>
     *   <li>POST dispatch - torrent actions (priorities, comments, stop/start/
     *       recheck, edit) via handleDirectoryPost(); P-R-G means no rendering
     *       after a POST</li>
     *   <li>renderHeader() - doctype, head, theme/font CSS, navbar</li>
     *   <li>form open, appendTorrentInfo(), displayTorrentEdit()</li>
     *   <li>appendResourceError() when the path does not exist on disk</li>
     *   <li>no-listing branch - appendMediaSection() player, comments section,
     *       footer, return</li>
     *   <li>file listing - wrapFileList() + sort, appendFileTableHead(),
     *       appendParentDirRow(), renderFileRow() loop, per-counter scripts;
     *       streamed when out != null and the table reaches
     *       STREAM_MIN_FILE_ROWS</li>
     *   <li>renderCommentsSection(), form close, lightbox/refresh scripts,
     *       footer</li>
     * </ol>
     *
     * @param xxxr The Resource unused
     * @param base The encoded base URL
     * @param parent True if the parent directory should be included
     * @param postParams map of POST parameters or null if not a POST
     * @param sortParam the file sort key from the request, or null
     * @param out the response writer for streamed mode, or null to buffer
     *            everything into the returned string
     * @return buffered mode: the full page; streamed mode: the tail remaining
     *         after the last drained chunk (possibly empty); null only when
     *         postParams != null (P-R-G)
     * @since 0.7.14
     */
    String getListHTML(File xxxr, String base, boolean parent, Map<String, String[]> postParams, String sortParam, PrintWriter out) throws IOException {
        String decodedBase = decodePath(base);
        String title = decodedBase;
        String cpath = _contextPath + '/';
        if (title.startsWith(cpath)) {title = title.substring(cpath.length());}

        // Get the snark associated with this directory
        String[] tNameAndPath = extractTorrentNameAndPath(title);
        String tName = tNameAndPath[0];
        String pathInTorrent = tNameAndPath[1];
        Snark snark = _manager.getTorrentByBaseName(tName);

        // caller must P-R-G
        if (snark != null && postParams != null) {
            handleDirectoryPost(snark, postParams);
            return null;
        }

        File r;
        if (snark != null) {
            Storage storage = snark.getStorage();
            if (storage != null) {
                r = resolveTorrentPath(storage, pathInTorrent);
            } else {r = new File("");} // magnet, dummy}
        } else {r = new File("");} // dummy

        boolean showStopStart = snark != null;
        Storage storage = snark != null ? snark.getStorage() : null;
        boolean showPriority = storage != null && !storage.complete() && r.isDirectory();

        final String directory = title.endsWith("/") ? title.substring(0, title.length() - 1) : title;
        final int dirSlash = directory.indexOf('/');
        final boolean isTopLevel = dirSlash <= 0;

        StringBuilder buf = new StringBuilder(6*1024);
        renderHeader(buf, directory);

        if (parent) {buf.append("<div class=page id=dirlist>\n");} // always true
         // for stop/start/check
        final boolean er = snark != null && _manager.util().ratingsEnabled();
        final boolean ec = snark != null && _manager.util().commentsEnabled(); // global setting
        final boolean esc = ec && _manager.getSavedCommentsEnabled(snark); // per-torrent setting
        final boolean includeForm = showStopStart || showPriority || er || ec;
        if (includeForm) {
            buf.append("<form action=\"").append(base).append("\" method=POST>\n")
               .append("<input type=hidden name=nonce value=\"").append(getNonce()).append("\">\n");
            if (sortParam != null) {
                buf.append("<input type=hidden name=sort value=\"").append(DataHelper.stripHTML(sortParam)).append("\">\n");
            }
        }

        appendTorrentInfo(buf, snark, base, tName, showStopStart);
        displayTorrentEdit(snark, base, buf);

        if (snark != null && !r.exists()) {
            appendResourceError(buf, r, base, tName);
            return buf.toString();
        }

        File[] ls = null;
        if (r.isDirectory()) {ls = storage != null ? storage.listMerged(pathInTorrent) : r.listFiles();} // if r is not a directory, we are only showing torrent info section
        if (ls == null) {
            // We are only showing the torrent info section unless audio or video...
            if (storage != null && storage.complete()) {
                String mime = getMimeType(r.getName());
                boolean isAudio = mime != null && isAudio(mime);
                boolean isVideo = !isAudio && mime != null && isVideo(mime);
                if (isAudio || isVideo) {
                    String imgPath = isStandalone() ? "/i2psnark/.res/icons/" : "/themes/console/images/";
                    appendMediaSection(buf, base, tName, mime, isAudio, imgPath);
                }
            }
            renderCommentsSection(snark, er, ec, esc, _t("Comments &amp; Ratings"), buf);
            if (includeForm) {buf.append("</form>\n");}
            buf.append(isStandalone() ? FOOTER_STANDALONE : FOOTER);
            return buf.toString();
        }

        // Precompute remaining for all files for efficiency
        long[][] arrays = (storage != null) ? storage.remaining2() : null;
        long[] remainingArray = (arrays != null) ? arrays[0] : null;
        long[] previewArray = (arrays != null) ? arrays[1] : null;
        List<Sorters.FileAndIndex> fileList = wrapFileList(ls, storage, remainingArray, previewArray, isTopLevel);

        boolean showSort = fileList.size() > 1;
        if (showSort) {
            int sort = sortParam != null ? I2PSnarkUtil.parseInt(sortParam, 0) : 0;
            DataHelper.sort(fileList, Sorters.getFileComparator(sort, this));
        }

        buf.append("<div class=mainsection id=snarkFiles>")
           .append("<input hidden class=toggle_input id=toggle_files type=checkbox");
        // don't collapse file view if not in torrent root
        if (!isTopLevel || fileList.size() <= 10 || sortParam != null) {buf.append(" checked");}
        buf.append(">")
           .append("<label id=tab_files class=toggleview for=toggle_files><span class=tab_label>")
           .append(_t("Files"))
           .append("</span></label><hr>\n")
           .append("<table id=dirInfo>\n<thead>\n<tr>\n<th colspan=2>");
        appendFileTableHead(buf, base, directory, dirSlash, isTopLevel, showSort, showPriority, sortParam);
        buf.append("</th></tr></thead>\n<tbody>");
        // playlist check reads Storage metadata; no filesystem walk
        boolean hasAudio = hasCompleteAudio(storage, remainingArray, pathInTorrent);
        if (!isTopLevel || hasAudio) { // don't show row if top level or no playlist
            appendParentDirRow(buf, base, sortParam, decodedBase, isTopLevel, hasAudio, showPriority);
        }

        // Threshold-gated streaming: scaffold through dirNav goes out at once,
        // then rows drain every STREAM_DRAIN_EVERY so peak memory stays near
        // one chunk instead of the whole page.
        final boolean streamed = out != null && shouldStreamFileRows(fileList.size());
        int untilDrain = STREAM_DRAIN_EVERY;
        // false once the client disconnects; further drains are skipped while
        // rendering continues so counters and closing markup stay consistent
        boolean drainOk = streamed;
        if (streamed) {drainOk = drainTo(out, buf);}

        FileRowContext ctx = new FileRowContext(decodedBase, storage, showPriority, isTopLevel);
        FileRowCounters counters = new FileRowCounters();
        boolean rowEven = true;
        try {
            for (Sorters.FileAndIndex fai : fileList) {
                rowEven = renderFileRow(buf, ctx, fai, rowEven, counters);
                if (drainOk && --untilDrain == 0) {
                    drainOk = drainTo(out, buf);
                    untilDrain = STREAM_DRAIN_EVERY;
                }
            }
        } catch (RuntimeException e) {
            // buffered mode keeps pre-streaming semantics: nothing is committed,
            // so propagate and let the container render its error page
            if (!streamed) {throw e;}
            // otherwise committed - skip remaining rows rather than lose the
            // page; closing markup below always renders
            if (_log.shouldWarn()) {_log.warn("File list render aborted after error", e);}
        }
        if (counters.showSaveButton) {
            buf.append("</tbody>\n<thead><tr id=setPriority><th colspan=")
               .append(showPriority ? '5' : '4')
               .append("><input type=submit class=accept value=\"")
               .append(_t("Save priorities"))
               .append("\" name=savepri>\n</th></tr></thead>\n");
        }
        buf.append("</table>\n</div>\n");
        if (counters.imgCount > 0) {buf.append("<script src=").append(_resourcePath).append("js/getImgDimensions.js></script>\n");}
        if (counters.txtCount > 0) {buf.append("<script src=").append(_resourcePath).append("js/textView.js></script>\n");}
        buf.append("<script src=").append(_resourcePath).append("js/togglePriorities.js></script>\n");
        buf.append("<script src=").append(_resourcePath).append("js/togglePanels.js type=module></script>\n");

        renderCommentsSection(snark, er, ec, esc, _t("Comments"), buf);

        // for stop/start/check
        if (includeForm) {buf.append("</form>\n");}
        boolean enableLightbox = _manager.util().enableLightbox();
        if (enableLightbox) {
            buf.append("<link rel=stylesheet href=").append(_resourcePath).append("lightbox.css>\n")
               .append("<script nonce=").append(cspNonce).append(" type=module>\n")
               .append("  import {Lightbox} from \"").append(_resourcePath).append("js/lightbox.js\";\n")
               .append("  var lightbox = new Lightbox();lightbox.load();\n")
               .append("</script>\n");
        }
        int delay = _manager.getRefreshDelaySeconds();
        buf.append("<script nonce=").append(cspNonce).append(" type=module>\n")
           .append("  window.snarkRefreshDelay = ").append(delay).append(";\n")
           .append("  import {initSnarkRefresh} from \"").append(_resourcePath).append("js/refreshTorrents.js\";\n")
           .append("  document.addEventListener(\"DOMContentLoaded\", initSnarkRefresh, true);\n")
           .append("</script>\n");
        if (!isStandalone()) {buf.append(FOOTER);}
        else {buf.append(FOOTER_STANDALONE);}
        return buf.toString();
    }

    /**
     * Dispatch a POST on the torrent directory page to its action, validating
     * the anti-CSRF nonce first. Never renders; the caller responds with a
     * redirect (P-R-G). A missing nonce is ignored silently; an invalid nonce
     * or an unrecognized command adds a console message instead.
     * Extracted from getListHTML.
     *
     * @param snark non-null target torrent
     * @param postParams the POST parameter map, non-null
     * @since 0.9.71+
     */
    private void handleDirectoryPost(Snark snark, Map<String, String[]> postParams) {
        String[] val = postParams.get("nonce");
        if (val == null) {return;}
        if (!isValidNonce(val[0])) {
            _manager.addMessage(_t("Please retry form submission (bad nonce)"));
            return;
        }
        String action = findPostAction(postParams);
        if (action != null) {
            executeDirectoryPostAction(snark, postParams, action);
        } else {
            _manager.addMessage(_t("Unknown command"));
        }
    }

    /** Command keys accepted by {@link #executeDirectoryPostAction}, in precedence order. */
    private static final String[] DIRECTORY_POST_ACTIONS = {
        "savepri", "addComment", "deleteComments", "setCommentsEnabled",
        "stop", "start", "recheck", "editTorrent", "setInOrderEnabled"
    };

    /**
     * Returns the first matching command key from a directory-page POST, in
     * the fixed precedence order of {@link #DIRECTORY_POST_ACTIONS}.
     * Static and side-effect-free for testability.
     *
     * @param postParams the POST parameter map, non-null
     * @return the matched action key, or null if none is present
     * @since 0.9.71+
     */
    static String findPostAction(Map<String, String[]> postParams) {
        for (String action : DIRECTORY_POST_ACTIONS) {
            if (postParams.get(action) != null) {return action;}
        }
        return null;
    }

    /**
     * Performs the side effects of a validated directory-page POST command.
     *
     * @param snark non-null target torrent
     * @param postParams the full POST parameter map, needed by form handlers
     * @param action a key returned by {@link #findPostAction}, non-null
     * @since 0.9.71+
     */
    private void executeDirectoryPostAction(Snark snark, Map<String, String[]> postParams, String action) {
        switch (action) {
            case "savepri": savePriorities(snark, postParams); break;
            case "addComment": saveComments(snark, postParams); break;
            case "deleteComments": deleteComments(snark, postParams); break;
            case "setCommentsEnabled": saveCommentsSetting(snark, postParams); break;
            case "stop": _manager.stopTorrent(snark, false); break;
            case "start": _manager.startTorrent(snark); break;
            case "recheck": _manager.recheckTorrent(snark); break;
            case "editTorrent": saveTorrentEdit(snark, postParams); break;
            case "setInOrderEnabled":
                _manager.saveTorrentStatus(snark);
                _manager.addMessage(_t("Sequential piece or file order not saved - feature currently broken."));
                break;
            default: break; // unreachable - findPostAction returns listed keys only
        }
    }

    /**
     * Renders the doctype, HTML head (theme and font CSS, CSP'd theme script,
     * favicon) and the navbar. All inputs come from servlet state so page
     * assembly stays allocation-light; only the directory display name is
     * passed in. Extracted from getListHTML.
     *
     * @param buf target buffer
     * @param directory decoded directory display name for the page title,
     *                  escaped here; may be empty
     * @since 0.9.71+
     */
    private void renderHeader(StringBuilder buf, String directory) {
        String theme = _manager.getTheme();
        boolean standalone = isStandalone();
        buf.append(DOCTYPE).append("<html").append(standalone ? " class=standalone" : "").append(">\n")
           .append("<head>\n<meta charset=utf-8>\n").append("<title>")
           .append(_t("I2PSnark")).append(" - [").append(_t("Torrent")).append(": ")
           .append(DataHelper.escapeHTML(directory)).append("]")
           .append("</title>\n").append(cssLink("snark.css", _themePath, "id=snarkTheme")).append("\n");

        if (!_manager.util().collapsePanels()) {buf.append(cssLink("nocollapse.css", _themePath)).append("\n");}
        String lang = Translate.getLanguage(_manager.util().getContext());
        if (lang.equals("zh") || lang.equals("ja") || lang.equals("ko")) {
            buf.append(cssLink("snark_big.css", _themePath)).append("\n"); // larger fonts for cjk translations
        }
        buf.append(cssLink("images/images.css", _themePath)).append("\n"); // images.css

        String fontPath = standalone ? "/i2psnark/.res/themes/fonts/" : "/themes/fonts/";
        if (standalone || useSoraFont()) {
            buf.append("<link rel=stylesheet href=").append(fontPath).append("Sora.css>\n");
        } else {
            buf.append("<link rel=stylesheet href=").append(fontPath).append("OpenSans.css>\n");
        }
        if (!standalone) {
            File override = new File(I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() +
                                     File.separatorChar + "docs" + File.separatorChar + "themes" +
                                     File.separatorChar + "snark" + File.separatorChar + theme +
                                     File.separatorChar + "override.css");
            if (override.exists()) {
                buf.append(cssLink("override.css", _themePath)).append('\n'); // version-persistent user edits
            }
        }

        buf.append("<script nonce=").append(cspNonce).append(">const theme = \"").append(theme).append("\";</script>\n")
           .append("<noscript><style>.script{display:none}</style></noscript>\n") // hide javascript-dependent buttons when js is unavailable
           .append("<link rel=\"shortcut icon\" href=\"")
           .append(_contextPath).append(WARBASE)
           .append("icons/favicon.svg\">\n</head>\n<body style=display:none;pointer-events:none class=\"")
           .append(theme)
           .append(" lang_")
           .append(lang)
           .append("\">\n<div id=navbar><a href=")
           .append(_contextPath)
           .append("/ title=")
           .append(_t("Torrents"))
           .append(" id=nav_main class=snarkNav>")
           .append(_contextName.equals(DEFAULT_NAME) ? _t("I2PSnark") : _contextName).append("</a>")
           .append("<a href=")
           .append(_contextPath)
           .append("/configure id=nav_config class=snarkNav>")
           .append(_t("Configure"))
           .append("</a></div>\n");
    }

    /**
     * Renders the error table shown when the requested path within the torrent
     * does not exist on disk. Extracted from getListHTML.
     *
     * @param buf target buffer
     * @param r the missing resource path
     * @param base the encoded base URL
     * @param tName the torrent name, escaped here
     * @since 0.9.71+
     */
    private void appendResourceError(StringBuilder buf, File r, String base, String tName) {
        // fixup TODO
        buf.append("<table class=resourceError id=DoesNotExist>\n<tr><th colspan=2>")
           .append(_t("Resource Does Not Exist"))
           .append("</th></tr><tr><td><b>").append(_t("Resource")).append(":</b></td><td>").append(r.toString())
           .append("</td></tr><tr><td><b>").append(_t("Base")).append(":</b></td><td>").append(base)
           .append("</td></tr><tr><td><b>").append(_t("Torrent")).append(":</b></td><td>").append(DataHelper.escapeHTML(tName))
           .append("</td></tr>\n</table>");
    }

    /**
     * Renders the HTML5 audio/video player section for a complete single-media
     * torrent. Static: no servlet state required beyond the parameters.
     * Extracted from getListHTML.
     *
     * @param buf target buffer
     * @param base the encoded base URL with trailing slash
     * @param tName the torrent name, escaped here
     * @param mime the non-null media MIME type
     * @param isAudio true for audio, false for video
     * @param imgPath web-visible path to the console icons
     * @since 0.9.71+
     */
    private static void appendMediaSection(StringBuilder buf, String base, String tName,
                                           String mime, boolean isAudio, String imgPath) {
        String path = base.substring(0, base.length() - 1); // strip trailing slash
        String newTab = "<img src=" + imgPath + "newtab.svg width=16 height=auto class=newTab>";
        buf.append("<div class=mainsection id=media>\n<table id=mediaContainer>\n<tr>");
        // HTML5
        if (isAudio) {
            buf.append("<th class=audio>")
               .append(DataHelper.escapeHTML(tName))
               .append("<a href=\"")
               .append(path)
               .append("\" title=\"Open in new tab\" target=_blank>")
               .append(newTab)
               .append("</a></th></tr>\n<tr><td><audio controls>");
        } else {
            buf.append("<th id=videoTitle class=video>")
               .append(DataHelper.escapeHTML(tName))
               .append("<a href=\"")
               .append(path)
               .append("\" title=\"Open in new tab\" target=_blank>")
               .append(newTab)
               .append("</a></th></tr>\n<tr><td><video id=embedVideo controls>");
        }
        buf.append("<source src=\"").append(path).append("\" type=\"").append(mime).append("\">");
        if (isAudio) {buf.append("</audio>");}
        else {buf.append("</video>");}
        buf.append("</td></tr>\n</table>\n</div>\n");
    }

    /**
     * Whether a filesystem entry is a legacy padding directory that should be
     * hidden in top-level listings. Old versions created ".pad"/"_pad"
     * directories; Storage no longer does, but they may still exist on disk.
     * Extracted from getListHTML for testability.
     *
     * @param f the entry, non-null
     * @return true if it is a padding directory
     * @since 0.9.71+
     */
    static boolean isPaddingDir(File f) {
        if (!f.isDirectory()) {return false;}
        String n = f.getName();
        return n.equals(".pad") || n.equals("_pad");
    }

    /**
     * Wraps a raw directory listing in Sorters.FileAndIndex entries carrying
     * the precomputed per-file remaining/preview arrays, optionally hiding
     * legacy padding directories. Pure wrapping; sorting is the caller's job.
     * Extracted from getListHTML for testability.
     *
     * @param ls the raw listing, non-null
     * @param storage may be null (magnet/dummy); arrays must then be null too
     * @param remainingArray precomputed by Storage.remaining2(), null iff storage is null
     * @param previewArray may be null
     * @param skipPaddingDirs true hides ".pad"/"_pad" directories
     * @return wrapped entries, possibly empty, never null
     * @since 0.9.71+
     */
    static List<Sorters.FileAndIndex> wrapFileList(File[] ls, Storage storage,
            long[] remainingArray, long[] previewArray, boolean skipPaddingDirs) {
        List<Sorters.FileAndIndex> rv = new ArrayList<>(ls.length);
        for (File f : ls) {
            if (skipPaddingDirs && isPaddingDir(f)) {continue;}
            rv.add(new Sorters.FileAndIndex(f, storage, remainingArray, previewArray));
        }
        return rv;
    }

    /**
     * Next value of the name/file-type sort cycle used by the Directory
     * column header: unset/0/1 sorts name descending ("-1"), which offers the
     * type sort ("12"), whose reverse ("-12") restarts unsorted ("").
     * Must stay consistent with {@link #isTypeSortNext(String)}.
     * Extracted from getListHTML for testability.
     *
     * @param sortParam current sort key from the request, may be null
     * @return the next sort key, possibly empty, never null
     * @since 0.9.71+
     */
    static String nextNameTypeSort(String sortParam) {
        if (sortParam == null || "0".equals(sortParam) || "1".equals(sortParam)) {return "-1";}
        if ("-1".equals(sortParam)) {return "12";}
        if ("12".equals(sortParam)) {return "-12";}
        return "";
    }

    /**
     * Whether the Directory column's next sort target is the file type,
     * i.e. the current key sits in the name half ("-1"/"12") of the cycle.
     * Drives only the link tooltip; must stay consistent with
     * {@link #nextNameTypeSort(String)}.
     *
     * @param sortParam current sort key, may be null
     * @return true if the tooltip should advertise type sorting
     * @since 0.9.71+
     */
    static boolean isTypeSortNext(String sortParam) {
        return "-1".equals(sortParam) || "12".equals(sortParam);
    }

    /**
     * Appends an icon-only column-header link that toggles to nextSort on
     * click. Shared by all sortable columns of the file table; dedupes four
     * identical link/icon sequences in getListHTML.
     *
     * @param buf target buffer
     * @param base the encoded base URL
     * @param icon icon name (without extension)
     * @param label accessible text for the icon
     * @param tooltip hover text
     * @param nextSort precomputed next sort key
     * @param id anchor id, or null for none
     * @since 0.9.71+
     */
    private void appendSortToggleLink(StringBuilder buf, String base, String icon, String label,
                                      String tooltip, String nextSort, String id) {
        buf.append("<a");
        if (id != null) {buf.append(" id=").append(id);}
        buf.append(" href=\"").append(base).append(sortQueryString(nextSort)).append("\">");
        appendIcon(buf, icon, label, tooltip, true, false);
        buf.append("</a>");
    }

    /**
     * Renders the sortable column headers of the files table: Directory/
     * Name-type, Size, Download Status (only when priorities are shown) and
     * Download Priority (same condition). Non-sortable state falls back to
     * bare icons with descriptive tooltips. Extracted from getListHTML.
     *
     * @param buf target buffer, positioned inside the first &lt;th&gt;
     * @param base the encoded base URL
     * @param directory decoded directory display name
     * @param dirSlash index of '/' in directory (-1 at top level)
     * @param isTopLevel true in the torrent root
     * @param showSort true when more than one entry is listed
     * @param showPriority true when priority controls are shown
     * @param sortParam current sort key, may be null
     * @since 0.9.71+
     */
    private void appendFileTableHead(StringBuilder buf, String base, String directory, int dirSlash,
                                     boolean isTopLevel, boolean showSort, boolean showPriority,
                                     String sortParam) {
        String tx = _t("Directory");
        // cycle through sort by name or type
        // TODO: add "(ascending") or "(descending") suffix to tooltip to indicate direction of sort
        if (showSort) {
            boolean typeNext = isTypeSortNext(sortParam);
            appendSortToggleLink(buf, base, "file", tx,
                                 _t("Sort by {0}", typeNext ? _t("File type") : _t("Name")),
                                 nextNameTypeSort(sortParam), null);
        } else {
            appendIcon(buf, "file", tx, tx + ": " + directory, true, false);
        }
        if (!isTopLevel) {
            buf.append("&nbsp;").append(DataHelper.escapeHTML(directory.substring(dirSlash + 1)));
        }
        buf.append("</th><th class=fileSize>");
        tx = _t("Size");
        if (showSort) {
            appendSortToggleLink(buf, base, "size", tx, _t("Sort by {0}", tx),
                                 "-5".equals(sortParam) ? "5" : "-5", null);
        } else {
            appendIcon(buf, "size", tx, tx, true, false);
        }
        buf.append("</th><th class=fileStatus>");
        boolean showRemainingSort = showSort && showPriority;
        tx = _t("Download Status");
        if (showRemainingSort) {
            appendSortToggleLink(buf, base, "status", tx, _t("Sort by {0}", _t("Remaining")),
                                 "10".equals(sortParam) ? "-10" : "10", "sortRemaining");
        } else {
            appendIcon(buf, "status", tx, tx, true, false);
        }
        if (showPriority) {
            buf.append("</th><th class=\"priority volatile\">");
            tx = _t("Download Priority");
            if (showSort) {
                appendSortToggleLink(buf, base, "priority", tx, _t("Sort by {0}", tx),
                                     "13".equals(sortParam) ? "-13" : "13", null);
            } else {
                appendIcon(buf, "priority", tx, tx, true, false);
            }
        }
    }

    /**
     * Renders the dirNav row below the column headers: the parent-directory
     * link (subdirectories only) and the audio-playlist button when complete
     * audio exists at or below this point. The row itself is emitted only when
     * at least one of the two applies; the gate lives in getListHTML.
     * hasCompleteAudio is evaluated once per view and passed in, replacing two
     * recursive walks. Extracted from getListHTML.
     *
     * @param buf target buffer
     * @param base the encoded base URL with trailing slash
     * @param sortParam current sort key, may be null
     * @param decodedBase the decoded base URL
     * @param isTopLevel true in the torrent root (no parent link)
     * @param hasAudio result of hasCompleteAudio(fileList, ...)
     * @param showPriority true when the priority column shifts colspans
     * @since 0.9.71+
     */
    private void appendParentDirRow(StringBuilder buf, String base, String sortParam,
                                    String decodedBase, boolean isTopLevel, boolean hasAudio,
                                    boolean showPriority) {
        buf.append("<tr id=dirNav><td colspan=").append(showPriority ? '3' : '2').append(" class=ParentDir>");
        if (!isTopLevel) { // don't show parent dir link if top level
            buf.append("<a href=\"");
            URIUtil.encodePath(buf, addPaths(decodedBase,"../"));
            buf.append("/").append("\">");
            appendIcon(buf, _t("up"), "", "", true, true);
            buf.append(' ').append(_t("Parent directory")).append("</a>");
        }

        buf.append("</td><td colspan=2 class=\"ParentDir playlist\">");
        // playlist button
        if (hasAudio) {
            buf.append("<a href=\"").append(base).append("?playlist");
            if (sortParam != null && !"0".equals(sortParam) && !"1".equals(sortParam)) {
                // sortParam is raw from the request - never reflect it unstripped
                buf.append("&amp;sort=").append(DataHelper.stripHTML(sortParam));
            }
            buf.append("\">");
            appendIcon(buf, "playlist", "", _t("Audio Playlist"), false, true);
            buf.append(' ').append(_t("Audio Playlist")).append("</a>");
        }
        buf.append("</td></tr>\n");
    }

    /**
     * Renders the collapsible comments/ratings section (opener div, toggle,
     * tab label, body, close) when ratings or comments are enabled globally;
     * renders nothing otherwise. Shared by both return paths of getListHTML.
     * Previously the main path emitted stray markup outside the section and
     * forced a comment-file load even with the feature disabled.
     *
     * @param snark non-null torrent
     * @param er ratings enabled globally
     * @param ec comments enabled globally
     * @param esc comments enabled for this torrent
     * @param tabLabel translated label for the section tab
     * @param buf target buffer
     * @since 0.9.71+
     */
    private void renderCommentsSection(Snark snark, boolean er, boolean ec, boolean esc,
                                       String tabLabel, StringBuilder buf) {
        if (!er && !ec) {return;}
        CommentSet comments = snark.getComments();
        buf.append("<div class=mainsection id=commentSection>\n")
           .append("<input hidden class=toggle_input id=toggle_comments type=checkbox");
        if (comments != null && !comments.isEmpty()) {buf.append(" checked");}
        buf.append(">\n<label id=tab_comments class=toggleview for=toggle_comments><span class=tab_label>")
           .append(tabLabel)
           .append("</span></label><hr>\n");
        displayComments(snark, er, ec, esc, buf);
        buf.append("</div>\n");
    }

    /** Known postman tracker base64 announce, both old and new. */
    private static final String POSTMAN_B64 =
            "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICkEbKUqJ9mPYQlTSujhNxiRIW-oLwMtvayCFci99oX8MvazPS7~97x0Gsm-onEK1Td9nBdmq30OqDxpRtXBimbzkLbR1IKObbg9HvrKs3L-kSyGwTUmHG9rSQSoZEvFMA-S0EXO~o4g21q1oikmxPMhkeVwQ22VHB0-LZJfmLr4SAAAA";
    private static final String POSTMAN_B64_NEW =
            "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICrsRuil8qK~whOvj8uNTv~ohZnTZHxTLgi~sDyo98BwJ-4Y4NMSuF4GLzcgLypcR1D1WY2tDqMKRYFVyLE~MTPVjRRgXfcKolykQ666~Go~A~~CNV4qc~zlO6F4bsUhVZDU7WJ7mxCAwqaMiJsL-NgIkb~SMHNxIzaE~oy0agHJMBQAEAAcAAA==";
    /** Known tracker base32 announces. */
    private static final String BT_B32 = "ev5dpxvcmshi6mil7gaon3b2wbplwylzraxs4wtz7dd5lzdsc2dq.b32.i2p";
    private static final String CHUDO_B32 = "swhb5i7wcjcohmus3gbt3w6du6pmvl3isdvxvepuhdxxkfbzao6q.b32.i2p";
    private static final String CRYPT_B32 = "ri5a27ioqd4vkik72fawbcryglkmwyy4726uu5j3eg6zqh2jswfq.b32.i2p";
    private static final String FREEDOM_B32 = "nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p";
    private static final String ICU812_B32 = "h77hk3pr622mx5c6qmybvbtrdo5la7pxo6my4kzr47x2mlpnvm2a.b32.i2p";
    private static final String LODIKON_B32 = "q2a7tqlyddbyhxhtuia4bmtqpohpp266wsnrkm6cgoahdqrjo3ra.b32.i2p";
    private static final String LYOKO_B32 = "afuuortfaqejkesne272krqvmafn65mhls6nvcwv3t7l2ic2p4kq.b32.i2p";
    private static final String ODIFT_B32 = "bikpeyxci4zuyy36eau5ycw665dplun4yxamn7vmsastejdqtfoq.b32.i2p";
    private static final String OMITRACK_B32 = "a5ruhsktpdhfk5w46i6yf6oqovgdlyzty7ku6t5yrrpf4qedznjq.b32.i2p";
    private static final String OTDG_B32 = "w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p";
    private static final String POSTMAN_B32_NEW = "6a4kxkg5wp33p25qqhgwl6sj4yh4xuf5b3p3qldwgclebchm3eea.b32.i2p";
    private static final String R4SAS_B32 = "punzipidirfqspstvzpj6gb4tkuykqp6quurj6e23bgxcxhdoe7q.b32.i2p";
    private static final String SKANK_B32 = "by7luzwhx733fhc5ug2o75dcaunblq2ztlshzd7qvptaoa73nqua.b32.i2p";
    private static final String SIMP_B32 = "wc4sciqgkceddn6twerzkfod6p2npm733p7z3zwsjfzhc4yulita.b32.i2p";
    private static final String THEBLAND_B32 = "s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p";
    private static final String SIGMA_B32 = "qimlze77z7w32lx2ntnwkuqslrzlsqy7774v3urueuarafyqik5a.b32.i2p";

    /**
     * Known tracker announce keys (base64 postman blobs and base32 hostnames)
     * mapped to their display names. Package-visible for testing.
     * Matched literally - entries are plain strings, not regexes.
     */
    static final String[][] KNOWN_TRACKERS = {
        {POSTMAN_B64, "tracker2.postman.i2p"},
        {POSTMAN_B64_NEW, "tracker2.postman.i2p"},
        {POSTMAN_B32_NEW, "tracker2.postman.i2p"},
        {BT_B32, "opentracker.bt.i2p"},
        {CHUDO_B32, "tracker.chudo.i2p"},
        {CRYPT_B32, "tracker.crypthost.i2p"},
        {FREEDOM_B32, "torrfreedom.i2p"},
        {ICU812_B32, "tracker.icu812.i2p"},
        {LODIKON_B32, "tracker.lodikon.i2p"},
        {LYOKO_B32, "lyoko.i2p"},
        {ODIFT_B32, "opendiftracker.i2p"},
        {OMITRACK_B32, "omitracker.i2p"},
        {OTDG_B32, "opentracker.dg2.i2p"},
        {R4SAS_B32, "opentracker.r4sas.i2p"},
        {SKANK_B32, "opentracker.skank.i2p"},
        {SIMP_B32, "opentracker.simp.i2p"},
        {THEBLAND_B32, "tracker.thebland.i2p"},
        {SIGMA_B32, "sigmatracker.i2p"}
    };

    /**
     * Rewrite known tracker b64 and b32 announces to their domain names for
     * display. Literal matching only; no per-call regex compilation, and
     * unmatched announces pass through allocation-free.
     *
     * @param announce the raw announce URL, non-null
     * @return the rewritten URL
     */
    static String prettyAnnounce(String announce) {
        String s = DataHelper.stripHTML(announce);
        for (String[] tracker : KNOWN_TRACKERS) {
            if (s.contains(tracker[0])) {s = s.replace(tracker[0], tracker[1]);}
        }
        return s;
    }

    /**
     * Appends detailed torrent information as HTML to the provided StringBuilder buffer.
     * <p>
     * This includes torrent metadata such as torrent name, size, pieces, trackers,
     * web seeds, completion status, download speed, share ratio, and control buttons.
     * It handles cases where the torrent (snark) or metadata may be null.
     *
     * @param buf            the StringBuilder to append HTML to
     * @param snark          the Snark object representing the torrent; may be null
     * @param base           the base URL or path used in links
     * @param tName          the torrent display name used in error case
     * @param showStopStart  whether to show start/stop/recheck buttons in torrent info
     */
    private void appendTorrentInfo(StringBuilder buf, Snark snark, String base, String tName, boolean showStopStart) {
        if (snark == null) {
            buf.append("<table class=resourceError id=NotFound><tr><th colspan=2>")
               .append(_t("Resource Not found"))
               .append("</th></tr><tr><td><b>").append(_t("Torrent")).append(":</b></td><td>")
               .append(DataHelper.escapeHTML(tName))
               .append("</td></tr><tr><td><b>").append(_t("Base")).append(":</b></td><td>")
               .append(base)
               .append("</td></tr>\n</table>\n");
            return;
        }

        String fullPath = snark.getName();
        String baseName = encodePath((new File(fullPath)).getName());
        MetaInfo meta = snark.getMetaInfo();
        Storage storage = snark.getStorage();

        buf.append("<div class=mainsection id=snarkInfo>")
           .append("<table id=torrentInfo>\n")
           .append("<tr><th colspan=2>");
        appendIcon(buf, "torrent", "", "", true, false);
        buf.append("<b>").append(_t("Torrent")).append(":</b> ");
        if (storage != null) {
            buf.append(DataHelper.escapeHTML(storage.getBase().getPath()));
        } else {
            buf.append(DataHelper.escapeHTML(snark.getBaseName()));
        }
        String hex = I2PSnarkUtil.toHex(snark.getInfoHash());
        buf.append("</th><th><span class=infohash title=\"").append(_t("Info hash"))
           .append("\" tabindex=0>")
           .append(hex.toUpperCase(Locale.US))
           .append("</span>");

        String announce = null;
        if (meta != null) {
            announce = meta.getAnnounce();
            if (announce == null) { announce = snark.getTrackerURL(); }
            announce = prettyAnnounce(announce);
        }
        if (meta != null && !meta.isPrivate()) {
            buf.append("<a class=mLink href=\"").append(MagnetURI.MAGNET_FULL).append(hex);
            if (announce != null) { buf.append("&amp;tr=").append(announce); }
            if (baseName != null) {
                buf.append("&amp;dn=").append(DataHelper.escapeHTML(baseName).replace(".torrent", "")
                   .replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"));
            }
            buf.append("\" title=\"").append(MagnetURI.MAGNET_FULL).append(hex);
            if (announce != null) { buf.append("&amp;tr=").append(announce); }
            if (baseName != null) {
                buf.append("&amp;dn=").append(DataHelper.escapeHTML(baseName).replace(".torrent", "")
                   .replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"));
            }
            buf.append("\">");
            appendIcon(buf, "magnet", "", "", false, true, true);
            buf.append("</a>");
        }

        buf.append("<a class=tLink href=\"").append(_contextPath).append('/')
           .append(baseName).append("\" title=\"")
           .append(DataHelper.escapeHTML(baseName).replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"))
           .append("\">");
        appendIcon(buf, "torrent", "", "", false, true, true);
        buf.append("</a></th></tr>\n");

        long dat = (meta != null) ? meta.getCreationDate() : 0;
        long[] dates = _manager.getSavedAddedAndCompleted(snark);
        DateFormat fmt = _DATE_FMT2.get();
        long dataLength = snark.getDataLength();
        long totalLength = snark.getTotalLength();

        buf.append("<tr id=torrentInfoStats>").append("<td colspan=3><span class=nowrap");
        StringBuilder sizeTitle = new StringBuilder(64);
        if (dat > 0) {
            sizeTitle.append(_t("Created")).append(": ").append(fmt.format(new Date(dat)));
        }
        if (totalLength > dataLength) {
            if (sizeTitle.length() > 0) {
                sizeTitle.append('\n');
            }
            sizeTitle.append(_t("Size of the files actually downloaded: {0}, excluding {1} of BEP 47 padding files",
                                 formatSize(dataLength), formatSize(totalLength - dataLength)));
        }
        if (sizeTitle.length() > 0) {
            buf.append(" title=\"").append(sizeTitle).append("\"");
        }
        buf.append(">");
        appendIcon(buf, "file", "", "", true, false);
        buf.append("<b>").append(_t("Size")).append(":</b> ").append(formatSize(dataLength));
        if (storage != null) {
            int fileCount = storage.getFileCount();
            buf.append("</span>&nbsp;<span class=nowrap>");
            appendIcon(buf, "file", "", "", true, false);
            buf.append("<b>").append(_t("Files")).append(":</b> ").append(fileCount);
        }
        int pieces = snark.getPieces();
        double completion = (pieces - snark.getNeeded()) / (double) pieces;
        buf.append("</span>&nbsp;<span class=nowrap");
        if (dates[0] > 0) {
            String date = fmt.format(new Date(dates[0]));
            buf.append(" title=\"").append(_t("Added")).append(": ").append(date).append("\"");
        }
        buf.append(">");
        appendIcon(buf, "file", "", "", true, false);
        buf.append("<b>").append(_t("Pieces")).append(":</b> ").append(pieces)
           .append(" @ ").append(formatSize(snark.getPieceLength(0)).replace("iB", ""));

        if (dates[0] > 0) {
            String date = DataHelper.formatTime(dates[0]);
            long sz = snark.getTotalLength();
            long time;
            if (storage != null && storage.complete()) {
                time = dates[1] - dates[0];
            } else {
                sz -= snark.getRemainingLength();
                time = _context.clock().now() - dates[0];
            }
            time /= 1000;

            if (time >= 30) {
                long rate = sz / time;
                if (rate >= 100) {
                    buf.append("</span>&nbsp;<span class=nowrap title=\"")
                       .append(_t("Average download speed for torrent")).append("\">");
                    appendIcon(buf, "head_rxspeed", "", "", true, false);
                    buf.append("<b>").append(_t("Download speed")).append(":</b> ")
                       .append(rate / 1024).append("K/s");
                }
            }
        }

        buf.append("</span>&nbsp;<span class=nowrap>");
        appendIcon(buf, "head_tx", "", "", true, false);

        buf.append("<b>").append(_t("Share ratio")).append(":</b> ");
        long uploaded = snark.getUploaded();
        if (uploaded > 0) {
            double ratio = uploaded / ((double) snark.getDataLength());
            if (ratio < 0.1) {
                buf.append((new DecimalFormat("0.000")).format(ratio));
            } else {
                buf.append((new DecimalFormat("0.00")).format(ratio));
            }
            buf.append(" x");
        } else {
            buf.append('0');
        }

        buf.append("</span>&nbsp;<span id=completion class=nowrap");
        if (dates[1] > 0) {
            String date = fmt.format(new Date(dates[1]));
            buf.append(" title=\"").append(_t("Completed")).append(": ").append(date).append("\"");
        }
        buf.append(">");
        appendIcon(buf, "head_rx", "", "", true, false);
        buf.append("<b>");
        if (completion < 1.0) {
            buf.append(_t("Completion")).append(":</b> ").append((new DecimalFormat("0.0%"))
               .format(completion).replace("0.0%", "0%"));
        } else {
            buf.append(_t("Complete")).append("</b>");
        }
        buf.append("</span>");

        if (meta != null) {
            String cby = meta.getCreatedBy();
            long needed = snark.getNeededLength();
            if (needed < 0) { needed = snark.getRemainingLength(); }
            if (needed > 0) {
               buf.append("&nbsp;<span class=nowrap>");
               appendIcon(buf, "head_rx", "", "", true, false);
               buf.append("<b>").append(_t("Remaining")).append(":</b> ").append(formatSize(needed)).append("</span>");
            }
            long skipped = snark.getSkippedLength();
            if (skipped > 0) {
                buf.append("&nbsp;<span class=nowrap>");
                appendIcon(buf, "head_rx", "", "", true, false);
                buf.append("<b>").append(_t("Skipped")).append(":</b> ").append(formatSize(skipped)).append("</span>");
            }

            if (storage != null) {
                dat = storage.getActivity();
                if (dat > 0) {
                    String date = fmt.format(new Date(dat));
                    buf.append("&nbsp;<span class=nowrap>");
                    appendIcon(buf, "torrent", "", "", true, false);
                    buf.append("<b>").append(_t("Last activity")).append(":</b> ").append(date).append("</span>");
                }
            }
        }
        buf.append("</td></tr>\n");

        List<List<String>> alist = (meta != null) ? meta.getAnnounceList() : null;
        if (alist != null && !alist.isEmpty()) {
            buf.append("<tr id=trackers title=\"")
               .append(_t("Only I2P trackers will be used; non-I2P trackers are displayed for informational purposes only"))
               .append("\"><td colspan=3>");
            appendIcon(buf, "torrent", "", "", true, false);
            buf.append("<b>").append(_t("Trackers")).append(":</b> ");

            for (List<String> alist2 : alist) {
                if (alist2.isEmpty()) {
                    buf.append("<span class=\"info_tracker primary\">");
                    boolean more = false;
                    for (String s : alist2) {
                        if (more) { buf.append("<span class=info_tracker>"); }
                        else { more = true; }
                        buf.append(getShortTrackerLink(prettyAnnounce(s), snark.getInfoHash()));
                        buf.append("</span> ");
                    }
                }
                buf.append("<span class=info_tracker>");
                boolean more = false;
                for (String s : alist2) {
                    if (more) { buf.append("<span class=info_tracker>"); }
                    else { more = true; }
                    buf.append(getShortTrackerLink(prettyAnnounce(s), snark.getInfoHash()));
                    buf.append("</span> ");
                }
            }
            buf.append("</td></tr>\n");
        } else if (meta != null) {
            announce = meta.getAnnounce();
            if (announce == null) { announce = snark.getTrackerURL(); }
            if (announce != null) {
                announce = prettyAnnounce(announce);
                buf.append("<tr id=trackers title=\"")
                   .append(_t("Only I2P trackers will be used; non-I2P trackers are displayed for informational purposes only"))
                   .append("\"><td colspan=3>");
                appendIcon(buf, "torrent", "", "", true, false);
                buf.append("<b>").append(_t("Tracker")).append(":</b> <span class=\"info_tracker primary\">")
                   .append(getShortTrackerLink(announce, snark.getInfoHash()))
                   .append("</span> ")
                   .append("</td></tr>\n");
            }
        }

        List<String> weblist = (meta != null) ? meta.getWebSeedURLs() : null;
        if (weblist != null) {
            List<String> wlist = new ArrayList<>(weblist.size());
            for (String s : weblist) {
                if (isI2PTracker(s)) { wlist.add(s); }
            }
            if (!wlist.isEmpty()) {
                buf.append("<tr id=webseeds><td colspan=3>");
                appendIcon(buf, "torrent", "", "", true, false);
                buf.append("<b>").append(_t("Web Seeds")).append("</b>: ");
                boolean more = false;
                for (String s : wlist) {
                    buf.append("<span class=info_tracker>");
                    if (more) { buf.append(' '); }
                    else { more = true; }
                    buf.append(getShortTrackerLink(DataHelper.stripHTML(s), snark.getInfoHash()))
                       .append("</span> ");
                }
                buf.append("</td></tr>\n");
            }
        }

        if (meta != null) {
            String com = meta.getComment();
            if (com != null && !com.isEmpty()) {
                if (com.length() > 5000) { com = com.substring(0, 5000) + "&hellip;"; }
                buf.append("<tr><td id=metacomment colspan=3><div class=commentWrapper>\n")
                   .append(DataHelper.stripHTML(com).replace("\r\n", "<br>").replace("\n", "<br>").replace("&apos;", "'"))
                   .append("</div>\n</td></tr>\n");
            }
        }

        if (showStopStart) {
            buf.append("<tr id=torrentInfoControl><td colspan=3>");
            if (snark.isChecking()) {
                buf.append("<span id=fileCheck><b>").append(_t("Checking")).append("&hellip; ")
                   .append((new DecimalFormat("0.0%")).format(snark.getCheckingProgress()))
                   .append("&nbsp;<a href=\"").append(base).append("\">")
                   .append(_t("Refresh page for results")).append("</a></b></span>");
            } else if (snark.isStarting()) {
                buf.append("<b>").append(_t("Starting")).append("&hellip;</b>");
            } else if (snark.isAllocating()) {
                buf.append("<b>").append(_t("Allocating")).append("&hellip;</b>");
            } else {
                boolean isRunning = !snark.isStopped();
                buf.append("<input type=submit value=\"");
                if (isRunning) {
                    buf.append(_t("Stop")).append("\" name=stop class=stoptorrent>");
                } else {
                    buf.append(_t("Start")).append("\" name=start class=starttorrent>");
                }
                buf.append("<input type=submit name=recheck value=\"").append(_t("Force Recheck"));
                if (isRunning) {
                    buf.append("\" class=disabled disabled title=\"")
                       .append(_t("Stop the torrent in order to check file integrity")).append("\">");
                } else {
                    buf.append("\" class=reload title=\"").append(_t("Check integrity of the downloaded files")).append("\">");
                }
            }
            buf.append("</td></tr>\n");
        }

        buf.append("</table>\n").append("</div>\n");
    }

    /**
     * Appends the HTML markup for the file download priority options in torrent file listings
     *
     * This includes radio buttons for selecting high, normal, or skip priority,
     * conditioned on whether priority display is enabled, the file is not complete,
     * and the file is not a directory.
     *
     * @param buf           The StringBuilder to append the HTML to.
     * @param showPriority  Flag indicating if priority options should be shown.
     * @param complete      Flag indicating if the file download is complete.
     * @param isDirectory   Flag indicating if the file is a directory.
     * @param priority      The current priority value of the file (&lt;0 is skip, 0 normal, &gt;0 high).
     * @param fileIndex     The identifier index for the file, used to ensure unique input names.
     * @since 0.9.68+
     */
    private void appendPriority(StringBuilder buf, boolean showPriority, boolean complete,
                                       boolean isDirectory, int priority, int fileIndex) {
        if (showPriority) {
            buf.append("<td class=\"priority volatile\">\n");
            if (!complete && !isDirectory) {
                buf.append("<label class=priorityHigh title=\"")
                   .append(_t("Download file at high priority"))
                   .append("\"><input type=radio class=\"optbox prihigh\" value=5 name=pri_")
                   .append(fileIndex);
                if (priority > 0) {buf.append(" checked");}
                buf.append('>')
                   .append(_t("High"))
                   .append("</label>\n<label class=priorityNormal title=\"")
                   .append(_t("Download file at normal priority"))
                   .append("\"><input type=radio class=\"optbox prinorm\" value=0 name=pri_")
                   .append(fileIndex);
                if (priority == 0) {buf.append(" checked");}
                buf.append('>')
                   .append(_t("Normal"))
                   .append("</label>\n<label class=prioritySkip title=\"")
                   .append(_t("Do not download this file"))
                   .append("\"><input type=radio class=\"optbox priskip\" value=-9 name=pri_")
                   .append(fileIndex);
                if (priority < 0) {buf.append(" checked");}
                buf.append('>').append(_t("Skip")).append("</label>\n");
            }
            buf.append("</td>");
        }
    }

    /**
     * Builds an HTML progress bar with optional percentage text and a tooltip showing
     * the remaining size and completion percentage.
     *
     * @param total total size in bytes
     * @param remaining remaining size in bytes
     * @param includePercent whether to include the percentage text inside the bar
     * @param includeTooltip whether to provide tooltip / value on hover
     * @param noThinsp whether to avoid using thin space
     * @param formatSize whether to format the size in human-readable format
     * @return String containing the HTML for the progress bar
     * @since 0.9.68+
     */
    private String buildProgressBar(long total, long remaining, boolean includePercent, boolean includeTooltip, boolean noThinsp, boolean formatSize) {
        if (total <= 0) return "";
        long percent = 100 * (total - remaining) / total;
        StringBuilder sb = new StringBuilder(256);

        sb.append("<div class=barOuter><div class=barInner style=\"width:")
          .append(percent).append("%\">");

        if ((includePercent || remaining > 0) && includeTooltip) {
            sb.append("<div class=barText tabindex=0 title=\"")
              .append(percent).append("% ").append(_t("complete"))
              .append("; ")
              .append(formatSize ? DataHelper.formatSize2(remaining).replace("i", "") : String.valueOf(remaining))
              .append(' ').append(_t("remaining"))
              .append("\">");

            if (formatSize) {
                // Only append "B" if the value is under 1KB
                boolean addBLabelCurrent = (total - remaining) < 1024;
                boolean addBLabelTotal = total < 1024;
                sb.append(DataHelper.formatSize2(total - remaining).replace("i", ""))
                  .append(addBLabelCurrent ? "B" : "")
                  .append(thinsp(noThinsp))
                  .append(DataHelper.formatSize2(total).replace("i", ""))
                  .append(addBLabelTotal ? "B" : "");
            } else {
                sb.append(total - remaining).append(thinsp(noThinsp)).append(total);
            }

            sb.append("</div>");
        }

        sb.append("</div></div>");
        return sb.toString();
    }

    /**
     * Extracts the torrent name and path-in-torrent from the given title.
     *
     * @param title The full path/title string.
     * @return An array where index 0 is tName and index 1 is pathInTorrent.
     * @since 0.9.68+
     */
    private String[] extractTorrentNameAndPath(String title) {
        String[] result = new String[2];
        int titleSlash = title.indexOf('/');
        if (titleSlash > 0) {
            result[0] = title.substring(0, titleSlash);   // tName
            result[1] = title.substring(titleSlash);      // pathInTorrent
        } else {
            result[0] = title;                            // tName
            result[1] = "/";                              // pathInTorrent
        }
        return result;
    }

    /**
     * Basic checks only, not as comprehensive as what TrackerClient does.
     * Just to hide non-i2p trackers from the details page.
     * @return whether i2 p tracker
     * @since 0.9.46
     */
    static boolean isI2PTracker(String url, boolean udpEnabled) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String method = uri.getScheme();
            if (!("http".equals(method) || (udpEnabled && "udp".equals(method)))) {return false;}
            String host = uri.getHost();
            if (host == null || !host.endsWith(".i2p")) {return false;}
        } catch (URISyntaxException use) {return false;}
        return true;
    }

    /**
     * @since 0.9.46
     * @deprecated Use {@link #isI2PTracker(String, boolean)} for testability
     */
    private boolean isI2PTracker(String url) {
        return isI2PTracker(url, _manager.util().udpEnabled());
    }

    /**
     * Whether the MIME type is audio.
     *
     * @param mime non-null
     * @return whether audio
     * @since 0.9.44
     */
    private static boolean isAudio(String mime) {
        /**
         *  Don't include playlist files as the browser doesn't support them in the HTML5 player,
         *  and if it did and prefetched, that could be a security issue
         */
        return (mime.startsWith("audio/") && !mime.equals("audio/mpegurl") && !mime.equals("audio/x-scpls")) ||
                mime.equals("application/ogg");
    }

    /**
     * Whether the MIME type is video.
     *
     * @param mime non-null
     * @return whether video
     * @since 0.9.44
     */
    private static boolean isVideo(String mime) {
        return mime.startsWith("video/") && !mime.equals("video/x-msvideo") && /*!mime.equals("video/x-matroska") &&*/
               !mime.equals("video/quicktime") && !mime.equals("video/x-flv");
    }

    /**
     * List the files in a directory and wrap each in a FileAndIndex with the given remaining
     * array.
     *
     * @param dir the directory to list
     * @param storage the storage for the torrent
     * @param remainingArray the precomputed remaining byte counts
     * @return the wrapped file list, or null if the directory cannot be listed or is empty
     */
    private static List<Sorters.FileAndIndex> listFileAndIndex(
            File dir, Storage storage, long[] remainingArray) {
        File[] ls = dir.listFiles();
        if (ls == null || ls.length == 0) {
            return null;
        }
        List<Sorters.FileAndIndex> rv = new ArrayList<>(ls.length);
        for (int i = 0; i < ls.length; i++) {
            rv.add(new Sorters.FileAndIndex(ls[i], storage, remainingArray));
        }
        return rv;
    }

    /**
     * Is there at least one complete audio file in this directory or below?
     * Recursive.
     *
     * @return whether complete audio is present
     * @since 0.9.44
     */
    /**
     * First entry of names at or under prefix whose remaining byte count is
     * zero and whose name satisfies match, or null. names must be
     * index-aligned with remaining (Storage.getFileNames() paired with
     * Storage.remaining2()); prefix is '' at the torrent root or a directory
     * path ending with '/' - matching is a plain startsWith across all
     * descendant depths.
     *
     * @param match caller-supplied name filter, e.g. the audio MIME check
     * @return the first matching name, or null when none qualifies
     * @since 0.9.71+
     */
    static String findCompleteFile(List<String> names, long[] remaining, String prefix, Predicate<String> match) {
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (!n.startsWith(prefix) || remaining[i] != 0) {continue;}
            if (match.test(n)) {return n;}
        }
        return null;
    }

    /**
     * Whether any complete audio file exists at or below the browsed
     * directory. Answered from Storage metadata instead of recursively
     * listing directories on every page view.
     */
    private boolean hasCompleteAudio(Storage storage, long[] remainingArray, String prefix) {
        if (storage == null || remainingArray == null) {return false;}
        String found = findCompleteFile(storage.getFileNames(), remainingArray, prefix,
                                        n -> {String m = getMimeType(n); return m != null && isAudio(m);});
        return found != null;
    }

    /**
     * The audio files in the resource list as an m3u playlist.
     * https://en.wikipedia.org/wiki/M3U
     *
     * @param base The encoded base URL
     * @param sortParam may be null
     * @return String of HTML or null if no files or on error
     * @since 0.9.44
     */
    private String getPlaylist(String reqURL, String base, String sortParam) throws IOException {
        String decodedBase = decodePath(base);
        String title = decodedBase;
        String cpath = _contextPath + '/';
        if (title.startsWith(cpath)) {title = title.substring(cpath.length());}

        // Get the snark associated with this directory
        String[] tNameAndPath = extractTorrentNameAndPath(title);
        String tName = tNameAndPath[0];
        String pathInTorrent = tNameAndPath[1];

        Snark snark = _manager.getTorrentByBaseName(tName);
        if (snark == null) {return null;}
        Storage storage = snark.getStorage();
        if (storage == null) {return null;}
        File r = resolveTorrentPath(storage, pathInTorrent);
        if (!r.isDirectory()) {return null;}
        // precompute remaining for all files for efficiency
        long[] remainingArray = storage.remaining();
        List<Sorters.FileAndIndex> fileList = listFileAndIndex(r, storage, remainingArray);
        if (fileList == null) {return null;}

        boolean showSort = fileList.size() > 1;
        int sort = 0;
        if (showSort) {
            if (sortParam != null) {
                sort = I2PSnarkUtil.parseInt(sortParam, 0);
            }
            DataHelper.sort(fileList, Sorters.getFileComparator(sort, this));
        }
        StringBuilder buf = new StringBuilder(512);
        getPlaylist(buf, fileList, reqURL, sort, storage, remainingArray);
        String rv = buf.toString();
        if (rv.length() <= 0) {return null;}
        return rv;
    }

    /**
     * Append playlist entries in m3u format to buf.
     * Recursive.
     *
     * @param buf out parameter
     * @param reqURL encoded, WITH trailing slash
     * @since 0.9.44
     */
    private void getPlaylist(StringBuilder buf, List<Sorters.FileAndIndex> fileList, String reqURL, int sort, Storage storage, long[] remainingArray) {
        for (Sorters.FileAndIndex fai : fileList) {
            if (fai.isDirectory) {
                // recurse
                List<Sorters.FileAndIndex> fl2 = listFileAndIndex(fai.file, storage, remainingArray);
                if (fl2 != null) {
                    if (fl2.size() > 1) {DataHelper.sort(fl2, Sorters.getFileComparator(sort, this));}
                    String name = fai.file.getName();
                    String url2 = reqURL + encodePath(name) + '/';
                    getPlaylist(buf, fl2, url2, sort, storage, remainingArray);
                }
                continue;
            }
            if (fai.remaining != 0) {continue;}
            String name = fai.file.getName();
            String mime = getMimeType(name);
            if (mime != null && isAudio(mime)) {buf.append(reqURL).append(encodePath(name)).append('\n');} // TODO Extended M3U
        }
    }

    /**
     * Immutable parameters for rendering the comments/ratings section.
     * Package-visible for testing.
     *
     * @since 0.9.71+
     */
    static class CommentsContext {
        final Snark snark;
        final boolean er;
        final boolean ec;
        final boolean esc;
        final String authorName;
        final boolean canRate;

        CommentsContext(Snark snark, boolean er, boolean ec, boolean esc,
                        String authorName, boolean canRate) {
            this.snark = snark;
            this.er = er;
            this.ec = ec;
            this.esc = esc;
            this.authorName = authorName;
            this.canRate = canRate;
        }
    }

    /**
     * Snapshot read atomically from a torrent's comment set: the user's own
     * rating, community rating count and average, and an iterator over the
     * existing comments when any exist. Package-visible for testing.
     *
     * @since 0.9.71+
     */
    static class CommentsHeaderResult {
        final int myRating;
        final int ratingCount;
        final double averageRating;
        final Iterator<Comment> iter;

        /**
         * Single-value result for the no-comment-set case.
         *
         * @param myRating the user's own rating (0 when none)
         */
        CommentsHeaderResult(int myRating) {
            this(myRating, 0, 0d, null);
        }

        CommentsHeaderResult(int myRating, int ratingCount, double averageRating,
                             Iterator<Comment> iter) {
            this.myRating = myRating;
            this.ratingCount = ratingCount;
            this.averageRating = averageRating;
            this.iter = iter;
        }
    }

    /**
     * Display the ratings and comments section.
     *
     * @param er ratings enabled globally
     * @param ec comments enabled globally
     * @param esc comments enabled this torrent
     * @since 0.9.31
     */
    private void displayComments(Snark snark, boolean er, boolean ec, boolean esc, StringBuilder buf) {
        CommentSet comments = snark.getComments();
        String authorName = _manager.util().getCommentsName();
        CommentsContext ctx = new CommentsContext(snark, er, ec, esc, authorName,
                                                  esc && !authorName.isEmpty());
        renderCommentsHeader(ctx, buf);

        // new rating / comment form; intentionally no preselected rating
        if (ctx.canRate) {
            buf.append("<tr id=newRating>\n");
            if (er) {
                buf.append("<td>\n<select name=myRating>\n");
                for (int i = 5; i >= 0; i--) {
                    buf.append("<option value=\"").append(i).append("\"");
                    if (i == 0) {buf.append(" selected");}
                    buf.append('>');
                    if (i != 0) {
                        for (int j = 0; j < i; j++) {buf.append("★");}
                        buf.append(' ').append(ngettext("1 star", "{0} stars", i));
                    } else {buf.append("☆ ").append(_t("No rating"));}
                    buf.append("</option>\n");
                }
                buf.append("</select>\n</td>");
            } else {buf.append("<td></td>");}
            if (esc) {buf.append("<td id=addCommentText><textarea name=nofilter_newComment cols=44 rows=4></textarea></td>");}
            else {buf.append("<td></td>");}
            buf.append("<td class=commentAction><input type=submit name=addComment value=\"");
            if (er && esc) {buf.append(_t("Rate and Comment"));}
            else if (er) {buf.append(_t("Rate Torrent"));}
            else {buf.append(_t("Add Comment"));}
            buf.append("\" class=accept></td></tr>\n");
        }
        // current rating and community stats, read under one lock above
        CommentsHeaderResult state = readCommentState(comments, er, ec);
        if (comments != null) {
            if (er) {
                buf.append("<tr id=myRating><td>");
                if (state.myRating > 0) {
                    buf.append(_t("My Rating")).append(":</td><td colspan=2 class=commentRating>");
                    for (int i = 0; i < state.myRating; i++) {
                        StringBuilder iconBuf = new StringBuilder();
                        appendIcon(iconBuf, "rateme", "★", "", false, true);
                        buf.append(iconBuf.toString());
                    }
                }
                buf.append("</td></tr>");
            }
            if (er) {
                buf.append("<tr id=showRatings><td>");
                if (state.ratingCount > 0) {
                    buf.append(_t("Average Rating"))
                       .append(":</td><td colspan=2>")
                       .append((new DecimalFormat("0.0")).format(state.averageRating));
                } else {
                    buf.append(_t("Average Rating")).append(":</td><td colspan=2>");
                    buf.append(_t("No community ratings currently available"));
                }
                buf.append("</td></tr>\n");
            }
        }

        buf.append("</table>\n");
        int ccount = 0;
        if (state.iter != null) {
            DateFormat fmt = _DATE_FMT3.get();
            fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
            buf.append("<table id=userComments>\n");
            while (state.iter.hasNext()) {
                Comment c = state.iter.next();
                buf.append("<tr><td class=commentAuthor>");
                if (c.getName() != null) {
                    buf.append("<span class=commentAuthorName title=\"").append(DataHelper.escapeHTML(c.getName())).append("\">")
                       .append(DataHelper.escapeHTML(c.getName())).append("</span>");
                }
                buf.append("</td><td class=commentRating>");
                if (er) {
                    int rt = c.getRating();
                    if (rt > 0) {
                        for (int i = 0; i < rt; i++) {
                            StringBuilder iconBuf = new StringBuilder();
                            appendIcon(iconBuf, "rateme", "★", "", false, true);
                            buf.append(iconBuf.toString());
                        }
                    }
                }
                buf.append("</td><td class=commentText>");
                if (esc) {
                    if (c.getText() != null) {
                        buf.append("<div class=commentWrapper title=\"").append(_t("Submitted")).append(": ")
                           .append(fmt.format(new Date(c.getTime()))).append("\">")
                           .append(DataHelper.escapeHTML(c.getText()))
                           .append("</div></td><td class=commentDelete><input type=checkbox class=optbox name=\"cdelete.")
                           .append(c.getID()).append("\" title=\"").append(_t("Mark for deletion")).append("\">");
                        ccount++;
                    } else {buf.append("</td><td class=commentDelete>");} // insert empty named columns to maintain table layout
                } else {buf.append("</td><td class=commentDelete>");} // insert empty named columns to maintain table layout
                buf.append("</td></tr>\n");
            }
            if (esc && ccount > 0) {
                buf.append("<tr id=commentDeleteAction><td colspan=4 class=commentAction><input type=submit name=deleteComments value=\"")
                   .append(_t("Delete Selected"))
                   .append("\" class=delete></td></tr>\n");
            }
            buf.append("</table>\n");
        }
    }

    /**
     * Renders the commentInfo table header: section title plus the
     * author-name span (the configured name, or a "configure" hint when
     * comments are on for the torrent but no author name is set).
     * Extracted from displayComments.
     *
     * @param ctx non-null rendering context
     * @param buf target buffer
     * @since 0.9.71+
     */
    private void renderCommentsHeader(CommentsContext ctx, StringBuilder buf) {
        buf.append("<table id=commentInfo>\n<tr><th colspan=3>")
           .append(_t("Ratings and Comments").replace("and", "&amp;"))
           .append("&nbsp;&nbsp;&nbsp;");
        if (ctx.esc && !ctx.canRate) {
            buf.append("<span id=nameRequired>")
               .append(_t("Author name required to rate or comment"))
               .append("&nbsp;&nbsp;<a href=\"").append(_contextPath).append("/configure#configureAuthor\">[")
               .append(_t("Configure"))
               .append("]</a></span>");
        } else if (ctx.esc) {
            buf.append("<span id=nameRequired><span class=commentAuthorName title=\"")
               .append(_t("Your author name for published comments and ratings"))
               .append("\">")
               .append(DataHelper.escapeHTML(ctx.authorName))
               .append("</span></span>");
        }
        buf.append("</th></tr>\n");
    }

    /**
     * Reads the user's rating, community rating statistics, and the comment
     * iterator from a torrent's comment set within a single lock scope, so
     * markup rendering happens outside synchronized blocks.
     * Extracted from displayComments.
     *
     * @param comments may be null when none are saved yet
     * @param er ratings enabled globally
     * @param ec comments enabled globally
     * @return state snapshot, never null; iter non-null only when comments exist
     * @since 0.9.71+
     */
    private static CommentsHeaderResult readCommentState(CommentSet comments, boolean er, boolean ec) {
        int myRating = 0;
        int rcnt = 0;
        double avg = 0;
        Iterator<Comment> iter = null;
        if (comments != null) {
            synchronized (comments) {
                if (er) {
                    myRating = comments.getMyRating();
                    rcnt = comments.getRatingCount();
                    if (rcnt > 0) {avg = comments.getAverageRating();}
                }
                if (ec && comments.size() > 0) {iter = comments.iterator();}
            }
        }
        return new CommentsHeaderResult(myRating, rcnt, avg, iter);
    }

    /**
     * Sort query string for torrent file-list links, where the value is a
     * servlet-generated numeric sort key. Distinct from the request-based
     * getQueryString() builders: no request context, and the value is
     * HTML-stripped unconditionally rather than validated numerically.
     *
     * @param so sort key, may be null or empty for no sorting
     * @return "?sort=..." or ""
     * @since 0.9.16
     */
    private static String sortQueryString(String so) {
        if (so != null && !so.isEmpty()) {return "?sort=" + DataHelper.stripHTML(so);}
        return "";
    }

    /**
     *  Pick an icon; try to catch the common types in an i2p environment.
     *
     *  @return file name not including ".png"
     *  @since 0.7.14
     */
    private String toIcon(File item) {
        if (item.isDirectory()) {return "folder";}
        return toIcon(item.toString());
    }

    /**
     * Returns the icon name representing the file type or mime type of the given file path.
     * Determines custom icons for certain special cases like i2p install executables.
     *
     * @param path the file path or file name to analyze
     * @return a string representing the icon name matching the file type
     * @since 0.9.68+
     */
    String toIcon(String path) {
        String plc = path.toLowerCase(Locale.US);
        String mime = getMimeType(path);
        if (mime == null) mime = "";

        // i2pinstall files get special icon
        if (plc.endsWith(".exe") && plc.contains("i2pinstall")) {
            return plc.contains("+") ? "plus" : "i2p";
        }

        if (IconMaps.MIME_ICON_MAP.containsKey(mime)) {
            String icon = IconMaps.MIME_ICON_MAP.get(mime);
            if ("compress".equals(icon) && (plc.endsWith(".su3") || plc.endsWith(".su2"))) {
                return "i2p";
            }
            if ("package".equals(icon) && plc.contains("i2pinstall")) {
                return plc.contains("+") ? "plus" : "i2p";
            }
            return icon;
        }

        for (Map.Entry<String, String> entry : IconMaps.SUFFIX_ICON_MAP.entrySet()) {
            if (plc.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        if (plc.contains(".css.")) return "code";
        if (plc.contains("shasum")) return "hash";

        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("audio/") || "application/ogg".equals(mime)) return "audio";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("font/")) return "font";

        return "generic";
    }

    /**
     * Holds immutable mappings from MIME types and file suffixes to icon names.
     * Initialized once and reused to optimize performance of icon lookup.
     */
    private static class IconMaps {
        static final Map<String, String> MIME_ICON_MAP;
        static final Map<String, String> SUFFIX_ICON_MAP;

        static {
            Map<String, String> mimeMap = new HashMap<>();
            mimeMap.put("application/compress", "compress");
            mimeMap.put("application/epub+zip", "ebook");
            mimeMap.put("application/gzip", "compress");
            mimeMap.put("application/java-archive", "package");
            mimeMap.put("application/pdf", "pdf");
            mimeMap.put("application/rtf", "text");
            mimeMap.put("application/x-7z-compressed", "compress");
            mimeMap.put("application/x-bittorrent", "magnet");
            mimeMap.put("application/x-bzip2", "compress");
            mimeMap.put("application/x-gtar", "tar");
            mimeMap.put("application/x-jar", "package");
            mimeMap.put("application/x-java-archive", "package");
            mimeMap.put("application/x-mobipocket-ebook", "ebook");
            mimeMap.put("application/x-rar-compressed", "rar");
            mimeMap.put("application/x-tar", "tar");
            mimeMap.put("application/x-xz", "compress");
            mimeMap.put("application/zip", "compress");
            mimeMap.put("text/html", "html");
            mimeMap.put("text/plain", "text");
            mimeMap.put("text/x-sfv", "text");
            MIME_ICON_MAP = Collections.unmodifiableMap(mimeMap);

            Map<String, String> suffixMap = new HashMap<>();
            suffixMap.put(".appimage", "package");
            suffixMap.put(".azw3", "ebook");
            suffixMap.put(".azw4", "ebook");
            suffixMap.put(".bat", "windows");
            suffixMap.put(".bin", "app");
            suffixMap.put(".cgi", "code");
            suffixMap.put(".cpp", "code");
            suffixMap.put(".css", "code");
            suffixMap.put(".deb", "package");
            suffixMap.put(".dll", "windows");
            suffixMap.put(".dmg", "apple");
            suffixMap.put(".exe", "windows");
            suffixMap.put(".fb2", "ebook");
            suffixMap.put(".flatpak", "package");
            suffixMap.put(".h", "code");
            suffixMap.put(".ini", "text");
            suffixMap.put(".iso", "cd");
            suffixMap.put(".jar", "package");
            suffixMap.put(".js", "code");
            suffixMap.put(".json", "code");
            suffixMap.put(".jsp", "html");
            suffixMap.put(".md5", "hash");
            suffixMap.put(".md", "text");
            suffixMap.put(".nfo", "text");
            suffixMap.put(".nrg", "cd");
            suffixMap.put(".php", "code");
            suffixMap.put(".pl", "code");
            suffixMap.put(".prc", "ebook");
            suffixMap.put(".py", "code");
            suffixMap.put(".rpm", "package");
            suffixMap.put(".sh", "shell");
            suffixMap.put(".snap", "package");
            suffixMap.put(".srt", "srt");
            suffixMap.put(".su2", "i2p");
            suffixMap.put(".su3", "i2p");
            suffixMap.put(".tgz", "tar");
            suffixMap.put(".ttf", "font");
            suffixMap.put(".txz", "tar");
            suffixMap.put(".url", "html");
            suffixMap.put(".woff2", "font");
            suffixMap.put(".woff", "font");
            suffixMap.put(".xpi2p", "plugin");
            SUFFIX_ICON_MAP = Collections.unmodifiableMap(suffixMap);
        }
    }

    /**
     * Appends an <img> tag for an icon to the given StringBuilder.
     *
     * @param buf the StringBuilder to append to (must not be null)
     * @param name the icon name without file extension (e.g., "magnet", "folder")
     * @param alt the alt text (should already be HTML-escaped if needed)
     * @param title the tooltip title (optional; if empty, no title attribute is added)
     * @param fromTheme if true, uses the current theme's image path (_imgPath); otherwise uses the WARBASE/icons/ path
     * @param isSvg if true, uses .svg extension; otherwise uses .png
     * @since 0.9.68+
     */
    void appendIcon(StringBuilder buf, String name, String alt, String title, boolean fromTheme, boolean isSvg, boolean addDimensions) {
        buf.append("<img").append(addDimensions ? " width=16 height=16" : "").append(" alt=\"").append(alt).append("\" src=\"");
        if (fromTheme) {buf.append(_imgPath).append(name);}
        else {buf.append(_contextPath).append(WARBASE).append("icons/").append(name);}
        buf.append(isSvg ? ".svg\"" : ".png\"");
        if (!title.isEmpty()) {buf.append(" title=\"").append(title).append("\"");}
        buf.append(">");
    }

    /**
     * Overloaded method that defaults addDimensions to false.
     * @since 0.9.68+
     */
    void appendIcon(StringBuilder buf, String name, String alt, String title, boolean fromTheme, boolean isSvg) {
        appendIcon(buf, name, alt, title, fromTheme, isSvg, false);
    }

    /**
     *  Icon file (svg) in the .war. Always 16x16.
     *  Wrapped in a tooltip span.
     *
     *  @param icon name without the ".svg"
     *  @param altText non-null
     *  @param titleText non-null (used as data-tooltip)
     *  @since 0.9.51+
     */
    private String toSVGWithDataTooltip(String icon, String altText, String titleText) {
        StringBuilder buf = new StringBuilder(128);
        buf.append("<span class=tooltipped data-tooltip=\"").append(titleText).append("\">");
        appendIcon(buf, icon, altText, "", false, true); // from WARBASE, SVG, no title on img (tooltip is on span)
        buf.append("</span>");
        return buf.toString();
    }

    /**
     * Save file priority changes for a torrent.
     *
     * @param snark the torrent
     * @param postParams the form params
     */
    private void savePriorities(Snark snark, Map<String, String[]> postParams) {
        Storage storage = snark.getStorage();
        if (storage == null) {return;}
        for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("pri_")) {
                int fileIndex = I2PSnarkUtil.parseInt(key.substring(4), -1);
                if (fileIndex < 0) {continue;}
                String[] values = entry.getValue(); // jetty arrays
                if (values.length == 0) {continue;}
                int pri = I2PSnarkUtil.parseInt(values[0], Integer.MIN_VALUE);
                // Only reject unparsable input - PRIORITY_SKIP (-9) is valid
                // and must reach Storage.setPriority.
                if (pri == Integer.MIN_VALUE) {continue;}
                storage.setPriority(fileIndex, pri);
                _manager.addMessage(_t("File downloading priorities updated for torrent ") + storage.getBaseName());
            }
        }
        snark.updatePiecePriorities();
        _manager.saveTorrentStatus(snark);
    }

    /**
     * Save comment changes for a torrent.
     *
     * @param snark the torrent
     * @param postParams the form params
     */
    private void saveComments(Snark snark, Map<String, String[]> postParams) {
        String[] a = postParams.get("myRating");
        String r = (a != null) ? a[0] : null;
        a = postParams.get("nofilter_newComment");
        String c = (a != null) ? a[0] : null;
        if ((r == null || r.equals("0")) && (c == null || c.isEmpty())) {return;}
        int rat = I2PSnarkUtil.parseInt(r, 0);
        Comment com = new Comment(c, _manager.util().getCommentsName(), rat);
        boolean changed = snark.addComments(Collections.singletonList(com));
        if (!changed) {_log.warn("Add of comment ID " + com.getID() + " failed");}
    }

    /**
     * Delete comments for a torrent.
     *
     * @param snark the torrent
     * @param postParams the form params
     */
    private void deleteComments(Snark snark, Map<String, String[]> postParams) {
        CommentSet cs = snark.getComments();
        if (cs == null) {return;}
        synchronized(cs) {
            for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("cdelete.")) {
                    int id = I2PSnarkUtil.parseInt(key.substring(8), -1);
                    if (id >= 0) {
                        boolean changed = cs.remove(id);
                        if (!changed) {_log.warn("Delete of comment ID " + id + " failed");}
                    }
                }
            }
        }
    }

    /**
     * Save comment enabled setting for a torrent.
     *
     * @param snark the torrent
     * @param postParams the form params
     */
    private void saveCommentsSetting(Snark snark, Map<String, String[]> postParams) {
        boolean yes = postParams.get("enableComments") != null;
        _manager.setSavedCommentsEnabled(snark, yes);
    }

    /**
     * Display the edit-torrent section.
     *
     * @param snark non-null
     * @param base the base path
     * @param buf output buffer
     */
    private void displayTorrentEdit(Snark snark, String base, StringBuilder buf) {
        if (snark == null) {return;}
        MetaInfo meta = snark.getMetaInfo();
        if (meta == null) {return;}
        String editSectionTop =
            "<div id=editSection class=mainsection>\n" +
            "<input hidden class=toggle_input id=toggle_torrentedit type=checkbox>" +
            "<label id=tab_torrentedit class=toggleview for=toggle_torrentedit><span class=tab_label>";
        buf.append(editSectionTop).append(_t("Edit Torrent")).append("</span></label><hr>\n")
           .append("<table id=torrentEdit>\n");
        boolean isRunning = !snark.isStopped();
        String announce = meta.getAnnounce();
        if (announce == null) {announce = snark.getTrackerURL();}
        if (announce != null && !isI2PTracker(announce)) {announce = null;} // strip non-i2p trackers
        List<List<String>> alist = meta.getAnnounceList();
        Set<String> annlist = new TreeSet<>();
        if (alist != null && !alist.isEmpty()) {
            for (List<String> alist2 : alist) { // strip non-i2p trackers
                for (String s : alist2) {
                    if (isI2PTracker(s)) {annlist.add(s);}
                }
            }
        }
        if (announce != null) {annlist.add(announce);}
        if (!annlist.isEmpty()) {
            buf.append("<tr class=header><th>")
               .append(_t("Active Trackers"))
               .append("</th><th>")
               .append(_t("Announce URL"))
               .append("</th><th>")
               .append(_t("Primary"))
               .append("</th><th id=remove>")
               .append(_t("Delete"))
               .append("</th></tr>\n");
            for (String s : annlist) {
                String hc = Integer.toString(s.hashCode());
                buf.append("<tr><td>");
                s = DataHelper.stripHTML(s);
                buf.append("<span class=info_tracker>")
                   .append(getShortTrackerLink(s, snark.getInfoHash()))
                   .append("</span></td><td>")
                   .append(s)
                   .append("</td><td>");
                if (hc != null) {
                    buf.append("<input type=radio class=optbox name=primary");
                    if (s.equals(announce)) {buf.append(" checked ");}
                    buf.append(" value=\"").append(hc).append("\"");
                    if (isRunning) {buf.append(" disabled");}
                    buf.append(">");
                }
                buf.append("</td><td>");
                if (hc != null) {
                    buf.append("<input type=checkbox class=optbox name=\"removeTracker-")
                       .append(hc).append("\" title=\"").append(_t("Mark for deletion")).append("\"");
                    if (isRunning) {buf.append(" disabled");}
                    buf.append(">");
                }
                buf.append("</td></tr>\n");
            }
        }

        List<Tracker> newTrackers = _manager.getSortedTrackers();
        for (Iterator<Tracker> iter = newTrackers.iterator(); iter.hasNext(); ) {
            Tracker t = iter.next();
            String announceURL = t.announceURL.replace("&#61;", "=");
            if (announceURL.equals(announce) || annlist.contains(announceURL)) {iter.remove();}
        }
        if (!newTrackers.isEmpty()) {
            buf.append("<tr class=header><th>").append(_t("Add Tracker")).append("</th><th>");
            if (announce == null) {buf.append(_t("Announce URL")).append("</th><th>").append(_t("Primary"));}
            else {buf.append("</th><th>");}
            buf.append("</th><th id=add>").append("Add").append("</th></tr>\n");
            for (Tracker t : newTrackers) {
                String name = t.name;
                int hc = t.announceURL.hashCode();
                String announceURL = t.announceURL.replace("&#61;", "=");
                buf.append("<tr><td><span class=info_tracker>").append(name).append("</span></td><td>")
                   .append(announceURL).append("</td><td>")
                   .append("<input type=radio class=optbox name=primary value=\"")
                   .append(hc).append("\"");
                if (isRunning) {buf.append(" disabled");}
                buf.append("></td><td>")
                   .append("<input type=checkbox class=optbox id=\"").append(name).append("\" name=\"addTracker-")
                   .append(hc).append("\" title=\"").append(_t("Add tracker")).append("\"");
                if (isRunning) {buf.append(" disabled");}
                buf.append("></td></tr>\n");
            }
        }

        String com = meta.getComment();
        if (com == null) {com = "";}
        else if (!com.isEmpty()) {com = DataHelper.escapeHTML(com);}
        buf.append("<tr class=header><th colspan=4>")
           .append(_t("Torrent Comment"))
           .append("</th></tr>\n<tr><td colspan=4 id=addCommentText><textarea name=nofilter_newTorrentComment cols=88 rows=4");
        if (isRunning) {buf.append(" readonly");}
        buf.append(">").append(com).append("</textarea></td></tr>\n");
        if (isRunning) {
            buf.append("<tfoot><tr><td colspan=4><span id=stopfirst>")
               .append(_t("Torrent must be stopped in order to edit"))
               .append("</span></td></tr></tfoot>\n</table>\n</div>\n");
            return;
        } else {
            buf.append("<tfoot><tr><td colspan=4><input type=submit name=editTorrent value=\"")
               .append(_t("Save Changes"))
               .append("\" class=accept></td></tr></tfoot>\n</table>\n</div>\n");
        }
    }

    /**
     *  @since 0.9.53
     */
    private void saveTorrentEdit(Snark snark, Map<String, String[]> postParams) {
        if (!snark.isStopped()) {
            _manager.addMessage(_t("Torrent must be stopped")); // shouldn't happen
            return;
        }
        EditParams ep = parseEditParams(postParams);
        MetaInfo meta = snark.getMetaInfo();
        if (meta == null) {
            _manager.addMessage("Can't edit magnet"); // shouldn't happen
            return;
        }
        if (!hasChanges(meta, ep)) {
            _manager.addMessage("No changes to torrent, not saved");
            return;
        }
        AnnounceListResult alr = buildAnnounceList(meta, ep, _manager.getSortedTrackers(), _manager.util().udpEnabled());
        if (alr.newAnnList == null) {
            alr.thePrimary = null;
        }
        String newComment = ep.newComment.isEmpty() ? null : ep.newComment;
        String newCreatedBy = null; // createdBy field disabled per spec
        MetaInfo newMeta = new MetaInfo(meta, alr.thePrimary, alr.newAnnList, newComment, newCreatedBy, meta.getWebSeedURLs());
        File f = new File(_manager.util().getTempDir(), "edit-" + _manager.util().getContext().random().nextLong() + ".torrent");
        try (OutputStream out = _manager.areFilesPublic() ? new FileOutputStream(f) : new SecureFileOutputStream(f)) {
            out.write(newMeta.getTorrentData());
            boolean ok = FileUtil.rename(f, new File(snark.getName()));
            if (!ok) {
                _manager.addMessage("Save edit changes failed");
                return;
            }
        } catch (IOException ioe) {
            _manager.addMessage("Save edit changes failed: " + ioe.getMessage());
            return;
        } finally {f.delete();}
        snark.replaceMetaInfo(newMeta);
        _manager.addMessage("Torrent changes saved");
    }

    /**
     * Immutable context for rendering a single file row in the torrent file browser.
     * Package-visible for testing.
     *
     * @since 0.9.71+
     */
    static class FileRowContext {
        final String decodedBase;
        final Storage storage;
        final boolean showPriority;
        final boolean isTopLevel;

        FileRowContext(String decodedBase, Storage storage, boolean showPriority, boolean isTopLevel) {
            this.decodedBase = decodedBase;
            this.storage = storage;
            this.showPriority = showPriority;
            this.isTopLevel = isTopLevel;
        }
    }

    /**
     * Mutable counters for file row rendering.
     * Package-visible for testing.
     *
     * @since 0.9.71+
     */
    static class FileRowCounters {
        int videoCount = 0;
        int imgCount = 0;
        int txtCount = 0;
        boolean showSaveButton = false;
    }

    /**
     * Parsed edit form parameters. Package-visible for testing.
     */
    static class EditParams {
        final List<Integer> toAdd;
        final List<Integer> toDel;
        final Integer primary;
        final String newComment;
        final String newCreatedBy;

        EditParams(List<Integer> toAdd, List<Integer> toDel, Integer primary, String newComment, String newCreatedBy) {
            this.toAdd = toAdd;
            this.toDel = toDel;
            this.primary = primary;
            this.newComment = newComment;
            this.newCreatedBy = newCreatedBy;
        }
    }

    /**
     * Result of building the new announce list. Package-visible for testing.
     */
    static class AnnounceListResult {
        final List<List<String>> newAnnList;
        String thePrimary;

        AnnounceListResult(List<List<String>> newAnnList, String thePrimary) {
            this.newAnnList = newAnnList;
            this.thePrimary = thePrimary;
        }
    }

    /**
     * Parses the edit form POST parameters into an {@link EditParams} object.
     * Recognized keys: "addTracker-{id}", "removeTracker-{id}", "primary",
     * "nofilter_newTorrentComment", "nofilter_newTorrentCreatedBy".
     *
     * @param postParams the request parameter map (Jetty-style String[] values)
     * @return parsed parameters, never null
     * @since 0.9.71+
     */
    static EditParams parseEditParams(Map<String, String[]> postParams) {
        List<Integer> toAdd = new ArrayList<>();
        List<Integer> toDel = new ArrayList<>();
        Integer primary = null;
        String newComment = "";
        String newCreatedBy = "";
        for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue()[0];   // jetty arrays
            if (key.startsWith("addTracker-")) {
                int id = I2PSnarkUtil.parseInt(key.substring(11), -1);
                if (id >= 0) {toAdd.add(id);}
            } else if (key.startsWith("removeTracker-")) {
                int id = I2PSnarkUtil.parseInt(key.substring(14), -1);
                if (id >= 0) {toDel.add(id);}
            } else if (key.equals("primary")) {
                int p = I2PSnarkUtil.parseInt(val, -1);
                if (p >= 0) {primary = p;}
            } else if (key.equals("nofilter_newTorrentComment")) {
                newComment = val.trim();
            } else if (key.equals("nofilter_newTorrentCreatedBy")) {
                newCreatedBy = val.trim();
            }
        }
        return new EditParams(toAdd, toDel, primary, newComment, newCreatedBy);
    }

    /**
     * Renders a single file row for the torrent file browser table.
     * Extracted from getListHTML for testability and UI responsiveness.
     *
     * @param buf the StringBuilder to append HTML to
     * @param ctx immutable context (decoded base path, storage, priority flag, top-level flag)
     * @param fai the file and its metadata from the sorted file list
     * @param rowEven true for even row (alternating row class)
     * @param counters mutable accumulators (video/img/text counts, save button flag)
     * @return the next rowEven value (toggled)
     * @since 0.9.71+
     */
    /** Warn icon for missing/unrecognized files. */
    private String warnIcon(String alt, String tooltip) {
        StringBuilder ico = new StringBuilder();
        appendIcon(ico, "warn", alt, tooltip, false, true, true);
        return ico.toString();
    }

    /** Tick icon for complete files. */
    private String tickIcon() {
        StringBuilder ico = new StringBuilder();
        appendIcon(ico, "tick", _t("Complete"), _t("Complete"), false, true, true);
        return ico.toString();
    }

    /** Priority indicator icon (block/skip, clock/normal, red clock/high). */
    private String priorityIcon(int priority) {
        StringBuilder ico = new StringBuilder(64);
        ico.append("<div class=priorityIndicator>");
        if (priority < 0) {appendIcon(ico, "block", "", "", false, false, true);}
        else if (priority == 0) {appendIcon(ico, "clock", "", "", false, false, true);}
        else {appendIcon(ico, "clock_red", "", "", false, false, true);}
        ico.append("</div>");
        return ico.toString();
    }

    private boolean renderFileRow(StringBuilder buf, FileRowContext ctx, Sorters.FileAndIndex fai,
                                   boolean rowEven, FileRowCounters counters) {
        File item = fai.file;
        boolean complete = false;
        String status = "";
        long length = item.length();
        int fileIndex = fai.index;
        int priority = 0;

        if (fai.isDirectory) {
            complete = true;
        } else if (ctx.storage == null) {
            complete = true;
            status = warnIcon(_t("Not found"), _t("Torrent not found"));
        } else {
            long remaining = fai.remaining;
            if (remaining < 0) {
                complete = true;
                status = warnIcon(_t("Unrecognized"), _t("File not found in torrent"));
            } else if (remaining == 0 || length <= 0) {
                complete = true;
                status = tickIcon();
            } else {
                priority = fai.priority;
                status = priorityIcon(priority) + buildProgressBar(length, remaining, true, false, false, true);
            }
        }

        String rowClass = (rowEven ? "even" : "odd");
        String completed = (complete ? "completed" : "incomplete");
        buf.append("<tr class=\"").append(rowClass).append(' ').append(completed).append("\">");

        String path = addPaths(ctx.decodedBase, item.getName());
        if (fai.isDirectory) {
            complete = true;
            if (!path.endsWith("/")) {path = addPaths(path, "/");}
        }
        path = encodePath(path);
        String icon = toIcon(item);
        String mime = getMimeType(path);
        if (mime == null) {mime = "";}
        boolean isAudio = isAudio(mime);
        boolean isVideo = !isAudio && isVideo(mime);
        boolean isImage = mime.startsWith("image/");
        boolean isText = mime.startsWith("text/") || mime.equals("application/javascript") ||
                         mime.equals("application/json") || mime.equals("application/xml") ||
                         path.toLowerCase().endsWith(".asc") || path.toLowerCase().endsWith(".bat") ||
                         path.toLowerCase().endsWith(".ini") || path.toLowerCase().endsWith(".md5") ||
                         path.toLowerCase().endsWith(".sh") || path.toLowerCase().endsWith(".url");
        boolean isPDF = mime.equals("application/pdf");
        if (isAudio || isImage || isVideo || mime.equals("application/pdf")) {
            int semicolonIndex = mime.indexOf(';');
            if (semicolonIndex != -1) {mime = mime.substring(0, semicolonIndex).trim();}
        }

        buf.append("<td class=\"fileIcon");
        if (!complete) {buf.append(" volatile");}
        else if (isText) {
            buf.append(" text");
            counters.txtCount++;
        }
        buf.append("\">");

        String preview = null;
        if (isVideo && complete) {counters.videoCount++;}
        if (complete || (isAudio && fai.preview > 100 * 1024) ||
            (isVideo && fai.preview > 5 * 1024 * 1024 && fai.preview / (double) fai.length >= 0.01d)) {
            String ppath = complete ? path : path + "?limit=" + fai.preview;
            if (!complete) {
                double pct = fai.preview / (double) fai.length;
                preview = " &nbsp;<span class=audioPreview>" + _t("Preview") + ": " +
                           (new DecimalFormat("0.00%")).format(pct) + "</span>";
            }
            if (isAudio || isVideo) {
                buf.append("\n<style>.thumb{max-height:inherit!important;max-width:240px!important}</style>\n");
                if (isAudio) {buf.append("<audio");}
                else {buf.append("<video");}
                buf.append(" controls><source src=\"").append(ppath);
                if (isVideo) {buf.append("#t=20");}
                buf.append("\" type=\"").append(mime).append("\">");
            }
            buf.append("<a href=\"").append(ppath).append("\">");
            if (mime.startsWith("image/") && !ppath.endsWith(".ico")) {
                buf.append("<img alt=\"\" border=0 class=thumb src=\"")
                   .append(ppath).append("\" data-lb data-lb-caption=\"")
                   .append(DataHelper.escapeHTML(item.getName())).append("\" data-lb-group=allInDir></a>");
                counters.imgCount++;
            } else if (mime.startsWith("image/") && ppath.endsWith(".ico")) {
                buf.append("<img alt=\"\" width=16 height=16 class=favicon border=0 src=\"")
                   .append(ppath).append("\" data-lb data-lb-caption=\"")
                   .append(DataHelper.escapeHTML(item.getName())).append("\" data-lb-group=allInDir></a>");
            } else {
                appendIcon(buf, icon, _t("Open"), "", false, true);
                buf.append("</a>");
            }
            if (isAudio) {buf.append("</audio>");}
            else if (isVideo) {buf.append("</video>");}
        } else {
            appendIcon(buf, icon, "", "", false, true);
        }
        buf.append("</td><td class=\"snarkFileName");
        if (!complete) {buf.append(" volatile");}
        buf.append("\">");

        if (complete) {
            buf.append("<a href=\"").append(path).append("\"");
            if (isAudio || isVideo || isText || isImage || isPDF) {buf.append(" target=_blank");}
            if (mime.equals("audio/mpeg")) {buf.append(" class=targetfile");}
            buf.append(">");
        }
        buf.append(DataHelper.escapeHTML(item.getName()));
        if (complete) {
            buf.append("</a>");
            if (mime.equals("audio/mpeg")) {
                String tags = Mp3Tags.getTags(item);
                buf.append("<a class=tags href=\"").append(path).append("\" target=_blank hidden>");
                if (tags != null) {buf.append(tags);}
                else {buf.append(DataHelper.escapeHTML(item.getName()));}
                buf.append("</a>");
            }
        } else if (preview != null) {buf.append(preview);}
        buf.append("</td><td class=fileSize>");
        if (!fai.isDirectory) {buf.append(formatSize(length));}
        buf.append("</td><td class=\"fileStatus volatile\">").append(status).append("</td>");

        if (ctx.showPriority) {
            counters.showSaveButton = true;
            appendPriority(buf, ctx.showPriority, complete, fai.isDirectory, priority, fileIndex);
        }
        buf.append("</tr>\n");
        return !rowEven;
    }

    /**
     * Determines whether the edit parameters represent any actual change to
     * the torrent's metadata. Avoids a save cycle when the form is submitted
     * without modifications.
     *
     * @param meta current torrent MetaInfo
     * @param ep parsed edit parameters
     * @return true if any field differs, false if identical
     * @since 0.9.71+
     */
    static boolean hasChanges(MetaInfo meta, EditParams ep) {
        String oldPrimary = meta.getAnnounce();
        String oldComment = meta.getComment() == null ? "" : meta.getComment();
        String oldCreatedBy = meta.getCreatedBy() == null ? "" : meta.getCreatedBy();
        return !ep.toAdd.isEmpty()
            || !ep.toDel.isEmpty()
            || (ep.primary != null && !String.valueOf(ep.primary).equals(oldPrimary))
            || !oldComment.equals(ep.newComment)
            || !oldCreatedBy.equals(ep.newCreatedBy);
    }

    /**
     * Builds the new announce list and primary tracker from the current
     * MetaInfo and edit parameters.
     *
     * @param meta current torrent MetaInfo
     * @param ep parsed edit parameters
     * @param trackers the sorted list of known trackers (for resolving add IDs)
     * @param udpEnabled whether UDP trackers are enabled in config
     * @return new announce list (may be null) and primary tracker URL
     * @since 0.9.71+
     */
    static AnnounceListResult buildAnnounceList(MetaInfo meta, EditParams ep, List<Tracker> trackers, boolean udpEnabled) {
        List<List<String>> alist = meta.getAnnounceList();
        Set<String> annlist = new TreeSet<>();
        if (alist != null && !alist.isEmpty()) {
            for (List<String> alist2 : alist) { // strip non-i2p trackers
                for (String s : alist2) {
                    if (isI2PTracker(s, udpEnabled)) {annlist.add(s);}
                }
            }
        }
        String oldPrimary = meta.getAnnounce();
        if (oldPrimary != null && isI2PTracker(oldPrimary, udpEnabled)) {annlist.add(oldPrimary);}
        for (Integer i : ep.toDel) {
            int hc = i.intValue();
            for (Iterator<String> iter = annlist.iterator(); iter.hasNext(); ) {
                String s = iter.next();
                if (s.hashCode() == hc) {iter.remove();}
            }
        }
        for (Integer i : ep.toAdd) {
            int hc = i.intValue();
            for (Tracker t : trackers) {
                if (t.announceURL.hashCode() == hc) {
                    annlist.add(t.announceURL);
                    break;
                }
            }
        }
        String thePrimary = oldPrimary;
        if (ep.primary != null) {
            int hc = ep.primary.intValue();
            for (String s : annlist) {
                if (s.hashCode() == hc) {
                    thePrimary = s;
                    break;
                }
            }
        }
        List<List<String>> newAnnList;
        if (annlist.isEmpty()) {
            newAnnList = null;
            thePrimary = null;
        } else {
            List<String> aalist = new ArrayList<>(annlist);
            newAnnList = Collections.singletonList(aalist);
            if (!aalist.contains(thePrimary)) {thePrimary = aalist.get(0);}
        }
        return new AnnounceListResult(newAnnList, thePrimary);
    }

    /**
     * Whether the user agent cannot handle collapsible panels.
     *
     * @param req the request
     * @return true if panels should not be collapsed
     */
    static boolean noCollapsePanels(HttpServletRequest req) {
        // check for user agents that can't toggle the collapsible panels...
        // TODO: QupZilla supports panel collapse as of circa v2.1.2, so disable conditionally
        // TODO: Konqueror supports panel collapse as of circa v5 (5.34), so disable conditionally
        String ua = req.getHeader("user-agent");
        return ua != null && (ua.contains("Konq") || ua.contains("konq") ||
                              ua.contains("QupZilla") || ua.contains("Dillo") ||
                              ua.contains("Netsurf") || ua.contains("Midori"));
    }

    /**
     * Whether "a" equals "b", or "a" is a directory and a parent of
     * file or directory "b", canonically speaking.
     *
     * @param a the parent directory candidate
     * @param b the file or directory to check
     * @return true if a contains b or they are the same
     * @since 0.9.15
     */
    private static boolean isParentOf(File a, File b) {
        try {
            a = a.getCanonicalFile();
            b = b.getCanonicalFile();
        } catch (IOException ioe) {return false;}
        if (a.equals(b)) {return true;}
        if (!a.isDirectory()) {return false;}
        // easy case
        if (!b.getPath().startsWith(a.getPath())) {return false;}
        // dir by dir
        while (!a.equals(b)) {
            b = b.getParentFile();
            if (b == null) {return false;}
        }
        return true;
    }

    /**
     * Reject a torrent whose data directory lies inside another torrent's data
     * directory, or (when checkContained) would contain another torrent's data
     * directory. Adds a message explaining the rejection.
     *
     * @param dataDir the data directory for the torrent being added or created
     * @param checkContained also reject when it would contain another torrent's data
     * @return true to reject
     * @since 0.9.71+
     */
    private boolean checkNestedTorrent(File dataDir, boolean checkContained) {
        for (Snark s : _manager.getTorrents()) {
            Storage storage = s.getStorage();
            if (storage == null) continue;
            File sbase = storage.getBase();
            if (isParentOf(sbase, dataDir)) {
                String msg = _t("Cannot add torrent {0} inside another torrent: {1}", dataDir.getAbsolutePath(), sbase);
                _manager.addMessageAndPrint(msg);
                return true;
            }
            if (checkContained && isParentOf(dataDir, sbase)) {
                String msg = _t("Cannot add torrent {0} including another torrent: {1}", dataDir.getAbsolutePath(), sbase);
                _manager.addMessageAndPrint(msg);
                return true;
            }
        }
        return false;
    }

    /**
     * Whether we are running in standalone mode.
     *
     * @return whether standalone
     * @since 0.9.54+
     */
    boolean isStandalone() {
        if (_context.isRouterContext()) {return false;}
        else {return true;}
    }
}

