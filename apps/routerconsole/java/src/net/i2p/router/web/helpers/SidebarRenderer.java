package net.i2p.router.web.helpers;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.text.Collator;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import net.i2p.app.ClientAppManager;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.news.NewsEntry;
import net.i2p.router.news.NewsManager;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.ContextHelper;
import net.i2p.router.web.GraphGenerator;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NavHelper;
import net.i2p.router.web.NewsHelper;
import net.i2p.util.PortMapper;
import net.i2p.util.SystemVersion;

/**
 * Helper for sidebar rendering and navigation.
 * @since 0.9.33
 */
class SidebarRenderer {

    private static final Collator COLLATOR = Collator.getInstance();

    static final String ALL_SECTIONS[] =
        {"Advanced", "AdvancedRouterInfo", "Bandwidth", "BandwidthGraph", "Clock", "Congestion", "CPUBar",
         "Destinations", "FirewallAndReseedStatus", "HelpAndFAQ", "I2PInternals", "I2PServices", "MemoryBar",
         "NetworkReachability", "NewsHeadings", "Peers", "RestartStatus", "RouterInfo", "ShortRouterInfo",
         "Tunnels", "TunnelStatus", "UpdateStatus"};
    static final Map<String, String> SECTION_NAMES;

    static {
        Map<String, String> aMap = new HashMap<String, String>();
        aMap.put("AdvancedRouterInfo", _x("Router Information (advanced)"));
        aMap.put("Advanced", _x("Advanced Links"));
        aMap.put("BandwidthGraph", _x("Bandwidth Graph"));
        aMap.put("Bandwidth", _x("Bandwidth"));
        aMap.put("Clock", _x("UTC Clock"));
        aMap.put("Congestion", _x("Congestion"));
        aMap.put("CPUBar", _x("CPU Load"));
        aMap.put("Destinations", _x("Service Tunnels"));
        aMap.put("FirewallAndReseedStatus", _x("Firewall &amp; Reseed Status"));
        aMap.put("HelpAndFAQ", _x("Help &amp; FAQ"));
        aMap.put("I2PInternals", _x("I2P Internals"));
        aMap.put("I2PServices", _x("I2P Services"));
        aMap.put("MemoryBar", _x("Memory Usage Bar"));
        aMap.put("NetworkReachability", _x("Network Reachability"));
        aMap.put("NewsHeadings", _x("News &amp; Updates"));
        aMap.put("Peers", _x("Peers"));
        aMap.put("RestartStatus", _x("Restart Status"));
        aMap.put("RouterInfo", _x("Router Information"));
        aMap.put("ShortRouterInfo", _x("Router Information (brief)"));
        aMap.put("TunnelStatus", _x("Tunnel Status"));
        aMap.put("Tunnels", _x("Tunnels"));
        aMap.put("UpdateStatus", _x("Update Status"));
        SECTION_NAMES = Collections.unmodifiableMap(aMap);
    }

    private final RouterContext _context;
    private final SidebarHelper _helper;
    private static final String PROP_ADVANCED = "routerconsole.advanced";
    private static final String PROP_UNIFIED_SIDEBAR = "routerconsole.unifiedSidebar";
    private String toggleId = "";
    private String getToggle() {return "<input type=checkbox id=" + toggleId + " class=\"toggleSection script\" checked hidden>";}

    public SidebarRenderer(RouterContext context, SidebarHelper helper) {
        _context = context;
        _helper = helper;
    }

    public boolean isAdvanced() {return _context.getBooleanProperty(PROP_ADVANCED);}
    public boolean unifiedSidebar() {return _context.getBooleanProperty(PROP_UNIFIED_SIDEBAR);}
    public boolean floodfillEnabled() {return _context.netDb().floodfillEnabled();}

    /**
     *  Note - Ensure all links in here are absolute, as the summary bar may be displayed
     *  on lower-level directory errors.
     */
    public void renderSummaryHTML(Writer out) throws IOException {
        String requestURI = _helper.getRequestURI();
        boolean isXHR = requestURI.contains("/xhr1");
        String page = requestURI.replace("/", "").replace(".jsp", "");
        List<String> sections = _helper.getSummaryBarSections(page);
        boolean isUnified = unifiedSidebar();
        boolean isHelp = requestURI.contains("help");
        boolean isHome = requestURI.contains("home");
        boolean isSitemap = requestURI.contains("sitemap");

        // Regardless of section order, we want to process the restart buttons first,
        // so other sections reflect the current restart state
        String restartStatus = sections.contains("RestartStatus") ? renderRestartStatusHTML() : null;

        StringBuilder buf = new StringBuilder(1024);
        for (String section : sections) {
            buf.setLength(0);

            buf.append("<hr>\n");
            if ("HelpAndFAQ".equals(section) && (isUnified || (!isSitemap && !isHelp)))
                buf.append(renderHelpAndFAQHTML());
            else if ("I2PServices".equals(section))
                buf.append(renderI2PServicesHTML());
            else if ("I2PInternals".equals(section) && (isUnified || !isSitemap))
                buf.append(renderI2PInternalsHTML());
            else if ("Advanced".equals(section) && (isUnified || !isSitemap))
                buf.append(renderAdvancedHTML());
            else if ("RouterInfo".equals(section) || "General".equals(section)) // Backwards-compatibility
                buf.append(renderRouterInfoHTML());
            else if ("ShortRouterInfo".equals(section) || "ShortGeneral".equals(section)) // Backwards-compatibility
                buf.append(renderShortRouterInfoHTML());
            else if ("AdvancedRouterInfo".equals(section))
                buf.append(renderAdvancedRouterInfoHTML());
            else if ("MemoryBar".equals(section))
                buf.append(renderMemoryBarHTML());
            else if ("CPUBar".equals(section))
                buf.append(renderCPUBarHTML());
            else if ("NetworkReachability".equals(section))
                buf.append(renderNetworkReachabilityHTML());
            else if ("UpdateStatus".equals(section))
                buf.append(renderUpdateStatusHTML());
            else if ("RestartStatus".equals(section))
                buf.append(restartStatus); // prerendered above
            else if ("Peers".equals(section))
                buf.append(renderPeersHTML());
            else if ("FirewallAndReseedStatus".equals(section))
                buf.append(renderFirewallAndReseedStatusHTML());
            else if ("Bandwidth".equals(section))
                buf.append(renderBandwidthHTML());
            else if ("BandwidthGraph".equals(section))
                buf.append(renderBandwidthGraphHTML());
            else if ("Tunnels".equals(section))
                buf.append(renderTunnelsHTML());
            else if ("Congestion".equals(section))
                buf.append(renderCongestionHTML());
            else if ("TunnelStatus".equals(section))
                buf.append(renderTunnelStatusHTML());
            else if ("Destinations".equals(section))
                buf.append(renderDestinationsHTML());
            else if ("NewsHeadings".equals(section) && (isUnified || isHome))
                buf.append(renderNewsHeadingsHTML());
            else if ("Clock".equals(section))
                buf.append(renderClockHTML());

            // Only output section if there's more than the <hr> to print
            if (buf.length() > 5) {out.append(buf);}
        }
    }

