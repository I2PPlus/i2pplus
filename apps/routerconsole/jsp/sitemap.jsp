<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<title>Sitemap - I2P+</title>
<%@include file="css.jsi" %>
</head>
<body class="<%=lang%>">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>

<%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
%>

<h1 class="home"><%=intl._t("Router Sitemap")%></h1>

<%
    String version = net.i2p.CoreVersion.VERSION;
    String firstVersion = ctx.getProperty("router.firstVersion");
    boolean oldHome = ctx.getBooleanProperty("routerconsole.oldHomePage");
    if (oldHome || !version.equals(firstVersion)) {
%>

<div class="news" id="news">
<%
    if (newshelper.shouldShowNews()) {
%>
 <jsp:getProperty name="newshelper" property="content" />
 <hr>
<%
    }  // shouldShowNews()
%>
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=i2pcontextId%>" />
 <jsp:getProperty name="updatehelper" property="newsStatus" /><br>
</div>

<%
    } // only show news section if we're using /sitemap as homepage
%>

<div class="main" id="sitemap">

<div class="search" id="homesearch">
<form action="/search.jsp" target="_blank" rel="noreferrer" method="POST">
<table class="search">
<tr>
<td align="right"><input size="40" type="text" class="search" name="query" required placeholder="<%=intl._t("Please enter a search query")%>" /></td>
<td align="left"><button type="submit" value="search" class="search"><%=intl._t("Search")%></button></td>
<td align="left">
<jsp:useBean class="net.i2p.router.web.helpers.SearchHelper" id="searchhelper" scope="request" />
<jsp:setProperty name="searchhelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:getProperty name="searchhelper" property="selector" />
</td>
</tr>
</table>
</form>
</div>

<div id="sitemapcontainer">

<h3><%=intl._t("Services")%></h3>

<a href="/dns">
<span class="sitemapLink" title="<%=intl._t("Manage I2P addresses")%>">
<img src="/themes/console/images/svg/addressbook.svg">
<span class="sitemapLabel"><%=intl._t("Addressbook")%></span>
</span>
</a>

<a href="/imagegen" target="_blank">
<span class="sitemapLink extlink" title="<%=intl._t("Identification Image Generator")%>">
<img src="/themes/console/images/info/imagegen.png">
<span class="sitemapLabel"><%=intl._t("Imagegen")%></span>
</span>
</a>

<%
    boolean embedApps = ctx.getBooleanProperty("routerconsole.embedApps");
%>

<% if (!embedApps) { %>
<a href="/torrents" target="_blank" rel="noreferrer">
<span class="sitemapLink extlink" title="<%=intl._t("Create and download torrents")%>">
<% } else { %>
<a href="/torrents">
<span class="sitemapLink" title="<%=intl._t("Create and download torrents")%>">
<% } %>
<img src="/themes/console/images/svg/snark.svg">
<span class="sitemapLabel"><%=intl._t("Torrents")%></span>
</span>
</a>

<a href="/i2ptunnelmgr">
<span class="sitemapLink" title="<%=intl._t("Manage client and server tunnels")%>">
<img src="/themes/console/images/svg/tunnelmanager.svg">
<span class="sitemapLabel"><%=intl._t("Tunnel Manager")%></span>
</span>
</a>

<% if (!embedApps) { %>
<a href="/webmail" target="_blank" rel="noreferrer">
<span class="sitemapLink extlink" title="<%=intl._t("Webmail client")%>">
<% } else { %>
<a href="/webmail">
<span class="sitemapLink" title="<%=intl._t("Webmail client")%>">
<% } %>
<img src="/themes/console/images/svg/mail.svg">
<span class="sitemapLabel"><%=intl._t("Webmail")%></span>
</span>
</a>

<a href="http://127.0.0.1:7658/" target="_blank" rel="noreferrer">
<span class="sitemapLink extlink" title="<%=intl._t("Local webserver for hosting content on the I2P network")%>">
<img src="/themes/console/images/svg/webserver.svg">
<span class="sitemapLabel"><%=intl._t("Web Server")%></span>
</span>
</a>

<h3><%=intl._t("Information &amp; Diagnostics")%></h3>

<a href="/certs">
<span class="sitemapLink" title="<%=intl._t("Review active encryption certificates used in console")%>">
<img src="/themes/console/images/svg/certs.svg">
<span class="sitemapLabel"><%=intl._t("Certificates")%></span>
</span>
</a>

<a href="/help/changelog">
<span class="sitemapLink" title="<%=intl._t("Review recent updates to I2P")%>">
<img src="/themes/console/images/svg/changelog.svg">
<span class="sitemapLabel"><%=intl._t("Change Log")%></span>
</span>
</a>

