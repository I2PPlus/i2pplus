<%@include file="headers.jsi"%>
<%@include file="headers-unsafe.jsi"%>
<%@page pageEncoding="UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"%>
<%
boolean __isClient = false;
boolean __invalid = false;
int curTunnel = -1;
String tun = request.getParameter("tunnel");
if (tun != null) {
  try {
    curTunnel = Integer.parseInt(tun);
    __isClient = EditBean.staticIsClient(curTunnel);
  } catch (NumberFormatException nfe) {
    __invalid = true;
  }
} else {
  String type = request.getParameter("type");
  __isClient = EditBean.isClient(type);
}
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en" id="tman">
<head>
    <title><%=intl._t("Tunnel Manager")%> - <%=(__isClient ? intl._t("Edit Client Tunnel") : intl._t("Edit Server Tunnel"))%></title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
    <script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>

    <% if (editBean.allowCSS()) { %>
    <style type="text/css">body {opacity: 0;}</style>
    <link rel="icon" href="<%=editBean.getTheme()%>images/favicon.ico" />
    <link href="<%=editBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css" />
    <link href="<%=editBean.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css" />
    <% }
  %>
<style type='text/css'>input.default{width: 1px; height: 1px; visibility: hidden;}</style>
<script src="/js/resetScroll.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head>
<body id="tunnelEditPage">
<%
if (__invalid) {
    %><div id="notReady">Invalid tunnel parameter</div><%
} else {
    if (editBean.isInitialized()) {
%>
  <form method="post" action="list">
    <div class="panel">
<%
        if (__isClient) {
            %><%@include file="editClient.jsi" %><%
        } else {
            %><%@include file="editServer.jsi" %><%
        }
%>
    </div>
  </form>
<%
    } else {
        %><div id="notReady"><%=intl._t("Tunnels not initialized yet; please retry in a few moments.").replace("yet;", "yet&hellip;<br>")%></div><%
    }  // isInitialized()
}
%>
<span data-iframe-height></span>
<style type="text/css">body {opacity: 1 !important;}</style>
</body>
</html>
