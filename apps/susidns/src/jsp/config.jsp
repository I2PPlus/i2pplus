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
<%@include file="headers.jsi" %>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html" %>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application"/>
<jsp:useBean id="cfg" class="i2p.susi.dns.ConfigBean" scope="session"/>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="cfg" property="*" />
<% cfg.storeMethod(request.getMethod()); %>
<!DOCTYPE html>
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title><%=intl._t("configuration")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="<%=base.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<script src="/js/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script src="js/messages.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head>
<body>
<div class="page">
<hr>
<div id="navi">
<a id="overview" href="index"><%=intl._t("Overview")%></a>&nbsp;
<a class="abook" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook" href="addressbook?book=local&amp;filter=none"><%=intl._t("Local")%></a>&nbsp;
<a class="abook" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="config" class="active" href="config"><%=intl._t("Configuration")%></a>
</div>
<hr>
<div class="headline" id="configure">
<h3><%=intl._t("Configuration")%></h3>
<h4><%=intl._t("File location")%>: ${cfg.fileName}</h4>
</div>
<div id="messages">${cfg.messages}</div>
<form method="POST" action="config">
<div id="config">
<input type="hidden" name="serial" value="${cfg.serial}" >
<textarea name="config" rows="10" cols="80" spellcheck="false">${cfg.config}</textarea>
</div>
<div id="buttons">
<input class="reload" type="submit" name="action" value="<%=intl._t("Reload")%>" >
<input class="accept" type="submit" name="action" value="<%=intl._t("Save")%>" >
</div>
</form>
<div class="help" id="helpconfig">
<h3><%=intl._t("Hints")%></h3>
<ol>
<li>
<%=intl._t("File and directory paths here are relative to the address book's working directory, which is normally ~/.i2p/addressbook/ (Linux) or %LOCALAPPDATA%\\I2P\\addressbook\\ (Windows).")%>
</li>
<li>
<%=intl._t("If you want to manually add lines to an address book, add them to the private or local address books.")%>
<%=intl._t("The router address book and the published address book are updated by the address book application.")%>
</li>
<li>
<%=intl._t("When you publish your address book, ALL destinations from the local and router address books appear there.")%>
<%=intl._t("Use the private address book for private destinations, these are not published.")%>
</li>
</ol>
<h3><%=intl._t("Options")%></h3>
<ul>
<li><b>subscriptions</b> -
<%=intl._t("File containing the list of subscriptions URLs (no need to change)")%>
</li>
<li><b>update_delay</b> -
<%=intl._t("Update interval in hours")%>
</li>
<li><b>published_addressbook</b> -
<%=intl._t("Your public hosts.txt file (choose a path within your webserver document root)")%>
</li>
<li><b>router_addressbook</b> -
<%=intl._t("Your hosts.txt (don't change)")%>
</li>
<li><b>local_addressbook</b> -
<%=intl._t("Your personal address book, these hosts will be published")%>
</li>
<li><b>private_addressbook</b> -
<%=intl._t("Your private address book, it is never published")%>
</li>
<li><b>proxy_port</b> -
<%=intl._t("Port for your eepProxy (no need to change)")%>
</li>
<li><b>proxy_host</b> -
<%=intl._t("Hostname for your eepProxy (no need to change)")%>
</li>
<li><b>should_publish</b> -
<%=intl._t("Whether to update the published address book")%>
</li>
<li><b>etags</b> -
<%=intl._t("File containing the etags header from the fetched subscription URLs (no need to change)")%>
</li>
<li><b>last_modified</b> -
<%=intl._t("File containing the modification timestamp for each fetched subscription URL (no need to change)")%>
</li>
<li><b>log</b> -
<%=intl._t("File to log activity to (change to /dev/null if you like)")%>
</li>
<li><b>theme</b> -
<%=intl._t("Name of the theme to use (defaults to 'light')")%>
</li>
</ul>
</div>
<div id="footer">
<hr>
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_top">susi</a> 2005 </p>
</div>
</div>
</body>
</html>
