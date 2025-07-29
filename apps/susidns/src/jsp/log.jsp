<%
/*
 * This file is part of SusiDNS project for I2P
 * Created on Sep 02, 2005
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
%>
<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@include file="headers.jsi"%>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session"/>
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application"/>
<jsp:useBean id="log" class="i2p.susi.dns.LogBean" scope="session"/>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application"/>
<jsp:setProperty name="log" property="*"/>
<%
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
<title><%=intl._t("subscription log")%> - susidns</title>
<link rel=stylesheet href="<%=log.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<%  if (base.useSoraFont()) { %><link href="<%=base.getTheme()%>../../fonts/Sora.css" rel=stylesheet><% } else { %>
<link href="<%=base.getTheme()%>../../fonts/OpenSans.css" rel=stylesheet><% } %>
<% if (overrideCssActive) { %><link rel=stylesheet href="<%=base.getTheme()%>override.css"><% } %>
<script nonce="<%=cspNonce%>">const theme = <%=theme%>;</script>
</head>
<body id=subsLog style=display:none;pointer-events:none>
<div id=page>
<div id=navi>
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id=subs class=selected href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id=configlink href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id=overview href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div class=headline id=subscriptions>
<h3><%=intl._t("Subscription Log")%>&nbsp;<span><%=intl._t("Most recent (excluding bad domains)")%></span></h3>
<h4><%=intl._t("File location")%>: <span class=storage>${log.logName}</span></h4>
</div>
<div id=messages class=canClose>${log.messages}</div>
<div id=config><ul>${log.logged}</ul></div>
</div>
<span id=newToday hidden><%= log.getTodayEntryCount() %></span>
<span data-iframe-height></span>
<script nonce="<%=cspNonce%>" src="/js/clickToClose.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/updatedEvent.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<style>body{display:block!important;pointer-events:auto!important}</style>
</body>
</html>