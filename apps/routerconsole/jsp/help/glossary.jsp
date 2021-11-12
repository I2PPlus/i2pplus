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
<title>Glossary - I2P+</title>
<%@include file="../css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("Glossary")%></h1>
<div class="main" id="help">
<div class="confignav">
<span class="tab"><a href="/help/configuration">Configuration</a></span>
<span class="tab"><a href="/help/advancedsettings">Advanced Settings</a></span>
<span class="tab"><a href="/help/ui">User Interface</a></span>
<span class="tab"><a href="/help/reseed">Reseeding</a></span>
<span class="tab"><a href="/help/tunnelfilter">Tunnel Filtering</a></span>
<span class="tab"><a href="/help/faq">FAQ</a></span>
<span class="tab"><a href="/help/newusers">New User Guide</a></span>
<span class="tab"><a href="/help/webhosting">Web Hosting</a></span>
<span class="tab"><a href="/help/hostnameregistration">Hostname Registration</a></span>
<span class="tab"><a href="/help/troubleshoot">Troubleshoot</a></span>
<span class="tab2">Glossary</span>
<span class="tab"><a href="/help/legal">Legal</a></span>
<span class="tab"><a href="/help/changelog">Change Log</a></span>
</div>
<div id="glossary">
<h2><%=intl._t("I2P Terminology")%></h2>
<div id="terms">

<p><b>Addressbook</b><br>
An I2P web application that manages service destinations for the network, mapping B64 hashes to human-readable names. Destinations may be added manually, or via subscriptions to hosts.txt files. Also referred to as SusiDNS. For more information, see the <a href="https://geti2p.net/en/docs/naming" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>Base32 (B32) / Base64 (B64)</b><br>
A <a href="https://en.wikipedia.org/wiki/Base32" class="sitelink external" target="_blank" rel="noreferrer">Base32</a> link (which always ends with .b32.i2p) is a hash of a <a href="https://en.wikipedia.org/wiki/Base64" class="sitelink external" target="_blank" rel="noreferrer">Base64</a> destination. For services on the network, the Base64 hash is often mapped to an .i2p domain in the addressbook.
</p>

<p><b>BOB</b><br>
BOB (Basic Open Bridge) is a simple application-to-router protocol, largely superseded by <i>SAM</i>. For more information, see the <a href="https://geti2p.net/en/docs/api/bob" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>Clearnet</b><br>
Clearnet normally refers to the publicly accessible internet, the opposite of the darknet that typically describes the encrypted, anonymous services built on I2P, Tor etc. For more information, see the <a href="https://en.wikipedia.org/wiki/Clearnet_(networking)" class="sitelink external" target="_blank" rel="noreferrer">Wikipedia article</a>.
</p>

<p><b>Destination</b><br>
The unique cryptographic identity of the inbound endpoint of a tunnel providing access to a service on the I2P network, represented as a Base32 or Base64 hash of the public key. Equivalent to an IP address and port.
</p>

<p><b>Eepget</b><br>
A command-line application supplied with I2P that is used to request URL resources, similar to <a href="https://www.gnu.org/software/wget/" class="sitelink external" target="_blank" rel="noreferrer">wget</a> or <a href="https://curl.haxx.se/" class="sitelink external" target="_blank" rel="noreferrer">curl</a>. Eepget is also used internally by the router, for example to reseed from remote hosts. By default, eepget uses the HTTP proxy on <code>127.0.0.1:4444</code>.
</p>

<p><b>Eephead</b><br>
A command-line application supplied with I2P+ (Linux/MacOS) that is used to perform a <code>head</code> request on a specified http resource on the network, returning various response headers from the remote server. By default, eephead uses the HTTP proxy on <code>127.0.0.1:4444</code>.
</p>

<p><b>Eepsite</b><br>
A website hosted on the I2P network. By default, a <a href="https://www.eclipse.org/jetty/about.html" class="sitelink external" target="_blank" rel="noreferrer">Jetty webserver</a> is configured and running on <a href="http://127.0.0.1:7658/" target="_blank" rel="noreferrer">http://127.0.0.1:7658/</a>.
</p>

