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

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

%>
<%@page pageEncoding="UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@ page contentType="text/html"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title><%=intl._t("Introduction")%> - SusiDNS</title>
<link rel="stylesheet" type="text/css" href="<%=base.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<link rel="stylesheet" type="text/css" href="<%=base.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>">
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body id="ovrvw">
<div class="page">
<div id="navi">
<a class="selected" id="overview" href="index"><%=intl._t("Overview")%></a>&nbsp;
<a class="abook" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="config" href="config"><%=intl._t("Configuration")%></a>
</div>
<hr>
<div id="content">
<h3><%=intl._t("What is the addressbook?")%></h3>
<p>
<%=intl._t("The addressbook application is part of your I2P installation.")%>&nbsp;<wbr>
<%=intl._t("It regularly updates your hosts.txt file from distributed sources or \"subscriptions\".")%>
</p>
<p>
<%=intl._t("In the default configuration, the address book is only subscribed to {0}.", "i2p-projekt.i2p")%>&nbsp;<wbr>
<%=intl._t("Subscribing to additional sites is easy, just add them to your <a href=\"subscriptions\">subscriptions</a> file.")%>
</p>
<p>
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
<% String cspNonce = Integer.toHexString(net.i2p.util.RandomSource.getInstance().nextInt()); %>
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
<style>.illustrate {border: 1px solid #010011 !important; background: #fff !important; filter: sepia(100%) invert(100%);}</style>
<%  } else if (theme.contains("dark")) { %>
<style>.illustrate {border: 1px solid #111 !important; box-shadow: none !important; background: #fff !important; background: rgba(255,255,255,0.3) !important; filter: invert(1) sepia(100%) hue-rotate(30deg);}</style>
<%  } %>
<object type="image/svg+xml" data="images/how.svg">
<img src="/themes/susidns/images/how.png" border="0" alt="How the address book works" title="How the address book works" />
</object>
</noscript>
</div>
</div>
<div id="footer">
<hr>
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_blank">susi</a> 2005</p>
</div>
</div>
<span data-iframe-height></span>
</body>
</html>
