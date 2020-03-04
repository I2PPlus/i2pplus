<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html id="count">
<head>
<%@include file="css.jsi" %>
<%@include file="csp-unsafe.jsi" %>
<%=intl.title("tunnel peer count")%>
<%@include file="summaryajax.jsi" %>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type="text/javascript"></script>
</head>
<body id="routertunnels">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Tunnel Count by Peer")%></h1>
<div class="main" id="tunnels">
<div class="confignav"><span class="tab"><a href="/tunnels">Tunnel Summary</a></span> <span class="tab2">Tunnel Count by Peer</span></div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelPeerCountHelper" id="tunnelPeerCountHelper" scope="request" />
<jsp:setProperty name="tunnelPeerCountHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelPeerCountHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelPeerCountHelper" property="tunnelPeerCount" />
<script nonce=" + cspNonce + " type="text/javascript">new Tablesort(document.getElementById("tunnelPeerCount"));</script>
</div>
<!--
<script nonce="<%=cspNonce%>" type="text/javascript">
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/tunnelpeercount?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("routertunnels").innerHTML = xhr.responseText;
      }
    }
    xhr.send();
    new Tablesort(document.getElementById("tunnelPeerCount"));
  }, 15000);
</script>
-->
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
