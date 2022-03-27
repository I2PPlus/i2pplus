<%
/*
 * Created on Sep 02, 2005
 *
 *  This file is part of susidns project, see http://susi.i2p/
 *
 *  Copyright (C) 2005 <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * $Revision: 1.2 $
 */
%>
<%@include file="headers.jsi"%>
<%@page pageEncoding="UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@ page contentType="text/html"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<!DOCTYPE HTML>
<html>
<head>
<link rel="preload" href="images/how.svg" as="object">
<meta charset="utf-8">
<title><%=intl._t("Introduction")%> - SusiDNS</title>
<link rel="stylesheet" type="text/css" href="<%=base.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<link rel="stylesheet" type="text/css" href="<%=base.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>">
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body id="ovrvw">
<style type="text/css">body{opacity:0}</style>
<div class="page">
<div id="navi">
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="configlink" href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id="overview" class="selected" href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div id="content">
<h3><%=intl._t("What is the addressbook?")%></h3>
<p>
<%=intl._t("The addressbook application is part of your I2P installation.")%>&nbsp;<wbr>
<%=intl._t("It regularly updates your hosts.txt file from distributed sources or \"subscriptions\".")%>&nbsp;<wbr>
<%=intl._t("Subscribing to additional sites is easy, just add them to your <a href=\"subscriptions\">subscriptions</a> file.")%>&nbsp;<wbr>
<%=intl._t("For more information on naming in I2P, see <a href=\"http://i2p-projekt.i2p/naming.html\" target=\"_blank\">the overview</a>.")%>
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
<div class="illustrate" id="svg">
<% /* load svg via ajax (with noscript fallback) so we can style per theme */ %>
<script nonce="<%=cspNonce%>" type="text/javascript">
var now = Date.now();
xhr = new XMLHttpRequest();
xhr.open("GET","/susidns/images/how.svg?time=" + now, false);
xhr.send("");
document.getElementById("svg").appendChild(xhr.responseXML.documentElement);
</script>
<noscript>
<%  String theme = base.getTheme();
    if (theme.contains("midnight")) { %>
<style type="text/css">.illustrate {border: 1px solid #010011 !important; background: #fff !important; filter: sepia(100%) invert(100%);}</style>
<%  } else if (theme.contains("dark")) { %>
<style type="text/css">.illustrate {border: 1px solid #111 !important; box-shadow: none !important; background: #fff !important; background: rgba(255,255,255,0.3) !important; filter: invert(1) sepia(100%) hue-rotate(30deg);}</style>
<%  } %>
<object type="image/svg+xml" data="images/how.svg?<%=net.i2p.CoreVersion.VERSION%>">
<img src="/themes/susidns/images/how.png" border="0" alt="How the address book works" title="How the address book works" />
</object>
</noscript>
</div>
</div>
</div>
<span data-iframe-height></span>
<style type="text/css">body{opacity: 1 !important;}</style>
</body>
</html>
