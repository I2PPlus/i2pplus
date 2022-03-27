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
 * $Revision: 1.1 $
 */
%>
<%@include file="headers.jsi"%>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html" %>
<%@page trimDirectiveWhitespaces="true"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="log" class="i2p.susi.dns.LogBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="log" property="*" />
<!DOCTYPE HTML>
<html>
<head>
<meta charset="utf-8">
<title><%=intl._t("subscription log")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="<%=log.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<link rel="stylesheet" type="text/css" href="<%=log.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>">
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body id="subsLog">
<style type="text/css">body{opacity: 0;}</style>
<div class="page">
<div id="navi">
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" class="selected" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="configlink" href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id="overview" href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div class="headline" id="subscriptions">
<h3><%=intl._t("Subscription Log")%></h3>
<h4><%=intl._t("File location")%>: <span class="storage">${log.logName}</span></h4>
</div>
<script src="/js/closeMessage.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<div id="messages">${log.messages}</div>
<div id="config">
<pre>${log.logged}</pre>
</div>
</div>
<span data-iframe-height></span>
<style type="text/css">body{opacity: 1 !important;}</style>
</body>
</html>
