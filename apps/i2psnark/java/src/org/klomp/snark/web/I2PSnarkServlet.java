package org.klomp.snark.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
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
import net.i2p.servlet.util.ServletUtil;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
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
import org.klomp.snark.Tracker;
import org.klomp.snark.TrackerClient;
import org.klomp.snark.URIUtil;
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
    private static final String WARBASE = "/.resources/";
    private static final char HELLIP = '\u2026';
    private static final String PROP_ADVANCED = "routerconsole.advanced";

    String cspNonce = Integer.toHexString(_context.random().nextInt());

    public I2PSnarkServlet() {
        super();
    }

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
        if (!configName.equals(DEFAULT_NAME))
            configName = DEFAULT_NAME + '_' + _contextName;
        _manager = new SnarkManager(_context, _contextPath, configName);
        String configFile = _context.getProperty(PROP_CONFIG_FILE);
        if ( (configFile == null) || (configFile.trim().length() <= 0) )
            configFile = configName + ".config";
        _manager.loadConfig(configFile);
        _manager.start();
        loadMimeMap("org/klomp/snark/web/mime");
        setResourceBase(_manager.getDataDir());
        setWarBase(WARBASE);
    }

    @Override
    public void destroy() {
        if (_manager != null)
            _manager.stop();
        super.destroy();
    }

    /**
     *  We override this to set the file relative to the storage directory
     *  for the torrent.
     *
     *  @param pathInContext should always start with /
     */
    @Override
    public File getResource(String pathInContext)
    {
        if (pathInContext == null || pathInContext.equals("/") || pathInContext.equals("/index.jsp") ||
            !pathInContext.startsWith("/") || pathInContext.length() == 0 ||
            pathInContext.equals("/index.html") || pathInContext.startsWith(WARBASE))
            return super.getResource(pathInContext);
        // files in the i2psnark/ directory
        // get top level
        pathInContext = pathInContext.substring(1);
        File top = new File(pathInContext);
        File parent;
        while ((parent = top.getParentFile()) != null) {
            top = parent;
        }
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

    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
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
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Service " + req.getMethod() + " \"" + req.getContextPath() + "\" \"" + req.getServletPath() + "\" \"" + req.getPathInfo() + '"');
        // since we are not overriding handle*(), do this here
        String method = req.getMethod();
        // this is the part after /i2psnark
        String path = req.getServletPath();

        // in-war icons etc.
        if (path != null && path.startsWith(WARBASE)) {
            if (method.equals("GET") || method.equals("HEAD"))
                super.doGet(req, resp);
            else  // no POST either
                resp.sendError(405);
            return;
        }

        if (_context.isRouterContext())
            _themePath = "/themes/snark/" + _manager.getTheme() + '/';
        else
            _themePath = _contextPath + WARBASE + "themes/snark/" + _manager.getTheme() + '/';
        _imgPath = _themePath + "images/";
        req.setCharacterEncoding("UTF-8");

        String pOverride = _manager.util().connected() ? null : "";
        String peerString = getQueryString(req, pOverride, null, null);

        // AJAX for mainsection
        if ("/.ajax/xhr1.html".equals(path)) {
            setXHRHeaders(resp, cspNonce, false);
            PrintWriter out = resp.getWriter();
            out.write("<!DOCTYPE HTML>\n<html>\n<head>\n");
            out.write("</head>\n<body id=\"snarkxhr\"><div id=\"mainsection\">\n");
            writeMessages(out, false, peerString);
            boolean canWrite;
            synchronized(this) {
                canWrite = _resourceBase.canWrite();
            }
            writeTorrents(out, req, canWrite);
            out.write("</div></body>\n</html>\n");
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
                if (resource == null) {
                    resp.sendError(404);
                } else if (req.getParameter("playlist") != null) {
                    String base = addPaths(req.getRequestURI(), "/");
                    String listing = getPlaylist(req.getRequestURL().toString(), base, req.getParameter("sort"));
                    if (listing != null) {
                        setHTMLHeaders(resp, cspNonce, false);
                        // TODO custom name
                        resp.setContentType("audio/mpegurl; charset=UTF-8; name=\"playlist.m3u\"");
                        resp.addHeader("Content-Disposition", "attachment; filename=\"playlist.m3u\"");
                        resp.getWriter().write(listing);
                    } else {
                        resp.sendError(404);
                    }
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
                    } else { // shouldn't happen
                        resp.sendError(404);
                    }
                }
            } else {
                // local completed files in torrent directories
                if (method.equals("GET") || method.equals("HEAD"))
                    super.doGet(req, resp);
                else if (method.equals("POST"))
                    super.doPost(req, resp);
                else
                    resp.sendError(405);
            }
            return;
        }

        // Either the main page or /configure

        String nonce = req.getParameter("nonce");
        if (nonce != null) {
            if (nonce.equals(String.valueOf(_nonce)))
                processRequest(req);
            else  // nonce is constant, shouldn't happen
                _manager.addMessage("Please retry form submission (bad nonce)");
            // P-R-G (or G-R-G to hide the params from the address bar)
            sendRedirect(req, resp, peerString);
            return;
        }

        boolean noCollapse = noCollapsePanels(req);
        boolean collapsePanels = _manager.util().collapsePanels();
        setHTMLHeaders(resp, cspNonce, false);
        PrintWriter out = resp.getWriter();
        boolean isStandalone = !_context.isRouterContext();
        out.write(DOCTYPE + "<html>\n" +
                  "<head>\n" +
                  "<meta charset=\"utf-8\">\n" +
                  "<meta name=\"viewport\" content=\"width=device-width\">\n" +
                  "<link rel=\"preload\" href=\"/themes/fonts/DroidSans.css\" as=\"style\">\n" +
                  "<link rel=\"preload\" href=\"" + _themePath + "images/images.css?" + CoreVersion.VERSION + "\" as=\"style\">\n" +
                  "<link rel=\"shortcut icon\" href=\"" + _contextPath + WARBASE + "icons/favicon.svg\">\n");
        if (!isStandalone)
            out.write("<link rel=\"preload\" href=\"/js/iframeResizer/iframeResizer.contentWindow.js?" + CoreVersion.VERSION + "\" as=\"script\">");
        out.write("<title>");
        if (_contextName.equals(DEFAULT_NAME))
            out.write(_t("I2PSnark"));
        else
            out.write(_contextName);
        out.write(" - ");
        if (isConfigure) {
            out.write(_t("Configuration"));
        } else {
            String peerParam = req.getParameter("p");
            if ("2".equals(peerParam))
                out.write(_t("Debug Mode"));
            else
                out.write(_t("Anonymous BitTorrent Client"));
        }
        out.write("</title>\n");

        // we want it to go to the base URI so we don't refresh with some funky action= value
        int delay = 0;
        String jsPfx = _context.isRouterContext() ? "" : ".resources";
        if (!isConfigure) {
            delay = _manager.getRefreshDelaySeconds();
            if (delay > 0) {
                String downMsg = _context.isRouterContext() ? _t("Router is down") : _t("I2PSnark has stopped");
                // fallback to metarefresh when javascript is disabled
                out.write("<noscript><meta http-equiv=\"refresh\" content=\"" + delay + ";" + _contextPath + "/" + peerString + "\"></noscript>\n");
            }
            out.write("<script nonce=\"" + cspNonce + "\" type=\"text/javascript\">\n"  +
                      "var deleteMessage1 = \"" + _t("Are you sure you want to delete the file \\''{0}\\'' (downloaded data will not be deleted) ?") + "\";\n" +
                      "var deleteMessage2 = \"" + _t("Are you sure you want to delete the torrent \\''{0}\\'' and all downloaded data?") + "\";\n" +
                      "</script>\n" +
                      "<script src=\".resources/js/delete.js?" + CoreVersion.VERSION + "\" type=\"text/javascript\"></script>\n");
            if (delay > 0) {
                out.write("<script nonce=\"" + cspNonce + "\" type=\"module\">\n" +
                          "import {refreshTorrents} from \""  + _contextPath + WARBASE + "js/refreshTorrents.js?" + CoreVersion.VERSION + "\";\n" +
                          "var ajaxDelay = " + (delay * 1000) + ";\n" +
                          "var visibility = document.visibilityState;\n" +
                          "var cycle;\n" +
                          "if (visibility = \"visible\") {\n" +
                          "function timer() {\n" +
                          "var cycle = setInterval(function() {\n" +
                          "requestAnimationFrame(refreshTorrents);\n" +
                          "}, ajaxDelay);\n" +
                          "}\n" +
                          "timer();\n" +
                          "}\n" +
                          "</script>\n");
            }
        }
        // custom dialog boxes for javascript alerts
        //out.write("<script src=\"" + jsPfx + "/js/custom-alert.js\" type=\"text/javascript\"></script>\n");
        //out.write("<link type=\"text/css\" rel=\"stylesheet\" href=\"" + _contextPath + WARBASE + "custom-alert.css\">\n");

        // selected theme inserted here
        out.write(HEADER_A + _themePath + HEADER_B + "\n");
        out.write(HEADER_A + _themePath + HEADER_I + "\n"); // load css image assets
        out.write(HEADER_A + _themePath + HEADER_Z + "\n"); // optional override.css for version-persistent user edits

        // larger fonts for cjk translations
        String lang = (Translate.getLanguage(_manager.util().getContext()));
        if (lang.equals("zh") || lang.equals("ja") || lang.equals("ko"))
            out.write(HEADER_A + _themePath + HEADER_D + "\n");

        //  ...and inject CSS to display panels uncollapsed
        if (noCollapse || !collapsePanels) {
            out.write(HEADER_A + _themePath + HEADER_C + "\n");
        }
        out.write("</head>\n" + "<body id=\"snarkxhr\" class=\"" + _manager.getTheme() + " lang_" + lang + "\">\n" + "<center>\n");
        List<Tracker> sortedTrackers = null;
