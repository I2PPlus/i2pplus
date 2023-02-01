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
<%=intl.title("participating tunnels")%>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.natural.js" type=text/javascript></script>
</head>
<body id="routertunnels">
<script nonce="<%=cspNonce%>" type=text/javascript>progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Participating Tunnels")%></h1>
<div class="main" id="tunnels">
<div class="confignav">
<span class="tab" title="Locally hosted tunnels (exploratory and client)"><a href="/tunnels">Local</a></span>
<span class="tab2"><%=intl._t("Transit")%></span>
<span class="tab" title="Top 50 transit tunnel peers"><a href="/tunnelsparticipatingsummary"><%=intl._t("Transit Count by Peer")%></a></span>
<span class="tab"><a href="/tunnelpeercount">Tunnel Count by Peer</a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelParticipatingHelper" id="tunnelParticipatingHelper" scope="request" />
<jsp:setProperty name="tunnelParticipatingHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    tunnelParticipatingHelper.storeWriter(out);
%>
<jsp:getProperty name="tunnelParticipatingHelper" property="tunnelsParticipating" />
<script nonce="<%=cspNonce%>" type=text/javascript>
  import {onVisible} from "/js/onVisible.js";
  var main = document.getElementById("tunnels");
  var tunnels = document.getElementById("tunnels_part");
  var tunnels = document.getElementById("tunnels_part");
  var refresh = document.getElementById("refreshPage");
  var xhrtunnels = new XMLHttpRequest();
  var visible = document.visibilityState;
  if (tunnels) {var sorter = new Tablesort((tunnels), {descending: true});}

  function initRefresh() {
    var timerId = setInterval(updateTunnels, 60000););
    updateTunnels();
  }

  function removeHref() {
    if (refresh) {refresh.removeAttribute("href");}
  }

  function updateTunnels() {
    xhrtunnels.open('GET', '/tunnelsparticipating?' + new Date().getTime(), true);
    xhrtunnels.responseType = "document";
    xhrtunnels.onreadystatechange = function () {
      if (xhrtunnels.readyState === 4 && xhrtunnels.status === 200) {
        var tunnelsResponse = xhrtunnels.responseXML.getElementById("tunnels_part");
        var mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
        if (tunnels && tunnelsResponse && tunnels !== tunnelsResponse) {
          tunnels.innerHTML = tunnelsResponse.innerHTML;
          sorter.refresh();
        } else if (!tunnels) {
          main.innerHTML = mainResponse.innerHTML;
        }
      }
    }
    if (tunnels) {sorter.refresh();}
    removeHref();
    xhrtunnels.send();
  }
  if (refresh) {
    refresh.addEventListener("click", updateTunnels);
    refresh.addEventListener("mouseover", removeHref);
  }
  window.addEventListener("DOMContentLoaded", progressx.hide());
  onVisible(main, () => {initRefresh();});
</script>
</div>
<script nonce="<%=cspNonce%>" src="/js/lazyload.js" type=text/javascript></script>
</body>
</html>