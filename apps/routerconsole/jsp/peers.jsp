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
<%=intl.title("peer connections")%>
<link href=/themes/console/tablesort.css rel=stylesheet type=text/css>
</head>
<body>
<script nonce="<%=cspNonce%>" type=text/javascript>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.PeerHelper" id="peerHelper" scope="request" />
<jsp:setProperty name="peerHelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="peerHelper" property="urlBase" value="peers.jsp" />
<jsp:setProperty name="peerHelper" property="transport" value="<%=request.getParameter(\"transport\")%>" />
<jsp:setProperty name="peerHelper" property="sort" value="<%=request.getParameter(\"sort\") != null ? request.getParameter(\"sort\") : \"\"%>" />
<%
    String req = request.getParameter("transport");
    if (req == null) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%></h1>
<%
    } else if (req.equals("ntcp")) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; NTCP</h1>
<%
    } else if (req.equals("ssu")) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; SSU</h1>
<%
    }
%>
<div class=main id="peers">
<%
    peerHelper.storeWriter(out);
    if (allowIFrame)
        peerHelper.allowGraphical();
%>
<jsp:getProperty name="peerHelper" property="peerSummary" />
</div>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.natural.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/lazyload.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" type=text/javascript>
  var autorefresh = document.getElementById("autorefresh");
  var ntcpConn = document.getElementById("ntcpconnections");
  var peersNTCP = document.getElementById("peersNTCP");
  var peersSSU = document.getElementById("peersSSU");
  var refreshPeersId = setInterval(refreshPeers, 15000);
  var sorterNTCP;
  var sorterSSU;
  var ssuConn = document.getElementById("udpconnections");
  var summary = document.getElementById("transportSummary");
  var url = document.location;
  var path = location.pathname;
  var query = location.search;
  var queryParams = new URL(url.href).searchParams;
  var xhrPeers = new XMLHttpRequest();

  function initRefresh() {
    addSortListeners();
    refreshPeers();
  }

  function addSortListeners() {
    if (ntcpConn && !sorterNTCP) {
      var sorterNTCP = new Tablesort((ntcpConn), {descending: true});
      ntcpConn.addEventListener('beforeSort', function() {progressx.show();progressx.progress(0.5);}, true);
      ntcpConn.addEventListener('afterSort', function() {progressx.hide();}, true);
    } else if (ssuConn && !sorterSSU) {
      var sorterSSU = new Tablesort((ssuConn), {descending: true});
      ssuConn.addEventListener('beforeSort', function() {progressx.show();progressx.progress(0.5);}, true);
      ssuConn.addEventListener('afterSort', function() {progressx.hide();}, true);
    }
  }

  function refreshPeers() {
    var now = new Date().getTime();
    if (queryParams.has("transport")) {
      xhrPeers.open("GET", path + query + "&t=" + now, true);
    } else {
      xhrPeers.open("GET", "/peers?t=" + now, true);
    }
    xhrPeers.responseType = "document";
    xhrPeers.onreadystatechange = function () {
      if (xhrPeers.readyState === 4 && xhrPeers.status === 200 && autorefresh.checked) {
        addSortListeners();
        if (ssuConn) {
          if (peersSSU) {
            var peersSSUResponse = xhrPeers.responseXML.getElementById("peersSSU");
            if (peersSSUResponse !== null && peersSSU.innerHTML !== peersSSUResponse.innerHTML) {
              peersSSU.innerHTML = peersSSUResponse.innerHTML;
              if (!sorterSSU) {
                var sorterSSU = new Tablesort((ssuConn), {descending: true});
              }
              sorterSSU.refresh();
              lazyload();
            }
          } else {
            var udpResponse = xhrPeers.responseXML.getElementById("udp");
            if (!Object.is(udp.innerHTML, udpResponse.innerHTML)) {
              udp.innerHTML = udpResponse.innerHTML;
            }
          }
        } else if (ntcpConn) {
          if (peersNTCP) {
            var peersNTCPResponse = xhrPeers.responseXML.getElementById("peersNTCP");
            if (peersNTCPResponse !== null && peersNTCP.innerHTML !== peersNTCPResponse.innerHTML) {
              peersNTCP.innerHTML = peersNTCPResponse.innerHTML;
              if (!sorterNTCP) {
                var sorterNTCP = new Tablesort((ntcpConn), {descending: true});
              }
              sorterNTCP.refresh();
              lazyload();
            }
          } else {
            var ntcpResponse = xhrPeers.responseXML.getElementById("ntcp");
            if (!Object.is(ntcp.innerHTML, ntcpResponse.innerHTML)) {
              ntcp.innerHTML = ntcpResponse.innerHTML;
            }
          }
        } else if (summary) {
          var summaryResponse = xhrPeers.responseXML.getElementById("transportSummary");
          if (!Object.is(summary.innerHTML, summaryResponse.innerHTML)) {
            summary.innerHTML = summaryResponse.innerHTML;
          }
        }
      }
    }
    xhrPeers.send();
  }

  document.addEventListener("DOMContentLoaded", () => {
    lazyload();
    initRefresh();
    progressx.hide();
    if (ntcpConn !== null) {ntcpConn.addEventListener("mouseover", lazyload());}
    if (ssuConn !== null) {ssuConn.addEventListener("mouseover", lazyload());}
  });
</script>
</body>
</html>