<a href="/debug">
<span class="sitemapLink" title="<%=intl._t("View debugging information")%>">
<img src="/themes/console/images/svg/bug.svg">
<span class="sitemapLabel"><%=intl._t("Debugging")%></span>
</span>
</a>

<a href="/events?from=604800">
<span class="sitemapLink" title="<%=intl._t("View historical log of router events")%>">
<img src="/themes/console/images/svg/logs.svg">
<span class="sitemapLabel"><%=intl._t("Event Log")%></span>
</span>
</a>

<a href="/graphs">
<span class="sitemapLink" title="<%=intl._t("View router performance graphs")%>">
<img src="/themes/console/images/svg/graphs.svg">
<span class="sitemapLabel"><%=intl._t("Graphs")%></span>
</span>
</a>

<a href="/help/">
<span class="sitemapLink" title="<%=intl._t("Router help section")%>">
<img src="/themes/console/images/svg/help.svg">
<span class="sitemapLabel"><%=intl._t("Help Section")%></span>
</span>
</a>

<a href="/home">
<span class="sitemapLink" title="<%=intl._t("Router homepage")%>">
<img src="/themes/console/images/svg/home.svg">
<span class="sitemapLabel"><%=intl._t("Home")%></span>
</span>
</a>

<a href="/news">
<span class="sitemapLink" title="<%=intl._t("View news relating to I2P")%>">
<img src="/themes/console/images/svg/news.svg">
<span class="sitemapLabel"><%=intl._t("I2P News")%></span>
</span>
</a>

<a href="/jars">
<span class="sitemapLink" title="<%=intl._t("Review extended info about installed .jar and .war files")%>">
<img src="/themes/console/images/svg/package.svg">
<span class="sitemapLabel"><%=intl._t("Jars")%></span>
</span>
</a>

<a href="/jobs">
<span class="sitemapLink" title="<%=intl._t("View router workload and stats")%>">
<img src="/themes/console/images/svg/jobs.svg">
<span class="sitemapLabel"><%=intl._t("Router Jobs")%></span>
</span>
</a>

<a href="/logs">
<span class="sitemapLink" title="<%=intl._t("View router logs")%>">
<img src="/themes/console/images/svg/logs.svg">
<span class="sitemapLabel"><%=intl._t("Router Logs")%></span>
</span>
</a>

<a href="/netdb">
<span class="sitemapLink" title="<%=intl._t("View information and stats regarding all known peers")%>">
<img src="/themes/console/images/svg/globe.svg">
<span class="sitemapLabel"><%=intl._t("NetDb")%></span>
</span>
</a>

<a href="/netdb?f=4">
<span class="sitemapLink" title="<%=intl._t("Network database search tool")%>">
<img src="/themes/console/images/svg/searchnetdb.svg">
<span class="sitemapLabel"><%=intl._t("NetDb Search")%></span>
</span>
</a>

<a href="/peers">
<span class="sitemapLink" title="<%=intl._t("View active peers")%>">
<img src="/themes/console/images/svg/peers.svg">
<span class="sitemapLabel"><%=intl._t("Peers")%></span>
</span>
</a>

<a href="/profiles?f=1">
<span class="sitemapLink" title="<%=intl._t("View peer performance profiles")%>">
<img src="/themes/console/images/svg/profile.svg">
<span class="sitemapLabel"><%=intl._t("Profiles")%></span>
</span>
</a>

<a href="/info">
<span class="sitemapLink" title="<%=intl._t("View information about router and running environment")%>">
<img src="/themes/console/images/svg/info.svg">
<span class="sitemapLabel"><%=intl._t("Router Info")%></span>
</span>
</a>

<a href="/stats">
<span class="sitemapLink" title="<%=intl._t("Textual router performance statistics")%>">
<img src="/themes/console/images/svg/textstats.svg">
<span class="sitemapLabel"><%=intl._t("Statistics")%></span>
</span>
</a>

<a href="/netdb?f=3">
<span class="sitemapLink" title="<%=intl._t("Review possible sybils in network database")%>">
<img src="/themes/console/images/svg/sybil.svg">
<span class="sitemapLabel"><%=intl._t("Sybil Analysis")%></span>
</span>
</a>

<a href="/tunnels">
<span class="sitemapLink" title="<%=intl._t("View active tunnels")%>">
<img src="/themes/console/images/svg/hardhat.svg">
<span class="sitemapLabel"><%=intl._t("Tunnels")%></span>
</span>
</a>

<h3><%=intl._t("Configuration")%></h3>

