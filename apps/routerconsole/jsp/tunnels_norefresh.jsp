<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("tunnel summary")%>
</head>
<body id="routertunnels">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Tunnel Summary")%></h1>
<div class="main" id="tunnels">
<jsp:useBean class="net.i2p.router.web.helpers.TunnelHelper" id="tunnelHelper" scope="request" />
<jsp:setProperty name="tunnelHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelHelper" property="tunnelSummary" />
</div>
<!--
<script nonce="<%=cspNonce%>" type="text/javascript">
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/tunnels?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("routertunnels").innerHTML = xhr.responseText;
      }
    }
    xhr.send();
  }, 15000);
</script>
-->
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
