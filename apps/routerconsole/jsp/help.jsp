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
<title>Help and Support - I2P+</title>
<%@include file="css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1><%=intl._t("Help &amp; Support")%></h1>
<div class="main" id="help">

<div class="confignav">
<span class="tab"><a href="#sidebarhelp"><%=intl._t("Sidebar")%></a></span>
<span class="tab"><a href="#configurationhelp">Configuration</a></span>
<span class="tab"><a href="#reachabilityhelp"><%=intl._t("Reachability")%></a></span>
<span class="tab"><a href="#reseedhelp">Reseeding</a></span>
<span class="tab"><a href="#advancedsettings">Advanced Settings</a></span>
<span class="tab"><a href="#faq"><%=intl._t("FAQ")%></a></span>
<span class="tab"><a href="#troubleshoot">Troubleshoot</a></span>
<span class="tab"><a href="#legal">Legal</a></span>
<span class="tab"><a href="#changelog">Change Log</a></span>
</div>

<div id="volunteer"><%@include file="help.jsi" %></div>
<div id="sidebarhelp"><%@include file="help-sidebar.jsi" %></div>
<div id="configurationhelp"><%@include file="help-configuration.jsi" %></div>
<div id="reachabilityhelp"><%@include file="help-reachability.jsi" %></div>
<div id="reseedhelp"><%@include file="help-reseed.jsi" %></div>
<div id="advancedsettings"><%@include file="help-advancedsettings.jsi" %></div>
<div id="faq"><%@include file="help-faq.jsi" %></div>
<div id="troubleshoot"><%@include file="help-troubleshoot.jsi" %></div>
<div id="legal"><%@include file="help-legal.jsi" %></div>
<div id="changelog">
<h2>Change Log</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="768" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />
<p id="fullhistory"><a href="/history.txt" target="_blank">View the full change log</a></p>
</div>

</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
