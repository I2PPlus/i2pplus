<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<html>
<head>
<title>Frequently Asked Questions - I2P+</title>
<%@include file="../css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp">Frequently Asked Questions</h1>
<div class="main" id="help">
<div class="confignav">
<span class="tab"><a href="/help/configuration">Configuration</a></span>
<span class="tab"><a href="/help/advancedsettings">Advanced Settings</a></span>
<span class="tab"><a href="/help/ui">User Interface</a></span>
<span class="tab"><a href="/help/reseed">Reseeding</a></span>
<span class="tab"><a href="/help/tunnelfilter">Tunnel Filtering</a></span>
<span class="tab2">FAQ</span>
<span class="tab"><a href="/help/newusers">New User Guide</a></span>
<span class="tab"><a href="/help/webhosting">Web Hosting</a></span>
<span class="tab"><a href="/help/hostnameregistration">Hostname Registration</a></span>
<span class="tab"><a href="/help/troubleshoot">Troubleshoot</a></span>
<span class="tab"><a href="/help/glossary">Glossary</a></span>
<span class="tab"><a href="/help/legal">Legal</a></span>
<span class="tab"><a href="/help/changelog">Change Log</a></span>
</div>
<div id="faq">
<h2>Abridged I2P FAQ</h2>

<p class="infohelp">This is a shortened version of the official FAQ. For the full version, please visit <a href="https://geti2p.net/faq" target="_blank" rel="noreferrer" class="sitelink external">https://geti2p.net/faq</a> or <a href="http://i2p-projekt.i2p/faq" target="_blank" rel="noreferrer" class="sitelink">http://i2p-projekt.i2p/faq</a>.

<h3>My router has been up for several minutes and has zero or very few connections</h3>

<p>If after a few minutes of uptime your router is indicating 0 Active Peers and 0 Known Peers, with a notification in the sidebar that you need to check your network connection, verify that you can access the internet. If your internet connection is functional, you may need to unblock Java in your firewall. Otherwise, you may need to reseed your I2P router. Visit the <a href="/configreseed#reseedconfig">Reseed Configuration page</a> and click the <i>Save Changes and Reseed Now</i> button. For more information, see the <a href="#reseedhelp">Reseed help section</a> above.</p>

<h3>My router has very few active peers, is this OK?</h3>

<p>If your router has 10 or more active peers, everything is fine. The router should maintain connections to a few peers at all times. The best way to stay "better-connected" to the network is to <a href="/config">share more bandwidth</a>.</p>

<h3 id="addressbooksubs">I'm missing lots of hosts in my addressbook. What are some good subscription links?</h3>

<p>The default subscription is to <code>http://i2p-projekt.i2p/hosts.txt</code> which is seldom updated. If you don't have another subscription, you may often have to use "jump" links which is much slower but ensures that your addressbook is only populated by sites you use (in addition to the default subscription addresses). To speed up browsing on I2P, it's a good idea to add some addressbook subscriptions.</p>

<p>Here are some other public addressbook subscription links. You may wish to add one or two to your <a href="/susidns/subscriptions" target="_blank" rel="noreferrer">susidns subscription list</a>. In the event that addresses conflict in the subscriptions, the lists placed at the top of your susidns configuration will take precedence over those placed further down.</p>

<ul>
<li><code>http://stats.i2p/cgi-bin/newhosts.txt</code></li>
<li><code>http://reg.i2p/export/hosts.txt</code></li>
<li><code>http://identiguy.i2p/hosts.txt</code></li>
<li><code>http://notbob.i2p/hosts.txt</code></li>
<li><code>http://skank.i2p/hosts.txt</code></li>
</ul>

<p>Note that subscribing to a hosts.txt service is an act of trust, as a malicious subscription could give you incorrect addresses, so be careful subscribing to lists from unknown sources. The operators of these services may have various policies for listing hosts. Presence on this list does not imply endorsement.</p>

<h3>How do I access IRC, BitTorrent, or other services on the regular Internet?</h3>

