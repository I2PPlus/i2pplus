/* I2P+ lsCompact.js by dr|z3d */
/* Compact non-debug Leaseset tables, add Signature and Encryption types counters,
/* and implement auto-refresh */
/* License: AGPL3 or later */

import { lsDebug } from "/js/lsDebug.js";
import { onVisible, onHidden } from "/js/onVisible.js";

document.addEventListener("DOMContentLoaded", () => {
  let container = document.querySelector(".leasesets_container");
  let refreshInterval = null;
  let isRefreshing = false;
  let lsCount = 0;
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
    const summary = document.getElementById("leasesetsummary") || document.getElementById("leasesetdebug");
    if (!summary) return;

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
    sigCell.innerHTML = "<b>${translateSigTypes}:</b>";

    const sigText = Object.entries(signatureCounts)
      .sort()
      .map(([type, count]) => `<span class=counterLS><span class="lsLabel sigType" title="${translateSigType}">${type} <span class=lsCounter>${count}</span></span></span>`)
      .join(" &nbsp;");
    const sigValueCell = document.createElement("td");
    sigValueCell.innerHTML = sigText;

    const encCell = document.createElement("td");
    encCell.innerHTML = "<b>${translateEncTypes}:</b>";

    const encText = Object.entries(encryptionCounts)
      .sort()
      .map(([type, count]) => `<span class=counterLS><span class="lsLabel encType"  title="${translateEncType}">${type} <span class=lsCounter>${count}</span></span></span>`)
      .join(" &nbsp;");
    const encValueCell = document.createElement("td");
    encValueCell.innerHTML = encText;

    if (!debug) {
      const mergedCell = document.createElement("td");
      mergedCell.colSpan = 4;
      mergedCell.style.textAlign = "center";
      mergedCell.innerHTML = `
        ${sigValueCell.innerHTML}
        <span class=vsep></span>
        &nbsp;${encValueCell.innerHTML}
      `;
      row.appendChild(mergedCell);
    } else {
      row.appendChild(sigCell);
      row.appendChild(sigValueCell);
      row.appendChild(encCell);
      row.appendChild(encValueCell);
    }

    tbody.appendChild(row);

    const lsLocalCount = document.getElementById("lsLocalCount");
    if (lsLocalCount) {
      const leaseCountsRow = document.getElementById("leasesetCounts");
      if (leaseCountsRow) {
        const counters = leaseCountsRow.querySelectorAll(".lsCounter.sets");
        if (counters.length > 0) {
          const totalCount = Array.from(counters).reduce((sum, el) => {
            const val = parseInt(el.textContent);
            return val ? sum + val : sum;
          }, 0);
          lsLocalCount.textContent = totalCount;
        }
      }
    }
    summary.removeAttribute("hidden");
  }

  function styleLabels() {
    const style = document.createElement("style");
    style.type = "text/css";
    style.id = "lsLabels";
    style.textContent = `
      .counterLS{padding:2px;display:inline-block;vertical-align:middle;border:var(--border_soft);box-shadow:var(--highlight);background:var(--badge)}
      .counterLS .published{background:var(--globe) no-repeat 4px center/14px}
      .counterLS .unpublished{background:var(--hardhat) no-repeat 4px center/14px}
      .counterLS .clientB32{background:var(--ping) no-repeat 4px center/14px}
      .counterLS .clientHostname{background:var(--link) no-repeat 4px center/14px}
      .lsCounter,.lsBadge{margin:-3px -1px -3px 0;padding:2px 6px;min-width:28px;display:inline-block;text-align:center;font-weight:700}
      .lsLabel{padding:1px 4px 1px 22px;display:inline-block;font-weight:500}
      .lsLabel.encType,.lsLabel.sigType{margin-right:-4px}
      .lsLabel.encType .lsCounter,.lsLabel.sigType .lsCounter{margin-left:4px}
      .lsLabel.encType{background:var(--crypto) no-repeat 4px center/14px}
      .lsLabel.sigType{background:var(--lock) no-repeat 4px center/14px}`;
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
          <span class=counterLS title="${translate_localPublic}"><span class="lsLabel published">Published</span> <span class="lsCounter sets">${publishedCount}</span></span> &nbsp;
          <span class=counterLS title="${translate_localPrivate}"><span class="lsLabel unpublished">Unpublished</span> <span class="lsCounter sets">${unpublishedCount}</span></span> &nbsp;
          <span class=counterLS title="${translate_requestedLS}"><span class="lsLabel clientHostname">Client (hostname)</span> <span class="lsCounter sets">${knownClientCount}</span></span> &nbsp;
          <span class=counterLS title="${translate_requestedLS}"><span class="lsLabel clientB32">Client (b32)</span> <span class="lsCounter sets">${clientCount}</span></span>
        `;
        row.appendChild(cell);
        tbody.appendChild(row);
        lsCount = Array.from(document.querySelectorAll("#leasesetCounts span.lsCounter")).reduce((sum, el) => sum + parseInt(el.textContent) || 0, 0);
        lsLocalCount.textContent = lsCount;
      }
    }
  }

  function refreshLeasesets() {
    if (!container) return;
    progressx.show(theme); progressx.progress(.7);
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
        if (!lsLabels) { styleLabels(); }
        if (!debug) { sortLeasesets(); }
        progressx.progress(1);
        setTimeout(() => { progressx.hide(); }, 100);
      })
      .catch(() => { });
  }

  function startRefresh() {
    if (isRefreshing) { return; }
    isRefreshing = true;
    refreshInterval = setInterval(() => { requestAnimationFrame(refreshLeasesets); }, 15000);
  }

  function stopRefresh() {
    if (!isRefreshing) { return; }
    isRefreshing = false;
    clearInterval(refreshInterval);
    refreshInterval = null;
    progressx.hide();
  }

  function initLSCompact() {
    startRefresh();
    progressx.show();
    compact();
    lsDebug();
    countTypes();
    styleLabels();
    if (!debug) { sortLeasesets(); }
    progressx.hide();
  }

  initLSCompact();

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") { stopRefresh(); }
    else { initLSCompact(); }
  });

});