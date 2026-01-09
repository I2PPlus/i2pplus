<%
/*
 * This file is part of SusiDNS project for I2P
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */
%>
<% response.setHeader("Content-Disposition", "attachment; filename=exported_hosts.txt"); %>
<%@page contentType="text/plain" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<jsp:useBean id="book" class="i2p.susi.dns.NamingServiceBean" scope="session"/>
<jsp:setProperty name="book" property="resetDeletionMarks" value="1"/>
<% book.export(out); %>
