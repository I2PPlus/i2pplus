/* I2P+ lsCompact.js by dr|z3d */
/* Compact non-debug Leaseset tables, add Signature and Encryption types counters,
/* and implement auto-refresh */
/* License: AGPL3 or later */

import {lsDebug} from "/js/lsDebug.js";
import {onVisible, onHidden} from "/js/onVisible.js";

document.addEventListener("DOMContentLoaded", () => {
  let container = document.querySelector(".leasesets_container");
  let refreshInterval = null;
  let isRefreshing = false;
  const debug = document.getElementById("leasesetdebug");

  function compact() {
    if (!container || document.getElementById("leasesetdebug")) return;
    document.querySelectorAll("table.leaseset").forEach(table => {
      const expiry = table.querySelector(".expiry");
      const ekeys = Array.from(table.querySelectorAll(".ekey"));
      if (expiry && ekeys.length > 0) {
        ekeys.forEach(el => el.remove());
        ekeys.forEach(ekey => expiry.insertAdjacentElement("afterend", ekey));
        const oldTr = table.querySelector("tr.ekeys");
        if (oldTr) oldTr.remove();
      }
    });
  }

  function countTypes() {
    const summary = document.getElementById("leasesetdebug") || document.getElementById("leasesetsummary");
    if (!summary) return;

    const localSummary = document.querySelector("#leasesetsummary.local");
    if (localSummary) {
      localSummary.removeAttribute("hidden");
      const lsLocalCount = document.getElementById("lsLocalCount");
      const count = document.querySelectorAll(".leaseset").length;
      lsLocalCount.textContent = count;
    }

    const signatureCounts = {};
    document.querySelectorAll("span.nowrap.stype").forEach(span => {
      if (!span.classList.contains("bullet")) {
        const boldElement = span.querySelector("b");
        const signatureSpan = boldElement?.nextElementSibling;
        if (signatureSpan) {
          const signatureType = signatureSpan.textContent.trim().split(/\s+/)[0];
          signatureCounts[signatureType] = (signatureCounts[signatureType] || 0) + 1;
        }
      }
    });

    const encryptionCounts = {};
    document.querySelectorAll("span.nowrap.ekey").forEach(span => {
      if (!span.classList.contains("bullet")) {
        const boldElement = span.querySelector("b");
        const encryptionSpan = boldElement?.nextElementSibling;
        if (encryptionSpan) {
          const encryptionType = encryptionSpan.textContent.trim().split(/\s+/)[0];
          encryptionCounts[encryptionType] = (encryptionCounts[encryptionType] || 0) + 1;
        }
      }
    });

    const tbody = summary.querySelector("tbody") || summary;
    const existingRow = tbody.querySelector("#sigEncCount");
    if (existingRow) existingRow.remove();

    const row = document.createElement("tr");
    row.id = "sigEncCount";

    const sigCell = document.createElement("td");
    sigCell.innerHTML = "<b>Signature types</b>";

    const sigText = Object.entries(signatureCounts)
      .sort()
      .map(([type, count]) => `&bullet; <span class=lsLabel>${type}</span> (${count})`)
      .join(" &nbsp;");
    const sigValueCell = document.createElement("td");
    sigValueCell.innerHTML = sigText;

    const encCell = document.createElement("td");
    encCell.innerHTML = "<b>Encryption types</b>";

    const encText = Object.entries(encryptionCounts)
      .sort()
      .map(([type, count]) => `&bullet; <span class=lsLabel>${type}</span> (${count})`)
      .join(" &nbsp;");
    const encValueCell = document.createElement("td");
    encValueCell.innerHTML = encText;

    row.appendChild(sigCell);
    row.appendChild(sigValueCell);
    row.appendChild(encCell);
    row.appendChild(encValueCell);
    tbody.appendChild(row);
  }

  function styleLabels() {
    const style = document.createElement("style");
    style.type = "text/css";
    style.id = "lsLabels";
    style.textContent = ".lsLabel{font-weight:500}";
    document.head.appendChild(style);
  }

  function sortLeasesets() {
    document.querySelectorAll("table.leaseset").forEach(table => {
      const lastTh = table.querySelector("th:last-child");
      let sortPriority = 4;
      let sortText = "";
      if (lastTh) {
        sortText = lastTh.textContent.trim().toLowerCase();
        const span = lastTh.querySelector("span.lsdest");
        if (span) {
          sortPriority = span.classList.contains("published") ? 0 : 1;
        } else {
          const a = lastTh.querySelector("a");
          if (a) {
            sortPriority = a.classList.contains("destlink") ? 2 : 3;
          }
        }
      }
      table.dataset.sortPriority = sortPriority;
      table.dataset.sortText = sortText;
    });

    const tables = Array.from(document.querySelectorAll("table.leaseset"));
    tables.sort((a, b) => {
      const prioA = parseInt(a.dataset.sortPriority);
      const prioB = parseInt(b.dataset.sortPriority);
      if (prioA !== prioB) return prioA - prioB;
      return a.dataset.sortText.localeCompare(b.dataset.sortText);
    });

    tables.forEach(table => {
      if (table.parentNode) {
        table.parentNode.appendChild(table);
      }
    });

    if (window.location.search.includes("l=3")) {
      const summaryTable = document.getElementById("leasesetsummary");
      const publishedCount = document.querySelectorAll("table.leaseset th:last-child span.lsdest.published").length;
      const unpublishedCount = document.querySelectorAll("table.leaseset th:last-child span.lsdest:not(.published)").length;
      const knownClientCount = document.querySelectorAll("table.leaseset th:last-child a.destlink").length;
      const clientCount = document.querySelectorAll("table.leaseset th:last-child a:not(.destlink)").length;

      if (summaryTable) {
        const tbody = summaryTable.querySelector("tbody") || summaryTable;
        const existingRow = tbody.querySelector("#leasesetCounts");
        if (existingRow) existingRow.remove();

        const row = document.createElement("tr");
        row.id = "leasesetCounts";

        const cell = document.createElement("td");
        const cell_r = document.createElement("td");
        cell.colSpan = 4;
        cell.style.textAlign = "center";
        cell.innerHTML = `
          &bullet;&nbsp; <span class=lsLabel>Published:</span> ${publishedCount} &nbsp;
          &bullet;&nbsp; <span class=lsLabel>Unpublished:</span> ${unpublishedCount} &nbsp;
          &bullet;&nbsp; <span class=lsLabel>Client (hostname):</span> ${knownClientCount} &nbsp;
          &bullet;&nbsp; <span class=lsLabel>Client (b32):</span> ${clientCount}
        `;
        row.appendChild(cell);
        tbody.appendChild(row);
      }
    }
  }

  function refreshLeasesets() {
    if (!container) return;
    progressx.show(theme);progressx.progress(.7);
    const url = window.location.href;
    fetch(url)
      .then(response => {
        if (!response.ok) throw new Error("Network response was not ok");
        progressx.progress(.8);
        return response.text();
      })
      .then(html => {
        const lsLabels = document.getElementById("lsLabels");
        const temp = document.createElement("div");
        temp.innerHTML = html;
        const newContainer = temp.querySelector(".leasesets_container");
        if (!newContainer) return;
        const oldContainer = document.querySelector(".leasesets_container");
        if (!oldContainer) return;
        oldContainer.parentNode.replaceChild(newContainer, oldContainer);
        container = newContainer;
        progressx.progress(.9)
        compact();
        lsDebug();
        countTypes();
        if (!lsLabels) {styleLabels();}
        if (!debug) {sortLeasesets();}
        progressx.progress(1);
        setTimeout(() => {progressx.hide();}, 100);
      })
      .catch(() => {});
  }

  function startRefresh() {
    if (isRefreshing) {return;}
    isRefreshing = true;
    refreshInterval = setInterval(() => {requestAnimationFrame(refreshLeasesets);}, 15000);
  }

  function stopRefresh() {
    if (!isRefreshing) {return;}
    isRefreshing = false;
    clearInterval(refreshInterval);
    refreshInterval = null;
  }

  compact();
  lsDebug();
  countTypes();
  styleLabels();
  if (!debug) {sortLeasesets();}
  onHidden(document.body, () => {stopRefresh();});
  onVisible(document.body, () => {startRefresh();});

});