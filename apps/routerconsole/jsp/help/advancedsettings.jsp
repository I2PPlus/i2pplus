<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="64kb"%>
<!DOCTYPE HTML>
<%@include file="../head.jsi"%>
<%  String pageTitlePrefix = ctx.getProperty("routerconsole.pageTitlePrefix");
    pageTitlePrefix = (pageTitlePrefix != null) ? pageTitlePrefix + ' ' : "";
%>
<title><%=pageTitlePrefix%> <%=intl._t("Advanced Configuration Help - I2P+")%></title>
</head>
<body>
<%@include file="../sidebar.jsi"%><h1 class=hlp><%=intl._t("Advanced Configuration Help")%></h1>
<div class=main id=help>
<div class=confignav>
<span class=tab><a href="/help/configuration" title="<%=intl._t("Configuration")%>"><%=intl._t("Configuration")%></a></span>
<span class=tab2 id=adv><span><%=intl._t("Advanced Settings")%></span></span>
<span class=tab><a href="/help/ui" title="<%=intl._t("User Interface")%>"><%=intl._t("User Interface")%></a></span>
<span class=tab><a href="/help/reseed" title="<%=intl._t("Reseeding")%>"><%=intl._t("Reseeding")%></a></span>
<span class=tab><a href="/help/tunnelfilter" title="<%=intl._t("Tunnel Filtering")%>"><%=intl._t("Tunnel Filtering")%></a></span>
<span class=tab><a href="/help/jsonrpc" title="<%=intl._t("Console API")%>"><%=intl._t("JSONRPC")%></a></span>
<span class=tab><a href="/help/faq" title="<%=intl._t("Frequently Asked Questions")%>"><%=intl._t("FAQ")%></a></span>
<span class=tab><a href="/help/newusers" title="<%=intl._t("New User Guide")%>"><%=intl._t("New User Guide")%></a></span>
<span class=tab><a href="/help/webhosting" title="<%=intl._t("Web Hosting")%>"><%=intl._t("Web Hosting")%></a></span>
<span class=tab><a href="/help/hostnameregistration" title="<%=intl._t("Hostname Registration")%>"><%=intl._t("Hostname Registration")%></a></span>
<span class=tab><a href="/help/troubleshoot" title="<%=intl._t("Troubleshoot")%>"><%=intl._t("Troubleshoot")%></a></span>
<span class=tab><a href="/help/glossary" title="<%=intl._t("Glossary")%>"><%=intl._t("Glossary")%></a></span>
<span class=tab><a href="/help/legal" title="<%=intl._t("Legal")%>"><%=intl._t("Legal")%></a></span>
<span class=tab><a href="/help/changelog" title="<%=intl._t("Change Log")%>"><%=intl._t("Change Log")%></a></span>
</div>

<div id=advancedsettings>
<h2><%=intl._t("Advanced Router Configuration")%></h2>

<p class=infohelp><%=intl._t("The router configuration options listed below are not available in the user interface, usually because they are rarely used or provide access to advanced settings that most users will not need. This is not a comprehensive list. Some settings will require a restart of the router to take effect. Note that all settings are case sensitive. You will need to edit your <code>router.config</code> file to add options, or, once you have added <code>routerconsole.advanced=true</code> to the router.config file, you may edit settings within the console on the <a href=/configadvanced>Advanced Configuration page</a>.")%></p>

<table id=configinfo>

<tr class=section><th>routerconsole</th></tr>

