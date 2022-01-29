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
<title>Help Section - I2P+</title>
<%@include file="../css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp">Help &amp; Support</h1>
<div class="main" id="help">
<ul id="helptoc">
<li id="help_configadv"><a href="advancedsettings"><b>Advanced Settings</b></a><br>Advanced configuration settings</li>
<li id="help_changelog"><a href="changelog"><b>Change Log</b></a><br>Recent code changes to I2P+</li>
<li id="help_configuration"><a href="configuration"><b>Configuration</b></a><br>Configuring I2P+: Network, Firefox, Tunnel Manager</li>
<li id="help_faq"><a href="faq"><b>F.A.Q.</b></a><br>Frequently Asked Questions</b></li>
<li id="help_glossary"><a href="glossary"><b>Glossary</b></a><br>I2P-specific terminology</li>
<li id="help_legal"><a href="legal"><b>Legal</b></a><br>Licensing and copyright notices</li>
<li id="help_newusers"><a href="newusers"><b>New User Guide</b></a><br>A gentle introduction to I2P</li>
<li id="help_reseed"><a href="reseed"><b>Reseeding</b></a><br>A guide to reseeding your I2P+ router</li>
<li id="help_troubleshoot"><a href="troubleshoot"><b>Troubleshooting</b></a><br>What to do in the event I2P isn't working</li>
<li id="help_filter"><a href="tunnelfilter"><b>Tunnel Filtering</b></a><br>An introduction to tunnel access lists</li>
<li id="help_ui"><a href="ui"><b>User Interface</b></a><br>Information about the sidebar, network status messages, and theme overrides </li>
<li id="help_webhosting"><a href="webhosting"><b>Webhosting on I2P</b></a><br>An introduction to hosting websites on the I2P network</li>
</ul>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>