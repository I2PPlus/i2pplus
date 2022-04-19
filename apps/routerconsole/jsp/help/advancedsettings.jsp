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
<title><%=intl._t("Advanced Configuration Help - I2P+")%></title>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("Advanced Configuration Help")%></h1>
<div class="main" id="help">
<div class="confignav">
<span class="tab"><a href="/help/configuration"><%=intl._t("Configuration")%></a></span>
<span class="tab2"><%=intl._t("Advanced Settings")%></span>
<span class="tab"><a href="/help/ui"><%=intl._t("User Interface")%></a></span>
<span class="tab"><a href="/help/reseed"><%=intl._t("Reseeding")%></a></span>
<span class="tab"><a href="/help/tunnelfilter"><%=intl._t("Tunnel Filtering")%></a></span>
<span class="tab"><a href="/help/webhosting"><%=intl._t("Web Hosting")%></a></span>
<span class="tab"><a href="/help/faq"><%=intl._t("FAQ")%></a></span>
<span class="tab"><a href="/help/newusers"><%=intl._t("New User Guide")%></a></span>
<span class="tab"><a href="/help/webhosting"><%=intl._t("Web Hosting")%></a></span>
<span class="tab"><a href="/help/hostnameregistration"><%=intl._t("Hostname Registration")%></a></span>
<span class="tab"><a href="/help/troubleshoot"><%=intl._t("Troubleshoot")%></a></span>
<span class="tab"><a href="/help/glossary"><%=intl._t("Glossary")%></a></span>
<span class="tab"><a href="/help/legal"><%=intl._t("Legal")%></a></span>
<span class="tab"><a href="/help/changelog"><%=intl._t("Change Log")%></a></span>
</div>

<div id="advancedsettings">
<h2><%=intl._t("Advanced Router Configuration")%></h2>

<p class="infohelp"><%=intl._t("The router configuration options listed below are not available in the user interface, usually because they are rarely used or provide access to advanced settings that most users will not need. This is not a comprehensive list. Some settings will require a restart of the router to take effect. Note that all settings are case sensitive. You will need to edit your <code>router.config</code> file to add options, or, once you have added <code>routerconsole.advanced=true</code> to the router.config file, you may edit settings within the console on the <a href=/configadvanced>Advanced Configuration page</a>.")%></p>

<table id="configinfo">

