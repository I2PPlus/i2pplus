<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="../css.jsi" %>
<title><%=intl._t("User Interface Help")%> - I2P+</title>
</head>
<body>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("User Interface Help")%></h1>
<div class="main" id="help">
<div class="confignav">
<span class="tab"><a href="/help/configuration"><%=intl._t("Configuration")%></a></span>
<span class="tab"><a href="/help/advancedsettings"><%=intl._t("Advanced Settings")%></a></span>
<span class="tab2"><%=intl._t("User Interface")%></span>
<span class="tab"><a href="/help/reseed"><%=intl._t("Reseeding")%></a></span>
<span class="tab"><a href="/help/tunnelfilter"><%=intl._t("Tunnel Filtering")%></a></span>
<span class="tab"><a href="/help/faq"><%=intl._t("FAQ")%></a></span>
<span class="tab"><a href="/help/newusers"><%=intl._t("New User Guide")%></a></span>
<span class="tab"><a href="/help/webhosting"><%=intl._t("Web Hosting")%></a></span>
<span class="tab"><a href="/help/hostnameregistration"><%=intl._t("Hostname Registration")%></a></span>
<span class="tab"><a href="/help/troubleshoot"><%=intl._t("Troubleshoot")%></a></span>
<span class="tab"><a href="/help/glossary"><%=intl._t("Glossary")%></a></span>
<span class="tab"><a href="/help/legal"><%=intl._t("Legal")%></a></span>
<span class="tab"><a href="/help/changelog"><%=intl._t("Change Log")%></a></span>
</div>
<p class="infohelp" id="tooltips"><%=intl._t("Note: Many of the labels and options in the console have tooltips that indicate purpose or explain the meaning. Hover over the label or option to activate the tooltip.")%></p>
<div id="themeoverride">
<h2><%=intl._t("Theme Overrides")%> (I2P+)</h2>
<p><%=intl._t("I2P+ supports custom theme overrides for the console, the tunnel manager, I2PSnark, SusiDNS and Susimail, permitting changes to the themes which are persistent after upgrades. In order to activate an override, create a css file named <code>override.css</code> with your theme modifications and place it in the relevant theme directory of the theme you wish to change, or rename one of the example files, and then hard refresh the browser. Note that the tunnel manager and the console share the same override.css. To deactivate an override, delete or rename the <code>override.css</code> file and then hard refresh the browser (usually Control + Shift + R).")%></p>
<p><%=intl._t("The themes directory is located in the I2P application directory under <code>docs/themes</code>. There are example override files in the console and I2PSnark theme sub-directories.")%></p>
</div>
<div id="sidebarhelp">
<h2><%=intl._t("Sidebar Information")%></h2>
<p><%=intl._t("Many of the stats on the sidebar may be <a href='/configstats'>configured</a> to be <a href='/graphs'>graphed</a> for further analysis. You may also customize the sections that appear on the Sidebar and their positioning on the <a href='/configsidebar'>Sidebar Configuration page</a>.")%></p>
<h3><%=intl._t("Router Info")%></h3>
<ul>
<li><b><%=intl._t("Local Identity")%>:</b> <%=intl._t("If you hover over the <i>Router Info</i> heading, your truncated router identity will be shown (the first four characters (24 bits) of your 44-character (256-bit) Base64 router hash). The full hash is shown on your <a href='netdb?r=.'>Network Database entry</a>. Never reveal this to anyone, as your router identity is uniquely linked to your IP address in the network database.")%></li>
<li><b><%=intl._t("Version")%>:</b> <%=intl._t("The version of the I2P software you are running. If a new version is available, you will be notified in the Sidebar. It is recommended to keep your router up to date to ensure optimal performance and security. Router updates are usually made available on average every 2-3 months.")%></li>
<li><b><%=intl._t("Clock Skew")%>:</b> <%=intl._t("The skew (offset) of your computer's clock relative to the network-synced time (if known). I2P requires your computer's time be accurate. If the skew is more than a few seconds, please correct the problem by adjusting your computer's time. If I2P cannot connect to the internet, a reading of 0ms may be indicated. Note: This is only displayed in the <i>Router Information (advanced)</i> section. You may add this section to your Sidebar on the <a href='/configsidebar'>Sidebar Configuration page</a>.")%></li>
<li><b><%=intl._t("Memory")%>:</b> <%=intl._t("This indicates the amount of RAM I2P is using, and the total amount available, allocated by Java. If the usage is consistently high relative to the available RAM, this may indicate that you need to increase the ram allocated to the JVM. You can allocate more RAM by editing your <code>wrapper.config</code> file which is normally located in the I2P application directory. You will need to edit the <code>wrapper.java.maxmemory</code> parameter, which by default is set to 256(MB) for I2P, or 384 for I2P+. Note: if the memory bar is enabled, the memory entry in the router info section will be suppressed.")%></li>
</ul>
<h3><%=intl._t("Peers")%></h3>
<ul>
<li><b><%=intl._t("Active")%>:</b> The first number is the number of peers your router has sent or received a message from in the last few minutes. This may range from 8-10 to several hundred, depending on your total bandwidth, shared bandwidth, and locally-generated traffic. The second number is the number of peers seen in the last hour or so. Do not be concerned if these numbers vary widely. <a href='/configstats#router.activePeers'>[Enable graphing]</a>.")%></li>
<li><b><%=intl._t("Fast")%>:</b> This is the number of peers your router has available for building client tunnels. It is generally in the range 8-30. Your fast peers are shown on the <a href='/profiles'>Profiles page</a>. <a href='/configstats#router.fastPeers'>[Enable graphing]</a>")%></li>
<li><b><%=intl._t("High Capacity")%>:</b> <%=intl._t("This is the number of peers your router has available for building your exploratory tunnels which are used to determine network performance. It is generally in the range 8-75. The fast peers are included in the high capacity tier.Your high capacity peers are shown on the <a href='/profiles'>Profiles page</a>. <a href='/configstats#router.highCapacityPeers'>[Enable graphing]</a>")%></li>
<li><b><%=intl._t("Integrated")%>:</b> This is the number of peers your router will use for network database inquiries. These are usually the "floodfill" routers which are responsible for maintaining network integrity. Your well integrated peers are shown on the bottom of the <a href='/profiles'>Profiles page</a>.")%></li>
<li><b><%=intl._t("Known")%>:</b> This is the total number of peers that are known by your router. They are listed on the <a href='/netdb'>Network Database page</a>. This may range from under 100 to 1000 or more. This number is not the total size of the network; it may vary widely depending on your total bandwidth, shared bandwidth, and locally-generated traffic. I2P does not require a router to know every other router in the network.")%></li>
</ul>
<h3><%=intl._t("Bandwidth In/Out")%></h3>
<p><%=intl._t("This section indicates your average bandwidth speeds and total usage for the session. All values are in bytes per second. You may change your bandwidth limits on the <a href='/config'>Bandwidth Configuration page</a>. The more bandwidth you make available, the more you help the network and improve your own anonymity, so please take the time to review the settings. If you are unsure of your network's speed, using a service such as <a href='http://speedtest.net/'>SpeedTest</a> or similar will give you a good indication of your bandwidth capability. Your upstream share amount (KBps Out) will determine your overall contribution to the network. Bandwidth is <a href='/graphs'>graphed</a> by default.")%></p>
<h3><%=intl._t("Local Destinations")%></h3>
<p><%=intl._t("These are the local services provided by your router. They may be clients started through the <a href='/i2ptunnelmgr'>Tunnel Manager</a> or external programs connecting through SAM, BOB, or directly to I2CP. By default, most of your client services (mail, http proxy, IRC) will share the same set of tunnels and be listed as <i>Shared Clients</i> and <i>Shared Clients(DSA)</i>, which enhances anonymity by blending the traffic from all shared services, and reduces the overhead required to build and maintain tunnels. However, if you experience a tunnel failure, all your services will go offline at the same time, so in some scenarios you may wish to configure client services to use their own set of tunnels. This can be done by unchecking the <i>Share tunnels with other clients&hellip;</i> option listed under <i>Shared Clients</i> on the configuration page of the relevant client service in the Tunnel Manager, after which you will need to restart the client service from the <a href='/i2ptunnelmgr'>main Tunnel Manager page</a>.")%></p>
<h3><%=intl._t("Tunnels")%></h3>
<p><%=intl._t("The actual tunnels are shown on the <a href='/tunnels'>Tunnels page</a>.")%></p>
<ul>
<li><b><%=intl._t("Exploratory")%>:</b> Tunnels built by your router and used for communication with the floodfill peers, building new tunnels, and testing existing tunnels.")%></li>
<li><b><%=intl._t("Client")%>:</b> Tunnels built by your router for each client's use.")%></li>
<li><b><%=intl._t("Participating")%>:</b> Tunnels built by other routers through your router. This may vary widely depending on network demand, your shared bandwidth, and amount of locally-generated traffic. The recommended method for limiting participating tunnels is to change your share percentage on the <a  href='/config'>Bandwidth Configuration page</a>. You may also limit the total number by setting <code>router.maxParticipatingTunnels=nnn</code> on the <a href='/configadvanced'>Advanced configuration page</a>. <a href='/configstats#tunnel.participatingTunnels'>[Enable graphing]</a>.")%></li>
<li><b><%=intl._t("Share Ratio")%>:</b> The number of participating tunnels you route for others, divided by the total number of hops in all your exploratory and client tunnels. A number greater than 1.00 means you are contributing more tunnels to the network than you are using.")%></li>
</ul>
<h3><%=intl._t("Congestion")%></h3>
<p><%=intl._t("Some basic indications of router overload:")%></p>
<ul>
<li><b><%=intl._t("Job Lag")%>:</b> How long jobs are waiting before execution. The job queue is listed on the <a href='/jobs'>Jobs page</a>. Unfortunately, there are several other job queues in the router that may be congested, and their status is not available in the router console. The job lag should generally be zero. If it is consistently higher than 500ms, your computer is very slow, your network is experiencing connectivity issues, or the router has serious problems. <a href='/configstats#jobQueue.jobLag'>[Enable graphing]</a>.")%></li>
<li><b><%=intl._t("Message Delay")%>:</b> How long an outbound message waits in the queue. This should generally be a few hundred milliseconds or less. If it is consistently higher than 1000ms, your computer is very slow, or you should adjust your bandwidth limits, or your (BitTorrent?) clients may be sending too much data and should have their transmit bandwidth limit reduced. <a href='/configstats#transport.sendProcessingTime'>[Enable graphing]</a> (transport.sendProcessingTime).")%></li>
<li><b><%=intl._t("Backlog")%>:</b> This is the number of pending requests from other routers to build a participating tunnel through your router. It should usually be close to zero. If it is consistently high, your computer is too slow, and you should reduce your share bandwidth limits.")%></li>
<li><b><%=intl._t("Accepting/Rejecting")%>:</b> Your router's status on accepting or rejecting requests from other routers to build a participating tunnel through your router. Your router may accept all requests, accept or reject a percentage of requests, or reject all requests for a number of reasons, to control the bandwidth and CPU demands and maintain capacity for local clients. <b>Note")%>:</b> It will take at least 10 minutes from your router starting for it to accept building participating tunnels in order to ensure your router is stable and successfully bootstrapped to the network.")%></li>
</ul>
<p><%=intl._t("<b>Note")%>:</b> This section is not enabled by default unless <a href='/help/configuration#advancedconsole'>Advanced Console mode</a> is enabled. You may enable it on the <a href='/configsidebar'>Sidebar Configuration page</a>.")%></p>
</div>
<div id="reachabilityhelp">
<h2><%=intl._t("Reachability Help")%></h2>
<p><%=intl._t("While I2P will work fine behind most firewalls, your speeds and network integration will generally improve if the I2P port is forwarded for both UDP and TCP.")%>&nbsp;
<%=intl._t("If you think you have opened up your firewall and I2P still thinks you are firewalled, remember that you may have multiple firewalls, for example both software packages and external hardware routers.")%>&nbsp;
<%=intl._t("If there is an error, the <a href=\"/logs\">logs</a> may also help diagnose the problem.")%></p>
<ul id="reachability">
<li><b><%=intl._t("OK")%>:</b>
<%=intl._t("Your UDP port does not appear to be firewalled.")%></li>
<li><b><%=intl._t("Firewalled")%>:</b>
<%=intl._t("Your UDP port appears to be firewalled.")%>&nbsp;
<%=intl._t("As the firewall detection methods are not 100% reliable, this may occasionally be displayed in error.")%>&nbsp;
<%=intl._t("However, if it appears consistently, you should check whether both your external and internal firewalls are open for your port.")%>&nbsp;
<%=intl._t("I2P will work fine when firewalled, there is no reason for concern. When firewalled, the router uses \"introducers\" to relay inbound connections.")%>&nbsp;
<%=intl._t("However, you will get more participating traffic and help the network if you open your firewall.")%>&nbsp;
<%=intl._t("If you think you have already done so, remember that you may have both a hardware and a software firewall, or be behind an additional, institutional firewall you cannot control.")%>&nbsp;
<%=intl._t("Also, some routers cannot correctly forward both TCP and UDP on a single port, or may have other limitations or bugs that prevent them from passing traffic through to I2P.")%></li>
<li><b><%=intl._t("Testing")%>:</b>
<%=intl._t("The router is currently testing whether your UDP port is firewalled.")%></li>
<li><b><%=intl._t("Hidden")%>:</b>
<%=intl._t("The router is not configured to publish its address, therefore it does not expect incoming connections.")%>&nbsp;
<%=intl._t("Hidden mode is automatically enabled for added protection in certain countries.")%></li>
<li><b><%=intl._t("WARN - Firewalled and Fast")%>:</b>
<%=intl._t("You have configured I2P to share more than 128KBps of bandwidth, but you are firewalled.")%>&nbsp;
<%=intl._t("While I2P will work fine in this configuration, if you really have over 128KBps of bandwidth to share, it will be much more helpful to the network if you open your firewall.")%></li>
<li><b><%=intl._t("WARN - Firewalled and Floodfill")%>:</b>
<%=intl._t("You have configured I2P to be a floodfill router, but you are firewalled.")%>&nbsp;
<%=intl._t("For best participation as a floodfill router, you should open your firewall.")%></li>
<li><b><%=intl._t("WARN - Firewalled with Inbound TCP Enabled")%>:</b>
<%=intl._t("You have configured inbound TCP, however your UDP port is firewalled, and therefore it is likely that your TCP port is firewalled as well.")%>
<%=intl._t("If your TCP port is firewalled with inbound TCP enabled, routers will not be able to contact you via TCP, which will hurt the network.")%>
<%=intl._t("Please open your firewall or disable inbound TCP above.")%></li>
<li><b><%=intl._t("WARN - Firewalled with UDP Disabled")%>:</b>
<%=intl._t("You have configured inbound TCP, however you have disabled UDP.")%>&nbsp;
<%=intl._t("You appear to be firewalled on TCP, therefore your router cannot accept inbound connections.")%>&nbsp;
<%=intl._t("Please open your firewall or enable UDP.")%></li>
<li><b><%=intl._t("ERR - Clock Skew")%>:</b>
<%=intl._t("Your system's clock is skewed, which will make it difficult to participate in the network.")%>&nbsp;
<%=intl._t("Correct your clock setting if this error persists.")%></li>
<li><b><%=intl._t("ERR - Private TCP Address")%>:</b>
<%=intl._t("You must never advertise an unroutable IP address such as 127.0.0.1 or 192.168.1.1 as your external address.")%>
<%=intl._t("Correct the address or disable inbound TCP on the Network Configuration page.")%></li>
<li><b><%=intl._t("ERR - SymmetricNAT")%>:</b>
<%=intl._t("I2P detected that you are firewalled by a Symmetric NAT.")%>
<%=intl._t("I2P does not work well behind this type of firewall. You will probably not be able to accept inbound connections, which will limit your participation in the network.")%></li>
<li><b><%=intl._t("ERR - UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart")%>:</b>
<%=intl._t("I2P was unable to bind to the configured port noted on the advanced network configuration page .")%>&nbsp;
<%=intl._t("Check to see if another program is using the configured port. If so, stop that program or configure I2P to use a different port.")%>&nbsp;
<%=intl._t("This may be a transient error, if the other program is no longer using the port.")%>&nbsp;
<%=intl._t("However, a restart is always required after this error.")%></li>
<li><b><%=intl._t("ERR - UDP Disabled and Inbound TCP host/port not set")%>:</b>
<%=intl._t("You have not configured inbound TCP with a hostname and port on the Network Configuration page, however you have disabled UDP.")%>&nbsp;
<%=intl._t("Therefore your router cannot accept inbound connections.")%>&nbsp;
<%=intl._t("Please configure a TCP host and port on the Network Configuration page or enable UDP.")%></li>
<li><b><%=intl._t("ERR - Client Manager I2CP Error - check logs")%>:</b>
<%=intl._t("This is usually due to a port 7654 conflict. Check the logs to verify.")%>&nbsp;
<%=intl._t("Do you have another I2P instance running? Stop the conflicting program and restart I2P.")%></li>
</ul>

<h3><%=intl._t("Clock Skew")%></h3>
<p><%=intl._t("The skew (offset) of your computer's clock relative to the network-synced time.")%>&nbsp;
<%=intl._t("I2P requires your computer's time be accurate.")%>&nbsp;
<%=intl._t("If the skew is more than a few seconds, please correct the problem by adjusting your computer's time.")%>&nbsp;
<%=intl._t("If I2P cannot connect to the internet, a reading of 0ms may be indicated.")%></p>
</div>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
<%@include file="../summaryajax.jsi" %>
</body>
</html>