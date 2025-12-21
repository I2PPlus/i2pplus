<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="../head.jsi"%>
<%  String pageTitlePrefix = ctx.getProperty("routerconsole.pageTitlePrefix");
    pageTitlePrefix = (pageTitlePrefix != null) ? pageTitlePrefix + ' ' : "";
%>
<title><%=pageTitlePrefix%> <%=intl._t("I2PControl JSON-RPC API")%> - I2P+</title>
</head>
<body>
<%@include file="../sidebar.jsi"%>
<h1 class=hlp><%=intl._t("I2PControl JSON-RPC API")%></h1>
<div class=main id=help>

<div class=confignav>
<span class=tab><a href="/help/configuration" title="<%=intl._t("Configuration")%>"><%=intl._t("Configuration")%></a></span>
<span class=tab><a href="/help/advancedsettings" title="<%=intl._t("Advanced Settings")%>"><%=intl._t("Advanced Settings")%></a></span>
<span class=tab><a href="/help/ui" title="<%=intl._t("User Interface")%>"><%=intl._t("User Interface")%></a></span>
<span class=tab><a href="/help/reseed" title="<%=intl._t("Reseeding")%>"><%=intl._t("Reseeding")%></a></span>
<span class=tab2 id=json><span><%=intl._t("JSONRPC")%></span></span>
<span class=tab><a href="/help/faq" title="<%=intl._t("Frequently Asked Questions")%>"><%=intl._t("FAQ")%></a></span>
<span class=tab><a href="/help/newusers" title="<%=intl._t("New User Guide")%>"><%=intl._t("New User Guide")%></a></span>
<span class=tab><a href="/help/webhosting" title="<%=intl._t("Web Hosting")%>"><%=intl._t("Web Hosting")%></a></span>
<span class=tab><a href="/help/hostnameregistration" title="<%=intl._t("Hostname Registration")%>"><%=intl._t("Hostname Registration")%></a></span>
<span class=tab><a href="/help/troubleshoot" title="<%=intl._t("Troubleshoot")%>"><%=intl._t("Troubleshoot")%></a></span>
<span class=tab><a href="/help/glossary" title="<%=intl._t("Glossary")%>"><%=intl._t("Glossary")%></a></span>
<span class=tab><a href="/help/legal" title="<%=intl._t("Legal")%>"><%=intl._t("Legal")%></a></span>
<span class=tab><a href="/help/changelog"><%=intl._t("Change Log")%></a></span>
</div>

<div id=jsonrpchelp>
<h2 id="jsonrpc"><%=intl._t("I2PControl JSON-RPC API")%></h2>

<p>
<%=intl._t("I2PControl provides a JSON-RPC 2.0 interface for remote control and monitoring of your I2P router. This allows external applications to programmatically manage I2P settings, retrieve status information, and control router operations through standardized HTTP requests.")%>
</p>

<p>
<%=intl._t("I2PControl is a JSON-RPC 2.0 interface for remote control and monitoring of your I2P router. It listens on {0} by default when enabled for security reasons and must be enabled through the {1}.", "https://localhost:7650", "<a href=\"/configwebapps\">" + intl._t("Web Apps configuration page") + "</a>")%>
</p>

<h3><%=intl._t("Authentication")%></h3>

<p>
<%=intl._t("All API operations (except for authentication itself) require a valid authentication token. To obtain a token, you must first authenticate with your configured password:")%>
</p>
<p>
<%=intl._t("The returned token must be included in either the 'Token' parameter or as an 'X-Auth-Token' HTTP header in all subsequent API calls.")%>
</p>
<p><%=intl._t("API Version: 1. Clients must specify API version 1 in authentication requests for compatibility.")%></p>

<h3><%=intl._t("API Methods")%></h3>

<h4>Authenticate</h4>
<p>
<%=intl._t("Authenticates with the I2PControl service and returns an authentication token for subsequent API calls.")%>
</p>
<ul>
<li><b><%=intl._t("Parameters:")%></b> <%=intl._t("Password (string), API (integer, must be 1)")%></li>
<li><b><%=intl._t("Returns:")%></b> <%=intl._t("Token (string), API (integer)")%></li>
<li><b><%=intl._t("Authentication:")%></b> <%=intl._t("None (this is the unauthenticated method)")%></li>
</ul>