<tr class=config><th id=advancedconsole>routerconsole.advanced={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, additional functionality will be enabled in the console and the user will be able to edit settings directly on the <a href=/configadvanced>Advanced Configuration page</a>. Extra display options are provided in the <a href=/netdb>Network Database section</a>, including the <a href='/netdb?f=3'>Sybil Analysis tool</a>, and there are additional configuration options on the <a href=/configclients>Clients Configuration page</a>. This will also enable the installation of unsigned updates, manual configuration of the news URL, and additional sections on the sidebar.")%></td></tr>

<tr class=config><th>routerconsole.allowUntrustedPlugins={true|false}</th></tr>
<tr><td><%=intl._t("Plugins signed with the cryptographic key of the developer are the recommended format, but if you wish to install unsigned plugins (.zip) you can set this to true. Note that you may still encounter issues attempting to install an unsigned plugin if the developer has included additional checks in the plugin build process.")%></td></tr>

<tr class=config><th>routerconsole.browser={/path/to/browser}</th></tr>
<tr><td><%=intl._t("This setting allows the manual selection of the browser which I2P will launch on startup (if the console is <a href=/configservice#browseronstart>configured</a> to launch a browser on startup), overriding the OS default browser. For more information, see the <a href=#alternative_browser>FAQ entry</a> above.")%></td></tr>

<tr class=config><th>routerconsole.enableCompression={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, this enables gzip compression for the router console and default web applications. [Enabled by default]")%></td></tr>

<tr class=config><th>routerconsole.enablePluginInstall={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, this enables plugin installation on the <a href=/configplugins>Plugin Configuration page</a>. [Enabled by default]")%></td></tr>

<tr class=config><th>routerconsole.enableReverseLookups={true|false}  <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to true, this setting enables reverse DNS lookups for peers, triggered when displaying the country flag of a peer or when the hostname or domain is explicitly displayed in the console, or when the peer's RouterInfo is written to disk. In order to avoid repeat lookups, it's recommended to configure a generous caching policy for DNS lookups of 10 minutes or more, and to configure a privacy respecting DNS server in your operating system. [Disabled by default, restart required]")%></td></tr>

<tr class=config><th>routerconsole.pageTitlePrefix={string} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting permits a custom text prefix to be set for the page title on router console pages, potentially useful when you have multiple consoles open on browser tabs and want an easy method to differentiate between them.")%></td></tr>

<tr class=config><th>routerconsole.redirectToHTTPS={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, accessing the router console via http:// when https:// is enabled will automatically redirect to https. To configure router console access, edit the settings in your <code>clients.config</code> file in your profile directory (<i>not</i> the application installation directory). [Enabled by default]")%></td></tr>

<tr class=config><th>routerconsole.showPeerTestAvg={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will display a Peer Test Average readout in the Congestion section of the sidebar, calculated by adding the successful peer test average and the average excess time taken by failed tests. The average value may be used as a guide to determine the optimal <code>router.peerTestTimeout</code> value. [Default is false]")%></td></tr>

<tr class=config><th>routerconsole.sitemapSites={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will display the <i>Sites of Interest</i> links on <a href=/sitemap>the sitemap</a>. [Default is false]")%></td></tr>

<tr class=config><th>routerconsole.unifiedSidebar={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will present the standard sidebar on the homepage, enabling configuration via <a href=/configsidebar>. [Default is false]")%></td></tr>

<tr class=section><th>router</th></tr>

<tr class=config><th>router.blocklist.enable={true|false}</th></tr>
<tr><td><%=intl._t("This setting determines whether or not the router should use the provided <i>blocklist.txt</i> file and any user configured blocklists to enable banning of routers by hash or ip address. Ranges and net masks are supported for IPv4 but not IPv6. See the <i>blocklist.txt</i> in your I2P+ application directory for more info. [Enabled by default, restart required]")%></td></tr>

<tr class=config><th>router.blocklist.expireInterval={n}</th></tr>
<tr><td><%=intl._t("This setting permits an expiry time to be configured (in seconds) for bans enforced via blocklists. By default bans persist for the duration of the router session. [Restart required]")%></td></tr>

<tr class=config><th>router.blocklist.file={/path/to/additional_blocklist.txt}</th></tr>
<tr><td><%=intl._t("This setting, when configured with a path pointing at a valid blocklist file, enables an additional, user-configured blocklist to be loaded at router startup. [Disabled by default, restart required]")%></td></tr>

<tr class=config><th>router.blocklistTor.enable={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines whether or not the router should use the provided <i>blocklist_tor.txt</i> file in your I2P+ application directory to enable banning of routers operating from Tor exit node ip addresses. [Enabled by default, restart required]")%></td></tr>

<tr class=config><th>router.blockCountries={countrycode,countrycode2} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting enables requests from routers in the specified countries to be blocked and banned as soon as a direct tunnel request is made. Countries must be specified as two letter country codes, separated with commas. e.g. router.blockCountries=cn,ru")%></td></tr>

<tr class=config><th>router.blockOldRouters={true|false}</th></tr>
<tr><td><%=intl._t("When set to false, the router will not block tunnel build requests from slower or unreachable routers running older versions. [Default is true, restart required]")%></td></tr>

<tr class=config><th>router.buildHandlerThreads={n}</th></tr>
<tr><td><%=intl._t("Allocate number of processor threads for building tunnels. If your processor supports hyperthreading or simultaneous multithreading, you may multiply the number of processor cores by 2 to get the maximum number of threads to allocate, otherwise number of processor cores = maximum number of threads available. Note that you may wish to allocate less than the theoretical maximum to ensure you have headroom for other tasks.")%></td></tr>

<tr class=config><th>router.codelInterval={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in milliseconds) determines how long a packet may stay in the CoDel queue before it is dropped. Default is 1000ms. [Restart required]")%></td></tr>

<tr class=config><th>router.codelMaxQueue={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the maximum number of messages in the outbound queue before it is marked as full. [Default is 768]")%></td></tr>

<tr class=config><th>router.codelBacklog={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the maximum number of messages above the maximum queue value before further messages are dropped. [Default is 128]")%></td></tr>

<tr class=config><th>router.codelTarget={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in milliseconds) allows you to manually configure the queue delay target value for the CoDel queue management system. Default is 50ms. [Restart required]")%></td></tr>

<tr class=config><th>router.defaultProcessingTimeThrottle={n}</th></tr>
<tr><td><%=intl._t("This setting, in milliseconds, overrides the default message processing time limit (indicated as <i>Message Delay</i> in the Congestion sidebar section) before the router is determined to be congested and transit tunnel requests are rejected. [Default is 750ms or 1500ms if the router is flagged as slow]")%></td></tr>

<tr class=config><th>router.disableTunnelTesting={true|false}</th></tr>
<tr><td><%=intl._t("Periodically test tunnels to determine the average lag, and display the results in the Congestion sidepanel section. To enable, set this value to false. [Default is false]")%></td></tr>

<tr class=config><th>router.dynamicKeys={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, the router will change its identity and UDP port every time the router restarts. [Default is false]")%></td></tr>

<tr class=config><th>router.enableTransitThrottle={true|false}</th></tr>
<tr><td><%=intl._t("When set to false, the router will not throttle tunnel build requests from other routers, and should be used with caution. [Default is true, restart required]")%></td></tr>

<tr class=config><th>router.excludePeerCaps={netDBcaps}</th></tr>
<tr><td><%=intl._t("This setting determines which <a href=/profiles#profile_defs>peer capabilities</a> will not be used to build your router's tunnels. e.g. <code>router.excludePeerCaps=LMN</code>")%></td></tr>

<tr class=config><th>router.expireRouterInfo={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in hours) determines how old a RouterInfo in the NetDb is (its last known publication date) before it's classified as stale and deleted. [Default is 28 hours unless the router is a Floodfill, in which case the default is 8 hours]")%></td></tr>

<tr class=config><th>router.exploreBredth={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the number of peers to explore in parallel when the Peer Exploration Job runs [default is 1 peer at a time when more than 3000 peers are known]. Note that increasing this value will increase the bandwidth requirements for the exploration, and setting this value too high may cause excessive message delay and interfere with other services, so use with caution!")%></td></tr>

<tr class=config><th>router.exploreBuckets={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the number of NetDb DHT buckets the Peer Exploration Job will reference when run. By default, the number of buckets to explore will vary depending on whether the router has been running for over an hour, if the router is hidden or in the 'K' bandwidth tier, and the number of known routers.")%></td></tr>

<tr class=config><th>router.explorePeersDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in seconds) allows you to override the delay between runs of the Peer Exploration Job which attempts to locate new peers to add to the NetDb. The job will run every 80 seconds by default, or if the router is experiencing job or message lag, every 3 minutes. If the size of the NetDb reaches 4000 peers, the pause will increase to 15 minutes. You may wish to increase the delay if your NetDb is well-populated (over 2000 peers), or if you wish to reduce overall bandwidth usage. Note: For Floodfill routers, this job does not run.")%></td></tr>

<tr class=config><th>router.exploreQueue={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the maximum number of peer hashes to queue for exploration. In I2P the maximum is 128, and in I2P+ the default value is 512.")%></td></tr>

<tr class=config><th>router.exploreWhenFloodfill={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines whether a router acting as a floodfill will explore the NetDb to learn about new peers. [Default is false, restart required]")%></td></tr>

<tr class=config><th>router.forceUnreachable={true|false}</th></tr>
<tr><td><%=intl._t("This setting, when set to true, prevents a router from directly communicating with other routers, forcing the use of introducers. [Default is false unlesss router is in hidden mode]")%></td></tr>

<tr class=config><th>router.hideFloodfillParticipant={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, if your router is serving as a floodfill for the network, your <a href=/configadvanced#ffconf>floodfill participation</a> will be hidden from other routers.")%></td></tr>

<tr class=config><th>router.maxJobRunners={n}</th></tr>
<tr><td><%=intl._t("Defines the maximum number of parallel <a href=/jobs>jobs</a> that can be run. The default value is determined by the amount of memory allocated to the JVM via <code>wrapper.config</code>, and is set at 3 for less than 64MB, 4 for less than 256M, or 5 for more than 256MB. Note: A change to this setting requires a restart of the router.")%></td></tr>

<tr class=config><th>router.maxParticipatingTunnels={n}</th></tr>
<tr><td><%=intl._t("Determines the maximum number of participating tunnels the router can build. To disable participation completely, set to 0. [Default is 8000, or 2000 if running on Arm or Android]")%></td></tr>

<tr class=config><th>router.maxTunnelPercentage={n}</th></tr>
<tr><td><%=intl._t("Defines the maximum percentage of active local tunnels (client and exploratory) that a peer will be used for. [Default is 10% in I2P+, 33% in I2P]")%></td></tr>

<tr class=config><th>router.minThrottleTunnels={n}</th></tr>
<tr><td><%=intl._t("This setting allows you to configure the minimum number of hosted participating tunnels before the router starts to reject tunnel requests based on anticipated bandwidth requirements. [Default is 100 if system is running Android, 800 if system is running on ARM and less than 4 cores available, 1200 if system is running on ARM and 4 or more cores available, otherwise 2500]")%></td></tr>

<tr class=config><th>router.minVersionAllowed={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting allows you to exclude older routers from your NetDb. Routers older than the default or the value you set will be deleted from the NetDb and banned for the duration of the router session. By default, routers older than 0.9.20 are excluded. Note: starting with I2P+ version 0.9.46+, only routers running very recent versions are used for building local tunnels. [Restart required]")%></td></tr>

<tr class=config><th>router.networkDatabase.flat={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, the router info files stored in your profile's netDB directory will not be split into 64 sub-directories. [Default is false]")%></td></tr>

<tr class=config><th>router.overrideIsSlow={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to true, your router will not be classified as slow, regardless of the specifications of your host system. Ordinarily, routers running on hosts with the following characteristics will be classified as slow, and various performance-related router options will be modified accordingly:")%>
<ul>
<li><%=intl._t("Host OS running Android")%></li>
<li><%=intl._t("Host OS running on ARM (MacOS excepted)")%></li>
<li><%=intl._t("32bit host OS running with less than 4 detected cores")%></li>
<li><%=intl._t("64bit host OS running with 1 detected core and less than 384MB allocated to the JVM")%></li>
<li><%=intl._t("Less than 256MB allocated to the JVM")%></li>
<li><%=intl._t("Router running a non-native, software emulated version of the Java BigInteger library")%></li>
</ul></td></tr>

<tr class=config><th>router.peerTestConcurrency={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the number of peer tests to run concurrently, used to determine peer latency. [Default is 4]")%></td></tr>

<tr class=config><th>router.peerTestDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the delay between peer test jobs, configured in milliseconds. [Default is 5000]")%></td></tr>

<tr class=config><th>router.peerTestTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("To determine peer latency, this setting allocates the maximum time a peer test should take before being considered a failure, configured in milliseconds. In the event that the timeout is configured lower than the average successful test, the average successful test value will be used. [Default is 1000]")%></td></tr>

<tr class=config><th>router.publishPeerRankings={true|false}</th></tr>
<tr><td><%=intl._t("This setting determines whether stats about our router are sporadically published to the NetDb. [Default is false]")%></td></tr>

<tr class=config><th>router.pumpInitialOutboundQueue={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting configures the initial size of the outbound message queue. [Default is 64]")%></td></tr>

<tr class=config><th>router.pumpMaxInboundMsgs={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting configures the maximum number of inbound messages queued per cycle (pump). [Default is 24]")%></td></tr>

<tr class=config><th>router.pumpMaxInboundQueue={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting configures the maximum size of the inbound message queue. [Default is 1024]")%></td></tr>

<tr class=config><th>router.pumpMaxOutboundMsgs={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting configures the maximum number of outbound messages queued per cycle (pump). [Default is 64]")%></td></tr>

<tr class=config><th>router.rebuildKeys={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, the router will change its identity and UDP port when the router restarts and then delete the key from router.config to prevent further changes. [Default is false]")%></td></tr>

<tr class=config><th>router.refreshRouterDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in milliseconds) allows you to manually configure the delay between router refresh updates run by the Refresh Routers Job. By default the pause between refreshes is determined by the size of the NetDb, and introduces some randomness in the timing to mitigate traffic analysis. For values lower than 2000 milliseconds, increasing the value of <code>router.refreshTimeout</code> is recommended. Note that setting this value below 2000 milliseconds will increase your network traffic and may introduce job lag, and is not recommended for sustained use.")%></td></tr>

<tr class=config><th>router.refreshSkipIfYounger={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in hours) allows you to manually configure how old a RouterInfo is in order for it to be checked by the Refresh Routers Job. By default, the age of a RouterInfo before a refresh is intitiated scales according to the size of the NetDb, increasing as the NetDb grows in size. A value of 0 will force the Router Refresh Job to check every router in the NetDb, regardless of age.")%></td></tr>

<tr class=config><th>router.refreshTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in seconds) allows you to manually configure the amount of time to wait before an attempt to refresh a router is determined to have failed. [The default is 20 seconds]")%></td></tr>

<tr class=config><th>router.refreshUninteresting={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines whether routers classified as uninteresting (K or L tier, unreachable or older than 0.9.50) are checked during the refresh router process after 1 hour of uptime. [Default is false]")%></td></tr>

<tr class=config><th>router.relaxCongestionCaps={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to true, this setting will relax the conditions required for your router to publish congestion caps, specifically when it's determined that your bandwidth usage is high. Other conditions, such as high job lag or a router classified as slow, will still cause caps to be published. [Default is false]")%></td></tr>

<tr class=config><th>router.tunnelConcurrentBuilds={n}</th></tr>
<tr><td><%=intl._t("When configured, this sets a hard limit for the number of tunnels the router is permitted to build concurrently. By default the router uses the average build time and current outbound bandwidth to determine the optimum build rate. [Restart required]")%></td></tr>

<tr class=config><th>router.updateUnsigned={true|false}</th></tr>
<tr><td><%=intl._t("If you wish to install unsigned (.zip) I2P updates, this should be added to your <code>router.config</code> file unless you have already configured <code>routerconsole.advanced=true</code>, in which case this option is already provisioned. Note: as of I2P+ 0.9.48+, installation of <a href=/configupdate#i2pupdates>unsigned updates</a> is enabled by default.")%></td></tr>

<tr class=config><th>router.updateUnsignedURL={url}</th></tr>
<tr><td><%=intl._t("This setting allows you to configure the update url for the unsigned update feature, if enabled. The url should end with <code>/i2pupdate.zip</code>. Note: do not install unsigned updates unless you trust the source of the update!")%></td></tr>

<tr class=config><th>router.validateRoutersAfter={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in minutes) allows you to manually configure how long to wait after startup before RouterInfos in the NetDb are checked for validity, after which point only valid routers will be accepted for inclusion. When the validation occurs, expired RouterInfos and unresponsive peers only accessible via SSU will be removed from the NetDb. [Default is 60 minutes] Note: This setting has no bearing on older routers (older than 0.9.29 by default) which are removed from the NetDb and banned for the router session as soon as a NetDb store is attempted.")%></td></tr>

<tr class=section><th>i2np</th></tr>

<tr class=config><th>i2np.blockMyCountry={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will block direct communication from all routers in your own country and add them to your banlist until your router restarts. [Default is false]")%></td></tr>

<tr class=config><th>i2np.blockXG={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will block direct communication from all X tier routers that publish a G congestion cap (no transit tunnels), adding them to the session banlist. [Default is true]")%></td></tr>

<tr class=config><th>i2np.udp.disablePeerTest={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting permits disabling SSU tests to determine if your router is firewalled and can be enabled for routers that are definitely not firewalled but are experiencing intermittent firewalling issues. In the event that testing is disabled, both SSU and NTCP should report your current public ip address on <a href=/info>the router info page</a>. [Restart required]")%></td></tr>

<tr class=config><th>i2np.udp.preferred={true|false|always}</th></tr>
<tr><td><%=intl._t("This setting determines which transport takes priority when communicating with peers. By default, NTCP is favored over UDP (SSU). By setting the value to true, UDP will be favored unless a pre-existing NTCP connection exists; when set to always, UDP will always be used regardless of any pre-existing NTCP connection. [Default is false]")%></td></tr>

<tr class=section><th><%=intl._t("miscellaneous")%></th></tr>

<tr class=config><th>desktopgui.enabled={true|false}</th></tr>
<tr><td><%=intl._t("If set to true, this option will place an icon in the system tray / notification area, with basic service control options. [Disabled by default]")%></td></tr>

<tr class=config><th>i2psnark.maxFilesPerTorrent={n}</th></tr>
<tr><td><%=intl._t("This setting allows configuration of the maximum number of files per torrent I2PSnark will permit, when downloading or creating a torrent. Note that substantially increasing this value from the default of 2000 files may require additional configuration on the host system to increase the maximum number of open files the operating system will permit (e.g. <code>ulimit -n</code> on Linux). To change, add to I2PSnark's configuration file <code>i2psnark.config</code>. [Restart of I2PSnark or router required]")%></td></tr>

<tr class=config><th>i2p.streaming.answerPings={true|false}</th></tr>
<tr><td><%=intl._t("This tunnel-specific setting allows you to enable or disable replies to pings sent to servers hosted by the router. To disable pings, you must add the line <code>i2p.streaming.answerPings=false</code> to the <i>Custom Options</i> section for the server's configuration in the Tunnel Manager.")%></td></tr>

<tr class=config><th>i2p.streaming.enablePongDelay={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when enabled, introduces a random pong delay of up to 50ms for all ping-enabled servers hosted by the router. Default is disabled. [Restart required]")%></td></tr>

<tr class=config><th>i2p.streaming.maxPongDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when enabled, modifies the maximum additional random pong delay introduced for ping-enabled servers, if <code>i2p.streaming.enablePongDelay</code> is also enabled. Unless explicitly set, the default value of 50ms will be used.")%></td></tr>

<tr class=config><th>i2p.vmCommSystem={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, I2P runs without network connectivity, which is helpful if you are constantly restarting the router to test code updates as this prevents network disruption.")%></td></tr>

<tr class=config id=ntpserverconfig><th>time.sntpServerList={server1,server2}</th></tr>
<tr><td><%=intl._t("This setting permits the configuration of alternative NTP servers required to ensure that your router maintains accurate clock time. [Default is 0.pool.ntp.org,1.pool.ntp.org,2.pool.ntp.org]")%></td></tr>

</table>

</div>
</div>
<script src=/js/toggleElements.js></script>
<script nonce=<%=cspNonce%>>setupToggles("#configinfo tr.config", "#configinfo tr:not(.config):not(.section)", "table-row");</script>
<noscript><style>#configinfo tr.config th{cursor:default}#configinfo tr.config th::after{display:none!important}#configinfo td{border-bottom-width:1px!important}</style></noscript>
</body>
</html>