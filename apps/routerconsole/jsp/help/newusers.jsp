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
<title><%=intl._t("New User Guide")%> - I2P+</title>
</head>
<body>
<%@include file="../summary.jsi" %>
<h1 class="hlp"><%=intl._t("New User Guide")%></h1>
<div class=main id="help">
<div class="confignav">
<span class="tab"><a href="/help/configuration"><%=intl._t("Configuration")%></a></span>
<span class="tab"><a href="/help/advancedsettings"><%=intl._t("Advanced Settings")%></a></span>
<span class="tab"><a href="/help/ui"><%=intl._t("User Interface")%></a></span>
<span class="tab"><a href="/help/reseed"><%=intl._t("Reseeding")%></a></span>
<span class="tab"><a href="/help/tunnelfilter"><%=intl._t("Tunnel Filtering")%></a></span>
<span class="tab"><a href="/help/faq"><%=intl._t("FAQ")%></a></span>
<span class="tab2"><%=intl._t("New User Guide")%></span>
<span class="tab"><a href="/help/webhosting"><%=intl._t("Web Hosting")%></a></span>
<span class="tab"><a href="/help/hostnameregistration"><%=intl._t("Hostname Registration")%></a></span>
<span class="tab"><a href="/help/troubleshoot"><%=intl._t("Troubleshoot")%></a></span>
<span class="tab"><a href="/help/glossary"><%=intl._t("Glossary")%></a></span>
<span class="tab"><a href="/help/legal"><%=intl._t("Legal")%></a></span>
<span class="tab"><a href="/help/changelog"><%=intl._t("Change Log")%></a></span>
</div>
<div id="newuserguide">
<h2><%=intl._t("Introduction to I2P")%></h2>
<jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "docs/readme/readme.html"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:setProperty name="contenthelper" property="contextId" value="<%=i2pcontextId%>" />
 <jsp:getProperty name="contenthelper" property="content" />
</div>
</div>
<script nonce="<%=cspNonce%>" type=text/javascript>window.addEventListener("DOMContentLoaded", progressx.hide());</script>
<%@include file="../summaryajax.jsi" %>
</body>
</html>