<p><b>Exploratory Tunnel</b><br>
A tunnel used by the router for communicating with other routers to perform various "housekeeping" functions such as learning and publishing <i>leasesets</i>, testing client tunnels, and learning about and validating other routers in the network. These tunnels are normally low-bandwidth.
</p>

<p><b>Floodfill</b><br>
A router on the network tasked with providing and receiving information about other routers on the network. The current minimum share bandwidth for a router to be considered as capable of performing floodfill duties is 128 KBytes/sec, though it's possible to override this requirement and force a router to be a floodfill on the <a href="/configadvanced">Advanced Configuration page</a>. For more information, see the <a href="https://geti2p.net/en/docs/how/network-database#floodfill" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>Garlic Routing</b><br>
A variant of onion routing that encrypts multiple messages together to make it more difficult for attackers to perform traffic analysis and to increase the speed of data transfer. For more information, see the <a href="https://geti2p.net/en/docs/how/garlic-routing" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>Hidden Mode</b><br>
A router configured to run in Hidden Mode will not publish its details to the <i>Network Database</i> and will not participate in routing traffic for other routers. Routers operating in countries designated as strict and routers manually configured run in Hidden Mode.
</p>

<p><b>I2CP</b><br>
The I2P Client Protocol (I2CP) enables external applications (clients) to communicate with I2P over single TCP socket, by default using port 7654. For more information, see the <a href="https://geti2p.net/en/docs/protocol/i2cp" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>I2NP</b><br>
The I2P Network Protocol (I2NP) manages the routing and mixing of messages between routers, in addition to the transport selection (where more than one is supported) when communicating with a peer. For more information, see the <a href="https://geti2p.net/en/docs/protocol/i2np" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>I2P+</b><br>
A soft fork of the Java I2P software which retains full compatibility, providing an enhanced user interface and improvements to network performance. See <a href="http://skank.i2p/" class="sitelink" target="_blank" rel="noreferrer">http://skank.i2p/</a> or <a href="https://i2pplus.github.io" class="sitelink external" target="_blank" rel="noreferrer">https://i2pplus.github.io</a>for more information.
</p>

<p><b>I2PBote (plugin)</b><br>
An I2P plugin that provides serverless, end-to-end encrypted e-mail within the I2P network. For more information, see <a href="http://bote.i2p/" class="sitelink" target="_blank" rel="noreferrer">http://bote.i2p/</a>. Source code is available on <a href="https://github.com/i2p/i2p.i2p-bote" class="sitelink external" target="_blank" rel="noreferrer">github</a>.
</p>

<p><b>i2pd</b><br>
An alternative implementation of an I2P router coded in C++. For more information, see <a href="https://i2pd.website/" class="sitelink external" target="_blank" rel="noreferrer">https://i2pd.website/</a> Source code is available on <a href="https://github.com/PurpleI2P/i2pd" class="sitelink external" target="_blank" rel="noreferrer">github</a>.
</p>

<p><b>I2PSnark</b><br>
A fork of the <a href="http://www.klomp.org/snark/" class="sitelink external" target="_blank" rel="noreferrer">Snark BitTorrent client</a> refactored for use on the I2P network. Usually supplied as part of the default suite of applications with Java I2P, and also available as a <a href="http://skank.i2p/#download" class="sitelink" target="_blank" rel="noreferrer">standalone Java application</a>.
</p>

<p><b>Introducer</b><br>
A router that facilitates the connection to the network of another router that is behind a firewall.
</p>

<p><b>Jump Service</b><br>
A simple CGI application that takes a hostname as a parameter and returns a 301 redirect to the proper URL with a <code>?i2paddresshelper=key</code> string appended. The HTTP proxy will interpret the appended string and use the key as the actual destination. In addition, the proxy will cache the key so the address helper is not necessary until restart, and offer the option to add the resolved address to the addressbook.
</p>

