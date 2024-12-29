(function() {
  "use strict";
  document.addEventListener("DOMContentLoaded", () => {
    let total = 0, started = 0, stopped = 0;
    const header = document.getElementById("pconfig");
    if (!header) return;
    const controlElements = document.querySelectorAll("#pluginconfig td .control.accept, #pluginconfig td .control.stop");

    controlElements.forEach(element => {
      total++;
      element.classList.contains("accept") ? stopped++ : started++;
    });

    if (!document.getElementById("pluginTotals")) {
      const span = document.createElement("span");
      span.id = "pluginTotals";
      span.innerHTML = `<span id="started">${started}</span> <span id="stopped">${stopped}</span>`;
      header.appendChild(span);
    }
  });
})();