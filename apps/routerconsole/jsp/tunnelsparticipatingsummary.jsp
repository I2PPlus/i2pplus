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
<html lang="<%=lang%>" id=participatingTunnels>
<head>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
<%=intl.title("Transit Tunnels by Peer")%>
<link href=/themes/console/tablesort.css rel=stylesheet type=text/css>
</head>
<body id=transitbypeer>
<script nonce="<%=cspNonce%>" type=text/javascript>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<h1 class=netwrk><%=intl._t("Transit Tunnels by Peer")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="<%=intl._t("Locally hosted tunnels (exploratory and client)")%>"><a href="/tunnels">Local</a></span>
<span class=tab><a href="/tunnelsparticipating"><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
<span class=tab><a href="/tunnelsparticipatingfastest"><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</a></span>
<span class=tab2 title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><%=intl._t("Transit by Peer")%></span>
<span class=tab><a href="/tunnelpeercount"><%=intl._t("All Tunnels by Peer")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TransitSummaryHelper" id="transitSummaryHelper" scope="request" />
<jsp:setProperty name="transitSummaryHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    transitSummaryHelper.storeWriter(out);
%>
<jsp:getProperty name="transitSummaryHelper" property="transitSummary" />
</div>
<script nonce="<%=cspNonce%>" src=/js/lazyload.js type=text/javascript></script>
<script nonce="<%=cspNonce%>" src=/js/tablesort/tablesort.js type=text/javascript></script>
<script nonce="<%=cspNonce%>" src=/js/tablesort/tablesort.dotsep.js type=text/javascript></script>
<script nonce="<%=cspNonce%>" src=/js/tablesort/tablesort.natural.js type=text/javascript></script>
<script nonce="<%=cspNonce%>" src=/js/tablesort/tablesort.number.js type=text/javascript></script>
<script nonce="<%=cspNonce%>" type=module>
  import {onVisible} from "/js/onVisible.js";
  var main = document.getElementById("tunnels");
  var peers = document.getElementById("transitPeers");
  var summary = document.getElementById("transitSummary");
  var visible = document.visibilityState;
  var xhrtunnels = new XMLHttpRequest();
  if (summary) {var sorter = new Tablesort((summary), {descending: true});}
  function initRefresh() {
    if (refreshId) {
      clearInterval(refreshId);
    }
    var refreshId = setInterval(updateTunnels, 15000);
    if (summary && sorter === null) {
      var sorter = new Tablesort((summary), {descending: true});
    }
    addSortListeners();
    updateTunnels();
  }
  function addSortListeners() {
    if (summary) {
      summary.addEventListener('beforeSort', function() {progressx.show();progressx.progress(0.5);}, true);
      summary.addEventListener('afterSort', function() {progressx.hide();}, true);
    }
  }
  function updateTunnels() {
    xhrtunnels.open('GET', '/tunnelsparticipatingsummary?t=' + new Date().getTime(), true);
    xhrtunnels.responseType = "document";
    xhrtunnels.onreadystatechange = function () {
      if (xhrtunnels.readyState === 4 && xhrtunnels.status === 200) {
        var mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
        var peersResponse = xhrtunnels.responseXML.getElementById("transitPeers");
        if (peersResponse) {
        addSortListeners();
          if (peers && peers !== peersResponse) {
            peers.innerHTML = peersResponse.innerHTML;
            sorter.refresh();
          }
        } else if (!summary || !peersReponse) {
          main.innerHTML = mainResponse.innerHTML;
        }
      }
    }
    if (sorter) {sorter.refresh();}
    xhrtunnels.send();
  }
  onVisible(main, () => {updateTunnels();});
  if (visible === "hidden") {clearInterval(refreshId);}
  window.addEventListener("DOMContentLoaded", progressx.hide(), true);
  document.addEventListener("DOMContentLoaded", initRefresh(), true);
</script>
</body>
</html>