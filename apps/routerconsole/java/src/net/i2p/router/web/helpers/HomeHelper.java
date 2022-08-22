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
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "addressbook.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "graphs.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "mail.svg" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help" + S + I + "help.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "pluginconfig.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "globe.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/logs" + S + I + "logs.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/i2ptunnelmgr" + S + I + "tunnelmanager.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "webserver.svg" + S +
        "";

    static final String NEWINSTALL_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "addressbook.svg" + S +
        _x("Configure Bandwidth") + S + _x("I2P Bandwidth Configuration") + S + "/config" + S + I + "speedometer.svg" + S +
        // FIXME wasn't escaped
        _x("Configure UI") + S + _x("Select console theme & language & set optional console password").replace("&", "&amp;") + S + "/configui" + S + I + "ui.svg" + S +
        _x("Customize Sidebar") + S + _x("Customize the sidebar by adding or removing or repositioning elements") + S + "/configsidebar" + S + I + "sidebar.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "graphs.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "mail.svg" + S +
        _x("Help") + S + _x("I2P Router Help") + S + "/help" + S + I + "help.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "pluginconfig.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "globe.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/logs" + S + I + "logs.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/i2ptunnelmgr" + S + I + "tunnelmanager.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "webserver.svg" + S +
        _x("Wizard") + S + _x("Configuration and bandwidth tester") + S + "/wizard" + S + I + "wizard.svg" + S +
        "";

    static final String ADVANCED_SERVICES =
        _x("Addressbook") + S + _x("Manage your I2P hosts file here (I2P domain name resolution)") + S + "/dns" + S + I + "addressbook.svg" + S +
        _x("Advanced Config") + S + _x("Advanced router configuration") + S + "/configadvanced" + S + I + "configure.svg" + S +
        _x("Changelog") + S + _x("Recent changes") + S + "/help/changelog" + S + I + "changelog.svg" + S +
        _x("Graphs") + S + _x("Graph Router Performance") + S + "/graphs" + S + I + "graphs.svg" + S +
        _x("I2PMail") + S + _x("Anonymous webmail client") + S + "/webmail" + S + I + "mail.svg" + S +
        _x("Manage Plugins") + S + _x("Install and configure I2P plugins") + S + "/configplugins" + S + I + "pluginconfig.svg" + S +
        _x("Network Database") + S + _x("Show list of all known I2P routers") + S + "/netdb" + S + I + "globe.svg" + S +
        _x("Router Info") + S + _x("Summary of router properties") + S + "/info" + S + I + "info.svg" + S +
        _x("Router Logs") + S + _x("Health Report") + S + "/logs" + S + I + "logs.svg" + S +
        _x("Sitemap") + S + _x("Router Sitemap") + S + "/sitemap" + S + I + "sitemap.svg" + S +
        _x("Torrents") + S + _x("Built-in anonymous BitTorrent Client") + S + "/torrents" + S + I + "snark.svg" + S +
        _x("Tunnel Manager") + S + _x("Manage server and client tunnels") + S + "/i2ptunnelmgr" + S + I + "tunnelmanager.svg" + S +
        _x("Web Server") + S + _x("Local web server for hosting your own content on I2P") + S + "http://127.0.0.1:7658/" + S + I + "webserver.svg" + S +
        "";

    // No commas allowed in text strings!
    static final String DEFAULT_FAVORITES =

// I2P specific
        _x("I2P Bug Reports") + S + _x("Bug tracker") + S + "http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues" + S + I + "bug.svg" + S +
        _x("I2P FAQ") + S + _x("Frequently Asked Questions") + S + "http://i2p-projekt.i2p/faq" + S + I + "faq.svg" + S +
        _x("I2P Plugins") + S + _x("zzz's plugin repository") + S + "http://stats.i2p/i2p/plugins/" + S + I + "plugin.svg" + S +
        "i2pmetrics.i2p" + S + _x("Historical infrastructure data from the I2P network") + S + "http://i2pmetrics.i2p/" + S + I + "stats.svg" + S +
        _x("Project Website") + S + _x("I2P home page") + S + "http://i2p-projekt.i2p/" + S + I + "i2p.svg" + S +
        "stats.i2p" + S + _x("I2P Network Statistics") + S + "http://stats.i2p/cgi-bin/dashboard.cgi" + S + I + "stats.svg" + S +
        _x("Javadocs") + S + _x("I2P+ API documentation") + S + "http://javadoc.skank.i2p/" + S + I + "helplink.svg" + S +

