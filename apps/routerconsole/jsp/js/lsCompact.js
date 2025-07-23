/* I2P+ lsCompact.js by dr|z3d */
/* Compact non-debug Leaseset tables */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", () => {
  const container = document.querySelector(".leasesets_container");
  if (!container || document.getElementById("leasesetdebug")) {return;}
  document.querySelectorAll("table.leaseset").forEach(table => {
    const expiry = table.querySelector(".expiry");
    const ekeys = table.querySelectorAll(".ekey");

    if (expiry && ekeys.length > 0) {
      ekeys.forEach((ekey, index) => {
        expiry.insertAdjacentElement("afterend", ekey);
        if (index < ekeys.length - 1) {
          const separator = document.createElement("span");
          separator.textContent = " ";
          expiry.insertAdjacentElement("afterend", separator);
        }
      });

      const oldTr = table.querySelector("tr.ekeys");
      if (oldTr !== null) {oldTr.remove();}
    }
  });
});