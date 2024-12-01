<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<%@ page buffer="32kb" %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    String pageTitlePrefix = "";
    if (ctx.getProperty("routerconsole.lang") != null) {
        lang = ctx.getProperty("routerconsole.lang");
    }
    if (ctx.getProperty("routerconsole.pageTitlePrefix") != null) {
        pageTitlePrefix = ctx.getProperty("routerconsole.pageTitlePrefix");
    }
%>
<html lang="<%=lang%>">
<head>
<%@include file="../head.jsi" %>
<title><%=pageTitlePrefix%> <%=intl._t("Licensing Information")%> - I2P+</title>
</head>
<body>
<%@include file="../summary.jsi" %>
<h1 class=hlp><%=intl._t("Legal &amp; Licensing")%></h1>
<div class=main id=help>
<div class=confignav>
<span class=tab><a href="/help/configuration" title="<%=intl._t("Configuration")%>"><%=intl._t("Configuration")%></a></span>
<span class=tab><a href="/help/advancedsettings" title="<%=intl._t("Advanced Settings")%>"><%=intl._t("Advanced Settings")%></a></span>
<span class=tab><a href="/help/ui" title="<%=intl._t("User Interface")%>"><%=intl._t("User Interface")%></a></span>
<span class=tab><a href="/help/reseed" title="<%=intl._t("Reseeding")%>"><%=intl._t("Reseeding")%></a></span>
<span class=tab><a href="/help/tunnelfilter" title="<%=intl._t("Tunnel Filtering")%>"><%=intl._t("Tunnel Filtering")%></a></span>
<span class=tab><a href="/help/faq" title="<%=intl._t("Frequently Asked Questions")%>"><%=intl._t("FAQ")%></a></span>
<span class=tab><a href="/help/newusers" title="<%=intl._t("New User Guide")%>"><%=intl._t("New User Guide")%></a></span>
<span class=tab><a href="/help/webhosting" title="<%=intl._t("Web Hosting")%>"><%=intl._t("Web Hosting")%></a></span>
<span class=tab><a href="/help/hostnameregistration" title="<%=intl._t("Hostname Registration")%>"><%=intl._t("Hostname Registration")%></a></span>
<span class=tab><a href="/help/troubleshoot" title="<%=intl._t("Troubleshoot")%>"><%=intl._t("Troubleshoot")%></a></span>
<span class=tab><a href="/help/glossary" title="<%=intl._t("Glossary")%>"><%=intl._t("Glossary")%></a></span>
<span class=tab2 id=leg><span><%=intl._t("Legal")%></span></span>
<span class=tab><a href="/help/changelog"><%=intl._t("Change Log")%></a></span>
</div>
<div id=legal><%@include file="../help-legal.jsi" %></div>
</div></body>
</html>