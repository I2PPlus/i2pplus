<%
/*
 * This file is part of SusiDNS project for I2P+
 * Created on Dec 16, 2025
 * License: GPL2 or later
 */
%>
<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<%@include file="headers.jsi"%>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session"/>
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application"/>
<jsp:useBean id="blacklist" class="i2p.susi.dns.BlacklistBean" scope="session"/>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application"/>
<jsp:setProperty name="blacklist" property="*"/>
<%
    blacklist.storeMethod(request.getMethod());
    boolean overrideCssActive = base.isOverrideCssActive();
    String theme = base.getTheme().replace("/themes/susidns/", "").replace("/", "");
    theme = "\"" + theme + "\"";
%>
<!DOCTYPE HTML>
<html>
<head>
<script src=/js/setupIframe.js></script>
<meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1">
<title><%=intl._t("blacklist")%> - susidns</title>
<link rel=stylesheet href="<%=blacklist.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<%  if (base.useSoraFont()) { %><link href="<%=base.getTheme()%>../../fonts/Sora.css" rel=stylesheet><% } else { %>
<link href="<%=base.getTheme()%>../../fonts/OpenSans.css" rel=stylesheet><% } %>
<% if (overrideCssActive) { %><link rel=stylesheet href="<%=base.getTheme()%>override.css"><% } %>
<style>body{display:none;pointer-events:none}</style>
<script nonce="<%=cspNonce%>">const theme = <%=theme%>;</script>
</head>
<body id=bls>
<div id=page>
<div id=navi>
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id=subs href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id=blacklist class=selected href="blacklist"><%=intl._t("Blacklist")%></a>&nbsp;
<a id=configlink href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id=overview href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div class=headline id=blacklist>
<h3><%=intl._t("Blacklist Management")%></h3>
<h4><%=intl._t("File location")%>: <span class=storage>${blacklist.fileName}</span></h4>
</div>
<div id=messages class=canClose>${blacklist.messages}</div>
<form method=POST action="blacklist#navi">
<div id=content>
<input type=hidden name="serial" value="${blacklist.serial}" >
<textarea name="content" rows="15" cols="80" placeholder="<%=intl._t("Enter I2P addresses to blacklist (one per line)")%>">${blacklist.content}</textarea>
</div>
<div id=buttons>
<input class=update style=float:left type=submit name=action value="<%=intl._t("Reload")%>">
<input class=accept type=submit name=action value="<%=intl._t("Save")%>">
</div>
</form>
<div class=help id=helpblacklist>
<p class=help>
<%=intl._t("The blacklist file contains a list of I2P addresses that should be blocked from access via the HTTP proxy, and excluded from the address book display.")%>&nbsp;<wbr>
<%=intl._t("Enter hostnames (one per line).")%>
</p>
</div>
</div>
<span data-iframe-height></span>
<style>body{display:block;pointer-events:auto}</style>
<script src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/updatedEvent.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/clickToClose.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</body>
</html>