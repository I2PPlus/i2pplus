<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
<%=intl.title("local tunnels")%>
</head>
<body id=routertunnels>
<script nonce=<%=cspNonce%>>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<h1 class=netwrk><%=intl._t("Local Tunnels")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab2 title="Locally hosted tunnels (exploratory and client)">Local</span>
<span class=tab><a href="/transit"><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
<span class=tab><a href="/transitfast"><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</a></span>
<span class=tab title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><a href="/transitsummary"><%=intl._t("Transit by Peer")%></a></span>
<span class=tab><a href="/tunnelpeercount">Tunnel Count by Peer</a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelHelper" id="tunnelHelper" scope="request" />
<jsp:setProperty name="tunnelHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelHelper" property="tunnelSummary" />
</div>
<script nonce=<%=cspNonce%>>
  var visibility = document.visibilityState;
  if (visibility == "visible") {
    setInterval(function() {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/tunnels', true);
      xhr.responseType = "document";
      xhr.onreadystatechange = function () {
        if (xhr.readyState==4 && xhr.status==200) {
          var tunnels = document.getElementById("tunnels");
          var tunnelsResponse = xhr.responseXML.getElementById("tunnels");
          var tunnelsParent = tunnels.parentNode;
          if (!Object.is(tunnels.innerHTML, tunnelsResponse.innerHTML)) {
            tunnelsParent.replaceChild(tunnelsResponse, tunnels);
          }
        }
      }
      xhr.send();
    }, 15000);
  }
</script>
<script nonce=<%=cspNonce%>>window.addEventListener("DOMContentLoaded", progressx.hide());</script>
</body>
</html>