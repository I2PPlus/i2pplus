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
<title><%=intl._t("Troubleshooting")%> - I2P+</title>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("Troubleshooting")%></h1>
<div class="main" id="help">
<div class="confignav">
<span class="tab"><a href="/help/configuration"><%=intl._t("Configuration")%></a></span>
<span class="tab"><a href="/help/advancedsettings"><%=intl._t("Advanced Settings")%></a></span>
<span class="tab"><a href="/help/ui"><%=intl._t("User Interface")%></a></span>
<span class="tab"><a href="/help/reseed"><%=intl._t("Reseeding")%></a></span>
<span class="tab"><a href="/help/tunnelfilter"><%=intl._t("Tunnel Filtering")%></a></span>
<span class="tab"><a href="/help/faq"><%=intl._t("FAQ")%></a></span>
<span class="tab"><a href="/help/newusers"><%=intl._t("New User Guide")%></a></span>
<span class="tab"><a href="/help/webhosting"><%=intl._t("Web Hosting")%></a></span>
<span class="tab"><a href="/help/hostnameregistration"><%=intl._t("Hostname Registration")%></a></span>
<span class="tab2"><%=intl._t("Troubleshoot")%></span>
<span class="tab"><a href="/help/glossary"><%=intl._t("Glossary")%></a></span>
<span class="tab"><a href="/help/legal"><%=intl._t("Legal")%></a></span>
<span class="tab"><a href="/help/changelog"><%=intl._t("Change Log")%></a></span>
</div>
<div id="troubleshoot">
<h2><%=intl._t("Troubleshooting")%>&nbsp;I2P+</h2>

<ul>

<p><%=intl._t("If you're experiencing issues with running I2P+, the following information may help identify and resolve the problem:")%></p>

<li><b><%=intl._t("Be Patient!")%></b><br>
<%=intl._t("I2P+ may be slow to integrate into network the first time you run it as it bootstraps into the network and learns of additional peers. The longer your I2P+ router is running, the better it will perform, so try and keep your router on as long as you can, 24/7 if possible! If, after 30 minutes, your <i>Active: [connected/recent]</i> count still has less than 10 peers, or your total number of <i>Integrated</i> peers is less than 5, there are several things you can do to check for problems:")%></li>

<li><b><%=intl._t("Check your Configuration &amp; Bandwidth Allocation")%></b><br>
%=intl._t("I2P+ functions best when you can accurately reflect the speed of your network connection in the <a href=/config>bandwidth configuration section</a>. By default I2P+ is configured with some fairly conservative values that will not suit many use cases, so please take time to review these settings and correct where necessary. The more bandwidth you allocate, <i>specifically</i> upstream bandwidth, the more you will benefit from the network.")%></li>

<li><b><%=intl._t("Firewalls, Modems &amp; Routers")%></b><br>
%=intl._t("Where possible, please ensure I2P+/Java is allowed bi-directional port access from the internet by configuring your modem/router/pc firewall accordingly. If you're behind a prohibitive firewall but have unrestricted outbound access, I2P+ can still function; you can turn off inbound access and rely on <a href=http://i2p-projekt.i2p/udp.html target=_blank rel=noreferrer class=sitelink>SSU IP Address Detection</a> (<a href=https://wikipedia.org/wiki/Hole_punching target=_blank rel=noreferrer class='sitelink external'>firewall hole punching</a>) to connect you to the network, and your network status in the side panel will indicate \"Status: Firewalled\". For optimal performance, please ensure I2P's <a href=/confignet#udpconfig>external port</a> is visible from the internet (see below for more information).")%></li>

<li><b><%=intl._t("Check Your Proxy Setttings")%></b><br>
%=intl._t("If you cannot see any websites at all (not even <a href=http://i2p-projekt.i2p/ target=_blank rel=noreferrer>i2p-projekt.i2p</a>), make sure your browser's proxy is set to access http traffic (<i>not</i> https, <i>not</i> socks) via <code>127.0.0.1 port 4444</code>. If you need some help, there's <a href=https://geti2p.net/en/about/browser-config target=_blank rel=noreferrer class='sitelink external'>a guide</a> to configuring your browser for I2P use.")%></li>

<li><b><%=intl._t("Check Your Logs")%></b><br>
%=intl._t("<a href=/logs>Logs</a> may help resolve a problem. You may wish to paste excerpts in a <a href=http://i2pforum.i2p/ target=_blank rel=noreferrer>forum</a> for help, or perhaps <a href=http://cake.i2p/pastebin/ target=_blank rel=noreferrer class='sitelink external'>paste</a> it instead and reference the link on IRC for help.")%></li>

