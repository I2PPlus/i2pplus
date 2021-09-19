package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.web.App;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NavHelper;
import net.i2p.router.web.PluginStarter;
import net.i2p.router.web.WebAppStarter;
import net.i2p.util.PortMapper;

import net.i2p.router.web.CSSHelper;


/**
 *  For /home and /confighome
 *
 *  @since 0.9
 */
public class HomeHelper extends HelperBase {

    private static final char S = ',';
    private static final String I = "/themes/console/images/";
    static final String PROP_SERVICES = "routerconsole.services";
    static final String PROP_FAVORITES = "routerconsole.favorites";
    static final String PROP_CONFIG = "routerconsole.configopts";
    static final String PROP_MONITORING = "routerconsole.monitoring";
    static final String PROP_OLDHOME = "routerconsole.oldHomePage";
    static final String PROP_SEARCH = "routerconsole.showSearch";
    /** @since 0.9.35 */
//    static final String PROP_NEWTAB = "routerconsole.homeExtLinksToNewTab";

    // No commas allowed in text strings!
    static final String DEFAULT_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "svg/addressbook.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "svg/graphs.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "svg/mail.svg" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help" + S + I + "svg/help.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "svg/pluginconfig.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "svg/globe.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "svg/info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/logs" + S + I + "svg/logs.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "svg/sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "svg/snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/i2ptunnelmgr" + S + I + "svg/tunnelmanager.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "svg/webserver.svg" + S +
        "";

    static final String NEWINSTALL_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "svg/addressbook.svg" + S +
        _x("Configure Bandwidth") + S + _x("I2P Bandwidth Configuration") + S + "/config" + S + I + "svg/speedometer.svg" + S +
        // FIXME wasn't escaped
        _x("Configure UI") + S + _x("Select console theme & language & set optional console password").replace("&", "&amp;") + S + "/configui" + S + I + "svg/ui.svg" + S +
        _x("Customize Sidebar") + S + _x("Customize the sidebar by adding or removing or repositioning elements") + S + "/configsidebar" + S + I + "svg/sidebar.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "svg/graphs.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "svg/mail.svg" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help" + S + I + "svg/help.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "svg/pluginconfig.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "svg/globe.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "svg/info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/logs" + S + I + "svg/logs.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "svg/sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "svg/snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/i2ptunnelmgr" + S + I + "svg/tunnelmanager.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "svg/webserver.svg" + S +
        _x("Wizard") + S + _x("Configuration and bandwidth tester") + S + "/wizard" + S + I + "svg/wizard.svg" + S +
        "";

    static final String ADVANCED_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "svg/addressbook.svg" + S +
        _x("Advanced Config") + S + _x("Advanced router configuration") + S + "/configadvanced" + S + I + "svg/configure.svg" + S +
        _x("Changelog") + S + _x("Recent changes") + S + "/help/changelog" + S + I + "svg/changelog.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "svg/graphs.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "svg/mail.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "svg/pluginconfig.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "svg/globe.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "svg/info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/logs" + S + I + "svg/logs.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "svg/sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "svg/snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/i2ptunnelmgr" + S + I + "svg/tunnelmanager.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "svg/webserver.svg" + S +
        "";

