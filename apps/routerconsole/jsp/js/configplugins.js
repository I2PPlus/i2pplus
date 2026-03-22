/**
 * @module configplugins
 * @description Counts and displays total started/stopped plugin counts
 * on the /configplugins page.
 * @license AGPL3 or later
 */

(function() {
  "use strict";
  /** Counts plugin control elements and appends totals to the plugin config header. */
  document.addEventListener("DOMContentLoaded", () => {
    let total = 0, started = 0, stopped = 0;
    const header = document.getElementById("pconfig");
    if (!header) { return; }
    const controlElements = document.querySelectorAll("#pluginconfig td .control.accept, #pluginconfig td .control.stop");

    controlElements.forEach(element => {
      total++;
      if (element.classList.contains("accept")) { stopped++; } else { started++; }
    });

    if (!document.getElementById("pluginTotals")) {
      const span = document.createElement("span");
      span.id = "pluginTotals";
      span.innerHTML = `<span id="started">${started}</span> <span id="stopped">${stopped}</span>`;
      header.appendChild(span);
    }
  });
})();