// software repositories + filesharing
        "cake.i2p" + S + _x("Transient server-encrypted filesharing and pastebin") + S + "http://cake.i2p/" + S + I + "cake.svg" + S +
        "fs.i2p" + S + _x("Secure filesharing service") + S + "http://fs.i2p/" + S + I + "cloud.svg" + S +
        "git.i2p" + S + _x("Community Git hosting") + S + "http://git.i2p/explore/repos" + S + I + "git.svg" + S +
        "git.idk.i2p" + S + _x("Official I2P Git repository") + S + "http://git.idk.i2p/explore/projects" + S + I + "gitlab.svg" + S +
        "sharefile.i2p" + S + _x("Secure filesharing service") + S + "http://sharefile.i2p/" + S + I + "cloud.svg" + S +
        "skank.i2p" + S + _x("Home of I2P+") + S + "http://skank.i2p/" + S + I + "plus.svg" + S +

// torrent trackers
        _x("Postman's Tracker") + S + _x("Bittorrent tracker") + S + "http://tracker2.postman.i2p/" + S + I + "postman.svg" + S +
        //_x("Chudo") + S + _x("No login Bittorrent tracker") + S + "http://torrents.chudo.i2p/" + S + I + "magnet.svg" + S +

// domain name registration + uptime trackers
        "Identiguy" + S + _x("List of active eepsites and uptime monitor") + S + "http://identiguy.i2p/" + S + I + "servermonitor.svg" + S +
        "notbob.i2p" + S + _x("List of active eepsites and uptime monitor") + S + "http://notbob.i2p/" + S + I + "notbob.png" + S +
        "reg.i2p" + S + _x("I2P Domain Name Registration") + S + "http://reg.i2p/" + S + I + "reg.svg" + S +
        "scanner.linuxfarm" + S + _x("List of active eepsites and uptime monitor") + S + "http://scanner.linuxfarm.i2p/" + S + I + "stats.svg" + S +
        _x("zzz Domain Registry") + S + _x("I2P Domain Name Registration") + S + "http://stats.i2p/i2p/addkey.html" + S + I + "registrar.svg" + S +

// forums + social
        _x("Dev Forum") + S + _x("Development forum") + S + "http://zzz.i2p/" + S + I + "forum.svg" + S +
        "dread.i2p" + S + _x("Darknet forums") + S + "http://dread.i2p/" + S + I + "dread.svg" + S +
        _x("I2P Forum") + S + _x("I2P-related community forums") + S + "http://i2pforum.i2p/" + S + I + "forum.svg" + S +
        "discuss.i2p" + S + _x("File-sharing forum") + S + "http://discuss.i2p/" + S + I + "forum.svg" + S +
        "libreddit.i2p" + S + _x("Alternative privacy-focused front-end for Reddit") + S + "http://libreddit.i2p/" + S + I + "libreddit.svg" + S +
        "nitter" + S + _x("Alternative front-end for Twitter") + S + "http://nitter.skank.i2p/" + S + I + "twitter.svg" + S +
        "novabbs.i2p" + S + _x("Rocksolid forums for the darknets") + S + "http://novabbs.i2p/" + S + I + "forum.svg" + S +
        "ramble.i2p" + S + _x("Cross-network micro-blogging &amp; forums &amp; wiki") + S + "http://ramble.i2p/" + S + I + "ramble.svg" + S +
        "teddit.i2p" + S + _x("Alternative privacy-focused front-end for Reddit") + S + "http://teddit.i2p/" + S + I + "teddit.svg" + S +
        //"zeronet.i2p" + S + _x("Zeronet I2P Gateway") + S + "http://zeronet.i2p/" + S + I + "zeronet.svg" + S +
        //_x("Dancing Elephants") + S + _x("Rocksolid forums for the darknets") + S + "http://def3.i2p/" + S + I + "forum.svg" + S +
        //"query.i2p" + S + _x("The StackOverflow of I2P") + S + "http://query.i2p/" + S + I + "forum.svg" + S +