    // No commas allowed in text strings!
    static final String DEFAULT_FAVORITES =

// I2P specific
        _x("I2P Bug Reports") + S + _x("Bug tracker") + S + "http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues" + S + I + "svg/bug.svg" + S +
        _x("I2P FAQ") + S + _x("Frequently Asked Questions") + S + "http://i2p-projekt.i2p/faq" + S + I + "svg/faq.svg" + S +
        _x("I2P Plugins") + S + _x("zzz's plugin repository") + S + "http://stats.i2p/i2p/plugins/" + S + I + "svg/plugin.svg" + S +
        "i2pmetrics.i2p" + S + _x("Historical infrastructure data from the I2P network") + S + "http://i2pmetrics.i2p/" + S + I + "svg/stats.svg" + S +
        _x("Project Website") + S + _x("I2P home page") + S + "http://i2p-projekt.i2p/" + S + I + "svg/i2p.svg" + S +
        "stats.i2p" + S + _x("I2P Network Statistics") + S + "http://stats.i2p/cgi-bin/dashboard.cgi" + S + I + "svg/stats.svg" + S +
        _x("Javadocs") + S + _x("I2P+ API documentation") + S + "http://javadoc.skank.i2p/" + S + I + "svg/helplink.svg" + S +

// software repositories + filesharing
        "cake.i2p" + S + _x("Transient server-encrypted filesharing and pastebin") + S + "http://cake.i2p/" + S + I + "svg/cake.svg" + S +
        "fs.i2p" + S + _x("Secure filesharing service") + S + "http://fs.i2p/" + S + I + "svg/cloud.svg" + S +
        "git.i2p" + S + _x("Community Git hosting") + S + "http://git.i2p/explore/repos" + S + I + "svg/git.svg" + S +
        "git.idk.i2p" + S + _x("Official I2P Git repository") + S + "http://git.idk.i2p/explore/projects" + S + I + "svg/gitlab.svg" + S +
        "sharefile.i2p" + S + _x("Secure filesharing service") + S + "http://sharefile.i2p/" + S + I + "svg/cloud.svg" + S +
        "skank.i2p" + S + _x("Home of I2P+") + S + "http://skank.i2p/" + S + I + "svg/plus.svg" + S +

// torrent trackers
        _x("Chudo") + S + _x("No login Bittorrent tracker") + S + "http://torrents.chudo.i2p/" + S + I + "svg/magnet.svg" + S +
        _x("Postman's Tracker") + S + _x("Bittorrent tracker") + S + "http://tracker2.postman.i2p/" + S + I + "svg/postman.svg" + S +

// domain name registration + uptime trackers
        "Identiguy" + S + _x("List of active eepsites and uptime monitor") + S + "http://identiguy.i2p/" + S + I + "svg/servermonitor.svg" + S +
        "notbob.i2p" + S + _x("List of active eepsites and uptime monitor") + S + "http://notbob.i2p/" + S + I + "notbob.png" + S +
        "reg.i2p" + S + _x("I2P Domain Name Registration") + S + "http://reg.i2p/" + S + I + "svg/reg.svg" + S +
        _x("zzz Domain Registry") + S + _x("I2P Domain Name Registration") + S + "http://stats.i2p/i2p/addkey.html" + S + I + "svg/registrar.svg" + S +

// forums + social
        _x("Dev Forum") + S + _x("Development forum") + S + "http://zzz.i2p/" + S + I + "svg/forum.svg" + S +
        _x("I2P Forum") + S + _x("I2P-related community forums") + S + "http://i2pforum.i2p/" + S + I + "svg/forum.svg" + S +
        "nitter" + S + _x("Alternative front-end for Twitter") + S + "http://nitter.swurl.i2p/" + S + I + "svg/twitter.svg" + S +
        _x("novabbs.i2p") + S + _x("Rocksolid forums for the darknets") + S + "http://novabbs.i2p/" + S + I + "svg/forum.svg" + S +
        "ramble.i2p" + S + _x("Cross-network micro-blogging &amp; forums &amp; wiki") + S + "http://ramble.i2p/" + S + I + "svg/ramble.svg" + S +
        "teddit.i2p" + S + _x("Alternative privacy-focused front-end for Reddit") + S + "http://teddit.i2p/" + S + I + "svg/teddit.svg" + S +
        "zeronet.i2p" + S + _x("Zeronet I2P Gateway") + S + "http://zeronet.i2p/" + S + I + "svg/zeronet.svg" + S +
        //_x("Dancing Elephants") + S + _x("Rocksolid forums for the darknets") + S + "http://def3.i2p/" + S + I + "svg/forum.svg" + S +
        //"query.i2p" + S + _x("The StackOverflow of I2P") + S + "http://query.i2p/" + S + I + "svg/forum.svg" + S +

// hosting + other services
        _x("Pastebin") + S + _x("Encrypted I2P Pastebin") + S + "http://paste.r4sas.i2p/" + S + I + "svg/paste.svg" + S +
        _x("radio.r4sas.i2p") + S + _x("Streaming radio service") + S + "http://radio.r4sas.i2p/" + S + I + "svg/radio.svg" + S +
           "tube.i2p" + S + _x("Alternative front-end to Youtube") + S + "http://tube.i2p/" + S + I + "svg/tv.svg" + S +
           "speedtest" + S + _x("Outproxy bandwidth test") + S + "http://outproxy.purokishi.i2p/speedtest/" + S + I + "svg/speedometer.svg" + S +
           "webhosting.i2p" + S + _x("Provider of privacy respecting web hosting and VPN services") + S + "http://webhosting.i2p/" + S + I + "svg/incog.svg" + S +
        //_x("Deep Web Radio") + S + _x("Streaming radio service") + S + "http://deepwebradio.i2p/" + S + I + "svg/radio.svg" + S +

// search engines
        "Legwork" + S +_x("I2P Web Search Engine") + S + "http://legwork.i2p/" + S + I + "svg/search.svg" + S +
        "Ransack" + S +_x("I2P-based Metasearch Engine") + S + "http://ransack.i2p/" + S + I + "svg/search.svg" + S +

// wikis & reference
        "ebooks.i2p" + S + _x("Huge collection of books &amp; magazines &amp; comics") + S + "http://ebooks.i2p/" + S + I + "svg/books.svg" + S +
        "I2P Wiki" + S + _x("I2P-related Wiki") + S + "http://wiki.i2p-projekt.i2p/" + S + I + "svg/wiki.svg" + S +
        "jalibrary.i2p" + S + _x("Books &amp; courses &amp art") + S + "http://jalibrary.i2p/" + S + I + "svg/books.svg" + S +
        "nexus.i2p" + S + _x("Searchable science library") + S + "http://nexus.i2p/" + S + I + "svg/nexus.svg" + S +
        _x("Psychonaut") + S + _x("Wiki relating to altered states of consciousness") + S + "http://psy.i2p/" + S + I + "svg/psy.svg" + S +
        "tome.i2p" + S + _x("Collection of books &amp; other reading material") + S + "http://tome.i2p/" + S + I + "svg/books.svg" + S +

// news + blogs
        "LinuxFarm" + S + _x("Router performance monitoring and Linux tips") + S + "http://linuxfarm.i2p/" + S + I + "svg/linuxfarm.svg" + S +
        _x("Planet I2P") + S + _x("I2P News") + S + "http://planet.i2p/" + S + I + "svg/planet.svg" + S +