<h4>Echo</h4>
<p>
<%=intl._t("A simple test method that returns the provided string. Useful for testing API connectivity and authentication.")%>
</p>
<ul>
<li><b><%=intl._t("Parameters:")%></b> <%=intl._t("Echo (string)")%></li>
<li><b><%=intl._t("Returns:")%></b> <%=intl._t("Result (string - echoed input)")%></li>
<li><b><%=intl._t("Authentication:")%></b> <%=intl._t("Required")%></li>
</ul>

<h4>RouterInfo</h4>
<p>
<%=intl._t("Retrieves read-only status information about the I2P router. Each parameter is optional - include only the metrics you need.")%>
</p>
<ul>
<li><b><%=intl._t("Parameters:")%></b> <%=intl._t("Any combination of:")%>
<ul>
<li><code>i2p.router.version</code> - <%=intl._t("Router version")%></li>
<li><code>i2p.router.uptime</code> - <%=intl._t("Router uptime in milliseconds")%></li>
<li><code>i2p.router.status</code> - <%=intl._t("Current router status")%></li>
<li><code>i2p.router.net.status</code> - <%=intl._t("Network status (0-14)")%></li>
<li><code>i2p.router.net.bw.inbound.1s</code> - <%=intl._t("1-second average inbound bandwidth (Bps)")%></li>
<li><code>i2p.router.net.bw.outbound.1s</code> - <%=intl._t("1-second average outbound bandwidth (Bps)")%></li>
<li><code>i2p.router.net.bw.inbound.15s</code> - <%=intl._t("15-second average inbound bandwidth (Bps)")%></li>
<li><code>i2p.router.net.bw.outbound.15s</code> - <%=intl._t("15-second average outbound bandwidth (Bps)")%></li>
<li><code>i2p.router.net.tunnels.participating</code> - <%=intl._t("Number of participating tunnels")%></li>
<li><code>i2p.router.netdb.knownpeers</code> - <%=intl._t("Number of known peers")%></li>
<li><code>i2p.router.netdb.activepeers</code> - <%=intl._t("Number of active peers")%></li>
<li><code>i2p.router.netdb.fastpeers</code> - <%=intl._t("Number of fast peers")%></li>
<li><code>i2p.router.netdb.highcapacitypeers</code> - <%=intl._t("Number of high-capacity peers")%></li>
<li><code>i2p.router.netdb.isreseeding</code> - <%=intl._t("Whether reseed is in progress")%></li>
</ul>
</li>
<li><b><%=intl._t("Returns:")%></b> <%=intl._t("Requested metrics with their current values")%></li>
<li><b><%=intl._t("Authentication:")%></b> <%=intl._t("Required")%></li>
</ul>

<h4>RouterManager</h4>
<p>
<%=intl._t("Controls router lifecycle and management operations. Each operation is optional and executes asynchronously.")%>
</p>
<ul>
<li><b><%=intl._t("Parameters:")%></b> <%=intl._t("Any combination of:")%>
<ul>
<li><code>Graceful</code> - <%=intl._t("Graceful restart/shutdown")%></li>
<li><code>Shutdown</code> - <%=intl._t("Immediate hard shutdown")%></li>
<li><code>Restart</code> - <%=intl._t("Immediate hard restart")%></li>
<li><code>ShutdownGraceful</code> - <%=intl._t("Graceful shutdown (waits for tunnels)")%></li>
<li><code>RestartGraceful</code> - <%=intl._t("Graceful restart (waits for tunnels)")%></li>
<li><code>Reseed</code> - <%=intl._t("Trigger router reseeding")%></li>
<li><code>FindUpdates</code> - <%=intl._t("Check for available updates")%></li>
<li><code>Update</code> - <%=intl._t("Install available updates")%></li>
</ul>
</li>
<li><b><%=intl._t("Returns:")%></b> <%=intl._t("Confirmation of operation (null for most operations, result status for FindUpdates/Update)")%></li>
<li><b><%=intl._t("Authentication:")%></b> <%=intl._t("Required")%></li>
</ul>