// hosting + other services
           "major.i2p" + S + _x("IRC Logs for multiple networks") + S + "http://major.i2p/" + S + I + "major.svg" + S +
        _x("Pastebin") + S + _x("Encrypted I2P Pastebin") + S + "http://paste.r4sas.i2p/" + S + I + "paste.svg" + S +
        _x("radio.r4sas.i2p") + S + _x("Streaming radio service") + S + "http://radio.r4sas.i2p/" + S + I + "radio.svg" + S +
           "tube.i2p" + S + _x("Alternative front-end to Youtube") + S + "http://tube.i2p/" + S + I + "tv.svg" + S +
           "tinypng.i2p" + S + _x("Image optimizer") + S + "http://tinypng.i2p/" + S + I + "tinypng.png" + S +
           "stormycloud.i2p" + S + _x("Privacy-focused not-for-profit organization") + S + "http://stormycloud.i2p/" + S + I + "stormycloud.svg" + S +
           "speedtest" + S + _x("Outproxy bandwidth test") + S + "http://outproxy.purokishi.i2p/speedtest/" + S + I + "speedometer.svg" + S +
           "webhosting.i2p" + S + _x("Provider of privacy respecting web hosting and VPN services") + S + "http://webhosting.i2p/" + S + I + "incog.svg" + S +
        //_x("Deep Web Radio") + S + _x("Streaming radio service") + S + "http://deepwebradio.i2p/" + S + I + "radio.svg" + S +

// search engines
        "ahmia.i2p" + S +_x("Tor-focused Search Engine") + S + "http://ahmia.i2p/" + S + I + "onionsearch.svg" + S +
        "DuckDuckGo" + S +_x("DuckDuckGo Search Engine (no javascript)") + S + "http://duckduckgo.i2p/" + S + I + "duckduckgo.svg" + S +
        "btdigg.i2p" + S +_x("Clearnet BitTorrent DHT search engine") + S + "http://btdigg.i2p/" + S + I + "torrentsearch.svg" + S +
        "I2PSearch" + S +_x("I2P Web Search Engine") + S + "http://i2psearch.i2p/" + S + I + "search.svg" + S +
        "Legwork" + S +_x("I2P Web Search Engine") + S + "http://legwork.i2p/" + S + I + "search.svg" + S +
        "Mojeek" + S +_x("Privacy-focused Clearnet Search Engine") + S + "http://mojeek.i2p/" + S + I + "search.svg" + S +
        "raklet.i2p" + S +_x("Tor-focused Search Engine") + S + "http://raklet.i2p/" + S + I + "onionsearch.svg" + S +
        "Ransack" + S +_x("I2P-based Metasearch Engine") + S + "http://ransack.i2p/" + S + I + "search.svg" + S +
        "torch.i2p" + S +_x("Tor-focused Search Engine") + S + "http://torch.i2p/" + S + I + "onionsearch.svg" + S +

// wikis & reference
        "ddosecrets.i2p" + S + _x("Enabling the free transmission of data in the public interest") + S + "http://ddosecrets.i2p/" + S + I + "wiki.svg" + S +
        "ebooks.i2p" + S + _x("Huge collection of books &amp; magazines &amp; comics") + S + "http://ebooks.i2p/" + S + I + "books.svg" + S +
        "freehaven.i2p" + S + _x("Selected Papers in Anonymity") + S + "http://freehaven.i2p/" + S + I + "books.svg" + S +
        "hiddenwiki.i2p" + S + _x("Tor .onion site links") + S + "http://hiddenwiki.i2p/" + S + I + "wiki.svg" + S +
        _x("HTMLColors") + S + _x("HTML Color Reference") + S + "http://htmlcolors.i2p/" + S + I + "help.svg" + S +
        "I2P Wiki" + S + _x("I2P-related Wiki") + S + "http://wiki.i2p-projekt.i2p/" + S + I + "wiki.svg" + S +
        "imperial.i2p" + S + _x("Extensive repository of DRM-free ebooks") + S + "http://imperial.i2p/" + S + I + "books.svg" + S +
        "legalcases.i2p" + S + _x("Publicly available texts of court decisions all over the world") + S + "http://legalcases.i2p/" + S + I + "books.svg" + S +
        "mathworld.i2p" + S + _x("Wolfram's Online Math Reference") + S + "http://mathworld.i2p/" + S + I + "books.svg" + S +
        "mdn.i2p" + S + _x("Mozilla Developer Network") + S + "http://mdn.i2p/" + S + I + "mdn.svg" + S +
        "Philosopedia" + S + _x("Internet Encylopedia of Philosophy") + S + "http://philosopedia.i2p/" + S + I + "books.svg" + S +
        "plato.i2p" + S + _x("Standford Encylopedia of Philosophy") + S + "http://plato.i2p/" + S + I + "plato.png" + S +
        _x("Psychonaut") + S + _x("Wiki relating to altered states of consciousness") + S + "http://psychonaut.i2p/" + S + I + "psy.svg" + S +
        _x("SimplifiedGuide") + S + _x("Computing-related tutorials") + S + "http://simplifiedguide.i2p/" + S + I + "help.svg" + S +
        _x("UrbanDictionary") + S + _x("Crowd-sourced slang dictionary") + S + "http://urbandictionary.i2p/" + S + I + "urbandictionary.svg" + S +
        "vuldb.i2p" + S + _x("Vulnerability Database") + S + "http://vuldb.i2p/" + S + I + "vuldb.svg" + S +
        "w3schools.i2p" + S + _x("Web technology reference and learning site") + S + "http://w3schools.i2p/" + S + I + "w3schools.svg" + S +
        "wikiless.i2p" + S + _x("Alternative Wikipedia front-end focused on privacy") + S + "http://wikiless.i2p/" + S + I + "wiki.svg" + S +
        "Wordnik" + S + _x("The biggest online English dictionary") + S + "http://wordnik.i2p/" + S + I + "wordnik.svg" + S +
        //"jalibrary.i2p" + S + _x("Books &amp; courses &amp; art") + S + "http://jalibrary.i2p/" + S + I + "books.svg" + S +
        //"nexus.i2p" + S + _x("Searchable science library") + S + "http://nexus.i2p/" + S + I + "nexus.svg" + S +
        //"tome.i2p" + S + _x("Collection of books &amp; other reading material") + S + "http://tome.i2p/" + S + I + "books.svg" + S +

