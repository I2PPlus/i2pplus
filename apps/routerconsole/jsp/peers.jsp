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
<script nonce=<%=cspNonce%> type=text/javascript>progressx.show();progressx.progress(0.5);</script>
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
    } else if (req.equals("ssudebug")) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; SSU (<%=intl._t("Advanced")%>)</h1>
<%
    }
%>
<div class=main id=peers>
<%
    peerHelper.storeWriter(out);
    if (allowIFrame)
        peerHelper.allowGraphical();
%>
<jsp:getProperty name="peerHelper" property="peerSummary" />
</div>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js type=text/javascript></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js type=text/javascript></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.natural.js type=text/javascript></script>
<script nonce=<%=cspNonce%> src=/js/lazyload.js type=text/javascript></script>
<script nonce=<%=cspNonce%> type=text/javascript>
  var autorefresh = document.getElementById("autorefresh");
  var ntcpConn = document.getElementById("ntcpconnections");
  var ntcpH3 = document.getElementById("ntcpcon");
  var ntcpTfoot = document.querySelector("#ntcpconnections tfoot");
  var peersNTCP = document.getElementById("peersNTCP");
  var peersSSU = document.getElementById("peersSSU");
  var refreshPeersId = setInterval(refreshPeers, 60000);
  var ssuConn = document.getElementById("udpconnections");
  var ssuH3 = document.getElementById("udpcon");
  var ssuTfoot = document.querySelector("#udpconnections tfoot");
  var summary = document.getElementById("transportSummary");
  var url = document.location;
  var path = location.pathname;
  var query = location.search;
  var queryParams = new URL(url.href).searchParams;
  var xhrPeers = new XMLHttpRequest();

  if (peersNTCP && typeof sorterNTCP === "undefined") {
    var sorterNTCP = new Tablesort((ntcpConn), {descending: true});
  }
  if (peersSSU && typeof sorterSSU === "undefined") {
    var sorterSSU = new Tablesort((ssuConn), {descending: true});
  }

  function initRefresh() {
    addSortListeners();
    //refreshPeers();
    lazyload();
  }

  function addSortListeners() {
    if (ntcpConn) {
      ntcpConn.addEventListener('beforeSort', function() {progressx.show();progressx.progress(0.5);}, true);
      ntcpConn.addEventListener('afterSort', function() {progressx.hide();}, true);
    }
    if (ssuConn) {
      ssuConn.addEventListener('beforeSort', function() {progressx.show();progressx.progress(0.5);}, true);
      ssuConn.addEventListener('afterSort', function() {progressx.hide();}, true);
    }
  }

  function countTiers() {
    if (ssuTfoot || ntcpTfoot) {
      var tierL = document.querySelectorAll(".rbw.L");
      var tierM = document.querySelectorAll(".rbw.M");
      var tierN = document.querySelectorAll(".rbw.N");
      var tierO = document.querySelectorAll(".rbw.O");
      var tierP = document.querySelectorAll(".rbw.P");
      var tierX = document.querySelectorAll(".rbw.X");
      var isFF = document.querySelectorAll(".isff");
      var isU = document.querySelectorAll(".isU");
      var countL = tierL !== null ? tierL.length : "0";
      var countM = tierM !== null ? tierM.length : "0";
      var countN = tierN !== null ? tierN.length : "0";
      var countO = tierO !== null ? tierO.length : "0";
      var countP = tierP !== null ? tierP.length : "0";
      var countX = tierX !== null ? tierX.length : "0";
      var countFF = isFF !== null ? isFF.length : "0";
      var countU = isU !== null ? isU.length : "0";
      var peerFoot = document.querySelector("tfoot .peer");
      var pCount = document.getElementById("peerCounter");
      var topCount = document.getElementById("topCount");
      var counter = "<table><tr>" +
                    (countL > 0 ? "<td class=rbw>L<span> " + countL + "</span></td>" : "") +
                    (countM > 0 ? "<td class=rbw>M<span> " + countM + "</span></td>" : "") +
                    (countN > 0 ? "<td class=rbw>N<span> " + countN + "</span></td>" : "") +
                    (countO > 0 ? "<td class=rbw>O<span> " + countO + "</span></td>" : "") +
                    (countP > 0 ? "<td class=rbw>P<span> " + countP + "</span></td>" : "") +
                    (countX > 0 ? "<td class=rbw>X<span> " + countX + "</span></td>" : "") +
                    (countU > 0 ? "<td class=rbw id=u>U<span> " + countU + "</span></td>" : "") +
                    (countFF > 0 ? "<td class=rbw id=ff>F<span> " + countFF + "</span></td>" : "") +
                    "</tr></table>";
      topCount.innerHTML = counter;
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
        if (ssuConn) {
          if (peersSSU) {
            var peersSSUResponse = xhrPeers.responseXML.getElementById("peersSSU");
            var ssuH3Response = xhrPeers.responseXML.getElementById("udpcon");
            var ssuTfootResponse = xhrPeers.responseXML.querySelector("#udpconnections tfoot");
            if (peersSSUResponse !== null && peersSSU.innerHTML !== peersSSUResponse.innerHTML) {
              ssuH3.innerHTML = ssuH3Response.innerHTML;
              peersSSU.innerHTML = peersSSUResponse.innerHTML;
              if (ssuTfoot !== null) {
                ssuTfoot.innerHTML = ssuTfootResponse.innerHTML;
                countTiers();
              }
              sorterSSU.refresh();
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
            var ntcpH3Response = xhrPeers.responseXML.getElementById("ntcpcon");
            var ntcpTfootResponse = xhrPeers.responseXML.querySelector("#ntcpconnections tfoot");
            if (peersNTCPResponse !== null && peersNTCP.innerHTML !== peersNTCPResponse.innerHTML) {
              ntcpH3.innerHTML = ntcpH3Response.innerHTML;
              peersNTCP.innerHTML = peersNTCPResponse.innerHTML;
              if (ntcpTfoot !== null) {
                ntcpTfoot.innerHTML = ntcpTfootResponse.innerHTML;
                countTiers();
              }
              sorterNTCP.refresh();
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
    if (ntcpConn || ssuConn) {
      addSortListeners();
      if (peersNTCP) {sorterNTCP.refresh();}
      if (peersSSU) {sorterSSU.refresh();}
    }
    progressx.hide();
    xhrPeers.send();
  }

  progressx.hide();
  xhrPeers.addEventListener("load", countTiers(), true);
  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    if (peersNTCP !== null) {ntcpConn.addEventListener("mouseover", lazyload());}
    if (peersSSU !== null) {ssuConn.addEventListener("mouseover", lazyload());}
  });
</script>
</body>
</html>