<p>Unless an outproxy has been set up for the service you want to connect to, this is not possible, with the exception of BitTorrent (see below). There are only three types of outproxies running right now: HTTP, HTTPS, and email. Note that there is currently no publicly listed SOCKS outproxy. If this type of service is required, try <a href="https://torproject.org/" target="_blank" rel="noreferrer" class="sitelink external">Tor</a>.</p>

<h3>Can I download torrents from non-I2P trackers?</h3>

<p>Until the advent of I2P integration in <a href="http://www.vuze.com/" target="_blank" rel="noreferrer" class="sitelink external">Vuze</a>, it wasn't possible to download torrents that weren't hosted on the I2P network. However, now that Vuze (and latterly <a href="https://www.biglybt.com/" target="_blank" rel="noreferrer" class="sitelink external">Bigly</a>) allow users to share non-I2P torrents on I2P, non-I2P torrents can be downloaded over I2P if Vuze/Bigly users have chosen to make content available to the I2P network. Popular torrents with a large number of peers are more likely to be accessible via I2P; to determine if the content is available, add the torrent link, magnet or infohash to your I2P-enabled BitTorrent client.</p>

<h3 id="alternative_browser">How do I configure I2P to open at startup in a specific web browser?</h3>

<p>By default, I2P will use the system configured default browser to launch at startup. If you wish to nominate an alternative browser, you will need to edit your router.config file (or the <a href="/configadvanced">Advanced Configuration page</a> if you have enabled advanced console mode) and add a new configuration line <code>routerconsole.browser=/path/to/browser</code> or <code>routerconsole.browser=\path\to\browser.exe</code> if using Windows, replacing <code>/path/to/browser</code> with the location of the browser you wish to use. Note: If the path to your chosen browser contains spaces, you may need to encase the path in quotes e.g. <code>routerconsole.browser="C:\Program Files\Mozilla\Firefox.exe"</code></p>

<h3>How do I configure my browser to access .i2p websites?</h3>

<p>You will need to configure your browser to use the HTTP proxy server (by default on host: <code>127.0.0.1</code> port: <code>4444</code>). See the <a href="https://geti2p.net/en/about/browser-config" target="_blank" rel="noreferrer" class="sitelink external">Browser Proxy Configuration Guide</a> for a more detailed explanation.</p>

<h3>What is an eepsite?</h3>

<p>An eepsite is a website that is hosted anonymously on the I2P network - you can access it by configuring your web browser to use I2P's HTTP proxy (see above) and browsing to the <code>.i2p</code> suffixed website (e.g. <a href="http://i2p-projekt.i2p" target="_blank" rel="noreferrer" class="sitelink">http://i2p-projekt.i2p</a>). Also ensure your browser is configured to resolve DNS remotely when using the proxy to avoid DNS leaks.</p>

<h3>Most of the eepsites are down?</h3>

<p>If you consider every eepsite that has ever been created, yes, most of them are down. People and eepsites come and go. A good way to get started in I2P is check out a list of eepsites that are currently up. <a href="http://identiguy.i2p" target="_blank" rel="noreferrer" class="sitelink">http://identiguy.i2p</a> tracks active eepsites.</p>

<h3>Where are my I2P configuration files stored?</h3>
<p>Configuration files for the router, installed plugins, and router logs are stored in the following location:</p>
<ul id="faqconfigfiles">
<li><b>Windows:</b> <code>%APPDATA%\I2P\</code></li>
<li><b>OS X:</b> <code>/Users/<i>username</i>/Library/Application Support/i2p</code></li>
<li><b>Linux:</b>
<ul>
<li>Standard Java Installation:<code> ~/.i2p/</code></li>
<li>Package/repository installation: <code>/var/lib/i2p/i2p-config/</code></li>
</ul>
</li>
</ul>

<h3>How do I enable https:// access for the router console?</h3>
<p>Locate the configuration file: <code>00-net.i2p.router.web.RouterConsoleRunner-clients.config</code> in the <code>clients.config.d</code> folder in your I2P settings directory, and then edit accordingly:</p>
<ul>
<li><b>For both non-SSL and SSL:</b> <code>clientApp.0.args=7657 127.0.0.1 -s 7667 127.0.0.1 ./webapps/</code></li>
<li><b>For SSL only:</b> <code>clientApp.0.args=-s 7667 127.0.0.1 ./webapps/</code></li>
</ul>

