/**
 * @module peers
 * @description Handles auto-refresh, table sorting, and bandwidth tier count
 * display for the /peers pages (NTCP, SSU, and summary views).
 * @author dr|z3d
 * @license AGPL3 or later
 */

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

  /**
   * Initializes refresh listeners and table sort event handlers.
   * @function initRefresh
   * @returns {void}
   */
  function initRefresh() {
    addSortListeners();
  }

  /**
   * Adds progress bar handlers for beforeSort/afterSort events on peer tables.
   * @function addSortListeners
   * @returns {void}
   */
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

  /**
   * Counts peers by bandwidth tier (L, M, N, O, P, X) and floodfill status,
   * displaying the counts in the top count element.
   * @function countTiers
   * @returns {void}
   */
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

  /**
   * Sets up periodic element refresh for the given selector.
   * @function setupRefresh
   * @param {string} selector - CSS selector for elements to refresh
   * @returns {void}
   */
  function setupRefresh(selector) {
    const fetchUrl = queryParams.has("transport") ? path + query : "/peers";
    refreshElements(selector, fetchUrl, REFRESH_INTERVAL);
  }

  /**
   * Sets up periodic refresh for the transport summary section.
   * @function setupSummaryRefresh
   * @returns {void}
   */
  function setupSummaryRefresh() {
    if (!summary) {return;}
    refreshElements("#transportSummary", "/peers", REFRESH_INTERVAL);
  }

  progressx.hide();

  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    countTiers();

    if (peersNTCP) {
      setupRefresh("#peersNTCP, #ntcpcon, #ntcpconnections tfoot");
    } else if (peersSSU) {
      setupRefresh("#peersSSU, #udpcon, #udpconnections tfoot");
    } else {
      setupSummaryRefresh();
    }

    document.addEventListener("refreshComplete", () => {
      if (sorterNTCP) { sorterNTCP.refresh(); }
      else if (sorterSSU) { sorterSSU.refresh(); }
      countTiers();
    });
  });
})();