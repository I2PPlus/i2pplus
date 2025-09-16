<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang", "en");
    String pageTitlePrefix = ctx.getProperty("routerconsole.pageTitlePrefix");
    pageTitlePrefix = (pageTitlePrefix != null) ? pageTitlePrefix + ' ' : "";
%>
<%@include file="../head.jsi"%>
<title><%=pageTitlePrefix%> <%=intl._t("Configuration Help")%> - I2P+</title>
</head>
<body>
<%@include file="../sidebar.jsi"%>
<h1 class=hlp><%=intl._t("Configuration Help")%></h1>
<div class=main id=help>
<div class=confignav>
<span class=tab2 id=cfig><span><%=intl._t("Configuration")%></span></span>
<span class=tab><a href="/help/advancedsettings" title="<%=intl._t("Advanced Settings")%>"><%=intl._t("Advanced Settings")%></a></span>
<span class=tab><a href="/help/ui" title="<%=intl._t("User Interface")%>"><%=intl._t("User Interface")%></a></span>
<span class=tab><a href="/help/reseed" title="<%=intl._t("Reseeding")%>"><%=intl._t("Reseeding")%></a></span>
<span class=tab><a href="/help/tunnelfilter" title="<%=intl._t("Tunnel Filtering")%>"><%=intl._t("Tunnel Filtering")%></a></span>
<span class=tab><a href="/help/faq" title="<%=intl._t("Frequently Asked Questions")%>"><%=intl._t("FAQ")%></a></span>
<span class=tab><a href="/help/newusers" title="<%=intl._t("New User Guide")%>"><%=intl._t("New User Guide")%></a></span>
<span class=tab><a href="/help/webhosting" title="<%=intl._t("Web Hosting")%>"><%=intl._t("Web Hosting")%></a></span>
<span class=tab><a href="/help/hostnameregistration" title="<%=intl._t("Hostname Registration")%>"><%=intl._t("Hostname Registration")%></a></span>
<span class=tab><a href="/help/troubleshoot" title="<%=intl._t("Troubleshoot")%>"><%=intl._t("Troubleshoot")%></a></span>
<span class=tab><a href="/help/glossary" title="<%=intl._t("Glossary")%>"><%=intl._t("Glossary")%></a></span>
<span class=tab><a href="/help/legal" title="<%=intl._t("Legal")%>"><%=intl._t("Legal")%></a></span>
<span class=tab><a href="/help/changelog"><%=intl._t("Change Log")%></a></span>
</div>
<div id=configurationhelp>
<h2 id=confignet><%=intl._t("Network Configuration")%></h2>
<p><%=intl._t("While I2P will work fine behind most firewalls, your speeds and network integration will generally improve if the I2P port is forwarded for both UDP and TCP.")%>&nbsp;<a href=/confignet>[<%=intl._t("Network Configuration")%>]</a>.</p>
<p><%=intl._t("If you can, please poke a hole in your firewall to allow unsolicited UDP and TCP packets to reach you.")%>&nbsp;
<%=intl._t("If you can't, I2P supports UPnP (Universal Plug and Play) and UDP hole punching with \"SSU introductions\" to relay traffic.")%>&nbsp;
<%=intl._t("Most of the options on the Network Configuration page are for special situations, for example where UPnP does not work correctly, or a firewall not under your control is doing harm.")%>&nbsp;
<%=intl._t("Certain firewalls such as symmetric NATs may not work well with I2P.")%></p>
<p><%=intl._t("UPnP is used to communicate with Internet Gateway Devices (IGDs) to detect the external IP address and forward ports.")%>&nbsp;
<%=intl._t("UPnP support is beta, and may not work for any number of reasons")%>:</p>
<ul id=upnphelp>
<li><%=intl._t("No UPnP-compatible device present")%></li>
<li><%=intl._t("UPnP disabled on the device")%></li>
<li><%=intl._t("Software firewall interference with UPnP")%></li>
<li><%=intl._t("Bugs in the device's UPnP implementation")%></li>
<li><%=intl._t("Multiple firewall/routers in the internet connection path")%></li>
<li><%=intl._t("UPnP device change, reset, or address change")%></li>
</ul>
<p><%=intl._t("UPnP may be enabled or disabled on the Network Configuration page, but a change requires a router restart to take effect.")%>&nbsp;
<a href="/info#upnpstatus">[<%=intl._t("Review the UPnP status here.")%>]</a></p>
<p><%=intl._t("Also, <b>do not enter a private IP address</b> like 127.0.0.1 or 192.168.1.1.")%>&nbsp;
<%=intl._t("If you specify the wrong IP address, or do not properly configure your NAT or firewall, your network performance will degrade substantially.")%>&nbsp;
<%=intl._t("When in doubt, leave the settings at the defaults.")%>&nbsp;</p>
</div>
<div id=browserconfig>
<h2 id=confignet><%=intl._t("Web Browser Configuration")%></h2>
<p><%=intl._t("In order to access websites hosted on I2P, and optionally use the default outproxy to connect to websites on clearnet, you will need to configure your browser to use the I2P HTTP proxy, by default running on <code>127.0.0.1 port 4444</code>. Most browsers offer an option to configure your proxy; in this mini-tutorial we'll focus on Firefox.")%></p>
<ol>
<li><%=intl._t("Open the Preferences page in Firefox and scroll to the bottom of the General section.")%></li>
<li><%=intl._t("In the Network Settings sub-section, hit the <i>Settings</i> button.")%>
<li><%=intl._t("Under <i>Configure Proxy Access to Internet</i> select the <i>Manual proxy configuration</i> option.")%>
<li><%=intl._t("Enter <code>127.0.0.1</code> in the <i>HTTP Proxy</i> field, and <code>4444</code> in the <i>Port</i> field.")%></li>
<li><%=intl._t("Tick the box marked <i>Use this proxy server for all protocols</i> to avoid requests escaping the proxy.")%>
<li><%=intl._t("In the box marked <i>No Proxy for</i> add the line <code>127.0.0.1,localhost</code> to ensure the I2P console and other locally hosted websites are still available.")%>
<li><%=intl._t("Tick the box marked <i>Proxy DNS when using SOCKS v5</i> to prevent DNS leaks.")%>
<li><%=intl._t("Hit the <i>OK</i> button and test the proxy is working by visiting <a href=http://planeta.i2p/ target=_blank rel=noreferrer class=sitelink>planeta.i2p</a> and <a href=https://www.myip.com/ target=_blank rel=noreferrer class='sitelink external'>www.myip.com</a>.")%>
</ol>
<p><%=intl._t("Note: To enhance your security when browsing .i2p sites, you may wish to consider installing an addon to prevent Javascript from running in the browser such as <a href=https://addons.mozilla.org/en-US/firefox/addon/noscript/ target=_blank rel=noreferrer class='sitelink external'>NoScript</a>, though it's recommended to whitelist the address I2P+ is running on to enable various router console enhancements. In addition, an addon that prevents requests to offsite resources is also recommended, for example <a href=https://addons.mozilla.org/en-US/firefox/addon/umatrix/ target=_blank rel=noreferrer class='sitelink external'>uMatrix</a>.")%></p>
<p><%=intl._t("For a more extensive guide to configuration for various browsers, see the <a href=https://geti2p.net/en/about/browser-config target=_blank rel=noreferrer class='sitelink external'>browser configuration guide</a>.")%></p>
</div>
<div id=tunnman>
<h2 id=tunnelmanager><%=intl._t("The Tunnel Manager")%></h2>
<p><%=intl._t("The Tunnel Manager enables configuration of local services provided by your router.")%>&nbsp;
<%=intl._t("You can determine which services automatically start when the router starts, manage tunnel allocation to optimize for high usage services or to minimize the the cpu and memory overhead, add server and client tunnels to host or access services on the network, set rate limiting or tunnel filtering for your server tunnels and more.")%></p>
<p><%=intl._t("By default, most of your client services (mail, http proxy, IRC) will share the same set of tunnels (for performance reasons) and be listed as <i>Shared Clients</i> and <i>Shared Clients(DSA)</i> in the sidebar under Service Tunnels and on the <a href=/tunnels>Tunnels page</a>.")%>&nbsp;<%=intl._t("However, if you experience a tunnel failure, all your services will go offline at the same time, so in some scenarios you may wish to configure client services to use their own set of tunnels.")%>&nbsp;
<%=intl._t("This can be done by unchecking the <i>Share tunnels with other configured clients</i> option listed under <i>Shared Client</i> on the configuration page of the relevant client service, after which you will need to restart the client service from the <a href=/i2ptunnelmgr>Tunnel Manager index page</a>.")%></p>
<p><%=intl._t("Note: Most of the options in the Tunnel Manager have tooltips; be sure to check those before you change settings you don't fully understand!")%></p>
</div>
</div>
</body>
</html>