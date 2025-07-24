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
    const debugTable = document.getElementById("leasesetdebug");
    if (!debugTable) return;

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

    const tbody = debugTable.querySelector("tbody") || debugTable;
    const existingRow = tbody.querySelector("#sigEncCount");
    if (existingRow) existingRow.remove();

    const row = document.createElement("tr");
    row.id = "sigEncCount";

    const sigCell = document.createElement("td");
    sigCell.innerHTML = "<b>Signature types</b>";

    const sigText = Object.entries(signatureCounts)
      .sort()
      .map(([type, count]) => `&bullet; ${type} (${count})`)
      .join(" &nbsp;");
    const sigValueCell = document.createElement("td");
    sigValueCell.innerHTML = sigText;

    const encCell = document.createElement("td");
    encCell.innerHTML = "<b>Encryption types</b>";

    const encText = Object.entries(encryptionCounts)
      .sort()
      .map(([type, count]) => `&bullet; ${type} (${count})`)
      .join(" &nbsp;");
    const encValueCell = document.createElement("td");
    encValueCell.innerHTML = encText;

    row.appendChild(sigCell);
    row.appendChild(sigValueCell);
    row.appendChild(encCell);
    row.appendChild(encValueCell);
    tbody.appendChild(row);

    document.querySelectorAll("#leasesetdebug b").forEach(b => {
      b.textContent = b.textContent.replace(/:\s*$/g, "");
    });
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
  onHidden(document.body, () => {stopRefresh();});
  onVisible(document.body, () => {startRefresh();});

});