package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.web.App;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NavHelper;
import net.i2p.router.web.PluginStarter;
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
    static final String PROP_SEARCH = "routerconsole.showSearch";

    // No commas allowed in text strings!
    static final String DEFAULT_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns?book=router&filter=latest" + S + I + "addressbook.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "graphs.svg" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help/" + S + I + "help.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "mail.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "pluginconfig.svg" + S +
        _x("NetDb Search") + S + _x("Network database search tool") + S + "/netdb?f=4" + S + I + "searchnetdb.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "globe.svg" + S +
        _x("Peer Profiles") + S + _x("List of recently connected peers with profiling info") + S + "/profiles?f=1" + S + I + "profile.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/routerlogs" + S + I + "logs.svg" + S +
        _x("Router Updates") + S + _x("Configure update URLs and policy") + S + "/configupdate" + S + I + "update.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/tunnelmanager" + S + I + "tunnelmanager.svg" + S +
        _x("Webapps") + S + _x("Manage Router webapps") + S + "/configwebapps" + S + I + "webapps.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "webserver.svg" + S +
        "";

    static final String NEWINSTALL_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns?book=router&filter=latest" + S + I + "addressbook.svg" + S +
        _x("Configure Bandwidth") + S + _x("I2P Bandwidth Configuration") + S + "/config" + S + I + "speedometer.svg" + S +
        _x("Configure UI") + S + _x("Select console theme &amp; language &amp; set optional console password") + S + "/configui" + S + I + "ui.svg" + S +
        _x("Customize Sidebar") + S + _x("Customize the sidebar by adding or removing or repositioning elements") + S + "/configsidebar" + S + I + "sidebar.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "graphs.svg" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help/" + S + I + "help.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "mail.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "pluginconfig.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "globe.svg" + S +
        _x("Peer Profiles") + S + _x("List of recently connected peers with profiling info") + S + "/profiles?f=1" + S + I + "profile.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/routerlogs" + S + I + "logs.svg" + S +
        _x("Router Updates") + S + _x("Configure update URLs and policy") + S + "/configupdate" + S + I + "update.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/tunnelmanager" + S + I + "tunnelmanager.svg" + S +
        _x("Webapps") + S + _x("Manage Router webapps") + S + "/configwebapps" + S + I + "webapps.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "webserver.svg" + S +
        _x("Wizard") + S + _x("Configuration and bandwidth tester") + S + "/wizard" + S + I + "wizard.svg" + S +
        "";

    static final String ADVANCED_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns?book=router&filter=latest" + S + I + "addressbook.svg" + S +
        _x("Advanced Config") + S + _x("Advanced router configuration") + S + "/configadvanced" + S + I + "configure.svg" + S +
        _x("Changelog") + S + _x("Recent changes") + S + "/changelog" + S + I + "changelog.svg" + S +
        _x("Clients") + S + _x("Start or stop Router clients") + S + "/configclients" + S + I + "editclient.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "graphs.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "mail.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "pluginconfig.svg" + S +
        _x("NetDb Search") + S + _x("Network database search tool") + S + "/netdb?f=4" + S + I + "searchnetdb.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "globe.svg" + S +
        _x("Peer Profiles") + S + _x("List of recently connected peers with profiling info") + S + "/profiles?f=1" + S + I + "profile.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/routerlogs" + S + I + "logs.svg" + S +
        _x("Router Updates") + S + _x("Configure update URLs and policy") + S + "/configupdate" + S + I + "update.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/tunnelmanager" + S + I + "tunnelmanager.svg" + S +
        _x("Webapps") + S + _x("Manage Router webapps") + S + "/configwebapps" + S + I + "webapps.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "webserver.svg" + S +
        "";

    // No commas allowed in text strings!
    static final String DEFAULT_FAVORITES =