// news + blogs
        "ArsTechnica" + S + _x("Technology News &amp; more...") + S + "http://arstechnica.i2p/" + S + I + "arstechnica.svg" + S +
        "bellingcat.i2p" + S + _x("Independent investigative journalism") + S + "http://bellingcat.i2p/" + S + I + "bellingcat.svg" + S +
        "BenthamsGaze" + S + _x("Information Security Research &amp; Education") + S + "http://benthamsgaze.i2p/" + S + I + "benthamsgaze.png" + S +
        _x("ChinaDigitalTimes") + S + _x("Covering China from Cyberspace") + S + "http://chinadigitaltimes.i2p/" + S + I + "chinadigitaltimes.png" + S +
        "CultureMagazine" + S + _x("Cannabis-related magazine") + S + "http://culturemagazine.i2p/" + S + I + "cannabis.svg" + S +
        "DarkDotNet" + S + _x("Darknet related news site") + S + "http://darkdotnet.i2p/" + S + I + "news.svg" + S +
        "DarkNetDaily" + S + _x("Darknet related news site") + S + "http://darknetdaily.i2p/" + S + I + "news.svg" + S +
        "DarknetLive" + S + _x("Darknet related news site") + S + "http://darknetlive.i2p/" + S + I + "news.svg" + S +
        _x("DigitalTrends") + S + _x("Tech News &amp; Reviews") + S + "http://digitaltrends.i2p/" + S + I + "digitaltrends.svg" + S +
        "EllipticNews" + S + _x("The Elliptic Curve Cryptography Blog") + S + "http://ellipticnews.i2p/" + S + I + "news.svg" + S +
        "FrontLineDefenders" + S + _x("Human rights activism") + S + "http://frontlinedefenders.i2p/" + S + I + "news.svg" + S +
        "HumanRightsWatch" + S + _x("Defending Human Rights Worldwide") + S + "http://humanrightswatch.i2p/" + S + I + "news.svg" + S +
        "Hackaday" + S + _x("Fresh hacks every day") + S + "http://hackaday.i2p/" + S + I + "hackaday.svg" + S +
        "HackerNews" + S + _x("Computer related news aggregation") + S + "http://hackernews.i2p/" + S + I + "news.svg" + S +
        "High Times" + S + _x("Cannabis-related magazine") + S + "http://hightimes.i2p/" + S + I + "cannabis.svg" + S +
        _x("HongKongFreePress") + S + _x("Independent &amp; impartial news for Hong Kong") + S + "http://hongkongfreepress.i2p/" + S + I + "news.svg" + S +
        "hrichina.i2p" + S + _x("Human Rights in China") + S + "http://hrichina.i2p/" + S + I + "news.svg" + S +
        _x("JapanToday") + S + _x("English language Japanese News") + S + "http://japantoday.i2p/" + S + I + "japantoday.svg" + S +
        _x("KrebsOnSecurity") + S + _x("In-depth security news &amp; investigation") + S + "http://krebsonsecurity.i2p/" + S + I + "krebs.png" + S +
        _x("SchneierOnSecurity") + S + _x("Internationally renowned security technologist") + S + "http://schneieronsecurity.i2p/" + S + I + "schneier.png" + S +
        "LinuxFarm" + S + _x("Router performance monitoring and Linux tips") + S + "http://linuxfarm.i2p/" + S + I + "linuxfarm.svg" + S +
        "LKML" + S + _x("Linux Kernel Mailing List") + S + "http://lkml.i2p/" + S + I + "news.svg" + S +
        _x("MetaCritic") + S + _x("Movie + TV + Music + Game reviews") + S + "http://metacritic.i2p/" + S + I + "metacritic.svg" + S +
        _x("NakedSecurity") + S + _x("Security news and research blog") + S + "http://nakedsecurity.i2p/" + S + I + "news.svg" + S +
        "MotherJones" + S + _x("Smart &amp; fearless journalism") + S + "http://motherjones.i2p/" + S + I + "news.svg" + S +
        _x("Newsweek") + S + _x("American &amp International News") + S + "http://newsweek.i2p/" + S + I + "newsweek.svg" + S +
        _x("NewsNow") + S + _x("The Independent News Discovery Platform") + S + "http://newsnow.i2p/" + S + I + "newsnow.svg" + S +
        _x("Planet I2P") + S + _x("I2P News") + S + "http://planet.i2p/" + S + I + "planet.svg" + S +
        "Phayul" + S + _x("News &amp; Views on Tibet") + S + "http://phayul.i2p/" + S + I + "news.svg" + S +
        _x("SaveTibet") + S + _x("International Campaign for Tibet") + S + "http://savetibet.i2p/" + S + I + "savetibet.svg" + S +
        "RollingStone" + S + _x("Lifestyle magazine") + S + "http://rollingstone.i2p/" + S + I + "rollingstone.svg" + S +
        _x("ScienceDaily") + S + _x("Latest scientific research news") + S + "http://sciencedaily.i2p/" + S + I + "science.svg" + S +
        "SoylentNews" + S + _x("Community-driven tech news") + S + "http://soylentnews.i2p/" + S + I + "news.svg" + S +
        "Slashdot" + S + _x("News for nerds") + S + "http://slashdot.i2p/" + S + I + "slashdot.svg" + S +
        "SlashGear" + S + _x("Technology news &amp; reviews") + S + "http://slashgear.i2p/" + S + I + "slashgear.svg" + S +
        _x("TaipeiTimes") + S + _x("News from the Taiwan Capital") + S + "http://taipeitimes.i2p/" + S + I + "news.svg" + S +
        "TechXplore" + S + _x("Technology and engineering news") + S + "http://techxplore.i2p/" + S + I + "news.svg" + S +
        "TechMeme" + S + _x("Technology news aggregator") + S + "http://techmeme.i2p/" + S + I + "news.svg" + S +
        _x("TheConversation") + S + _x("Academic commentary and essays") + S + "http://theconverastion.i2p/" + S + I + "theconversation.svg" + S +
        "TheMarkup" + S + _x("Watching Big Tech...") + S + "http://themarkup.i2p/" + S + I + "news.svg" + S +
        _x("TheMoscowTimes") + S + _x("Independent Journalism from Russia") + S + "http://themoscowtimes.i2p/" + S + I + "news.svg" + S +
        _x("TheTibetPost") + S + _x("International News from Tibet") + S + "http://thetibetpost.i2p/" + S + I + "tibetpost.svg" + S +
        _x("ThreatPost") + S + _x("Security related news") + S + "http://threatpost.i2p/" + S + I + "news.svg" + S +
        "TorrentFreak" + S + _x("Filesharing &amp; copyright news") + S + "http://torrentfreak.i2p/" + S + I + "torrentfreak.svg" + S +
        "Ukrinform" + S + _x("Ukranian National News Agency") + S + "http://ukrinform.i2p/" + S + I + "news.svg" + S +
        "Wired" + S + _x("Technology News &amp; more...") + S + "http://wired.i2p/" + S + I + "wired.svg" + S +

        "";