<a href="/configadvanced">
<span class="sitemapLink" title="<%=intl._t("Advanced Router Configuration")%>">
<img src="/themes/console/images/svg/configure.svg">
<span class="sitemapLabel"><%=intl._t("Advanced")%></span>
</span>
</a>

<a href="/config">
<span class="sitemapLink" title="<%=intl._t("Configure router bandwidth allocation")%>">
<img src="/themes/console/images/svg/speedometer.svg">
<span class="sitemapLabel"><%=intl._t("Bandwidth")%></span>
</span>
</a>

<a href="/configclients">
<span class="sitemapLink" title="<%=intl._t("Start or stop Router clients")%>">
<img src="/themes/console/images/svg/editclient.svg">
<span class="sitemapLabel"><%=intl._t("Clients")%></span>
</span>
</a>

<a href="/confighome">
<span class="sitemapLink" title="<%=intl._t("Customize homepage")%>">
<img src="/themes/console/images/svg/home.svg">
<span class="sitemapLabel"><%=intl._t("Homepage")%></span>
</span>
</a>

<a href="/configi2cp">
<span class="sitemapLink" title="<%=intl._t("Configure I2P Client Control Interface")%>">
<img src="/themes/console/images/svg/i2cp.svg">
<span class="sitemapLabel"><%=intl._t("I2CP")%></span>
</span>
</a>

<a href="/configkeyring">
<span class="sitemapLink" title="<%=intl._t("View or edit router keyring for encrypted destinations")%>">
<img src="/themes/console/images/svg/keys.svg">
<span class="sitemapLabel"><%=intl._t("Keyring")%></span>
</span>
</a>

<a href="/configlogging">
<span class="sitemapLink" title="<%=intl._t("Configure router logging options")%>">
<img src="/themes/console/images/svg/logs.svg">
<span class="sitemapLabel"><%=intl._t("Logging")%></span>
</span>
</a>

<a href="/confignet">
<span class="sitemapLink" title="<%=intl._t("Configure router networking options")%>">
<img src="/themes/console/images/svg/networkconfig.svg">
<span class="sitemapLabel"><%=intl._t("Network")%></span>
</span>
</a>

<a href="/configpeer">
<span class="sitemapLink" title="<%=intl._t("Manually edit peers and view banned peers and blocklist")%>">
<img src="/themes/console/images/svg/peerconfig.svg">
<span class="sitemapLabel"><%=intl._t("Peers")%></span>
</span>
</a>

<a href="/configplugins">
<span class="sitemapLink" title="<%=intl._t("Install and manage router plugins")%>">
<img src="/themes/console/images/svg/pluginconfig.svg">
<span class="sitemapLabel"><%=intl._t("Plugins")%></span>
</span>
</a>

<a href="/configreseed">
<span class="sitemapLink" title="<%=intl._t("Router reseeding and configuration")%>">
<img src="/themes/console/images/svg/reseed.svg">
<span class="sitemapLabel"><%=intl._t("Reseeding")%></span>
</span>
</a>

<a href="/configfamily">
<span class="sitemapLink" title="<%=intl._t("Configure and manage Router family")%>">
<img src="/themes/console/images/svg/family.svg">
<span class="sitemapLabel"><%=intl._t("Router Family")%></span>
</span>
</a>

<a href="/configservice">
<span class="sitemapLink" title="<%=intl._t("Manage Router service")%>">
<img src="/themes/console/images/svg/service.svg">
<span class="sitemapLabel"><%=intl._t("Router Service")%></span>
</span>
</a>

<a href="/configstats">
<span class="sitemapLink" title="<%=intl._t("Configure stat collection for graphing")%>">
<img src="/themes/console/images/svg/graphs.svg">
<span class="sitemapLabel"><%=intl._t("Router Stats")%></span>
</span>
</a>

<a href="/configupdate">
<span class="sitemapLink" title="<%=intl._t("Configure router updates")%>">
<img src="/themes/console/images/svg/update.svg">
<span class="sitemapLabel"><%=intl._t("Router Updates")%></span>
</span>
</a>

<a href="/wizard">
<span class="sitemapLink" title="<%=intl._t("Configuration wizard for new users")%>">
<img src="/themes/console/images/svg/wizard.svg">
<span class="sitemapLabel"><%=intl._t("Setup Wizard")%></span>
</span>
</a>

<a href="/configsidebar">
<span class="sitemapLink" title="<%=intl._t("Customize sidebar")%>">
<img src="/themes/console/images/svg/sidebar.svg">
<span class="sitemapLabel"><%=intl._t("Sidebar")%></span>
</span>
</a>

