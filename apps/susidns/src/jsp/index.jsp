<%
/*
 * This file is part of SusiDNS project for I2P
 * Created on Sep 02, 2005
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
%>
<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<%@include file="headers.jsi"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<%
    boolean overrideCssActive = base.isOverrideCssActive();
    String theme = base.getTheme().replace("/themes/susidns/", "").replace("/", "");
    theme = "\"" + theme + "\"";
%>
<!DOCTYPE HTML>
<html>
<head>
<script src=/js/setupIframe.js></script>
<link rel=preload href="images/how.svg" as="object">
<meta charset=utf-8>
<meta name=viewport content="width=device-width, initial-scale=1">
<title><%=intl._t("Introduction")%> - SusiDNS</title>
<link rel=stylesheet href="<%=base.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<%  if (base.useSoraFont()) { %><link href="<%=base.getTheme()%>../../fonts/Sora.css" rel=stylesheet><% } else { %>
<link href="<%=base.getTheme()%>../../fonts/OpenSans.css" rel=stylesheet><% } %>
<%  if (overrideCssActive) { %><link rel=stylesheet href="<%=base.getTheme()%>override.css"><% } %>
<script src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/updatedEvent.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<style>body{display:none;pointer-events:none}</style>
<script nonce="<%=cspNonce%>">const theme = <%=theme%>;</script>
</head>
<body id=ovrvw>
<div id=page>
<div id=navi>
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id=subs href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id=configlink href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id=overview class=selected href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div id=content>
<h3><%=intl._t("What is the addressbook?")%></h3>
<p>
<%=intl._t("The addressbook application is part of your I2P installation.")%>&nbsp;<wbr>
<%=intl._t("It regularly updates your hosts.txt file from distributed sources or \"subscriptions\".")%>&nbsp;<wbr>
<%=intl._t("Subscribing to additional sites is easy, just add them to your <a href=\"subscriptions\">subscriptions</a> file.")%>&nbsp;<wbr>
<%=intl._t("For more information on naming in I2P, see <a href=\"http://i2p-projekt.i2p/naming.html\" target=_blank>the overview</a>.")%>
</p>
<h3><%=intl._t("How does the addressbook application work?")%></h3>
<p>
<%=intl._t("The addressbook application regularly polls your subscriptions and merges their content into your \"router\" address book.")%>&nbsp;<wbr>
<%=intl._t("Then it merges your \"master\" address book into the router address book as well.")%>&nbsp;<wbr>
<%=intl._t("If configured, the router address book is now written to the \"published\" address book, which will be publicly available if you are running an eepsite.")%>
</p>
<p>
<%=intl._t("The router also uses a private address book, which is not merged or published.")%>&nbsp;<wbr>
<%=intl._t("Hosts in the private address book can be accessed by you but their addresses are never distributed to others.")%>&nbsp;<wbr>
<%=intl._t("The private address book can also be used for aliases of hosts in your other address books.")%>
</p>
<div class=illustrate id=svg>
<% /* load svg via ajax (with noscript fallback) so we can style per theme */ %>
<script nonce="<%=cspNonce%>">
  const xhrdns = new XMLHttpRequest();
  xhrdns.open("GET","/susidns/images/how.svg", false);
  xhrdns.send();
  document.getElementById("svg").appendChild(xhrdns.responseXML.documentElement);
</script>
<noscript>
<%  if (theme.contains("midnight")) { %>
<style>.illustrate{border:1px solid #010011!important;background:#fff!important;filter:sepia(100%) invert(100%)}</style>
<%  } else if (theme.contains("dark")) { %>
<style>.illustrate{border:1px solid #111!important;box-shadow:none!important;background:#fff!important;background:rgba(255,255,255,0.3)!important;filter:invert(1) sepia(100%) hue-rotate(30deg)}</style>
<%  } %>
<object type="image/svg+xml" data="images/how.svg?<%=net.i2p.CoreVersion.VERSION%>">
<img src="/themes/susidns/images/how.png" border="0" alt="How the address book works" title="How the address book works" />
</object>
</noscript>
</div>
</div>
</div>
<span data-iframe-height></span>
<style>body{display:block;pointer-events:auto}</style>
</body>
</html>