<h4>NetworkSetting</h4>
<p>
<%=intl._t("Manages router network configuration. Parameters can be used to either set values (if provided) or retrieve current values (if null/omitted).")%>
</p>
<ul>
<li><b><%=intl._t("NTCP Transport Settings:")%></b>
<ul>
<li><code>i2p.router.net.ntcp.port</code> - <%=intl._t("NTCP port (1-65535)")%></li>
<li><code>i2p.router.net.ntcp.hostname</code> - <%=intl._t("NTCP hostname")%></li>
<li><code>i2p.router.net.ntcp.autoip</code> - <%=intl._t("Auto IP setting")%> ("always", "true", "false")</li>
</ul>
</li>
<li><b><%=intl._t("SSU Transport Settings:")%></b>
<ul>
<li><code>i2p.router.net.ssu.port</code> - <%=intl._t("SSU port (1-65535)")%></li>
<li><code>i2p.router.net.ssu.hostname</code> - <%=intl._t("SSU hostname")%></li>
<li><code>i2p.router.net.ssu.autoip</code> - <%=intl._t("IP source detection")%> ("ssu", "local,ssu", "upnp,ssu", "local,upnp,ssu")</li>
<li><code>i2p.router.net.ssu.detectedip</code> - <%=intl._t("Read-only detected IP address")%></li>
</ul>
</li>
<li><b><%=intl._t("Network Settings:")%></b>
<ul>
<li><code>i2p.router.net.upnp</code> - <%=intl._t("UPnP enabled/disabled")%></li>
<li><code>i2p.router.net.laptopmode</code> - <%=intl._t("Laptop mode setting")%></li>
</ul>
</li>
<li><b><%=intl._t("Bandwidth Settings:")%></b>
<ul>
<li><code>i2p.router.net.bw.share</code> - <%=intl._t("Bandwidth share percentage (0-100)")%></li>
<li><code>i2p.router.net.bw.in</code> - <%=intl._t("Inbound bandwidth limit (Bps)")%></li>
<li><code>i2p.router.net.bw.out</code> - <%=intl._t("Outbound bandwidth limit (Bps)")%></li>
</ul>
</li>
<li><b><%=intl._t("Returns:")%></b> <%=intl._t("SettingsSaved (boolean), RestartNeeded (boolean), plus current values for queried settings")%></li>
<li><b><%=intl._t("Authentication:")%></b> <%=intl._t("Required")%></li>
</ul>

<h4>I2PControl</h4>
<p>
<%=intl._t("Manages I2PControl service settings including password, port, and restart configuration.")%>
</p>
<ul>
<li><b><%=intl._t("Parameters:")%></b> <%=intl._t("Any combination of:")%>
<ul>
<li><code>i2pcontrol.address</code> - <%=intl._t("Listen address (string, e.g., 127.0.0.1)")%></li>
<li><code>i2pcontrol.password</code> - <%=intl._t("New password (string)")%></li>
<li><code>i2pcontrol.port</code> - <%=intl._t("Listen port (integer, 1-65535)")%></li>
</ul>
</li>
<li><b><%=intl._t("Returns:")%></b> <%=intl._t("SettingsSaved (boolean), RestartNeeded (boolean), current values for requested settings")%></li>
<li><b><%=intl._t("Authentication:")%></b> <%=intl._t("Required")%></li>
</ul>

<h4>AdvancedSettings</h4>
<p>
<%=intl._t("Access and modify advanced I2P router configuration settings.")%>
</p>
<ul>
<li><b><%=intl._t("Parameters:")%></b> <%=intl._t("Key-value pairs of advanced settings")%></li>
<li><b><%=intl._t("Returns:")%></b> <%=intl._t("Updated settings values")%></li>
<li><b><%=intl._t("Authentication:")%></b> <%=intl._t("Required")%></li>
</ul>