<li><b><%=intl._t("Verify Java is Up to Date")%></b><br>
%=intl._t("Ensure your Java is up to date. Version 1.8 or higher is required; version 11 or higher is recommended. Check your Java version at the top of <a href=/info>the router information page</a>.")%></li>

<li><b><%=intl._t("Problems running on Legacy Hardware")%></b><br>
%=intl._t("[Linux/Unix/Solaris] If you can't start the router with <code>i2p/i2prouter start</code> try the <code>runplain.sh</code> script in the same directory. Root privileges are never required to run I2P+. If you need to compile the <a href=http://i2p-projekt.i2p/jbigi.html target=_blank rel=noreferrer>jBigi library</a> (which is necessary in rare cases), consult the documentation, visit the forums, or visit <a href=irc://127.0.0.1:6668/saltR class=chatlink>the I2P+ channel</a> or <a href=irc://127.0.0.1:6668/i2p-dev class=chatlink>IRC developer channel</a>.")%></li>

<li><b><%=intl._t("Enable Universal Plug and Play (UPnP)")%></b><br>
%=intl._t("Your modem or router may support <a href=https://wikipedia.org/wiki/Universal_Plug_and_Play target=_blank rel=noreferrer>Universal Plug &amp; Play</a> (UPnP), which permits automatic port forwarding. Ensure UPnP support for I2P+ is enabled on the <a href=/confignet>config page</a>, then try to activate UPnP on your modem/router and possibly your computer also. Now try restarting the <a href="/">I2P+ router</a>. If successful, I2P+ should report \"Status: OK\" in the side panel once the I2P+ router completes initial connectivity tests.")%></li>

<li><b><%=intl._t("Port Forwarding")%></b><br>
%=intl._t("Open <a href=/confignet#udpconfig>I2P+'s port</a> on your modem, router and/or firewall(s) for better connectivity (ideally both UDP and TCP). More information on how to go about port forwarding can be found at <a href=http://portforward.com/ target=_blank rel=noreferrer class='sitelink external'>portforward.com</a>, in addition to our forums and IRC channels listed below. Note that I2P does not support connecting to the internet via an http or socks proxy [patches welcome!], though you can connect to proxies via I2P itself once connected to the network.")%></li>

<li><b><%=intl._t("Getting Support Online")%></b><br>
%=intl._t("You may also want to review the information on the <a href=http://i2p-projekt.i2p/ target=_blank rel=noreferrer>I2P website</a>, post messages to the <a href=http://i2pforum.i2p/viewforum.php?f=8 target=_blank rel=noreferrer>I2P discussion forum</a> or swing by <a href=irc://127.0.0.1:6668/saltR class=chatlink>#saltR</a> or <a href=irc://127.0.0.1:6668/i2p class=chatlink>#i2p</a> on I2P's internal IRC network.")%></li>

<li><b><%=intl._t("Reporting Bugs")%></b><br>
%=intl._t("If you'd like to report a bug, please file a ticket on <a href=http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues target=_blank rel=noreferrer class=sitelink>trac.i2p2.i2p</a>. For developer-related discussions, please visit <a href=http://zzz.i2p/ target=_blank rel=noreferrer class=sitelink>zzz's developer forums</a> or come and visit the <a href=irc://127.0.0.1:6668/i2p-dev class=chatlink>developer channel</a> on I2P's IRC network.")%></li>
</ul>

<hr>

<p><%=intl._t("Further assistance is available here:")%></p>
<ul class="links" id="furtherassistance">
<li><a href=http://i2pforum.i2p/ target=_blank rel=noreferrer><%=intl._t("I2P Support Forum")%></a></li>
<li><a href=http://zzz.i2p/ target=_blank rel=noreferrer><%=intl._t("I2P Developers' Forum")%></a></li>
<li><a href=http://wiki.i2p-projekt.i2p/wiki/index.php/Eepsite/Services target=_blank rel=noreferrer><%=intl._t("I2P Wiki")%></a></li>
<li><%=intl._t("The FAQ on <a href=http://i2p-projekt.i2p/en/faq target=_blank rel=noreferrer>i2p-projekt.i2p</a> or <a href=https://geti2p.net/en/faq target=_blank rel=noreferrer>geti2p.net</a>")%></li>
</ul>
<p><%=intl._t("You may also try <a href=irc://127.0.0.1:6668/saltR class=chatlink>#saltR</a> or <a href=irc://127.0.0.1:6668/i2p class=chatlink> on I2P's IRC network</a>.")%></p>
</div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>