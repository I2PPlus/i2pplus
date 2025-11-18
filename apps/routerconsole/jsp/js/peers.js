/* I2P+ peers.js by dr|z3d */
/* Handle refresh, table sorts, and tier counts on /peers pages */
/* License: AGPL3 or later */

import { refreshElements } from '/js/refreshElements.js';

(function () {
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
  const REFRESH_INTERVAL = summary ? 5 * 1000 : 10 * 1000;

  let sorterNTCP, sorterSSU;

  if (peersNTCP && typeof sorterNTCP === "undefined") {
    sorterNTCP = new Tablesort(ntcpConn, { descending: true });
  }

  if (peersSSU && typeof sorterSSU === "undefined") {
    sorterSSU = new Tablesort(ssuConn, { descending: true });
  }

  function initRefresh() {
    addSortListeners();
  }

  function addSortListeners() {
    if (ntcpConn) {
      ntcpConn.addEventListener("beforeSort", function () {
        progressx.show(theme);
        progressx.progress(0.5);
      });
      ntcpConn.addEventListener("afterSort", function () {
        progressx.hide();
      });
    }

    if (ssuConn) {
      ssuConn.addEventListener("beforeSort", function () {
        progressx.show(theme);
        progressx.progress(0.5);
      });
      ssuConn.addEventListener("afterSort", function () {
        progressx.hide();
      });
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

      if (topCount) {
        topCount.innerHTML = counter;
      }
    }
  }

  function setupRefresh(selector) {
    const fetchUrl = queryParams.has("transport") ? path + query : "/peers";
    refreshElements(selector, fetchUrl, REFRESH_INTERVAL);
  }

  progressx.hide();

  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    countTiers();

    if (peersNTCP) {
      setupRefresh("#peersNTCP, #ntcpcon, #ntcpconnections tfoot");
    }

    if (peersSSU) {
      setupRefresh("#peersSSU, #udpcon, #udpconnections tfoot");
    }

    document.addEventListener("refreshComplete", () => {
      if (sorterNTCP) sorterNTCP.refresh();
      else if (sorterSSU) sorterSSU.refresh();
      countTiers();
    });
  });
})();