<p><b>Laptop Mode</b><br>
An <a href="/confignet#ipchange">optional configuration</a> that automatically changes your <a href="/netdb?r=.">router identity</a> and <a href="/confignet#udpconfig">external port</a> when the public ip address your system is on changes. This can prevent an adversary from correlating your router identity and ip address in scenarios where your ip address may change frequently, for example when you're running I2P on a laptop from multiple locations.
</p>

<p><b>Lease</b><br>
The information that defines the authorization for a particular tunnel to receive messages targeting a <i>Destination</i>.
</p>

<p><b>LeaseSet</b><br>
A group of tunnel entry points (leases) for a <i>destination</i>. Note: A 0-hop server tunnel will only have one lease, regardless of the number of tunnels configured.
</p>

<p><b>Monotone / mtn</b><br>
The distributed version control system (DVCS) perviously used to maintain I2P's source code, now superseded by git.
</p>

<p><b>Multihoming</b><br>
Services may be hosted on multiple routers simultaneously, by sharing the same private key for the destination. Multihoming can further enhance the security and stability of a server by providing redundancy; if a server is multihomed, when a server in the pool goes offline, the provided service will remain available, making it more difficult to correlate server downtime with a router going offline.
</p>

<p><b>Network Database</b><br>
A distributed database containing router contact information (RouterInfos) and destination contact information (LeaseSets). A single router maintains its own (partial) database for communicating with other routers on the network; no single router will maintain a list of all routers. The RouterInfos are stored on disk and read into memory when the router starts, or when new RouterInfos are acquired. Also referred to as the <i>NetDb</i>. For more information, see the <a href="https://geti2p.net/en/docs/how/network-database" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>NTCP / NTCP2</b><br>
NTCP (NIO-based TCP) and NTCP2 are TCP-based network transports which use Java's NIO (New I/O) TCP implementation to deliver I2NP messages between routers on the network. NTCP2 improves the resistance of NTCP to various attacks and automated traffic identification methods. For more information, see the <a href="https://geti2p.net/en/docs/transport/ntcp" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>Orchid (plugin)</b><br>
A java implementation of a Tor client, available as an I2P plugin. Can be used as the default <i>outproxy</i> if configured in the relevant SOCKS or HTTP client tunnel in the <i>Tunnel Manager</i>. Available from <a href="http://stats.i2p/i2p/plugins" class="sitelink">http://stats.i2p/i2p/plugins</a>. Source code is available on <a href="https://github.com/i2p/i2p.plugins.orchid" class="sitelink external" target="_blank" rel="noreferrer">github</a>.
</p>

<p><b>Outproxy</b><br>
A service on the network that provides a proxy connection to the <i>clearnet</i>. By default, I2P has an HTTP outproxy configured for web browsing on <code>127.0.0.1:4444</code>.
</p>

<p><b>Participation</b><br>
The act of contributing to the network by permitting other routers to build tunnels using your router in a tunnel chain. At least 12KB/s upstream bandwidth (network share) is required to be allocated in order to enable participation. Note that firewalled routers will be limited in how much they contribute to participation, and a router designated as Hidden will not participate at all.
</p>

<p><b>Reseeding</b><br>
The process of acquiring router identities, normally via <i>clearnet</i> servers, to ensure your router can communicate and build tunnels with other routers on the network. When a router has no router identities for other peers (usually immediately after installation), the process of reseeding is also referred to as <i>bootstrapping</i>.
</p>

<p><b>Router</b><br>
The core I2P software, which routes encrypted packets on the I2P network. All routers by default participate in the network except routers running in countries considered hostile (see <i>Strict Countries</i>), which both helps the network and provides cover traffic for any clients or servers connecting to the I2P network through the router.
</p>