<h4>GetRate</h4>
<p>
<%=intl._t("Retrieves statistical rate data from router for monitoring and analysis. Useful for creating custom graphs or monitoring applications.")%>
</p>
<ul>
<li><b><%=intl._t("Parameters:")%></b>
<ul>
<li><code>Stat</code> - <%=intl._t("Statistical metric name (string)")%></li>
<li><code>Period</code> - <%=intl._t("Time period in milliseconds (integer)")%></li>
</ul>
</li>
<li><b><%=intl._t("Returns:")%></b> <%=intl._t("Result (number - average value for specified period)")%></li>
<li><b><%=intl._t("Authentication:")%></b> <%=intl._t("Required")%></li>
</ul>
</li>
<li><b>Returns:</b> Result (number - average value for the specified period)</li>
<li><b>Authentication:</b> Required</li>
</ul>

<h3><%=intl._t("Network Status Codes")%></h3>

<p><%=intl._t("The {0} field returns an integer value representing the current network connectivity status:", "<code>i2p.router.net.status</code>")%></p>

<ul>
<li><b>0:</b> <%=intl._t("OK - Full connectivity")%></li>
<li><b>1:</b> <%=intl._t("TESTING - Still establishing connectivity")%></li>
<li><b>2:</b> <%=intl._t("FIREWALLED - Behind restrictive firewall")%></li>
<li><b>3:</b> <%=intl._t("HIDDEN - Router in hidden mode")%></li>
<li><b>4:</b> <%=intl._t("WARN_FIREWALLED_AND_FAST - Firewalled but fast peer")%></li>
<li><b>5:</b> <%=intl._t("WARN_FIREWALLED_AND_FLOODFILL - Firewalled but floodfill")%></li>
<li><b>6:</b> <%=intl._t("WARN_FIREWALLED_WITH_INBOUND_TCP - Firewalled with inbound TCP")%></li>
<li><b>7:</b> <%=intl._t("WARN_FIREWALLED_WITH_UDP_DISABLED - Firewalled with UDP disabled")%></li>
<li><b>8:</b> <%=intl._t("ERROR_I2CP - I2CP error")%></li>
<li><b>9:</b> <%=intl._t("ERROR_CLOCK_SKEW - System clock skew detected")%></li>
<li><b>10:</b> <%=intl._t("ERROR_PRIVATE_TCP_ADDRESS - Private TCP address configured")%></li>
<li><b>11:</b> <%=intl._t("ERROR_SYMMETRIC_NAT - Symmetric NAT detected")%></li>
<li><b>12:</b> <%=intl._t("ERROR_UDP_PORT_IN_USE - UDP port already in use")%></li>
<li><b>13:</b> <%=intl._t("ERROR_NO_ACTIVE_PEERS_CHECK_CONNECTION_AND_FIREWALL - No active peers")%></li>
<li><b>14:</b> <%=intl._t("ERROR_UDP_DISABLED_AND_TCP_UNSET - UDP disabled, TCP not configured")%></li>
</ul>

<h3><%=intl._t("Error Codes")%></h3>

<p><%=intl._t("In addition to standard JSON-RPC 2.0 error codes, I2PControl provides specific error codes:")%></p>

<ul>
<li><b>-32700:</b> <%=intl._t("JSON parse error")%></li>
<li><b>-32600:</b> <%=intl._t("Invalid request")%></li>
<li><b>-32601:</b> <%=intl._t("Method not found")%></li>
<li><b>-32602:</b> <%=intl._t("Invalid parameters")%></li>
<li><b>-32603:</b> <%=intl._t("Internal error")%></li>
<li><b>-32001:</b> <%=intl._t("Invalid password provided")%></li>
<li><b>-32002:</b> <%=intl._t("No authentication token presented")%></li>
<li><b>-32003:</b> <%=intl._t("Authentication token doesn't exist")%></li>
<li><b>-32004:</b> <%=intl._t("The provided authentication token was expired and will be removed")%></li>
<li><b>-32005:</b> <%=intl._t("The version of the I2PControl API used wasn't specified, but is required to be specified")%></li>
<li><b>-32006:</b> <%=intl._t("The version of the I2PControl API specified is not supported by I2PControl")%></li>
</ul>

