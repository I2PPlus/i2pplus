<%@page pageEncoding="UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"%>
<%@include file="headers.jsi"%>
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
<!DOCTYPE html>
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.IndexBean" id="indexBean" scope="request" />
<jsp:setProperty name="indexBean" property="*" />
<html id=tman>
<head>
<script src=/js/setupIframe.js></script>
<title><%=intl._t("Tunnel Manager")%> - <%=(__isClient ? intl._t("Edit Client Tunnel") : intl._t("Edit Server Tunnel"))%></title>
<meta charset=utf-8>
<script src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<link rel=icon href="<%=editBean.getTheme()%>images/favicon.svg">
<link href="<%=editBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel=stylesheet>
<link href="<%=editBean.getTheme()%>../images/images.css?<%=net.i2p.CoreVersion.VERSION%>" rel=stylesheet>
<link href="<%=editBean.getTheme()%>images/images.css?<%=net.i2p.CoreVersion.VERSION%>" rel=stylesheet>
<link href="<%=editBean.getTheme()%>../images/i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel=stylesheet>
<%
  if (indexBean.useSoraFont()) {
%>
<link href="<%=indexBean.getTheme()%>../../fonts/Sora.css" rel=stylesheet>
<%
  }
%>
<link href="<%=editBean.getTheme()%>override.css" rel=stylesheet>
<script src="/js/resetScroll.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/selectAll.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce="<%=cspNonce%>">var deleteMessage = "<%=intl._t("Are you sure you want to delete?")%>";</script>
<script src="js/delete.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<style>body{display:none;pointer-events:none}input.default{width:1px;height:1px;visibility:hidden}</style>
</head>
<body id=tunnelEditPage>
<%
if (__invalid) {
%>
<div id=notReady>Invalid tunnel parameter</div>
<%
} else {
    if (editBean.isInitialized()) {
%>
<form method=POST action="list">
<div class=panel>
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
<div id=notReady><%=intl._t("Initializing Tunnel Manager{0}", "&hellip;")%><noscript><%=intl._t("Tunnels not initialized yet; please retry in a few moments.").replace("yet;", "yet&hellip;<br>")%></noscript></div>
<script nonce="<%=cspNonce%>">
  setInterval(function() {
    const xhrtunnman = new XMLHttpRequest();
    xhrtunnman.open('GET', '/i2ptunnel/', true);
    xhrtunnman.responseType = "text";
    xhrtunnman.onreadystatechange = function () {
      if (xhrtunnman.readyState==4 && xhrtunnman.status==200) {
        document.getElementById("page").innerHTML=xhrtunnman.responseText;
      }
    }
    xhrtunnman.send();
  }, 5000);
</script>
<%
    }  // isInitialized()
}
%>
<span data-iframe-height></span>
<style>body{display:block;pointer-events:auto}</style>
</body>
</html>