    public String renderHelpAndFAQHTML() {
        StringBuilder buf = new StringBuilder(512);
        toggleId = "toggle_sb_help";
        buf.append("<h3 id=helpfaq><a href=/help/ target=_top title=\"")
           .append(_t("I2P Router Help &amp; FAQ"))
           .append("\">")
           .append(_t("Help &amp; FAQ"))
           .append("</a>")
           .append(getToggle())
           .append("</h3><hr class=b>\n<table id=sb_help>\n<tr><td>");

        // Store all items in map so they are sorted by translated name, then output
        Map<String, String> svcs = new TreeMap<String, String>(COLLATOR);
        StringBuilder rbuf = new StringBuilder(128);

        String tx = _t("Advanced Settings");
        rbuf.append("<a href=/help/advancedsettings target=_top title=\"")
            .append(_t("A guide to some of the less-used configuration settings"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Changelog");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/changelog target=_top title=\"")
            .append(_t("Recent development changes to the router"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Configuration");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/configuration target=_top title=\"")
            .append(_t("An introduction to configuring your router"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("FAQ");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/faq target=_top title=\"")
            .append(_t("A shortened version of the official Frequently Asked Questions"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("New Users");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/newusers target=_top title=\"")
            .append(_t("A gentle introduction to I2P"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Reseeding");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/reseed target=_top title=\"")
            .append(_t("A guide to reseeding the router"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Troubleshoot");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/troubleshoot target=_top title=\"")
            .append(_t("Troubleshooting &amp; Further Assistance"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("UI");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/ui target=_top title=\"")
            .append(_t("Information about the sidebar and network status messages"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Webhosting");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/webhosting target=_top title=\"")
            .append(_t("An introduction to hosting websites on the I2P network"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        for (String row : svcs.values()) {buf.append(row);}
        buf.append("</td></tr>\n</table>\n");
        return buf.toString();
    }

    public String renderI2PServicesHTML() {
        boolean embedApps = _context.getBooleanProperty(CSSHelper.PROP_EMBED_APPS);

        // Store all items in map so they are sorted by translated name, add the plugins, then output
        Map<String, String> svcs = new TreeMap<String, String>(COLLATOR);
        StringBuilder rbuf = new StringBuilder(128);
        PortMapper pm = _context.portMapper();
        if (pm.isRegistered(PortMapper.SVC_SUSIDNS)) {
            String tx = _t("Addressbook");
            rbuf.append("<a href=\"/dns?book=router&amp;filter=latest\" target=_top title=\"")
                .append(_t("Manage your I2P hosts file here (I2P domain name resolution)"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n<a class=sb_icon target=_top href=\"/dns?book=router&amp;filter=latest\" title=\"")
                .append(tx)
                .append("\" hidden><span><img src=/themes/console/images/addressbook.svg> <span id=newHosts class=\"badge volatile\" title=\"")
                .append(_t("New hosts in last 24 hours"))
                .append("\"></span></span></a>");
            svcs.put(tx, rbuf.toString());
        }

        if (pm.isRegistered(PortMapper.SVC_SUSIMAIL)) {
            String tx = _t("I2PMail");
            rbuf.setLength(0);
            rbuf.append("<a href=/webmail ");
            if (embedApps) {rbuf.append("target=_top ");}
            else {rbuf.append("target=_blank ");}
            rbuf.append("title=\"").append(_t("Anonymous webmail client"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n<a class=sb_icon target=_top href=/webmail title=\"")
                .append(tx)
                .append("\" hidden><span><img src=/themes/console/images/mail.svg></span></a>\n");
            svcs.put(tx, rbuf.toString());
        }

        if (pm.isRegistered(PortMapper.SVC_JSONRPC)) {
            String tx = _t("I2PControl");
            rbuf.setLength(0);
            rbuf.append("<a href=/jsonrpc/ target=_blank title=\"")
                .append(_t("RPC Service"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n<a class=sb_icon target=_top href=/jsonrpc/ title=\"")
                .append(tx)
                .append("\" hidden><span><img src=/themes/console/images/i2cp.svg></span></a>\n");
            svcs.put(tx, rbuf.toString());
        }

        if (pm.isRegistered(PortMapper.SVC_I2PSNARK)) {
            String tx = _t("Torrents");
            rbuf.setLength(0);
            rbuf.append("<a href=/torrents ");
            if (embedApps) {rbuf.append("target=_top ");}
            else {rbuf.append("target=_blank ");}
            rbuf.append("title=\"")
                .append(_t("Built-in anonymous BitTorrent Client"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n<a class=sb_icon target=_top href=/torrents title=\"")
                .append(tx)
                .append("\" hidden><span><img src=/themes/console/images/snark.svg></span></a>\n");
            svcs.put(tx, rbuf.toString());
        }

        if (_helper.isI2PTunnelRunning()) {
            String tx = _t("Tunnel Manager");
            rbuf.setLength(0);
            rbuf.append("<a href=/tunnelmanager target=_top title=\"")
                .append(_t("Manage I2P client and server tunnels to access or provide network services"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n<a class=sb_icon target=_top href=/tunnelmanager title=\"")
                .append(tx)
                .append("\" hidden><span><img src=/themes/console/images/tunnelmanager.svg></span></a>\n");
            svcs.put(tx, rbuf.toString());
        }

        List<String> urls = pm.getEepsiteURLs();
        if (urls != null) {
            String tx = _t("Web Server");
            String txtt = _t("Local Web Server");
            int sz = urls.size();
            if (sz > 1) {Collections.sort(urls);}
            for (int i = 0; i < sz; i++) {
                String url = urls.get(i);
                String txp = sz > 1 ? tx + ' ' + (i + 1) : tx;
                rbuf.setLength(0);
                rbuf.append("<a href=\"")
                    .append(url)
                    .append("\" target=_blank title=\"")
                    .append(txtt)
                    .append("\">")
                    .append(nbsp(txp))
                    .append("</a>\n<a class=sb_icon target=_blank href=\"")
                    .append(url)
                    .append("\" title=\"")
                    .append(tx)
                    .append("\" hidden><span><img src=/themes/console/images/webserver.svg></span></a>\n");
                svcs.put(txp, rbuf.toString());
            }
        }

        Map<String, String> apps = NavHelper.getInstance(_context).getClientAppLinks();
        if (apps != null) {svcs.putAll(apps);}
        if (!svcs.isEmpty()) {
            StringBuilder buf = new StringBuilder(128 * svcs.size());
            toggleId = "toggle_sb_services";

            buf.append("<h3><a href=/configwebapps target=_top title=\"")
               .append(_t("Configure startup of clients and webapps (services); manually start dormant services"))
               .append("\">")
               .append(_t("Services"))
               .append("</a>")
               .append(getToggle())
               .append("</h3>\n<hr class=b>\n<table id=sb_services class=collapse><tbody>\n<tr><td class=volatile>");
            for (String row : svcs.values()) {buf.append(row);}
            buf.append("</td></tr>\n<tr id=newHostsList class=script><td></td></tr>\n</tbody></table>\n");
            return buf.toString();
        } else {return "";}
    }

    /**
     *  @return null if none
     *  @since 0.9.43 split out from above, used by HomeHelper, fixed for IPv6
     */
    static String getEepsiteURL(PortMapper pm) {
        int port = pm.getPort(PortMapper.SVC_EEPSITE);
        int sslPort = pm.getPort(PortMapper.SVC_HTTPS_EEPSITE);
        if (port <= 0 && sslPort <= 0) {return null;}
        String svc;
        StringBuilder buf = new StringBuilder(32);
        if (sslPort > 0) {
            buf.append("https://");
            svc = PortMapper.SVC_HTTPS_EEPSITE;
            port = sslPort;
        } else {
            buf.append("http://");
            svc = PortMapper.SVC_EEPSITE;
        }
        String host = pm.getActualHost(svc, "127.0.0.1");
        if (host.contains(":")) {buf.append('[');}
        buf.append(host);
        if (host.contains(":")) {buf.append(']');}
        buf.append(':').append(port).append('/');
        return buf.toString();
    }

    public String renderClockHTML() {
        StringBuilder buf = new StringBuilder(128);
        String FORMAT = "HH:mm:ss";
        DateFormat dfmt = new SimpleDateFormat(FORMAT, Locale.UK);
        TimeZone utc = TimeZone.getTimeZone("GMT");
        dfmt.setTimeZone(utc);
        buf.append("<div id=clock class=volatile>").append(dfmt.format(new Date())).append("</div>");
        return buf.toString();
    }

    public String renderI2PInternalsHTML() {
        StringBuilder buf = new StringBuilder(512);
        toggleId = "toggle_sb_internals";
        buf.append("<h3><a href=/sitemap target=_top title=\"")
           .append(_t("I2P+ Router Sitemap"))
           .append("\">")
           .append(_t("Internals"))
           .append("</a>")
           .append(getToggle())
           .append("</h3>\n<hr class=b>\n<table id=sb_internals class=collapse>\n<tr><td>");

        // Store all items in map so they are sorted by translated name, then output
        Map<String, String> svcs = new TreeMap<String, String>(COLLATOR);
        StringBuilder rbuf = new StringBuilder(128);

        String tx = _t("Certs");
        rbuf.append("<a target=_top title=\"")
            .append(_t("Review active encryption certificates used in console"))
            .append("\" href=/certs>")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Events");
        rbuf.setLength(0);
        // 7 days
        rbuf.append("<a href=\"/events?from=604800\" target=_top title=\"")
            .append(_t("View historical log of router events"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        if (!GraphGenerator.isDisabled(_context)) {
            tx = _t("Graphs");
            rbuf.setLength(0);
            rbuf.append("<a href=/graphs target=_top title=\"")
                .append(_t("Graph router performance"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n")
                .append("<a class=sb_icon target=_top href=/graphs title=\"")
                .append(tx)
                .append("\" hidden><span><img src=/themes/console/images/graphs.svg></span></a>\n");
            svcs.put(tx, rbuf.toString());
        }

        tx = _t("Help");
        rbuf.setLength(0);
        rbuf.append("<a href=/help/ target=_top title=\"")
            .append(_t("Router Help and FAQ"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Info");
        rbuf.setLength(0);
        rbuf.append("<a href=/info target=_top title=\"")
            .append(_t("Router information including transport addresses and UPnP scan"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Jobs");
        rbuf.setLength(0);
        rbuf.append("<a href=/jobs target=_top title=\"")
            .append(_t("Show the router's workload, and how it's performing"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=/jobs title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/jobs.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Logs");
        rbuf.setLength(0);
        LogsHelper logsHelper = new LogsHelper();
        logsHelper.setContext(this._context);
        int errorCount = logsHelper.getCriticalLogCount();
        boolean haveErrors = errorCount > 0;
        String errorTooltip = haveErrors ? " / " + _t("Errors") + ":" + errorCount : "";
        rbuf.append("<a class=volatile href=/").append(haveErrors ? "error" : "router")
            .append("logs target=_top title=\"")
            .append(_t("Health Report"))
            .append(errorTooltip)
            .append("\">")
            .append(nbsp(tx)).append(haveErrors ? "*" : "")
            .append("</a>\n<a class=\"sb_icon volatile\" target=_top href=/")
            .append(haveErrors ? "error" : "router")
            .append("logs title=\"")
            .append(tx).append(errorTooltip)
            .append("\" hidden><span><img src=/themes/console/images/logs")
            .append(haveErrors ? "Error" : "").append(".svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("NetDb");
        rbuf.setLength(0);
        rbuf.append("<a href=/netdb target=_top title=\"")
            .append(_t("Show list of all known I2P routers"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n")
            .append("<a class=sb_icon target=_top href=/netdb title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/globe.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Peers");
        rbuf.setLength(0);
        rbuf.append("<a href=/peers target=_top title=\"")
            .append(_t("Show all current peer connections"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=/peers title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/peers.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Profiles");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/profiles?f=1\" target=_top title=\"")
            .append(_t("Show recent peer performance profiles"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=\"/profiles?f=1\" title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/profile.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Tunnels");
        rbuf.setLength(0);
        rbuf.append("<a href=/tunnels target=_top title=\"")
            .append(_t("View existing tunnels and tunnel build status"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=/tunnels title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/hardhat.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        for (String row : svcs.values()) {buf.append(row);}
        buf.append("</td></tr>\n</table>\n");
        return buf.toString();
    }

    public String renderAdvancedHTML() {
        StringBuilder buf = new StringBuilder(512);
        toggleId = "toggle_sb_advanced";

        buf.append("<h3 id=advanced><a title=\"")
           .append(_t("Advanced Configuration"))
           .append("\" href=/configadvanced target=_top>")
           .append(_t("Advanced"))
           .append("</a>")
           .append(getToggle())
           .append("</h3>\n<hr class=b>\n<table id=sb_advanced class=collapse>\n<tr><td>");

        // Store all items in map so they are sorted by translated name, then output
        Map<String, String> svcs = new TreeMap<String, String>(COLLATOR);
        StringBuilder rbuf = new StringBuilder(128);

        File javadoc = new File(_context.getBaseDir(), "docs/javadoc/index.html");
        if (javadoc.exists()) {
            String tx = _t("API");
            rbuf.append("<a title=\"")
                .append(_t("Documentation for the I2P API"))
                .append("\" href=/javadoc/index.html target=_blank>")
                .append(nbsp(tx))
                .append("</a>\n<a class=sb_icon target=_blank href=/javadoc/index.html title=\"")
                .append(tx)
                .append("\" hidden><span><img src=/themes/console/images/help.svg></span></a>\n");
            svcs.put(tx, rbuf.toString());
        }

        String tx = _t("Debug");
        rbuf.setLength(0);
        rbuf.append("<a title=\"")
            .append(_t("View router debug information"))
            .append("\" href=/debug target=_top>")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=/debug title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/bug.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Jars");
        rbuf.setLength(0);
        rbuf.append("<a title=\"")
            .append(_t("Review extended info about installed .jar and .war files"))
            .append("\" href=/jars target=_top>")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=/jars title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/package.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Lookup");
        rbuf.setLength(0);
        rbuf.append("<a title=\"")
            .append(_t("Network database search tool"))
            .append("\" href=\"/netdb?f=4\" target=_top>")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=\"/netdb?f=4\" title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/searchnetdb.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Stats");
        rbuf.setLength(0);
        rbuf.append("<a href=/stats target=_top title=\"")
            .append(_t("Textual router performance statistics"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=/stats title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/stats.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Sybils");
        rbuf.setLength(0);
        rbuf.append("<a title=\"")
            .append(_t("Review possible sybils in network database"))
            .append("\" href=\"/netdb?f=3\" target=_top>")
            .append(nbsp(tx))
            .append("</a>\n<a class=sb_icon target=_top href=\"/netdb?f=3\" title=\"")
            .append(tx)
            .append("\" hidden><span><img src=/themes/console/images/sybil.svg></span></a>\n");
        svcs.put(tx, rbuf.toString());

        for (String row : svcs.values()) {buf.append(row);}
        buf.append("</td></tr>\n</table>");
        return buf.toString();
    }

    public String renderRouterInfoHTML() {
        if (_helper == null) {return "";}
        StringBuilder buf = new StringBuilder(512);
        toggleId = "toggle_sb_general";

        buf.append("<h3><a href=/info target=_top title=\"")
           .append(_t("Your Local Identity [{0}] is your unique I2P router identity, similar to an IP address but tailored to I2P. ", _helper.getIdent()))
           .append(_t("Never disclose this to anyone, as it can reveal your real world IP."))
           .append("\">")
           .append(_t("Router Info"))
           .append(" <span class=\"badge volatile\" hidden title=\"")
           .append(_t("How long we've been running for this session"))
           .append("\">")
           .append(_t("Up"))
           .append(' ')
           .append(_helper.getUptime())
           .append("</span></a>")
           .append(getToggle())
           .append("</h3>\n<hr class=b>\n<table id=sb_general><tr title=\"")
           .append(_t("The version of the I2P software we are running"))
           .append("\"><td><a href=/configupdate><b>")
           .append(_t("Version"))
           .append("</b></a></td><td class=digits><span>")
           .append(_helper.getVersion())
           .append("</span></td></tr>\n<tr title=\"")
           .append(_t("How long we've been running for this session"))
           .append("\"><td><b>")
           .append(_t("Uptime"))
           .append("</b></td><td class=digits><span class=volatile>")
           .append(_helper.getUptime())
           .append("</span></td></tr>\n</table>\n");
        return buf.toString();
    }

    public String renderShortRouterInfoHTML() {
        if (_helper == null) {return "";}
        StringBuilder buf = new StringBuilder(512);

        buf.append("<table id=sb_shortgeneral>\n<tr title=\"")
           .append(_t("The version of the I2P software we are running"))
           .append("\"><td><a href=\"/configupdate\"><b>")
           .append(_t("Version"))
           .append("</b></a></td><td class=digits><span>")
           .append(_helper.getVersion())
           .append("</span></td></tr>\n<tr title=\"")
           .append(_t("How long we've been running for this session"))
           .append("\"><td><b>")
           .append(_t("Uptime"))
           .append("</b></td><td class=digits><span class=volatile>")
           .append(_helper.getUptime())
           .append("</span></td></tr>\n</table>\n");
        return buf.toString();
    }

    /** @since 0.9.32 */
    public String renderAdvancedRouterInfoHTML() {
        if (_helper == null) {return "";}
        StringBuilder buf = new StringBuilder(512);
        toggleId = "toggle_sb_advancedgeneral";

        buf.append("<h3><a href=\"/info\" target=_top title=\"")
           .append(_t("Your Local Identity [{0}] is your unique I2P router identity, similar to an IP address but tailored to I2P. ", _helper.getIdent()))
           .append(_t("Never disclose this to anyone, as it can reveal your real world IP."))
           .append("\">")
           .append(_t("Router Info"))
           .append(" <span class=\"badge volatile\" hidden title=\"")
           .append(_t("How long we've been running for this session"))
           .append("\">")
           .append(_t("Up"))
           .append(" ").append(_helper.getUptime())
           .append("</span></a>")
           .append(getToggle())
           .append("</h3>\n<hr class=b>\n<table id=sb_advancedgeneral>\n<tr title=\"")
           .append(_t("The version of the I2P software we are running"))
           .append("\"><td><a href=/configupdate><b>")
           .append(_t("Version"))
           .append("</b></a></td><td class=digits><span>")
           .append(_helper.getVersion())
           .append("</span></td></tr>\n<tr title=\"")
           .append(_t("How long we've been running for this session"))
           .append("\"><td><b>")
           .append(_t("Uptime"))
           .append("</b></td><td class=digits><span class=volatile>")
           .append(_helper.getUptime())
           .append("</span></td></tr>\n");

           if (_context.clock().getOffset() != 0) {
               buf.append("<tr title=\"")
                  .append(_t("Difference between network-synced time and local time"))
                  .append("\"><td><b>")
                  .append(_t("Clock Skew"))
                  .append("</b></td><td class=\"digits");
               if (_context.clock().getOffset() > 1000 || _context.clock().getOffset() < -1000) {
                   buf.append(" warntext");
               }
               buf.append(" volatile\"><span>")
                  .append(DataHelper.formatDuration2(_context.clock().getOffset()))
                  .append("</span></td></tr>\n");
           } else {
               buf.append("<tr title=\"")
                  .append(_t("Difference between network-synced time and local time"))
                  .append("\" hidden><td><b>")
                  .append(_t("Clock Skew"))
                  .append("</b></td><td class=digits><span class=volatile>---</span></td></tr>\n");
           }

           String requestURI = _helper.getRequestURI();
           String page = requestURI.replace("/", "").replace(".jsp", "");
           List<String> sections = _helper.getSummaryBarSections(page);
           if (!sections.contains("MemoryBar")) {
               buf.append("<tr title=\"")
                  .append(_t("How much RAM I2P is using / total RAM available to I2P (excludes RAM allocated to the JVM)"))
                  .append("\">")
                  .append("<td><b>")
                  .append(_t("Memory"))
                  .append("</b></td><td class=digits><span class=volatile>")
                  .append(_helper.getMemory())
                  .append("</span></td></tr>\n");
           }
           buf.append("</table>\n");
        return buf.toString();
    }

    /** @since 0.9.57+ */
    public String renderMemoryBarHTML() {
        if (_helper == null) {return "";}
        return _helper.getMemoryBar();
    }

    /** @since 0.9.57+ */
    public String renderCPUBarHTML() {
        if (_helper == null) {return "";}
        return _helper.getCPUBar();
    }

    public String renderNetworkReachabilityHTML() {
        if (_helper == null) {return "";}
        StringBuilder buf = new StringBuilder(512);
        SidebarHelper.NetworkStateMessage reachability = _helper.getReachability();
        RouterContext ctx = ContextHelper.getContext(null);
        boolean shuttingDown = ConfigRestartBean.isShuttingDown(ctx);
        boolean restarting = ConfigRestartBean.isRestarting(ctx);
        long timeRemaining = ctx.router().getShutdownTimeRemaining();
        buf.append("<h4 id=sb_status><span id=sb_netstatus class=\"sb_netstatus volatile ");
        switch (reachability.getState()) {
            case VMCOMM:
                buf.append("vmcomm");
                break;
            case CLOCKSKEW:
                buf.append("clockskew");
                break;
            case ERROR:
                buf.append("error");
                break;
            case WARN:
                buf.append("warn");
                break;
            case HIDDEN:
                buf.append("hidden");
                break;
            case FIREWALLED:
                buf.append("firewalled");
                break;
            case RUNNING:
                buf.append("running");
                break;
            case TESTING:
            default:
                buf.append("testing");
        }
        if (floodfillEnabled()) {buf.append(" floodfill");}
        if (shuttingDown) {buf.append(" shuttingDown");}
        else if (restarting) {buf.append(" restarting");}
        buf.append("\"><span id=netstatus>")
           .append(_t("Status"))
           .append(": ")
           .append(reachability.getMessage())
           .append(floodfillEnabled() ? " <span id=ffenabled>(" + _t("Floodfill enabled") + ")</span>" : "")
           .append("</span>");
        if (floodfillEnabled() && !reachability.getMessage().contains(_t("Floodfill"))) {
            int lsCount = _context.netDb().getFloodfillLeases().size();
            if (lsCount > 0) {
            buf.append(" <a href=\"/netdb?l=1\"><span id=lsCount class=\"badge volatile\" title=\"")
               .append(_t("Total number of remote LeaseSets stored in our NetDb"))
               .append("\">")
               .append(lsCount)
               .append("</span></a>");
            }
        }

        String buildStatus = _helper.getTunnelStatus();
        String buildStatusText = buildStatus;
        String buildStatusTooltip = "";
        String buildStatusClass = "tunnelBuildStatus";
        int partTunnels = _helper.getParticipatingTunnels();

        if (buildStatus.startsWith("[starting]")) {
            buildStatusClass += " starting";
            buildStatusTooltip = _t("Declining transit tunnel requests during startup phase");
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        } else if (buildStatus.startsWith("[shutdown]")) {
            buildStatusClass += " rejecting";
            buildStatusTooltip = _t("Declining transit tunnel requests as we are shutting down");
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        } else if (buildStatus.startsWith("[rejecting/overload]")) {
            buildStatusClass += " rejecting";
            buildStatusTooltip = _t("Router or network performance is impeding the building of transit tunnels");
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        } else if (buildStatus.startsWith("[rejecting/max]")) {
            buildStatusClass += " rejecting";
            buildStatusTooltip = _t("Router is currently hosting the configured maximum number of transit tunnels");
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        } else if (buildStatus.startsWith("[rejecting/bandwidth]")) {
            buildStatusClass += " rejecting";
            buildStatusTooltip = _t("Router is currently using the configured maximum share bandwidth");
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        } else if (buildStatus.startsWith("[ready]") || buildStatus.startsWith("[accepting]")  && partTunnels <= 0) {
            buildStatusClass += " accepting";
            buildStatusTooltip = _t("Router is ready to build participating tunnels");
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        } else if (buildStatus.startsWith("[accepting]")) {
            buildStatusClass += " active";
            buildStatusTooltip = _t("Accepting transit tunnel requests; hosting {0} tunnels", partTunnels);
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        } else if (buildStatus.startsWith("[hidden]")) {
            buildStatusClass += " hidden";
            buildStatusTooltip = _t("No transit tunnels are built when Hidden mode is active");
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        } else if (buildStatus.startsWith("[disabled]")) {
            buildStatusClass += " rejecting disabled";
            buildStatusTooltip = _t("Router is configured to reject all transit tunnel requests");
            buildStatusText = buildStatusText.substring(buildStatusText.indexOf(']') + 1).trim();
        }

        buf.append(" <span id=sb_tunnelBuildStatus class=\"volatile ")
           .append(buildStatusClass).append("\" title=\"").append(buildStatusTooltip).append("\">")
           .append("</span>");

        buf.append("</span></h4>\n");

        if (!SigType.ECDSA_SHA256_P256.isAvailable()) {
            buf.append("<hr>\n<h4><span class=warn>")
               .append(_t("Warning: ECDSA is not available. Update your Java or OS"))
               .append("</a></span></h4>\n");
        }

        return buf.toString();
    }

    public String renderUpdateStatusHTML() {
        String updateStatus = _helper.getUpdateStatus();
        StringBuilder buf = new StringBuilder(512);
        if (_helper == null || updateStatus.isEmpty()) {return "";}
        else {
            toggleId = "toggle_sb_updatesection";
            buf.append("<div id=sb_updatesection class=collapse><h3><a href=/configupdate target=_top title=\"")
               .append(_t("Configure I2P Updates"))
               .append("\">")
               .append(_t("Update Status"))
               .append("</a>")
               .append(getToggle())
               .append("</h3><hr class=b>")
               .append(updateStatus)
               .append("</div>\n");
        }
        return buf.toString();
    }

    public String renderRestartStatusHTML() {
        if (_helper == null) {return "";}
        return _helper.getRestartStatus();
    }

    public String renderPeersHTML() {
        if (_helper == null) {return "";}
        StringBuilder buf = new StringBuilder(512);
        int active = _helper.getActivePeers();
        int allPeers = _helper.getAllPeers();
        boolean isAdvanced = isAdvanced();
        toggleId = "toggle_sb_peers";

        buf.append("<h3><a href=/peers target=_top title=\"")
           .append(_t("Show all current peer connections"))
           .append("\">")
           .append(_t("Peers"))
           .append(" <span class=\"badge volatile\" hidden title=\"")
           .append(_t("Peers active in the last minute"))
           .append(" / ")
           .append(_t("Total peers in NetDb"))
           .append("\">")
           .append(active)
           .append (" / ")
           .append(allPeers)
           .append("</span></a>")
           .append(getToggle())
           .append("</h3><hr class=b><table id=sb_peers>\n<tr title=\"");
        if (isAdvanced) {
            buf.append(_t("Peers we've been talking to in the last minute / last hour"));
        } else {
            buf.append(_t("Peers we've been talking to in the last minute"));
        }
        buf.append("\"><td><a href=/peers><b>")
           .append(_t("Active"))
           .append("</b></a></td><td class=digits><span class=volatile>");
        buf.append(active);
        if (isAdvanced) {
            buf.append(SidebarHelper.THINSP).append(Math.max(active, _helper.getActiveProfiles()));
        }
        buf.append("</span></td></tr>\n<tr title=\"")
           .append(_t("The number of peers available for building client tunnels"))
           .append("\"><td><a href=\"/profiles?f=1#profilelist\"><b>")
           .append(_t("Fast"))
           .append("</b></a></td><td class=digits><span class=volatile>")
           .append(_helper.getFastPeers())
           .append("</span></td></tr>\n<tr title=\"")
           .append(_t("The number of peers available for building exploratory tunnels"))
           .append("\"><td><a href=\"/profiles?f=1#highcap_peers\"><b>")
           .append(_t("High capacity"))
           .append("</b></a></td><td class=digits><span class=volatile>")
           .append(_helper.getHighCapacityPeers())
           .append("</span></td></tr>\n<tr title=\"")
           .append(_t("The number of peers available for network database inquiries"))
           .append("\"><td><a href=\"/profiles?f=2\"><b>")
           .append(_t("Floodfill"))
           .append("</b></a></td><td class=digits><span class=volatile>")
           .append(_helper.getWellIntegratedPeers())
           .append("</span></td></tr>\n<tr title=\"")
           .append(_t("The number of peers without a published public IP address"))
           .append("\"><td><a href=\"/netdb?caps=U\"><b>")
           .append(_t("Unreachable"))
           .append("</b></a></td><td class=digits><span class=volatile>")
           .append(_helper.getUnreachablePeers())
           .append("</span></td></tr>\n");
       if (_helper.getBanlistedPeers() > 1) {
           buf.append("<tr title=\"")
              .append(_t("The number of banned peers"))
              .append("\"><td><a href=\"/profiles?f=3\"><b>")
              .append(_t("Banned"))
              .append("</b></a></td><td class=digits><span class=volatile>")
              .append(_helper.getBanlistedPeers())
              .append("</span></td></tr>\n");
       }

        buf.append("<tr title=\"")
           .append(_t("The total number of peers in our network database"))
           .append(" / ")
           .append(_t("Total RouterInfos stored on disk"))
           .append("\"><td><a href=\"/netdb\"><b>")
           .append(_t("Known"))
           .append("</b></a></td><td class=digits><span class=volatile>")
           .append(allPeers)
           .append(" / ")
           .append(_helper.getStoredRouterInfos())
           .append("</span></td></tr>\n</table>\n<table id=sb_peers_condensed hidden>\n<tr><td><a class=sb_icon id=floodfill href=\"/profiles?f=2\" title=\"")
           .append(_t("Floodfill"))
           .append("\"><span><span class=\"badge volatile\">")
           .append(_helper.getWellIntegratedPeers())
           .append("</span></span></a>\n<a class=sb_icon id=fast href=\"/profiles?f=1#profilelist\" title=\"")
           .append(_t("Fast"))
           .append("\"><span><span class=\"badge volatile\">")
           .append(_helper.getFastPeers())
           .append("</span></span></a>\n<a class=sb_icon id=firewalled href=\"/netdb?caps=U\" title=\"")
           .append(_t("Unreachable"))
           .append("\"><span><span class=\"badge volatile\">")
           .append(_helper.getUnreachablePeers())
           .append("</span></span></a>\n<a class=sb_icon id=banned href=\"/profiles?f=3\" title=\"")
           .append(_t("Banned"))
           .append("\"><span><span class=\"badge volatile\">")
           .append(_helper.getBanlistedPeers())
           .append("</span></span></a>\n</td></tr>\n</table>\n");

        return buf.toString();
    }

    public String renderFirewallAndReseedStatusHTML() {
        if (_helper == null) {return "";}
        return _helper.getFirewallAndReseedStatus();
    }

    public String renderBandwidthHTML() {
        if (_helper == null) {return "";}
        StringBuilder buf = new StringBuilder(512);
        toggleId = "toggle_sb_bandwidth";

        buf.append("<h3><a href=/config title=\"")
           .append(_t("Configure router bandwidth allocation"))
           .append("\" target=_top>")
           .append(_t("Bandwidth in/out"))
           .append("</a>")
           .append(getToggle())
           .append("</h3>\n<hr class=b><table id=sb_bandwidth>\n<tr><td><b>")
           .append(DataHelper.formatDuration2(3 * 1000).replace("3&nbsp;sec", _t("Current")))
           .append("</b></td><td class=digits><span class=volatile>")
           .append(_helper.getSecondKBps())
           .append("Bps</span></td></tr>\n");

        if (_context.router().getUptime() > 6*60*1000) {
            buf.append("<tr><td><b>")
               .append(DataHelper.formatDuration2(5 * 60 * 1000).replace("5&nbsp;min", _t("5 Min Average")))
               .append("</b></td><td class=digits><span class=volatile>")
               .append(_helper.getFiveMinuteKBps())
               .append("Bps</span></td></tr>\n");
        } else {
            buf.append("<tr hidden><td><b>")
               .append(DataHelper.formatDuration2(5 * 60 * 1000).replace("5&nbsp;min", _t("5 Min Average")))
               .append("</b></td><td class=digits><span class=volatile>---Bps</span></td></tr>\n");
        }

        if (_context.router().getUptime() > 2*60*1000) {
            buf.append("<tr><td><b>")
               .append(_t("Total").replace("Total", _t("Lifetime")))
               .append("</b></td><td class=digits><span class=volatile>")
               .append(_helper.getLifetimeKBps())
               .append("Bps</span></td></tr>\n");
        } else {
            buf.append("<tr hidden><td><b>")
               .append(_t("Total").replace("Total", _t("Lifetime")))
               .append("</b></td><td class=digits><span class=volatile>---Bps</span></td></tr>\n");
        }

        buf.append("<tr><td><b>")
           .append(_t("Used").replace("Used", "Transferred"))
           .append("</b></td><td class=digits><span class=volatile>")
           .append(_helper.getInboundTransferred().replace("iB", ""))
           .append(" / ")
           .append(_helper.getOutboundTransferred().replace("iB", ""))
           .append("</span></table>\n");
        return buf.toString();
    }

    /** @since 0.9.32 */
    public String renderBandwidthGraphHTML() {
        if (_helper == null) {return "";}
        if (GraphGenerator.isDisabled(_context)) {return "";}
        String r = _context.getProperty(CSSHelper.PROP_REFRESH, CSSHelper.DEFAULT_REFRESH);
        int refreshPeriod = 3;
        try {refreshPeriod = Integer.parseInt(r);}
        catch (NumberFormatException nfe) {}
        String t = "&amp;t=" + (_context.clock().now() / 1000);
        String g = "style=\"background-image:url(\'/viewstat.jsp?stat=bw.combined&amp;periodCount=20&amp;width=250&amp;height=50&amp;hideLegend=true&amp;hideGrid=true&amp;hideTitle=true" +
                    t + "\')\"";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<div id=sb_graphcontainer class=collapse title=\"")
           .append(_t("Our inbound &amp; outbound traffic for the last 20 minutes"))
           .append("\">\n<span id=sb_graphstats class=volatile>")
           .append(_helper.getSecondKBps())
           .append("Bps</span>\n<a href=/graphs>\n<canvas id=minigraph width=245 height=50>\n<div id=minigraphcontainer ")
           .append(g)
           .append("></div></canvas>\n<noscript><div id=minigraphcontainer_noscript ")
           .append(g)
           .append("></div></noscript></a>\n</div>\n");
        return buf.toString();
    }

    public String renderTunnelsHTML() {
        if (_helper == null) {return "";}
        StringBuilder buf = new StringBuilder(512);
        int partTunnels = _helper.getParticipatingTunnels();
        String maxTunnels = _helper.getMaxParticipatingTunnels();
        int totalTunnels =_helper.getInboundTunnels() + _helper.getOutboundTunnels() + _helper.getInboundClientTunnels() +
                          _helper.getOutboundClientTunnels() + partTunnels;
        RouterInfo ri = _context.router().getRouterInfo();
        toggleId = "toggle_sb_tunnels";

        buf.append("<h3><a href=/tunnels target=_top title=\"")
           .append(_t("View existing tunnels and tunnel build status"))
           .append("\">")
           .append(_t("Tunnels"))
           .append(" <span class=\"badge volatile\" hidden title=\"")
           .append(_t("Total number of tunnels in use"));
       if (_helper.getTunnelBuildSuccess() > 0) {
           buf.append(" / ")
              .append(_t("Average tunnel build success for last 10m interval"));
       }
       buf.append("\">")
          .append(totalTunnels);
       if (_helper.getTunnelBuildSuccess() > 0) {
           buf.append(" / ")
              .append(_helper.getTunnelBuildSuccess())
              .append("%");
       }
       buf.append("</span></a>")
          .append(getToggle())
          .append("</h3>\n<hr class=b>\n<table id=sb_tunnels>\n");
        if (_helper.getInboundClientTunnels() > 0 || _helper.getOutboundClientTunnels() > 0) {
            buf.append("<tr title=\"")
               .append(_t("Tunnels we are using to provide or access services on the network"))
               .append(" (")
               .append(_t("inbound / outbound"))
               .append(")\"><td><a href=/tunnels#client_tunnels><b>")
               .append(_t("Service"))
               .append("</b></a></td><td class=digits><span class=volatile>")
               .append(_helper.getInboundClientTunnels())
               .append(" / ")
               .append(_helper.getOutboundClientTunnels())
               .append("</span></td></tr>\n");
        }

        buf.append("<tr title=\"")
           .append(_t("Used for building and testing tunnels, and communicating with floodfill peers"))
           .append(" (")
           .append(_t("inbound / outbound"))
           .append(")").append("\"><td><a href=/tunnels#exploratory><b>")
           .append(_t("Utility"))
           .append("</b></a></td><td class=digits><span class=volatile>")
           .append(_helper.getInboundTunnels())
           .append(" / ")
           .append(_helper.getOutboundTunnels())
           .append("</span></td></tr>\n");

            if ((maxTunnels == null || partTunnels > 0 || Integer.parseInt(maxTunnels) > 0) &&
                !_context.router().isHidden() && ri != null && !ri.getBandwidthTier().equals("K") && partTunnels > 0) {
                buf.append("<tr title=\"")
                   .append(_t("Tunnels we are participating in, directly contributing bandwidth to the network"))
                   .append(" (")
                   .append(_t("Current / Maximum"))
                   .append(")\"><td><a href=/transit><b>")
                   .append(_t("Transit"))
                   .append("</b></a></td><td class=digits><span class=volatile>")
                   .append(partTunnels)
                   .append(" / ")
                   .append(maxTunnels)
                   .append("</span></td></tr>\n");
            }
            buf.append("<tr title=\"")
               .append(_t("Total number of tunnels in use"))
               .append("\"><td><a href=/tunnelpeercount><b>")
               .append(_t("Total"))
               .append("</b></a></td><td class=digits><span class=volatile>")
               .append(totalTunnels)
               .append("</span></td></tr>\n<tr title=\"")
               .append(_t("Concurrent tunnel builds (averaged over a minute) / single tunnel build time"))
               .append("\"><td><b>")
               .append(_t("Concurrency"))
               .append("</b></td><td class=digits><span class=volatile>")
               .append(_helper.getConcurrency())
               .append("</span></td></tr>\n");
            if (_helper.getTunnelBuildSuccess() > 0) {
                buf.append("<tr title=\"")
                   .append(_t("Combined utility and service tunnel build success (averaged over 10 minute intervals)"))
                   .append("\"><td><a href=\"/graph?stat=tunnel.tunnelBuildSuccessAvg.60000&c=1440&w=1000&h=280\"><b>")
                   .append(_t("Build Success"))
                   .append("</b></a></td><td class=digits><span class=volatile>")
                   .append(_helper.getTunnelBuildSuccess())
                   .append("%</span></td></tr>\n");
            }

            if ((maxTunnels == null || Integer.parseInt(maxTunnels) > 0) && !_context.router().isHidden() && ri != null &&
                 !ri.getBandwidthTier().equals("K") && !_helper.getShareRatio().toString().equals("0")) {
                buf.append("<tr title=\"")
                   .append(_t("The ratio of tunnel hops we provide to tunnel hops we use - a value greater than 1.00 indicates a positive contribution to the network"))
                   .append("\"><td><b>")
                   .append(_t("Share ratio"))
                   .append("</b></td><td class=digits><span class=volatile>")
                   .append(_helper.getShareRatio())
                   .append("</span></td></tr>\n");
            }

            buf.append("</table>\n<table id=sb_tunnels_condensed hidden>\n<tr><td><a class=sb_icon id=service href=/tunnels#client_tunnels title=\"")
               .append(_t("Service"))
               .append(' ')
               .append(_t("tunnels"))
               .append("\"><span><span class=\"badge volatile\">")
               .append(_helper.getInboundClientTunnels() + _helper.getOutboundClientTunnels())
               .append("</span></span></a>\n<a class=sb_icon id=transit href=/transit title=\"")
               .append(_t("Transit"))
               .append(' ')
               .append(_t("tunnels"))
               .append("\"><span><span class=\"badge volatile\">")
               .append(partTunnels)
               .append("</span></span></a>\n<a class=sb_icon id=utility href=/tunnels#exploratory title=\"")
               .append(_t("Utility"))
               .append(' ')
               .append(_t("tunnels"))
               .append("\"><span><span class=\"badge volatile\">")
               .append(_helper.getInboundTunnels() + _helper.getOutboundTunnels())
               .append("</span></span></a>\n<a class=sb_icon id=tcount href=/tunnelpeercount title=\"")
               .append(_t("Tunnel Count by Peer"))
               .append("\"><span><span class=\"badge volatile\">")
               .append(totalTunnels)
               .append("</span></span></a>\n</td></tr>\n</table>\n");

        return buf.toString();
    }

    public String renderCongestionHTML() {
        if (_helper == null) {return "";}
        long maxLag = _context.jobQueue().getMaxLag();
        StringBuilder buf = new StringBuilder(512);
        toggleId = "toggle_sb_queue";

        buf.append("<h3><a href=/jobs target=_top title=\"")
           .append(_t("What's in the router's job queue?"))
           .append("\">")
           .append(_t("Congestion"));
        if (_context.router().getUptime() > 2*60*1000) {
           buf.append(" <span class=\"badge volatile\" hidden title=\"")
              .append(_t("Job lag"))
              .append(" / ")
              .append(_t("Message delay"))
              .append("\">")
              .append(_helper.getAvgJobLag())
              .append(" / ")
              .append(_helper.getMessageDelay())
              .append("</span>");
        } else {
           buf.append(" <span class=\"badge volatile\" hidden title=\"")
              .append(_t("Job lag"))
              .append(" / ")
              .append(_t("Message delay"))
              .append("\">--- / ---</span>");
        }
        buf.append("</a>")
           .append(getToggle())
           .append("</h3>\n<hr class=b>\n<table id=sb_queue>\n<tr title=\"");
        if (isAdvanced() && maxLag >= 30) {buf.append(_t("Average / peak job delay (last minute)"));}
        else {buf.append(_t("Average / max delay before scheduled jobs are run"));}
        buf.append("\"><td><b>")
           .append(_t("Job lag"))
           .append("</b></td><td class=digits>");
        int maxLagBeforeDrop = 2000;
        if (maxLag > maxLagBeforeDrop) {buf.append("<span class=\"warntext volatile\">");}
        else {buf.append("<span class=volatile>");}
        buf.append(_helper.getJobLag())
           .append("</span></td></tr>\n<tr title=\"")
           .append(_t("Indicates how quickly outbound messages to other I2P routers are sent"))
           .append("\"><td><b>")
           .append(_t("Message delay"))
           .append("</b></td><td class=digits>");
        if (_context.throttle().getMessageDelay() > 2000) {
            buf.append("<span class=\"warntext volatile\">")
               .append(_helper.getMessageDelay())
               .append("</span>");
        } else {
            buf.append("<span class=volatile>")
               .append(_helper.getMessageDelay())
               .append("</span>");
        }
        buf.append("</td></tr>\n");

        if (_context.getBooleanPropertyDefaultTrue("routerconsole.showPeerTestAvg")) {
            buf.append("<tr title=\"")
               .append(_t("Average time to test a peer (successful / total)"))
               .append("\"><td><a href=/profiles><b>")
               .append(_t("Peer test average"))
               .append("</b></a></td><td class=digits><span class=volatile>")
               .append(_helper.getAvgPeerTestTimeGood())
               .append(" / " )
               .append(_helper.getAvgPeerTestTime());
            if (_helper.getAvgPeerTestTime() > 0) {buf.append("&nbsp;ms");}
            buf.append("</span></td></tr>\n");
        }

        if (!_context.getBooleanProperty("router.disableTunnelTesting") && !_helper.getTunnelLag().equals("0")) {
            buf.append("<tr title=\"")
               .append(_t("Round trip time for a tunnel test"))
               .append("\"><td><b>")
               .append(_t("Tunnel lag"))
               .append("</b></td><td class=digits><span class=volatile>")
               .append(_helper.getTunnelLag())
               .append("</span></td></tr>\n");
        } else {
            buf.append("<tr title=\"")
               .append(_t("Round trip time for a tunnel test"))
               .append("\" hidden><td><b>")
               .append(_t("Tunnel lag"))
               .append("</b></td><td class=digits><span class=volatile>---</span></td></tr>\n");
        }

        if (Integer.parseInt(_helper.getInboundBacklog()) > 0) {
        buf.append("<tr title=\"")
           .append(_t("Queued requests from other routers to participate in tunnels"))
           .append("\"><td><b>")
           .append(_t("Backlog"))
           .append("</b></td><td class=digits><span class=\"warntext volatile\">")
           .append(_helper.getInboundBacklog())
           .append("</span></td></tr>\n");
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    public String renderTunnelStatusHTML() {
        if (_helper == null) {return "";}
        int partTunnels = _helper.getParticipatingTunnels();
        StringBuilder buf = new StringBuilder(50);
        String getStatus = _helper.getTunnelStatus();
        String buildStatus = getStatus.indexOf('[') >= 0 && getStatus.indexOf(']') > getStatus.indexOf('[')
                              ? getStatus.substring(getStatus.indexOf(']') + 1) : getStatus;
        boolean linebreak = buildStatus.indexOf("<br>") >= 0;
        if (linebreak) {
            buildStatus = buildStatus.substring(buildStatus.indexOf("<br>") + 4);
        }
        buf.append("<h4 id=sb_tunnelstatus class=\"volatile collapse\"><span class=\"tunnelBuildStatus");
        if (getStatus.startsWith("[starting]"))
            buf.append(" starting\" title=\"").append(_t("Declining transit tunnel requests during startup phase"));
        else if (getStatus.startsWith("[shutdown]"))
            buf.append(" rejecting\" title=\"").append(_t("Declining transit tunnel requests as we are shutting down"));
        else if (getStatus.startsWith("[rejecting/overload]"))
            buf.append(" rejecting\" title=\"").append(_t("Router or network performance is impeding the building of transit tunnels"));
        else if (getStatus.startsWith("[rejecting/max]"))
            buf.append(" rejecting\" title=\"").append(_t("Router is currently hosting the configured maximum number of transit tunnels"));
        else if (getStatus.startsWith("[rejecting/bandwidth]"))
            buf.append(" rejecting\" title=\"").append(_t("Router is currently using the configured maximum share bandwidth"));
        else if (getStatus.startsWith("[ready]") || getStatus.startsWith("[accepting]") && partTunnels <= 0)
            buf.append(" accepting\" title=\"").append(_t("Router is ready to build participating tunnels"));
        else if (getStatus.startsWith("[accepting]"))
            buf.append(" active\" title=\"").append(_t("Accepting transit tunnel requests; hosting {0} tunnels", partTunnels));
        else if (getStatus.startsWith("[hidden]"))
            buf.append(" hidden\" title=\"").append(_t("No transit tunnels are built when Hidden mode is active"));
        else if (getStatus.startsWith("[disabled]"))
            buf.append(" rejecting disabled\" title=\"").append(_t("Router is configured to reject all transit tunnel requests"));
        buf.append("\">").append(buildStatus).append("</span></h4>\n");
        return buf.toString();
    }

    public String renderDestinationsHTML() {
        if (_helper == null) {return "";}
        return _helper.getDestinations();
    }

    /** @since 0.9.1 */
    public String renderNewsHeadingsHTML() {
        if (_helper == null) {return "";}
        NewsHelper newshelper = _helper.getNewsHelper();
        if (newshelper == null || newshelper.shouldShowNews()) return "";
        StringBuilder buf = new StringBuilder(512);
        String consoleNonce = CSSHelper.getNonce();
        if (consoleNonce != null) {
            // Get news content.
            List<NewsEntry> entries = Collections.emptyList();
            ClientAppManager cmgr = _context.clientAppManager();
            if (cmgr != null) {
                NewsManager nmgr = (NewsManager) cmgr.getRegisteredApp(NewsManager.APP_NAME);
                if (nmgr != null) {entries = nmgr.getEntries();}
            }
            if (!entries.isEmpty()) {
                int i = 0;
                // show a min of 1, max of 3, none older than 60 days over min
                // Except, if news fetching is disabled, min is 0 and oldest is 7 days.
                // We still show news even if disabled, because the user could click it manually
                String freq = _context.getProperty(ConfigUpdateHandler.PROP_REFRESH_FREQUENCY,
                                                   ConfigUpdateHandler.DEFAULT_REFRESH_FREQUENCY);
                long ms = ConfigUpdateHandler.DEFAULT_REFRESH_FREQ;
                try {ms = Long.parseLong(freq);}
                catch (NumberFormatException nfe) {}
                final int min = (ms > 0) ? 1 : 0;
                final int max = 3;
                final long age = (ms > 0) ? 60*24*60*60*1000L : 7*24*60*60*1000L;
                final long oldest = _context.clock().now() - age;
                toggleId = "toggle_sb_newsheadings";

                for (NewsEntry entry : entries) {
                    if (i >= min && entry.updated > 0 &&entry.updated < oldest) {break;}
                    if (i == 0) {
                        // Set up title and pre-headings stuff.
                        buf.append("<h3 id=sb_newsH3><a href=/news>")
                           .append(_t("News &amp; Updates"))
                           .append(" <span id=newsCount class=\"badge volatile\" hidden></span></a>")
                           .append(getToggle())
                           .append("</h3><hr class=b><div id=sb_newsheadings>\n<table>\n");
                    }
                    buf.append("<tr><td><a href=\"/home?news=1&amp;consoleNonce=").append(consoleNonce).append("\"");
                    if (entry.updated > 0) {
                        buf.append(" title=\"")
                           .append(_t("Published"))
                           .append(": ")
                           .append(DataHelper.formatDate(entry.updated))
                           .append("\"");
                    }
                    buf.append(">").append(entry.title).append("</a></td></tr>\n");
                    if (++i >= max) {break;}
                }
                if (i > 0) {buf.append("</table>\n</div>\n");}
            }
        }
        return buf.toString();
    }

    /** tag only */
    private static final String _x(String s) {return s;}

    /** translate a string */
    private String _t(String s) {return Messages.getString(s, _context);}

    /** @since 0.9.23 */
    private String _t(String s, Object o) {return Messages.getString(s, o, _context);}

    /**
     *  Where the translation is to two words or more,
     *  prevent splitting across lines
     *
     *  @since 0.9.18
     */
    private static String nbsp(String s) {
        if (s.length() <= 30) {return s.replace(" ", "&nbsp;");} // if it's too long, this makes it worse
        else {return s;}
    }

}