<h3><%=intl._t("Rate Statistics")%></h3>

<p><%=intl._t("For a complete list of available rate statistics, see the {0} documentation in the router console or refer to the {1} online documentation. Common rate statistics include:")%></p>

<ul>
<li><code>bw.recvRate</code> - <%=intl._t("Bandwidth receive rate")%></li>
<li><code>bw.sendRate</code> - <%=intl._t("Bandwidth send rate")%></li>
<li><code>router.activePeers</code> - <%=intl._t("Active peer count")%></li>
<li><code>router.knownPeers</code> - <%=intl._t("Known peer count")%></li>
<li><code>tunnel.testSuccessTime</code> - <%=intl._t("Tunnel test success time")%></li>
</ul>
<p><%=intl._t("See the {0} documentation in the router console or refer to the {1} online documentation.", "<a href=\"/configstats\">" + intl._t("Rate Statistics") + "</a>", "<a href=\"http://geti2p.net/en/misc/ratestats\" target=\"_blank\">" + intl._t("Rate Statistics Documentation") + "</a>")%></p>

<h3><%=intl._t("Example Usage")%></h3>

<p><b><%=intl._t("Get Router Version and Uptime:")%></b></p>
<pre><code>{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "RouterInfo",
  "params": {
    "Token": "your_auth_token",
    "i2p.router.version": null,
    "i2p.router.uptime": null
  }
</code></pre>

<p><b><%=intl._t("Get Current Bandwidth Usage:")%></b></p>
<pre><code>{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "RouterInfo",
  "params": {
    "Token": "your_auth_token",
    "i2p.router.net.bw.inbound.1s": null,
    "i2p.router.net.bw.outbound.1s": null
  }
</code></pre>

<p><b><%=intl._t("Change Bandwidth Limits:")%></b></p>
<pre><code>{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "NetworkSetting",
  "params": {
    "Token": "your_auth_token",
    "i2p.router.net.bw.share": "80",
    "i2p.router.net.bw.in": "256000",
    "i2p.router.net.bw.out": "128000"
  }
</code></pre>

<p><b><%=intl._t("Graceful Restart:")%></b></p>
<pre><code>{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "RouterManager",
  "params": {
    "Token": "your_auth_token",
    "RestartGraceful": null
  }
</code></pre>

<h3><%=intl._t("Security Considerations")%></h3>

<ul>
<li><%=intl._t("Always change the default API password from {0} to a strong, unique password", "<code>itoopie</code>")%></li>
<li><%=intl._t("Consider binding I2PControl to localhost only if not accessed remotely")%></li>
<li><%=intl._t("Use HTTPS when accessing I2PControl over untrusted networks")%></li>
<li><%=intl._t("Tokens may expire - implement re-authentication logic in your applications")%></li>
<li><%=intl._t("Monitor I2PControl access logs for unauthorized usage")%></li>
<li><%=intl._t("Be cautious with router management operations - they can affect network connectivity")%></li>
</ul>

<h3><%=intl._t("Integration Examples")%></h3>

<p><%=intl._t("The I2PControl API can be integrated with:")%></p>

<ul>
<li><b><%=intl._t("Monitoring systems:")%></b> <%=intl._t("Create custom dashboards for router performance")%></li>
<li><b><%=intl._t("Automation scripts:")%></b> <%=intl._t("Schedule restarts or configuration changes")%></li>
<li><b><%=intl._t("Mobile applications:")%></b> <%=intl._t("Monitor and manage your router remotely")%></li>
<li><b><%=intl._t("Network management tools:")%></b> <%=intl._t("Integrate I2P status into broader network monitoring")%></li>
<li><b><%=intl._t("Alerting systems:")%></b> <%=intl._t("Send notifications based on router status changes")%></li>
</ul>

</div>
</div>
</body>
</html>