<%
/*
 * This file is part of SusiDNS project for I2P
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
%>
<% response.setHeader("Content-Disposition", "attachment; filename=exported_hosts.txt"); %>
<%@page contentType="text/plain" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<%@include file="headers.jsi"%>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session"/>
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session"/>
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application"/>
<jsp:setProperty name="book" property="*"/>
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<%
    String theme = base.getTheme().replace("/themes/susidns/", "").replace("/", "");
    theme = "\"" + theme + "\"";
%>
<!DOCTYPE HTML>
<html>
<head>
<meta charset=utf-8>
<title><%=intl._t("Export")%> - SusiDNS</title>
<link rel=stylesheet href="<%=base.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<%  if (base.useSoraFont()) { %><link href="<%=base.getTheme()%>../../fonts/Sora.css" rel=stylesheet><% } else { %>
<link href="<%=base.getTheme()%>../../fonts/OpenSans.css" rel=stylesheet><% } %>
</head>
<body>
<div id=navi>
<a href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a href="blacklist"><%=intl._t("Blacklist")%></a>&nbsp;
<a href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div id=content>
<h3><%=intl._t("Export")%></h3>
<% book.export(out); %>
</div>
</body>
</html>
