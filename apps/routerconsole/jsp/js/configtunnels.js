/* I2P+ configtunnels.js by dr|z3d */
/* Toggle display of tunnel count badges according to toggle state */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", () => {
  if (theme !== "dark") {return;}
  function showHideTunnelCounts() {
    const table = document.querySelector("#tunnelconfig");
    if (!table) return;
    setupToggles("#tunnelconfig thead", "#tunnelconfig thead+tbody", "table-row-group");
    table.addEventListener("click", function(event) {
      const toggle = event.target.closest(".toggle");
      if (!toggle) return;
      const tIn = toggle.querySelector(".th_title.left .tIn");
      const tOut = toggle.querySelector(".th_title.left .tOut");

      if (toggle.classList.contains("expanded")) {
        if (tIn) tIn.style.display = "none";
        if (tOut) tOut.style.display = "none";
      } else {
        if (tIn) tIn.removeAttribute("style");
        if (tOut) tOut.removeAttribute("style");
      }
    });
  }
  showHideTunnelCounts();
});