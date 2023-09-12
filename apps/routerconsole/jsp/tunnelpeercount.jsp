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
<html lang="<%=lang%>" id=count>
<head>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
<%=intl.title("tunnel peer count")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body id=routertunnels>
<script nonce=<%=cspNonce%>>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<h1 class=netwrk><%=intl._t("Tunnel Count by Peer")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="<%=intl._t("Locally hosted tunnels (exploratory and client)")%>"><a href="/tunnels">Local</a></span>
<span class=tab><a href="/tunnelsparticipating"><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
<span class=tab><a href="/tunnelsparticipatingfastest"><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</a></span>
<span class=tab title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><a href="/tunnelsparticipatingsummary"><%=intl._t("Transit by Peer")%></a></span>
<span class=tab2><%=intl._t("Tunnel Count by Peer")%></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelPeerCountHelper" id="tunnelPeerCountHelper" scope="request" />
<jsp:setProperty name="tunnelPeerCountHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelPeerCountHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelPeerCountHelper" property="tunnelPeerCount" />
</div>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.natural.js></script>
<script nonce=<%=cspNonce%> type=module>
  import {onVisible} from "/js/onVisible.js";
  var main = document.getElementById("tunnels");
  var peers = document.getElementById("allPeers");
  var tunnels = document.getElementById("tunnelPeerCount");
  var refresh = document.getElementById("refreshPage");
  var xhrtunnels = new XMLHttpRequest();
  var visible = document.visibilityState;
  if (tunnels) {var sorter = new Tablesort((tunnels), {descending: true});}
  function initRefresh() {
    if (refreshId) {
      clearInterval(refreshId);
    }
    var refreshId = setInterval(updateTunnels, 60000);
    if (tunnels && sorter === null) {
      var sorter = new Tablesort((tunnels), {descending: true});
      removeHref();
    }
    addSortListeners();
    updateTunnels();
  }
  function removeHref() {
    if (refresh) {refresh.removeAttribute("href");}
  }
  function addSortListeners() {
    if (tunnels) {
      tunnels.addEventListener('beforeSort', function() {progressx.show();progressx.progress(0.5);}, true);
      tunnels.addEventListener('afterSort', function() {progressx.hide();}, true);
    }
  }
  function updateTunnels() {
    xhrtunnels.open('GET', '/tunnelpeercount?t=' + new Date().getTime(), true);
    xhrtunnels.responseType = "document";
    xhrtunnels.onreadystatechange = function () {
      if (xhrtunnels.readyState === 4 && xhrtunnels.status === 200) {
        var mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
        var peersResponse = xhrtunnels.responseXML.getElementById("allPeers");
        if (peersResponse) {
          addSortListeners();
          if (peers && peers !== peersResponse) {
            peers.innerHTML = peersResponse.innerHTML;
            sorter.refresh();
            removeHref();
          }
        } else if (!tunnels || !peersReponse) {
          main.innerHTML = mainResponse.innerHTML;
        }
      }
    }
    if (sorter) {sorter.refresh();}
    xhrtunnels.send();
  }
  if (refresh) {
    refresh.addEventListener("click", function() {progressx.show();progressx.progress(0.5);updateTunnels();progressx.hide();});
    refresh.addEventListener("mouseover", removeHref);
  }
  onVisible(main, () => {updateTunnels();});
  if (visible === "hidden") {clearInterval(refreshId);}
  window.addEventListener("DOMContentLoaded", progressx.hide(), true);
  document.addEventListener("DOMContentLoaded", initRefresh(), true);
</script>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
</body>
</html>