<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%=intl.title("local tunnels")%>
</head>
<body id="routertunnels">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Local Tunnels")%></h1>
<div class="main" id="tunnels">
<div class="confignav">
<span class="tab2" title="Locally hosted tunnels (exploratory and client)">Local</span>
<span class="tab"><a href="/tunnelsparticipating">Participating</a></span>
<span class="tab"><a href="/tunnelpeercount">Tunnel Count by Peer</a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelHelper" id="tunnelHelper" scope="request" />
<jsp:setProperty name="tunnelHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelHelper" property="tunnelSummary" />
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var visibility = document.visibilityState;
  if (visibility == "visible") {
    setInterval(function() {
      progressx.show();
      progressx.progress(0.5);
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/tunnels?' + new Date().getTime(), true);
      xhr.responseType = "document";
      xhr.onreadystatechange = function () {
        if (xhr.readyState==4 && xhr.status==200) {
          var tunnels = document.getElementById("tunnels");
          var tunnelsResponse = xhr.responseXML.getElementById("tunnels");
          var tunnelsParent = tunnels.parentNode;
            if (!Object.is(tunnels.innerHTML, tunnelsResponse.innerHTML))
              tunnelsParent.replaceChild(tunnelsResponse, tunnels);
        }
      }
      window.addEventListener("pageshow", progressx.hide());
      xhr.send();
    }, 15000);
  }
</script>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>