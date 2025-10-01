/* I2P+ peers.js by dr|z3d */
/* Handle refresh, table sorts, and tier counts on /peers pages */
/* License: AGPL3 or later */

(function() {
  const ntcpConn = document.getElementById("ntcpconnections");
  const ntcpH3 = document.getElementById("ntcpcon");
  const ntcpTfoot = document.querySelector("#ntcpconnections tfoot");
  const peersNTCP = document.getElementById("peersNTCP");
  const peersSSU = document.getElementById("peersSSU");
  const ssuConn = document.getElementById("udpconnections");
  const ssuH3 = document.getElementById("udpcon");
  const ssuTfoot = document.querySelector("#udpconnections tfoot");
  const summary = document.getElementById("transportSummary");
  const url = document.location;
  const path = location.pathname;
  const query = location.search;
  const queryParams = new URL(url.href).searchParams;
  const xhrPeers = new XMLHttpRequest();
  const REFRESH_INTERVAL = summary ? 5*1000 : 30*1000;

  let sorterNTCP, sorterSSU;

  if (peersNTCP && typeof sorterNTCP === "undefined") {
    sorterNTCP = new Tablesort(ntcpConn, {descending: true});
  }
  if (peersSSU && typeof sorterSSU === "undefined") {
    sorterSSU = new Tablesort(ssuConn, {descending: true});
  }

  function initRefresh() {addSortListeners();}

  function addSortListeners() {
    if (ntcpConn) {
      ntcpConn.addEventListener("beforeSort", function() {progressx.show(theme); progressx.progress(0.5);});
      ntcpConn.addEventListener("afterSort", function() {progressx.hide();});
    }
    if (ssuConn) {
      ssuConn.addEventListener("beforeSort", function() {progressx.show(theme); progressx.progress(0.5);});
      ssuConn.addEventListener("afterSort", function() {progressx.hide();});
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
      const countL = tierL.length;
      const countM = tierM.length;
      const countN = tierN.length;
      const countO = tierO.length;
      const countP = tierP.length;
      const countX = tierX.length;
      const countFF = isFF.length;
      const countU = isU.length;
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
      if (topCount) {topCount.innerHTML = counter;}
    }
  }

  function refreshPeers() {
    if (queryParams.has("transport")) {xhrPeers.open("GET", path + query, true);}
    else {xhrPeers.open("GET", "/peers", true);}
    xhrPeers.responseType = "document";
    xhrPeers.onreadystatechange = function () {
      if (xhrPeers.readyState === 4 && xhrPeers.status === 200) {
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
    countTiers();
    setInterval(refreshPeers, REFRESH_INTERVAL);
 });
})();