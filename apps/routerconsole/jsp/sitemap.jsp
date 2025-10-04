<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="64kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
    String pageTitlePrefix = ctx.getProperty("routerconsole.pageTitlePrefix");
    pageTitlePrefix = (pageTitlePrefix != null) ? pageTitlePrefix + ' ' : "";
    String version = net.i2p.CoreVersion.VERSION;
    String firstVersion = ctx.getProperty("router.firstVersion");
    boolean embedApps = ctx.getBooleanProperty("routerconsole.embedApps");
%>
<title><%=pageTitlePrefix%> Sitemap - I2P+</title>
</head>
<body class="<%=lang%>" id="map">
<%@include file="sidebar.jsi"%>
<h1 class="home"><%=intl._t("Router Sitemap")%></h1>
<div class="main" id="sitemap">
<div id="searchbar">
<jsp:useBean class="net.i2p.router.web.helpers.SearchHelper" id="searchhelper" scope="request"/>
<jsp:setProperty name="searchhelper" property="contextId" value="<%=i2pcontextId%>"/>
<form action="/search.jsp" target="_blank" rel="noreferrer" method="POST">
<table>
<tr>
<td>
<div>
<jsp:getProperty name="searchhelper" property="selector"/>
<input size=40 type=text class=search name=query required placeholder="<%=intl._t("Please enter a search query")%>" />
<button type="submit" value="search" class="search"><%=intl._t("Search")%></button>
</div>
</td>
</tr>
</table>
</form>
</div>
<div id=sitemapcontainer>
<h3><%=intl._t("Services")%></h3>
<%
  class LinkEntry {
    String href, titleKey, imgSrc, labelKey;
    boolean extLink;
    LinkEntry(String h, String t, String i, String l, boolean e) { href=h; titleKey=t; imgSrc=i; labelKey=l; extLink=e; }
  }
  LinkEntry[] serviceLinks = new LinkEntry[]{
    new LinkEntry("/dns", "Manage I2P addresses", "/themes/console/images/addressbook.svg", "Addressbook", false),
    new LinkEntry("/imagegen", "Identification Image Generator", "/themes/console/images/imagegen.png", "Imagegen", true),
    new LinkEntry("/torrents", "Create and download torrents", "/themes/console/images/snark.svg", "Torrents", !embedApps),
    new LinkEntry("/tunnelmanager", "Manage client and server tunnels", "/themes/console/images/tunnelmanager.svg", "Tunnel Manager", false),
    new LinkEntry("/webmail", "Webmail client", "/themes/console/images/mail.svg", "Webmail", !embedApps),
    new LinkEntry("http://127.0.0.1:7658/", "Local webserver for hosting content on the I2P network", "/themes/console/images/webserver.svg", "Web Server", true)
  };
  for (LinkEntry sitemapLink : serviceLinks) {
%>
<a href="<%=sitemapLink.href%>" <%=(sitemapLink.extLink ? "target=_blank rel=noreferrer" : "")%>>
<span class="sitemapLink <%=(sitemapLink.extLink ? "extlink" : "")%>" title="<%=intl._t(sitemapLink.titleKey)%>">
<img src="<%=sitemapLink.imgSrc%>">
<span class="sitemapLabel"><%=intl._t(sitemapLink.labelKey)%></span>
</span>
</a>
<% } %>
<h3><%=intl._t("Information &amp; Diagnostics")%></h3>
<%
  LinkEntry[] infoLinks = new LinkEntry[]{
    new LinkEntry("/certs", "Review active encryption certificates used in console", "/themes/console/images/certs.svg", "Certificates", false),
    new LinkEntry("/changelog", "Review recent updates to I2P", "/themes/console/images/changelog.svg", "Change Log", false),
    new LinkEntry("/debug", "View debugging information", "/themes/console/images/bug.svg", "Debugging", false),
    new LinkEntry("/events?from=604800", "View historical log of router events", "/themes/console/images/logs.svg", "Event Log", false),
    new LinkEntry("/graphs", "View router performance graphs", "/themes/console/images/graphs.svg", "Graphs", false),
    new LinkEntry("/help/", "Router help section", "/themes/console/images/help.svg", "Help Section", false),
    new LinkEntry("/home", "Router homepage", "/themes/console/images/home.svg", "Home", false),
    new LinkEntry("/news", "View news relating to I2P", "/themes/console/images/news.svg", "I2P News", false),
    new LinkEntry("/jars", "Review extended info about installed .jar and .war files", "/themes/console/images/package.svg", "Jars", false),
    new LinkEntry("/jobs", "View router workload and stats", "/themes/console/images/jobs.svg", "Router Jobs", false),
    new LinkEntry("/routerlogs", "View router logs", "/themes/console/images/logs.svg", "Router Logs", false),
    new LinkEntry("/netdb", "View information and stats regarding all known peers", "/themes/console/images/globe.svg", "NetDb", false),
    new LinkEntry("/netdb?f=4", "Network database search tool", "/themes/console/images/searchnetdb.svg", "NetDb Search", false),
    new LinkEntry("/peers", "View active peers", "/themes/console/images/peers.svg", "Peers", false),
    new LinkEntry("/profiles?f=1", "View peer performance profiles", "/themes/console/images/profile.svg", "Profiles", false),
    new LinkEntry("/info", "View information about router and running environment", "/themes/console/images/info.svg", "Router Info", false),
    new LinkEntry("/stats", "Textual router performance statistics", "/themes/console/images/textstats.svg", "Statistics", false),
    new LinkEntry("/netdb?f=3", "Review possible sybils in network database", "/themes/console/images/sybil.svg", "Sybil Analysis", false),
    new LinkEntry("/debug?d=6", "Current console and webapp translation report", "/themes/console/images/translation.svg", "Translation Status", false),
    new LinkEntry("/tunnels", "View active tunnels", "/themes/console/images/hardhat.svg", "Tunnels", false)
  };
  for (LinkEntry sitemapLink : infoLinks) {
%>
<a href="<%=sitemapLink.href%>">
<span class="sitemapLink" title="<%=intl._t(sitemapLink.titleKey)%>">
<img src="<%=sitemapLink.imgSrc%>">
<span class="sitemapLabel"><%=intl._t(sitemapLink.labelKey)%></span>
</span>
</a>
<% } %>
<h3><%=intl._t("Configuration")%></h3>
<%
  LinkEntry[] configLinks = new LinkEntry[]{
    new LinkEntry("/configadvanced", "Advanced Router Configuration", "/themes/console/images/configure.svg", "Advanced", false),
    new LinkEntry("/config", "Configure router bandwidth allocation", "/themes/console/images/speedometer.svg", "Bandwidth", false),
    new LinkEntry("/configclients", "Start or stop Router clients", "/themes/console/images/editclient.svg", "Clients", false),
    new LinkEntry("/confighome", "Customize homepage", "/themes/console/images/home.svg", "Homepage", false),
    new LinkEntry("/configi2cp", "Configure I2P Client Control Interface", "/themes/console/images/i2cp.svg", "I2CP", false),
    new LinkEntry("/configkeyring", "View or edit router keyring for encrypted destinations", "/themes/console/images/keys.svg", "Keyring", false),
    new LinkEntry("/configlogging", "Configure router logging options", "/themes/console/images/logs.svg", "Logging", false),
    new LinkEntry("/confignet", "Configure router networking options", "/themes/console/images/networkconfig.svg", "Network", false),
    new LinkEntry("/configpeer", "Manually edit peers and view banned peers and blocklist", "/themes/console/images/peerconfig.svg", "Peers", false),
    new LinkEntry("/configplugins", "Install and manage router plugins", "/themes/console/images/pluginconfig.svg", "Plugins", false),
    new LinkEntry("/configreseed", "Router reseeding and configuration", "/themes/console/images/reseed.svg", "Reseeding", false),
    new LinkEntry("/configfamily", "Configure and manage Router family", "/themes/console/images/family.svg", "Router Family", false),
    new LinkEntry("/configservice", "Manage Router service", "/themes/console/images/service.svg", "Router Service", false),
    new LinkEntry("/configstats", "Configure stat collection for graphing", "/themes/console/images/graphs.svg", "Router Stats", false),
    new LinkEntry("/configupdate", "Configure router updates", "/themes/console/images/update.svg", "Router Updates", false),
    new LinkEntry("/wizard", "Configuration wizard for new users", "/themes/console/images/wizard.svg", "Setup Wizard", false),
    new LinkEntry("/configsidebar", "Customize sidebar", "/themes/console/images/sidebar.svg", "Sidebar", false),
    new LinkEntry("/configtunnels", "Configure tunnel options", "/themes/console/images/hardhat.svg", "Tunnels", false),
    new LinkEntry("/configui", "Configure router user interface and optional console password", "/themes/console/images/ui.svg", "User Interface", false),
    new LinkEntry("/configwebapps", "Manage Router webapps", "/themes/console/images/webapps.svg", "Webapps", false)
  };
  for (LinkEntry sitemapLink : configLinks) {
%>
<a href="<%=sitemapLink.href%>">
<span class=sitemapLink title="<%=intl._t(sitemapLink.titleKey)%>">
<img src="<%=sitemapLink.imgSrc%>">
<span class=sitemapLabel><%=intl._t(sitemapLink.labelKey)%></span>
</span>
</a>
<% } %>
<% if (version.equals(firstVersion)) { %>
<h3><%=intl._t("Help &amp; Support")%></h3>
<%
  LinkEntry[] helpLinks = new LinkEntry[]{
    new LinkEntry("/help/advancedsettings", "Advanced configuration settings", "/themes/console/images/configure.svg", "Advanced Settings", false),
    new LinkEntry("/help/configuration", "Configuring I2P for optimal performance", "/themes/console/images/wrench.svg", "Configuration", false),
    new LinkEntry("/help/faq", "Frequently Asked Questions", "/themes/console/images/faq.svg", "FAQ", false),
    new LinkEntry("/help/glossary", "I2P Terminology", "/themes/console/images/faq.svg", "Glossary", false),
    new LinkEntry("/help/legal", "Licensing and copyright notices", "/themes/console/images/legal.svg", "Legal", false),
    new LinkEntry("/help/newusers", "Information for new users", "/themes/console/images/help.svg", "New Users", false),
    new LinkEntry("/help/reseed", "A guide to reseeding your I2P router", "/themes/console/images/reseed.svg", "Reseeding", false),
    new LinkEntry("/help/troubleshoot", "What to do in the event I2P isn't working", "/themes/console/images/wrench.svg", "Troubleshooting", false),
    new LinkEntry("/help/tunnelfilter", "Introduction to tunnel filters", "/themes/console/images/filter.svg", "Tunnel Filters", false),
    new LinkEntry("/help/ui", "Information about the sidebar and network status messages", "/themes/console/images/ui.svg", "User Interface", false),
    new LinkEntry("/help/webhosting", "A guide to reseeding your I2P router", "/themes/console/images/webserver.svg", "Web Hosting", false)
  };
  for (LinkEntry sitemapLink : helpLinks) {
%>
<a href="<%=sitemapLink.href%>">
<span class=sitemapLink title="<%=intl._t(sitemapLink.titleKey)%>">
<img src="<%=sitemapLink.imgSrc%>">
<span class=sitemapLabel><%=intl._t(sitemapLink.labelKey)%></span>
</span>
</a>
<% } %>
<% } %>
<%
  boolean sitemapSites = ctx.getBooleanProperty("routerconsole.sitemapSites");
  if (sitemapSites) {
%>
<h3>
<%=intl._t("Sites of Interest")%>
<a href=/confighome#configsites style=float:right title="<%=intl._t("Customize")%>">
<img src=/themes/console/images/configure.svg height=16 width=16 alt="<%=intl._t("Customize")%>"/>
</a>
</h3>
<jsp:useBean class="net.i2p.router.web.helpers.HomeHelper" id="homehelper" scope="request"/>
<jsp:setProperty name="homehelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:getProperty name="homehelper" property="favorites"/>
<% } %>
</div>
</div>
</body>
</html>