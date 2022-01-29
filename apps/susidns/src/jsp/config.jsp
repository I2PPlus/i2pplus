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

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");
    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; script-src 'self' 'unsafe-inline'; form-action 'self'; frame-ancestors 'self'; object-src 'none'; media-src 'none'; require-trusted-types-for 'script'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

%>
<% //@include file="headers.jsi" %>
<%@page pageEncoding="UTF-8"%>
<%@ page contentType="text/html" %>
<%@page trimDirectiveWhitespaces="true"%>
<jsp:useBean id="version" class="i2p.susi.dns.VersionBean" scope="application"/>
<jsp:useBean id="cfg" class="i2p.susi.dns.ConfigBean" scope="session"/>
<jsp:useBean id="base" class="i2p.susi.dns.BaseBean" scope="session" />
<jsp:useBean id="intl" class="i2p.susi.dns.Messages" scope="application" />
<jsp:setProperty name="cfg" property="*" />
<!DOCTYPE HTML>
<html>
<head>
<meta charset="utf-8">
<title><%=intl._t("configuration")%> - susidns</title>
<link rel="stylesheet" type="text/css" href="<%=base.getTheme()%>susidns.css?<%=net.i2p.CoreVersion.VERSION%>">
<link rel="stylesheet" type="text/css" href="<%=base.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>">
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body id="cfg">
<style type="text/css">body{opacity: 0;}</style>
<div class="page">
<div id="navi">
<a class="abook router" href="addressbook?book=router&amp;filter=none"><%=intl._t("Router")%></a>&nbsp;
<a class="abook master" href="addressbook?book=master&amp;filter=none"><%=intl._t("Master")%></a>&nbsp;
<a class="abook private" href="addressbook?book=private&amp;filter=none"><%=intl._t("Private")%></a>&nbsp;
<a class="abook published" href="addressbook?book=published&amp;filter=none"><%=intl._t("Published")%></a>&nbsp;
<a id="subs" href="subscriptions"><%=intl._t("Subscriptions")%></a>&nbsp;
<a id="configlink" class="selected" href="config"><%=intl._t("Configuration")%></a>&nbsp;
<a id="overview" href="index"><%=intl._t("Help")%></a>
</div>
<hr>
<div class="headline" id="configure">
<h3><%=intl._t("Configuration")%></h3>
<h4><%=intl._t("File location")%>: <span class="storage">${cfg.fileName}</span></h4>
</div>
<script src="/js/closeMessage.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<div id="messages">${cfg.messages}</div>
<form method="POST" action="config#navi">
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
<li><%=intl._t("File and directory paths here are relative to the addressbook's working directory, which is normally ~/.i2p/addressbook/ (Linux) or %LOCALAPPDATA%\\I2P\\addressbook\\ (Windows).")%></li>
<li><%=intl._t("If you want to manually add lines to an addressbook, add them to the private or master addressbooks.")%>&nbsp;<wbr><%=intl._t("The router addressbook and the published addressbook are updated by the addressbook application.")%></li>
<li><%=intl._t("When you publish your addressbook, ALL destinations from the master and router addressbooks appear there.")%>&nbsp;<wbr><%=intl._t("Use the private addressbook for private destinations, these are not published.")%></li>
</ol>
<h3><%=intl._t("Options")%></h3>
<ul>
<li><b>subscriptions</b> - <%=intl._t("File containing the list of subscriptions URLs (no need to change)")%></li>
<li><b>update_delay</b> - <%=intl._t("Update interval in hours")%></li>
<li><b>published_addressbook</b> - <%=intl._t("Your public hosts.txt file (choose a path within your webserver document root)")%></li>
<li><b>router_addressbook</b> - <%=intl._t("Your hosts.txt (don't change)")%></li>
<li><b>master_addressbook</b> - <%=intl._t("Your personal addressbook, these hosts will be published")%></li>
<li><b>private_addressbook</b> - <%=intl._t("Your private addressbook, it is never published")%> </li>
<li><b>proxy_port</b> - <%=intl._t("Port for your eepProxy (no need to change)")%> </li>
<li><b>proxy_host</b> - <%=intl._t("Hostname for your eepProxy (no need to change)")%> </li>
<li><b>should_publish</b> - <%=intl._t("Whether to update the published addressbook")%> </li>
<li><b>etags</b> - <%=intl._t("File containing the etags header from the fetched subscription URLs (no need to change)")%></li>
<li><b>last_modified</b> - <%=intl._t("File containing the modification timestamp for each fetched subscription URL (no need to change)")%></li>
<li><b>log</b> - <%=intl._t("File to log activity to (change to /dev/null if you like)")%></li>
<li><b>theme</b> - <%=intl._t("Name of override theme to use (defaults to console-selected theme)")%></li>
</ul>
</div>
<div id="footer">
<hr>
<p class="footer">susidns v${version.version} &copy; <a href="${version.url}" target="_top">susi</a> 2005 </p>
</div>
</div>
<span data-iframe-height></span>
<style type="text/css">body{opacity: 1 !important;}</style>
</body>
</html>
