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
<%=intl.title("Most Recent Transit Tunnels")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body id=transit>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class=netwrk><%=intl._t("Most Recent Transit Tunnels")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="<%=intl._t("Locally hosted tunnels (exploratory and client)")%>"><a href="/tunnels">Local</a></span>
<span class=tab2><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</span>
<span class=tab><a href="/transitfast"><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</a></span>
<span class=tab title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><a href="/transitsummary"><%=intl._t("Transit by Peer")%></a></span>
<span class=tab><a href="/tunnelpeercount">Tunnel Count by Peer</a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelParticipatingHelper" id="tunnelParticipatingHelper" scope="request" />
<jsp:setProperty name="tunnelParticipatingHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    tunnelParticipatingHelper.storeWriter(out);
%>
<jsp:getProperty name="tunnelParticipatingHelper" property="tunnelsParticipating" />
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.natural.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.dotsep.js></script>
<script nonce=<%=cspNonce%> type=module>
  import {onVisible} from "/js/onVisible.js";
  var main = document.getElementById("tunnels");
  var peers = document.getElementById("transitPeers");
  var tunnels = document.getElementById("allTransit");
  var refresh = document.getElementById("refreshPage");
  var visible = document.visibilityState;
  var xhrtunnels = new XMLHttpRequest();
  if (tunnels) {var sorter = new Tablesort((tunnels), {descending: true});}
  function updateNotes() {
    var notes = document.querySelectorAll(".statusnotes");
    if (notes[0] !== null) {
      var notesResponse = xhrtunnels.responseXML.querySelectorAll(".statusnotes");
      var i;
      for (i = 0; i < notes.length; i++) {
        if (notesResponse[i] !== null) {
          notes[i].innerHTML = notesResponse[i].innerHTML;
        }
      }
    }
  }
  function initRefresh() {
    if (refreshId) {clearInterval(refreshId);}
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
      tunnels.addEventListener('beforeSort', function() {progressx.show(theme);progressx.progress(0.5);});
      tunnels.addEventListener('afterSort', function() {progressx.hide();});
    }
  }
  function updateTunnels() {
    xhrtunnels.open('GET', '/tunnelsparticipating', true);
    xhrtunnels.responseType = "document";
    xhrtunnels.onreadystatechange = function () {
      if (xhrtunnels.readyState === 4 && xhrtunnels.status === 200) {
        var mainResponse = xhrtunnels.responseXML.getElementById("tunnels");
        var peersResponse = xhrtunnels.responseXML.getElementById("transitPeers");
        if (peersResponse) {
          addSortListeners();
          if (peers && peers !== peersResponse) {
            peers.innerHTML = peersResponse.innerHTML;
            updateNotes();
            sorter.refresh();
            removeHref();
          }
        } else if (!tunnels || !peersResponse) {
          main.innerHTML = mainResponse.innerHTML;
        }
      }
    }
    if (sorter) {sorter.refresh();}
    xhrtunnels.send();
  }
  if (refresh) {
    refresh.addEventListener("click", function() {progressx.show(theme);progressx.progress(0.5);updateTunnels();progressx.hide();});
    refresh.addEventListener("mouseenter", removeHref);
  }
  onVisible(main, () => {updateTunnels();});
  if (visible === "hidden") {clearInterval(refreshId);}
  window.addEventListener("DOMContentLoaded", progressx.hide);
  document.addEventListener("DOMContentLoaded", initRefresh);
</script>
</div>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
</body>
</html>