<p><b>Router Identity</b><br>
Information defining the unique identity of a router on the network which includes its IP address (or the IP addresses of intermediate introducers) and listening port, public signing and encryption keys. Router identities correlate to peers in the NetDB. Also referred to as a <i>RouterInfo</i>.
</p>

<p><b>SAM</b><br>
SAM (Simple Anonymous Messaging) is a protocol which allows a client application written in any programming language to communicate over I2P, by using a socket-based interface to the I2P router. For more information, see the <a href="https://geti2p.net/en/docs/api/samv3" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>Shared Client</b><br>
In order to use a single set of tunnels for multiple client services, clients can be configured from the <i>Tunnel Manager</i> to operate in <i>Shared Client</i> mode. This mode conserves resources and blends the traffic from multiple clients into a single set of tunnels, making traffic analysis harder. Clients configured as Shared Clients share the same tunnel options.
</p>

<p><b>SSU</b><br>
SSU (Secure Semi-reliable UDP) is an I2P network transport providing encrypted, connection-oriented, point-to-point connections, in addition to IP address detection and NAT traversal services. For more information, see the <a href="https://geti2p.net/en/docs/transport/ssu" class="sitelink external" target="_blank" rel="noreferrer">online documentation</a>.
</p>

<p><b>Strict Countries</b><br>
A list of countries derived from the <a href="https://freedomhouse.org/report/countries-world-freedom-2019" class="sitelink external" target="_blank" rel="noreferrer">World Freedom Report</a> with a poor civil liberties reputation. This list is used to place routers running in the specified countries in <i>Hidden Mode</i> to provide enhanced security for users. The following countries are designated as strict: Afghanistan, Azerbaijan, Bahrain, Belarus, Brunei, Burundi, Cameroon, Central African Republic, Chad, China, Cuba, Democratic Republic of the Congo, Egypt, Equatorial Guinea, Eritrea, Ethiopia, Iran, Kazakhstan, Laos, Libya, Myanmar, North Korea, Palestinian Territories, Rwanda, Saudi Arabia, Somalia, South Sudan, Sudan, Eswatini (Swaziland), Syria, Tajikistan, Thailand, Turkey, Turkmenistan, Venezuela, United Arab Emirates, Uzbekistan, Western Sahara, Yemen.
</p>

<p><b>Tunnel</b><br>
A <a href="https://geti2p.net/en/docs/tunnels/unidirectional" class="sitelink external" target="_blank" rel="noreferrer">unidirectional</a> encrypted communication pathway between a client or server on the I2P network. Similar to a circuit in Tor, except that Tor circuits are bidirectional.
</p>

<p><b>Tunnel Endpoint</b><br>
The last router in a tunnel, which may be the <i>Outbound Endpoint</i>, where the client's tunnels meet the <i>Inbound Gateway</i> of the destination server's tunnels, or the <i>Inbound Endpoint</i> which is the final router in the chain that connects to the destination server.
</p>

<p><b>Tunnel Gateway</b><br>
The first router in a tunnel. For inbound tunnels, this is the one mentioned in the <i>LeaseSet</i> published in the <i>Network Database</i>. For outbound tunnels, the gateway is the originating router.
</p>

<p><b>Tunnel Manager</b><br>
A web application supplied with the router that permits creation and configuration of client and server tunnels. Also referred to as the <i>Hidden Services Manager</i>.
</p>

<p><b>Tunnel Participant</b><br>
A router in a tunnel not designated as a gateway or endpoint.
</p>

<p><b>ZzzOT (plugin)</b><br>
A Java I2P plugin implementation of a BitTorrent <a href="https://en.wikipedia.org/wiki/Opentracker" class="sitelink external" target="_blank" rel="noreferrer">Open Tracker</a>. Available from <a href="http://stats.i2p/i2p/plugins" class="sitelink">http://stats.i2p/i2p/plugins</a>. Source code is available on <a href="https://github.com/i2p/i2p.plugins.zzzot" class="sitelink external" target="_blank" rel="noreferrer">github</a>.
</p>

</div>
</div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>