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
%>
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.IndexBean" id="indexBean" scope="request" />
<jsp:setProperty name="indexBean" property="*" />
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en" id="tman">
<head>
<title><%=intl._t("Tunnel Manager")%> - <%=(__isClient ? intl._t("Edit Client Tunnel") : intl._t("Edit Server Tunnel"))%></title>
<meta charset="utf-8">
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<link rel="icon" href="<%=editBean.getTheme()%>images/favicon.svg">
<link href="<%=editBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<link href="<%=editBean.getTheme()%>../images/images.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<link href="<%=editBean.getTheme()%>images/images.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<link href="<%=editBean.getTheme()%>../images/i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<link href="<%=editBean.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css">
<script src="/js/resetScroll.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script src="/js/selectAll.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" type="text/javascript">var deleteMessage = "<%=intl._t("Are you sure you want to delete?")%>";</script>
<script src="js/delete.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head>
<body id="tunnelEditPage">
<style type="text/css">body{opacity:0}input.default{width:1px;height:1px;visibility:hidden}</style>
<%
if (__invalid) {
%>
<div id="notReady">Invalid tunnel parameter</div>
<%
} else {
    if (editBean.isInitialized()) {
%>
<form method="post" action="list">
<div class="panel">
<%
        if (__isClient) {
%>
<%@include file="editClient.jsi" %>
<%
        } else {
%>
<%@include file="editServer.jsi" %>
<%
        }
%>
</div>
</form>
<%
    } else {
%>
<div id="notReady"><%=intl._t("Initializing Tunnel Manager{0}", "&hellip;")%><noscript><%=intl._t("Tunnels not initialized yet; please retry in a few moments.").replace("yet;", "yet&hellip;<br>")%></noscript></div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/i2ptunnel/?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("page").innerHTML=xhr.responseText;
      }
    }
    xhr.send();
  }, 5000);
</script>
<%
    }  // isInitialized()
}
%>
<span data-iframe-height></span>
<style type="text/css">body {opacity: 1 !important;}</style>
</body>
</html>