<h3>How do I connect to IRC within I2P?</h3>

<p>A tunnel to the main IRC server network within I2P, Irc2P, is created when I2P is installed (see the <a href="/i2ptunnelmgr">Tunnel Manager</a>), and is automatically started when the I2P router starts. To connect to it, tell your IRC client to connect to server: <code>127.0.0.1</code> port: <code>6668</code>.</p>

<p>XChat-like client users can create a new network with the server <code>127.0.0.1/6668</code> (remember to tick <i>Bypass proxy server</i> if you have a proxy server configured), or you can connect with the command <code>/server 127.0.0.1 6668</code>. Different IRC clients may vary the syntax.</p>

<h3>My IRC client keeps disconnecting from the network, is there anything I can do?</h3>

<p>Some IRC clients are configured to disconnect from the server if there's been no communication with the server for a period of time, which can sometimes be problematic for I2P IRC if the <i>ping timeout</i> value is set too low. Setting the ping timeout value in your client to a high value (e.g. 320 seconds) will often result in a more robust connection to the network. In hexchat you can configure the value by typing <code>/set net_ping_timeout 320</code>. This will write the value to your <code>hexchat.conf</code> file.</p>

<h3>What ports does I2P use?</h3>

<table id="portfaq">
<tr><th colspan="3">LOCAL PORTS</th></tr>
<tr><td colspan="3" class="infohelp">These are the local I2P port mappings, listening only to local connections by default, except where noted. Unless you require access from other machines, they should only be accessible from localhost.</td></tr>
<tr><th>Port</th><th>Function</th><th>Notes</th></tr>
<tr><td>1900</td><td>UPnP SSDP UDP multicast listener</td><td>Cannot be changed. Binds to all interfaces. Can be disabled on the <a href="/confignet">Network Configuration page</a>.</td></tr>
<tr><td>2827</td><td>BOB bridge</td><td>A higher level socket API for clients. Can be enabled/disabled on the <a href="/configclients">Client Configuration page</a>. Can be changed in the <code>bob.config</code> file. [Disabled by default]</td></tr>
<tr><td>4444</td><td>HTTP proxy</td><td>Can be disabled or changed in the <a href="/i2ptunnelmgr">Tunnel Manager</a>. Can also be configured to bind to a specific interface or all interfaces.</td></tr>
<tr><td>4445</td><td>HTTPS proxy</td><td>Can be disabled or changed in the <a href="/i2ptunnelmgr">Tunnel Manager</a>. Can also be configured to bind to a specific interface or all interfaces.</td></tr>
<tr><td>6668</td><td>IRC proxy</td><td>Can be disabled or changed in the <a href="/i2ptunnelmgr">Tunnel Manager</a>. Can also be configured to bind to a specific interface or all interfaces.</td></tr>
<tr><td>7652</td><td>UPnP HTTP TCP event listener</td><td>Binds to the LAN address. Can be changed with advanced config <code>i2np.upnp.HTTPPort=nnnn</code>. Can be disabled on the <a href="/confignet">Network Configuration page</a>.</td></tr>
<tr><td>7653</td><td>UPnP SSDP UDP search response listener</td><td>Binds to all interfaces. Can be changed with advanced config <code>i2np.upnp.SSDPPort=nnnn</code>. Can be disabled on the <a href="/configclients">Client Configuration page</a>.</td></tr>
<tr><td>7654</td><td>I2P Client Protocol port</td><td>Used by client apps. Can be changed to a different port on the <a href="/configclients">Client Configuration page</a> but this is not recommended. Can be bound to a different interface or all interfaces, or disabled, on the <a href="/configclients">Client Configuration page</a>.</td></tr>
<tr><td>7655</td><td>UDP for SAM bridge</td><td>A higher level socket API for clients. Only opened when a SAM V3 client requests a UDP session. Can be enabled/disabled on the <a href="/configclients"> Client Configuration page</a>. Can be changed in the <code>clients.config</code> file with the SAM command line option <code>sam.udp.port=nnnn</code>.</td></tr>
<tr><td>7656</td><td>SAM bridge</td><td>A higher level socket API for clients. Can be enabled/disabled on the <a href="/configclients">Client Configuration page</a>. Can be changed in the <code>clients.config</code> file. [Disabled by default]</td></tr>
<tr><td>7657</td><td>I2P Router Console (Web interface)</td><td>Can be disabled in the <code>clients.config</code> file. Can also be configured to bind to a specific interface or all interfaces. If you make the Router Console available over the network, you might wish to <a href="/configui#passwordheading">enforce an access password</a> to prevent unauthorized access.</td></tr>
<tr><td>7658</td><td>I2P Web Server</td><td>Can be disabled in the <code>clients.config</code> file. Can also be configured to bind to a specific interface or all interfaces in the <code>jetty.xml</code> file.</td></tr>
<tr><td>7659</td><td>Outgoing mail to smtp.postman.i2p</td><td>Can be disabled or changed in the <a href="/i2ptunnelmgr">Tunnel Manager</a>. Can also be configured to bind to a specific interface or all interfaces.</td></tr>
<tr><td>7660</td><td>Incoming mail from pop.postman.i2p</td><td>Can be disabled or changed in the <a href="/i2ptunnelmgr">Tunnel Manager</a>. Can also be configured to bind to a specific interface or all interfaces.</td></tr>
<tr><td>7667</td><td>I2P Router Console (https://)</td><td>Can be enabled in the <code>clients.config</code> file. Can also be configured to bind to a specific interface or all interfaces. If both http and https are enabled, automatic redirection to https is enabled by default. If you make the Router Console available over the network, you might wish to <a href="/configui#passwordheading">enforce an access password</a> to prevent unauthorized access.</td></tr>
<tr><td>8998</td><td>mtn.i2p2.i2p (I2P's Monotone DVCS)</td><td>Can be disabled or changed in the <a href="/i2ptunnelmgr">Tunnel Manager</a>. Can also be configured to bind to a specific interface or all interfaces. [Disabled by default]</td></tr>
<tr><td>31000</td><td>Local connection to wrapper control channel port</td><td>Outbound to 32000 only, does not listen on this port. Starts at 31000 and will increment until 31999 looking for a free port. To change, see the <a href="http://wrapper.tanukisoftware.com/doc/english/prop-port.html" target="_blank" rel="noreferrer" class="sitelink external">wrapper documentation</a>.</td></tr>
<tr><td>32000</td><td>Local control channel for the service wrapper</td><td>To change, see the <a href="http://wrapper.tanukisoftware.com/doc/english/prop-port.html" target="_blank" rel="noreferrer" class="sitelink external">wrapper documentation</a>.</td></tr>
<tr><th colspan="3">INTERNET FACING PORTS</th></tr>
<tr><td colspan="3" class="infohelp">I2P selects a random port between 9000 and 31000 to communicate with other routers when the program is run for the first time, or when your external IP address changes when running in <a href="/confignet#ipchange">Laptop Mode</a>. The <a href="/confignet#udpconfig">selected port</a> is shown on the <a href="/confignet">Network Configuration page</a>.</td></tr>
<tr><td colspan="3">Outbound UDP from the <a href="/confignet#udpconfig">random port</a> noted on the Network Configuration page to arbitrary remote UDP ports, allowing replies.</td></tr>
<tr><td colspan="3">Outbound TCP from random high ports to arbitrary remote TCP ports.</td></tr>
<tr><td colspan="3">Inbound UDP to the <a href="/confignet#udpconfig">port</a> noted on the Network Configuration page from arbitrary locations (optional, but recommended).</td></tr>
<tr><td colspan="3">Inbound TCP to the <a href="/confignet#externaltcp">port</a> noted on the Network Configuration page from arbitrary locations (optional, but recommended). <a href="/confignet#tcpconfig">Inbound TCP</a> may be disabled on the Network Configuration page.</td></tr>
<tr><td colspan="3">Outbound UDP on port 123, allowing replies: this is necessary for I2P's internal time sync (via SNTP - querying a random SNTP host in <code>pool.ntp.org</code> or <a href="/help/advancedsettings#ntpserverconfig">another server you specify</a>).</td></tr>
</table>
</div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>