        "";


    public boolean shouldShowWelcome() {
        return _context.getProperty(Messages.PROP_LANG) == null;
    }

    /* @since 0.9.52+ */
    public boolean shouldShowBandwidthConfig() {
        return _context.getProperty("i2np.bandwidth.outboundKBytesPerSecond") == null;
    }

    public boolean shouldShowSearch() {
        return _context.getBooleanProperty(PROP_SEARCH);
    }

    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
    }

    public String getServices() {
        List<App> plugins = NavHelper.getClientApps(_context);
        net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
        String version = net.i2p.CoreVersion.VERSION;
        String firstVersion = ctx.getProperty("router.firstVersion");
        if (!isAdvanced() && version.equals(firstVersion)) {
            return homeTable(PROP_SERVICES, NEWINSTALL_SERVICES, plugins);
        } else if (!version.equals(firstVersion) && !isAdvanced()) {
            return homeTable(PROP_SERVICES, DEFAULT_SERVICES, plugins);
        } else {
            return homeTable(PROP_SERVICES, ADVANCED_SERVICES, plugins);
        }
    }

    public String getFavorites() {
        return homeTable(PROP_FAVORITES, DEFAULT_FAVORITES, null);
    }

    public String getConfigServices() {
        return configTable(PROP_SERVICES, DEFAULT_SERVICES);
    }

    public String getConfigFavorites() {
        return configTable(PROP_FAVORITES, DEFAULT_FAVORITES);
    }

    public String getConfigSearch() {
        return configTable(SearchHelper.PROP_ENGINES, SearchHelper.ENGINES_DEFAULT);
    }

    public String getConfigHome() {
        return getChecked(PROP_OLDHOME);
    }

