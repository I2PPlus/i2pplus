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
<title><%=intl._t("Web Hosting - I2P+")%></title>
</head>
<body>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("Hostname Registration")%></h1>
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
<span class="tab2"><%=intl._t("Hostname Registration")%></span>
<span class="tab"><a href="/help/troubleshoot"><%=intl._t("Troubleshoot")%></a></span>
<span class="tab"><a href="/help/glossary"><%=intl._t("Glossary")%></a></span>
<span class="tab"><a href="/help/legal"><%=intl._t("Legal")%></a></span>
<span class="tab"><a href="/help/changelog"><%=intl._t("Change Log")%></a></span>
</div>
<div id="webhosting">

<h2><%=intl._t("Registering an I2P Domain")%></h2>

<p><%=intl._t("To obtain your own hostname, I2P domain registrars such as <a href=http://stats.i2p/i2p/addkey.html target=_blank rel=noreferrer class=sitelink>stats.i2p</a> or <a href=http://reg.i2p/ target=_blank rel=noreferrer class=sitelink>reg.i2p</a> provide registration services that allow you to register and manage an .i2p hostname or sub-domain which will then be propagated across the network. Some registration sites will require the <i>B64 destination address</i> for the tunnel/service you wish to associate with a hostname, which you should copy from the <i>Local destination</i> section on the <a href='http://127.0.0.1:7657/i2ptunnel/edit?tunnel=3'>Tunnel Manager Configuration page</a>.")%></p>

<p><%=intl._t("If a <i>Registration Authentication</i> string is requested, you can find it on the <a href='http://127.0.0.1:7657/i2ptunnel/register?tunnel=3'>Registration Authentication page</a> linked from the <a href='http://127.0.0.1:7657/i2ptunnel/edit?tunnel=3'>Tunnel Manager Configuration page</a>. Registration authentication ensures only the owner is able to register or otherwise manage the hostname, and is required on stats.i2p and reg.i2p.")%></p>

<p><%=intl._t("If you are in a hurry and can't wait a few hours, you can tell people to use a \"jump\" address helper redirection service. This will usually work within a few minutes of your registering your hostname on the same site. Test it yourself first by entering <code>http://stats.i2p/cgi-bin/jump.cgi?a=<i>yourchosenhostname.i2p</i></code> into your browser. Once it is working, you can tell others to use it.")%></p>

<p><%=intl._t("Alternatively, you can copy the <i>address helper link</i> for your domain, indicated either on the addressbook list page, or on the details page for your domain e.g. <a href='http://127.0.0.1:7657/susidns/details?h=skank.i2p&amp;book=router' target=_blank rel=noreferrer>details for i2p-projekt.i2p</a>, and paste the link where it's required to share it with others.")%></p>

<h3><%=intl._t("Backup your private key!")%></h3>

<p><%=intl._t("When a new service tunnel is created, or a service is run for the first time (e.g. the default jetty webserver, plugins), a private encryption key is created which essentially controls the ownership of address. This is usually named <code>&hellip;privKey.dat</code>, or <code>eepPriv.dat</code>, though it's a good idea to change the name to indicate the service it belongs to and then respecify it in the Tunnel Manager server settings. This .dat file should be backed up safely; lose it and you will lose ownership and control of your hostname!")%></p>

<p><%=intl._t("To migrate the key to a new router, or to setup multihoming (running the same service on multiple routers for redundancy and load-balancing), copy the backed up key to your .i2p directory and specify it in the new server tunnel, or replace the default key in the plugin folder (e.g. the zzzot plugin).")%></p>

</div>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
<%@include file="../summaryajax.jsi" %>
</body>
</html>