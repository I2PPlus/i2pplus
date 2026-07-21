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

<tr class=config><th>routerconsole.enforceLogin={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to true, the router console will require login authentication. If no password is set, you will be required to set one on the login page. When set to false, the console is accessible without login if no passwords are configured. [Default is false, will be enabled by default in a future release for CSRF defense]")%></td></tr>

<tr class=config><th>routerconsole.sidebarGraphLegacy={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to true, the sidebar bandwidth graph uses the legacy RRD4J SVG renderer. When set to false, the new dual-baseline canvas renderer is used, with gradient fills, glow effects, and a split inbound/outbound display. [Default is false]")%></td></tr>

<tr class=config><th>routerconsole.sidebarGraphMinutes={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The time period, in minutes, displayed in the sidebar bandwidth graph. Values outside the valid range (2–30) are clamped. [Default is 20]")%></td></tr>

<tr class=config><th>routerconsole.sidebarGraphSplit={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to true (the default), inbound and outbound traffic are displayed in a split view with a center baseline, with outbound spikes upward and inbound spikes downward. When set to false, both lines are displayed in overlay mode from the top of the graph with a configurable composite blend. [Default is true]")%></td></tr>

<tr class=config><th>routerconsole.sidebarGraphDirection={ltr|rtl} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Sets the render direction for the sidebar bandwidth graph. When set to rtl (the default), the graph renders right-to-left with newest data on the right. When set to ltr, the graph renders left-to-right with newest data on the left. [Default is rtl]")%></td></tr>

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

<tr class=config><th>router.banlogger.maxArchives={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The maximum number of session banlog archive files to keep. When exceeded, older archives are deleted. [Default is 5]")%></td></tr>

<tr class=config><th>router.hashScan.frequency={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The frequency in milliseconds to scan for suspicious hash patterns (potential Sybil attacks). Set to 0 to disable. [Default is 3600000]")%></td></tr>

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

<tr class=config><th>router.inboundExploratoryExcludeUnreachable={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to false, unreachable peers may be selected as hops for inbound exploratory tunnels. [Default is true]")%></td></tr>

<tr class=config><th>router.outboundExploratoryExcludeUnreachable={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to false, unreachable peers may be selected as hops for outbound exploratory tunnels. [Default is true]")%></td></tr>

<tr class=config><th>router.inboundClientExcludeUnreachable={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to false, unreachable peers may be selected as hops for inbound client tunnels. [Default is true]")%></td></tr>

<tr class=config><th>router.outboundClientExcludeUnreachable={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to false, unreachable peers may be selected as hops for outbound client tunnels. [Default is true]")%></td></tr>

<tr class=config><th>router.inboundExploratoryExcludeSlow={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to false, slow peers (high tunnel test times) may be selected as hops for inbound exploratory tunnels. [Default is true]")%></td></tr>

<tr class=config><th>router.outboundExploratoryExcludeSlow={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to false, slow peers may be selected as hops for outbound exploratory tunnels. [Default is true]")%></td></tr>

<tr class=config><th>router.inboundClientExcludeSlow={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to false, slow peers may be selected as hops for inbound client tunnels. [Default is true]")%></td></tr>

<tr class=config><th>router.outboundClientExcludeSlow={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to false, slow peers may be selected as hops for outbound client tunnels. [Default is true]")%></td></tr>

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

<tr class=config><th>router.minJobRunners={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Defines the minimum number of parallel <a href=/jobs>job runners</a>. The router will not scale down below this number. Default is 4, minimum is 1. This setting is dynamic and takes effect without restart.")%></td></tr>

<tr class=config><th>router.dynamicJobScaling={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Enable dynamic scaling of job runners based on queue load. When enabled, the router automatically scales the number of job runner threads up or down based on ready job count and lag. [Default is true]")%></td></tr>

<tr class=config><th>router.scaleUpLagThreshold={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The job queue lag threshold in milliseconds that triggers scaling up job runners. When max lag exceeds this value, additional runners are started. [Default is 1]")%></td></tr>

<tr class=config><th>router.scaleDownLagThreshold={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The job queue lag threshold in milliseconds that allows scaling down job runners. When max lag stays below this value, excess runners are stopped. [Default is 1]")%></td></tr>

<tr class=config><th>router.scaleUpJobsRatio={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The ratio of ready jobs to active runners that triggers scaling up. If readyJobs > activeRunners * ratio, additional runners are started. [Default is 1.2]")%></td></tr>

<tr class=config><th>router.scaleCheckInterval={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The interval in milliseconds between job runner scaling checks. [Default is 1000]")%></td></tr>

<tr class=config><th>router.scaleCooldown={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The cooldown period in milliseconds between scale operations. [Default is 5000]")%></td></tr>

<tr class=config><th>router.scaleFeedbackEnabled={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Enable the feedback mechanism that automatically rolls back ineffective scale-up operations. If lag increases after adding runners, they will be removed. [Default is true]")%></td></tr>

<tr class=config><th>router.maxWaitingJobs={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The maximum number of waiting jobs in the queue before aggressive job dropping is triggered. When exceeded, non-critical jobs are dropped to reduce queue pressure. [Default is 48]")%></td></tr>

<tr class=config><th>router.buildHandlerMaxQueue={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of pending tunnel build jobs in the BuildHandler queue. When the queue exceeds this limit, new build requests are rejected to prevent job queue overload. [Default is 256]")%></td></tr>

<tr class=config><th>router.buildConcurrencyMultiplier={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Multiplier applied to the base tunnel build concurrency. The effective concurrency is baseBuildRate * multiplier, clamped by maxConcurrentBuilds. Higher values accelerate pool recovery but increase network load. [Default is 1.0]")%></td></tr>

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
<tr><td><%=intl._t("This setting determines the number of peer tests to run concurrently, used to determine peer latency. When tunnel build success drops below 50%, concurrency is halved to avoid adding load during a failure cascade. [Default varies: 1 on slow systems, 2 on single/dual-core, 4 otherwise]")%></td></tr>

<tr class=config><th>router.peerTestDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting determines the delay between peer test jobs, configured in milliseconds. [Default is 30000 (30s), or 45000 (45s) on slow systems]")%></td></tr>

<tr class=config><th>router.peerTestTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("To determine peer latency, this setting allocates the maximum time a peer test should take before being considered a failure, configured in milliseconds. In the event that the timeout is configured lower than the average successful test, the average successful test value will be used. [Default is 5000]")%></td></tr>

<tr class=config><th>profileOrganizer.minFastPeers={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum number of peers to retain in the fast tier during profile reorganization. A higher value ensures more peers are available for tunnel building, at the cost of potentially including slower peers. If more than 3000 routers are known, the default scales to <code>knownRouters / 15</code>. [Default is 400]")%></td></tr>

<tr class=config><th>profileOrganizer.minHighCapacityPeers={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum number of peers to retain in the high-capacity tier. These are the peers with the highest measured throughput and greatest ability to relay traffic. If more than 3000 routers are known, the default scales to <code>knownRouters / 15</code>. [Default is 500]")%></td></tr>

<tr class=config><th>profileOrganizer.maxProfiles={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of peer profiles to retain in memory. Older, inactive profiles are expired first when this limit is reached. Values below 100 are clamped to 100. [Default is 8000, or 800 on slow systems]")%></td></tr>

<tr class=config><th>profileOrganizer.maxRouterInfoAgeHours={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum age of a RouterInfo (in hours) for a peer to be considered selectable for tunnel building. Peers with RouterInfos older than this threshold are excluded from selection unless they have an active connection. This is particularly relevant for floodfill routers that do not actively explore the NetDB, as their RouterInfos may become stale without periodic refresh. [Default is 3]")%></td></tr>

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
<tr><td><%=intl._t("When configured, this sets a hard limit for the number of tunnels the router is permitted to build concurrently. By default the router uses the average build time and current outbound bandwidth to determine the optimum build rate. This setting is dynamic and takes effect without restart.")%></td></tr>

<tr class=config><th>router.tunnel.useLegacyPeerSelection={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to true, the router uses the older peer selection algorithm when building tunnels (prefer fast peers directly, without the multi-tier HighCap → Fast → Active fallback chain). May be useful for troubleshooting peer selection issues. [Default is false. This setting is dynamic and takes effect immediately.]")%></td></tr>

<tr class=config><th>router.tunnelGrowthFactor={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Growth factor used by the router's transit tunnel throttle. Higher values make the router accept tunnel build requests more aggressively relative to the 10-minute average. Lower values make it probabalistically reject sooner when tunnel counts are rising rapidly. [Default is 2.0]")%></td></tr>

<tr class=config><th>router.tunnelTestTimeGrowthFactor={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Growth factor for tunnel test time acceptance rate limiting. If the 1-minute average test time exceeds the 1-hour average multiplied by this factor, the router begins probabalistically rejecting. [Default is 1.5]")%></td></tr>

<tr class=config><th>router.throttleRejectExponent={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Controls the steepness of the rejection probability curve when bandwidth usage approaches capacity. Lower values give a smoother transition (less aggressive rejection at moderate load). [Default is 10]")%></td></tr>

<tr class=config><th>router.penaltyCapD={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Penalty multiplier applied to a peer's capacity value when the peer publishes a 'D' congestion cap (moderate congestion). Higher values reduce the peer's effective capacity more aggressively. [Default is 2.5]")%></td></tr>

<tr class=config><th>router.penaltyCapE={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Penalty multiplier applied to a peer's capacity value when the peer publishes an 'E' congestion cap (severe congestion). [Default is 4.75]")%></td></tr>

<tr class=config><th>router.pruneEarlyExpiryDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Time in milliseconds before expiration when pruned tunnels are marked for early expiry, giving replacement tunnels time to build. Higher values allow more overlap between old and new tunnels. [Default is 120000 (2 minutes)]")%></td></tr>

<tr class=config><th>router.tunnel.slowThreshold={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The latency threshold in milliseconds for removing slow tunnels from <a href=/tunnels>local tunnel pools</a>. Tunnels with latency above this threshold will be removed if the pool exceeds the minimum tunnel count. Set to 0 to use 1 second or the lowest recorded latency from all pools, whichever is higher. [Default is 0]")%></td></tr>

<tr class=config><th>router.tunnel.slowThresholdMin={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The minimum number of tunnels to keep in each pool when removing slow tunnels (minimum 2), overriding the configured tunnel quantity. Set to 0 to use the configured quantity per pool. [Default is 0]")%></td></tr>

<tr class=config><th>router.tunnel.slowTunnelInterval={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The interval in milliseconds between runs of the Remove Slow Tunnels job. Higher values reduce tunnel churn but may keep slow tunnels longer. [Default is 90000 (90s), or 120000 (120s) if under load]")%></td></tr>

<tr class=config><th>router.tunnel.pruneEarlyExpiryDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The time in milliseconds before expiration to mark tunnels for early expiry when the Remove Slow Tunnels job prunes slow or excess tunnels. Higher values allow more time for new tunnels to build. [Default is 30000]")%></td></tr>

<tr class=section><th>i2p.tunnel</th></tr>

<tr class=config><th>i2p.tunnel.testJob.hardLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Hard limit on total tunnel test jobs (queued + active). Prevents runaway test backlogs from causing job queue lag. Once reached, no new tests are scheduled until the count drops. [Default is 128 (96 on slower systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.maxQueued={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Base adaptive queue limit for tunnel test jobs. The router scales this up/down by 2-3x based on job queue lag and failure rates. [Default is 48 (32 on slower systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.maxConcurrent={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of tunnel tests that can execute simultaneously. Caps concurrent test throughput to prevent overwhelming the router. [Default is 64 (32 on slower systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.buildRequest.maxConsecutiveFails={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum consecutive client tunnel build timeouts before forcing exploratory tunnels for build reply delivery. Higher values keep the router trying paired tunnels longer; lower values fall back to exploratory sooner. [Default is 3]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.requestTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("How long to wait for a tunnel build reply before trying a different pool or different peers. The BuildExecutor adaptively increases this when success rates are low. [Default is 5000 (5s), 10000 (10s) on slow systems]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.firstHopTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Timeout in milliseconds for the first-hop OutNetMessage before firing TunnelBuildFirstHopFailJob. Should be >= requestTimeout for proper sequencing. [Default is 10000 (10 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.nextHopLookupTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Timeout in milliseconds for RouterInfo lookup of the next hop during tunnel build processing. If the lookup times out, the build request is rejected. [Default is 8000 (8 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.exploratoryBackoff={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Backoff delay in milliseconds between exploratory tunnel build retries. Higher values reduce build request rate during failures; lower values retry faster. [Default is 200]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.clientBackoff={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Backoff delay in milliseconds between client tunnel build retries. Lower values rebuild failed client tunnels faster. [Default is 50]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.gracePeriod={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Grace period in milliseconds to accept late tunnel build replies after the router has given up waiting. Prevents wasting valid tunnel builds that arrive just after timeout. [Default is 60000 (60 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.targetMin={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum target tunnels per pool before triggering urgent rebuild logic. When the number of good tunnels drops below this threshold, the pool escalates to urgent rebuild mode. [Default is 2]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.minLookupLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Floor on concurrent next-hop RouterInfo lookups during tunnel build processing. Ensures a minimum level of lookup concurrency even when few tunnels are participating. [Default is 10 (4 on slow systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.maxLookupLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Ceiling on concurrent next-hop RouterInfo lookups during tunnel build processing. Caps lookup concurrency to prevent overwhelming the netDb. [Default is max(cores/2, 16) (10 on slow systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.percentLookupLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Percentage of current participating tunnels used to scale the concurrent lookup limit. The effective limit is clamped between minLookupLimit and maxLookupLimit. [Default is 40 (15 on slow systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.maxRequestFuture={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum allowable future skew for tunnel build request timestamps. Requests with timestamps farther in the future than this are rejected as potential replay attacks. [Default is 300000 (5 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.maxRequestAge={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum age in milliseconds for tunnel build requests (ElGamal). Requests older than this are rejected as potential replay attacks. Must be >1 hour due to rounding in the protocol. [Default is 3900000 (65 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.maxRequestAgeEcies={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum age in milliseconds for tunnel build requests (ECIES). ECIES timestamps are rounded to minutes so the tolerance is tighter. [Default is 480000 (8 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.build.jobLagLimitTunnel={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Job lag threshold in milliseconds that triggers tunnel build limiting. When router job lag exceeds this value, tunnel build requests are throttled to reduce queue pressure. [Default is 800 (500 on slow systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.minTestDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum delay in milliseconds before scheduling a tunnel test. The router will not test a tunnel that was recently tested within this window. [Default is 30000 (30 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.maxTestDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum delay in milliseconds before scheduling a tunnel test. The router will not defer a tunnel test beyond this limit. [Default is 90000 (90 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.maxExploratoryPerPool={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum concurrent tunnel test jobs per exploratory pool. Caps how many exploratory tunnels are tested simultaneously. [Default is 12]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.maxClientPerPool={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum concurrent tunnel test jobs per client pool. Caps how many client tunnels are tested simultaneously. [Default is 24]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.poolCoverageThreshold={f} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Ratio of tunnels tested vs total pool size that triggers pool coverage heuristics. When fewer than this fraction of tunnels have recent test results, the pool prioritises testing over building. [Default is 0.95]")%></td></tr>

<tr class=config><th>i2p.tunnel.peerSelector.clientCooldownMs={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Cooldown period in milliseconds for client peer selection. Peers selected for a client tunnel are excluded from selection in other client pools for this duration, ensuring peer diversity. [Default is 15000 (15 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.peerSelector.clientStrategy={s} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Client peer selection strategy: 'default' (balanced, preferred), 'reliability' (prioritise proven peers with high tunnel acceptance ratio), or 'diversity' (lower cooldowns, explores broader peer set). Changes apply dynamically. [Default is 'default']")%></td></tr>

<tr class=config><th>i2p.tunnel.lifetime={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Tunnel lifetime in milliseconds. Tunnels expire after this period and are replaced with fresh builds. Lower values increase churn but may improve freshness; higher values reduce build overhead but may accumulate stale tunnels. [Default is 600000 (10 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.startupTime={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Startup suppression period in milliseconds. Warnings and tunnel reduction logic are suppressed during initial router startup to avoid premature decisions. [Default is 300000 (5 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.refreshThrottle={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum interval in milliseconds between LeaseSet publishes. Prevents rapid publish storms during tunnel churn. [Default is 120000 (2 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.leasesetBuildMinInterval={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum interval in milliseconds between LeaseSet object builds. Prevents unnecessary LeaseSet object churn on every tunnel add/remove. [Default is 120000 (2 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.leaseMaxDuration={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum lease duration in milliseconds for published LeaseSets. Lease end dates are capped to this value from now, so peers re-fetch sooner when set shorter than the tunnel lifetime. The gateway still processes messages for the full tunnel lifetime; only the cached LeaseSet on the requesting side expires earlier, triggering a re-fetch of the latest version. Default is auto-computed from i2p.netdb.republishInterval + 60 seconds (6 minutes with standard settings).")%></td></tr>

<tr class=config><th>i2p.tunnel.maxConcurrentBuildsPerDirection={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of concurrent tunnel builds per direction. Higher values accelerate pool recovery but may overload the network. [Default is 6]")%></td></tr>

<tr class=config><th>i2p.tunnel.buildTriesQuantityOverride={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("If exploratory tunnel build success rate falls below 1 in this many attempts, tunnel quantity is reduced. Higher values make quantity reduction less likely. [Default is 12]")%></td></tr>

<tr class=config><th>i2p.tunnel.targetBuffer={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Extra tunnels to maintain beyond the configured pool quantity. A positive value keeps spare tunnels ready so the pool doesn't drain when tunnels expire simultaneously. [Default is 0]")%></td></tr>

<tr class=config><th>i2p.tunnel.goodDeficitThrottle={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum interval in milliseconds between GOOD-tunnel deficit rebuilds for non-critical pools. Prevents rapid build-retry loops when the pool has enough FAILING tunnels to satisfy the numerical target. [Default is 30000 (30 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.minTestPeriod={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum tunnel test period in milliseconds. Tunnel tests always wait at least this long for a reply before timing out. [Default is 45000 (45 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.testJob.maxTestPeriod={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum tunnel test period in milliseconds. Tunnel tests never wait longer than this for a reply, even on high-latency paths. [Default is 50000 (50 seconds)]")%></td></tr>

<tr class=config><th>i2p.tunnel.ghostPeer.timeoutThreshold={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Number of tunnel build timeouts before a peer is marked as a ghost (blacklisted for tunnel participation). Higher values tolerate more transient failures. [Default is 3]")%></td></tr>

<tr class=config><th>i2p.tunnel.ghostPeer.cooldownMs={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Cooldown period in milliseconds before a ghost-marked peer can be considered for tunnel participation again. Shorter values allow faster peer reuse. [Default is 180000 (3 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.ghostPeer.attackCooldownMs={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Cooldown period in milliseconds for ghost-marked peers during attack mode. Longer cooldowns limit exposure to potentially malicious peers during high-stress periods. [Default is 300000 (5 minutes)]")%></td></tr>

<tr class=config><th>i2p.tunnel.requestThrottle.minLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum number of incoming tunnel build requests accepted per peer per clean window. Prevents starving peers during low-traffic periods. [Default is 10]")%></td></tr>

<tr class=config><th>i2p.tunnel.requestThrottle.maxLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of incoming tunnel build requests accepted per peer per clean window. Caps request volume to prevent DoS. [Default is 300]")%></td></tr>

<tr class=config><th>i2p.tunnel.requestThrottle.burst1sThreshold={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Number of tunnel build requests from a single peer in 1 second that triggers an immediate ban. Protects against rapid-fire DoS attacks. [Default is 10]")%></td></tr>

<tr class=config><th>i2p.tunnel.requestThrottle.percentLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Percentage of participating tunnels used to dynamically scale the per-peer request throttle limit. The effective maxLimit is adjusted based on this percentage of current participating tunnel count. [Default is 20]")%></td></tr>

<tr class=config><th>i2p.tunnel.participatingThrottle.minLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum number of participating tunnel build requests accepted per clean window. Ensures a baseline acceptance rate even when the router is under load. [Default is 80 (40 on slow systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.participatingThrottle.maxLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of participating tunnel build requests accepted per clean window. Caps acceptance to prevent overload. [Default is 300 (150 on slow systems)]")%></td></tr>

<tr class=config><th>i2p.tunnel.participatingThrottle.percentLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Percentage of current participating tunnels used to scale the participating throttle limits. Higher values allow more build requests relative to tunnel count. [Default is 10]")%></td></tr>

<tr class=config><th>i2p.tunnel.exploratoryPeer.minNonfailingPct={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum percentage of non-failing peer profiles required before the router broadens exploratory peer selection. When below this threshold, only the most reliable peers are used. [Default is 15]")%></td></tr>

<tr class=config><th>i2p.tunnel.exploratoryPeer.minActivePeers={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum number of active (non-failing) peers required for normal exploratory peer selection. Below this threshold, selection logic becomes more permissive. [Default is 12]")%></td></tr>

<tr class=config><th>i2p.tunnel.exploratoryPeer.minActivePeersStartup={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Minimum number of active peers required during router startup before restricting exploratory peer selection. During early startup the router is more lenient about peer quality. [Default is 6]")%></td></tr>

<tr class=config><th>i2p.streaming.maxSlowStartWindow={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum slow start window size for streaming connections. Caps the congestion window during slow start to prevent overwhelming the network. [Default is 64]")%></td></tr>

<tr class=config><th>i2p.streaming.maxRtt={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum round-trip time in milliseconds for streaming connections. Caps the computed RTT to prevent pathological timeout values after network disturbances. [Default is 60000 (60 seconds)]")%></td></tr>

<tr class=config><th>i2p.streaming.maxRetransmissions={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of packet retransmissions before giving up on a connection. Higher values improve reliability on lossy links but delay failure detection. [Default is 64]")%></td></tr>

<tr class=config><th>i2p.streaming.destinationCooldownMs={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Cooldown period in milliseconds between connection attempts to the same unreachable destination. Prevents hammering unreachable peers. [Default is 60000 (60 seconds)]")%></td></tr>

<tr class=config><th>i2p.streaming.maxPingTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum time in milliseconds to wait for a pong reply when pinging a peer. [Default is 300000 (5 minutes)]")%></td></tr>

<tr class=config><th>i2p.streaming.dropOverLimit={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Number of times to respond to a throttled destination before silently dropping excess packets. Prevents connection storms from overwhelming the router. [Default is 3]")%></td></tr>

<tr class=config><th>i2p.streaming.defaultStreamDelayMax={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Default maximum stream delay in milliseconds when no explicit connect timeout is set. [Default is 10000 (10 seconds)]")%></td></tr>

<tr class=config><th>i2p.streaming.acceptTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum time in milliseconds a received SYN may wait in the accept queue before accept() pulls it. If accept() is delayed (busy hosted server) the connection is refused after this window, so a higher value tolerates brief stalls. [Default is 30000 (30 seconds)]")%></td></tr>

<tr class=config><th>i2p.streaming.maxQueueSize={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of pending connections (SYNs and partial packets) in the server accept queue. When full, new SYNs are dropped (the client retries) rather than reset, so a larger value better absorbs connection bursts at the cost of memory. [Default is 256 (128 on slow systems)]")%></td></tr>

<tr class=config><th>i2p.netdb.republishInterval={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Interval in milliseconds between automated LeaseSet republishes. Higher values reduce netdb traffic but may cause stale lease info. [Default is 300000 (5 minutes)]")%></td></tr>

<tr class=config><th>i2p.netdb.proactiveRepublishThreshold={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Threshold in milliseconds for proactive LeaseSet republish. If a lease is expiring within this window, the LeaseSet is republished immediately rather than waiting for the next periodic cycle. [Default is 180000 (3 minutes)]")%></td></tr>

<tr class=config><th>router.idleTunnelDetectionPeriod={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The period in milliseconds to check for idle tunnels. Tunnels with no traffic for this period may be dropped. [Default is 60000]")%></td></tr>

<tr class=config><th>router.idleTunnelMinMessages={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The minimum number of messages a tunnel must have processed in the detection period to be considered active. Tunnels below this threshold are candidates for removal. [Default is 3]")%></td></tr>

<tr class=config><th>router.idleTunnelScanInterval={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The interval in milliseconds between scans for idle transit tunnels. [Default is 180000]")%></td></tr>

<tr class=config><th>router.updateUnsigned={true|false}</th></tr>
<tr><td><%=intl._t("If you wish to install unsigned (.zip) I2P updates, this should be added to your <code>router.config</code> file unless you have already configured <code>routerconsole.advanced=true</code>, in which case this option is already provisioned. Note: as of I2P+ 0.9.48+, installation of <a href=/configupdate#i2pupdates>unsigned updates</a> is enabled by default.")%></td></tr>

<tr class=config><th>router.updateUnsignedURL={url}</th></tr>
<tr><td><%=intl._t("This setting allows you to configure the update url for the unsigned update feature, if enabled. The url should end with <code>/i2pupdate.zip</code>. Note: do not install unsigned updates unless you trust the source of the update!")%></td></tr>

<tr class=config><th>router.validateRoutersAfter={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting (in minutes) allows you to manually configure how long to wait after startup before RouterInfos in the NetDb are checked for validity, after which point only valid routers will be accepted for inclusion. When the validation occurs, expired RouterInfos and unresponsive peers only accessible via SSU will be removed from the NetDb. [Default is 60 minutes] Note: This setting has no bearing on older routers (older than 0.9.29 by default) which are removed from the NetDb and banned for the router session as soon as a NetDb store is attempted.")%></td></tr>

<tr class=section><th>i2np</th></tr>

<tr class=config><th>i2np.blockMyCountry={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will block direct communication from all routers in your own country and add them to your banlist until your router restarts. [Default is false, or true if router is in hidden mode]")%></td></tr>

<tr class=config><th>router.banlistXG={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will block direct communication from all X tier routers that publish a G congestion cap and are neither reachable or unreachable, adding them to the session banlist. [Default is false]")%></td></tr>

<tr class=config><th>router.banlistLU={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("This setting, when set to true, will block direct communication from all LU tier routers (low bandwidth, unreachable, or firewalled), adding them to the session banlist. [Default is true]")%></td></tr>

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

<tr class=config><th>eepget.connectTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The connect timeout in milliseconds for eepget HTTP requests (time to wait for initial response headers). Increase for high-latency remote access scenarios. [Default is 90000 (90s)]")%></td></tr>

<tr class=config><th>eepget.inactivityTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The inactivity timeout in milliseconds for eepget transfers (max idle time during data phase). Increase for slow connections experiencing stalls. [Default is 300000 (5min)]")%></td></tr>

<tr class=config><th>eepget.maxCompleteFails={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum number of consecutive zero-data transfer failures before eepget gives up, even if retries remain. Increase if transfers fail without receiving any data. [Default is 20]")%></td></tr>

<tr class=config><th>eepget.defaultRetries={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Default number of retries for eepget when the <code>-n</code> flag is not specified. [Default is 10]")%></td></tr>

<tr class=config><th>i2p.streaming.disconnectTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The TIME-WAIT duration in milliseconds after a streaming connection disconnects, during which late packets are still acknowledged. Increase for high-latency paths where final ACKs may be delayed. [Default is 300000 (5min)]")%></td></tr>

<tr class=config><th>i2p.streaming.maxConnectTimeout={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The upper bound clamp on streaming connect timeout. Prevents per-connection timeouts from exceeding this ceiling regardless of per-tunnel configuration. Increase for very high-latency remote access. [Default is 120000 (2min)]")%></td></tr>

<tr class=config><th>i2p.streaming.initialWindowSize={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Initial congestion window size (in packets) for new streaming connections. A larger window starts connections faster but may cause more retransmissions on congested paths. [Default is 8]")%></td></tr>

<tr class=config><th>i2p.streaming.maxWindowSize={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("Maximum congestion window size (in packets) for streaming connections. Caps the maximum throughput of a single stream. [Default is 128]")%></td></tr>

<tr class=config><th>i2p.streaming.minResendDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The minimum retransmission delay in milliseconds. Packets will not be resent faster than this interval. Lower values allow faster recovery on lossy connections. [Default is 100ms]")%></td></tr>

<tr class=config><th>i2p.streaming.maxResendDelay={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The maximum retransmission delay in milliseconds. Retransmit interval will not exceed this value regardless of backoff. Increase to allow longer between retries on congested paths. [Default is 30000 (30s)]")%></td></tr>

<tr class=config><th>i2p.streaming.maxRTO={n} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("The maximum Retransmission TimeOut in milliseconds after exponential backoff (doubling). Caps how long the sender waits before attempting retransmission during severe congestion. [Default is 30000 (30s)]")%></td></tr>

<tr class=config><th>i2p.vmCommSystem={true|false}</th></tr>
<tr><td><%=intl._t("When set to true, I2P runs without network connectivity, which is helpful if you are constantly restarting the router to test code updates as this prevents network disruption.")%></td></tr>

<tr class=config><th>i2cp.disableLoopback={true|false} <span class=plus>I2P+</span></th></tr>
<tr><td><%=intl._t("When set to true, disables local-local loopback delivery and forces all traffic through tunnels (outbound tunnel → network → inbound tunnel → local destination). Useful for testing tunnel routing behavior when the source and destination are on the same router. [Disabled by default]")%></td></tr>

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