// I2P specific
        _x("I2P+ Bug Reports") + S + _x("Bug tracker") + S + "https://github.com/I2PPlus/i2pplus/issues" + S + I + "bug.svg" + S +
        _x("I2P FAQ") + S + _x("Frequently Asked Questions") + S + "http://i2p-projekt.i2p/faq" + S + I + "faq.svg" + S +
        _x("I2P Plugins") + S + _x("zzz's plugin repository") + S + "http://stats.i2p/i2p/plugins/" + S + I + "plugin.svg" + S +
        "i2pmetrics.i2p" + S + _x("Historical infrastructure data from the I2P network") + S + "http://i2pmetrics.i2p/" + S + I + "stats.svg" + S +
        _x("Project Website") + S + _x("I2P home page") + S + "http://i2p-projekt.i2p/" + S + I + "eepsites/i2p.svg" + S +
        _x("Javadocs") + S + _x("I2P+ API documentation") + S + "http://javadoc.skank.i2p/" + S + I + "helplink.svg" + S +
        "stats.i2p" + S + _x("I2P Network Statistics") + S + "http://stats.i2p/cgi-bin/dashboard.cgi" + S + I + "stats.svg" + S +

// software repositories + filesharing
        "drop.i2p" + S + _x("Transient server-encrypted filesharing and pastebin") + S + "http://drop.i2p/" + S + I + "eepsites/drop.svg" + S +
        "fs.i2p" + S + _x("Secure filesharing service") + S + "http://fs.i2p/" + S + I + "eepsites/cloud.svg" + S +
        "git.idk.i2p" + S + _x("Official I2P Git repository") + S + "http://git.idk.i2p/explore/projects" + S + I + "gitlab.svg" + S +
        "git.skank.i2p" + S + _x("Official I2P+ Git repository") + S + "http://git.skank.i2p/" + S + I + "eepsites/gitplus.svg" + S +
        "sharefile.i2p" + S + _x("Secure filesharing service") + S + "http://sharefile.i2p/" + S + I + "eepsites/cloud.svg" + S +
        "skank.i2p" + S + _x("Home of I2P+") + S + "http://skank.i2p/" + S + I + "plus.svg" + S +
        //"cake.i2p" + S + _x("Transient server-encrypted filesharing and pastebin") + S + "http://cake.i2p/" + S + I + "eepsites/cake.svg" + S +

// torrent trackers
        _x("Postman's Tracker") + S + _x("BitTorrent tracker") + S + "http://tracker2.postman.i2p/" + S + I + "eepsites/postman.svg" + S +

// domain name registration + uptime trackers
        "notbob.i2p" + S + _x("List of active eepsites and uptime monitor") + S + "http://notbob.i2p/" + S + I + "eepsites/notbob.png" + S +
        "reg.i2p" + S + _x("I2P Domain Name Registration") + S + "http://reg.i2p/" + S + I + "eepsites/reg.svg" + S +
        _x("zzz Domain Registry") + S + _x("I2P Domain Name Registration") + S + "http://stats.i2p/i2p/addkey.html" + S + I + "eepsites/registrar.svg" + S +

// forums + social
        _x("I2P Forum") + S + _x("I2P-related community forums") + S + "http://i2pforum.i2p/" + S + I + "eepsites/forum.svg" + S +
        "discuss.i2p" + S + _x("File-sharing forum") + S + "http://discuss.i2p/" + S + I + "eepsites/forum.svg" + S +
        "ramble.i2p" + S + _x("Cross-network micro-blogging &amp; forums &amp; wiki") + S + "http://ramble.i2p/" + S + I + "eepsites/ramble.svg" + S +
        "shreddit" + S + _x("Alternative privacy-focused front-end for Reddit") + S + "http://shreddit.i2p/" + S + I + "eepsites/shreddit.svg" + S +
        //"Natter" + S + _x("Alternative front-end for Twitter") + S + "http://natter.i2p/" + S + I + "eepsites/twitter.svg" + S +
        //"novabbs.i2p" + S + _x("Rocksolid forums for the darknets") + S + "http://novabbs.i2p/" + S + I + "eepsites/forum.svg" + S +
        //"teddit" + S + _x("Alternative privacy-focused front-end for Reddit") + S + "http://teddit.ls.i2p/" + S + I + "eepsites/teddit.svg" + S +