/*
    static final String FAVORITES_RU =
        "333.i2p" + S + _x("i2pd development forums") + S + "http://333.i2p/" + S + I + "forum.svg" + S +

        "";

    static final String DEFAULT_FAVORITES_RU = DEFAULT_FAVORITES.replace("\"\";", "") + FAVORITES_RU;
*/

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
/*
        if (_context.getProperty(Messages.PROP_LANG) == "ru")
            return homeTable(PROP_FAVORITES, DEFAULT_FAVORITES_RU, null);
        else
*/
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
        buf.append("<div class=\"linkgroup\">");
        PortMapper pm = _context.portMapper();
        for (App app : apps) {
            String url;
            if (app.name.equals(website) && app.url.equals("http://127.0.0.1:7658/")) {
                // fixup eepsite link
                url = SummaryBarRenderer.getEepsiteURL(pm);
                if (url == null)
                    continue;
                else
                    url = app.url + "\" target=\"_blank\" class=\"extlink";
            // embed plugins in the console
            } else if ((app.url.contains("bote") && !app.url.contains(".i2p") && (embedApps))) {
                url = "/embed?url=/i2pbote&amp;name=BoteMail";
            } else if ((app.url.contains("BwSchedule") && (embedApps))) {
                url = "/embed?url=/BwSchedule/home&amp;name=Bandwidth+Scheduler";
            } else if ((app.url.contains("outproxy") && !app.url.contains(".i2p") && (embedApps))) {
                url = "/embed?url=/outproxy/&amp;name=SOCKS+Outproxy+Plugin";
            // if external links set to open in new tab, add class so we can indicate external links with overlay
            // plugins need to be manually added
            } else if ((!embedApps && (app.url.contains("webmail") || app.url.contains("torrents") || app.url.contains("outproxy")
                    || app.url.contains("bote") || app.url.contains("orchid") || app.url.contains("BwSchedule")))
                    || app.url.contains(".i2p") || app.url.contains("history.txt")) {
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
            buf.append("\n<div class=\"applink");
            // tag sites that require javascript to function
            if (url.contains("i2pmetrics") || url.contains("paste.r4sas") || url.contains("zeronet") || url.contains("speedtest") ||
                url.contains("tinypng") || url.contains("vuldb"))
                buf.append(" js");
            buf.append("\" style=\"display: inline-block; text-align: center;\">\n" +
                       "<div class=\"appicon\">" +
                       // usability: add tabindex -1 so we avoid 2 tabs per app
                       "<a href=\"").append(url).append("\" tabindex=\"-1\">" +
                       "<img alt=\"\" title=\"").append(app.desc).append("\" src=\"").append(app.icon)
               // don't version the icons to avoid double-caching where they're used in css
               //.append(app.icon.contains("?") ? "&amp;" : "?").append(CoreVersion.VERSION)
               .append("\" width=\"32\" height=\"32\"></a></div>\n<table><tr><td><div class=\"applabel\"><a href=\"")
               .append(url).append("\" title=\"").append(app.desc).append("\">").append(app.name)
               .append("</a></div></td></tr></table>\n</div>");
            }
            buf.append("</div>\n");
            return buf.toString();
        }

    private String renderConfig(Collection<App> apps) {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<table class=\"homelinkedit\"><tr><th class=\"center\" title=\"")
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
            buf.append("<tr><td class=\"center\"><input type=\"checkbox\" class=\"optbox\" name=\"delete_")
               .append(app.name)
               .append("\" id=\"");
            if (url.contains("%s"))
                buf.append("search_");
            buf.append(app.name.replace(" ", "_").replace("\'", ""))
               .append("\"></td>");
            if (app.icon != null) {
                buf.append("<td><img width=\"20\" height=\"20\" alt=\"\" src=\"").append(app.icon).append("\">");
            } else {
                buf.append("<td class=\"noicon\">");
            }
            buf.append("</td><td><label for=\"");
            if (url.contains("%s"))
                buf.append("search_");
            buf.append(app.name.replace(" ", "_").replace("\'", ""))
               .append("\">")
               .append(DataHelper.escapeHTML(app.name))
               .append("</label></td><td><a href=\"");
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
        buf.append("<tr class=\"addnew\"><td colspan=\"2\"><b>")
           .append(_t("Add")).append(":</b>" +
                   "</td><td><input type=\"text\" name=\"nofilter_name\"></td>" +
                   "<td><input type=\"text\" size=\"40\" name=\"nofilter_url\"></td></tr>");
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
