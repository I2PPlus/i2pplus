package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.CoreVersion;
import net.i2p.app.ClientAppManager;
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
    private static final String PROP_SEARCH = "routerconsole.showSearch";
    private static final String bottomWrap = "<br>\n" +
            "<div class=\"clearer\">&nbsp;</div>\n" +
                "</div>\n" + 
                "</div>\n";

/*

Policy adapted from http://zzz.i2p/topics/236 last updated there 2016-08.
Subject to change without notice.

How to get my Eepsite added to the Router Console home page

If your site is:

- Broadly useful, in good taste, and of general interest to the I2P community
- Is not an image board (chan)
- Is not a general image host or file host unless it has strict editorial control and TOS (but even so we probably won't accept it)
- If it contains user-generated content, or is a forum, tracker, file host, wiki, or anything allowing user comments,
  it has English (and native language if different) terms of service posted that prohibit extremely inappropriate things.
- If it is an index of i2p sites, it does not link to sites that are extremely inappropriate.
- Reliably up for a few months at least
- Will be up 24/7 except for maintenance, updates, etc
- Decently fast (home DSL/cable is fine)
- On a router running the current release

...then contact a dev.

** You MUST include the following info ** :

- Affirm that you are the owner of the site in question. We do not add links to sites without permission.
- The hostname
- The registration authentication string, or the registration site or feed where it is available
- An email address
- The URL to link to
- The URL of your English terms of service if available or necessary
- If the site is not in English, a brief description of the site in English
- A URL to a 32x32 transparent png icon to display. We will copy this icon into the router console source and serve it locally.
  64x64 is ok also but it will be scaled to 32x32.
- The license of the icon.
- (Optional) A one or two-word label in English. If not provided we will use example.i2p
- (Optional) a few words or a sentence in English for a popup (tooltip)
- Affirm that you will regularly update the router to the latest release

Translations will happen through our normal translation process.

Other criteria:

Your site should not require browsers to load clearnet resources.
Please test your site to ensure that clearnet Javascript, CSS, fonts and images are not embedded.
Your submission may be rejected for this reason.

All decisions on inclusion will be made at a dev meeting, generally in IRC #i2p-dev
on a Tuesday at 8 PM UTC about 2-4 weeks before a scheduled release.
The group decision at the meeting is final.

Space is limited. Not all requests will be accepted, even if they meet all the above criteria.

(end of content adapted from zzz.i2p)

Steps for the devs after receiving a submission:

- Verify hostname was not previously used or registered anywhere by another site
- Verify compatibility with all criteria
- Verify icon is consistent with the console home page style and will look fine in both themes
- Verify icon license compatibility with our licenses
- Validate authentication string unless submitted to a registration site that does that
- Pick the right category or discuss with submitter
- Ensure display name is short and reasonable and translatable
- Ensure description is short and reasonable and translatable
- Resolve any issues with submitter
- Add to agenda for a monthly meeting before the scheduled tag freeze

Steps for the devs after approval at a meeting:

- Add to the bottom of the correct category below
- Ensure display name is tagged and does not contain commas unless the hostname alone
- Ensure description is tagged and does not contain commas
- Check in the icon in the right place with a description of the license in the checkin comment
- Add to installer/resources/hosts.txt WITHOUT the authentication string and check in
- Check in this file HomeHelper.java
- Test
- Add to i2p.www i2p2www/static/hosts.txt WITH the authentication string and check in
  (don't forget this step!)

*/

    // No commas allowed in text strings!
    static final String DEFAULT_SERVICES =
        _x("Email") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "email.png" + S +
        _x("Hidden Services Manager") + S + _x("Control your client and server tunnels") + S + "/i2ptunnelmgr" + S + I + "server_32x32.png" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "i2psnark.png" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "server_32x32.png" + S +
        _x("Address Book") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "book_addresses.png" + S +
        "";

    /** @since 0.9.44 */
    static final String DEFAULT_CONFIG =
        //_x("Configure Homepage") + S + _x("Configure the contents of this page") + S + "/confighome" + S + I + "info/home.png" + S +
        _x("Configure Bandwidth") + S + _x("I2P Bandwidth Configuration") + S + "/config" + S + I + "info/bandwidth.png" + S +
        // FIXME wasn't escaped
        //_x("Configure UI") + S + _x("Select console theme & language & set optional console password").replace("&", "&amp;") + S + "/configui" + S + I + "info/ui.png" + S +
        //_x("Customize Home Page") + S + _x("I2P Home Page Configuration") + S + "/confighome" + S + I + "home_page.png" + S +
        //_x("Customize Sidebar") + S + _x("Customize the sidebar by adding or removing or repositioning elements") + S + "/configsidebar" + S + I + "info/sidebar.png" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help" + S + I + "support.png" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "plugin.png" + S +
        _x("Router Console") + S + _x("I2P Router Console") + S + "/console" + S + I + "info/console.png" + S +
        "";

    // No commas allowed in text strings!
    static final String DEFAULT_FAVORITES =
        //"anoncoin.i2p" + S + _x("The Anoncoin project") + S + "http://anoncoin.i2p/" + S + I + "anoncoin_32.png" + S +
        //"colombo-bt.i2p" + S + _x("The Italian Bittorrent Resource") + S + "http://colombo-bt.i2p/" + S + I + "colomboicon.png" + S +
        //_x("Dev Builds") + S + _x("Development builds of I2P") + S + "http://bobthebuilder.i2p/" + S + I + "script_gear.png" + S +
        //_x("Dev Forum") + S + _x("Development forum") + S + "http://zzz.i2p/" + S + I + "group_gear.png" + S +
        //_x("diftracker") + S + _x("Bittorrent tracker") + S + "http://diftracker.i2p/" + S + I + "i2psnark.png" + S +
        //"echelon.i2p" + S + _x("I2P Applications") + S + "http://echelon.i2p/" + S + I + "echelon.png" + S +
        //"exchanged.i2p" + S + _x("Anonymous cryptocurrency exchange") + S + "http://exchanged.i2p/" + S + I + "exchanged.png" + S +
        _x("I2P FAQ") + S + _x("Frequently Asked Questions") + S + "http://i2p-projekt.i2p/faq" + S + I + "question.png" + S +
        _x("I2P Forum") + S + _x("Community forum") + S + "http://i2pforum.i2p/" + S + I + "group.png" + S +
        _x("Git Project Hosting") + S + _x("Community git project hosting") + S + "http://git.idk.i2p" + S + I + "i2pgit.png" + S +
        _x("I2P Pastebin") + S + _x("Pastebin for I2P users") + S + "http://paste.idk.i2p" + S + I + "paste.png" + S +
        //"git.repo.i2p" + S + _x("A public anonymous Git hosting site - supports pulling via Git and HTTP and pushing via SSH") + S + "http://git.repo.i2p/" + S + I + "git-logo.png" + S +
        //"hiddengate [ru]" + S + _x("Russian I2P-related wiki") + S + "http://hiddengate.i2p/" + S + I + "hglogo32.png" + S +
        //_x("I2P Wiki") + S + _x("Anonymous wiki - share the knowledge") + S + "http://i2pwiki.i2p/" + S + I + "i2pwiki_logo.png" + S +
        //"Ident " + _x("Microblog") + S + _x("Your premier microblogging service on I2P") + S + "http://id3nt.i2p/" + S + I + "ident_icon_blue.png" + S +
        //_x("Javadocs") + S + _x("Technical documentation") + S + "http://i2p-javadocs.i2p/" + S + I + "education.png" + S +
        //"jisko.i2p" + S + _x("Simple and fast microblogging website") + S + "http://jisko.i2p/" + S + I + "jisko_console_icon.png" + S +
        //_x("Key Server") + S + _x("OpenPGP Keyserver") + S + "http://keys.i2p/" + S + I + "education.png" + S +
        //"killyourtv.i2p" + S + _x("Debian and Tahoe-LAFS repositories") + S + "http://killyourtv.i2p/" + S + I + "television_delete.png" + S +
        //_x("MuWire") + S + _x("Easy anonymous file sharing") + S + "http://muwire.i2p/" + S + I + "muwire.png" + S +
        //_x("Open4You") + S + _x("Free I2P Site hosting with PHP and MySQL") + S + "http://open4you.i2p/" + S + I + "open4you-logo.png" + S +
        //_x("Pastebin") + S + _x("Encrypted I2P Pastebin") + S + "http://zerobin.i2p/" + S + I + "paste_plain.png" + S +
        _x("Planet I2P") + S + _x("I2P News") + S + "http://planet.i2p/" + S + I + "world.png" + S +
        //_x("I2P Plugins") + S + _x("Add-on directory") + S + "http://i2pwiki.i2p/index.php?title=Plugins" + S + I + "info/plugin_link.png" + S +
        //_x("Postman's Tracker") + S + _x("Bittorrent tracker") + S + "http://tracker2.postman.i2p/" + S + I + "magnet.png" + S +
        //_x("PrivateBin") + S + _x("Encrypted I2P Pastebin") + S + "http://paste.crypthost.i2p/" + S + I + "paste_plain.png" + S +
        _x("Project Website") + S + _x("I2P home page") + S + "http://i2p-projekt.i2p/" + S + I + "glass.png" + S +
        //_x("lenta news [ru]") + S + _x("Russian News Feed") + S + "http://lenta.i2p/" + S + I + "lenta_main_logo.png" + S +
        //"Salt" + S + "salt.i2p" + S + "http://salt.i2p/" + S + I + "salt_console.png" + S +
        //_x("The Tin Hat") + S + _x("Privacy guides and tutorials") + S + "http://secure.thetinhat.i2p/" + S + I + "thetinhat.png" + S +
        //_x("Ugha's Wiki") + S + S + "http://ugha.i2p/" + S + I + "billiard_marker.png" + S +
        //"sponge.i2p" + S + _x("Seedless and the Robert BitTorrent applications") + S + "http://sponge.i2p/" + S + I + "user_astronaut.png" + S +
        "notbob.i2p" + S + _x("Not Bob's Address Services") + S + "http://notbob.i2p/" + S + I + "notblob.png" + S +
        "[Ramble]" + S + _x("Ramble user-moderated forum aggregator") + S + "http://ramble.i2p/" + S + I + "ramble.png" + S +
        "StormyCloud" + S + _x("StormyCloud Outproxy Services") + S + "http://stormycloud.i2p/" + S + I + "stormycloud.png" + S +
        "";

    // No commas allowed in text strings!
    /** @since 0.9.44 */
    static final String DEFAULT_MONITORING =
        _x("Logs") + S + _x("View the logs") + S + "/logs" + S + I + "info/logs.png" + S +
        _x("Graphs") + S + _x("Visualize information about the router") + S + "/graphs" + S + I + "chart_line.png" + S +
        _x("I2P Technical Docs") + S + _x("Technical documentation") + S + "http://i2p-projekt.i2p/how" + S + I + "education.png" + S +
        _x("I2P Wiki") + S + S + "http://wiki.i2p-projekt.i2p/" + S + I + "trac_wiki.png" + S +
        _x("I2P Bug Reports") + S + _x("Bug tracker") + S + "http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues" + S + I + "bug.png" + S +
        "stats.i2p" + S + _x("I2P Network Statistics") + S + "http://stats.i2p/cgi-bin/dashboard.cgi" + S + I + "chart_bar.png" + S +
        "";

    public boolean shouldShowWelcome() {
        return _context.getProperty(Messages.PROP_LANG) == null;
    }

    public boolean shouldShowSearch() {
        return _context.getBooleanProperty(PROP_SEARCH);
    }

    /** @since 0.9.47 */
    private String topWrap(String headline) {
        String str = "<div class=\"ag2\">\n" +
            "<h4 class=\"app\">" +
            headline +
            "</h4>\n" +
            "<div class=\"homeapps\">\n";
        return str;
    }
    
    public String getServices() {
        String table = homeTable(PROP_SERVICES, DEFAULT_SERVICES, null);
        if (table.length() == 0) {
            return "";
        }
        StringBuilder buf = new StringBuilder(1380);
        buf.append(topWrap(_t("Applications")));
        buf.append(table);
        buf.append(bottomWrap);
        return buf.toString();
    }
    
    /** @since 0.9.47 */
    public String getPlugins() {
        List<App> plugins = NavHelper.getInstance(_context).getClientApps(_context);
        String table = pluginTable(plugins);
        if (table.length() == 0) {
            return "";
        }
        StringBuilder buf = new StringBuilder(1380);
        buf.append(topWrap(_t("Plugins")));
        buf.append(table);
        buf.append(bottomWrap);
        return buf.toString();
    }
    
    /** @since 0.9.44 */
    public String getConfig() {
        String table = homeTable(PROP_CONFIG, DEFAULT_CONFIG, null);
        if (table.length() == 0) {
            return "";
        }
        StringBuilder buf = new StringBuilder(1380);
        buf.append(topWrap(_t("Configuration and Help")));
        buf.append(table);
        buf.append(bottomWrap);
        return buf.toString();
    }

    /** @since 0.9.44 */
    public String getMonitoring() {
        String table = homeTable(PROP_MONITORING, DEFAULT_MONITORING, null);
        if (table.length() == 0) {
            return "";
        }
        StringBuilder buf = new StringBuilder(1380);
        buf.append(topWrap(_t("Network and Developer Information")));
        buf.append(table);
        buf.append(bottomWrap);
        return buf.toString();
    }

    public String getFavorites() {
        String table = homeTable(PROP_FAVORITES, DEFAULT_FAVORITES, null);
        if (table.length() == 0) {
            return "";
        }
        StringBuilder buf = new StringBuilder(1380);
        buf.append(topWrap(_t("I2P Community Sites")));
        buf.append(table);
        buf.append(bottomWrap);
        return buf.toString();
    }

    public String getConfigServices() {
        return configTable(PROP_SERVICES, DEFAULT_SERVICES);
    }

    /** @since 0.9.47 */
    public String getConfigPlugins() {
        return getPlugins();
    }

    /** @since 0.9.44 */
    public String getConfigConfig() {
        return configTable(PROP_CONFIG, DEFAULT_CONFIG);
    }

    /** @since 0.9.44 */
    public String getConfigMonitoring() {
        return configTable(PROP_MONITORING, DEFAULT_MONITORING);
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

    public String getProxyStatus() {
        int port = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY);
        if (port <= 0)
            return _t("The HTTP proxy is not up");
        return "<img src=\"http://console.i2p/onepixel.png?" + _context.random().nextInt() + "\"" +
               " alt=\"" + _t("Your browser is not properly configured to use the HTTP proxy at {0}",
                             _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST) + ':' + port) +
               "\">";
    }


    private String pluginTable(Collection<App> toAdd) {
        Collection<App> apps = buildApps(_context, "");
        if (toAdd != null)
            apps.addAll(toAdd);
        return renderApps(apps);
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
        if (apps.size() == 0) {
            return "";
        }
        String website = _t("Web Server");
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=\"appgroup\">");
        PortMapper pm = _context.portMapper();
        ClientAppManager cmgr = _context.clientAppManager();
        for (App app : apps) {
            String svc = null;
            String url;
            if (app.name.equals(website) && app.url.equals("http://127.0.0.1:7658/")) {
                // fixup I2P Site link
                url = SummaryBarRenderer.getEepsiteURL(pm);
                if (url == null)
                    continue;
            } else {
                url = app.url;
                // check for disabled webapps and other things
                if (url.equals("/dns")) {
                    svc = PortMapper.SVC_SUSIDNS;
                } else if (url.equals("/webmail")) {
                    svc = PortMapper.SVC_SUSIMAIL;
                } else if (url.equals("/torrents")) {
                    svc = PortMapper.SVC_I2PSNARK;
                } else if (url.equals("/i2ptunnelmgr")) {
                    svc = PortMapper.SVC_I2PTUNNEL;
                    // need both webapp and TCG, but we aren't refreshing
                    // the icons, so let's not do this
                    //ClientAppManager cmgr = _context.clientAppManager();
                    //if (cmgr != null && cmgr.getRegisteredApp("i2ptunnel") == null)
                    //    continue;
                } else if (url.equals("/logs")) {
                    svc = PortMapper.SVC_LOGS;
                } else if (url.equals("/configplugins")) {
                    if (!PluginStarter.pluginsEnabled(_context))
                        continue;
                }
            }
            if (svc != null && !pm.isRegistered(svc))
                continue;
            // If an image isn't in a /themes or /images directory, it comes from a plugin.
            // tag it thus.
            String plugin = "";
            if (!app.icon.startsWith("/themes") && !app.icon.startsWith("/images")) {
                plugin = " plugin";
            }
            buf.append("\n<div class=\"app" + plugin + "\">\n" +
                       "<div class=\"appimg" + plugin + "\">" +
                       // usability: add tabindex -1 so we avoid 2 tabs per app
                       "<a href=\"").append(url).append("\" tabindex=\"-1\">" +
                       "<img alt=\"\" title=\"").append(app.desc).append("\" src=\"").append(app.icon)
               // version the icons because they may change
               .append(app.icon.contains("?") ? "&amp;" : "?").append(CoreVersion.VERSION).append("\">");
            // notification bubbles
            if (svc != null && cmgr != null) {
                int nc = cmgr.getBubbleCount(svc);
                if (nc > 0) {
                    buf.append("<span class=\"notifbubble\" ");
                    String ns = cmgr.getBubbleText(svc);
                    if (ns != null)
                        buf.append(" title=\"").append(DataHelper.escapeHTML(ns)).append("\" ");
                    buf.append('>');
                    buf.append(nc);
                    buf.append("</span>");
                }
            }
            buf.append("</a></div>\n" +
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
               .append(app.name)
               .append("\"></td><td align=\"center\">");
            if (app.icon != null) {
                buf.append("<img height=\"16\" alt=\"\" src=\"").append(app.icon).append("\">");
            }
            buf.append("</td><td align=\"left\"><label for=\"")
               .append(app.name)
               .append("\">")
               .append(DataHelper.escapeHTML(app.name))
               .append("</label></td><td align=\"left\"><a href=\"");
            String url = DataHelper.escapeHTML(app.url);
            buf.append(url)
               .append("\">");
            // truncate before escaping
            if (app.url.length() > 50)
                buf.append(DataHelper.escapeHTML(app.url.substring(0, 48))).append("&hellip;");
            else
                buf.append(url);
            buf.append("</a></td></tr>\n");
        }
        buf.append("<tr id=\"addnew\"><td colspan=\"2\" align=\"center\"><b>")
           .append(_t("Add")).append(":</b>" +
                   "</td><td align=\"left\"><input type=\"text\" name=\"nofilter_name\" required></td>" +
                   "<td align=\"left\"><input type=\"text\" size=\"40\" name=\"nofilter_url\" required></td></tr>");
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
