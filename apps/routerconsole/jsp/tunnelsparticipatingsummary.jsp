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
<html lang="<%=lang%>" id="participatingTunnels">
<head>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
<%=intl.title("Transit Tunnels Summary")%>
</head>
<body id="routertunnels">
<script nonce="<%=cspNonce%>" type=text/javascript>progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Transit Tunnels Summary")%></h1>
<div class="main" id="tunnels">
<div class="confignav">
<span class="tab" title="Locally hosted tunnels (exploratory and client)"><a href="/tunnels">Local</a></span>
<span class="tab2" title="Transit tunnel usage by router"><%=intl._t("Transit Summary")%></span>
<span class="tab"><a href="/tunnelsparticipating"><%=intl._t("Transit Tunnels")%></a></span>
<span class="tab"><a href="/tunnelpeercount"><%=intl._t("Tunnel Count by Peer")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TransitSummaryHelper" id="transitSummaryHelper" scope="request" />
<jsp:setProperty name="transitSummaryHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    transitSummaryHelper.storeWriter(out);
%>
<jsp:getProperty name="transitSummaryHelper" property="transitSummary" />
</div>
<script nonce="<%=cspNonce%>" src="/js/lazyload.js" type=text/javascript></script>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.dotsep.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.natural.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" type=text/javascript>
  var main = document.getElementById("tunnels");
  var peers = document.getElementById("transitPeers");
  var summary = document.getElementById("transitSummary");
  var visibility = document.visibilityState;
  var xhrtunnels = new XMLHttpRequest();
  if (summary) {var sorter = new Tablesort((summary), {descending: true});}

  setInterval(function () {
    xhrtunnels.open('GET', '/tunnelsparticipatingsummary?' + new Date().getTime(), true);
    xhrtunnels.responseType = "document";
    xhrtunnels.onreadystatechange = function () {
      if (xhrtunnels.readyState === 4 && xhrtunnels.status === 200) {
        var mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
        var peersResponse = xhrtunnels.responseXML.getElementById("transitPeers");
        if ((peers && peersResponse && peers !== peersResponse) || peers && peersResponse) {
          peers.innerHTML = peersResponse.innerHTML;
          sorter.refresh();
        } else if ((mainResponse && main !== mainResponse) || peersReponse !== null && !peers) {
          main.innerHTML = mainResponse.innerHTML;
        }
      }
    }
    if (visibility === "visible") {
      if (summary) {sorter.refresh();}
      xhrtunnels.send();
    }
  }, 15000);

  window.addEventListener("DOMContentLoaded", progressx.hide());
</script>
</body>
</html>