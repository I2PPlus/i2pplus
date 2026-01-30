package org.klomp.snark.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.*;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.servlet.RequestWrapper;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.FileUtil;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.UIMessages;
import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MagnetURI;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Peer;
import org.klomp.snark.PeerID;
import org.klomp.snark.Snark;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.Storage;
import org.klomp.snark.TorrentCreateFilter;
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
    private String _contextPath; /** generally "/i2psnark" */
    private String _contextName; /** generally "i2psnark" */
    private transient SnarkManager _manager;
    private long _nonce;
    private String _themePath;
    private String _resourcePath;
    private String _imgPath;
    private String _lastAnnounceURL;

    private static final String DEFAULT_NAME = "i2psnark";
    public static final String PROP_CONFIG_FILE = "i2psnark.configFile";
    public static final String WARBASE = "/.res/";
    static final char HELLIP = '\u2026';
    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final String RC_PROP_ENABLE_SORA_FONT = "routerconsole.displayFontSora";
    private int searchResults;
    private static boolean debug = false;
    String cspNonce = Integer.toHexString(_context.random().nextInt());
    public I2PSnarkServlet() {super();}

    @Override
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        String cpath = getServletContext().getContextPath();
        _contextPath = cpath == "" ? "/" : cpath;
        _contextName = cpath == "" ? DEFAULT_NAME : cpath.substring(1).replace("/", "_");
        _nonce = _context.random().nextLong();
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
    }

    @Override
    public void destroy() {
        if (_manager != null) {_manager.stop();}
        super.destroy();
    }

    /**
     *  We override this to set the file relative to the storage directory
     *  for the torrent.
     *
     *  @param pathInContext should always start with /
     */
    @Override
    public File getResource(String pathInContext) {
        synchronized(this) {
        if (pathInContext == null || pathInContext.equals("/") || pathInContext.equals("/index.jsp") ||
            !pathInContext.startsWith("/") || pathInContext.length() == 0 || pathInContext.equals("/index.html") ||
            pathInContext.startsWith(WARBASE)) {
            return super.getResource(pathInContext);
        }

        pathInContext = pathInContext.substring(1); // files in the i2psnark/ directory - get top level
        File top = new File(pathInContext);
        File parent;

        while ((parent = top.getParentFile()) != null) {top = parent;}
        Snark snark = _manager.getTorrentByBaseName(top.getPath());
        if (snark != null) {
            Storage storage = snark.getStorage();
            if (storage != null) {
                File sbase = storage.getBase();
                String child = pathInContext.substring(top.getPath().length());
                return new File(sbase, child);
            }
        }

        return new File(_resourceBase, pathInContext);

        }
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

    public boolean isAdvanced() {return _context.getBooleanProperty(PROP_ADVANCED);}

    public boolean useSoraFont() {
        return _context.getBooleanProperty(RC_PROP_ENABLE_SORA_FONT) || isStandalone();
    }

    /**
     * Handle what we can here, calling super.doGet() or super.doPost() for the rest.
     *
     * Some parts modified from Jetty
     */
    private void doGetAndPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Get HTTP method and servlet path
        String method = req.getMethod(); // since we are not overriding handle*(), do this here
        String path = req.getServletPath(); // this is the part after /i2psnark
        String lang = req.getParameter("lang");

        req.setCharacterEncoding("UTF-8"); // Set request encoding early

        // Set Content Security Policy header for JS requests
        String csp = "default-src 'self'; base-uri 'self'; connect-src 'self'; worker-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; ";
        if (path.contains(".js")) {resp.setHeader("Content-Security-Policy", csp);}

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

        // AJAX for mainsection
        if ("/.ajax/xhr1.html".equals(path)) {
            setXHRHeaders(resp, cspNonce, false);
            boolean canWrite;
            synchronized (this) {
                canWrite = _resourceBase.canWrite();
            }
            out = resp.getWriter();
            out.write("<!DOCTYPE HTML>\n<html>\n<body id=snarkxhr>\n<div id=mainsection>\n");
            writeTorrents(out, req, canWrite);
            out.write("\n</div>\n</body>\n</html>\n");
            out.flush();
            return;
        }

        // AJAX for screenlog
        if ("/.ajax/xhrscreenlog.html".equals(path)) {
            setXHRHeaders(resp, cspNonce, false);
            boolean canWrite;
            synchronized (this) {
                canWrite = _resourceBase.canWrite();
            }
            out = resp.getWriter();
            out.write("<!DOCTYPE HTML>\n<html>\n<body id=snarkxhrlogs>\n");
            writeMessages(out, isConfigure, peerString);
            out.write("</body>\n</html>\n");
            out.flush();
            return;
        }

        boolean isIndex = (path.isEmpty() || "/".equals(path) || "index.jsp".equals(path));

        // Handle non index, configure, or known special paths
        if (!(isIndex || "/index.html".equals(path) || "/_post".equals(path) || isConfigure)) {
            if (path.endsWith("/")) {
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
                    if (listing != null) {
                        setHTMLHeaders(resp, cspNonce, false);
                        resp.setContentType("audio/mpegurl; charset=UTF-8; name=\"playlist.m3u\"");
                        resp.addHeader("Content-Disposition", "attachment; filename=\"playlist.m3u\"");
                        resp.getWriter().write(listing);
                        return;
                    } else {
                        resp.sendError(404);
                        return;
                    }
                } else {
                    String base = addPaths(req.getRequestURI(), "/");
                    String listing = getListHTML(resource, base, true,
                        "POST".equals(method) ? req.getParameterMap() : null,
                        req.getParameter("sort"));
                    if ("POST".equals(method)) {
                        sendRedirect(req, resp, ""); // POST-Redirect-GET
                        return;
                    } else if (listing != null) {
                        setHTMLHeaders(resp, cspNonce, true);
                        resp.getWriter().write(listing);
                        return;
                    } else {
                        resp.sendError(404);
                        return;
                    }
                }
            } else {
                if ("GET".equals(method) || "HEAD".equals(method)) {
                    super.doGet(req, resp);
                } else if ("POST".equals(method)) {
                    super.doPost(req, resp);
                } else {
                    resp.sendError(405);
                }
                return;
            }
        }

        setHTMLHeaders(resp, cspNonce, true);

        String nonce = req.getParameter("nonce");
        if (nonce != null) {
            if (( "POST".equals(method) || "Clear".equals(req.getParameter("action"))) &&
                nonce.equals(String.valueOf(_nonce))) {
                processRequest(req);
            } else {
                _manager.addMessage("Please retry form submission (bad nonce)");
            }
            sendRedirect(req, resp, peerString);
            return;
        }

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

        if (isConfigure) {
            buf.append("<a href=")
               .append(_contextPath)
               .append("/ title=\"")
               .append(_t("Torrents"))
               .append("\" id=nav_main class=\"snarkNav isConfig\">")
               .append(_contextName.equals(DEFAULT_NAME) ? _t("I2PSnark") : _contextName)
               .append("</a>");
        } else {
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

        buf.append("</div>\n");

        // Render search form when multiple torrents exist
        if (_manager.getTorrents().size() > 1) {
            String s = req.getParameter("search");
            boolean searchActive = (s != null && !s.equals(""));
            buf.append("<form id=snarkSearch action=\"").append(_contextPath).append("\" method=GET hidden>\n")
               .append("<span id=searchwrap><input id=searchInput type=search required name=search size=20 placeholder=\"")
               .append(_t("Search torrents")).append("\"");
            if (searchActive) {
                buf.append(" value=\"").append(DataHelper.escapeHTML(s.trim())).append("\"");
            }
            buf.append("><a href=").append(_contextPath).append(" title=\"").append(_t("Clear search"))
               .append("\" hidden>x</a></span><input type=submit value=\"Search\">\n</form>\n");
        }

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
            out.write("<div class=logshim></div>\n</div>\n");
            writeConfigForm(out, req);
            writeTorrentCreateFilterForm(out, req);
            writeTrackerForm(out, req);
        } else {
            boolean canWrite;
            synchronized (this) {
                canWrite = _resourceBase.canWrite();
            }
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

        String jsPath = "<script src=" + _resourcePath + "js/";

        if (!isConfigure) {
            out.write(jsPath + "toggleLinks.js type=module></script>\n");
        }
        out.write(jsPath + "setFilterQuery.js type=module></script>\n");

        if (!isStandalone()) {
            out.write(FOOTER);
        } else {
            out.write(FOOTER_STANDALONE);
        }
        out.flush();
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

        String resourcePath = debug ? "/themes/" : _contextPath + WARBASE;

        buf.append(DOCTYPE)
           .append("<html")
           .append(isStandalone() ? " class=standalone" : "")
           .append(" style=\"background:").append(pageBackground).append("\">\n<head>\n")
           .append("<meta charset=utf-8>\n<meta name=viewport content=\"width=device-width, initial-scale=1\">\n")
           .append("<script nonce=").append(cspNonce).append(">const theme = \"").append(theme).append("\";</script>\n");

        if (!isConfigure && !isStandalone()) {
            buf.append("<link rel=modulepreload href=/js/iframeResizer/updatedEvent.js>\n")
               .append("<link rel=modulepreload href=/js/iframeResizer/iframeResizer.contentWindow.js>\n")
               .append("<link rel=modulepreload href=/js/setupIframe.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/tunnelCounter.js>\n");
        }
        if (!isConfigure) {
            buf.append("<link rel=modulepreload href=").append(resourcePath).append("js/refreshTorrents.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/snarkSort.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/toggleLinks.js>\n")
               .append("<link rel=modulepreload href=").append(resourcePath).append("js/toggleLog.js>\n");
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
               .append("  window.totalSnarks = totalSnarks;\n</script>\n")
               .append("<script src=").append(resourcePath).append("js/snarkWork.js type=module></script>\n")
               .append("<script src=").append(resourcePath).append("js/messageTypes.js type=module></script>\n");
            if (!isStandalone()) {
                buf.append("<script src=").append(resourcePath).append("js/tunnelCounter.js type=module></script>\n");
            }
            buf.append("<script nonce=").append(cspNonce).append(" type=module>\n")
               .append("  import {initSnarkRefresh} from \"").append(resourcePath).append("js/refreshTorrents.js\";\n")
               .append("  document.addEventListener(\"DOMContentLoaded\", initSnarkRefresh);\n</script>\n");
            if (delay > 0) {
                buf.append("<noscript><meta http-equiv=refresh content=\"").append(delay < 60 ? 60 : delay)
                   .append(";").append(_contextPath).append("/").append(peerString).append("\"></noscript>\n");
            }
        }

        // Append CSS assets and user overrides
        buf.append(HEADER_A).append(_themePath).append(HEADER_B).append("\n");
        buf.append(HEADER_A).append(_themePath).append(HEADER_I).append("\n");

        String slash = String.valueOf(java.io.File.separatorChar);
        String themeBase = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() + slash +
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
            buf.append(HEADER_A).append(_themePath).append(HEADER_Z).append("\n");
        }

        // Larger fonts for CJK languages
        if ("zh".equals(lang) || "ja".equals(lang) || "ko".equals(lang)) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_D).append("\n");
        }

        if (noCollapse || !collapsePanels) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_C).append("\n");
        }

        buf.append("<style id=cssfilter></style>\n<style id=toggleLogCss></style>\n");

        if (!isStandalone()) {
            long now = _context.clock().now();
            buf.append("<style id=graphcss>:root{--snarkGraph:url('/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false")
               .append("&period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&hideTitle=true")
               .append("&hideGrid=true&t=").append(now).append("')}\"</style>\n");
        }

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
                         "object-src 'none'; media-src '" + (allowMedia ? "self" : "none") + "'";
            resp.setHeader("Content-Security-Policy", csp);
            resp.setHeader("Permissions-Policy", "fullscreen=(self)");
            resp.setHeader("Referrer-Policy", "same-origin");
        }

        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-XSS-Protection", "1; mode=block");
    }

    /**
     * Sets Cross-Origin Resource Sharing (CORS) and Content Security Policy (CSP) headers
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
        int maxAge = Math.min(Integer.parseInt(refresh), 60);
        resp.setHeader("Cache-Control", "private, no-cache, max-age=" + maxAge);
        resp.setHeader("Content-Security-Policy", "default-src 'none'; child-src 'self'");
    }

    private transient UIMessages.Message lastMessage;

    private synchronized String getLastMessage() {
        List<UIMessages.Message> msgs = _manager.getMessages();
        if (lastMessage == null || (msgs.size() > 0 && msgs.get(msgs.size() - 1) != lastMessage)) {
            if (!msgs.isEmpty()) {lastMessage = msgs.get(msgs.size() - 1);}
            else {lastMessage = null;}
        }
        return lastMessage != null ? lastMessage.message : null;
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
        _resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
        List<UIMessages.Message> msgs = _manager.getMessages();
        int entries = msgs.size();
        StringBuilder buf = new StringBuilder(entries*256);
        if (!msgs.isEmpty()) {
            buf.append("<div id=screenlog")
               .append(isConfigure ? " class=configpage" : "")
               .append(" tabindex=0>\n<a id=closelog href=\"")
               .append(_contextPath).append('/');
            if (isConfigure) {buf.append("configure");}
            if (peerString.length() > 0) {buf.append(peerString).append("&amp;");}
            else {buf.append("?");}
            int lastID = msgs.get(msgs.size() - 1).id;
            String tx = _t("clear messages");
            String x = _t("Expand");
            String s = _t("Shrink");
            buf.append("action=Clear&amp;id=").append(lastID)
               .append("&amp;nonce=").append(_nonce).append("\">");
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
                if (msg.contains("class=infohash")) {msg = msg.replaceFirst(" \\(", "</span> (");} // does this fix the display snafu?
                if (msg.contains(_t("Warning - No I2P"))) {msg = msg.replace("</span>", "");}
                buf.append("<li class=msg>").append(msg).append("</li>\n");
            }
            buf.append("</ul>");
        } else {buf.append("<div id=screenlog hidden><ul id=messages></ul>");}
        buf.append("</div>\n<script src=")
           .append(_resourcePath)
           .append("js/toggleLog.js type=module></script>\n");
        int delay = 0;
        delay = _manager.getRefreshDelaySeconds();
        if (delay > 0 && _context.isRouterContext()) {
            buf.append("<script src=\"").append(_resourcePath).append("js/graphRefresh.js?")
               .append(CoreVersion.VERSION).append("\" defer></script>\n");
        }
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
     * @param out the PrintWriter to which the HTML output will be written
     * @param req the HttpServletRequest containing the request parameters
     * @param canWrite a boolean indicating whether the data directory is writable
     * @return true if the current page is the first page of the torrent list
     * @throws IOException if an I/O error occurs while writing to the output stream
     */
    private boolean writeTorrents(PrintWriter out, HttpServletRequest req, boolean canWrite) throws IOException {
        final long stats[] = new long[6];
        String filter = req.getParameter("filter") != null ? req.getParameter("filter") : "";
        String peerParam = req.getParameter("p");
        String search = req.getParameter("search");
        String srt = req.getParameter("sort");
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
        boolean sortEnabled = srt != null && !srt.isEmpty();
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
            try { start = Math.max(0, Math.min(total - 1, Integer.parseInt(stParam))); } catch(NumberFormatException ignored) {}
        }

        int pageSize = filterEnabled ? 9999 : Math.max(_manager.getPageSize(), 10);
        String ps = req.getParameter("ps");
        if ("null".equals(ps)) ps = Integer.toString(pageSize);
        if (ps != null) {
            try { pageSize = Integer.parseInt(ps); } catch(NumberFormatException ignored) {}
        }

        boolean isDegraded = false, noThinsp = false;
        String ua = req.getHeader("User-Agent");
        if (ua != null) {
            isDegraded = ServletUtil.isTextBrowser(ua);
            noThinsp = isDegraded || ua.startsWith("Opera");
        }

        if (isForm) {
            if (showStatusFilter) renderFilterBar(out, req);
            else out.write("<form id=torrentlist action=_post method=POST target=processForm>\n");
            writeHiddenInputs(out, req, null);
            out.flush();
        }

        out.write(TABLE_HEADER);
        paginator(out, req, start, pageSize, total, filter, noThinsp, isForm, searchActive, (searchActive ? search.length() : 0));
        out.write(appendSnarkHeader(req, snarks, start, pageSize, filter, peerParam, srt, _contextPath));
        out.flush();

        String uri = _contextPath + '/';
        boolean showDebug = "2".equals(peerParam);
        int end = Math.min(start + pageSize, snarks.size());
        StringBuilder buf = new StringBuilder(2048);

        for (int i = start; i < end; i++) {
            Snark snark = snarks.get(i);
            boolean showPeers = showDebug || "1".equals(peerParam) || Base64.encode(snark.getInfoHash()).equals(peerParam);
            boolean hide = false;
            buf.setLength(0);
            displaySnark(out, req, snark, uri, i, stats, showPeers, isDegraded, noThinsp, showDebug,
                         hide, false, canWrite, filter, filterEnabled, srt, sortEnabled, buf);

            // additionally accumulate downloads, uploads, ETA, flags
            if (snark.getPeerList().size() >= 1) {
                if (snark.getDownloadRate() > 0) downloads++;
                if (snark.getUploadRate() > 0) { uploads++; isUploading = true; }
                hasPeers = true;
                long needed = snark.getNeededLength();
                if (needed > total) needed = total;
                if (stats[2] > 0 && needed > 0) totalETA += needed / stats[2];
            }
        }

        if (total == 0) {
            out.write("<tbody id=noTorrents><tr id=noload class=noneLoaded><td colspan=12><i>");
            synchronized(this) {
                File dd = _resourceBase;
                if (!dd.exists() && !dd.mkdirs()) out.write(_t("Data directory cannot be created") + ": " + DataHelper.escapeHTML(dd.toString()));
                else if (!dd.isDirectory()) out.write(_t("Not a directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                else if (!dd.canRead()) out.write(_t("Unreadable") + ": " + DataHelper.escapeHTML(dd.toString()));
                else if (!canWrite) out.write(_t("No write permissions for data directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                else if (searchActive) out.write(_t("No torrents found."));
                else out.write(_t("No torrents loaded."));
            }
            out.write("</i></td></tr></tbody>");
        }

        //paginator(out, req, start, pageSize, total, filter, noThinsp, true, searchActive, (searchActive ? search.length() : 0));
        appendSnarkFooter(out, buf, stats, total, isConnected, noSnarks, hasPeers, isUploading, dht, isStandalone(), debug, peerParam);

        if (showDebug) out.write("<tr id=dhtDebug>");
        else out.write("<tfoot><tr id=dhtDebug hidden>");
        out.write("<th colspan=12><div class=volatile>");
        if (dht != null) out.write(_manager.getBandwidthListener().toString() + dht.renderStatusHTML());
        else out.write("<b id=noDHTpeers>" + _t("No DHT Peers") + "</b>");
        out.write("</div></th></tr></tfoot>\n</table>\n");

        if (isForm) out.write("</form>\n");
        if (total > 0) out.write("<script src=/i2psnark/.res/js/convertTooltips.js></script>\n");

        out.flush();
        return start == 0;
    }

    /**
     * Builds the HTML table header row for the torrent list display, including sortable column
     * headers, peer toggling links, and activity indicators.
     *
     * <p>This method constructs the header HTML fragment for the torrents table,
     * dynamically inserting sorting links, peer visibility toggles, and icons that
     * reflect the current torrent list state and request parameters.</p>
     *
     * <p>Key enhancements include caching localized strings for reuse, extracting common
     * patterns into helper methods, and simplifying query string construction to improve
     * both server-side rendering performance and code maintainability.</p>
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
        final String txtType = _t("File type");
        final String txtName = _t("Torrent name");
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

        // Determine URL separator based on presence of query params
        boolean hasQueryParams = req.getQueryString() != null && !req.getQueryString().isEmpty();
        String separator = hasQueryParams ? "&" : "?";

        // Construct filterQuery string reliably
        String currentSearch = req.getParameter("search");
        StringBuilder fq = new StringBuilder("filter=");
        fq.append(filterParam == null || filterParam.isEmpty() ? "all" : filterParam);
        if (currentSearch != null && !currentSearch.isEmpty()) {
            fq.append(separator).append("search=").append(currentSearch);
        }
        String filterQuery = fq.toString();

        // Cache torrent activity flags and counts, break early if all true to save time
        boolean hasPeers = false, isDownloading = false, isUploading = false;
        int activeDownloadsCount = 0, activeUploadsCount = 0;
        int end = Math.min(start + pageSize, total);
        for (int i = start; i < end && !(hasPeers && isDownloading && isUploading); i++) {
            Snark s = snarks.get(i);
            if (!s.getPeerList().isEmpty()) {
                hasPeers = true;
                if (s.getDownloadRate() > 0) {
                    isDownloading = true;
                    activeDownloadsCount++;
                }
                if (s.getUploadRate() > 0) {
                    isUploading = true;
                    activeUploadsCount++;
                }
            }
        }

        // Start building header row
        buf.append("<tr>");

        // Status header sort parameters and active sort detection
        String nextSort = ("-2".equals(currentSort)) ? "2" : "-2";
        boolean isStatusSort = "2".equals(currentSort) || "-2".equals(currentSort);
        boolean isStatusDesc = currentSort != null && currentSort.startsWith("-");
        appendSortHeader(buf, contextPath, req, currentSort, nextSort, separator, filterQuery,
                         "status", "status", txtStatus, showSort, isStatusSort, isStatusDesc);

        // Peer toggle link cell
        buf.append("<th class=peerCount>");
        if (isConnected && !noSnarks && hasPeers) {
            boolean showPeers = peerParam != null;
            String queryString = showPeers ? getQueryString(req, "", null, null) : getQueryString(req, "1", null, null);
            String link = contextPath + '/' + queryString + filterQuery;
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

        // Torrent name/type sorting header (colspan=2 includes hidden checkbox)
        buf.append("<th class=torrentLink colspan=2><input id=linkswitch class=optbox type=checkbox hidden></th>");

        // Torrent header with sort icon (toggle between name and type)
        boolean isTypeSort = false;
        nextSort = null;
        if (showSort) {
            if (currentSort == null || "0".equals(currentSort) || "1".equals(currentSort)) {
                nextSort = "-1";
            } else if ("-1".equals(currentSort)) {
                nextSort = "12";
                isTypeSort = true;
            } else if ("12".equals(currentSort)) {
                nextSort = "-12";
                isTypeSort = true;
            } else {
                nextSort = "1";
            }
        }
        boolean isTorrentSortActive = "1".equals(currentSort) || "0".equals(currentSort) || "-1".equals(currentSort)
                                     || "12".equals(currentSort) || "-12".equals(currentSort);
        boolean isTorrentSortDesc = currentSort != null && currentSort.startsWith("-");
        appendSortHeader(buf, contextPath, req, currentSort, nextSort, separator, filterQuery,
                         "torrentSort", "torrent", txtTorrent,
                         showSort, isTorrentSortActive, isTorrentSortDesc);

        // Empty cell class=tName
        buf.append("<th class=tName></th>");

        // ETA header (sortable if downloading)
        if (isConnected && !noSnarks && isDownloading) {
            nextSort = null;
            if (showSort) {
                if (currentSort == null || "-4".equals(currentSort)) {
                    nextSort = "4";
                } else if ("4".equals(currentSort)) {
                    nextSort = "-4";
                }
            }
            boolean isETAActive = "4".equals(currentSort) || "-4".equals(currentSort);
            boolean isETADesc = "-4".equals(currentSort);
            appendSortHeader(buf, contextPath, req, currentSort, nextSort, separator, filterQuery,
                             "ETA", "eta", txtETA, showSort, isETAActive, isETADesc);
        } else {
            buf.append("<th class=ETA></th>");
        }

        // RX header with multi-state sorting
        boolean isDlSort = false;
        if (!noSnarks && showSort) {
            String sortRX = currentSort;
            if ("-5".equals(sortRX)) {
                nextSort = "5";
            } else if ("5".equals(sortRX)) {
                nextSort = "-6"; isDlSort = true;
            } else if ("-6".equals(sortRX)) {
                nextSort = "6"; isDlSort = true;
            } else if ("6".equals(sortRX)) {
                nextSort = "-5"; isDlSort = true;
            } else {
                nextSort = "-5";
            }
            boolean isRXActive = "-5".equals(currentSort) || "5".equals(currentSort) || "-6".equals(currentSort) || "6".equals(currentSort);
            boolean isRXDesc = "-5".equals(currentSort) || "-6".equals(currentSort);
            appendSortHeader(buf, contextPath, req, currentSort, nextSort, separator, filterQuery,
                             "rxd", "head_rx", txtRX, showSort, isRXActive, isRXDesc);
        } else {
            buf.append("<th class=rxd></th>");
        }

        // RateDown header (show if downloading)
        if (isConnected && !noSnarks && isDownloading) {
            nextSort = "-8".equals(currentSort) ? "8" : "-8";
            boolean desc8 = "8".equals(currentSort);
            // Determine peerFlag for getQueryString
            String peerFlag = (peerParam != null) ? "1" : "0";
            buf.append("<th class=rateDown><span class=sortIcon>");
            if (desc8) {
                buf.append("<span class=descending></span>");
            } else {
                buf.append("<span class=ascending></span>");
            }
            buf.append("<a class=sorter href=\"").append(contextPath).append('/')
               .append(getQueryString(req, peerFlag, nextSort, filterParam, null))
               .append(separator).append(filterQuery).append("\">");
            appendIcon(buf, "head_rx", txtRXRate, showSort ? _t("Sort by {0}", txtRX) : "", true, false);
            buf.append("</a></span></th>");
        } else {
            buf.append("<th class=rateDown></th>");
        }

        // TX header with ratio sorting
        boolean isRatSort = false, nextRatSort = false;
        if (showSort) {
            if ("-7".equals(currentSort)) {
                nextSort = "7";
            } else if ("7".equals(currentSort)) {
                nextSort = "-11"; nextRatSort = true;
            } else if ("-11".equals(currentSort)) {
                nextSort = "11"; nextRatSort = true; isRatSort = true;
            } else if ("11".equals(currentSort)) {
                nextSort = "-7"; isRatSort = true;
            } else {
                nextSort = "-7";
            }
            boolean isTXActive = "-7".equals(currentSort) || "7".equals(currentSort) || "-11".equals(currentSort) || "11".equals(currentSort);
            boolean isTXDesc = "-7".equals(currentSort) || "-11".equals(currentSort);
            appendSortHeader(buf, contextPath, req, currentSort, nextSort, separator, filterQuery,
                             "txd", "head_tx", txtTX, showSort, isTXActive, isTXDesc);
        } else {
            buf.append("<th class=txd></th>");
        }

        // RateUp header (show if uploading)
        if (isConnected && !noSnarks && isUploading) {
            nextSort = "-9".equals(currentSort) ? "9" : "-9";
            boolean ascendingRateUp = "9".equals(currentSort);
            buf.append("<th class=rateUp><span class=sortIcon>");
            if (ascendingRateUp) {
                buf.append("<span class=ascending></span>");
            } else {
                buf.append("<span class=descending></span>");
            }
            buf.append("<a class=sorter href=\"").append(contextPath).append('/')
               .append(getQueryString(req, null, null, nextSort))
               .append(separator).append(filterQuery).append("\">");
            appendIcon(buf, "head_txspeed", txtTXRate, showSort ? _t("Sort by {0}", _t("Up Rate")) : "", true, false);
            buf.append("</a></span></th>");
        } else {
            buf.append("<th class=rateUp></th>");
        }

        // Action buttons header (Start/Stop all)
        buf.append("<th class=tAction>");
        if (isConnected && !noSnarks) {
            buf.append("<input type=submit id=actionStopAll name=action_StopAll value=\"").append(txtStopAll)
               .append("\" title=\"").append(txtStopAllTitle).append("\">");
            for (Snark s : snarks) {
                if (s.isStopped()) {
                    buf.append("<input type=submit id=actionStartAll name=action_StartAll value=\"")
                       .append(txtStartAll).append("\" title=\"").append(txtStartStoppedTitle).append("\">");
                    break;
                }
            }
        } else if (!noSnarks) {
            boolean disableStartAll = _manager.util().isConnecting();
            buf.append("<input type=submit id=actionStartAll name=action_StartAll value=\"")
               .append(txtStartAll).append("\" title=\"").append(txtStartAllTitle).append("\"")
               .append(disableStartAll ? " disabled" : "").append(">");
        }
        buf.append("</th></tr>\n</thead>\n<tbody id=snarkTbody>");

        return buf.toString();
    }

    /**
     * Appends a sortable table header cell including sort icons and links.
     * Shows ascending or descending icon only if this header matches current sorting.
     *
     * @param buf the StringBuilder used to append HTML content
     * @param contextPath the context path prefix used in URLs
     * @param req current HttpServletRequest to build query strings
     * @param currentSort the current sort parameter value
     * @param newSort the sort parameter value to link to for toggling sorting
     * @param separator '&' or '?' depending on query string presence
     * @param filterQuery filtered search parameters to append to URL
     * @param cssClass CSS class to apply to the <th> element
     * @param iconName icon identifier for rendering
     * @param title localized title text for the header cell
     * @param showSort if true, render sorting link and icons; otherwise render plain header
     * @param currentSortMatches true if this header corresponds to the current sort parameter (active sorted column)
     * @param isDescending true if the current sorting direction for this header is descending
     */
    private void appendSortHeader(StringBuilder buf, String contextPath, HttpServletRequest req,
                                  String currentSort, String newSort, String separator, String filterQuery,
                                  String cssClass, String iconName, String title, boolean showSort,
                                  boolean currentSortMatches, boolean isDescending) {
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

        buf.append("<a class=sorter href=\"").append(contextPath).append('/')
           .append(getQueryString(req, null, null, newSort))
           .append(separator).append(filterQuery).append("\">");

        appendIcon(buf, iconName, title, _t("Sort by {0}", title), true, false);
        buf.append("</a></span></th>");
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
     * @param total        total number of torrents
     * @param isConnected  whether the system is connected
     * @param noSnarks     true if no torrents are loaded (empty list)
     * @param hasPeers     true if there are any connected peers
     * @param isUploading  true if there is active uploading
     * @param dht          the DHT instance providing additional peer info (may be null)
     * @param isStandalone true if running in standalone mode (tunnel info omitted if standalone)
     * @param debug        true if in debug mode (affects resource path and display)
     * @param peerParam    the peer parameter from the request query, affects debug mode toggle links
     * @throws IOException if writing to the output stream fails
     */
    private void appendSnarkFooter(PrintWriter out, StringBuilder buf, long[] stats, int total, boolean isConnected,
                                   boolean noSnarks, boolean hasPeers, boolean isUploading, DHT dht,
                                   boolean isStandalone, boolean debug, String peerParam) throws IOException {

        if (_manager.util().isConnecting()) {
            out.write("<tfoot id=snarkFoot class=initializing><tr><th id=torrentTotals class=left colspan=12></th></tr></tfoot>\n");
            return;
        }

        // Cache constant localized strings that are reused
        final String titleTotalSize = _t("Total size of loaded torrents");
        final String titleConnectedPeers = ngettext("1 connected peer", "{0} peer connections", (int) stats[4]);
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

        final String resourcePath = !isStandalone ? (debug ? "/themes/" : _contextPath + WARBASE) : null;

        out.write("<tfoot id=snarkFoot><tr class=volatile><th id=torrentTotals class=left colspan=6><span id=totals>");
        out.write(_manager.getDiskUsage());

        // Torrent count span
        buf.setLength(0);
        buf.append("<span id=torrentCount class=counter title=\"");
        buf.append(ngettext("1 torrent", "{0} torrents", total));
        buf.append("\">");
        appendIcon(buf, "torrent", "", "", true, false);
        buf.append("<span class=badge>");
        buf.append(total);
        buf.append("</span></span>");
        out.write(buf.toString());

        // Filesize span
        buf.setLength(0);
        buf.append("<span id=torrentFilesize class=counter title=\"");
        buf.append(titleTotalSize);
        buf.append("\">");
        appendIcon(buf, "size", "", "", true, false);
        buf.append("<span class=badge>");
        buf.append(DataHelper.formatSize2(stats[5]).replace("i", ""));
        buf.append("</span></span>");
        out.write(buf.toString());

        // Peer count span
        buf.setLength(0);
        buf.append("<span id=peerCount class=counter title=\"");
        buf.append(titleConnectedPeers);
        buf.append("\">");
        appendIcon(buf, "showpeers", "", "", true, false);
        buf.append("<span class=badge>");
        buf.append((int) stats[4]);
        buf.append("</span></span>");
        out.write(buf.toString());

        ArrayList<Snark> snarks = new ArrayList<Snark>(_manager.getTorrents());

        // actively downloading
        int downloads = 0;
        int start = 1;

        for (int i = start; i < snarks.size(); i++) {
            if ((snarks.get(i).getPeerList().size() >= 1) && (snarks.get(i).getDownloadRate() > 0)) {downloads++;}
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
            if ((snarks.get(i).getPeerList().size() >= 1) && (snarks.get(i).getUploadRate() > 0)) {uploads++;}
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

        // Tunnel counter if not standalone
        if (!isStandalone) {
            buf.setLength(0);
            buf.append("<span id=tnlInCount class=counter title=\"");
            buf.append(titleInboundTunnels);
            buf.append("\" hidden>");
            appendIcon(buf, "inbound", "", "", true, true);
            buf.append("<span class=badge></span></span>");
            out.write(buf.toString());

            buf.setLength(0);
            buf.append("<span id=tnlOutCount class=counter title=\"");
            buf.append(titleOutboundTunnels);
            buf.append("\" hidden>");
            appendIcon(buf, "outbound", "", "", true, true);
            buf.append("<span class=badge></span></span>");
            out.write(buf.toString());
        }

        out.write("</span></th>");

        if (isConnected && total > 0) {
            out.write("<th class=ETA>");
            if (!noSnarks && hasPeers && stats[2] > 0) {
                out.write("<span title=\"");
                out.write(titleEstimatedDownload);
                out.write("\">");
                out.write(DataHelper.formatDuration2(Math.max(stats[2], 10) * 1000));
                out.write("</span>");
            }
            out.write("</th><th class=rxd title=\"");
            out.write(titleDataDownloaded);
            out.write("\">");
            if (stats[0] > 0) out.write(formatSize(stats[0]).replace("iB", ""));
            out.write("</th><th class=rateDown title=\"");
            out.write(titleTotalDownloadSpeed);
            out.write("\">");
            if (stats[2] > 0) out.write(formatSize(stats[2]).replace("iB", "") + "/s");
            out.write("</th><th class=txd title=\"");
            out.write(titleTotalUploaded);
            out.write("\">");
            if (stats[1] > 0) out.write(formatSize(stats[1]).replace("iB", ""));
            out.write("</th><th class=rateUp title=\"");
            out.write(titleTotalUploadSpeed);
            out.write("\">");
            if (stats[3] > 0 && isUploading) out.write(formatSize(stats[3]).replace("iB", "") + "/s");
            out.write("</th><th class=tAction>");

            if (dht != null && !"2".equals(peerParam)) {
                out.write("<a id=debugMode href=\"?p=2\" title=\"" + toggleDebug + "\">" + debugModeText + "</a>");
            } else if (dht != null) {
                out.write("<a id=debugMode href=\"?p\" title=\"" + toggleDebug + "\">" + normalModeText + "</a>");
            }

            out.write("</th>");
        } else {
            out.write("<th colspan=6></th>");
        }
        out.write("</tr>\n</tfoot>");
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
        String srt = req.getParameter("sort");
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
        return new StringBuilder()
          .append("<a class=filter id=").append(filterId).append(" href=\"").append(baseUrl).append(filterId).append("\"><span>")
          .append(title).append(badge).append("</span></a>").toString();
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
            if (term.length() > 0) {
                if (searchList == null) {searchList = new ArrayList<String>(4);}
                searchList.add(Normalizer.normalize(term.toLowerCase(Locale.US), Normalizer.Form.NFKD));
            }
        }
        if (searchList == null || searchList.isEmpty()) {return new ArrayList<Snark>(0);} // empty list
        List<Snark> matches = new ArrayList<Snark>(32);
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
     *  hidden inputs for nonce and paramters p, st, and sort
     *
     *  @param out writes to it
     *  @param action if non-null, add it as the action
     *  @since 0.9.16
     */
    private void writeHiddenInputs(PrintWriter out, HttpServletRequest req, String action) {
        StringBuilder buf = new StringBuilder(256);
        writeHiddenInputs(buf, req, action);
        out.append(buf);
    }

    /**
     *  hidden inputs for nonce and parameters p, st, and sort
     *
     *  @param buf appends to it
     *  @param action if non-null, add it as the action
     *  @since 0.9.16
     */
    private void writeHiddenInputs(StringBuilder buf, HttpServletRequest req, String action) {
        Map<String, String> params = new HashMap<>();
        params.put("nonce", String.valueOf(_nonce));
        params.put("p", req.getParameter("p"));
        params.put("st", req.getParameter("st"));
        params.put("sort", req.getParameter("sort"));
        params.put("action", action);
        params.put("search", req.getParameter("search"));

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value != null) {
                if (key.equals("search")) { // for buttons, keep the search term
                    buf.append("<input type=hidden name=").append(key).append(" value=\"").append(DataHelper.escapeHTML(value)).append("\">");
                } else {
                    buf.append("<input type=hidden name=").append(key).append(" value=\"").append(DataHelper.stripHTML(value)).append("\">");
                }
            }
        }
        buf.append("\n");
    }

    private static boolean isValidNumeric(String str) {
        if (str == null || str.isEmpty()) {return false;}
        String regex = "^-?[0-9]\\d*$";
        if (!str.matches(regex)) {return false;}
        int num = Integer.parseInt(str);
        return true;
    }

    /**
     *  Build HTML-escaped and stripped query string.
     *  Keeps any existing search param.
     *
     *  @param p override or "" for default or null to keep the same as in req
     *  @param st override or "" for default or null to keep the same as in req
     *  @param so override or "" for default or null to keep the same as in req
     *  @return non-null, possibly empty
     *  @since 0.9.16
     */
    private static String getQueryString(HttpServletRequest req, String p, String st, String so) {
        return getQueryString(req, p, st, so, null);
    }

    /**
     *  @param s search param override or "" for default or null to keep the same as in req
     *  @since 0.9.58
     */
    private static String getQueryString(HttpServletRequest req, String p, String st, String so, String search) {
        String url = req.getRequestURL().toString();
        String filter = req.getParameter("filter");
        StringBuilder buf = new StringBuilder(64);

        // Create a map with parameter names and their corresponding variable references
        Map<String, String> params = new HashMap<>();
        params.put("p", p);
        params.put("sort", so);
        params.put("st", st);
        params.put("search", search);

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String paramName = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                value = req.getParameter(paramName);
                if (value != null) {
                    if ("p".equals(paramName) || "sort".equals(paramName) || "st".equals(paramName)) {
                        value = DataHelper.stripHTML(value);
                    } else if ("search".equals(paramName)) {value = DataHelper.escapeHTML(value);}
                }
            }
            if (isValidNumeric(value) && value != null && !value.isEmpty()) {
                if (buf.length() <= 0) {buf.append("?" + paramName + "=");}
                else {buf.append("&" + paramName + "=");}
                buf.append(value);
            }
        }
        return buf.toString();
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
               .append(getQueryString(req, null, "", null))
               .append("\"").append(start > 0 ? "" : " class=disabled")
               .append("><span id=first>");
            appendIcon(buf, "first", _t("First"), _t("First page"), true, true);
            buf.append("</span></a>");

            // Back
            int prev = Math.max(0, start - pageSize);
            buf.append("<a href=\"").append(_contextPath)
               .append(getQueryString(req, null, String.valueOf(prev), null))
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
               .append(getQueryString(req, null, String.valueOf(next), null))
               .append("\"").append(next + pageSize < total ? "" : " class=disabled")
               .append("><span id=next>");
            appendIcon(buf, "next", _t("Next"), _t("Next page"), true, true);
            buf.append("</span></a>");

            // Last
            int last = ((total - 1) / pageSize) * pageSize;
            buf.append("<a href=\"").append(_contextPath)
               .append(getQueryString(req, null, String.valueOf(last), null))
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
    private void processRequest(HttpServletRequest req) {
        String action = extractAction(req);
        if (action == null) {return;} // No action specified

        switch (action) {
            case "Add": handleAdd(req);
                break;
            case "Save": handleSave(req);
                break;
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
    }

    /**
     * Extracts the action parameter from the request.
     * Checks "action" parameter and fallback to keys starting with "action_".
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
                if (key.startsWith("action_")) {
                    action = key.substring(7);
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
                    _manager.addMessage(msg);
                    if (isStandalone()) {System.out.println(" • " + msg);}
                } else {
                    String msg = _t("Torrent already in the queue: {0}", canonical);
                    _manager.addMessage(msg);
                    if (isStandalone()) {System.out.println(" • " + msg);}
                }
            } catch (IOException ignored) {}
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
            _manager.addMessageNoEscape(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            tmp.delete();
            return;
        }

        // Validate torrent and add
        try (FileInputStream in = new FileInputStream(tmp)) {
            byte[] infoHash = new byte[20];
            String name = MetaInfo.getNameAndInfoHash(in, infoHash);

            Snark snark = _manager.getTorrentByInfoHash(infoHash);
            if (snark != null) {
                String msg = _t("Torrent with this info hash is already running: {0}", snark.getBaseName());
                _manager.addMessage(msg);
                if (isStandalone()) {System.out.println(" • " + msg);}
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
            _manager.addMessageNoEscape(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
        } catch (OutOfMemoryError oom) {
            String msg = _t("ERROR - Out of memory, cannot create torrent from {0}", DataHelper.escapeHTML(newFile)) +
                         ": " + DataHelper.stripHTML(oom.getMessage());
            _manager.addMessageNoEscape(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
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
                    _manager.addMessage(msg);
                    if (isStandalone()) {System.out.println(" • " + msg);}
                    return;
                }
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    String msg = _t("Data directory cannot be created") + ": " + dir;
                    _manager.addMessage(msg);
                    if (isStandalone()) {System.out.println(" • " + msg);}
                    return;
                }
                // Prevent nested torrents
                for (Snark s : _manager.getTorrents()) {
                    Storage storage = s.getStorage();
                    if (storage == null) continue;
                    File sbase = storage.getBase();
                    if (isParentOf(sbase, dir)) {
                        String msg = _t("Cannot add torrent {0} inside another torrent: {1}", dir.getAbsolutePath(), sbase);
                        _manager.addMessage(msg);
                        if (isStandalone()) {System.out.println(" • " + msg);}
                        return;
                    }
                }
            }
        }

        if (newURL.startsWith("http://") || newURL.startsWith("https://")) {
            if (isI2PTracker(newURL)) {
                FetchAndAdd fetch = new FetchAndAdd(_context, _manager, newURL, dir);
                _manager.addDownloader(fetch);
            } else {
                String msg = _t("Download from non-I2P location {0} is not supported", urlify(newURL));
                _manager.addMessageNoEscape(msg);
                if (isStandalone()) {System.out.println(" • " + msg);}
            }
        } else if (newURL.startsWith(MagnetURI.MAGNET) || newURL.startsWith(MagnetURI.MAGGOT)) {
            addMagnet(newURL, dir);
        } else if (isValidHexInfoHash(newURL)) {
            addMagnet(MagnetURI.MAGNET_FULL + newURL.toUpperCase(Locale.US), dir);
        } else if (isValidBase32InfoHash(newURL)) {
            addMagnet(MagnetURI.MAGNET_FULL + newURL.toUpperCase(Locale.US), dir);
        } else if (isValidV2InfoHash(newURL)) {
            String msg = _t("Error: Version 2 info hashes are not supported");
            _manager.addMessage(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
        } else {
            handleAddFromFilePath(newURL, dataDir);
        }
    }

    /**
     * Validates if a string is a valid 40-hex character info hash.
     */
    private boolean isValidHexInfoHash(String s) {
        return s.length() == 40 && s.matches("[a-fA-F0-9]+");
    }

    /**
     * Validates if a string is a valid 32-base32 character info hash.
     */
    private boolean isValidBase32InfoHash(String s) {
        return s.length() == 32 && s.matches("[a-zA-Z2-7]+");
    }

    /**
     * Validates if string is version 2 hex multihash (68 characters starting with "1220").
     */
    private boolean isValidV2InfoHash(String s) {
        return s.length() == 68 && s.startsWith("1220") && s.matches("[a-fA-F0-9]+");
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
            _manager.addMessage(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            return;
        }
        if (!newURL.endsWith(".torrent")) {
            String msg = _t("Torrent at {0} was not valid", DataHelper.escapeHTML(newURL));
            _manager.addMessageNoEscape(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            return;
        }

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] infoHash = new byte[20];
            String name = MetaInfo.getNameAndInfoHash(in, infoHash);

            Snark snark = _manager.getTorrentByInfoHash(infoHash);
            if (snark != null) {
                String msg = _t("Torrent with this info hash is already running: {0}", snark.getBaseName());
                _manager.addMessage(msg);
                if (isStandalone()) {System.out.println(" • " + msg);}
                return;
            }

            String filteredName = Storage.filterName(name);
            File torrentFile = new File(dataDir, filteredName + ".torrent");
            String canonical = torrentFile.getCanonicalPath();

            if (torrentFile.exists()) {
                if (_manager.getTorrent(canonical) != null) {
                    String msg = _t("Torrent already running: {0}", filteredName);
                    _manager.addMessage(msg);
                    if (isStandalone()) {System.out.println(" • " + msg);}
                    return;
                } else {
                    String msg = _t("Torrent already in the queue: {0}", filteredName);
                    _manager.addMessage(msg);
                    if (isStandalone()) {System.out.println(" • " + msg);}
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
            _manager.addMessageNoEscape(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
        } catch (OutOfMemoryError oom) {
            String msg = _t("ERROR - Out of memory, cannot create torrent from {0}",
                            DataHelper.escapeHTML(newURL)) + ": " + DataHelper.stripHTML(oom.getMessage());
            _manager.addMessageNoEscape(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
        }
    }

    /**
     * Handles the "Stop_" action to stop a torrent by encoded info hash.
     *
     * @param action the action string starting with "Stop_"
     */
    private void handleStop(String action) {
        String torrent = action.substring(5).replace("%3D", "=");
        if (torrent == null) return;

        byte[] infoHash = Base64.decode(torrent);
        if (infoHash == null || infoHash.length != 20) return;

        Snark snark = _manager.getTorrentByInfoHash(infoHash);
        if (snark != null && DataHelper.eq(infoHash, snark.getInfoHash())) {
            _manager.stopTorrent(snark);
        }
    }

    /**
     * Handles the "Start_" action to start a torrent by encoded info hash.
     *
     * @param action the action string starting with "Start_"
     */
    private void handleStart(String action) {
        String torrent = action.substring(6).replace("%3D", "=");
        if (torrent == null) return;

        byte[] infoHash = Base64.decode(torrent);
        if (infoHash != null && infoHash.length == 20) {
            _manager.startTorrent(infoHash);
        }
    }

    /**
     * Handles the "Remove_" action to remove a torrent by encoded info hash.
     *
     * @param action the action string starting with "Remove_"
     */
    private void handleRemove(String action) {
        String torrent = action.substring(7);
        if (torrent == null) return;

        byte[] infoHash = Base64.decode(torrent);
        if (infoHash == null || infoHash.length != 20) return;

        for (String name : _manager.listTorrentFiles()) {
            Snark snark = _manager.getTorrent(name);
            if (snark != null && DataHelper.eq(infoHash, snark.getInfoHash())) {
                MetaInfo meta = snark.getMetaInfo();
                if (meta == null) {
                    // magnet - remove and delete are the same thing
                    _manager.deleteMagnet(snark);
                    _manager.addMessage(_t("Magnet deleted: {0}", name.replace("Magnet ", "")));
                    return;
                }
                File torrentFile = new File(name);
                File dataDir = _manager.getDataDir();
                boolean canDelete = dataDir.canWrite() || !torrentFile.exists();
                _manager.stopTorrent(snark, canDelete);
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
     * Handles the "Delete_" action to delete a torrent and its data by encoded info hash.
     *
     * @param action the action string starting with "Delete_"
     */
    private void handleDelete(String action) {
        String torrent = action.substring(7);
        if (torrent == null) return;

        byte[] infoHash = Base64.decode(torrent);
        if (infoHash == null || infoHash.length != 20) return;

        for (String name : _manager.listTorrentFiles()) {
            Snark snark = _manager.getTorrent(name);
            if (snark != null && DataHelper.eq(infoHash, snark.getInfoHash())) {
                MetaInfo meta = snark.getMetaInfo();
                if (meta == null) {
                    _manager.deleteMagnet(snark);
                    _manager.addMessage(_t("Magnet deleted: {0}", name.replace("Magnet ", "")));
                    return;
                }
                File torrentFile = new File(name);
                File dataDir = _manager.getDataDir();
                boolean canDelete = dataDir.canWrite() || !torrentFile.exists();
                _manager.stopTorrent(snark, canDelete);

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
    private void handleSave(HttpServletRequest req) {
        // Extract parameters and update configuration
        boolean filesPublic = req.getParameter("filesPublic") != null;
        boolean autoStart = req.getParameter("autoStart") != null;
        boolean useOpenTrackers = req.getParameter("useOpenTrackers") != null;
        boolean useDHT = req.getParameter("useDHT") != null;
        boolean ratings = req.getParameter("ratings") != null;
        boolean comments = req.getParameter("comments") != null;
        boolean collapsePanels = req.getParameter("collapsePanels") != null;
        boolean showStatusFilter = req.getParameter("showStatusFilter") != null;
        boolean enableLightbox = req.getParameter("enableLightbox") != null;
        boolean enableAddCreate = req.getParameter("enableAddCreate") != null;
        boolean enableVaryInboundHops = req.getParameter("varyInbound") != null;
        boolean enableVaryOutboundHops = req.getParameter("varyOutbound") != null;

        String dataDir = req.getParameter("nofilter_dataDir");
        String seedPct = req.getParameter("seedPct");
        String eepHost = req.getParameter("eepHost");
        String eepPort = req.getParameter("eepPort");
        String i2cpHost = req.getParameter("i2cpHost");
        String i2cpPort = req.getParameter("i2cpPort");
        String i2cpOpts = buildI2CPOpts(req);
        String upLimit = req.getParameter("upLimit");
        String upBW = req.getParameter("upBW");
        String downBW = req.getParameter("downBW");
        String refreshDel = req.getParameter("refreshDelay");
        String startupDel = req.getParameter("startupDelay");
        String pageSize = req.getParameter("pageSize");
        String theme = req.getParameter("theme");
        String lang = req.getParameter("lang");
        String commentsName = req.getParameter("nofilter_commentsName");
        String apiTarget = req.getParameter("apiTarget");
        String apiKey = req.getParameter("apiKey");

        _manager.updateConfig(dataDir, filesPublic, autoStart, refreshDel, startupDel, pageSize, seedPct, eepHost, eepPort,
                              i2cpHost, i2cpPort, i2cpOpts, upLimit, upBW, downBW, useOpenTrackers, useDHT, theme, lang,
                              ratings, comments, commentsName, collapsePanels, showStatusFilter, enableLightbox,
                              enableAddCreate, enableVaryInboundHops, enableVaryOutboundHops, apiTarget, apiKey);
        try {
            setResourceBase(_manager.getDataDir());
        } catch (ServletException ignored) {}
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
     * Handles creating a new torrent from provided base file or directory.
     *
     * @param req the HTTP request
     */
    private void handleCreate(HttpServletRequest req) {
        String baseData = req.getParameter("nofilter_baseFile");
        if (baseData == null || baseData.trim().isEmpty()) {
            String msg = _t("Error creating torrent - you must specify a file or directory");
            _manager.addMessage(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            return;
        }

        File baseFile = new File(baseData.trim());
        if (!baseFile.isAbsolute()) {
            baseFile = new File(_manager.getDataDir(), baseData.trim());
        }

        if (!baseFile.exists()) {
            String msg = _t("Cannot create a torrent for the nonexistent data: {0}", baseFile.getAbsolutePath());
            _manager.addMessage(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            return;
        }

        File dataDir = _manager.getDataDir();
        if (!dataDir.canWrite()) {
            String msg = _t("No write permissions for data directory") + ": " + dataDir;
            _manager.addMessage(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            return;
        }

        String baseName = baseFile.getName();
        if (baseName.toLowerCase(Locale.US).endsWith(".torrent")) {
            String msg = _t("Cannot add a torrent ending in \".torrent\": {0}", baseFile.getAbsolutePath());
            _manager.addMessage(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            return;
        }

        if (_manager.getTorrentByBaseName(baseName) != null) {
            String msg = _t("Torrent with this name is already running: {0}", baseName);
            _manager.addMessage(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            return;
        }

        if (isParentOf(baseFile, _manager.getDataDir()) ||
            isParentOf(baseFile, _manager.util().getContext().getBaseDir()) ||
            isParentOf(baseFile, _manager.util().getContext().getConfigDir())) {
            String msg = _t("Cannot add a torrent including an I2P directory: {0}", baseFile.getAbsolutePath());
            _manager.addMessage(msg);
            if (isStandalone()) {System.out.println(" • " + msg);}
            return;
        }

        // Check nested torrents
        for (Snark s : _manager.getTorrents()) {
            Storage storage = s.getStorage();
            if (storage == null) continue;
            File sbase = storage.getBase();
            if (isParentOf(sbase, baseFile)) {
                String msg = _t("Cannot add torrent {0} inside another torrent: {1}", baseFile.getAbsolutePath(), sbase);
                _manager.addMessage(msg);
                if (isStandalone()) {System.out.println(" • " + msg);}
                return;
            }
            if (isParentOf(baseFile, sbase)) {
                String msg = _t("Cannot add torrent {0} including another torrent: {1}", baseFile.getAbsolutePath(), sbase);
                _manager.addMessage(msg);
                if (isStandalone()) {System.out.println(" • " + msg);}
                return;
            }
        }

        String announceURL = req.getParameter("announceURL");
        if ("none".equals(announceURL)) announceURL = null;
        _lastAnnounceURL = announceURL;

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

        List<List<String>> announceList = null;
        if (!backupURLs.isEmpty()) {
            if (announceURL == null) {
                String msg = _t("Error - Cannot include alternate trackers without a primary tracker");
                _manager.addMessage(msg);
                if (isStandalone()) {System.out.println(" • " + msg);}
                return;
            }
            backupURLs.add(0, announceURL);
            boolean hasPrivate = false;
            boolean hasPublic = false;
            for (String url : backupURLs) {
                if (_manager.getPrivateTrackers().contains(url)) hasPrivate = true;
                else hasPublic = true;
            }
            if (hasPrivate && hasPublic) {
                String msg = _t("Error - Cannot mix private and public trackers in a torrent");
                _manager.addMessage(msg);
                if (isStandalone()) {System.out.println(" • " + msg);}
                return;
            }
            announceList = new ArrayList<>(backupURLs.size());
            for (String url : backupURLs) {
                announceList.add(Collections.singletonList(url));
            }
        }

        try {
            boolean isPrivate = _manager.getPrivateTrackers().contains(announceURL);
            String[] filters = req.getParameterValues("filters");
            List<TorrentCreateFilter> filterList = new ArrayList<>();
            Map<String, TorrentCreateFilter> torrentCreateFilters = _manager.getTorrentCreateFilterMap();
            if (filters == null) filters = new String[0];
            for (String filterName : filters) {
                TorrentCreateFilter filter = torrentCreateFilters.get(filterName);
                if (filter != null) {
                    filterList.add(filter);
                }
            }

            Storage storage = new Storage(_manager.util(), baseFile, announceURL, announceList, null, isPrivate, null, filterList);
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

            if (announceURL != null && !_manager.util().getOpenTrackers().contains(announceURL)) {
                _manager.addMessage(_t("Many I2P trackers require you to register new torrents before seeding - please do so before starting \"{0}\"", baseFile.getName()));
            }
        } catch (IOException ioe) {
            _manager.addMessage(_t("Error creating a torrent for \"{0}\"", baseFile.getAbsolutePath()) + ": " + ioe);
            _log.error("Error creating a torrent", ioe);
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
                int count = 0;
                for (Snark snark : matches) {
                    if (!snark.isStopped()) continue;
                    _manager.startTorrent(snark);
                    if ((count++ & 0x0f) == 15) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    }
                }
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
            try {
                int id = Integer.parseInt(sid);
                _manager.clearMessages(id);
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     *  Redirect a POST to a GET (P-R-G), preserving the peer string
     *  @since 0.9.5
     */
    private void sendRedirect(HttpServletRequest req, HttpServletResponse resp, String p) throws IOException {
        String url = req.getRequestURL().toString();
        // Trim trailing "_post" if present
        if (url.endsWith("_post")) {url = url.substring(0, url.length() - 5);}

        // Validate parameter p as numeric (digits only)
        if (p != null && !p.isEmpty()) {
            // Remove any HTML entities &amp; before validating
            String decodedP = p.replace("&amp;", "&");
            // Check that decodedP only contains digits and optional query characters
            // Example: if p is query string starting with '?', allow appropriate format
            // For strict numeric only:
            if (!decodedP.matches("\\d+")) {
                // Invalid redirect parameter, reject request
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid redirect parameter");
                return;
            }
            url += decodedP;
        }
        // Perform redirect safely
        resp.sendRedirect(url);
        return;
    }

    /** @since 0.9 */
    private void processTrackerForm(String action, HttpServletRequest req) {
        if (action.equals(_t("Delete selected")) || action.equals(_t("Save tracker configuration"))) {
            boolean changed = false;
            Map<String, Tracker> trackers = _manager.getTrackerMap();
            List<String> removed = new ArrayList<String>();
            List<String> open = new ArrayList<String>();
            List<String> priv = new ArrayList<String>();
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
            List<String> oldOpen = new ArrayList<String>(_manager.util().getOpenTrackers());
            Collections.sort(oldOpen);
            Collections.sort(open);
            if (!open.equals(oldOpen)) {_manager.saveOpenTrackers(open);}

            priv.removeAll(removed);
            // open trumps private
            priv.removeAll(open);
            List<String> oldPriv = new ArrayList<String>(_manager.getPrivateTrackers());
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
                if (name.length() > 0 && hurl.startsWith("http://") && TrackerClient.isValidAnnounce(aurl)) {
                    Map<String, Tracker> trackers = _manager.getTrackerMap();
                    trackers.put(name, new Tracker(name, aurl, hurl));
                    _manager.saveTrackerMap();
                    String type = req.getParameter("add_tracker_type");
                    if ("1".equals(type)) {
                        List<String> newOpen = new ArrayList<String>(_manager.util().getOpenTrackers());
                        newOpen.add(aurl);
                        _manager.saveOpenTrackers(newOpen);
                    } else if ("2".equals(type)) {
                        List<String> newPriv = new ArrayList<String>(_manager.getPrivateTrackers());
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

    /** @since 0.9.62+ */
    private void processTorrentCreateFilterForm(String action, HttpServletRequest req) {
        if (action.equals(_t("Delete selected")) || action.equals(_t("Save Filter Configuration"))) {
            boolean changed = false;
            Map<String, TorrentCreateFilter> torrentCreateFilters = _manager.getTorrentCreateFilterMap();
            Enumeration<?> e = req.getParameterNames();
            ArrayList<String> newDefaults = new ArrayList<String>();
            ArrayList<TorrentCreateFilter> replaceFilters = new ArrayList<TorrentCreateFilter>();
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

    private static final String iopts[] = {"inbound.length", "inbound.quantity", "outbound.length", "outbound.quantity" };

    /** put the individual i2cp selections into the option string */
    private static String buildI2CPOpts(HttpServletRequest req) {
        StringBuilder buf = new StringBuilder(128);
        String p = req.getParameter("i2cpOpts");
        if (p != null) {buf.append(p);}
        for (int i = 0; i < iopts.length; i++) {
            p = req.getParameter(iopts[i]);
            if (p != null) {buf.append(' ').append(iopts[i]).append('=').append(p);}
        }
        return buf.toString();
    }

    private List<Snark> getSortedSnarks(HttpServletRequest req) {
        ArrayList<Snark> rv = new ArrayList<Snark>(_manager.getTorrents());
        if (rv.size() > 1) {
            int sort = 0;
            String ssort = req.getParameter("sort");
            if (ssort != null) {
                try {sort = Integer.parseInt(ssort);}
                catch (NumberFormatException nfe) {}
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

    static final int MAX_DISPLAYED_FILENAME_LENGTH = 255;
    private static final int MAX_DISPLAYED_ERROR_LENGTH = 43;

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

    private String snarkStatus;
    private String filterParam;
    private boolean filterEnabled;
    private String sortParam;
    private boolean sortEnabled;

    /**
     * Displays a single snark (torrent) as an HTML table row, including optional peer rows.
     * Updates the provided statistics array with cumulative data (uploaded, downloaded, rates,
     * peer counts, and size). Controls filtering and sorting behavior based on provided
     * parameters, showing detailed information with status, progress bars, and action buttons.
     * Appends generated HTML to the provided StringBuilder buffer instead of writing directly
     * to output, allowing batching and improved rendering performance.
     *
     * @param out the PrintWriter for output (used only for final append)
     * @param req the HttpServletRequest containing request parameters and context
     * @param snark the Snark instance representing the torrent to display
     * @param uri base URI context path for links and references
     * @param row the index of this snark in the listing, used for styling
     * @param stats a six-element long array accumulating totals: [downloaded, uploaded, down rate, up rate, peers, total size]
     * @param showPeers whether to display peer detail rows below this snark
     * @param isDegraded true if the browser environment is text-mode or limited, affecting formatting
     * @param noThinsp true if thin spaces should be omitted due to browser quirks
     * @param showDebug if true, enables debug mode showing additional details
     * @param statsOnly if true, only updates stats array and output is suppressed
     * @param showRatios if true, display upload ratio bars instead of raw data
     * @param canWrite indicates if the i2psnark data directory is writable (affects action buttons)
     * @param filterParam filter parameter from request for conditional row inclusion
     * @param filterEnabled true if filtering is active (non-default filter applied)
     * @param sortParam current sort field parameter from request
     * @param sortEnabled true if sorting is active
     * @param buf StringBuilder buffer to append the generated HTML output for this snark
     * @throws IOException if an I/O error occurs during output operations
     */
    private void displaySnark(PrintWriter out, HttpServletRequest req, Snark snark, String uri, int row, long stats[],
                              boolean showPeers, boolean isDegraded, boolean noThinsp, boolean showDebug, boolean statsOnly,
                              boolean showRatios, boolean canWrite,
                              String filterParam, boolean filterEnabled, String sortParam, boolean sortEnabled,
                              StringBuilder buf) throws IOException {
        // Update stats first (minimal processing)
        long uploaded = snark.getUploaded();
        stats[0] += snark.getDownloaded();
        stats[1] += uploaded;
        long downBps = snark.getDownloadRate();
        long upBps = snark.getUploadRate();
        boolean isRunning = !snark.isStopped();
        if (isRunning) {
            stats[2] += downBps;
            stats[3] += upBps;
        }
        int curPeers = snark.getPeerList().size();
        stats[4] += curPeers;
        long total = snark.getTotalLength();
        if (total > 0) stats[5] += total;
        if (statsOnly) return;

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
        String b64 = Base64.encode(snark.getInfoHash());
        boolean isValid = meta != null;
        boolean isMultiFile = isValid && meta.getFiles() != null;

        int knownPeers = Math.max(curPeers, snark.getTrackerSeenPeers());
        StatusResult statusResult = buildStatusString(snark, curPeers, knownPeers, downBps, upBps, isRunning, remaining, needed, noThinsp);
        String snarkStatusLocal = statusResult.snarkStatus;

        // Filter check early exit
        if (!filterEnabled || snarkMatchesFilter(snark, filterParam, snarkStatusLocal)) {
            String statusString = statusResult.statusHtml;
            String rowClass = (row % 2 == 0 ? "rowEven" : "rowOdd");
            String rowStatus = rowClass + ' ' + snarkStatusLocal;

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
                buf.append("<a class=magnetlink href=\"").append(magnetLink);
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
                SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMM yyyy", Locale.US);
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                long[] dates = _manager.getSavedAddedAndCompleted(snark);
                String date = fmt.format(new Date(dates[1]));
                buf.append("<div class=barComplete title=\"").append(_t("Completed")).append(": ").append(date).append("\">")
                   .append(formatSize(total).replaceAll("iB", "")).append("</div>");
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
                double ratio = total > 0 ? uploaded / (double) total : 0;
                String txPercent = new DecimalFormat(ratio <= 0.01 && ratio > 0 ? "0.00" : "0").format(ratio * 100);
                String txPercentBar = ratio > 1 ? "100%" : txPercent + "%";

                if (showRatios) {
                    if (total > 0) {
                        buf.append("<span class=tx><span class=txBarText>").append(txPercent).append(" %")
                           .append("</span><span class=txBarInner style=\"width:calc(")
                           .append(txPercentBar).append(" - 2px)\"></span></span>");
                    } else {
                        buf.append("&mdash;");
                    }
                } else if (uploaded > 0) {
                    buf.append("<span class=tx title=\"").append(_t("Share ratio")).append(": ").append(txPercent).append(" %");
                    SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMM yyyy", Locale.US);
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
                buf.append("<input type=submit class=actionStop name=\"action_Stop_").append(b64).append("\" value=\"").append(_t("Stop"))
                   .append("\" title=\"").append(_t("Stop torrent")).append("\"").append(shouldDisable ? " disabled" : "").append(">");
            } else if (!snark.isStarting()) {
                buf.append("<input type=submit class=actionStart name=\"action_Start_").append(b64).append("\" value=\"").append(_t("Start"))
                   .append("\" title=\"").append(_t("Start torrent")).append("\"").append(shouldDisable ? " disabled" : "").append(">");

                if (isValid && canWrite) {
                    buf.append("<input type=submit class=actionRemove name=\"action_Remove_").append(b64).append("\" value=\"").append(_t("Remove"))
                       .append("\" title=\"").append(_t("Remove and delete torrent, retaining downloaded files")).append("\" client=\"")
                       .append(escapeJSString(snark.getName())).append("\" data-name=\"").append(escapeJSString(snark.getBaseName())).append(".torrent\">");
                }
                if (!isValid || canWrite) {
                    buf.append("<input type=submit class=actionDelete name=\"action_Delete_").append(b64).append("\" value=\"").append(_t("Delete"))
                       .append("\" title=\"").append(_t("Delete .torrent file and associated data files")).append("\" client=\"")
                       .append(escapeJSString(snark.getName())).append("\" data-name=\"").append(escapeJSString(snark.getBaseName())).append(".torrent\">");
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
     * Generates HTML status string and status code for a Snark based on its state and peer info.
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
        StringBuilder iconBuf = new StringBuilder(128);
        StringBuilder statusBuf = new StringBuilder(256);

        String snarkSt = "";

        boolean isAllocating = snark.isAllocating();
        boolean isChecking = snark.isChecking();
        boolean isStarting = snark.isStarting();
        boolean hasTrackerProblems = snark.getTrackerProblems() != null && isRunning && curPeers == 0;
        boolean isComplete = remaining == 0 || needed == 0;
        boolean isSeeding = isComplete && isRunning;
        boolean isStopped = !isRunning;
        boolean hasConnectedPeers = curPeers > 0;
        boolean hasPeers = knownPeers > 0;
        boolean isUploading = upBps > 0;
        boolean isDownloading = downBps > 0;
        boolean isActivelySeeding = isComplete && isRunning && hasPeers && isUploading;

        // Cache repeated peer count HTML once
        final String peerCountHtml = new StringBuilder()
            .append("</td><td class=peerCount><b><span class=right>")
            .append(curPeers)
            .append("</span>")
            .append(thinsp(noThinsp))
            .append("<span class=left>").append(knownPeers).append("</span>")
            .toString();

        if (isChecking) {
            appendIcon(iconBuf, "processing", "", _t("Checking"), false, true);
            statusBuf.append(iconBuf).append(peerCountHtml);
            snarkSt = "active starting processing";
        } else if (isAllocating) {
            appendIcon(iconBuf, "processing", "", _t("Allocating"), false, true);
            statusBuf.append(iconBuf).append("</td><td class=peerCount><b>");
            snarkSt = "active starting processing";
        } else if (hasTrackerProblems) {
            String tooltip = snark.getTrackerProblems();
            appendIcon(iconBuf, "error", "", tooltip, false, true);
            statusBuf.append(iconBuf).append(peerCountHtml);
            snarkSt = "inactive downloading incomplete neterror";
        } else if (isStarting) {
            appendIcon(iconBuf, "stalled", "", _t("Starting"), false, true);
            statusBuf.append(iconBuf).append("</td><td class=peerCount><b>");
            snarkSt = "active starting";
        } else if (isActivelySeeding) {
            String tooltip = ngettext("Seeding to {0} peer", "Seeding to {0} peers", curPeers);
            appendIcon(iconBuf, "seeding_active", "", tooltip, false, true);
            statusBuf.append(iconBuf).append(peerCountHtml);
            snarkSt = "active seeding complete connected";
        } else if (isSeeding && hasConnectedPeers && !isUploading) {
            statusBuf.append(toSVGWithDataTooltip("seeding", "",
                    _t("Seeding") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")"))
                .append(peerCountHtml);
            snarkSt = "inactive seeding complete connected";
        } else if (!isComplete && hasConnectedPeers && !isUploading && !isDownloading) {
            statusBuf.append(toSVGWithDataTooltip("stalled", "",
                    _t("Stalled") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")"))
                .append(peerCountHtml);
            snarkSt = "inactive incomplete connected";
        } else if (isSeeding) {
            String tooltip = ngettext("Seeding to {0} peer in swarm", "Seeding to {0} peers in swarm", curPeers);
            appendIcon(iconBuf, "seeding", "", tooltip, false, true);
            statusBuf.append(iconBuf).append(peerCountHtml);
            snarkSt = "inactive seeding complete";
        } else if (!isRunning && isComplete) {
            snarkSt = "inactive complete stopped";
            statusBuf.append(toSVGWithDataTooltip("complete", "", _t("Complete"))).append("</td><td class=peerCount><b>&mdash;");
        } else {
            if (hasConnectedPeers && isDownloading) {
                statusBuf.append(toSVGWithDataTooltip("downloading", "",
                        _t("OK") + ", " + ngettext("Downloading from {0} peer", "Downloading from {0} peers", curPeers)))
                    .append(peerCountHtml);
                snarkSt = "active downloading incomplete connected";
            } else if (!isComplete && hasConnectedPeers) {
                statusBuf.append(toSVGWithDataTooltip("stalled", "",
                        _t("Stalled") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")"))
                    .append(peerCountHtml);
                snarkSt = "inactive downloading incomplete connected";
            } else if (isRunning && hasPeers && !hasConnectedPeers) {
                statusBuf.append(toSVGWithDataTooltip("nopeers", "",
                        _t("No Peers") + " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")"))
                    .append("</td><td class=peerCount><b><span class=right>0</span>")
                    .append(thinsp(noThinsp))
                    .append("<span class=left>").append(knownPeers).append("</span>");
                snarkSt = "inactive downloading incomplete nopeers";
            } else if (isRunning && !hasPeers) {
                statusBuf.append(toSVGWithDataTooltip("nopeers", "", _t("No Peers")))
                    .append(peerCountHtml);
                snarkSt = "inactive downloading incomplete nopeers zero";
            } else {
                statusBuf.append(toSVGWithDataTooltip("stopped", "", _t("Stopped")))
                    .append("</td><td class=peerCount><b>&mdash;");
                snarkSt = "inactive incomplete stopped zero";
            }
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
     * Appends HTML for a single peer row to the given StringBuilder.
     *
     * @param buf the StringBuilder to append to
     * @param peer the Peer object to render
     * @param MetaInfo the MetaInfo of the torrent (may be null)
     * @param boolean noThinsp whether to suppress thin space characters
     */
    private void appendPeerRow(StringBuilder buf, Peer peer, Snark snark, MetaInfo meta, boolean noThinsp) {
        long t = peer.getInactiveTime();
        String snarkStatus;
        if ((peer.getUploadRate() > 0 || peer.getDownloadRate() > 0) && t < 60 * 1000) {
            snarkStatus = "active";
            if (peer.getUploadRate() > 0 && !peer.isInteresting() && !peer.isChoking()) {
                snarkStatus += " TX";
            }
            if (peer.getDownloadRate() > 0 && !peer.isInterested() && !peer.isChoked()) {
                snarkStatus += " RX";
            }
        } else {snarkStatus = "inactive";}

        if (!peer.isConnected()) {return;}

        buf.append("<tr class=\"peerinfo ")
           .append(snarkStatus)
           .append(" volatile\">\n<td class=status title=\"")
           .append(_t("Peer attached to swarm"))
           .append("\"></td><td class=peerdata colspan=5>");

        PeerID pid = peer.getPeerID();
        String ch = pid != null ? pid.toString() : "????";
        if (ch.startsWith("WebSeed@")) {buf.append(ch);}
        else {
            String client = getClientName(peer);
            buf.append("<span class=peerclient><code title=\"")
               .append(_t("Destination (identity) of peer"))
               .append("\">")
               .append(peer.toString().substring(5, 9))
               .append("</code>&nbsp;<span class=clientid>")
               .append(client)
               .append("</span></span>");
        }

        if (t >= 5000) {
            buf.append("<span class=inactivity style=width:").append(t / 2000)
               .append("px title=\"").append(_t("Inactive")).append(": ")
               .append(t / 1000).append(' ').append(_t("seconds")).append("\"></span>");
        }

        buf.append("</td><td class=ETA></td><td class=rxd>");
        float pct;
        boolean isValid = meta != null;
        if (isValid) {
            pct = (float) (100.0 * peer.completed() / meta.getPieces());
            if (pct >= 100.0) {
                buf.append("<span class=peerSeed title=\"")
                   .append(_t("Seed"))
                   .append("\">");
                appendIcon(buf, "peerseed", _t("Seed"), "", false, true);
                buf.append("</span>");
            } else {buf.append(buildProgressBar(100, (int) (100 - pct), true, false, noThinsp, false));}
        } else {pct = 101.0f;} // Indicates unknown

        buf.append("</td><td class=\"rateDown");
        if (peer.getDownloadRate() >= 100000) {buf.append(" hundred");}
        else if (peer.getDownloadRate() >= 10000) {buf.append(" ten");}
        buf.append("\">");

        long needed = meta != null ? snark.getNeededLength() : -1;
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
        } else if (!isValid) {
            buf.append("<span class=unchoked><span class=right>")
               .append(formatSizeSpans(formatSize(peer.getDownloadRate()), false))
               .append("/s</span></span>");
        }

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

        buf.append("</td><td class=tAction></td></tr>\n");
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
     *   &lt;li&gt;Clients starting with "LU" or "ZV" may indicate Az or Robert versions&lt;/li&gt;
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
        String version = null;

        // Special cases for known clients
        if (ch.startsWith("ZV") || "VUZP".equals(ch)) {version = getRobtVersion(pid.getID());}
        else if (ch.startsWith("LU")) {version = getAzVersion(pid.getID());}

        boolean hasVersion = version != null && !version.isEmpty();

        if ("AwMD".equals(ch)) {return "I2PSnark";}
        else if ("LUFa".equals(ch)) {return "Vuze";}
        else if ("LUJJ".equals(ch)) {return "BiglyBT";}
        else if ("LVhE".equals(ch)) {return "XD";}
        else if (ch.startsWith("LV")) {return "Transmission";}
        else if ("LUtU".equals(ch)) {return "KTorrent";}
        else if ("LUVU".equals(ch)) {return "EepTorrent";}
        else if ("LURF".equals(ch)) {return "Deluge";}
        else if ("LXFC".equals(ch)) {return "qBittorrent";}
        else if ("LUxU".equals(ch)) {return "libtorrent";}
        else if ("VElY".equals(ch)) {return "Tixati";}
        else if ("ZV".equals(ch.substring(2, 4)) || "VUZP".equals(ch)) {return "Robert";}
        else if ("CwsL".equals(ch)) {return "I2PSnarkXL";}
        else if ("BFJT".equals(ch)) {return "I2PRufus";}
        else if ("TTMt".equals(ch)) {return "I2P-BT";}

        // Try to extract client name from handshake "v" field
        Map<String, BEValue> handshake = peer.getHandshakeMap();
        if (handshake != null) {
            BEValue bev = handshake.get("v");
            if (bev != null) {
                try {
                    String s = bev.getString();
                    if (!s.isEmpty()) {return s.length() > 64 ? s.substring(0, 64) : s;}
                } catch (InvalidBEncodingException ignored) {}
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
     *  Make it JS and HTML-safe
     *  @since 0.9.15
     *  http://stackoverflow.com/questions/8749001/escaping-html-entities-in-javascript-string-literals-within-the-script-block
     */
    private static String escapeJSString(String s) {
        return s.replace("\\", "\\u005c")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("\"", "\\u0022")
                .replace("'", "\\u0027")
                .replace("&", "\\u0026");
    }

    /**
     *  Get version from bytes 3-6
     *  @return " w.x.y.z" or ""
     *  @since 0.9.14
     */
    private static String getAzVersion(byte[] id) {
        if (id[7] != '-') {return "";}
        StringBuilder buf = new StringBuilder(8);
        buf.append(' ');
        for (int i = 3; i <= 6; i++) {
            int val = id[i] - '0';
            if (val < 0) {return "";}
            if (val > 9) {val = id[i] - 'A';}
            if (i != 6 || val != 0) {
                if (i != 3) {buf.append('.');}
                buf.append(val);
            }
        }
        return buf.toString();
    }

    /**
     *  Get version from bytes 3-5
     *  @return " w.x.y" or ""
     *  @since 0.9.14
     */
    private static String getRobtVersion(byte[] id) {
        StringBuilder buf = new StringBuilder(8);
        buf.append(' ');
        for (int i = 3; i <= 5; i++) {
            int val = id[i];
            if (val < 0) {return "";}
            if (i != 3) {buf.append('.');}
            buf.append(val);
        }
        return buf.toString();
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
     *  Generate link to details page if we know it supports it.
     *  Start of anchor only, caller must add anchor text or img and close anchor.
     *
     *  @return string or null if unknown tracker
     *  @since 0.8.4
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
                buf.append("<a href=\"").append(baseURL).append("details.php?info_hash=")
                   .append(TrackerClient.urlencode(infohash))
                   .append("\" title=\"").append(_t("Details at {0} tracker", name)).append("\" target=_blank>");
                return buf.toString();
            }
        }
        return null;
    }

    /**
     *  Full link to details page with img
     *  @return string or null if details page unsupported
     *  @since 0.8.4
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
     *  Full anchor to home page or details page with shortened hostname as anchor text
     *  @return string, non-null
     *  @since 0.9.5
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
/**
        else if (announce.startsWith("udp://tracker.")) {
            announce = announce.substring(14) + " [ext]";
            int colon = announce.indexOf(':');
            String port = "";
            if (colon > 0) {
                port = announce.substring(colon);
                announce = announce.substring(0, colon);
            }
        } else if (announce.startsWith("udp://")) {
            announce = announce.substring(6) + " [ext]";
            int colon = announce.indexOf(':');
            String port = "";
            if (colon > 0) {
                port = announce.substring(colon);
                announce = announce.substring(0, colon);
            }
        }
**/
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
        if (newURL.length() > 0) {buf.append(" checked>");} // force toggle open
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

    private void writeSeedForm(PrintWriter out, HttpServletRequest req, List<Tracker> sortedTrackers, List<TorrentCreateFilter> sortedFilters) throws IOException {
        StringBuilder buf = new StringBuilder(3*1024);
        _resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
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

    private static final int[] times = { 5, 15, 30, 60, 2*60, 5*60, 10*60, 30*60, 60*60, -1 };

    private void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String dataDir = _manager.getDataDir().getAbsolutePath();
        String lang = (Translate.getLanguage(_manager.util().getContext()));
        boolean filesPublic = _manager.areFilesPublic();
        boolean autoStart = _manager.shouldAutoStart();
        boolean useOpenTrackers = _manager.util().shouldUseOpenTrackers();
        boolean useDHT = _manager.util().shouldUseDHT();
        boolean useRatings = _manager.util().ratingsEnabled();
        boolean useComments = _manager.util().commentsEnabled();
        boolean collapsePanels = _manager.util().collapsePanels();
        boolean showStatusFilter = _manager.util().showStatusFilter();
        boolean enableLightbox = _manager.util().enableLightbox();
        boolean enableAddCreate = _manager.util().enableAddCreate();
        boolean noCollapse = noCollapsePanels(req);
        boolean varyInbound = _manager.util().enableVaryInboundHops();
        boolean varyOutbound = _manager.util().enableVaryOutboundHops();
        //String openTrackers = _manager.util().getOpenTrackerString();
        //int seedPct = 0;

/* configuration */

        StringBuilder buf = new StringBuilder(16*1024);
        buf.append("<form id=mainconfig action=\"").append(_contextPath).append("/configure\" method=POST>\n")
           .append("<div class=\"configPanel lang_").append(lang).append("\"><div class=snarkConfig>\n");
        writeHiddenInputs(buf, req, "Save");
        buf.append("<span class=configTitle>").append(_t("Configuration")).append("</span><hr>\n")
           .append("<table border=0 id=configs>\n");

/* user interface */

        buf.append("<tr><th class=suboption>").append(_t("User Interface"));
        if (_context.isRouterContext()) {
            buf.append("&nbsp;&nbsp;<a href=\"/torrents?configure\" target=_top class=script id=embed>")
               .append(_t("Switch to Embedded Mode")).append("</a>")
               .append("<a href=\"").append(_contextPath).append("/configure\" target=_top class=script id=fullscreen>")
               .append(_t("Switch to Fullscreen Mode")).append("</a>");
        }
        buf.append("</th></tr>\n<tr><td>\n<div class=optionlist>\n").append("<span class=configOption><b>")
           .append(_t("Theme")).append("</b> \n");
        if (_manager.getUniversalTheming()) {
            buf.append("<select id=themeSelect name=theme disabled title=\"")
               .append(_t("To change themes manually, disable universal theming"))
               .append("\"><option>")
               .append(_manager.getTheme())
               .append("</option></select> <span id=bwHoverHelp>");
            appendIcon(buf, "details", "", "", true, true);
            buf.append("<span id=bwHelp>")
               .append(_t("Universal theming is enabled."))
               .append("</span></span> <a href=\"/configui\" target=_blank>[")
               .append(_t("Configure"))
               .append("]</a></span><br>");
        } else {
            buf.append("<select id=themeSelect name=theme>");
            String theme = _manager.getTheme();
            String[] themes = _manager.getThemes();
            Arrays.sort(themes);
            for (int i = 0; i < themes.length; i++) {
                if (themes[i].equals(theme)) {
                    buf.append("\n<OPTION value=\"").append(themes[i]).append("\" SELECTED>").append(themes[i]);
                } else {
                    buf.append("\n<OPTION value=\"").append(themes[i]).append("\">").append(themes[i]);
                }
            }
            buf.append("</select>\n</span><br>\n");
        }

        buf.append("<span class=configOption><b>").append(_t("Refresh time"))
           .append("</b> \n<select name=refreshDelay title=\"")
           .append(_t("How frequently torrent status is updated on the main page")).append("\">");
        int delay = _manager.getRefreshDelaySeconds();
        for (int i = 0; i < times.length; i++) {
            buf.append("<option value=\"").append(Integer.toString(times[i])).append("\"");
            if (times[i] == delay) {buf.append(" selected");}
            buf.append(">");
            if (times[i] > 0) {buf.append(DataHelper.formatDuration2(times[i] * 1000));}
            else {buf.append(_t("Never"));}
            buf.append("</option>\n");
        }
        buf.append("</select>\n</span><br>\n")
           .append("<span class=configOption><label><b>")
           .append(_t("Page size"))
           .append("</b> <input type=text name=pageSize size=5 maxlength=4 min=10 pattern=\"[0-9]{0,4}\" ")
           .append("class=\"r numeric\" title=\"")
           .append(_t("Maximum number of torrents to display per page"))
           .append("\" value=\"").append(_manager.getPageSize()).append("\"> ")
           .append(_t("torrents"))
           .append("</label></span><br>\n");

        if (isStandalone()) {
            try {
                // class only in standalone builds
                Class<?> helper = Class.forName("org.klomp.snark.standalone.ConfigUIHelper");
                Method getLangSettings = helper.getMethod("getLangSettings", I2PAppContext.class);
                String langSettings = (String) getLangSettings.invoke(null, _context);
                // If we get to here, we have the language settings
                buf.append("<span class=configOption><b>").append(_t("Language")).append("</b> ")
                   .append(langSettings).append("</span><br>\n");
            } catch (ClassNotFoundException e) {}
            catch (NoSuchMethodException e) {}
            catch (IllegalAccessException e) {}
            catch (InvocationTargetException e) {}
        } else {
            buf.append("<span class=configOption><b>").append(_t("Language")).append("</b> ")
               .append("<span id=snarkLang>").append(lang).append("</span> ")
               .append("<a href=\"/configui#langheading\" target=_blank>").append("[").append(_t("Configure")).append("]</a>")
               .append("</span><br>\n");
        }

        buf.append("<span class=configOption><label for=collapsePanels><b>")
           .append(_t("Collapsible panels"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=collapsePanels id=collapsePanels ")
           .append((collapsePanels ? "checked " : ""))
           .append("title=\"");
        if (noCollapse) {
            String ua = req.getHeader("user-agent");
            buf.append(_t("Your browser does not support this feature.")).append("[" + ua + "]").append("\" disabled");
        } else {
            buf.append(_t("Allow the 'Add Torrent' and 'Create Torrent' panels to be collapsed, and collapse by default in non-embedded mode")).append("\"");
        }
        buf.append("></span><br>\n")
           .append("<span class=configOption><label for=showStatusFilter><b>")
           .append(_t("Torrent filter bar"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=showStatusFilter id=showStatusFilter ")
           .append((showStatusFilter ? "checked " : ""))
           .append("title=\"")
           .append(_t("Show filter bar above torrents for selective display based on status"))
           .append(" (").append(_t("requires javascript")).append(")")
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=enableLightbox><b>")
           .append(_t("Enable lightbox"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=enableLightbox id=enableLightbox ")
           .append((enableLightbox ? "checked " : ""))
           .append("title=\"")
           .append(_t("Use a lightbox to display images when thumbnails are clicked"))
           .append(" (").append(_t("requires javascript")).append(")")
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=enableAddCreate><b>")
           .append(_t("Persist Add/Create"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=enableAddCreate id=enableAddCreate ")
           .append((enableAddCreate ? "checked " : ""))
           .append("title=\"")
           .append(_t("Display the 'Add' and 'Create' sections on all torrent listing pages when in multipage mode"))
           .append("\"></span><br>\n")
           .append("</div>\n</td></tr>\n");

/* comments/ratings */

        buf.append("<tr><th class=suboption>")
           .append(_t("Comments &amp; Ratings"))
           .append("</th></tr>\n<tr><td>\n<div class=optionlist>\n")
           .append("<span class=configOption><label for=ratings><b>")
           .append(_t("Enable Ratings"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=ratings id=ratings ")
           .append(useRatings ? "checked " : "")
           .append("title=\"")
           .append(_t("Show ratings on torrent pages"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=comments><b>")
           .append(_t("Enable Comments"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=comments id=comments ")
           .append(useComments ? "checked " : "")
           .append("title=\"")
           .append(_t("Show comments on torrent pages"))
           .append("\"></span><br>\n")
           .append("<span class=configOption id=configureAuthor><label><b>")
           .append(_t("Comment Author"))
           .append("</b> <input type=text name=nofilter_commentsName spellcheck=false value=\"")
           .append(DataHelper.escapeHTML(_manager.util().getCommentsName())).append("\" size=15 maxlength=16 title=\"")
           .append(_t("Set the author name for your comments and ratings"))
           .append("\"></label></span>\n")
           .append("</div>\n</td></tr>\n");

/* torrent options */

        buf.append("<tr><th class=suboption>")
           .append(_t("Torrent Options"))
           .append("</th></tr>\n<tr><td>\n<div class=optionlist>\n")
           .append("<span id=bwAllocation class=configOption title=\"").append(_t("Half available bandwidth recommended.")).append("\">")
           .append("<b>").append(_t("Bandwidth limit")).append("</b> ")
           .append("<span id=bwDown></span><input type=text name=downBW class=\"r numeric\" value=\"")
           .append(_manager.getBandwidthListener().getDownBWLimit() / 1024).append("\" size=5 maxlength=4 pattern=\"[0-9]{1,4}\"")
           .append(" title=\"").append(_t("Maximum bandwidth allocated for downloading")).append("\"> KB/s down")
           .append(" <span id=bwUp></span><input type=text name=upBW class=\"r numeric\" value=\"")
           .append(_manager.util().getMaxUpBW()).append("\" size=5 maxlength=4 pattern=\"[0-9]{1,4}\"")
           .append(" title=\"").append(_t("Maximum bandwidth allocated for uploading")).append("\"> KB/s up");
        if (_context.isRouterContext()) {
            buf.append(" <a href=\"/config.jsp\" target=_blank title=\"")
               .append(_t("View or change router bandwidth"))
               .append("\">[")
               .append(_t("Configure"))
               .append("]</a>");
        }

        buf.append("</span><br>\n");
        buf.append("<span class=configOption><label><b>")
           .append(_t("Total uploader limit"))
           .append("</b> <input type=text name=upLimit class=\"r numeric\" value=\"")
           .append(_manager.util().getMaxUploaders()).append("\" size=5 maxlength=3 pattern=\"[0-9]{1,3}\"")
           .append(" title=\"")
           .append(_t("Maximum number of peers to upload to"))
           .append("\"> ")
           .append(_t("peers"))
           .append("</label></span><br>\n")
           .append("<span class=configOption><label for=useOpenTrackers><b>")
           .append(_t("Use open trackers also").replace(" also", ""))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=useOpenTrackers id=useOpenTrackers ")
           .append(useOpenTrackers ? "checked " : "")
           .append("title=\"")
           .append(_t("Announce torrents to open trackers as well as trackers listed in the torrent file"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=useDHT><b>")
           .append(_t("Enable DHT"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=useDHT id=useDHT ")
           .append(useDHT ? "checked " : "")
           .append("title=\"")
           .append(_t("Use DHT to find additional peers"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=autoStart><b>")
           .append(_t("Auto start torrents"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" name=autoStart id=autoStart")
           .append(autoStart ? " checked" : "")
           .append(" title=\"")
           .append(_t("Automatically start torrents when added and restart torrents when I2PSnark starts"))
           .append("\"></span>");

        if (_context.isRouterContext()) {
            buf.append("<br>\n<span class=configOption><label><b>")
               .append(_t("Startup delay"))
               .append("</b> <input type=text name=startupDelay size=5 maxlength=4 pattern=\"[0-9]{1,4}\" class=\"r numeric\"")
               .append(" title=\"")
               .append(_t("How long before auto-started torrents are loaded when I2PSnark starts"))
               .append("\" value=\"").append(_manager.util().getStartupDelay()).append("\"> ")
               .append(_t("minutes"))
               .append("</label></span>");
        }
        buf.append("\n</div>\n</td></tr>\n");

/* data storage */

        boolean isWindows = SystemVersion.isWindows();
        boolean isARM = SystemVersion.isARM();

        buf.append("<tr><th class=suboption>")
           .append(_t("Data Storage"))
           .append("</th></tr><tr><td>\n<div class=optionlist>\n")
           .append("<span class=configOption><label><b>")
           .append(_t("Data directory"))
           .append("</b> <input type=text name=nofilter_dataDir size=60").append(" title=\"")
           .append(_t("Directory where torrents and downloaded/shared files are stored"))
           .append("\" value=\"").append(DataHelper.escapeHTML(dataDir)).append("\" spellcheck=false></label></span><br>\n")
           .append("<span class=configOption><label for=filesPublic><b>")
           .append(_t("Files readable by all"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" name=filesPublic id=filesPublic ")
           .append(filesPublic ? "checked " : "").append("title=\"")
           .append(_t("Set file permissions to allow other local users to access the downloaded files"))
           .append("\"></span>\n")
           .append("<span class=configOption><label for=maxFiles><b>")
           .append(_t("Max files per torrent"))
           .append("</b> <input type=text name=maxFiles size=5 maxlength=5 pattern=\"[0-9]{1,5}\" class=\"r numeric\"").append(" title=\"")
           .append(_t("Maximum number of files permitted per torrent - note that trackers may set their own limits, and your OS may limit the number of open files, preventing torrents with many files (and subsequent torrents) from loading"))
           .append("\" value=\"" + _manager.getMaxFilesPerTorrent() + "\" spellcheck=false disabled></label></span><br>\n")
           .append("</div></td></tr>\n");

/* i2cp/tunnel configuration */
        _resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
        String IPString = _manager.util().getOurIPString();
        Map<String, String> options = new TreeMap<String, String>(_manager.util().getI2CPOptions());

        buf.append("<tr><th class=suboption>").append(_t("Tunnel Configuration")).append("&nbsp;");
        if (!IPString.equals("unknown")) {
            // Only truncate if it's an actual dest
            buf.append("&nbsp;<span id=ourDest title=\"")
               .append(_t("Our destination (identity) for this session")).append("\">")
               .append(_t("Dest.")).append("<code>").append(IPString.substring(0,4)).append("</code></span>");
        }
        buf.append("</th></tr>\n<tr><td>\n<div class=optionlist>\n");
        buf.append("<span class=configOption><b>")
           .append(_t("Inbound Settings"))
           .append("</b> \n")
           .append(renderOptions(1, 16, SnarkManager.DEFAULT_TUNNEL_QUANTITY, options.remove("inbound.quantity"), "inbound.quantity", TUNNEL))
           .append("&nbsp;")
           .append(renderOptions(0, 6, 3, options.remove("inbound.length"), "inbound.length", HOP))
           .append("</span><br>\n")
           .append("<span class=configOption><b>")
           .append(_t("Outbound Settings"))
           .append("</b> \n")
           .append(renderOptions(1, 32, SnarkManager.DEFAULT_TUNNEL_QUANTITY, options.remove("outbound.quantity"), "outbound.quantity", TUNNEL))
           .append("&nbsp;")
           .append(renderOptions(0, 6, 3, options.remove("outbound.length"), "outbound.length", HOP))
           .append("</span><br>\n")
           .append("<span class=configOption id=hopVariance><b>")
           .append(_t("Vary Tunnel Length"))
           .append("</b> \n")
           .append("<label title=\"").append(_t("Add 0 or 1 additional hops randomly to Inbound tunnels")).append("\">")
           .append("<input type=checkbox class=\"optbox slider\" name=varyInbound id=varyInbound ")
           .append(varyInbound ? "checked " : "").append("> <span>").append(_t("Inbound")).append("</span></label>")
           .append("<label title=\"").append(_t("Add 0 or 1 additional hops randomly to Outbound tunnels")).append("\">")
           .append("<input type=checkbox class=\"optbox slider\" name=varyOutbound id=varyOutbound ")
           .append(varyOutbound ? "checked " : "").append("> <span>").append(_t("Outbound")).append("</span></label>")
           .append("</span><br>\n")
           .append("<script src=\"" + _resourcePath + "js/toggleVaryTunnelLength.js?" + CoreVersion.VERSION + "\" defer></script>\n")
           .append("<noscript><style>#hopVariance .optbox.slider{pointer-events:none!important;opacity:.4!important}</style></noscript>\n");

        if (isStandalone()) {
            buf.append("<span class=configOption><label><b>")
               .append(_t("I2CP host"))
               .append("</b> <input type=text name=i2cpHost value=\"")
               .append(_manager.util().getI2CPHost()).append("\" size=5></label></span><br>\n")
               .append("<span class=configOption><label><b>")
               .append(_t("I2CP port"))
               .append("</b> <input type=text name=i2cpPort value=\"")
               .append(_manager.util().getI2CPPort()).append("\" class=numeric size=5 maxlength=5 pattern=\"[0-9]{1,5}\"></label></span><br>\n");
        }

        options.remove(I2PSnarkUtil.PROP_MAX_BW);
        options.remove(SnarkManager.PROP_OPENTRACKERS); // was accidentally in the I2CP options prior to 0.8.9 so it will be in old config files
        StringBuilder opts = new StringBuilder(256);
        for (Map.Entry<String, String> e : options.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            opts.append(key).append('=').append(val).append(' ');
        }
        String ibkey = "inbound.lengthVariance=1 ";
        String obkey = "outbound.lengthVariance=1 ";
        boolean containsIbk = opts.indexOf(ibkey) != -1;
        boolean containsObk = opts.indexOf(obkey) != -1;
        if (varyInbound) {
            if (!containsIbk) {
                opts.append(ibkey);
                _manager.util().setVaryInboundHops(true);
            }
        } else if (!varyInbound) {
            if (containsIbk) {
                opts.delete(opts.indexOf(ibkey), opts.indexOf(ibkey) + ibkey.length());
                _manager.util().setVaryInboundHops(false);
            }
        }
        if (varyOutbound) {
            if (!containsObk) {
                opts.append(obkey);
                _manager.util().setVaryOutboundHops(true);
            }
        } else if (!varyOutbound) {
            if (containsObk) {
                opts.delete(opts.indexOf(obkey), opts.indexOf(obkey) + obkey.length());
                _manager.util().setVaryOutboundHops(false);
            }
        }
        String spacer = "<tr class=spacer><td></td></tr>\n";
        buf.append("<span class=configOption id=i2cpOptions><label><b>")
           .append(_t("I2CP options"))
           .append("</b> <input type=text id=i2cpOpts name=i2cpOpts value=\"")
           .append(opts.toString().trim()).append("\" size=60></label></span>\n")
           .append("</div>\n</td></tr>\n")
           .append(spacer);

/* save config */

        buf.append("<tr><td><input type=submit class=accept value=\"")
           .append(_t("Save configuration"))
           .append("\" name=foo></td></tr>\n")
           .append(spacer).append("</table></div></div></form>");
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     * Writes the HTML form for managing torrent creation file filters.
     *
     * @param out the PrintWriter to which the HTML output will be written
     * @param req the HttpServletRequest containing the current request parameters
     * @throws IOException if an I/O error occurs while writing to the output stream
     * @since 0.9.62+ Added torrent creation filter management form
     */
    private void writeTorrentCreateFilterForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(5*1024);
        buf.append("<form id=createFilterForm action=\"")
           .append(_contextPath)
           .append("/configure\" method=POST>\n<div class=configPanel id=fileFilter>\n<div class=snarkConfig>\n");
        writeHiddenInputs(buf, req, "SaveCreateFilters");
        buf.append("<span id=filtersTitle class=\"configTitle expanded\">")
           .append(_t("Torrent Create File Filtering"))
           .append("</span><hr>\n<table hidden>\n<tr>")
           .append("<th title=\"")
           .append(_t("Mark filter for deletion"))
           .append("\"></th><th>")
           .append(_t("Name"))
           .append("</th><th>")
           .append(_t("Filter Pattern"))
           .append("</th><th class=radio>")
           .append(_t("Starts With"))
           .append("</th><th class=radio>")
           .append(_t("Contains"))
           .append("</th><th class=radio>")
           .append(_t("Ends With"))
           .append("</th><th>")
           .append(_t("Enabled by Default"))
           .append("</th></tr>\n");
        for (TorrentCreateFilter f : _manager.getSortedTorrentCreateFilterStrings()) {
            boolean isDefault = f.isDefault;
            String filterType = f.filterType;
            String nameUnderscore = f.name.replace(" ", "_");
            buf.append("<tr class=createFilterString><td><input type=checkbox class=optbox name=\"delete_")
               .append(f.name)
               .append("\"></td><td>")
               .append(f.name)
               .append("</td><td>")
               .append(f.filterPattern)
               .append("</td><td>")
               .append("<label class=filterStartsWith><input type=radio class=optbox value=starts_with name=\"filterType_")
               .append(nameUnderscore)
               .append("\"")
               .append(filterType.equals("starts_with") ? " checked" : "")
               .append("></label></td><td><label class=filterContains><input type=radio class=optbox value=contains name=\"filterType_")
               .append(nameUnderscore)
               .append("\"")
               .append(filterType.equals("contains") ? " checked" : "")
               .append("></label></td><td><label class=filterEndsWith><input type=radio class=optbox value=ends_with name=\"filterType_")
               .append(nameUnderscore)
               .append("\"")
               .append(filterType.equals("ends_with") ? " checked" : "")
               .append("></label></td><td><input type=checkbox class=optbox name=\"defaultEnabled_")
               .append(f.name)
               .append("\"");
            if (f.isDefault) {buf.append(" checked");}
            buf.append("></td></tr>\n");
        }
        String spacer = "<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n";
        String filterFormElements =
            "<td><input type=text class=torrentCreateFilterName name=fname spellcheck=false></td>" +
            "<td><input type=text class=torrentCreateFilterPattern name=filterPattern spellcheck=false></td>" +
            "<td><label class=filterStartsWith><input type=radio class=optbox name=filterType value=starts_with></label></td>" +
            "<td><label class=filterContains><input type=radio class=optbox name=filterType value=contains checked></label></td>" +
            "<td><label class=filterEndsWith><input type=radio class=optbox name=filterType value=ends_with></label></td>" +
            "<td><input type=checkbox class=optbox name=filterIsDefault></td>";
        String buttons = String.format(
            "<tr><td colspan=7>\n" +
            "<input type=submit name=raction class=delete value=\"%s\">\n" +
            "<input type=submit name=raction class=accept value=\"%s\">\n" +
            "<input type=submit name=raction class=reload value=\"%s\">\n" +
            "<input type=submit name=raction class=add value=\"%s\">\n" +
            "</td></tr>\n",
            _t("Delete selected"),
            _t("Save Filter Configuration"),
            _t("Restore defaults"),
            _t("Add File Filter")
        );
        buf.append(spacer)
           .append("<tr id=addFileFilter>")
           .append("<td><b>").append(_t("Add")).append(":</b></td>").append(filterFormElements).append("</tr>")
           .append(spacer).append(buttons).append(spacer).append("</table>\n</div>\n</div>\n</form>\n");
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     * Writes the HTML form for managing trackers with optimized string building.
     * Minimizes append() calls by batching HTML fragments, and caches collections for efficient lookups.
     *
     * @param out the PrintWriter to which the HTML output will be written
     * @param req the HttpServletRequest containing the current request parameters
     * @throws IOException if an I/O error occurs while writing to the output stream
     * @since 0.9 Added tracker management form, optimized in 2025 for rendering performance
     */
    private void writeTrackerForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(5 * 1024);

        buf.append("<form id=trackerConfigForm action=\"")
           .append(_contextPath)
           .append("/configure\" method=POST>\n<div class=configPanel id=trackers><div class=snarkConfig>\n");
        writeHiddenInputs(buf, req, "SaveTrackers");
        buf.append("<span id=trackersTitle class=\"configTitle expanded\">")
           .append(_t("Trackers"))
           .append("</span><hr>\n<table id=trackerconfig hidden>\n<tr><th title=\"")
           .append(_t("Select trackers for removal from I2PSnark's known list"))
           .append("\"></th><th>")
           .append(_t("Name"))
           .append("</th><th>")
           .append(_t("Website URL"))
           .append("</th><th class=radio>")
           .append(_t("Standard"))
           .append("</th><th class=radio>")
           .append(_t("Open"))
           .append("</th><th class=radio>")
           .append(_t("Private"))
           .append("</th><th>")
           .append(_t("Announce URL"))
           .append("</th></tr>\n");

        I2PSnarkUtil util = _manager.util();
        Set<String> openSet = new HashSet<>(util.getOpenTrackers());
        Set<String> privateSet = new HashSet<>(_manager.getPrivateTrackers());

        // Batch all rows to reduce append calls
        StringBuilder rowsBatch = new StringBuilder(8192);
        for (Tracker t : _manager.getSortedTrackers()) {
            String name = t.name;
            String homeURL = t.baseURL.endsWith(".i2p/") ? t.baseURL.substring(0, t.baseURL.length() - 1) : t.baseURL;
            String announceURL = t.announceURL;

            boolean isPrivate = privateSet.contains(announceURL);
            boolean isKnownOpen = util.isKnownOpenTracker(announceURL);
            boolean isOpen = isKnownOpen || openSet.contains(announceURL);

            rowsBatch.append("<tr class=knownTracker><td><input type=checkbox class=optbox id=\"")
                     .append(name)
                     .append("\" name=\"delete_")
                     .append(name)
                     .append("\" title=\"")
                     .append(_t("Mark tracker for deletion"))
                     .append("\"></td><td><label for=\"")
                     .append(name)
                     .append("\">")
                     .append(name)
                     .append("</label></td><td>")
                     .append(urlify(homeURL, 64))
                     .append("</td><td><input type=radio class=optbox value=\"0\" tabindex=-1 name=\"ttype_")
                     .append(announceURL).append("\"");
            if (!(isOpen || isPrivate)) {rowsBatch.append(" checked");}
            else if (isKnownOpen) {rowsBatch.append(" disabled");}
            rowsBatch.append("></td><td><input type=radio class=optbox value=1 tabindex=-1 name=\"ttype_")
                     .append(announceURL)
                     .append("\"");
            if (isOpen) {rowsBatch.append(" checked");}
            else if ("http://diftracker.i2p/announce.php".equals(announceURL) ||
                     "http://tracker2.postman.i2p/announce.php".equals(announceURL) ||
                     "http://torrfreedom.i2p/announce.php".equals(announceURL)) {
                rowsBatch.append(" disabled");
            }
            rowsBatch.append("></td><td><input type=radio class=optbox value=2 tabindex=-1 name=\"ttype_")
                     .append(announceURL)
                     .append("\"");
            if (isPrivate) {rowsBatch.append(" checked");}
            else if (isKnownOpen || "http://diftracker.i2p/announce.php".equals(announceURL) ||
                     "http://tracker2.postman.i2p/announce.php".equals(announceURL) ||
                     "http://torrfreedom.i2p/announce.php".equals(announceURL)) {
                rowsBatch.append(" disabled");
            }
            rowsBatch.append("></td><td>")
                     .append(urlify(announceURL, 64))
                     .append("</td></tr>\n");
        }

        buf.append(rowsBatch);

        _resourcePath = debug ? "/themes/" : _contextPath + WARBASE;

        String spacer = "<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n";
        String trackerFormElements =
            "<td><input type=text class=trackername name=tname spellcheck=false></td>" +
            "<td><input type=text class=trackerhome name=thurl spellcheck=false></td>" +
            "<td><input type=radio class=optbox value=0 name=add_tracker_type checked></td>" +
            "<td><input type=radio class=optbox value=1 name=add_tracker_type></td>" +
            "<td><input type=radio class=optbox value=2 name=add_tracker_type></td>" +
            "<td><input type=text class=trackerannounce name=taurl spellcheck=false></td>";

        String noscript =
            "<noscript><style>" +
            ".configPanel .configTitle{pointer-events:none!important}" +
            "#fileFilter table,#trackers table{display:table!important}" +
            "#fileFilter .configTitle::after,#trackers .configTitle::after{display:none!important}" +
            "</style></noscript>\n";

        buf.append(spacer);
        buf.append("<tr id=addtracker><td><b>")
           .append(_t("Add"))
           .append(":</b></td>")
           .append(trackerFormElements)
           .append("</tr>\n")
           .append(spacer);

        String buttons =
            "<tr><td colspan=7>\n" +
            "<input type=submit name=taction class=default value=\"" + _t("Add tracker") + "\">\n" +
            "<input type=submit name=taction class=delete value=\"" + _t("Delete selected") + "\">\n" +
            "<input type=submit name=taction class=accept value=\"" + _t("Save tracker configuration") + "\">\n" +
            "<input type=submit name=taction class=add value=\"" + _t("Add tracker") + "\">\n" +
            "<input type=submit name=taction class=reload value=\"" + _t("Restore defaults") + "\">\n" +
            "</td></tr>" + spacer +
            "</table>\n</div>\n</div></form>\n" +
            noscript +
            "<script src=\"" + _resourcePath + "js/toggleConfigs.js?" + CoreVersion.VERSION + "\"></script>\n";

        buf.append(buttons);
        out.append(buf);
        out.flush();
        buf.setLength(0);
    }

    /**
     *  @param url in base32 or hex
     *  @param dataDir null to default to snark data directory
     *  @since 0.8.4
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

    /** copied from ConfigTunnelsHelper */
    private static final String HOP = "hop";
    private static final String TUNNEL = "tunnel";
    /** dummies for translation */
    private static final String HOPS = ngettext("1 hop", "{0} hops");
    private static final String TUNNELS = ngettext("1 tunnel", "{0} tunnels");
    /** prevents the ngettext line below from getting tagged */
    private static final String DUMMY0 = "{0} ";
    private static final String DUMMY1 = "1 ";

   /**
    * Generates HTML for a dropdown selection menu.
    *
    * @param min the minimum value for the dropdown options
    * @param max the maximum value for the dropdown options
    * @param dflt the default value for the dropdown
    * @param strNow the string representation of the current selected option
    * @param selName the name attribute for the select element
    * @param name the base name of the option to be displayed in the dropdown
    * @return a string representing the HTML for the dropdown selection menu
    * @since 0.7.14 Modified from ConfigTunnelsHelper
    */
    private String renderOptions(int min, int max, int dflt, String strNow, String selName, String name) {
       int now = dflt;
       try {now = Integer.parseInt(strNow);}
       catch (Throwable t) {}
       StringBuilder buf = new StringBuilder(128);
       buf.append("<select name=\"").append(selName);
       if (selName.contains("quantity")) {
           buf.append("\" title=\"")
              .append(_t("This configures the maximum number of tunnels to open, determined by the number of connected peers (actual usage may be less)"));
       }
       if (selName.contains("length")) {
           buf.append("\" title=\"")
              .append(_t("Changing this setting to less than 3 hops may improve speed at the expense of anonymity and is not recommended"));
       }
       buf.append("\">\n");
       for (int i = min; i <= max; i++) {
           buf.append("<option value=\"").append(i).append("\"");
           if (i == now) {buf.append(" selected");}
           // constants to prevent tagging
           buf.append(">").append(ngettext(DUMMY1 + name, DUMMY0 + name + 's', i)).append("</option>\n");
       }
       buf.append("</select>\n");
       return buf.toString();
    }

    /** translate */
    private String _t(String s) {return _manager.util().getString(s);}

    /** translate */
    private String _t(String s, Object o) {return _manager.util().getString(s, o);}

    /** translate */
    private String _t(String s, Object o, Object o2) {return _manager.util().getString(s, o, o2);}

    /** translate (ngettext) @since 0.7.14 */
    private String ngettext(String s, String p, int n) {return _manager.util().getString(n, s, p);}

    /** dummy for tagging */
    private static String ngettext(String s, String p) {return null;}

    /** format filesize */
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
    private static String urlify(String s, int max) {
        StringBuilder buf = new StringBuilder(256);
        // browsers seem to work without doing this but let's be strict
        String link = urlEncode(s);
        String display;
        if (s.length() <= max) {
            if (link.startsWith("https")) {display = DataHelper.escapeHTML(link);}
            else {display = DataHelper.escapeHTML(link.replace("http://", ""));}
        } else {display = DataHelper.escapeHTML(s.substring(0, max)) + "&hellip;";}
        buf.append("<a href=\"").append(link).append("\" target=_blank>").append(display).append("</a>");
        return buf.toString();
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

    private static final String escapeChars[] = {"\"", "<", ">", "'"};
    private static final String escapeCodes[] = {"&quot;", "&lt;", "&gt;", "&apos;"};

    /**
     * Modded from DataHelper.
     * Does not escape ampersand. String must already have escaped ampersand.
     * @param unescaped the unescaped string, non-null
     * @return the escaped string
     * @since 0.9.33
     */
    private static String escapeHTML2(String unescaped) {
        String escaped = unescaped;
        for (int i = 0; i < escapeChars.length; i++) {
            escaped = escaped.replace(escapeChars[i], escapeCodes[i]);
        }
        return escaped;
    }

    private static final String DOCTYPE = "<!DOCTYPE HTML>\n";
    private static final String HEADER_A = "<link href=\"";
    private static final String HEADER_B = "snark.css?" + CoreVersion.VERSION + "\" rel=stylesheet id=snarkTheme>";
    private static final String HEADER_C = "nocollapse.css?" + CoreVersion.VERSION + "\" rel=stylesheet>";
    private static final String HEADER_D = "snark_big.css?" + CoreVersion.VERSION + "\" rel=stylesheet>";
    private static final String HEADER_I = "images/images.css?" + CoreVersion.VERSION + "\" rel=stylesheet>";
    private static final String HEADER_Z = "override.css\" rel=stylesheet>";
    private static final String TABLE_HEADER = "<table id=torrents width=100% border=0>\n" + "<thead id=snarkHead>";
    private static final String FOOTER = "</div>\n<span id=endOfPage data-iframe-height></span>\n" +
        "<script src=/js/iframeResizer/iframeResizer.contentWindow.js id=iframeResizer type=module></script>\n" +
        "<script src=/js/iframeResizer/updatedEvent.js type=module></script>\n" +
        "<script src=/js/setupIframe.js type=module></script>\n" +
        "<script src=/js/detectPageZoom.js type=module></script>\n" +
        "<link rel=stylesheet href=/i2psnark/.res/snarkAlert.css>\n" +
        "</body>\n</html>";
    private static final String FOOTER_STANDALONE = "</div>\n" +
        "<script src=/i2psnark/.res/js/detectPageZoom.js type=module></script>\n" +
        "<link rel=stylesheet href=/i2psnark/.res/snarkAlert.css>\n" + "</body>\n</html>";
    private static final String IFRAME_FORM = "<iframe name=processForm id=processForm hidden></iframe>\n";

    /**
     * Modded heavily from the Jetty version in Resource.java,
     * pass Resource as 1st param
     * All the xxxResource constructors are package local so we can't extend them.
     *
     * Get the resource list as a HTML directory listing.
     * @param xxxr The Resource unused
     * @param base The encoded base URL
     * @param parent True if the parent directory should be included
     * @param postParams map of POST parameters or null if not a POST
     * @return String of HTML or null if postParams != null
     * @since 0.7.14
     */
    private String getListHTML(File xxxr, String base, boolean parent, Map<String, String[]> postParams, String sortParam) throws IOException {
        _resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
        String decodedBase = decodePath(base);
        String title = decodedBase;
        String cpath = _contextPath + '/';
        String slash = String.valueOf(java.io.File.separatorChar);
        if (title.startsWith(cpath)) {title = title.substring(cpath.length());}

        // Get the snark associated with this directory
        String[] tNameAndPath = extractTorrentNameAndPath(title);
        String tName = tNameAndPath[0];
        String pathInTorrent = tNameAndPath[1];
        Snark snark = _manager.getTorrentByBaseName(tName);

        if (snark != null && postParams != null) {
            // caller must P-R-G
            String[] val = postParams.get("nonce");
            if (val != null) {
                String nonce = val[0];
                if (String.valueOf(_nonce).equals(nonce)) {
                    if (postParams.get("savepri") != null) {savePriorities(snark, postParams);}
                    else if (postParams.get("addComment") != null) {saveComments(snark, postParams);}
                    else if (postParams.get("deleteComments") != null) {deleteComments(snark, postParams);}
                    else if (postParams.get("setCommentsEnabled") != null) {saveCommentsSetting(snark, postParams);}
                    else if (postParams.get("stop") != null) {_manager.stopTorrent(snark, false);}
                    else if (postParams.get("start") != null) {_manager.startTorrent(snark);}
                    else if (postParams.get("recheck") != null) {_manager.recheckTorrent(snark);}
                    else if (postParams.get("editTorrent") != null) {saveTorrentEdit(snark, postParams);}
                    else if (postParams.get("setInOrderEnabled") != null) {
                        _manager.saveTorrentStatus(snark);
                        _manager.addMessage(_t("Sequential piece or file order not saved - feature currently broken."));
                    } else {_manager.addMessage(_t("Unknown command"));}
                } else {_manager.addMessage(_t("Please retry form submission (bad nonce)"));}
            }
            return null;
        }

        File r;
        if (snark != null) {
            Storage storage = snark.getStorage();
            if (storage != null) {
                File sbase = storage.getBase();
                if (pathInTorrent.equals("/")) {r = sbase;}
                else {r = new File(sbase, pathInTorrent);}
            } else {r = new File("");} // magnet, dummy}
        } else {r = new File("");} // dummy

        boolean showStopStart = snark != null;
        Storage storage = snark != null ? snark.getStorage() : null;
        boolean showPriority = storage != null && !storage.complete() && r.isDirectory();

        StringBuilder buf=new StringBuilder(6*1024);
        buf.append(DOCTYPE).append("<html").append(isStandalone() ? " class=standalone" : "").append(">\n")
           .append("<head>\n<meta charset=utf-8>\n").append("<title>");
        if (title.endsWith("/")) {title = title.substring(0, title.length() - 1);}
        final String directory = title;
        final int dirSlash = directory.indexOf('/');
        final boolean isTopLevel = dirSlash <= 0;
        title = _t("I2PSnark") + " - [" + _t("Torrent") + ": " + DataHelper.escapeHTML(title) + "]";
        buf.append(title).append("</title>\n").append(HEADER_A).append(_themePath).append(HEADER_B).append("\n");

        boolean collapsePanels = _manager.util().collapsePanels(); // uncollapse panels
        if (!collapsePanels) {buf.append(HEADER_A + _themePath + HEADER_C).append("\n");}
        String lang = (Translate.getLanguage(_manager.util().getContext()));
        if (lang.equals("zh") || lang.equals("ja") || lang.equals("ko")) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_D); // larger fonts for cjk translations
        }
        buf.append(HEADER_A + _themePath + HEADER_I).append("\n"); // images.css

        String theme = _manager.getTheme();
        String themeBase = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() + slash +
                           "docs" + slash + "themes" + slash + "snark" + slash + theme + slash;
        File override = new File(themeBase + "override.css");
        String fontPath = isStandalone() ? "/i2psnark/.res/themes/fonts/" : "/themes/fonts/";
        if (isStandalone() || useSoraFont()) {
            buf.append("<link rel=stylesheet href=").append(fontPath).append("Sora.css>\n");
        } else {
            buf.append("<link rel=stylesheet href=").append(fontPath).append("OpenSans.css>\n");
        }
        if (!isStandalone() && override.exists()) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_Z).append("\n"); // optional override.css for version-persistent user edits
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

        if (parent) {buf.append("<div class=page id=dirlist>\n");} // always true
         // for stop/start/check
        final boolean er = snark != null && _manager.util().ratingsEnabled();
        final boolean ec = snark != null && _manager.util().commentsEnabled(); // global setting
        final boolean esc = ec && _manager.getSavedCommentsEnabled(snark); // per-torrent setting
        final boolean includeForm = showStopStart || showPriority || er || ec;
        if (includeForm) {
            buf.append("<form action=\"").append(base).append("\" method=POST>\n")
               .append("<input type=hidden name=nonce value=\"").append(_nonce).append("\">\n");
            if (sortParam != null) {
                buf.append("<input type=hidden name=sort value=\"").append(DataHelper.stripHTML(sortParam)).append("\">\n");
            }
        }

        appendTorrentInfo(buf, snark, base, tName, showStopStart);
        displayTorrentEdit(snark, base, buf);

        if (snark != null && !r.exists()) {
            // fixup TODO
            buf.append("<table class=resourceError id=DoesNotExist>\n<tr><th colspan=2>")
               .append(_t("Resource Does Not Exist"))
               .append("</th></tr><tr><td><b>").append(_t("Resource")).append(":</b></td><td>").append(r.toString())
               .append("</td></tr><tr><td><b>").append(_t("Base")).append(":</b></td><td>").append(base)
               .append("</td></tr><tr><td><b>").append(_t("Torrent")).append(":</b></td><td>").append(DataHelper.escapeHTML(tName))
               .append("</td></tr>\n</table>");
            return buf.toString();
        }

        File[] ls = null;
        if (r.isDirectory()) {ls = r.listFiles();} // if r is not a directory, we are only showing torrent info section
        if (ls == null) {
            // We are only showing the torrent info section unless audio or video...
            if (storage != null && storage.complete()) {
                String mime = getMimeType(r.getName());
                boolean isAudio = mime != null && isAudio(mime);
                boolean isVideo = !isAudio && mime != null && isVideo(mime);
                String path = base.substring(0, base.length() - 1);
                String imgPath = isStandalone() ? "/i2psnark/.res/icons/" : "/themes/console/images/";
                String newTab = "<img src=" + imgPath + "newtab.svg width=16 height=auto class=newTab>";
                if (isAudio || isVideo) {
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
                    // strip trailing slash
                    buf.append("<source src=\"").append(path).append("\" type=\"").append(mime).append("\">");
                    if (isAudio) {buf.append("</audio>");}
                    else {buf.append("</video>");}
                    buf.append("</td></tr>\n</table>\n</div>\n");
                }
            }
            if (er || ec) {
                CommentSet comments = snark.getComments();
                buf.append("<div class=mainsection id=commentSection>")
                   .append("<input hidden class=toggle_input id=toggle_comments type=checkbox");
                if (comments != null && !comments.isEmpty()) {buf.append(" checked");}
                buf.append(">\n<label id=tab_comments class=toggleview for=toggle_comments><span class=tab_label>")
                   .append(_t("Comments &amp; Ratings"))
                   .append("</span></label><hr>\n");
                displayComments(snark, er, ec, esc, buf);
                buf.append("</div>\n");
            }
            if (includeForm) {buf.append("</form>\n");}
            if (!isStandalone()) {buf.append(FOOTER);}
            else {buf.append(FOOTER_STANDALONE);}
            return buf.toString();
        }

        List<Sorters.FileAndIndex> fileList = new ArrayList<Sorters.FileAndIndex>(ls.length);
        // Precompute remaining for all files for efficiency
        long[][] arrays = (storage != null) ? storage.remaining2() : null;
        long[] remainingArray = (arrays != null) ? arrays[0] : null;
        long[] previewArray = (arrays != null) ? arrays[1] : null;
        for (int i = 0; i < ls.length; i++) {
            File f = ls[i];
            if (isTopLevel) {
                // Hide (assumed) padding directory if it's in the filesystem.
                // Storage now will not create padding files, but may have been created by an old version or other client.
                String n = f.getName();
                if ((n.equals(".pad") || n.equals("_pad")) && f.isDirectory()) {continue;}
            }
            fileList.add(new Sorters.FileAndIndex(f, storage, remainingArray, previewArray));
        }

        boolean showSort = fileList.size() > 1;
        if (showSort) {
            int sort = 0;
            if (sortParam != null) {
                try {sort = Integer.parseInt(sortParam);}
                catch (NumberFormatException nfe) {}
            }
            DataHelper.sort(fileList, Sorters.getFileComparator(sort, this));
        }

// Directory info section

        buf.append("<div class=mainsection id=snarkFiles>")
           .append("<input hidden class=toggle_input id=toggle_files type=checkbox");
        // don't collapse file view if not in torrent root
        String up = "";
        if (!isTopLevel || fileList.size() <= 10 || sortParam != null || getQueryString(up) != null) {buf.append(" checked");}
        buf.append(">")
           .append("<label id=tab_files class=toggleview for=toggle_files><span class=tab_label>")
           .append(_t("Files"))
           .append("</span></label><hr>\n")
           .append("<table id=dirInfo>\n<thead>\n<tr>\n<th colspan=2>");
        String tx = _t("Directory");
        // cycle through sort by name or type
        // TODO: add "(ascending") or "(descending") suffix to tooltip to indicate direction of sort
        String sort;
        boolean isTypeSort = false;
        if (showSort) {
            if (sortParam == null || "0".equals(sortParam) || "1".equals(sortParam)) {sort = "-1";}
            else if ("-1".equals(sortParam)) {sort = "12"; isTypeSort = true;}
            else if ("12".equals(sortParam)) {sort = "-12"; isTypeSort = true;}
            else {sort = "";}
            buf.append("<a href=\"").append(base).append(getQueryString(sort)).append("\">");
        }
        appendIcon(buf, "file", tx, showSort ? _t("Sort by {0}", (isTypeSort ? _t("File type") : _t("Name"))) : tx + ": " + directory, true, false);
        if (showSort) {buf.append("</a>");}
        if (!isTopLevel) {
            buf.append("&nbsp;").append(DataHelper.escapeHTML(directory.substring(dirSlash + 1)));
        }
        buf.append("</th><th class=fileSize>");
        if (showSort) {
            sort = ("-5".equals(sortParam)) ? "5" : "-5";
            buf.append("<a href=\"").append(base).append(getQueryString(sort)).append("\">");
        }
        tx = _t("Size");
        appendIcon(buf, "size", tx, showSort ? _t("Sort by {0}", tx) : tx, true, false);
        if (showSort) {buf.append("</a>");}
        buf.append("</th><th class=fileStatus>");
        boolean showRemainingSort = showSort && showPriority;
        if (showRemainingSort) {
            sort = ("10".equals(sortParam)) ? "-10" : "10";
            buf.append("<a id=sortRemaining href=\"").append(base)
               .append(getQueryString(sort)).append("\">");
        }
        tx = _t("Download Status");
        appendIcon(buf, "status", tx, showRemainingSort ? _t("Sort by {0}", _t("Remaining")) : tx, true, false);
        if (showRemainingSort) {buf.append("</a>");}
        if (showPriority) {
            buf.append("</th><th class=\"priority volatile\">");
            if (showSort) {
                sort = ("13".equals(sortParam)) ? "-13" : "13";
                buf.append("<a href=\"").append(base).append(getQueryString(sort)).append("\">");
            }
            tx = _t("Download Priority");
            appendIcon(buf, "priority", tx, showSort ? _t("Sort by {0}", tx) : tx, true, false);
            if (showSort) {buf.append("</a>");}
        }
        buf.append("</th></tr></thead>\n<tbody>");
        if (!isTopLevel || hasCompleteAudio(fileList, storage, remainingArray)) { // don't show row if top level or no playlist
            buf.append("<tr id=dirNav><td colspan=").append(showPriority ? '3' : '2').append(" class=ParentDir>");
            if (!isTopLevel) { // don't show parent dir link if top level
                buf.append("<a href=\"");
                URIUtil.encodePath(buf, addPaths(decodedBase,"../"));
                buf.append("/").append(getQueryString(up)).append("\">");
                appendIcon(buf, _t("up"), "", "", true, true);
                buf.append(' ').append(_t("Parent directory")).append("</a>");
            }

            buf.append("</td><td colspan=2 class=\"ParentDir playlist\">");
            // playlist button
            if (hasCompleteAudio(fileList, storage, remainingArray)) {
                buf.append("<a href=\"").append(base).append("?playlist");
                if (sortParam != null && !"0".equals(sortParam) && !"1".equals(sortParam)) {
                    buf.append("&amp;sort=").append(sortParam);
                }
                buf.append("\">");
                appendIcon(buf, "playlist", "", _t("Audio Playlist"), false, true);
                buf.append(' ').append(_t("Audio Playlist")).append("</a>");
            }
            buf.append("</td></tr>\n");
        }

        //DateFormat dfmt=DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
        boolean showSaveButton = false;
        boolean rowEven = true;
        //boolean inOrder = storage != null && storage.getInOrder(); // disabled for now
        int videoCount = 0;
        int imgCount = 0;
        int txtCount = 0;
        for (Sorters.FileAndIndex fai : fileList) {
            /*
             * String encoded = encodePath(ls[i].getName());
             * bugfix for I2P - Backport from Jetty 6 (zero file lengths and last-modified times)
             * http: *jira.codehaus.org/browse/JETTY-361?page=com.atlassian.jira.plugin.system.issuetabpanels%3Achangehistory-tabpanel#issue-tabs
             * See resource.diff attachment
             * Resource item = addPath(encoded);
             */
            File item = fai.file;
            // Get completeness and status string
            boolean complete = false;
            String status = "";
            long length = item.length();
            int fileIndex = fai.index;
            int priority = 0;
            if (fai.isDirectory) {complete = true;}
            else {
                if (storage == null) {
                    complete = true;
                    StringBuilder ico = new StringBuilder();
                    appendIcon(ico, "warn", _t("Not found"), _t("Torrent not found"), false, true, true);
                    status = ico.toString();
                } else {
                    long remaining = fai.remaining;
                    if (remaining < 0) {
                        complete = true;
                        StringBuilder ico = new StringBuilder();
                        appendIcon(ico, "warn", _t("Unrecognized"), _t("File not found in torrent"), false, true, true);
                        status = ico.toString();
                    } else if (remaining == 0 || length <= 0) {
                        complete = true;
                        StringBuilder ico = new StringBuilder();
                        appendIcon(ico, "tick", _t("Complete"), _t("Complete"), false, true, true);
                        status = ico.toString();
                    } else {
                        priority = fai.priority;
                        StringBuilder ico = new StringBuilder();
                        ico.append("<div class=priorityIndicator>");
                        if (priority < 0) {
                            appendIcon(buf, "block", "", "", false, false, true);
                        } else if (priority == 0) {
                            appendIcon(ico, "clock", "", "", false, false, true);
                        } else {
                            appendIcon(ico, "clock_red", "", "", false, false, true);
                        }
                        ico.append("</div>");
                        long percent = 100 * (length - remaining) / length;
                        status = ico.toString() + buildProgressBar(length, remaining, true, false, false, true);
                    }
                }
            }

            String rowClass = (rowEven ? "rowEven" : "rowOdd");
            String completed = (complete ? "completed" : "incomplete");
            rowEven = !rowEven;
            buf.append("<tr class=\"").append(rowClass).append(' ').append(completed).append("\">");
            String path = addPaths(decodedBase, item.getName());
            if (fai.isDirectory) {
                complete = true;
                if (!path.endsWith("/")) {path=addPaths(path, "/");}
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
            // For binary files, strip charset if present
            if (isAudio || isImage || isVideo || mime.equals("application/pdf")) {
                int semicolonIndex = mime.indexOf(';');
                if (semicolonIndex != -1) {mime = mime.substring(0, semicolonIndex).trim();}
            }
            buf.append("<td class=\"fileIcon");
            if (!complete) {buf.append(" volatile");}
            else if (isText) {
              buf.append(" text");
              txtCount++;
            }
            buf.append("\">");
            String preview = null;
            if (isVideo && complete) {videoCount++;}
            if (complete || (isAudio && fai.preview > 100*1024) ||
                (isVideo && fai.preview > 5*1024*1024 && fai.preview / (double) fai.length >= 0.01d)) {
                String ppath = complete ? path : path + "?limit=" + fai.preview;
                if (!complete) {
                    double pct = fai.preview / (double) fai.length;
                    preview = " &nbsp;<span class=audioPreview>" + _t("Preview") + ": " +
                               (new DecimalFormat("0.00%")).format(pct) + "</span>";
                }
                if (isAudio || isVideo) {
                    // scale up image thumbnails if directory also contains audio/video
                    buf.append("\n<style>.thumb{max-height:inherit!important;max-width:240px!important}</style>\n");
                    // HTML5
                    if (isAudio) {buf.append("<audio");}
                    else {buf.append("<video");}
                    buf.append(" controls><source src=\"").append(ppath);
                    // display video 20 seconds in for a better chance of a thumbnail
                    if (isVideo) {buf.append("#t=20");}
                    buf.append("\" type=\"").append(mime).append("\">");
                }
                buf.append("<a href=\"").append(ppath).append("\">");
                if (mime.startsWith("image/") && !ppath.endsWith(".ico")) {
                    // thumbnail
                    buf.append("<img alt=\"\" border=0 class=thumb src=\"")
                       .append(ppath).append("\" data-lb data-lb-caption=\"")
                       .append(item.getName()).append("\" data-lb-group=allInDir></a>");
                   imgCount++;
                } else if (mime.startsWith("image/") && ppath.endsWith(".ico")) {
                    // favicon without scaling
                    buf.append("<img alt=\"\" width=16 height=16 class=favicon border=0 src=\"")
                       .append(ppath).append("\" data-lb data-lb-caption=\"")
                       .append(item.getName()).append("\" data-lb-group=allInDir></a>");
                } else {
                    appendIcon(buf, icon, _t("Open"), "", false, true);
                    buf.append("</a>");
                }
                if (isAudio) {buf.append("</audio>");}
                else if (isVideo) {buf.append("</video>");}
            } else {appendIcon(buf, icon, "", "", false, true);}
            buf.append("</td><td class=\"snarkFileName");
            if (!complete) {buf.append(" volatile");}
            buf.append("\">");
            if (complete) {
                buf.append("<a href=\"").append(path).append("\"");
                // send browser-viewable files to new tab to avoid potential display in iframe
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
            if (showPriority) {
                showSaveButton = true;
                appendPriority(buf, showPriority, complete, fai.isDirectory, priority, fileIndex);
            }
            buf.append("</tr>\n");
        }
        if (showSaveButton) {
            buf.append("</tbody>\n<thead><tr id=setPriority><th colspan=5><input type=submit class=accept value=\"")
               .append(_t("Save priorities"))
               .append("\" name=savepri>\n</th></tr></thead>\n");
        }
        buf.append("</table>\n</div>\n");
        //if (videoCount == 1) {buf.append("<script src=\"" + _resourcePath + "js/getMetadata.js?" + CoreVersion.VERSION + "\"></script>\n");}
        if (imgCount > 0) {buf.append("<script src=").append(_resourcePath).append("js/getImgDimensions.js></script>\n");}
        if (txtCount > 0) {buf.append("<script src=").append(_resourcePath).append("js/textView.js></script>\n");}
        buf.append("<script src=").append(_resourcePath).append("js/togglePriorities.js></script>\n");

// Comment section

        CommentSet comments = snark.getComments();
        if (er || ec) {
            buf.append("<div class=mainsection id=commentSection>\n<input hidden class=toggle_input id=toggle_comments type=checkbox");
            if (comments != null && !comments.isEmpty()) {buf.append(" checked");}
        }
        buf.append(">\n<label id=tab_comments class=toggleview for=toggle_comments><span class=tab_label>")
           .append(_t("Comments")).append("</span></label><hr>\n");
        displayComments(snark, er, ec, esc, buf);

        // for stop/start/check
        buf.append("</div>\n");
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
        // FIXME: if b64 appears in link, convert to b32 or domain name (if known)
        String postmanb64 = "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICkEbKUqJ9mPYQlTSujhNxiRIW-oLwMtvayCFci99oX8MvazPS7~97x0Gsm-onEK1Td9nBdmq30OqDxpRtXBimbzkLbR1IKObbg9HvrKs3L-kSyGwTUmHG9rSQSoZEvFMA-S0EXO~o4g21q1oikmxPMhkeVwQ22VHB0-LZJfmLr4SAAAA";
        String postmanb64_new = "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICrsRuil8qK~whOvj8uNTv~ohZnTZHxTLgi~sDyo98BwJ-4Y4NMSuF4GLzcgLypcR1D1WY2tDqMKRYFVyLE~MTPVjRRgXfcKolykQ666~Go~A~~CNV4qc~zlO6F4bsUhVZDU7WJ7mxCAwqaMiJsL-NgIkb~SMHNxIzaE~oy0agHJMBQAEAAcAAA==";
        String btb32 = "ev5dpxvcmshi6mil7gaon3b2wbplwylzraxs4wtz7dd5lzdsc2dq.b32.i2p";
        String chudob32 = "swhb5i7wcjcohmus3gbt3w6du6pmvl3isdvxvepuhdxxkfbzao6q.b32.i2p";
        String cryptb32 = "ri5a27ioqd4vkik72fawbcryglkmwyy4726uu5j3eg6zqh2jswfq.b32.i2p";
        String freedomb32 = "nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p";
        String icu812b32 = "h77hk3pr622mx5c6qmybvbtrdo5la7pxo6my4kzr47x2mlpnvm2a.b32.i2p";
        String lodikonb32 = "q2a7tqlyddbyhxhtuia4bmtqpohpp266wsnrkm6cgoahdqrjo3ra.b32.i2p";
        String lyokob32 = "afuuortfaqejkesne272krqvmafn65mhls6nvcwv3t7l2ic2p4kq.b32.i2p";
        String odiftb32 = "bikpeyxci4zuyy36eau5ycw665dplun4yxamn7vmsastejdqtfoq.b32.i2p";
        String omitrackb32 = "a5ruhsktpdhfk5w46i6yf6oqovgdlyzty7ku6t5yrrpf4qedznjq.b32.i2p";
        String otdgb32 = "w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p";
        String postmanb32_new = "6a4kxkg5wp33p25qqhgwl6sj4yh4xuf5b3p3qldwgclebchm3eea.b32.i2p";
        String r4sasb32 = "punzipidirfqspstvzpj6gb4tkuykqp6quurj6e23bgxcxhdoe7q.b32.i2p";
        String simpb32 = "wc4sciqgkceddn6twerzkfod6p2npm733p7z3zwsjfzhc4yulita.b32.i2p";
        String skankb32 = "by7luzwhx733fhc5ug2o75dcaunblq2ztlshzd7qvptaoa73nqua.b32.i2p";
        String theblandb32 = "s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p";
        String sigmab32 = "qimlze77z7w32lx2ntnwkuqslrzlsqy7774v3urueuarafyqik5a.b32.i2p";

        if (meta != null) {
            announce = meta.getAnnounce();
            if (announce == null) { announce = snark.getTrackerURL(); }
            announce = DataHelper.stripHTML(announce)
                .replace(postmanb64, "tracker2.postman.i2p")
                .replace(postmanb64_new, "tracker2.postman.i2p")
                .replace(postmanb32_new, "tracker2.postman.i2p")
                .replaceAll(btb32, "opentracker.bt.i2p")
                .replaceAll(chudob32, "tracker.chudo.i2p")
                .replaceAll(cryptb32, "tracker.crypthost.i2p")
                .replaceAll(freedomb32, "torrfreedom.i2p")
                .replaceAll(icu812b32, "tracker.icu812.i2p")
                .replaceAll(lodikonb32, "tracker.lodikon.i2p")
                .replaceAll(lyokob32, "lyoko.i2p")
                .replaceAll(odiftb32, "opendiftracker.i2p")
                .replaceAll(omitrackb32, "omitracker.i2p")
                .replaceAll(otdgb32, "opentracker.dg2.i2p")
                .replaceAll(r4sasb32, "opentracker.r4sas.i2p")
                .replaceAll(skankb32, "opentracker.skank.i2p")
                .replaceAll(simpb32, "opentracker.simp.i2p")
                .replaceAll(theblandb32, "tracker.thebland.i2p")
                .replaceAll(sigmab32, "sigmatracker.i2p");
        }
        if (meta != null && !meta.isPrivate()) {
            buf.append("<a class=magnetlink href=\"").append(MagnetURI.MAGNET_FULL).append(hex);
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

        buf.append("<a class=torrentlink href=\"").append(_contextPath).append('/')
           .append(baseName).append("\" title=\"")
           .append(DataHelper.escapeHTML(baseName).replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"))
           .append("\">");
        appendIcon(buf, "torrent", "", "", false, true, true);
        buf.append("</a></th></tr>\n");

        long dat = (meta != null) ? meta.getCreationDate() : 0;
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMMM yyyy", Locale.US);
        long[] dates = _manager.getSavedAddedAndCompleted(snark);

        buf.append("<tr id=torrentInfoStats>").append("<td colspan=3><span class=nowrap");
        if (dat > 0) {
            String date = fmt.format(new Date(dat));
            buf.append(" title=\"").append(_t("Created")).append(": ").append(date).append("\"");
        }
        buf.append(">");
        appendIcon(buf, "file", "", "", true, false);
        buf.append("<b>").append(_t("Size")).append(":</b> ").append(formatSize(snark.getTotalLength()));
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
            double ratio = uploaded / ((double) snark.getTotalLength());
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
                        buf.append(getShortTrackerLink(DataHelper.stripHTML(s)
                           .replaceAll(cryptb32, "tracker.crypthost.i2p")
                           .replaceAll(freedomb32, "torrfreedom.i2p")
                           .replaceAll(lodikonb32, "tracker.lodikon.i2p")
                           .replaceAll(otdgb32, "opentracker.dg2.i2p")
                           .replaceAll(odiftb32, "opendiftracker.i2p")
                           .replaceAll(theblandb32, "tracker.thebland.i2p"), snark.getInfoHash()));
                        buf.append("</span> ");
                    }
                }
                buf.append("<span class=info_tracker>");
                boolean more = false;
                for (String s : alist2) {
                    if (more) { buf.append("<span class=info_tracker>"); }
                    else { more = true; }
                    buf.append(getShortTrackerLink(DataHelper.stripHTML(s)
                       .replaceAll(cryptb32, "tracker.crypthost.i2p")
                       .replaceAll(freedomb32, "torrfreedom.i2p")
                       .replaceAll(lodikonb32, "tracker.lodikon.i2p")
                       .replaceAll(otdgb32, "opentracker.dg2.i2p")
                       .replaceAll(odiftb32, "opendiftracker.i2p")
                       .replaceAll(theblandb32, "tracker.thebland.i2p"), snark.getInfoHash()));
                    buf.append("</span> ");
                }
            }
            buf.append("</td></tr>\n");
        } else if (meta != null) {
            announce = meta.getAnnounce();
            if (announce == null) { announce = snark.getTrackerURL(); }
            if (announce != null) {
                announce = DataHelper.stripHTML(announce)
                   .replace(postmanb64, "tracker2.postman")
                   .replaceAll(cryptb32, "tracker.crypthost.i2p")
                   .replaceAll(freedomb32, "torrfreedom.i2p")
                   .replaceAll(lodikonb32, "tracker.lodikon.i2p")
                   .replaceAll(otdgb32, "opentracker.dg2.i2p")
                   .replaceAll(odiftb32, "opendiftracker.i2p")
                   .replaceAll(theblandb32, "tracker.thebland.i2p")
                   .replaceAll(icu812b32, "tracker.icu812.i2p")
                   .replaceAll(chudob32, "tracker.chudo.i2p");
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
            if (com != null && com.length() > 0) {
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
     * @since 0.9.46
     */
    private boolean isI2PTracker(String url) {
        try {
            URI uri = new URI(url);
            String method = uri.getScheme();
            if (!("http".equals(method) || (_manager.util().udpEnabled() && "udp".equals(method)))) {return false;}
            String host = uri.getHost();
            if (host == null || !host.endsWith(".i2p")) {return false;}
        } catch (URISyntaxException use) {return false;}
        return true;
    }

    /**
     * @param mime non-null
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
     * @param mime non-null
     * @since 0.9.44
     */
    private static boolean isVideo(String mime) {
        return mime.startsWith("video/") && !mime.equals("video/x-msvideo") && /*!mime.equals("video/x-matroska") &&*/
               !mime.equals("video/quicktime") && !mime.equals("video/x-flv");
    }

    /**
     * Is there at least one complete audio file in this directory or below?
     * Recursive.
     *
     * @since 0.9.44
     */
    private boolean hasCompleteAudio(List<Sorters.FileAndIndex> fileList, Storage storage, long[] remainingArray) {
        for (Sorters.FileAndIndex fai : fileList) {
            if (fai.isDirectory) {
                // recurse
                File[] ls = fai.file.listFiles();
                if (ls != null && ls.length > 0) {
                    List<Sorters.FileAndIndex> fl2 = new ArrayList<Sorters.FileAndIndex>(ls.length);
                    for (int i = 0; i < ls.length; i++) {fl2.add(new Sorters.FileAndIndex(ls[i], storage, remainingArray));}
                    if (hasCompleteAudio(fl2, storage, remainingArray)) {return true;}
                }
                continue;
            }
            if (fai.remaining != 0) {continue;}
            String name = fai.file.getName();
            String mime = getMimeType(name);
            if (mime != null && isAudio(mime)) {return true;}
        }
        return false;
    }

    /**
     * Get the audio files in the resource list as a m3u playlist.
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
        File sbase = storage.getBase();
        File r;
        if (pathInTorrent.equals("/")) {r = sbase;}
        else {r = new File(sbase, pathInTorrent);}
        if (!r.isDirectory()) {return null;}
        File[] ls = r.listFiles();
        if (ls == null) {return null;}
        List<Sorters.FileAndIndex> fileList = new ArrayList<Sorters.FileAndIndex>(ls.length);
        // precompute remaining for all files for efficiency
        long[] remainingArray = storage.remaining();
        for (int i = 0; i < ls.length; i++) {fileList.add(new Sorters.FileAndIndex(ls[i], storage, remainingArray));}

        boolean showSort = fileList.size() > 1;
        int sort = 0;
        if (showSort) {
            if (sortParam != null) {
                try {sort = Integer.parseInt(sortParam);}
                catch (NumberFormatException nfe) {}
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
                File[] ls = fai.file.listFiles();
                if (ls != null && ls.length > 0) {
                    List<Sorters.FileAndIndex> fl2 = new ArrayList<Sorters.FileAndIndex>(ls.length);
                    for (int i = 0; i < ls.length; i++) {fl2.add(new Sorters.FileAndIndex(ls[i], storage, remainingArray));}
                    if (ls.length > 1) {DataHelper.sort(fl2, Sorters.getFileComparator(sort, this));}
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
     * @param er ratings enabled globally
     * @param ec comments enabled globally
     * @param esc comments enabled this torrent
     * @since 0.9.31
     */
    private void displayComments(Snark snark, boolean er, boolean ec, boolean esc, StringBuilder buf) {
        Iterator<Comment> iter = null;
        int myRating = 0;
        CommentSet comments = snark.getComments();
        boolean canRate = esc && _manager.util().getCommentsName().length() > 0;

        buf.append("<table id=commentInfo>\n<tr><th colspan=3>")
           .append(_t("Ratings and Comments").replace("and", "&amp;"))
           .append("&nbsp;&nbsp;&nbsp;");
        if (esc && !canRate) {
            buf.append("<span id=nameRequired>")
               .append(_t("Author name required to rate or comment"))
               .append("&nbsp;&nbsp;<a href=\"").append(_contextPath).append("/configure#configureAuthor\">[")
               .append(_t("Configure"))
               .append("]</a></span>");
        } else if (esc) {
            buf.append("<span id=nameRequired><span class=commentAuthorName title=\"")
               .append(_t("Your author name for published comments and ratings"))
               .append("\">")
               .append(DataHelper.escapeHTML(_manager.util().getCommentsName()))
               .append("</span></span>");
        }
        buf.append("</th></tr>\n");

        // new rating / comment form
        if (canRate) {
            buf.append("<tr id=newRating>\n");
            if (er) {
                buf.append("<td>\n<select name=myRating>\n");
                for (int i = 5; i >= 0; i--) {
                    buf.append("<option value=\"").append(i).append("\"");
                    if (i == myRating) {buf.append(" selected");}
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
        if (comments != null) {
            synchronized(comments) {
                // current rating
                if (er) {
                    buf.append("<tr id=myRating><td>");
                    myRating = comments.getMyRating();
                    if (myRating > 0) {
                        buf.append(_t("My Rating")).append(":</td><td colspan=2 class=commentRating>");
                        for (int i = 0; i < myRating; i++) {
                            StringBuilder iconBuf = new StringBuilder();
                            appendIcon(iconBuf, "rateme", "★", "", false, true);
                            buf.append(iconBuf.toString());
                        }
                    }
                    buf.append("</td></tr>");
                }
                if (er) {
                    buf.append("<tr id=showRatings><td>");
                    int rcnt = comments.getRatingCount();
                    if (rcnt > 0) {
                        double avg = comments.getAverageRating();
                        buf.append(_t("Average Rating"))
                           .append(":</td><td colspan=2>")
                           .append((new DecimalFormat("0.0")).format(avg));
                    } else {
                        buf.append(_t("Average Rating")).append(":</td><td colspan=2>");
                        buf.append(_t("No community ratings currently available"));
                    }
                    buf.append("</td></tr>\n");
                }
                if (ec) {
                    int sz = comments.size();
                    if (sz > 0) {iter = comments.iterator();}
                }
            }
        }

        buf.append("</table>\n");
        int ccount = 0;
        if (iter != null) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
            buf.append("<table id=userComments>\n");
            while (iter.hasNext()) {
                Comment c = iter.next();
                buf.append("<tr><td class=commentAuthor>");
                if (c.getName() != null) {
                    buf.append("<span class=commentAuthorName title=\"" + DataHelper.escapeHTML(c.getName()) + "\">")
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
     *  @param so null ok
     *  @return query string or ""
     *  @since 0.9.16
     */
    private static String getQueryString(String so) {
        if (so != null && !so.equals("")) {return "?sort=" + DataHelper.stripHTML(so);}
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

        for (java.util.Map.Entry<String, String> entry : IconMaps.SUFFIX_ICON_MAP.entrySet()) {
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
        static final java.util.Map<String, String> MIME_ICON_MAP;
        static final java.util.Map<String, String> SUFFIX_ICON_MAP;

        static {
            java.util.Map<String, String> mimeMap = new java.util.HashMap<>();
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
            MIME_ICON_MAP = java.util.Collections.unmodifiableMap(mimeMap);

            java.util.Map<String, String> suffixMap = new java.util.HashMap<>();
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
            SUFFIX_ICON_MAP = java.util.Collections.unmodifiableMap(suffixMap);
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
    private void appendIcon(StringBuilder buf, String name, String alt, String title, boolean fromTheme, boolean isSvg, boolean addDimensions) {
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
    private void appendIcon(StringBuilder buf, String name, String alt, String title, boolean fromTheme, boolean isSvg) {
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

    /** @since 0.8.1 */
    private void savePriorities(Snark snark, Map<String, String[]> postParams) {
        Storage storage = snark.getStorage();
        if (storage == null) {return;}
        for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("pri_")) {
                try {
                    int fileIndex = Integer.parseInt(key.substring(4));
                    String val = entry.getValue()[0]; // jetty arrays
                    int pri = Integer.parseInt(val);
                    storage.setPriority(fileIndex, pri);
                    _manager.addMessage(_t("File downloading priorities updated for torrent ") + storage.getBaseName());
                } catch (Throwable t) {t.printStackTrace();}
            }
        }
        //if (postParams.get("setInOrderEnabled") != null) {
        //    storage.setInOrder(postParams.get("enableInOrder") != null);
        //}
        snark.updatePiecePriorities();
        _manager.saveTorrentStatus(snark);
    }

    /** @since 0.9.31 */
    private void saveComments(Snark snark, Map<String, String[]> postParams) {
        String[] a = postParams.get("myRating");
        String r = (a != null) ? a[0] : null;
        a = postParams.get("nofilter_newComment");
        String c = (a != null) ? a[0] : null;
        if ((r == null || r.equals("0")) && (c == null || c.length() == 0)) {return;}
        int rat = 0;
        try {rat = Integer.parseInt(r);}
        catch (NumberFormatException nfe) {}
        Comment com = new Comment(c, _manager.util().getCommentsName(), rat);
        boolean changed = snark.addComments(Collections.singletonList(com));
        if (!changed) {_log.warn("Add of comment ID " + com.getID() + " failed");}
    }

    /** @since 0.9.31 */
    private void deleteComments(Snark snark, Map<String, String[]> postParams) {
        CommentSet cs = snark.getComments();
        if (cs == null) {return;}
        synchronized(cs) {
            for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("cdelete.")) {
                    try {
                        int id = Integer.parseInt(key.substring(8));
                        boolean changed = cs.remove(id);
                        if (!changed) {_log.warn("Delete of comment ID " + id + " failed");}
                    } catch (NumberFormatException nfe) {}
                }
            }
        }
    }

    /** @since 0.9.31 */
    private void saveCommentsSetting(Snark snark, Map<String, String[]> postParams) {
        boolean yes = postParams.get("enableComments") != null;
        _manager.setSavedCommentsEnabled(snark, yes);
    }

    /**
     * @param snark non-null
     * @since 0.9.53
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
        Set<String> annlist = new TreeSet<String>();
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
        else if (com.length() > 0) {com = DataHelper.escapeHTML(com);}
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
        List<Integer> toAdd = new ArrayList<Integer>();
        List<Integer> toDel = new ArrayList<Integer>();
        Integer primary = null;
        String newComment = "";
        String newCreatedBy = "";
        for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue()[0];   // jetty arrays
            if (key.startsWith("addTracker-")) {
                try {toAdd.add(Integer.parseInt(key.substring(11)));}
                catch (NumberFormatException nfe) {}
            } else if (key.startsWith("removeTracker-")) {
                try {toDel.add(Integer.parseInt(key.substring(14)));}
                catch (NumberFormatException nfe) {}
            } else if (key.equals("primary")) {
                try {primary = Integer.parseInt(val);}
                catch (NumberFormatException nfe) {}
            } else if (key.equals("nofilter_newTorrentComment")) {
                newComment = val.trim();
            } else if (key.equals("nofilter_newTorrentCreatedBy")) {
                newCreatedBy = val.trim();
            }
        }
        MetaInfo meta = snark.getMetaInfo();
        if (meta == null) {
            _manager.addMessage("Can't edit magnet"); // shouldn't happen
            return;
        }
        String oldPrimary = meta.getAnnounce();
        String oldComment = meta.getComment();
        if (oldComment == null) {oldComment = "";}
        String oldCreatedBy = meta.getCreatedBy();
        if (oldCreatedBy == null) {oldCreatedBy = "";}
        if (toAdd.isEmpty() && toDel.isEmpty() &&
            (primary == null || String.valueOf(primary).equals(oldPrimary)) &&
            oldComment.equals(newComment) && oldCreatedBy.equals(newCreatedBy)) {
            _manager.addMessage("No changes to torrent, not saved");
            return;
        }
        List<List<String>> alist = meta.getAnnounceList();
        Set<String> annlist = new TreeSet<String>();
        if (alist != null && !alist.isEmpty()) {
            for (List<String> alist2 : alist) { // strip non-i2p trackers
                for (String s : alist2) {
                    if (isI2PTracker(s)) {annlist.add(s);}
                }
            }
        }
        if (oldPrimary != null) {annlist.add(oldPrimary);}
        List<Tracker> newTrackers = _manager.getSortedTrackers();
        for (Integer i : toDel) {
            int hc = i.intValue();
            for (Iterator<String> iter = annlist.iterator(); iter.hasNext(); ) {
                String s = iter.next();
                if (s.hashCode() == hc) {iter.remove();}
            }
        }
        for (Integer i : toAdd) {
            int hc = i.intValue();
            for (Tracker t : newTrackers) {
                if (t.announceURL.hashCode() == hc) {
                    annlist.add(t.announceURL);
                    break;
                }
            }
        }
        String thePrimary = oldPrimary;
        if (primary != null) {
            int hc = primary.intValue();
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
            List<String> aalist = new ArrayList<String>(annlist);
            newAnnList = Collections.singletonList(aalist);
            if (!aalist.contains(thePrimary)) {thePrimary = aalist.get(0);}
        }
        if (newComment.equals("")) {newComment = null;}
        newCreatedBy = null;
        MetaInfo newMeta = new MetaInfo(meta, thePrimary, newAnnList, newComment, newCreatedBy, meta.getWebSeedURLs());
        File f = new File(_manager.util().getTempDir(), "edit-" + _manager.util().getContext().random().nextLong() + ".torrent");
        OutputStream out = null;
        try {
            out = _manager.areFilesPublic() ? new FileOutputStream(f) : new SecureFileOutputStream(f);
            out.write(newMeta.getTorrentData());
            out.close();
            boolean ok = FileUtil.rename(f, new File(snark.getName()));
            if (!ok) {
                _manager.addMessage("Save edit changes failed");
                return;
            }
        } catch (IOException ioe) {
            try {if (out != null) out.close();}
            catch (IOException ioe2) {}
            _manager.addMessage("Save edit changes failed: " + ioe.getMessage());
            return;
        } finally {f.delete();}
        snark.replaceMetaInfo(newMeta);
        _manager.addMessage("Torrent changes saved");
    }

    /** @since 0.9.32 */
    private static boolean noCollapsePanels(HttpServletRequest req) {
        // check for user agents that can't toggle the collapsible panels...
        // TODO: QupZilla supports panel collapse as of circa v2.1.2, so disable conditionally
        // TODO: Konqueror supports panel collapse as of circa v5 (5.34), so disable conditionally
        String ua = req.getHeader("user-agent");
        return ua != null && (ua.contains("Konq") || ua.contains("konq") ||
                              ua.contains("QupZilla") || ua.contains("Dillo") ||
                              ua.contains("Netsurf") || ua.contains("Midori"));
    }

    /**
     *  Is "a" equal to "b", or is "a" a directory and a parent
     *  of file or directory "b", canonically speaking?
     *  @since 0.9.15
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
     *  Are we running in standalone mode?
     *  @since 0.9.54+
     */
    private boolean isStandalone() {
        if (_context.isRouterContext()) {return false;}
        else {return true;}
    }

}