<a href="/configtunnels">
<span class="sitemapLink" title="<%=intl._t("Configure tunnel options")%>">
<img src="/themes/console/images/svg/hardhat.svg">
<span class="sitemapLabel"><%=intl._t("Tunnels")%></span>
</span>
</a>

<a href="/configui">
<span class="sitemapLink" title="<%=intl._t("Configure router user interface and optional console password")%>">
<img src="/themes/console/images/svg/ui.svg">
<span class="sitemapLabel"><%=intl._t("User Interface")%></span>
</span>
</a>

<a href="/configwebapps">
<span class="sitemapLink" title="<%=intl._t("Manage Router webapps")%>">
<img src="/themes/console/images/svg/webapps.svg">
<span class="sitemapLabel"><%=intl._t("Webapps")%></span>
</span>
</a>

<%
    if (version.equals(firstVersion)) {
%>

<h3><%=intl._t("Help &amp; Support")%></h3>

<a href="/help/advancedsettings">
<span class="sitemapLink" title="<%=intl._t("Advanced configuration settings")%>">
<img src="/themes/console/images/svg/configure.svg">
<span class="sitemapLabel"><%=intl._t("Advanced Settings")%></span>
</span>
</a>

<a href="/help/configuration">
<span class="sitemapLink" title="<%=intl._t("Configuring I2P for optimal performance")%>">
<img src="/themes/console/images/info/optimize.png">
<span class="sitemapLabel"><%=intl._t("Configuration")%></span>
</span>
</a>

<a href="/help/faq">
<span class="sitemapLink" title="<%=intl._t("Frequently Asked Questions")%>">
<img src="/themes/console/images/svg/faq.svg">
<span class="sitemapLabel"><%=intl._t("FAQ")%></span>
</span>
</a>

<a href="/help/glossary">
<span class="sitemapLink" title="<%=intl._t("I2P Terminology")%>">
<img src="/themes/console/images/svg/faq.svg">
<span class="sitemapLabel"><%=intl._t("Glossary")%></span>
</span>
</a>

<a href="/help/legal">
<span class="sitemapLink" title="<%=intl._t("Licensing and copyright notices")%>">
<img src="/themes/console/images/svg/legal.svg">
<span class="sitemapLabel"><%=intl._t("Legal")%></span>
</span>
</a>

<a href="/help/newusers">
<span class="sitemapLink" title="<%=intl._t("Information for new users")%>">
<img src="/themes/console/images/svg/help.svg">
<span class="sitemapLabel"><%=intl._t("New Users")%></span>
</span>
</a>

<a href="/help/reseed">
<span class="sitemapLink" title="<%=intl._t("A guide to reseeding your I2P router")%>">
<img src="/themes/console/images/svg/reseed.svg">
<span class="sitemapLabel"><%=intl._t("Reseeding")%></span>
</span>
</a>

<a href="/help/troubleshoot">
<span class="sitemapLink" title="<%=intl._t("What to do in the event I2P isn't working")%>">
<img src="/themes/console/images/svg/wrench.svg">
<span class="sitemapLabel"><%=intl._t("Troubleshooting")%></span>
</span>
</a>

<a href="/help/tunnelfilter">
<span class="sitemapLink" title="<%=intl._t("Introduction to tunnel filters")%>">
<img src="/themes/console/images/svg/filter.svg">
<span class="sitemapLabel"><%=intl._t("Tunnel Filters")%></span>
</span>
</a>

<a href="/help/ui">
<span class="sitemapLink" title="<%=intl._t("Information about the sidebar and network status messages")%>">
<img src="/themes/console/images/svg/ui.svg">
<span class="sitemapLabel"><%=intl._t("User Interface")%></span>
</span>
</a>

<a href="/help/webhosting">
<span class="sitemapLink" title="<%=intl._t("A guide to reseeding your I2P router")%>">
<img src="/themes/console/images/svg/webserver.svg">
<span class="sitemapLabel"><%=intl._t("Web Hosting")%></span>
</span>
</a>

<%  } // first version %>

<%
    boolean sitemapSites = ctx.getBooleanProperty("routerconsole.sitemapSites");
    if (sitemapSites) {
%>
<h3><%=intl._t("Sites of Interest")%><a href="/confighome#configsites" style="float: right" title="Customize"><img src="/themes/console/images/svg/configure.svg" height="16" width="16" alt="Customize"></a></h3>
<jsp:useBean class="net.i2p.router.web.helpers.HomeHelper" id="homehelper" scope="request" />
<jsp:setProperty name="homehelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:getProperty name="homehelper" property="favorites" />
<%
    }
%>

</div>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>