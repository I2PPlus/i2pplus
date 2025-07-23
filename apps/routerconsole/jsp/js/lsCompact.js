/* I2P+ lsCompact.js by dr|z3d */
/* Compact non-debug Leaseset tables and implement auto-refresh */
/* License: AGPL3 or later */

import {lsDebug} from "/js/lsDebug.js";

document.addEventListener("DOMContentLoaded", () => {
  let container = document.querySelector(".leasesets_container");

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

  function refreshLeasesets() {
    if (!container) return;
    const url = window.location.href;
    fetch(url)
      .then(response => {
        if (!response.ok) throw new Error("Network response was not ok");
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
        compact();
        lsDebug();
      })
      .catch(() => {});
  }

  compact();
  lsDebug();
  setInterval(() => {requestAnimationFrame(refreshLeasesets)}, 15000);
});