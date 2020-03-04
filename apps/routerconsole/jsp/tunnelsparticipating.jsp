<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html id="participatingTunnels">
<head>
<%@include file="css.jsi" %>
<%@include file="csp-unsafe.jsi" %>
<%=intl.title("participating tunnels")%>
<%@include file="summaryajax.jsi" %>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type="text/javascript"></script>
</head>
<body id="routertunnels">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Participating Tunnels")%></h1>
<div class="main" id="tunnels">
<div class="confignav"><span class="tab" title="Locally hosted tunnels (exploratory and client)"><a href="/tunnels">Local</a></span> <span class="tab2">Participating</span> <span class="tab"><a href="/tunnelpeercount">Tunnel Count by Peer</a></span></div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelParticipatingHelper" id="tunnelParticipatingHelper" scope="request" />
<jsp:setProperty name="tunnelParticipatingHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelParticipatingHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelParticipatingHelper" property="tunnelsParticipating" />
<script nonce=" + cspNonce + " type="text/javascript">new Tablesort(document.getElementById("participating"));</script>
</div>
<!--
<script nonce="<%=cspNonce%>" type="text/javascript">
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/tunnelsparticipating?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("routertunnels").innerHTML = xhr.responseText;
      }
    }
    xhr.send();
    new Tablesort(document.getElementById("participating"));
  }, 15000);
</script>
-->
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
