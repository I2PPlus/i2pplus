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
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; script-src 'self' 'unsafe-inline'; form-action 'self'; frame-ancestors 'self'; object-src 'none'; media-src 'none'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

%>
<% //@include file="headers.jsi" %>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:useBean id="subs" class="i2p.susi.dns.SubscriptionsBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="subs" property="*" />
<!DOCTYPE HTML>
<html>
<head>
<meta charset="utf-8">
<title><%=intl._t("subscriptions")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="<%=subs.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<link rel="stylesheet" type="text/css" href="<%=subs.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>">
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body id="sbs">
<style type="text/css">body{opacity: 0;}</style>
<div class="page">
<div id="navi">
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" class="selected" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="configlink" href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id="overview" href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div class="headline" id="subscriptions">
<h3><%=intl._t("Subscriptions")%>&nbsp;&nbsp;<span><a href="log.jsp">View Log</a></span></h3>
<h4><%=intl._t("File location")%>: <span class="storage">${subs.fileName}</span></h4>
</div>
<script src="/js/closeMessage.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<div id="messages">${subs.messages}</div>
<form method="POST" action="subscriptions#navi">
<div id="content">
<input type="hidden" name="serial" value="${subs.serial}" >
<textarea name="content" rows="10" cols="80">${subs.content}</textarea>
</div>
<div id="buttons">
<input class="update" style="float: left;" type="submit" name="action" value="<%=intl._t("Update")%>" >
<!--<input class="reload" type="submit" name="action" value="<%=intl._t("Reload")%>" >-->
<input class="accept" type="submit" name="action" value="<%=intl._t("Save")%>" >
</div>
</form>
<div class="help" id="helpsubs">
<p class="help">
<%=intl._t("The subscription file contains a list of i2p URLs.")%>&nbsp;<wbr>
<%=intl._t("The addressbook application regularly checks this list for new eepsites.")%>&nbsp;<wbr>
<%=intl._t("Those URLs refer to published hosts.txt files.")%>&nbsp;<wbr>
<a href="/help#addressbooksubs" target="_top"><%=intl._t("See the FAQ for a list of subscription URLs.")%></a>
</p>
</div>
<div id="footer">
<hr>
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_top">susi</a> 2005</p>
</div>
</div>
<span data-iframe-height></span>
<style type="text/css">body{opacity: 1 !important;}</style>
</body>
</html>
