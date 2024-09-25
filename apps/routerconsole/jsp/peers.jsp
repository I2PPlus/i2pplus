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
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
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
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.natural.js></script>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
<script nonce=<%=cspNonce%>>
  const autorefresh = document.getElementById("autorefresh");
  const ntcpConn = document.getElementById("ntcpconnections");
  const ntcpH3 = document.getElementById("ntcpcon");
  const ntcpTfoot = document.querySelector("#ntcpconnections tfoot");
  const peersNTCP = document.getElementById("peersNTCP");
  const peersSSU = document.getElementById("peersSSU");
  const refreshPeersId = setInterval(refreshPeers, 60000);
  const ssuConn = document.getElementById("udpconnections");
  const ssuH3 = document.getElementById("udpcon");
  const ssuTfoot = document.querySelector("#udpconnections tfoot");
  const summary = document.getElementById("transportSummary");
  const url = document.location;
  const path = location.pathname;
  const query = location.search;
  const queryParams = new URL(url.href).searchParams;
  const xhrPeers = new XMLHttpRequest();

  if (peersNTCP && typeof sorterNTCP === "undefined") {
    const sorterNTCP = new Tablesort((ntcpConn), {descending: true});
  }
  if (peersSSU && typeof sorterSSU === "undefined") {
    const sorterSSU = new Tablesort((ssuConn), {descending: true});
  }

  function initRefresh() {
    addSortListeners();
  }

  function addSortListeners() {
    if (ntcpConn) {
      ntcpConn.addEventListener('beforeSort', function() {progressx.show(theme);progressx.progress(0.5);});
      ntcpConn.addEventListener('afterSort', function() {progressx.hide();});
    }
    if (ssuConn) {
      ssuConn.addEventListener('beforeSort', function() {progressx.show(theme);progressx.progress(0.5);});
      ssuConn.addEventListener('afterSort', function() {progressx.hide();});
    }
  }

  function countTiers() {
    if (ssuTfoot || ntcpTfoot) {
      const tierL = document.querySelectorAll(".rbw.L");
      const tierM = document.querySelectorAll(".rbw.M");
      const tierN = document.querySelectorAll(".rbw.N");
      const tierO = document.querySelectorAll(".rbw.O");
      const tierP = document.querySelectorAll(".rbw.P");
      const tierX = document.querySelectorAll(".rbw.X");
      const isFF = document.querySelectorAll(".isff");
      const isU = document.querySelectorAll(".isU");
      const countL = tierL !== null ? tierL.length : "0";
      const countM = tierM !== null ? tierM.length : "0";
      const countN = tierN !== null ? tierN.length : "0";
      const countO = tierO !== null ? tierO.length : "0";
      const countP = tierP !== null ? tierP.length : "0";
      const countX = tierX !== null ? tierX.length : "0";
      const countFF = isFF !== null ? isFF.length : "0";
      const countU = isU !== null ? isU.length : "0";
      const peerFoot = document.querySelector("tfoot .peer");
      const pCount = document.getElementById("peerCounter");
      const topCount = document.getElementById("topCount");
      const counter = "<table><tr>" +
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
    if (queryParams.has("transport")) {
      xhrPeers.open("GET", path + query, true);
    } else {
      xhrPeers.open("GET", "/peers", true);
    }
    xhrPeers.responseType = "document";
    xhrPeers.onreadystatechange = function () {
      if (xhrPeers.readyState === 4 && xhrPeers.status === 200 && autorefresh.checked) {
        if (ssuConn) {
          if (peersSSU) {
            const peersSSUResponse = xhrPeers.responseXML.getElementById("peersSSU");
            const ssuH3Response = xhrPeers.responseXML.getElementById("udpcon");
            const ssuTfootResponse = xhrPeers.responseXML.querySelector("#udpconnections tfoot");
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
            const udpResponse = xhrPeers.responseXML.getElementById("udp");
            if (!Object.is(udp.innerHTML, udpResponse.innerHTML)) {
              udp.innerHTML = udpResponse.innerHTML;
            }
          }
        } else if (ntcpConn) {
          if (peersNTCP) {
            const peersNTCPResponse = xhrPeers.responseXML.getElementById("peersNTCP");
            const ntcpH3Response = xhrPeers.responseXML.getElementById("ntcpcon");
            const ntcpTfootResponse = xhrPeers.responseXML.querySelector("#ntcpconnections tfoot");
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
            const ntcpResponse = xhrPeers.responseXML.getElementById("ntcp");
            if (!Object.is(ntcp.innerHTML, ntcpResponse.innerHTML)) {
              ntcp.innerHTML = ntcpResponse.innerHTML;
            }
          }
        } else if (summary) {
          const summaryResponse = xhrPeers.responseXML.getElementById("transportSummary");
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
  xhrPeers.addEventListener("load", countTiers, true);
  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    if (peersNTCP !== null) {ntcpConn.addEventListener("mouseenter", lazyload);}
    if (peersSSU !== null) {ssuConn.addEventListener("mouseenter", lazyload);}
  });
</script>
</body>
</html>