// hosting + other services
        "ArduLLM" + S + _x("AI Image generation & Chat hub") + S + "http://ardullm.i2p/" + S + I + "eepsites/ardullm.webp" + S +
        "major.i2p" + S + _x("IRC Logs for multiple networks") + S + "http://major.i2p/" + S + I + "eepsites/major.svg" + S +
        _x("Pastebin") + S + _x("Encrypted I2P Pastebin") + S + "http://paste.r4sas.i2p/" + S + I + "paste.svg" + S +
        "qwik.i2p" + S + _x("Landing page for various services") + S + "http://qwik.i2p/" + S + I + "eepsites/qwik.svg" + S +
        "radio.r4sas.i2p" + S + _x("Streaming radio service") + S + "http://radio.r4sas.i2p/" + S + I + "eepsites/radio.svg" + S +
        "stormycloud.i2p" + S + _x("Privacy-focused not-for-profit organization") + S + "http://stormycloud.i2p/" + S + I + "eepsites/stormycloud.svg" + S +
        _x("translate") + S + _x("Text translation engine") + S + "http://translate.i2p/" + S + I + "eepsites/translate.svg" + S +
        "incognet.i2p" + S + _x("Provider of privacy respecting web hosting and VPN services") + S + "http://incognet.i2p/" + S + I + "eepsites/incog.svg" + S +
        //"base64-image.i2p" + S + _x("Base64 Image Encoder") + S + "http://base64-image.i2p/" + S + I + "eepsites/base64-image.svg" + S +
        //"imageproxy.i2p" + S + _x("An image cache and resize service") + S + "http://imageproxy.i2p/" + S + I + "eepsites/imageproxy.svg" + S +
        //"rimgo" + S + _x("Alternative frontend for Imgur") + S + "http://rimgo.ls.i2p/" + S + I + "eepsites/imageproxy.svg" + S +
        //"translate.idk.i2p" + S + _x("Text translation engine") + S + "http://translate.idk.i2p/" + S + I + "eepsites/translate.svg" + S +
        //"tube.i2p" + S + _x("Alternative front-end to Youtube") + S + "http://tube.i2p/" + S + I + "eepsites/tv.svg" + S +
        //_x("speedtest") + S + _x("Outproxy bandwidth test") + S + "http://outproxy.purokishi.i2p/speedtest/" + S + I + "speedometer.svg" + S +

// search engines
        "DuckDuckGo" + S +_x("DuckDuckGo Search Engine (no javascript)") + S + "http://duckduckgo.i2p/" + S + I + "eepsites/duckduckgo.svg" + S +
        "Mojeek" + S +_x("Privacy-focused Clearnet Search Engine") + S + "http://mojeek.i2p/" + S + I + "search.svg" + S +
        "Shinobi" + S +_x("I2P Web Search Engine") + S + "http://shinobi.i2p/" + S + I + "eepsites/shinobi.png" + S +
        //"ahmia.i2p" + S +_x("Tor-focused Search Engine") + S + "http://ahmia.i2p/" + S + I + "eepsites/onionsearch.svg" + S +
        //"binternet" + S +_x("Pinterest Image Search Engine") + S + "http://binternet.lostskunk-dnr.i2p/" + S + I + "eepsites/binternet.svg" + S +
        //"btdigg.i2p" + S +_x("Clearnet BitTorrent DHT search engine") + S + "http://btdigg.i2p/" + S + I + "eepsites/torrentsearch.svg" + S +
        //"torch.i2p" + S +_x("Tor-focused Search Engine") + S + "http://torch.i2p/" + S + I + "eepsites/onionsearch.svg" + S +

// wikis & reference
        "I2P Wiki" + S + _x("I2P-related Wiki") + S + "http://wiki.i2p-projekt.i2p/" + S + I + "wiki.svg" + S +
        "mathworld.i2p" + S + _x("Wolfram's Online Math Reference") + S + "http://mathworld.i2p/" + S + I + "eepsites/mathworld.svg" + S +
        "mdn.i2p" + S + _x("Mozilla Developer Network") + S + "http://mdn.i2p/" + S + I + "eepsites/mdn.svg" + S +
        "Philosopedia" + S + _x("Internet Encyclopedia of Philosophy") + S + "http://philosopedia.i2p/" + S + I + "eepsites/books.svg" + S +
        "plato.i2p" + S + _x("Standford Encyclopedia of Philosophy") + S + "http://plato.i2p/" + S + I + "eepsites/plato.png" + S +
        _x("SimplifiedGuide") + S + _x("Computing-related tutorials") + S + "http://simplified.i2p/" + S + I + "help.svg" + S +
        "w3schools.i2p" + S + _x("Web technology reference and learning site") + S + "http://w3schools.i2p/" + S + I + "eepsites/w3schools.svg" + S +
        "wikiless.i2p" + S + _x("Alternative Wikipedia front-end focused on privacy") + S + "http://wikiless.i2p/" + S + I + "wiki.svg" + S +
        _x("WorldAtlas") + S + _x("World atlas including geography facts &amp; flags") + S + "http://worldatlas.i2p/" + S + I + "planet.svg" + S +
        //"ddosecrets.i2p" + S + _x("Enabling the free transmission of data in the public interest") + S + "http://ddosecrets.i2p/" + S + I + "wiki.svg" + S +
        //"freehaven.i2p" + S + _x("Selected Papers in Anonymity") + S + "http://freehaven.i2p/" + S + I + "eepsites/books.svg" + S +
        //"hiddenwiki.i2p" + S + _x("Tor .onion site links") + S + "http://hiddenwiki.i2p/" + S + I + "wiki.svg" + S +
        //_x("HTMLColors") + S + _x("HTML Color Reference") + S + "http://htmlcolors.i2p/" + S + I + "help.svg" + S +
        //_x("Psychonaut") + S + _x("Wiki relating to altered states of consciousness") + S + "http://psychonaut.i2p/" + S + I + "eepsites/psy.svg" + S +
        //"vuldb.i2p" + S + _x("Vulnerability Database") + S + "http://vuldb.i2p/" + S + I + "eepsites/vuldb.svg" + S +
        //"Wordnik" + S + _x("The biggest online English dictionary") + S + "http://wordnik.i2p/" + S + I + "eepsites/wordnik.svg" + S +

