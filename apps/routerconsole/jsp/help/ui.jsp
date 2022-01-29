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
<title>User Interface Help - I2P+</title>
<%@include file="../css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="../summary.jsi" %>
<h1 class="hlp">User Interface Help</h1>
<div class="main" id="help">
<div class="confignav">
<span class="tab"><a href="/help/configuration">Configuration</a></span>
<span class="tab"><a href="/help/advancedsettings">Advanced Settings</a></span>
<span class="tab2">User Interface</span>
<span class="tab"><a href="/help/reseed">Reseeding</a></span>
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
<p class="infohelp" id="tooltips">Note: Many of the labels and options in the console have tooltips that indicate purpose or explain the meaning. Hover over the label or option to activate the tooltip.</p>
<div id="themeoverride">
<h2>Theme Overrides (I2P+)</h2>
<p>I2P+ supports custom theme overrides for the console, the tunnel manager, I2PSnark, SusiDNS and Susimail, permitting changes to the themes which are persistent after upgrades. In order to activate an override, create a css file named <code>override.css</code> with your theme modifications and place it in the relevant theme directory of the theme you wish to change, or rename one of the example files, and then hard refresh the browser. Note that the tunnel manager and the console share the same override.css. To deactivate an override, delete or rename the <code>override.css</code> file and then hard refresh the browser (usually Control + Shift + R).</p>
<p>The themes directory is located in the I2P application directory under <code>docs/themes</code>. There are example override files in the console and I2PSnark theme sub-directories.</p>
</div>
<div id="sidebarhelp"><%@include file="../help-sidebar.jsi" %></div>
<div id="reachabilityhelp"><%@include file="../help-reachability.jsi" %></div>
</div>
<%@include file="../summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>