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
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<%@ page buffer="32kb" %>
<%@ page contentType="text/html"%>
<%@include file="headers.jsi"%>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:useBean id="subs" class="i2p.susi.dns.SubscriptionsBean" scope="session" />
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application" />
<jsp:setProperty name="subs" property="*" />
<!DOCTYPE HTML>
<html>
<head>
<script src=/js/setupIframe.js></script>
<meta charset=utf-8>
<title><%=intl._t("subscriptions")%> - susidns</title>
<link rel=stylesheet href="<%=subs.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<%
    if (base.useSoraFont()) {
%>
<link href="<%=base.getTheme()%>../../fonts/Sora.css" rel=stylesheet>
<%
    }
%>
<link rel=stylesheet href="<%=subs.getTheme()%>override.css">
<script src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/updatedEvent.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<style>body{display:none;pointer-events:none}</style>
</head>
<body id=sbs>
<div id=page>
<div id=navi>
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id=subs class=selected href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id=configlink href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id=overview href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div class=headline id=subscriptions>
<h3><%=intl._t("Subscriptions")%>&nbsp;&nbsp;<span><a href="log.jsp">View Log</a></span></h3>
<h4><%=intl._t("File location")%>: <span class=storage>${subs.fileName}</span></h4>
</div>
<div id=messages>${subs.messages}</div>
<form method=POST action="subscriptions#navi">
<div id=content>
<input type=hidden name="serial" value="${subs.serial}" >
<textarea name="content" rows="10" cols="80">${subs.content}</textarea>
</div>
<div id=buttons>
<input class=update style=float:left type=submit name=action value="<%=intl._t("Update")%>" >
<input class=accept type=submit name=action value="<%=intl._t("Save")%>" >
</div>
</form>
<div class=help id=helpsubs>
<p class=help>
<%=intl._t("The subscription file contains a list of i2p URLs.")%>&nbsp;<wbr>
<%=intl._t("The addressbook application regularly checks this list for new eepsites.")%>&nbsp;<wbr>
<%=intl._t("Those URLs refer to published hosts.txt files.")%>&nbsp;<wbr>
<a href="/help/faq#addressbooksubs" target=_top><%=intl._t("See the FAQ for a list of subscription URLs.")%></a>
</p>
</div>
</div>
<span data-iframe-height></span>
<style>body{display:block;pointer-events:auto}</style>
<script nonce="<%=cspNonce%>" src="/js/closeMessage.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</body>
</html>