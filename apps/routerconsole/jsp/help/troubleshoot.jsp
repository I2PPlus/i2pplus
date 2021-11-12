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
<title>Troubleshooting - I2P+</title>
<%@include file="../css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("Troubleshooting")%></h1>
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
<span class="tab2">Troubleshoot</span>
<span class="tab"><a href="/help/glossary">Glossary</a></span>
<span class="tab"><a href="/help/legal">Legal</a></span>
<span class="tab"><a href="/help/changelog">Change Log</a></span>
</div>
<div id="troubleshoot">
<h2><%=intl._t("Troubleshooting")%>&nbsp;I2P</h2>

<ul>

<p>If you're experiencing issues with running I2P, the following information may help identify and resolve the problem:</p>

<li><b>Be Patient!</b><br>
I2P may be slow to integrate into network the first time you run it as it bootstraps into the network and learns of additional peers. The longer your I2P router is running, the better it will perform, so try and keep your router on as long as you can, 24/7 if possible! If, after 30 minutes, your <i>Active: [connected/recent]</i> count still has less than 10 peers, or your total number of <i>Integrated</i> peers is less than 5, there are several things you can do to check for problems:</li>

<li><b>Check your Configuration &amp; Bandwidth Allocation</b><br>
I2P functions best when you can accurately reflect the speed of your network connection in the <a href="/config">bandwidth configuration section</a>. By default I2P is configured with some fairly conservative values that will not suit many use cases, so please take time to review these settings and correct where necessary. The more bandwidth you allocate, <i>specifically</i> upstream bandwidth, the more you will benefit from the network.</li>

<li><b>Firewalls, Modems &amp; Routers</b><br>
Where possible, please ensure I2P/Java is allowed bi-directional port access from the internet by configuring your modem/router/pc firewall accordingly. If you're behind a prohibitive firewall but have unrestricted outbound access, I2P can still function; you can turn off inbound access and rely on <a href="http://i2p-projekt.i2p/udp.html" target="_blank" rel="noreferrer" class="sitelink">SSU IP Address Detection</a> (<a href="https://wikipedia.org/wiki/Hole_punching" target="_blank" rel="noreferrer" class="sitelink external">firewall hole punching</a>) to connect you to the network, and your network status in the side panel will indicate "Network: Firewalled". For optimal performance, please ensure I2P's <a href="/confignet#udpconfig">external port</a> is visible from the internet (see below for more information).</li>

<li><b>Check Your Proxy Setttings</b><br>
If you cannot see any websites at all (not even <a href="http://i2p-projekt.i2p/" target="_blank" rel="noreferrer">i2p-projekt.i2p</a>), make sure your browser's proxy is set to access http traffic (<i>not</i> https, <i>not</i> socks) via <code>127.0.0.1 port 4444</code>. If you need some help, there's <a href="https://geti2p.net/en/about/browser-config" target="_blank" rel="noreferrer" class="sitelink external">a guide</a> to configuring your browser for I2P use.</li>

<li><b>Check Your Logs</b><br>
<a href="/logs">Logs</a> may help resolve a problem. You may wish to paste excerpts in a <a href="http://i2pforum.i2p/" target="_blank" rel="noreferrer">forum</a> for help, or perhaps <a href="http://zerobin.i2p/" target="_blank" rel="noreferrer" class="sitelink external">paste</a> it instead and reference the link on IRC for help.</li>

<li><b>Verify Java is Up to Date</b><br>
Ensure your Java is up to date. Version 1.7 or higher is required; 1.8 or higher is recommended. Check your Java version at the top of <a href="/logs">the logs page</a>.</li>

<li><b>Problems running on Legacy Hardware</b><br>
[Linux/Unix/Solaris] If you can't start the router with <code>i2p/i2prouter start</code> try the <code>runplain.sh</code> script in the same directory. Root privileges are never required to run I2P. If you need to compile the <a href="http://i2p-projekt.i2p/jbigi.html" target="_blank" rel="noreferrer">jBigi library</a> (which is necessary in rare cases), consult the documentation, visit the forums, or come pay a visit to our <a href="irc://127.0.0.1:6668/i2p-dev" class="chatlink">IRC developer channel</a>.</li>

<li><b>Enable Universal Plug and Play (UPnP)</b><br>
Your modem or router may support <a href="https://wikipedia.org/wiki/Universal_Plug_and_Play" target="_blank" rel="noreferrer">Universal Plug &amp; Play</a> (UPnP), which permits automatic port forwarding. Ensure UPnP support for I2P is enabled on the <a href="/confignet">config page</a>, then try to activate UPnP on your modem/router and possibly your computer also. Now try restarting the <a href="/">I2P router</a>. If successful, I2P should report "Network: OK" in the side panel once the I2P router completes initial connectivity tests.</li>

<li><b>Port Forwarding</b><br>
Open <a href="/confignet#udpconfig">I2P's port</a> on your modem, router and/or firewall(s) for better connectivity (ideally both UDP and TCP). More information on how to go about port forwarding can be found at <a href="http://portforward.com/" target="_blank" rel="noreferrer" class="sitelink external">portforward.com</a>, in addition to our forums and IRC channels listed below. Note that I2P does not support connecting to the internet via an http or socks proxy [patches welcome!], though you can connect to proxies via I2P itself once connected to the network.</li>

<li><b>Getting Support Online</b><br>
You may also want to review the information on the <a href="http://i2p-projekt.i2p/" target="_blank" rel="noreferrer">I2P website</a>, post messages to the <a href="http://i2pforum.i2p/viewforum.php?f=8" target="_blank" rel="noreferrer">I2P discussion forum</a> or swing by <a href="irc://127.0.0.1:6668/i2p" class="chatlink">#i2p</a> or <a href="irc://127.0.0.1:6668/i2p-chat" class="chatlink">#i2p-chat</a> on I2P's internal IRC network. These channels are also available outside of I2P's encrypted, anonymous network via the <a href="irc://irc.freenode.net/i2p" class="chatlink">Freenode</a> or <a href="irc://irc.freenode.net/i2p" class="chatlink">OFTC</a> IRC networks.</li>

<li><b>Reporting Bugs</b><br>
If you'd like to report a bug, please file a ticket on <a href="http://trac.i2p2.i2p/" target="_blank" rel="noreferrer" class="sitelink">trac.i2p2.i2p</a>. For developer-related discussions, please visit <a href="http://zzz.i2p/" target="_blank" rel="noreferrer" class="sitelink">zzz's developer forums</a> or come and visit the <a href="irc://127.0.0.1:6668/i2p-dev" class="chatlink">developer channel</a> on I2P's IRC network.</li>
</ul>

<hr>

<p>Further assistance is available here:</p>
<ul class="links" id="furtherassistance">
<li><a href="http://i2pforum.i2p/" target="_blank" rel="noreferrer">I2P Support Forum</a></li>
<li><a href="http://zzz.i2p/" target="_blank" rel="noreferrer">I2P Developers' Forum</a></li>
<li><a href="http://wiki.i2p-projekt.i2p/wiki/index.php/Eepsite/Services" target="_blank" rel="noreferrer">I2P Wiki</a></li>
<li>The FAQ on <a href="http://i2p-projekt.i2p/en/faq" target="_blank" rel="noreferrer">i2p-projekt.i2p</a> or <a href="https://geti2p.net/en/faq" target="_blank" rel="noreferrer">geti2p.net</a></li>
</ul>
<p>You may also try <a href="irc://127.0.0.1:6668/i2p" class="chatlink">I2P's IRC network</a>.</p>
</div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>