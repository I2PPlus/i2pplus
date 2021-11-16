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
<title>Reseeding Help - I2P+</title>
<%@include file="../css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("Reseeding Help")%></h1>
<div class="main" id="help">

<div class="confignav">
<span class="tab"><a href="/help/configuration">Configuration</a></span>
<span class="tab"><a href="/help/advancedsettings">Advanced Settings</a></span>
<span class="tab"><a href="/help/ui">User Interface</a></span>
<span class="tab2">Reseeding</span>
<span class="tab"><a href="/help/tunnelfilter">Tunnel Filtering</a></span>
<span class="tab"><a href="/help/faq">FAQ</a></span>
<span class="tab"><a href="/help/newusers">New User Guide</a></span>
<span class="tab"><a href="/help/webhosting">Web Hosting</a></span>
<span class="tab"><a href="/help/hostnameregistration">Hostname Registration</a></span>
<span class="tab"><a href="/help/troubleshoot">Troubleshoot</a></span>
<span class="tab"><a href="/help/glossary">Glossary</a></span>
<span class="tab"><a href="/help/legal">Legal</a></span>
<span class="tab"><a href="/help/changelog">Change Log</a></span>
</div>

<div id="reseedhelp">
<h2><%=intl._t("Reseeding Your Router")%></h2>

<p><%=intl._t("Reseeding is the bootstrapping process used to find other routers when you first install I2P, or when your router has too few router references remaining. The default settings will work for most people. Change these only if HTTPS is blocked by a restrictive firewall and reseed has failed.")%></p>

<ol>
<li><%=intl._t("If reseeding has failed, you should first check your network connection.")%></li>
<li><%=intl._t("If a firewall is blocking your connections to reseed hosts, you may have access to a proxy.")%>

<ul>
<li><%=intl._t("The proxy may be remotely hosted or a service running on your computer (localhost/127.0.0.1).")%></li>
<li><%=intl._t("To use a proxy to reseed, configure the proxy type, hostname, and port on the {0}.", "<a href=\"/configreseed\">" + intl._t("Reseed Configuration page") + "</a>")%></li>
<li><%=intl._t("If you are running the Tor Browser, you may use its Tor instance by configuring I2P to use: <i>Proxy type:</i> <code>SOCKS 5</code> <i>Host:</i> <code>127.0.0.1</code> <i>Port:</i> <code>9150</code>.")%></li>
<li><%=intl._t("If you are running a standalone Tor instance locally, you may use it by configuring I2P to use: <i>Proxy type:</i> <code>SOCKS 5</code> <i>Host:</i> <code>127.0.0.1</code> <i>Port:</i> <code>9050</code>.")%></li>
<li><%=intl._t("If you have some peers but need more, you may try the I2P Outproxy option. This will not work for the initial reseed when you have 0 known peers. Selecting this option will use the I2P HTTP outproxy (by default on <code>127.0.0.1:4444</code>) or, if configured, the Orchid Tor plugin.")%></li>
<li><%=intl._t("Then click the <i>Save changes</i> button to confirm the proxy configuration and then the <i>Reseed now</i> button to initiate the reseed process.")%></li>
</ul>
</li>

<li><%=intl._t("If you know and trust somebody that runs I2P, ask them to send you a reseed file generated using this page in their router console. Then, select the <i>Reseed from file</i> option on the {0} using the file you received.", "<a href=\"/configreseed#reseedurl\">" + intl._t("Reseed Configuration page") + "</a>")%></li>
<li><%=intl._t("If you know and trust somebody that publishes reseed files, ask them for the URL. Then, click the <i>Reseed from URL</i> button on the {0} to reseed using the URL you received.", "<a href=\"/configreseed#reseedcreatefile\">" + intl._t("Reseed Configuration page") + "</a>")%></li>
</ol>

<h3 id="manual_reseed"><%=intl._t("How do I reseed manually?")%></h3>

<p><%=intl._t("An I2P router usually only needs to be seeded once, to join the network for the first time. However, if the router is running for an extended period without network connectivity it may also need to be reseeded. Reseeding involves fetching multiple \"RouterInfo\" files (bundled into a signed zip-file) from at least two predefined server URLs picked from a volunteer-run group of clearnet HTTPS servers.")%></p>

<p><%=intl._t("If the number of known peers drops below 50, I2P will attempt to reseed automatically. If the reseed attempt fails, a notification will appear in the sidebar with an option to manually reseed. A failed reseed attempt can occur if your local firewall limits outbound traffic, you are experiencing network connectivity issues, or if the reseed request is blocked entirely.")%></p>

<p><%=intl._t("In the event that you suspect the request is being blocked, you can configure I2P to use a proxy to reseed. Go to the {0} and configure the proxy type, hostname, and port.", "<a href=\"/configreseed#reseedproxytype\">" + intl._t("Reseed Configuration page") + "</a>")%></p>

<p><%=intl._t("If you are stuck behind an ISP firewall or filter, you can use the following manual method (non-automated technical solution) to join the I2P network.")%></p>

<h3><%=intl._t("Joining the I2P Network using a reseed file")%></h3>

<p><%=intl._t("Please contact a known trustworthy friend who has a running I2P router, and ask them for help with reseeding your I2P router. Request that they send you a reseed file exported from their running I2P router. It is vital that the file is exchanged over a secure channel, e.g. encrypted to avoid external tampering (PGP Sign, Encrypt and Verified with a trusted public key). The file itself is unsigned, so please accept files only from known trusted friends. Never import a reseed file if you cannot verify its source.")%></p>

<p><%=intl._t("To import the received i2preseed.zip file into your local I2P router:")%></p>

<ul>
<li><%=intl._t("Go to the {0}", "<a href=\"/configreseed#reseedzip\">" + intl._t("Reseed Configuration page") + "</a>")%></li>
<li><%=intl._t("Under <i>Select zip or su3 file</i> click <i>Browse&hellip;</i>")%></li>
<li><%=intl._t("Select the i2preseed.zip file")%></li>
<li><%=intl._t("Click the <i>Reseed from File</i> button")%></li>
</ul>

<p><%=intl._t("The reseed status will be indicated on the sidebar. You can also check the {0} for the following message: <code>Reseed got 100 router infos from file with 0 errors</code>", "<a href=\"/logs#servicelogs\">" + intl._t("Service (Wrapper) log") + "</a>")%></p>

<h3><%=intl._t("Sharing a reseed file")%></h3>

<p><%=intl._t("For trusted friends you can use your local I2P router to give them a jump start:")%></p>

<ul>
<li><%=intl._t("Go to the {0}", "<a href=\"/configreseed#reseedcreatefile\">" + intl._t("Reseed Configuration page") + "</a>")%></li>
<li><%=intl._t("Under <i>Create Reseed File</i> click the <i>Create reseed file</i> button")%></li>
<li><%=intl._t("Securely send the i2preseed.zip file to your friend")%></li>
</ul>

<p><%=intl._t("Do not reveal this file to unknown users, as it contains sensitive data (100 RouterInfo files) from your own I2P router which potentially could be used by an adversary to decloak your identity. In order to protect your anonymity: you should wait for a few hours/days before sharing the file with your trusted friend. It is also advisable to use this procedure sparingly (no more than once a week).")%></p>

<h3><%=intl._t("General guidelines for manual reseeding of I2P")%></h3>

<ul>
<li><%=intl._t("Do not publicly publish the reseed file or share these files with a friend of a friend!")%></li>
<li><%=intl._t("This file should be used only for a very limited number of friends (less than 3).")%></li>
<li><%=intl._t("The file will only be valid for a limited time (less than 3 weeks).")%></li>
</ul>

</div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>