package net.i2p.router.web.helpers;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.text.Collator;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;

import net.i2p.CoreVersion;
import net.i2p.app.ClientAppManager;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.news.NewsEntry;
import net.i2p.router.news.NewsManager;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NavHelper;
import net.i2p.router.web.NewsHelper;
import net.i2p.router.web.StatSummarizer;
import net.i2p.util.PortMapper;
import net.i2p.util.SystemVersion;

import net.i2p.data.router.RouterInfo;

// for UTC clock
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 *  Refactored from summarynoframe.jsp to save ~100KB
 *
 */
class SummaryBarRenderer {

    static final String ALL_SECTIONS[] =
        {"HelpAndFAQ", "I2PServices", "I2PInternals", "RouterInfo", "ShortRouterInfo", "AdvancedRouterInfo", "MemoryBar", "NetworkReachability",
        "UpdateStatus", "RestartStatus", "Peers", "PeersAdvanced", "FirewallAndReseedStatus", "Bandwidth", "BandwidthGraph", "Tunnels",
        "Congestion", "TunnelStatus", "Destinations", "NewsHeadings", "Advanced", "Clock"};
    static final Map<String, String> SECTION_NAMES;

    static {
        Map<String, String> aMap = new HashMap<String, String>();
        aMap.put("HelpAndFAQ", _x("Help &amp; FAQ"));
        aMap.put("I2PServices", _x("I2P Services"));
        aMap.put("I2PInternals", _x("I2P Internals"));
        aMap.put("RouterInfo", _x("Router Information"));
        aMap.put("ShortRouterInfo", _x("Router Information (brief)"));
        aMap.put("AdvancedRouterInfo", _x("Router Information (advanced)"));
        aMap.put("MemoryBar", _x("Memory Usage Bar"));
        aMap.put("NetworkReachability", _x("Network Reachability"));
        aMap.put("UpdateStatus", _x("Update Status"));
        aMap.put("RestartStatus", _x("Restart Status"));
        aMap.put("Peers", _x("Peers"));
        aMap.put("PeersAdvanced", _x("Peers (advanced)"));
        aMap.put("FirewallAndReseedStatus", _x("Firewall &amp; Reseed Status"));
        aMap.put("Bandwidth", _x("Bandwidth"));
        aMap.put("BandwidthGraph", _x("Bandwidth Graph"));
        aMap.put("Tunnels", _x("Tunnels"));
        aMap.put("Congestion", _x("Congestion"));
        aMap.put("TunnelStatus", _x("Tunnel Status"));
        aMap.put("Destinations", _x("Service Tunnels"));
        aMap.put("NewsHeadings", _x("News &amp; Updates"));
        aMap.put("Advanced", _x("Advanced Links"));
        aMap.put("Clock", _x("UTC Clock"));
        SECTION_NAMES = Collections.unmodifiableMap(aMap);
    }

    private final RouterContext _context;
    private final SummaryHelper _helper;
    private static final String PROP_ADVANCED = "routerconsole.advanced";

