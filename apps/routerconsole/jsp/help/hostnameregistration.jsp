<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<html>
<head>
<title>Web Hosting - I2P+</title>
<%@include file="../css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp">Hostname Registration</h1>
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
<span class="tab2">Hostname Registration</span>
<span class="tab"><a href="/help/troubleshoot">Troubleshoot</a></span>
<span class="tab"><a href="/help/glossary">Glossary</a></span>
<span class="tab"><a href="/help/legal">Legal</a></span>
<span class="tab"><a href="/help/changelog">Change Log</a></span>
</div>
<div id="webhosting">

<h2>Registering an I2P Domain</h2>

<p>To obtain your own hostname, I2P domain registrars such as <a href="http://stats.i2p/i2p/addkey.html" target="_blank" rel="noreferrer" class="sitelink">stats.i2p</a> or <a href="http://reg.i2p/" target="_blank" rel="noreferrer" class="sitelink">reg.i2p</a> provide registration services that allow you to register and manage an .i2p hostname or sub-domain which will then be propagated across the network. Some registration sites will require the <i>B64 destination address</i> for the tunnel/service you wish to associate with a hostname, which you should copy from the <i>Local destination</i> section on the <a href="http://127.0.0.1:7657/i2ptunnel/edit?tunnel=3">Tunnel Manager Configuration page</a>.</p>

<p>If a <i>Registration Authentication</i> string is requested, you can find it on the <a href="http://127.0.0.1:7657/i2ptunnel/register?tunnel=3">Registration Authentication page</a> linked from the <a href="http://127.0.0.1:7657/i2ptunnel/edit?tunnel=3">Tunnel Manager Configuration page</a>. Registration authentication ensures only the owner is able to register or otherwise manage the hostname, and is required on stats.i2p and reg.i2p.</p>

<p>If you are in a hurry and can't wait a few hours, you can tell people to use a "jump" address helper redirection service. This will usually work within a few minutes of your registering your hostname on the same site. Test it yourself first by entering <code>http://stats.i2p/cgi-bin/jump.cgi?a=<i>something</i>.i2p</code> into your browser. Once it is working, you can tell others to use it.</p>

<p>Alternatively, you can copy the <i>address helper link</i> for your domain, indicated either on the addressbook list page, or on the details page for your domain e.g. <a href="http://127.0.0.1:7657/susidns/details?h=skank.i2p&amp;book=router" target="_blank" rel="noreferrer">details for i2p-projekt.i2p</a>, and paste the link where it's required to share it with others.</p>

<h3>Backup your private key!</h3>

<p>When a new service tunnel is created, or a service is run for the first time (e.g. the default jetty webserver, plugins), a private encryption key is created which essentially controls the ownership of address. This is usually named <code>&hellip;privKey.dat</code>, or <code>eepPriv.dat</code>, though it's a good idea to change the name to indicate the service it belongs to and then respecify it in the Tunnel Manager server settings. This .dat file should be backed up safely; lose it and you will lose ownership and control of your hostname!</p>

<p>To migrate the key to a new router, or to setup multihoming (running the same service on multiple routers for redundancy and load-balancing), copy the backed up key to your .i2p directory and specify it in the new server tunnel, or replace the default key in the plugin folder (e.g. the zzzot plugin).</p>

</div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>