//    public boolean homeExtLinksToNewTab() {
//        return _context.getBooleanProperty(PROP_NEWTAB);
//    }

    public String getProxyStatus() {
        int port = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY);
        if (port <= 0)
            return _t("The HTTP proxy is not up");
        return "<img src=\"http://console.i2p/onepixel.png?" + _context.random().nextInt() + "\"" +
                " alt=\"" + _t("Your browser is not properly configured to use the HTTP proxy at {0}",
                _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST) + ':' + port) +
                "\">";
    }

    private String homeTable(String prop, String dflt, Collection<App> toAdd) {
        String config = _context.getProperty(prop, dflt);
        Collection<App> apps = buildApps(_context, config);
        if (toAdd != null)
            apps.addAll(toAdd);
        return renderApps(apps);
    }

    private String configTable(String prop, String dflt) {
        String config = _context.getProperty(prop, dflt);
        Collection<App> apps;
        if (prop.equals(SearchHelper.PROP_ENGINES))
            apps = buildSearchApps(config);
        else
            apps = buildApps(_context, config);
        return renderConfig(apps);
    }

    private static final String SS = Character.toString(S);

    static Collection<App> buildApps(RouterContext ctx, String config) {
        String[] args = DataHelper.split(config, SS);
        Set<App> apps = new TreeSet<App>(new AppComparator());
        for (int i = 0; i < args.length - 3; i += 4) {
            String name = Messages.getString(args[i], ctx);
            String desc = Messages.getString(args[i+1], ctx);
            String url = args[i+2];
            String icon = args[i+3];
            apps.add(new App(name, desc, url, icon));
        }
        return apps;
    }

    static Collection<App> buildSearchApps(String config) {
        String[] args = DataHelper.split(config, SS);
        Set<App> apps = new TreeSet<App>(new AppComparator());
        for (int i = 0; i < args.length - 1; i += 2) {
            String name = args[i];
            String url = args[i+1];
            apps.add(new App(name, null, url, null));
        }
        return apps;
    }

    static void saveApps(RouterContext ctx, String prop, Collection<App> apps, boolean full) {
        StringBuilder buf = new StringBuilder(1024);
        for (App app : apps) {
            buf.append(app.name).append(S);
            if (full)
                buf.append(app.desc).append(S);
            buf.append(app.url).append(S);
            if (full)
                buf.append(app.icon).append(S);
        }
        ctx.router().saveConfig(prop, buf.toString());
    }

    private String renderApps(Collection<App> apps) {
        String website = _t("Web Server");
        StringBuilder buf = new StringBuilder(1024);
        boolean embedApps = _context.getBooleanProperty(CSSHelper.PROP_EMBED_APPS);
        buf.append("<div class=\"appgroup\">");
        PortMapper pm = _context.portMapper();
        for (App app : apps) {
            String url;
            if (app.name.equals(website) && app.url.equals("http://127.0.0.1:7658/")) {
                // fixup eepsite link
                url = SummaryBarRenderer.getEepsiteURL(pm);
                if (url == null)
                    continue;
            // embed plugins in the console
            } else if ((app.url.contains("bote") && !app.url.contains(".i2p") && (embedApps))) {
                url = "/embed?url=/i2pbote&amp;name=BoteMail";
            } else if ((app.url.contains("BwSchedule") && (embedApps))) {
                url = "/embed?url=/BwSchedule/home&amp;name=Bandwidth+Scheduler";
            // if external links set to open in new tab, add class so we can indicate external links with overlay
            // plugins need to be manually added
            } else if (((app.url.contains("webmail") || (app.url.contains("torrents"))) && (!embedApps))
                    || ((app.url.contains("bote") || (app.url.contains("orchid") || (app.url.contains("BwSchedule"))
                    || ((app.url.contains(".i2p"))) || (app.url.contains("history.txt")))))) {
                url = app.url + "\" target=\"_blank\" class=\"extlink";
            } else {
                url = app.url;
                // check for disabled webapps and other things
                if (url.equals("/dns")) {
                    if (!pm.isRegistered("susidns"))
                        continue;
                } else if (url.equals("/webmail")) {
                    if (!pm.isRegistered("susimail"))
                        continue;
                } else if (url.equals("/torrents")) {
                    if (!pm.isRegistered("i2psnark"))
                        continue;
                } else if (url.equals("/configplugins")) {
                    if (!PluginStarter.pluginsEnabled(_context))
                        continue;
                }
            }
            buf.append("\n<div class=\"app");
            // tag sites that require javascript to function
            if (url.contains("i2pmetrics") || url.contains("paste.r4sas") || url.contains("zeronet") || url.contains("speedtest"))
                buf.append(" js");
            buf.append("\" style=\"display: inline-block; text-align: center;\">\n" +
                       "<div class=\"appimg\">" +
                       // usability: add tabindex -1 so we avoid 2 tabs per app
                       "<a href=\"").append(url).append("\" tabindex=\"-1\">" +
                       "<img alt=\"\" title=\"").append(app.desc).append("\" style=\"max-width: 32px; max-height: 32px;\" src=\"").append(app.icon)
               // version the icons because they may change
               .append(app.icon.contains("?") ? "&amp;" : "?").append(CoreVersion.VERSION).append("\"></a>" +
                       "</div>\n" +
                       "<table><tr><td>" +
                       "<div class=\"applabel\">" +
                       "<a href=\"").append(url).append("\" title=\"").append(app.desc).append("\">").append(app.name).append("</a>" +
                       "</div>" +
                       "</td></tr></table>\n" +
                       "</div>");
            }
            buf.append("</div>\n");
            return buf.toString();
        }

    private String renderConfig(Collection<App> apps) {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table class=\"homelinkedit\"><tr><th title=\"")
           .append(_t("Mark for deletion"))
           .append("\">")
           .append(_t("Remove"))
           .append("</th><th></th><th>")
           .append(_t("Name"))
           .append("</th><th>")
           .append(_t("URL"))
           .append("</th></tr>\n");
        for (App app : apps) {
            buf.append("<tr><td align=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"delete_")
               .append(app.name)
               .append("\" id=\"")
               .append(app.name.replace(" ", "_"))
               .append("\"></td>");
            if (app.icon != null) {
                buf.append("<td align=\"center\"><img height=\"16\" alt=\"\" src=\"").append(app.icon).append("\">");
            } else {
                buf.append("<td align=\"center\" class=\"noicon\">");
            }
            buf.append("</td><td align=\"left\"><label for=\"")
               .append(app.name.replace(" ", "_"))
               .append("\">")
               .append(DataHelper.escapeHTML(app.name))
               .append("</label></td><td align=\"left\"><a href=\"");
            String url = DataHelper.escapeHTML(app.url);
            buf.append(url)
               .append("\">");
            // truncate before escaping
            String urltext = DataHelper.escapeHTML(app.url).replace("&amp;ref=console", "");
            if (app.url.length() > 72)
                buf.append(urltext.substring(0, 70)).append("&hellip;");
            else
                buf.append(urltext);
            buf.append("</a></td></tr>\n");
        }
        buf.append("<tr id=\"addnew\"><td colspan=\"2\" align=\"center\"><b>")
           .append(_t("Add")).append(":</b>" +
                   "</td><td align=\"left\"><input type=\"text\" name=\"nofilter_name\"></td>" +
                   "<td align=\"left\"><input type=\"text\" size=\"40\" name=\"nofilter_url\"></td></tr>");
        buf.append("</table>\n");
        return buf.toString();
    }

    /** ignore case, current locale */
    private static class AppComparator implements Comparator<App>, Serializable {
        public int compare(App l, App r) {
            return l.name.toLowerCase().compareTo(r.name.toLowerCase());
        }
    }
}