    public SummaryBarRenderer(RouterContext context, SummaryHelper helper) {
        _context = context;
        _helper = helper;
    }

    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
    }

    /**
     *  Note - ensure all links in here are absolute, as the summary bar may be displayed
     *         on lower-level directory errors.
     */
    public void renderSummaryHTML(Writer out) throws IOException {
        String requestURI = _helper.getRequestURI();
        String page = requestURI.replace("/", "").replace(".jsp", "");
        List<String> sections = _helper.getSummaryBarSections(page);

        // regardless of section order, we want to process the restart buttons first,
        // so other sections reflect the current restart state
        String restartStatus = sections.contains("RestartStatus") ? renderRestartStatusHTML() : null;

        StringBuilder buf = new StringBuilder(1024);
        for (String section : sections) {
            buf.setLength(0);

            buf.append("<hr>\n");
            if ("HelpAndFAQ".equals(section) && (!requestURI.contains("sitemap") || !requestURI.contains("help")))
                buf.append(renderHelpAndFAQHTML());
            else if ("I2PServices".equals(section))
                buf.append(renderI2PServicesHTML());
            else if ("I2PInternals".equals(section) && !requestURI.contains("sitemap"))
                buf.append(renderI2PInternalsHTML());
            else if ("Advanced".equals(section) && !requestURI.contains("sitemap"))
                buf.append(renderAdvancedHTML());
            else if ("RouterInfo".equals(section) || "General".equals(section)) // Backwards-compatibility
                buf.append(renderRouterInfoHTML());
            else if ("ShortRouterInfo".equals(section) || "ShortGeneral".equals(section)) // Backwards-compatibility
                buf.append(renderShortRouterInfoHTML());
            else if ("AdvancedRouterInfo".equals(section))
                buf.append(renderAdvancedRouterInfoHTML());
            else if ("MemoryBar".equals(section))
                buf.append(renderMemoryBarHTML());
            else if ("NetworkReachability".equals(section))
                buf.append(renderNetworkReachabilityHTML());
            else if ("UpdateStatus".equals(section))
                buf.append(renderUpdateStatusHTML());
            else if ("RestartStatus".equals(section))
                buf.append(restartStatus); // prerendered above
            else if ("Peers".equals(section))
                buf.append(renderPeersHTML());
            else if ("PeersAdvanced".equals(section))
                buf.append(renderPeersAdvancedHTML());
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
//            else if ("NewsHeadings".equals(section) && !requestURI.contains("news"))
            else if ("NewsHeadings".equals(section) && requestURI.contains("home")) // only render on homepage
                buf.append(renderNewsHeadingsHTML());
            else if ("Clock".equals(section))
                buf.append(renderClockHTML());

            // Only output section if there's more than the <hr> to print
            if (buf.length() > 5)
                out.write(buf.toString());
        }
    }

    public String renderHelpAndFAQHTML() {
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3 id=\"helpfaq\"><a href=\"/help/\" target=\"_top\" title=\"")
           .append(_t("I2P Router Help &amp; FAQ"))
           .append("\">")
           .append(_t("Help &amp; FAQ"))
           .append("</a></h3><hr class=\"b\">\n" +
                   "<table id=\"sb_help\">\n<tr><td>");

        // Store all items in map so they are sorted by translated name, then output
        Map<String, String> svcs = new TreeMap<String, String>(Collator.getInstance());
        StringBuilder rbuf = new StringBuilder(128);

        String tx = _t("Advanced Settings");
        rbuf.append("<a href=\"/help/advancedsettings\" target=\"_top\" title=\"")
            .append(_t("A guide to some of the less-used configuration settings"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Changelog");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/changelog\" target=\"_top\" title=\"")
            .append(_t("Recent development changes to the router"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Configuration");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/configuration\" target=\"_top\" title=\"")
            .append(_t("An introduction to configuring your router"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("FAQ");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/faq\" target=\"_top\" title=\"")
            .append(_t("A shortened version of the official Frequently Asked Questions"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("New Users");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/newusers\" target=\"_top\" title=\"")
            .append(_t("A gentle introduction to I2P"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Reseeding");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/reseed\" target=\"_top\" title=\"")
            .append(_t("A guide to reseeding the router"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Troubleshoot");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/troubleshoot\" target=\"_top\" title=\"")
            .append(_t("Troubleshooting &amp; Further Assistance"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("UI");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/ui\" target=\"_top\" title=\"")
            .append(_t("Information about the sidebar and network status messages"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Webhosting");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/webhosting\" target=\"_top\" title=\"")
            .append(_t("An introduction to hosting websites on the I2P network"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        for (String row : svcs.values()) {
             buf.append(row);
        }
        buf.append("</td></tr>\n</table>\n");
        return buf.toString();
    }

    public String renderI2PServicesHTML() {
        boolean embedApps = _context.getBooleanProperty(CSSHelper.PROP_EMBED_APPS);

        // Store all items in map so they are sorted by translated name, add the plugins, then output
        Map<String, String> svcs = new TreeMap<String, String>(Collator.getInstance());
        StringBuilder rbuf = new StringBuilder(128);
        PortMapper pm = _context.portMapper();
        if (pm.isRegistered(PortMapper.SVC_SUSIDNS)) {
            String tx = _t("Addressbook");
            rbuf.append("<a href=\"/dns\" target=\"_top\" title=\"")
                .append(_t("Manage your I2P hosts file here (I2P domain name resolution)"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n");
            svcs.put(tx, rbuf.toString());
        }

        if (pm.isRegistered(PortMapper.SVC_SUSIMAIL)) {
            String tx = _t("I2PMail");
            rbuf.setLength(0);
            rbuf.append("<a href=\"/webmail\" ");
            if (embedApps) {
                rbuf.append("target=\"_top\" ");
            } else {
                rbuf.append("target=\"_blank\" ");
            }
            rbuf.append("title=\"")
                .append(_t("Anonymous webmail client"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n");
            svcs.put(tx, rbuf.toString());
        }

        if (pm.isRegistered(PortMapper.SVC_JSONRPC)) {
            String tx = _t("I2PControl");
            rbuf.setLength(0);
            rbuf.append("<a href=\"/jsonrpc/\" target=\"_blank\" title=\"")
                .append(_t("RPC Service"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n");
            svcs.put(tx, rbuf.toString());
        }

        if (pm.isRegistered(PortMapper.SVC_I2PSNARK)) {
            String tx = _t("Torrents");
            rbuf.setLength(0);
            rbuf.append("<a href=\"/torrents\" ");
            if (embedApps) {
                rbuf.append("target=\"_top\" ");
            } else {
                rbuf.append("target=\"_blank\" ");
            }
            rbuf.append("title=\"")
                .append(_t("Built-in anonymous BitTorrent Client"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n");
            svcs.put(tx, rbuf.toString());
        }

        if (pm.isRegistered(PortMapper.SVC_I2PTUNNEL)) {
            String tx = _t("Tunnel Manager");
            rbuf.setLength(0);
            rbuf.append("<a href=\"/i2ptunnelmgr\" target=\"_top\" title=\"")
                .append(_t("Manage I2P client and server tunnels to access or provide network services"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n");
            svcs.put(tx, rbuf.toString());
        }

        List<String> urls = pm.getEepsiteURLs();
        if (urls != null) {
            String tx = _t("Web Server");
            String txtt = _t("Local Web Server");
            int sz = urls.size();
            if (sz > 1)
                Collections.sort(urls);
            for (int i = 0; i < sz; i++) {
                String url = urls.get(i);
                String txp = sz > 1 ? tx + ' ' + (i + 1) : tx;
                rbuf.setLength(0);
                rbuf.append("<a href=\"")
                    .append(url)
                    .append("\" target=\"_blank\" title=\"")
                    .append(txtt)
                    .append("\">")
                    .append(nbsp(txp))
                    .append("</a>\n");
                svcs.put(txp, rbuf.toString());
            }
        }

        Map<String, String> apps = NavHelper.getClientAppLinks();
        if (apps != null)
            svcs.putAll(apps);
        if (!svcs.isEmpty()) {
            StringBuilder buf = new StringBuilder(128 * svcs.size());
            buf.append("<h3><a href=\"/configwebapps\" target=\"_top\" title=\"")
               .append(_t("Configure startup of clients and webapps (services); manually start dormant services"))
               .append("\">")
               .append(_t("Services"))
               .append("</a></h3>\n<hr class=\"b\">\n<table id=\"sb_services\">\n<tr>\n<td>");
            for (String row : svcs.values()) {
                buf.append(row);
            }
            buf.append("</td>\n</tr>\n</table>\n");
            return buf.toString();
        } else {
            return "";
        }
    }

    /**
     *  @return null if none
     *  @since 0.9.43 split out from above, used by HomeHelper, fixed for IPv6
     */
    static String getEepsiteURL(PortMapper pm) {
        int port = pm.getPort(PortMapper.SVC_EEPSITE);
        int sslPort = pm.getPort(PortMapper.SVC_HTTPS_EEPSITE);
        if (port <= 0 && sslPort <= 0)
            return null;
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
        if (host.contains(":"))
            buf.append('[');
        buf.append(host);
        if (host.contains(":"))
            buf.append(']');
        buf.append(':')
           .append(port)
           .append('/');
        return buf.toString();
    }

    public String renderClockHTML() {
        StringBuilder buf = new StringBuilder(128);
        String FORMAT = "HH:mm:ss";
        DateFormat dfmt = new SimpleDateFormat(FORMAT, Locale.UK);
        TimeZone utc = TimeZone.getTimeZone("GMT");
        dfmt.setTimeZone(utc);
        buf.append("<div id=\"clock\">")
           .append(dfmt.format(new Date()))
           .append("</div>");
        return buf.toString();
    }

    public String renderI2PInternalsHTML() {
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/sitemap\" target=\"_top\" title=\"")
           .append(_t("I2P Router Sitemap"))
           .append("\">")
           .append(_t("Internals"))
           .append("</a></h3>\n<hr class=\"b\">\n" +
                   "<table id=\"sb_internals\">\n<tr>\n<td>\n");

        // Store all items in map so they are sorted by translated name, then output
        Map<String, String> svcs = new TreeMap<String, String>(Collator.getInstance());
        StringBuilder rbuf = new StringBuilder(128);

        String tx = _t("Certs");
        rbuf.append("<a target=\"_top\" title=\"")
            .append(_t("Review active encryption certificates used in console"))
            .append("\" href=\"/certs\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Events");
        rbuf.setLength(0);
        // 7 days
        rbuf.append("<a href=\"/events?from=604800\" target=\"_top\" title=\"")
            .append(_t("View historical log of router events"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        if (!StatSummarizer.isDisabled(_context)) {
            tx = _t("Graphs");
            rbuf.setLength(0);
            rbuf.append("<a href=\"/graphs\" target=\"_top\" title=\"")
                .append(_t("Graph router performance"))
                .append("\">")
                .append(nbsp(tx))
                .append("</a>\n");
            svcs.put(tx, rbuf.toString());
        }

        tx = _t("Help");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/help/\" target=\"_top\" title=\"")
            .append(_t("Router Help and FAQ"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Info");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/info\" target=\"_top\" title=\"")
            .append(_t("Router information including transport addresses and UPnP scan"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Jobs");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/jobs\" target=\"_top\" title=\"")
            .append(_t("Show the router's workload, and how it's performing"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Logs");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/logs\" target=\"_top\" title=\"")
            .append(_t("Health Report"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("NetDb");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/netdb\" target=\"_top\" title=\"")
            .append(_t("Show list of all known I2P routers"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Peers");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/peers\" target=\"_top\" title=\"")
            .append(_t("Show all current peer connections"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Profiles");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/profiles?f=1\" target=\"_top\" title=\"")
            .append(_t("Show recent peer performance profiles"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Tunnels");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/tunnels\" target=\"_top\" title=\"")
            .append(_t("View existing tunnels and tunnel build status"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        for (String row : svcs.values()) {
             buf.append(row);
        }
        buf.append("</td>\n</tr>\n</table>\n");
        return buf.toString();
    }

    public String renderAdvancedHTML() {
        StringBuilder buf = new StringBuilder(512);

        buf.append("<h3 id=\"advanced\"><a title=\"")
           .append(_t("Advanced Configuration"))
           .append("\" href=\"/configadvanced\" target=\"_top\">")
           .append(_t("Advanced"))
           .append("</a></h3>\n")

           .append("<hr class=\"b\">\n<table id=\"sb_advanced\">\n<tr><td>");

        // Store all items in map so they are sorted by translated name, then output
        Map<String, String> svcs = new TreeMap<String, String>(Collator.getInstance());
        StringBuilder rbuf = new StringBuilder(128);

        File javadoc = new File(_context.getBaseDir(), "docs/javadoc/index.html");
        if (javadoc.exists()) {
            String tx = _t("API");
            rbuf.append("<a title=\"")
                .append(_t("Documentation for the I2P API"))
                .append("\" href=\"/javadoc/index.html\" target=\"_blank\">")
                .append(nbsp(tx))
                .append("</a>\n");
            svcs.put(tx, rbuf.toString());
        }

        String tx = _t("Debug");
        rbuf.setLength(0);
        rbuf.append("<a title=\"")
            .append(_t("View router debug information"))
            .append("\" href=\"/debug\" target=\"_top\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Jars");
        rbuf.setLength(0);
        rbuf.append("<a title=\"")
            .append(_t("Review extended info about installed .jar and .war files"))
            .append("\" href=\"/jars\" target=\"_top\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Lookup");
        rbuf.setLength(0);
        rbuf.append("<a title=\"")
            .append(_t("Network database search tool"))
            .append("\" href=\"/netdb?f=4\" target=\"_top\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Stats");
        rbuf.setLength(0);
        rbuf.append("<a href=\"/stats\" target=\"_top\" title=\"")
            .append(_t("Textual router performance statistics"))
            .append("\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        tx = _t("Sybils");
        rbuf.setLength(0);
        rbuf.append("<a title=\"")
            .append(_t("Review possible sybils in network database"))
            .append("\" href=\"/netdb?f=3\" target=\"_top\">")
            .append(nbsp(tx))
            .append("</a>\n");
        svcs.put(tx, rbuf.toString());

        for (String row : svcs.values()) {
             buf.append(row);
        }
        buf.append("</td></tr>\n</table>");
        return buf.toString();
    }

    public String renderRouterInfoHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/info\" target=\"_top\" title=\"")
           .append(_t("Your Local Identity [{0}] is your unique I2P router identity, similar to an IP address but tailored to I2P. ", _helper.getIdent()))
           .append(_t("Never disclose this to anyone, as it can reveal your real world IP."))
           .append("\">")
           .append(_t("Router Info"))
           .append("</a></h3>\n<hr class=\"b\">\n" +

                   "<table id=\"sb_general\">" +
                   "<tr title=\"")
           .append(_t("The version of the I2P software we are running"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/configupdate\"><b>")
           .append(_t("Version"))
           .append("</b></a></td>" +
                   "<td class=\"digits\" align=\"right\">")
           .append(_helper.getVersion())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("How long we've been running for this session"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Uptime"))
           .append("</b></td>" +
                   "<td class=\"digits\" align=\"right\">")
           .append(_helper.getUptime())
           .append("</td></tr>\n</table>\n");
        return buf.toString();
    }

    public String renderShortRouterInfoHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<table id=\"sb_shortgeneral\">" +
                   "<tr title=\"")
           .append(_t("The version of the I2P software we are running"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/configupdate\"><b>")
           .append(_t("Version"))
           .append("</b></a></td>" +
                   "<td class=\"digits\" align=\"right\">")
           .append(_helper.getVersion())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("How long we've been running for this session"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Uptime"))
           .append("</b></td>" +
                   "<td class=\"digits\" align=\"right\">")
           .append(_helper.getUptime())
           .append("</td></tr></table>\n");
        return buf.toString();
    }

    /** @since 0.9.32 */
    public String renderAdvancedRouterInfoHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/info\" target=\"_top\" title=\"")
           .append(_t("Your Local Identity [{0}] is your unique I2P router identity, similar to an IP address but tailored to I2P. ", _helper.getIdent()))
           .append(_t("Never disclose this to anyone, as it can reveal your real world IP."))
           .append("\">")
           .append(_t("Router Info"))
           .append("</a></h3>\n<hr class=\"b\">\n" +

                   "<table id=\"sb_advancedgeneral\">\n" +
                   "<tr title=\"")
           .append(_t("The version of the I2P software we are running"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/configupdate\"><b>")
           .append(_t("Version"))
           .append("</b></a></td>" +
                   "<td class=\"digits\" align=\"right\">")
           .append(_helper.getVersion())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("How long we've been running for this session"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Uptime"))
           .append("</b></td>" +
                   "<td class=\"digits\" align=\"right\">")
           .append(_helper.getUptime())
           .append("</td></tr>\n");

           if (_context.clock().getOffset() != 0) {
               buf.append("<tr title=\"")
                  .append(_t("Difference between network-synced time and local time"))
                  .append("\">" +
                           "<td align=\"left\"><b>")
                  .append(_t("Clock Skew"))
                  .append("</b></td>" +
                           "<td class=\"digits");
               if (_context.clock().getOffset() > 1000 || _context.clock().getOffset() < -1000)
                   buf.append(" warntext");
               buf.append("\" align=\"right\">")
                  .append(DataHelper.formatDuration2(_context.clock().getOffset()))
                  .append("</td></tr>\n");
           }

           String requestURI = _helper.getRequestURI();
           String page = requestURI.replace("/", "").replace(".jsp", "");
           List<String> sections = _helper.getSummaryBarSections(page);
           if (!sections.contains("MemoryBar")) {
               buf.append("<tr title=\"")
                  .append(_t("How much RAM I2P is using / total RAM available to I2P (excludes RAM allocated to the JVM)"))
                  .append("\">" +
                          "<td align=\"left\"><b>")
                  .append(_t("Memory"))
                  .append("</b></td>" +
                          "<td class=\"digits\" align=\"right\">")
                  .append(_helper.getMemory().replace("iB", ""))
                  .append("</td></tr>\n");
           }
           buf.append("</table>\n");
        return buf.toString();
    }

    /** @since 0.9.32 */
    public String renderMemoryBarHTML() {
        if (_helper == null) return "";
        return _helper.getMemoryBar();
    }

    public String renderNetworkReachabilityHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        SummaryHelper.NetworkStateMessage reachability = _helper.getReachability();
        buf.append("<h4 id=\"sb_status\"><span class=\"sb_netstatus ");
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
        buf.append("\">")
           .append(_t("Status"))
           .append(": ")
           .append(reachability.getMessage())
           .append("</span></h4>\n");
        if (!SigType.ECDSA_SHA256_P256.isAvailable()) {
            buf.append("<hr>\n<h4><span class=\"warn\"><a href=\"http://trac.i2p2.i2p/wiki/Crypto/ECDSA");
            if ("ru".equals(Messages.getLanguage(_context)))
                buf.append("-ru");
            buf.append("\" target=\"_top\" title=\"")
               .append(_t("See more information on the wiki"))
               .append("\">")
               .append(_t("Warning: ECDSA is not available. Update your Java or OS"))
               .append("</a></span></h4>\n");
        }
        if (!SystemVersion.isJava7()) {
            buf.append("<hr><h4><span class=\"warn\">")
               .append(_t("Warning: Java version {0} is no longer supported by I2P.", System.getProperty("java.version")))
               .append(' ')
               .append(_t("Update Java to version {0} or higher to receive I2P updates.", "7"))
               .append("</span></h4>\n");
        }
        return buf.toString();
    }

    public String renderUpdateStatusHTML() {
//        if (_helper == null) return "";
        String updateStatus = _helper.getUpdateStatus();
//        if ("".equals(updateStatus)) return "";
        StringBuilder buf = new StringBuilder(512);
        if (_helper == null || "".equals(updateStatus)) {
            buf.append("<div id=\"sb_updatesection\" class=\"hide\"></div>\n");
        } else {
            buf.append("<div id=\"sb_updatesection\">\n")
               .append("<h3 id=\"sb_updatestatus\"><a href=\"/configupdate\" target=\"_top\" title=\"")
               .append(_t("Configure I2P Updates"))
               .append("\">")
               .append(_t("Update Status"))
               .append("</a></h3>\n<hr class=\"b\">\n")
               .append(updateStatus)
               .append("\n</div>\n");
        }
        return buf.toString();
    }

    public String renderRestartStatusHTML() {
        if (_helper == null) return "";
        return _helper.getRestartStatus();
    }

    public String renderPeersHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/peers\" target=\"_top\" title=\"")
           .append(_t("Show all current peer connections"))
           .append("\">")
           .append(_t("Peers"))
           .append("</a></h3>\n<hr class=\"b\">\n" +

                   "<table id=\"sb_peers\">\n" +

                   "<tr title=\"");
        if (isAdvanced())
            buf.append(_t("Peers we've been talking to in the last few minutes/last hour").replace("last few minutes/last hour", "last minute / last hour"));
        else {
           buf.append(_t("Peers we've been talking to in the last few minutes/last hour").replace("last few minutes/last hour", "last minute"));
        }
        buf.append("\">" +
                   "<td align=\"left\"><a href=\"/peers\"><b>")
           .append(_t("Active"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">");
        int active = _helper.getActivePeers();
        buf.append(active);
        if (isAdvanced()) {
            buf.append(SummaryHelper.THINSP)
           .append(Math.max(active, _helper.getActiveProfiles()));
       }
       buf.append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of peers available for building client tunnels"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/profiles?f=1#profilelist\"><b>")
           .append(_t("Fast"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getFastPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of peers available for building exploratory tunnels"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/profiles?f=1#highcap_peers\"><b>")
           .append(_t("High capacity"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getHighCapacityPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of peers available for network database inquiries"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/netdb?caps=f\"><b>")
           .append(_t("Integrated"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getWellIntegratedPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of peers without a published public IP address"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/netdb?caps=U\"><b>")
           .append(_t("Unreachable"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getUnreachablePeers())
           .append("</td></tr>\n");
       if (_helper.getBanlistedPeers() > 1) {
           buf.append("<tr title=\"")
               .append(_t("The number of banned peers"))
               .append("\">" +
                       "<td align=\"left\"><a href=\"/profiles?f=3\"><b>")
               .append(_t("Banned"))
               .append("</b></a></td><td class=\"digits\" align=\"right\">")
               .append(_helper.getBanlistedPeers())
               .append("</td></tr>\n");
       }

        buf.append("<tr title=\"")
           .append(_t("The total number of peers in our network database"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/netdb\"><b>")
           .append(_t("Known"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getAllPeers())
           .append("</td></tr>\n" +

                   "</table>\n");
        return buf.toString();
    }

    /** @since 0.9.32 */
    public String renderPeersAdvancedHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/peers\" target=\"_top\" title=\"")
           .append(_t("Show all current peer connections"))
           .append("\">")
           .append(_t("Peers"))
           .append("</a></h3>\n<hr class=\"b\">\n" +

                   "<table id=\"sb_peersadvanced\">\n" +

                   "<tr title=\"")
           .append(_t("Peers we've been talking to in the last few minutes/last hour").replace("last few minutes/last hour", "last minute / last hour"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Active"))
           .append("</b></td><td class=\"digits\" align=\"right\">");
        int active = _helper.getActivePeers();
        buf.append(active)
           .append(SummaryHelper.THINSP)
           .append(Math.max(active, _helper.getActiveProfiles()))
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of peers available for building client tunnels"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Fast"))
           .append("</b></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getFastPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of peers available for building exploratory tunnels"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("High capacity"))
           .append("</b></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getHighCapacityPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of peers available for network database inquiries"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Integrated"))
           .append("</b></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getWellIntegratedPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of peers without a published public ip address in our network database"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Unreachable"))
           .append("</b></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getUnreachablePeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The total number of peers in our network database"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/netdb\"><b>")
           .append(_t("Known"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getAllPeers())
           .append("</td></tr>\n" +

                   "<tr class=\"separator\"><td colspan=\"2\"><hr></td></tr>" +

                   "<tr title=\"")
           .append(_t("The number of peers failing network tests"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/profiles\"><b>")
           .append(_t("Failing"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getFailingPeers())
           .append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("The number of banned peers"))
           .append("\">" +
                   "<td align=\"left\"><a href=\"/profiles?f=3\"><b>")
           .append(_t("Banned"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getBanlistedPeers())
           .append("</td></tr>\n" +

                   "</table>\n");
        return buf.toString();
    }

    public String renderFirewallAndReseedStatusHTML() {
        if (_helper == null) return "";
        return _helper.getFirewallAndReseedStatus();
    }

    public String renderBandwidthHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/config\" title=\"")
           .append(_t("Configure router bandwidth allocation"))
           .append("\" target=\"_top\">")
           .append(_t("Bandwidth in/out"))
           .append("</a></h3>\n<hr class=\"b\">" +
                   "<table id=\"sb_bandwidth\">\n" +

                   "<tr><td align=\"left\"><b>")
           .append(DataHelper.formatDuration2(3 * 1000).replace("3&nbsp;sec", _t("Current")))  // lie and say 3 sec since 1 sec would appear as 1000 ms
           .append("</b></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getSecondKBps())
           .append("Bps</td></tr>\n");

        if (_context.router().getUptime() > 6*60*1000) {
            buf.append("<tr><td align=\"left\"><b>")
           .append(DataHelper.formatDuration2(5 * 60 * 1000).replace("5&nbsp;min", _t("5 Min Average")))   // 5 min
           .append("</b></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getFiveMinuteKBps())
           .append("Bps</td></tr>\n");
        }

        if (_context.router().getUptime() > 2*60*1000) {
            buf.append("<tr><td align=\"left\"><b>")
           .append(_t("Total").replace("Total", _t("Lifetime")))
           .append("</b></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getLifetimeKBps())
           .append("Bps</td></tr>\n");
        }

        buf.append("<tr><td align=\"left\"><b>")
           .append(_t("Used").replace("Used", "Transferred"))
           .append("</b></td><td class=\"digits\" align=\"right\">")
           .append(_helper.getInboundTransferred().replace("KiB", "K").replace("MiB", "M").replace("GiB", "G")
                                                  .replace("TiB", "T").replace("PiB", "P").replace("EiB", "E")
                                                  .replace("ZiB", "Z").replace("YiB", "Y"))
           .append(SummaryHelper.THINSP)
           .append(_helper.getOutboundTransferred().replace("KiB", "K").replace("MiB", "M").replace("GiB", "G")
                                                    .replace("TiB", "T").replace("PiB", "P").replace("EiB", "E")
                                                    .replace("ZiB", "Z").replace("YiB", "Y"))
           .append("</td></tr>\n" +

                   "</table>\n");
        return buf.toString();
    }

    /** @since 0.9.32 */
    public String renderBandwidthGraphHTML() {
        if (_helper == null) return "";
        if (StatSummarizer.isDisabled(_context))
            return "";
        String r = _context.getProperty(CSSHelper.PROP_REFRESH, CSSHelper.DEFAULT_REFRESH);
        int refreshPeriod = 3;
        try {
          refreshPeriod = Integer.parseInt(r);
        } catch (NumberFormatException nfe) {}
        StringBuilder buf = new StringBuilder(512);
        buf.append("<div id=\"sb_graphcontainer\" title=\"")
           .append(_t("Our inbound &amp; outbound traffic for the last 20 minutes"))
           .append("\">\n<span id=\"sb_graphstats\">")
           .append(_helper.getSecondKBps())
           .append("Bps</span>\n")
           .append("<a href=\"/graphs\">\n");
        buf.append("<canvas id=\"minigraph\" width=\"245\" height=\"50\">\n")
           .append("<div id=\"minigraphcontainer\" style=\"background-image: url(/viewstat.jsp?stat=bw.combined")
           .append("&amp;periodCount=20&amp;width=250&amp;height=50&amp;hideLegend=true&amp;hideGrid=true&amp;")
           .append("hideTitle=true&amp;time=").append(_context.clock().now() / 1000).append("\"></div>")
           .append("</canvas>\n")
           .append("<noscript><div id=\"minigraphcontainer\" style=\"background-image: url(/viewstat.jsp?stat=bw.combined")
           .append("&amp;periodCount=20&amp;width=250&amp;height=50&amp;hideLegend=true&amp;hideGrid=true&amp;")
           .append("hideTitle=true&amp;time=").append(_context.clock().now() / 1000).append("\"></div></noscript>");
        buf.append("</a>\n</div>\n");
        return buf.toString();
    }

    public String renderTunnelsHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/tunnels\" target=\"_top\" title=\"")
           .append(_t("View existing tunnels and tunnel build status"))
           .append("\">")
           .append(_t("Tunnels"))
           .append("</a></h3>\n<hr class=\"b\">\n" +
                   "<table id=\"sb_tunnels\">\n");
        if (_helper.getInboundClientTunnels() > 0 || _helper.getOutboundClientTunnels() > 0) {
            buf.append("<tr title=\"")
               .append(_t("Tunnels we are using to provide or access services on the network")).append(" (").append(_t("inbound / outbound")).append(")")
               .append("\">" +
                       "<td align=\"left\"><a href=\"/tunnels\"><b>")
               .append(_t("Client"))
               .append("</b></a></td><td class=\"digits\" align=\"right\">")
//               .append(_helper.getInboundClientTunnels() + _helper.getOutboundClientTunnels())
               .append(_helper.getInboundClientTunnels()).append(" / ").append(_helper.getOutboundClientTunnels())
               .append("</td></tr>\n");
        }

        buf.append("<tr title=\"")
           .append(_t("Used for building and testing tunnels, and communicating with floodfill peers")).append(" (").append(_t("inbound / outbound")).append(")")
           .append("\">" +
                   "<td align=\"left\"><a href=\"/tunnels#exploratory\"><b>")
           .append(_t("Exploratory"))
           .append("</b></a></td><td class=\"digits\" align=\"right\">")
//           .append(_helper.getInboundTunnels() + _helper.getOutboundTunnels())
           .append(_helper.getInboundTunnels()).append(" / ").append(_helper.getOutboundTunnels())
           .append("</td></tr>\n");

           String maxTunnels = _context.getProperty("router.maxParticipatingTunnels");
           RouterInfo ri = _context.router().getRouterInfo();
           int partTunnels = _helper.getParticipatingTunnels();
//           if (partTunnels >= 2)
//               partTunnels = partTunnels - 1; // fix duplicate display
           if ((maxTunnels == null || partTunnels > 0
               || Integer.valueOf(maxTunnels) > 0) && !_context.router().isHidden() && ri != null && !ri.getBandwidthTier().equals("K")
               && partTunnels > 0) {
               buf.append("<tr title=\"")
                  .append(_t("Tunnels we are participating in, directly contributing bandwidth to the network"))
                  .append("\">" +
                          "<td align=\"left\"><a href=\"/tunnelsparticipating\"><b>")
                  .append(_t("Participating"))
                  .append("</b></a></td><td class=\"digits\" align=\"right\">")
                  .append(partTunnels)
                  .append("</td></tr>\n");
           }
           buf.append("<tr title=\"")
              .append(_t("Total number of tunnels in use"))
              .append("\">" +
                      "<td align=\"left\"><a href=\"/tunnelpeercount\"><b>")
              .append(_t("Total"))
              .append("</b></a></td><td class=\"digits\" align=\"right\">")
              .append(_helper.getInboundTunnels() + _helper.getOutboundTunnels() +
                      _helper.getInboundClientTunnels() + _helper.getOutboundClientTunnels() +
                      partTunnels)
              .append("</td></tr>\n");

           buf.append("<tr title=\"")
              .append(_t("Concurrent tunnel builds (averaged over a minute) / single tunnel build time"))
              .append("\">" +
                      "<td align=\"left\"><b>")
              .append(_t("Concurrency"))
              .append("</b></td><td class=\"digits\" align=\"right\">")
              .append(_helper.getConcurrency())
              .append("</td></tr>\n");

           if ((maxTunnels == null || Integer.valueOf(maxTunnels) > 0) && !_context.router().isHidden() && ri != null &&
                !ri.getBandwidthTier().equals("K") && !_helper.getShareRatio().toString().equals("0")) {
               buf.append("<tr title=\"")
                  .append(_t("The ratio of tunnel hops we provide to tunnel hops we use - a value greater than 1.00 indicates a positive contribution to the network"))
                  .append("\">" +
                          "<td align=\"left\"><b>")
                  .append(_t("Share ratio"))
                  .append("</b></td><td class=\"digits\" align=\"right\">")
                  .append(_helper.getShareRatio())
                  .append("</td></tr>\n");
           }

           buf.append("</table>\n");
        return buf.toString();
    }

    public String renderCongestionHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<h3><a href=\"/jobs\" target=\"_top\" title=\"")
           .append(_t("What's in the router's job queue?"))
           .append("\">")
           .append(_t("Congestion"))
           .append("</a></h3>\n<hr class=\"b\">\n" +
                   "<table id=\"sb_queue\">\n" +

                   "<tr title=\"")
           .append(_t("Indicates router performance"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Job lag"))
           .append("</b></td><td class=\"digits\" align=\"right\">");
        if (_context.jobQueue().getMaxLag() > 1000) {
            buf.append("<span class=\"warntext\">").append(_helper.getJobLag()).append("</span>");
        } else {
            buf.append(_helper.getJobLag());
        }
        buf.append("</td></tr>\n" +

                   "<tr title=\"")
           .append(_t("Indicates how quickly outbound messages to other I2P routers are sent"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Message delay"))
           .append("</b></td><td class=\"digits\" align=\"right\">");
        if (_context.throttle().getMessageDelay() > 2000) {
            buf.append("<span class=\"warntext\">").append(_helper.getMessageDelay()).append("</span>");
        } else {
            buf.append(_helper.getMessageDelay());
        }
        buf.append("</td></tr>\n");

        if (_context.getProperty("routerconsole.showPeerTestAvg") != null &&
            _context.getBooleanProperty("routerconsole.showPeerTestAvg")) {
            buf.append("<tr title=\"")
               .append(_t("Average time to test a peer (successful / total)"))
               .append("\">" +
                       "<td align=\"left\"><b>")
               .append(_t("Peer test average"))
               .append("</b></td><td class=\"digits\" align=\"right\">")
               .append(_helper.getAvgPeerTestTimeGood()).append(" / " )
               .append(_helper.getAvgPeerTestTime());
            if (_helper.getAvgPeerTestTime() > 0)
                buf.append("&nbsp;ms");
            buf.append("</td></tr>\n");
        }

        if (_context.getProperty("router.disableTunnelTesting") == null ||
            !_context.getBooleanProperty("router.disableTunnelTesting")) {
            buf.append("<tr title=\"")
               .append(_t("Round trip time for a tunnel test"))
               .append("\">" +
                       "<td align=\"left\"><b>")
               .append(_t("Tunnel lag"))
               .append("</b></td><td class=\"digits\" align=\"right\">")
               .append(_helper.getTunnelLag())
               .append("</td></tr>\n");
        }

        if (Integer.parseInt(_helper.getInboundBacklog()) > 0) {
        buf.append("<tr title=\"")
           .append(_t("Queued requests from other routers to participate in tunnels"))
           .append("\">" +
                   "<td align=\"left\"><b>")
           .append(_t("Backlog"))
           .append("</b></td><td class=\"digits\" align=\"right\"><span class=\"warntext\">")
           .append(_helper.getInboundBacklog())
           .append("</span></td></tr>\n");
        }
        buf.append("</table>\n");
        return buf.toString();
    }

    public String renderTunnelStatusHTML() {
        if (_helper == null) return "";
        StringBuilder buf = new StringBuilder(50);
        buf.append("<h4 id=\"sb_tunnelstatus\"><span class=\"tunnelBuildStatus");
        if (_t(_helper.getTunnelStatus()).contains(("Starting up")))
            buf.append(" starting\" title=\"").append(_t("No participating tunnels requests are accepted for the first 10 minutes while router stabilizes"));
        if (_t(_helper.getTunnelStatus()).contains(("Shutting down")))
            buf.append(" rejecting\" title=\"").append(_t("Declining participating tunnel requests as we are shutting down"));
        if (_t(_helper.getTunnelStatus()).contains(("Declining")) && !(_t(_helper.getTunnelStatus()).contains(("Shutting down"))) &&
          !(_t(_helper.getTunnelStatus()).contains(("Participation disabled"))))
            buf.append(" rejecting\" title=\"").append(_t("Router or network performance is impeding the building of participating tunnels"));
        if (_t(_helper.getTunnelStatus()).contains(("Dropping")) && !(_t(_helper.getTunnelStatus()).contains(("Shutting down"))))
            buf.append(" rejecting\" title=\"").append(_t("Router or network performance is impeding the building of participating tunnels"));
        if (_t(_helper.getTunnelStatus()).contains(("Declining")) && (_t(_helper.getTunnelStatus()).contains(("Participation disabled"))))
            buf.append(" rejecting disabled\" title=\"").append(_t("Router is configured to reject all participating tunnel requests"));
        if (_t(_helper.getTunnelStatus()).contains(("Accepting")) && (_helper.getParticipatingTunnels() <= 0))
            buf.append(" accepting\" title=\"").append(_t("Router is ready to build participating tunnels"));
        if (_t(_helper.getTunnelStatus()).contains(("Accepting")) && (_helper.getParticipatingTunnels() > 0))
            buf.append(" active\" title=\"").append(_t("Accepting participating tunnel requests; participating in {0} tunnels", _helper.getParticipatingTunnels()));
        else if (_t(_helper.getTunnelStatus()).contains(("Hidden Mode")))
            buf.append(" hidden\" title=\"").append(_t("No participating tunnels are built when Hidden mode is active"));
        buf.append("\">")
           .append(_helper.getTunnelStatus())
           .append("</span></h4>\n");
        return buf.toString();
    }

    public String renderDestinationsHTML() {
        if (_helper == null) return "";
        return _helper.getDestinations();
    }

    /** @since 0.9.1 */
    public String renderNewsHeadingsHTML() {
        if (_helper == null) return "";
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
                if (nmgr != null)
                    entries = nmgr.getEntries();
            }
            if (!entries.isEmpty()) {
                int i = 0;
                // show a min of 1, max of 3, none older than 60 days over min
                // Except, if news fetching is disabled, min is 0 and oldest is 7 days.
                // We still show news even if disabled, because the user could click it manually
                String freq = _context.getProperty(ConfigUpdateHandler.PROP_REFRESH_FREQUENCY,
                                                   ConfigUpdateHandler.DEFAULT_REFRESH_FREQUENCY);
                long ms = ConfigUpdateHandler.DEFAULT_REFRESH_FREQ;
                try {
                    ms = Long.parseLong(freq);
                } catch (NumberFormatException nfe) {}
                final int min = (ms > 0) ? 1 : 0;
                final int max = 3;
                final long age = (ms > 0) ? 60*24*60*60*1000L : 7*24*60*60*1000L;
                final long oldest = _context.clock().now() - age;
                for (NewsEntry entry : entries) {
                    if (i >= min && entry.updated > 0 &&
                        entry.updated < oldest)
                        break;
                    if (i == 0) {
                        // Set up title and pre-headings stuff.
                        buf.append("<h3><a href=\"/news\">")
                           .append(_t("News &amp; Updates"))
                           .append("</a></h3><hr class=\"b\"><div id=\"sb_newsheadings\">\n<table>\n");
                    }
                    buf.append("<tr><td><a href=\"/?news=1&amp;consoleNonce=")
                       .append(consoleNonce)
                       .append("\"");
                    if (entry.updated > 0) {
                        buf.append(" title=\"")
                           .append(_t("Published")).append(": ").append(DataHelper.formatDate(entry.updated)).append("\"");
                    }
                    buf.append(">");
                    buf.append(entry.title)
                       .append("</a></td></tr>\n");
                    if (++i >= max)
                        break;
                }
                if (i > 0)
                    buf.append("</table>\n</div>\n");
            }
        }
        return buf.toString();
    }

    /** tag only */
    private static final String _x(String s) {
        return s;
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** @since 0.9.23 */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /**
     *  Where the translation is to two words or more,
     *  prevent splitting across lines
     *
     *  @since 0.9.18
     */
    private static String nbsp(String s) {
        // if it's too long, this makes it worse
        if (s.length() <= 30)
            return s.replace(" ", "&nbsp;");
        else
            return s;
    }
}
