package org.klomp.snark.web;

import java.io.File;
import java.io.FileInputStream;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
import org.klomp.snark.dht.DHT;
import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;

import java.util.regex.*;

/**
 *  Refactored to eliminate Jetty dependencies.
 */
public class I2PSnarkServlet extends BasicServlet {

    private static final long serialVersionUID = 1L;
    /** generally "/i2psnark" */
    private String _contextPath;
    /** generally "i2psnark" */
    private String _contextName;
    private transient SnarkManager _manager;
    private long _nonce;
    private String _themePath;
    private String _imgPath;
    private String _lastAnnounceURL;

    private static final String DEFAULT_NAME = "i2psnark";
    public static final String PROP_CONFIG_FILE = "i2psnark.configFile";
    private static final String WARBASE = "/.res/";
    private static final char HELLIP = '\u2026';
    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final String RC_PROP_ENABLE_SORA_FONT = "routerconsole.displayFontSora";
    private int searchResults;
    private boolean debug = false;

    String cspNonce = Integer.toHexString(_context.random().nextInt());
    public I2PSnarkServlet() {super();}

    @Override
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        String cpath = getServletContext().getContextPath();
        _contextPath = cpath == "" ? "/" : cpath;
        _contextName = cpath == "" ? DEFAULT_NAME : cpath.substring(1).replace("/", "_");
        _nonce = _context.random().nextLong();
        // limited protection against overwriting other config files or directories
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
        if (pathInContext == null || pathInContext.equals("/") || pathInContext.equals("/index.jsp") ||
            !pathInContext.startsWith("/") || pathInContext.length() == 0 ||
            pathInContext.equals("/index.html") || pathInContext.startsWith(WARBASE))
            return super.getResource(pathInContext);
        // files in the i2psnark/ directory - get top level
        pathInContext = pathInContext.substring(1);
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
        return _context.getBooleanProperty(RC_PROP_ENABLE_SORA_FONT) || !_context.isRouterContext();
    }

    /**
     * Handle what we can here, calling super.doGet() or super.doPost() for the rest.
     *
     * Some parts modified from:
     * <pre>
      // ========================================================================
      // $Id: Default.java,v 1.51 2006/10/08 14:13:18 gregwilkins Exp $
      // Copyright 199-2004 Mort Bay Consulting Pty. Ltd.
      // ------------------------------------------------------------------------
      // Licensed under the Apache License, Version 2.0 (the "License");
      // you may not use this file except in compliance with the License.
      // You may obtain a copy of the License at
      // http://www.apache.org/licenses/LICENSE-2.0
      // Unless required by applicable law or agreed to in writing, software
      // distributed under the License is distributed on an "AS IS" BASIS,
      // WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      // See the License for the specific language governing permissions and
      // limitations under the License.
      // ========================================================================
     * </pre>
     *
     */
    private void doGetAndPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String method = req.getMethod(); // since we are not overriding handle*(), do this here
        String path = req.getServletPath(); // this is the part after /i2psnark

        // in-war icons etc.
        if (path != null && path.startsWith(WARBASE)) {
            if (method.equals("GET") || method.equals("HEAD")) {super.doGet(req, resp);}
            else {resp.sendError(405);} // no POST either
            return;
        }

        if (_context.isRouterContext()) {_themePath = "/themes/snark/" + _manager.getTheme() + '/';}
        else {_themePath = _contextPath + WARBASE + "themes/snark/" + _manager.getTheme() + '/';}
        _imgPath = _themePath + "images/";
        req.setCharacterEncoding("UTF-8");

        String pOverride = _manager.util().connected() ? null : "";
        String peerString = getQueryString(req, pOverride, null, null, "");
        String resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
        String jsPfx = _context.isRouterContext() ? "" : ".res";

        // AJAX for mainsection
        if ("/.ajax/xhr1.html".equals(path)) {
            setXHRHeaders(resp, cspNonce, false);
            PrintWriter out = resp.getWriter();
            out.write("<!DOCTYPE HTML>\n<html>\n<body id=snarkxhr>\n<div id=mainsection>\n");
            boolean canWrite;
            synchronized(this) {canWrite = _resourceBase.canWrite();}
            writeTorrents(out, req, canWrite);
            out.write("\n</div>\n</body>\n</html>\n");
            out.flush();
            return;
        }

        // AJAX for screenlog
        if ("/.ajax/xhrscreenlog.html".equals(path)) {
            setXHRHeaders(resp, cspNonce, false);
            PrintWriter out = resp.getWriter();
            out.write("<!DOCTYPE HTML>\n<html>\n<body id=snarkxhrlogs>\n");
            writeMessages(out, false, peerString);
            boolean canWrite;
            synchronized(this) {canWrite = _resourceBase.canWrite();}
            out.write("</body>\n</html>\n");
            out.flush();
            return;
        }

        boolean isConfigure = "/configure".equals(path);
        // index.jsp doesn't work, it is grabbed by the war handler before here
        if (!(path == null || path.equals("/") || path.equals("/index.jsp") ||
              path.equals("/index.html") || path.equals("/_post") || isConfigure)) {
            if (path.endsWith("/")) {
                // Listing of a torrent (torrent detail page)
                // bypass the horrid Resource.getListHTML()
                String pathInfo = req.getPathInfo();
                String pathInContext = addPaths(path, pathInfo);
                File resource = getResource(pathInContext);
                if (resource == null) {resp.sendError(404);}
                else if (req.getParameter("playlist") != null) {
                    String base = addPaths(req.getRequestURI(), "/");
                    String listing = getPlaylist(req.getRequestURL().toString(), base, req.getParameter("sort"));
                    if (listing != null) {
                        setHTMLHeaders(resp, cspNonce, false);
                        // TODO custom name
                        resp.setContentType("audio/mpegurl; charset=UTF-8; name=\"playlist.m3u\"");
                        resp.addHeader("Content-Disposition", "attachment; filename=\"playlist.m3u\"");
                        resp.getWriter().write(listing);
                    } else {resp.sendError(404);}
                } else {
                    String base = addPaths(req.getRequestURI(), "/");
                    String listing = getListHTML(resource, base, true, method.equals("POST") ? req.getParameterMap() : null,
                                                 req.getParameter("sort"));
                    if (method.equals("POST")) {
                        // P-R-G
                        sendRedirect(req, resp, "");
                    } else if (listing != null) {
                        setHTMLHeaders(resp, cspNonce, true);
                        resp.getWriter().write(listing);
                    } else {resp.sendError(404);} // shouldn't happen
                }
            } else {
                // local completed files in torrent directories
                if (method.equals("GET") || method.equals("HEAD")) {super.doGet(req, resp);}
                else if (method.equals("POST")) {super.doPost(req, resp);}
                else {resp.sendError(405);}
            }
            return;
        }

        // Either the main page or /configure

        String nonce = req.getParameter("nonce");
        if (nonce != null) {
            if (nonce.equals(String.valueOf(_nonce))) {processRequest(req);}
            else {_manager.addMessage("Please retry form submission (bad nonce)");} // nonce is constant, shouldn't happen
            // P-R-G (or G-R-G to hide the params from the address bar)
            sendRedirect(req, resp, peerString);
            return;
        }

        boolean noCollapse = noCollapsePanels(req);
        boolean collapsePanels = _manager.util().collapsePanels();
        setHTMLHeaders(resp, cspNonce, false);
        PrintWriter out = resp.getWriter();
        StringBuilder buf = new StringBuilder(4*1024);
        String theme = _manager.getTheme();
        String pageBackground = "#fff";
        if (theme.equals("dark")) {pageBackground = "#000";}
        else if (theme.equals("midnight")) {
            pageBackground = "repeating-linear-gradient(180deg,rgba(0,0,24,.75) 2px,rgba(0,0,0,.7) 4px)/100% 4px," +
                             "var(--tile)/171px 148px,var(--offline)/0,#000010";
        } else if (theme.equals("ubergine")) {
            pageBackground = "repeating-linear-gradient(90deg,rgba(0,0,0,.5) 2px,rgba(48,0,48,.5) 4px)," +
                             "repeating-linear-gradient(180deg,#080008 2px,#212 4px),var(--offline) no-repeat,#130313";
        } else if (theme.equals("vanilla")) {
            pageBackground = "repeating-linear-gradient(180deg,#6f5b4c 1px,#a9927e 1px,#bfa388 4px),#cab39b";
        }
        buf.append(DOCTYPE).append("<html style=\"background:").append(pageBackground).append("\">\n")
           .append("<head>\n").append("<meta charset=utf-8>\n");
        if (!isStandalone()) {
            buf.append("<script src=\"/js/iframeResizer/iframeResizer.contentWindow.js?").append(CoreVersion.VERSION).append("\" id=iframeResizer></script>\n")
               .append("<script src=\"/js/iframeResizer/updatedEvent.js?").append(CoreVersion.VERSION).append("\"></script>\n");
        }
        buf.append("<meta name=viewport content=\"width=device-width\">\n");
        if (!isStandalone() && useSoraFont()) {
            buf.append("<link rel=preload href=/themes/fonts/Sora.css as=style>\n")
               .append("<link rel=preload href=/themes/fonts/Sora/Sora.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=preload href=/themes/fonts/Sora/Sora-Italic.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=stylesheet href=/themes/fonts/Sora.css>\n");
        } else if (!isStandalone()) {
            buf.append("<link rel=preload href=/themes/fonts/DroidSans.css as=style>\n")
               .append("<link rel=preload href=/themes/fonts/DroidSans/DroidSans.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=preload href=/themes/fonts/DroidSans/DroidSans-Bold.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=stylesheet href=/themes/fonts/DroidSans.css>\n");
        } else {
            buf.append("<link rel=preload href=/i2psnark/.res/themes/fonts/Sora.css as=style>\n")
               .append("<link rel=preload href=/i2psnark/.res/themes/fonts/Sora/Sora.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=preload href=/i2psnark/.res/themes/fonts/Sora/Sora-Italic.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=stylesheet href=/i2psnark/.res/themes/fonts/Sora.css>\n");
        }
        buf.append("<link rel=preload href=\"").append(_themePath).append("snark.css?").append(CoreVersion.VERSION).append("\" as=style>\n")
           .append("<link rel=preload href=\"").append(_themePath).append("images/images.css?").append(CoreVersion.VERSION).append("\" as=style>\n")
           .append("<link rel=\"shortcut icon\" href=\"").append(_contextPath).append(WARBASE).append("icons/favicon.svg\">\n");
        buf.append("<title>");
        if (_contextName.equals(DEFAULT_NAME)) {buf.append(_t("I2PSnark"));}
        else {buf.append(_contextName);}
        buf.append(" - ");
        if (isConfigure) {buf.append(_t("Configuration"));}
        else {
            String peerParam = req.getParameter("p");
            if ("2".equals(peerParam)) {buf.append(_t("Debug Mode"));}
            else {buf.append(_t("Anonymous BitTorrent Client"));}
        }
        buf.append("</title>\n");

        int delay = _manager.getRefreshDelaySeconds();
        String pageSize = String.valueOf(_manager.getPageSize());
        if (!isConfigure) {
            buf.append("<script nonce=").append(cspNonce).append(">\n")
               .append("  const deleteMsg = \"")
               .append(_t("Are you sure you want to delete {0} and all downloaded data?")).append("\";\n")
               .append("  const postDeleteMsg = \"")
               .append(_t("Deleting <b>{0}</b> and all associated data...")).append("\";\n")
               .append("  const removeMsg = \"")
               .append(_t("Are you sure you want to delete torrent file {0} and associated metadata?")).append("\";\n")
               .append("  const removeMsg2 = \"")
               .append(_t("Note: Downloaded data will not be deleted.")).append("\";\n")
               .append("  const postRemoveMsg = \"")
               .append(_t("Deleting {0} and associated metadata only...")).append("\";\n")
               .append("  const snarkPageSize = ").append(pageSize).append(";\n")
               .append("  const snarkRefreshDelay = ").append(delay).append(";\n")
               .append("  const totalSnarks = ").append(_manager.listTorrentFiles().size()).append(";\n")
               .append("  window.snarkPageSize = snarkPageSize;\n")
               .append("  window.snarkRefreshDelay = snarkRefreshDelay;\n")
               .append("  window.totalSnarks = totalSnarks;\n</script>\n");
            if (!isStandalone()) {
                buf.append("<script src=\"").append(resourcePath).append("js/tunnelCounter.js?").append(CoreVersion.VERSION)
                   .append("\" type=module></script>\n");
            }
            buf.append("<script nonce=").append(cspNonce).append(" type=module>\n")
               .append("  import {initSnarkRefresh} from \"").append(resourcePath).append("js/refreshTorrents.js").append("\";\n")
               .append("  document.addEventListener(\"DOMContentLoaded\", initSnarkRefresh);\n</script>\n")
               .append("<script src=\"").append(resourcePath).append("js/confirm.js?").append(CoreVersion.VERSION).append("\"></script>\n")
               .append("<script src=").append(resourcePath).append("js/snarkAlert.js type=module></script>\n")
               .append("<link rel=stylesheet href=").append(resourcePath).append("snarkAlert.css>\n");

            if (delay > 0) {
                String downMsg = _context.isRouterContext() ? _t("Router is down") : _t("I2PSnark has stopped");
                // fallback to metarefresh when javascript is disabled
                buf.append("<noscript><meta http-equiv=refresh content=\"").append(delay < 60 ? 60 : delay).append(";")
                   .append(_contextPath).append("/").append(peerString).append("\"></noscript>\n");
            }
        } else {delay = 0;}

        // selected theme inserted here
        buf.append(HEADER_A).append(_themePath).append(HEADER_B).append("\n");
        buf.append(HEADER_A).append(_themePath).append(HEADER_I).append("\n"); // load css image assets
        String slash = String.valueOf(java.io.File.separatorChar);
        String themeBase = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() + slash + "docs" + slash + "themes" +
                           slash + "snark" + slash + _manager.getTheme() + slash;
        File override = new File(themeBase + "override.css");
        int rnd = _context.random().nextInt(3);
        if (!isStandalone() && rnd == 0 && _manager.getTheme().equals("light")) {
            buf.append("<style>#screenlog{background:url(/themes/snark/light/images/k2.webp) no-repeat right bottom,")
               .append("repeating-linear-gradient(180deg,rgba(255,255,255,.5) 2px,rgba(220,220,255,.5) 4px),var(--snarkGraph) no-repeat,")
               .append("var(--th);background-size:72px auto,100%,calc(100% - 80px) calc(100% - 4px),100%;background-position:right bottom,")
               .append("center center,left bottom,center center;background-blend-mode:multiply,overlay,luminosity,normal}</style>\n");
        }
        if (!isStandalone() && override.exists()) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_Z).append("\n"); // optional override.css for version-persistent user edits
        }

        // larger fonts for cjk translations
        String lang = (Translate.getLanguage(_manager.util().getContext()));
        long now = _context.clock().now();
        if (lang.equals("zh") || lang.equals("ja") || lang.equals("ko")) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_D).append("\n");
        }

        //  ...and inject CSS to display panels uncollapsed
        if (noCollapse || !collapsePanels) {buf.append(HEADER_A).append(_themePath).append(HEADER_C).append("\n");}
        // add placeholders for filterbar, toggleLog css
        buf.append("<style id=cssfilter></style>\n").append("<style id=toggleLogCss></style>\n");

        if (!isStandalone() && delay <= 0) {
            buf.append("<style id=graphcss>:root{--snarkGraph:url('/viewstat.jsp")
               .append("?stat=[I2PSnark] InBps&showEvents=false&period=60000&periodCount=1440&end=0&width=2000&height=160")
               .append("&hideLegend=true&hideTitle=true&hideGrid=true&t=").append(now).append("\')}\"</style>\n");
        }
        buf.append("<script src=" + resourcePath + "js/click.js></script>\n")
           .append("</head>\n<body style=display:none;pointer-events:none id=snarkxhr class=\"").append(_manager.getTheme())
           .append(" lang_").append(lang).append("\">\n")
           .append("<span id=toast hidden></span>\n").append("<center>\n").append(IFRAME_FORM);
        List<Tracker> sortedTrackers = null;
        List<TorrentCreateFilter> sortedFilters = null;
        buf.append("<div id=navbar>\n");
        if (isConfigure) {
            buf.append("<a href=\"").append(_contextPath).append("/\" title=\"").append(_t("Torrents"))
               .append("\" class=\"snarkNav nav_main\">");
            if (_contextName.equals(DEFAULT_NAME)) {buf.append(_t("I2PSnark"));}
            else {buf.append(_contextName);}
            buf.append("</a>\n");
        } else {
            buf.append("<a href=\"").append(_contextPath).append('/').append(peerString)
               .append("\" title=\"").append(_t("Refresh page")).append("\" class=\"snarkNav nav_main\">");
            if (_contextName.equals(DEFAULT_NAME)) {buf.append(_t("I2PSnark"));}
            else {buf.append(_contextName);}
            buf.append("</a>\n");
            //buf.append("<a href=\"").append(_contextPath).append("/configure")
            //   .append("\" title=\"").append(_t("Configuration")).append("\" class=\"snarkNav nav_config\">")
            //   .append(_t("Settings")).append("</a>");
            sortedTrackers = _manager.getSortedTrackers();
            sortedFilters = _manager.getSortedTorrentCreateFilterStrings();
            buf.append("<a href=\"http://discuss.i2p/\" class=\"snarkNav nav_forum\" target=_blank title=\"")
               .append(_t("Torrent &amp; filesharing forum")).append("\">").append(_t("Forum")).append("</a>");
            for (Tracker t : sortedTrackers) {
                if (t.baseURL == null || !t.baseURL.startsWith("http")) {continue;}
                if (_manager.util().isKnownOpenTracker(t.announceURL)) {continue;}
                buf.append("\n<a href=\"").append(t.baseURL).append("\" class=\"snarkNav nav_tracker\" target=_blank>").append(t.name).append("</a>");
            }
            buf.append("\n<a href=\"http://btdigg.i2p/\" class=\"snarkNav nav_search\" target=_blank title=\"")
               .append(_t("I2P-based search engine for clearnet-hosted torrents")).append("\">").append(_t("BTDigg")).append("</a>");
        }
        if (_manager.getTorrents().size() > 1) {
            String s = req.getParameter("search");
            boolean searchActive = (s != null && !s.equals(""));
            buf.append("<form id=snarkSearch action=\"").append(_contextPath).append("\" method=GET hidden>\n")
               .append("<span id=searchwrap><input id=searchInput type=search required name=search size=20 placeholder=\"")
               .append(_t("Search torrents"))
               .append("\"");
            if (searchActive) {
                buf.append(" value=\"").append(DataHelper.escapeHTML(s).trim()).append('"');
            }
            buf.append("><a href=").append(_contextPath).append(" title=\"").append(_t("Clear search"))
               .append("\" hidden>x</a></span><input type=submit value=\"Search\">\n").append("</form>\n");
            //buf.append("<script src=\"" + resourcePath + "js/search.js?" + CoreVersion.VERSION + "\"></script>");
        }
        buf.append("</div>\n");
        String newURL = req.getParameter("newURL");
        if (newURL != null && newURL.trim().length() > 0 && req.getMethod().equals("GET"))
            _manager.addMessage(_t("Click \"Add torrent\" button to fetch torrent"));
        buf.append("<div id=page>\n<div id=mainsection class=mainsection>\n");
        out.write(buf.toString());
        buf.setLength(0);
        out.flush();

        writeMessages(out, isConfigure, peerString);

        if (isConfigure) {
            // end of mainsection div
            out.write("<div class=logshim></div>\n</div>\n");
            writeConfigForm(out, req);
            writeTorrentCreateFilterForm(out, req);
            writeTrackerForm(out, req);

        } else {
            boolean canWrite;
            synchronized(this) {canWrite = _resourceBase.canWrite();}
            boolean pageOne = writeTorrents(out, req, canWrite);
            boolean enableAddCreate = _manager.util().enableAddCreate();
            // end of mainsection div
            if (pageOne || enableAddCreate) {
                out.write("</div>\n<div id=lowersection>\n");
                if (canWrite) {
                    writeAddForm(out, req);
                    writeSeedForm(out, req, sortedTrackers, sortedFilters);
                }
                writeConfigLink(out);
                // end of lowersection div
            } else {
                // end of mainsection div
                out.write("</div>\n<div id=lowersection>\n");
                writeConfigLink(out);
                // end of lowersection div
            }
            out.write("</div>\n");
        }
        if (!isConfigure) {
            out.write("<script src=" + resourcePath + "js/toggleLinks.js type=module></script>\n");
        }
        out.write("<script src=" + resourcePath + "js/setFilterQuery.js></script>\n");
        if (!isStandalone()) {out.write(FOOTER);}
        else {out.write(FOOTER_STANDALONE);}
        out.flush();
    }

    /**
     *  The standard HTTP headers for all HTML pages
     *
     *  @since 0.9.16 moved from doGetAndPost()
     */

    private void setHTMLHeaders(HttpServletResponse resp, String cspNonce, boolean allowMedia) {
        StringBuilder headers = new StringBuilder(2048);
        // use \t: for header key termination so we don't split the values with colons e.g. data:, blob:
        headers.append("Accept-Ranges\t: bytes\r\n");
        String mimeType = resp.getContentType();
        if (mimeType != null && (mimeType.equals("image/png") || mimeType.equals("image/jpeg") || mimeType.equals("font/woff2") ||
            mimeType.equals("image/gif") || mimeType.equals("image/webp") || mimeType.equals("image/svg+xml") ||
            mimeType.equals("text/css") || mimeType.endsWith("/javascript"))) {
            headers.append("Cache-Control\t: private, max-age=2628000, immutable\r\n");
        } else {headers.append("Cache-Control\t: private, no-cache, max-age=2628000\r\n");}
        StringBuilder csp = new StringBuilder("default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; ");
        csp.append("script-src 'self' 'nonce-").append(cspNonce).append("'; ");
        csp.append("object-src 'none'; media-src '").append(allowMedia ? "self" : "none").append("'");
        headers.append("Content-Security-Policy\t: ").append(csp).append("\r\n");
        headers.append("Permissions-Policy\t: fullscreen=(self)\r\n");
        headers.append("Referrer-Policy\t: same-origin\r\n");
        headers.append("X-Content-Type-Options\t: nosniff\r\n");
        headers.append("X-XSS-Protection\t: 1; mode=block\r\n");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=utf-8");
        String[] headerLines = headers.toString().split("\r\n");
        for (String line : headerLines) {
            String[] headerParts = line.split("\t:");
            String headerName = headerParts[0].trim();
            String headerValue = headerParts[1].trim().replace('\t', ':');
            resp.setHeader(headerName, headerValue);
        }
        headers.setLength(0);
    }

    private void setXHRHeaders(HttpServletResponse resp, String cspNonce, boolean allowMedia) {
        String pageSize = String.valueOf(_manager.getPageSize());
        String refresh = String.valueOf(_manager.getRefreshDelaySeconds());
        StringBuilder headers = new StringBuilder(1024);
        headers.append("Cache-Control: private, no-cache, max-age=60\r\n");
        headers.append("Content-Security-Policy: default-src 'none'\r\n");
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=utf-8");
        String[] headerLines = headers.toString().split("\r\n");
        for (String line : headerLines) {
            String[] headerParts = line.split(": ");
            resp.setHeader(headerParts[0], headerParts[1]);
        }
        headers.setLength(0);
    }

    private UIMessages.Message lastMessage;

    private String getLastMessage() {
        UIMessages.Message lastMessage = null;
        List<UIMessages.Message> msgs = _manager.getMessages();
        if (!msgs.isEmpty()) {lastMessage = msgs.get(msgs.size() - 1);}
        return lastMessage != null ? lastMessage.message : null;
    }

    private void writeMessages(PrintWriter out, boolean isConfigure, String peerString) throws IOException {
        String resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
        List<UIMessages.Message> msgs = _manager.getMessages();
        int entries = msgs.size();
        StringBuilder mbuf = new StringBuilder(entries*256);
        if (!msgs.isEmpty()) {
            mbuf.append("<div id=screenlog").append(isConfigure ? " class=configpage" : "").append(" tabindex=0>\n");
            mbuf.append("<a id=closelog href=\"").append(_contextPath).append('/');
            if (isConfigure) {mbuf.append("configure");}
            if (peerString.length() > 0) {mbuf.append(peerString).append("&amp;");}
            else {mbuf.append("?");}
            int lastID = msgs.get(msgs.size() - 1).id;
            mbuf.append("action=Clear&amp;id=").append(lastID).append("&amp;nonce=").append(_nonce).append("\">");
            String tx = _t("clear messages");
            mbuf.append(toThemeSVG("delete", tx, tx)).append("</a>\n");
            mbuf.append("<a class=script id=expand hidden>");
            String x = _t("Expand");
            mbuf.append(toThemeSVG("expand", x, x)).append("</a>\n");
            mbuf.append("<a class=script id=shrink hidden>");
            String s = _t("Shrink");
            mbuf.append(toThemeSVG("shrink", s, s)).append("</a>\n");
            mbuf.append("<ul id=messages class=volatile>\n");
            if (!_manager.util().connected()) {
                mbuf.append("<noscript>\n<li class=noscriptWarning>")
                    .append(_t("Warning! Javascript is disabled in your browser. " +
                            "If {0} is enabled, you will lose any input in the add/create torrent sections when a refresh occurs.",
                            "<a href=\"configure\">" + _t("page refresh") + "</a>"))
                    .append("</li>\n</noscript>\n");
            }

            for (int i = msgs.size()-1; i >= 0; i--) {
                String msg = msgs.get(i).message
                                        .replace("Adding Magnet ", "Magnet added: " + "<span class=infohash>")
                                        .replace("Starting torrent: Magnet", "Starting torrent: <span class=infohash>");
                if (msg.contains("class=infohash")) {msg = msg.replaceFirst(" \\(", "</span> (");} // does this fix the display snafu?
                if (msg.contains(_t("Warning - No I2P"))) {msg = msg.replace("</span>", "");}
                mbuf.append("<li class=msg>").append(msg).append("</li>\n");
            }
            mbuf.append("</ul>");
        } else {mbuf.append("<div id=screenlog hidden><ul id=messages></ul>");}
        mbuf.append("</div>\n");
        if (isConfigure) {
            mbuf.append("<script nonce=").append(cspNonce).append(" type=module>\n")
                .append("  import {initToggleLog} from \"").append(resourcePath).append("js/toggleLog.js").append("\";\n")
                .append("  initToggleLog();\n")
                .append("</script>\n");
        } else {
            mbuf.append("<script src=").append(resourcePath).append("js/toggleLog.js type=module></script>\n");
        }
        int delay = 0;
        delay = _manager.getRefreshDelaySeconds();
        if (delay > 0 && _context.isRouterContext()) {
            mbuf.append("<script src=\"").append(resourcePath).append("js/graphRefresh.js?")
                .append(CoreVersion.VERSION).append("\" defer></script>\n");
        }
        out.write(mbuf.toString());
        out.flush();
        mbuf.setLength(0);
    }

    /**
     *  @param canWrite is the data directory writable?
     *  @return true if on first page
     */
    private boolean writeTorrents(PrintWriter out, HttpServletRequest req, boolean canWrite) throws IOException {
        /** dl, ul, down rate, up rate, peers, size */
        final long stats[] = new long[6];
        String filter = req.getParameter("filter") != null ? req.getParameter("filter") : "";
        String peerParam = req.getParameter("p");
        String psize = req.getParameter("ps");
        String search = req.getParameter("search");
        String srt = req.getParameter("sort");
        String stParam = req.getParameter("st");
        int refresh = _manager.getRefreshDelaySeconds();
        List<Snark> snarks = getSortedSnarks(req);
        int total = snarks.size();
        boolean isForm = _manager.util().connected() || !snarks.isEmpty();
        boolean showStatusFilter = _manager.util().showStatusFilter();
        filterParam = filter;
        filterEnabled = filterParam != null && !filterParam.equals("all") && !filterParam.equals("");
        boolean searchActive = (search != null && !search.equals("") && search.length() > 0);
        if (isForm) {
            StringBuilder buf = new StringBuilder(1280);
            if (showStatusFilter && !snarks.isEmpty() && _manager.util().connected()) {
                buf.append("<form id=torrentlist class=filterbarActive action=\"_post\" method=POST target=processForm>\n");
            } else {buf.append("<form id=torrentlist action=\"_post\" method=POST target=processForm>\n");}

            // selective display of torrents based on status
            if (showStatusFilter) {
                if (!snarks.isEmpty() && _manager.util().connected()) {
                    StringBuilder activeQuery = new StringBuilder("/i2psnark/?");
                    if (peerParam != null) {activeQuery.append("p=" + peerParam + "&");}
                    if (srt != null) {activeQuery.append("sort=" + srt + "&");}
                    if (searchActive) {activeQuery.append("search=" + search + "&");}
                    if (psize != null) {activeQuery.append("ps=" + psize + "&");}
                    if (filterEnabled) {
                        String existingFilter = "filter=" + filterParam;  // remove existing filter parameter
                        int filterIndex = activeQuery.indexOf(existingFilter);
                        if (filterIndex >= 0) {activeQuery.delete(filterIndex, filterIndex + existingFilter.length());}
                    }
                    activeQuery.setLength(activeQuery.length() - 1);
                    String buttonUrl = activeQuery.toString();
                    int pageSizeConf = _manager.getPageSize();
                    buttonUrl += (buttonUrl.contains("=") ? "&filter=" : "?filter=");
                    buf.append("<div id=torrentDisplay>")
                       .append("<a class=filter id=search href=\"").append(buttonUrl).append("all\"")
                       .append(searchActive ? "" : " hidden").append("><span>").append(_t("Search"))
                       .append("<span class=badge").append(searchActive ? "" : " hidden").append(">");
                    if (searchResults > Math.max(pageSizeConf, 10)) {
                        buf.append(Math.max(pageSizeConf, 10)).append(" / ").append(searchResults);
                    } else if (searchActive) {buf.append(searchResults);}
                    buf.append("</span></span></a>")
                       .append("<a class=filter id=all href=\"").append(buttonUrl).append("all\"")
                       .append(!searchActive ? "" : " hidden").append("><span>").append(_t("Show All"))
                       .append("<span class=badge hidden>");
                    if (Math.max(pageSizeConf, 10) < total) {
                        buf.append(Math.max(pageSizeConf, 10)).append(" / ").append(total);
                    } else {buf.append(total);}
                    buf.append("</span></span></a>")
                        .append("<a class=filter id=active href=\"").append(buttonUrl).append("active\"><span>")
                        .append(_t("Active")).append("<span class=badge></span></span></a>")
                        .append("<a class=filter id=inactive href=\"").append(buttonUrl).append("inactive\"><span>")
                        .append(_t("Inactive")).append("<span class=badge></span></span></a>")
                        .append("<a class=filter id=connected href=\"").append(buttonUrl).append("connected\"><span>")
                        .append(_t("Connected")).append("<span class=badge></span></span></a>")
                        .append("<a class=filter id=downloading href=\"").append(buttonUrl).append("downloading\"><span>")
                        .append(_t("Downloading")).append("<span class=badge></span></span></a>")
                        .append("<a class=filter id=seeding href=\"").append(buttonUrl).append("seeding\"><span>")
                        .append(_t("Seeding")).append("<span class=badge></span></span></a>")
                        .append("<a class=filter id=complete href=\"").append(buttonUrl).append("complete\"><span>")
                        .append(_t("Complete")).append("<span class=badge></span></span></a>")
                        .append("<a class=filter id=incomplete href=\"").append(buttonUrl).append("incomplete\"><span>")
                        .append(_t("Incomplete")).append("<span class=badge></span></span></a>")
                        .append("<a class=filter id=stopped href=\"").append(buttonUrl).append("stopped\"><span>")
                        .append(_t("Stopped")).append("<span class=badge></span></span></a>")
                        .append("</div>\n");
                }
            }
            out.write(buf.toString());
            buf.setLength(0);
            writeHiddenInputs(out, req, null);
            out.flush();
        }

        // Opera and text-mode browsers: no &thinsp; and no input type=image values submitted
        // Using a unique name fixes Opera, except for the buttons with js confirms, see below
        String ua = req.getHeader("User-Agent");
        boolean isDegraded = ua != null && ServletUtil.isTextBrowser(ua);
        boolean noThinsp = isDegraded || (ua != null && ua.startsWith("Opera"));

        // search
        boolean isSearch = false;
        if (searchActive) {
            List<Snark> matches = search(search, snarks);
            if (matches != null) {
                snarks = matches;
                isSearch = true;
                searchResults = matches.size();
            }
        }

        // pages
        int start = 0;
        if (stParam != null) {
            try {start = Math.max(0, Math.min(total - 1, Integer.parseInt(stParam)));}
            catch (NumberFormatException nfe) {}
        }
        int pageSize = filterEnabled ? 9999 : Math.max(_manager.getPageSize(), 10);
        String ps = req.getParameter("ps");
        if (ps == "null") {ps = String.valueOf(pageSize);}
        if (ps != null) {
            try {pageSize = Integer.parseInt(ps);}
            catch (NumberFormatException nfe) {}
        }
        // move pagenav here so we can align it nicely without resorting to hacks
        if (isForm && total > 0 && (start > 0 || total > pageSize)) {
            if (!searchActive || searchActive && search.length() > pageSize) {
                out.write("<div class=pagenavcontrols id=pagenavtop>");
                writePageNav(out, req, start, pageSize, total, filter, noThinsp);
                out.write("</div>");
            }
        } else if (isForm && showStatusFilter && total > pageSize) {
            out.write("<div class=pagenavcontrols id=pagenavtop hidden>");
            writePageNav(out, req, start, pageSize, total, filter, noThinsp);
            out.write("</div>");
        } else {
            out.write("<div class=pagenavcontrols id=pagenavtop hidden></div>");
        }

        out.write(TABLE_HEADER);

        String currentSort = req.getParameter("sort");
        String url = req.getRequestURL().toString();
        boolean hasQueryParams = req.getQueryString() != null && !req.getQueryString().isEmpty();
        filterParam = req.getParameter("filter") != null ? req.getParameter("filter") : "";
        String filterQuery = "";
        String separator = hasQueryParams ? "&" : "?";
        filterQuery = "filter=" + (filterParam.isEmpty() ? "all" : filterParam);
        boolean showSort = total > 1;
        StringBuilder hbuf = new StringBuilder(2*1024);
        hbuf.append("<tr><th class=status>");
        // show incomplete torrents at top on first click
        String sort = ("-2".equals(currentSort)) ? "2" : "-2";
        if (showSort) {
            hbuf.append("<span class=sortIcon>");
            if (currentSort == null || "-2".equals(currentSort)) {
                sort = "2";
                if ( "-2".equals(currentSort)) {
                    hbuf.append("<span class=ascending></span>");
                }
            } else if ("2".equals(currentSort)) {
                sort = "-2";
                hbuf.append("<span class=descending></span>");
            } else {
                sort = "2";
            }
            hbuf.append("<a class=sorter href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
            hbuf.append(separator).append(filterQuery);
            hbuf.append("\">");
        }
        String tx = _t("Status");
        hbuf.append(toThemeImg("status", tx, showSort ? _t("Sort by {0}", tx) : tx));
        if (showSort) {
            hbuf.append("</a></span>");
        }
        hbuf.append("</th><th class=peerCount>");

        boolean hasPeers = false;
        if (_manager.util().connected() && !snarks.isEmpty()) {
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if (snarks.get(i).getPeerList().size() >= 1) {
                    hasPeers = true;
                    break;
                }
            }
            if (hasPeers) {
                String queryString = peerParam != null ? getQueryString(req, "", null, null) : getQueryString(req, "1", null, null);
                String link = _contextPath + '/' + queryString + filterQuery;
                tx = peerParam != null ? _t("Hide Peers") : _t("Show Peers");
                String img = peerParam != null ? "hidepeers" : "showpeers";
                String filterParam = peerParam == null ? "&filter=" : "?filter=";
                if (link.contains("filter=")) {
                    int index = link.indexOf("filter=");
                    link = link.substring(0, index) + link.substring(index).replaceFirst("filter=", filterParam);
                } else {link += filterParam;}
                hbuf.append(" <a class=\"sorter ").append(peerParam == null ? "showPeers" : "hidePeers")
                    .append(!hasPeers ? " noPeers" : "").append("\" href=\"").append(link).append("\">")
                    .append(toThemeImg(img, tx, tx)).append("</a>\n");
            }
        }

        hbuf.append("<th class=torrentLink colspan=2><input id=linkswitch class=optbox type=checkbox hidden></th>");
        hbuf.append("<th id=torrentSort>");
        // cycle through sort by name or type
        boolean isTypeSort = false;
        if (showSort) {
            hbuf.append("<span class=sortIcon>");
            if (currentSort == null || "0".equals(currentSort) || "1".equals(currentSort)) {
                sort = "-1";
                if ("1".equals(currentSort) || currentSort == null)
                    hbuf.append("<span class=ascending></span>");
            } else if ("-1".equals(currentSort)) {
                sort = "12";
                isTypeSort = true;
                hbuf.append("<span class=descending></span>");
            } else if ("12".equals(currentSort)) {
                sort = "-12";
                isTypeSort = true;
                hbuf.append("<span class=ascending></span>");
            } else {
                sort = "1";
            }
            hbuf.append("<a class=sorter href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
            hbuf.append(separator).append(filterQuery);
            hbuf.append("\">");
        }
        tx = _t("Torrent");
        if (!snarks.isEmpty()) {
            hbuf.append(toThemeImg("torrent", tx, showSort ? _t("Sort by {0}", (isTypeSort ? _t("File type") : _t("Torrent name"))) : tx));
            if (showSort) {
                hbuf.append("</a></span>");
            }
        }
        hbuf.append("</th><th class=tName></th><th class=ETA>");
        // FIXME: only show icon when actively downloading, not uploading
        if (_manager.util().connected() && !snarks.isEmpty()) {
            boolean isDownloading = false;
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if ((snarks.get(i).getPeerList().size() >= 1) && (snarks.get(i).getDownloadRate() > 0)) {
                    isDownloading = true;
                    break;
                }
            }
            if (isDownloading) {
                if (showSort) {
                    hbuf.append("<span class=sortIcon>");
                    if (currentSort == null || "-4".equals(currentSort)) {
                        sort = "4";
                        if ("-4".equals(currentSort))
                            hbuf.append("<span class=descending></span>");
                    } else if ("4".equals(currentSort)) {
                        sort = "-4";
                        hbuf.append("<span class=ascending></span>");
                    }
                    hbuf.append("<a class=sorter href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                    hbuf.append(separator).append(filterQuery);
                    hbuf.append("\">");
                }
            tx = _t("ETA");
            hbuf.append(toThemeImg("eta", tx, showSort ? _t("Sort by {0}", _t("Estimated time remaining")) : _t("Estimated time remaining")));
                if (showSort) {
                    hbuf.append("</a></span>");
                }
            }
         }
        hbuf.append("</th><th class=rxd>");
        // cycle through sort by size or downloaded
        boolean isDlSort = false;
        if (!snarks.isEmpty()) {
            if (showSort) {
                hbuf.append("<span class=sortIcon>");
                if ("-5".equals(currentSort)) {
                    sort = "5";
                    hbuf.append("<span class=descending></span>");
                } else if ("5".equals(currentSort)) {
                    sort = "-6";
                    isDlSort = true;
                    hbuf.append("<span class=ascending></span>");
                } else if ("-6".equals(currentSort)) {
                    sort = "6";
                    isDlSort = true;
                    hbuf.append("<span class=descending></span>");
                } else if ("6".equals(currentSort)) {
                    sort = "-5";
                    isDlSort = true;
                    hbuf.append("<span class=ascending></span>");
                } else {
                    sort = "-5";
                }
                hbuf.append("<a class=sorter href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                hbuf.append(separator).append(filterQuery);
                hbuf.append("\">");
            }
            tx = _t("RX");
            hbuf.append(toThemeImg("head_rx", tx, showSort ? _t("Sort by {0}", (isDlSort ? _t("Downloaded") : _t("Size"))) : _t("Downloaded")));
            if (showSort) {
                hbuf.append("</a></span>");
            }
        }
        hbuf.append("</th>");
        hbuf.append("<th class=rateDown>");
        // FIXME only show icon when total down rate > 0
        if (_manager.util().connected() && !snarks.isEmpty()) {
            boolean isDownloading = false;
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if ((snarks.get(i).getPeerList().size() >= 1) && (snarks.get(i).getDownloadRate() > 0)) {
                    isDownloading = true;
                    break;
                }
            }
            if (isDownloading) {
                if (showSort) {
                    hbuf.append("<span class=sortIcon>");
                    if (currentSort == null || "8".equals(currentSort)) {
                        sort = "-8";
                        if ("8".equals(currentSort))
                            hbuf.append("<span class=descending></span>");
                    } else if ("-8".equals(currentSort)) {
                        sort = "8";
                        hbuf.append("<span class=ascending></span>");
                    } else {
                        sort = "-8";
                    }
                    if (peerParam != null) {
                        hbuf.append("<a class=sorter href=\"" + _contextPath + '/' + getQueryString(req, "1", sort, filter, null));
                    } else {
                        hbuf.append("<a class=sorter href=\"" + _contextPath + '/' + getQueryString(req, "0", sort, filter, null));
                    }
                    hbuf.append(separator).append(filterQuery);
                    hbuf.append("\">");
                    tx = _t("RX Rate");
                    hbuf.append(toThemeImg("head_rxspeed", tx, showSort ? _t("Sort by {0}", _t("Down Rate")) : _t("Down Rate")));
                    if (showSort) {
                        hbuf.append("</a></span>");
                    }
                }
            }
        }
        hbuf.append("<th class=txd>");
        boolean isRatSort = false;
        // cycle through sort by uploaded or ratio
        boolean nextRatSort = false;
        if (showSort) {
            hbuf.append("<span class=sortIcon>");
            if ("-7".equals(currentSort)) {
                sort = "7";
                hbuf.append("<span class=descending></span>");
            } else if ("7".equals(currentSort)) {
                sort = "-11";
                nextRatSort = true;
                hbuf.append("<span class=ascending></span>");
            } else if ("-11".equals(currentSort)) {
                sort = "11";
                nextRatSort = true;
                isRatSort = true;
                hbuf.append("<span class=descending></span>");
            } else if ("11".equals(currentSort)) {
                sort = "-7";
                isRatSort = true;
                hbuf.append("<span class=ascending></span>");
            } else {
                sort = "-7";
            }
            hbuf.append("<a class=sorter href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort))
                .append(separator).append(filterQuery).append("\">");
        }
        tx = _t("TX");
        hbuf.append(toThemeImg("head_tx", tx, showSort ? _t("Sort by {0}", (nextRatSort ? _t("Upload ratio") : _t("Uploaded"))) : _t("Uploaded")));
        if (showSort) {hbuf.append("</a></span>");}
        hbuf.append("</th>")
            .append("<th class=rateUp>");
        // FIXME only show icon when total up rate > 0 and no choked peers
        if (_manager.util().connected() && !snarks.isEmpty()) {
            boolean isUploading = false;
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if ((snarks.get(i).getPeerList().size() >= 1) && (snarks.get(i).getUploadRate() > 0)) {
                    isUploading = true;
                    break;
                }
            }
            if (isUploading) {
                if (showSort) {
                    hbuf.append("<span class=sortIcon>");
                    if (currentSort == null || "9".equals(currentSort)) {
                        sort = "-9";
                        if ("9".equals(currentSort))
                            hbuf.append("<span class=ascending></span>");
                    } else if ("-9".equals(currentSort)) {
                        sort = "9";
                        hbuf.append("<span class=descending></span>");
                    } else {
                        sort = "-9";
                    }
                    hbuf.append("<a class=sorter href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort))
                        .append(separator).append(filterQuery).append("\">");
                }
                tx = _t("TX Rate");
                hbuf.append(toThemeImg("head_txspeed", tx, showSort ? _t("Sort by {0}", _t("Up Rate")) : _t("Up Rate")));
                if (showSort) {hbuf.append("</a></span>");}
            }
        }
        hbuf.append("</th>")
            .append("<th class=tAction>");
        if (_manager.isStopping()) {
            hbuf.append("");
        } else if (_manager.util().connected()) {
            hbuf.append("<input type=submit id=actionStopAll name=action_StopAll value=\"")
                .append(_t("Stop All") + "\" title=\"" + _t("Stop all torrents and the I2P tunnel") + "\">");
            for (Snark s : snarks) {
                if (s.isStopped()) {
                    // show startall too
                    hbuf.append("<input type=submit id=actionStartAll name=action_StartAll value=\"")
                        .append(_t("Start All") + "\" title=\"" + _t("Start all stopped torrents") + "\">");
                    break;
                }
            }
        } else if ((!_manager.util().isConnecting()) && !snarks.isEmpty()) {
            hbuf.append("<input type=submit id=actionStartAll name=action_StartAll value=\"" +
                      _t("Start All") + "\" title=\"" + _t("Start all torrents and the I2P tunnel") + "\">");
        }
        hbuf.append("</th></tr></thead>\n<tbody id=snarkTbody>");
        out.write(hbuf.toString());
        hbuf.setLength(0);
        out.flush();
        String uri = _contextPath + '/';
        boolean showDebug = "2".equals(peerParam);
        int totalSnarks = snarks.size();

        for (int i = 0; i < totalSnarks; i++) {
            Snark snark = snarks.get(i);
            boolean showPeers = showDebug || "1".equals(peerParam) || Base64.encode(snark.getInfoHash()).equals(peerParam);
            boolean hide = i < start || i >= start + pageSize;
            displaySnark(out, req, snark, uri, i, stats, showPeers, isDegraded, noThinsp, showDebug, hide, isRatSort, canWrite);
        }

        StringBuilder ftr = new StringBuilder(2*1024);
        if (total == 0) {
            ftr.append("<tr id=noload class=noneLoaded><td colspan=12><i>");
            synchronized(this) {
                File dd = _resourceBase;
                if (!dd.exists() && !dd.mkdirs()) {
                    ftr.append(_t("Data directory cannot be created") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!dd.isDirectory()) {
                    ftr.append(_t("Not a directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!dd.canRead()) {
                    ftr.append(_t("Unreadable") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!canWrite) {
                    ftr.append(_t("No write permissions for data directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (isSearch) {
                    ftr.append(_t("No torrents found."));
                } else {
                    ftr.append(_t("No torrents loaded."));
                }
            }
            ftr.append("</i></td></tr></tbody>\n").append("<tfoot id=\"snarkFoot");
            if (_manager.util().isConnecting()) {ftr.append("\" class=\"initializing");}
            ftr.append("\"><tr><th id=torrentTotals align=left colspan=12></th></tr></tfoot>\n");
        } else if (snarks.size() > 0) {
            // Add a pagenav to bottom of table if we have 50+ torrents per page
            // TODO: disable on pages where torrents is < 50 e.g. last page
            if (total > 0 && (start > 0 || total > pageSize) && pageSize >= 50 && total - start >= 20) {
                ftr.append("<tr id=pagenavbottom><td colspan=12><div class=pagenavcontrols>");
                StringWriter stringWriter = new StringWriter();
                PrintWriter tempPrintWriter = new PrintWriter(stringWriter);
                writePageNav(tempPrintWriter, req, start, pageSize, total, filter, noThinsp);
                tempPrintWriter.flush();
                ftr.append(stringWriter.getBuffer().toString()).append("</div></td></tr>\n");
            } else {
                ftr.append("<tr id=pagenavbottom hidden><td colspan=12><div class=pagenavcontrols></div></td></tr>\n");
            }
            ftr.append("</tbody>\n<tfoot id=snarkFoot><tr class=volatile>")
               .append("<th id=torrentTotals align=left colspan=6><span id=totals>");

            // Disk usage
            ftr.append(_manager.getDiskUsage());

            // torrent count
            ftr.append("<span id=torrentCount class=counter title=\"").append(ngettext("1 torrent", "{0} torrents", total)).append("\">");
            toThemeImg(ftr, "torrent");
            ftr.append("<span class=badge>").append(total).append("</span></span>");

            // torrents filesize
            ftr.append("<span id=torrentFilesize class=counter title=\"").append(_t("Total size of loaded torrents")).append("\">");
            toThemeImg(ftr, "size");
            ftr.append("<span class=badge>").append(DataHelper.formatSize2(stats[5]).replace("i", "")).append("</span></span>");

            // connected peers
            ftr.append("<span id=peerCount class=counter title=\"")
               .append(ngettext("1 connected peer", "{0} connected peers", (int) stats[4])
               .replace("connected peers", "peer connections"));
            DHT dht = _manager.util().getDHT();
            if (dht != null) {
                int dhts = dht.size();
                if (dhts > 0) {
                    ftr.append(" (").append(ngettext("1 DHT peer", "{0} DHT peers", dhts)).append(")");
                }
            }
            ftr.append("\">");
            toThemeImg(ftr, "showpeers");
            ftr.append("<span class=badge>").append((int) stats[4]).append("</span></span>");

            // actively downloading
            int downloads = 0;
            for (int i = start; i < snarks.size(); i++) {
                if ((snarks.get(i).getPeerList().size() >= 1) && (snarks.get(i).getDownloadRate() > 0)) {
                    downloads++;
                }
            }
            ftr.append("<span id=rxCount class=counter title=\"").append(_t("Active downloads")).append("\">");
            toThemeImg(ftr, "head_rx");
            ftr.append("<span class=badge>").append(downloads).append("</span></span>");

            // actively uploading
            int uploads = 0;
            for (int i = start; i < snarks.size(); i++) {
                if ((snarks.get(i).getPeerList().size() >= 1) && (snarks.get(i).getUploadRate() > 0)) {
                    uploads++;
                }
            }
            ftr.append("<span id=txCount class=counter title=\"").append(_t("Active uploads")).append("\">");
            toThemeImg(ftr, "head_tx");
            ftr.append("<span class=badge>").append(uploads).append("</span></span>");

            if (!isStandalone()) {
                String resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
                ftr.append("<span id=tnlInCount class=counter title=\"").append(_t("Active Inbound tunnels")).append("\" hidden>");
                toThemeSVG(ftr, "inbound", "", "");
                ftr.append("<span class=badge>").append("</span></span>");

                ftr.append("<span id=tnlOutCount class=counter title=\"").append(_t("Active Outbound tunnels")).append("\" hidden>");
                toThemeSVG(ftr, "outbound", "", "");
                ftr.append("<span class=badge>").append("</span></span>");
            }

            ftr.append("</span></th>");

            if (_manager.util().connected() && total > 0) {
                ftr.append("<th class=ETA>");
                if (_manager.util().connected() && !snarks.isEmpty()) {
                    hasPeers = false;
                    long remainingSeconds = 0;
                    long totalETA = 0;
                    int end = Math.min(start + pageSize, snarks.size());
                    for (int i = start; i < end; i++) {
                        long needed = snarks.get(i).getNeededLength();
                        if (needed > total) {needed = total;}
                        if (stats[2] > 0 && needed > 0) {remainingSeconds = needed / stats[2];}
                        else {remainingSeconds = 0;}
                        totalETA+= remainingSeconds;
                        hasPeers = true;
                        if (hasPeers) {
                            if (totalETA > 0) {
                                ftr.append("<span title=\"")
                                   .append(_t("Estimated download time for all torrents") + "\">")
                                   .append(DataHelper.formatDuration2(Math.max(totalETA, 10) * 1000))
                                   .append("</span>");
                            }
                        }
                        break;
                    }
                }
                ftr.append("</th>").append("<th class=rxd title=\"").append(_t("Data downloaded this session") + "\">");
                if (stats[0] > 0) {ftr.append(formatSize(stats[0]).replaceAll("iB", ""));}
                ftr.append("</th>").append("<th class=rateDown title=\"").append(_t("Total download speed") + "\">");
                if (stats[2] > 0) {ftr.append(formatSize(stats[2]).replaceAll("iB", "") + "/s");}
                ftr.append("</th>").append("<th class=txd  title=\"").append(_t("Total data uploaded (for listed torrents)") + "\">");
                if (stats[1] > 0) {ftr.append(formatSize(stats[1]).replaceAll("iB", ""));}
                ftr.append("</th>").append("<th class=rateUp title=\"").append(_t("Total upload speed") + "\">");
                boolean isUploading = false;
                int end = Math.min(start + pageSize, snarks.size());
                for (int i = start; i < end; i++) {
                    if ((snarks.get(i).getPeerList().size() >= 1) && (snarks.get(i).getUploadRate() > 0)) {
                        isUploading = true;
                        break;
                    }
                }
                if (stats[3] > 0 && isUploading) {ftr.append(formatSize(stats[3]).replaceAll("iB", "") + "/s");}
                ftr.append("</th>").append("<th class=tAction>");
                if (dht != null && (!"2".equals(peerParam))) {
                    ftr.append("<a id=debugMode href=\"?p=2\" title=\"").append(_t("Toggle Debug Mode") + "\">").append(_t("Debug Mode") + "</a>");
                } else if (dht != null) {
                    ftr.append("<a id=debugMode href=\"?p\" title=\"").append(_t("Toggle Debug Mode") + "\">").append(_t("Normal Mode") + "</a>");
                }
                ftr.append("</th>");
                } else {ftr.append("<th colspan=6></th>");}
                ftr.append("</tr>\n");

                if (showDebug) {ftr.append("<tr id=dhtDebug>");}
                else {ftr.append("<tr id=dhtDebug hidden>");}
                ftr.append("<th colspan=12><div class=volatile>");
                if (dht != null) {ftr.append(_manager.getBandwidthListener().toString()).append(dht.renderStatusHTML());}
                else {ftr.append("<b id=noDHTpeers>").append(_t("No DHT Peers")).append("</b>");}
                ftr.append("</div></th></tr>").append("</tfoot>\n");
            }

            ftr.append("</table>\n");
            if (isForm) {ftr.append("</form>");}

            out.write(ftr.toString());
            ftr.setLength(0);
            out.flush();

            return start == 0;
    }

    /**
     *  search torrents for matching terms
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
                if (searchList == null) {
                    searchList = new ArrayList<String>(4);
                }
                searchList.add(Normalizer.normalize(term.toLowerCase(Locale.US), Normalizer.Form.NFKD));
            }
        }
        if (searchList == null || searchList.isEmpty()) {
            return new ArrayList<Snark>(0); // empty list
        }
        List<Snark> matches = new ArrayList<Snark>(32);
        for (Snark snark : snarks) {
            String lcname = Normalizer.normalize(snark.getBaseName().toLowerCase(Locale.US), Normalizer.Form.NFKD);
            // search for any term (OR)
            for (int j = 0; j < searchList.size(); j++) {
                String term = searchList.get(j);
                if (lcname.contains(term)) {
                    matches.add(snark);
                    break;
                }
            }
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
        out.write(buf.toString());
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
        int num = Integer.valueOf(str);
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
     *  @since 0.9.6
     */

    private void writePageNav(PrintWriter out, HttpServletRequest req, int start, int pageSize, int total, String search, boolean noThinsp) {
        StringBuilder buf = new StringBuilder(1024);

        // First
        buf.append("<a href=\"")
           .append(_contextPath)
           .append(getQueryString(req, null, "", null, null))
           .append("\"")
           .append(start > 0 ? "" : " class=disabled")
           .append("><span id=first>")
           .append(toThemeSVG("first", _t("First"), _t("First page")))
           .append("</span></a>");

        // Back
        int prev = Math.max(0, start - pageSize);
        buf.append("<a href=\"")
           .append(_contextPath)
           .append(getQueryString(req, null, String.valueOf(prev), null, null))
           .append("\"")
           .append(prev > 0 ? "" : " class=disabled")
           .append("><span id=previous>")
           .append(toThemeSVG("previous", _t("Prev"), _t("Previous page")))
           .append("</span></a>");

        // Page count
        int pages = 1 + ((total - 1) / pageSize);
        if (pages == 1 && start > 0) {
            pages = 2;
        }
        if (pages > 1) {
            int page = (start + pageSize >= total) ? pages : (1 + (start / pageSize));
            buf.append("<span id=pagecount>")
               .append(page)
               .append(thinsp(noThinsp))
               .append(pages)
               .append("</span>");
        }

        // Next
        int next = start + pageSize;
        buf.append("<a href=\"")
           .append(_contextPath)
           .append(getQueryString(req, null, String.valueOf(next), null, null))
           .append("\"")
           .append(next + pageSize < total ? "" : " class=disabled")
           .append("><span id=next>")
           .append(toThemeSVG("next", _t("Next"), _t("Next page")))
           .append("</span></a>");

        // Last
        int last = ((total - 1) / pageSize) * pageSize;
        buf.append("<a href=\"")
           .append(_contextPath)
           .append(getQueryString(req, null, String.valueOf(last), null, null))
           .append("\"")
           .append(start + pageSize < total ? "" : " class=disabled")
           .append("><span id=last>")
           .append(toThemeSVG("last", _t("Last"), _t("Last page")))
           .append("</span></a>");

        out.write(buf.toString());
        buf.setLength(0);
        out.flush();
    }

    /**
     * Do what they ask, adding messages to _manager.addMessage as necessary
     */
    private void processRequest(HttpServletRequest req) {
        String action = req.getParameter("action");
        if (action == null) {
            // http://www.onenaught.com/posts/382/firefox-4-change-input-type-image-only-submits-x-and-y-not-name
            @SuppressWarnings("unchecked") // TODO-Java6: Remove cast, return type is correct
            Map<String, String[]> params = req.getParameterMap();
            for (Object o : params.keySet()) {
                String key = (String) o;
                if (key.startsWith("action_")) {
                    action = key.substring(0, key.length()).substring(7);
                    break;
                }
            }
            if (action == null) {
                // confirm.js will generate this error when a remove/delete is cancelled, so suppress it.
                //_manager.addMessage("No action specified");
                return;
            }
        }

        if ("Add".equals(action)) {
            File dd = _manager.getDataDir();
            if (!dd.canWrite()) {
                _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                return;
            }

            String contentType = req.getContentType();
            RequestWrapper reqw = new RequestWrapper(req);
            String newURL = reqw.getParameter("nofilter_newURL");
            String newFile = reqw.getFilename("newFile");
            if (newFile != null && newFile.trim().length() > 0) {
                if (!newFile.endsWith(".torrent")) {newFile += ".torrent";}
                File local = new File(dd, newFile);
                String newFile2 = Storage.filterName(newFile);
                File local2;
                if (!newFile.equals(newFile2)) {local2 = new File(dd, newFile2);}
                else {local2 = null;}
                if (local.exists() || (local2 != null && local2.exists())) {
                    try {
                        String canonical = local.getCanonicalPath();
                        String canonical2 = local2 != null ? local2.getCanonicalPath() : null;
                        if (_manager.getTorrent(canonical) != null ||
                            (canonical2 != null && _manager.getTorrent(canonical2) != null)) {
                            _manager.addMessage(_t("Torrent already running: {0}", canonical));
                         } else {_manager.addMessage(_t("Torrent already in the queue: {0}", canonical));}
                    } catch (IOException ioe) {}
                } else {
                    File tmp = new File(_manager.util().getTempDir(), "newTorrent-" + _manager.util().getContext().random().nextLong() + ".torrent");
                    InputStream in = null;
                    OutputStream out = null;
                    try {
                        in = reqw.getInputStream("newFile");
                        out = new SecureFileOutputStream(tmp);
                        DataHelper.copy(in, out);
                        out.close();
                        out = null;
                        in.close();
                        // test that it's a valid torrent file, and get the hash to check for dups
                        in = new FileInputStream(tmp);
                        byte[] fileInfoHash = new byte[20];
                        String name = MetaInfo.getNameAndInfoHash(in, fileInfoHash);
                        try { in.close(); } catch (IOException ioe) {}
                        Snark snark = _manager.getTorrentByInfoHash(fileInfoHash);
                        if (snark != null) {
                            _manager.addMessage(_t("Torrent with this info hash is already running: {0}", snark.getBaseName()));
                            return;
                        }
                        if (local2 != null) {local = local2;}
                        String canonical = local.getCanonicalPath();
                        // This may take a LONG time to create the storage.
                        boolean ok = _manager.copyAndAddTorrent(tmp, canonical, dd);
                        if (!ok) {throw new IOException("Unknown error - check logs");}
                        snark = _manager.getTorrentByInfoHash(fileInfoHash);
                        if (snark != null) {snark.startTorrent();}
                        else {throw new IOException("Not found: " + canonical);}
                    } catch (IOException ioe) {
                        _manager.addMessageNoEscape(_t("Torrent at {0} was not valid", DataHelper.escapeHTML(newFile)) + ": " + DataHelper.stripHTML(ioe.getMessage()));
                        tmp.delete();
                        local.delete();
                        if (local2 != null) {local2.delete();}
                        return;
                    } catch (OutOfMemoryError oom) {
                        _manager.addMessageNoEscape(_t("ERROR - Out of memory, cannot create torrent from {0}",
                                                    DataHelper.escapeHTML(newFile)) + ": " +
                                                    DataHelper.stripHTML(oom.getMessage()));
                    } finally {
                        if (in != null) try { in.close(); } catch (IOException ioe) {}
                        if (out != null) try { out.close(); } catch (IOException ioe) {}
                        tmp.delete();
                    }
                }
            } else if (newURL != null && newURL.trim().length() > 0) {
                newURL = newURL.trim();
                String newDir = reqw.getParameter("nofilter_newDir");
                File dir = null;
                if (newDir != null) {
                    newDir = newDir.trim();
                    if (newDir.length() > 0) {
                        dir = new SecureFile(newDir);
                        if (!dir.isAbsolute()) {
                            _manager.addMessage(_t("Data directory must be an absolute path") + ": " + dir);
                            return;
                        }
                        if (!dir.isDirectory() && !dir.mkdirs()) {
                            _manager.addMessage(_t("Data directory cannot be created") + ": " + dir);
                            return;
                        }
                        Collection<Snark> snarks = _manager.getTorrents();
                        for (Snark s : snarks) {
                            Storage storage = s.getStorage();
                            if (storage == null) {continue;}
                            File sbase = storage.getBase();
                            if (isParentOf(sbase, dir)) {
                                _manager.addMessage(_t("Cannot add torrent {0} inside another torrent: {1}",
                                                      dir.getAbsolutePath(), sbase));
                                return;
                            }
                        }
                    }
                }
                if (newURL.startsWith("http://") || newURL.startsWith("https://")) {
                    if (isI2PTracker(newURL)) {
                        FetchAndAdd fetch = new FetchAndAdd(_context, _manager, newURL, dir);
                        _manager.addDownloader(fetch);
                    } else {_manager.addMessageNoEscape(_t("Download from non-I2P location {0} is not supported", urlify(newURL)));} // TODO
                } else if (newURL.startsWith(MagnetURI.MAGNET) || newURL.startsWith(MagnetURI.MAGGOT)) {
                    addMagnet(newURL, dir);
                } else if (newURL.length() == 40 && newURL.replaceAll("[a-fA-F0-9]", "").length() == 0) {
                    // hex
                    newURL = newURL.toUpperCase(Locale.US);
                    addMagnet(MagnetURI.MAGNET_FULL + newURL, dir);
                } else if (newURL.length() == 32 && newURL.replaceAll("[a-zA-Z2-7]", "").length() == 0) {
                    // b32
                    newURL = newURL.toUpperCase(Locale.US);
                    addMagnet(MagnetURI.MAGNET_FULL + newURL, dir);
                } else if (newURL.length() == 68 && newURL.startsWith("1220") &&
                           newURL.replaceAll("[a-fA-F0-9]", "").length() == 0) {
                    // TODO hex v2 multihash
                    _manager.addMessage("Error: Version 2 info hashes are not supported");
                    //addMagnet(MagnetURI.MAGNET_FULL_V2 + newURL, dir);
                } else {
                    // try as file path, hopefully we're on the same box
                    if (newURL.startsWith("file://")) {newURL = newURL.substring(7);}
                    File file = new File(newURL);
                    if (file.isAbsolute() && file.exists()) {
                        if (!newURL.endsWith(".torrent")) {
                            _manager.addMessageNoEscape(_t("Torrent at {0} was not valid", DataHelper.escapeHTML(newURL)));
                            return;
                        }
                        FileInputStream in = null;
                        try {
                            // This is all copied from FetchAndAdd
                            // test that it's a valid torrent file, and get the hash to check for dups
                            in = new FileInputStream(file);
                            byte[] fileInfoHash = new byte[20];
                            String name = MetaInfo.getNameAndInfoHash(in, fileInfoHash);
                            try { in.close(); } catch (IOException ioe) {}
                            Snark snark = _manager.getTorrentByInfoHash(fileInfoHash);
                            if (snark != null) {
                                _manager.addMessage(_t("Torrent with this info hash is already running: {0}",
                                                    snark.getBaseName()).replace("Magnet ", "").replace("</span>", ""));
                                return;
                            }

                            // check for dup file name
                            String originalName = Storage.filterName(name);
                            name = originalName + ".torrent";
                            File torrentFile = new File(dd, name);
                            String canonical = torrentFile.getCanonicalPath();
                            if (torrentFile.exists()) {
                                if (_manager.getTorrent(canonical) != null) {
                                    _manager.addMessage(_t("Torrent already running: {0}", name));
                                } else {
                                    _manager.addMessage(_t("Torrent already in the queue: {0}", name));
                                }
                            } else {
                                // This may take a LONG time to create the storage.
                                boolean ok = _manager.copyAndAddTorrent(file, canonical, dd);
                                if (!ok) {throw new IOException("Unknown error - check logs");}
                                snark = _manager.getTorrentByInfoHash(fileInfoHash);
                                if (snark != null) {snark.startTorrent();}
                                else {throw new IOException("Unknown error - check logs");}
                            }
                        } catch (IOException ioe) {
                            _manager.addMessageNoEscape(_t("Torrent at {0} was not valid", DataHelper.escapeHTML(newURL)) + ": " +
                                                        DataHelper.stripHTML(ioe.getMessage()));
                        } catch (OutOfMemoryError oom) {
                            _manager.addMessageNoEscape(_t("ERROR - Out of memory, cannot create torrent from {0}",
                            DataHelper.escapeHTML(newURL)) + ": " + DataHelper.stripHTML(oom.getMessage()));
                        } finally {
                            try { if (in != null) in.close(); } catch (IOException ioe) {}
                        }
                    } else {
                        _manager.addMessage(_t("Invalid URL: Must start with \"{0}\" or \"{1}\"",
                                               "http://", MagnetURI.MAGNET));
                    }
                }
            } else {_manager.addMessage(_t("Enter URL or select torrent file"));} // no file or URL specified
        } else if (action.startsWith("Stop_")) {
            String torrent = action.substring(5);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ((infoHash != null) && (infoHash.length == 20)) { // valid sha1
                    for (String name : _manager.listTorrentFiles()) {
                        Snark snark = _manager.getTorrent(name);
                        if ((snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash()))) {
                            _manager.stopTorrent(snark, false);
                            break;
                        }
                    }
                }
            }
        } else if (action.startsWith("Start_")) {
            String torrent = action.substring(6).replace("%3D", "=");
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ((infoHash != null) && (infoHash.length == 20)) {_manager.startTorrent(infoHash);} // valid sha1
            }
        } else if (action.startsWith("Remove_")) {
            String torrent = action.substring(7);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ((infoHash != null) && (infoHash.length == 20)) { // valid sha1
                    for (String name : _manager.listTorrentFiles()) {
                        Snark snark = _manager.getTorrent(name);
                        if ((snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash()))) {
                            MetaInfo meta = snark.getMetaInfo();
                            if (meta == null) {
                                // magnet - remove and delete are the same thing
                                // Remove not shown on UI so we shouldn't get here
                                _manager.deleteMagnet(snark);
                                name = name.replace("Magnet ", "");
                                _manager.addMessage(_t("Magnet deleted: {0}", name));
                                return;
                            }
                            File f = new File(name);
                            File dd = _manager.getDataDir();
                            boolean canDelete = dd.canWrite() || !f.exists();
                            _manager.stopTorrent(snark, canDelete);
                            // TODO race here with the DirMonitor, could get re-added
                            if (f.delete()) {_manager.addMessage(_t("Torrent file deleted: {0}", f.getAbsolutePath()));}
                            else if (f.exists()) {
                                if (!canDelete) {
                                    _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                                }
                                _manager.addMessage(_t("Torrent file could not be deleted: {0}", f.getAbsolutePath()));
                            }
                            break;
                        }
                    }
                }
            }
        } else if (action.startsWith("Delete_")) {
            String torrent = action.substring(7);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ((infoHash != null) && (infoHash.length == 20)) { // valid sha1
                    for (String name : _manager.listTorrentFiles()) {
                        Snark snark = _manager.getTorrent(name);
                        if ((snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash()))) {
                            MetaInfo meta = snark.getMetaInfo();
                            if (meta == null) {
                                // magnet - remove and delete are the same thing
                                name = name.replace("Magnet ", "");
                                _manager.deleteMagnet(snark);
                                if (snark instanceof FetchAndAdd) {_manager.addMessage(_t("Download deleted: {0}", name));}
                                else {_manager.addMessage(_t("Magnet deleted: {0}", name));}
                                return;
                            }
                            File f = new File(name);
                            File dd = _manager.getDataDir();
                            boolean canDelete = dd.canWrite() || !f.exists();
                            _manager.stopTorrent(snark, canDelete);
                            // TODO race here with the DirMonitor, could get re-added
                            if (f.delete()) {_manager.addMessage(_t("Torrent file deleted: {0}", f.getAbsolutePath()));}
                            else if (f.exists()) {
                                if (!canDelete)
                                    _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                                _manager.addMessage(_t("Torrent file could not be deleted: {0}", f.getAbsolutePath()));
                                return;
                            }
                            Storage storage = snark.getStorage();
                            if (storage == null) {break;}
                            List<List<String>> files = meta.getFiles();
                            if (files == null) { // single file torrent
                                for (File df : storage.getFiles()) {
                                    // should be only one
                                    if (df.delete()) {
                                        _manager.addMessage(_t("Data file deleted: {0}", df.getAbsolutePath()));
                                    } else if (df.exists()) {
                                        _manager.addMessage(_t("Data file could not be deleted: {0}", df.getAbsolutePath()));
                                    }
                                    // else already gone
                                }
                                break;
                            }
                            // step 1 delete files
                            for (File df : storage.getFiles()) {
                                if (df.delete()) {
                                    //_manager.addMessage(_t("Data file deleted: {0}", df.getAbsolutePath()));
                                } else if (df.exists()) {
                                    _manager.addMessage(_t("Data file could not be deleted: {0}", df.getAbsolutePath()));
                                // else already gone
                                }
                            }
                            // step 2 delete dirs bottom-up
                            Set<File> dirs = storage.getDirectories();
                            if (dirs == null) {break;}  // directory deleted out from under us
                            if (_log.shouldInfo()) {_log.info("Dirs to delete: " + DataHelper.toString(dirs));}
                            boolean ok = false;
                            for (File df : dirs) {
                                if (df.delete()) {
                                    ok = true;
                                    //_manager.addMessage(_t("Data dir deleted: {0}", df.getAbsolutePath()));
                                } else if (df.exists()) {
                                    ok = false;
                                    _manager.addMessage(_t("Directory could not be deleted: {0}", df.getAbsolutePath()));
                                    if (_log.shouldWarn()) {
                                        _log.warn("[I2PSnark] Could not delete directory: " + df);
                                    }
                                // else already gone
                                }
                            }
                            // step 3 message for base (last one)
                            if (ok) {_manager.addMessage(_t("Directory deleted: {0}", storage.getBase()));}
                            break;
                        }
                    }
                }
            }
        } else if ("Save".equals(action)) {
            String dataDir = req.getParameter("nofilter_dataDir");
            boolean filesPublic = req.getParameter("filesPublic") != null;
            boolean autoStart = req.getParameter("autoStart") != null;
            boolean smartSort = req.getParameter("smartSort") != null;
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
            boolean useOpenTrackers = req.getParameter("useOpenTrackers") != null;
            boolean useDHT = req.getParameter("useDHT") != null;
            //String openTrackers = req.getParameter("openTrackers");
            String theme = req.getParameter("theme");
            String lang = req.getParameter("lang");
            boolean ratings = req.getParameter("ratings") != null;
            boolean comments = req.getParameter("comments") != null;
            // commentsName is filtered in SnarkManager.updateConfig()
            String commentsName = req.getParameter("nofilter_commentsName");
            boolean collapsePanels = req.getParameter("collapsePanels") != null;
            boolean showStatusFilter = req.getParameter("showStatusFilter") != null;
            boolean enableLightbox = req.getParameter("enableLightbox") != null;
            boolean enableAddCreate = req.getParameter("enableAddCreate") != null;
            boolean enableVaryInboundHops = req.getParameter("varyInbound") != null;
            boolean enableVaryOutboundHops = req.getParameter("varyOutbound") != null;
            _manager.updateConfig(dataDir, filesPublic, autoStart, smartSort, refreshDel, startupDel, pageSize, seedPct, eepHost, eepPort,
                                  i2cpHost, i2cpPort, i2cpOpts, upLimit, upBW, downBW, useOpenTrackers, useDHT, theme, lang, ratings, comments,
                                  commentsName, collapsePanels, showStatusFilter, enableLightbox, enableAddCreate, enableVaryInboundHops,
                                  enableVaryOutboundHops);
            // update servlet
            try {setResourceBase(_manager.getDataDir());}
            catch (ServletException se) {}
        } else if ("Save2".equals(action)) {
            String taction = req.getParameter("taction");
            if (taction != null) {processTrackerForm(taction, req);}
        } else if ("Save3".equals(action)) {
            String raction = req.getParameter("raction");
            if (raction != null) {processTorrentCreateFilterForm(raction, req);}
        } else if ("Create".equals(action)) {
            String baseData = req.getParameter("nofilter_baseFile");
            if (baseData != null && baseData.trim().length() > 0) {
                File baseFile = new File(baseData.trim());
                if (!baseFile.isAbsolute()) {baseFile = new File(_manager.getDataDir(), baseData);}
                String announceURL = req.getParameter("announceURL");
                String comment = req.getParameter("comment");

                if (baseFile.exists()) {
                    File dd = _manager.getDataDir();
                    if (!dd.canWrite()) {
                        _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                        return;
                    }
                    String tName = baseFile.getName();
                    if (tName.toLowerCase(Locale.US).endsWith(".torrent")) {
                        _manager.addMessage(_t("Cannot add a torrent ending in \".torrent\": {0}", baseFile.getAbsolutePath()));
                        return;
                    }
                    Snark snark = _manager.getTorrentByBaseName(tName);
                    if (snark != null) {
                        _manager.addMessage(_t("Torrent with this name is already running: {0}", tName));
                        return;
                    }
                    if (isParentOf(baseFile,_manager.getDataDir()) ||
                        isParentOf(baseFile, _manager.util().getContext().getBaseDir()) ||
                        isParentOf(baseFile, _manager.util().getContext().getConfigDir())) {
                        _manager.addMessage(_t("Cannot add a torrent including an I2P directory: {0}", baseFile.getAbsolutePath()));
                        return;
                    }
                    Collection<Snark> snarks = _manager.getTorrents();
                    for (Snark s : snarks) {
                        Storage storage = s.getStorage();
                        if (storage == null) {continue;}
                        File sbase = storage.getBase();
                        if (isParentOf(sbase, baseFile)) {
                            _manager.addMessage(_t("Cannot add torrent {0} inside another torrent: {1}",
                                                  baseFile.getAbsolutePath(), sbase));
                            return;
                        }
                        if (isParentOf(baseFile, sbase)) {
                            _manager.addMessage(_t("Cannot add torrent {0} including another torrent: {1}",
                                                  baseFile.getAbsolutePath(), sbase));
                            return;
                        }
                    }

                    if (announceURL.equals("none")) {announceURL = null;}
                    _lastAnnounceURL = announceURL;
                    List<String> backupURLs = new ArrayList<String>();
                    Enumeration<?> e = req.getParameterNames();
                    while (e.hasMoreElements()) {
                         Object o = e.nextElement();
                         if (!(o instanceof String)) {continue;}
                         String k = (String) o;
                        if (k.startsWith("backup_")) {
                            String url = k.substring(7);
                            if (!url.equals(announceURL)) {backupURLs.add(DataHelper.stripHTML(url));}
                        }
                    }
                    List<List<String>> announceList = null;
                    if (!backupURLs.isEmpty()) {
                        // BEP 12 - Put primary first, then the others, each as the sole entry in their own list
                        if (announceURL == null) {
                            _manager.addMessage(_t("Error - Cannot include alternate trackers without a primary tracker"));
                            return;
                        }
                        backupURLs.add(0, announceURL);
                        boolean hasPrivate = false;
                        boolean hasPublic = false;
                        for (String url : backupURLs) {
                            if (_manager.getPrivateTrackers().contains(url)) {hasPrivate = true;}
                            else {hasPublic = true;}
                        }
                        if (hasPrivate && hasPublic) {
                            _manager.addMessage(_t("Error - Cannot mix private and public trackers in a torrent"));
                            return;
                        }
                        announceList = new ArrayList<List<String>>(backupURLs.size());
                        for (String url : backupURLs) {
                            announceList.add(Collections.singletonList(url));
                        }
                    }
                    try {
                        // This may take a long time to check the storage, but since it already exists,
                        // it shouldn't be THAT bad, so keep it in this thread.
                        // TODO thread it for big torrents, perhaps a la FetchAndAdd
                        boolean isPrivate = _manager.getPrivateTrackers().contains(announceURL);
                        String[] filters = req.getParameterValues("filters");
                        List<TorrentCreateFilter> filterList = new ArrayList<TorrentCreateFilter>();
                        Map<String, TorrentCreateFilter> torrentCreateFilters = _manager.getTorrentCreateFilterMap();
                        if (filters == null) {filters = new String[0];}
                        for (int i = 0; i < filters.length; i++) {
                            TorrentCreateFilter filter = torrentCreateFilters.get(filters[i]);
                            filterList.add(filter);
                        }
                        Storage s = new Storage(_manager.util(), baseFile, announceURL, announceList, null, isPrivate, null, filterList);
                        s.close(); // close the files... maybe need a way to pass this Storage to addTorrent rather than starting over
                        MetaInfo info = s.getMetaInfo();
                        File torrentFile = new File(_manager.getDataDir(), s.getBaseName() + ".torrent");
                        // FIXME is the storage going to stay around thanks to the info reference?
                        // now add it, but don't automatically start it
                        boolean ok = _manager.addTorrent(info, s.getBitField(), torrentFile.getAbsolutePath(), baseFile, true);
                        if (!ok) {return;}
                        List<String> filesExcluded = s.getExcludedFiles(_manager.getDataDir());
                        if (_log.shouldInfo() && filesExcluded.size() > 0) {
                            String msg = filesExcluded.size() + " excluded from \"" + baseFile.getName() + "\" due to filter rules [" + String.join(", ", filesExcluded) + "]";
                            _log.info("[I2PSnark] " + msg);
                            if (!_context.isRouterContext()) {System.out.println(" • " + msg);}
                        }
                        if (filesExcluded.size() > 5) {
                            _manager.addMessage(filesExcluded.size() + _t(" files or folders were excluded from \"{0}\" due to filter rules.", baseFile.getName()));
                        } else if (filesExcluded.size() > 0) {
                            _manager.addMessage(_t("The following files or folders were excluded from \"{0}\" due to filter rules: ",
                                                baseFile.getName()) + String.join(", ", filesExcluded));
                        }
                        _manager.addMessage(_t("Torrent created for \"{0}\"", baseFile.getName()) + " ➜  " + torrentFile.getAbsolutePath());
                        if (announceURL != null && !_manager.util().getOpenTrackers().contains(announceURL))
                            _manager.addMessage(_t("Many I2P trackers require you to register new torrents before seeding - please do so before starting \"{0}\"", baseFile.getName()));
                    } catch (IOException ioe) {
                        _manager.addMessage(_t("Error creating a torrent for \"{0}\"", baseFile.getAbsolutePath()) + ": " + ioe);
                        _log.error("Error creating a torrent", ioe);
                    }
                } else {
                    _manager.addMessage(_t("Cannot create a torrent for the nonexistent data: {0}", baseFile.getAbsolutePath()));
                }
            } else {
                _manager.addMessage(_t("Error creating torrent - you must enter a file or directory"));
            }
        } else if ("StopAll".equals(action)) {
            String search = req.getParameter("search");
            if (search != null && search.length() > 0) {
                List<Snark> matches = search(search, _manager.getTorrents());
                if (matches != null) {
                    for (Snark snark : matches) {_manager.stopTorrent(snark, false);}
                    return;
                }
            }
            _manager.stopAllTorrents(false);
        } else if ("StartAll".equals(action)) {
            String search = req.getParameter("search");
            if (search != null && search.length() > 0) {
                List<Snark> matches = search(search, _manager.getTorrents());
                if (matches != null) {
                    // TODO thread it
                    int count = 0;
                    for (Snark snark : matches) {
                        if (!snark.isStopped()) {continue;}
                        _manager.startTorrent(snark);
                        if ((count++ & 0x0f) == 15) {
                            // try to prevent OOMs
                            try {Thread.sleep(200);}
                            catch (InterruptedException ie) {}
                        }
                    }
                    return;
                }
            }
            _manager.startAllTorrents();
        } else if ("Clear".equals(action)) {
            String sid = req.getParameter("id");
            if (sid != null) {
                try {
                    int id = Integer.parseInt(sid);
                    _manager.clearMessages(id);
                } catch (NumberFormatException nfe) {}
            }
        } else {_manager.addMessage("Unknown POST action: \"" + action + '\"');}
    }

    /**
     *  Redirect a POST to a GET (P-R-G), preserving the peer string
     *  @since 0.9.5
     */
    private void sendRedirect(HttpServletRequest req, HttpServletResponse resp, String p) throws IOException {
        String url = req.getRequestURL().toString();
        StringBuilder buf = new StringBuilder(128);
        if (url.endsWith("_post")) {url = url.substring(0, url.length() - 5);}
        buf.append(url);
        if (p.length() > 0) {buf.append(p.replace("&amp;", "&"));}  // no you don't html escape the redirect header
        resp.setHeader("Location", buf.toString());
        resp.setStatus(303);
        resp.getOutputStream().close();
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
                 if (!(o instanceof String))
                     continue;
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
                     if ("1".equals(val))
                         open.add(k);
                     else if ("2".equals(val))
                         priv.add(k);
                }
            }
            if (changed) {
                _manager.saveTrackerMap();
            }

            open.removeAll(removed);
            List<String> oldOpen = new ArrayList<String>(_manager.util().getOpenTrackers());
            Collections.sort(oldOpen);
            Collections.sort(open);
            if (!open.equals(oldOpen))
                _manager.saveOpenTrackers(open);

            priv.removeAll(removed);
            // open trumps private
            priv.removeAll(open);
            List<String> oldPriv = new ArrayList<String>(_manager.getPrivateTrackers());
            Collections.sort(oldPriv);
            Collections.sort(priv);
            if (!priv.equals(oldPriv))
                _manager.savePrivateTrackers(priv);

        } else if (action.equals(_t("Add tracker"))) {
            String name = req.getParameter("tname");
            String hurl = req.getParameter("thurl");
            String aurl = req.getParameter("taurl");
            if (name != null && hurl != null && aurl != null) {
                name = DataHelper.stripHTML(name.trim());
                hurl = DataHelper.stripHTML(hurl.trim());
                if (!hurl.startsWith("http://")) {hurl = "http://" + hurl;} // Add http:// if not present
                aurl = DataHelper.stripHTML(aurl.trim()).replace("=", "&#61;");
                if (!aurl.startsWith("http://")) {aurl = "http://" + aurl;}  // Add http:// if not present
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
                } else {
                    _manager.addMessage(_t("Enter valid tracker name and URLs"));
                }
            } else {
                _manager.addMessage(_t("Enter valid tracker name and URLs"));
            }
        } else if (action.equals(_t("Restore defaults"))) {
            _manager.setDefaultTrackerMap();
            _manager.saveOpenTrackers(null);
            _manager.addMessage(_t("Restored default trackers"));
        } else {
            _manager.addMessage("Unknown POST action: \"" + action + '\"');
        }
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
                if (!(o instanceof String))
                    continue;
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

                if (filterType == null) {
                    filterType = oldFilterType;
                }

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
            } else {
                _manager.addMessage(_t("Enter valid name and filter pattern"));
            }
        } else if (action.equals(_t("Restore defaults"))) {
            _manager.setDefaultTorrentCreateFilterMap();
            _manager.addMessage(_t("Restored default torrent create filters"));
        } else {
            _manager.addMessage("Unknown POST action: \"" + action + '\"');
        }
    }

    private static final String iopts[] = {"inbound.length", "inbound.quantity",
                                           "outbound.length", "outbound.quantity" };

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

    private static final int MAX_DISPLAYED_FILENAME_LENGTH = 255;
    private static final int MAX_DISPLAYED_ERROR_LENGTH = 43;

    private boolean snarkMatchesFilter(Snark s, String filter) {
        String snarkStatus = this.snarkStatus;
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
     *  Display one snark (one line in table, unless showPeers is true)
     *
     *  @param stats in/out param (totals)
     *  @param statsOnly if true, output nothing, update stats only
     *  @param canWrite is the i2psnark data directory writable?
     */
    private String snarkStatus;
    private String filterParam;
    private boolean filterEnabled;
    private String sortParam;
    private boolean sortEnabled;
    private void displaySnark(PrintWriter out, HttpServletRequest req,
                              Snark snark, String uri, int row, long stats[], boolean showPeers,
                              boolean isDegraded, boolean noThinsp, boolean showDebug, boolean statsOnly,
                              boolean showRatios, boolean canWrite) throws IOException {
        // stats
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
        if (total > 0) {stats[5] += total;}
        if (statsOnly) {return;}

        String basename = snark.getBaseName();
        String fullBasename = basename;
        if (basename.length() > MAX_DISPLAYED_FILENAME_LENGTH) {
            String start = ServletUtil.truncate(basename, MAX_DISPLAYED_FILENAME_LENGTH);
            if (start.indexOf(' ') < 0 && start.indexOf('-') < 0) {basename = start + HELLIP;} // browser has nowhere to break it
        }
        // includes skipped files, -1 for magnet mode
        long remaining = snark.getRemainingLength();
        if (remaining > total) {remaining = total;}
        // does not include skipped files, -1 for magnet mode or when not running.
        long needed = snark.getNeededLength();
        if (needed > total) {needed = total;}
        long remainingSeconds;
        if (downBps > 0 && needed > 0) {remainingSeconds = needed / downBps;}
        else {remainingSeconds = -1;}

        MetaInfo meta = snark.getMetaInfo();
        String b64 = Base64.encode(snark.getInfoHash());
        String b64Short = b64.substring(0, 6);
        // isValid means isNotMagnet
        boolean isValid = meta != null;
        boolean isMultiFile = isValid && meta.getFiles() != null;

        String err = snark.getTrackerProblems();
        int knownPeers = Math.max(curPeers, snark.getTrackerSeenPeers());
        filterParam = req.getParameter("filter");
        filterEnabled = filterParam != null && !filterParam.equals("all") && !filterParam.equals("");
        sortParam = req.getParameter("sort");
        sortEnabled = sortParam != null && !sortParam.equals("");

        String statusString;
        // add status to table rows so we can selectively show/hide snarks and style based on status
        //String snarkStatus;
        String rowClass = (row % 2 == 0 ? "rowEven" : "rowOdd");
        if (snark.isChecking()) {
            (new DecimalFormat("0.00%")).format(snark.getCheckingProgress());
            statusString = toSVGWithDataTooltip("processing", "", _t("Checking")) + "</td>" + "<td class=peerCount><b><span class=right>" +
                           curPeers + "</span>" + thinsp(noThinsp) + "<span class=left>" + knownPeers + "</span>";
            snarkStatus = "active starting processing";
        } else if (snark.isAllocating()) {
            statusString = toSVGWithDataTooltip("processing", "", _t("Allocating")) + "</td>" + "<td class=peerCount><b>";
            snarkStatus = "active starting processing";
        } else if (err != null && isRunning && curPeers == 0) {
            statusString = toSVGWithDataTooltip("error", "", err) + "</td>" + "<td class=peerCount><b><span class=right>" +
                           curPeers + "</span>" + thinsp(noThinsp) + "<span class=left>" + knownPeers + "</span>";
            snarkStatus = "inactive downloading incomplete neterror";
        } else if (snark.isStarting()) {
            statusString = toSVGWithDataTooltip("stalled", "", _t("Starting")) + "</td>" + "<td class=peerCount><b>";
            snarkStatus = "active starting";
        } else if (remaining == 0 || needed == 0) { // < 0 means no meta size yet
            // partial complete or seeding
            if (isRunning) {
                String img;
                String txt;
                String tooltip;
                if (remaining == 0) {
                    img = "seeding";
                    txt = _t("Seeding");
                    tooltip = ngettext("Seeding to {0} peer", "Seeding to {0} peers", curPeers);
                    if (curPeers > 0 && upBps <= 0) {snarkStatus = "inactive seeding complete connected";}
                    else if (curPeers > 0) {
                        snarkStatus = "active seeding complete connected";
                        img = "seeding_active";
                    }
                } else {
                    // partial
                    img = "complete";
                    txt = _t("Complete");
                    tooltip = txt;
                    snarkStatus = "complete";
                    if (curPeers > 0) {
                        tooltip = txt + " (" + _t("Seeding to {0} of {1} peers in swarm", curPeers, knownPeers) + ")";
                        if (upBps > 0) {snarkStatus = "active seeding complete connected";}
                        else {snarkStatus = "inactive seeding complete connected";}
                    }
                }
                if (curPeers > 0) {
                    statusString = toSVGWithDataTooltip(img, "", tooltip) + "</td>" +
                                   "<td class=peerCount><b><span class=right>" + curPeers +
                                   "</span>" + thinsp(noThinsp) + "<span class=left>" + knownPeers + "</span>";
                    if (upBps > 0) {snarkStatus = "active seeding complete connected";}
                    else {snarkStatus = "inactive seeding complete connected";}
                } else {
                    statusString = toSVGWithDataTooltip(img, "", tooltip) + "</td>" +
                                   "<td class=peerCount><b><span class=right>" + curPeers +
                                   "</span>" + thinsp(noThinsp) + "<span class=left>" + knownPeers + "</span>";
                    if (upBps > 0 && curPeers > 0) {snarkStatus = "active seeding complete connected";}
                    else if (upBps <= 0 && curPeers > 0) {snarkStatus = "inactive seeding complete connected";}
                    else {snarkStatus = "inactive seeding complete";}
                }
            } else {
                statusString = toSVGWithDataTooltip("complete", "", _t("Complete")) + "</td>" + "<td class=peerCount><b>‒";
                snarkStatus = "inactive complete stopped zero";
            }
        } else {
            if (isRunning && curPeers > 0 && downBps > 0) {
                statusString = toSVGWithDataTooltip("downloading", "", _t("OK") + ", " +
                               ngettext("Downloading from {0} peer", "Downloading from {0} peers", curPeers)) + "</td>" +
                               "<td class=peerCount><b><span class=right>" + curPeers + "</span>" + thinsp(noThinsp) +
                               "<span class=left>" + knownPeers + "</span>";
                snarkStatus = "active downloading incomplete connected";
            } else if (isRunning && curPeers > 0) {
                statusString = toSVGWithDataTooltip("stalled", "", _t("Stalled") +
                               " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")") + "</td>" +
                               "<td class=peerCount><b><span class=right>" + curPeers + "</span>" + thinsp(noThinsp) +
                               "<span class=left>" + knownPeers + "</span>";
                snarkStatus = "inactive downloading incomplete connected";
            } else if (isRunning && knownPeers > 0) {
                statusString = toSVGWithDataTooltip("nopeers", "", _t("No Peers") +
                               " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")") + "</td>" +
                               "<td class=peerCount><b><span class=right>0</span>" +
                               thinsp(noThinsp) + "<span class=left>" + knownPeers + "</span>";
                snarkStatus = "inactive downloading incomplete nopeers";
            } else if (isRunning) {
                statusString = toSVGWithDataTooltip("nopeers", "", _t("No Peers")) + "</td>" +
                               "<td class=peerCount><b>‒";
                snarkStatus = "inactive downloading incomplete nopeers zero";
            } else {
                statusString = toSVGWithDataTooltip("stopped", "", _t("Stopped")) + "</td>" +
                               "<td class=peerCount><b>‒";
                snarkStatus = "inactive incomplete stopped zero";
            }
        }

        String rowStatus = (rowClass + ' ' + snarkStatus);
        StringBuilder buf = new StringBuilder(2*1024);
        if (!filterEnabled || snarkMatchesFilter(snark, filterParam)) {
            buf.append("<tr class=\"" + rowStatus + " volatile\">").append("<td class=status>").append(statusString);

            // link column
            buf.append("</b></td><td class=trackerLink>");
            if (isValid) {
                String announce = meta.getAnnounce();
                if (announce == null) {announce = snark.getTrackerURL();}
                if (announce != null) {
                    String trackerLink = getTrackerLink(announce, snark.getInfoHash()); // Link to tracker details page
                    if (trackerLink != null) {buf.append(trackerLink);}
                }
            }

            String encodedBaseName = encodePath(fullBasename);
            String hex = I2PSnarkUtil.toHex(snark.getInfoHash());
            // magnet column
            buf.append("</td><td class=magnet>");
            if (isValid && meta != null) {
                String announce = meta.getAnnounce();
                buf.append("<a class=magnetlink href=\"" + MagnetURI.MAGNET_FULL + hex);
                if (announce != null) {
                    buf.append("&amp;tr=" + announce);
                }
                if (encodedBaseName != null) {
                    buf.append("&amp;dn=" + encodedBaseName.replace(".torrent", "")
                       .replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"));
                }
                buf.append("\">" + toSVG("magnet", "") + "<span class=copyMagnet></span></a>");
            }

            // File type icon column
            buf.append("</td><td class=\"details");
            if (!isValid && !isMultiFile) {buf.append(" fetching");}
            else {buf.append(" data");}
            buf.append("\">");
            if (isValid) {
                CommentSet comments = snark.getComments();
                // Link to local details page - note that trailing slash on a single-file torrent
                // gets us to the details page instead of the file.
                buf.append("<span class=filetype><a href=\"").append(encodedBaseName)
                   .append("/\" title=\"").append(_t("Torrent details")).append("\">");
                if (comments != null && !comments.isEmpty()) {
                    buf.append("<span class=commented title=\"").append(_t("Torrent has comments")).append("\">");
                    toSVG(buf, "rateme", "", "");
                    buf.append("</span>");
                }
            }
            String icon;
            if (isMultiFile) {icon = "folder";}
            else if (isValid) {icon = toIcon(meta.getName());}
            else if (snark instanceof FetchAndAdd) {icon = "download";}
            else {icon = "magnet";}
            if (isValid) {buf.append(toSVG(icon)).append("</a></span>");}
            else {buf.append(toSVG(icon));}

            // Torrent name column
            buf.append("</td><td class=tName>");
            if (remaining == 0 || isMultiFile) {
                buf.append("<a href=\"").append(encodedBaseName);
                if (isMultiFile) {buf.append('/');}
                buf.append("\" title=\"");
                if (isMultiFile) {buf.append(_t("View files"));}
                else {buf.append(_t("Open file"));}
                buf.append("\">");
            }
            if (basename.contains("Magnet")) {
                buf.append(DataHelper.escapeHTML(basename)
                   .replace("Magnet ", "<span class=infohash>")
                   .replaceFirst("\\(", "</span> <span class=magnetLabel>").replaceAll("\\)$", ""));
                buf.append("</span>");
            } else {buf.append(DataHelper.escapeHTML(basename));}
            if (remaining == 0 || isMultiFile) {buf.append("</a>");}
            buf.append("</td><td class=ETA>");
            if (isRunning && remainingSeconds > 0 && !snark.isChecking()) {
                buf.append(DataHelper.formatDuration2(Math.max(remainingSeconds, 10) * 1000));
            } // (eta 6h)
            buf.append("</td>").append("<td class=rxd>");
            if (remaining > 0) {
                long percent = 100 * (total - remaining) / total;
                buf.append("<div class=barOuter>")
                   .append("<div class=barInner style=\"width: " + percent + "%;\">")
                   .append("<div class=barText tabindex=0 title=\"")
                   .append(percent + "% " + _t("complete") + "; " + formatSize(remaining) + ' ' + _t("remaining"))
                   .append("\">")
                   .append(formatSize(total-remaining).replaceAll("iB","") + thinsp(noThinsp) + formatSize(total).replaceAll("iB",""))
                   .append("</div></div></div>");
            } else if (remaining == 0) {
                // needs locale configured for automatic translation
                SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMM yyyy");
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                long[] dates = _manager.getSavedAddedAndCompleted(snark);
                String date = fmt.format(new Date(dates[1]));
                buf.append("<div class=barComplete title=\"")
                   .append(_t("Completed") + ": " + date + "\">")
                   .append(formatSize(total).replaceAll("iB","")) // 3GB
                   .append("</div>");
            }
            buf.append("</td>").append("<td class=\"rateDown");
            if (downBps >= 100000) {buf.append(" hundred");}
            else if (downBps >= 10000) {buf.append(" ten");}
            buf.append("\">");
            // we may only be uploading to peers, so hide when downrate <= 0
            if (isRunning && needed > 0 && downBps > 0 && curPeers > 0) {
                buf.append("<span class=right>")
                   .append(formatSize(downBps).replaceAll("iB", "")
                                              .replace("B", "</span><span class=left>B")
                                              .replace("K", "</span><span class=left>K")
                                              .replace("M", "</span><span class=left>M")
                                              .replace("G", "</span><span class=left>G"));
                buf.append("/s</span>");
            }
            buf.append("</td>").append("<td class=txd>");
            if (isValid) {
                double ratio = uploaded / ((double) total);
                if (total <= 0) {ratio = 0;}
                String txPercent = (new DecimalFormat("0")).format(ratio * 100);
                String txPercentBar = txPercent + "%";
                if (ratio > 1) {txPercentBar = "100%";}
                if (ratio <= 0.01 && ratio > 0) {txPercent = (new DecimalFormat("0.00")).format(ratio * 100);}
                if (showRatios) {
                    if (total > 0) {
                    buf.append("<span class=tx><span class=txBarText>").append(txPercent).append("&#8239;%")
                       .append("</span><span class=txBarInner style=\"width:calc(").append(txPercentBar)
                       .append(" - 2px)\"></span></span>");
                } else {
                    buf.append("‒");
                }
            } else if (uploaded > 0) {
                buf.append("<span class=tx title=\"").append(_t("Upload ratio").replace("Upload", "Share"))
                   .append(": ").append(txPercent).append("&#8239;%");
                SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMM yyyy");
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                Storage storage = snark.getStorage();
                long lastActive = storage.getActivity();
                String date = fmt.format(new Date(lastActive));
                if (storage != null) {buf.append(" &bullet; ").append(_t("Last activity")).append(": ").append(date);}
                buf.append("\"><span class=txBarText><span class=right>");
                buf.append(formatSize(uploaded).replaceAll("iB","")
                                               .replace("B", "</span><span class=left>B</span>")
                                               .replace("K", "</span><span class=left>K</span>")
                                               .replace("M", "</span><span class=left>M</span>")
                                               .replace("G", "</span><span class=left>G</span>")
                                               .replace("T", "</span><span class=left>T</span>"));
                buf.append("</span> <span class=txBarInner style=\"width:calc(").append(txPercentBar)
                   .append(" - 2px)\"></span></span>");
            }
        }
        buf.append("</td>").append("<td class=\"rateUp");
        if (upBps >= 100000) {buf.append(" hundred");}
        else if (upBps >= 10000) {buf.append(" ten");}
        buf.append("\">");
        if (isRunning && isValid && upBps > 0 && curPeers > 0) {
            buf.append("<span class=right>");
            buf.append(formatSize(upBps).replaceAll("iB","")
                                        .replace("B", "</span><span class=left>B")
                                        .replace("K", "</span><span class=left>K")
                                        .replace("M", "</span><span class=left>M")
                                        .replace("G", "</span><span class=left>G"));
            buf.append("/s</span>");
        }
        buf.append("</td>").append("<td class=tAction>");
        if (snark.isChecking()) {buf.append("<span class=isChecking></span>");} // show no buttons
        else if (isRunning) {
            // Stop Button
            buf.append("<input type=submit class=actionStop name=\"action_Stop_").append(b64).append("\" value=\"")
               .append(_t("Stop")).append("\" title=\"").append(_t("Stop the torrent").replace(" the", "")).append("\">");
        } else if (!snark.isStarting()) {
            if (!_manager.isStopping()) {
                // Start Button
                buf.append("<input type=submit class=actionStart name=\"action_Start_").append(b64).append("\" value=\"")
                   .append(_t("Start")).append("\" title=\"").append(_t("Start the torrent").replace(" the", "")).append("\">");
            }
            if (isValid && canWrite) {
                // Remove Button
                buf.append("<input type=submit class=actionRemove name=\"action_Remove_").append(b64).append("\" value=\"")
                   .append(_t("Remove")).append("\" title=\"").append(_t("Remove the torrent from the active list, deleting the .torrent file")
                   .replace("Remove the torrent from the active list, deleting the .torrent file", "Remove and delete torrent, retaining downloaded files"));
                buf.append("\" client=\"").append(escapeJSString(snark.getName()))
                   .append("\" data-name=\"").append(escapeJSString(snark.getBaseName())).append(".torrent\">");
            }

            // We can delete magnets without write privs
            if (!isValid || canWrite) {
                // Delete Button
                buf.append("<input type=submit class=actionDelete name=\"action_Delete_").append(b64).append("\" value=\"")
                   .append(_t("Delete")).append("\" title=\"").append(_t("Delete the .torrent file and the associated data files")
                   .replace("the .torrent file", "torrent file").replace("and the associated", "and associated"));
                buf.append("\" client=\"").append(escapeJSString(snark.getName()))
                   .append("\" data-name=\"").append(escapeJSString(snark.getBaseName())).append(".torrent\">");
            }
        }
        buf.append("</td></tr>\n");

        if (showPeers && isRunning && curPeers > 0) {
            List<Peer> peers = snark.getPeerList();
            //if (!showDebug) {Collections.sort(peers, new PeerComparator());}
            Collections.sort(peers, new PeerComparator());
            for (Peer peer : peers) {
                long t = peer.getInactiveTime();
                if ((peer.getUploadRate() > 0 || peer.getDownloadRate() > 0) && t < 60 * 1000) {
                    snarkStatus = "active";
                    if (peer.getUploadRate() > 0 && !peer.isInteresting() && !peer.isChoking())
                        snarkStatus += " TX";
                    if (peer.getDownloadRate() > 0 && !peer.isInterested() && !peer.isChoked())
                        snarkStatus += " RX";
                } else {snarkStatus = "inactive";}
                if (!peer.isConnected()) {continue;}
                buf.append("<tr class=\"peerinfo ").append(snarkStatus).append(" volatile\">\n<td class=status title=\"")
                   .append(_t("Peer attached to swarm")).append("\"></td><td class=peerdata colspan=5>");
                PeerID pid = peer.getPeerID();
                String client = null;
                String ch = pid != null ? pid.toString() : "????";
                if (ch.startsWith("WebSeed@")) {buf.append(ch);}
                else {
                    // most clients start -xx, see
                    // BT spec or libtorrent identify_client.cpp
                    // Base64 encode -xx
                    // Anything starting with L is -xx and has an Az version
                    // snark is 9 nulls followed by 3 3 3 (binary), see Snark
                    // PeerID.toString() skips nulls
                    // Base64 encode '\3\3\3' = AwMD
                    boolean addVersion = true;
                    ch = ch.substring(0, 4);
                    String version = ("ZV".equals(ch.substring(2,4)) || "VUZP".equals(ch) ? getRobtVersion(pid.getID()) : getAzVersion(pid.getID()));
                    boolean hasVersion = version != null && !version.equals("");
                    buf.append("<span class=peerclient><code title=\"").append(_t("Destination (identity) of peer")).append("\">")
                       .append(peer.toString().substring(5, 9)).append("</code>&nbsp;");
                    if (hasVersion) {buf.append("<span class=clientid title=\"").append(_t("Version")).append(": ").append(version).append("\">");}
                    else {buf.append("<span class=clientid>");}
                    if ("AwMD".equals(ch)) {client = "I2PSnark";}
                    else if ("LUFa".equals(ch)) {client = "Vuze";}
                    else if ("LUJJ".equals(ch)) {client = "BiglyBT";}
                    else if ("LVhE".equals(ch)) {client = "XD";}
                    else if (ch.startsWith("LV")) {client = "Transmission";} // LVCS 1.0.2?; LVRS 1.0.4
                    else if ("LUtU".equals(ch)) {client = "KTorrent";}
                    else if ("LUVU".equals(ch)) {client = "EepTorrent";}

                    // libtorrent and downstreams
                    // https://www.libtorrent.org/projects.html
                    else if ("LURF".equals(ch)) {client = "Deluge";} // DL
                    else if ("LXFC".equals(ch)) {client = "qBittorrent";} // qB
                    else if ("LUxU".equals(ch)) {client = "libtorrent";} // LT
                    // ancient below here
                    else if ("ZV".equals(ch.substring(2,4)) || "VUZP".equals(ch)) {client = "Robert";}
                    else if ("CwsL".equals(ch)) {client = "I2PSnarkXL";}
                    else if ("BFJT".equals(ch)) {client = "I2PRufus";}
                    else if ("TTMt".equals(ch)) {client = "I2P-BT";}
                    else {
                        // get client + version from handshake when client = null;
                        Map<String, BEValue> handshake = peer.getHandshakeMap();
                        if (handshake != null) {
                            BEValue bev = handshake.get("v");
                            if (bev != null) {
                                try {
                                    String s = bev.getString();
                                    if (s.length() > 0) {
                                        if (s.length() > 64) {s = s.substring(0, 64);}
                                        client = DataHelper.escapeHTML(s);
                                        addVersion = false;
                                    }
                                 } catch (InvalidBEncodingException ibee) {}
                             }
                         }
                         if (client == null) {client = ch;}
                    }
                    buf.append(client + "</span></span>");
                }
                if (t >= 5000) {
                    buf.append("<span class=inactivity style=\"width:").append(t / 2000)
                       .append("px\" title=\"").append(_t("Inactive")).append(": ")
                       .append(t / 1000).append(' ').append(_t("seconds")).append("\"></span>");
                }
                buf.append("</td>").append("<td class=ETA></td>").append("<td class=rxd>");
                float pct;
                if (isValid) {
                    pct = (float) (100.0 * peer.completed() / meta.getPieces());
                    if (pct >= 100.0) {
                        buf.append("<span class=\"peerSeed\" title=\"").append(_t("Seed")).append("\">")
                           .append(toSVG("peerseed", _t("Seed"), "")).append("</span>");
                    } else {
                        String ps = String.valueOf(pct);
                        if (ps.length() > 5) {ps = ps.substring(0, 5);}
                        buf.append("<div class=barOuter title=\"").append(ps).append("%\">")
                           .append("<div class=barInner style=\"width:").append(ps).append("%;\">")
                           .append("</div></div>");
                    }
                } else {pct = (float) 101.0;} // until we get the metainfo we don't know how many pieces there are
                buf.append("</td>");
                buf.append("<td class=\"rateDown");
                if (peer.getDownloadRate() >= 100000) {buf.append(" hundred");}
                else if (peer.getDownloadRate() >= 10000) {buf.append(" ten");}
                buf.append("\">");
                if (needed > 0) {
                    if (peer.isInteresting() && !peer.isChoked() && peer.getDownloadRate() > 0) {
                        buf.append("<span class=unchoked><span class=right>");
                        buf.append(formatSize(peer.getDownloadRate())
                                                  .replace("iB","")
                                                  .replace("B", "</span><span class=left>B")
                                                  .replace("K", "</span><span class=left>K")
                                                  .replace("M", "</span><span class=left>M")
                                                  .replace("G", "</span><span class=left>G"));
                        buf.append("/s</span></span>");
                    } else if (peer.isInteresting() && !peer.isChoked()) {
                        buf.append("<span class=\"unchoked idle\"></span>");
                    } else {
                        buf.append("<span class=choked title=\"");
                        if (!peer.isInteresting()) {
                            buf.append(_t("Uninteresting (The peer has no pieces we need)"));
                        } else {
                            buf.append(_t("Choked (The peer is not allowing us to request pieces)"));
                        }
                        buf.append("\"><span class=right>");
                        buf.append(formatSize(peer.getDownloadRate())
                                                  .replace("iB","")
                                                  .replace("B", "</span><span class=left>B")
                                                  .replace("K", "</span><span class=left>K")
                                                  .replace("M", "</span><span class=left>M")
                                                  .replace("G", "</span><span class=left>G"));
                        buf.append("/s</span></span>");
                    }
                } else if (!isValid) {
                        buf.append("<span class=unchoked><span class=right>");
                        buf.append(formatSize(peer.getDownloadRate())
                                                  .replace("iB","")
                                                  .replace("B", "</span><span class=left>B")
                                                  .replace("K", "</span><span class=left>K")
                                                  .replace("M", "</span><span class=left>M")
                                                  .replace("G", "</span><span class=left>G"));
                        buf.append("/s</span></span>");
                }
                buf.append("</td>").append("<td class=txd>").append("</td>").append("<td class=\"rateUp");
                if (peer.getUploadRate() >= 100000) {buf.append(" hundred");}
                else if (peer.getUploadRate() >= 10000) {buf.append(" ten");}
                buf.append("\">");
                if (isValid && pct < 100.0) {
                    if (peer.isInterested() && !peer.isChoking() && peer.getUploadRate() > 0) {
                        buf.append("<span class=unchoked>");
                        String sizeStr = formatSize(peer.getUploadRate())
                                                        .replace("iB","")
                                                        .replace("B", "<span class=left>B")
                                                        .replace("K", "<span class=left>K")
                                                        .replace("M", "<span class=left>M")
                                                        .replace("G", "<span class=left>G");
                        buf.append("<span class=right>").append(sizeStr).append("</span>");
                        buf.append("</span>");
                    } else if (peer.isInterested() && !peer.isChoking()) {
                        buf.append("<span class=\"unchoked idle\" title=\"")
                           .append(_t("Peer is interested but currently idle")).append("\">")
                           .append("</span>");
                    } else {
                        buf.append("<span class=choked title=\"");
                        if (!peer.isInterested()) {
                            buf.append(_t("Uninterested (We have no pieces the peer needs)"));
                        } else {
                            buf.append(_t("Choking (We are not allowing the peer to request pieces)"));
                        }
                        buf.append("\">");
                        String sizeStr = formatSize(peer.getUploadRate())
                                                  .replace("iB","")
                                                  .replace("B", "<span class=left>B")
                                                  .replace("K", "<span class=left>K")
                                                  .replace("M", "<span class=left>M")
                                                  .replace("G", "<span class=left>G");
                        buf.append("<span class=right>").append(sizeStr).append("</span>");
                        buf.append("</span>");
                    }
                }

                buf.append("</td>").append("<td class=tAction>").append("</td></tr>\n");
/**
                if (showDebug) {buf.append("<tr class=\"debuginfo volatile ").append(rowClass).append("\">\n");}
                else {buf.append("<tr class=\"debuginfo volatile ").append(rowClass).append("\" hidden>\n");}
                buf.append("<td class=status></td><td colspan=12>")
                   .append(peer.getSocket().replaceAll("Connection", "<b>Connection</b>").replaceAll(";", " &bullet;").replaceAll("\\* ", "")
                                           .replaceAll("from", "<span class=from>⇦</span>").replaceAll("to", "<span class=to>⇨</span>"))
                   .append("</td></tr>");
**/
            }
        }
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
        }
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
            int diff = r.completed() - l.completed();      // reverse
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
              announce.startsWith("http://ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p/") ||
              announce.startsWith("http://torrfreedom.i2p/") || announce.startsWith("http://ZgNqT5tv") ||
              announce.startsWith("http://nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p/"))) {
            for (Tracker t : _manager.getTrackers()) {
                String aURL = t.announceURL;
                if (!(aURL.startsWith(announce) || // vvv hack for non-b64 announce in list vvv
                      (announce.startsWith("http://lnQ6yoBT") && aURL.startsWith("http://tracker2.postman.i2p/")) ||
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
            buf.append(toSVG("link", _t("Info"), ""));
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
        if (announce.startsWith("http://"))
            announce = announce.substring(7);
        else if (announce.startsWith("https://"))
            announce = announce.substring(8);
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
        // strip path
        int slsh = announce.indexOf('/');
        if (slsh > 0)
            announce = announce.substring(0, slsh);
        if (trackerLinkUrl != null) {
            buf.append(trackerLinkUrl);
        } else {
            // browsers don't like a full b64 dest, so convert it to b32
            String host = announce;
            if (host.length() >= 516) {
                int colon = announce.indexOf(':');
                String port = "";
                if (colon > 0) {
                    port = host.substring(colon);
                    host = host.substring(0, colon);
                }
                if (host.endsWith(".i2p"))
                    host = host.substring(0, host.length() - 4);
                byte[] b = Base64.decode(host);
                if (b != null) {
                    Hash h = _context.sha().calculateHash(b);
                    // should we add the port back or strip it?
                    host = Base32.encode(h.getData()) + ".b32.i2p" + port;
                }
            }
            int space = host.indexOf(" ");
            if (!host.endsWith("[ext]") || host.contains(".i2p"))
                buf.append("<a href=\"http://").append(urlEncode(host)).append("/\" target=_blank>");
            else
                host = host.substring(0, space);
        }
        // strip port
        int colon = announce.indexOf(':');
        if (colon > 0)
            announce = announce.substring(0, colon);
        if (announce.length() > 67)
            announce = DataHelper.escapeHTML(announce.substring(0, 40)) + "&hellip;" +
                       DataHelper.escapeHTML(announce.substring(announce.length() - 8));
        if (announce.endsWith(".i2p") && !announce.endsWith(".b32.i2p")) {
            announce = announce.replace(".i2p", "");
            if (announce.equals("tracker2.postman"))
                announce = "postman";
            if (announce.startsWith("tracker."))
                announce = announce.substring(8, announce.length());
            if (announce.startsWith("opentracker."))
                announce = announce.substring(12, announce.length());
        }
        buf.append(announce);
        buf.append("</a>");
        return buf.toString();
    }

    private void writeAddForm(PrintWriter out, HttpServletRequest req) throws IOException {
        // display incoming parameter if a GET so links will work
        StringBuilder buf = new StringBuilder(1024);
        String newURL = req.getParameter("nofilter_newURL");
        if (newURL == null || newURL.trim().length() <= 0 || req.getMethod().equals("POST"))
            newURL = "";
        else
            newURL = DataHelper.stripHTML(newURL); // XSS

        buf.append("<div id=add class=snarkNewTorrent>\n");
        buf.append("<form id=addForm action=\"_post\" method=POST enctype=\"multipart/form-data\" accept-charset=\"UTF-8\" target=processForm>\n");
        buf.append("<div class=sectionPanel id=addSection>\n");
        writeHiddenInputs(buf, req, "Add");
        buf.append("<input hidden class=toggle_input id=toggle_addtorrent type=checkbox");
        if (newURL.length() > 0) {
            buf.append(" checked=checked>"); // force toggle open
        } else {
            buf.append('>');
        }
        buf.append("<label id=tab_addtorrent class=toggleview for=\"toggle_addtorrent\"><span class=tab_label>")
           .append(_t("Add Torrent"))
           .append("</span></label>")
           .append("<hr>\n<table border=0><tr><td>")
           .append(_t("From URL"))
           .append(":<td><input id=addTorrentURL type=text name=nofilter_newURL size=85 value=\"" + newURL + "\" spellcheck=false")
           .append(" title=\"")
           .append(_t("Enter the torrent file download URL (I2P only), magnet link, or info hash"))
           .append("\" required>\n")
           .append("<input type=submit class=add value=\"")
           .append(_t("Add torrent"))
           .append("\" name=foo><br>\n<tr hidden><td>")
           .append(_t("Torrent file"))
           .append(":<td><input type=\"file\" name=\"newFile\" accept=\".torrent\"/><tr><td>")
           .append(_t("Data dir"))
           .append(":<td><input type=text name=nofilter_newDir size=85 value=\"")
           .append(_manager.getDataDir().getAbsolutePath()).append("\" spellcheck=false")
           .append(" title=\"")
           .append(_t("Enter the directory to save the data in (default {0})", _manager.getDataDir().getAbsolutePath()))
           .append("\"></td></tr>\n</table>\n")
           .append("<div id=addNotify class=notify hidden><table><tr><td></td></tr></table></div>\n")
           .append("</div>\n</form>\n</div>\n");

        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private void writeSeedForm(PrintWriter out, HttpServletRequest req, List<Tracker> sortedTrackers, List<TorrentCreateFilter> sortedFilters) throws IOException {
        StringBuilder buf = new StringBuilder(3*1024);
        String resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
        buf.append("<div class=sectionPanel id=createSection>\n<div>\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        buf.append("<form id=createForm action=\"_post\" method=POST target=processForm>\n");
        writeHiddenInputs(buf, req, "Create");
        buf.append("<input hidden class=toggle_input id=toggle_createtorrent type=checkbox>")
           .append("<label id=tab_newtorrent class=toggleview for=\"toggle_createtorrent\"><span class=tab_label>")
           .append(_t("Create Torrent"))
           .append("</span></label><hr>\n<table border=0>")
        //buf.append("From file: <input type=file name=\"newFile\" size=50 value=\"" + newFile + "\" /><br>\n");
           .append("<tr><td>").append(_t("Data to seed")).append(":</td>")
           .append("<td><input id=createTorrentFile type=text name=nofilter_baseFile size=85 value=\"").append("\" spellcheck=false title=\"")
           .append(_t("File or directory to seed (full path or within the directory {0} )",
                   _manager.getDataDir().getAbsolutePath() + File.separatorChar))
           .append("\" required> <input type=submit class=create value=\"").append(_t("Create torrent"))
           .append("\" name=foo>").append("</td></tr>\n")
           .append("<tr id=createTorrentFilters title=\"").append(_t("Exclude files from the torrent if they reside in the torrent folder")).append("\">")
           .append("<td>").append(_t("Content Filters")).append(":</td>")
           .append("<td><div id=contentFilter>");

        for (TorrentCreateFilter f : sortedFilters) {
           String name = f.name;
           String nameUnderscore = name.replace(" ", "_");
           String pattern = f.filterPattern;
           String type = f.filterType;
           String filterTypeLabel = type.replace("_", " ");
           boolean isDefault = f.isDefault;
           buf.append("<input type=checkbox id=").append(nameUnderscore).append(" name=filters")
              .append(" value=\"").append(name).append("\"").append(isDefault ? " checked" : "").append(" hidden>")
              .append("<label for=\"").append(nameUnderscore).append("\" class=\"createFilterToggle ").append(type)
              .append("\" title=\"Filter pattern: (").append(filterTypeLabel).append(") ").append(pattern).append("\">")
              .append(name).append("</label>");
        }

        buf.append("</div></td></tr>")
           .append("<tr><td>").append(_t("Trackers")).append(":</td>")
           .append("<td>\n<table id=trackerselect>\n")
           .append("<tr><td>Name</td><td>").append(_t("Primary")).append("</td><td>")
           .append(_t("Alternates")).append("</td><td>").append(_t("Tracker Type")).append("</td></tr>\n");

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
        buf.append("></td><td></td><td></td></tr>\n</table>\n")
           .append("</td></tr>\n</table>\n</form>\n</div>\n")
           .append("<div id=createNotify class=notify hidden><table><tr><td></td></tr></table></div>\n")
           .append("</div>\n");

        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private static final int[] times = { 5, 15, 30, 60, 2*60, 5*60, 10*60, 30*60, 60*60, -1 };

    private void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String dataDir = _manager.getDataDir().getAbsolutePath();
        String lang = (Translate.getLanguage(_manager.util().getContext()));
        boolean filesPublic = _manager.areFilesPublic();
        boolean autoStart = _manager.shouldAutoStart();
        boolean smartSort = _manager.isSmartSortEnabled();
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

        buf.append("<tr><th class=suboption>")
           .append(_t("User Interface"));
        if (_context.isRouterContext()) {
            buf.append("&nbsp;&nbsp;<a href=/torrentmgr target=_top class=script id=embed>")
               .append(_t("Switch to Embedded Mode")).append("</a>")
               .append("<a href=\"").append(_contextPath).append("/configure\" target=_top class=script id=fullscreen>")
               .append(_t("Switch to Fullscreen Mode")).append("</a>");
        }
        buf.append("</th></tr>\n<tr><td>\n<div class=optionlist>\n")
           .append("<span class=configOption><b>")
           .append(_t("Theme"))
           .append("</b> \n");
        if (_manager.getUniversalTheming()) {
            buf.append("<select id=themeSelect name=theme disabled=disabled title=\"")
               .append(_t("To change themes manually, disable universal theming"))
               .append("\"><option>")
               .append(_manager.getTheme())
               .append("</option></select> <span id=bwHoverHelp>")
               .append(toThemeSVG("details", "", ""))
               .append("<span id=bwHelp>")
               .append(_t("Universal theming is enabled."))
               .append("</span></span>")
               .append(" <a href=\"/configui\" target=_blank>[")
               .append(_t("Configure"))
               .append("]</a></span><br>");
        } else {
            buf.append("<select id=themeSelect name=theme>");
            String theme = _manager.getTheme();
            String[] themes = _manager.getThemes();
            Arrays.sort(themes);
            for (int i = 0; i < themes.length; i++) {
                if(themes[i].equals(theme))
                    buf.append("\n<OPTION value=\"").append(themes[i]).append("\" SELECTED>").append(themes[i]);
                else
                    buf.append("\n<OPTION value=\"").append(themes[i]).append("\">").append(themes[i]);
            }
            buf.append("</select>\n</span><br>\n");
        }

        buf.append("<span class=configOption><b>")
           .append(_t("Refresh time"))
           .append("</b> \n<select name=refreshDelay title=\"")
           .append(_t("How frequently torrent status is updated on the main page"))
           .append("\">");
        int delay = _manager.getRefreshDelaySeconds();
        for (int i = 0; i < times.length; i++) {
            buf.append("<option value=\"")
               .append(Integer.toString(times[i]))
               .append("\"");
            if (times[i] == delay) {
                buf.append(" selected=selected");
            }
            buf.append(">");
            if (times[i] > 0)
                buf.append(DataHelper.formatDuration2(times[i] * 1000));
            else
                buf.append(_t("Never"));
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

        if (!_context.isRouterContext()) {
            try {
                // class only in standalone builds
                Class<?> helper = Class.forName("org.klomp.snark.standalone.ConfigUIHelper");
                Method getLangSettings = helper.getMethod("getLangSettings", I2PAppContext.class);
                String langSettings = (String) getLangSettings.invoke(null, _context);
                // If we get to here, we have the language settings
                buf.append("<span class=configOption><b>").append(_t("Language")).append("</b> ")
                   .append(langSettings).append("</span><br>\n");
            } catch (ClassNotFoundException e) {
            } catch (NoSuchMethodException e) {
            } catch (IllegalAccessException e) {
            } catch (InvocationTargetException e) {
            }
        } else {
            buf.append("<span class=configOption><b>").append(_t("Language")).append("</b> ")
               .append("<span id=snarkLang>").append(lang).append("</span> ")
               .append("<a href=\"/configui#langheading\" target=_blank>").append("[").append(_t("Configure")).append("]</a>")
               .append("</span><br>\n");
        }

        buf.append("<span class=configOption><label for=\"smartSort\"><b>")
           .append(_t("Smart torrent sorting"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=smartSort id=smartSort ")
           .append((smartSort ? "checked " : ""))
           .append("title=\"")
           .append(_t("Ignore words such as 'a' and 'the' when sorting"))
           .append("\" ></span><br>\n")
           .append("<span class=configOption><label for=\"collapsePanels\"><b>")
           .append(_t("Collapsible panels"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=collapsePanels id=collapsePanels ")
           .append((collapsePanels ? "checked " : ""))
           .append("title=\"");
        if (noCollapse) {
            String ua = req.getHeader("user-agent");
            buf.append(_t("Your browser does not support this feature.")).append("[" + ua + "]").append("\" disabled=\"disabled");
        } else {
            buf.append(_t("Allow the 'Add Torrent' and 'Create Torrent' panels to be collapsed, and collapse by default in non-embedded mode"));
        }
        buf.append("\"></span><br>\n")
           .append("<span class=configOption><label for=\"showStatusFilter\"><b>")
           .append(_t("Torrent filter bar"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=showStatusFilter id=showStatusFilter ")
           .append((showStatusFilter ? "checked " : ""))
           .append("title=\"")
           .append(_t("Show filter bar above torrents for selective display based on status"))
           .append(" (").append(_t("requires javascript")).append(")")
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=\"enableLightbox\"><b>")
           .append(_t("Enable lightbox"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" ")
           .append("name=enableLightbox id=enableLightbox ")
           .append((enableLightbox ? "checked " : ""))
           .append("title=\"")
           .append(_t("Use a lightbox to display images when thumbnails are clicked"))
           .append(" (").append(_t("requires javascript")).append(")")
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=\"enableAddCreate\"><b>")
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
           .append("<span class=configOption><label for=\"ratings\"><b>")
           .append(_t("Enable Ratings"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=ratings id=ratings ")
           .append(useRatings ? "checked " : "")
           .append("title=\"")
           .append(_t("Show ratings on torrent pages"))
           .append("\" ></span><br>\n")
           .append("<span class=configOption><label for=\"comments\"><b>")
           .append(_t("Enable Comments"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=comments id=comments ")
           .append(useComments ? "checked " : "")
           .append("title=\"")
           .append(_t("Show comments on torrent pages"))
           .append("\" ></span><br>\n")
           .append("<span class=configOption id=configureAuthor><label><b>")
           .append(_t("Comment Author"))
           .append("</b> <input type=text name=nofilter_commentsName spellcheck=false value=\"")
           .append(DataHelper.escapeHTML(_manager.util().getCommentsName())).append("\" size=15 maxlength=16 title=\"")
           .append(_t("Set the author name for your comments and ratings"))
           .append("\" ></label></span>\n")
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
           .append("<span class=configOption><label for=\"useOpenTrackers\"><b>")
           .append(_t("Use open trackers also").replace(" also", ""))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=useOpenTrackers id=useOpenTrackers ")
           .append(useOpenTrackers ? "checked " : "")
           .append("title=\"")
           .append(_t("Announce torrents to open trackers as well as trackers listed in the torrent file"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=\"useDHT\"><b>")
           .append(_t("Enable DHT"))
           .append("</b></label> <input type=checkbox class=\"optbox slider\" name=useDHT id=useDHT ")
           .append(useDHT ? "checked " : "")
           .append("title=\"")
           .append(_t("Use DHT to find additional peers"))
           .append("\"></span><br>\n")
           .append("<span class=configOption><label for=\"autoStart\"><b>")
           .append(_t("Auto start torrents"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" name=autoStart id=autoStart ")
           .append(autoStart ? "checked " : "")
           .append("title=\"")
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

        buf.append("<tr><th class=suboption>")
           .append(_t("Data Storage"))
           .append("</th></tr><tr><td>\n<div class=optionlist>\n")
           .append("<span class=configOption><label><b>")
           .append(_t("Data directory"))
           .append("</b> <input type=text name=nofilter_dataDir size=60").append(" title=\"")
           .append(_t("Directory where torrents and downloaded/shared files are stored"))
           .append("\" value=\"").append(DataHelper.escapeHTML(dataDir)).append("\" spellcheck=false></label></span><br>\n")
           .append("<span class=configOption><label for=\"filesPublic\"><b>")
           .append(_t("Files readable by all"))
           .append("</b> </label><input type=checkbox class=\"optbox slider\" name=filesPublic id=filesPublic ")
           .append(filesPublic ? "checked " : "").append("title=\"")
           .append(_t("Set file permissions to allow other local users to access the downloaded files"))
           .append("\" ></span>\n")
           .append("<span class=configOption><label for=\"maxFiles\"><b>")
           .append(_t("Max files per torrent"))
           .append("</b> <input type=text name=maxFiles size=5 maxlength=5 pattern=\"[0-9]{1,5}\" class=\"r numeric\"").append(" title=\"")
           .append(_t("Maximum number of files permitted per torrent - note that trackers may set their own limits, and your OS may limit the number of open files, preventing torrents with many files (and subsequent torrents) from loading"))
           .append("\" value=\"" + _manager.getMaxFilesPerTorrent() + "\" spellcheck=false disabled></label></span><br>\n")
           .append("</div></td></tr>\n");

/* i2cp/tunnel configuration */
        String resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
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
           .append(renderOptions(1, 16, SnarkManager.DEFAULT_TUNNEL_QUANTITY, options.remove("outbound.quantity"), "outbound.quantity", TUNNEL))
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
           .append("<script src=\"" + resourcePath + "js/toggleVaryTunnelLength.js?" + CoreVersion.VERSION + "\" defer></script>\n")
           .append("<noscript><style>#hopVariance .optbox.slider{pointer-events:none!important;opacity:.4!important}</style></noscript>\n");

        if (!_context.isRouterContext()) {
            buf.append("<span class=configOption><label><b>")
               .append(_t("I2CP host"))
               .append("</b> <input type=text name=i2cpHost value=\"")
               .append(_manager.util().getI2CPHost()).append("\" size=5></label></span><br>\n")
               .append("<span class=configOption><label><b>")
               .append(_t("I2CP port"))
               .append("</b> <input type=text name=i2cpPort value=\"")
               .append(_manager.util().getI2CPPort()).append("\" class=numeric size=5 maxlength=5 pattern=\"[0-9]{1,5}\" ></label></span><br>\n");
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
        buf.append("<span class=configOption id=i2cpOptions><label><b>")
           .append(_t("I2CP options"))
           .append("</b> <input type=text id=i2cpOpts name=i2cpOpts value=\"")
           .append(opts.toString().trim()).append("\" size=60></label></span>\n")
           .append("</div>\n</td></tr>\n")
           .append("<tr class=spacer><td></td></tr>\n");  // spacer

/* save config */

        buf.append("<tr><td><input type=submit class=accept value=\"")
           .append(_t("Save configuration"))
           .append("\" name=foo></td></tr>\n")
           .append("<tr class=spacer><td>&nbsp;</td></tr>\n")  // spacer
           .append("</table></div></div></form>");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    /** @since 0.9.62+ */
    private void writeTorrentCreateFilterForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(5*1024);
        buf.append("<form action=\"").append(_contextPath).append("/configure#navbar\" method=POST>\n")
           .append("<div class=configPanel id=fileFilter><div class=snarkConfig>\n");
        writeHiddenInputs(buf, req, "Save3");
        buf.append("<span id=filtersTitle class=\"configTitle expanded\">").append(_t("Torrent Create File Filtering")).append("</span><hr>\n")
           .append("<table hidden>\n<tr>")
           .append("<th title=\"").append(_t("Mark filter for deletion")).append("\"></th>")
           .append("<th>").append(_t("Name")).append("</th>")
           .append("<th>").append(_t("Filter Pattern")).append("</th>")
           .append("<th class=radio>").append(_t("Starts With")).append("</th>")
           .append("<th class=radio>").append(_t("Contains")).append("</th>")
           .append("<th class=radio>").append(_t("Ends With")).append("</th>")
           .append("<th>").append(_t("Enabled by Default")).append("</th>")
           .append("</tr>\n");
        for (TorrentCreateFilter f : _manager.getSortedTorrentCreateFilterStrings()) {
            boolean isDefault = f.isDefault;
            String filterType = f.filterType;
            String nameUnderscore = f.name.replace(" ", "_");
            buf.append("<tr class=createFilterString>")
               .append("<td><input type=checkbox class=optbox name=\"delete_").append(f.name).append("\"></td>")
               .append("<td>").append(f.name).append("</td>")
               .append("<td>").append(f.filterPattern).append("</td>")
               .append("<td>").append("<label class=filterStartsWith><input type=radio class=optbox value=\"starts_with\" name=\"filterType_")
               .append(nameUnderscore).append("\"").append(filterType.equals("starts_with") ? " checked" : "").append("></label></td>")
               .append("<td>").append("<label class=filterContains><input type=radio class=optbox value=\"contains\" name=\"filterType_")
               .append(nameUnderscore).append("\"").append(filterType.equals("contains") ? " checked" : "").append("></label></td>")
               .append("<td>").append("<label class=filterEndsWith><input type=radio class=optbox value=\"ends_with\" name=\"filterType_")
               .append(nameUnderscore).append("\"").append(filterType.equals("ends_with") ? " checked" : "").append("></label></td>")
               .append("<td><input type=checkbox class=optbox name=\"defaultEnabled_").append(f.name).append("\"");
            if (f.isDefault) {buf.append(" checked=checked");}
            buf.append("></td></tr>\n");
        }
        buf.append("<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n") // spacer
           .append("<tr id=addFileFilter>")
           .append("<td><b>").append(_t("Add")).append(":</b></td>")
           .append("<td><input type=text class=torrentCreateFilterName name=fname spellcheck=false></td>")
           .append("<td><input type=text class=torrentCreateFilterPattern name=filterPattern spellcheck=false></td>")
           .append("<td><label class=filterStartsWith><input type=radio class=optbox name=\"filterType\" value=\"starts_with\"></label></td>")
           .append("<td><label class=filterContains><input type=radio class=optbox name=\"filterType\" value=\"contains\" checked></label></td>")
           .append("<td><label class=filterEndsWith><input type=radio class=optbox name=\"filterType\" value=\"ends_with\"></label></td>")
           .append("<td><input type=checkbox class=optbox name=filterIsDefault></td>")
           .append("<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n") // spacer
           .append("<tr><td colspan=7>\n")
           .append("<input type=submit name=raction class=delete value=\"").append(_t("Delete selected")).append("\">\n")
           .append("<input type=submit name=raction class=accept value=\"").append(_t("Save Filter Configuration")).append("\">\n")
           .append("<input type=submit name=raction class=reload value=\"").append(_t("Restore defaults")).append("\">\n")
           .append("<input type=submit name=raction class=add value=\"").append(_t("Add File Filter")).append("\">\n")
           .append("</td></tr>\n")
           .append("<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n") // spacer
           .append("</table>\n</div>\n</div>\n</form>\n");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    /** @since 0.9 */
    private void writeTrackerForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(5*1024);
        buf.append("<form action=\"").append(_contextPath).append("/configure#navbar\" method=POST>\n")
           .append("<div class=configPanel id=trackers><div class=snarkConfig>\n");
        writeHiddenInputs(buf, req, "Save2");
        buf.append("<span id=trackersTitle class=\"configTitle expanded\">").append(_t("Trackers")).append("</span><hr>\n")
           .append("<table id=trackerconfig hidden>\n<tr>")
           .append("<th title=\"").append(_t("Select trackers for removal from I2PSnark's known list")).append("\"></th>")
           .append("<th>").append(_t("Name")).append("</th>")
           .append("<th>").append(_t("Website URL")).append("</th>")
           .append("<th class=radio>").append(_t("Standard")).append("</th>")
           .append("<th class=radio>").append(_t("Open")).append("</th>")
           .append("<th class=radio>").append(_t("Private")).append("</th>")
           .append("<th>").append(_t("Announce URL")).append("</th>")
           .append("</tr>\n");
        List<String> openTrackers = _manager.util().getOpenTrackers();
        List<String> privateTrackers = _manager.getPrivateTrackers();
        for (Tracker t : _manager.getSortedTrackers()) {
            String name = t.name;
            String homeURL = t.baseURL;
            String announceURL = t.announceURL.replace("&#61;", "=");
            boolean isPrivate = privateTrackers.contains(t.announceURL);
            boolean isKnownOpen = _manager.util().isKnownOpenTracker(t.announceURL);
            boolean isOpen = isKnownOpen || openTrackers.contains(t.announceURL);
            buf.append("<tr class=knownTracker><td><input type=checkbox class=optbox id=\"")
               .append(name).append("\" name=\"delete_")
               .append(name).append("\" title=\"").append(_t("Mark tracker for deletion")).append("\">")
               .append("</td><td><label for=\"").append(name).append("\">").append(name).append("</label></td><td>");
            if (homeURL.endsWith(".i2p/")) {homeURL = homeURL.substring(0, homeURL.length() - 1);}
            buf.append(urlify(homeURL, 64))
               .append("</td><td><input type=radio class=optbox value=\"0\" tabindex=-1 name=\"ttype_")
               .append(announceURL).append("\"");
            if (!(isOpen || isPrivate)) {buf.append(" checked=checked");}
            else if (isKnownOpen) {buf.append(" disabled=disabled");}
            buf.append("></td><td><input type=radio class=optbox value=1 tabindex=-1 name=\"ttype_")
               .append(announceURL).append("\"");
            if (isOpen) {buf.append(" checked=checked");}
            else if (t.announceURL.equals("http://diftracker.i2p/announce.php") ||
                     t.announceURL.equals("http://tracker2.postman.i2p/announce.php") ||
                     t.announceURL.equals("http://torrfreedom.i2p/announce.php")) {
                buf.append(" disabled=disabled");
            }
            buf.append("></td><td><input type=radio class=optbox value=2 tabindex=-1 name=\"ttype_")
               .append(announceURL).append("\"");
            if (isPrivate) {buf.append(" checked=checked");}
            else if (isKnownOpen ||
                     t.announceURL.equals("http://diftracker.i2p/announce.php") ||
                     t.announceURL.equals("http://tracker2.postman.i2p/announce.php") ||
                     t.announceURL.equals("http://torrfreedom.i2p/announce.php")) {
                buf.append(" disabled=disabled");
            }
            buf.append("></td><td>").append(urlify(announceURL, 64)).append("</td></tr>\n");
        }
        String resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
        buf.append("<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n")  // spacer
           .append("<tr id=addtracker><td><b>")
           .append(_t("Add")).append(":</b></td>")
           .append("<td><input type=text class=trackername name=tname spellcheck=false></td>")
           .append("<td><input type=text class=trackerhome name=thurl spellcheck=false></td>")
           .append("<td><input type=radio class=optbox value=\"0\" name=add_tracker_type checked=checked></td>")
           .append("<td><input type=radio class=optbox value=1 name=add_tracker_type></td>")
           .append("<td><input type=radio class=optbox value=2 name=add_tracker_type></td>")
           .append("<td><input type=text class=trackerannounce name=taurl spellcheck=false></td></tr>\n")
           .append("<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n") // spacer
           .append("<tr><td colspan=7>\n")
           .append("<input type=submit name=taction class=default value=\"")
           .append(_t("Add tracker")).append("\">\n")
           .append("<input type=submit name=taction class=delete value=\"")
           .append(_t("Delete selected")).append("\">\n")
           .append("<input type=submit name=taction class=accept value=\"")
           .append(_t("Save tracker configuration")).append("\">\n")
           .append("<input type=submit name=taction class=add value=\"")
           .append(_t("Add tracker")).append("\">\n")
           .append("<input type=submit name=taction class=reload value=\"")
           .append(_t("Restore defaults")).append("\">\n")
           .append("</td></tr>")
           .append("<tr class=spacer><td colspan=7>&nbsp;</td></tr>\n") // spacer
           .append("</table>\n</div>\n</div></form>\n")
           .append("<noscript><style>")
           .append(".configPanel .configTitle{pointer-events:none!important}")
           .append("#fileFilter table,#trackers table{display:table!important}")
           .append("#fileFilter .configTitle::after,#trackers .configTitle::after{display:none!important}")
           .append("</style></noscript>\n")
           .append("<script src=\"" + resourcePath + "js/toggleConfigs.js?" + CoreVersion.VERSION + "\"></script>\n");
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    private void writeConfigLink(PrintWriter out) throws IOException {
        StringBuilder buf = new StringBuilder(192);
        buf.append("<div id=configSection>\n<span class=snarkConfig>")
           .append("<span id=tab_config class=configTitle><a href=\"")
           .append(_contextPath).append("/configure\"><span class=tab_label>")
           .append(_t("Configuration"))
           .append("</span></a></span></span>\n</div>\n");
        out.write(buf.toString());
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

    /** modded from ConfigTunnelsHelper @since 0.7.14 */
    private String renderOptions(int min, int max, int dflt, String strNow, String selName, String name) {
        int now = dflt;
        try {
            now = Integer.parseInt(strNow);
        } catch (Throwable t) {}
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
            buf.append("<option value=\"").append(i).append("\" ");
            if (i == now)
                buf.append("selected=selected ");
            // constants to prevent tagging
            buf.append(">").append(ngettext(DUMMY1 + name, DUMMY0 + name + 's', i))
               .append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    /** translate */
    private String _t(String s) {
        return _manager.util().getString(s);
    }

    /** translate */
    private String _t(String s, Object o) {
        return _manager.util().getString(s, o);
    }

    /** translate */
    private String _t(String s, Object o, Object o2) {
        return _manager.util().getString(s, o, o2);
    }

    /** translate (ngettext) @since 0.7.14 */
    private String ngettext(String s, String p, int n) {
        return _manager.util().getString(n, s, p);
    }

    /** dummy for tagging */
    private static String ngettext(String s, String p) {
        return null;
    }

    private static String formatSize(long bytes) {
        return DataHelper.formatSize2(bytes) + 'B';
    }

    /**
     * This is for a full URL. For a path only, use encodePath().
     * @since 0.7.14
     */
    static String urlify(String s) {
        return urlify(s, 100);
    }

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
            if (link.startsWith("https"))
                display = DataHelper.escapeHTML(link);
            else
                display = DataHelper.escapeHTML(link.replace("http://", ""));
        } else
            display = DataHelper.escapeHTML(s.substring(0, max)) + "&hellip;";
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
    private static final String FOOTER = "</div>\n</center>\n<span id=endOfPage data-iframe-height></span>\n" +
                                         "<script src=/js/setupIframe.js></script>\n" + "</body>\n</html>";
    private static final String FOOTER_STANDALONE = "</div>\n</center></body>\n</html>";
    private static final String IFRAME_FORM = "<iframe name=processForm id=processForm hidden></iframe>\n";

    /**
     * Modded heavily from the Jetty version in Resource.java,
     * pass Resource as 1st param
     * All the xxxResource constructors are package local so we can't extend them.
     *
     * <pre>
      // ========================================================================
      // $Id: Resource.java,v 1.32 2009/05/16 01:53:36 gregwilkins Exp $
      // Copyright 1996-2004 Mort Bay Consulting Pty. Ltd.
      // ------------------------------------------------------------------------
      // Licensed under the Apache License, Version 2.0
      // ========================================================================
     * </pre>
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
        String resourcePath = debug ? "/themes/" : _contextPath + WARBASE;
        String decodedBase = decodePath(base);
        String title = decodedBase;
        String cpath = _contextPath + '/';
        if (title.startsWith(cpath))
            title = title.substring(cpath.length());

        // Get the snark associated with this directory
        String tName;
        String pathInTorrent;
        int slash = title.indexOf('/');
        if (slash > 0) {
            tName = title.substring(0, slash);
            pathInTorrent = title.substring(slash);
        } else {
            tName = title;
            pathInTorrent = "/";
        }
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
        buf.append(DOCTYPE).append("<html>\n<head>\n<meta charset=utf-8>\n");
        if (!isStandalone()) {
            buf.append("<script src=\"/js/iframeResizer/iframeResizer.contentWindow.js?").append(CoreVersion.VERSION)
               .append("\" id=iframeResizer></script>\n");
        }
        buf.append("<script src=" + resourcePath + "js/click.js type=module></script>\n").append("<title>");
        if (title.endsWith("/")) {title = title.substring(0, title.length() - 1);}
        final String directory = title;
        final int dirSlash = directory.indexOf('/');
        final boolean isTopLevel = dirSlash <= 0;
        title = _t("I2PSnark") + " - [" + _t("Torrent") + ": " + DataHelper.escapeHTML(title) + "]";
        buf.append(title);
        buf.append("</title>\n").append(HEADER_A).append(_themePath).append(HEADER_B).append("\n");
        // uncollapse panels
        boolean collapsePanels = _manager.util().collapsePanels();
        if (!collapsePanels) {buf.append(HEADER_A + _themePath + HEADER_C).append("\n");}
        String lang = (Translate.getLanguage(_manager.util().getContext()));
        if (lang.equals("zh") || lang.equals("ja") || lang.equals("ko")) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_D); // larger fonts for cjk translations
        }
        buf.append(HEADER_A + _themePath + HEADER_I).append("\n"); // images.css
        String themeBase = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() +
                           java.io.File.separatorChar + "docs" + java.io.File.separatorChar + "themes" +
                           java.io.File.separatorChar + "snark" + java.io.File.separatorChar + _manager.getTheme() +
                           java.io.File.separatorChar;
        File override = new File(themeBase + "override.css");
        if (!isStandalone() && useSoraFont()) {
            buf.append("<link rel=preload href=/themes/fonts/Sora.css as=style>\n")
               .append("<link rel=preload href=/themes/fonts/Sora/Sora.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=preload href=/themes/fonts/Sora/Sora-Italic.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=stylesheet href=/themes/fonts/Sora.css>\n");
        } else {
            buf.append("<link rel=preload href=/themes/fonts/DroidSans.css as=style>\n")
               .append("<link rel=preload href=/themes/fonts/DroidSans/DroidSans.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=preload href=/themes/fonts/DroidSans/DroidSans-Bold.woff2 as=font type=font/woff2 crossorigin>\n")
               .append("<link rel=stylesheet href=/themes/fonts/DroidSans.css>\n");
        }
        if (!isStandalone() && override.exists()) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_Z).append("\n"); // optional override.css for version-persistent user edits
        }
        // hide javascript-dependent buttons when js is unavailable
        buf.append("<noscript><style>.script{display:none}</style></noscript>\n")
           .append("<link rel=\"shortcut icon\" href=\"").append(_contextPath).append(WARBASE).append("icons/favicon.svg\">\n");

/** TODO event delegation so it works with ajax refresh
        if (showPriority)
            buf.append("<script src=\"").append(_contextPath + WARBASE + "js/setPriority.js?" + CoreVersion.VERSION + "\"></script>\n");
            buf.append("<script src=\"/themes/setPriority.js?" + CoreVersion.VERSION + "\"></script>\n"); // debugging
**/

        buf.append("</head>\n<body style=display:none;pointer-events:none class=lang_").append(lang).append(">\n");
        buf.append("<center>\n<div id=navbar><a href=\"").append(_contextPath).append("/\" title=Torrents class=\"snarkNav nav_main\">");
        if (_contextName.equals(DEFAULT_NAME)) {buf.append(_t("I2PSnark"));}
        else {buf.append(_contextName);}
        buf.append("</a>\n</div>\n");

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
                buf.append("<input type=hidden name=sort value=\"")
                   .append(DataHelper.stripHTML(sortParam)).append("\">\n");
            }
        }

// Torrent info section

        if (snark != null) {
            String fullPath = snark.getName();
            String baseName = encodePath((new File(fullPath)).getName());
            MetaInfo meta = snark.getMetaInfo();
            buf.append("<div class=mainsection id=snarkInfo>")
               .append("<table id=torrentInfo>\n")
               .append("<tr><th colspan=2>");
            toThemeImg(buf, "torrent");
            buf.append("<b>")
               .append(_t("Torrent"))
               .append(":</b> ");
            if (snark.getStorage() != null) {
               buf.append(DataHelper.escapeHTML(snark.getStorage().getBase().getPath()));
            } else {
               buf.append(DataHelper.escapeHTML(snark.getBaseName()));
            }
            String hex = I2PSnarkUtil.toHex(snark.getInfoHash());
            buf.append("</th><th><span class=infohash title=\"")
               .append(_t("Info hash")).append("\" tabindex=0>")
               .append(hex.toUpperCase(Locale.US))
               .append("</span>");

            String announce = null;
            // FIXME: if b64 appears in link, convert to b32 or domain name (if known)
            String postmanb64 = "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICkEbKUqJ9mPYQlTSujhNxiRIW-oLwMtvayCFci99oX8MvazPS7~97x0Gsm-onEK1Td9nBdmq30OqDxpRtXBimbzkLbR1IKObbg9HvrKs3L-kSyGwTUmHG9rSQSoZEvFMA-S0EXO~o4g21q1oikmxPMhkeVwQ22VHB0-LZJfmLr4SAAAA";
            String postmanb64_new = "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICrsRuil8qK~whOvj8uNTv~ohZnTZHxTLgi~sDyo98BwJ-4Y4NMSuF4GLzcgLypcR1D1WY2tDqMKRYFVyLE~MTPVjRRgXfcKolykQ666~Go~A~~CNV4qc~zlO6F4bsUhVZDU7WJ7mxCAwqaMiJsL-NgIkb~SMHNxIzaE~oy0agHJMBQAEAAcAAA==";
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
            String skankb32 = "by7luzwhx733fhc5ug2o75dcaunblq2ztlshzd7qvptaoa73nqua.b32.i2p";
            String theblandb32 = "s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p";

            if (meta != null) {
                announce = meta.getAnnounce();
                if (announce == null)
                    announce = snark.getTrackerURL();
                if (announce != null) {
                    announce = DataHelper.stripHTML(announce)
                       .replace(postmanb64, "tracker2.postman.i2p")
                       .replace(postmanb64_new, "tracker2.postman.i2p")
                       .replace(postmanb32_new, "tracker2.postman.i2p")
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
                       .replaceAll(theblandb32, "tracker.thebland.i2p");
                }

                if (meta != null || !meta.isPrivate()) {
                    buf.append("<a class=magnetlink href=\"")
                       .append(MagnetURI.MAGNET_FULL).append(hex);
                    if (announce != null)
                        buf.append("&amp;tr=").append(announce);
                    if (baseName != null)
                        buf.append("&amp;dn=").append(DataHelper.escapeHTML(baseName).replace(".torrent", "")
                           .replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"));
                    buf.append("\" title=\"")
                       .append(MagnetURI.MAGNET_FULL).append(hex);
                    if (announce != null)
                        buf.append("&amp;tr=").append(announce);
                    if (baseName != null)
                        buf.append("&amp;dn=").append(DataHelper.escapeHTML(baseName).replace(".torrent", "")
                           .replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"));
                    buf.append("\">")
                       .append(toSVG("magnet", ""))
                       .append("</a>");
                }

                buf.append("<a class=torrentlink href=\"").append(_contextPath).append('/')
                   .append(baseName).append("\" title=\"").append(DataHelper.escapeHTML(baseName)
                   .replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"))
                   .append("\">")
                   .append(toSVG("torrent", ""))
                   .append("</a>")
                   .append("</th></tr>\n");
                long dat = meta.getCreationDate();
                // needs locale configured for automatic translation
                SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMMM yyyy");
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                long[] dates = _manager.getSavedAddedAndCompleted(snark);
                buf.append("<tr id=torrentInfoStats>")
                   .append("<td colspan=3><span class=nowrap");
                if (dat > 0) {
                    String date = fmt.format(new Date(dat));
                    buf.append(" title=\"").append(_t("Created")).append(": ").append(date).append("\"");
                }
                buf.append(">");
                toThemeImg(buf, "file");
                buf.append("<b>")
                   .append(_t("Size"))
                   .append(":</b> ")
                   .append(formatSize(snark.getTotalLength()));
                if (storage != null) {
                    int fileCount = storage.getFileCount();
                    buf.append("</span>&nbsp;<span class=nowrap>");
                    toThemeImg(buf, "file");
                    buf.append("<b>")
                       .append(_t("Files"))
                       .append(":</b> ")
                       .append(fileCount);
                }
                int pieces = snark.getPieces();
                double completion = (pieces - snark.getNeeded()) / (double) pieces;
                buf.append("</span>&nbsp;<span class=nowrap");
                if (dates[0] > 0) {
                    String date = fmt.format(new Date(dates[0]));
                    buf.append(" title=\"").append(_t("Added")).append(": ").append(date).append("\"");
                }
                buf.append(">");
                toThemeImg(buf, "file");
                buf.append("<b>")
                   .append(_t("Pieces"))
                   .append(":</b> ")
                   .append(pieces)
                   .append(" @ ")
                   .append(formatSize(snark.getPieceLength(0)).replace("iB", ""));

                // up ratio
                buf.append("</span>&nbsp;<span class=nowrap>");
                toThemeImg(buf, "head_tx");
                buf.append("<b>")
                   .append(_t("Upload ratio").replace("Upload", "Share"))
                   .append(":</b> ");
                long uploaded = snark.getUploaded();
                if (uploaded > 0) {
                    double ratio = uploaded / ((double) snark.getTotalLength());
                    if (ratio < 0.1) {
                        buf.append((new DecimalFormat("0.000")).format(ratio));
                    } else {
                        buf.append((new DecimalFormat("0.00")).format(ratio));
                    }
                    buf.append("&#8239;x");
                } else {
                    buf.append('0');
                }

                buf.append("</span>&nbsp;<span id=completion class=nowrap");
                if (dates[1] > 0) {
                    String date = fmt.format(new Date(dates[1]));
                    buf.append(" title=\"").append(_t("Completed")).append(": ").append(date).append("\"");
                }
                buf.append(">");
                toThemeImg(buf, "head_rx");
                buf.append("<b>");
                if (completion < 1.0) {
                    buf.append(_t("Completion")).append(":</b> ").append((new DecimalFormat("0.0%"))
                       .format(completion).replace("0.0%","0%"));
                } else {
                    buf.append(_t("Complete")).append("</b>");
                }
                buf.append("</span>");

                if (meta != null) {
                    String cby = meta.getCreatedBy();
                    // not including skipped files, but -1 when not running
                    long needed = snark.getNeededLength();
                    if (needed < 0) {
                       // including skipped files, valid when not running
                       needed = snark.getRemainingLength();
                    }
                    if (needed > 0) {
                       buf.append("&nbsp;<span class=nowrap>");
                       toThemeImg(buf, "head_rx");
                       buf.append("<b>")
                          .append(_t("Remaining"))
                          .append(":</b> ")
                          .append(formatSize(needed))
                          .append("</span>");
                    }
                    long skipped = snark.getSkippedLength();
                    if (skipped > 0) {
                        buf.append("&nbsp;<span class=nowrap>");
                        toThemeImg(buf, "head_rx");
                        buf.append("<b>").append(_t("Skipped")).append(":</b> ").append(formatSize(skipped)).append("</span");
                    }

                    // needs locale configured for automatic translation
                    fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                    if (storage != null) {
                        dat = storage.getActivity();
                        if (dat > 0) {
                            String date = fmt.format(new Date(dat));
                            buf.append("&nbsp;<span class=nowrap>");
                            toThemeImg(buf, "torrent");
                            buf.append("<b>").append(_t("Last activity")).append(":</b> ").append(date).append("</span>");
                        }
                    }
                }
                buf.append("</td></tr>\n");

                List<List<String>> alist = meta.getAnnounceList();
                if (alist != null && !alist.isEmpty()) {
                    buf.append("<tr id=trackers title=\"")
                       .append(_t("Only I2P trackers will be used; non-I2P trackers are displayed for informational purposes only"))
                       .append("\"><td colspan=3>");
                    toThemeImg(buf, "torrent");
                    buf.append("<b>")
                       .append(_t("Trackers")).append(":</b> ");

                    for (List<String> alist2 : alist) {
                        if (alist2.isEmpty()) {
                            buf.append("<span class=\"info_tracker primary\">");
                            boolean more = false;
                            for (String s : alist2) {
                                if (more)
                                    buf.append("<span class=info_tracker>");
                                else
                                    more = true;
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
                            if (more) {
                                buf.append("<span class=info_tracker>");
                            } else {
                                more = true;
                            }
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
                } else {
                    if (meta != null) {
                    announce = meta.getAnnounce();
                    if (announce == null) {
                        announce = snark.getTrackerURL();
                    }
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
                        toThemeImg(buf, "torrent");
                        buf.append("<b>")
                           .append(_t("Tracker")).append(":</b> ")
                           .append("<span class=\"info_tracker primary\">")
                           .append(getShortTrackerLink(announce, snark.getInfoHash()))
                           .append("</span> ")
                           .append("</td></tr>\n");
                        }
                    }
                }

                List<String> weblist = meta.getWebSeedURLs();
                if (weblist != null) {
                    List<String> wlist = new ArrayList<String>(weblist.size());
                    // strip non-i2p web seeds
                    for (String s : weblist) {
                        if (isI2PTracker(s))
                            wlist.add(s);
                    }
                    if (!wlist.isEmpty()) {
                        buf.append("<tr id=webseeds><td colspan=3>");
                        toThemeImg(buf, "torrent");
                        buf.append("<b>").append(_t("Web Seeds")).append("</b>: ");
                        boolean more = false;
                        for (String s : wlist) {
                            buf.append("<span class=info_tracker>");
                            if (more) {
                                buf.append(' ');
                            } else {
                                more = true;
                            }
                            buf.append(getShortTrackerLink(DataHelper.stripHTML(s), snark.getInfoHash()))
                               .append("</span> ");
                        }
                        buf.append("</td></tr>\n");
                    }
                }
            }

            if (meta != null) {
                String com = meta.getComment();
                if (com != null && com.length() > 0) {
                    if (com.length() > 5000) {
                        com = com.substring(0, 5000) + "&hellip;";
                    }
                    buf.append("<tr><td id=metacomment colspan=3>")
                       .append("<div class=commentWrapper>")
                       .append(DataHelper.stripHTML(com).replace("\r\n", "<br>").replace("\n", "<br>").replace("&apos;", "&#39;"))
                       .append("</div></td></tr>\n");
                }
            }

            // buttons
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
                        buf.append("\" class=disabled disabled=disabled title=\"")
                           .append(_t("Stop the torrent in order to check file integrity"))
                           .append("\">");
                    } else {
                        buf.append("\" class=reload title=\"")
                           .append(_t("Check integrity of the downloaded files"))
                           .append("\">");
                    }
                }
/**
// TODO: fix feature so checkbox status is saved
                boolean showInOrder = storage != null && !storage.complete() && meta != null;
                if (showInOrder && meta != null) {
                    buf.append("<span id=torrentOrderControl><label for=\"enableInOrder\"><b>");
                    String txt = (meta.getFiles() != null && meta.getFiles().size() > 1) ?
                                   _t("Download files in order") :
                                   _t("Download pieces in order");
                    buf.append(txt);
                    buf.append("</b></label><input type=checkbox class=optbox name=enableInOrder id=enableInOrder");
                    if (storage.getInOrder())
                        buf.append(" checked=checked");
                    buf.append(">" +
                               "<input type=submit name=setInOrderEnabled value=\"");
                    buf.append(_t("Save Preference"));
                    buf.append("\" class=accept></span>");
                }
**/
                buf.append("</td></tr>\n");
            }
        } else {
            // snark == null
            // shouldn't happen
            buf.append("<table class=resourceError id=NotFound><tr><th colspan=2>")
               .append(_t("Resource Not found"))
               .append("</th></tr><tr><td><b>").append(_t("Torrent")).append(":</b></td><td>").append(DataHelper.escapeHTML(tName))
               .append("</td></tr><tr><td><b>").append(_t("Base")).append(":</b></td><td>").append(base)
               .append("</td></tr>\n");
        }
        buf.append("</table>\n")
           .append("</div>\n");

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
        if (r.isDirectory()) {
            ls = r.listFiles();
        }  // if r is not a directory, we are only showing torrent info section

        if (ls == null) {
            // We are only showing the torrent info section
            // unless audio or video...
            if (storage != null && storage.complete()) {
                String mime = getMimeType(r.getName());
                boolean isAudio = mime != null && isAudio(mime);
                boolean isVideo = !isAudio && mime != null && isVideo(mime);
                String path = base.substring(0, base.length() - 1);
                String newTab = "<img src=/themes/console/light/images/newtab.png width=16 height=auto class=newTab>";
                if (isAudio || isVideo) {
                    buf.append("<div class=mainsection id=media>")
                       .append("<table id=mediaContainer>\n<tr>");
                    // HTML5
                    if (isAudio) {
                        buf.append("<th class=audio>").append(_t("Audio file: "))
                           .append(DataHelper.escapeHTML(tName))
                           .append("<a href=\"").append(path)
                           .append("\" title=\"Open in new tab\" target=_blank>")
                           .append(newTab)
                           .append("</a>").append("</th></tr>\n<tr><td>")
                           .append("<audio controls>");
                    } else {
                        buf.append("<th id=videoTitle class=video>").append(_t("Video file: "))
                           .append(DataHelper.escapeHTML(tName))
                           .append("<a href=\"").append(path)
                           .append("\" title=\"Open in new tab\" target=_blank>")
                           .append(newTab)
                           .append("</a>").append("</th></tr>\n<tr><td>")
                           .append("<video id=embedVideo controls>");
                    }
                    // strip trailing slash
                    buf.append("<source src=\"").append(path).append("\" type=\"").append(mime).append("\">");
                    if (isAudio) {
                        buf.append("</audio>");
                    } else {
                        buf.append("</video>")
                           .append("<script src=\"").append(resourcePath).append("js/getMetadata.js?")
                           .append(CoreVersion.VERSION).append("\"></script>\n");
                    }
                    buf.append("</td></tr>\n</table>\n</div>\n");
                }
            }
            if (er || ec) {
                CommentSet comments = snark.getComments();
                buf.append("<div class=mainsection id=commentSection>")
                   .append("<input hidden class=toggle_input id=toggle_comments type=checkbox");
                if (comments != null && !comments.isEmpty()) {
                    buf.append(" checked");
                }
                buf.append(">\n<label id=tab_comments class=toggleview for=\"toggle_comments\"><span class=tab_label>")
                   .append(_t("Comments &amp; Ratings"))
                   .append("</span></label><hr>\n");
                displayComments(snark, er, ec, esc, buf);
                buf.append("</div>\n");
            }
            if (includeForm) {
                buf.append("</form>\n");
            }
            if (!isStandalone()) {
                buf.append(FOOTER);
            } else {
                buf.append(FOOTER_STANDALONE);
            }
            return buf.toString();
        }

        List<Sorters.FileAndIndex> fileList = new ArrayList<Sorters.FileAndIndex>(ls.length);
        // precompute remaining for all files for efficiency
        long[][] arrays = (storage != null) ? storage.remaining2() : null;
        long[] remainingArray = (arrays != null) ? arrays[0] : null;
        long[] previewArray = (arrays != null) ? arrays[1] : null;
        for (int i = 0; i < ls.length; i++) {
            File f = ls[i];
            if (isTopLevel) {
                // Hide (assumed) padding directory if it's in the filesystem.
                // Storage now will not create padding files, but
                // may have been created by an old version or other client.
                String n = f.getName();
                if ((n.equals(".pad") || n.equals("_pad")) && f.isDirectory()) {
                    continue;
                }
            }
            fileList.add(new Sorters.FileAndIndex(f, storage, remainingArray, previewArray));
        }

        boolean showSort = fileList.size() > 1;
        if (showSort) {
            int sort = 0;
            if (sortParam != null) {
                try {
                    sort = Integer.parseInt(sortParam);
                } catch (NumberFormatException nfe) {}
            }
            DataHelper.sort(fileList, Sorters.getFileComparator(sort, this));
        }

// Directory info section

        buf.append("<div class=mainsection id=snarkFiles>")
           .append("<input hidden class=toggle_input id=toggle_files type=checkbox");
        // don't collapse file view if not in torrent root
        String up = "";
        if (!isTopLevel || fileList.size() <= 10 || sortParam != null || getQueryString(up) != null) {
            buf.append(" checked");
        }
        buf.append(">")
           .append("<label id=tab_files class=toggleview for=\"toggle_files\"><span class=tab_label>")
           .append(_t("Files"))
           .append("</span></label><hr>\n")
           .append("<table class=dirInfo>\n<thead>\n<tr>\n<th colspan=2>");
        String tx = _t("Directory");
        // cycle through sort by name or type
        // TODO: add "(ascending") or "(descending") suffix to tooltip to indicate direction of sort
        String sort;
        boolean isTypeSort = false;
        if (showSort) {
            if (sortParam == null || "0".equals(sortParam) || "1".equals(sortParam)) {
                sort = "-1";
            } else if ("-1".equals(sortParam)) {
                sort = "12";
                isTypeSort = true;
            } else if ("12".equals(sortParam)) {
                sort = "-12";
                isTypeSort = true;
            } else {
                sort = "";
            }
            buf.append("<a href=\"").append(base).append(getQueryString(sort)).append("\">");
        }
        toThemeImg(buf, "file", tx, showSort ? _t("Sort by {0}", (isTypeSort ? _t("File type") : _t("Name"))) : tx + ": " + directory);
        if (showSort) {
            buf.append("</a>");
        }
        if (!isTopLevel) {
            buf.append("&nbsp;").append(DataHelper.escapeHTML(directory.substring(dirSlash + 1)));
        }
        buf.append("</th><th class=fileSize>");
        if (showSort) {
            sort = ("-5".equals(sortParam)) ? "5" : "-5";
            buf.append("<a href=\"").append(base).append(getQueryString(sort)).append("\">");
        }
        tx = _t("Size");
        toThemeImg(buf, "size", tx, showSort ? _t("Sort by {0}", tx) : tx);
        if (showSort) {
            buf.append("</a>");
        }
        buf.append("</th><th class=fileStatus>");
        boolean showRemainingSort = showSort && showPriority;
        if (showRemainingSort) {
            sort = ("10".equals(sortParam)) ? "-10" : "10";
            buf.append("<a id=sortRemaining href=\"").append(base)
               .append(getQueryString(sort)).append("\">");
        }
        tx = _t("Download Status");
        toThemeImg(buf, "status", tx, showRemainingSort ? _t("Sort by {0}", _t("Remaining")) : tx);
        if (showRemainingSort) {buf.append("</a>");}
        if (showPriority) {
            buf.append("</th><th class=\"priority volatile\">");
            if (showSort) {
                sort = ("13".equals(sortParam)) ? "-13" : "13";
                buf.append("<a href=\"").append(base).append(getQueryString(sort)).append("\">");
            }
            tx = _t("Download Priority");
            toThemeImg(buf, "priority", tx, showSort ? _t("Sort by {0}", tx) : tx);
            if (showSort)
                buf.append("</a>");
        }
        buf.append("</th></tr></thead>\n<tbody id=dirInfo>");
        if (!isTopLevel || hasCompleteAudio(fileList, storage, remainingArray)) { // don't show row if top level or no playlist
            buf.append("<tr><td colspan=\"" + (showPriority ? '3' : '2') + "\" class=ParentDir>");
            if (!isTopLevel) { // don't show parent dir link if top level
                up = "up";
                buf.append("<a href=\"");
                URIUtil.encodePath(buf, addPaths(decodedBase,"../"));
                buf.append("/").append(getQueryString(up))
                   .append("\">")
                   .append(toThemeSVG(up, "", ""))
                   .append(' ')
                   .append(_t("Up to higher level directory").replace("Up to higher level", "Parent"))
                   .append("</a>");
            }

            buf.append("</td><td colspan=2 class=\"ParentDir playlist\">");
            // playlist button
            if (hasCompleteAudio(fileList, storage, remainingArray)) {
                buf.append("<a href=\"").append(base).append("?playlist");
                if (sortParam != null && !"0".equals(sortParam) && !"1".equals(sortParam)) {
                    buf.append("&amp;sort=").append(sortParam);
                }
                buf.append("\">").append(toSVG("playlist"))
                   .append(' ').append(_t("Audio Playlist")).append("</a>");
            }
            buf.append("</td></tr>\n");
        }

        //DateFormat dfmt=DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
        boolean showSaveButton = false;
        boolean rowEven = true;
        boolean inOrder = storage != null && storage.getInOrder();
        int videoCount = 0;
        int imgCount = 0;
        int txtCount = 0;
        for (Sorters.FileAndIndex fai : fileList) {
            // String encoded = encodePath(ls[i].getName());
            // bugfix for I2P - Backport from Jetty 6 (zero file lengths and last-modified times)
            // http://jira.codehaus.org/browse/JETTY-361?page=com.atlassian.jira.plugin.system.issuetabpanels%3Achangehistory-tabpanel#issue-tabs
            // See resource.diff attachment
            // Resource item = addPath(encoded);
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
                    // Assume complete, perhaps he removed a completed torrent but kept a bookmark
                    complete = true;
                    //status = fai.isDirectory ? "" : toSVG("warn", "iconStatus", _t("Not found"), _t("Torrent not found?").replace("?", ""));
                    status = toSVG("warn", "iconStatus", _t("Not found"), _t("Torrent not found?").replace("?", ""));
                } else {
                    long remaining = fai.remaining;
                    if (remaining < 0) {
                        complete = true;
                        //status = fai.isDirectory ? "" : toSVG("warn", "iconStatus", _t("Unrecognized"), _t("File not found in torrent?").replace("?", ""));
                        status = toSVG("warn", "iconStatus", _t("Unrecognized"), _t("File not found in torrent?").replace("?", ""));
                    } else if (remaining == 0 || length <= 0) {
                        complete = true;
                        status = toSVG("tick", "iconStatus", _t("Complete"), _t("Complete"));
                    } else {
                        priority = fai.priority;
                        if (priority < 0) {status = "<div class=priorityIndicator>" + toImg("block") + "</div>";}
                        else if (priority == 0) {status = "<div class=priorityIndicator>" + toImg("clock") + "</div>";}
                        else {status = "<div class=priorityIndicator>" + toImg("clock_red") + "</div>";}
                        long percent = 100 * (length - remaining) / length;
                        status += " <div class=barOuter>" + "<div class=barInner style=\"width: " + percent + "%;\">" +
                                  "<div class=barText tabindex=0 title=\"" + formatSize(remaining) + ' ' + _t("remaining") +
                                  "\">" + percent + "%</div></div></div>";
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
                             mime.equals("application/json") || mime.equals("application/xml");
            boolean isPDF = mime.equals("application/pdf");
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
                       .append(item.getName()).append("\" data-lb-group=\"allInDir\"></a>");
                   imgCount++;
                } else if (mime.startsWith("image/") && ppath.endsWith(".ico")) {
                    // favicon without scaling
                    buf.append("<img alt=\"\" width=16 height=16 class=favicon border=0 src=\"")
                       .append(ppath).append("\" data-lb data-lb-caption=\"")
                       .append(item.getName()).append("\" data-lb-group=\"allInDir\"></a>");
                } else if (fai.isDirectory) {buf.append(toSVG(icon, _t("Open"))).append("</a>");}
                else {buf.append(toSVG(icon, _t("Open"))).append("</a>");}
                if (isAudio) {buf.append("</audio>");}
                else if (isVideo) {buf.append("</video>");}
            } else {buf.append(toSVG(icon));}
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
                buf.append("<td class=\"priority volatile\">");
                if ((!complete) && (!fai.isDirectory)) {
                    if (!inOrder) {
                        buf.append("<label class=priorityHigh title=\"")
                           .append(_t("Download file at high priority")).append("\">")
                           .append("\n<input type=radio class=\"prihigh optbox\" value=5 name=\"pri.")
                           .append(fileIndex).append("\" ");
                        if (priority > 0) {buf.append("checked=checked");}
                        buf.append('>').append(_t("High")).append("</label>");
                    }
                    buf.append("<label class=priorityNormal title=\"").append(_t("Download file at normal priority")).append("\">")
                       .append("\n<input type=radio class=\"prinorm optbox\" value=\"0\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority == 0 || (inOrder && priority >= 0)) {buf.append("checked=checked");}
                    buf.append('>').append(_t("Normal")).append("</label>")
                       .append("<label class=prioritySkip title=\"").append(_t("Do not download this file")).append("\">")
                       .append("\n<input type=radio class=\"priskip optbox\" value=\"-9\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority < 0) {buf.append("checked=checked");}
                    buf.append('>').append(_t("Skip")).append("</label>");
                    showSaveButton = true;
                }
                buf.append("</td>");
            }
            buf.append("</tr>\n");
        }
        if (showSaveButton) {
            buf.append("</tbody>\n<thead><tr id=setPriority><th colspan=5>")
            /* TODO: fixup so works with ajax refresh
                   "<span class=script>");
            if (!inOrder) {
                buf.append("<a class=control id=setallhigh href=#>")
                   .append(toImg("clock_red")).append(_t("Set all high")).append("</a>\n");
            }
            buf.append("<a class=control id=setallnorm href=#>")
               .append(toImg("clock")).append(_t("Set all normal")).append("</a>\n")
               .append("<a class=control id=setallskip href=#>")
               .append(toImg("block")).append(_t("Skip all")).append("</a></span>\n");
           */
               .append("<input type=submit class=accept value=\"").append(_t("Save priorities"))
               .append("\" name=\"savepri\">\n").append("</th></tr></thead>\n");
        }
        buf.append("</table>\n</div>\n");
        if (videoCount == 1) {buf.append("<script src=\"" + resourcePath + "js/getMetadata.js?" + CoreVersion.VERSION + "\"></script>\n");}
        if (imgCount > 0) {buf.append("<script src=" + resourcePath + "js/getImgDimensions.js></script>\n");}
        if (txtCount > 0) {buf.append("<script src=" + resourcePath + "js/textView.js></script>\n");}

// Comment section

        CommentSet comments = snark.getComments();
        if (er || ec) {
            buf.append("<div class=mainsection id=commentSection>\n")
               .append("<input hidden class=toggle_input id=toggle_comments type=checkbox");
            if (comments != null && !comments.isEmpty()) {buf.append(" checked");}
        }
        buf.append(">\n<label id=tab_comments class=toggleview for=\"toggle_comments\">")
           .append("<span class=tab_label>").append(_t("Comments")).append("</span></label><hr>\n");
        displayComments(snark, er, ec, esc, buf);
        // for stop/start/check
        buf.append("</div>\n");
        if (includeForm) {buf.append("</form>\n");}
        boolean enableLightbox = _manager.util().enableLightbox();
        if (enableLightbox) {
            buf.append("<link rel=stylesheet href=").append(resourcePath).append("lightbox.css>\n")
               .append("<script nonce=").append(cspNonce).append(" type=module>\n")
               .append("  import {Lightbox} from \"").append(resourcePath).append("js/lightbox.js\";\n")
               .append("  var lightbox = new Lightbox();lightbox.load();\n")
               .append("</script>\n");
        }
        buf.append("<script nonce=").append(cspNonce).append(" type=module>\n")
           .append("  import {initSnarkRefresh} from \"").append(resourcePath).append("js/refreshTorrents.js\";\n")
           .append("  document.addEventListener(\"DOMContentLoaded\", initSnarkRefresh, true);\n")
           .append("</script>\n");
        int delay = _manager.getRefreshDelaySeconds();
        if (!isStandalone()) {buf.append(FOOTER);}
        else {buf.append(FOOTER_STANDALONE);}
        return buf.toString();
    }

    /**
     * Basic checks only, not as comprehensive as what TrackerClient does.
     * Just to hide non-i2p trackers from the details page.
     * @since 0.9.46
     */
    private static boolean isI2PTracker(String url) {
        try {
            URI uri = new URI(url);
            String method = uri.getScheme();
            if (!"http".equals(method) && !"https".equals(method)) {return false;}
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
        return mime.startsWith("video/") && !mime.equals("video/x-msvideo") && !mime.equals("video/x-matroska") &&
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
                    for (int i = 0; i < ls.length; i++) {
                         fl2.add(new Sorters.FileAndIndex(ls[i], storage, remainingArray));
                    }
                    if (hasCompleteAudio(fl2, storage, remainingArray))
                        return true;
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
        String tName;
        String pathInTorrent;
        int slash = title.indexOf('/');
        if (slash > 0) {
            tName = title.substring(0, slash);
            pathInTorrent = title.substring(slash);
        } else {
            tName = title;
            pathInTorrent = "/";
        }
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
        long[] remainingArray = (storage != null) ? storage.remaining() : null;
        for (int i = 0; i < ls.length; i++) {
            fileList.add(new Sorters.FileAndIndex(ls[i], storage, remainingArray));
        }

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
    private void getPlaylist(StringBuilder buf, List<Sorters.FileAndIndex> fileList,
                             String reqURL, int sort,
                             Storage storage, long[] remainingArray) {
        for (Sorters.FileAndIndex fai : fileList) {
            if (fai.isDirectory) {
                // recurse
                File[] ls = fai.file.listFiles();
                if (ls != null && ls.length > 0) {
                    List<Sorters.FileAndIndex> fl2 = new ArrayList<Sorters.FileAndIndex>(ls.length);
                    for (int i = 0; i < ls.length; i++) {
                         fl2.add(new Sorters.FileAndIndex(ls[i], storage, remainingArray));
                    }
                    if (ls.length > 1) {
                        DataHelper.sort(fl2, Sorters.getFileComparator(sort, this));
                    }
                    String name = fai.file.getName();
                    String url2 = reqURL + encodePath(name) + '/';
                    getPlaylist(buf, fl2, url2, sort, storage, remainingArray);
                }
                continue;
            }
            if (fai.remaining != 0) {continue;}
            String name = fai.file.getName();
            String mime = getMimeType(name);
            if (mime != null && isAudio(mime)) {
                buf.append(reqURL).append(encodePath(name)).append('\n'); // TODO Extended M3U
            }
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
                        buf.append("<option value=\"").append(i).append("\" ");
                        if (i == myRating) {buf.append("selected=selected");}
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
                buf.append("\" class=accept></td>");
                buf.append("</tr>\n");
            }

            if (comments != null) {
                synchronized(comments) {
                    // current rating
                    if (er) {
                        buf.append("<tr id=myRating><td>");
                        myRating = comments.getMyRating();
                        if (myRating > 0) {
                            buf.append(_t("My Rating")).append(":</td><td colspan=2 class=commentRating>");
                            String img = toSVG("rateme", "★", "");
                            for (int i = 0; i < myRating; i++) {buf.append(img);}
                        }
                        buf.append("</td></tr>");
                    }
                    if (er) {
                        buf.append("<tr id=showRatings><td>");
                        int rcnt = comments.getRatingCount();
                        if (rcnt > 0) {
                            double avg = comments.getAverageRating();
                            buf.append(_t("Average Rating")).append(":</td><td colspan=2>").append((new DecimalFormat("0.0")).format(avg));
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
            // TODO  disable / enable comments for this torrent
            // existing ratings / comments table
            int ccount = 0;
            if (iter != null) {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                buf.append("<table id=userComments>\n");

                while (iter.hasNext()) {
                    Comment c = iter.next();
                    buf.append("<tr><td class=commentAuthor>");
                    // (c.isMine())
                    //  buf.append(_t("me"));
                    //else if (c.getName() != null)
                    // TODO can't be hidden... hide if comments are hidden?
                    if (c.getName() != null) {
                        buf.append("<span class=commentAuthorName title=\"" + DataHelper.escapeHTML(c.getName()) + "\">");
                        buf.append(DataHelper.escapeHTML(c.getName()));
                        buf.append("</span>");
                    }
                    buf.append("</td><td class=commentRating>");
                    if (er) {
                        int rt = c.getRating();
                        if (rt > 0) {
                            String img = toSVG("rateme", "★", "");
                            for (int i = 0; i < rt; i++) {
                                buf.append(img);
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
                        } else {
                            buf.append("</td><td class=commentDelete>"); // insert empty named columns to maintain table layout
                        }
                    } else {
                        buf.append("</td><td class=commentDelete>"); // insert empty named columns to maintain table layout
                    }
                    buf.append("</td></tr>\n");
                }
                if (esc && ccount > 0) {
                    // TODO format better
                    buf.append("<tr id=commentDeleteAction><td colspan=4 class=commentAction>")
                       .append("<input type=submit name=deleteComments value=\"")
                       .append(_t("Delete Selected"))
                       .append("\" class=delete></td></tr>\n");
                }
                buf.append("</table>\n");
            } else if (esc) {}
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
     *  Pick an icon; try to catch the common types in an i2p environment
     *  Pkg private for FileTypeSorter.
     *
     *  @return file name not including ".png"
     *  @since 0.7.14
     */
    String toIcon(String path) {
        String icon;
        // Note that for this to work well, our custom mime.properties file must be loaded.
        String plc = path.toLowerCase(Locale.US);
        String mime = getMimeType(path);
        if (mime == null) {mime = "";}
        if (mime.equals("text/html") || plc.endsWith(".jsp")) {icon = "html";}

        else if (plc.endsWith(".srt")) {icon = "srt";}

        else if (mime.equals("text/plain") || mime.equals("text/x-sfv") ||
                 mime.equals("application/rtf") || plc.endsWith(".md") ||
                 plc.endsWith(".ini") || plc.endsWith(".nfo")) {icon = "text";}

        else if (mime.equals("application/epub+zip") ||
                 mime.equals("application/x-mobipocket-ebook") ||
                 plc.endsWith(".fb2") || plc.endsWith(".azw3") ||
                 plc.endsWith(".azw4") || plc.endsWith(".prc")) {icon = "ebook";}

        else if (mime.equals("application/x-jar") || mime.equals("application/x-java-archive") ||
                 mime.equals("application/java-archive") || plc.endsWith(".jar") || plc.endsWith(".exe")) {
            if (plc.contains("i2pinstall") && plc.contains("+")) {icon = "plus";}
            else if (plc.contains("i2pinstall")) {icon = "i2p";}
            else if (plc.endsWith(".exe")) {icon = "windows";}
            else {icon = "package";}
        }

        else if (mime.equals("application/java-archive") || plc.endsWith(".deb") ||
                 plc.endsWith(".rpm") || plc.endsWith(".flatpak") || plc.endsWith(".snap") ||
                 plc.endsWith(".appimage")) {icon = "package";}

        else if (plc.endsWith(".xpi2p")) {icon = "plugin";}

        else if (mime.equals("application/pdf")) {icon = "pdf";}

        else if (mime.startsWith("image/")) {icon = "image";}

        else if (mime.startsWith("audio/") || mime.equals("application/ogg")) {icon = "audio";}

        else if (mime.startsWith("video/")) {icon = "video";}

        else if (mime.startsWith("font/") || plc.endsWith(".ttf") ||
                 plc.endsWith(".woff") || plc.endsWith(".woff2")) {icon = "font";}

        else if (mime.equals("application/zip")) {
            if (plc.endsWith(".su3") || plc.endsWith(".su2")) {icon = "i2p";}
            else {icon = "compress";}
        }

        else if (mime.equals("application/x-rar-compressed")) {icon = "rar";}

        else if (mime.equals("application/x-gtar") || mime.equals("application/x-tar") ||
                 plc.endsWith(".txz")) {icon = "tar";}

        else if (mime.equals("application/x-xz") || mime.equals("application/compress") ||
                 mime.equals("application/gzip") || mime.equals("application/x-7z-compressed") ||
                 mime.equals("application/x-bzip2")) {icon = "compress";}

        else if (plc.endsWith(".bin")) {icon = "app";}

        else if (plc.endsWith(".bat") || plc.endsWith(".dll")) {icon = "windows";}

        else if (plc.endsWith(".dmg")) {icon = "apple";}

        else if (plc.endsWith(".iso") || plc.endsWith(".nrg")) {icon = "cd";}

        else if (plc.endsWith(".sh")) {icon = "shell";}

        else if (plc.contains(".css.") || plc.endsWith(".css") || plc.endsWith(".js") ||
                 plc.endsWith(".cgi") || plc.endsWith(".pl") || plc.endsWith(".py") ||
                 plc.endsWith(".php") || plc.endsWith(".h") || plc.endsWith(".cpp") ||
                 plc.endsWith(".json")) {icon = "code";}

        else if (plc.endsWith(".md5") || plc.contains("shasum")) {icon = "hash";}

        else if (mime.equals("application/x-bittorrent")) {icon = "magnet";}

        else {icon = "generic";}

        return icon;
    }

    /**
     *  Icon file in the .war. Always 16x16.
     *
     *  @param icon name without the ".png"
     *  @since 0.7.14
     */
    private String toImg(String icon) {return toImg(icon, "");}

    /**
     *  Icon file in the .war. Always 16x16.
     *
     *  @param icon name without the ".png"
     *  @since 0.8.2
     */
    private String toImg(String icon, String altText) {
        return "<img alt=\"" + altText + "\" height=16 width=16 src=\"" + _contextPath + WARBASE + "icons/" + icon + ".png\">";
    }

    /**
     *  Icon file in the .war. Always 16x16.
     *
     *  @param image name without the ".png"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.33
     */
    private String toImg(String icon, String classText, String altText, String titleText) {
        return "<img class=\"" + classText + "\" alt=\"" + altText + "\" height=16 width=16 src=" +
                _contextPath + WARBASE + "icons/" + icon + ".png title=\"" + titleText + "\">";
    }

    /**
     *  Icon file (svg) in the .war. Always 16x16.
     *
     *  @param icon name without the ".svg"
     *  @since 0.9.51+
     */
    private String toSVG(String icon) {return toSVG(icon, "");}

    /**
     *  Icon file (svg) in the .war. Always 16x16.
     *
     *  @param icon name without the ".svg"
     *  @since 0.8.2
     */
    private String toSVG(String icon, String altText) {
        return "<img alt=\"" + altText + "\" height=16 width=16 src=" + _contextPath + WARBASE + "icons/" + icon + ".svg>";
    }

    /**
     *  Icon file (svg) in the .war. Always 16x16.
     *
     *  @param image name without the ".svg"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.51+
     */
    private String toSVG(String icon, String altText, String titleText) {
        return "<img alt=\"" + altText + "\" height=16 width=16 src=" + _contextPath +
                WARBASE + "icons/" + icon + ".svg title=\"" + titleText + "\">";
    }

    /**
     *  Icon file (svg) in the .war. Always 16x16.
     *
     *  @param image name without the ".svg"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.51+
     */
    private String toSVGWithDataTooltip(String icon, String altText, String titleText) {
        return "<span class=tooltipped data-tooltip=\"" + titleText + "\"><img alt=\"" + altText +
               "\" height=16 width=16 src=" + _contextPath + WARBASE + "icons/" + icon + ".svg></span>";
    }

    /**
     *  Icon file (svg) in the .war.
     *
     *  @param image name without the ".svg"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.33
     */
    private String toSVG(String icon, String classText, String altText, String titleText) {
        return "<img class=\"" + classText + "\" alt=\"" + altText + "\" height=16 width=16 src=" + _contextPath +
                WARBASE + "icons/" + icon + ".svg title=\"" + titleText + "\">";
    }

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @since 0.9.16
     */
    private String toThemeImg(String image) {return toThemeImg(image, "", "");}

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @since 0.9.16
     */
    private void toThemeImg(StringBuilder buf, String image) {toThemeImg(buf, image, "", "");}

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.16
     */
    private String toThemeImg(String image, String altText, String titleText) {
        StringBuilder buf = new StringBuilder(128);
        toThemeImg(buf, image, altText, titleText);
        return buf.toString();
    }

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.16
     */
    private void toThemeImg(StringBuilder buf, String image, String altText, String titleText) {
        buf.append("<img alt=\"").append(altText).append("\" src=").append(_imgPath).append(image).append(".png");
        if (titleText.length() > 0) {buf.append(" title=\"").append(titleText).append('"');}
        buf.append('>');
    }

    /**
     *  SVG image file in the .war
     *
     *  @param image name without the ".svg"
     *  @since 0.9.51+
     */
    private void toSVG(StringBuilder buf, String image) {toSVG(buf, image, "", "");}

    /**
     *  SVG Image file in the .war
     *
     *  @param image name without the ".svg"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.51 (I2P+)
     */
    private void toSVG(StringBuilder buf, String image, String altText, String titleText) {
        buf.append("<img alt=\"").append(altText).append("\" src=").append(_contextPath + WARBASE)
           .append("icons/").append(image).append(".svg");
        if (titleText.length() > 0) {buf.append(" title=\"").append(titleText).append('"');}
        buf.append('>');
    }

    /**
     *  SVG Image file in the theme.
     *
     *  @param image name without the ".svg"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.48 (I2P+)
     */
    private String toThemeSVG(String image, String altText, String titleText) {
        StringBuilder buf = new StringBuilder(128);
        toThemeSVG(buf, image, altText, titleText);
        return buf.toString();
    }

    /**
     *  SVG Image file in the theme.
     *
     *  @param image name without the ".svg"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.48 (I2P+)
     */
    private void toThemeSVG(StringBuilder buf, String image, String altText, String titleText) {
        buf.append("<img alt=\"").append(altText).append("\" src=").append(_imgPath).append(image).append(".svg");
        if (titleText.length() > 0) {buf.append(" title=\"").append(titleText).append('"');}
        buf.append('>');
    }

    /** @since 0.8.1 */
    private void savePriorities(Snark snark, Map<String, String[]> postParams) {
        Storage storage = snark.getStorage();
        if (storage == null) {return;}
        for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("pri.")) {
                try {
                    int fileIndex = Integer.parseInt(key.substring(4));
                    String val = entry.getValue()[0]; // jetty arrays
                    int pri = Integer.parseInt(val);
                    storage.setPriority(fileIndex, pri);
                } catch (Throwable t) {t.printStackTrace();}
            }
        }
        if (postParams.get("setInOrderEnabled") != null) {
            storage.setInOrder(postParams.get("enableInOrder") != null);
        }
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
        MetaInfo meta = snark.getMetaInfo();
        if (meta == null) {return;}
        buf.append("<div id=editSection class=mainsection>\n")
           .append("<input hidden class=toggle_input id=toggle_torrentedit type=checkbox>")
           .append("<label id=tab_torrentedit class=toggleview for=\"toggle_torrentedit\"><span class=tab_label>")
           .append(_t("Edit Torrent"))
           .append("</span></label><hr>\n")
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
            buf.append("<tr class=header><th>").append(_t("Active Trackers")).append("</th>")
               .append("<th>").append(_t("Announce URL")).append("</th><th>")
               .append(_t("Primary")).append("</th><th id=remove>").append(_t("Delete")).append("</th></tr>\n");
            for (String s : annlist) {
                int hc = s.hashCode();
                buf.append("<tr><td>");
                s = DataHelper.stripHTML(s);
                buf.append("<span class=info_tracker>").append(getShortTrackerLink(s, snark.getInfoHash())).append("</span>")
                   .append("</td><td>").append(s).append("</td><td>")
                   .append("<input type=radio class=optbox name=primary");
                if (s.equals(announce)) {buf.append(" checked=checked ");}
                buf.append("value=\"").append(hc).append("\"");
                if (isRunning) {buf.append(" disabled=disabled");}
                buf.append("></td><td>")
                   .append("<input type=checkbox class=optbox name=\"removeTracker-")
                   .append(hc).append("\" title=\"").append(_t("Mark for deletion")).append("\"");
                if (isRunning) {buf.append(" disabled=disabled");}
                buf.append("></td></tr>\n");
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
                if (isRunning) {buf.append(" disabled=disabled");}
                buf.append("></td><td>")
                   .append("<input type=checkbox class=optbox id=\"").append(name).append("\" name=\"addTracker-")
                   .append(hc).append("\" title=\"").append(_t("Add tracker")).append("\"");
                if (isRunning) {buf.append(" disabled=disabled");}
                buf.append("></td></tr>\n");
            }
        }

        String com = meta.getComment();
        if (com == null) {com = "";}
        else if (com.length() > 0) {com = DataHelper.escapeHTML(com);}
        buf.append("<tr class=header><th colspan=4>").append(_t("Torrent Comment")).append("</th></tr>\n")
           .append("<tr><td colspan=4 id=addCommentText><textarea name=nofilter_newTorrentComment cols=88 rows=4");
        if (isRunning) {buf.append(" readonly");}
        buf.append(">").append(com).append("</textarea></td>").append("</tr>\n");
        if (isRunning) {
            buf.append("<tfoot><tr><td colspan=4><span id=stopfirst>")
               .append(_t("Torrent must be stopped in order to edit"))
               .append("</span></td></tr></tfoot>\n</table>\n</div>\n");
            return;
        } else {
            buf.append("<tfoot><tr><td colspan=4>")
               .append("<input type=submit name=editTorrent value=\"")
               .append(_t("Save Changes"))
               .append("\" class=accept></td></tr></tfoot>\n")
               .append("</table>\n</div>\n");
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
            (primary == null || primary.equals(oldPrimary)) &&
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
            out = new SecureFileOutputStream(f);
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
     *  Is "a" equal to "b",
     *  or is "a" a directory and a parent of file or directory "b",
     *  canonically speaking?
     *
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
     *
     *  @since 0.9.54+
     */
    private boolean isStandalone() {
        if (_context.isRouterContext()) {return false;}
        else {return true;}
    }

}