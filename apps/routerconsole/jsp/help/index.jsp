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
<title><%=intl._t("Help Section")%> - I2P+</title>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("Help &amp; Support")%></h1>
<div class="main" id="help">
<ul id="helptoc">
<li id="help_configadv"><a href="advancedsettings"><b><%=intl._t("Advanced Settings")%></b></a><br><%=intl._t("Advanced configuration settings")%></li>
<li id="help_changelog"><a href="changelog"><b><%=intl._t("Change Log")%></b></a><br><%=intl._t("Recent code changes to I2P+")%></li>
<li id="help_configuration"><a href="configuration"><b><%=intl._t("Configuration")%></b></a><br><%=intl._t("Configuring I2P+: Network, Firefox, Tunnel Manager")%></li>
<li id="help_faq"><a href="faq"><b><%=intl._t("F.A.Q.")%></b></a><br><%=intl._t("Frequently Asked Questions")%></b></li>
<li id="help_glossary"><a href="glossary"><b><%=intl._t("Glossary")%></b></a><br>I<%=intl._t("2P-specific terminology")%></li>
<li id="help_legal"><a href="legal"><b><%=intl._t("Legal")%></b></a><br><%=intl._t("Licensing and copyright notices")%></li>
<li id="help_newusers"><a href="newusers"><b><%=intl._t("New User Guide")%></b></a><br><%=intl._t("A gentle introduction to I2P")%></li>
<li id="help_reseed"><a href="reseed"><b><%=intl._t("Reseeding")%></b></a><br><%=intl._t("A guide to reseeding your I2P+ router")%></li>
<li id="help_troubleshoot"><a href="troubleshoot"><b><%=intl._t("Troubleshooting")%></b></a><br><%=intl._t("What to do in the event I2P+ isn't working")%></li>
<li id="help_filter"><a href="tunnelfilter"><b><%=intl._t("Tunnel Filtering")%></b></a><br><%=intl._t("An introduction to tunnel access lists")%></li>
<li id="help_ui"><a href="ui"><b><%=intl._t("User Interface")%></b></a><br><%=intl._t("Information about the sidebar, network status messages, and theme overrides")%></li>
<li id="help_webhosting"><a href="webhosting"><b><%=intl._t("Webhosting on I2P")%></b></a><br><%=intl._t("An introduction to hosting websites on the I2P network")%></li>
</ul>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>