/*
        long now = System.currentTimeMillis();
        String time = String.valueOf(now);
*/
        if (isConfigure) {
            out.write("<div class=\"snarknavbar\" id=\"top\">\n<a href=\"" + _contextPath + "/\" title=\"");
            out.write(_t("Torrents"));
            out.write("\" class=\"snarkNav nav_main\">");
            if (_contextName.equals(DEFAULT_NAME))
                out.write(_t("I2PSnark"));
            else
                out.write(_contextName);
            out.write("</a>\n");
        } else {
            out.write("<div class=\"snarknavbar\" id=\"top\">\n<a href=\"" + _contextPath + '/' + peerString + "\" title=\"");
            out.write(_t("Refresh page"));
            out.write("\" class=\"snarkNav nav_main\">");
            if (_contextName.equals(DEFAULT_NAME))
                out.write(_t("I2PSnark"));
            else
                out.write(_contextName);
            out.write("</a>\n");
            sortedTrackers = _manager.getSortedTrackers();
//            if (_context.isRouterContext() && _manager.hasModifiedTrackers()) {
//            if (_context.isRouterContext()) {
                out.write("<a href=\"http://discuss.i2p/\" class=\"snarkNav nav_forum\" target=\"_blank\">");
                out.write(_t("Forum"));
                out.write("</a>\n");
/*
                out.write("<a href=\"http://anodex.i2p/\" class=\"snarkNav nav_tracker\" target=\"_blank\">");
                out.write("Anodex");
                out.write("</a>\n");
*/
                out.write("<a href=\"http://torrents.chudo.i2p/\" class=\"snarkNav nav_tracker\" target=\"_blank\">");
                out.write("Chudo");
                out.write("</a>");
                for (Tracker t : sortedTrackers) {
                    if (t.baseURL == null || !t.baseURL.startsWith("http"))
                        continue;
                    if (_manager.util().isKnownOpenTracker(t.announceURL))
                        continue;
                    out.write("\n<a href=\"" + t.baseURL + "\" class=\"snarkNav nav_tracker\" target=\"_blank\">" + t.name + "</a>");
                }
//                out.write("\n<a href=\"http://torrentfinder.i2p/\" class=\"snarkNav nav_search\" target=\"_blank\">");
//                out.write(_t("Finder"));
//                out.write("</a>");
//            }
        }
        out.write("\n</div>\n");
        String newURL = req.getParameter("newURL");
        if (newURL != null && newURL.trim().length() > 0 && req.getMethod().equals("GET"))
            _manager.addMessage(_t("Click \"Add torrent\" button to fetch torrent"));
        out.write("<div class=\"page\">\n<div id=\"mainsection\" class=\"mainsection\">\n");

        writeMessages(out, isConfigure, peerString);

        if (isConfigure) {
            // end of mainsection div
            out.write("<div class=\"logshim\"></div>\n</div>\n");
            writeConfigForm(out, req);
            writeTrackerForm(out, req);
        } else {
            boolean canWrite;
            synchronized(this) {
                canWrite = _resourceBase.canWrite();
            }
            boolean pageOne = writeTorrents(out, req, canWrite);
            // end of mainsection div
            if (pageOne) {
                out.write("</div>\n<div id=\"lowersection\">\n");
                if (canWrite) {
                    writeAddForm(out, req);
                    writeSeedForm(out, req, sortedTrackers);
                }
                writeConfigLink(out);
                // end of lowersection div
            } else {
                out.write("</div>\n<div id=\"lowersection\">\n");
                boolean enableAddCreate = _manager.util().enableAddCreate();
                if (canWrite && enableAddCreate) {
                    writeAddForm(out, req);
                    writeSeedForm(out, req, sortedTrackers);
                }
                writeConfigLink(out);
            }
            out.write("</div>\n");
        }
        out.write(FOOTER);
    }

    /**
     *  The standard HTTP headers for all HTML pages
     *
     *  @since 0.9.16 moved from doGetAndPost()
     */
    private static void setHTMLHeaders(HttpServletResponse resp, String cspNonce, boolean allowMedia) {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, private, max-age=2628000");
        resp.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; script-src 'self' 'unsafe-inline' 'nonce-" + cspNonce + "'; form-action 'self'; frame-ancestors 'self'; object-src 'none'; base-uri 'self'; media-src '" + (allowMedia ? "self" : "none") + "'");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("X-XSS-Protection", "1; mode=block");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Referrer-Policy", "same-origin");
        resp.setHeader("Accept-Ranges", "none");
        resp.setHeader("Feature-Policy", "fullscreen 'self'");
    }

    private static void setXHRHeaders(HttpServletResponse resp, String cspNonce, boolean allowMedia) {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Cache-Control", "private, max-age=2628000");
        resp.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; script-src 'self' 'unsafe-inline' 'nonce-" + cspNonce + "'; form-action 'self'; frame-ancestors 'self'; object-src 'none'; base-uri 'self'; media-src '" + (allowMedia ? "self" : "none") + "'");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("X-XSS-Protection", "1; mode=block");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Referrer-Policy", "same-origin");
        resp.setHeader("Accept-Ranges", "none");
        resp.setHeader("Feature-Policy", "fullscreen 'self'");
    }

    private void writeMessages(PrintWriter out, boolean isConfigure, String peerString) throws IOException {
        List<UIMessages.Message> msgs = _manager.getMessages();
        if (!msgs.isEmpty()) {
            out.write("<div id=\"screenlog\"");
            if (isConfigure)
                out.write(" configpage");
            if (!_manager.util().connected())
                out.write(" init");
             out.write("\" tabindex=\"0\">\n" +
                      "<a id=\"closelog\" href=\"" + _contextPath + '/');
            if (isConfigure)
                out.write("configure");
            if (peerString.length() > 0)
                out.write(peerString + "&amp;");
            else
                out.write("?");
            int lastID = msgs.get(msgs.size() - 1).id;
            out.write("action=Clear&amp;id=" + lastID + "&amp;nonce=" + _nonce + "\">");
            String tx = _t("clear messages");
            out.write(toThemeSVG("delete", tx, tx));
            out.write("</a>\n");
            out.write("<a class=\"script\" id=\"expand\" href=\"#\">");
            String x = _t("Expand");
            out.write(toThemeSVG("expand", x, x));
            out.write("</a>\n");
            out.write("<a class=\"script\" id=\"shrink\" href=\"#\">");
            String s = _t("Shrink");
            out.write(toThemeSVG("shrink", s, s));
            out.write("</a>\n");
            out.write("<ul>\n");
            out.write("<script src=\"" + _contextPath + WARBASE + "js/toggleLog.js?" + CoreVersion.VERSION + "\" type=\"text/javascript\"></script>\n");
            // FIXME only show once
            if (!_manager.util().connected()) {
                out.write("<noscript>\n<li class=\"noscriptWarning\">" +
                          _t("Warning! Javascript is disabled in your browser. If {0} is enabled, you will lose any input in the add/create torrent sections when a refresh occurs.",
                          "<a href=\"configure\">" + _t("page refresh") + "</a>"));
                out.write("</li>\n</noscript>\n");
            }

            for (int i = msgs.size()-1; i >= 0; i--) {
                String msg = msgs.get(i).message
                             .replace("Adding Magnet ", "Magnet added: " + "<span class=\"infohash\">")
                             .replace("Starting torrent: Magnet", "Starting torrent: <span class=\"infohash\">")
                             .replaceFirst(" \\(", "</span> (");
                if (msg.contains(_t("Warning - No I2P")))
                    msg = msg.replace("</span>", "");
                out.write("<li>" + msg + "</li>\n");
            }
            out.write("</ul>\n</div>\n");
            int delay = 0;
            delay = _manager.getRefreshDelaySeconds();
            if (delay > 0 && _context.isRouterContext())
                out.write("<script type=\"text/javascript\" src=\"" + _contextPath + WARBASE + "js/graphRefresh.js?" + CoreVersion.VERSION + "\"></script>\n");
                //out.write("<script type=\"text/javascript\" src=\"/themes/graphRefresh.js?" + CoreVersion.VERSION + "\"></script>\n"); // debugging
        }
    }

    /**
     *  @param canWrite is the data directory writable?
     *  @return true if on first page
     */
    private boolean writeTorrents(PrintWriter out, HttpServletRequest req, boolean canWrite) throws IOException {
        /** dl, ul, down rate, up rate, peers, size */
        final long stats[] = new long[6];
        String peerParam = req.getParameter("p");
        String stParam = req.getParameter("st");

        List<Snark> snarks = getSortedSnarks(req);
        boolean isForm = _manager.util().connected() || !snarks.isEmpty();
        boolean showStatusFilter = _manager.util().showStatusFilter();
        if (isForm) {
            if (showStatusFilter && !snarks.isEmpty() && _manager.util().connected())
              out.write("<form id=\"torrentlist\" class=\"filterbarActive\" action=\"_post\" method=\"POST\">\n");
            else
              out.write("<form id=\"torrentlist\" action=\"_post\" method=\"POST\">\n");
            if (showStatusFilter) {
                // selective display of torrents based on status
                // this should probably be done via a query string, but for now prototyping in js
                // then we can show all matches, not just those on page, and paginate as required
                if (!snarks.isEmpty() && _manager.util().connected()) {
                    // ensure we hide torrent filter bar (if enabled) and js is disabled
                    out.write("<noscript><style type=\"text/css\">.script {display: none;}</style></noscript>\n");
                    out.write("<div id=\"torrentDisplay\" class=\"script\">\n" +
                              "<input type=\"radio\" name=\"torrentDisplay\" id=\"all\" hidden><label for=\"all\" class=\"filterbutton\">Show All</label>" +
                              "<input type=\"radio\" name=\"torrentDisplay\" id=\"active\" hidden><label for=\"active\" class=\"filterbutton\">Active</label>" +
                              "<input type=\"radio\" name=\"torrentDisplay\" id=\"inactive\" hidden><label for=\"inactive\" class=\"filterbutton\">Inactive</label>" +
                              "<input type=\"radio\" name=\"torrentDisplay\" id=\"downloading\" hidden><label for=\"downloading\" class=\"filterbutton\">Downloading</label>" +
                              "<input type=\"radio\" name=\"torrentDisplay\" id=\"seeding\" hidden><label for=\"seeding\" class=\"filterbutton\">Seeding</label>" +
                              "<input type=\"radio\" name=\"torrentDisplay\" id=\"complete\" hidden><label for=\"complete\" class=\"filterbutton\">Complete</label>" +
                              "<input type=\"radio\" name=\"torrentDisplay\" id=\"incomplete\" hidden><label for=\"incomplete\" class=\"filterbutton\">Incomplete</label>" +
                              "<input type=\"radio\" name=\"torrentDisplay\" id=\"stopped\" hidden><label for=\"stopped\" class=\"filterbutton\">Stopped</label>\n" +
                              "</div>\n");
                }
            }
            writeHiddenInputs(out, req, null);
        }

        // Opera and text-mode browsers: no &thinsp; and no input type=image values submitted
        // Using a unique name fixes Opera, except for the buttons with js confirms, see below
        String ua = req.getHeader("User-Agent");
        boolean isDegraded = ua != null && ServletUtil.isTextBrowser(ua);
        boolean noThinsp = isDegraded || (ua != null && ua.startsWith("Opera"));

        // pages
        int start = 0;
        int total = snarks.size();
        if (stParam != null) {
            try {
                start = Math.max(0, Math.min(total - 1, Integer.parseInt(stParam)));
            } catch (NumberFormatException nfe) {}
        }
        int pageSize = Math.max(_manager.getPageSize(), 5);

        // move pagenav here so we can align it nicely without resorting to hacks
        if (total > 0 && (start > 0 || total > pageSize)) {
            out.write("<div class=\"pagenavcontrols\" id=\"pagenavtop\">");
            writePageNav(out, req, start, pageSize, total, noThinsp);
            out.write("</div>");
        }

        out.write(TABLE_HEADER);

        String currentSort = req.getParameter("sort");
        boolean showSort = total > 1;
        out.write("<tr>\n<th class=\"graphicStatus\">");
        // show incomplete torrents at top on first click
        String sort = "-2";
        if (showSort) {
            out.write("<span class=\"sortIcon\">");
            if (currentSort == null || "-2".equals(currentSort)) {
                sort = "2";
                if ( "-2".equals(currentSort))
                    out.write("<span class=\"ascending\"></span>");
            } else if ("2".equals(currentSort)) {
                sort = "-2";
                out.write("<span class=\"descending\"></span>");
            } else {
                sort = "2";
            }
            out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
            out.write("\">");
        }
        String tx = _t("Status");
        out.write(toThemeImg("status", tx, showSort ? _t("Sort by {0}", tx) : tx));
        if (showSort)
            out.write("</a></span>");
        out.write("</th>\n<th class=\"peerCount\">");
        if (_manager.util().connected() && !snarks.isEmpty()) {
            boolean hasPeers = false;
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if (snarks.get(i).getPeerCount() > 0) {
                    hasPeers = true;
                    break;
                }
            }
            if (hasPeers) {
                out.write(" <a href=\"" + _contextPath + '/');
                if (peerParam != null) {
                    // disable peer view
                    out.write(getQueryString(req, "", null, null));
                    out.write("\">");
                    tx = _t("Hide Peers");
                    out.write(toThemeImg("hidepeers", tx, tx));
                } else {
                    // enable peer view
                    out.write(getQueryString(req, "1", null, null));
                    out.write("\">");
                    tx = _t("Show Peers");
                    out.write(toThemeImg("showpeers", tx, tx));
                }
                out.write("</a>\n");
            }
        }
        out.write("</th>\n<th id=\"torrentSort\" colspan=\"2\" align=\"left\">");
        // cycle through sort by name or type
        boolean isTypeSort = false;
        if (showSort) {
            out.write("<span class=\"sortIcon\">");
            if (currentSort == null || "0".equals(currentSort) || "1".equals(currentSort)) {
                sort = "-1";
                if ("1".equals(currentSort) || currentSort == null)
                    out.write("<span class=\"ascending\"></span>");
            } else if ("-1".equals(currentSort)) {
                sort = "12";
                isTypeSort = true;
                out.write("<span class=\"descending\"></span>");
            } else if ("12".equals(currentSort)) {
                sort = "-12";
                isTypeSort = true;
                out.write("<span class=\"ascending\"></span>");
            } else {
                sort = "";
            }
            out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
            out.write("\">");
        }
        tx = _t("Torrent");
        if (!snarks.isEmpty()) {
            out.write(toThemeImg("torrent", tx,
                                 showSort ? _t("Sort by {0}", (isTypeSort ? _t("File type") : _t("Torrent name"))) : tx));
            if (showSort)
                out.write("</a></span>");
        }
        out.write("</th>\n<th class=\"torrentName\"></th>\n<th class=\"torrentETA\" align=\"right\">");
        // FIXME: only show icon when actively downloading, not uploading
        if (_manager.util().connected() && !snarks.isEmpty()) {
            boolean isDownloading = false;
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if ((snarks.get(i).getPeerCount() > 0) && (snarks.get(i).getDownloadRate() > 0)) {
                    isDownloading = true;
                    break;
                }
            }
            if (isDownloading) {
                if (showSort) {
                    // show lower ETA values at top
//                    sort = ("4".equals(currentSort)) ? "-4" : "4";
                    out.write("<span class=\"sortIcon\">");
                    if (currentSort == null || "-4".equals(currentSort)) {
                        sort = "4";
                        if ("-4".equals(currentSort))
                            out.write("<span class=\"descending\"></span>");
                    } else if ("4".equals(currentSort)) {
                        sort = "-4";
                        out.write("<span class=\"ascending\"></span>");
                    }
                    out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                    out.write("\">");
                }
            // Translators: Please keep short or translate as " "
            tx = _t("ETA");
            out.write(toThemeImg("eta", tx, showSort ? _t("Sort by {0}", _t("Estimated time remaining")) : _t("Estimated time remaining")));
                if (showSort)
                    out.write("</a></span>");
            }
         }
        out.write("</th>\n<th class=\"torrentDownloaded\" align=\"right\">");
        // cycle through sort by size or downloaded
        boolean isDlSort = false;
        if (!snarks.isEmpty()) {
            if (showSort) {
                out.write("<span class=\"sortIcon\">");
                if ("-5".equals(currentSort)) {
                    sort = "5";
                    out.write("<span class=\"descending\"></span>");
                } else if ("5".equals(currentSort)) {
                    sort = "-6";
                    isDlSort = true;
                    out.write("<span class=\"ascending\"></span>");
                } else if ("-6".equals(currentSort)) {
                    sort = "6";
                    isDlSort = true;
                    out.write("<span class=\"descending\"></span>");
                } else if ("6".equals(currentSort)) {
                    sort = "-5";
                    isDlSort = true;
                    out.write("<span class=\"ascending\"></span>");
                } else {
                    sort = "-5";
                }
                out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                out.write("\">");
            }
            // Translators: Please keep short or translate as " "
            tx = _t("RX");
            out.write(toThemeImg("head_rx", tx, showSort ? _t("Sort by {0}", (isDlSort ? _t("Downloaded") : _t("Size"))) : _t("Downloaded")));
            if (showSort)
                out.write("</a></span>");
        }
        out.write("</th>\n");
        out.write("<th class=\"RateDown\" align=\"right\">");
        // FIXME only show icon when total down rate > 0
        if (_manager.util().connected() && !snarks.isEmpty()) {
            boolean isDownloading = false;
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if ((snarks.get(i).getPeerCount() > 0) && (snarks.get(i).getDownloadRate() > 0)) {
                    isDownloading = true;
                    break;
                }
            }
            if (isDownloading) {
                if (showSort) {
                    out.write("<span class=\"sortIcon\">");
                    if (currentSort == null || "8".equals(currentSort)) {
                        sort = "-8";
                        if ("8".equals(currentSort))
                            out.write("<span class=\"descending\"></span>");
                    } else if ("-8".equals(currentSort)) {
                        sort = "8";
                        out.write("<span class=\"asscending\"></span>");
                    } else {
                        sort = "-8";
                    }
                out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                out.write("\">");
                }
                // Translators: Please keep short or translate as " "
                tx = _t("RX Rate");
                out.write(toThemeImg("head_rxspeed", tx, showSort ? _t("Sort by {0}", _t("Down Rate")) : _t("Down Rate")));
                if (showSort)
                    out.write("</a></span>");
            }
        }
        out.write("<th class=\"torrentUploaded\" align=\"right\">");
        boolean isRatSort = false;
        // cycle through sort by uploaded or ratio
        boolean nextRatSort = false;
        if (showSort) {
            out.write("<span class=\"sortIcon\">");
            if ("-7".equals(currentSort)) {
                sort = "7";
                out.write("<span class=\"descending\"></span>");
            } else if ("7".equals(currentSort)) {
                sort = "-11";
                nextRatSort = true;
                out.write("<span class=\"ascending\"></span>");
            } else if ("-11".equals(currentSort)) {
                sort = "11";
                nextRatSort = true;
                isRatSort = true;
                out.write("<span class=\"descending\"></span>");
            } else if ("11".equals(currentSort)) {
                sort = "-7";
                isRatSort = true;
                out.write("<span class=\"ascending\"></span>");
            } else {
                sort = "-7";
            }
            out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
            out.write("\">");
        }
        // Translators: Please keep short or translate as " "
        tx = _t("TX");
        out.write(toThemeImg("head_tx", tx, showSort ? _t("Sort by {0}", (nextRatSort ? _t("Upload ratio") : _t("Uploaded"))) : _t("Uploaded")));
        if (showSort)
            out.write("</a></span>");
        out.write("</th>\n");
        out.write("<th class=\"RateUp\" align=\"right\">");
        // FIXME only show icon when total up rate > 0 or no choked peers
        if (_manager.util().connected() && !snarks.isEmpty()) {
            boolean isUploading = false;
            int end = Math.min(start + pageSize, snarks.size());
            for (int i = start; i < end; i++) {
                if ((snarks.get(i).getPeerCount() > 0) && (snarks.get(i).getUploadRate() > 0)) {
                    isUploading = true;
                    break;
                }
            }
            if (isUploading) {
                if (showSort) {
                    out.write("<span class=\"sortIcon\">");
                    if (currentSort == null || "9".equals(currentSort)) {
                        sort = "-9";
                        if ("9".equals(currentSort))
                            out.write("<span class=\"ascending\"></span>");
                    } else if ("-9".equals(currentSort)) {
                        sort = "9";
                        out.write("<span class=\"descending\"></span>");
                    } else {
                        sort = "-9";
                    }
                    out.write("<a href=\"" + _contextPath + '/' + getQueryString(req, null, null, sort));
                    out.write("\">");
                }
                // Translators: Please keep short or translate as " "
                tx = _t("TX Rate");
                out.write(toThemeImg("head_txspeed", tx, showSort ? _t("Sort by {0}", _t("Up Rate")) : _t("Up Rate")));
                if (showSort)
                    out.write("</a></span>");
            }
        }
        out.write("</th>\n");
        out.write("<th class=\"torrentAction\" align=\"center\">");
        if (_manager.isStopping()) {
            out.write("");
        } else if (_manager.util().connected()) {
            out.write("<input type=\"submit\" id=\"actionStopAll\" name=\"action_StopAll\" value=\"" +
                      _t("Stop All") + "\" title=\"" + _t("Stop all torrents and the I2P tunnel") + "\">");
            for (Snark s : snarks) {
                if (s.isStopped()) {
                    // show startall too
                    out.write("<input type=\"submit\" id=\"actionStartAll\" name=\"action_StartAll\" value=\"" +
                             _t("Start All") + "\" title=\"" + _t("Start all stopped torrents") + "\">");
                    break;
                }
            }
        } else if ((!_manager.util().isConnecting()) && !snarks.isEmpty()) {
            out.write("<input type=\"submit\" id=\"actionStartAll\" name=\"action_StartAll\" value=\"" +
                      _t("Start All") + "\" title=\"" + _t("Start all torrents and the I2P tunnel") + "\">");
        } else {
            out.write("");
        }
        out.write("</th>\n</tr>\n</thead>\n<tbody id=\"snarkTbody\">\n");
        String uri = _contextPath + '/';
        boolean showDebug = "2".equals(peerParam);

        for (int i = 0; i < total; i++) {
            Snark snark = snarks.get(i);
            boolean showPeers = showDebug || "1".equals(peerParam) || Base64.encode(snark.getInfoHash()).equals(peerParam);
            boolean hide = i < start || i >= start + pageSize;
            displaySnark(out, req, snark, uri, i, stats, showPeers, isDegraded, noThinsp, showDebug, hide, isRatSort, canWrite);
        }

        if (total == 0) {
            out.write("<tr id=\"noload\" class=\"noneLoaded\"><td colspan=\"11\"><i>");
            synchronized(this) {
                File dd = _resourceBase;
                if (!dd.exists() && !dd.mkdirs()) {
                    out.write(_t("Data directory cannot be created") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!dd.isDirectory()) {
                    out.write(_t("Not a directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!dd.canRead()) {
                    out.write(_t("Unreadable") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else if (!canWrite) {
                    out.write(_t("No write permissions for data directory") + ": " + DataHelper.escapeHTML(dd.toString()));
                } else {
                    out.write(_t("No torrents loaded."));
                }
            }
            out.write("</i></td></tr>\n</tbody>\n");
            out.write("<tfoot id=\"snarkFoot");
            if (_manager.util().isConnecting() || !_manager.util().connected())
                out.write("\" class=\"initializing");
            out.write("\">\n<tr>\n<th id=\"torrentTotals\" align=\"left\" colspan=\"11\"></th></tr></tfoot>");
        } else /** if (snarks.size() > 1) */ {

            // Add a pagenav to bottom of table if we have 50+ torrents per page
            // TODO: disable on pages where torrents is < 50 e.g. last page
            if (total > 0 && (start > 0 || total > pageSize) && pageSize >= 50 && total - start >= 20) {
                out.write("<tr id=\"pagenavbottom\"><td colspan=\"11\"><div class=\"pagenavcontrols\">");
                writePageNav(out, req, start, pageSize, total, noThinsp);
                out.write("</div></td></tr>\n</tbody>\n");
            }
            out.write("<tfoot id=\"snarkFoot\">\n<tr class=\"volatile\">\n<th id=\"torrentTotals\" align=\"left\" colspan=\"5\">");
            out.write("<span id=\"totals\"><span class=\"canhide\">");
            out.write(_t("Totals"));
            out.write(":&nbsp;</span>");
            out.write(ngettext("1 torrent", "{0} torrents", total));
            out.write(" &bullet; ");
            out.write(DataHelper.formatSize2(stats[5]) + "B");
            if (_manager.util().connected() && total > 0 && stats[4] > 0) {
                out.write(" &bullet; ");
                out.write(ngettext("1 connected peer", "{0} connected peers", (int) stats[4])
                   .replace("connected peers", "peer connections"));
            }

            DHT dht = _manager.util().getDHT();
            if (dht != null) {
                int dhts = dht.size();
                if (dhts > 0) {
                    out.write(" &bullet; <span>");
                    out.write(ngettext("1 DHT peer", "{0} DHT peers", dhts));
                    out.write("</span>");
                }
            }

            String ibtunnels = _manager.util().getI2CPOptions().get("inbound.quantity");
            String obtunnels = _manager.util().getI2CPOptions().get("outbound.quantity");
            if (_manager.util().connected()) {
                out.write("<span class=\"canhide\" title=\"" + _t("Configured maximum (actual usage may be less)") +
                          "\"> &bullet; " + _t("Tunnels") + ": ");
                out.write(ibtunnels);
                out.write(" in / ");
                out.write(obtunnels);
                out.write(" out</span>");
            }

            String IPString = _manager.util().getOurIPString();
            if (!IPString.equals("unknown")) {
                // Only truncate if it's an actual dest
                out.write("&nbsp;<span id=\"ourDest\" title=\"");
                out.write(_t("Our destination (identity) for this session"));
                out.write("\">");
                out.write(_t("Dest."));
                out.write("<tt title=\"");
                out.write(_t("Our destination (identity) for this session"));
                out.write("\">");
                out.write(IPString.substring(0, 4));
                out.write("</tt></span>");
            }
            out.write("</span>");
            out.write("</th>\n");
            if (_manager.util().connected() && total > 0) {
                out.write("<th class=\"torrentETA\" align=\"right\">");
                // FIXME: add total ETA for all torrents here
                // out.write("<th class=\"torrentETA\" align=\"right\" title=\"");
                // out.write(_t("Estimated download time for all torrents") + "\">");

                if (_manager.util().connected() && !snarks.isEmpty()) {
                    boolean hasPeers = false;
                    long remainingSeconds = 0;
                    long totalETA = 0;
                    int end = Math.min(start + pageSize, snarks.size());
                    for (int i = start; i < end; i++) {
                        long needed = snarks.get(i).getNeededLength();
                        if (needed > total)
                            needed = total;
                            if (stats[2] > 0 && needed > 0) {
                                remainingSeconds = needed / stats[2];
                            } else {
                                remainingSeconds = 0;
                            }
                        totalETA+= remainingSeconds;
                        hasPeers = true;
                        if (hasPeers) {
                            if (totalETA > 0) {
                                out.write("<span title=\"");
                                out.write(_t("Estimated download time for all torrents") + "\">");
                                out.write(DataHelper.formatDuration2(Math.max(totalETA, 10) * 1000));
                                out.write("</span>");
                            }
                        }
                        break;
                    }
                }
                out.write("</th>\n");
                out.write("<th class=\"torrentDownloaded\" align=\"right\" title=\"");
                out.write(_t("Data downloaded this session") + "\">");
                if (stats[0] > 0) {
                    out.write(formatSize(stats[0]).replaceAll("iB", ""));
                }
                out.write("</th>\n");
                out.write("<th class=\"RateDown\" title=\"");
                out.write(_t("Total download speed") + "\">");
                if (stats[2] > 0) {
                    out.write(formatSize(stats[2]).replaceAll("iB", "") + "/s");
                }
                out.write("</th>\n");
                out.write("<th class=\"torrentUploaded\" align=\"right\"  title=\"");
                out.write(_t("Total data uploaded (for listed torrents)") + "\">");
                if (stats[1] > 0) {
                    out.write(formatSize(stats[1]).replaceAll("iB", ""));
                }
                out.write("</th>\n");
                out.write("<th class=\"RateUp\" align=\"right\" title=\"");
                out.write(_t("Total upload speed") + "\">");
                boolean isUploading = false;
                int end = Math.min(start + pageSize, snarks.size());
                for (int i = start; i < end; i++) {
                    if ((snarks.get(i).getPeerCount() > 0) && (snarks.get(i).getUploadRate() > 0)) {
                        isUploading = true;
                        break;
                    }
                }
                if (stats[3] > 0 && isUploading) {
                    out.write(formatSize(stats[3]).replaceAll("iB", "") + "/s");
                }
                out.write("</th>\n");
                out.write("<th class=\"torrentAction\">");
                if (dht != null && (!"2".equals(peerParam))) {
                    out.write("<a id=\"debugMode\" href=\"?p=2\" title=\"");
                    out.write(_t("Enable Debug Mode") + "\">");
                    out.write(_t("Debug Mode") + "</a>");
                } else if (dht != null) {
                    out.write("<a id=\"debugMode\" href=\"?p\" title=\"");
                    out.write(_t("Disable Debug Mode") + "\">");
                    out.write(_t("Normal Mode") + "</a>");
                }
                out.write("</th>");
                } else {
                    out.write("<th colspan=\"6\"></th>");
                }
                out.write("\n</tr>\n");

                if (showDebug) {
                    out.write("<tr id=\"dhtDebug\">\n");
                    out.write("<th colspan=\"12\">\n<span class=\"volatile\">");
                    if (dht != null) {
                        out.write(dht.renderStatusHTML());
                    } else {
                        out.write("<b>");
                        out.write(_t("No DHT Peers"));
                        out.write("</b>");
                    }
                    out.write("\n</span>\n</th>\n</tr>\n");
                }
                out.write("</tfoot>\n");
            }

            out.write("</table>\n");
            if (isForm)
                out.write("</form>\n");

            // load torrentDisplay script here to ensure table has loaded into dom
            if (_contextName.equals(DEFAULT_NAME) && showStatusFilter) {
                out.write("<script src=\"" + _contextPath + WARBASE + "js/torrentDisplay.js?" + CoreVersion.VERSION +
                          "\" type=\"text/javascript\" async></script>\n");
                //out.write("<script src=\"/themes/torrentDisplay.js?" + CoreVersion.VERSION + "\" type=\"text/javascript\" async></script>\n"); // debugging
            }
            return start == 0;
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
     *  hidden inputs for nonce and paramters p, st, and sort
     *
     *  @param buf appends to it
     *  @param action if non-null, add it as the action
     *  @since 0.9.16
     */
    private void writeHiddenInputs(StringBuilder buf, HttpServletRequest req, String action) {
        buf.append("<input type=\"hidden\" name=\"nonce\" value=\"")
           .append(_nonce).append("\" >\n");
        String peerParam = req.getParameter("p");
        if (peerParam != null) {
            buf.append("<input type=\"hidden\" name=\"p\" value=\"")
               .append(DataHelper.stripHTML(peerParam)).append("\" >\n");
        }
        String stParam = req.getParameter("st");
        if (stParam != null) {
            buf.append("<input type=\"hidden\" name=\"st\" value=\"")
               .append(DataHelper.stripHTML(stParam)).append("\" >\n");
        }
        String soParam = req.getParameter("sort");
        if (soParam != null) {
            buf.append("<input type=\"hidden\" name=\"sort\" value=\"")
               .append(DataHelper.stripHTML(soParam)).append("\" >\n");
        }
        if (action != null) {
            buf.append("<input type=\"hidden\" name=\"action\" value=\"")
               .append(action).append("\" >\n");
        }
    }

    /**
     *  Build HTML-escaped and stripped query string
     *
     *  @param p override or "" for default or null to keep the same as in req
     *  @param st override or "" for default or null to keep the same as in req
     *  @param so override or "" for default or null to keep the same as in req
     *  @return non-null, possibly empty
     *  @since 0.9.16
     */
    private static String getQueryString(HttpServletRequest req, String p, String st, String so) {
/*
        long now = System.currentTimeMillis();
        String time = String.valueOf(now);
*/
        String url = req.getRequestURL().toString();
        StringBuilder buf = new StringBuilder(64);
        if (p == null) {
            p = req.getParameter("p");
            if (p != null)
                p = DataHelper.stripHTML(p);
        }
        if (p != null && !p.equals(""))
            buf.append("?p=").append(p);
        if (so == null) {
            so = req.getParameter("sort");
            if (so != null)
                so = DataHelper.stripHTML(so);
        }
        if (so != null && !so.equals("")) {
            if (buf.length() <= 0)
                buf.append("?sort=");
            else
                buf.append("&amp;sort=");
            buf.append(so);
        }
        if (st == null) {
            st = req.getParameter("st");
            if (st != null)
                st = DataHelper.stripHTML(st);
        }
        if (st != null && !st.equals("")) {
            if (buf.length() <= 0)
                buf.append("?st=");
            else
                buf.append("&amp;st=");
            buf.append(st);
        }
/*
        if ((p == null || !p.equals("")) && (so == null || so.equals("")) && (st == null || st.equals("")) || url.contains("/configure"))
            buf.append("?time=").append(time);
        else
            buf.append("&amp;time=").append(time);
*/
        return buf.toString();
    }

    /**
     *  @since 0.9.6
     */
    private void writePageNav(PrintWriter out, HttpServletRequest req, int start, int pageSize, int total,
                              boolean noThinsp) {
            // Page nav
            if (start > 0) {
                // First
                out.write("<a href=\"" + _contextPath);
                out.write(getQueryString(req, null, "", null));
                out.write("\"><span id=\"first\">");
                out.write(toThemeSVG("first", _t("First"), _t("First page")));
                out.write("</span></a>");
                int prev = Math.max(0, start - pageSize);
                //if (prev > 0) {
                if (true) {
                    // Back
                    out.write("<a href=\"" + _contextPath);
                    String sprev = (prev > 0) ? Integer.toString(prev) : "";
                    out.write(getQueryString(req, null, sprev, null));
                    out.write("\"><span id=\"previous\">");
                    out.write(toThemeSVG("previous", _t("Prev"), _t("Previous page")));
                    out.write("</span></a>");
                }
            } else {
                out.write(
                          "<span id=\"first\" class=\"disable\"><img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "first.svg\"></span>" +
                          "<span id=\"previous\" class=\"disable\"><img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "previous.svg\"></span>");
            }
            // Page count
            int pages = 1 + ((total - 1) / pageSize);
            if (pages == 1 && start > 0)
                pages = 2;
            if (pages > 1) {
                int page;
                if (start + pageSize >= total)
                    page = pages;
                else
                    page = 1 + (start / pageSize);
                out.write("<span id=\"pagecount\">" + page + thinsp(noThinsp) + pages + "</span>");
            }
            if (start + pageSize < total) {
                int next = start + pageSize;
                //if (next + pageSize < total) {
                if (true) {
                    // Next
                    out.write("<a href=\"" + _contextPath);
                    out.write(getQueryString(req, null, Integer.toString(next), null));
                    out.write("\"><span id=\"next\">");
                    out.write(toThemeSVG("next", _t("Next"), _t("Next page")));
                    out.write("</span></a>");
                }
                // Last
                int last = ((total - 1) / pageSize) * pageSize;
                out.write("<a href=\"" + _contextPath);
                out.write(getQueryString(req, null, Integer.toString(last), null));
                out.write("\"><span id=\"last\">");
                out.write(toThemeSVG("last", _t("Last"), _t("Last page")));
                out.write("</span></a>");
            } else {
                out.write("<span id=\"next\" class=\"disable\"><img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "next.svg\"></span>" +
                          "<span id=\"last\" class=\"disable\"><img alt=\"\" border=\"0\" class=\"disable\" src=\"" +
                          _imgPath + "last.svg\">");
            }
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
                _manager.addMessage("No action specified");
                return;
            }
        }
        // sadly, Opera doesn't send value with input type=image, so we have to use GET there
        //if (!"POST".equals(req.getMethod())) {
        //    _manager.addMessage("Action must be with POST");
        //    return;
        //}
        if ("Add".equals(action)) {
            String newURL = req.getParameter("nofilter_newURL");
         /******
            // NOTE - newFile currently disabled in HTML form - see below
            File f = null;
            if ( (newFile != null) && (newFile.trim().length() > 0) )
                f = new File(newFile.trim());
            if ( (f != null) && (!f.exists()) ) {
                _manager.addMessage(_t("Torrent file {0} does not exist", newFile));
            }
            if ( (f != null) && (f.exists()) ) {
                // NOTE - All this is disabled - load from local file disabled
                File local = new File(_manager.getDataDir(), f.getName());
                String canonical = null;
                try {
                    canonical = local.getCanonicalPath();

                    if (local.exists()) {
                        if (_manager.getTorrent(canonical) != null)
                            _manager.addMessage(_t("Torrent already running: {0}", newFile));
                        else
                            _manager.addMessage(_t("Torrent already in the queue: {0}", newFile));
                    } else {
                        boolean ok = FileUtil.copy(f.getAbsolutePath(), local.getAbsolutePath(), true);
                        if (ok) {
                            _manager.addMessage(_t("Copying torrent to {0}", local.getAbsolutePath()));
                            _manager.addTorrent(canonical);
                        } else {
                            _manager.addMessage(_t("Unable to copy the torrent to {0}", local.getAbsolutePath()) + ' ' + _t("from {0}", f.getAbsolutePath()));
                        }
                    }
                } catch (IOException ioe) {
                    _log.warn("hrm: " + local, ioe);
                }
            } else
          *****/
            if (newURL != null) {
                newURL = newURL.trim();
                String newDir = req.getParameter("nofilter_newDir");
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
                            if (storage == null)
                                continue;
                            File sbase = storage.getBase();
                            if (isParentOf(sbase, dir)) {
                                _manager.addMessage(_t("Cannot add torrent {0} inside another torrent: {1}",
                                                      dir.getAbsolutePath(), sbase));
                                return;
                            }
                        }
                    }
                }
                File dd = _manager.getDataDir();
                if (!dd.canWrite()) {
                    _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                    return;
                }
                if (newURL.startsWith("http://") || newURL.startsWith("https://")) {
                    if (isI2PTracker(newURL)) {
                        FetchAndAdd fetch = new FetchAndAdd(_context, _manager, newURL, dir);
                        _manager.addDownloader(fetch);
                    } else {
                        // TODO
                        _manager.addMessageNoEscape(_t("Download from non-I2P location {0} is not supported", urlify(newURL)));
                    }
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
                    // hex v2 multihash
                    // TODO
                    _manager.addMessage("Error: Version 2 info hashes are not supported");
                    //addMagnet(MagnetURI.MAGNET_FULL_V2 + newURL, dir);
                } else {
                    // try as file path, hopefully we're on the same box
                    if (newURL.startsWith("file://"))
                        newURL = newURL.substring(7);
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
                                _manager.addMessage(_t("Torrent with this info hash is already running: {0}", snark.getBaseName()).replace("Magnet ", ""));
                                return;
                            }

                            // check for dup file name
                            String originalName = Storage.filterName(name);
                            name = originalName + ".torrent";
                            File torrentFile = new File(dd, name);
                            String canonical = torrentFile.getCanonicalPath();
                            if (torrentFile.exists()) {
                                if (_manager.getTorrent(canonical) != null)
                                    _manager.addMessage(_t("Torrent already running: {0}", name));
                                else
                                    _manager.addMessage(_t("Torrent already in the queue: {0}", name));
                            } else {
                                // This may take a LONG time to create the storage.
                                boolean ok = _manager.copyAndAddTorrent(file, canonical, dd);
                                if (!ok)
                                    throw new IOException("Unknown error - check logs");
                                snark = _manager.getTorrentByBaseName(originalName);
                                if (snark != null)
                                    snark.startTorrent();
                                else
                                    throw new IOException("Unknown error - check logs");
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
            } else {
                // no file or URL specified
            }
        } else if (action.startsWith("Stop_")) {
            String torrent = action.substring(5);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (String name : _manager.listTorrentFiles() ) {
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
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
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    _manager.startTorrent(infoHash);
                }
            }
        } else if (action.startsWith("Remove_")) {
            String torrent = action.substring(7);
            if (torrent != null) {
                byte infoHash[] = Base64.decode(torrent);
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (String name : _manager.listTorrentFiles() ) {
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
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
                            if (f.delete()) {
                                _manager.addMessage(_t("Torrent file deleted: {0}", f.getAbsolutePath()));
                            } else if (f.exists()) {
                                if (!canDelete)
                                    _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
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
                if ( (infoHash != null) && (infoHash.length == 20) ) { // valid sha1
                    for (String name : _manager.listTorrentFiles() ) {
                        Snark snark = _manager.getTorrent(name);
                        if ( (snark != null) && (DataHelper.eq(infoHash, snark.getInfoHash())) ) {
                            MetaInfo meta = snark.getMetaInfo();
                            if (meta == null) {
                                // magnet - remove and delete are the same thing
                                name = name.replace("Magnet ", "");
                                _manager.deleteMagnet(snark);
                                if (snark instanceof FetchAndAdd)
                                    _manager.addMessage(_t("Download deleted: {0}", name));
                                else
                                    _manager.addMessage(_t("Magnet deleted: {0}", name));
                                return;
                            }
                            File f = new File(name);
                            File dd = _manager.getDataDir();
                            boolean canDelete = dd.canWrite() || !f.exists();
                            _manager.stopTorrent(snark, canDelete);
                            // TODO race here with the DirMonitor, could get re-added
                            if (f.delete()) {
                                _manager.addMessage(_t("Torrent file deleted: {0}", f.getAbsolutePath()));
                            } else if (f.exists()) {
                                if (!canDelete)
                                    _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                                _manager.addMessage(_t("Torrent file could not be deleted: {0}", f.getAbsolutePath()));
                                return;
                            }
                            Storage storage = snark.getStorage();
                            if (storage == null)
                                break;
                            List<List<String>> files = meta.getFiles();
                            if (files == null) { // single file torrent
                                for (File df : storage.getFiles()) {
                                    // should be only one
                                    if (df.delete())
                                        _manager.addMessage(_t("Data file deleted: {0}", df.getAbsolutePath()));
                                    else if (df.exists())
                                        _manager.addMessage(_t("Data file could not be deleted: {0}", df.getAbsolutePath()));
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
                            if (dirs == null)
                                break;  // directory deleted out from under us
                            if (_log.shouldLog(Log.INFO))
                                _log.info("Dirs to delete: " + DataHelper.toString(dirs));
                            boolean ok = false;
                            for (File df : dirs) {
                                if (df.delete()) {
                                    ok = true;
                                    //_manager.addMessage(_t("Data dir deleted: {0}", df.getAbsolutePath()));
                                } else if (df.exists()) {
                                    ok = false;
                                    _manager.addMessage(_t("Directory could not be deleted: {0}", df.getAbsolutePath()));
                                    if (_log.shouldLog(Log.WARN))
                                        _log.warn("Could not delete dir " + df);
                                // else already gone
                                }
                            }
                            // step 3 message for base (last one)
                            if (ok)
                                _manager.addMessage(_t("Directory deleted: {0}", storage.getBase()));
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
            _manager.updateConfig(dataDir, filesPublic, autoStart, smartSort, refreshDel, startupDel, pageSize,
                                  seedPct, eepHost, eepPort, i2cpHost, i2cpPort, i2cpOpts,
                                  upLimit, upBW, useOpenTrackers, useDHT, theme,
                                  lang, ratings, comments, commentsName, collapsePanels, showStatusFilter, enableLightbox, enableAddCreate);
            // update servlet
            try {
                setResourceBase(_manager.getDataDir());
            } catch (ServletException se) {}
        } else if ("Save2".equals(action)) {
            String taction = req.getParameter("taction");
            if (taction != null)
                processTrackerForm(taction, req);
        } else if ("Create".equals(action)) {
            String baseData = req.getParameter("nofilter_baseFile");
            if (baseData != null && baseData.trim().length() > 0) {
                File baseFile = new File(baseData.trim());
                if (!baseFile.isAbsolute())
                    baseFile = new File(_manager.getDataDir(), baseData);
                String announceURL = req.getParameter("announceURL");
                // make the user add a tracker on the config form now
                //String announceURLOther = req.getParameter("announceURLOther");
                //if ( (announceURLOther != null) && (announceURLOther.trim().length() > "http://.i2p/announce".length()) )
                //    announceURL = announceURLOther;
                String comment = req.getParameter("comment");

                if (baseFile.exists()) {
                    File dd = _manager.getDataDir();
                    if (!dd.canWrite()) {
                        _manager.addMessage(_t("No write permissions for data directory") + ": " + dd);
                        return;
                    }
                    String torrentName = baseFile.getName();
                    if (torrentName.toLowerCase(Locale.US).endsWith(".torrent")) {
                        _manager.addMessage(_t("Cannot add a torrent ending in \".torrent\": {0}", baseFile.getAbsolutePath()));
                        return;
                    }
                    Snark snark = _manager.getTorrentByBaseName(torrentName);
                    if (snark != null) {
                        _manager.addMessage(_t("Torrent with this name is already running: {0}", torrentName));
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
                        if (storage == null)
                            continue;
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

                    if (announceURL.equals("none"))
                        announceURL = null;
                    _lastAnnounceURL = announceURL;
                    List<String> backupURLs = new ArrayList<String>();
                    Enumeration<?> e = req.getParameterNames();
                    while (e.hasMoreElements()) {
                         Object o = e.nextElement();
                         if (!(o instanceof String))
                             continue;
                         String k = (String) o;
                        if (k.startsWith("backup_")) {
                            String url = k.substring(7);
                            if (!url.equals(announceURL))
                                backupURLs.add(DataHelper.stripHTML(url));
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
                            if (_manager.getPrivateTrackers().contains(url))
                                hasPrivate = true;
                            else
                                hasPublic = true;
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
                        Storage s = new Storage(_manager.util(), baseFile, announceURL, announceList, null, isPrivate, null);
                        s.close(); // close the files... maybe need a way to pass this Storage to addTorrent rather than starting over
                        MetaInfo info = s.getMetaInfo();
                        File torrentFile = new File(_manager.getDataDir(), s.getBaseName() + ".torrent");
                        // FIXME is the storage going to stay around thanks to the info reference?
                        // now add it, but don't automatically start it
                        boolean ok = _manager.addTorrent(info, s.getBitField(), torrentFile.getAbsolutePath(), baseFile, true);
                        if (!ok)
                            return;
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
            _manager.stopAllTorrents(false);
        } else if ("StartAll".equals(action)) {
            _manager.startAllTorrents();
        } else if ("Clear".equals(action)) {
            String sid = req.getParameter("id");
            if (sid != null) {
                try {
                    int id = Integer.parseInt(sid);
                    _manager.clearMessages(id);
                } catch (NumberFormatException nfe) {}
            }
        } else {
            _manager.addMessage("Unknown POST action: \"" + action + '\"');
        }
    }

    /**
     *  Redirect a POST to a GET (P-R-G), preserving the peer string
     *  @since 0.9.5
     */
    private void sendRedirect(HttpServletRequest req, HttpServletResponse resp, String p) throws IOException {
        String url = req.getRequestURL().toString();
        StringBuilder buf = new StringBuilder(128);
        if (url.endsWith("_post"))
            url = url.substring(0, url.length() - 5);
        buf.append(url);
        if (p.length() > 0)
            buf.append(p.replace("&amp;", "&"));  // no you don't html escape the redirect header
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
                aurl = DataHelper.stripHTML(aurl.trim()).replace("=", "&#61;");
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

    private static final String iopts[] = {"inbound.length", "inbound.quantity",
                                           "outbound.length", "outbound.quantity" };

    /** put the individual i2cp selections into the option string */
    private static String buildI2CPOpts(HttpServletRequest req) {
        StringBuilder buf = new StringBuilder(128);
        String p = req.getParameter("i2cpOpts");
        if (p != null)
            buf.append(p);
        for (int i = 0; i < iopts.length; i++) {
            p = req.getParameter(iopts[i]);
            if (p != null)
                buf.append(' ').append(iopts[i]).append('=').append(p);
        }
        return buf.toString();
    }

    private List<Snark> getSortedSnarks(HttpServletRequest req) {
        ArrayList<Snark> rv = new ArrayList<Snark>(_manager.getTorrents());
        if (rv.size() > 1) {
            int sort = 0;
            String ssort = req.getParameter("sort");
            if (ssort != null) {
                try {
                    sort = Integer.parseInt(ssort);
                } catch (NumberFormatException nfe) {}
            }
            String lang;
            if (_manager.isSmartSortEnabled())
                lang = Translate.getLanguage(_manager.util().getContext());
            else
                lang = null;
            // Java 7 TimSort - may be unstable
            DataHelper.sort(rv, Sorters.getComparator(sort, lang, this));
        }
        return rv;
    }

    private static final int MAX_DISPLAYED_FILENAME_LENGTH = 255;
    private static final int MAX_DISPLAYED_ERROR_LENGTH = 43;

    /**
     *  Display one snark (one line in table, unless showPeers is true)
     *
     *  @param stats in/out param (totals)
     *  @param statsOnly if true, output nothing, update stats only
     *  @param canWrite is the i2psnark data directory writable?
     */
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
        int curPeers = snark.getPeerCount();
        stats[4] += curPeers;
        long total = snark.getTotalLength();
        if (total > 0)
            stats[5] += total;
        if (statsOnly)
            return;

        String basename = snark.getBaseName();
        String fullBasename = basename;
        if (basename.length() > MAX_DISPLAYED_FILENAME_LENGTH) {
            String start = ServletUtil.truncate(basename, MAX_DISPLAYED_FILENAME_LENGTH);
            if (start.indexOf(' ') < 0 && start.indexOf('-') < 0) {
                // browser has nowhere to break it
                basename = start + HELLIP;
            }
        }
        // includes skipped files, -1 for magnet mode
        long remaining = snark.getRemainingLength();
        if (remaining > total)
            remaining = total;
        // does not include skipped files, -1 for magnet mode or when not running.
        long needed = snark.getNeededLength();
        if (needed > total)
            needed = total;
        long remainingSeconds;
        if (downBps > 0 && needed > 0)
            remainingSeconds = needed / downBps;
        else
            remainingSeconds = -1;

        MetaInfo meta = snark.getMetaInfo();
        String b64 = Base64.encode(snark.getInfoHash());
        String b64Short = b64.substring(0, 6);
        // isValid means isNotMagnet
        boolean isValid = meta != null;
        boolean isMultiFile = isValid && meta.getFiles() != null;

        String err = snark.getTrackerProblems();
        int knownPeers = Math.max(curPeers, snark.getTrackerSeenPeers());

        String statusString;
        // add status to table rows so we can selectively show/hide snarks and style based on status
        String snarkStatus;
        String rowClass = (row % 2 == 0 ? "rowEven" : "rowOdd");
        if (snark.isChecking()) {
            (new DecimalFormat("0.00%")).format(snark.getCheckingProgress());
            statusString = toSVGWithDataTooltip("processing", "", _t("Checking")) + "</td>" +
                                 "<td class=\"peerCount\"><b><span class=\"right\">" +
                                 curPeers + "</span>" + thinsp(noThinsp) + "<span class=\"left\">" + knownPeers + "</span>";
/*
                                 "<div class=\"percentBarOuter filecheck\" style=\"max-width: 60px; height: 8px !important;\" title=\"" +
                                 _t("Checking file integrity") + "\">" + "<div class=\"percentBarInner\" style=\"height: 8px !important; width: " +
                                 (new DecimalFormat("0%")).format(snark.getCheckingProgress()) + "\">" +
                                 "<div class=\"percentBarText\"></div></div></div>";
*/
            snarkStatus = "active starting processing";
        } else if (snark.isAllocating()) {
            statusString = toSVGWithDataTooltip("processing", "", _t("Allocating")) + "</td>" +
                           "<td class=\"peerCount\"><b>";
            snarkStatus = "active starting processing";
        } else if (err != null && isRunning && curPeers == 0) {
            statusString = toSVGWithDataTooltip("error", "", err) + "</td>\n" +
                                 "<td class=\"peerCount\"><b><span class=\"right\">" +
                                 curPeers + "</span>" + thinsp(noThinsp) + "<span class=\"left\">" + knownPeers + "</span>";
            snarkStatus = "inactive downloading incomplete neterror";
        } else if (snark.isStarting()) {
            statusString = toSVGWithDataTooltip("stalled", "", _t("Starting")) + "</td>\n" +
                           "<td class=\"peerCount\"><b>";
            snarkStatus = "active starting";
        } else if (remaining == 0 || needed == 0) {  // < 0 means no meta size yet
            // partial complete or seeding
            if (isRunning) {
                String img;
                String txt;
                String tooltip;
                if (remaining == 0) {
                    img = "seeding";
                    txt = _t("Seeding");
                    tooltip = ngettext("Seeding to {0} peer", "Seeding to {0} peers", curPeers);
                    snarkStatus = "active seeding complete";
                    if (curPeers > 0)
                        img = "seeding_active";
                } else {
                    // partial
                    img = "complete";
                    txt = _t("Complete");
                    tooltip = txt;
                    snarkStatus = "complete";
                    if (curPeers > 0) {
                        tooltip = txt + " (" + _t("Seeding to {0} of {1} peers in swarm", curPeers, knownPeers) + ")";
                        if (upBps > 0) {
                            snarkStatus = "active seeding complete";
                        } else {
                            snarkStatus = "inactive seeding complete";
                        }
                    }
                }
                if (curPeers > 0 && !showPeers) {
                    statusString = toSVGWithDataTooltip(img, "", tooltip) + "</td>\n" +
                                         "<td class=\"peerCount\"><b>" +
                                         "<a href=\"" +
                                         uri + getQueryString(req, b64, null, null) + "\"><span class=\"right\">" +
                                         curPeers + "</span>" + thinsp(noThinsp) + "<span class=\"left\">" + knownPeers + "</span></a>";
                    if (upBps > 0) {
                        snarkStatus = "active seeding complete";
                    } else {
                        snarkStatus = "inactive seeding complete";
                    }
                } else if (curPeers > 0) {
                    statusString = toSVGWithDataTooltip(img, "", tooltip) + "</td>\n" +
                                         "<td class=\"peerCount\"><b><a href=\"" + uri + "\" title=\"" +
                                         _t("Hide Peers") + "\">" + "<span class=\"right\">" + curPeers + "</span>" + thinsp(noThinsp) +
                                         "<span class=\"left\">" + knownPeers + "</span></a>";
                    if (upBps > 0) {
                        snarkStatus = "active seeding complete";
                    } else {
                        snarkStatus = "inactive seeding complete";
                    }
                } else {
                    statusString = toSVGWithDataTooltip(img, "", tooltip) + "</td>\n" +
                               "<td class=\"peerCount\"><b><span class=\"right\">" + curPeers +
                               "</span>" + thinsp(noThinsp) + "<span class=\"left\">" + knownPeers + "</span>";
                    if (upBps > 0 || curPeers > 0) {
                        snarkStatus = "active seeding complete";
                    } else {
                        snarkStatus = "inactive seeding complete";
                    }
                }
            } else {
                statusString = toSVGWithDataTooltip("complete", "", _t("Complete")) + "</td>\n" +
                                     "<td class=\"peerCount\"><b>‒";
                snarkStatus = "inactive complete stopped zero";
            }
        } else {
            if (isRunning && curPeers > 0 && downBps > 0 && !showPeers) {
                statusString = toSVGWithDataTooltip("downloading", "", _t("OK") +
                                     " (" + _t("Downloading from {0} of {1} peers in swarm", curPeers, knownPeers) + ")") + "</td>\n" +
                                     "<td class=\"peerCount\"><b><a href=\"" +
                                     uri + getQueryString(req, b64, null, null) + "\"><span class=\"right\">" +
                                     curPeers + "</span>" + thinsp(noThinsp) + "<span class=\"left\">" + knownPeers + "</span></a>";
                snarkStatus = "active downloading incomplete";
            } else if (isRunning && curPeers > 0 && downBps > 0) {
                statusString = toSVGWithDataTooltip("downloading", "", _t("OK") + ", " + ngettext("Downloading from {0} peer", "Downloading from {0} peers", curPeers)) + "</td>\n" +
                                     "<td class=\"peerCount\"><b><a href=\"" +
                                     uri + "\" title=\"" + _t("Hide Peers") + "\"><span class=\"right\">" + curPeers + "</span>" + thinsp(noThinsp) +
                                     "<span class=\"left\">" + knownPeers + "</span></a>";
                snarkStatus = "active downloading incomplete";
            } else if (isRunning && curPeers > 0 && !showPeers) {
                statusString = toSVGWithDataTooltip("stalled", "", _t("Stalled") + " (" + ngettext("Connected to {0} peer", "Connected to {0} peers", curPeers) + ")") + "</td>\n" +
                                     "<td class=\"peerCount\"><b><a href=\"" +
                                     uri + getQueryString(req, b64, null, null) + "\"><span class=\"right\">" +
                                     curPeers + "</span>" + thinsp(noThinsp) + "<span class=\"left\">" + knownPeers + "</span></a>";
                snarkStatus = "inactive downloading incomplete";
            } else if (isRunning && curPeers > 0) {
                statusString = toSVGWithDataTooltip("stalled", "", _t("Stalled") +
                                     " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")") + "</td>\n" +
                                     "<td class=\"peerCount\"><b><a href=\"" + uri + "\" title=\"" + _t("Hide Peers") +
                                     "\"><span class=\"right\">" + curPeers + "</span>" + thinsp(noThinsp) + "<span class=\"left\">" + knownPeers + "</span></a>";
                snarkStatus = "inactive downloading incomplete";
            } else if (isRunning && knownPeers > 0) {
                statusString = toSVGWithDataTooltip("nopeers", "", _t("No Peers") +
                                     " (" + _t("Connected to {0} of {1} peers in swarm", curPeers, knownPeers) + ")") + "</td>\n" +
                                     "<td class=\"peerCount\"><b><span class=\"right\">0</span>" +
                                     thinsp(noThinsp) + "<span class=\"left\">" + knownPeers + "</span>";
                snarkStatus = "inactive downloading incomplete nopeers";
            } else if (isRunning) {
                statusString = toSVGWithDataTooltip("nopeers", "", _t("No Peers")) + "</td>\n" +
                                     "<td class=\"peerCount\"><b>‒";
                snarkStatus = "inactive downloading incomplete nopeers zero";
            } else {
                statusString = toSVGWithDataTooltip("stopped", "", _t("Stopped")) + "</td>\n" +
                                     "<td class=\"peerCount\"><b>‒";
                snarkStatus = "inactive incomplete stopped zero";
            }
        }

        String rowStatus = (rowClass + ' ' + snarkStatus);
        out.write("<tr class=\"" + rowStatus + " volatile\">\n" +
                  "<td class=\"graphicStatus\" align=\"center\">");
        out.write(statusString);

        // (i) icon column
        out.write("</b></td>\n<td class=\"trackerDetails\">");
        if (isValid) {
            String announce = meta.getAnnounce();
            if (announce == null)
                announce = snark.getTrackerURL();
            if (announce != null) {
                // Link to tracker details page
                String trackerLink = getTrackerLink(announce, snark.getInfoHash());
                if (trackerLink != null)
                    out.write(trackerLink);
            }
        }

        String encodedBaseName = encodePath(fullBasename);
        // File type icon column
        out.write("</td>\n<td class=\"torrentDetails\">");
        if (isValid) {
            StringBuilder buf = new StringBuilder(128);
            CommentSet comments = snark.getComments();
            // Link to local details page - note that trailing slash on a single-file torrent
            // gets us to the details page instead of the file.
            buf.append("<span class=\"snarkFiletype\"><a class=\"linkbutton\" href=\"").append(encodedBaseName)
               .append("/\" title=\"").append(_t("Torrent details")).append("\">");
            if (comments != null && !comments.isEmpty()) {
                buf.append("<span class=\"snarkCommented\" title=\"").append(_t("Torrent has comments")).append("\">");
                toSVG(buf, "rateme", "", "");
                buf.append("</span>");
            }
            out.write(buf.toString());
        }
        String icon;
        if (isMultiFile)
            icon = "folder";
        else if (isValid)
            icon = toIcon(meta.getName());
        else if (snark instanceof FetchAndAdd)
            icon = "download";
        else
            icon = "magnet";
        if (isValid) {
            out.write(toSVG(icon));
            out.write("</a></span>");
        } else {
            out.write(toSVG(icon));
        }

        // Torrent name column
        out.write("</td>\n<td class=\"torrentName\">");
        if (remaining == 0 || isMultiFile) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("<a href=\"").append(encodedBaseName);
            if (isMultiFile)
                buf.append('/');
            buf.append("\" title=\"");
            if (isMultiFile)
                buf.append(_t("View files"));
            else
                buf.append(_t("Open file"));
            buf.append("\">");
            out.write(buf.toString());
        }
        if (basename.contains("Magnet")) {
            out.write(DataHelper.escapeHTML(basename)
               .replace("Magnet ", "<span class=\"infohash\">")
               .replaceFirst("\\(", "</span> <span class=\"magnetLabel\">").replaceAll("\\)$", ""));
            out.write("</span>");
        } else {
            out.write(DataHelper.escapeHTML(basename));
        }
        if (remaining == 0 || isMultiFile)
            out.write("</a>");

//        if (basename.contains("Magnet"))
//            out.write("</span>");
        out.write("</td>\n<td align=\"right\" class=\"torrentETA\">");
        if (isRunning && remainingSeconds > 0 && !snark.isChecking())
            out.write(DataHelper.formatDuration2(Math.max(remainingSeconds, 10) * 1000)); // (eta 6h)
        out.write("</td>\n");
        out.write("<td align=\"right\" class=\"torrentDownloaded\">");
        if (remaining > 0) {
            long percent = 100 * (total - remaining) / total;
            out.write("<div class=\"percentBarOuter\">");
            out.write("<div class=\"percentBarInner\" style=\"width: " + percent + "%;\">");
            out.write("<div class=\"percentBarText\" tabindex=\"0\" title=\"");
            out.write(percent + "% " + _t("complete") + "; " + formatSize(remaining) + ' ' + _t("remaining"));
            out.write("\">");
            out.write(formatSize(total-remaining).replaceAll("iB","") + thinsp(noThinsp) + formatSize(total).replaceAll("iB",""));
            out.write("</div></div></div>");
        } else if (remaining == 0) {
            // needs locale configured for automatic translation
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMM yyyy");
            fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
            long[] dates = _manager.getSavedAddedAndCompleted(snark);
            String date = fmt.format(new Date(dates[1]));
            out.write("<div class=\"percentBarComplete\" title=\"");
            out.write(_t("Completed") + ": " + date + "\">");
            out.write(formatSize(total).replaceAll("iB","")); // 3GB
            out.write("</div>");
            }
        out.write("</td>\n");
        out.write("<td align=\"right\" class=\"RateDown");
        if (downBps >= 100000)
            out.write(" hundred");
        else if (downBps >= 10000)
            out.write(" ten");
        out.write("\">");
        // we may only be uploading to peers, so hide when downrate <= 0
        if (isRunning && needed > 0 && downBps > 0 && curPeers > 0) {
            out.write("<span class=\"right\">");
            out.write(formatSize(downBps).replaceAll("iB", "")
                                         .replace("B", "</span><span class=\"left\">B")
                                         .replace("K", "</span><span class=\"left\">K")
                                         .replace("M", "</span><span class=\"left\">M")
                                         .replace("G", "</span><span class=\"left\">G")
                                         + "/s</span>");
        }
        out.write("</td>\n");
        out.write("<td align=\"right\" class=\"torrentUploaded\">");
        if (isValid) {
            double ratio = uploaded / ((double) total);
            if (total <= 0)
                ratio = 0;
            String txPercent = (new DecimalFormat("0")).format(ratio * 100);
            String txPercentBar = txPercent + "%";
            if (ratio > 1)
                txPercentBar = "100%";
            if (ratio <= 0.01 && ratio > 0)
                txPercent = (new DecimalFormat("0.00")).format(ratio * 100);
            if (showRatios) {
                if (total > 0) {
                    out.write("<span class=\"uploaded\"><span class=\"txBarText\">");
                    out.write(txPercent);
                    out.write("&#8239;%");
                    out.write("</span><span class=\"txBarInner\" style=\"width: calc(" + txPercentBar + " - 2px)\"></span>");
                    out.write("</span>");
                } else {
                    out.write("‒");
                }
            } else if (uploaded > 0) {
                out.write("<span class=\"uploaded\" title=\"");
                out.write(_t("Upload ratio").replace("Upload", "Share"));
                out.write(": ");
                out.write(txPercent);
                out.write("&#8239;%");
                SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMM yyyy");
                fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                Storage storage = snark.getStorage();
                long lastActive = storage.getActivity();
                String date = fmt.format(new Date(lastActive));
                if (storage != null) {
                    out.write(" &bullet; ");
                    out.write(_t("Last activity"));
                    out.write(": ");
                    out.write(date);
                }
                out.write("\"><span class=\"txBarText\"><span class=\"right\">");
                out.write(formatSize(uploaded).replaceAll("iB","")
                                              .replace("B", "</span><span class=\"left\">B</span>")
                                              .replace("K", "</span><span class=\"left\">K</span>")
                                              .replace("M", "</span><span class=\"left\">M</span>")
                                              .replace("G", "</span><span class=\"left\">G</span>")
                                              .replace("T", "</span><span class=\"left\">T</span>"));
                out.write("</span><span class=\"txBarInner\" style=\"width: calc(" + txPercentBar + " - 2px)\"></span></span>");
            }
        }
        out.write("</td>\n");
        out.write("<td align=\"right\" class=\"RateUp");
        if (upBps >= 100000)
            out.write(" hundred");
        else if (upBps >= 10000)
            out.write(" ten");
        out.write("\">");
        if (isRunning && isValid && upBps > 0 && curPeers > 0) {
            out.write("<span class=\"right\">");
            out.write(formatSize(upBps).replaceAll("iB","")
                                        .replace("B", "</span><span class=\"left\">B")
                                        .replace("K", "</span><span class=\"left\">K")
                                        .replace("M", "</span><span class=\"left\">M")
                                        .replace("G", "</span><span class=\"left\">G")
                                        + "/s</span>");
        }
        out.write("</td>\n");
        out.write("<td align=\"center\" class=\"torrentAction\">");
        if (snark.isChecking()) {
            // show no buttons
            out.write("<span class=\"isChecking\"></span>");
        } else if (isRunning) {
            // Stop Button
            out.write("<input type=\"submit\" class=\"actionStop\" name=\"action_Stop_" + b64 + "\" value=\"" +
                      _t("Stop") + "\" title=\"" + _t("Stop the torrent") + "\">");

        } else if (!snark.isStarting()) {
            if (!_manager.isStopping()) {
                // Start Button
                out.write("<input type=\"submit\" class=\"actionStart\" name=\"action_Start_" + b64 + "\" value=\"" +
                          _t("Start") + "\" title=\"" + _t("Start the torrent") + "\">");

            }
            if (isValid && canWrite) {
                // Remove Button
                out.write("<input type=\"submit\" class=\"actionRemove\" name=\"action_Remove_" + b64 + "\" value=\"" +
                          _t("Remove") + "\" title=\"" + _t("Remove the torrent from the active list, deleting the .torrent file") +
                          "\" client=\"" + escapeJSString(snark.getName()) + "\">");
            }

            // We can delete magnets without write privs
            if (!isValid || canWrite) {
                // Delete Button
                out.write("<input type=\"submit\" class=\"actionDelete\" name=\"action_Delete_" + b64 + "\" value=\"" +
                          _t("Delete") + "\" title=\"" + _t("Delete the .torrent file and the associated data files") +
                          "\" client=\"" + escapeJSString(snark.getName()) + "\">");
            }
        }
        out.write("</td>\n</tr>\n");

        if(showPeers && isRunning && curPeers > 0) {
            List<Peer> peers = snark.getPeerList();
            if (!showDebug)
                Collections.sort(peers, new PeerComparator());
            for (Peer peer : peers) {
                long t = peer.getInactiveTime();
                if ((peer.getUploadRate() > 0 || peer.getDownloadRate() > 0) && t < 60 * 1000) {
                    snarkStatus = "active";
                    if (peer.getUploadRate() > 0 && !peer.isInteresting() && !peer.isChoking())
                        snarkStatus += " TX";
                    if (peer.getDownloadRate() > 0 && !peer.isInterested() && !peer.isChoked())
                        snarkStatus += " RX";
                } else {
                    snarkStatus = "inactive";
                }
                if (!peer.isConnected())
                    continue;
                out.write("<tr class=\"peerinfo " + snarkStatus + " volatile\">\n<td class=\"graphicStatus\" title=\"");
                out.write(_t("Peer attached to swarm"));
                out.write("\"></td><td class=\"peerdata\" colspan=\"4\">");
                PeerID pid = peer.getPeerID();
                String ch = pid != null ? pid.toString() : "????";
                if (ch.startsWith("WebSeed@")) {
                    out.write(ch);
                } else {
                    ch = ch.substring(0, 4);
                    String client;
                    out.write("<span class=\"peerclient\"><tt title=\"");
                    out.write(_t("Destination (identity) of peer"));
                    out.write("\">" + peer.toString().substring(5, 9)+ "</tt>&nbsp;");
                    out.write("<span class=\"clientid\">");
                    if ("AwMD".equals(ch))
                        client = _t("I2PSnark");
                    else if ("LUFa".equals(ch))
                        client = "Vuze" + "<span class=\"clientVersion\"><i>" + getAzVersion(pid.getID()) + "</i></span>";
                    else if ("LUJJ".equals(ch))
                        client = "BiglyBT" + "<span class=\"clientVersion\"><i>" + getAzVersion(pid.getID()) + "</i></span>";
                    else if ("LVhE".equals(ch))
                        client = "XD" + "<span class=\"clientVersion\"><i>" + getAzVersion(pid.getID()) + "</i></span>";
                    else if ("ZV".equals(ch.substring(2,4)) || "VUZP".equals(ch))
                        client = "Robert" + "<span class=\"clientVersion\"><i>" + getRobtVersion(pid.getID()) + "</i></span>";
                    else if (ch.startsWith("LV")) // LVCS 1.0.2?; LVRS 1.0.4
                       client = "Transmission" + "<span class=\"clientVersion\"><i>" + getAzVersion(pid.getID()) + "</i></span>";
                    else if ("LUtU".equals(ch))
                        client = "KTorrent" + "<span class=\"clientVersion\"><i>" + getAzVersion(pid.getID()) + "</i></span>";
                    else if ("CwsL".equals(ch))
                        client = "I2PSnarkXL";
                    else if ("BFJT".equals(ch))
                        client = "I2PRufus";
                    else if ("TTMt".equals(ch))
                        client = "I2P-BT";
                    else
                        client = _t("Unknown") + " (" + ch + ')';
                    out.write(client + "</span></span>");
                }
                if (t >= 5000) {
                    if (showDebug) {
                        out.write(" &#10140; <i>" + _t("inactive") + "&nbsp;" + (t / 1000) + "s</i>");
                    } else {
                        out.write("<span class=\"inactivity\" style=\"width: " + (t / 2000) +
                                  "px;\" title=\"" + _t("Inactive") + ": " +
                                  (t / 1000) + ' ' + _t("seconds") + "\"></span>");
                    }
                }
                out.write("</td>\n");
                out.write("<td class=\"torrentETA\">");
                out.write("</td>\n");
                out.write("<td align=\"right\" class=\"torrentDownloaded\">");
                float pct;
                if (isValid) {
                    pct = (float) (100.0 * peer.completed() / meta.getPieces());
                    if (pct >= 100.0)
                        out.write("<span class=\"peerSeed\" title=\"" + _t("Seed") + "\">" + toSVG("peerseed", _t("Seed"), "") + "</span>");
                    else {
                        String ps = String.valueOf(pct);
                        if (ps.length() > 5)
                            ps = ps.substring(0, 5);
                        out.write("<div class=\"percentBarOuter\" title=\"" + ps + "%\">");
                        out.write("<div class=\"percentBarInner\" style=\"width:" + ps + "%;\">");
                        out.write("</div></div>");
                    }
                } else {
                    pct = (float) 101.0;
                    // until we get the metainfo we don't know how many pieces there are
                    //out.write("??");
                }
                out.write("</td>\n");
                out.write("<td align=\"right\" class=\"RateDown");
                if (peer.getDownloadRate() >= 100000)
                    out.write(" hundred");
                else if (peer.getDownloadRate() >= 10000)
                    out.write(" ten");
                out.write("\">");
                if (needed > 0) {
                    if (peer.isInteresting() && !peer.isChoked() && peer.getDownloadRate() > 0) {
                        out.write("<span class=\"unchoked\"><span class=\"right\">");
                        out.write(formatSize(peer.getDownloadRate()).replace("iB","")
                                                                     .replace("B", "</span><span class=\"left\">B")
                                                                     .replace("K", "</span><span class=\"left\">K")
                                                                     .replace("M", "</span><span class=\"left\">M")
                                                                     .replace("G", "</span><span class=\"left\">G")
                                                                     + "/s</span></span>");
                    } else if (peer.isInteresting() && !peer.isChoked()) {
                        out.write("<span class=\"unchoked idle\"></span>");
                    } else {
                        out.write("<span class=\"choked\" title=\"");
                        if (!peer.isInteresting()) {
                            out.write(_t("Uninteresting (The peer has no pieces we need)"));
                        } else {
                            out.write(_t("Choked (The peer is not allowing us to request pieces)"));
                        }
                        out.write("\"><span class=\"right\">");
                        out.write(formatSize(peer.getDownloadRate()).replace("iB","")
                                                                     .replace("B", "</span><span class=\"left\">B")
                                                                     .replace("K", "</span><span class=\"left\">K")
                                                                     .replace("M", "</span><span class=\"left\">M")
                                                                     .replace("G", "</span><span class=\"left\">G")
                                                                     + "/s</span></span>");
                    }
                } else if (!isValid) {
                    //if (peer supports metadata extension) {
                        out.write("<span class=\"unchoked\"><span class=\"right\">");
                        out.write(formatSize(peer.getDownloadRate()).replace("iB","")
                                                                     .replace("B", "</span><span class=\"left\">B")
                                                                     .replace("K", "</span><span class=\"left\">K")
                                                                     .replace("M", "</span><span class=\"left\">M")
                                                                     .replace("G", "</span><span class=\"left\">G")
                                                                     + "/s</span></span>");
                    //} else {
                    //}
                }
                out.write("</td>\n");
                out.write("<td class=\"torrentUploaded\">");
                out.write("</td>\n");
                out.write("<td align=\"right\" class=\"RateUp");
                if (peer.getUploadRate() >= 100000)
                    out.write(" hundred");
                else if (peer.getUploadRate() >= 10000)
                    out.write(" ten");
                out.write("\">");
                if (isValid && pct < 100.0) {
                    if (peer.isInterested() && !peer.isChoking() && peer.getUploadRate() > 0) {
                        out.write("<span class=\"unchoked\"><span class=\"right\">");
                        out.write(formatSize(peer.getUploadRate()).replace("iB","")
                                                                   .replace("B", "</span><span class=\"left\">B")
                                                                   .replace("K", "</span><span class=\"left\">K")
                                                                   .replace("M", "</span><span class=\"left\">M")
                                                                   .replace("G", "</span><span class=\"left\">G")
                                                                   + "/s</span></span>");
                    } else if (peer.isInterested() && !peer.isChoking()) {
                        out.write("<span class=\"unchoked idle\" title=\"");
                        out.write(_t("Peer is interested but currently idle"));
                        out.write("\">");
                    } else {
                        out.write("<span class=\"choked\" title=\"");
                        if (!peer.isInterested()) {
                            out.write(_t("Uninterested (We have no pieces the peer needs)"));
                        } else {
                            out.write(_t("Choking (We are not allowing the peer to request pieces)"));
                        }
                        out.write("\"><span class=\"right\">");
                        out.write(formatSize(peer.getUploadRate()).replace("iB","")
                                                                   .replace("B", "</span><span class=\"left\">B")
                                                                   .replace("K", "</span><span class=\"left\">K")
                                                                   .replace("M", "</span><span class=\"left\">M")
                                                                   .replace("G", "</span><span class=\"left\">G")
                                                                   + "/s</span></span>");
                    }
                }
                out.write("</td>\n");
                out.write("<td class=\"torrentAction\">");
                out.write("</td>\n</tr>\n");
                if (showDebug)
                    out.write("<tr class=\"debuginfo volatile " + rowClass + "\">\n<td class=\"graphicStatus\"></td>" +
                              "<td colspan=\"12\">" + peer.getSocket()
                              .replaceAll("Connection", "<b>Connection</b>").replaceAll(";", " &bullet;").replaceAll("\\* ", "")
                              .replaceAll("from", "<span class=\"from\">⇦</span>").replaceAll("to", "<span class=\"to\">⇨</span>") +
                              "</td>\n</tr>");
            }
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
        if (id[7] != '-')
            return "";
        StringBuilder buf = new StringBuilder(8);
        buf.append(' ');
        for (int i = 3; i <= 6; i++) {
            int val = id[i] - '0';
            if (val < 0)
                return "";
            if (val > 9)
                val = id[i] - 'A';
            if (i != 6 || val != 0) {
                if (i != 3)
                    buf.append('.');
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
            if (val < 0)
                return "";
            if (i != 3)
                buf.append('.');
            buf.append(val);
        }
        return buf.toString();
    }

    /** @since 0.8.2 */
    private static String thinsp(boolean disable) {
        if (disable)
            return " / ";
        return ("&thinsp;/&thinsp;");
//        return ("&#8239;/&#8239;");
    }

    /**
     *  Sort by completeness (seeds first), then by ID
     *  @since 0.8.1
     */
    private static class PeerComparator implements Comparator<Peer>, Serializable {

        public int compare(Peer l, Peer r) {
            int diff = r.completed() - l.completed();      // reverse
            if (diff != 0)
                return diff;
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
//                buf.append("<a href=\"").append(baseURL).append("details.php?dllist=1&amp;filelist=1&amp;info_hash=")
                buf.append("<a href=\"").append(baseURL).append("details.php?info_hash=")
                   .append(TrackerClient.urlencode(infohash))
                   .append("\" title=\"").append(_t("Details at {0} tracker", name)).append("\" target=\"_blank\">");
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
     *  Full anchor to home page or details page with shortened host name as anchor text
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
                buf.append("<a href=\"http://").append(urlEncode(host)).append("/\" target=\"blank\">");
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
        String newURL = req.getParameter("nofilter_newURL");
        if (newURL == null || newURL.trim().length() <= 0 || req.getMethod().equals("POST"))
            newURL = "";
        else
            newURL = DataHelper.stripHTML(newURL);    // XSS
        //String newFile = req.getParameter("newFile");
        //if ( (newFile == null) || (newFile.trim().length() <= 0) ) newFile = "";

        out.write("<div id=\"add\" class=\"snarkNewTorrent\">\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"_post\" method=\"POST\">\n");
        out.write("<div class=\"sectionPanel\" id=\"addSection\">\n");
        writeHiddenInputs(out, req, "Add");
        out.write("<input hidden class=\"toggle_input\" id=\"toggle_addtorrent\" type=\"checkbox\"");
        if (newURL.length() > 0)
            out.write(" checked=\"checked\">");  // force toggle open
        else
            out.write('>');
        out.write("<label id=\"tab_addtorrent\" class=\"toggleview\" for=\"toggle_addtorrent\"><span class=\"tab_label\">");
        out.write(_t("Add Torrent"));
        out.write("</span></label>");

        out.write("<hr>\n<table border=\"0\"><tr><td>");
        out.write(_t("From URL"));
        out.write(":<td><input type=\"text\" name=\"nofilter_newURL\" size=\"85\" value=\"" + newURL + "\" spellcheck=\"false\"");
        out.write(" title=\"");
        out.write(_t("Enter the torrent file download URL (I2P only), magnet link, or info hash"));
        out.write("\">\n");
        // not supporting from file at the moment, since the file name passed isn't always absolute (so it may not resolve)
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br>");
        out.write("<input type=\"submit\" class=\"add\" value=\"");
        out.write(_t("Add torrent"));
        out.write("\" name=\"foo\" ><br>\n" +
                  "<tr><td>");

        out.write(_t("Data dir"));
        out.write(":<td><input type=\"text\" name=\"nofilter_newDir\" size=\"85\" value=\"" + _manager.getDataDir().getAbsolutePath() + "\" spellcheck=\"false\"");
        out.write(" title=\"");
        out.write(_t("Enter the directory to save the data in (default {0})", _manager.getDataDir().getAbsolutePath()));
        out.write("\"></td></tr>\n");
        out.write("</table>\n");
        out.write("</div>\n</form>\n</div>\n");
    }

    private void writeSeedForm(PrintWriter out, HttpServletRequest req, List<Tracker> sortedTrackers) throws IOException {
        out.write("<div class=\"sectionPanel\" id=\"createSection\">\n<div>\n");
        // *not* enctype="multipart/form-data", so that the input type=file sends the filename, not the file
        out.write("<form action=\"_post\" method=\"POST\">\n");
        writeHiddenInputs(out, req, "Create");
        out.write("<input hidden class=\"toggle_input\" id=\"toggle_createtorrent\" type=\"checkbox\">" +
                  "<label id=\"tab_newtorrent\" class=\"toggleview\" for=\"toggle_createtorrent\"><span class=\"tab_label\">");
        out.write(_t("Create Torrent"));
        out.write("</span></label><hr>\n<table border=\"0\"><tr><td>");
        //out.write("From file: <input type=\"file\" name=\"newFile\" size=\"50\" value=\"" + newFile + "\" /><br>\n");
        out.write(_t("Data to seed"));
        out.write(":</td><td>"
                  + "<input type=\"text\" required name=\"nofilter_baseFile\" size=\"85\" value=\""
                  + "\" spellcheck=\"false\" title=\"");
        out.write(_t("File or directory to seed (full path or within the directory {0} )",
                    _manager.getDataDir().getAbsolutePath() + File.separatorChar));
        out.write("\" > <input type=\"submit\" class=\"create\" value=\"");
        out.write(_t("Create torrent"));
        out.write("\" name=\"foo\" >");
        out.write("</td></tr>\n");
        // TODO: Add support for adding comment to .torrent
        //out.write("<tr><td>\n");
        //out.write(_t("Comment"));
        //out.write(":</td><td>"
        //          + "<input type=\"text\" name=\"comment\" size=\"85\" maxlength=\"256\" value=\""
        //          + "\" title=\"");
        //out.write(_t("Add an optional comment or description to be embedded in the torrent file"));
        //out.write("\" >");
        //out.write("</td></tr>\n");
        out.write("<tr><td>\n");
        out.write(_t("Trackers"));
        out.write(":<td>\n<table id=\"trackerselect\">\n<tr>\n<td>Name</td><td align=\"center\">");
        out.write(_t("Primary"));
        out.write("</td><td align=\"center\">");
        out.write(_t("Alternates"));
        out.write("</td><td>");
        out.write(_t("Tracker Type"));
        out.write("</td>\n</tr>\n");

        for (Tracker t : sortedTrackers) {
            List<String> openTrackers = _manager.util().getOpenTrackers();
            List<String> privateTrackers = _manager.getPrivateTrackers();
            boolean isPrivate = privateTrackers.contains(t.announceURL);
            boolean isKnownOpen = _manager.util().isKnownOpenTracker(t.announceURL);
            boolean isOpen = isKnownOpen || openTrackers.contains(t.announceURL);
            String name = t.name;
            String announceURL = t.announceURL.replace("&#61;", "=");
            String homeURL = t.baseURL;
            out.write("<tr>\n<td><span class=\"trackerName\">");
            out.write("<a href=\"" + homeURL + "\" target=\"_blank\">" + name + "</a>");
            out.write("</span></td>\n<td align=\"center\"><input type=\"radio\" class=\"optbox\" name=\"announceURL\" value=\"");
            out.write(announceURL);
            out.write("\"");
            if (announceURL.equals(_lastAnnounceURL))
                out.write(" checked");
            out.write("></td>\n<td align=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"backup_");
            out.write(announceURL);
            out.write("\" value=\"foo\"></td>\n<td>");

            if (!(isOpen || isPrivate))
                out.write(_t("Standard"));
            if (isOpen)
                out.write(_t("Open"));
            if (isPrivate) {
                out.write(_t("Private"));
            }
            out.write("</td>\n</tr>\n");
        }
        out.write("<tr>\n<td><i>");
        out.write(_t("none"));
        out.write("</i></td>\n<td align=\"center\"><input type=\"radio\" class=\"optbox\" name=\"announceURL\" value=\"none\"");
        if (_lastAnnounceURL == null)
            out.write(" checked");
        out.write("></td>\n<td></td>\n<td></td>\n</tr>\n</table>\n");
        // make the user add a tracker on the config form now
        //out.write(_t("or"));
        //out.write("&nbsp;<input type=\"text\" name=\"announceURLOther\" size=\"57\" value=\"http://\" " +
        //          "title=\"");
        //out.write(_t("Specify custom tracker announce URL"));
        //out.write("\" > " +
        out.write("</td>\n</tr>\n" +
                  "</table>\n" +
                  "</form>\n</div>\n</div>\n");
    }

    private static final int[] times = { 5, 15, 30, 60, 2*60, 5*60, 10*60, 30*60, 60*60, -1 };

    private void writeConfigForm(PrintWriter out, HttpServletRequest req) throws IOException {
        String dataDir = _manager.getDataDir().getAbsolutePath();
        String lang = (Translate.getLanguage(_manager.util().getContext()));
        boolean filesPublic = _manager.areFilesPublic();
        boolean autoStart = _manager.shouldAutoStart();
        boolean smartSort = _manager.isSmartSortEnabled();
        boolean useOpenTrackers = _manager.util().shouldUseOpenTrackers();
        //String openTrackers = _manager.util().getOpenTrackerString();
        boolean useDHT = _manager.util().shouldUseDHT();
        boolean useRatings = _manager.util().ratingsEnabled();
        boolean useComments = _manager.util().commentsEnabled();
        boolean collapsePanels = _manager.util().collapsePanels();
        boolean showStatusFilter = _manager.util().showStatusFilter();
        boolean enableLightbox = _manager.util().enableLightbox();
        boolean enableAddCreate = _manager.util().enableAddCreate();
        //int seedPct = 0;

        boolean noCollapse = noCollapsePanels(req);

// configuration

        out.write("<form action=\"" + _contextPath + "/configure#top\" method=\"POST\">\n" +
                  "<div class=\"configPanel lang_" + lang + "\"><div class=\"snarkConfig\">\n");
        writeHiddenInputs(out, req, "Save");
        out.write("<span class=\"configTitle\">");
        out.write(_t("Configuration"));
        out.write("</span><hr>\n" +
                  "<table border=\"0\" id=\"configs\">\n");

// user interface

        out.write("<tr><th class=\"suboption\">");
        out.write(_t("User Interface"));
        if (_context.isRouterContext()) {
            out.write("&nbsp;&nbsp;<a href=\"/torrentmgr\" target=\"_top\" class=\"script\" id=\"embed\">");
            out.write(_t("Switch to Embedded Mode"));
            out.write("</a>");
            out.write("<a href=\"/i2psnark/configure\" target=\"_top\" class=\"script\" id=\"fullscreen\">");
            out.write(_t("Switch to Fullscreen Mode"));
            out.write("</a>");
        }
        out.write("</th></tr>\n<tr><td>\n<div class=\"optionlist\">\n");

        out.write("<span class=\"configOption\"><b>");
        out.write(_t("Theme"));
        out.write("</b> \n");
        if (_manager.getUniversalTheming()) {
            out.write("<select name='theme' disabled=\"disabled\" title=\"");
            out.write(_t("To change themes manually, disable universal theming"));
            out.write("\"><option>");
            out.write(_manager.getTheme());
            out.write("</option></select> <span id=\"bwHoverHelp\">");
            out.write(toThemeSVG("details", "", ""));
            out.write("<span id=\"bwHelp\">");
            out.write(_t("Universal theming is enabled."));
            out.write("</span></span>");
            out.write(" <a href=\"/configui\" target=\"_blank\">[");
            out.write(_t("Configure"));
            out.write("]</a></span><br>");
        } else {
            out.write("<select name='theme'>");
            String theme = _manager.getTheme();
            String[] themes = _manager.getThemes();
            Arrays.sort(themes);
            for (int i = 0; i < themes.length; i++) {
                if(themes[i].equals(theme))
                    out.write("\n<OPTION value=\"" + themes[i] + "\" SELECTED>" + themes[i]);
                else
                    out.write("\n<OPTION value=\"" + themes[i] + "\">" + themes[i]);
            }
            out.write("</select>\n</span><br>\n");
        }

        out.write("<span class=\"configOption\"><b>");
        out.write(_t("Refresh time"));
        out.write("</b> \n<select name=\"refreshDelay\" title=\"");
        out.write(_t("How frequently torrent status is updated on the main page"));
        out.write("\">");
        int delay = _manager.getRefreshDelaySeconds();
        for (int i = 0; i < times.length; i++) {
            out.write("<option value=\"");
            out.write(Integer.toString(times[i]));
            out.write("\"");
            if (times[i] == delay)
                out.write(" selected=\"selected\"");
            out.write(">");
            if (times[i] > 0)
                out.write(DataHelper.formatDuration2(times[i] * 1000));
            else
                out.write(_t("Never"));
            out.write("</option>\n");
        }
        out.write("</select>\n</span><br>\n");

        out.write("<span class=\"configOption\"><label><b>");
        out.write(_t("Page size"));
        out.write("</b> <input type=\"text\" name=\"pageSize\" size=\"5\" maxlength=\"4\" pattern=\"[0-9]{0,4}\" class=\"r numeric\""
                  + " title=\"");
        out.write(_t("Maximum number of torrents to display per page"));
        out.write("\" value=\"" + _manager.getPageSize() + "\" > ");
        out.write(_t("torrents"));
        out.write("</label></span><br>\n");

        if (!_context.isRouterContext()) {
            try {
                // class only in standalone builds
                Class<?> helper = Class.forName("org.klomp.snark.standalone.ConfigUIHelper");
                Method getLangSettings = helper.getMethod("getLangSettings", I2PAppContext.class);
                String langSettings = (String) getLangSettings.invoke(null, _context);
                // If we get to here, we have the language settings
                out.write("<span class=\"configOption\"><b>");
                out.write(_t("Language"));
                out.write("</b> ");
                out.write(langSettings);
                out.write("</span><br>\n");
            } catch (ClassNotFoundException e) {
            } catch (NoSuchMethodException e) {
            } catch (IllegalAccessException e) {
            } catch (InvocationTargetException e) {
            }
        } else {
            out.write("<span class=\"configOption\"><b>");
            out.write(_t("Language"));
            out.write("</b> <span id=\"snarkLang\">");
            out.write(lang);
            out.write("</span> <a href=\"/configui#langheading\" target=\"_blank\">");
            out.write("[");
            out.write(_t("Configure"));
            out.write("]</a></span><br>\n");
        }

        out.write("<span class=\"configOption\"><label for=\"smartSort\"><b>");
        out.write(_t("Smart torrent sorting"));
        out.write("</b> </label><input type=\"checkbox\" class=\"optbox slider\" name=\"smartSort\" id=\"smartSort\" value=\"true\" "
                  + (smartSort ? "checked " : "")
                  + "title=\"");
        out.write(_t("Ignore words such as 'a' and 'the' when sorting"));
        out.write("\" ></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"collapsePanels\"><b>");
        out.write(_t("Collapsible panels"));
        out.write("</b> </label><input type=\"checkbox\" class=\"optbox slider\" name=\"collapsePanels\" id=\"collapsePanels\" value=\"true\" "
                  + (collapsePanels ? "checked " : "")
                  + "title=\"");
        if (noCollapse) {
        String ua = req.getHeader("user-agent");
            out.write(_t("Your browser does not support this feature."));
            out.write("[" + ua + "]");
            out.write("\" disabled=\"disabled");
        } else {
            out.write(_t("Allow the 'Add Torrent' and 'Create Torrent' panels to be collapsed, and collapse by default in non-embedded mode"));
        }
        out.write("\"></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"showStatusFilter\"><b>");
        out.write(_t("Torrent filter bar"));
        out.write("</b> </label><input type=\"checkbox\" class=\"optbox slider\" name=\"showStatusFilter\" id=\"showStatusFilter\" value=\"true\" "
                  + (showStatusFilter ? "checked " : "")
                  + "title=\"");
        out.write(_t("Show filter bar above torrents for selective display based on status"));
        out.write(" (" + _t("requires javascript") + ")");
        out.write("\"></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"enableLightbox\"><b>");
        out.write(_t("Enable lightbox"));
        out.write("</b> </label><input type=\"checkbox\" class=\"optbox slider\" name=\"enableLightbox\" id=\"enableLightbox\" value=\"true\" "
                  + (enableLightbox ? "checked " : "")
                  + "title=\"");
        out.write(_t("Use a lightbox to display images when thumbnails are clicked"));
        out.write(" (" + _t("requires javascript") + ")");
        out.write("\"></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"enableAddCreate\"><b>");
        out.write(_t("Persist Add/Create"));
        out.write("</b> </label><input type=\"checkbox\" class=\"optbox slider\" name=\"enableAddCreate\" id=\"enableAddCreate\" value=\"true\" "
                  + (enableAddCreate ? "checked " : "")
                  + "title=\"");
        out.write(_t("Display the 'Add' and 'Create' sections on all torrent listing pages when in multipage mode"));
        out.write("\"></span><br>\n");

        out.write("</div>\n</td></tr>\n");

// comments/ratings

        out.write("<tr><th class=\"suboption\">");
        out.write(_t("Comments &amp; Ratings"));
        out.write("</th></tr>\n<tr><td>\n<div class=\"optionlist\">\n");

        out.write("<span class=\"configOption\"><label for=\"ratings\"><b>");
        out.write(_t("Enable Ratings"));
        out.write("</b></label> <input type=\"checkbox\" class=\"optbox slider\" name=\"ratings\" id=\"ratings\" value=\"true\" "
                  + (useRatings ? "checked " : "")
                  + "title=\"");
        out.write(_t("Show ratings on torrent pages"));
        out.write("\" ></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"comments\"><b>");
        out.write(_t("Enable Comments"));
        out.write("</b></label> <input type=\"checkbox\" class=\"optbox slider\" name=\"comments\" id=\"comments\" value=\"true\" "
                  + (useComments ? "checked " : "")
                  + "title=\"");
        out.write(_t("Show comments on torrent pages"));
        out.write("\" ></span><br>\n");

        out.write("<span class=\"configOption\" id=\"configureAuthor\"><label><b>");
        out.write(_t("Comment Author"));
        out.write("</b> <input type=\"text\" name=\"nofilter_commentsName\" spellcheck=\"false\" value=\""
                  + DataHelper.escapeHTML(_manager.util().getCommentsName()) + "\" size=\"15\" maxlength=\"16\" title=\"");
        out.write(_t("Set the author name for your comments and ratings"));
        out.write("\" ></label></span>\n");

        out.write("</div>\n</td></tr>\n");

// torrent options

        out.write("<tr><th class=\"suboption\">");
        out.write(_t("Torrent Options"));
        out.write("</th></tr>\n<tr><td>\n<div class=\"optionlist\">\n");

        out.write("<span class=\"configOption\"><label><b>");
        out.write(_t("Up bandwidth limit"));
        out.write("</b> <input type=\"text\" name=\"upBW\" class=\"r numeric\" value=\""
                  + _manager.util().getMaxUpBW() + "\" size=\"5\" maxlength=\"5\" pattern=\"[0-9]{1,5}\""
                  + " title=\"");
        out.write(_t("Maximum bandwidth allocated for uploading"));
        out.write("\"> KBps</label> <span id=\"bwHoverHelp\">");
        out.write(toThemeSVG("details", "", ""));
        out.write("<span id=\"bwHelp\"><i>");
        out.write(_t("Half available bandwidth recommended."));
        out.write("</i></span></span>");
        if (_context.isRouterContext()) {
            out.write(" <a href=\"/config.jsp\" target=\"blank\" title=\"");
            out.write(_t("View or change router bandwidth"));
            out.write("\">[");
            out.write(_t("Configure"));
            out.write("]</a>");
        }
        out.write("</span><br>\n");

        out.write("<span class=\"configOption\"><label><b>");
        out.write(_t("Total uploader limit"));
        out.write("</b> <input type=\"text\" name=\"upLimit\" class=\"r numeric\" value=\""
                  + _manager.util().getMaxUploaders() + "\" size=\"5\" maxlength=\"3\" pattern=\"[0-9]{1,3}\""
                  + " title=\"");
        out.write(_t("Maximum number of peers to upload to"));
        out.write("\" > ");
        out.write(_t("peers"));
        out.write("</label></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"useOpenTrackers\"><b>");
        out.write(_t("Use open trackers also").replace(" also", ""));
        out.write("</b></label> <input type=\"checkbox\" class=\"optbox slider\" name=\"useOpenTrackers\" id=\"useOpenTrackers\" value=\"true\" "
                  + (useOpenTrackers ? "checked " : "")
                  + "title=\"");
        out.write(_t("Announce torrents to open trackers as well as trackers listed in the torrent file"));
        out.write("\" ></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"useDHT\"><b>");
        out.write(_t("Enable DHT"));
        out.write("</b></label> <input type=\"checkbox\" class=\"optbox slider\" name=\"useDHT\" id=\"useDHT\" value=\"true\" "
                  + (useDHT ? "checked " : "")
                  + "title=\"");
        out.write(_t("Use DHT to find additional peers"));
        out.write("\" ></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"autoStart\"><b>");
        out.write(_t("Auto start torrents"));
        out.write("</b> </label><input type=\"checkbox\" class=\"optbox slider\" name=\"autoStart\" id=\"autoStart\" value=\"true\" "
                  + (autoStart ? "checked " : "")
                  + "title=\"");
        out.write(_t("Automatically start torrents when added and restart torrents when I2PSnark starts"));
        out.write("\" ></span>");

        if (_context.isRouterContext()) {
            out.write("<br>\n<span class=\"configOption\"><label><b>");
            out.write(_t("Startup delay"));
            out.write("</b> <input type=\"text\" name=\"startupDelay\" size=\"5\" maxlength=\"4\" pattern=\"[0-9]{1,4}\" class=\"r numeric\""
                      + " title=\"");
            out.write(_t("How long before auto-started torrents are loaded when I2PSnark starts"));
            out.write("\" value=\"" + _manager.util().getStartupDelay() + "\" > ");
            out.write(_t("minutes"));
            out.write("</label></span>");
        }
        out.write("\n</div>\n</td></tr>\n");

        //Auto add: <input type="checkbox" name="autoAdd" value="true" title="If true, automatically add torrents that are found in the data directory" />
        //Auto stop: <input type="checkbox" name="autoStop" value="true" title="If true, automatically stop torrents that are removed from the data directory" />
        //out.write("<br>\n");
/*
        out.write("Seed percentage: <select name=\"seedPct\" disabled=\"true\" >\n");
        if (seedPct <= 0)
            out.write("<option value=\"0\" selected=\"selected\">Unlimited</option>\n");
        else
            out.write("<option value=\"0\">Unlimited</option>\n");
        if (seedPct == 100)
            out.write("<option value=\"100\" selected=\"selected\">100%</option>\n");
        else
            out.write("<option value=\"100\">100%</option>\n");
        if (seedPct == 150)
            out.write("<option value=\"150\" selected=\"selected\">150%</option>\n");
        else
            out.write("<option value=\"150\">150%</option>\n");
        out.write("</select><br>\n");
*/

        //          "<tr><td>");
        //out.write(_t("Open tracker announce URLs"));
        //out.write(": <td><input type=\"text\" name=\"openTrackers\" value=\""
        //          + openTrackers + "\" size=\"50\" ><br>\n");

        //out.write("\n");
        //out.write("EepProxy host: <input type=\"text\" name=\"eepHost\" value=\""
        //          + _manager.util().getEepProxyHost() + "\" size=\"15\" /> ");
        //out.write("port: <input type=\"text\" name=\"eepPort\" value=\""
        //          + _manager.util().getEepProxyPort() + "\" size=\"5\" maxlength=\"5\" /><br>\n");

// data storage

        out.write("<tr><th class=\"suboption\">");
        out.write(_t("Data Storage"));
        out.write("</th></tr><tr><td>\n<div class=\"optionlist\">\n");

        out.write("<span class=\"configOption\"><label><b>");
        out.write(_t("Data directory"));
        out.write("</b> <input type=\"text\" name=\"nofilter_dataDir\" size=\"60\"" + " title=\"");
        out.write(_t("Directory where torrents and downloaded/shared files are stored"));
        out.write("\" value=\"" + DataHelper.escapeHTML(dataDir) + "\" spellcheck=\"false\"></label></span><br>\n");

        out.write("<span class=\"configOption\"><label for=\"filesPublic\"><b>");
        out.write(_t("Files readable by all"));
        out.write("</b> </label><input type=\"checkbox\" class=\"optbox slider\" name=\"filesPublic\" id=\"filesPublic\" value=\"true\" " +
                  (filesPublic ? "checked " : "") + "title=\"");
        out.write(_t("Set file permissions to allow other local users to access the downloaded files"));
        out.write("\" ></span>\n");

        out.write("<span class=\"configOption\"><label for=\"maxFiles\"><b>");
        out.write(_t("Max files per torrent"));
        out.write("</b> <input type=\"text\" name=\"maxFiles\" size=\"5\" maxlength=\"5\" pattern=\"[0-9]{1,5}\" class=\"r numeric\"" + " title=\"");
        out.write(_t("Maximum number of files permitted per torrent - note that trackers may set their own limits, and your OS may limit the number of open files, preventing torrents with many files (and subsequent torrents) from loading"));
        out.write("\" value=\"" + _manager.getMaxFilesPerTorrent() + "\" spellcheck=\"false\" disabled></label></span><br>\n");
        out.write("</div></td></tr>\n");

// i2cp/tunnel configuration

        out.write("<tr><th class=\"suboption\">");
        out.write(_t("Tunnel Configuration"));
        out.write("</th></tr>\n<tr><td>\n<div class=\"optionlist\">\n");

        Map<String, String> options = new TreeMap<String, String>(_manager.util().getI2CPOptions());

        out.write("<span class=\"configOption\"><b>");
        out.write(_t("Inbound Settings"));
        out.write("</b> \n");
        out.write(renderOptions(1, 16, SnarkManager.DEFAULT_TUNNEL_QUANTITY, options.remove("inbound.quantity"), "inbound.quantity", TUNNEL));
        out.write("&nbsp;");
        out.write(renderOptions(0, 6, 3, options.remove("inbound.length"), "inbound.length", HOP));
        out.write("</span><br>\n");

        out.write("<span class=\"configOption\"><b>");
        out.write(_t("Outbound Settings"));
        out.write("</b> \n");
        out.write(renderOptions(1, 16, SnarkManager.DEFAULT_TUNNEL_QUANTITY, options.remove("outbound.quantity"), "outbound.quantity", TUNNEL));
        out.write("&nbsp;");
        out.write(renderOptions(0, 6, 3, options.remove("outbound.length"), "outbound.length", HOP));
        out.write("</span><br>\n");

        if (!_context.isRouterContext()) {
            out.write("<span class=\"configOption\"><label><b>");
            out.write(_t("I2CP host"));
            out.write("</b> <input type=\"text\" name=\"i2cpHost\" value=\""
                      + _manager.util().getI2CPHost() + "\" size=\"5\" ></label></span><br>\n");

            out.write("<span class=\"configOption\"><label><b>");
            out.write(_t("I2CP port"));
            out.write("</b> <input type=\"text\" name=\"i2cpPort\" value=\"" +
                      + _manager.util().getI2CPPort() + "\" class=\"numeric\" size=\"5\" maxlength=\"5\" pattern=\"[0-9]{1,5}\" ></label></span><br>\n");
        }

        options.remove(I2PSnarkUtil.PROP_MAX_BW);
        // was accidentally in the I2CP options prior to 0.8.9 so it will be in old config files
        options.remove(SnarkManager.PROP_OPENTRACKERS);
        StringBuilder opts = new StringBuilder(64);
        for (Map.Entry<String, String> e : options.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            opts.append(key).append('=').append(val).append(' ');
        }
        out.write("<span class=\"configOption\"><label><b>");
        out.write(_t("I2CP options"));
        out.write("</b> <input type=\"text\" name=\"i2cpOpts\" value=\""
                  + opts.toString() + "\" size=\"60\" ></label></span>\n");

        out.write("</div>\n</td></tr>\n");

        out.write("<tr class=\"spacer\"><td></td></tr>\n");  // spacer

// save config

        out.write("<tr><td><input type=\"submit\" class=\"accept\" value=\"");
        out.write(_t("Save configuration"));
        out.write("\" name=\"foo\" ></td></tr>\n" +
                  "<tr class=\"spacer\"><td>&nbsp;</td></tr>\n" +  // spacer
                  "</table></div></div></form>");
    }

    /** @since 0.9 */
    private void writeTrackerForm(PrintWriter out, HttpServletRequest req) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<form action=\"" + _contextPath + "/configure#top\" method=\"POST\">\n" +
                   "<div class=\"configPanel\" id=\"trackers\"><div class=\"snarkConfig\">\n");
        writeHiddenInputs(buf, req, "Save2");
        buf.append("<span class=\"configTitle\">");
//        toThemeImg(buf, "config");
//        buf.append(' ');
        buf.append(_t("Trackers"));
        buf.append("</span><hr>\n"   +
                   "<table class=\"trackerconfig\"><tr><th title=\"")
            .append(_t("Select trackers for removal from I2PSnark's known list"))
           //.append(_t("Remove"))
           .append("\"></th><th>")
           .append(_t("Name"))
           .append("</th><th>")
           .append(_t("Website URL"))
           .append("</th><th>")
           .append(_t("Standard"))
           .append("</th><th>")
           .append(_t("Open"))
           .append("</th><th>")
           .append(_t("Private"))
           .append("</th><th>")
           .append(_t("Announce URL"))
           .append("</th></tr>\n");
//           .append("<tr class=\"spacer top\"><td colspan=\"7\">&nbsp;</td></tr>\n"); // spacer
        List<String> openTrackers = _manager.util().getOpenTrackers();
        List<String> privateTrackers = _manager.getPrivateTrackers();
        for (Tracker t : _manager.getSortedTrackers()) {
            String name = t.name;
            String homeURL = t.baseURL;
            String announceURL = t.announceURL.replace("&#61;", "=");
            boolean isPrivate = privateTrackers.contains(t.announceURL);
            boolean isKnownOpen = _manager.util().isKnownOpenTracker(t.announceURL);
            boolean isOpen = isKnownOpen || openTrackers.contains(t.announceURL);
            buf.append("<tr class=\"knownTracker\"><td><input type=\"checkbox\" class=\"optbox\" id=\"").append(name).append("\" name=\"delete_")
               .append(name).append("\" title=\"").append(_t("Mark tracker for deletion")).append("\">" +
                       "</td><td><label for=\"").append(name).append("\">").append(name).append("</label></td><td>");
               if (homeURL.endsWith(".i2p/"))
                   homeURL = homeURL.substring(0, homeURL.length() - 1);
            buf.append(urlify(homeURL, 64)).append("</td><td><input type=\"radio\" class=\"optbox\" value=\"0\" tabindex=\"-1\" name=\"ttype_")
               .append(announceURL).append("\"");
            if (!(isOpen || isPrivate))
                buf.append(" checked=\"checked\"");
            else if (isKnownOpen)
                buf.append(" disabled=\"disabled\"");
            buf.append(">" +
                       "</td><td><input type=\"radio\" class=\"optbox\" value=\"1\" tabindex=\"-1\" name=\"ttype_")
               .append(announceURL).append("\"");
            if (isOpen)
                buf.append(" checked=\"checked\"");
            else if (t.announceURL.equals("http://diftracker.i2p/announce.php") ||
                     t.announceURL.equals("http://tracker2.postman.i2p/announce.php") ||
                     t.announceURL.equals("http://torrfreedom.i2p/announce.php"))
                buf.append(" disabled=\"disabled\"");
            buf.append(">" +
                       "</td><td><input type=\"radio\" class=\"optbox\" value=\"2\" tabindex=\"-1\" name=\"ttype_")
               .append(announceURL).append("\"");
            if (isPrivate) {
                buf.append(" checked=\"checked\"");
            } else if (isKnownOpen ||
                       t.announceURL.equals("http://diftracker.i2p/announce.php") ||
                       t.announceURL.equals("http://tracker2.postman.i2p/announce.php") ||
                       t.announceURL.equals("http://torrfreedom.i2p/announce.php")) {
                buf.append(" disabled=\"disabled\"");
            }
            buf.append(">" +
                       "</td><td>").append(urlify(announceURL, 64))
               .append("</td></tr>\n");
        }
        buf.append("<tr class=\"spacer\"><td colspan=\"7\">&nbsp;</td></tr>\n")  // spacer
           .append("<tr id=\"addtracker\"><td><b>")
           .append(_t("Add")).append(":</b></td>" +
                   "<td><input type=\"text\" class=\"trackername\" name=\"tname\" spellcheck=\"false\"></td>" +
                   "<td><input type=\"text\" class=\"trackerhome\" name=\"thurl\" spellcheck=\"false\"></td>" +
                   "<td><input type=\"radio\" class=\"optbox\" value=\"0\" name=\"add_tracker_type\" checked=\"checked\"></td>" +
                   "<td><input type=\"radio\" class=\"optbox\" value=\"1\" name=\"add_tracker_type\"></td>" +
                   "<td><input type=\"radio\" class=\"optbox\" value=\"2\" name=\"add_tracker_type\"></td>" +
                   "<td><input type=\"text\" class=\"trackerannounce\" name=\"taurl\" spellcheck=\"false\"></td></tr>\n" +
                   "<tr class=\"spacer\"><td colspan=\"7\">&nbsp;</td></tr>\n" +  // spacer
                   "<tr><td colspan=\"7\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"default\" value=\"").append(_t("Add tracker")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"delete\" value=\"").append(_t("Delete selected")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"add\" value=\"").append(_t("Add tracker")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"accept\" value=\"").append(_t("Save tracker configuration")).append("\">\n" +
                   // "<input type=\"reset\" class=\"cancel\" value=\"").append(_t("Cancel")).append("\">\n" +
                   "<input type=\"submit\" name=\"taction\" class=\"reload\" value=\"").append(_t("Restore defaults")).append("\">\n" +
                   "</td></tr>" +
                   "<tr class=\"spacer\"><td colspan=\"7\">&nbsp;</td></tr>\n" +  // spacer
                   "</table>\n</div>\n</div></form>\n");
        out.write(buf.toString());
    }

    private void writeConfigLink(PrintWriter out) throws IOException {
        out.write("\n<div id=\"configSection\">\n<span class=\"snarkConfig\">" +
                  "<span id=\"tab_config\" class=\"configTitle\"><a href=\"configure\"><span class=\"tab_label\">");
//        out.write(toThemeImg("config"));
//        out.write(' ');
        out.write(_t("Configuration"));
        out.write("</span></a></span></span>\n</div>\n");
    }

    /**
     *  @param url in base32 or hex
     *  @param dataDir null to default to snark data directory
     *  @since 0.8.4
     */
    private void addMagnet(String url, File dataDir) {
        if (url.startsWith(MagnetURI.MAGNET_FULL_V2)) {
            _manager.addMessage("Cannot add magnet: version 2 magnet links are not supported");
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
        if (selName.contains("quantity"))
            buf.append("\" title=\"").append(_t("This configures the maximum number of tunnels to open, determined by the number of connected peers (actual usage may be less)"));
        if (selName.contains("length"))
            buf.append("\" title=\"").append(_t("Changing this setting to less than 3 hops may improve speed at the expense of anonymity and is not recommended"));
        buf.append("\">\n");
        for (int i = min; i <= max; i++) {
            buf.append("<option value=\"").append(i).append("\" ");
            if (i == now)
                buf.append("selected=\"selected\" ");
            // constants to prevent tagging
            buf.append(">").append(ngettext(DUMMY1 + name, DUMMY0 + name + 's', i));
            buf.append("</option>\n");
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
        buf.append("<a href=\"").append(link).append("\" target=\"_blank\">").append(display).append("</a>");
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
    private static final String HEADER_B = "snark.css?" + CoreVersion.VERSION + "\" rel=\"stylesheet\" type=\"text/css\" >";
    private static final String HEADER_C = "nocollapse.css?" + CoreVersion.VERSION + "\" rel=\"stylesheet\" type=\"text/css\" >";
    private static final String HEADER_D = "snark_big.css?" + CoreVersion.VERSION + "\" rel=\"stylesheet\" type=\"text/css\" >";
    private static final String HEADER_I = "images/images.css?" + CoreVersion.VERSION + "\" rel=\"stylesheet\" type=\"text/css\" >";
    private static final String HEADER_Z = "override.css\" rel=\"stylesheet\" type=\"text/css\" >";
    private static final String TABLE_HEADER = "<table border=\"0\" id=\"torrents\" width=\"100%\" >\n" + "<thead id=\"snarkHead\">\n";
    private static final String FOOTER = "</div>\n</center>\n<span id=\"endOfPage\" data-iframe-height></span>\n" +
                                         "<script type=\"text/javascript\" src=\"/js/iframeResizer/iframeResizer.contentWindow.js?" +
                                         CoreVersion.VERSION + "\" id=\"iframeResizer\"></script>\n" +
                                         "<style type=\"text/css\">body{opacity: 1 !important}</style>\n</body>\n</html>";

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
     * Get the resource list as a HTML directory listing.
     * @param xxxr The Resource unused
     * @param base The encoded base URL
     * @param parent True if the parent directory should be included
     * @param postParams map of POST parameters or null if not a POST
     * @return String of HTML or null if postParams != null
     * @since 0.7.14
     */
    private String getListHTML(File xxxr, String base, boolean parent, Map<String, String[]> postParams, String sortParam)
        throws IOException
    {
        String decodedBase = decodePath(base);
        String title = decodedBase;
        String cpath = _contextPath + '/';
        if (title.startsWith(cpath))
            title = title.substring(cpath.length());

        // Get the snark associated with this directory
        String torrentName;
        String pathInTorrent;
        int slash = title.indexOf('/');
        if (slash > 0) {
            torrentName = title.substring(0, slash);
            pathInTorrent = title.substring(slash);
        } else {
            torrentName = title;
            pathInTorrent = "/";
        }
        Snark snark = _manager.getTorrentByBaseName(torrentName);

        if (snark != null && postParams != null) {
            // caller must P-R-G
            String[] val = postParams.get("nonce");
            if (val != null) {
                String nonce = val[0];
                if (String.valueOf(_nonce).equals(nonce)) {
                    if (postParams.get("savepri") != null) {
                        savePriorities(snark, postParams);
                    } else if (postParams.get("addComment") != null) {
                        saveComments(snark, postParams);
                    } else if (postParams.get("deleteComments") != null) {
                        deleteComments(snark, postParams);
                    } else if (postParams.get("setCommentsEnabled") != null) {
                        saveCommentsSetting(snark, postParams);
                    } else if (postParams.get("stop") != null) {
                        _manager.stopTorrent(snark, false);
                    } else if (postParams.get("start") != null) {
                        _manager.startTorrent(snark);
                    } else if (postParams.get("recheck") != null) {
                        _manager.recheckTorrent(snark);
                    } else if (postParams.get("editTorrent") != null) {
                        saveTorrentEdit(snark, postParams);
                    } else if (postParams.get("setInOrderEnabled") != null) {
                        _manager.saveTorrentStatus(snark);
                        _manager.addMessage("Sequential piece or file order not saved - feature currently broken.");
                    } else {
                        _manager.addMessage("Unknown command");
                    }
                } else {
                    _manager.addMessage("Please retry form submission (bad nonce)");
                }
            }
            return null;
        }

        File r;
        if (snark != null) {
            Storage storage = snark.getStorage();
            if (storage != null) {
                File sbase = storage.getBase();
                if (pathInTorrent.equals("/"))
                    r = sbase;
                else
                    r = new File(sbase, pathInTorrent);
            } else {
                // magnet, dummy
                r = new File("");
            }
        } else {
            // dummy
            r = new File("");
        }

        boolean showStopStart = snark != null;
        Storage storage = snark != null ? snark.getStorage() : null;
        boolean showPriority = storage != null && !storage.complete() && r.isDirectory();

        StringBuilder buf=new StringBuilder(4096);
        buf.append(DOCTYPE).append("<html>\n<head>\n<title>");
        if (title.endsWith("/"))
            title = title.substring(0, title.length() - 1);
        final String directory = title;
        final int dirSlash = directory.indexOf('/');
        final boolean isTopLevel = dirSlash <= 0;
        title = _t("I2PSnark") + " - [" + _t("Torrent") + ": " + DataHelper.escapeHTML(title) + "]";
        buf.append(title);
        buf.append("</title>\n").append(HEADER_A).append(_themePath).append(HEADER_B).append("\n");
        // uncollapse panels
        boolean collapsePanels = _manager.util().collapsePanels();
        if (!collapsePanels) {
            buf.append(HEADER_A + _themePath + HEADER_C).append("\n");
        }
        // larger fonts for cjk translations
        String lang = (Translate.getLanguage(_manager.util().getContext()));
        if (lang.equals("zh") || lang.equals("ja") || lang.equals("ko")) {
            buf.append(HEADER_A).append(_themePath).append(HEADER_D);
        }
        buf.append(HEADER_A + _themePath + HEADER_I).append("\n"); // images.css
        buf.append(HEADER_A + _themePath + HEADER_Z).append("\n"); // optional override.css for version-persistent user edits
        // hide javascript-dependent buttons when js is unavailable
        buf.append("<noscript><style type=\"text/css\">.script{display:none}</style></noscript>\n")
           .append("<link rel=\"shortcut icon\" href=\"" + _contextPath + WARBASE + "icons/favicon.svg\">\n");
        //if (showPriority) // TODO fixup with ajax refresh
            //buf.append("<script src=\"").append(_contextPath).append(WARBASE + "js/setPriority.js?" + CoreVersion.VERSION + "\" type=\"text/javascript\" async></script>\n");
            //buf.append("<script src=\"/themes/setPriority.js?" + CoreVersion.VERSION + "\" type=\"text/javascript\"></script>\n"); // debugging
        buf.append("</head>\n<body class=\"lang_" + lang + "\">\n" +
                   "<center>\n<div class=\"snarknavbar\"><a href=\"").append(_contextPath).append("/\" title=\"Torrents\"" +
                   " class=\"snarkNav nav_main\">");
        if (_contextName.equals(DEFAULT_NAME))
            buf.append(_t("I2PSnark"));
        else
            buf.append(_contextName);
        buf.append("</a>\n</div>\n");

        if (parent)  // always true
            buf.append("<div class=\"page\" id=\"dirlist\">\n");
        // for stop/start/check
/*
        final boolean er = isTopLevel && snark != null && _manager.util().ratingsEnabled();
        final boolean ec = isTopLevel && snark != null && _manager.util().commentsEnabled(); // global setting
*/
        final boolean er = snark != null && _manager.util().ratingsEnabled();
        final boolean ec = snark != null && _manager.util().commentsEnabled(); // global setting
        final boolean esc = ec && _manager.getSavedCommentsEnabled(snark); // per-torrent setting
        final boolean includeForm = showStopStart || showPriority || er || ec;
        if (includeForm) {
            buf.append("<form action=\"").append(base).append("\" method=\"POST\">\n");
            buf.append("<input type=\"hidden\" name=\"nonce\" value=\"").append(_nonce).append("\" >\n");
            if (sortParam != null) {
                buf.append("<input type=\"hidden\" name=\"sort\" value=\"")
                   .append(DataHelper.stripHTML(sortParam)).append("\" >\n");
            }
        }

// Torrent info section

        if (snark != null) {
            String fullPath = snark.getName();
            String baseName = encodePath((new File(fullPath)).getName());
            MetaInfo meta = snark.getMetaInfo();
            buf.append("<div class=\"mainsection\" id=\"snarkInfo\">");
            buf.append("<table class=\"torrentInfo\" id=\"torrentInfo\">\n");
            buf.append("<tr><th colspan=\"2\">");
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
            buf.append("</th><th><span class=\"infohash\" title=\"")
               .append(_t("Info hash")).append("\">")
               .append(hex.toUpperCase(Locale.US))
               .append("</span>");

            String announce = null;
            // FIXME: if b64 appears in link, convert to b32 or domain name (if known)
            String postmanb64 = "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICkEbKUqJ9mPYQlTSujhNxiRIW-oLwMtvayCFci99oX8MvazPS7~97x0Gsm-onEK1Td9nBdmq30OqDxpRtXBimbzkLbR1IKObbg9HvrKs3L-kSyGwTUmHG9rSQSoZEvFMA-S0EXO~o4g21q1oikmxPMhkeVwQ22VHB0-LZJfmLr4SAAAA";
            String postmanb64_new= "lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICrsRuil8qK~whOvj8uNTv~ohZnTZHxTLgi~sDyo98BwJ-4Y4NMSuF4GLzcgLypcR1D1WY2tDqMKRYFVyLE~MTPVjRRgXfcKolykQ666~Go~A~~CNV4qc~zlO6F4bsUhVZDU7WJ7mxCAwqaMiJsL-NgIkb~SMHNxIzaE~oy0agHJMBQAEAAcAAA==";
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
                    buf.append("<a class=\"magnetlink\" href=\"")
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

                buf.append("<a class=\"torrentlink\" href=\"").append(_contextPath).append('/')
                   .append(baseName).append("\" title=\"").append(DataHelper.escapeHTML(baseName)
                   .replace("%20", " ").replace("%27", "\'").replace("%5B", "[").replace("%5D", "]"))
                   .append("\">")
                   .append(toSVG("torrent", ""))
                   .append("</a>")
                   .append("</th></tr>\n");
                buf.append("<tr id=\"torrentInfoStats\">");
                buf.append("<td colspan=\"3\"><span class=\"nowrap\">");
                toThemeImg(buf, "file");
                buf.append("<b>")
                   .append(_t("Size"))
                   .append(":</b> ")
                   .append(formatSize(snark.getTotalLength()));
                if (storage != null) {
                    int fileCount = storage.getFileCount();
                    buf.append("</span>&nbsp;<span class=\"nowrap\">");
                    toThemeImg(buf, "file");
                    buf.append("<b>")
                       .append(_t("Files"))
                       .append(":</b> ")
                       .append(fileCount);
                }
                int pieces = snark.getPieces();
                double completion = (pieces - snark.getNeeded()) / (double) pieces;
                buf.append("</span>&nbsp;<span class=\"nowrap\">");
                toThemeImg(buf, "file");
                buf.append("<b>")
                   .append(_t("Pieces"))
                   .append(":</b> ")
                   .append(pieces)
                   .append(" @ ")
                   .append(formatSize(snark.getPieceLength(0)).replace("iB", ""));

                // up ratio
                buf.append("</span>&nbsp;<span class=\"nowrap\">");
                toThemeImg(buf, "head_tx");
                buf.append("<b>")
                   .append(_t("Upload ratio").replace("Upload", "Share"))
                   .append(":</b> ");
                long uploaded = snark.getUploaded();
                if (uploaded > 0) {
                    double ratio = uploaded / ((double) snark.getTotalLength());
                    if (ratio < 0.1)
                        buf.append((new DecimalFormat("0.000")).format(ratio));
                    else
                        buf.append((new DecimalFormat("0.00")).format(ratio));
                    buf.append("&#8239;x");
                } else {
                    buf.append('0');
                }

                buf.append("</span>&nbsp;<span id=\"completion\" class=\"nowrap\">");
                toThemeImg(buf, "head_rx");
                buf.append("<b>");
                if (completion < 1.0)
                    buf.append(_t("Completion")).append(":</b> ").append((new DecimalFormat("0.0%"))
                       .format(completion).replace("0.0%","0%"));
                else
                    buf.append(_t("Complete")).append("</b>");

                if (meta != null) {
                    String cby = meta.getCreatedBy();
                    if (cby != null && cby.length() > 0) {
                        buf.append("<span id=\"metainfo\" hidden>");
                        if (cby.length() > 128)
                            cby = cby.substring(0, 128);
                        toThemeImg(buf, "author");
                        buf.append("<b>")
                           .append(_t("Created by")).append(":</b> ")
                           .append(DataHelper.stripHTML(cby));
                    }

                    long dat = meta.getCreationDate();
                    // needs locale configured for automatic translation
                    SimpleDateFormat fmt = new SimpleDateFormat("HH:mm, EEE dd MMMM yyyy");
                    fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                    long[] dates = _manager.getSavedAddedAndCompleted(snark);
                    if (dat > 0 && cby == null)
                        buf.append("<span id=\"metainfo\" hidden>");
                    else if (dat > 0 && cby != null)
                        buf.append("<br>");
                    if (dat > 0) {
                        String date = fmt.format(new Date(dat));
                        toThemeImg(buf, "create");
                        buf.append("<b>").append(_t("Created")).append(":</b> ").append(date);
                    }
                    if (dat <= 0 && dates[0] > 0)
                        buf.append("<span id=\"metainfo\" hidden>");
                    if (dates[0] > 0) {
                        String date = fmt.format(new Date(dates[0]));
                        if (dat > 0)
                            buf.append("<br>");
                        toThemeImg(buf, "add");
                        buf.append("<b>").append(_t("Added")).append(":</b> ").append(date);
                    }
                    if (dates[1] > 0) {
                        String date = fmt.format(new Date(dates[1]));
                        buf.append("<br>").append(toSVG("tick")).append("<b>").append(_t("Completed")).append(":</b> ").append(date);
                    }
                    if (dat > 0 || dates[0] > 0)
                        buf.append("</span>");
                    buf.append("</span>"); // close #completion

                    // not including skipped files, but -1 when not running
                    long needed = snark.getNeededLength();
                    if (needed < 0) {
                       // including skipped files, valid when not running
                       needed = snark.getRemainingLength();
                    }
                    if (needed > 0) {
                       buf.append("&nbsp;<span class=\"nowrap\">");
                       toThemeImg(buf, "head_rx");
                       buf.append("<b>")
                          .append(_t("Remaining"))
                          .append(":</b> ")
                          .append(formatSize(needed))
                          .append("</span>");
                    }
                    long skipped = snark.getSkippedLength();
                    if (skipped > 0) {
                        buf.append("&nbsp;<span class=\"nowrap\">");
                        toThemeImg(buf, "head_rx");
                        buf.append("<b>").append(_t("Skipped")).append(":</b> ").append(formatSize(skipped)).append("</span");
                    }

                    // needs locale configured for automatic translation
                    fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                    if (storage != null) {
                        dat = storage.getActivity();
                        if (dat > 0) {
                            String date = fmt.format(new Date(dat));
                            buf.append("&nbsp;<span class=\"nowrap\">");
                            toThemeImg(buf, "torrent");
                            buf.append("<b>").append(_t("Last activity")).append(":</b> ").append(date).append("</span>");
                        }
                    }
                }
                buf.append("</td></tr>\n");

                List<List<String>> alist = meta.getAnnounceList();
                if (alist != null && !alist.isEmpty()) {
                    buf.append("<tr id=\"trackers\" title=\"")
                       .append(_t("Only I2P trackers will be used; non-I2P trackers are displayed for informational purposes only"))
                       .append("\"><td colspan=\"3\">");
                    toThemeImg(buf, "torrent");
                    buf.append("<b>")
                       .append(_t("Trackers")).append(":</b> ");

                    for (List<String> alist2 : alist) {
                        if (alist2.isEmpty()) {
                            buf.append("<span class=\"info_tracker primary\">");
                            boolean more = false;
                            for (String s : alist2) {
                                if (more)
                                    buf.append("<span class=\"info_tracker\">");
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
                        buf.append("<span class=\"info_tracker\">");
                        boolean more = false;
                        for (String s : alist2) {
                            if (more)
                                buf.append("<span class=\"info_tracker\">");
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
                    buf.append("</td></tr>\n");
                } else {
                    if (meta != null) {
                    announce = meta.getAnnounce();
                    if (announce == null)
                       announce = snark.getTrackerURL();
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
                        buf.append("<tr id=\"trackers\" title=\"")
                           .append(_t("Only I2P trackers will be used; non-I2P trackers are displayed for informational purposes only"))
                           .append("\"><td colspan=\"3\">");
                        toThemeImg(buf, "torrent");
                        buf.append("<b>")
                           .append(_t("Tracker")).append(":</b> ");
                        buf.append("<span class=\"info_tracker primary\">");
                        buf.append(getShortTrackerLink(announce, snark.getInfoHash()));
                        buf.append("</span> ");
                        buf.append("</td></tr>\n");
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
                        buf.append("<tr id=\"webseeds\"><td colspan=\"3\">");
                        toThemeImg(buf, "torrent");
                        buf.append("<b>")
                           .append(_t("Web Seeds")).append("</b>: ");
                        boolean more = false;
                        for (String s : wlist) {
                            buf.append("<span class=\"info_tracker\">");
                            if (more)
                                buf.append(' ');
                            else
                                more = true;
                            buf.append(getShortTrackerLink(DataHelper.stripHTML(s), snark.getInfoHash()));
                            buf.append("</span> ");
                        }
                        buf.append("</td></tr>\n");
                    }
                }
            }

            if (meta != null) {
                String com = meta.getComment();
                if (com != null && com.length() > 0) {
                    if (com.length() > 4000)
                        com = com.substring(0, 4000) + "&hellip;";
                    buf.append("<tr><td id=\"metacomment\" colspan=\"3\">");
                    buf.append("<div class=\"commentWrapper\">")
                       .append(DataHelper.stripHTML(com).replace("\r\n", "<br>").replace("\n", "<br>"))
                       .append("</div></td></tr>\n");
                }
            }

            // buttons
            if (showStopStart) {
                buf.append("<tr id=\"torrentInfoControl\"><td colspan=\"3\">");
                if (snark.isChecking()) {
                    buf.append("<span id=\"fileCheck\"><b>").append(_t("Checking")).append("&hellip; ")
                       .append((new DecimalFormat("0.0%")).format(snark.getCheckingProgress()))
                       .append("&nbsp;<a href=\"").append(base).append("\">")
                       .append(_t("Refresh page for results")).append("</a></b></span>");
                } else if (snark.isStarting()) {
                    buf.append("<b>").append(_t("Starting")).append("&hellip;</b>");
                } else if (snark.isAllocating()) {
                    buf.append("<b>").append(_t("Allocating")).append("&hellip;</b>");
                } else {
                    boolean isRunning = !snark.isStopped();
                    buf.append("<input type=\"submit\" value=\"");
                    if (isRunning)
                        buf.append(_t("Stop")).append("\" name=\"stop\" class=\"stoptorrent\">");
                    else
                        buf.append(_t("Start")).append("\" name=\"start\" class=\"starttorrent\">");
                    buf.append("<input type=\"submit\" name=\"recheck\" value=\"").append(_t("Force Recheck"));
                    if (isRunning)
                        buf.append("\" class=\"disabled\" disabled=\"disabled\" title=\"")
                           .append(_t("Stop the torrent in order to check file integrity"))
                           .append("\">");
                    else
                        buf.append("\" class=\"reload\" title=\"")
                           .append(_t("Check integrity of the downloaded files"))
                           .append("\">");
                }
/**
// TODO: fix feature so checkbox status is saved
                boolean showInOrder = storage != null && !storage.complete() && meta != null;
                if (showInOrder && meta != null) {
                    buf.append("<span id=\"torrentOrderControl\"><label for=\"enableInOrder\"><b>");
                    String txt = (meta.getFiles() != null && meta.getFiles().size() > 1) ?
                                   _t("Download files in order") :
                                   _t("Download pieces in order");
                    buf.append(txt);
                    buf.append("</b></label><input type=\"checkbox\" class=\"optbox\" name=\"enableInOrder\" id=\"enableInOrder\"");
                    if (storage.getInOrder())
                        buf.append(" checked=\"checked\"");
                    buf.append(">" +
                               "<input type=\"submit\" name=\"setInOrderEnabled\" value=\"");
                    buf.append(_t("Save Preference"));
                    buf.append("\" class=\"accept\"></span>");
                }
**/
                buf.append("</td></tr>\n");
            }
        } else {
            // snark == null
            // shouldn't happen
            buf.append("<table class=\"resourceError\" id=\"NotFound\"><tr><th colspan=\"2\">")
               .append(_t("Resource Not found"))
               .append("</th></tr><tr><td><b>").append(_t("Torrent")).append(":</b></td><td>").append(DataHelper.escapeHTML(torrentName))
               .append("</td></tr><tr><td><b>").append(_t("Base")).append(":</b></td><td>").append(base)
               .append("</td></tr>\n");
        }
        buf.append("</table>\n");
        buf.append("</div>\n");

        displayTorrentEdit(snark, base, buf);

        if (snark != null && !r.exists()) {
            // fixup TODO
            buf.append("<table class=\"resourceError\" id=\"DoesNotExist\">\n<tr><th colspan=\"2\">")
               .append(_t("Resource Does Not Exist"))
               .append("</th></tr><tr><td><b>").append(_t("Resource")).append(":</b></td><td>").append(r.toString())
               .append("</td></tr><tr><td><b>").append(_t("Base")).append(":</b></td><td>").append(base)
               .append("</td></tr><tr><td><b>").append(_t("Torrent")).append(":</b></td><td>").append(DataHelper.escapeHTML(torrentName))
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
                String newTab = "<img src=\"/themes/console/light/images/newtab.png\" width=\"16\" height=\"auto\" class=\"newTab\">";
                if (isAudio || isVideo) {
                    buf.append("<div class=\"mainsection\" id=\"media\">")
                       .append("<table id=\"mediaContainer\">\n<tr>");
                    // HTML5
                    if (isAudio) {
                        buf.append("<th class=\"audio\">").append(_t("Audio file: ")).append(DataHelper.escapeHTML(torrentName))
                           .append("<a href=\"").append(path).append("\" title=\"Open in new tab\" target=\"_blank\">")
                           .append(newTab)
                           .append("</a>").append("</th></tr>\n<tr><td>")
                           .append("<audio controls>");
                    } else {
                        buf.append("<th id=\"videoTitle\" class=\"video\">").append(_t("Video file: ")).append(DataHelper.escapeHTML(torrentName))
                           .append("<a href=\"").append(path).append("\" title=\"Open in new tab\" target=\"_blank\">")
                           .append(newTab)
                           .append("</a>").append("</th></tr>\n<tr><td>")
                           .append("<video id=\"embedVideo\" controls>");
                    }
                    // strip trailing slash
                    buf.append("<source src=\"").append(path).append("\" type=\"").append(mime).append("\">");
                    if (isAudio) {
                        buf.append("</audio>");
                    } else {
                        buf.append("</video>")
                           .append("<script src=\"").append(_contextPath).append(WARBASE + "js/getMetadata.js?" + CoreVersion.VERSION +
                                   "\" type=\"text/javascript\"></script>\n");
                    }
                    buf.append("</td></tr>\n</table>\n</div>\n");
                }
            }
            if (er || ec) {
                CommentSet comments = snark.getComments();
                buf.append("<div class=\"mainsection\" id=\"commentSection\">")
                   .append("<input class=\"toggle_input\" id=\"toggle_comments\" type=\"checkbox\"");
                if (comments != null && !comments.isEmpty())
                    buf.append(" checked");
                buf.append(">\n<label id=\"tab_comments\" class=\"toggleview\" for=\"toggle_comments\"><span class=\"tab_label\">")
                   .append(_t("Comments &amp; Ratings"))
                   .append("</span></label><hr>\n");
                displayComments(snark, er, ec, esc, buf);
                buf.append("</div>\n");
            }
            if (includeForm)
                buf.append("</form>\n");
                buf.append(FOOTER);
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
                if ((n.equals(".pad") || n.equals("_pad")) && f.isDirectory())
                    continue;
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

        buf.append("<div class=\"mainsection\" id=\"snarkFiles\">");
        buf.append("<input class=\"toggle_input\" id=\"toggle_files\" type=\"checkbox\"");
        // don't collapse file view if not in torrent root
        String up = "";
        if (!isTopLevel || fileList.size() <= 10 || sortParam != null || getQueryString(up) != null)
            buf.append(" checked");
        buf.append(">");
        buf.append("<label id=\"tab_files\" class=\"toggleview\" for=\"toggle_files\"><span class=\"tab_label\">");
//        buf.append(toImg("folder", ""));
//        buf.append(' ');
        buf.append(_t("Files"));
        buf.append("</span></label><hr>\n");
        buf.append("<table class=\"dirInfo\">\n<thead>\n" +
                   "<tr>\n" +
                   "<th colspan=2>");
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
            buf.append("<a href=\"").append(base)
               .append(getQueryString(sort)).append("\">");
        }
        toThemeImg(buf, "file", tx,
                   showSort ? _t("Sort by {0}", (isTypeSort ? _t("File type") : _t("Name")))
                            : tx + ": " + directory);
        if (showSort)
            buf.append("</a>");
        if (!isTopLevel) {
            buf.append("&nbsp;");
            buf.append(DataHelper.escapeHTML(directory.substring(dirSlash + 1)));
        }
        buf.append("</th>\n<th class=\"snarkFileSize\">");
        if (showSort) {
            sort = ("-5".equals(sortParam)) ? "5" : "-5";
            buf.append("<a href=\"").append(base)
               .append(getQueryString(sort)).append("\">");
        }
        tx = _t("Size");
        toThemeImg(buf, "size", tx,
                   showSort ? _t("Sort by {0}", tx) : tx);
        if (showSort)
            buf.append("</a>");
        buf.append("</th>\n<th class=\"fileStatus\">");
        boolean showRemainingSort = showSort && showPriority;
        if (showRemainingSort) {
            sort = ("10".equals(sortParam)) ? "-10" : "10";
            buf.append("<a id=\"sortRemaining\" href=\"").append(base)
               .append(getQueryString(sort)).append("\">");
        }
        tx = _t("Download Status");
        toThemeImg(buf, "status", tx,
                   showRemainingSort ? _t("Sort by {0}", _t("Remaining")) : tx);
        if (showRemainingSort)
            buf.append("</a>");
        if (showPriority) {
            buf.append("</th>\n<th class=\"priority volatile\">");
            if (showSort) {
                sort = ("13".equals(sortParam)) ? "-13" : "13";
                buf.append("<a href=\"").append(base)
                   .append(getQueryString(sort)).append("\">");
            }
            tx = _t("Download Priority");
            toThemeImg(buf, "priority", tx,
                       showSort ? _t("Sort by {0}", tx) : tx);
            if (showSort)
                buf.append("</a>");
        }
        buf.append("</th>\n</tr>\n</thead>\n<tbody id=\"dirInfo\">");
        if (!isTopLevel || hasCompleteAudio(fileList, storage, remainingArray)) { // don't show row if top level or no playlist
            buf.append("<tr><td colspan=\"" + (showPriority ? '3' : '2') + "\" class=\"ParentDir\">");
            if (!isTopLevel) { // don't show parent dir link if top level
                up = "up";
                buf.append("<a href=\"");
                URIUtil.encodePath(buf, addPaths(decodedBase,"../"));
                buf.append("/").append(getQueryString(up));
                buf.append("\">");
                buf.append(toThemeSVG(up, "", ""));
                buf.append(' ')
                   .append(_t("Up to higher level directory").replace("Up to higher level", "Parent"))
                   .append("</a>");
            }

            buf.append("</td><td colspan=\"2\" class=\"ParentDir playlist\">");
            // playlist button
            if (hasCompleteAudio(fileList, storage, remainingArray)) {
                buf.append("<a href=\"").append(base).append("?playlist");
                if (sortParam != null && !"0".equals(sortParam) && !"1".equals(sortParam))
                    buf.append("&amp;sort=").append(sortParam);
                buf.append("\">");
                buf.append(toSVG("playlist"));
                buf.append(' ').append(_t("Audio Playlist")).append("</a>");
            }
            buf.append("</td></tr>\n");
        }

        //DateFormat dfmt=DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
        boolean showSaveButton = false;
        boolean rowEven = true;
        boolean inOrder = storage != null && storage.getInOrder();
        for (Sorters.FileAndIndex fai : fileList)
        {
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
            if (fai.isDirectory) {
                complete = true;
            } else {
                if (storage == null) {
                    // Assume complete, perhaps he removed a completed torrent but kept a bookmark
                    complete = true;
                    status = toSVG("warn", "iconStatus", _t("Not found"), _t("Torrent not found?").replace("?", ""));
                } else {

                            long remaining = fai.remaining;
                            if (remaining < 0 || (remaining < 0 && fai.isDirectory)) {
                                complete = true;
                                status = toSVG("warn", "iconStatus", _t("Unrecognized"), _t("File not found in torrent?").replace("?", ""));
                            } else if (remaining == 0 || length <= 0) {
                                complete = true;
                                status = toSVG("tick", "iconStatus", _t("Complete"), _t("Complete"));
                            } else {
                                priority = fai.priority;
                                if (priority < 0)
                                    status = "<div class=\"priorityIndicator\">" + toImg("block") + "</div>";
                                else if (priority == 0)
                                    status = "<div class=\"priorityIndicator\">" + toImg("clock") + "</div>";
                                else
                                    status = "<div class=\"priorityIndicator\">" + toImg("clock_red") + "</div>";
                                long percent = 100 * (length - remaining) / length;
                                status += " <div class=\"percentBarOuter\">" +
                                         "<div class=\"percentBarInner\" style=\"width: " +
                                         percent + "%;\"><div class=\"percentBarText\" tabindex=\"0\" title=\"" +
                                         formatSize(remaining) + ' ' + _t("remaining") +
                                         "\">" + percent + "%</div></div></div>";
                            }

                }
            }

            String rowClass = (rowEven ? "rowEven" : "rowOdd");
            String completed = (complete ? "completed" : "incomplete");
            rowEven = !rowEven;
            buf.append("<tr class=\"").append(rowClass).append(' ').append(completed).append("\">");

            String path = addPaths(decodedBase, item.getName());
            if (fai.isDirectory && !path.endsWith("/"))
                path=addPaths(path,"/");
            path = encodePath(path);
            String icon = toIcon(item);
            String mime = getMimeType(path);
            if (mime == null)
                mime = "";

            boolean isAudio = isAudio(mime);
            boolean isVideo = !isAudio && isVideo(mime);
            int videoCount = 0;
            buf.append("<td class=\"fileIcon");
            if (!complete)
                buf.append(" volatile");
            buf.append("\">");
            String preview = null;
            if (isVideo && complete)
              videoCount++;
            if (complete ||
                (isAudio && fai.preview > 100*1024) ||
                (isVideo && fai.preview > 5*1024*1024 && fai.preview / (double) fai.length >= 0.01d)) {
                String ppath = complete ? path : path + "?limit=" + fai.preview;
                if (!complete) {
                    double pct = fai.preview / (double) fai.length;
                    preview = " &nbsp;<span class=\"audioPreview\">" + _t("Preview") + ": " + (new DecimalFormat("0.00%")).format(pct) + "</span>";
                }
                if (isAudio || isVideo) {
                    // scale up image thumbnails if directory also contains audio/video
                    buf.append("\n<style type=\"text/css\">.thumb{max-height: inherit !important; max-width: 240px !important;}</style>\n");
                    // HTML5
                    if (isAudio)
                        buf.append("<audio");
                    else
                        buf.append("<video");
                    buf.append(" controls><source src=\"").append(ppath);
                    if (isVideo)
                        // display video 20 seconds in for a better chance of a thumbnail
                        buf.append("#t=20");
                    buf.append("\" type=\"").append(mime).append("\">");
                }
                buf.append("<a href=\"").append(ppath).append("\">");
                if (mime.startsWith("image/")) {
                    // thumbnail
                    buf.append("<img alt=\"\" border=\"0\" class=\"thumb\" src=\"")
                       .append(ppath).append("\" data-lightbox data-lightbox-caption=\"")
                       .append(item.getName()).append("\" data-lightbox-group=\"allInDir\"></a>");
                } else {
                    buf.append(toSVG(icon, _t("Open"))).append("</a>");
                }
                if (isAudio)
                    buf.append("</audio>");
                else if (isVideo)
                    buf.append("</video>");
                if (videoCount == 1) {
                    buf.append("<script src=\"").append(_contextPath).append(WARBASE + "js/getMetadata.js?" + CoreVersion.VERSION +
                    //buf.append("<script src=\"/themes/getMetadata.js?" + CoreVersion.VERSION + // debugging
                               "\" type=\"text/javascript\"></script>\n");
                }
            } else {
                buf.append(toSVG(icon));
            }
            buf.append("</td><td class=\"snarkFileName");
            if (!complete)
                buf.append(" volatile");
            buf.append("\">");
            if (complete) {
                buf.append("<a href=\"").append(path);
                buf.append("\"");
                // send browser-viewable files to new tab to avoid potential display in iframe
                if (isAudio || isVideo || mime.startsWith("text/") || mime.startsWith("image/") || mime.equals("application/pdf"))
                    buf.append(" target=\"_blank\"");
                if (mime.equals("audio/mpeg"))
                    buf.append(" class=\"targetfile\"");
                buf.append(">");
            }
            buf.append(DataHelper.escapeHTML(item.getName()));
            if (complete) {
                buf.append("</a>");
                if (mime.equals("audio/mpeg")) {
                    String tags = Mp3Tags.getTags(item);
                    buf.append("<a class=\"tags\" href=\"").append(path);
                    buf.append("\" target=\"_blank\" hidden>");
                    if (tags != null)
                        buf.append(tags);
                    else
                        buf.append(DataHelper.escapeHTML(item.getName()));
                    buf.append("</a>");
                }
            } else if (preview != null) {
                buf.append(preview);
            }
            buf.append("</td><td align=right class=\"snarkFileSize\">");
            if (!fai.isDirectory)
                buf.append(formatSize(length));
            buf.append("</td><td class=\"fileStatus volatile\">");
            //buf.append(dfmt.format(new Date(item.lastModified())));
            buf.append(status);
            buf.append("</td>");
            if (showPriority) {
                buf.append("<td class=\"priority volatile\">");
                if ((!complete) && (!fai.isDirectory)) {
                    if (!inOrder) {
                    buf.append("<label class=\"priorityHigh\" title=\"").append(_t("Download file at high priority")).append("\">" +
                               "\n<input type=\"radio\" class=\"prihigh optbox\" value=\"5\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority > 0)
                        buf.append("checked=\"checked\"");
                    buf.append('>')
                       .append(_t("High")).append("</label>");
                    }

                    buf.append("<label class=\"priorityNormal\" title=\"").append(_t("Download file at normal priority")).append("\">" +
                               "\n<input type=\"radio\" class=\"prinorm optbox\" value=\"0\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority == 0 || (inOrder && priority >= 0))
                        buf.append("checked=\"checked\"");
                    buf.append('>')
                       .append(_t("Normal")).append("</label>");

                    buf.append("<label class=\"prioritySkip\" title=\"").append(_t("Do not download this file")).append("\">" +
                               "\n<input type=\"radio\" class=\"priskip optbox\" value=\"-9\" name=\"pri.").append(fileIndex).append("\" ");
                    if (priority < 0)
                        buf.append("checked=\"checked\"");
                    buf.append('>')
                       .append(_t("Skip")).append("</label>");
                    showSaveButton = true;
                }
                buf.append("</td>");
            }
            buf.append("</tr>\n");
        }
        if (showSaveButton) {
            buf.append("</tbody>\n<thead><tr id=\"setPriority\"><th colspan=\"5\">");

/* TODO: fixup so works with ajax refresh
                       "<span class=\"script\">");
            if (!inOrder) {
                buf.append("<a class=\"control\" id=\"setallhigh\" href=\"#\">")
                   .append(toImg("clock_red")).append(_t("Set all high")).append("</a>\n");
            }
            buf.append("<a class=\"control\" id=\"setallnorm\" href=\"#\">")
               .append(toImg("clock")).append(_t("Set all normal")).append("</a>\n" +
                       "<a class=\"control\" id=\"setallskip\" href=\"#\">")
               .append(toImg("block")).append(_t("Skip all")).append("</a></span>\n");
*/

           buf.append("<input type=\"submit\" class=\"accept\" value=\"").append(_t("Save priorities"))
               .append("\" name=\"savepri\" >\n" +
                       "</th></tr></thead>\n");
        }
        buf.append("</table>\n</div>\n");

// Comment section

        CommentSet comments = snark.getComments();
        if (er || ec) {
            buf.append("<div class=\"mainsection\" id=\"commentSection\">\n");
                buf.append("<input class=\"toggle_input\" id=\"toggle_comments\" type=\"checkbox\"");
                if (comments != null && !comments.isEmpty())
                    buf.append(" checked");
                buf.append(">\n<label id=\"tab_comments\" class=\"toggleview\" for=\"toggle_comments\"><span class=\"tab_label\">");
                buf.append(_t("Comments"));
            buf.append("</span></label><hr>\n");
            displayComments(snark, er, ec, esc, buf);
            // for stop/start/check
            buf.append("</div>\n");
        }
        if (includeForm)
            buf.append("</form>\n");
        boolean enableLightbox = _manager.util().enableLightbox();
        if (!showRemainingSort && enableLightbox) {
            buf.append("<link type=\"text/css\" rel=\"stylesheet\" href=\"").append(_contextPath).append(WARBASE + "lightbox.css\">\n");
            buf.append("<script src=\"").append(_contextPath).append(WARBASE + "js/lightbox.js?" + CoreVersion.VERSION + "\" type=\"text/javascript\"></script>\n")
               .append("<script nonce=\"" + cspNonce + "\" type=\"text/javascript\">\nvar lightbox = new Lightbox();lightbox.load();\n</script>\n");
        }
        int delay = _manager.getRefreshDelaySeconds();
        if (delay > 0) {
            buf.append("<script nonce=\"" + cspNonce + "\" type=\"module\">\n" +
                       "import {refreshTorrents} from \"" + _contextPath + WARBASE + "js/refreshTorrents.js?" + CoreVersion.VERSION + "\";\n" +
                       "var ajaxDelay = " + (delay * 1000) + ";\n" +
                       "var visibility = document.visibilityState;\n" +
                       "var cycle;\n" +
                       "if (visibility = \"visible\") {\n" +
                       "function timer() {\n" +
                       "var cycle = setInterval(function() {\n" +
                       "requestAnimationFrame(refreshTorrents);\n");
            if (enableLightbox)
                buf.append("import {Lightbox} from \"" + _contextPath + WARBASE + "js/lightbox.js?" + CoreVersion.VERSION + "\";\n" +
                           "var lightbox = new Lightbox();\nlightbox.load();\n");
            buf.append("}, ajaxDelay);\n" +
                       "}\n" +
                       "timer();\n" +
                       "}\n" +
                       "</script>\n");
        }
        buf.append(FOOTER);
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
            if (!"http".equals(method) && !"https".equals(method))
                return false;
            String host = uri.getHost();
            if (host == null || !host.endsWith(".i2p"))
                return false;
        } catch (URISyntaxException use) {
            return false;
        }
        return true;
    }

    /**
     * @param mime non-null
     * @since 0.9.44
     */
    private static boolean isAudio(String mime) {
        // don't include playlist files as the browser doesn't support them
        // in the HTML5 player,
        // and if it did and prefetched, that could be a security issue
        return (mime.startsWith("audio/") &&
                !mime.equals("audio/mpegurl") &&
                !mime.equals("audio/x-scpls")) ||
               mime.equals("application/ogg");
    }

    /**
     * @param mime non-null
     * @since 0.9.44
     */
    private static boolean isVideo(String mime) {
        return mime.startsWith("video/") &&
               !mime.equals("video/x-msvideo") &&
               !mime.equals("video/x-matroska") &&
               !mime.equals("video/quicktime") &&
               !mime.equals("video/x-flv");
    }

    /**
     * Is there at least one complete audio file in this directory or below?
     * Recursive.
     *
     * @since 0.9.44
     */
    private boolean hasCompleteAudio(List<Sorters.FileAndIndex> fileList,
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
                    if (hasCompleteAudio(fl2, storage, remainingArray))
                        return true;
                }
                continue;
            }
            if (fai.remaining != 0)
                continue;
            String name = fai.file.getName();
            String mime = getMimeType(name);
            if (mime != null && isAudio(mime))
                return true;
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
        if (title.startsWith(cpath))
            title = title.substring(cpath.length());

        // Get the snark associated with this directory
        String torrentName;
        String pathInTorrent;
        int slash = title.indexOf('/');
        if (slash > 0) {
            torrentName = title.substring(0, slash);
            pathInTorrent = title.substring(slash);
        } else {
            torrentName = title;
            pathInTorrent = "/";
        }
        Snark snark = _manager.getTorrentByBaseName(torrentName);
        if (snark == null)
            return null;
        Storage storage = snark.getStorage();
        if (storage == null)
            return null;
        File sbase = storage.getBase();
        File r;
        if (pathInTorrent.equals("/"))
            r = sbase;
        else
            r = new File(sbase, pathInTorrent);
        if (!r.isDirectory())
            return null;
        File[] ls = r.listFiles();
        if (ls == null)
            return null;
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
                try {
                    sort = Integer.parseInt(sortParam);
                } catch (NumberFormatException nfe) {}
            }
            DataHelper.sort(fileList, Sorters.getFileComparator(sort, this));
        }
        StringBuilder buf = new StringBuilder(512);
        getPlaylist(buf, fileList, reqURL, sort, storage, remainingArray);
        String rv = buf.toString();
        if (rv.length() <= 0)
            return null;
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
                    if (ls.length > 1)
                        DataHelper.sort(fl2, Sorters.getFileComparator(sort, this));
                    String name = fai.file.getName();
                    String url2 = reqURL + encodePath(name) + '/';
                    getPlaylist(buf, fl2, url2, sort, storage, remainingArray);
                }
                continue;
            }
            if (fai.remaining != 0)
                continue;
            String name = fai.file.getName();
            String mime = getMimeType(name);
            if (mime != null && isAudio(mime)) {
                // TODO Extended M3U
                buf.append(reqURL).append(encodePath(name)).append('\n');
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

            buf.append("<table class=\"snarkCommentInfo\">\n<tr><th colspan=\"3\">")
               .append(_t("Ratings and Comments").replace("and", "&amp;"))
               .append("&nbsp;&nbsp;&nbsp;");
            if (esc && !canRate) {
                buf.append("<span id=\"nameRequired\">");
                buf.append(_t("Author name required to rate or comment"));
                buf.append("&nbsp;&nbsp;<a href=\"").append(_contextPath).append("/configure#configureAuthor\">[");
                buf.append(_t("Configure"));
                buf.append("]</a></span>");
            } else if (esc) {
                buf.append("<span id=\"nameRequired\"><span class=\"commentAuthorName\" title=\"")
                   .append(_t("Your author name for published comments and ratings"))
                   .append("\">");
                buf.append(DataHelper.escapeHTML(_manager.util().getCommentsName()));
                buf.append("</span></span>");
            }

            buf.append("<span id=\"commentstoggle\"><label><input type=\"checkbox\" class=\"optbox\" name=\"enableComments\" id=\"enableComments\" title=\"");
            buf.append(_t("Enable viewing and posting comments for this torrent")).append("\" ");
            if (esc)
                buf.append("checked=\"checked\"");
            else if (!ec)
                buf.append("disabled=\"disabled\"");
            buf.append(">&nbsp;");
            buf.append("</label> &nbsp;");
            if (ec) {
                buf.append("<input type=\"submit\" name=\"setCommentsEnabled\" value=\"");
                buf.append(_t("Save Preference"));
                buf.append("\" class=\"accept\">");
            }
            buf.append("</span></th></tr>\n");

            // new rating / comment form
            if (canRate) {
                buf.append("<tr id=\"newRating\">\n");
                if (er) {
                    buf.append("<td>\n<select name=\"myRating\">\n");
                    for (int i = 5; i >= 0; i--) {
                        buf.append("<option value=\"").append(i).append("\" ");
                        if (i == myRating)
                            buf.append("selected=\"selected\"");
                        buf.append('>');
                        if (i != 0) {
                            for (int j = 0; j < i; j++) {
                                buf.append("★");
                            }
                            buf.append(' ').append(ngettext("1 star", "{0} stars", i));
                        } else {
                            buf.append("☆ ").append(_t("No rating"));
                        }
                        buf.append("</option>\n");
                    }
                    buf.append("</select>\n</td>");
                } else {
                    buf.append("<td></td>\n");
                }
                if (esc) {
                    buf.append("<td id=\"addCommentText\"><textarea name=\"nofilter_newComment\" cols=\"44\" rows=\"4\"></textarea></td>\n");
                } else {
                    buf.append("<td></td>\n");
                }
                buf.append("<td class=\"commentAction\"><input type=\"submit\" name=\"addComment\" value=\"");
                if (er && esc)
                    buf.append(_t("Rate and Comment"));
                else if (er)
                    buf.append(_t("Rate Torrent"));
                else
                    buf.append(_t("Add Comment"));
                buf.append("\" class=\"accept\"></td>\n");
                buf.append("</tr>\n");
            }

            if (comments != null) {
                synchronized(comments) {
                    // current rating
                    if (er) {
                        buf.append("<tr id=\"myRating\"><td>");
                        myRating = comments.getMyRating();
                        if (myRating > 0) {
                            buf.append(_t("My Rating")).append(":</td><td colspan=\"2\" class=\"commentRating\">");
                            String img = toSVG("rateme", "★", "");
                            for (int i = 0; i < myRating; i++) {
                                buf.append(img);
                            }
                        }
                        buf.append("</td></tr>");
                    }
                    if (er) {
                        buf.append("<tr id=\"showRatings\"><td>");
                        int rcnt = comments.getRatingCount();
                        if (rcnt > 0) {
                            double avg = comments.getAverageRating();
                            buf.append(_t("Average Rating")).append(":</td><td colspan=\"2\">").append((new DecimalFormat("0.0")).format(avg));
                        } else {
                            buf.append(_t("Average Rating")).append(":</td><td colspan=\"2\">");
                            buf.append(_t("No community ratings currently available"));
                        }
                        buf.append("</td></tr>\n");
                    }
                    if (ec) {
                        int sz = comments.size();
                        if (sz > 0)
                            iter = comments.iterator();
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
                buf.append("<table class=\"snarkComments\">\n");

                while (iter.hasNext()) {
                    Comment c = iter.next();
                    buf.append("<tr><td class=\"commentAuthor\">");
                    // (c.isMine())
                    //  buf.append(_t("me"));
                    //else if (c.getName() != null)
                    // TODO can't be hidden... hide if comments are hidden?
                    if (c.getName() != null) {
                        buf.append("<span class=\"commentAuthorName\" title=\"" + DataHelper.escapeHTML(c.getName()) + "\">");
                        buf.append(DataHelper.escapeHTML(c.getName()));
                        buf.append("</span>");
                    }
                    buf.append("</td><td class=\"commentRating\">");
                    if (er) {
                        int rt = c.getRating();
                        if (rt > 0) {
                            String img = toSVG("rateme", "★", "");
                            for (int i = 0; i < rt; i++) {
                                buf.append(img);
                            }
                        }
                    }
//                    buf.append("</td><td class=\"commentDate\">").append(fmt.format(new Date(c.getTime())));
                    buf.append("</td><td class=\"commentText\">");
                    if (esc) {
                        if (c.getText() != null) {
                            buf.append("<div class=\"commentWrapper\" title=\"").append(_t("Submitted")).append(": ").append(fmt.format(new Date(c.getTime()))).append("\">");
                            buf.append(DataHelper.escapeHTML(c.getText()));
                            buf.append("</div></td><td class=\"commentDelete\"><input type=\"checkbox\" class=\"optbox\" name=\"cdelete.")
                               .append(c.getID()).append("\" title=\"").append(_t("Mark for deletion")).append("\">");
                            ccount++;
                        } else {
                            buf.append("</td><td class=\"commentDelete\">"); // insert empty named columns to maintain table layout
                        }
                    } else {
                        buf.append("</td><td class=\"commentDelete\">"); // insert empty named columns to maintain table layout
                    }
                    buf.append("</td></tr>\n");
                }
                if (esc && ccount > 0) {
                    // TODO format better
                    buf.append("<tr id=\"commentDeleteAction\"><td colspan=\"4\" class=\"commentAction\" align=\"right\">")
                       .append("<input type=\"submit\" name=\"deleteComments\" value=\"");
                    buf.append(_t("Delete Selected"));
                    buf.append("\" class=\"delete\"></td></tr>\n");
                }
                buf.append("</table>\n");
            } else if (esc) {
                //buf.append(_t("No comments for this torrent"));
            } // iter != null
    }

    /**
     *  @param so null ok
     *  @return query string or ""
     *  @since 0.9.16
     */
    private static String getQueryString(String so) {
        if (so != null && !so.equals(""))
            return "?sort=" + DataHelper.stripHTML(so);
        return "";
    }

    /**
     *  Pick an icon; try to catch the common types in an i2p environment.
     *
     *  @return file name not including ".png"
     *  @since 0.7.14
     */
    private String toIcon(File item) {
        if (item.isDirectory())
            return "folder";
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
        if (mime == null)
            mime = "";
        if (mime.equals("text/html") || plc.endsWith(".jsp"))
            icon = "html";
        else if (mime.equals("text/plain") || mime.equals("text/x-sfv") ||
                 mime.equals("application/rtf") || plc.endsWith(".md") ||
                 plc.endsWith(".ini") || plc.endsWith(".srt") || plc.endsWith(".nfo"))
            icon = "text";
        else if  (mime.equals("application/epub+zip") ||
                 mime.equals("application/x-mobipocket-ebook") ||
                 plc.endsWith(".fb2") || plc.endsWith(".azw3") ||
                 plc.endsWith(".azw4") || plc.endsWith(".prc"))
            icon = "ebook";
        else if (mime.equals("application/x-jar") || mime.equals("application/x-java-archive") ||
               mime.equals("application/java-archive") || plc.endsWith(".jar") || plc.endsWith(".exe")) {
            if (plc.contains("i2pinstall") && plc.contains("+"))
                icon = "plus";
            else if (plc.contains("i2pinstall"))
                icon = "i2p";
            else if (plc.endsWith(".exe"))
                icon = "windows";
            else
                icon = "package";
        } else if (mime.equals("application/java-archive") || plc.endsWith(".deb") || plc.endsWith(".rpm") ||
                   plc.endsWith(".flatpak") || plc.endsWith(".snap") || plc.endsWith(".appimage"))
            icon = "package";
        else if (plc.endsWith(".xpi2p"))
            icon = "plugin";
        else if (mime.equals("application/pdf"))
            icon = "pdf";
        else if (mime.startsWith("image/"))
            icon = "image";
        else if (mime.startsWith("audio/") || mime.equals("application/ogg"))
            icon = "audio";
        else if (mime.startsWith("video/"))
            icon = "video";
        else if (mime.startsWith("font/") || plc.endsWith(".ttf") || plc.endsWith(".woff") || plc.endsWith(".woff2"))
            icon = "font";
        else if (mime.equals("application/zip")) {
            if (plc.endsWith(".su3") || plc.endsWith(".su2"))
                icon = "i2p";
            else
                icon = "compress";
        } else if (mime.equals("application/x-rar-compressed")) {
            icon = "rar";
        } else if (mime.equals("application/x-gtar") || mime.equals("application/x-tar") || plc.endsWith(".txz")) {
            icon = "tar";
        } else if (mime.equals("application/x-xz") || mime.equals("application/compress") ||
                   mime.equals("application/gzip") || mime.equals("application/x-7z-compressed") ||
                   mime.equals("application/x-bzip2")) {
            icon = "compress";
        } else if (plc.endsWith(".bin"))
            icon = "app";
        else if (plc.endsWith(".bat") || plc.endsWith(".dll"))
            icon = "windows";
        else if (plc.endsWith(".dmg"))
            icon = "apple";
        else if (plc.endsWith(".iso") || plc.endsWith(".nrg"))
            icon = "cd";
        else if (plc.contains(".css.") || plc.endsWith(".css") || plc.endsWith(".js") ||
                 plc.endsWith(".cgi") || plc.endsWith(".pl") || plc.endsWith(".py") ||
                 plc.endsWith(".php") || plc.endsWith(".h") || plc.endsWith(".cpp") ||
                 plc.endsWith(".json"))
            icon = "code";
        else if (plc.endsWith(".md5") || plc.contains("shasum"))
            icon = "hash";
        else if (mime.equals("application/x-bittorrent"))
            icon = "magnet";
        else
            icon = "generic";
        return icon;
    }

    /**
     *  Icon file in the .war. Always 16x16.
     *
     *  @param icon name without the ".png"
     *  @since 0.7.14
     */
    private String toImg(String icon) {
        return toImg(icon, "");
    }

    /**
     *  Icon file in the .war. Always 16x16.
     *
     *  @param icon name without the ".png"
     *  @since 0.8.2
     */
    private String toImg(String icon, String altText) {
        return "<img alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"" + _contextPath + WARBASE + "icons/" + icon + ".png\">";
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
        return "<img class=\"" + classText + "\" alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"" + _contextPath + WARBASE + "icons/" + icon + ".png\" title=\"" + titleText + "\">";
    }

    /**
     *  Icon file (svg) in the .war. Always 16x16.
     *
     *  @param icon name without the ".svg"
     *  @since 0.9.51+
     */
    private String toSVG(String icon) {
        return toSVG(icon, "");
    }

    /**
     *  Icon file (svg) in the .war. Always 16x16.
     *
     *  @param icon name without the ".svg"
     *  @since 0.8.2
     */
    private String toSVG(String icon, String altText) {
        return "<img alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"" + _contextPath + WARBASE + "icons/" + icon + ".svg\">";
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
        return "<img alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"" + _contextPath +
                WARBASE + "icons/" + icon + ".svg\" title=\"" + titleText + "\">";
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
        return "<span class=\"tooltipped\" data-tooltip=\"" + titleText + "\"><img alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"" + _contextPath +
                WARBASE + "icons/" + icon + ".svg\"></span>";
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
        return "<img class=\"" + classText + "\" alt=\"" + altText + "\" height=\"16\" width=\"16\" src=\"" + _contextPath +
                WARBASE + "icons/" + icon + ".svg\" title=\"" + titleText + "\">";
    }

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @since 0.9.16
     */
    private String toThemeImg(String image) {
        return toThemeImg(image, "", "");
    }

    /**
     *  Image file in the theme.
     *
     *  @param image name without the ".png"
     *  @since 0.9.16
     */
    private void toThemeImg(StringBuilder buf, String image) {
        toThemeImg(buf, image, "", "");
    }

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
        buf.append("<img alt=\"").append(altText).append("\" src=\"").append(_imgPath).append(image).append(".png\"");
        if (titleText.length() > 0)
            buf.append(" title=\"").append(titleText).append('"');
        buf.append('>');
    }

    /**
     *  SVG image file in the .war
     *
     *  @param image name without the ".svg"
     *  @since 0.9.51+
     */
    private void toSVG(StringBuilder buf, String image) {
        toSVG(buf, image, "", "");
    }

    /**
     *  SVG Image file in the .war
     *
     *  @param image name without the ".svg"
     *  @param altText non-null
     *  @param titleText non-null
     *  @since 0.9.51 (I2P+)
     */
    private void toSVG(StringBuilder buf, String image, String altText, String titleText) {
        buf.append("<img alt=\"").append(altText).append("\" src=\"").append(_contextPath).append(WARBASE)
           .append("icons/").append(image).append(".svg\"");
        if (titleText.length() > 0)
            buf.append(" title=\"").append(titleText).append('"');
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
        buf.append("<img alt=\"").append(altText).append("\" src=\"").append(_imgPath).append(image).append(".svg\"");
        if (titleText.length() > 0)
            buf.append(" title=\"").append(titleText).append('"');
        buf.append('>');
    }

    /** @since 0.8.1 */
    private void savePriorities(Snark snark, Map<String, String[]> postParams) {
        Storage storage = snark.getStorage();
        if (storage == null)
            return;
        for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("pri.")) {
                try {
                    int fileIndex = Integer.parseInt(key.substring(4));
                    String val = entry.getValue()[0];   // jetty arrays
                    int pri = Integer.parseInt(val);
                    storage.setPriority(fileIndex, pri);
                    //System.err.println("Priority now " + pri + " for " + file);
                } catch (Throwable t) { t.printStackTrace(); }
            }
        }
        if (postParams.get("setInOrderEnabled") != null)
            storage.setInOrder(postParams.get("enableInOrder") != null);
        snark.updatePiecePriorities();
        _manager.saveTorrentStatus(snark);
    }

    /** @since 0.9.31 */
    private void saveComments(Snark snark, Map<String, String[]> postParams) {
        String[] a = postParams.get("myRating");
        String r = (a != null) ? a[0] : null;
        a = postParams.get("nofilter_newComment");
        String c = (a != null) ? a[0] : null;
        if ((r == null || r.equals("0")) && (c == null || c.length() == 0))
            return;
        int rat = 0;
        try {
            rat = Integer.parseInt(r);
        } catch (NumberFormatException nfe) {}
        Comment com = new Comment(c, _manager.util().getCommentsName(), rat);
        boolean changed = snark.addComments(Collections.singletonList(com));
        if (!changed)
            _log.warn("Add of comment ID " + com.getID() + " UNSUCCESSFUL");
    }

    /** @since 0.9.31 */
    private void deleteComments(Snark snark, Map<String, String[]> postParams) {
        CommentSet cs = snark.getComments();
        if (cs == null)
            return;
        synchronized(cs) {
            for (Map.Entry<String, String[]> entry : postParams.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("cdelete.")) {
                    try {
                        int id = Integer.parseInt(key.substring(8));
                        boolean changed = cs.remove(id);
                        if (!changed)
                            _log.warn("Delete of comment ID " + id + " UNSUCCESSFUL");
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
        if (meta == null)
            return;
        buf.append("<div id=\"editSection\" class=\"mainsection\">\n" +
                   "<input hidden class=\"toggle_input\" id=\"toggle_torrentedit\" type=\"checkbox\">" +
                   "<label id=\"tab_torrentedit\" class=\"toggleview\" for=\"toggle_torrentedit\"><span class=\"tab_label\">");
        buf.append(_t("Edit Torrent"))
           .append("</span></label><hr>\n")
           .append("<table id=\"torrentEdit\">\n");
        boolean isRunning = !snark.isStopped();
        String announce = meta.getAnnounce();
        if (announce == null)
            announce = snark.getTrackerURL();
        if (announce != null) {
            // strip non-i2p trackers
            if (!isI2PTracker(announce))
                announce = null;
        }
        List<List<String>> alist = meta.getAnnounceList();
        Set<String> annlist = new TreeSet<String>();
        if (alist != null && !alist.isEmpty()) {
            // strip non-i2p trackers
            for (List<String> alist2 : alist) {
                for (String s : alist2) {
                    if (isI2PTracker(s))
                        annlist.add(s);
                }
            }
        }
        if (announce != null)
            annlist.add(announce);
        if (!annlist.isEmpty()) {
            buf.append("<tr class=\"header\"><th>").append(_t("Active Trackers")).append("</th><th>").append(_t("Announce URL")).append("</th><th>")
               .append(_t("Primary")).append("</th><th id=\"remove\">").append(_t("Delete")).append("</th></tr>\n");
            for (String s : annlist) {
                int hc = s.hashCode();
                buf.append("<tr><td>");
                s = DataHelper.stripHTML(s);
                buf.append("<span class=\"info_tracker\">").append(getShortTrackerLink(s, snark.getInfoHash())).append("</span>")
                   .append("</td><td>").append(s).append("</td><td>")
                   .append("<input type=\"radio\" class=\"optbox\" name=\"primary\" ");
                if (s.equals(announce))
                    buf.append("checked=\"checked\" ");
                buf.append("value=\"").append(hc).append("\"");
                if (isRunning)
                    buf.append(" disabled=\"disabled\"");
                buf.append("></td><td>")
                   .append("<input type=\"checkbox\" class=\"optbox\" name=\"removeTracker-")
                   .append(hc).append("\" title=\"").append(_t("Mark for deletion")).append("\"");
                if (isRunning)
                    buf.append(" disabled=\"disabled\"");
                buf.append("></td></tr>\n");
            }
        }

        List<Tracker> newTrackers = _manager.getSortedTrackers();
        for (Iterator<Tracker> iter = newTrackers.iterator(); iter.hasNext(); ) {
            Tracker t = iter.next();
            String announceURL = t.announceURL.replace("&#61;", "=");
            if (announceURL.equals(announce) || annlist.contains(announceURL))
                iter.remove();
        }
        if (!newTrackers.isEmpty()) {
            buf.append("<tr class=\"header\"><th>").append(_t("Add Tracker")).append("</th><th>");
            if (announce == null)
                buf.append(_t("Announce URL")).append("</th><th>").append(_t("Primary"));
            else
                buf.append("</th><th>");
            buf.append("</th><th id=\"add\">").append("Add").append("</th></tr>\n");
            for (Tracker t : newTrackers) {
                String name = t.name;
                int hc = t.announceURL.hashCode();
                String announceURL = t.announceURL.replace("&#61;", "=");
                buf.append("<tr><td><span class=\"info_tracker\">").append(name).append("</span></td><td>")
                   .append(announceURL).append("</td><td>")
                   .append("<input type=\"radio\" class=\"optbox\" name=\"primary\" value=\"")
                   .append(hc).append("\"");
                if (isRunning)
                    buf.append(" disabled=\"disabled\"");
                buf.append("></td><td>")
                   .append("<input type=\"checkbox\" class=\"optbox\" id=\"").append(name).append("\" name=\"addTracker-")
                   .append(hc).append("\" title=\"").append(_t("Add tracker")).append("\"");
                if (isRunning)
                    buf.append(" disabled=\"disabled\"");
                buf.append("></td></tr>\n");
            }
        }

        String com = meta.getComment();
        if (com == null) {
            com = "";
        } else if (com.length() > 0) {
            com = DataHelper.escapeHTML(com).replace("\r\n", "<br>").replace("\n", "<br>");
        }
        buf.append("<tr class=\"header\"><th colspan=\"4\">").append(_t("Torrent Comment")).append("</th></tr>\n");
        buf.append("<tr><td colspan=\"4\" id=\"addCommentText\"><textarea name=\"nofilter_newTorrentComment\" cols=\"88\" rows=\"4\"");
        if (isRunning)
            buf.append(" readonly");
        buf.append(">").append(com).append("</textarea></td>").append("</tr>\n");

/*
        String cb = meta.getCreatedBy();
        if (cb == null) {
            cb = "";
        } else if (cb.length() > 0) {
            cb = DataHelper.escapeHTML(cb);
        }
        buf.append("<tr><td>");
        toThemeImg(buf, "details");
        buf.append("</td><td><b>")
           .append(_t("Created By")).append("</b></td>");
        buf.append("<td id=\"editTorrentCreatedBy\"><input type=\"text\" name=\"nofilter_newTorrentCreatedBy\" cols=\"44\" rows=\"1\" value=\"")
           .append(cb).append("\"></td></tr>");
*/
        if (isRunning) {
            buf.append("<tfoot><tr><td colspan=\"4\"><span id=\"stopfirst\">")
               .append(_t("Torrent must be stopped in order to edit"))
               .append("</span></td></tr></tfoot>\n</table>\n</div>\n");
            return;
        } else {
            buf.append("<tfoot><tr><td colspan=\"4\">")
               .append("<input type=\"submit\" name=\"editTorrent\" value=\"")
               .append(_t("Save Changes"))
               .append("\" class=\"accept\"></td></tr></tfoot>\n")
               .append("</table>\n</div>\n");
        }
    }

    /**
     *  @since 0.9.53
     */
    private void saveTorrentEdit(Snark snark, Map<String, String[]> postParams) {
        if (!snark.isStopped()) {
            // shouldn't happen
            _manager.addMessage(_t("Torrent must be stopped"));
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
                try {
                    toAdd.add(Integer.parseInt(key.substring(11)));
                } catch (NumberFormatException nfe) {}
            } else if (key.startsWith("removeTracker-")) {
                try {
                    toDel.add(Integer.parseInt(key.substring(14)));
                } catch (NumberFormatException nfe) {}
            } else if (key.equals("primary")) {
                try {
                    primary = Integer.parseInt(val);
                } catch (NumberFormatException nfe) {}
            } else if (key.equals("nofilter_newTorrentComment")) {
                newComment = val.trim();
            } else if (key.equals("nofilter_newTorrentCreatedBy")) {
                newCreatedBy = val.trim();
            }
        }
        MetaInfo meta = snark.getMetaInfo();
        if (meta == null) {
            // shouldn't happen
            _manager.addMessage("Can't edit magnet");
            return;
        }
        String oldPrimary = meta.getAnnounce();
        String oldComment = meta.getComment();
        if (oldComment == null)
            oldComment = "";
        String oldCreatedBy = meta.getCreatedBy();
        if (oldCreatedBy == null)
            oldCreatedBy = "";
        if (toAdd.isEmpty() && toDel.isEmpty() &&
            (primary == null || primary.equals(oldPrimary)) &&
            oldComment.equals(newComment) &&
            oldCreatedBy.equals(newCreatedBy)) {
            _manager.addMessage("No changes to torrent, not saved");
            return;
        }
        List<List<String>> alist = meta.getAnnounceList();
        Set<String> annlist = new TreeSet<String>();
        if (alist != null && !alist.isEmpty()) {
            // strip non-i2p trackers
            for (List<String> alist2 : alist) {
                for (String s : alist2) {
                    if (isI2PTracker(s))
                        annlist.add(s);
                }
            }
        }
        if (oldPrimary != null)
            annlist.add(oldPrimary);
        List<Tracker> newTrackers = _manager.getSortedTrackers();
        for (Integer i : toDel) {
            int hc = i.intValue();
            for (Iterator<String> iter = annlist.iterator(); iter.hasNext(); ) {
                String s = iter.next();
                if (s.hashCode() == hc)
                    iter.remove();
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
            if (!aalist.contains(thePrimary))
                thePrimary = aalist.get(0);
        }
        if (newComment.equals(""))
            newComment = null;
        if (newCreatedBy.equals(""))
            newCreatedBy = null;
        MetaInfo newMeta = new MetaInfo(thePrimary, meta.getName(), null, meta.getFiles(), meta.getLengths(),
                                        meta.getPieceLength(0), meta.getPieceHashes(), meta.getTotalLength(), meta.isPrivate(),
                                        newAnnList, newCreatedBy, meta.getWebSeedURLs(), newComment);
        if (!DataHelper.eq(meta.getInfoHash(), newMeta.getInfoHash())) {
            // shouldn't happen
            _manager.addMessage("Torrent edit failed, infohash mismatch");
            return;
        }
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
            try { if (out != null) out.close(); } catch (IOException ioe2) {}
            _manager.addMessage("Save edit changes failed: " + ioe);
            return;
        } finally {
            f.delete();
        }
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
        } catch (IOException ioe) {
            return false;
        }
        if (a.equals(b))
            return true;
        if (!a.isDirectory())
            return false;
        // easy case
        if (!b.getPath().startsWith(a.getPath()))
            return false;
        // dir by dir
        while (!a.equals(b)) {
            b = b.getParentFile();
            if (b == null)
                return false;
        }
        return true;
    }
}