// news + blogs
        "ArsTechnica" + S + _x("Technology News &amp; more...") + S + "http://arstechnica.i2p/" + S + I + "eepsites/arstechnica.svg" + S +
        "BenthamsGaze" + S + _x("Information Security Research &amp; Education") + S + "http://benthamsgaze.i2p/" + S + I + "eepsites/benthamsgaze.png" + S +
        _x("DigitalTrends") + S + _x("Tech News &amp; Reviews") + S + "http://digitaltrends.i2p/" + S + I + "eepsites/digitaltrends.svg" + S +
        "EFF" + S + _x("Defending your rights in the digital world") + S + "http://eff.i2p/" + S + I + "eepsites/eff.svg" + S +
        "EllipticNews" + S + _x("The Elliptic Curve Cryptography Blog") + S + "http://ellipticnews.i2p/" + S + I + "news.svg" + S +
        "Hackaday" + S + _x("Fresh hacks every day") + S + "http://hackaday.i2p/" + S + I + "eepsites/hackaday.svg" + S +
        _x("HackerNews") + S + _x("Computer related news aggregation") + S + "http://hackernews.i2p/" + S + I + "news.svg" + S +
        _x("HongKongFreePress") + S + _x("Independent &amp; impartial news for Hong Kong") + S + "http://hongkongfreepress.i2p/" + S + I + "eepsites/hkfp.svg" + S +
        _x("JStorDaily") + S + _x("Where news meets its scholarly match") + S + "http://jstordaily.i2p/" + S + I + "eepsites/jstordaily.png" + S +
        _x("Jurist") + S + _x("Legal News &amp; Commentary") + S + "http://jurist.i2p/" + S + I + "eepsites/jurist.svg" + S +
        _x("KrebsOnSecurity") + S + _x("In-depth security news &amp; investigation") + S + "http://krebsonsecurity.i2p/" + S + I + "eepsites/krebs.png" + S +
        "LKML" + S + _x("Linux Kernel Mailing List") + S + "http://lkml.i2p/" + S + I + "news.svg" + S +
        "MotherJones" + S + _x("Smart &amp; fearless journalism") + S + "http://motherjones.i2p/" + S + I + "eepsites/motherjones.svg" + S +
        _x("NewStatesman") + S + _x("Politics &amp; Current Affairs magazine") + S + "http://newstatesman.i2p/" + S + I + "eepsites/newstatesman.svg" + S +
        _x("OpenDemocracy") + S + _x("Independent International Media Platform") + S + "http://opendemocracy.i2p/" + S + I + "news.svg" + S +
        _x("RightToPrivacy") + S + _x("Online privacy &amp; tech blog") + S + "http://righttoprivacy.i2p/" + S + I + "eepsites/rtp.webp" + S +
        "RollingStone" + S + _x("Lifestyle magazine") + S + "http://rollingstone.i2p/" + S + I + "eepsites/rollingstone.svg" + S +
        _x("SchneierOnSecurity") + S + _x("Internationally renowned security technologist") + S + "http://schneieronsecurity.i2p/" + S + I + "eepsites/schneier.png" + S +
        _x("ScienceDaily") + S + _x("Latest scientific research news") + S + "http://sciencedaily.i2p/" + S + I + "eepsites/science.svg" + S +
        "SoylentNews" + S + _x("Community-driven tech news") + S + "http://soylentnews.i2p/" + S + I + "news.svg" + S +
        "Simp" + S + _x("Various services") + S + "http://simp.i2p/" + S + I + "eepsites/simp.svg" + S +
        "Slashdot" + S + _x("News for nerds") + S + "http://slashdot.i2p/" + S + I + "eepsites/slashdot.svg" + S +
        "SlashGear" + S + _x("Technology news &amp; reviews") + S + "http://slashgear.i2p/" + S + I + "eepsites/slashgear.svg" + S +
        _x("TaipeiTimes") + S + _x("News from the Taiwan Capital") + S + "http://taipeitimes.i2p/" + S + I + "news.svg" + S +
        _x("TechMeme") + S + _x("Technology news aggregator") + S + "http://techmeme.i2p/" + S + I + "news.svg" + S +
        _x("TheAtlantic") + S + _x("News &amp; Reviews") + S + "http://theatlantic.i2p/" + S + I + "news.svg" + S +
        _x("TheConversation") + S + _x("Academic commentary and essays") + S + "http://theconversation.i2p/" + S + I + "eepsites/theconversation.svg" + S +
        _x("TheGuardian") + S + _x("Global News") + S + "http://theguardian.i2p/" + S + I + "eepsites/theguardian.svg" + S +
        _x("TheMarkup") + S + _x("Watching Big Tech...") + S + "http://themarkup.i2p/" + S + I + "news.svg" + S +
        _x("TheMoscowTimes") + S + _x("Independent Journalism from Russia") + S + "http://themoscowtimes.i2p/" + S + I + "news.svg" + S +
        _x("TheNewRepublic") + S + _x("Politics &amp; Current Affairs magazine") + S + "http://newrepublic.i2p/" + S + I + "eepsites/newrepublic.svg" + S +
        "TorrentFreak" + S + _x("Filesharing &amp; copyright news") + S + "http://torrentfreak.i2p/" + S + I + "eepsites/torrentfreak.svg" + S +
        _x("WashingtonInstitute") + S + _x("Advancing a balanced &amp; realistic understanding of American interests in the Middle East") + S + "http://washingtoninstitute.i2p/" + S + I + "eepsites/washingtoninstitute.svg" + S +
        //"bellingcat.i2p" + S + _x("Independent investigative journalism") + S + "http://bellingcat.i2p/" + S + I + "eepsites/bellingcat.svg" + S +
        //_x("CultureMagazine") + S + _x("Cannabis-related magazine") + S + "http://culturemagazine.i2p/" + S + I + "eepsites/cannabis.svg" + S +
        //"DarknetLive" + S + _x("Darknet related news site") + S + "http://darknetlive.i2p/" + S + I + "news.svg" + S +
        //_x("ForeignPolicy") + S + _x("The Global Magazine of News &amp; Ideas") + S + "http://foreignpolicy.i2p/" + S + I + "news.svg" + S +
        //_x("FrontLineDefenders") + S + _x("Human rights activism") + S + "http://frontlinedefenders.i2p/" + S + I + "news.svg" + S +
        //_x("HongKongWatch") + S + _x("Monitors threats to freedoms and rights in Hong Kong") + S + "http://hongkongwatch.i2p/" + S + I + "news.svg" + S +
        //_x("HumanRightsWatch") + S + _x("Defending Human Rights Worldwide") + S + "http://humanrightswatch.i2p/" + S + I + "news.svg" + S +
        //"intpolicydigest.i2p" + S + _x("Politics &amp; Current Affairs Journal") + S + "http://intpolicydigest.i2p/" + S + I + "news.svg" + S +
        //_x("JapanToday") + S + _x("English language Japanese News") + S + "http://japantoday.i2p/" + S + I + "eepsites/japantoday.svg" + S +
        //_x("Planeta") + S + _x("News and blog feeds on I2P") + S + "http://planeta.i2p/" + S + I + "planet.svg" + S +
        //"supchina.i2p" + S + _x("Reporting on China without fear or favor") + S + "http://supchina.i2p/" + S + I + "eepsites/supchina.svg" + S +
        //_x("TechXplore") + S + _x("Technology and engineering news") + S + "http://techxplore.i2p/" + S + I + "eepsites/techxplore.svg" + S +
        //_x("TheTibetPost") + S + _x("International News from Tibet") + S + "http://thetibetpost.i2p/" + S + I + "eepsites/tibetpost.svg" + S +
        //_x("UpstreamJournal") + S + _x("A magazine about human rights and social justice") + S + "http://upstreamjournal.i2p/" + S + I + "news.svg" + S +
        "";

    public boolean shouldShowWelcome() {return _context.getProperty(Messages.PROP_LANG) == null;}

    /* @since 0.9.52+ */
    public boolean shouldShowBandwidthConfig() {return _context.getProperty("i2np.bandwidth.outboundKBytesPerSecond") == null;}

    public boolean shouldShowSearch() {return _context.getBooleanProperty(PROP_SEARCH);}

    public boolean isAdvanced() {return _context.getBooleanProperty(PROP_ADVANCED);}

    public String getServices() {
        List<App> plugins = NavHelper.getInstance(_context).getClientApps(_context);
        net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
        String version = net.i2p.CoreVersion.VERSION;
        String firstVersion = ctx.getProperty("router.firstVersion");
        if (!isAdvanced() && version.equals(firstVersion)) {return homeTable(PROP_SERVICES, NEWINSTALL_SERVICES, plugins);}
        else if (!version.equals(firstVersion) && !isAdvanced()) {return homeTable(PROP_SERVICES, DEFAULT_SERVICES, plugins);}
        else {return homeTable(PROP_SERVICES, ADVANCED_SERVICES, plugins);}
    }

    public String getFavorites() {return homeTable(PROP_FAVORITES, DEFAULT_FAVORITES, null);}

    public String getConfigServices() {return configTable(PROP_SERVICES, DEFAULT_SERVICES);}

    public String getConfigFavorites() {return configTable(PROP_FAVORITES, DEFAULT_FAVORITES);}

    public String getConfigSearch() {return configTable(SearchHelper.PROP_ENGINES, SearchHelper.ENGINES_DEFAULT);}

    public String getProxyStatus() {
        int port = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY);
        if (port <= 0) {return _t("The HTTP proxy is not up");}
        return "<img src=\"http://console.i2p/onepixel.png?" + _context.random().nextInt() + "\"" +
                " alt=\"" + _t("Your browser is not properly configured to use the HTTP proxy at {0}",
                _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST) + ':' + port) +
                "\">";
    }

    private String homeTable(String prop, String dflt, Collection<App> toAdd) {
        String config = _context.getProperty(prop, dflt);
        Collection<App> apps = buildApps(_context, config);
        if (toAdd != null) {apps.addAll(toAdd);}
        return renderApps(apps);
    }

    private String configTable(String prop, String dflt) {
        String config = _context.getProperty(prop, dflt);
        Collection<App> apps;
        if (prop.equals(SearchHelper.PROP_ENGINES)) {apps = buildSearchApps(config);}
        else {apps = buildApps(_context, config);}
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
            if (full) {buf.append(app.desc).append(S);}
            buf.append(app.url).append(S);
            if (full) {buf.append(app.icon).append(S);}
        }
        ctx.router().saveConfig(prop, buf.toString());
    }

    private String renderApps(Collection<App> apps) {
        String website = _t("Web Server");
        StringBuilder buf = new StringBuilder(6*1024);
        boolean embedApps = _context.getBooleanProperty(CSSHelper.PROP_EMBED_APPS);
        buf.append("<div class=linkgroup>");
        PortMapper pm = _context.portMapper();
        LogsHelper logsHelper = new LogsHelper();
        logsHelper.setContext(this._context);
        int errorCount = logsHelper.getCriticalLogCount();
        boolean haveErrors = errorCount > 0;
        for (App app : apps) {
            String url;
            if (app.name.equals(website) && app.url.equals("http://127.0.0.1:7658/")) {
                url = SidebarRenderer.getEepsiteURL(pm);
                if (url == null) {continue;}
                else {url = app.url + "\" target=_blank class=\"extlink";}
            } else if ((!embedApps && (app.url.contains("webmail") || app.url.contains("torrents") || app.url.contains("outproxy") ||
                       app.url.contains("bote") || app.url.contains("orchid") || app.url.contains("BwSchedule"))) || app.url.endsWith("tracker/") ||
                       app.url.contains(".i2p") || app.url.contains("history.txt") || app.url.contains("prometheus/status.html")) {
                url = app.url + "\" target=_blank class=\"extlink";
            } else {
                url = app.url;
                if (url.contains("/dns")) {
                    if (!pm.isRegistered(PortMapper.SVC_SUSIDNS)) {continue;}
                } else if (url.equals("/webmail")) {
                    if (!pm.isRegistered(PortMapper.SVC_SUSIMAIL)) {continue;}
                } else if (url.equals("/torrents")) {
                    if (!pm.isRegistered(PortMapper.SVC_I2PSNARK)) {continue;}
                } else if (url.equals("/i2ptunnelmgr") || url.equals("/tunnelmanager")) {
                    if (!pm.isRegistered(PortMapper.SVC_I2PTUNNEL)) {continue;}
                } else if (url.equals("/configplugins")) {
                    if (!PluginStarter.pluginsEnabled(_context)) {continue;}
                } else if (url.equals("/routerlogs")) {
                    if (haveErrors) {
                        url = "/errorlogs";
                        app.icon = I + "logsError.svg";
                        app.desc += " / " + _t("Errors") + ":" + errorCount;
                    }
                }
            }
            buf.append("\n<div class=\"applink");
                if (url.contains("i2pmetrics") || url.contains("paste.r4sas") || url.contains("speedtest") ||
                url.contains("vuldb") || url.contains("meduza") || url.contains("mdn") || url.contains("ardullm") ||
                url.contains("w3schools") || url.contains("translate.idk")) {
                buf.append(" js");
            }
            buf.append("\" style=display:inline-block;text-align:center><div class=appicon><a href=\"")
               .append(url)
               .append("\" tabindex=-1><img alt=\"\" title=\"")
               .append(app.desc)
               .append("\" src=\"")
               .append(app.icon)
               .append("\" width=32 height=32></a></div><table><tr><td><div class=applabel><a href=\"")
               .append(url)
               .append("\" title=\"")
               .append(app.desc)
               .append("\">")
               .append(app.name)
               .append("</a></div></td></tr></table></div>");
            }
            buf.append("</div>\n");
            return buf.toString();
        }

    private String renderConfig(Collection<App> apps) {
        StringBuilder buf = new StringBuilder(64*1024);
        buf.append("<table class=homelinkedit><tr><th class=center title=\"")
           .append(_t("Mark for deletion"))
           .append("\">")
           .append(_t("Remove"))
           .append("</th><th></th><th>")
           .append(_t("Name"))
           .append("</th><th>")
           .append(_t("URL"))
           .append("</th></tr>\n");
        for (App app : apps) {
            String url = DataHelper.escapeHTML(app.url);
            buf.append("<tr><td class=center><input type=checkbox class=optbox name=\"delete_")
               .append(app.name).append("\" id=\"");
            if (url.contains("%s")) {buf.append("search_");}
            buf.append(app.name.replace(" ", "_").replace("\'", ""))
               .append("\"></td>");
            if (app.icon != null) {
                buf.append("<td><img width=20 height=20 alt=\"\" src=\"")
                   .append(app.icon)
                   .append("\">");
            } else {buf.append("<td class=noicon>");}
            buf.append("</td><td><label for=\"");
            if (url.contains("%s")) {buf.append("search_");}
            buf.append(app.name.replace(" ", "_").replace("\'", ""))
               .append("\">")
               .append(DataHelper.escapeHTML(app.name))
               .append("</label></td><td><a href=\"")
               .append(url).append("\">");
            String urltext = DataHelper.escapeHTML(app.url).replace("&amp;ref=console", "");
            if (app.url.length() > 72) {buf.append(urltext.substring(0, 70)).append("&hellip;");}
            else {buf.append(urltext);}
            buf.append("</a></td></tr>\n");
        }
        buf.append("<tr class=addnew><td colspan=2><b>")
           .append(_t("Add"))
           .append(":</b></td><td><input type=text name=nofilter_name></td><td><input type=text size=40 name=nofilter_url></td></tr></table>\n");
        return buf.toString();
    }

    /** ignore case, current locale */
    private static class AppComparator implements Comparator<App>, Serializable {
        public int compare(App l, App r) {return l.name.toLowerCase().compareTo(r.name.toLowerCase());}
    }

}