<tr><th id="advancedconsole">routerconsole.advanced={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, additional functionality will be enabled in the console and the user will be able to edit settings directly on the <a href=/configadvanced>Advanced Configuration page</a>. Extra display options are provided in the <a href=/netdb>Network Database section</a>, including the <a href=/netdb?f=3>Sybil Analysis tool</a>, and there are additional configuration options on the <a href=/configclients>Clients Configuration page</a>. This will also enable the installation of unsigned updates, manual configuration of the news URL, and additional sections on the sidebar.")%></td></tr>

<tr><th>routerconsole.allowUntrustedPlugins={true|false}</th></tr>
<tr><td><%=intl._t("Plugins signed with the cryptographic key of the developer are the recommended format, but if you wish to install unsigned plugins (.zip) you can set this to true. Note that you may still encounter issues attempting to install an unsigned plugin if the developer has included additional checks in the plugin build process.")%></td></tr>

<tr><th>routerconsole.browser={/path/to/browser}</th></tr>
<tr><td><%=intl._t("This setting allows the manual selection of the browser which I2P will launch on startup (if the console is <a href=/configservice#browseronstart>configured</a> to launch a browser on startup), overriding the OS default browser. For more information, see the <a href=#alternative_browser>FAQ entry</a> above.")%></td></tr>

<tr><th>routerconsole.enableCompression={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, this enables gzip compression for the router console and default web applications. [Enabled by default]")%></td></tr>

<tr><th>routerconsole.enablePluginInstall={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, this enables plugin installation on the <a href=/configplugins>Plugin Configuration page</a>. [Enabled by default]")%></td></tr>

<tr><th>routerconsole.redirectToHTTPS={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, accessing the router console via http:// when https:// is enabled will automatically redirect to https. To configure router console access, edit the settings in your <code>clients.config</code> file in your profile directory (<i>not</i> the application installation directory). [Enabled by default]")%></td></tr>

<tr><th>router.buildHandlerThreads={n}</th></tr>
<tr><td><%=intl._t("Allocate number of processor threads for building tunnels. If your processor supports hyperthreading or simultaneous multithreading, you may multiply the number of processor cores by 2 to get the maximum number of threads to allocate, otherwise number of processor cores = maximum number of threads available. Note that you may wish to allocate less than the theoretical maximum to ensure you have headroom for other tasks.")%></td></tr>

<tr><th>router.disableTunnelTesting={true|false}</th></tr>
<tr><td><%=intl._t("Periodically test tunnels to determine the average lag, and display the results in the Congestion sidepanel section. To enable, set this value to false. [Default is false]")%></td></tr>

<tr><th>router.dynamicKeys={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, the router will change its identity and UDP port every time the router restarts. [Default is false]")%></td></tr>

<tr><th>router.excludePeerCaps={netDBcaps}</th></tr>
<tr><td><%=intl._t("This setting determines which <a href=/profiles#profile_defs>peer capabilities</a> will not be used to build your router's tunnels. e.g. <code>router.excludePeerCaps=LMN</code>")%></td></tr>

<tr><th>router.hideFloodfillParticipant={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, if your router is serving as a floodfill for the network, your <a href=/configadvanced#ffconf>floodfill participation</a> will be hidden from other routers.")%></td></tr>

<tr><th>router.maxJobRunners={n}</th></tr>
<tr><td><%=intl._t("Defines the maximum number of parallel <a href=/jobs>jobs</a> that can be run. The default value is determined by the amount of memory allocated to the JVM via <code>wrapper.config</code>, and is set at 3 for less than 64MB, 4 for less than 256M, or 5 for more than 256MB. Note: A change to this setting requires a restart of the router.")%></td></tr>

<tr><th>router.maxParticipatingTunnels={n}</th></tr>
<tr><td><%=intl._t("Determines the maximum number of participating tunnels the router can build. To disable participation completely, set to 0. [Default is 8000, or 2000 if running on Arm or Android]")%></td></tr>

<tr><th>router.maxTunnelPercentage={n}</th></tr>
<tr><td><%=intl._t("Defines the maximum percentage of active local tunnels (client and exploratory) that a peer will be used for. [Default is 15% in I2P+, 33% in I2P]")%></td></tr>

<tr><th>router.networkDatabase.flat={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, the router info files stored in your profile's netDB directory will not be split into 64 sub-directories. [Default is false]")%></td></tr>

<tr><th>router.rebuildKeys={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, the router will change its identity and UDP port when the router restarts and then delete the key from router.config to prevent further changes. [Default is false]")%></td></tr>

<tr><th>router.tunnelConcurrentBuilds={n}</th></tr>
<tr><td><%=intl._t("When configured, this sets a hard limit for the number of tunnels the router is permitted to build concurrently. By default the router uses the average build time and current outbound bandwidth to determine the optimum build rate. [Restart required]")%></td></tr>

<tr><th>router.updateUnsigned={true|false}</th></tr>
<tr><td><%=intl._t("If you wish to install unsigned (.zip) I2P updates, this should be added to your <code>router.config</code> file unless you have already configured <code>routerconsole.advanced=true</code>, in which case this option is already provisioned. Note: as of I2P+ 0.9.48+, installation of <a href=/configupdate#i2pupdates>unsigned updates</a> is enabled by default.")%></td></tr>

<tr><th>router.updateUnsignedURL={url}</th></tr>
<tr><td><%=intl._t("This setting allows you to configure the update url for the unsigned update feature, if enabled. The url should end with <code>/i2pupdate.zip</code>. Note: do not install unsigned updates unless you trust the source of the update!")%></td></tr>

<tr><th>i2p.streaming.answerPings={true|false}</th></tr>
<tr><td><%=intl._t("This tunnel-specific setting allows you to enable or disable replies to pings sent to servers hosted by the router. To disable pings, you must add the line <code>i2p.streaming.answerPings=false</code> to the <i>Custom Options</i> section for the server's configuration in the Tunnel Manager.")%></td></tr>

<tr><th>i2p.vmCommSystem={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, I2P runs without network connectivity, which is helpful if you are constantly restarting the router to test code updates as this prevents network disruption.")%></td></tr>

<tr><th>i2p.streaming.enablePongDelay={true|false} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when enabled, introduces a random pong delay of up to 50ms for all ping-enabled servers hosted by the router. Default is disabled. [Restart required]")%></td></tr>

<tr><th>i2p.streaming.maxPongDelay={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when enabled, modifies the maximum additional random pong delay introduced for ping-enabled servers, if <code>i2p.streaming.enablePongDelay</code> is also enabled. Unless explicitly set, the default value of 50ms will be used.")%></td></tr>

<tr><th>router.codelInterval={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in milliseconds) determines how long a packet may stay in the CoDel queue before it is dropped. Default is 1000ms. [Restart required]")%></td></tr>

<tr><th>router.router.codelMaxQueue={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the maximum number of messages in the outbound queue before it is marked as full. [Default is 768]")%></td></tr>

<tr><th>router.router.codelBacklog={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the maximum number of messages above the maximum queue value before further messages are dropped. [Default is 128]")%></td></tr>

<tr><th>router.codelTarget={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in milliseconds) allows you to manually configure the queue delay target value for the CoDel queue management system. Default is 50ms. [Restart required]")%></td></tr>

<tr><th>router.expireRouterInfo={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in hours) determines how old a RouterInfo in the NetDb is (its last known publication date) before it's classified as stale and deleted. [Default is 28 hours unless the router is a Floodfill, in which case the default is 8 hours]")%></td></tr>

<tr><th>router.exploreBredth={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the number of peers to explore in parallel when the Peer Exploration Job runs [default is 1 peer]. Note that increasing this value will increase the bandwidth requirements for the exploration, and setting this value too high may cause excessive message delay and interfere with other services, so use with caution!")%></td></tr>

<tr><th>router.exploreBuckets={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the number of NetDb DHT buckets the Peer Exploration Job will reference when run. By default, the number of buckets to explore will vary depending on whether the router has been running for over an hour, if the router is hidden or in the 'K' bandwidth tier, and the number of known routers.")%></td></tr>

<tr><th>router.explorePeersDelay={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in seconds) allows you to override the delay between runs of the Peer Exploration Job which attempts to locate new peers to add to the NetDb. The job will run every 80 seconds by default, or if the router is experiencing job or message lag, every 3 minutes. If the size of the NetDb reaches 4000 peers, the pause will increase to 15 minutes. You may wish to increase the delay if your NetDb is well-populated (over 2000 peers), or if you wish to reduce overall bandwidth usage. Note: For Floodfill routers, this job does not run.")%></td></tr>

<tr><th>router.exploreQueue={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the maximum number of peer hashes to queue for exploration. In I2P the maximum is 128, and in I2P+ the default value is 512.")%></td></tr>

<tr><th>router.exploreWhenFloodfill={true|false} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines whether a router acting as a floodfill will explore the NetDb to learn about new peers. [Default is false, restart required]")%></td></tr>

<tr><th>router.forceUnreachable={true|false}</th></tr>
<tr><td><%=intl._t("This setting, when set to true, prevents a router from directly communicating with other routers, forcing the use of introducers. [Default is false unlesss router is in hidden mode]")%></td></tr>

<tr><th>router.minVersionAllowed={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting allows you to exclude older routers from your NetDb. Routers older than the default or the value you set will be deleted from the NetDb and banned for the duration of the router session. By default, routers older than 0.9.20 are excluded. Note: starting with I2P+ version 0.9.46+, only routers running very recent versions are used for building local tunnels. [Restart required]")%></td></tr>

<tr><th>router.peerTestConcurrency={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the number of peer tests to run concurrently, used to determine peer latency. [Default is 4]")%></td></tr>

<tr><th>router.peerTestDelay={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the delay between peer test jobs, configured in milliseconds. [Default is 5000]")%></td></tr>

<tr><th>router.peerTestTimeout={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("To determine peer latency, this setting allocates the maximum time a peer test should take before being considered a failure, configured in milliseconds. In the event that the timeout is configured lower than the average successful test, the average successful test value will be used. [Default is 1000]")%></td></tr>

<tr><th>router.publishPeerRankings={true|false}</th></tr>
<tr><td><%=intl._t("This setting determines whether stats about our router are sporadically published to the NetDb. [Default is false]")%></td></tr>

<tr><th>router.pumpInitialOutboundQueue={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting configures the initial size of the outbound message queue. [Default is 64]")%></td></tr>

<tr><th>router.pumpMaxInboundQueue={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting configures the maximum size of the inbound message queue. [Default is 1024]")%></td></tr>

<tr><th>router.pumpMaxInboundMsgs={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting configures the maximum number of inbound messages queued per cycle (pump). [Default is 24]")%></td></tr>

<tr><th>router.pumpMaxOutboundMsgs={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting configures the maximum number of outbound messages queued per cycle (pump). [Default is 64]")%></td></tr>

<tr><th>router.refreshRouterDelay={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in milliseconds) allows you to manually configure the delay between router refresh updates run by the Refresh Routers Job. By default the pause between refreshes is determined by the size of the NetDb, and introduces some randomness in the timing to mitigate traffic analysis. For values lower than 2000 milliseconds, increasing the value of <code>router.refreshTimeout</code> is recommended. Note that setting this value below 2000 milliseconds will increase your network traffic and may introduce job lag, and is not recommended for sustained use.")%></td></tr>

<tr><th>router.refreshSkipIfYounger={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in hours) allows you to manually configure how old a RouterInfo is in order for it to be checked by the Refresh Routers Job. By default, the age of a RouterInfo before a refresh is intitiated scales according to the size of the NetDb, increasing as the NetDb grows in size. A value of 0 will force the Router Refresh Job to check every router in the NetDb, regardless of age.")%></td></tr>

<tr><th>router.refreshTimeout={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in seconds) allows you to manually configure the amount of time to wait before an attempt to refresh a router is determined to have failed. [The default is 20 seconds]")%></td></tr>

<tr><th>router.refreshUninteresting={true|false} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines whether routers classified as uninteresting (K or L tier, unreachable or older than 0.9.50) are checked during the refresh router process after 1 hour of uptime. [Default is false]")%></td></tr>

<tr><th>router.validateRoutersAfter={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in minutes) allows you to manually configure how long to wait after startup before RouterInfos in the NetDb are checked for validity, after which point only valid routers will be accepted for inclusion. When the validation occurs, expired RouterInfos and unresponsive peers only accessible via SSU will be removed from the NetDb. [Default is 60 minutes] Note: This setting has no bearing on older routers (older than 0.9.29 by default) which are removed from the NetDb and banned for the router session as soon as a NetDb store is attempted.")%></td></tr>

<tr><th>routerconsole.graphHiDpi={true|false} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will enable HiDPI mode for the graphs on <a href=/graphs>the graphs page</a>. Once enabled, you may need to refresh the page to see the settings applied, and the graph display may take a couple of refresh cycles to stabilize. Note that Javascript should be enabled in your browser. [Experimental]")%></td></tr>

<tr><th>routerconsole.showPeerTestAvg={true|false} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will display a Peer Test Average readout in the Congestion section of the sidebar, calculated by adding the successful peer test average and the average excess time taken by failed tests. The average value may be used as a guide to determine the optimal <code>router.peerTestTimeout</code> value. [Default is false]")%></td></tr>

<tr><th>routerconsole.sitemapSites={true|false} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will display the <i>Sites of Interest</i> links on <a href=/sitemap>the sitemap</a>. [Default is false]")%></td></tr>

<tr id="ntpserverconfig"><th>time.sntpServerList={server1,server2}</th></tr>
<tr><td><%=intl._t("This setting permits the configuration of alternative NTP servers required to ensure that your router maintains accurate clock time. [Default is 0.pool.ntp.org,1.pool.ntp.org,2.pool.ntp.org]")%></td></tr>

<tr><th>desktopgui.enabled={true|false}</th></tr>
<tr><td><%=intl._t("If set to true, this option will place an icon in the system tray / notification area, with basic service control options. [Disabled by default]")%></td></tr>

<tr><th>i2psnark.maxFilesPerTorrent={n} <span class="plus">I2P+</span></th></tr>
<tr><td><%=intl._t("This setting allows configuration of the maximum number of files per torrent I2PSnark will permit, when downloading or creating a torrent. Note that substantially increasing this value from the default of 2000 files may require additional configuration on the host system to increase the maximum number of open files the operating system will permit (e.g. <code>ulimit -n</code> on Linux). To change, add to I2PSnark's configuration file <code>i2psnark.config</code>. [Restart of I2PSnark or router required]")%></td></tr>

</table>

</div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>